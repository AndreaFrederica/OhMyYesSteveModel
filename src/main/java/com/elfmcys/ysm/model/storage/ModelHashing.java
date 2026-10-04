package com.elfmcys.ysm.model.storage;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.natives.Blake3;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class ModelHashing {
    private ModelHashing() {
    }

    public static Hash256 blake3(byte[] data) {
        return new Hash256(Blake3.computeHash(ArrayBuffer.borrow(data)));
    }

    public static Hash256 blake3(ByteBuffer data) {
        try (var copy = ArrayBuffer.copyOf(data)) {
            return new Hash256(Blake3.computeHash(copy));
        }
    }

    public static Hash256 blake3(UniBuffer data) {
        return new Hash256(Blake3.computeHash(data));
    }

    public static Hash256 blake3(Path file) throws IOException {
        var size = Files.size(file);
        if (size < 0 || size > UniBuffer.MAX_SIZE) {
            throw new IOException("File is too large to hash: " + file);
        }
        try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
            Hash256 hash;
            try {
                try (var source = NativeBuffer.mapFile(channel, 0, size)) {
                    hash = new Hash256(Blake3.computeHash(source));
                }
            } catch (UnsupportedOperationException unsupported) {
                // Zip/Jar file systems do not expose map(). Keep the fallback bounded so
                // a provider that cannot map a large source cannot recreate the old spike.
                if (size > 64L * 1024 * 1024) {
                    throw new IOException("File system cannot memory-map a large source: " + file,
                            unsupported);
                }
                var source = ByteBuffer.allocate(Math.toIntExact(size));
                while (source.hasRemaining()) {
                    if (channel.read(source) < 0) {
                        throw new IOException("File changed while hashing: " + file);
                    }
                }
                hash = new Hash256(Blake3.computeHash(source.flip()));
            }
            if (channel.size() != size) {
                throw new IOException("File changed while hashing: " + file);
            }
            return hash;
        }
    }

}
