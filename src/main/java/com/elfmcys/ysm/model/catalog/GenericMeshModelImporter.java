package com.elfmcys.ysm.model.catalog;

import cc.sirrus.ysmlib.YsmRuntime;
import cc.sirrus.ysmlib.scene.ByteData;
import cc.sirrus.ysmlib.scene.ScenePackage;
import cc.sirrus.ysmlib.scene.ScenePackageAssets;
import cc.sirrus.ysmlib.scene.SceneAsset;
import cc.sirrus.ysmlib.scene.MeshAsset;
import cc.sirrus.ysmlib.scene.fbx.FbxEvaluation;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import cc.sirrus.ysmlib.scene.mmd.PmdDocument;
import cc.sirrus.ysmlib.scene.mmd.PmxDocument;
import com.elfmcys.ysm.format.parser.RawCompileResult;
import com.elfmcys.ysm.format.schema.model.ModelFileWriter;
import com.elfmcys.ysm.format.schema.model.ModelSchema;
import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.storage.ModelHashing;
import com.elfmcys.ysm.proto.mixel.manifest.Manifest;
import com.elfmcys.ysm.proto.mixel.manifest.asset.Common;
import com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTarget;
import com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind;
import com.elfmcys.ysm.proto.mixel.manifest.info.Info;
import com.elfmcys.ysm.proto.mixel.manifest.info.License;
import com.elfmcys.ysm.proto.mixel.manifest.info.Metadata;
import com.elfmcys.ysm.proto.mixel.manifest.info.ModelSettings;
import com.elfmcys.ysm.proto.mixel.manifest.info.ModelStats;
import com.elfmcys.ysm.proto.mixel.manifest.info.Properties;
import com.elfmcys.ysm.proto.mixel.manifest.info.Settings;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.zip.GZIPInputStream;

/**
 * Converts user supplied portable scene files into the existing general-mesh
 * model container.  The converter is deliberately independent from the
 * catalog and can therefore be used by the cache worker without touching a
 * Minecraft render thread.
 */
public final class GenericMeshModelImporter {
    public static final ReadLimits LIMITS = new ReadLimits(
            1_000_000_000, 100_000_000, 4 * 1024 * 1024);
    private static final long MAX_SOURCE_BYTES = 1_000_000_000L;
    private static final long MAX_PACKAGE_ENTRIES = 100_000L;
    private static final List<String> IMAGE_EXTENSIONS = List.of(
            ".png", ".jpg", ".jpeg", ".bmp", ".tga", ".webp");

    public Captured capture(Path source) throws IOException {
        Objects.requireNonNull(source, "source");
        var name = source.getFileName().toString().toLowerCase(Locale.ROOT);
        var hash = ModelHashing.blake3(source);
        ByteData profile=null;
        var profilePath=source.resolveSibling(source.getFileName()+cc.sirrus.ysmlib.scene.SceneModelProfile.SUFFIX);
        if(Files.exists(profilePath)) {
            if(Files.size(profilePath)>1024*1024) throw new IOException("Model profile exceeds 1 MiB: "+profilePath);
            var bytes=Files.readAllBytes(profilePath);profile=new ByteData(bytes);YsmRuntime.scenes().readModelProfile(profile);
            hash=ModelHashing.blake3((hash.toString()+":"+ModelHashing.blake3(bytes)).getBytes(StandardCharsets.UTF_8));
        }
        if (name.endsWith(".unitypackage")) {
            // The package hash covers every serialized Unity asset and makes
            // the conversion cache invalidate when any referenced texture or
            // prefab changes.
            return new Captured(hash, source, true, null,profile);
        }
        return new Captured(hash, source, false, null,profile);
    }

