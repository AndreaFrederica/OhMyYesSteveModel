package com.elfmcys.ysm.network.forge;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.capability.ModelInfoCapabilityProvider;
import com.elfmcys.ysm.capability.ModelSelectionService;
import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.elfmcys.ysm.capability.StarModelsCapabilityProvider;
import com.elfmcys.ysm.client.animation.molang.CustomMolangParser;
/*
import com.elfmcys.ysm.client.compat.touhoulittlemaid.TlmCommonCompat;
*/
import com.elfmcys.ysm.event.CapabilityEvent;
import com.elfmcys.ysm.geckolib3.core.molang.value.IValue;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.service.ServerModelService;
import com.elfmcys.ysm.molang.parser.ParseException;
import com.elfmcys.ysm.network.NetworkHandler;
import com.elfmcys.ysm.network.protocol.StarredModelSnapshots;
import com.elfmcys.ysm.proto.network.*;
import com.elfmcys.ysm.proto.network.EmitMolangSync;
import com.elfmcys.ysm.proto.network.EntityAnimationActionRequest;
import com.elfmcys.ysm.proto.network.EntityRef;
import com.elfmcys.ysm.proto.network.ExecuteMolangEvent;
import com.elfmcys.ysm.proto.network.Hand;
import com.elfmcys.ysm.proto.network.MolangSyncEvent;
import com.elfmcys.ysm.proto.network.StarredModelOperation;
import com.elfmcys.ysm.proto.network.StarredModelsSnapshot;
import com.elfmcys.ysm.proto.network.SubmitRouletteExpressionRequest;
import com.elfmcys.ysm.proto.network.SwingHandRequest;
import com.elfmcys.ysm.proto.network.UpdateStarredModelRequest;
import com.elfmcys.ysm.util.ProtoBytes;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.floats.FloatList;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.EnumHand;

public final class ControlHandler {
    private static final int MAX_MOLANG_TARGETS = 1024;
    private static final int MAX_MOLANG_ARGUMENTS = 16;
    private static final int MAX_EXPRESSION_LENGTH = 4096;

    private ControlHandler() {
    }

    public static void handleStarredModels(StarredModelsSnapshot message,
                                           Supplier<NetworkEvent.Context> contextSupplier) {
        var context = contextSupplier.get();
        var hashes = readHashSet(message.modelHashes());
        var connection = context.getNetworkManager();
        if (hashes != null) context.enqueueWork(() ->
                ClientSessionRuntime.runIfCurrent(connection, () -> {
                    var player = Minecraft.getMinecraft().player;
                    if (player != null) {
                        var capability = player.getCapability(StarModelsCapabilityProvider.STAR_MODELS_CAP, null);
                        if (capability != null) capability.setStarModels(hashes);
                    }
                }));
        context.setPacketHandled(true);
    }

    public static void handleUpdateStar(UpdateStarredModelRequest message,
                                        Supplier<NetworkEvent.Context> contextSupplier) {
        var context = contextSupplier.get();
        var sender = context.getSender();
        if (sender != null && message.modelHash().remaining() == Hash256.SIZE
                && message.operation() != StarredModelOperation.STARRED_MODEL_OPERATION_UNSPECIFIED) {
            var hash = new Hash256(ProtoBytes.copy(message.modelHash()));
            enqueueServer(context, sender, () -> {
                var capability = sender.getCapability(StarModelsCapabilityProvider.STAR_MODELS_CAP, null);
                if (capability != null) {
                    if (message.operation() == StarredModelOperation.STARRED_MODEL_OPERATION_ADD) {
                        capability.addModel(hash);
                    } else {
                        capability.removeModel(hash);
                    }
                }
            });
        }
        context.setPacketHandled(true);
    }

