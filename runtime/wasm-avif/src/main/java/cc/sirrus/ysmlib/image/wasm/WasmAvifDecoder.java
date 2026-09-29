package cc.sirrus.ysmlib.image.wasm;

import cc.sirrus.ysmlib.image.ImageProvider;
import com.dylibso.chicory.compiler.MachineFactoryCompiler;
import com.dylibso.chicory.runtime.*;
import com.dylibso.chicory.wasi.WasiPreview1;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.function.Function;

/** libavif/libaom executed as JVM bytecode by Chicory, without JNI or a system WASM engine. */
public final class WasmAvifDecoder {
    private static final int MAX_INPUT = 256 * 1024 * 1024;
    private static final class Module {
        static final WasmModule CODE = load();
        static final Function<Instance, Machine> FACTORY = MachineFactoryCompiler.compile(CODE);
        private static WasmModule load() {
            try (var input = WasmAvifDecoder.class.getResourceAsStream("/cc/sirrus/ysmlib/avif/decoder.wasm")) {
                if (input == null) throw new IOException("Bundled AVIF decoder is missing");
                return Parser.parse(input);
            } catch (IOException failure) { throw new UncheckedIOException(failure); }
        }
    }

    public ImageProvider.Info probe(ByteBuffer encoded) throws IOException { return read(encoded, false).info(); }

    public byte[] decode(ByteBuffer encoded, ImageProvider.Info expected) throws IOException {
        var result = read(encoded, true);
        if (!result.info().equals(expected)) throw new IOException("AVIF metadata mismatch");
        return result.bytes();
    }

    private ImageProvider.Encoded read(ByteBuffer encoded, boolean decode) throws IOException {
        if (encoded.remaining() == 0 || encoded.remaining() > MAX_INPUT) throw new IOException("AVIF input budget exceeded");
        // Default WASI options expose no directories, environment or inherited standard streams.
        // Each operation owns its memory, decoder state and host descriptors, including on traps.
        try (var wasi = WasiPreview1.builder().build()) {
            var instance = Instance.builder(Module.CODE).withMachineFactory(Module.FACTORY)
                    .withImportValues(ImportValues.builder().addFunction(wasi.toHostFunctions()).build()).build();
            int length = encoded.remaining();
            int address = (int) instance.export("malloc").apply(length)[0];
            if (address == 0) throw new IOException("AVIF working memory budget exceeded");
            byte[] source = new byte[length]; encoded.duplicate().get(source);
            instance.memory().write(address, source);
            int status = (int) instance.export("ysm_avif_read").apply(address, length, decode ? 1 : 0)[0];
            if (status != 0) throw new IOException("Invalid or unsupported AVIF, libavif status=" + status);
            int width = (int) instance.export("ysm_avif_width").apply()[0];
            int height = (int) instance.export("ysm_avif_height").apply()[0];
            var info = new ImageProvider.Info(ImageProvider.Format.AVIF, width, height);
            int pixels = (int) instance.export("ysm_avif_pixels").apply()[0];
            if (decode && pixels == 0) throw new IOException("AVIF decoder returned no pixels");
            return new ImageProvider.Encoded(info, decode ? instance.memory().readBytes(pixels, info.pixelBytes()) : new byte[0]);
        } catch (RuntimeException invalid) {
            throw new IOException("AVIF JVM decoder failed", invalid);
        }
    }
}
