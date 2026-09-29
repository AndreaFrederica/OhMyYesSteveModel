package com.elfmcys.ysm.geckolib3.model;

import com.elfmcys.ysm.client.animation.molang.PhysicsManager;
import com.elfmcys.ysm.client.entity.IPreviewEntity;
import com.elfmcys.ysm.client.event.ClientTickEvent;
import com.elfmcys.ysm.client.sound.stream.AudioStreamProvider;
import com.elfmcys.ysm.geckolib3.core.AnimationState;
import com.elfmcys.ysm.geckolib3.core.builder.Animation;
import com.elfmcys.ysm.geckolib3.core.builder.controller.AnimationControllerData;
import com.elfmcys.ysm.geckolib3.core.controller.IAnimationController;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.geckolib3.core.manager.AnimationData;
import com.elfmcys.ysm.geckolib3.core.molang.context.DebugSource;
import com.elfmcys.ysm.geckolib3.core.molang.storage.IForeignVariableStorage;
import com.elfmcys.ysm.geckolib3.core.molang.value.IValue;
import com.elfmcys.ysm.geckolib3.core.processor.AnimationProcessor;
import com.elfmcys.ysm.geckolib3.core.processor.BoneView;
import com.elfmcys.ysm.geckolib3.core.util.RateLimiter;
import com.elfmcys.ysm.geckolib3.geo.GeoRenderData;
import com.elfmcys.ysm.geckolib3.geo.RenderContext;
import com.elfmcys.ysm.geckolib3.geo.render.built.GeoModel;
import com.elfmcys.ysm.geckolib3.model.provider.data.EntityModelData;
import com.elfmcys.ysm.natives.NativeProfiler;
import com.elfmcys.ysm.util.RenderUtil;
import com.google.common.collect.Maps;
import it.unimi.dsi.fastutil.objects.Object2ReferenceMap;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

public abstract class AnimatableEntity<TEntity extends Entity> {
    private final AnimationData manager = new AnimationData();
    private final AnimationProcessor<TEntity> animationProcessor;
    private final RateLimiter rateLimiter;
    private final EntityStateTracker<TEntity> stateTracker;
    private final Map<RenderContext, GeoRenderData> renderDataPool = new HashMap<>();
    protected final PhysicsManager physicsManager;

    protected final TEntity entity;
    private AnimatedGeoModel currentModel;
    private Object2ReferenceMap<String, List<IValue>> eventHandlers;
    private GeoRenderData mainRenderData;

    // 这两个变量不跟随动画一起更新，所以不能放进 stateTracker
    protected float lastFrameTime = -1;
    protected boolean lastMutableRender;
    protected boolean currentFrameTicked;
    protected boolean currentFrameShouldTick;

    private boolean currentFrameExtracted = false;

    protected boolean lastFrameRendered = true;
    protected boolean currentFrameRendered = false;
    protected boolean lastFrameUpdated;
    protected float seekTime;
    protected boolean initialize = false;

    /**
     * 存储 Coded 动画控制器的动画播放状态，用于一些 molang 判断
     */
    protected Map<String, AnimationState> codedAnimationStates = Maps.newHashMap();

    protected AnimatableEntity(TEntity entity) {
        this.entity = entity;
        this.animationProcessor = new AnimationProcessor<>(this);
        this.rateLimiter = new RateLimiter();
        this.stateTracker = createStateTracker(entity);
        this.physicsManager = new PhysicsManager();
        this.rateLimiter.setLimit(getFrameRateLimit());
    }

    protected void resetGeoModel() {
        if (mainRenderData != null) {
            mainRenderData.modelState.close();
            mainRenderData = null;
        }
        for (var data : renderDataPool.values()) {
            data.modelState.close();
        }
        renderDataPool.clear();

        currentModel = null;
        eventHandlers = null;
        animationProcessor.clearModel();
        physicsManager.reset();
        rateLimiter.reset();
        manager.reset();
        stateTracker.reset();
        lastFrameTime = -1;
        lastMutableRender = false;
        currentFrameTicked = false;
        currentFrameShouldTick = false;
        lastFrameRendered = true;
        currentFrameRendered = false;
        currentFrameExtracted = false;
        lastFrameUpdated = false;
        seekTime = 0;
        codedAnimationStates.clear();
    }

    protected EntityStateTracker<TEntity> createStateTracker(TEntity entity) {
        return new EntityStateTracker<>(entity);
    }

    public EntityStateTracker<TEntity> getStateTracker() {
        return stateTracker;
    }

