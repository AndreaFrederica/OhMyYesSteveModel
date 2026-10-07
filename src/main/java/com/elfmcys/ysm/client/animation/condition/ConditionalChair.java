package com.elfmcys.ysm.client.animation.condition;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import org.jetbrains.annotations.Nullable;

// TODO
public class ConditionalChair extends AbstractConditionEntity {
    public ConditionalChair() {
        super("chair");
    }

    @Nullable
    @Override
    protected Entity getEntity(EntityLivingBase livingEntity) {
        return null;
    }
}
