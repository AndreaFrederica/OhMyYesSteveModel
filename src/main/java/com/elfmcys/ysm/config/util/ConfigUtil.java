package com.elfmcys.ysm.config.util;

import net.minecraftforge.common.config.ConfigElement;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.fml.client.config.IConfigElement;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.stream.Collectors;

@SuppressWarnings("unused")
public final class ConfigUtil {
    public static final String CATEGORY_GENERAL = Configuration.CATEGORY_GENERAL;
    public static final String CATEGORY_CLIENT = Configuration.CATEGORY_CLIENT;
    public static final String CATEGORY_SERVER = "server";

    @NotNull
    public static List<IConfigElement> getRootElement(Configuration config, String rootCategory) {
        return new ConfigElement(config.getCategory(rootCategory)).getChildElements();
    }

    @NotNull
    public static List<IConfigElement> getRootElements(Configuration config) {
        return config.getCategoryNames().stream()
                .map(config::getCategory)
                .filter(category -> !category.isChild())
                .map(ConfigElement::new)
                .collect(Collectors.toList());
    }

    @NotNull
    public static List<IConfigElement> getDisplayElements(Configuration config) {
        List<IConfigElement> rootElements = getRootElements(config);
        return rootElements.size() == 1 ? rootElements.getFirst().getChildElements() : rootElements;
    }
}
