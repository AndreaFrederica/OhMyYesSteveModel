package cc.sirrus.ysmlib.codec;

import java.io.IOException;
import java.nio.ByteBuffer;

/** Zstandard frames; exact decompressed size and explicit budgets are mandatory. */
public interface CompressionProvider {
    String id();
    byte[] compress(ByteBuffer input, int level, int maxOutputBytes) throws IOException;
    byte[] decompress(ByteBuffer input, int outputBytes, int maxOutputBytes) throws IOException;
}
