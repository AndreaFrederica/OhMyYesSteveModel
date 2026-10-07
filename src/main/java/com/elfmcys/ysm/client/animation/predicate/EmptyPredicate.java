package com.elfmcys.ysm.client.animation.predicate;

import com.elfmcys.ysm.geckolib3.core.PlayState;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.geckolib3.model.AnimatableEntity;
import com.elfmcys.ysm.molang.runtime.ExpressionEvaluator;
import net.minecraft.entity.EntityLivingBase;

public class EmptyPredicate implements IAnimationPredicate<AnimatableEntity<? extends EntityLivingBase>> {
    public static final EmptyPredicate INSTANCE = new EmptyPredicate();

    public static <T extends AnimatableEntity<?>> IAnimationPredicate<T> instance() {
        return (event, evaluator) -> PlayState.STOP;
    }

    @Override
    public PlayState test(AnimationEvent<AnimatableEntity<? extends EntityLivingBase>> event, ExpressionEvaluator<?> evaluator) {
        return PlayState.STOP;
    }
}
