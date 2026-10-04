package com.elfmcys.ysm.model.resource.client;

import cc.sirrus.ysmlib.YsmRuntime;
import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import cc.sirrus.ysmlib.scene.mmd.MmdMaterials;
import cc.sirrus.ysmlib.scene.vrm.VrmDocument;
import cc.sirrus.ysmlib.scene.vrm.VrmMaterials;
import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.client.renderer.*;
import com.elfmcys.ysm.model.resource.client.render.*;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import java.io.IOException;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.ToIntFunction;
import java.util.concurrent.Semaphore;

/** One target payload: worker-prepared immutable assets and render-owner publication, shared through existing leases. */
public final class GeneralMeshModelResources implements RenderTargetResources, AutoCloseable {
    /** General scenes may contain several 4K source images; keep this larger budget explicit and bounded. */
    public static final ReadLimits DEFAULT_LIMITS = new ReadLimits(1024 * 1024 * 1024, 100_000_000, 4 * 1024 * 1024);
    /** Large scene decoding is intentionally serialized so catalog workers cannot multiply peak heap use. */
    private static final Semaphore PREPARE_SLOT = new Semaphore(1);
    private final ScenePackageAssets assets;
    private final ReadLimits limits;
    private final GeometryFrame variants;
    private final SceneAsset gltf;
    private final VrmDocument vrm;
    private final VrmMaterials.Frame vrmInitialMaterials;
    private final MmdMaterials mmd;
    private final MmdTextureBindings mmdTextures;
    private final List<SceneAnimation> animations;
    private final SceneModelProfile profile;
    private PreparedSceneTextures preparedTextures;
    private GltfRenderResources gltfPrograms;
    private VrmRenderResources vrmPrograms;
    private MmdRenderResources mmdPrograms;
    private SceneTexturePublication.Binding<?> binding;
    private Map<PreparedSceneTextures.Key,Integer> textureIds;
    private boolean closed;

    private GeneralMeshModelResources(ScenePackageAssets assets, ReadLimits limits, GeometryFrame variants,
                                      SceneAsset gltf, VrmDocument vrm, VrmMaterials.Frame vrmInitialMaterials,
                                      MmdMaterials mmd, MmdTextureBindings mmdTextures,
                                      PreparedSceneTextures preparedTextures,List<SceneAnimation> animations) {
        this.assets=assets;this.limits=limits;this.variants=variants;this.gltf=gltf;this.vrm=vrm;
        this.vrmInitialMaterials=vrmInitialMaterials;this.mmd=mmd;
        this.mmdTextures=mmdTextures;this.preparedTextures=preparedTextures;
        this.animations=List.copyOf(animations);
        var saved=assets.source().files().get(SceneModelProfile.PACKAGE_PATH);
        double low=Double.POSITIVE_INFINITY,high=Double.NEGATIVE_INFINITY;
        for(var draw:variants.draws()) for(var primitive:draw.geometry().primitives()) {
            var position=primitive.attributes().get("POSITION");if(position==null) continue;
            var indices=primitive.indices();for(int i=0;i<indices.size();i++) {
                int offset=indices.get(i)*position.components();var values=position.values();
                var p=draw.world().transformPoint(new Vec3(values.get(offset),values.get(offset+1),values.get(offset+2)));
                double y=switch(variants.coordinates().upAxis()) { case "X" -> p.x();case "Z" -> p.z();default -> p.y(); };
                low=Math.min(low,y);high=Math.max(high,y);
            }
        }
        this.profile=saved!=null?YsmRuntime.scenes().readModelProfile(saved):SceneModelProfile.defaults(assets.source().settings().metersPerUnit(),
            Double.isFinite(high-low)&&high>low?high-low:1,Double.isFinite(low)?low:0).withBones(SceneSkeleton.suggest(SceneSkeleton.of(assets)));
        SceneSkeleton.validate(SceneSkeleton.of(assets),profile.bones());
        profile.validateAnimations(assets.source(),animations);
    }
    public SceneModelProfile profile() { return profile; }

    /** CPU work only. Playback probing uses a private, immediately closed Lib session. */
    public static GeneralMeshModelResources prepare(ScenePackage source, ReadLimits limits, BooleanSupplier cancelled) throws IOException {
        return prepare(source,limits,cancelled,null,event->{});
    }
    public static GeneralMeshModelResources prepare(ScenePackage source, ReadLimits limits, BooleanSupplier cancelled,
            cc.sirrus.ysmlib.SceneDiskCache cache,java.util.function.Consumer<cc.sirrus.ysmlib.SceneDiskCache.Event> progress) throws IOException {
        boolean acquired = false;
        try {
            PREPARE_SLOT.acquire();
            acquired = true;
            return prepareExclusive(source, limits, cancelled,cache,progress);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for the scene memory gate", interrupted);
        } finally {
            if (acquired) PREPARE_SLOT.release();
        }
    }

