package com.elfmcys.ysm.natives;

import com.elfmcys.ysm.buffer.UniBuffer;
import cc.sirrus.ysmlib.YsmRuntime;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.lang.ref.Reference;
import java.util.Objects;

/** Transitional mod adapter; all hashing is supplied by the independent runtime. */
public final class Blake3 {
    public static final int HASH_SIZE = 32;
    private Blake3() {}

    public static boolean validateHash(UniBuffer source, byte[] hash) {
        requireHash(hash);
        return MessageDigest.isEqual(computeHash(source), hash);
    }
    public static byte[] computeHash(UniBuffer source) {
        try { return YsmRuntime.hashes().blake3(source.nio()); }
        finally { Reference.reachabilityFence(source); }
    }
    /** Hashes a read-only/direct view without first copying it into a UniBuffer. */
    public static byte[] computeHash(ByteBuffer source) {
        Objects.requireNonNull(source, "source");
        return YsmRuntime.hashes().blake3(source.asReadOnlyBuffer());
    }
    public static void computeHash(UniBuffer source, byte[] hash) {
        requireHash(hash);
        System.arraycopy(computeHash(source), 0, hash, 0, HASH_SIZE);
    }
    private static void requireHash(byte[] hash) {
        Objects.requireNonNull(hash, "hash");
        if (hash.length != HASH_SIZE) throw new IllegalArgumentException("Expected BLAKE3-256");
    }

}
