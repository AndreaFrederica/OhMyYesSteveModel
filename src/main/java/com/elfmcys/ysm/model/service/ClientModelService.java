package com.elfmcys.ysm.model.service;

import com.elfmcys.ysm.config.ModelLoadingConfig;
import com.elfmcys.ysm.model.catalog.CatalogScanProgress;

import com.elfmcys.ysm.AssetPaths;
import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.client.sound.stream.AudioStreamProvider;
import com.elfmcys.ysm.client.texture.CustomTexture;
import com.elfmcys.ysm.model.ModelRuntime;
import com.elfmcys.ysm.model.catalog.client.ClientCatalogManager;
import com.elfmcys.ysm.model.catalog.client.ClientCatalogSnapshot;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.RenderTargetIds;
import com.elfmcys.ysm.model.resource.RuntimeContentStore;
import com.elfmcys.ysm.model.resource.client.BakeProfile;
import com.elfmcys.ysm.model.resource.client.ClientModelRenderTargetManager;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.model.resource.client.ResourceLease;
import com.elfmcys.ysm.model.resource.client.ResourceRequest;
import com.elfmcys.ysm.model.resource.client.SoundSource;
import com.elfmcys.ysm.model.resource.client.asset.ClientAssetBatch;
import com.elfmcys.ysm.model.resource.client.asset.ClientAssetRepository;
import com.elfmcys.ysm.model.resource.client.audio.ClientAudioRuntime;
import com.elfmcys.ysm.model.resource.client.preview.ClientPreviewGenerator;
import com.elfmcys.ysm.model.resource.client.remote.RemoteChunkFetcher;
import com.elfmcys.ysm.model.resource.client.remote.RemoteMetadataFetcher;
import com.elfmcys.ysm.model.resource.client.remote.RemoteModelStore;
import com.elfmcys.ysm.model.resource.client.render.BakedAnimationCache;
import com.elfmcys.ysm.model.resource.client.render.BakedModelCache;
import com.elfmcys.ysm.model.resource.client.render.DefaultAnimationRuntime;
import com.elfmcys.ysm.model.resource.client.render.ModelRenderTargetLoader;
import com.elfmcys.ysm.model.resource.client.render.ModelLoadProgress;
import com.elfmcys.ysm.model.session.client.RemoteCatalogActivation;
import com.elfmcys.ysm.model.session.client.state.ActivationSnapshot;
import com.elfmcys.ysm.model.storage.AtomicSharedCache;
import com.elfmcys.ysm.model.storage.PreviewStore;
import com.elfmcys.ysm.natives.image.ImageSource;
import com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind;
import com.elfmcys.ysm.util.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

public final class ClientModelService implements AutoCloseable {
    private static volatile ClientModelService INSTANCE;

    private final ScheduledThreadPoolExecutor workers;
    private final ClientCatalogManager catalogManager;
    private final ClientAssetRepository assetRepository;
    private final ClientModelRenderTargetManager renderTargetManager;
    private final ClientAudioRuntime audioRuntime;
    private final ClientPreviewGenerator previewOperations;
    private final Closeable exportPreviewRegistration;
    private final Path gameDirectory;
    private RemoteModelStore remoteStore;
    private final AtomicBoolean closed = new AtomicBoolean();

    private ClientModelService(ScheduledThreadPoolExecutor workers,
                               ClientCatalogManager catalogManager,
                               ClientAssetRepository assetRepository,
                               ClientModelRenderTargetManager renderTargetManager,
                               ClientPreviewGenerator previewOperations,
                               Closeable exportPreviewRegistration,
                               Path gameDirectory) {
        this.workers = workers;
        this.catalogManager = catalogManager;
        this.assetRepository = assetRepository;
        this.renderTargetManager = renderTargetManager;
        this.audioRuntime = new ClientAudioRuntime(
                workers, renderTargetManager::resolveSound);
        this.previewOperations = previewOperations;
        this.exportPreviewRegistration = exportPreviewRegistration;
        this.gameDirectory = gameDirectory;
    }

    private ClientModelService(ScheduledThreadPoolExecutor workers,
                               ClientCatalogManager catalogManager,
                               ClientAssetRepository assetRepository,
                               ClientModelRenderTargetManager renderTargetManager,
                               Path gameDirectory) {
        this(workers, catalogManager, assetRepository, renderTargetManager,
                null, () -> { }, gameDirectory);
    }

