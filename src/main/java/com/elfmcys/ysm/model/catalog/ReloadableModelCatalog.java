package com.elfmcys.ysm.model.catalog;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.model.catalog.builtin.BuiltinModelCatalog;
import com.elfmcys.ysm.model.catalog.content.CatalogContentBinding;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogCandidate;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogIndexEntry;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogIndexSnapshot;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogRecord;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogTransition;
import com.elfmcys.ysm.model.catalog.source.CatalogBuildException;
import com.elfmcys.ysm.model.catalog.source.CatalogModelLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.catalog.source.ModelCatalogSource;
import com.elfmcys.ysm.model.catalog.source.ModelDirectoryWatcher;
import com.elfmcys.ysm.model.catalog.source.ModelSourceResolver;
import com.elfmcys.ysm.model.catalog.source.ModelSourceKind;
import com.elfmcys.ysm.model.catalog.source.SourceObservation;
import com.elfmcys.ysm.model.catalog.source.SourceChangeSet;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelPackDescriptor;
import com.elfmcys.ysm.model.domain.ModelScanError;
import com.elfmcys.ysm.model.domain.ModelScanReport;
import com.elfmcys.ysm.model.domain.ModelScanWarning;
import com.elfmcys.ysm.model.storage.ConvertedCacheCoordinator;
import com.elfmcys.ysm.model.storage.ConvertedSourceIndex;
import com.elfmcys.ysm.model.storage.ModelStorageInfra;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** Process catalog authority with one incremental, owner-tick-committed scan at a time. */
public final class ReloadableModelCatalog implements AutoCloseable {
    private static final int MAX_INPUTS = 100_000;
    private static final int CACHE_ADMISSION = 16;

    /** Unreachable converted root of a composition that owns no converted storage. */
    private static final Path DETACHED_CONVERTED_ROOT = Path.of(".").toAbsolutePath().normalize()
            .resolve("never-converted");

    private final List<ModelCatalogSource> roots;
    private final CatalogReconciler reconciler;
    private final Path convertedRoot;
    private final Pruner pruner;
    private final LastUsedModelStore lastUsedStore;
    private final CopyOnWriteArrayList<Consumer<CatalogTransition>> listeners =
            new CopyOnWriteArrayList<>();
    private final AtomicBoolean watcherReloadRequested = new AtomicBoolean();

    private volatile CatalogCandidate current;
    private volatile Supplier<CatalogLoadingPolicy> loadingPolicy = () -> CatalogLoadingPolicy.DEFAULT;
    private volatile CatalogScanProgress progress = CatalogScanProgress.IDLE;
    private ModelDirectoryWatcher watcher;
    private ActiveScan active;
    private boolean scanningEnabled;
    private boolean firstScan = true;
    private boolean closed;
    private long scanSequence;
    private volatile LastUsedModelStore.Preference lastUsed = LastUsedModelStore.Preference.EMPTY;
    private final Set<Path> pendingWatcherPaths = new LinkedHashSet<>();
    private boolean pendingWatcherOverflow;
    private volatile CatalogReconciler.ScanDiscovery lastDiscovery;

    public ReloadableModelCatalog(ModelStorageInfra storage,
                                  List<ModelCatalogSource> roots,
                                  BuiltinModelCatalog builtins) {
        Objects.requireNonNull(storage, "storage");
        Objects.requireNonNull(builtins, "builtins");
        this.roots = List.copyOf(roots);
        reconciler = new CatalogReconciler(this.roots,
                new ModelSourceResolver(new RawModelImporter(builtins.contract()),
                        storage.indexes(), storage.objects()),
                builtins.contract(), builtins.defaultContent());
        try {
            current = reconciler.materializeBuiltins();
        } catch (CatalogBuildException failure) {
            throw new UncheckedIOException(
                    "Failed to materialize the builtin catalog", failure);
        }
        convertedRoot = storage.convertedRoot().toAbsolutePath().normalize();
        pruner = storage.convertedConsumers()::pruneOnce;
        lastUsedStore = new LastUsedModelStore(storage.gameCacheRoot().getParent()
                .resolve("last-used-model.properties"));
        lastUsed = lastUsedStore.read();
    }

