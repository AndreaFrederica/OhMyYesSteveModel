package com.elfmcys.ysm.config;

import com.elfmcys.ysm.config.util.ConfigBuilder;

public class LoadingStateScreenConfig {
    public static boolean DISABLE_LOADING_STATE_SCREEN = false;
    public static Position LOADING_STATE_POSITION = Position.TOP_CENTER;

    static void build(ConfigBuilder builder) {
        builder.pushCategory("loading_state_screen");

        DISABLE_LOADING_STATE_SCREEN = builder.get(
                "DisableLoadingStateScreen",
                DISABLE_LOADING_STATE_SCREEN,
                "Whether to disable loading state screen"
        );

        LOADING_STATE_POSITION = builder.get(
                "LoadingStatePosition",
                LOADING_STATE_POSITION,
                "Loading state screen position"
        );

        builder.popCategory();
    }

    public enum Position {
        TOP_LEFT,
        TOP_CENTER,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_CENTER,
        BOTTOM_RIGHT
    }
}
