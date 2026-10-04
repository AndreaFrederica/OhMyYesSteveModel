package com.elfmcys.ysm.client.gui.button;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.client.animation.AnimationRegister;
import com.elfmcys.ysm.client.gui.CustomGuiPlayerEntity;
import com.elfmcys.ysm.model.resource.client.asset.ClientAssetBatch;
import com.elfmcys.ysm.model.resource.client.AcquireResult;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.model.catalog.client.entry.CatalogModelMetadata;
import com.elfmcys.ysm.model.catalog.client.entry.ClientCatalogEntry;
import com.elfmcys.ysm.model.service.ClientModelService;
import com.elfmcys.ysm.model.resource.client.ResourceLease;
import com.elfmcys.ysm.model.resource.client.ResourceRequest;
import com.elfmcys.ysm.client.texture.CustomTexture;
import com.elfmcys.ysm.client.texture.CustomTextureManager;
import com.elfmcys.ysm.client.texture.TextureHolder;
import com.elfmcys.ysm.natives.image.ImageSource;
import com.elfmcys.ysm.model.resource.client.asset.ModelAssetSelector;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

final class CatalogModelCardState implements AutoCloseable {
    private final ClientModelService service = ClientModelService.instance();
    private final ClientAssetBatch assets;
    private final ClientCatalogEntry entry;
    private final CatalogModelMetadata metadata;
    private final CustomGuiPlayerEntity entity;
    private final CatalogModelPreviewAnimationState previewAnimations =
            new CatalogModelPreviewAnimationState();

    private final TargetState targetState;
    private CompletableFuture<Optional<ResourceLease>> cacheProbe;
    private CompletableFuture<Optional<ResourceLease>> hoverLoad;
    @Nullable
    private CustomTexture previewTexture;
    @Nullable
    private CustomTexture backgroundTexture;
    @Nullable
    private CustomTexture foregroundTexture;
    @Nullable
    private TextureHolder preview;
    @Nullable
    private TextureHolder background;
    @Nullable
    private TextureHolder foreground;
    @Nullable
    private Throwable presentationFailure;
    @Nullable
    private Throwable renderFailure;
    private long activeHoverGeneration = -1;
    private long attemptedHoverGeneration = -1;
    private boolean closed;

    CatalogModelCardState(ClientAssetBatch assets, ClientCatalogEntry entry,
                          CatalogModelMetadata metadata, CustomGuiPlayerEntity entity) {
        this.assets = assets;
        this.entry = entry;
        this.metadata = metadata;
        this.entity = entity;
        targetState = new TargetState(
                service.resourceRequest(entry.modelHash(), metadata.defaultTexture()));
        requestPresentation();
        tryReady();
        if (!targetState.hasInterest()) {
            startCacheProbe();
        }
    }

    @Nullable
    ModelRenderTarget renderTarget() {
        pollRenderTarget();
        return targetState.renderTarget();
    }

    @Nullable
    TextureHolder preview() {
        return previewTexture != null && previewTexture.ready() ? preview : null;
    }

    @Nullable
    TextureHolder background() {
        return background;
    }

    @Nullable
    TextureHolder foreground() {
        return foreground;
    }

    @Nullable
    Throwable loadError() {
        pollRenderTarget();
        var loadError = targetState.failure();
        if (loadError == null) {
            loadError = textureFailure(previewTexture);
        }
        if (loadError == null) {
            loadError = textureFailure(backgroundTexture);
        }
        if (loadError == null) {
            loadError = textureFailure(foregroundTexture);
        }
        if (loadError == null) {
            loadError = presentationFailure;
        }
        if (loadError == null) {
            loadError = renderFailure;
        }
        var renderTarget = targetState.renderTarget();
        if (loadError == null && renderTarget != null && renderTarget.playerResources() != null) {
            var resources = renderTarget.playerResources();
            if (resources.animations().hasFailures()
                    || resources.fpArmAnimations().hasFailures()) {
                loadError = new IllegalStateException("One or more preview animations failed to load");
            }
        }
        // Presentation failures do not retire an otherwise usable 3D model.
        return loadError;
    }

    boolean renderDisabled() {
        return renderFailure != null;
    }

    void recordRenderFailure(Throwable failure) {
        if (renderFailure == null) {
            renderFailure = Objects.requireNonNull(failure, "failure");
            YesSteveModel.LOGGER.warn("Disabling model card 3D preview after renderer failure for {}",
                    metadata.path(), failure);
        }
    }

    void updatePreviewAnimations(boolean hovered, boolean focused, long now) {
        pollRenderTarget();
        if (targetState.renderTarget() != null) {
            previewAnimations.apply(entity.getPreviewInfo(), hovered, focused, now);
        }
    }

