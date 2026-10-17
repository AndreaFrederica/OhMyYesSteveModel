package com.elfmcys.ysm.model.catalog;

import cc.sirrus.ysmlib.YsmRuntime;
import cc.sirrus.ysmlib.cpm.CpmDocument;
import cc.sirrus.ysmlib.scene.ByteData;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import com.elfmcys.ysm.format.parser.CpmYsmModelAdapter;
import com.elfmcys.ysm.format.parser.CpmTextureAtlas;
import com.elfmcys.ysm.format.parser.GeoBuilder;
import com.elfmcys.ysm.format.parser.RawCompileResult;
import com.elfmcys.ysm.format.schema.model.ModelFileConstant;
import com.elfmcys.ysm.format.schema.model.ModelFileWriter;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.storage.ModelHashing;
import com.elfmcys.ysm.natives.image.Image;
import com.elfmcys.ysm.proto.mixel.asset.model.ModelData;
import com.elfmcys.ysm.proto.mixel.asset.strings.StringData;
import com.elfmcys.ysm.proto.mixel.manifest.Manifest;
import com.elfmcys.ysm.proto.mixel.manifest.asset.Common;
import com.elfmcys.ysm.proto.mixel.manifest.asset.PBRTextureSet;
import com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTarget;
import com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind;
import com.elfmcys.ysm.proto.mixel.manifest.info.Info;
import com.elfmcys.ysm.proto.mixel.manifest.info.Metadata;
import com.elfmcys.ysm.proto.mixel.manifest.info.ModelSettings;
import com.elfmcys.ysm.proto.mixel.manifest.info.ModelStats;
import com.elfmcys.ysm.proto.mixel.manifest.info.Properties;
import com.elfmcys.ysm.proto.mixel.manifest.info.Settings;
import com.elfmcys.ysm.util.ProtoUtil;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Converts a CPM source directory to the regular YSM player container. */
public final class CpmModelImporter {
    public static final ReadLimits LIMITS = ReadLimits.DEFAULT;
    private static final List<String> IMAGE_EXTENSIONS = List.of(".png", ".jpg", ".jpeg", ".bmp");
    private static final java.util.concurrent.ConcurrentMap<Hash256, CpmDocument> DOCUMENTS =
            new java.util.concurrent.ConcurrentHashMap<>();

    public Captured capture(Path source) throws IOException {
        Objects.requireNonNull(source, "source");
        var files = Files.isDirectory(source) ? readFiles(source) : readArchive(source);
        var hash = hash(files);
        var document = YsmRuntime.cpm().read(files, LIMITS);
        remember(hash, document);
        return new Captured(hash, document);
    }

    public static java.util.Optional<cc.sirrus.ysmlib.cpm.CpmPoseSession> createPoseSession(Hash256 hash) {
        if (hash == null) {
            return java.util.Optional.empty();
        }
        var document = DOCUMENTS.get(hash);
        return document == null ? java.util.Optional.empty() : YsmRuntime.cpm().createPoseSession(document);
    }

    /** Returns the parsed CPM source for host feature adapters (attachments,
     * item sockets and diagnostics).  The returned document is immutable. */
    public static java.util.Optional<CpmDocument> document(Hash256 hash) {
        return hash == null ? java.util.Optional.empty() : java.util.Optional.ofNullable(DOCUMENTS.get(hash));
    }

    /** Restores a persisted CPM source closure from a converted YSM container. */
    public static void restoreSource(Hash256 hash, byte[] archive) throws IOException {
        Objects.requireNonNull(hash, "hash");
        Objects.requireNonNull(archive, "archive");
        var files = readArchiveBytes(archive);
        if (!hash.equals(hash(files))) {
            throw new IOException("Persisted CPM source hash does not match model identity");
        }
        var document = YsmRuntime.cpm().read(files, LIMITS);
        remember(hash, document);
    }

    private static void remember(Hash256 hash, CpmDocument document) {
        DOCUMENTS.put(hash, document);
        while (DOCUMENTS.size() > 256) {
            var first = DOCUMENTS.keySet().stream().findFirst().orElse(null);
            if (first == null) break;
            DOCUMENTS.remove(first);
        }
    }