    ReloadableModelCatalog(CatalogReconciler reconciler, CatalogCandidate initial) {
        roots = List.of();
        this.reconciler = Objects.requireNonNull(reconciler, "reconciler");
        current = Objects.requireNonNull(initial, "initial");
        convertedRoot = DETACHED_CONVERTED_ROOT;
        pruner = ignored -> ConvertedCacheCoordinator.PruneResult.SKIPPED_ACTIVE_CONSUMER;
        lastUsedStore = null;
    }

    public CatalogSnapshot current() {
        return current.snapshot();
    }

    public void configureLoading(Supplier<CatalogLoadingPolicy> policy) {
        loadingPolicy = Objects.requireNonNull(policy, "policy");
    }

    public CatalogScanProgress progress() {
        return progress;
    }

    public CatalogIndexSnapshot index() {
        return current.index();
    }

    public CatalogCandidate currentCandidate() {
        return current;
    }

    /** Records a local selection without making persistence part of the model-load critical path. */
    public void rememberLastUsed(String path, Hash256 modelId) {
        if (lastUsedStore == null) return;
        var next = new LastUsedModelStore.Preference(path.replace('\\', '/'), modelId);
        lastUsed = next;
        lastUsedStore.write(path, modelId);
    }

    /** Enables watcher/scan only after converted consumer registration has completed. */
    public CompletableFuture<ReloadResult> startScanning() {
        synchronized (this) {
            if (closed) {
                return completedFailure("Catalog owner is closed");
            }
            if (!scanningEnabled) {
                scanningEnabled = true;
            }
        }
        return reload();
    }

    /** Explicit authoring changes use the same directory-scoped discovery as filesystem watcher events. */
    public synchronized void sourceEdited(java.nio.file.Path path) {
        pendingWatcherPaths.add(path.toAbsolutePath().normalize());watcherReloadRequested.set(true);
    }
    public CompletableFuture<ReloadResult> reload() {
        final ActiveScan scan;
        synchronized (this) {
            if (closed) {
                return completedFailure("Catalog owner is closed");
            }
            if (!scanningEnabled) {
                return completedFailure("Catalog scanning has not been activated");
            }
            if (active != null) {
                return CompletableFuture.completedFuture(result(
                        ReloadStatus.BUSY, "Catalog scan is already in progress"));
            }
            var changes = new SourceChangeSet(new LinkedHashSet<>(pendingWatcherPaths),
                    pendingWatcherOverflow);
            pendingWatcherPaths.clear();
            pendingWatcherOverflow = false;
            scan = new ActiveScan(++scanSequence, current, firstScan, changes,
                    lastDiscovery);
            firstScan = false;
            active = scan;
            progress = new CatalogScanProgress(CatalogScanProgress.Stage.DISCOVERING,
                    0, 0, 0, 0, 0);
        }
        YesSteveModel.LOGGER.info("Starting model catalog scan {}", scan.sequence);
        scan.start();
        return scan.completion;
    }

    public Subscription subscribe(Consumer<CatalogTransition> listener) {
        Objects.requireNonNull(listener, "listener");
        final CatalogCandidate value;
        synchronized (this) {
            if (closed) {
                throw new IllegalStateException("Catalog owner is closed");
            }
            listeners.add(listener);
            value = current;
        }
        var empty = CatalogCandidate.empty();
        notifyObserver(listener, new CatalogTransition(
                empty.index(), empty.snapshot(), value.index(), value.snapshot()));
        return new Subscription(listener);
    }

    public void tick() {
        synchronized (this) {
            // A watcher hint may describe files absent from the active scan's inventory.
            // Keep it until a new scan can be admitted instead of consuming it on BUSY.
            if (!closed && scanningEnabled && active == null
                    && watcherReloadRequested.getAndSet(false)) {
                reload();
            }
        }
        final ActiveScan scan;
        synchronized (this) {
            scan = active;
        }
        if (scan != null) {
            scan.tick();
        }
    }

    public synchronized boolean busy() {
        return active != null;
    }

    void watcherChanged(SourceChangeSet changes) {
        synchronized (this) {
            pendingWatcherPaths.addAll(changes.paths());
            pendingWatcherOverflow |= changes.overflow();
        }
        watcherReloadRequested.set(true);
    }

    private void publish(CatalogCandidate candidate) {
        final CatalogTransition transition;
        synchronized (this) {
            if (closed) {
                return;
            }
            var previous = current;
            current = candidate;
            transition = new CatalogTransition(previous.index(), previous.snapshot(),
                    candidate.index(), candidate.snapshot());
        }
        listeners.forEach(listener -> notifyObserver(listener, transition));
    }

