package cc.sirrus.ysmlib.scene.fbx;

import cc.sirrus.ysmlib.scene.*;
import java.util.*;

/** Applies sidecar rigid-node poses to an already compiled FBX draw frame. */
public final class FbxPhysicsGeometry {
  private FbxPhysicsGeometry() {}
  /** Compiling FBX cluster tables is a source operation; keep one immutable result per mesh. */
  private static final Map<FbxDocument,Map<Integer,Optional<MeshAsset.Skinning>>> SKIN_CACHE =
      Collections.synchronizedMap(new WeakHashMap<>());
  public static GeometryFrame apply(FbxEvaluation.Frame frame,GeometryFrame geometry,Map<Integer,Pose> poses) {
    return apply(null,frame,geometry,poses);
  }
  /** Applies rigid node motion and, when source skin data is supplied, a bounded weighted bone delta. */
  public static GeometryFrame apply(FbxDocument source,FbxEvaluation.Frame frame,GeometryFrame geometry,Map<Integer,Pose> poses) {
    Objects.requireNonNull(frame);Objects.requireNonNull(geometry);Objects.requireNonNull(poses);if(poses.isEmpty())return geometry;
    return applyDeltas(source,frame,geometry,absoluteDeltas(frame,poses));
  }
  /** Applies target-local retarget deltas (the format emitted by SkeletonRetargeter). */
  public static GeometryFrame applyLocalDeltas(FbxDocument source,FbxEvaluation.Frame frame,GeometryFrame geometry,Map<Integer,Pose> poses) {
    Objects.requireNonNull(frame);Objects.requireNonNull(geometry);Objects.requireNonNull(poses);if(poses.isEmpty())return geometry;
    return applyDeltas(source,frame,geometry,deltas(frame,poses));
  }
  private static GeometryFrame applyDeltas(FbxDocument source,FbxEvaluation.Frame frame,GeometryFrame geometry,Map<Integer,float[]> deltas) {
    var draws=new ArrayList<GeometryFrame.Draw>(geometry.draws().size());
    for(var draw:geometry.draws()) {
      var nodeDelta=deltas.get(draw.node());
      var sourceHasSkin=source!=null&&hasSkin(source,draw.mesh());
      MeshAsset.Skinning compiled=sourceHasSkin?compiledSkin(source,draw.mesh()):null;
      boolean weighted=compiled!=null&&hasAffectedJoint(compiled,deltas);
      // A rigid body on a mesh node can be represented by the draw transform. This
      // avoids copying every vertex and keeps FBX static meshes on the native path.
      if(!weighted&&nodeDelta!=null) {
        draws.add(new GeometryFrame.Draw(draw.node(),draw.mesh(),new Matrix4(nodeDelta).multiply(draw.world()),draw.geometry(),draw.visible()));
        continue;
      }
      var primitives=new ArrayList<MeshAsset.Primitive>(draw.geometry().primitives().size());
      // FBX material runs normally share one corner-vertex table.  Keep that
      // table shared after a pose overlay too; transforming it once per
      // material used to multiply both CPU skinning and temporary allocations
      // by the material count.
      Map<String,MeshAsset.Attribute> sharedAttributes=null;
      if(weighted&&!draw.geometry().primitives().isEmpty()) {
        var first=draw.geometry().primitives().get(0).attributes();
        boolean sameTable=true;
        for(var candidate:draw.geometry().primitives()) if(candidate.attributes()!=first) {sameTable=false;break;}
        if(sameTable) sharedAttributes=transformAttributes(first,compiled,deltas);
      }
      for(var primitive:draw.geometry().primitives()) {
        MeshAsset.Skinning skin=sourceHasSkin?compiled:primitive.skinning();
        if(skin==null||!hasAffectedJoint(skin,deltas)) { primitives.add(primitive);continue; }
        var attributes=sharedAttributes;
        if(attributes==null) attributes=transformAttributes(primitive.attributes(),skin,deltas);
        primitives.add(new MeshAsset.Primitive(primitive.topology(),attributes,primitive.indices(),primitive.material(),primitive.skinning(),primitive.morphs()));
      }
      draws.add(new GeometryFrame.Draw(draw.node(),draw.mesh(),draw.world(),new MeshAsset(draw.geometry().name(),primitives,draw.geometry().defaultMorphWeights()),draw.visible()));
    }
    return new GeometryFrame(geometry.seconds(),geometry.coordinates(),draws);
  }
  private static Map<String,MeshAsset.Attribute> transformAttributes(Map<String,MeshAsset.Attribute> input,MeshAsset.Skinning skin,Map<Integer,float[]> deltas) {
    var attributes=new LinkedHashMap<String,MeshAsset.Attribute>();
    for(var entry:input.entrySet()) attributes.put(entry.getKey(),transform(entry.getKey(),entry.getValue(),skin,deltas));
    return Map.copyOf(attributes);
  }
  private static boolean hasSkin(FbxDocument source,int mesh){return source!=null&&mesh>=0&&mesh<source.meshes().size()&&source.meshes().get(mesh).skins().size()!=0;}
  private static MeshAsset.Skinning compiledSkin(FbxDocument source,int mesh) {
    synchronized(SKIN_CACHE) {
      var byMesh=SKIN_CACHE.get(source);if(byMesh==null){byMesh=new HashMap<>();SKIN_CACHE.put(source,byMesh);}
      var existing=byMesh.get(mesh);if(existing!=null)return existing.orElse(null);
      MeshAsset.Skinning built;try { built=FbxSkinningCompiler.compile(source,mesh); }
      catch(IllegalArgumentException unsupportedSkin) { byMesh.put(mesh,Optional.empty());return null; }
      byMesh.put(mesh,Optional.ofNullable(built));return built;
    }
  }
  /**
   * Converts the local target deltas produced by SkeletonRetargeter into
   * world-space deltas required by FBX skinning.  A Pose supplied through
   * ScenePackagePlayer is relative to the target bone's FBX rest local
   * transform; treating it as an absolute world pose applies the inverse rest
   * matrix a second time and tears the whole avatar apart even at rest.
   */
  private static Map<Integer,float[]> absoluteDeltas(FbxEvaluation.Frame frame,Map<Integer,Pose> poses) {
    var out=new HashMap<Integer,float[]>(poses.size());
    for(var entry:poses.entrySet()) { int node=entry.getKey();if(node<0||node>=frame.nodes().size())continue;
      out.put(node,entry.getValue().multiply(FbxPhysicsBinding.nodePose(frame.nodes().get(node)).inverse()).matrix().copy()); }
    return out;
  }
  static Map<Integer,float[]> deltas(FbxEvaluation.Frame frame,Map<Integer,Pose> poses) {
    var out=new HashMap<Integer,float[]>(poses.size());
    var current=new Matrix4[frame.nodes().size()];
    var visiting=new byte[current.length];
    for(var entry:poses.entrySet()) {
      int node=entry.getKey(); if(node<0||node>=frame.nodes().size()) continue;
      // YSM's reference pose is represented by zero translation and the
      // identity quaternion.  It must produce no FBX deformation at all;
      // walking FBX's exporter-specific local/world hierarchy for this case
      // can differ because of inherit modes and geometry compensation.
      var pose=entry.getValue();
      if(pose.position().equals(Vec3.ZERO) && pose.rotation().equals(Rotation.IDENTITY)) {
        out.put(node,Matrix4.IDENTITY.copy());
        continue;
      }
      worldCurrent(frame,node,poses,current,visiting);
      // The evaluator already provides the authoritative column-major FBX
      // world matrix.  Converting it through Pose/quaternion here loses the
      // source basis (and, for Blender exports, can transpose the rotation),
      // so a zero local retarget delta would become a large non-identity
      // matrix and collapse the skinned mesh.  Compare against the raw matrix.
      var rest=affine(frame.nodes().get(node).world());
      out.put(node,current[node].multiply(rest.inverse()).copy());
    }
    return out;
  }
  private static Matrix4 worldCurrent(FbxEvaluation.Frame frame,int index,Map<Integer,Pose> poses,Matrix4[] current,byte[] visiting) {
    if(current[index]!=null)return current[index];
    if(visiting[index]==1)throw new IllegalArgumentException("Cyclic FBX node hierarchy");
    visiting[index]=1;var node=frame.nodes().get(index);
    Matrix4 parent=Matrix4.IDENTITY;
    if(node.parent()>=0) parent=worldCurrent(frame,node.parent(),poses,current,visiting);
    Matrix4 local=node.local().size()==12?affine(node.local()):FbxPhysicsBinding.nodePose(node).matrix();
    var delta=poses.get(index);
    if(delta!=null) {
      // Retargeted positions are local offsets in the parent's coordinates;
      // rotations are post-rest local rotations.
      var translated=new float[16];
      translated[0]=translated[5]=translated[10]=translated[15]=1;
      translated[12]=delta.position().x();translated[13]=delta.position().y();translated[14]=delta.position().z();
      local=new Matrix4(translated).multiply(local).multiply(new Transform(Vec3.ZERO,delta.rotation(),Vec3.ONE).matrix());
    }
    current[index]=parent.multiply(local);visiting[index]=2;return current[index];
  }
  private static Matrix4 affine(DoubleData d) {
    if(d.size()!=12) throw new IllegalArgumentException("FBX affine matrix requires 12 components");
    // ufbx snapshots store the 3x4 affine matrix in column-major order:
    // three values for each basis column followed by translation.  The
    // shared Matrix4 contract uses the same column-major layout, with a
    // fourth homogeneous row inserted at slots 3, 7 and 11.
    float[] m=new float[16];
    m[0]=(float)d.get(0);m[1]=(float)d.get(1);m[2]=(float)d.get(2);m[3]=0;
    m[4]=(float)d.get(3);m[5]=(float)d.get(4);m[6]=(float)d.get(5);m[7]=0;
    m[8]=(float)d.get(6);m[9]=(float)d.get(7);m[10]=(float)d.get(8);m[11]=0;
    m[12]=(float)d.get(9);m[13]=(float)d.get(10);m[14]=(float)d.get(11);m[15]=1;
    return new Matrix4(m);
  }
  private static boolean hasAffectedJoint(MeshAsset.Skinning skin,Map<Integer,float[]> deltas) {
    for(int i=0;i<skin.joints().size();i++) if(skin.weights().get(i)!=0&&deltas.containsKey(skin.joints().get(i))) return true;
    return false;
  }
  private static MeshAsset.Attribute transform(String name,MeshAsset.Attribute value,MeshAsset.Skinning skin,Map<Integer,float[]> deltas) {
    if(!name.equals("POSITION")&&!name.equals("NORMAL")&&!name.equals("TANGENT"))return value;int width=value.components();if(name.equals("NORMAL")&&width!=3||name.equals("POSITION")&&width!=3||name.equals("TANGENT")&&width!=4)return value;float[] out=new float[value.values().size()];
    for(int vertex=0;vertex<value.count();vertex++){float[] matrix=blend(skin,vertex,deltas);int o=vertex*width;Vec3 original=new Vec3(value.values().get(o),value.values().get(o+1),value.values().get(o+2));
      // Positions may legitimately be at the origin, so never normalize or reject
      // a zero position.  A degenerate blended normal/tangent can occur when an
      // exporter contains a zero influence or a singular scale; retain the source
      // direction for that vertex instead of aborting the complete animation frame.
      if(name.equals("POSITION")){Vec3 d=point(matrix,original);out[o]=d.x();out[o+1]=d.y();out[o+2]=d.z();}
      else {Vec3 d=direction(matrix,original);float length=(float)Math.sqrt(d.dot(d));if(length<1e-12f){d=original;length=(float)Math.sqrt(d.dot(d));}if(length>=1e-12f){out[o]=d.x()/length;out[o+1]=d.y()/length;out[o+2]=d.z()/length;}else {out[o]=original.x();out[o+1]=original.y();out[o+2]=original.z();}}
      if(width==4)out[o+3]=value.values().get(o+3);}
    return new MeshAsset.Attribute(width,new FloatData(out));
  }
  private static float[] blend(MeshAsset.Skinning skin,int vertex,Map<Integer,float[]> deltas){
    int begin=skin.offsets().get(vertex),end=skin.offsets().get(vertex+1);if(begin==end)return Matrix4.IDENTITY.copy();
    float[] out=new float[16];float total=0;
    for(int i=begin;i<end;i++){int joint=skin.joints().get(i);float weight=skin.weights().get(i);if(weight==0)continue;var delta=deltas.get(joint);if(delta==null)delta=Matrix4.IDENTITY.copy();for(int k=0;k<16;k++)out[k]+=weight*delta[k];total+=weight;}
    if(total<1e-12f)return Matrix4.IDENTITY.copy();
    if(Math.abs(total-1f)>1e-6f)for(int k=0;k<16;k++)out[k]/=total;
    return out;
  }
  private static Vec3 point(float[] m,Vec3 p){return new Vec3(m[0]*p.x()+m[4]*p.y()+m[8]*p.z()+m[12],m[1]*p.x()+m[5]*p.y()+m[9]*p.z()+m[13],m[2]*p.x()+m[6]*p.y()+m[10]*p.z()+m[14]);}
  private static Vec3 direction(float[] m,Vec3 p){return new Vec3(m[0]*p.x()+m[4]*p.y()+m[8]*p.z(),m[1]*p.x()+m[5]*p.y()+m[9]*p.z(),m[2]*p.x()+m[6]*p.y()+m[10]*p.z());}
  private static MeshAsset.Attribute transform(String name,MeshAsset.Attribute value,Matrix4 matrix) {
    if(name.equals("POSITION")&&value.components()==3){float[] out=new float[value.values().size()];for(int i=0;i<value.count();i++){var p=matrix.transformPoint(new Vec3(value.values().get(i*3),value.values().get(i*3+1),value.values().get(i*3+2)));out[i*3]=p.x();out[i*3+1]=p.y();out[i*3+2]=p.z();}return new MeshAsset.Attribute(3,new FloatData(out));}
    if(name.equals("NORMAL")&&value.components()==3)return directions(value,3,matrix,false);
    if(name.equals("TANGENT")&&value.components()==4)return directions(value,4,matrix,true);
    return value;
  }
  private static MeshAsset.Attribute directions(MeshAsset.Attribute value,int width,Matrix4 matrix,boolean tangent){float[] out=new float[value.values().size()];for(int i=0;i<value.count();i++){int o=i*width;Vec3 original=new Vec3(value.values().get(o),value.values().get(o+1),value.values().get(o+2));var d=matrix.transformDirection(original);float l=(float)Math.sqrt(d.dot(d));if(l<1e-12f){d=original;l=(float)Math.sqrt(d.dot(d));}if(l>=1e-12f){out[o]=d.x()/l;out[o+1]=d.y()/l;out[o+2]=d.z()/l;}else {out[o]=original.x();out[o+1]=original.y();out[o+2]=original.z();}if(width==4)out[o+3]=value.values().get(o+3);}return new MeshAsset.Attribute(width,new FloatData(out));}
}
