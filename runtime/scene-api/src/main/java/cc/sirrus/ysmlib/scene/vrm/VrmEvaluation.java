package cc.sirrus.ysmlib.scene.vrm;

import cc.sirrus.ysmlib.scene.*;
import java.util.*;

/** Avatar evaluation before SpringBone simulation: animation, gaze, expressions, then node constraints. */
public interface VrmEvaluation {
  sealed interface Gaze permits Angles,Target {}
  /** Degrees in face space: positive yaw looks left; positive pitch looks down, for both profiles. */
  record Angles(double yaw,double pitch) implements Gaze {
    public Angles { if(!Double.isFinite(yaw) || !Double.isFinite(pitch)) throw new IllegalArgumentException("Non-finite gaze"); }
  }
  record Target(Vec3 position) implements Gaze { public Target { Objects.requireNonNull(position); } }
  record Input(Map<String,Float> expressions,Map<String,VrmDocument.Category> categories,Gaze gaze) {
    public static final Input NONE=new Input(Map.of(),Map.of(),null);
    public Input { expressions=Map.copyOf(expressions);categories=Map.copyOf(categories); }
  }
  record Frame(ScenePose pose,List<Rotation> localRotations,List<VrmExpressions.MaterialState> materials,Map<String,Float> expressions,Angles gaze) {
    public Frame { localRotations=List.copyOf(localRotations);materials=List.copyOf(materials);expressions=Map.copyOf(expressions); }
  }
  Frame evaluate(AnimationClip animation,double seconds,Input input);
}
