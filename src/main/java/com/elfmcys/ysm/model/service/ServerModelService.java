package com.elfmcys.ysm.model.service;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.config.ServerConfig;
import com.elfmcys.ysm.model.ModelRuntime;
import com.elfmcys.ysm.model.catalog.ReloadStatus;
import com.elfmcys.ysm.model.catalog.ReloadableModelCatalog;
import com.elfmcys.ysm.model.catalog.snapshot.CatalogSnapshot;
import com.elfmcys.ysm.model.catalog.snapshot.ServerCatalog;
import com.elfmcys.ysm.model.domain.ModelScanReport;
import com.elfmcys.ysm.model.domain.ModelScanWarning;
import com.elfmcys.ysm.model.session.server.ServerConnectionRegistry;
import com.elfmcys.ysm.model.session.server.ServerModelSession;
import com.elfmcys.ysm.model.storage.PreviewStore;
import com.elfmcys.ysm.network.NetworkHandler;
import com.elfmcys.ysm.network.dispatch.ResourceDispatchWorker;
import com.elfmcys.ysm.network.dispatch.ServerAssetTransfers;
import com.elfmcys.ysm.network.forge.ForgeTransportPort;
import com.elfmcys.ysm.network.forge.SessionProtocolHandler;
import com.elfmcys.ysm.proto.network.MetadataPrefixRequest;
import com.elfmcys.ysm.proto.network.ModelChunkRequest;
import com.elfmcys.ysm.proto.network.PresentationPageRequest;
import com.elfmcys.ysm.util.ThreadTools;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetworkManager;
import net.minecraft.server.MinecraftServer;

/** Side-local owner of the server catalog, connections, and global distribution worker. */
public final class ServerModelService implements AutoCloseable {
    private static volatile ServerModelService INSTANCE;

    private final MinecraftServer server;
    private final ReloadableModelCatalog catalog;
    private final ResourceDispatchWorker dispatch;
    private final ModelExportService.ServerRuntime exports;
    private final ReloadableModelCatalog.Subscription publicationSubscription;
    private final ServerConnectionRegistry<ConnectionState> connections =
            new ServerConnectionRegistry<>();
    private final AtomicReference<CatalogSnapshot> pendingPublication =
            new AtomicReference<>();

    private ServerModelService(MinecraftServer server) {
        this.server = server;
        var system = ModelRuntime.system();
        catalog = system.catalog();
        dispatch = new ResourceDispatchWorker(ServerModelService::dispatchSettings);
        dispatch.start();
        exports = new ModelExportService.ServerRuntime(ThreadTools::submit);
        publicationSubscription = catalog.subscribe(transition ->
                pendingPublication.set(transition.current()));
    }

    /** Applies cross-owner catalog facts and transfer completions on the server tick. */
    public synchronized void tick() {
        if (INSTANCE != this) {
            return;
        }
        var publication = pendingPublication.getAndSet(null);
        if (publication != null) {
            for (var player : server.getPlayerList().getPlayers()) {
                try {
                    SessionProtocolHandler.publishCatalog(player, publication);
                } catch (RuntimeException failure) {
                    YesSteveModel.LOGGER.error(
                            "Failed to publish catalog to {}",
                            player.getGameProfile().getName(), failure);
                }
            }
        }
        exports.tick();
        connections.forEach(state -> state.assetTransfers.tick());
    }

    public static synchronized ServerModelService start(MinecraftServer server) {
        if (INSTANCE == null) {
            INSTANCE = new ServerModelService(server);
            ModelRuntime.system().activateCatalog();
        }
        return INSTANCE;
    }

    public static ServerModelService instance() {
        var service = INSTANCE;
        if (service == null) {
            throw new IllegalStateException("Server model service is not running");
        }
        return service;
    }

    public static Optional<ServerModelService> current() {
        return Optional.ofNullable(INSTANCE);
    }

    public Optional<ServerCatalog> catalog() {
        return Optional.of(new ServerCatalog(catalog.current()));
    }

    public synchronized ServerModelSession offerSession(EntityPlayerMP player, boolean syncRoaming) {
        if (INSTANCE != this) {
            throw new IllegalStateException("Server model service is not running");
        }
        var playerId = player.getUniqueID();
        var current = connections.current(playerId, player.connection.getNetworkManager()).orElse(null);
        if (current != null) {
            return current.session;
        }
        var session = new ServerModelSession(catalog::current, syncRoaming);
        var transport = new ForgeTransportPort(player);
        connections.replace(playerId, player.connection.getNetworkManager(),
                new ConnectionState(player, session, transport));
        return session;
    }

    public synchronized Optional<ServerModelSession> session(EntityPlayerMP player) {
        return session(player, player.connection.getNetworkManager());
    }

    public synchronized Optional<ServerModelSession> session(
            EntityPlayerMP player, NetworkManager connection) {
        return connections.current(player.getUniqueID(), connection)
                .map(state -> state.session);
    }

    public synchronized boolean runIfCurrent(
            EntityPlayerMP player, ServerModelSession owner, Runnable action) {
        return runIfCurrent(player, player.connection.getNetworkManager(), owner, action);
    }

