package cc.sirrus.ysmlib.scene.physics.natives;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.mmd.*;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import cc.sirrus.ysmlib.scene.physics.wasm.WasmPhysicsProvider;
import cc.sirrus.ysmlib.scene.physics.*;
import java.lang.reflect.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static cc.sirrus.ysmlib.scene.mmd.PmxDocument.*;

/** Native is supplied explicitly to isolated players; runtime selection is not changed. */
class NativeMmdConformanceTest {
  ByteData resource(String name) throws Exception {
    try(var in=getClass().getResourceAsStream("/mmd-oracle/"+name)){return new ByteData(Objects.requireNonNull(in).readAllBytes());}
  }
  AnimationClip animation(String name) throws Exception {return new VmdAnimation().compile(new VmdReader().read(resource(name),ReadLimits.DEFAULT)).clip();}
  PmxDocument model(String name) throws Exception {return new PmxReader().read(resource(name),ReadLimits.DEFAULT);}
  static Names names(String name) {return new Names(name,name);}
  static PmxDocument replace(PmxDocument d,List<Vertex> vertices,List<Morph> morphs,List<RigidBody> bodies,List<Joint> joints,List<SoftBody> soft) {
    return new PmxDocument(d.version(),d.globals(),d.names(),d.comment(),d.englishComment(),vertices,d.indices(),d.textures(),d.materials(),d.bones(),morphs,d.displayFrames(),bodies,joints,soft,d.source());
  }
  static void compare(MmdPlayback.Frame expected,MmdPlayback.Frame actual) {
    assertEquals(expected.seconds(),actual.seconds());assertEquals(expected.simulationSeconds(),actual.simulationSeconds());
    assertEquals(expected.pose().morphs(),actual.pose().morphs());
    for(int i=0;i<expected.pose().bones().size();i++) {
      var e=expected.pose().bones().get(i).matrix();var a=actual.pose().bones().get(i).matrix();
      assertArrayEquals(e.copy(),a.copy(),.003f,"bone="+i+" t="+actual.seconds());
    }
    for(int i=0;i<expected.primitives().size();i++)for(String attribute:List.of("POSITION","NORMAL"))
      assertArrayEquals(expected.primitives().get(i).get(attribute).values().copy(),actual.primitives().get(i).get(attribute).values().copy(),.005f,"mesh "+attribute+" t="+actual.seconds());
  }
  static void exercise(PmxDocument source,AnimationClip animation) {
    try(var n=new MmdPlayer(source,animation,NativePhysicsConformanceTest.nativeProvider(),MmdPlayback.Settings.preview(),Map.of());
        var w=new MmdPlayer(source,animation,new WasmPhysicsProvider(),MmdPlayback.Settings.preview(),Map.of());
        var np=MmdPlayer.packed(source,animation,NativePhysicsConformanceTest.nativeProvider(),MmdPlayback.Settings.preview(),Map.of());
        var wp=MmdPlayer.packed(source,animation,new WasmPhysicsProvider(),MmdPlayback.Settings.preview(),Map.of())) {
      for(int i=0;i<=60;i++){compare(w.seek(i/60d),n.seek(i/60d));assertEquals(n.current(),np.seek(i/60d));assertEquals(w.current(),wp.seek(i/60d));}
      var end=n.current();for(double time:new double[]{.5,.503,0,.25,1}){compare(w.seek(time),n.seek(time));assertEquals(n.current(),np.seek(time));assertEquals(w.current(),wp.seek(time));}assertEquals(end,n.current());
    }
  }
  @ParameterizedTest @ValueSource(ints={0,1,2})
  void allPmxRigidModesAnimationPhysicsSwitchImpulseMorphAndBackwardSeek(int mode) throws Exception {
    var d=model("skin.pmx");
    var body=new RigidBody(names("rigid"),1,0,0,0,new Vec3(.2f,0,0),new Vec3(0,1,0),new Vec3(0,0,.5f),1,.05f,.1f,0,.5f,mode);
    var morphs=new ArrayList<>(d.morphs());int impulse=morphs.size();
    morphs.add(new Morph(names("impulse"),4,10,List.of(new ImpulseOffset(0,true,new Vec3(.1f,0,0),new Vec3(0,.05f,0)))));
    morphs.add(new Morph(names("reset"),4,10,List.of(new ImpulseOffset(0,false,Vec3.ZERO,Vec3.ZERO))));
    var a=animation("skin.vmd");var tracks=new ArrayList<>(a.tracks());
    tracks.add(new AnimationClip.Track(AnimationClip.Property.BONE_PHYSICS_ENABLED,1,"","",new AnimationCurve(new double[]{0,.25,.5},new FloatData(1,0,1),1,false,AnimationCurve.Interpolation.STEP,FloatData.EMPTY,FloatData.EMPTY,FloatData.EMPTY)));
    for(int i=0;i<2;i++)tracks.add(new AnimationClip.Track(AnimationClip.Property.MORPH_WEIGHTS,impulse+i,"","",new AnimationCurve(new double[]{0,.2,.3,.7,.8},new FloatData(0,1,0,1,0),1,false,AnimationCurve.Interpolation.STEP,FloatData.EMPTY,FloatData.EMPTY,FloatData.EMPTY)));
    exercise(replace(d,d.vertices(),morphs,List.of(body),List.of(),List.of()),new AnimationClip("physics",tracks,30));
  }
  @Test void pmxSoftPinsAnchorsAndDeformedMeshMatchAcrossBackwardSeek() throws Exception {
    var d=model("skin.pmx");var vertices=new ArrayList<Vertex>();
    var positions=List.of(new Vec3(0,2,0),new Vec3(1,2,0),new Vec3(0,2,1),new Vec3(1,2,0),new Vec3(1,2,1),new Vec3(0,2,1));
    for(int i=0;i<d.vertices().size();i++){var v=d.vertices().get(i);vertices.add(new Vertex(positions.get(i),new Vec3(0,1,0),v.uv(),v.additionalUv(),v.deform(),v.bones(),v.weights(),v.sdefC(),v.sdefR0(),v.sdefR1(),v.edgeScale()));}
    var rigid=new RigidBody(names("anchor"),0,0,0,1,new Vec3(.2f,.2f,.2f),positions.get(2),Vec3.ZERO,1,0,0,0,.5f,0);
    var soft=new SoftBody(names("cloth"),0,0,0,0,0,0,0,1,.02f,0,new FloatData(1,0,0,0,0,0,.2f,0,1,.1f,1,.7f),new FloatData(.1f,1,.5f,.5f,.5f,.5f),new IntData(0,4,0,4),new FloatData(1,1,1),List.of(new Anchor(0,2,false)),new IntData(0,1));
    exercise(replace(d,vertices,d.morphs(),List.of(rigid),List.of(),List.of(soft)),animation("skin.vmd"));
  }
  @Test void boneGroupMaterialAndVertexMorphFixtureKeepsJavaEvaluationParity() throws Exception {exercise(model("morph.pmx"),animation("morph.vmd"));}
  @Test void expressionAndVpdOverlaysRemainEquivalentAcrossNativeReplay() throws Exception {
    var source=model("morph.pmx");var clip=animation("morph.vmd");
    try(var n=new MmdPlayer(source,clip,NativePhysicsConformanceTest.nativeProvider(),MmdPlayback.Settings.preview(),Map.of());
        var w=new MmdPlayer(source,clip,new WasmPhysicsProvider(),MmdPlayback.Settings.preview(),Map.of())) {
      var name=source.bones().get(0).names().local();
      var vpd=new VpdDocument("",List.of(new VpdDocument.BonePose(0,name,new Vec3(.1f,.2f,0),new FloatData(0,0,0,1))),List.of(),ByteData.EMPTY);
      for(var player:List.of(n,w)){player.vpdPose(vpd);player.morphWeights(Map.of(0,.5f));player.boneRotations(Map.of(0,Rotation.axisAngle(new Vec3(0,1,0),.2)));}
      for(double time:new double[]{0,.2,.5,.503,1,.5,0,1})compare(w.seek(time),n.seek(time));
      for(var player:List.of(n,w)){player.vpdPose(null);player.morphWeights(Map.of());player.boneRotations(Map.of());}compare(w.seek(1),n.seek(1));
    }
  }
  @Test void fourLayerTransitionsMasksExpressionsAndPhysicsReplayMatchWasm() throws Exception {
    var d=model("morph.pmx");
    var rigid=new RigidBody(names("layer-physics"),0,0,0,0,new Vec3(.2f,0,0),d.bones().get(0).position(),Vec3.ZERO,1,.05f,.1f,0,.5f,1);
    var source=replace(d,d.vertices(),d.morphs(),List.of(rigid),List.of(),List.of());var clip=animation("morph.vmd");
    var layers=new MmdAnimationLayers(source);layers.clip(0,clip);layers.play(0);
    layers.clip(1,clip);layers.settings(1,new MmdAnimationLayers.Settings(.5f,2,true,.2,.2));layers.play(1);
    layers.clip(2,clip);layers.settings(2,new MmdAnimationLayers.Settings(.25f,.5f,false,0,0));layers.boneMask(2,Set.of(0),Set.of());layers.play(2);
    layers.settings(3,new MmdAnimationLayers.Settings(.1f,1,true,0,0));layers.transition(3,clip,.75);
    try(var n=new MmdPlayer(source,layers.freeze(),NativePhysicsConformanceTest.nativeProvider(),MmdPlayback.Settings.preview(),Map.of());
        var w=new MmdPlayer(source,layers.freeze(),new WasmPhysicsProvider(),MmdPlayback.Settings.preview(),Map.of())) {
      for(var player:List.of(n,w))player.morphWeights(Map.of(0,.3f));
      for(double time:new double[]{0,.1,.2,.5,.503,.75,1,.5,0,1})compare(w.seek(time),n.seek(time));
    }
  }
  @Test void pmxJointPolicyAndRsFilterAreAppliedBeforeWorldConstructionAndSurviveReplay() throws Exception {
    var d=model("skin.pmx");
    var bodies=List.of(new RigidBody(names("follow"),0,0,65535,0,new Vec3(.5f,0,0),Vec3.ZERO,Vec3.ZERO,1,0,0,0,0,0),
        new RigidBody(names("dynamic"),1,0,65535,0,new Vec3(.5f,0,0),new Vec3(0,1,0),Vec3.ZERO,1,0,0,0,0,1));
    var joint=new Joint(names("fixed"),0,0,1,new Vec3(0,.5f,0),Vec3.ZERO,Vec3.ZERO,Vec3.ZERO,Vec3.ZERO,Vec3.ZERO,Vec3.ZERO,Vec3.ZERO);
    var source=replace(d,d.vertices(),d.morphs(),bodies,List.of(joint),List.of());
    var clip=new AnimationClip("rest",List.of(),30);var ends=new ArrayList<Float>();
    for(boolean joints:new boolean[]{false,true}) {
      var settings=MmdPlayback.Settings.preview().withPhysicsPolicy(joints,true,true);
      assertTrue(settings.withPhysics(false).withAnimationRange(null).kinematicFilter());
      assertEquals(joints,settings.withPhysics(false).jointsEnabled());
      try(var n=new MmdPlayer(source,clip,NativePhysicsConformanceTest.nativeProvider(),settings,Map.of());
          var w=new MmdPlayer(source,clip,new WasmPhysicsProvider(),settings,Map.of())) {
        for(double time:new double[]{0,.25,.5,.503,.25,0,.5})compare(w.seek(time),n.seek(time));
        ends.add(n.current().pose().bones().get(1).position().y());
      }
    }
    assertTrue(ends.get(1)-ends.get(0)>5,"Disabled joints must allow free fall; enabled joints hold the body");
  }
  @Test void packedPlayerReusesWorldAndPublishesOnePoseBatchPerStepWithoutScalarTransfers() throws Exception {
    var d=model("skin.pmx");var bodies=new ArrayList<RigidBody>();
    for(int i=0;i<32;i++)bodies.add(new RigidBody(names("follow"+i),0,0,0,0,new Vec3(.2f,0,0),Vec3.ZERO,Vec3.ZERO,1,0,0,0,0,0));
    bodies.add(new RigidBody(names("dynamic"),1,0,0,0,new Vec3(.2f,0,0),new Vec3(0,1,0),Vec3.ZERO,1,0,0,0,0,1));
    var source=replace(d,d.vertices(),d.morphs(),bodies,List.of(),List.of());var counts=new HashMap<String,Integer>();
    var nativeProvider=NativePhysicsConformanceTest.nativeProvider();
    var provider=new PhysicsProvider() {
      public String id(){return nativeProvider.id();}
      public PhysicsWorld createWorld(PhysicsSpec.World settings) {
        counts.merge("worlds",1,Integer::sum);var world=nativeProvider.createWorld(settings);
        return (PhysicsWorld)Proxy.newProxyInstance(PhysicsWorld.class.getClassLoader(),new Class<?>[]{PhysicsWorld.class},(proxy,method,args)->{
          if(Set.of("setBodyPose","bodyStates","softBodyVertices","softBodyNormals","applyImpulse","setSoftBodyPin").contains(method.getName()))
            throw new AssertionError("Packed player attempted scalar transfer: "+method.getName());
          counts.merge(method.getName(),1,Integer::sum);
          try{return method.invoke(world,args);}catch(InvocationTargetException failure){throw failure.getCause();}
        });
      }
    };
    try(var player=MmdPlayer.packed(source,animation("skin.vmd"),provider,MmdPlayback.Settings.preview(),Map.of())) {
      counts.remove("readBodyStates");player.seek(1);
      assertEquals(1,counts.get("worlds"));assertEquals(60,counts.get("setBodyPoses"));assertEquals(60,counts.get("step"));assertEquals(60,counts.get("readBodyStates"));
      var cached=player.current();assertSame(cached,player.seek(1));assertEquals(60,counts.get("step"));
      player.seek(.5);assertEquals(2,counts.get("worlds"));assertEquals(1,counts.get("close"));
    }
    assertEquals(2,counts.get("close"));
  }
}
