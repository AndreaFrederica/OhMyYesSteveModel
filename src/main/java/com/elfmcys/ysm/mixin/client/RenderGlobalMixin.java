package com.elfmcys.ysm.mixin.client;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.client.animation.AnimationParallelTicker;
import com.elfmcys.ysm.util.RenderUtil;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(RenderGlobal.class)
public class RenderGlobalMixin {
    @WrapMethod(method = "renderEntities")
    private void wrapRenderEntities(Entity renderViewEntity, ICamera camera, float partialTicks, Operation<Void> original) {
        if (!YesSteveModel.isAvailable()) {
            original.call(renderViewEntity, camera, partialTicks);
            return;
        }

        RenderUtil.setRenderingLevel(true);
        AnimationParallelTicker.scheduleAll(partialTicks);
        original.call(renderViewEntity, camera, partialTicks);
        AnimationParallelTicker.waitAll();
        RenderUtil.setRenderingLevel(false);
    }
}
