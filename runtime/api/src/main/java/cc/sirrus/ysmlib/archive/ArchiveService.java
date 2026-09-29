package cc.sirrus.ysmlib.archive;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** The Java baseline is mandatory. Accelerators are optional per capability. */
public final class ArchiveService {
    private final ArchiveProvider baseline;
    private final List<ArchiveProvider> accelerators;
    private final boolean javaOnly;

    public ArchiveService(ArchiveProvider baseline, List<ArchiveProvider> accelerators,
                          boolean javaOnly) {
        this.baseline = Objects.requireNonNull(baseline, "baseline");
        this.accelerators = List.copyOf(accelerators);
        this.javaOnly = javaOnly;
        for (var format : ArchiveFormat.values()) {
            if (!baseline.supports(format)) {
                throw new IllegalArgumentException("Missing Java baseline: " + format);
            }
        }
    }

    public Archive open(Path source, ArchiveFormat format, ArchiveLimits limits) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(limits, "limits");
        if (!javaOnly) {
            for (var provider : accelerators) {
                try {
                    if (provider.supports(format)) {
                        return Objects.requireNonNull(provider.open(source, format, limits));
                    }
                } catch (LinkageError unavailable) {
                    // Loading/binding failure affects this capability only.
                    // An IOException is a content/I/O failure and must propagate.
                }
            }
        }
        return baseline.open(source, format, limits);
    }
}
