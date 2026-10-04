package cc.sirrus.ysmlib.scene;

import java.util.List;

/** Evaluated channels exposed to hosts and extensions, including camera, light and shadow data. */
public record AnimationFrame(double seconds,List<Channel> channels) {
  public record Channel(AnimationClip.Property property,int targetIndex,String binding,String component,FloatData value) {}
  public AnimationFrame { channels=List.copyOf(channels); }
}
