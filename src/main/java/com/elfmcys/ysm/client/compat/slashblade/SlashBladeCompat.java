package com.elfmcys.ysm.client.compat.slashblade;

import com.elfmcys.ysm.client.animation.molang.CtrlBinding;
import com.elfmcys.ysm.client.entity.CustomHumanoidEntity;
import com.elfmcys.ysm.geckolib3.core.PlayState;
import com.elfmcys.ysm.geckolib3.core.builder.LoopType;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.common.Loader;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;

import static com.elfmcys.ysm.client.compat.slashblade.SlashBladeAnimation.isSlashBlade;

public class SlashBladeCompat {
    private static final String SLASH_BLADE_ID = "flammpfeil.slashblade";
    private static boolean IS_LOADED = false;

    public static void init() {
        IS_LOADED = Loader.isModLoaded(SLASH_BLADE_ID);
    }

    public static boolean isSlashBladeLoaded() {
        return IS_LOADED;
    }

    public static boolean isSlashBladeItem(ItemStack stack) {
        return isSlashBladeLoaded() && isSlashBlade(stack);
    }

    public static String getAnimationName(AnimationEvent<? extends CustomHumanoidEntity<?>> event) {
        if (isSlashBladeLoaded()) {
            return SlashBladeAnimation.getAnimationName(event);
        }
        return StringUtils.EMPTY;
    }

    @Nullable
    public static PlayState playMainAnimation(EntityLivingBase livingEntity, AnimationEvent<? extends CustomHumanoidEntity<?>> event, String animationName, LoopType loopType) {
        if (isSlashBladeLoaded() && isSlashBladeItem(livingEntity.getHeldItemMainhand())) {
            return SlashBladeAnimation.playMainAnimation(event, animationName, loopType);
        }
        return null;
    }

    public static void addBinding(CtrlBinding binding) {
        if (isSlashBladeLoaded()) {
            SlashBladeBinding.addInnerBinding(binding);
        } else {
            addEmptyBinding(binding);
        }
    }

    /**
     * 没有安装此模组时，这些 molang 应该存在，否则会报错
     */
    private static void addEmptyBinding(CtrlBinding binding) {
        binding.livingEntityVar("slashblade_animation", ctx -> StringUtils.EMPTY);
    }
}