    public float getSeekTime() {
        return seekTime;
    }

    @SuppressWarnings("rawtypes")
    public <T extends AnimatableEntity<TEntity>> void addAnimationController(IAnimationController value) {
        this.manager.addAnimationController(value);
    }

    public AnimationData getAnimationData() {
        return manager;
    }

    public abstract ResourceLocation getTextureLocation();

    public abstract boolean isModelPresent();

    public abstract float getWidthScale();

    public abstract float getHeightScale();

    @Nullable
    public abstract Animation getAnimation(String name);

    @Nullable
    public IValue getUserFunction(String name) {
        return null;
    }

    public Optional<AudioStreamProvider> getSoundStream(String name) {
        return Optional.empty();
    }

    @Nullable
    public final List<IValue> getEventHandler(String name) {
        return this.eventHandlers.get(name);
    }

    public PhysicsManager getPhysicsManager(AnimationEvent<?> event) {
        return physicsManager;
    }

    @Nullable
    public AnimationControllerData getAnimationControllerData(String animationControllerName) {
        return null;
    }

    protected float getSwingMotionAniMathHelperreshold() {
        return 0.15f;
    }

    /**
     * 更新动画之前调用，
     * 如果由于频率限制、renderTick 倒退等原因导致动画不更新，则不会调用
     */
    protected void preAnimationSetup(float seekTime, boolean shouldTick) {
    }

    /**
     * 更新动画之后调用，
     * 如果由于频率限制、renderTick 倒退等原因导致动画不更新，则不会调用
     */
    protected void postAnimationSetup(float seekTime, boolean shouldTick) {
    }

    public final TEntity getEntity() {
        return entity;
    }

    public boolean isFakePlayer() {
        return false;
    }

    @Nullable
    public BoneView getBone(int boneName) {
        return animationProcessor.getBone(boneName);
    }

    protected boolean allowEmitting() {
        return true;
    }

    public int getFrameRateLimit() {
        var localPlayer = Minecraft.getInstance().player;
        if (localPlayer != null && localPlayer != entity) {
            var localPos = localPlayer.position();
            if (localPos.x != 0 || localPos.y != 0 || localPos.z != 0) {
                // 未渲染：屏幕外、太远、被 EntityCulling 剔除
                if (!lastFrameRendered) {
                    return 10;
                }

                var distance = localPlayer.distanceTo(entity);
                // 超远距离：原版超过这个距离会跳过渲染
                if (distance > 64) {
                    return 30;
                }
                // 一般远距离
                if (distance > 40) {
                    return 60;
                }
            }
        }
        return ClientTickEvent.getRefreshRate();
    }

    public @Nullable GeoRenderData update(float partialTicks) {
        return update(partialTicks, resolveRenderContext(RenderUtil.extractRenderContext()));
    }

    @Nullable
    protected GeoRenderData update(float partialTicks, RenderContext context) {
        try (var ignored = NativeProfiler.beginAnimatableUpdate()) {
            if (this.currentModel == null) {
                return null;
            }
            var event = createAnimationEvent(partialTicks, context);
            tickAnimation(event);

            var renderData = getRenderData(context);
            if (!context.immutable() || !currentFrameExtracted) {
                if (context.immutable()) {
                    currentFrameExtracted = true;
                }
                extractRenderData(event, renderData);
                com.elfmcys.ysm.client.renderer.RenderStateModifiers.apply(renderTargetKind(), entity, renderData);
            } else {
                renderData.ctx = context;
            }
            return renderData;
        }
    }

    public com.elfmcys.ysm.api.rendering.v0.TargetKind renderTargetKind() { return null; }

    protected GeoRenderData createRenderData() {
        return new GeoRenderData();
    }

    protected void extractRenderData(AnimationEvent<?> event, GeoRenderData data) {
        extractModelState(data.modelState);
        data.ctx = event.getRenderContext();
        data.texture = getTextureLocation();
        data.widthScale = getWidthScale();
        data.heightScale = getHeightScale();
        data.partialTicks = event.getRequestedPartialTick();
        data.animationData = event.getExtraData();
    }

    protected void extractModelState(com.elfmcys.ysm.geckolib3.geo.animated.GeoModelState state) {
        state.extract(currentModel);
    }

    private GeoRenderData getRenderData(RenderContext context) {
        GeoRenderData renderData;
        if (context.immutable()) {
            if (mainRenderData == null) {
                renderData = createRenderData();
                mainRenderData = renderData;
            } else {
                renderData = mainRenderData;
            }
        } else {
            renderData = renderDataPool.computeIfAbsent(context, c -> createRenderData());
        }
        return renderData;
    }

