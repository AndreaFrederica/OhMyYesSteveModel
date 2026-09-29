package cc.sirrus.ysmlib.archive.java;

import cc.sirrus.ysmlib.archive.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.DeflaterOutputStream;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class LegacyArchiveTest {
    @TempDir Path temp;
    private static ArchiveFormat format(int version) {
        return version == 1 ? ArchiveFormat.LEGACY_V1 : ArchiveFormat.LEGACY_V2;
    }

    @ParameterizedTest @ValueSource(ints = {1, 2})
    void extractsCanonicalHistoricalWriterFilesWithoutNative(int version) throws Exception {
        byte[] pixels = new byte[32_000];
        new Random(817).nextBytes(pixels);
        var files = new LinkedHashMap<String, byte[]>();
        files.put("main.json", "{\"format_version\":\"1.12.0\"}".getBytes(StandardCharsets.UTF_8));
        files.put("textures/皮肤.png", pixels);
        files.put("/lang/zh_cn.json", "你好".getBytes(StandardCharsets.UTF_8));
        files.put("empty", new byte[0]);
        Path path = write(version, files);
        try (var archive = new JavaLegacyArchive(path, format(version), ArchiveLimits.DEFAULT)) {
            assertEquals(List.of("empty", "main.json"), archive.files(null));
            assertEquals(List.of("lang", "textures"), archive.directories(""));
            assertEquals(List.of("皮肤.png"), archive.files("textures/"));
            for (var entry : files.entrySet()) assertArrayEquals(entry.getValue(), archive.read(entry.getKey()));
            byte[] owned = archive.read("main.json");
            owned[0] = 0;
            assertArrayEquals(files.get("main.json"), archive.read("main.json"));
            assertNull(archive.read("missing"));
            assertFalse(archive.contains("../main.json"));
        }
        var closed = new JavaLegacyArchive(path, format(version), ArchiveLimits.DEFAULT);
        closed.close();
        closed.close();
        assertThrows(IllegalStateException.class, () -> closed.read("main.json"));
    }

    @ParameterizedTest @ValueSource(ints = {1, 2})
    void rejectsChecksumTruncationAndOversizedFields(int version) throws Exception {
        var valid = Files.readAllBytes(write(version, Map.of("a", new byte[]{1,2,3})));
        var corrupt = valid.clone(); corrupt[corrupt.length - 1] ^= 1;
        assertInvalid(corrupt, version);
        for (int length = 0; length < valid.length; length++) {
            var shortFile = Arrays.copyOf(valid, length);
            if (length >= 24) checksum(shortFile);
            assertInvalid(shortFile, version);
        }
        var huge = valid.clone(); ByteBuffer.wrap(huge).putInt(24, -1); checksum(huge);
        assertInvalid(huge, version);
        var wrongVersion = valid.clone(); ByteBuffer.wrap(wrongVersion).putInt(4, 3);
        assertInvalid(wrongVersion, version);
    }

    @ParameterizedTest @ValueSource(ints = {1, 2})
    void rejectsAmbiguousNamesAndExpansionBeyondBudget(int version) throws Exception {
        for (String name : List.of("../a", "a/../b", "a\\b", "C:/a", "a//b", "a\0b", "")) {
            assertThrows(IOException.class, () -> new JavaLegacyArchive(
                    write(version, Map.of(name, new byte[0])), format(version), ArchiveLimits.DEFAULT));
        }
        var collision = new LinkedHashMap<String, byte[]>();
        collision.put("a", new byte[0]); collision.put("/a", new byte[0]);
        assertThrows(IOException.class, () -> new JavaLegacyArchive(
                write(version, collision), format(version), ArchiveLimits.DEFAULT));
        var parent = new LinkedHashMap<String, byte[]>();
        parent.put("a/b", new byte[0]); parent.put("a", new byte[0]);
        assertThrows(IOException.class, () -> new JavaLegacyArchive(
                write(version, parent), format(version), ArchiveLimits.DEFAULT));
        var limits = new ArchiveLimits(1_000_000, 16, 10, 100, 1024);
        try (var archive = new JavaLegacyArchive(write(version, Map.of("bomb", new byte[1000])), format(version), limits)) {
            assertThrows(IOException.class, () -> archive.read("bomb"));
        }
    }

    @ParameterizedTest @ValueSource(ints = {1, 2})
    void detectsAesCorruptionEvenWithRecomputedOuterChecksum(int version) throws Exception {
        byte[] bytes = Files.readAllBytes(write(version, Map.of("a", new byte[]{42})));
        bytes[bytes.length - 1] ^= 0x7f;
        checksum(bytes);
        Path path = Files.write(temp.resolve("broken.ysm"), bytes);
        try (var archive = new JavaLegacyArchive(path, format(version), ArchiveLimits.DEFAULT)) {
            assertThrows(IOException.class, () -> archive.read("a"));
        }
    }

    @Test void nativeProviderLinkageFailureFallsBackButContentFailureDoesNot() throws Exception {
        var path = write(2, Map.of("a", new byte[]{7}));
        var baseline = new JavaArchiveProvider();
        var unavailable = provider(new UnsatisfiedLinkError("not installed"));
        try (var archive = new ArchiveService(baseline, List.of(unavailable), false)
                .open(path, format(2), ArchiveLimits.DEFAULT)) {
            assertArrayEquals(new byte[]{7}, archive.read("a"));
        }
        var invalid = provider(new IOException("bad content"));
        assertThrows(IOException.class, () -> new ArchiveService(baseline, List.of(invalid), false)
                .open(path, format(2), ArchiveLimits.DEFAULT));
        try (var archive = new ArchiveService(baseline, List.of(invalid), true)
                .open(path, format(2), ArchiveLimits.DEFAULT)) {
            assertArrayEquals(new byte[]{7}, archive.read("a"));
        }
    }

    private static ArchiveProvider provider(Throwable failure) {
        return new ArchiveProvider() {
            public String id() { return "test-accelerator"; }
            public boolean supports(ArchiveFormat format) { return true; }
            public Archive open(Path source, ArchiveFormat format, ArchiveLimits limits) throws IOException {
                if (failure instanceof LinkageError error) throw error;
                throw (IOException) failure;
            }
        };
    }

    private void assertInvalid(byte[] bytes, int version) throws IOException {
        Path path = Files.write(temp.resolve("invalid.ysm"), bytes);
        assertThrows(IOException.class, () -> new JavaLegacyArchive(path, format(version), ArchiveLimits.DEFAULT));
    }

    private Path write(int version, Map<String, byte[]> files) throws Exception {
        var entries = new ByteArrayOutputStream();
        var out = new DataOutputStream(entries);
        for (var entry : files.entrySet()) {
            byte[] name = entry.getKey().getBytes(StandardCharsets.UTF_8);
            if (version == 2) name = Base64.getEncoder().encode(name);
            out.writeInt(name.length); out.write(name);
            byte[] key = new byte[16], iv = new byte[16];
            new Random(718).nextBytes(key); new Random(916).nextBytes(iv);
            var compressed = new ByteArrayOutputStream();
            try (var zlib = new DeflaterOutputStream(compressed)) { zlib.write(entry.getValue()); }
            byte[] encrypted = encrypt(compressed.toByteArray(), key, iv);
            out.writeInt(encrypted.length);
            if (version == 1) out.write(key);
            else {
                // Historical writer used overflowing long accumulation, independently
                // spelling out the seed rather than sharing any decoder helper.
                long seed = 0;
                for (byte b : MessageDigest.getInstance("MD5").digest(encrypted)) seed = (seed << 8) + (b & 255);
                byte[] derived = new byte[16]; new Random(seed).nextBytes(derived);
                byte[] wrapped = encrypt(key, derived, iv);
                out.writeInt(wrapped.length); out.write(wrapped);
            }
            out.write(iv); out.write(encrypted);
        }
        var result = new ByteArrayOutputStream();
        var header = new DataOutputStream(result);
        header.writeInt(0x59534750); header.writeInt(version);
        header.write(MessageDigest.getInstance("MD5").digest(entries.toByteArray()));
        entries.writeTo(result);
        return Files.write(temp.resolve("fixture-" + version + ".ysm"), result.toByteArray());
    }

    private static byte[] encrypt(byte[] bytes, byte[] key, byte[] iv) throws Exception {
        var cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return cipher.doFinal(bytes);
    }
    private static void checksum(byte[] file) throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5").digest(Arrays.copyOfRange(file, 24, file.length));
        System.arraycopy(digest, 0, file, 8, 16);
    }
}
