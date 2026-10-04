package cc.sirrus.ysmlib.scene.mmd;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import cc.sirrus.ysmlib.scene.java.*;
import cc.sirrus.ysmlib.scene.physics.wasm.WasmPhysicsProvider;
import java.util.*;
import org.junit.jupiter.api.Test;
import static cc.sirrus.ysmlib.scene.mmd.PmxDocument.*;
import static cc.sirrus.ysmlib.scene.mmd.MmdRigTest.*;
import static org.junit.jupiter.api.Assertions.*;

class MmdPlaybackTest {
  @Test void actualPackedPlayerReceivesWorldGravityTerrainAndTeleportReset() throws Exception {
    var model=withPhysics(source(),List.of(body(0,0),body(1,1)),List.of(),List.of());
    var empty=new AnimationClip("",List.of(),30);
    try(var actual=MmdPlayer.packed(model,empty,new WasmPhysicsProvider(),MmdPlayback.Settings.preview(),Map.of())) {
      var floor=new ScenePhysicsInput.Box(new Vec3(-10,-1,-10),new Vec3(10,0,10));
      actual.physicsInput(new ScenePhysicsInput(0,30_000_000,0,0,Matrix4.IDENTITY,new Vec3(0,-9.8f,0),List.of(floor),List.of(),ScenePhysicsInput.Settings.defaults()));
      var resting=actual.seek(1);assertEquals(.1,resting.pose().bones().get(1).position().y(),.025);
      assertSame(resting,actual.seek(1));
      assertThrows(IllegalStateException.class,()->actual.seek(.5));
      actual.physicsInput(new ScenePhysicsInput(1,30_000_020,0,0,Matrix4.IDENTITY,Vec3.ZERO,List.of(),List.of(),ScenePhysicsInput.Settings.defaults()));
      var reset=actual.seek(1+1.0/60);assertEquals(1,reset.pose().bones().get(1).position().y(),.001);
      actual.physicsInput(null);assertDoesNotThrow(()->actual.seek(.5));
    }
  }
  private ByteData resource(String name) throws Exception { try(var input=getClass().getResourceAsStream("/mmd-oracle/"+name)) { return new ByteData(Objects.requireNonNull(input).readAllBytes()); } }
  private PmxDocument source() throws Exception { return new PmxReader().read(resource("skin.pmx"),ReadLimits.DEFAULT); }
  private AnimationClip clip() throws Exception { return new VmdAnimation().compile(new VmdReader().read(resource("skin.vmd"),ReadLimits.DEFAULT)).clip(); }
  private static PmxDocument withPhysics(PmxDocument d,List<RigidBody> bodies,List<Joint> joints,List<SoftBody> soft) {
    return new PmxDocument(d.version(),d.globals(),d.names(),d.comment(),d.englishComment(),d.vertices(),d.indices(),d.textures(),d.materials(),d.bones(),d.morphs(),d.displayFrames(),bodies,joints,soft,d.source());
  }
  private static RigidBody body(int bone,int mode) {
    return new RigidBody(names("body"+bone),bone,0,0,0,new Vec3(.1f,0,0),new Vec3(0,bone,0),Vec3.ZERO,1,0,0,0,0,mode);
  }
  private static RigidBody invalidUnitBody(int bone) {
    return new RigidBody(names("invalid"),bone,0,0,0,new Vec3(.1f,0,0),new Vec3(0,bone,0),Vec3.ZERO,1,2,-1,Float.NaN,0,bone==0?0:1);
  }
  private MmdPlayer player(PmxDocument source,AnimationClip clip) { return new MmdPlayer(source,clip,new WasmPhysicsProvider(),MmdPlayback.Settings.preview(),Map.of()); }

