package com.elfmcys.ysm.client.animation.molang.functions;

import com.elfmcys.ysm.client.match.ItemMatchKind;
import com.elfmcys.ysm.client.match.ItemMatchRegistry;
import com.elfmcys.ysm.geckolib3.core.molang.context.IContext;
import com.elfmcys.ysm.geckolib3.core.molang.function.entity.LivingEntityFunction;
import com.elfmcys.ysm.geckolib3.util.MolangUtils;
import com.elfmcys.ysm.molang.runtime.ExecutionContext;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import org.apache.commons.lang3.StringUtils;

public class ArmorCheck extends LivingEntityFunction {
    private static final String EMPTY = "empty";
    private static final int FALSE = 0;
    private static final int TRUE = 1;

    public static ArmorCheck armorCheck() {
        return new ArmorCheck();
    }

    @Override
    protected Object eval(ExecutionContext<IContext<EntityLivingBase>> context, ArgumentCollection arguments) {
        EntityEquipmentSlot slotType = MolangUtils.parseSlotType(context.entity(), arguments.getAsString(context, 0));
        if (slotType == null || slotType.getSlotType() != EntityEquipmentSlot.Type.ARMOR) {
            return null;
        }

        String input = arguments.getAsString(context, 1);
        EntityLivingBase entity = context.entity().entity();

        if (StringUtils.isBlank(input)) {
            return FALSE;
        }

        ItemStack item = entity.getItemStackFromSlot(slotType);

        // 为空的特殊判断
        if (item.isEmpty() && input.equals(EMPTY)) {
            return TRUE;
        }

        ItemMatchKind kind = ItemMatchKind.fromName(input);
        return isArmorKind(kind) && ItemMatchRegistry.matches(item, input, kind) ? TRUE : FALSE;
    }

    private static boolean isArmorKind(ItemMatchKind kind) {
        return kind == ItemMatchKind.ID || kind == ItemMatchKind.ORE || kind == ItemMatchKind.TAG;
    }

    @Override
    public boolean validateArgumentSize(int size) {
        return size == 2 || size == 3;
    }
}
