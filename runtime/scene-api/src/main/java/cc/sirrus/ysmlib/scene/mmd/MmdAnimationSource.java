package cc.sirrus.ysmlib.scene.mmd;

import cc.sirrus.ysmlib.scene.*;
import java.util.List;
import java.util.Objects;

/** Immutable animation program. Sampling any time is independent of earlier queries. */
@FunctionalInterface
public interface MmdAnimationSource {
  record Sample(AnimationFrame animation,List<CompatibilityReport.Diagnostic> diagnostics) {
    public Sample { Objects.requireNonNull(animation);diagnostics=List.copyOf(diagnostics); }
  }
  Sample sample(double seconds);
}
