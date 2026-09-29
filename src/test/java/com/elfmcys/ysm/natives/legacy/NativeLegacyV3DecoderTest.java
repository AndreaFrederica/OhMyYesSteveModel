package com.elfmcys.ysm.natives.legacy;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.format.legacy.V3dCache;
import com.elfmcys.ysm.testutil.NativeLibraryExtension;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(NativeLibraryExtension.class)
class NativeLegacyV3DecoderTest {
    @TempDir Path temp;
    private static final String WIRE_SHA256 =
            "3e41fd30736a8b89eb043e8eeefc7cc328491580e88a395ab2c4062df5ad0ea6";

    private static byte[] fixture() throws IOException {
        try (var stream = NativeLegacyV3DecoderTest.class.getResourceAsStream(
                "/legacy/legacy_v3_dynamic_vector.ysm")) {
            assertNotNull(stream);
            return stream.readAllBytes();
        }
    }

    @Test void capturesDynamicGoldenFromOffsetArrayAndDirectInputs() throws Exception {
        var decoder = new NativeLegacyV3Decoder();
        var source = fixture();
        assertEquals("548e5610a57e561f84878882f14dd3557aac1c73c073accc5ee970312d8ac2f3",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source)));
        var padded = new byte[source.length + 11];
        System.arraycopy(source, 0, padded, 7, source.length);
        var direct = ByteBuffer.allocateDirect(source.length + 11);
        direct.position(7).put(source).flip().position(7);
        try (var array = ArrayBuffer.move(padded, 7, source.length);
             var nativeInput = NativeBuffer.move(direct)) {
            for (var input : new com.elfmcys.ysm.buffer.UniBuffer[]{array, nativeInput}) {
                try (var decoded = decoder.decodeWire(input)) {
                    assertEquals(1, decoded.innerVersion());
                    assertEquals(12_004, decoded.plaintext().size());
                    var digest = MessageDigest.getInstance("SHA-256");
                    digest.update(decoded.plaintext().nio());
                    assertEquals(WIRE_SHA256, HexFormat.of().formatHex(digest.digest()));
                }
            }
        }
    }

    @Test void materializesAndRestoresRealEnvelopeThroughJni() throws Exception {
        var source = Files.write(temp.resolve("fixture.ysm"), fixture());
        var cache = new V3dCache(new NativeLegacyV3Decoder());
        var directory = cache.materialize(source, temp.resolve("cache"));
        assertEquals(WIRE_SHA256, V3dCache.validate(directory).wire().sha256());
        var restored = temp.resolve("restored.ysm");
        V3dCache.restoreOriginal(directory, restored);
        assertArrayEquals(fixture(), Files.readAllBytes(restored));
        assertEquals(directory, cache.materialize(source, temp.resolve("cache")));
    }

    @Test void invalidSourceFailsWithoutOwningOutput() {
        try (var input = ArrayBuffer.move(new byte[]{1, 2, 3})) {
            assertThrows(IOException.class, () -> new NativeLegacyV3Decoder().decodeWire(input));
        }
    }
}
