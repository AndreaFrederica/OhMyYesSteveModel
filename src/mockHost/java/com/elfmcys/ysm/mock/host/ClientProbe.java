package com.elfmcys.ysm.mock.host;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.resource.client.asset.ClientAssetBatch;
import com.elfmcys.ysm.model.service.ClientModelService;
import com.elfmcys.ysm.model.session.client.ClientModelSession;
import com.elfmcys.ysm.model.session.client.state.ActivationSnapshot;
import com.elfmcys.ysm.natives.image.ImageSource;
import com.elfmcys.ysm.network.NetworkHandler;
import com.elfmcys.ysm.network.forge.ClientProtocolGateway;
import com.elfmcys.ysm.network.forge.ClientSessionRuntime;
import com.elfmcys.ysm.proto.network.SelectModelRequest;
import com.mojang.blaze3d.systems.RenderSystem;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

@OnlyIn(Dist.CLIENT)
public final class ClientProbe {
    private final HostIo io;
    private final String address;
    private Minecraft minecraft;
    private HostAction action;
    private PendingAction pending;
    private Connection currentConnection;
    private Connection retiredConnection;
    private int connectionOrdinal;
    private int ticks;
    private int readyAt;
    private int reconnectAt;
    private int connectAttempts;
    private long connectStartedAtNanos;
    private boolean ready;
    private boolean connecting;
    private boolean reconnectRequested;
    private boolean stopAfterAck;
    private boolean modelScreenOpened;
    private long frameBackgroundStart, duplicateBackgroundFrames;

    @SubscribeEvent
    public void onRenderFrame(TickEvent.RenderTickEvent event) {
        long count = com.elfmcys.ysm.client.event.RenderFirstPlayerBackground.drawCount();
        if (event.phase == TickEvent.Phase.START) frameBackgroundStart = count;
        else if (count - frameBackgroundStart > 1) duplicateBackgroundFrames++;
    }


    public ClientProbe(HostIo io) {
        this.io = io;
        address = requiredEnvironment("YSM_MOCK_SERVER_ADDRESS");
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        minecraft = Minecraft.getInstance();
        modelScreenOpened |= minecraft.screen instanceof com.elfmcys.ysm.client.gui.PlayerModelScreen;
        ticks++;
        try {
            publishReady();
            connectWhenNeeded();
            advance();
        } catch (Throwable failure) {
            failAction(failure);
        }
    }

    @SubscribeEvent
    public void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        currentConnection = event.getConnection();
        connectionOrdinal++;
        modelScreenOpened = false;
        connecting = false;
        reconnectRequested = false;
        try {
            io.event("connection-opened", null, Map.of(
                    "attempts", connectAttempts,
                    "connection", connectionId(currentConnection),
                    "ordinal", connectionOrdinal,
                    "ownerThread", Minecraft.getInstance().isSameThread()));
        } catch (IOException failure) {
            throw new IllegalStateException("Failed to record client login", failure);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        var connection = event.getConnection();
        if (connection == null) {
            return;
        }
        retiredConnection = connection;
        currentConnection = null;
        connecting = false;
        if (reconnectRequested) {
            reconnectAt = ticks + 20;
        }
        try {
            io.event("connection-closing", null, Map.of(
                    "connection", connection == null ? "none" : connectionId(connection),
                    "capturedOldConnection", connection != null,
                    "ownerThread", Minecraft.getInstance().isSameThread()));
        } catch (IOException failure) {
            throw new IllegalStateException("Failed to record client logout", failure);
        }
    }

    private void publishReady() throws IOException {
        if (ready || !YesSteveModel.isAvailable()
                || ClientModelService.current().isEmpty()) {
            return;
        }
        var startupProjection = StartupProjectionProbe.verify(minecraft);
        ready = true;
        readyAt = ticks;
        io.ready(Map.of(
                "available", true,
                "clientThread", minecraft.isSameThread(),
                "renderThread", RenderSystem.isOnRenderThread(),
                "startupProjection", startupProjection));
    }

