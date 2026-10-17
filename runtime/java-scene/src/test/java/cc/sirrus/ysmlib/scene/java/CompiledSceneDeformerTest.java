package cc.sirrus.ysmlib.scene.java;

import cc.sirrus.ysmlib.scene.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CompiledSceneDeformerTest {
  @Test void arbitraryInfluencesSparseMorphsAndReflectionsMatchReference() {
    var mesh=mesh(117,9);var compiled=new CompiledSceneDeformer(mesh);var original=mesh.attributes().get("POSITION").values().copy();
    for(int frame=0;frame<25;frame++){
      var palette=palette(9,frame);var weights=new FloatData(frame*.07f);
      var reference=new Deformer().deform(mesh,palette,weights,Deformer.NormalMode.INVERSE_TRANSPOSE);
      var actual=compiled.deform(palette,weights);
      for(String key:reference.keySet())assertArrayEquals(reference.get(key).values().copy(),actual.get(key).values().copy(),2e-5f,key+" frame "+frame);
      assertSame(actual,compiled.deform(palette,weights));
      float[] retained=actual.get("POSITION").values().copy();compiled.deform(palette(9,frame+1),weights);
      assertArrayEquals(retained,actual.get("POSITION").values().copy());
    }
    assertArrayEquals(original,mesh.attributes().get("POSITION").values().copy());
  }
  @Test void singularBlendAndZeroWeightUnusedJointsKeepReferenceSemantics() {
    var source=mesh(1,2);var skin=new MeshAsset.Skinning(new IntData(0,3),new IntData(0,1,-1),new FloatData(.5f,.5f,0),List.of(MeshAsset.Deform.LINEAR),FloatData.EMPTY);
    var mesh=new MeshAsset.Primitive(source.topology(),source.attributes(),source.indices(),-1,skin,source.morphs());
    var palette=List.of(Matrix4.IDENTITY,new Transform(Vec3.ZERO,Rotation.IDENTITY,new Vec3(-1,1,1)).matrix());
    var actual=new CompiledSceneDeformer(mesh).deform(palette,new FloatData(0));
    var expected=new Deformer().deform(mesh,palette,new FloatData(0),Deformer.NormalMode.INVERSE_TRANSPOSE);
    assertEquals(expected,actual);
    assertThrows(IllegalArgumentException.class,()->new CompiledSceneDeformer(mesh).deform(List.of(),new FloatData(0)));
  }
  static List<Matrix4> palette(int count,int frame){var out=new ArrayList<Matrix4>();for(int j=0;j<count;j++)out.add(new Transform(new Vec3(j*.12f,frame*.01f,-j*.07f),Rotation.axisAngle(new Vec3(1,2,3).normalized(),.07*(frame+j)),new Vec3(j%2==0?-1.3f:1.7f,.8f+j*.02f,1.2f)).matrix());return out;}
  static MeshAsset.Primitive mesh(int count,int influences){
    var random=new Random(17);float[] p=new float[count*3],n=new float[count*3],t=new float[count*4],w=new float[count*influences];int[] offsets=new int[count+1],joints=new int[w.length],indices=new int[count];
    for(int v=0;v<count;v++){indices[v]=v;offsets[v]=v*influences;for(int k=0;k<3;k++){p[v*3+k]=random.nextFloat();n[v*3+k]=random.nextFloat()-.5f;t[v*4+k]=random.nextFloat();}t[v*4+3]=v%2==0?1:-1;for(int k=0;k<influences;k++){joints[v*influences+k]=k;w[v*influences+k]=(.2f+random.nextFloat())/influences;}}
    offsets[count]=w.length;
    return new MeshAsset.Primitive(MeshAsset.Topology.POINTS,Map.of("POSITION",new MeshAsset.Attribute(3,new FloatData(p)),"NORMAL",new MeshAsset.Attribute(3,new FloatData(n)),"TANGENT",new MeshAsset.Attribute(4,new FloatData(t))),new IntData(indices),-1,
      new MeshAsset.Skinning(new IntData(offsets),new IntData(joints),new FloatData(w),Collections.nCopies(count,MeshAsset.Deform.LINEAR),FloatData.EMPTY),
      List.of(new MeshAsset.MorphTarget("sparse",Map.of("POSITION",new MeshAsset.Attribute(3,new FloatData(.1f,.2f,-.3f,.3f,-.2f,.1f))),new IntData(0,count-1))));
  }
}
