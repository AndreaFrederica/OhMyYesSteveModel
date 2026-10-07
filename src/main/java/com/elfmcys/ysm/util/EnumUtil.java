package com.elfmcys.ysm.util;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.inventory.EntityEquipmentSlot;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

public class EnumUtil {
    private static final Object2ObjectOpenHashMap<String, EntityEquipmentSlot> EQUIPMENT_SLOTS = new Object2ObjectOpenHashMap<>();

    static {
        for (EntityEquipmentSlot slot : EntityEquipmentSlot.values()) {
            EQUIPMENT_SLOTS.put(slot.getName().toLowerCase(Locale.US), slot);
        }
    }

    @Nullable
    public static EntityEquipmentSlot getEquipmentSlot(String name) {
        return EQUIPMENT_SLOTS.get(name);
    }
}
