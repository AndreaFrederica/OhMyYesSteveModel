package com.elfmcys.ysm.mixin.client;

import com.elfmcys.ysm.natives.NativeProfiler;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class GameRendererMixin {
    @Unique
    private boolean ysm$profileFrameActive;

    @Shadow private float fov;
    @Shadow private float oldFov;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void ysm$initializeFov(CallbackInfo ci) {
        // A paused world can render before the first tickFov. Vanilla's zero-initialized
        // multipliers then produce a 0-degree perspective (Infinity, followed by NaN).
        // Seed both interpolation endpoints with the neutral multiplier; leave later
        // ticks, Forge FOV events and any other mod's explicit initialization alone.
        if (fov == 0.0F && oldFov == 0.0F) {
            fov = 1.0F;
            oldFov = 1.0F;
        }
    }

    @Inject(method = "render(FJZ)V", at = @At("HEAD"))
    private void beforeRender(float partialTicks, long nanoTime, boolean renderLevel, CallbackInfo ci) {
        ysm$profileFrameActive = NativeProfiler.beginFrame();
    }

    @Inject(method = "render(FJZ)V", at = @At("RETURN"))
    private void afterRender(float partialTicks, long nanoTime, boolean renderLevel, CallbackInfo ci) {
        NativeProfiler.endFrame(ysm$profileFrameActive);
        ysm$profileFrameActive = false;
    }
}