    private void connectWhenNeeded() throws IOException {
        if (!ready || minecraft.getConnection() != null) {
            return;
        }
        if (connecting) {
            if (System.nanoTime() - connectStartedAtNanos
                    < TimeUnit.SECONDS.toNanos(40)) {
                return;
            }
            connecting = false;
            io.event("connection-retry", null, Map.of(
                    "attempt", connectAttempts + 1,
                    "reason", "pre-login-timeout",
                    "screen", minecraft.screen == null
                            ? "none" : minecraft.screen.getClass().getName()));
        }
        if (connectionOrdinal == 0 && ticks - readyAt < 40) {
            return;
        }
        if (connectionOrdinal > 0 && (!reconnectRequested || ticks < reconnectAt)) {
            return;
        }
        connecting = true;
        connectAttempts++;
        connectStartedAtNanos = System.nanoTime();
        var serverAddress = ServerAddress.parseString(address);
        var serverData = new ServerData("YSM mock host", address, false);
        ConnectScreen.startConnecting(minecraft.screen, minecraft, serverAddress,
                serverData, false);
    }

    private void advance() throws Exception {
        if (!ready) {
            return;
        }
        if (pending != null) {
            var result = pending.poll();
            if (result != null) {
                io.complete(action, result);
                action = null;
                pending = null;
                if (stopAfterAck) {
                    minecraft.stop();
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
            case "await-state" -> () -> awaitState(next.require("state"));
            case "snapshot" -> () -> snapshot();
            case "await-auto-catalog" -> () -> awaitAutoCatalog(next.require("prefix"),
                    Integer.parseInt(next.require("count")));
            case "await-local-scan" -> () -> {
                var catalog = com.elfmcys.ysm.model.ModelRuntime.system().catalog();
                if (catalog.busy()) return null;
                if (modelScreenOpened) throw new IllegalStateException("Scan required a model screen");
                var facts = new LinkedHashMap<String, Object>(snapshot());
                facts.put("modelScreenOpened", false);
                facts.put("scanErrors", catalog.current().report().errors().stream()
                        .map(Object::toString).toList());
                return facts;
            };
            case "await-equipped" -> () -> awaitEquipped(next.require("path"));
            case "open-catalog" -> () -> {
                minecraft.setScreen(new com.elfmcys.ysm.client.gui.PlayerModelScreen());
                return Map.of("opened", true);
            };
            case "await-pack-button" -> () -> awaitPackButton(next.require("name"));
            case "loading-settings-proof" -> new LoadingSettingsProof();
            case "pair-render-proof" -> new PairRenderProof();
            case "runtime-render-proof" -> new RuntimeRenderProof();
            case "runtime-first-person-proof" -> new FirstPersonProof();
            case "runtime-first-person-body-proof" -> new FirstPersonBodyProbe(io, () -> ticks);
            case "runtime-audio-proof" -> new RuntimeAudioProof(next.require("codec"));
            case "await-path" -> () -> awaitPath(next.require("path"));
            case "page-pack-cover" -> new PagePackCover(next.require("hierarchy"));
            case "select-path" -> () -> selectPath(next.require("path"));
            case "invalid-select" -> delayedConnectionCheck(() -> {
                NetworkHandler.sendToServer(SelectModelRequest.newBuilder()
                        .setTextureId("").build());
                return ClientModelService.instance().catalog().models().size();
            }, "invalidSelection");
            case "enable-sync-reconnect" -> {
                com.elfmcys.ysm.config.ClientConfig.NETWORK_SESSION_MODE.set(
                        com.elfmcys.ysm.network.session.SessionMode.AUTO);
                yield reconnect();
            }
            case "await-player-model" -> () -> awaitPlayerModel(next.require("player"), next.require("path"));
            case "disconnect-reconnect" -> reconnect();
            case "late-old-owner" -> () -> lateOldOwner();
            case "set-slot" -> () -> setSlot(Integer.parseInt(next.require("slot")));
            case "stop" -> () -> {
                stopAfterAck = true;
                return Map.of("normalStopRequested", true,
                        "ownerThread", minecraft.isSameThread());
            };
            default -> throw new IllegalArgumentException(
                    "Unknown client action: " + next.name());
        };
    }

    private Map<String, ?> awaitState(String expected) {
        var state = ClientSessionRuntime.state().orElse(null);
        if (state == null || !state.name().equals(expected)) {
            return null;
        }
        return Map.of(
                "connection", connectionId(currentConnection),
                "connectionOrdinal", connectionOrdinal,
                "minecraftConnected", minecraft.getConnection() != null,
                "state", state.name());
    }

    private Map<String, ?> snapshot() {
        var result = new LinkedHashMap<String, Object>();
        var service = ClientModelService.instance();
        result.put("catalogCount", service.catalog().models().size());
        result.put("connection", currentConnection == null
                ? "none" : connectionId(currentConnection));
        result.put("connectionOrdinal", connectionOrdinal);
        result.put("minecraftConnected", minecraft.getConnection() != null);
        result.put("paths", service.catalog().models().values().stream()
                .map(entry -> entry.displayPath()).sorted().toList());
        result.put("sessionState", ClientSessionRuntime.state()
                .map(Enum::name).orElse("NONE"));
        return result;
    }

    private Map<String, ?> awaitAutoCatalog(String prefix, int expectedCount) {
        if (modelScreenOpened) {
            throw new IllegalStateException("Startup scan required a model screen");
        }
        var count = ClientModelService.instance().catalog().models().values().stream()
                .filter(entry -> entry.displayPath().startsWith(prefix)).count();
        return count < expectedCount ? null : Map.of("count", count,
                "modelScreenOpened", false, "prefix", prefix);
    }

    private Map<String, ?> awaitEquipped(String path) {
        if (modelScreenOpened) {
            throw new IllegalStateException("Selection restoration required a model screen");
        }
        var modelId = ClientModelService.instance().resolvePath(path).orElse(null);
        var player = minecraft.player;
        if (modelId == null || player == null) return null;
        var capability = player.getCapability(
                com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider.CAP).resolve().orElse(null);
        if (capability == null || !modelId.equals(capability.getModelHash())
                || !capability.isModelPresent()) return null;
        return Map.of("path", path, "modelId", modelId.toString(),
                "modelScreenOpened", false, "connectionOrdinal", connectionOrdinal);
    }

    private Map<String, ?> awaitPlayerModel(String name, String path) throws IOException {
        if (minecraft.level == null) return null;
        var player = minecraft.level.players().stream()
                .filter(value -> value.getGameProfile().getName().equals(name)).findFirst().orElse(null);
        var id = ClientModelService.instance().resolvePath(path).orElse(null);
        if (player == null || id == null) return null;
        var capability = player.getCapability(
                com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider.CAP).resolve().orElse(null);
        if (capability == null || !id.equals(capability.getModelHash())
                || !capability.isModelPresent() || capability.getModelRenderTarget() == null
                || !id.equals(capability.getModelRenderTarget().modelHash())) return null;
        return Map.of("player", name, "entityId", player.getId(), "modelId", id.toString(),
                "renderModelId", capability.getModelRenderTarget().modelHash().toString(),
                "path", path, "present", true, "modelScreenOpened", modelScreenOpened);
    }

    private Map<String, ?> awaitPackButton(String name) throws IOException {
        if (!(minecraft.screen instanceof com.elfmcys.ysm.client.gui.PlayerModelScreen screen)) {
            // A catalog publication can rebuild/replace the screen during the same
            // client tick. Restore the already requested catalog view and inspect the
            // next live screen; this is not a folder interaction or a rescan trigger.
            minecraft.setScreen(new com.elfmcys.ysm.client.gui.PlayerModelScreen());
            return null;
        }
        var found = screen.children().stream()
                .filter(com.elfmcys.ysm.client.gui.button.PackButton.class::isInstance)
                .map(com.elfmcys.ysm.client.gui.button.PackButton.class::cast)
                .anyMatch(button -> button.getMessage().getString().equals(name));
        if (!found) return null;
        try (var screenshot = net.minecraft.client.Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) {
            screenshot.writeToFile(io.artifact("auto-catalog-refresh.png"));
        }
        minecraft.setScreen(null);
        return Map.of("pack", name, "folderClicked", false, "automaticRefresh", true);
    }

    private final class PairRenderProof implements PendingAction {
        private final int captureAt = ticks + 10;
        private final net.minecraft.client.CameraType previous = minecraft.options.getCameraType();

        private PairRenderProof() {
            minecraft.setScreen(null);
            minecraft.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT);
        }

        @Override
        public Map<String, ?> poll() throws Exception {
            if (ticks < captureAt) return null;
            var first = awaitPlayerModel("YsmHostA", "host/online");
            var second = awaitPlayerModel("YsmHostB", "host/second");
            if (first == null || second == null) return null;
            try (var screenshot = net.minecraft.client.Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) {
                screenshot.writeToFile(io.artifact("both-players-after-rejoin.png"));
            } finally {
                minecraft.options.setCameraType(previous);
            }
            return Map.of("playerA", first, "playerB", second, "bothRendered", true);
        }
    }

    private final class LoadingSettingsProof implements PendingAction {
        private int stage;
        private int captureAt;

        @Override
        public Map<String, ?> poll() throws Exception {
            if (stage == 0) {
                var settings = new com.elfmcys.ysm.client.gui.ModelLoadingScreen(
                        new com.elfmcys.ysm.client.gui.ConfigScreen(null));
                minecraft.setScreen(settings);
                var sliders = settings.children().stream()
                        .filter(net.minecraftforge.client.gui.widget.ForgeSlider.class::isInstance).count();
                if (sliders != 5) throw new IllegalStateException("Missing loading controls");
                captureAt = ticks + 5;
                stage++;
                return null;
            }
            if (ticks < captureAt) return null;
            if (stage == 1) {
                try (var screenshot = net.minecraft.client.Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) {
                    screenshot.writeToFile(io.artifact("loading-settings.png"));
                }
                minecraft.setScreen(null);
                com.elfmcys.ysm.model.ModelRuntime.system().catalog().reload();
                captureAt = ticks + 3;
                stage++;
                return null;
            }
            try (var screenshot = net.minecraft.client.Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) {
                screenshot.writeToFile(io.artifact("loading-progress.png"));
            }
            var service = ClientModelService.instance();
            return Map.of("controls", 5, "stage", service.scanProgress().stage().name(),
                    "clientWorkers", com.elfmcys.ysm.config.ModelLoadingConfig.CLIENT_WORKERS.get(),
                    "catalogWorkers", com.elfmcys.ysm.config.ModelLoadingConfig.CATALOG_WORKERS.get());
        }
    }

    @SubscribeEvent
    public void onScreenOpening(net.minecraftforge.client.event.ScreenEvent.Opening event) {
        if (event.getNewScreen() instanceof com.elfmcys.ysm.client.gui.PlayerModelScreen) {
            try {
                io.event("model-screen-opened", action == null ? null : action.id(), Map.of(
                        "source", java.util.Arrays.stream(new Throwable().getStackTrace())
                                .map(StackTraceElement::toString).limit(32).toList()));
            } catch (IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
        }
    }

    private Map<String, ?> awaitPath(String path) {
        var service = ClientModelService.instance();
        var modelId = service.resolvePath(path).orElse(null);
        if (modelId == null) {
            return null;
        }
        var activation = ClientSessionRuntime.session().activationSnapshot().orElse(null);
        if (activation == null
                || !(activation.entries().get(modelId) instanceof
                ActivationSnapshot.Ready)) {
            return null;
        }
        var entry = service.catalog().find(modelId).orElseThrow();
        return Map.of(
                "containerId", entry.displayRepresentation().containerId().toString(),
                "modelId", modelId.toString(),
                "origin", entry.origin().name(),
                "path", path);
    }

    private Map<String, ?> selectPath(String path) {
        var service = ClientModelService.instance();
        var modelId = service.resolvePath(path).orElse(null);
        if (modelId == null || currentConnection == null) {
            return null;
        }
        var entry = service.catalog().find(modelId).orElse(null);
        if (entry == null) {
            return null;
        }
        var texture = entry.displayRepresentation().view().getPlayer().getTextureNames()
                .stream().findFirst().orElse("");
        ClientProtocolGateway.selectModel(modelId, texture);
        return Map.of(
                "connection", connectionId(currentConnection),
                "modelId", modelId.toString(),
                "networkPath", true,
                "path", path,
                "texture", texture);
    }

    private PendingAction delayedConnectionCheck(
            IntSupplier starter, String classification) {
        var beforeConnection = currentConnection;
        var beforeCatalog = starter.getAsInt();
        var readyAt = ticks + 20;
        return () -> {
            if (ticks < readyAt) {
                return null;
            }
            var afterCatalog = ClientModelService.instance().catalog().models().size();
            if (currentConnection != beforeConnection || minecraft.getConnection() == null
                    || beforeCatalog != afterCatalog) {
                throw new IllegalStateException(
                        "Ordinary YSM failure changed connection or current catalog");
            }
            return Map.of(
                    "catalogUnchanged", true,
                    "classification", classification,
                    "connection", connectionId(currentConnection),
                    "minecraftConnected", true);
        };
    }

    private PendingAction reconnect() {
        if (currentConnection == null || minecraft.getConnection() == null) {
            throw new IllegalStateException("Reconnect requires an active Minecraft connection");
        }
        var old = currentConnection;
        var ordinal = connectionOrdinal;
        reconnectRequested = true;
        old.disconnect(Component.literal("YSM mock reconnect"));
        return () -> {
            if (connectionOrdinal <= ordinal || currentConnection == null
                    || currentConnection == old
                    || ClientSessionRuntime.state().orElse(null)
                    != ClientModelSession.State.ACTIVE) {
                return null;
            }
            return Map.of(
                    "currentConnection", connectionId(currentConnection),
                    "currentOrdinal", connectionOrdinal,
                    "oldConnection", connectionId(old),
                    "sameProcess", true);
        };
    }

    private Map<String, ?> lateOldOwner() {
        if (retiredConnection == null || currentConnection == null
                || retiredConnection == currentConnection) {
            return null;
        }
        var before = ClientModelService.instance().catalog().models().keySet();
        var effect = new boolean[1];
        var accepted = ClientSessionRuntime.runIfCurrent(
                retiredConnection, () -> effect[0] = true);
        var after = ClientModelService.instance().catalog().models().keySet();
        if (accepted || effect[0] || !before.equals(after)) {
            throw new IllegalStateException("Retired client owner produced a current effect");
        }
        return Map.of(
                "currentConnection", connectionId(currentConnection),
                "oldConnection", connectionId(retiredConnection),
                "oldGateAccepted", false,
                "publicationUnchanged", true);
    }

    private Map<String, ?> setSlot(int slot) {
        if (minecraft.player == null || minecraft.getConnection() == null) {
            return null;
        }
        minecraft.player.getInventory().selected = slot;
        minecraft.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
        return Map.of(
                "connection", connectionId(currentConnection),
                "networkPath", "vanilla-client-to-server",
                "selectedSlot", slot);
    }

    private void failAction(Throwable failure) {
        if (action == null) {
            throw failure instanceof RuntimeException runtime
                    ? runtime : new IllegalStateException("Client probe failed", failure);
        }
        try {
            io.fail(action, failure);
        } catch (IOException writeFailure) {
            failure.addSuppressed(writeFailure);
            throw new IllegalStateException("Client action failed without an acknowledgement",
                    failure);
        } finally {
            action = null;
            pending = null;
        }
    }

    private static String connectionId(Connection connection) {
        if (connection == null) {
            throw new IllegalStateException("No exact client connection");
        }
        return Integer.toUnsignedString(System.identityHashCode(connection));
    }

    private static String requiredEnvironment(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing environment variable: " + name);
        }
        return value;
    }

    @FunctionalInterface
    interface PendingAction {
        Map<String, ?> poll() throws Exception;
    }

    /** Exercises real SoundEngine channel handoff with the JVM game stream adapters. */
    private final class RuntimeAudioProof implements PendingAction {
        private final String codec;
        private final java.util.concurrent.atomic.AtomicLong bytesRead = new java.util.concurrent.atomic.AtomicLong();
        private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        private final CompletableFuture<Void> stopped = new CompletableFuture<>();
        private long expected;
        private int started;
        private com.elfmcys.ysm.client.sound.instance.CustomSoundInstance sound;
        RuntimeAudioProof(String codec) {
            if (!codec.equals("opus") && !codec.equals("vorbis")) throw new IllegalArgumentException("Unknown codec");
            this.codec = codec;
        }
        @Override public Map<String, ?> poll() throws Exception {
            if (sound == null) {
                var data = java.nio.ByteBuffer.wrap(java.nio.file.Files.readAllBytes(io.artifact(codec + "-under.ogg")));
                var media = cc.sirrus.ysmlib.audio.SupportedAudioProbe.inspect(data).media();
                expected = media.frames() * 2;
                com.elfmcys.ysm.client.sound.stream.CustomAudioStream delegate = codec.equals("opus")
                        ? new com.elfmcys.ysm.client.sound.stream.OpusAudioStream(data, media)
                        : new com.elfmcys.ysm.client.sound.stream.VorbisAudioStream(data, media);
                var counting = new com.elfmcys.ysm.client.sound.stream.CustomAudioStream() {
                    public java.nio.ByteBuffer read(int length) throws java.io.IOException {
                        var pcm = delegate.read(length); bytesRead.addAndGet(pcm.remaining()); return pcm;
                    }
                    public javax.sound.sampled.AudioFormat getFormat() { return delegate.getFormat(); }
                    public boolean isClosed() { return closed.get(); }
                    public void close() throws java.io.IOException { delegate.close(); closed.set(true); }
                };
                var provider = new com.elfmcys.ysm.client.sound.stream.AudioStreamProvider() {
                    public CompletableFuture<com.elfmcys.ysm.client.sound.stream.CustomAudioStream> openStream(boolean looping) {
                        return CompletableFuture.completedFuture(counting);
                    }
                    public void stop() { stopped.complete(null); }
                    public java.util.concurrent.CompletionStage<Void> stopped() { return stopped; }
                };
                sound = new com.elfmcys.ysm.client.sound.instance.CustomSoundInstance(
                        com.elfmcys.ysm.init.ModSounds.CUSTOM, provider, minecraft.player);
                sound.setConfiguredVolume(.05f);
                minecraft.getSoundManager().play(sound);
                started = ticks;
            }
            if (ticks - started > 800) {
                sound.setStopped();
                throw new IllegalStateException("SoundEngine did not finish " + codec + ": bytes=" + bytesRead.get());
            }
            if (!closed.get()) return null;
            if (bytesRead.get() != expected) throw new IllegalStateException("SoundEngine PCM frame mismatch");
            sound.setStopped();
            return Map.of("codec", codec, "pcmBytes", bytesRead.get(), "streamClosed", true,
                    "javaOnly", Boolean.getBoolean("ysm.runtime.javaOnly"));
        }
    }

    /** Exercise actual Forge arm events in frames, including the vanilla fallback toggle. */
    private final class FirstPersonProof implements PendingAction {
        private int phase, finishAt = ticks + 50;
        private long before, right, left, backgroundBefore, backgrounds;
        private final net.minecraft.client.CameraType camera = minecraft.options.getCameraType();
        private final net.minecraft.world.entity.HumanoidArm mainArm = minecraft.options.mainHand().get();
        private final boolean disabled = com.elfmcys.ysm.config.ClientConfig.DISABLE_SELF_HANDS.get();
        private final boolean bodyEnabled = com.elfmcys.ysm.client.compat.FirstPersonCompat.isInstalled()
                && dev.tr7zw.firstperson.api.FirstPersonAPI.isEnabled();

        FirstPersonProof() {
            if (bodyEnabled) dev.tr7zw.firstperson.api.FirstPersonAPI.setEnabled(false);
            minecraft.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
            minecraft.options.mainHand().set(net.minecraft.world.entity.HumanoidArm.RIGHT);
            com.elfmcys.ysm.config.ClientConfig.DISABLE_SELF_HANDS.set(false);
            minecraft.setScreen(null);
            before = com.elfmcys.ysm.client.event.ReplacePlayerHandRenderEvent.replacementCount();
            backgroundBefore = com.elfmcys.ysm.client.event.RenderFirstPlayerBackground.drawCount();
        }

        @Override public Map<String, ?> poll() throws Exception {
            if (ticks < finishAt) return null;
            long count = com.elfmcys.ysm.client.event.ReplacePlayerHandRenderEvent.replacementCount();
            String status = com.elfmcys.ysm.client.event.ReplacePlayerHandRenderEvent.diagnostics();
            try {
                if (phase < 2) {
                    if (count <= before || !status.contains(phase == 0 ? "RIGHT vertices=" : "LEFT vertices="))
                        throw new IllegalStateException("First-person arm not replaced: " + status);
                    try (var screenshot = net.minecraft.client.Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) {
                        screenshot.writeToFile(io.artifact(phase == 0 ? "first-person-right.png" : "first-person-left.png"));
                    }
                    if (phase == 0) {
                        right = count - before;
                        minecraft.options.mainHand().set(net.minecraft.world.entity.HumanoidArm.LEFT);
                    } else {
                        left = count - before;
                        backgrounds = com.elfmcys.ysm.client.event.RenderFirstPlayerBackground.drawCount() - backgroundBefore;
                        if (backgrounds <= 0) throw new IllegalStateException("Background not drawn: "
                                + com.elfmcys.ysm.client.event.RenderFirstPlayerBackground.diagnostics());
                        var backgroundKind = com.elfmcys.ysm.api.rendering.v0.TargetKind.PLAYER_BACKGROUND;
                        if (RenderFeatureProbe.calls(backgroundKind) == 0 || RenderFeatureProbe.draws(backgroundKind) == 0)
                            throw new IllegalStateException("Background extension or nonempty draw not reached");
                        for (var kind : new com.elfmcys.ysm.api.rendering.v0.TargetKind[]{
                                com.elfmcys.ysm.api.rendering.v0.TargetKind.PLAYER_LEFT_ARM,
                                com.elfmcys.ysm.api.rendering.v0.TargetKind.PLAYER_RIGHT_ARM}) {
                            if (RenderFeatureProbe.calls(kind) == 0) throw new IllegalStateException("Modifier not reached: " + kind);
                        }
                        backgroundBefore += backgrounds;
                        com.elfmcys.ysm.config.ClientConfig.DISABLE_SELF_HANDS.set(true);
                    }
                    before = count;
                    phase++;
                    finishAt = ticks + 50;
                    return null;
                }
                if (count != before) throw new IllegalStateException("Disabled hands still replaced");
                if (com.elfmcys.ysm.client.event.RenderFirstPlayerBackground.drawCount() != backgroundBefore)
                    throw new IllegalStateException("Disabled background still drawn");
                if (duplicateBackgroundFrames != 0) throw new IllegalStateException("Repeated background submission in one frame");
                restore();
                return Map.of("rightDraws", right, "leftDraws", left, "backgroundDraws", backgrounds, "disabledFallback", true);
            } catch (Exception failure) {
                restore();
                throw failure;
            }
        }

        private void restore() {
            if (bodyEnabled) dev.tr7zw.firstperson.api.FirstPersonAPI.setEnabled(true);
            minecraft.options.setCameraType(camera);
            minecraft.options.mainHand().set(mainArm);
            com.elfmcys.ysm.config.ClientConfig.DISABLE_SELF_HANDS.set(disabled);
        }
    }

    /** Evidence from real frames, including the selected remotely imported model. */
    private final class RuntimeRenderProof implements PendingAction {
        private final jdk.jfr.Recording recording = new jdk.jfr.Recording();
        private final int finishAt = ticks + 80;
        RuntimeRenderProof() {
            recording.enable("cc.sirrus.ysmlib.Operation").withThreshold(java.time.Duration.ZERO);
            recording.start();
            minecraft.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT);
            minecraft.setScreen(null);
        }
        @Override public Map<String, ?> poll() throws Exception {
            if (ticks < finishAt) return null;
            var artifact = io.artifact("jvm-render.jfr");
            try (recording) {
                recording.stop();
                recording.dump(artifact);
            }
            long renders = jdk.jfr.consumer.RecordingFile.readAllEvents(artifact).stream()
                    .filter(e -> e.getEventType().getName().equals("cc.sirrus.ysmlib.Operation"))
                    .filter(e -> e.getString("operation").equals("render")).count();
            if (renders == 0) throw new IllegalStateException("No YSM renderer calls in actual game frames");
            try (var screenshot = net.minecraft.client.Screenshot.takeScreenshot(minecraft.getMainRenderTarget())) {
                screenshot.writeToFile(io.artifact("jvm-render.png"));
            }
            return Map.of("renderCalls", renders, "renderer", cc.sirrus.ysmlib.YsmRuntime.render().id(),
                    "nativeDraws", nativeDraws(),
                    "javaOnly", Boolean.getBoolean("ysm.runtime.javaOnly"));
        }
    }

