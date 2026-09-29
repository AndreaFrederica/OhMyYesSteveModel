package cc.sirrus.ysmlib.legacy.java;

import java.util.*;

/** Internal historical wire tree; construction is confined to one import. */
final class WireNode {
  final Map<String, Object> fields = new LinkedHashMap<>();

  WireNode put(String key, Object value) {
    if (value != null) fields.put(key, value);
    return this;
  }

  boolean has(String key) {
    return fields.containsKey(key);
  }

  String s(String key) {
    return (String) fields.getOrDefault(key, "");
  }

  int i(String key) {
    return ((Number) fields.getOrDefault(key, 0)).intValue();
  }

  long l(String key) {
    return ((Number) fields.getOrDefault(key, 0)).longValue();
  }

  float f(String key) {
    return ((Number) fields.getOrDefault(key, 0)).floatValue();
  }

  boolean b(String key) {
    return (Boolean) fields.getOrDefault(key, false);
  }

  WireNode n(String key) {
    return (WireNode) fields.getOrDefault(key, new WireNode());
  }

  byte[] bytes(String key) {
    return (byte[]) fields.get(key);
  }

  @SuppressWarnings("unchecked")
  <T> List<T> list(String key) {
    return (List<T>) fields.getOrDefault(key, List.of());
  }

  @SuppressWarnings("unchecked")
  <K, V> Map<K, V> map(String key) {
    return (Map<K, V>) fields.getOrDefault(key, Map.of());
  }
}
