package com.elfmcys.ysm.model.resource.client.render;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import cc.sirrus.ysmlib.scene.mmd.*;
import cc.sirrus.ysmlib.scene.vrm.VrmMaterials;
import com.elfmcys.ysm.api.rendering.v0.SceneLightmap;
import com.elfmcys.ysm.client.renderer.*;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;
import java.util.*;

/** Driver-backed lighting regression: same sky level, changing map, independent block axis, emission and scopes. */
final class SceneLightmapGpuVerification {
    private static final FloatData ONE = new FloatData(1,1,1,1), ZERO = new FloatData(0,0,0,0);
    private static final Vec3 LIGHT = new Vec3(0,0,1);
    private static final float[] EMISSION = {.05f,.08f,.1f};
    @FunctionalInterface private interface Draw { void render(SceneLightmap lightmap); }

    static void verify() throws Exception {
        int oldDraw=GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        int oldRead=GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        int oldUnit=GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        int framebuffer=GL30C.glGenFramebuffers(),color=GL11C.glGenTextures(),map=GL11C.glGenTextures();
        int sampler=0;
        boolean samplers=GL.getCapabilities().OpenGL33||GL.getCapabilities().GL_ARB_sampler_objects;
        try(var mesh=new SceneMeshBuffer()) {
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,color);
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D,0,GL30C.GL_RGBA32F,32,32,0,GL11C.GL_RGBA,GL11C.GL_FLOAT,0L);
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER,framebuffer);
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER,GL30C.GL_COLOR_ATTACHMENT0,GL11C.GL_TEXTURE_2D,color,0);
            if(GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER)!=GL30C.GL_FRAMEBUFFER_COMPLETE)throw new AssertionError("Lightmap framebuffer");
            GL11C.glViewport(0,0,32,32);GL11C.glDisable(GL11C.GL_BLEND);GL11C.glDisable(GL11C.GL_CULL_FACE);
            GL11C.glDisable(GL11C.GL_DEPTH_TEST);GL11C.glDisable(GL30C.GL_FRAMEBUFFER_SRGB);GL11C.glFrontFace(GL11C.GL_CCW);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,map);
            var pixels=MemoryUtil.memAlloc(16*16*4);
            try {
                while(pixels.hasRemaining())pixels.put((byte)255);pixels.flip();
                GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D,0,GL11C.GL_RGBA8,16,16,0,GL11C.GL_RGBA,GL11C.GL_UNSIGNED_BYTE,pixels);
            } finally { MemoryUtil.memFree(pixels); }
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D,GL11C.GL_TEXTURE_MIN_FILTER,GL11C.GL_NEAREST);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D,GL12C.GL_TEXTURE_MAX_LEVEL,0);
            // A deliberately unsuitable inherited sampler must be bypassed, then restored.
            if(samplers) {
                sampler=GL33C.glGenSamplers();GL33C.glSamplerParameteri(sampler,GL11C.GL_TEXTURE_MIN_FILTER,GL11C.GL_NEAREST_MIPMAP_LINEAR);
                GL33C.glBindSampler(0,sampler);GL33C.glBindSampler(3,sampler);
            }
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0+3);GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,color);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0);GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,color);
            var geometry=geometry();
            var definition=new MmdMaterials.Definition(0,MmdMaterials.Format.PMX,"lit",16,MmdMaterials.SphereMode.NONE,null,null,null);
            var values=new MmdMorphState.Material(new FloatData(.3f,.2f,.1f,.7f),new Vec3(.1f,.2f,.3f),8,
                    new Vec3(.2f,.3f,.4f),new FloatData(.2f,.4f,.6f,.8f),1,ONE,ZERO,ONE,ZERO,ONE,ZERO);
            var mmd=new MmdMaterials.Material(definition,values);
            try(var shader=new MmdSurfaceProgram(false)) {
                mesh.upload(geometry,shader.layout(),ReadLimits.DEFAULT);
                exercise("MMD diffuse/ambient/specular",map,false,false,new float[3],lightmap->{
                    try(var scope=shader.bind(mmd,new MmdTextureBindings.Material(null,null,null),ignored->0,mmdView(lightmap))) { mesh.draw(); }
                });
                exercise("MMD outline",map,false,false,new float[3],lightmap->{
                    try(var scope=shader.bindEdge(mmd,mmdView(lightmap))) { mesh.draw(); }
                });
                var invalid=new SceneLightmap(GL11C.glGenTextures(),0,15);
                try {
                    try { shader.bind(mmd,new MmdTextureBindings.Material(null,null,null),ignored->0,mmdView(invalid));throw new AssertionError("Missing lightmap accepted"); }
                    catch(IllegalStateException expected) { if(!expected.getMessage().contains("lightmap"))throw expected; }
                } finally { GL11C.glDeleteTextures(invalid.textureId()); }
            }
            for(boolean unlit:new boolean[]{false,true}) {
                var parameters=new HashMap<String,FloatData>();
                parameters.put("baseColor",new FloatData(.6f,.5f,.4f,.7f));parameters.put("metallic",new FloatData(0));
                parameters.put("roughness",new FloatData(.8f));parameters.put("emissive",new FloatData(EMISSION));
                var material=new SceneAsset.Material("lit",unlit?"unlit":"metallic-roughness",parameters,Map.of(),"BLEND",.5f,true,Map.of());
                try(var shader=new GltfSurfaceProgram(geometry,material)) {
                    mesh.upload(geometry,shader.layout(),ReadLimits.DEFAULT);
                    var bindings=new GltfTextureBindings("model",scene(),material);
                    exercise(unlit?"glTF unlit":"glTF lit/emission",map,true,unlit,unlit?new float[3]:EMISSION,lightmap->{
                        try(var scope=shader.bind(material,bindings,ignored->0,gltfView(lightmap))) { mesh.draw(); }
                    });
                }
            }
            for(var variant:List.of(VrmMaterials.Shader.MTOON_0,VrmMaterials.Shader.MTOON_1,VrmMaterials.Shader.LEGACY_UNLIT)) {
                var parameters=new HashMap<String,FloatData>();
                parameters.put("baseColor",new FloatData(.6f,.5f,.4f,.7f));parameters.put("emissive",new FloatData(EMISSION));
                parameters.put("VRMC_materials_mtoon/outlineColorFactor",new FloatData(.2f,.4f,.6f));
                parameters.put("VRMC_materials_mtoon/outlineWidthFactor",new FloatData(.01f));
                parameters.put("VRMC_materials_mtoon/outlineLightingMixFactor",new FloatData(1));
                parameters.put("_Color",parameters.get("baseColor"));parameters.put("_EmissionColor",new FloatData(.05f,.08f,.1f,1));
                parameters.put("_OutlineColor",new FloatData(.2f,.4f,.6f,1));parameters.put("_OutlineWidth",new FloatData(.01f));
                parameters.put("_OutlineLightingMix",new FloatData(1));
                var state=new VrmMaterials.RenderState("BLEND",.5f,false,VrmMaterials.Cull.NONE,1,0,false,0,0,-1,"worldCoordinates",VrmMaterials.Cull.FRONT);
                var material=new VrmMaterials.Material(0,"fixture",variant,variant==VrmMaterials.Shader.MTOON_1?VrmMaterials.ParameterSpace.GLTF_LINEAR:VrmMaterials.ParameterSpace.LEGACY_SHADER,parameters,
                        Map.of(),Map.of(),state,0,new CompatibilityReport(List.of(),List.of()));
                try(var shader=new VrmSurfaceProgram(geometry,material)) {
                    mesh.upload(geometry,shader.layout(),ReadLimits.DEFAULT);
                    var bindings=new VrmTextureBindings("model",scene(),material);
                    boolean unlit=variant==VrmMaterials.Shader.LEGACY_UNLIT;
                    exercise(variant+" surface/emission",map,true,unlit,EMISSION,lightmap->{
                        try(var scope=shader.prepare(material,bindings,ignored->0,vrmView(lightmap)).bind()) { mesh.draw(); }
                    });
                    exercise(variant+" lit outline",map,true,false,new float[3],lightmap->{
                        try(var scope=shader.prepare(material,bindings,ignored->0,vrmView(lightmap)).bind(true)) { mesh.draw(); }
                    });
                }
            }
            if(GL11C.glGetError()!=GL11C.GL_NO_ERROR)throw new AssertionError("Scene lightmap GL error");
            System.out.println("Scene lightmap: MMD/glTF/MToon0/1, same sky=15 day/night updates, independent block light, darkness, alpha, emission/unlit, outlines, failed binding and sampler restoration passed");
        } finally {
            if(samplers){GL33C.glBindSampler(0,0);GL33C.glBindSampler(3,0);if(sampler!=0)GL33C.glDeleteSamplers(sampler);}
            GL20C.glUseProgram(0);GL13C.glActiveTexture(oldUnit);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER,oldDraw);GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER,oldRead);
            GL30C.glDeleteFramebuffers(framebuffer);GL11C.glDeleteTextures(color);GL11C.glDeleteTextures(map);
        }
    }

    private static void exercise(String name,int map,boolean linear,boolean unlit,float[] emission,Draw draw) {
        draw.render(SceneLightmap.NONE);var reference=pixel();
        if(reference[0]<=0)throw new AssertionError(name+" baseline not drawn");
        for(int[] scenario:List.of(new int[]{0,15,51,77,102},new int[]{0,15,230,240,255},
                new int[]{12,0,240,160,80},new int[]{0,0,0,0,0})) {
            update(map,scenario);
            int active=GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE),program=GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0+3);int texture3=GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0);int texture0=GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
            boolean samplers=GL.getCapabilities().OpenGL33||GL.getCapabilities().GL_ARB_sampler_objects;
            int sampler0=samplers?GL11C.glGetInteger(GL33C.GL_SAMPLER_BINDING):0;
            GL13C.glActiveTexture(active);
            draw.render(new SceneLightmap(map,scenario[0],scenario[1]));var actual=pixel();
            for(int c=0;c<3;c++) {
                float factor=unlit?1:scenario[c+2]/255f;
                if(linear&&!unlit)factor=(float)(factor<=.04045?factor/12.92:Math.pow((factor+.055)/1.055,2.4));
                near((reference[c]-emission[c])*factor+emission[c],actual[c],name+" RGB "+c);
            }
            near(reference[3],actual[3],name+" opacity");
            if(active!=GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE)||program!=GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM))throw new AssertionError(name+" host program/unit lost");
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0+3);
            if(texture3!=GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D))throw new AssertionError(name+" unit3 binding lost");
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
            if(texture0!=GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D)||(samplers&&sampler0!=GL11C.glGetInteger(GL33C.GL_SAMPLER_BINDING)))throw new AssertionError(name+" unit0 binding/sampler lost");
            GL13C.glActiveTexture(active);
        }
        draw.render(SceneLightmap.NONE);var preview=pixel();
        for(int c=0;c<4;c++)near(reference[c],preview[c],name+" preview after world");
    }
    private static void update(int map,int[] values) {
        int texture=GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        var data=MemoryUtil.memAlloc(4);
        try {
            data.put((byte)values[2]).put((byte)values[3]).put((byte)values[4]).put((byte)0).flip();
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,map);
            GL11C.glTexSubImage2D(GL11C.GL_TEXTURE_2D,0,values[0],values[1],1,1,GL11C.GL_RGBA,GL11C.GL_UNSIGNED_BYTE,data);
        } finally { GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,texture);MemoryUtil.memFree(data); }
    }
    private static MmdSurfaceProgram.View mmdView(SceneLightmap map) { return new MmdSurfaceProgram.View(Matrix4.IDENTITY,Matrix4.IDENTITY,new Vec3(1,1,1),LIGHT,ONE,true,map); }
    private static GltfSurfaceProgram.View gltfView(SceneLightmap map) { return new GltfSurfaceProgram.View(Matrix4.IDENTITY,Matrix4.IDENTITY,LIGHT,new Vec3(1,1,1),new Vec3(.2f,.2f,.2f),ONE,true,map); }
    private static VrmSurfaceProgram.View vrmView(SceneLightmap map) { return new VrmSurfaceProgram.View(Matrix4.IDENTITY,Matrix4.IDENTITY,LIGHT,new Vec3(1,1,1),new Vec3(.2f,.2f,.2f),ONE,true,map); }
    private static SceneAsset scene() { return new SceneAsset("",SceneAsset.Coordinates.GLTF,List.of(),List.of(),-1,List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),Map.of(),new CompatibilityReport(List.of(),List.of())); }
    private static MeshAsset.Primitive geometry() {
        return new MeshAsset.Primitive(MeshAsset.Topology.TRIANGLES,Map.of(
                "POSITION",new MeshAsset.Attribute(3,new FloatData(-1,-1,-.5f,1,-1,-.5f,-1,1,-.5f,1,1,-.5f)),
                "NORMAL",new MeshAsset.Attribute(3,new FloatData(0,0,1,0,0,1,0,0,1,0,0,1)),
                "TEXCOORD_0",new MeshAsset.Attribute(2,new FloatData(0,0,0,0,0,0,0,0)),
                "_MMD_EDGE_SCALE",new MeshAsset.Attribute(1,new FloatData(1,1,1,1))),new IntData(0,1,2,2,1,3),0,null,List.of());
    }
    private static float[] pixel() { float[] result=new float[4];GL11C.glReadPixels(16,16,1,1,GL11C.GL_RGBA,GL11C.GL_FLOAT,result);return result; }
    private static void near(float expected,float actual,String name) { if(!Float.isFinite(actual)||Math.abs(expected-actual)>3e-5)throw new AssertionError(name+": "+actual+" != "+expected); }
}
