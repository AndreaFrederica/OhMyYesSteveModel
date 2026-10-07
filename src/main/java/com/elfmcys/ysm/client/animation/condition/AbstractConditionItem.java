package com.elfmcys.ysm.client.animation.condition;

import com.elfmcys.ysm.client.match.ItemMatchKind;
import com.elfmcys.ysm.client.match.ItemMatchRegistry;
import com.elfmcys.ysm.client.match.MatchConstants;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;

import java.util.EnumMap;

public abstract class AbstractConditionItem {
    private final EnumMap<ItemMatchKind, ObjectOpenHashSet<String>> test = new EnumMap<>(ItemMatchKind.class);
    private final int preSize;
    private final String prefix;

    protected AbstractConditionItem(String prefix) {
        this.preSize = prefix.length();
        this.prefix = prefix;
    }

    public void addTest(String name) {
        if (name.length() <= this.preSize || !name.startsWith(this.prefix)) {
            return;
        }
        String substring = name.substring(this.preSize);
        ItemMatchKind kind = ItemMatchKind.fromName(substring);
        if (kind == null) {
            return;
        }
        this.test.computeIfAbsent(kind, _ -> new ObjectOpenHashSet<>())
                .add(kind.normalize(substring));
    }

    public String doTest(EntityLivingBase livingEntity, EnumHand hand) {
        ItemStack stack = livingEntity.getHeldItem(hand);
        if (stack.isEmpty()) {
            return MatchConstants.EMPTY;
        }
        for (ItemMatchKind kind : ItemMatchKind.values()) {
            String result = ItemMatchRegistry.test(stack, this.test.get(kind), kind);
            if (!result.isEmpty()) {
                return this.prefix + kind.prefix() + result;
            }
        }
        return MatchConstants.EMPTY;
    }
}
