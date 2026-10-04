package cc.sirrus.ysmlib.scene.java;

import cc.sirrus.ysmlib.scene.*;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;

public final class SceneModelProfileCodec {
  private static final Gson JSON=new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
  public SceneModelProfile read(ByteData bytes) {
    if(bytes.size()>1024*1024) throw new IllegalArgumentException("Model profile exceeds 1 MiB");
    var tree=JsonParser.parseString(new String(bytes.copy(),StandardCharsets.UTF_8));
    if(tree.isJsonObject()&&!tree.getAsJsonObject().has("metadata"))tree.getAsJsonObject().add("metadata",JSON.toJsonTree(SceneModelProfile.Metadata.empty()));
    if(tree.isJsonObject()&&!tree.getAsJsonObject().has("retarget"))tree.getAsJsonObject().add("retarget",JSON.toJsonTree(SceneModelProfile.Retarget.empty()));
    if(tree.isJsonObject()&&!tree.getAsJsonObject().has("transitions"))tree.getAsJsonObject().add("transitions",new JsonArray());
    if(tree.isJsonObject()&&!tree.getAsJsonObject().has("heldItems"))tree.getAsJsonObject().add("heldItems",JSON.toJsonTree(SceneModelProfile.HeldItems.defaults()));
    var root=object(tree,"profile","schemaVersion","profileId","placement","bones","actions","presentation","metadata","retarget","transitions","heldItems");
    if(root.get("retarget").isJsonObject()&&!root.getAsJsonObject("retarget").has("sourceModel"))root.getAsJsonObject("retarget").addProperty("sourceModel","@ysm/default");
    var retarget=object(root.get("retarget"),"retarget","sourceBones","translationScale","sourceModel");
    if(!retarget.get("sourceBones").isJsonObject())throw new IllegalArgumentException("retarget.sourceBones must be an object");
    var metadata=object(root.get("metadata"),"metadata","name","tips","license","authors","links");
    object(metadata.get("license"),"metadata.license","type","desc");
    pairs(metadata.get("links"),"metadata.links");
    if(!metadata.get("authors").isJsonArray())throw new IllegalArgumentException("metadata.authors must be an array");
    for(var value:metadata.getAsJsonArray("authors")){var author=object(value,"author","name","role","contacts","comment","avatar");pairs(author.get("contacts"),"author.contacts");}
    object(root.get("placement"),"placement","metersPerUnit","sizeMode","scale","height","referenceHeight","footY","x","y","z","yaw");
    object(root.get("presentation"),"presentation","outlines","outlineScale");
    if(!root.get("bones").isJsonObject() || !root.get("actions").isJsonObject())throw new IllegalArgumentException("Profile mappings must be objects");
    root.getAsJsonObject("bones").entrySet().forEach(e->{
      if(e.getValue().isJsonObject())for(String key:java.util.List.of("restPitch","restYaw","restRoll"))
        if(!e.getValue().getAsJsonObject().has(key))e.getValue().getAsJsonObject().addProperty(key,0);
      object(e.getValue(),"bones."+e.getKey(),"index","name","parent","pitch","yaw","roll","weight","restPitch","restYaw","restRoll");
    });
    var held=object(root.get("heldItems"),"heldItems","enabled","firstPerson","left","right");
    for(String hand:java.util.List.of("left","right"))object(held.get(hand),"heldItems."+hand,"enabled","binding","x","y","z","pitch","yaw","roll","scale");
    root.getAsJsonObject("actions").entrySet().forEach(e->object(e.getValue(),"actions."+e.getKey(),"path","clip","loop"));
    if(!root.get("transitions").isJsonArray())throw new IllegalArgumentException("transitions must be an array");
    for(var edge:root.getAsJsonArray("transitions")) {
      var transition=object(edge,"transition","from","to","animation");
      object(transition.get("animation"),"transition.animation","path","clip","loop");
    }
    var value=JSON.fromJson(tree,SceneModelProfile.class);
    if(value==null) throw new IllegalArgumentException("Empty model profile");return value;
  }
  private static void pairs(JsonElement value,String location){
    if(!value.isJsonArray())throw new IllegalArgumentException(location+" must be an array");
    for(var pair:value.getAsJsonArray())object(pair,location,"key","value");
  }
  private static JsonObject object(JsonElement value,String location,String... keys) {
    if(value==null || !value.isJsonObject())throw new IllegalArgumentException("Expected object: "+location);
    var object=value.getAsJsonObject();var expected=java.util.Set.of(keys);
    for(String key:object.keySet())if(!expected.contains(key))throw new IllegalArgumentException("Unknown profile field: "+location+"."+key);
    for(String key:keys)if(!object.has(key)||object.get(key).isJsonNull())throw new IllegalArgumentException("Missing profile field: "+location+"."+key);
    return object;
  }
  public ByteData write(SceneModelProfile value) { return new ByteData(JSON.toJson(value).getBytes(StandardCharsets.UTF_8)); }
}
