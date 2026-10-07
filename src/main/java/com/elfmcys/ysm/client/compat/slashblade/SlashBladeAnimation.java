package com.elfmcys.ysm.client.compat.slashblade;

import com.elfmcys.ysm.geckolib3.core.PlayState;
import com.elfmcys.ysm.geckolib3.core.builder.LoopType;
import com.elfmcys.ysm.geckolib3.core.event.predicate.AnimationEvent;
import com.elfmcys.ysm.geckolib3.core.molang.context.IContext;
import com.elfmcys.ysm.geckolib3.model.AnimatableEntity;
import mods.flammpfeil.slashblade.item.ItemSlashBlade;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;

import java.util.EnumMap;
import java.util.Map;

public class SlashBladeAnimation {
    /**
     * ComboSequence -> 目标动画 key（slashblade.animation.json 内带 slashblade: 前缀的 key）。
     * 同名招式直接对位；1.20 无同名的按触发上下文与挥刀方向取语义近似，低置信度的后续实机微调。
     */
    private static final Map<ItemSlashBlade.ComboSequence, String> COMBO_ANIMATION = new EnumMap<>(ItemSlashBlade.ComboSequence.class);

    static {
        COMBO_ANIMATION.put(ItemSlashBlade.ComboSequence.Saya1, "slashblade:combo_a1");
        COMBO_ANIMATION.put(ItemSlashBlade.ComboSequence.Saya2, "slashblade:combo_a2");
        COMBO_ANIMATION.put(ItemSlashBlade.ComboSequence.Battou, "slashblade:combo_a4ex");
        COMBO_ANIMATION.put(ItemSlashBlade.ComboSequence.Kiriage, "slashblade:upperslash");
        COMBO_ANIMATION.put(ItemSlashBlade.ComboSequence.Kiriorosi, "slashblade:aerial_rave_b4");
        COMBO_ANIMATION.put(ItemSlashBlade.ComboSequence.SlashDim, "slashblade:judgement_cut");
        COMBO_ANIMATION.put(ItemSlashBlade.ComboSequence.Iai, "slashblade:combo_a4ex");
        COMBO_ANIMATION.put(ItemSlashBlade.ComboSequence.SlashEdge, "slashblade:combo_b1");
        COMBO_ANIMATION.put(ItemSlashBlade.ComboSequence.ReturnEdge, "slashblade:combo_b2");
        COMBO_ANIMATION.put(ItemSlashBlade.ComboSequence.SIai, "slashblade:combo_a4ex");
        COMBO_ANIMATION.put(ItemSlashBlade.ComboSequence.SSlashEdge, "slashblade:combo_b3");
        COMBO_ANIMATION.put(ItemSlashBlade.ComboSequence.SReturnEdge, "slashblade:combo_b4");
        COMBO_ANIMATION.put(ItemSlashBlade.ComboSequence.SSlashBlade, "slashblade:combo_b7");
        COMBO_ANIMATION.put(ItemSlashBlade.ComboSequence.AerialRave, "slashblade:aerial_rave_a1");
        COMBO_ANIMATION.put(ItemSlashBlade.ComboSequence.ASlashEdge, "slashblade:aerial_rave_a1");
        COMBO_ANIMATION.put(ItemSlashBlade.ComboSequence.AKiriorosi, "slashblade:aerial_rave_a2");
        COMBO_ANIMATION.put(ItemSlashBlade.ComboSequence.AKiriorosiB, "slashblade:aerial_rave_a2");
        COMBO_ANIMATION.put(ItemSlashBlade.ComboSequence.AKiriage, "slashblade:aerial_rave_a3");
        COMBO_ANIMATION.put(ItemSlashBlade.ComboSequence.AKiriorosiFinish, "slashblade:aerial_rave_b4");
        COMBO_ANIMATION.put(ItemSlashBlade.ComboSequence.RapidSlash, "slashblade:rapid_slash");
        COMBO_ANIMATION.put(ItemSlashBlade.ComboSequence.RapidSlashEnd, "slashblade:rapid_slash");
        COMBO_ANIMATION.put(ItemSlashBlade.ComboSequence.RisingStar, "slashblade:rising_star");
        COMBO_ANIMATION.put(ItemSlashBlade.ComboSequence.HelmBraker, "slashblade:aerial_cleave_landing");
        COMBO_ANIMATION.put(ItemSlashBlade.ComboSequence.Calibur, "slashblade:rapid_slash");
        COMBO_ANIMATION.put(ItemSlashBlade.ComboSequence.Force1, "slashblade:combo_b5");
        COMBO_ANIMATION.put(ItemSlashBlade.ComboSequence.Force2, "slashblade:combo_b6");
        COMBO_ANIMATION.put(ItemSlashBlade.ComboSequence.Force3, "slashblade:combo_a1");
        COMBO_ANIMATION.put(ItemSlashBlade.ComboSequence.Force4, "slashblade:combo_a2");
        COMBO_ANIMATION.put(ItemSlashBlade.ComboSequence.Force5, "slashblade:combo_b7");
        COMBO_ANIMATION.put(ItemSlashBlade.ComboSequence.Force6, "slashblade:combo_b7");
        // None / Noutou / HiraTuki / Stinger 无对应动画，超时即回空
    }

