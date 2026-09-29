package com.elfmcys.ysm.format.vfs;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.UniBuffer;
import com.elfmcys.ysm.util.Closeable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;

public class Directory implements VirtualFileSystem, Closeable {
    private final Path dir;
    private ArrayBuffer buffer;
    private boolean closed;

    public Directory(Path dir) {
        this.dir = dir.normalize().toAbsolutePath();
    }

    @Override
    public Directory directoryView(String path) {
        var subDir = prependPath(path);
        if (subDir == null) {
            throw new IllegalArgumentException("Illegal sub path: " + path);
        }
        return new Directory(subDir);
    }

    @Nullable
    private Path prependPath(@Nullable String path) {
        checkOpen();
        if (path == null) {
            return this.dir;
        }
        var newPath = this.dir.resolve(path).normalize();
        if (!newPath.startsWith(this.dir)) {
            return null;
        }
        return newPath;
    }

    @Override
    public @NotNull String[] listFiles(@Nullable String path) {
        return list(path, false);
    }

    @Override
    public @NotNull String[] listDirectories(@Nullable String path) {
        return list(path, true);
    }

    @Override
    public boolean hasFile(String fileName) {
        var file = prependPath(fileName);
        return file != null && Files.isRegularFile(file);
    }

    private ArrayBuffer getBufferView(int size) {
        if (buffer != null) {
            if (buffer.size() < size) {
                var newSize = Math.max(size, Math.min(UniBuffer.MAX_SIZE, buffer.size() * 2));
                buffer.close();
                buffer = ArrayBuffer.allocate(newSize);
            }
        } else {
            buffer = ArrayBuffer.allocate(Math.max(32 * 1024, size));
        }
        return buffer.slice(0, size).borrow();
    }

    @Override
    public @Nullable ArrayBuffer getFile(String fileName) {
        var file = prependPath(fileName);
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }

        try (var channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
            var size = channel.size();
            if (size > UniBuffer.MAX_SIZE) {
                throw new IllegalArgumentException("File is too large: " + file);
            }

            var buffer = getBufferView((int) size);
            readFully(channel, buffer.nio(), file);
            return buffer;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        if (!closed) {
            if (buffer != null) {
                buffer.close();
                buffer = null;
            }
            closed = true;
        }
    }

    private String[] list(@Nullable String path, boolean directories) {
        var target = prependPath(path);
        if (target == null) {
            throw new IllegalArgumentException("Illegal sub path: " + path);
        }
        if (!Files.isDirectory(target)) {
            return new String[0];
        }

        try (var stream = Files.list(target)) {
            return stream
                    .filter(child -> directories ? Files.isDirectory(child) : Files.isRegularFile(child))
                    .map(child -> child.getFileName().toString())
                    .sorted(Comparator.naturalOrder())
                    .toArray(String[]::new);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("Directory has been closed");
        }
    }

    private static void readFully(SeekableByteChannel channel, ByteBuffer dst, Path file) throws IOException {
        while (dst.hasRemaining()) {
            if (channel.read(dst) < 0) {
                throw new EOFException("File changed while reading: " + file);
            }
        }
    }
}
