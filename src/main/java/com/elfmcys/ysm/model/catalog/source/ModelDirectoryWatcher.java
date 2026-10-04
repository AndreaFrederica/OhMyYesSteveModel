package com.elfmcys.ysm.model.catalog.source;

import com.elfmcys.ysm.YesSteveModel;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Recursive watcher that emits hints only. Scanner publication remains the source of truth. */
public final class ModelDirectoryWatcher implements AutoCloseable {
    private final WatchService watchService;
    private final Consumer<SourceChangeSet> listener;
    private final Map<WatchKey, Path> directories = new ConcurrentHashMap<>();
    private final Set<Path> roots;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Thread thread;

    public ModelDirectoryWatcher(List<ModelCatalogSource> roots,
                                 Consumer<SourceChangeSet> listener) throws IOException {
        this.listener = listener;
        watchService = FileSystems.getDefault().newWatchService();
        var watchedRoots = roots.stream()
                .filter(root -> root.rootKind() != CatalogRootKind.BUILTIN)
                .toList();
        this.roots = watchedRoots.stream().map(ModelCatalogSource::path)
                .map(path -> path.toAbsolutePath().normalize())
                .collect(Collectors.toUnmodifiableSet());
        try {
            for (var root : watchedRoots) {
                if (root.createIfMissing()) {
                    Files.createDirectories(root.path());
                }
                if (Files.isDirectory(root.path())) {
                    registerRecursively(root.path());
                }
                var parent = root.path().toAbsolutePath().normalize().getParent();
                if (parent != null && Files.isDirectory(parent)) {
                    register(parent);
                }
            }
        } catch (IOException error) {
            try {
                watchService.close();
            } catch (IOException cleanupError) {
                error.addSuppressed(cleanupError);
            }
            throw error;
        }
        thread = new Thread(this::run, "YSM Model Directory Watcher");
        thread.setDaemon(true);
        thread.start();
    }

    private void run() {
        while (!closed.get()) {
            final WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            } catch (ClosedWatchServiceException ignored) {
                return;
            }
            var directory = directories.get(key);
            var changed = new HashSet<Path>();
            var overflow = false;
            if (directory != null) {
                for (var event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                        overflow = true;
                        continue;
                    }
                    var context = event.context();
                    if (!(context instanceof Path relative)) {
                        overflow = true;
                        continue;
                    }
                    var path = directory.resolve(relative).toAbsolutePath().normalize();
                    if (ModelSourceDiscovery.isEditorTemporaryFile(path)) continue;
                    // Child events describe content changes. Directory mtime MODIFY
                    // would otherwise turn those exact events into subtree scans.
                    if (event.kind() == StandardWatchEventKinds.ENTRY_MODIFY
                            && Files.isDirectory(path)) continue;
                    if (relevant(path)) {
                        changed.add(path);
                    }
                    if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE
                            && relevant(path) && Files.isDirectory(path)) {
                        try {
                            registerRecursively(path);
                        } catch (IOException error) {
                            overflow = true;
                            YesSteveModel.LOGGER.error(
                                    "Failed to register a new model directory with the watcher path={}",
                                    path, error);
                        }
                    }
                }
            }
            if (!key.reset()) {
                directories.remove(key);
            }
            if (overflow || !changed.isEmpty()) {
                try {
                    listener.accept(new SourceChangeSet(changed, overflow));
                } catch (RuntimeException error) {
                    YesSteveModel.LOGGER.error("Model directory watcher listener failed", error);
                }
            }
        }
    }

    private void registerRecursively(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (var directory : paths.filter(Files::isDirectory).toList()) {
                register(directory);
            }
        }
    }

    private void register(Path directory) throws IOException {
        var key = directory.register(watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_DELETE,
                StandardWatchEventKinds.ENTRY_MODIFY);
        directories.put(key, directory.toAbsolutePath().normalize());
    }

    private boolean relevant(Path path) {
        return roots.stream().anyMatch(root -> path.startsWith(root) || root.startsWith(path));
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                watchService.close();
            } catch (IOException error) {
                YesSteveModel.LOGGER.debug("Failed to close the model directory watcher", error);
            }
            thread.interrupt();
            directories.clear();
        }
        joinWatcher();
    }

    private void joinWatcher() {
        var interrupted = false;
        while (thread.isAlive()) {
            try {
                thread.join();
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
