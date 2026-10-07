package com.elfmcys.ysm.client.animation.molang.variable;

import com.elfmcys.ysm.geckolib3.core.molang.context.IContext;
import com.elfmcys.ysm.geckolib3.core.molang.variable.IValueEvaluator;
import net.minecraft.entity.player.EntityPlayer;

public class FirstPersonModHideVariable implements IValueEvaluator<Boolean, IContext<EntityPlayer>> {
    @Override
    public Boolean eval(IContext<EntityPlayer> ctx) {
        return ctx.animationEvent().getRenderContext().firstPersonMod();
    }
}
