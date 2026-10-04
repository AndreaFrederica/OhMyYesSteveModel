package cc.sirrus.ysmlib.scene.mmd;

import cc.sirrus.ysmlib.scene.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static cc.sirrus.ysmlib.scene.mmd.MmdRigTest.*;
import static cc.sirrus.ysmlib.scene.mmd.PmxDocument.*;
import static org.junit.jupiter.api.Assertions.*;

class MmdAnimationLayersTest {
  @Test void singleClipCachesBindingsAndConstantChannelsWithoutExpandingRestOrReorderingOverrides() {
    var constant=MmdOverridesTest.track(AnimationClip.Property.TRANSLATION,0,3,0,0);
    var named=new AnimationClip.Track(constant.property(),-1,"en-root","",constant.curve());
    var clip=new AnimationClip("pose",List.of(named,constant),30);
    var program=MmdAnimationLayers.singleClip(source(),clip,null);
    var first=program.sample(0);assertSame(first,program.sample(0));
    var later=program.sample(10);assertSame(first.animation().channels(),later.animation().channels());
    assertEquals(2,later.animation().channels().size());assertEquals(0,later.animation().channels().get(0).targetIndex());
    assertEquals("en-root",later.animation().channels().get(0).binding());assertEquals(-1,clip.tracks().get(0).targetIndex());
    assertEquals(first,program.sample(0));assertEquals(10,later.animation().seconds());
    assertTrue(MmdAnimationLayers.singleClip(source(),new AnimationClip("rest",List.of(),30),null).sample(2).animation().channels().isEmpty());
    assertThrows(IllegalArgumentException.class,()->program.sample(Double.NaN));
  }
  @Test void singleClipPreservesDuplicateMissingNameDiagnosticsAndRawCurveRangeSemantics() {
    var model=model(List.of(bone("root",Vec3.ZERO,-1,0),bone("root",Y,-1,0)),List.of(),List.of());
    var curve=new AnimationCurve(new double[]{0,1},new FloatData(0,0,0,4,0,0),3,false,AnimationCurve.Interpolation.LINEAR,FloatData.EMPTY,FloatData.EMPTY,FloatData.EMPTY);
    var clip=new AnimationClip("named",List.of(new AnimationClip.Track(AnimationClip.Property.TRANSLATION,-1,"root","",curve),new AnimationClip.Track(AnimationClip.Property.TRANSLATION,-1,"missing","",curve)),30);
    var range=new AnimationPlaybackRange(.25,.75,true);var program=MmdAnimationLayers.singleClip(model,clip,range);
    var sampler=new cc.sirrus.ysmlib.scene.java.ClipSampler();
    for(double time:new double[]{0,.5,1.25,.125,0}) {
      var expected=sampler.sample(clip,range.sample(time));var actual=program.sample(time).animation();
      assertEquals(expected,actual);assertEquals(new MmdEvaluator(model).evaluate(expected),new MmdEvaluator(model).evaluate(actual));
    }
    assertEquals(2,new MmdEvaluator(model).evaluate(program.sample(0).animation()).diagnostics().size());
  }
  @Test void frozenProgramIsIndependentOfQueryOrderAndLaterControllerEdits() {
    var layers=new MmdAnimationLayers(source());layers.clip(0,clip(10,.8f));layers.play(0);layers.transition(0,clip(20,.2f),2);
    var frozen=layers.freeze();var expected=frozen.sample(.25);layers.reset(0);layers.clip(1,clip(100,1));layers.play(1);
    frozen.sample(1);frozen.sample(0);assertEquals(expected,frozen.sample(.25));
    assertEquals(.25,expected.animation().seconds());assertThrows(IllegalArgumentException.class,()->frozen.sample(-1));
  }
  @Test void frozenScratchIsSafeAcrossConcurrentAndRetainedQueries() throws Exception {
    var layers=new MmdAnimationLayers(source());layers.clip(0,clip(10,.8f));layers.play(0);layers.transition(0,clip(20,.2f),2);
    var frozen=layers.freeze();var times=List.of(.25,.9,.1,.8,.25,0d,1d);var expected=times.stream().map(frozen::sample).toList();
    var pool=java.util.concurrent.Executors.newFixedThreadPool(4);
    try {var futures=new ArrayList<java.util.concurrent.Future<MmdAnimationSource.Sample>>();
      for(int i=0;i<100;i++){double time=times.get(i%times.size());futures.add(pool.submit(()->frozen.sample(time)));}
      for(int i=0;i<futures.size();i++)assertEquals(expected.get(i%times.size()),futures.get(i).get());
      assertEquals(expected.get(0),frozen.sample(.25));
    }finally{pool.shutdownNow();}
  }
  static AnimationClip.Track constant(AnimationClip.Property property,int index,float... value) {
    var packed=new float[value.length*2];System.arraycopy(value,0,packed,0,value.length);System.arraycopy(value,0,packed,value.length,value.length);
    return new AnimationClip.Track(property,index,"","",new AnimationCurve(new double[]{0,1},new FloatData(packed),value.length,property==AnimationClip.Property.ROTATION,AnimationCurve.Interpolation.LINEAR,FloatData.EMPTY,FloatData.EMPTY,FloatData.EMPTY));
  }
  static AnimationClip clip(float x,float morph) {return new AnimationClip("layer",List.of(constant(AnimationClip.Property.TRANSLATION,0,x,0,0),constant(AnimationClip.Property.MORPH_WEIGHTS,0,morph)),30);}
  static PmxDocument source() {return model(List.of(bone("root",Vec3.ZERO,-1,0),bone("other",Y,-1,0)),List.of(new Morph(names("expression"),4,1,List.of())),List.of());}
  static float value(MmdAnimationLayers layers,AnimationClip.Property property,int index,int component) {
    return layers.current().animation().channels().stream().filter(c->c.property()==property && c.targetIndex()==index).findFirst().orElseThrow().value().get(component);
  }
  @Test void sequentialBoneMorphAndQuaternionBlendingIsNotWeightNormalized() {
    var layers=new MmdAnimationLayers(source());var q1=Rotation.axisAngle(Y,.5);var q2=Rotation.axisAngle(Z,.8);
    layers.clip(0,new AnimationClip("first",List.of(constant(AnimationClip.Property.TRANSLATION,0,10,0,0),constant(AnimationClip.Property.ROTATION,0,q1.x(),q1.y(),q1.z(),q1.w()),constant(AnimationClip.Property.MORPH_WEIGHTS,0,1)),30));
    layers.clip(1,new AnimationClip("second",List.of(constant(AnimationClip.Property.TRANSLATION,0,20,0,0),constant(AnimationClip.Property.ROTATION,0,q2.x(),q2.y(),q2.z(),q2.w()),constant(AnimationClip.Property.MORPH_WEIGHTS,0,.2f)),30));
    for(int i=0;i<2;i++){layers.settings(i,new MmdAnimationLayers.Settings(.5f,1,true,0,0));layers.play(i);}
    assertEquals(12.5f,value(layers,AnimationClip.Property.TRANSLATION,0,0));assertEquals(.35f,value(layers,AnimationClip.Property.MORPH_WEIGHTS,0,0),1e-6);
    var pose=new MmdEvaluator(source()).evaluate(layers.current().animation());var expected=Rotation.slerp(Rotation.slerp(Rotation.IDENTITY,q1,.5),q2,.5);vector(expected.rotate(X),pose.bones().get(0).rotation().rotate(X));
    assertSame(layers.current(),layers.current(),"Queries reuse the last complete animation result");
  }
  @Test void boneMasksProtectLowerLayersWhileMorphAndFullWeightIkKeepTheirPriority() {
    var layers=new MmdAnimationLayers(source());layers.clip(0,clip(1,.2f));layers.play(0);
    layers.clip(1,new AnimationClip("masked",List.of(constant(AnimationClip.Property.TRANSLATION,0,5,0,0),constant(AnimationClip.Property.TRANSLATION,1,7,0,0),constant(AnimationClip.Property.MORPH_WEIGHTS,0,.8f),constant(AnimationClip.Property.IK_ENABLED,0,0)),30));
    layers.boneMask(1,Set.of(0,1),Set.of(0));layers.play(1);
    assertEquals(1,value(layers,AnimationClip.Property.TRANSLATION,0,0));assertEquals(7,value(layers,AnimationClip.Property.TRANSLATION,1,0));assertEquals(.8f,value(layers,AnimationClip.Property.MORPH_WEIGHTS,0,0));assertEquals(0,value(layers,AnimationClip.Property.IK_ENABLED,0,0));
    layers.settings(1,new MmdAnimationLayers.Settings(.5f,1,true,0,0));
    assertTrue(layers.current().animation().channels().stream().noneMatch(c->c.property()==AnimationClip.Property.IK_ENABLED));
    layers.enabled(1,false);assertEquals(.2f,value(layers,AnimationClip.Property.MORPH_WEIGHTS,0,0));
  }
  @Test void inclusiveLoopBoundaryNonLoopEndAndZeroDurationPoseStayDistinct() {
    var layers=new MmdAnimationLayers(source());layers.clip(0,clip(2,.4f));layers.play(0);layers.advance(1);assertEquals(1,layers.sourceSeconds(0));layers.advance(.25);assertEquals(.25,layers.sourceSeconds(0));
    layers.clip(0,clip(2,.4f));layers.settings(0,new MmdAnimationLayers.Settings(1,1,false,0,0));layers.play(0);layers.advance(2);
    assertTrue(layers.finished(0));assertEquals(MmdAnimationLayers.State.STOPPED,layers.state(0));assertEquals(2,value(layers,AnimationClip.Property.TRANSLATION,0,0));
    layers.stop(0);assertFalse(layers.finished(0));assertEquals(0,value(layers,AnimationClip.Property.TRANSLATION,0,0));
    layers.clip(0,new AnimationClip("VPD",List.of(MmdOverridesTest.track(AnimationClip.Property.TRANSLATION,0,3,0,0)),0));layers.play(0);layers.advance(100);
    assertFalse(layers.finished(0));assertEquals(0,layers.sourceSeconds(0));assertEquals(3,value(layers,AnimationClip.Property.TRANSLATION,0,0));
  }
  @Test void fadesFollowSpeedAndPauseResumePreservesFadeProgress() {
    var layers=new MmdAnimationLayers(source());layers.clip(0,clip(2,.4f));layers.settings(0,new MmdAnimationLayers.Settings(1,2,true,1,1));layers.play(0);layers.advance(.25);
    assertEquals(.5f,layers.effectiveWeight(0));double position=layers.sourceSeconds(0);layers.pause(0);layers.advance(1);assertEquals(.5f,layers.effectiveWeight(0));assertEquals(position,layers.sourceSeconds(0));
    layers.resume(0);layers.advance(.25);assertEquals(1,layers.effectiveWeight(0));assertEquals(MmdAnimationLayers.State.PLAYING,layers.state(0));
    layers.stop(0);layers.advance(.25);assertEquals(.5f,layers.effectiveWeight(0));layers.advance(.25);assertEquals(0,layers.effectiveWeight(0));assertEquals(0,layers.sourceSeconds(0));
  }
  @Test void transitionUsesSmoothstepSnapshotAndUnscaledTimeAndSnapshotsRestoreAllState() {
    var layers=new MmdAnimationLayers(source());layers.clip(0,clip(10,.8f));layers.settings(0,new MmdAnimationLayers.Settings(1,4,true,0,0));layers.play(0);layers.advance(.1);
    layers.transition(0,clip(20,.2f),2);assertEquals(10,value(layers,AnimationClip.Property.TRANSLATION,0,0));layers.advance(.25);
    double t=.125*.125*(3-2*.125);assertEquals(10+10*t,value(layers,AnimationClip.Property.TRANSLATION,0,0),1e-5);assertEquals(.8+(.2-.8)*t,value(layers,AnimationClip.Property.MORPH_WEIGHTS,0,0),1e-6);
    var snapshot=layers.snapshot();var before=layers.current();layers.advance(.25);var after=layers.current();layers.pause(0);layers.boneMask(0,Set.of(),Set.of());layers.clip(2,clip(100,1));layers.play(2);
    layers.restore(snapshot);assertEquals(before,layers.current());layers.advance(.25);assertEquals(after,layers.current());
    assertThrows(IllegalArgumentException.class,()->new MmdAnimationLayers(source()).restore(snapshot));
  }
  @Test void aliasBindingDiagnosticsAndLateClockOverflowAreExplicit() {
    var layers=new MmdAnimationLayers(source());var named=constant(AnimationClip.Property.TRANSLATION,0,4,0,0);
    layers.clip(0,new AnimationClip("named",List.of(new AnimationClip.Track(named.property(),-1,"en-root","",named.curve()),new AnimationClip.Track(named.property(),-1,"missing","",named.curve())),30));layers.play(0);
    assertEquals(4,value(layers,AnimationClip.Property.TRANSLATION,0,0));assertTrue(layers.current().diagnostics().stream().anyMatch(d->d.location().equals("missing")));
    layers.clip(3,clip(2,.2f));layers.settings(3,new MmdAnimationLayers.Settings(1,Float.MAX_VALUE,true,0,0));layers.play(3);var before=layers.current();
    assertThrows(IllegalArgumentException.class,()->layers.advance(Double.MAX_VALUE));assertSame(before,layers.current());assertEquals(0,layers.sourceSeconds(0));
  }
}
