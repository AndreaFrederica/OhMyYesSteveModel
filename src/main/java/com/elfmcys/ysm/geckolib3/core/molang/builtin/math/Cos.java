package com.elfmcys.ysm.geckolib3.core.molang.builtin.math;

import com.elfmcys.ysm.geckolib3.core.util.MathUtil;
import com.elfmcys.ysm.molang.runtime.ExecutionContext;
import com.elfmcys.ysm.molang.runtime.Function;
import net.minecraft.util.math.MathHelper;

public class Cos implements Function {
    @Override
    public Object evaluate(ExecutionContext<?> context, ArgumentCollection arguments) {
        return MathHelper.cos(arguments.getAsFloat(context, 0) / 180f * MathUtil.PI);
    }

    @Override
    public boolean validateArgumentSize(int size) {
        return size == 1;
    }
}
