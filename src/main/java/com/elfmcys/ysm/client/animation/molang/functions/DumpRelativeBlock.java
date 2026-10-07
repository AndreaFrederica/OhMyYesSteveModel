package com.elfmcys.ysm.client.animation.molang.functions;

import com.elfmcys.ysm.geckolib3.core.molang.context.IContext;
import com.elfmcys.ysm.geckolib3.core.molang.function.entity.EntityFunction;
import com.elfmcys.ysm.geckolib3.util.MolangUtils;
import com.elfmcys.ysm.molang.runtime.ExecutionContext;
import com.elfmcys.ysm.util.ComponentUtils;
import net.minecraft.entity.Entity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import net.minecraftforge.oredict.OreDictionary;

public class DumpRelativeBlock extends EntityFunction {
    @Override
    protected Object eval(ExecutionContext<IContext<Entity>> ctx, ArgumentCollection arguments) {
        if (!ctx.entity().isDebugEnabled()) {
            return null;
        }

        var block = MolangUtils.getRelativeBlock(ctx, arguments);
        if (block == null) {
            return null;
        }
        ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(block.getBlock());
        if (blockId == null) {
            return null;
        }
        ctx.entity().debugPrint(new TextComponentString("Display ").appendSibling(ComponentUtils.suggestOnClickText(block.getBlock().getLocalizedName())));
        ctx.entity().debugPrint(new TextComponentString("Name ").appendSibling(ComponentUtils.suggestOnClickText(blockId.toString())));
        ItemStack stack = new ItemStack(Item.getItemFromBlock(block.getBlock()), 1, block.getBlock().damageDropped(block));
        for (int oreId : OreDictionary.getOreIDs(stack)) {
            ctx.entity().debugPrint(new TextComponentString("OreDict: name ").appendSibling(ComponentUtils.suggestOnClickText(OreDictionary.getOreName(oreId)))
                    .appendText("  id ").appendSibling(ComponentUtils.suggestOnClickText(String.valueOf(oreId))));
        }

        return null;
    }

    @Override
    public boolean validateArgumentSize(int size) {
        return size == 3;
    }
}
