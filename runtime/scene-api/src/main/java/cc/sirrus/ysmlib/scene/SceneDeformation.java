package cc.sirrus.ysmlib.scene;

import java.util.*;

/** Instance-owned compiled deformation; returned attributes remain immutable across subsequent samples. */
@FunctionalInterface
public interface SceneDeformation {
  Map<String,MeshAsset.Attribute> deform(List<Matrix4> palette,FloatData morphWeights);
}
