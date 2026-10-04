package cc.sirrus.ysmlib.scene.mmd;

import cc.sirrus.ysmlib.scene.*;
import java.util.*;

/** An owned MMD evaluation result in source model coordinates; reading it never advances simulation. */
public record MmdPose(AnimationFrame animation,boolean visible,List<Pose> bones,List<Matrix4> palette,
                      MmdMorphState morphs,List<CompatibilityReport.Diagnostic> diagnostics) {
  public MmdPose { bones=List.copyOf(bones);palette=List.copyOf(palette);diagnostics=List.copyOf(diagnostics); }
}
