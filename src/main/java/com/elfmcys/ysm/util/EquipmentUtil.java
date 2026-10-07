package com.elfmcys.ysm.util;

import net.minecraft.entity.EntityLivingBase;
import net.minecraft.init.Items;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;

public class EquipmentUtil {
    public static ItemStack getEquippedItem(EntityLivingBase entity, EntityEquipmentSlot slot) {
        /*
        if (slot.getSlotType() == EntityEquipmentSlot.Type.ARMOR && entity instanceof EntityPlayer player && CosmeticArmorCompat.isInstalled()) {
            var stack = CosmeticArmorCompat.getSkinArmorItem(player, slot);
            if (stack.isPresent()) {
                return stack.get();
            }
        }
        */
        var rawItem = entity.getItemStackFromSlot(slot);
        return rawItem;
    }

    // TODO
    public static ItemStack getEquippedElytraItem(EntityLivingBase entity) {
        // 时装盔甲
        /*
        if (entity instanceof EntityPlayer player && CosmeticArmorCompat.isInstalled()) {
            var stack = CosmeticArmorCompat.getSkinArmorItem(player, EntityEquipmentSlot.CHEST);
            if (stack.isPresent() && stack.get().getItem() == Items.ELYTRA) {
                return stack.get();
            }
        }
        */
        // 鞘翅插槽
        /*
        if (ElytraSlotCompat.isInstalled()) {
            var stack = ElytraSlotCompat.getEquippedElytraItem(entity);
            if (stack.getItem() == Items.ELYTRA) {
                return stack;
            }
        }
        */
        // 原版鞘翅
        var stack = entity.getItemStackFromSlot(EntityEquipmentSlot.CHEST);
        if (stack.getItem() == Items.ELYTRA) {
            return stack;
        }
        return ItemStack.EMPTY;
    }
}
