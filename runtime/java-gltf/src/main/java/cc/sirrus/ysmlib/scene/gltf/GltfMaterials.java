package cc.sirrus.ysmlib.scene.gltf;

import cc.sirrus.ysmlib.scene.*;
import com.google.gson.*;
import java.util.*;
import static cc.sirrus.ysmlib.scene.gltf.JsonFields.*;

final class GltfMaterials {
  private GltfMaterials() {}
  static SceneAsset.Material read(JsonObject m) {
    var pbr=object(m,"pbrMetallicRoughness");var values=new LinkedHashMap<String,FloatData>();var textures=new LinkedHashMap<String,SceneAsset.TextureBinding>();
    values.put("baseColor",vectorSize(pbr,"baseColorFactor",4,1,1,1,1));values.put("metallic",new FloatData(number(pbr,"metallicFactor",1)));
    values.put("roughness",new FloatData(number(pbr,"roughnessFactor",1)));values.put("emissive",vectorSize(m,"emissiveFactor",3,0,0,0));
    texture(pbr,"baseColorTexture","baseColor",textures);texture(pbr,"metallicRoughnessTexture","metallicRoughness",textures);
    texture(m,"normalTexture","normal",textures);texture(m,"occlusionTexture","occlusion",textures);texture(m,"emissiveTexture","emissive",textures);
    String alpha=string(m,"alphaMode","OPAQUE");if(!Set.of("OPAQUE","MASK","BLEND").contains(alpha)) throw new IllegalArgumentException("Invalid alpha mode");
    // Extension fields stay namespaced. Their rendering coverage is reported separately by the reader.
    var extensions=object(m,"extensions");
    for(var extension:extensions.entrySet()) if(extension.getValue().isJsonObject()) {
      for(var field:extension.getValue().getAsJsonObject().entrySet()) {
        String key=extension.getKey()+"/"+field.getKey();var v=field.getValue();
        if(field.getKey().endsWith("Texture") && v.isJsonObject() && v.getAsJsonObject().has("index")) textures.put(key,binding(v.getAsJsonObject()));
        else if(v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber()) values.put(key,new FloatData(number(v)));
        else if(v.isJsonArray()) {
          var array=v.getAsJsonArray();boolean numeric=true;for(var element:array) numeric&=element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber();
          if(numeric) { float[] vector=new float[array.size()];for(int i=0;i<vector.length;i++) vector[i]=number(array.get(i));values.put(key,new FloatData(vector)); }
        }
      }
    }
    return new SceneAsset.Material(string(m,"name",""),extensions.has("KHR_materials_unlit")?"unlit":"metallic-roughness",values,textures,
        alpha,number(m,"alphaCutoff",.5f),bool(m,"doubleSided",false),metadata(m));
  }
  private static void texture(JsonObject owner,String field,String semantic,Map<String,SceneAsset.TextureBinding> out) {
    if(owner.has(field)) out.put(semantic,binding(owner.getAsJsonObject(field)));
  }
  private static SceneAsset.TextureBinding binding(JsonObject t) {
    int texCoord=integer(t,"texCoord",0);float[] uv={1,0,0,0,1,0,0,0,1};
    var ext=object(t,"extensions");if(ext.has("KHR_texture_transform")) {
      var transform=ext.getAsJsonObject("KHR_texture_transform");texCoord=integer(transform,"texCoord",texCoord);
      var offset=vectorSize(transform,"offset",2,0,0);var scale=vectorSize(transform,"scale",2,1,1);
      float rotation=number(transform,"rotation",0),c=(float)Math.cos(rotation),s=(float)Math.sin(rotation);
      uv=new float[]{c*scale.get(0),s*scale.get(0),0,-s*scale.get(1),c*scale.get(1),0,offset.get(0),offset.get(1),1};
    }
    return new SceneAsset.TextureBinding(integer(t,"index",-1),texCoord,new FloatData(uv),number(t,"scale",number(t,"strength",1)),metadata(t));
  }
}
