package com.elfmcys.ysm.client.animation.condition;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import org.jetbrains.annotations.Nullable;

public class ConditionalPassenger extends AbstractConditionEntity {
    public ConditionalPassenger() {
        super("passenger");
    }

    @Nullable
    @Override
    protected Entity getEntity(EntityLivingBase livingEntity) {
        return livingEntity.getControllingPassenger();
    }
}
