package com.elfmcys.ysm.model.catalog.source;

import com.elfmcys.ysm.format.container.AssetContainerConstant;
import com.elfmcys.ysm.format.legacy.LegacyYsmHeader;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.model.domain.ModelScanError;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.Map;

/** Deterministic source discovery shared by runtime scanning and build-time builtin indexing. */
public final class ModelSourceDiscovery {
    private static final Set<String> FILE_EXTENSIONS = Set.of(
            ".mxc", ".ysm", ".zip", ".7z", ".pmx", ".pmd", ".fbx",
            ".gltf", ".glb", ".vrm", ".unitypackage", ".yscene");
    private static final Set<String> GENERIC_EXTENSIONS = Set.of(
            ".pmx", ".pmd", ".fbx", ".gltf", ".glb", ".vrm", ".yscene");

    private ModelSourceDiscovery() {
    }

    public static RootInventoryState inventory(ModelCatalogSource source) {
        var configured = source.path().toAbsolutePath().normalize();
        CatalogRootIdentity identity = new CatalogRootIdentity(
                source.rootKind(), configured, "");
        try {
            if (source.createIfMissing()) {
                Files.createDirectories(configured);
            }
            var canonical = configured.toRealPath();
            var attributes = Files.readAttributes(canonical, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isDirectory()) {
                throw new IOException("Model root is not a directory: " + configured);
            }
            identity = new CatalogRootIdentity(source.rootKind(), canonical,
                    Objects.toString(attributes.fileKey(), ""));

            var discovered = discover(canonical);
            if (!discovered.failures().isEmpty()) {
                var failure = discovered.failures().get(0);
                throw new IOException("Model root traversal is incomplete at " + failure.path(),
                        failure.error());
            }

            var models = new LinkedHashMap<ModelSourceKey, SourceObservation>();
            for (var path : discovered.models()) {
                var real = requireInside(canonical, path);
                var kind = sourceKind(real);
                var relative = ModelPath.relativeTo(canonical, real);
                var key = new ModelSourceKey(identity, relative, kind);
                var stamp = Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)
                        ? SourceStamp.captureDirectory(real) : SourceStamp.captureFile(real);
                var estimatedBytes = stamp instanceof SourceStamp.File file
                        ? file.size() : ((SourceStamp.RawDirectory) stamp).totalBytes();
                models.put(key, new SourceObservation(key, real, stamp, estimatedBytes));
            }

            var packs = new LinkedHashMap<ModelPackSourceKey, PackObservation>();
            for (var directory : discovered.packDirectories()) {
                var real = requireInside(canonical, directory);
                var manifest = real.resolve("ysm-pack.json");
                if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                var hierarchy = real.equals(canonical)
                        ? "" : ModelPath.relativeTo(canonical, real).value() + "/";
                var key = new ModelPackSourceKey(identity, hierarchy);
                var cover = real.resolve("ysm-pack.png");
                packs.put(key, new PackObservation(key, real,
                        SourceStamp.captureFile(manifest),
                        Files.isRegularFile(cover, LinkOption.NOFOLLOW_LINKS)
                                ? SourceStamp.captureFile(cover) : null));
            }
            return new RootInventoryState.Complete(identity, models, packs);
        } catch (IOException | RuntimeException error) {
            return new RootInventoryState.Incomplete(identity,
                    ModelScanError.infrastructure(source.rootKind(), configured.toString(),
                            "ROOT_INVENTORY_INCOMPLETE", error));
        }
    }

    /**
     * Rebuilds only the directory subtrees mentioned by the watcher.  The
     * previous complete inventory remains authoritative for all other paths.
     * This is deliberately event driven: an overflow or an empty previous
     * inventory must use {@link #inventory(ModelCatalogSource)} instead.
     */
    public static RootInventoryState inventoryChanged(ModelCatalogSource source,
                                                      RootInventoryState.Complete previous,
                                                      Set<Path> changedPaths) {
        if (previous == null || changedPaths == null || changedPaths.isEmpty()) {
            return inventory(source);
        }
        var configured = source.path().toAbsolutePath().normalize();
        try {
            var canonical = configured.toRealPath();
            if (!canonical.equals(previous.root().canonicalAbsoluteRoot())) {
                return inventory(source);
            }
            var rootAttributes = Files.readAttributes(canonical, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!rootAttributes.isDirectory()) {
                throw new IOException("Model root is not a directory: " + configured);
            }

            var scopes = new ArrayList<Path>();
            for (var rawChanged : changedPaths) {
                var changed = rawChanged.toAbsolutePath().normalize();
                if (!changed.startsWith(canonical)) {
                    continue;
                }
                var scope = changed;
                // A file changed inside a raw model directory changes the
                // directory source's stamp; rescan that raw directory as one
                // unit instead of walking the entire model root.
                var rawOwner = previous.sources().values().stream()
                        .filter(observation -> observation.key().sourceKind()
                                == ModelSourceKind.CURRENT_RAW_DIRECTORY)
                        .map(SourceObservation::absolutePath)
                        .filter(path -> changed.startsWith(path))
                        .max(Comparator.comparingInt(Path::getNameCount))
                        .orElse(null);
                if (rawOwner != null) {
                    scope = rawOwner;
                } else if (!Files.isDirectory(scope, LinkOption.NOFOLLOW_LINKS)) {
                    scope = scope.getParent();
                }
                if (scope == null || !scope.startsWith(canonical)) {
                    return inventory(source);
                }
                scopes.add(scope);
            }
            if (scopes.isEmpty()) {
                return previous;
            }
            // Keep only the outermost scopes; scanning a child twice defeats
            // the point of the dirty-set and can duplicate observations.
            scopes.sort(Comparator.comparingInt(Path::getNameCount));
            var reduced = new ArrayList<Path>();
            for (var scope : scopes) {
                if (reduced.stream().noneMatch(scope::startsWith)) {
                    reduced.add(scope);
                }
            }

            var identity = previous.root();
            var models = new LinkedHashMap<>(previous.sources());
            var packs = new LinkedHashMap<>(previous.packs());
            for (var scope : reduced) {
                models.entrySet().removeIf(entry -> entry.getValue().absolutePath().startsWith(scope));
                packs.entrySet().removeIf(entry -> entry.getValue().directory().startsWith(scope));
                if (!Files.exists(scope, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                if (Files.isDirectory(scope, LinkOption.NOFOLLOW_LINKS)
                        && isRawDirectory(scope)) {
                    addModel(identity, scope, models);
                    continue;
                }
                var discovered = discover(scope);
                if (!discovered.failures().isEmpty()) {
                    var failure = discovered.failures().get(0);
                    throw new IOException("Model subtree traversal is incomplete at "
                            + failure.path(), failure.error());
                }
                for (var path : discovered.models()) {
                    addModel(identity, requireInside(canonical, path), models);
                }
                for (var directory : discovered.packDirectories()) {
                    var real = requireInside(canonical, directory);
                    var manifest = real.resolve("ysm-pack.json");
                    if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) continue;
                    var hierarchy = real.equals(canonical) ? ""
                            : ModelPath.relativeTo(canonical, real).value() + "/";
                    var key = new ModelPackSourceKey(identity, hierarchy);
                    var cover = real.resolve("ysm-pack.png");
                    packs.put(key, new PackObservation(key, real,
                            SourceStamp.captureFile(manifest),
                            Files.isRegularFile(cover, LinkOption.NOFOLLOW_LINKS)
                                    ? SourceStamp.captureFile(cover) : null));
                }
            }
            return complete(identity, canonical, models, packs);
        } catch (IOException | RuntimeException error) {
            return new RootInventoryState.Incomplete(previous.root(),
                    ModelScanError.infrastructure(source.rootKind(), configured.toString(),
                            "ROOT_INVENTORY_INCOMPLETE", error));
        }
    }

    private static void addModel(CatalogRootIdentity identity, Path real,
                                 Map<ModelSourceKey, SourceObservation> models)
            throws IOException {
        var kind = sourceKind(real);
        var relative = ModelPath.relativeTo(identity.canonicalAbsoluteRoot(), real);
        var key = new ModelSourceKey(identity, relative, kind);
        var stamp = Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)
                ? SourceStamp.captureDirectory(real) : SourceStamp.captureFile(real);
        var estimatedBytes = stamp instanceof SourceStamp.File file
                ? file.size() : ((SourceStamp.RawDirectory) stamp).totalBytes();
        models.put(key, new SourceObservation(key, real, stamp, estimatedBytes));
    }

    private static RootInventoryState.Complete complete(CatalogRootIdentity identity,
                                                         Path canonical,
                                                         Map<ModelSourceKey, SourceObservation> models,
                                                         Map<ModelPackSourceKey, PackObservation> packs) {
        return new RootInventoryState.Complete(identity, models, packs);
    }

    public static SourceObservation observe(ModelSourceKey expectedKey, Path path)
            throws IOException {
        var real = requireInside(expectedKey.root().canonicalAbsoluteRoot(), path);
        var actualKind = sourceKind(real);
        if (actualKind != expectedKey.sourceKind()) {
            throw new IOException("Model source kind changed while loading: " + path);
        }
        var stamp = Files.isDirectory(real, LinkOption.NOFOLLOW_LINKS)
                ? SourceStamp.captureDirectory(real) : SourceStamp.captureFile(real);
        var estimatedBytes = stamp instanceof SourceStamp.File file
                ? file.size() : ((SourceStamp.RawDirectory) stamp).totalBytes();
        return new SourceObservation(expectedKey, real, stamp, estimatedBytes);
    }

    public static Result discover(Path root) throws IOException {
        var models = new ArrayList<Path>();
        var packDirectories = new ArrayList<Path>();
        var failures = new ArrayList<Failure>();

        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                if (attributes.isSymbolicLink() || attributes.isOther()) {
                    failures.add(new Failure(directory,
                            new IOException("Links and special directories are not allowed")));
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (!directory.equals(root) && isRawDirectory(directory)) {
                    models.add(directory);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                packDirectories.add(directory);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (attributes.isSymbolicLink() || attributes.isOther()) {
                    failures.add(new Failure(file,
                            new IOException("Links and special files are not allowed")));
                } else if (attributes.isRegularFile() && isArchive(file)) {
                    models.add(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException error) {
                failures.add(new Failure(file, error));
                return FileVisitResult.CONTINUE;
            }
        });

        Comparator<Path> byRelativePath = Comparator.comparing(
                path -> normalizedRelative(root, path));
        models.sort(byRelativePath);
        packDirectories.sort(byRelativePath);
        failures.sort(Comparator.comparing(failure -> normalizedRelative(root, failure.path())));
        return new Result(models, packDirectories, failures);
    }

    private static boolean isRawDirectory(Path directory) {
        return Files.isRegularFile(directory.resolve("ysm.json"), LinkOption.NOFOLLOW_LINKS)
                || Files.isRegularFile(directory.resolve("main.json"), LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean isArchive(Path file) {
        var name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return FILE_EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    private static ModelSourceKind sourceKind(Path source) throws IOException {
        if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            return ModelSourceKind.CURRENT_RAW_DIRECTORY;
        }
        var name = source.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".mxc")) {
            return ModelSourceKind.DIRECT_CONTAINER;
        }
        if (name.endsWith(".unitypackage")) {
            return ModelSourceKind.GENERIC_UNITY_PACKAGE;
        }
        if (GENERIC_EXTENSIONS.stream().anyMatch(name::endsWith)) {
            return ModelSourceKind.GENERIC_RAW_FILE;
        }
        if (name.endsWith(".ysm")) {
            try {
                if (hasCurrentContainerMagic(source)) {
                    return ModelSourceKind.DIRECT_CONTAINER;
                }
                return switch (LegacyYsmHeader.probe(source)) {
                    case V1_RAW, V2_RAW -> ModelSourceKind.CURRENT_RAW_ARCHIVE;
                    case V3_ENCRYPTED -> ModelSourceKind.LEGACY_ARCHIVE;
                    case UNSUPPORTED -> ModelSourceKind.UNSUPPORTED_YSM;
                };
            } catch (IOException invalid) {
                return ModelSourceKind.UNSUPPORTED_YSM;
            }
        }
        return ModelSourceKind.CURRENT_RAW_ARCHIVE;
    }

    private static boolean hasCurrentContainerMagic(Path source) throws IOException {
        try (var input = Files.newInputStream(source)) {
            return Arrays.equals(input.readNBytes(AssetContainerConstant.HEAD.length),
                    AssetContainerConstant.HEAD);
        }
    }

    private static Path requireInside(Path root, Path candidate) throws IOException {
        var real = candidate.toRealPath();
        if (!real.startsWith(root)) {
            throw new IOException("Model source escapes its root: " + candidate);
        }
        return real;
    }

    private static String normalizedRelative(Path root, Path path) {
        try {
            return root.relativize(path).toString().replace('\\', '/');
        } catch (IllegalArgumentException ignored) {
            return path.toString().replace('\\', '/');
        }
    }

    public record Result(List<Path> models, List<Path> packDirectories, List<Failure> failures) {
        public Result {
            models = List.copyOf(models);
            packDirectories = List.copyOf(packDirectories);
            failures = List.copyOf(failures);
        }
    }

    public record Failure(Path path, IOException error) {
    }
}
