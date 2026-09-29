package com.elfmcys.ysm.client.renderer;

import com.elfmcys.ysm.capability.PlayerAnimatableCapability;
import com.elfmcys.ysm.client.entity.CustomFirstPersonArmEntity;
import com.elfmcys.ysm.event.api.SpecialPlayerRenderEvent;
import com.elfmcys.ysm.geckolib3.core.util.Color;
import com.elfmcys.ysm.geckolib3.geo.CustomTranslucentRenderType;
import com.elfmcys.ysm.natives.render.NativeRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraftforge.common.MinecraftForge;

public class CustomFirstPersonArmRenderer {
    private CustomFirstPersonArmEntity armEntity = null;
    private CustomFirstPersonArmEntity backgroundEntity = null;
    private String status = "not rendered";
    private String backgroundStatus = "not rendered";

    public String backgroundStatus() { return backgroundStatus; }

    public String status() { return status; }

    public void clear() {
        if (armEntity != null) armEntity.release();
        if (backgroundEntity != null) backgroundEntity.release();
        armEntity = null;
        backgroundEntity = null;
        status = "not rendered";
        backgroundStatus = "not rendered";
    }

    public boolean render(LocalPlayer player, PlayerAnimatableCapability cap, HumanoidArm arm,
                       PoseStack poseStack, MultiBufferSource bufferSource,
                       int packedLight, float partialTick) {
        return renderPart(player, cap, arm, poseStack, bufferSource, packedLight, partialTick);
    }

    public boolean renderBackground(LocalPlayer player, PlayerAnimatableCapability cap,
                                    PoseStack poseStack, MultiBufferSource bufferSource,
                                    int packedLight, float partialTick) {
        // Keep arm diagnostics independent of the background pass.
        String armStatus = status;
        try {
            return renderPart(player, cap, null, poseStack, bufferSource, packedLight, partialTick);
        } finally {
            backgroundStatus = status;
            status = armStatus;
        }
    }

    private boolean renderPart(LocalPlayer player, PlayerAnimatableCapability cap, HumanoidArm arm,
                               PoseStack poseStack, MultiBufferSource bufferSource,
                               int packedLight, float partialTick) {
        if (armEntity == null || armEntity.getEntity() != player) {
            clear();
            armEntity = new CustomFirstPersonArmEntity(player, cap);
        }
        if (arm == null && backgroundEntity == null) {
            backgroundEntity = new CustomFirstPersonArmEntity(player, cap);
        }
        var entity = arm == null ? backgroundEntity : armEntity;
        entity.checkModelUpdate();
        var model = entity.getLoadedGeoModel();
        var locators = com.elfmcys.ysm.client.model.locator.FirstPersonLocator.get();
        var locator = arm == null ? locators.background : arm == HumanoidArm.LEFT ? locators.leftArm : locators.rightArm;
        String part = arm == null ? "BACKGROUND" : arm.name();
        if (model == null || model.getModel().locatorType() != locators || model.locatorGroup(locator).isEmpty()) {
            status = model == null ? "model pending" : "no " + part + " locator";
            return false;
        }
        if (arm == null) entity.selectBackground();
        else entity.selectArm(arm);
        var data = entity.update(partialTick);
        if (data == null || !data.modelState.isValid()) {
            status = "state unavailable";
            return false;
        }

        var renderEvent = new SpecialPlayerRenderEvent(player, cap, cap.getModelId());
        if (MinecraftForge.EVENT_BUS.post(renderEvent)) {
            status = "extension canceled";
            return false;
        }

        var textureLocation = renderEvent.getTextureLocationOverride() == null ? data.texture : renderEvent.getTextureLocationOverride();
        var vertexConsumer = bufferSource.getBuffer(CustomTranslucentRenderType.create(textureLocation));

        poseStack.pushPose();
        try {
            if (arm == null) {
                // Background uses the model's first-person origin, with no hand/swing offset.
                poseStack.translate(0, -1.5, 0);
            } else if (arm == HumanoidArm.LEFT) {
                poseStack.translate(0.25, 1.8, 0);
            } else {
                poseStack.translate(-0.25, 1.8, 0);
            }
            if (arm != null) poseStack.scale(-1, -1, 1);

            var modelEvent = new com.elfmcys.ysm.api.rendering.v0.event.RenderModelEvent(player,
                    entity.renderTargetKind(), data, bufferSource, CustomTranslucentRenderType.create(textureLocation),
                    poseStack, packedLight, OverlayTexture.NO_OVERLAY, Color.WHITE.getColor());
            if (com.elfmcys.ysm.YesSteveModel.postEvent(modelEvent)) {
                // A canceled YSM draw intentionally suppresses this part, including its vanilla replacement.
                status = part + " extension suppressed";
                return true;
            }
            NativeRenderer.render(vertexConsumer, poseStack.last(), data.modelState.getNativeState(), data.modelState.getVertexCount(),
                    packedLight, OverlayTexture.NO_OVERLAY, Color.WHITE.getColor(), data.ctx.nativeType());
            status = part + " vertices=" + data.modelState.getVertexCount();
            return true;
        } finally {
            poseStack.popPose();
        }
    }
}
