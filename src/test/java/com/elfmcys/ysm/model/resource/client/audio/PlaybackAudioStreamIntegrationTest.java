package com.elfmcys.ysm.model.resource.client.audio;

import com.elfmcys.ysm.buffer.NativeBuffer;
import cc.sirrus.ysmlib.audio.SupportedAudioProbe;
import com.elfmcys.ysm.testutil.NativeLibraryExtension;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(NativeLibraryExtension.class)
class PlaybackAudioStreamIntegrationTest {
    private static final AudioRetentionCache.Receipt RECEIPT =
            new AudioRetentionCache.Receipt(0, 1);

    @Test
    void normalShortEofPublishesExactColdBytesForHotReplay() throws Exception {
        var fixture = fixture("opus-under");
        var publication = new AtomicReference<PcmAudio>();
        var closes = new AtomicInteger();
        var cold = stream(fixture.encoded(), false,
                (pcm, ignored) -> publication.set(pcm), closes);
        byte[] coldBytes;
        try (cold) {
            coldBytes = readAll(cold, 511);
        }

        var cached = publication.get();
        assertTrue(cached != null);
        assertEquals(fixture.reference().length, cached.size());
        var hot = new PlaybackAudioStream(cached.acquire(), false, RECEIPT,
                () -> true, (pcm, ignored) -> { }, () -> { }, ignored -> { });
        byte[] hotBytes;
        try (hot) {
            hotBytes = readAll(hot, 4095);
        }
        assertArrayEquals(coldBytes, hotBytes);
        assertPcmReference(fixture.reference(), coldBytes);
        assertEquals(1, closes.get());
    }

    @Test
    void exactThresholdDoesNotPublishPcm() throws Exception {
        var fixture = fixture("vorbis-exact");
        var publication = new AtomicReference<PcmAudio>();
        var stream = stream(fixture.encoded(), false,
                (pcm, ignored) -> publication.set(pcm), new AtomicInteger());
        try (stream) {
            readAll(stream, 8192);
        }
        assertNull(publication.get());
    }

    @Test
    void longLoopReopensAnIndependentDecoderWithoutLosingTheBoundary() throws Exception {
        var fixture = fixture("opus-long");
        var stream = stream(fixture.encoded(), true,
                (pcm, ignored) -> { }, new AtomicInteger());
        byte[] twoCycles;
        try (stream) {
            twoCycles = readExactly(stream, fixture.reference().length * 2, 8192);
        }
        var expected = new byte[twoCycles.length];
        System.arraycopy(fixture.reference(), 0, expected, 0, fixture.reference().length);
        System.arraycopy(fixture.reference(), 0, expected, fixture.reference().length,
                fixture.reference().length);
        assertPcmReference(expected, twoCycles);
    }

    @Test
    void cancellationBeforeEofDiscardsTheCandidate() throws Exception {
        var fixture = fixture("vorbis-under");
        var publication = new AtomicReference<PcmAudio>();
        var stream = stream(fixture.encoded(), false,
                (pcm, ignored) -> publication.set(pcm), new AtomicInteger());
        assertTrue(stream.read(257).hasRemaining());
        stream.close();
        assertNull(publication.get());
    }

    @Test
    void twoColdPlaybacksKeepIndependentPositions() throws Exception {
        var fixture = fixture("opus-long");
        var media = inspect(fixture.encoded());
        var owner = new EncodedAudio(
                NativeBuffer.copyOf(ByteBuffer.wrap(fixture.encoded())), media);
        var first = new PlaybackAudioStream(owner.acquire(), false, RECEIPT,
                () -> true, (pcm, ignored) -> { }, () -> { }, ignored -> { });
        var second = new PlaybackAudioStream(owner.acquire(), false, RECEIPT,
                () -> true, (pcm, ignored) -> { }, () -> { }, ignored -> { });
        owner.close();
        try (first; second) {
            assertArrayEquals(bytes(first.read(4096)), bytes(second.read(4096)));
            assertArrayEquals(bytes(first.read(8192)), bytes(second.read(8192)));
        }
    }

