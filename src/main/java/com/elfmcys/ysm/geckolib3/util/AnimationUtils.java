/*
 * Copyright (c) 2020.
 * Author: Bernie G. (Gecko)
 */

package com.elfmcys.ysm.geckolib3.util;

public class AnimationUtils {
    public static float convertTicksToSeconds(float ticks) {
        return ticks / 20;
    }

    public static float convertSecondsToTicks(float seconds) {
        return seconds * 20;
    }
}
