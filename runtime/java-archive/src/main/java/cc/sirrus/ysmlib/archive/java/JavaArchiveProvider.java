package cc.sirrus.ysmlib.archive.java;

import cc.sirrus.ysmlib.archive.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** Mandatory portable baseline. Class loading and all operations are JNI-free. */
public final class JavaArchiveProvider implements ArchiveProvider {
    @Override public String id() { return "java-archive"; }
    @Override public boolean supports(ArchiveFormat format) { return format != null; }
    @Override public Archive open(Path source, ArchiveFormat format, ArchiveLimits limits) throws IOException {
        Objects.requireNonNull(limits, "limits");
        return switch (format) {
            case LEGACY_V1, LEGACY_V2 -> new JavaLegacyArchive(source, format, limits);
            case ZIP, SEVEN_ZIP -> new JavaStandardArchive(source, format, limits);
        };
    }
}
