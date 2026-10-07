package com.elfmcys.ysm.event;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.capability.ModelInfoCapabilityProvider;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.event.entity.living.PotionEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

@Mod.EventBusSubscriber
public class MobEffectSyncEvent {
    @SubscribeEvent
    public static void onAdded(PotionEvent.PotionAddedEvent event) {
        if (!YesSteveModel.isAvailable() || event.getEntity().world.isRemote) {
            return;
        }
        if (event.getEntity() instanceof EntityPlayerMP player && event.getPotionEffect().getPotion() != null) {
            var effectInstance = event.getPotionEffect();
            var cap = player.getCapability(ModelInfoCapabilityProvider.MODEL_INFO_CAP, null);
            if (cap != null) {
                cap.getPropertiesTracker().addEffect(player, effectInstance.getPotion(), effectInstance.getAmplifier() + 1);
            }
        }
    }

    @SubscribeEvent
    public static void onRemoved(PotionEvent.PotionRemoveEvent event) {
        if (!YesSteveModel.isAvailable() || event.getEntity().world.isRemote) {
            return;
        }
        if (event.getEntity() instanceof EntityPlayerMP player && event.getPotion() != null) {
            var cap = player.getCapability(ModelInfoCapabilityProvider.MODEL_INFO_CAP, null);
            if (cap != null) {
                cap.getPropertiesTracker().removeEffect(player, event.getPotion());
            }
        }
    }

    @SubscribeEvent
    public static void onExpired(PotionEvent.PotionExpiryEvent event) {
        if (!YesSteveModel.isAvailable() || event.getEntity().world.isRemote) {
            return;
        }
        if (event.getEntity() instanceof EntityPlayerMP player && event.getPotionEffect() != null && event.getPotionEffect().getPotion() != null) {
            var cap = player.getCapability(ModelInfoCapabilityProvider.MODEL_INFO_CAP, null);
            if (cap != null) {
                cap.getPropertiesTracker().removeEffect(player, event.getPotionEffect().getPotion());
            }
        }
    }
}
