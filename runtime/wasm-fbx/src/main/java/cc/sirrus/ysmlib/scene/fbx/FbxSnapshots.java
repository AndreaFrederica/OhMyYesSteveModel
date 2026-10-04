package cc.sirrus.ysmlib.scene.fbx;

import cc.sirrus.ysmlib.scene.*;
import com.google.gson.*;
import java.util.*;
import java.util.function.Function;
import static cc.sirrus.ysmlib.scene.fbx.FbxDocument.*;

/** Private reactor transfer adapter. Public records never retain mutable JSON trees. */
final class FbxSnapshots {
  private FbxSnapshots() {}
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
    return new FbxEvaluation.Frame(seconds,nodes(root),map(root,"meshes",m->new FbxEvaluation.MeshFrame(data(m,"position"),data(m,"normal"),true)),elements(root),details(root),textures(root),
        map(root,"draws",d->new FbxEvaluation.DrawFrame(i(d,"node"),i(d,"mesh"),data(d,"positions"),data(d,"normals"),
            map(d,"uvDirections",v->new FbxEvaluation.UvDirections(data(v,"tangents"),data(v,"bitangents"))))),coverage);
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
    var values=owner.getAsJsonArray(name);int[] out=new int[values.size()];for(int i=0;i<out.length;i++) out[i]=integer(values.get(i));return new IntData(out);
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
    return new DoubleData(out);
  }
}
