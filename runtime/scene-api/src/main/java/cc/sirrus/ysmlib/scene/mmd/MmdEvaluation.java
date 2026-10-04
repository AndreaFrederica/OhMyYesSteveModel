package cc.sirrus.ysmlib.scene.mmd;

import cc.sirrus.ysmlib.scene.*;
import java.util.Map;

/** Stateless pose evaluation. Physics and history-dependent playback belong to a timeline session. */
public interface MmdEvaluation {
  /** Outside-parent entries are model-space rigid delta transforms keyed by the source PMX key. */
  MmdPose evaluate(AnimationFrame animation,Map<Integer,Pose> outsideParents);
  default MmdPose evaluate(AnimationFrame animation) { return evaluate(animation,Map.of()); }
}
