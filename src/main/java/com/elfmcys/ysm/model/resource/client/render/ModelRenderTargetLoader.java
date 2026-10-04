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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import us.hebi.quickbuf.ProtoSource;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import com.elfmcys.ysm.model.resource.client.GeneralMeshModelResources;

/** Builds the current Java model render target from a model container view. */
public final class ModelRenderTargetLoader {
    private static final int CURRENT_RAW_UV_VERSION = 29;
    private static final AtomicLong PROGRESS_ID = new AtomicLong();
    private static final AtomicReference<ProgressState> PROGRESS = new AtomicReference<>(
            new ProgressState(0, ModelLoadProgress.IDLE));

    private record ProgressState(long id, ModelLoadProgress value) {}

    private static final class ProgressHandle implements AutoCloseable {
        private final long id;
        private final String model;
        private boolean closed;

        private ProgressHandle(long id, String model) { this.id = id; this.model = model; }
        void update(String stage) { update(stage, stage, model, 0, 0, 0, 0); }
        void update(String stage, long completed, long total) {
            update(stage, stage, model, completed, total, 0, 0);
        }
        void update(String stage, String operation, String currentFile,
                    long completed, long total, int completedItems, int totalItems) {
            if (!closed) PROGRESS.set(new ProgressState(id, new ModelLoadProgress(
                    true, stage, operation, currentFile == null ? "" : currentFile, model,
                    Math.max(0, completed), Math.max(0, total),
                    Math.max(0, completedItems), Math.max(0, totalItems))));
        }
        @Override public void close() {
            if (!closed) {
                closed = true;
                // A newer concurrent load must remain visible.
                PROGRESS.updateAndGet(current -> current.id() == id
                        ? new ProgressState(id, ModelLoadProgress.IDLE) : current);
            }
        }
    }

    public static ModelLoadProgress progress() { return PROGRESS.get().value(); }

    private static String sceneCacheOperation(cc.sirrus.ysmlib.SceneDiskCache.Event event) {
        String prefix="gui.yes_steve_model.loading.cache.";
        return net.minecraft.network.chat.Component.translatable(prefix+event.stage()).getString()+" · "
                +net.minecraft.network.chat.Component.translatable(prefix+event.state()).getString()
                +" ("+event.elapsedMillis()+" ms)"
                +(event.state().equals("building")||event.state().equals("uncached")?" · "+event.reason():"");
    }

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
        var progress = beginProgress(representation.modelId().toString());
        try {
            progress.update("inspect", "inspect_manifest", key.targetId(), 0, 0, 0, 1);
            requireActive(cancelled);
            var view = representation.view();
            final RenderTargetView target;
            final String selectedTexture;
            try {
                target = view.requireRenderTarget(key.targetId());
                selectedTexture = ModelManifestLookup.chooseTexture(
                        view, key.targetId(), key.selectedTexture());
            } catch (IllegalArgumentException failure) {
                throw AssetLoadException.content(
                        "Invalid model render target selection", failure);
            }
            if (view.schema() == com.elfmcys.ysm.format.schema.model.ModelSchema.GENERAL_MESH) {
                progress.update("general_mesh", "read_scene_package", key.targetId(), 0, 0, 0, 4);
                requireActive(cancelled);
                var packageSource=target.readScenePackage(cancelled,chunks,GeneralMeshModelResources.DEFAULT_LIMITS);
                progress.update("general_mesh", "prepare_mesh", packageSource.model().path(), 0, 0, 1, 4);
                var payload=GeneralMeshModelResources.prepare(packageSource,GeneralMeshModelResources.DEFAULT_LIMITS,cancelled,
                        GeneralSceneCache.get(),event->progress.update("general_mesh",sceneCacheOperation(event),event.item(),
                                0,0,switch(event.stage()){case "documents"->1;case "geometry","surface"->2;case "image"->3;default->4;},5));
                progress.update("general_mesh", "prepare_common_assets", key.targetId(), 0, 0, 3, 4);
                try {
                    var commonData=DefaultBuildStage.commonAssets(representation,view,null);
                    var common=new com.elfmcys.ysm.model.resource.client.CommonAsset(
                            commonData.sounds(),new Object2ReferenceOpenHashMap<>(commonData.userFunctions()),
                            new Object2ReferenceOpenHashMap<>());
                    var meshTarget=new ModelRenderTarget(representation.modelId(),key.targetId(),payload,common,view.getMetadata());
                    progress.update("ready", "publish_target", key.targetId(), 0, 0, 4, 4);
                    return new LoadResult.Ready(ModelCandidate.testing(meshTarget));
                } catch(RuntimeException|Error failure) {
                    // Prepared resources have no GL publication yet; cleanup is owned by the render owner once a candidate exists.
                    throw failure;
                }
            }
            var request = new LoadRequest(representation, view, target,
                    target.textureDescriptor(selectedTexture),
                    target.textureSources(cancelled, chunks, selectedTexture), key.targetId(), selectedTexture,
                    defaultModel, cacheOnly, resourceFailures);
            requireActive(cancelled);
            progress.update("legacy", "read_definition", key.targetId(), 0, 0, 1, 4);
            var definition = request.target().readDefinition(cancelled, chunks);
            var commonStrings = readCommonStrings(cancelled, request.view(), chunks);
            progress.update("legacy", "build_legacy_mesh_and_textures", key.targetId(), 0, 0, 2, 4);
            var ready = new LoadResult.Ready(buildStage.build(
                    cancelled, new LoadedTarget(request, definition, commonStrings)));
            progress.update("ready", "publish_target", key.targetId(), 0, 0, 4, 4);
            return ready;
        } catch (AssetLoadException | CancellationException failure) {
            return failure(failure);
        } catch (IOException failure) {
            return failure(AssetLoadException.content(
                    "Failed to load model render target", failure));
        } catch (IllegalArgumentException failure) {
            return failure(AssetLoadException.content(
                    "Failed to build model render target", failure));
        } finally {
            progress.close();
        }
    }

    private static ProgressHandle beginProgress(Hash256 model) {
        return beginProgress(model.toString());
    }

    private static ProgressHandle beginProgress(String model) {
        long id = PROGRESS_ID.incrementAndGet();
        PROGRESS.set(new ProgressState(id, new ModelLoadProgress(
                true, "queued", "queued", model, model, 0, 0, 0, 0)));
        return new ProgressHandle(id, model);
    }

    public static LoadResult.Failed failure(Throwable cause) {
        final ResourceFailure.Kind kind;
        if (ResourceFailure.isCancellation(cause)) {
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
            if (target.generalMeshResources() != null) {
                target.generalMeshResources().publish(closed::get);
                published = true;
                return target;
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
