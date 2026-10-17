package cc.sirrus.ysmlib.scene.fbx;

import cc.sirrus.ysmlib.scene.*;
import java.util.*;

/** Resolved images indexed by source texture; package material overlays may append synthetic indices. */
public record FbxAssets(Map<Integer,Image> images,Map<Integer,FbxMappingProfile.MaterialOverride> materialOverrides) {
  public record Image(String reference,boolean embedded,ByteData bytes) { public Image { Objects.requireNonNull(reference);Objects.requireNonNull(bytes); } }
  public FbxAssets { images=Map.copyOf(images);materialOverrides=Map.copyOf(materialOverrides); }
  public FbxAssets(Map<Integer,Image> images) { this(images,Map.of()); }
}
