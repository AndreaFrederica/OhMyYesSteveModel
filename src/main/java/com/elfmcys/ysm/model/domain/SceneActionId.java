package com.elfmcys.ysm.model.domain;

import cc.sirrus.ysmlib.scene.SceneAnimation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Wire identity is source path plus source-local clip, never a display label or client alias. */
public final class SceneActionId {
    private SceneActionId() {}
    public static String of(SceneAnimation animation, boolean loop) {
        String source = animation.selection().sourceId().startsWith("@ysm/generated/")
                ? animation.selection().sourceId() : animation.sourcePath();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (source + "\n" + animation.selection().clip()).getBytes(StandardCharsets.UTF_8));
            return "scene/" + HexFormat.of().formatHex(digest) + (loop ? "/loop" : "/once");
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    public static String base(String id) {
        if (id == null) return "";
        return id.matches("scene/[0-9a-f]{64}/(loop|once)(~[0-9a-f]{16})?")
                ? id.split("~", 2)[0] : "";
    }
    public static boolean looping(String id) { return base(id).endsWith("/loop"); }
    public static String request(String id, long serial) {
        String base = base(id);
        if (base.isEmpty()) throw new IllegalArgumentException("Invalid scene action ID");
        return base + "~" + String.format(java.util.Locale.ROOT, "%016x", serial);
    }
}
