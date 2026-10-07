package com.elfmcys.ysm.client.animation.molang.functions;

import com.elfmcys.ysm.client.particle.ParticleSpawner;
import com.elfmcys.ysm.geckolib3.core.molang.context.IContext;
import com.elfmcys.ysm.geckolib3.core.molang.function.entity.EntityFunction;
import com.elfmcys.ysm.molang.runtime.ExecutionContext;
import net.minecraft.entity.Entity;

public class ParticleFunction extends EntityFunction {
    private final boolean absPos;

    public ParticleFunction(boolean absPos) {
        this.absPos = absPos;
    }

    @Override
    protected Object eval(ExecutionContext<IContext<Entity>> context, ArgumentCollection arguments) {
        if (!context.entity().allowEmitting() || context.entity().animatableEntity().isFakePlayer()) {
            return null;
        }
        return ParticleSpawner.evalSpawnParticle(context, arguments, this.absPos);
    }

    @Override
    public boolean validateArgumentSize(int size) {
        return size >= 1;
    }
}
