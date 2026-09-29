package cc.sirrus.ysmlib.legacy.java;

import cc.sirrus.ysmlib.audio.SupportedAudioProbe;
import cc.sirrus.ysmlib.image.ImageProvider;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;

/** Bounded decoder for all historical inner versions 1 through 32. */
final class HistoricalWireReader {
  @FunctionalInterface
  private interface Reader<T> {
    T read() throws IOException;
  }

  private final ByteBuffer input;
  private final ImageProvider images;
  private final boolean archival;
  private int version, entries;

  HistoricalWireReader(byte[] wire, ImageProvider images) {
    this(wire, images, false);
  }

  HistoricalWireReader(byte[] wire, ImageProvider images, boolean archival) {
    input = ByteBuffer.wrap(wire).order(ByteOrder.LITTLE_ENDIAN);
    this.images = images;
    this.archival = archival;
  }

  WireNode read() throws IOException {
    try {
      version = input.getInt();
      check(version >= 1 && version <= 32, "Unsupported inner version");
      var result = version < 19 ? legacy() : current();
      String hash = archival && version < 19 ? result.s("identity") : result.n("info").s("hash");
      check(hash.matches("[0-9a-fA-F]{32}"), "Invalid historical model identity");
      byte[] id = new byte[32];
      System.arraycopy(HexFormat.of().parseHex(hash), 0, id, 0, 16);
      result.put("version", version).put("modelId", id);
      check(!input.hasRemaining(), "Trailing historical wire bytes");
      return result;
    } catch (BufferUnderflowException | IllegalArgumentException e) {
      throw new IOException("Invalid historical wire at " + input.position(), e);
    }
  }

  private static void check(boolean ok, String message) throws IOException {
    if (!ok) throw new IOException(message);
  }

  private long var(int max) throws IOException {
    long value = 0;
    for (int i = 0; i < max; i++) {
      int b = input.get() & 255;
      check(i < max - 1 || (b & (max == 5 ? 0xf0 : 0xfe)) == 0, "Varint overflow");
      value |= (long) (b & 127) << (i * 7);
      if ((b & 128) == 0) {
        check(i == 0 || b != 0, "Noncanonical varint");
        return value;
      }
    }
    throw new IOException("Unterminated varint");
  }

  private int u() throws IOException {
    return (int) var(5);
  }

  private int enumRange(int min, int max) throws IOException {
    int v = u();
    check(v >= min && v <= max, "Invalid historical enum");
    return v;
  }

  private boolean bool() throws IOException {
    int b = input.get() & 255;
    check(b <= 1, "Invalid boolean");
    return b != 0;
  }

  private float f() {
    return input.getFloat();
  }

  private byte[] bytes(int limit) throws IOException {
    long size = var(5);
    check(size <= limit && size <= input.remaining(), "Truncated/oversized historical field");
    byte[] data = new byte[(int) size];
    input.get(data);
    return data;
  }

