package cc.sirrus.ysmlib.scene.java;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.fbx.FbxMappingProfile;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Strict bounded codec for the FBX source mapping sidecar. */
public final class FbxMappingProfileCodec {
  public FbxMappingProfile read(ByteData bytes) {
    if(bytes.size()>256*1024) throw new IllegalArgumentException("FBX mapping profile exceeds 256 KiB");
    var root=object(JsonParser.parseString(new String(bytes.copy(),StandardCharsets.UTF_8)),"profile");
    keys(root,"profile","schemaVersion","skinByMeshElement");
    int schema=root.get("schemaVersion").getAsInt();String hash=root.has("sourceHash")?root.get("sourceHash").getAsString():"";
    var mapElement=root.get("skinByMeshElement");if(!mapElement.isJsonObject())throw new IllegalArgumentException("skinByMeshElement must be an object");
    var map=new LinkedHashMap<Integer,Integer>();for(var e:mapElement.getAsJsonObject().entrySet()) {
      int mesh=parseId(e.getKey()),skin=integer(e.getValue());if(skin<0)throw new IllegalArgumentException("Negative FBX Skin ID");map.put(mesh,skin);
    }
    var materials=new LinkedHashMap<Integer,FbxMappingProfile.MaterialOverride>();
    var materialObject=root.has("materialByElement")?root.getAsJsonObject("materialByElement"):new JsonObject();
    for(var e:materialObject.entrySet()) {
      var value=object(e.getValue(),"materialByElement."+e.getKey());
      keys(value,"material","name","baseColor","normal","emissive","alphaMode","alphaCutoff");
      materials.put(parseId(e.getKey()),new FbxMappingProfile.MaterialOverride(
          value.get("name").getAsString(),value.get("baseColor").getAsString(),
          value.get("normal").getAsString(),value.get("emissive").getAsString(),
          value.get("alphaMode").getAsString(),value.get("alphaCutoff").getAsFloat()));
    }
    return new FbxMappingProfile(schema,hash,map,materials);
  }
  public ByteData write(FbxMappingProfile value) {
    var root=new JsonObject();root.addProperty("schemaVersion",value.schemaVersion());root.addProperty("sourceHash",value.sourceHash());var map=new JsonObject();
    value.skinByMeshElement().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e->map.addProperty(Integer.toString(e.getKey()),e.getValue()));root.add("skinByMeshElement",map);
    var materials=new JsonObject();value.materialByElement().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e->{
      var material=e.getValue();var object=new JsonObject();object.addProperty("name",material.name());object.addProperty("baseColor",material.baseColor());object.addProperty("normal",material.normal());object.addProperty("emissive",material.emissive());object.addProperty("alphaMode",material.alphaMode());object.addProperty("alphaCutoff",material.alphaCutoff());materials.add(Integer.toString(e.getKey()),object);
    });root.add("materialByElement",materials);
    return new ByteData(new GsonBuilder().disableHtmlEscaping().create().toJson(root).getBytes(StandardCharsets.UTF_8));
  }
  private static int parseId(String value){try{if(!value.matches("[0-9]+"))throw new NumberFormatException();long n=Long.parseLong(value);if(n>Integer.MAX_VALUE)throw new NumberFormatException();return (int)n;}catch(NumberFormatException e){throw new IllegalArgumentException("Invalid FBX mesh element ID: "+value);}}
  private static int integer(JsonElement value){try{return value.getAsBigDecimal().intValueExact();}catch(NumberFormatException|ArithmeticException e){throw new IllegalArgumentException("Expected integer FBX Skin ID",e);}}
  private static JsonObject object(JsonElement value,String where){if(value==null||!value.isJsonObject())throw new IllegalArgumentException("Expected object: "+where);return value.getAsJsonObject();}
  private static void keys(JsonObject object,String where,String... names){var allowed=new HashSet<>(Set.of(names));allowed.add("sourceHash");allowed.add("materialByElement");for(String key:object.keySet())if(!allowed.contains(key))throw new IllegalArgumentException("Unknown field: "+where+"."+key);for(String key:names)if(!object.has(key)||object.get(key).isJsonNull())throw new IllegalArgumentException("Missing field: "+where+"."+key);}
}
