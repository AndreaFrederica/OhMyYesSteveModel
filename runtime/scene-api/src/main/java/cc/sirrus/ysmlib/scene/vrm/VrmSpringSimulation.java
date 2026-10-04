package cc.sirrus.ysmlib.scene.vrm;

import cc.sirrus.ysmlib.scene.*;
import java.util.List;

/** Isolated managed SpringBone state. A fixed-step player owns time; sampling never advances the solver. */
public interface VrmSpringSimulation {
  interface Snapshot {}
  NodeConstraintEvaluation.Frame reset(ScenePose pose,List<Rotation> localRotations);
  NodeConstraintEvaluation.Frame step(ScenePose pose,List<Rotation> localRotations,double seconds);
  NodeConstraintEvaluation.Frame sample(ScenePose pose,List<Rotation> localRotations);
  Snapshot snapshot();
  void restore(Snapshot snapshot);
}
