package com.elfmcys.ysm.format.legacy;

import cc.sirrus.ysmlib.YsmRuntime;
import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.UniBuffer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Host buffer adapter for the independent V3 envelope capability. */
public final class JavaLegacyV3Decoder implements LegacyV3Decoder {
    @Override public int profile() {return YsmRuntime.v3().profile();}
    @Override public DecodedWire decodeWire(UniBuffer source) throws IOException {
        byte[] plain=YsmRuntime.v3().decode(source.nio(),UniBuffer.MAX_SIZE);
        return new DecodedWire(ByteBuffer.wrap(plain).order(ByteOrder.LITTLE_ENDIAN).getInt(),ArrayBuffer.move(plain));
    }
}
