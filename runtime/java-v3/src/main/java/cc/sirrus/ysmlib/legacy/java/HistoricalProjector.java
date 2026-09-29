package cc.sirrus.ysmlib.legacy.java;

import cc.sirrus.ysmlib.legacy.LegacyImportProvider.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Deterministic projection to the public mixel schema. Source hashes are not target identities. */
final class HistoricalProjector {
  private final List<Payload> images = new ArrayList<>(), named = new ArrayList<>();
  private int nextImage;
  private static final Comparator<String> UTF8 =
      (a, b) ->
          Arrays.compareUnsigned(
              a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));

  private static <V> Map<String, V> sorted(Map<String, V> input) {
    var result = new TreeMap<String, V>(UTF8);
    result.putAll(input);
    return result;
  }

  private static SchemaProto p() {
    return new SchemaProto();
  }

  private static SchemaProto program(String source) {
    return p().s(3, source);
  }

  private static String statements(List<String> source) {
    var out = new StringBuilder();
    for (String s : source) {
      out.append(s);
      if (!s.stripTrailing().endsWith(";")) out.append(';');
      out.append('\n');
    }
    return out.toString();
  }

  private static void pairs(SchemaProto p, int field, Map<String, String> map) {
    sorted(map).forEach((k, v) -> p.m(field, p().s(1, k).s(2, v)));
  }

  private static int enumValue(int value) throws IOException {
    if (value < 0) throw new IOException("Enum is not target-representable");
    return value;
  }

  private static Payload payload(String kind, int id, String name, byte[] bytes) {
    return new Payload(kind, "DIRECT", id, name, 0, 0, ByteBuffer.wrap(bytes));
  }

  private static SchemaProto molang(WireNode n) throws IOException {
    return switch (n.i("type")) {
      case 1 -> p().f(1, n.f("number"));
      case 2 -> p().m(2, program(n.s("source")));
      default -> throw new IOException("Missing historical Molang variant");
    };
  }

  private static SchemaProto keyframe(WireNode n) throws IOException {
    var p = p().f(1, n.f("tick")).u(2, enumValue(n.i("easing")));
    for (var v : n.<WireNode>list("pre")) p.m(3, molang(v));
    for (var v : n.<WireNode>list("post")) p.m(4, molang(v));
    return p;
  }

  private static SchemaProto animationFile(WireNode n) throws IOException {
    var file = p();
    for (var a : n.<WireNode>list("animations")) {
      var out = p().s(1, a.s("name")).f(2, a.f("length")).u(3, enumValue(a.i("loop")));
      if (a.has("weight")) out.m(4, molang(a.n("weight")));
      for (var b : a.<WireNode>list("bones")) {
        var bone = p().s(1, b.s("name"));
        int field = 2;
        for (String channel : List.of("rotation", "position", "scale")) {
          for (var f : b.<WireNode>list(channel)) bone.m(field, keyframe(f));
          field++;
        }
        out.m(5, bone);
      }
      for (var i : a.<WireNode>list("instructions"))
        out.m(6, p().m(1, program(statements(i.list("data")))).f(2, i.f("tick")));
      for (var s : a.<WireNode>list("sounds")) out.m(7, p().s(1, s.s("data")).f(2, s.f("tick")));
      file.m(1, out);
    }
    return file;
  }

  private static SchemaProto controllers(WireNode n) {
    var file = p();
    for (var ce : sorted(n.<String, WireNode>map("controllers")).entrySet()) {
      var c = ce.getValue();
      var out = p().s(1, ce.getKey());
      if (!c.s("initial").isEmpty()) out.s(2, c.s("initial"));
      for (var se : sorted(c.<String, WireNode>map("states")).entrySet()) {
        var s = se.getValue();
        var state = p().s(1, se.getKey());
        for (var a : s.<WireNode>list("animations"))
          state.m(
              2,
              p().s(1, a.s("name"))
                  .m(2, program(a.s("condition").isEmpty() ? "true" : a.s("condition"))));
        for (var t : s.<WireNode>list("transitions"))
          state.m(3, p().s(1, t.s("name")).m(2, program(t.s("condition"))));
        if (!s.list("entry").isEmpty()) state.m(4, program(statements(s.list("entry"))));
        if (!s.list("exit").isEmpty()) state.m(5, program(statements(s.list("exit"))));
        if (s.has("length") || !s.map("points").isEmpty()) {
          var blend = p();
          if (s.has("length")) blend.f(1, s.f("length"));
          s.<Float, Float>map("points").forEach((k, v) -> blend.m(2, p().f(1, k).f(2, v)));
          state.m(6, blend);
        }
        for (String sound : s.<String>list("sounds")) state.s(8, sound);
        out.m(3, state);
      }
      file.m(1, out);
    }
    return file;
  }

  private record Geo(SchemaProto proto, SchemaProto stats) {}

  private static Geo geo(WireNode n) {
    var result = p();
    var cubes = p();
    int boneCount = 0, cubeCount = 0, faces = 0;
    for (var bone : n.<WireNode>list("bones")) {
      boneCount++;
      var b = p().s(1, bone.s("name"));
      if (!bone.s("parent").isEmpty()) b.s(2, bone.s("parent"));
      b.floats(3, bone.list("pivot"))
          .floats(4, bone.list("rotation"))
          .u(6, bone.list("cubes").size());
      result.m(1, b);
      for (var cube : bone.<WireNode>list("cubes")) {
        cubeCount++;
        var positions = new LinkedHashMap<List<Float>, Integer>();
        var uvs = new LinkedHashMap<List<Float>, Integer>();
        var indices = new ArrayList<Integer>();
        var uvIndices = new ArrayList<Integer>();
        var normals = new ArrayList<Float>();
        for (var q : cube.<WireNode>list("quads")) {
          faces++;
          normals.addAll(q.list("normal"));
          for (var v : q.<WireNode>list("vertices")) {
            var pos = normalizeZero(v.<Float>list("position"));
            var uv = normalizeZero(v.<Float>list("uv"));
            indices.add(positions.computeIfAbsent(pos, k -> positions.size()));
            uvIndices.add(uvs.computeIfAbsent(uv, k -> uvs.size()));
          }
        }
        var flatPos = new ArrayList<Float>();
        positions.keySet().forEach(flatPos::addAll);
        var flatUv = new ArrayList<Float>();
        uvs.keySet().forEach(flatUv::addAll);
        cubes.m(
            1,
            p().u(1, cube.list("quads").size())
                .floats(2, flatPos)
                .ints(3, indices)
                .floats(4, flatUv)
                .ints(5, uvIndices)
                .floats(6, normals));
      }
    }
    return new Geo(
        result
            .m(2, p().f(1, n.n("properties").f("height")).f(2, n.n("properties").f("width")))
            .m(3, cubes),
        p().u(1, boneCount).u(2, cubeCount).u(3, faces));
  }

  private static List<Float> normalizeZero(List<Float> data) {
    return data.stream().map(v -> v == 0 ? 0f : v).toList();
  }

  private static String geoName(int type) throws IOException {
    return switch (type) {
      case 1 -> "main";
      case 2 -> "arm";
      default -> throw new IOException("Unknown player geometry type");
    };
  }

  private static String animationName(int type) throws IOException {
    return switch (type) {
      case 1 -> "main";
      case 2 -> "arm";
      case 3 -> "extra";
      case 4 -> "tac";
      case 6 -> "carryon";
      case 7 -> "parcool";
      case 8 -> "swem";
      case 9 -> "slashblade";
      case 10 -> "tlm";
      case 11 -> "fp_arm";
      case 12 -> "immersive_melodies";
      case 13 -> "irons_spell_books";
      default -> throw new IOException("Unknown player animation type");
    };
  }

  private SchemaProto image(WireNode image) {
    int id = nextImage++;
    images.add(
        new Payload(
            "BLOB_IMAGE",
            image.s("encoding"),
            id,
            "",
            image.i("width"),
            image.i("height"),
            ByteBuffer.wrap(image.bytes("bytes"))));
    return p().u(1, id)
        .s(2, image.s("encoding"))
        .u(3, image.i("width"))
        .u(4, image.i("height"))
        .u(5, 1);
  }

  private SchemaProto texture(WireNode n) throws IOException {
    var out = p().m(1, image(n.n("uv").n("value")));
    var pbr = n.<Integer, WireNode>map("pbr");
    for (int type : pbr.keySet())
      if (type != 1 && type != 2) throw new IOException("Unknown PBR type");
    if (pbr.containsKey(1)) out.m(2, image(pbr.get(1).n("value")));
    if (pbr.containsKey(2)) out.m(3, image(pbr.get(2).n("value")));
    return out;
  }

  private static SchemaProto modelSettings(WireNode p) {
    return p().f(1, p.f("height_scale"))
        .f(2, p.f("width_scale"))
        .b(3, p.b("layers"))
        .b(4, p.b("culling"))
        .b(5, p.b("no_lighting"))
        .b(6, p.b("merge"));
  }

  private record Target(SchemaProto manifest, Payload payload) {}

  private Target target(WireNode source, WireNode props, String name, int kind, int blob)
      throws IOException {
    var manifest = p().s(1, name).u(2, kind).u(4, blob).m(6, modelSettings(props));
    var data = p();
    var stats = p().u(1, 0).u(2, 0).u(3, 0);
    if (kind == 1) {
      var geometries = new TreeMap<String, WireNode>(UTF8);
      for (var e : source.<Integer, WireNode>map("geos").entrySet())
        geometries.put(geoName(e.getKey()), e.getValue().n("value"));
      for (var e : geometries.entrySet()) {
        var g = geo(e.getValue());
        data.m(1, p().s(1, e.getKey()).m(2, g.proto));
        if (e.getKey().equals("main")) stats = g.stats;
      }
      var animations = new TreeMap<String, WireNode>(UTF8);
      for (var e : source.<Integer, WireNode>map("animations").entrySet())
        animations.put(animationName(e.getKey()), e.getValue().n("value"));
      for (var e : animations.entrySet())
        data.m(2, p().s(1, e.getKey()).m(2, animationFile(e.getValue())));
      for (var e : sorted(source.<String, WireNode>map("controllers")).entrySet())
        data.m(3, p().s(1, e.getKey()).m(2, controllers(e.getValue().n("value"))));
      var textures = sorted(source.<String, WireNode>map("textures"));
      if (textures.isEmpty()) throw new IOException("Player target has no texture");
      for (var e : textures.entrySet())
        manifest.m(5, p().s(1, e.getKey()).m(2, texture(e.getValue())));
    } else {
      for (String match : source.<String>list("match")) manifest.s(3, match);
      if (source.has("geo")) {
        var g = geo(source.n("geo").n("value"));
        data.m(1, p().s(1, "main").m(2, g.proto));
        stats = g.stats;
      }
      if (source.has("animation"))
        data.m(2, p().s(1, "main").m(2, animationFile(source.n("animation").n("value"))));
      if (source.has("controller"))
        data.m(3, p().s(1, "main").m(2, controllers(source.n("controller").n("value"))));
      if (source.has("texture"))
        manifest.m(5, p().s(1, "default").m(2, texture(source.n("texture"))));
    }
    return new Target(manifest.m(7, stats), payload("MODEL_DATA", blob, name, data.bytes()));
  }

  private static SchemaProto form(WireNode n) {
    boolean inert = n.s("value").isBlank();
    var out =
        p().s(1, n.s("type"))
            .s(2, n.s("title"))
            .s(3, n.s("description"))
            .m(4, program(inert ? "0" : n.s("value")))
            .f(5, n.f("step"))
            .f(6, n.f("min"))
            .f(7, n.f("max"))
            .m(9, program(inert ? "return;" : n.s("value") + "=t.value"));
    sorted(n.<String, String>map("labels"))
        .forEach((k, v) -> out.m(8, p().s(1, k).m(2, program(v))));
    return out;
  }

  private SchemaProto info(WireNode root) throws IOException {
    var n = root.n("info");
    var props = n.n("properties");
    var out = p();
    for (var e : sorted(root.n("common").<String, WireNode>map("languages")).entrySet()) {
      var lang = p().s(1, e.getKey());
      @SuppressWarnings("unchecked")
      var entries = (Map<String, String>) e.getValue().fields.get("value");
      pairs(lang, 2, entries);
      out.m(1, lang);
    }
    var settings = p();
    pairs(settings, 1, props.map("extra"));
    for (var button : props.<WireNode>list("buttons")) {
      var b = p().s(1, button.s("id")).s(2, button.s("name")).s(3, button.s("sound"));
      for (var form : button.<WireNode>list("forms")) b.m(4, form(form));
      settings.m(2, b);
    }
    for (var c : props.<WireNode>list("classify")) {
      var cls = p().s(1, c.s("id"));
      pairs(cls, 2, c.map("extra"));
      settings.m(3, cls);
    }
    if (!props.s("default_texture").isEmpty()) settings.s(4, props.s("default_texture"));
    if (!props.s("preview").isEmpty()) settings.s(5, props.s("preview"));
    settings.b(6, props.b("no_rotation"));
    var gui = n.<String, WireNode>map("gui");
    if (gui.containsKey("gui_foreground")) settings.m(7, image(gui.get("gui_foreground")));
    if (gui.containsKey("gui_background")) settings.m(8, image(gui.get("gui_background")));
    out.m(3, settings);
    var avatars = n.<String, WireNode>map("avatars");
    var used = new HashSet<String>();
    if (n.has("metadata")) {
      var meta = n.n("metadata");
      var m = p().s(1, meta.s("name"));
      if (!meta.s("tips").isEmpty()) m.s(2, meta.s("tips"));
      var license = p().s(1, meta.s("license"));
      if (!meta.s("description").isEmpty()) license.s(2, meta.s("description"));
      m.m(3, license);
      pairs(m, 5, meta.map("links"));
      for (var a : meta.<WireNode>list("authors")) {
        var author = p().s(1, a.s("name")).s(2, a.s("role"));
        pairs(author, 3, a.map("contact"));
        if (!a.s("comment").isEmpty()) author.s(4, a.s("comment"));
        if (avatars.containsKey(a.s("name"))) {
          author.m(5, image(avatars.get(a.s("name"))));
          used.add(a.s("name"));
        }
        m.m(4, author);
      }
      out.m(4, m);
    }
    if (used.size() != avatars.size()) throw new IOException("Historical avatar has no author");
    out.m(
        2,
        p().bytes(1, root.bytes("modelId"))
            .b(2, props.b("free"))
            .s(3, Integer.toUnsignedString(n.i("origin"))));
    if (root.has("export"))
      out.m(5, p().u(1, root.n("export").l("timestamp")).s(3, root.n("export").s("extra")));
    for (String key : List.of("thumb-button", "thumb-icon"))
      if (gui.containsKey(key)) {
        var img = gui.get(key);
        named.add(
            new Payload(
                "NAMED_IMAGE",
                img.s("encoding"),
                0,
                key,
                img.i("width"),
                img.i("height"),
                ByteBuffer.wrap(img.bytes("bytes"))));
        out.u(key.equals("thumb-button") ? 6 : 7, 1);
      }
    return out;
  }

  Bundle project(WireNode model, long sourceSize) throws IOException {
    var projectiles = new ArrayList<>(model.<WireNode>list("projectiles"));
    var vehicles = new ArrayList<>(model.<WireNode>list("vehicles"));
    if (model.i("version") <= 26) {
      projectiles.sort(Comparator.comparing(n -> n.s("source_key"), UTF8));
      vehicles.sort(Comparator.comparing(n -> n.s("source_key"), UTF8));
    }
    int count = (model.has("player") ? 1 : 0) + projectiles.size() + vehicles.size();
    nextImage = 2 + count;
    var targets = new ArrayList<Target>();
    var props = model.n("info").n("properties");
    int blob = 2;
    if (model.has("player")) targets.add(target(model.n("player"), props, "player", 1, blob++));
    int ordinal = 1;
    for (var n : projectiles) targets.add(target(n, props, "projectile-" + ordinal++, 3, blob++));
    ordinal = 1;
    for (var n : vehicles) targets.add(target(n, props, "vehicle-" + ordinal++, 4, blob++));
    var manifest = p();
    for (var t : targets) manifest.m(1, t.manifest);
    var common = p().u(2, 1);
    var sounds = sorted(model.n("common").<String, WireNode>map("sounds"));
    int stream = 1;
    var soundPayloads = new ArrayList<Payload>();
    for (var e : sounds.entrySet()) {
      var s = e.getValue().n("value");
      common.m(
          1,
          p().s(1, e.getKey())
              .s(2, s.s("encoding"))
              .u(3, s.i("channels"))
              .u(4, s.i("rate"))
              .u(5, s.l("samples"))
              .u(6, stream));
      soundPayloads.add(
          new Payload(
              "SOUND_STREAM",
              s.s("encoding"),
              stream++,
              e.getKey(),
              0,
              0,
              ByteBuffer.wrap(s.bytes("bytes"))));
    }
    manifest.m(2, common).m(3, info(model));
    var strings = p();
    for (var e : sorted(model.n("common").<String, WireNode>map("functions")).entrySet())
      strings.m(1, p().s(1, e.getKey()).m(2, program(e.getValue().s("value"))));
    var payloads = new ArrayList<Payload>();
    payloads.add(payload("MANIFEST", 0, "", manifest.bytes()));
    payloads.add(payload("STRING_DATA", 1, "", strings.bytes()));
    for (var t : targets) payloads.add(t.payload);
    payloads.addAll(images);
    payloads.addAll(named);
    payloads.addAll(soundPayloads);
    if (payloads.size() > 32766) throw new IOException("Historical payload count exceeds limit");
    return new Bundle(
        model.i("version"), sourceSize, ByteBuffer.wrap(model.bytes("modelId")), payloads);
  }
}
