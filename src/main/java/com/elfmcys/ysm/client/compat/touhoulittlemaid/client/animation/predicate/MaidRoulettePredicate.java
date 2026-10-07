package com.elfmcys.ysm.client.compat.touhoulittlemaid.client.animation.predicate;

/*
import com.elfmcys.ysm.client.animation.predicate.IAnimationPredicate;
import com.elfmcys.ysm.client.compat.touhoulittlemaid.client.CustomYsmMaidEntity;
import com.elfmcys.ysm.client.entity.IPreviewEntity;
import com.elfmcys.ysm.geckolib3.core.PlayState;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.molang.runtime.ExpressionEvaluator;

import static com.elfmcys.ysm.client.animation.predicate.IAnimationPredicate.playAnimation;

public class MaidRoulettePredicate implements IAnimationPredicate<CustomYsmMaidEntity> {
    @Override
    public PlayState test(AnimationEvent<CustomYsmMaidEntity> event, ExpressionEvaluator<?> evaluator) {
        CustomYsmMaidEntity animatable = event.getAnimatableEntity();
        if (animatable instanceof IPreviewEntity guiEntity) {
            if (guiEntity.getPreviewInfo().hasPreview()) {
                return IAnimationPredicate.playLoopAnimation(event, guiEntity.getPreviewInfo().getPreview());
            }
            return PlayState.STOP;
        }

        if (animatable.isRouletteAnimPlaying()) {
            if (animatable.shouldResetRouletteAnim()) {
                animatable.clearRouletteAnimDirty();
                event.getCodedController().indicateReload();
            }
            return playAnimation(event, animatable.getRouletteAnim());
        }
        return PlayState.STOP;
    }
}
*/
