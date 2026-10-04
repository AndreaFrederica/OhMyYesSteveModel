package cc.sirrus.ysmlib.scene.mmd;

import cc.sirrus.ysmlib.scene.*;
import java.util.*;

/** PMM is a multi-object project, with embedded keyframes and external asset references. */
public record PmmDocument(int version,List<Model> models,List<Accessory> accessories,Camera camera,Light light,
    Gravity gravity,Shadow shadow,Media audio,Media video,Media image,Map<String,Number> settings,
    List<String> modelLabels,List<String> accessoryLabels,ByteData unknownMatrix,IntData modelSelections,
    ByteData trailingData,ByteData source) {
  /** Index/previous/next refer to keyframe records, not bone IDs. Initial records have implicit IDs. */
  public record Key(int index,long frame,int previous,int next) {}
  public record Parent(int model,int bone) { public static final Parent NONE=new Parent(-1,-1); }
  public record BoneKey(Key key,ByteData interpolation,Vec3 translation,FloatData rotation,int selected,int physicsDisabled) {}
  public record MorphKey(Key key,float weight,int selected) {}
  public record ModelKey(Key key,int visible,ByteData ikEnabled,List<Parent> outsideParents,int selected) {
    public ModelKey { outsideParents=List.copyOf(outsideParents); }
  }
  public record BoneState(Vec3 translation,FloatData rotation,int dirty,int physicsDisabled,int tracksSelected,int unknown) {}
  public record ParentState(long begin,long end,Parent parent) {}
  public record Model(int index,String name,String englishName,String path,List<String> boneNames,List<String> morphNames,
      IntData ikBones,IntData outsideBones,List<BoneKey> boneKeys,List<MorphKey> morphKeys,List<ModelKey> modelKeys,
      List<BoneState> boneStates,FloatData morphStates,ByteData ikStates,List<ParentState> outsideStates,
      ByteData expansionStates,Map<String,Number> settings) {
    public Model { boneNames=List.copyOf(boneNames);morphNames=List.copyOf(morphNames);boneKeys=List.copyOf(boneKeys);morphKeys=List.copyOf(morphKeys);
      modelKeys=List.copyOf(modelKeys);boneStates=List.copyOf(boneStates);outsideStates=List.copyOf(outsideStates);settings=Map.copyOf(settings); }
  }
  public record AccessoryState(int opacityVisible,Parent parent,Vec3 translation,Vec3 euler,float scale,int shadow) {
    public float opacity() { return (100-((opacityVisible&254)>>1))*.01f; }
    public boolean visible() { return (opacityVisible&1)!=0; }
  }
  public record AccessoryKey(Key key,AccessoryState state,int selected) {}
  public record Accessory(int index,String name,String path,int drawOrder,List<AccessoryKey> keys,AccessoryState current,int addBlend) {
    public Accessory { keys=List.copyOf(keys); }
  }
  public record CameraKey(Key key,float distance,Vec3 target,Vec3 euler,Parent parent,ByteData interpolation,int orthographic,int fov,int selected) {}
  public record Camera(List<CameraKey> keys,Vec3 target,Vec3 position,Vec3 euler,int orthographic) { public Camera { keys=List.copyOf(keys); } }
  public record LightKey(Key key,Vec3 color,Vec3 direction,int selected) {}
  public record Light(List<LightKey> keys,Vec3 color,Vec3 direction) { public Light { keys=List.copyOf(keys); } }
  public record GravityKey(Key key,int noiseEnabled,int noise,float acceleration,Vec3 direction,int selected) {}
  public record Gravity(List<GravityKey> keys,float acceleration,int noise,Vec3 direction,int noiseEnabled) { public Gravity { keys=List.copyOf(keys); } }
  public record ShadowKey(Key key,int mode,float distance,int selected) {}
  public record Shadow(List<ShadowKey> keys,int enabled,float distance) { public Shadow { keys=List.copyOf(keys); } }
  public record Media(String path,int enabled,int offsetX,int offsetY,float scale) {}
  public PmmDocument {
    models=List.copyOf(models);accessories=List.copyOf(accessories);settings=Map.copyOf(settings);
    modelLabels=List.copyOf(modelLabels);accessoryLabels=List.copyOf(accessoryLabels);Objects.requireNonNull(source);
  }
  /** Includes disabled media because a project can enable it later. Empty references are omitted. */
  public List<String> assetReferences() {
    var paths=new LinkedHashSet<String>();for(var m:models) paths.add(m.path());for(var a:accessories) paths.add(a.path());
    paths.add(audio.path());paths.add(video.path());paths.add(image.path());paths.remove("");return List.copyOf(paths);
  }
}
