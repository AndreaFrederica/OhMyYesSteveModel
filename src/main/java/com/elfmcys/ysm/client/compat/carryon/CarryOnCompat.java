package com.elfmcys.ysm.client.compat.carryon;

import com.elfmcys.ysm.client.animation.molang.CtrlBinding;
import com.elfmcys.ysm.client.entity.CustomPlayerEntity;
import com.elfmcys.ysm.geckolib3.core.controller.HybridAnimationController;
import com.elfmcys.ysm.geckolib3.core.controller.IAnimationController;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.fml.common.Loader;
import org.apache.commons.lang3.StringUtils;

import java.util.Optional;
import java.util.function.BiFunction;

// TODO：重新移植 Carry On 以允许搬运玩家
public class CarryOnCompat {
    private static final String CARRY_ON_ID = "carryon";
    private static boolean INSTALLED = false;

    public static void init() {
        INSTALLED = Loader.isModLoaded(CARRY_ON_ID);
    }

    public static boolean isInstalled() {
        return INSTALLED;
    }

    public static Optional<BiFunction<String, CustomPlayerEntity, IAnimationController<CustomPlayerEntity>>> animationPredicate() {
        if (INSTALLED) {
            return Optional.of((name, entity) -> new HybridAnimationController<>(entity, name, 0.1f, new CarryOnPredicate()));
        } else {
            return Optional.empty();
        }
    }

    public static boolean isCarryOnPrincess(EntityPlayer player) {
        if (INSTALLED) {
            return CarryOnInnerCompat.isCarryOnPrincess(player);
        }
        return false;
    }

    public static void addBinding(CtrlBinding binding) {
        if (isInstalled()) {
            CarryOnCtrlBinding.addInnerBinding(binding);
        } else {
            addEmptyBinding(binding);
        }
    }

    /**
     * 没有安装此模组时，这些 molang 应该存在，否则会报错
     */
    private static void addEmptyBinding(CtrlBinding binding) {
        binding.livingEntityVar("carryon_type", ctx -> StringUtils.EMPTY);
        binding.livingEntityVar("carryon_is_princess", ctx -> false);
    }
}
