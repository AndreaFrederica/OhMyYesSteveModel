package cc.sirrus.ysmlib.v3d;

import cc.sirrus.ysmlib.legacy.V3EnvelopeProvider;
import cc.sirrus.ysmlib.legacy.java.JavaDecodedWorkspaceProvider;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class V3dCacheTest {
    @Test void captureKeepsFingerprintAndDecodedInputTogetherAcrossSourceEdits() throws Exception {
        var path = source();
        var captured = V3dCache.capture(path);
        var original = Files.readAllBytes(path);
        Files.write(path, new byte[]{9, 8, 7});
        var cache = new V3dCache(decoder(1));
        var root = temp.resolve("captured");
        assertArrayEquals(wire, cache.readWire(captured, root));
        assertArrayEquals(wire, new V3dCache(decoder(1)).readWire(captured, root));
        assertEquals(1, calls.get());
        var generation = cache.materialize(captured, root);
        assertEquals(captured.sha256(), V3dCache.validate(generation).source().sha256());
        assertArrayEquals(original, Files.readAllBytes(generation.resolve("source/original.ysm")));
        assertThrows(java.nio.ReadOnlyBufferException.class, () -> captured.bytes().put(0, (byte) 4));
    }

    @Test void completeWorkspaceKeepsRecoveryIndependentOfEdits() throws Exception {
        byte[] historical;
        try (var stream = getClass().getResourceAsStream("/historical/v32.wire")) {
            historical = java.util.Objects.requireNonNull(stream).readAllBytes();
        }
        var decoder = new V3EnvelopeProvider() {
            public int profile() { return 1; }
            public byte[] decode(ByteBuffer source, int limit) {
                return historical.clone();
            }
        };
        var input = source();
        var root = new V3dCache(decoder, new JavaDecodedWorkspaceProvider())
                .materialize(input, temp.resolve("complete"));
        assertNotNull(V3dCache.validate(root).decoded());
        Files.writeString(root.resolve("legacy/model.json"), "edited");
        assertThrows(IOException.class, () -> V3dCache.validate(root));
        var restored = temp.resolve("original.ysm");
        V3dCache.restoreOriginal(root, restored);
        assertArrayEquals(Files.readAllBytes(input), Files.readAllBytes(restored));
    }
    @TempDir Path temp;
    private final AtomicInteger calls = new AtomicInteger();
    private final byte[] wire = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(32).put(new byte[]{0, 1, -1, 42, 0}).array();

    private V3EnvelopeProvider decoder(int profile) {
        return new V3EnvelopeProvider() {
            public int profile() { return profile; }
            public byte[] decode(ByteBuffer source, int limit) {
                calls.incrementAndGet();
                return wire.clone();
            }
        };
    }

    private Path source() throws IOException {
        return Files.write(temp.resolve("model.ysm"), new byte[]{3, 4, 5, 0, -1});
    }

    @Test void preservesExactBytesAndReusesWithoutDecodingOrRewriting() throws Exception {
        var source = source();
        var cache = new V3dCache(decoder(1));
        var output = cache.materialize(source, temp.resolve("cache"));
        var timestamp = Files.getLastModifiedTime(output.resolve("v3d.json"));
        assertArrayEquals(Files.readAllBytes(source),
                Files.readAllBytes(output.resolve("source/original.ysm")));
        assertArrayEquals(wire, Files.readAllBytes(output.resolve("source/wire.bin")));
        assertEquals(output, cache.materialize(source, temp.resolve("cache")));
        assertEquals(1, calls.get());
        assertEquals(timestamp, Files.getLastModifiedTime(output.resolve("v3d.json")));
        var manifest = V3dCache.validate(output);
        assertEquals(3, manifest.source().envelopeVersion());
        assertEquals(32, manifest.source().innerVersion());
        assertTrue(Files.readString(output.resolve("v3d.json")).contains("decoder_profile"));
        var restored = temp.resolve("restored.ysm");
        V3dCache.restoreOriginal(output, restored);
        assertArrayEquals(Files.readAllBytes(source), Files.readAllBytes(restored));
        assertThrows(IOException.class, () -> V3dCache.restoreOriginal(output, restored));
    }

    @Test void changedSourceAndProfileProduceNewGenerations() throws Exception {
        var source = source();
        var root = temp.resolve("cache");
        var first = new V3dCache(decoder(1)).materialize(source, root);
        Files.write(source, new byte[]{5, 4, 3});
        var second = new V3dCache(decoder(1)).materialize(source, root);
        var third = new V3dCache(decoder(2)).materialize(source, root);
        assertNotEquals(first, second);
        assertNotEquals(second, third);
        assertEquals(3, calls.get());
        V3dCache.validate(first);
        V3dCache.validate(second);
        V3dCache.validate(third);
    }

    @Test void corruptedFilesAndDependenciesAreNeverCacheHits() throws Exception {
        var source = source();
        var root = temp.resolve("cache");
        var cache = new V3dCache(decoder(1));
        var output = cache.materialize(source, root);
        for (var name : new String[]{"source/original.ysm", "source/wire.bin",
                "integrity.json", "v3d.json"}) {
            var file = output.resolve(name);
            var bytes = Files.readAllBytes(file);
            bytes[bytes.length - 1] ^= 1;
            Files.write(file, bytes);
            assertThrows(IOException.class, () -> V3dCache.validate(output));
            assertThrows(IOException.class,
                    () -> V3dCache.restoreOriginal(output, temp.resolve("invalid.ysm")));
            assertFalse(Files.exists(temp.resolve("invalid.ysm")));
            assertEquals(output, cache.materialize(source, root));
            V3dCache.validate(output);
        }
        assertEquals(5, calls.get());
    }

    @Test void failedDecodeAndPartialDirectoryDoNotPublish() throws Exception {
        var root = temp.resolve("cache");
        var partial = Files.createDirectories(root.resolve(".v3d-pending-interrupted"));
        Files.writeString(partial.resolve("v3d.json"), "{}");
        assertThrows(IOException.class, () -> V3dCache.validate(partial));
        var source = source();
        var failing = new V3dCache(new V3EnvelopeProvider() {
            public int profile() { return 1; }
            public byte[] decode(ByteBuffer bytes, int limit) throws IOException {
                throw new IOException("injected decoder failure");
            }
        });
        assertThrows(IOException.class, () -> failing.materialize(source, root));
        try (var entries = Files.list(root)) {
            assertEquals(1, entries.count());
        }
        var output = new V3dCache(decoder(1)).materialize(source, root);
        V3dCache.validate(output);
        assertTrue(Files.exists(partial));
        try (var entries = Files.list(root)) {
            assertEquals(2, entries.count());
        }
    }

    @Test void rejectsInvalidWireMetadataAndOversizedSourceBeforeDecode() throws Exception {
        var source = temp.resolve("huge.ysm");
        try (var file = new java.io.RandomAccessFile(source.toFile(), "rw")) {
            file.setLength(V3EnvelopeProvider.SOURCE_LIMIT + 1L);
        }
        assertThrows(IOException.class,
                () -> new V3dCache(decoder(1)).materialize(source, temp.resolve("cache")));
        assertEquals(0, calls.get());
    }

    @Test void rejectsProviderReturningInvalidWireBeforePublishing() throws Exception {
        var input = source();
        for (byte[] bad : new byte[][]{new byte[0], new byte[3], new byte[4],
                ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(33).array()}) {
            var cache = new V3dCache(new V3EnvelopeProvider() {
                public int profile() { return 1; }
                public byte[] decode(ByteBuffer bytes, int limit) { return bad; }
            });
            assertThrows(IOException.class, () -> cache.materialize(input, temp.resolve("invalid")));
            try (var entries = Files.list(temp.resolve("invalid"))) { assertEquals(0, entries.count()); }
        }
    }

    @Test void failedPublicationRestoresPreviousDirectory() throws Exception {
        var target = Files.createDirectory(temp.resolve("target.v3d"));
        Files.writeString(target.resolve("sentinel"), "previous contents");
        assertThrows(IOException.class,
                () -> V3dCache.publish(temp.resolve("missing-staging"), target));
        assertEquals("previous contents", Files.readString(target.resolve("sentinel")));
        try (var entries = Files.list(temp)) {
            assertEquals(1, entries.count());
        }
    }

    @Test void rejectsValidJsonWithChangedDependencyAndUnsupportedProfile() throws Exception {
        var output = new V3dCache(decoder(1)).materialize(source(), temp.resolve("cache"));
        var integrity = output.resolve("integrity.json");
        Files.writeString(integrity, Files.readString(integrity)
                .replace("\"decoder_profile\": 1", "\"decoder_profile\": 2"));
        assertThrows(IOException.class, () -> V3dCache.validate(output));
        var manifest = output.resolve("v3d.json");
        Files.writeString(manifest, Files.readString(manifest)
                .replace("\"format_version\": 1", "\"format_version\": 999"));
        assertThrows(IOException.class, () -> V3dCache.validate(output));
    }
}
