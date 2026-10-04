package cc.sirrus.ysmlib.scene;

import java.util.*;

/** Immutable portable scene. Every source node remains addressable, whether or not it belongs to a skin. */
public record SceneAsset(String name,Coordinates coordinates,List<Node> nodes,List<Scene> scenes,int defaultScene,
    List<MeshAsset> meshes,List<Skin> skins,List<Material> materials,List<Texture> textures,List<Image> images,
    List<Camera> cameras,List<Light> lights,List<AnimationClip> animations,Map<String,String> metadata,
    CompatibilityReport compatibility) {
  public record Coordinates(boolean rightHanded,double metersPerUnit,String upAxis) {
    public static final Coordinates GLTF=new Coordinates(true,1,"Y");
    public Coordinates { if(!Double.isFinite(metersPerUnit) || metersPerUnit<=0) throw new IllegalArgumentException("Invalid unit scale");Objects.requireNonNull(upAxis); }
  }
  /** matrix==null means TRS; a source matrix is never lossily decomposed for storage. */
  public record Node(String name,IntData children,Transform transform,Matrix4 matrix,int mesh,int skin,int camera,int light,
      FloatData morphWeights,Map<String,String> metadata) {
    public Node { name=Objects.requireNonNullElse(name,"");Objects.requireNonNull(children);Objects.requireNonNull(transform);Objects.requireNonNull(morphWeights);metadata=Map.copyOf(metadata); }
    public Matrix4 localMatrix() { return matrix==null?transform.matrix():matrix; }
  }
  public record Scene(String name,IntData roots,Map<String,String> metadata) {
    public Scene { name=Objects.requireNonNullElse(name,"");Objects.requireNonNull(roots);metadata=Map.copyOf(metadata); }
  }
  public record Skin(String name,IntData joints,List<Matrix4> inverseBindMatrices,int skeleton,Map<String,String> metadata) {
    public Skin { name=Objects.requireNonNullElse(name,"");Objects.requireNonNull(joints);inverseBindMatrices=List.copyOf(inverseBindMatrices);metadata=Map.copyOf(metadata);
      if(joints.size()!=inverseBindMatrices.size()) throw new IllegalArgumentException("Inverse bind matrix count mismatch"); }
  }
  /** Texture bindings identify semantic usage; UV transforms retain the full 3x3 column-major matrix. */
  public record TextureBinding(int texture,int texCoord,FloatData uvTransform,float scale,Map<String,String> metadata) {
    public TextureBinding { if(texture<0 || texCoord<0 || uvTransform.size()!=9 || !Float.isFinite(scale)) throw new IllegalArgumentException("Invalid texture binding");metadata=Map.copyOf(metadata); }
  }
  public record Material(String name,String workflow,Map<String,FloatData> parameters,Map<String,TextureBinding> textures,
      String alphaMode,float alphaCutoff,boolean doubleSided,Map<String,String> metadata) {
    public Material { name=Objects.requireNonNullElse(name,"");Objects.requireNonNull(workflow);parameters=Map.copyOf(parameters);textures=Map.copyOf(textures);
      Objects.requireNonNull(alphaMode);metadata=Map.copyOf(metadata);if(!Float.isFinite(alphaCutoff)) throw new IllegalArgumentException("Non-finite alpha cutoff"); }
  }
  public record Texture(String name,int image,int magFilter,int minFilter,int wrapS,int wrapT,Map<String,String> metadata) {
    public Texture { name=Objects.requireNonNullElse(name,"");metadata=Map.copyOf(metadata); }
  }
  public record Image(String name,String mimeType,String sourceUri,ByteData bytes,Map<String,String> metadata) {
    public Image { name=Objects.requireNonNullElse(name,"");mimeType=Objects.requireNonNullElse(mimeType,"");sourceUri=Objects.requireNonNullElse(sourceUri,"");Objects.requireNonNull(bytes);metadata=Map.copyOf(metadata); }
  }
  /** Perspective: yfov/aspect/znear/zfar (infinite far encoded by absent parameter). Orthographic: xmag/ymag/znear/zfar. */
  public record Camera(String name,String projection,Map<String,FloatData> parameters,Map<String,String> metadata) {
    public Camera { name=Objects.requireNonNullElse(name,"");Objects.requireNonNull(projection);parameters=Map.copyOf(parameters);metadata=Map.copyOf(metadata); }
  }
  /** glTF intensity remains in physical units, range=0 denotes infinite. Host extensions own lighting effects. */
  public record Light(String name,String type,Vec3 color,float intensity,float range,float innerCone,float outerCone,Map<String,String> metadata) {
    public Light { name=Objects.requireNonNullElse(name,"");Objects.requireNonNull(type);Objects.requireNonNull(color);metadata=Map.copyOf(metadata);
      if(!Float.isFinite(intensity) || !Float.isFinite(range) || !Float.isFinite(innerCone) || !Float.isFinite(outerCone)) throw new IllegalArgumentException("Non-finite light"); }
  }
  public SceneAsset {
    name=Objects.requireNonNullElse(name,"");Objects.requireNonNull(coordinates);nodes=List.copyOf(nodes);scenes=List.copyOf(scenes);
    meshes=List.copyOf(meshes);skins=List.copyOf(skins);materials=List.copyOf(materials);textures=List.copyOf(textures);images=List.copyOf(images);
    cameras=List.copyOf(cameras);lights=List.copyOf(lights);animations=List.copyOf(animations);metadata=Map.copyOf(metadata);Objects.requireNonNull(compatibility);
    reference(defaultScene,scenes.size(),"default scene");
    int[] parents=new int[nodes.size()];Arrays.fill(parents,-1);
    for(int i=0;i<nodes.size();i++) {
      var node=nodes.get(i);reference(node.mesh(),meshes.size(),"node mesh");reference(node.skin(),skins.size(),"node skin");
      reference(node.camera(),cameras.size(),"node camera");reference(node.light(),lights.size(),"node light");
      for(int k=0;k<node.children().size();k++) { int child=node.children().get(k);
        if(child<0 || child>=nodes.size() || parents[child]!=-1) throw new IllegalArgumentException("Invalid or multiply parented node");parents[child]=i; }
    }
    byte[] state=new byte[nodes.size()];
    for(int start=0;start<nodes.size();start++) {
      int node=start;while(node!=-1 && state[node]==0) { state[node]=1;node=parents[node]; }
      if(node!=-1 && state[node]==1) throw new IllegalArgumentException("Cyclic scene graph");
      node=start;while(node!=-1 && state[node]==1) { state[node]=2;node=parents[node]; }
    }
    for(var scene:scenes) { var unique=new HashSet<Integer>();for(int i=0;i<scene.roots().size();i++) {
      int root=scene.roots().get(i);if(root<0 || root>=nodes.size() || parents[root]!=-1 || !unique.add(root)) throw new IllegalArgumentException("Invalid scene root"); } }
    for(var skin:skins) { reference(skin.skeleton(),nodes.size(),"skeleton");var unique=new HashSet<Integer>();for(int i=0;i<skin.joints().size();i++) {
      int joint=skin.joints().get(i);if(joint<0 || joint>=nodes.size() || !unique.add(joint)) throw new IllegalArgumentException("Invalid skin joint"); } }
  }
  private static void reference(int index,int count,String label) { if(index< -1 || index>=count) throw new IllegalArgumentException("Invalid "+label+" reference"); }
}
