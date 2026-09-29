package com.elfmcys.ysm.model.catalog.builtin;

import com.elfmcys.ysm.buffer.ArrayBuffer;
import com.elfmcys.ysm.buffer.BufferType;
import com.elfmcys.ysm.client.animation.molang.CustomMolangParser;
import com.elfmcys.ysm.format.AssetLoadException;
import cc.sirrus.ysmlib.audio.SupportedAudioProbe;
import com.elfmcys.ysm.format.schema.file.AssetFileConstant;
import com.elfmcys.ysm.format.schema.file.ChunkDataSource;
import com.elfmcys.ysm.format.schema.file.PBRImageSources;
import com.elfmcys.ysm.format.schema.model.ModelFileConstant;
import com.elfmcys.ysm.format.schema.model.ModelFileView;
import com.elfmcys.ysm.model.catalog.content.DefaultAnimationKey;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.resource.client.render.AnimationProtoMapper;
import com.elfmcys.ysm.model.storage.ManagedContainer;
import com.elfmcys.ysm.model.storage.ModelHashing;
import com.elfmcys.ysm.natives.image.ImageSource;
import com.elfmcys.ysm.model.resource.client.render.RuntimeGeometryMapper;
import cc.sirrus.ysmlib.YsmRuntime;
import cc.sirrus.ysmlib.render.Geometry;
import com.elfmcys.ysm.proto.mixel.asset.model.ModelData;
import com.elfmcys.ysm.proto.mixel.asset.model.data.Animation;
import com.elfmcys.ysm.proto.mixel.asset.model.data.GeoModel;
import com.elfmcys.ysm.proto.mixel.manifest.asset.PBRTextureSet;
import com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTarget;
import com.elfmcys.ysm.util.ProtoUtil;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sound.sampled.UnsupportedAudioFileException;
import us.hebi.quickbuf.ProtoSource;

/** Build-safe semantic materialization. It never registers textures or writes bake caches. */
public final class BuiltinModelMaterializer {
    private static final int CURRENT_RAW_UV_VERSION = 29;

    private BuiltinModelMaterializer() {
    }

    public static Result materialize(ManagedContainer handle) throws IOException {
        var view = handle.view();
        var chunks = handle.chunks();
        decodeNamedImage(view, chunks, ModelFileConstant.THUMB_BUTTON_CHUNK_NAME);
        decodeNamedImage(view, chunks, ModelFileConstant.THUMB_ICON_CHUNK_NAME);
        decodePresentationImages(view, chunks);

        var animationHashes = new LinkedHashMap<DefaultAnimationKey, Hash256>();
        for (var target : view.getRenderTargets()) {
            var definition = readDefinition(view, chunks, target.descriptor().blobId());
            bindControllers(definition);
            bindAndHashAnimations(target.descriptor(), definition, animationHashes);
            for (var textureName : target.getTextureNames()) {
                var texture = target.textureDescriptor(textureName);
                var sources = target.textureSources(chunks, textureName);
                decodeAdditionalTextureImages(sources);
                try (var uv = sources.uv().open();
                     var pixels = uv.decodeToBuffer()) {
                    for (var geoEntry : definition.geoModels().object2ObjectEntrySet()) {
                        var geo = GeoModel.parseFrom(
                                ProtoSource.newInstance(geoEntry.getValue()));
                        try {
                            var baked = YsmRuntime.bake().bake(RuntimeGeometryMapper.map(geo), pixels.nio(), uv.width(), uv.height(),
                                    new Geometry.Options(CURRENT_RAW_UV_VERSION,
                                    target.descriptor().settings().forceCulling(),
                                    false, hasPbr(texture)));
                            // Exercise the same cache representation consumed at runtime.
                            YsmRuntime.bake().decode(ByteBuffer.wrap(YsmRuntime.bake().encode(baked)));
                        } catch (IOException invalid) {
                            throw new IOException("JVM bake rejected model target="
                                    + target.id() + " texture=" + textureName
                                    + " geometry=" + geoEntry.getKey(), invalid);
                        }
                    }
                }
            }
        }
        bindCommonStrings(view, chunks);
        validateSounds(view, chunks);
        return new Result(handle.representation().modelId(), Map.copyOf(animationHashes));
    }

    private static ModelData readDefinition(
            ModelFileView view, ChunkDataSource chunks, int blobId) throws IOException {
        var chunk = view.getFileView().getAssetView().getChunkInfo(
                AssetFileConstant.BLOB_CHUNK_PREFIX + blobId);
        if (chunk == null) {
            throw new FileNotFoundException("Missing render target definition blob " + blobId);
        }
        try (var data = chunks.readPayload(chunk, BufferType.ARRAY)) {
            return ModelData.parseFrom(
                    ProtoUtil.source((ArrayBuffer) data));
        }
    }

