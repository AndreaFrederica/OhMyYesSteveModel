package com.elfmcys.ysm.config;

import com.elfmcys.ysm.config.util.ConfigBuilder;

public class ExtraPlayerScreenConfig {
    public static boolean DISABLE_PLAYER_RENDER = false;
    public static int PLAYER_POS_X = 10;
    public static int PLAYER_POS_Y = 10;
    public static float PLAYER_SCALE = 40;
    public static float PLAYER_YAW_OFFSET = 5;

    static void build(ConfigBuilder builder) {
        builder.pushCategory("extra_player_render");

        DISABLE_PLAYER_RENDER = builder.get(
                "DisablePlayerRender",
                DISABLE_PLAYER_RENDER,
                "Whether to display player"
        );

        PLAYER_POS_X = builder.getProp(
                "PlayerPosX",
                PLAYER_POS_X,
                "Player position x in screen"
        ).setMinValue(0).setMaxValue(Integer.MAX_VALUE).getInt();

        PLAYER_POS_Y = builder.getProp(
                "PlayerPosY",
                PLAYER_POS_Y,
                "Player position y in screen"
        ).setMinValue(0).setMaxValue(Integer.MAX_VALUE).getInt();

        PLAYER_SCALE = (float) builder.getProp(
                "PlayerScale",
                PLAYER_SCALE,
                "Player scale in screen"
        ).setMinValue(8.0).setMaxValue(360.0).getDouble();

        PLAYER_YAW_OFFSET = (float) builder.getProp(
                "PlayerYawOffset",
                PLAYER_YAW_OFFSET,
                "Player yaw offset in screen"
        ).setMinValue(Double.MIN_VALUE).setMaxValue(Double.MAX_VALUE).getDouble();

        builder.popCategory();
    }
}
