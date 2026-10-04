package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelFileIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConvertedSourceIndexStoreTest {
    @TempDir
    Path temp;

    @Test
    void exactMappingRoundTripsAsOneAtomicIndex() throws Exception {
        var store = store("1.2.3");
        var custom = entry(1, 11, "custom/group/model", "1.2.3");
        var auth = entry(2, 12, "auth/model", "1.2.3");

        store.replace(List.of(auth, custom));

        assertEquals(custom, store.find("custom/group/model").orElseThrow());
        assertEquals(auth, store.find("auth/model").orElseThrow());
        assertEquals(Set.of("custom/group/model", "auth/model"), store.read().keySet());
        assertEquals(temp.resolve("cache/converted/index.bin").toAbsolutePath().normalize(),
                store.path());
    }

    @Test
    void unifiedIdentityKeepsTheExistingIndexBytes() throws Exception {
        var store = store("1.2.3");
        var entry = entry(1, 11, "custom/model", "1.2.3");
        store.replace(List.of(entry));
        var path = entry.rawRelativePath().getBytes(StandardCharsets.UTF_8);
        var version = entry.fullModVersion().getBytes(StandardCharsets.UTF_8);
        var expected = ByteBuffer.allocate(8 + Integer.BYTES + Hash256.SIZE * 2
                        + Integer.BYTES + path.length + Integer.BYTES + version.length)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put("YSMCIDX1".getBytes(StandardCharsets.US_ASCII))
                .putInt(1)
                .put(entry.modelId().bytes())
                .put(entry.containerId().bytes())
                .putInt(path.length).put(path)
                .putInt(version.length).put(version)
                .array();

        assertArrayEquals(expected, Files.readAllBytes(store.path()));
    }

    @Test
    void stampedEntriesRoundTripWithoutContentHashingMetadata() throws Exception {
        var store = store("1.2.3");
        var entry = new ConvertedSourceIndex(
                new ModelFileIdentity(hash(3), hash(13)), "custom/model.unitypackage",
                "1.2.3", 435_900_000L, 123_456_789L, "file-key");

        store.replace(List.of(entry));

        assertEquals(entry, store.find("custom/model.unitypackage").orElseThrow());
        assertTrue(entry.hasSourceStamp());
        assertArrayEquals("YSMCIDX2".getBytes(StandardCharsets.US_ASCII),
                Arrays.copyOfRange(Files.readAllBytes(store.path()), 0, 8));
    }

    @Test
    void scopedReplacementPreservesOtherManagementNamespaces() throws Exception {
        var store = store("1.2.3");
        var custom = entry(1, 11, "custom/old", "1.2.3");
        var auth = entry(2, 12, "auth/kept", "1.2.3");
        var replacement = entry(3, 13, "custom/new", "1.2.3");
        store.replace(List.of(custom, auth));

        store.replaceScopes(Set.of("custom"), List.of(replacement));

        assertTrue(store.find("custom/old").isEmpty());
        assertEquals(replacement, store.find("custom/new").orElseThrow());
        assertEquals(auth, store.find("auth/kept").orElseThrow());
    }

    @Test
    void rejectsDuplicatePathsAndForeignVersions() {
        var store = store("1.2.3");
        assertThrows(IllegalArgumentException.class, () -> store.replace(List.of(
                entry(1, 11, "custom/model", "1.2.3"),
                entry(2, 12, "custom/model", "1.2.3"))));
        assertThrows(IllegalArgumentException.class, () -> store.replace(List.of(
                entry(1, 11, "custom/model", "other"))));
    }

    @Test
    void malformedExactIndexIsDeletedAndOtherVersionsAreMisses() throws Exception {
        var first = store("1.2.3");
        first.replace(List.of(entry(1, 11, "custom/model", "1.2.3")));
        assertTrue(store("2.0.0").read().isEmpty());
        assertTrue(Files.exists(first.path()));

        Files.write(first.path(), new byte[]{1, 2, 3});

        assertTrue(first.read().isEmpty());
        assertFalse(Files.exists(first.path()));
    }

    private ConvertedSourceIndexStore store(String version) {
        return new ConvertedSourceIndexStore(temp.resolve("cache"), version);
    }

    private static ConvertedSourceIndex entry(
            int modelSeed, int containerSeed, String path, String version) {
        return new ConvertedSourceIndex(
                new ModelFileIdentity(hash(modelSeed), hash(containerSeed)), path, version);
    }

    private static Hash256 hash(int seed) {
        var bytes = new byte[Hash256.SIZE];
        bytes[0] = (byte) seed;
        return new Hash256(bytes);
    }
}
