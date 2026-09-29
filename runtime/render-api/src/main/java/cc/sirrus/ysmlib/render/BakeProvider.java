package cc.sirrus.ysmlib.render;

import java.io.IOException;
import java.nio.ByteBuffer;

public interface BakeProvider {
  String profile();

  BakedModel bake(
      Geometry.Model source, ByteBuffer rgba, int width, int height, Geometry.Options options)
      throws IOException;

  byte[] encode(BakedModel model) throws IOException;

  BakedModel decode(ByteBuffer payload) throws IOException;
}
