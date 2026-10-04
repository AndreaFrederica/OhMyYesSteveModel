package cc.sirrus.ysmlib.scene.io;

import cc.sirrus.ysmlib.scene.ByteData;
import java.io.IOException;

/** The caller owns dependency access policy. Importers never implicitly open network or host paths. */
@FunctionalInterface
public interface AssetResolver {
  ByteData resolve(String sourceReference) throws IOException;
  AssetResolver NONE=reference->{ throw new AssetFormatException("External dependency requires a resolver: "+reference); };
}
