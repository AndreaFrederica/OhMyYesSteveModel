package com.elfmcys.ysm.client.event;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.elfmcys.ysm.client.entity.CustomPlayerEntity;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.client.renderer.CustomPlayerRenderer;
import com.elfmcys.ysm.config.ClientConfig;
import com.elfmcys.ysm.event.api.SpecialPlayerRenderEvent;
import com.elfmcys.ysm.mclib.utils.Interpolations;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

@Mod.EventBusSubscriber(value = Side.CLIENT)
public class RenderFirstPlayerBackground {
    /**
     * 因为 RenderHandEvent 可有几率会渲染多次，所以为了避免多次渲染，这样设计
     */
    private static boolean ALREADY_RENDERED = false;

    @SubscribeEvent
    public static void onRenderLevelLase(RenderWorldLastEvent event) {
        if (!YesSteveModel.isAvailable()) {
            return;
        }
        ALREADY_RENDERED = false;
    }

    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent event) {
        if (!YesSteveModel.isAvailable()) {
            return;
        }
        if (ClientConfig.DISABLE_SELF_MODEL) {
            return;
        }
        if (ClientConfig.DISABLE_SELF_HANDS) {
            return;
        }
        EntityPlayerSP player = Minecraft.getMinecraft().player;
        if (player == null || ALREADY_RENDERED) {
            return;
        }
        ALREADY_RENDERED = true;
        var cap = player.getCapability(PlayerAnimatableCapabilityProvider.CAP, null);
        if (cap != null) {
            if (!cap.isInitializedAndEnabled()) {
                return;
            }
            String modelId = cap.getModelId();
            ModelRenderTarget model = cap.getModelRenderTarget();
            var variant = cap.getModelVariant();
            if (model == null || variant == null) {
                return;
            }
            // TODO
            /*
            if (!variant.armModel().hasFirstPersonBackground) {
                return;
            }
             */
            CustomPlayerRenderer renderer = RegisterEntityRenderersEvent.getPlayerRenderer();
            CustomPlayerEntity customPlayer = cap;
            if (MinecraftForge.EVENT_BUS.post(new SpecialPlayerRenderEvent(player, customPlayer, modelId))) {
                return;
            }

            if (renderer != null) {
                GlStateManager.pushMatrix();
                if (Minecraft.getMinecraft().gameSettings.viewBobbing) {
                    bobView(event.getPartialTicks(), player);
                }
                GlStateManager.translate(0, -1.5, 0);
                // TODO
                // NativeRenderer.renderModel(vertexConsumer, poseStack.last(), variant.armModel(), variant.armModel().getInitialState(), null, NativeRenderer.RENDER_MODE_BACKGROUND, event.getPackedLight(), OverlayTexture.NO_OVERLAY, 1, 1, 1, 1);
                GlStateManager.popMatrix();
            }
        }
    }

    private static void bobView(float pPartialTicks, EntityPlayerSP player) {
        float walk = player.distanceWalkedModified - player.prevDistanceWalkedModified;
        float walk2 = -(player.distanceWalkedModified + walk * pPartialTicks);
        float lerp = Interpolations.lerp(player.prevCameraYaw, player.cameraYaw, pPartialTicks);
        GlStateManager.translate(-MathHelper.sin(walk2 * (float) Math.PI) * lerp * 0.5F, Math.abs(MathHelper.cos(walk2 * (float) Math.PI) * lerp), 0.0D);
        GlStateManager.rotate(MathHelper.sin(walk2 * (float) Math.PI) * lerp * 3.0F, 0, 0, 1);
        GlStateManager.rotate(Math.abs(MathHelper.cos(walk2 * (float) Math.PI - 0.2F) * lerp) * 5.0F, 1, 0, 0);
    }
}
