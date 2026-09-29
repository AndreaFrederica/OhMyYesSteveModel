package cc.sirrus.ysmlib.legacy;

import java.io.IOException;
import java.nio.ByteBuffer;

/** Historical archival view, independent of the current model schema and runtime projection. */
public interface DecodedWorkspaceProvider {
  int profile();

  /** Emits deterministic relative paths. The caller owns publication and integrity metadata. */
  void materialize(ByteBuffer historicalWire, EntrySink output) throws IOException;

  @FunctionalInterface
  interface EntrySink {
    void write(String relativePath, ByteBuffer bytes) throws IOException;
  }
}
