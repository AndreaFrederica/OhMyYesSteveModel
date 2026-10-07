package com.elfmcys.ysm.client.animation;

import com.elfmcys.ysm.geckolib3.core.builder.LoopType;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.geckolib3.model.AnimatableEntity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.math.MathHelper;

import java.util.function.BiPredicate;

public class AnimationState<TE extends EntityLivingBase, AE extends AnimatableEntity<TE>> {
    private final String animationName;
    private final LoopType loopType;
    private final int priority;
    private final BiPredicate<TE, AnimationEvent<AE>> predicate;

    public AnimationState(String animationName, LoopType loopType, int priority, BiPredicate<TE, AnimationEvent<AE>> predicate) {
        this.animationName = animationName;
        this.loopType = loopType;
        this.priority = MathHelper.clamp(priority, Priority.HIGHEST, Priority.LOWEST);
        this.predicate = predicate;
    }

    public BiPredicate<TE, AnimationEvent<AE>> getPredicate() {
        return predicate;
    }

    public String getAnimationName() {
        return animationName;
    }

    public LoopType getLoopType() {
        return loopType;
    }

    public int getPriority() {
        return priority;
    }
}
