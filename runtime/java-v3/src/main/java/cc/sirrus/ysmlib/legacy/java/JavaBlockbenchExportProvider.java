package cc.sirrus.ysmlib.legacy.java;

import cc.sirrus.ysmlib.image.ImageProvider;
import cc.sirrus.ysmlib.legacy.BlockbenchExportProvider;
import cc.sirrus.ysmlib.legacy.V3EnvelopeProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Loss-aware, editable Blockbench exporter.  It deliberately uses mesh elements when a historical
 * quad cannot be proven to be a Blockbench cuboid; no geometry is silently dropped.
 */
public final class JavaBlockbenchExportProvider implements BlockbenchExportProvider {
  private static final int MAX_WIRE = 256 * 1024 * 1024;
  private static final int MAX_EMBEDDED_WIRE = 32 * 1024 * 1024;
  private static final int MAX_OUTPUT = 512 * 1024 * 1024;
  private final V3EnvelopeProvider envelope;
  private final ImageProvider images;

  public JavaBlockbenchExportProvider(V3EnvelopeProvider envelope, ImageProvider images) {
    this.envelope = Objects.requireNonNull(envelope);
    this.images = Objects.requireNonNull(images);
  }

  @Override
  public byte[] exportModel(ByteBuffer source) throws IOException {
    return exportWire(envelope.decode(source, MAX_WIRE), source.remaining());
  }

  @Override
  public byte[] exportWire(byte[] wire, long sourceSize) throws IOException {
    if (wire == null || wire.length < 4 || wire.length > MAX_WIRE || sourceSize < 0 || sourceSize > V3EnvelopeProvider.SOURCE_LIMIT)
      throw new IOException("Invalid historical wire budget");
    return new Project(wire, sourceSize).write(new HistoricalWireReader(wire, images).read());
  }

  private final class Project {
    final byte[] wire;
    final long sourceSize;
    final String wireSeed;
    final LinkedHashMap<String, Object> root = new LinkedHashMap<>();
    final List<Object> elements = new ArrayList<>(), outliner = new ArrayList<>(), textures = new ArrayList<>(), animations = new ArrayList<>();
    final List<Object> diagnostics = new ArrayList<>();
    final Map<String, String> boneIds = new LinkedHashMap<>();
    final Map<String, Map<String, String>> boneIdsByTarget = new LinkedHashMap<>();
    final Set<String> duplicateBoneNames = new HashSet<>();
    final Map<String, List<String>> boneChildren = new LinkedHashMap<>();
    final Map<String, List<Float>> bonePivots = new LinkedHashMap<>();
    final Map<String, Object> ysm = new LinkedHashMap<>();
    int textureWidth = 64, textureHeight = 64;

    Project(byte[] wire, long sourceSize) {
      this.wire = wire;
      this.sourceSize = sourceSize;
      try {
        this.wireSeed = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(wire));
      } catch (NoSuchAlgorithmException impossible) {
        throw new AssertionError(impossible);
      }
    }

    byte[] write(WireNode model) throws IOException {
      ysm.put("inner_version", model.i("version"));
      ysm.put("source_size", sourceSize);
      ysm.put("model_id", HexFormat.of().formatHex(model.bytes("modelId")));
      ysm.put("export_profile", profile());
      ysm.put("geometry_policy", "cuboid_when_provable_else_mesh");
      root.put("meta", map("format_version", "4.10", "model_format", "free", "box_uv", false));
      root.put("name", modelName(model));
      collectTargets(model);
      if (textures.isEmpty()) addFallbackTexture();
      root.put("resolution", map("width", textureWidth, "height", textureHeight));
      root.put("elements", elements);
      root.put("outliner", outliner);
      root.put("textures", textures);
      if (!animations.isEmpty()) root.put("animations", animations);
      collectDiagnostics(model);
      collectHistoricalSidecar(model);
      ysm.put("diagnostics", diagnostics);
      root.put("ysm", ysm);
      StringBuilder json = new StringBuilder(128 * 1024);
      Json.append(json, root);
      json.append('\n');
      byte[] output = json.toString().getBytes(StandardCharsets.UTF_8);
      if (output.length > MAX_OUTPUT) throw new IOException("Blockbench project exceeds output budget");
      return output;
    }

    String modelName(WireNode model) {
      WireNode info = model.n("info");
      WireNode metadata = info.n("metadata");
      String name = metadata.s("name");
      return name.isBlank() ? "YSM model" : name;
    }

