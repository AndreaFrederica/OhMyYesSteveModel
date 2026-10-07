package com.elfmcys.ysm.mixin.client;

import com.elfmcys.ysm.util.RenderUtil;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.EntityLivingBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(GuiInventory.class)
public class GuiInventoryMixin {
    @WrapMethod(method = "drawEntityOnScreen")
    private static void wrapRenderEntityInInventoryFollowsAngle(int x, int y, int scale, float mouseX, float mouseY, EntityLivingBase livingEntity, Operation<Void> original) {
        RenderUtil.setRenderingInInventory(true);
        original.call(x, y, scale, mouseX, mouseY, livingEntity);
        RenderUtil.setRenderingInInventory(false);
    }

    @WrapOperation(
            method = "drawEntityOnScreen",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/RenderHelper;enableStandardItemLighting()V"
            )
    )
    private static void cancelLightingRotation(Operation<Void> original) {
        // 只要没别的模组做一样的事，就不会有问题
        GlStateManager.rotate(-135.0F, 0, 1, 0);
        original.call();
        GlStateManager.rotate(135.0F, 0, 1, 0);
    }
}
