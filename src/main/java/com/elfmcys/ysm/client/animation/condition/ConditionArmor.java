package com.elfmcys.ysm.client.animation.condition;

import com.elfmcys.ysm.client.match.ItemMatchKind;
import com.elfmcys.ysm.client.match.ItemMatchRegistry;
import com.elfmcys.ysm.client.match.MatchConstants;
import com.elfmcys.ysm.util.EnumUtil;
import com.elfmcys.ysm.util.EquipmentUtil;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.inventory.EntityEquipmentSlot;

import net.minecraft.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;

public class ConditionArmor {
    private final EnumMap<EntityEquipmentSlot, EnumMap<ItemMatchKind, ObjectOpenHashSet<String>>> test = new EnumMap<>(EntityEquipmentSlot.class);

    public ConditionArmor() {
    }

    public void addTest(String name) {
        EntityEquipmentSlot type = getType(name);
        if (type == null) {
            return;
        }
        String value = name.substring(type.getName().length());
        if (value.length() <= 1) {
            return;
        }
        ItemMatchKind kind = ItemMatchKind.fromName(value);
        if (kind == null) {
            return;
        }
        this.test.computeIfAbsent(type, _ -> new EnumMap<>(ItemMatchKind.class))
                .computeIfAbsent(kind, _ -> new ObjectOpenHashSet<>())
                .add(kind.normalize(value));
    }

    public String doTest(EntityLivingBase livingEntity, EntityEquipmentSlot slot) {
        ItemStack item = EquipmentUtil.getEquippedItem(livingEntity, slot);
        if (item.isEmpty()) {
            return MatchConstants.EMPTY;
        }
        EnumMap<ItemMatchKind, ObjectOpenHashSet<String>> test = this.test.get(slot);
        if (test == null) {
            return MatchConstants.EMPTY;
        }
        for (ItemMatchKind kind : ItemMatchKind.values()) {
            String result = ItemMatchRegistry.test(item, test.get(kind), kind);
            if (!result.isEmpty()) {
                return slot.getName() + kind.prefix() + result;
            }
        }
        return MatchConstants.EMPTY;
    }

    public boolean hasTest(EntityEquipmentSlot slot) {
        return this.test.containsKey(slot);
    }

    @Nullable
    private static EntityEquipmentSlot getType(String name) {
        for (int i = 0; i < name.length(); i++) {
            if (isKindSymbol(name.charAt(i))) {
                return EnumUtil.getEquipmentSlot(name.substring(0, i));
            }
        }
        return null;
    }

    private static boolean isKindSymbol(char symbol) {
        return symbol == MatchConstants.ID_PRE_C || symbol == MatchConstants.ORE_PRE_C || symbol == MatchConstants.TAG_PRE_C;
    }
}
