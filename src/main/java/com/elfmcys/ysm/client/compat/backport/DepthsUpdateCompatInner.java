package com.elfmcys.ysm.client.compat.backport;

import com.elfmcys.ysm.client.match.ItemMatchRegistry;
import sayys.depthsupdate.item.ItemSpyglass;

final class DepthsUpdateCompatInner {
    static void init() {
        ItemMatchRegistry.register("$minecraft:spyglass", ItemSpyglass.class);
        ItemMatchRegistry.register(":spyglass", ItemSpyglass.class);
    }
}
