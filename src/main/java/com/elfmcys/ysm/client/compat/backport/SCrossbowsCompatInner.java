package com.elfmcys.ysm.client.compat.backport;

import com.elfmcys.ysm.client.match.ItemMatchRegistry;
import net.minecraft.item.ItemStack;
import net.smileycorp.crossbows.common.item.ItemCrossbow;

final class SCrossbowsCompatInner {
    static void init() {
        ItemMatchRegistry.register("$minecraft:crossbow", ItemCrossbow.class);
        ItemMatchRegistry.register("#forge:tools/crossbows", ItemCrossbow.class);
        ItemMatchRegistry.register(":crossbow", ItemCrossbow.class);
    }

    static boolean isCharged(ItemStack stack) {
        if (stack.getItem() instanceof ItemCrossbow) {
            return ItemCrossbow.isCharged(stack);
        }
        return false;
    }
}
