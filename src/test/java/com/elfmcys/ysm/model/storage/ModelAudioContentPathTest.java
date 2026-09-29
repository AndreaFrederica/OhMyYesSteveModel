package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.format.parser.DefaultAnimationFilter;
import com.elfmcys.ysm.model.catalog.RawModelImporter;
import com.elfmcys.ysm.model.catalog.builtin.BuiltinModelMaterializer;
import com.elfmcys.ysm.model.catalog.source.CatalogModelLocation;
import com.elfmcys.ysm.model.catalog.source.CatalogRootKind;
import com.elfmcys.ysm.model.domain.ModelPath;
import com.elfmcys.ysm.testutil.NativeLibraryExtension;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(NativeLibraryExtension.class)
class ModelAudioContentPathTest {
    @TempDir
    Path temp;

    @Test
    void rawDirectoryArchiveAndCurrentExportPreserveConsumableAudio() throws Exception {
        var source = copyRawFixture(temp.resolve("raw"));
        var archive = zip(source, temp.resolve("raw.zip"));
        var importer = new RawModelImporter(DefaultAnimationFilter.keepAll());

        Path directoryContainer;
        try (var captured = importer.capture(source)) {
            directoryContainer = importer.convert(captured,
                    Files.createDirectories(temp.resolve("directory-output"))).stagedContainer();
        }
        Path archiveContainer;
        try (var captured = importer.capture(archive)) {
            archiveContainer = importer.convert(captured,
                    Files.createDirectories(temp.resolve("archive-output"))).stagedContainer();
        }

        var directory = open(directoryContainer);
        var archived = open(archiveContainer);
        try {
            assertEquals(directory.representation().modelId(), archived.representation().modelId());
            assertAudio(directory);
            assertAudio(archived);

            var exportedPath = temp.resolve("exported.ysm");
            ModelExporter.export(directory, TestPreviews.blank(), exportedPath, "audio-u2");
            var exported = open(exportedPath);
            try {
                assertEquals(directory.representation().modelId(), exported.representation().modelId());
                assertNotEquals(directory.representation().containerId(),
                        exported.representation().containerId());
                assertAudio(exported);
            } finally {
                exported.representation().close();
            }
        } finally {
            archived.representation().close();
            directory.representation().close();
        }
    }

    @Test
    void completeM2MaterializationRejectsCodecCorruptionAfterAdmission() throws Exception {
        var source = copyRawFixture(temp.resolve("corrupt-raw"));
        var corrupt = Files.readAllBytes(fixtureRoot().resolve("vorbis-under.ogg"));
        int setup = find(corrupt, new byte[]{5, 'v', 'o', 'r', 'b', 'i', 's'});
        assertTrue(setup >= 0);
        corrupt[setup + 20] ^= 0x40;
        rewritePageChecksum(corrupt, pageContaining(corrupt, setup));
        Files.write(source.resolve("sounds/tone_corrupt.ogg"), corrupt);

        var importer = new RawModelImporter(DefaultAnimationFilter.keepAll());
        Path container;
        try (var captured = importer.capture(source)) {
            container = importer.convert(captured,
                    Files.createDirectories(temp.resolve("corrupt-output"))).stagedContainer();
        }
        var content = open(container);
        try {
            assertThrows(IOException.class,
                    () -> BuiltinModelMaterializer.materialize(content));
        } finally {
            content.representation().close();
        }
    }

    private static void assertAudio(ManagedContainer content) throws Exception {
        var sounds = content.modelFile().getCommon().sounds();
        assertEquals(Set.of("tone_opus", "tone_vorbis"), sounds.keySet());
        assertEquals(48_000, sounds.get("tone_opus").sampleRate());
        assertEquals(60_001, sounds.get("tone_opus").frames());
        assertEquals(44_100, sounds.get("tone_vorbis").sampleRate());
        assertEquals(176_399, sounds.get("tone_vorbis").frames());
        content.modelFile().getCommon().validateSoundContent(() -> false, content.chunks());

        assertStoredBytes(content, "tone_opus", "opus-input-rate.ogg");
        assertStoredBytes(content, "tone_vorbis", "vorbis-under.ogg");
    }

    private static void assertStoredBytes(ManagedContainer content, String soundName,
                                          String fixtureName) throws Exception {
        var sound = content.modelFile().getCommon().sounds().get(soundName);
        var chunk = content.modelFile().getFileView().streamInfo(sound.streamId());
        assertTrue(chunk.encoding().isEmpty());
        assertEquals(0, chunk.decodeSize());
        assertEquals(0, chunk.flags());
        try (var payload = content.chunks().readPayload(chunk, BufferType.ARRAY);
             var array = payload.acquireArray()) {
            var actual = Arrays.copyOfRange(array.array(), array.arrayOffset(),
                    array.arrayOffset() + array.size());
            assertArrayEquals(Files.readAllBytes(fixtureRoot().resolve(fixtureName)), actual);
        }
    }

    private static ManagedContainer open(Path file) throws Exception {
        return ManagedContainer.openDirect(file, new CatalogModelLocation(
                CatalogRootKind.CUSTOM, new ModelPath("audio-u2")));
    }

    private static Path copyRawFixture(Path destination) throws Exception {
        var manifest = ModelAudioContentPathTest.class.getResource(
                "/assets/ysm/builtin/misc/1_alex/ysm.json");
        var source = Path.of(Objects.requireNonNull(manifest).toURI()).getParent();
        try (var paths = Files.walk(source)) {
            for (var path : paths.toList()) {
                var target = destination.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        var sounds = Files.createDirectories(destination.resolve("sounds"));
        Files.copy(fixtureRoot().resolve("opus-input-rate.ogg"),
                sounds.resolve("tone_opus.ogg"));
        Files.copy(fixtureRoot().resolve("vorbis-under.ogg"),
                sounds.resolve("tone_vorbis.ogg"));
        return destination;
    }

    private static Path zip(Path source, Path archive) throws IOException {
        try (var output = new ZipOutputStream(Files.newOutputStream(archive));
             var paths = Files.walk(source)) {
            for (var path : paths.filter(Files::isRegularFile).sorted().toList()) {
                var name = source.relativize(path).toString().replace('\\', '/');
                output.putNextEntry(new ZipEntry(name));
                Files.copy(path, output);
                output.closeEntry();
            }
        }
        assertFalse(Files.size(archive) == 0);
        return archive;
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

    private static Path fixtureRoot() {
        var configured = System.getenv("YSM_AUDIO_FIXTURE_DIR");
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(
                    "YSM_AUDIO_FIXTURE_DIR must identify the contract fixture directory");
        }
        return Path.of(configured).toAbsolutePath().normalize();
    }
}
