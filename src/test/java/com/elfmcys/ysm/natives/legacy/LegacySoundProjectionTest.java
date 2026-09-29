package com.elfmcys.ysm.natives.legacy;

import com.elfmcys.ysm.proto.mixel.common.Sound;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LegacySoundProjectionTest {
    @Test
    void requiresTheProjectedDescriptorToMatchThePreservedStream() throws Exception {
        var encoded = ByteBuffer.wrap(Files.readAllBytes(
                fixtureRoot().resolve("opus-under.ogg")));
        var record = new LegacyPayloadContract.PayloadRecord(
                LegacyPayloadContract.PayloadKind.SOUND_STREAM,
                LegacyPayloadContract.PayloadEncoding.OGG_OPUS,
                1, "tone", 0, 0, 0, encoded.remaining());
        var valid = sound("tone", "OGG_OPUS", 2, 48_000, 191_999, 1);

        assertDoesNotThrow(() -> LegacyModelImporter.validateSound(
                record, encoded, Map.of(1, valid)));
        assertThrows(LegacyPayloadContract.ProtocolException.class,
                () -> LegacyModelImporter.validateSound(record, encoded,
                        Map.of(1, sound("tone", "OGG_OPUS", 2,
                                48_000, 192_000, 1))));
        assertThrows(LegacyPayloadContract.ProtocolException.class,
                () -> LegacyModelImporter.validateSound(record, encoded,
                        Map.of(1, sound("other", "OGG_OPUS", 2,
                                48_000, 191_999, 1))));
        assertThrows(LegacyPayloadContract.ProtocolException.class,
                () -> LegacyModelImporter.validateSound(record, encoded,
                        Map.of(1, sound("tone", "OGG_VORBIS", 2,
                                48_000, 191_999, 1))));
    }

    private static Sound sound(
            String name, String encoding, int channels, int sampleRate,
            long frames, int streamId) {
        return Sound.newBuilder()
                .setName(name)
                .setEncoding(encoding)
                .setChannels(channels)
                .setSampleRate(sampleRate)
                .setSamples(frames)
                .setStreamId(streamId)
                .build();
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