    static boolean isSlashBlade(ItemStack stack) {
        return stack.getItem() instanceof ItemSlashBlade;
    }

    static String getAnimationName(AnimationEvent<? extends AnimatableEntity<? extends EntityLivingBase>> event) {
        EntityLivingBase livingEntity = event.getAnimatableEntity().getEntity();
        return getCombName(livingEntity.getHeldItemMainhand(), livingEntity);
    }

    static String getAnimationName(IContext<? extends EntityLivingBase> context) {
        EntityLivingBase livingEntity = context.entity();
        return getCombName(livingEntity.getHeldItemMainhand(), livingEntity);
    }

    /**
     * slashblade:idle
     * slashblade:run
     * slashblade:walk
     */
    static PlayState playMainAnimation(AnimationEvent<? extends AnimatableEntity<? extends EntityLivingBase>> event, String animationName, LoopType loopType) {
        String name = "slashblade:" + animationName;
        if (event.getAnimatableEntity().getAnimation(name) != null) {
            return playAnimation(event, name, loopType);
        }
        return playAnimation(event, animationName, loopType);
    }

    @NotNull
    private static String getCombName(ItemStack mainHandItem, EntityLivingBase entity) {
        if (!SlashBladeCompat.isSlashBladeItem(mainHandItem)) {
            return StringUtils.EMPTY;
        }
        NBTTagCompound tag = mainHandItem.getTagCompound();
        if (tag == null) {
            return StringUtils.EMPTY;
        }
        ItemSlashBlade.ComboSequence combo = ItemSlashBlade.getComboSequence(tag);
        // comboResetTicks 单位是 tick；time 换算为毫秒与上游 (gameTime - lastActionTime) * 50 对齐
        long time = (entity.world.getTotalWorldTime() - ItemSlashBlade.LastActionTime.get(tag)) * 50;
        long timeout = (long) combo.comboResetTicks * 50;
        if (time > timeout) {
            return StringUtils.EMPTY;
        }
        String name = COMBO_ANIMATION.get(combo);
        if (name == null) {
            return StringUtils.EMPTY;
        }
        // 原拔刀剑没有区分空中次元斩，这里加上
        if (combo == ItemSlashBlade.ComboSequence.SlashDim && !entity.onGround) {
            return "slashblade:judgement_cut_slash_air";
        }
        return name;
    }

    @NotNull
    private static PlayState playAnimation(AnimationEvent<?> event, String animationName, LoopType loopType) {
        event.getCodedController().setAnimation(animationName, loopType);
        return PlayState.CONTINUE;
    }
}
