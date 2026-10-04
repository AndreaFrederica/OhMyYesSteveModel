package cc.sirrus.ysmlib.scene.mmd;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.physics.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static cc.sirrus.ysmlib.scene.mmd.MmdRigTest.*;
import static org.junit.jupiter.api.Assertions.*;

class MmdEditorPlaybackTest {
  @Test void disabledPhysicsNeverCreatesAWorldAndCanSeekWithoutReplay() {
    var source=model(List.of(bone("左足",Y,-1,0)),List.of(),List.of());
    var forbidden=new PhysicsProvider() {
      public String id(){throw new AssertionError("Provider should not be queried");}
      public PhysicsWorld createWorld(PhysicsSpec.World s){throw new AssertionError("Editor created physics");}
    };
    var settings=new MmdPlayback.Settings(60,10,new Vec3(0,-98,0),true,1).withPhysics(false);
    try(var player=new MmdPlayer(source,MmdBasicAnimationFactory.forState(source,"walk"),forbidden,settings,Map.of())) {
      player.seek(100);var a=player.seek(.25);assertTrue(Math.abs(a.pose().bones().get(0).rotation().x())>.1);
      assertEquals(0,a.simulationSeconds());
      player.boneRotations(Map.of(0,Rotation.axisAngle(Y,.5)));var b=player.seek(.25);
      assertNotEquals(a.pose().bones(),b.pose().bones());
      player.boneRotations(Map.of());assertEquals(a.pose(),player.seek(.25).pose());
    }
  }
}
