package cc.sirrus.ysmlib.scene.fbx;

import cc.sirrus.ysmlib.scene.*;
import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import java.io.IOException;
import java.util.*;
import java.util.function.Function;
import static cc.sirrus.ysmlib.scene.fbx.FbxDocument.*;

/** Private reactor transfer adapter. Public records never retain mutable JSON trees. */
final class FbxSnapshots {
  private FbxSnapshots() {}

  /**
   * Reads the native/WASM snapshot incrementally.  JsonParser.parseReader on
   * the whole document creates one JsonElement per scalar and was responsible
   * for multi-gigabyte peaks when a modest FBX contained many sparse morph
   * offsets.  Only the current top-level record is materialized; it is
   * immediately converted to the immutable scene records before the next one
   * is read.
   */
  static FbxDocument document(ByteData source, JsonReader reader) throws IOException {
    return document(source,reader,false);
  }
  /** Native playback evaluates morph targets in ufbx and does not need the
   * large source-side delta tables. Keeping their count/element metadata while
   * skipping those duplicate arrays prevents a second Java-side morph copy. */
  static FbxDocument document(ByteData source, JsonReader reader, boolean compactMorphs) throws IOException {
    int version=0;boolean ascii=false;String creator="";double unit=1, fps=30;
    List<Element> elements=List.of();List<Node> nodes=List.of();List<Mesh> meshes=List.of();List<Skin> skins=List.of();
    List<Animation> animations=List.of();List<Layer> layers=List.of();List<Curve> curves=List.of();List<Texture> textures=List.of();List<FbxDetails.BlendShape> shapes=List.of();
    var detailRoot=new JsonObject();for(String n:List.of("blendDeformers","blendChannels","materials","lights","cameras","constraints","warnings")) detailRoot.add(n,new JsonArray());
    reader.beginObject();
    while(reader.hasNext()) {
      String name=reader.nextName();
      switch(name) {
        case "version" -> version=reader.nextInt(); case "ascii" -> ascii=reader.nextBoolean(); case "creator" -> creator=reader.nextString();
        case "sourceUnitMeters" -> unit=reader.nextDouble(); case "framesPerSecond" -> fps=reader.nextDouble();
        case "elements" -> elements=readObjects(reader,FbxSnapshots::element);
        case "nodes" -> nodes=readObjects(reader,FbxSnapshots::node);
        case "meshes" -> meshes=readMeshes(reader);
        // A skin can contain millions of sparse influence entries.  Parsing
        // the whole object through JsonParser creates a temporary JsonElement
        // for every scalar before the typed arrays are built.  Keep the
        // object-level stream and materialize only the bounded primitive
        // arrays that the public source record owns.
        case "skins" -> skins=readSkins(reader);
        case "animations" -> animations=readObjects(reader,FbxSnapshots::animation);
        case "animationLayers" -> layers=readObjects(reader,FbxSnapshots::layer);
        case "curves" -> curves=readObjects(reader,FbxSnapshots::curve);
        case "textures" -> textures=readObjects(reader,FbxSnapshots::texture);
        case "blendShapes" -> shapes=readBlendShapes(reader,compactMorphs);
        case "blendDeformers","blendChannels","materials","lights","cameras","constraints","warnings" -> detailRoot.add(name,readJsonArray(reader));
        default -> reader.skipValue();
      }
    }
    reader.endObject();
    var info=new Info(version,ascii,creator,unit,fps);var details=details(detailRoot);
    var coverage=new CompatibilityReport(List.of(new CompatibilityReport.Feature("fbx.source",CompatibilityReport.Level.READ,true,
        "Typed source records and original bytes retained; evaluation coverage is reported by a separate session")),
        details.warnings().stream().map(w->new CompatibilityReport.Diagnostic(CompatibilityReport.Severity.WARNING,"fbx.element."+w.element(),w.description()+" (count "+w.count()+")")).toList());
    return new FbxDocument(source,info,elements,nodes,meshes,skins,animations,layers,curves,textures,shapes,details,coverage);
  }

