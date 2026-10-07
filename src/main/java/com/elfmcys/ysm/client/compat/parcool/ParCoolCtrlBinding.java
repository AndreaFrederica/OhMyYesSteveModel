package com.elfmcys.ysm.client.compat.parcool;

/*
import com.elfmcys.ysm.client.animation.molang.CtrlBinding;
import com.elfmcys.ysm.geckolib3.core.molang.context.IContext;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.apache.commons.lang3.StringUtils;

public class ParCoolCtrlBinding {
    static void addInnerBinding(CtrlBinding binding) {
        binding.livingEntityVar("parcool_state", ParCoolCtrlBinding::getState);
    }

    private static String getState(IContext<LivingEntity> context) {
        if (context.entity() instanceof Player player) {
            String animation = ParCoolAnimationManger.getAnimation(player);
            if (animation != null) {
                return animation.substring("parcool:".length());
            }
        }
        return StringUtils.EMPTY;
    }
}
*/
