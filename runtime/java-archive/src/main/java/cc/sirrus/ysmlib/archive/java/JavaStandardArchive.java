package cc.sirrus.ysmlib.archive.java;

import cc.sirrus.ysmlib.archive.Archive;
import cc.sirrus.ysmlib.archive.ArchiveFormat;
import cc.sirrus.ysmlib.archive.ArchiveLimits;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipFile;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;

final class JavaStandardArchive implements Archive {
    private final ArchiveIndex<Entry> index = new ArchiveIndex<>();
    private final ArchiveLimits limits;
    private java.io.Closeable owner;

    JavaStandardArchive(Path source, ArchiveFormat format, ArchiveLimits limits) throws IOException {
        this.limits = limits;
        if (Files.size(source) > limits.sourceBytes()) throw new IOException("Archive source size limit");
        try {
            int count = 0;
            if (format == ArchiveFormat.ZIP) {
                var zip = new ZipFile(source.toFile(), StandardCharsets.UTF_8);
                owner = zip;
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    checkEntry(++count, entry.getName(), entry.getSize());
                    index.add(entry.getName(), new Entry(entry.getSize(), entry.getCrc(),
                            () -> zip.getInputStream(entry)), entry.isDirectory());
                }
            } else {
                var seven = SevenZFile.builder().setPath(source)
                        .setMaxMemoryLimitKb(limits.decoderMemoryKiB()).get();
                owner = seven;
                for (var entry : seven.getEntries()) {
                    checkEntry(++count, entry.getName(), entry.getSize());
                    index.add(entry.getName(), new Entry(entry.getSize(),
                            entry.getHasCrc() ? entry.getCrcValue() : -1,
                            () -> seven.getInputStream(entry)), entry.isDirectory());
                }
            }
        } catch (IOException | RuntimeException | Error failure) {
            if (owner != null) {
                try { owner.close(); }
                catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
    }

    private void checkEntry(int count, String name, long size) throws IOException {
        if (count > limits.entries() || size < 0 || size > limits.entryBytes()
                || name == null || name.getBytes(StandardCharsets.UTF_8).length > limits.nameBytes()) {
            throw new IOException("Archive entry/name/size limit");
        }
    }

    @Override public List<String> files(String path) { checkOpen(); return index.list(path, false); }
    @Override public List<String> directories(String path) { checkOpen(); return index.list(path, true); }
    @Override public boolean contains(String path) { checkOpen(); return index.get(path) != null; }
    @Override public byte[] read(String path) throws IOException {
        checkOpen();
        Entry entry = index.get(path);
        if (entry == null) return null;
        try (var input = entry.reader.open()) {
            var output = new ByteArrayOutputStream((int) Math.min(8192, entry.size));
            var crc = new CRC32();
            byte[] block = new byte[8192];
            for (int count; (count = input.read(block)) != -1;) {
                if (count == 0) throw new IOException("Archive decoder made no progress");
                if (count > limits.entryBytes() - output.size()) throw new IOException("Archive entry size limit");
                output.write(block, 0, count);
                crc.update(block, 0, count);
            }
            if (output.size() != entry.size || (entry.crc >= 0 && crc.getValue() != entry.crc)) {
                throw new IOException("Archive entry size/CRC mismatch");
            }
            return output.toByteArray();
        }
    }

    @Override public void close() throws IOException {
        if (owner != null) {
            var closing = owner;
            owner = null;
            index.clear();
            closing.close();
        }
    }
    private void checkOpen() { if (owner == null) throw new IllegalStateException("Archive is closed"); }
    private record Entry(long size, long crc, Reader reader) {}
    @FunctionalInterface private interface Reader { InputStream open() throws IOException; }
}
