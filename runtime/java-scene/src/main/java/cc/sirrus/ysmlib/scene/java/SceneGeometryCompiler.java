package cc.sirrus.ysmlib.scene.java;

import cc.sirrus.ysmlib.scene.*;
import java.util.*;

/** Selected scene roots, per-instance skin palettes and per-node view geometry meet only at draw projection. */
public final class SceneGeometryCompiler implements SceneGeometry {
  private final SceneAsset source;
  private final SceneEvaluator evaluator;
  private final Deformer deformer=new Deformer();
  private final List<Integer> nodes;
  private final Map<Integer,List<MeshAsset.Primitive>> views;

  /** Scene -1 explicitly includes every root; nonnegative values select the corresponding source scene. */
  public SceneGeometryCompiler(SceneAsset source,int scene) { this(source,scene,Map.of()); }
  /** Overrides identify exact node instances. An empty list hides that instance without changing the shared mesh. */
  public SceneGeometryCompiler(SceneAsset source,int scene,Map<Integer,List<MeshAsset.Primitive>> overrides) {
    this.source=Objects.requireNonNull(source);evaluator=new SceneEvaluator(source);
    if(scene< -1 || scene>=source.scenes().size()) throw new IllegalArgumentException("Invalid geometry scene selection");
    var selected=new LinkedHashSet<Integer>();var pending=new ArrayDeque<Integer>();
    if(scene==-1) for(int n=0;n<source.nodes().size();n++) pending.add(n);
    else for(int n:source.scenes().get(scene).roots().copy()) pending.add(n);
    while(!pending.isEmpty()) { int n=pending.remove();if(!selected.add(n)) continue;for(int c:source.nodes().get(n).children().copy()) pending.add(c); }
    nodes=selected.stream().filter(n->source.nodes().get(n).mesh()!=-1).toList();
    var copy=new LinkedHashMap<Integer,List<MeshAsset.Primitive>>();
    overrides.forEach((n,primitives)-> {
      if(n<0 || n>=source.nodes().size() || source.nodes().get(n).mesh()==-1) throw new IllegalArgumentException("View override requires a mesh node");
      copy.put(n,List.copyOf(primitives));
    });views=Map.copyOf(copy);
  }
  @Override public GeometryFrame compile(ScenePose pose) {
    Objects.requireNonNull(pose);int count=source.nodes().size();
    if(pose.globalMatrices().size()!=count || pose.localMatrices().size()!=count || pose.morphWeights().size()!=count)
      throw new IllegalArgumentException("Geometry pose has a different node layout");
    var draws=new ArrayList<GeometryFrame.Draw>();
    for(int n:nodes) {
      var node=source.nodes().get(n);var mesh=source.meshes().get(node.mesh());var primitives=views.getOrDefault(n,mesh.primitives());
      if(primitives.isEmpty()) continue;
      var palette=evaluator.skinPalette(pose,n);var weights=pose.morphWeights().get(n);var output=new ArrayList<MeshAsset.Primitive>();
      for(var primitive:primitives) {
        // A shared source mesh may have both rigid and skinned instances. Skin attributes are active only for the latter.
        var input=node.skin()!=-1?primitive:new MeshAsset.Primitive(primitive.topology(),primitive.attributes(),primitive.indices(),primitive.material(),null,primitive.morphs());
        // glTF permits primitives with no morphs in a mesh whose other primitives have targets.
        var attributes=deformer.deform(input,palette,input.morphs().isEmpty()?FloatData.EMPTY:weights,Deformer.NormalMode.INVERSE_TRANSPOSE);
        output.add(new MeshAsset.Primitive(primitive.topology(),attributes,primitive.indices(),primitive.material(),null,List.of()));
      }
      draws.add(new GeometryFrame.Draw(n,node.mesh(),pose.globalMatrices().get(n),new MeshAsset(mesh.name(),output,FloatData.EMPTY),true));
    }
    return new GeometryFrame(pose.seconds(),source.coordinates(),draws);
  }
}
