package com.elfmcys.ysm.network.forge;

import com.elfmcys.ysm.capability.PlayerAnimatableCapability;
import com.elfmcys.ysm.client.demand.LocalPlayerDemand;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.resource.client.ResourceLease;
import com.elfmcys.ysm.model.resource.client.ResourceRequest;
import com.elfmcys.ysm.model.service.ClientModelService;
import com.elfmcys.ysm.network.NetworkHandler;
import com.elfmcys.ysm.network.protocol.EntityRefEncoder;
import com.elfmcys.ysm.network.protocol.ProtocolMessages;
import com.elfmcys.ysm.proto.network.EmitMolangSync;
import com.elfmcys.ysm.proto.network.EntityAnimationActionRequest;
import com.elfmcys.ysm.proto.network.Hand;
import com.elfmcys.ysm.proto.network.RouletteAnimationSelection;
import com.elfmcys.ysm.proto.network.SelectModelRequest;
import com.elfmcys.ysm.proto.network.StarredModelOperation;
import com.elfmcys.ysm.proto.network.SubmitRouletteExpressionRequest;
import com.elfmcys.ysm.proto.network.SwingHandRequest;
import com.elfmcys.ysm.proto.network.UpdateStarredModelRequest;
import it.unimi.dsi.fastutil.floats.FloatList;
import it.unimi.dsi.fastutil.objects.Object2FloatMap;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import us.hebi.quickbuf.ProtoMessage;

public final class ClientProtocolGateway {
    private static final LocalPlayerStateReporter STATE_REPORTER = new LocalPlayerStateReporter();
    private static final LocalPlayerDemand MODEL_DEMAND = new LocalPlayerDemand(
            ClientProtocolGateway::monotonicMillis,
            new LocalPlayerDemand.ResourceAccess() {
                @Override
                public ResourceRequest request(
                        Hash256 modelId, String textureId) {
                    return ClientModelService.instance().resourceRequest(modelId, textureId);
                }

                @Override
                public CompletableFuture<Optional<ResourceLease>>
                getOrStartOffline(ResourceRequest request) {
                    return ClientModelService.instance().getOrStartOffline(request);
                }

                @Override
                public ResourceLease getOrStart(
                        ResourceRequest request) {
                    return ClientModelService.instance().getOrStart(request);
                }
            },
            new LocalPlayerDemand.SelectionEffect() {
                @Override
                public boolean available() {
                    return NetworkHandler.isRemoteChannelPresent()
                            && ClientSessionRuntime.businessSession().isPresent();
                }

                @Override
                public void send(Hash256 modelId, String textureId) {
                    sendModelSelection(modelId, textureId);
                }
            });

    private ClientProtocolGateway() {
    }

    public static void tick(LocalPlayer player, PlayerAnimatableCapability capability) {
        MODEL_DEMAND.tick();
        STATE_REPORTER.tick(player, capability);
    }

    public static void resetWorld() {
        MODEL_DEMAND.reset();
        STATE_REPORTER.resetSession();
    }

    public static void playSelfAnimation(String animationId) {
        var player = net.minecraft.client.Minecraft.getInstance().player;
        if (player != null) {
            var cap = player.getCapability(com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider.CAP).resolve().orElse(null);
            if (cap != null && cap.isGeneralMesh()) {
                var action = com.elfmcys.ysm.client.animation.GeneralAnimationActions.resolve(cap.getModelRenderTarget(), animationId);
                if (action == null) return;
                animationId = com.elfmcys.ysm.model.domain.SceneActionId.request(
                        com.elfmcys.ysm.model.domain.SceneActionId.of(action.animation(), action.loop()), System.nanoTime());
                if (!NetworkHandler.isRemoteChannelPresent()) { cap.playExtraAnimation(animationId);return; }
            }
        }
        STATE_REPORTER.setAnimation(animationId);
    }

    public static void stopSelfAnimation() {
        var player = net.minecraft.client.Minecraft.getInstance().player;
        if (player != null) player.getCapability(com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider.CAP)
                .ifPresent(cap -> { if (cap.isGeneralMesh()) cap.stopExtraAnimation(); });
        STATE_REPORTER.setAnimation("");
    }

