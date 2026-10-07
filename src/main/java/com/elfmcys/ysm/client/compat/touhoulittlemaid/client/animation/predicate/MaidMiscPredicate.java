package com.elfmcys.ysm.client.compat.touhoulittlemaid.client.animation.predicate;

/*
import com.elfmcys.ysm.client.animation.predicate.IAnimationPredicate;
import com.elfmcys.ysm.client.compat.touhoulittlemaid.client.CustomYsmMaidEntity;
import com.elfmcys.ysm.client.entity.IPreviewEntity;
import com.elfmcys.ysm.geckolib3.core.PlayState;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.molang.runtime.ExpressionEvaluator;
import com.github.tartaricacid.touhoulittlemaid.entity.item.EntitySit;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.MaidGameRecordManager;

import static com.elfmcys.ysm.client.animation.predicate.IAnimationPredicate.playLoopAnimation;

public class MaidMiscPredicate implements IAnimationPredicate<CustomYsmMaidEntity> {
    public static final String[] ANIM_LIST = new String[]{"game_win", "game_lost", "beg"};

    @Override
    public PlayState test(AnimationEvent<CustomYsmMaidEntity> event, ExpressionEvaluator<?> evaluator) {
        EntityMaid maid = event.getAnimatableEntity().getEntity();
        if (maid == null || event.getAnimatableEntity() instanceof IPreviewEntity) {
            return PlayState.STOP;
        }
        // 赢棋输棋优先
        if (maid.getVehicle() instanceof EntitySit) {
            MaidGameRecordManager manager = maid.getGameRecordManager();
            if (manager.isWin()) {
                return playLoopAnimation(event, "game_win");
            }
            if (manager.isLost()) {
                return playLoopAnimation(event, "game_lost");
            }
        }
        // 祈求动画
        if (maid.isBegging()) {
            return playLoopAnimation(event, "beg");
        }
        return PlayState.STOP;
    }
}
*/
