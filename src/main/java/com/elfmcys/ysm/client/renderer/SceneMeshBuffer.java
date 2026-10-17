package com.elfmcys.ysm.client.renderer;

import cc.sirrus.ysmlib.scene.MeshAsset;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.*;

/** Instance-owned streaming geometry. Consumes already deformed frames and performs real indexed draws. */
public final class SceneMeshBuffer implements AutoCloseable {
    private final int vao;
    private final int vertices;
    private final int indices;
    private Set<Integer> enabled = Set.of();
    private MeshAsset.Primitive uploaded;
    private Map<String, Integer> layout = Map.of();
    private int count;
    private int mode;
    private boolean closed;
    private FloatBuffer vertexStaging;
    private IntBuffer indexStaging;
    private long vertexCapacity,indexCapacity,uploadedVertexBytes,uploadedIndexBytes;
    private cc.sirrus.ysmlib.scene.IntData uploadedIndices;
    private Map<String,MeshAsset.Attribute> uploadedAttributes=Map.of();
    private record IndexBuffer(int id,long bytes) {}
    private final boolean retainIndexVariants;
    private final IdentityHashMap<cc.sirrus.ysmlib.scene.IntData,IndexBuffer> indexVariants=new IdentityHashMap<>();
    public record UploadStatistics(long vertexBytes,long indexBytes,long vertexCapacity,long indexCapacity) {}
    public UploadStatistics uploadStatistics(){return new UploadStatistics(uploadedVertexBytes,uploadedIndexBytes,vertexCapacity,indexCapacity);}
    /** GPU capacities plus retained direct upload staging. */
    public long retainedBytes(){return vertexCapacity+indexCapacity+(vertexStaging==null?0:4L*vertexStaging.capacity())+(indexStaging==null?0:4L*indexStaging.capacity());}
    private long projectedBytes(long verticesBytes,cc.sirrus.ysmlib.scene.IntData nextIndices){
        long next=4L*nextIndices.size(),indicesBytes=indexCapacity;
        if(retainIndexVariants){if(!indexVariants.containsKey(nextIndices))indicesBytes=(indexVariants.size()>=2?0:indexCapacity)+next;}
        else indicesBytes=Math.max(indexCapacity,next);
        return Math.max(vertexCapacity,verticesBytes)+indicesBytes+Math.max(vertexStaging==null?0:4L*vertexStaging.capacity(),verticesBytes)+Math.max(indexStaging==null?0:4L*indexStaging.capacity(),next);
    }
    private boolean sameAttributes(MeshAsset.Primitive geometry,Map<String,Integer> attributes){
        if(!layout.equals(attributes))return false;
        for(String name:attributes.keySet()){var before=uploadedAttributes.get(name);var after=geometry.attributes().get(name);if(before==null||after==null||before.components()!=after.components()||before.values()!=after.values())return false;}return true;
    }

    public SceneMeshBuffer() {
        this(false);
    }
    /** Retain the two immutable first/third-person index variants while sharing one vertex buffer. */
    public SceneMeshBuffer(boolean retainIndexVariants) {
        this.retainIndexVariants=retainIndexVariants;
        RenderSystem.assertOnRenderThread();
        int previousError = GL11C.glGetError();
        if (previousError != GL11C.GL_NO_ERROR) throw new IllegalStateException("Host GL error before mesh allocation: " + previousError);
        int newVao = 0, newVertices = 0, newIndices = 0;
        try {
            newVao = GL30C.glGenVertexArrays();
            newVertices = GL15C.glGenBuffers();
            newIndices = GL15C.glGenBuffers();
            if (newVao == 0 || newVertices == 0 || newIndices == 0 || GL11C.glGetError() != GL11C.GL_NO_ERROR) {
                throw new IllegalStateException("Unable to allocate scene mesh buffers");
            }
        } catch (RuntimeException | Error failure) {
            if (newVao != 0) GL30C.glDeleteVertexArrays(newVao);
            if (newVertices != 0) GL15C.glDeleteBuffers(newVertices);
            if (newIndices != 0) GL15C.glDeleteBuffers(newIndices);
            throw failure;
        }
        vao = newVao; vertices = newVertices; indices = newIndices;
    }

