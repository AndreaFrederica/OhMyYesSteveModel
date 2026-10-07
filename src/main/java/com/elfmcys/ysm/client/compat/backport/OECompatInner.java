package com.elfmcys.ysm.client.compat.backport;

import com.elfmcys.ysm.client.match.ItemMatchRegistry;
import com.sirsquidly.oe.Main;
import com.sirsquidly.oe.capabilities.CapabilityRiptide;
import com.sirsquidly.oe.items.ItemTrident;
import net.minecraft.entity.EntityLivingBase;

final class OECompatInner {
    static void init() {
        ItemMatchRegistry.register("$minecraft:trident", ItemTrident.class);
        ItemMatchRegistry.register("#forge:tools/tridents", ItemTrident.class);
        ItemMatchRegistry.register(":spear", Main.SPEAR);
    }

    static boolean isAutoSpinAttack(EntityLivingBase living) {
        var cap = living.getCapability(CapabilityRiptide.RIPTIDE_CAP, null);
        return cap != null && cap.getRiptideAnimate();
    }
}
