package cc.sirrus.ysmlib.codec.java;

import com.google.gson.JsonParser;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PortableCodecTest {
    @Test void matchesOfficialBlake3VectorsAcrossTreeBoundariesAndBufferKinds() throws Exception {
        var hash = new JavaHashProvider();
        try (var resource = getClass().getResourceAsStream("/blake3-test-vectors.json")) {
            var vectors = JsonParser.parseReader(new InputStreamReader(resource, StandardCharsets.UTF_8));
            for (var item : vectors.getAsJsonObject().getAsJsonArray("cases")) {
                var vector = item.getAsJsonObject();
                int length = vector.get("input_len").getAsInt();
                byte[] source = new byte[length];
                for (int i = 0; i < length; i++) source[i] = (byte) (i % 251);
                byte[] expected = HexFormat.of().parseHex(vector.get("hash").getAsString().substring(0, 64));
                assertArrayEquals(expected, hash.blake3(ByteBuffer.wrap(source)));
                ByteBuffer direct = ByteBuffer.allocateDirect(length + 6);
                direct.position(3); direct.put(source); direct.limit(3 + length); direct.position(3);
                assertArrayEquals(expected, hash.blake3(direct.asReadOnlyBuffer()), "direct length=" + length);
                assertEquals(3, direct.position());
            }
        }
    }

    @Test void decodesIndependentLibzstdFramesAndRejectsDamage() throws Exception {
        var codec = new JavaCompressionProvider();
        byte[] expected = fixture("zstd-payload.bin");
        for (String file : new String[]{"zstd-level1.zst", "zstd-level19-checksum.zst", "zstd-no-size.zst", "zstd-concatenated.zst"}) {
            byte[] frame = fixture(file);
            assertArrayEquals(expected, codec.decompress(ByteBuffer.wrap(frame), expected.length, 1_000_000), file);
            assertThrows(IOException.class, () -> codec.decompress(ByteBuffer.wrap(frame), expected.length - 1, 1_000_000));
            assertThrows(IOException.class, () -> codec.decompress(ByteBuffer.wrap(frame), expected.length + 1, 1_000_000));
            assertThrows(IOException.class, () -> codec.decompress(ByteBuffer.wrap(frame), expected.length, 1));
            byte[] shortFrame = Arrays.copyOf(frame, frame.length - 1);
            assertThrows(IOException.class, () -> codec.decompress(ByteBuffer.wrap(shortFrame), expected.length, 1_000_000));
        }
        byte[] checksum = fixture("zstd-level19-checksum.zst"); checksum[checksum.length-1] ^= 1;
        assertThrows(IOException.class, () -> codec.decompress(ByteBuffer.wrap(checksum), expected.length, 1_000_000));
    }

    @Test void compressesBoundedPayloadAndWritesInteropArtifact() throws Exception {
        var codec = new JavaCompressionProvider();
        byte[] payload = fixture("zstd-payload.bin");
        ByteBuffer input = ByteBuffer.wrap(payload).asReadOnlyBuffer();
        byte[] compressed = codec.compress(input, 16, 1_000_000);
        assertEquals(0, input.position());
        assertArrayEquals(payload, codec.decompress(ByteBuffer.wrap(compressed), payload.length, 1_000_000));
        assertThrows(IOException.class, () -> codec.compress(input, 16, 16));
        Path result = Path.of("build/interop/java-zstd.zst");
        Files.createDirectories(result.getParent()); Files.write(result, compressed);
    }

    private byte[] fixture(String name) throws IOException {
        try (var resource = getClass().getResourceAsStream("/" + name)) {
            if (resource == null) throw new FileNotFoundException(name);
            return resource.readAllBytes();
        }
    }
}
