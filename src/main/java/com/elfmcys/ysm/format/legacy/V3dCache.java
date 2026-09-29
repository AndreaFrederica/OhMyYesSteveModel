package com.elfmcys.ysm.format.legacy;

import cc.sirrus.ysmlib.YsmRuntime;
import cc.sirrus.ysmlib.legacy.DecodedWorkspaceProvider;
import cc.sirrus.ysmlib.legacy.V3EnvelopeProvider;
import cc.sirrus.ysmlib.v3d.V3dManifest;
import com.elfmcys.ysm.buffer.ArrayBuffer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;

/** Compatibility adapter; all workspace IO and validation belong to ysmlib. */
public final class V3dCache {
    private final cc.sirrus.ysmlib.v3d.V3dCache delegate;

    public V3dCache() { delegate = YsmRuntime.v3d(); }

    public V3dCache(LegacyV3Decoder decoder) { this(decoder, null); }

    public V3dCache(LegacyV3Decoder decoder, DecodedWorkspaceProvider workspace) {
        delegate = new cc.sirrus.ysmlib.v3d.V3dCache(new V3EnvelopeProvider() {
            @Override public int profile() { return decoder.profile(); }
            @Override public byte[] decode(ByteBuffer source, int limit) throws IOException {
                byte[] envelope = new byte[source.remaining()];
                source.duplicate().get(envelope);
                try (var input = ArrayBuffer.move(envelope); var wire = decoder.decodeWire(input)) {
                    var bytes = wire.plaintext().nio();
                    if (bytes.remaining() > limit) throw new IOException("V3 wire exceeds limit");
                    byte[] result = new byte[bytes.remaining()];
                    bytes.get(result);
                    return result;
                }
            }
        }, workspace);
    }

    public Path materialize(Path source, Path root) throws IOException {
        return delegate.materialize(source, root);
    }

    public static V3dManifest validate(Path directory) throws IOException {
        return cc.sirrus.ysmlib.v3d.V3dCache.validate(directory);
    }

    public static void restoreOriginal(Path directory, Path output) throws IOException {
        cc.sirrus.ysmlib.v3d.V3dCache.restoreOriginal(directory, output);
    }
}
