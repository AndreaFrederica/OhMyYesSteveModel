package com.elfmcys.ysm.client.compat.curios.functions;

/*
import com.elfmcys.ysm.client.compat.curios.CuriosCompat;
import com.elfmcys.ysm.client.compat.curios.MolangCache;
import com.elfmcys.ysm.geckolib3.core.molang.context.IContext;
import com.elfmcys.ysm.geckolib3.core.molang.function.entity.LivingEntityFunction;
import com.elfmcys.ysm.molang.runtime.ExecutionContext;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.commons.lang3.StringUtils;

public class HasAnyCurios extends LivingEntityFunction {
    @Override
    protected Object eval(ExecutionContext<IContext<LivingEntity>> ctx, ArgumentCollection args) {
        var slotType = args.getAsString(ctx, 0);
        if (StringUtils.isEmpty(slotType)) {
            return null;
        }

        var itemSet = MolangCache.ITEM_SET.get();
        itemSet.clear();
        for (var i = 1; i < args.size(); ++i) {
            var itemId = args.getAsResourceLocation(ctx, i);
            if (itemId == null) {
                return null;
            }
            var item = ForgeRegistries.ITEMS.getValue(itemId);
            if (item != null) {
                itemSet.add(item);
            }
        }

        return CuriosCompat.hasAnyItemEquipped(ctx.entity().entity(), slotType, itemSet);
    }

    @Override
    public boolean validateArgumentSize(int size) {
        return size > 0;
    }
}
*/
