package com.elfmcys.ysm.geckolib3.util;

import net.minecraft.block.state.IBlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.common.BiomeDictionary;
import net.minecraftforge.oredict.OreDictionary;

/**
 * 1.12.2 没有 tag 体系（tag 自 1.13 引入），本类为 molang 的 {@code *_has_any_tag} /
 * {@code *_has_all_tags} 查询提供 1.12.2 的等价替身，判定顺序：
 * <ol>
 *   <li>少数有明确 1.12.2 语义的原版 tag 直接映射（目前只有 {@code minecraft:replaceable}）；</li>
 *   <li>其次走 1.12.2 自身的分组机制——物品用 {@link OreDictionary} 矿物词典名，
 *       生物群系用 {@link BiomeDictionary} 的 Type 名；</li>
 *   <li>最后退化为与对象自身注册名比较，使模型里直接写 {@code minecraft:xxx} 的写法仍能命中。</li>
 * </ol>
 * 命不中的一律返回 {@code false}，不抛异常。因此为 1.20.1 编写的模型包若使用 1.13+
 * 的 tag id，需改用 1.12.2 的矿物词典名 / BiomeDictionary Type 名 / 具体注册名。
 */
public final class LegacyTags {
    private LegacyTags() {
    }

    public static boolean blockHasTag(IBlockState state, ResourceLocation tagId) {
        // 1.13+ 的 minecraft:replaceable 正是由旧版材质的 replaceable 标志派生而来
        if ("minecraft".equals(tagId.getNamespace()) && "replaceable".equals(tagId.getPath())) {
            return state.getMaterial().isReplaceable();
        }
        return tagId.equals(state.getBlock().getRegistryName());
    }

    public static boolean itemHasTag(ItemStack stack, ResourceLocation tagId) {
        String path = tagId.getPath();
        for (int oreId : OreDictionary.getOreIDs(stack)) {
            if (path.equalsIgnoreCase(OreDictionary.getOreName(oreId))) {
                return true;
            }
        }
        return tagId.equals(stack.getItem().getRegistryName());
    }

    public static boolean biomeHasTag(Biome biome, ResourceLocation tagId) {
        // 1.20.1 的生物群系 tag 惯例是 minecraft:is_forest，BiomeDictionary 侧则是 FOREST
        String path = tagId.getPath();
        if (path.startsWith("is_")) {
            path = path.substring(3);
        }
        for (BiomeDictionary.Type type : BiomeDictionary.getTypes(biome)) {
            if (path.equalsIgnoreCase(type.getName())) {
                return true;
            }
        }
        return tagId.equals(biome.getRegistryName());
    }
}
