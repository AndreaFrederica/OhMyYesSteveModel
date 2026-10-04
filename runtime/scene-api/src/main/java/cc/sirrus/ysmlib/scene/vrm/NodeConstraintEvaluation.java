package cc.sirrus.ysmlib.scene.vrm;

import cc.sirrus.ysmlib.scene.*;
import java.util.List;

/** Local rotations are supplied explicitly: decomposing a world matrix can lose shear or scale signs. */
public interface NodeConstraintEvaluation {
  record Frame(ScenePose pose,List<Rotation> localRotations) { public Frame { localRotations=List.copyOf(localRotations); } }
  Frame evaluate(ScenePose input,List<Rotation> localRotations);
}
