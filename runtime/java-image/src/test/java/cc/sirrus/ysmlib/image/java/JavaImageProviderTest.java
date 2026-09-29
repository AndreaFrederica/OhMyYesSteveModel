package cc.sirrus.ysmlib.image.java;

import cc.sirrus.ysmlib.image.ImageProvider;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JavaImageProviderTest {
    private final JavaImageProvider codec = new JavaImageProvider();

    @Test void pngPreservesRgbaAndZtxRoundTripsTransparentPixels() throws Exception {
        var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xffff0000); image.setRGB(1, 0, 0x8000ff00);
        image.setRGB(0, 1, 0x000000ff); image.setRGB(1, 1, 0xffffffff);
        var bytes = new ByteArrayOutputStream(); assertTrue(ImageIO.write(image, "png", bytes));
        var input = ByteBuffer.wrap(bytes.toByteArray()).asReadOnlyBuffer();
        var info = codec.probe(input);
        assertEquals(new ImageProvider.Info(ImageProvider.Format.PNG, 2, 2), info);
        byte[] expected = {(byte)255,0,0,(byte)255, 0,(byte)255,0,(byte)128,
                0,0,(byte)255,0, (byte)255,(byte)255,(byte)255,(byte)255};
        assertArrayEquals(expected, codec.decode(input, info));
        var encoded = codec.encode(ByteBuffer.wrap(expected), 2, 2, 2, 2);
        assertEquals(ImageProvider.Format.ZTX, encoded.info().format());
        assertArrayEquals(expected, codec.decode(ByteBuffer.wrap(encoded.bytes()), encoded.info()));
        assertEquals(0, input.position());
        var resized = codec.encode(ByteBuffer.wrap(expected), 2, 2, 1, 1);
        assertEquals(1, resized.info().width()); assertEquals(1, resized.info().height());
        assertEquals(160, codec.decode(ByteBuffer.wrap(resized.bytes()), resized.info())[3] & 255);
        byte[] truncated = java.util.Arrays.copyOf(encoded.bytes(), encoded.bytes().length - 1);
        assertThrows(IOException.class, () -> codec.decode(ByteBuffer.wrap(truncated), encoded.info()));
        assertThrows(IOException.class, () -> codec.decode(input,
                new ImageProvider.Info(ImageProvider.Format.PNG, 3, 2)));
    }

    @Test void decodesAllBuiltinImagesWithoutYsmNative() throws Exception {
        Path root = Path.of(System.getProperty("ysm.test.builtinRoot"));
        int count = 0;
        try (var files = Files.walk(root)) {
            for (Path path : files.filter(p -> p.toString().matches(".*\\.(webp|jpg|png|avif)")).toList()) {
                byte[] bytes = Files.readAllBytes(path); var info = codec.probe(ByteBuffer.wrap(bytes));
                assertEquals(info.pixelBytes(), codec.decode(ByteBuffer.wrap(bytes), info).length, path.toString());
                count++;
            }
        }
        assertEquals(200, count, "Builtin image corpus changed; review test coverage");
    }
}
