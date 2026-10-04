package cc.sirrus.ysmlib.scene;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Indexed mesh with separate primitives, complete vertex attributes and unbounded influence sets. */
public record MeshAsset(String name,List<Primitive> primitives,FloatData defaultMorphWeights) {
  public enum Topology { POINTS,LINES,LINE_LOOP,LINE_STRIP,TRIANGLES,TRIANGLE_STRIP,TRIANGLE_FAN }
  public enum Deform { LINEAR,SDEF,QDEF }
  public record Attribute(int components,FloatData values) {
    public Attribute {
      Objects.requireNonNull(values);
      if(components<1 || components>16 || values.size()%components!=0) throw new IllegalArgumentException("Invalid vertex attribute shape");
    }
    public int count() { return values.size()/components; }
  }
  /** offsets has vertexCount+1 entries. SDEF data stores original C,R0,R1, nine floats per vertex. */
  public record Skinning(IntData offsets,IntData joints,FloatData weights,List<Deform> deforms,FloatData sdef) {
    public Skinning {
      Objects.requireNonNull(offsets);Objects.requireNonNull(joints);Objects.requireNonNull(weights);Objects.requireNonNull(sdef);
      deforms=List.copyOf(deforms);
      if(offsets.size()!=deforms.size()+1 || offsets.get(0)!=0 || offsets.get(offsets.size()-1)!=joints.size()
          || joints.size()!=weights.size()) throw new IllegalArgumentException("Invalid skin influence ranges");
      boolean hasSdef=deforms.contains(Deform.SDEF);
      if(hasSdef && sdef.size()!=deforms.size()*9) throw new IllegalArgumentException("Missing SDEF parameters");
      for(int v=0;v<deforms.size();v++) {
        int begin=offsets.get(v),end=offsets.get(v+1);
        if(begin<0 || end<begin || end>joints.size()) throw new IllegalArgumentException("Invalid skin offsets");
        if(deforms.get(v)==Deform.SDEF && end-begin!=2) throw new IllegalArgumentException("SDEF needs two influences");
        if(deforms.get(v)==Deform.QDEF && end-begin!=4) throw new IllegalArgumentException("QDEF needs four influences");
      }
      for(int i=0;i<joints.size();i++) if(joints.get(i)< -1 || joints.get(i)==-1 && weights.get(i)!=0 || weights.get(i)<0)
        throw new IllegalArgumentException("Invalid skin influence");
    }
  }
  /** Sparse targets scatter each attribute row to vertexIndices; repeated source indices remain additive. */
  public record MorphTarget(String name,Map<String,Attribute> attributes,IntData vertexIndices) {
    public MorphTarget(String name,Map<String,Attribute> attributes) { this(name,attributes,IntData.EMPTY); }
    public MorphTarget { name=Objects.requireNonNullElse(name,"");attributes=Map.copyOf(attributes);Objects.requireNonNull(vertexIndices); }
    public boolean sparse() { return vertexIndices.size()!=0; }
  }
  public record Primitive(Topology topology,Map<String,Attribute> attributes,IntData indices,int material,
                          Skinning skinning,List<MorphTarget> morphs) {
    public Primitive {
      Objects.requireNonNull(topology);attributes=Map.copyOf(attributes);Objects.requireNonNull(indices);morphs=List.copyOf(morphs);
      var positions=attributes.get("POSITION");
      if(positions==null || positions.components()!=3) throw new IllegalArgumentException("Mesh requires VEC3 positions");
      int count=positions.count();
      for(var a:attributes.values()) if(a.count()!=count) throw new IllegalArgumentException("Vertex attribute count mismatch");
      for(int i=0;i<indices.size();i++) if(indices.get(i)<0 || indices.get(i)>=count) throw new IllegalArgumentException("Mesh index out of bounds");
      if(material< -1) throw new IllegalArgumentException("Invalid material index");
      if(skinning!=null && skinning.deforms().size()!=count) throw new IllegalArgumentException("Skin vertex count mismatch");
      if(topology==Topology.TRIANGLES && indices.size()%3!=0 || topology==Topology.LINES && indices.size()%2!=0)
        throw new IllegalArgumentException("Incomplete mesh primitive");
      for(var target:morphs) {
        for(var a:target.attributes().values()) if(a.count()!=(target.sparse()?target.vertexIndices().size():count))
          throw new IllegalArgumentException("Morph vertex count mismatch");
        for(int i=0;i<target.vertexIndices().size();i++) if(target.vertexIndices().get(i)<0 || target.vertexIndices().get(i)>=count)
          throw new IllegalArgumentException("Sparse morph vertex outside mesh");
      }
      if(attributes.containsKey("NORMAL") && attributes.get("NORMAL").components()!=3
          || attributes.containsKey("TANGENT") && attributes.get("TANGENT").components()!=4)
        throw new IllegalArgumentException("Invalid normal/tangent shape");
    }
    public int vertexCount() { return attributes.get("POSITION").count(); }
  }
  public MeshAsset { name=Objects.requireNonNullElse(name,"");primitives=List.copyOf(primitives);Objects.requireNonNull(defaultMorphWeights); }
}
