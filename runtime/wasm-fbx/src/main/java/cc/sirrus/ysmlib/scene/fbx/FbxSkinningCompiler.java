package cc.sirrus.ysmlib.scene.fbx;

import cc.sirrus.ysmlib.scene.IntData;
import cc.sirrus.ysmlib.scene.MeshAsset;
import cc.sirrus.ysmlib.scene.FloatData;
import java.util.*;

/**
 * Compiles the sparse FBX skin-cluster representation to the runtime's
 * unbounded influence layout.  FBX weights are stored per control vertex,
 * while render vertices are face corners, so the influence range is expanded
 * through the mesh controlVertices array without duplicating any attributes.
 */
public final class FbxSkinningCompiler {
  private FbxSkinningCompiler() {}

  /** Returns null for meshes without a supported skin deformer. */
  public static MeshAsset.Skinning compile(FbxDocument source, int meshIndex) {
    Objects.requireNonNull(source);
    if (meshIndex < 0 || meshIndex >= source.meshes().size()) throw new IndexOutOfBoundsException("meshIndex");
    var mesh = source.meshes().get(meshIndex);
    if (mesh.skins().size() == 0 || mesh.controlVertices().size() == 0) return null;
    // FBX has no defined composition rule for multiple FbxSkin objects.
    // Accept only exact semantic duplicates (including reordered clusters),
    // then compile the canonical first skin once instead of summing weights
    // from unrelated deformers.
    var selectedSkins = FbxSkinningSupport.uniqueSkinIndices(source, meshIndex);
    if (selectedSkins.isEmpty()) return null;
    int selectedSkinIndex = selectedSkins.get(0);
    var nodeByTyped = new HashMap<Integer,Integer>();
    var nodeByElement = new HashMap<Integer,Integer>();
    for (int i = 0; i < source.nodes().size(); i++) {
      var node = source.nodes().get(i);
      nodeByElement.put(node.element(), i);
      for (var element : source.elements()) if (element.id() == node.element()) nodeByTyped.put(element.typedId(), i);
    }
    var influences = new ArrayList<Map<Integer,Double>>(mesh.cornerCount());
    for (int corner = 0; corner < mesh.cornerCount(); corner++) {
      int control = mesh.controlVertices().get(corner);
      if (control < 0) throw new IllegalArgumentException("Negative FBX control vertex: " + control);
      var merged = new LinkedHashMap<Integer,Double>();
      {
        int skinIndex = selectedSkinIndex;
        if (skinIndex < 0 || skinIndex >= source.skins().size()) throw new IllegalArgumentException("FBX skin index outside document");
        var skin = source.skins().get(skinIndex);
        if (skin.method() == 2 || skin.method() == 3) {
          throw new IllegalArgumentException("FBX dual/blended-quaternion skinning requires quaternion backend");
        }
        if (control < skin.vertices().size()) {
          var vertex = skin.vertices().get(control);
          int begin = vertex.begin(), end = Math.addExact(begin, vertex.count());
          if (begin < 0 || end > skin.weights().size()) throw new IllegalArgumentException("FBX skin vertex range outside weights");
          for (int wi = begin; wi < end; wi++) {
            var weight = skin.weights().get(wi);
            if (weight.cluster() < 0 || weight.cluster() >= skin.clusters().size()) throw new IllegalArgumentException("FBX cluster index outside skin");
            add(merged, joint(source, nodeByTyped, nodeByElement, skin.clusters().get(weight.cluster())), weight.weight());
          }
        } else {
          // Some exporters omit the compact vertex/weight table. ufbx still
          // provides the original control-point arrays on every cluster.
          for (var cluster : skin.clusters()) {
            var vertices = cluster.rawVertices();
            for (int i = 0; i < vertices.size(); i++) if (vertices.get(i) == control) {
              if (i >= cluster.rawWeights().size()) throw new IllegalArgumentException("FBX raw cluster weight range outside data");
              add(merged, joint(source, nodeByTyped, nodeByElement, cluster), cluster.rawWeights().get(i));
            }
          }
        }
      }
      if (merged.isEmpty()) throw new IllegalArgumentException("FBX control vertex has no skin weight: " + control);
      double sum = merged.values().stream().mapToDouble(Double::doubleValue).sum();
      if (!(sum > 0) || !Double.isFinite(sum)) throw new IllegalArgumentException("FBX skin weights are not positive");
      merged.replaceAll((joint, weight) -> weight / sum);
      influences.add(merged);
    }
    var offsets = new int[influences.size() + 1];
    var joints = new ArrayList<Integer>();
    var weights = new ArrayList<Float>();
    for (int vertex = 0; vertex < influences.size(); vertex++) {
      offsets[vertex] = joints.size();
      for (var entry : influences.get(vertex).entrySet()) {
        joints.add(entry.getKey()); weights.add(entry.getValue().floatValue());
      }
    }
    offsets[influences.size()] = joints.size();
    var jointArray = joints.stream().mapToInt(Integer::intValue).toArray();
    var weightArray = new float[weights.size()];
    for (int i = 0; i < weightArray.length; i++) weightArray[i] = weights.get(i);
    return new MeshAsset.Skinning(IntData.owned(offsets), IntData.owned(jointArray),
        FloatData.owned(weightArray),
        Collections.nCopies(influences.size(), MeshAsset.Deform.LINEAR), FloatData.EMPTY);
  }

  private static void add(Map<Integer,Double> out, int joint, double weight) {
    if (joint < 0 || !Double.isFinite(weight) || weight < 0) throw new IllegalArgumentException("Invalid FBX skin influence");
    if (weight != 0) out.merge(joint, weight, Double::sum);
  }
  private static int joint(FbxDocument source, Map<Integer,Integer> typed, Map<Integer,Integer> elements, FbxDocument.Cluster cluster) {
    var byTyped = typed.get(cluster.bone());
    if (byTyped != null) return byTyped;
    var byElement = elements.get(cluster.bone());
    if (byElement != null) return byElement;
    throw new IllegalArgumentException("FBX cluster bone does not resolve to a node: " + cluster.bone());
  }
  private static int skinIndex(FbxDocument source,int id) {
    for(int i=0;i<source.skins().size();i++) if(source.skins().get(i).element()==id)return i;
    for(var element:source.elements()) if(element.typedId()==id) for(int i=0;i<source.skins().size();i++) if(source.skins().get(i).element()==element.id())return i;
    if(id>=0&&id<source.skins().size())return id;
    throw new IllegalArgumentException("FBX skin deformer does not resolve: "+id);
  }
}
