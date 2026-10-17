package cc.sirrus.ysmlib.cpm;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Optional host registry bridge used by animation.js ScriptItem.getItem(). */
public final class CpmItemNames {
    private static final ConcurrentMap<Long, String> NAMES = new ConcurrentHashMap<>();
    private CpmItemNames() {}
    public static void register(long id, String name) { if (id >= 0 && name != null) NAMES.put(id, name); }
    public static String lookup(long id) { return NAMES.getOrDefault(id, "minecraft:air"); }
}
