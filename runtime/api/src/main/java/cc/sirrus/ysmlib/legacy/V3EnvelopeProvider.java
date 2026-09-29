package cc.sirrus.ysmlib.legacy;

import java.io.IOException;
import java.nio.ByteBuffer;

/** Historical compiled wire; deliberately separate from the V1/V2 raw-file archive API. */
public interface V3EnvelopeProvider {
  int SOURCE_LIMIT = 69_206_016;

  int profile();

  byte[] decode(ByteBuffer envelope, int plaintextLimit) throws IOException;
}
