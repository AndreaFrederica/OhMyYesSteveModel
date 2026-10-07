package com.elfmcys.ysm.client.compat;

/*
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.common.Loader;

import java.util.Optional;

public class CosmeticArmorCompat {
    private static final String MOD_ID = "cosmeticarmorreworked";
    private static boolean INSTALLED;

    public static void init() {
        INSTALLED = Loader.isModLoaded(MOD_ID);
    }

    public static boolean isInstalled() {
        return INSTALLED;
    }

    public static Optional<ItemStack> getSkinArmorItem(EntityPlayer player, EntityEquipmentSlot slot) {
        if (slot.getSlotType() != EntityEquipmentSlot.Type.ARMOR) {
            return Optional.empty();
        }

        var cosInventory = CosArmorAPI.getCAStacksClient(player.getUniqueID());
        if (cosInventory.isSkinArmor(slot.getIndex())) {
            return Optional.of(ItemStack.EMPTY);
        }

        var skinArmor = cosInventory.getStackInSlot(slot.getIndex());
        return skinArmor.isEmpty() ? Optional.empty() : Optional.of(skinArmor);
    }
}
*/
