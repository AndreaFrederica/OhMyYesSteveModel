package com.elfmcys.ysm.format.parser;

import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.format.vfs.ArchiveFileSystem;
import com.elfmcys.ysm.format.vfs.Directory;
import com.elfmcys.ysm.natives.buffer.BufferArgument;
import java.io.UncheckedIOException;
import java.nio.ReadOnlyBufferException;
import java.nio.file.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Runs without loading any YSM native library. */
class PortableCaptureTest {
    @TempDir Path temp;

    @Test void directoryCaptureSurvivesSourceReuseAndClose() throws Exception {
        Files.write(temp.resolve("a"), new byte[]{1,2,3});
        Files.write(temp.resolve("b"), new byte[]{4,5});
        Files.write(temp.resolve("unread"), new byte[]{6});
        try (var source = new Directory(temp); var capture = new CapturedSourceData(source)) {
            assertArrayEquals(new byte[]{1,2,3}, bytes(capture.getFile("a")));
            source.getFile("b");
            capture.freeze();
            source.close();
            var frozen = capture.getFile("a");
            assertArrayEquals(new byte[]{1,2,3}, bytes(frozen));
            assertThrows(ReadOnlyBufferException.class, () -> frozen.nio().put(0, (byte) 7));
            try (var acquired = frozen.acquire()) {
                acquired.nio().put(0, (byte) 7);
                assertArrayEquals(new byte[]{1,2,3}, bytes(capture.getFile("a")));
            }
            var packed = BufferArgument.packInput(frozen.slice(1, 2));
            assertArrayEquals(new byte[]{2,3}, (byte[]) packed.obj());
            assertEquals(2, packed.flags());
            frozen.close();
            assertArrayEquals(new byte[]{1,2,3}, bytes(capture.getFile("a")));
            assertThrows(IllegalStateException.class, () -> capture.getFile("unread"));
        }
    }

    @Test void archiveAdapterUsesPrerequisiteAndCaptureOwnsBytes() throws Exception {
        Path zip = temp.resolve("model.zip");
        try (var out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("sub/a")); out.write(new byte[]{1,2,3}); out.closeEntry();
            out.putNextEntry(new ZipEntry("b")); out.write(new byte[]{4}); out.closeEntry();
        }
        try (var source = ArchiveFileSystem.open(zip); var capture = new CapturedSourceData(source)) {
            assertArrayEquals(new byte[]{1,2,3}, bytes(capture.getFile("sub/a")));
            assertArrayEquals(new byte[]{4}, bytes(source.getFile("b")));
            capture.freeze(); source.close();
            assertArrayEquals(new byte[]{1,2,3}, bytes(capture.getFile("sub/a")));
        }
        Files.delete(zip);
    }

    @Test void compiledV3NeverRoutesToRawArchive() throws Exception {
        Path v3 = Files.write(temp.resolve("v3.ysm"),
                new byte[]{(byte) 0xef, (byte) 0xbb, (byte) 0xbf, 'Y', 'S', 'G', 'P', 0});
        assertThrows(UncheckedIOException.class, () -> ArchiveFileSystem.open(v3));
    }

    private static byte[] bytes(UniBuffer buffer) {
        byte[] result = new byte[buffer.size()]; buffer.nio().get(result); return result;
    }
}
