package cc.sirrus.ysmlib.scene.mmd;

import cc.sirrus.ysmlib.scene.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static cc.sirrus.ysmlib.scene.mmd.MmdRigTest.*;
import static cc.sirrus.ysmlib.scene.mmd.PmxDocument.*;
import static org.junit.jupiter.api.Assertions.*;

class MmdProceduralControlsTest {
  static PmxDocument source() {
    return model(List.of(bone("頭",Vec3.ZERO,-1,0),bone("LeftEye",X,0,1),bone("右目",X.multiply(-1),0,1)),
        List.of(new Morph(names("まばたき"),2,1,List.of())),List.of());
  }
  @Test void headUsesXyzOrderEyesClampAndTrackingOwnerSuppressesOrdinaryRotations() {
    var c=new MmdProceduralControls(source(),1);c.headAngles(new Vec3(.2f,.3f,.4f));c.eyeAngles(5,-5);c.eyeTracking(true);
    var result=c.advance(0);var expected=Rotation.axisAngle(X,.2).multiply(Rotation.axisAngle(Y,.3)).multiply(Rotation.axisAngle(Z,.4));
    vector(expected.rotate(X),result.bones().get(0).rotate(X));
    var eyes=Rotation.axisAngle(X,.35).multiply(Rotation.axisAngle(Y,-.35));vector(eyes.rotate(Z),result.bones().get(1).rotate(Z));
    assertEquals(result.bones().get(1),result.bones().get(2));c.eyeMaxAngle(.01f);vector(Rotation.axisAngle(X,.1).multiply(Rotation.axisAngle(Y,-.1)).rotate(Z),c.advance(0).bones().get(1).rotate(Z));
    c.trackingOwnsBones(true);assertTrue(c.advance(0).bones().isEmpty());c.trackingOwnsBones(false);assertEquals(3,c.advance(0).bones().size());
  }
  @Test void blinkFollowsSineCurveThenReleasesAnimationAndSnapshotRestoresRandomSchedule() {
    var c=new MmdProceduralControls(source(),42);c.blinkParameters(4,.2);c.autoBlink(true);
    assertTrue(c.advance(4).morphs().isEmpty());assertEquals(.70710677f,c.advance(.05).morphs().get(0),1e-6);
    assertEquals(1,c.advance(.05).morphs().get(0),1e-6);var saved=c.snapshot();
    var expected=new ArrayList<MmdProceduralControls.Overrides>();
    for(double delta:new double[]{.05,.05,.001,2.8,3,.1,.1})expected.add(c.advance(delta));
    assertEquals(0,expected.get(1).morphs().get(0));assertTrue(expected.get(2).morphs().isEmpty());
    c.restore(saved);var actual=new ArrayList<MmdProceduralControls.Overrides>();for(double delta:new double[]{.05,.05,.001,2.8,3,.1,.1})actual.add(c.advance(delta));assertEquals(expected,actual);
    c.autoBlink(false);assertTrue(c.current().morphs().isEmpty());assertTrue(c.advance(10).morphs().isEmpty());
    assertThrows(IllegalArgumentException.class,()->new MmdProceduralControls(source(),42).restore(saved));
  }
  @Test void invalidInputDoesNotAdvanceAndMissingBindingsAreSafe() {
    var c=new MmdProceduralControls(source(),42);c.autoBlink(true);var before=c.current();
    assertThrows(IllegalArgumentException.class,()->c.advance(Double.NaN));assertThrows(IllegalArgumentException.class,()->c.advance(-1));
    assertThrows(IllegalArgumentException.class,()->c.eyeAngles(1,Float.NaN));assertThrows(IllegalArgumentException.class,()->c.blinkParameters(1,Double.NaN));assertSame(before,c.current());
    assertThrows(IllegalArgumentException.class,()->c.blinkParameters(Double.MAX_VALUE,.1));
    var absent=new MmdProceduralControls(model(List.of(bone("root",Vec3.ZERO,-1,0)),List.of(),List.of()),1);absent.autoBlink(true);absent.eyeTracking(true);
    assertTrue(absent.advance(1).bones().isEmpty());assertTrue(absent.current().morphs().isEmpty());
  }
}
