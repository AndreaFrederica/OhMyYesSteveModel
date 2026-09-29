package cc.sirrus.ysmlib.codec.java;

import java.io.IOException;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EmptyFrameIntegrityTest {
    @Test void emptyLogicalOutputStillValidatesCompressedInput() throws Exception {
        var codec = new JavaCompressionProvider();
        var frame = codec.compress(ByteBuffer.allocate(0), 3, 1024);
        assertEquals(0, codec.decompress(ByteBuffer.wrap(frame), 0, 0).length);
        frame[0] ^= 1;
        assertThrows(IOException.class, () -> codec.decompress(ByteBuffer.wrap(frame), 0, 0));
        assertThrows(IOException.class, () -> codec.decompress(ByteBuffer.allocate(0), 0, 0));
    }
}
