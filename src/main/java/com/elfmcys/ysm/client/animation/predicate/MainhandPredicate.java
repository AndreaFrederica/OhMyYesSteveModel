package com.elfmcys.ysm.client.animation.predicate;

import com.elfmcys.ysm.client.animation.condition.ConditionalHold;
import com.elfmcys.ysm.client.compat.backport.BackportCompat;
import com.elfmcys.ysm.client.compat.touhoulittlemaid.client.TlmClientCompat;
import com.elfmcys.ysm.client.entity.CustomHumanoidEntity;
import com.elfmcys.ysm.client.entity.HumanoidStateTracker;
import com.elfmcys.ysm.client.entity.IPreviewEntity;
import com.elfmcys.ysm.geckolib3.core.PlayState;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.molang.runtime.ExpressionEvaluator;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;
import org.apache.commons.lang3.StringUtils;

import static com.elfmcys.ysm.client.animation.predicate.IAnimationPredicate.playAnimation;

public class MainhandPredicate implements IAnimationPredicate<CustomHumanoidEntity<?>> {
    @Override
    public PlayState test(AnimationEvent<CustomHumanoidEntity<?>> event, ExpressionEvaluator<?> evaluator) {
        EntityLivingBase entity = event.getAnimatableEntity().getEntity();
        if (entity == null || event.getAnimatableEntity() instanceof IPreviewEntity) {
            return PlayState.STOP;
        }

        if (!checkSwingAndUse(entity, EnumHand.MAIN_HAND)) {
            return PlayState.PAUSE;
        }

        ItemStack mainHandItem = entity.getHeldItem(EnumHand.MAIN_HAND);
        /*
        PlayState gunHoldAnimation = TACZCompat.playGunHoldAnimation(mainHandItem, event);
        if (gunHoldAnimation != null) {
            return gunHoldAnimation;
        }
        gunHoldAnimation = SWarfareCompat.playGunHoldAnimation(mainHandItem, event);
        if (gunHoldAnimation != null) {
            return gunHoldAnimation;
        }
        */
        if (BackportCompat.isCrossbowCharged(mainHandItem)) {
            return playAnimation(event, "hold_mainhand:charged_crossbow");
        }
        boolean playerIsFishing = entity instanceof EntityPlayer player && player.fishEntity != null;
        boolean maidIsFishing = TlmClientCompat.isMaidFishing(entity);
        if (playerIsFishing || maidIsFishing) {
            return playAnimation(event, "hold_mainhand:fishing");
        }

        var tracker = event.getAnimatableEntity().getStateTracker();
        if (!isSameItem(mainHandItem, tracker, EnumHand.MAIN_HAND)) {
            tracker.setHandItem(mainHandItem, EnumHand.MAIN_HAND);
            event.getCodedController().indicateReload();
        }

        ConditionalHold conditionalHold = event.getAnimatableEntity().getConditionManager().getHoldMainhand();
        if (conditionalHold != null) {
            String name = conditionalHold.doTest(entity, EnumHand.MAIN_HAND);
            if (StringUtils.isNoneBlank(name)) {
                return playAnimation(event, name);
            }
        }

        return PlayState.STOP;
    }

    private boolean isSameItem(ItemStack playerItem, HumanoidStateTracker<?> tracker, EnumHand hand) {
        ItemStack preItem = tracker.getHandItem(hand);
        if (preItem.isItemDamaged()) {
            return ItemStack.areItemsEqualIgnoreDurability(playerItem, preItem);
        }
        return ItemStack.areItemStacksEqual(playerItem, preItem);
    }

    private boolean checkSwingAndUse(EntityLivingBase entity, EnumHand hand) {
        if (entity.isSwingInProgress && entity.swingingHand == hand) {
            return false;
        }
        return !entity.isHandActive() || entity.getActiveHand() != hand;
    }
}
