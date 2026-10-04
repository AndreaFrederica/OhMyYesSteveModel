package com.elfmcys.ysm.client.entity;

import com.elfmcys.ysm.geckolib3.core.builder.Animation;
import com.elfmcys.ysm.geckolib3.core.builder.controller.AnimationControllerData;
import com.elfmcys.ysm.geckolib3.geo.render.built.GeoModel;
import com.elfmcys.ysm.model.resource.client.AcquireResult;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.model.resource.client.ProjectileModelResources;
import com.elfmcys.ysm.model.resource.client.ResourceLease;
import com.elfmcys.ysm.model.service.ClientModelService;
import com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.projectile.Projectile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class CustomProjectileEntity extends CustomEntity<Projectile> {
    @Override
    public com.elfmcys.ysm.api.rendering.v0.TargetKind renderTargetKind() {
        return com.elfmcys.ysm.api.rendering.v0.TargetKind.PROJECTILE;
    }

    private ProjectileModelResources projectileResources;

    public CustomProjectileEntity(Projectile projectile) {
        super(projectile, true);
    }

    @Override
    protected String requestedRenderTargetId() {
        var hash = getModelHash();
        return hash == null ? null : ClientModelService.instance()
                .findRenderTarget(hash,
                        RenderTargetKind.RENDER_TARGET_KIND_PROJECTILE,
                        entity.getType().builtInRegistryHolder().key().location()).orElse(null);
    }

    @Override
    protected String fallbackRenderTargetId() {
        return null;
    }

    @Override
    protected void onSetupAnimationController() {
        if (projectileResources != null) {
            projectileResources.controllerFactory().accept(this);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected @Nullable ResourceHolder createResourceHolder(ResourceLease lease, boolean isFallback) {
        if (!(lease.poll() instanceof AcquireResult.Ready ready)) {
            return null;
        }
        var model = ready.target();
        if (!isFallback) {
            var projectileResources = model.projectileResources();
            if (projectileResources != null || model.generalMeshResources()!=null) {
                return new ResourceHolder(lease, false);
            }
        }
        return null;
    }

    /**
     * 当前箭矢没有模型时，由于跳过渲染而不会检查模型更新，此时依赖于异步更新的机制检查。
     */
    @Override
    @SuppressWarnings("deprecation")
    protected void onModelRenderTargetLoaded(ModelRenderTarget newModel) {
        super.onModelRenderTargetLoaded(newModel);
        projectileResources = newModel.projectileResources();
    }

    @Override
    public void resetModelRenderTarget() {
        super.resetModelRenderTarget();
        projectileResources = null;
    }

    @Override
    protected GeoModel getYsmGeoModel() {
        return projectileResources.model();
    }

    @Override
    @NotNull
    public ResourceLocation getTextureLocation() {
        return getModelRenderTarget().textureId();
    }

    @Override
    public Animation getAnimation(String name) {
        if(isGeneralMesh()) return null;
        return projectileResources.animations().get(name);
    }

    @Override
    public @Nullable AnimationControllerData getAnimationControllerData(String animationControllerName) {
        if(isGeneralMesh()) return null;
        return projectileResources.controllers().get(animationControllerName);
    }

    @Override
    public boolean isModelPresent() {
        return super.isModelPresent() && (projectileResources != null || isGeneralMesh()) && getResourceHolder().isLoaded();
    }

    @Override
    public float getWidthScale() {
        return 0.7F;
    }

    @Override
    public float getHeightScale() {
        return 0.7F;
    }

}
