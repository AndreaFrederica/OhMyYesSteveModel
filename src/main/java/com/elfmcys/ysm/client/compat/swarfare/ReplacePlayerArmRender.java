package com.elfmcys.ysm.client.compat.swarfare;

/*
import com.atsuishio.superbwarfare.api.event.RenderPlayerArmEvent;
import com.atsuishio.superbwarfare.client.renderer.CustomGunRenderer;
import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.capability.PlayerAnimatableCapabilityProvider;
import com.elfmcys.ysm.client.event.RegisterEntityRenderersEvent;
import com.elfmcys.ysm.client.model.locator.FirstPersonLocator;
import com.elfmcys.ysm.client.renderer.CustomFirstPersonArmRenderer;
import com.elfmcys.ysm.config.ClientConfig;
import com.elfmcys.ysm.geckolib3.model.AnimatedGeoModel;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import software.bernie.geckolib.cache.object.GeoBone;

public class ReplacePlayerArmRender {
//    TODO
//    @SubscribeEvent
//    public void onRenderHand(RenderPlayerArmEvent event) {
//        if (!YesSteveModel.isAvailable()) {
//            return;
//        }
//        if (ClientConfig.DISABLE_SELF_MODEL.get()) {
//            return;
//        }
//        if (ClientConfig.DISABLE_SELF_HANDS.get()) {
//            return;
//        }
//        LocalPlayer player = event.getLocalPlayer();
//        if (player == null) {
//            return;
//        }
//        event.setCanceled(true);
//
//        player.getCapability(PlayerAnimatableCapabilityProvider.CAP).ifPresent(cap -> {
//            HumanoidArm arm = event.getArm();
//            var model = cap.getLoadedGeoModel();
//            var variant = cap.getModelVariant();
//            if (model == null || variant == null || !hasArmBone(arm, model)) {
//                return;
//            }
//            PoseStack poseStack = event.getStack();
//            boolean useOldHandRender = event.isUseOldHandRender();
//            GeoBone bone = event.getBone();
//            MultiBufferSource multiBufferSource = event.getCurrentBuffer();
//            float partialTick = Minecraft.getInstance().getPartialTick();
//            CustomFirstPersonArmRenderer armRenderer = RegisterEntityRenderersEvent.getFirstPersonArmRenderer();
//
//            if (arm == HumanoidArm.LEFT) {
//                poseStack.translate(-1.0f * CustomGunRenderer.SCALE_RECIPROCAL, 2.0f * CustomGunRenderer.SCALE_RECIPROCAL, 0.0f);
//                poseStack.translate(-0.275, 0.0625, 0);
//            } else {
//                poseStack.translate(CustomGunRenderer.SCALE_RECIPROCAL, 2.0f * CustomGunRenderer.SCALE_RECIPROCAL, 0.0f);
//                poseStack.translate(0.275, 0.0625, 0);
//            }
//
//            if (useOldHandRender) {
//                poseStack.translate((bone.getPivotX() - 1) / 16f, (bone.getPivotY() - 2) / 16f, bone.getPivotZ() / 16f);
//            } else {
//                poseStack.translate(bone.getPivotX() / 16f, (bone.getPivotY() + 7) / 16f, bone.getPivotZ() / 16f);
//                poseStack.mulPose(Axis.YP.rotationDegrees(180));
//                poseStack.mulPose(Axis.ZP.rotationDegrees(180));
//            }
//
//             armRenderer.render(player, cap, arm, poseStack, multiBufferSource, event.getPackedLightIn(), partialTick);
//        });
//    }
//
//    private boolean hasArmBone(HumanoidArm arm, AnimatedGeoModel model) {
//        if (arm == HumanoidArm.LEFT) {
//            return !model.locatorGroup(FirstPersonLocator.get().leftArm).isEmpty();
//        } else {
//            return !model.locatorGroup(FirstPersonLocator.get().rightArm).isEmpty();
//        }
//    }
}
*/
