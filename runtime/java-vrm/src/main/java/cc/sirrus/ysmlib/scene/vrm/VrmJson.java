package cc.sirrus.ysmlib.scene.vrm;

import cc.sirrus.ysmlib.scene.*;
import com.google.gson.*;
import java.util.*;

/** JSON trees here originate from the bounded, duplicate-rejecting glTF reader. */
final class VrmJson {
  private VrmJson() {}
  static JsonObject extensions(Map<String,String> metadata) { return JsonParser.parseString(metadata.getOrDefault("extensions","{}")).getAsJsonObject(); }
  static JsonObject object(JsonObject o,String name) { return o.has(name)?o.getAsJsonObject(name):new JsonObject(); }
  static JsonArray array(JsonObject o,String name) { return o.has(name)?o.getAsJsonArray(name):new JsonArray(); }
  static String string(JsonObject o,String name,String fallback) {
    if(!o.has(name)) return fallback;var p=o.getAsJsonPrimitive(name);if(!p.isString()) throw new IllegalArgumentException("Expected string: "+name);return p.getAsString();
  }
  static int integer(JsonElement v) {
    if(v==null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Expected integer");
    return v.getAsBigDecimal().intValueExact();
  }
  static int integer(JsonObject o,String name,int fallback) { return o.has(name)?integer(o.get(name)):fallback; }
  static float number(JsonElement v) {
    if(v==null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Expected number");
    float n=v.getAsFloat();if(!Float.isFinite(n)) throw new IllegalArgumentException("Non-finite number");return n;
  }
  static float number(JsonObject o,String name,float fallback) { return o.has(name)?number(o.get(name)):fallback; }
  static float range(float v,float min,float max,String name) { if(!Float.isFinite(v) || v<min || v>max) throw new IllegalArgumentException("Out of range: "+name);return v; }
  static boolean bool(JsonObject o,String name,boolean fallback) {
    if(!o.has(name)) return fallback;var p=o.getAsJsonPrimitive(name);if(!p.isBoolean()) throw new IllegalArgumentException("Expected boolean: "+name);return p.getAsBoolean();
  }
  static FloatData vector(JsonElement v) { var a=v.getAsJsonArray();float[] out=new float[a.size()];for(int i=0;i<out.length;i++) out[i]=number(a.get(i));return new FloatData(out); }
  static FloatData vector(JsonObject o,String name,int size,float... fallback) {
    var v=o.has(name)?vector(o.get(name)):new FloatData(fallback);if(v.size()!=size) throw new IllegalArgumentException("Invalid vector: "+name);return v;
  }
  static Vec3 vec3(JsonObject o,String name,Vec3 fallback) {
    var v=vector(o,name,3,fallback.x(),fallback.y(),fallback.z());return new Vec3(v.get(0),v.get(1),v.get(2));
  }
  static Vec3 legacyVec3(JsonObject o,String name,Vec3 fallback) {
    var v=object(o,name);return new Vec3(number(v,"x",fallback.x()),number(v,"y",fallback.y()),number(v,"z",fallback.z()));
  }
  static int index(int i,int count,String label) { if(i<0 || i>=count) throw new IllegalArgumentException("Invalid "+label+" index: "+i);return i; }
  static int optionalIndex(int i,int count,String label) { return i==-1?i:index(i,count,label); }
  static IntData indices(JsonArray a,int count,String label) {
    int[] out=new int[a.size()];var seen=new HashSet<Integer>();for(int i=0;i<out.length;i++) {
      out[i]=index(integer(a.get(i)),count,label);if(!seen.add(out[i])) throw new IllegalArgumentException("Duplicate "+label);
    }return new IntData(out);
  }
  static void version(JsonObject o,String label) { if(!string(o,"specVersion","").equals("1.0")) throw new IllegalArgumentException("Unsupported "+label+" version"); }
}
