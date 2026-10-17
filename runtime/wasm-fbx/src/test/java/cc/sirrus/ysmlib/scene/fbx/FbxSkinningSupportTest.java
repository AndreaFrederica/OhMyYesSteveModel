package cc.sirrus.ysmlib.scene.fbx;

import cc.sirrus.ysmlib.scene.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FbxSkinningSupportTest {
  @Test void reorderedClustersAreEquivalent() {
    var mesh = mesh();
    var a = skin(List.of(cluster(10, 0.75), cluster(11, 0.25)),
        List.of(new FbxDocument.SkinWeight(0, .75), new FbxDocument.SkinWeight(1, .25)));
    // Same semantic influences, with cluster and weight order reversed.
    var b = skin(List.of(cluster(11, 0.25), cluster(10, 0.75)),
        List.of(new FbxDocument.SkinWeight(1, .75), new FbxDocument.SkinWeight(0, .25)));
    assertTrue(FbxSkinningSupport.equivalent(mesh, a, b));
  }

  @Test void differentWeightsAreRejected() {
    var mesh = mesh();
    var a = skin(List.of(cluster(10, 0.75), cluster(11, 0.25)),
        List.of(new FbxDocument.SkinWeight(0, .75), new FbxDocument.SkinWeight(1, .25)));
    var b = skin(List.of(cluster(10, 0.5), cluster(11, 0.5)),
        List.of(new FbxDocument.SkinWeight(0, .5), new FbxDocument.SkinWeight(1, .5)));
    assertFalse(FbxSkinningSupport.equivalent(mesh, a, b));
  }

  private static FbxDocument.Mesh mesh() {
    return new FbxDocument.Mesh(1, new DoubleData(0, 0, 0), DoubleData.EMPTY, new IntData(0),
        List.of(), List.of(), List.of(), new IntData(100, 101), new IntData(), 0, 0, new IntData(),
        DoubleData.EMPTY, DoubleData.EMPTY, false);
  }

  private static FbxDocument.Cluster cluster(int bone, double weight) {
    var matrix = new DoubleData(1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0);
    return new FbxDocument.Cluster(bone, bone, matrix, matrix, matrix, "Normalize", new IntData(0), new DoubleData(weight));
  }

  private static FbxDocument.Skin skin(List<FbxDocument.Cluster> clusters, List<FbxDocument.SkinWeight> weights) {
    return new FbxDocument.Skin(99, 0, clusters, List.of(new FbxDocument.SkinVertex(0, weights.size(), 0)), weights);
  }
}
