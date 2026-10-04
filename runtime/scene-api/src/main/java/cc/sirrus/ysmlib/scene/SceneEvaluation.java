package cc.sirrus.ysmlib.scene;

import java.util.List;

/** Reusable scene evaluation plan, independent from Minecraft and the renderer. */
public interface SceneEvaluation {
  ScenePose evaluate(AnimationClip clip,double seconds);
  List<Matrix4> skinPalette(ScenePose pose,int meshNode);
}
