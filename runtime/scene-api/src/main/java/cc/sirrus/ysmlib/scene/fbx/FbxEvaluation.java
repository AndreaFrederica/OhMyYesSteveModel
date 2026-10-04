package cc.sirrus.ysmlib.scene.fbx;

import cc.sirrus.ysmlib.scene.*;
import java.util.*;

/** Stateful resource ownership, deterministic time sampling. Frames own immutable data after a session closes. */
public interface FbxEvaluation extends AutoCloseable {
  /** FBX SDK world binding and DCC mesh-motion compensation are distinct source workflows. */
  enum SkinSpace { BIND_WORLD, FOLLOW_MESH_NODE }
  record Settings(SkinSpace skinSpace) {
    public static final Settings DEFAULT=new Settings(SkinSpace.BIND_WORLD);
    public Settings { Objects.requireNonNull(skinSpace); }
  }
  record MeshFrame(DoubleData positions,DoubleData normals,boolean geometryLocal) {}
  record UvDirections(DoubleData tangents,DoubleData bitangents) {}
  /** Geometry is in converted world space. Every mesh instance has its own fallback transform and material mapping. */
  record DrawFrame(int node,int mesh,DoubleData positions,DoubleData normals,List<UvDirections> uvDirections) {
    public DrawFrame { uvDirections=List.copyOf(uvDirections); }
  }
  record Frame(double seconds,List<FbxDocument.Node> nodes,List<MeshFrame> meshes,List<FbxDocument.Element> elements,
      FbxDetails.State details,List<FbxDocument.Texture> textures,List<DrawFrame> draws,CompatibilityReport compatibility) {
    public Frame { nodes=List.copyOf(nodes);meshes=List.copyOf(meshes);elements=List.copyOf(elements);textures=List.copyOf(textures);draws=List.copyOf(draws);Objects.requireNonNull(details);Objects.requireNonNull(compatibility); }
  }
  /** Stack -1 uses the file's default layered animation. Source time can be negative for pre-roll. */
  Frame evaluate(int stack,double seconds);
  @Override void close();
}
