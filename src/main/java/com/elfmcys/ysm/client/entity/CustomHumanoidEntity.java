package com.elfmcys.ysm.client.entity;

import com.elfmcys.ysm.model.service.ClientModelService;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.model.resource.client.PlayerModelVariant;
import com.elfmcys.ysm.model.resource.client.ResourceLease;

import com.elfmcys.ysm.client.animation.condition.ConditionManager;
import com.elfmcys.ysm.client.animation.molang.MolangEventWrapper;
import com.elfmcys.ysm.client.model.locator.PlayerLocator;
import com.elfmcys.ysm.geckolib3.core.builder.Animation;
import com.elfmcys.ysm.geckolib3.core.builder.controller.AnimationControllerData;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.geckolib3.core.molang.value.IValue;
import com.elfmcys.ysm.geckolib3.geo.GeoRenderData;
import com.elfmcys.ysm.geckolib3.geo.render.built.GeoModel;
import com.elfmcys.ysm.geckolib3.model.AnimatableEntity;
import com.elfmcys.ysm.geckolib3.model.AnimatedGeoModel;
import com.elfmcys.ysm.model.domain.Hash256;
import it.unimi.dsi.fastutil.booleans.BooleanArrayList;
import it.unimi.dsi.fastutil.booleans.BooleanList;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Math;
import org.joml.Vector2f;

import java.util.Objects;

/**
 * 使用玩家模型的实体
 */
public abstract class CustomHumanoidEntity<T extends LivingEntity> extends CustomEntity<T> {
    protected String textureName;
    @Nullable
    private PlayerModelVariant modelVariant;

    private final FloatArrayList headRotBackup = new FloatArrayList(2);
    private boolean fireInitEvent = false;
    private IValue wrappedUpdateHandler = null;
    private final BooleanList updateHandlerArgs = new BooleanArrayList(1);

    /**
     * 用于禁用 YSM 模型，因为有玩家想强制显示原版玩家模型
     */
    private boolean disabled = false;

    /**
     * 专为 tacz 枪械事件使用的，用来将枪械动画重置
     */
    private boolean tacGunAnimationNeedReload = false;

    protected CustomHumanoidEntity(T entity, boolean asyncUpdate) {
        this(entity, asyncUpdate, new EntityModelBinding());
    }

    CustomHumanoidEntity(T entity, boolean asyncUpdate, EntityModelBinding modelBinding) {
        super(entity, asyncUpdate, modelBinding);
        updateHandlerArgs.size(1);
    }

    @Override
    protected void codeAnimation(AnimationEvent<? extends AnimatableEntity<T>> animationEvent, boolean shouldUpdate) {
        var model = getLoadedGeoModel();
        if (model != null) {
            // 更新头部旋转
            var heads = model.locatorGroup(PlayerLocator.get().head);
            var headRotBak = headRotBackup;
            for (var i = 0; i < heads.size(); i++) {
                var head = heads.get(i);

                float rotX;
                float rotY;
                if (shouldUpdate) {
                    rotX = head.getRotationX();
                    rotY = head.getRotationY();
                    headRotBak.set(i * 2, rotX);
                    headRotBak.set(i * 2 + 1, rotY);
                } else {
                    rotX = headRotBak.getFloat(i * 2);
                    rotY = headRotBak.getFloat(i * 2 + 1);
                }

                var data = animationEvent.getExtraData();
                head.setRotationX(rotX + Math.toRadians(data.headPitch));
                head.setRotationY(rotY + Math.toRadians(data.netHeadYaw));
            }
        }
    }

    @Override
    protected void extractRenderData(AnimationEvent<?> event, GeoRenderData data) {
        super.extractRenderData(event, data);
        data.renderLayersFirst = renderLayersFirst();
    }

    @Override
    protected void recoverLastCodedAnimation(boolean lastFrameUpdated) {
        var model = getLoadedGeoModel();
        if (model != null) {
            var heads = model.locatorGroup(PlayerLocator.get().head);
            var headRotBak = headRotBackup;
            for (var i = 0; i < heads.size(); i++) {
                var head = heads.get(i);
                head.setRotationX(headRotBak.getFloat(i * 2));
                head.setRotationY(headRotBak.getFloat(i * 2 + 1));
            }
        }
    }

    @Override
    protected HumanoidStateTracker<T> createStateTracker(T entity) {
        return new HumanoidStateTracker<T>(entity);
    }

    @Override
    public HumanoidStateTracker<T> getStateTracker() {
        return (HumanoidStateTracker<T>) super.getStateTracker();
    }

    public void updateTextureName(String textureName) {
        this.textureName = textureName;
        updateTexture(true);
    }

    public void updateModelAndTexture(Hash256 modelHash, String textureName) {
        setInitialized();
        this.textureName = textureName;
        updateModelHash(modelHash);
        updateTexture(true);
    }

    public void updateModelAndTexture(String modelPath, String textureName) {
        ClientModelService.instance().resolvePath(modelPath)
                .ifPresent(hash -> updateModelAndTexture(hash, textureName));
    }

