package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.AssetPaths;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.model.catalog.source.CatalogModelLocation;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.format.parser.RawCompileResult;
import com.elfmcys.ysm.format.schema.model.ModelFileIdentityReader;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;

/** Exact-path converted-container storage owned by model management. */
public final class ConvertedObjectStore {
    /** Suffix of one stored converted container. */
    static final String CONTAINER_SUFFIX = ".mxc";

    /** Staging directory of the converted store. */
    static final String TEMPORARY_DIRECTORY = ".tmp";

    private final Path gameCacheRoot;
    private final AtomicSharedCache cache;

    public ConvertedObjectStore(Path gameCacheRoot) {
        this(gameCacheRoot, new AtomicSharedCache(gameCacheRoot));
    }

    ConvertedObjectStore(Path gameCacheRoot, AtomicSharedCache cache) {
        this.gameCacheRoot = Objects.requireNonNull(gameCacheRoot, "gameCacheRoot");
        this.cache = Objects.requireNonNull(cache, "cache");
    }

    public Path temporaryRoot() {
        return convertedRoot().resolve(TEMPORARY_DIRECTORY);
    }

    public LegacyConversionCache legacyCache(String fullModVersion, String profile) {
        return new LegacyConversionCache(gameCacheRoot, this, cache, fullModVersion, profile);
    }

    private Path convertedRoot() {
        return AssetPaths.convertedRoot(gameCacheRoot);
    }

    /** Converted objects of one model, partitioned by model id. */
    private Path modelRoot(Hash256 modelId) {
        return convertedRoot().resolve(modelId.toString());
    }

    private Path containerPath(Hash256 modelId, Hash256 containerId) {
        return modelRoot(modelId).resolve(containerId + CONTAINER_SUFFIX);
    }

    public Optional<ModelFileIdentity> findIdentity(ConvertedSourceIndex entry)
            throws IOException {
        Objects.requireNonNull(entry, "entry");
        var expectedModelId = entry.modelId();
        var expectedContainerId = entry.containerId();
        var file = containerPath(expectedModelId, expectedContainerId);
        if (!RegularFileProbe.exists(file)) {
            return Optional.empty();
        }
        try {
            final ModelFileIdentity identity;
            try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
                identity = ModelFileIdentityReader.read(channel);
            }
            if (!identity.modelId().equals(expectedModelId)
                    || !identity.containerId().equals(expectedContainerId)) {
                return Optional.empty();
            }
            return Optional.of(identity);
        } catch (AssetLoadException accessOrContent) {
            if (accessOrContent.reason() == AssetLoadException.Reason.ACCESS) {
                throw accessOrContent;
            }
            return Optional.empty();
        } catch (FileSystemException | SecurityException infrastructureFailure) {
            throw infrastructureFailure;
        } catch (IOException | RuntimeException invalid) {
            return Optional.empty();
        }
    }

    public ModelFileIdentity commit(RawCompileResult compiled,
                                    CatalogModelLocation location) throws IOException {
        Objects.requireNonNull(compiled, "compiled");
        final ModelFileIdentity stagedIdentity;
        try (var staged = ManagedContainer.verifyFile(compiled.stagedContainer())) {
            stagedIdentity = staged.identity();
        }
        var actualModelId = stagedIdentity.modelId();
        if (!compiled.modelHash().equals(actualModelId)) {
            throw new ModelHashMismatchException(location, compiled.modelHash(), actualModelId);
        }
        var containerId = stagedIdentity.containerId();
        var target = containerPath(actualModelId, containerId);
        cache.materializeReplacing("converted-container", identityKey(stagedIdentity), target,
                path -> fullyMatches(path, stagedIdentity), temporary -> {
            Files.copy(compiled.stagedContainer(), temporary,
                    StandardCopyOption.REPLACE_EXISTING);
            try (var verified = ManagedContainer.verifyFile(temporary)) {
                if (!verified.identity().equals(stagedIdentity)) {
                    throw new IOException("Converted container identity changed before commit");
                }
            }
        });
        return stagedIdentity;
    }

    public Path objectPath(Hash256 modelId, Hash256 containerId) {
        return containerPath(modelId, containerId);
    }

    private static boolean fullyMatches(Path file, ModelFileIdentity expected)
            throws IOException {
        try (var verified = ManagedContainer.verifyFile(file)) {
            return verified.identity().equals(expected);
        } catch (AssetLoadException failure) {
            if (failure.reason() == AssetLoadException.Reason.ACCESS) {
                throw failure;
            }
            return false;
        } catch (FileSystemException | SecurityException failure) {
            throw failure;
        } catch (IOException | RuntimeException invalid) {
            return false;
        }
    }

    private static String identityKey(ModelFileIdentity identity) {
        return identity.modelId() + ":" + identity.containerId();
    }

}
