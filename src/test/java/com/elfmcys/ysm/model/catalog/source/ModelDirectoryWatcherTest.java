package com.elfmcys.ysm.model.catalog.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelDirectoryWatcherTest {
    @TempDir
    Path temp;

    @Test
    void atomicProfileSaveIgnoresTemporaryFileEvents() throws Exception {
        var root = Files.createDirectories(temp.resolve("custom/nested"));
        var events = new LinkedBlockingQueue<SourceChangeSet>();
        try (var watcher = new ModelDirectoryWatcher(List.of(new ModelCatalogSource(
                CatalogRootKind.CUSTOM, temp.resolve("custom"), true)), events::add)) {
            var scratch = Files.createTempFile(root, ".omysm-", ".tmp");
            Files.writeString(scratch, "{}");
            assertNull(events.poll(300, TimeUnit.MILLISECONDS));
            var sidecar = root.resolve("avatar.pmx.omysm.json");
            Files.move(scratch, sidecar, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            var event = events.poll(5, TimeUnit.SECONDS);
            assertNotNull(event);
            assertTrue(event.paths().contains(sidecar));
            assertFalse(event.paths().contains(scratch));
            assertFalse(event.paths().contains(root));
        }
    }

    @Test
    void observesChangesBelowRegisteredRoots() throws Exception {
        var root = Files.createDirectories(temp.resolve("custom/nested"));
        var events = new LinkedBlockingQueue<SourceChangeSet>();
        try (var watcher = new ModelDirectoryWatcher(List.of(
                new ModelCatalogSource(CatalogRootKind.CUSTOM, temp.resolve("custom"), true)),
                events::add)) {
            var model = root.resolve("model.mxc").toAbsolutePath().normalize();
            Files.writeString(model, "changed");

            var event = events.poll(5, TimeUnit.SECONDS);
            assertNotNull(event);
            assertTrue(event.overflow() || event.paths().contains(model));
        }
    }

    @Test
    void closeReturnsOnlyAfterTheWatcherThreadTerminates() throws Exception {
        var watcher = new ModelDirectoryWatcher(List.of(
                new ModelCatalogSource(CatalogRootKind.CUSTOM,
                        temp.resolve("custom"), true)), ignored -> { });
        var field = ModelDirectoryWatcher.class.getDeclaredField("thread");
        field.setAccessible(true);
        var thread = (Thread) field.get(watcher);

        watcher.close();

        assertFalse(thread.isAlive());
    }

    @Test
    void skipsBuiltinRootsOnNonWatchableFilesystems() throws Exception {
        var archive = temp.resolve("builtin.zip");
        try (var filesystem = FileSystems.newFileSystem(
                archive, Map.of("create", "true"))) {
            var builtin = Files.createDirectories(filesystem.getPath("/builtin"));
            try (var watcher = new ModelDirectoryWatcher(List.of(
                    new ModelCatalogSource(CatalogRootKind.BUILTIN, builtin, false),
                    new ModelCatalogSource(CatalogRootKind.CUSTOM,
                            temp.resolve("custom"), true)), ignored -> { })) {
                assertTrue(Files.isDirectory(temp.resolve("custom")));
            }
        }
    }
}
