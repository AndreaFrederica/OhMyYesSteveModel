package com.elfmcys.ysm.natives.legacy;

import com.elfmcys.ysm.format.parser.DefaultAnimationFilter;
import com.elfmcys.ysm.format.schema.model.ModelFileView;
import com.elfmcys.ysm.model.catalog.RawModelImporter;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.DeflaterOutputStream;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LegacyRawArchiveIntegrationTest {
    @TempDir Path temp;

    @Test void v1AndV2UseRawPipelineAndMatchDirectoryModelIdentity() throws Exception {
        var raw = new RawModelImporter(DefaultAnimationFilter.keepAll());
        var directory = Path.of("src/main/resources/assets/ysm/builtin/misc/1_alex");
        var files = new TreeMap<String, byte[]>();
        try (var paths = Files.walk(directory)) {
            for (var path : paths.filter(Files::isRegularFile).toList())
                files.put(directory.relativize(path).toString().replace('\\', '/'), Files.readAllBytes(path));
        }
        try (var baseline = raw.capture(directory)) {
            for (int version : new int[]{1,2}) {
                Path archive = write(version, files);
                try (var captured = raw.capture(archive)) {
                    assertEquals(baseline.modelId(), captured.modelId());
                    var result = raw.convert(captured, temp.resolve("v" + version));
                    try (var channel = FileChannel.open(result.stagedContainer())) {
                        assertEquals(baseline.modelId(), new ModelFileView(channel).getModelHash());
                    }
                }
            }
        }
    }

    private Path write(int version, Map<String, byte[]> files) throws Exception {
        var entries = new ByteArrayOutputStream();
        var out = new DataOutputStream(entries);
        for (var entry : files.entrySet()) {
            byte[] name = entry.getKey().getBytes(StandardCharsets.UTF_8);
            if (version == 2) name = Base64.getEncoder().encode(name);
            out.writeInt(name.length); out.write(name);
            byte[] key = new byte[16], iv = new byte[16];
            new Random(718).nextBytes(key); new Random(916).nextBytes(iv);
            var compressed = new ByteArrayOutputStream();
            try (var zlib = new DeflaterOutputStream(compressed)) { zlib.write(entry.getValue()); }
            byte[] encrypted = encrypt(compressed.toByteArray(), key, iv);
            out.writeInt(encrypted.length);
            if (version == 1) out.write(key);
            else {
                // Historical writer used overflowing long accumulation, independently
                // spelling out the seed rather than sharing any decoder helper.
                long seed = 0;
                for (byte b : MessageDigest.getInstance("MD5").digest(encrypted)) seed = (seed << 8) + (b & 255);
                byte[] derived = new byte[16]; new Random(seed).nextBytes(derived);
                byte[] wrapped = encrypt(key, derived, iv);
                out.writeInt(wrapped.length); out.write(wrapped);
            }
            out.write(iv); out.write(encrypted);
        }
        var result = new ByteArrayOutputStream();
        var header = new DataOutputStream(result);
        header.writeInt(0x59534750); header.writeInt(version);
        header.write(MessageDigest.getInstance("MD5").digest(entries.toByteArray()));
        entries.writeTo(result);
        return Files.write(temp.resolve("fixture-" + version + ".ysm"), result.toByteArray());
    }

    private static byte[] encrypt(byte[] bytes, byte[] key, byte[] iv) throws Exception {
        var cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return cipher.doFinal(bytes);
    }
}
