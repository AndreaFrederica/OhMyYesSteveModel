package cc.sirrus.ysmlib.scene;

import java.util.*;
import java.text.Normalizer;

/** Source bone identities for authoring. Suggestions never rewrite the source names or choose ambiguous matches. */
public final class SceneSkeleton {
  public record Bone(int index,String name,String alternate,int parent,Vec3 position,boolean ik) {}
  private SceneSkeleton() {}
  public static List<Bone> of(ScenePackageAssets assets) {
    var out=new ArrayList<Bone>();var model=assets.model();
    if(model instanceof ScenePackageAssets.Pmx pmx) {
      var bones=pmx.value().bones();for(int i=0;i<bones.size();i++) { var b=bones.get(i);out.add(new Bone(i,b.names().local(),b.names().universal(),b.parent(),b.position(),b.ik()!=null)); }
    } else if(model instanceof ScenePackageAssets.Pmd pmd) {
      var doc=pmd.value();for(int i=0;i<doc.bones().size();i++) { var b=doc.bones().get(i);int index=i;
        String alt=doc.english()==null || i>=doc.english().bones().size()?"":doc.english().bones().get(i);
        out.add(new Bone(i,b.name(),alt,b.parent()==0xffff?-1:b.parent(),b.position(),doc.ik().stream().anyMatch(k->k.controller()==index))); }
    } else if(model instanceof ScenePackageAssets.Fbx fbx) {
      var doc=fbx.value();var names=new HashMap<Integer,String>();for(var e:doc.elements())names.put(e.id(),e.name());
      for(int i=0;i<doc.nodes().size();i++) {var node=doc.nodes().get(i);var m=node.world();out.add(new Bone(i,names.getOrDefault(node.element(),"node-"+i),"",node.parent(),new Vec3((float)m.get(9),(float)m.get(10),(float)m.get(11)),false));}
    } else {
      SceneAsset scene=model instanceof ScenePackageAssets.Gltf g?g.value().scene():model instanceof ScenePackageAssets.Vrm v?v.value().scene():null;
      if(scene!=null) {
        var nodes=scene.nodes();int[] parents=new int[nodes.size()];Arrays.fill(parents,-1);
        for(int i=0;i<nodes.size();i++) { var children=nodes.get(i).children();for(int j=0;j<children.size();j++) parents[children.get(j)]=i; }
        for(int i=0;i<nodes.size();i++) out.add(new Bone(i,nodes.get(i).name(),"",parents[i],Vec3.ZERO,false));
      }
    }
    return List.copyOf(out);
  }
  public static SceneModelProfile.BoneBinding bind(List<Bone> bones,int index) {
    var b=bones.get(index);var path=new ArrayDeque<String>();int p=b.parent(),count=0;
    while(p>=0) { if(p>=bones.size() || count++>=bones.size())throw new IllegalArgumentException("Invalid skeleton hierarchy");var parent=bones.get(p);path.addFirst(p+":"+parent.name());p=parent.parent(); }
    return new SceneModelProfile.BoneBinding(index,b.name(),String.join("/",path),0,0,0,1);
  }
  public static void validate(List<Bone> bones,Map<String,SceneModelProfile.BoneBinding> mapping) {
    for(var entry:mapping.entrySet()) {
      var b=entry.getValue();if(b.index()<0) continue;
      if(b.index()>=bones.size()) throw new IllegalArgumentException(entry.getKey()+": bone index missing: "+b.index());
      var actual=bind(bones,b.index());
      if(!b.name().equals(actual.name()) || !b.parent().equals(actual.parent())) throw new IllegalArgumentException(entry.getKey()+": skeleton changed: "+b.name());
      if(!SceneModelProfile.customBinding(entry.getKey()) && entry.getKey().endsWith("Ik")!=bones.get(b.index()).ik()) throw new IllegalArgumentException(entry.getKey()+": IK/FK bone mismatch: "+b.name());
    }
  }
  public static Map<String,SceneModelProfile.BoneBinding> suggest(List<Bone> bones) {
    String[][] aliases={
      {"root","全ての親"},{"hips","センター","下半身","pelvis"},{"spine","上半身"},{"chest","上半身2","胸"},{"neck","首"},{"head","頭"},
      {"leftUpperArm","左腕","leftarm"},{"leftLowerArm","左ひじ","leftforearm"},{"leftHand","左手首"},
      {"rightUpperArm","右腕","rightarm"},{"rightLowerArm","右ひじ","rightforearm"},{"rightHand","右手首"},
      {"leftUpperLeg","左足","leftthigh"},{"leftLowerLeg","左ひざ","leftcalf"},{"leftFoot","左足首"},{"leftToes","左つま先"},
      {"rightUpperLeg","右足","rightthigh"},{"rightLowerLeg","右ひざ","rightcalf"},{"rightFoot","右足首"},{"rightToes","右つま先"},
      {"leftLegIk","左足IK"},{"rightLegIk","右足IK"},{"leftToeIk","左つま先IK"},{"rightToeIk","右つま先IK"}};
    var result=new LinkedHashMap<String,SceneModelProfile.BoneBinding>();var used=new HashSet<Integer>();
    for(var names:aliases) {
      var accepted=new HashSet<String>();for(String n:names) accepted.add(normalize(n));
      var found=bones.stream().filter(b->b.ik()==names[0].endsWith("Ik"))
          .filter(b->accepted.contains(normalize(b.name())) || accepted.contains(normalize(b.alternate()))).toList();
      if(found.size()==1 && used.add(found.get(0).index())) result.put(names[0],bind(bones,found.get(0).index()));
    }
    return Map.copyOf(result);
  }
  public static String normalize(String name) { return Normalizer.normalize(name,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]",""); }
}
