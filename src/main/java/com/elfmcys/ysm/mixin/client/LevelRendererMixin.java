package com.elfmcys.ysm.mixin.client;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.client.animation.AnimationParallelTicker;
import com.elfmcys.ysm.util.RenderUtil;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class LevelRendererMixin {
    private boolean ysm$invalidProjectionLogged;

    @Inject(method = "renderLevel(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V",
            at = @At(value = "HEAD"))
    private void beforeRenderLevel(PoseStack pMatrixStack, float pPartialTicks, long pFinishTimeNano, boolean pDrawBlockOutline, Camera pActiveRenderInfo, GameRenderer pGameRenderer, LightTexture pLightmap, Matrix4f pProjection, CallbackInfo ci) {
        if (YesSteveModel.isAvailable()) {
            var current = RenderSystem.getProjectionMatrix();
            if ((!pProjection.isFinite() || !current.isFinite()) && !ysm$invalidProjectionLogged) {
                ysm$invalidProjectionLogged = true;
                var window = Minecraft.getInstance().getWindow();
                YesSteveModel.LOGGER.error(
                        "Invalid world projection before entity rendering: argumentFinite={}, currentFinite={}, "
                                + "argument={}, current={}, window={}x{}, gui={}x{}, camera={}, partialTicks={}",
                        pProjection.isFinite(), current.isFinite(), matrixValues(pProjection),
                        matrixValues(current), window.getWidth(), window.getHeight(),
                        window.getGuiScaledWidth(), window.getGuiScaledHeight(),
                        Minecraft.getInstance().options.getCameraType(), pPartialTicks);
            }
            RenderUtil.setRenderingLevel(true);
            AnimationParallelTicker.scheduleAll(pPartialTicks);
        }
    }

    @Inject(method = "renderLevel(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V",
            at = @At(value = "RETURN"))
    private void afterRenderEntities(PoseStack pMatrixStack, float pPartialTicks, long pFinishTimeNano, boolean pDrawBlockOutline, Camera pActiveRenderInfo, GameRenderer pGameRenderer, LightTexture pLightmap, Matrix4f pProjection, CallbackInfo ci) {
        if (YesSteveModel.isAvailable()) {
            AnimationParallelTicker.waitAll();
            RenderUtil.setRenderingLevel(false);
        }
    }

    private static String matrixValues(Matrix4f matrix) {
        var values = new float[16];
        matrix.get(values);
        var invalid = new StringBuilder();
        for (int index = 0; index < values.length; index++) {
            if (!Float.isFinite(values[index])) {
                if (invalid.length() > 0) invalid.append(',');
                invalid.append(index).append('=').append(values[index]);
            }
        }
        return invalid.length() == 0 ? "finite" : invalid.toString();
    }
}
