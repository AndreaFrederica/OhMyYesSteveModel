package com.elfmcys.ysm.client.compat.swarfare;

import com.atsuishio.superbwarfare.api.event.RenderPlayerArmEvent;
import com.atsuishio.superbwarfare.client.renderer.CustomGunRenderer;
import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.elfmcys.ysm.client.event.RegisterEntityRenderersEvent;
import com.elfmcys.ysm.config.ClientConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import software.bernie.geckolib.cache.object.GeoBone;

public class ReplacePlayerArmRender {
    @SubscribeEvent
    public void onRenderHand(RenderPlayerArmEvent event) {
        if (!YesSteveModel.isAvailable() || ClientConfig.DISABLE_SELF_MODEL.get()
                || ClientConfig.DISABLE_SELF_HANDS.get() || !event.getTransformType().firstPerson()) return;
        LocalPlayer player = event.getLocalPlayer();
        if (player == null) return;
        player.getCapability(PlayerAnimatableCapabilityProvider.CAP).ifPresent(cap -> {
            if (!cap.isInitializedAndEnabled() || cap.getModelRenderTarget() == null || cap.getModelVariant() == null) return;
            HumanoidArm arm = event.getArm();
            PoseStack poseStack = event.getStack();
            GeoBone bone = event.getBone();
            if (bone == null) return;
            poseStack.pushPose();
            try {
                float side = arm == HumanoidArm.LEFT ? -1 : 1;
                poseStack.translate(side * CustomGunRenderer.SCALE_RECIPROCAL, 2.0f * CustomGunRenderer.SCALE_RECIPROCAL, 0);
                poseStack.translate(side * 0.275, 0.0625, 0);
                if (event.isUseOldHandRender()) {
                    poseStack.translate((bone.getPivotX() - 1) / 16f, (bone.getPivotY() - 2) / 16f, bone.getPivotZ() / 16f);
                } else {
                    poseStack.translate(bone.getPivotX() / 16f, (bone.getPivotY() + 7) / 16f, bone.getPivotZ() / 16f);
                    poseStack.mulPose(Axis.YP.rotationDegrees(180));
                    poseStack.mulPose(Axis.ZP.rotationDegrees(180));
                }
                if (RegisterEntityRenderersEvent.getFirstPersonArmRenderer().render(player, cap, arm,
                        poseStack, event.getCurrentBuffer(), event.getPackedLightIn(), Minecraft.getInstance().getPartialTick())) {
                    event.setCanceled(true);
                }
            } finally {
                poseStack.popPose();
            }
        });
    }
}
