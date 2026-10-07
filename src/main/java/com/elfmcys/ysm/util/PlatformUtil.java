package com.elfmcys.ysm.util;

import com.elfmcys.ysm.YesSteveModel;

import java.net.URI;

public final class PlatformUtil {
    private PlatformUtil() {
    }

    public static void openLink(String link) {
        URI uri;
        try {
            uri = URI.create(link);
            if (uri.isAbsolute() && (uri.getScheme().equalsIgnoreCase("http") ||
                    uri.getScheme().equalsIgnoreCase("https"))) {
                openUri(uri);
            }
        } catch (Exception e) {
            Throwable cause = e.getCause();
            YesSteveModel.LOGGER.error("Couldn't open link: {}", cause == null ? "<UNKNOWN>" : cause.getMessage());
        }
    }

    public static void openUri(URI uri) {
        try {
            Class<?> oclass = Class.forName("java.awt.Desktop");
            Object object = oclass.getMethod("getDesktop").invoke(null);
            oclass.getMethod("browse", URI.class).invoke(object, uri);
        } catch (Throwable throwable) {
            Throwable cause = throwable.getCause();
            YesSteveModel.LOGGER.error("Couldn't open uri: {}", cause == null ? "<UNKNOWN>" : cause.getMessage());
        }
    }
}
