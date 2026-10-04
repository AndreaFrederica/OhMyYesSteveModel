package com.elfmcys.ysm.client.texture;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.natives.image.ImageSource;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;

/** GUI images decode on workers; the render owner polls complete pixels for upload. */
public class CustomTexture extends AbstractTexture {
    private final ImageSource source;
    private final Executor workers;
    private final Decoder decoder;
    private final Uploader uploader;
    private final java.util.function.Consumer<CustomTexture> initializer;
    private volatile @Nullable Throwable failure;
    private @Nullable Attempt pending;
    private boolean initialized;
    private boolean ready;

    public CustomTexture(ImageSource source, Executor workers) {
        this(source, workers, CustomTexture::decode, CustomTexture::upload, texture -> {
            // A defined transparent texel is safe to display while decoding is pending.
            try (var blank = new NativeImage(1, 1, true)) { upload(texture, blank); }
        });
    }

    CustomTexture(ImageSource source, Executor workers, Decoder decoder, Uploader uploader,
                  java.util.function.Consumer<CustomTexture> initializer) {
        this.source = Objects.requireNonNull(source, "source");
        this.workers = Objects.requireNonNull(workers, "workers");
        this.decoder = Objects.requireNonNull(decoder, "decoder");
        this.uploader = Objects.requireNonNull(uploader, "uploader");
        this.initializer = Objects.requireNonNull(initializer, "initializer");
    }

    @Override
    public void load(ResourceManager resourceManager) {
        RenderSystem.assertOnRenderThreadOrInit();
        if (failure != null) return;
        cancelPendingLoad();
        if (!initialized) {
            initializer.accept(this);
            initialized = true;
        }
        var attempt = new Attempt();
        attempt.job = new FutureTask<>(() -> { decode(attempt); return null; });
        synchronized (this) { pending = attempt; }
        try {
            workers.execute(attempt.job);
        } catch (RuntimeException error) {
            synchronized (this) {
                if (pending == attempt) { pending = null; failure = error; }
            }
            attempt.job.cancel(false);
        }
    }

    private void decode(Attempt attempt) {
        synchronized (this) { if (pending != attempt) return; }
        NativeImage pixels = null;
        Throwable error = null;
        try {
            pixels = Objects.requireNonNull(decoder.decode(source), "GUI decoder returned null");
        } catch (Throwable decodingFailure) { error = decodingFailure; }
        synchronized (this) {
            if (pending == attempt) {
                attempt.pixels = pixels;
                attempt.failure = error;
                attempt.done = true;
                return;
            }
        }
        if (pixels != null) pixels.close(); // The page/reload changed while decoding.
    }

    /** Render-thread only; returns true if a completed decode consumed this frame's budget. */
    boolean uploadReady() {
        RenderSystem.assertOnRenderThreadOrInit();
        final Attempt attempt;
        synchronized (this) {
            if (pending == null || !pending.done) return false;
            attempt = pending;
            pending = null;
        }
        if (attempt.failure != null) {
            failure = attempt.failure;
            YesSteveModel.LOGGER.error("Failed to decode standalone model texture from {}", source, failure);
            return true;
        }
        try (var pixels = attempt.pixels) {
            uploader.upload(this, pixels);
            ready = true;
        } catch (Exception error) {
            failure = error;
            YesSteveModel.LOGGER.error("Failed to upload standalone model texture from {}", source, error);
        }
        return true;
    }

    /** Retiring a page cancels work immediately; GPU deletion remains with the texture registry. */
    public synchronized void cancelPendingLoad() {
        var attempt = pending;
        pending = null;
        if (attempt != null) {
            attempt.job.cancel(false);
            if (attempt.pixels != null) attempt.pixels.close();
        }
    }

    @Override public void close() { cancelPendingLoad(); }
    public boolean ready() { return ready; }
    public Optional<Throwable> failure() { return Optional.ofNullable(failure); }

    private static NativeImage decode(ImageSource source) throws Exception {
        try (var image = source.open()) { return image.decode(); }
    }

    private static void upload(CustomTexture texture, NativeImage img) {
        TextureUtil.prepareImage(texture.getId(), 0, img.getWidth(), img.getHeight());
        img.upload(0, 0, 0, 0, 0, img.getWidth(), img.getHeight(), false, false, false, false);
    }

    private static final class Attempt {
        FutureTask<Void> job;
        NativeImage pixels;
        Throwable failure;
        boolean done;
    }

    @FunctionalInterface interface Decoder { NativeImage decode(ImageSource source) throws Exception; }
    @FunctionalInterface interface Uploader { void upload(CustomTexture texture, NativeImage image); }
}
