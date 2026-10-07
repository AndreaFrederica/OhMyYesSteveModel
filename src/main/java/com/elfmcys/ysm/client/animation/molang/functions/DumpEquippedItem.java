package com.elfmcys.ysm.client.animation.molang.functions;

import com.elfmcys.ysm.geckolib3.core.molang.context.IContext;
import com.elfmcys.ysm.geckolib3.core.molang.function.entity.LivingEntityFunction;
import com.elfmcys.ysm.geckolib3.util.MolangUtils;
import com.elfmcys.ysm.molang.runtime.ExecutionContext;
import com.elfmcys.ysm.util.ComponentUtils;
import com.elfmcys.ysm.util.EquipmentUtil;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import net.minecraftforge.oredict.OreDictionary;

public class DumpEquippedItem extends LivingEntityFunction {
    @Override
    protected Object eval(ExecutionContext<IContext<EntityLivingBase>> context, ArgumentCollection arguments) {
        if (!context.entity().isDebugEnabled()) {
            return null;
        }

        EntityEquipmentSlot slotType = MolangUtils.parseSlotType(context.entity(), arguments.getAsString(context, 0));
        if (slotType == null) {
            return null;
        }

        ItemStack itemStack = EquipmentUtil.getEquippedItem(context.entity().entity(), slotType);
        if (itemStack.isEmpty()) {
            return null;
        }

        ResourceLocation id = ForgeRegistries.ITEMS.getKey(itemStack.getItem());
        if (id == null) {
            return null;
        }
        context.entity().debugPrint(new TextComponentString("Display ").appendSibling(ComponentUtils.suggestOnClickText(itemStack.getItem().getItemStackDisplayName(itemStack))));
        context.entity().debugPrint(new TextComponentString("Name ").appendSibling(ComponentUtils.suggestOnClickText(id.toString())));

        for (int oreId : OreDictionary.getOreIDs(itemStack)) {
            context.entity().debugPrint(new TextComponentString("OreDict: name ").appendSibling(ComponentUtils.suggestOnClickText(OreDictionary.getOreName(oreId)))
                    .appendText("  id ").appendSibling(ComponentUtils.suggestOnClickText(String.valueOf(oreId))));
        }

        for (var entry : EnchantmentHelper.getEnchantments(itemStack).entrySet()) {
            Enchantment enchantment = entry.getKey();
            if (enchantment == null) {
                continue;
            }
            ResourceLocation enchantmentId = enchantment.getRegistryName();
            if (enchantmentId == null) {
                continue;
            }
            int level = entry.getValue();
            context.entity().debugPrint(new TextComponentString("Enchantment: display ").appendSibling(ComponentUtils.suggestOnClickText(enchantment.getTranslatedName(level)))
                    .appendText("  name ").appendSibling(ComponentUtils.suggestOnClickText(enchantmentId.toString())));
        }

        return null;
    }

    @Override
    public boolean validateArgumentSize(int size) {
        return size == 1;
    }
}
