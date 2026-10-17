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
import com.elfmcys.ysm.model.catalog.CpmModelImporter;
import cc.sirrus.ysmlib.cpm.CpmPoseSession;
import com.elfmcys.ysm.geckolib3.model.AnimatedGeoBone;
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
    private static volatile boolean cpmItemNamesRegistered;
    private static volatile java.util.Map<String, Double> cpmItemConstants = java.util.Map.of();
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
    private CpmPoseSession cpmPoseSession;
    /** Partial tick associated with the frame currently being evaluated. */
    private volatile float cpmPartialTicks;

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
            if (!(this instanceof IPreviewEntity)) holder.updateGeneralAnimation(generalAnimationState(),seconds,this instanceof CustomPlayerEntity p?p.generalActionElapsed(partialTicks):0);
            boolean boundSkeleton;
            if(this instanceof IPreviewEntity&&editorReferencePose) {
                try {boundSkeleton=updateGeneralReference(scene);}
                catch(RuntimeException failure) {editorReferencePose=false;boundSkeleton=false;scene.skeletonPose(java.util.Map.of(),java.util.Map.of());
                    com.elfmcys.ysm.YesSteveModel.LOGGER.error("Cannot evaluate reference pose model={}",getModelHash(),failure);}
            }
            else boundSkeleton=holder.retargeting && updateGeneralSkeleton(scene,partialTicks);
            if (!boundSkeleton && !(this instanceof IPreviewEntity) && entity instanceof net.minecraft.world.entity.LivingEntity living)
                scene.look(net.minecraft.util.Mth.wrapDegrees(living.getYHeadRot()-living.yBodyRot),living.getXRot());
            if(!(this instanceof IPreviewEntity)){
                if(entity instanceof net.minecraft.world.entity.LivingEntity living)scene.interactionInputs(living,this instanceof CustomPlayerEntity p?p.getRoamingStruct():null,seconds,boundSkeleton);
                if(scene.controllerLayerRequested()&&!boundSkeleton)updateGeneralControllerLayer(scene,partialTicks);
                scene.updateHostPhysics(this,partialTicks,seconds);
            }
            holder.updateScene(sequence,seconds);
        }
        return scene;
    }

    /** Applies CPM's per-entity expression/script pose after the regular YSM frame. */
    private void applyCpmPose(float partialTicks) {
        var session = cpmPoseSession;
        if (session == null || getModelHash() == null || getLoadedGeoModel() == null) return;
        // The two CPM generations expose different Euler channels.  Legacy
        // CustomModel calls setEulerAnglesRad(yaw=source.x,
        // pitch=source.y, roll=source.z), while Modern/Remake's
        // ModelBone.instantiate() swaps those channels before constructing
        // its quaternion. Keep the source document beside the pose session so
        // this bridge never infers a format from bone names or screenshots.
        var cpmDocument = CpmModelImporter.document(getModelHash()).orElse(null);
        boolean legacyCpm = cpmDocument instanceof cc.sirrus.ysmlib.cpm.CpmDocument.Legacy;
        var inputs = new java.util.LinkedHashMap<String, Double>();
        inputs.put("age", entity.tickCount + (double) partialTicks);
        inputs.put("time", inputs.get("age"));
        inputs.put("partial", (double) partialTicks);
        inputs.put("scale", 1d);
        boolean firstPerson = entity == net.minecraft.client.Minecraft.getInstance().getCameraEntity()
                && net.minecraft.client.Minecraft.getInstance().options.getCameraType().isFirstPerson();
        inputs.put("is_first_person", firstPerson ? 1d : 0d);
        if (entity instanceof net.minecraft.world.entity.LivingEntity living) {
            inputs.put("limb_swing", (double) living.walkAnimation.position(partialTicks));
            inputs.put("limb_swing_amount", (double) living.walkAnimation.speed(partialTicks));
            inputs.put("anim_position", inputs.get("limb_swing"));
            inputs.put("anim_speed", inputs.get("limb_swing_amount"));
            // CustomModel 1.12 expression names (the Lib keeps the newer
            // CPM names above as well).
            inputs.put("limb_speed", inputs.get("limb_swing_amount"));
            inputs.put("head_pitch", (double) living.getXRot());
            inputs.put("head_yaw", (double) living.getYHeadRot());
            inputs.put("body_yaw", (double) living.yBodyRot);
            inputs.put("is_crouching", living.isCrouching() ? 1d : 0d);
            inputs.put("is_sneaking", living.isCrouching() ? 1d : 0d);
            inputs.put("is_riding", living.isPassenger() ? 1d : 0d);
            inputs.put("is_sprinting", living.isSprinting() ? 1d : 0d);
            inputs.put("is_hurt", living.hurtTime > 0 ? 1d : 0d);
            inputs.put("is_climbing", living.onClimbable() ? 1d : 0d);
            inputs.put("is_on_ground", living.onGround() ? 1d : 0d);
            inputs.put("is_wet", living.isInWaterRainOrBubble() ? 1d : 0d);
            inputs.put("is_in_water", living.isInWater() ? 1d : 0d);
            inputs.put("pose_sleeping", living.isSleeping() ? 1d : 0d);
            inputs.put("pose_swimming", living.isSwimming() ? 1d : 0d);
            inputs.put("pose_fall_flying", living.isFallFlying() ? 1d : 0d);
            inputs.put("pose_spin_attack", living.isAutoSpinAttack() ? 1d : 0d);
            inputs.put("is_alive", living.isAlive() ? 1d : 0d);
            inputs.put("is_burning", living.isOnFire() ? 1d : 0d);
            inputs.put("is_glowing", living.isCurrentlyGlowing() ? 1d : 0d);
            inputs.put("is_in_lava", living.isInLava() ? 1d : 0d);
            inputs.put("is_invisible", living.isInvisible() ? 1d : 0d);
            inputs.put("is_camera_first_person", firstPerson ? 1d : 0d);
            inputs.put("current_pose", cpmPoseId(living));
            inputs.put("pose.standing", 0d); inputs.put("pose.fall_flying", 1d);
            inputs.put("pose.sleeping", 2d); inputs.put("pose.swimming", 3d);
            inputs.put("pose.spin_attack", 4d); inputs.put("pose.sneaking", 5d);
            inputs.put("pose.dying", 6d);
            var motion = living.getDeltaMovement();
            inputs.put("motion_x", motion.x);
            inputs.put("motion_y", motion.y);
            inputs.put("motion_z", motion.z);
            inputs.put("speed_x", motion.x);
            inputs.put("speed_y", motion.y);
            inputs.put("speed_z", motion.z);
            inputs.put("hurt_time", (double) living.hurtTime - partialTicks);
            inputs.put("pos_y", living.getY());
            inputs.put("pos_x", living.getX());
            inputs.put("pos_z", living.getZ());
            inputs.put("yaw", (double) living.getYRot());
            inputs.put("pitch", (double) living.getXRot());
            inputs.put("health", (double) living.getHealth());
            inputs.put("max_health", (double) living.getMaxHealth());
            var main = living.getMainHandItem();
            var off = living.getOffhandItem();
            var left = living.getMainArm() == net.minecraft.world.entity.HumanoidArm.LEFT ? main : off;
            var right = living.getMainArm() == net.minecraft.world.entity.HumanoidArm.RIGHT ? main : off;
            inputs.put("left_hand_empty", left.isEmpty() ? 1d : 0d);
            inputs.put("right_hand_empty", right.isEmpty() ? 1d : 0d);
            inputs.put("main_hand_empty", main.isEmpty() ? 1d : 0d);
            inputs.put("off_hand_empty", off.isEmpty() ? 1d : 0d);
            inputs.put("helmet_empty", living.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).isEmpty() ? 1d : 0d);
            inputs.put("chestplate_empty", living.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).isEmpty() ? 1d : 0d);
            inputs.put("leggings_empty", living.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.LEGS).isEmpty() ? 1d : 0d);
            inputs.put("boots_empty", living.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.FEET).isEmpty() ? 1d : 0d);
            inputs.put("inv.helmet", cpmItemId(living.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD)));
            inputs.put("inv.chestplate", cpmItemId(living.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST)));
            inputs.put("inv.leggings", cpmItemId(living.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.LEGS)));
            inputs.put("inv.boots", cpmItemId(living.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.FEET)));
            inputs.put("inv.offhand", cpmItemId(off));
            inputs.put("inv.mainhand", cpmItemId(main));
            inputs.put("left_hand_item_id", cpmItemId(left));
            inputs.put("right_hand_item_id", cpmItemId(right));
            inputs.put("main_hand_item_id", cpmItemId(main));
            inputs.put("off_hand_item_id", cpmItemId(off));
            inputs.put("helmet_empty_id", cpmItemId(living.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD)));
            inputs.put("chestplate_empty_id", cpmItemId(living.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST)));
            inputs.put("leggings_empty_id", cpmItemId(living.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.LEGS)));
            inputs.put("boots_empty_id", cpmItemId(living.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.FEET)));
            if (living instanceof net.minecraft.world.entity.player.Player player) {
                inputs.put("food_level", (double) player.getFoodData().getFoodLevel());
                inputs.put("saturation_level", (double) player.getFoodData().getSaturationLevel());
                for (int i = 0; i < player.getInventory().items.size(); i++) {
                    var stack = player.getInventory().items.get(i);
                    inputs.put("inventory_" + i + "_empty", stack.isEmpty() ? 1d : 0d);
                    inputs.put("inventory_" + i + "_id", cpmItemId(stack));
                }
            }
            // Legacy expressions compare inv.* with item.<path> constants.
            // Registering the compact path keeps the Lib evaluator Minecraft
            // independent while preserving the upstream numeric semantics.
            if (!cpmItemNamesRegistered) synchronized (CustomEntity.class) {
                if (!cpmItemNamesRegistered) {
                    var constants = new java.util.LinkedHashMap<String, Double>();
                    for (var entry : net.minecraft.core.registries.BuiltInRegistries.ITEM.entrySet()) {
                        int id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getId(entry.getValue());
                        cc.sirrus.ysmlib.cpm.CpmItemNames.register(id, entry.getKey().location().toString());
                        constants.put("item." + entry.getKey().location().getPath(), (double) id);
                    }
                    cpmItemConstants = java.util.Map.copyOf(constants);
                    cpmItemNamesRegistered = true;
                }
            }
            inputs.putAll(cpmItemConstants);
        }
        // Modern CPM scripts can read and modify the live vanilla skeleton
        // through model.getBone("head"), etc.  Feed the Lib the post-YSM
        // animation values before evaluating the script; otherwise the
        // script starts from zero every frame and authored locomotion is lost.
        if (!legacyCpm) session.setHostPose(cpmHostPose());
        var poses = session.tick(entity.tickCount + (double) partialTicks, inputs);
        // CPM owns emission clocks in the per-entity Lib session.  Drain once
        // after evaluation so GUI/secondary passes cannot duplicate events.
        for (var particle : session.drainParticles())
            com.elfmcys.ysm.client.particle.ParticleSpawner.spawnCpmParticle(entity, particle);
        for (var entry : poses.entrySet()) {
            var bone = cpmBoneView(entry.getKey(), legacyCpm);
            if (bone == null) continue;
            var pose = entry.getValue();
            var position = pose.position();
            var rotation = pose.rotation();
            var scale = pose.scale();
            if (isCpmCanonicalHost(entry.getKey())) {
                // Legacy skeleton.*_all entries only replace the vanilla
                // ModelRenderer rotation point. Their zero rotation/scale
                // defaults must not erase the normal YSM locomotion pose.
                bone.setPosition((float) position[0], (float) position[1], (float) position[2]);
                if (!legacyCpm) {
                    bone.setRotation((float) -Math.toRadians(rotation[0]),
                            (float) -Math.toRadians(rotation[1]),
                            (float) -Math.toRadians(rotation[2]));
                    bone.setScale((float) scale[0], (float) scale[1], (float) scale[2]);
                    bone.setHidden(!pose.visible());
                }
                continue;
            }
            // JavaModelState turns attributes into the CPM ModelBone
            // translation (-attrX, +attrY, +attrZ) / 16.  CPM's original
            // Bone.update() uses (-x, -y, +z) / 16, so the bridge stores the
            // source values as (x, -y, z) pixels here.  The renderer performs
            // the only pixel-to-block conversion.
            // AnimatedGeoBone stores CPM translations in authored pixels;
            // JavaModelState performs the single pixel-to-block conversion
            // while building the pose matrix.  Dividing here would shrink
            // every legacy local offset by another factor of sixteen.
            if (legacyCpm) CpmLegacyRenderPoseAdapter.apply(bone, pose);
            else CpmModernRenderPoseAdapter.apply(bone, pose);
            var color = pose.color();
            bone.setColor(clampCpmColor(color[0]), clampCpmColor(color[1]), clampCpmColor(color[2]));
            bone.setTransparency(Math.max(0, Math.min(255, (int) Math.round(pose.alpha() * 255))));
            bone.setGlow(pose.emissive() ? 15 : -1);
        }
    }

    private java.util.Map<String, CpmPoseSession.BonePose> cpmHostPose() {
        var result = new java.util.LinkedHashMap<String, CpmPoseSession.BonePose>();
        cpmHostPose(result, "head", "Head");
        cpmHostPose(result, "body", "UpperBody");
        cpmHostPose(result, "left_arm", "LeftArm");
        cpmHostPose(result, "right_arm", "RightArm");
        cpmHostPose(result, "left_leg", "LeftLeg");
        cpmHostPose(result, "right_leg", "RightLeg");
        return result;
    }

    private void cpmHostPose(java.util.Map<String, CpmPoseSession.BonePose> target,
                             String scriptName, String ysmName) {
        var view = getBone(com.elfmcys.ysm.geckolib3.core.molang.util.StringPool.computeIfAbsent(ysmName));
        if (!(view instanceof AnimatedGeoBone bone)) return;
        target.put(scriptName, new CpmPoseSession.BonePose(
                new double[]{bone.getPositionX(), bone.getPositionY(), bone.getPositionZ()},
                // CpmPoseSession takes host rotations in radians and
                // converts them to CPM's degree based script channels.  The
                // YSM model frame is reflected on X, so CPM observes the
                // inverse reflected rotation on all three axes.
                new double[]{-bone.getRotationX(),
                        -bone.getRotationY(),
                        -bone.getRotationZ()},
                new double[]{bone.getScaleX(), bone.getScaleY(), bone.getScaleZ()},
                !bone.areCubesHidden(), new double[]{1, 1, 1}, 1, false));
    }

    /**
     * The Lib compiler reserves YSM's canonical player bone names for the
     * host skeleton and prefixes a CPM bone that uses one of those names.
     * CPM scripts still address the authored name, so resolve that name at
     * the host boundary instead of silently dropping the pose.  The fallback
     * chain also handles the collision prefixes used by the compiler when a
     * package contains both a canonical name and its reserved form.
     */
    @Nullable
    private AnimatedGeoBone cpmBoneView(String authoredName, boolean legacy) {
        var pool = com.elfmcys.ysm.geckolib3.core.molang.util.StringPool
                .computeIfAbsent(authoredName);
        var view = getBone(pool);
        if (view instanceof AnimatedGeoBone bone) return bone;
        String hostAlias = switch (authoredName) {
            case "head" -> "Head"; case "body" -> "UpperBody";
            case "left_arm" -> "LeftArm"; case "right_arm" -> "RightArm";
            case "left_leg" -> "LeftLeg"; case "right_leg" -> "RightLeg";
            default -> null;
        };
        if (hostAlias != null) {
            view = getBone(com.elfmcys.ysm.geckolib3.core.molang.util.StringPool.computeIfAbsent(hostAlias));
            if (view instanceof AnimatedGeoBone bone) return bone;
        }
        if (cpmPoseSession == null) return null;
        String candidate = "__cpm_" + authoredName;
        view = getBone(com.elfmcys.ysm.geckolib3.core.molang.util.StringPool.computeIfAbsent(candidate));
        if (view instanceof AnimatedGeoBone bone) return bone;
        // Compiler collision prefixes are deterministic but not knowable from
        // this renderer. Probe the bounded prefix chain used by CpmSceneCompiler.
        for (int i = 1; i <= 8; i++) {
            candidate = "_".repeat(i) + "__cpm_" + authoredName;
            view = getBone(com.elfmcys.ysm.geckolib3.core.molang.util.StringPool.computeIfAbsent(candidate));
            if (view instanceof AnimatedGeoBone bone) return bone;
        }
        return null;
    }

    /**
     * CPM expressions are a post-animation layer.  AnimatableEntity extracts
     * GeoRenderData immediately after tickAnimation(), so applying the pose
     * from update() would leave the current frame one tick behind.  The
     * post-animation hook is the last point at which bone state can still be
     * included in the render snapshot and also runs for asynchronous updates.
     */
    @Override
    protected void postAnimationSetup(float seekTime, boolean shouldTick) {
        super.postAnimationSetup(seekTime, shouldTick);
        applyCpmPose(currentFramePartialTicks());
    }

    private float currentFramePartialTicks() {
        return cpmPartialTicks;
    }

    private static int clampCpmColor(double value) {
        return Math.max(0, Math.min(255, (int) Math.round(value * 255)));
    }

    private static boolean isCpmCanonicalHost(String name) {
        return switch (name) {
            case "Root", "MAllBody", "AllBody", "UpBody", "UpperBody", "Arm",
                    "LeftArm", "LeftForeArm", "LeftHand", "RightArm", "RightForeArm", "RightHand",
                    "AllHead", "MHead", "Head", "DownBody", "Leg", "LeftLeg", "LeftLowerLeg", "LeftFoot",
                    "RightLeg", "RightLowerLeg", "RightFoot", "HeadOverlay", "BodyOverlay", "LeftArmOverlay",
                    "RightArmOverlay", "LeftLegOverlay", "RightLegOverlay", "head", "body", "left_arm", "right_arm",
                    "left_leg", "right_leg" -> true;
            default -> false;
        };
    }

    private static double cpmItemId(net.minecraft.world.item.ItemStack stack) {
        return stack == null || stack.isEmpty() ? 0d
                : net.minecraft.core.registries.BuiltInRegistries.ITEM.getId(stack.getItem());
    }

    private static double cpmPoseId(net.minecraft.world.entity.LivingEntity living) {
        if (living.isFallFlying()) return 1d;
        if (living.isSleeping()) return 2d;
        if (living.isSwimming()) return 3d;
        if (living.isAutoSpinAttack()) return 4d;
        if (living.isCrouching()) return 5d;
        if (!living.isAlive()) return 6d;
        return 0d;
    }

    /** Logical YSM animation state used by the general-scene adapter. */
    protected String generalAnimationState() { return "idle"; }
    /** Host-specific YSM controllers supply their evaluated pose; Lib owns the cross-rig conversion. */
    protected boolean updateGeneralSkeleton(com.elfmcys.ysm.client.renderer.GeneralMeshInstance scene,float partialTicks) { return false; }
    protected void updateGeneralControllerLayer(com.elfmcys.ysm.client.renderer.GeneralMeshInstance scene,float partialTicks) {}

    protected void onModelRenderTargetLoaded(ModelRenderTarget newModel) {
        var resourceHolder = modelBinding.resourceHolder();
        if (resourceHolder == null) {
            return;
        }
        deferHandler = newModel.assets().eventHandlers().get(MolangEventWrapper.DEFER);
        if (cpmPoseSession != null) cpmPoseSession.close();
        // A fallback render target is valid even when no model has been selected.
        // Its binding hash is null, and it must not create a CPM session.
        var modelHash = getModelHash();
        cpmPoseSession = modelFallback || modelHash == null
                ? null : CpmModelImporter.createPoseSession(modelHash).orElse(null);
    }

    protected void resetModelRenderTarget() {
        waitForAsyncUpdate();
        modelBinding.releaseRenderTarget();
        currentModelRenderTarget = null;
        deferHandler = null;
        if (cpmPoseSession != null) {
            cpmPoseSession.close();
            cpmPoseSession = null;
        }
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
        cpmPartialTicks = partialTicks;
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
        cpmPartialTicks = partialTicks;
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

        void updateGeneralAnimation(String state,double seconds,double elapsed) {
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
            boolean sourceAction=selected!=null&&selected.selection().sourceId().startsWith("@ysm/source/");
            boolean useSkeleton=transition==null && (sourceAction||mapped==null&&com.elfmcys.ysm.model.domain.SceneActionId.base(state).isEmpty())
                    && !scene.profile().retarget().sourceBones().isEmpty();
            if(sourceAction&&!useSkeleton)throw new IllegalArgumentException("Source action requires explicit sourceBones");
            if (selected == null && !rest && !useSkeleton) {
                if (mapped != null) YesSteveModel.LOGGER.warn("Unavailable scene mapping model={} state={} source={}", model.modelHash(), state, mapped.sourceId());
                selected = com.elfmcys.ysm.client.animation.GeneralAnimationActions.automatic(model, state);
            }
            var target = selected == null || useSkeleton ? cc.sirrus.ysmlib.scene.ScenePackagePlayback.Selection.REST : selected.selection();
            try {
                if(!useSkeleton&&retargeting)scene.skeletonPose(java.util.Map.of(),java.util.Map.of());
                // Native controller changes do not restart the MMD physics world or source clock.
                if(!(useSkeleton && retargeting && target.equals(scene.selection()))) {
                    double offset=!com.elfmcys.ysm.model.domain.SceneActionId.base(state).isEmpty()?elapsed:0;
                    boolean retained=scene.selectLive(target, selected == null || useSkeleton ? null : selected.range(), loop,offset);
                    if(!retained){sceneStart=Double.NaN;lastSceneTime=0;}sceneUpdated=false;
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
