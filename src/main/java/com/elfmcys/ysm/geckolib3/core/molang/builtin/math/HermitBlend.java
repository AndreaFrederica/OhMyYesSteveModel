package com.elfmcys.ysm.geckolib3.core.molang.builtin.math;

import com.elfmcys.ysm.molang.runtime.ExecutionContext;
import com.elfmcys.ysm.molang.runtime.Function;
import net.minecraft.util.math.MathHelper;

public class HermitBlend implements Function {
    @Override
    public Object evaluate(ExecutionContext<?> context, ArgumentCollection arguments) {
        double min = MathHelper.ceil(arguments.getAsFloat(context, 0));
        return MathHelper.floor(3.0d * Math.pow(min, 2.0d) - 2.0d * Math.pow(min, 3.0d));
    }

    @Override
    public boolean validateArgumentSize(int size) {
        return size == 1;
    }
}