    void collectTargets(WireNode model) throws IOException {
      if (model.has("player")) {
        WireNode player = model.n("player");
        for (var e : player.<Integer, WireNode>map("geos").entrySet()) {
          String type = switch (e.getKey()) {
            case 1 -> "main";
            case 2 -> "arm";
            default -> "geo-" + e.getKey();
          };
          addGeometry(type, e.getValue().n("value"), player.<String, WireNode>map("textures"));
        }
        addAnimations(player);
        addControllers(player, "player");
      }
      int n = 1;
      for (WireNode replacement : model.<WireNode>list("projectiles")) {
        addGeometry("projectile-" + n++, replacement.n("geo").n("value"), replacementTextures(replacement));
        addAnimationWrapper(replacement.n("animation"), "projectile-" + (n - 1), "projectile-" + (n - 1));
        addControllerWrapper(replacement.n("controller"), "projectile-" + (n - 1));
      }
      n = 1;
      for (WireNode replacement : model.<WireNode>list("vehicles")) {
        addGeometry("vehicle-" + n++, replacement.n("geo").n("value"), replacementTextures(replacement));
        addAnimationWrapper(replacement.n("animation"), "vehicle-" + (n - 1), "vehicle-" + (n - 1));
        addControllerWrapper(replacement.n("controller"), "vehicle-" + (n - 1));
      }
    }

    Map<String, WireNode> replacementTextures(WireNode replacement) {
      return replacement.has("texture") ? Map.of("default", replacement.n("texture")) : Map.of();
    }

    void addGeometry(String target, WireNode geo, Map<String, WireNode> textureMap) throws IOException {
      if (geo.fields.isEmpty()) return;
      Map<String, Integer> targetTextures = addTextures(target, textureMap);
      int defaultTexture = targetTextures.isEmpty() ? 0 : targetTextures.values().iterator().next();
      List<WireNode> bones = geo.<WireNode>list("bones");
      Set<String> names = new HashSet<>();
      List<String> keys = new ArrayList<>();
      for (int i = 0; i < bones.size(); i++) {
        WireNode bone = bones.get(i);
        String local = bone.s("name").isBlank() ? "bone-" + i : bone.s("name");
        String unique = target + "/" + local;
        if (!names.add(local)) {
          unique += "-" + i;
          diagnostic("duplicate_bone", target + " bone " + local + " renamed to " + unique);
        }
        boneIds.put(unique, id("bone:" + unique));
        Map<String, String> scopedBones = boneIdsByTarget.computeIfAbsent(target, ignored -> new LinkedHashMap<>());
        if (scopedBones.containsKey(local)) {
          scopedBones.remove(local);
          duplicateBoneNames.add(target + "/" + local);
        } else if (!duplicateBoneNames.contains(target + "/" + local)) scopedBones.put(local, boneIds.get(unique));
        boneChildren.put(unique, new ArrayList<>());
        bonePivots.put(unique, safeVector(bone.list("pivot"), unique + "/pivot"));
        keys.add(unique);
      }
      for (int i = 0; i < bones.size(); i++) {
        WireNode bone = bones.get(i);
        String unique = keys.get(i);
        String group = boneIds.get(unique);
        for (int c = 0; c < bone.<WireNode>list("cubes").size(); c++) {
          WireNode cube = bone.<WireNode>list("cubes").get(c);
          String elementId = id("element:" + unique + ":" + c);
          Object element = element(cube, elementId, target, unique, defaultTexture);
          elements.add(element);
          boneChildren.get(unique).add(elementId);
        }
      }
      for (int i = 0; i < bones.size(); i++) {
        WireNode bone = bones.get(i);
        String unique = keys.get(i);
        String parent = bone.s("parent");
        String parentKey = parent.isBlank() ? "" : target + "/" + parent;
        if (!parentKey.isBlank() && boneIds.containsKey(parentKey)) boneChildren.get(parentKey).add(boneIds.get(unique));
        else if (!parent.isBlank()) diagnostic("missing_parent", unique + " parent " + parent + " was not found");
      }
      for (int i = 0; i < bones.size(); i++) {
        WireNode bone = bones.get(i);
        String unique = keys.get(i);
        if (!bone.s("parent").isBlank() && boneIds.containsKey(target + "/" + bone.s("parent"))) continue;
        outliner.add(group(unique, bone, boneChildren.getOrDefault(unique, List.of())));
      }
      ysm.computeIfAbsent("geometries", ignored -> new ArrayList<>());
      @SuppressWarnings("unchecked") List<Object> gs = (List<Object>) ysm.get("geometries");
      gs.add(map("target", target, "identifier", geo.n("properties").s("identifier"), "bone_count", bones.size(), "cube_count", countCubes(geo)));
    }