  @Test void productionCompiledClipMatchesUncachedVmdThroughPhysicsLoopReplayAndYsmOverrides() throws Exception {
    var model=withPhysics(source(),List.of(body(0,0),body(1,1)),List.of(),List.of());var clip=clip();
    var provider=new WasmPhysicsProvider();var sampler=new ClipSampler();
    for(boolean loop:new boolean[]{false,true}) {
      var range=new AnimationPlaybackRange(0,1,loop);var settings=MmdPlayback.Settings.preview().withAnimationRange(range);
      MmdAnimationSource reference=time->new MmdAnimationSource.Sample(sampler.sample(clip,range.sample(time)),List.of());
      try(var actual=MmdPlayer.packed(model,clip,provider,settings,Map.of());
          var expected=MmdPlayer.packed(model,reference,provider,settings.withAnimationRange(null),Map.of())) {
        var override=Map.of(0,new Pose(new Vec3(.2f,0,0),Rotation.axisAngle(Y,.3)));
        actual.bonePoses(override);expected.bonePoses(override);
        actual.ikOverrides(Map.of(0,false));expected.ikOverrides(Map.of(0,false));
        for(double time:new double[]{0,.125,1,1.25,.125}) {
          var a=actual.seek(time);var b=expected.seek(time);
          assertEquals(b.pose().bones(),a.pose().bones());assertEquals(b.pose().palette(),a.pose().palette());
          assertEquals(b.pose().morphs(),a.pose().morphs());assertEquals(b.pose().diagnostics(),a.pose().diagnostics());
          assertEquals(b.primitives(),a.primitives());assertEquals(b.simulationSeconds(),a.simulationSeconds());
          assertSame(a,actual.seek(time));
        }
        actual.bonePoses(Map.of());expected.bonePoses(Map.of());
        assertEquals(expected.seek(.125).primitives(),actual.seek(.125).primitives());
      }
    }
  }

