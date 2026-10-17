package cc.sirrus.ysmlib.scene.java;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.fbx.*;
import cc.sirrus.ysmlib.scene.physics.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FbxPhysicsProfileCodecTest {
  @Test void profileRoundTripsStrictSidecarShape() {
    var body=new FbxPhysicsProfile.Body("hair",3,new PhysicsSpec.Body(PhysicsSpec.Shape.CAPSULE_Y,new Vec3(.05f,.3f,0),new Pose(new Vec3(0,.1f,0),Rotation.IDENTITY),PhysicsSpec.Motion.DYNAMIC,1,.1f,.2f,.3f,.4f,.01f,2,4));
    var joint=new PhysicsSpec.Joint(PhysicsSpec.JointType.SPRING_6DOF,0,-1,Pose.IDENTITY,Pose.IDENTITY,new Vec3(-1,-1,-1),new Vec3(1,1,1),new Vec3(-.5f,-.5f,-.5f),new Vec3(.5f,.5f,.5f),new Vec3(1,2,3),new Vec3(4,5,6),.7f,true);
    var profile=new FbxPhysicsProfile(List.of(body),List.of(new FbxPhysicsProfile.Joint(0,-1,joint)));var copy=new FbxPhysicsProfileCodec().read(new FbxPhysicsProfileCodec().write(profile));
    assertEquals(profile,copy);
    assertThrows(IllegalArgumentException.class,()->new FbxPhysicsProfileCodec().read(new ByteData("{\"bodies\":[],\"joints\":[],\"extra\":1}".getBytes())));
  }
}
