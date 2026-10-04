package cc.sirrus.ysmlib.scene.mmd;

import cc.sirrus.ysmlib.scene.*;
import java.util.List;

/** PMD's original bone kinds, base-morph indirection and bone-relative body poses remain distinct from PMX. */
public record PmdDocument(String name,String comment,List<Vertex> vertices,IntData indices,List<Material> materials,
    List<Bone> bones,List<Ik> ik,List<Morph> morphs,IntData morphDisplay,List<String> frameNames,List<BoneDisplay> boneDisplay,
    English english,List<String> toonTextures,List<PmxDocument.RigidBody> rigidBodies,List<PmxDocument.Joint> joints,
    int optionalSections,ByteData source) {
  public record Vertex(Vec3 position,Vec3 normal,FloatData uv,int bone0,int bone1,int weightPercent,int edgeFlag) {}
  public record Material(Vec3 diffuse,float alpha,float shininess,Vec3 specular,Vec3 ambient,int toonIndex,int edgeFlag,int indexCount,String textureNames) {}
  public record Bone(String name,int parent,int tail,int type,int ikParent,Vec3 position) {}
  public record Ik(int controller,int target,int iterations,float angleLimit,IntData links) {}
  public record MorphVertex(int index,Vec3 position) {}
  /** Base offsets use mesh vertex indices; other offsets use indices into the base morph's vertex list. */
  public record Morph(String name,int type,List<MorphVertex> vertices) { public Morph { vertices=List.copyOf(vertices); } }
  /** frame is one-based, with frame 0 reserved for the built-in center frame. */
  public record BoneDisplay(int bone,int frame) {}
  public record English(String model,String comment,List<String> bones,List<String> morphs,List<String> frames) {
    public English { bones=List.copyOf(bones);morphs=List.copyOf(morphs);frames=List.copyOf(frames); }
  }
  public PmdDocument {
    vertices=List.copyOf(vertices);materials=List.copyOf(materials);bones=List.copyOf(bones);ik=List.copyOf(ik);morphs=List.copyOf(morphs);
    frameNames=List.copyOf(frameNames);boneDisplay=List.copyOf(boneDisplay);toonTextures=List.copyOf(toonTextures);
    rigidBodies=List.copyOf(rigidBodies);joints=List.copyOf(joints);
  }
}
