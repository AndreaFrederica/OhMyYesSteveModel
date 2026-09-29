package cc.sirrus.ysmlib.legacy.java;

import cc.sirrus.ysmlib.legacy.DecodedWorkspaceProvider;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Materializes the archival reader tree without calling the current-schema projector. */
public final class JavaDecodedWorkspaceProvider implements DecodedWorkspaceProvider {
  public int profile() {
    return 1;
  }

  public void materialize(ByteBuffer historicalWire, EntrySink output) throws IOException {
    if (historicalWire.remaining() > 256 * 1024 * 1024)
      throw new IOException("Wire budget exceeded");
    byte[] wire = new byte[historicalWire.remaining()];
    historicalWire.duplicate().get(wire);
    var root = new HistoricalWireReader(wire, null, true).read();
    var writer = new Writer(output);
    var decoded = new LinkedHashMap<String, Object>();
    decoded.put("schema", "LegacyV3Decoded");
    decoded.put("schema_version", profile());
    decoded.put("data", writer.visit(root, "legacy"));
    writer.json("legacy/model.json", decoded);
  }

  private static final class Writer {
    final EntrySink output;
    int sequence;

    Writer(EntrySink output) {
      this.output = Objects.requireNonNull(output);
    }

    void bytes(String path, byte[] value) throws IOException {
      output.write(path, ByteBuffer.wrap(value).asReadOnlyBuffer());
    }

    void json(String path, Object value) throws IOException {
      var text = new StringBuilder();
      append(text, value);
      bytes(path, text.append('\n').toString().getBytes(StandardCharsets.UTF_8));
    }

    Object visit(Object value, String category) throws IOException {
      if (value instanceof WireNode node) {
        if (node.has("bytes")) return media(node);
        if (node.has("quads")) return cube(node);
        var result = new LinkedHashMap<String, Object>();
        for (var entry : node.fields.entrySet()) {
          String key = entry.getKey();
          String next = category(key, category);
          Object converted = visit(entry.getValue(), next);
          if (Set.of("animations", "controllers", "languages", "metadata").contains(key)) {
            String path = next + "/" + sequence++ + ".json";
            json(path, converted);
            result.put(key, Map.of("$ref", path));
          } else result.put(key, converted);
        }
        if (node.has("properties") && node.has("bones")) {
          String path = "geometry/" + sequence++ + ".json";
          json(path, result);
          return Map.of("$ref", path);
        }
        return result;
      }
      if (value instanceof Map<?, ?> map) {
        // Entry arrays retain typed keys, order, null optionals, and names verbatim.
        var entries = new ArrayList<Object>();
        for (var e : map.entrySet()) {
          Object item;
          if (category.equals("functions") && e.getValue() instanceof String text) {
            String path = "functions/" + sequence++ + ".molang";
            bytes(path, text.getBytes(StandardCharsets.UTF_8));
            item = Map.of("$ref", path);
          } else if (category.equals("functions")
              && e.getValue() instanceof WireNode wrapped
              && wrapped.fields.get("value") instanceof String text) {
            String path = "functions/" + sequence++ + ".molang";
            bytes(path, text.getBytes(StandardCharsets.UTF_8));
            item = Map.of("hash", wrapped.s("hash"), "value", Map.of("$ref", path));
          } else item = visit(e.getValue(), category);
          var entry = new LinkedHashMap<String, Object>();
          entry.put("key", visit(e.getKey(), category));
          entry.put("value", item);
          entries.add(entry);
        }
        return Map.of("$map", entries);
      }
      if (value instanceof List<?> list) {
        var result = new ArrayList<Object>();
        for (Object item : list) result.add(visit(item, category));
        return result;
      }
      if (value instanceof byte[] data) return Map.of("$bytes", HexFormat.of().formatHex(data));
      if (value instanceof Float number && !Float.isFinite(number))
        return Map.of(
            "$float32", String.format(Locale.ROOT, "%08x", Float.floatToRawIntBits(number)));
      return value;
    }

