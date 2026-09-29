package com.elfmcys.ysm.model.resource.client.asset;

import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.format.container.AssetContainerView;
import com.elfmcys.ysm.format.schema.file.AssetFileConstant;
import com.elfmcys.ysm.format.schema.file.ChunkDataSource;
import com.elfmcys.ysm.format.schema.file.FileImageSource;
import com.elfmcys.ysm.format.schema.model.ModelFileConstant;
import com.elfmcys.ysm.model.catalog.client.ClientCatalogManager;
import com.elfmcys.ysm.model.catalog.client.ClientCatalogSnapshot;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.catalog.source.ModelCatalogSources;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.domain.ModelPackDescriptor;
import com.elfmcys.ysm.model.domain.ModelRepresentation;
import com.elfmcys.ysm.model.resource.RuntimeContentStore;
import com.elfmcys.ysm.model.resource.client.remote.RemoteModelContent;
import com.elfmcys.ysm.model.resource.client.remote.RemotePresentationFetcher;
import com.elfmcys.ysm.model.storage.ModelHashing;
import com.elfmcys.ysm.model.storage.PreviewStore;
import com.elfmcys.ysm.natives.image.ImageSource;
import com.elfmcys.ysm.network.forge.ClientSessionRuntime;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/** Catalog-bound image access for local and active remote sessions. */
public final class ClientAssetRepository {
    private final ClientCatalogManager catalogs;
    private final RemotePresentationFetcher presentations;
    private final Function<ModelContent, ModelContent> exactContent;
    private final PreviewStore previewStore;
    private final Executor workers;
    private final Function<ModelContent, CompletableFuture<ImageSource>> localPreview;
    private final Function<ModelContent,
            CompletableFuture<Optional<ImageSource>>> remotePreview;

    public ClientAssetRepository(ClientCatalogManager catalogs) {
        this(catalogs, ClientSessionRuntime::fetchPresentation,
                Function.identity(), null, Runnable::run,
                ClientAssetRepository::missingLocalPreview,
                ClientAssetRepository::missingRemotePreview);
    }

    public ClientAssetRepository(ClientCatalogManager catalogs,
                                 RuntimeContentStore contentStore) {
        this(catalogs, ClientSessionRuntime::fetchPresentation,
                Objects.requireNonNull(contentStore, "contentStore")::exact,
                null, Runnable::run, ClientAssetRepository::missingLocalPreview,
                ClientAssetRepository::missingRemotePreview);
    }

    public ClientAssetRepository(ClientCatalogManager catalogs,
                                 RuntimeContentStore contentStore,
                                 PreviewStore previewStore, Executor workers) {
        this(catalogs, ClientSessionRuntime::fetchPresentation,
                Objects.requireNonNull(contentStore, "contentStore")::exact,
                Objects.requireNonNull(previewStore, "previewStore"),
                Objects.requireNonNull(workers, "workers"),
                ClientAssetRepository::missingLocalPreview,
                ClientAssetRepository::missingRemotePreview);
    }

    public ClientAssetRepository(
            ClientCatalogManager catalogs, RuntimeContentStore contentStore,
            PreviewStore previewStore, Executor workers,
            Function<ModelContent, CompletableFuture<ImageSource>> localPreview,
            Function<ModelContent,
                    CompletableFuture<Optional<ImageSource>>> remotePreview) {
        this(catalogs, ClientSessionRuntime::fetchPresentation,
                Objects.requireNonNull(contentStore, "contentStore")::exact,
                Objects.requireNonNull(previewStore, "previewStore"),
                Objects.requireNonNull(workers, "workers"),
                Objects.requireNonNull(localPreview, "localPreview"),
                Objects.requireNonNull(remotePreview, "remotePreview"));
    }

    ClientAssetRepository(ClientCatalogManager catalogs,
                           RemotePresentationFetcher presentations) {
        this(catalogs, presentations, Function.identity(),
                null, Runnable::run, ClientAssetRepository::missingLocalPreview,
                ClientAssetRepository::missingRemotePreview);
    }

