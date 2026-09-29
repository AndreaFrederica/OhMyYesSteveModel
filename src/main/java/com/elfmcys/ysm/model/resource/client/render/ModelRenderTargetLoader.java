package com.elfmcys.ysm.model.resource.client.render;

import com.elfmcys.ysm.client.animation.molang.CustomMolangParser;
import com.elfmcys.ysm.client.model.locator.PlayerLocator;
import com.elfmcys.ysm.client.model.locator.FirstPersonLocator;
import com.elfmcys.ysm.client.model.locator.ProjectileLocator;
import com.elfmcys.ysm.client.model.locator.VehicleLocator;
import com.elfmcys.ysm.geckolib3.geo.render.built.GeoLocatorType;
import com.elfmcys.ysm.format.AssetLoadException;
import com.elfmcys.ysm.format.schema.file.AssetFileConstant;
import com.elfmcys.ysm.format.schema.file.ChunkDataSource;
import com.elfmcys.ysm.format.schema.file.PBRImageSources;
import com.elfmcys.ysm.format.schema.model.ModelFileView;
import com.elfmcys.ysm.format.schema.model.ModelManifestLookup;
import com.elfmcys.ysm.format.schema.model.views.RenderTargetView;
import com.elfmcys.ysm.geckolib3.core.molang.value.IValue;
import com.elfmcys.ysm.geckolib3.file.AnimationControllerFile;
import com.elfmcys.ysm.geckolib3.geo.render.built.GeoModel;
import com.elfmcys.ysm.model.catalog.content.DefaultAnimationKey;
import com.elfmcys.ysm.model.catalog.content.ModelContent;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.ModelRepresentation;
import com.elfmcys.ysm.model.resource.client.AnimationStore;
import com.elfmcys.ysm.model.resource.client.BakeProfile;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.model.resource.client.ModelResourceFailures;
import com.elfmcys.ysm.model.resource.client.PlayerModelVariant;
import com.elfmcys.ysm.model.resource.client.ResourceFailure;
import com.elfmcys.ysm.model.resource.client.SoundSource;
import com.elfmcys.ysm.model.resource.client.data.CommonAssetData;
import com.elfmcys.ysm.model.resource.client.data.ModelRenderTargetBuildInput;
import com.elfmcys.ysm.model.resource.client.data.PlayerModelData;
import com.elfmcys.ysm.model.resource.client.data.ProjectileModelData;
import com.elfmcys.ysm.model.resource.client.data.RenderTargetData;
import com.elfmcys.ysm.model.resource.client.data.VehicleModelData;
import com.elfmcys.ysm.proto.mixel.asset.model.ModelData;
import com.elfmcys.ysm.proto.mixel.asset.model.data.AnimationFile;
import com.elfmcys.ysm.proto.mixel.asset.strings.StringData;
import com.elfmcys.ysm.proto.mixel.common.Image;
import com.elfmcys.ysm.proto.mixel.manifest.asset.PBRTextureSet;
import com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTarget;
import com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind;
import com.elfmcys.ysm.util.FifoHashMap;
import com.elfmcys.ysm.util.ResourceTransaction;
import it.unimi.dsi.fastutil.objects.Object2ReferenceOpenHashMap;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import us.hebi.quickbuf.ProtoSource;

/** Builds the current Java model render target from a model container view. */
public final class ModelRenderTargetLoader {
    private static final int CURRENT_RAW_UV_VERSION = 29;

    private final BuildStage buildStage;
    private final BakeProfile bakeProfile;

    public ModelRenderTargetLoader(BakedModelCache bakedModels, BakedAnimationCache bakedAnimations,
                              Executor workers, DefaultAnimationRuntime defaultAnimations) {
        this.buildStage = new DefaultBuildStage(
                bakedModels, bakedAnimations, workers, defaultAnimations);
        this.bakeProfile = new BakeProfile(bakedModels.profileKey() + "/"
                + BakedAnimationCache.profileKey() + "/raw-uv-" + CURRENT_RAW_UV_VERSION);
    }

    public BakeProfile bakeProfile() {
        return bakeProfile;
    }

