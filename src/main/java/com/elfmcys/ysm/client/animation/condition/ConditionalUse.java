package com.elfmcys.ysm.client.animation.condition;

import net.minecraft.util.EnumHand;

public class ConditionalUse extends AbstractConditionItem {
    public ConditionalUse(EnumHand hand) {
        super("use_" + (hand == EnumHand.MAIN_HAND ? "mainhand" : "offhand"));
    }
}
