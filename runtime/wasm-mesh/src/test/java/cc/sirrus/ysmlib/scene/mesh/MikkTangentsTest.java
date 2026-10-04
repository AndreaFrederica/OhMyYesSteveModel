package cc.sirrus.ysmlib.scene.mesh;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.*;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class MikkTangentsTest {
  @Test void matchesUnmodifiedBlenderLoopOutput() throws Exception {
    try(var reader=new InputStreamReader(getClass().getResourceAsStream("/blender-tangents/corners.json"),StandardCharsets.UTF_8)) {
      for(var item:JsonParser.parseReader(reader).getAsJsonArray()) {
        var fixture=item.getAsJsonObject();var output=new MikkTangents().generate(data(fixture,"position"),data(fixture,"normal"),data(fixture,"uv"),ReadLimits.DEFAULT);
        assertArrayEquals(data(fixture,"tangent").copy(),output.copy(),2e-5f,fixture.get("name").getAsString());
      }
    }
  }
  @Test void scratchBudgetAndIndependentRetry() {
    var positions=new FloatData(0,0,0,1,0,0,0,1,0);var normals=new FloatData(0,0,1,0,0,1,0,0,1);var uv=new FloatData(0,0,1,0,0,1);
    var generator=new MikkTangents();
    assertThrows(IllegalArgumentException.class,()->generator.generate(positions,normals,uv,new ReadLimits(160,100,8)));
    assertArrayEquals(new float[]{1,0,0,1,1,0,0,1,1,0,0,1},generator.generate(positions,normals,uv,ReadLimits.DEFAULT).copy(),1e-6f);
  }
  private static FloatData data(JsonObject value,String field) {
    var array=value.getAsJsonArray(field);float[] result=new float[array.size()];for(int i=0;i<result.length;i++) result[i]=array.get(i).getAsFloat();return new FloatData(result);
  }
}
