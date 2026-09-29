package com.elfmcys.ysm.client.sound.stream;

import cc.sirrus.ysmlib.audio.SupportedAudioProbe;
import com.elfmcys.ysm.format.AssetLoadException;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "YSM_AUDIO_FIXTURE_DIR", matches = ".+")
class AudioDecoderIntegrationTest {
    private static final int[] READ_SIZES = {1, 3, 17, 511, 4095, 8192};

    @Test
    void decodesEveryContractFixtureThroughTheRuntimeAdapters() throws Exception {
        var root = fixtureRoot();
        var manifest = JsonParser.parseString(Files.readString(root.resolve("manifest.json")))
                .getAsJsonObject();
        for (var element : manifest.getAsJsonArray("cases")) {
            var fixture = element.getAsJsonObject();
            String id = fixture.get("id").getAsString();
            byte[] encodedBytes = Files.readAllBytes(root.resolve(
                    fixture.get("encoded").getAsString()));
            byte[] expected = Files.readAllBytes(root.resolve(
                    fixture.get("reference").getAsString()));
            var encoded = ByteBuffer.allocateDirect(encodedBytes.length);
            encoded.put(encodedBytes).flip();

            var inspection = SupportedAudioProbe.inspect(encoded);
            assertTrue(inspection.playable(), id + ": " + inspection.diagnostic());
            CustomAudioStream stream = inspection.media().encoding()
                    == SupportedAudioProbe.Encoding.OGG_OPUS
                    ? new OpusAudioStream(encoded, inspection.media())
                    : new VorbisAudioStream(encoded, inspection.media());
            byte[] actual;
            try {
                assertFalse(stream.read(0).hasRemaining(), id);
                actual = readAll(stream);
                assertFalse(stream.read(23).hasRemaining(), id);
            } finally {
                stream.close();
                stream.close();
            }

            assertEquals(expected.length, actual.length, id + " byte count");
            assertEquals(fixture.get("frames").getAsLong(), actual.length / 2L,
                    id + " frame count");
            assertPcmReference(expected, actual, id);
        }
    }

    @Test
    void rejectsCodecCorruptionAfterStructuralAdmission() throws Exception {
        var root = fixtureRoot();

        byte[] vorbisBytes = Files.readAllBytes(root.resolve("vorbis-under.ogg"));
        byte[] corruptSetup = vorbisBytes.clone();
        int setup = find(corruptSetup, new byte[]{5, 'v', 'o', 'r', 'b', 'i', 's'});
        assertTrue(setup >= 0);
        corruptSetup[setup + 20] ^= 0x40;
        rewritePageChecksum(corruptSetup, pageContaining(corruptSetup, setup));
        var corruptInspection = SupportedAudioProbe.inspect(ByteBuffer.wrap(corruptSetup));
        assertTrue(corruptInspection.playable(), corruptInspection.diagnostic());
        var failure = assertThrows(AssetLoadException.class,
                () -> decodeAndClose(new VorbisAudioStream(
                        direct(corruptSetup), corruptInspection.media())));
        assertEquals(AssetLoadException.Reason.CONTENT, failure.reason());
    }

    private static byte[] readAll(CustomAudioStream stream) throws IOException {
        var output = new ByteArrayOutputStream();
        for (int index = 0; ; index++) {
            var chunk = stream.read(READ_SIZES[index % READ_SIZES.length]);
            if (!chunk.hasRemaining()) {
                return output.toByteArray();
            }
            byte[] bytes = new byte[chunk.remaining()];
            chunk.get(bytes);
            output.write(bytes);
        }
    }

    private static void decodeAndClose(CustomAudioStream stream) throws IOException {
        try {
            readAll(stream);
        } finally {
            stream.close();
            stream.close();
        }
    }

    private static ByteBuffer direct(byte[] bytes) {
        var buffer = ByteBuffer.allocateDirect(bytes.length);
        return buffer.put(bytes).flip();
    }

    private static int find(byte[] bytes, byte[] pattern) {
        outer:
        for (int offset = 0; offset <= bytes.length - pattern.length; offset++) {
            for (int index = 0; index < pattern.length; index++) {
                if (bytes[offset + index] != pattern[index]) {
                    continue outer;
                }
            }
            return offset;
        }
        return -1;
    }

    private static int pageContaining(byte[] bytes, int target) {
        for (int offset = 0; offset < bytes.length; ) {
            int size = pageSize(bytes, offset);
            if (target < offset + size) {
                return offset;
            }
            offset += size;
        }
        throw new IllegalArgumentException("target is outside Ogg pages");
    }

    private static int pageSize(byte[] bytes, int offset) {
        int segments = Byte.toUnsignedInt(bytes[offset + 26]);
        int size = 27 + segments;
        for (int index = 0; index < segments; index++) {
            size += Byte.toUnsignedInt(bytes[offset + 27 + index]);
        }
        return size;
    }

    private static void rewritePageChecksum(byte[] bytes, int offset) {
        for (int index = 22; index < 26; index++) {
            bytes[offset + index] = 0;
        }
        int crc = 0;
        int size = pageSize(bytes, offset);
        for (int index = 0; index < size; index++) {
            crc ^= Byte.toUnsignedInt(bytes[offset + index]) << 24;
            for (int bit = 0; bit < 8; bit++) {
                crc = crc << 1 ^ ((crc & 0x8000_0000) != 0 ? 0x04c1_1db7 : 0);
            }
        }
        for (int index = 0; index < 4; index++) {
            bytes[offset + 22 + index] = (byte) (crc >>> (index * 8));
        }
    }

    private static void assertPcmReference(byte[] expected, byte[] actual, String id) {
        com.elfmcys.ysm.testutil.AudioAssertions.reference(expected, actual, id.startsWith("opus"), id);
    }

    private static Path fixtureRoot() {
        var configured = System.getenv("YSM_AUDIO_FIXTURE_DIR");
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(
                    "YSM_AUDIO_FIXTURE_DIR must identify the contract fixture directory");
        }
        return Path.of(configured).toAbsolutePath().normalize();
    }
}
