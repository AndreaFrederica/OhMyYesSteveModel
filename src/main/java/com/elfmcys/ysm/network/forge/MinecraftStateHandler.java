package com.elfmcys.ysm.network.forge;

import com.elfmcys.ysm.capability.ClientLazyCapabilityProvider;
import com.elfmcys.ysm.capability.ProjectileModelInfoCapability;
import com.elfmcys.ysm.capability.VehicleModelInfoCapability;
import com.elfmcys.ysm.client.event.EntityLoadEvent;
import com.elfmcys.ysm.geckolib3.core.molang.util.StringPool;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.network.protocol.ModelReferenceCodec;
import com.elfmcys.ysm.proto.network.EntityRef;
import com.elfmcys.ysm.proto.network.MolangVariable;
import com.elfmcys.ysm.proto.network.ProjectileModelState;
import com.elfmcys.ysm.proto.network.VehicleModelState;
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2FloatOpenHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.entity.Entity;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

public final class MinecraftStateHandler {
    private MinecraftStateHandler() {
    }

    public static ProjectileModelState projectile(
            int entityId, ProjectileModelInfoCapability capability) {
        var message = ProjectileModelState.newBuilder()
                .setEntity(EntityRef.newBuilder()
                        .setEntityId(entityId).build())
                .setModel(ModelReferenceCodec.create(capability.getOwnerModelHash(), null));
        addVariables(message::addMolangVariables, capability.getMolangVarsServerBound());
        return message.build();
    }

    public static VehicleModelState vehicle(
            int entityId, VehicleModelInfoCapability capability) {
        var message = VehicleModelState.newBuilder()
                .setEntity(EntityRef.newBuilder()
                        .setEntityId(entityId).build())
                .setModel(ModelReferenceCodec.create(capability.getOwnerModelHash(), null));
        addVariables(message::addMolangVariables, capability.getMolangVarsServerBound());
        return message.build();
    }

    public static void handleProjectile(ProjectileModelState message,
                                        Supplier<NetworkEvent.Context> contextSupplier) {
        var context = contextSupplier.get();
        if (!ModelReferenceCodec.valid(message.model())) {
            context.setPacketHandled(true);
            return;
        }
        var modelHash = ModelReferenceCodec.read(message.model());
        var variables = clientVariables(message.molangVariables());
        var connection = context.getNetworkManager();
        context.enqueueWork(() -> ClientSessionRuntime.runIfCurrent(connection,
                () -> EntityLoadEvent.executeOnEntity(message.entity().entityId(),
                        entity -> applyProjectile(entity, modelHash, variables))));
        context.setPacketHandled(true);
    }

    public static void handleVehicle(VehicleModelState message,
                                     Supplier<NetworkEvent.Context> contextSupplier) {
        var context = contextSupplier.get();
        if (!ModelReferenceCodec.valid(message.model())) {
            context.setPacketHandled(true);
            return;
        }
        var modelHash = ModelReferenceCodec.read(message.model());
        var variables = clientVariables(message.molangVariables());
        var connection = context.getNetworkManager();
        context.enqueueWork(() -> ClientSessionRuntime.runIfCurrent(connection,
                () -> EntityLoadEvent.executeOnEntity(message.entity().entityId(),
                        entity -> applyVehicle(entity, modelHash, variables))));
        context.setPacketHandled(true);
    }

    private static void addVariables(
            Consumer<MolangVariable> variableConsumer,
            Object2FloatOpenHashMap<String> variables) {
        variables.object2FloatEntrySet().fastForEach(entry -> variableConsumer.accept(
                MolangVariable.newBuilder()
                        .setName(entry.getKey())
                        .setValue(entry.getFloatValue()).build()));
    }

    private static Int2FloatOpenHashMap clientVariables(
            Iterable<MolangVariable> variables) {
        var result = new Int2FloatOpenHashMap();
        for (var variable : variables) {
            result.put(StringPool.computeIfAbsent(variable.name()), variable.value_());
        }
        return result;
    }

    @SideOnly(Side.CLIENT)
    @SuppressWarnings("DataFlowIssue")
    private static void applyProjectile(Entity entity, Hash256 modelHash, Int2FloatOpenHashMap variables) {
        var capability = entity.getCapability(ClientLazyCapabilityProvider.CAP, null);
        if (capability != null) {
            var animatable = capability.getProjectileAnimatableCapabilityProvider().initialize();
            animatable.init(modelHash);
            animatable.initRoamingVars(variables);
        }
    }

    @SideOnly(Side.CLIENT)
    private static void applyVehicle(Entity entity, Hash256 modelHash, Int2FloatOpenHashMap variables) {
        var capability = entity.getCapability(ClientLazyCapabilityProvider.CAP, null);
        if (capability != null) {
            var animatable = capability.getVehicleAnimatableCapabilityProvider().initialize();
            animatable.init(modelHash);
            animatable.initRoamingVars(variables);
        }
    }
}
