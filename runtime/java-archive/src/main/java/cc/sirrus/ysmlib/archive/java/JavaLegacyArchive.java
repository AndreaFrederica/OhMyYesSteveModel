package cc.sirrus.ysmlib.archive.java;

import cc.sirrus.ysmlib.archive.Archive;
import cc.sirrus.ysmlib.archive.ArchiveFormat;
import cc.sirrus.ysmlib.archive.ArchiveLimits;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** V1/V2 raw-file archives. No current-schema, V3, Minecraft or JNI dependency. */
public final class JavaLegacyArchive implements Archive {
    private final ArchiveIndex<Entry> index = new ArchiveIndex<>();
    private final ArchiveLimits limits;
    private byte[] source;

    public JavaLegacyArchive(Path path, ArchiveFormat format, ArchiveLimits limits) throws IOException {
        this.limits = limits;
        if (format != ArchiveFormat.LEGACY_V1 && format != ArchiveFormat.LEGACY_V2) {
            throw new IllegalArgumentException("Not a legacy raw archive format");
        }
        long size = Files.size(path);
        if (size <= 24 || size > limits.sourceBytes()) throw new IOException("Archive source size limit");
        try (var input = Files.newInputStream(path)) {
            source = input.readNBytes(Math.toIntExact(size));
            if (source.length != size || input.read() != -1) throw new IOException("Archive source changed");
        }
        ByteBuffer bytes = ByteBuffer.wrap(source);
        int version = format == ArchiveFormat.LEGACY_V1 ? 1 : 2;
        if (bytes.getInt() != 0x59534750 || bytes.getInt() != version) {
            throw new IOException("Legacy archive header/version mismatch");
        }
        byte[] expected = take(bytes, 16);
        if (!MessageDigest.isEqual(expected, md5(source, 24, source.length - 24))) {
            throw new IOException("Legacy archive MD5 mismatch");
        }
        int entries = 0;
        while (bytes.hasRemaining()) {
            if (++entries > limits.entries()) throw new IOException("Archive entry count limit");
            byte[] name = take(bytes, length(bytes, limits.nameBytes()));
            if (version == 2) {
                try { name = Base64.getDecoder().decode(name); }
                catch (IllegalArgumentException invalid) { throw new IOException("Invalid Base64 filename", invalid); }
            }
            String pathName = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(name)).toString();
            int encryptedSize = length(bytes, limits.sourceBytes());
            if (encryptedSize == 0 || encryptedSize % 16 != 0) throw new IOException("Invalid AES payload size");
            byte[] key = take(bytes, version == 1 ? 16 : length(bytes, limits.sourceBytes()));
            if (version == 2 && (key.length == 0 || key.length % 16 != 0)) {
                throw new IOException("Invalid wrapped AES key size");
            }
            byte[] iv = take(bytes, 16);
            require(bytes, encryptedSize);
            index.add(pathName, new Entry(bytes.position(), encryptedSize, key, iv, version), false);
            bytes.position(bytes.position() + encryptedSize);
        }
    }

    @Override public List<String> files(String path) { checkOpen(); return index.list(path, false); }
    @Override public List<String> directories(String path) { checkOpen(); return index.list(path, true); }
    @Override public boolean contains(String path) { checkOpen(); return index.get(path) != null; }

    @Override public byte[] read(String path) throws IOException {
        checkOpen();
        Entry entry = index.get(path);
        if (entry == null) return null;
        byte[] key = entry.key;
        if (entry.version == 2) {
            byte[] digest = md5(source, entry.offset, entry.size);
            long seed = ByteBuffer.wrap(digest).getLong(8);
            byte[] derived = new byte[16];
            new Random(seed).nextBytes(derived);
            key = decrypt(entry.key, 0, entry.key.length, derived, entry.iv);
            if (key.length != 16) throw new IOException("Wrapped key is not AES-128");
        }
        byte[] compressed = decrypt(source, entry.offset, entry.size, key, entry.iv);
        var inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            var output = new ByteArrayOutputStream(Math.min(8192, limits.entryBytes()));
            byte[] block = new byte[8192];
            while (!inflater.finished()) {
                int count = inflater.inflate(block);
                if (count > limits.entryBytes() - output.size()) throw new IOException("Archive entry size limit");
                output.write(block, 0, count);
                if (count == 0 && !inflater.finished()) throw new IOException("Truncated or invalid zlib stream");
            }
            if (inflater.getRemaining() != 0) throw new IOException("Trailing zlib data");
            return output.toByteArray();
        } catch (DataFormatException invalid) {
            throw new IOException("Invalid zlib data", invalid);
        } finally {
            inflater.end();
        }
    }

    @Override public void close() { source = null; index.clear(); }
    private void checkOpen() { if (source == null) throw new IllegalStateException("Archive is closed"); }

    private static byte[] decrypt(byte[] source, int offset, int size, byte[] key, byte[] iv) throws IOException {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(source, offset, size);
        } catch (GeneralSecurityException invalid) {
            throw new IOException("Legacy AES decryption failed", invalid);
        }
    }

    private static byte[] md5(byte[] bytes, int offset, int size) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            digest.update(bytes, offset, size);
            return digest.digest();
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static int length(ByteBuffer input, int limit) throws IOException {
        require(input, 4);
        long value = Integer.toUnsignedLong(input.getInt());
        if (value > limit) throw new IOException("Archive field size limit");
        return (int) value;
    }
    private static void require(ByteBuffer input, int size) throws IOException {
        if (size < 0 || size > input.remaining()) throw new IOException("Truncated archive entry");
    }
    private static byte[] take(ByteBuffer input, int size) throws IOException {
        require(input, size);
        byte[] result = new byte[size];
        input.get(result);
        return result;
    }
    private record Entry(int offset, int size, byte[] key, byte[] iv, int version) {}
}
