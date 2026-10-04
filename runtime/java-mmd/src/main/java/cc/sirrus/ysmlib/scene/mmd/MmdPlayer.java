package cc.sirrus.ysmlib.scene.mmd;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.java.ClipSampler;
import cc.sirrus.ysmlib.scene.physics.PhysicsProvider;
import java.util.*;

/** Shared managed animation/physics player. Backward seeks replace the complete rig and solver together. */
public final class MmdPlayer implements MmdPlayback {
  private final PmxDocument source;
  private final MmdAnimationSource animationSource;
  private List<CompatibilityReport.Diagnostic> animationDiagnostics=List.of();
  private final PhysicsProvider provider;
  private final Settings settings;
  private final boolean packedPhysics;
  private final MeshAsset mesh;
  private final DeformationProvider.Session deformation;
  private final boolean deferredDeformation;
  private final MmdPhysicsClock liveClock;
  private long discardedLiveSteps;
  public synchronized long discardedLiveSteps(){return discardedLiveSteps;}
  private final Map<Integer,Pose> outside;
  private final ClipSampler sampler=new ClipSampler();
  private Map<Integer,Rotation> overlays=Map.of();
  private Map<Integer,Pose> poseOverrides=Map.of();
  private Map<Integer,Float> expressions=Map.of();
  private Map<Integer,Boolean> ikSwitches=Map.of();
  private AnimationFrame vpd;
  private final Map<String,List<Integer>> boneNames;
  private boolean overlayDirty;
  @Override public synchronized void bonePoses(Map<Integer,Pose> poses) {
    open();var next=Map.copyOf(poses);
    for(int index:next.keySet())if(index<0 || index>=source.bones().size())throw new IllegalArgumentException("Invalid pose override bone");
    if(!next.equals(poseOverrides)){poseOverrides=next;overlayDirty=true;}
  }
  @Override public synchronized void boneRotations(Map<Integer,Rotation> rotations) {
    open();
    var next=Map.copyOf(rotations);for(int index:next.keySet()) if(index<0 || index>=source.bones().size()) throw new IllegalArgumentException("Invalid overlay bone");
    if(!next.equals(overlays)) { overlays=next;overlayDirty=true; }
  }
  @Override public synchronized void vpdPose(VpdDocument pose) {
    open();var next=pose==null?null:sampler.sample(new VpdReader().animation(pose),0);
    if(!Objects.equals(next,vpd)){vpd=next;overlayDirty=true;}
  }
  @Override public synchronized void morphWeights(Map<Integer,Float> weights) {
    open();var next=Map.copyOf(weights);
    for(var entry:next.entrySet())if(entry.getKey()<0 || entry.getKey()>=source.morphs().size() || !Float.isFinite(entry.getValue()))throw new IllegalArgumentException("Invalid expression override");
    if(!next.equals(expressions)){expressions=next;overlayDirty=true;}
  }
  @Override public synchronized void ikOverrides(Map<Integer,Boolean> switches) {
    open();var next=Map.copyOf(switches);for(int index:next.keySet())if(index<0 || index>=source.bones().size())throw new IllegalArgumentException("Invalid IK override bone");
    if(!next.equals(ikSwitches)){ikSwitches=next;overlayDirty=true;}
  }
  private Simulation simulation;
  private Frame current;
  private boolean closed,failed;
  private final List<PmdRuntimeProfile.Constraint> pmdConstraints;

