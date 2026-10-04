package cc.sirrus.ysmlib.scene;

import java.util.Objects;

/** Source clip metadata for selection/preview; an entry alone does not imply it can bind to the package model. */
public record SceneAnimation(ScenePackagePlayback.Selection selection,String name,String sourcePath,
                             AnimationPreview.Range range,double sourceFramesPerSecond) {
    public SceneAnimation {
        Objects.requireNonNull(selection);Objects.requireNonNull(name);Objects.requireNonNull(sourcePath);Objects.requireNonNull(range);
        if(!Double.isFinite(sourceFramesPerSecond) || sourceFramesPerSecond<0) throw new IllegalArgumentException("Invalid source frame rate");
    }
}
