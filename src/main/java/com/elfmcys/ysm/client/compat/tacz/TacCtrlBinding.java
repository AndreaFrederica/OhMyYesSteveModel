package com.elfmcys.ysm.client.compat.tacz;

/*
import com.elfmcys.ysm.client.animation.molang.CtrlBinding;
import com.elfmcys.ysm.geckolib3.core.molang.context.IContext;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.gun.FireMode;
import com.tacz.guns.resource.index.CommonGunIndex;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.apache.commons.lang3.StringUtils;

import java.util.Optional;

public class TacCtrlBinding {
    static void addInnerBinding(CtrlBinding binding) {
        binding.livingEntityVar("tac_hold_gun", ctx -> IGun.mainhandHoldGun(ctx.entity()));
        binding.livingEntityVar("tac_gun_type", TacCtrlBinding::getGunType);
        binding.livingEntityVar("tac_gun_id", TacCtrlBinding::getGunId);
        binding.livingEntityVar("tac_is_fire", ctx -> IGunOperator.fromLivingEntity(ctx.entity()).getSynShootCoolDown() > 0);
        binding.livingEntityVar("tac_is_aim", ctx -> IGunOperator.fromLivingEntity(ctx.entity()).getSynAimingProgress() > 0);
        binding.livingEntityVar("tac_is_reload", ctx -> IGunOperator.fromLivingEntity(ctx.entity()).getSynReloadState().getCountDown() > 0);
        binding.livingEntityVar("tac_is_melee", ctx -> IGunOperator.fromLivingEntity(ctx.entity()).getSynMeleeCoolDown() > 0);
        binding.livingEntityVar("tac_is_draw", ctx -> IGunOperator.fromLivingEntity(ctx.entity()).getSynDrawCoolDown() > 0);

        // 新版新增
        binding.livingEntityVar("tac_fire_mode", ctx -> {
            FireMode fireMode = IGun.getMainHandFireMode(ctx.entity());
            return fireMode != FireMode.UNKNOWN ? fireMode.name() : StringUtils.EMPTY;
        });
    }

    private static String getGunType(IContext<LivingEntity> context) {
        ItemStack mainHandItem = context.entity().getMainHandItem();
        IGun gun = IGun.getIGunOrNull(mainHandItem);
        if (gun == null) {
            return StringUtils.EMPTY;
        }
        Optional<CommonGunIndex> indexOptional = TimelessAPI.getCommonGunIndex(gun.getGunId(mainHandItem));
        if (indexOptional.isEmpty()) {
            return StringUtils.EMPTY;
        }
        return indexOptional.get().getType();
    }

    private static String getGunId(IContext<LivingEntity> context) {
        ItemStack mainHandItem = context.entity().getMainHandItem();
        IGun gun = IGun.getIGunOrNull(mainHandItem);
        if (gun == null) {
            return StringUtils.EMPTY;
        }
        return gun.getGunId(mainHandItem).toString();
    }
}
*/
