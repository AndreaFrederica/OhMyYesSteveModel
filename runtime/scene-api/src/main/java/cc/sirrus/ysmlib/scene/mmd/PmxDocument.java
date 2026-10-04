package cc.sirrus.ysmlib.scene.mmd;

import cc.sirrus.ysmlib.scene.*;
import java.util.List;

/** PMX source-space semantics. Bone positions and rigid-body poses are absolute MMD model coordinates. */
public record PmxDocument(float version,ByteData globals,Names names,String comment,String englishComment,
    List<Vertex> vertices,IntData indices,List<String> textures,List<Material> materials,List<Bone> bones,
    List<Morph> morphs,List<DisplayFrame> displayFrames,List<RigidBody> rigidBodies,List<Joint> joints,
    List<SoftBody> softBodies,ByteData source) {
  public record Names(String local,String universal) {}
  /** deform is the original PMX tag 0=BDEF1,1=BDEF2,2=BDEF4,3=SDEF,4=QDEF. */
  public record Vertex(Vec3 position,Vec3 normal,FloatData uv,FloatData additionalUv,int deform,IntData bones,FloatData weights,
      Vec3 sdefC,Vec3 sdefR0,Vec3 sdefR1,float edgeScale) {}
  public record Material(Names names,FloatData diffuse,Vec3 specular,float shininess,Vec3 ambient,int flags,
      FloatData edgeColor,float edgeSize,int texture,int sphereTexture,int sphereMode,boolean sharedToon,int toonTexture,String memo,int indexCount) {}
  public record Inherit(int parent,float weight) {}
  public record Axes(Vec3 x,Vec3 z) {}
  public record IkLink(int bone,Vec3 minimum,Vec3 maximum) {}
  public record Ik(int target,int iterations,float angleLimit,List<IkLink> links) { public Ik { links=List.copyOf(links); } }
  public record Bone(Names names,Vec3 position,int parent,int layer,int flags,int tailBone,Vec3 tailOffset,
      Inherit inherit,Vec3 fixedAxis,Axes localAxes,Integer externalParentKey,Ik ik) {}
  public sealed interface MorphOffset permits GroupOffset,VertexOffset,BoneOffset,UvOffset,MaterialOffset,ImpulseOffset {}
  public record GroupOffset(int morph,float weight) implements MorphOffset {}
  public record VertexOffset(int vertex,Vec3 translation) implements MorphOffset {}
  public record BoneOffset(int bone,Vec3 translation,FloatData rotation) implements MorphOffset {}
  public record UvOffset(int vertex,FloatData offset) implements MorphOffset {}
  public record MaterialOffset(int material,int operation,FloatData diffuse,Vec3 specular,float shininess,Vec3 ambient,
      FloatData edgeColor,float edgeSize,FloatData textureTint,FloatData sphereTint,FloatData toonTint) implements MorphOffset {}
  public record ImpulseOffset(int rigidBody,boolean local,Vec3 velocity,Vec3 torque) implements MorphOffset {}
  /** type preserves all eleven PMX kinds, including group versus flip and each of five UV channels. */
  public record Morph(Names names,int panel,int type,List<MorphOffset> offsets) { public Morph { offsets=List.copyOf(offsets); } }
  public record DisplayElement(boolean morph,int index) {}
  public record DisplayFrame(Names names,boolean special,List<DisplayElement> elements) { public DisplayFrame { elements=List.copyOf(elements); } }
  /** collisionMask contains allowed groups (PMX's raw mask), not a host-specific inverted mask. */
  public record RigidBody(Names names,int bone,int group,int collisionMask,int shape,Vec3 size,Vec3 position,Vec3 euler,
      float mass,float linearDamping,float angularDamping,float restitution,float friction,int mode) {}
  public record Joint(Names names,int type,int bodyA,int bodyB,Vec3 position,Vec3 euler,Vec3 linearLower,Vec3 linearUpper,
      Vec3 angularLower,Vec3 angularUpper,Vec3 linearSpring,Vec3 angularSpring) {}
  public record Anchor(int body,int vertex,boolean near) {}
  /** config/cluster/iterations/stiffness retain PMX field order, with exact float/int types. */
  public record SoftBody(Names names,int shape,int material,int group,int collisionMask,int flags,int bendingDistance,
      int clusterCount,float mass,float margin,int aeroModel,FloatData config,FloatData cluster,IntData iterations,
      FloatData stiffness,List<Anchor> anchors,IntData pins) { public SoftBody { anchors=List.copyOf(anchors); } }
  public PmxDocument {
    vertices=List.copyOf(vertices);textures=List.copyOf(textures);materials=List.copyOf(materials);bones=List.copyOf(bones);
    morphs=List.copyOf(morphs);displayFrames=List.copyOf(displayFrames);rigidBodies=List.copyOf(rigidBodies);
    joints=List.copyOf(joints);softBodies=List.copyOf(softBodies);
  }
}
