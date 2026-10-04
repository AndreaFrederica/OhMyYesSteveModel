package cc.sirrus.ysmlib.scene.fbx;

import cc.sirrus.ysmlib.scene.*;
import java.util.*;

/** Explicit float draw projection. Lossless source doubles stay in FbxDocument/Frame. No secondary skin evaluation. */
public final class FbxGeometry {
  public GeometryFrame compile(FbxDocument source,FbxEvaluation.Frame frame) {
    Objects.requireNonNull(source);Objects.requireNonNull(frame);
    if(frame.nodes().size()!=source.nodes().size() || frame.meshes().size()!=source.meshes().size()) throw new IllegalArgumentException("FBX frame belongs to a different source");
    var names=new HashMap<Integer,String>();source.elements().forEach(e->names.put(e.id(),e.name()));var draws=new ArrayList<GeometryFrame.Draw>();
    for(var draw:frame.draws()) {
      var node=frame.nodes().get(draw.node());var mesh=source.meshes().get(draw.mesh());
      if(node.mesh()!=draw.mesh() || draw.positions().size()!=mesh.positions().size()) throw new IllegalArgumentException("FBX draw source mismatch");
      var attributes=new LinkedHashMap<String,MeshAsset.Attribute>();put(attributes,"POSITION",3,draw.positions());put(attributes,"NORMAL",3,draw.normals());
      put(attributes,"_FBX_POSITION_W",1,mesh.positionW());put(attributes,"_FBX_NORMAL_W",1,mesh.normalW());
      for(int i=0;i<mesh.uvSets().size();i++) {
        var uv=mesh.uvSets().get(i);var directions=draw.uvDirections().get(i);put(attributes,"TEXCOORD_"+i,2,uv.uv());
        put(attributes,"_FBX_TANGENT_"+i,3,directions.tangents());put(attributes,"_FBX_BITANGENT_"+i,3,directions.bitangents());
        put(attributes,"_FBX_TANGENT_W_"+i,1,uv.tangentW());put(attributes,"_FBX_BITANGENT_W_"+i,1,uv.bitangentW());
        if(i==0 && directions.tangents().size()!=0 && directions.bitangents().size()!=0 && draw.normals().size()!=0) {
          var t=directions.tangents();var b=directions.bitangents();var n=draw.normals();float[] packed=new float[mesh.cornerCount()*4];
          for(int v=0;v<mesh.cornerCount();v++) { int o=v*3;
            for(int k=0;k<3;k++) packed[v*4+k]=scalar(t.get(o+k));
            double sign=(n.get(o+1)*t.get(o+2)-n.get(o+2)*t.get(o+1))*b.get(o)
                +(n.get(o+2)*t.get(o)-n.get(o)*t.get(o+2))*b.get(o+1)+(n.get(o)*t.get(o+1)-n.get(o+1)*t.get(o))*b.get(o+2);
            packed[v*4+3]=sign<0?-1:1;
          }attributes.put("TANGENT",new MeshAsset.Attribute(4,new FloatData(packed)));
        }
      }
      for(int i=0;i<mesh.colorSets().size();i++) put(attributes,"COLOR_"+i,4,mesh.colorSets().get(i).colors());
      // Preserve source face order. Group only consecutive runs; transparency may depend on order.
      var primitives=new ArrayList<MeshAsset.Primitive>();var indices=new ArrayList<Integer>();Key previous=null;
      for(var face:mesh.faces()) {
        if(face.count()==0) continue;
        var topology=face.count()==1?MeshAsset.Topology.POINTS:face.count()==2?MeshAsset.Topology.LINES:MeshAsset.Topology.TRIANGLES;
        int material=node.materials().size()==0?-1:node.materials().get(face.material());var key=new Key(topology,material);
        if(!key.equals(previous)&&previous!=null) { primitives.add(primitive(previous,attributes,indices));indices.clear(); }
        if(face.count()<3) for(int i=0;i<face.count();i++) indices.add(face.begin()+i);
        else for(int i=0;i<face.triangles().size();i++) indices.add(face.triangles().get(i));
        previous=key;
      }
      if(previous!=null) primitives.add(primitive(previous,attributes,indices));
      var geometry=new MeshAsset(names.getOrDefault(mesh.element(),""),primitives,FloatData.EMPTY);
      draws.add(new GeometryFrame.Draw(draw.node(),draw.mesh(),Matrix4.IDENTITY,geometry,node.visible()));
    }
    return new GeometryFrame(frame.seconds(),SceneAsset.Coordinates.GLTF,draws);
  }
  private record Key(MeshAsset.Topology topology,int material) {}
  private static MeshAsset.Primitive primitive(Key key,Map<String,MeshAsset.Attribute> attributes,List<Integer> indices) {
    return new MeshAsset.Primitive(key.topology(),attributes,new IntData(indices.stream().mapToInt(i->i).toArray()),key.material(),null,List.of());
  }
  private static void put(Map<String,MeshAsset.Attribute> out,String name,int width,DoubleData values) {
    if(values.size()==0) return;float[] v=new float[values.size()];for(int i=0;i<v.length;i++) v[i]=scalar(values.get(i));out.put(name,new MeshAsset.Attribute(width,new FloatData(v)));
  }
  private static float scalar(double v) { float out=(float)v;if(!Float.isFinite(out)) throw new IllegalArgumentException("FBX value exceeds draw precision range");return out; }
}
