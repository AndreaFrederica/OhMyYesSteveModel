package cc.sirrus.ysmlib.scene.fbx;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.*;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FbxReactorTest {
  @Test void readsAsciiAndBinaryWithoutNativeAndPreservesFaceCorners() throws Exception {
    try(var a=new FbxReactor(sample("maya_cube_7500_ascii.fbx"),ReadLimits.DEFAULT);
        var b=new FbxReactor(sample("maya_cube_7500_binary.fbx"),ReadLimits.DEFAULT)) {
      var left=a.source();var right=b.source();assertTrue(left.get("ascii").getAsBoolean());assertFalse(right.get("ascii").getAsBoolean());
      assertEquals(7500,left.get("version").getAsInt());var mesh=left.getAsJsonArray("meshes").get(0).getAsJsonObject();
      assertEquals(24,mesh.getAsJsonArray("position").size());assertEquals(6,mesh.getAsJsonArray("faces").size());
      assertEquals(24,mesh.getAsJsonArray("controlVertices").size());
      assertEquals(mesh.get("position"),right.getAsJsonArray("meshes").get(0).getAsJsonObject().get("position"));
      for(var face:mesh.getAsJsonArray("faces")) assertEquals(6,face.getAsJsonObject().getAsJsonArray("triangles").size());
      assertFalse(left.getAsJsonArray("elements").isEmpty());assertFalse(mesh.getAsJsonArray("uvSets").isEmpty());
    }
  }
  @Test void sourceCurvesAndLayersRemainDeterministicAcrossTimeJumps() throws Exception {
    String name="maya_anim_layers_7500_binary.fbx";try(var reactor=new FbxReactor(sample(name),ReadLimits.DEFAULT)) {
      var source=reactor.source();assertFalse(source.getAsJsonArray("animations").isEmpty());
      assertFalse(source.getAsJsonArray("curves").isEmpty());assertFalse(source.getAsJsonArray("animationLayers").isEmpty());
      var start=reactor.evaluate(0,0);var middle=reactor.evaluate(0,.5);reactor.evaluate(0,1);
      assertEquals(middle,reactor.evaluate(0,.5));assertEquals(source,reactor.source());
      assertNotEquals(start.get("nodes"),middle.get("nodes"),name);
      assertThrows(AssetFormatException.class,()->reactor.evaluate(99999,0));
      assertEquals(middle,reactor.evaluate(0,.5));
    }
  }
  @Test void staticPivotMatchesOriginalUfbxReferenceTranslation() throws Exception {
    try(var reactor=new FbxReactor(sample("maya_pivots_7500_binary.fbx"),ReadLimits.DEFAULT)) {
      var source=reactor.source();var elements=source.getAsJsonArray("elements");int id=-1;
      for(var e:elements) if(e.getAsJsonObject().get("name").getAsString().equals("pCube1")) { id=e.getAsJsonObject().get("id").getAsInt();break; }
      assertTrue(id>=0);JsonArray local=null;
      for(var n:source.getAsJsonArray("nodes")) if(n.getAsJsonObject().get("element").getAsInt()==id) local=n.getAsJsonObject().getAsJsonArray("local");
      assertNotNull(local);
      // Frozen expected values from upstream test/test_transform.h, UFBXT_FILE_TEST(maya_pivots).
      double[] expected={.7211236250,1.8317762500,-.6038020000};for(int k=0;k<3;k++) assertEquals(expected[k],local.get(9+k).getAsDouble(),1e-6);
      assertEquals(reactor.evaluate(0,0).get("nodes"),reactor.evaluate(0,1).get("nodes"));
    }
  }
  @Test void malformedInputsBudgetsAndClosedSessionsFailExplicitly() throws Exception {
    assertThrows(AssetFormatException.class,()->new FbxReactor(new ByteData(new byte[]{1,2,3}),ReadLimits.DEFAULT));
    var source=sample("maya_cube_7500_binary.fbx");
    assertThrows(AssetFormatException.class,()->new FbxReactor(source,new ReadLimits(1024,10,128)));
    assertThrows(AssetFormatException.class,()->new FbxReactor(source,new ReadLimits(1024*1024,2,1024)));
    var reactor=new FbxReactor(source,ReadLimits.DEFAULT);reactor.close();reactor.close();
    assertThrows(IllegalStateException.class,reactor::source);
  }
  private static ByteData sample(String name) throws Exception {
    try(var input=FbxReactorTest.class.getResourceAsStream("/ufbx/"+name)) { return new ByteData(Objects.requireNonNull(input).readAllBytes()); }
  }
}
