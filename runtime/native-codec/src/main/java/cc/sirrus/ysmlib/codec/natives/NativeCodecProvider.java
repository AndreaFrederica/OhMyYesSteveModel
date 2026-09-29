package cc.sirrus.ysmlib.codec.natives;

import cc.sirrus.ysmlib.codec.HashProvider;
import cc.sirrus.ysmlib.codec.CompressionProvider;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;

/** Optional, independently built ysmlib accelerator. Never loads the official YSM library. */
public final class NativeCodecProvider implements HashProvider, CompressionProvider {
    public NativeCodecProvider(Path library) {
        System.load(library.toAbsolutePath().normalize().toString());
        if (nAbiVersion() != 1) throw new UnsatisfiedLinkError("ysmlib codec ABI mismatch");
        byte[] expected = HexFormat.of().parseHex("af1349b9f5f9a1a6a0404dea36dcc9499bcb25c9adc112b7cc9a93cae41f3262");
        if (!Arrays.equals(nHash(new byte[0]), expected))
            throw new UnsatisfiedLinkError("ysmlib BLAKE3 self-check failed");
        try {
            byte[] sample = {1, 2, 3, 4};
            if (!Arrays.equals(nDecompress(nCompress(sample, 3, 1024), sample.length), sample))
                throw new UnsatisfiedLinkError("ysmlib Zstd self-check failed");
        } catch (IOException invalid) {
            throw (UnsatisfiedLinkError) new UnsatisfiedLinkError("ysmlib Zstd self-check failed").initCause(invalid);
        }
    }
    @Override public String id() { return "native-ysmlib-codec-v1"; }
    @Override public byte[] blake3(ByteBuffer input) { return nHash(copy(input)); }
    @Override public byte[] compress(ByteBuffer input, int level, int maxOutputBytes) throws IOException {
        if (maxOutputBytes < 0) throw new IOException("Invalid compression budget");
        return nCompress(copy(input), level, maxOutputBytes);
    }
    @Override public byte[] decompress(ByteBuffer input, int outputBytes, int maxOutputBytes) throws IOException {
        if (outputBytes < 0 || outputBytes > maxOutputBytes) throw new IOException("Zstd output budget exceeded");
        return nDecompress(copy(input), outputBytes);
    }
    private static byte[] copy(ByteBuffer input) {
        byte[] bytes = new byte[input.remaining()]; input.duplicate().get(bytes); return bytes;
    }
    private static native int nAbiVersion();
    private static native byte[] nHash(byte[] input);
    private static native byte[] nCompress(byte[] input, int level, int maxOutputBytes) throws IOException;
    private static native byte[] nDecompress(byte[] input, int outputBytes) throws IOException;
}
