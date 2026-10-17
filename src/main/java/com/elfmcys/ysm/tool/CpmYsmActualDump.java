package com.elfmcys.ysm.tool;

import com.elfmcys.ysm.format.parser.GeoBuilder;
import com.elfmcys.ysm.format.parser.CpmYsmModelAdapter;
import com.elfmcys.ysm.format.parser.CpmTextureAtlas;
import cc.sirrus.ysmlib.scene.ByteData;
import cc.sirrus.ysmlib.cpm.CpmDocument;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Exports the geometry produced by the real CPM -> YSM adapter.
 *
 * <p>This is deliberately a small, dependency-free diagnostic tool.  The JSON is a dump of the
 * post-GeoBuilder protobuf, so offline renderers can consume the same vertices, indices, normals,
 * pivots and transform mode as the game runtime instead of rebuilding cubes from CPM input.
 */
public final class CpmYsmActualDump {
  private CpmYsmActualDump() {}

  public static void main(String[] args) throws Exception {
    if (args.length < 1 || args.length > 2) {
      throw new IllegalArgumentException("usage: CpmYsmActualDump <model-directory> [output.json]");
    }
    Path modelDir = Path.of(args[0]);
    Path output = args.length == 2 ? Path.of(args[1]) : Path.of("build/cpm-ysm-dump.json");
    var files = new java.util.LinkedHashMap<String, ByteData>();
    try (var paths = Files.walk(modelDir)) {
      for (var path : paths.filter(Files::isRegularFile).sorted().toList()) {
        var relative = modelDir.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
        files.put(relative, new ByteData(Files.readAllBytes(path)));
      }
    }
    if (!files.containsKey("model.json")) {
      var candidates = files.keySet().stream().filter(p -> p.equalsIgnoreCase("model.json") || p.toLowerCase(Locale.ROOT).endsWith("/model.json")).toList();
      if (candidates.size() == 1) {
        String prefix = candidates.get(0).substring(0, candidates.get(0).length() - "model.json".length());
        var normalized = new java.util.LinkedHashMap<String, ByteData>();
        files.forEach((p, b) -> normalized.put(p.startsWith(prefix) ? p.substring(prefix.length()) : p, b)); files = normalized;
      }
    }
    if (!files.containsKey("model.json")) throw new IOException("CPM directory has no model.json");
    var document = cc.sirrus.ysmlib.YsmRuntime.cpm().read(files, cc.sirrus.ysmlib.scene.io.ReadLimits.DEFAULT);
    var atlas = CpmTextureAtlas.build(document);
    GeoBuilder.Result result = CpmYsmModelAdapter.build(document, atlas.layout());
    java.util.Map<String, double[]> neutralPose = new java.util.LinkedHashMap<>();
    // The provider dispatches to the format-specific session: Modern uses
    // ModelBone.instantiate + ScriptBone semantics, while Legacy remains on
    // its independent CustomModel evaluator.  Export the complete neutral
    // runtime pose for either source family.
    try (var session = cc.sirrus.ysmlib.YsmRuntime.cpm().createPoseSession(document).orElseThrow(
        () -> new IOException("CPM provider has no pose session"))) {
      session.init(0d);
      // The source Modern renderer instantiates vanilla biped bones at their
      // neutral rotation points before attaching CPM bones.  The offline
      // dump has no Minecraft entity to provide those host attributes, so
      // seed the exact neutral host attribute channel explicitly.  This is
      // source skeleton data, not a model-specific offset.
      if (document instanceof CpmDocument.Remake remake) {
        var p = remake.skeleton().parameters();
        double armX = p.getOrDefault("arm_interval", 10d) / 2d;
        double legX = p.getOrDefault("leg_interval", 3.8d) / 2d;
        double armY = 24d - p.getOrDefault("arm_pivot_height", 22d);
        double legY = 24d - p.getOrDefault("leg_length", 12d);
        double headY = 24d - p.getOrDefault("head_pivot_height", 24d);
        double bodyY = 24d - p.getOrDefault("body_pivot_height", 24d);
        var hosts = new java.util.LinkedHashMap<String, cc.sirrus.ysmlib.cpm.CpmPoseSession.BonePose>();
        // The canonical YSM host skeleton is a parent chain.  Distribute the
        // vanilla root rotation points as parent-local attributes so Head,
        // arms and legs are not translated once per synthetic ancestor.
        hosts.put("head", neutralHost(0, headY - bodyY, p.getOrDefault("head_offset", 0d)));
        hosts.put("body", neutralHost(0, bodyY, p.getOrDefault("body_offset", 0d)));
        hosts.put("left_arm", neutralHost(-armX, armY - bodyY, p.getOrDefault("arm_offset", 0d)));
        hosts.put("right_arm", neutralHost(armX, armY - bodyY, p.getOrDefault("arm_offset", 0d)));
        // The synthetic leg branch is rooted below DownBody/AllBody, whose
        // neutral position is zero in the compact YSM skeleton; retain the
        // vanilla leg rotation point here.
        hosts.put("left_leg", neutralHost(-legX, legY, p.getOrDefault("leg_offset", 0d)));
        hosts.put("right_leg", neutralHost(legX, legY, p.getOrDefault("leg_offset", 0d)));
        if ("quadruped".equals(remake.skeleton().type())) {
          double qLegX = p.getOrDefault("leg_interval", 6d) / 2d;
          double qLegY = 24d - p.getOrDefault("leg_length", 6d);
          double qLegZ = p.getOrDefault("leg_offset", 1d);
          double qFrontZ = qLegZ + p.getOrDefault("body_length", 12d);
          hosts.put("left_back_leg", neutralHost(-qLegX, qLegY, qLegZ));
          hosts.put("right_back_leg", neutralHost(qLegX, qLegY, qLegZ));
          hosts.put("left_front_leg", neutralHost(-qLegX, qLegY, qFrontZ));
          hosts.put("right_front_leg", neutralHost(qLegX, qLegY, qFrontZ));
        }
        if ("slime".equals(remake.skeleton().type())) hosts.keySet().removeIf(k -> !k.equals("body"));
        session.setHostPose(hosts);
      }
      session.tick(0d, java.util.Map.of()).forEach((name, pose) ->
          neutralPose.put(name, new double[]{pose.position()[0], pose.position()[1], pose.position()[2],
              pose.rotation()[0], pose.rotation()[1], pose.rotation()[2], pose.scale()[0], pose.scale()[1], pose.scale()[2],
              pose.visible() ? 1d : 0d}));
    }
    String json = dump(result.model(), neutralPose);
    Files.createDirectories(output.toAbsolutePath().normalize().getParent());
    Files.writeString(output, json);
    String atlasName = output.getFileName().toString().replaceFirst("\\.json$", "-atlas.png");
    Files.write(output.resolveSibling(atlasName), atlas.png());
    System.out.println(output.toAbsolutePath().normalize());
  }

