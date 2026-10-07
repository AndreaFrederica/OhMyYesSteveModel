package com.elfmcys.ysm.client.compat.aquaacrobatics;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraftforge.fml.common.Loader;

public final class AACompat {
    private static final String AA_MOD_ID = "aquaacrobatics";
    private static boolean AA_INSTALLED;

    private AACompat() {
    }

    public static void init() {
        if (Loader.isModLoaded(AA_MOD_ID)) AA_INSTALLED = true;
    }

    public static boolean isSwimming(Entity entity) {
        return AA_INSTALLED && AACompatInner.isSwimming(entity);
    }

    private static boolean useAAData(Entity entity) {
        return AA_INSTALLED && AACompatInner.useAAData(entity);
    }

    public static boolean isSwimmingPose(Entity entity) {
        return useAAData(entity) && AACompatInner.isSwimmingPose(entity);
    }

    public static boolean isCrouchingPose(Entity entity) {
        return useAAData(entity) ? AACompatInner.isCrouchingPose(entity) : entity.isSneaking();
    }

    public static boolean isFallFlyingPose(EntityLivingBase entity) {
        return useAAData(entity) ? AACompatInner.isFallFlyingPose(entity) : entity.isElytraFlying();
    }
}
