package cc.sirrus.ysmlib.scene;

import java.util.List;
import java.util.Objects;

public record AnimationClip(String name,List<Track> tracks,double sourceFramesPerSecond) {
  public enum Property {
    TRANSLATION,ROTATION,SCALE,MORPH_WEIGHTS,MATERIAL,DISPLAY,IK_ENABLED,
    CAMERA_TARGET,CAMERA_DISTANCE,CAMERA_EULER,CAMERA_FOV,CAMERA_PERSPECTIVE,
    LIGHT_COLOR,LIGHT_DIRECTION,SHADOW_MODE,SHADOW_DISTANCE,
    BONE_PHYSICS_ENABLED,OUTSIDE_PARENT,CAMERA_PARENT,ACCESSORY_PARENT,ACCESSORY_EULER,OPACITY,
    GRAVITY_ACCELERATION,GRAVITY_DIRECTION,GRAVITY_NOISE,GRAVITY_NOISE_ENABLED
  }
  /** targetIndex=-1 denotes an external name binding (for example a VMD bone/morph name). */
  public record Track(Property property,int targetIndex,String binding,String component,AnimationCurve curve) {
    public Track {
      Objects.requireNonNull(property);Objects.requireNonNull(curve);
      binding=Objects.requireNonNullElse(binding,"");component=Objects.requireNonNullElse(component,"");
      if(targetIndex< -1) throw new IllegalArgumentException("Invalid animation target");
    }
  }
  public AnimationClip {
    name=Objects.requireNonNullElse(name,"");tracks=List.copyOf(tracks);
    if(!Double.isFinite(sourceFramesPerSecond) || sourceFramesPerSecond<0) throw new IllegalArgumentException("Invalid source frame rate");
  }
  public double durationSeconds() { return tracks.stream().mapToDouble(t->t.curve().time(t.curve().keys()-1)).max().orElse(0); }
}