    private AnimationEvent<AnimatableEntity<TEntity>> createAnimationEvent(float partialTicks, RenderContext context) {
        final Entity entity = this.entity;
        final LivingEntity livingEntity = entity instanceof LivingEntity ? (LivingEntity) entity : null;
        int entityTickCount = this instanceof IPreviewEntity ? ClientTickEvent.getTickCount() : entity.tickCount;
        float realPartialTicks = partialTicks != 1f ? partialTicks : Minecraft.getInstance().getFrameTime();

        boolean shouldSit = entity.isPassenger() && (entity.getVehicle() != null && entity.getVehicle().shouldRiderSit());
        float limbSwingAmount = 0;
        float limbSwing = 0;

        if (!shouldSit && entity.isAlive() && livingEntity != null) {
            limbSwingAmount = livingEntity.walkAnimation.speed(partialTicks);
            limbSwing = livingEntity.walkAnimation.position(partialTicks);
            if (livingEntity.isBaby()) {
                limbSwing *= 3.0F;
            }
        }

        EntityModelData entityModelData = new EntityModelData();
        entityModelData.isSitting = shouldSit;

        float lerpBodyRot = 0;
        float lerpHeadRot = 0;
        float netHeadYaw = 0;

        if (livingEntity != null) {
            entityModelData.isChild = livingEntity.isBaby();
            lerpBodyRot = Mth.rotLerp(partialTicks, livingEntity.yBodyRotO, livingEntity.yBodyRot);
            lerpHeadRot = Mth.rotLerp(partialTicks, livingEntity.yHeadRotO, livingEntity.yHeadRot);
            netHeadYaw = lerpHeadRot - lerpBodyRot;
        }

        if (shouldSit && entity.getVehicle() instanceof LivingEntity) {
            LivingEntity vehicle = (LivingEntity) entity.getVehicle();
            lerpBodyRot = Mth.rotLerp(partialTicks, vehicle.yBodyRotO, vehicle.yBodyRot);
            netHeadYaw = lerpHeadRot - lerpBodyRot;
            float clampedHeadYaw = Mth.clamp(Mth.wrapDegrees(netHeadYaw), -85, 85);
            lerpBodyRot = lerpHeadRot - clampedHeadYaw;
            if (clampedHeadYaw * clampedHeadYaw > 2500f) {
                lerpBodyRot += clampedHeadYaw * 0.2f;
            }
            netHeadYaw = lerpHeadRot - lerpBodyRot;
        }

        entityModelData.rawHeadPitch = Mth.lerp(partialTicks, entity.xRotO, entity.getXRot());
        entityModelData.headPitch = -entityModelData.rawHeadPitch;
        entityModelData.rawNetHeadYaw = netHeadYaw;
        entityModelData.netHeadYaw = -Mth.clamp(Mth.wrapDegrees(netHeadYaw), -85, 85);
        entityModelData.lerpBodyRot = lerpBodyRot;
        entityModelData.lerpedAge = entityTickCount + partialTicks;
        entityModelData.limbSwing = limbSwing;
        entityModelData.limbSwingAmount = limbSwingAmount;
        entityModelData.isMoving =  (limbSwingAmount <= -getSwingMotionAniMathHelperreshold() || limbSwingAmount <= getSwingMotionAniMathHelperreshold());

        return new AnimationEvent<>(this,
                entityTickCount, partialTicks, realPartialTicks,
                context,
                entityModelData,
                getDebugSource());
    }

