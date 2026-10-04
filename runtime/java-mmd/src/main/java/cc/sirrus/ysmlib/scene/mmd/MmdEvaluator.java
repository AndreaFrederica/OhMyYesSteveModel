package cc.sirrus.ysmlib.scene.mmd;

import cc.sirrus.ysmlib.scene.*;
import java.util.*;

/** Isolated no-physics pose queries; the same rig stages are used by simulation sessions. */
public final class MmdEvaluator implements MmdEvaluation {
  private final PmxDocument source;
  private final List<PmdRuntimeProfile.Constraint> pmdConstraints;
  public MmdEvaluator(PmxDocument source) { this.source=Objects.requireNonNull(source);pmdConstraints=null; }
  public MmdEvaluator(PmdDocument source) { var profile=new PmdRuntimeProfile(source);this.source=profile.asset;pmdConstraints=profile.constraints; }
  @Override public MmdPose evaluate(AnimationFrame frame,Map<Integer,Pose> outsideParents) {
    var rig=new MmdRig(source,pmdConstraints);rig.begin(frame,outsideParents);rig.update(false);rig.update(true);return rig.pose();
  }
}
