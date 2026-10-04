package cc.sirrus.ysmlib.scene.vrm;

import cc.sirrus.ysmlib.scene.*;
import java.util.*;

/** Versioned render inputs; loading/evaluating this service does not allocate host graphics resources. */
public interface VrmMaterials {
  enum Shader { GLTF,MTOON_0,MTOON_1,LEGACY_UNLIT,CUSTOM }
  enum ParameterSpace { GLTF_LINEAR,LEGACY_SHADER }
  enum Transfer { LINEAR,SRGB,SOURCE_DEFINED }
  enum Cull { NONE,FRONT,BACK }
  enum UvRule { STATIC,MTOON_0,MTOON_1,MATCAP }
  /** Blend factors retain Unity's numeric enum for legacy shaders (0=Zero,1=One,5=SrcAlpha,10=OneMinusSrcAlpha).
   * Legacy queue is exact; -1 denotes a glTF/MToon 1 category+offset order. */
  record RenderState(String alphaMode,float alphaCutoff,boolean depthWrite,Cull cull,int sourceBlend,int destinationBlend,
      boolean alphaToCoverage,int category,int queueOffset,int legacyQueue,String outlineMode,Cull outlineCull) {}
  /** Channel -1 means RGB/RGBA or a normal vector, otherwise 0=R,1=G,2=B. UV matrices use glTF's top-left convention. */
  record Texture(SceneAsset.TextureBinding binding,Transfer transfer,int channel,UvRule uvRule) {
    public Texture { Objects.requireNonNull(binding);Objects.requireNonNull(transfer);Objects.requireNonNull(uvRule); }
  }
  /** Legacy parameters keep shader names and source color values. Never reinterpret them as glTF linear factors. */
  record Material(int index,String shaderName,Shader shader,ParameterSpace parameterSpace,Map<String,FloatData> parameters,
      Map<String,Texture> textures,Map<String,Boolean> keywords,RenderState renderState,double seconds,CompatibilityReport compatibility) {
    public Material { parameters=Map.copyOf(parameters);textures=Map.copyOf(textures);keywords=Map.copyOf(keywords);Objects.requireNonNull(compatibility); }
  }
  record Frame(List<Material> materials) { public Frame { materials=List.copyOf(materials); } }
  /** Values come from the same expression evaluation used by playback; null uses the immutable material bases. */
  Frame evaluate(List<VrmExpressions.MaterialState> expressions,double seconds);
  /** Resolve texture coordinates with a caller-sampled mask channel. No mask texture means a mask value of one.
   * MATCAP inputs are view-normal-generated UVs; this service never derives them from mesh TEXCOORD. */
  FloatData uv(Material material,String slot,float u,float v,float mask);
}
