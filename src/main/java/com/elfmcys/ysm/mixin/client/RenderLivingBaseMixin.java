package com.elfmcys.ysm.mixin.client;

import com.elfmcys.ysm.accessor.ILivingRenderer;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderLivingBase;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.EntityLivingBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(RenderLivingBase.class)
public abstract class RenderLivingBaseMixin extends Render<EntityLivingBase> implements ILivingRenderer {
    protected RenderLivingBaseMixin(RenderManager manager) {
        super(manager);
    }

    @Unique
    @Override
    public void ysm$renderNameTag(EntityLivingBase entity, double x, double y, double z, float entityYaw, float partialTicks) {
        super.doRender(entity, x, y, z, entityYaw, partialTicks);
    }
}