    int countCubes(WireNode geo) {
      return geo.<WireNode>list("bones").stream().mapToInt(b -> b.<WireNode>list("cubes").size()).sum();
    }

    Object group(String unique, WireNode bone, List<String> children) {
      List<Float> pivot = safeVector(bone.list("pivot"), unique + "/pivot");
      List<Float> rotation = safeVector(bone.list("rotation"), unique + "/rotation");
      var out = new LinkedHashMap<String, Object>();
      out.put("name", unique.substring(unique.indexOf('/') + 1));
      out.put("origin", vector(pivot, 3));
      out.put("color", 0);
      out.put("uuid", boneIds.get(unique));
      if (rotation.size() == 3) out.put("rotation", rotation);
      if (bone.b("hidden") || bone.b("dont_render")) out.put("visibility", false);
      out.put("ysm_dont_render", bone.b("dont_render"));
      out.put("ysm_cubes_hidden", bone.b("cubes_hidden"));
      out.put("ysm_hide_children", bone.b("hide_children"));
      out.put("ysm_reset", bone.b("reset"));
      out.put("children", children);
      return out;
    }

    Object element(WireNode cube, String uuid, String target, String bone, int texture) {
      if (isCuboid(cube)) return cuboid(cube, uuid, target, bone, texture);
      return mesh(cube, uuid, target, bone, texture);
    }

