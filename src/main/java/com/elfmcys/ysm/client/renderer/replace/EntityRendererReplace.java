package com.elfmcys.ysm.client.renderer.replace;

import com.elfmcys.ysm.capability.VehicleAnimatableCapabilityProvider;
import com.elfmcys.ysm.client.event.RegisterEntityRenderersEvent;
import com.elfmcys.ysm.mclib.utils.Interpolations;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public class EntityRendererReplace {
    public static boolean renderInMixin(Entity entity, double x, double y, double z, float yawIn, float partialTick) {
        var cap = entity.getCapability(VehicleAnimatableCapabilityProvider.CAP, null);
        if (cap != null) {
            if (cap.isInitialized() && cap.isModelPresent()) {
                float yaw = getYaw(entity, yawIn, partialTick);
                RegisterEntityRenderersEvent.getVehicleRenderer().doRender(entity, x, y, z, yaw, partialTick);
                return false;
            }
        }
        return true;
    }

    public static float getYaw(Entity entity, float yawIn, float partialTick) {
        float yaw = yawIn;

        if (entity instanceof EntityLivingBase livingEntity) {
            yaw = getLivingEntityYaw(livingEntity, partialTick);
        } else if (entity instanceof EntityMinecart minecart) {
            yaw = getMinecartYaw(minecart, partialTick, yaw);
        }
        return yaw;
    }

    private static float getLivingEntityYaw(EntityLivingBase livingEntity, float partialTick) {
        float yaw = Interpolations.lerpYaw(livingEntity.prevRenderYawOffset, livingEntity.renderYawOffset, partialTick);
        float headYaw = Interpolations.lerpYaw(livingEntity.prevRotationYawHead, livingEntity.rotationYawHead, partialTick);

        boolean shouldSit = livingEntity.isRiding() && (livingEntity.getRidingEntity() != null && livingEntity.getRidingEntity().shouldRiderSit());
        if (shouldSit && livingEntity.getRidingEntity() instanceof EntityLivingBase vehicle) {
            yaw = Interpolations.lerpYaw(vehicle.prevRenderYawOffset, vehicle.renderYawOffset, partialTick);

            float wrappedYawDiff = MathHelper.wrapDegrees(headYaw - yaw);
            wrappedYawDiff = MathHelper.clamp(wrappedYawDiff, -85.0F, 85.0F);

            yaw = headYaw - wrappedYawDiff;
            if (wrappedYawDiff * wrappedYawDiff > 2500.0F) {
                yaw += wrappedYawDiff * 0.2F;
            }
        }
        return yaw;
    }

    /// {@link net.minecraft.client.renderer.entity.RenderMinecart#doRender(EntityMinecart, double, double, double, float, float)}
    private static float getMinecartYaw(EntityMinecart minecart, float partialTick, float yaw) {
        double interpX = Interpolations.lerp(minecart.lastTickPosX, minecart.posX, partialTick);
        double interpY = Interpolations.lerp(minecart.lastTickPosY, minecart.posY, partialTick);
        double interpZ = Interpolations.lerp(minecart.lastTickPosZ, minecart.posZ, partialTick);
        Vec3d basePos = minecart.getPos(interpX, interpY, interpZ);
        if (basePos != null) {
            Vec3d posFront = minecart.getPosOffset(interpX, interpY, interpZ, 0.3F);
            Vec3d posBack = minecart.getPosOffset(interpX, interpY, interpZ, -0.3F);
            if (posFront == null) {
                posFront = basePos;
            }

            if (posBack == null) {
                posBack = basePos;
            }
            Vec3d offset = posBack.add(-posFront.x, -posFront.y, -posFront.z);
            if (offset.length() != 0) {
                yaw = (float) (Math.atan2(offset.z, offset.x) * 180 / Math.PI);
            }
        }
        return yaw;
    }
}
