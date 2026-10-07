package com.elfmcys.ysm.client.renderer;

import com.elfmcys.ysm.capability.VehicleAnimatableCapabilityProvider;
import com.elfmcys.ysm.client.entity.CustomVehicleEntity;
import com.elfmcys.ysm.geckolib3.geo.GeoEntityRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.NotNull;

public class CustomVehicleRenderer extends GeoEntityRenderer<Entity, CustomVehicleEntity> {
    public CustomVehicleRenderer(RenderManager renderManager) {
        super(renderManager);
    }

    @Override
    public void doRender(Entity entity, double x, double y, double z, float yaw, float partialTick) {
        if (Minecraft.getMinecraft().player == null || entity.isInvisibleToPlayer(Minecraft.getMinecraft().player)) {
            return;
        }
        var cap = entity.getCapability(VehicleAnimatableCapabilityProvider.CAP, null);
        if (cap != null) {
            cap.checkModelUpdate();
            render(cap, x, y, z, yaw, partialTick);
        }
    }

    @Override
    @NotNull
    protected ResourceLocation getEntityTexture(Entity entity) {
        var cap = entity.getCapability(VehicleAnimatableCapabilityProvider.CAP, null);
        return cap != null ? cap.getTextureLocation() : TextureMap.LOCATION_MISSING_TEXTURE;
    }
}
