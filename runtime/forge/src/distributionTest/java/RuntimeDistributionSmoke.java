import cc.sirrus.ysmlib.YsmRuntime;
import cc.sirrus.ysmlib.archive.*;
import cc.sirrus.ysmlib.audio.SupportedAudioProbe;
import java.nio.file.*;
import java.util.Arrays;
import java.nio.ByteBuffer;
import java.util.HexFormat;
import java.util.zip.*;

/** Launched with only the actual shaded prerequisite JAR and these test classes. */
public final class RuntimeDistributionSmoke {
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("ysm-runtime-distribution-");
        Path zip = dir.resolve("model.zip"), seven = dir.resolve("model.7z");
        try {
            byte[] expected = {1,2,3,4};
            byte[] digest = YsmRuntime.hashes().blake3(ByteBuffer.allocate(0));
            if (!HexFormat.of().formatHex(digest).equals("af1349b9f5f9a1a6a0404dea36dcc9499bcb25c9adc112b7cc9a93cae41f3262")) {
                throw new AssertionError("Shaded BLAKE3 implementation mismatch");
            }
            byte[] compressed = YsmRuntime.compression().compress(ByteBuffer.wrap(expected), 3, 1024);
            if (!Arrays.equals(expected, YsmRuntime.compression().decompress(ByteBuffer.wrap(compressed), 4, 1024))) {
                throw new AssertionError("Shaded Zstandard implementation mismatch");
            }
            try (var out = new ZipOutputStream(Files.newOutputStream(zip))) {
                out.putNextEntry(new ZipEntry("test")); out.write(expected); out.closeEntry();
            }
            verify(zip, ArchiveFormat.ZIP, expected);
            // Reflection deliberately names the shaded implementation, verifying relocation.
            String prefix = "cc.sirrus.ysmlib.internal.apache.commons.compress.archivers.";
            Class<?> writerType = Class.forName(prefix + "sevenz.SevenZOutputFile");
            Class<?> entryType = Class.forName(prefix + "sevenz.SevenZArchiveEntry");
            Object entry = entryType.getConstructor().newInstance();
            entryType.getMethod("setName", String.class).invoke(entry, "test");
            try (var writer = (AutoCloseable) writerType.getConstructor(java.io.File.class).newInstance(seven.toFile())) {
                writerType.getMethod("putArchiveEntry", Class.forName(prefix + "ArchiveEntry")).invoke(writer, entry);
                writerType.getMethod("write", byte[].class).invoke(writer, (Object) expected);
                writerType.getMethod("closeArchiveEntry").invoke(writer);
            }
            verify(seven, ArchiveFormat.SEVEN_ZIP, expected);
            try (var input = RuntimeDistributionSmoke.class.getResourceAsStream("/alpha.avif");
                 var reference = RuntimeDistributionSmoke.class.getResourceAsStream("/alpha.rgba")) {
                var avif = ByteBuffer.wrap(input.readAllBytes());
                var info = YsmRuntime.images().probe(avif);
                byte[] expectedPixels = reference.readAllBytes(), actual = YsmRuntime.images().decode(avif, info);
                if (actual.length != expectedPixels.length) throw new AssertionError("AVIF size mismatch");
                for (int i = 0; i < actual.length; i++) {
                    if (Math.abs((actual[i] & 255) - (expectedPixels[i] & 255)) > (i % 4 == 3 ? 0 : 2))
                        throw new AssertionError("Shaded JVM AVIF decoder mismatch at component " + i);
                }
            }
            try {
                Class.forName("org.apache.commons.compress.archivers.sevenz.SevenZFile");
                throw new AssertionError("Unrelocated dependency leaked into distribution");
            } catch (ClassNotFoundException expectedAbsence) { }
            for (String codec : new String[]{"opus", "vorbis"}) {
                try (var input = RuntimeDistributionSmoke.class.getResourceAsStream("/" + codec + "-mono.ogg")) {
                    var data = ByteBuffer.wrap(input.readAllBytes());
                    var media = SupportedAudioProbe.inspect(data).media();
                    long count = 0;
                    try (var decoder = YsmRuntime.audio().open(data, media)) {
                        var output = ByteBuffer.allocate(2048); int read;
                        while ((read = decoder.read(output.clear())) > 0) count += read;
                    }
                    if (count != 120002) throw new AssertionError("Shaded " + codec + " frame count mismatch");
                }
            }
            try (var input = RuntimeDistributionSmoke.class.getResourceAsStream("/legacy_v3_dynamic_vector.ysm")) {
                byte[] envelope = input.readAllBytes();
                if (YsmRuntime.v3().decode(ByteBuffer.wrap(envelope), 12004).length != 12004)
                    throw new AssertionError("Shaded V3 envelope mismatch");
                Path source = Files.write(dir.resolve("original.ysm"), envelope);
                Path workspace = new cc.sirrus.ysmlib.v3d.V3dCache(YsmRuntime.v3())
                        .materialize(source, dir.resolve("v3d"));
                cc.sirrus.ysmlib.v3d.V3dCache.validate(workspace);
                Path restored = dir.resolve("restored.ysm");
                cc.sirrus.ysmlib.v3d.V3dCache.restoreOriginal(workspace, restored);
                if (Files.mismatch(source, restored) != -1) throw new AssertionError("Shaded V3D restore mismatch");
            }
            try (var input = RuntimeDistributionSmoke.class.getResourceAsStream("/historical/v32.wire")) {
                var paths = new java.util.HashSet<String>();
                YsmRuntime.decodedWorkspace().materialize(ByteBuffer.wrap(input.readAllBytes()), (path, bytes) -> paths.add(path));
                if (!paths.contains("legacy/model.json") || paths.stream().noneMatch(p -> p.endsWith(".tga")))
                    throw new AssertionError("Shaded historical workspace is incomplete");
            }
            System.out.println("Standalone prerequisite JAR: archives/hash/zstd/images/Opus/Vorbis/V3/workspace passed without Forge or YSM native");
        } finally {
            try (var paths = Files.walk(dir)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void verify(Path source, ArchiveFormat format, byte[] expected) throws Exception {
        try (var archive = YsmRuntime.archives().open(source, format, ArchiveLimits.DEFAULT)) {
            if (!Arrays.equals(expected, archive.read("test"))) throw new AssertionError(format);
        }
    }
}
