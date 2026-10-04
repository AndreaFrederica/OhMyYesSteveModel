package com.elfmcys.ysm.geckolib3.geo;

import com.elfmcys.ysm.geckolib3.core.util.Color;
import com.elfmcys.ysm.geckolib3.model.AnimatableEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.Entity;

public abstract class GeoEntityRenderer<TEntity extends Entity, T extends AnimatableEntity<TEntity>> extends EntityRenderer<TEntity> implements IGeoRenderer<T> {
    public GeoEntityRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    public void render(T animatable, float yaw, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        var mc = Minecraft.getInstance();
        if(animatable instanceof com.elfmcys.ysm.client.entity.CustomEntity<?> custom) {
            var scene=custom.updateGeneralMesh(partialTick);
            if(scene!=null) {
                if(mc.player!=null && !custom.getEntity().isInvisibleTo(mc.player)) {
                    poseStack.pushPose();
                    try {
                        poseStack.mulPose(Axis.YP.rotationDegrees(180-yaw));
                        com.elfmcys.ysm.client.renderer.SceneEntityRenderer.render(custom,scene,poseStack,bufferSource,packedLight);
                    } finally { poseStack.popPose(); }
                }
                super.render(animatable.getEntity(),yaw,partialTick,poseStack,bufferSource,packedLight);
                return;
            }
        }
        var data = animatable.update(partialTick);
        if (data != null && mc.player != null) {
            var entity = animatable.getEntity();
            var bodyVisible = !entity.isInvisibleTo(mc.player);
            var glowing = mc.shouldEntityAppearGlowing(entity);
            var renderType = getRenderType(data.texture, bodyVisible, glowing,
                    data.modelState.hasTranslucentVertices());

            if (renderType != null && (bodyVisible || glowing)) {
                poseStack.pushPose();
                try {
                    poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - yaw));
                    preRender(data, animatable, poseStack, bufferSource,
                            packedLight, getPackedOverlay(entity, 0), Color.WHITE);
                    if (data.modelState.isValid()) {
                        var event = new com.elfmcys.ysm.api.rendering.v0.event.RenderModelEvent(entity,
                                animatable.renderTargetKind(), data, bufferSource, renderType, poseStack,
                                packedLight, getPackedOverlay(entity, 0), Color.WHITE.getColor());
                        if (!com.elfmcys.ysm.YesSteveModel.postEvent(event)) {
                            render(data, animatable, renderType, poseStack, bufferSource,
                                    packedLight, getPackedOverlay(entity, 0), Color.WHITE);
                        }
                    }
                    postRender(data, animatable, poseStack, bufferSource,
                            packedLight, getPackedOverlay(entity, 0), Color.WHITE);
                } finally {
                    poseStack.popPose();
                }
            }
        }
        super.render(animatable.getEntity(), yaw, partialTick, poseStack, bufferSource, packedLight);
    }

    public static int getPackedOverlay(Entity entity, float uIn) {
        return OverlayTexture.pack(OverlayTexture.u(uIn), OverlayTexture.v(false));
    }
}
