package com.elfmcys.ysm.client.entity;

import com.elfmcys.ysm.client.controller.VehicleOriginController;
import com.elfmcys.ysm.client.controller.collections.VehicleControllerCollection;
import com.elfmcys.ysm.geckolib3.core.builder.Animation;
import com.elfmcys.ysm.geckolib3.core.builder.controller.AnimationControllerData;
import com.elfmcys.ysm.geckolib3.geo.render.built.GeoModel;
import com.elfmcys.ysm.model.resource.client.AcquireResult;
import com.elfmcys.ysm.model.resource.client.ModelRenderTarget;
import com.elfmcys.ysm.model.resource.client.ResourceLease;
import com.elfmcys.ysm.model.resource.client.VehicleModelResources;
import com.elfmcys.ysm.model.service.ClientModelService;
import com.elfmcys.ysm.proto.mixel.manifest.asset.RenderTargetKind;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

public class CustomVehicleEntity extends CustomEntity<Entity> {
    @Override
    public com.elfmcys.ysm.api.rendering.v0.TargetKind renderTargetKind() {
        return com.elfmcys.ysm.api.rendering.v0.TargetKind.VEHICLE;
    }

    private VehicleModelResources vehicleResources;
    private VehicleOriginController originController;

    public CustomVehicleEntity(Entity vehicle) {
        super(vehicle, true);
    }

    @Override
    protected String requestedRenderTargetId() {
        var hash = getModelHash();
        return hash == null ? null : ClientModelService.instance()
                .findRenderTarget(hash,
                        RenderTargetKind.RENDER_TARGET_KIND_VEHICLE,
                        entity.getType().builtInRegistryHolder().key().location()).orElse(null);
    }

    @Override
    protected String fallbackRenderTargetId() {
        return null;
    }

    @Override
    protected void onSetupAnimationController() {
        if (vehicleResources != null) {
            vehicleResources.controllerFactory().accept(this);
            originController = (VehicleOriginController) getAnimationData().getAnimationController(VehicleControllerCollection.NAME_ORIGIN);
        }
    }

    @Nullable
    public Vector3f getRotation() {
        if (originController != null) {
            return originController.getRotation();
        }
        return null;
    }

    @Override
    @SuppressWarnings("deprecation")
    protected @Nullable ResourceHolder createResourceHolder(ResourceLease lease, boolean isFallback) {
        if (!(lease.poll() instanceof AcquireResult.Ready ready)) {
            return null;
        }
        var model = ready.target();
        var vehicleResources = model.vehicleResources();
        if (vehicleResources != null || model.generalMeshResources()!=null) {
            return new ResourceHolder(lease, isFallback);
        }
        return null;
    }

    /**
     * 当前载具没有模型时，由于跳过渲染而不会检查模型更新，此时依赖于异步更新的机制检查。
     */
    @Override
    @SuppressWarnings("deprecation")
    protected void onModelRenderTargetLoaded(ModelRenderTarget newModel) {
        super.onModelRenderTargetLoaded(newModel);
        vehicleResources = newModel.vehicleResources();
    }

    @Override
    public void resetModelRenderTarget() {
        super.resetModelRenderTarget();
        this.vehicleResources = null;
        this.originController = null;
    }

    @Override
    protected GeoModel getYsmGeoModel() {
        return vehicleResources.model();
    }

    @Override
    @NotNull
    public ResourceLocation getTextureLocation() {
        return getModelRenderTarget().textureId();
    }

    @Override
    public Animation getAnimation(String name) {
        if(isGeneralMesh()) return null;
        return vehicleResources.animations().get(name);
    }

    @Override
    public @Nullable AnimationControllerData getAnimationControllerData(String animationControllerName) {
        if(isGeneralMesh()) return null;
        return vehicleResources.controllers().get(animationControllerName);
    }

    @Override
    public boolean isModelPresent() {
        return super.isModelPresent() && (vehicleResources != null || isGeneralMesh()) && getResourceHolder().isLoaded();
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
