package com.elfmcys.ysm.client.compat.curios.functions;

/*
import com.elfmcys.ysm.client.compat.curios.CuriosCompat;
import com.elfmcys.ysm.client.compat.curios.MolangCache;
import com.elfmcys.ysm.geckolib3.core.molang.context.IContext;
import com.elfmcys.ysm.geckolib3.core.molang.function.entity.LivingEntityFunction;
import com.elfmcys.ysm.molang.runtime.ExecutionContext;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.LivingEntity;
import org.apache.commons.lang3.StringUtils;

public class HasAnyCuriosWithAllTag extends LivingEntityFunction {
    @Override
    protected Object eval(ExecutionContext<IContext<LivingEntity>> ctx, ArgumentCollection args) {
        var slotType = args.getAsString(ctx, 0);
        if (StringUtils.isEmpty(slotType)) {
            return null;
        }

        var tagList = MolangCache.TAG_LIST.get();
        tagList.size(args.size() - 1);
        for (var i = 1; i < args.size(); ++i) {
            var tagId = args.getAsResourceLocation(ctx, i);
            if (tagId == null) {
                return null;
            }
            var tag = TagKey.create(Registries.ITEM, tagId);
            tagList.set(i - 1, tag);
        }

        return CuriosCompat.hasAnyItemEquippedWithAllTag(ctx.entity().entity(), slotType, tagList);
    }

    @Override
    public boolean validateArgumentSize(int size) {
        return size > 1;
    }
}
*/