  private AnimationFrame sampleAnimation(double time) {
    var sampled=animationSource.sample(time);var frame=sampled.animation();animationDiagnostics=sampled.diagnostics();
    if(overlays.isEmpty() && poseOverrides.isEmpty() && expressions.isEmpty() && ikSwitches.isEmpty() && vpd==null)return frame;
    var channels=new ArrayList<AnimationFrame.Channel>(frame.channels());
    if(vpd!=null)channels.addAll(vpd.channels());
    poseOverrides.forEach((index,pose)->{
      var p=pose.position();var q=pose.rotation();
      channels.add(new AnimationFrame.Channel(AnimationClip.Property.TRANSLATION,index,"","",new FloatData(p.x(),p.y(),p.z())));
      channels.add(new AnimationFrame.Channel(AnimationClip.Property.ROTATION,index,"","",new FloatData(q.x(),q.y(),q.z(),q.w())));
    });
    expressions.forEach((index,weight)->channels.add(new AnimationFrame.Channel(AnimationClip.Property.MORPH_WEIGHTS,index,"","",new FloatData(weight))));
    ikSwitches.forEach((index,enabled)->channels.add(new AnimationFrame.Channel(AnimationClip.Property.IK_ENABLED,index,"","",new FloatData(enabled?1:0))));
    var evaluated=List.copyOf(channels);
    for(var entry:overlays.entrySet()) {
      int index=entry.getKey();var base=Rotation.IDENTITY;String name=source.bones().get(index).names().local();
      for(var c:evaluated) if(c.property()==AnimationClip.Property.ROTATION && matches(c,index)) {
        var v=c.value();base=Rotation.normalized(v.get(0),v.get(1),v.get(2),v.get(3));
      }
      var q=base.multiply(entry.getValue());
      channels.add(new AnimationFrame.Channel(AnimationClip.Property.ROTATION,index,name,"",new FloatData(q.x(),q.y(),q.z(),q.w())));
    }
    return new AnimationFrame(frame.seconds(),channels);
  }
  private boolean matches(AnimationFrame.Channel channel,int index) {
    if(channel.targetIndex()>=0)return channel.targetIndex()==index;
    var bound=boneNames.get(channel.binding());return bound!=null && bound.get(0)==index;
  }

  private final class Simulation implements AutoCloseable {
    final MmdRig rig=new MmdRig(source,pmdConstraints);
    final MmdRig fractional=settings.physicsEnabled()?new MmdRig(source,pmdConstraints):null;
    final MmdPhysicsBinding physics;
    final List<CompatibilityReport.Diagnostic> physicsDiagnostics=new ArrayList<>();
    long step;
    Simulation() {
      rig.begin(sampleAnimation(0),outside);rig.update(false);
      physics=settings.physicsEnabled()?new MmdPhysicsBinding(source,mesh,rig,provider,settings,physicsDiagnostics,packedPhysics,deformation):null;rig.update(true);
    }
    void advanceTo(long target) {
      while(step<target) {
        double time=(step+1d)/settings.frequencyHz();rig.begin(sampleAnimation(time),outside);rig.update(false);
        physics.step(rig);rig.update(true);step++;
      }
    }
    Frame sample(double time) {
      if(physics==null) {
        rig.begin(sampleAnimation(time),outside);rig.update(false);rig.update(true);
        var pose=withDiagnostics(rig.pose());
        var attributes=mesh.primitives().isEmpty()?Map.<String,MeshAsset.Attribute>of():
            deformation.deform(pose.palette(),pose.morphs().meshWeights(),SceneProvider.NormalPolicy.MMD_WEIGHTED_ROTATION);
        return new Frame(time,0,pose,Collections.nCopies(mesh.primitives().size(),attributes),!deferredDeformation);
      }
      double simulated=(double)step/settings.frequencyHz();MmdRig output=rig;
      if(time>simulated+1e-12 || overlayDirty) {
        output=fractional;System.arraycopy(rig.world,0,output.world,0,rig.world.length);
        output.begin(sampleAnimation(time),outside);output.update(false);physics.reflect(output);output.update(true);
      }
      var pose=withDiagnostics(output.pose());
      return new Frame(time,simulated,pose,physics.geometry(pose),!deferredDeformation);
    }
    private MmdPose withDiagnostics(MmdPose pose) {
      if(physicsDiagnostics.isEmpty() && animationDiagnostics.isEmpty())return pose;
      var all=new ArrayList<CompatibilityReport.Diagnostic>(pose.diagnostics());all.addAll(animationDiagnostics);all.addAll(physicsDiagnostics);
      return new MmdPose(pose.animation(),pose.visible(),pose.bones(),pose.palette(),pose.morphs(),all);
    }
    @Override public void close() { if(physics!=null) physics.close(); }
  }

