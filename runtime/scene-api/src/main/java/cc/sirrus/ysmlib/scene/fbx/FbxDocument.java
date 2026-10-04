package cc.sirrus.ysmlib.scene.fbx;

import cc.sirrus.ysmlib.scene.*;
import java.util.*;

/** Source FBX plus typed reader output. Curves and source doubles are not resampled or truncated. */
public record FbxDocument(ByteData source,Info info,List<Element> elements,List<Node> nodes,List<Mesh> meshes,List<Skin> skins,
    List<Animation> animations,List<Layer> layers,List<Curve> curves,List<Texture> textures,List<FbxDetails.BlendShape> blendShapes,
    FbxDetails.State details,CompatibilityReport compatibility) {
  public record Info(int version,boolean ascii,String creator,double sourceUnitMeters,double framesPerSecond) {}
  /** All four real components and other value variants are retained; flags identify active variants. */
  public record Property(String name,int type,int flags,long integer,DoubleData values,String text,ByteData blob) {}
  public record Connection(int destination,String sourceProperty,String targetProperty) {}
  /** Property layers are ordered from evaluated overrides to source and template defaults. */
  public record Element(int id,int typedId,int type,String name,List<List<Property>> propertyLayers,List<Connection> connections) {
    public Element { propertyLayers=propertyLayers.stream().map(List::copyOf).toList();connections=List.copyOf(connections); }
    public Optional<Property> property(String name) { for(var layer:propertyLayers) for(var p:layer) if(p.name().equals(name)) return Optional.of(p);return Optional.empty(); }
  }
  /** Matrices are source column-major 3x4 affine values. Node and non-inherited geometry transforms remain separate. */
  public record Node(int element,int parent,int mesh,int light,int camera,boolean visible,int inheritMode,
      DoubleData local,DoubleData world,DoubleData geometryLocal,DoubleData geometryWorld,IntData materials) {}
  public record UvSet(String name,DoubleData uv,DoubleData tangents,DoubleData bitangents,DoubleData tangentW,DoubleData bitangentW) {}
  public record ColorSet(String name,DoubleData colors) {}
  /** One- and two-corner faces remain points/lines; triangles contain source corner indices, not control-point indices. */
  public record Face(int begin,int count,int material,IntData triangles) {}
  public record Mesh(int element,DoubleData positions,DoubleData normals,IntData controlVertices,List<UvSet> uvSets,List<ColorSet> colorSets,
      List<Face> faces,IntData skins,IntData blendDeformers,int cacheDeformers,int subdivisionLevel,IntData allDeformers,
      DoubleData positionW,DoubleData normalW,boolean generatedNormals) {
    public Mesh { uvSets=List.copyOf(uvSets);colorSets=List.copyOf(colorSets);faces=List.copyOf(faces); }
    public int cornerCount() { return positions.size()/3; }
  }
  public record Cluster(int element,int bone,DoubleData geometryToBone,DoubleData meshToBone,DoubleData bindWorld,String linkMode,IntData rawVertices,DoubleData rawWeights) {}
  public record SkinVertex(int begin,int count,double dualQuaternionWeight) {}
  public record SkinWeight(int cluster,double weight) {}
  /** Method uses ufbx's fixed ABI order: linear, rigid, dual quaternion, blended dual quaternion/linear. */
  public record Skin(int element,int method,List<Cluster> clusters,List<SkinVertex> vertices,List<SkinWeight> weights) {
    public Skin { clusters=List.copyOf(clusters);vertices=List.copyOf(vertices);weights=List.copyOf(weights); }
  }
  public record Animation(int element,double begin,double end,IntData layers) {}
  public record AnimatedProperty(int element,String name,DoubleData defaults,IntData curves) {}
  public record Layer(int element,double weight,boolean blended,boolean additive,boolean composeRotation,boolean composeScale,List<AnimatedProperty> properties) {
    public Layer { properties=List.copyOf(properties); }
  }
  /** Cubic tangents are time/value offsets; interpolation is previous-key hold, next-key hold, linear or cubic. */
  public record Key(double time,double value,int interpolation,double leftDx,double leftDy,double rightDx,double rightDy) {}
  public record Curve(int element,int preMode,int preRepeat,int postMode,int postRepeat,List<Key> keys) { public Curve { keys=List.copyOf(keys); } }
  public record TextureLayer(int texture,int blendMode,double alpha) {}
  public record Texture(int element,int type,String relativePath,String absolutePath,ByteData rawRelativePath,ByteData rawAbsolutePath,
      ByteData content,String uvSet,int wrapU,int wrapV,DoubleData uvTransform,List<TextureLayer> layers) { public Texture { layers=List.copyOf(layers); } }
  public FbxDocument {
    Objects.requireNonNull(source);Objects.requireNonNull(info);elements=List.copyOf(elements);nodes=List.copyOf(nodes);meshes=List.copyOf(meshes);
    skins=List.copyOf(skins);animations=List.copyOf(animations);layers=List.copyOf(layers);curves=List.copyOf(curves);textures=List.copyOf(textures);
    blendShapes=List.copyOf(blendShapes);Objects.requireNonNull(details);Objects.requireNonNull(compatibility);
  }
  public int constraintCount() { return details.constraints().size(); }
}
