package com.elfmcys.ysm.mock.host;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.model.service.ServerModelService;
import com.elfmcys.ysm.model.session.server.state.Selection;
import com.elfmcys.ysm.model.session.server.ServerModelSession;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

final class ServerProbe {
    private final HostIo io;
    private final Map<UUID, Integer> connectionOrdinals = new LinkedHashMap<>();
    private final Map<String, CapturedSession> retired = new LinkedHashMap<>();
    private MinecraftServer server;
    private HostAction action;
    private PendingAction pending;
    private boolean stopAfterAck;

    ServerProbe(HostIo io) {
        this.io = io;
    }

    @SubscribeEvent
    public void onStarted(ServerStartedEvent event) {
        server = event.getServer();
        try {
            var service = ServerModelService.instance();
            io.ready(Map.of(
                    "available", YesSteveModel.isAvailable(),
                    "catalogCount", service.catalog().orElseThrow().models().size(),
                    "ownerThread", server.isSameThread()));
        } catch (IOException failure) {
            throw new IllegalStateException("Failed to publish server readiness", failure);
        }
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        var ordinal = connectionOrdinals.merge(player.getUUID(), 1, Integer::sum);
        try {
            io.event("connection-opened", null, Map.of(
                    "connection", connectionId(player),
                    "ordinal", ordinal,
                    "player", player.getGameProfile().getName(),
                    "playerId", player.getUUID().toString(),
                    "ownerThread", server != null && server.isSameThread()));
        } catch (IOException failure) {
            throw new IllegalStateException("Failed to record server login", failure);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        var service = ServerModelService.current().orElse(null);
        var session = service == null ? null : service.session(player).orElse(null);
        if (session != null) {
            retired.put(player.getGameProfile().getName(),
                    new CapturedSession(player, session, connectionId(player)));
        }
        try {
            io.event("connection-closing", null, Map.of(
                    "capturedSession", session != null,
                    "connection", connectionId(player),
                    "player", player.getGameProfile().getName(),
                    "ownerThread", server != null && server.isSameThread()));
        } catch (IOException failure) {
            throw new IllegalStateException("Failed to record server logout", failure);
        }
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || server == null) {
            return;
        }
        try {
            advance();
        } catch (Throwable failure) {
            failAction(failure);
        }
    }

    @SubscribeEvent
    public void onStopping(ServerStoppingEvent event) {
        try {
            io.event("host-stopping", null, Map.of(
                    "ownerThread", event.getServer().isSameThread(),
                    "servicePresent", ServerModelService.current().isPresent()));
        } catch (IOException failure) {
            throw new IllegalStateException("Failed to record server stopping", failure);
        }
    }

    @SubscribeEvent
    public void onStopped(ServerStoppedEvent event) {
        try {
            io.event("host-stopped", null, Map.of(
                    "servicePresent", ServerModelService.current().isPresent()));
        } catch (IOException failure) {
            throw new IllegalStateException("Failed to record server stop", failure);
        }
    }

    private void advance() throws Exception {
        if (pending != null) {
            var result = pending.poll();
            if (result != null) {
                io.complete(action, result);
                action = null;
                pending = null;
                if (stopAfterAck) {
                    server.halt(false);
                }
            }
            return;
        }
        action = io.nextAction();
        if (action == null) {
            return;
        }
        io.event("action-started", action.id(), Map.of("name", action.name()));
        pending = start(action);
    }

    private PendingAction start(HostAction next) {
        return switch (next.name()) {
            case "snapshot" -> () -> snapshot();
            case "prepare-render-stage" -> () -> prepareRenderStage(next.require("player"));
            case "await-pack" -> () -> awaitPack(next.require("hierarchy"));
            case "await-selection" -> () -> awaitSelection(
                    next.require("player"), next.require("path"));
            case "late-old-owner" -> () -> lateOldOwner(next.require("player"));
            case "await-slot" -> () -> awaitSlot(next.require("player"),
                    Integer.parseInt(next.require("slot")));
            case "stop" -> () -> {
                stopAfterAck = true;
                return Map.of("normalStopRequested", true,
                        "ownerThread", server.isSameThread());
            };
            default -> throw new IllegalArgumentException(
                    "Unknown server action: " + next.name());
        };
    }