    @Override
    protected String requestedTextureName() {
        return textureName == null ? "" : textureName;
    }

    public void setDisabled(boolean disabled) {
        this.disabled = disabled;
    }

    public boolean isDisabled() {
        return disabled;
    }

    public boolean isInitializedAndEnabled() {
        return isInitialized() && !disabled;
    }

    @Override
    protected void onModelRenderTargetLoaded(ModelRenderTarget newModel) {
        super.onModelRenderTargetLoaded(newModel);
        updateTexture(false);
        var updateHandlers = newModel.assets().eventHandlers().get(MolangEventWrapper.PLAYER_UPDATE);
        if (updateHandlers != null) {
            wrappedUpdateHandler = MolangEventWrapper.wrap(updateHandlers, updateHandlerArgs);
        } else {
            wrappedUpdateHandler = null;
        }
    }

    @Override
    protected void resetModelRenderTarget() {
        super.resetModelRenderTarget();
        modelVariant = null;
        wrappedUpdateHandler = null;
    }

    @Override
    protected void onLoadGeoModel(AnimatedGeoModel model) {
        super.onLoadGeoModel(model);
        var heads = model.locatorGroup(PlayerLocator.get().head);
        var headRot = headRotBackup;
        headRot.size(heads.size() * 2);
        for (var i = 0; i < heads.size(); i++) {
            var head = heads.get(i);
            var rot = new Vector2f(head.getRotationX(), head.getRotationY());
            headRot.set(i * 2, rot.x);
            headRot.set(i * 2 + 1, rot.y);
        }
    }

    @Override
    protected void resetGeoModel() {
        super.resetGeoModel();
        headRotBackup.clear();
        tacGunAnimationNeedReload = false;
        fireInitEvent = true;
    }

    @Override
    public void reset() {
        super.reset();
        textureName = null;
        disabled = false;
    }

    @Override
    protected void preAnimationSetup(float seekTime, boolean shouldTick) {
        super.preAnimationSetup(seekTime, shouldTick);
        if (fireInitEvent) {
            fireInitEvent = false;
            var initEvent = getEventHandler(MolangEventWrapper.PLAYER_INIT);
            if (initEvent != null) {
                executeMolangExp(MolangEventWrapper.wrap(initEvent), true, true, null);
            }
        }
        if (wrappedUpdateHandler != null) {
            updateHandlerArgs.set(0, shouldTick);
            executeMolangExp(wrappedUpdateHandler, true, true, null);
        }
    }

    public ConditionManager getConditionManager() {
        return getModelRenderTarget().playerResources().conditionManager();
    }

    @SuppressWarnings("unchecked")
    private void updateTexture(boolean replaceModel) {
        if (getModelRenderTarget() == null) {
            return;
        }
        var playerResources = getModelRenderTarget().playerResources();
        if(playerResources==null) { modelVariant=null;return; }
        var variant = isModelPresent() ? playerResources.variants().get(textureName) : null;
        if (variant == null) {
            variant = playerResources.defaultVariant();
        }
        if (variant != modelVariant) {
            waitForAsyncUpdate();
            modelVariant = variant;
            if (replaceModel) {
                setGeoModelInplace(variant.mainModel());
            }
        }
    }

    @Override
    public GeoModel getYsmGeoModel() {
        return Objects.requireNonNull(modelVariant, "modelVariant").mainModel();
    }

    @Nullable
    @Override
    public Animation getAnimation(String name) {
        if(isGeneralMesh()) return null;
        return getModelRenderTarget().playerResources().animations().get(name);
    }

    @Override
    public @Nullable AnimationControllerData getAnimationControllerData(String name) {
        if(isGeneralMesh()) return null;
        return getModelRenderTarget().playerResources().animationControllers().get(name);
    }

    public String getTextureName() {
        if(isGeneralMesh()) return "";
        return isModelPresent() ? textureName : getModelRenderTarget().playerResources().defaultTextureName();
    }

    @Override
    @NotNull
    @SuppressWarnings("unchecked")
    public ResourceLocation getTextureLocation() {
        return getModelRenderTarget().textureId();
    }

    @Nullable
    public PlayerModelVariant getModelVariant() {
        return modelVariant;
    }

    @Override
    public float getHeightScale() {
        return getModelRenderTarget().info().getPlayerSettings().heightScale();
    }

    @Override
    public float getWidthScale() {
        return getModelRenderTarget().info().getPlayerSettings().widthScale();
    }

    public boolean renderLayersFirst() {
        return getModelRenderTarget().info().getPlayerSettings().renderLayersFirst();
    }

    public boolean isTacGunAnimationNeedReload() {
        return tacGunAnimationNeedReload;
    }

    public void setTacGunAnimationNeedReload(boolean tacGunAnimationNeedReload) {
        this.tacGunAnimationNeedReload = tacGunAnimationNeedReload;
    }

    protected class HumanoidResourceHolder extends ResourceHolder {
        public HumanoidResourceHolder(ResourceLease lease, boolean fallback) {
            super(lease, fallback);
        }
    }

}
