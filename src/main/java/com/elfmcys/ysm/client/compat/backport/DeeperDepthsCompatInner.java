package com.elfmcys.ysm.client.compat.backport;

import com.deeperdepths.common.items.ItemSpyglass;
import com.elfmcys.ysm.client.match.ItemMatchRegistry;

final class DeeperDepthsCompatInner {
    static void init() {
        ItemMatchRegistry.register("$minecraft:spyglass", ItemSpyglass.class);
        ItemMatchRegistry.register(":spyglass", ItemSpyglass.class);
    }
}
