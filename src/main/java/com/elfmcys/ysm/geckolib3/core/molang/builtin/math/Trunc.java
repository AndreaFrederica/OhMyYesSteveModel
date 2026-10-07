package com.elfmcys.ysm.geckolib3.core.molang.builtin.math;

import com.elfmcys.ysm.molang.runtime.ExecutionContext;
import com.elfmcys.ysm.molang.runtime.Function;
import net.minecraft.util.math.MathHelper;

public class Trunc implements Function {
    @Override
    public Object evaluate(ExecutionContext<?> context, ArgumentCollection arguments) {
        double value = arguments.getAsFloat(context, 0);
        return value < 0 ? MathHelper.ceil(value) : MathHelper.floor(value);
    }

    @Override
    public boolean validateArgumentSize(int size) {
        return size == 1;
    }
}