    private static void bindControllers(ModelData definition) {
        if (!definition.animationControllers().isEmpty()) {
            definition.animationControllers().values().forEach(
                    AnimationProtoMapper::controllerFile);
        }
    }

    private static void bindAndHashAnimations(
            RenderTarget target,
            ModelData definition,
            Map<DefaultAnimationKey, Hash256> output) throws IOException {
        if (definition.animationFiles().isEmpty()) {
            return;
        }
        for (var file : definition.animationFiles().object2ObjectEntrySet()) {
            var domain = DefaultAnimationKey.domain(target, file.getKey());
            for (var animation : file.getValue().animations()) {
                AnimationProtoMapper.animation(animation);
                var key = new DefaultAnimationKey(domain, animation.name());
                var hash = payloadHash(animation);
                var previous = output.putIfAbsent(key, hash);
                if (previous != null && !previous.equals(hash)) {
                    throw new IOException("Conflicting default animation payloads for " + key);
                }
            }
        }
    }

    public static Hash256 payloadHash(Animation animation)
            throws IOException {
        return ModelHashing.blake3(ProtoUtil.serializeToArray(animation));
    }

    private static void decodeAdditionalTextureImages(
            PBRImageSources sources)
            throws IOException {
        if (sources.normal() != null) {
            decode(sources.normal());
        }
        if (sources.specular() != null) {
            decode(sources.specular());
        }
    }

    private static void decode(ImageSource source)
            throws IOException {
        try (var image = source.open(); var ignored = image.decodeToBuffer()) {
        }
    }

    private static void decodeNamedImage(ModelFileView view, ChunkDataSource chunks,
                                         String type) throws IOException {
        var source = view.getFileView().imageChunkSource(chunks, type);
        if (source != null) {
            decode(source);
        }
    }

    private static void decodePresentationImages(ModelFileView view,
                                                 ChunkDataSource chunks)
            throws IOException {
        var info = view.getManifest().info();
        var settings = info.settings();
        if (settings.hasGuiForeground()) {
            decode(view.getFileView().imageBlobSource(
                    chunks, settings.guiForegroundUnsafe()));
        }
        if (settings.hasGuiBackground()) {
            decode(view.getFileView().imageBlobSource(
                    chunks, settings.guiBackgroundUnsafe()));
        }
        if (info.hasMetadata() && !info.metadataUnsafe().authors().isEmpty()) {
            for (var author : info.metadataUnsafe().authors()) {
                if (author.hasAvatar()) {
                    decode(view.getFileView().imageBlobSource(
                            chunks, author.avatarUnsafe()));
                }
            }
        }
    }

    private static void bindCommonStrings(ModelFileView view, ChunkDataSource chunks)
            throws IOException {
        var common = view.getManifest().commonAssets();
        if (common.stringsBlobId() == 0) {
            return;
        }
        var chunk = view.getFileView().getAssetView().getChunkInfo(
                AssetFileConstant.BLOB_CHUNK_PREFIX + common.stringsBlobId());
        if (chunk == null) {
            throw new FileNotFoundException("Missing common strings blob");
        }
        try (var data = chunks.readPayload(chunk, BufferType.ARRAY)) {
            var strings = com.elfmcys.ysm.proto.mixel.asset.strings.StringData
                    .parseFrom(ProtoUtil.source((ArrayBuffer) data));
            if (!strings.userFunctions().isEmpty()) {
                var parser = CustomMolangParser.rentInstance();
                try {
                    strings.userFunctions().forEach(function ->
                            parser.parseExpression(function.body().source(), false));
                } finally {
                    CustomMolangParser.returnInstance(parser);
                }
            }
        }
    }

    private static void validateSounds(ModelFileView view, ChunkDataSource chunks)
            throws IOException {
        var decoded = new HashSet<Integer>();
        for (var sound : view.getCommon().sounds().values()) {
            if (!decoded.add(sound.streamId())) {
                continue;
            }
            try (var admitted = sound.readVerified(() -> false, chunks);
                 var stream = cc.sirrus.ysmlib.YsmRuntime.audio().open(admitted.encoded(), admitted.media())) {
                var pcm = ByteBuffer.allocate(8192);
                while (stream.read(pcm.clear()) != 0) {
                    // Decode every frame without retaining complete PCM.
                }
            } catch (AssetLoadException failure) {
                throw new IOException("Builtin sound cannot be decoded: " + sound.name(), failure);
            }
        }
    }

    private static boolean hasPbr(PBRTextureSet texture) {
        return texture.hasNormal() || texture.hasSpecular();
    }

    public record Result(Hash256 modelHash,
                         Map<DefaultAnimationKey, Hash256> animationHashes) {
    }
}
