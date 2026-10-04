package cc.sirrus.ysmlib.scene.gltf;

import com.google.gson.*;
import cc.sirrus.ysmlib.scene.*;
import java.util.*;

final class JsonFields {
  private JsonFields() {}
  static JsonArray array(JsonObject o,String name) { return o.has(name)?o.getAsJsonArray(name):new JsonArray(); }
  static JsonObject object(JsonObject o,String name) { return o.has(name)?o.getAsJsonObject(name):new JsonObject(); }
  static String string(JsonObject o,String name,String fallback) {
    if(!o.has(name)) return fallback;var p=o.get(name).getAsJsonPrimitive();if(!p.isString()) throw new IllegalArgumentException("Expected string: "+name);return p.getAsString();
  }
  static int integer(JsonElement value) { if(value==null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Expected integer");return value.getAsBigDecimal().intValueExact(); }
  static int integer(JsonObject o,String name,int fallback) { return o.has(name)?integer(o.get(name)):fallback; }
  static float number(JsonElement value) { if(!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Expected number");float v=value.getAsFloat();if(!Float.isFinite(v)) throw new IllegalArgumentException("Non-finite JSON number");return v; }
  static float number(JsonObject o,String name,float fallback) { return o.has(name)?number(o.get(name)):fallback; }
  static boolean bool(JsonObject o,String name,boolean fallback) {
    if(!o.has(name)) return fallback;var p=o.get(name).getAsJsonPrimitive();if(!p.isBoolean()) throw new IllegalArgumentException("Expected boolean: "+name);return p.getAsBoolean();
  }
  static FloatData vector(JsonObject o,String name,float... fallback) {
    if(!o.has(name)) return new FloatData(fallback);var a=o.getAsJsonArray(name);float[] v=new float[a.size()];for(int i=0;i<v.length;i++) v[i]=number(a.get(i));return new FloatData(v);
  }
  static FloatData vectorSize(JsonObject o,String name,int size,float... fallback) { var v=vector(o,name,fallback);if(v.size()!=size) throw new IllegalArgumentException("Invalid "+name+" vector");return v; }
  static Vec3 vec3(JsonObject o,String name,Vec3 fallback) { var v=vectorSize(o,name,3,fallback.x(),fallback.y(),fallback.z());return new Vec3(v.get(0),v.get(1),v.get(2)); }
  static IntData ints(JsonArray array) { int[] out=new int[array.size()];for(int i=0;i<out.length;i++) out[i]=integer(array.get(i));return new IntData(out); }
  static Map<String,String> metadata(JsonObject o) {
    var out=new LinkedHashMap<String,String>();if(o.has("extensions")) out.put("extensions",o.get("extensions").toString());if(o.has("extras")) out.put("extras",o.get("extras").toString());return out;
  }
  static int nonnegative(int value,String name) { if(value<0) throw new IllegalArgumentException("Negative "+name);return value; }
}