  private static cc.sirrus.ysmlib.cpm.CpmPoseSession.BonePose neutralHost(double attrX, double attrY, double attrZ) {
    return new cc.sirrus.ysmlib.cpm.CpmPoseSession.BonePose(
        new double[]{attrX, attrY, attrZ}, new double[]{0, 0, 0},
        new double[]{1, 1, 1}, true, new double[]{1, 1, 1}, 1, false);
  }

  public static String dump(Object model) throws IOException { return dump(model, java.util.Map.of()); }
  public static String dump(Object model, java.util.Map<String, double[]> neutralPose) throws IOException {
    Object properties = call(model, "properties");
    Object cubesMessage = callStatic("com.elfmcys.ysm.proto.mixel.asset.model.data.Cubes", "parseFrom",
        callStatic("us.hebi.quickbuf.ProtoSource", "newInstance", call(model, "cubes")));
    java.util.List<?> cubes = list(call(cubesMessage, "cubesLegacy"));
    StringBuilder out = new StringBuilder(32_768);
    out.append('{');
    field(out, "transformMode").append(number(call(properties, "transformMode"))).append(',');
    field(out, "textureWidth").append(number(call(properties, "textureWidth"))).append(',');
    field(out, "textureHeight").append(number(call(properties, "textureHeight"))).append(',');
    field(out, "bones").append('[');
    int cubeOffset = 0;
    java.util.List<?> bones = list(call(model, "bones"));
    for (int i = 0; i < bones.size(); i++) {
      if (i > 0) out.append(',');
      Object bone = bones.get(i);
      out.append('{');
      field(out, "index").append(i).append(',');
      field(out, "name"); string(out, String.valueOf(call(bone, "name"))); out.append(',');
      field(out, "parent");
      Object parent = call(bone, "parent");
      if (parent instanceof java.util.Optional<?> o && o.isPresent()) string(out, String.valueOf(o.get())); else out.append("null");
      out.append(',');
      field(out, "parentIndex").append(parentIndex(bones, parent)).append(',');
      field(out, "pivot"); numbers(out, call(bone, "pivot")); out.append(',');
      field(out, "rotate"); numbers(out, call(bone, "rotate")); out.append(',');
      int count = optionalInt(call(bone, "cubeCount"));
      field(out, "cubeCount").append(count).append(',');
      field(out, "cubes").append('[');
      for (int c = 0; c < count; c++) {
        if (c > 0) out.append(',');
        dumpCube(out, cubes.get(cubeOffset++));
      }
      out.append(']');
      out.append('}');
    }
    if (cubeOffset != cubes.size()) {
      throw new IOException("Unowned cubes in YSM geometry: " + (cubes.size() - cubeOffset));
    }
    out.append(']').append(',');
    field(out, "neutralPose").append('{');
    int poseIndex = 0;
    for (var entry : neutralPose.entrySet()) {
      if (poseIndex++ > 0) out.append(',');
      string(out, entry.getKey()); out.append(':');
      out.append('[');
      for (int i = 0; i < entry.getValue().length; i++) { if (i > 0) out.append(','); out.append(entry.getValue()[i]); }
      out.append(']');
    }
    out.append('}');
    out.append('}');
    return out.toString();
  }