    public static void handleEntityAnimation(EntityAnimationActionRequest message,
                                             Supplier<NetworkEvent.Context> contextSupplier) {
        var context = contextSupplier.get();
        var sender = context.getSender();
        /*
        if (sender != null && validNonPlayerTarget(message.target())) {

            enqueueServer(context, sender, () -> {
                var entity = sender.getServerWorld().getEntityByID(message.target().entityId());
                if (!TlmCommonCompat.canControlMaid(entity, sender)) return;
                if (message.hasStop() && message.stop()) {
                    TlmCommonCompat.setRouletteAnim(entity, "", -1);
                } else if (message.hasPlay() && message.play().classificationId().length() <= 128) {
                    TlmCommonCompat.setRouletteAnim(entity, message.play().classificationId(),
                            message.play().animationIndex());
                }
            });
        }
        */
        context.setPacketHandled(true);
    }

    public static void handleExecuteMolang(ExecuteMolangEvent message,
                                           Supplier<NetworkEvent.Context> contextSupplier) {
        var context = contextSupplier.get();
        if (message.targets().size() <= MAX_MOLANG_TARGETS
                && message.expression().length() <= MAX_EXPRESSION_LENGTH) {
            var connection = context.getNetworkManager();
            context.enqueueWork(() -> ClientSessionRuntime.runIfCurrent(
                    connection, () -> executeMolang(message)));
        }
        context.setPacketHandled(true);
    }

    public static void handleSubmitRoulette(SubmitRouletteExpressionRequest message,
                                            Supplier<NetworkEvent.Context> contextSupplier) {
        var context = contextSupplier.get();
        var sender = context.getSender();
        if (sender != null && message.hasTarget() && !message.target().orElseThrow().hasPlayerId()
                && message.expression().length() <= MAX_EXPRESSION_LENGTH) {
            enqueueServer(context, sender, () -> {
                var entity = sender.getServerWorld().getEntityByID(message.target().orElseThrow().entityId());
                /*
                if (entity != sender && !TlmCommonCompat.canControlMaid(entity, sender)) return;
                */
                if (entity != sender) return;
                var event = ExecuteMolangEvent.newBuilder()
                        .addTargets(EntityRef.newBuilder()
                                .setEntityId(entity.getEntityId()).build())
                        .setExpression(message.expression())
                        .build();
                NetworkHandler.broadcastToVisiblePlayers(event, entity);
            });
        }
        context.setPacketHandled(true);
    }

    public static void handleEmitMolangSync(EmitMolangSync message,
                                            Supplier<NetworkEvent.Context> contextSupplier) {
        var context = contextSupplier.get();
        var sender = context.getSender();
        if (sender != null && validArguments(message.arguments())) {
            enqueueServer(context, sender, () -> {
                var event = MolangSyncEvent.newBuilder()
                        .setSubject(EntityRef.newBuilder()
                                .setEntityId(sender.getEntityId()).build());
                message.arguments().forEach(event::addArguments);
                NetworkHandler.broadcastToVisiblePlayersAndSelf(event.build(), sender);
            });
        }
        context.setPacketHandled(true);
    }

    public static void handleMolangSync(MolangSyncEvent message,
                                        Supplier<NetworkEvent.Context> contextSupplier) {
        var context = contextSupplier.get();
        if (message.hasSubject() && !message.subject().orElseThrow().hasPlayerId()
                && validArguments(message.arguments())) {
            var connection = context.getNetworkManager();
            context.enqueueWork(() -> ClientSessionRuntime.runIfCurrent(connection, () -> {
                var level = Minecraft.getMinecraft().world;
                Entity entity = level == null ? null : level.getEntityByID(message.subject().orElseThrow().entityId());
                if (entity != null) {
                    var capability = entity.getCapability(PlayerAnimatableCapabilityProvider.CAP, null);
                    if (capability == null) return;
                    var args = new FloatArrayList(message.arguments().size());
                    message.arguments().forEach(args::add);
                    capability.molangSync(args);
                }
            }));
        }
        context.setPacketHandled(true);
    }

