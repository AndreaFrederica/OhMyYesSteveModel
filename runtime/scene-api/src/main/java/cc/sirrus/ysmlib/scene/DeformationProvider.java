package cc.sirrus.ysmlib.scene;

import java.util.List;
import java.util.Map;

/** Immutable model input, caller-owned scratch storage, immutable published output. */
public interface DeformationProvider {
  String id();
  default boolean deferred() { return false; }
  Session compile(MeshAsset.Primitive mesh);
  interface Session extends AutoCloseable {
    Map<String,MeshAsset.Attribute> deform(List<Matrix4> palette, FloatData weights, SceneProvider.NormalPolicy policy);
    @Override default void close() {}
  }
}
