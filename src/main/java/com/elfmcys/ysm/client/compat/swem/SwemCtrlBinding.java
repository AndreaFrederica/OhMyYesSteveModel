package com.elfmcys.ysm.client.compat.swem;

/*
import com.alaharranhonor.swem.forge.entities.horse.SWEMHorseEntityBase;
import com.elfmcys.ysm.client.animation.molang.CtrlBinding;
import com.elfmcys.ysm.geckolib3.core.molang.context.IContext;
import com.google.common.collect.Maps;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Locale;

public class SwemCtrlBinding {
    private static final EnumMap<SWEMHorseEntityBase.Gait, String> CACHE = Maps.newEnumMap(SWEMHorseEntityBase.Gait.class);

    static void addInnerBinding(CtrlBinding binding) {
        binding.livingEntityVar("swem_is_ride", ctx -> ctx.entity().getVehicle() instanceof SWEMHorseEntityBase);
        binding.livingEntityVar("swem_state", SwemCtrlBinding::getState);
    }

    @Nullable
    @SuppressWarnings("all")
    static String getState(IContext<LivingEntity> context) {
        if (context.entity().getVehicle() instanceof SWEMHorseEntityBase horse) {
            SWEMHorseEntityBase.Gait gait = horse.getGait();
            // 判断跳跃
            double jumpHeight = horse.jumpHeight;
            if (jumpHeight > 0) {
                int jumpAnimIndex = Math.min(Mth.ceil(jumpHeight), 5) - 1;
                return "jump_lv" + (jumpAnimIndex + 1);
            }
            if (SwemCompatInner.notMoving(horse)) {
                return "idle";
            }
            return CACHE.computeIfAbsent(gait, g -> g.name().toLowerCase(Locale.ENGLISH));
        }
        return StringUtils.EMPTY;
    }
}
*/