    public static void handleSwingHand(SwingHandRequest message,
                                       Supplier<NetworkEvent.Context> contextSupplier) {
        var context = contextSupplier.get();
        var sender = context.getSender();
        if (sender != null && message.hand() != Hand.HAND_UNSPECIFIED) {
            enqueueServer(context, sender, () -> sender.swingArm(
                    message.hand() == Hand.HAND_MAIN ? EnumHand.MAIN_HAND : EnumHand.OFF_HAND));
        }
        context.setPacketHandled(true);
    }

    private static void enqueueServer(NetworkEvent.Context context,
                                      EntityPlayerMP sender, Runnable action) {
        var connection = context.getNetworkManager();
        var service = ServerModelService.current().orElse(null);
        var owner = service == null ? null
                : service.session(sender, connection).orElse(null);
        if (owner != null) {
            context.enqueueWork(() -> service.runIfCurrent(
                    sender, connection, owner, () -> {
                        if (owner.active()) {
                            action.run();
                        }
                    }));
        }
    }

    public static void applyAcceptedModelSelection(EntityPlayerMP sender, Hash256 hash,
                                                   String textureId) {
        applyAcceptedModelSelection(sender, hash, textureId, true);
    }

    static void applyAcceptedModelSelection(EntityPlayerMP sender, Hash256 hash,
                                            String textureId, boolean publishState) {
        var modelCapability = sender.getCapability(ModelInfoCapabilityProvider.MODEL_INFO_CAP, null);
        if (modelCapability == null) {
            return;
        }
        ServerModelService.instance().catalog().ifPresent(snapshot -> {
            if (hash == null) {
                ModelSelectionService.selectBuiltinDefault(modelCapability, snapshot, textureId);
            } else {
                modelCapability.setModelAndTexture(hash, textureId);
            }
            modelCapability.applyClientAnimation("");
        });
        if (publishState && PlayerStateHandler.sendAuthoritativeFull(sender, true)
                && sender.getRidingEntity() != null && sender.getRidingEntity().getControllingPassenger() == sender) {
            CapabilityEvent.onVehicleSetModel(sender.getRidingEntity(), sender);
        }
    }

    static Set<Hash256> readHashSet(Iterable<ByteBuffer> values) {
        var result = new HashSet<Hash256>();
        var count = 0;
        for (var value : values) {
            if (++count > StarredModelSnapshots.MAX_MODEL_SET_SIZE
                    || value.remaining() != Hash256.SIZE) return null;
            if (!result.add(new Hash256(ProtoBytes.copy(value)))) return null;
        }
        return result;
    }

    private static boolean validNonPlayerTarget(EntityRef target) {
        return target != null && !target.hasPlayerId();
    }

    private static boolean validArguments(FloatList values) {
        if (values.size() > MAX_MOLANG_ARGUMENTS) return false;
        for (var i = 0; i < values.size(); i++) if (!Float.isFinite(values.getFloat(i))) return false;
        return true;
    }

    private static void executeMolang(ExecuteMolangEvent message) {
        var level = Minecraft.getMinecraft().world;
        if (level == null) return;
        for (var target : message.targets()) {
            if (target.hasPlayerId()) continue;
            Entity entity = level.getEntityByID(target.entityId());
            if (entity instanceof EntityPlayer player) {
                var capability = player.getCapability(PlayerAnimatableCapabilityProvider.CAP, null);
                if (capability != null) {
                    try {
                        IValue value = CustomMolangParser.parseSingleExpressionUnsafe(message.expression());
                        capability.executeMolangExp(value, true, false, null);
                    } catch (ParseException error) {
                        YesSteveModel.LOGGER.error("Failed to execute molang " + message.expression(), error);
                    }
                }
            }
            /*
            else if (TlmCommonCompat.isMaid(entity)) {
                TlmCommonCompat.handleExecuteMolang(entity, message.expression());
            }
            */
        }
    }
}
