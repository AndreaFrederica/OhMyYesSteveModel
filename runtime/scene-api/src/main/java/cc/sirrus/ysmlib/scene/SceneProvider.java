package cc.sirrus.ysmlib.scene;

import cc.sirrus.ysmlib.scene.io.*;
import cc.sirrus.ysmlib.scene.mmd.*;
import cc.sirrus.ysmlib.scene.bvh.*;
import cc.sirrus.ysmlib.scene.vrm.*;
import cc.sirrus.ysmlib.scene.fbx.*;
import java.io.IOException;
import java.util.*;

/** Portable import/evaluation services. Imported source coverage is not a claim of host rendering support. */
public interface SceneProvider {
  enum NormalPolicy { INVERSE_TRANSPOSE, MMD_WEIGHTED_ROTATION }
  /** Prepare glTF source geometry before morph/skin. -1 means no normal texture; other values select its UV set. */
  MeshAsset.Primitive prepareGltfSurface(MeshAsset.Primitive source,int normalTexCoord,ReadLimits limits);
  String profile();
  SceneModelProfile readModelProfile(ByteData source);
  ByteData writeModelProfile(SceneModelProfile source);
  /** Decode source samples without color conversion. TGA requires its format hint because it has no magic. */
  SceneImage readImage(ByteData source,String formatHint,ReadLimits limits) throws IOException;
  /** Samples in the material's chosen working space, suitable for filtering. Does not allocate any GPU resource. */
  FloatData texturePixels(SceneImage source,SceneImageUsage usage,ReadLimits limits) throws IOException;
  ScenePackage readPackage(ByteData source,ReadLimits limits) throws IOException;
  ByteData writePackage(ScenePackage source,ReadLimits limits) throws IOException;
  AssetResolver resolver(ScenePackage source,String owner,ScenePackage.ReferenceSyntax syntax);
  ScenePackageAssets loadPackage(ScenePackage source,ReadLimits limits) throws IOException;
  /** Enumerate source-local clips without opening players; fallbackFrameRate is only for stepping continuous-time formats. */
  List<SceneAnimation> animations(ScenePackageAssets source,double fallbackFrameRate);
  /** Decode model images with one cumulative budget; source parsing and image decoding remain distinct. */
  ScenePackageImages images(ScenePackageAssets source,ReadLimits limits) throws IOException;
  ScenePackagePlayback playback(ScenePackageAssets source,ScenePackagePlayback.Selection selection,ScenePackagePlayback.Settings settings,ReadLimits limits) throws AssetFormatException;
  <T> AnimationPreview<T> preview(AnimationPreview.Range range,AnimationPreview.Source<T> source);
  FbxDocument readFbx(ByteData source,ReadLimits limits) throws AssetFormatException;
  FbxEvaluation evaluator(FbxDocument source,ReadLimits limits,FbxEvaluation.Settings settings) throws AssetFormatException;
  GeometryFrame geometry(FbxDocument source,FbxEvaluation.Frame frame);
  SceneGeometry geometry(SceneAsset source,int sceneIndex);
  SceneGeometry geometry(SceneAsset source,int sceneIndex,ReadLimits limits);
  SceneGeometry geometry(VrmDocument source,int sceneIndex,boolean firstPerson);
  SceneGeometry geometry(VrmDocument source,int sceneIndex,boolean firstPerson,ReadLimits limits);
  GeometryFrame geometry(MeshAsset source,MmdPlayback.Frame frame,SceneAsset.Coordinates coordinates);
  FbxAssets dependencies(FbxDocument source,AssetResolver resolver,ReadLimits limits) throws IOException;
  BvhDocument readBvh(ByteData source,ReadLimits limits) throws AssetFormatException;
  BvhEvaluation evaluator(BvhDocument source,SceneAsset.Coordinates coordinates);
  GltfDocument readGltf(ByteData source,AssetResolver resolver,ReadLimits limits) throws IOException;
  VrmDocument readVrm(ByteData source,AssetResolver resolver,ReadLimits limits) throws IOException;
  VrmDocument vrmProfile(GltfDocument source) throws AssetFormatException;
  VrmaDocument readVrma(ByteData source,AssetResolver resolver,ReadLimits limits) throws IOException;
  VrmaDocument vrmaProfile(GltfDocument source) throws AssetFormatException;
  VrmaEvaluation retarget(VrmaDocument animation,VrmDocument avatar,VrmaEvaluation.Settings settings);
  VrmExpressions expressions(VrmDocument source);
  VrmViewGeometry firstPerson(VrmDocument source);
  VrmMaterials materials(VrmDocument source);
  VrmSpringSimulation springs(VrmDocument source);
  VrmEvaluation evaluator(VrmDocument source);
  VrmPlayback playback(VrmDocument source,VrmPlayback.Source animation,VrmPlayback.Settings settings);
  NodeConstraintEvaluation nodeConstraints(SceneAsset scene);
  PmxDocument readPmx(ByteData source,ReadLimits limits) throws AssetFormatException;
  PmdDocument readPmd(ByteData source,ReadLimits limits) throws AssetFormatException;
  PmmDocument readPmm(ByteData source,PmmModelResolver resolver,ReadLimits limits) throws IOException;
  PmmAnimationSet pmmAnimations(PmmDocument source) throws AssetFormatException;
  MeshAsset mesh(PmxDocument source);
  MeshAsset mesh(PmdDocument source);
  MmdMaterials materials(PmxDocument source);
  MmdMaterials materials(PmdDocument source);
  /** Fixed library assets for PMX shared toon / missing PMD default toon references (zero-based). */
  ByteData sharedMmdToon(int index) throws IOException;
  MmdEvaluation evaluator(PmxDocument source);
  MmdEvaluation evaluator(PmdDocument source);
  MmdPlayback playback(PmxDocument source,AnimationClip clip,MmdPlayback.Settings settings,Map<Integer,Pose> outsideParents);
  MmdPlayback playback(PmdDocument source,AnimationClip clip,MmdPlayback.Settings settings,Map<Integer,Pose> outsideParents);
  VmdDocument readVmd(ByteData source,ReadLimits limits) throws AssetFormatException;
  VpdDocument readVpd(ByteData source,ReadLimits limits) throws AssetFormatException;
  AnimationImport vmdAnimation(VmdDocument source);
  AnimationClip vpdAnimation(VpdDocument source);
  AnimationFrame sample(AnimationClip clip,double seconds);
  SceneEvaluation evaluator(SceneAsset scene);
  Map<String,MeshAsset.Attribute> deform(MeshAsset.Primitive mesh,List<Matrix4> palette,FloatData morphWeights,NormalPolicy normalPolicy);
}
