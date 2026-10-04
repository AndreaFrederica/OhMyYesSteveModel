package com.elfmcys.ysm.client.renderer;

import cc.sirrus.ysmlib.YsmRuntime;
import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.AssetFormatException;
import com.elfmcys.ysm.model.resource.client.GeneralMeshModelResources;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.Objects;

/** Consumer-owned playback, physics and streaming buffers. The consumer's existing lease keeps the target alive. */
public final class GeneralMeshInstance implements AutoCloseable {
    private final GeneralMeshModelResources target;
    private ScenePackagePlayback.Settings settings;
    private SceneModelProfile profile;
    private final GltfMeshRenderer gltf;
    private final VrmMeshRenderer vrm;
    private final MmdMeshRenderer mmd;
    private final boolean live;
    private ScenePackagePlayback player;
    private AnimationPreview<ScenePackagePlayback.Frame> timeline;
    private ScenePackagePlayback.Frame liveFrame;
    private PreviewBounds cachedBounds;
    private long lastSequence;
    private boolean hasSequence;
    private boolean closed;
    private long preparationGeneration;
    private boolean preparing;
    private final GeneralMeshPhysicsHost physicsHost=new GeneralMeshPhysicsHost();
    public void updateHostPhysics(com.elfmcys.ysm.client.entity.CustomEntity<?> owner,float partialTicks,double seconds) {
        requireOpen();if(live&&(mmd!=null||vrm!=null))player.physicsEnvironment(physicsHost.sample(owner,this,partialTicks,seconds));
    }
    private String preparationError="";
    public MmdMeshRenderer.UploadStatistics mmdUploadStatistics(){return mmd==null?null:mmd.uploadStatistics();}
    public long mmdGpuDispatches(){return mmd==null?0:mmd.gpuDispatches();}
    public long mmdGpuUploadedBytes(){return mmd==null?0:mmd.gpuUploadedBytes();}
    public boolean preparing() { return preparing; }
    public String preparationError() { return preparationError; }
    public int debugBone=-1;
    public boolean debugSkeleton;
    public record BonePoint(int index,int parent,float x,float y) {}
    public java.util.List<BonePoint> projectedBones=java.util.List.of();
    public void look(float yaw,float pitch) {
        if(!selection().sourceId().startsWith("@ysm/generated/")) return;
        var rotations=new java.util.HashMap<Integer,Rotation>();
        for(String role:java.util.List.of("neck","head")) {
            var b=profile.bones().get(role);if(b==null || b.index()<0)continue;
            double portion=role.equals("neck")?.3:profile.bones().containsKey("neck")?.7:1;
            var basis=Rotation.axisAngle(new Vec3(0,0,1),Math.toRadians(b.roll())).multiply(Rotation.axisAngle(new Vec3(0,1,0),Math.toRadians(b.yaw())))
                .multiply(Rotation.axisAngle(new Vec3(1,0,0),Math.toRadians(b.pitch())));
            var q=Rotation.axisAngle(new Vec3(0,1,0),Math.toRadians(Math.max(-80,Math.min(80,yaw)))*portion*b.weight())
                .multiply(Rotation.axisAngle(new Vec3(1,0,0),Math.toRadians(Math.max(-60,Math.min(60,pitch)))*portion*b.weight()));
            rotations.put(b.index(),basis.multiply(q).multiply(basis.inverse()));
        }
        player.boneRotations(rotations);
    }


