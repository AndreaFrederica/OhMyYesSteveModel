package cc.sirrus.ysmlib.scene.vrm;

import cc.sirrus.ysmlib.scene.*;
import java.util.*;

final class VrmHumanoid {
  private VrmHumanoid() {}
  static Map<String,String> hierarchy(boolean legacy) {
    var h=new LinkedHashMap<String,String>();h.put("hips","");h.put("spine","hips");h.put("chest","spine");h.put("upperChest","chest");h.put("neck","upperChest");h.put("head","neck");
    for(String n:List.of("leftEye","rightEye","jaw")) h.put(n,"head");
    for(String side:List.of("left","right")) {
      h.put(side+"UpperLeg","hips");h.put(side+"LowerLeg",side+"UpperLeg");h.put(side+"Foot",side+"LowerLeg");h.put(side+"Toes",side+"Foot");
      h.put(side+"Shoulder","upperChest");h.put(side+"UpperArm",side+"Shoulder");h.put(side+"LowerArm",side+"UpperArm");h.put(side+"Hand",side+"LowerArm");
      for(String finger:List.of("Thumb","Index","Middle","Ring","Little")) {
        String p=side+"Hand";for(String part:finger.equals("Thumb")&&!legacy?List.of("Metacarpal","Proximal","Distal"):List.of("Proximal","Intermediate","Distal")) {
          String name=side+finger+part;h.put(name,p);p=name;
        }
      }
    }return h;
  }
  static void validate(SceneAsset scene,Map<String,Integer> bones,boolean legacy) {
    var hierarchy=hierarchy(legacy);var seen=new HashSet<Integer>();int[] parents=VrmTopology.parents(scene);
    var required=new HashSet<>(List.of("hips","spine","head","leftUpperLeg","leftLowerLeg","leftFoot","rightUpperLeg","rightLowerLeg","rightFoot","leftUpperArm","leftLowerArm","leftHand","rightUpperArm","rightLowerArm","rightHand"));
    if(legacy) required.addAll(List.of("chest","neck"));
    for(String name:required) if(!bones.containsKey(name)) throw new IllegalArgumentException("Missing required human bone: "+name);
    for(var entry:bones.entrySet()) {
      String name=entry.getKey();int n=entry.getValue();if(!hierarchy.containsKey(name) || !seen.add(n)) throw new IllegalArgumentException("Unknown or aliased human bone: "+name);
      String parent=hierarchy.get(name);
      if((name.equals("upperChest") || name.endsWith("Intermediate") || name.endsWith("Distal") || !legacy && name.endsWith("ThumbProximal")) && !bones.containsKey(parent))
        throw new IllegalArgumentException("Missing required human bone parent: "+name);
      while(!parent.isEmpty() && !bones.containsKey(parent)) parent=hierarchy.get(parent);
      // The closest mapped ancestor must be the specified humanoid parent, allowing ordinary intervening nodes.
      int ancestor=parents[n];while(ancestor!=-1 && !bones.containsValue(ancestor)) ancestor=parents[ancestor];
      if(ancestor!=(parent.isEmpty()?-1:bones.get(parent))) throw new IllegalArgumentException("Invalid humanoid hierarchy: "+name);
      if(!legacy) {
        var node=scene.nodes().get(n);if(node.matrix()!=null) VrmMath.matrixRotation(node.matrix());
        var s=node.transform().scale();if(s.x()<=0 || s.y()<=0 || s.z()<=0) throw new IllegalArgumentException("Non-positive humanoid scale");
        if(node.matrix()!=null && VrmMath.determinant(node.matrix())<=0) throw new IllegalArgumentException("Non-positive humanoid matrix scale");
      }
    }
  }
}
