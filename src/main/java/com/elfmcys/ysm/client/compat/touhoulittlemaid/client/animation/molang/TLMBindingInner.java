package com.elfmcys.ysm.client.compat.touhoulittlemaid.client.animation.molang;

/*
import com.elfmcys.ysm.client.animation.molang.TLMBinding;
import com.elfmcys.ysm.geckolib3.core.molang.context.IContext;
import com.elfmcys.ysm.geckolib3.core.molang.variable.IValueEvaluator;
import com.github.tartaricacid.touhoulittlemaid.api.client.render.MaidRenderState;
import com.github.tartaricacid.touhoulittlemaid.entity.ai.brain.MaidGomokuAI;
import com.github.tartaricacid.touhoulittlemaid.entity.item.EntitySit;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.MaidGameRecordManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.function.Function;

public class TLMBindingInner {
    public static void addInnerBinding(TLMBinding binding) {
        binding.livingEntityVar("is_begging", checkMaid(EntityMaid::isBegging));
        binding.livingEntityVar("is_sitting", checkMaid(EntityMaid::isMaidInSittingPose));
        binding.livingEntityVar("has_backpack", checkMaid(EntityMaid::hasBackpack));
        binding.livingEntityVar("favorability_point", checkMaid(EntityMaid::getFavorability));
        binding.livingEntityVar("favorability_level", checkMaid(maid -> maid.getFavorabilityManager().getLevel()));
        binding.livingEntityVar("task_id", checkMaid(maid -> maid.getTask().getUid()));
        binding.livingEntityVar("schedule", checkMaid(maid -> maid.getSchedule().name().toLowerCase(Locale.ENGLISH)));
        binding.livingEntityVar("activity", checkMaid(maid -> maid.getScheduleDetail().getName()));
        binding.livingEntityVar("gomoku_win_count", checkMaid(maid -> maid.getGameRecordManager().getGomokuWinCount()));
        binding.livingEntityVar("gomoku_rank", checkMaid(MaidGomokuAI::getRank));
        binding.livingEntityVar("game_statue", checkMaid(TLMBindingInner::getGameStatue));
        binding.livingEntityVar("backpack_type", checkMaid(maid -> maid.getMaidBackpackType().getId().toString()));
        binding.livingEntityVar("is_entity", checkMaid(maid -> maid.renderState == MaidRenderState.ENTITY));
        binding.livingEntityVar("is_statue", checkMaid(maid -> maid.renderState == MaidRenderState.STATUE));
        binding.livingEntityVar("is_garage_kit", checkMaid(maid -> maid.renderState == MaidRenderState.GARAGE_KIT));
        binding.livingEntityVar("show_item", checkMaid(TLMBindingInner::getShowItem));
    }

    @NotNull
    private static IValueEvaluator<Object, IContext<LivingEntity>> checkMaid(Function<EntityMaid, Object> predicate) {
        return ctx -> {
            LivingEntity entity = ctx.entity();
            if (entity instanceof EntityMaid maid) {
                return predicate.apply(maid);
            }
            return 0;
        };
    }

    private static String getGameStatue(EntityMaid maid) {
        if (maid.getVehicle() instanceof EntitySit) {
            MaidGameRecordManager manager = maid.getGameRecordManager();
            if (manager.isWin()) {
                return "win";
            }
            if (manager.isLost()) {
                return "lost";
            }
        }
        return StringUtils.EMPTY;
    }

    private static String getShowItem(EntityMaid maid) {
        ItemStack item = maid.getBackpackShowItem();
        if (item.isEmpty()) {
            return StringUtils.EMPTY;
        }
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(item.getItem());
        if (key == null) {
            return StringUtils.EMPTY;
        }
        return key.toString();
    }
}
*/
