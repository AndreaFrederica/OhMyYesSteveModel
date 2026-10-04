package com.elfmcys.ysm.model.resource.client.remote;

import com.elfmcys.ysm.AssetPaths;
import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.container.AssetContainerConstant;
import com.elfmcys.ysm.format.container.AssetContainerView;
import com.elfmcys.ysm.format.container.ChunkDecoding;
import com.elfmcys.ysm.format.schema.file.AssetFileConstant;
import com.elfmcys.ysm.format.schema.file.ChunkDataSource;
import com.elfmcys.ysm.format.schema.model.ModelFileConstant;
import com.elfmcys.ysm.format.schema.model.ModelFileView;
import com.elfmcys.ysm.format.schema.model.ModelManifestLookup;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.domain.ModelRepresentation;
import com.elfmcys.ysm.model.storage.AtomicSharedCache;
import com.elfmcys.ysm.natives.Zstd;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/** Deterministic, index-free cache for verified remote representations. */
public final class RemoteModelStore implements AutoCloseable {
    private static final String METADATA_FILE = "metadata.bin";
    private static final String METADATA_DIRECTORY = "metadata";
    private static final String CHUNK_SUFFIX = ".bin";
    private static final String ZSTD_CHUNK_SUFFIX = "-zst.bin";
    private static final String ZSTD_ENCODING = "zstd";

    private final Path root;
    private final AtomicMove atomicMove;
    private final CacheReadObserver afterCacheRead;
    private boolean closed;

    public RemoteModelStore(Path root) throws IOException {
        this(root, AtomicSharedCache::moveCommitted, ignored -> { });
    }

    RemoteModelStore(Path root, AtomicMove atomicMove,
                     CacheReadObserver afterCacheRead) throws IOException {
        var configuredRoot = root.toAbsolutePath().normalize();
        Files.createDirectories(configuredRoot);
        this.root = configuredRoot.toRealPath();
        this.atomicMove = Objects.requireNonNull(atomicMove, "atomicMove");
        this.afterCacheRead = Objects.requireNonNull(afterCacheRead, "afterCacheRead");
        if (!Files.isWritable(this.root)) {
            throw AssetLoadException.access(
                    "Remote model store is not writable: " + this.root);
        }
    }

    public static RemoteModelStore create(Path gameDirectory) throws IOException {
        Objects.requireNonNull(gameDirectory, "gameDirectory");
        return new RemoteModelStore(AssetPaths.remoteRoot(gameDirectory));
    }

    public Path root() {
        return root;
    }

    public synchronized void publishChunk(Hash256 modelId, AssetContainerView.ChunkInfo chunk,
                                          byte[] storedBytes) throws IOException {
        publishChunk(modelId, chunk, ArrayBuffer.borrow(storedBytes));
    }

    public synchronized void publishChunk(Hash256 modelId, AssetContainerView.ChunkInfo chunk,
                                          UniBuffer storedBytes) throws IOException {
        requireOpen();
        Objects.requireNonNull(modelId, "modelId");
        Objects.requireNonNull(chunk, "chunk");
        if (chunk.hash() == null || chunk.hash().length != Hash256.SIZE) {
            throw AssetLoadException.content("Remote chunk has no content hash");
        }
        ChunkDecoding.validateStored(storedBytes, chunk);
        try {
            publishAtomic(
                    chunkPath(modelId, new Hash256(chunk.hash()), chunk.encoding()), storedBytes,
                    temporary -> {
                        try (var persisted = read(temporary, BufferType.NATIVE)) {
                            ChunkDecoding.validateStored(persisted, chunk);
                        }
                        return true;
                    });
        } catch (IOException error) {
            throw AssetLoadException.access("Failed to publish remote chunk", error);
        }
    }

