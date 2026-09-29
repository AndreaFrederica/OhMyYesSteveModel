package com.elfmcys.ysm.format.vfs;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.format.legacy.LegacyYsmHeader;
import cc.sirrus.ysmlib.archive.*;
import cc.sirrus.ysmlib.YsmRuntime;
import com.elfmcys.ysm.util.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Locale;

/** Mod adapter only; archive codecs live in the independent portable runtime. */
public final class ArchiveFileSystem implements VirtualFileSystem, Closeable {
    private final Archive archive;
    private ArrayBuffer current;
    private boolean closed;

    private ArchiveFileSystem(Archive archive) { this.archive = archive; }

    public static ArchiveFileSystem open(Path path) {
        return open(path, YsmRuntime.archives());
    }

    public static ArchiveFileSystem open(Path path, ArchiveService service) {
        try {
            String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
            ArchiveFormat format;
            if (name.endsWith(".zip")) format = ArchiveFormat.ZIP;
            else if (name.endsWith(".7z")) format = ArchiveFormat.SEVEN_ZIP;
            else if (name.endsWith(".ysm")) {
                format = switch (LegacyYsmHeader.probe(path)) {
                    case V1_RAW -> ArchiveFormat.LEGACY_V1;
                    case V2_RAW -> ArchiveFormat.LEGACY_V2;
                    default -> throw new IOException("V3/unsupported YSM is not a raw archive");
                };
            } else throw new IOException("Unsupported archive: " + name);
            return new ArchiveFileSystem(service.open(path, format, ArchiveLimits.DEFAULT));
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }

    @Override public String[] listFiles(String path) { checkOpen(); return archive.files(path).toArray(String[]::new); }
    @Override public String[] listDirectories(String path) { checkOpen(); return archive.directories(path).toArray(String[]::new); }
    @Override public boolean hasFile(String path) { checkOpen(); return archive.contains(path); }

    @Override public UniBuffer getFile(String path) {
        checkOpen();
        try {
            byte[] bytes = archive.read(path);
            if (current != null) { current.close(); current = null; }
            if (bytes == null) return null;
            current = ArrayBuffer.move(bytes);
            return current.borrow();
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        try { if (current != null) current.close(); }
        finally {
            current = null;
            try { archive.close(); }
            catch (IOException failure) { throw new UncheckedIOException(failure); }
        }
    }
    private void checkOpen() { if (closed) throw new IllegalStateException("Archive is closed"); }
}
