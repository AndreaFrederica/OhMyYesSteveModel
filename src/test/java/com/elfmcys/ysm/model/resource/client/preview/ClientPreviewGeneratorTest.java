package com.elfmcys.ysm.model.resource.client.preview;

import com.elfmcys.ysm.model.resource.client.ClientModelRenderTargetManager;

import com.elfmcys.ysm.model.resource.client.AcquireResult;
import com.elfmcys.ysm.model.resource.client.BakeProfile;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.model.resource.client.ResourceLease;
import com.elfmcys.ysm.model.resource.client.ResourceRequest;
import com.elfmcys.ysm.format.schema.file.ChunkDataSource;
import com.elfmcys.ysm.format.schema.model.ModelFileView;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelRepresentation;
import com.elfmcys.ysm.model.storage.TestPreviews;
import com.elfmcys.ysm.util.UnsafeUtil;
import com.mojang.blaze3d.platform.NativeImage;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientPreviewGeneratorTest {
    @Test
    void cancelledPagesFreeQueueCapacityWithoutWaitingForATick() {
        var workers = new QueueExecutor();
        try (var generator = new ClientPreviewGenerator(ignored -> { throw new AssertionError(); },
                (request, lease) -> { throw new AssertionError(); },
                image -> { throw new AssertionError(); }, Runnable::run, workers, 1, 2)) {
            for (int page = 0; page < 100; page++) {
                var first = generator.generate(content());
                var second = generator.generate(content());
                assertFalse(first.isDone()); assertFalse(second.isDone());
                first.cancel(false); second.cancel(false);
            }
            var current = generator.generate(content());
            assertFalse(current.isDone());
        }
    }

    @Test
    void multipleTicksInOneFrameDoNotBurstPreviewDrawsAndCancelledDeferredLeaseIsReleased() {
        var leases = new java.util.ArrayList<FakeLease>();
        var renders = new AtomicInteger();
        try (var generator = new ClientPreviewGenerator(ignored -> {
            var lease = new FakeLease(); leases.add(lease); return ready(lease);
        }, (request, lease) -> {
            renders.incrementAndGet(); return new NativeImage(1, 1, false);
        }, image -> TestPreviews.blank(), Runnable::run, Runnable::run)) {
            var first = generator.generate(content());
            var second = generator.generate(content());
            var third = generator.generate(content());
            generator.tick(); generator.tick(); generator.tick(); generator.tick();
            assertEquals(1, renders.get()); assertTrue(first.isDone());
            second.cancel(false); generator.tick();
            assertEquals(1, leases.get(1).closes.get());
            assertEquals(1, renders.get());
            generator.beginRenderFrame(); generator.tick();
            assertEquals(2, renders.get());
            generator.tick(); generator.tick(); assertTrue(third.isDone());
        }
    }
    private static final Hash256 MODEL_ID = hash(1);
    private static final ResourceRequest REQUEST = new ResourceRequest(
            MODEL_ID, "player", "", new BakeProfile("test"));

    @Test
    void ownerTickAdmitsEveryStageAndCallbacksOnlyPublishFacts() throws Exception {
        var acquisition = new CompletableFuture<AcquireResult>();
        var lease = new FakeLease();
        var host = new QueueExecutor();
        var workers = new QueueExecutor();
        var pixels = new NativeImage(1, 1, false);
        var expected = TestPreviews.blank();
        try (var generator = new ClientPreviewGenerator(
                ignored -> awaiting(lease, acquisition),
                (request, acquired) -> {
                    assertSame(REQUEST, request);
                    assertEquals(0, lease.closes.get());
                    return pixels;
                },
                image -> {
                    image.getPixelRGBA(0, 0);
                    return expected;
                }, host, workers)) {
            var result = generator.generate(content());
            assertFalse(result.isDone());
            assertEquals(0, generator.activeOperationCount());

            generator.tick();
            assertEquals(1, generator.activeOperationCount());

            acquisition.complete(new AcquireResult.Ready(target()));
            assertEquals(0, host.size());
            generator.tick();
            assertEquals(1, host.size());
            assertEquals(0, workers.size());
            assertFalse(result.isDone());

            host.runNext();
            assertEquals(1, lease.closes.get());
            assertEquals(0, workers.size());
            generator.tick();
            assertEquals(1, workers.size());
            assertFalse(result.isDone());

            workers.runNext();
            assertFalse(result.isDone());
            assertEquals(1, generator.activeOperationCount());
            generator.tick();
            assertSame(expected, result.join());
            assertEquals(0, generator.activeOperationCount());
            assertClosed(pixels);
        }
    }

    @Test
    void hostFailureClosesLeaseWithoutStartingEncoding() {
        var failure = new IllegalStateException("host failed");
        var lease = new FakeLease();
        var workers = new QueueExecutor();
        try (var generator = new ClientPreviewGenerator(
                ignored -> ready(lease),
                (request, acquired) -> {
                    throw failure;
                }, image -> {
                    throw new AssertionError("encoding must not start");
                }, Runnable::run, workers)) {
            var result = generator.generate(content());
            generator.tick();
            generator.tick();
            assertFalse(result.isDone());
            generator.tick();
            var thrown = assertThrows(CompletionException.class,
                    result::join);
            assertSame(failure, thrown.getCause());
            assertEquals(1, lease.closes.get());
            assertEquals(0, workers.size());
            assertEquals(0, generator.activeOperationCount());
        }
    }

    @Test
    void encodingFailureClosesPixelsAndLease() {
        var failure = new IllegalStateException("encode failed");
        var lease = new FakeLease();
        var pixels = new NativeImage(1, 1, false);
        try (var generator = new ClientPreviewGenerator(
                ignored -> ready(lease),
                (request, acquired) -> pixels,
                image -> {
                    throw failure;
                }, Runnable::run, Runnable::run)) {
            var result = generator.generate(content());
            generator.tick();
            generator.tick();
            generator.tick();
            assertFalse(result.isDone());
            generator.tick();
            var thrown = assertThrows(CompletionException.class,
                    result::join);
            assertSame(failure, thrown.getCause());
            assertEquals(1, lease.closes.get());
            assertClosed(pixels);
            assertEquals(0, generator.activeOperationCount());
        }
    }

    @Test
    void previewAndExportShareOneAdmissionUntilOwnerObservedTerminal() {
        var firstAcquisition = new CompletableFuture<AcquireResult>();
        var firstLease = new FakeLease();
        var targetStarts = new AtomicInteger();
        var host = new QueueExecutor();
        var workers = new QueueExecutor();
        var firstPixels = new NativeImage(1, 1, false);
        try (var generator = new ClientPreviewGenerator(
                ignored -> {
                    targetStarts.incrementAndGet();
                    return awaiting(firstLease, firstAcquisition);
                }, (request, lease) -> firstPixels,
                image -> TestPreviews.blank(), host, workers, 1, 2)) {
            var first = generator.generate(content());
            var second = generator.export("pack/model", null);

            generator.tick();
            assertEquals(1, targetStarts.get());
            assertEquals(1, generator.activeOperationCount());

            firstAcquisition.complete(new AcquireResult.Ready(target()));
            assertEquals(1, targetStarts.get());
            generator.tick();
            host.runNext();
            generator.tick();
            workers.runNext();

            assertFalse(first.isDone());
            assertFalse(second.isDone());
            assertEquals(1, targetStarts.get());
            assertEquals(1, generator.activeOperationCount());

            generator.tick();
            first.join();
            assertEquals(1, targetStarts.get());
            assertEquals(1, generator.activeOperationCount());
            assertFalse(second.isDone());
            assertEquals(1, workers.size());

            workers.runNext();
            assertFalse(second.isDone());
            generator.tick();
            assertThrows(CompletionException.class, second::join);
            assertEquals(0, generator.activeOperationCount());
            assertClosed(firstPixels);
        }
    }

    @Test
    void cancellationClosesLateLeaseBehindProductionTargetFuture() {
        var upstream = new CompletableFuture<AcquireResult>();
        var lease = new FakeLease();
        var host = new QueueExecutor();
        var workers = new QueueExecutor();
        try (var generator = new ClientPreviewGenerator(
                ignored -> awaiting(lease, upstream),
                (request, acquired) -> {
                    throw new AssertionError("cancelled target must not reach the host");
                }, image -> {
                    throw new AssertionError("cancelled target must not reach encoding");
                }, host, workers)) {
            var result = generator.generate(content());
            generator.tick();
            assertEquals(1, generator.activeOperationCount());

            assertEquals(true, result.cancel(false));
            generator.tick();
            assertEquals(1, lease.closes.get());
            assertEquals(0, generator.activeOperationCount());

            // The exact acquisition can finish after its consumer interest was cancelled.
            assertEquals(true, upstream.complete(new AcquireResult.Ready(target())));
            assertEquals(1, lease.closes.get());
            assertEquals(0, host.size());
            assertEquals(0, workers.size());
        }
    }

    @Test
    void closeCancelsAwaitingAcquisitionBeforeDroppingItsToken() {
        var terminal = new CompletableFuture<AcquireResult>();
        var lease = new FakeLease();
        var generator = new ClientPreviewGenerator(
                ignored -> awaiting(lease, terminal),
                (request, acquired) -> {
                    throw new AssertionError("closed target must not reach the host");
                }, image -> {
                    throw new AssertionError("closed target must not reach encoding");
                }, Runnable::run, Runnable::run);
        generator.generate(content());
        generator.tick();

        generator.close();

        assertEquals(1, lease.closes.get());
        assertEquals(0, generator.activeOperationCount());
        assertTrue(terminal.complete(new AcquireResult.Ready(target())));
        assertEquals(1, lease.closes.get());
    }

    private static ClientPreviewGenerator.AwaitingTarget ready(ResourceLease lease) {
        return awaiting(lease, CompletableFuture.completedFuture(
                new AcquireResult.Ready(target())));
    }

    private static ClientPreviewGenerator.AwaitingTarget awaiting(
            ResourceLease lease, CompletableFuture<AcquireResult> terminal) {
        return new ClientPreviewGenerator.AwaitingTarget(REQUEST,
                new ClientModelRenderTargetManager.ReadyAcquisition(
                        REQUEST, lease, terminal));
    }

    private static ModelRenderTarget target() {
        try {
            return (ModelRenderTarget) UnsafeUtil.getUnsafe()
                    .allocateInstance(ModelRenderTarget.class);
        } catch (InstantiationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void assertClosed(NativeImage image) {
        assertThrows(IllegalStateException.class, () -> image.getPixelRGBA(0, 0));
    }

    private static ModelContent content() {
        return new ModelContent() {
            @Override
            public Hash256 modelId() {
                return MODEL_ID;
            }

            @Override
            public ModelRepresentation representation() {
                return null;
            }

            @Override
            public ModelFileView modelFile() {
                return null;
            }

            @Override
            public ChunkDataSource chunks() {
                return null;
            }
        };
    }

    private static Hash256 hash(int marker) {
        var bytes = new byte[Hash256.SIZE];
        bytes[0] = (byte) marker;
        return new Hash256(bytes);
    }

    private static final class QueueExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.addLast(command);
        }

        private int size() {
            return tasks.size();
        }

        private void runNext() {
            tasks.removeFirst().run();
        }
    }

    private static final class FakeLease implements ResourceLease {
        private final AtomicInteger closes = new AtomicInteger();

        @Override
        public AcquireResult poll() {
            return new AcquireResult.Pending();
        }

        @Override
        public boolean isCurrent(ResourceRequest request) {
            return true;
        }

        @Override
        public void cancelPending() {
            close();
        }

        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }
}
