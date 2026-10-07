package com.elfmcys.ysm.client.compat;

import dev.tr7zw.firstperson.api.FirstPersonAPI;
import dev.tr7zw.firstperson.api.PlayerOffsetHandler;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.fml.common.Loader;

// FIXME：疑似未修好，但 API 调用已一致，应该是另一侧的问题？
public class FirstPersonCompat {
    private static final String MOD_ID = "firstperson";
    private static volatile float HEAD_POS;
    private static boolean INSTALLED;

    public static void init() {
        INSTALLED = Loader.isModLoaded(MOD_ID);
        if (INSTALLED) {
            registerOffset();
            setHeadPos(24);
        }
    }

    public static boolean isInstalled() {
        return INSTALLED;
    }

    private static void registerOffset() {
        FirstPersonAPI.registerPlayerHandler((PlayerOffsetHandler)(entity, delta, original, current) ->
                new Vec3d(current.x, 1.5f - HEAD_POS, current.z));
    }

    public static boolean isRenderingPlayer() {
        return INSTALLED && FirstPersonAPI.isRenderingPlayer();
    }

    public static boolean shouldHideHead() {
        return isRenderingPlayer();
    }

    public static void setHeadPos(float headPos) {
        HEAD_POS = headPos / 16f;
    }

    public static boolean isEnabled() {
        return FirstPersonAPI.isEnabled();
    }
}
