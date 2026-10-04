package com.elfmcys.ysm.client.renderer;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;
import java.io.IOException;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Instance-owned GPU geometry; only bone/morph scalars cross the frame boundary. */
public final class MmdGpuDeformer implements AutoCloseable {
    private final MeshAsset.Primitive mesh;
    private final int program;
    private final long storageBytes,storageLimit;
    private final int[] buffers=new int[6];
    private final int jointsStart,modesStart,morphRangesStart,morphEntriesStart;
    private FloatBuffer staging;
    private long frameCapacity;
    private Object frameKey;
    private boolean closed,failed;
    private long dispatches,uploadedBytes;
    public long dispatches(){return dispatches;}
    public long uploadedBytes(){return uploadedBytes;}
    public int outputBuffer(){if(closed)throw new IllegalStateException("GPU deformation closed");return buffers[4];}
    public static boolean available(){return GL.getCapabilities().OpenGL43 && GL11C.glGetInteger(GL43C.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS)>=6;}
    private static int attributeOffset(String name){return switch(name){case "POSITION"->0;case "NORMAL"->3;case "TANGENT"->6;case "TEXCOORD_0"->10;case "_MMD_UV1"->12;case "_MMD_EDGE_SCALE"->16;default->-1;};}
    public MmdGpuDeformer(MeshAsset.Primitive mesh,ReadLimits limits) throws IOException {
        RenderSystem.assertOnRenderThread();this.mesh=Objects.requireNonNull(mesh);
        if(!available() || mesh.skinning()==null || mesh.vertexCount()==0)throw new IllegalArgumentException("GPU MMD deformation unavailable");
        int n=mesh.vertexCount();var skin=mesh.skinning();int[] morphCounts=new int[n];
        for(var entry:mesh.attributes().entrySet()){int offset=attributeOffset(entry.getKey());if(offset<0)continue;
            int expected=switch(entry.getKey()){case "POSITION","NORMAL"->3;case "TANGENT","_MMD_UV1"->4;case "TEXCOORD_0"->2;default->1;};
            if(entry.getValue().components()!=expected)throw new IllegalArgumentException("GPU attribute shape mismatch");}
        for(var morph:mesh.morphs())for(var entry:morph.attributes().entrySet()){
            var base=mesh.attributes().get(entry.getKey());if(base==null || entry.getValue().components()!=base.components() && !(entry.getKey().equals("TANGENT")&&entry.getValue().components()==3))throw new IllegalArgumentException("GPU morph shape mismatch");}
        for(int v=0;v<n;v++){double sum=0;for(int i=skin.offsets().get(v);i<skin.offsets().get(v+1);i++)sum+=skin.weights().get(i);
            if(sum<1e-12 || skin.deforms().get(v)==MeshAsset.Deform.SDEF && Math.abs(sum-1)>.0001)throw new IllegalArgumentException("Invalid GPU skin weights");}
        for(var morph:mesh.morphs())for(var entry:morph.attributes().entrySet())if(attributeOffset(entry.getKey())>=0){var a=entry.getValue();
            for(int row=0;row<a.count();row++){int v=morph.sparse()?morph.vertexIndices().get(row):row;morphCounts[v]=Math.addExact(morphCounts[v],a.components());}}
        int[] morphOffsets=new int[n+1];for(int i=0;i<n;i++)morphOffsets[i+1]=Math.addExact(morphOffsets[i],morphCounts[i]);
        jointsStart=n+1;modesStart=Math.addExact(jointsStart,skin.joints().size());morphRangesStart=Math.addExact(modesStart,n);morphEntriesStart=Math.addExact(morphRangesStart,n+1);
        int integers=Math.addExact(morphEntriesStart,Math.multiplyExact(morphOffsets[n],3));long sourceBytes=(long)n*26*4,structureBytes=(long)integers*4,weightBytes=(long)skin.weights().size()*4,outputBytes=(long)n*17*4;
        storageBytes=sourceBytes+structureBytes+weightBytes+outputBytes+4;storageLimit=limits.maxBytes();
        long maximum=GL32C.glGetInteger64(GL43C.GL_MAX_SHADER_STORAGE_BLOCK_SIZE);
        if(Math.max(Math.max(sourceBytes,structureBytes),Math.max(weightBytes,outputBytes))>maximum || sourceBytes+structureBytes+weightBytes+outputBytes>limits.maxBytes()
                || n>limits.maxElements() || sourceBytes/4>Integer.MAX_VALUE || (n+255L)/256>GL30C.glGetIntegeri(GL43C.GL_MAX_COMPUTE_WORK_GROUP_COUNT,0))throw new IllegalArgumentException("GPU MMD storage budget exceeded");
        int shader=0,createdProgram=0;FloatBuffer source=null,weights=null;IntBuffer structure=null;
        int previousBuffer=GL11C.glGetInteger(GL43C.GL_SHADER_STORAGE_BUFFER_BINDING);
        try {
            String text;try(var stream=MmdGpuDeformer.class.getResourceAsStream("/assets/ysm/shaders/mmd_skinning.comp")){if(stream==null)throw new IOException("Missing MMD compute shader");text=new String(stream.readAllBytes(),StandardCharsets.UTF_8);}
            shader=GL20C.glCreateShader(GL43C.GL_COMPUTE_SHADER);GL20C.glShaderSource(shader,text);GL20C.glCompileShader(shader);
            if(GL20C.glGetShaderi(shader,GL20C.GL_COMPILE_STATUS)==0)throw new IOException(GL20C.glGetShaderInfoLog(shader));
            createdProgram=GL20C.glCreateProgram();GL20C.glAttachShader(createdProgram,shader);GL20C.glLinkProgram(createdProgram);
            if(GL20C.glGetProgrami(createdProgram,GL20C.GL_LINK_STATUS)==0)throw new IOException(GL20C.glGetProgramInfoLog(createdProgram));
            for(int i=0;i<buffers.length;i++){buffers[i]=GL15C.glGenBuffers();if(buffers[i]==0)throw new IOException("GPU deformation buffer allocation failed");}
            source=MemoryUtil.memCallocFloat(n*26);structure=MemoryUtil.memAllocInt(integers);weights=MemoryUtil.memAllocFloat(skin.weights().size());
            for(var entry:mesh.attributes().entrySet()){int offset=attributeOffset(entry.getKey());if(offset<0)continue;var a=entry.getValue();for(int v=0;v<n;v++)for(int k=0;k<a.components();k++)source.put(v*26+offset+k,a.values().get(v*a.components()+k));}
            for(int v=0;v<n;v++){if(skin.sdef().size()>0)for(int k=0;k<9;k++)source.put(v*26+17+k,skin.sdef().get(v*9+k));structure.put(v,skin.offsets().get(v));structure.put(modesStart+v,skin.deforms().get(v).ordinal());}
            structure.put(n,skin.offsets().get(n));for(int i=0;i<skin.joints().size();i++){structure.put(jointsStart+i,skin.joints().get(i));weights.put(i,skin.weights().get(i));}
            for(int i=0;i<=n;i++)structure.put(morphRangesStart+i,morphOffsets[i]);int[] cursor=morphOffsets.clone();
            for(int target=0;target<mesh.morphs().size();target++){var morph=mesh.morphs().get(target);for(var entry:morph.attributes().entrySet()){int offset=attributeOffset(entry.getKey());if(offset<0)continue;var a=entry.getValue();
                for(int row=0;row<a.count();row++){int v=morph.sparse()?morph.vertexIndices().get(row):row;for(int k=0;k<a.components();k++){int at=morphEntriesStart+cursor[v]++*3;structure.put(at,target);structure.put(at+1,offset+k);structure.put(at+2,Float.floatToRawIntBits(a.values().get(row*a.components()+k)));}}}}
            upload(buffers[0],source,GL15C.GL_STATIC_DRAW);upload(buffers[1],structure,GL15C.GL_STATIC_DRAW);upload(buffers[2],weights,GL15C.GL_STATIC_DRAW);
            GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER,buffers[4]);GL15C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER,outputBytes,GL15C.GL_DYNAMIC_DRAW);
            GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER,buffers[5]);GL15C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER,4L,GL15C.GL_DYNAMIC_DRAW);
            if(GL11C.glGetError()!=GL11C.GL_NO_ERROR)throw new IOException("GPU deformation initialization failed");
        }catch(IOException|RuntimeException|Error error){if(createdProgram!=0)GL20C.glDeleteProgram(createdProgram);for(int b:buffers)if(b!=0)GL15C.glDeleteBuffers(b);throw error;}
        finally {if(shader!=0)GL20C.glDeleteShader(shader);if(source!=null)MemoryUtil.memFree(source);if(structure!=null)MemoryUtil.memFree(structure);if(weights!=null)MemoryUtil.memFree(weights);GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER,previousBuffer);}
        program=createdProgram;
    }
    private static void upload(int buffer,Buffer data,int usage){GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER,buffer);if(data instanceof FloatBuffer f)GL15C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER,f,usage);else GL15C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER,(IntBuffer)data,usage);}
    public boolean update(Object key,List<Matrix4> palette,FloatData morphWeights){
        RenderSystem.assertOnRenderThread();Objects.requireNonNull(key);if(closed)throw new IllegalStateException("GPU deformation closed");if(failed)return false;if(frameKey==key)return true;
        if(morphWeights.size()!=mesh.morphs().size())throw new IllegalArgumentException("GPU morph weight layout mismatch");
        var skin=mesh.skinning();for(var matrix:palette)if(!matrix.isAffine())throw new IllegalArgumentException("GPU palette must be affine");
        for(int v=0;v<mesh.vertexCount();v++)for(int i=skin.offsets().get(v);i<skin.offsets().get(v+1);i++){
            int joint=skin.joints().get(i);if(skin.weights().get(i)!=0 && (joint<0||joint>=palette.size()) || skin.deforms().get(v)==MeshAsset.Deform.SDEF && joint>=palette.size())throw new IllegalArgumentException("GPU joint outside palette");}
        int length=Math.addExact(Math.multiplyExact(palette.size(),16),morphWeights.size());
        if(palette.size()>65536 || (long)length*4>GL32C.glGetInteger64(GL43C.GL_MAX_SHADER_STORAGE_BLOCK_SIZE) || storageBytes+(long)length*4>storageLimit)throw new IllegalArgumentException("GPU frame budget exceeded");
        if(staging==null||staging.capacity()<length){var next=MemoryUtil.memAllocFloat(length);if(staging!=null)MemoryUtil.memFree(staging);staging=next;}
        staging.clear();staging.limit(length);for(var matrix:palette)for(int c=0;c<4;c++)for(int r=0;r<4;r++)staging.put(matrix.get(c,r));for(int i=0;i<morphWeights.size();i++)staging.put(morphWeights.get(i));staging.flip();
        int oldProgram=GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM),oldBuffer=GL11C.glGetInteger(GL43C.GL_SHADER_STORAGE_BUFFER_BINDING);
        int[] previous=new int[6];long[] starts=new long[6],sizes=new long[6];for(int i=0;i<6;i++){previous[i]=GL30C.glGetIntegeri(GL43C.GL_SHADER_STORAGE_BUFFER_BINDING,i);starts[i]=GL32C.glGetInteger64i(GL43C.GL_SHADER_STORAGE_BUFFER_START,i);sizes[i]=GL32C.glGetInteger64i(GL43C.GL_SHADER_STORAGE_BUFFER_SIZE,i);}
        try {
            GL42C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT|GL42C.GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT);
            GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER,buffers[3]);long bytes=(long)length*4;
            if(frameCapacity<bytes){GL15C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER,bytes,GL15C.GL_STREAM_DRAW);frameCapacity=bytes;}
            GL15C.glBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER,0,staging);
            GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER,buffers[5]);GL15C.glBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER,0,new int[]{0});
            GL20C.glUseProgram(program);uniform("VertexCount",mesh.vertexCount());uniform("BoneCount",palette.size());uniform("JointsStart",jointsStart);uniform("ModesStart",modesStart);uniform("MorphRangesStart",morphRangesStart);uniform("MorphEntriesStart",morphEntriesStart);
            for(int i=0;i<6;i++)GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER,i,buffers[i]);GL43C.glDispatchCompute((mesh.vertexCount()+255)/256,1,1);
            GL42C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT|GL42C.GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT|GL42C.GL_BUFFER_UPDATE_BARRIER_BIT);
            int[] status={0};GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER,buffers[5]);GL15C.glGetBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER,0,status);
            if(GL11C.glGetError()!=GL11C.GL_NO_ERROR || status[0]!=0){failed=true;return false;}
            frameKey=key;dispatches++;uploadedBytes+=bytes+4;return true;
        }finally{GL20C.glUseProgram(oldProgram);for(int i=0;i<6;i++){if(previous[i]!=0&&sizes[i]>0)GL30C.glBindBufferRange(GL43C.GL_SHADER_STORAGE_BUFFER,i,previous[i],starts[i],sizes[i]);else GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER,i,previous[i]);}GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER,oldBuffer);}
    }
    private void uniform(String name,int value){GL20C.glUniform1i(GL20C.glGetUniformLocation(program,name),value);}
    /** Oracle readback is for verification only; normal rendering reads one status integer. */
    public float[] readback(){if(closed)throw new IllegalStateException("GPU deformation closed");float[] values=new float[mesh.vertexCount()*17];int previous=GL11C.glGetInteger(GL43C.GL_SHADER_STORAGE_BUFFER_BINDING);try{GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER,buffers[4]);GL15C.glGetBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER,0,values);return values;}finally{GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER,previous);}}
    @Override public void close(){RenderSystem.assertOnRenderThread();if(closed)return;closed=true;if(staging!=null)MemoryUtil.memFree(staging);for(int b:buffers)GL15C.glDeleteBuffers(b);GL20C.glDeleteProgram(program);frameKey=null;}
}
