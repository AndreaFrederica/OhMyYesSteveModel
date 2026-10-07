package com.elfmcys.ysm.model;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.model.catalog.ReloadResult;
import com.elfmcys.ysm.model.catalog.ReloadStatus;
import com.elfmcys.ysm.model.catalog.ReloadableModelCatalog;
import com.elfmcys.ysm.model.catalog.builtin.BuiltinModelCatalog;
import com.elfmcys.ysm.model.catalog.builtin.BuiltinModelIndex;
import com.elfmcys.ysm.model.catalog.source.ModelCatalogSources;
import com.elfmcys.ysm.model.resource.server.ServerChunkRuntime;
import com.elfmcys.ysm.model.storage.ModelStorageInfra;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Process composition root for immutable contracts, storage, and shared catalogs. */
public final class ModelSystem implements AutoCloseable {
    private final ModelStorageInfra storage;
    private final BuiltinModelCatalog builtins;
    private final ReloadableModelCatalog catalog;
    private final ServerChunkRuntime serverChunks;
    private CompletableFuture<ReloadResult> firstScan;
    private boolean closed;

    private ModelSystem(ModelStorageInfra storage,
                        BuiltinModelCatalog builtins,
                        ReloadableModelCatalog catalog,
                        ServerChunkRuntime serverChunks) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.builtins = Objects.requireNonNull(builtins, "builtins");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.serverChunks = Objects.requireNonNull(serverChunks, "serverChunks");
    }

    public static ModelSystem openDefault() {
        final BuiltinModelIndex contract;
        try {
            contract = BuiltinModelIndex.read(ModelCatalogSources.builtinIndex());
        } catch (IOException error) {
            throw new UncheckedIOException("Failed to load the builtin model contract", error);
        }
        var fullModVersion = Objects.requireNonNull(YesSteveModel.MOD,
                "Active mod container is unavailable").getVersion();
        var storage = ModelStorageInfra.openDefault(fullModVersion);
        BuiltinModelCatalog builtins = null;
        ReloadableModelCatalog catalog = null;
        ServerChunkRuntime serverChunks = null;
        try {
            builtins = BuiltinModelCatalog.open(contract);
            catalog = new ReloadableModelCatalog(
                    storage, ModelCatalogSources.sources(), builtins);
            serverChunks = new ServerChunkRuntime(error -> YesSteveModel.LOGGER.warn(
                    "Server model source instance became corrupted", error));
            return new ModelSystem(storage, builtins, catalog, serverChunks);
        } catch (RuntimeException | Error error) {
            if (serverChunks != null) {
                try {
                    serverChunks.close();
                } catch (RuntimeException closeError) {
                    error.addSuppressed(closeError);
                }
            }
            if (catalog != null) {
                try {
                    catalog.close();
                } catch (RuntimeException closeError) {
                    error.addSuppressed(closeError);
                }
            }
            if (builtins != null) {
                try {
                    builtins.close();
                } catch (RuntimeException closeError) {
                    error.addSuppressed(closeError);
                }
            }
            throw error;
        }
    }

    public BuiltinModelIndex builtinContract() {
        requireOpen();
        return builtins.contract();
    }

    public ModelStorageInfra storage() {
        requireOpen();
        return storage;
    }

    public BuiltinModelCatalog builtins() {
        requireOpen();
        return builtins;
    }

    public ReloadableModelCatalog catalog() {
        requireOpen();
        return catalog;
    }

    public ServerChunkRuntime serverChunks() {
        requireOpen();
        return serverChunks;
    }

    /** Registers this process before the one startup-delayed catalog scan. */
    public synchronized CompletableFuture<ReloadResult> activateCatalog() {
        requireOpen();
        if (firstScan != null) {
            return firstScan;
        }
        try {
            storage.convertedConsumers().register();
            firstScan = catalog.startScanning();
            return firstScan;
        } catch (IOException failure) {
            firstScan = CompletableFuture.completedFuture(new ReloadResult(
                    ReloadStatus.FAILED,
                    catalog.current().byModelId().size(),
                    catalog.current().report().errorCount(),
                    "Failed to register converted consumer: " + failure.getMessage()));
            return firstScan;
        }
    }

    public void tickCatalog() {
        requireOpen();
        catalog.tick();
    }

    public void tickServerRuntime() {
        requireOpen();
        serverChunks.tick();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        RuntimeException failure = null;
        try {
            serverChunks.close();
        } catch (RuntimeException error) {
            failure = suppress(failure, error);
        }
        try {
            catalog.close();
        } catch (RuntimeException error) {
            failure = suppress(failure, error);
        }
        try {
            storage.convertedConsumers().close();
        } catch (RuntimeException error) {
            failure = suppress(failure, error);
        }
        try {
            builtins.close();
        } catch (RuntimeException error) {
            failure = suppress(failure, error);
        }
        if (failure != null) {
            throw failure;
        }
    }

    private synchronized void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Model system is closed");
        }
    }

    private static RuntimeException suppress(RuntimeException failure,
                                             RuntimeException next) {
        if (failure == null) {
            return next;
        }
        failure.addSuppressed(next);
        return failure;
    }
}
