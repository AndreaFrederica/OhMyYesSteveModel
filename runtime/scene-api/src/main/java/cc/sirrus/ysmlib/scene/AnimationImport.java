package cc.sirrus.ysmlib.scene;

import java.util.Objects;

public record AnimationImport(AnimationClip clip,CompatibilityReport compatibility) {
  public AnimationImport { Objects.requireNonNull(clip);Objects.requireNonNull(compatibility); }
}
