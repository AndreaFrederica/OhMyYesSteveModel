package cc.sirrus.ysmlib.scene.natives;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.java.*;
import java.nio.*;
import java.nio.file.Path;
import java.util.*;

/** One packed JNI call per deformation, bounded persistent native workers, no native model handles. */
public final class NativeDeformationProvider implements DeformationProvider {
  private final boolean parallel;
  public NativeDeformationProvider(Path library) { this(library,true); }
  public NativeDeformationProvider(Path library,boolean parallel) {
    System.load(library.toAbsolutePath().normalize().toString());
    if(nAbi()!=1)throw new UnsatisfiedLinkError("ysmlib skinning ABI mismatch");
    this.parallel=parallel;
    var skin=new MeshAsset.Skinning(new IntData(0,1),new IntData(0),new FloatData(1),List.of(MeshAsset.Deform.LINEAR),FloatData.EMPTY);
    var mesh=new MeshAsset.Primitive(MeshAsset.Topology.POINTS,Map.of("POSITION",new MeshAsset.Attribute(3,new FloatData(1,2,3))),new IntData(0),-1,skin,List.of());
    try(var session=compile(mesh)) {
      var result=session.deform(List.of(Matrix4.IDENTITY),FloatData.EMPTY,SceneProvider.NormalPolicy.MMD_WEIGHTED_ROTATION);
      if(!result.get("POSITION").values().equals(new FloatData(1,2,3)))throw new UnsatisfiedLinkError("ysmlib skinning self-check failed");
    }
  }
  public String id(){return parallel?"native-skinning-v1-parallel":"native-skinning-v1-scalar";}
  public Session compile(MeshAsset.Primitive mesh) {
    Objects.requireNonNull(mesh);
    return mesh.skinning()==null?new JavaDeformationProvider().compile(mesh):new Compiled(mesh,parallel);
  }
  private static IntBuffer ints(int n){return ByteBuffer.allocateDirect(Math.multiplyExact(n,4)).order(ByteOrder.nativeOrder()).asIntBuffer();}
  private static FloatBuffer floats(int n){return ByteBuffer.allocateDirect(Math.multiplyExact(n,4)).order(ByteOrder.nativeOrder()).asFloatBuffer();}
  private static final class Compiled implements Session {
    final MeshAsset.Primitive mesh;final boolean parallel;final IntBuffer ranges,joints,modes;
    final FloatBuffer weights,sdef,input,output;final int flags;
    FloatBuffer palette=floats(0);boolean closed;Session fallback;
    final Map<String,float[]> scratch=new LinkedHashMap<>();
    Compiled(MeshAsset.Primitive mesh,boolean parallel){
      this.mesh=mesh;this.parallel=parallel;var skin=mesh.skinning();int count=mesh.vertexCount();
      if(count>2_000_000||skin.joints().size()>8_000_000)throw new IllegalArgumentException("Skinning transfer budget exceeded");
      ranges=ints(skin.offsets().size());for(int i=0;i<ranges.capacity();i++)ranges.put(i,skin.offsets().get(i));
      joints=ints(skin.joints().size());for(int i=0;i<joints.capacity();i++)joints.put(i,skin.joints().get(i));
      weights=floats(skin.weights().size());for(int i=0;i<weights.capacity();i++)weights.put(i,skin.weights().get(i));
      modes=ints(count);for(int i=0;i<count;i++)modes.put(i,skin.deforms().get(i).ordinal());
      sdef=floats(skin.sdef().size());for(int i=0;i<sdef.capacity();i++)sdef.put(i,skin.sdef().get(i));
      input=floats(count*10);output=floats(count*10);
      flags=(mesh.attributes().containsKey("NORMAL")?1:0)|(mesh.attributes().containsKey("TANGENT")?2:0);
    }
    public synchronized Map<String,MeshAsset.Attribute> deform(List<Matrix4> matrices,FloatData morphWeights,SceneProvider.NormalPolicy policy){
      if(closed)throw new IllegalStateException("Deformation session closed");Objects.requireNonNull(policy);
      if(fallback!=null)return fallback.deform(matrices,morphWeights,policy);
      if(matrices.size()>65536)throw new IllegalArgumentException("Skin palette budget exceeded");
      // Ordered morph accumulation remains shared with the Java oracle, including sparse repeated indices.
      Deformer.morphInto(mesh,morphWeights,scratch);var attributes=scratch;int n=mesh.vertexCount();
      float[] positions=attributes.get("POSITION"),normals=attributes.get("NORMAL"),tangents=attributes.get("TANGENT");
      for(int v=0;v<n;v++){int at=v*10;for(int k=0;k<3;k++)input.put(at+k,positions[v*3+k]);
        for(int k=0;k<3;k++)input.put(at+3+k,normals==null?0:normals[v*3+k]);for(int k=0;k<4;k++)input.put(at+6+k,tangents==null?0:tangents[v*4+k]);}
      int length=matrices.size()*16;if(palette.capacity()<length)palette=floats(length);palette.clear();palette.limit(length);
      for(var matrix:matrices)for(int c=0;c<4;c++)for(int r=0;r<4;r++)palette.put(matrix.get(c,r));palette.flip();
      try {
        int result=nSkin(ranges,joints,weights,modes,sdef,palette.slice(),input,output,
            flags|(policy==SceneProvider.NormalPolicy.INVERSE_TRANSPOSE?4:0),parallel);
        if(result==-1)throw new IllegalArgumentException("Native skinning input rejected");
        if(result!=0)throw new IllegalStateException("Native skinning numerical/allocation failure");
      }catch(LinkageError unavailable){fallback=new JavaDeformationProvider().compile(mesh);return fallback.deform(matrices,morphWeights,policy);}
      for(int v=0;v<n;v++){int at=v*10;for(int k=0;k<3;k++)positions[v*3+k]=output.get(at+k);
        if(normals!=null)for(int k=0;k<3;k++)normals[v*3+k]=output.get(at+3+k);if(tangents!=null)for(int k=0;k<4;k++)tangents[v*4+k]=output.get(at+6+k);}
      return Deformer.freeze(mesh,attributes);
    }
    public synchronized void close(){closed=true;if(fallback!=null)fallback.close();}
  }
  private static native int nAbi();
  static native int nSkin(IntBuffer ranges,IntBuffer joints,FloatBuffer weights,IntBuffer modes,FloatBuffer sdef,
                         FloatBuffer palette,FloatBuffer input,FloatBuffer output,int flags,boolean parallel);
}
