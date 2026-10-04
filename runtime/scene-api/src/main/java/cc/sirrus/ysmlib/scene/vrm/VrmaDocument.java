package cc.sirrus.ysmlib.scene.vrm;

import cc.sirrus.ysmlib.scene.*;
import java.util.*;

/** VRMC_vrm_animation 1.0. All glTF clips and the original gaze-origin metadata are retained. */
public record VrmaDocument(GltfDocument gltf,Map<String,Integer> humanBones,Map<String,Integer> expressions,
                           int lookAtNode,Vec3 lookAtOffset,CompatibilityReport compatibility) {
  public VrmaDocument { humanBones=Map.copyOf(humanBones);expressions=Map.copyOf(expressions); }
  public List<AnimationClip> animations() { return gltf.scene().animations(); }
}