    private static BootstrapCandidate createCandidate() {
        var threadCount = ModelLoadingConfig.CLIENT_WORKERS.get();
        var candidateWorkers = new ScheduledThreadPoolExecutor(threadCount, runnable -> {
            var thread = new Thread(runnable, "YSM Client Model Worker");
            thread.setDaemon(true);
            thread.setPriority(Math.max(Thread.MIN_PRIORITY,
                    Thread.NORM_PRIORITY - 1));
            return thread;
        });
        candidateWorkers.setRemoveOnCancelPolicy(true);
        ClientCatalogManager candidateCatalog = null;
        ClientModelRenderTargetManager candidateTargets = null;
        ClientPreviewGenerator candidatePreviews = null;
        Closeable candidateExportRegistration = null;
        try {
            var system = ModelRuntime.system();
            system.catalog().configureLoading(ModelLoadingConfig::catalogPolicy);
            var storage = system.storage();
            var gameCacheRoot = storage.gameCacheRoot();
            var shared = storage.cache();
            var gameDirectory = Minecraft.getInstance().gameDirectory.toPath();
            var bakedRoot = AssetPaths.bakedRoot(gameDirectory);
            var bakedShared = new AtomicSharedCache(bakedRoot);
            var builtins = system.builtins();
            candidateCatalog = new ClientCatalogManager(system.catalog());
            var defaultAnimations = new DefaultAnimationRuntime(system.builtinContract());
            var renderTargetLoader = new ModelRenderTargetLoader(
                    new BakedModelCache(bakedRoot, bakedShared),
                    new BakedAnimationCache(bakedRoot, bakedShared), candidateWorkers,
                    defaultAnimations);
            var contentStore = new RuntimeContentStore(error ->
                    YesSteveModel.LOGGER.warn(
                            "Model source instance became corrupted", error));
            candidateTargets = new ClientModelRenderTargetManager(
                    candidateCatalog, renderTargetLoader,
                    defaultAnimations, candidateWorkers,
                    ClientModelService::executeOnRenderThread, contentStore);
            candidateTargets.configurePublicationBudget(
                    ModelLoadingConfig.PUBLICATIONS_PER_TICK::get,
                    ModelLoadingConfig.PUBLICATION_MILLIS::get);
            var previewStore = new PreviewStore(gameCacheRoot, shared);
            var previews = new ClientPreviewGenerator(candidateCatalog, candidateTargets,
                    renderTargetLoader, previewStore,
                    ClientModelService::executeOnRenderThread, candidateWorkers);
            candidatePreviews = previews;
            var candidateAssets = new ClientAssetRepository(candidateCatalog, contentStore,
                    previewStore, candidateWorkers,
                    previews::resolveLocal, previews::probeRemote);
            candidateExportRegistration = ModelExportService.registerExporter(
                    previews::export);
            candidateCatalog.setListener(candidateTargets::catalogChanged);
            var required = candidateTargets.startRequired(candidateCatalog.initialize());
            return new BootstrapCandidate(new ClientModelService(
                    candidateWorkers, candidateCatalog, candidateAssets, candidateTargets,
                    candidatePreviews, candidateExportRegistration,
                    gameDirectory),
                    required);
        } catch (Throwable error) {
            var failure = unwrap(error);
            if (candidateExportRegistration != null) {
                try {
                    candidateExportRegistration.close();
                } catch (Throwable closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            if (candidatePreviews != null) {
                try {
                    candidatePreviews.close();
                } catch (Throwable closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            if (candidateTargets != null) {
                try {
                    candidateTargets.close();
                } catch (Throwable closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            if (candidateCatalog != null) {
                try {
                    candidateCatalog.close();
                } catch (Throwable closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            try {
                candidateWorkers.shutdownNow();
            } catch (Throwable closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw sneakyThrow(failure);
        }
    }

    public static synchronized ClientModelService start() {
        if (INSTANCE != null) {
            return INSTANCE;
        }
        var candidate = publishRequired(ClientModelService::createCandidate,
                ClientModelService::completeRequired,
                value -> INSTANCE = value.service);
        return candidate.service;
    }

    private static void completeRequired(BootstrapCandidate candidate) {
        var minecraft = Minecraft.getInstance();
        if (minecraft.isSameThread()) {
            minecraft.managedBlock(candidate.required::isDone);
        }
        candidate.required.join();
    }

    private static void executeOnRenderThread(Runnable task) {
        var minecraft = Minecraft.getInstance();
        if (minecraft.isSameThread()) {
            task.run();
            return;
        }
        // BlockableEventLoop.execute may run inline during startup; tell keeps GPU publication
        // on the render owner while managedBlock drains the same task queue.
        minecraft.tell(task);
    }

    static <T extends AutoCloseable> T publishRequired(
            Supplier<T> factory, Consumer<T> completeRequired,
            Consumer<T> publish) {
        T candidate = null;
        try {
            candidate = factory.get();
            completeRequired.accept(candidate);
        } catch (Throwable error) {
            var failure = unwrap(error);
            if (candidate != null) {
                try {
                    candidate.close();
                } catch (Throwable closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            throw sneakyThrow(failure);
        }
        publish.accept(candidate);
        return candidate;
    }

    public static ClientModelService instance() {
        var service = INSTANCE;
        if (service == null) {
            throw new IllegalStateException("Client model service is not running");
        }
        return service;
    }

    public static Optional<ClientModelService> current() {
        return Optional.ofNullable(INSTANCE);
    }

    public ClientCatalogSnapshot catalog() {
        return catalogManager.snapshot();
    }

    public Object beginConnection() {
        ModelRuntime.system().activateCatalog();
        return renderTargetManager.beginSession();
    }

    public RemoteCatalogActivation beginRemoteActivation(
            RemoteMetadataFetcher fetcher,
            RemoteChunkFetcher chunkFetcher,
            Object sessionOwner) throws IOException {
        renderTargetManager.bindRemoteFetcher(sessionOwner, chunkFetcher);
        var active = catalogManager.beginRemote();
        return new RemoteCatalogActivation(catalogManager::localIndex, active,
                remoteStore(), fetcher, workers);
    }

    public void publishSessionActivation(
            ActivationSnapshot activation) {
        catalogManager.publishSession(activation);
    }

    public void failRemoteCatalog() {
        catalogManager.failRemote();
    }

    public boolean contains(Hash256 hash) {
        return catalogManager.contains(hash);
    }

    public Optional<String> findRenderTarget(Hash256 hash,
                                             RenderTargetKind kind,
                                             ResourceLocation entityType) {
        return catalogManager.findRenderTarget(hash, kind, entityType);
    }

    public Optional<Hash256> resolvePath(String path) {
        return catalogManager.resolvePath(path);
    }

    public void rememberLastUsedModel(String path, Hash256 modelId) {
        ModelRuntime.system().catalog().rememberLastUsed(path, modelId);
    }

    public String displayPath(Hash256 hash) {
        return catalogManager.displayPath(hash);
    }

    public ModelRenderTarget defaultRenderTarget() {
        return renderTargetManager.defaultRenderTarget();
    }

    public BakeProfile bakeProfile() {
        return renderTargetManager.bakeProfile();
    }

    public ResourceRequest resourceRequest(Hash256 modelId, String textureName) {
        return resourceRequest(modelId, RenderTargetIds.PLAYER, textureName);
    }

    public ResourceRequest resourceRequest(Hash256 modelId, String targetId, String textureName) {
        return renderTargetManager.request(modelId, targetId, textureName);
    }

    public ResourceRequest defaultResourceRequest(String targetId) {
        return renderTargetManager.defaultRequest(targetId);
    }

    public ResourceLease getOrStart(ResourceRequest request) {
        return renderTargetManager.getOrStart(request);
    }

    public Optional<ResourceLease> findReady(ResourceRequest request) {
        return renderTargetManager.findReady(request);
    }

    public CompletableFuture<Optional<ResourceLease>> getOrStartOffline(
            ResourceRequest request) {
        return renderTargetManager.getOrStartOffline(request);
    }

    public CompletableFuture<Optional<ResourceLease>> getOrStartCached(
            ResourceRequest request) {
        return renderTargetManager.getOrStartCached(request);
    }

    public void reportActiveModelFailure(ModelContent version, boolean fallbackAvailable) {
        renderTargetManager.reportActiveFailure(version, fallbackAvailable);
    }

    public void reportActiveModelUse(ResourceRequest request) {
        renderTargetManager.reportActiveUse(request);
    }

    public ClientAssetBatch createAssetBatch() {
        return new ClientAssetBatch(assetRepository.openBatch());
    }

    public CustomTexture createTexture(ImageSource source) {
        return new CustomTexture(source, workers);
    }

    public void beginRenderFrame() {
        if (previewOperations != null) previewOperations.beginRenderFrame();
    }

    public com.elfmcys.ysm.client.renderer.GeneralMeshRenderHost sceneRenderer() {
        if(closed.get()) throw new IllegalStateException("Client model service is closed");
        return renderTargetManager.sceneRenderer();
    }

    public AudioStreamProvider createSoundPlayback(SoundSource source) {
        return audioRuntime.createPlayback(source);
    }

    public void disconnect(Object sessionOwner) {
        audioRuntime.stopAll();
        renderTargetManager.disconnect(sessionOwner);
        catalogManager.endRemote();
    }

    public void tick() {
        if (ModelLoadingConfig.CLIENT_WORKERS != null) {
            var configured = ModelLoadingConfig.CLIENT_WORKERS.get();
            if (workers.getCorePoolSize() != configured) workers.setCorePoolSize(configured);
        }
        var system = ModelRuntime.system();
        system.tickCatalog();
        system.tickServerRuntime();
        renderTargetManager.tick();
        audioRuntime.maintain();
        if (previewOperations != null) {
            previewOperations.tick();
        }
    }

    public int loadingCount() {
        return renderTargetManager.loadingCount();
    }

    public CatalogScanProgress scanProgress() {
        return ModelRuntime.system().catalog().progress();
    }

    public int activeWorkerCount() {
        return workers.getActiveCount();
    }

    public int queuedTaskCount() {
        return workers.getQueue().size();
    }

    /** Current detailed model/mesh loading stage for the in-game overlay. */
    public ModelLoadProgress meshLoadProgress() {
        return ModelRenderTargetLoader.progress();
    }

    @Override
    public void close() {
        synchronized (ClientModelService.class) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            if (INSTANCE == this) {
                INSTANCE = null;
            }
            closeResourcesUnchecked();
        }
    }

    private void closeResourcesUnchecked() {
        try {
            audioRuntime.close();
        } finally {
            try {
                exportPreviewRegistration.close();
            } finally {
                try {
                    if (previewOperations != null) {
                        previewOperations.close();
                    }
                } finally {
                    try {
                        renderTargetManager.close();
                    } finally {
                        try {
                            catalogManager.close();
                        } finally {
                            try {
                                var store = detachRemoteStore();
                                if (store != null) {
                                    store.close();
                                }
                            } finally {
                                workers.shutdown();
                                awaitTermination(workers);
                            }
                        }
                    }
                }
            }
        }
    }

    private synchronized RemoteModelStore remoteStore() throws IOException {
        if (closed.get()) {
            throw new IllegalStateException("Client model service is closed");
        }
        if (remoteStore == null) {
            remoteStore = RemoteModelStore.create(gameDirectory);
        }
        return remoteStore;
    }

    private synchronized RemoteModelStore detachRemoteStore() {
        var store = remoteStore;
        remoteStore = null;
        return store;
    }

    private static void awaitTermination(ScheduledThreadPoolExecutor workers) {
        var interrupted = false;
        while (!workers.isTerminated()) {
            try {
                if (workers.awaitTermination(Long.MAX_VALUE,
                        TimeUnit.NANOSECONDS)) {
                    break;
                }
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static Throwable unwrap(Throwable error) {
        var current = error;
        while ((current instanceof CompletionException
                || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static RuntimeException sneakyThrow(Throwable error) {
        ClientModelService.<RuntimeException>throwUnchecked(error);
        throw new AssertionError("unreachable");
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void throwUnchecked(Throwable error) throws E {
        throw (E) error;
    }

    private record BootstrapCandidate(
            ClientModelService service,
            CompletableFuture<Void> required) implements AutoCloseable {
        @Override
        public void close() {
            service.close();
        }
    }

}