    public static void acceptAuthoritativeFull(Hash256 modelHash, Integer roamingKey) {
        STATE_REPORTER.acceptAuthoritativeFull(modelHash, roamingKey);
    }

    public static void localPlayerCloned() {
        STATE_REPORTER.restartReportLifetime();
    }

    public static void playMaidAnimation(int entityId, int index, String classificationId) {
        send(ProtocolMessages.ENTITY_ANIMATION_ACTION_REQUEST_ID,
                EntityAnimationActionRequest.newBuilder()
                        .setTarget(EntityRefEncoder.encode(entityId))
                        .setPlay(RouletteAnimationSelection.newBuilder()
                                .setAnimationIndex(index)
                                .setClassificationId(classificationId)
                                .build())
                        .build());
    }

    public static void stopMaidAnimation(int entityId) {
        send(ProtocolMessages.ENTITY_ANIMATION_ACTION_REQUEST_ID,
                EntityAnimationActionRequest.newBuilder()
                        .setTarget(EntityRefEncoder.encode(entityId))
                        .setStop(true)
                        .build());
    }

    public static void selectModel(Hash256 hash, String textureId) {
        MODEL_DEMAND.select(hash, textureId);
    }

    private static void sendModelSelection(Hash256 hash, String textureId) {
        STATE_REPORTER.setAnimation("");
        com.elfmcys.ysm.YesSteveModel.LOGGER.info(
                "Sending model selection request modelId={} textureId={} intrinsicDefault={}",
                hash == null ? "<intrinsic-default>" : hash,
                textureId == null ? "<null>" : textureId,
                hash == null);
        var request = SelectModelRequest.newBuilder()
                .setTextureId(textureId);
        if (hash == null) {
            request.setIntrinsicDefault(true);
        } else {
            request.setModelId(ByteBuffer.wrap(hash.bytes()));
        }
        send(ProtocolMessages.SELECT_MODEL_REQUEST_ID, request.build());
    }

    private static long monotonicMillis() {
        return System.nanoTime() / 1_000_000L;
    }

    public static void updateStar(Hash256 hash, boolean add) {
        send(ProtocolMessages.UPDATE_STARRED_MODEL_REQUEST_ID,
                UpdateStarredModelRequest.newBuilder()
                        .setModelHash(ByteBuffer.wrap(hash.bytes()))
                        .setOperation(add ? StarredModelOperation.STARRED_MODEL_OPERATION_ADD
                                : StarredModelOperation.STARRED_MODEL_OPERATION_REMOVE)
                        .build());
    }

    public static void submitRouletteExpression(Entity entity, String expression) {
        send(ProtocolMessages.SUBMIT_ROULETTE_EXPRESSION_REQUEST_ID,
                SubmitRouletteExpressionRequest.newBuilder()
                        .setTarget(EntityRefEncoder.encode(entity.getId()))
                        .setExpression(expression).build());
    }

    public static void emitMolangSync(FloatList values) {
        var message = EmitMolangSync.newBuilder();
        values.forEach((float value) -> message.addArguments(value));
        send(ProtocolMessages.EMIT_MOLANG_SYNC_ID, message.build());
    }

    public static void swingHand(InteractionHand hand) {
        send(ProtocolMessages.SWING_HAND_REQUEST_ID, SwingHandRequest.newBuilder()
                .setHand(hand == InteractionHand.MAIN_HAND ? Hand.HAND_MAIN : Hand.HAND_OFF)
                .build());
    }

    public static void reportRoamingChanges(int modelKey, Object2FloatMap<String> values) {
        // The reporter samples the authoritative local roaming structure on the next client tick.
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void send(int id, ProtoMessage message) {
        var spec = ProtocolMessages.REGISTRY.find(id).orElse(null);
        if (spec != null && spec.messageType().isInstance(message)
                && NetworkHandler.isRemoteChannelPresent()
                && ClientSessionRuntime.businessSession().isPresent()) {
            try {
                NetworkHandler.sendToServer(message);
            } catch (RuntimeException failure) {
                com.elfmcys.ysm.YesSteveModel.LOGGER.warn(
                        "Failed to send model selection/network request type={}",
                        message.getClass().getSimpleName(), failure);
            }
        }
    }
}
