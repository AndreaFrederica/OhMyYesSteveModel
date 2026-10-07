package com.elfmcys.ysm.client.event;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.elfmcys.ysm.capability.ProjectileAnimatableCapabilityProvider;
import com.elfmcys.ysm.capability.VehicleAnimatableCapabilityProvider;
import com.elfmcys.ysm.client.compat.touhoulittlemaid.client.TlmClientCompat;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Mod.EventBusSubscriber(value = Side.CLIENT)
public class EntityLoadEvent {
    private static final Cache<Integer, List<Consumer<Entity>>> CACHE = CacheBuilder.newBuilder().expireAfterAccess(30,TimeUnit.SECONDS).build();

    @SubscribeEvent
    public static void onEntityLoadToWorld(final EntityJoinWorldEvent event) {
        if (!YesSteveModel.isAvailable()) {
            return;
        }
        var list = CACHE.getIfPresent(event.getEntity().getEntityId());
        if (list != null) {
            for (var consumer : list) {
                consumer.accept(event.getEntity());
            }
        }
        CACHE.invalidate(event.getEntity().getEntityId());
    }

    public static void onEntityLeaveWorld(int entityId) {
        var world = Minecraft.getMinecraft().world;
        var entity = world == null ? null : world.getEntityByID(entityId);
        if (entity == null) return;

        if (entity instanceof EntityPlayer) {
            var capability = entity.getCapability(PlayerAnimatableCapabilityProvider.CAP, null);
            if (capability != null) capability.reset();
        }
        var projectile = entity.getCapability(ProjectileAnimatableCapabilityProvider.CAP, null);
        if (projectile != null) projectile.reset();
        var vehicle = entity.getCapability(VehicleAnimatableCapabilityProvider.CAP, null);
        if (vehicle != null) vehicle.reset();
        /*
        TlmClientCompat.releaseAnimatable(entity);
        */
        CACHE.invalidate(entityId);
    }

    public static void executeOnEntity(int entityId, Consumer<Entity> consumer) {
        Minecraft.getMinecraft().addScheduledTask(() -> {
            var level = Minecraft.getMinecraft().world;
            if (level != null) {
                var entity = level.getEntityByID(entityId);
                if (entity != null) {
                    consumer.accept(entity);
                } else {
                    addRecoveryHandler(entityId, consumer);
                }
            }
        });
    }

    // 非线程安全
    private static void addRecoveryHandler(int entityId, Consumer<Entity> consumer) {
        var list = CACHE.getIfPresent(entityId);
        if (list == null) {
            list = new ArrayList<>(3);
            CACHE.put(entityId, list);
        }
        list.add(consumer);
    }
}
