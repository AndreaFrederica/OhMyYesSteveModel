package com.elfmcys.ysm.model.resource.client.render;

import cc.sirrus.ysmlib.scene.*;
import com.elfmcys.ysm.api.rendering.v0.SceneView;
import com.elfmcys.ysm.api.rendering.v0.TargetKind;
import com.elfmcys.ysm.api.rendering.v0.event.RenderSceneEvent;
import com.elfmcys.ysm.client.renderer.*;
import com.elfmcys.ysm.geckolib3.geo.RenderContext;
import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.client.renderer.MultiBufferSource;
import org.lwjgl.opengl.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises the actual host compositor and extension handoff with an already-published Lib playback instance. */
final class SceneHostGpuVerification {
    static void verify(GeneralMeshInstance instance) {
        int previousDraw=GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousRead=GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        int previousUnit=GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE),previousTexture=GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        int[] viewport=new int[4];GL11C.glGetIntegerv(GL11C.GL_VIEWPORT,viewport);
        int framebuffer=GL30C.glGenFramebuffers(),color=GL11C.glGenTextures(),depth=GL11C.glGenTextures();
        var dispatched=new AtomicReference<RenderSceneEvent>();var mode=new AtomicInteger();
        var frame=instance.frame();
        var buffers=MultiBufferSource.immediate(new BufferBuilder(256));
        try(var host=new GeneralMeshRenderHost(1024*1024,event->{
            if(event.frame()!=frame || event.assets()!=instance.assets()) throw new AssertionError("Host did not expose the published source frame");
            dispatched.set(event);
            if(mode.get()==1) event.setCanceled(true);
            if(mode.get()==2) { event.drawMaterials(event.view());event.setCanceled(true); }
        })) {
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER,framebuffer);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,color);
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D,0,GL11C.GL_RGBA8,32,32,0,GL11C.GL_RGBA,GL11C.GL_UNSIGNED_BYTE,0L);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D,GL11C.GL_TEXTURE_MIN_FILTER,GL11C.GL_NEAREST);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D,GL12C.GL_TEXTURE_MAX_LEVEL,0);
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER,GL30C.GL_COLOR_ATTACHMENT0,GL11C.GL_TEXTURE_2D,color,0);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,depth);
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D,0,GL30C.GL_DEPTH_COMPONENT32F,32,32,0,GL11C.GL_DEPTH_COMPONENT,GL11C.GL_FLOAT,0L);
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER,GL30C.GL_DEPTH_ATTACHMENT,GL11C.GL_TEXTURE_2D,depth,0);
            GL11C.glViewport(0,0,32,32);GL11C.glDepthMask(true);GL11C.glColorMask(true,true,true,true);
            GL11C.glClearColor(0,0,0,0);GL11C.glClearDepth(1);clear();
            var view=new SceneView(Matrix4.IDENTITY,Matrix4.IDENTITY,new Vec3(0,0,1),new Vec3(1,1,1),Vec3.ZERO,new FloatData(1,1,1,1),true);
            if(!host.render(instance,instance,TargetKind.PLAYER,RenderContext.levelImmutable(),false,view,buffers))throw new AssertionError("Successful scene must allow held item attachments");
            if(dispatched.get()==null || pixel()<.9f || instance.frame()!=frame) throw new AssertionError("Ordinary scene host did not render the stable frame");
            try { dispatched.get().drawMaterials(view);throw new AssertionError("Expired extension draw callback remained live"); }
            catch(IllegalStateException expected) { }
            clear();mode.set(1);
            if(host.render(instance,instance,TargetKind.PLAYER,RenderContext.levelImmutable(),false,view,buffers))throw new AssertionError("Canceled scene must not suppress vanilla items");
            if(pixel()!=0) throw new AssertionError("Cancelled event executed the built-in pass");
            mode.set(2);
            host.render(instance,instance,TargetKind.PLAYER,RenderContext.levelImmutable(),true,view,buffers);
            if(pixel()<.9f || !dispatched.get().firstPerson() || instance.frame()!=frame) throw new AssertionError("Extension could not borrow the material pass");
            System.out.println("Scene host: actual bound private target, shared compositor, immutable extension frame, cancellation, borrowed material draw and callback expiry passed");
        } finally {
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER,previousRead);GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER,previousDraw);
            GL13C.glActiveTexture(previousUnit);GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,previousTexture);
            GL11C.glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);
            GL30C.glDeleteFramebuffers(framebuffer);GL11C.glDeleteTextures(color);GL11C.glDeleteTextures(depth);
        }
    }
    private static void clear() { GL11C.glClear(GL11C.GL_COLOR_BUFFER_BIT|GL11C.GL_DEPTH_BUFFER_BIT); }
    private static float pixel() { float[] value=new float[4];GL11C.glReadPixels(16,16,1,1,GL11C.GL_RGBA,GL11C.GL_FLOAT,value);return value[0]; }
}
