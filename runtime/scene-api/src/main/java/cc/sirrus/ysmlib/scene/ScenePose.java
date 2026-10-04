package cc.sirrus.ysmlib.scene;

import java.util.List;

/** One owned evaluated pose, shared by renderer, preview and extension consumers. */
public record ScenePose(double seconds,List<Matrix4> localMatrices,List<Matrix4> globalMatrices,List<FloatData> morphWeights,
                        AnimationFrame extensionChannels) {
  public ScenePose { localMatrices=List.copyOf(localMatrices);globalMatrices=List.copyOf(globalMatrices);morphWeights=List.copyOf(morphWeights); }
}
