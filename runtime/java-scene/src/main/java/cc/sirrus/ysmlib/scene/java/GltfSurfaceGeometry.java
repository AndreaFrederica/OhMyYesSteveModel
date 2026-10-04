package cc.sirrus.ysmlib.scene.java;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import cc.sirrus.ysmlib.scene.mesh.MikkTangents;
import java.util.*;

/** Source preparation, before morphing or skinning. Generated target deltas follow glTF mesh semantics. */
public final class GltfSurfaceGeometry {
  /** Derived meshes only: node/skin/animation domains and original assets remain unchanged. */
  public SceneAsset prepare(SceneAsset source,ReadLimits limits) {
    var meshes=new ArrayList<MeshAsset>();var budget=new Budget(limits);
    for(var mesh:source.meshes()) {
      var primitives=new ArrayList<MeshAsset.Primitive>();
      for(var primitive:mesh.primitives()) {
        var material=primitive.material()<0?null:source.materials().get(primitive.material());
        var normal=material==null?null:material.textures().get("normal");
        primitives.add(prepare(primitive,normal==null?-1:normal.texCoord(),budget));
      }
      meshes.add(new MeshAsset(mesh.name(),primitives,mesh.defaultMorphWeights()));
    }
    return new SceneAsset(source.name(),source.coordinates(),source.nodes(),source.scenes(),source.defaultScene(),meshes,source.skins(),
        source.materials(),source.textures(),source.images(),source.cameras(),source.lights(),source.animations(),source.metadata(),source.compatibility());
  }
  public MeshAsset.Primitive prepare(MeshAsset.Primitive source,int normalTexCoord,ReadLimits limits) {
    return prepare(source,normalTexCoord,new Budget(Objects.requireNonNull(limits)));
  }
  private MeshAsset.Primitive prepare(MeshAsset.Primitive source,int normalTexCoord,Budget budget) {
    Objects.requireNonNull(source);
    if(normalTexCoord< -1) throw new IllegalArgumentException("Invalid normal texture UV set");
    boolean flat=!source.attributes().containsKey("NORMAL");
    boolean triangles=switch(source.topology()) { case TRIANGLES,TRIANGLE_STRIP,TRIANGLE_FAN->true;default->false; };
    boolean tangents=normalTexCoord>=0 && (flat || !source.attributes().containsKey("TANGENT"));
    if(!triangles) {
      // glTF points/lines have no implicit normal or tangent. Host lighting follows the source attribute presence.
      if(!flat || !source.attributes().containsKey("TANGENT")) return source;
      var attributes=new LinkedHashMap<>(source.attributes());attributes.remove("TANGENT");
      var targets=new ArrayList<MeshAsset.MorphTarget>();
      for(var target:source.morphs()) { var a=new LinkedHashMap<>(target.attributes());a.remove("TANGENT");targets.add(new MeshAsset.MorphTarget(target.name(),a,target.vertexIndices())); }
      return new MeshAsset.Primitive(source.topology(),attributes,source.indices(),source.material(),source.skinning(),targets);
    }
    if(!flat && !tangents) return source;
    String uv="TEXCOORD_"+normalTexCoord;
    if(tangents && (!source.attributes().containsKey(uv) || source.attributes().get(uv).components()!=2))
      throw new IllegalArgumentException("Normal texture requires VEC2 "+uv);
    int count=source.topology()==MeshAsset.Topology.TRIANGLES?source.indices().size():Math.multiplyExact(Math.max(0,source.indices().size()-2),3);
    budget.add(count);int[] corners=new int[count];
    if(source.topology()==MeshAsset.Topology.TRIANGLES) for(int i=0;i<count;i++) corners[i]=source.indices().get(i);
    else for(int f=0;f<count/3;f++) {
      var indices=source.indices();int a,b,c;
      if(source.topology()==MeshAsset.Topology.TRIANGLE_STRIP) { a=f+(f%2);b=f+1-(f%2);c=f+2; }
      else { a=0;b=f+1;c=f+2; }
      corners[f*3]=indices.get(a);corners[f*3+1]=indices.get(b);corners[f*3+2]=indices.get(c);
    }
    // Mikk returns face corners. Reusing source indices would overwrite discontinuous tangent frames.
    var base=new LinkedHashMap<String,MeshAsset.Attribute>();
    for(var entry:source.attributes().entrySet()) {
      if(entry.getKey().equals("TANGENT") && flat) continue;
      base.put(entry.getKey(),remap(entry.getValue(),corners,budget));
    }
    if(flat) base.put("NORMAL",flatNormals(base.get("POSITION"),budget));
    var generator=new MikkTangents();
    if(tangents) base.put("TANGENT",tangents(generator,base.get("POSITION"),base.get("NORMAL"),base.get(uv),budget));
    var targets=new ArrayList<MeshAsset.MorphTarget>();
    for(var target:source.morphs()) {
      var delta=new LinkedHashMap<String,MeshAsset.Attribute>();
      for(var entry:target.attributes().entrySet()) {
        if(entry.getKey().equals("TANGENT") && (flat || tangents)) continue;
        if(entry.getKey().equals("NORMAL") && flat) continue;
        var sourceBase=source.attributes().get(entry.getKey());
        if(sourceBase==null) throw new IllegalArgumentException("Morph attribute has no base: "+entry.getKey());
        delta.put(entry.getKey(),remapTarget(entry.getValue(),target.vertexIndices(),corners,budget));
      }
      var targetPosition=add(base.get("POSITION"),delta.get("POSITION"),budget);
      MeshAsset.Attribute targetNormal;
      if(flat) { targetNormal=flatNormals(targetPosition,budget);delta.put("NORMAL",subtract(targetNormal,base.get("NORMAL"),3,budget)); }
      else targetNormal=add(base.get("NORMAL"),delta.get("NORMAL"),budget);
      if(tangents) {
        var targetTangent=tangents(generator,targetPosition,targetNormal,add(base.get(uv),delta.get(uv),budget),budget);
        // Morph displacements never change handedness. The base W survives arbitrary morph weights.
        delta.put("TANGENT",subtract(targetTangent,base.get("TANGENT"),3,budget));
      }
      targets.add(new MeshAsset.MorphTarget(target.name(),delta));
    }
    MeshAsset.Skinning skin=null;
    if(source.skinning()!=null) {
      var old=source.skinning();long influences=0;
      for(int v:corners) influences+=old.offsets().get(v+1)-old.offsets().get(v);
      budget.add(influences*2+count+1);int[] offsets=new int[count+1],joints=new int[(int)influences];float[] weights=new float[joints.length];
      var deforms=new ArrayList<MeshAsset.Deform>(count);int at=0;
      for(int i=0;i<count;i++) {
        int vertex=corners[i];offsets[i]=at;deforms.add(old.deforms().get(vertex));
        for(int j=old.offsets().get(vertex);j<old.offsets().get(vertex+1);j++) { joints[at]=old.joints().get(j);weights[at++]=old.weights().get(j); }
      }
      offsets[count]=at;FloatData sdef=FloatData.EMPTY;
      if(old.sdef().size()!=0) sdef=remap(new MeshAsset.Attribute(9,old.sdef()),corners,budget).values();
      skin=new MeshAsset.Skinning(new IntData(offsets),new IntData(joints),new FloatData(weights),deforms,sdef);
    }
    budget.add(count);int[] indices=new int[count];for(int i=0;i<count;i++) indices[i]=i;
    return new MeshAsset.Primitive(MeshAsset.Topology.TRIANGLES,base,new IntData(indices),source.material(),skin,targets);
  }
  private static MeshAsset.Attribute remap(MeshAsset.Attribute source,int[] corners,Budget budget) {
    int c=source.components();budget.add((long)corners.length*c);float[] out=new float[corners.length*c];
    for(int i=0;i<corners.length;i++) for(int k=0;k<c;k++) out[i*c+k]=source.values().get(corners[i]*c+k);
    return new MeshAsset.Attribute(c,new FloatData(out));
  }
  private static MeshAsset.Attribute remapTarget(MeshAsset.Attribute source,IntData sparse,int[] corners,Budget budget) {
    if(sparse.size()==0) return remap(source,corners,budget);
    int c=source.components();budget.add((long)corners.length*c);float[] out=new float[corners.length*c];
    budget.add((long)sparse.size()*2);
    var rows=new HashMap<Integer,List<Integer>>();
    for(int row=0;row<sparse.size();row++) rows.computeIfAbsent(sparse.get(row),ignored->new ArrayList<>()).add(row);
    for(int i=0;i<corners.length;i++) {
      var mapped=rows.get(corners[i]);if(mapped==null) continue;
      for(int row:mapped) for(int k=0;k<c;k++) out[i*c+k]+=source.values().get(row*c+k);
    }
    return new MeshAsset.Attribute(c,new FloatData(out));
  }
  private static MeshAsset.Attribute flatNormals(MeshAsset.Attribute positions,Budget budget) {
    budget.add(positions.values().size());float[] out=new float[positions.values().size()];var p=positions.values();
    for(int i=0;i<out.length;i+=9) {
      double ax=(double)p.get(i+3)-p.get(i),ay=(double)p.get(i+4)-p.get(i+1),az=(double)p.get(i+5)-p.get(i+2);
      double bx=(double)p.get(i+6)-p.get(i),by=(double)p.get(i+7)-p.get(i+1),bz=(double)p.get(i+8)-p.get(i+2);
      double x=ay*bz-az*by,y=az*bx-ax*bz,z=ax*by-ay*bx,length=Math.sqrt(x*x+y*y+z*z);
      // Degenerate faces have no defined normal; preserve zero instead of inventing an axis.
      if(length!=0) for(int v=0;v<3;v++) { out[i+v*3]=(float)(x/length);out[i+v*3+1]=(float)(y/length);out[i+v*3+2]=(float)(z/length); }
    }
    return new MeshAsset.Attribute(3,new FloatData(out));
  }
  private static MeshAsset.Attribute add(MeshAsset.Attribute base,MeshAsset.Attribute delta,Budget budget) {
    if(delta==null) return base;
    if(base.components()!=delta.components() || base.count()!=delta.count()) throw new IllegalArgumentException("Invalid surface morph shape");
    budget.add(base.values().size());
    float[] values=base.values().copy();for(int i=0;i<values.length;i++) values[i]+=delta.values().get(i);
    return new MeshAsset.Attribute(base.components(),new FloatData(values));
  }
  private static MeshAsset.Attribute subtract(MeshAsset.Attribute target,MeshAsset.Attribute base,int components,Budget budget) {
    budget.add((long)base.count()*components);float[] values=new float[base.count()*components];
    for(int v=0;v<base.count();v++) for(int c=0;c<components;c++) values[v*components+c]=target.values().get(v*target.components()+c)-base.values().get(v*base.components()+c);
    return new MeshAsset.Attribute(components,new FloatData(values));
  }
  private static MeshAsset.Attribute tangents(MikkTangents generator,MeshAsset.Attribute p,MeshAsset.Attribute n,MeshAsset.Attribute uv,Budget budget) {
    budget.add((long)p.count()*4);
    return new MeshAsset.Attribute(4,generator.generate(p.values(),n.values(),uv.values(),budget.limits));
  }
  /** Cumulative generated scalars, including target intermediates. No arbitrary truncation of data. */
  private static final class Budget {
    private final ReadLimits limits;private long elements;
    Budget(ReadLimits limits) { this.limits=limits; }
    void add(long count) {
      elements=Math.addExact(elements,count);
      if(count<0 || elements>limits.maxElements() || elements*4>limits.maxBytes()) throw new IllegalArgumentException("Surface geometry budget exceeded");
    }
  }
}
