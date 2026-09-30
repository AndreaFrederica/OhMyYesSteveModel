package com.elfmcys.ysm.model.resource.client.render;

import com.elfmcys.ysm.format.schema.file.ChunkDataSource;
import com.elfmcys.ysm.format.schema.file.PBRImageSources;
import com.elfmcys.ysm.format.schema.model.ModelFileView;
import com.elfmcys.ysm.format.schema.model.views.ModelInfoView;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.domain.ModelRepresentation;
import com.elfmcys.ysm.model.resource.client.AcquireResult;
import com.elfmcys.ysm.model.resource.client.BakeProfile;
import com.elfmcys.ysm.model.resource.client.CommonAsset;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.model.resource.client.ModelResourceFailures;
import com.elfmcys.ysm.model.resource.client.RenderTargetResources;
import com.elfmcys.ysm.model.resource.client.ResourceFailure;
import com.elfmcys.ysm.model.resource.client.ResourceRequest;
import com.elfmcys.ysm.natives.image.ImageSource;
import com.elfmcys.ysm.util.UnsafeUtil;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelRenderTargetCacheTest {
    private static final BakeProfile PROFILE = new BakeProfile("test");
    private static final Object OWNER = new Object();
    private static final AtomicInteger NEXT_CONTAINER_ID = new AtomicInteger(100);

    @BeforeAll
    static void establishRenderOwner() {
        if (!RenderSystem.isOnRenderThread()) {
            RenderSystem.initRenderThread();
        }
    }

    @Test
    void slowPublicationDefersLaterResultsAndLiveCountLimitStillApplies() throws Exception {
        try (var cache = new ModelRenderTargetCache()) {
            cache.configurePublicationBudget(() -> 4, () -> 1);
            ModelRenderTargetCache.CurrentLookup slowLookup = (identity, key, request) -> {
                // Simulate one indivisible host upload exceeding the soft budget.
                var started = System.nanoTime();
                while (System.nanoTime() - started < 5_000_000) {
                    java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
                }
                return true;
            };
            var leases = new java.util.ArrayList<com.elfmcys.ysm.model.resource.client.ResourceLease>();
            for (int i = 0; i < 3; i++) {
                var id = hash(70 + i);
                var loaded = target();
                leases.add(cache.getOrStart(request(id), content(id), key(),
                        ignored -> ready(loaded), slowLookup, OWNER));
            }
            cache.tick();
            assertEquals(1, cache.readyCount());
            assertEquals(2, cache.loadingCount());
            cache.configurePublicationBudget(() -> 1, () -> 100);
            cache.tick();
            assertEquals(2, cache.readyCount());
            assertEquals(1, cache.loadingCount());
            cache.tick();
            assertEquals(3, cache.readyCount());
            assertEquals(0, cache.loadingCount());
            leases.forEach(com.elfmcys.ysm.model.resource.client.ResourceLease::close);
        }
    }

    @Test
    void multiConsumerReadyReuseAndEvictionOnlyDropCacheReachability() throws Exception {
        var hash = hash(1);
        var content = content(hash);
        var pending = new CompletableFuture<ModelRenderTargetLoader.LoadResult>();
        var firstTarget = target();
        var replacementTarget = target();
        var loads = new AtomicInteger();
        var closes = new AtomicInteger();
        try (var cache = new ModelRenderTargetCache(
                ignored -> closes.incrementAndGet(), ignored -> {},
                ignored -> { throw new AssertionError("Testing candidates bypass the host"); },
                () -> 4, () -> 0)) {
            ModelRenderTargetCache.Loader loader = ignored -> {
                loads.incrementAndGet();
                return pending;
            };
            var first = cache.getOrStart(request(hash), content, key(), loader,
                    current(), OWNER);
            var second = cache.getOrStart(request(hash), content, key(), loader,
                    current(), OWNER);

            assertNotSame(first, second);
            assertEquals(1, loads.get());
            pending.complete(readyResult(firstTarget));
            cache.tick();
            assertSame(firstTarget,
                    assertInstanceOf(AcquireResult.Ready.class, first.poll()).target());
            assertSame(firstTarget,
                    assertInstanceOf(AcquireResult.Ready.class, second.poll()).target());
            assertEquals(1, cache.readyCount());

            cache.tick();
            assertEquals(1, cache.readyCount());
            first.close();
            second.close();
            cache.tick();
            assertEquals(0, cache.readyCount());
            assertEquals(0, closes.get());

            var replacement = cache.getOrStart(request(hash), content, key(),
                    ignored -> {
                        loads.incrementAndGet();
                        return ready(replacementTarget);
                    }, current(), OWNER);
            cache.tick();
            assertEquals(2, loads.get());
            assertSame(replacementTarget,
                    assertInstanceOf(AcquireResult.Ready.class, replacement.poll()).target());
            replacement.close();
        }
        assertEquals(0, closes.get());
    }

    @Test
    void failedFlightLosesEligibilityBeforeSameKeyRetry() throws Exception {
        var hash = hash(2);
        var content = content(hash);
        var loads = new AtomicInteger();
        var loaded = target();
        try (var cache = new ModelRenderTargetCache()) {
            var first = cache.getOrStart(request(hash), content, key(), ignored -> {
                loads.incrementAndGet();
                return failed(ResourceFailure.Kind.DETERMINISTIC, new IOException("broken"));
            }, current(), OWNER);
            cache.tick();
            assertEquals(ResourceFailure.Kind.DETERMINISTIC,
                    assertInstanceOf(AcquireResult.Failed.class, first.poll()).failure().kind());
            assertEquals(0, cache.loadingCount());

            var retry = cache.getOrStart(request(hash), content, key(), ignored -> {
                loads.incrementAndGet();
                return ready(loaded);
            }, current(), OWNER);
            cache.tick();
            assertEquals(2, loads.get());
            assertSame(loaded,
                    assertInstanceOf(AcquireResult.Ready.class, retry.poll()).target());
        }
    }

    @Test
    void preAdmissionCollapseHasNoTransportEffect() {
        var hash = hash(3);
        var content = content(hash);
        var flight = new AtomicReference<BooleanSupplier>();
        var transportCancels = new AtomicInteger();
        try (var cache = new ModelRenderTargetCache()) {
            var lease = cache.getOrStart(request(hash), content, key(), context -> {
                flight.set(context);
                return new CompletableFuture<>();
            }, current(), OWNER);

            lease.cancelPending();
            cache.tick();
            assertTrue(flight.get().getAsBoolean());
            assertEquals(0, transportCancels.get());
            assertEquals(0, cache.loadingCount());
        }
    }

    @Test
    void postAdmissionLastInterestCancelsExactTransferOnce() {
        var hash = hash(4);
        var content = content(hash);
        var pending = new CountingFuture<ModelRenderTargetLoader.LoadResult>();
        var reports = new AtomicInteger();
        try (var cache = new ModelRenderTargetCache(
                     ignored -> {}, ignored -> reports.incrementAndGet())) {
            ModelRenderTargetCache.Loader loader = context -> {
                return pending;
            };
            var first = cache.getOrStart(request(hash), content, key(), loader,
                    current(), OWNER);
            var second = cache.getOrStart(request(hash), content, key(), loader,
                    current(), OWNER);

            first.cancelPending();
            cache.tick();
            assertEquals(0, pending.cancelCalls.get());
            assertEquals(1, cache.loadingCount());
            second.cancelPending();
            second.cancelPending();
            cache.tick();
            assertEquals(1, pending.cancelCalls.get());
            assertEquals(0, cache.loadingCount());
            assertEquals(0, reports.get());
        }
    }

    @Test
    void exceptionalLoaderCompletionReportsContractViolationExactlyOnce() {
        var hash = hash(13);
        var content = content(hash);
        var pending = new CompletableFuture<ModelRenderTargetLoader.LoadResult>();
        var reported = new AtomicReference<Throwable>();
        try (var cache = new ModelRenderTargetCache(
                     ignored -> {}, failure -> {
                         assertTrue(reported.compareAndSet(null, failure));
                     })) {
            var lease = cache.getOrStart(request(hash), content, key(), ignored -> pending,
                    current(), OWNER);
            var cause = new IOException("loader failed exceptionally");

            pending.completeExceptionally(cause);
            cache.tick();

            var failure = assertInstanceOf(AcquireResult.Failed.class, lease.poll());
            var violation = assertInstanceOf(IllegalStateException.class,
                    failure.failure().cause());
            assertSame(cause, violation.getCause());
            assertSame(violation, reported.get());
            assertEquals(0, cache.loadingCount());
        }
    }

    @Test
    void sessionRetirementCancelsOnlyItsExactPendingWork() {
        var epoch = new Object();
        var process = new Object();
        var epochPending = new CountingFuture<ModelRenderTargetLoader.LoadResult>();
        var processPending = new CountingFuture<ModelRenderTargetLoader.LoadResult>();
        try (var cache = new ModelRenderTargetCache()) {
            cache.getOrStart(request(hash(40)), content(hash(40)), key(),
                    ignored -> epochPending, current(), epoch);
            cache.getOrStart(request(hash(41)), content(hash(41)), key(),
                    ignored -> processPending, current(), process);

            cache.clearSession(epoch);
            cache.tick();

            assertEquals(1, epochPending.cancelCalls.get());
            assertEquals(0, processPending.cancelCalls.get());
            assertEquals(1, cache.loadingCount());
        }
    }

    @Test
    void readyProbeNeverStartsWorkAndReturnsAnIndependentConsumer() throws Exception {
        var hash = hash(60);
        var content = content(hash);
        var loaded = target();
        var loads = new AtomicInteger();
        try (var cache = new ModelRenderTargetCache()) {
            assertTrue(cache.findReady(request(hash), content, key(), current()).isEmpty());

            var builder = cache.getOrStart(request(hash), content, key(), ignored -> {
                loads.incrementAndGet();
                return ready(loaded);
            }, current(), OWNER);
            cache.tick();
            var probe = cache.findReady(request(hash), content, key(), current()).orElseThrow();

            assertEquals(1, loads.get());
            assertNotSame(builder, probe);
            assertSame(loaded,
                    assertInstanceOf(AcquireResult.Ready.class, probe.poll()).target());
            builder.close();
            probe.close();
        }
    }

    @Test
    void cachedProbeDoesNotJoinOrKeepAnExistingFullFlightAlive() {
        var hash = hash(61);
        var content = content(hash);
        var fullWork = new CountingFuture<ModelRenderTargetLoader.LoadResult>();
        var cacheLoads = new AtomicInteger();
        try (var cache = new ModelRenderTargetCache()) {
            var full = cache.getOrStart(request(hash), content, key(),
                    ignored -> fullWork, current(), OWNER);

            var probe = cache.getOrStartCached(request(hash), content, key(), ignored -> {
                cacheLoads.incrementAndGet();
                return new CompletableFuture<>();
            }, current(), OWNER);

            assertTrue(probe.join().isEmpty());
            assertEquals(0, cacheLoads.get());
            full.cancelPending();
            cache.tick();
            assertEquals(1, fullWork.cancelCalls.get());
        }
    }

    @Test
    void fullOfflineDemandReplacesAProbeOnlyFlight() throws Exception {
        var hash = hash(62);
        var content = content(hash);
        var probeWork = new CountingFuture<ModelRenderTargetLoader.LoadResult>();
        var loaded = target();
        try (var cache = new ModelRenderTargetCache()) {
            var probe = cache.getOrStartCached(request(hash), content, key(),
                    ignored -> probeWork, current(), OWNER);
            var full = cache.getOrStartOffline(request(hash), content, key(),
                    ignored -> ready(loaded), current(), OWNER);
            cache.tick();

            assertEquals(1, probeWork.cancelCalls.get());
            assertTrue(probe.join().isEmpty());
            var lease = full.join().orElseThrow();
            assertSame(loaded,
                    assertInstanceOf(AcquireResult.Ready.class, lease.poll()).target());
            lease.close();
        }
    }

    @Test
    void inFlightOverflowIsDisposedByTheOwnerTick() {
        try (var cache = new ModelRenderTargetCache()) {
            for (var marker = 0; marker < ModelRenderTargetCache.MAX_IN_FLIGHT; marker++) {
                var hash = wideHash(marker + 1);
                cache.getOrStart(request(hash), content(hash), key(),
                        ignored -> new CompletableFuture<>(), current(), OWNER);
            }
            var overflowHash = wideHash(ModelRenderTargetCache.MAX_IN_FLIGHT + 1);
            var overflow = cache.getOrStart(request(overflowHash), content(overflowHash),
                    key(), ignored -> new CompletableFuture<>(), current(), OWNER);

            assertInstanceOf(AcquireResult.Pending.class, overflow.poll());
            cache.tick();
            assertInstanceOf(AcquireResult.Failed.class, overflow.poll());
        }
    }

    @Test
    void cancellationRejectsAQueuedCandidateBeforeReadyCommit() throws Exception {
        var hash = hash(5);
        var content = content(hash);
        var pending = new CountingFuture<ModelRenderTargetLoader.LoadResult>();
        var loaded = target();
        try (var cache = new ModelRenderTargetCache(ignored -> {})) {
            var lease = cache.getOrStart(request(hash), content, key(), context -> pending,
                    current(), OWNER);
            var terminal = cache.terminal(lease);

            pending.complete(readyResult(loaded));
            lease.cancelPending();
            cache.tick();

            assertEquals(0, pending.cancelCalls.get());
            assertInstanceOf(AcquireResult.Failed.class, terminal.join());
        }
    }

    @Test
    void cancelledOldFlightCannotOverwriteSameKeyReplacementAba() throws Exception {
        var hash = hash(6);
        var content = content(hash);
        var oldPending = new CancelResistantFuture<ModelRenderTargetLoader.LoadResult>();
        var replacementPending = new CompletableFuture<ModelRenderTargetLoader.LoadResult>();
        var oldTarget = target();
        var replacementTarget = target();
        var rejected = new AtomicInteger();
        var loads = new AtomicInteger();
        try (var cache = new ModelRenderTargetCache(
                     ignored -> rejected.incrementAndGet())) {
            var old = cache.getOrStart(request(hash), content, key(), ignored -> {
                loads.incrementAndGet();
                return oldPending;
            }, current(), OWNER);
            old.cancelPending();
            cache.tick();
            var replacement = cache.getOrStart(request(hash), content, key(), ignored -> {
                loads.incrementAndGet();
                return replacementPending;
            }, current(), OWNER);

            oldPending.complete(readyResult(oldTarget));
            assertEquals(1, rejected.get());
            assertInstanceOf(AcquireResult.Pending.class, replacement.poll());
            replacementPending.complete(
                    readyResult(replacementTarget));
            cache.tick();

            assertEquals(2, loads.get());
            assertSame(replacementTarget,
                    assertInstanceOf(AcquireResult.Ready.class, replacement.poll()).target());
        }
    }

    @Test
    void normalAcquireAtomicallyReplacesOfflineFlight() {
        var hash = hash(7);
        var content = content(hash);
        var offlinePending = new CountingFuture<ModelRenderTargetLoader.LoadResult>();
        var normalPending = new CompletableFuture<ModelRenderTargetLoader.LoadResult>();
        try (var cache = new ModelRenderTargetCache()) {
            var offline = cache.getOrStartOffline(request(hash), content, key(),
                    context -> offlinePending, current(), OWNER);
            var normal = cache.getOrStart(request(hash), content, key(),
                    ignored -> normalPending, current(), OWNER);

            assertTrue(offline.join().isEmpty());
            assertEquals(1, offlinePending.cancelCalls.get());
            assertInstanceOf(AcquireResult.Pending.class, normal.poll());
        }
    }

    @Test
    void authorityChangePreservesTheHeldReadyValueWithoutCacheRetirement()
            throws Exception {
        var hash = hash(8);
        var content = content(hash);
        var loaded = target();
        var current = new AtomicReference<>(content.representation().identity());
        ModelRenderTargetCache.CurrentLookup lookup =
                (identity, key, request) -> identity.equals(current.get());
        try (var cache = new ModelRenderTargetCache()) {
            var lease = cache.getOrStart(request(hash), content, key(),
                    ignored -> ready(loaded), lookup, OWNER);
            cache.tick();

            current.set(new ModelFileIdentity(hash, hash(80)));

            assertFalse(lease.isCurrent(request(hash)));
            assertSame(loaded,
                    assertInstanceOf(AcquireResult.Ready.class, lease.poll()).target());
            assertEquals(1, cache.readyCount());
        }
    }

    @Test
    void invalidLoaderOutcomeFailsFlightWithoutRetainedFailureHit() {
        var hash = hash(9);
        var content = content(hash);
        var reports = new AtomicInteger();
        var loads = new AtomicInteger();
        try (var cache = new ModelRenderTargetCache(
                     ignored -> {}, ignored -> reports.incrementAndGet())) {
            var first = cache.getOrStart(request(hash), content, key(), ignored -> {
                loads.incrementAndGet();
                return null;
            }, current(), OWNER);
            cache.tick();
            assertInstanceOf(IllegalStateException.class,
                    assertInstanceOf(AcquireResult.Failed.class, first.poll()).failure().cause());

            var retry = cache.getOrStart(request(hash), content, key(), ignored -> {
                loads.incrementAndGet();
                return failed(ResourceFailure.Kind.TRANSIENT,
                        new CancellationException("retry"));
            }, current(), OWNER);
            cache.tick();
            assertInstanceOf(AcquireResult.Failed.class, retry.poll());
            assertEquals(2, loads.get());
            assertEquals(1, reports.get());
        }
    }

    @Test
    void closedHandleCannotBeObserved() throws Exception {
        var hash = hash(10);
        var loaded = target();
        try (var cache = new ModelRenderTargetCache()) {
            var lease = cache.getOrStart(request(hash), content(hash), key(),
                    ignored -> ready(loaded), current(), OWNER);
            lease.close();
            assertThrows(IllegalStateException.class, lease::poll);
        }
    }

    @Test
    void ordinaryTerminalWaitsForTheOwnerTickBeforeExposure() throws Exception {
        var loaded = target();
        try (var cache = new ModelRenderTargetCache()) {
            var lease = cache.getOrStart(request(hash(11)), content(hash(11)), key(),
                    ignored -> ready(loaded), current(), OWNER);

            assertInstanceOf(AcquireResult.Pending.class, lease.poll());
            assertEquals(0, cache.readyCount());

            cache.tick();
            assertEquals(1, cache.readyCount());
            assertSame(loaded,
                    assertInstanceOf(AcquireResult.Ready.class, lease.poll()).target());
        }
    }

    @Test
    void requiredBootstrapPublishesOnTheOwnerWithoutWaitingForTick() throws Exception {
        var loaded = target();
        var admissions = new AtomicInteger();
        var hostTasks = new ArrayDeque<Runnable>();
        try (var cache = new ModelRenderTargetCache()) {
            var lease = cache.getOrStartRequired(
                    request(hash(12)), content(hash(12)), key(), ignored -> {
                        admissions.incrementAndGet();
                        return readyResult(loaded);
                    }, current(), OWNER, hostTasks::add);

            assertEquals(1, admissions.get());
            assertInstanceOf(AcquireResult.Pending.class, lease.poll());
            assertEquals(0, cache.readyCount());
            hostTasks.remove().run();
            assertSame(loaded,
                    assertInstanceOf(AcquireResult.Ready.class, lease.poll()).target());
        }
    }

    @Test
    void ordinarySuccessCapAppliesAtTheReadyCommitForDesktopAndMobile() throws Exception {
        assertSuccessCap(4);
        assertSuccessCap(2);
    }

    @Test
    void hostPublicationFailureRejectsTheCandidateWithoutChangingReady() throws Exception {
        ImageSource source = () -> {
            throw new AssertionError("Injected decoder owns this test boundary");
        };
        var image = new NativeImage(1, 1, false);
        var prepared = new PreparedTextureSet(
                new PBRImageSources(source, null, null), "test/",
                ModelResourceFailures.none(), ignored -> image);
        prepared.prepare(() -> false);
        var attempts = new AtomicInteger();
        var cache = new ModelRenderTargetCache(
                ModelRenderTarget::close, ignored -> {}, ignored -> {
                    attempts.incrementAndGet();
                    throw new IOException("host failed");
                }, () -> 4);
        var candidate = new ModelRenderTargetLoader.ModelCandidate(
                closeableTarget(), prepared);
        try (cache) {
            var lease = cache.getOrStart(request(hash(50)), content(hash(50)), key(),
                    ignored -> CompletableFuture.completedFuture(
                            new ModelRenderTargetLoader.LoadResult.Ready(
                                    candidate)),
                    current(), OWNER);

            cache.tick();

            assertEquals(1, attempts.get());
            assertEquals(0, cache.readyCount());
            assertInstanceOf(AcquireResult.Failed.class, lease.poll());
            assertThrows(IllegalStateException.class, () -> image.getPixelRGBA(0, 0));
        }
    }

    @Test
    void hostBindingIsAdoptedBeforeTheCandidateBecomesReady() throws Exception {
        ImageSource source = () -> {
            throw new AssertionError("Injected decoder owns this test boundary");
        };
        var image = new NativeImage(1, 1, false);
        var prepared = new PreparedTextureSet(
                new PBRImageSources(source, null, null), "test/",
                ModelResourceFailures.none(), ignored -> image);
        prepared.prepare(() -> false);
        @SuppressWarnings("removal")
        var id = new ResourceLocation("ysm", "test/model");
        var textureManager = (TextureManager) UnsafeUtil.getUnsafe()
                .allocateInstance(TextureManager.class);
        HostTexturePublisher publisher = candidateTextures -> {
            candidateTextures.transfer().closeUnregistered();
            return new HostTexturePublisher.PublishedTextureBinding(
                    id, textureManager, List.of());
        };
        var cache = new ModelRenderTargetCache(
                ModelRenderTarget::close, ignored -> {}, publisher, () -> 4);
        var candidate = new ModelRenderTargetLoader.ModelCandidate(
                closeableTarget(), prepared);
        try (cache) {
            var lease = cache.getOrStart(request(hash(51)), content(hash(51)), key(),
                    ignored -> CompletableFuture.completedFuture(
                            new ModelRenderTargetLoader.LoadResult.Ready(
                                    candidate)),
                    current(), OWNER);

            assertInstanceOf(AcquireResult.Pending.class, lease.poll());
            cache.tick();
            var target = assertInstanceOf(AcquireResult.Ready.class, lease.poll()).target();
            assertEquals(id, target.textureId());
            assertThrows(IllegalStateException.class, () -> image.getPixelRGBA(0, 0));
        }
    }

    private static void assertSuccessCap(int limit) throws Exception {
        try (var cache = new ModelRenderTargetCache(
                ignored -> {}, ignored -> {},
                ignored -> { throw new AssertionError("Testing candidates bypass the host"); },
                () -> limit)) {
            for (var marker = 60; marker < 65; marker++) {
                var hash = hash(marker);
                var target = target();
                cache.getOrStart(request(hash), content(hash), key(),
                        ignored -> ready(target), current(), OWNER);
            }

            cache.tick();
            assertEquals(limit, cache.readyCount());
            cache.tick();
            assertEquals(Math.min(5, limit * 2), cache.readyCount());
        }
    }

    private static ModelRenderTargetCache.CurrentLookup current() {
        return (content, key, request) -> true;
    }

    private static CompletableFuture<ModelRenderTargetLoader.LoadResult> ready(
            ModelRenderTarget target) {
        return CompletableFuture.completedFuture(
                readyResult(target));
    }

    private static ModelRenderTargetLoader.LoadResult.Ready readyResult(
            ModelRenderTarget target) {
        return new ModelRenderTargetLoader.LoadResult.Ready(
                ModelRenderTargetLoader.ModelCandidate.testing(target));
    }

    private static CompletableFuture<ModelRenderTargetLoader.LoadResult> failed(
            ResourceFailure.Kind kind, Throwable cause) {
        return CompletableFuture.completedFuture(
                new ModelRenderTargetLoader.LoadResult.Failed(new ResourceFailure(kind, cause)));
    }

    private static ResourceRequest request(Hash256 modelId) {
        return new ResourceRequest(modelId, "player", "default", PROFILE);
    }

    private static RenderTargetKey key() {
        return new RenderTargetKey("player", "default", PROFILE);
    }

    private static Hash256 hash(int marker) {
        var bytes = new byte[Hash256.SIZE];
        bytes[0] = (byte) marker;
        return new Hash256(bytes);
    }

    private static Hash256 wideHash(int marker) {
        var bytes = new byte[Hash256.SIZE];
        ByteBuffer.wrap(bytes).putInt(marker);
        return new Hash256(bytes);
    }

    private static ModelRenderTarget target() throws InstantiationException {
        return (ModelRenderTarget) UnsafeUtil.getUnsafe().allocateInstance(ModelRenderTarget.class);
    }

    private static ModelRenderTarget closeableTarget() throws InstantiationException {
        return new ModelRenderTarget(hash(99), "test", new RenderTargetResources() {},
                (CommonAsset) UnsafeUtil.getUnsafe().allocateInstance(CommonAsset.class),
                (ModelInfoView) UnsafeUtil.getUnsafe().allocateInstance(ModelInfoView.class));
    }

    private static ModelContent content(Hash256 modelId) {
        var representation = representation(new ModelFileIdentity(
                modelId, hash(NEXT_CONTAINER_ID.getAndIncrement())));
        return new ModelContent() {
            @Override
            public Hash256 modelId() {
                return modelId;
            }

            @Override
            public ModelRepresentation representation() {
                return representation;
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

    private static ModelRepresentation representation(ModelFileIdentity identity) {
        try {
            var value = (ModelRepresentation) UnsafeUtil.getUnsafe()
                    .allocateInstance(ModelRepresentation.class);
            var field = ModelRepresentation.class.getDeclaredField("identity");
            UnsafeUtil.getUnsafe().putObject(
                    value, UnsafeUtil.getUnsafe().objectFieldOffset(field), identity);
            return value;
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    @Test
    void requiredBootstrapKeepsFastTerminalDispositionOnTheHostExecutor()
            throws Exception {
        var loaded = target();
        var terminalThread = new AtomicReference<Thread>();
        Executor immediateHost = task -> {
            var host = new Thread(task, "required-bootstrap-host");
            host.start();
            try {
                host.join();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(error);
            }
        };
        try (var cache = new ModelRenderTargetCache()) {
            var lease = cache.getOrStartRequired(
                    request(hash(13)), content(hash(13)), key(), ignored ->
                            readyResult(loaded), (identity, target, request) -> {
                        terminalThread.set(Thread.currentThread());
                        return true;
                    }, OWNER, immediateHost);

            assertEquals("required-bootstrap-host", terminalThread.get().getName());
            assertSame(loaded,
                    assertInstanceOf(AcquireResult.Ready.class, lease.poll()).target());
        }
    }

    private static class CountingFuture<T> extends CompletableFuture<T> {
        protected final AtomicInteger cancelCalls = new AtomicInteger();

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelCalls.incrementAndGet();
            return super.cancel(mayInterruptIfRunning);
        }
    }

    private static final class CancelResistantFuture<T> extends CountingFuture<T> {
        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelCalls.incrementAndGet();
            return false;
        }
    }
}