    private static GeneralMeshModelResources prepareExclusive(ScenePackage source, ReadLimits limits, BooleanSupplier cancelled,
            cc.sirrus.ysmlib.SceneDiskCache cache,java.util.function.Consumer<cc.sirrus.ysmlib.SceneDiskCache.Event> progress) throws IOException {
        active(cancelled);
        var services=YsmRuntime.scenes();
        var session=cache==null?null:cache.session(services,limits,event->{
            progress.accept(event);
            if(!event.state().equals("checking")) YesSteveModel.LOGGER.info("General scene cache source={} item={} stage={} state={} elapsedMs={} reason={}",
                    source.model().path(),event.item(),event.stage(),event.state(),event.elapsedMillis(),event.reason());
        },cancelled);
        var assets=session==null?services.loadPackage(source,limits):session.loadPackage(source);
        requireDeclaredFormat(source, assets);
        YesSteveModel.LOGGER.info("Prepared general scene source={} declaredFormat={} assetType={} animations={}",
                source.model().path(), source.model().format(), assets.model().getClass().getSimpleName(),
                source.animations().size());
        active(cancelled);
        SceneAsset gltf=null;VrmDocument vrm=null;VrmMaterials.Frame vrmInitialMaterials=null;
        MmdMaterials mmd=null;MmdTextureBindings mmdTextures=null;
        if(assets.model() instanceof ScenePackageAssets.Gltf document) gltf=document.value().scene();
        else if(assets.model() instanceof ScenePackageAssets.Vrm document) vrm=document.value();
        else if(assets.model() instanceof ScenePackageAssets.Fbx document)
            gltf=session==null?FbxSurfaceAdapter.scene(document.value(),document.assets()):session.scene(assets,"fbx-surface-1",()->FbxSurfaceAdapter.scene(document.value(),document.assets()));
        else if(assets.model() instanceof ScenePackageAssets.Pmx document) mmd=services.materials(document.value());
        else if(assets.model() instanceof ScenePackageAssets.Pmd document) mmd=services.materials(document.value());
        else throw new IllegalArgumentException("Source requires its dedicated host material renderer: "+source.model().format());
        final cc.sirrus.ysmlib.SceneDiskCache.Rest initial;
        if(session!=null) initial=session.rest(assets);
        else try(var player=services.playback(assets,ScenePackagePlayback.Selection.REST,ScenePackagePlayback.Settings.preview().withPhysics(false),limits)) {
            var frame=player.seek(0);
            initial=new cc.sirrus.ysmlib.SceneDiskCache.Rest(frame.thirdPerson(),frame.firstPerson(),
                    frame.details() instanceof ScenePackagePlayback.Vrm v?v.materials():null,
                    frame.details() instanceof ScenePackagePlayback.Mmd m?m.value().pose().diagnostics():List.of(),services.animations(assets,30));
        }
            for (var diagnostic : initial.diagnostics()) {
                var level = diagnostic.severity() == CompatibilityReport.Severity.ERROR ? "error" : "warning";
                YesSteveModel.LOGGER.warn("MMD compatibility {} source={} location={} message={}",
                        level, source.model().path(), diagnostic.location(), diagnostic.message());
            }
        if (vrm != null) {
            if (initial.vrmMaterials()==null)
                throw new IllegalArgumentException("VRM playback did not publish VRM material state");
            vrmInitialMaterials = initial.vrmMaterials();
        }
        active(cancelled);
        var draws=new ArrayList<>(initial.thirdPerson().draws());draws.addAll(initial.firstPerson().draws());
        var variants=new GeometryFrame(0,initial.thirdPerson().coordinates(),draws);
        var requests=new LinkedHashSet<PreparedSceneTextures.Key>();
        String owner=source.model().path();
        if(gltf!=null) {
            var used=new HashSet<Integer>();
            for(var draw:variants.draws()) for(var primitive:draw.geometry().primitives()) {
                int index=primitive.material();
                if(index>=0 && used.add(index)) requests.addAll(new GltfTextureBindings(owner,gltf,gltf.materials().get(index)).requests());
            }
        } else if (vrm != null) {
            var used=new HashSet<Integer>();
            for(var draw:variants.draws()) for(var primitive:draw.geometry().primitives()) {
                int index=primitive.material();
                if(index>=0 && used.add(index)) requests.addAll(new VrmTextureBindings(owner,vrm.scene(),vrmInitialMaterials.materials().get(index)).requests());
            }
        } else {
            mmdTextures=new MmdTextureBindings(owner,mmd);requests.addAll(mmdTextures.requests());
        }
        var images=session==null?services.images(assets,limits):session.images(assets);active(cancelled);
        long downsampled=images.images().values().stream()
                .filter(image -> image.metadata().containsKey("downsampleFactor")).count();
        if (downsampled > 0) {
            YesSteveModel.LOGGER.info("Downsampled general scene textures source={} count={} details={}",
                    source.model().path(), downsampled,
                    images.images().values().stream().map(image -> image.metadata().get("downsampleFactor"))
                            .filter(Objects::nonNull).toList());
        }
        var prepared=PreparedSceneTextures.prepare(images,requests,limits,cancelled,session);
        return new GeneralMeshModelResources(assets,limits,variants,gltf,vrm,vrmInitialMaterials,mmd,mmdTextures,prepared,initial.animations());
    }
    public ScenePackageAssets assets() { return assets; }
    public ReadLimits limits() { return limits; }
    public SceneAsset gltf() { return gltf; }
    public VrmDocument vrm() { return vrm; }
    public VrmMaterials.Frame vrmInitialMaterials() { return vrmInitialMaterials; }
    public MmdMaterials mmd() { return mmd; }
    public List<SceneAnimation> animations() { return animations; }