    public synchronized boolean runIfCurrent(
            EntityPlayerMP player, NetworkManager connection,
            ServerModelSession owner, Runnable action) {
        var state = connections.current(
                player.getUniqueID(), connection).orElse(null);
        if (state == null || state.session != owner) {
            return false;
        }
        return connections.runIfCurrent(player.getUniqueID(), connection,
                state, action);
    }

    public synchronized void closeSession(EntityPlayerMP player) {
        connections.remove(player.getUniqueID(), player.connection.getNetworkManager());
    }

    public ResourceDispatchWorker dispatch() {
        return dispatch;
    }

    public synchronized ForgeTransportPort transport(
            EntityPlayerMP player, ServerModelSession owner) {
        var state = connections.current(
                player.getUniqueID(), player.connection.getNetworkManager()).orElse(null);
        if (state == null || state.session != owner) {
            throw new IllegalStateException("Model session is not current");
        }
        return state.transport;
    }

    public ModelScanReport lastReport() {
        return catalog.current().report();
    }

    public synchronized void acceptMetadataPrefixRequest(
            EntityPlayerMP player, NetworkManager connection,
            ServerModelSession owner,
            MetadataPrefixRequest request) {
        acceptResourceRequest(player, connection, owner, transfers -> transfers.accept(request));
    }

    public synchronized void acceptModelChunkRequest(
            EntityPlayerMP player, NetworkManager connection,
            ServerModelSession owner,
            ModelChunkRequest request) {
        acceptResourceRequest(player, connection, owner, transfers -> transfers.accept(request));
    }

    public synchronized void acceptPresentationPageRequest(
            EntityPlayerMP player, NetworkManager connection,
            ServerModelSession owner,
            PresentationPageRequest request) {
        acceptResourceRequest(player, connection, owner, transfers -> transfers.accept(request));
    }

    public synchronized void cancelResourceTransfer(
            EntityPlayerMP player, NetworkManager connection,
            ServerModelSession owner, long transferId) {
        acceptResourceRequest(player, connection, owner,
                transfers -> transfers.cancel(transferId));
    }

    public CompletableFuture<ReloadOutcome> reload() {
        return catalog.reload().thenApply(result -> new ReloadOutcome(
                result.status() == ReloadStatus.COMMITTED,
                result.modelCount(), result.errorCount(), result.warnings(),
                result.message()));
    }

    CompletableFuture<Path> export(String requestedPath, String extra) {
        return exports.submit(requestedPath, extra);
    }

    @Override
    public synchronized void close() {
        if (INSTANCE != this) {
            return;
        }
        INSTANCE = null;
        publicationSubscription.close();
        pendingPublication.set(null);
        RuntimeException failure = closeOwner(null, connections::close);
        failure = closeOwner(failure, exports::close);
        failure = closeOwner(failure, dispatch::close);
        if (failure != null) {
            throw failure;
        }
    }

    private static RuntimeException closeOwner(
            RuntimeException failure, Runnable close) {
        try {
            close.run();
        } catch (RuntimeException closeFailure) {
            if (failure == null) {
                return closeFailure;
            }
            failure.addSuppressed(closeFailure);
        }
        return failure;
    }

    private void acceptResourceRequest(
            EntityPlayerMP player, NetworkManager connection,
            ServerModelSession owner, Predicate<ServerAssetTransfers> request) {
        var state = connections.current(
                player.getUniqueID(), connection).orElse(null);
        if (state == null || state.session != owner) {
            return;
        }
        if (!request.test(state.assetTransfers)) {
            connections.remove(player.getUniqueID(), connection);
        }
    }

    public record ReloadOutcome(boolean success, int modelCount, int errorCount,
                                List<ModelScanWarning> warnings, String message) {
        public ReloadOutcome {
            warnings = List.copyOf(warnings);
        }

        public int warningCount() {
            return warnings.stream()
                    .mapToInt(ModelScanWarning::occurrences)
                    .sum();
        }
    }

    private static ResourceDispatchWorker.Settings dispatchSettings() {
        var megabits = ServerConfig.BANDWIDTH_LIMIT;
        var bytesPerSecond = megabits == 0 ? 0L : megabits * 1_000_000L / 8;
        return new ResourceDispatchWorker.Settings(
                ServerConfig.DISPATCH_SOFT_LIMIT,
                ServerConfig.DISPATCH_HARD_LIMIT, bytesPerSecond);
    }

    private final class ConnectionState implements AutoCloseable {
        private final ServerModelSession session;
        private final ForgeTransportPort transport;
        private final ServerAssetTransfers assetTransfers;
        private boolean closed;

        private ConnectionState(EntityPlayerMP player,
                                ServerModelSession session,
                                ForgeTransportPort transport) {
            this.session = session;
            this.transport = transport;
            assetTransfers = new ServerAssetTransfers(session, dispatch, transport,
                    () -> ServerConfig.RESTRICTED_AUTH,
                    failure -> ServerModelService.this.runIfCurrent(player, session,
                            () -> NetworkHandler.sendToClientPlayer(failure, player)),
                    ModelRuntime.system().serverChunks(),
                    new PreviewStore(ModelRuntime.system().storage().gameCacheRoot(),
                            ModelRuntime.system().storage().cache()));
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            assetTransfers.close();
            dispatch.disconnect(session);
            session.close();
        }
    }
}
