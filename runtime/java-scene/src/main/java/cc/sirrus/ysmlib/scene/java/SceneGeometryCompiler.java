package cc.sirrus.ysmlib.scene.java;

import cc.sirrus.ysmlib.scene.*;
import java.util.*;

/** Selected scene roots, per-instance skin palettes and per-node view geometry meet only at draw projection. */
public final class SceneGeometryCompiler implements SceneGeometry {
  private final SceneAsset source;
  private final SceneEvaluator evaluator;
  private final Map<Integer,List<CompiledSceneDeformer>> deformers;
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
    var compiled=new HashMap<Integer,List<CompiledSceneDeformer>>();
    for(int n:nodes){var node=source.nodes().get(n);var inputs=views.getOrDefault(n,source.meshes().get(node.mesh()).primitives());
      compiled.put(n,inputs.stream().map(p->new CompiledSceneDeformer(node.skin()!=-1?p:new MeshAsset.Primitive(p.topology(),p.attributes(),p.indices(),p.material(),null,p.morphs()))).toList());}
    deformers=Map.copyOf(compiled);
  }
  @Override public GeometryFrame compile(ScenePose pose) {
    return compile(pose,false);
  }
  /** Explicit host opt-in: retain skin/morph inputs for GPU resolution from this exact pose. */
  public GeometryFrame compile(ScenePose pose,boolean deferSkinning) {
    Objects.requireNonNull(pose);int count=source.nodes().size();
    if(pose.globalMatrices().size()!=count || pose.localMatrices().size()!=count || pose.morphWeights().size()!=count)
      throw new IllegalArgumentException("Geometry pose has a different node layout");
    var draws=new ArrayList<GeometryFrame.Draw>();
    for(int n:nodes) {
      var node=source.nodes().get(n);var mesh=source.meshes().get(node.mesh());var primitives=views.getOrDefault(n,mesh.primitives());
      if(primitives.isEmpty()) continue;
      if(deferSkinning&&node.skin()!=-1){draws.add(new GeometryFrame.Draw(n,node.mesh(),pose.globalMatrices().get(n),new MeshAsset(mesh.name(),primitives,mesh.defaultMorphWeights()),true));continue;}
      var palette=evaluator.skinPalette(pose,n);var weights=pose.morphWeights().get(n);var output=new ArrayList<MeshAsset.Primitive>();
      for(int primitiveIndex=0;primitiveIndex<primitives.size();primitiveIndex++) {var primitive=primitives.get(primitiveIndex);
        // A shared source mesh may have both rigid and skinned instances. Skin attributes are active only for the latter.
        // glTF permits primitives with no morphs in a mesh whose other primitives have targets.
        var attributes=deformers.get(n).get(primitiveIndex).deform(palette,primitive.morphs().isEmpty()?FloatData.EMPTY:weights);
        output.add(new MeshAsset.Primitive(primitive.topology(),attributes,primitive.indices(),primitive.material(),null,List.of()));
      }
      draws.add(new GeometryFrame.Draw(n,node.mesh(),pose.globalMatrices().get(n),new MeshAsset(mesh.name(),output,FloatData.EMPTY),true));
    }
    return new GeometryFrame(pose.seconds(),source.coordinates(),draws);
  }
}
