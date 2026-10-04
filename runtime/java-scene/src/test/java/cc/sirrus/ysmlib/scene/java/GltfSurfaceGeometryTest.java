package cc.sirrus.ysmlib.scene.java;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GltfSurfaceGeometryTest {
  @Test void mirroredSharedVertexKeepsSeparateCornerFramesAndAllInfluences() {
    var attributes=attributes();
    attributes.put("NORMAL",a(3,0,0,1,0,0,1,0,0,1,0,0,1));
    attributes.put("TEXCOORD_7",a(2,0,0,1,0,0,1,1,0));
    attributes.put("_EXTRA",a(1,10,20,30,40));
    int[] offsets={0,6,12,18,24};int[] joints=new int[24];float[] weights=new float[24];
    for(int i=0;i<24;i++) { joints[i]=i%6;weights[i]=1f/6; }
    var skin=new MeshAsset.Skinning(new IntData(offsets),new IntData(joints),new FloatData(weights),Collections.nCopies(4,MeshAsset.Deform.LINEAR),FloatData.EMPTY);
    var source=new MeshAsset.Primitive(MeshAsset.Topology.TRIANGLES,attributes,new IntData(0,1,2,0,2,3),4,skin,List.of());
    var out=new GltfSurfaceGeometry().prepare(source,7,ReadLimits.DEFAULT);
    assertEquals(6,out.vertexCount());assertEquals(4,out.material());
    var t=out.attributes().get("TANGENT").values();assertEquals(1,t.get(3));assertEquals(-1,t.get(15));
    assertArrayEquals(new float[]{10,20,30,10,30,40},out.attributes().get("_EXTRA").values().copy());
    assertEquals(36,out.skinning().joints().size());assertEquals(6,out.skinning().offsets().get(1));
    assertFalse(source.attributes().containsKey("TANGENT"));
  }
  @Test void flatMorphNormalsAndTangentsAreGeneratedBeforeWeightedDeformation() {
    var base=attributes();base.put("TANGENT",a(4,9,8,7,-1,9,8,7,-1,9,8,7,-1,9,8,7,-1));
    base.put("TEXCOORD_4",a(2,0,0,1,0,0,1,-1,0));
    var morph=new MeshAsset.MorphTarget("bend",Map.of("POSITION",a(3,0,0,1,0,0,1)),new IntData(1,1));
    var source=new MeshAsset.Primitive(MeshAsset.Topology.TRIANGLES,base,new IntData(0,1,2,0,2,3),-1,null,List.of(morph));
    var out=new GltfSurfaceGeometry().prepare(source,4,ReadLimits.DEFAULT);
    assertEquals(3,out.morphs().get(0).attributes().get("TANGENT").components());
    var evaluated=new Deformer().deform(out,List.of(),new FloatData(1),Deformer.NormalMode.INVERSE_TRANSPOSE);
    assertArrayEquals(new float[]{(float)(-2/Math.sqrt(5)),0,(float)(1/Math.sqrt(5))},Arrays.copyOf(evaluated.get("NORMAL").values().copy(),3),1e-6f);
    assertEquals(2,evaluated.get("POSITION").values().get(5));
    assertEquals(1,evaluated.get("TANGENT").values().get(3));
    assertEquals(9,source.attributes().get("TANGENT").values().get(0));
    assertThrows(IllegalArgumentException.class,()->new GltfSurfaceGeometry().prepare(source,4,new ReadLimits(100,30,8)));
  }
  @Test void stripAndFanWindingAndAuthoredFrames() {
    var strip=new MeshAsset.Primitive(MeshAsset.Topology.TRIANGLE_STRIP,Map.of("POSITION",a(3,0,0,0,1,0,0,0,1,0,1,1,0)),new IntData(0,1,2,3),-1,null,List.of());
    var out=new GltfSurfaceGeometry().prepare(strip,-1,ReadLimits.DEFAULT);
    assertEquals(6,out.vertexCount());for(int i=2;i<18;i+=3) assertEquals(1,out.attributes().get("NORMAL").values().get(i));
    var fan=new MeshAsset.Primitive(MeshAsset.Topology.TRIANGLE_FAN,attributes(),new IntData(0,1,2,3),-1,null,List.of());
    out=new GltfSurfaceGeometry().prepare(fan,-1,ReadLimits.DEFAULT);
    for(int i=2;i<18;i+=3) assertEquals(1,out.attributes().get("NORMAL").values().get(i));
    var authored=new HashMap<>(strip.attributes());authored.put("NORMAL",a(3,0,0,1,0,0,1,0,0,1,0,0,1));authored.put("TANGENT",a(4,1,0,0,1,1,0,0,1,1,0,0,1,1,0,0,1));
    var same=new MeshAsset.Primitive(strip.topology(),authored,strip.indices(),-1,null,List.of());
    assertSame(same,new GltfSurfaceGeometry().prepare(same,4,ReadLimits.DEFAULT));
  }
  @Test void pointsAndLinesDoNotInventSurfaceFrames() {
    var source=new MeshAsset.Primitive(MeshAsset.Topology.LINES,attributes(),new IntData(0,1),-1,null,List.of());
    assertSame(source,new GltfSurfaceGeometry().prepare(source,8,ReadLimits.DEFAULT));
    assertThrows(IllegalArgumentException.class,()->new GltfSurfaceGeometry().prepare(new MeshAsset.Primitive(MeshAsset.Topology.TRIANGLES,attributes(),new IntData(0,1,2),-1,null,List.of()),8,ReadLimits.DEFAULT));
  }
  private static Map<String,MeshAsset.Attribute> attributes() { var values=new HashMap<String,MeshAsset.Attribute>();values.put("POSITION",a(3,0,0,0,1,0,0,0,1,0,-1,0,0));return values; }
  private static MeshAsset.Attribute a(int components,float... values) { return new MeshAsset.Attribute(components,new FloatData(values)); }
}
