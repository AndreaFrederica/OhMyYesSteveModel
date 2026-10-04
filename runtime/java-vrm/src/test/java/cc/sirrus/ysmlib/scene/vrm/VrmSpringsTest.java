package cc.sirrus.ysmlib.scene.vrm;

import cc.sirrus.ysmlib.scene.*;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static cc.sirrus.ysmlib.scene.vrm.VrmDocument.*;
import static cc.sirrus.ysmlib.scene.vrm.VrmFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class VrmSpringsTest {
  private VrmDocument springDocument(SceneAsset scene,int center,Joint parameters,Collider collider) {
    return new VrmDocument(new GltfDocument(scene,new ByteData(new byte[0]),Map.of()),Version.VRM_1,Map.of(),List.of(),List.of(),
        collider==null?List.of():List.of(collider),collider==null?List.of():List.of(new ColliderGroup("",new IntData(0))),
        List.of(new Spring("",center,List.of(parameters,new Joint(2,0,1,0,new Vec3(0,-1,0),.5f)),collider==null?IntData.EMPTY:new IntData(0))),
        List.of(),null,null,List.of(),COVERAGE);
  }
  @Test void matchesUnmodifiedThreeVrmForSphereCapsuleAndCenterSpaceAcross960Frames() throws Exception {
    int[] parents={-1,0,1,0};
    for(var caseValue:resource("springs.json").getAsJsonArray()) {
      var c=caseValue.getAsJsonObject();var rest=new ArrayList<Transform>();c.getAsJsonArray("rest").forEach(v->rest.add(transform(v.getAsJsonObject())));
      var p=c.getAsJsonObject("params");var parameters=new Joint(1,p.get("hitRadius").getAsFloat(),p.get("stiffness").getAsFloat(),p.get("gravityPower").getAsFloat(),vec(p.getAsJsonArray("gravityDir")),p.get("dragForce").getAsFloat());
      var col=c.getAsJsonObject("collider");var collider=new Collider(3,vec(col.getAsJsonArray("offset")),col.get("tail").isJsonNull()?null:vec(col.getAsJsonArray("tail")),col.get("radius").getAsFloat());
      var solver=new VrmSpringSolver(springDocument(scene(parents,rest),c.get("center").getAsInt(),parameters,collider));
      int frame=0;for(var f:c.getAsJsonArray("frames")) {
        frame++;var expected=f.getAsJsonObject();var input=new ArrayList<Transform>();expected.getAsJsonArray("input").forEach(v->input.add(transform(v.getAsJsonObject())));
        var result=solver.step(pose(parents,input),input.stream().map(Transform::rotation).toList(),1.0/60);
        for(int n=0;n<parents.length;n++) {
          var matrix=expected.getAsJsonArray("global").get(n).getAsJsonArray();float[] actual=result.pose().globalMatrices().get(n).copy();
          for(int k=0;k<16;k++) assertEquals(matrix.get(k).getAsDouble(),actual[k],2e-4,"frame "+frame+" node "+n+" element "+k+" center "+c.get("center")+" capsule "+collider.capsule());
        }
      }
    }
  }
  @Test void snapshotsRestoreAllHistoryAndSamplingDoesNotAdvanceTime() {
    int[] parents={-1,0,1};var rest=List.of(Transform.IDENTITY,Transform.IDENTITY,new Transform(new Vec3(0,-1,0),Rotation.IDENTITY,Vec3.ONE));
    var p=new Joint(1,0,1,.5f,new Vec3(1,0,0),.2f);var document=springDocument(scene(parents,rest),0,p,null);var solver=new VrmSpringSolver(document);
    var pose=pose(parents,rest);var rotations=rest.stream().map(Transform::rotation).toList();
    for(int i=0;i<20;i++) solver.step(pose,rotations,1.0/60);var saved=solver.snapshot();var before=solver.sample(pose,rotations);
    for(int i=0;i<10;i++) assertEquals(before.pose().localMatrices(),solver.sample(pose,rotations).pose().localMatrices());
    var expected=new ArrayList<List<Matrix4>>();for(int i=0;i<40;i++) expected.add(solver.step(pose,rotations,1.0/60).pose().localMatrices());
    solver.restore(saved);for(int i=0;i<40;i++) assertEquals(expected.get(i),solver.step(pose,rotations,1.0/60).pose().localMatrices());
    assertThrows(IllegalArgumentException.class,()->new VrmSpringSolver(document).restore(saved));assertThrows(IllegalArgumentException.class,()->solver.step(pose,rotations,-.1));
  }
  @Test void skippedJointUsesHeadLocalTailAndLegacyTerminalAddsSevenCentimeters() {
    int[] parents={-1,0,1,2};var rest=List.of(Transform.IDENTITY,Transform.IDENTITY,new Transform(new Vec3(0,-.4f,0),Rotation.IDENTITY,Vec3.ONE),new Transform(new Vec3(0,-.6f,0),Rotation.IDENTITY,Vec3.ONE));
    var p=new Joint(1,0,0,1,new Vec3(1,0,0),.5f);var base=springDocument(scene(parents,rest),-1,p,null);
    var sparse=new VrmDocument(base.gltf(),base.version(),base.humanBones(),base.expressions(),base.constraints(),base.colliders(),base.colliderGroups(),List.of(new Spring("",-1,List.of(p,new Joint(3,0,1,0,new Vec3(0,-1,0),.5f)),IntData.EMPTY)),List.of(),null,null,List.of(),COVERAGE);
    var result=new VrmSpringSolver(sparse).step(pose(parents,rest),rest.stream().map(Transform::rotation).toList(),1.0/60);
    Vec3 tail=VrmMath.position(result.pose().globalMatrices().get(3));assertEquals(1,Math.sqrt(tail.dot(tail)),1e-6);assertTrue(tail.x()>0);
    var legacy=new VrmDocument(base.gltf(),Version.VRM_0,Map.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(new LegacySpring("",-1,new IntData(3),p,IntData.EMPTY)),null,null,List.of(),COVERAGE);
    var old=new VrmSpringSolver(legacy).step(pose(parents,rest),rest.stream().map(Transform::rotation).toList(),1.0/60);
    assertTrue(old.localRotations().get(3).z()>0);assertEquals(Rotation.IDENTITY,result.localRotations().get(3));
  }
  @Test void rejectsCenterDependencyCyclesAndHandlesCoincidentColliderWithoutNaNs() {
    int[] parents={-1,0,1};var rest=List.of(Transform.IDENTITY,Transform.IDENTITY,new Transform(new Vec3(0,-1,0),Rotation.IDENTITY,Vec3.ONE));var p=new Joint(1,.1f,0,0,new Vec3(0,-1,0),.5f);
    assertThrows(IllegalArgumentException.class,()->new VrmSpringSolver(springDocument(scene(parents,rest),2,p,null)));
    var solver=new VrmSpringSolver(springDocument(scene(parents,rest),-1,p,new Collider(0,new Vec3(0,-1,0),null,.2f)));
    var result=solver.step(pose(parents,rest),rest.stream().map(Transform::rotation).toList(),1.0/60);
    for(var matrix:result.pose().globalMatrices()) for(float f:matrix.copy()) assertTrue(Float.isFinite(f));
  }
}
