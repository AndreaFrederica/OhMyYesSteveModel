package com.elfmcys.ysm.format.parser;

import cc.sirrus.ysmlib.audio.SupportedAudioProbe;
import com.elfmcys.ysm.format.parser.pojo.animation.AnimationFile;
import com.elfmcys.ysm.format.parser.pojo.controller.AnimationControllerFile;
import com.elfmcys.ysm.format.parser.pojo.manifest.ModelManifest;
import com.elfmcys.ysm.format.parser.pojo.manifest.models.PBRTextureSet;
import com.elfmcys.ysm.format.parser.pojo.manifest.models.PlayerModelFiles;
import com.elfmcys.ysm.format.parser.pojo.manifest.models.ReplacedModelFiles;
import com.elfmcys.ysm.format.parser.pojo.manifest.settings.ModelProperties;
import com.elfmcys.ysm.format.parser.pojo.model.GeoModel;
import com.elfmcys.ysm.format.schema.model.ModelFileConstant;
import com.elfmcys.ysm.format.schema.model.ModelFileWriter;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.natives.image.Image;
import com.elfmcys.ysm.proto.mixel.asset.model.ModelData;
import com.elfmcys.ysm.proto.mixel.asset.strings.StringData;
import com.elfmcys.ysm.proto.mixel.asset.strings.data.UserFunction;
import com.elfmcys.ysm.proto.mixel.common.Sound;
import com.elfmcys.ysm.proto.mixel.common.StringPair;
import com.elfmcys.ysm.proto.mixel.manifest.Manifest;
import com.elfmcys.ysm.proto.mixel.manifest.asset.Common;
import com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTarget;
import com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind;
import com.elfmcys.ysm.proto.mixel.manifest.info.Info;
import com.elfmcys.ysm.proto.mixel.manifest.info.LanguageFile;
import com.elfmcys.ysm.proto.mixel.manifest.info.PreviewSource;
import com.elfmcys.ysm.util.Closeable;
import com.elfmcys.ysm.util.ProtoUtil;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;

final class RawModelAssembler implements Closeable {
    private static final String MANIFEST_FILE_NAME = "ysm.json";

    static final String[] PLAYER_MAIN_ANIMATION_TYPES = {
            "main", "arm", "fp_arm", "extra", "tac", "carryon", "parcool", "swem",
            "slashblade", "tlm", "immersive_melodies", "irons_spell_books"
    };

    private final Gson gson = new Gson();
    private final RawModelSource source;
    private final boolean dryRun;
    private final DefaultAnimationFilter defaultAnimationFilter;
    private final boolean recompressImages;
    @Nullable
    private final Path dstDir;
    @Nullable
    private final ModelFileWriter writer;
    @Nullable
    private final RawImageCompressor imageCompressor;
    private final List<RawModelDiagnostic> diagnostics = new ArrayList<>();

    private ModelProperties activeProperties;

    RawModelAssembler(RawModelSource source, @Nullable Path dstDir, boolean dryRun,
                      DefaultAnimationFilter defaultAnimationFilter, boolean recompressImages) {
        this.source = Objects.requireNonNull(source, "source");
        this.dryRun = dryRun;
        this.defaultAnimationFilter = Objects.requireNonNull(
                defaultAnimationFilter, "defaultAnimationFilter");
        this.recompressImages = recompressImages;
        this.dstDir = dryRun ? null : Objects.requireNonNull(dstDir, "dstDir");
        this.writer = dryRun ? null : new ModelFileWriter();
        this.imageCompressor = dryRun || !recompressImages ? null : new RawImageCompressor();
    }