  @Test void fullReplayRestoresPhysicsAnimationMorphsAndOwnedPreviewBuffers() throws Exception {
    var model=withPhysics(source(),List.of(body(0,0),body(1,1)),List.of(),List.of());var clip=clip();
    try(var player=player(model,clip);var fresh=player(model,clip)) {
      var end=player.seek(1);assertEquals(1-98f*(1f/60)*(1f/60)*60*61/2,end.pose().bones().get(1).position().y(),.001);
      var saved=end.primitives().get(0).get("POSITION").values().copy();assertSame(end,player.current());
      player.seek(.5);assertEquals(end,player.seek(1));assertEquals(end,fresh.seek(1));
      assertArrayEquals(saved,end.primitives().get(0).get("POSITION").values().copy());
      var preview=new PreviewTimeline<>(new AnimationPreview.Range(0,1,30),player::seek);preview.play();
      var half=preview.advance(2,.5);assertSame(half,preview.advance(2,.5));assertSame(half,player.current());
      var geometry=new MmdGeometry();var sourceMesh=new MmdMeshCompiler().compile(model);var coordinates=new SceneAsset.Coordinates(false,.08,"Y");
      var draw=geometry.compile(sourceMesh,half,coordinates);assertEquals(draw,geometry.compile(sourceMesh,half,coordinates));
      assertSame(half,player.current());assertNull(draw.draws().get(0).geometry().primitives().get(0).skinning());
      assertEquals(half.primitives().get(0).get("POSITION"),draw.draws().get(0).geometry().primitives().get(0).attributes().get("POSITION"));
      assertEquals(end,preview.seek(1));
    }
  }
  @Test void liveSubstepBudgetDropsBacklogPreservesFractionAndDoesNotChangePreview() throws Exception {
    var model=withPhysics(source(),List.of(body(1,1)),List.of(),List.of());
    var settings=new MmdPlayback.Settings(60,10,new Vec3(0,-98,0),false,1000).withMaxLiveSubsteps(3);
    try(var live=MmdPlayer.packed(model,clip(),new WasmPhysicsProvider(),settings,Map.of());
        var preview=player(model,clip())) {
      var frame=live.seek(.503);assertEquals(.5,frame.simulationSeconds());assertEquals(27,live.discardedLiveSteps());
      assertEquals(1-98f*(1f/60)*(1f/60)*3*4/2,frame.pose().bones().get(1).position().y(),.001);
      assertSame(frame,live.seek(.503));assertEquals(27,live.discardedLiveSteps());
      live.seek(.52);assertEquals(27,live.discardedLiveSteps());
      assertTrue(preview.seek(.503).pose().bones().get(1).position().y() < -10);
      assertEquals(3,settings.withPhysics(false).withAnimationRange(null).withPhysicsPolicy(true,false,false).maxLiveSubsteps());
    }
  }
  @Test void loopingAnimationDoesNotRewindPhysicsAndOnceHoldsItsEndpoint() throws Exception {
    var model=withPhysics(source(),List.of(body(0,0),body(1,1)),List.of(),List.of());
    var base=new MmdPlayback.Settings(60,10,new Vec3(0,-98,0),false,1000).withMaxLiveSubsteps(1000);
    try(var loop=new MmdPlayer(model,clip(),new WasmPhysicsProvider(),base.withAnimationRange(new AnimationPlaybackRange(0,1,true)),Map.of());
        var once=new MmdPlayer(model,clip(),new WasmPhysicsProvider(),base.withAnimationRange(new AnimationPlaybackRange(0,1,false)),Map.of())) {
      var half=loop.seek(.5);var next=loop.seek(1.5);
      assertEquals(1.5,next.simulationSeconds());
      assertEquals(half.pose().animation().channels(),next.pose().animation().channels());
      assertTrue(next.pose().bones().get(1).position().y()<half.pose().bones().get(1).position().y()-20);
      var end=once.seek(1);var held=once.seek(1.5);
      assertEquals(end.pose().animation().channels(),held.pose().animation().channels());
      assertNotEquals(next.pose().animation().channels(),held.pose().animation().channels());
    }
  }
  @Test void fractionalPreviewDoesNotApplyAnotherPhysicsStepOrImpulse() throws Exception {
    var model=withPhysics(source(),List.of(body(0,0),body(1,1)),List.of(),List.of());
    try(var player=player(model,clip())) {
      var half=player.seek(.5);var fraction=player.seek(.503);
      assertEquals(.5,fraction.simulationSeconds());assertEquals(half.pose().bones().get(1),fraction.pose().bones().get(1));
      assertNotEquals(half.pose().bones().get(0),fraction.pose().bones().get(0));
      assertEquals(.503,fraction.pose().morphs().meshWeights().get(0),1e-6);assertSame(fraction,player.current());
      assertEquals(fraction,player.seek(.503));
    }
  }
  @Test void mergeModePreservesAnimatedBonePositionAndWorldAnchorConstrainsFreeBody() throws Exception {
    var source=source();var motion=clip();
    try(var merged=player(withPhysics(source,List.of(body(1,2)),List.of(),List.of()),motion)) {
      var result=merged.seek(.5);var expected=new MmdEvaluator(source).evaluate(new ClipSampler().sample(motion,.5));
      vector(expected.bones().get(1).position(),result.pose().bones().get(1).position());
    }
    var joint=new Joint(names("world"),2,-1,0,new Vec3(0,1,0),Vec3.ZERO,Vec3.ZERO,Vec3.ZERO,Vec3.ZERO,Vec3.ZERO,Vec3.ZERO,Vec3.ZERO);
    try(var anchored=player(withPhysics(source,List.of(body(1,1)),List.of(joint),List.of()),motion)) {
      vector(Y,anchored.seek(1).pose().bones().get(1).position());
    }
  }
  @Test void softPinsFollowAnimationAndFreeVerticesRemainSolverOwnedAcrossReplay() throws Exception {
    var original=source();var vertices=new ArrayList<Vertex>();
    var positions=List.of(new Vec3(0,2,0),new Vec3(1,2,0),new Vec3(0,2,1),new Vec3(1,2,0),new Vec3(1,2,1),new Vec3(0,2,1));
    for(int i=0;i<original.vertices().size();i++) { var v=original.vertices().get(i);vertices.add(new Vertex(positions.get(i),Y,v.uv(),v.additionalUv(),v.deform(),v.bones(),v.weights(),v.sdefC(),v.sdefR0(),v.sdefR1(),v.edgeScale())); }
    var source=new PmxDocument(original.version(),original.globals(),original.names(),"","",vertices,original.indices(),original.textures(),original.materials(),original.bones(),original.morphs(),original.displayFrames(),List.of(),List.of(),List.of(),original.source());
    var soft=new SoftBody(names("cloth"),0,0,0,0,0,0,0,1,.02f,0,
        new FloatData(1,0,0,0,0,0,.2f,0,1,.1f,1,.7f),new FloatData(.1f,1,.5f,.5f,.5f,.5f),new IntData(0,4,0,4),new FloatData(1,1,1),List.of(),new IntData(0,1));
    var model=withPhysics(source,List.of(),List.of(),List.of(soft));var motion=clip();
    try(var player=player(model,motion)) {
      var result=player.seek(.5);var pose=new MmdEvaluator(source).evaluate(new ClipSampler().sample(motion,.5));
      var expected=new Deformer().deform(new MmdMeshCompiler().compile(source).primitives().get(0),pose.palette(),pose.morphs().meshWeights(),Deformer.NormalMode.MMD_WEIGHTED_ROTATION).get("POSITION").values();
      var actual=result.primitives().get(0).get("POSITION").values();for(int i=0;i<6;i++) assertEquals(expected.get(i),actual.get(i),2e-5);
      assertTrue(actual.get(2*3+1)<expected.get(2*3+1));
      player.seek(0);assertEquals(result,player.seek(.5));
    }
  }
  @Test void workBudgetLiveRewindAndClosedSessionRejectWithoutSilentlyDroppingSteps() throws Exception {
    var settings=new MmdPlayback.Settings(60,10,new Vec3(0,-98,0),false,10);
    var player=new MmdPlayer(source(),clip(),new WasmPhysicsProvider(),settings,Map.of());
    assertThrows(IllegalArgumentException.class,()->player.seek(1));assertEquals(0,player.current().seconds());
    player.seek(.1);assertThrows(IllegalStateException.class,()->player.seek(0));player.close();player.close();
    assertThrows(IllegalStateException.class,player::current);
  }

