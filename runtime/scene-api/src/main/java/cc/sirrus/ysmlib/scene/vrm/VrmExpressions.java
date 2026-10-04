package cc.sirrus.ysmlib.scene.vrm;

import cc.sirrus.ysmlib.scene.*;
import java.util.*;

/** Stateless expression composition. Host rendering consumes the resulting material parameters and texture matrices. */
public interface VrmExpressions {
  /** Legacy shader keys (such as _Color) retain their source color space; they never replace glTF linear factors. */
  record MaterialState(Map<String,FloatData> parameters,Map<String,FloatData> textureTransforms) {
    public MaterialState { parameters=Map.copyOf(parameters);textureTransforms=Map.copyOf(textureTransforms); }
  }
  record Frame(Map<String,Float> effectiveWeights,List<FloatData> morphWeights,List<MaterialState> materials) {
    public Frame { effectiveWeights=Map.copyOf(effectiveWeights);morphWeights=List.copyOf(morphWeights);materials=List.copyOf(materials); }
  }
  Frame evaluate(Map<String,Float> weights);
  /** Custom expressions can opt into procedural override categories without rewriting the imported document. */
  Frame evaluate(Map<String,Float> weights,Map<String,VrmDocument.Category> customCategories);
}
