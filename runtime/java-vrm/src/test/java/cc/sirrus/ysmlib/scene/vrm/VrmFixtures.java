package cc.sirrus.ysmlib.scene.vrm;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.*;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class VrmFixtures {
  static final CompatibilityReport COVERAGE=new CompatibilityReport(List.of(),List.of());
  static JsonObject avatar(boolean legacy) {
    JsonObject root=JsonParser.parseString("""
        {"asset":{"version":"2.0"},"scenes":[{"nodes":[0]}],"scene":0,
        "buffers":[{"byteLength":12,"uri":"data:application/octet-stream;base64,AAAAAAAAAAAAAAAA"}],
        "bufferViews":[{"buffer":0,"byteLength":12}],"accessors":[{"bufferView":0,"componentType":5126,"count":1,"type":"VEC3","min":[0,0,0],"max":[0,0,0]}],
        "meshes":[{"primitives":[{"mode":0,"attributes":{"POSITION":0},"material":0,"targets":[{"POSITION":0},{"POSITION":0},{"POSITION":0}]}]}],
        "materials":[{"name":"face","pbrMetallicRoughness":{"baseColorFactor":[0.2,0.3,0.4,0.8]}}]}
        """).getAsJsonObject();
    var h=VrmHumanoid.hierarchy(legacy);var nodes=new JsonArray();var ids=new LinkedHashMap<String,Integer>();
    for(String name:h.keySet()) { ids.put(name,nodes.size());var n=new JsonObject();n.addProperty("name",name);n.add("children",new JsonArray());nodes.add(n); }
    for(var e:h.entrySet()) if(!e.getValue().isEmpty()) nodes.get(ids.get(e.getValue())).getAsJsonObject().getAsJsonArray("children").add(ids.get(e.getKey()));
    for(int i=0;i<2;i++) { var n=new JsonObject();n.addProperty("name","face"+i);n.addProperty("mesh",0);nodes.get(0).getAsJsonObject().getAsJsonArray("children").add(nodes.size());nodes.add(n); }
    root.add("nodes",nodes);var avatar=new JsonObject();avatar.addProperty("specVersion",legacy?"0.0":"1.0");
    var humanoid=new JsonObject();if(legacy) {
      var hb=new JsonArray();ids.forEach((k,v)->{var o=new JsonObject();o.addProperty("bone",k);o.addProperty("node",v);hb.add(o);});humanoid.add("humanBones",hb);
    } else { var hb=new JsonObject();ids.forEach((k,v)->{var o=new JsonObject();o.addProperty("node",v);hb.add(k,o);});humanoid.add("humanBones",hb); }
    avatar.add("humanoid",humanoid);avatar.add("meta",JsonParser.parseString("{\"name\":\"Synthetic test avatar\",\"authors\":[\"YSM\"],\"licenseUrl\":\"https://vrm.dev/licenses/1.0/\",\"customPolicy\":{\"retained\":true}}"));
    var ext=new JsonObject();ext.add(legacy?"VRM":"VRMC_vrm",avatar);root.add("extensions",ext);
    var used=new JsonArray();used.add(legacy?"VRM":"VRMC_vrm");root.add("extensionsUsed",used);return root;
  }
  static JsonObject extension(JsonObject root,boolean legacy) { return root.getAsJsonObject("extensions").getAsJsonObject(legacy?"VRM":"VRMC_vrm"); }
  static VrmDocument read(JsonObject root) throws Exception { return new VrmReader().read(new ByteData(root.toString().getBytes(StandardCharsets.UTF_8)),AssetResolver.NONE,ReadLimits.DEFAULT); }
  static Transform transform(JsonObject o) {
    var t=o.getAsJsonArray("translation");var r=o.getAsJsonArray("rotation");var s=o.getAsJsonArray("scale");
    return new Transform(vec(t),Rotation.normalized(r.get(0).getAsFloat(),r.get(1).getAsFloat(),r.get(2).getAsFloat(),r.get(3).getAsFloat()),vec(s));
  }
  static Vec3 vec(JsonArray a) { return new Vec3(a.get(0).getAsFloat(),a.get(1).getAsFloat(),a.get(2).getAsFloat()); }
  static SceneAsset scene(int[] parents,List<Transform> transforms) {
    var nodes=new ArrayList<SceneAsset.Node>();var roots=new ArrayList<Integer>();for(int n=0;n<parents.length;n++) {
      var children=new ArrayList<Integer>();for(int c=0;c<parents.length;c++) if(parents[c]==n) children.add(c);
      if(parents[n]==-1) roots.add(n);
      nodes.add(new SceneAsset.Node("n"+n,new IntData(children.stream().mapToInt(Integer::intValue).toArray()),transforms.get(n),null,-1,-1,-1,-1,FloatData.EMPTY,Map.of()));
    }return new SceneAsset("test",SceneAsset.Coordinates.GLTF,nodes,List.of(new SceneAsset.Scene("",new IntData(roots.stream().mapToInt(Integer::intValue).toArray()),Map.of())),0,
        List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),Map.of(),COVERAGE);
  }
  static ScenePose pose(int[] parents,List<Transform> transforms) {
    var local=transforms.stream().map(Transform::matrix).toList();var global=new ArrayList<Matrix4>();
    for(int n=0;n<parents.length;n++) global.add(parents[n]==-1?local.get(n):global.get(parents[n]).multiply(local.get(n)));
    return new ScenePose(0,local,global,Collections.nCopies(parents.length,FloatData.EMPTY),new AnimationFrame(0,List.of()));
  }
  static JsonElement resource(String path) throws Exception {
    try(var stream=VrmFixtures.class.getResourceAsStream("/three-vrm-oracle/"+path)) { return JsonParser.parseString(new String(Objects.requireNonNull(stream).readAllBytes(),StandardCharsets.UTF_8)); }
  }
}