    boolean isCuboid(WireNode cube) {
      List<WireNode> qs = cube.list("quads");
      if (qs.size() != 6) return false;
      float[] min = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
      float[] max = {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
      Set<String> normals = new HashSet<>();
      for (WireNode q : qs) {
        List<Float> n = q.list("normal");
        if (n.size() != 3 || !axisNormal(n)) return false;
        String nk = normalKey(n);
        if (!normals.add(nk)) return false;
        for (WireNode v : q.<WireNode>list("vertices")) {
          List<Float> p = v.list("position");
          if (p.size() != 3) return false;
          if (p.stream().anyMatch(x -> !Float.isFinite(x))) return false;
          for (int i = 0; i < 3; i++) { min[i] = Math.min(min[i], p.get(i)); max[i] = Math.max(max[i], p.get(i)); }
        }
      }
      if (normals.size() != 6) return false;
      for (WireNode q : qs) {
        List<Float> n = q.list("normal");
        int axis = Math.abs(Math.round(n.get(0))) == 1 ? 0 : Math.abs(Math.round(n.get(1))) == 1 ? 1 : 2;
        float plane = n.get(axis) < 0 ? min[axis] : max[axis];
        for (WireNode v : q.<WireNode>list("vertices")) if (Math.abs(v.<Float>list("position").get(axis) - plane) > 1e-4f) return false;
        if (!rectangularUv(q)) return false;
      }
      return true;
    }

    boolean axisNormal(List<Float> n) {
      int axes = 0;
      for (float x : n) if (Math.abs(Math.abs(x) - 1f) < 1e-4f) axes++;
      return axes == 1 && n.stream().filter(x -> Math.abs(x) > 1e-4f).count() == 1;
    }

    String normalKey(List<Float> n) {
      int axis = Math.abs(n.get(0)) > .5 ? 0 : Math.abs(n.get(1)) > .5 ? 1 : 2;
      return (axis == 0 ? "x" : axis == 1 ? "y" : "z") + (n.get(axis) < 0 ? "-" : "+");
    }

    boolean rectangularUv(WireNode q) {
      List<List<Float>> points = new ArrayList<>();
      Set<Float> u = new HashSet<>(), v = new HashSet<>();
      for (WireNode vertex : q.<WireNode>list("vertices")) {
        List<Float> uv = vertex.list("uv");
        if (uv.size() != 2 || !Float.isFinite(uv.get(0)) || !Float.isFinite(uv.get(1))) return false;
        points.add(uv);
        u.add(uv.get(0)); v.add(uv.get(1));
      }
      if (points.size() != 4 || u.size() != 2 || v.size() != 2) return false;
      // Every edge must be an edge of the UV rectangle. A scrambled vertex order would
      // otherwise be silently converted to a different texture orientation.
      for (int i = 0; i < 4; i++) {
        List<Float> a = points.get(i), b = points.get((i + 1) % 4);
        boolean sameU = Float.compare(a.get(0), b.get(0)) == 0;
        boolean sameV = Float.compare(a.get(1), b.get(1)) == 0;
        if (sameU == sameV) return false;
      }
      return true;
    }

    Object cuboid(WireNode cube, String uuid, String target, String bone, int texture) {
      float[] min = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
      float[] max = {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
      for (WireNode q : cube.<WireNode>list("quads")) for (WireNode v : q.<WireNode>list("vertices")) {
        List<Float> p = v.list("position");
        for (int i = 0; i < 3; i++) { min[i] = Math.min(min[i], p.get(i)); max[i] = Math.max(max[i], p.get(i)); }
      }
      var out = new LinkedHashMap<String, Object>();
      out.put("name", target + ":" + bone.substring(bone.indexOf('/') + 1) + ":cube");
      out.put("from", list(min[0], min[1], min[2])); out.put("to", list(max[0], max[1], max[2]));
      out.put("origin", vector(findPivot(bone), 3)); out.put("uuid", uuid); out.put("color", 0); out.put("texture", texture);
      if (cube.has("zero_size")) out.put("ysm_zero_size", cube.fields.get("zero_size"));
      var faces = new LinkedHashMap<String, Object>();
      for (WireNode q : cube.<WireNode>list("quads")) {
        String face = faceName(q.list("normal"));
        List<Float> us = new ArrayList<>(), vs = new ArrayList<>();
        for (WireNode v : q.<WireNode>list("vertices")) { us.add(v.<Float>list("uv").get(0)); vs.add(v.<Float>list("uv").get(1)); }
        // Preserve direction and mirroring through ordered UV corners. Blockbench accepts
        // reversed bounds; falling back to mesh is reserved for non-rectangular UVs.
        List<Float> first = q.<WireNode>list("vertices").get(0).list("uv");
        List<Float> opposite = q.<WireNode>list("vertices").get(2).list("uv");
        faces.put(face, map("uv", list(first.get(0), first.get(1), opposite.get(0), opposite.get(1)), "texture", texture));
      }
      out.put("faces", faces);
      return out;
    }

    List<Float> findPivot(String bone) {
      return bonePivots.getOrDefault(bone, List.of(0f, 0f, 0f));
    }

    Object mesh(WireNode cube, String uuid, String target, String bone, int texture) {
      var vertices = new LinkedHashMap<String, Object>();
      var faces = new LinkedHashMap<String, Object>();
      Map<List<Float>, String> ids = new LinkedHashMap<>();
      int[] counters = {0, 0};
      for (WireNode q : cube.<WireNode>list("quads")) {
        var refs = new ArrayList<String>(); var uv = new ArrayList<Object>();
        for (WireNode v : q.<WireNode>list("vertices")) {
          List<Float> p = v.list("position");
          String id = ids.computeIfAbsent(p, ignored -> "v" + counters[0]++);
          List<Float> safePosition = safeVector(p, target + "/" + bone + "/vertex");
          vertices.putIfAbsent(id, safePosition); refs.add(id);
          List<Float> originalUv = v.list("uv");
          uv.add(originalUv.size() == 2 ? List.of(safeNumber(originalUv.get(0), "UV/u"), safeNumber(originalUv.get(1), "UV/v")) : List.of(0f, 0f));
        }
        String faceId = "f" + counters[1]++;
        faces.put(faceId, map("vertices", refs, "uv", uv, "texture", texture, "ysm_normal", safeVector(q.list("normal"), target + "/" + bone + "/normal")));
      }
      var out = new LinkedHashMap<String, Object>();
      out.put("name", target + ":" + bone.substring(bone.indexOf('/') + 1) + ":mesh"); out.put("type", "mesh"); out.put("uuid", uuid);
      out.put("origin", list(0, 0, 0)); out.put("vertices", vertices); out.put("faces", faces); out.put("color", 0);
      diagnostic("mesh_fallback", target + "/" + bone.substring(bone.indexOf('/') + 1) + " cube exported as mesh to preserve " + cube.<WireNode>list("quads").size() + " quads");
      return out;
    }

    String faceName(List<Float> n) {
      if (n.get(0) > .5) return "east"; if (n.get(0) < -.5) return "west";
      if (n.get(1) > .5) return "up"; if (n.get(1) < -.5) return "down";
      if (n.get(2) > .5) return "south"; return "north";
    }

    Map<String, Integer> addTextures(String target, Map<String, WireNode> source) throws IOException {
      var result = new LinkedHashMap<String, Integer>();
      for (var entry : source.entrySet()) {
        WireNode t = entry.getValue();
        WireNode uv = t.n("uv").has("value") ? t.n("uv").n("value") : t.n("uv");
        if (uv.fields.isEmpty()) continue;
        int index = addTexture(target + ":" + entry.getKey(), uv, "uv");
        result.put(entry.getKey(), index);
        @SuppressWarnings("unchecked") List<Object> materials = (List<Object>) ysm.computeIfAbsent("materials", ignored -> new ArrayList<>());
        var pbrIndices = new LinkedHashMap<String, Object>();
        var pbrHashes = new LinkedHashMap<String, Object>();
        for (var pbr : t.<Integer, WireNode>map("pbr").entrySet()) {
          WireNode image = pbr.getValue().has("value") ? pbr.getValue().n("value") : pbr.getValue();
          if (!image.fields.isEmpty()) {
            pbrIndices.put(String.valueOf(pbr.getKey()), addTexture(target + ":" + entry.getKey() + ":pbr-" + pbr.getKey(), image, pbr.getKey() == 1 ? "normal" : pbr.getKey() == 2 ? "specular" : "pbr-" + pbr.getKey()));
            pbrHashes.put(String.valueOf(pbr.getKey()), pbr.getValue().s("hash"));
          }
        }
        materials.add(map("target", target, "name", entry.getKey(), "uv_texture", index, "uv_hash", t.n("uv").s("hash"), "pbr", pbrIndices, "pbr_hashes", pbrHashes));
      }
      return result;
    }

    int addTexture(String name, WireNode image, String role) throws IOException {
      byte[] rgba = images.decode(ByteBuffer.wrap(image.bytes("bytes")), new ImageProvider.Info(ImageProvider.Format.valueOf(image.s("encoding")), image.i("width"), image.i("height")));
      byte[] png = png(rgba, image.i("width"), image.i("height"));
      String uuid = id("texture:" + name);
      String source = "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
      var t = new LinkedHashMap<String, Object>();
      t.put("name", name.replaceAll("[^A-Za-z0-9_.-]", "_" ) + ".png"); t.put("path", t.get("name")); t.put("folder", ""); t.put("namespace", ""); t.put("id", uuid);
      t.put("particle", false); t.put("render_mode", "normal"); t.put("visible", true); t.put("internal", true); t.put("saved", true); t.put("uuid", uuid); t.put("source", source);
      t.put("uv_width", image.i("width")); t.put("uv_height", image.i("height"));
      t.put("ysm_role", role); t.put("ysm_source_encoding", image.s("encoding"));
      if (textures.isEmpty()) { textureWidth = image.i("width"); textureHeight = image.i("height"); }
      textures.add(t); return textures.size() - 1;
    }

    void addFallbackTexture() {
      byte[] px = { (byte) 255, (byte) 255, (byte) 255, (byte) 255 };
      var t = new LinkedHashMap<String, Object>(); t.put("name", "missing.png"); t.put("path", "missing.png"); t.put("id", id("texture:missing")); t.put("uuid", id("texture:missing")); t.put("source", "data:image/png;base64," + Base64.getEncoder().encodeToString(png(px, 1, 1))); t.put("internal", true); t.put("saved", true); t.put("visible", true); t.put("particle", false); t.put("folder", ""); t.put("namespace", ""); t.put("render_mode", "normal"); t.put("uv_width", 1); t.put("uv_height", 1); textures.add(t); textureWidth = textureHeight = 1;
      diagnostic("missing_texture", "No UV texture was present; inserted missing.png");
    }

    void addAnimations(WireNode player) {
      for (var entry : player.<Integer, WireNode>map("animations").entrySet()) {
        String geometryTarget = animationGeometryTarget(entry.getKey());
        addAnimationWrapper(entry.getValue(), geometryTarget, "player:animation-" + entry.getKey());
      }
    }

    String animationGeometryTarget(int type) {
      return switch (type) {
        case 1 -> "main";
        case 2, 11 -> "arm";
        default -> "main";
      };
    }

    void addAnimationWrapper(WireNode wrapped, String boneTarget, String animationTarget) {
      if (wrapped == null || wrapped.fields.isEmpty()) return;
      WireNode file = wrapped.has("value") ? wrapped.n("value") : wrapped;
      for (WireNode animation : file.<WireNode>list("animations")) addAnimation(animation, boneTarget, animationTarget);
    }

    void addAnimation(WireNode animation, String boneTarget, String animationTarget) {
      String animationName = animation.s("name");
      var out = new LinkedHashMap<String, Object>(); out.put("name", animationTarget + "/" + animationName); out.put("uuid", id("animation:" + animationTarget + ":" + animationName)); out.put("loop", loop(animation.i("loop"))); out.put("length", safeTime(animation.f("length"), animationTarget + "/" + animationName + "/length")); out.put("override", animation.b("override_previous"));
      var animators = new LinkedHashMap<String, Object>();
      for (WireNode b : animation.<WireNode>list("bones")) {
        String key = findBoneId(boneTarget, b.s("name"));
        if (key == null) { diagnostic("animation_bone_missing", animationTarget + " animation references " + b.s("name")); continue; }
        var animator = new LinkedHashMap<String, Object>(); animator.put("name", b.s("name")); animator.put("type", "bone"); animator.put("keyframes", keyframes(b, animationTarget, b.s("name"))); animators.put(key, animator);
      }
      out.put("animators", animators);
      if (animation.has("start_delay")) out.put("ysm_start_delay", molangValue(animation.n("start_delay")));
      if (animation.has("loop_delay")) out.put("ysm_loop_delay", molangValue(animation.n("loop_delay")));
      if (animation.has("weight")) out.put("ysm_weight", molangValue(animation.n("weight")));
      out.put("ysm_loop_value", animation.i("loop"));
      animations.add(out);
      @SuppressWarnings("unchecked") List<Object> source = (List<Object>) ysm.computeIfAbsent("animations", ignored -> new ArrayList<>());
      source.add(map("target", animationTarget, "bone_target", boneTarget, "name", animationName, "data", wireValue(animation)));
    }

    String findBoneId(String target, String name) {
      Map<String, String> scoped = boneIdsByTarget.get(target);
      if (duplicateBoneNames.contains(target + "/" + name)) return null;
      if (scoped != null && !duplicateBoneNames.contains(target + "/" + name) && scoped.containsKey(name)) return scoped.get(name);
      String match = null;
      for (var e : boneIdsByTarget.values()) {
        if (e.containsKey(name)) {
          if (match != null) return null;
          match = e.get(name);
        }
      }
      return match;
    }

    List<Object> keyframes(WireNode bone, String animationTarget, String boneName) {
      var result = new ArrayList<Object>();
      for (String channel : List.of("rotation", "position", "scale")) for (WireNode k : bone.<WireNode>list(channel)) {
        var x = new LinkedHashMap<String, Object>(); x.put("channel", channel); x.put("time", safeTime(k.f("tick"), animationTarget + "/" + boneName + "/" + channel)); x.put("interpolation", interpolation(k.i("easing"))); x.put("data_points", List.of(vectorMolang(k.<WireNode>list("post").isEmpty() ? k.<WireNode>list("pre") : k.<WireNode>list("post")))); x.put("uuid", id("keyframe:" + animationTarget + ":" + boneName + ":" + channel + ":" + k.f("tick"))); x.put("ysm_tick", safeNumber(k.f("tick"), animationTarget + "/" + boneName + "/" + channel)); x.put("ysm_easing", k.i("easing")); x.put("ysm_pre", vectorMolang(k.<WireNode>list("pre"))); if (!k.<WireNode>list("post").isEmpty()) x.put("ysm_post", vectorMolang(k.<WireNode>list("post"))); result.add(x);
      }
      return result;
    }

    Object vectorMolang(List<WireNode> v) {
      var out = new LinkedHashMap<String, Object>(); String[] keys = {"x", "y", "z"};
      for (int i = 0; i < Math.min(3, v.size()); i++) { WireNode n = v.get(i); out.put(keys[i], n.i("type") == 1 ? safeNumber(n.f("number"), "Molang " + keys[i]) : n.i("type") == 2 ? n.s("source") : 0); }
      return out;
    }

    Object molangValue(WireNode n) {
      return switch (n.i("type")) {
        case 1 -> safeNumber(n.f("number"), "Molang value");
        case 2 -> n.s("source");
        default -> 0;
      };
    }

    double safeTime(float ticks, String context) {
      return safeNumber(ticks, context) / 20.0;
    }

    float safeNumber(float value, String context) {
      if (Float.isFinite(value)) return value;
      diagnostic("non_finite_number", context + " contained a non-finite float and was exported as 0; original bits are retained in ysm sidecar");
      return 0f;
    }

    List<Float> safeVector(List<Float> values, String context) {
      if (values.size() != 3) return List.of(0f, 0f, 0f);
      return List.of(safeNumber(values.get(0), context + "/x"), safeNumber(values.get(1), context + "/y"), safeNumber(values.get(2), context + "/z"));
    }

    String loop(int value) { return value == 1 ? "loop" : value == 2 ? "hold" : "once"; }
    String interpolation(int value) { return value == 1 ? "step" : value == 2 ? "catmullrom" : "linear"; }

    void addControllers(WireNode player, String target) {
      for (var e : player.<String, WireNode>map("controllers").entrySet()) addControllerWrapper(e.getValue(), target + ":" + e.getKey());
    }

    void addControllerWrapper(WireNode wrapped, String target) {
      if (wrapped == null || wrapped.fields.isEmpty()) return;
      WireNode file = wrapped.has("value") ? wrapped.n("value") : wrapped;
      @SuppressWarnings("unchecked") List<Object> cs = (List<Object>) ysm.computeIfAbsent("controllers", ignored -> new ArrayList<>());
      cs.add(map("target", target, "data", wireValue(file)));
      diagnostic("controller_sidecar", target + " retained in ysm.controllers because Blockbench has no equivalent controller graph");
    }

    void collectDiagnostics(WireNode model) {
      if (model.has("common")) {
        for (var e : model.n("common").<String, WireNode>map("functions").entrySet()) diagnostic("function_sidecar", "function " + e.getKey() + " retained in ysm.functions");
        ysm.put("functions", wireValue(model.n("common").map("functions")));
      }
      if (model.has("order_info") && !model.s("order_info").isBlank()) ysm.put("order_info", model.s("order_info"));
    }

    void collectHistoricalSidecar(WireNode model) {
      WireNode info = model.n("info");
      if (info.has("metadata")) ysm.put("metadata", wireValue(info.n("metadata")));
      if (info.has("properties")) ysm.put("properties", wireValue(info.n("properties")));
      if (info.has("avatars")) ysm.put("avatars", mediaSummary(info.<String, WireNode>map("avatars")));
      if (info.has("gui")) ysm.put("gui", mediaSummary(info.<String, WireNode>map("gui")));
      if (model.has("export")) ysm.put("export", wireValue(model.n("export")));
      if (model.has("common")) {
        ysm.put("languages", wireValue(model.n("common").map("languages")));
        ysm.put("sounds", mediaSummary(model.n("common").<String, WireNode>map("sounds")));
      }
      collectVariants(model.<WireNode>list("projectiles"), "projectile");
      collectVariants(model.<WireNode>list("vehicles"), "vehicle");
      if (wire.length <= MAX_EMBEDDED_WIRE) ysm.put("source_wire_base64", Base64.getEncoder().encodeToString(wire));
      else diagnostic("source_wire_omitted", "The decoded wire exceeded the embedded source limit; source hash remains in ysm.model_id and source_wire_sha256");
      ysm.put("source_wire_sha256", wireSeed);
    }

    void collectVariants(List<WireNode> variants, String kind) {
      if (variants.isEmpty()) return;
      @SuppressWarnings("unchecked") List<Object> out = (List<Object>) ysm.computeIfAbsent("variants", ignored -> new ArrayList<>());
      int ordinal = 1;
      for (WireNode variant : variants) {
        out.add(map("kind", kind, "ordinal", ordinal++, "source_key", variant.s("source_key"), "match", variant.list("match")));
      }
    }

    Map<String, Object> mediaSummary(Map<String, WireNode> media) {
      var out = new LinkedHashMap<String, Object>();
      for (var e : media.entrySet()) {
        WireNode n = e.getValue().has("value") ? e.getValue().n("value") : e.getValue();
        out.put(e.getKey(), map("encoding", n.s("encoding"), "width", n.i("width"), "height", n.i("height"), "role", n.s("role")));
      }
      return out;
    }

    Object wireValue(Object value) {
      if (value == null) return null;
      if (value instanceof WireNode n) { var out = new LinkedHashMap<String, Object>(); for (var e : n.fields.entrySet()) out.put(e.getKey(), wireValue(e.getValue())); return out; }
      if (value instanceof Map<?, ?> m) { var out = new LinkedHashMap<String, Object>(); for (var e : m.entrySet()) out.put(String.valueOf(e.getKey()), wireValue(e.getValue())); return out; }
      if (value instanceof List<?> l) { var out = new ArrayList<Object>(); for (Object x : l) out.add(wireValue(x)); return out; }
      if (value instanceof byte[] b) return HexFormat.of().formatHex(b);
      if (value instanceof Float f && !Float.isFinite(f)) return map("$float32", String.format(Locale.ROOT, "%08x", Float.floatToRawIntBits(f)));
      if (value instanceof Double d && !Double.isFinite(d)) return map("$float64", String.format(Locale.ROOT, "%016x", Double.doubleToRawLongBits(d)));
      return value;
    }

    void diagnostic(String kind, String message) { diagnostics.add(map("kind", kind, "message", message)); }

    String id(String seed) {
      try { byte[] digest = MessageDigest.getInstance("SHA-1").digest((wireSeed + ":" + seed).getBytes(StandardCharsets.UTF_8)); digest[6] = (byte) ((digest[6] & 0x0f) | 0x50); digest[8] = (byte) ((digest[8] & 0x3f) | 0x80); return UUID.nameUUIDFromBytes(digest).toString(); }
      catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
  }

  private static List<Object> list(Object... values) { return Arrays.asList(values); }
  private static List<Float> vector(List<Float> values, int n) { return values.size() == n ? values : List.of(0f, 0f, 0f); }
  private static Map<String, Object> map(Object... values) { var out = new LinkedHashMap<String, Object>(); for (int i = 0; i + 1 < values.length; i += 2) out.put(String.valueOf(values[i]), values[i + 1]); return out; }

  private static byte[] png(byte[] rgba, int width, int height) {
    ByteArrayOutputStream out = new ByteArrayOutputStream(); out.writeBytes(new byte[]{(byte)137,80,78,71,13,10,26,10});
    ByteBuffer ihdr = ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN).putInt(width).putInt(height).put((byte)8).put((byte)6).put((byte)0).put((byte)0).put((byte)0); chunk(out, "IHDR", ihdr.array());
    byte[] scan = new byte[height * (width * 4 + 1)]; for (int y = 0; y < height; y++) { int src = y * width * 4, dst = y * (width * 4 + 1); scan[dst] = 0; System.arraycopy(rgba, src, scan, dst + 1, width * 4); }
    Deflater deflater = new Deflater(6); deflater.setInput(scan); deflater.finish(); byte[] compressed = new byte[scan.length + 64]; int n = deflater.deflate(compressed); deflater.end(); chunk(out, "IDAT", Arrays.copyOf(compressed, n)); chunk(out, "IEND", new byte[0]); return out.toByteArray();
  }

  private static void chunk(ByteArrayOutputStream out, String name, byte[] data) { byte[] type = name.getBytes(StandardCharsets.US_ASCII); ByteBuffer length = ByteBuffer.allocate(4).putInt(data.length); out.writeBytes(length.array()); out.writeBytes(type); out.writeBytes(data); CRC32 crc = new CRC32(); crc.update(type); crc.update(data); out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array()); }

  private static final class Json {
    static void append(StringBuilder out, Object value) {
      if (value == null) out.append("null"); else if (value instanceof String s) string(out, s); else if (value instanceof Float f && !Float.isFinite(f)) out.append("null"); else if (value instanceof Double d && !Double.isFinite(d)) out.append("null"); else if (value instanceof Number || value instanceof Boolean) out.append(value); else if (value instanceof Map<?, ?> m) { out.append('{'); boolean first = true; for (var e : m.entrySet()) { if (!first) out.append(','); first = false; string(out, String.valueOf(e.getKey())); out.append(':'); append(out, e.getValue()); } out.append('}'); } else if (value instanceof Iterable<?> i) { out.append('['); boolean first = true; for (Object x : i) { if (!first) out.append(','); first = false; append(out, x); } out.append(']'); } else throw new IllegalArgumentException("Unsupported JSON value " + value.getClass());
    }
    static void string(StringBuilder out, String s) { out.append('"'); for (int i = 0; i < s.length(); i++) { char c = s.charAt(i); if (c == '"' || c == '\\') out.append('\\').append(c); else if (c < 32 || Character.isSurrogate(c)) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c)); else out.append(c); } out.append('"'); }
  }
}