  private static JsonArray readJsonArray(JsonReader reader) throws IOException {
    var out=new JsonArray();reader.beginArray();while(reader.hasNext()) out.add(JsonParser.parseReader(reader));reader.endArray();return out;
  }
  private static <T> List<T> readObjects(JsonReader reader,java.util.function.Function<JsonObject,T> mapper) throws IOException {
    var out=new ArrayList<T>();reader.beginArray();while(reader.hasNext()) { var value=JsonParser.parseReader(reader);if(!value.isJsonObject()) throw new JsonParseException("FBX snapshot array item is not an object");out.add(mapper.apply(value.getAsJsonObject())); }reader.endArray();return List.copyOf(out);
  }
  private static List<Mesh> readMeshes(JsonReader reader) throws IOException {
    var out=new ArrayList<Mesh>();reader.beginArray();while(reader.hasNext()) out.add(readMesh(reader));reader.endArray();return List.copyOf(out);
  }

  private static List<Skin> readSkins(JsonReader reader) throws IOException {
    var out=new ArrayList<Skin>();reader.beginArray();while(reader.hasNext()) out.add(readSkin(reader));reader.endArray();return List.copyOf(out);
  }
  private static Skin readSkin(JsonReader reader) throws IOException {
    int element=0,method=0;var clusters=new ArrayList<Cluster>();var vertices=new SkinVertexBuilder();var weights=new SkinWeightBuilder();
    reader.beginObject();while(reader.hasNext()) switch(reader.nextName()) {
      case "element" -> element=reader.nextInt();
      case "method" -> method=reader.nextInt();
      case "clusters" -> { reader.beginArray();while(reader.hasNext()) clusters.add(readCluster(reader));reader.endArray(); }
      case "vertices" -> readSkinVertices(reader,vertices);
      case "weights" -> readSkinWeights(reader,weights);
      default -> reader.skipValue();
    }reader.endObject();
    return new Skin(element,method,List.copyOf(clusters),vertices.toList(),weights.toList());
  }
  private static Cluster readCluster(JsonReader reader) throws IOException {
    int element=0,bone=-1;DoubleData geometry=DoubleData.EMPTY,mesh=DoubleData.EMPTY,bind=DoubleData.EMPTY,rawWeights=DoubleData.EMPTY;String link="";IntData rawVertices=IntData.EMPTY;
    reader.beginObject();while(reader.hasNext()) switch(reader.nextName()) {
      case "element" -> element=reader.nextInt();case "bone" -> bone=reader.nextInt();case "geometryToBone" -> geometry=numbers(reader);
      case "meshToBone" -> mesh=numbers(reader);case "bindWorld" -> bind=numbers(reader);case "linkMode" -> link=reader.nextString();
      case "rawVertices" -> rawVertices=integers(reader);case "rawWeights" -> rawWeights=numbers(reader);default -> reader.skipValue();
    }reader.endObject();return new Cluster(element,bone,geometry,mesh,bind,link,rawVertices,rawWeights);
  }
  private static void readSkinVertices(JsonReader reader,SkinVertexBuilder out) throws IOException {
    reader.beginArray();while(reader.hasNext()) { reader.beginArray();int begin=reader.nextInt(),count=reader.nextInt();double dq=reader.nextDouble();if(reader.hasNext()) throw new JsonParseException("FBX skin vertex has extra values");reader.endArray();out.add(begin,count,dq); }reader.endArray();
  }
  private static void readSkinWeights(JsonReader reader,SkinWeightBuilder out) throws IOException {
    reader.beginArray();while(reader.hasNext()) { reader.beginArray();int cluster=reader.nextInt();double weight=reader.nextDouble();if(reader.hasNext()) throw new JsonParseException("FBX skin weight has extra values");reader.endArray();out.add(cluster,weight); }reader.endArray();
  }
  private static final class SkinVertexBuilder {
    private int[] begins=new int[1024],counts=new int[1024];private double[] dualWeights=new double[1024];private int size;
    void add(int begin,int count,double dualWeight) { if(size==begins.length) { int n=Math.multiplyExact(size,2);begins=Arrays.copyOf(begins,n);counts=Arrays.copyOf(counts,n);dualWeights=Arrays.copyOf(dualWeights,n); } begins[size]=begin;counts[size]=count;dualWeights[size++]=dualWeight; }
    SkinVertexList toList() { return new SkinVertexList(Arrays.copyOf(begins,size),Arrays.copyOf(counts,size),Arrays.copyOf(dualWeights,size)); }
  }
  private static final class SkinWeightBuilder {
    private int[] clusters=new int[1024];private double[] weights=new double[1024];private int size;
    void add(int cluster,double weight) { if(size==clusters.length) { int n=Math.multiplyExact(size,2);clusters=Arrays.copyOf(clusters,n);weights=Arrays.copyOf(weights,n); } clusters[size]=cluster;weights[size++]=weight; }
    SkinWeightList toList() { return new SkinWeightList(Arrays.copyOf(clusters,size),Arrays.copyOf(weights,size)); }
  }
  private static List<FbxDetails.BlendShape> readBlendShapes(JsonReader reader,boolean compact) throws IOException {
    var out=new ArrayList<FbxDetails.BlendShape>();reader.beginArray();while(reader.hasNext()) out.add(readBlendShape(reader,compact));reader.endArray();return List.copyOf(out);
  }
  private static FbxDetails.BlendShape readBlendShape(JsonReader reader,boolean compact) throws IOException {
    int element=0;IntData vertices=IntData.EMPTY;DoubleData positions=DoubleData.EMPTY,normals=DoubleData.EMPTY,weights=DoubleData.EMPTY;
    reader.beginObject();while(reader.hasNext()) switch(reader.nextName()) {
      case "element" -> element=reader.nextInt();case "vertices" -> {if(compact)reader.skipValue();else vertices=integers(reader);}case "positions" -> {if(compact)reader.skipValue();else positions=floats(reader);}
      case "normals" -> {if(compact)reader.skipValue();else normals=floats(reader);}case "weights" -> {if(compact)reader.skipValue();else weights=floats(reader);}default -> reader.skipValue();
    }reader.endObject();return new FbxDetails.BlendShape(element,vertices,positions,normals,weights);
  }
  private static Mesh readMesh(JsonReader reader) throws IOException {
    int element=0,cache=0,subdivision=0;boolean generated=false;DoubleData positions=DoubleData.EMPTY,normals=DoubleData.EMPTY,positionW=DoubleData.EMPTY,normalW=DoubleData.EMPTY;
    IntData controls=IntData.EMPTY,skins=IntData.EMPTY,blends=IntData.EMPTY,all=IntData.EMPTY;var uvs=new ArrayList<UvSet>();var colors=new ArrayList<ColorSet>();var faces=new ArrayList<Face>();
    reader.beginObject();while(reader.hasNext()) switch(reader.nextName()) {
      case "element" -> element=reader.nextInt();case "position" -> positions=numbers(reader);case "normal" -> normals=numbers(reader);
      case "positionW" -> positionW=numbers(reader);case "normalW" -> normalW=numbers(reader);case "controlVertices" -> controls=integers(reader);
      case "uvSets" -> { reader.beginArray();while(reader.hasNext()) uvs.add(readUvSet(reader));reader.endArray(); }
      case "colorSets" -> { reader.beginArray();while(reader.hasNext()) colors.add(readColorSet(reader));reader.endArray(); }
      case "faces" -> { reader.beginArray();while(reader.hasNext()) faces.add(readFace(reader));reader.endArray(); }
      case "skinDeformers" -> skins=integers(reader);case "blendDeformers" -> blends=integers(reader);case "allDeformers" -> all=integers(reader);
      case "cacheDeformers" -> cache=reader.nextInt();case "subdivisionLevel" -> subdivision=reader.nextInt();case "generatedNormals" -> generated=reader.nextBoolean();default -> reader.skipValue();
    }reader.endObject();
    return new Mesh(element,positions,normals,controls,List.copyOf(uvs),List.copyOf(colors),List.copyOf(faces),skins,blends,cache,subdivision,all,positionW,normalW,generated);
  }
  private static UvSet readUvSet(JsonReader reader) throws IOException {
    String name="";DoubleData uv=DoubleData.EMPTY,tangent=DoubleData.EMPTY,bitangent=DoubleData.EMPTY,tangentW=DoubleData.EMPTY,bitangentW=DoubleData.EMPTY;
    reader.beginObject();while(reader.hasNext()) switch(reader.nextName()) { case "name" -> name=reader.nextString();case "uv" -> uv=numbers(reader);case "tangent" -> tangent=numbers(reader);case "bitangent" -> bitangent=numbers(reader);case "tangentW" -> tangentW=numbers(reader);case "bitangentW" -> bitangentW=numbers(reader);default -> reader.skipValue(); }reader.endObject();
    return new UvSet(name,uv,tangent,bitangent,tangentW,bitangentW);
  }
  private static ColorSet readColorSet(JsonReader reader) throws IOException {
    String name="";DoubleData color=DoubleData.EMPTY;reader.beginObject();while(reader.hasNext()) switch(reader.nextName()) { case "name" -> name=reader.nextString();case "color" -> color=numbers(reader);default -> reader.skipValue(); }reader.endObject();return new ColorSet(name,color);
  }
  private static Face readFace(JsonReader reader) throws IOException {
    int begin=0,count=0,material=0;IntData triangles=IntData.EMPTY;reader.beginObject();while(reader.hasNext()) switch(reader.nextName()) { case "begin" -> begin=reader.nextInt();case "count" -> count=reader.nextInt();case "material" -> material=reader.nextInt();case "triangles" -> triangles=integers(reader);default -> reader.skipValue(); }reader.endObject();return new Face(begin,count,material,triangles);
  }
  private static DoubleData numbers(JsonReader reader) throws IOException { var out=new DoubleBuilder();readNumbers(reader,out);return out.toData(); }
  /** Blendshape payloads originate as float vertex deltas in FBX. Read them
   * directly into compact storage so a large morph never needs a temporary
   * double array followed by a second conversion. */
  private static DoubleData floats(JsonReader reader) throws IOException { var out=new FloatBuilder();readFloats(reader,out);return out.toData(); }
  private static void readFloats(JsonReader reader,FloatBuilder out) throws IOException { if(reader.peek()==com.google.gson.stream.JsonToken.BEGIN_ARRAY){reader.beginArray();while(reader.hasNext())readFloats(reader,out);reader.endArray();}else out.add(reader.nextDouble()); }
  private static void readNumbers(JsonReader reader,DoubleBuilder out) throws IOException { if(reader.peek()==com.google.gson.stream.JsonToken.BEGIN_ARRAY){reader.beginArray();while(reader.hasNext())readNumbers(reader,out);reader.endArray();}else out.add(reader.nextDouble()); }
  private static IntData integers(JsonReader reader) throws IOException { var out=new IntBuilder();readIntegers(reader,out);return out.toData(); }
  private static void readIntegers(JsonReader reader,IntBuilder out) throws IOException { if(reader.peek()==com.google.gson.stream.JsonToken.BEGIN_ARRAY){reader.beginArray();while(reader.hasNext())readIntegers(reader,out);reader.endArray();}else out.add(reader.nextInt()); }
  private static final class DoubleBuilder { private double[] values=new double[1024];private int size;void add(double value){if(!Double.isFinite(value))throw new IllegalArgumentException("Non-finite FBX value");if(size==values.length)values=Arrays.copyOf(values,Math.multiplyExact(values.length,2));values[size++]=value;}DoubleData toData(){return DoubleData.owned(Arrays.copyOf(values,size));} }
  private static final class FloatBuilder { private float[] values=new float[1024];private int size;void add(double value){float f=(float)value;if(!Double.isFinite(value)||!Float.isFinite(f))throw new IllegalArgumentException("Non-finite FBX value");if(size==values.length)values=Arrays.copyOf(values,Math.multiplyExact(values.length,2));values[size++]=f;}DoubleData toData(){return DoubleData.ownedFloats(Arrays.copyOf(values,size));} }
  private static final class IntBuilder { private int[] values=new int[1024];private int size;void add(int value){if(size==values.length)values=Arrays.copyOf(values,Math.multiplyExact(values.length,2));values[size++]=value;}IntData toData(){return IntData.owned(Arrays.copyOf(values,size));} }
  private static Element element(JsonObject e) {
    var propertyLayers=new ArrayList<List<Property>>();for(var layer:e.getAsJsonArray("properties")) { var values=new ArrayList<Property>();for(var value:layer.getAsJsonArray()) { var p=value.getAsJsonObject();values.add(new Property(s(p,"name"),i(p,"type"),i(p,"flags"),Long.parseLong(s(p,"integer")),data(p,"value"),s(p,"string"),blob(p,"blob"))); }propertyLayers.add(values); }
    return new Element(i(e,"id"),i(e,"typedId"),i(e,"type"),s(e,"name"),propertyLayers,map(e,"connections",c->new Connection(i(c,"to"),s(c,"sourceProperty"),s(c,"targetProperty"))));
  }
  private static Node node(JsonObject n) { return new Node(i(n,"element"),i(n,"parent"),i(n,"mesh"),i(n,"light"),i(n,"camera"),b(n,"visible"),i(n,"inheritMode"),matrix(n,"local"),matrix(n,"world"),matrix(n,"geometryLocal"),matrix(n,"geometryWorld"),ints(n,"materials")); }
  private static Mesh mesh(JsonObject m) { return new Mesh(i(m,"element"),data(m,"position"),data(m,"normal"),ints(m,"controlVertices"),map(m,"uvSets",v->new UvSet(s(v,"name"),data(v,"uv"),data(v,"tangent"),data(v,"bitangent"),data(v,"tangentW"),data(v,"bitangentW"))),map(m,"colorSets",v->new ColorSet(s(v,"name"),data(v,"color"))),map(m,"faces",v->new Face(i(v,"begin"),i(v,"count"),i(v,"material"),ints(v,"triangles"))),ints(m,"skinDeformers"),ints(m,"blendDeformers"),i(m,"cacheDeformers"),i(m,"subdivisionLevel"),ints(m,"allDeformers"),data(m,"positionW"),data(m,"normalW"),b(m,"generatedNormals")); }
  private static Skin skin(JsonObject v) { return new Skin(i(v,"element"),i(v,"method"),map(v,"clusters",c->new Cluster(i(c,"element"),i(c,"bone"),matrix(c,"geometryToBone"),matrix(c,"meshToBone"),matrix(c,"bindWorld"),s(c,"linkMode"),ints(c,"rawVertices"),data(c,"rawWeights"))),arrays(v,"vertices",a->new SkinVertex(integer(a.get(0)),integer(a.get(1)),number(a.get(2)))),arrays(v,"weights",a->new SkinWeight(integer(a.get(0)),number(a.get(1))))); }
  private static Animation animation(JsonObject v) { return new Animation(i(v,"element"),d(v,"begin"),d(v,"end"),ints(v,"layers")); }
  private static Layer layer(JsonObject v) { return new Layer(i(v,"element"),d(v,"weight"),b(v,"blended"),b(v,"additive"),b(v,"composeRotation"),b(v,"composeScale"),map(v,"properties",p->new AnimatedProperty(i(p,"element"),s(p,"name"),data(p,"default"),ints(p,"curves")))); }
  private static Curve curve(JsonObject v) { return new Curve(i(v,"element"),v.getAsJsonArray("pre").get(0).getAsInt(),v.getAsJsonArray("pre").get(1).getAsInt(),v.getAsJsonArray("post").get(0).getAsInt(),v.getAsJsonArray("post").get(1).getAsInt(),arrays(v,"keys",a->new Key(number(a.get(0)),number(a.get(1)),integer(a.get(2)),number(a.get(3)),number(a.get(4)),number(a.get(5)),number(a.get(6))))); }
  private static Texture texture(JsonObject v) { return new Texture(i(v,"element"),i(v,"type"),s(v,"relativePath"),s(v,"absolutePath"),blob(v,"rawRelativePath"),blob(v,"rawAbsolutePath"),blob(v,"content"),s(v,"uvSet"),integer(v.getAsJsonArray("wrap").get(0)),integer(v.getAsJsonArray("wrap").get(1)),matrix(v,"uvTransform"),arrays(v,"layers",a->new TextureLayer(integer(a.get(0)),integer(a.get(1)),number(a.get(2))))); }
  private static FbxDetails.BlendShape shape(JsonObject v) { return new FbxDetails.BlendShape(i(v,"element"),ints(v,"vertices"),data(v,"positions"),data(v,"normals"),data(v,"weights")); }
  static FbxDocument document(ByteData source,JsonObject root) {
    var info=new Info(i(root,"version"),b(root,"ascii"),s(root,"creator"),d(root,"sourceUnitMeters"),d(root,"framesPerSecond"));
    var meshes=map(root,"meshes",m->new Mesh(i(m,"element"),data(m,"position"),data(m,"normal"),ints(m,"controlVertices"),
        map(m,"uvSets",v->new UvSet(s(v,"name"),data(v,"uv"),data(v,"tangent"),data(v,"bitangent"),data(v,"tangentW"),data(v,"bitangentW"))),
        map(m,"colorSets",v->new ColorSet(s(v,"name"),data(v,"color"))),
        map(m,"faces",v->new Face(i(v,"begin"),i(v,"count"),i(v,"material"),ints(v,"triangles"))),
        ints(m,"skinDeformers"),ints(m,"blendDeformers"),i(m,"cacheDeformers"),i(m,"subdivisionLevel"),ints(m,"allDeformers"),data(m,"positionW"),data(m,"normalW"),b(m,"generatedNormals")));
    var skins=map(root,"skins",v->new Skin(i(v,"element"),i(v,"method"),
        map(v,"clusters",c->new Cluster(i(c,"element"),i(c,"bone"),matrix(c,"geometryToBone"),matrix(c,"meshToBone"),matrix(c,"bindWorld"),s(c,"linkMode"),ints(c,"rawVertices"),data(c,"rawWeights"))),
        arrays(v,"vertices",a->new SkinVertex(integer(a.get(0)),integer(a.get(1)),number(a.get(2)))),
        arrays(v,"weights",a->new SkinWeight(integer(a.get(0)),number(a.get(1))))));
    var animations=map(root,"animations",v->new Animation(i(v,"element"),d(v,"begin"),d(v,"end"),ints(v,"layers")));
    var layers=map(root,"animationLayers",v->new Layer(i(v,"element"),d(v,"weight"),b(v,"blended"),b(v,"additive"),b(v,"composeRotation"),b(v,"composeScale"),
        map(v,"properties",p->new AnimatedProperty(i(p,"element"),s(p,"name"),data(p,"default"),ints(p,"curves")))));
    var curves=map(root,"curves",v->new Curve(i(v,"element"),v.getAsJsonArray("pre").get(0).getAsInt(),v.getAsJsonArray("pre").get(1).getAsInt(),
        v.getAsJsonArray("post").get(0).getAsInt(),v.getAsJsonArray("post").get(1).getAsInt(),
        arrays(v,"keys",a->new Key(number(a.get(0)),number(a.get(1)),integer(a.get(2)),number(a.get(3)),number(a.get(4)),number(a.get(5)),number(a.get(6))))));
    var shapes=map(root,"blendShapes",v->new FbxDetails.BlendShape(i(v,"element"),ints(v,"vertices"),data(v,"positions"),data(v,"normals"),data(v,"weights")));
    var details=details(root);
    var coverage=new CompatibilityReport(List.of(new CompatibilityReport.Feature("fbx.source",CompatibilityReport.Level.READ,true,
        "Typed source records and original bytes retained; evaluation coverage is reported by a separate session")),
        details.warnings().stream().map(w->new CompatibilityReport.Diagnostic(CompatibilityReport.Severity.WARNING,"fbx.element."+w.element(),w.description()+" (count "+w.count()+")")).toList());
    return new FbxDocument(source,info,elements(root),nodes(root),meshes,skins,animations,layers,curves,textures(root),shapes,details,coverage);
  }
  static List<Texture> textures(JsonObject root) {
    return map(root,"textures",v->new Texture(i(v,"element"),i(v,"type"),s(v,"relativePath"),s(v,"absolutePath"),blob(v,"rawRelativePath"),blob(v,"rawAbsolutePath"),
        blob(v,"content"),s(v,"uvSet"),integer(v.getAsJsonArray("wrap").get(0)),integer(v.getAsJsonArray("wrap").get(1)),matrix(v,"uvTransform"),
        arrays(v,"layers",a->new TextureLayer(integer(a.get(0)),integer(a.get(1)),number(a.get(2))))));
  }
  static FbxDetails.State details(JsonObject root) {
    return new FbxDetails.State(
        map(root,"blendDeformers",v->new FbxDetails.BlendDeformer(i(v,"element"),ints(v,"channels"))),
        map(root,"blendChannels",v->new FbxDetails.BlendChannel(i(v,"element"),d(v,"weight"),arrays(v,"keys",a->new FbxDetails.BlendKey(integer(a.get(0)),number(a.get(1)),number(a.get(2)))))),
        map(root,"materials",v->new FbxDetails.Material(i(v,"element"),i(v,"shaderType"),i(v,"shader"),s(v,"shadingModel"),s(v,"shaderPrefix"),materialMaps(v,"fbx"),materialMaps(v,"pbr"),
            dictionary(v,"features",a->new FbxDetails.MaterialFeature(a.getAsJsonArray().get(0).getAsBoolean(),a.getAsJsonArray().get(1).getAsBoolean())),
            map(v,"textures",t->new FbxDetails.MaterialTexture(s(t,"property"),s(t,"shaderProperty"),i(t,"texture"))))),
        map(root,"lights",v->new FbxDetails.Light(i(v,"element"),i(v,"type"),i(v,"decay"),i(v,"areaShape"),b(v,"castLight"),b(v,"castShadows"),data(v,"color"),d(v,"intensity"),data(v,"direction"),d(v,"innerAngle"),d(v,"outerAngle"))),
        map(root,"cameras",v->new FbxDetails.Camera(i(v,"element"),i(v,"projection"),b(v,"resolutionPixels"),ints(v,"axes"),ints(v,"modes"),dictionary(v,"parameters",a->doubleArray(a.getAsJsonArray())))),
        map(root,"constraints",v->new FbxDetails.Constraint(i(v,"element"),i(v,"type"),i(v,"node"),s(v,"typeName"),b(v,"active"),d(v,"weight"),ints(v,"axes"),transform(v.getAsJsonObject("offset")),
            map(v,"targets",t->new FbxDetails.ConstraintTarget(i(t,"node"),d(t,"weight"),transform(t.getAsJsonObject("transform")))),
            data(v,"aim"),data(v,"upVector"),data(v,"pole"),i(v,"upType"),i(v,"upNode"),i(v,"effector"),i(v,"end"))),
        map(root,"warnings",v->new FbxDetails.Warning(i(v,"type"),i(v,"element"),i(v,"count"),s(v,"description"))));
  }
  private static FbxDetails.Transform transform(JsonObject v) { return new FbxDetails.Transform(data(v,"translation"),data(v,"rotation"),data(v,"scale")); }
  private static Map<String,FbxDetails.MaterialMap> materialMaps(JsonObject v,String name) {
    return dictionary(v,name,a->{ var m=a.getAsJsonObject();return new FbxDetails.MaterialMap(data(m,"value"),Long.parseLong(s(m,"integer")),i(m,"texture"),b(m,"hasValue"),b(m,"textureEnabled"),b(m,"disabled"),i(m,"components")); });
  }
  private static <T> Map<String,T> dictionary(JsonObject v,String name,Function<JsonElement,T> fn) {
    var out=new LinkedHashMap<String,T>();for(var e:v.getAsJsonObject(name).entrySet()) out.put(e.getKey(),fn.apply(e.getValue()));return Map.copyOf(out);
  }
  static List<Element> elements(JsonObject root) {
    return map(root,"elements",e-> {
      var propertyLayers=new ArrayList<List<Property>>();for(var layer:e.getAsJsonArray("properties")) {
        var values=new ArrayList<Property>();for(var value:layer.getAsJsonArray()) { var p=value.getAsJsonObject();
          values.add(new Property(s(p,"name"),i(p,"type"),i(p,"flags"),Long.parseLong(s(p,"integer")),data(p,"value"),s(p,"string"),blob(p,"blob")));
        }propertyLayers.add(values);
      }
      return new Element(i(e,"id"),i(e,"typedId"),i(e,"type"),s(e,"name"),propertyLayers,map(e,"connections",c->new Connection(i(c,"to"),s(c,"sourceProperty"),s(c,"targetProperty"))));
    });
  }
  static List<Node> nodes(JsonObject root) {
    return map(root,"nodes",n->new Node(i(n,"element"),i(n,"parent"),i(n,"mesh"),i(n,"light"),i(n,"camera"),b(n,"visible"),i(n,"inheritMode"),
        matrix(n,"local"),matrix(n,"world"),matrix(n,"geometryLocal"),matrix(n,"geometryWorld"),ints(n,"materials")));
  }
  static FbxEvaluation.Frame frame(JsonObject root,double seconds,CompatibilityReport coverage) {
    return frame(root, null, seconds, coverage);
  }
  static FbxEvaluation.Frame frame(JsonObject root,FbxDocument source,double seconds,CompatibilityReport coverage) {
    var sourceElements=source==null?elements(root):source.elements();
    var sourceDetails=source==null?details(root):dynamicDetails(root,source.details());
    var sourceTextures=source==null?textures(root):source.textures();
    List<FbxEvaluation.MeshFrame> frameMeshes=root.has("meshes")?map(root,"meshes",m->new FbxEvaluation.MeshFrame(data(m,"position"),data(m,"normal"),true)):List.<FbxEvaluation.MeshFrame>of();
    return new FbxEvaluation.Frame(seconds,nodes(root),frameMeshes,sourceElements,sourceDetails,sourceTextures,
        map(root,"draws",d->new FbxEvaluation.DrawFrame(i(d,"node"),i(d,"mesh"),data(d,"positions"),data(d,"normals"),
            map(d,"uvDirections",v->new FbxEvaluation.UvDirections(data(v,"tangents"),data(v,"bitangents"))))),coverage);
  }
  static FbxDetails.State dynamicDetails(JsonObject root,FbxDetails.State source) {
    if(root.has("materials")) return details(root);
    var lights=root.has("lights")?map(root,"lights",v->new FbxDetails.Light(i(v,"element"),i(v,"type"),i(v,"decay"),i(v,"areaShape"),b(v,"castLight"),b(v,"castShadows"),data(v,"color"),d(v,"intensity"),data(v,"direction"),d(v,"innerAngle"),d(v,"outerAngle"))):source.lights();
    var cameras=root.has("cameras")?map(root,"cameras",v->new FbxDetails.Camera(i(v,"element"),i(v,"projection"),b(v,"resolutionPixels"),ints(v,"axes"),ints(v,"modes"),dictionary(v,"parameters",a->doubleArray(a.getAsJsonArray())))):source.cameras();
    return new FbxDetails.State(source.blendDeformers(),source.blendChannels(),source.materials(),lights,cameras,source.constraints(),source.warnings());
  }
  private static <T> List<T> map(JsonObject owner,String name,Function<JsonObject,T> fn) {
    var out=new ArrayList<T>();for(var value:owner.getAsJsonArray(name)) out.add(fn.apply(value.getAsJsonObject()));return List.copyOf(out);
  }
  private static <T> List<T> arrays(JsonObject owner,String name,Function<JsonArray,T> fn) {
    var out=new ArrayList<T>();for(var value:owner.getAsJsonArray(name)) out.add(fn.apply(value.getAsJsonArray()));return List.copyOf(out);
  }
  private static int i(JsonObject owner,String name) { return integer(owner.get(name)); }
  private static int integer(JsonElement value) { return value.getAsBigDecimal().intValueExact(); }
  private static double d(JsonObject owner,String name) { return number(owner.get(name)); }
  private static double number(JsonElement value) { double n=value.getAsDouble();if(!Double.isFinite(n)) throw new IllegalArgumentException("Non-finite FBX value");return n; }
  private static boolean b(JsonObject owner,String name) { return owner.get(name).getAsBoolean(); }
  private static String s(JsonObject owner,String name) { return owner.get(name).getAsString(); }
  private static ByteData blob(JsonObject owner,String name) { return new ByteData(Base64.getDecoder().decode(s(owner,name))); }
  private static DoubleData matrix(JsonObject owner,String name) { var values=data(owner,name);if(values.size()!=12) throw new IllegalArgumentException("Invalid FBX matrix shape");return values; }
  private static IntData ints(JsonObject owner,String name) {
    var values=owner.getAsJsonArray(name);int[] out=new int[values.size()];for(int i=0;i<out.length;i++) out[i]=integer(values.get(i));return IntData.owned(out);
  }
  private static DoubleData data(JsonObject owner,String name) {
    return doubleArray(owner.getAsJsonArray(name));
  }
  private static DoubleData doubleArray(JsonArray array) {
    int width=array.size()!=0&&array.get(0).isJsonArray()?array.get(0).getAsJsonArray().size():1;
    double[] out=new double[Math.multiplyExact(array.size(),width)];int i=0;
    for(var value:array) { if(value.isJsonArray()) { if(value.getAsJsonArray().size()!=width) throw new IllegalArgumentException("Ragged FBX array");
        for(var scalar:value.getAsJsonArray()) out[i++]=number(scalar);
      } else out[i++]=number(value); }
    return DoubleData.owned(out);
  }
}
