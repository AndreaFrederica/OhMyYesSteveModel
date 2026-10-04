package com.elfmcys.ysm.model.resource.client.render;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import com.elfmcys.ysm.client.renderer.*;
import org.lwjgl.opengl.*;
import java.util.*;

/** A numeric RGBA8 destination matches Minecraft's ordinary RenderTarget, unlike an sRGB attachment. */
final class SceneDisplayGpuVerification {
    static void verify() throws Exception {
        int framebuffer=GL30C.glGenFramebuffers(),color=GL11C.glGenTextures(),depth=GL11C.glGenTextures();
        int oldVao=GL30C.glGenVertexArrays(),oldPbo=GL15C.glGenBuffers();
        try(var scratch=new SceneDisplayBuffer(1024*1024)) {
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0);GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER,framebuffer);
            allocate(color,depth,32,false);
            var destination=new SceneDisplayBuffer.Destination(framebuffer,color,depth,32,32,false);
            GL11C.glViewport(0,0,32,32);GL11C.glDisable(GL30C.GL_FRAMEBUFFER_SRGB);
            var material=new SceneAsset.Material("","unlit",Map.of("baseColor",new FloatData(1,1,1,.5f)),Map.of(),"BLEND",.5f,true,Map.of());
            var primitive=new MeshAsset.Primitive(MeshAsset.Topology.TRIANGLES,
                    Map.of("POSITION",new MeshAsset.Attribute(3,new FloatData(-1,-1,0,1,-1,0,-1,1,0,1,1,0))),new IntData(0,1,2,2,1,3),0,null,List.of());
            var frame=new GeometryFrame(0,SceneAsset.Coordinates.GLTF,List.of(new GeometryFrame.Draw(0,0,Matrix4.IDENTITY,new MeshAsset("",List.of(primitive),FloatData.EMPTY),true)));
            var scene=new SceneAsset("",SceneAsset.Coordinates.GLTF,List.of(),List.of(),-1,List.of(),List.of(),List.of(material),List.of(),List.of(),List.of(),List.of(),List.of(),Map.of(),new CompatibilityReport(List.of(),List.of()));
            var view=new GltfSurfaceProgram.View(Matrix4.IDENTITY,Matrix4.IDENTITY,new Vec3(0,0,1),Vec3.ZERO,Vec3.ZERO,new FloatData(1,1,1,1),true);
            try(var target=new GltfRenderResources("model",scene,frame);var renderer=new GltfMeshRenderer(target,ReadLimits.DEFAULT)) {
                clear();float[] original=pixel(16,16);float originalDepth=depth();
                GL30C.glBindVertexArray(oldVao);GL15C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER,oldPbo);
                GL13C.glActiveTexture(GL13C.GL_TEXTURE0+6);GL11C.glDepthFunc(GL11C.GL_GREATER);GL11C.glDepthMask(false);
                GL11C.glEnable(GL11C.GL_SCISSOR_TEST);GL11C.glScissor(8,8,16,16);
                scratch.draw(destination,()->renderer.render(frame,List.of(material),view,ignored->0));
                float[] actual=pixel(16,16);
                for(int i=0;i<3;i++) near(encode((decode(original[i])+1)*.5f),actual[i],1.01f/255,"linear blend channel "+i);
                near(.5f+.5f*original[3],actual[3],1.01f/255,"alpha is not gamma converted");
                compare(original,pixel(2,2),"scissor preserves untouched background");near(originalDepth,depth(),1e-6f,"blend keeps depth");
                if(GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING)!=framebuffer || GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING)!=framebuffer
                        || GL11C.glGetInteger(GL30C.GL_VERTEX_ARRAY_BINDING)!=oldVao || GL11C.glGetInteger(GL21C.GL_PIXEL_UNPACK_BUFFER_BINDING)!=oldPbo
                        || GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE)!=GL13C.GL_TEXTURE0+6 || GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK)
                        || GL11C.glGetInteger(GL11C.GL_DEPTH_FUNC)!=GL11C.GL_GREATER || !GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST))
                    throw new AssertionError("Compositing did not restore host state");
                GL15C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER,0);GL11C.glDisable(GL11C.GL_SCISSOR_TEST);clear();
                var opaque=new SceneAsset.Material("","unlit",Map.of("baseColor",new FloatData(.2f,.3f,.4f,1)),Map.of(),"OPAQUE",.5f,true,Map.of());
                try {
                    scratch.draw(destination,()->{ renderer.render(frame,List.of(opaque),view,ignored->0);throw new IllegalStateException("injected draw failure"); });
                    throw new AssertionError("Failed draw committed");
                } catch(IllegalStateException expected) { if(!expected.getMessage().equals("injected draw failure")) throw expected; }
                compare(original,pixel(16,16),"failed frame restores destination color");near(originalDepth,depth(),1e-6f,"failed frame restores destination depth");
                scratch.draw(destination,()->renderer.render(frame,List.of(opaque),view,ignored->0));
                near(encode(.2f),pixel(16,16)[0],1.01f/255,"opaque linear output converted once");near(.5f,depth(),1e-6f,"opaque depth commit");
                // Resize and the optional Forge depth/stencil texture must use the same copy/commit semantics.
                GL13C.glActiveTexture(GL13C.GL_TEXTURE0);allocate(color,depth,16,true);GL11C.glViewport(0,0,16,16);clear();
                var smaller=new SceneDisplayBuffer.Destination(framebuffer,color,depth,16,16,true);
                GL11C.glStencilMask(255);GL11C.glClearStencil(7);GL11C.glClear(GL11C.GL_STENCIL_BUFFER_BIT);
                scratch.draw(smaller,()->renderer.render(frame,List.of(opaque),view,ignored->0));
                int[] stencil=new int[1];GL11C.glReadPixels(8,8,1,1,GL11C.GL_STENCIL_INDEX,GL11C.GL_INT,stencil);
                if(stencil[0]!=7) throw new AssertionError("Scene stencil copy lost host values");
                near(encode(.2f),pixel(8,8)[0],1.01f/255,"resized scene buffer");
            }
            if(GL11C.glGetError()!=GL11C.GL_NO_ERROR) throw new AssertionError("Scene display GL error");
            System.out.println("Scene display: numeric Minecraft RGBA8, float linear blending, alpha/scissor, opaque depth, failed-frame isolation, resize/depth-stencil and host state restoration passed");
        } finally {
            GL11C.glDisable(GL11C.GL_SCISSOR_TEST);GL11C.glDepthMask(true);GL30C.glBindVertexArray(0);GL15C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER,0);
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER,0);GL13C.glActiveTexture(GL13C.GL_TEXTURE0);
            GL30C.glDeleteFramebuffers(framebuffer);GL11C.glDeleteTextures(color);GL11C.glDeleteTextures(depth);
            GL30C.glDeleteVertexArrays(oldVao);GL15C.glDeleteBuffers(oldPbo);
        }
    }
    private static void allocate(int color,int depth,int size,boolean stencil) {
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,color);GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D,0,GL11C.GL_RGBA8,size,size,0,GL11C.GL_RGBA,GL11C.GL_UNSIGNED_BYTE,0L);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D,GL11C.GL_TEXTURE_MIN_FILTER,GL11C.GL_NEAREST);GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D,GL12C.GL_TEXTURE_MAX_LEVEL,0);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER,GL30C.GL_COLOR_ATTACHMENT0,GL11C.GL_TEXTURE_2D,color,0);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,depth);
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D,0,stencil?GL30C.GL_DEPTH32F_STENCIL8:GL30C.GL_DEPTH_COMPONENT32F,size,size,0,
                stencil?GL30C.GL_DEPTH_STENCIL:GL11C.GL_DEPTH_COMPONENT,stencil?GL30C.GL_FLOAT_32_UNSIGNED_INT_24_8_REV:GL11C.GL_FLOAT,0L);
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER,stencil?GL30C.GL_DEPTH_STENCIL_ATTACHMENT:GL30C.GL_DEPTH_ATTACHMENT,GL11C.GL_TEXTURE_2D,depth,0);
        if(GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER)!=GL30C.GL_FRAMEBUFFER_COMPLETE) throw new AssertionError("Numeric display target");
    }
    private static void clear() { GL11C.glDepthMask(true);GL11C.glColorMask(true,true,true,true);GL11C.glClearColor(.5f,.25f,.75f,.8f);GL11C.glClearDepth(.7);GL11C.glClear(GL11C.GL_COLOR_BUFFER_BIT|GL11C.GL_DEPTH_BUFFER_BIT); }
    private static float[] pixel(int x,int y) { float[] value=new float[4];GL11C.glReadPixels(x,y,1,1,GL11C.GL_RGBA,GL11C.GL_FLOAT,value);return value; }
    private static float depth() { float[] value=new float[1];GL11C.glReadPixels(16,16,1,1,GL11C.GL_DEPTH_COMPONENT,GL11C.GL_FLOAT,value);return value[0]; }
    private static float decode(float x) { return x<=.04045f?x/12.92f:(float)Math.pow((x+.055f)/1.055f,2.4); }
    private static float encode(float x) { return x<=.0031308f?x*12.92f:1.055f*(float)Math.pow(x,1/2.4)-.055f; }
    private static void compare(float[] a,float[] b,String message) { for(int i=0;i<a.length;i++) near(a[i],b[i],.1f/255,message); }
    private static void near(float expected,float actual,float tolerance,String message) { if(!Float.isFinite(actual)||Math.abs(expected-actual)>tolerance) throw new AssertionError(message+": "+actual+" != "+expected); }
}
