package cc.sirrus.ysmlib;

import cc.sirrus.ysmlib.codec.HashProvider;
import cc.sirrus.ysmlib.codec.CompressionProvider;
import cc.sirrus.ysmlib.codec.java.JavaHashProvider;
import cc.sirrus.ysmlib.codec.java.JavaCompressionProvider;
import cc.sirrus.ysmlib.codec.natives.NativeCodecProvider;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.function.Supplier;

/** Stateless calls can retry on linkage failure. Content errors never select another decoder. */
final class CodecServices {
    private static final System.Logger LOG = System.getLogger(CodecServices.class.getName());
    final HashProvider hashes;
    final CompressionProvider compression;

    static CodecServices configured() {
        return create(Boolean.getBoolean("ysm.runtime.javaOnly"), () -> {
            var library = NativeLibraries.find("codec");
            return library == null ? null : new NativeCodecProvider(library);
        });
    }

    static CodecServices create(boolean javaOnly, Supplier<NativeCodecProvider> loader) {
        NativeCodecProvider accelerator = null;
        if (!javaOnly) {
            try { accelerator = loader.get(); }
            catch (LinkageError | SecurityException | java.nio.file.InvalidPathException unavailable) {
                LOG.log(System.Logger.Level.WARNING, "Optional codec accelerator unavailable; using Java", unavailable);
            }
        }
        return new CodecServices(new JavaHashProvider(), new JavaCompressionProvider(), accelerator, accelerator);
    }

    CodecServices(HashProvider baselineHash, CompressionProvider baselineCompression,
                  HashProvider acceleratedHash, CompressionProvider acceleratedCompression) {
        Objects.requireNonNull(baselineHash);
        Objects.requireNonNull(baselineCompression);
        hashes = new HashProvider() {
            private volatile HashProvider accelerated = acceleratedHash;
            public String id() { var selected = accelerated; return (selected == null ? baselineHash : selected).id(); }
            public byte[] blake3(ByteBuffer input) {
                var selected = accelerated;
                if (selected != null) {
                    try { return selected.blake3(input.duplicate()); }
                    catch (LinkageError unavailable) { accelerated = null; }
                }
                return baselineHash.blake3(input.duplicate());
            }
        };
        compression = new CompressionProvider() {
            private volatile CompressionProvider accelerated = acceleratedCompression;
            public String id() { var selected = accelerated; return (selected == null ? baselineCompression : selected).id(); }
            public byte[] compress(ByteBuffer input, int level, int maxOutputBytes) throws IOException {
                var selected = accelerated;
                if (selected != null) {
                    try { return selected.compress(input.duplicate(), level, maxOutputBytes); }
                    catch (LinkageError unavailable) { accelerated = null; }
                }
                return baselineCompression.compress(input.duplicate(), level, maxOutputBytes);
            }
            public byte[] decompress(ByteBuffer input, int outputBytes, int maxOutputBytes) throws IOException {
                var selected = accelerated;
                if (selected != null) {
                    try { return selected.decompress(input.duplicate(), outputBytes, maxOutputBytes); }
                    catch (LinkageError unavailable) { accelerated = null; }
                }
                return baselineCompression.decompress(input.duplicate(), outputBytes, maxOutputBytes);
            }
        };
    }
}
