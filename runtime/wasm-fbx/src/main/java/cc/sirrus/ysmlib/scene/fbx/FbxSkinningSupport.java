package cc.sirrus.ysmlib.scene.fbx;

import java.util.*;

/**
 * Validates the skin topology attached to a FBX mesh.
 *
 * FBX does not define a general operation for stacking two FbxSkin
 * deformers.  Unity/VRChat collapse a mesh to one bind-pose/weight table, so
 * the only lossless case we can accept is a duplicate skin (including skins
 * whose cluster arrays were emitted in a different order).  This helper is
 * deliberately source-only and bounded by the already parsed mesh tables.
 */
final class FbxSkinningSupport {
  private static final double EPSILON = 1e-9;

  private FbxSkinningSupport() {}

  /** Returns the first skin after removing semantically equivalent duplicates. */
  static List<Integer> uniqueSkinIndices(FbxDocument source, int meshIndex) {
    var mesh = source.meshes().get(meshIndex);
    if (mesh.skins().size() < 2) {
      if (mesh.skins().size() == 0) return List.of();
      return List.of(resolve(source, mesh.skins().get(0)));
    }
    int first = resolve(source, mesh.skins().get(0));
    var result = new ArrayList<Integer>(1);
    result.add(first);
    var base = source.skins().get(first);
    for (int i = 1; i < mesh.skins().size(); i++) {
      int candidate = resolve(source, mesh.skins().get(i));
      if (!equivalent(mesh, base, source.skins().get(candidate))) {
        throw new IllegalArgumentException("FBX multiple non-equivalent skin deformers require explicit source/profile selection");
      }
    }
    return List.copyOf(result);
  }

  static void validate(FbxDocument source, int meshIndex) {
    uniqueSkinIndices(source, meshIndex);
  }

  private static int resolve(FbxDocument source, int id) {
    for (int i = 0; i < source.skins().size(); i++) if (source.skins().get(i).element() == id) return i;
    for (var element : source.elements()) if (element.typedId() == id)
      for (int i = 0; i < source.skins().size(); i++) if (source.skins().get(i).element() == element.id()) return i;
    if (id >= 0 && id < source.skins().size()) return id;
    throw new IllegalArgumentException("FBX skin deformer does not resolve: " + id);
  }

  static boolean equivalent(FbxDocument.Mesh mesh,
      FbxDocument.Skin a, FbxDocument.Skin b) {
    if (a.method() != b.method()) return false;
    if (a.clusters().size() != b.clusters().size()) return false;
    var used = new boolean[b.clusters().size()];
    for (var cluster : a.clusters()) {
      int match = -1;
      for (int i = 0; i < b.clusters().size(); i++) {
        if (!used[i] && clusterEquivalent(cluster, b.clusters().get(i))) { match = i; break; }
      }
      if (match < 0) return false;
      used[match] = true;
    }
    var controls = new LinkedHashSet<Integer>();
    for (int i = 0; i < mesh.controlVertices().size(); i++) {
      int control = mesh.controlVertices().get(i);
      if (control < 0) return false;
      controls.add(control);
    }
    for (int control : controls) {
      if (!sameInfluences(a, b, control) || !sameDualWeight(a, b, control)) return false;
    }
    return true;
  }

  private static boolean clusterEquivalent(FbxDocument.Cluster a, FbxDocument.Cluster b) {
    return a.bone() == b.bone() && Objects.equals(a.linkMode(), b.linkMode())
        && same(a.geometryToBone(), b.geometryToBone())
        && same(a.meshToBone(), b.meshToBone())
        && same(a.bindWorld(), b.bindWorld());
  }

  private static boolean sameInfluences(FbxDocument.Skin a, FbxDocument.Skin b, int control) {
    var left = influences(a, control);
    var right = influences(b, control);
    if (!left.keySet().equals(right.keySet())) return false;
    for (int bone : left.keySet()) if (!close(left.get(bone), right.get(bone))) return false;
    return true;
  }

  private static Map<Integer, Double> influences(FbxDocument.Skin skin, int control) {
    var out = new HashMap<Integer, Double>();
    if (control >= 0 && control < skin.vertices().size()) {
      var vertex = skin.vertices().get(control);
      long end = (long) vertex.begin() + vertex.count();
      if (vertex.begin() < 0 || end > skin.weights().size()) return Map.of(Integer.MIN_VALUE, Double.NaN);
      for (int i = vertex.begin(); i < end; i++) {
        var weight = skin.weights().get(i);
        if (weight.cluster() < 0 || weight.cluster() >= skin.clusters().size()) return Map.of(Integer.MIN_VALUE, Double.NaN);
        int bone = skin.clusters().get(weight.cluster()).bone();
        out.merge(bone, weight.weight(), Double::sum);
      }
      return out;
    }
    // Fallback for exporters which omit the compact vertex table.
    for (var cluster : skin.clusters()) {
      for (int i = 0; i < cluster.rawVertices().size(); i++) {
        if (cluster.rawVertices().get(i) == control) {
          if (i >= cluster.rawWeights().size()) return Map.of(Integer.MIN_VALUE, Double.NaN);
          out.merge(cluster.bone(), cluster.rawWeights().get(i), Double::sum);
        }
      }
    }
    return out;
  }

  private static boolean sameDualWeight(FbxDocument.Skin a, FbxDocument.Skin b, int control) {
    double left = control < a.vertices().size() ? a.vertices().get(control).dualQuaternionWeight() : 0;
    double right = control < b.vertices().size() ? b.vertices().get(control).dualQuaternionWeight() : 0;
    return close(left, right);
  }

  private static boolean same(cc.sirrus.ysmlib.scene.DoubleData a, cc.sirrus.ysmlib.scene.DoubleData b) {
    if (a.size() != b.size()) return false;
    for (int i = 0; i < a.size(); i++) if (!close(a.get(i), b.get(i))) return false;
    return true;
  }

  private static boolean close(double a, double b) {
    return Double.isFinite(a) && Double.isFinite(b) && Math.abs(a - b) <= EPSILON * (1 + Math.max(Math.abs(a), Math.abs(b)));
  }
}
