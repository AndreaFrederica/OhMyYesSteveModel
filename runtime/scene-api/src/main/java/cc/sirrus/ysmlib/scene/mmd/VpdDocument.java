package cc.sirrus.ysmlib.scene.mmd;

import cc.sirrus.ysmlib.scene.*;
import java.util.List;

public record VpdDocument(String parentFile,List<BonePose> bones,List<MorphPose> morphs,ByteData source) {
  public record BonePose(int sourceIndex,String name,Vec3 translation,FloatData rotation) {}
  public record MorphPose(int sourceIndex,String name,float weight) {}
  public VpdDocument { bones=List.copyOf(bones);morphs=List.copyOf(morphs); }
}
