package com.elfmcys.ysm.client.compat.carryon;

import com.elfmcys.ysm.client.animation.molang.CtrlBinding;
import com.elfmcys.ysm.geckolib3.core.molang.context.IContext;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import org.apache.commons.lang3.StringUtils;

import java.util.Locale;

public class CarryOnCtrlBinding {
    static void addInnerBinding(CtrlBinding binding) {
        binding.livingEntityVar("carryon_type", CarryOnCtrlBinding::getType);
        binding.livingEntityVar("carryon_is_princess", ctx -> CarryOnInnerCompat.isCarryOnPrincess(ctx.entity()));
    }

    private static String getType(IContext<EntityLivingBase> context) {
        if (context.entity() instanceof EntityPlayer player) {
            CarryOnInnerCompat.Type type = CarryOnInnerCompat.getCarryOnType(player);
            if (type == CarryOnInnerCompat.Type.NONE) {
                return StringUtils.EMPTY;
            }
            return type.name().toLowerCase(Locale.ENGLISH);
        }
        return StringUtils.EMPTY;
    }
}
