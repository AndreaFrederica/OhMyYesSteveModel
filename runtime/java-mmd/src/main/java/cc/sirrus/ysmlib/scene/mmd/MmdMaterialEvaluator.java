package cc.sirrus.ysmlib.scene.mmd;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import java.util.*;
import static cc.sirrus.ysmlib.scene.mmd.MmdMaterials.*;

/** Resolves source material references without interpreting PMD filenames as PMX shared-toon flags. */
public final class MmdMaterialEvaluator implements MmdMaterials {
  private static final FloatData ONE=new FloatData(1,1,1,1),ZERO=new FloatData(0,0,0,0);
  private final List<Definition> definitions;
  private final Frame rest;
  public MmdMaterialEvaluator(PmxDocument source) {
    var definitions=new ArrayList<Definition>();var materials=new ArrayList<Material>();
    for(var value:source.materials()) {
      var definition=new Definition(definitions.size(),Format.PMX,value.names().local(),value.flags(),
          SphereMode.values()[value.sphereMode()],indexed(source,value.texture()),indexed(source,value.sphereTexture()),
          value.sharedToon()?new Image(ImageKind.SHARED_TOON,value.toonTexture(),"",-1):indexed(source,value.toonTexture()));
      definitions.add(definition);materials.add(new Material(definition,base(value.diffuse(),value.specular(),value.shininess(),value.ambient(),value.edgeColor(),value.edgeSize())));
    }
    this.definitions=List.copyOf(definitions);rest=new Frame(materials);
  }
  public MmdMaterialEvaluator(PmdDocument source) {
    var definitions=new ArrayList<Definition>();var materials=new ArrayList<Material>();
    for(var value:source.materials()) {
      Image diffuse=null,sphere=null,toon=null;SphereMode mode=SphereMode.NONE;
      for(String name:value.textureNames().split("\\*",-1)) if(!name.isEmpty()) {
        String lower=name.toLowerCase(Locale.ROOT);
        if(lower.endsWith(".sph") || lower.endsWith(".spa")) {
          if(sphere!=null) throw new IllegalArgumentException("Multiple PMD sphere textures in one material");
          sphere=named(name,-1);mode=lower.endsWith(".spa")?SphereMode.ADD:SphereMode.MULTIPLY;
        } else {
          if(diffuse!=null) throw new IllegalArgumentException("Multiple PMD diffuse textures in one material");
          diffuse=named(name,-1);
        }
      }
      if(value.toonIndex()!=255) {
        String name=source.toonTextures().isEmpty()?String.format(Locale.ROOT,"toon%02d.bmp",value.toonIndex()+1):source.toonTextures().get(value.toonIndex());
        if(!name.isEmpty()) toon=named(name,defaultToon(name));
      }
      // PMD's alpha=0.98 self-shadow convention is distinct from its opacity value.
      int flags=(value.alpha()<1?1:0)|(value.edgeFlag()!=0?16:0)|2;
      if(Math.abs(value.alpha()-.98f)>1e-7) flags|=12;
      var definition=new Definition(definitions.size(),Format.PMD,"material-"+definitions.size(),flags,mode,diffuse,sphere,toon);
      definitions.add(definition);materials.add(new Material(definition,base(
          new FloatData(value.diffuse().x(),value.diffuse().y(),value.diffuse().z(),value.alpha()),value.specular(),value.shininess(),value.ambient(),new FloatData(0,0,0,1),value.edgeFlag()==0?0:1)));
    }
    this.definitions=List.copyOf(definitions);rest=new Frame(materials);
  }
  private static Image indexed(PmxDocument source,int index) {
    return index<0 || source.textures().get(index).isEmpty()?null:new Image(ImageKind.INDEXED,index,"",-1);
  }
  private static Image named(String name,int fallback) { return new Image(ImageKind.NAMED,-1,name,fallback); }
  private static int defaultToon(String name) {
    return name.matches("(?i)toon(0[1-9]|10)\\.bmp")?Integer.parseInt(name.substring(4,6))-1:-1;
  }
  private static MmdMorphState.Material base(FloatData diffuse,Vec3 specular,float shininess,Vec3 ambient,FloatData edge,float edgeSize) {
    return new MmdMorphState.Material(diffuse,specular,shininess,ambient,edge,edgeSize,ONE,ZERO,ONE,ZERO,ONE,ZERO);
  }
  public List<Definition> definitions() { return definitions; }
  public Frame rest() { return rest; }
  public MeshAsset.Primitive geometry(int material,MeshAsset.Primitive evaluated,ReadLimits limits) {
    var definition=definitions.get(material);
    if(evaluated.skinning()!=null || !evaluated.morphs().isEmpty() || evaluated.topology()!=MeshAsset.Topology.TRIANGLES)
      throw new IllegalArgumentException("MMD material projection requires evaluated source triangles");
    if(evaluated.material()!=material) throw new IllegalArgumentException("MMD material/geometry index mismatch");
    if(definition.vertexColor() || definition.sphere()!=null && definition.sphereMode()==SphereMode.SUB_TEXTURE) {
      var uv=evaluated.attributes().get("_MMD_UV1");
      if(uv==null || uv.components()!=4) throw new IllegalArgumentException("MMD material requires additional UV1");
    }
    if(!definition.points() && !definition.lines()) return evaluated;
    // The explicit PMX description defines point precedence and all three edges per face.
    // Do not reinterpret the existing triplets as a line list, which drops/cross-connects edges.
    long count=(long)evaluated.indices().size()*(definition.points()?1:2);
    if(count>limits.maxElements() || count*Integer.BYTES>limits.maxBytes()) throw new IllegalArgumentException("MMD drawing index budget exceeded");
    IntData indices=evaluated.indices();
    if(!definition.points()) {
      int[] edges=new int[(int)count];
      for(int triangle=0;triangle<indices.size();triangle+=3) {
        int a=indices.get(triangle),b=indices.get(triangle+1),c=indices.get(triangle+2),at=triangle*2;
        edges[at]=a;edges[at+1]=b;edges[at+2]=b;edges[at+3]=c;edges[at+4]=c;edges[at+5]=a;
      }
      indices=new IntData(edges);
    }
    return new MeshAsset.Primitive(definition.points()?MeshAsset.Topology.POINTS:MeshAsset.Topology.LINES,evaluated.attributes(),indices,material,null,List.of());
  }
  public Frame evaluate(List<MmdMorphState.Material> values) {
    Objects.requireNonNull(values);
    if(values.size()!=definitions.size()) throw new IllegalArgumentException("MMD material frame size mismatch");
    var result=new ArrayList<Material>(values.size());
    for(int i=0;i<values.size();i++) result.add(new Material(definitions.get(i),values.get(i)));
    return new Frame(result);
  }
}