    protected void tickAnimation(@NotNull AnimationEvent<AnimatableEntity<TEntity>> animationEvent) {
        var frameTime = animationEvent.renderTicks;
        var ctx = animationEvent.getRenderContext();
        var mutableRender = !ctx.immutable();

        if (frameTime > lastFrameTime) {
            currentFrameTicked = false;
            currentFrameShouldTick = false;
            lastFrameTime = frameTime;
            rateLimiter.setLimit(getFrameRateLimit());
            lastFrameRendered = currentFrameRendered;
            currentFrameExtracted = false;
            currentFrameRendered = false;
        } else {
            // 目前不允许倒退，可能会影响 replay 的回放
            frameTime = lastFrameTime;
        }

        if (manager.startTick == -1) {
            manager.startTick = frameTime;
        } else {
            float currentTick = frameTime - manager.startTick;
            float deltaTicks = currentTick - manager.lastTick;
            if (deltaTicks > 0f) {
                manager.lastTick = currentTick;
                this.seekTime += deltaTicks;
            }
        }
        animationEvent.renderTicks = this.seekTime;

        if (!animationProcessor.isModelEmpty()) {
            currentFrameShouldTick |= rateLimiter.request(seekTime / 20);
            var shouldUpdate = (currentFrameShouldTick && !currentFrameTicked) || lastMutableRender || mutableRender;
            var shouldTick = (!mutableRender || (seekTime == 0 && !currentFrameTicked)) && currentFrameShouldTick && !currentFrameTicked;
            recoverLastCodedAnimation(lastFrameUpdated);
            if (shouldUpdate) {
                if (shouldTick) {
                    currentFrameTicked = true;
                    stateTracker.update(animationEvent.getEntityTickCount(), this.seekTime, animationEvent.getPartialTick());
                }
                getPhysicsManager(animationEvent).update(this.seekTime);
                preAnimationSetup(this.seekTime, shouldTick);
                getAnimationProcessor().tickAnimation(animationEvent, shouldTick, allowEmitting());
                postAnimationSetup(this.seekTime, shouldTick);
                lastMutableRender = mutableRender;
            }
            codeAnimation(animationEvent, shouldUpdate);
            lastFrameUpdated = shouldUpdate;
        }
    }

    protected void codeAnimation(AnimationEvent<? extends AnimatableEntity<TEntity>> animationEvent, boolean shouldUpdate) {
    }

    protected void recoverLastCodedAnimation(boolean lastFrameUpdated) {
    }

    public AnimationProcessor<TEntity> getAnimationProcessor() {
        return this.animationProcessor;
    }

    protected abstract void onSetupAnimationController();

    /**
     * 设置模型
     */
    protected void loadGeoModel(@NotNull GeoModel model, Object2ReferenceMap<String, List<IValue>> eventHandlers) {
        resetGeoModel();
        this.currentModel = new AnimatedGeoModel(model);
        this.eventHandlers = eventHandlers;
        onSetupAnimationController();
        this.animationProcessor.loadModel(currentModel, eventHandlers);
        onLoadGeoModel(this.currentModel);
    }

    protected final void setGeoModelInplace(@NotNull GeoModel model) {
        if (currentModel != null && currentModel.getModel() != model) {
            currentModel.setModelInplace(model);
        }
    }

    public void reloadGeoModel() {
        if (this.currentModel != null) {
            var model = this.currentModel.getModel();
            var eventHandlers = this.eventHandlers;
            resetGeoModel();
            loadGeoModel(model, eventHandlers);
        }
    }

    /**
     * 获取当前正在使用的模型
     */
    @Nullable
    public final AnimatedGeoModel getLoadedGeoModel() {
        return currentModel;
    }

    public final boolean hasRenderableModel() {
        return currentModel != null;
    }

    /**
     * 更新当前使用的模型后调用
     */
    protected void onLoadGeoModel(AnimatedGeoModel model) {
    }

    /**
     * 渲染期间是否会保持实体属性不变
     */
    protected final RenderContext resolveRenderContext(RenderContext context) {
        return context.withImmutable(determineImmutableContext(context));
    }

    protected boolean determineImmutableContext(RenderContext context) {
        if (context.inventory() || context.firstPersonMod() || context.paperDoll()) {
            return false;
        }
        return context.level() || context.irisShadow() || context.offScreen();
    }

    public void countRender() {
        currentFrameRendered = true;
    }

    public void executeMolangExp(IValue value, boolean allowEmitting, boolean pre, @Nullable Consumer<String> resultConsumer) {
        animationProcessor.enqueueMolangTask(value, allowEmitting, pre, resultConsumer);
    }

    public IForeignVariableStorage getPublicVariableStorage() {
        return this.animationProcessor.getPublicVariableStorage();
    }

    protected void setInitialized() {
        this.initialize = true;
    }

    public boolean isInitialized() {
        return this.initialize;
    }

    @Nullable
    public DebugSource getDebugSource() {
        return null;
    }

    @SuppressWarnings("resource")
    public boolean isActive() {
        return Minecraft.getInstance().level == entity.level() && !entity.isRemoved();
    }

    public void setCodedAnimationStates(String controllerName, AnimationState state) {
        this.codedAnimationStates.put(controllerName, state);
    }

    public AnimationState getCodedAnimationStates(String controllerName) {
        return this.codedAnimationStates.getOrDefault(controllerName, AnimationState.IDLE);
    }
}
