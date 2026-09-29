package cc.sirrus.ysmlib.codec.natives;

import cc.sirrus.ysmlib.codec.java.*;
import org.junit.jupiter.api.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class NativeCodecTest {
    static NativeCodecProvider nativeCodec;
    @BeforeAll static void load() {
        String library = System.getProperty("ysm.test.codecLibrary", "");
        Assumptions.assumeFalse(library.isBlank(), "Optional native conformance: run nativeTest with -PcodecLibrary");
        nativeCodec = new NativeCodecProvider(Path.of(library));
    }

    @Test void hashMatchesJavaAcrossChunkBoundariesAndViews() {
        var javaHash = new JavaHashProvider();
        for (int size : new int[]{0, 1, 63, 64, 65, 1023, 1024, 1025, 2048, 16385, 1000000}) {
            byte[] bytes = new byte[size]; new Random(7321).nextBytes(bytes);
            var source = ByteBuffer.allocateDirect(size + 2); source.put((byte) 5).put(bytes).put((byte) 9);
            source.position(1); source.limit(size + 1);
            assertArrayEquals(javaHash.blake3(source), nativeCodec.blake3(source.asReadOnlyBuffer()), "length=" + size);
            assertEquals(1, source.position()); assertEquals(size + 1, source.limit());
        }
    }

    @Test void zstdInteroperatesBothWays() throws Exception {
        var javaCodec = new JavaCompressionProvider();
        for (int size : new int[]{0, 1, 255, 65537, 475800}) {
            byte[] bytes = new byte[size]; new Random(9927).nextBytes(bytes);
            byte[] encodedJava = javaCodec.compress(ByteBuffer.wrap(bytes), 3, size * 2 + 1024);
            assertArrayEquals(bytes, nativeCodec.decompress(ByteBuffer.wrap(encodedJava), size, size));
            for (int level : new int[]{1, 3, 19}) {
                byte[] encodedNative = nativeCodec.compress(ByteBuffer.wrap(bytes), level, size * 2 + 1024);
                assertArrayEquals(bytes, javaCodec.decompress(ByteBuffer.wrap(encodedNative), size, size));
            }
        }
    }

    @Test void rejectsCorruptFramesSizeMismatchAndBudgetOverflow() throws Exception {
        assertThrows(IOException.class, () -> nativeCodec.decompress(ByteBuffer.wrap(new byte[]{1, 2}), 10, 10));
        assertThrows(IOException.class, () -> nativeCodec.decompress(ByteBuffer.allocate(0), 11, 10));
        assertThrows(IOException.class, () -> nativeCodec.compress(ByteBuffer.allocate(10), 3, 0));
        byte[] frame = nativeCodec.compress(ByteBuffer.wrap(new byte[]{1, 2, 3}), 3, 1024);
        assertThrows(IOException.class, () -> nativeCodec.decompress(ByteBuffer.wrap(frame), 4, 4));
    }
}
