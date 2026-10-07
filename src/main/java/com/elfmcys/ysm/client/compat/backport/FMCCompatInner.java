package com.elfmcys.ysm.client.compat.backport;

import com.elfmcys.ysm.client.match.ItemMatchRegistry;
import thedarkcolour.futuremc.item.TridentItem;

final class FMCCompatInner {
    static void init() {
        ItemMatchRegistry.register("$minecraft:trident", TridentItem.class);
        ItemMatchRegistry.register("#forge:tools/tridents", TridentItem.class);
        ItemMatchRegistry.register(":spear", TridentItem.getTRIDENT_USE_ACTION());
    }
}
