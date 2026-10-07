package com.elfmcys.ysm.client.entity;

import com.elfmcys.ysm.capability.PlayerAnimatableCapability;
import com.elfmcys.ysm.client.animation.condition.FPArmConditionManager;
import com.elfmcys.ysm.model.resource.client.ResourceLease;
import com.elfmcys.ysm.geckolib3.core.builder.Animation;
import com.elfmcys.ysm.geckolib3.core.builder.controller.AnimationControllerData;
import com.elfmcys.ysm.geckolib3.geo.render.built.GeoModel;
import com.elfmcys.ysm.model.domain.RenderTargetIds;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

public class CustomFirstPersonArmEntity extends CustomEntity<EntityPlayerSP> {
    private final PlayerAnimatableCapability mainModelEntity;

    public CustomFirstPersonArmEntity(EntityPlayerSP player, PlayerAnimatableCapability mainModelEntity) {
        super(player, false);
        this.mainModelEntity = mainModelEntity;
        updateModelHash(mainModelEntity.getModelHash());
    }

    @Override
    protected void onSetupAnimationController() {
        getModelRenderTarget().playerResources().fpArmControllerFactory().accept(this);
    }

    public PlayerAnimatableCapability getMainModelEntity() {
        return mainModelEntity;
    }

    @Override
    public void checkModelUpdate() {
        if (!Objects.equals(mainModelEntity.getModelHash(), getModelHash())) {
            updateModelHash(mainModelEntity.getModelHash());
            return;
        }
        super.checkModelUpdate();
        var resources = getModelRenderTarget() == null ? null : getModelRenderTarget().playerResources();
        var variant = resources == null ? null : resources.variants().get(mainModelEntity.getTextureName());
        if (variant == null && resources != null) {
            variant = resources.defaultVariant();
        }
        var loadedModel = getLoadedGeoModel();
        if (variant != null && loadedModel != null && loadedModel.getModel() != variant.armModel()) {
            waitForAsyncUpdate();
            setGeoModelInplace(variant.armModel());
        }
    }

    @Override
    protected @Nullable ResourceHolder createResourceHolder(ResourceLease lease, boolean isFallback) {
        return new ResourceHolder(lease, isFallback);
    }

    @Override
    protected String requestedTextureName() {
        return mainModelEntity.getTextureName();
    }

    @Override
    protected String requestedRenderTargetId() {
        return RenderTargetIds.PLAYER;
    }

    @Override
    public @Nullable AnimationControllerData getAnimationControllerData(String animationControllerName) {
        return getModelRenderTarget().playerResources().animationControllers().get(animationControllerName);
    }

    @Override
    public ResourceLocation getTextureLocation() {
        return mainModelEntity.getTextureLocation();
    }

    @Override
    public float getWidthScale() {
        return getModelRenderTarget().info().getPlayerSettings().widthScale();
    }

    @Override
    public float getHeightScale() {
        return getModelRenderTarget().info().getPlayerSettings().heightScale();
    }

    @Override
    public @Nullable Animation getAnimation(String name) {
        return getModelRenderTarget().playerResources().fpArmAnimations().get(name);
    }

    public FPArmConditionManager getFPArmConditionManager() {
        return getModelRenderTarget().playerResources().fpArmConditionManager();
    }

    @Override
    protected GeoModel getYsmGeoModel() {
        var resources = Objects.requireNonNull(getModelRenderTarget().playerResources());
        var variant = resources.variants().get(mainModelEntity.getTextureName());
        return (variant == null ? resources.defaultVariant() : variant).armModel();
    }

    @Override
    protected void preAnimationSetup(float seekTime, boolean shouldTick) {
        // 设置 roaming 变量
        getAnimationProcessor().putRemoteStruct(mainModelEntity.getRoamingStruct());
    }
}
