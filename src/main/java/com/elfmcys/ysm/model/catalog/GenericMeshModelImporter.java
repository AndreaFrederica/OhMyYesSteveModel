package com.elfmcys.ysm.model.catalog;

import cc.sirrus.ysmlib.YsmRuntime;
import cc.sirrus.ysmlib.scene.ByteData;
import cc.sirrus.ysmlib.scene.ScenePackage;
import cc.sirrus.ysmlib.scene.ScenePackageAssets;
import cc.sirrus.ysmlib.scene.SceneAsset;
import cc.sirrus.ysmlib.scene.MeshAsset;
import cc.sirrus.ysmlib.scene.fbx.FbxEvaluation;
import cc.sirrus.ysmlib.scene.fbx.FbxMappingProfile;
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
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

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
    private static final int MAX_RESOURCE_MAPPING_BYTES = 256 * 1024;
    private static final int RESOURCE_MAPPING_SCHEMA_VERSION = 1;
    private static final String FBX_RESOURCE_MAPPING_SUFFIX = ".resources.json";
    private static final String FBX_RESOURCE_MAPPING_PACKAGE_PATH = "_omysm/fbx-resources.json";
    private static final List<String> IMAGE_EXTENSIONS = List.of(
            ".png", ".jpg", ".jpeg", ".bmp", ".tga", ".webp", ".spa", ".sph", ".dds");

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
        ByteData fbxMapping=null;
        FbxResourceSidecar fbxResources=null;
        if(name.endsWith(".fbx") || name.endsWith(".unitypackage") || name.endsWith(".zip")) {
            var mappingPath=source.resolveSibling(source.getFileName()+cc.sirrus.ysmlib.scene.fbx.FbxMappingProfile.SUFFIX);
            if((name.endsWith(".fbx") || name.endsWith(".unitypackage") || name.endsWith(".zip")) && Files.exists(mappingPath)) {
                if(Files.size(mappingPath)>256*1024) throw new IOException("FBX mapping profile exceeds 256 KiB: "+mappingPath);
                fbxMapping=new ByteData(Files.readAllBytes(mappingPath));
                YsmRuntime.scenes().readFbxMapping(fbxMapping);
                hash=ModelHashing.blake3((hash.toString()+":fbxmap:"+ModelHashing.blake3(fbxMapping.copy())).getBytes(StandardCharsets.UTF_8));
            }
            fbxResources=readFbxResourceSidecar(source,name.endsWith(".unitypackage") || name.endsWith(".zip"));
            if(fbxResources!=null)
                hash=ModelHashing.blake3((hash.toString()+":fbxresources:"+ModelHashing.blake3(fbxResources.bytes().copy())).getBytes(StandardCharsets.UTF_8));
        }
        if (name.endsWith(".unitypackage") || name.endsWith(".zip")) {
            // Archive bytes are hashed as a whole, making the conversion cache
            // invalidate when any embedded model or referenced asset changes.
            return new Captured(hash, source, true, null,profile,fbxMapping,fbxResources);
        }
        if (!name.endsWith(".yscene")) {
            // Model identity must change when bundled textures or motions change.
            // Only selected sources reach capture; directory discovery uses metadata.
            var root = source.toAbsolutePath().normalize().getParent();
            var facts = new java.io.ByteArrayOutputStream();
            try (var output = new java.io.DataOutputStream(facts);
                 var paths = Files.walk(root)) {
                output.write(hash.bytes());
                for (var path : paths.filter(path -> !path.equals(source.toAbsolutePath().normalize()))
                        .filter(com.elfmcys.ysm.model.catalog.source.SourceStamp::isSceneDependency)
                        .filter(path -> Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                        .sorted().toList()) {
                    output.writeUTF(root.relativize(path).toString().replace('\\', '/'));
                    output.write(ModelHashing.blake3(path).bytes());
                }
            }
            hash = ModelHashing.blake3(facts.toByteArray());
        }
        return new Captured(hash, source, false, null,profile,fbxMapping,fbxResources);
    }

    public RawCompileResult convert(Captured captured, Path outputDirectory)
            throws IOException {
        Objects.requireNonNull(captured, "captured");
        Objects.requireNonNull(outputDirectory, "outputDirectory");
        boolean sceneFile=extension(captured.source()).equals(".yscene");
        var bundle = sceneFile ? null : captured.bundle() == null
                ? readBundle(captured.source(), captured.archiveSource(),
                        captured.fbxResources()==null ? Map.of() : captured.fbxResources().mappings())
                : captured.bundle();
        var packageSource = sceneFile ? YsmRuntime.scenes().readPackage(new ByteData(readBytes(captured.source())),LIMITS) : buildPackage(bundle);
        if(captured.profile()!=null || captured.fbxMapping()!=null || captured.fbxResources()!=null) {
            var files=new LinkedHashMap<>(packageSource.files());
            if(captured.profile()!=null) files.put(cc.sirrus.ysmlib.scene.SceneModelProfile.PACKAGE_PATH,captured.profile());
            if(captured.fbxMapping()!=null) {
                var mappingPath=cc.sirrus.ysmlib.scene.fbx.FbxMappingProfile.PACKAGE_PATH;
                var generated=files.get(mappingPath);
                files.put(mappingPath,generated==null?captured.fbxMapping():mergeFbxMappings(generated,captured.fbxMapping()));
            }
            if(captured.fbxResources()!=null) files.put(FBX_RESOURCE_MAPPING_PACKAGE_PATH,captured.fbxResources.bytes());
            if(captured.profile()!=null&&!sceneFile)for(var author:YsmRuntime.scenes().readModelProfile(captured.profile()).metadata().authors())if(!author.avatar().isBlank()&&!files.containsKey(author.avatar())) {
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
            writer.setManifest(manifest(captured.modelHash(), displayName, stats, assets, writer,
                    originVersion(captured.source(), captured.archiveSource())));
            writer.write(output);
        }
        return new RawCompileResult(captured.modelHash(), staged);
    }

    /** A cheap source identity used before any scene parser is opened. */
    public record Captured(Hash256 modelHash, Path source, boolean archiveSource,
                           Bundle bundle,ByteData profile,ByteData fbxMapping,
                           FbxResourceSidecar fbxResources) {
        public Captured(Hash256 modelHash,Path source,boolean archiveSource,Bundle bundle,ByteData profile) {
            this(modelHash,source,archiveSource,bundle,profile,null,null);
        }
        public Captured(Hash256 modelHash,Path source,boolean archiveSource,Bundle bundle,ByteData profile,ByteData fbxMapping) {
            this(modelHash,source,archiveSource,bundle,profile,fbxMapping,null);
        }
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

    /** Optional explicit FBX texture relocation table loaded from the source folder. */
    public record FbxResourceSidecar(ByteData bytes, Map<String,String> mappings) {
        public FbxResourceSidecar {
            Objects.requireNonNull(bytes, "bytes");
            mappings = Map.copyOf(mappings);
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

    private static Manifest manifest(Hash256 modelHash, String name, ModelStats stats, ScenePackageAssets assets,ModelFileWriter writer, String originVersion) throws IOException {
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
                .setFree(false).setOriginVer(originVersion).build();
        var info = Info.newBuilder()
                .setSettings(settings.build())
                .setProperties(properties)
                .setMetadata(profileMetadata(assets,name,writer));
        if(assets.model() instanceof ScenePackageAssets.Vrm avatar){
            var thumbnail=cc.sirrus.ysmlib.SceneProfiles.thumbnail(avatar.value());if(thumbnail.isPresent()){
                try(var data=ArrayBuffer.borrow(thumbnail.get().bytes().copy());var image=com.elfmcys.ysm.natives.image.Image.probe(data)){
                    try(var pixels=image.decodeToBuffer();
                        var thumbnailImage=com.elfmcys.ysm.natives.image.ImageEncoder.encodeLossy(pixels,image.width(),image.height(),156,270);
                        var icon=com.elfmcys.ysm.natives.image.ImageEncoder.encodeLossy(pixels,image.width(),image.height(),192,192)){
                        writer.setThumbnail(thumbnailImage);writer.setIcon(icon);
                    }
                    info.setThumbnailSource(com.elfmcys.ysm.proto.mixel.manifest.info.PreviewSource.PREVIEW_SOURCE_RAW).setIconSource(com.elfmcys.ysm.proto.mixel.manifest.info.PreviewSource.PREVIEW_SOURCE_RAW);
                }
            }
        }
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
                .setInfo(info.build()).addRenderTargets(target).build();
    }

    private static String originVersion(Path source, boolean archiveSource) {
        if (archiveSource) {
            var lower = source.getFileName().toString().toLowerCase(Locale.ROOT);
            return lower.endsWith(".zip") ? "workspace-zip" : "unitypackage";
        }
        var name = source.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".yscene")) return "scene-package";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1) : "generic-mesh";
    }

    private static ByteData mergeFbxMappings(ByteData generated,ByteData authored) {
        try {
            var base=JsonParser.parseString(new String(generated.copy(),StandardCharsets.UTF_8)).getAsJsonObject();
            var overlay=JsonParser.parseString(new String(authored.copy(),StandardCharsets.UTF_8)).getAsJsonObject();
            if(overlay.has("sourceHash")) base.add("sourceHash",overlay.get("sourceHash"));
            for(var key:List.of("skinByMeshElement","materialByElement")) if(overlay.has(key)&&overlay.get(key).isJsonObject()) {
                if(!base.has(key)||!base.get(key).isJsonObject()) base.add(key,new com.google.gson.JsonObject());
                for(var entry:overlay.getAsJsonObject(key).entrySet()) base.getAsJsonObject(key).add(entry.getKey(),entry.getValue());
            }
            return new ByteData(new com.google.gson.GsonBuilder().disableHtmlEscaping().create().toJson(base).getBytes(StandardCharsets.UTF_8));
        } catch(RuntimeException failure) { return authored; }
    }

    private static Metadata profileMetadata(ScenePackageAssets assets,String fallback,ModelFileWriter writer) throws IOException {
        var bytes=assets.source().files().get(cc.sirrus.ysmlib.scene.SceneModelProfile.PACKAGE_PATH);
        var metadata=bytes==null?(assets.model() instanceof ScenePackageAssets.Vrm v?cc.sirrus.ysmlib.SceneProfiles.metadata(v.value()):cc.sirrus.ysmlib.scene.SceneModelProfile.Metadata.empty()):YsmRuntime.scenes().readModelProfile(bytes).metadata();
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

    private static Bundle readBundle(Path source, boolean archiveSource,
                                     Map<String,String> packageResourceMappings)
            throws IOException {
        if (archiveSource) {
            var lower = source.getFileName().toString().toLowerCase(Locale.ROOT);
            return lower.endsWith(".unitypackage")
                    ? readUnityPackage(source, packageResourceMappings)
                    : readWorkspaceZip(source, packageResourceMappings);
        }
        var extension = extension(source);
        var format = switch (extension) {
            case ".pmx" -> ScenePackage.Format.PMX;
            case ".pmd" -> ScenePackage.Format.PMD;
            case ".vrm" -> ScenePackage.Format.VRM;
            case ".gltf", ".glb" -> ScenePackage.Format.GLTF;
            case ".fbx" -> ScenePackage.Format.FBX;
            default -> throw new IOException("Unsupported generic mesh source: " + source);
        };
        var root = source.toAbsolutePath().normalize().getParent();
        if(root==null) root=Path.of(".").toAbsolutePath().normalize();
        var modelPath = source.getFileName().toString();
        var files = new LinkedHashMap<String, byte[]>();
        var relocations = new ArrayList<ScenePackage.Relocation>();
        files.put(modelPath, readBytes(source));
        if (format == ScenePackage.Format.FBX) {
            var resources=readFbxResourceSidecar(source,false);
            addFbxDependencies(source, root, modelPath, files, relocations,
                    resources==null?Map.of():resources.mappings());
            if(resources!=null) files.put(FBX_RESOURCE_MAPPING_PACKAGE_PATH,resources.bytes().copy());
            var mapping=source.resolveSibling(source.getFileName()+cc.sirrus.ysmlib.scene.fbx.FbxMappingProfile.SUFFIX);
            if(Files.isRegularFile(mapping)) {
                var bytes=readBytes(mapping);
                YsmRuntime.scenes().readFbxMapping(new ByteData(bytes));
                files.put(cc.sirrus.ysmlib.scene.fbx.FbxMappingProfile.PACKAGE_PATH,bytes);
            }
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
                                            List<ScenePackage.Relocation> relocations,
                                            Map<String,String> explicitMappings)
            throws IOException {
        var document = YsmRuntime.scenes().readFbx(
                new ByteData(files.get(owner)), LIMITS);
        List<Path> candidates=null;
        // FBX exporters commonly emit the same texture connection several
        // times (one entry per material slot).  A ScenePackage relocation is
        // keyed by owner + source reference, so emitting every repeated
        // connection makes an otherwise valid FBX package fail with
        // "Duplicate package relocation" before geometry or materials load.
        var emittedReferences=new HashSet<String>();
        // Validate explicit targets up front. A present mapping is authoritative
        // for that source reference and must never silently fall back to a wrong
        // same-named file.
        for(var target:explicitMappings.values()) resolveFbxResourceTarget(root,target);
        for (var texture : document.textures()) {
            if (texture.type() != 0) continue;
            var reference = texture.relativePath().isEmpty()
                    ? texture.absolutePath() : texture.relativePath();
            if (reference.isEmpty()) continue;
            var normalizedReference = cleanTextureReference(reference);
            if (!emittedReferences.add(resourceKey(normalizedReference))) continue;
            var name = textureBasename(normalizedReference);
            var explicitTarget=explicitMappings.get(resourceKey(normalizedReference));
            if(explicitTarget!=null) {
                var found=resolveFbxResourceTarget(root,explicitTarget);
                var target=root.relativize(found).toString().replace('\\','/');
                files.putIfAbsent(target,readBytes(found));
                relocations.add(new ScenePackage.Relocation(owner,reference,target));
                continue;
            }
            if(candidates==null) try (var paths = Files.walk(root)) {
                candidates = paths.filter(Files::isRegularFile).toList();
            }
            // Prefer the FBX relative path exactly.  Falling back to a basename
            // is useful for exporters that write workstation absolute paths, but
            // silently picking the first duplicate texture produces a valid scene
            // with the wrong appearance, which is much harder to diagnose.
            var exact = candidates.stream()
                    .filter(path -> root.relativize(path).toString().replace('\\', '/').equalsIgnoreCase(normalizedReference))
                    .toList();
            var matches = exact.isEmpty() ? candidates.stream()
                    .filter(path -> path.getFileName().toString().trim().equalsIgnoreCase(name)).toList() : exact;
            if (matches.size() > 1) throw new IOException("Ambiguous FBX texture reference " + reference + ": " + matches);
            var found = matches.isEmpty() ? null : matches.get(0);
            if (found == null) continue;
            var target = root.relativize(found).toString().replace('\\', '/');
            files.putIfAbsent(target, readBytes(found));
            relocations.add(new ScenePackage.Relocation(owner, reference, target));
        }
    }

    private static FbxResourceSidecar readFbxResourceSidecar(Path source,
                                                             boolean packageTargets) throws IOException {
        var path=source.resolveSibling(source.getFileName()+FBX_RESOURCE_MAPPING_SUFFIX);
        if(!Files.isRegularFile(path)) return null;
        var bytes=readBytes(path);
        if(bytes.length>MAX_RESOURCE_MAPPING_BYTES)
            throw new IOException("FBX resource mapping exceeds 256 KiB: "+path);
        try {
            var root=JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();
            for(var key:root.keySet()) if(!key.equals("schemaVersion")&&!key.equals("resources"))
                throw new IOException("Unknown FBX resource mapping field: "+key);
            if(!root.has("schemaVersion")||root.get("schemaVersion").getAsInt()!=RESOURCE_MAPPING_SCHEMA_VERSION)
                throw new IOException("Unsupported FBX resource mapping schema: "+path);
            var resources=root.get("resources");
            if(resources==null||!resources.isJsonObject()) throw new IOException("FBX resource mapping requires an object named resources: "+path);
            var mappings=new LinkedHashMap<String,String>();
            for(var entry:resources.getAsJsonObject().entrySet()) {
                if(!entry.getValue().isJsonPrimitive()||!entry.getValue().getAsJsonPrimitive().isString())
                    throw new IOException("FBX resource mapping target must be a string: "+entry.getKey());
                var from=cleanTextureReference(entry.getKey());
                var target=cleanFbxResourceTarget(entry.getValue().getAsString());
                if(from.isBlank()||target.isBlank()) throw new IOException("FBX resource mapping paths must not be empty: "+path);
                var previous=mappings.put(resourceKey(from),target);
                if(previous!=null&&!previous.equals(target)) throw new IOException("Duplicate FBX resource mapping: "+entry.getKey());
            }
            if(!packageTargets) for(var target:mappings.values()) resolveFbxResourceTarget(source.getParent(),target);
            return new FbxResourceSidecar(new ByteData(bytes),mappings);
        } catch(JsonParseException|IllegalStateException|UnsupportedOperationException|NumberFormatException e) {
            throw new IOException("Invalid FBX resource mapping: "+path,e);
        }
    }

    private static String cleanFbxResourceTarget(String value) throws IOException {
        if(value==null) return "";
        var normalized=value.trim().replace('\\','/');
        if(normalized.isEmpty()||normalized.startsWith("/")||normalized.matches("^[A-Za-z]:.*")||normalized.indexOf('\0')>=0)
            throw new IOException("FBX resource mapping target must be relative: "+value);
        var parts=normalized.split("/");
        for(var part:parts) if(part.isEmpty()||part.equals(".")||part.equals(".."))
            throw new IOException("Invalid FBX resource mapping target: "+value);
        return normalized;
    }

    private static Path resolveFbxResourceTarget(Path root,String target) throws IOException {
        var normalized=cleanFbxResourceTarget(target);
        var rootAbsolute=root.toAbsolutePath().normalize();
        var resolved=rootAbsolute.resolve(normalized).normalize();
        if(!resolved.startsWith(rootAbsolute)||!Files.isRegularFile(resolved))
            throw new IOException("Missing FBX resource mapping target: "+target);
        return resolved;
    }

    private static String resourceKey(String value) {
        return cleanTextureReference(value).toLowerCase(Locale.ROOT);
    }

    private static String cleanTextureReference(String value) {
        if (value == null) return "";
        var s = value.trim();
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))))
            s = s.substring(1, s.length() - 1);
        int query = s.indexOf('?');
        if (query >= 0) s = s.substring(0, query);
        return s.replace('\\', '/');
    }

    private static String textureBasename(String value) {
        var s = cleanTextureReference(value);
        int slash = s.lastIndexOf('/');
        return slash >= 0 ? s.substring(slash + 1) : s;
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

    private static Bundle readUnityPackage(Path source,
                                           Map<String,String> explicitMappings) throws IOException {
        // UnityPackage is a gzip/tar stream. Keep the first pass metadata-only
        // and extract just the selected FBX and referenced images in later
        // passes. Holding every texture in the archive made large avatars
        // exceed the importer heap before FBX parsing even began.
        var index = readUnityIndex(source);
        var models = index.paths().entrySet().stream()
                .filter(entry -> extension(Path.of(entry.getValue())).equals(".fbx"))
                .sorted(Comparator.<Map.Entry<String, String>>comparingLong(
                        entry -> -index.assetSizes().getOrDefault(entry.getKey(), 0L))
                        .thenComparing(Map.Entry::getValue))
                .toList();
        if (models.isEmpty()) {
            throw new IOException("Unity package contains no FBX model: " + source.getFileName());
        }
        var modelPath = models.get(0).getValue();
        var modelBytes = readUnityAssets(source, index, Set.of(modelPath)).get(modelPath);
        if (modelBytes == null) throw new IOException("Unity package FBX asset is missing: " + modelPath);
        var files = new LinkedHashMap<String, byte[]>();
        files.put(modelPath, modelBytes);
        var relocations = new ArrayList<ScenePackage.Relocation>();
        var document = YsmRuntime.scenes().readFbx(new ByteData(modelBytes), LIMITS);
        var targets = new LinkedHashMap<String, String>();
        for (var texture : document.textures()) {
            if (texture.type() != 0) continue;
            var reference = texture.relativePath().isEmpty()
                    ? texture.absolutePath() : texture.relativePath();
            var explicitTarget = explicitMappings.get(resourceKey(reference));
            if (explicitTarget != null) {
                var found = resolveUnityPath(index.paths(), explicitTarget);
                if (found == null) {
                    throw new IOException("Missing Unity package FBX resource mapping target: " + explicitTarget);
                }
                targets.putIfAbsent(found, reference);
                continue;
            }
            var name = reference.isEmpty() ? "" : Path.of(reference.replace('\\', '/'))
                    .getFileName().toString();
            var exact = index.paths().values().stream()
                    .filter(path -> path.equalsIgnoreCase(reference.replace('\\', '/')))
                    .toList();
            var matches = exact.isEmpty() ? index.paths().values().stream()
                    .filter(path -> extension(Path.of(path))
                            .equals(extension(Path.of(name))))
                    .filter(path -> Path.of(path).getFileName().toString()
                            .equalsIgnoreCase(name)).toList() : exact;
            if (matches.size() > 1) throw new IOException("Ambiguous Unity texture reference " + reference + ": " + matches);
            if (!matches.isEmpty()) {
                targets.putIfAbsent(matches.get(0), reference);
            }
        }
        var imageBytes = readUnityAssets(source, index, targets.keySet());
        for (var entry : targets.entrySet()) {
            var bytes = imageBytes.get(entry.getKey());
            if (bytes == null) throw new IOException("Unity package asset is missing: " + entry.getKey());
            files.put(entry.getKey(), bytes);
            relocations.add(new ScenePackage.Relocation(modelPath, entry.getValue(), entry.getKey()));
        }
        addUnityMaterials(source,index,modelPath,document,files);
        return new Bundle(modelPath, ScenePackage.Format.FBX, files, List.of(), relocations);
    }

    private static final Pattern UNITY_GUID=Pattern.compile("(?m)^\\s*guid:\\s*([0-9a-fA-F]{32})\\s*$");
    private static final Pattern UNITY_NAME=Pattern.compile("(?m)^\\s*m_Name:\\s*(.+?)\\s*$");
    private static final Pattern UNITY_TEX=Pattern.compile("(?ms)^\\s*-\\s*_(MainTex|BaseMap|NormalMap|BumpMap|EmissionMap|EmissiveMap):\\s*\\n\\s*m_Texture:\\s*\\{fileID:\\s*([^,}]+)(?:,\\s*guid:\\s*([0-9a-fA-F]{32}))?[^}]*}");

    /** Recover the small standard PBR subset used by the Unity materials in an archive. */
    private static void addUnityMaterials(Path source,UnityIndex index,String modelPath,
                                          cc.sirrus.ysmlib.scene.fbx.FbxDocument document,
                                          Map<String,byte[]> files) throws IOException {
        var selected=new LinkedHashSet<String>();
        for(var path:index.paths().values()) if(path.endsWith(".mat")||path.endsWith(".meta")) selected.add(path);
        var bytes=readUnityAssets(source,index,selected);
        var guidPaths=new HashMap<String,String>();
        // UnityPackage folder names are the asset GUIDs. Most package exports
        // omit .meta files, so pathname folder IDs are the authoritative index.
        for(var entry:index.paths().entrySet()) guidPaths.put(entry.getKey().toLowerCase(Locale.ROOT),entry.getValue());
        for(var entry:bytes.entrySet()) if(entry.getKey().endsWith(".meta")) {
            var match=UNITY_GUID.matcher(new String(entry.getValue(),StandardCharsets.UTF_8));
            if(match.find()) guidPaths.put(match.group(1).toLowerCase(Locale.ROOT),entry.getKey().substring(0,entry.getKey().length()-5));
        }
        var materials=new ArrayList<UnityMaterial>();
        var imagePaths=new LinkedHashSet<String>();
        for(var entry:bytes.entrySet()) if(entry.getKey().endsWith(".mat")) {
            var text=new String(entry.getValue(),StandardCharsets.UTF_8);
            var name=match(UNITY_NAME,text);if(name==null) continue;
            String base="",normal="",emissive="";
            var matcher=UNITY_TEX.matcher(text);
            while(matcher.find()) {
                var guid=matcher.group(3);if(guid==null||matcher.group(2).trim().equals("0")) continue;
                var target=guidPaths.get(guid.toLowerCase(Locale.ROOT));if(target==null) continue;
                var ref=packageRelative(modelPath,target);
                switch(matcher.group(1)) { case "MainTex","BaseMap" -> { if(base.isEmpty()) base=ref; }
                    case "NormalMap","BumpMap" -> { if(normal.isEmpty()) normal=ref; }
                    case "EmissionMap","EmissiveMap" -> { if(emissive.isEmpty()) emissive=ref; } }
                imagePaths.add(target);
            }
            if(!base.isEmpty()||!normal.isEmpty()||!emissive.isEmpty())
                materials.add(new UnityMaterial(name,base,normal,emissive,name.toLowerCase(Locale.ROOT).contains("alpha")?"BLEND":"OPAQUE",.5f));
        }
        if(materials.isEmpty()) return;
        for(var target:imagePaths) {
            var image=readUnityAssets(source,index,Set.of(target)).get(target);
            if(image!=null) files.put(target,image);
        }
        var overrides=new LinkedHashMap<Integer,FbxMappingProfileData>();
        for(var material:document.details().materials()) {
            var sourceName=fbxMaterialName(document,material.element());
            var match=bestUnityMaterial(sourceName,materials);
            if(match==null) continue;
            overrides.put(material.element(),new FbxMappingProfileData(match.name(),match.baseColor(),match.normal(),match.emissive(),match.alphaMode(),match.cutoff()));
        }
        if(overrides.isEmpty()) return;
        var root=new com.google.gson.JsonObject();root.addProperty("schemaVersion",1);root.addProperty("sourceHash","");
        root.add("skinByMeshElement",new com.google.gson.JsonObject());var objects=new com.google.gson.JsonObject();
        for(var entry:overrides.entrySet()) { var value=entry.getValue();var object=new com.google.gson.JsonObject();
            object.addProperty("name",value.name);object.addProperty("baseColor",value.baseColor);object.addProperty("normal",value.normal);object.addProperty("emissive",value.emissive);object.addProperty("alphaMode",value.alphaMode);object.addProperty("alphaCutoff",value.cutoff);objects.add(Integer.toString(entry.getKey()),object); }
        root.add("materialByElement",objects);
        files.put(cc.sirrus.ysmlib.scene.fbx.FbxMappingProfile.PACKAGE_PATH,
                new com.google.gson.GsonBuilder().disableHtmlEscaping().create().toJson(root).getBytes(StandardCharsets.UTF_8));
    }

    private record UnityMaterial(String name,String baseColor,String normal,String emissive,String alphaMode,float cutoff) {}
    private record FbxMappingProfileData(String name,String baseColor,String normal,String emissive,String alphaMode,float cutoff) {}
    private static String match(Pattern pattern,String text) { var matcher=pattern.matcher(text);return matcher.find()?matcher.group(1).trim():null; }
    private static String packageRelative(String owner,String target) {
        var base=owner.substring(0,Math.max(0,owner.lastIndexOf('/'))).split("/",-1);var targetParts=target.split("/",-1);int common=0;
        while(common<base.length&&common<targetParts.length&&base[common].equals(targetParts[common])) common++;
        var out=new StringBuilder();for(int i=common;i<base.length;i++) out.append("../");for(int i=common;i<targetParts.length;i++){if(out.length()>0&&out.charAt(out.length()-1)!='/')out.append('/');out.append(targetParts[i]);}
        return out.toString();
    }
    private static String fbxMaterialName(cc.sirrus.ysmlib.scene.fbx.FbxDocument document,int element) {
        for(var value:document.elements()) if(value.id()==element||value.typedId()==element) {
            var name=value.name();int pipe=name.indexOf("::");return pipe>=0?name.substring(pipe+2):name;
        } return "";
    }
    private static String normalizedMaterialName(String value) { return value==null?"":value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]","").replaceFirst("^m","").replaceAll("alpha$",""); }
    private static UnityMaterial bestUnityMaterial(String sourceName,List<UnityMaterial> values) {
        var key=normalizedMaterialName(sourceName);
        for(var value:values) if(normalizedMaterialName(value.name()).equals(key)) return value;
        for(var value:values) if(!key.isEmpty()&&(key.contains(normalizedMaterialName(value.name()))||normalizedMaterialName(value.name()).contains(key))) return value;
        // A guessed first material paints every unmatched FBX slot with an
        // unrelated texture. Leave it unmapped so the renderer can retain the
        // source PBR values or report the missing material explicitly.
        return null;
    }

    /**
     * Reads a normal ZIP workspace without extracting it.  ZIP workspaces are
     * common exports from Blender/Unity tooling; keeping the archive as the
     * source lets the catalog cache the original file and avoids recreating a
     * flattened directory tree beside the user's model.
     */
    private static Bundle readWorkspaceZip(Path source,
                                           Map<String,String> explicitMappings) throws IOException {
        try (var zip = new ZipFile(source.toFile())) {
            var entries = new LinkedHashMap<String, ZipEntry>();
            var candidates = new ArrayList<ZipEntry>();
            var total = 0L;
            var it = zip.entries();
            while (it.hasMoreElements()) {
                var entry = it.nextElement();
                if (entry.isDirectory()) continue;
                var path = cleanZipPath(entry.getName());
                if (path == null) continue;
                if (entries.putIfAbsent(path.toLowerCase(Locale.ROOT), entry) != null)
                    throw new IOException("Duplicate ZIP workspace path: " + path);
                var size = entry.getSize();
                if (size > MAX_SOURCE_BYTES || (size >= 0 && total > MAX_SOURCE_BYTES - size))
                    throw new IOException("ZIP workspace exceeds import budget");
                if (isGenericWorkspaceModel(path)) candidates.add(entry);
                if (size > 0) total += size;
            }
            if (candidates.isEmpty())
                throw new IOException("ZIP workspace contains no supported model: " + source.getFileName());
            candidates.sort(Comparator
                    .comparingInt((ZipEntry e) -> e.getName().toLowerCase(Locale.ROOT).endsWith(".fbx") ? 0 : 1)
                    .thenComparingLong(e -> -(e.getSize() < 0 ? 0 : e.getSize()))
                    .thenComparing(ZipEntry::getName));
            var modelEntry = candidates.get(0);
            var modelPath = cleanZipPath(modelEntry.getName());
            var format = workspaceFormat(modelPath);
            var modelBytes = readZipEntry(zip, modelEntry);
            var files = new LinkedHashMap<String, byte[]>();
            files.put(modelPath, modelBytes);
            var relocations = new ArrayList<ScenePackage.Relocation>();
            if (format == ScenePackage.Format.FBX) {
                var document = YsmRuntime.scenes().readFbx(new ByteData(modelBytes), LIMITS);
                var targets = new LinkedHashMap<String, String>();
                for (var texture : document.textures()) {
                    if (texture.type() != 0) continue;
                    var reference = texture.relativePath().isEmpty()
                            ? texture.absolutePath() : texture.relativePath();
                    if (reference.isEmpty()) continue;
                    var normalized = cleanTextureReference(reference);
                    var explicit = explicitMappings.get(resourceKey(normalized));
                    String target;
                    if (explicit != null) {
                        target = resolveZipPath(entries, explicit);
                        if (target == null)
                            throw new IOException("Missing ZIP workspace FBX resource mapping target: " + explicit);
                    } else {
                        target = findZipTexture(entries, normalized);
                        if (target == null) continue;
                    }
                    targets.putIfAbsent(target, reference);
                }
                for (var target : targets.entrySet()) {
                    var entry = entries.get(target.getKey().toLowerCase(Locale.ROOT));
                    if (entry == null) throw new IOException("Missing ZIP workspace asset: " + target.getKey());
                    files.put(target.getKey(), readZipEntry(zip, entry));
                    relocations.add(new ScenePackage.Relocation(modelPath, target.getValue(), target.getKey()));
                }
            } else {
                // glTF/VRM/PMX/PMD references are already package-relative;
                // retain all bounded companion files so URI and texture lookup
                // works without a second extraction step.
                for (var entry : entries.values()) {
                    var path = cleanZipPath(entry.getName());
                    if (path.equals(modelPath)) continue;
                    if (isWorkspaceCompanion(path)) files.put(path, readZipEntry(zip, entry));
                }
            }
            return new Bundle(modelPath, format, files, List.of(), relocations);
        }
    }

    private static String cleanZipPath(String value) {
        if (value == null) return null;
        var normalized = value.replace('\\', '/');
        while (normalized.startsWith("./")) normalized = normalized.substring(2);
        if (normalized.isBlank() || normalized.startsWith("/") || normalized.indexOf('\0') >= 0)
            return null;
        for (var part : normalized.split("/")) if (part.isBlank() || part.equals("..")) return null;
        return normalized;
    }

    private static boolean isGenericWorkspaceModel(String value) {
        var lower = value.toLowerCase(Locale.ROOT);
        return lower.endsWith(".fbx") || lower.endsWith(".gltf") || lower.endsWith(".glb")
                || lower.endsWith(".vrm") || lower.endsWith(".pmx") || lower.endsWith(".pmd");
    }

    private static ScenePackage.Format workspaceFormat(String value) throws IOException {
        var lower = value.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".fbx")) return ScenePackage.Format.FBX;
        if (lower.endsWith(".gltf") || lower.endsWith(".glb")) return ScenePackage.Format.GLTF;
        if (lower.endsWith(".vrm")) return ScenePackage.Format.VRM;
        if (lower.endsWith(".pmx")) return ScenePackage.Format.PMX;
        if (lower.endsWith(".pmd")) return ScenePackage.Format.PMD;
        throw new IOException("Unsupported ZIP workspace model: " + value);
    }

    private static boolean isWorkspaceCompanion(String value) {
        var lower = value.toLowerCase(Locale.ROOT);
        return IMAGE_EXTENSIONS.stream().anyMatch(lower::endsWith)
                || lower.endsWith(".bin") || lower.endsWith(".vmd") || lower.endsWith(".vpd")
                || lower.endsWith(".vrma") || lower.endsWith(".gltf") || lower.endsWith(".glb");
    }

    private static String resolveZipPath(Map<String, ZipEntry> entries, String target) throws IOException {
        var normalized = cleanZipPath(cleanFbxResourceTarget(target));
        if (normalized == null) throw new IOException("Invalid ZIP workspace mapping target: " + target);
        var entry = entries.get(normalized.toLowerCase(Locale.ROOT));
        return entry == null ? null : cleanZipPath(entry.getName());
    }

    private static String findZipTexture(Map<String, ZipEntry> entries, String reference) throws IOException {
        var normalized = cleanZipPath(cleanTextureReference(reference));
        if (normalized == null) return null;
        var exact = entries.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(normalized)).toList();
        if (exact.size() > 1) throw new IOException("Ambiguous ZIP workspace texture reference: " + reference);
        if (!exact.isEmpty()) return cleanZipPath(exact.get(0).getValue().getName());
        var slash = normalized.lastIndexOf('/');
        var basename = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        var matches = entries.entrySet().stream().filter(entry -> {
            var path = entry.getKey();
            return path.substring(path.lastIndexOf('/') + 1).equalsIgnoreCase(basename);
        }).toList();
        if (matches.size() > 1) throw new IOException("Ambiguous ZIP workspace texture reference: " + reference + ": " + matches);
        return matches.isEmpty() ? null : cleanZipPath(matches.get(0).getValue().getName());
    }

    private static byte[] readZipEntry(ZipFile zip, ZipEntry entry) throws IOException {
        var size = entry.getSize();
        if (size > MAX_SOURCE_BYTES || size > Integer.MAX_VALUE)
            throw new IOException("ZIP workspace entry is too large: " + entry.getName());
        try (var input = zip.getInputStream(entry)) {
            var output = new ByteArrayOutputStream((int) Math.min(size < 0 ? 64 * 1024 : size, 64 * 1024));
            var buffer = new byte[64 * 1024];
            long total = 0;
            while (true) {
                int count = input.read(buffer);
                if (count < 0) break;
                total += count;
                if (total > MAX_SOURCE_BYTES || total > Integer.MAX_VALUE)
                    throw new IOException("ZIP workspace entry exceeds import budget: " + entry.getName());
                output.write(buffer, 0, count);
            }
            if (size >= 0 && total != size)
                throw new IOException("Truncated ZIP workspace entry: " + entry.getName());
            return output.toByteArray();
        }
    }

    /** Resolve an explicit mapping against Unity's serialized pathname table.
     * The target is package-relative and never interpreted as a host path. */
    private static String resolveUnityPath(Map<String, String> paths,
                                           String target) throws IOException {
        var normalized = cleanFbxResourceTarget(target);
        var matches = paths.values().stream()
                .filter(path -> path.replace('\\', '/').equalsIgnoreCase(normalized))
                .toList();
        if (matches.size() > 1) throw new IOException("Ambiguous Unity package mapping target: " + target);
        return matches.isEmpty() ? null : matches.get(0);
    }

    private record UnityIndex(Map<String, String> paths, Map<String, Long> assetSizes) {}

    /** Reads only Unity pathname and asset-size metadata; no asset payloads are retained. */
    private static UnityIndex readUnityIndex(Path source) throws IOException {
        var paths = new LinkedHashMap<String, String>();
        var assetSizes = new LinkedHashMap<String, Long>();
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
                var slash = name.lastIndexOf('/');
                var folder = slash < 1 ? "" : name.substring(0, slash);
                var leaf = slash < 1 ? "" : name.substring(slash + 1);
                if (leaf.equals("pathname")) {
                    var bytes = readEntry(gzip, size);
                    var path = new String(bytes, StandardCharsets.UTF_8).trim()
                            .replace('\\', '/');
                    if (!path.isEmpty() && !path.startsWith("/") && !path.contains(".."))
                        paths.put(folder, path);
                } else {
                    discardEntry(gzip, size);
                    if (leaf.equals("asset") && !folder.isEmpty()) assetSizes.put(folder, size);
                }
                total += size;
            }
        }
        return new UnityIndex(Map.copyOf(paths), Map.copyOf(assetSizes));
    }

    /** Extracts only selected pathname entries in a second streaming pass. */
    private static Map<String, byte[]> readUnityAssets(Path source, UnityIndex index,
                                                        Set<String> selectedPaths) throws IOException {
        var selected = new HashMap<String, String>();
        for (var entry : index.paths().entrySet()) {
            if (selectedPaths.stream().anyMatch(path -> path.equalsIgnoreCase(entry.getValue())))
                selected.put(entry.getKey(), entry.getValue());
        }
        var output = new LinkedHashMap<String, byte[]>();
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
                var slash = name.lastIndexOf('/');
                var folder = slash < 1 ? "" : name.substring(0, slash);
                var leaf = slash < 1 ? "" : name.substring(slash + 1);
                if (leaf.equals("asset") && selected.containsKey(folder)) {
                    output.put(selected.get(folder), readEntry(gzip, size));
                } else {
                    discardEntry(gzip, size);
                }
                total += size;
            }
        }
        return output;
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

    private static void discardEntry(InputStream input, long size) throws IOException {
        long remaining = size;
        byte[] buffer = new byte[64 * 1024];
        while (remaining > 0) {
            int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (count < 0) throw new IOException("Truncated Unity package entry");
            remaining -= count;
        }
        var padding = (512 - (size & 511)) & 511;
        while (padding > 0) {
            int count = input.read(buffer, 0, (int) Math.min(buffer.length, padding));
            if (count < 0) throw new IOException("Truncated Unity package padding");
            padding -= count;
        }
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
