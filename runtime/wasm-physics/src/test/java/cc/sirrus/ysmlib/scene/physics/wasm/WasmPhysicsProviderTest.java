package cc.sirrus.ysmlib.scene.physics.wasm;

import cc.sirrus.ysmlib.scene.Pose;
import cc.sirrus.ysmlib.scene.Rotation;
import cc.sirrus.ysmlib.scene.Vec3;
import cc.sirrus.ysmlib.scene.physics.PhysicsSpec;
import cc.sirrus.ysmlib.scene.physics.PhysicsWorld;
import java.util.List;
import java.nio.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WasmPhysicsProviderTest {
  private static final WasmPhysicsProvider PROVIDER=new WasmPhysicsProvider();
  private static Pose at(float x,float y,float z) { return new Pose(new Vec3(x,y,z),Rotation.IDENTITY); }
  private static PhysicsSpec.Body sphere(float y,int group,int mask) {
    return new PhysicsSpec.Body(PhysicsSpec.Shape.SPHERE,new Vec3(0.5f,0,0),at(0,y,0),PhysicsSpec.Motion.DYNAMIC,
        1,0,0,0,0.5f,0.04f,group,mask);
  }
  private static void floor(PhysicsWorld world) {
    world.addBody(new PhysicsSpec.Body(PhysicsSpec.Shape.BOX,new Vec3(10,0.5f,10),at(0,-0.5f,0),PhysicsSpec.Motion.STATIC,
        0,0,0,0,0.5f,0.04f,1,0xffff));
  }
  @Test void packedTransfersPreserveScalarRotationTrajectoryAndRejectPartialReadback() {
    var settings=new PhysicsSpec.World(Vec3.ZERO,1f/60,10,false);
    try(var scalar=PROVIDER.createWorld(settings);var packed=PROVIDER.createWorld(settings)) {
      for(var world:List.of(scalar,packed)){world.addBody(sphere(0,1,0));}
      var ids=ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer();
      var input=ByteBuffer.allocateDirect(28).order(ByteOrder.nativeOrder()).asFloatBuffer();
      var output=FloatBuffer.allocate(15);var tooSmall=FloatBuffer.allocate(12);for(int i=0;i<12;i++)tooSmall.put(i,123);
      for(int step=0;step<30;step++) {
        // Accepted unit tolerance is intentional: transfer must not silently change authored scalars.
        var q=new Rotation(.1f,.2f,.3f,.9274f);var pose=new Pose(new Vec3(step*.01f,1,0),q);
        scalar.setBodyPose(0,pose);input.clear();input.put(pose.position().x()).put(1).put(0).put(q.x()).put(q.y()).put(q.z()).put(q.w()).flip();packed.setBodyPoses(ids,input);
        assertEquals(0,ids.position());assertEquals(0,input.position());scalar.step();packed.step();assertEquals(scalar.bodyStates(),packed.bodyStates());
        assertThrows(IllegalArgumentException.class,()->packed.readBodyStates(tooSmall));assertEquals(0,tooSmall.position());for(int i=0;i<12;i++)assertEquals(123,tooSmall.get(i));
        output.clear();output.position(1);assertEquals(1,packed.readBodyStates(output));assertEquals(14,output.position());
        var normalized=Rotation.normalized(output.get(4),output.get(5),output.get(6),output.get(7));assertEquals(packed.bodyStates().get(0).pose().rotation(),normalized);
      }
    }
  }

  @Test void freeFallUsesExplicitFixedStepsAndQueriesDoNotAdvanceTime() {
    var settings=PhysicsSpec.World.defaults();
    try(var world=PROVIDER.createWorld(settings)) {
      world.addBody(sphere(10,1,0xffff));
      for(int i=0;i<60;i++) world.step();
      var result=world.bodyStates();
      float dt=settings.stepSeconds();
      assertEquals(10+settings.gravity().y()*dt*dt*60*61/2,result.get(0).pose().position().y(),0.0003);
      assertEquals(settings.gravity().y()*dt*60,result.get(0).linearVelocity().y(),0.0001);
      assertEquals(result,world.bodyStates());assertEquals(60,world.stepIndex());
    }
  }

  @Test void collisionMasksControlActualSphereGroundContact() {
    try(var world=PROVIDER.createWorld(PhysicsSpec.World.defaults())) {
      floor(world);int colliding=world.addBody(sphere(3,1,0xffff));
      int filtered=world.addBody(sphere(3,2,0));
      for(int i=0;i<240;i++) world.step();
      var result=world.bodyStates();
      assertEquals(0.5,result.get(colliding).pose().position().y(),0.025);
      assertTrue(result.get(filtered).pose().position().y() < -50);
    }
  }

  @Test void sixDofSpringConstrainsARealDynamicBody() {
    try(var world=PROVIDER.createWorld(PhysicsSpec.World.defaults())) {
      int root=world.addBody(new PhysicsSpec.Body(PhysicsSpec.Shape.SPHERE,new Vec3(0.1f,0,0),Pose.IDENTITY,
          PhysicsSpec.Motion.KINEMATIC,0,0,0,0,0,0.04f,1,0));
      int dynamic=world.addBody(sphere(-1,1,0));
      world.addJoint(new PhysicsSpec.Joint(PhysicsSpec.JointType.SPRING_6DOF,root,dynamic,at(0,-1,0),Pose.IDENTITY,
          new Vec3(0,-2,0),new Vec3(0,2,0),Vec3.ZERO,Vec3.ZERO,new Vec3(0,40,0),Vec3.ZERO,0.5f,true));
      for(int i=0;i<180;i++) world.step();
      float y=world.bodyStates().get(dynamic).pose().position().y();
      assertTrue(y < -1 && y > -3.1,"spring body y="+y);
    }
  }

  @Test void softBodyPinsRemainFixedWhileClothFalls() {
    try(var world=PROVIDER.createWorld(PhysicsSpec.World.defaults())) {
      var vertices=List.of(new Vec3(-1,2,0),new Vec3(1,2,0),new Vec3(-1,2,1),new Vec3(1,2,1));
      int soft=world.addSoftBody(new PhysicsSpec.SoftBody(vertices,List.of(0,2,1,1,2,3),false,List.of(0,1),
          1,0.02f,1,0xffff,PhysicsSpec.SoftConfig.defaults()));
      for(int i=0;i<60;i++) world.step();
      var result=world.softBodyVertices(soft);
      assertEquals(vertices.get(0),result.get(0));assertEquals(vertices.get(1),result.get(1));
      assertTrue(result.get(2).y()<1.9);assertTrue(result.get(3).y()<1.9);
    }
  }

  @Test void replayRestoresContactSolverAndSoftBodyHistoryExactly() {
    try(var world=PROVIDER.createWorld(PhysicsSpec.World.defaults())) {
      floor(world);int ball=world.addBody(sphere(2,1,0xffff));
      int rope=world.addSoftBody(new PhysicsSpec.SoftBody(List.of(new Vec3(0,4,0),new Vec3(0,3,0),new Vec3(0,2,0)),
          List.of(0,1,1,2),true,List.of(0),1,0.02f,1,0xffff,PhysicsSpec.SoftConfig.defaults()));
      for(int i=0;i<90;i++) world.step();
      var before=world.bodyStates();var beforeSoft=world.softBodyVertices(rope);var checkpoint=world.snapshot();
      world.applyImpulse(ball,new Vec3(1,2,0),new Vec3(0,0,1),false);
      for(int i=0;i<30;i++) world.step();
      var after=world.bodyStates();var afterSoft=world.softBodyVertices(rope);
      world.restore(checkpoint);
      assertEquals(90,world.stepIndex());assertEquals(before,world.bodyStates());assertEquals(beforeSoft,world.softBodyVertices(rope));
      world.applyImpulse(ball,new Vec3(1,2,0),new Vec3(0,0,1),false);
      for(int i=0;i<30;i++) world.step();
      assertEquals(after,world.bodyStates());assertEquals(afterSoft,world.softBodyVertices(rope));
    }
  }

  @Test void invalidReferencesAndForeignCheckpointsAreRejectedWithoutChangingWorld() {
    var first=PROVIDER.createWorld(PhysicsSpec.World.defaults());
    try(first;var second=PROVIDER.createWorld(PhysicsSpec.World.defaults())) {
      first.addBody(sphere(1,1,0xffff));
      assertThrows(IllegalArgumentException.class,()->first.setVelocity(4,Vec3.ZERO,Vec3.ZERO));
      assertThrows(IllegalArgumentException.class,()->second.restore(first.snapshot()));
      first.step();assertEquals(1,first.stepIndex());
    }
    assertThrows(IllegalStateException.class,first::step);
    first.close();
  }

  @Test void contactTrajectoryMatchesIndependentNativeBulletFixture() throws Exception {
    try(var input=getClass().getResourceAsStream("/bullet-3.25-contact.csv");
        var world=PROVIDER.createWorld(PhysicsSpec.World.defaults().withoutReplay())) {
      assertNotNull(input,"Regenerate the frozen native oracle fixture with engine/build_oracle.py");
      floor(world);world.addBody(sphere(3,1,0xffff));
      var rows=new BufferedReader(new InputStreamReader(input,StandardCharsets.US_ASCII)).lines().filter(l->!l.startsWith("#")).toList();
      for(var row:rows) {
        var values=row.split(",");int target=Integer.parseInt(values[0]);
        while(world.stepIndex()<target) world.step();
        var actual=world.bodyStates().get(1);var p=actual.pose().position();var v=actual.linearVelocity();
        float[] observed={p.x(),p.y(),p.z(),v.x(),v.y(),v.z()};
        for(int component=0;component<observed.length;component++)
          assertEquals(Float.parseFloat(values[component+1]),observed[component],0.0005,"step="+target+" component="+component);
      }
      assertThrows(IllegalStateException.class,world::snapshot);
    }
  }

  @Test void jointMotorsWorldAnchorAndAnimatedSoftPinsSurviveReplay() {
    try(var world=PROVIDER.createWorld(new PhysicsSpec.World(Vec3.ZERO,1f/60,10))) {
      int body=world.addBody(sphere(0,1,0));
      int joint=world.addJoint(new PhysicsSpec.Joint(PhysicsSpec.JointType.SLIDER,-1,body,Pose.IDENTITY,Pose.IDENTITY,
          new Vec3(-10,0,0),new Vec3(10,0,0),Vec3.ZERO,Vec3.ZERO,Vec3.ZERO,Vec3.ZERO,0,false));
      world.configureJoint(joint,new PhysicsSpec.SliderOptions(true,2,10,false,0,0));
      int rope=world.addSoftBody(new PhysicsSpec.SoftBody(List.of(new Vec3(0,2,0),new Vec3(0,1,0)),List.of(0,1),true,List.of(0),1,.02f,1,0,PhysicsSpec.SoftConfig.defaults()));
      world.setSoftBodyPin(rope,0,new Vec3(1,2,0));for(int i=0;i<30;i++) world.step();
      var checkpoint=world.snapshot();var states=world.bodyStates();var vertices=world.softBodyVertices(rope);
      assertTrue(Math.abs(states.get(body).pose().position().x())>.5);
      assertEquals(new Vec3(1,2,0),vertices.get(0));assertEquals(2,world.softBodyNormals(rope).size());
      assertThrows(IllegalArgumentException.class,()->world.setSoftBodyPin(rope,1,Vec3.ZERO));
      for(int i=0;i<15;i++) world.step();world.restore(checkpoint);assertEquals(states,world.bodyStates());assertEquals(vertices,world.softBodyVertices(rope));
      world.resetBodyForces(body);assertEquals(Vec3.ZERO,world.bodyStates().get(body).linearVelocity());
    }
  }
  @Test void degenerateSoftFacesAndZeroLengthLinksNeverEnterBullet() {
    assertThrows(IllegalArgumentException.class,()->new PhysicsSpec.SoftBody(List.of(Vec3.ZERO,new Vec3(1,1,0),new Vec3(2,2,0)),List.of(0,1,2),false,List.of(),1,.02f,1,0,PhysicsSpec.SoftConfig.defaults()));
    assertThrows(IllegalArgumentException.class,()->new PhysicsSpec.SoftBody(List.of(Vec3.ZERO,Vec3.ZERO),List.of(0,1),true,List.of(),1,.02f,1,0,PhysicsSpec.SoftConfig.defaults()));
  }
  @Test void animationDrivenBodyCanResumeDynamicsWithChangedGravityAndReplay() {
    try(var world=PROVIDER.createWorld(PhysicsSpec.World.defaults())) {
      int body=world.addBody(sphere(3,1,0));world.step();world.setKinematic(body,true);world.setBodyPose(body,at(0,5,0));
      for(int i=0;i<30;i++) world.step();assertEquals(5,world.bodyStates().get(body).pose().position().y(),1e-6);
      var saved=world.snapshot();world.setGravity(new Vec3(0,12,0));world.setKinematic(body,false);
      for(int i=0;i<30;i++) world.step();var result=world.bodyStates();assertTrue(result.get(body).pose().position().y()>6);
      world.restore(saved);world.setGravity(new Vec3(0,12,0));world.setKinematic(body,false);for(int i=0;i<30;i++) world.step();assertEquals(result,world.bodyStates());
    }
  }
}
