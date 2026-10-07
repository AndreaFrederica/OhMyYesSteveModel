package com.elfmcys.ysm.config;

import com.elfmcys.ysm.config.util.ConfigBuilder;
import com.elfmcys.ysm.config.util.ConfigUtil;
import com.elfmcys.ysm.config.util.IFormatter;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.fml.relauncher.Side;
import org.jetbrains.annotations.NotNull;

import java.io.File;

public final class Config {
    private static Configuration config;

    public static void init(File configFile, Side side) {
        if (config != null) throw new IllegalStateException("Init have been performed!");
        config = new Configuration(configFile);
        readFromFile(side);
    }

    private static void readFromFile(Side side) {
        build(ConfigBuilder.startReadingFromFile(config), side);
    }

    static void readFromProp() {
        build(ConfigBuilder.startReadingFromProp(config), Side.CLIENT);
    }

    public static void save() {
        build(ConfigBuilder.startSaving(config), Side.CLIENT);
    }

    private static void build(@NotNull ConfigBuilder builder, Side side) {
        builder.setLangKeyPrefix("config.yes_steve_model");
        builder.setLangKeyFormatter(IFormatter.IDENTITY);

        if (side.isClient()) {
            builder.pushCategory(ConfigUtil.CATEGORY_CLIENT);
            ClientConfig.build(builder);
            ExtraPlayerScreenConfig.build(builder);
            LoadingStateScreenConfig.build(builder);
            builder.popCategory();
        }

        if (side.isServer()) {
            builder.pushCategory(ConfigUtil.CATEGORY_SERVER);
            ServerConfig.build(builder);
            builder.popCategory();
        }

        builder.finishBuilding();
    }

    static Configuration getConfig() {
        return config;
    }
}
