package com.elfmcys.ysm.client.entity;

import com.elfmcys.ysm.model.resource.client.AcquireResult;
import com.elfmcys.ysm.model.service.ClientModelService;
import com.elfmcys.ysm.client.demand.ContinuousDemand;
import com.elfmcys.ysm.model.resource.client.ResourceLease;
import com.elfmcys.ysm.model.resource.client.ResourceRequest;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.YesSteveModel;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.LongSupplier;

final class EntityModelBinding implements AutoCloseable {
    @FunctionalInterface
    interface ResourceFactory {
        @Nullable
        CustomEntity.ResourceHolder create(ResourceLease lease, boolean fallback);
    }

    interface ModelAccess {
        @Nullable
        ModelContent content(Hash256 modelId);

        ResourceRequest resourceRequest(Hash256 modelId, String targetId,
                                        String textureName);

        ResourceRequest defaultResourceRequest(String targetId);

        ResourceLease getOrStart(ResourceRequest request);

        CompletableFuture<Optional<ResourceLease>> getOrStartOffline(ResourceRequest request);

        void reportActiveModelUse(ResourceRequest request);

        void reportActiveModelFailure(ModelContent content, boolean fallbackAvailable);
    }

    private final ModelAccess models;
    private final LongSupplier clock;
    private final ContinuousDemand<ModelIntent> demand = new ContinuousDemand<>();
    @Nullable
    private Hash256 modelHash;
    @Nullable
    private ResourceRequest desiredRequest;
    @Nullable
    private ResourceLease desiredLease;
    @Nullable
    private ModelContent desiredContent;
    @Nullable
    private CustomEntity.ResourceHolder resourceHolder;
    @Nullable
    private Hash256 resourceModelId;
    @Nullable
    private CompletableFuture<Optional<ResourceLease>> offlineLoad;
    private boolean offlineMiss;
    private boolean onlineStarted;
    private boolean terminalFailure;
    private boolean failureReported;
    @Nullable
    private Throwable failureCause;

    EntityModelBinding() {
        this(new ClientModelAccess(), EntityModelBinding::monotonicMillis);
    }

    EntityModelBinding(ModelAccess models) {
        this(models, EntityModelBinding::monotonicMillis);
    }

    EntityModelBinding(ModelAccess models, LongSupplier clock) {
        this.models = Objects.requireNonNull(models, "models");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    void updateModelHash(@Nullable Hash256 modelHash) {
        updateModelHash(modelHash, "player", "");
    }

    void updateModelHash(@Nullable Hash256 modelHash, String renderTargetId,
                         String textureName) {
        var next = new ModelIntent(modelHash, renderTargetId, textureName);
        if (!demand.isCurrent(next)) {
            demand.observe(next, clock.getAsLong());
            clearDesired();
        }
        this.modelHash = modelHash;
    }

    @Nullable
    Hash256 modelHash() {
        return modelHash;
    }

    @Nullable
    CustomEntity.ResourceHolder resourceHolder() {
        return resourceHolder;
    }

    void synchronize(@Nullable String renderTargetId, String textureName,
                     @Nullable String fallbackRenderTargetId, ResourceFactory factory) {
        revokeUnavailablePrimary();
        var content = modelHash == null ? null : models.content(modelHash);
        if (content == null || renderTargetId == null || renderTargetId.isBlank()) {
            clearDesired();
            releasePrimaryResource();
            installFallback(fallbackRenderTargetId, factory);
            return;
        }

        var request = models.resourceRequest(modelHash, renderTargetId, textureName);
        if (installedPrimaryIsCurrent(request)) {
            clearDesired();
            models.reportActiveModelUse(request);
            return;
        }

        if (desiredRequest != null && (!sameDesired(request, content)
                || desiredLease != null && !desiredLease.isCurrent(request))) {
            clearDesired();
        }
        if (desiredRequest == null) {
            beginDesired(request, content);
        }
        if (terminalFailure) {
            reportFailureOnce(content, fallbackRenderTargetId);
        }

        finishOffline(request, content, fallbackRenderTargetId);
        if (offlineMiss && desiredLease == null && !onlineStarted && !terminalFailure
                && demand.effectEligible(true, clock.getAsLong(),
                ContinuousDemand.SWITCH_DWELL_MILLIS)) {
            onlineStarted = true;
            try {
                desiredLease = models.getOrStart(request);
            } catch (RuntimeException failure) {
                terminalFailure = true;
                failureCause = failure;
                reportFailureOnce(content, fallbackRenderTargetId, failure);
            }
        }

        var lease = desiredLease;
        if (lease != null) {
            var result = lease.poll();
            if (result instanceof AcquireResult.Ready) {
                desiredLease = null;
                if (installReady(lease, request.modelId(), false, factory)) {
                    terminalFailure = false;
                    failureReported = false;
                    models.reportActiveModelUse(request);
                } else {
                    terminalFailure = true;
                    failureCause = new IllegalStateException(
                            "Resource factory did not create a model holder");
                    reportFailureOnce(content, fallbackRenderTargetId, failureCause);
                }
            } else if (result instanceof AcquireResult.Failed failed) {
                desiredLease = null;
                terminalFailure = true;
                failureCause = failed.failure().cause();
                reportFailureOnce(content, fallbackRenderTargetId, failureCause);
            }
        }

        if (resourceHolder == null) {
            installFallback(fallbackRenderTargetId, factory);
        }
    }

    void clearModel() {
        modelHash = null;
        demand.reset();
        clearDesired();
    }

    void installReadyForPreview(ResourceRequest request, ResourceLease lease,
                                ResourceFactory factory) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(factory, "factory");
        clearDesired();
        replaceResourceHolder(null, null);
        modelHash = request.modelId();
        demand.observe(new ModelIntent(request.modelId(), request.targetId(),
                request.requestedTexture()), clock.getAsLong());
        if (!lease.isCurrent(request)) {
            lease.close();
            throw new IllegalArgumentException("Preview resource lease is not current");
        }
        if (!installReady(lease, request.modelId(), false, factory)) {
            throw new IllegalArgumentException("Preview resource lease is not ready and current");
        }
    }

