package com.elfmcys.ysm.client.entity;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.client.animation.AnimationParallelTicker;
import com.elfmcys.ysm.client.animation.GeneralAnimationMappingStore;
import com.elfmcys.ysm.client.animation.debug.CustomDebugSource;
import com.elfmcys.ysm.client.animation.molang.MolangEventWrapper;
import com.elfmcys.ysm.client.animation.molang.PhysicsManager;
import com.elfmcys.ysm.client.gui.overlay.DebugAnimationScreen;
import com.elfmcys.ysm.model.resource.client.AcquireResult;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.model.service.ClientModelService;
import com.elfmcys.ysm.model.resource.client.ResourceLease;
import com.elfmcys.ysm.model.resource.client.ResourceRequest;
import com.elfmcys.ysm.client.sound.stream.AudioStreamProvider;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.geckolib3.core.molang.context.DebugSource;
import com.elfmcys.ysm.geckolib3.core.molang.value.IValue;
import com.elfmcys.ysm.geckolib3.core.processor.DebugInfo;
import com.elfmcys.ysm.geckolib3.geo.GeoRenderData;
import com.elfmcys.ysm.geckolib3.geo.RenderContext;
import com.elfmcys.ysm.geckolib3.geo.render.built.GeoModel;
import com.elfmcys.ysm.geckolib3.model.AnimatableEntity;
import com.elfmcys.ysm.model.domain.Hash256;
import com.elfmcys.ysm.model.domain.RenderTargetIds;
import com.elfmcys.ysm.util.Closeable;
import com.elfmcys.ysm.util.ThreadTools;
import com.elfmcys.ysm.util.UnsafeUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Future;

/**
 * 自动管理当前 model id 和 model container，并在找不到指定模型时 fallback 到默认模型
 */
public abstract class CustomEntity<T extends Entity> extends AnimatableEntity<T> {
    private final EntityModelBinding modelBinding;
    private ModelRenderTarget currentModelRenderTarget;
    private boolean modelFallback;
    private int lastCheckUpdateTime;
    @Nullable
    private PhysicsManager alterPhysicsManager;
    @Nullable
    private DebugInfo debugInfo;
    @Nullable
    private List<IValue> deferHandler;

    @Nullable
    private Future<GeoRenderData> asyncTask;

    protected CustomEntity(T entity, boolean asyncUpdate) {
        this(entity, asyncUpdate, new EntityModelBinding());
    }

    CustomEntity(T entity, boolean asyncUpdate, EntityModelBinding modelBinding) {
        super(entity);
        this.modelBinding = Objects.requireNonNull(modelBinding, "modelBinding");
        if (asyncUpdate) {
            AnimationParallelTicker.add(this);
        }
    }

    @Override
    public PhysicsManager getPhysicsManager(AnimationEvent<?> event) {
        var context = event.getRenderContext();
        if (context.immutable() || context.firstPersonMod() || context.paperDoll()) {
            return physicsManager;
        }
        if (alterPhysicsManager == null) {
            alterPhysicsManager = new PhysicsManager();
        }
        return alterPhysicsManager;
    }

    @Nullable
    public List<IValue> getMolangDeferHandler() {
        return deferHandler;
    }

    public void setDebugInfo(@Nullable DebugInfo debugInfo) {
        this.debugInfo = debugInfo;
    }

    @Override
    protected void preAnimationSetup(float seekTime, boolean shouldTick) {
        super.preAnimationSetup(seekTime, shouldTick);
        // 更新调试信息
        if (debugInfo != null) {
            var processor = getAnimationProcessor();
            processor.enqueueMolangTask(evaluator -> {
                debugInfo.evaluatePre(evaluator);
                return null;
            }, false, true, null);
            processor.enqueueMolangTask(evaluator -> {
                debugInfo.evaluatePost(evaluator);
                return null;
            }, false, false, null);
        }
    }

    public void checkModelUpdate() {
        if (lastCheckUpdateTime < entity.tickCount) {
            checkModelRenderTargetUpdate();
            lastCheckUpdateTime = entity.tickCount;
        }
    }

