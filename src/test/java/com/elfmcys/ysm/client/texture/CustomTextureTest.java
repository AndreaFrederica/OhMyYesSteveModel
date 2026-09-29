package com.elfmcys.ysm.client.texture;

import com.elfmcys.ysm.natives.image.ImageSource;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CustomTextureTest {
    private static final ImageSource UNUSED_SOURCE = () -> { throw new IOException("unused"); };
    @BeforeAll static void establishRenderOwner() {
        if (!RenderSystem.isOnRenderThread()) RenderSystem.initRenderThread();
    }
    private static CustomTexture texture(Executor workers, CustomTexture.Decoder decoder, CustomTexture.Uploader uploader) {
        return new CustomTexture(UNUSED_SOURCE, workers, decoder, uploader, ignored -> {});
    }

    @Test void loadReturnsBeforeDecodeAndUploadWaitsForRenderOwner() {
        var workers = new QueueExecutor();
        var events = new ArrayList<String>();
        var images = new ArrayList<NativeImage>();
        var texture = texture(workers, source -> {
            events.add("decode"); var pixels = new NativeImage(1, 1, false); images.add(pixels); return pixels;
        }, (ignored, pixels) -> { pixels.getPixelRGBA(0, 0); events.add("upload"); });
        texture.load(null);
        assertTrue(events.isEmpty());
        assertFalse(texture.uploadReady());
        workers.runNext();
        assertEquals(List.of("decode"), events);
        assertFalse(texture.ready());
        assertTrue(texture.uploadReady());
        assertEquals(List.of("decode", "upload"), events);
        assertTrue(texture.ready()); assertClosed(images.get(0));
        texture.load(null); workers.runNext(); texture.uploadReady();
        assertEquals(List.of("decode", "upload", "decode", "upload"), events);
        assertClosed(images.get(1));
    }

    @Test void cancellationBeforeWorkerStartSkipsDecode() {
        var workers = new QueueExecutor();
        var texture = texture(workers, source -> { throw new AssertionError("stale decode"); },
                (ignored, pixels) -> { throw new AssertionError("stale upload"); });
        texture.load(null); texture.cancelPendingLoad(); workers.runNext();
        assertFalse(texture.uploadReady());
    }

    @Test void cancellationAfterDecodeClosesOwnedPixelsWithoutUpload() {
        var workers = new QueueExecutor(); var pixels = new NativeImage(1, 1, false);
        var texture = texture(workers, source -> pixels,
                (ignored, image) -> { throw new AssertionError("stale upload"); });
        texture.load(null); workers.runNext(); texture.close();
        assertClosed(pixels); assertFalse(texture.uploadReady());
    }

    @Test void closeDuringRealWorkerDecodeDiscardsLatePixels() throws Exception {
        var workers = java.util.concurrent.Executors.newSingleThreadExecutor();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var pixels = new NativeImage(1, 1, false);
        var owner = Thread.currentThread();
        var texture = texture(workers, source -> {
            assertNotSame(owner, Thread.currentThread()); entered.countDown();
            if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("timeout");
            return pixels;
        }, (ignored, image) -> { throw new AssertionError("late upload"); });
        try {
            texture.load(null);
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            texture.close(); release.countDown();
            workers.shutdown(); assertTrue(workers.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
            assertClosed(pixels); assertFalse(texture.uploadReady());
        } finally { release.countDown(); workers.shutdownNow(); }
    }

    @Test void reloadDiscardsPreviousDecodeAndPublishesOnlyTheNewGeneration() {
        var workers = new QueueExecutor(); var images = new ArrayList<NativeImage>();
        var uploads = new AtomicInteger();
        var texture = texture(workers, source -> {
            var image = new NativeImage(1, 1, false); images.add(image); return image;
        }, (ignored, pixels) -> uploads.incrementAndGet());
        texture.load(null); workers.runNext(); texture.load(null);
        assertClosed(images.get(0)); assertFalse(texture.uploadReady());
        workers.runNext(); texture.uploadReady();
        assertEquals(1, uploads.get()); assertClosed(images.get(1));
    }

    @Test void decodeAndUploadFailuresRemainVisibleAndDoNotLeakPixels() {
        var workers = new QueueExecutor(); var failure = new IOException("broken");
        var attempts = new AtomicInteger();
        var texture = texture(workers, source -> { attempts.incrementAndGet(); throw failure; },
                (ignored, image) -> fail("upload after failure"));
        texture.load(null); workers.runNext(); texture.uploadReady(); texture.load(null);
        assertSame(failure, texture.failure().orElseThrow()); assertEquals(1, attempts.get());
        var image = new NativeImage(1, 1, false); var uploadFailure = new IllegalStateException("upload");
        var brokenUpload = texture(workers, source -> image, (ignored, pixels) -> { throw uploadFailure; });
        brokenUpload.load(null); workers.runNext(); brokenUpload.uploadReady(); brokenUpload.close();
        assertSame(uploadFailure, brokenUpload.failure().orElseThrow()); assertClosed(image);
    }

    @Test void executorRejectionBecomesAnObservableFailure() {
        var failure = new java.util.concurrent.RejectedExecutionException("closed");
        var texture = texture(command -> { throw failure; }, source -> { throw new AssertionError(); },
                (ignored, image) -> fail("upload"));
        texture.load(null); assertSame(failure, texture.failure().orElseThrow());
        assertFalse(texture.uploadReady()); texture.close();
    }

    @Test void registryBudgetsUploadsAndRetiresDecodedPixelsImmediatelyOnPageRelease() {
        var workers = new QueueExecutor();
        var uploaded = new AtomicInteger();
        var textures = new ArrayList<CustomTexture>();
        var images = new ArrayList<NativeImage>();
        var holders = new ArrayList<TextureHolder>();
        var registry = new CustomTextureManager.Registry(new CustomTextureManager.Host() {
            public void register(net.minecraft.resources.ResourceLocation id,
                                 net.minecraft.client.renderer.texture.AbstractTexture texture) {
                try { texture.load(null); } catch (IOException error) { throw new AssertionError(error); }
            }
            public void release(net.minecraft.resources.ResourceLocation id) {}
        });
        for (int i = 0; i < 5; i++) {
            var pixels = new NativeImage(1, 1, false); images.add(pixels);
            var texture = texture(workers, source -> pixels, (ignored, image) -> uploaded.incrementAndGet());
            textures.add(texture); holders.add(registry.register(texture, 200)); workers.runNext();
        }
        registry.release(textures.get(4));
        assertClosed(images.get(4)); // Does not wait for the 200-tick GPU removal delay.
        registry.uploadFrame();
        assertTrue(uploaded.get() > 0 && uploaded.get() <= 2);
        for (int i = 0; i < 4; i++) registry.uploadFrame();
        assertEquals(4, uploaded.get());
        images.forEach(CustomTextureTest::assertClosed);
        textures.forEach(registry::release);
    }

    private static void assertClosed(NativeImage image) {
        assertThrows(IllegalStateException.class, () -> image.getPixelRGBA(0, 0));
    }
    private static final class QueueExecutor implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        public void execute(Runnable task) { tasks.add(task); }
        void runNext() { tasks.removeFirst().run(); }
    }
}
