package cc.sirrus.ysmlib;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.*;
import cc.sirrus.ysmlib.scene.java.*;
import cc.sirrus.ysmlib.scene.gltf.GltfReader;
import cc.sirrus.ysmlib.scene.mmd.*;
import cc.sirrus.ysmlib.scene.bvh.*;
import cc.sirrus.ysmlib.scene.vrm.*;
import cc.sirrus.ysmlib.scene.fbx.*;
import java.io.IOException;
import java.util.*;

/** Stateless managed service composition; no host objects or GPU resources. */
final class SceneServices implements SceneProvider {
  private final SceneParsedCache parsedCache=new SceneParsedCache();
  private static final cc.sirrus.ysmlib.scene.physics.PhysicsProvider NO_PHYSICS=new cc.sirrus.ysmlib.scene.physics.PhysicsProvider() {
    public String id() { return "disabled-preview"; }
    public cc.sirrus.ysmlib.scene.physics.PhysicsWorld createWorld(cc.sirrus.ysmlib.scene.physics.PhysicsSpec.World settings) {
      throw new IllegalStateException("A disabled preview must never create a physics world");
    }
  };
  public String profile() { return "ysmlib-java-scene-1"; }
  public SceneModelProfile readModelProfile(ByteData source) { return new SceneModelProfileCodec().read(source); }
  public ByteData writeModelProfile(SceneModelProfile source) { return new SceneModelProfileCodec().write(source); }
  public MeshAsset.Primitive prepareGltfSurface(MeshAsset.Primitive source,int normalTexCoord,ReadLimits limits) {
    return new GltfSurfaceGeometry().prepare(source,normalTexCoord,limits);
  }
  public SceneImage readImage(ByteData source,String formatHint,ReadLimits limits) throws IOException {
    return new cc.sirrus.ysmlib.image.java.SceneImageDecoder().read(source,formatHint,limits);
  }
  public FloatData texturePixels(SceneImage source,SceneImageUsage usage,ReadLimits limits) throws IOException {
    return new cc.sirrus.ysmlib.image.java.SceneImageSamples().prepare(source,usage,limits);
  }
  public ScenePackage readPackage(ByteData source,ReadLimits limits) throws IOException { return new ScenePackageCodec().read(source,limits); }
  public ByteData writePackage(ScenePackage source,ReadLimits limits) throws IOException { return new ScenePackageCodec().write(source,limits); }
  public AssetResolver resolver(ScenePackage source,String owner,ScenePackage.ReferenceSyntax syntax) { return new PackageAssetResolver(source,owner,syntax); }
  public ScenePackageAssets loadPackage(ScenePackage source,ReadLimits limits) throws IOException {
    var cached=parsedCache.find(source,limits);if(cached!=null){parsedCache.add(cached,limits);return cached;}
    var result=new ScenePackageLoader(this,source,limits).load();parsedCache.add(result,limits);return result;
  }
  public List<SceneAnimation> animations(ScenePackageAssets source,double fallbackFrameRate) {
    return ScenePackageAnimations.list(source,fallbackFrameRate);
  }
  public ScenePackageImages images(ScenePackageAssets source,ReadLimits limits) throws IOException {
    return new ScenePackageImageLoader(this,source,limits).load();
  }
  public ScenePackagePlayback playback(ScenePackageAssets source,ScenePackagePlayback.Selection selection,ScenePackagePlayback.Settings settings,ReadLimits limits) throws AssetFormatException {
    return new ScenePackagePlayer(this,source,selection,settings,limits);
  }
  public <T> AnimationPreview<T> preview(AnimationPreview.Range range,AnimationPreview.Source<T> source) { return new PreviewTimeline<>(range,source); }
  public FbxDocument readFbx(ByteData source,ReadLimits limits) throws AssetFormatException { return new FbxReader().read(source,limits); }
  public FbxEvaluation evaluator(FbxDocument source,ReadLimits limits,FbxEvaluation.Settings settings) throws AssetFormatException { return new FbxPlayer(source,limits,settings); }
  public GeometryFrame geometry(FbxDocument source,FbxEvaluation.Frame frame) { return new FbxGeometry().compile(source,frame); }
  public SceneGeometry geometry(SceneAsset source,int sceneIndex) { return geometry(source,sceneIndex,ReadLimits.DEFAULT); }
  public SceneGeometry geometry(SceneAsset source,int sceneIndex,ReadLimits limits) {
    return new SceneGeometryCompiler(new GltfSurfaceGeometry().prepare(source,limits),sceneIndex);
  }
  public SceneGeometry geometry(VrmDocument source,int sceneIndex,boolean firstPerson) {
    return geometry(source,sceneIndex,firstPerson,ReadLimits.DEFAULT);
  }
  public SceneGeometry geometry(VrmDocument source,int sceneIndex,boolean firstPerson,ReadLimits limits) {
    var prepared=new GltfSurfaceGeometry().prepare(source.scene(),limits);
    var views=new LinkedHashMap<Integer,List<MeshAsset.Primitive>>();
    for(var node:new VrmFirstPerson().compile(source,prepared).nodes()) views.put(node.node(),(firstPerson?node.firstPerson():node.thirdPerson()).stream().map(VrmViewGeometry.Draw::geometry).toList());
    return new SceneGeometryCompiler(prepared,sceneIndex,views);
  }
  public GeometryFrame geometry(MeshAsset source,MmdPlayback.Frame frame,SceneAsset.Coordinates coordinates) { return new MmdGeometry().compile(source,frame,coordinates); }
  public FbxAssets dependencies(FbxDocument source,AssetResolver resolver,ReadLimits limits) throws IOException { return new FbxDependencies().resolve(source,resolver,limits); }
  public BvhDocument readBvh(ByteData source,ReadLimits limits) throws AssetFormatException { return new BvhReader().read(source,limits); }
  public BvhEvaluation evaluator(BvhDocument source,SceneAsset.Coordinates coordinates) { return new BvhEvaluator(source,coordinates); }
  public GltfDocument readGltf(ByteData source,AssetResolver resolver,ReadLimits limits) throws IOException { return new GltfReader().read(source,resolver,limits); }
  public VrmDocument readVrm(ByteData source,AssetResolver resolver,ReadLimits limits) throws IOException { return new VrmReader().read(source,resolver,limits); }
  public VrmDocument vrmProfile(GltfDocument source) throws AssetFormatException { return new VrmReader().profile(source); }
  public VrmaDocument readVrma(ByteData source,AssetResolver resolver,ReadLimits limits) throws IOException { return new VrmaReader().read(source,resolver,limits); }
  public VrmaDocument vrmaProfile(GltfDocument source) throws AssetFormatException { return new VrmaReader().profile(source); }
  public VrmaEvaluation retarget(VrmaDocument animation,VrmDocument avatar,VrmaEvaluation.Settings settings) { return new VrmaRetarget(animation,avatar,settings); }
  public VrmExpressions expressions(VrmDocument source) { return new VrmExpressionEvaluator(source); }
  public VrmViewGeometry firstPerson(VrmDocument source) { return new VrmFirstPerson().compile(source); }
  public VrmMaterials materials(VrmDocument source) { return new VrmMaterialEvaluator(source); }
  public VrmSpringSimulation springs(VrmDocument source) { return new VrmSpringSolver(source); }
  public VrmEvaluation evaluator(VrmDocument source) { return new VrmEvaluator(source); }
  public VrmPlayback playback(VrmDocument source,VrmPlayback.Source animation,VrmPlayback.Settings settings) { return new VrmPlayer(source,animation,settings); }
  public NodeConstraintEvaluation nodeConstraints(SceneAsset scene) { return new VrmConstraints(scene,VrmReader.constraints(scene)); }
  public PmxDocument readPmx(ByteData source,ReadLimits limits) throws AssetFormatException { return new PmxReader().read(source,limits); }
  public PmdDocument readPmd(ByteData source,ReadLimits limits) throws AssetFormatException { return new PmdReader().read(source,limits); }
  public PmmDocument readPmm(ByteData source,PmmModelResolver resolver,ReadLimits limits) throws IOException { return new PmmReader().read(source,resolver,limits); }
  public PmmAnimationSet pmmAnimations(PmmDocument source) throws AssetFormatException { return new PmmAnimation().compile(source); }
  public MeshAsset mesh(PmxDocument source) { return new MmdMeshCompiler().compile(source); }
  public MeshAsset mesh(PmdDocument source) { return new MmdMeshCompiler().compile(source); }
  public MmdMaterials materials(PmxDocument source) { return new MmdMaterialEvaluator(source); }
  public MmdMaterials materials(PmdDocument source) { return new MmdMaterialEvaluator(source); }
  public ByteData sharedMmdToon(int index) throws IOException { return MmdSharedToons.read(index); }
  public MmdEvaluation evaluator(PmxDocument source) { return new MmdEvaluator(source); }
  public MmdEvaluation evaluator(PmdDocument source) { return new MmdEvaluator(source); }
  public MmdPlayback playback(PmxDocument source,AnimationClip clip,MmdPlayback.Settings settings,Map<Integer,Pose> outsideParents) {
    return MmdPlayer.packed(source,clip,settings.physicsEnabled()?YsmRuntime.physics():NO_PHYSICS,settings,outsideParents,YsmRuntime.deformation());
  }
  public MmdPlayback playback(PmdDocument source,AnimationClip clip,MmdPlayback.Settings settings,Map<Integer,Pose> outsideParents) {
    return MmdPlayer.packed(source,clip,settings.physicsEnabled()?YsmRuntime.physics():NO_PHYSICS,settings,outsideParents,YsmRuntime.deformation());
  }
  public VmdDocument readVmd(ByteData source,ReadLimits limits) throws AssetFormatException { return new VmdReader().read(source,limits); }
  public VpdDocument readVpd(ByteData source,ReadLimits limits) throws AssetFormatException { return new VpdReader().read(source,limits); }
  public AnimationImport vmdAnimation(VmdDocument source) { return new VmdAnimation().compile(source); }
  public AnimationClip vpdAnimation(VpdDocument source) { return new VpdReader().animation(source); }
  public AnimationFrame sample(AnimationClip clip,double seconds) { return new ClipSampler().sample(clip,seconds); }
  public SceneEvaluation evaluator(SceneAsset scene) { return new SceneEvaluator(scene); }
  public Map<String,MeshAsset.Attribute> deform(MeshAsset.Primitive mesh,List<Matrix4> palette,FloatData weights,NormalPolicy policy) {
    return new Deformer().deform(mesh,palette,weights,switch(policy) { case INVERSE_TRANSPOSE->Deformer.NormalMode.INVERSE_TRANSPOSE;case MMD_WEIGHTED_ROTATION->Deformer.NormalMode.MMD_WEIGHTED_ROTATION; });
  }
}