    Object cube(WireNode node) throws IOException {
      var quads = node.<WireNode>list("quads");
      var mesh = ByteBuffer.allocate(8 + quads.size() * 23 * 4).order(ByteOrder.LITTLE_ENDIAN);
      mesh.putInt(0x314d5359).putInt(quads.size()); // YSM1, then quad count.
      for (var quad : quads) {
        for (float f : quad.<Float>list("normal")) mesh.putFloat(f);
        for (var vertex : quad.<WireNode>list("vertices")) {
          for (float f : vertex.<Float>list("position")) mesh.putFloat(f);
          for (float f : vertex.<Float>list("uv")) mesh.putFloat(f);
        }
      }
      String path = "geometry/" + sequence++ + ".mesh";
      bytes(path, mesh.array());
      var result = new LinkedHashMap<String, Object>();
      result.put("quads", Map.of("$ref", path, "count", quads.size()));
      if (node.has("zero_size")) result.put("zero_size", node.fields.get("zero_size"));
      return result;
    }

    Object media(WireNode node) throws IOException {
      byte[] original = node.bytes("bytes"), emitted = original;
      String encoding = node.s("encoding"), role = node.s("role");
      String extension = encoding.toLowerCase(Locale.ROOT);
      String dir =
          switch (role) {
            case "avatar" -> "avatars";
            case "gui", "icon", "thumbnail" -> "gui";
            default -> node.has("width") ? "textures" : "sounds";
          };
      if (encoding.equals("RGBA")) {
        int w = node.i("width"), h = node.i("height");
        var tga = ByteBuffer.allocate(18 + original.length).order(ByteOrder.LITTLE_ENDIAN);
        tga.put(2, (byte) 2).putShort(12, (short) w).putShort(14, (short) h);
        tga.put(16, (byte) 32).put(17, (byte) 0x28).position(18);
        for (int i = 0; i < original.length; i += 4)
          tga.put(original[i + 2]).put(original[i + 1]).put(original[i]).put(original[i + 3]);
        emitted = tga.array();
        extension = "tga";
      } else if (encoding.equals("JPEG")) extension = "jpg";
      String path = dir + "/" + sequence++ + "." + extension;
      bytes(path, emitted);
      var result = new LinkedHashMap<String, Object>();
      for (var e : node.fields.entrySet())
        if (!e.getKey().equals("bytes")) result.put(e.getKey(), e.getValue());
      result.put("$ref", path);
      if (encoding.equals("RGBA")) result.put("original_rgba_sha256", sha256(original));
      return result;
    }

    static String category(String key, String current) {
      return switch (key) {
        case "animations", "animation" -> "animations";
        case "controllers", "controller" -> "controllers";
        case "languages" -> "lang";
        case "functions" -> "functions";
        default -> current;
      };
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static void append(StringBuilder out, Object value) {
    if (value == null) out.append("null");
    else if (value instanceof String text) {
      out.append('"');
      for (int i = 0; i < text.length(); i++) {
        char c = text.charAt(i);
        if (c == '"' || c == '\\') out.append('\\').append(c);
        else if (c < 32 || Character.isSurrogate(c))
          out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
        else out.append(c);
      }
      out.append('"');
    } else if (value instanceof Map<?, ?> map) {
      out.append('{');
      boolean first = true;
      for (var e : new TreeMap<>(map).entrySet()) {
        if (!first) out.append(',');
        first = false;
        append(out, e.getKey().toString());
        out.append(':');
        append(out, e.getValue());
      }
      out.append('}');
    } else if (value instanceof List<?> list) {
      out.append('[');
      boolean first = true;
      for (Object item : list) {
        if (!first) out.append(',');
        first = false;
        append(out, item);
      }
      out.append(']');
    } else if (value instanceof Number || value instanceof Boolean) out.append(value);
    else throw new IllegalArgumentException("Unsupported archival value " + value.getClass());
  }
}
