package com.elfmcys.ysm.client.match;

import com.elfmcys.ysm.util.IdUtil;
import net.minecraft.item.EnumAction;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.oredict.OreDictionary;
import org.jetbrains.annotations.Nullable;

public enum ItemMatchKind {
    ID {
        @Override
        public String prefix() {
            return MatchConstants.ID_PRE;
        }

        @Override
        boolean isValid(String name) {
            return IdUtil.isValidResourceLocation(name);
        }

        @Override
        boolean match(ItemStack stack, String test) {
            if (stack.isEmpty()) return false;
            Item item = stack.getItem();
            //noinspection ConstantValue
            if (item == null) return false;
            ResourceLocation regName = item.getRegistryName();
            return regName != null && regName.toString().equals(test);
        }

        @Override
        public String normalize(String name) {
            return IdUtil.normalize(super.normalize(name));
        }
    },
    ORE {
        @Override
        public String prefix() {
            return MatchConstants.ORE_PRE;
        }

        @Override
        boolean isValid(String name) {
            return OreDictionary.doesOreNameExist(name);
        }

        @Override
        boolean match(ItemStack stack, String test) {
            if (stack.isEmpty()) return false;
            for (int oreId : OreDictionary.getOreIDs(stack)) {
                String oreName = OreDictionary.getOreName(oreId);
                if (!oreName.equals("Unknown") && oreName.equals(test)) {
                    return true;
                }
            }
            return false;
        }
    },
    TAG {
        @Override
        public String prefix() {
            return MatchConstants.TAG_PRE;
        }

        @Override
        boolean isValid(String name) {
            return IdUtil.isValidResourceLocation(name);
        }

        @Override
        boolean match(ItemStack stack, String test) {
            return false;
        }

        @Override
        public String normalize(String name) {
            return IdUtil.normalize(super.normalize(name));
        }
    },
    EXTRA {
        @Override
        public String prefix() {
            return MatchConstants.EXTRA_PRE;
        }

        @Override
        boolean isValid(String name) {
            return !name.equalsIgnoreCase(EnumAction.NONE.name());
        }

        @Override
        boolean match(ItemStack stack, String test) {
            EnumAction anim = stack.getItemUseAction();
            //noinspection ConstantValue
            return anim != null && anim.name().equalsIgnoreCase(test);
        }
    };

    public abstract String prefix();

    abstract boolean isValid(String name);

    abstract boolean match(ItemStack stack, String test);

    /**
     * 去除符号标记并规范化。
     *
     * @param name 以 {@link MatchConstants} 中的符号开头
     */
    public String normalize(String name) {
        return name.substring(1);
    }

    /**
     * @param name 以 {@link MatchConstants} 中的符号开头
     */
    @Nullable
    public static ItemMatchKind fromName(String name) {
        String candidate = name.substring(1);
        for (ItemMatchKind kind : ItemMatchKind.values()) {
            if (name.startsWith(kind.prefix()) && kind.isValid(candidate)) {
                return kind;
            }
        }
        return null;
    }
}