  public MmdPlayer(PmxDocument source,AnimationClip clip,PhysicsProvider provider,Settings settings,Map<Integer,Pose> outsideParents) {
    this(source,null,clip,provider,settings,outsideParents);
  }
  public MmdPlayer(PmxDocument source,MmdAnimationSource animationSource,PhysicsProvider provider,Settings settings,Map<Integer,Pose> outsideParents) {
    this(source,null,null,Objects.requireNonNull(animationSource),provider,settings,outsideParents);
  }
  public MmdPlayer(PmdDocument source,AnimationClip clip,PhysicsProvider provider,Settings settings,Map<Integer,Pose> outsideParents) {
    this(new PmdRuntimeProfile(source),clip,provider,settings,outsideParents);
  }
  public MmdPlayer(PmdDocument source,MmdAnimationSource animationSource,PhysicsProvider provider,Settings settings,Map<Integer,Pose> outsideParents) {
    this(new PmdRuntimeProfile(source),animationSource,provider,settings,outsideParents);
  }
  private MmdPlayer(PmdRuntimeProfile profile,AnimationClip clip,PhysicsProvider provider,Settings settings,Map<Integer,Pose> outsideParents) {
    this(profile.asset,profile.constraints,clip,provider,settings,outsideParents);
  }
  private MmdPlayer(PmxDocument source,List<PmdRuntimeProfile.Constraint> pmdConstraints,AnimationClip clip,PhysicsProvider provider,Settings settings,Map<Integer,Pose> outsideParents) {
    this(source,pmdConstraints,Objects.requireNonNull(clip),null,provider,settings,outsideParents);
  }
  private MmdPlayer(PmdRuntimeProfile profile,MmdAnimationSource animationSource,PhysicsProvider provider,Settings settings,Map<Integer,Pose> outsideParents) {
    this(profile.asset,profile.constraints,null,Objects.requireNonNull(animationSource),provider,settings,outsideParents);
  }
  private MmdPlayer(PmxDocument source,List<PmdRuntimeProfile.Constraint> pmdConstraints,AnimationClip clip,MmdAnimationSource animationSource,PhysicsProvider provider,Settings settings,Map<Integer,Pose> outsideParents) {
    this(source,pmdConstraints,clip,animationSource,provider,settings,outsideParents,false);
  }
  /** Packed-transfer entry point used by the shared runtime. */
  public static MmdPlayer packed(PmxDocument source,AnimationClip clip,PhysicsProvider provider,Settings settings,Map<Integer,Pose> outsideParents) {
    return new MmdPlayer(source,null,Objects.requireNonNull(clip),null,provider,settings,outsideParents,true);
  }
  public static MmdPlayer packed(PmxDocument source,MmdAnimationSource program,PhysicsProvider provider,Settings settings,Map<Integer,Pose> outsideParents) {
    return new MmdPlayer(source,null,null,Objects.requireNonNull(program),provider,settings,outsideParents,true);
  }
  public static MmdPlayer packed(PmdDocument source,AnimationClip clip,PhysicsProvider provider,Settings settings,Map<Integer,Pose> outsideParents) {
    var profile=new PmdRuntimeProfile(source);
    return new MmdPlayer(profile.asset,profile.constraints,Objects.requireNonNull(clip),null,provider,settings,outsideParents,true);
  }
  public static MmdPlayer packed(PmdDocument source,MmdAnimationSource program,PhysicsProvider provider,Settings settings,Map<Integer,Pose> outsideParents) {
    var profile=new PmdRuntimeProfile(source);
    return new MmdPlayer(profile.asset,profile.constraints,null,Objects.requireNonNull(program),provider,settings,outsideParents,true);
  }
  private MmdPlayer(PmxDocument source,List<PmdRuntimeProfile.Constraint> pmdConstraints,AnimationClip clip,MmdAnimationSource animationSource,PhysicsProvider provider,Settings settings,Map<Integer,Pose> outsideParents,boolean packedPhysics) {
    this(source,pmdConstraints,clip,animationSource,provider,settings,outsideParents,packedPhysics,new cc.sirrus.ysmlib.scene.java.JavaDeformationProvider());
  }
  public static MmdPlayer packed(PmxDocument source,AnimationClip clip,PhysicsProvider provider,Settings settings,Map<Integer,Pose> outsideParents,DeformationProvider deformation) {
    return new MmdPlayer(source,null,Objects.requireNonNull(clip),null,provider,settings,outsideParents,true,deformation);
  }
  public static MmdPlayer packed(PmxDocument source,MmdAnimationSource program,PhysicsProvider provider,Settings settings,Map<Integer,Pose> outsideParents,DeformationProvider deformation) {
    return new MmdPlayer(source,null,null,Objects.requireNonNull(program),provider,settings,outsideParents,true,deformation);
  }
  public static MmdPlayer packed(PmdDocument source,AnimationClip clip,PhysicsProvider provider,Settings settings,Map<Integer,Pose> outsideParents,DeformationProvider deformation) {
    var profile=new PmdRuntimeProfile(source);return new MmdPlayer(profile.asset,profile.constraints,Objects.requireNonNull(clip),null,provider,settings,outsideParents,true,deformation);
  }
  private MmdPlayer(PmxDocument source,List<PmdRuntimeProfile.Constraint> pmdConstraints,AnimationClip clip,MmdAnimationSource animationSource,PhysicsProvider provider,Settings settings,Map<Integer,Pose> outsideParents,boolean packedPhysics,DeformationProvider deformer) {
    this.packedPhysics=packedPhysics;
    this.pmdConstraints=pmdConstraints;
    this.source=Objects.requireNonNull(source);this.provider=Objects.requireNonNull(provider);
    if(animationSource!=null && settings.animationRange()!=null)throw new IllegalArgumentException("Animation programs own their per-layer playback ranges");
    this.animationSource=animationSource==null?MmdAnimationLayers.singleClip(source,Objects.requireNonNull(clip),settings.animationRange()):animationSource;
    boneNames=MmdRig.names(source.bones().stream().map(PmxDocument.Bone::names).toList());
    this.settings=Objects.requireNonNull(settings);outside=Map.copyOf(outsideParents);mesh=new MmdMeshCompiler().compile(source);
    liveClock=settings.replayable()?null:new MmdPhysicsClock(settings.frequencyHz(),settings.maxLiveSubsteps());
    deferredDeformation=Objects.requireNonNull(deformer).deferred();
    if(deferredDeformation&&!source.softBodies().isEmpty())throw new IllegalArgumentException("Soft-body playback requires CPU pin/deformation data");
    deformation=mesh.primitives().isEmpty()?null:Objects.requireNonNull(deformer).compile(mesh.primitives().get(0));
    try { simulation=new Simulation();current=simulation.sample(0); }
    catch(RuntimeException|Error failure) { if(simulation!=null)simulation.close();if(deformation!=null)deformation.close();throw failure; }
  }
  @Override public synchronized Frame current() { open();return current; }
  @Override public synchronized Frame advance(double elapsedSeconds) {
    open();if(!Double.isFinite(elapsedSeconds) || elapsedSeconds<0) throw new IllegalArgumentException("Invalid playback delta");return seek(current.seconds()+elapsedSeconds);
  }
  @Override public synchronized Frame seek(double seconds) {
    open();if(!Double.isFinite(seconds) || seconds<0 || seconds*settings.frequencyHz()>Long.MAX_VALUE-1024d) throw new IllegalArgumentException("Invalid playback time");
    if(current.seconds()==seconds && !overlayDirty) return current;
    if(!settings.physicsEnabled()) { current=simulation.sample(seconds);overlayDirty=false;return current; }
    long target=(long)Math.floor(seconds*settings.frequencyHz()+1e-9);boolean backwards=seconds<current.seconds();
    if(backwards && !settings.replayable()) throw new IllegalStateException("Backward seek is disabled in this live session");
    long work=target-(backwards?0:simulation.step);
    if(work>settings.maxStepsPerSeek()) throw new IllegalArgumentException("Playback seek work budget exceeded");
    if(liveClock!=null) {
      var tick=liveClock.consume(seconds-current.seconds());
      long skipped=Math.max(0,work-tick.steps());
      simulation.step+=skipped;discardedLiveSteps+=skipped;
    }
    if(backwards) {
      var candidate=new Simulation();
      try { candidate.advanceTo(target);var frame=candidate.sample(seconds);var old=simulation;simulation=candidate;current=frame;old.close(); }
      catch(RuntimeException|Error failure) { candidate.close();throw failure; }
    } else {
      try { simulation.advanceTo(target);current=simulation.sample(seconds); }
      catch(RuntimeException|Error failure) { failed=true;throw failure; }
    }
    overlayDirty=false;return current;
  }
  private void open() { if(closed || failed) throw new IllegalStateException("MMD playback session is closed or failed"); }
  @Override public synchronized void close() { if(!closed) { closed=true;try{simulation.close();}finally{if(deformation!=null)deformation.close();} } }
}