    /** Single-file metadata lookup. This method never calls the network fetcher. */
    public synchronized Optional<RemoteModelContent> probeMetadata(
            ModelFileIdentity identity) throws IOException {
        requireOpen();
        Objects.requireNonNull(identity, "identity");
        var path = metadataPath(identity);
        try {
            try (var metadataPrefix = read(path, BufferType.NATIVE)) {
                return Optional.of(validateMetadata(identity, metadataPrefix));
            }
        } catch (NoSuchFileException missing) {
            return Optional.empty();
        } catch (AssetLoadException invalid) {
            if (invalid.reason() == AssetLoadException.Reason.ACCESS) {
                throw invalid;
            }
            return Optional.empty();
        }
    }

    /** Validation completes before the atomic replacement that publishes the new prefix. */
    public synchronized RemoteModelContent commitMetadataPrefix(
            ModelFileIdentity identity, UniBuffer metadataPrefix) throws IOException {
        requireOpen();
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(metadataPrefix, "metadataPrefix");
        if (metadataPrefix.size() <= 0
                || metadataPrefix.size() > AssetContainerConstant.MAX_FILE_SIZE) {
            throw AssetLoadException.content("Remote metadata prefix size is invalid");
        }
        var content = validateMetadata(identity, metadataPrefix);
        try {
            publishAtomic(metadataPath(identity), metadataPrefix, temporary -> {
                try (var persisted = read(temporary, BufferType.NATIVE)) {
                    var verified = validateMetadata(identity, persisted);
                    verified.representation().close();
                }
                return true;
            });
        } catch (IOException | SecurityException access) {
            content.representation().close();
            throw AssetLoadException.access("Failed to commit remote metadata prefix", access);
        }
        return content;
    }

    public RemoteModelContent openContent(ModelFileIdentity identity)
            throws IOException {
        return probeMetadata(identity).orElseThrow(() ->
                AssetLoadException.access("Remote metadata is not cached"));
    }

    <T> CompletableFuture<T> loadRenderTarget(
            RemoteModelContent content, String targetId, String textureName,
            BooleanSupplier cancelled, RemoteChunkFetcher fetcher,
            Executor workers, Function<ModelContent, T> load) {
        return loadChunks(content, cancelled, fetcher, workers,
                () -> renderTargetChunks(content, targetId, textureName), load);
    }

    <T> CompletableFuture<T> loadChunks(
            RemoteModelContent content,
            BooleanSupplier cancelled, RemoteChunkFetcher fetcher,
            Executor workers, List<AssetContainerView.ChunkInfo> chunks,
            Function<ModelContent, T> load) {
        var immutableChunks = List.copyOf(chunks);
        return loadChunks(content, cancelled, fetcher, workers,
                () -> immutableChunks, load);
    }

    private <T> CompletableFuture<T> loadChunks(
            RemoteModelContent content,
            BooleanSupplier cancelled, RemoteChunkFetcher fetcher,
            Executor workers, ChunkPlan chunks,
            Function<ModelContent, T> load) {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(cancelled, "cancelled");
        Objects.requireNonNull(workers, "workers");
        Objects.requireNonNull(chunks, "chunks");
        Objects.requireNonNull(load, "load");
        var probe = CompletableFuture.supplyAsync(() -> {
            try {
                return probeChunks(content, chunks.get(), cancelled, load);
            } catch (IOException | RuntimeException failure) {
                throw new CompletionException(failure);
            }
        }, workers);
        var transfer = new AtomicReference<CompletableFuture<Void>>();
        var transferCancellationRequested = new AtomicBoolean();
        var result = probe.thenCompose(outcome -> {
            if (outcome.missing.isEmpty()) {
                return CompletableFuture.completedFuture(outcome.loaded);
            }
            if (fetcher == null) {
                return CompletableFuture.failedFuture(
                        AssetLoadException.access("Remote chunk fetcher is unavailable"));
            }
            var expected = new LinkedHashMap<String, AssetContainerView.ChunkInfo>();
            outcome.missing.forEach(chunk -> expected.put(chunk.type(), chunk));
            var received = new ConcurrentHashMap<String, UniBuffer>();
            final CompletableFuture<Void> fetching;
            try {
                fetching = Objects.requireNonNull(fetcher.fetch(
                        content.representation().identity(), outcome.missing,
                        (chunk, bytes) -> receiveChunk(expected, received, chunk, bytes)),
                        "Remote chunk fetcher returned a null future");
            } catch (RuntimeException failure) {
                closeAll(received);
                return CompletableFuture.failedFuture(failure);
            }
            fetching.whenComplete((ignored, failure) -> {
                if (failure != null) {
                    closeAll(received);
                }
            });
            transfer.set(fetching);
            if (transferCancellationRequested.get()) {
                fetching.cancel(false);
            }
            return fetching.thenApplyAsync(ignored -> {
                try {
                    return persistAndLoad(
                            content, outcome.missing, received, cancelled, load);
                } catch (IOException | RuntimeException failure) {
                    throw new CompletionException(failure);
                }
            }, workers);
        });
        result.whenComplete((ignored, failure) -> {
            if (!result.isCancelled()) {
                return;
            }
            transferCancellationRequested.set(true);
            probe.cancel(false);
            var fetching = transfer.get();
            if (fetching != null) {
                fetching.cancel(false);
            }
        });
        return result;
    }

