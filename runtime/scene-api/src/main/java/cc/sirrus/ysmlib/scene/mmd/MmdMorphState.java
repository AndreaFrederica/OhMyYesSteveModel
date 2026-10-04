package cc.sirrus.ysmlib.scene.mmd;

import cc.sirrus.ysmlib.scene.*;
import java.util.*;

/** One evaluated MMD morph layer. Impulses are commands consumed once by a simulation step, never by rendering. */
public record MmdMorphState(FloatData meshWeights,List<Vec3> boneTranslations,List<Rotation> boneRotations,
                           List<Material> materials,List<Impulse> impulses) {
  /** Color parameters are final values; texture modulation retains separate additive and multiplicative factors. */
  public record Material(FloatData diffuse,Vec3 specular,float shininess,Vec3 ambient,FloatData edgeColor,float edgeSize,
      FloatData textureMultiply,FloatData textureAdd,FloatData sphereMultiply,FloatData sphereAdd,FloatData toonMultiply,FloatData toonAdd) {}
  public record Impulse(int body,boolean local,Vec3 velocity,Vec3 torque,boolean reset) {}
  public MmdMorphState { boneTranslations=List.copyOf(boneTranslations);boneRotations=List.copyOf(boneRotations);materials=List.copyOf(materials);impulses=List.copyOf(impulses); }
}