  private static void dumpCube(StringBuilder out, Object cube) {
    out.append('{');
    field(out, "faceCount").append(number(call(cube, "faceCount"))).append(',');
    field(out, "pos"); numbers(out, call(cube, "pos")); out.append(',');
    field(out, "uv"); numbers(out, call(cube, "uv")); out.append(',');
    field(out, "normal"); numbers(out, call(cube, "normal")); out.append(',');
    field(out, "posIndices"); ints(out, call(cube, "posIndices")); out.append(',');
    field(out, "uvIndices"); ints(out, call(cube, "uvIndices"));
    out.append('}');
  }

  private static StringBuilder field(StringBuilder out, String name) {
    string(out, name); out.append(':'); return out;
  }

  private static void string(StringBuilder out, String value) {
    if (value == null) { out.append("null"); return; }
    out.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '\\' -> out.append("\\\\");
        case '"' -> out.append("\\\"");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
          else out.append(c);
        }
      }
    }
    out.append('"');
  }

  private static void numbers(StringBuilder out, Object values) {
    out.append('[');
    int n = ((Number) call(values, "size")).intValue();
    for (int i = 0; i < n; i++) { if (i > 0) out.append(','); out.append(number(call(values, "getFloat", i))); }
    out.append(']');
  }

  private static void ints(StringBuilder out, Object values) {
    out.append('[');
    int n = ((Number) call(values, "size")).intValue();
    for (int i = 0; i < n; i++) { if (i > 0) out.append(','); out.append(number(call(values, "getInt", i))); }
    out.append(']');
  }

  private static java.util.List<?> list(Object value) { return (java.util.List<?>) value; }
  private static int optionalInt(Object value) {
    if (value instanceof java.util.OptionalInt oi) return oi.orElse(0);
    var optional = (java.util.Optional<?>) value;
    return optional.isPresent() ? ((Number) optional.get()).intValue() : 0;
  }
  private static int parentIndex(java.util.List<?> bones, Object parent) {
    if (!(parent instanceof java.util.Optional<?> o) || o.isEmpty()) return -1;
    String name = String.valueOf(o.get());
    for (int i = 0; i < bones.size(); i++) if (name.equals(String.valueOf(call(bones.get(i), "name")))) return i;
    return -1;
  }
  private static Number number(Object value) { return (Number) value; }
  private static Object call(Object receiver, String name, Object... args) {
    try {
      for (var method : receiver.getClass().getMethods()) {
        if (method.getName().equals(name) && compatible(method.getParameterTypes(), args)) return method.invoke(receiver, args);
      }
      throw new NoSuchMethodException(name);
    } catch (ReflectiveOperationException e) { throw new IllegalStateException("Cannot call " + name, e); }
  }
  private static Object callStatic(String type, String name, Object... args) {
    try {
      Class<?> c = Class.forName(type);
      for (var method : c.getMethods()) {
        if (java.lang.reflect.Modifier.isStatic(method.getModifiers()) && method.getName().equals(name)
            && compatible(method.getParameterTypes(), args)) return method.invoke(null, args);
      }
      throw new NoSuchMethodException(name);
    } catch (ReflectiveOperationException e) { throw new IllegalStateException("Cannot call " + type + "." + name, e); }
  }
  private static boolean compatible(Class<?>[] types, Object[] args) {
    if (types.length != args.length) return false;
    for (int i = 0; i < types.length; i++) {
      if (args[i] == null) { if (types[i].isPrimitive()) return false; continue; }
      Class<?> t = types[i].isPrimitive() ? wrapper(types[i]) : types[i];
      if (!t.isInstance(args[i])) return false;
    }
    return true;
  }
  private static Class<?> wrapper(Class<?> type) {
    return type == int.class ? Integer.class : type == long.class ? Long.class : type == float.class ? Float.class
        : type == double.class ? Double.class : type == boolean.class ? Boolean.class : type == byte.class ? Byte.class
        : type == short.class ? Short.class : type == char.class ? Character.class : type;
  }
}
