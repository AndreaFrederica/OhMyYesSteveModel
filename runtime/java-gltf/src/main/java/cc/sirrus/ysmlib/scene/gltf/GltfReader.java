package cc.sirrus.ysmlib.scene.gltf;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.*;
import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import static cc.sirrus.ysmlib.scene.gltf.JsonFields.*;

/** glTF 2.0 / GLB source reader. External resources are resolved only through a caller-provided capability. */
public final class GltfReader {
  public GltfDocument read(ByteData source,AssetResolver resolver,ReadLimits limits) throws IOException {
    if(source.size()>limits.maxBytes()) throw new AssetFormatException("glTF source exceeds byte limit");
    try { return new Reader(source,resolver,limits).read(); }
    catch(RuntimeException e) {
      throw new AssetFormatException("Invalid glTF: "+e.getMessage(),e);
    }
  }
  private static final class Reader {
    final ByteData source;final ReadLimits limits;final JsonObject root;final GltfBuffers buffers;
    Reader(ByteData source,AssetResolver resolver,ReadLimits limits) throws IOException {
      this.source=source;this.limits=limits;var bytes=source.view().order(ByteOrder.LITTLE_ENDIAN);ByteData binary=null;byte[] json;
      if(bytes.remaining()>=4 && bytes.getInt(0)==0x46546c67) {
        if(bytes.remaining()<20 || bytes.getInt(4)!=2 || Integer.toUnsignedLong(bytes.getInt(8))!=bytes.limit()) throw new AssetFormatException("Invalid GLB header");
        bytes.position(12);json=null;boolean first=true;
        while(bytes.hasRemaining()) {
          if(bytes.remaining()<8) throw new AssetFormatException("Truncated GLB chunk header");
          int length=bytes.getInt(),type=bytes.getInt();if(length<0 || length>bytes.remaining() || length%4!=0) throw new AssetFormatException("Invalid GLB chunk length");
          if(first && type!=0x4e4f534a) throw new AssetFormatException("First GLB chunk must be JSON");first=false;
          if(type==0x4e4f534a) { if(json!=null) throw new AssetFormatException("Duplicate GLB JSON");json=new byte[length];bytes.get(json); }
          else if(type==0x004e4942) { if(binary!=null) throw new AssetFormatException("Duplicate GLB BIN");byte[] b=new byte[length];bytes.get(b);binary=new ByteData(b); }
          else bytes.position(bytes.position()+length); // Unrecognized GLB chunks remain in the original source.
        }
      } else json=source.copy();
      if(json==null) throw new AssetFormatException("Missing glTF JSON");
      String text;
      try { text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(json)).toString(); }
      catch(CharacterCodingException e) { throw new AssetFormatException("Malformed glTF UTF-8",e); }
      checkJson(text,limits);root=JsonParser.parseString(text).getAsJsonObject();
      var asset=object(root,"asset");if(!string(asset,"version","").equals("2.0") || !string(asset,"minVersion","2.0").equals("2.0")) throw new AssetFormatException("Only glTF 2.0 is supported");
      buffers=new GltfBuffers(root,binary,resolver,limits,source.size());
    }
    GltfDocument read() throws IOException {
      var meshes=new ArrayList<MeshAsset>();for(var element:array(root,"meshes")) meshes.add(mesh(element.getAsJsonObject()));
      var images=new ArrayList<SceneAsset.Image>();
      for(var element:array(root,"images")) {
        var i=element.getAsJsonObject();String uri=string(i,"uri","");ByteData data;
        if(i.has("uri") && i.has("bufferView") || !i.has("uri") && !i.has("bufferView")) throw new IllegalArgumentException("Image must have one source");
        if(i.has("bufferView")) { var view=buffers.view(integer(i,"bufferView",-1));byte[] b=new byte[view.remaining()];view.get(b);data=new ByteData(b); }
        else data=buffers.dependency(uri);
        images.add(new SceneAsset.Image(string(i,"name",""),string(i,"mimeType",""),uri,data,metadata(i)));
      }
      var textures=new ArrayList<SceneAsset.Texture>();var samplers=array(root,"samplers");
      for(var element:array(root,"textures")) {
        var t=element.getAsJsonObject();int sampler=integer(t,"sampler",-1);var s=sampler==-1?new JsonObject():samplers.get(sampler).getAsJsonObject();
        int image=integer(t,"source",-1);if(image>=images.size() || image< -1) throw new IllegalArgumentException("Invalid image reference");
        textures.add(new SceneAsset.Texture(string(t,"name",""),image,integer(s,"magFilter",9729),integer(s,"minFilter",9987),integer(s,"wrapS",10497),integer(s,"wrapT",10497),metadata(t)));
      }
      var materials=new ArrayList<SceneAsset.Material>();for(var element:array(root,"materials")) {
        var material=GltfMaterials.read(element.getAsJsonObject());for(var binding:material.textures().values()) if(binding.texture()>=textures.size()) throw new IllegalArgumentException("Invalid material texture");materials.add(material);
      }
      for(var mesh:meshes) for(var primitive:mesh.primitives()) if(primitive.material()>=materials.size()) throw new IllegalArgumentException("Invalid primitive material");
      var cameras=new ArrayList<SceneAsset.Camera>();for(var element:array(root,"cameras")) {
        var c=element.getAsJsonObject();String type=string(c,"type","");if(!type.equals("perspective") && !type.equals("orthographic")) throw new IllegalArgumentException("Unknown camera projection");
        var parameters=new LinkedHashMap<String,FloatData>();for(var field:object(c,type).entrySet()) if(field.getValue().isJsonPrimitive() && field.getValue().getAsJsonPrimitive().isNumber()) parameters.put(field.getKey(),new FloatData(number(field.getValue())));
        cameras.add(new SceneAsset.Camera(string(c,"name",""),type,parameters,metadata(c)));
      }
      var lights=new ArrayList<SceneAsset.Light>();for(var element:array(object(object(root,"extensions"),"KHR_lights_punctual"),"lights")) {
        var l=element.getAsJsonObject();var spot=object(l,"spot");lights.add(new SceneAsset.Light(string(l,"name",""),string(l,"type",""),vec3(l,"color",Vec3.ONE),number(l,"intensity",1),number(l,"range",0),number(spot,"innerConeAngle",0),number(spot,"outerConeAngle",(float)Math.PI/4),metadata(l)));
      }
      var skins=new ArrayList<SceneAsset.Skin>();for(var element:array(root,"skins")) {
        var s=element.getAsJsonObject();var joints=ints(array(s,"joints"));var inverse=new ArrayList<Matrix4>();
        if(s.has("inverseBindMatrices")) {
          var a=buffers.accessor(integer(s,"inverseBindMatrices",-1));if(!a.type().equals("MAT4") || a.componentType()!=5126 || a.count()!=joints.size()) throw new IllegalArgumentException("Invalid inverse bind accessor");
          for(int i=0;i<a.count();i++) { float[] m=new float[16];for(int k=0;k<16;k++) m[k]=a.values().get(i*16+k);inverse.add(new Matrix4(m)); }
        } else for(int i=0;i<joints.size();i++) inverse.add(Matrix4.IDENTITY);
        skins.add(new SceneAsset.Skin(string(s,"name",""),joints,inverse,integer(s,"skeleton",-1),metadata(s)));
      }
      var nodes=new ArrayList<SceneAsset.Node>();for(var element:array(root,"nodes")) {
        var n=element.getAsJsonObject();Matrix4 matrix=n.has("matrix")?new Matrix4(vectorSize(n,"matrix",16).copy()):null;
        if(matrix!=null && !matrix.isAffine()) throw new IllegalArgumentException("Node matrix must be affine");
        if(matrix!=null && (n.has("translation") || n.has("rotation") || n.has("scale"))) throw new IllegalArgumentException("Node has matrix and TRS");
        var q=vectorSize(n,"rotation",4,0,0,0,1);var transform=new Transform(vec3(n,"translation",Vec3.ZERO),new Rotation(q.get(0),q.get(1),q.get(2),q.get(3)),vec3(n,"scale",Vec3.ONE));
        int mesh=integer(n,"mesh",-1);var weights=vector(n,"weights");if(mesh>=0 && weights.size()!=0 && weights.size()!=meshes.get(mesh).defaultMorphWeights().size()) throw new IllegalArgumentException("Node morph count mismatch");
        int light=integer(object(object(n,"extensions"),"KHR_lights_punctual"),"light",-1);
        nodes.add(new SceneAsset.Node(string(n,"name",""),ints(array(n,"children")),transform,matrix,mesh,integer(n,"skin",-1),integer(n,"camera",-1),light,weights,metadata(n)));
      }
      var scenes=new ArrayList<SceneAsset.Scene>();for(var element:array(root,"scenes")) { var s=element.getAsJsonObject();scenes.add(new SceneAsset.Scene(string(s,"name",""),ints(array(s,"nodes")),metadata(s))); }
      var animations=new ArrayList<AnimationClip>();for(var element:array(root,"animations")) animations.add(animation(element.getAsJsonObject(),nodes,meshes));
      var meta=metadata(root);meta.put("asset",object(root,"asset").toString());
      var scene=new SceneAsset(string(object(root,"asset"),"generator",""),SceneAsset.Coordinates.GLTF,nodes,scenes,integer(root,"scene",-1),meshes,skins,materials,textures,images,cameras,lights,animations,meta,coverage());
      // Joint values are indices within each node's selected skin, not global scene node indices.
      for(var node:nodes) if(node.skin()!=-1) {
        if(node.mesh()==-1) throw new IllegalArgumentException("Skin node has no mesh");int jointCount=skins.get(node.skin()).joints().size();
        for(var primitive:meshes.get(node.mesh()).primitives()) { var skin=primitive.skinning();if(skin==null) throw new IllegalArgumentException("Skinned primitive has no weights");
          for(int i=0;i<skin.joints().size();i++) if(skin.joints().get(i)>=jointCount) throw new IllegalArgumentException("Vertex joint exceeds skin"); }
      }
      return new GltfDocument(scene,source,buffers.dependencies);
    }
    MeshAsset mesh(JsonObject mesh) {
      var primitives=new ArrayList<MeshAsset.Primitive>();int morphCount=-1;
      for(var value:array(mesh,"primitives")) {
        var p=value.getAsJsonObject();var attributes=new LinkedHashMap<String,MeshAsset.Attribute>();
        for(var entry:object(p,"attributes").entrySet()) {
          var a=buffers.accessor(integer(entry.getValue()));if(a.componentType()==5125) throw new IllegalArgumentException("Unsigned int vertex attribute is not glTF core");
          attributes.put(entry.getKey(),new MeshAsset.Attribute(a.components(),a.values()));
        }
        if(!attributes.containsKey("POSITION")) throw new IllegalArgumentException("Primitive has no positions");
        int count=attributes.get("POSITION").count();IntData indices;
        if(p.has("indices")) indices=buffers.indices(integer(p,"indices",-1),count);
        else { int[] sequential=new int[count];for(int i=0;i<count;i++) sequential[i]=i;indices=new IntData(sequential); }
        var targets=new ArrayList<MeshAsset.MorphTarget>();int ti=0;var targetNames=new JsonArray();
        if(mesh.has("extras") && mesh.get("extras").isJsonObject()) {
          var extras=mesh.getAsJsonObject("extras");if(extras.has("targetNames") && extras.get("targetNames").isJsonArray()) targetNames=extras.getAsJsonArray("targetNames");
        }
        for(var target:array(p,"targets")) {
          var fields=new LinkedHashMap<String,MeshAsset.Attribute>();for(var entry:target.getAsJsonObject().entrySet()) { var a=buffers.accessor(integer(entry.getValue()));fields.put(entry.getKey(),new MeshAsset.Attribute(a.components(),a.values())); }
          targets.add(new MeshAsset.MorphTarget(ti<targetNames.size()?targetNames.get(ti).getAsString():"",fields));ti++;
        }
        if(!targets.isEmpty()) {
          if(morphCount!=-1 && morphCount!=targets.size()) throw new IllegalArgumentException("Mesh primitives have different nonempty morph counts");
          morphCount=targets.size();
        }
        int mode=integer(p,"mode",4);if(mode<0 || mode>6) throw new IllegalArgumentException("Invalid topology");
        primitives.add(new MeshAsset.Primitive(MeshAsset.Topology.values()[mode],attributes,indices,integer(p,"material",-1),skinning(p,attributes,count),targets));
      }
      if(primitives.isEmpty()) throw new IllegalArgumentException("Mesh has no primitives");
      morphCount=Math.max(0,morphCount);
      var weights=vector(mesh,"weights",new float[morphCount]);if(weights.size()!=morphCount) throw new IllegalArgumentException("Default morph count mismatch");
      return new MeshAsset(string(mesh,"name",""),primitives,weights);
    }
    MeshAsset.Skinning skinning(JsonObject primitive,Map<String,MeshAsset.Attribute> attributes,int count) {
      var sets=new TreeMap<Integer,MeshAsset.Attribute>();
      for(var entry:attributes.entrySet()) if(entry.getKey().startsWith("JOINTS_")) sets.put(Integer.parseInt(entry.getKey().substring(7)),entry.getValue());
      for(String key:attributes.keySet()) if(key.startsWith("WEIGHTS_") && !sets.containsKey(Integer.parseInt(key.substring(8)))) throw new IllegalArgumentException("Weights without joints");
      if(sets.isEmpty()) return null;
      long n=(long)count*sets.size()*4;if(n>limits.maxBytes()/8 || n>Integer.MAX_VALUE) throw new IllegalArgumentException("Influence budget exceeded");
      int[] offsets=new int[count+1],joints=new int[(int)n];float[] weights=new float[(int)n];
      var definitions=object(primitive,"attributes");int write=0;
      for(var set:sets.entrySet()) {
        int id=set.getKey();var j=buffers.accessor(integer(definitions.get("JOINTS_"+id)));var w=buffers.accessor(integer(definitions.get("WEIGHTS_"+id)));
        if(id<0 || j.components()!=4 || w.components()!=4 || j.count()!=count || w.count()!=count || j.normalized() || j.componentType()!=5121 && j.componentType()!=5123)
          throw new IllegalArgumentException("Invalid joint/weight accessor");
      }
      for(int vertex=0;vertex<count;vertex++) {
        offsets[vertex]=write;double total=0;
        for(var set:sets.entrySet()) { var w=attributes.get("WEIGHTS_"+set.getKey());if(w==null) throw new IllegalArgumentException("Joints without weights");
          for(int i=0;i<4;i++) { joints[write]=(int)set.getValue().values().get(vertex*4+i);float weight=w.values().get(vertex*4+i);if(weight<0) throw new IllegalArgumentException("Negative weight");weights[write++]=weight;total+=weight; } }
        if(total==0) throw new IllegalArgumentException("Zero total skin weight");for(int i=offsets[vertex];i<write;i++) weights[i]/=(float)total;
      }
      offsets[count]=write;return new MeshAsset.Skinning(new IntData(offsets),new IntData(joints),new FloatData(weights),Collections.nCopies(count,MeshAsset.Deform.LINEAR),FloatData.EMPTY);
    }
    AnimationClip animation(JsonObject animation,List<SceneAsset.Node> nodes,List<MeshAsset> meshes) {
      var tracks=new ArrayList<AnimationClip.Track>();var samplers=array(animation,"samplers");var used=new HashSet<String>();
      for(var value:array(animation,"channels")) {
        var channel=value.getAsJsonObject();var target=object(channel,"target");String path=string(target,"path","");
        if(!Set.of("translation","rotation","scale","weights").contains(path)) throw new IllegalArgumentException("Unsupported animation target path: "+path);
        int node=integer(target,"node",-1);if(node<0 || node>=nodes.size()) throw new IllegalArgumentException("Invalid animation target node");
        if(!used.add(node+":"+path)) throw new IllegalArgumentException("Duplicate animation target");
        var sampler=samplers.get(integer(channel,"sampler",-1)).getAsJsonObject();var input=buffers.accessor(integer(sampler,"input",-1));var output=buffers.accessor(integer(sampler,"output",-1));
        if(input.components()!=1 || input.componentType()!=5126 || output.componentType()!=5126) throw new IllegalArgumentException("Animation needs floating-point accessors");
        var interpolation=AnimationCurve.Interpolation.valueOf(string(sampler,"interpolation","LINEAR"));
        if(interpolation==AnimationCurve.Interpolation.BEZIER) throw new IllegalArgumentException("Bezier is not a glTF interpolation");
        int components=path.equals("rotation")?4:3;
        if(path.equals("weights")) { int mesh=nodes.get(node).mesh();if(mesh<0) throw new IllegalArgumentException("Morph target has no mesh");components=meshes.get(mesh).defaultMorphWeights().size(); }
        int keys=input.count(),factor=interpolation==AnimationCurve.Interpolation.CUBICSPLINE?3:1;
        if((long)keys*components*factor!=output.values().size()) throw new IllegalArgumentException("Animation output count mismatch");
        if(!path.equals("weights") && output.components()!=components || path.equals("weights") && output.components()!=1) throw new IllegalArgumentException("Invalid animation output shape");
        if(!path.equals("weights") && nodes.get(node).matrix()!=null) throw new IllegalArgumentException("Animation targets a matrix node");
        double[] times=new double[keys];float[] values=new float[keys*components],in=factor==3?new float[values.length]:new float[0],out=new float[in.length];
        for(int i=0;i<keys;i++) { times[i]=input.values().get(i);for(int k=0;k<components;k++) {
          values[i*components+k]=output.values().get((i*factor+(factor==3?1:0))*components+k);
          if(factor==3) { in[i*components+k]=output.values().get(i*factor*components+k);out[i*components+k]=output.values().get((i*factor+2)*components+k); }
        } }
        var curve=new AnimationCurve(times,new FloatData(values),components,path.equals("rotation"),interpolation,new FloatData(in),new FloatData(out),FloatData.EMPTY);
        var property=switch(path) { case "translation"->AnimationClip.Property.TRANSLATION;case "rotation"->AnimationClip.Property.ROTATION;case "scale"->AnimationClip.Property.SCALE;case "weights"->AnimationClip.Property.MORPH_WEIGHTS;default->throw new AssertionError(); };
        tracks.add(new AnimationClip.Track(property,node,"","",curve));
      }
      return new AnimationClip(string(animation,"name",""),tracks,0);
    }
    CompatibilityReport coverage() {
      var required=new HashSet<String>();for(var e:array(root,"extensionsRequired")) required.add(e.getAsString());
      var features=new ArrayList<CompatibilityReport.Feature>();var diagnostics=new ArrayList<CompatibilityReport.Diagnostic>();
      features.add(new CompatibilityReport.Feature("gltf.core",CompatibilityReport.Level.EVALUATED,true,"Scene, skin, morph and animation CPU semantics; host renderer remains separate"));
      for(var extension:array(root,"extensionsUsed")) {
        String id=extension.getAsString();var level=switch(id) {
          case "KHR_mesh_quantization","KHR_texture_transform" -> CompatibilityReport.Level.EVALUATED;
          case "KHR_lights_punctual" -> CompatibilityReport.Level.EXTERNAL_EFFECT;
          default -> CompatibilityReport.Level.READ;
        };
        features.add(new CompatibilityReport.Feature(id,level,required.remove(id),level==CompatibilityReport.Level.READ?"Source retained; runtime support must be provided before claiming compatibility":"Parsed portable semantics"));
        if(level==CompatibilityReport.Level.READ) diagnostics.add(new CompatibilityReport.Diagnostic(CompatibilityReport.Severity.WARNING,"extensions/"+id,"Extension retained; evaluation/render integration is pending"));
      }
      if(!required.isEmpty()) throw new IllegalArgumentException("Required extensions missing from extensionsUsed");
      return new CompatibilityReport(features,diagnostics);
    }
  }
  /** Bound nesting and token count before Gson builds its object tree; reject duplicate object keys. */
  private static void checkJson(String text,ReadLimits limits) throws IOException {
    try(var reader=new JsonReader(new StringReader(text))) {
      reader.setLenient(false);var names=new ArrayDeque<Set<String>>();int depth=0,tokens=0;
      while(reader.peek()!=JsonToken.END_DOCUMENT) {
        if(++tokens>limits.maxElements()) throw new AssetFormatException("glTF JSON token budget exceeded");
        switch(reader.peek()) {
          case BEGIN_OBJECT -> { reader.beginObject();names.push(new HashSet<>());if(++depth>128) throw new AssetFormatException("JSON nesting limit exceeded"); }
          case END_OBJECT -> { reader.endObject();names.pop();depth--; }
          case BEGIN_ARRAY -> { reader.beginArray();if(++depth>128) throw new AssetFormatException("JSON nesting limit exceeded"); }
          case END_ARRAY -> { reader.endArray();depth--; }
          case NAME -> { String name=reader.nextName();if(!names.peek().add(name)) throw new AssetFormatException("Duplicate JSON key: "+name); }
          case STRING,NUMBER -> reader.nextString();case BOOLEAN -> reader.nextBoolean();case NULL -> reader.nextNull();
          default -> throw new AssetFormatException("Unexpected JSON token");
        }
      }
    }
  }
}
