package com.elfmcys.ysm.geckolib3.core.molang.variable.entity;

import com.elfmcys.ysm.geckolib3.core.molang.context.IContext;
import com.elfmcys.ysm.geckolib3.core.molang.variable.IValueEvaluator;
import com.elfmcys.ysm.geckolib3.core.molang.variable.LambdaVariable;
import net.minecraft.entity.projectile.EntityFishHook;

public class FishingHookVariable extends LambdaVariable<EntityFishHook> {
    public FishingHookVariable(IValueEvaluator<?, IContext<EntityFishHook>> evaluator) {
        super(evaluator);
    }

    @Override
    protected boolean validateContext(IContext<?> context) {
        return context.entity() instanceof EntityFishHook;
    }
}
