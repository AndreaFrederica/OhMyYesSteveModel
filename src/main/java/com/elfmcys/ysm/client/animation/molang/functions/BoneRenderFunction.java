package com.elfmcys.ysm.client.animation.molang.functions;

import com.elfmcys.ysm.geckolib3.core.molang.context.IContext;
import com.elfmcys.ysm.geckolib3.core.molang.function.entity.EntityFunction;
import com.elfmcys.ysm.geckolib3.core.molang.util.StringPool;
import com.elfmcys.ysm.geckolib3.model.AnimatedGeoBone;
import com.elfmcys.ysm.molang.runtime.ExecutionContext;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;

public abstract class BoneRenderFunction extends EntityFunction {
    private final int argumentCount;

    private BoneRenderFunction(int argumentCount) {
        this.argumentCount = argumentCount;
    }

    @Override
    public final boolean validateArgumentSize(int size) {
        return size == argumentCount;
    }

    @Override
    protected final Object eval(ExecutionContext<IContext<Entity>> context,
                                ArgumentCollection arguments) {
        if (!context.entity().allowEmitting()) {
            return null;
        }

        var boneName = arguments.getAsPooledString(context, 0);
        if (boneName == StringPool.EMPTY) {
            return null;
        }

        var bone = context.entity().animatableEntity().getBone(boneName);
        if (!(bone instanceof AnimatedGeoBone animatedBone)) {
            return null;
        }

        apply(context, arguments, animatedBone);
        return null;
    }

    protected abstract void apply(ExecutionContext<IContext<Entity>> context,
                                  ArgumentCollection arguments,
                                  AnimatedGeoBone bone);

    static int roundedClamp(float value, int minimum, int maximum) {
        return MathHelper.clamp(Math.round(value), minimum, maximum);
    }

    public static final class Color extends BoneRenderFunction {
        public Color() {
            super(4);
        }

        @Override
        protected void apply(ExecutionContext<IContext<Entity>> context,
                             ArgumentCollection arguments,
                             AnimatedGeoBone bone) {
            bone.setColor(
                    roundedClamp(arguments.getAsFloat(context, 1), 0, 255),
                    roundedClamp(arguments.getAsFloat(context, 2), 0, 255),
                    roundedClamp(arguments.getAsFloat(context, 3), 0, 255));
        }
    }

    public static final class Transparency extends BoneRenderFunction {
        public Transparency() {
            super(2);
        }

        @Override
        protected void apply(ExecutionContext<IContext<Entity>> context,
                             ArgumentCollection arguments,
                             AnimatedGeoBone bone) {
            bone.setTransparency(
                    roundedClamp(arguments.getAsFloat(context, 1), 0, 255));
        }
    }

    public static final class Glow extends BoneRenderFunction {
        public Glow() {
            super(2);
        }

        @Override
        protected void apply(ExecutionContext<IContext<Entity>> context,
                             ArgumentCollection arguments,
                             AnimatedGeoBone bone) {
            bone.setGlow(
                    roundedClamp(arguments.getAsFloat(context, 1), -1, 15));
        }
    }
}
