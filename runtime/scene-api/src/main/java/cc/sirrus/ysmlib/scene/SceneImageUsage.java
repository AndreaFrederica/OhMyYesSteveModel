package cc.sirrus.ysmlib.scene;

import java.util.Objects;

/** Material-owned sample interpretation, independent of image filename and embedded display profile. */
public record SceneImageUsage(Transfer transfer,Alpha alpha) {
  /** Conversion occurs before filtering/mipmap generation. SOURCE_NUMERIC preserves legacy shader arithmetic
   * on encoded RGB; unlike LINEAR it makes no claim that those values represent physical light. Alpha is linear. */
  public enum Transfer { LINEAR,SRGB,SOURCE_NUMERIC }
  /** RAW retains source association/data; STRAIGHT reconstructs opacity; OPAQUE reconstructs RGB and forces A=1. */
  public enum Alpha { RAW,STRAIGHT,OPAQUE }
  public SceneImageUsage { Objects.requireNonNull(transfer);Objects.requireNonNull(alpha); }
}
