package cc.sirrus.ysmlib.archive;

import java.io.IOException;
import java.nio.file.Path;

/** A capability provider. Native implementations may be supplied separately. */
public interface ArchiveProvider {
    String id();
    boolean supports(ArchiveFormat format);
    Archive open(Path source, ArchiveFormat format, ArchiveLimits limits) throws IOException;
}
