package cc.sirrus.ysmlib.scene.bvh;

import cc.sirrus.ysmlib.scene.*;
import java.util.*;

/** BVH has no unit or coordinate-system declaration. Hosts must supply that authoring information. */
public record BvhDocument(List<Joint> joints,int frames,double frameSeconds,int channels,FloatData samples,ByteData source) {
  public enum Channel { Xposition,Yposition,Zposition,Xrotation,Yrotation,Zrotation }
  /** End Sites remain distinct nodes; duplicate source names are legal and never used as identity. */
  public record Joint(String name,int parent,Vec3 offset,List<Channel> channels,int channelOffset,boolean endSite) {
    public Joint { Objects.requireNonNull(name);Objects.requireNonNull(offset);channels=List.copyOf(channels); }
  }
  public BvhDocument {
    joints=List.copyOf(joints);Objects.requireNonNull(samples);Objects.requireNonNull(source);
    if(joints.isEmpty() || frames<0 || channels<0 || !Double.isFinite(frameSeconds*Math.max(1,frames-1)) || frameSeconds<=0
        || (long)frames*channels!=samples.size()) throw new IllegalArgumentException("Invalid BVH motion dimensions");
    int cursor=0;
    for(int i=0;i<joints.size();i++) {
      var joint=joints.get(i);
      if(joint.parent()>=i || joint.parent()< -1 || joint.channelOffset()!=cursor
          || new HashSet<>(joint.channels()).size()!=joint.channels().size()
          || joint.endSite() && !joint.channels().isEmpty()) throw new IllegalArgumentException("Invalid BVH hierarchy/channels");
      cursor+=joint.channels().size();
    }
    if(cursor!=channels) throw new IllegalArgumentException("Invalid BVH channel count");
  }
  public double durationSeconds() { return Math.max(0,frames-1)*frameSeconds; }
}
