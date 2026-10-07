package com.elfmcys.ysm.client.compat.curios;

/*
import com.elfmcys.ysm.client.compat.curios.functions.HasAnyCurios;
import com.elfmcys.ysm.client.compat.curios.functions.HasAnyCuriosWithAllTag;
import com.elfmcys.ysm.client.compat.curios.functions.HasAnyCuriosWithAnyTag;
import com.elfmcys.ysm.geckolib3.core.molang.binding.ContextBinding;
import com.elfmcys.ysm.geckolib3.core.molang.context.IContext;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;

import java.util.function.Predicate;

public class CuriosCompatInner {
*/
    /**
     * 搜索整个饰品栏，性能很差。
     */
    /*
    @Nullable
    public static ItemStack searchCuriosInventory(ICuriosItemHandler curiosInventory, Predicate<ItemStack> itemPredicate) {
        for (var curiosHandler : curiosInventory.getCurios().values()) {
            var itemStack = searchCuriosHandler(curiosHandler, itemPredicate);
            if (itemStack != null) {
                return itemStack;
            }
        }
        return null;
    }
    */

    /**
     * 仅搜索一类饰品槽位
     */
    /*
    @Nullable
    public static ItemStack searchCuriosHandler(ICurioStacksHandler curiosHandler, Predicate<ItemStack> itemPredicate) {
        var rendersToggle = curiosHandler.getRenders();
        var cosmeticItemHandler = curiosHandler.hasCosmetic() ? curiosHandler.getCosmeticStacks() : null;
        var itemHandler = curiosHandler.getStacks();
        for (int i = 0; i < itemHandler.getSlots() && i < rendersToggle.size(); i++) {
            if (rendersToggle.get(i)) {
                if (cosmeticItemHandler != null) {
                    var itemStack = cosmeticItemHandler.getStackInSlot(i);
                    if (itemStack != null && !itemStack.isEmpty()) {
                        if (itemPredicate.test(itemStack)) {
                            return itemStack;
                        }
                        continue;
                    }
                }

                var itemStack = itemHandler.getStackInSlot(i);
                if (!itemStack.isEmpty() && itemPredicate.test(itemStack)) {
                    return itemStack;
                }
            }
        }
        return null;
    }

    static void addMolangBinding(ContextBinding binding) {
        binding.function("has_any_curios", new HasAnyCurios());
        binding.function("has_any_curios_with_all_tags", new HasAnyCuriosWithAllTag());
        binding.function("has_any_curios_with_any_tag", new HasAnyCuriosWithAnyTag());
        binding.livingEntityVar("dump_curios", CuriosCompatInner::dumpItems);
    }

    private static Object dumpItems(IContext<? extends LivingEntity> ctx) {
        if (!ctx.isDebugEnabled()) {
            return null;
        }
        CuriosApi.getCuriosInventory(ctx.entity()).ifPresent(inventory -> {
            for (var entry : inventory.getCurios().entrySet()) {
                ctx.debugPrint(Component.literal("-------- Type ").append(ComponentUtils.suggestOnClickText(entry.getKey())).append(" --------"));
                ctx.debugPrint("");
                searchCuriosHandler(entry.getValue(), itemStack -> {
                    ctx.debugPrint(Component.literal("Display ").append(ComponentUtils.suggestOnClickText(itemStack.getHoverName().getString(99))));

                    var holder = itemStack.getItemHolder();
                    holder.unwrapKey().ifPresent(key -> ctx.debugPrint(
                            Component.literal("Name ").append(ComponentUtils.suggestOnClickText(key.location().toString()))));
                    holder.tags().forEach(tagKey -> ctx.debugPrint(
                            Component.literal("Tag ").append(ComponentUtils.suggestOnClickText(tagKey.location().toString()))));

                    ctx.debugPrint("");
                    return false;
                });
            }
        });
        return null;
    }
}
*/
