package cc.sirrus.ysmlib.scene.fbx;

import cc.sirrus.ysmlib.scene.*;
import java.util.*;

/** Resolved images indexed by source texture. Stored filenames remain in the original document. */
public record FbxAssets(Map<Integer,Image> images) {
  public record Image(String reference,boolean embedded,ByteData bytes) { public Image { Objects.requireNonNull(reference);Objects.requireNonNull(bytes); } }
  public FbxAssets { images=Map.copyOf(images); }
}
