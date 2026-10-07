package com.elfmcys.ysm.client.compat.backport;

import com.elfmcys.ysm.client.match.ItemMatchRegistry;
import suike.suikecherry.item.ModItemBrush;

final class SuiKeCherryCompatInner {
    static void init() {
        ItemMatchRegistry.register("$minecraft:brush", ModItemBrush.class);
        ItemMatchRegistry.register(":brush", ModItemBrush.class);
    }
}
