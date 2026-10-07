package com.elfmcys.ysm.client.animation.molang.variable;

import com.elfmcys.ysm.geckolib3.core.molang.context.IContext;
import com.elfmcys.ysm.geckolib3.core.molang.variable.IValueEvaluator;
import net.minecraft.block.BlockHorizontal;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.math.BlockPos;

public class LadderFacingVariable implements IValueEvaluator<Integer, IContext<EntityLivingBase>> {
    @Override
    public Integer eval(IContext<EntityLivingBase> ctx) {
        EntityLivingBase entity = ctx.entity();
        /// {@link net.minecraft.util.CombatTracker#calculateFallSuffix)}
        if (entity.isOnLadder()) {
            var blockState = entity.world.getBlockState(new BlockPos(
                    entity.posX, entity.getEntityBoundingBox().minY, entity.posZ
            ));
            if (blockState.getPropertyKeys().contains(BlockHorizontal.FACING)) {
                // 输出数字 0-3，分别对应：南-西-北-东
                return blockState.getValue(BlockHorizontal.FACING).getHorizontalIndex();
            }
        }
        return 0;
    }
}
