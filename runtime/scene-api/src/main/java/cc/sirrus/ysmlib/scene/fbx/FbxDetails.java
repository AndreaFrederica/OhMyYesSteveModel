package cc.sirrus.ysmlib.scene.fbx;

import cc.sirrus.ysmlib.scene.*;
import java.util.*;

/** FBX-specific data which cannot be reduced to a glTF material or morph without loss. */
public final class FbxDetails {
  private FbxDetails() {}
  public record BlendDeformer(int element,IntData channels) {}
  public record BlendKey(int shape,double targetWeight,double effectiveWeight) {}
  public record BlendChannel(int element,double weight,List<BlendKey> keys) { public BlendChannel { keys=List.copyOf(keys); } }
  /** Sparse offsets address control vertices; optional Blender offset weights are retained separately. */
  public record BlendShape(int element,IntData vertices,DoubleData positions,DoubleData normals,DoubleData weights) {}
  public record MaterialMap(DoubleData value,long integer,int texture,boolean hasValue,boolean textureEnabled,boolean disabled,int components) {}
  public record MaterialFeature(boolean enabled,boolean explicit) {}
  public record MaterialTexture(String property,String shaderProperty,int texture) {}
  /** PBR values may be approximations provided by the source reader; the original FBX maps remain available. */
  public record Material(int element,int shaderType,int shader,String shadingModel,String shaderPrefix,
      Map<String,MaterialMap> fbx,Map<String,MaterialMap> pbr,Map<String,MaterialFeature> features,List<MaterialTexture> textures) {
    public Material { fbx=Map.copyOf(fbx);pbr=Map.copyOf(pbr);features=Map.copyOf(features);textures=List.copyOf(textures); }
  }
  /** Intensity is the FBX percentage divided by 100, not an assumed physical light unit. Angles are degrees. */
  public record Light(int element,int type,int decay,int areaShape,boolean castLight,boolean castShadows,
      DoubleData color,double intensity,DoubleData localDirection,double innerAngle,double outerAngle) {}
  /** Camera modes and projection axes use the pinned ufbx enumeration order; all optical source parameters remain separate. */
  public record Camera(int element,int projection,boolean resolutionPixels,IntData axes,IntData modes,Map<String,DoubleData> parameters) {
    public Camera { parameters=Map.copyOf(parameters); }
  }
  public record Transform(DoubleData translation,DoubleData rotation,DoubleData scale) {}
  public record ConstraintTarget(int node,double weight,Transform transform) {}
  public record Constraint(int element,int type,int node,String typeName,boolean active,double weight,IntData axes,Transform offset,
      List<ConstraintTarget> targets,DoubleData aim,DoubleData upVector,DoubleData pole,int upType,int upNode,int effector,int end) {
    public Constraint { targets=List.copyOf(targets); }
  }
  public record Warning(int type,int element,int count,String description) {}
  public record State(List<BlendDeformer> blendDeformers,List<BlendChannel> blendChannels,List<Material> materials,
      List<Light> lights,List<Camera> cameras,List<Constraint> constraints,List<Warning> warnings) {
    public State { blendDeformers=List.copyOf(blendDeformers);blendChannels=List.copyOf(blendChannels);materials=List.copyOf(materials);
      lights=List.copyOf(lights);cameras=List.copyOf(cameras);constraints=List.copyOf(constraints);warnings=List.copyOf(warnings); }
  }
}
