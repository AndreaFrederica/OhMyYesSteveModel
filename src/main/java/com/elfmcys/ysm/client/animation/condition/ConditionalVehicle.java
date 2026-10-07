package com.elfmcys.ysm.client.animation.condition;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import org.jetbrains.annotations.Nullable;

public class ConditionalVehicle extends AbstractConditionEntity {
    public ConditionalVehicle() {
        super("vehicle");
    }

    @Nullable
    @Override
    protected Entity getEntity(EntityLivingBase livingEntity) {
        return livingEntity.getRidingEntity();
    }
}
