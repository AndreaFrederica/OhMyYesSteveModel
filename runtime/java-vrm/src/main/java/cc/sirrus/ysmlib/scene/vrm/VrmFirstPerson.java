package cc.sirrus.ysmlib.scene.vrm;

import cc.sirrus.ysmlib.scene.*;
import java.util.*;
import static cc.sirrus.ysmlib.scene.vrm.VrmViewGeometry.*;

/** Compiles camera visibility without modifying a mesh shared by other nodes or views. */
public final class VrmFirstPerson {
  public VrmViewGeometry compile(VrmDocument document) {
    return compile(document,document.scene());
  }
  /** Prepared surface geometry is filtered only after full-mesh normal/tangent generation. */
  public VrmViewGeometry compile(VrmDocument document,SceneAsset scene) {
    if(!scene.nodes().equals(document.scene().nodes()) || !scene.skins().equals(document.scene().skins()))
      throw new IllegalArgumentException("First-person geometry must preserve source node and skin domains");
    var fp=document.firstPerson();
    int head=fp==null?document.humanBones().getOrDefault("head",-1):fp.bone();
    if(head<0 || head>=scene.nodes().size()) throw new IllegalArgumentException("Missing first-person bone");
    boolean[] hidden=new boolean[scene.nodes().size()];var pending=new ArrayDeque<Integer>();pending.add(head);
    while(!pending.isEmpty()) { int n=pending.remove();hidden[n]=true;for(int c:scene.nodes().get(n).children().copy()) pending.add(c); }
    var annotations=new HashMap<Integer,String>();
    if(fp!=null) for(var a:fp.annotations()) {
      if(a.node()<0 || a.node()>=hidden.length || scene.nodes().get(a.node()).mesh()<0 || annotations.putIfAbsent(a.node(),a.type())!=null)
        throw new IllegalArgumentException("Invalid or duplicate first-person node annotation");
    }
    var views=new ArrayList<NodeView>();
    for(int n=0;n<scene.nodes().size();n++) {
      var node=scene.nodes().get(n);if(node.mesh()<0) continue;
      String type=annotations.getOrDefault(n,"auto");
      if(!Set.of("auto","both","firstPersonOnly","thirdPersonOnly").contains(type)) throw new IllegalArgumentException("Invalid first-person annotation: "+type);
      var first=new ArrayList<Draw>();var third=new ArrayList<Draw>();var primitives=scene.meshes().get(node.mesh()).primitives();
      for(int p=0;p<primitives.size();p++) {
        var primitive=primitives.get(p);var draw=new Draw(p,primitive);
        if(!type.equals("firstPersonOnly")) third.add(draw);
        if(type.equals("thirdPersonOnly")) continue;
        if(!type.equals("auto")) { first.add(draw);continue; }
        if(node.skin()<0) { if(!hidden[n]) first.add(draw);continue; }
        var filtered=filter(primitive,scene.skins().get(node.skin()),hidden);
        if(filtered!=null) first.add(filtered==primitive?draw:new Draw(p,filtered));
      }
      views.add(new NodeView(n,first,third));
    }
    return new VrmViewGeometry(views);
  }

  private static MeshAsset.Primitive filter(MeshAsset.Primitive source,SceneAsset.Skin skin,boolean[] hidden) {
    var influences=source.skinning();if(influences==null) throw new IllegalArgumentException("Skinned first-person primitive has no weights");
    boolean[] erased=new boolean[source.vertexCount()];
    for(int v=0;v<erased.length;v++) for(int i=influences.offsets().get(v);i<influences.offsets().get(v+1);i++) {
      int joint=influences.joints().get(i);if(joint>=skin.joints().size()) throw new IllegalArgumentException("First-person vertex joint exceeds skin");
      if(influences.weights().get(i)>0 && hidden[skin.joints().get(joint)]) erased[v]=true;
    }
    var indices=source.indices();boolean affected=false;
    for(int i=0;i<indices.size();i++) if(erased[indices.get(i)]) { affected=true;break; }
    if(!affected) return source;
    var output=new Indices();var topology=source.topology();int count=indices.size();
    switch(topology) {
      case POINTS -> { for(int i=0;i<count;i++) output.add(erased,indices.get(i)); }
      case LINES -> { for(int i=0;i<count;i+=2) output.add(erased,indices.get(i),indices.get(i+1)); }
      case LINE_STRIP,LINE_LOOP -> {
        for(int i=1;i<count;i++) output.add(erased,indices.get(i-1),indices.get(i));
        if(topology==MeshAsset.Topology.LINE_LOOP && count>1) output.add(erased,indices.get(count-1),indices.get(0));
        topology=MeshAsset.Topology.LINES;
      }
      case TRIANGLES -> { for(int i=0;i<count;i+=3) output.add(erased,indices.get(i),indices.get(i+1),indices.get(i+2)); }
      case TRIANGLE_STRIP -> {
        for(int i=2;i<count;i++) output.add(erased,indices.get(i%2==0?i-2:i-1),indices.get(i%2==0?i-1:i-2),indices.get(i));
        topology=MeshAsset.Topology.TRIANGLES;
      }
      case TRIANGLE_FAN -> {
        for(int i=2;i<count;i++) output.add(erased,indices.get(0),indices.get(i-1),indices.get(i));
        topology=MeshAsset.Topology.TRIANGLES;
      }
    }
    if(output.size==0) return null;
    return new MeshAsset.Primitive(topology,source.attributes(),new IntData(Arrays.copyOf(output.values,output.size)),source.material(),source.skinning(),source.morphs());
  }
  /** Primitive-at-a-time removal also defines point/line behavior; strips never bridge a removed segment. */
  private static final class Indices {
    int[] values=new int[16];int size;
    void add(boolean[] erased,int... vertices) {
      for(int v:vertices) if(erased[v]) return;
      int needed=Math.addExact(size,vertices.length);
      if(needed>values.length) values=Arrays.copyOf(values,Math.max(needed,Math.addExact(values.length,values.length/2)));
      for(int v:vertices) values[size++]=v;
    }
  }
}
