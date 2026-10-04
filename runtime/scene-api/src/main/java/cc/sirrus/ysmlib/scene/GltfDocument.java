package cc.sirrus.ysmlib.scene;

import java.util.Map;

/** Source and resolved dependencies remain available for extensions and lossless archival. */
public record GltfDocument(SceneAsset scene,ByteData source,Map<String,ByteData> dependencies) {
  public GltfDocument { dependencies=Map.copyOf(dependencies); }
}