    public LoadResult load(BooleanSupplier cancelled, ModelContent content,
                    RenderTargetKey key, boolean defaultModel,
                    ModelResourceFailures resourceFailures) {
        return load(cancelled, content, key, defaultModel, false, resourceFailures);
    }

    public LoadResult load(BooleanSupplier cancelled, ModelContent content,
                    RenderTargetKey key, boolean defaultModel, boolean cacheOnly,
                    ModelResourceFailures resourceFailures) {
        var representation = content.representation();
        var chunks = content.chunks();
        try {
            requireActive(cancelled);
            var view = representation.view();
            final RenderTargetView target;
            final String selectedTexture;
            try {
                target = view.requireRenderTarget(key.targetId());
                selectedTexture = ModelManifestLookup.chooseTexture(
                        view.getManifest(), key.targetId(), key.selectedTexture());
            } catch (IllegalArgumentException failure) {
                throw AssetLoadException.content(
                        "Invalid model render target selection", failure);
            }
            var request = new LoadRequest(representation, view, target,
                    target.textureDescriptor(selectedTexture),
                    target.textureSources(cancelled, chunks, selectedTexture), key.targetId(), selectedTexture,
                    defaultModel, cacheOnly, resourceFailures);
            requireActive(cancelled);
            var definition = request.target().readDefinition(cancelled, chunks);
            var commonStrings = readCommonStrings(cancelled, request.view(), chunks);
            return new LoadResult.Ready(buildStage.build(
                    cancelled, new LoadedTarget(request, definition, commonStrings)));
        } catch (AssetLoadException | CancellationException failure) {
            return failure(failure);
        } catch (IOException failure) {
            return failure(AssetLoadException.content(
                    "Failed to load model render target", failure));
        } catch (IllegalArgumentException failure) {
            return failure(AssetLoadException.content(
                    "Failed to build model render target", failure));
        }
    }

    public static LoadResult.Failed failure(Throwable cause) {
        final ResourceFailure.Kind kind;
        if (cause instanceof CancellationException) {
            kind = ResourceFailure.Kind.TRANSIENT;
        } else if (cause instanceof AssetLoadException asset) {
            kind = asset.reason() == AssetLoadException.Reason.CONTENT
                    ? ResourceFailure.Kind.DETERMINISTIC
                    : ResourceFailure.Kind.TRANSIENT;
        } else {
            throw new IllegalArgumentException("Unclassified asset failure", cause);
        }
        return new LoadResult.Failed(new ResourceFailure(
                kind, cause));
    }

    private static StringData readCommonStrings(
            BooleanSupplier cancelled, ModelFileView view, ChunkDataSource chunks)
            throws IOException {
        var common = view.getManifest().commonAssets();
        if (common.stringsBlobId() > 0) {
            return view.getCommon().readStringData(cancelled, chunks);
        }
        return null;
    }

