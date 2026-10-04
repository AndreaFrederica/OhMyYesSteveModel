package cc.sirrus.ysmlib.scene.java;

import cc.sirrus.ysmlib.scene.*;
import java.util.ArrayList;

public final class ClipSampler {
  public AnimationFrame sample(AnimationClip clip,double seconds) {
    if(!Double.isFinite(seconds)) throw new IllegalArgumentException("Non-finite sample time");
    var channels=new ArrayList<AnimationFrame.Channel>(clip.tracks().size());
    for(var t:clip.tracks()) channels.add(new AnimationFrame.Channel(t.property(),t.targetIndex(),t.binding(),t.component(),
        new FloatData(CurveSampler.sample(t.curve(),seconds))));
    return new AnimationFrame(seconds,channels);
  }
}