    public final ModelRenderTarget getModelRenderTarget() {
        return currentModelRenderTarget;
    }

    protected final void updateModelHash(Hash256 modelHash) {
        modelBinding.updateModelHash(
                modelHash, requestedRenderTargetId(), requestedTextureName());
        checkModelRenderTargetUpdate();
    }

    private void checkModelRenderTargetUpdate() {
        waitForAsyncUpdate();
        modelBinding.synchronize(
                requestedRenderTargetId(), requestedTextureName(), fallbackRenderTargetId(), this::prepareResourceHolder);
        applyBoundResource();
    }

    protected final void installReadyForPreview(ResourceRequest request,
                                                ResourceLease lease) {
        waitForAsyncUpdate();
        modelBinding.installReadyForPreview(request, lease, this::prepareResourceHolder);
        applyBoundResource();
    }

    private void applyBoundResource() {
        var resourceHolder = modelBinding.resourceHolder();
        if (resourceHolder != null) {
            if ((resourceHolder.model != currentModelRenderTarget || resourceHolder.fallback != modelFallback) && resourceHolder.isLoaded()) {
                currentModelRenderTarget = resourceHolder.model;
                modelFallback = resourceHolder.fallback;
                onModelRenderTargetLoaded(currentModelRenderTarget);
                if(currentModelRenderTarget.generalMeshResources()!=null) resetGeoModel();
                else loadGeoModel(getYsmGeoModel(), currentModelRenderTarget.assets().eventHandlers());
            }
        } else if (currentModelRenderTarget != null) {
            resetModelRenderTarget();
        }
    }

    @Nullable
    protected abstract ResourceHolder createResourceHolder(ResourceLease lease, boolean isFallback);

