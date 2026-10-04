package cc.sirrus.ysmlib.scene.vrm;

import cc.sirrus.ysmlib.scene.MeshAsset;
import java.util.*;

/** Immutable per-node visibility. Hosts still select scene roots and own camera layers. */
public record VrmViewGeometry(List<NodeView> nodes) {
  /** Source primitive index survives filtering; attributes, morphs and skinning retain source vertex indices. */
  public record Draw(int sourcePrimitive,MeshAsset.Primitive geometry) {
    public Draw { if(sourcePrimitive<0) throw new IllegalArgumentException("Invalid primitive index");Objects.requireNonNull(geometry); }
  }
  /** Empty draw lists mean hidden. Third-person source topology is never changed. */
  public record NodeView(int node,List<Draw> firstPerson,List<Draw> thirdPerson) {
    public NodeView { if(node<0) throw new IllegalArgumentException("Invalid node index");firstPerson=List.copyOf(firstPerson);thirdPerson=List.copyOf(thirdPerson); }
  }
  public VrmViewGeometry { nodes=List.copyOf(nodes); }
}
