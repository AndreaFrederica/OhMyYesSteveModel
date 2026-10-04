package cc.sirrus.ysmlib.scene.fbx;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.*;

/** Bounded, memory-only document reader. External dependency access is never inferred from stored file paths. */
public final class FbxReader {
  public FbxDocument read(ByteData source,ReadLimits limits) throws AssetFormatException {
    try(var reactor=new FbxReactor(source,limits)) { return FbxSnapshots.document(source,reactor.source()); }
    catch(IllegalArgumentException|IllegalStateException|ArithmeticException e) { throw new AssetFormatException("Invalid FBX source: "+e.getMessage(),e); }
  }
}
