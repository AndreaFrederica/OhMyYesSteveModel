package com.elfmcys.ysm.util;

import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.ModLoadingWarning;

/** Platform compatibility facade. ysmlib owns optional acceleration discovery. */
public final class NativeLibUtil {
    private NativeLibUtil() {}
    public static void load() { cc.sirrus.ysmlib.YsmRuntime.images(); }
    public static boolean isAvailable() { return true; }
    public static boolean isMobilePlatform() {
        return System.getenv("MOD_ANDROID_RUNTIME") != null
                || System.getenv("FCL_VERSION_CODE") != null
                || System.getenv("ZALITH_VERSION_CODE") != null
                || System.getProperty("java.runtime.name", "").toLowerCase(java.util.Locale.ROOT).contains("android");
    }
    public static Component getUnsupportedMsg() { return Component.empty(); }
    public static String getUnsupportedMsgStr() { return ""; }
    public static ModLoadingWarning getUnavailableWarning() { return null; }
}
