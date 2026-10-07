package com.elfmcys.ysm.client.animation.molang.variable;

import com.elfmcys.ysm.client.entity.CustomPlayerEntity;
import com.elfmcys.ysm.geckolib3.core.molang.context.IContext;
import com.elfmcys.ysm.geckolib3.core.molang.variable.IValueEvaluator;
import net.minecraft.entity.player.EntityPlayer;

public class TextureNameVariable implements IValueEvaluator<String, IContext<EntityPlayer>> {
    @Override
    public String eval(IContext<EntityPlayer> ctx) {
        if (ctx.animatableEntity() instanceof CustomPlayerEntity animatableEntity) {
            return animatableEntity.getTextureName();
        } else {
            return null;
        }
    }
}
