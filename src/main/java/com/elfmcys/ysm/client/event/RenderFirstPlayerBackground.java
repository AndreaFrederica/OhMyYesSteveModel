package com.elfmcys.ysm.client.event;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.elfmcys.ysm.config.ClientConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(value = Dist.CLIENT)
public class RenderFirstPlayerBackground {
    /**
     * 因为 RenderHandEvent 可有几率会渲染多次，所以为了避免多次渲染，这样设计
     */
    private static boolean ALREADY_RENDERED = false;

    @SubscribeEvent
    public static void onRenderFrame(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) ALREADY_RENDERED = false;
    }

    private static long draws;
    private static String status = "not rendered";

    public static long drawCount() { return draws; }
    public static String diagnostics() { return "First-person background: " + status + " (" + draws + ")"; }
    public static void reset() {
        ALREADY_RENDERED = false;
        draws = 0;
        status = "not rendered";
    }

    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent event) {
        var minecraft = Minecraft.getInstance();
        if (!YesSteveModel.isAvailable() || ClientConfig.DISABLE_SELF_MODEL.get()
                || ClientConfig.DISABLE_SELF_HANDS.get() || !minecraft.options.getCameraType().isFirstPerson()) {
            status = "disabled or unavailable";
            return;
        }
        var player = minecraft.player;
        if (player == null || minecraft.getCameraEntity() != player || player.isSpectator()) return;
        if (ALREADY_RENDERED) return;
        // Both hands can post RenderHandEvent. The background is a single camera-relative pass.
        status = "model pending or disabled";
        player.getCapability(PlayerAnimatableCapabilityProvider.CAP).ifPresent(cap -> {
            if (!cap.isInitializedAndEnabled() || cap.getModelRenderTarget() == null || cap.getModelVariant() == null) return;
            var renderer = RegisterEntityRenderersEvent.getFirstPersonArmRenderer();
            ALREADY_RENDERED = true;
            var poseStack = event.getPoseStack();
            poseStack.pushPose();
            try {
                if (minecraft.options.bobView().get()) bobView(poseStack, event.getPartialTick(), player);
                if (renderer.renderBackground(player, cap, poseStack, event.getMultiBufferSource(),
                        event.getPackedLight(), event.getPartialTick())) draws++;
                status = renderer.backgroundStatus();
            } finally {
                poseStack.popPose();
            }
        });
    }

    private static void bobView(PoseStack pMatrixStack, float pPartialTicks, Player player) {
        float walk = player.walkDist - player.walkDistO;
        float walk2 = -(player.walkDist + walk * pPartialTicks);
        float lerp = Mth.lerp(pPartialTicks, player.oBob, player.bob);
        pMatrixStack.translate(-Mth.sin(walk2 * (float) Math.PI) * lerp * 0.5F, Math.abs(Mth.cos(walk2 * (float) Math.PI) * lerp), 0.0D);
        pMatrixStack.mulPose(Axis.ZN.rotationDegrees(Mth.sin(walk2 * (float) Math.PI) * lerp * 3.0F));
        pMatrixStack.mulPose(Axis.XN.rotationDegrees(Math.abs(Mth.cos(walk2 * (float) Math.PI - 0.2F) * lerp) * 5.0F));
    }
}
