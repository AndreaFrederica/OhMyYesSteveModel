package com.elfmcys.ysm.client.compat.backport;

import com.elfmcys.ysm.client.match.ItemMatchRegistry;
import git.jbredwards.crossbow.api.ICrossbow;
import git.jbredwards.crossbow.mod.common.capability.ICrossbowProjectiles;
import net.minecraft.item.ItemStack;

final class JCrossbowCompatInner {
    static void init() {
        ItemMatchRegistry.register("$minecraft:crossbow", ICrossbow.class);
        ItemMatchRegistry.register("#forge:tools/crossbows", ICrossbow.class);
        ItemMatchRegistry.register(":crossbow", ICrossbow.CROSSBOW_ACTION);
    }

    static boolean isCharged(ItemStack stack) {
        if (stack.getItem() instanceof ICrossbow) {
            ICrossbowProjectiles cap = ICrossbowProjectiles.get(stack);
            return cap != null && !cap.isEmpty();
        }
        return false;
    }
}
