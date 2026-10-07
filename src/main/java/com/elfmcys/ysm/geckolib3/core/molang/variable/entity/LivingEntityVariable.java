package com.elfmcys.ysm.geckolib3.core.molang.variable.entity;

import com.elfmcys.ysm.geckolib3.core.molang.variable.IValueEvaluator;
import com.elfmcys.ysm.geckolib3.core.molang.context.IContext;
import com.elfmcys.ysm.geckolib3.core.molang.variable.LambdaVariable;
import net.minecraft.entity.EntityLivingBase;

public class LivingEntityVariable extends LambdaVariable<EntityLivingBase> {
    public LivingEntityVariable(IValueEvaluator<?, IContext<EntityLivingBase>> evaluator) {
        super(evaluator);
    }

    @Override
    protected boolean validateContext(IContext<?> context) {
        return context.entity() instanceof EntityLivingBase;
    }
}
