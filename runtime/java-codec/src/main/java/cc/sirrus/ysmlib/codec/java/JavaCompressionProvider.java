package cc.sirrus.ysmlib.codec.java;

import cc.sirrus.ysmlib.codec.CompressionProvider;
import io.airlift.compress.zstd.ZstdCompressor;
import io.airlift.compress.zstd.ZstdDecompressor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Pure Java Zstandard. Compression level is a quality hint, not a wire identity. */
public final class JavaCompressionProvider implements CompressionProvider {
    @Override public String id() { return "java-zstd"; }

    @Override public byte[] compress(ByteBuffer input, int level, int maxOutputBytes) throws IOException {
        if (maxOutputBytes < 0) throw new IllegalArgumentException("Negative output budget");
        byte[] source = copy(input);
        var codec = new ZstdCompressor();
        int bound = codec.maxCompressedLength(source.length);
        if (bound < 0 || bound > maxOutputBytes) throw new IOException("Zstd output budget exceeded");
        byte[] result = new byte[bound];
        try {
            int size = codec.compress(source, 0, source.length, result, 0, result.length);
            return Arrays.copyOf(result, size);
        } catch (RuntimeException invalid) { throw new IOException("Zstd compression failed", invalid); }
    }

    @Override public byte[] decompress(ByteBuffer input, int outputBytes, int maxOutputBytes) throws IOException {
        if (outputBytes < 0 || outputBytes > maxOutputBytes) throw new IOException("Zstd output budget exceeded");
        if (input.remaining() < 6) throw new IOException("Truncated Zstd frame");
        // Aircompressor returns immediately for a zero-capacity destination,
        // without inspecting the frame. Even empty logical chunks must validate.
        byte[] source = copy(input), result = new byte[Math.max(1, outputBytes)];
        try {
            int size = new ZstdDecompressor().decompress(source, 0, source.length, result, 0, result.length);
            if (size != outputBytes) throw new IOException("Zstd decoded size mismatch");
            return outputBytes == 0 ? new byte[0] : result;
        } catch (RuntimeException invalid) { throw new IOException("Invalid Zstd frame", invalid); }
    }

    private static byte[] copy(ByteBuffer input) {
        byte[] bytes = new byte[input.remaining()]; input.duplicate().get(bytes); return bytes;
    }
}
