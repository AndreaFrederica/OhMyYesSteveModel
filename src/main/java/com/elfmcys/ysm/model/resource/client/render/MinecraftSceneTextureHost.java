package com.elfmcys.ysm.model.resource.client.render;

import com.elfmcys.ysm.YesSteveModel;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;
import java.io.IOException;

/** Render-owner upload of float samples already in the material's working space; no parsing or color conversion here. */
public final class MinecraftSceneTextureHost implements SceneTexturePublication.Host<ResourceLocation> {
    public static final MinecraftSceneTextureHost INSTANCE = new MinecraftSceneTextureHost();
    private long nextId;
    private MinecraftSceneTextureHost() {}

    @Override @SuppressWarnings("removal")
    public ResourceLocation register(PreparedSceneTextures.Pixels pixels) {
        RenderSystem.assertOnRenderThread();
        var manager = Minecraft.getInstance().getTextureManager();
        var id = new ResourceLocation(YesSteveModel.MOD_ID, "scene-textures/" + ++nextId);
        var texture = new Texture(pixels);
        try {
            manager.register(id, texture);
            if (manager.getTexture(id) != texture || !texture.ready) {
                throw new IllegalStateException("Host substituted or failed a scene texture: " + id);
            }
            return id;
        } catch (RuntimeException | Error failure) {
            try { manager.release(id); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            // A failed/IO-substituted registration may never have transferred this object to the host.
            // releaseId is idempotent; this object is not shared with another mapping.
            try { texture.close(); texture.releaseId(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    @Override public void release(ResourceLocation mapping) {
        RenderSystem.assertOnRenderThread();
        Minecraft.getInstance().getTextureManager().release(mapping);
    }

    private static final class Texture extends AbstractTexture {
        private PreparedSceneTextures.Pixels pixels;
        private boolean ready;
        private boolean closed;
        Texture(PreparedSceneTextures.Pixels pixels) { this.pixels = pixels; }

        @Override public void load(ResourceManager resources) throws IOException {
            RenderSystem.assertOnRenderThreadOrInit();
            if (closed) throw new IOException("Scene texture is closed");
            ready = false;
            var upload = pixels;
            if (upload == null) throw new IOException("Scene texture pixels were already released");
            int max = GL11C.glGetInteger(GL11C.GL_MAX_TEXTURE_SIZE);
            if (upload.width() > max || upload.height() > max) throw new IOException("Scene image exceeds host texture dimensions");
            int previousError = GL11C.glGetError();
            if (previousError != GL11C.GL_NO_ERROR) throw new IOException("Host GL error before scene upload: " + previousError);
            int binding = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
            int buffer = GL11C.glGetInteger(GL21C.GL_PIXEL_UNPACK_BUFFER_BINDING);
            int alignment = GL11C.glGetInteger(GL11C.GL_UNPACK_ALIGNMENT);
            int rowLength = GL11C.glGetInteger(GL11C.GL_UNPACK_ROW_LENGTH);
            int skipRows = GL11C.glGetInteger(GL11C.GL_UNPACK_SKIP_ROWS);
            int skipPixels = GL11C.glGetInteger(GL11C.GL_UNPACK_SKIP_PIXELS);
            int swapBytes = GL11C.glGetInteger(GL11C.GL_UNPACK_SWAP_BYTES);
            var data = MemoryUtil.memAllocFloat(upload.rgba().size());
            try {
                data.put(upload.rgba().view()).flip();
                GL15C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER, 0);
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, getId());
                GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, 1);
                GL11C.glPixelStorei(GL11C.GL_UNPACK_ROW_LENGTH, 0);
                GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_ROWS, 0);
                GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_PIXELS, 0);
                GL11C.glPixelStorei(GL11C.GL_UNPACK_SWAP_BYTES, 0);
                GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, GL30C.GL_RGBA16F, upload.width(), upload.height(), 0,
                        GL11C.GL_RGBA, GL11C.GL_FLOAT, data);
                var sampler = upload.sampler();
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, sampler.magFilter());
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, sampler.minFilter());
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, sampler.wrapS());
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, sampler.wrapT());
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL12C.GL_TEXTURE_BASE_LEVEL, 0);
                int lastLevel = sampler.mipmaps() ? 31 - Integer.numberOfLeadingZeros(Math.max(pixels.width(), pixels.height())) : 0;
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL12C.GL_TEXTURE_MAX_LEVEL, lastLevel);
                if (sampler.mipmaps()) GL30C.glGenerateMipmap(GL11C.GL_TEXTURE_2D);
                int error = GL11C.glGetError();
                if (error != GL11C.GL_NO_ERROR) throw new IOException("Scene texture upload failed: GL " + error);
                ready = true;
            } finally {
                // The host texture owns the GPU copy now; retaining the CPU FloatData here
                // kept every published 4K scene image alive for the lifetime of the cache.
                pixels = null;
                MemoryUtil.memFree(data);
                GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, alignment);
                GL11C.glPixelStorei(GL11C.GL_UNPACK_ROW_LENGTH, rowLength);
                GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_ROWS, skipRows);
                GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_PIXELS, skipPixels);
                GL11C.glPixelStorei(GL11C.GL_UNPACK_SWAP_BYTES, swapBytes);
                GL15C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER, buffer);
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, binding);
            }
        }
        @Override public void close() { closed = true; ready = false; }
    }
}