    public GeneralMeshInstance(GeneralMeshModelResources target,ScenePackagePlayback.Selection selection,
                               AnimationPreview.Range range,ScenePackagePlayback.Settings settings) throws AssetFormatException {
        this(target,selection,Objects.requireNonNull(range),settings,false);
    }
    /** Live MMD worlds do not accumulate a preview replay log. World placement remains outside the source simulation. */
    public static GeneralMeshInstance live(GeneralMeshModelResources target) throws AssetFormatException {
        var defaults=ScenePackagePlayback.Settings.preview();var mmd=defaults.mmd();
        var settings=new ScenePackagePlayback.Settings(new cc.sirrus.ysmlib.scene.mmd.MmdPlayback.Settings(
                mmd.frequencyHz(),mmd.solverIterations(),mmd.gravity(),false,mmd.maxStepsPerSeek()),defaults.vrm());
        return new GeneralMeshInstance(target,ScenePackagePlayback.Selection.REST,null,settings,true);
    }
    private GeneralMeshInstance(GeneralMeshModelResources target,ScenePackagePlayback.Selection selection,
                                AnimationPreview.Range range,ScenePackagePlayback.Settings settings,boolean live) throws AssetFormatException {
        RenderSystem.assertOnRenderThread();this.target=Objects.requireNonNull(target);this.settings=Objects.requireNonNull(settings);
        this.live=live;
        this.profile=settings.modelProfile()==null?target.profile():settings.modelProfile();
        this.settings=this.settings.withModelProfile(profile).withDeferredMmdDeformation(false);
        target.requirePublished();
        gltf=target.gltf()!=null?new GltfMeshRenderer(target.gltfPrograms(),target.limits()):null;
        vrm=target.vrm()!=null?new VrmMeshRenderer(target.vrmPrograms(),target.limits()):null;
        mmd=target.mmd()!=null?new MmdMeshRenderer(target.mmdPrograms(),target.limits()):null;
        try {
        if(mmd!=null) {
            var model=target.assets().model();MeshAsset mesh=null;
            if(model instanceof ScenePackageAssets.Pmx pmx && pmx.value().softBodies().isEmpty())mesh=pmx.mesh();
            else if(model instanceof ScenePackageAssets.Pmd pmd)mesh=pmd.mesh();
            if(mesh!=null&&!mesh.primitives().isEmpty()&&mmd.enableGpu(mesh.primitives().get(0)))this.settings=this.settings.withDeferredMmdDeformation(true);
        }
        replace(selection,range); }
        catch(RuntimeException|Error|AssetFormatException failure) {
            try { close(); } catch(RuntimeException cleanup) { failure.addSuppressed(cleanup); }throw failure;
        }
    }
    /** A failed selection leaves the current clip, pose and physics world usable. */
    public void select(ScenePackagePlayback.Selection selection,AnimationPreview.Range range) throws AssetFormatException {
        requireOpen();if(live) throw new IllegalStateException("Live scene has no preview transport");
        if(settings.mmd().physicsEnabled()) replaceAsync(selection,Objects.requireNonNull(range));
        else replace(selection,Objects.requireNonNull(range));
    }
    public void selectLive(ScenePackagePlayback.Selection selection, AnimationPreview.Range range, boolean loop) throws AssetFormatException {
        requireOpen();if(!live) throw new IllegalStateException("Preview scene requires an explicit animation range");
        replace(selection,range,loop);
    }
    public void selectLive(ScenePackagePlayback.Selection selection) throws AssetFormatException {
        requireOpen();if(!live) throw new IllegalStateException("Preview scene requires an explicit animation range");
        replace(selection,null);
    }
    private void replace(ScenePackagePlayback.Selection selection,AnimationPreview.Range range) throws AssetFormatException {
        replace(selection,range,false);
    }
    private void replace(ScenePackagePlayback.Selection selection,AnimationPreview.Range range,boolean loop) throws AssetFormatException {
        requireOpen();var services=YsmRuntime.scenes();
        preparationGeneration++;preparing=false;preparationError="";
        var playbackSettings=live && range!=null ? settings.withAnimationRange(
                new cc.sirrus.ysmlib.scene.AnimationPlaybackRange(range.start(),range.end(),loop)) : settings;
        var next=services.playback(target.assets(),selection,playbackSettings,target.limits());
        final AnimationPreview<ScenePackagePlayback.Frame> preview;
        final ScenePackagePlayback.Frame initial;
        try { preview=live?null:services.preview(range,next::seek);initial=live?next.seek(0):preview.current(); }
        catch(RuntimeException|Error failure) { try { next.close(); } catch(RuntimeException cleanup) { failure.addSuppressed(cleanup); }throw failure; }
        var previous=player;player=next;timeline=preview;liveFrame=initial;hasSequence=false;if(previous!=null) previous.close();
        cachedBounds=null;
    }
    /** Source evaluation has no GL dependency. Keep rendering the last good frame until initialization completes. */
    private void replaceAsync(ScenePackagePlayback.Selection selection,AnimationPreview.Range range) {
        long generation=++preparationGeneration;preparing=true;preparationError="";
        var requested=settings;var assets=target.assets();var limits=target.limits();
        record Prepared(ScenePackagePlayback player,AnimationPreview<ScenePackagePlayback.Frame> timeline) {}
        java.util.concurrent.CompletableFuture.supplyAsync(()->{
            ScenePackagePlayback next=null;
            try {
                var services=YsmRuntime.scenes();next=services.playback(assets,selection,requested,limits);
                return new Prepared(next,services.preview(range,next::seek));
            } catch(Exception|Error e) {if(next!=null)next.close();throw new java.util.concurrent.CompletionException(e);}
        }).whenComplete((prepared,failure)->net.minecraft.client.Minecraft.getInstance().execute(()->{
            if(closed || generation!=preparationGeneration) {if(prepared!=null)prepared.player().close();return;}
            preparing=false;
            if(failure!=null) {preparationError=failure.toString();com.elfmcys.ysm.YesSteveModel.LOGGER.error("Cannot initialize editor scene physics source={}",assets.source().model().path(),failure);return;}
            var previous=player;var state=timeline.state();player=prepared.player();timeline=prepared.timeline();
            timeline.looping(state.looping());if(state.playing())timeline.play();liveFrame=timeline.current();hasSequence=false;cachedBounds=null;previous.close();
        }));
    }
    public ScenePackagePlayback.Selection selection() { requireOpen();return player.selection(); }
    int renderedHeldItems;
    private String lastItemFailure="";
    public int renderedHeldItems(){return renderedHeldItems;}
    void itemFailure(RuntimeException failure) {
        String reason=failure.toString();if(reason.equals(lastItemFailure))return;lastItemFailure=reason;
        com.elfmcys.ysm.YesSteveModel.LOGGER.error("Cannot render generic held item source={}",assets().source().model().path(),failure);
    }
    public SceneModelProfile profile() { return profile; }
    public void configurePreview(SceneModelProfile value,boolean physics) throws AssetFormatException {
        requireOpen();if(live) throw new IllegalStateException("Editor configuration requires an independent preview");
        SceneSkeleton.validate(SceneSkeleton.of(target.assets()),value.bones());
        boolean rebuild=!profile.bones().equals(value.bones()) || settings.mmd().physicsEnabled()!=physics;
        var previous=settings;var previousProfile=profile;settings=settings.withPhysics(physics).withModelProfile(value);profile=value;
        try { if(rebuild) {if(physics)replaceAsync(selection(),timeline.range());else replace(selection(),timeline.range());}cachedBounds=null; }
        catch(RuntimeException|AssetFormatException failure) { settings=previous;profile=previousProfile;throw failure; }
    }
    public void boneRotations(java.util.Map<Integer,Rotation> rotations) {
        player.boneRotations(rotations);
        if(!live) timeline.seek(timeline.state().seconds());
    }
    public void skeletonPose(java.util.Map<Integer,Pose> poses,java.util.Map<Integer,Boolean> ik) {
        requireOpen();player.bonePoses(poses);player.ikOverrides(ik);
        if(!live)timeline.seek(timeline.state().seconds());
    }
    public org.joml.Matrix4f placement() {
        var p=profile.placement();float s=(float)(p.effectiveScale()/target.assets().source().settings().metersPerUnit());
        return new org.joml.Matrix4f().translation((float)p.x(),(float)p.y(),(float)p.z()).rotateY((float)Math.toRadians(p.yaw()))
            .scale(s).translate(0,(float)(-p.footY()*target.assets().source().settings().metersPerUnit()),0);
    }
    public ScenePackageAssets assets() { requireOpen();return target.assets(); }
    public boolean linearOutput() { requireOpen();return gltf!=null || vrm!=null; }
    public boolean live() { return live; }
    public AnimationPreview<ScenePackagePlayback.Frame> timeline() {
        requireOpen();if(live) throw new IllegalStateException("Live scene has no preview transport");return timeline;
    }
    public ScenePackagePlayback.Frame frame() { requireOpen();return live?liveFrame:timeline.current(); }

