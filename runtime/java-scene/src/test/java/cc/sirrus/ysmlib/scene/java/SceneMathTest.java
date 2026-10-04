package cc.sirrus.ysmlib.scene.java;

import cc.sirrus.ysmlib.scene.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static cc.sirrus.ysmlib.scene.AnimationCurve.Interpolation.*;

class SceneMathTest {
  private AnimationCurve curve(float[] values,int components,boolean quaternion,AnimationCurve.Interpolation interpolation,float[] in,float[] out,float[] bezier) {
    return new AnimationCurve(new double[]{0,2},new FloatData(values),components,quaternion,interpolation,new FloatData(in),new FloatData(out),new FloatData(bezier));
  }
  @Test void stepAndCubicUseSecondsAndClampWithoutHistory() {
    var step=curve(new float[]{2,8},1,false,STEP,new float[0],new float[0],new float[0]);
    assertArrayEquals(new float[]{2},CurveSampler.sample(step,1.99));assertArrayEquals(new float[]{8},CurveSampler.sample(step,2));
    var cubic=curve(new float[]{0,2},1,false,CUBICSPLINE,new float[]{0,0},new float[]{2,0},new float[0]);
    assertArrayEquals(new float[]{1.5f},CurveSampler.sample(cubic,1),1e-6f);
    assertArrayEquals(new float[]{0},CurveSampler.sample(cubic,-100));
    assertArrayEquals(new float[]{2},CurveSampler.sample(cubic,100));
    assertThrows(IllegalArgumentException.class,()->CurveSampler.sample(cubic,Double.NaN));
  }
  @Test void bezierInvertsXAndUsesDestinationControlPoints() {
    // Destination x(t)=t^3, y(t)=1-(1-t)^3. At x=1/8, y=7/8.
    var bezier=curve(new float[]{0,8},1,false,BEZIER,new float[0],new float[0],new float[]{0,0,1,1,0,1,0,1});
    assertArrayEquals(new float[]{7},CurveSampler.sample(bezier,.25),1e-5f);
  }
  @Test void quaternionUsesShortestSlerpAndCubicNormalization() {
    var linear=curve(new float[]{0,0,0,1,0,0,-1,0},4,true,LINEAR,new float[0],new float[0],new float[0]);
    assertArrayEquals(new float[]{0,0,-.70710677f,.70710677f},CurveSampler.sample(linear,1),1e-6f);
    var anti=curve(new float[]{0,0,0,1,0,0,0,-1},4,true,LINEAR,new float[0],new float[0],new float[0]);
    assertArrayEquals(new float[]{0,0,0,1},CurveSampler.sample(anti,1),1e-6f);
    var cubic=curve(new float[]{0,0,0,1,0,0,1,0},4,true,CUBICSPLINE,new float[8],new float[8],new float[0]);
    assertArrayEquals(new float[]{0,0,.70710677f,.70710677f},CurveSampler.sample(cubic,1),1e-6f);
  }
  @Test void matrixInverseAndOwnership() {
    var m=new Transform(new Vec3(2,3,4),Rotation.normalized(1,2,3,4),new Vec3(2,3,-4)).matrix();
    var p=new Vec3(4,2,-5);var back=m.inverse().transformPoint(m.transformPoint(p));
    assertEquals(p.x(),back.x(),1e-5);assertEquals(p.y(),back.y(),1e-5);assertEquals(p.z(),back.z(),1e-5);
    float[] values={1,2,3};var owned=new FloatData(values);values[0]=99;assertEquals(1,owned.get(0));
    assertThrows(java.nio.ReadOnlyBufferException.class,()->owned.view().put(0,8));
    assertThrows(IllegalArgumentException.class,()->new Matrix4(new float[16]).inverse());
  }
  private MeshAsset.Primitive mesh(MeshAsset.Deform deform,int[] joints,float[] weights,float[] sdef,List<MeshAsset.MorphTarget> morphs) {
    var skin=new MeshAsset.Skinning(new IntData(0,joints.length),new IntData(joints),new FloatData(weights),List.of(deform),new FloatData(sdef));
    return new MeshAsset.Primitive(MeshAsset.Topology.POINTS,Map.of("POSITION",new MeshAsset.Attribute(3,new FloatData(1,0,0)),
        "NORMAL",new MeshAsset.Attribute(3,new FloatData(1,1,0))),new IntData(0),-1,skin,morphs);
  }
  @Test void unlimitedInfluencesAndMorphsAreAppliedBeforeSkinning() {
    var morphs=new ArrayList<MeshAsset.MorphTarget>();var weights=new float[150];Arrays.fill(weights,1);
    for(int i=0;i<150;i++) morphs.add(new MeshAsset.MorphTarget("m"+i,Map.of("POSITION",new MeshAsset.Attribute(3,new FloatData(.01f,0,0)))));
    var mesh=mesh(MeshAsset.Deform.LINEAR,new int[]{0,1,2,3,4},new float[]{.2f,.2f,.2f,.2f,.2f},new float[0],morphs);
    var palette=new ArrayList<Matrix4>();for(int i=0;i<5;i++) palette.add(new Transform(new Vec3(i,0,0),Rotation.IDENTITY,Vec3.ONE).matrix());
    var result=new Deformer().deform(mesh,palette,new FloatData(weights),Deformer.NormalMode.INVERSE_TRANSPOSE);
    assertEquals(4.5f,result.get("POSITION").values().get(0),1e-5);
    assertEquals(1,mesh.attributes().get("POSITION").values().get(0));
  }
  @Test void qdefRetainsVolumeWhereLinearTwistCollapses() {
    var palette=List.of(Matrix4.IDENTITY,new Transform(Vec3.ZERO,new Rotation(0,0,1,0),Vec3.ONE).matrix());
    var qdef=mesh(MeshAsset.Deform.QDEF,new int[]{0,1,-1,-1},new float[]{.5f,.5f,0,0},new float[0],List.of());
    var result=new Deformer().deform(qdef,palette,FloatData.EMPTY,Deformer.NormalMode.MMD_WEIGHTED_ROTATION);
    assertArrayEquals(new float[]{0,1,0},result.get("POSITION").values().copy(),1e-6f);
    var linear=mesh(MeshAsset.Deform.LINEAR,new int[]{0,1},new float[]{.5f,.5f},new float[0],List.of());
    assertArrayEquals(new float[]{0,0,0},new Deformer().deform(linear,palette,FloatData.EMPTY,Deformer.NormalMode.MMD_WEIGHTED_ROTATION).get("POSITION").values().copy(),1e-6f);
  }
  @Test void sdefUsesOriginalPivotParameters() {
    var palette=List.of(new Transform(Vec3.ZERO,Rotation.normalized(0,0,1,1),Vec3.ONE).matrix(),
        new Transform(Vec3.ZERO,Rotation.normalized(0,0,-1,1),Vec3.ONE).matrix());
    var sdef=mesh(MeshAsset.Deform.SDEF,new int[]{0,1},new float[]{.5f,.5f},new float[]{0,0,0,1,0,0,-1,0,0},List.of());
    var result=new Deformer().deform(sdef,palette,FloatData.EMPTY,Deformer.NormalMode.MMD_WEIGHTED_ROTATION);
    assertArrayEquals(new float[]{1,.5f,0},result.get("POSITION").values().copy(),1e-6f);
    assertThrows(IllegalArgumentException.class,()->new Deformer().deform(sdef,List.of(new Transform(Vec3.ZERO,Rotation.IDENTITY,new Vec3(2,1,1)).matrix(),Matrix4.IDENTITY),FloatData.EMPTY,Deformer.NormalMode.MMD_WEIGHTED_ROTATION));
  }
  @Test void inverseTransposePreservesNormalUnderNonuniformScale() {
    var mesh=mesh(MeshAsset.Deform.LINEAR,new int[]{0},new float[]{1},new float[0],List.of());
    var result=new Deformer().deform(mesh,List.of(new Transform(Vec3.ZERO,Rotation.IDENTITY,new Vec3(2,1,1)).matrix()),FloatData.EMPTY,Deformer.NormalMode.INVERSE_TRANSPOSE);
    assertArrayEquals(new float[]{.4472136f,.8944272f,0},result.get("NORMAL").values().copy(),1e-6f);
  }
}
