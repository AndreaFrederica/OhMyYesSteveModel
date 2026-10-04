package cc.sirrus.ysmlib.scene.mmd;

import cc.sirrus.ysmlib.scene.*;
import java.util.*;

/** Embedded project animation, separate from external model/media resolution and scene physics ownership. */
public record PmmAnimationSet(List<AnimationImport> models,List<AnimationImport> accessories,AnimationImport scene) {
  public PmmAnimationSet { models=List.copyOf(models);accessories=List.copyOf(accessories);Objects.requireNonNull(scene); }
}