    private <T> RenderTargetProbe<T> probeChunks(
            RemoteModelContent content,
            List<AssetContainerView.ChunkInfo> descriptors,
            BooleanSupplier cancelled, Function<ModelContent, T> load) throws IOException {
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("Model activation was cancelled");
        }
        var missing = new ArrayList<AssetContainerView.ChunkInfo>();
        for (var chunk : descriptors) {
            if (cancelled.getAsBoolean()) {
                throw new CancellationException("Model activation was cancelled");
            }
            try (var ignored = content.chunks().readStoredVerified(
                    chunk, BufferType.ARRAY)) {
                // A verified hit remains subject to the load's own exact read validation.
            } catch (IOException unavailable) {
                missing.add(chunk);
            }
        }
        return missing.isEmpty()
                ? RenderTargetProbe.loaded(load.apply(content))
                : RenderTargetProbe.missing(List.copyOf(missing));
    }

    private static List<AssetContainerView.ChunkInfo> renderTargetChunks(
            RemoteModelContent content, String targetId, String textureName)
            throws IOException {
        var view = content.representation().view();
        var target = view.requireRenderTarget(targetId);
        var selected = ModelManifestLookup.chooseTexture(
                view, targetId, textureName);
        var descriptors = new LinkedHashMap<String, AssetContainerView.ChunkInfo>();
        addBlob(descriptors, view, target.descriptor().blobId());
        if (view.schema() == com.elfmcys.ysm.format.schema.model.ModelSchema.MC) {
            var texture = target.textureDescriptor(selected);
            addBlob(descriptors, view, texture.uv().blobId());
            if (texture.hasNormal()) addBlob(descriptors, view, texture.normalUnsafe().blobId());
            if (texture.hasSpecular()) addBlob(descriptors, view, texture.specularUnsafe().blobId());
        }
        var common = view.getManifest().commonAssets();
        if (common.stringsBlobId() > 0) {
            addBlob(descriptors, view, common.stringsBlobId());
        }
        return descriptors.values().stream()
                .sorted(Comparator.comparing(AssetContainerView.ChunkInfo::type))
                .toList();
    }

    private static void receiveChunk(
            Map<String, AssetContainerView.ChunkInfo> expected,
            Map<String, UniBuffer> received,
            AssetContainerView.ChunkInfo chunk, UniBuffer bytes) throws IOException {
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(bytes, "bytes");
        var descriptor = expected.get(chunk.type());
        if (!sameChunk(descriptor, chunk)) {
            throw AssetLoadException.content(
                    "Remote transfer returned an unexpected chunk");
        }
        var copy = bytes.acquire();
        if (received.putIfAbsent(chunk.type(), copy) != null) {
            copy.close();
            throw AssetLoadException.content(
                    "Remote transfer returned a chunk more than once: " + chunk.type());
        }
    }

    private <T> T persistAndLoad(
            RemoteModelContent content,
            List<AssetContainerView.ChunkInfo> missing,
            Map<String, UniBuffer> received,
            BooleanSupplier cancelled,
            Function<ModelContent, T> load) throws IOException {
        try {
            var complete = new LinkedHashMap<String, UniBuffer>();
            for (var chunk : missing) {
                if (cancelled.getAsBoolean()) {
                    throw new CancellationException("Model activation was cancelled");
                }
                var bytes = received.get(chunk.type());
                if (bytes == null) {
                    throw AssetLoadException.content(
                            "Remote transfer completed without a required chunk: " + chunk.type());
                }
                ChunkDecoding.validateStored(bytes, chunk);
                complete.put(chunk.type(), bytes);
            }
            var prepared = content.withReceived(complete);
            try {
                for (var chunk : missing) {
                    try {
                        publishChunk(content.representation().modelId(), chunk,
                                complete.get(chunk.type()));
                    } catch (IOException cacheFailure) {
                        YesSteveModel.LOGGER.warn(
                                "Failed to persist verified remote model chunk model={} chunk={}",
                                content.representation().modelId(), chunk.type(), cacheFailure);
                    }
                }
                if (cancelled.getAsBoolean()) {
                    throw new CancellationException("Model activation was cancelled");
                }
                return load.apply(prepared);
            } finally {
                prepared.closeReceived();
            }
        } finally {
            closeAll(received);
        }
    }

    private static void closeAll(Map<String, UniBuffer> buffers) {
        buffers.values().forEach(UniBuffer::close);
        buffers.clear();
    }

    private static boolean sameChunk(AssetContainerView.ChunkInfo expected,
                                     AssetContainerView.ChunkInfo actual) {
        return expected != null
                && expected.type().equals(actual.type())
                && expected.offset() == actual.offset()
                && expected.size() == actual.size()
                && expected.decodeSize() == actual.decodeSize()
                && expected.encoding().equals(actual.encoding())
                && Arrays.equals(expected.hash(), actual.hash());
    }

    private static void addBlob(Map<String, AssetContainerView.ChunkInfo> descriptors,
                                ModelFileView view,
                                int blobId) throws IOException {
        var name = AssetFileConstant.BLOB_CHUNK_PREFIX + blobId;
        var chunk = view.getFileView().getAssetView().getChunkInfo(name);
        if (chunk == null) {
            throw AssetLoadException.content("Model activation chunk is missing: " + name);
        }
        descriptors.putIfAbsent(name, chunk);
    }

    @Override
    public synchronized void close() {
        closed = true;
    }

    private void requireOpen() throws IOException {
        if (closed) {
            throw AssetLoadException.access("Remote model store is closed");
        }
    }

    private RemoteModelContent validateMetadata(ModelFileIdentity identity,
                                                UniBuffer metadataPrefix)
            throws IOException {
        try {
            var representation = ModelRepresentation.fromMetadataPrefix(
                    identity, metadataPrefix);
            var asset = representation.view().getFileView().getAssetView();
            if (!identity.containerId().equals(asset.getContainerId())) {
                representation.close();
                throw AssetLoadException.content("Remote metadata container id mismatch");
            }
            var chunks = new StoreChunkDataSource(identity);
            return new RemoteModelContent(representation, chunks, chunks, this);
        } catch (AssetLoadException failure) {
            throw failure;
        } catch (IOException | RuntimeException invalid) {
            throw AssetLoadException.content("Invalid remote metadata prefix", invalid);
        }
    }

    ChunkDataSource chunkSource(ModelFileIdentity identity) {
        return new StoreChunkDataSource(identity);
    }

    Path metadataPath(ModelFileIdentity identity) {
        return ownedPath(metadataRoot(identity).resolve(METADATA_FILE));
    }

    Path chunkPath(Hash256 modelId, Hash256 hash, String encoding) {
        return ownedPath(modelRoot(modelId).resolve(
                hash + (ZSTD_ENCODING.equals(encoding) ? ZSTD_CHUNK_SUFFIX : CHUNK_SUFFIX)));
    }

    /** Verified remote content of one model. */
    private Path modelRoot(Hash256 modelId) {
        return root.resolve(modelId.toString());
    }

    /** Verified remote metadata of one exact container. */
    private Path metadataRoot(ModelFileIdentity identity) {
        return modelRoot(identity.modelId())
                .resolve(METADATA_DIRECTORY)
                .resolve(identity.containerId().toString());
    }

    private void publishAtomic(Path target, UniBuffer bytes,
                               AtomicSharedCache.CacheValidator validator) throws IOException {
        target = ownedPath(target);
        var directory = prepareOwnedDirectory(target.getParent());
        var fileName = target.getFileName();
        var physicalTarget = directory.path().resolve(fileName);
        rejectEscapingExistingTarget(physicalTarget);
        var temporary = directory.path().resolve(fileName + ".tmp-" + UUID.randomUUID());
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                var source = bytes.nio();
                while (source.hasRemaining()) {
                    channel.write(source);
                }
                channel.force(true);
            }
            if (!validator.validate(temporary)) {
                throw AssetLoadException.content(
                        "Remote cache writer produced an invalid object: " + target);
            }
            verifyOwnedDirectory(directory);
            rejectEscapingExistingTarget(physicalTarget);
            atomicMove.move(temporary, physicalTarget);
        } finally {
            verifyOwnedDirectory(directory);
            Files.deleteIfExists(temporary);
        }
    }

    private Path ownedPath(Path candidate) {
        var normalized = candidate.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            throw new IllegalArgumentException("Remote cache path escapes its owner root");
        }
        return normalized;
    }

    private OwnedDirectory prepareOwnedDirectory(Path candidate) throws IOException {
        var lexical = ownedPath(candidate);
        var current = inspectOwnedDirectory(root);
        for (var segment : root.relativize(lexical)) {
            var child = current.path().resolve(segment.toString());
            try {
                Files.createDirectory(child);
            } catch (FileAlreadyExistsException ignored) {
                // Existing components are accepted only after resolving their physical identity.
            } catch (SecurityException access) {
                throw AssetLoadException.access(
                        "Failed to create a remote cache directory", access);
            }
            current = inspectOwnedDirectory(child);
        }
        return current;
    }

    private OwnedDirectory inspectOwnedDirectory(Path candidate) throws IOException {
        final Path physical;
        final BasicFileAttributes attributes;
        try {
            physical = candidate.toRealPath();
            attributes = Files.readAttributes(physical, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
        } catch (IOException | SecurityException access) {
            throw AssetLoadException.access(
                    "Failed to resolve a remote cache directory", access);
        }
        if (!physical.startsWith(root) || !attributes.isDirectory()) {
            throw AssetLoadException.access(
                    "Remote cache directory escapes its physical owner root");
        }
        return new OwnedDirectory(physical, attributes.fileKey());
    }

    private void verifyOwnedDirectory(OwnedDirectory expected) throws IOException {
        var current = inspectOwnedDirectory(expected.path());
        if (!sameIdentity(expected.path(), expected.fileKey(),
                current.path(), current.fileKey())) {
            throw AssetLoadException.access(
                    "Remote cache directory changed during an active operation");
        }
    }

    private OwnedFile inspectOwnedFile(Path candidate) throws IOException {
        var lexical = ownedPath(candidate);
        final Path physical;
        final BasicFileAttributes attributes;
        try {
            physical = lexical.toRealPath();
            attributes = Files.readAttributes(physical, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            throw missing;
        } catch (IOException | SecurityException access) {
            throw AssetLoadException.access("Failed to resolve cached remote data", access);
        }
        if (!physical.startsWith(root) || !attributes.isRegularFile()) {
            throw AssetLoadException.access(
                    "Cached remote data escapes its physical owner root");
        }
        return new OwnedFile(physical, attributes.fileKey());
    }

    private void verifyOwnedFile(OwnedFile expected) throws IOException {
        var current = inspectOwnedFile(expected.path());
        if (!sameIdentity(expected.path(), expected.fileKey(),
                current.path(), current.fileKey())) {
            throw AssetLoadException.access(
                    "Cached remote data changed while it was being opened");
        }
    }

    private void rejectEscapingExistingTarget(Path target) throws IOException {
        try {
            inspectOwnedFile(target);
        } catch (NoSuchFileException missing) {
            return;
        }
    }

    private record OwnedDirectory(Path path, Object fileKey) {
    }

    private record OwnedFile(Path path, Object fileKey) {
    }

    private record RenderTargetProbe<T>(T loaded,
                                         List<AssetContainerView.ChunkInfo> missing) {
        private static <T> RenderTargetProbe<T> loaded(T value) {
            return new RenderTargetProbe<>(Objects.requireNonNull(value, "loaded"), List.of());
        }

        private static <T> RenderTargetProbe<T> missing(
                List<AssetContainerView.ChunkInfo> chunks) {
            return new RenderTargetProbe<>(null, List.copyOf(chunks));
        }
    }

    @FunctionalInterface
    private interface ChunkPlan {
        List<AssetContainerView.ChunkInfo> get() throws IOException;
    }

    private static boolean sameIdentity(Path expectedPath, Object expectedKey,
                                        Path currentPath, Object currentKey)
            throws IOException {
        if (expectedKey != null && currentKey != null) {
            return expectedKey.equals(currentKey);
        }
        return Files.isSameFile(expectedPath, currentPath);
    }

    private final class StoreChunkDataSource implements ChunkDataSource {
        private final ModelFileIdentity identity;

        private StoreChunkDataSource(ModelFileIdentity identity) {
            this.identity = Objects.requireNonNull(identity, "identity");
        }

        @Override
        public UniBuffer readPayload(AssetContainerView.ChunkInfo chunk,
                                     BufferType bufferType) throws IOException {
            return decodePayload(chunk, bufferType);
        }

        @Override
        public UniBuffer readPayload(BooleanSupplier cancelled,
                                     AssetContainerView.ChunkInfo chunk,
                                     BufferType bufferType) throws IOException {
            if (cancelled.getAsBoolean()) {
                throw new CancellationException("Remote chunk read was cancelled");
            }
            var result = decodePayload(chunk, bufferType);
            if (cancelled.getAsBoolean()) {
                result.close();
                throw new CancellationException("Remote chunk read was cancelled");
            }
            return result;
        }

        private UniBuffer decodePayload(AssetContainerView.ChunkInfo chunk,
                                        BufferType bufferType) throws IOException {
            try (var stored = readStoredVerifiedNow(chunk, BufferType.ARRAY)) {
                if (!ChunkDecoding.isZstd(chunk)) {
                    return bufferType == BufferType.ARRAY
                            ? stored.acquireArray() : stored.acquireNative();
                }
                try {
                    return Zstd.decompressAndValidate(stored, chunk.decodeSize(),
                            chunk.hash(), bufferType);
                } catch (RuntimeException invalid) {
                    throw AssetLoadException.content(
                            "Invalid cached zstd chunk: " + chunk.type(), invalid);
                }
            }
        }

        @Override
        public UniBuffer readStoredVerified(AssetContainerView.ChunkInfo chunk,
                                            BufferType bufferType) throws IOException {
            return readStoredVerifiedNow(chunk, bufferType);
        }

        private UniBuffer readStoredVerifiedNow(AssetContainerView.ChunkInfo chunk,
                                                 BufferType bufferType) throws IOException {
            synchronized (RemoteModelStore.this) {
                requireOpen();
                return readStoredVerifiedLocked(chunk, bufferType);
            }
        }

        private UniBuffer readStoredVerifiedLocked(AssetContainerView.ChunkInfo chunk,
                                                   BufferType bufferType) throws IOException {
            if (ModelFileConstant.MANIFEST_CHUNK_NAME.equals(chunk.type())) {
                throw AssetLoadException.content(
                        "Manifest is included in the metadata prefix");
            }
            if (chunk.hash() == null || chunk.hash().length != Hash256.SIZE) {
                throw AssetLoadException.content("Remote chunk has no content hash");
            }
            var path = chunkPath(identity.modelId(), new Hash256(chunk.hash()),
                    chunk.encoding());
            AssetLoadException cachedFailure;
            try (var stored = read(path, bufferType)) {
                validateCached(stored, chunk);
                return stored.acquire();
            } catch (NoSuchFileException missing) {
                cachedFailure = AssetLoadException.access(
                        "Remote chunk is not cached: " + chunk.type(), missing);
            } catch (AssetLoadException failure) {
                if (failure.reason() == AssetLoadException.Reason.ACCESS) {
                    throw failure;
                }
                cachedFailure = failure;
            }
            throw cachedFailure;
        }

        private void validateCached(UniBuffer stored, AssetContainerView.ChunkInfo chunk)
                throws IOException {
            if (ChunkDecoding.isZstd(chunk)) {
                try {
                    try (var ignored = Zstd.decompressAndValidate(stored, chunk.decodeSize(),
                            chunk.hash(), BufferType.NATIVE)) {
                        // A cached zstd representation may have a different compressed size.
                    }
                } catch (RuntimeException error) {
                    throw AssetLoadException.content(
                            "Invalid cached zstd chunk: " + chunk.type(), error);
                }
            } else {
                ChunkDecoding.validateStored(stored, chunk);
            }
        }
    }

    private UniBuffer read(Path path, BufferType type) throws IOException {
        var physical = inspectOwnedFile(path);
        final FileChannel opened;
        try {
            opened = FileChannel.open(physical.path(), StandardOpenOption.READ,
                    LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            throw missing;
        } catch (IOException | SecurityException error) {
            throw AssetLoadException.access("Failed to open cached remote chunk", error);
        }
        UniBuffer result = null;
        try (var channel = opened) {
            verifyOwnedFile(physical);
            var size = channel.size();
            if (size > AssetContainerConstant.MAX_FILE_SIZE) {
                throw AssetLoadException.content("Cached data is too large: " + size);
            }
            result = UniBuffer.allocate(Math.toIntExact(size), type);
            var target = result.nio();
            while (target.hasRemaining()) {
                if (channel.read(target) < 0) {
                    throw AssetLoadException.content(
                            "Cached chunk was truncated while reading");
                }
            }
        } catch (AssetLoadException error) {
            close(result);
            throw error;
        } catch (IOException error) {
            close(result);
            throw AssetLoadException.access("Failed to read cached remote chunk", error);
        } catch (RuntimeException | Error error) {
            close(result);
            throw error;
        }
        try {
            afterCacheRead.observe(physical.path());
            return result;
        } catch (AssetLoadException error) {
            result.close();
            throw error;
        } catch (IOException error) {
            result.close();
            throw AssetLoadException.access("Failed to read cached remote chunk", error);
        } catch (RuntimeException | Error error) {
            result.close();
            throw error;
        }
    }

    private static void close(UniBuffer buffer) {
        if (buffer != null) {
            buffer.close();
        }
    }

    @FunctionalInterface
    interface AtomicMove {
        void move(Path temporary, Path target) throws IOException;
    }

    @FunctionalInterface
    interface CacheReadObserver {
        void observe(Path path) throws IOException;
    }

}