    @Nullable
    private cc.sirrus.ysmlib.scene.ScenePackagePlayback.Settings editorSettings;
    private boolean editorReferencePose;
    public final void previewReferencePose(boolean enabled) {
        if(!(this instanceof IPreviewEntity))throw new IllegalStateException("Reference diagnostics require an independent preview");
        var scene=getGeneralMeshInstance();
        if(scene!=null&&!enabled)scene.skeletonPose(java.util.Map.of(),java.util.Map.of());
        if(scene!=null&&enabled) {
            try {
                if(!editorReferencePose)scene.select(cc.sirrus.ysmlib.scene.ScenePackagePlayback.Selection.REST,new cc.sirrus.ysmlib.scene.AnimationPreview.Range(0,0,30));
                scene.timeline().pause();updateGeneralReference(scene);
            } catch(cc.sirrus.ysmlib.scene.io.AssetFormatException e){throw new IllegalArgumentException("Cannot preview mapped reference pose",e);}
        }
        editorReferencePose=enabled;
    }
    protected boolean updateGeneralReference(com.elfmcys.ysm.client.renderer.GeneralMeshInstance scene){return false;}
    public final void configureEditorPreview(cc.sirrus.ysmlib.scene.SceneModelProfile profile,boolean physics) {
        if(!(this instanceof IPreviewEntity)) throw new IllegalStateException("Only a preview owns editor settings");
        editorSettings=cc.sirrus.ysmlib.scene.ScenePackagePlayback.Settings.previewUi().withPhysics(physics).withModelProfile(profile);
        var scene=getGeneralMeshInstance();if(scene!=null) try { scene.configurePreview(profile,physics); }
        catch(cc.sirrus.ysmlib.scene.io.AssetFormatException e) { throw new IllegalArgumentException("Cannot configure editor preview",e); }
    }
    private ResourceHolder prepareResourceHolder(ResourceLease lease,boolean fallback) {
        var holder=createResourceHolder(lease,fallback);
        if(holder!=null && ownsSceneInstance()) {
            try { holder.prepareScene(this instanceof IPreviewEntity,editorSettings); }
            catch(RuntimeException|Error failure) {
                try { holder.close(); } catch(RuntimeException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
        return holder;
    }

    protected String requestedTextureName() {
        return "";
    }

    protected String requestedRenderTargetId() {
        return RenderTargetIds.PLAYER;
    }

    protected String fallbackRenderTargetId() {
        return requestedRenderTargetId();
    }

    protected final ResourceHolder getResourceHolder() {
        return modelBinding.resourceHolder();
    }

    protected boolean ownsSceneInstance() { return true; }

    @Nullable
    public com.elfmcys.ysm.client.renderer.GeneralMeshInstance getGeneralMeshInstance() {
        var holder=getResourceHolder();return holder==null?null:holder.scene;
    }

    /** Select a Lib clip for GUI preview without routing it through MC animation controllers. */
    public final void selectGeneralAnimation(String actionId) {
        if(editorReferencePose)previewReferencePose(false);
        RenderSystem.assertOnRenderThread();
        var scene=getGeneralMeshInstance();var holder=getResourceHolder();
        if(scene==null || scene.live() || holder==null || actionId==null || actionId.isBlank()) return;
        if(actionId.equals(holder.generalPreviewId)) { scene.timeline().play();return; }
        var action=com.elfmcys.ysm.client.animation.GeneralAnimationActions.resolve(currentModelRenderTarget,actionId);
        if(action==null) throw new IllegalArgumentException("Unknown scene preview action: "+actionId);
        var selected=action.animation();
        try {
            scene.select(selected.selection(),selected.range());scene.timeline().looping(action.loop());scene.timeline().play();
            holder.generalPreviewId=actionId;
        } catch(cc.sirrus.ysmlib.scene.io.AssetFormatException failure) {
            throw new IllegalArgumentException("Cannot select scene preview animation",failure);
        }
    }

    public final void previewGeneralAnimation(cc.sirrus.ysmlib.scene.ScenePackagePlayback.Selection selection,
            cc.sirrus.ysmlib.scene.AnimationPreview.Range range, boolean loop) {
        if(editorReferencePose)previewReferencePose(false);
        var scene=getGeneralMeshInstance();if(scene==null || scene.live()) return;
        try { scene.select(selection,range);scene.timeline().looping(loop);scene.timeline().play(); }
        catch(cc.sirrus.ysmlib.scene.io.AssetFormatException failure) { throw new IllegalArgumentException("Cannot preview scene action",failure); }
    }

    /** Stop a general-scene preview at its first frame without creating a new playback session. */
    public final void stopGeneralAnimation() {
        RenderSystem.assertOnRenderThread();
        var scene = getGeneralMeshInstance();
        if (scene == null || scene.live()) return;
        var timeline = scene.timeline();
        timeline.pause();
        timeline.seek(timeline.range().start());
    }

    public final boolean isGeneralMesh() {
        return currentModelRenderTarget!=null && currentModelRenderTarget.generalMeshResources()!=null;
    }

    public final com.elfmcys.ysm.geckolib3.model.provider.data.EntityModelData scenePresentation(float partialTicks) {
        return createAnimationEvent(partialTicks,com.elfmcys.ysm.util.RenderUtil.extractRenderContext()).getExtraData();
    }
    public final com.elfmcys.ysm.geckolib3.model.provider.data.EntityModelData scenePhysicsPresentation(float partialTicks) {
        return createAnimationEvent(partialTicks,com.elfmcys.ysm.geckolib3.geo.RenderContext.levelImmutable()).getExtraData();
    }

    /** Same host frame sequence for world, GUI and secondary passes; only the owning instance advances. */
    @Nullable
    public com.elfmcys.ysm.client.renderer.GeneralMeshInstance updateGeneralMesh(float partialTicks) {
        RenderSystem.assertOnRenderThread();checkModelUpdate();
        var holder=getResourceHolder();var scene=getGeneralMeshInstance();
        if(scene!=null && ownsSceneInstance()) {
            long sequence=com.elfmcys.ysm.client.event.ClientTickEvent.renderSequence();
            double sampledSeconds=this instanceof IPreviewEntity?System.nanoTime()/1_000_000_000.0:(entity.tickCount+(double)partialTicks)/20;
            // Gate state selection and pose inputs as well as physics. World/first-person/secondary
            // passes can use different partial ticks, so their raw times are not a monotonic clock.
            if(!holder.sceneClock.beginFrame(sequence,sampledSeconds))return scene;
            double seconds=holder.sceneClock.seconds();
            if (!(this instanceof IPreviewEntity)) holder.updateGeneralAnimation(generalAnimationState(),seconds);
            boolean boundSkeleton;
            if(this instanceof IPreviewEntity&&editorReferencePose) {
                try {boundSkeleton=updateGeneralReference(scene);}
                catch(RuntimeException failure) {editorReferencePose=false;boundSkeleton=false;scene.skeletonPose(java.util.Map.of(),java.util.Map.of());
                    com.elfmcys.ysm.YesSteveModel.LOGGER.error("Cannot evaluate reference pose model={}",getModelHash(),failure);}
            }
            else boundSkeleton=holder.retargeting && updateGeneralSkeleton(scene,partialTicks);
            if (!boundSkeleton && !(this instanceof IPreviewEntity) && entity instanceof net.minecraft.world.entity.LivingEntity living)
                scene.look(net.minecraft.util.Mth.wrapDegrees(living.getYHeadRot()-living.yBodyRot),living.getXRot());
            if(!(this instanceof IPreviewEntity))scene.updateHostPhysics(this,partialTicks,seconds);
            holder.updateScene(sequence,seconds);
        }
        return scene;
    }

    /** Logical YSM animation state used by the general-scene adapter. */
    protected String generalAnimationState() { return "idle"; }
    /** Host-specific YSM controllers supply their evaluated pose; Lib owns the cross-rig conversion. */
    protected boolean updateGeneralSkeleton(com.elfmcys.ysm.client.renderer.GeneralMeshInstance scene,float partialTicks) { return false; }

    protected void onModelRenderTargetLoaded(ModelRenderTarget newModel) {
        var resourceHolder = modelBinding.resourceHolder();
        if (resourceHolder == null) {
            return;
        }
        deferHandler = newModel.assets().eventHandlers().get(MolangEventWrapper.DEFER);
    }

    protected void resetModelRenderTarget() {
        waitForAsyncUpdate();
        modelBinding.releaseRenderTarget();
        currentModelRenderTarget = null;
        deferHandler = null;
        modelFallback = false;
        resetGeoModel();
    }

    @Override
    protected void resetGeoModel() {
        super.resetGeoModel();
        alterPhysicsManager = null;
        lastCheckUpdateTime = 0;
    }

    public void reset() {
        modelBinding.clearModel();
        initialize = false;
        resetModelRenderTarget();
    }

    // getGeoModel 跟女仆的 IGeoEntity 冲突了，所以叫这个
    protected abstract GeoModel getYsmGeoModel();

    public final Hash256 getModelHash() {
        return modelBinding.modelHash();
    }

    /** Presentation path for GUI and third-party string APIs; never used as model identity. */
    public final String getModelId() {
        var modelHash = modelBinding.modelHash();
        return modelHash == null ? "default" : ClientModelService.instance().displayPath(modelHash);
    }

    @Override
    public boolean isModelPresent() {
        var resourceHolder = modelBinding.resourceHolder();
        return resourceHolder != null && !resourceHolder.fallback && resourceHolder.isLoaded();
    }

    @Override
    @Nullable
    public final IValue getUserFunction(String name) {
        return getModelRenderTarget().assets().userFunctions().get(name);
    }

    @Override
    public Optional<AudioStreamProvider> getSoundStream(String name) {
        var target = currentModelRenderTarget;
        if (target == null) {
            return Optional.empty();
        }
        var source = target.assets().sounds().get(name);
        if (source == null) {
            return Optional.empty();
        }
        return Optional.of(ClientModelService.instance().createSoundPlayback(source));
    }

    @Override
    public DebugSource getDebugSource() {
        if (DebugAnimationScreen.isEnabled()) {
            return CustomDebugSource.INSTANCE;
        } else {
            return null;
        }
    }

    public void beginAsyncUpdate(final float partialTicks) {
        waitForAsyncUpdate();
        if(isGeneralMesh()) return;
        UnsafeUtil.getUnsafe().storeFence();
        asyncTask = ThreadTools.submit(() -> {
            try {
                return super.update(partialTicks, RenderContext.levelImmutable());
            } finally {
                UnsafeUtil.getUnsafe().storeFence();
            }
        });
    }

    @Override
    @Nullable
    protected GeoRenderData update(float partialTicks, RenderContext context) {
        RenderSystem.assertOnRenderThread();
        var resolvedContext = resolveRenderContext(context);
        if (resolvedContext.immutable() && asyncTask != null) {
            var result = awaitAsyncUpdate();
            if (result != null) {
                return result;
            }
        }
        waitForAsyncUpdate();
        checkModelUpdate();
        if(isGeneralMesh()) return null;
        return super.update(partialTicks, resolvedContext);
    }

    public void waitForAsyncUpdate() {
        awaitAsyncUpdate();
    }

    private @Nullable GeoRenderData awaitAsyncUpdate() {
        if (asyncTask != null) {
            GeoRenderData result = null;
            try {
                result = asyncTask.get();
                UnsafeUtil.getUnsafe().loadFence();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable e) {
                e.printStackTrace();
            }
            asyncTask = null;
            return result;
        }
        return null;
    }

    public boolean canUpdateAsync() {
        return !isGeneralMesh();
    }

    protected static class ResourceHolder implements Closeable {
        private final ResourceLease lease;
        public final ModelRenderTarget model;
        public final boolean fallback;
        private boolean closed;
        private com.elfmcys.ysm.client.renderer.GeneralMeshInstance scene;
        private final com.elfmcys.ysm.client.animation.SceneFrameClock sceneClock=new com.elfmcys.ysm.client.animation.SceneFrameClock();
        private double sceneStart=Double.NaN,lastSceneTime;
        private long sceneSequence;
        private boolean sceneUpdated;
        private String generalAnimationState="";
        private String generalPreviewId="";
        private boolean retargeting;
        private cc.sirrus.ysmlib.scene.SceneStateTransitions transitions;
        private cc.sirrus.ysmlib.scene.SceneStateTransitions.Active activeTransition;
        private long generalAnimationMappingRevision = -1;

        protected ResourceHolder(ResourceLease lease, boolean fallback) {
            this.lease = lease;
            if (!(lease.poll() instanceof AcquireResult.Ready ready)) {
                throw new IllegalArgumentException("Resource holder requires a ready lease");
            }
            this.model = ready.target();
            this.fallback = fallback;
        }

        public boolean isLoaded() {
            return true;
        }

        void prepareScene(boolean preview,cc.sirrus.ysmlib.scene.ScenePackagePlayback.Settings editor) {
            var payload=model.generalMeshResources();if(payload==null || scene!=null) return;
            try {
                if(preview) {
                    var animation=editor==null?payload.animations().stream().filter(a -> !a.selection().sourceId().startsWith("@ysm/generated/")).findFirst().orElse(null):null;
                    var selection=animation==null?cc.sirrus.ysmlib.scene.ScenePackagePlayback.Selection.REST:animation.selection();
                    var range=animation==null?new cc.sirrus.ysmlib.scene.AnimationPreview.Range(0,10,30):animation.range();
                    scene=new com.elfmcys.ysm.client.renderer.GeneralMeshInstance(payload,selection,range,
                            editor==null?cc.sirrus.ysmlib.scene.ScenePackagePlayback.Settings.previewUi():editor);
                    // A rest pose has no source animation. Running a ten-second
                    // preview transport here needlessly invokes MMD physics and
                    // full vertex deformation on every UI update.
                    if (animation == null) return;
                } else scene=com.elfmcys.ysm.client.renderer.GeneralMeshInstance.live(payload);
                if(preview) { scene.timeline().looping(true);scene.timeline().play(); }
            } catch(cc.sirrus.ysmlib.scene.io.AssetFormatException failure) {
                throw new IllegalArgumentException("Cannot initialize scene instance",failure);
            }
        }

        void updateScene(long sequence,double seconds) {
            if(sceneUpdated && sceneSequence==sequence) return;
            boolean initializing=Double.isNaN(sceneStart);
            double next=initializing?0:Math.max(lastSceneTime,seconds-sceneStart);
            if (initializing) sceneStart=seconds;
            if (!scene.live()) {
                // Catalog cards are rendered once per display frame, while an
                // MMD seek rebuilds a complete deformed vertex table. Keep the
                // UI transport deterministic but sample it at 30 Hz and avoid
                // trying to replay a long stall after the screen was hidden.
                double elapsed = next - lastSceneTime;
                if (elapsed < 1.0 / 30.0) {
                    sceneSequence=sequence;sceneUpdated=true;
                    return;
                }
                if (elapsed > .25) {
                    next=lastSceneTime + .25;
                    sceneStart=seconds-next;
                }
            }
            if(scene.live()) scene.sampleLive(sequence,next);
            else scene.advance(sequence,next-lastSceneTime);
            lastSceneTime=next;sceneSequence=sequence;sceneUpdated=true;
        }

        void updateGeneralAnimation(String state,double seconds) {
            if (scene == null || !scene.live() || state == null || state.isBlank()) return;
            if(transitions==null)transitions=new cc.sirrus.ysmlib.scene.SceneStateTransitions(scene.profile(),scene.assets().source(),model.generalMeshResources().animations());
            var transition=transitions.update(state,seconds);
            long revision = GeneralAnimationMappingStore.revision();
            if (state.equals(generalAnimationState) && revision == generalAnimationMappingRevision && transition==activeTransition) return;
            var action = com.elfmcys.ysm.client.animation.GeneralAnimationActions.resolve(model, state);
            var mapped = com.elfmcys.ysm.client.animation.GeneralAnimationActions.mappings(model).get(state);
            var selected = transition!=null?transition.animation():action == null ? null : action.animation();
            boolean loop = transition==null && (action != null ? action.loop() : !state.equals("death") && !state.equals("attacked"));
            boolean rest = mapped != null && mapped.selection().equals(cc.sirrus.ysmlib.scene.ScenePackagePlayback.Selection.REST);
            // Native controllers are the default for bound rigs. A named VMD in the directory
            // must not silently replace gameplay; explicit mappings and wheel requests own their clip.
            boolean useSkeleton=transition==null && mapped==null && com.elfmcys.ysm.model.domain.SceneActionId.base(state).isEmpty()
                    && !scene.profile().retarget().sourceBones().isEmpty();
            if (selected == null && !rest && !useSkeleton) {
                if (mapped != null) YesSteveModel.LOGGER.warn("Unavailable scene mapping model={} state={} source={}", model.modelHash(), state, mapped.sourceId());
                selected = com.elfmcys.ysm.client.animation.GeneralAnimationActions.automatic(model, state);
            }
            var target = selected == null || useSkeleton ? cc.sirrus.ysmlib.scene.ScenePackagePlayback.Selection.REST : selected.selection();
            try {
                // Native controller changes do not restart the MMD physics world or source clock.
                if(!(useSkeleton && retargeting && target.equals(scene.selection()))) {
                    scene.selectLive(target, selected == null || useSkeleton ? null : selected.range(), loop);
                    sceneStart=Double.NaN;lastSceneTime=0;sceneUpdated=false;
                }
                retargeting=useSkeleton;
                activeTransition=transition;
                generalAnimationState=state;generalAnimationMappingRevision=revision;
            } catch (cc.sirrus.ysmlib.scene.io.AssetFormatException | RuntimeException failure) {
                YesSteveModel.LOGGER.error("Cannot play scene action model={} state={} selection={}", model.modelHash(), state, target, failure);
                generalAnimationState=state;generalAnimationMappingRevision=revision;activeTransition=transition;
            }
        }

        ResourceLease lease() {
            return lease;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if(scene!=null) { scene.close();scene=null; }
        }

    }
}
