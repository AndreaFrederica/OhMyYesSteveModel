package com.elfmcys.ysm.client.renderer.layer;

import com.elfmcys.ysm.client.entity.CustomPlayerEntity;
import com.elfmcys.ysm.client.model.locator.PlayerLocator;
import com.elfmcys.ysm.geckolib3.geo.GeoLayerRenderer;
import com.elfmcys.ysm.geckolib3.geo.GeoRenderData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;

// LayerCustomHead
public class CustomPlayerHeadLayer extends GeoLayerRenderer<CustomPlayerEntity> {
    public CustomPlayerHeadLayer() {
    }

    @Override
    public void render(CustomPlayerEntity animatable, GeoRenderData renderData, double x, double y, double z) {
        var player = animatable.getEntity();
        ItemStack head = player.getItemStackFromSlot(EntityEquipmentSlot.HEAD);
        if (!head.isEmpty() && !isArmorHead(head)) {
            renderHeadItem(renderData, player, head);
        }
        /*
        ItemStack curiosHead = SimpleHatsCompat.getCuriosHead(player);
        if (curiosHead != null && !curiosHead.isEmpty()) {
            renderHeadItem(renderData, player, curiosHead);
        }
        */
    }

    private boolean isArmorHead(ItemStack itemStack) {
        return itemStack.getItem() instanceof ItemArmor armor && armor.getEquipmentSlot() == EntityEquipmentSlot.HEAD;
    }

    private void renderHeadItem(GeoRenderData data, EntityPlayer player, ItemStack head) {
        // FIXME：变换似乎不得准确，待测试
        data.modelState.visitLocatorGroup(PlayerLocator.get().head, () -> {
            GlStateManager.scale(0.625F, 0.625F, 0.625F);
            GlStateManager.translate(0.0F, 0.25F, 0.0F);
            Minecraft.getMinecraft().getItemRenderer().renderItem(player, head, ItemCameraTransforms.TransformType.HEAD);
        });
    }
}
