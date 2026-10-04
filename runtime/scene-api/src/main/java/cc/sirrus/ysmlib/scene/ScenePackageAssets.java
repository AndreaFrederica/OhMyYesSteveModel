package cc.sirrus.ysmlib.scene;

import cc.sirrus.ysmlib.scene.mmd.*;
import cc.sirrus.ysmlib.scene.vrm.*;
import cc.sirrus.ysmlib.scene.fbx.*;
import cc.sirrus.ysmlib.scene.bvh.*;
import java.util.*;

/** Parsed source closure, independent of any mutable player or host GPU resource. */
public record ScenePackageAssets(ScenePackage source,Map<String,Document> documents,Map<Reference,String> dependencies,PreparedGeometry preparedGeometry) {
  /** Optional immutable derived geometry; source documents remain unchanged. Mutable players never enter this value. */
  public record PreparedGeometry(SceneAsset scene,VrmViewGeometry views) {public PreparedGeometry {Objects.requireNonNull(scene);}}
  public ScenePackageAssets(ScenePackage source,Map<String,Document> documents,Map<Reference,String> dependencies) {this(source,documents,dependencies,null);}
  public record Reference(String owner,String sourceReference) {}
  public sealed interface Document permits Gltf,Vrm,Fbx,Pmx,Pmd,Pmm,Vmd,Vpd,Vrma,Bvh {}
  public record Gltf(GltfDocument value) implements Document {}
  public record Vrm(VrmDocument value) implements Document {}
  public record Fbx(FbxDocument value,FbxAssets assets) implements Document {}
  public record Pmx(PmxDocument value,MeshAsset mesh) implements Document {}
  public record Pmd(PmdDocument value,MeshAsset mesh) implements Document {}
  /** Project model keys are original PMM model indices. Accessories/media stay in the source package. */
  public record Pmm(PmmDocument value,Map<Integer,Document> models) implements Document {
    public Pmm { models=Map.copyOf(models); }
  }
  public record Vmd(VmdDocument value,AnimationImport animation) implements Document {}
  public record Vpd(VpdDocument value,AnimationClip animation) implements Document {}
  public record Vrma(VrmaDocument value) implements Document {}
  public record Bvh(BvhDocument value) implements Document {}
  public ScenePackageAssets {
    Objects.requireNonNull(source);documents=Map.copyOf(documents);dependencies=Map.copyOf(dependencies);
    if(!documents.containsKey(source.model().id())) throw new IllegalArgumentException("Missing parsed model");
  }
  public Document model() { return documents.get(source.model().id()); }
}
