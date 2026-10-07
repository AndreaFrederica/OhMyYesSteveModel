package com.elfmcys.ysm.config;

import com.elfmcys.ysm.config.util.ConfigBuilder;

public class ServerConfig {
    public static String DEFAULT_MODEL_PATH = "default";
    public static String DEFAULT_MODEL_TEXTURE = "default";
    public static boolean CAN_SWITCH_MODEL = true;
    // 禁止在玩家客户端 GUI 界面显示的模型相对路径
    public static String[] CLIENT_NOT_DISPLAY_MODEL_PATHS = {};
    // server_scheduler
    public static int THREAD_COUNT = 0;
    public static int BANDWIDTH_LIMIT = 5;
    public static int DISPATCH_SOFT_LIMIT = 24;
    public static int DISPATCH_HARD_LIMIT = 48;
    public static boolean RESTRICTED_AUTH = false;
    public static boolean LOW_BANDWIDTH_USAGE = false;

    static void build(ConfigBuilder builder) {
        DEFAULT_MODEL_PATH = builder.get(
                "DefaultModelPath",
                DEFAULT_MODEL_PATH,
                "The relative path of the default model when a player first enters the game"
        );

        DEFAULT_MODEL_TEXTURE = builder.get(
                "DefaultModelTexture",
                DEFAULT_MODEL_TEXTURE,
                "The default model texture when a player first enters the game"
        );

        CAN_SWITCH_MODEL = builder.get(
                "CanSwitchModel",
                CAN_SWITCH_MODEL,
                "Whether or not players are allowed to switch models"
        );

        CLIENT_NOT_DISPLAY_MODEL_PATHS = builder.get(
                "ClientNotDisplayModelPaths",
                CLIENT_NOT_DISPLAY_MODEL_PATHS,
                """
                        Relative model paths that are not displayed on the client model selection screen
                        Example: ["model.mxc", "pack/private/legacy.ysm"]
                        """
        );

        builder.pushCategory("server_scheduler");

        THREAD_COUNT = builder.getProp(
                "ThreadCount",
                THREAD_COUNT,
                "Concurrent level for processing models. Value 0 means AUTO."
        ).setMinValue(0).setMaxValue(Math.max(2, Runtime.getRuntime().availableProcessors() - 1)).getInt();

        BANDWIDTH_LIMIT = builder.getProp(
                "BandwidthLimit",
                BANDWIDTH_LIMIT,
                "Global model distribution limit in Mbps. 0 means unlimited."
        ).setMinValue(0).setMaxValue(999).getInt();

        DISPATCH_SOFT_LIMIT = builder.getProp(
                "DispatchSoftLimit",
                DISPATCH_SOFT_LIMIT,
                "Per-player queue size that enables one-fragment round-robin visits."
        ).setMinValue(1).setMaxValue(255).getInt();

        DISPATCH_HARD_LIMIT = builder.getProp(
                "DispatchHardLimit",
                DISPATCH_HARD_LIMIT,
                "Atomic per-player logical-packet admission cap."
        ).setMinValue(2).setMaxValue(256).getInt();

        RESTRICTED_AUTH = builder.get(
                "RestrictedAuth",
                RESTRICTED_AUTH,
                "Restrict unauthorized auth-model icon and chunk downloads."
        );

        LOW_BANDWIDTH_USAGE = builder.get(
                "LowBandwidthUsage",
                LOW_BANDWIDTH_USAGE,
                """
                        Suppress network synchronization of partial features to reduce bandwidth usage
                        Only effective when there are tons of players
                        """
        );

        builder.popCategory();
    }
}
