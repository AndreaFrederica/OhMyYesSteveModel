package cc.sirrus.ysmlib.scene.fbx;

import cc.sirrus.ysmlib.scene.*;
import java.util.*;

/** Explicit float draw projection. Lossless source doubles stay in FbxDocument/Frame. No secondary skin evaluation. */
public final class FbxGeometry {
  /** Converted attributes that are invariant across animation samples. */
  private static final Map<FbxDocument, Map<Integer, Map<String, MeshAsset.Attribute>>> STATIC_ATTRIBUTES =
      Collections.synchronizedMap(new WeakHashMap<>());

  public GeometryFrame compile(FbxDocument source,FbxEvaluation.Frame frame) {
    return compile(source,frame,false);
  }

  /**
   * Builds an undeformed mesh with FBX cluster weights attached. The normal
   * playback path intentionally remains evaluated to preserve compatibility;
   * native/GPU callers can use this entry point to deform from a rest pose.
   */
  public GeometryFrame compileRest(FbxDocument source,FbxEvaluation.Frame frame) {
    Objects.requireNonNull(source); Objects.requireNonNull(frame);
    var draws=new ArrayList<FbxEvaluation.DrawFrame>();
    for (var draw : frame.draws()) {
      var mesh=source.meshes().get(draw.mesh());
      var directions=new ArrayList<FbxEvaluation.UvDirections>();
      for (var uv : mesh.uvSets()) directions.add(new FbxEvaluation.UvDirections(uv.tangents(),uv.bitangents()));
      draws.add(new FbxEvaluation.DrawFrame(draw.node(),draw.mesh(),mesh.positions(),mesh.normals(),directions));
    }
    var rest=new FbxEvaluation.Frame(frame.seconds(),frame.nodes(),frame.meshes(),frame.elements(),frame.details(),frame.textures(),draws,frame.compatibility());
    return compile(source,rest,true);
  }

  /**
   * Compiles a native frame directly from an immutable off-heap snapshot.
   * Native evaluation already produces float attributes; materializing a
   * DoubleData frame here would only create a transient heap expansion.
   */
  GeometryFrame compileNative(FbxDocument source,FbxNativePlayer player,FbxNativePlayer.NativeGeometrySnapshot snapshot,double seconds) {
    Objects.requireNonNull(source);Objects.requireNonNull(player);Objects.requireNonNull(snapshot);
    var names=new HashMap<Integer,String>();source.elements().forEach(e->names.put(e.id(),e.name()));
    var draws=new ArrayList<GeometryFrame.Draw>();var cachedAll=staticAttributes(source);
    int po=0,no=0,to=0,bo=0;
    for(int di=0;di<player.layoutDraws();di++) {
      int nodeIndex=player.drawNode(di),meshIndex=player.drawMesh(di);
      if(nodeIndex<0||nodeIndex>=source.nodes().size()||meshIndex<0||meshIndex>=source.meshes().size()) throw new IllegalStateException("Native FBX draw index out of range");
      var node=source.nodes().get(nodeIndex);var mesh=source.meshes().get(meshIndex);int corners=mesh.cornerCount();
      var attributes=new LinkedHashMap<String,MeshAsset.Attribute>();
      attributes.put("POSITION",new MeshAsset.Attribute(3,player.direct(snapshot.positions(),po,corners*3)));po+=corners*3;
      if(mesh.normals().size()!=0){attributes.put("NORMAL",new MeshAsset.Attribute(3,player.direct(snapshot.normals(),no,corners*3)));no+=corners*3;}
      attributes.putAll(cachedAll.getOrDefault(meshIndex,Map.of()));
      var uvDirections=new ArrayList<FloatData>();
      for(int i=0;i<mesh.uvSets().size();i++) {
        var uv=mesh.uvSets().get(i);FloatData t=FloatData.EMPTY,b=FloatData.EMPTY;
        if(uv.tangents().size()!=0){t=player.direct(snapshot.tangents(),to,corners*3);to+=corners*3;attributes.put("_FBX_TANGENT_"+i,new MeshAsset.Attribute(3,t));}
        if(uv.bitangents().size()!=0){b=player.direct(snapshot.bitangents(),bo,corners*3);bo+=corners*3;attributes.put("_FBX_BITANGENT_"+i,new MeshAsset.Attribute(3,b));}
        uvDirections.add(t);
        if(i==0&&t.size()!=0&&b.size()!=0&&mesh.normals().size()!=0){
          var n=attributes.get("NORMAL").values();float[] packed=new float[corners*4];
          for(int v=0;v<corners;v++){int o=v*3;for(int k=0;k<3;k++)packed[v*4+k]=t.get(o+k);double sign=(n.get(o+1)*t.get(o+2)-n.get(o+2)*t.get(o+1))*b.get(o)+(n.get(o+2)*t.get(o)-n.get(o)*t.get(o+2))*b.get(o+1)+(n.get(o)*t.get(o+1)-n.get(o+1)*t.get(o))*b.get(o+2);packed[v*4+3]=sign<0?-1:1;}
          attributes.put("TANGENT",new MeshAsset.Attribute(4,FloatData.owned(packed)));
        }
      }
      var sharedAttributes=Map.copyOf(attributes);
      var primitives=new ArrayList<MeshAsset.Primitive>();var indices=new ArrayList<Integer>();Key previous=null;
      for(var face:mesh.faces()){if(face.count()==0)continue;var topology=face.count()==1?MeshAsset.Topology.POINTS:face.count()==2?MeshAsset.Topology.LINES:MeshAsset.Topology.TRIANGLES;int material=node.materials().size()==0?-1:node.materials().get(face.material());var key=new Key(topology,material);if(!key.equals(previous)&&previous!=null){primitives.add(primitive(previous,sharedAttributes,indices,null));indices.clear();}if(face.count()<3)for(int i=0;i<face.count();i++)indices.add(face.begin()+i);else for(int i=0;i<face.triangles().size();i++)indices.add(face.triangles().get(i));previous=key;}
      if(previous!=null)primitives.add(primitive(previous,sharedAttributes,indices,null));
      draws.add(new GeometryFrame.Draw(nodeIndex,meshIndex,Matrix4.IDENTITY,new MeshAsset(names.getOrDefault(mesh.element(),""),primitives,FloatData.EMPTY),node.visible()));
    }
    return new GeometryFrame(seconds,SceneAsset.Coordinates.GLTF,draws);
  }