  private String s() throws IOException {
    return StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes(16 * 1024 * 1024)))
        .toString();
  }

  private String hash() throws IOException {
    return new String(bytes(16 * 1024 * 1024), StandardCharsets.ISO_8859_1);
  }

  private int count(int max) throws IOException {
    long n = var(5);
    check(n <= max && n <= 1_048_576 - entries, "Historical collection budget exceeded");
    entries += (int) n;
    return (int) n;
  }

  private <T> List<T> list(Reader<T> read) throws IOException {
    return list(read, 1_048_576);
  }

  private <T> List<T> list(Reader<T> read, int max) throws IOException {
    int n = count(max);
    var result = new ArrayList<T>();
    for (int i = 0; i < n; i++) result.add(read.read());
    return result;
  }

  private <K, V> Map<K, V> map(Reader<K> key, Reader<V> value) throws IOException {
    int n = count(1_048_576);
    var result = new LinkedHashMap<K, V>();
    for (int i = 0; i < n; i++) {
      K k = key.read();
      check(!result.containsKey(k), "Duplicate historical map key");
      result.put(k, value.read());
    }
    return result;
  }

  private <T> T optional(Reader<T> read) throws IOException {
    int tag = enumRange(0, 1);
    return tag == 0 ? null : read.read();
  }

  private List<String> strings() throws IOException {
    return list(this::s);
  }

  private Map<String, String> stringMap() throws IOException {
    return map(this::s, this::s);
  }

  private WireNode wrap(Reader<?> value) throws IOException {
    return new WireNode().put("hash", hash()).put("value", value.read());
  }

  private List<Float> vec() {
    return List.of(f(), f(), f());
  }

  private WireNode quad() throws IOException {
    var n = new WireNode().put("normal", vec());
    var vertices = new ArrayList<WireNode>();
    for (int i = 0; i < 4; i++)
      vertices.add(new WireNode().put("position", vec()).put("uv", List.of(f(), f())));
    return n.put("vertices", vertices);
  }

  private WireNode cube() throws IOException {
    var n = new WireNode().put("quads", list(this::quad, 6));
    n.put("zero_size", List.of(bool(), bool(), bool()));
    return n;
  }

  private WireNode bone() throws IOException {
    var n = new WireNode().put("parent", s());
    n.put(
        "cubes",
        version == 5
            ? list(this::quad).stream().map(q -> new WireNode().put("quads", List.of(q))).toList()
            : list(this::cube));
    n.put("name", s());
    n.put("dont_render", bool())
        .put("hidden", bool())
        .put("cubes_hidden", bool())
        .put("hide_children", bool())
        .put("reset", bool());
    return n.put("pivot", vec()).put("rotation", vec());
  }

  private WireNode extra() throws IOException {
    return new WireNode()
        .put("name", s())
        .put("tips", s())
        .put("extra", strings())
        .put("authors", strings())
        .put("license", s())
        .put("free", bool());
  }

  private WireNode geo() throws IOException {
    var n = new WireNode().put("bones", list(this::bone, 65536));
    var props = new WireNode();
    props.put("identifier", s());
    props.put("height", f()).put("width", f());
    props
        .put("visible_bounds_height", f())
        .put("visible_bounds_width", f())
        .put("visible_bounds_offset", list(this::f));
    props.put("height_scale", f()).put("width_scale", f()).put("extra_info", optional(this::extra));
    props
        .put("variables", stringMap())
        .put("initialize", strings())
        .put("pre_animation", strings());
    n.put("properties", props);
    if (version == 5) n.put("global_cube_count", u());
    return n;
  }

  private WireNode molang() throws IOException {
    int type = enumRange(0, 2);
    var n = new WireNode().put("type", type);
    if (type == 1) n.put("number", f());
    if (type == 2) n.put("source", s());
    return n;
  }

  private List<WireNode> molangVec() throws IOException {
    return List.of(molang(), molang(), molang());
  }

  private WireNode keyframe() throws IOException {
    return new WireNode()
        .put("tick", f())
        .put("easing", u())
        .put("pre", molangVec())
        .put("post", optional(this::molangVec));
  }

  private WireNode boneAnimation() throws IOException {
    return new WireNode()
        .put("name", s())
        .put("rotation", list(this::keyframe))
        .put("position", list(this::keyframe))
        .put("scale", list(this::keyframe));
  }

  private WireNode animation() throws IOException {
    var n = new WireNode().put("name", s()).put("length", f()).put("loop", u());
    if (version >= 10) {
      n.put("start_delay", optional(this::molang));
      n.put("loop_delay", optional(this::molang));
      n.put("weight", optional(this::molang));
      n.put("override_previous", optional(this::bool));
    }
    n.put("bones", list(this::boneAnimation))
        .put("instructions", list(() -> new WireNode().put("data", strings()).put("tick", f())));
    if (version >= 11)
      n.put("sounds", list(() -> new WireNode().put("data", s()).put("tick", f())));
    return n;
  }

  private WireNode animations() throws IOException {
    return new WireNode().put("animations", list(this::animation));
  }

  private WireNode controllerState() throws IOException {
    var n =
        new WireNode()
            .put("animations", list(() -> new WireNode().put("name", s()).put("condition", s())))
            .put("transitions", list(() -> new WireNode().put("name", s()).put("condition", s())))
            .put("entry", strings())
            .put("exit", strings());
    Float length = version < 15 ? f() : optional(this::f);
    n.put("length", length);
    if (length == null) n.put("points", map(this::f, this::f));
    n.put("blend_via_shortest_path", bool());
    if (version >= 28) n.put("sounds", strings());
    return n;
  }

  private WireNode controllers() throws IOException {
    return new WireNode()
        .put(
            "controllers",
            map(
                this::s,
                () ->
                    new WireNode()
                        .put("initial", s())
                        .put("states", map(this::s, this::controllerState))));
  }

  private WireNode image(boolean old, String role) throws IOException {
    byte[] bytes = bytes(66 * 1024 * 1024);
    int w = u(), h = u(), encoding = 1, frames = 1;
    if (!old && version >= 23) {
      encoding = enumRange(1, 5);
      frames = u();
    }
    check(
        w > 0 && h > 0 && w <= 4096 && h <= 4096 && frames == 1,
        "Invalid historical image metadata");
    var format = ImageProvider.Format.values()[encoding - 1];
    if (archival) {
      if (format == ImageProvider.Format.RGBA)
        check(bytes.length == (long) w * h * 4, "Invalid raw image size");
      return new WireNode()
          .put("bytes", bytes)
          .put("width", w)
          .put("height", h)
          .put("encoding", format.name())
          .put("frames", frames)
          .put("role", role);
    }
    var info = new ImageProvider.Info(format, w, h);
    if (format == ImageProvider.Format.RGBA)
      check(bytes.length == info.pixelBytes(), "Invalid raw image size");
    else
      check(
          images.probe(ByteBuffer.wrap(bytes)).equals(info), "Historical image metadata mismatch");
    if (format == ImageProvider.Format.RGBA || format == ImageProvider.Format.PNG) {
      byte[] pixels =
          format == ImageProvider.Format.RGBA ? bytes : images.decode(ByteBuffer.wrap(bytes), info);
      int maxW = w, maxH = h;
      switch (role) {
        case "gui" -> {
          maxW = 260;
          maxH = 450;
        }
        case "avatar" -> {
          maxW = 320;
          maxH = 320;
        }
        case "icon" -> {
          maxW = 192;
          maxH = 192;
        }
        case "thumbnail" -> {
          maxW = 156;
          maxH = 270;
        }
      }
      var encoded = images.encode(ByteBuffer.wrap(pixels), w, h, maxW, maxH);
      info = encoded.info();
      bytes = encoded.bytes();
      check(images.probe(ByteBuffer.wrap(bytes)).equals(info), "Image encode round trip failed");
    }
    return new WireNode()
        .put("bytes", bytes)
        .put("width", info.width())
        .put("height", info.height())
        .put("encoding", info.format().name());
  }

  private WireNode texture() throws IOException {
    return new WireNode()
        .put("uv", wrap(() -> image(false, "texture")))
        .put("pbr", map(this::u, () -> wrap(() -> image(false, "texture"))));
  }

  private WireNode sound() throws IOException {
    byte[] bytes = bytes(4 * 1024 * 1024);
    if (archival) return new WireNode().put("bytes", bytes).put("encoding", "OGG");
    var inspected = SupportedAudioProbe.inspect(ByteBuffer.wrap(bytes));
    if (!inspected.playable()) {
      if (inspected.disposition() == SupportedAudioProbe.Disposition.CORRUPT_SUPPORTED)
        throw new IOException("Corrupt historical audio");
      return null;
    }
    var m = inspected.media();
    return new WireNode()
        .put("bytes", bytes)
        .put("encoding", m.encoding().name())
        .put("channels", m.channels())
        .put("rate", m.sampleRate())
        .put("samples", m.frames());
  }

  private Map<String, WireNode> sounds(boolean wrapped) throws IOException {
    var result = new LinkedHashMap<String, WireNode>();
    int n = count(1_048_576);
    for (int i = 0; i < n; i++) {
      String name = s(), hash = wrapped ? hash() : "";
      var sound = sound();
      if (sound != null) {
        check(!result.containsKey(name), "Duplicate sound name");
        result.put(name, wrapped ? new WireNode().put("hash", hash).put("value", sound) : sound);
      }
    }
    return result;
  }

  private WireNode metadata() throws IOException {
    return new WireNode()
        .put("name", s())
        .put("tips", s())
        .put("license", s())
        .put("description", s())
        .put(
            "authors",
            list(
                () ->
                    new WireNode()
                        .put("name", s())
                        .put("role", s())
                        .put("contact", stringMap())
                        .put("comment", s())))
        .put("links", stringMap());
  }

  private WireNode form() throws IOException {
    return new WireNode()
        .put("type", s())
        .put("title", s())
        .put("description", s())
        .put("value", s())
        .put("step", f())
        .put("min", f())
        .put("max", f())
        .put("labels", stringMap());
  }

  private WireNode properties() throws IOException {
    var n =
        new WireNode().put("height_scale", f()).put("width_scale", f()).put("extra", stringMap());
    if (version >= 12)
      n.put(
              "buttons",
              list(
                  () ->
                      new WireNode()
                          .put("id", s())
                          .put("name", s())
                          .put("sound", s())
                          .put("forms", list(this::form))))
          .put("classify", list(() -> new WireNode().put("id", s()).put("extra", stringMap())));
    n.put("default_texture", s()).put("preview", s()).put("free", bool());
    if (version >= 8) n.put("layers", bool());
    if (version >= 13) n.put("culling", bool());
    if (version >= 14) n.put("no_rotation", bool());
    if (version >= 17) n.put("no_lighting", bool());
    if (version >= 32) n.put("merge", bool());
    if (version >= 20) {
      n.put("gui_foreground", s()).put("gui_background", s());
    }
    return n;
  }

  private WireNode export() throws IOException {
    return new WireNode().put("random", s()).put("timestamp", var(10)).put("extra", s());
  }

  private WireNode common() throws IOException {
    return new WireNode()
        .put("sounds", sounds(true))
        .put("functions", map(this::s, () -> wrap(this::s)))
        .put("languages", map(this::s, () -> wrap(this::stringMap)));
  }

  private WireNode player() throws IOException {
    return new WireNode()
        .put("animations", map(this::u, () -> wrap(this::animations)))
        .put("controllers", map(this::s, () -> wrap(this::controllers)))
        .put("textures", map(this::s, this::texture))
        .put("geos", map(this::u, () -> wrap(this::geo)));
  }

  private WireNode replacement() throws IOException {
    var n = new WireNode().put("animation", optional(() -> wrap(this::animations)));
    if (version >= 22) n.put("controller", optional(() -> wrap(this::controllers)));
    n.put("texture", texture()).put("geo", wrap(this::geo));
    if (version >= 27) n.put("match", strings());
    return n;
  }

  private WireNode info() throws IOException {
    var n =
        new WireNode()
            .put("hash", hash())
            .put("metadata", optional(this::metadata))
            .put("properties", properties())
            .put("avatars", map(this::s, () -> image(false, "avatar")));
    if (version >= 20) {
      int count = count(1_048_576);
      var gui = new LinkedHashMap<String, WireNode>();
      for (int i = 0; i < count; i++) {
        String key = s();
        check(!gui.containsKey(key), "Duplicate GUI image");
        String role =
            switch (key) {
              case "gui_foreground", "gui_background" -> "gui";
              case "thumb-button" -> "thumbnail";
              case "thumb-icon" -> "icon";
              default -> throw new IOException("Unknown GUI image role");
            };
        gui.put(key, image(false, role));
      }
      n.put("gui", gui);
    }
    if (version >= 27) n.put("origin", u());
    return n;
  }

  private List<WireNode> namedReplacements() throws IOException {
    var map = map(this::s, this::replacement);
    var result = new ArrayList<WireNode>();
    map.forEach(
        (k, v) ->
            result.add(
                archival
                    ? v.put("source_key", k)
                    : v.put("source_key", k).put("match", List.of(k))));
    return result;
  }

  private WireNode current() throws IOException {
    var n = new WireNode().put("common", common());
    if (version >= 27)
      n.put("vehicles", list(this::replacement)).put("projectiles", list(this::replacement));
    else {
      n.put("projectiles", namedReplacements());
      if (version >= 21) n.put("vehicles", namedReplacements());
    }
    n.put("player", optional(this::player))
        .put("info", info())
        .put("export", optional(this::export));
    n.put("order_info", s());
    return n;
  }

  private String textureName(String name) {
    if (version != 1) return name;
    if (name.equals("arrow.png")) return "/ARROW\\";
    return name.endsWith(".png") ? name.substring(0, name.length() - 4) : name;
  }

  private static <K> Map<K, WireNode> wrapMap(Map<K, ?> source, Map<K, String> hashes) {
    var result = new LinkedHashMap<K, WireNode>();
    source.forEach(
        (k, v) -> {
          if (v != null)
            result.put(k, new WireNode().put("value", v).put("hash", hashes.getOrDefault(k, "")));
        });
    return result;
  }

  private WireNode legacy() throws IOException {
    var modelTypes = list(() -> enumRange(1, 3));
    var geos = map(() -> enumRange(1, 3), () -> optional(this::geo));
    var animations = map(() -> enumRange(1, 11), () -> optional(this::animations));
    List<WireNode> controllers = List.of();
    Map<String, String> controllerHashes = Map.of();
    if (version >= 10) {
      controllers = list(() -> optional(this::controllers));
      controllerHashes = map(this::s, this::hash);
    }
    var textures =
        map(
            this::s,
            () -> {
              if (version >= 3)
                return new WireNode()
                    .put("uv", image(true, "texture"))
                    .put("pbr", map(this::u, () -> image(true, "texture")));
              var image = optional(() -> image(true, "texture"));
              check(archival || image != null, "Null historical texture");
              return new WireNode().put("uv", image);
            });
    Map<String, WireNode> sounds = Map.of();
    Map<String, String> soundHashes = Map.of(),
        functions = Map.of(),
        functionHashes = Map.of(),
        languageHashes = Map.of();
    Map<String, Map<String, String>> languages = Map.of();
    if (version >= 11) {
      sounds = sounds(false);
      soundHashes = map(this::s, this::hash);
    }
    if (version >= 16) {
      functions = stringMap();
      functionHashes = map(this::s, this::hash);
    }
    if (version >= 18) {
      languages = map(this::s, this::stringMap);
      languageHashes = map(this::s, this::hash);
    }
    Map<String, WireNode> avatars =
        version >= 4 ? map(this::s, () -> image(true, "avatar")) : Map.of();
    var geoHashes = map(() -> enumRange(1, 3), this::hash);
    var animationHashes = map(() -> enumRange(1, 11), this::hash);
    var textureHashes =
        map(
            this::s,
            () -> {
              var x = new WireNode().put("uv", hash());
              if (version >= 3) x.put("pbr", map(this::u, this::hash));
              return x;
            });
    String identity = hash();
    WireNode info =
        version >= 2
            ? optional(
                () ->
                    new WireNode()
                        .put("metadata", optional(this::metadata))
                        .put("properties", properties()))
            : null;
    var export = version >= 6 ? optional(this::export) : null;
    if (archival) {
      return new WireNode()
          .put("model_types", modelTypes)
          .put("geos", geos)
          .put("animations", animations)
          .put("controllers", controllers)
          .put("controller_hashes", controllerHashes)
          .put("textures", textures)
          .put("sounds", sounds)
          .put("sound_hashes", soundHashes)
          .put("functions", functions)
          .put("function_hashes", functionHashes)
          .put("languages", languages)
          .put("language_hashes", languageHashes)
          .put("avatars", avatars)
          .put("geo_hashes", geoHashes)
          .put("animation_hashes", animationHashes)
          .put("texture_hashes", textureHashes)
          .put("identity", identity)
          .put("info", info)
          .put("export", export);
    }
    var player = new WireNode();
    var root = new WireNode().put("player", player).put("export", export);
    var projectiles = new ArrayList<WireNode>();
    var arrow = new WireNode().put("source_key", "arrow").put("match", List.of("arrow"));
    var wrappedGeos = wrapMap(geos, geoHashes);
    if (geos.containsKey(3)) {
      projectiles.add(arrow);
      if (wrappedGeos.containsKey(3)) arrow.put("geo", wrappedGeos.remove(3));
    }
    var wrappedAnimations = wrapMap(animations, animationHashes);
    if (wrappedAnimations.containsKey(5)) {
      if (projectiles.isEmpty()) projectiles.add(arrow);
      arrow.put("animation", wrappedAnimations.remove(5));
    }
    player.put("geos", wrappedGeos).put("animations", wrappedAnimations);
    check(controllers.size() == controllerHashes.size(), "Controller names and values differ");
    var namedControllers = new LinkedHashMap<String, WireNode>();
    int c = 0;
    for (var e : controllerHashes.entrySet()) {
      var v = controllers.get(c++);
      if (v != null)
        namedControllers.put(e.getKey(), new WireNode().put("hash", e.getValue()).put("value", v));
    }
    player.put("controllers", namedControllers);
    var normalizedTextures = new LinkedHashMap<String, WireNode>();
    for (var e : textures.entrySet()) {
      String name = textureName(e.getKey());
      var t = e.getValue();
      var hashes = textureHashes.get(name);
      if (hashes == null && version == 1)
        for (var h : textureHashes.entrySet())
          if (textureName(h.getKey()).equals(name)) {
            hashes = h.getValue();
            break;
          }
      if (hashes == null) hashes = new WireNode();
      var texture =
          new WireNode()
              .put("uv", new WireNode().put("hash", hashes.s("uv")).put("value", t.n("uv")))
              .put(
                  "pbr",
                  wrapMap(t.<Integer, WireNode>map("pbr"), hashes.<Integer, String>map("pbr")));
      if (name.equals("/ARROW\\")) {
        if (projectiles.isEmpty()) projectiles.add(arrow);
        arrow.put("texture", texture);
      } else {
        check(!normalizedTextures.containsKey(name), "Duplicate normalized texture");
        normalizedTextures.put(name, texture);
      }
    }
    player.put("textures", normalizedTextures);
    root.put("projectiles", projectiles)
        .put(
            "common",
            new WireNode()
                .put("sounds", wrapMap(sounds, soundHashes))
                .put("functions", wrapMap(functions, functionHashes))
                .put("languages", wrapMap(languages, languageHashes)));
    if (info == null) {
      info = new WireNode();
      var main = wrappedGeos.getOrDefault(1, new WireNode()).n("value").n("properties");
      var props =
          new WireNode()
              .put("height_scale", main.has("height_scale") ? main.f("height_scale") : .7f)
              .put("width_scale", main.has("width_scale") ? main.f("width_scale") : .7f);
      if (!normalizedTextures.isEmpty())
        props.put("default_texture", normalizedTextures.keySet().iterator().next());
      var extras = new LinkedHashMap<String, String>();
      if (main.has("extra_info")) {
        var extra = main.n("extra_info");
        var authors =
            extra.<String>list("authors").stream().map(a -> new WireNode().put("name", a)).toList();
        info.put(
            "metadata",
            new WireNode()
                .put("name", extra.s("name"))
                .put("tips", extra.s("tips"))
                .put("license", extra.s("license"))
                .put("authors", authors));
        props.put("free", extra.b("free"));
        int i = 0;
        for (String label : extra.<String>list("extra")) extras.put("extra" + (i++), label);
      } else for (int i = 0; i < 8; i++) extras.put("extra" + i, "");
      info.put("properties", props.put("extra", extras));
    }
    info.put("hash", identity).put("avatars", avatars);
    return root.put("info", info);
  }
}
