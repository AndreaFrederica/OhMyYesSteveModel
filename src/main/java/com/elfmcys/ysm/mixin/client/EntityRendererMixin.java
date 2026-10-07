package com.elfmcys.ysm.mixin.client;

import com.elfmcys.ysm.natives.NativeProfiler;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(EntityRenderer.class)
public class EntityRendererMixin {
    @WrapMethod(method = "updateCameraAndRender(FJ)V")
    private void wrapRender(float partialTicks, long nanoTime, Operation<Void> original) {
        boolean profileFrameActive = NativeProfiler.beginFrame();
        original.call(partialTicks, nanoTime);
        NativeProfiler.endFrame(profileFrameActive);
    }
}
