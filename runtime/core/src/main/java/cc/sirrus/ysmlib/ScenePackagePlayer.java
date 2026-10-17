package cc.sirrus.ysmlib;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.*;
import cc.sirrus.ysmlib.scene.mmd.*;
import cc.sirrus.ysmlib.scene.vrm.*;
import cc.sirrus.ysmlib.scene.fbx.*;
import cc.sirrus.ysmlib.scene.physics.*;
import java.util.*;

/** Thin composition of the same public format players used outside Minecraft. */
final class ScenePackagePlayer implements ScenePackagePlayback {
  private final ScenePackageAssets assets;
  private Selection selection;
  private double lastSeconds;
  private final java.util.function.DoubleFunction<Frame> evaluate;
  private final Runnable release;
  private boolean closed;
  private Frame current;
  private final SceneFeatureRuntime features;
  private final SceneLayerMixer mixer;
  private MmdAnimationSource liveBase;
  private record VrmSource(java.util.function.DoubleFunction<VrmaEvaluation.Sample> sample,String authored) {}
  private VrmSource liveVrm;
  private final int boneCount;
  private String liveAuthored;
  private double sourceStart;
  private AnimationPlaybackRange liveRange;
  @FunctionalInterface private interface SourceSwitch {void accept(Selection next,AnimationPlaybackRange range,double elapsed);}
  private SourceSwitch switchSource;
  private Runnable invalidate=()->{};
  private Map<Integer,Pose> hostPoses=Map.of();
  private Map<Integer,Rotation> hostRotations=Map.of();
  private java.util.function.Consumer<ScenePhysicsInput> physicsInput=input->{};
  private java.util.function.Consumer<cc.sirrus.ysmlib.scene.physics.PhysicsEnvironment> physicsEnvironment=input->{};
  private final ScenePhysicsMotion fbxMotion=new ScenePhysicsMotion();
  private java.util.function.Consumer<Map<Integer,Rotation>> boneOverlay=rotations->{ if(!rotations.isEmpty()) throw new UnsupportedOperationException("Bone overlay is currently MMD-only"); };
  private java.util.function.Consumer<Map<Integer,Pose>> poseOverlay=poses->{if(!poses.isEmpty())throw new UnsupportedOperationException("Bone poses are currently MMD-only");};
  private java.util.function.Consumer<Map<Integer,Boolean>> ikOverlay=switches->{if(!switches.isEmpty())throw new UnsupportedOperationException("IK overrides are currently MMD-only");};
  ScenePackagePlayer(SceneProvider services,ScenePackageAssets assets,Selection selection,Settings settings,ReadLimits limits) throws AssetFormatException {
    this.assets=Objects.requireNonNull(assets);this.selection=Objects.requireNonNull(selection);Objects.requireNonNull(settings);
    var source=assets.source();var model=assets.model();
    var profile=settings.modelProfile();
    if(profile==null && source.files().containsKey(SceneModelProfile.PACKAGE_PATH)) profile=services.readModelProfile(source.files().get(SceneModelProfile.PACKAGE_PATH));
    var mappings=profile==null?Map.<String,SceneModelProfile.BoneBinding>of():profile.bones();
    var skeleton=SceneSkeleton.of(assets);boneCount=skeleton.size();SceneSkeleton.validate(skeleton,mappings);
    var effective=profile==null?SceneModelProfile.defaults(source.settings().metersPerUnit(),1,0):profile;
    SceneFeatureBindings.validate(assets,effective);features=new SceneFeatureRuntime(effective,assets);
    if(model instanceof ScenePackageAssets.Pmx p)for(int i=0;i<p.value().morphs().size();i++){var n=p.value().morphs().get(i).names();features.morphName(n.local(),i);features.morphName(n.universal(),i);}
    if(model instanceof ScenePackageAssets.Pmd p)for(int i=0;i<p.value().morphs().size();i++)features.morphName(p.value().morphs().get(i).name(),i);
    mixer=new SceneLayerMixer(services,assets,effective);
    features.layerSemantics=mixer::semantic;
    boolean authored=selection.sourceId().startsWith("@ysm/profile/");
    String authoredName=authored?selection.sourceId().substring(13):null;
    var authoredClip=authored?SceneFeatureBindings.clip(effective,authoredName,model instanceof ScenePackageAssets.Pmx||model instanceof ScenePackageAssets.Pmd,false):null;
    // Generated locomotion clips are deliberately kept out of the source
    // document map. They are synthesized from the model's bone names when an
    // MMD package has no VMD/VPD counterpart.
    boolean generated=selection.sourceId().startsWith("@ysm/generated/");
    var selected=selection.sourceId().isEmpty() || generated || authored?null:assets.documents().get(selection.sourceId());
    if(!selection.sourceId().isEmpty() && !generated && !authored && selected==null) throw new IllegalArgumentException("Unknown package animation source: "+selection.sourceId());
    if(generated && !(model instanceof ScenePackageAssets.Pmx || model instanceof ScenePackageAssets.Pmd))
      throw new IllegalArgumentException("Generated MMD animation requires PMX/PMD");
    if((generated||authored) && selection.clip()!=-1) throw new IllegalArgumentException("Generated animation selections do not have a clip index");
    if(selection.sourceId().isEmpty() && selection.clip()!=-1) throw new IllegalArgumentException("Rest selection has no clip");
    Runnable closer=()->{};java.util.function.DoubleFunction<Frame> evaluator;
    if(model instanceof ScenePackageAssets.Gltf gltf) {
      var scene=gltf.value().scene();requireEvaluated(scene.compatibility());
      var clip=authored?authoredClip:localClip(scene,source,selection,selected);var animation=services.evaluator(scene);
      var geometry=assets.preparedGeometry()==null?services.geometry(scene,source.settings().scene(),limits)
          :new cc.sirrus.ysmlib.scene.java.SceneGeometryCompiler(assets.preparedGeometry().scene(),source.settings().scene());
      evaluator=time->{ double st=sourceTime(settings,time);var pose=animation.evaluate(mixer.sampledClip(clip,st),st);pose=features.nodeMorphs(pose,features.authored(authoredName,st),time,false);pose=features.nodeMorphs(pose,features.semantic(null,time),time);var draw=scaled(geometry.compile(pose));return new Frame(time,draw,draw,new Scene(pose)); };
    } else if(model instanceof ScenePackageAssets.Vrm vrm) {
      var avatar=vrm.value();requireVrmEvaluated(avatar);
      var prepared=assets.preparedGeometry();
      var geometrySource=prepared==null?new cc.sirrus.ysmlib.scene.java.GltfSurfaceGeometry().prepare(avatar.scene(),limits):prepared.scene();
      var geometry=new cc.sirrus.ysmlib.scene.java.SceneGeometryCompiler(geometrySource,source.settings().scene());
      var visibility=new VrmFirstPerson();var nativeViews=prepared!=null&&prepared.views()!=null?prepared.views():visibility.compile(avatar,geometrySource);
      var hidden=new HashSet<Integer>();for(String role:effective.features().firstPerson().hiddenBones())hidden.add(SceneFeatureBindings.requiredBone(effective,role));
      var views=new VrmGeometryViews(visibility.exclude(nativeViews,geometrySource,hidden,effective.features().firstPerson().boneThreshold()));
      var rig=new SceneRig(avatar.scene());var avatarEvaluation=services.evaluator(avatar);
      liveVrm=vrmSource(services,avatar,selection,effective);liveRange=settings.animationRange();
      VrmPlayback.Source animation=time->{
        double st=vrmSourceTime(time);var base=liveVrm.sample().apply(st);
        var supplied=new LinkedHashMap<>(hostPoses);hostRotations.forEach((n,q)->supplied.put(n,new Pose(supplied.getOrDefault(n,Pose.IDENTITY).position(),q)));
        var pose=mixer.nodePose(base.pose(),rig,supplied);var rotations=new ArrayList<>(base.rotations());
        for(int n=0;n<boneCount;n++)if(pose.localMatrices().get(n)!=base.pose().localMatrices().get(n))rotations.set(n,SceneRig.rotation(pose.localMatrices().get(n)));
        pose=new ScenePose(time,pose.localMatrices(),pose.globalMatrices(),pose.morphWeights(),pose.extensionChannels());
        var inputs=new LinkedHashMap<>(base.input().expressions());inputs.putAll(features.vrm(features.authoredBase(liveVrm.authored(),st)));
        var nativeWeights=mixer.vrmExpressions(inputs);
        var weights=features.vrm(features.semantic(null,st,time,nativeWeights.keySet(),false),time,nativeWeights);
        var evaluated=avatarEvaluation.evaluatePose(pose,rotations,new VrmEvaluation.Input(weights,base.input().categories(),effective.features().procedural().eyeTracking()?new VrmEvaluation.Angles(features.yaw,features.pitch):base.input().gaze()));
        return vrmNodeMorphs(evaluated,liveVrm.authored(),st,time);
      };
      var player=services.playback(avatar,animation,settings.vrm());var materials=services.materials(avatar);
      physicsInput=player::physicsInput;physicsEnvironment=player::physicsEnvironment;
      poseOverlay=poses->{};boneOverlay=rotations->{for(int n:rotations.keySet())if(n<0||n>=boneCount)throw new IllegalArgumentException("Rotation outside VRM skeleton");};
      switchSource=(next,range,elapsed)->{
        var candidate=vrmSource(services,avatar,next,effective);var sample=candidate.sample().apply(range==null?elapsed:range.sample(elapsed));
        var supplied=new LinkedHashMap<>(hostPoses);hostRotations.forEach((n,q)->supplied.put(n,new Pose(supplied.getOrDefault(n,Pose.IDENTITY).position(),q)));
        var candidatePose=mixer.nodePose(sample.pose(),rig,supplied);var rotations=new ArrayList<>(sample.rotations());
        for(int n=0;n<boneCount;n++)if(candidatePose.localMatrices().get(n)!=sample.pose().localMatrices().get(n))rotations.set(n,SceneRig.rotation(candidatePose.localMatrices().get(n)));
        avatarEvaluation.evaluatePose(candidatePose,rotations,sample.input());
        liveVrm=candidate;sourceStart=lastSeconds-elapsed;liveRange=range;this.selection=next;
      };
      evaluator=time->{var frame=player.seek(time);var projected=views.project(scaled(geometry.compile(frame.pose(),settings.deferVrmDeformation())));
        return new Frame(time,projected.thirdPerson(),projected.firstPerson(),new Vrm(frame,materials.evaluate(frame.materials(),vrmSourceTime(time))));};
    } else if(model instanceof ScenePackageAssets.Pmx || model instanceof ScenePackageAssets.Pmd) {
      AnimationClip clip;
      if(authored) clip=authoredClip;
      else if(generated) {
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
      var physics=settings.mmd().replayable()?YsmRuntime.physics():YsmRuntime.hostPhysics();
      final AnimationClip baseClip=clip;
      MmdAnimationSource baseProgram=model instanceof ScenePackageAssets.Pmx pmx?MmdAnimationLayers.singleClip(pmx.value(),baseClip,settings.animationRange()):time->new MmdAnimationSource.Sample(services.sample(baseClip,sourceTime(settings,time)),List.of());
      liveBase=baseProgram;
      liveAuthored=authoredName;liveRange=settings.animationRange();
      MmdAnimationSource program=time->{var base=liveBase.sample(time);double elapsed=Math.max(0,time-sourceStart);double st=liveRange==null?elapsed:liveRange.sample(elapsed);return new MmdAnimationSource.Sample(features.mmdFrame(mixer.apply(mixer.poseBase(base.animation(),hostPoses)),time,liveAuthored,st),base.diagnostics());};
      if(model instanceof ScenePackageAssets.Pmx pmx) { mesh=pmx.mesh();player=MmdPlayer.packed(pmx.value(),program,physics,mmdSettings.withAnimationRange(null),Map.of(),deformation); }
      else { var pmd=(ScenePackageAssets.Pmd)model;mesh=pmd.mesh();player=MmdPlayer.packed(pmd.value(),program,physics,mmdSettings.withAnimationRange(null),Map.of(),deformation); }
      closer=player::close;invalidate=player::animationChanged;
      switchSource=(next,range,elapsed)->{
        var nextClip=mmdClip(model,assets,next,effective);
        // Input invalidation releases the cached frame, never the simulation clock.
        double start=lastSeconds-elapsed;
        MmdAnimationSource nextBase=model instanceof ScenePackageAssets.Pmx pmx?MmdAnimationLayers.singleClip(pmx.value(),nextClip,range):t->new MmdAnimationSource.Sample(services.sample(nextClip,range==null?t:range.sample(t)),List.of());
        liveBase=t->nextBase.sample(Math.max(0,t-start));liveAuthored=next.sourceId().startsWith("@ysm/profile/")?next.sourceId().substring(13):null;sourceStart=start;liveRange=range;this.selection=next;player.animationChanged();
      };
      physicsInput=player::physicsInput;
      physicsEnvironment=player::physicsEnvironment;
      boneOverlay=player::boneRotations;
      poseOverlay=player::bonePoses;ikOverlay=player::ikOverrides;
      evaluator=time->{
        var rotations=new LinkedHashMap<>(hostRotations);features.eyes().forEach(rotations::putIfAbsent);player.boneRotations(rotations);
        var frame=player.seek(time);
        var draw=services.geometry(mesh,frame,new SceneAsset.Coordinates(false,source.settings().metersPerUnit(),"Y"));
        return new Frame(time,draw,draw,new Mmd(frame));
      };
    } else if(model instanceof ScenePackageAssets.Fbx fbx) {
      if(authored||!effective.features().expressions().isEmpty()||!effective.features().layers().isEmpty())throw new IllegalArgumentException("Authored FBX interactions require an explicit FBX channel adapter");
      if(selected!=null && !selection.sourceId().equals(source.model().id()))
        throw new IllegalArgumentException("External FBX animation requires explicit skeleton retargeting");
      // FBX sources without AnimationStack entries still have a valid rest
      // pose.  A transient selection carrying clip zero must not turn that
      // rest pose into native stack 0 (which is outside the native scene).
      int stack=selected==null||fbx.value().animations().isEmpty()?-1:
          requireClip(selection.clip(),fbx.value().animations().size());
      var player=services.evaluator(fbx.value(),limits,new FbxEvaluation.Settings(source.settings().fbxSkinSpace()));
      FbxPhysicsBinding physics=null;
      if(settings.mmd().physicsEnabled() && source.files().containsKey(FbxPhysicsProfile.PACKAGE_PATH)) {
        var fbxProfile=new cc.sirrus.ysmlib.scene.java.FbxPhysicsProfileCodec().read(source.files().get(FbxPhysicsProfile.PACKAGE_PATH));
        if(!fbxProfile.bodies().isEmpty()) {
          var provider=settings.mmd().replayable()?YsmRuntime.physics():YsmRuntime.hostPhysics();
          physics=new FbxPhysicsBinding(fbx.value(),fbxProfile,provider,new PhysicsSpec.World(settings.mmd().gravity(),1f/settings.mmd().frequencyHz(),settings.mmd().solverIterations(),settings.mmd().replayable(),settings.mmd().kinematicFilter()));
        }
      }
      final FbxPhysicsBinding fbxPhysics=physics;closer=()->{try{player.close();}finally{if(fbxPhysics!=null)fbxPhysics.close();}};
      if(fbxPhysics!=null){physicsInput=input->{if(input==null){fbxMotion.reset();fbxPhysics.environment(null);}else fbxPhysics.environment(fbxMotion.update(input));};physicsEnvironment=fbxPhysics::environment;}
      // FBX has no MMD IK controller, but its skin clusters can consume the
      // same evaluated source poses produced by YSM's skeleton retargeter.
      // Apply those poses after the source frame is sampled so the native and
      // managed FBX evaluators share one deformation path.
      poseOverlay=poses->{hostPoses=Map.copyOf(poses);};
      ikOverlay=switches->{if(!switches.isEmpty())throw new UnsupportedOperationException("FBX targets do not expose MMD IK controllers");};
      evaluator=time->{
        double sourceSeconds=sourceTime(settings,time);var nativePlayer=player instanceof FbxNativePlayer p?p:null;FbxEvaluation.Frame frame;GeometryFrame draw;
        boolean hostPosesAppliedByNative=false;
        // FbxResilientPlayer deliberately owns the retry-to-rest policy for
        // malformed or exporter-specific animation samples. Calling the
        // native geometry fast path here would bypass that policy because it
        // invokes FbxNativePlayer directly. Use the normal evaluated-frame
        // path for the resilient wrapper so a bad sample can never escape to
        // the render thread.
        if(fbxPhysics==null&&nativePlayer!=null) {
          // YSM skeleton retargeting is a per-frame FBX skin delta. Keep it in
          // the resident ufbx skin pass when the native evaluator can express
          // the transform; otherwise fall back to the checked Java projection
          // below. This avoids copying and blending every FBX corner on the
          // render thread for the normal linear-skin path.
          if(!hostPoses.isEmpty()) {
            var physical=nativePlayer.evaluatePhysicsDeltas(stack,sourceSeconds,hostPoses);
            if(physical!=null) { frame=physical;draw=services.geometry(fbx.value(),frame);hostPosesAppliedByNative=true; }
            else { var nativeGeometry=nativePlayer.evaluateGeometry(stack,sourceSeconds);frame=nativeGeometry.frame();draw=nativeGeometry.geometry(); }
          } else { var nativeGeometry=nativePlayer.evaluateGeometry(stack,sourceSeconds);frame=nativeGeometry.frame();draw=nativeGeometry.geometry(); }
        }
        else { frame=fbxPhysics!=null&&nativePlayer!=null?nativePlayer.evaluateNodes(stack,sourceSeconds):player.evaluate(stack,sourceSeconds);
        if(fbxPhysics==null) draw=services.geometry(fbx.value(),frame);
        else {
          var poses=fbxPhysics.step(frame,sourceSeconds);
          if(player instanceof FbxNativePlayer fbxNative) {
            var physical=fbxNative.evaluatePhysics(frame,sourceSeconds,poses);
            if(physical!=null) { frame=physical;draw=services.geometry(fbx.value(),frame); }
            else { frame=player.evaluate(stack,sourceSeconds);draw=FbxPhysicsGeometry.apply(fbx.value(),frame,services.geometry(fbx.value(),frame),poses); }
          } else draw=FbxPhysicsGeometry.apply(fbx.value(),frame,services.geometry(fbx.value(),frame),poses);
        }}
        if(!hostPoses.isEmpty()&&!hostPosesAppliedByNative) draw=FbxPhysicsGeometry.applyLocalDeltas(fbx.value(),frame,draw,hostPoses);
        draw=scaled(draw);
        return new Frame(time,draw,draw,new Fbx(frame));
      };
    } else throw new IllegalArgumentException("PMM multi-object playback is not yet implemented; the parsed project remains available");
    var raw=evaluator;evaluate=time->{var frame=raw.apply(time);var references=mixer.referenceWeights(hostPoses.keySet());return new Frame(frame.seconds(),frame.thirdPerson(),features.firstPerson(frame.firstPerson()),frame.details(),references.keySet(),references);};release=closer;
  }
  private VrmEvaluation.Frame vrmNodeMorphs(VrmEvaluation.Frame frame,String authored,double sourceSeconds,double seconds) {
    var pose=features.nodeMorphs(frame.pose(),features.authored(authored,sourceSeconds),seconds,false);
    pose=features.nodeMorphs(pose,features.semantic(authored,sourceSeconds,seconds),seconds);
    return new VrmEvaluation.Frame(pose,frame.localRotations(),frame.materials(),frame.expressions(),frame.gaze());
  }
  private static AnimationClip mmdClip(ScenePackageAssets.Document model,ScenePackageAssets assets,Selection selected,SceneModelProfile profile) {
    String id=selected.sourceId();if(id.isEmpty()){if(selected.clip()!=-1)throw new IllegalArgumentException("Rest has no clip");return new AnimationClip("",List.of(),30);}
    if(id.startsWith("@ysm/profile/")){if(selected.clip()!=-1)throw new IllegalArgumentException("Authored clip has no index");return SceneFeatureBindings.clip(profile,id.substring(13),true,false);}
    if(id.startsWith("@ysm/generated/")){if(selected.clip()!=-1)throw new IllegalArgumentException("Generated clip has no index");String state=id.substring(15);return model instanceof ScenePackageAssets.Pmx p?MmdBasicAnimationFactory.forState(p.value(),state,profile.bones()):MmdBasicAnimationFactory.forState(((ScenePackageAssets.Pmd)model).value(),state,profile.bones());}
    singleClip(selected);var d=assets.documents().get(id);if(d instanceof ScenePackageAssets.Vmd v)return v.animation().clip();if(d instanceof ScenePackageAssets.Vpd v)return v.animation();throw new IllegalArgumentException("Invalid MMD source: "+id);
  }
  public boolean select(Selection next,AnimationPlaybackRange range){return select(next,range,0);}
  public boolean select(Selection next,AnimationPlaybackRange range,double elapsed){open();if(!Double.isFinite(elapsed)||elapsed<0)throw new IllegalArgumentException("Invalid source offset");if(switchSource==null)return false;switchSource.accept(next,range,elapsed);current=null;return true;}
  private double vrmSourceTime(double time){double elapsed=Math.max(0,time-sourceStart);return liveRange==null?elapsed:liveRange.sample(elapsed);}
  private VrmSource vrmSource(SceneProvider services,VrmDocument avatar,Selection selection,SceneModelProfile profile) {
    String id=selection.sourceId();var selected=id.isEmpty()?null:assets.documents().get(id);
    if(selected instanceof ScenePackageAssets.Vrma vrma) {
      var retarget=services.retarget(vrma.value(),avatar,VrmaEvaluation.Settings.automatic());requireEvaluated(retarget.compatibility());
      int clip=requireClip(selection.clip(),vrma.value().animations().size());return new VrmSource(time->retarget.sample(clip,time),null);
    }
    String authored=id.startsWith("@ysm/profile/")?id.substring(13):null;
    if(authored!=null&&selection.clip()!=-1||id.isEmpty()&&selection.clip()!=-1)throw new IllegalArgumentException("Virtual source has no clip index");
    if(authored==null&&!id.isEmpty()&&selected==null)throw new IllegalArgumentException("Unknown VRM animation source: "+id);
    var clip=authored!=null?SceneFeatureBindings.clip(profile,authored,false,false):localClip(avatar.scene(),assets.source(),selection,selected);
    var scene=new cc.sirrus.ysmlib.scene.java.SceneEvaluator(avatar.scene());var rest=new SceneRig(avatar.scene()).restRotations();
    return new VrmSource(time->{var pose=scene.evaluate(clip,time);var rotations=new ArrayList<>(rest);
      if(clip!=null)for(var track:clip.tracks())if(track.property()==AnimationClip.Property.ROTATION){float[] q=cc.sirrus.ysmlib.scene.java.CurveSampler.sample(track.curve(),time);rotations.set(track.targetIndex(),Rotation.normalized(q[0],q[1],q[2],q[3]));}
      return new VrmaEvaluation.Sample(pose,rotations,VrmEvaluation.Input.NONE);},authored);
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
    var handled=Set.of("VRM","VRMC_vrm","VRMC_springBone","VRMC_springBone_extended_collider","VRMC_springBone_limit","VRMC_node_constraint","VRMC_materials_mtoon");
    var report=avatar.gltf().scene().compatibility();
    requireEvaluated(new CompatibilityReport(report.features().stream().filter(f->!handled.contains(f.id())).toList(),report.diagnostics()));
  }
  private GeometryFrame scaled(GeometryFrame frame) {
    var c=frame.coordinates();return new GeometryFrame(frame.seconds(),new SceneAsset.Coordinates(c.rightHanded(),assets.source().settings().metersPerUnit(),c.upAxis()),frame.draws());
  }
  public ScenePackageAssets assets() { return assets; }
  public Selection selection() { return selection; }
  public void physicsInput(ScenePhysicsInput input){if(closed)throw new IllegalStateException("Scene package player is closed");physicsInput.accept(input);}
  public void physicsEnvironment(cc.sirrus.ysmlib.scene.physics.PhysicsEnvironment environment){if(closed)throw new IllegalStateException("Scene package player is closed");physicsEnvironment.accept(environment);}
  public void boneRotations(Map<Integer,Rotation> rotations) {open();var next=Map.copyOf(rotations);boneOverlay.accept(next);hostRotations=next;current=null; }
  public void bonePoses(Map<Integer,Pose> poses) { open();var next=Map.copyOf(poses);int count=boneCount;for(int b:next.keySet())if(b<0||b>=count)throw new IllegalArgumentException("Bone pose outside source");var filtered=new LinkedHashMap<>(next);mixer.ownedBones().forEach(filtered::remove);poseOverlay.accept(filtered);hostPoses=next;invalidate.run();current=null; }
  public void ikOverrides(Map<Integer,Boolean> switches) { if(closed)throw new IllegalStateException("Scene package player is closed");ikOverlay.accept(switches);current=null; }
  private void open(){if(closed)throw new IllegalStateException("Scene package player is closed");}
  private void publishPoses(){var poses=new LinkedHashMap<>(hostPoses);mixer.ownedBones().forEach(poses::remove);poseOverlay.accept(poses);}
  public void expressions(Map<String,Float> weights){open();features.expressions(weights);invalidate.run();current=null;}
  public void expressionAnimation(String name,double seconds){open();features.expressionAnimation(name,seconds);invalidate.run();current=null;}
  public void sourcePoses(Map<Integer,Pose> poses){open();var next=Map.copyOf(poses);for(int b:next.keySet())if(b<0||b>=boneCount)throw new IllegalArgumentException("Source pose outside skeleton");mixer.sourcePoses(next);invalidate.run();current=null;}
  public void gaze(double yaw,double pitch){open();if(!Double.isFinite(yaw)||!Double.isFinite(pitch))throw new IllegalArgumentException("Invalid gaze");features.yaw=yaw;features.pitch=pitch;current=null;}
  public void layers(List<Layer> layers){open();mixer.layers(layers);publishPoses();invalidate.run();current=null;}
  public Frame seek(double seconds) {
    if(closed) throw new IllegalStateException("Scene package player is closed");
    if(!Double.isFinite(seconds)) throw new IllegalArgumentException("Non-finite scene time");
    if(current!=null && current.seconds()==seconds) return current;
    features.lastTime=seconds;
    var next=evaluate.apply(seconds);current=next;lastSeconds=seconds;return next;
  }
  public void close() { if(!closed) { closed=true;release.run(); } }
}
