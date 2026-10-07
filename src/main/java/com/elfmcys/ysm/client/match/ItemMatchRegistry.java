package com.elfmcys.ysm.client.match;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.client.compat.slashblade.SlashBladeCompat;
import com.elfmcys.ysm.util.IdUtil;
import net.minecraft.item.*;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.function.Predicate;

public final class ItemMatchRegistry {
    public static final EnumMap<ItemMatchKind, List<MatchRule<ItemStack>>> RULES = new EnumMap<>(ItemMatchKind.class);

    private ItemMatchRegistry() {
    }

    static {
        register(":sword", ItemSword.class);
        register(":axe", ItemAxe.class);
        register(":pickaxe", ItemPickaxe.class);
        register(":shovel", ItemSpade.class);
        register(":hoe", ItemHoe.class);
        register(":shield", ItemShield.class);
        register(":bow", ItemBow.class);
        register(":fishing_rod", ItemFishingRod.class);
        register(":throwable_potion", ItemSplashPotion.class);
        register(":throwable_potion", ItemLingeringPotion.class);

        register("#minecraft:axe", ItemAxe.class);
        register("#minecraft:hoe", ItemHoe.class);
        register("#minecraft:pickaxe", ItemPickaxe.class);
        register("#minecraft:shovel", ItemSpade.class);
        register("#minecraft:sword", ItemSword.class);
        register("#minecraft:throwable_potion", ItemSplashPotion.class);
        register("#minecraft:throwable_potion", ItemLingeringPotion.class);
        register("#minecraft:bow", ItemBow.class);
        register("#forge:tools/fishing_rods", ItemFishingRod.class);
        register("#forge:tools/shields", ItemShield.class);

        Predicate<ItemStack> isSlashBlade = stack -> SlashBladeCompat.isSlashBladeItem(stack);
        register("#slashblade:slashblade", isSlashBlade);
        register("#slashblade:slashblade_wood", isSlashBlade);
        register("#slashblade:slashblade_bamboo", isSlashBlade);
        register("#slashblade:slashblade_silverbamboo", isSlashBlade);
        register("#slashblade:slashblade_white", isSlashBlade);
    }

    public static void register(String name, String alias) {
        register(0, name, alias);
    }

    public static void register(int priority, String name, String alias) {
        register(priority, name, MatcherFactory.isItemId(IdUtil.normalize(alias)));
    }

    public static void register(String name, Class<?> itemClass) {
        register(0, name, itemClass);
    }

    public static void register(int priority, String name, Class<?> itemClass) {
        register(priority, name, MatcherFactory.isItem(itemClass));
    }

    public static void register(String name, EnumAction anim) {
        register(0, name, anim);
    }

    public static void register(int priority, String name, EnumAction anim) {
        register(priority, name, MatcherFactory.isAnim(anim));
    }

    public static void register(String name, Predicate<ItemStack> matcher) {
        register(0, name, matcher);
    }

    public static void register(int priority, String name, Predicate<ItemStack> matcher) {
        ItemMatchKind kind = ItemMatchKind.fromName(name);
        if (kind == null) {
            YesSteveModel.LOGGER.warn("Invalid item match kind in '{}'", name);
            return;
        }
        MatchRule.addRule(priority, kind.normalize(name), matcher, RULES.computeIfAbsent(kind, _ -> new ArrayList<>()));
    }

    public static String test(ItemStack stack, @Nullable Collection<String> tests, ItemMatchKind kind) {
        if (tests == null) {
            return MatchConstants.EMPTY;
        }
        for (String test : tests) {
            if (matches(stack, test, kind)) {
                return test;
            }
        }
        return MatchConstants.EMPTY;
    }

    public static boolean matches(ItemStack stack, String name, ItemMatchKind kind) {
        return MatchRule.anyMatch(RULES.get(kind), name, stack) || kind.match(stack, name);
    }
}
