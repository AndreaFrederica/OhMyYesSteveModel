package com.elfmcys.ysm.client.compat.touhoulittlemaid.client.animation.predicate;

/*
import com.elfmcys.ysm.client.animation.AnimationState;
import com.elfmcys.ysm.client.animation.Priority;
import com.elfmcys.ysm.client.animation.predicate.IAnimationPredicate;
import com.elfmcys.ysm.client.compat.slashblade.SlashBladeCompat;
import com.elfmcys.ysm.client.compat.tacz.TACZCompat;
import com.elfmcys.ysm.client.compat.touhoulittlemaid.client.CustomYsmMaidEntity;
import com.elfmcys.ysm.client.entity.IPreviewEntity;
import com.elfmcys.ysm.geckolib3.core.PlayState;
import com.elfmcys.ysm.geckolib3.core.builder.LoopType;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.molang.runtime.ExpressionEvaluator;
import com.github.tartaricacid.touhoulittlemaid.api.client.render.MaidRenderState;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import net.minecraft.world.entity.Entity;

import java.util.Objects;

import static com.elfmcys.ysm.client.animation.predicate.IAnimationPredicate.playAnimation;

public class YsmMaidMainPredicate implements IAnimationPredicate<CustomYsmMaidEntity> {
    @SuppressWarnings("unchecked")
    private static final ReferenceArrayList<AnimationState<EntityMaid, CustomYsmMaidEntity>>[] DATA = new ReferenceArrayList[Priority.LOWEST + 1];

    static {
        for (int i = 0; i < DATA.length; i++) {
            DATA[i] = new ReferenceArrayList<>(6);
        }
    }

    public static void register(AnimationState<EntityMaid, CustomYsmMaidEntity> state) {
        DATA[state.getPriority()].add(state);
    }

    @Override
    public PlayState test(AnimationEvent<CustomYsmMaidEntity> event, ExpressionEvaluator<?> evaluator) {
        EntityMaid maid = event.getAnimatableEntity().getEntity();
        if (maid == null || event.getAnimatableEntity() instanceof IPreviewEntity) {
            return PlayState.STOP;
        }

        // 雕像或者手办状态不播放主动画
        if (maid.renderState != MaidRenderState.ENTITY) {
            return PlayState.STOP;
        }

        // 跑酷模组只作用于玩家，故禁用
        // 载具动画单独检查
        Entity vehicle = maid.getVehicle();
        if (vehicle != null && vehicle.isAlive()) {
            return PlayState.STOP;
        }

        for (int i = Priority.HIGHEST; i <= Priority.LOWEST; i++) {
            for (AnimationState<EntityMaid, CustomYsmMaidEntity> state : DATA[i]) {
                if (state.getPredicate().test(maid, event)) {
                    String animationName = state.getAnimationName();
                    LoopType loopType = state.getLoopType();

                    // 先判断拔刀剑动画
                    // FIXME: 女仆不能打出剑技吧？
                    PlayState slashBladeAnimation = SlashBladeCompat.playMainAnimation(maid, event, animationName, loopType);
                    if (slashBladeAnimation != null) {
                        return slashBladeAnimation;
                    }

                    // 再判断 tacz 动画
                    PlayState gunMainAnimation = TACZCompat.playGunMainAnimation(maid, event, animationName, loopType);
                    return Objects.requireNonNullElseGet(gunMainAnimation, () -> playAnimation(event, animationName, loopType));
                }
            }
        }
        return PlayState.STOP;
    }
}
*/
