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
        String source = animation.selection().sourceId().startsWith("@ysm/")
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
    /** Tagged nonce: world start tick (40 bits) and per-click serial (16 bits), still the same authorized base ID. */
    public static String requestAt(String id,long tick,long serial){if(tick<0||tick>0xffffffffffL)throw new IllegalArgumentException("Scene start tick out of range");return request(id,0x5900000000000000L|(tick<<16)|(serial&65535));}
    public static java.util.OptionalLong startTick(String id){if(base(id).isEmpty()||!id.contains("~"))return java.util.OptionalLong.empty();long nonce=Long.parseUnsignedLong(id.substring(id.indexOf('~')+1),16);return (nonce>>>56)==0x59?java.util.OptionalLong.of((nonce>>>16)&0xffffffffffL):java.util.OptionalLong.empty();}
}
