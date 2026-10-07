package com.elfmcys.ysm.geckolib3.util;

import com.elfmcys.ysm.geckolib3.core.molang.context.IContext;
import com.elfmcys.ysm.molang.runtime.ExecutionContext;
import com.elfmcys.ysm.molang.runtime.Function;
import com.elfmcys.ysm.util.EnumUtil;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

public class MolangUtils {
    // 改成 -5 ~ 5 之间，防止有人传入过大的值有人用此做矿透
    private static final int MAX_RELATIVE_BLOCK_POS = 5;

    public static float normalizeTime(long timestamp) {
        return ((float) (timestamp + 6000L) / 24000) % 1;
    }

    @Nullable
    public static IBlockState getRelativeBlock(ExecutionContext<IContext<Entity>> ctx, Function.ArgumentCollection args) {
        return getRelativeBlock(ctx, args, 0);
    }

    @Nullable
    public static IBlockState getRelativeBlock(ExecutionContext<IContext<Entity>> ctx, Function.ArgumentCollection args, int argsOffset) {
        double offsetX = args.getAsDouble(ctx, argsOffset);
        double offsetY = args.getAsDouble(ctx, argsOffset + 1);
        double offsetZ = args.getAsDouble(ctx, argsOffset + 2);
        if (Math.abs(offsetX) > MAX_RELATIVE_BLOCK_POS || Math.abs(offsetY) > MAX_RELATIVE_BLOCK_POS || Math.abs(offsetZ) > MAX_RELATIVE_BLOCK_POS) {
            return null;
        }
        var entity = ctx.entity().entity();
        BlockPos pos = new BlockPos((int) Math.round(entity.posX + offsetX - 0.5d),
                (int) Math.round(entity.posY + offsetY - 0.5d),
                (int) Math.round(entity.posZ + offsetZ - 0.5d));
        return entity.world.getBlockState(pos);
    }

    @Nullable
    public static EntityEquipmentSlot parseSlotType(IContext<?> context, String value) {
        if (value == null) {
            return null;
        }
        EntityEquipmentSlot slot = EnumUtil.getEquipmentSlot(value);
        if (slot == null) {
            context.debugPrint("Illegal slot type: %s.", value);
        }
        return slot;
    }
}
