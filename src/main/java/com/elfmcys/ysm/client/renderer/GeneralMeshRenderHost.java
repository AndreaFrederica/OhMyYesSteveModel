package com.elfmcys.ysm.client.renderer;

import cc.sirrus.ysmlib.scene.*;
import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.api.rendering.v0.SceneView;
import com.elfmcys.ysm.api.rendering.v0.TargetKind;
import com.elfmcys.ysm.api.rendering.v0.event.RenderSceneEvent;
import com.elfmcys.ysm.geckolib3.geo.RenderContext;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.MultiBufferSource;
import org.lwjgl.opengl.*;
import java.io.IOException;

/** One render-owner scratch buffer shared by all scene consumers; no content, cache, lease or playback authority. */
public final class GeneralMeshRenderHost implements AutoCloseable {
    private final SceneDisplayBuffer display;
    private final java.util.function.Consumer<RenderSceneEvent> events;
    private boolean closed;

    public GeneralMeshRenderHost(long maxCompositingBytes) { this(maxCompositingBytes,event->{ if(YesSteveModel.postEvent(event)) event.setCanceled(true); }); }
    public GeneralMeshRenderHost(long maxCompositingBytes,java.util.function.Consumer<RenderSceneEvent> events) {
        display=new SceneDisplayBuffer(maxCompositingBytes);this.events=java.util.Objects.requireNonNull(events);
    }

    public boolean render(GeneralMeshInstance instance, Object target, TargetKind kind, RenderContext context,
                       boolean firstPerson, SceneView view, MultiBufferSource buffers) {
        RenderSystem.assertOnRenderThread();
        if(closed) throw new IllegalStateException("Scene render host is closed");
        if(!(buffers instanceof MultiBufferSource.BufferSource source))
            throw new IllegalArgumentException("General mesh requires an explicit flush adapter for this buffer source");
        source.endBatch();
        var event=new RenderSceneEvent(target,kind,instance.assets(),instance.frame(),context,firstPerson,view,
                selected->draw(instance,firstPerson,selected));
        final boolean cancelled;
        try { events.accept(event);cancelled=event.isCanceled(); }
        finally { event.endDispatch(); }
        if(cancelled) return false;
        // Built-in shadow drawing is deliberately outside the current product boundary. An extension may
        // consume the event and use its source shadow/light channels; otherwise the pass is skipped safely.
        if(context.irisShadow()) return false;
        if(instance.linearOutput()) {
            try { display.draw(destination(),()->draw(instance,firstPerson,event.view())); }
            catch(IOException | RuntimeException failure) {
                // A single model or a host GL state mismatch must not take down
                // the Minecraft render thread. The card/entity will recover on
                // a later frame after the display buffer has restored bindings.
                YesSteveModel.LOGGER.warn("Skipping generic mesh frame after scene compositing failure", failure);return false;
            }
        } else {
            // MMD's traditional shader intentionally evaluates encoded numeric color values.
            try {
                destination(); // Do not write an unrelated shader-pack MRT using this ordinary-target adapter.
                draw(instance,firstPerson,event.view());
            } catch (RuntimeException failure) {
                YesSteveModel.LOGGER.warn("Skipping generic mesh frame after renderer failure", failure);return false;
            }
        }
        return true;
    }

    private static void draw(GeneralMeshInstance instance,boolean firstPerson,SceneView view) {
        instance.render(firstPerson,new GltfSurfaceProgram.View(view.modelView(),view.projection(),view.toLight(),
                view.lightRadiance(),view.ambientIrradiance(),view.tint(),view.orthographic()));
    }

    /** Query the currently bound host target, including private GUI targets; never guess from Minecraft's main target. */
    private static SceneDisplayBuffer.Destination destination() {
        int framebuffer=GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        if(framebuffer==0 || GL11C.glGetInteger(GL20C.GL_DRAW_BUFFER0)!=GL30C.GL_COLOR_ATTACHMENT0
                || GL11C.glGetInteger(GL13C.GL_SAMPLES)>1)
            throw new IllegalArgumentException("Scene pass needs an explicit framebuffer adapter");
        for(int i=1;i<GL11C.glGetInteger(GL20C.GL_MAX_DRAW_BUFFERS);i++)
            if(GL11C.glGetInteger(GL20C.GL_DRAW_BUFFER0+i)!=GL11C.GL_NONE)
                throw new IllegalArgumentException("Scene MRT rendering requires an extension adapter");
        int color=textureAttachment(GL30C.GL_COLOR_ATTACHMENT0),depth=textureAttachment(GL30C.GL_DEPTH_ATTACHMENT);
        boolean stencil=GL30C.glGetFramebufferAttachmentParameteri(GL30C.GL_DRAW_FRAMEBUFFER,GL30C.GL_STENCIL_ATTACHMENT,
                GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE)!=GL11C.GL_NONE;
        if(stencil && textureAttachment(GL30C.GL_STENCIL_ATTACHMENT)!=depth)
            throw new IllegalArgumentException("Scene destination has a separate stencil attachment");
        int bound=GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        try {
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,color);
            if(GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D,0,GL11C.GL_TEXTURE_INTERNAL_FORMAT)!=GL11C.GL_RGBA8)
                throw new IllegalArgumentException("Scene destination working space requires an extension adapter");
            return new SceneDisplayBuffer.Destination(framebuffer,color,depth,
                    GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D,0,GL11C.GL_TEXTURE_WIDTH),
                    GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D,0,GL11C.GL_TEXTURE_HEIGHT),stencil);
        } finally { GL11C.glBindTexture(GL11C.GL_TEXTURE_2D,bound); }
    }
    private static int textureAttachment(int point) {
        if(GL30C.glGetFramebufferAttachmentParameteri(GL30C.GL_DRAW_FRAMEBUFFER,point,GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE)!=GL11C.GL_TEXTURE
                || GL30C.glGetFramebufferAttachmentParameteri(GL30C.GL_DRAW_FRAMEBUFFER,point,GL30C.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LEVEL)!=0)
            throw new IllegalArgumentException("Scene destination requires base-level texture attachments");
        return GL30C.glGetFramebufferAttachmentParameteri(GL30C.GL_DRAW_FRAMEBUFFER,point,GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
    }
    @Override public void close() {
        RenderSystem.assertOnRenderThread();if(closed) return;closed=true;display.close();
    }
}
