package cc.sirrus.ysmlib.scene.bvh;

import cc.sirrus.ysmlib.scene.*;

/** Original Euler channels are evaluated directly; multiple turns must not collapse to shortest-arc quaternions. */
public interface BvhEvaluation {
  enum Sampling { STEP, LINEAR_CHANNELS }
  SceneAsset scene();
  ScenePose evaluate(double seconds,Sampling sampling);
}
