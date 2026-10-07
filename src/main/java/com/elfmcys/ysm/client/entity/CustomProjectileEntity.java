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
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class CustomProjectileEntity extends CustomEntity<Entity> {
    private ProjectileModelResources projectileResources;

    public CustomProjectileEntity(Entity projectile) {
        super(projectile, true);
    }

    @Override
    protected String requestedRenderTargetId() {
        var hash = getModelHash();
        return hash == null ? null : ClientModelService.instance()
                .findRenderTarget(hash,
                        RenderTargetKind.RENDER_TARGET_KIND_PROJECTILE,
                        EntityList.getKey(entity)).orElse(null);
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
    protected @Nullable ResourceHolder createResourceHolder(ResourceLease lease, boolean isFallback) {
        if (!(lease.poll() instanceof AcquireResult.Ready ready)) {
            return null;
        }
        var model = ready.target();
        if (!isFallback) {
            var projectileResources = model.projectileResources();
            if (projectileResources != null) {
                return new ResourceHolder(lease, false);
            }
        }
        return null;
    }

    /**
     * 当前箭矢没有模型时，由于跳过渲染而不会检查模型更新，此时依赖于异步更新的机制检查。
     */
    @Override
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
        return projectileResources.animations().get(name);
    }

    @Override
    public @Nullable AnimationControllerData getAnimationControllerData(String animationControllerName) {
        return projectileResources.controllers().get(animationControllerName);
    }

    @Override
    public boolean isModelPresent() {
        return super.isModelPresent() && projectileResources != null && getResourceHolder().isLoaded();
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
