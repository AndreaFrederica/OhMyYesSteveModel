package com.elfmcys.ysm.client.renderer.layer;

import com.elfmcys.ysm.client.entity.CustomPlayerEntity;
import com.elfmcys.ysm.client.model.locator.PlayerLocator;
import com.elfmcys.ysm.geckolib3.geo.GeoLayerRenderer;
import com.elfmcys.ysm.geckolib3.geo.GeoRenderData;
import com.elfmcys.ysm.util.EquipmentUtil;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.model.ModelElytra;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.RenderLivingBase;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.entity.layers.LayerArmorBase;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EnumPlayerModelParts;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

// LayerElytra
public class CustomPlayerElytraLayer extends GeoLayerRenderer<CustomPlayerEntity> {
    private static final ResourceLocation WINGS_LOCATION = new ResourceLocation("textures/entity/elytra.png");
    private final RenderLivingBase<? extends EntityLivingBase> renderer;
    private final ModelElytra elytraModel;

    public CustomPlayerElytraLayer(RenderManager context) {
        this.renderer = context.getSkinMap().get("default");
        this.elytraModel = new ModelElytra();
    }

    @Override
    public void render(CustomPlayerEntity animatable, GeoRenderData renderData, double x, double y, double z) {
        var player = animatable.getEntity();
        ItemStack stack = EquipmentUtil.getEquippedElytraItem(player);
        if (!stack.isEmpty()) {
            ResourceLocation texture = getElytraTexture(player, stack.getItem());
            renderData.modelState.visitLocatorGroup(PlayerLocator.get().elytra, () -> {
                GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
                GlStateManager.enableBlend();
                GlStateManager.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);

                GlStateManager.rotate(180, 0, 0, 1);
                var anim = renderData.animationData;
                this.renderer.bindTexture(texture);
                this.elytraModel.setRotationAngles(anim.limbSwing, anim.limbSwingAmount, anim.lerpedAge, anim.netHeadYaw, anim.headPitch, DEFAULT_SCALE, player);
                this.elytraModel.render(player, anim.limbSwing, anim.limbSwingAmount, anim.lerpedAge, anim.netHeadYaw, anim.headPitch, DEFAULT_SCALE);
                if (stack.hasEffect()) LayerArmorBase.renderEnchantedGlint(this.renderer, player, this.elytraModel, anim.limbSwing, anim.limbSwingAmount, renderData.partialTicks, anim.lerpedAge, anim.netHeadYaw, anim.headPitch, DEFAULT_SCALE);

                GlStateManager.disableBlend();
                GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            });
        }
    }

    // TODO：支持一些其他模组的鞘翅
    private static ResourceLocation getElytraTexture(EntityLivingBase livingEntity, Item elytra) {
        if (livingEntity instanceof AbstractClientPlayer player) {
            if (player.isPlayerInfoSet() && player.getLocationElytra() != null) {
                return player.getLocationElytra();
            } else if (player.hasPlayerInfo() && player.getLocationCape() != null && player.isWearing(EnumPlayerModelParts.CAPE)) {
                return player.getLocationCape();
            }
        }
        return WINGS_LOCATION;
    }
}