    private void finish(ActiveScan scan, ReloadStatus status, String message) {
        synchronized (this) {
            if (closed || active != scan) {
                return;
            }
            active = null;
            progress = new CatalogScanProgress(status == ReloadStatus.COMMITTED
                    ? CatalogScanProgress.Stage.COMPLETE : CatalogScanProgress.Stage.FAILED,
                    Math.max(0, scan.expectedOutcomes), scan.disposedOutcomes,
                    0, 0, current.snapshot().report().errorCount());
        }
        var result = result(status, message);
        YesSteveModel.LOGGER.info(
                "Finished model catalog scan {} status={} models={} errors={} elapsedMs={} message={}",
                scan.sequence, status, result.modelCount(), result.errorCount(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - scan.startedAtNanos), message);
        // Keep every rejected source actionable in the normal client log. The
        // aggregate count alone made conversion and dependency failures look
        // like a successful scan with a mysterious missing model.
        current.snapshot().report().errors().forEach(error -> YesSteveModel.LOGGER.error(
                "Model catalog source failed root={} source={} category={} code={} message={} detail=\n{}",
                error.rootKind(), error.source(), error.category(), error.code(),
                error.message(), error.detail()));
        scan.completion.complete(result);
    }

    private CompletableFuture<ReloadResult> completedFailure(String message) {
        return CompletableFuture.completedFuture(result(ReloadStatus.FAILED, message));
    }

    private ReloadResult result(ReloadStatus status, String message) {
        var snapshot = current.snapshot();
        return new ReloadResult(status, snapshot.byModelId().size(),
                snapshot.report().errorCount(), message, snapshot.report().warnings());
    }

