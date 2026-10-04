package cc.sirrus.ysmlib.scene;

import java.util.*;

/** Shared draw geometry boundary. Source documents and format-specific material data remain owned by the asset. */
public record GeometryFrame(double seconds,SceneAsset.Coordinates coordinates,List<Draw> draws) {
  public record Draw(int node,int mesh,Matrix4 world,MeshAsset geometry,boolean visible) {
    public Draw { Objects.requireNonNull(world);Objects.requireNonNull(geometry); }
  }
  public GeometryFrame { if(!Double.isFinite(seconds)) throw new IllegalArgumentException("Non-finite frame time");Objects.requireNonNull(coordinates);draws=List.copyOf(draws); }
}
