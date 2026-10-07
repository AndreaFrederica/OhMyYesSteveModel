package com.elfmcys.ysm.client.renderer.layer;

import com.elfmcys.ysm.client.entity.CustomPlayerEntity;
import com.elfmcys.ysm.client.model.locator.PlayerLocator;
import com.elfmcys.ysm.geckolib3.geo.GeoLayerRenderer;
import com.elfmcys.ysm.geckolib3.geo.GeoRenderData;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.model.ModelParrot;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.entity.RenderParrot;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.passive.EntityParrot;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;

import java.util.UUID;

// LayerEntityOnShoulder
public class CustomParrotOnShoulderLayer extends GeoLayerRenderer<CustomPlayerEntity> {
    private final EntityData leftData;
    private final EntityData rightData;

    public CustomParrotOnShoulderLayer(RenderManager context) {
        this.leftData = new EntityData(context);
        this.rightData = new EntityData(context);
    }

    @Override
    public void render(CustomPlayerEntity animatable, GeoRenderData renderData, double x, double y, double z) {
        var player = animatable.getEntity();
        renderData.modelState.visitLocatorGroup(PlayerLocator.get().leftShoulder, () -> {
            render(player, player.getLeftShoulderEntity(), this.leftData, renderData);
        });
        renderData.modelState.visitLocatorGroup(PlayerLocator.get().rightShoulder, () -> {
            render(player, player.getRightShoulderEntity(), this.rightData, renderData);
        });
    }

    private static void render(EntityLivingBase living, NBTTagCompound shoulderTag, EntityData data, GeoRenderData renderData) {
        data.update(shoulderTag);
        if (data.renderer == null || data.texture == null || data.model == null) {
            return;
        }

        var anim = renderData.animationData;
        float ageInTicks = anim.lerpedAge;
        if (data.clazz == EntityParrot.class) {
            ageInTicks = 0.0F;
        }

        GlStateManager.enableRescaleNormal();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

        GlStateManager.translate(0, 1.5, 0);
        GlStateManager.rotate(180, 0, 0, 1);
        data.renderer.bindTexture(data.texture);
        data.model.setLivingAnimations(living, anim.limbSwing, anim.limbSwingAmount, renderData.partialTicks);
        data.model.setRotationAngles(anim.limbSwing, anim.limbSwingAmount, ageInTicks, anim.netHeadYaw, anim.headPitch, DEFAULT_SCALE, living);
        data.model.render(living, anim.limbSwing, anim.limbSwingAmount, ageInTicks, anim.netHeadYaw, anim.headPitch, DEFAULT_SCALE);

        GlStateManager.disableRescaleNormal();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static class EntityData {
        private final RenderManager renderManager;
        private UUID uuid;
        private Class<? extends Entity> clazz;
        private Render<? extends Entity> renderer;
        private ResourceLocation texture;
        private ModelBase model;

        private EntityData(RenderManager context) {
            this.renderManager = context;
        }

        private void update(NBTTagCompound shoulderTag) {
            UUID uuid = shoulderTag.getUniqueId("UUID");
            if (this.uuid != null && this.uuid.equals(uuid)) return;
            this.uuid = uuid;

            this.clazz = EntityList.getClassFromName(shoulderTag.getString("id"));
            this.renderer = null;
            this.texture = null;
            this.model = null;
            if (this.clazz == EntityParrot.class) {
                this.renderer = new RenderParrot(this.renderManager);
                this.texture = RenderParrot.PARROT_TEXTURES[shoulderTag.getInteger("Variant")];
                this.model = new ModelParrot();
            }
        }
    }
}
