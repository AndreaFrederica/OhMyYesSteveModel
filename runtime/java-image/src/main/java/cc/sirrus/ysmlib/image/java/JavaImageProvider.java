package cc.sirrus.ysmlib.image.java;

import cc.sirrus.ysmlib.image.ImageProvider;
import cc.sirrus.ysmlib.codec.java.JavaCompressionProvider;
import cc.sirrus.ysmlib.image.wasm.WasmAvifDecoder;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.*;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** Java PNG/JPEG/WebP/ZTX with a JVM-hosted AVIF codec. No native library is loaded. */
public final class JavaImageProvider implements ImageProvider {
    private static final int MAX_BYTES = 256 * 1024 * 1024;
    @Override public String id() { return "java-image"; }

    /** Describes the AVIF backend without initializing its WASM module. */
    public String avifDecoderId() { return "Chicory (JVM/WASM)"; }

    @Override public Info probe(ByteBuffer encoded) throws IOException {
        byte[] bytes = copy(encoded);
        if (isAvif(bytes)) return new WasmAvifDecoder().probe(ByteBuffer.wrap(bytes));
        if (isZtx(bytes)) {
            if (bytes.length <= 12) throw new IOException("Truncated ZTX");
            var header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            return info(Format.ZTX, header.getInt(4), header.getInt(8));
        }
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            ImageReader reader = reader(input);
            try {
                reader.setInput(input, true, true);
                Format format = switch (reader.getFormatName().toLowerCase(java.util.Locale.ROOT)) {
                    case "png" -> Format.PNG;
                    case "jpeg", "jpg" -> Format.JPEG;
                    case "webp" -> Format.WEBP;
                    default -> throw new IOException("Unsupported image format");
                };
                return info(format, reader.getWidth(0), reader.getHeight(0));
            } finally { reader.dispose(); }
        }
    }

    @Override public byte[] decode(ByteBuffer encoded, Info expected) throws IOException {
        if (expected.format() == Format.RGBA) {
            if (encoded.remaining() != expected.pixelBytes()) throw new IOException("RGBA size mismatch");
            return copy(encoded);
        }
        Info actual = probe(encoded);
        if (!actual.equals(expected)) throw new IOException("Image metadata mismatch");
        if (actual.format() == Format.AVIF) return new WasmAvifDecoder().decode(encoded, expected);
        byte[] bytes = copy(encoded);
        if (actual.format() == Format.ZTX) {
            return new JavaCompressionProvider().decompress(ByteBuffer.wrap(bytes, 12, bytes.length - 12),
                    expected.pixelBytes(), MAX_BYTES);
        }
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var reader = reader(input);
            try {
                reader.setInput(input, true, true);
                BufferedImage image = reader.read(0);
                if (image == null || image.getWidth() != expected.width() || image.getHeight() != expected.height()) {
                    throw new IOException("Image decoded size mismatch");
                }
                byte[] pixels = new byte[expected.pixelBytes()];
                int[] row = new int[expected.width()];
                for (int y = 0, offset = 0; y < expected.height(); y++) {
                    image.getRGB(0, y, expected.width(), 1, row, 0, expected.width());
                    for (int argb : row) {
                        pixels[offset++] = (byte) (argb >>> 16); pixels[offset++] = (byte) (argb >>> 8);
                        pixels[offset++] = (byte) argb; pixels[offset++] = (byte) (argb >>> 24);
                    }
                }
                return pixels;
            } finally { reader.dispose(); }
        }
    }

    @Override public Encoded encode(ByteBuffer rgba, int width, int height, int maxWidth, int maxHeight) throws IOException {
        Info source = info(Format.RGBA, width, height);
        if (rgba.remaining() < source.pixelBytes()) throw new IOException("RGBA input too small");
        if (maxWidth < 1 || maxHeight < 1) throw new IOException("Invalid image limits");
        // ZTX is lossless; presentation images are fitted before encoding like the
        // existing constrained-device path, preserving alpha instead of flattening it.
        byte[] pixels = new byte[source.pixelBytes()]; rgba.duplicate().get(pixels);
        if (width > maxWidth || height > maxHeight) {
            double scale = Math.min((double) maxWidth / width, (double) maxHeight / height);
            int scaledWidth = Math.max(1, Math.min(maxWidth, (int) Math.round(width * scale)));
            int scaledHeight = Math.max(1, Math.min(maxHeight, (int) Math.round(height * scale)));
            pixels = scale(pixels, width, height, scaledWidth, scaledHeight);
            width = scaledWidth; height = scaledHeight;
        }
        byte[] compressed = new JavaCompressionProvider().compress(ByteBuffer.wrap(pixels), 16, MAX_BYTES - 12);
        byte[] result = new byte[12 + compressed.length];
        ByteBuffer out = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN);
        out.put(new byte[]{'z','t','x','1'}).putInt(width).putInt(height).put(compressed);
        return new Encoded(info(Format.ZTX, width, height), result);
    }

    private static byte[] scale(byte[] input, int width, int height, int targetWidth, int targetHeight) {
        byte[] result = new byte[targetWidth * targetHeight * 4];
        for (int y = 0; y < targetHeight; y++) {
            double top = (double) y * height / targetHeight, bottom = (double) (y + 1) * height / targetHeight;
            for (int x = 0; x < targetWidth; x++) {
                double left = (double) x * width / targetWidth, right = (double) (x + 1) * width / targetWidth;
                double[] sum = new double[4];
                for (int sy = (int) top; sy < Math.ceil(bottom); sy++) {
                    double wy = Math.min(bottom, sy + 1) - Math.max(top, sy);
                    for (int sx = (int) left; sx < Math.ceil(right); sx++) {
                        double weight = wy * (Math.min(right, sx + 1) - Math.max(left, sx));
                        int offset = (sy * width + sx) * 4;
                        for (int c = 0; c < 4; c++) sum[c] += (input[offset + c] & 255) * weight;
                    }
                }
                double area = (right - left) * (bottom - top);
                int offset = (y * targetWidth + x) * 4;
                for (int c = 0; c < 4; c++) result[offset + c] = (byte) Math.round(sum[c] / area);
            }
        }
        return result;
    }

    private static ImageReader reader(MemoryCacheImageInputStream input) throws IOException {
        var readers = ImageIO.getImageReaders(input);
        if (!readers.hasNext()) throw new IOException("Unsupported or invalid image");
        return readers.next();
    }
    private static Info info(Format format, int width, int height) throws IOException {
        try { return new Info(format, width, height); }
        catch (IllegalArgumentException invalid) { throw new IOException("Invalid image dimensions", invalid); }
    }
    private static boolean isZtx(byte[] bytes) {
        return bytes.length >= 4 && bytes[0]=='z' && bytes[1]=='t' && bytes[2]=='x' && bytes[3]=='1';
    }
    private static boolean isAvif(byte[] bytes) {
        if (bytes.length < 16 || bytes[4] != 'f' || bytes[5] != 't' || bytes[6] != 'y' || bytes[7] != 'p') return false;
        int size = ByteBuffer.wrap(bytes).getInt();
        if (size < 16 || size > bytes.length) return false;
        for (int offset = 8; offset <= size - 4; offset += 4) {
            if (offset == 12) continue; // minor_version is not a compatible brand
            if (bytes[offset] == 'a' && bytes[offset + 1] == 'v' && bytes[offset + 2] == 'i'
                    && (bytes[offset + 3] == 'f' || bytes[offset + 3] == 's')) return true;
        }
        return false;
    }
    private static byte[] copy(ByteBuffer input) throws IOException {
        if (input.remaining() > MAX_BYTES) throw new IOException("Encoded image limit");
        byte[] bytes = new byte[input.remaining()]; input.duplicate().get(bytes); return bytes;
    }
}
