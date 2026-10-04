package cc.sirrus.ysmlib.scene.java;

import cc.sirrus.ysmlib.scene.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SceneGeometryTest {
  @Test void rigidAndSkinnedInstancesShareSourceWithoutSharingADeformationOrSelection() {
    var scene=scene();var pose=new SceneEvaluator(scene).evaluate(null,0);
    var rigged=new SceneGeometryCompiler(scene,0).compile(pose);var rigid=new SceneGeometryCompiler(scene,1).compile(pose);
    assertEquals(1,rigged.draws().size());assertEquals(1,rigid.draws().size());
    assertPoint(rigged.draws().get(0),new Vec3(1,.5f,2));assertPoint(rigid.draws().get(0),new Vec3(11,1,0));
    assertNull(rigged.draws().get(0).geometry().primitives().get(0).skinning());
    assertEquals(new FloatData(1,0,0),scene.meshes().get(0).primitives().get(0).attributes().get("POSITION").values());
    assertEquals(2,new SceneGeometryCompiler(scene,-1).compile(pose).draws().size());
    assertTrue(new SceneGeometryCompiler(scene,0,Map.of(1,List.of())).compile(pose).draws().isEmpty());
    assertPoint(new SceneGeometryCompiler(scene,1,Map.of(1,List.of())).compile(pose).draws().get(0),new Vec3(11,1,0));
    assertEquals(rigged,new SceneGeometryCompiler(scene,0).compile(pose));
  }
  private static void assertPoint(GeometryFrame.Draw draw,Vec3 expected) {
    var p=draw.geometry().primitives().get(0).attributes().get("POSITION").values();
    assertEquals(expected,draw.world().transformPoint(new Vec3(p.get(0),p.get(1),p.get(2))));
  }
  private static SceneAsset scene() {
    var skin=new MeshAsset.Skinning(new IntData(0,1),new IntData(0),new FloatData(1),List.of(MeshAsset.Deform.LINEAR),FloatData.EMPTY);
    var primitive=new MeshAsset.Primitive(MeshAsset.Topology.POINTS,Map.of("POSITION",new MeshAsset.Attribute(3,new FloatData(1,0,0))),new IntData(0),-1,skin,
        List.of(new MeshAsset.MorphTarget("lift",Map.of("POSITION",new MeshAsset.Attribute(3,new FloatData(0,1,0))))));
    var nodes=List.of(node("rigid",new Vec3(10,0,0),0,-1,1),node("skin",new Vec3(3,0,0),0,0,.5f),node("joint",new Vec3(0,0,2),-1,-1,0));
    return new SceneAsset("shared",SceneAsset.Coordinates.GLTF,nodes,
        List.of(new SceneAsset.Scene("skin",new IntData(1,2),Map.of()),new SceneAsset.Scene("rigid",new IntData(0),Map.of())),0,
        List.of(new MeshAsset("mesh",List.of(primitive),new FloatData(0))),
        List.of(new SceneAsset.Skin("bones",new IntData(2),List.of(Matrix4.IDENTITY),2,Map.of())),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),Map.of(),new CompatibilityReport(List.of(),List.of()));
  }
  private static SceneAsset.Node node(String name,Vec3 translation,int mesh,int skin,float weight) {
    return new SceneAsset.Node(name,IntData.EMPTY,new Transform(translation,Rotation.IDENTITY,new Vec3(1,1,1)),null,mesh,skin,-1,-1,mesh<0?FloatData.EMPTY:new FloatData(weight),Map.of());
  }
}
