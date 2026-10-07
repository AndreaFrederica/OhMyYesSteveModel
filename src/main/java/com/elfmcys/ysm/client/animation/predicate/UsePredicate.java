package com.elfmcys.ysm.client.animation.predicate;

import com.elfmcys.ysm.client.animation.EntityTickStates;
import com.elfmcys.ysm.client.animation.condition.ConditionalUse;
import com.elfmcys.ysm.client.entity.CustomHumanoidEntity;
import com.elfmcys.ysm.client.entity.IPreviewEntity;
import com.elfmcys.ysm.geckolib3.core.PlayState;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.molang.runtime.ExpressionEvaluator;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.EnumHand;
import org.apache.commons.lang3.StringUtils;

import static com.elfmcys.ysm.client.animation.predicate.IAnimationPredicate.playAnimation;

public class UsePredicate implements IAnimationPredicate<CustomHumanoidEntity<?>> {
    @Override
    public PlayState test(AnimationEvent<CustomHumanoidEntity<?>> event, ExpressionEvaluator<?> evaluator) {
        EntityLivingBase entity = event.getAnimatableEntity().getEntity();
        if (entity == null || event.getAnimatableEntity() instanceof IPreviewEntity) {
            return PlayState.STOP;
        }

        if (entity.isHandActive() && !entity.isPlayerSleeping()) {
            if (entity.getItemInUseMaxCount() == 1 && event.getAnimatableEntity().getStateTracker().setEntityTickState(EntityTickStates.USING_ITEM)) {
                event.getCodedController().indicateReload();
            }
            var conditionManager = event.getAnimatableEntity().getConditionManager();
            if (entity.getActiveHand() == EnumHand.MAIN_HAND) {
                ConditionalUse conditionalUse = conditionManager.getUseMainhand();
                if (conditionalUse != null) {
                    String name = conditionalUse.doTest(entity, EnumHand.MAIN_HAND);
                    if (StringUtils.isNoneBlank(name)) {
                        return playAnimation(event, name);
                    }
                }
                return playAnimation(event, "use_mainhand");
            } else {
                ConditionalUse conditionalUse = conditionManager.getUseOffhand();
                if (conditionalUse != null) {
                    String name = conditionalUse.doTest(entity, EnumHand.OFF_HAND);
                    if (StringUtils.isNoneBlank(name)) {
                        return playAnimation(event, name);
                    }
                }
                return playAnimation(event, "use_offhand");
            }
        }
        return PlayState.STOP;
    }
}
