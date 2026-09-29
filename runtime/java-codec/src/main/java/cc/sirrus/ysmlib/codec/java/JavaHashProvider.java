package cc.sirrus.ysmlib.codec.java;

import cc.sirrus.ysmlib.codec.HashProvider;
import java.nio.ByteBuffer;
import org.bouncycastle.crypto.digests.Blake3Digest;

public final class JavaHashProvider implements HashProvider {
    @Override public String id() { return "java-blake3"; }

    @Override public byte[] blake3(ByteBuffer input) {
        var bytes = input.duplicate();
        var digest = new Blake3Digest(256);
        if (bytes.hasArray()) {
            digest.update(bytes.array(), bytes.arrayOffset() + bytes.position(), bytes.remaining());
        } else {
            byte[] block = new byte[Math.min(8192, bytes.remaining())];
            while (bytes.hasRemaining()) {
                int count = Math.min(block.length, bytes.remaining());
                bytes.get(block, 0, count); digest.update(block, 0, count);
            }
        }
        byte[] result = new byte[32]; digest.doFinal(result, 0); return result;
    }
}
