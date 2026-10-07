package com.elfmcys.ysm.client.compat.swem;

import com.elfmcys.ysm.client.animation.molang.CtrlBinding;
/*
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.fml.ModList;
*/
import net.minecraft.entity.EntityLivingBase;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;

public class SwemCompat {
    /*
    private static final String MOD_ID = "swem";
    private static boolean INSTALLED;

    public static void init() {
        INSTALLED = ModList.get().isLoaded(MOD_ID);
    }

    public static boolean isInstalled() {
        return INSTALLED;
    }
*/

    @Nullable
    public static String getAnimation(EntityLivingBase livingEntity) {
        /*
    public static String getAnimation(LivingEntity livingEntity) {
        if (isInstalled()) {
            return SwemCompatInner.getAnimation(livingEntity);
        }
        */
        return null;
    }

    public static void addBinding(CtrlBinding binding) {
        /*
        if (isInstalled()) {
            SwemCtrlBinding.addInnerBinding(binding);
        } else {
            addEmptyBinding(binding);
        }
        */
        addEmptyBinding(binding);
    }

    /**
     * 没有安装此模组时，这些 molang 应该存在，否则会报错
     */
    private static void addEmptyBinding(CtrlBinding binding) {
        binding.livingEntityVar("swem_is_ride", ctx -> false);
        binding.livingEntityVar("swem_state", ctx -> StringUtils.EMPTY);
    }
}