    void releaseRenderTarget() {
        clearDesired();
        replaceResourceHolder(null, null);
    }

    private void beginDesired(ResourceRequest request, ModelContent content) {
        desiredRequest = request;
        desiredContent = content;
        offlineMiss = false;
        onlineStarted = false;
        terminalFailure = false;
        failureReported = false;
        try {
            offlineLoad = models.getOrStartOffline(request);
        } catch (RuntimeException failure) {
            terminalFailure = true;
            failureCause = failure;
        }
    }

    private void finishOffline(ResourceRequest request, ModelContent content,
                               @Nullable String fallbackRenderTargetId) {
        var current = offlineLoad;
        if (current == null || !current.isDone()) {
            return;
        }
        offlineLoad = null;
        try {
            var acquired = current.join();
            var lease = acquired.orElse(null);
            if (lease == null) {
                offlineMiss = true;
            } else if (!sameDesired(request, content) || !lease.isCurrent(request)) {
                lease.cancelPending();
            } else {
                desiredLease = lease;
            }
        } catch (CancellationException failure) {
            terminalFailure = true;
            failureCause = failure;
            reportFailureOnce(content, fallbackRenderTargetId, failure);
        } catch (CompletionException failure) {
            terminalFailure = true;
            failureCause = failure;
            reportFailureOnce(content, fallbackRenderTargetId, failure);
        }
    }

    private void installFallback(@Nullable String renderTargetId,
                                 ResourceFactory factory) {
        if (resourceHolder != null || renderTargetId == null || renderTargetId.isBlank()) {
            return;
        }
        var request = models.defaultResourceRequest(renderTargetId);
        var lease = models.getOrStart(request);
        try {
            if (lease.poll() instanceof AcquireResult.Ready) {
                installReady(lease, request.modelId(), true, factory);
            } else {
                lease.cancelPending();
            }
        } catch (RuntimeException | Error error) {
            lease.close();
            throw error;
        }
    }

    private boolean installReady(ResourceLease lease, Hash256 modelId,
                                 boolean fallback, ResourceFactory factory) {
        final CustomEntity.ResourceHolder next;
        try {
            next = factory.create(lease, fallback);
        } catch (RuntimeException | Error error) {
            lease.close();
            throw error;
        }
        if (next == null) {
            lease.close();
            return false;
        }
        if (next.lease() != lease) {
            next.close();
            lease.close();
            throw new IllegalStateException("Resource factory did not transfer the supplied lease");
        }
        replaceResourceHolder(next, modelId);
        return true;
    }

