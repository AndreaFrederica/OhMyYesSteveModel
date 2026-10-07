package com.elfmcys.ysm.event;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.capability.*;
import com.elfmcys.ysm.config.ServerConfig;
import com.elfmcys.ysm.model.ModelRuntime;
import com.elfmcys.ysm.model.service.ServerModelService;
import com.elfmcys.ysm.network.NetworkHandler;
import com.elfmcys.ysm.network.forge.MinecraftStateHandler;
import com.elfmcys.ysm.network.forge.PlayerStateHandler;
import com.elfmcys.ysm.network.forge.SessionProtocolHandler;
import com.elfmcys.ysm.network.protocol.StarredModelSnapshots;
import com.elfmcys.ysm.proto.network.PlayerStateUpdate;
import java.util.Optional;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.IProjectile;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;

@Mod.EventBusSubscriber
public final class CapabilityEvent {
    private static final ResourceLocation MODEL_INFO_CAP = new ResourceLocation(YesSteveModel.MOD_ID, "model_id");
    private static final ResourceLocation PROJECTILE_MODEL_INFO_CAP = new ResourceLocation(YesSteveModel.MOD_ID, "projectile_model_id");
    private static final ResourceLocation VEHICLE_MODEL_INFO_CAP = new ResourceLocation(YesSteveModel.MOD_ID, "vehicle_model_id");
    private static final ResourceLocation AUTH_MODELS_CAP = new ResourceLocation(YesSteveModel.MOD_ID, "own_models");
    private static final ResourceLocation STAR_MODELS_CAP = new ResourceLocation(YesSteveModel.MOD_ID, "star_models");
    private static final ResourceLocation ANIMATABLE_CAP = new ResourceLocation(YesSteveModel.MOD_ID, "animatable");
    private static final ResourceLocation PROJECTILE_ANIMATABLE_CAP = new ResourceLocation(YesSteveModel.MOD_ID, "projectile_animatable");
    private static final ResourceLocation VEHICLE_ANIMATABLE_CAP = new ResourceLocation(YesSteveModel.MOD_ID, "vehicle_animatable");
    private static final ResourceLocation CLIENT_LAZY_CAP = new ResourceLocation(YesSteveModel.MOD_ID, "client_lazy");

