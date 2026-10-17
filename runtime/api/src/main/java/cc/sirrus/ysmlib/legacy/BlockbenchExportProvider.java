package cc.sirrus.ysmlib.legacy;

import java.io.IOException;
import java.nio.ByteBuffer;

/** Exports a validated historical YSM source as an editable Blockbench project. */
public interface BlockbenchExportProvider {
  /** Export a complete V3 envelope to one UTF-8 {@code .bbmodel} JSON document. */
  byte[] exportModel(ByteBuffer source) throws IOException;

  /** Export an already decoded historical wire document. */
  byte[] exportWire(byte[] wire, long sourceSize) throws IOException;

  /** Changes when the Blockbench mapping semantics change. */
  default int profile() { return 1; }
}
