package com.elfmcys.ysm.model.catalog;

import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.parser.RawModelDiagnostic;
import com.elfmcys.ysm.model.catalog.builtin.BuiltinModelIndex;
import com.elfmcys.ysm.model.catalog.content.CatalogContentBinding;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogCandidate;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogIndexEntry;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogIndexSnapshot;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogRecord;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot;
import com.elfmcys.ysm.model.catalog.source.CatalogBuildException;
import com.elfmcys.ysm.model.catalog.source.CatalogInfrastructureException;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.catalog.source.ModelCatalogSource;
import com.elfmcys.ysm.model.catalog.source.ModelPackScanner;
import com.elfmcys.ysm.model.catalog.source.ModelSourceDiscovery;
import com.elfmcys.ysm.model.catalog.source.ModelSourceException;
import com.elfmcys.ysm.model.catalog.source.ModelSourceResolver;
import com.elfmcys.ysm.model.catalog.source.PackObservation;
import com.elfmcys.ysm.model.catalog.source.RootInventoryState;
import com.elfmcys.ysm.model.catalog.source.SourceObservation;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelPackDescriptor;
import com.elfmcys.ysm.model.domain.ModelScanError;
import com.elfmcys.ysm.model.domain.ModelScanReport;
import com.elfmcys.ysm.model.domain.ModelScanWarning;
import com.elfmcys.ysm.model.storage.ConvertedSourceIndex;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.model.storage.ModelHashMismatchException;
import java.io.IOException;
import java.nio.file.FileSystemException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Builds one complete immutable identity/content pair without publishing it. */
public final class CatalogReconciler {
    private final List<ModelCatalogSource> roots;
    private final ModelSourceResolver resolver;
    private final ModelPackScanner packScanner = new ModelPackScanner();
    private final Set<Hash256> builtinReservedIds;
    private final Map<Hash256, CatalogRecord> fixedRecords;
    private final CatalogIndexSnapshot builtinIndex;
    private final BuiltinModelIndex builtinContract;

    public CatalogReconciler(List<ModelCatalogSource> roots, ModelSourceResolver resolver,
                             Set<Hash256> builtinReservedIds,
                             ManagedContainer intrinsicDefault,
                             CatalogIndexSnapshot builtinIndex) {
        this(roots, resolver, builtinReservedIds, intrinsicDefault, builtinIndex, null);
    }

    public CatalogReconciler(List<ModelCatalogSource> roots,
                             ModelSourceResolver resolver,
                             BuiltinModelIndex builtinContract,
                             ManagedContainer intrinsicDefault) {
        this(roots, resolver, builtinContract.entries().stream()
                        .map(BuiltinModelIndex.Entry::modelHash)
                        .collect(Collectors.toUnmodifiableSet()),
                intrinsicDefault, CatalogIndexSnapshot.empty(), builtinContract);
    }

    private CatalogReconciler(List<ModelCatalogSource> roots,
                              ModelSourceResolver resolver,
                              Set<Hash256> builtinReservedIds,
                              ManagedContainer intrinsicDefault,
                              CatalogIndexSnapshot builtinIndex,
                              BuiltinModelIndex builtinContract) {
        this.roots = List.copyOf(roots);
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.builtinReservedIds = Set.copyOf(builtinReservedIds);
        Objects.requireNonNull(intrinsicDefault, "intrinsicDefault");
        this.builtinIndex = Objects.requireNonNull(builtinIndex, "builtinIndex");
        this.builtinContract = builtinContract;
        fixedRecords = Map.of(intrinsicDefault.modelId(),
                new CatalogRecord(intrinsicDefault.location(),
                        new CatalogContentBinding(
                                intrinsicDefault.modelId(), intrinsicDefault)));
    }