    /** Bind only attributes actually used by the selected material shader; source attributes stay immutable. */
    public void upload(MeshAsset.Primitive geometry, Map<String, Integer> attributes, ReadLimits limits) {
        requireOpen();
        if (uploaded == geometry && layout.equals(attributes)) return;
        if (geometry.skinning() != null || !geometry.morphs().isEmpty()) {
            throw new IllegalArgumentException("Scene renderer requires already deformed geometry");
        }
        var bindings = new ArrayList<>(attributes.entrySet());
        bindings.sort(Map.Entry.comparingByValue());
        var locations = new HashSet<Integer>();
        int stride = 0;
        int maxAttributes = GL11C.glGetInteger(GL20C.GL_MAX_VERTEX_ATTRIBS);
        for (var binding : bindings) {
            var attribute = geometry.attributes().get(binding.getKey());
            if (attribute == null || attribute.components() > 4 || binding.getValue() < 0
                    || binding.getValue() >= maxAttributes || !locations.add(binding.getValue())) {
                throw new IllegalArgumentException("Invalid or unavailable scene shader attribute: " + binding);
            }
            stride += attribute.components();
        }
        if (!attributes.containsKey("POSITION")) throw new IllegalArgumentException("Scene shader requires POSITION");
        long vertexScalars = (long) geometry.vertexCount() * stride;
        long bytes = (vertexScalars + geometry.indices().size()) * Integer.BYTES;
        if (bytes > limits.maxBytes() || projectedBytes(vertexScalars*4,geometry.indices())>limits.maxBytes() || vertexScalars > Integer.MAX_VALUE
                || geometry.vertexCount() > limits.maxElements() || geometry.indices().size() > limits.maxElements()) {
            throw new IllegalArgumentException("Scene geometry upload budget exceeded");
        }
        int previousError = GL11C.glGetError();
        if (previousError != GL11C.GL_NO_ERROR) throw new IllegalStateException("Host GL error before mesh upload: " + previousError);
        int previousVao = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
        int previousBuffer = GL11C.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING);
        boolean vertexChanged=!sameAttributes(geometry,attributes),indexChanged=uploadedIndices!=geometry.indices();
        var retained=retainIndexVariants?indexVariants.get(geometry.indices()):null;
        if(retained!=null)indexChanged=false;
        int indexBuffer=retained==null?indices:retained.id();boolean newIndexBuffer=false;
        FloatBuffer vertexData = null;
        IntBuffer indexData = null;
        uploaded = null;
        try {
            if(retainIndexVariants&&retained==null){
                if(indexVariants.size()>=2){for(var variant:indexVariants.values())if(variant.id()!=indices)GL15C.glDeleteBuffers(variant.id());indexVariants.clear();indexCapacity=0;GL30C.glBindVertexArray(vao);GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER,indices);GL15C.glBufferData(GL15C.GL_ELEMENT_ARRAY_BUFFER,0L,GL15C.GL_STATIC_DRAW);}
                if(!indexVariants.isEmpty()){indexBuffer=GL15C.glGenBuffers();newIndexBuffer=true;if(indexBuffer==0)throw new IllegalStateException("Cannot allocate view indices");}
            }
            GL30C.glBindVertexArray(vao);
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, vertices);
            if(vertexChanged&&vertexScalars>0) {
                if(vertexStaging==null||vertexStaging.capacity()<vertexScalars)vertexStaging=MemoryUtil.memRealloc(vertexStaging,(int)vertexScalars);
                vertexData=vertexStaging;vertexData.clear();
                for (int vertex = 0; vertex < geometry.vertexCount(); vertex++) for (var binding : bindings) {
                    var attribute=geometry.attributes().get(binding.getKey());for(int component=0;component<attribute.components();component++)vertexData.put(attribute.values().get(vertex*attribute.components()+component));
                }
                vertexData.flip();long size=vertexScalars*Float.BYTES;
                if(vertexCapacity<size){GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER,size,GL15C.GL_STREAM_DRAW);vertexCapacity=size;}
                GL15C.glBufferSubData(GL15C.GL_ARRAY_BUFFER,0L,vertexData);uploadedVertexBytes+=size;
            }
            GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, indexBuffer);
            if(indexChanged&&geometry.indices().size()>0) {
                int scalars=geometry.indices().size();if(indexStaging==null||indexStaging.capacity()<scalars)indexStaging=MemoryUtil.memRealloc(indexStaging,scalars);
                indexData=indexStaging;indexData.clear();indexData.put(geometry.indices().view()).flip();long size=(long)scalars*Integer.BYTES;
                if(retainIndexVariants||indexCapacity<size){GL15C.glBufferData(GL15C.GL_ELEMENT_ARRAY_BUFFER,size,GL15C.GL_STATIC_DRAW);if(retainIndexVariants)indexCapacity+=size;else indexCapacity=size;}
                GL15C.glBufferSubData(GL15C.GL_ELEMENT_ARRAY_BUFFER,0L,indexData);uploadedIndexBytes+=size;
            }
            for (int location : enabled) if (!locations.contains(location)) GL20C.glDisableVertexAttribArray(location);
            long offset = 0;
            for (var binding : bindings) {
                int components = geometry.attributes().get(binding.getKey()).components();
                GL20C.glVertexAttribPointer(binding.getValue(), components, GL11C.GL_FLOAT, false, stride * Float.BYTES, offset);
                GL20C.glEnableVertexAttribArray(binding.getValue());
                offset += (long) components * Float.BYTES;
            }
            enabled = Set.copyOf(locations);
            int error = GL11C.glGetError();
            if (error != GL11C.GL_NO_ERROR) throw new IllegalStateException("Scene mesh upload failed: GL " + error);
            count = geometry.indices().size();
            mode = switch (geometry.topology()) {
                case POINTS -> GL11C.GL_POINTS;
                case LINES -> GL11C.GL_LINES;
                case LINE_LOOP -> GL11C.GL_LINE_LOOP;
                case LINE_STRIP -> GL11C.GL_LINE_STRIP;
                case TRIANGLES -> GL11C.GL_TRIANGLES;
                case TRIANGLE_STRIP -> GL11C.GL_TRIANGLE_STRIP;
                case TRIANGLE_FAN -> GL11C.GL_TRIANGLE_FAN;
            };
            layout = Map.copyOf(attributes);
            uploaded = geometry;uploadedIndices=geometry.indices();uploadedAttributes=geometry.attributes();
            if(retainIndexVariants&&retained==null)indexVariants.put(geometry.indices(),new IndexBuffer(indexBuffer,(long)geometry.indices().size()*4));
        } catch(RuntimeException|Error failure) {
            uploadedAttributes=Map.of();uploadedIndices=null;vertexCapacity=0;
            if(newIndexBuffer)GL15C.glDeleteBuffers(indexBuffer);
            for(var variant:indexVariants.values())if(variant.id()!=indices)GL15C.glDeleteBuffers(variant.id());indexVariants.clear();indexCapacity=0;
            GL30C.glBindVertexArray(vao);
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER,vertices);GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER,0L,GL15C.GL_STREAM_DRAW);
            GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER,indices);GL15C.glBufferData(GL15C.GL_ELEMENT_ARRAY_BUFFER,0L,GL15C.GL_STATIC_DRAW);
            throw failure;
        } finally {

            GL30C.glBindVertexArray(previousVao);
            GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, previousBuffer);
        }
    }

    /** Caller owns shader, uniforms, material raster state, projection and target framebuffer. */
    public void gpuStreams(int buffer) {
        requireOpen();if(uploaded==null)throw new IllegalStateException("Missing GPU draw layout");
        int oldVao=GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING),oldBuffer=GL11C.glGetInteger(GL15C.GL_ARRAY_BUFFER_BINDING);
        try{GL30C.glBindVertexArray(vao);GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER,buffer);
            for(var entry:Map.of("POSITION",0,"NORMAL",3,"TANGENT",6,"TEXCOORD_0",10).entrySet())if(layout.containsKey(entry.getKey())){
                var attribute=uploaded.attributes().get(entry.getKey());GL20C.glVertexAttribPointer(layout.get(entry.getKey()),attribute.components(),GL11C.GL_FLOAT,false,17*4,(long)entry.getValue()*4);
            }
            int error=GL11C.glGetError();if(error!=GL11C.GL_NO_ERROR)throw new IllegalStateException("GPU scene binding failed: "+error);
        }finally{GL30C.glBindVertexArray(oldVao);GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER,oldBuffer);}
    }
    public void draw() {
        requireOpen();
        if (uploaded == null) throw new IllegalStateException("Scene mesh has no complete uploaded frame");
        if (count == 0) return;
        int previousError = GL11C.glGetError();
        if (previousError != GL11C.GL_NO_ERROR) throw new IllegalStateException("Host GL error before scene draw: " + previousError);
        int previousVao = GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
        boolean restart = GL11C.glIsEnabled(GL31C.GL_PRIMITIVE_RESTART);
        boolean fixedRestart = GL.getCapabilities().OpenGL43 && GL11C.glIsEnabled(GL43C.GL_PRIMITIVE_RESTART_FIXED_INDEX);
        try {
            if (restart) GL11C.glDisable(GL31C.GL_PRIMITIVE_RESTART);
            if (fixedRestart) GL11C.glDisable(GL43C.GL_PRIMITIVE_RESTART_FIXED_INDEX);
            GL30C.glBindVertexArray(vao);
            GL11C.glDrawElements(mode, count, GL11C.GL_UNSIGNED_INT, 0L);
            int error = GL11C.glGetError();
            if (error != GL11C.GL_NO_ERROR) throw new IllegalStateException("Scene mesh draw failed: GL " + error);
        } finally {
            GL30C.glBindVertexArray(previousVao);
            if (restart) GL11C.glEnable(GL31C.GL_PRIMITIVE_RESTART);
            if (fixedRestart) GL11C.glEnable(GL43C.GL_PRIMITIVE_RESTART_FIXED_INDEX);
        }
    }

    private void requireOpen() {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Scene mesh buffer is closed");
    }
    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        closed = true; uploaded = null;uploadedIndices=null;uploadedAttributes=Map.of();
        if(vertexStaging!=null){MemoryUtil.memFree(vertexStaging);vertexStaging=null;}
        if(indexStaging!=null){MemoryUtil.memFree(indexStaging);indexStaging=null;}
        for(var variant:indexVariants.values())if(variant.id()!=indices)GL15C.glDeleteBuffers(variant.id());indexVariants.clear();
        GL30C.glDeleteVertexArrays(vao);
        GL15C.glDeleteBuffers(vertices);
        GL15C.glDeleteBuffers(indices);
    }
}
