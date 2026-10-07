package com.elfmcys.ysm.event;

import com.elfmcys.ysm.YesSteveModel;
import net.minecraft.entity.EntityLivingBase;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

@Mod.EventBusSubscriber(modid = YesSteveModel.MOD_ID)
public class LivingShieldBlockEvent {
    public static final String COOLDOWN = "ysm$shield_block_cooldown";

    public static void onShieldBlock(EntityLivingBase entity) {
        entity.getEntityData().setInteger(COOLDOWN, 5);
    }

    @SubscribeEvent
    public static void onLivingTick(LivingEvent.LivingUpdateEvent event) {
        EntityLivingBase entity = event.getEntityLiving();
        if (entity.getEntityData().hasKey(COOLDOWN)) {
            int cooldown = entity.getEntityData().getInteger(COOLDOWN);
            if (cooldown > 0) {
                entity.getEntityData().setInteger(COOLDOWN, cooldown - 1);
            } else {
                entity.getEntityData().removeTag(COOLDOWN);
            }
        }
    }

    public static boolean inShieldBlockCooldown(EntityLivingBase entity) {
        return entity.getEntityData().hasKey(COOLDOWN);
    }
}