    ClientAssetRepository(
            ClientCatalogManager catalogs,
            RemotePresentationFetcher presentations,
            Function<ModelContent, ModelContent> exactContent) {
        this(catalogs, presentations, exactContent, null, Runnable::run,
                ClientAssetRepository::missingLocalPreview,
                ClientAssetRepository::missingRemotePreview);
    }

    ClientAssetRepository(
            ClientCatalogManager catalogs,
            RemotePresentationFetcher presentations,
            Function<ModelContent, ModelContent> exactContent,
            PreviewStore previewStore, Executor workers) {
        this(catalogs, presentations, exactContent, previewStore, workers,
                ClientAssetRepository::missingLocalPreview,
                ClientAssetRepository::missingRemotePreview);
    }

    ClientAssetRepository(
            ClientCatalogManager catalogs,
            RemotePresentationFetcher presentations,
            Function<ModelContent, ModelContent> exactContent,
            PreviewStore previewStore, Executor workers,
            Function<ModelContent, CompletableFuture<ImageSource>> localPreview,
            Function<ModelContent,
                    CompletableFuture<Optional<ImageSource>>> remotePreview) {
        this.catalogs = Objects.requireNonNull(catalogs, "catalogs");
        this.presentations = Objects.requireNonNull(presentations, "presentations");
        this.exactContent = Objects.requireNonNull(exactContent, "exactContent");
        this.previewStore = previewStore;
        this.workers = Objects.requireNonNull(workers, "workers");
        this.localPreview = Objects.requireNonNull(localPreview, "localPreview");
        this.remotePreview = Objects.requireNonNull(remotePreview, "remotePreview");
    }

    public Batch openBatch() {
        return new Batch(catalogs.snapshot());
    }

    private static CompletableFuture<ImageSource> missingLocalPreview(
            ModelContent ignored) {
        return CompletableFuture.failedFuture(
                new IOException("Client preview generator is unavailable"));
    }

    private static CompletableFuture<Optional<ImageSource>> missingRemotePreview(
            ModelContent ignored) {
        return CompletableFuture.completedFuture(Optional.empty());
    }