    public RawCompileResult convert(Captured captured, Path outputDirectory) throws IOException {
        Objects.requireNonNull(captured, "captured");
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        var atlas = CpmTextureAtlas.build(captured.document());
        var geometry = CpmYsmModelAdapter.build(captured.document(), atlas.layout());
        Files.createDirectories(outputDirectory);
        var staged = outputDirectory.resolve("cpm.mxc");
        var name = captured.document().metadata().name().isBlank()
                ? "CPM model" : captured.document().metadata().name();
        try (var writer = new ModelFileWriter();
             var output = Files.newByteChannel(staged, StandardOpenOption.CREATE,
                     StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            var modelData = ModelData.newBuilder();
            var geoBytes = ProtoUtil.serializeToArray(geometry.model());
            modelData.putGeoModels("main", ByteBuffer.wrap(geoBytes));
            modelData.putGeoModels("arm", ByteBuffer.wrap(geoBytes));
            var target = RenderTarget.newBuilder().setTargetId("player")
                    .setKind(RenderTargetKind.RENDER_TARGET_KIND_PLAYER)
                    .setSettings(ModelSettings.newBuilder()
                            .setHeightScale(geometry.heightScale())
                            .setWidthScale(geometry.widthScale()).build())
                    .setStats(geometry.stats().toProto());
            var defaultTexture = addAtlasTexture(writer, target, atlas);
            target.setBlobId(writer.addProtoBlob(modelData.build(), 16));

            var origin = captured.document() instanceof CpmDocument.Legacy ? "cpm-legacy" : "cpm-remake";
            var properties = Properties.newBuilder().setModelId(ByteBuffer.wrap(captured.modelHash().bytes()))
                    .setOriginVer(origin).setFree(false).build();
            var metadata = Metadata.newBuilder().setName(name)
                    .setLicense(com.elfmcys.ysm.proto.mixel.manifest.info.License.newBuilder()
                            .setType("CPM source; model license not declared").setDesc(
                                    "Parser/runtime attribution is bundled; verify the model author's license before redistribution.").build());
            if (!captured.document().metadata().authors().isEmpty()) {
                metadata.addAuthors(com.elfmcys.ysm.proto.mixel.manifest.info.Author.newBuilder()
                        .setName(String.join(", ", captured.document().metadata().authors())).build());
            }
            var info = Info.newBuilder().setProperties(properties)
                    .setSettings(Settings.newBuilder().setDefaultTexture(defaultTexture).build())
                    .setMetadata(metadata.build()).build();
            var strings = writer.addProtoBlob(StringData.newBuilder().build(), 16);
            var manifest = Manifest.newBuilder().setInfo(info)
                    .setCommonAssets(Common.newBuilder().setStringsBlobId(strings).build())
                    .addRenderTargets(target.build()).build();
            writer.setManifest(manifest);
            var sourceArchive = serializeSource(captured.document().files());
            try (var source = com.elfmcys.ysm.buffer.ArrayBuffer.borrow(sourceArchive)) {
                writer.setCpmSource(source, 16);
            }
            writer.write(output);
        }
        return new RawCompileResult(captured.modelHash(), staged);
    }

    private static String addAtlasTexture(ModelFileWriter writer, RenderTarget.Builder target,
                                          CpmTextureAtlas.Result atlas) throws IOException {
        try (var data = com.elfmcys.ysm.buffer.ArrayBuffer.borrow(atlas.png());
             var image = Image.probe(data)) {
            int id = writer.addImageBlob(image);
            target.putTextures("cpm_atlas", PBRTextureSet.newBuilder().setUv(
                    com.elfmcys.ysm.proto.mixel.common.Image.newBuilder().setBlobId(id)
                            .setFormat(image.format().name()).setWidth(image.width())
                            .setHeight(image.height()).setFrameCount(1).build()).build());
            return "cpm_atlas";
        } catch (RuntimeException invalidImage) {
            throw new IOException("Invalid CPM texture atlas", invalidImage);
        }
    }

    private static String addTextures(ModelFileWriter writer, RenderTarget.Builder target,
                                    CpmDocument document) throws IOException {
        var candidates = new LinkedHashMap<String, ByteData>();
        document.files().entrySet().stream()
                .filter(e -> IMAGE_EXTENSIONS.stream().anyMatch(e.getKey().toLowerCase(Locale.ROOT)::endsWith))
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> {
                    String key = stem(e.getKey());
                    candidates.putIfAbsent(key, e.getValue());
                    // CustomModel's legacy texture symbols are often
                    // namespace-like (tex.foo) while the file is foo.png.
                    int slash = key.lastIndexOf('/');
                    String leaf = slash < 0 ? key : key.substring(slash + 1);
                    int dot = leaf.lastIndexOf('.');
                    if (dot > 0) candidates.putIfAbsent(leaf.substring(dot + 1), e.getValue());
                });
        for (var entry : candidates.entrySet()) {
            try (var data = com.elfmcys.ysm.buffer.ArrayBuffer.borrow(entry.getValue().copy());
                 var image = Image.probe(data)) {
                int id = writer.addImageBlob(image);
                target.putTextures(entry.getKey(), PBRTextureSet.newBuilder().setUv(
                        com.elfmcys.ysm.proto.mixel.common.Image.newBuilder().setBlobId(id)
                                .setFormat(image.format().name()).setWidth(image.width())
                                .setHeight(image.height()).setFrameCount(1).build()).build());
            } catch (RuntimeException invalidImage) {
                throw new IOException("Invalid CPM texture: " + entry.getKey(), invalidImage);
            }
        }
        if (candidates.isEmpty()) {
            // The YSM player schema requires at least one texture descriptor.  A
            // transparent fallback keeps accessory/host-texture CPM packs loadable;
            // the runtime can still replace it through the normal texture selector.
            try (var data = com.elfmcys.ysm.buffer.ArrayBuffer.borrow(java.util.Base64.getDecoder().decode(
                    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="));
                 var image = Image.probe(data)) {
                int id = writer.addImageBlob(image);
                target.putTextures("cpm_placeholder", PBRTextureSet.newBuilder().setUv(
                        com.elfmcys.ysm.proto.mixel.common.Image.newBuilder().setBlobId(id)
                                .setFormat(image.format().name()).setWidth(image.width())
                                .setHeight(image.height()).setFrameCount(1).build()).build());
            }
            return "cpm_placeholder";
        }
        return candidates.keySet().stream().findFirst().orElse("");
    }

    private static String stem(String path) {
        var normalized = path.replace('\\', '/');
        var dot = normalized.lastIndexOf('.');
        return dot <= 0 ? normalized : normalized.substring(0, dot);
    }

    private static Map<String, ByteData> readFiles(Path root) throws IOException {
        var result = new LinkedHashMap<String, ByteData>();
        try (var paths = Files.walk(root)) {
            for (var path : paths.filter(Files::isRegularFile).sorted().toList()) {
                var relative = root.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize())
                        .toString().replace('\\', '/');
                result.put(relative, new ByteData(Files.readAllBytes(path)));
            }
        }
        // Some exported collections keep the pack name as an extra directory
        // (for example 12-Rubik/'s Cube/model.json). Treat the unique nested
        // model.json as the package root, matching the archive importer.
        if (!result.containsKey("model.json")) {
            var candidates = result.keySet().stream()
                    .filter(path -> path.equalsIgnoreCase("model.json") || path.toLowerCase(Locale.ROOT).endsWith("/model.json"))
                    .toList();
            if (candidates.size() == 1) {
                String model = candidates.get(0);
                String prefix = model.substring(0, model.length() - "model.json".length());
                var normalized = new LinkedHashMap<String, ByteData>();
                result.forEach((path, bytes) -> normalized.put(path.startsWith(prefix) ? path.substring(prefix.length()) : path, bytes));
                result = normalized;
            }
        }
        return result;
    }

    private static Map<String, ByteData> readArchive(Path archive) throws IOException {
        var result = new LinkedHashMap<String, ByteData>();
        try (var zip = new java.util.zip.ZipFile(archive.toFile())) {
            var entries = zip.stream().filter(e -> !e.isDirectory()).sorted(
                    Comparator.comparing(java.util.zip.ZipEntry::getName)).toList();
            var prefix = entries.stream().map(java.util.zip.ZipEntry::getName)
                    .map(name -> name.replace('\\', '/'))
                    .filter(name -> name.equalsIgnoreCase("model.json") || name.toLowerCase(Locale.ROOT).endsWith("/model.json"))
                    .findFirst().map(name -> {
                        var slash = name.lastIndexOf('/');
                        return slash < 0 ? "" : name.substring(0, slash + 1);
                    }).orElse("");
            for (var entry : entries) {
                var path = entry.getName().replace('\\', '/');
                if (!prefix.isEmpty() && path.startsWith(prefix)) path = path.substring(prefix.length());
                while (path.startsWith("./")) path = path.substring(2);
                if (path.contains("../") || path.startsWith("/"))
                    throw new IOException("Invalid CPM archive path: " + path);
                try (var input = zip.getInputStream(entry)) {
                    result.put(path, new ByteData(readBounded(input, LIMITS.maxBytes())));
                }
            }
        }
        return result;
    }

    private static Map<String, ByteData> readArchiveBytes(byte[] archive) throws IOException {
        var result = new LinkedHashMap<String, ByteData>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                var path = entry.getName().replace('\\', '/');
                if (path.isBlank() || path.startsWith("/") || path.contains("../"))
                    throw new IOException("Invalid persisted CPM path: " + path);
                if (result.putIfAbsent(path, new ByteData(readBounded(zip, LIMITS.maxBytes()))) != null)
                    throw new IOException("Duplicate persisted CPM path: " + path);
            }
        }
        return result;
    }

    private static byte[] readBounded(InputStream input, int maximum) throws IOException {
        var output = new ByteArrayOutputStream();
        var buffer = new byte[8192];
        int total = 0;
        for (int count; (count = input.read(buffer)) >= 0;) {
            total = Math.addExact(total, count);
            if (total > maximum) throw new IOException("CPM archive entry exceeds source byte budget");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static byte[] serializeSource(Map<String, ByteData> files) throws IOException {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            files.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                try {
                    var item = new ZipEntry(entry.getKey());
                    item.setTime(0L);
                    zip.putNextEntry(item);
                    zip.write(entry.getValue().copy());
                    zip.closeEntry();
                } catch (IOException failure) {
                    throw new java.io.UncheckedIOException(failure);
                }
            });
        } catch (java.io.UncheckedIOException failure) {
            throw failure.getCause();
        }
        return output.toByteArray();
    }

    private static Hash256 hash(Map<String, ByteData> files) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            files.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                try {
                    out.writeUTF(entry.getKey());
                    out.writeInt(entry.getValue().size());
                    out.write(entry.getValue().copy());
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        }
        return ModelHashing.blake3(bytes.toByteArray());
    }

    public record Captured(Hash256 modelHash, CpmDocument document) {
        public Captured {
            Objects.requireNonNull(modelHash, "modelHash");
            Objects.requireNonNull(document, "document");
        }
    }
}
