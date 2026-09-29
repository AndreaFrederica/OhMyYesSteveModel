package com.elfmcys.ysm.natives;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.buffer.annotation.Owned;
import cc.sirrus.ysmlib.YsmRuntime;
import java.io.IOException;
import java.lang.ref.Reference;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import org.jetbrains.annotations.Nullable;

/** Transitional mod adapter for the prerequisite's Java Zstandard implementation. */
public final class Zstd {
    private Zstd() {}

    @Owned
    public static UniBuffer compressAndHash(UniBuffer source, byte @Nullable [] hash, BufferType type, int level) {
        checkHash(hash);
        try {
            byte[] bytes = YsmRuntime.compression().compress(source.nio(), level, UniBuffer.MAX_SIZE);
            if (hash != null) System.arraycopy(YsmRuntime.hashes().blake3(source.nio()), 0, hash, 0, 32);
            return output(bytes, type);
        } catch (IOException invalid) { throw new IllegalStateException("Zstd compression failed", invalid); }
        finally { Reference.reachabilityFence(source); }
    }

    @Owned
    public static UniBuffer decompressAndValidate(UniBuffer source, int outputSize, byte @Nullable [] hash, BufferType type) {
        checkHash(hash);
        try {
            byte[] bytes = YsmRuntime.compression().decompress(source.nio(), outputSize, UniBuffer.MAX_SIZE);
            if (hash != null && !MessageDigest.isEqual(hash, YsmRuntime.hashes().blake3(ByteBuffer.wrap(bytes)))) {
                throw new IOException("Zstd decoded content hash mismatch");
            }
            return output(bytes, type);
        } catch (IOException invalid) { throw new IllegalStateException("Zstd decompression failed", invalid); }
        finally { Reference.reachabilityFence(source); }
    }
    private static UniBuffer output(byte[] bytes, BufferType type) {
        return switch (type) {
            case ARRAY -> ArrayBuffer.move(bytes);
            case NATIVE -> NativeBuffer.copyOf(ByteBuffer.wrap(bytes));
        };
    }
    private static void checkHash(byte[] hash) {
        if (hash != null && hash.length != 32) throw new IllegalArgumentException("Expected BLAKE3-256");
    }

}