    private static CompletableFuture<ImageSource> contentPresentation(
            BooleanSupplier cancelled, ModelContent content, ModelAssetSelector selector) {
        try {
            return CompletableFuture.completedFuture(
                    imageSource(cancelled, content.representation(), content.chunks(), selector));
        } catch (IOException | RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private static ImageSource imageSource(BooleanSupplier cancelled,
                                           ModelRepresentation representation,
                                           ChunkDataSource chunks,
                                           ModelAssetSelector selector) throws IOException {
        var view = representation.view();
        if (selector instanceof ModelAssetSelector.ModelPreview) {
            var source = view.thumbnailSource(cancelled, chunks);
            if (source == null) {
                throw new IOException("Model contains no preview image");
            }
            return source;
        }
        if (!(selector instanceof ModelAssetSelector.ModelPresentation presentation)) {
            throw new IOException("Selector does not identify a model image");
        }
        var info = view.getManifest().info();
        return switch (presentation.asset()) {
            case MODEL_ICON -> {
                var source = view.iconSource(cancelled, chunks);
                if (source == null) {
                    throw new IOException("Model contains no icon");
                }
                yield source;
            }
            case AUTHOR_AVATAR -> {
                var metadata = info.metadataUnsafe();
                if (metadata == null || metadata.authors().isEmpty()
                        || presentation.index() >= metadata.authors().size()
                        || !metadata.authors().get(presentation.index()).hasAvatar()) {
                    throw new IOException("Model contains no requested author avatar");
                }
                yield view.getFileView().imageBlobSource(cancelled, chunks,
                        metadata.authors().get(presentation.index()).avatarUnsafe());
            }
            case GUI_FOREGROUND -> {
                var settings = info.settings();
                if (!settings.hasGuiForeground()) {
                    throw new IOException("Model contains no GUI foreground");
                }
                yield view.getFileView().imageBlobSource(
                        cancelled, chunks, settings.guiForegroundUnsafe());
            }
            case GUI_BACKGROUND -> {
                var settings = info.settings();
                if (!settings.hasGuiBackground()) {
                    throw new IOException("Model contains no GUI background");
                }
                yield view.getFileView().imageBlobSource(
                        cancelled, chunks, settings.guiBackgroundUnsafe());
            }
        };
    }

    private static CompletableFuture<ImageSource> localPackCover(ModelPackDescriptor pack) {
        try {
            var root = ModelCatalogSources.sources().stream()
                    .filter(value -> value.rootKind() == pack.rootKind())
                    .findFirst().orElseThrow(() -> new IOException("Pack root is unavailable"));
            var hierarchy = pack.hierarchy().endsWith("/")
                    ? pack.hierarchy().substring(0, pack.hierarchy().length() - 1)
                    : pack.hierarchy();
            var rootPath = root.path().toAbsolutePath().normalize();
            var file = rootPath.resolve(hierarchy).resolve("ysm-pack.png").normalize();
            if (!file.startsWith(rootPath)) {
                throw new IOException("Pack cover is unavailable");
            }
            return CompletableFuture.completedFuture(new FileImageSource(
                    file, pack.coverSize(), pack.coverHash(), pack.coverFormat()));
        } catch (IOException | RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    // rootKind is endpoint-local provenance and may differ for identical published content.
    static boolean samePackCoverContent(ModelPackDescriptor candidate,
                                        ModelPackDescriptor requested) {
        return candidate.hierarchy().equals(requested.hierarchy())
                && Objects.equals(candidate.coverHash(), requested.coverHash())
                && candidate.coverSize() == requested.coverSize()
                && candidate.coverFormat().equalsIgnoreCase(requested.coverFormat());
    }

    public final class Batch implements AutoCloseable {
        private final ClientCatalogSnapshot snapshot;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final ArrayList<PendingAsset> assets = new ArrayList<>();
        private CompletableFuture<Void> transfer;
        private boolean submitted;

        private Batch(ClientCatalogSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        public synchronized CompletableFuture<ImageSource> preview(Hash256 modelId) {
            checkRegistrationOpen();
            var entry = snapshot.find(modelId).orElse(null);
            if (entry == null) {
                return register(() -> CompletableFuture.failedFuture(
                        new IllegalArgumentException("Unknown model id: " + modelId)), null, null);
            }
            return registerModel(entry.content(), ModelAssetSelector.preview(), true);
        }

        public synchronized CompletableFuture<ImageSource> packCover(ModelPackDescriptor pack) {
            checkRegistrationOpen();
            if (pack.coverHash() == null) return registerEager(() ->
                    CompletableFuture.failedFuture(
                    new IOException("Model pack contains no cover")), null, null);
            var local = catalogs.localState().packs().stream()
                    .filter(value -> samePackCoverContent(value, pack))
                    .findFirst().orElse(null);
            if (local != null) return registerEager(() -> localPackCover(local), null, null);
            var slot = assets.size();
            var member = new RemotePresentationFetcher.PackCover(slot, pack);
            return register(() -> CompletableFuture.failedFuture(
                    new IOException("Remote pack cover produced no outcome")), member, null);
        }

        public synchronized CompletableFuture<ImageSource> presentation(
                Hash256 modelId, ModelAssetSelector.PresentationAsset asset, int index) {
            checkRegistrationOpen();
            var entry = snapshot.find(modelId).orElse(null);
            if (entry == null) return register(() -> CompletableFuture.failedFuture(
                    new IllegalArgumentException("Unknown model id: " + modelId)), null, null);
            return registerModel(entry.content(), ModelAssetSelector.presentation(asset, index),
                    false);
        }

        public synchronized void submit() {
            checkRegistrationOpen();
            submitted = true;
            var preparations = assets.stream().map(value -> value.preparation)
                    .filter(Objects::nonNull).toArray(CompletableFuture[]::new);
            if (preparations.length != 0) {
                CompletableFuture.allOf(preparations)
                        .whenComplete((ignored, failure) -> beginTransfer());
                return;
            }
            beginTransfer();
        }

        private synchronized void beginTransfer() {
            if (closed.get()) return;
            var remote = assets.stream().map(value -> value.member)
                    .filter(Objects::nonNull)
                    .filter(member -> {
                        var asset = assets.get(member.slot());
                        return !asset.started && !asset.result.isDone();
                    }).toList();
            if (remote.isEmpty()) {
                publish();
                return;
            }
            var byMember = new IdentityHashMap<RemotePresentationFetcher.Member, PendingAsset>();
            remote.forEach(member -> byMember.put(member, assets.get(member.slot())));
            try {
                transfer = Objects.requireNonNull(presentations.fetch(remote, (member, outcome) -> {
                    var asset = byMember.get(member);
                    if (asset == null) throw new IOException(
                            "Presentation transfer returned an unknown page member");
                    if (outcome instanceof RemotePresentationFetcher.Unavailable) {
                        asset.prepared = new Resolved(null,
                                new IOException("Remote presentation resource is unavailable"));
                        return;
                    }
                    var bytes = ((RemotePresentationFetcher.Data) outcome).bytes();
                    if (asset.previewPayload != null) {
                        asset.previewPayload.accept(bytes);
                    } else if (asset.remoteContent != null) {
                        asset.remoteContent.publish(asset.chunk, bytes);
                    } else {
                        asset.prepared = new Resolved(remotePackCover(
                                ((RemotePresentationFetcher.PackCover) member).pack(), bytes), null);
                    }
                }), "Presentation fetch returned no terminal owner");
            } catch (RuntimeException failure) {
                failAll(failure);
                return;
            }
            transfer.whenComplete((ignored, failure) -> {
                if (failure != null) failAll(failure);
                else publish();
            });
        }

        public synchronized boolean hasRemoteRequests() {
            checkRegistrationOpen();
            return assets.stream().anyMatch(asset -> asset.member != null
                    && !asset.started && !asset.result.isDone());
        }

        private void checkRegistrationOpen() {
            if (submitted || closed.get()) {
                throw new IllegalStateException("Asset batch is already submitted or cancelled");
            }
        }

        private boolean cancelled() {
            return closed.get();
        }

        private CompletableFuture<ImageSource> registerModel(
                ModelContent content, ModelAssetSelector selector, boolean preview) {
            var readable = exactContent.apply(content);
            if (preview) {
                var identity = content.representation().identity();
                if (content instanceof RemoteModelContent) {
                    return registerRemotePreview(identity, readable);
                }
                return registerEager(() -> resolveLocalPreview(readable), null, null);
            }
            if (!(content instanceof RemoteModelContent remote)) {
                return registerEager(() -> contentPresentation(
                        this::cancelled, readable, selector), null, null);
            }
            try {
                var chunk = presentationChunk(content, selector);
                if (chunk == null) return registerEager(() -> CompletableFuture.failedFuture(
                        new IOException("Model contains no requested presentation")), null, null);
                try (var ignored = content.chunks().readStoredVerified(chunk, BufferType.ARRAY)) {
                    return registerEager(() -> contentPresentation(
                            this::cancelled, content, selector), null, null);
                } catch (IOException missing) {
                    var slot = assets.size();
                    RemotePresentationFetcher.Member member = new RemotePresentationFetcher.Icon(
                            slot, remote.representation().identity(), chunk);
                    return register(() -> contentPresentation(
                            this::cancelled, content, selector),
                            member, new RemoteBacking(remote, chunk));
                }
            } catch (IOException | RuntimeException failure) {
                return registerEager(() -> CompletableFuture.failedFuture(failure), null, null);
            }
        }

        private CompletableFuture<ImageSource> resolveLocalPreview(ModelContent content) {
            try {
                return Objects.requireNonNull(localPreview.apply(content),
                        "Local preview resolver returned no operation");
            } catch (RuntimeException failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }

        private CompletableFuture<Optional<ImageSource>> probeRemotePreview(
                ModelContent content) {
            try {
                return Objects.requireNonNull(remotePreview.apply(content),
                        "Remote preview probe returned no operation");
            } catch (RuntimeException failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }

        private CompletableFuture<ImageSource> registerRemotePreview(
                ModelFileIdentity identity,
                ModelContent readable) {
            var payload = new PreviewPayload(identity);
            var member = new RemotePresentationFetcher.Preview(assets.size(), identity);
            var pending = new PendingAsset(() -> decodeRemotePreview(payload), member,
                    null, null, payload);
            assets.add(pending);
            pending.preparation = track(pending, probeRemotePreview(readable))
                    .whenComplete((source, failure) -> {
                        synchronized (Batch.this) {
                            if (closed.get()) return;
                            if (failure != null) {
                                pending.started = true;
                                pending.result.completeExceptionally(failure);
                            } else if (source.isPresent()) {
                                pending.started = true;
                                pending.result.complete(source.orElseThrow());
                            }
                        }
                    });
            return pending.result;
        }

        private CompletableFuture<ImageSource> decodeRemotePreview(PreviewPayload payload) {
            return CompletableFuture.supplyAsync(() -> {
                try (var bytes = payload.take()) {
                    if (bytes == null) {
                        throw new IOException("Remote preview produced no outcome");
                    }
                    return previewStore == null
                            ? PreviewStore.decode(bytes)
                            : previewStore.accept(payload.identity.containerId(), bytes);
                } catch (IOException failure) {
                    throw new CompletionException(failure);
                }
            }, workers);
        }

        private CompletableFuture<ImageSource> registerEager(
                Supplier<CompletableFuture<ImageSource>> loader,
                RemotePresentationFetcher.Member member, RemoteBacking backing) {
            var result = register(loader, member, backing);
            var pending = assets.get(assets.size() - 1);
            pending.started = true;
            try {
                track(pending, loader.get()).whenComplete((source, failure) -> {
                    if (closed.get()) {
                        return;
                    }
                    if (failure == null) {
                        pending.result.complete(source);
                    } else {
                        pending.result.completeExceptionally(failure);
                    }
                });
            } catch (RuntimeException failure) {
                pending.result.completeExceptionally(failure);
            }
            return result;
        }

        private CompletableFuture<ImageSource> register(
                Supplier<CompletableFuture<ImageSource>> loader,
                RemotePresentationFetcher.Member member, RemoteBacking backing) {
            var pending = new PendingAsset(loader, member,
                    backing == null ? null : backing.content,
                    backing == null ? null : backing.chunk, null);
            assets.add(pending);
            return pending.result;
        }

        private void publish() {
            final List<PendingAsset> current;
            synchronized (this) {
                if (closed.get()) return;
                current = List.copyOf(assets);
            }
            var pending = current.stream().filter(asset -> !asset.started).toList();
            var resolutions = pending.stream().map(asset -> {
                asset.started = true;
                if (asset.prepared != null) {
                    return CompletableFuture.completedFuture(asset.prepared);
                }
                try {
                    return track(asset, asset.loader.get()).handle(Resolved::new);
                } catch (RuntimeException failure) {
                    return CompletableFuture.completedFuture(new Resolved(null, failure));
                }
            }).toList();
            CompletableFuture.allOf(resolutions.toArray(CompletableFuture[]::new))
                    .whenComplete((ignored, failure) -> {
                        synchronized (Batch.this) {
                            if (closed.get()) return;
                            for (var index = 0; index < pending.size(); index++) {
                                var resolved = resolutions.get(index).join();
                                if (resolved.failure == null) {
                                    pending.get(index).result.complete(resolved.source);
                                } else {
                                    pending.get(index).result.completeExceptionally(resolved.failure);
                                }
                            }
                        }
                    });
        }

        private synchronized void failAll(Throwable failure) {
            if (closed.get()) return;
            assets.forEach(asset -> asset.result.completeExceptionally(failure));
        }

        private synchronized <T> CompletableFuture<T> track(PendingAsset asset, CompletableFuture<T> operation) {
            asset.operation = Objects.requireNonNull(operation, "Asset loader returned no operation");
            if (closed.get() || asset.result.isCancelled()) operation.cancel(false);
            return operation;
        }

        @Override
        public synchronized void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            if (transfer != null) transfer.cancel(false);
            assets.forEach(asset -> {
                if (asset.operation != null) asset.operation.cancel(false);
                if (asset.preparation != null) asset.preparation.cancel(false);
            });
            assets.stream().map(asset -> asset.previewPayload)
                    .filter(Objects::nonNull).forEach(PreviewPayload::close);
            assets.forEach(asset -> asset.result.cancel(false));
            assets.clear();
        }

        private final class PendingAsset {
            private final Supplier<CompletableFuture<ImageSource>> loader;
            private final RemotePresentationFetcher.Member member;
            private final RemoteModelContent remoteContent;
            private final AssetContainerView.ChunkInfo chunk;
            private final PreviewPayload previewPayload;
            private final CompletableFuture<ImageSource> result = new CompletableFuture<>();
            private CompletableFuture<?> preparation;
            private CompletableFuture<?> operation;
            private volatile Resolved prepared;
            private volatile boolean started;

            private PendingAsset(
                    Supplier<CompletableFuture<ImageSource>> loader,
                    RemotePresentationFetcher.Member member, RemoteModelContent remoteContent,
                    AssetContainerView.ChunkInfo chunk,
                    PreviewPayload previewPayload) {
                this.loader = loader;
                this.member = member;
                this.remoteContent = remoteContent;
                this.chunk = chunk;
                this.previewPayload = previewPayload;
            }
        }
    }

    private static final class PreviewPayload {
        private final ModelFileIdentity identity;
        private UniBuffer bytes;

        private PreviewPayload(
                ModelFileIdentity identity) {
            this.identity = identity;
        }

        private synchronized void accept(UniBuffer source) throws IOException {
            if (bytes != null) {
                throw new IOException("Remote preview produced more than one payload");
            }
            bytes = source.acquire();
        }

        private synchronized UniBuffer take() {
            var result = bytes;
            bytes = null;
            return result;
        }

        private synchronized void close() {
            if (bytes != null) {
                bytes.close();
                bytes = null;
            }
        }
    }

    private static AssetContainerView.ChunkInfo
    presentationChunk(ModelContent content, ModelAssetSelector selector) throws IOException {
        var view = content.representation().view();
        if (selector instanceof ModelAssetSelector.ModelPreview) {
            return view.getFileView().getAssetView().getChunkInfo(
                    ModelFileConstant.THUMB_BUTTON_CHUNK_NAME);
        }
        var presentation = (ModelAssetSelector.ModelPresentation) selector;
        if (presentation.asset() == ModelAssetSelector.PresentationAsset.MODEL_ICON) {
            return view.getFileView().getAssetView().getChunkInfo(
                    ModelFileConstant.THUMB_ICON_CHUNK_NAME);
        }
        var info = view.getManifest().info();
        com.elfmcys.ysm.proto.mixel.common.Image image;
        if (presentation.asset() == ModelAssetSelector.PresentationAsset.AUTHOR_AVATAR) {
            var metadata = info.metadataUnsafe();
            if (metadata == null || metadata.authors().isEmpty()
                    || presentation.index() >= metadata.authors().size()
                    || !metadata.authors().get(presentation.index()).hasAvatar()) {
                return null;
            }
            image = metadata.authors().get(presentation.index()).avatarUnsafe();
        } else if (presentation.asset() == ModelAssetSelector.PresentationAsset.GUI_FOREGROUND) {
            var settings = info.settings();
            if (!settings.hasGuiForeground()) return null;
            image = settings.guiForegroundUnsafe();
        } else {
            var settings = info.settings();
            if (!settings.hasGuiBackground()) return null;
            image = settings.guiBackgroundUnsafe();
        }
        return view.getFileView().getAssetView().getChunkInfo(
                AssetFileConstant.BLOB_CHUNK_PREFIX + image.blobId());
    }

    private static ImageSource remotePackCover(
            ModelPackDescriptor pack, UniBuffer bytes) throws IOException {
        if (bytes.size() != pack.coverSize()
                || !ModelHashing.blake3(bytes).equals(pack.coverHash())) {
            throw new IOException("Remote pack cover content mismatch");
        }
        var stored = bytes.acquire();
        try (var image = com.elfmcys.ysm.natives.image.Image.probe(stored)) {
            if (!image.format().name().equalsIgnoreCase(pack.coverFormat())) {
                throw new IOException("Remote pack cover format mismatch");
            }
        } catch (IOException | RuntimeException | Error failure) {
            stored.close();
            throw failure;
        }
        return () -> com.elfmcys.ysm.natives.image.Image.probe(stored);
    }

    private record RemoteBacking(
            RemoteModelContent content,
            AssetContainerView.ChunkInfo chunk) {
    }

    private record Resolved(ImageSource source, Throwable failure) {
    }
}