    @Override
    public void close() {
        final ActiveScan scan;
        final ModelDirectoryWatcher currentWatcher;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            progress = CatalogScanProgress.IDLE;
            scan = active;
            active = null;
            currentWatcher = watcher;
            watcher = null;
            listeners.clear();
            current = CatalogCandidate.empty();
            watcherReloadRequested.set(false);
        }
        if (scan != null) {
            scan.cancelAndAwait();
            scan.completion.complete(new ReloadResult(
                    ReloadStatus.FAILED, 0, 0, "Catalog owner is closed"));
        }
        if (currentWatcher != null) {
            currentWatcher.close();
        }
    }

    private static void notifyObserver(Consumer<CatalogTransition> observer,
                                       CatalogTransition transition) {
        try {
            observer.accept(transition);
        } catch (Throwable failure) {
            YesSteveModel.LOGGER.error("Catalog observer failed after publication", failure);
        }
    }

    private final class ActiveScan {
        private final long sequence;
        private final long startedAtNanos = System.nanoTime();
        private final CatalogCandidate previous;
        private final boolean pruneOpportunity;
        private final SourceChangeSet changes;
        private final CatalogReconciler.ScanDiscovery previousDiscovery;
        private final CompletableFuture<ReloadResult> completion = new CompletableFuture<>();
        private final ExecutorService executor;
        private final ExecutorService cacheExecutor;
        private final int workerCount;
        private final boolean createWatcher;
        private volatile ModelDirectoryWatcher createdWatcher;
        private int submittedOutcomes;
        private int nextCacheSource;
        private int nextPack;
        private int cacheInFlight;
        private int coldInFlight;
        private List<SourceObservation> cacheSources = List.of();
        private final ArrayDeque<SourceObservation> coldSources = new ArrayDeque<>();
        private final ConcurrentLinkedQueue<SourceObservation> cacheMisses = new ConcurrentLinkedQueue<>();
        private final List<ConvertedSourceIndex> convertedIndexes = new ArrayList<>();
        private final Set<Path> retainedConverted = new LinkedHashSet<>();
        private final ConcurrentLinkedQueue<ScanOutcome> outcomes =
                new ConcurrentLinkedQueue<>();
        private final AtomicInteger completedOutcomes = new AtomicInteger();
        private final Map<CatalogModelLocation, CatalogRecord> effectiveRecords =
                new LinkedHashMap<>();
        private final Map<CatalogModelLocation, CatalogIndexEntry> effectiveIndex =
                new LinkedHashMap<>();
        private final Map<PackKey, ModelPackDescriptor> effectivePacks =
                new LinkedHashMap<>();
        private final List<ModelScanError> errors = new ArrayList<>();
        private final List<ModelScanWarning> warnings = new ArrayList<>();
        private final List<CatalogModelLocation> acceptedOrder = new ArrayList<>();
        private final Set<CatalogModelLocation> acceptedLocations =
                new HashSet<>();
        private final Set<Hash256> acceptedModelIds = new HashSet<>();
        private final Set<String> acceptedPaths = new HashSet<>();

        private volatile CatalogReconciler.ScanDiscovery discovery;
        private volatile Throwable discoveryFailure;
        private volatile FinalOutcome finalOutcome;
        private volatile int expectedOutcomes = -1;
        private volatile String phase = "inventory";
        private volatile String currentPath = "";
        private int disposedOutcomes;
        private int publishedOutcomes = -1;
        private boolean discoveryApplied;
        private boolean finalizerScheduled;

        private ActiveScan(long sequence, CatalogCandidate previous,
                           boolean pruneOpportunity, SourceChangeSet changes,
                           CatalogReconciler.ScanDiscovery previousDiscovery) {
            this.sequence = sequence;
            this.previous = previous;
            this.pruneOpportunity = pruneOpportunity;
            this.changes = changes;
            this.previousDiscovery = previousDiscovery;
            createWatcher = watcher == null && !roots.isEmpty();
            workerCount = loadingPolicy.get().workers();
            executor = Executors.newFixedThreadPool(workerCount, runnable -> {
                var thread = new Thread(runnable, "YSM Catalog Scan " + sequence);
                thread.setDaemon(true);
                thread.setPriority(Math.max(Thread.MIN_PRIORITY,
                        Thread.NORM_PRIORITY - 1));
                return thread;
            });
            var cacheWorkers = Math.max(1, Math.min(workerCount, 4));
            cacheExecutor = Executors.newFixedThreadPool(cacheWorkers, runnable -> {
                var thread = new Thread(runnable, "YSM Catalog Cache " + sequence);
                thread.setDaemon(true);
                thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
                return thread;
            });
        }

        private void start() {
            executor.execute(this::discover);
        }

        private void discover() {
            try {
                phase = "inventory";
                currentPath = roots.stream().map(root -> root.path().toString())
                        .collect(Collectors.joining(", "));
                // Recursive watch registration performs directory I/O too; keep it
                // in discovery instead of freezing the joining client's owner tick.
                if (createWatcher) {
                    createdWatcher = new ModelDirectoryWatcher(roots,
                            ReloadableModelCatalog.this::watcherChanged);
                }
                var value = reconciler.discoverIncremental(previousDiscovery, changes);
                var count = Math.addExact(value.sources().size(), value.packs().size());
                if (count > MAX_INPUTS) {
                    throw new IllegalStateException("Catalog scan input capacity exceeded");
                }
                expectedOutcomes = count;
                discovery = value;
                lastDiscovery = value;
            } catch (Throwable failure) {
                fail(failure);
            }
        }

        private void resolve(Supplier<Object> operation) {
            try {
                outcomes.add(new ScanOutcome(operation.get(), false));
            } catch (Throwable failure) {
                fail(failure);
            } finally {
                completedOutcomes.incrementAndGet();
            }
        }

        private void probeCache(SourceObservation source) {
            try {
                phase = "cache-check";
                currentPath = source.key().relativePath().value();
                var cached = reconciler.resolveCachedIncremental(source);
                if (cached.isPresent()) {
                    outcomes.add(new ScanOutcome(cached.get(), true));
                    completedOutcomes.incrementAndGet();
                } else {
                    cacheMisses.add(source);
                }
            } catch (Throwable failure) {
                fail(failure);
            }
        }

        private synchronized void fail(Throwable failure) {
            if (discoveryFailure == null) {
                discoveryFailure = failure;
                executor.shutdownNow();
                cacheExecutor.shutdownNow();
            }
        }

        private void tick() {
            var limits = loadingPolicy.get();
            var batchStarted = System.nanoTime();
            adoptWatcher();
            if (discoveryFailure != null) {
                if (executor.isTerminated() && cacheExecutor.isTerminated()) {
                    // Discovery may have published the watcher after the first check.
                    adoptWatcher();
                    disposePendingOutcomes();
                    finish(this, ReloadStatus.FAILED, message(discoveryFailure));
                }
                return;
            }
            var discovered = discovery;
            if (discovered == null) {
                return;
            }
            if (!discoveryApplied) {
                applyDiscovery(discovered);
                cacheSources = discovered.sources().stream()
                        .filter(source -> source.key().sourceKind() == ModelSourceKind.LEGACY_ARCHIVE
                                || source.key().sourceKind() == ModelSourceKind.GENERIC_RAW_FILE
                                || source.key().sourceKind() == ModelSourceKind.GENERIC_UNITY_PACKAGE)
                        .sorted(lastUsedComparator())
                        .toList();
                discovered.sources().stream()
                        .filter(source -> source.key().sourceKind() != ModelSourceKind.LEGACY_ARCHIVE)
                        .filter(source -> source.key().sourceKind() != ModelSourceKind.GENERIC_RAW_FILE
                                && source.key().sourceKind() != ModelSourceKind.GENERIC_UNITY_PACKAGE)
                        .forEach(coldSources::addLast);
                discovered.sources().stream()
                        .filter(source -> source.key().sourceKind() == ModelSourceKind.GENERIC_RAW_FILE
                                || source.key().sourceKind() == ModelSourceKind.GENERIC_UNITY_PACKAGE)
                        .filter(source -> !cacheSources.contains(source))
                        .sorted(lastUsedComparator())
                        .forEach(coldSources::addLast);
                // Legacy cache misses are appended below; generic cache misses follow the same path.
                discoveryApplied = true;
                if (!errors.isEmpty()) {
                    publishProjection(discovered.startedAt());
                }
                publishedOutcomes = 0;
            }
            for (var disposed = 0; disposed < limits.publicationsPerTick(); disposed++) {
                if (disposed > 0 && System.nanoTime() - batchStarted
                        >= TimeUnit.MILLISECONDS.toNanos(limits.publicationMillis())) break;
                var completed = outcomes.poll();
                if (completed == null) {
                    break;
                }
                if (completed.cached()) cacheInFlight--;
                else coldInFlight--;
                var outcome = completed.value();
                if (outcome instanceof CatalogReconciler.ResolvedSource source) {
                    applySource(source);
                } else if (outcome instanceof CatalogReconciler.ResolvedPack pack) {
                    applyPack(pack);
                } else {
                    fail(new IllegalStateException(
                            "Catalog worker returned an unsupported outcome"));
                    break;
                }
                disposedOutcomes++;
            }
            if (disposedOutcomes != publishedOutcomes) {
                publishProjection(discovered.startedAt());
                publishedOutcomes = disposedOutcomes;
            }
            // Misses retain only discovery metadata, never decoded source bytes.
            // Waiting for cold admission must not block probes of later warm sources.
            for (SourceObservation miss; (miss = cacheMisses.poll()) != null;) {
                cacheInFlight--;
                submittedOutcomes--;
                coldSources.addLast(miss);
            }
            while (nextCacheSource < cacheSources.size() && cacheInFlight < CACHE_ADMISSION
                    && discoveryFailure == null) {
                var source = cacheSources.get(nextCacheSource++);
                cacheInFlight++;
                submittedOutcomes++;
                try {
                    cacheExecutor.execute(() -> probeCache(source));
                } catch (java.util.concurrent.RejectedExecutionException failure) {
                    fail(failure);
                }
            }
            // Completed-but-unpublished content counts against its own lane's limit.
            var admissionLimit = workerCount + limits.queued();
            while ((!coldSources.isEmpty() || nextPack < discovered.packs().size())
                    && coldInFlight < admissionLimit && discoveryFailure == null) {
                submittedOutcomes++;
                coldInFlight++;
                try {
                    // Read pack manifests first.  Pack metadata is tiny and is
                    // needed by the browser; letting thousands of model sources
                    // ahead of it makes the progress UI appear stuck at N-1/N.
                    if (nextPack < discovered.packs().size()) {
                        var pack = discovered.packs().get(nextPack++);
                        phase = "read-pack";
                        currentPath = pack.key().hierarchy();
                        executor.execute(() -> resolve(() -> reconciler.resolveIncremental(pack)));
                    } else if (!coldSources.isEmpty()) {
                        var source = coldSources.removeFirst();
                        phase = switch (source.key().sourceKind()) {
                            case LEGACY_ARCHIVE -> "convert-legacy";
                            case GENERIC_RAW_FILE -> "convert-mesh";
                            case GENERIC_UNITY_PACKAGE -> "convert-unitypackage";
                            default -> "load-source";
                        };
                        currentPath = source.key().relativePath().value();
                        executor.execute(() -> resolve(() -> reconciler.resolveIncremental(source)));
                    }
                } catch (java.util.concurrent.RejectedExecutionException failure) {
                    fail(failure);
                }
            }
            progress = new CatalogScanProgress(disposedOutcomes == expectedOutcomes
                    ? CatalogScanProgress.Stage.FINALIZING : CatalogScanProgress.Stage.LOADING,
                    expectedOutcomes, disposedOutcomes, expectedOutcomes - submittedOutcomes,
                    submittedOutcomes - disposedOutcomes, errors.size(), phase, currentPath);
            if (expectedOutcomes >= 0
                    && completedOutcomes.get() == expectedOutcomes
                    && disposedOutcomes == expectedOutcomes
                    && !finalizerScheduled && discoveryFailure == null) {
                finalizerScheduled = true;
                cacheExecutor.shutdown();
                executor.execute(() -> finalizeScan(discovered));
            }
            var terminal = finalOutcome;
            if (terminal != null && executor.isTerminated() && cacheExecutor.isTerminated()) {
                if (terminal.failure != null) {
                    errors.add(ModelScanError.infrastructure(
                            CatalogRootKind.CUSTOM, convertedRoot.toString(),
                            "CATALOG_FINALIZATION_FAILED",
                            terminal.failure instanceof IOException io
                                    ? io : new IOException(terminal.failure)));
                    publishProjection(discovered.startedAt());
                    finish(this, ReloadStatus.FAILED, message(terminal.failure));
                } else {
                    if (applyConfirmedDeletions(discovered)) {
                        publishProjection(discovered.startedAt());
                    }
                    if (terminal.prune == ConvertedCacheCoordinator.PruneResult.FAILED) {
                        errors.add(ModelScanError.infrastructure(
                                CatalogRootKind.CUSTOM, convertedRoot.toString(),
                                "CONVERTED_PRUNE_FAILED",
                                new IOException("Converted prune failed")));
                        publishProjection(discovered.startedAt());
                    }
                    finish(this, ReloadStatus.COMMITTED, "");
                }
            }
        }

        private Comparator<SourceObservation> lastUsedComparator() {
            return Comparator.comparing((SourceObservation source) ->
                    lastUsed.matches(source.key().relativePath().value()) ? 0 : 1);
        }

        private void adoptWatcher() {
            synchronized (ReloadableModelCatalog.this) {
                if (!closed && createdWatcher != null) {
                    watcher = createdWatcher;
                    createdWatcher = null;
                }
            }
        }

        private void applyDiscovery(CatalogReconciler.ScanDiscovery value) {
            previous.snapshot().byModelId().values().forEach(record ->
                    effectiveRecords.put(record.location(), record));
            previous.index().entries().forEach(entry ->
                    effectiveIndex.put(entry.location(), entry));
            previous.snapshot().packs().forEach(pack ->
                    effectivePacks.put(new PackKey(pack.rootKind(), pack.hierarchy()), pack));
            errors.addAll(value.errors());
        }

        private void applySource(CatalogReconciler.ResolvedSource outcome) {
            if (outcome.error() != null) {
                errors.add(outcome.error());
                return;
            }
            var entry = outcome.entry();
            var location = entry.location();
            retainConverted(entry.backingFile());
            var path = location.path().value();
            if (acceptedModelIds.contains(entry.modelId())
                    || acceptedPaths.contains(path)
                    || conflictsWithIntrinsicDefault(entry)) {
                errors.add(ModelScanError.from(location.rootKind(), path,
                        new IllegalArgumentException(
                                "Catalog source conflicts with an accepted model")));
                outcome.content().representation().close();
                return;
            }
            effectiveIndex.put(location, entry);
            outcome.convertedIndex().ifPresent(convertedIndexes::add);
            warnings.addAll(outcome.warnings());
            effectiveRecords.remove(location);
            acceptedModelIds.add(entry.modelId());
            acceptedPaths.add(path);
            acceptedLocations.add(location);
            effectiveRecords.put(location, new CatalogRecord(location,
                    new CatalogContentBinding(entry.modelId(), outcome.content())));
            acceptedOrder.add(location);
        }

        private void applyPack(CatalogReconciler.ResolvedPack outcome) {
            if (outcome.error() != null) {
                errors.add(outcome.error());
                return;
            }
            outcome.pack().ifPresent(pack -> effectivePacks.put(
                    new PackKey(pack.rootKind(), pack.hierarchy()), pack));
        }

        private void publishProjection(Instant startedAt) {
            var selected = selectRecords(
                    effectiveRecords, acceptedOrder, acceptedLocations);
            var packs = effectivePacks.values().stream().sorted().toList();
            var report = new ModelScanReport(startedAt, Instant.now(),
                    concat(errors, selected.conflicts), warnings);
            var selectedIdentities = selected.records.values().stream()
                    .collect(Collectors.toUnmodifiableMap(
                            CatalogRecord::location,
                            record -> record.binding().content()
                                    .representation().identity()));
            var indexEntries = effectiveIndex.values().stream()
                    .filter(entry -> entry.identity().equals(
                            selectedIdentities.get(entry.location())))
                    .sorted(Comparator
                            .comparingInt((CatalogIndexEntry entry) ->
                                    rootPriority(entry.location().rootKind()))
                            .thenComparing(entry -> entry.location().path().value()))
                    .toList();
            var candidate = new CatalogCandidate(
                    new CatalogIndexSnapshot(indexEntries, packs, report),
                    new CatalogSnapshot(selected.records, packs, report));
            publish(candidate);
            if (pruneOpportunity) {
                candidate.index().entries().stream()
                        .map(CatalogIndexEntry::backingFile)
                        .forEach(this::retainConverted);
            }
        }

        private boolean conflictsWithIntrinsicDefault(CatalogIndexEntry entry) {
            return effectiveRecords.values().stream()
                    .filter(record -> isIntrinsicDefault(record.location()))
                    .anyMatch(record -> record.binding().modelId().equals(entry.modelId())
                            || record.location().path().value().equals(
                            entry.location().path().value()));
        }

        private void retainConverted(Path path) {
            if (!pruneOpportunity) {
                return;
            }
            var checked = path.toAbsolutePath().normalize();
            if (checked.startsWith(convertedRoot)) {
                retainedConverted.add(checked);
            }
        }

        private boolean applyConfirmedDeletions(
                CatalogReconciler.ScanDiscovery value) {
            var observed = value.sources().stream()
                    .map(source -> new CatalogModelLocation(
                            source.key().root().rootKind(),
                            source.key().relativePath()))
                    .collect(Collectors.toUnmodifiableSet());
            var observedPacks = value.packs().stream()
                    .map(pack -> new PackKey(
                            pack.key().root().rootKind(), pack.key().hierarchy()))
                    .collect(Collectors.toUnmodifiableSet());
            var completeRoots = value.completeRoots().keySet();
            var changed = effectiveRecords.entrySet().removeIf(entry ->
                    completeRoots.contains(entry.getKey().rootKind())
                            && !isIntrinsicDefault(entry.getKey())
                            && !observed.contains(entry.getKey()));
            changed |= effectiveIndex.entrySet().removeIf(entry ->
                    completeRoots.contains(entry.getKey().rootKind())
                            && !observed.contains(entry.getKey()));
            changed |= effectivePacks.entrySet().removeIf(entry ->
                    completeRoots.contains(entry.getKey().root())
                            && !observedPacks.contains(entry.getKey()));
            return changed;
        }

        private void finalizeScan(CatalogReconciler.ScanDiscovery discovered) {
            ConvertedCacheCoordinator.PruneResult prune = null;
            Throwable failure = null;
            try {
                reconciler.verifyDiscovery(discovered, changes,
                        previousDiscovery != null && !changes.paths().isEmpty());
                var scopes = discovered.completeRoots().keySet().stream()
                        .map(CatalogRootKind::namespace)
                        .collect(Collectors.toUnmodifiableSet());
                reconciler.replaceIncrementalIndex(scopes, convertedIndexes);
                if (pruneOpportunity) {
                    prune = pruner.prune(retainedConverted);
                }
            } catch (Throwable error) {
                failure = error;
            } finally {
                finalOutcome = new FinalOutcome(prune, failure);
                executor.shutdown();
            }
        }

        private void cancelAndAwait() {
            executor.shutdownNow();
            cacheExecutor.shutdownNow();
            var interrupted = false;
            while (!executor.isTerminated() || !cacheExecutor.isTerminated()) {
                try {
                    executor.awaitTermination(1, TimeUnit.DAYS);
                    cacheExecutor.awaitTermination(1, TimeUnit.DAYS);
                } catch (InterruptedException ignored) {
                    interrupted = true;
                    executor.shutdownNow();
                    cacheExecutor.shutdownNow();
                }
            }
            disposePendingOutcomes();
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        private void disposePendingOutcomes() {
            var abandonedWatcher = createdWatcher;
            createdWatcher = null;
            if (abandonedWatcher != null) abandonedWatcher.close();
            cacheMisses.clear();
            for (ScanOutcome outcome; (outcome = outcomes.poll()) != null;) {
                if (outcome.value() instanceof CatalogReconciler.ResolvedSource source
                        && source.error() == null) {
                    try {
                        source.content().representation().close();
                    } catch (Throwable failure) {
                        YesSteveModel.LOGGER.error(
                                "Failed to dispose unpublished catalog content", failure);
                    }
                }
            }
        }
    }

    private static Selection selectRecords(
            Map<CatalogModelLocation, CatalogRecord> source,
            List<CatalogModelLocation> acceptedOrder,
            Set<CatalogModelLocation> acceptedLocations) {
        var baseline = new ArrayList<>(source.values());
        baseline.sort(Comparator
                .comparingInt((CatalogRecord record) ->
                        rootPriority(record.location().rootKind()))
                .thenComparing(record -> record.location().path().value()));
        var ordered = new ArrayList<CatalogRecord>(baseline.size());
        baseline.stream().filter(record -> isIntrinsicDefault(record.location()))
                .forEach(ordered::add);
        acceptedOrder.stream().map(source::get).filter(Objects::nonNull)
                .forEach(ordered::add);
        baseline.stream().filter(record -> !isIntrinsicDefault(record.location())
                        && !acceptedLocations.contains(record.location()))
                .forEach(ordered::add);
        var records = new LinkedHashMap<Hash256, CatalogRecord>();
        var paths = new LinkedHashMap<String, CatalogRecord>();
        var conflicts = new ArrayList<ModelScanError>();
        for (var record : ordered) {
            var path = record.location().path().value();
            if (paths.containsKey(path) || records.containsKey(record.binding().modelId())) {
                conflicts.add(ModelScanError.from(record.location().rootKind(), path,
                        new IllegalArgumentException(
                                "Catalog source conflicts with a selected model")));
                continue;
            }
            paths.put(path, record);
            records.put(record.binding().modelId(), record);
        }
        return new Selection(records, conflicts);
    }

    private static int rootPriority(CatalogRootKind root) {
        return switch (root) {
            case BUILTIN -> 0;
            case AUTH -> 1;
            case CUSTOM -> 2;
        };
    }

    private static boolean isIntrinsicDefault(CatalogModelLocation location) {
        return location.rootKind() == CatalogRootKind.BUILTIN
                && location.path().value().equals("default");
    }

    private static List<ModelScanError> concat(
            List<ModelScanError> errors, List<ModelScanError> conflicts) {
        var result = new ArrayList<ModelScanError>(errors.size() + conflicts.size());
        result.addAll(errors);
        result.addAll(conflicts);
        return result;
    }

    private static String message(Throwable failure) {
        return Objects.requireNonNullElse(
                failure.getMessage(), failure.getClass().getSimpleName());
    }

    private record Selection(Map<Hash256, CatalogRecord> records,
                             List<ModelScanError> conflicts) {
    }

    private record PackKey(CatalogRootKind root, String hierarchy) {
    }

    private record ScanOutcome(Object value, boolean cached) {}

    private record FinalOutcome(ConvertedCacheCoordinator.PruneResult prune,
                                Throwable failure) {
    }

    @FunctionalInterface
    private interface Pruner {
        ConvertedCacheCoordinator.PruneResult prune(Set<Path> retained);
    }

    public final class Subscription implements AutoCloseable {
        private final Consumer<CatalogTransition> listener;
        private boolean subscriptionClosed;

        private Subscription(Consumer<CatalogTransition> listener) {
            this.listener = listener;
        }

        @Override
        public void close() {
            synchronized (ReloadableModelCatalog.this) {
                if (!subscriptionClosed) {
                    subscriptionClosed = true;
                    listeners.remove(listener);
                }
            }
        }
    }
}
