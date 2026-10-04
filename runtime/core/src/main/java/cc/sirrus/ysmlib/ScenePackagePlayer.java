package cc.sirrus.ysmlib;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.*;
import cc.sirrus.ysmlib.scene.mmd.*;
import cc.sirrus.ysmlib.scene.vrm.*;
import cc.sirrus.ysmlib.scene.fbx.*;
import java.util.*;

/** Thin composition of the same public format players used outside Minecraft. */
final class ScenePackagePlayer implements ScenePackagePlayback {
  private final ScenePackageAssets assets;
  private final Selection selection;
  private final java.util.function.DoubleFunction<Frame> evaluate;
  private final Runnable release;
  private boolean closed;
  private Frame current;
  private java.util.function.Consumer<Map<Integer,Rotation>> boneOverlay=rotations->{ if(!rotations.isEmpty()) throw new UnsupportedOperationException("Bone overlay is currently MMD-only"); };
  private java.util.function.Consumer<Map<Integer,Pose>> poseOverlay=poses->{if(!poses.isEmpty())throw new UnsupportedOperationException("Bone poses are currently MMD-only");};
  private java.util.function.Consumer<Map<Integer,Boolean>> ikOverlay=switches->{if(!switches.isEmpty())throw new UnsupportedOperationException("IK overrides are currently MMD-only");};
  ScenePackagePlayer(SceneProvider services,ScenePackageAssets assets,Selection selection,Settings settings,ReadLimits limits) throws AssetFormatException {
    this.assets=Objects.requireNonNull(assets);this.selection=Objects.requireNonNull(selection);Objects.requireNonNull(settings);
    var source=assets.source();var model=assets.model();
    var profile=settings.modelProfile();
    if(profile==null && source.files().containsKey(SceneModelProfile.PACKAGE_PATH)) profile=services.readModelProfile(source.files().get(SceneModelProfile.PACKAGE_PATH));
    var mappings=profile==null?Map.<String,SceneModelProfile.BoneBinding>of():profile.bones();
    SceneSkeleton.validate(SceneSkeleton.of(assets),mappings);
    // Generated locomotion clips are deliberately kept out of the source
    // document map. They are synthesized from the model's bone names when an
    // MMD package has no VMD/VPD counterpart.
    boolean generated=selection.sourceId().startsWith("@ysm/generated/");
    var selected=selection.sourceId().isEmpty() || generated?null:assets.documents().get(selection.sourceId());
    if(!selection.sourceId().isEmpty() && !generated && selected==null) throw new IllegalArgumentException("Unknown package animation source: "+selection.sourceId());
    if(generated && !(model instanceof ScenePackageAssets.Pmx || model instanceof ScenePackageAssets.Pmd))
      throw new IllegalArgumentException("Generated MMD animation requires PMX/PMD");
    if(generated && selection.clip()!=-1) throw new IllegalArgumentException("Generated animation selections do not have a clip index");
    if(selection.sourceId().isEmpty() && selection.clip()!=-1) throw new IllegalArgumentException("Rest selection has no clip");
    Runnable closer=()->{};java.util.function.DoubleFunction<Frame> evaluator;
    if(model instanceof ScenePackageAssets.Gltf gltf) {
      var scene=gltf.value().scene();requireEvaluated(scene.compatibility());
      var clip=localClip(scene,source,selection,selected);var animation=services.evaluator(scene);
      var geometry=assets.preparedGeometry()==null?services.geometry(scene,source.settings().scene(),limits)
          :new cc.sirrus.ysmlib.scene.java.SceneGeometryCompiler(assets.preparedGeometry().scene(),source.settings().scene());
      evaluator=time->{ var pose=animation.evaluate(clip,sourceTime(settings,time));var draw=scaled(geometry.compile(pose));return new Frame(time,draw,draw,new Scene(pose)); };
    } else if(model instanceof ScenePackageAssets.Vrm vrm) {
      var avatar=vrm.value();requireVrmEvaluated(avatar);
      var third=preparedVrmGeometry(services,avatar,source.settings().scene(),false,limits);
      var first=preparedVrmGeometry(services,avatar,source.settings().scene(),true,limits);
      final VrmPlayback.Source animation;
      if(selected instanceof ScenePackageAssets.Vrma vrma) {
        var retarget=services.retarget(vrma.value(),avatar,VrmaEvaluation.Settings.automatic());requireEvaluated(retarget.compatibility());
        int index=requireClip(selection.clip(),vrma.value().animations().size());animation=time->retarget.evaluate(index,sourceTime(settings,time));
      } else {
        var clip=localClip(avatar.scene(),source,selection,selected);var avatarAnimation=services.evaluator(avatar);
        animation=time->avatarAnimation.evaluate(clip,sourceTime(settings,time),VrmEvaluation.Input.NONE);
      }
      var player=services.playback(avatar,animation,settings.vrm());var materials=services.materials(avatar);
      evaluator=time->{ var frame=player.seek(time);return new Frame(time,scaled(third.compile(frame.pose())),scaled(first.compile(frame.pose())),new Vrm(frame,materials.evaluate(frame.materials(),sourceTime(settings,time)))); };
    } else if(model instanceof ScenePackageAssets.Pmx || model instanceof ScenePackageAssets.Pmd) {
      AnimationClip clip;
      if(generated) {
        String state=selection.sourceId().substring("@ysm/generated/".length());
        if(state.isBlank()) throw new IllegalArgumentException("Generated MMD animation state is empty");
        if(model instanceof ScenePackageAssets.Pmx pmx) clip=MmdBasicAnimationFactory.forState(pmx.value(),state,mappings);
        else clip=MmdBasicAnimationFactory.forState(((ScenePackageAssets.Pmd)model).value(),state,mappings);
      } else if(selected==null) clip=new AnimationClip("",List.of(),30);
      else if(selected instanceof ScenePackageAssets.Vmd vmd) { singleClip(selection);requireEvaluated(vmd.animation().compatibility());clip=vmd.animation().clip(); }
      else if(selected instanceof ScenePackageAssets.Vpd vpd) { singleClip(selection);clip=vpd.animation(); }
      else throw new IllegalArgumentException("MMD playback requires a VMD/VPD source or rest selection");
      final MeshAsset mesh;final MmdPlayback player;
      var mmdSettings=settings.mmd().withAnimationRange(settings.animationRange());
      var deformation=settings.deferMmdDeformation()?new cc.sirrus.ysmlib.scene.java.DeferredDeformationProvider():YsmRuntime.deformation();
      if(model instanceof ScenePackageAssets.Pmx pmx) { mesh=pmx.mesh();player=settings.deferMmdDeformation()
          ?MmdPlayer.packed(pmx.value(),clip,YsmRuntime.physics(),mmdSettings,Map.of(),deformation):services.playback(pmx.value(),clip,mmdSettings,Map.of()); }
      else { var pmd=(ScenePackageAssets.Pmd)model;mesh=pmd.mesh();player=settings.deferMmdDeformation()
          ?MmdPlayer.packed(pmd.value(),clip,YsmRuntime.physics(),mmdSettings,Map.of(),deformation):services.playback(pmd.value(),clip,mmdSettings,Map.of()); }
      closer=player::close;
      boneOverlay=player::boneRotations;
      poseOverlay=player::bonePoses;ikOverlay=player::ikOverrides;
      evaluator=time->{
        var frame=player.seek(time);
        var draw=services.geometry(mesh,frame,new SceneAsset.Coordinates(false,source.settings().metersPerUnit(),"Y"));
        return new Frame(time,draw,draw,new Mmd(frame));
      };
    } else if(model instanceof ScenePackageAssets.Fbx fbx) {
      if(selected!=null && !selection.sourceId().equals(source.model().id()))
        throw new IllegalArgumentException("External FBX animation requires explicit skeleton retargeting");
      int stack=selected==null?-1:requireClip(selection.clip(),fbx.value().animations().size());
      var player=services.evaluator(fbx.value(),limits,new FbxEvaluation.Settings(source.settings().fbxSkinSpace()));closer=player::close;
      evaluator=time->{ var frame=player.evaluate(stack,sourceTime(settings,time));var draw=scaled(services.geometry(fbx.value(),frame));return new Frame(time,draw,draw,new Fbx(frame)); };
    } else throw new IllegalArgumentException("PMM multi-object playback is not yet implemented; the parsed project remains available");
    evaluate=evaluator;release=closer;
  }
  private SceneGeometry preparedVrmGeometry(SceneProvider services,VrmDocument avatar,int scene,boolean first,ReadLimits limits) {
    var prepared=assets.preparedGeometry();
    if(prepared==null || prepared.views()==null)return services.geometry(avatar,scene,first,limits);
    var views=new LinkedHashMap<Integer,List<MeshAsset.Primitive>>();
    for(var node:prepared.views().nodes())views.put(node.node(),(first?node.firstPerson():node.thirdPerson()).stream().map(VrmViewGeometry.Draw::geometry).toList());
    return new cc.sirrus.ysmlib.scene.java.SceneGeometryCompiler(prepared.scene(),scene,views);
  }
  private static double sourceTime(Settings settings,double elapsed) {
    return settings.animationRange()==null?elapsed:settings.animationRange().sample(elapsed);
  }
  private static AnimationClip localClip(SceneAsset scene,ScenePackage source,Selection selection,ScenePackageAssets.Document selected) {
    if(selected==null) return null;
    // Separate glTF documents have separate node index domains, even when their names happen to match.
    String path=selection.sourceId().equals(source.model().id())?source.model().path():source.animations().stream()
        .filter(s->s.id().equals(selection.sourceId())).findFirst().orElseThrow().path();
    if(!path.equals(source.model().path()) || !(selected instanceof ScenePackageAssets.Gltf || selected instanceof ScenePackageAssets.Vrm))
      throw new IllegalArgumentException("External scene animation requires explicit retargeting; use VRMA for a humanoid avatar");
    return scene.animations().get(requireClip(selection.clip(),scene.animations().size()));
  }
  private static int requireClip(int index,int count) {
    if(index<0 || index>=count) throw new IllegalArgumentException("Animation clip index out of range");return index;
  }
  private static void singleClip(Selection selected) {
    if(selected.clip()!=0) throw new IllegalArgumentException("VMD/VPD sources have one clip at index 0");
  }
  private static void requireEvaluated(CompatibilityReport report) {
    if(!report.canEvaluateRequiredFeatures()) throw new IllegalArgumentException("Source has required behavior without an evaluator: "+report);
  }
  private static void requireVrmEvaluated(VrmDocument avatar) {
    // Source-reader coverage is intentionally READ. These exact profiles have dedicated evaluators above.
    var handled=Set.of("VRM","VRMC_vrm","VRMC_springBone","VRMC_node_constraint","VRMC_materials_mtoon");
    var report=avatar.gltf().scene().compatibility();
    requireEvaluated(new CompatibilityReport(report.features().stream().filter(f->!handled.contains(f.id())).toList(),report.diagnostics()));
  }
  private GeometryFrame scaled(GeometryFrame frame) {
    var c=frame.coordinates();return new GeometryFrame(frame.seconds(),new SceneAsset.Coordinates(c.rightHanded(),assets.source().settings().metersPerUnit(),c.upAxis()),frame.draws());
  }
  public ScenePackageAssets assets() { return assets; }
  public Selection selection() { return selection; }
  public void boneRotations(Map<Integer,Rotation> rotations) { boneOverlay.accept(rotations);current=null; }
  public void bonePoses(Map<Integer,Pose> poses) { if(closed)throw new IllegalStateException("Scene package player is closed");poseOverlay.accept(poses);current=null; }
  public void ikOverrides(Map<Integer,Boolean> switches) { if(closed)throw new IllegalStateException("Scene package player is closed");ikOverlay.accept(switches);current=null; }
  public Frame seek(double seconds) {
    if(closed) throw new IllegalStateException("Scene package player is closed");
    if(!Double.isFinite(seconds)) throw new IllegalArgumentException("Non-finite scene time");
    if(current!=null && current.seconds()==seconds) return current;
    var next=evaluate.apply(seconds);current=next;return next;
  }
  public void close() { if(!closed) { closed=true;release.run(); } }
}
