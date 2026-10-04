package cc.sirrus.ysmlib.scene.vrm;

import cc.sirrus.ysmlib.scene.*;
import java.util.*;

/** Versioned VRM profile. The original glTF (including every extension field) remains authoritative source data. */
public record VrmDocument(GltfDocument gltf,Version version,Map<String,Integer> humanBones,
    List<Expression> expressions,List<Constraint> constraints,List<Collider> colliders,
    List<ColliderGroup> colliderGroups,List<Spring> springs,List<LegacySpring> legacySprings,
    FirstPerson firstPerson,LookAt lookAt,List<LegacyMaterial> legacyMaterials,CompatibilityReport compatibility) {
  public enum Version { VRM_0, VRM_1 }
  public enum Override { NONE, BLOCK, BLEND }
  public enum Category { OTHER, BLINK, MOUTH, LOOK_AT }
  public enum ConstraintType { ROTATION, ROLL, AIM }
  public record MorphBind(int node,int index,float weight) {}
  /** VRM 1 uses the six specification color names; VRM 0 retains shader property names. */
  public record MaterialBind(int material,String property,FloatData target) {}
  public record TextureBind(int material,FloatData scale,FloatData offset) {}
  public record Expression(String key,String name,String preset,boolean binary,Override blink,Override mouth,Override lookAt,
      List<MorphBind> morphs,List<MaterialBind> materials,List<TextureBind> textures) {
    public Expression { morphs=List.copyOf(morphs);materials=List.copyOf(materials);textures=List.copyOf(textures); }
    public Category category() {
      return switch(preset) {
        case "blink","blinkLeft","blinkRight","blink_l","blink_r" -> Category.BLINK;
        case "aa","ih","ou","ee","oh","a","i","u","e","o" -> Category.MOUTH;
        case "lookUp","lookDown","lookLeft","lookRight","lookup","lookdown","lookleft","lookright" -> Category.LOOK_AT;
        default -> Category.OTHER;
      };
    }
  }
  public record Constraint(int node,int source,ConstraintType type,Vec3 axis,float weight) {}
  public record Collider(int node,Vec3 offset,Vec3 tail,float radius) { public boolean capsule() { return tail!=null; } }
  public record ColliderGroup(String name,IntData colliders) {}
  public record Joint(int node,float hitRadius,float stiffness,float gravityPower,Vec3 gravityDir,float dragForce) {}
  public record Spring(String name,int center,List<Joint> joints,IntData colliderGroups) {
    public Spring { joints=List.copyOf(joints); }
  }
  /** VRM 0 roots apply to entire subtrees and have implicit terminal joints; not a VRM 1 chain. */
  public record LegacySpring(String name,int center,IntData roots,Joint parameters,IntData colliderGroups) {}
  public record MeshAnnotation(int node,String type) {}
  public record FirstPerson(int bone,Vec3 offset,List<MeshAnnotation> annotations) {
    public FirstPerson { annotations=List.copyOf(annotations); }
  }
  /** Legacy curves retain (time,value,inTangent,outTangent) groups; VRM 1 has an empty curve. */
  public record RangeMap(float inputMax,float outputScale,FloatData curve) {}
  public record LookAt(String type,Vec3 offset,RangeMap horizontalInner,RangeMap horizontalOuter,RangeMap verticalDown,RangeMap verticalUp) {}
  public record LegacyMaterial(String name,String shader,int renderQueue,Map<String,FloatData> values,
      Map<String,Integer> textures,Map<String,Boolean> keywords,Map<String,String> tags) {
    public LegacyMaterial { values=Map.copyOf(values);textures=Map.copyOf(textures);keywords=Map.copyOf(keywords);tags=Map.copyOf(tags); }
  }
  public VrmDocument {
    Objects.requireNonNull(gltf);Objects.requireNonNull(version);humanBones=Map.copyOf(humanBones);
    expressions=List.copyOf(expressions);constraints=List.copyOf(constraints);colliders=List.copyOf(colliders);
    colliderGroups=List.copyOf(colliderGroups);springs=List.copyOf(springs);legacySprings=List.copyOf(legacySprings);
    legacyMaterials=List.copyOf(legacyMaterials);Objects.requireNonNull(compatibility);
  }
  public SceneAsset scene() { return gltf.scene(); }
}
