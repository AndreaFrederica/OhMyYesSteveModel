package com.elfmcys.ysm.client.animation.condition;

import net.minecraft.util.EnumHand;

public class ConditionalSwing extends AbstractConditionItem {
    public ConditionalSwing(EnumHand hand) {
        super(hand == EnumHand.MAIN_HAND ? "swing" : "swing_offhand");
    }
}