    ScanDiscovery discoverIncremental() {
        var startedAt = Instant.now();
        var complete = new LinkedHashMap<CatalogRootKind, RootInventoryState.Complete>();
        var errors = new ArrayList<ModelScanError>();
        for (var root : roots) {
            var inventory = ModelSourceDiscovery.inventory(root);
            if (inventory instanceof RootInventoryState.Incomplete incomplete) {
                errors.add(incomplete.error());
                continue;
            }
            var value = (RootInventoryState.Complete) inventory;
            if (root.rootKind() == CatalogRootKind.BUILTIN && builtinContract != null) {
                try {
                    builtinContract.validateCoverage(value.sources().values().stream()
                            .map(source -> source.key().relativePath()).toList());
                } catch (IOException | RuntimeException failure) {
                    errors.add(ModelScanError.infrastructure(root.rootKind(),
                            root.path().toString(), "BUILTIN_INVENTORY_MISMATCH", failure));
                    continue;
                }
            }
            complete.put(root.rootKind(), value);
        }
        var observations = complete.values().stream()
                .flatMap(root -> root.sources().values().stream())
                .filter(source -> !(source.key().root().rootKind()
                        == CatalogRootKind.BUILTIN
                        && source.key().relativePath().value().equals("default")))
                .sorted(Comparator
                        .comparingInt((SourceObservation source) ->
                                rootPriority(source.key().root().rootKind()))
                        .thenComparing(source -> source.key().relativePath().value()))
                .toList();
        var packs = complete.values().stream()
                .flatMap(root -> root.packs().values().stream())
                .sorted(Comparator
                        .comparingInt((PackObservation pack) ->
                                rootPriority(pack.key().root().rootKind()))
                        .thenComparing(pack -> pack.key().hierarchy()))
                .toList();
        return new ScanDiscovery(startedAt, complete, observations, packs, errors,
                complete.size() == roots.size());
    }

    ResolvedSource resolveIncremental(SourceObservation observation) {
        return resolveIncremental(observation, false);
    }

    /** Empty means cold work is required; cache failures retain ordinary scan diagnostics. */
    Optional<ResolvedSource> resolveCachedIncremental(SourceObservation observation) {
        return Optional.ofNullable(resolveIncremental(observation, true));
    }

    private ResolvedSource resolveIncremental(SourceObservation observation, boolean cacheOnly) {
        ModelSourceResolver.MaterializedResolution result = null;
        try {
            result = cacheOnly ? resolver.resolveCached(observation).orElse(null)
                    : resolver.resolveMaterialized(observation);
            if (result == null) return null;
            if (builtinContract != null
                    && observation.key().root().rootKind() == CatalogRootKind.BUILTIN) {
                var expected = builtinContract.require(observation.key().relativePath());
                if (!expected.equals(result.entry().modelId())) {
                    throw new ModelHashMismatchException(
                            result.entry().location(), expected, result.entry().modelId());
                }
            }
            var after = ModelSourceDiscovery.observe(
                    observation.key(), observation.absolutePath());
            if (!after.equals(observation)) {
                throw new IOException("Model source changed while loading: "
                        + observation.absolutePath());
            }
            var warnings = scanWarnings(observation, result.diagnostics());
            return new ResolvedSource(observation, result.entry(), result.content(),
                    result.convertedIndexEntry(), warnings, null);
        } catch (Throwable failure) {
            if (result != null) {
                result.content().representation().close();
            }
            if (failure instanceof Error fatal) {
                throw fatal;
            }
            return new ResolvedSource(observation, null, null,
                    Optional.empty(), List.of(),
                    ModelScanError.from(observation.key().root().rootKind(),
                            observation.key().relativePath().value(), failure));
        }
    }

    ResolvedPack resolveIncremental(PackObservation observation) {
        try {
            var root = new ModelCatalogSource(
                    observation.key().root().rootKind(),
                    observation.key().root().canonicalAbsoluteRoot(), false);
            return new ResolvedPack(observation,
                    packScanner.scan(root, observation.directory()), null);
        } catch (Throwable failure) {
            if (failure instanceof Error fatal) {
                throw fatal;
            }
            return new ResolvedPack(observation, Optional.empty(),
                    ModelScanError.from(observation.key().root().rootKind(),
                            observation.key().hierarchy(), failure));
        }
    }

    void verifyDiscovery(ScanDiscovery discovery) throws CatalogInfrastructureException {
        if (!discovery.allRootsComplete()) {
            throw new CatalogInfrastructureException("Catalog inventory is incomplete");
        }
        for (var root : roots) {
            var expected = discovery.completeRoots().get(root.rootKind());
            if (expected == null) {
                continue;
            }
            if (!expected.equals(ModelSourceDiscovery.inventory(root))) {
                throw new CatalogInfrastructureException(
                        "Model root changed while scanning: " + root.path());
            }
        }
    }

    void replaceIncrementalIndex(Set<String> rootNamespaces,
                                 Collection<ConvertedSourceIndex> entries)
            throws CatalogInfrastructureException {
        resolver.replaceIndex(rootNamespaces, entries);
    }

    Map<Hash256, CatalogRecord> fixedRecords() {
        return fixedRecords;
    }

    private static int rootPriority(CatalogRootKind root) {
        return switch (root) {
            case BUILTIN -> 0;
            case AUTH -> 1;
            case CUSTOM -> 2;
        };
    }

