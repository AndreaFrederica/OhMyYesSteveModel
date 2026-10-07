package com.elfmcys.ysm.geckolib3.core.molang.builtin.query;

import com.elfmcys.ysm.geckolib3.core.molang.context.IContext;
import com.elfmcys.ysm.geckolib3.core.molang.function.entity.EntityFunction;
import com.elfmcys.ysm.molang.runtime.ExecutionContext;
import net.minecraft.entity.Entity;

public class PositionDelta extends EntityFunction {
    @Override
    protected Object eval(ExecutionContext<IContext<Entity>> context, ArgumentCollection arguments) {
        int axis = arguments.getAsInt(context, 0);
        var delta = context.entity().animatableEntity().getStateTracker().getPositionDelta();
        switch (axis) {
            case 0: return delta.x;
            case 1: return delta.y;
            case 2: return delta.z;
            default: return null;
        }
    }

    @Override
    public boolean validateArgumentSize(int size) {
        return size == 1;
    }
}
