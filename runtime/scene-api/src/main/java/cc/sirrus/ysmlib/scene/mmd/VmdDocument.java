package cc.sirrus.ysmlib.scene.mmd;

import cc.sirrus.ysmlib.scene.*;
import java.util.List;

/** Lossless source keys, including unused interpolation padding and optional legacy sections. Coordinates remain MMD left-handed. */
public record VmdDocument(String signature,String modelName,List<BoneKey> bones,List<MorphKey> morphs,
                          List<CameraKey> cameras,List<LightKey> lights,List<ShadowKey> shadows,
                          List<PropertyKey> properties,int sections,ByteData source) {
  public record BoneKey(String name,long frame,Vec3 translation,FloatData rotation,ByteData interpolation) {}
  public record MorphKey(String name,long frame,float weight) {}
  public record CameraKey(long frame,float distance,Vec3 target,Vec3 euler,ByteData interpolation,long fovDegrees,boolean perspective) {}
  public record LightKey(long frame,Vec3 color,Vec3 direction) {}
  /** distance is the original VMD stored value; consumers may convert it for their shadow renderer. */
  public record ShadowKey(long frame,int mode,float distance) {}
  public record IkSwitch(String name,boolean enabled) {}
  public record PropertyKey(long frame,boolean visible,List<IkSwitch> ik) {
    public PropertyKey { ik=List.copyOf(ik); }
  }
  public VmdDocument {
    bones=List.copyOf(bones);morphs=List.copyOf(morphs);cameras=List.copyOf(cameras);lights=List.copyOf(lights);
    shadows=List.copyOf(shadows);properties=List.copyOf(properties);
  }
}
