package cc.sirrus.ysmlib.legacy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

/** Converts historical compiled models to the versioned public model schema payloads. */
public interface LegacyImportProvider {
  record Payload(
      String kind, String encoding, int id, String name, int width, int height, ByteBuffer bytes) {
    public Payload {
      var copy = ByteBuffer.allocate(bytes.remaining());
      copy.put(bytes.duplicate()).flip();
      bytes = copy.asReadOnlyBuffer();
    }

    @Override
    public ByteBuffer bytes() {
      return bytes.asReadOnlyBuffer();
    }
  }

  record Bundle(int innerVersion, long sourceSize, ByteBuffer modelId, List<Payload> payloads) {
    public Bundle {
      var copy = ByteBuffer.allocate(modelId.remaining());
      copy.put(modelId.duplicate()).flip();
      modelId = copy.asReadOnlyBuffer();
      payloads = List.copyOf(payloads);
    }

    @Override
    public ByteBuffer modelId() {
      return modelId.asReadOnlyBuffer();
    }
  }

  Bundle importModel(ByteBuffer source) throws IOException;
}
