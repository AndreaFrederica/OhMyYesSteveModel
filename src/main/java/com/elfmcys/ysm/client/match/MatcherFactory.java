package com.elfmcys.ysm.client.match;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.item.EnumAction;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;

import java.util.function.Predicate;

public final class MatcherFactory {
    public static Predicate<ItemStack> isItem(Class<?> itemClass) {
        return stack -> itemClass.isInstance(stack.getItem());
    }

    public static Predicate<ItemStack> isItemId(String id) {
        return stack -> {
            Item item = stack.getItem();
            //noinspection ConstantValue
            if (item == null) return false;
            ResourceLocation regName = item.getRegistryName();
            return regName != null && regName.toString().equals(id);
        };
    }

    public static Predicate<ItemStack> isAnim(EnumAction anim) {
        return stack -> anim == stack.getItemUseAction();
    }

    public static Predicate<Entity> isEntity(Class<?> entityClass) {
        return entityClass::isInstance;
    }

    public static Predicate<Entity> isEntityId(String id) {
        return entity -> {
            ResourceLocation regName = EntityList.getKey(entity);
            return regName != null && regName.toString().equals(id);
        };
    }
}