    record ScanDiscovery(Instant startedAt,
                         Map<CatalogRootKind, RootInventoryState.Complete> completeRoots,
                         List<SourceObservation> sources,
                         List<PackObservation> packs,
                         List<ModelScanError> errors,
                         boolean allRootsComplete) {
        ScanDiscovery {
            completeRoots = Map.copyOf(completeRoots);
            sources = List.copyOf(sources);
            packs = List.copyOf(packs);
            errors = List.copyOf(errors);
        }
    }

    record ResolvedSource(SourceObservation observation, CatalogIndexEntry entry,
                          ManagedContainer content,
                          Optional<ConvertedSourceIndex> convertedIndex,
                          List<ModelScanWarning> warnings, ModelScanError error) {
        ResolvedSource {
            Objects.requireNonNull(observation, "observation");
            convertedIndex = Objects.requireNonNull(convertedIndex, "convertedIndex");
            warnings = List.copyOf(warnings);
            if ((entry == null) == (error == null)
                    || (content == null) != (entry == null)) {
                throw new IllegalArgumentException(
                        "Resolved source must contain exactly one outcome");
            }
        }
    }

    record ResolvedPack(PackObservation observation,
                        Optional<ModelPackDescriptor> pack, ModelScanError error) {
        ResolvedPack {
            Objects.requireNonNull(observation, "observation");
            pack = Objects.requireNonNull(pack, "pack");
        }
    }

    /** Builds the side-local startup state without retaining optional content process-wide. */
    public CatalogCandidate materializeBuiltins() throws CatalogBuildException {
        return materialize(List.copyOf(builtinIndex.entries()),
                builtinIndex.packs(), new ArrayList<>(builtinIndex.report().errors()),
                new ArrayList<>(builtinIndex.report().warnings()), Instant.now());
    }

    public CatalogCandidate reconcile() throws CatalogBuildException {
        var startedAt = Instant.now();
        var initial = discoverComplete();
        var observations = initial.values().stream()
                .flatMap(root -> root.sources().values().stream()).toList();
        var packObservations = initial.values().stream()
                .flatMap(root -> root.packs().values().stream()).toList();

        var entries = new ArrayList<>(builtinIndex.entries());
        var convertedIndexes = new ArrayList<ConvertedSourceIndex>();
        var packs = new ArrayList<>(builtinIndex.packs());
        var errors = new ArrayList<>(builtinIndex.report().errors());
        var warnings = new ArrayList<>(builtinIndex.report().warnings());
        for (var observation : observations) {
            try {
                var result = resolver.resolve(observation);
                entries.add(result.entry());
                result.convertedIndexEntry().ifPresent(convertedIndexes::add);
                warnings.addAll(scanWarnings(observation, result.diagnostics()));
            } catch (ModelSourceException failure) {
                errors.add(ModelScanError.from(observation.key().root().rootKind(),
                        observation.key().relativePath().value(), failure));
            }
        }
        for (var observation : packObservations) {
            try {
                var root = new ModelCatalogSource(
                        observation.key().root().rootKind(),
                        observation.key().root().canonicalAbsoluteRoot(), false);
                packScanner.scan(root, observation.directory()).ifPresent(packs::add);
            } catch (Exception failure) {
                errors.add(ModelScanError.from(observation.key().root().rootKind(),
                        observation.key().hierarchy(), failure));
            }
        }

        verifyFinalInventory(initial);
        resolver.replaceIndex(roots.stream().map(root -> root.rootKind().namespace())
                .collect(Collectors.toUnmodifiableSet()), convertedIndexes);
        var sortedPacks = packs.stream().sorted().toList();
        return materialize(entries, sortedPacks, errors, warnings, startedAt);
    }

    private CatalogCandidate materialize(List<CatalogIndexEntry> entries,
                                         List<ModelPackDescriptor> packs,
                                         List<ModelScanError> errors,
                                         List<ModelScanWarning> warnings,
                                         Instant startedAt)
            throws CatalogInfrastructureException {
        var content = new ArrayList<ManagedContainer>();
        for (var entry : entries) {
            try {
                content.add(ManagedContainer.openIndexed(entry));
            } catch (AssetLoadException failure) {
                if (failure.reason() == AssetLoadException.Reason.ACCESS) {
                    throw new CatalogInfrastructureException(
                            "Failed to access indexed model container: "
                                    + entry.backingFile(), failure);
                }
                errors.add(ModelScanError.from(entry.location().rootKind(),
                        entry.location().path().value(), failure));
            } catch (FileSystemException | SecurityException failure) {
                throw new CatalogInfrastructureException(
                        "Failed to access indexed model container: " + entry.backingFile(),
                        failure);
            } catch (IOException | RuntimeException failure) {
                errors.add(ModelScanError.from(entry.location().rootKind(),
                        entry.location().path().value(), failure));
            }
        }

        var records = selectFirstValid(content, errors);
        var report = new ModelScanReport(startedAt, Instant.now(), errors, warnings);
        var index = new CatalogIndexSnapshot(entries, packs, report);
        var snapshot = new CatalogSnapshot(records, packs, report);
        return new CatalogCandidate(index, snapshot);
    }

