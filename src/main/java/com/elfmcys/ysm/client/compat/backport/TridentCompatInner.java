package com.elfmcys.ysm.client.compat.backport;

import com.elfmcys.ysm.client.match.ItemMatchRegistry;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.trident.Trident;
import net.minecraft.trident.item.ITrident;
import net.minecraft.trident.util.EntityHelper;

final class TridentCompatInner {
    static void init() {
        ItemMatchRegistry.register(":spear", Trident.SPEAR);
        ItemMatchRegistry.register("$minecraft:trident", ITrident.class);
        ItemMatchRegistry.register("#forge:tools/tridents", ITrident.class);
    }

    static boolean isAutoSpinAttack(EntityLivingBase living) {
        return EntityHelper.getSpinAttack(living);
    }

    static void setAutoSpinAttack(EntityLivingBase living, boolean enable) {
        EntityHelper.setSpinAttack(living, enable);
    }
}
