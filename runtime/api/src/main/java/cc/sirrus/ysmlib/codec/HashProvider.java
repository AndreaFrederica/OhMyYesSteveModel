package cc.sirrus.ysmlib.codec;

import java.nio.ByteBuffer;

/** BLAKE3-256 capability. Input range and position are preserved. */
public interface HashProvider {
    String id();
    byte[] blake3(ByteBuffer input);
}