    public RawCompileResult convert(Captured captured, Path outputDirectory)
            throws IOException {
        Objects.requireNonNull(captured, "captured");
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        boolean sceneFile=extension(captured.source()).equals(".yscene");
        var bundle = sceneFile ? null : captured.bundle() == null
                ? readBundle(captured.source(), captured.unityPackage())
                : captured.bundle();
        var packageSource = sceneFile ? YsmRuntime.scenes().readPackage(new ByteData(readBytes(captured.source())),LIMITS) : buildPackage(bundle);
        if(captured.profile()!=null) {
            var files=new LinkedHashMap<>(packageSource.files());files.put(cc.sirrus.ysmlib.scene.SceneModelProfile.PACKAGE_PATH,captured.profile());
            if(!sceneFile)for(var author:YsmRuntime.scenes().readModelProfile(captured.profile()).metadata().authors())if(!author.avatar().isBlank()&&!files.containsKey(author.avatar())) {
                var root=captured.source().getParent().toRealPath();var image=root.resolve(author.avatar()).normalize();
                if(!image.startsWith(root)||!image.toRealPath().startsWith(root)||Files.size(image)>16*1024*1024)throw new IOException("Invalid author avatar path/budget: "+author.avatar());
                files.put(author.avatar(),new ByteData(Files.readAllBytes(image)));
            }
            packageSource=new ScenePackage(packageSource.model(),packageSource.settings(),packageSource.animations(),packageSource.relocations(),files);
        }
        var assets = YsmRuntime.scenes().loadPackage(packageSource, LIMITS);
        var stats = sceneStats(assets);
        var encoded = YsmRuntime.scenes().writePackage(packageSource, LIMITS);
        Files.createDirectories(outputDirectory);
        var staged = outputDirectory.resolve("generic-mesh.mxc");
        var displayName = displayName(captured.source(), packageSource.model().path());
        try (var writer = new ModelFileWriter(ModelSchema.GENERAL_MESH);
             var data = ArrayBuffer.borrow(encoded.copy());
             var output = Files.newByteChannel(staged, StandardOpenOption.CREATE,
                     StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            writer.addBlob(data, 0);
            writer.setManifest(manifest(captured.modelHash(), displayName, stats, assets,writer));
            writer.write(output);
        }
        return new RawCompileResult(captured.modelHash(), staged);
    }

    /** A cheap source identity used before any scene parser is opened. */
    public record Captured(Hash256 modelHash, Path source, boolean unityPackage,
                           Bundle bundle,ByteData profile) {
        public Captured {
            Objects.requireNonNull(modelHash, "modelHash");
            source = Objects.requireNonNull(source, "source").toAbsolutePath().normalize();
        }
    }

    /** Package files are virtual paths; they never grant access to host paths. */
    public record Bundle(String modelPath, ScenePackage.Format format,
                         Map<String, byte[]> files,
                         List<ScenePackage.Source> animations,
                         List<ScenePackage.Relocation> relocations) {
        public Bundle {
            modelPath = ScenePackage.checkedPath(modelPath);
            Objects.requireNonNull(format, "format");
            files = copyFiles(files);
            animations = List.copyOf(animations);
            relocations = List.copyOf(relocations);
        }

        private static Map<String, byte[]> copyFiles(Map<String, byte[]> values) {
            var copy = new LinkedHashMap<String, byte[]>();
            values.forEach((path, bytes) -> copy.put(ScenePackage.checkedPath(path),
                    Objects.requireNonNull(bytes, "bytes").clone()));
            return Map.copyOf(copy);
        }
    }

    private static ScenePackage buildPackage(Bundle bundle) throws IOException {
        var files = new LinkedHashMap<String, ByteData>();
        bundle.files().forEach((path, bytes) -> files.put(path, new ByteData(bytes)));
        return new ScenePackage(
                new ScenePackage.Source("model", bundle.modelPath(), bundle.format()),
                new ScenePackage.Settings(sourceMetersPerUnit(bundle.format()), -1, FbxEvaluation.SkinSpace.BIND_WORLD),
                bundle.animations(), bundle.relocations(), files);
    }

    /** MMD coordinates are conventionally decimeters/PMX units; 12.5 source
     * units correspond to one host metre in the established YSM scene contract.
     * Unity/glTF/FBX packages retain their authored metre scale. */
    private static double sourceMetersPerUnit(ScenePackage.Format format) {
        return format == ScenePackage.Format.PMX || format == ScenePackage.Format.PMD ? .08 : 1.0;
    }

    private static int distinctPmxTextures(PmxDocument model) {
        var used = new java.util.HashSet<String>();
        for (var material : model.materials()) {
            addTexture(model.textures(), used, material.texture());
            addTexture(model.textures(), used, material.sphereTexture());
            if (!material.sharedToon()) addTexture(model.textures(), used, material.toonTexture());
        }
        return used.size();
    }

    private static int distinctPmdTextures(PmdDocument model) {
        var used = new java.util.HashSet<String>();
        for (var material : model.materials()) {
            for (var reference : material.textureNames().split("\\*", -1)) {
                if (!reference.isBlank()) used.add(reference);
            }
        }
        for (var reference : model.toonTextures()) if (!reference.isBlank()) used.add(reference);
        return used.size();
    }

    private static void addTexture(List<String> textures, java.util.Set<String> used, int index) {
        if (index >= 0 && index < textures.size()) {
            var reference = textures.get(index);
            if (!reference.isBlank()) used.add(reference);
        }
    }

    private static ModelStats sceneStats(ScenePackageAssets assets) {
        var model = assets.model();
        if (model instanceof ScenePackageAssets.Pmx pmx) {
            var doc = pmx.value();
            return stats(doc.bones().size(), 1, doc.indices().size() / 3, doc.materials().size(), distinctPmxTextures(doc));
        }
        if (model instanceof ScenePackageAssets.Pmd pmd) {
            var doc = pmd.value();
            return stats(doc.bones().size(), 1, doc.indices().size() / 3, doc.materials().size(), distinctPmdTextures(doc));
        }
        if (model instanceof ScenePackageAssets.Gltf gltf) {
            return sceneStats(gltf.value().scene());
        }
        if (model instanceof ScenePackageAssets.Vrm vrm) {
            return sceneStats(vrm.value().scene());
        }
        if (model instanceof ScenePackageAssets.Fbx fbx) {
            var document = fbx.value();
            var faces = 0L;
            for (var mesh : document.meshes()) {
                for (var face : mesh.faces()) faces += face.triangles().size() / 3L;
            }
            var bones = document.skins().stream().mapToLong(skin -> skin.clusters().size()).sum();
            return stats(bones, document.meshes().size(), faces,
                    0, document.textures().size());
        }
        return ModelStats.newBuilder().build();
    }

    private static ModelStats sceneStats(SceneAsset scene) {
        var faces = 0L;
        for (var mesh : scene.meshes()) {
            for (var primitive : mesh.primitives()) {
                faces += triangleCount(primitive);
            }
        }
        var bones = scene.skins().stream().mapToLong(skin -> skin.joints().size()).sum();
        return stats(bones, scene.meshes().size(), faces,
                scene.materials().size(), scene.images().size());
    }

    private static int triangleCount(MeshAsset.Primitive primitive) {
        var count = primitive.indices().size();
        return switch (primitive.topology()) {
            case TRIANGLES -> count / 3;
            case TRIANGLE_STRIP, TRIANGLE_FAN -> Math.max(0, count - 2);
            default -> 0;
        };
    }

    private static ModelStats stats(long bones, long meshes, long faces,
                                    long materials, long textures) {
        return ModelStats.newBuilder()
                .setBones(safeCount(bones)).setCubes(0).setFaces(safeCount(faces))
                .setMeshes(safeCount(meshes)).setMaterials(safeCount(materials))
                .setTextures(safeCount(textures)).build();
    }

    private static int safeCount(long value) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, value));
    }

    private static Manifest manifest(Hash256 modelHash, String name, ModelStats stats, ScenePackageAssets assets,ModelFileWriter writer) throws IOException {
        var settings = Settings.newBuilder().setDefaultTexture("")
                .setPreviewAnimation("").setDisablePreviewRotation(false);
        for (var animation : YsmRuntime.scenes().animations(assets, 30)) {
            for (boolean loop : new boolean[]{false, true}) settings.addExtraAnimation(
                    com.elfmcys.ysm.proto.mixel.common.StringPair.newBuilder()
                        .setKey(com.elfmcys.ysm.model.domain.SceneActionId.of(animation, loop))
                        .setValue(animation.name().isBlank() ? animation.sourcePath() : animation.name()).build());
        }
        var properties = Properties.newBuilder()
                .setModelId(ByteBuffer.wrap(modelHash.bytes()))
                .setFree(false).setOriginVer("generic-mesh").build();
        var info = Info.newBuilder()
                .setSettings(settings.build())
                .setProperties(properties)
                .setMetadata(profileMetadata(assets,name,writer))
                .build();
        var target = RenderTarget.newBuilder().setTargetId("player")
                .setKind(RenderTargetKind.RENDER_TARGET_KIND_PLAYER).setBlobId(1)
                .setSettings(ModelSettings.newBuilder().setHeightScale(1)
                        .setWidthScale(1).setRenderLayersFirst(false)
                        .setForceCulling(false).setGuiNoLighting(false)
                        .setMergeMultilineExpr(false).build())
                .setStats(stats)
                .build();
        return Manifest.newBuilder().setCommonAssets(Common.newBuilder()
                        .setStringsBlobId(0).build())
                .setInfo(info).addRenderTargets(target).build();
    }

    private static Metadata profileMetadata(ScenePackageAssets assets,String fallback,ModelFileWriter writer) throws IOException {
        var bytes=assets.source().files().get(cc.sirrus.ysmlib.scene.SceneModelProfile.PACKAGE_PATH);
        var metadata=bytes==null?cc.sirrus.ysmlib.scene.SceneModelProfile.Metadata.empty():YsmRuntime.scenes().readModelProfile(bytes).metadata();
        var result=Metadata.newBuilder().setName(metadata.name().isBlank()?fallback:metadata.name()).setTips(metadata.tips())
            .setLicense(License.newBuilder().setType(metadata.license().type()).setDesc(metadata.license().desc()).build());
        for(var pair:metadata.links())result.addLinks(com.elfmcys.ysm.proto.mixel.common.StringPair.newBuilder().setKey(pair.key()).setValue(pair.value()).build());
        for(var author:metadata.authors()) {
            var entry=com.elfmcys.ysm.proto.mixel.manifest.info.Author.newBuilder().setName(author.name()).setRole(author.role()).setComment(author.comment());
            for(var pair:author.contacts())entry.addContacts(com.elfmcys.ysm.proto.mixel.common.StringPair.newBuilder().setKey(pair.key()).setValue(pair.value()).build());
            if(!author.avatar().isEmpty()) {
                var image=assets.source().files().get(author.avatar());if(image==null)throw new IOException("Missing author avatar: "+author.avatar());
                // Use the same image format/probe and blob metadata as ordinary YSM author avatars.
                try(var data=ArrayBuffer.borrow(image.copy());var decoded=com.elfmcys.ysm.natives.image.Image.probe(data)){
                    if((long)decoded.width()*decoded.height()>4_000_000)throw new IOException("Author avatar exceeds 4 million pixels: "+author.avatar());
                    int id=writer.addImageBlob(decoded);entry.setAvatar(com.elfmcys.ysm.proto.mixel.common.Image.newBuilder()
                    .setBlobId(id).setFormat(decoded.format().name()).setWidth(decoded.width()).setHeight(decoded.height()).setFrameCount(1).build());}
            }
            result.addAuthors(entry.build());
        }
        return result.build();
    }

    private static Bundle readBundle(Path source, boolean unityPackage)
            throws IOException {
        if (unityPackage) return readUnityPackage(source);
        var extension = extension(source);
        var format = switch (extension) {
            case ".pmx" -> ScenePackage.Format.PMX;
            case ".pmd" -> ScenePackage.Format.PMD;
            case ".vrm" -> ScenePackage.Format.VRM;
            case ".gltf", ".glb" -> ScenePackage.Format.GLTF;
            case ".fbx" -> ScenePackage.Format.FBX;
            default -> throw new IOException("Unsupported generic mesh source: " + source);
        };
        var root = source.getParent() == null ? Path.of(".") : source.getParent();
        var modelPath = source.getFileName().toString();
        var files = new LinkedHashMap<String, byte[]>();
        var relocations = new ArrayList<ScenePackage.Relocation>();
        files.put(modelPath, readBytes(source));
        if (format == ScenePackage.Format.FBX) {
            addFbxDependencies(source, root, modelPath, files, relocations);
        } else {
            addSiblingFiles(root, source, files);
            if (format == ScenePackage.Format.PMX) {
                addMmdDependencies(modelPath, YsmRuntime.scenes().readPmx(
                        new ByteData(files.get(modelPath)), LIMITS).textures(), files, relocations);
            } else if (format == ScenePackage.Format.PMD) {
                var document = YsmRuntime.scenes().readPmd(
                        new ByteData(files.get(modelPath)), LIMITS);
                var references = new ArrayList<String>();
                for (var material : document.materials()) {
                    for (var value : material.textureNames().split("\\*", -1))
                        if (!value.isEmpty()) references.add(value);
                }
                references.addAll(document.toonTextures());
                addMmdDependencies(modelPath, references, files, relocations);
            }
        }
        var animations = new ArrayList<ScenePackage.Source>();
        if (format == ScenePackage.Format.PMX || format == ScenePackage.Format.PMD) {
            addMmdAnimations(root, modelPath, files, animations);
        } else if (format == ScenePackage.Format.VRM) {
            addVrmaAnimations(root, files, animations);
        }
        return new Bundle(modelPath, format, files, animations, relocations);
    }

    private static void addFbxDependencies(Path model, Path root, String owner,
                                            Map<String, byte[]> files,
                                            List<ScenePackage.Relocation> relocations)
            throws IOException {
        var document = YsmRuntime.scenes().readFbx(
                new ByteData(files.get(owner)), LIMITS);
        List<Path> candidates;
        try (var paths = Files.walk(root)) {
            candidates = paths.filter(Files::isRegularFile).toList();
        }
        for (var texture : document.textures()) {
            if (texture.type() != 0) continue;
            var reference = texture.relativePath().isEmpty()
                    ? texture.absolutePath() : texture.relativePath();
            if (reference.isEmpty()) continue;
            var name = Path.of(reference.replace('\\', '/')).getFileName().toString();
            var found = candidates.stream()
                    .filter(path -> path.getFileName().toString().equalsIgnoreCase(name))
                    .findFirst().orElse(null);
            if (found == null) continue;
            var target = root.relativize(found).toString().replace('\\', '/');
            files.putIfAbsent(target, readBytes(found));
            relocations.add(new ScenePackage.Relocation(owner, reference, target));
        }
    }

    private static void addSiblingFiles(Path root, Path model,
                                        Map<String, byte[]> files) throws IOException {
        try (var paths = Files.walk(root)) {
            for (var path : paths.filter(Files::isRegularFile).toList()) {
                if (path.equals(model)) continue;
                var extension = extension(path);
                if (!IMAGE_EXTENSIONS.contains(extension) &&
                        !List.of(".bin", ".vmd", ".vpd", ".vrma", ".gltf", ".glb")
                                .contains(extension)) continue;
                var relative = root.relativize(path).toString().replace('\\', '/');
                files.putIfAbsent(relative, readBytes(path));
            }
        }
    }

    /**
     * MMD files frequently store a DCC subdirectory in the texture reference
     * while the shipped texture is beside the PMX/PMD. Keep the source path
     * intact and add an explicit package relocation to the actual packaged
     * file instead of making the resolver guess at runtime.
     */
    private static void addMmdDependencies(String owner, List<String> references,
                                           Map<String, byte[]> files,
                                           List<ScenePackage.Relocation> relocations) {
        var mapped = new java.util.HashSet<String>();
        for (var reference : references) {
            if (reference == null || reference.isBlank() || !mapped.add(reference)) continue;
            var normalized = reference.replace('\\', '/');
            if (files.containsKey(normalized)) continue;
            var name = Path.of(normalized).getFileName().toString();
            var target = files.keySet().stream()
                    .filter(path -> Path.of(path).getFileName().toString().equalsIgnoreCase(name))
                    .findFirst().orElse(null);
            if (target != null) relocations.add(new ScenePackage.Relocation(owner, reference, target));
        }
    }

    private static void addMmdAnimations(Path root, String modelPath,
                                         Map<String, byte[]> files,
                                         List<ScenePackage.Source> animations) {
        files.keySet().stream().sorted().forEach(path -> {
            var extension = extension(Path.of(path));
            if (extension.equals(".vmd") || extension.equals(".vpd")) {
                var format = extension.equals(".vmd")
                        ? ScenePackage.Format.VMD : ScenePackage.Format.VPD;
                animations.add(new ScenePackage.Source("motion-" + animations.size(), path, format));
            }
        });
    }

    private static void addVrmaAnimations(Path root, Map<String, byte[]> files,
                                          List<ScenePackage.Source> animations) {
        files.keySet().stream().sorted().forEach(path -> {
            if (extension(Path.of(path)).equals(".vrma")) {
                animations.add(new ScenePackage.Source("motion-" + animations.size(), path,
                        ScenePackage.Format.VRMA));
            }
        });
    }

    private static Bundle readUnityPackage(Path source) throws IOException {
        var entries = readUnityEntries(source);
        var models = entries.entrySet().stream()
                .filter(entry -> extension(Path.of(entry.getKey())).equals(".fbx"))
                .sorted(Comparator.<Map.Entry<String, byte[]>>comparingLong(
                        entry -> -entry.getValue().length).thenComparing(Map.Entry::getKey))
                .toList();
        if (models.isEmpty()) {
            throw new IOException("Unity package contains no FBX model: " + source.getFileName());
        }
        var model = models.get(0);
        var modelPath = model.getKey();
        var files = new LinkedHashMap<String, byte[]>();
        files.put(modelPath, model.getValue());
        var relocations = new ArrayList<ScenePackage.Relocation>();
        var document = YsmRuntime.scenes().readFbx(new ByteData(model.getValue()), LIMITS);
        for (var texture : document.textures()) {
            if (texture.type() != 0) continue;
            var reference = texture.relativePath().isEmpty()
                    ? texture.absolutePath() : texture.relativePath();
            var name = reference.isEmpty() ? "" : Path.of(reference.replace('\\', '/'))
                    .getFileName().toString();
            var found = entries.entrySet().stream()
                    .filter(entry -> extension(Path.of(entry.getKey()))
                            .equals(extension(Path.of(name))))
                    .filter(entry -> Path.of(entry.getKey()).getFileName().toString()
                            .equalsIgnoreCase(name)).findFirst().orElse(null);
            if (found != null) {
                files.putIfAbsent(found.getKey(), found.getValue());
                relocations.add(new ScenePackage.Relocation(modelPath, reference, found.getKey()));
            }
        }
        return new Bundle(modelPath, ScenePackage.Format.FBX, files, List.of(), relocations);
    }

    /** Reads only pathname and asset records from Unity's gzip/tar layout. */
    private static Map<String, byte[]> readUnityEntries(Path source) throws IOException {
        var output = new LinkedHashMap<String, byte[]>();
        var pending = new HashMap<String, byte[]>();
        var names = new HashMap<String, String>();
        long entries = 0;
        long total = 0;
        try (InputStream raw = Files.newInputStream(source);
             var gzip = new GZIPInputStream(raw, 64 * 1024)) {
            byte[] header = new byte[512];
            while (true) {
                readFullyOrEof(gzip, header);
                if (isZeroBlock(header)) break;
                entries++;
                if (entries > MAX_PACKAGE_ENTRIES) throw new IOException("Unity package has too many entries");
                var name = tarString(header, 0, 100);
                var size = tarOctal(header, 124, 12);
                if (size < 0 || size > MAX_SOURCE_BYTES || total > MAX_SOURCE_BYTES - size)
                    throw new IOException("Unity package exceeds import budget");
                var bytes = readEntry(gzip, size);
                total += size;
                var slash = name.lastIndexOf('/');
                if (slash < 1) continue;
                var folder = name.substring(0, slash);
                var leaf = name.substring(slash + 1);
                if (leaf.equals("pathname")) {
                    var path = new String(bytes, StandardCharsets.UTF_8).trim()
                            .replace('\\', '/');
                    if (!path.isEmpty() && !path.startsWith("/") && !path.contains("..")) {
                        names.put(folder, path);
                        var asset = pending.remove(folder);
                        if (asset != null && wantedUnityAsset(path)) output.put(path, asset);
                    }
                } else if (leaf.equals("asset")) {
                    var path = names.get(folder);
                    if (path != null && wantedUnityAsset(path)) output.put(path, bytes);
                    else if (path == null) pending.put(folder, bytes);
                }
            }
        }
        return output;
    }

    private static boolean wantedUnityAsset(String path) {
        var extension = extension(Path.of(path));
        return extension.equals(".fbx") || IMAGE_EXTENSIONS.contains(extension);
    }

    private static byte[] readBytes(Path path) throws IOException {
        var size = Files.size(path);
        if (size < 0 || size > MAX_SOURCE_BYTES) throw new IOException("Source exceeds import budget: " + path);
        return Files.readAllBytes(path);
    }

    private static byte[] readEntry(InputStream input, long size) throws IOException {
        if (size > Integer.MAX_VALUE) throw new IOException("Unity asset is too large");
        var bytes = input.readNBytes(Math.toIntExact(size));
        if (bytes.length != size) throw new IOException("Truncated Unity package entry");
        var padding = (512 - (size & 511)) & 511;
        if (padding > 0 && input.readNBytes(Math.toIntExact(padding)).length != padding)
            throw new IOException("Truncated Unity package padding");
        return bytes;
    }

    private static void readFullyOrEof(InputStream input, byte[] target) throws IOException {
        var offset = 0;
        while (offset < target.length) {
            var count = input.read(target, offset, target.length - offset);
            if (count < 0) {
                if (offset == 0) return;
                throw new IOException("Truncated Unity package header");
            }
            offset += count;
        }
    }

    private static boolean isZeroBlock(byte[] bytes) {
        for (var value : bytes) if (value != 0) return false;
        return true;
    }

    private static String tarString(byte[] bytes, int offset, int length) {
        var end = offset;
        while (end < offset + length && bytes[end] != 0) end++;
        return new String(bytes, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static long tarOctal(byte[] bytes, int offset, int length) {
        long value = 0;
        var index = offset;
        while (index < offset + length && (bytes[index] == 0 || bytes[index] == ' ')) index++;
        while (index < offset + length && bytes[index] >= '0' && bytes[index] <= '7')
            value = (value << 3) + (bytes[index++] - '0');
        return value;
    }

    private static String extension(Path path) {
        var name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        var dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot);
    }

    private static String displayName(Path source, String modelPath) {
        var name = Path.of(modelPath).getFileName().toString();
        var dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : source.getFileName().toString();
    }
}