  private GeometryFrame compile(FbxDocument source,FbxEvaluation.Frame frame,boolean attachSkinning) {
    Objects.requireNonNull(source);Objects.requireNonNull(frame);
    if(frame.nodes().size()!=source.nodes().size()) throw new IllegalArgumentException("FBX frame belongs to a different source");
    var names=new HashMap<Integer,String>();source.elements().forEach(e->names.put(e.id(),e.name()));var draws=new ArrayList<GeometryFrame.Draw>();
    for(var draw:frame.draws()) {
      var node=frame.nodes().get(draw.node());var mesh=source.meshes().get(draw.mesh());
      if(node.mesh()!=draw.mesh() || draw.positions().size()!=mesh.positions().size()) throw new IllegalArgumentException("FBX draw source mismatch");
      var attributes=new LinkedHashMap<String,MeshAsset.Attribute>();
      var cached=staticAttributes(source).get(draw.mesh());
      put(attributes,"POSITION",3,draw.positions());put(attributes,"NORMAL",3,draw.normals());
      attributes.putAll(cached);
      for(int i=0;i<mesh.uvSets().size();i++) {
        var uv=mesh.uvSets().get(i);var directions=draw.uvDirections().get(i);
        put(attributes,"_FBX_TANGENT_"+i,3,directions.tangents());put(attributes,"_FBX_BITANGENT_"+i,3,directions.bitangents());
        if(i==0 && directions.tangents().size()!=0 && directions.bitangents().size()!=0 && draw.normals().size()!=0) {
          var t=directions.tangents();var b=directions.bitangents();var n=draw.normals();float[] packed=new float[mesh.cornerCount()*4];
          for(int v=0;v<mesh.cornerCount();v++) { int o=v*3;
            for(int k=0;k<3;k++) packed[v*4+k]=scalar(t.get(o+k));
            double sign=(n.get(o+1)*t.get(o+2)-n.get(o+2)*t.get(o+1))*b.get(o)
                +(n.get(o+2)*t.get(o)-n.get(o)*t.get(o+2))*b.get(o+1)+(n.get(o)*t.get(o+1)-n.get(o+1)*t.get(o))*b.get(o+2);
            packed[v*4+3]=sign<0?-1:1;
          }attributes.put("TANGENT",new MeshAsset.Attribute(4,FloatData.owned(packed)));
        }
      }
      // Preserve source face order. Group only consecutive runs; transparency may depend on order.
      var sharedAttributes=Map.copyOf(attributes);
      var primitives=new ArrayList<MeshAsset.Primitive>();var indices=new ArrayList<Integer>();Key previous=null;
      var skinning=attachSkinning?FbxSkinningCompiler.compile(source,draw.mesh()):null;
      for(var face:mesh.faces()) {
        if(face.count()==0) continue;
        var topology=face.count()==1?MeshAsset.Topology.POINTS:face.count()==2?MeshAsset.Topology.LINES:MeshAsset.Topology.TRIANGLES;
        int material=node.materials().size()==0?-1:node.materials().get(face.material());var key=new Key(topology,material);
        if(!key.equals(previous)&&previous!=null) { primitives.add(primitive(previous,sharedAttributes,indices,skinning));indices.clear(); }
        if(face.count()<3) for(int i=0;i<face.count();i++) indices.add(face.begin()+i);
        else for(int i=0;i<face.triangles().size();i++) indices.add(face.triangles().get(i));
        previous=key;
      }
      if(previous!=null) primitives.add(primitive(previous,sharedAttributes,indices,skinning));
      var geometry=new MeshAsset(names.getOrDefault(mesh.element(),""),primitives,FloatData.EMPTY);
      draws.add(new GeometryFrame.Draw(draw.node(),draw.mesh(),Matrix4.IDENTITY,geometry,node.visible()));
    }
    return new GeometryFrame(frame.seconds(),SceneAsset.Coordinates.GLTF,draws);
  }
  private Map<Integer,Map<String,MeshAsset.Attribute>> staticAttributes(FbxDocument source) {
    var cached=STATIC_ATTRIBUTES.get(source);if(cached!=null)return cached;
    var built=new LinkedHashMap<Integer,Map<String,MeshAsset.Attribute>>();
    for(int i=0;i<source.meshes().size();i++) {
      var mesh=source.meshes().get(i);var values=new LinkedHashMap<String,MeshAsset.Attribute>();
      put(values,"_FBX_POSITION_W",1,mesh.positionW());put(values,"_FBX_NORMAL_W",1,mesh.normalW());
      for(int u=0;u<mesh.uvSets().size();u++) {
        var uv=mesh.uvSets().get(u);put(values,"TEXCOORD_"+u,2,uv.uv());
        put(values,"_FBX_TANGENT_W_"+u,1,uv.tangentW());put(values,"_FBX_BITANGENT_W_"+u,1,uv.bitangentW());
      }
      for(int c=0;c<mesh.colorSets().size();c++)put(values,"COLOR_"+c,4,mesh.colorSets().get(c).colors());
      built.put(i,Map.copyOf(values));
    }
    STATIC_ATTRIBUTES.put(source,built);return built;
  }
  private record Key(MeshAsset.Topology topology,int material) {}
  private static MeshAsset.Primitive primitive(Key key,Map<String,MeshAsset.Attribute> attributes,List<Integer> indices,MeshAsset.Skinning skinning) {
    // Skinning is defined over all corner vertices. Point/line/triangle runs
    // retain the same vertex table, so one compiled range is valid for every
    // material run of the mesh.
    return new MeshAsset.Primitive(key.topology(),attributes,IntData.owned(indices.stream().mapToInt(i->i).toArray()),key.material(),skinning,List.of());
  }
  private static void put(Map<String,MeshAsset.Attribute> out,String name,int width,DoubleData values) {
    if(values.size()==0) return;out.put(name,new MeshAsset.Attribute(width,values.floatView()));
  }
  private static float scalar(double v) { float out=(float)v;if(!Float.isFinite(out)) throw new IllegalArgumentException("FBX value exceeds draw precision range");return out; }
}
