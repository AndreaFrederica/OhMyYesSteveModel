package com.elfmcys.ysm.accessor;

import net.minecraft.entity.EntityLivingBase;

public interface ILivingRenderer {
    void ysm$renderNameTag(EntityLivingBase entity, double x, double y, double z, float entityYaw, float partialTicks);
}
