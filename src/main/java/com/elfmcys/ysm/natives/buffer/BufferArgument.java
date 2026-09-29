package com.elfmcys.ysm.natives.buffer;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.buffer.annotation.Owned;

import java.nio.ByteBuffer;

public class BufferArgument {
    public static Input packInput(UniBuffer buf) {
        if (buf instanceof ArrayBuffer arrayBuf) {
            long flags = ((long) arrayBuf.arrayOffset() << 32)
                    | ((long) arrayBuf.size() & 0xFFFF_FFFFL);
            return new Input(arrayBuf.array(), flags);
        } else if (buf instanceof NativeBuffer nativeBuf) {
            var nio = nativeBuf.nio();
            long flags = Long.MIN_VALUE
                    | ((long) nio.position() << 32)
                    | ((long) nio.remaining() & 0xFFFF_FFFFL);
            return new Input(nio, flags);
        }
        // Generic read-only capture views deliberately expose neither raw pointers
        // nor mutable arrays. Direct views can be borrowed; heap views need a copy.
        var nio = buf.nio();
        if (!nio.isReadOnly()) {
            throw new IllegalArgumentException("Unknown mutable UniBuffer type: " + buf.getClass().getName());
        }
        if (nio.isDirect()) {
            long flags = Long.MIN_VALUE | ((long) nio.position() << 32)
                    | (nio.remaining() & 0xFFFF_FFFFL);
            return new Input(nio, flags);
        }
        var bytes = new byte[nio.remaining()];
        nio.get(bytes);
        return new Input(bytes, bytes.length);
    }

    public record Input(Object obj, long flags) {}
}
