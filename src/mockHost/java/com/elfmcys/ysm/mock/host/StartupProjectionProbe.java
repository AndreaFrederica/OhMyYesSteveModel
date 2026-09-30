package com.elfmcys.ysm.mock.host;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;

import java.util.Map;

/** Exercises the real, mixin-transformed renderer before connecting, on the named dev host. */
final class StartupProjectionProbe {
    private StartupProjectionProbe() {}

    static Map<String, ?> verify(Minecraft minecraft) {
        try {
            var renderer = minecraft.gameRenderer;
            var fov = GameRenderer.class.getDeclaredField("fov");
            var oldFov = GameRenderer.class.getDeclaredField("oldFov");
            var tick = GameRenderer.class.getDeclaredField("tick");
            var distance = GameRenderer.class.getDeclaredField("renderDistance");
            var getFov = GameRenderer.class.getDeclaredMethod("getFov", Camera.class, float.class, boolean.class);
            var tickFov = GameRenderer.class.getDeclaredMethod("tickFov");
            for (var field : new java.lang.reflect.Field[]{fov, oldFov, tick, distance}) field.setAccessible(true);
            getFov.setAccessible(true);
            tickFov.setAccessible(true);
            float initial = fov.getFloat(renderer), initialOld = oldFov.getFloat(renderer);
            float originalDistance = distance.getFloat(renderer);
            int rendererTicks = tick.getInt(renderer);
            if (rendererTicks != 0 || minecraft.level != null) {
                throw new IllegalStateException("Startup projection probe missed the pre-tick window");
            }
            int setting = minecraft.options.fov().get();
            var camera = renderer.getMainCamera();
            try {
                distance.setFloat(renderer, 128.0F);
                for (float partial : new float[]{0.0F, 0.5F, 1.0F}) {
                    double effective = (double)getFov.invoke(renderer, camera, partial, true);
                    requireClose(setting, effective, "FOV before first tick at " + partial);
                    if (!renderer.getProjectionMatrix(effective).isFinite()) {
                        throw new IllegalStateException("Non-finite startup projection");
                    }
                }
                // Negative control: reconstruct the observed vanilla startup state.
                fov.setFloat(renderer, 0.0F);
                oldFov.setFloat(renderer, 0.0F);
                double brokenFov = (double)getFov.invoke(renderer, camera, 0.0F, true);
                if (brokenFov != 0.0 || renderer.getProjectionMatrix(brokenFov).isFinite()) {
                    throw new IllegalStateException("Zero-initialized FOV did not reproduce the invalid projection");
                }
                fov.setFloat(renderer, initial);
                oldFov.setFloat(renderer, initialOld);
                tickFov.invoke(renderer);
                requireClose(setting, (double)getFov.invoke(renderer, camera, 0.5F, true), "First FOV tick");

                // A running FOV effect must still interpolate, rather than being clamped to 70 degrees.
                oldFov.setFloat(renderer, 0.8F);
                fov.setFloat(renderer, 1.2F);
                requireClose(setting * 0.9, (double)getFov.invoke(renderer, camera, 0.25F, true), "Dynamic FOV");
                requireClose(70.0, (double)getFov.invoke(renderer, camera, 0.25F, false), "Hand FOV");
                return Map.of("rendererTicks", rendererTicks, "initialModifier", initial,
                        "initialOldModifier", initialOld, "finiteBeforeFirstTick", true,
                        "zeroInitializationReproduced", true, "dynamicFovPreserved", true);
            } finally {
                fov.setFloat(renderer, initial);
                oldFov.setFloat(renderer, initialOld);
                distance.setFloat(renderer, originalDistance);
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot inspect startup projection on the named Forge host", failure);
        }
    }

    private static void requireClose(double expected, double actual, String label) {
        if (!Double.isFinite(actual) || Math.abs(expected - actual) > 0.0001) {
            throw new IllegalStateException(label + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