    private void replaceResourceHolder(@Nullable CustomEntity.ResourceHolder next,
                                       @Nullable Hash256 nextModelId) {
        var previous = resourceHolder;
        resourceHolder = next;
        resourceModelId = nextModelId;
        if (previous != null && previous != next) {
            previous.close();
        }
    }

    private boolean installedPrimaryIsCurrent(ResourceRequest request) {
        return resourceHolder != null && !resourceHolder.fallback
                && request.modelId().equals(resourceModelId)
                && resourceHolder.lease().isCurrent(request);
    }

    private void revokeUnavailablePrimary() {
        if (resourceHolder != null && !resourceHolder.fallback
                && resourceModelId != null && models.content(resourceModelId) == null) {
            replaceResourceHolder(null, null);
        }
    }

    private boolean sameDesired(ResourceRequest request, ModelContent content) {
        return desiredContent == content && request.equals(desiredRequest);
    }

    private void reportFailureOnce(ModelContent content,
                                   @Nullable String fallbackRenderTargetId) {
        reportFailureOnce(content, fallbackRenderTargetId, failureCause);
    }

    private void reportFailureOnce(ModelContent content,
                                   @Nullable String fallbackRenderTargetId,
                                   @Nullable Throwable cause) {
        if (!failureReported) {
            models.reportActiveModelFailure(content, hasFallback(fallbackRenderTargetId));
            if (cause == null) {
                YesSteveModel.LOGGER.error(
                        "Failed to install active model modelId={} without a failure cause",
                        content.modelId());
            } else {
                YesSteveModel.LOGGER.error(
                        "Failed to install active model modelId={} fallbackKept={}",
                        content.modelId(), resourceHolder != null, cause);
            }
            failureReported = true;
        }
    }

    private void clearDesired() {
        var previous = desiredLease;
        desiredLease = null;
        if (offlineLoad != null) {
            offlineLoad.whenComplete((acquired, failure) -> {
                if (acquired != null) {
                    acquired.ifPresent(ResourceLease::cancelPending);
                }
            });
            offlineLoad.cancel(false);
            offlineLoad = null;
        }
        desiredRequest = null;
        desiredContent = null;
        offlineMiss = false;
        onlineStarted = false;
        terminalFailure = false;
        failureReported = false;
        failureCause = null;
        if (previous != null) {
            previous.cancelPending();
        }
    }

    private void releasePrimaryResource() {
        if (resourceHolder != null && !resourceHolder.fallback) {
            replaceResourceHolder(null, null);
        }
    }

    private static boolean hasFallback(@Nullable String renderTargetId) {
        return renderTargetId != null && !renderTargetId.isBlank();
    }

    @Override
    public void close() {
        modelHash = null;
        demand.reset();
        releaseRenderTarget();
    }

    private static long monotonicMillis() {
        return System.nanoTime() / 1_000_000L;
    }

    private record ModelIntent(@Nullable Hash256 modelId, String targetId,
                               String textureName) {
        private ModelIntent {
            targetId = Objects.requireNonNullElse(targetId, "");
            textureName = Objects.requireNonNullElse(textureName, "");
        }
    }

    private static final class ClientModelAccess implements ModelAccess {
        private ClientModelService service() {
            return ClientModelService.instance();
        }

        @Override
        public @Nullable ModelContent content(Hash256 modelId) {
            return service().catalog().find(modelId).map(entry -> entry.content()).orElse(null);
        }

        @Override
        public ResourceRequest resourceRequest(Hash256 modelId, String targetId,
                                               String textureName) {
            return service().resourceRequest(modelId, targetId, textureName);
        }

        @Override
        public ResourceRequest defaultResourceRequest(String targetId) {
            return service().defaultResourceRequest(targetId);
        }

        @Override
        public ResourceLease getOrStart(ResourceRequest request) {
            return service().getOrStart(request);
        }

        @Override
        public CompletableFuture<Optional<ResourceLease>> getOrStartOffline(
                ResourceRequest request) {
            return service().getOrStartOffline(request);
        }

        @Override
        public void reportActiveModelUse(ResourceRequest request) {
            service().reportActiveModelUse(request);
        }

        @Override
        public void reportActiveModelFailure(ModelContent content, boolean fallbackAvailable) {
            service().reportActiveModelFailure(content, fallbackAvailable);
        }
    }
}