    Result parse() {
        try {
            var sourceManifest = readManifest();
            activeProperties = sourceManifest.properties;

            var manifest = dryRun ? null : Manifest.newBuilder();
            writePlayerModel(manifest, sourceManifest.files.player, sourceManifest.properties);
            var info = ModelInfoBuilder.buildInfo(!dryRun, sourceManifest, this::readImageBlob);
            writeReplacedModels(manifest, sourceManifest);
            writeCommonAssets(manifest, info, sourceManifest);
            writeContainerImages(info, sourceManifest.properties);

            var modelHash = new Hash256(source.aggregateHash());
            if (dryRun) {
                return new Result(modelHash, null, List.copyOf(diagnostics));
            }
            var hashId = modelHash.toString();
            ModelInfoBuilder.setProperties(Objects.requireNonNull(info, "info"),
                    sourceManifest.properties, ModelFileConstant.CURRENT_VERSION.toString(), modelHash);
            Objects.requireNonNull(manifest, "manifest")
                    .setInfo(info.build());
            writer().setManifest(manifest.build());
            Files.createDirectories(Objects.requireNonNull(dstDir, "dstDir"));
            var output = dstDir.resolve(hashId + ".mxc");
            try (var channel = FileChannel.open(output,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                writer().write(channel);
            }
            return new Result(modelHash, output, List.copyOf(diagnostics));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private ModelManifest readManifest() throws IOException {
        var manifest = source.readJson(MANIFEST_FILE_NAME, "manifest", gson, ModelManifest.class, false)
                .orElseGet(() -> {
                    try {
                        return LegacyManifestBuilder.build(source, gson);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
        manifest.normalize();
        return manifest;
    }

    private void writePlayerModel(@Nullable Manifest.Builder manifest,
                                  PlayerModelFiles files, ModelProperties properties)
            throws IOException {
        if (files == null) {
            throw new IOException("Missing player model files");
        }
        var main = readGeoModel(Objects.requireNonNull(files.model.get("main"), "Missing main model path"), true)
                .orElse(null);
        var arm = readGeoModel(Objects.requireNonNull(files.model.get("arm"), "Missing arm model path"), true)
                .orElse(null);
        var playerData = dryRun ? null : ModelData.newBuilder();

        if (!dryRun) {
            Objects.requireNonNull(playerData, "playerData").putGeoModels("main",
                    ByteBuffer.wrap(ProtoUtil.serializeToArray(
                            Objects.requireNonNull(main, "main").model)));
            playerData.putGeoModels("arm", ByteBuffer.wrap(ProtoUtil.serializeToArray(
                    Objects.requireNonNull(arm, "arm").model)));

            if (properties.heightScale == 0.7f && main.heightScale != 0.7f) {
                properties.heightScale = main.heightScale;
            }
            if (properties.widthScale == 0.7f && main.widthScale != 0.7f) {
                properties.widthScale = main.widthScale;
            }
        }

        var defaultTexture = properties.defaultTexture;

        for (var animationType : PLAYER_MAIN_ANIMATION_TYPES) {
            var path = files.animation.get(animationType);
            if (path != null) {
                var animation = readAnimation(path, true).orElse(null);
                if (!dryRun) {
                    animation = filterAnimations(
                            RenderTargetKind.RENDER_TARGET_KIND_PLAYER,
                            List.of(), animationType,
                            Objects.requireNonNull(animation, "animation"));
                    if (animation.animations().isEmpty()) {
                        continue;
                    }
                    Objects.requireNonNull(playerData, "playerData").putAnimationFiles(
                            animationType, Objects.requireNonNull(animation, "animation"));
                }
            }
        }

        for (var controllerFile : files.animationControllers) {
            var controller = readAnimationController(controllerFile);
            if (!dryRun) {
                var name = fileNameWithoutExtension(controllerFile);
                Objects.requireNonNull(playerData, "playerData").putAnimationControllers(
                        name, Objects.requireNonNull(controller, "controller"));
            }
        }
        var player = dryRun ? null : RenderTarget.newBuilder()
                .setTargetId("player")
                .setKind(RenderTargetKind.RENDER_TARGET_KIND_PLAYER);
        for (var texture : files.texture) {
            var textureSet = readTextureSet(texture);
            if (!dryRun) {
                var textureName = textureName(texture.uv);
                if (defaultTexture.isEmpty()) {
                    defaultTexture = textureName;
                    properties.defaultTexture = textureName;
                }
                Objects.requireNonNull(player, "player").putTextures(
                        textureName, Objects.requireNonNull(textureSet, "textureSet"));
            }
        }

        if (!dryRun) {
            Objects.requireNonNull(player, "player")
                    .setSettings(ModelInfoBuilder.modelSettings(properties))
                    .setStats(Objects.requireNonNull(main, "main").stats.toProto())
                    .setBlobId(writer().addProtoBlob(
                            Objects.requireNonNull(playerData, "playerData").build(), 16));
            Objects.requireNonNull(manifest, "manifest").addRenderTargets(player.build());
        }
    }

    @SuppressWarnings("deprecation")
    private void writeReplacedModels(@Nullable Manifest.Builder manifest,
                                     ModelManifest sourceManifest) throws IOException {
        var projectileIndex = 0;
        for (var model : sourceManifest.files.projectiles.list) {
            writeReplacedModel(manifest, model, "projectile/%04d".formatted(projectileIndex++),
                    RenderTargetKind.RENDER_TARGET_KIND_PROJECTILE);
        }
        if (sourceManifest.files.arrow != null) {
            writeReplacedModel(manifest, sourceManifest.files.arrow, "projectile/%04d".formatted(projectileIndex),
                    RenderTargetKind.RENDER_TARGET_KIND_PROJECTILE);
        }
        var vehicleIndex = 0;
        for (var model : sourceManifest.files.vehicles.list) {
            writeReplacedModel(manifest, model, "vehicle/%04d".formatted(vehicleIndex++),
                    RenderTargetKind.RENDER_TARGET_KIND_VEHICLE);
        }
    }

    private void writeReplacedModel(@Nullable Manifest.Builder manifest,
                                    ReplacedModelFiles files, String targetId,
                                    RenderTargetKind kind)
            throws IOException {
        if (files == null || files.match == null || files.match.isEmpty()) {
            return;
        }
        var geo = readGeoModel(files.model, true).orElse(null);
        com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationFile animation = null;
        if (!StringUtils.isBlank(files.animation)) {
            animation = readAnimation(files.animation, true).orElse(null);
        }
        com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationControllerFile controller = null;
        if (!StringUtils.isBlank(files.controller)) {
            controller = readAnimationController(files.controller);
        }

        RenderTarget.Builder replaced = null;
        if (!dryRun) {
            replaced = RenderTarget.newBuilder()
                    .setTargetId(targetId)
                    .setKind(kind);
            for (var match : files.match) {
                replaced.addMatch(match);
            }
            var data = ModelData.newBuilder();
            data.putGeoModels("main", ByteBuffer.wrap(ProtoUtil.serializeToArray(
                    Objects.requireNonNull(geo, "geo").model)));
            if (animation != null) {
                animation = filterAnimations(kind, files.match, "main", animation);
            }
            if (animation != null && !animation.animations().isEmpty()) {
                data.putAnimationFiles("main", animation);
            }
            if (controller != null) {
                data.putAnimationControllers("main", controller);
            }

            replaced.setBlobId(writer().addProtoBlob(data.build(), 16))
                    .setSettings(ModelInfoBuilder.modelSettings(activeProperties))
                    .setStats(geo.stats.toProto());
        }

        var texture = readTextureSet(files.texture);
        if (dryRun) {
            return;
        }
        Objects.requireNonNull(replaced, "replaced").putTextures(
                "default", Objects.requireNonNull(texture, "texture"));
        Objects.requireNonNull(manifest, "manifest").addRenderTargets(replaced.build());
    }

    private com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationFile filterAnimations(
            RenderTargetKind kind, List<String> matches,
            String animationSet,
            com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationFile animations) throws IOException {
        return defaultAnimationFilter.apply(kind, matches, animationSet, animations);
    }

    @SuppressWarnings("deprecation")
    private void writeCommonAssets(@Nullable Manifest.Builder manifest,
                                   @Nullable Info.Builder info,
                                   ModelManifest sourceManifest) throws IOException {
        var files = sourceManifest.files;
        final List<Sound> sounds;
        if (!StringUtils.isBlank(files.soundPath)) {
            sounds = writeSounds(files.soundPath);
        } else if (files.player != null && !StringUtils.isBlank(files.player.soundPath)) {
            sounds = writeSounds(files.player.soundPath);
        } else {
            sounds = writeSounds("sounds");
        }

        var strings = dryRun ? null : StringData.newBuilder();
        writeFunctions(strings, StringUtils.isBlank(files.functionPath) ? "functions" : files.functionPath);
        writeLanguageFiles(info, StringUtils.isBlank(files.languagePath) ? "lang" : files.languagePath);
        if (!dryRun) {
            var common = Common.newBuilder()
                    .setStringsBlobId(writer().addProtoBlob(
                            Objects.requireNonNull(strings, "strings").build(), 16));
            sounds.forEach(common::addSounds);
            Objects.requireNonNull(manifest, "manifest").setCommonAssets(common.build());
        }
    }

    private void writeContainerImages(@Nullable Info.Builder info,
                                      ModelProperties properties)
            throws IOException {
        if (readImage(properties.icon, "icon", false, RawImageCompressor.MODEL_ICON,
                dryRun ? null : writer()::setIcon) && !dryRun) {
            Objects.requireNonNull(info, "info")
                    .setIconSource(PreviewSource.PREVIEW_SOURCE_RAW);
        }
        if (readImage(properties.thumbnail, "thumbnail", false,
                RawImageCompressor.MODEL_THUMBNAIL, dryRun ? null : writer()::setThumbnail) && !dryRun) {
            Objects.requireNonNull(info, "info")
                    .setThumbnailSource(PreviewSource.PREVIEW_SOURCE_RAW);
        }
    }

    @SuppressWarnings("SameParameterValue")
    Optional<GeoBuilder.Result> readGeoModel(String fileName, boolean required) throws IOException {
        if (dryRun) {
            source.readFile(fileName, "model", required);
            return Optional.empty();
        }
        return source.readJson(fileName, "model", gson, GeoModel.class, required)
                .map(m -> {
                    try {
                        return GeoBuilder.build(m);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    @SuppressWarnings("SameParameterValue")
    Optional<com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationFile> readAnimation(String fileName, boolean required) throws IOException {
        if (dryRun) {
            source.readFile(fileName, "animation", required);
            return Optional.empty();
        }
        return source.readJson(fileName, "animation",
                AnimationFile.createGson(activeProperties != null && activeProperties.mergeMultilineExpr),
                AnimationFile.class, required)
                .map(AnimationBuilder::build);
    }

    @Nullable
    private com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationControllerFile readAnimationController(String fileName) throws IOException {
        if (dryRun) {
            source.readFile(fileName, "controller", true);
            return null;
        }
        return source.readJson(fileName, "controller", gson, AnimationControllerFile.class, true)
                .map(AnimationControllerBuilder::build)
                .orElseThrow();
    }

    @Nullable
    private com.elfmcys.ysm.proto.mixel.manifest.asset.PBRTextureSet readTextureSet(PBRTextureSet src) throws IOException {
        if (src == null || StringUtils.isBlank(src.uv)) {
            throw new IOException("Missing texture uv");
        }
        var dst = dryRun ? null : com.elfmcys.ysm.proto.mixel.manifest.asset.PBRTextureSet.newBuilder();
        readImageBlob(src.uv, "texture", RawImageCompressor.LOSSLESS_TEXTURE,
                dst == null ? null : dst::setUv);
        if (!StringUtils.isBlank(src.normal)) {
            readImageBlob(src.normal, "texture/normal", RawImageCompressor.LOSSLESS_TEXTURE,
                    dst == null ? null : dst::setNormal);
        }
        if (!StringUtils.isBlank(src.specular)) {
            readImageBlob(src.specular, "texture/specular", RawImageCompressor.LOSSLESS_TEXTURE,
                    dst == null ? null : dst::setSpecular);
        }
        return dst == null ? null : dst.build();
    }

    void readImageBlob(String path, String hashType, RawImageCompressor.Policy policy,
                       @Nullable Consumer<com.elfmcys.ysm.proto.mixel.common.Image> consumer) throws IOException {
        readImage(path, hashType, true, policy, img -> {
            var blobId = writer().addImageBlob(img);
            Objects.requireNonNull(consumer, "consumer").accept(com.elfmcys.ysm.proto.mixel.common.Image.newBuilder()
                    .setBlobId(blobId)
                    .setFormat(img.format().name())
                    .setWidth(img.width())
                    .setHeight(img.height())
                    .setFrameCount(1)
                    .build());
        });
    }

    boolean readImage(String path, String type, boolean required, RawImageCompressor.Policy policy,
                      @Nullable Consumer<Image> consumer) throws IOException {
        var data = source.readFile(path, type, required);
        if (data.isEmpty()) {
            return false;
        }
        if (dryRun) {
            return true;
        }
        try (var original = Image.probe(data.orElseThrow())) {
            try (var stored = recompressImages
                    ? imageCompressor().compress(original, policy, path)
                    : original.share()) {
                Objects.requireNonNull(consumer, "consumer").accept(stored);
            }
        }
        return true;
    }

    private List<Sound> writeSounds(String path)
            throws IOException {
        var sounds = new ArrayList<Sound>();
        var names = new HashSet<String>();
        var failure = new IOException[1];
        source.collectFiles(path, "", "sound", true, (name, data) -> {
            if (failure[0] != null) {
                return;
            }
            var inspection = SupportedAudioProbe.inspect(data.nio());
            if (inspection.disposition() == SupportedAudioProbe.Disposition.UNKNOWN) {
                diagnostics.add(new RawModelDiagnostic(
                        RawModelDiagnostic.Kind.UNKNOWN_AUDIO));
                return;
            }
            if (!inspection.playable()) {
                diagnostics.add(new RawModelDiagnostic(
                        RawModelDiagnostic.Kind.INVALID_AUDIO));
                return;
            }
            if (!names.add(name)) {
                failure[0] = new IOException("Duplicate raw sound name: " + name);
                return;
            }
            var media = inspection.media();
            int streamId = dryRun ? sounds.size() + 1 : writer().addStream(data);
            sounds.add(Sound.newBuilder()
                    .setName(name)
                    .setEncoding(media.encoding().name())
                    .setChannels(media.channels())
                    .setSampleRate((int) media.sampleRate())
                    .setSamples(media.frames())
                    .setStreamId(streamId)
                    .build());
        });
        if (failure[0] != null) {
            throw failure[0];
        }
        return List.copyOf(sounds);
    }

    private void writeFunctions(@Nullable StringData.Builder strings, String path) {
        source.collectFiles(path, ".molang", "molang-func", true, (name, buf) -> {
            if (!dryRun) {
                Objects.requireNonNull(strings, "strings")
                        .addUserFunctions(UserFunction.newBuilder()
                                .setName(name)
                                .setBody(SourcePrograms.of(RawModelSource.readUtf8(buf)))
                                .build());
            }
        });
    }

    private void writeLanguageFiles(@Nullable Info.Builder info, String path) {
        source.collectFiles(path, ".json", "language", true, (name, buf) -> {
            if (!dryRun) {
                var object = JsonParser.parseString(RawModelSource.readUtf8(buf)).getAsJsonObject();
                var language = LanguageFile.newBuilder()
                        .setLocale(name);
                for (var entry : object.entrySet()) {
                    if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isString()) {
                        language.putEntries(entry.getKey(), entry.getValue().getAsString());
                    }
                }
                Objects.requireNonNull(info, "info").addLanguageFiles(language.build());
            }
        });
    }

    private ModelFileWriter writer() {
        return Objects.requireNonNull(writer, "writer");
    }

    private RawImageCompressor imageCompressor() {
        return Objects.requireNonNull(imageCompressor, "imageCompressor");
    }

    private static StringPair stringPair(String key, String value) {
        return StringPair.newBuilder()
                .setKey(key)
                .setValue(value)
                .build();
    }

    private static String normalizePath(String path) {
        if (path == null) {
            return "";
        }
        var normalized = path.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String joinPath(String dir, String name) {
        if (StringUtils.isBlank(dir)) {
            return name;
        }
        return dir + "/" + name;
    }

    private static String textureName(String path) {
        return removeExtension(baseName(path));
    }

    private static String fileNameWithoutExtension(String path) {
        return removeExtension(baseName(path));
    }

    private static String baseName(String path) {
        var normalized = normalizePath(path);
        var index = normalized.lastIndexOf('/');
        return index >= 0 ? normalized.substring(index + 1) : normalized;
    }

    private static String removeExtension(String path) {
        var index = path.lastIndexOf('.');
        return index >= 0 ? path.substring(0, index) : path;
    }

    @Override
    public void close() {
        if (writer != null) {
            writer.close();
        }
    }

    record Result(Hash256 modelHash, @Nullable Path output,
                  List<RawModelDiagnostic> diagnostics) {
    }
}
