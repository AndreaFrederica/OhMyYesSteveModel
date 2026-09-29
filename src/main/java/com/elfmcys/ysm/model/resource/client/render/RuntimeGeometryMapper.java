package com.elfmcys.ysm.model.resource.client.render;

import cc.sirrus.ysmlib.render.Geometry;
import com.elfmcys.ysm.proto.mixel.asset.model.data.Cubes;
import com.elfmcys.ysm.proto.mixel.asset.model.data.GeoModel;
import java.io.IOException;
import java.util.ArrayList;
import us.hebi.quickbuf.ProtoSource;

/** The only protobuf-to-renderer boundary; the prerequisite never depends on host schemas. */
public final class RuntimeGeometryMapper {
  private RuntimeGeometryMapper() {}

  public static Geometry.Model map(GeoModel model) throws IOException {
    var cubes = Cubes.parseFrom(ProtoSource.newInstance(model.cubes())).cubesLegacy();
    var bones = new ArrayList<Geometry.Bone>();
    int offset = 0;
    for (var bone : model.bones()) {
      if (bone.pivot().size() != 3 || bone.rotate().size() != 3)
        throw new IOException("Invalid bone vectors");
      int count = bone.cubeCount().orElse(0);
      if (count < 0 || count > cubes.size() - offset)
        throw new IOException("Invalid bone cube count");
      var geometry = new ArrayList<Geometry.Cube>();
      for (int c = 0; c < count; c++) {
        var cube = cubes.get(offset++);
        int faces = cube.faceCount();
        if (faces < 0
            || faces > 6
            || cube.pos().size() % 3 != 0
            || cube.pos().size() > 24
            || cube.uv().size() % 2 != 0
            || cube.posIndices().size() != faces * 4
            || cube.uvIndices().size() != faces * 4
            || cube.normal().size() != faces * 3) throw new IOException("Invalid cube geometry");
        var positions = new ArrayList<Geometry.V3>();
        for (int i = 0; i < cube.pos().size(); i += 3)
          positions.add(
              new Geometry.V3(
                  cube.pos().getFloat(i), cube.pos().getFloat(i + 1), cube.pos().getFloat(i + 2)));
        var quads = new ArrayList<Geometry.Face>();
        for (int f = 0; f < faces; f++) {
          var indices = new ArrayList<Integer>();
          var uv = new ArrayList<Geometry.V2>();
          for (int v = 0; v < 4; v++) {
            indices.add(cube.posIndices().getInt(f * 4 + v));
            int u = cube.uvIndices().getInt(f * 4 + v);
            if (u < 0 || u >= cube.uv().size() / 2) throw new IOException("UV index out of range");
            uv.add(new Geometry.V2(cube.uv().getFloat(u * 2), cube.uv().getFloat(u * 2 + 1)));
          }
          quads.add(
              new Geometry.Face(
                  indices,
                  uv,
                  new Geometry.V3(
                      cube.normal().getFloat(f * 3),
                      cube.normal().getFloat(f * 3 + 1),
                      cube.normal().getFloat(f * 3 + 2))));
        }
        geometry.add(new Geometry.Cube(positions, quads));
      }
      bones.add(
          new Geometry.Bone(
              bone.name(),
              bone.parent().orElse(null),
              new Geometry.V3(
                  bone.pivot().getFloat(0), bone.pivot().getFloat(1), bone.pivot().getFloat(2)),
              new Geometry.V3(
                  bone.rotate().getFloat(0), bone.rotate().getFloat(1), bone.rotate().getFloat(2)),
              geometry));
    }
    if (offset != cubes.size()) throw new IOException("Unowned cubes in geometry payload");
    return new Geometry.Model(bones);
  }
}
