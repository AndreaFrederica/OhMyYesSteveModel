package com.elfmcys.ysm.natives.legacy;

import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.format.legacy.JavaLegacyV3Decoder;
import com.elfmcys.ysm.format.legacy.LegacyV3Decoder;
import java.io.IOException;

/** Compatibility name for the portable V3 decoder. */
public final class NativeLegacyV3Decoder implements LegacyV3Decoder {
    private final JavaLegacyV3Decoder delegate = new JavaLegacyV3Decoder();
    @Override public int profile() { return delegate.profile(); }
    @Override public DecodedWire decodeWire(UniBuffer source) throws IOException {
        return delegate.decodeWire(source);
    }
}
