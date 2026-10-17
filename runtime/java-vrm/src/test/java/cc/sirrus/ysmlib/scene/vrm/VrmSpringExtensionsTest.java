package cc.sirrus.ysmlib.scene.vrm;

import cc.sirrus.ysmlib.scene.*;
import com.google.gson.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static cc.sirrus.ysmlib.scene.vrm.VrmDocument.*;
import static cc.sirrus.ysmlib.scene.vrm.VrmFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class VrmSpringExtensionsTest {
  static void vector(Vec3 a,Vec3 b){assertEquals(a.x(),b.x(),2e-5);assertEquals(a.y(),b.y(),2e-5);assertEquals(a.z(),b.z(),2e-5);}
  @Test void limitsUseSpecifiedSingularDirectionsAndClampAngles() {
    var cone=new VrmSpringLimit(new Limit(LimitType.CONE,.3f,0,Rotation.IDENTITY),new Vec3(0,1,0),Rotation.IDENTITY);
    vector(new Vec3(0,(float)Math.cos(.3),(float)Math.sin(.3)),cone.direction(new Vec3(0,-1,0)));
    var hinge=new VrmSpringLimit(new Limit(LimitType.HINGE,.4f,0,Rotation.IDENTITY),new Vec3(0,1,0),Rotation.IDENTITY);
    vector(new Vec3(0,1,0),hinge.direction(new Vec3(1,0,0)));vector(new Vec3(0,1,0),hinge.direction(new Vec3(-1,0,0)));
    vector(new Vec3(0,(float)Math.cos(.4),(float)Math.sin(.4)),hinge.direction(new Vec3(0,-1,0)));
    var spherical=new VrmSpringLimit(new Limit(LimitType.SPHERICAL,.2f,.3f,Rotation.IDENTITY),new Vec3(0,1,0),Rotation.IDENTITY);
    vector(new Vec3((float)Math.sin(.3),(float)Math.cos(.3),0),spherical.direction(new Vec3(1,0,0)));
    vector(new Vec3(0,(float)Math.cos(.2),(float)Math.sin(.2)),spherical.direction(new Vec3(0,-1,0)));
    var unlimited=new VrmSpringLimit(new Limit(LimitType.CONE,10,0,Rotation.IDENTITY),new Vec3(0,1,0),Rotation.IDENTITY);vector(new Vec3(0,-1,0),unlimited.direction(new Vec3(0,-1,0)));
    var negativeAxis=new VrmSpringLimit(new Limit(LimitType.HINGE,0,0,Rotation.IDENTITY),new Vec3(0,-1,0),Rotation.IDENTITY);vector(new Vec3(0,-1,0),negativeAxis.constrain(Vec3.ZERO,new Vec3(1,0,0),1,Rotation.IDENTITY));
  }
  @Test void extendedColliderOverridesFallbackAndLastJointLimitIsIgnored() throws Exception {
    var root=avatar(false);var springs=JsonParser.parseString("""
      {"specVersion":"1.0","colliders":[{"node":0,"shape":{"sphere":{"radius":9}},"extensions":{"VRMC_springBone_extended_collider":{"specVersion":"1.0","shape":{"plane":{"normal":[1,0,0]}}}}}],
      "springs":[{"joints":[{"node":0,"extensions":{"VRMC_springBone_limit":{"specVersion":"1.0","limit":{"cone":{"angle":0.3}}}}},{"node":1,"extensions":{"VRMC_springBone_limit":{"specVersion":"unsupported"}}}]}]}
      """).getAsJsonObject();root.getAsJsonObject("extensions").add("VRMC_springBone",springs);
    var parsed=read(root);assertEquals(new Vec3(1,0,0),parsed.colliders().get(0).planeNormal());assertEquals(0,parsed.colliders().get(0).radius());assertEquals(LimitType.CONE,parsed.springs().get(0).joints().get(0).limit().type());assertNull(parsed.springs().get(0).joints().get(1).limit());
    springs.getAsJsonArray("colliders").get(0).getAsJsonObject().getAsJsonObject("extensions").getAsJsonObject("VRMC_springBone_extended_collider").getAsJsonObject("shape").getAsJsonObject("plane").add("normal",JsonParser.parseString("[2,0,0]"));
    assertThrows(Exception.class,()->read(root));
  }
  @Test void insideSphereCapsuleAndPlaneMatchReferenceProjection() {
    int[] parents={-1,0,1};var rest=List.of(Transform.IDENTITY,Transform.IDENTITY,new Transform(new Vec3(0,1,0),Rotation.IDENTITY,Vec3.ONE));var scene=scene(parents,rest);
    for(var collider:List.of(new Collider(0,new Vec3(.3f,.5f,0),null,.4f,true,null),new Collider(0,new Vec3(.3f,.5f,-1),new Vec3(.3f,.5f,1),.4f,true,null),new Collider(0,new Vec3(.3f,0,0),null,0,false,new Vec3(1,0,0)))){
      var joint=new Joint(1,.05f,0,0,Vec3.ZERO,1);var doc=document(scene,joint,collider);var solver=new VrmSpringSolver(doc);
      var frame=solver.step(pose(parents,rest),rest.stream().map(Transform::rotation).toList(),1.0/60);
      Vec3 expected;if(collider.planeNormal()!=null)expected=new Vec3(.35f,1,0).normalized();else{var center=new Vec3(.3f,.5f,0);expected=center.add(new Vec3(0,1,0).subtract(center).normalized().multiply(.35f)).normalized();}
      vector(expected,SceneRig.position(frame.pose().globalMatrices().get(2)));
    }
  }
  @Test void angularLimitRunsAfterCollisionAndSnapshotsKeepPendingTeleport() {
    int[] parents={-1,0,1};var rest=List.of(Transform.IDENTITY,Transform.IDENTITY,new Transform(new Vec3(0,1,0),Rotation.IDENTITY,Vec3.ONE));
    var joint=new Joint(1,.05f,0,20,new Vec3(1,0,0),0,new Limit(LimitType.CONE,.1f,0,Rotation.IDENTITY));
    var solver=new VrmSpringSolver(document(scene(parents,rest),joint,new Collider(0,new Vec3(.8f,0,0),null,0,false,new Vec3(1,0,0))));
    for(int i=0;i<50;i++){var tip=SceneRig.position(solver.step(pose(parents,rest),rest.stream().map(Transform::rotation).toList(),1.0/60).pose().globalMatrices().get(2));assertTrue(tip.y()>=Math.cos(.1)-1e-5);assertEquals(1,tip.dot(tip),1e-5);}
    var input=new ScenePhysicsInput(0,0,0,0,Matrix4.IDENTITY,Vec3.ZERO,List.of(),List.of(),ScenePhysicsInput.Settings.defaults());
    solver.environment(new cc.sirrus.ysmlib.scene.physics.PhysicsEnvironment(input,Vec3.ZERO,Vec3.ZERO,Vec3.ZERO,Vec3.ZERO,true));var saved=solver.snapshot();var first=solver.step(pose(parents,rest),rest.stream().map(Transform::rotation).toList(),1.0/60);solver.restore(saved);assertEquals(first,solver.step(pose(parents,rest),rest.stream().map(Transform::rotation).toList(),1.0/60));
  }
  static VrmDocument document(SceneAsset scene,Joint joint,Collider collider){return new VrmDocument(new GltfDocument(scene,ByteData.EMPTY,Map.of()),Version.VRM_1,Map.of(),List.of(),List.of(),List.of(collider),List.of(new ColliderGroup("",new IntData(0))),List.of(new Spring("",-1,List.of(joint,new Joint(2,0,0,0,Vec3.ZERO,1)),new IntData(0))),List.of(),null,null,List.of(),COVERAGE);}
}
