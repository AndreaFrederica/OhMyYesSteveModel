package com.elfmcys.ysm.client.compat.carryon;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import tschipp.carryon.common.item.ItemEntity;
import tschipp.carryon.common.item.ItemTile;

public class CarryOnInnerCompat {
    static boolean isCarryOnPrincess(EntityLivingBase entity) {
        Entity vehicle = entity.getRidingEntity();
        return vehicle instanceof EntityPlayer playerVehicle && getCarryOnType(playerVehicle) == CarryOnInnerCompat.Type.PLAYER;
    }

    static CarryOnInnerCompat.Type getCarryOnType(EntityPlayer player) {
        ItemStack carry = player.getHeldItemMainhand();
        if (!carry.isEmpty()) {
            return CarryOnInnerCompat.Type.NONE;
        }
        if (carry.getItem() instanceof ItemTile && ItemTile.hasTileData(carry)) {
            return CarryOnInnerCompat.Type.BLOCK;
        }
        if (carry.getItem() instanceof ItemEntity && ItemEntity.hasEntityData(carry)) {
            return CarryOnInnerCompat.Type.ENTITY;
        }
        /*
        if (carry.isCarrying(CarryOnData.CarryType.PLAYER)) {
            return CarryOnInnerCompat.Type.PLAYER;
        }
        */
        return CarryOnInnerCompat.Type.NONE;
    }

    enum Type {
        ENTITY, BLOCK, PLAYER, NONE
    }
}
