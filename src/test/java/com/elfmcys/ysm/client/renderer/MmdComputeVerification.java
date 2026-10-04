package com.elfmcys.ysm.client.renderer;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import cc.sirrus.ysmlib.scene.java.JavaDeformationProvider;
import org.lwjgl.opengl.*;
import java.util.*;

/** Independent compute oracle and production buffer verification, before host benchmarking. */
public final class MmdComputeVerification {
    public static void verify() throws Exception {
        var attrs=new LinkedHashMap<String,MeshAsset.Attribute>();
        attrs.put("POSITION",new MeshAsset.Attribute(3,new FloatData(1,2,3,-1,.2f,2,4,3,-2,1,1,1)));
        attrs.put("NORMAL",new MeshAsset.Attribute(3,new FloatData(0,1,0,0,1,0,0,1,0,0,0,0)));
        attrs.put("TANGENT",new MeshAsset.Attribute(4,new FloatData(1,0,0,1,1,0,0,-1,1,0,0,1,1,0,0,1)));
        attrs.put("TEXCOORD_0",new MeshAsset.Attribute(2,new FloatData(new float[8])));
        attrs.put("_MMD_UV1",new MeshAsset.Attribute(4,new FloatData(new float[16])));
        attrs.put("_MMD_EDGE_SCALE",new MeshAsset.Attribute(1,new FloatData(1,1,1,1)));
        var skin=new MeshAsset.Skinning(new IntData(0,2,4,8,10),new IntData(0,1,0,1,0,1,0,1,0,-1),
                new FloatData(.3f,.7f,.4f,.6f,.2f,.3f,.1f,.4f,1,0),
                List.of(MeshAsset.Deform.LINEAR,MeshAsset.Deform.SDEF,MeshAsset.Deform.QDEF,MeshAsset.Deform.SDEF),
                new FloatData(0,0,0,0,0,0,0,0,0, .3f,.2f,.1f,.1f,.4f,.2f,.5f,-.1f,.3f, 0,0,0,0,0,0,0,0,0, .1f,.2f,.3f,.2f,.4f,.1f,0,0,0));
        var morph=new MeshAsset.MorphTarget("repeated",Map.of(
                "POSITION",new MeshAsset.Attribute(3,new FloatData(.1f,.2f,.3f,.3f,-.2f,.1f)),
                "TEXCOORD_0",new MeshAsset.Attribute(2,new FloatData(.1f,.2f,.2f,-.1f)),
                "_MMD_UV1",new MeshAsset.Attribute(4,new FloatData(.1f,.2f,.3f,.4f,-.1f,.2f,0,.1f))),new IntData(1,1));
        var mesh=new MeshAsset.Primitive(MeshAsset.Topology.POINTS,attrs,new IntData(0,1,2,3),-1,skin,List.of(morph));
        int parent=GL15C.glGenBuffers();GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER,parent);
        GL15C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER,1024L,GL15C.GL_STATIC_DRAW);
        for(int i=0;i<6;i++)GL30C.glBindBufferRange(GL43C.GL_SHADER_STORAGE_BUFFER,i,parent,0,256);
        try(var gpu=new MmdGpuDeformer(mesh,ReadLimits.DEFAULT);var cpu=new JavaDeformationProvider().compile(mesh)) {
            for(int i=0;i<40;i++){
                var palette=List.of(new Transform(new Vec3(.1f,0,0),Rotation.axisAngle(Vec3.ONE.normalized(),i*.03),Vec3.ONE).matrix(),
                        new Transform(new Vec3(0,.2f,0),Rotation.axisAngle(new Vec3(0,1,0),-i*.04),Vec3.ONE).matrix());
                var weights=new FloatData(i%2==0?.7f:0);Object key=new Object();
                if(!gpu.update(key,palette,weights))throw new AssertionError("Synthetic GPU frame rejected");
                compare(cpu.deform(palette,weights,SceneProvider.NormalPolicy.MMD_WEIGHTED_ROTATION),gpu.readback(),2e-5f);
                long count=gpu.dispatches();gpu.update(key,palette,weights);if(gpu.dispatches()!=count)throw new AssertionError("Same frame dispatched twice");
                for(int slot=0;slot<6;slot++)if(GL30C.glGetIntegeri(GL43C.GL_SHADER_STORAGE_BUFFER_BINDING,slot)!=parent || GL32C.glGetInteger64i(GL43C.GL_SHADER_STORAGE_BUFFER_SIZE,slot)!=256)throw new AssertionError("SSBO range lost");
                if(GL11C.glGetInteger(GL43C.GL_SHADER_STORAGE_BUFFER_BINDING)!=parent)throw new AssertionError("Generic SSBO binding lost");
            }
            var bad=List.of(new Transform(Vec3.ZERO,Rotation.IDENTITY,new Vec3(2,1,1)).matrix(),Matrix4.IDENTITY);
            if(gpu.update(new Object(),bad,new FloatData(0)))throw new AssertionError("Nonrigid SDEF accepted");
            if(gpu.update(new Object(),List.of(Matrix4.IDENTITY,Matrix4.IDENTITY),new FloatData(0)))throw new AssertionError("Failed GPU session reused");
        }finally{for(int i=0;i<6;i++)GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER,i,0);GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER,0);GL15C.glDeleteBuffers(parent);}
        System.setProperty("ysm.mmd.referenceUpload","false");
        try(var limited=new MmdGpuDeformer(mesh,new ReadLimits(1024*1024,10_000,10_000))){
            try{limited.update(new Object(),Collections.nCopies(65536,Matrix4.IDENTITY),new FloatData(0));throw new AssertionError("Combined static/frame GPU budget ignored");}
            catch(IllegalArgumentException expected){}
            if(!limited.update(new Object(),List.of(Matrix4.IDENTITY,Matrix4.IDENTITY),new FloatData(0)))throw new AssertionError("Input rejection damaged GPU session");
        }
        try(var buffer=new MmdSharedMeshBuffer();var gpu=new MmdGpuDeformer(mesh,ReadLimits.DEFAULT)) {
            var geometry=List.of(mesh);buffer.upload(geometry,ReadLimits.DEFAULT);buffer.upload(geometry,ReadLimits.DEFAULT);
            if(buffer.statistics().vertexUploads()!=1||buffer.statistics().indexUploads()!=1||buffer.statistics().stagingAllocations()!=1)throw new AssertionError("Buffer caching failed");
            gpu.update(new Object(),List.of(Matrix4.IDENTITY,Matrix4.IDENTITY),new FloatData(0));
            buffer.uploadGpu(geometry,ReadLimits.DEFAULT,gpu.outputBuffer());buffer.upload(geometry,ReadLimits.DEFAULT);
            if(buffer.statistics().vertexUploads()!=2||buffer.statistics().indexUploads()!=1||buffer.statistics().stagingAllocations()!=1)throw new AssertionError("GPU -> CPU buffer recovery failed");
        }
        if(GL11C.glGetError()!=GL11C.GL_NO_ERROR)throw new AssertionError("Compute verification GL error");
        System.out.println("MMD compute: BDEF/SDEF/QDEF, nonzero SDEF, repeated sparse position/UV morph, zero normals, endpoint identity, same-frame reuse, SSBO restoration, failure isolation and buffer recovery passed");
    }
    public static float compare(Map<String,MeshAsset.Attribute> expected,float[] output,float tolerance) {
        var offsets=Map.of("POSITION",0,"NORMAL",3,"TANGENT",6,"TEXCOORD_0",10,"_MMD_UV1",12,"_MMD_EDGE_SCALE",16);float worst=0;
        for(var entry:expected.entrySet()){var offset=offsets.get(entry.getKey());if(offset==null)continue;var a=entry.getValue();
            for(int v=0;v<a.count();v++)for(int k=0;k<a.components();k++){float e=a.values().get(v*a.components()+k),actual=output[v*17+offset+k];float error=Math.abs(e-actual);
                if(!Float.isFinite(actual)||error>tolerance)throw new AssertionError(entry.getKey()+" vertex="+v+" component="+k+" expected="+e+" actual="+actual+" error="+error);worst=Math.max(worst,error);}}
        return worst;
    }
}
