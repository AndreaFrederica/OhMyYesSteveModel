package com.elfmcys.ysm.client.match;

import com.elfmcys.ysm.YesSteveModel;
import com.elfmcys.ysm.util.IdUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.EntityFishHook;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.function.Predicate;

public final class EntityMatchRegistry {
    public static final EnumMap<EntityMatchKind, List<MatchRule<Entity>>> RULES = new EnumMap<>(EntityMatchKind.class);

    private EntityMatchRegistry() {
    }

    static {
        register("#minecraft:fishing_bobber", EntityFishHook.class);
    }

    public static void register(String name, String alias) {
        register(0, name, alias);
    }

    public static void register(int priority, String name, String alias) {
        register(priority, name, MatcherFactory.isEntityId(IdUtil.normalize(alias)));
    }

    public static void register(String name, Class<?> entityClass) {
        register(0, name, entityClass);
    }

    public static void register(int priority, String name, Class<?> entityClass) {
        register(priority, name, MatcherFactory.isEntity(entityClass));
    }

    public static void register(String name, Predicate<Entity> matcher) {
        register(0, name, matcher);
    }

    public static void register(int priority, String name, Predicate<Entity> matcher) {
        EntityMatchKind kind = EntityMatchKind.fromName(name);
        if (kind == null) {
            YesSteveModel.LOGGER.warn("Invalid entity match kind in '{}'", name);
            return;
        }
        MatchRule.addRule(priority, kind.normalize(name), matcher, RULES.computeIfAbsent(kind, _ -> new ArrayList<>()));
    }

    public static String test(Entity entity, @Nullable Collection<String> tests, EntityMatchKind kind) {
        if (tests == null) {
            return MatchConstants.EMPTY;
        }
        for (String test : tests) {
            if (matches(entity, test, kind)) {
                return test;
            }
        }
        return MatchConstants.EMPTY;
    }

    public static boolean matches(Entity entity, String name, EntityMatchKind kind) {
        return MatchRule.anyMatch(RULES.get(kind), name, entity) || kind.match(entity, name);
    }
}