  @Test void invalidUnitPhysicsValuesAreClampedWithDiagnosticsAndPlaybackContinues() throws Exception {
    var model=withPhysics(source(),List.of(invalidUnitBody(0)),List.of(),List.of());
    try(var player=player(model,clip())) {
      var diagnostics=player.current().pose().diagnostics();
      assertEquals(3,diagnostics.size());
      assertTrue(diagnostics.stream().allMatch(d->d.severity()==CompatibilityReport.Severity.WARNING));
      assertTrue(diagnostics.stream().anyMatch(d->d.location().contains("linearDamping")&&d.message().contains("clamped to 1.0")));
      assertTrue(diagnostics.stream().anyMatch(d->d.location().contains("angularDamping")&&d.message().contains("clamped to 0.0")));
      assertTrue(diagnostics.stream().anyMatch(d->d.location().contains("restitution")&&d.message().contains("clamped to 0.0")));
      assertDoesNotThrow(()->player.seek(.25));
    }
  }
  @Test void pmmBonePhysicsSwitchDrivesAnimationThenResumesDynamicBodyAndReplays() throws Exception {
    var source=source();var model=withPhysics(source,List.of(body(1,1)),List.of(),List.of());
    var track=new AnimationClip.Track(AnimationClip.Property.BONE_PHYSICS_ENABLED,1,"","",new AnimationCurve(new double[]{0,.5},new FloatData(0,1),1,false,AnimationCurve.Interpolation.STEP,FloatData.EMPTY,FloatData.EMPTY,FloatData.EMPTY));
    var tracks=new ArrayList<>(clip().tracks());tracks.add(track);var clip=new AnimationClip("switch",tracks,30);
    try(var player=player(model,clip)) {
      var driven=player.seek(.4);var expected=new MmdEvaluator(source).evaluate(new ClipSampler().sample(clip,.4));vector(expected.bones().get(1).position(),driven.pose().bones().get(1).position());
      var released=player.seek(1);assertTrue(released.pose().bones().get(1).position().y()< -10);
      player.seek(0);assertEquals(released,player.seek(1));
    }
  }
}
