package com.elfmcys.ysm.geckolib3.core.molang.variable.entity;

import com.elfmcys.ysm.geckolib3.core.molang.variable.IValueEvaluator;
import com.elfmcys.ysm.geckolib3.core.molang.context.IContext;
import com.elfmcys.ysm.geckolib3.core.molang.variable.LambdaVariable;
import net.minecraft.client.entity.EntityPlayerSP;

public class LocalPlayerVariable extends LambdaVariable<EntityPlayerSP> {
    public LocalPlayerVariable(IValueEvaluator<?, IContext<EntityPlayerSP>> evaluator) {
        super(evaluator);
    }

    @Override
    protected boolean validateContext(IContext<?> context) {
        return context.entity() instanceof EntityPlayerSP;
    }
}
