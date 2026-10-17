package cc.sirrus.ysmlib.scene;

import java.util.*;

/** Shared draw geometry boundary. Source documents and format-specific material data remain owned by the asset. */
public record GeometryFrame(double seconds,SceneAsset.Coordinates coordinates,List<Draw> draws) {
  public record Draw(int node,int mesh,Matrix4 world,MeshAsset geometry,boolean visible,IntData sourcePrimitives) {
    public Draw(int node,int mesh,Matrix4 world,MeshAsset geometry,boolean visible){this(node,mesh,world,geometry,visible,IntData.EMPTY);}
    public Draw { Objects.requireNonNull(world);Objects.requireNonNull(geometry);Objects.requireNonNull(sourcePrimitives);if(sourcePrimitives.size()!=0&&sourcePrimitives.size()!=geometry.primitives().size())throw new IllegalArgumentException("Primitive provenance size mismatch"); }
    public int sourcePrimitive(int ordinal){return sourcePrimitives.size()==0?ordinal:sourcePrimitives.get(ordinal);}
  }
  public GeometryFrame { if(!Double.isFinite(seconds)) throw new IllegalArgumentException("Non-finite frame time");Objects.requireNonNull(coordinates);draws=List.copyOf(draws); }
}