    void updateDemand(long hoverGeneration, boolean bakeEligible) {
        if (closed) {
            return;
        }
        pollRenderTarget();
        tryReady();
        if (!bakeEligible) {
            if (activeHoverGeneration != -1 && activeHoverGeneration != hoverGeneration) {
                activeHoverGeneration = -1;
                if (hoverLoad != null) {
                    hoverLoad.cancel(false);
                    hoverLoad = null;
                }
                targetState.cancelPending();
            }
            return;
        }
        activeHoverGeneration = hoverGeneration;
        if (targetState.renderTarget() != null || targetState.failure() != null
                || attemptedHoverGeneration == hoverGeneration || cacheProbe != null) {
            return;
        }
        attemptedHoverGeneration = hoverGeneration;
        startOfflineLoad(hoverGeneration);
    }

    private static @Nullable Throwable textureFailure(@Nullable CustomTexture texture) {
        return texture == null ? null : texture.failure().orElse(null);
    }

    private void requestPresentation() {
        requestGuiAssets();
        assets.preview(entry.modelHash()).whenComplete((data, error) -> Minecraft.getInstance().execute(() -> {
            if (closed) {
                return;
            }
            if (data != null) {
                previewTexture = service.createTexture(data);
                preview = CustomTextureManager.register(previewTexture, 10 * 20);
            } else if (!isCancellation(error)) {
                presentationFailure = unwrap(error);
            }
        }));
    }

    private void tryReady() {
        if (targetState.hasInterest() || targetState.failure() != null) {
            return;
        }
        targetState.accept(service.findReady(targetState.request()), null);
        pollRenderTarget();
    }

    private void startCacheProbe() {
        cacheProbe = service.getOrStartCached(targetState.request());
        var current = cacheProbe;
        current.whenComplete((acquired, error) -> Minecraft.getInstance().execute(() -> {
            if (cacheProbe == current) {
                cacheProbe = null;
            }
            if (closed) {
                cancelAcquired(acquired);
                return;
            }
            targetState.accept(acquired, error);
            pollRenderTarget();
        }));
    }

    private void startOfflineLoad(long generation) {
        hoverLoad = service.getOrStartOffline(targetState.request());
        var current = hoverLoad;
        current.whenComplete((acquired, error) -> Minecraft.getInstance().execute(() -> {
            if (hoverLoad == current) {
                hoverLoad = null;
            }
            if (closed || activeHoverGeneration != generation) {
                cancelAcquired(acquired);
                return;
            }
            targetState.accept(acquired, error);
            pollRenderTarget();
        }));
    }

    private void pollRenderTarget() {
        targetState.poll(this::applyRenderTarget);
    }

    private void applyRenderTarget(ModelRenderTarget nextRenderTarget) {
        entity.reset();
        entity.updateModelAndTexture(entry.modelHash(), metadata.defaultTexture());
        if(nextRenderTarget.playerResources()==null && nextRenderTarget.generalMeshResources()!=null) {
            previewAnimations.configure("",false,false,()->0,false);
            return;
        }
        var playerResources = Objects.requireNonNull(nextRenderTarget.playerResources(),
                "Catalog model card requires a player render target");
        var animations = playerResources.animations();
        previewAnimations.configure(nextRenderTarget.info().getSettings().previewAnimation().orElse(""),
                animations.containsKey(AnimationRegister.HOVER),
                animations.containsKey(AnimationRegister.HOVER_FADEOUT),
                () -> {
                    var fadeout = animations.get(AnimationRegister.HOVER_FADEOUT);
                    return fadeout == null ? 0 : fadeout.animationLength * 50;
                },
                animations.containsKey(AnimationRegister.FOCUS));
    }

    private void requestGuiAssets() {
        var settings = entry.displayRepresentation().view().getManifest().info().settings();
        if (settings.hasGuiBackground()) {
            requestGuiAsset(ModelAssetSelector.PresentationAsset.GUI_BACKGROUND, false);
        }
        if (settings.hasGuiForeground()) {
            requestGuiAsset(ModelAssetSelector.PresentationAsset.GUI_FOREGROUND, true);
        }
    }

    private void requestGuiAsset(ModelAssetSelector.PresentationAsset asset, boolean foregroundAsset) {
        assets.presentation(entry.modelHash(), asset, 0)
                .thenAccept(source -> Minecraft.getInstance().execute(() ->
                        applyPresentationImage(source, foregroundAsset)))
                .exceptionally(error -> {
                    if (!isCancellation(error)) {
                        YesSteveModel.LOGGER.error("Failed to load model presentation asset for {}",
                                entry.modelHash(), unwrap(error));
                    }
                    return null;
                });
    }

