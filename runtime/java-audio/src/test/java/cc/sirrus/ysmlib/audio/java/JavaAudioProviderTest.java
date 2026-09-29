package cc.sirrus.ysmlib.audio.java;

import cc.sirrus.ysmlib.audio.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class JavaAudioProviderTest {
    private final AudioProvider provider = new JavaAudioProvider();

    @Test void decodesAllBuiltinSoundsWithExactFrameCounts() throws Exception {
        int count = 0;
        Path root = Path.of(System.getProperty("ysm.test.builtinRoot"));
        try (var paths = Files.walk(root)) {
            for (var path : paths.filter(p -> p.toString().endsWith(".ogg")).toList()) {
                var data = ByteBuffer.wrap(Files.readAllBytes(path)).asReadOnlyBuffer();
                var inspection = SupportedAudioProbe.inspect(data);
                assertTrue(inspection.playable(), path + ": " + inspection.diagnostic());
                long bytes = 0;
                try (var stream = provider.open(data, inspection.media())) {
                    var buffer = ByteBuffer.allocate(8192);
                    int length;
                    while ((length = stream.read(buffer.clear())) != 0) bytes += length;
                    assertEquals(0, stream.read(buffer.clear()));
                }
                assertEquals(inspection.media().frames() * 2, bytes, path.toString());
                assertEquals(0, data.position()); count++;
            }
        }
        assertEquals(54, count);
    }

    @Test void matchesIndependentPcmAndReadSizes() throws Exception {
        var checks = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
        for (String name : new String[]{"opus-mono", "opus-stereo", "vorbis-mono", "vorbis-stereo"}) {
            byte[] encoded = resource(name + ".ogg"), reference = resource(name + ".pcm");
            var inspection = SupportedAudioProbe.inspect(ByteBuffer.wrap(encoded));
            assertTrue(inspection.playable(), name + ": " + inspection.diagnostic());
            var output = new ByteArrayOutputStream();
            var stream = provider.open(ByteBuffer.wrap(encoded), inspection.media());
            try {
                int[] sizes = {2, 3, 18, 510, 8192}; int read = 0;
                while (true) {
                    var buffer = ByteBuffer.allocate(sizes[read++ % sizes.length]);
                    int count = stream.read(buffer);
                    if (count == 0) break;
                    output.write(buffer.array(), 0, count);
                }
            } finally { stream.close(); stream.close(); }
            assertThrows(IllegalStateException.class, () -> stream.read(ByteBuffer.allocate(2)));
            byte[] actual = output.toByteArray(); assertEquals(reference.length, actual.length, name);
            Files.createDirectories(Path.of("build/decoded"));
            Files.write(Path.of("build/decoded/" + name + ".pcm"), actual);
            var expectedPcm = ByteBuffer.wrap(reference).order(ByteOrder.LITTLE_ENDIAN);
            var actualPcm = ByteBuffer.wrap(actual).order(ByteOrder.LITTLE_ENDIAN);
            int maxDelta = 0;
            double signalEnergy = 0, errorEnergy = 0;
            while (expectedPcm.hasRemaining()) {
                int expected = expectedPcm.getShort(), value = actualPcm.getShort();
                int delta = Math.abs(expected - value);
                maxDelta = Math.max(maxDelta, delta);
                signalEnergy += (double) expected * expected; errorEnergy += (double) delta * delta;
            }
            final int maximum = maxDelta;
            final double snr = errorEnergy == 0 ? Double.POSITIVE_INFINITY : 10 * Math.log10(signalEnergy / errorEnergy);
            // Concentus is the fixed-point Opus implementation; ffmpeg's decoder uses floats.
            // Keep a peak bound as well as signal/error energy; frame count above remains exact.
            int peakBound = name.startsWith("opus") ? 8 : 2;
            checks.add(() -> assertTrue(maximum <= peakBound && snr >= 70,
                    name + " maximum difference " + maximum + " LSB, SNR=" + snr));
        }
        assertAll(checks);
    }

    @Test void rejectsCrcAndMetadataConflictsAndOwnsSourceCopy() throws Exception {
        byte[] source = resource("opus-mono.ogg");
        var buffer = ByteBuffer.wrap(source); var media = SupportedAudioProbe.inspect(buffer).media();
        try (var stream = provider.open(buffer, media)) {
            java.util.Arrays.fill(source, (byte) 0);
            assertTrue(stream.read(ByteBuffer.allocate(20)) > 0);
        }
        assertThrows(IOException.class, () -> provider.open(buffer, media));
        var mismatch = new SupportedAudioProbe.MediaInfo(media.encoding(), media.channels(), media.sampleRate(),
                media.frames() + 1, media.preSkip(), media.outputGain());
        assertThrows(IOException.class, () -> provider.open(ByteBuffer.wrap(resource("opus-mono.ogg")), mismatch));
    }

    private static byte[] resource(String name) throws IOException {
        try (var in = JavaAudioProviderTest.class.getResourceAsStream("/" + name)) {
            if (in == null) throw new IOException("Missing audio fixture " + name);
            return in.readAllBytes();
        }
    }

    @Test void rejectsCodecCorruptionAfterOggAdmission() throws Exception {
        byte[] bytes = resource("vorbis-mono.ogg");
        byte[] setup = {5, 'v', 'o', 'r', 'b', 'i', 's'};
        int marker = -1;
        outer: for (int i = 0; i <= bytes.length - setup.length; i++) {
            for (int j = 0; j < setup.length; j++) if (bytes[i + j] != setup[j]) continue outer;
            marker = i; break;
        }
        assertTrue(marker > 0);
        bytes[marker + 7] = (byte) 255; // declare unavailable Vorbis codebooks
        for (int page = 0; page < bytes.length;) {
            int segments = bytes[page + 26] & 255, size = 27 + segments;
            for (int i = 0; i < segments; i++) size += bytes[page + 27 + i] & 255;
            for (int i = 22; i < 26; i++) bytes[page + i] = 0;
            int crc = 0;
            for (int i = 0; i < size; i++) {
                crc ^= (bytes[page + i] & 255) << 24;
                for (int bit = 0; bit < 8; bit++) crc = crc << 1 ^ (crc < 0 ? 0x04c11db7 : 0);
            }
            for (int i = 0; i < 4; i++) bytes[page + 22 + i] = (byte) (crc >>> (i * 8));
            page += size;
        }
        var input = ByteBuffer.wrap(bytes);
        var inspected = SupportedAudioProbe.inspect(input);
        assertTrue(inspected.playable(), inspected.diagnostic());
        assertThrows(IOException.class, () -> {
            try (var stream = provider.open(input, inspected.media())) {
                var output = ByteBuffer.allocate(8192);
                while (stream.read(output.clear()) != 0) { }
            }
        });
    }
}
