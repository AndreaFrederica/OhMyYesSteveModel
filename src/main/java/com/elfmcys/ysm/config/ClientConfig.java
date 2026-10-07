package com.elfmcys.ysm.config;

import com.elfmcys.ysm.config.util.ConfigBuilder;
import com.elfmcys.ysm.network.session.SessionMode;

public class ClientConfig {
    // general
    public static boolean DISCLAIMER_SHOW = true;
    public static boolean PRINT_ANIMATION_ROULETTE_MSG = false;
    public static boolean DISABLE_SELF_MODEL = false;
    public static boolean DISABLE_OTHER_MODEL = false;
    public static boolean DISABLE_SELF_HANDS = false;
    public static boolean DISABLE_PROJECTILE_MODEL = false;
    public static boolean DISABLE_VEHICLE_MODEL = false;
    public static boolean DISABLE_EXTERNAL_FIRST_PERSON_ANIM = false;
    public static boolean USE_COMPATIBILITY_RENDERER = false;
    public static double SOUND_VOLUME = 100.0;
    public static boolean SHOW_MODEL_ID_FIRST = false;
    // network
    public static SessionMode NETWORK_SESSION_MODE = SessionMode.AUTO;
    // Integration
    public static boolean ENABLE_SOPHISTICATED_BACKPACK_COMPAT = true;
    public static boolean ENABLE_PARCOOL_COMPAT = true;

    static void build(ConfigBuilder builder) {
        builder.pushCategory("general");

        DISCLAIMER_SHOW = builder.get(
                "DisclaimerShow",
                DISCLAIMER_SHOW,
                "Whether to display disclaimer GUI"
        );

        PRINT_ANIMATION_ROULETTE_MSG = builder.get(
                "PrintAnimationRouletteMsg",
                PRINT_ANIMATION_ROULETTE_MSG,
                "Whether to print animation roulette play message"
        );

        DISABLE_SELF_MODEL = builder.get(
                "DisableSelfModel",
                DISABLE_SELF_MODEL,
                "Prevents rendering of self player's model"
        );

        DISABLE_OTHER_MODEL = builder.get(
                "DisableOtherModel",
                DISABLE_OTHER_MODEL,
                "Prevents rendering of other player's model"
        );

        DISABLE_SELF_HANDS = builder.get(
                "DisableSelfHands",
                DISABLE_SELF_HANDS,
                "Prevents rendering of self player's hand"
        );

        DISABLE_PROJECTILE_MODEL = builder.get(
                "DisableProjectileModel",
                DISABLE_PROJECTILE_MODEL,
                "Prevents rendering of projectile model"
        );

        DISABLE_VEHICLE_MODEL = builder.get(
                "DisableVehicleModel",
                DISABLE_VEHICLE_MODEL,
                "Prevents rendering of vehicle model"
        );

        DISABLE_EXTERNAL_FIRST_PERSON_ANIM = builder.get(
                "DisableExternalFirstPersonAnim",
                DISABLE_EXTERNAL_FIRST_PERSON_ANIM,
                "Disable first person animation from other mods."
        );

        USE_COMPATIBILITY_RENDERER = builder.get(
                "UseCompatibilityRenderer",
                USE_COMPATIBILITY_RENDERER,
                "If rendering errors occur, try turning on this."
        );

        SOUND_VOLUME = builder.getProp(
                "SoundVolume",
                SOUND_VOLUME,
                "The amount of volume when the animation is played."
        ).setMinValue(0.0).setMaxValue(100.0).getDouble();

        SHOW_MODEL_ID_FIRST = builder.get(
                "ShowModelIdFirst",
                SHOW_MODEL_ID_FIRST,
                "Whether to display model ID first in the model selection screen, instead of the model name filled in by the model author."
        );

        builder.popCategory();

        builder.pushCategory("network");
        NETWORK_SESSION_MODE = builder.get(
                "SessionMode",
                NETWORK_SESSION_MODE,
                """
                        AUTO uses the game-server YSM session when its channel is present.
                        LOCAL declines the server session and keeps the local catalog.
                        """
        );
        builder.popCategory();

        builder.pushCategory("Integration");
        ENABLE_SOPHISTICATED_BACKPACK_COMPAT = builder.get(
                "SophisticatedBackpack",
                ENABLE_SOPHISTICATED_BACKPACK_COMPAT
        );
        ENABLE_PARCOOL_COMPAT = builder.get(
                "Parcool",
                ENABLE_PARCOOL_COMPAT
        );
        builder.popCategory();
    }
}
