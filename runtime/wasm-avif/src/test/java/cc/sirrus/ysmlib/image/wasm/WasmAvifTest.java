package cc.sirrus.ysmlib.image.wasm;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class WasmAvifTest {
    @Test void decodesEveryBuiltinAvifWithoutNative() throws Exception {
        var codec = new WasmAvifDecoder();
        List<Path> files;
        try (var paths = Files.walk(Path.of(System.getProperty("ysm.test.builtinRoot")))) {
            files = paths.filter(p -> p.toString().endsWith(".avif")).sorted().toList();
        }
        assertFalse(files.isEmpty());
        for (var path : files) {
            var encoded = ByteBuffer.wrap(Files.readAllBytes(path)).asReadOnlyBuffer();
            var info = codec.probe(encoded);
            byte[] pixels = codec.decode(encoded, info);
            assertEquals(info.pixelBytes(), pixels.length, path.toString());
            assertEquals(0, encoded.position());
            Path relative = Path.of(System.getProperty("ysm.test.builtinRoot")).relativize(path);
            Path output = Path.of("build/decoded").resolve(relative + ".rgba");
            Files.createDirectories(output.getParent()); Files.write(output, pixels);
            System.out.println(path.getFileName() + " " + info.width() + "x" + info.height());
        }
    }

    @Test void rejectsInvalidAndTruncatedData() {
        var codec = new WasmAvifDecoder();
        assertThrows(IOException.class, () -> codec.probe(ByteBuffer.wrap(new byte[]{1, 2, 3})));
    }

    @Test void preservesIndependentRgbaFixtureIncludingAlpha() throws Exception {
        var codec = new WasmAvifDecoder();
        byte[] source, expected;
        try (var in = getClass().getResourceAsStream("/alpha.avif")) { source = in.readAllBytes(); }
        try (var in = getClass().getResourceAsStream("/alpha.rgba")) { expected = in.readAllBytes(); }
        var info = codec.probe(ByteBuffer.wrap(source));
        assertEquals(17, info.width()); assertEquals(13, info.height());
        byte[] actual = codec.decode(ByteBuffer.wrap(source), info);
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            int difference = Math.abs((expected[i] & 255) - (actual[i] & 255));
            // Pillow uses libyuv integer conversion; this build uses libavif's scalar conversion.
            assertTrue(difference <= (i % 4 == 3 ? 0 : 2), "RGBA component " + i + " differs by " + difference);
        }
        assertThrows(IOException.class, () -> codec.decode(ByteBuffer.wrap(source),
                new cc.sirrus.ysmlib.image.ImageProvider.Info(info.format(), 1, 1)));
        assertThrows(IOException.class, () -> codec.decode(ByteBuffer.wrap(java.util.Arrays.copyOf(source, source.length / 2)), info));
    }
}
