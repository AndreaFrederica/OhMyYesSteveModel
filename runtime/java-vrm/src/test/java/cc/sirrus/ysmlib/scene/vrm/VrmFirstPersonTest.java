package cc.sirrus.ysmlib.scene.vrm;

import cc.sirrus.ysmlib.scene.*;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static cc.sirrus.ysmlib.scene.vrm.VrmFixtures.*;

class VrmFirstPersonTest {
  @Test void allInfluencesAndInstanceSkinMappingsDetermineVisibilityWithoutChangingSource() {
    var d=document(MeshAsset.Topology.TRIANGLES,9,Set.of(1),List.of(),1);
    var views=new VrmFirstPerson().compile(d);var source=d.scene().meshes().get(0).primitives().get(0);
    var first=view(views,2).firstPerson().get(0).geometry();
    assertArrayEquals(new int[]{3,4,5,6,7,8},first.indices().copy());
    assertSame(source.skinning(),first.skinning());assertSame(source.attributes().get("POSITION"),first.attributes().get("POSITION"));
    assertSame(source.morphs().get(0),first.morphs().get(0));assertEquals(source.material(),first.material());
    assertSame(source,view(views,2).thirdPerson().get(0).geometry());
    // The same mesh on a different skin maps joint 1 to the body instead of the head subtree.
    assertSame(source,view(views,6).firstPerson().get(0).geometry());
    assertEquals(9,source.indices().size());assertEquals(6,source.skinning().offsets().get(1));
    assertThrows(UnsupportedOperationException.class,()->views.nodes().clear());
  }
  @Test void defaultsHideRigidHeadDescendantsAndAnnotationsOverridePerNode() {
    var d=document(MeshAsset.Topology.TRIANGLES,3,Set.of(0),List.of(),1);
    var automatic=new VrmFirstPerson().compile(d);
    assertTrue(view(automatic,2).firstPerson().isEmpty());assertTrue(view(automatic,5).firstPerson().isEmpty());
    assertEquals(1,view(automatic,4).firstPerson().size());assertEquals(1,view(automatic,5).thirdPerson().size());
    var annotations=List.of(new VrmDocument.MeshAnnotation(2,"both"),new VrmDocument.MeshAnnotation(4,"thirdPersonOnly"),new VrmDocument.MeshAnnotation(5,"firstPersonOnly"));
    var explicit=new VrmFirstPerson().compile(document(MeshAsset.Topology.TRIANGLES,3,Set.of(0),annotations,1));
    assertEquals(1,view(explicit,2).firstPerson().size());assertTrue(view(explicit,4).firstPerson().isEmpty());
    assertEquals(1,view(explicit,5).firstPerson().size());assertTrue(view(explicit,5).thirdPerson().isEmpty());
    // VRM 0 may specify a different erase root. Choosing the hips hides all rigid descendants.
    assertTrue(view(new VrmFirstPerson().compile(document(MeshAsset.Topology.TRIANGLES,3,Set.of(),List.of(),0)),4).firstPerson().isEmpty());
  }
  @Test void stripsFansAndLinesPreservePrimitiveBoundariesAndWinding() {
    check(MeshAsset.Topology.TRIANGLE_STRIP,6,Set.of(0),MeshAsset.Topology.TRIANGLES,2,1,3,2,3,4,4,3,5);
    check(MeshAsset.Topology.TRIANGLE_FAN,6,Set.of(2),MeshAsset.Topology.TRIANGLES,0,3,4,0,4,5);
    check(MeshAsset.Topology.LINE_STRIP,6,Set.of(2),MeshAsset.Topology.LINES,0,1,3,4,4,5);
    check(MeshAsset.Topology.LINE_LOOP,6,Set.of(2),MeshAsset.Topology.LINES,0,1,3,4,4,5,5,0);
    check(MeshAsset.Topology.LINES,6,Set.of(2),MeshAsset.Topology.LINES,0,1,4,5);
    check(MeshAsset.Topology.POINTS,6,Set.of(2),MeshAsset.Topology.POINTS,0,1,3,4,5);
  }
  @Test void matchesUnmodifiedThreeVrmFirstPersonOutput() throws Exception {
    var input=resource("first-person-avatar.gltf").getAsJsonObject();var d=read(input);
    var expected=resource("first-person.json").getAsJsonObject();var result=new VrmFirstPerson().compile(d);
    for(var node:result.nodes()) {
      var e=expected.getAsJsonObject(d.scene().nodes().get(node.node()).name());assertNotNull(e);
      compare(e.getAsJsonArray("firstPerson"),node.firstPerson());compare(e.getAsJsonArray("thirdPerson"),node.thirdPerson());
    }
  }
  private static void compare(JsonArray expected,List<VrmViewGeometry.Draw> actual) {
    assertEquals(expected.size(),actual.size());for(int i=0;i<expected.size();i++) {
      var a=expected.get(i).getAsJsonArray();int[] indices=new int[a.size()];for(int j=0;j<indices.length;j++) indices[j]=a.get(j).getAsInt();
      assertArrayEquals(indices,actual.get(i).geometry().indices().copy());
    }
  }
  private static VrmViewGeometry.NodeView view(VrmViewGeometry geometry,int node) { return geometry.nodes().stream().filter(v->v.node()==node).findFirst().orElseThrow(); }
  private static void check(MeshAsset.Topology topology,int count,Set<Integer> hidden,MeshAsset.Topology expected,int... indices) {
    var d=document(topology,count,hidden,List.of(),1);var p=view(new VrmFirstPerson().compile(d),2).firstPerson().get(0).geometry();
    assertEquals(expected,p.topology());assertArrayEquals(indices,p.indices().copy());
  }
  private static VrmDocument document(MeshAsset.Topology topology,int count,Set<Integer> hidden,List<VrmDocument.MeshAnnotation> annotations,int eraseRoot) {
    int[] offsets=new int[count+1],joints=new int[count*6],indices=new int[count];float[] weights=new float[count*6];
    for(int v=0;v<count;v++) { indices[v]=v;offsets[v]=6*v;weights[6*v]=1;joints[6*v+1]=2; // zero head weight must not hide geometry
      joints[6*v+5]=1;weights[6*v+5]=hidden.contains(v)?1e-7f:0; }
    offsets[count]=6*count;var positions=new MeshAsset.Attribute(3,new FloatData(new float[3*count]));
    var mesh=new MeshAsset("shared",List.of(new MeshAsset.Primitive(topology,Map.of("POSITION",positions),new IntData(indices),-1,
        new MeshAsset.Skinning(new IntData(offsets),new IntData(joints),new FloatData(weights),Collections.nCopies(count,MeshAsset.Deform.LINEAR),FloatData.EMPTY),
        List.of(new MeshAsset.MorphTarget("retained",Map.of("POSITION",positions))))),new FloatData(0));
    var nodes=List.of(node(new int[]{1,2,4,6},-1,-1),node(new int[]{3,5},-1,-1),node(new int[]{},0,0),node(new int[]{},-1,-1),
        node(new int[]{},0,-1),node(new int[]{},0,-1),node(new int[]{},0,1));
    var skins=List.of(new SceneAsset.Skin("head",new IntData(0,3,1),Collections.nCopies(3,Matrix4.IDENTITY),0,Map.of()),
        new SceneAsset.Skin("body",new IntData(0,4,1),Collections.nCopies(3,Matrix4.IDENTITY),0,Map.of()));
    var scene=new SceneAsset("views",SceneAsset.Coordinates.GLTF,nodes,List.of(new SceneAsset.Scene("",new IntData(0),Map.of())),0,
        List.of(mesh),skins,List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),Map.of(),COVERAGE);
    return new VrmDocument(new GltfDocument(scene,new ByteData(new byte[0]),Map.of()),VrmDocument.Version.VRM_1,Map.of("head",1),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),
        new VrmDocument.FirstPerson(eraseRoot,Vec3.ZERO,annotations),null,List.of(),COVERAGE);
  }
  private static SceneAsset.Node node(int[] children,int mesh,int skin) { return new SceneAsset.Node("",new IntData(children),Transform.IDENTITY,null,mesh,skin,-1,-1,FloatData.EMPTY,Map.of()); }
}
