package cc.sirrus.ysmlib.archive.java;

import cc.sirrus.ysmlib.archive.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.zip.*;
import org.apache.commons.compress.archivers.sevenz.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

class StandardArchiveTest {
    @TempDir Path temp;
    private final JavaArchiveProvider provider = new JavaArchiveProvider();

    @ParameterizedTest @EnumSource(value = ArchiveFormat.class, names = {"ZIP", "SEVEN_ZIP"})
    void randomAccessEmptyFilesAndOwnership(ArchiveFormat format) throws Exception {
        Path source = fixture(format, new String[]{"a/一.txt", "empty", "b.bin"},
                new byte[][]{{1,2,3}, {}, {4,5}});
        try (Archive archive = provider.open(source, format, ArchiveLimits.DEFAULT)) {
            assertEquals(List.of("b.bin", "empty"), archive.files(null));
            assertEquals(List.of("a"), archive.directories(null));
            assertArrayEquals(new byte[]{4,5}, archive.read("b.bin"));
            assertArrayEquals(new byte[]{1,2,3}, archive.read("a/一.txt"));
            assertArrayEquals(new byte[0], archive.read("empty"));
            byte[] copy = archive.read("a/一.txt"); copy[0] = 9;
            assertArrayEquals(new byte[]{1,2,3}, archive.read("a/一.txt"));
            assertNull(archive.read("missing"));
        }
        Files.delete(source); // Closing releases archive handles on Windows too.
    }

    @ParameterizedTest @EnumSource(value = ArchiveFormat.class, names = {"ZIP", "SEVEN_ZIP"})
    void rejectsUnsafePathsAndLimitsAndReleasesFailedOpens(ArchiveFormat format) throws Exception {
        Path unsafe = fixture(format, new String[]{"../outside"}, new byte[][]{{1}});
        assertThrows(IOException.class, () -> provider.open(unsafe, format, ArchiveLimits.DEFAULT));
        Files.delete(unsafe);
        Path source = fixture(format, new String[]{"a", "b"}, new byte[][]{new byte[64], {1}});
        assertThrows(IOException.class, () -> provider.open(source, format,
                new ArchiveLimits(1_000_000, 16, 10, 100, 65536)));
        assertThrows(IOException.class, () -> provider.open(source, format,
                new ArchiveLimits(1_000_000, 100, 1, 100, 65536)));
        assertThrows(IOException.class, () -> provider.open(source, format,
                new ArchiveLimits(24, 100, 10, 100, 65536)));
        Files.delete(source);
    }

    private Path fixture(ArchiveFormat format, String[] names, byte[][] contents) throws IOException {
        Path path = temp.resolve("fixture." + format.name());
        if (format == ArchiveFormat.ZIP) {
            try (var out = new ZipOutputStream(Files.newOutputStream(path))) {
                for (int i = 0; i < names.length; i++) {
                    out.putNextEntry(new ZipEntry(names[i])); out.write(contents[i]); out.closeEntry();
                }
            }
        } else {
            try (var out = new SevenZOutputFile(path.toFile())) {
                for (int i = 0; i < names.length; i++) {
                    var entry = new SevenZArchiveEntry(); entry.setName(names[i]);
                    out.putArchiveEntry(entry); out.write(contents[i]); out.closeArchiveEntry();
                }
            }
        }
        return path;
    }
}
