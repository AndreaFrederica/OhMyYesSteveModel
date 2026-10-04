package cc.sirrus.ysmlib.scene.vrm;

import cc.sirrus.ysmlib.scene.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static cc.sirrus.ysmlib.scene.vrm.VrmDocument.*;
import static cc.sirrus.ysmlib.scene.vrm.VrmFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class VrmConstraintsTest {
  @Test void matchesUnmodifiedThreeVrmFor121FramesWithRotatedRestAndDependencyChain() throws Exception {
    var fixture=resource("constraints.json").getAsJsonObject();int[] parents=new int[fixture.getAsJsonArray("parents").size()];for(int i=0;i<parents.length;i++) parents[i]=fixture.getAsJsonArray("parents").get(i).getAsInt();
    var rest=new ArrayList<Transform>();fixture.getAsJsonArray("rest").forEach(e->rest.add(transform(e.getAsJsonObject())));
    var constraints=new ArrayList<Constraint>();for(var value:fixture.getAsJsonArray("declarations")) {
      var d=value.getAsJsonObject();String type=d.get("type").getAsString();var axis=type.equals("ROLL")?new Vec3(0,1,0):type.equals("AIM")?new Vec3(0,0,-1):Vec3.ZERO;
      constraints.add(new Constraint(d.get("node").getAsInt(),d.get("source").getAsInt(),ConstraintType.valueOf(type),axis,d.get("weight").getAsFloat()));
    }
    var evaluator=new VrmConstraints(scene(parents,rest),constraints);
    for(var value:fixture.getAsJsonArray("frames")) {
      var f=value.getAsJsonObject();var input=new ArrayList<Transform>();f.getAsJsonArray("input").forEach(e->input.add(transform(e.getAsJsonObject())));
      var result=evaluator.evaluate(pose(parents,input),input.stream().map(Transform::rotation).toList());
      for(int n=0;n<parents.length;n++) {
        var expected=f.getAsJsonArray("rotations").get(n).getAsJsonArray();var q=result.localRotations().get(n);
        double dot=q.x()*expected.get(0).getAsDouble()+q.y()*expected.get(1).getAsDouble()+q.z()*expected.get(2).getAsDouble()+q.w()*expected.get(3).getAsDouble();
        assertEquals(1,Math.abs(dot),3e-6,"quaternion node "+n);
        var matrix=f.getAsJsonArray("global").get(n).getAsJsonArray();float[] actual=result.pose().globalMatrices().get(n).copy();
        for(int k=0;k<16;k++) assertEquals(matrix.get(k).getAsDouble(),actual[k],8e-6,"matrix node "+n+" element "+k);
      }
    }
  }
  @Test void detectsCyclesThroughAncestorTransformsAndAllowsLocalOnlyDependencies() {
    int[] parents={-1,0,-1};var transforms=List.of(Transform.IDENTITY,new Transform(new Vec3(0,1,0),Rotation.IDENTITY,Vec3.ONE),Transform.IDENTITY);var scene=scene(parents,transforms);
    assertThrows(IllegalArgumentException.class,()->new VrmConstraints(scene,List.of(new Constraint(0,1,ConstraintType.AIM,new Vec3(0,1,0),1))));
    assertThrows(IllegalArgumentException.class,()->new VrmConstraints(scene,List.of(new Constraint(0,2,ConstraintType.ROTATION,Vec3.ZERO,1),new Constraint(2,0,ConstraintType.ROLL,new Vec3(1,0,0),1))));
    assertDoesNotThrow(()->new VrmConstraints(scene,List.of(new Constraint(0,1,ConstraintType.ROTATION,Vec3.ZERO,1))));
  }
  @Test void preservesSignedNonUniformScaleAndUsesStableAntiparallelRotation() {
    int[] parents={-1,-1};var rest=List.of(Transform.IDENTITY,new Transform(new Vec3(4,3,2),Rotation.IDENTITY,new Vec3(-2,3,.5f)));
    var input=List.of(new Transform(Vec3.ZERO,Rotation.axisAngle(new Vec3(0,1,0),Math.PI),Vec3.ONE),rest.get(1));
    var evaluator=new VrmConstraints(scene(parents,rest),List.of(new Constraint(1,0,ConstraintType.ROTATION,Vec3.ZERO,1)));
    var result=evaluator.evaluate(pose(parents,input),input.stream().map(Transform::rotation).toList());var m=result.pose().localMatrices().get(1);
    assertEquals(2,VrmMath.column(m,0).x(),1e-6);assertEquals(3,VrmMath.column(m,1).y(),1e-6);assertEquals(-.5,VrmMath.column(m,2).z(),1e-6);assertEquals(new Vec3(4,3,2),VrmMath.position(m));
    var q=VrmMath.fromTo(new Vec3(0,1,0),new Vec3(0,-1,0));assertEquals(-1,q.rotate(new Vec3(0,1,0)).y(),1e-6);
  }
}
