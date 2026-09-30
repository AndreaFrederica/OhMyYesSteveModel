package com.elfmcys.ysm.model.catalog.source;

import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.parser.RawModelDiagnostic;
import com.elfmcys.ysm.model.catalog.RawModelImporter;
import com.elfmcys.ysm.model.catalog.content.DirectContainerAdmission;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogIndexEntry;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import com.elfmcys.ysm.model.storage.ConvertedObjectStore;
import com.elfmcys.ysm.model.storage.ConvertedSourceIndex;
import com.elfmcys.ysm.model.storage.ConvertedSourceIndexStore;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.model.storage.LegacyConversionCache;
import cc.sirrus.ysmlib.v3d.V3dCache;
import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.natives.legacy.LegacyModelImportException;
import com.elfmcys.ysm.natives.legacy.LegacyModelImporter;
import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class ModelSourceResolver {
    private final RawModelImporter importer;
    private final LegacyModelImporter legacyImporter;
    private final ConvertedSourceIndexStore indexes;
    private final ConvertedObjectStore objects;
    private final LegacyConversionCache legacyCache;

    public ModelSourceResolver(RawModelImporter importer,
                               ConvertedSourceIndexStore indexes,
                               ConvertedObjectStore objects) {
        this(importer, new LegacyModelImporter(), indexes, objects);
    }

    public ModelSourceResolver(RawModelImporter importer,
                               LegacyModelImporter legacyImporter,
                               ConvertedSourceIndexStore indexes,
                               ConvertedObjectStore objects) {
        this.importer = Objects.requireNonNull(importer, "importer");
        this.legacyImporter = Objects.requireNonNull(legacyImporter, "legacyImporter");
        this.indexes = Objects.requireNonNull(indexes, "indexes");
        this.objects = Objects.requireNonNull(objects, "objects");
        this.legacyCache = objects.legacyCache(indexes.fullModVersion(), legacyImporter.cacheProfile());
    }

    public Resolution resolve(SourceObservation observation) throws CatalogBuildException {
        var location = new CatalogModelLocation(observation.key().root().rootKind(),
                observation.key().relativePath());
        if (observation.key().sourceKind() == ModelSourceKind.DIRECT_CONTAINER) {
            return validateDirect(observation, location);
        }
        if (observation.key().sourceKind() == ModelSourceKind.LEGACY_ARCHIVE) {
            var result = resolveLegacy(observation, location);
            result.content().representation().close();
            return new Resolution(result.entry(), result.convertedIndexEntry(), result.diagnostics());
        }
        if (observation.key().sourceKind() == ModelSourceKind.UNSUPPORTED_YSM) {
            throw new ModelSourceException("Unsupported .ysm container");
        }

        var rawRelativePath = observation.key().root().rootKind().namespace()
                + "/" + observation.key().relativePath().value();
        try (var captured = importer.capture(observation.absolutePath())) {
            var indexed = indexes.find(rawRelativePath)
                    .filter(entry -> entry.modelId().equals(captured.modelId()));
            if (indexed.isPresent()) {
                var existing = objects.findIdentity(indexed.get());
                if (existing.isPresent()) {
                    var identity = existing.get();
                    var entry = new CatalogIndexEntry(identity,
                            location, objects.objectPath(identity.modelId(), identity.containerId()));
                    return new Resolution(entry, indexed, captured.diagnostics());
                }
            }

            Files.createDirectories(objects.temporaryRoot());
            var temporaryDirectory = Files.createTempDirectory(
                    objects.temporaryRoot(), "convert-");
            try {
                var compiled = importer.convert(captured, temporaryDirectory);
                var object = objects.commit(compiled, location);
                var entry = new ConvertedSourceIndex(object, rawRelativePath,
                        indexes.fullModVersion());
                var catalogEntry = new CatalogIndexEntry(
                        object, location,
                        objects.objectPath(object.modelId(), object.containerId()));
                return new Resolution(catalogEntry, Optional.of(entry),
                        compiled.diagnostics());
            } finally {
                deleteTree(temporaryDirectory);
            }
        } catch (ModelSourceException | CatalogInfrastructureException failure) {
            throw failure;
        } catch (IOException | SecurityException failure) {
            throw new CatalogInfrastructureException(
                    "Failed to access converted model storage", failure);
        } catch (RuntimeException failure) {
            throw new ModelSourceException("Failed to capture or convert raw model", failure);
        }
    }

    public MaterializedResolution resolveMaterialized(SourceObservation observation)
            throws CatalogBuildException, IOException {
        if (observation.key().sourceKind() == ModelSourceKind.LEGACY_ARCHIVE) {
            return resolveLegacy(observation, new CatalogModelLocation(
                    observation.key().root().rootKind(), observation.key().relativePath()));
        }
        if (observation.key().sourceKind() == ModelSourceKind.DIRECT_CONTAINER) {
            var location = new CatalogModelLocation(observation.key().root().rootKind(),
                    observation.key().relativePath());
            try {
                var content = ManagedContainer.openDirect(
                        observation.absolutePath(), location);
                try {
                    DirectContainerAdmission.requireEmbeddedPreview(content);
                    return new MaterializedResolution(new CatalogIndexEntry(
                            content.representation().identity(), location,
                            observation.absolutePath()), content,
                            Optional.empty(), List.of());
                } catch (IOException | RuntimeException | Error failure) {
                    content.representation().close();
                    throw failure;
                }
            } catch (AssetLoadException failure) {
                if (failure.reason() == AssetLoadException.Reason.ACCESS) {
                    throw new CatalogInfrastructureException(
                            "Failed to access direct model container", failure);
                }
                throw new ModelSourceException(
                        "Direct model container is invalid", failure);
            } catch (FileSystemException | SecurityException failure) {
                throw new CatalogInfrastructureException(
                        "Failed to access direct model container", failure);
            } catch (IOException failure) {
                throw new ModelSourceException(
                        "Direct model container is invalid", failure);
            } catch (RuntimeException failure) {
                throw new ModelSourceException(
                        "Direct model container is invalid", failure);
            }
        }
        var resolved = resolve(observation);
        var content = ManagedContainer.openIndexed(resolved.entry());
        try {
            return new MaterializedResolution(resolved.entry(), content,
                    resolved.convertedIndexEntry(), resolved.diagnostics());
        } catch (RuntimeException | Error failure) {
            content.representation().close();
            throw failure;
        }
    }

    private Resolution validateDirect(SourceObservation observation,
                                      CatalogModelLocation location)
            throws CatalogBuildException {
        try {
            var content = ManagedContainer.openDirect(observation.absolutePath(), location);
            try {
                DirectContainerAdmission.requireEmbeddedPreview(content);
                return new Resolution(new CatalogIndexEntry(
                        content.representation().identity(), location, observation.absolutePath()),
                        Optional.empty(), List.of());
            } finally {
                content.representation().close();
            }
        } catch (AssetLoadException failure) {
            if (failure.reason() == AssetLoadException.Reason.ACCESS) {
                throw new CatalogInfrastructureException(
                        "Failed to access direct model container", failure);
            }
            throw new ModelSourceException("Direct model container is invalid", failure);
        } catch (FileSystemException | SecurityException failure) {
            throw new CatalogInfrastructureException(
                    "Failed to access direct model container", failure);
        } catch (IOException failure) {
            throw new ModelSourceException("Direct model container is invalid", failure);
        } catch (RuntimeException failure) {
            throw new ModelSourceException("Direct model container is invalid", failure);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure)
                    throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public void replaceIndex(Set<String> rootNamespaces,
                             Collection<ConvertedSourceIndex> entries)
            throws CatalogInfrastructureException {
        try {
            indexes.replaceScopes(rootNamespaces, entries);
        } catch (IOException failure) {
            throw new CatalogInfrastructureException(
                    "Failed to replace converted-source index", failure);
        }
    }

    public record Resolution(CatalogIndexEntry entry,
                             Optional<ConvertedSourceIndex> convertedIndexEntry,
                             List<RawModelDiagnostic> diagnostics) {
        public Resolution {
            Objects.requireNonNull(entry, "entry");
            Objects.requireNonNull(convertedIndexEntry, "convertedIndexEntry");
            diagnostics = List.copyOf(diagnostics);
        }
    }

    public record MaterializedResolution(
            CatalogIndexEntry entry, ManagedContainer content,
            Optional<ConvertedSourceIndex> convertedIndexEntry,
            List<RawModelDiagnostic> diagnostics) {
        public MaterializedResolution {
            Objects.requireNonNull(entry, "entry");
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(convertedIndexEntry, "convertedIndexEntry");
            diagnostics = List.copyOf(diagnostics);
        }
    }

    /** Cache-only worker path: never decodes an envelope or rebuilds a converted object. */
    public Optional<MaterializedResolution> resolveCached(SourceObservation observation)
            throws IOException {
        if (observation.key().sourceKind() != ModelSourceKind.LEGACY_ARCHIVE) {
            return Optional.empty();
        }
        var source = V3dCache.capture(observation.absolutePath());
        var location = new CatalogModelLocation(observation.key().root().rootKind(),
                observation.key().relativePath());
        // Receipts and objects are atomically published. Do not acquire the conversion
        // lock here: a duplicate cold source must not block unrelated cache hits.
        return legacyCache.find(source, location).map(content ->
                cachedLegacyResolution(observation, content));
    }

    private MaterializedResolution cachedLegacyResolution(SourceObservation observation,
                                                           ManagedContainer content) {
        var identity = content.representation().identity();
        var sourcePath = observation.key().root().rootKind().namespace()
                + "/" + observation.key().relativePath().value();
        return new MaterializedResolution(new CatalogIndexEntry(identity, content.location(), content.file()),
                content, Optional.of(new ConvertedSourceIndex(identity, sourcePath,
                indexes.fullModVersion())), List.of());
    }

    private MaterializedResolution resolveLegacy(
            SourceObservation observation, CatalogModelLocation location)
            throws CatalogBuildException {
        try {
            var source = V3dCache.capture(observation.absolutePath());
            return legacyCache.withSourceLock(source, () -> resolveLegacyCaptured(observation, location, source));
        } catch (CatalogBuildException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new CatalogInfrastructureException("Failed to access legacy source/cache", failure);
        }
    }

    private MaterializedResolution resolveLegacyCaptured(SourceObservation observation,
            CatalogModelLocation location, V3dCache.CapturedSource source) throws CatalogBuildException {
        var sourcePath = observation.key().root().rootKind().namespace()
                + "/" + observation.key().relativePath().value();
        Path temporaryDirectory = null;
        try {
            var existing = legacyCache.find(source, location);
            if (existing.isPresent()) {
                var content = existing.get();
                YesSteveModel.LOGGER.debug("Legacy conversion cache hit source={} sha256={}", sourcePath, source.sha256());
                return cachedLegacyResolution(observation, content);
            }
            Files.createDirectories(objects.temporaryRoot());
            temporaryDirectory = Files.createTempDirectory(
                    objects.temporaryRoot(), "legacy-convert-");
            final com.elfmcys.ysm.format.parser.RawCompileResult staged;
            try {
                staged = legacyImporter.stage(source, legacyCache.wireRoot(), temporaryDirectory);
            } catch (IOException failure) {
                throw new ModelSourceException("Failed to decode/project legacy source or V3D cache", failure);
            }
            final ModelFileIdentity object;
            try {
                object = objects.commit(staged, location);
            } catch (IOException | RuntimeException failure) {
                throw LegacyModelImportException.publicationFailure(
                        "Failed to commit converted legacy model", failure);
            }
            var sourceIndex = new ConvertedSourceIndex(
                    object, sourcePath, indexes.fullModVersion());
            var catalogEntry = new CatalogIndexEntry(
                    object, location,
                    objects.objectPath(object.modelId(), object.containerId()));
            var content = legacyCache.record(source, catalogEntry);
            YesSteveModel.LOGGER.debug("Legacy conversion cache built source={} sha256={}", sourcePath, source.sha256());
            return new MaterializedResolution(
                    catalogEntry, content, Optional.of(sourceIndex), List.of());
        } catch (CatalogBuildException failure) {
            throw failure;
        } catch (LegacyModelImportException failure) {
            throw new ModelSourceException(
                    "Legacy model import failed with status " + failure.statusCode(), failure);
        } catch (FileSystemException | SecurityException failure) {
            throw new CatalogInfrastructureException(
                    "Failed to access legacy conversion storage", failure);
        } catch (IOException failure) {
            throw new CatalogInfrastructureException(
                    "Failed to publish converted legacy model", failure);
        } finally {
            if (temporaryDirectory != null) {
                try {
                    deleteTree(temporaryDirectory);
                } catch (IOException cleanupFailure) {
                    // A failed best-effort cleanup must not change publication authority.
                }
            }
        }
    }
}
