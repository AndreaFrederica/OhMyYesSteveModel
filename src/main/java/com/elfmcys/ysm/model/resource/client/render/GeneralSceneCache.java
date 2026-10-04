package com.elfmcys.ysm.model.resource.client.render;

import cc.sirrus.ysmlib.SceneDiskCache;
import com.elfmcys.ysm.AssetPaths;
import com.elfmcys.ysm.config.ModelLoadingConfig;

/** Host root, version and disk budget; the Lib owns the portable cache wire and stages. */
public final class GeneralSceneCache {
    private GeneralSceneCache() {}
    private static SceneDiskCache cache;
    private static int budget;
    public static synchronized SceneDiskCache get() {
        int configured=ModelLoadingConfig.SCENE_CACHE_MIB.get();
        if(cache==null || configured!=budget) {
            budget=configured;
            String version=net.minecraftforge.fml.ModList.get().getModContainerById("ysm").orElseThrow().getModInfo().getVersion().toString();
            cache=new SceneDiskCache(AssetPaths.gameCacheRoot().resolve("scene"),"oh-my-ysm/"+version+"/surface-1",
                    (long)budget*1024*1024,Math.min((long)budget,1024)*1024*1024);
        }
        return cache;
    }
}