    private Map<CatalogRootKind, RootInventoryState.Complete> discoverComplete()
            throws CatalogInfrastructureException {
        var result = new LinkedHashMap<CatalogRootKind, RootInventoryState.Complete>();
        for (var root : roots) {
            var inventory = ModelSourceDiscovery.inventory(root);
            if (inventory instanceof RootInventoryState.Incomplete incomplete) {
                throw new CatalogInfrastructureException(
                        "Model root inventory is incomplete: " + root.path(),
                        new IOException(incomplete.error().message()));
            }
            result.put(root.rootKind(), (RootInventoryState.Complete) inventory);
        }
        return result;
    }

    private static List<ModelScanWarning> scanWarnings(
            SourceObservation observation, List<RawModelDiagnostic> diagnostics) {
        var warnings = new ArrayList<ModelScanWarning>();
        for (var kind : RawModelDiagnostic.Kind.values()) {
            int occurrences = (int) diagnostics.stream()
                    .filter(diagnostic -> diagnostic.kind() == kind)
                    .count();
            if (occurrences == 0) {
                continue;
            }
            var warningKind = switch (kind) {
                case UNKNOWN_AUDIO -> ModelScanWarning.Kind.UNKNOWN_AUDIO;
                case INVALID_AUDIO -> ModelScanWarning.Kind.INVALID_AUDIO;
            };
            warnings.add(new ModelScanWarning(
                    observation.key().root().rootKind(),
                    observation.key().relativePath().value(), warningKind, occurrences));
        }
        return List.copyOf(warnings);
    }

    private void verifyFinalInventory(
            Map<CatalogRootKind, RootInventoryState.Complete> initial)
            throws CatalogInfrastructureException {
        for (var root : roots) {
            var finalState = ModelSourceDiscovery.inventory(root);
            if (!(finalState instanceof RootInventoryState.Complete complete)
                    || !complete.equals(initial.get(root.rootKind()))) {
                throw new CatalogInfrastructureException(
                        "Model root changed while building catalog: " + root.path());
            }
        }
    }

    private Map<Hash256, CatalogRecord> selectFirstValid(
            List<ManagedContainer> candidates, List<ModelScanError> errors) {
        var fixedPaths = fixedRecords.values().stream()
                .map(record -> record.location().path().value())
                .collect(Collectors.toUnmodifiableSet());
        var byPath = new LinkedHashMap<String, ManagedContainer>();
        for (var candidate : candidates) {
            var location = candidate.location();
            var path = location.path().value();
            if (location.rootKind() != CatalogRootKind.BUILTIN
                    && builtinReservedIds.contains(candidate.modelId())) {
                errors.add(ModelScanError.from(location.rootKind(), path,
                        new IllegalArgumentException(
                                "Model id is reserved by a builtin model: "
                                        + candidate.modelId())));
                continue;
            }
            if (fixedPaths.contains(path)) {
                errors.add(ModelScanError.from(location.rootKind(), path,
                        new IllegalArgumentException("Duplicate model path: " + path)));
                continue;
            }
            var existing = byPath.get(path);
            if (existing == null) {
                byPath.put(path, candidate);
            } else if (existing.location().rootKind() == CatalogRootKind.CUSTOM
                    && location.rootKind() == CatalogRootKind.AUTH) {
                byPath.put(path, candidate);
            } else if (!(existing.location().rootKind() == CatalogRootKind.AUTH
                    && location.rootKind() == CatalogRootKind.CUSTOM)) {
                errors.add(ModelScanError.from(location.rootKind(), path,
                        new IllegalArgumentException("Duplicate model path: " + path)));
            }
        }

        var result = new LinkedHashMap<>(fixedRecords);
        for (var candidate : byPath.values()) {
            result.putIfAbsent(candidate.modelId(), new CatalogRecord(candidate.location(),
                    new CatalogContentBinding(candidate.modelId(), candidate)));
        }
        return result;
    }
}
