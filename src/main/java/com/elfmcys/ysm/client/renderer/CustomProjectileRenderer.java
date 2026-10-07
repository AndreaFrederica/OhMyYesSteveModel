package com.elfmcys.ysm.client.renderer;

import com.elfmcys.ysm.capability.ProjectileAnimatableCapabilityProvider;
import com.elfmcys.ysm.client.entity.CustomProjectileEntity;
import com.elfmcys.ysm.geckolib3.geo.GeoProjectilesRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.entity.Entity;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.NotNull;

public class CustomProjectileRenderer extends GeoProjectilesRenderer<Entity, CustomProjectileEntity> {
    public CustomProjectileRenderer(RenderManager renderManager) {
        super(renderManager);
    }

    @Override
    public void doRender(Entity entity, double x, double y, double z, float yaw, float partialTick) {
        if (Minecraft.getMinecraft().player == null || entity.isInvisibleToPlayer(Minecraft.getMinecraft().player)) {
            return;
        }
        var cap = entity.getCapability(ProjectileAnimatableCapabilityProvider.CAP, null);
        if (cap != null) {
            cap.checkModelUpdate();
            render(cap, x, y, z, yaw, partialTick);
        }
    }

    @Override
    @NotNull
    protected ResourceLocation getEntityTexture(Entity entity) {
        var cap = entity.getCapability(ProjectileAnimatableCapabilityProvider.CAP, null);
        return cap != null ? cap.getTextureLocation() : TextureMap.LOCATION_MISSING_TEXTURE;
    }
}
