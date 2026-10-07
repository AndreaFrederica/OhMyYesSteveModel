package com.elfmcys.ysm.client.match;

import com.elfmcys.ysm.util.IdUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

public enum EntityMatchKind {
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
        boolean match(Entity entity, String test) {
            ResourceLocation regName = EntityList.getKey(entity);
            return regName != null && regName.toString().equals(test);
        }

        @Override
        public String normalize(String name) {
            return IdUtil.normalize(super.normalize(name));
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
        boolean match(Entity entity, String test) {
            return false;
        }

        @Override
        public String normalize(String name) {
            return IdUtil.normalize(super.normalize(name));
        }
    };

    public abstract String prefix();

    abstract boolean isValid(String name);

    abstract boolean match(Entity entity, String test);

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
    public static EntityMatchKind fromName(String name) {
        String candidate = name.substring(1);
        for (EntityMatchKind kind : EntityMatchKind.values()) {
            if (name.startsWith(kind.prefix()) && kind.isValid(candidate)) {
                return kind;
            }
        }
        return null;
    }
}
