package cc.sirrus.ysmlib.v3d;

/** Metadata for archival source/wire and an optional independent decoded workspace. */
public record V3dManifest(String format, int formatVersion, int decoderProfile,
                          Source source, Wire wire, Decoded decoded) {
    public V3dManifest(String format, int formatVersion, int decoderProfile, Source source, Wire wire) {
        this(format, formatVersion, decoderProfile, source, wire, null);
    }
    public static final String FORMAT = "ysm-v3-decoded";
    public static final int VERSION = 1;
    public static final String SOURCE_PATH = "source/original.ysm";
    public static final String WIRE_PATH = "source/wire.bin";

    public record Source(String path, String sha256, long size,
                         int envelopeVersion, int innerVersion) {}

    public record Wire(String path, String compression, String sha256, long rawSize) {}

    public record Decoded(int schemaVersion, int parserProfile, String derivedFromWireSha256,
                          java.util.Map<String, FileDigest> files) {}
    public record FileDigest(long size, String sha256) {}
    public record Integrity(SourceDigest source, WireDependency wire, Decoded decoded) {}
    public record SourceDigest(String sha256) {}
    public record WireDependency(String sha256, String derivedFromSourceSha256,
                                 int decoderProfile) {}

    public Integrity integrity() {
        return new Integrity(new SourceDigest(source.sha256()),
                new WireDependency(wire.sha256(), source.sha256(), decoderProfile), decoded);
    }
}
