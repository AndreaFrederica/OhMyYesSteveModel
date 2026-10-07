package com.elfmcys.ysm.client.match;

import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

public record MatchRule<T>(int priority, String name, Predicate<T> matcher) {
    public static <C> boolean anyMatch(@Nullable Collection<MatchRule<C>> rules, String name, C candidate) {
        if (rules == null) {
            return false;
        }
        for (var rule : rules) {
            if (rule.name().equalsIgnoreCase(name) && rule.matcher().test(candidate)) {
                return true;
            }
        }
        return false;
    }

    public static <C> void addRule(int priority, String name, Predicate<C> matcher, @Nullable List<MatchRule<C>> rules) {
        if (rules == null) {
            return;
        }
        int index = rules.size();
        while (index > 0 && rules.get(index - 1).priority() < priority) {
            index--;
        }
        rules.add(index, new MatchRule<>(priority, name, matcher));
    }
}
