package com.elfmcys.ysm.format.legacy;

import com.elfmcys.ysm.buffer.UniBuffer;
import java.io.IOException;
import java.nio.ByteOrder;
import java.util.Objects;

/** Optional envelope capability; independent of current-model projection. */
public interface LegacyV3Decoder {
    int SOURCE_BYTE_LIMIT = 69_206_016;

    /** Increment whenever envelope decoding changes the captured bytes. */
    int profile();

    /** Borrows source synchronously; the caller closes the returned wire. */
    DecodedWire decodeWire(UniBuffer source) throws IOException;

    record DecodedWire(int innerVersion, UniBuffer plaintext) implements AutoCloseable {
        public DecodedWire {
            Objects.requireNonNull(plaintext, "plaintext");
            if (innerVersion < 1 || innerVersion > 32 || plaintext.size() < 4
                    || plaintext.size() > UniBuffer.MAX_SIZE
                    || plaintext.nio().order(ByteOrder.LITTLE_ENDIAN).getInt() != innerVersion) {
                throw new IllegalArgumentException("Invalid historical wire version or size");
            }
        }

        @Override
        public void close() {
            plaintext.close();
        }
    }
}
