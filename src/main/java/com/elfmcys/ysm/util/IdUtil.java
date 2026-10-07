package com.elfmcys.ysm.util;

import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * {@link ResourceLocation} 的辅助方法。验证移植自更高版本。
 */
public final class IdUtil {
    public static final String DEFAULT_NAMESPACE = "minecraft";

    private IdUtil() {
    }

    /**
     * Attempts to parse the specified {@code location} as a {@code ResourceLocation} by splitting it into a
     * namespace and path by a colon.
     * <p>
     * If no colon is present in the {@code location}, the namespace defaults to {@code minecraft}, taking the {@code
     * location} as the path.
     *
     * @param location the location string to try to parse as a {@code ResourceLocation}
     * @return the parsed resource location; otherwise {@code null} if there is a non {@code [a-z0-9_.-]} character in
     * the decomposed namespace or a non {@code [a-z0-9/._-]} character in the decomposed path
     */
    @Nullable
    public static ResourceLocation tryParse(String location) {
        String[] astring = ResourceLocation.splitObjectName(location);
        if (!isValidNamespace(astring[0]) || !isValidPath(astring[1])) return null;
        return new ResourceLocation(astring[0], astring[1]);
    }

    /**
     * Splits the specified {@code location} into a namespace and path by a colon, checking both are valid.
     * <p>
     * If no colon is present in the {@code location}, the namespace defaults to {@code minecraft}, taking the {@code
     * location} as the path.</p>
     *
     * @return {@code true} if both the decomposed namespace and path are valid
     * @see #isValidPath(String)
     * @see #isValidNamespace(String)
     */
    public static boolean isValidResourceLocation(String location) {
        String[] split = ResourceLocation.splitObjectName(location);
        return isValidNamespace(split[0].isEmpty() ? DEFAULT_NAMESPACE : split[0]) && isValidPath(split[1]);
    }

    public static String normalize(String location) {
        String[] split = ResourceLocation.splitObjectName(location);
        return split[0].isEmpty() ? DEFAULT_NAMESPACE : split[0] + ":" + split[1];
    }

    private static boolean isValidPath(String path) {
        for (int i = 0; i < path.length(); ++i) {
            if (!validPathChar(path.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean validPathChar(char charValue) {
        return validNamespaceChar(charValue) || charValue == '/';
    }

    private static boolean isValidNamespace(String namespace) {
        for (int i = 0; i < namespace.length(); ++i) {
            if (!validNamespaceChar(namespace.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean validNamespaceChar(char charValue) {
        return charValue == '_' || charValue == '-' || charValue >= 'a' && charValue <= 'z' || charValue >= '0' && charValue <= '9' || charValue == '.';
    }
}
