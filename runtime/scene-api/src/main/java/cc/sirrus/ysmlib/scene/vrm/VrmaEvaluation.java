package cc.sirrus.ysmlib.scene.vrm;

import cc.sirrus.ysmlib.scene.CompatibilityReport;
import java.util.Map;

/** Rest-pose aware FK retargeting; the returned avatar frame is ready for the same SpringBone player. */
public interface VrmaEvaluation extends VrmPlayback.Source {
  record Settings(Double hipsTranslationScale,Map<String,String> expressionBindings) {
    public static Settings automatic() { return new Settings(null,Map.of()); }
    public Settings {
      if(hipsTranslationScale!=null && (!Double.isFinite(hipsTranslationScale) || hipsTranslationScale<=0)) throw new IllegalArgumentException("Invalid hips translation scale");
      expressionBindings=Map.copyOf(expressionBindings);
    }
  }
  @Override default VrmEvaluation.Frame evaluate(double seconds) { return evaluate(0,seconds); }
  VrmEvaluation.Frame evaluate(int animation,double seconds);
  CompatibilityReport compatibility();
}
