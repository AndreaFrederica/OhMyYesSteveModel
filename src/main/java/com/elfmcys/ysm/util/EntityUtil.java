package com.elfmcys.ysm.util;

import com.elfmcys.ysm.mclib.utils.Interpolations;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;

/**
 * 用于补全一部分旧版缺失的内容
 */
public final class EntityUtil {
    // 旧版没有分离的视角旋转获取方法，但是在 getLook 对 getVectorForRotation 的调用中可见踪迹

    public static float getViewXRot(Entity entity, float partialTick) {
        if (entity instanceof EntityPlayerSP) {
            return entity.rotationPitch;
        }
        // Entity#getLook
        return partialTick == 1.0F ? entity.rotationPitch : Interpolations.lerp(entity.prevRotationPitch, entity.rotationPitch, partialTick);
    }

    public static float getViewYRot(Entity entity, float partialTick) {
        if (entity instanceof EntityPlayerSP clientPlayer) {
            return clientPlayer.rotationYaw;
        }
        if (entity instanceof EntityLivingBase livingEntity) {
            return partialTick == 1.0F ? livingEntity.rotationYawHead : Interpolations.lerp(livingEntity.prevRotationYawHead, livingEntity.rotationYawHead, partialTick);
        }
        // Entity#getLook
        return partialTick == 1.0F ? entity.rotationYaw : Interpolations.lerp(entity.prevRotationYaw, entity.rotationYaw, partialTick);
    }
}