    static long nativeDraws() {
        return cc.sirrus.ysmlib.YsmRuntime.successfulNativeRenderDraws();
    }

    private final class PagePackCover implements PendingAction {
        private final String hierarchy;
        private ClientAssetBatch batch;
        private CompletableFuture<
                ImageSource> cover;

        private PagePackCover(String hierarchy) {
            this.hierarchy = hierarchy;
        }

        @Override
        public Map<String, ?> poll() {
            if (batch == null) {
                var service = ClientModelService.instance();
                var pack = service.catalog().packs().stream()
                        .filter(candidate -> candidate.hierarchy().equals(hierarchy)
                                && candidate.rootKind()
                                == CatalogRootKind.CUSTOM)
                        .findFirst().orElse(null);
                if (pack == null) {
                    return null;
                }
                batch = service.createAssetBatch();
                cover = batch.packCover(pack);
                batch.submit();
            }
            if (!cover.isDone()) {
                return null;
            }
            try {
                var source = cover.join();
                var texture = ClientModelService.instance().createTexture(source);
                texture.load(minecraft.getResourceManager());
                var failure = texture.failure().orElse(null);
                texture.close();
                if (failure != null) {
                    throw new IllegalStateException("Preview texture publication failed", failure);
                }
                return Map.of(
                        "batchClosed", true,
                        "hierarchy", hierarchy,
                        "nativeImageDecode", true,
                        "pageAsset", "remote-pack-cover",
                        "renderThread", RenderSystem.isOnRenderThread(),
                        "texturePublished", true,
                        "textureReleased", true);
            } catch (CompletionException failure) {
                throw new IllegalStateException("Page pack cover failed", failure.getCause());
            } finally {
                batch.close();
                batch = null;
                cover = null;
            }
        }
    }
}