    public void publish(BooleanSupplier cancelled) throws Exception {
        publish(MinecraftSceneTextureHost.INSTANCE,
                id->Minecraft.getInstance().getTextureManager().getTexture(id).getId(),cancelled);
    }
    /** Same transaction with an explicit host for graphics verification and alternate host adapters. */
    public <I> void publish(SceneTexturePublication.Host<I> host, ToIntFunction<I> ids, BooleanSupplier cancelled) throws Exception {
        long started=System.nanoTime();
        RenderSystem.assertOnRenderThread();
        if(closed || binding!=null) throw new IllegalStateException("Scene target is no longer publishable");
        active(cancelled);
        GltfRenderResources newGltf=null;VrmRenderResources newVrm=null;MmdRenderResources newMmd=null;SceneTexturePublication.Binding<I> newBinding=null;
        try {
            if(gltf!=null) newGltf=new GltfRenderResources(assets.source().model().path(),gltf,variants);
            else if(vrm!=null) newVrm=new VrmRenderResources(assets.source().model().path(),vrm.scene(),variants,vrmInitialMaterials);
            else newMmd=new MmdRenderResources(mmd,mmdTextures);
            active(cancelled);
            newBinding=SceneTexturePublication.publish(preparedTextures,host,cancelled);
            var resolved=new LinkedHashMap<PreparedSceneTextures.Key,Integer>();
            for(var entry:newBinding.mappings().entrySet()) {
                int id=ids.applyAsInt(entry.getValue());
                if(id==0 || !org.lwjgl.opengl.GL11C.glIsTexture(id)) throw new IllegalStateException("Scene host returned an unpublished texture");
                resolved.put(entry.getKey(),id);
            }
            active(cancelled);
            gltfPrograms=newGltf;vrmPrograms=newVrm;mmdPrograms=newMmd;textureIds=Map.copyOf(resolved);binding=newBinding;
            preparedTextures=null;
            YesSteveModel.LOGGER.info("General scene GPU publication source={} elapsedMs={} textures={}",assets.source().model().path(),(System.nanoTime()-started)/1_000_000,textureIds.size());
        } catch(Exception|Error failure) {
            for(AutoCloseable resource:new AutoCloseable[]{newBinding,newMmd,newVrm,newGltf}) if(resource!=null) {
                try { resource.close(); } catch(Exception cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
    }
    public GltfRenderResources gltfPrograms() { requirePublished();return gltfPrograms; }
    public VrmRenderResources vrmPrograms() { requirePublished();return vrmPrograms; }
    public MmdRenderResources mmdPrograms() { requirePublished();return mmdPrograms; }
    public int textureId(PreparedSceneTextures.Key key) {
        requirePublished();var id=textureIds.get(key);
        if(id==null) throw new IllegalArgumentException("Texture is outside this scene target publication: "+key);return id;
    }
    public void requirePublished() {
        RenderSystem.assertOnRenderThread();
        if(closed || binding==null) throw new IllegalStateException("Scene target is not published or has been closed");
    }
    @Override public void close() {
        RenderSystem.assertOnRenderThread();if(closed) return;closed=true;preparedTextures=null;
        RuntimeException failure=null;
        for(AutoCloseable resource:new AutoCloseable[]{binding,mmdPrograms,vrmPrograms,gltfPrograms}) if(resource!=null) {
            try { resource.close(); } catch(Exception error) {
                if(failure==null) failure=new IllegalStateException("Cannot release scene target publication",error);else failure.addSuppressed(error);
            }
        }
        binding=null;mmdPrograms=null;vrmPrograms=null;gltfPrograms=null;textureIds=null;
        if(failure!=null) throw failure;
    }
    private static void active(BooleanSupplier cancelled) {
        if(cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException("Scene target preparation/publication cancelled");
    }

    private static void requireDeclaredFormat(ScenePackage source, ScenePackageAssets assets) {
        var actual = assets.model();
        var valid = switch (source.model().format()) {
            case PMX -> actual instanceof ScenePackageAssets.Pmx;
            case PMD -> actual instanceof ScenePackageAssets.Pmd;
            case FBX -> actual instanceof ScenePackageAssets.Fbx;
            case GLTF -> actual instanceof ScenePackageAssets.Gltf;
            case VRM -> actual instanceof ScenePackageAssets.Vrm;
            default -> true;
        };
        if (!valid) {
            throw new IllegalArgumentException("Scene package format mismatch: declared "
                    + source.model().format() + " but decoded " + actual.getClass().getSimpleName());
        }
    }
}
