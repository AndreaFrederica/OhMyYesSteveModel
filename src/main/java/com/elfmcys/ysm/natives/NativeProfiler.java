package com.elfmcys.ysm.natives;

import cc.sirrus.ysmlib.RuntimeProfiler;

/** Legacy call-site facade backed by standard Java Flight Recorder events. */
public final class NativeProfiler {
    private static final ThreadLocal<RuntimeProfiler.Scope> FRAME = new ThreadLocal<>();
    private NativeProfiler() {}
    public static RuntimeProfiler.Scope beginAnimatableUpdate() { return RuntimeProfiler.begin("animation.update"); }
    public static RuntimeProfiler.Scope beginRenderer() { return RuntimeProfiler.begin("render"); }
    public static RuntimeProfiler.Scope beginFallbackVertexWrite() { return RuntimeProfiler.begin("render.vertexConsumer"); }
    public static boolean beginFrame() {
        if (FRAME.get() != null) FRAME.get().close();
        FRAME.set(RuntimeProfiler.begin("frame"));
        return true;
    }
    public static void endFrame(boolean active) {
        var frame = FRAME.get();
        if (active && frame != null) {
            frame.close();
            FRAME.remove();
        }
    }
}
