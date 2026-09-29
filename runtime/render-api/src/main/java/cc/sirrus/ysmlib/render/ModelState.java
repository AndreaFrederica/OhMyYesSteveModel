package cc.sirrus.ysmlib.render;

import java.util.List;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/** Provider-neutral per-entity state. Each successful extraction owns its snapshot. */
public interface ModelState extends AutoCloseable {
  int ATTRIBUTE_COUNT = 14;

  boolean extract(BakedModel model, float[] attributes);

  void invalidate();

  long generation();

  boolean valid();

  BakedModel model();

  List<Integer> renderBones();

  List<Integer> locators();

  int vertices();

  int translucentVertices();

  Matrix4f pose(int bone, Matrix4f destination);

  Matrix3f normal(int bone, Matrix3f destination);

  int color(int bone);

  int glow(int bone);

  boolean uniform(int bone);

  float orientation(int bone);

  float normalScale(int bone);

  @Override
  void close();
}
