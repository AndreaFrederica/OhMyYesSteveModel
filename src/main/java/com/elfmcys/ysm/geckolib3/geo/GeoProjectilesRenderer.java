package com.elfmcys.ysm.geckolib3.geo;

import com.elfmcys.ysm.geckolib3.core.util.Color;
import com.elfmcys.ysm.geckolib3.model.AnimatableEntity;
import com.elfmcys.ysm.natives.render.RenderContextType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;

public abstract class GeoProjectilesRenderer<TEntity extends Entity, T extends AnimatableEntity<TEntity>> extends Render<TEntity> implements IGeoRenderer<T> {
    public GeoProjectilesRenderer(RenderManager manager) {
        super(manager);
    }

    public void render(T animatable, double x, double y, double z, float yaw, float partialTick) {
        var mc = Minecraft.getMinecraft();
        var data = animatable.update(partialTick);
        if (data != null && mc.player != null) {
            var entity = animatable.getEntity();
            var bodyVisible = !entity.isInvisible() && !entity.isInvisibleToPlayer(mc.player);
            var glowing = entity.isGlowing();

            if (bodyVisible || glowing) {
                GlStateManager.pushMatrix();
                try {
                    GlStateManager.translate((float) x, (float) y, (float) z);
                    GlStateManager.rotate(entity.prevRotationYaw + (entity.rotationYaw - entity.prevRotationYaw) * partialTick - 90.0F, 0.0F, 1.0F, 0.0F);
                    GlStateManager.rotate(entity.prevRotationPitch + (entity.rotationPitch - entity.prevRotationPitch) * partialTick, 0.0F, 0.0F, 1.0F);
                    data.packedLight = data.ctx.nativeType() == RenderContextType.GUI
                            ? 0x7FFF7FFF
                            : entity.getBrightnessForRender();
                    preRender(data, animatable, Color.WHITE);
                    if (data.modelState.isValid()) {
                        render(data, animatable, Color.WHITE);
                    }
                    postRender(data, animatable, Color.WHITE);
                } finally {
                    GlStateManager.popMatrix();
                }
            }
        }
        super.doRender(animatable.getEntity(), x, y, z, yaw, partialTick);
    }

    @Override
    protected ResourceLocation getEntityTexture(TEntity entity) {
        return TextureMap.LOCATION_MISSING_TEXTURE;
    }
}