    private Map<String, ?> prepareRenderStage(String name) {
        var player = server.getPlayerList().getPlayerByName(name);
        if (player == null) throw new IllegalStateException("Missing render-stage player: " + name);
        var level = player.serverLevel();
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                level.setBlockAndUpdate(new net.minecraft.core.BlockPos(x, 199, z),
                        net.minecraft.world.level.block.Blocks.SMOOTH_STONE.defaultBlockState());
            }
        }
        level.setDayTime(6000);
        level.setWeatherParameters(6000, 0, false, false);
        player.connection.teleport(0.5, 200, 0.5, 180, 0);
        return Map.of("stageReady", true);
    }

    private Map<String, ?> snapshot() {
        var result = new LinkedHashMap<String, Object>();
        var service = ServerModelService.instance();
        var catalog = service.catalog().orElseThrow();
        result.put("available", YesSteveModel.isAvailable());
        result.put("catalogCount", catalog.models().size());
        result.put("paths", catalog.catalog().byLocation().keySet().stream()
                .map(location -> location.path().value()).sorted().toList());
        var sessions = new LinkedHashMap<String, Object>();
        for (var player : server.getPlayerList().getPlayers()) {
            sessions.put(player.getGameProfile().getName(), sessionFacts(player));
        }
        result.put("sessions", sessions);
        result.put("ownerThread", server.isSameThread());
        return result;
    }

    private Map<String, ?> awaitPack(String hierarchy) {
        var pack = ServerModelService.instance().catalog().orElseThrow().packs().stream()
                .filter(candidate -> candidate.hierarchy().equals(hierarchy))
                .findFirst().orElse(null);
        if (pack == null) {
            return null;
        }
        return Map.of(
                "coverSize", pack.coverSize(),
                "hierarchy", hierarchy,
                "ownerThread", server.isSameThread(),
                "rootKind", pack.rootKind().name());
    }

    private Map<String, ?> awaitSelection(String playerName, String path) {
        var player = server.getPlayerList().getPlayerByName(playerName);
        if (player == null) {
            return null;
        }
        var service = ServerModelService.instance();
        var expected = service.catalog().orElseThrow().findPath(path).orElse(null);
        var session = service.session(player).orElse(null);
        if (expected == null || session == null
                || !(session.selection() instanceof Selection.Model selected)
                || !selected.modelId().equals(expected.modelId())) {
            return null;
        }
        return Map.of(
                "connection", connectionId(player),
                "modelId", expected.modelId().toString(),
                "path", path,
                "player", playerName,
                "ownerThread", server.isSameThread());
    }

    private Map<String, ?> lateOldOwner(String playerName) {
        var old = retired.get(playerName);
        var currentPlayer = server.getPlayerList().getPlayerByName(playerName);
        if (old == null || currentPlayer == null) {
            return null;
        }
        var service = ServerModelService.instance();
        var current = service.session(currentPlayer).orElse(null);
        if (current == null || !current.active()) {
            return null;
        }
        var before = current.selection().toString();
        var effect = new boolean[1];
        var accepted = service.runIfCurrent(old.player(), old.session(),
                () -> effect[0] = true);
        var after = current.selection().toString();
        if (accepted || effect[0] || !before.equals(after)) {
            throw new IllegalStateException("Retired server owner produced a current effect");
        }
        return Map.of(
                "currentConnection", connectionId(currentPlayer),
                "oldConnection", old.connection(),
                "oldGateAccepted", false,
                "currentSelectionUnchanged", true,
                "ownerThread", server.isSameThread());
    }

    private Map<String, ?> awaitSlot(String playerName, int slot) {
        var player = server.getPlayerList().getPlayerByName(playerName);
        if (player == null || player.getInventory().selected != slot) {
            return null;
        }
        return Map.of(
                "connection", connectionId(player),
                "player", playerName,
                "selectedSlot", slot,
                "ownerThread", server.isSameThread());
    }

    private Map<String, ?> sessionFacts(ServerPlayer player) {
        var session = ServerModelService.instance().session(player).orElse(null);
        if (session == null) {
            return Map.of("present", false);
        }
        return Map.of(
                "active", session.active(),
                "connection", connectionId(player),
                "pending", session.pending(),
                "present", true,
                "published", session.hasPublishedCatalog(),
                "selection", session.selection().toString());
    }

    private void failAction(Throwable failure) {
        if (action == null) {
            throw failure instanceof RuntimeException runtime
                    ? runtime : new IllegalStateException("Server probe failed", failure);
        }
        try {
            io.fail(action, failure);
        } catch (IOException writeFailure) {
            failure.addSuppressed(writeFailure);
            throw new IllegalStateException("Server action failed without an acknowledgement",
                    failure);
        } finally {
            action = null;
            pending = null;
        }
    }

    private static String connectionId(ServerPlayer player) {
        return Integer.toUnsignedString(
                System.identityHashCode(player.connection.connection));
    }

    @FunctionalInterface
    private interface PendingAction {
        Map<String, ?> poll() throws Exception;
    }

    private record CapturedSession(ServerPlayer player, ServerModelSession session,
                                   String connection) {
    }
}