    /**
     * Returns the current third/first-person geometry bounds in host metres.  The
     * result is cached for the current immutable frame so GUI cards can ask for
     * framing every render tick without walking every vertex repeatedly.
     */
    public PreviewBounds previewBounds(boolean firstPerson) {
        requireOpen();
        var geometry = firstPerson ? frame().firstPerson() : frame().thirdPerson();
        // Framing is a camera property of the selected asset. Recomputing an
        // AABB over every deformed MMD vertex on every animation frame costs
        // nearly as much as the deformation itself and causes Alt+Y stalls.
        // Keep the first valid frame's bounds until the clip is replaced.
        if (cachedBounds != null) return cachedBounds;
        if(mmd!=null && frame().details() instanceof ScenePackagePlayback.Mmd details && !details.value().deformed()) {
            var attributes=mmd.resolvedAttributes(details.value());
            var model=target.assets().model();var source=model instanceof ScenePackageAssets.Pmx pmx?pmx.mesh():((ScenePackageAssets.Pmd)model).mesh();
            var resolved=new cc.sirrus.ysmlib.scene.mmd.MmdPlayback.Frame(details.value().seconds(),details.value().simulationSeconds(),details.value().pose(),java.util.Collections.nCopies(source.primitives().size(),attributes));
            geometry=YsmRuntime.scenes().geometry(source,resolved,geometry.coordinates());
        }
        var bounds = PreviewBounds.empty();
        var placementMatrix=placement();
        var coordinates = geometry.coordinates();
        for (var draw : geometry.draws()) {
            if (!draw.visible()) continue;
            try {
                var positions = draw.geometry().primitives();
                for (var primitive : positions) {
                    var attribute = primitive.attributes().get("POSITION");
                    if (attribute == null || attribute.components() < 3) continue;
                    var values = attribute.values();
                    var indices = primitive.indices();
                    if (indices.size() > 0) {
                        for (int i = 0; i < indices.size(); i++) {
                            int index = indices.get(i);
                            if (index < 0 || index > values.size() / attribute.components()) continue;
                            int vertex = index * attribute.components();
                            if (vertex + 2 >= values.size()) continue;
                            float x=values.get(vertex),y=values.get(vertex+1),z=values.get(vertex+2);
                            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) continue;
                            bounds = bounds.include(placed(toHostMetres(draw.world().transformPoint(new Vec3(x,y,z)), coordinates),placementMatrix));
                        }
                    } else {
                        for (int vertex = 0; vertex + 2 < values.size(); vertex += attribute.components()) {
                            float x=values.get(vertex),y=values.get(vertex+1),z=values.get(vertex+2);
                            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) continue;
                            bounds = bounds.include(placed(toHostMetres(draw.world().transformPoint(new Vec3(x,y,z)), coordinates),placementMatrix));
                        }
                    }
                }
            } catch (RuntimeException invalidGeometry) {
                // A malformed optional draw must not disable every model card.
                // The full load error remains reported by the model worker; the
                // preview simply falls back to the remaining valid geometry.
            }
        }
        cachedBounds = bounds;
        return bounds;
    }

    private static Vec3 placed(Vec3 value,org.joml.Matrix4f placementMatrix) {
        var p=placementMatrix.transformPosition(new org.joml.Vector3f(value.x(),value.y(),value.z()));return new Vec3(p.x,p.y,p.z);
    }
    private static Vec3 toHostMetres(Vec3 source, SceneAsset.Coordinates coordinates) {
        float scale = (float) coordinates.metersPerUnit();
        var x = source.x() * scale;
        var y = source.y() * scale;
        var z = source.z() * scale * (coordinates.rightHanded() ? 1 : -1);
        return switch (coordinates.upAxis()) {
            case "Y" -> new Vec3(x, y, z);
            case "Z" -> new Vec3(x, z, -y);
            case "X" -> new Vec3(-y, x, z);
            default -> throw new IllegalArgumentException(
                    "Scene requires an explicit source-axis adapter: " + coordinates.upAxis());
        };
    }
    public ScenePackagePlayback.Frame advance(long sequence,double elapsedSeconds) { return timeline().advance(sequence,elapsedSeconds); }
    /** Absolute instance time supplied once by the host owner, not by individual render passes. */
    public ScenePackagePlayback.Frame sampleLive(long sequence,double seconds) {
        requireOpen();if(!live) throw new IllegalStateException("Preview scene uses its transport controls");
        if(!Double.isFinite(seconds)||seconds<0) throw new IllegalArgumentException("Invalid live scene time");
        if(hasSequence && sequence<lastSequence) throw new IllegalArgumentException("Live scene sequence moved backwards");
        if(hasSequence && sequence==lastSequence) return liveFrame;
        var next=player.seek(seconds);liveFrame=next;lastSequence=sequence;hasSequence=true;return next;
    }

    /** View maps source scene coordinates into camera space, including the host's explicit units/basis conversion. */
    public void render(boolean firstPerson,GltfSurfaceProgram.View view) {
        var frame=frame();var geometry=firstPerson?frame.firstPerson():frame.thirdPerson();
        if(gltf!=null) gltf.render(geometry,target.gltf().materials(),view,target::textureId);
        else if(vrm!=null) {
            if(!(frame.details() instanceof ScenePackagePlayback.Vrm details)) throw new IllegalArgumentException("VRM instance received another source frame");
            var materials=details.materials();
            var vrmView=new VrmSurfaceProgram.View(view.modelView(),view.projection(),view.toLight(),view.lightRadiance(),view.diffuseIrradiance(),view.tint(),view.orthographic(),view.lightmap());
            vrm.render(geometry,materials,vrmView,target::textureId);
        } else {
            if(!(frame.details() instanceof ScenePackagePlayback.Mmd details)) throw new IllegalArgumentException("MMD instance received another source frame");
            var materials=target.mmd().evaluate(details.value().pose().morphs().materials());
            // A single MMD file owns one ordered material set. PMM project instances have their own future dispatcher.
            if(geometry.draws().size()!=1) throw new IllegalArgumentException("MMD model requires one geometry instance");
            var draw=geometry.draws().get(0);if(!draw.visible()) return;
            var modelView=view.modelView().multiply(draw.world());
            var mmdView=new MmdSurfaceProgram.View(modelView,view.projection(),view.lightRadiance(),view.toLight(),view.tint(),view.orthographic(),view.lightmap());
            mmd.render(draw.geometry().primitives(),materials,mmdView,target::textureId,SceneWinding.clockwise(modelView,view.projection()),profile.presentation(),details.value());
        }
    }
    private void requireOpen() {
        RenderSystem.assertOnRenderThread();
        if(closed) throw new IllegalStateException("Scene instance is closed");target.requirePublished();
    }
    @Override public void close() {
        RenderSystem.assertOnRenderThread();if(closed) return;closed=true;preparationGeneration++;
        RuntimeException failure=null;
        for(AutoCloseable resource:new AutoCloseable[]{player,mmd,vrm,gltf}) if(resource!=null) {
            try { resource.close(); } catch(Exception error) {
                if(failure==null) failure=new IllegalStateException("Cannot close scene instance",error);else failure.addSuppressed(error);
            }
        }
        player=null;timeline=null;cachedBounds=null;if(failure!=null) throw failure;
    }

    public record PreviewBounds(Vec3 min, Vec3 max, boolean valid) {
        private static PreviewBounds empty() {
            // Vec3 deliberately rejects non-finite values.  Empty bounds are
            // represented by finite sentinels plus an explicit validity bit.
            return new PreviewBounds(Vec3.ZERO, Vec3.ZERO, false);
        }

        private PreviewBounds include(Vec3 point) {
            if (point == null) return this;
            if (!Float.isFinite(point.x()) || !Float.isFinite(point.y()) || !Float.isFinite(point.z())) return this;
            if (!valid) return new PreviewBounds(point, point, true);
            return new PreviewBounds(
                    new Vec3(Math.min(min.x(), point.x()), Math.min(min.y(), point.y()), Math.min(min.z(), point.z())),
                    new Vec3(Math.max(max.x(), point.x()), Math.max(max.y(), point.y()), Math.max(max.z(), point.z())), true);
        }

        @Override public boolean valid() {
            return valid && Float.isFinite(min.x()) && Float.isFinite(min.y()) && Float.isFinite(min.z())
                    && Float.isFinite(max.x()) && Float.isFinite(max.y()) && Float.isFinite(max.z())
                    && max.x() >= min.x() && max.y() >= min.y() && max.z() >= min.z();
        }

        public Vec3 center() {
            return new Vec3((min.x() + max.x()) * .5f, (min.y() + max.y()) * .5f,
                    (min.z() + max.z()) * .5f);
        }

        public float maxExtent() {
            return Math.max(max.x() - min.x(), Math.max(max.y() - min.y(), max.z() - min.z()));
        }
    }
}