    @SubscribeEvent
    public static void onAttachCapabilityEvent(AttachCapabilitiesEvent<Entity> event) {
        if (!YesSteveModel.isAvailable()) {
            return;
        }

        Entity entity = event.getObject();
        if (entity instanceof EntityPlayer player) {
            if (!entity.world.isRemote && player.getCapability(ModelInfoCapabilityProvider.MODEL_INFO_CAP, null) == null && !event.getCapabilities().containsKey(MODEL_INFO_CAP)) {
                event.addCapability(MODEL_INFO_CAP, new ModelInfoCapabilityProvider());
            }
            if (player.getCapability(AuthModelsCapabilityProvider.AUTH_MODELS_CAP, null) == null && !event.getCapabilities().containsKey(AUTH_MODELS_CAP)) {
                event.addCapability(AUTH_MODELS_CAP, new AuthModelsCapabilityProvider());
            }
            if (player.getCapability(StarModelsCapabilityProvider.STAR_MODELS_CAP, null) == null && !event.getCapabilities().containsKey(STAR_MODELS_CAP)) {
                event.addCapability(STAR_MODELS_CAP, new StarModelsCapabilityProvider());
            }
        } else if (entity instanceof IProjectile) {
            if (!entity.world.isRemote && entity.getCapability(ProjectileModelInfoCapabilityProvider.CAP, null) == null && !event.getCapabilities().containsKey(PROJECTILE_MODEL_INFO_CAP)) {
                event.addCapability(PROJECTILE_MODEL_INFO_CAP, new ProjectileModelInfoCapabilityProvider());
            }
        } else {
            if (!entity.world.isRemote && entity.getCapability(VehicleModelInfoCapabilityProvider.CAP, null) == null && !event.getCapabilities().containsKey(VEHICLE_MODEL_INFO_CAP)) {
                event.addCapability(VEHICLE_MODEL_INFO_CAP, new VehicleModelInfoCapabilityProvider());
            }
        }

        if (FMLCommonHandler.instance().getSide() == Side.CLIENT && entity.world.isRemote) {
            // 客户端玩家
            if (entity instanceof AbstractClientPlayer clientPlayer
                && clientPlayer.getCapability(PlayerAnimatableCapabilityProvider.CAP, null) == null
                && !event.getCapabilities().containsKey(ANIMATABLE_CAP)) {
                event.addCapability(ANIMATABLE_CAP, new PlayerAnimatableCapabilityProvider(clientPlayer));
                return;
            }

            // 客户端懒加载 cap
            if (entity.getCapability(ClientLazyCapabilityProvider.CAP, null) == null
                    && !event.getCapabilities().containsKey(CLIENT_LAZY_CAP)) {
                var vehicleAnimatable = new VehicleAnimatableCapabilityProvider(entity);
                event.addCapability(VEHICLE_ANIMATABLE_CAP, vehicleAnimatable);

                ProjectileAnimatableCapabilityProvider projectileAnimatable = null;
                if (entity instanceof Entity projectile) {
                    projectileAnimatable = new ProjectileAnimatableCapabilityProvider(projectile);
                    event.addCapability(PROJECTILE_ANIMATABLE_CAP, projectileAnimatable);
                }

                event.addCapability(CLIENT_LAZY_CAP, new ClientLazyCapabilityProvider(vehicleAnimatable, projectileAnimatable));
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerCloned(PlayerEvent.Clone event) {
        if (!YesSteveModel.isAvailable()) {
            return;
        }
        ModelInfoCapability oldModelInfoCap = getModelInfoCap(event.getOriginal());
        AuthModelsCapability oldAuthModelsCap = getAuthModelsCap(event.getOriginal());
        StarModelsCapability oldStarModelsCap = getStarModelsCap(event.getOriginal());

        ModelInfoCapability newModelInfoCap = getModelInfoCap(event.getEntityPlayer());
        AuthModelsCapability newAuthModelsCap = getAuthModelsCap(event.getEntityPlayer());
        StarModelsCapability newStarModelsCap = getStarModelsCap(event.getEntityPlayer());

        if (oldModelInfoCap != null && newModelInfoCap != null) {
            newModelInfoCap.moveFrom(oldModelInfoCap);
        }
        if (oldAuthModelsCap != null && newAuthModelsCap != null) {
            newAuthModelsCap.copyFrom(oldAuthModelsCap);
        }
        if (oldStarModelsCap != null && newStarModelsCap != null) {
            newStarModelsCap.copyFrom(oldStarModelsCap);
        }
    }

    @SubscribeEvent
    public static void onTrackingPlayer(PlayerEvent.StartTracking event) {
        if (!YesSteveModel.isAvailable()) {
            return;
        }
        if (event.getTarget() instanceof EntityPlayerMP trackPlayer) {
            final EntityPlayer player = event.getEntityPlayer();
            ModelInfoCapability cap = getModelInfoCap(trackPlayer);
            if (cap != null) {
                if (!NetworkHandler.isPlayerChannelPresent(trackPlayer) && !cap.isMandatory()) {
                    return;
                }
                buildModelInfoPacket(trackPlayer, cap).ifPresent(packet -> {
                    PlayerStateHandler.sendToObserver(packet, player);
                });
            }
        } else if (event.getTarget() instanceof Entity && event.getTarget() instanceof IProjectile) {
            var projectile = (Entity & IProjectile) event.getTarget();
            ProjectileModelInfoCapability cap = projectile.getCapability(ProjectileModelInfoCapabilityProvider.CAP, null);
            if (cap != null) {
                if (cap.isInitialized()) {
                    NetworkHandler.sendToClientPlayer(
                            MinecraftStateHandler.projectile(projectile.getEntityId(), cap),
                            event.getEntityPlayer());
                }
            }
        } else if (event.getTarget() != null) {
            VehicleModelInfoCapability cap = event.getTarget().getCapability(VehicleModelInfoCapabilityProvider.CAP, null);
            if (cap != null) {
                if (cap.isInitialized()) {
                    NetworkHandler.sendToClientPlayer(
                            MinecraftStateHandler.vehicle(event.getTarget().getEntityId(), cap),
                            event.getEntityPlayer());
                }
            }
        }
    }

    @SubscribeEvent
    public static void onEntityJoinWorld(EntityJoinWorldEvent event) {
        if (!YesSteveModel.isAvailable()) {
            return;
        }
        if (event.getEntity() instanceof EntityPlayerMP serverPlayer) {
            ModelInfoCapability modelInfoCap = getModelInfoCap(serverPlayer);
            if (modelInfoCap != null) {
                if (!NetworkHandler.isPlayerChannelPresent(serverPlayer) && !modelInfoCap.isMandatory()) {
                    modelInfoCap.consumeDirty();
                    return;
                }
                modelInfoCap.stopAnimation(serverPlayer);
                modelInfoCap.consumeDirty();
            }

            AuthModelsCapability authModelsCap = getAuthModelsCap(serverPlayer);
            if (authModelsCap != null) {
                SessionProtocolHandler.refreshGrants(serverPlayer);
            }

            StarModelsCapability starModelCap = getStarModelsCap(serverPlayer);
            if (starModelCap != null) {
                NetworkHandler.sendToClientPlayer(
                        StarredModelSnapshots.create(starModelCap.getStarModels()), serverPlayer);
            }
        }
    }

    /**
     * 同步客户端服务端数据
     */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (!YesSteveModel.isAvailable()) {
            return;
        }
        if (event.phase == TickEvent.Phase.END) {
            var players = FMLCommonHandler.instance().getMinecraftServerInstance().getPlayerList().getPlayers();
            var lowBandwidthUsage = ServerConfig.LOW_BANDWIDTH_USAGE;
            for (EntityPlayerMP player : players) {
                ModelInfoCapability cap = getModelInfoCap(player);
                if (cap != null) {
                    if (!NetworkHandler.isPlayerChannelPresent(player) && !cap.isMandatory()) {
                        cap.consumeDirty();
                        return;
                    }
                    if (cap.consumeDirty()) {
                        cap.getPropertiesTracker().tick(player, false, lowBandwidthUsage);
                        buildModelInfoPacket(player, cap).ifPresent(packet -> {
                            PlayerStateHandler.broadcast(player, packet);
                            if (player.getRidingEntity() != null && player.getRidingEntity().getControllingPassenger() == player) {
                                CapabilityEvent.onVehicleSetModel(player.getRidingEntity(), player);
                            }
                        });
                    } else {
                        cap.getPropertiesTracker().tick(player, true, lowBandwidthUsage);
                    }
                }
            }
            ServerModelService.current().ifPresent(service -> {
                if (FMLCommonHandler.instance().getSide() == Side.SERVER) {
                    var system = ModelRuntime.system();
                    system.tickCatalog();
                    system.tickServerRuntime();
                }
                service.tick();
            });
        }
    }

    public static void onProjectileSetOwner(Entity projectile, EntityPlayerMP owner) {
        ModelInfoCapability ownerCap = owner.getCapability(ModelInfoCapabilityProvider.MODEL_INFO_CAP, null);
        if (ownerCap != null) {
            if (!NetworkHandler.isPlayerChannelPresent(owner) && !ownerCap.isMandatory()) {
                return;
            }
            ProjectileModelInfoCapability cap = projectile.getCapability(ProjectileModelInfoCapabilityProvider.CAP, null);
            if (cap != null) {
                ownerCap.executeWithMolangVars(molangVars -> {
                    cap.init(ownerCap.getModelId(), molangVars);
                    NetworkHandler.broadcastToVisiblePlayers(
                        MinecraftStateHandler.projectile(projectile.getEntityId(), cap), projectile);
                });
            }
        }
    }

    public static void onVehicleSetModel(Entity vehicle, EntityPlayerMP owner) {
        ModelInfoCapability ownerCap = owner.getCapability(ModelInfoCapabilityProvider.MODEL_INFO_CAP, null);
        if (ownerCap != null) {
            if (!NetworkHandler.isPlayerChannelPresent(owner) && !ownerCap.isMandatory()) {
                return;
            }
            VehicleModelInfoCapability cap = vehicle.getCapability(VehicleModelInfoCapabilityProvider.CAP, null);
            if (cap != null) {
                // 失败就丢弃
                ownerCap.getMolangVars().ifPresent(molangVars -> {
                    cap.update(ownerCap.getModelId(), molangVars);
                    NetworkHandler.broadcastToVisiblePlayers(
                            MinecraftStateHandler.vehicle(vehicle.getEntityId(), cap), vehicle);
                });
            }
        }
    }

    private static ModelInfoCapability getModelInfoCap(EntityPlayer player) {
        return player.getCapability(ModelInfoCapabilityProvider.MODEL_INFO_CAP, null);
    }

    private static Optional<PlayerStateUpdate> buildModelInfoPacket(
            EntityPlayerMP player, ModelInfoCapability capability) {
        return ServerModelService.current().flatMap(ServerModelService::catalog)
                .flatMap(snapshot -> ModelInfoSyncAssembler.build(
                        player, capability, snapshot));
    }

    private static AuthModelsCapability getAuthModelsCap(EntityPlayer player) {
        return player.getCapability(AuthModelsCapabilityProvider.AUTH_MODELS_CAP, null);
    }

    private static StarModelsCapability getStarModelsCap(EntityPlayer player) {
        return player.getCapability(StarModelsCapabilityProvider.STAR_MODELS_CAP, null);
    }
}
