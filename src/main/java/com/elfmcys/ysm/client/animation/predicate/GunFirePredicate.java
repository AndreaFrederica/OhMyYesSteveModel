package com.elfmcys.ysm.client.animation.predicate;

/*
import com.elfmcys.ysm.client.compat.swarfare.SWarfareCompat;
import com.elfmcys.ysm.client.compat.tacz.TACZCompat;
import com.elfmcys.ysm.client.entity.CustomHumanoidEntity;
import com.elfmcys.ysm.client.entity.IPreviewEntity;
import com.elfmcys.ysm.geckolib3.core.PlayState;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.molang.runtime.ExpressionEvaluator;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;

import java.util.Objects;

public class GunFirePredicate implements IAnimationPredicate<CustomHumanoidEntity<?>> {
    @Override
    public PlayState test(AnimationEvent<CustomHumanoidEntity<?>> event, ExpressionEvaluator<?> evaluator) {
        EntityLivingBase entity = event.getAnimatableEntity().getEntity();
        if (entity == null || event.getAnimatableEntity() instanceof IPreviewEntity) {
            return PlayState.STOP;
        }
        if (!entity.isSwingInProgress && !entity.isHandActive()) {
            ItemStack mainHandItem = entity.getHeldItem(EnumHand.MAIN_HAND);
            PlayState result = TACZCompat.playGunOnceAnimation(mainHandItem, event);
            if (result == null) {
                result = SWarfareCompat.playGunOnceAnimation(mainHandItem, event);
            }
            return Objects.requireNonNullElse(result, PlayState.STOP);
        }
        return PlayState.STOP;
    }

    public static boolean available() {
        return TACZCompat.isInstalled() || SWarfareCompat.isInstalled();
    }
}
*/
