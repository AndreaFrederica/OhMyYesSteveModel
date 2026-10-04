package cc.sirrus.ysmlib.scene.natives;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.java.*;
import cc.sirrus.ysmlib.scene.mmd.*;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import java.nio.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeDeformationTest {
  static NativeDeformationProvider nativeProvider;
  @BeforeAll static void load(){String path=System.getProperty("ysm.native.skinning.library");
    Assumptions.assumeTrue(path!=null,"Native tests require explicit library");nativeProvider=new NativeDeformationProvider(Path.of(path));}
  static void compare(Map<String,MeshAsset.Attribute> a,Map<String,MeshAsset.Attribute> b){
    assertEquals(a.keySet(),b.keySet());for(var name:a.keySet()){var x=a.get(name);var y=b.get(name);assertEquals(x.components(),y.components());
      for(int i=0;i<x.values().size();i++)assertEquals(x.values().get(i),y.values().get(i),2e-5,"attribute="+name+" scalar="+i);}}
  @Test void linearSdefQdefMorphTangentAndRetainedFrames(){
    var modes=List.of(MeshAsset.Deform.LINEAR,MeshAsset.Deform.SDEF,MeshAsset.Deform.QDEF);
    var skin=new MeshAsset.Skinning(new IntData(0,2,4,8),new IntData(0,1,0,1,0,1,0,1),new FloatData(.3f,.7f,.4f,.6f,.2f,.3f,.1f,.4f),modes,
        new FloatData(0,0,0,0,0,0,0,0,0, .3f,.2f,.1f,.1f,.4f,.2f,.5f,-.1f,.3f, 0,0,0,0,0,0,0,0,0));
    var attrs=Map.of("POSITION",new MeshAsset.Attribute(3,new FloatData(1,2,3,-1,.2f,2,4,3,-2)),"NORMAL",new MeshAsset.Attribute(3,new FloatData(0,1,0,0,1,0,0,1,0)),
        "TANGENT",new MeshAsset.Attribute(4,new FloatData(1,0,0,1,1,0,0,-1,1,0,0,1)),"TEXCOORD_0",new MeshAsset.Attribute(2,new FloatData(new float[6])));
    var morph=new MeshAsset.MorphTarget("sparse",Map.of("POSITION",new MeshAsset.Attribute(3,new FloatData(.1f,.2f,.3f,.3f,-.2f,.1f))),new IntData(1,1));
    var mesh=new MeshAsset.Primitive(MeshAsset.Topology.POINTS,attrs,new IntData(0,1,2),-1,skin,List.of(morph));
    var javaProvider=new JavaDeformationProvider();try(var a=javaProvider.compile(mesh);var b=nativeProvider.compile(mesh)){
      Map<String,MeshAsset.Attribute> saved=null;float[] old=null;
      for(int frame=0;frame<40;frame++){var palette=List.of(new Transform(new Vec3(.1f,0,0),Rotation.axisAngle(Vec3.ONE.normalized(),frame*.03),Vec3.ONE).matrix(),
          new Transform(new Vec3(0,.2f,0),Rotation.axisAngle(new Vec3(0,1,0),-frame*.04),Vec3.ONE).matrix());
        for(var policy:SceneProvider.NormalPolicy.values()){var expected=a.deform(palette,new FloatData(.7f),policy);var actual=b.deform(palette,new FloatData(.7f),policy);compare(expected,actual);
          if(saved==null){saved=actual;old=actual.get("POSITION").values().copy();}else assertArrayEquals(old,saved.get("POSITION").values().copy());}
      }
    }
  }
  @Test void linearReflectionNonuniformScaleAndUnboundedInfluences(){
    var skin=new MeshAsset.Skinning(new IntData(0,6),new IntData(0,0,0,0,0,0),new FloatData(.1f,.1f,.1f,.2f,.2f,.3f),List.of(MeshAsset.Deform.LINEAR),FloatData.EMPTY);
    var mesh=new MeshAsset.Primitive(MeshAsset.Topology.POINTS,Map.of("POSITION",new MeshAsset.Attribute(3,new FloatData(1,2,3)),"NORMAL",new MeshAsset.Attribute(3,new FloatData(1,1,1)),
        "TANGENT",new MeshAsset.Attribute(4,new FloatData(1,0,0,-1))),new IntData(0),-1,skin,List.of());
    var palette=List.of(new Transform(new Vec3(1,2,3),Rotation.IDENTITY,new Vec3(-2,3,4)).matrix());
    try(var a=new JavaDeformationProvider().compile(mesh);var b=nativeProvider.compile(mesh)){for(var policy:SceneProvider.NormalPolicy.values())compare(a.deform(palette,FloatData.EMPTY,policy),b.deform(palette,FloatData.EMPTY,policy));}
  }
  @Test void sourcePmxFixturesAndParallelOutputEqualScalar() throws Exception {
    for(String file:new String[]{"skin.pmx","morph.pmx"})try(var input=getClass().getResourceAsStream("/mmd-oracle/"+file)){
      var model=new PmxReader().read(new ByteData(input.readAllBytes()),ReadLimits.DEFAULT);var mesh=new MmdMeshCompiler().compile(model).primitives().get(0);
      var palette=Collections.nCopies(model.bones().size(),Matrix4.IDENTITY);var weights=new FloatData(new float[mesh.morphs().size()]);
      try(var a=new JavaDeformationProvider().compile(mesh);var b=nativeProvider.compile(mesh)){compare(a.deform(palette,weights,SceneProvider.NormalPolicy.MMD_WEIGHTED_ROTATION),b.deform(palette,weights,SceneProvider.NormalPolicy.MMD_WEIGHTED_ROTATION));}
    }
    int count=8192;int[] offsets=new int[count+1],joints=new int[count];float[] weights=new float[count],positions=new float[count*3];
    for(int i=0;i<count;i++){offsets[i]=i;weights[i]=1;positions[i*3]=i*.001f;}offsets[count]=count;
    var mesh=new MeshAsset.Primitive(MeshAsset.Topology.POINTS,Map.of("POSITION",new MeshAsset.Attribute(3,new FloatData(positions))),IntData.EMPTY,-1,
        new MeshAsset.Skinning(new IntData(offsets),new IntData(joints),new FloatData(weights),Collections.nCopies(count,MeshAsset.Deform.LINEAR),FloatData.EMPTY),List.of());
    var scalar=new NativeDeformationProvider(Path.of(System.getProperty("ysm.native.skinning.library")),false);
    try(var a=scalar.compile(mesh);var b=nativeProvider.compile(mesh)){for(int i=0;i<10;i++)assertEquals(a.deform(List.of(Matrix4.IDENTITY),FloatData.EMPTY,SceneProvider.NormalPolicy.MMD_WEIGHTED_ROTATION),b.deform(List.of(Matrix4.IDENTITY),FloatData.EMPTY,SceneProvider.NormalPolicy.MMD_WEIGHTED_ROTATION));}
  }
  @Test void rejectPaletteAndClosedSessionWithoutPublishing(){
    var skin=new MeshAsset.Skinning(new IntData(0,1),new IntData(0),new FloatData(1),List.of(MeshAsset.Deform.LINEAR),FloatData.EMPTY);
    var mesh=new MeshAsset.Primitive(MeshAsset.Topology.POINTS,Map.of("POSITION",new MeshAsset.Attribute(3,new FloatData(1,2,3))),new IntData(0),-1,skin,List.of());
    var session=nativeProvider.compile(mesh);assertThrows(IllegalArgumentException.class,()->session.deform(List.of(),FloatData.EMPTY,SceneProvider.NormalPolicy.MMD_WEIGHTED_ROTATION));
    assertEquals(new FloatData(1,2,3),session.deform(List.of(Matrix4.IDENTITY),FloatData.EMPTY,SceneProvider.NormalPolicy.MMD_WEIGHTED_ROTATION).get("POSITION").values());
    session.close();assertThrows(IllegalStateException.class,()->session.deform(List.of(Matrix4.IDENTITY),FloatData.EMPTY,SceneProvider.NormalPolicy.MMD_WEIGHTED_ROTATION));
  }
  private static IntBuffer ints(int... values){var b=ByteBuffer.allocateDirect(values.length*4).order(ByteOrder.nativeOrder()).asIntBuffer();return b.put(values).flip();}
  private static FloatBuffer floats(float... values){var b=ByteBuffer.allocateDirect(values.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();return b.put(values).flip();}
  @Test void jniRejectsLateInvalidCapacityAlignmentAndNumericOverflowAtomically(){
    var r=ints(0,1,2);var j=ints(0,0);var w=floats(1,1);var modes=ints(0,0);var s=floats();var p=floats(Matrix4.IDENTITY.copy());
    var in=floats(new float[20]);var out=floats(new float[20]);for(int i=0;i<20;i++)out.put(i,-123);
    j.put(1,9);assertEquals(-1,NativeDeformationProvider.nSkin(r,j,w,modes,s,p,in,out,0,true));for(int i=0;i<20;i++)assertEquals(-123,out.get(i));
    j.put(1,0);assertEquals(-1,NativeDeformationProvider.nSkin(r,j,w,modes,s,p,in,floats(new float[19]),0,true));
    var bytes=ByteBuffer.allocateDirect(13).order(ByteOrder.nativeOrder());bytes.position(1);var unaligned=bytes.slice().order(ByteOrder.nativeOrder()).asIntBuffer();
    assertEquals(-1,NativeDeformationProvider.nSkin(unaligned,j,w,modes,s,p,in,out,0,true));
    p.put(0,Float.MAX_VALUE);in.put(0,Float.MAX_VALUE);assertEquals(-2,NativeDeformationProvider.nSkin(r,j,w,modes,s,p,in,out,0,true));
    for(int i=0;i<20;i++)assertEquals(-123,out.get(i));assertEquals(0,r.position());assertEquals(0,out.position());
  }
}
