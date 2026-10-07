package com.elfmcys.ysm.mixin.client;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.client.renderer.replace.EntityRendererReplace;
import com.elfmcys.ysm.client.renderer.replace.FishingHookRendererReplace;
import com.elfmcys.ysm.client.renderer.replace.ProjectileRendererReplace;
import com.elfmcys.ysm.config.ClientConfig;
import com.elfmcys.ysm.util.RenderUtil;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.IProjectile;
import net.minecraft.entity.projectile.EntityFishHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(RenderManager.class)
public class RenderManagerMixin {
    @WrapWithCondition(
            method = "renderEntity",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/Render;doRender(Lnet/minecraft/entity/Entity;DDDFF)V"
            )
    )
    private boolean replaceRender(Render<?> renderer, Entity entity, double x, double y, double z, float rotationYaw, float partialTicks) {
        if (!YesSteveModel.isAvailable()) {
            return true;
        }

        if (entity instanceof IProjectile && !ClientConfig.DISABLE_PROJECTILE_MODEL) {
            // 鱼漂比较特殊
            if (entity instanceof EntityFishHook hook) {
                return FishingHookRendererReplace.renderInMixin(hook, x, y, z, rotationYaw, partialTicks);
            } else {
                return ProjectileRendererReplace.renderInMixin(entity, x, y, z, rotationYaw, partialTicks);
            }
        }

        if (!ClientConfig.DISABLE_VEHICLE_MODEL) {
            RenderUtil.adjustPassengerPosition(entity, partialTicks);
            return EntityRendererReplace.renderInMixin(entity, x, y, z, rotationYaw, partialTicks);
        }

        return true;
    }
}