    private void applyPresentationImage(ImageSource source, boolean foregroundAsset) {
        if (closed) {
            return;
        }
        var texture = service.createTexture(source);
        var holder = CustomTextureManager.register(texture, 10 * 20);
        if (foregroundAsset) {
            foregroundTexture = texture;
            foreground = holder;
        } else {
            backgroundTexture = texture;
            background = holder;
        }
    }

    @Override
    public void close() {
        closed = true;
        if (cacheProbe != null) {
            cacheProbe.cancel(false);
            cacheProbe = null;
        }
        if (hoverLoad != null) {
            hoverLoad.cancel(false);
            hoverLoad = null;
        }
        entity.reset();
        previewAnimations.reset();
        targetState.close();
        previewTexture = release(previewTexture);
        backgroundTexture = release(backgroundTexture);
        foregroundTexture = release(foregroundTexture);
        preview = null;
        background = null;
        foreground = null;
    }

    private static void cancelAcquired(@Nullable Optional<ResourceLease> acquired) {
        if (acquired != null) {
            acquired.ifPresent(ResourceLease::cancelPending);
        }
    }

    private static @Nullable CustomTexture release(@Nullable CustomTexture texture) {
        if (texture != null) {
            CustomTextureManager.release(texture);
        }
        return null;
    }

    static final class TargetState implements AutoCloseable {
        private final ResourceRequest request;
        @Nullable
        private ResourceLease lease;
        @Nullable
        private ModelRenderTarget renderTarget;
        @Nullable
        private Throwable failure;
        private boolean closed;

        TargetState(ResourceRequest request) {
            this.request = Objects.requireNonNull(request, "request");
        }

        ResourceRequest request() {
            return request;
        }

        void accept(@Nullable Optional<ResourceLease> acquired, @Nullable Throwable error) {
            var next = acquired == null ? null : acquired.orElse(null);
            if (closed) {
                cancelPending(next);
                return;
            }
            if (error != null) {
                cancelPending(next);
                if (!isCancellation(error)) {
                    fail(unwrap(error));
                }
                return;
            }
            if (next == null) {
                return;
            }
            final boolean current;
            try {
                current = next.isCurrent(request);
            } catch (RuntimeException failure) {
                next.cancelPending();
                fail(failure);
                return;
            } catch (Error fatal) {
                next.cancelPending();
                throw fatal;
            }
            if (failure != null || lease != null || !current) {
                next.cancelPending();
                return;
            }
            lease = next;
        }

        @Nullable ModelRenderTarget poll(Consumer<ModelRenderTarget> ready) {
            if (closed || failure != null || lease == null) {
                return renderTarget;
            }
            final boolean current;
            try {
                current = lease.isCurrent(request);
            } catch (RuntimeException failure) {
                fail(failure);
                return null;
            }
            if (!current) {
                releaseLease();
                renderTarget = null;
                return null;
            }
            if (renderTarget != null) {
                return renderTarget;
            }
            var result = lease.poll();
            if (result instanceof AcquireResult.Failed failed) {
                failure = failed.failure().cause();
                releaseLease();
            } else if (result instanceof AcquireResult.Ready loaded) {
                renderTarget = loaded.target();
                ready.accept(renderTarget);
            }
            return renderTarget;
        }

        @Nullable ModelRenderTarget renderTarget() {
            return renderTarget;
        }

        @Nullable Throwable failure() {
            return failure;
        }

        boolean hasInterest() {
            return lease != null || renderTarget != null;
        }

        void cancelPending() {
            if (lease != null && lease.poll() instanceof AcquireResult.Pending) {
                releaseLease();
            }
        }

        void fail(Throwable error) {
            if (failure == null) {
                failure = Objects.requireNonNull(error, "error");
            }
            releaseLease();
            renderTarget = null;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            releaseLease();
            renderTarget = null;
        }

        private void releaseLease() {
            var current = lease;
            lease = null;
            cancelPending(current);
        }

        private static void cancelPending(@Nullable ResourceLease lease) {
            if (lease != null) {
                lease.cancelPending();
            }
        }
    }

    private static Throwable unwrap(@Nullable Throwable error) {
        if (error == null) {
            return new IllegalStateException("Unknown model load failure");
        }
        while ((error instanceof CompletionException || error instanceof ExecutionException)
                && error.getCause() != null) {
            error = error.getCause();
        }
        return Objects.requireNonNull(error);
    }

    private static boolean isCancellation(@Nullable Throwable error) {
        return error != null && unwrap(error) instanceof CancellationException;
    }
}
