package cc.sirrus.ysmlib.legacy.java;

import cc.sirrus.ysmlib.image.ImageProvider;
import cc.sirrus.ysmlib.legacy.*;
import java.io.IOException;
import java.nio.ByteBuffer;

/** Envelope, wire reader, and schema projector remain separate replaceable components. */
public final class JavaLegacyImportProvider implements LegacyImportProvider {
  private final V3EnvelopeProvider envelope;
  private final ImageProvider images;

  public JavaLegacyImportProvider(V3EnvelopeProvider envelope, ImageProvider images) {
    this.envelope = envelope;
    this.images = images;
  }

  @Override
  public Bundle importModel(ByteBuffer source) throws IOException {
    byte[] wire = envelope.decode(source, 256 * 1024 * 1024);
    return projectWire(wire, source.remaining());
  }

  public Bundle projectWire(byte[] wire, long sourceSize) throws IOException {
    return new HistoricalProjector()
        .project(new HistoricalWireReader(wire, images).read(), sourceSize);
  }
}
