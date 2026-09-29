package cc.sirrus.ysmlib.archive;

/** Explicit budgets shared by providers; no codec may silently exceed them. */
public record ArchiveLimits(int sourceBytes, int entryBytes, int entries, int nameBytes,
                            int decoderMemoryKiB) {
    public static final ArchiveLimits DEFAULT =
            new ArchiveLimits(256 * 1024 * 1024, 256 * 1024 * 1024, 65_536, 65_535, 262_144);

    public ArchiveLimits {
        if (sourceBytes < 24 || entryBytes < 0 || entries < 1 || nameBytes < 1
                || decoderMemoryKiB < 1) {
            throw new IllegalArgumentException("Invalid archive limits");
        }
    }
}
