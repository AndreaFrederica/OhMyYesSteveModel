package cc.sirrus.ysmlib.scene.physics.natives;

import cc.sirrus.ysmlib.scene.Pose;
import cc.sirrus.ysmlib.scene.Rotation;
import cc.sirrus.ysmlib.scene.Vec3;
import cc.sirrus.ysmlib.scene.physics.*;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativePhysicsProviderTest {
  private static NativePhysicsProvider provider() {
    var value=System.getProperty("ysm.native.physics.library");
    Assumptions.assumeTrue(value!=null,"Set ysm.native.physics.library to run native integration tests");
    return new NativePhysicsProvider(Path.of(value));
  }
  private static Pose at(float x,float y,float z) { return new Pose(new Vec3(x,y,z),Rotation.IDENTITY); }
  private static PhysicsSpec.Body dynamic(float y) { return new PhysicsSpec.Body(PhysicsSpec.Shape.SPHERE,new Vec3(.25f,0,0),at(0,y,0),PhysicsSpec.Motion.DYNAMIC,1,0,0,0,.5f,.04f,1,0xffff); }

  @Test void capabilitiesAndSoftBodyPinAndNormalReadback() {
    var p=provider();assertEquals(5,p.capabilities().abi());assertEquals(0xffL,p.capabilities().featureBits());
    try(var world=p.createWorld(PhysicsSpec.World.defaults().withoutReplay())) {
      int soft=world.addSoftBody(new PhysicsSpec.SoftBody(List.of(new Vec3(-1,2,0),new Vec3(1,2,0),new Vec3(-1,2,1),new Vec3(1,2,1)),List.of(0,2,1,1,2,3),false,List.of(0,1),1,.02f,1,0xffff,PhysicsSpec.SoftConfig.defaults()));
      for(int i=0;i<60;i++)world.step();var vertices=world.softBodyVertices(soft);assertEquals(new Vec3(-1,2,0),vertices.get(0));assertEquals(new Vec3(1,2,0),vertices.get(1));assertTrue(vertices.get(2).y()<1.9f);assertEquals(4,world.softBodyNormals(soft).size());
    }
  }

  @Test void everyJointTypeUsesUnifiedAbiAndWorldAnchors() {
    var p=provider();
    for(var type:PhysicsSpec.JointType.values()) try(var world=p.createWorld(PhysicsSpec.World.defaults().withoutReplay())) {
      int body=world.addBody(dynamic(1));
      int joint=world.addJoint(new PhysicsSpec.Joint(type,-1,body,Pose.IDENTITY,Pose.IDENTITY,new Vec3(-2,-2,-2),new Vec3(2,2,2),new Vec3(-1,-1,-1),new Vec3(1,1,1),new Vec3(10,10,10),new Vec3(10,10,10),.5f,true));
      if(type==PhysicsSpec.JointType.CONE_TWIST) world.configureJoint(joint,new PhysicsSpec.ConeTwistOptions(1,1,1,.8f,.3f,1,.1f,.01f,true,2,Rotation.IDENTITY));
      if(type==PhysicsSpec.JointType.SLIDER) world.configureJoint(joint,new PhysicsSpec.SliderOptions(true,1,10,false,0,0));
      if(type==PhysicsSpec.JointType.HINGE) world.configureJoint(joint,new PhysicsSpec.HingeOptions(.8f,.3f,1,true,1,2));
      world.step();assertTrue(Float.isFinite(world.bodyStates().get(body).pose().position().y()));
    }
  }

  @Test void localImpulseUsesBodyBasisLikeWasm() {
    var p=provider();
    try(var world=p.createWorld(new PhysicsSpec.World(Vec3.ZERO,1f/60,10).withoutReplay())) {
      var rotated=new Pose(Vec3.ZERO,Rotation.axisAngle(new Vec3(0,0,1), (float)(Math.PI/2)));
      int body=world.addBody(new PhysicsSpec.Body(PhysicsSpec.Shape.SPHERE,new Vec3(.25f,0,0),rotated,PhysicsSpec.Motion.DYNAMIC,1,0,0,0,.5f,.04f,1,0xffff));
      world.applyImpulse(body,new Vec3(1,0,0),Vec3.ZERO,true);
      assertEquals(0,world.bodyStates().get(body).linearVelocity().x(),1e-5);
      assertEquals(1,world.bodyStates().get(body).linearVelocity().y(),1e-5);
    }
  }

  @Test void bulkPosePublicationUpdatesSeveralBodiesInOneOperation() {
    var p=provider();
    try(var world=p.createWorld(new PhysicsSpec.World(Vec3.ZERO,1f/60,10).withoutReplay())) {
      int a=world.addBody(new PhysicsSpec.Body(PhysicsSpec.Shape.SPHERE,new Vec3(.25f,0,0),Pose.IDENTITY,PhysicsSpec.Motion.KINEMATIC,0,0,0,0,.5f,.04f,1,0xffff));
      int b=world.addBody(new PhysicsSpec.Body(PhysicsSpec.Shape.SPHERE,new Vec3(.25f,0,0),Pose.IDENTITY,PhysicsSpec.Motion.KINEMATIC,0,0,0,0,.5f,.04f,1,0xffff));
      world.setBodyPoses(new int[]{a,b},new Pose[]{at(2,3,4),at(-2,-3,-4)});
      assertEquals(new Vec3(2,3,4),world.bodyStates().get(a).pose().position());assertEquals(new Vec3(-2,-3,-4),world.bodyStates().get(b).pose().position());
    }
  }

  @Test void softBodyTriangleTrajectoryMatchesWasmBulletOracle() {
    var nativeProvider=provider();var wasmProvider=new cc.sirrus.ysmlib.scene.physics.wasm.WasmPhysicsProvider();
    var vertices=List.of(new Vec3(-1,2,0),new Vec3(1,2,0),new Vec3(-1,2,1),new Vec3(1,2,1));
    var spec=new PhysicsSpec.SoftBody(vertices,List.of(0,2,1,1,2,3),false,List.of(0,1),1,.02f,1,0xffff,PhysicsSpec.SoftConfig.defaults());
    try(var nativeWorld=nativeProvider.createWorld(PhysicsSpec.World.defaults().withoutReplay());var wasmWorld=wasmProvider.createWorld(PhysicsSpec.World.defaults().withoutReplay())) {
      int n=nativeWorld.addSoftBody(spec),w=wasmWorld.addSoftBody(spec);for(int i=0;i<30;i++){nativeWorld.step();wasmWorld.step();}
      var a=nativeWorld.softBodyVertices(n);var b=wasmWorld.softBodyVertices(w);assertEquals(b.size(),a.size());
      for(int i=0;i<a.size();i++){assertEquals(b.get(i).x(),a.get(i).x(),2e-3,"vertex "+i+" x");assertEquals(b.get(i).y(),a.get(i).y(),2e-3,"vertex "+i+" y");assertEquals(b.get(i).z(),a.get(i).z(),2e-3,"vertex "+i+" z");}
    }
  }

  @Test void softBodyRopeTrajectoryMatchesWasmBulletOracle() {
    var nativeProvider=provider();var wasmProvider=new cc.sirrus.ysmlib.scene.physics.wasm.WasmPhysicsProvider();
    var spec=new PhysicsSpec.SoftBody(List.of(new Vec3(0,4,0),new Vec3(0,3,0),new Vec3(0,2,0)),List.of(0,1,1,2),true,List.of(0),1,.02f,1,0xffff,PhysicsSpec.SoftConfig.defaults());
    try(var nativeWorld=nativeProvider.createWorld(PhysicsSpec.World.defaults().withoutReplay());var wasmWorld=wasmProvider.createWorld(PhysicsSpec.World.defaults().withoutReplay())) {
      int n=nativeWorld.addSoftBody(spec),w=wasmWorld.addSoftBody(spec);for(int i=0;i<30;i++){nativeWorld.step();wasmWorld.step();}
      var a=nativeWorld.softBodyVertices(n);var b=wasmWorld.softBodyVertices(w);assertEquals(b.size(),a.size());
      for(int i=0;i<a.size();i++){assertEquals(b.get(i).x(),a.get(i).x(),2e-3,"rope vertex "+i+" x");assertEquals(b.get(i).y(),a.get(i).y(),2e-3,"rope vertex "+i+" y");assertEquals(b.get(i).z(),a.get(i).z(),2e-3,"rope vertex "+i+" z");}
    }
  }
}
