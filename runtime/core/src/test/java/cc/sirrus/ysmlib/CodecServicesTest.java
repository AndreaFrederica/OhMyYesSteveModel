package cc.sirrus.ysmlib;

import cc.sirrus.ysmlib.codec.*;
import cc.sirrus.ysmlib.codec.java.*;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CodecServicesTest {
    @Test void javaOnlyNeverInvokesLoader() {
        var services = CodecServices.create(true, () -> { throw new AssertionError("Native load attempted"); });
        assertEquals("java-blake3", services.hashes.id());
        assertEquals("java-zstd", services.compression.id());
    }

    @Test void absentLibraryFallsBack() {
        var services = CodecServices.create(false, () -> { throw new UnsatisfiedLinkError("absent"); });
        assertArrayEquals(new JavaHashProvider().blake3(ByteBuffer.allocate(0)),
                services.hashes.blake3(ByteBuffer.allocate(0)));
        assertEquals("java-zstd", services.compression.id());
    }

    @Test void linkageFailureDisablesOnlyFailedCapabilityAndPreservesInput() throws Exception {
        var calls = new AtomicInteger();
        HashProvider broken = new HashProvider() {
            public String id() { return "broken"; }
            public byte[] blake3(ByteBuffer input) {
                calls.incrementAndGet(); input.get(); throw new UnsatisfiedLinkError("missing hash symbol");
            }
        };
        var services = new CodecServices(new JavaHashProvider(), new JavaCompressionProvider(), broken,
                new JavaCompressionProvider());
        var source = ByteBuffer.wrap(new byte[]{99, 1, 2, 3, 88}); source.position(1); source.limit(4);
        byte[] expected = new JavaHashProvider().blake3(source);
        assertArrayEquals(expected, services.hashes.blake3(source));
        assertArrayEquals(expected, services.hashes.blake3(source));
        assertEquals(1, calls.get()); assertEquals(1, source.position()); assertEquals(4, source.limit());
        assertEquals("java-blake3", services.hashes.id());
        byte[] encoded = services.compression.compress(source, 3, 1024);
        assertArrayEquals(new byte[]{1, 2, 3}, services.compression.decompress(ByteBuffer.wrap(encoded), 3, 3));
    }

    @Test void contentErrorsAreNeverRetried() {
        var failure = new IOException("bad frame");
        CompressionProvider bad = new CompressionProvider() {
            public String id() { return "content-error"; }
            public byte[] compress(ByteBuffer in, int level, int budget) throws IOException { throw failure; }
            public byte[] decompress(ByteBuffer in, int length, int budget) throws IOException { throw failure; }
        };
        var services = new CodecServices(new JavaHashProvider(), new JavaCompressionProvider(), null, bad);
        assertSame(failure, assertThrows(IOException.class,
                () -> services.compression.decompress(ByteBuffer.allocate(0), 0, 0)));
        assertSame(failure, assertThrows(IOException.class,
                () -> services.compression.compress(ByteBuffer.allocate(0), 3, 100)));
        assertEquals("content-error", services.compression.id());
    }
}