    private static void requireActive(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("Model render target load was cancelled");
        }
    }

    static <T extends AutoCloseable> T publishCandidate(
            BooleanSupplier cancelled, ResourceTransaction resources, T candidate) {
        resources.commit();
        try {
            requireActive(cancelled);
            return candidate;
        } catch (RuntimeException | Error error) {
            try {
                candidate.close();
            } catch (Exception closeError) {
                error.addSuppressed(closeError);
            }
            throw error;
        }
    }

    @FunctionalInterface
    interface BuildStage {
        ModelCandidate build(BooleanSupplier cancelled, LoadedTarget input) throws IOException;
    }

    public sealed interface LoadResult {
        record Ready(ModelCandidate candidate) implements LoadResult {
            public Ready {
                Objects.requireNonNull(candidate, "candidate");
            }
        }

        record Failed(ResourceFailure failure) implements LoadResult {
            public Failed {
                Objects.requireNonNull(failure, "failure");
            }
        }
    }

    record LoadRequest(ModelRepresentation representation, ModelFileView view, RenderTargetView target,
                       PBRTextureSet textureProto, PBRImageSources textureSources,
                       String targetId, String selectedTexture, boolean defaultModel,
                       boolean cacheOnly,
                       ModelResourceFailures resourceFailures) {
    }

    record LoadedTarget(LoadRequest request, ModelData definition,
                        StringData commonStrings) {
    }

    private record DefaultBuildStage(BakedModelCache bakedModels, BakedAnimationCache bakedAnimations,
                                     Executor workers, DefaultAnimationRuntime defaultAnimations)
            implements BuildStage {
        @Override
        public ModelCandidate build(BooleanSupplier cancelled, LoadedTarget input) throws IOException {
            var request = input.request();
            var representation = request.representation();
            var view = request.view();
            var target = request.target();
            var textureProto = request.textureProto();
            var textureSources = request.textureSources();
            var targetId = request.targetId();
            var selectedTexture = request.selectedTexture();
            var definition = input.definition();
            requireActive(cancelled);
            return buildNow(cancelled, representation, view, target, textureProto,
                    textureSources, targetId, selectedTexture, definition,
                    input.commonStrings(), request.defaultModel(), request.cacheOnly(),
                    request.resourceFailures());
        }

        private ModelCandidate buildNow(BooleanSupplier cancelled,
                                           ModelRepresentation representation, ModelFileView view,
                                           RenderTargetView target, PBRTextureSet textureProto,
                                           PBRImageSources textureSources, String targetId, String selectedTexture,
                                           ModelData definition,
                                           StringData commonStrings,
                                           boolean defaultModel,
                                           boolean cacheOnly,
                                           ModelResourceFailures resourceFailures) throws IOException {
            try (var resources = new ResourceTransaction()) {
                var textureResource = targetId + "/" + selectedTexture + "/";
                var textures = resources.own(new PreparedTextureSet(
                        textureSources, textureResource, resourceFailures));
                var textureHash = textureHash(view, textureProto.uv());
                var definitionHash = definitionHash(view, target.descriptor().blobId());
                var controllers = controllerFiles(definition);
                Iterable<? extends Map.Entry<String, AnimationFile>> animationFiles =
                        definition.animationFiles().object2ObjectEntrySet();
                final RenderTargetData targetData;
                switch (target.kind()) {
                case RENDER_TARGET_KIND_PLAYER -> {
                    var main = resources.own(bakeModel(representation, targetId + "/" + selectedTexture + "/main",
                            textureHash, geoModel(definition, "main"), textures, target, textureProto,
                            defaultModel, cacheOnly, PlayerLocator.get()));
                    var arm = resources.own(bakeModel(representation, targetId + "/" + selectedTexture + "/arm",
                            textureHash, geoModel(definition, "arm"), textures, target, textureProto,
                            defaultModel, cacheOnly, FirstPersonLocator.get()));
                    var mainFiles = new ArrayList<Map.Entry<String, AnimationFile>>();
                    var firstPersonFiles = new ArrayList<Map.Entry<String, AnimationFile>>();
                    for (var file : animationFiles) {
                        (file.getKey().equals("fp_arm") ? firstPersonFiles : mainFiles).add(file);
                    }
                    var animations = resources.own(loadAnimations(representation, target.descriptor(), targetId,
                            "main", definitionHash, mainFiles, defaultModel,
                            resourceFailures));
                    var firstPersonAnimations = resources.own(loadAnimations(representation, target.descriptor(),
                            targetId, "fp_arm", definitionHash, firstPersonFiles,
                            defaultModel, resourceFailures));
                    var variants = new FifoHashMap<>(new String[]{selectedTexture},
                            new PlayerModelVariant[]{new PlayerModelVariant(main, arm)});
                    targetData = new PlayerModelData(variants, animations, firstPersonAnimations,
                            List.copyOf(controllers.values()));
                }
                case RENDER_TARGET_KIND_PROJECTILE, RENDER_TARGET_KIND_VEHICLE -> {
                    var baked = resources.own(bakeModel(representation, targetId + "/" + selectedTexture,
                            textureHash, geoModel(definition, "main"), textures, target, textureProto,
                            defaultModel, cacheOnly,
                            target.kind() == RenderTargetKind.RENDER_TARGET_KIND_PROJECTILE
                                    ? ProjectileLocator.get() : VehicleLocator.get()));
                    var animations = resources.own(loadAnimations(representation, target.descriptor(), targetId,
                            "main", definitionHash, animationFiles,
                            defaultModel, resourceFailures));
                    var controller = controllers.values().stream().findFirst().orElse(null);
                    targetData =
                            target.kind() == RenderTargetKind.RENDER_TARGET_KIND_PROJECTILE
                                    ? new ProjectileModelData(baked, animations, controller)
                                    : new VehicleModelData(baked, animations, controller);
                }
                default -> throw AssetLoadException.content(
                        "Unsupported render target kind: " + target.kind());
                }

                var common = commonAssets(representation, view, commonStrings);
                var data = new ModelRenderTargetBuildInput(targetId, targetData, common,
                        view.getMetadata());
                textures.prepare(cancelled);
                final ModelRenderTarget targetResult;
                try {
                    targetResult = ModelRenderTargetAssembler.build(representation.modelId(), data);
                } catch (IllegalArgumentException error) {
                    throw AssetLoadException.content(
                            "Invalid assembled model render target: " + targetId, error);
                }
                return publishCandidate(cancelled, resources,
                        new ModelCandidate(targetResult, textures));
            }
        }

        private static CommonAssetData commonAssets(ModelRepresentation representation,
                                                    ModelFileView view,
                                                    StringData source)
                throws IOException {
            var sounds = new LinkedHashMap<String, SoundSource>();
            for (var entry : view.getCommon().sounds().entrySet()) {
                sounds.put(entry.getKey(), new SoundSource(
                        representation.identity(), entry.getValue()));
            }
            var functions = new Object2ReferenceOpenHashMap<String,
                    IValue>();
            if (source != null && !source.userFunctions().isEmpty()) {
                var parser = CustomMolangParser.rentInstance();
                try {
                    for (var function : source.userFunctions()) {
                        try {
                            functions.put(function.name(),
                                    parser.parseExpression(function.body().source(), false));
                        } catch (RuntimeException error) {
                            throw AssetLoadException.content(
                                    "Invalid model function: " + function.name(), error);
                        }
                    }
                } finally {
                    CustomMolangParser.returnInstance(parser);
                }
            }
            return new CommonAssetData(Map.copyOf(sounds), functions);
        }

        private static Map<String, AnimationControllerFile> controllerFiles(
                ModelData source) throws IOException {
            var result = new LinkedHashMap<String, AnimationControllerFile>();
            if (!source.animationControllers().isEmpty()) {
                for (var entry : source.animationControllers().object2ObjectEntrySet()) {
                    try {
                        result.put(entry.getKey(),
                                AnimationProtoMapper.controllerFile(entry.getValue()));
                    } catch (RuntimeException error) {
                        throw AssetLoadException.content(
                                "Invalid animation controller: " + entry.getKey(), error);
                    }
                }
            }
            return result;
        }

        private GeoModel bakeModel(ModelRepresentation representation, String resourceName, byte[] textureHash,
                                   com.elfmcys.ysm.proto.mixel.asset.model.data.GeoModel geo,
                                   BakedModelCache.TexturePixelsSupplier texturePixels,
                                   RenderTargetView target,
                                   PBRTextureSet textureProto,
                                   boolean defaultModel, boolean cacheOnly, GeoLocatorType locatorType) throws IOException {
            if (defaultModel) {
                return BakedModelCache.bakeResident(geo, resourceName, texturePixels,
                        CURRENT_RAW_UV_VERSION,
                        target.descriptor().settings().forceCulling(),
                        false, hasPbr(textureProto), locatorType);
            }
            if (cacheOnly) {
                return bakedModels.loadExisting(representation.containerId(),
                        resourceName, textureHash, locatorType);
            }
            return bakedModels.loadOrBake(representation.containerId(),
                    resourceName, textureHash, geo, texturePixels, CURRENT_RAW_UV_VERSION,
                    target.descriptor().settings().forceCulling(), false,
                    hasPbr(textureProto), locatorType);
        }

        private AnimationStore loadAnimations(ModelRepresentation representation,
                                              RenderTarget target,
                                              String targetId, String animationSet,
                                              Hash256 definitionHash,
                                              Iterable<? extends Map.Entry<String,
                                                      AnimationFile>>
                                                      animationFiles,
                                              boolean defaultModel,
                                              ModelResourceFailures resourceFailures) throws IOException {
            if (defaultModel) {
                return BakedAnimationCache.bindResident(animationFiles,
                        (animation, bound) -> defaultAnimations.requireCurrent(
                                target, animationSet, animation));
            }
            var domain = DefaultAnimationKey.domain(target, animationSet);
            var fallback = defaultAnimations.fallback(domain);
            return bakedAnimations.loadOrBake(representation.containerId(),
                    targetId, animationSet, definitionHash, animationFiles, fallback,
                    name -> resourceFailures.animation(targetId + "/" + animationSet + "/" + name));
        }
    }

    static com.elfmcys.ysm.proto.mixel.asset.model.data.GeoModel geoModel(ModelData source, String name)
            throws IOException {
        for (var entry : source.geoModels().object2ObjectEntrySet()) {
            if (entry.getKey().equals(name)) {
                try {
                    return com.elfmcys.ysm.proto.mixel.asset.model.data.GeoModel.parseFrom(
                            ProtoSource.newInstance(entry.getValue()));
                } catch (IOException error) {
                    throw AssetLoadException.content(
                            "Invalid geo model named " + name, error);
                }
            }
        }
        throw AssetLoadException.content("Model data contains no geo model named " + name);
    }

    private static byte[] textureHash(ModelFileView view,
                                      Image image)
            throws IOException {
        var chunk = view.getFileView().getAssetView().getChunkInfo(AssetFileConstant.BLOB_CHUNK_PREFIX
                + image.blobId());
        if (chunk == null || chunk.hash() == null || chunk.hash().length != Hash256.SIZE) {
            throw AssetLoadException.content("Texture chunk contains no content hash");
        }
        return chunk.hash();
    }

    private static Hash256 definitionHash(ModelFileView view, int blobId) throws IOException {
        var chunk = view.getFileView().getAssetView().getChunkInfo(AssetFileConstant.BLOB_CHUNK_PREFIX + blobId);
        if (chunk == null || chunk.hash() == null || chunk.hash().length != Hash256.SIZE) {
            throw AssetLoadException.content(
                    "Render target definition contains no content hash");
        }
        return new Hash256(chunk.hash());
    }

    private static boolean hasPbr(PBRTextureSet texture) {
        return texture.hasNormal() || texture.hasSpecular();
    }

    public static final class ModelCandidate implements AutoCloseable {
        private final ModelRenderTarget target;
        private final PreparedTextureSet textures;
        private final AtomicBoolean closed = new AtomicBoolean();
        private boolean published;

        ModelCandidate(ModelRenderTarget target, PreparedTextureSet textures) {
            this.target = Objects.requireNonNull(target, "target");
            this.textures = textures;
        }

        static ModelCandidate testing(ModelRenderTarget target) {
            return new ModelCandidate(target, null);
        }

        public ModelRenderTarget publish() throws Exception {
            return publish(HostTexturePublisher.production());
        }

        ModelRenderTarget publish(HostTexturePublisher publisher) throws Exception {
            if (closed.get() || published) {
                throw new IllegalStateException("Model candidate is no longer publishable");
            }
            if (textures == null) {
                published = true;
                return target;
            }
            var binding = publisher.publish(textures);
            try {
                target.adoptTexture(binding.base(), binding);
            } catch (Throwable error) {
                binding.close();
                throw error;
            }
            published = true;
            return target;
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            if (textures != null) {
                textures.close();
            }
            target.close();
        }

        void reject(Consumer<ModelRenderTarget> rejectedTargetCloser) {
            if (textures == null) {
                if (closed.compareAndSet(false, true)) {
                    rejectedTargetCloser.accept(target);
                }
            } else {
                close();
            }
        }
    }
}