    @Test
    void shortLoopSwitchesToCachedPcmWithoutChangingSamples() throws Exception {
        var fixture = fixture("vorbis-under");
        var publication = new AtomicReference<PcmAudio>();
        try (var playback = stream(fixture.encoded(), true,
                (pcm, ignored) -> publication.set(pcm), new AtomicInteger())) {
            var actual = readExactly(playback, fixture.reference().length * 2, 8192);
            var expected = new byte[actual.length];
            System.arraycopy(fixture.reference(), 0, expected, 0, fixture.reference().length);
            System.arraycopy(fixture.reference(), 0, expected, fixture.reference().length,
                    fixture.reference().length);
            assertTrue(publication.get() != null);
            com.elfmcys.ysm.testutil.AudioAssertions.reference(
                    expected, actual, false, "Vorbis cached loop");
        }
    }

    @Test
    void cachedPlaybacksOwnTheirUploadBuffersAndPositions() throws Exception {
        byte[] samples = {1, 2, 3, 4, 5, 6, 7, 8};
        var pcm = new PcmAudio(samples,
                new javax.sound.sampled.AudioFormat(44100, 16, 1, true, false));
        try (var first = cachedStream(pcm, false); var second = cachedStream(pcm, false)) {
            var firstChunk = first.read(4);
            assertTrue(firstChunk.isDirect());
            assertEquals(0, firstChunk.position());
            assertEquals(4, firstChunk.remaining());
            assertArrayEquals(new byte[]{1, 2}, bytes(second.read(2)));
            assertArrayEquals(new byte[]{3, 4, 5, 6}, bytes(second.read(4)));
            // Reading another playback must not overwrite a pending host upload.
            assertArrayEquals(new byte[]{1, 2, 3, 4}, bytes(firstChunk));
            assertArrayEquals(new byte[]{5, 6}, bytes(first.read(3)));
            assertArrayEquals(new byte[]{7, 8}, bytes(first.read(8192)));
            assertTrue(first.read(8192).isDirect());
            assertEquals(0, first.read(8192).remaining());
            assertArrayEquals(new byte[]{7, 8}, bytes(second.read(8192)));
        }
    }

    private static PlaybackAudioStream cachedStream(PcmAudio pcm, boolean looping)
            throws Exception {
        return new PlaybackAudioStream(pcm.acquire(), looping, RECEIPT,
                () -> true, (ignored, receipt) -> { }, () -> { }, ignored -> { });
    }

    private static PlaybackAudioStream stream(
            byte[] encoded, boolean looping,
            PlaybackAudioStream.PcmPublisher publisher,
            AtomicInteger closes) throws Exception {
        var audio = new EncodedAudio(
                NativeBuffer.copyOf(ByteBuffer.wrap(encoded)), inspect(encoded));
        return new PlaybackAudioStream(audio, looping, RECEIPT,
                () -> true, publisher, closes::incrementAndGet, ignored -> { });
    }

    private static SupportedAudioProbe.MediaInfo inspect(byte[] encoded) {
        var inspection = SupportedAudioProbe.inspect(ByteBuffer.wrap(encoded));
        assertTrue(inspection.playable(), inspection.diagnostic());
        return inspection.media();
    }

    private static byte[] readAll(PlaybackAudioStream stream, int size) throws Exception {
        var output = new ByteArrayOutputStream();
        while (true) {
            var chunk = stream.read(size);
            assertTrue(chunk.isDirect(), "OpenAL uploads require direct buffers, including EOF");
            if (!chunk.hasRemaining()) {
                return output.toByteArray();
            }
            output.writeBytes(bytes(chunk));
        }
    }

    private static byte[] readExactly(PlaybackAudioStream stream, int length, int size)
            throws Exception {
        var output = new ByteArrayOutputStream(length);
        while (output.size() < length) {
            var chunk = stream.read(Math.min(size, length - output.size()));
            assertTrue(chunk.isDirect(), "OpenAL uploads require direct buffers");
            assertTrue(chunk.hasRemaining());
            output.writeBytes(bytes(chunk));
        }
        return output.toByteArray();
    }

    private static byte[] bytes(ByteBuffer buffer) {
        var result = new byte[buffer.remaining()];
        buffer.get(result);
        return result;
    }

    private static Fixture fixture(String name) throws Exception {
        var root = Path.of(System.getenv("YSM_AUDIO_FIXTURE_DIR"));
        return new Fixture(Files.readAllBytes(root.resolve(name + ".ogg")),
                Files.readAllBytes(root.resolve(name + ".s16le")));
    }

    private static void assertPcmReference(byte[] expected, byte[] actual) {
        com.elfmcys.ysm.testutil.AudioAssertions.reference(expected, actual, true, "Opus playback");
    }

    private record Fixture(byte[] encoded, byte[] reference) {
    }
}
