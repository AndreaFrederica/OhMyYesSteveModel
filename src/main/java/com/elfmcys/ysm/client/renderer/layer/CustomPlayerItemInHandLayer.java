package com.elfmcys.ysm.client.renderer.layer;

import com.elfmcys.ysm.client.compat.slashblade.SlashBladeCompat;
import com.elfmcys.ysm.client.compat.slashblade.SlashBladeRender;
/*
import com.elfmcys.ysm.client.compat.swarfare.SWarfareCompat;
*/
import com.elfmcys.ysm.client.entity.CustomPlayerEntity;
import com.elfmcys.ysm.client.model.locator.PlayerLocator;
import com.elfmcys.ysm.geckolib3.geo.GeoLayerRenderer;
import com.elfmcys.ysm.geckolib3.geo.GeoRenderData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHandSide;

// LayerHeldItem
public class CustomPlayerItemInHandLayer extends GeoLayerRenderer<CustomPlayerEntity> {
    public CustomPlayerItemInHandLayer() {
    }

    @Override
    public void render(CustomPlayerEntity animatable, GeoRenderData renderData, double x, double y, double z) {
        var entityLivingBaseIn = animatable.getEntity();
        var mainHandItem = entityLivingBaseIn.getHeldItemMainhand();
        if (!mainHandItem.isEmpty() && renderData.modelState.locatorGroupSize(PlayerLocator.get().rightHand) > 0) {
            if (SlashBladeCompat.isSlashBladeItem(mainHandItem)) {
                SlashBladeRender.renderMainhandSlashBlade(entityLivingBaseIn, renderData, mainHandItem);
            } else {
                /*
                TACZCompat.openFlashShellRender(entityLivingBaseIn, mainHandItem);
                */
                this.renderArmWithItem(entityLivingBaseIn, mainHandItem, ItemCameraTransforms.TransformType.THIRD_PERSON_RIGHT_HAND,
                        EnumHandSide.RIGHT, renderData);
                /*
                TACZCompat.stopFlashShellRender(mainHandItem);
                */
            }
        }

        var offhandItem = entityLivingBaseIn.getHeldItemOffhand();
        if (!offhandItem.isEmpty() && renderData.modelState.locatorGroupSize(PlayerLocator.get().leftHand) > 0) {
            if (SlashBladeCompat.isSlashBladeItem(offhandItem)) {
                SlashBladeRender.renderOffhandSlashBlade(renderData, offhandItem);
            /*
            } else {
                // 卓越前线副手枪械不用这个渲染
                if (!SWarfareCompat.isGun(offhandItem)) {
                    this.renderArmWithItem(entityLivingBaseIn, mainHandItem,
                                           ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                                           HumanoidArm.RIGHT, renderData, poseStack, buffer, packedLight);
                }
            */
            }
            /*
            // TACZ 副手枪械渲染
            TACZCompat.renderOffsetHand(offhandItem, renderData, entityLivingBaseIn, poseStack, buffer, packedLight);
            // 卓越前线副手枪械渲染
            SWarfareCompat.renderOffsetHand(offhandItem, renderData, entityLivingBaseIn, poseStack, buffer, packedLight);
            */
            this.renderArmWithItem(entityLivingBaseIn, offhandItem, ItemCameraTransforms.TransformType.THIRD_PERSON_LEFT_HAND,
                    EnumHandSide.LEFT, renderData);
        }
    }

    protected void renderArmWithItem(EntityLivingBase livingEntity, ItemStack itemStack, ItemCameraTransforms.TransformType displayContext, EnumHandSide arm, GeoRenderData data) {
        if (!itemStack.isEmpty()) {
            boolean isLeftHand = arm == EnumHandSide.LEFT;
            var locator = isLeftHand ? PlayerLocator.get().leftHand : PlayerLocator.get().rightHand;

            data.modelState.visitLocatorGroup(locator, () -> {
                // 渲染默认手部物品
                GlStateManager.translate(0, -0.0625, -0.1);
                GlStateManager.rotate(-90.0F, 1, 0, 0);
                /*
                // 卓越前线的枪械需要缩放一下，不然太小了
                if (SWarfareCompat.isGun(itemStack)) {
                    GlStateManager.translate(0.1, 0, 0);
                    GlStateManager.scale(1.25f, 1.25f, 1.25f);
                }
                */
                Minecraft.getMinecraft().getItemRenderer().renderItemSide(livingEntity, itemStack, displayContext, isLeftHand);
            });
        }
    }
}
