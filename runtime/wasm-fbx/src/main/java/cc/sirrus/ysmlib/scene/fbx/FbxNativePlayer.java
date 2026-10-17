package cc.sirrus.ysmlib.scene.fbx;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.AssetFormatException;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import java.io.UncheckedIOException;
import java.nio.*;
import java.util.*;

/** Native ufbx evaluator. Source metadata is loaded once; frames use reusable direct buffers. */
public final class FbxNativePlayer implements FbxEvaluation {
  private final FbxDocument source;
  private final ReadLimits limits;
  private final Settings settings;
  private final CompatibilityReport coverage;
  private final FbxNativeReactor.Layout layout;
  private final int[] meshSelection;
  private final int[] skinSelection;
  private final ByteBuffer positions,normals,tangents,bitangents,drawNodes,drawMeshes,local,world,geometryLocal,geometryWorld;
  private final ByteBuffer physicsDeltas;
  /** Native node evaluation does not populate the resident vertex buffers. */
  private int lastStack = Integer.MIN_VALUE;
  private double lastSeconds = Double.NaN;
  private boolean geometryReady;
  /** Animation-free FBX scenes can reuse one immutable geometry projection. */
  private NativeGeometry staticGeometry;
  private final Map<FrameKey,Frame> frames=Collections.synchronizedMap(new LinkedHashMap<>(8,.75f,true) {
    @Override protected boolean removeEldestEntry(Map.Entry<FrameKey,Frame> e) { return size()>Integer.getInteger("ysm.fbx.frameCacheEntries",1); }
  });
  private boolean closed;

  private FbxNativePlayer(FbxDocument source,ReadLimits limits,Settings settings) throws AssetFormatException {
    this.source=Objects.requireNonNull(source);this.limits=Objects.requireNonNull(limits);this.settings=Objects.requireNonNull(settings);
    for (int mesh = 0; mesh < source.meshes().size(); mesh++) {
      try { FbxSkinningSupport.validate(source, mesh); }
      catch (IllegalArgumentException unsupportedSkin) {
        throw new AssetFormatException(unsupportedSkin.getMessage(), unsupportedSkin);
      }
    }
    meshSelection=new int[source.meshes().stream().mapToInt(m->m.skins().size()>0?1:0).sum()];
    skinSelection=new int[meshSelection.length];int selectionIndex=0;
    for(var mesh:source.meshes()) if(mesh.skins().size()>0) { meshSelection[selectionIndex]=mesh.element();skinSelection[selectionIndex]=mesh.skins().get(0);selectionIndex++; }
    layout=FbxNativeReactor.prepare(source.source(),limits,meshSelection,skinSelection);
    long directBytes=directBufferBytes(layout);
    if(directBytes>limits.maxBytes()) throw new AssetFormatException("Native FBX direct buffer budget exceeded: "+directBytes+" > "+limits.maxBytes());
    positions=buffer(layout.positions());normals=buffer(layout.normals());tangents=buffer(layout.tangents());bitangents=buffer(layout.bitangents());
    drawNodes=bufferBytes(layout.draws(),Integer.BYTES);drawMeshes=bufferBytes(layout.draws(),Integer.BYTES);
    local=buffer(layout.nodes()*12);world=buffer(layout.nodes()*12);geometryLocal=buffer(layout.nodes()*12);geometryWorld=buffer(layout.nodes()*12);
    physicsDeltas=buffer(layout.nodes()*16);
    coverage=new CompatibilityReport(List.of(
        new CompatibilityReport.Feature("fbx.native-ufbx",CompatibilityReport.Level.EVALUATED,true,"Native ufbx parser/evaluator"),
        new CompatibilityReport.Feature("fbx.geometry-direct",CompatibilityReport.Level.EVALUATED,true,"Per-frame geometry and node transforms written directly into reusable native buffers"),
        new CompatibilityReport.Feature("fbx.physics-native-skin",CompatibilityReport.Level.EVALUATED,true,"Linear, rigid, dual-quaternion and blended-DQ physics deltas are applied in the resident native skin pass when transforms are representable"),
        new CompatibilityReport.Feature("fbx.geometry",CompatibilityReport.Level.EVALUATED,true,"Native evaluated geometry with source static data reuse")),List.of());
  }
  public static FbxEvaluation tryCreate(FbxDocument source,ReadLimits limits,Settings settings) {
    if(!FbxNativeReactor.available()) return null;
    try { return new FbxNativePlayer(source,limits,settings); }
    catch(AssetFormatException|RuntimeException|LinkageError failure) { return null; }
  }
  @Override public synchronized Frame evaluate(int stack,double seconds) {
    if(closed) throw new IllegalStateException("FBX player is closed");
    var key=new FrameKey(stack,Double.doubleToLongBits(seconds),settings.skinSpace());var cached=frames.get(key);if(cached!=null)return cached;
    try {
      evaluateNative(stack,seconds);
      var frame=frame(seconds);frames.put(key,frame);return frame;
    } catch(AssetFormatException e) { throw new UncheckedIOException(e); }
  }
  /** Evaluates the resident scene while materializing only node data for physics input. */
  public synchronized Frame evaluateNodes(int stack,double seconds) {
    if(closed) throw new IllegalStateException("FBX player is closed");
    try { FbxNativeReactor.evaluateNodes(source.source(),limits,meshSelection,skinSelection,layout,stack,seconds,local,world,geometryLocal,geometryWorld);
      lastStack=stack;lastSeconds=seconds;geometryReady=false;return nodeFrame(seconds); }
    catch(AssetFormatException e) { throw new UncheckedIOException(e); }
  }
  /**
   * Reuses the current resident ufbx evaluation and writes physics-adjusted
   * supported skinning methods directly into the native geometry buffers. A
   * null result means a DQ transform contains shear and must use the checked
   * Java projection instead of silently applying the wrong method.
   */
  public synchronized Frame evaluatePhysics(int stack,double seconds,Map<Integer,Pose> poses) {
    Objects.requireNonNull(poses);
    try { evaluateNative(stack,seconds); }
    catch(AssetFormatException e) { throw new UncheckedIOException(e); }
    return evaluatePhysics(nodeFrame(seconds),seconds,poses);
  }
  /** Applies physics to a node-only frame produced by {@link #evaluateNodes}. */
  public synchronized Frame evaluatePhysics(Frame base,double seconds,Map<Integer,Pose> poses) {
    Objects.requireNonNull(base);Objects.requireNonNull(poses);
    if(poses.isEmpty()) {
      if(!geometryReady || lastStack==Integer.MIN_VALUE || Double.doubleToLongBits(lastSeconds)!=Double.doubleToLongBits(seconds))
        try { evaluateNative(lastStack==Integer.MIN_VALUE?-1:lastStack,seconds); }
        catch(AssetFormatException e) { throw new UncheckedIOException(e); }
      return frame(seconds);
    }
    // evaluateNodes() supplies matrices for physics but leaves resident vertex
    // buffers untouched. Refresh them before the native skin pass so the first
    // physics frame cannot consume stale or uninitialized geometry.
    if(!geometryReady || lastStack==Integer.MIN_VALUE || Double.doubleToLongBits(lastSeconds)!=Double.doubleToLongBits(seconds))
      try { evaluateNative(lastStack==Integer.MIN_VALUE?-1:lastStack,seconds); }
      catch(AssetFormatException e) { throw new UncheckedIOException(e); }
    var values=physicsDeltas.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer();
    for(int node=0;node<layout.nodes();node++) for(int k=0;k<16;k++) values.put(node*16+k,(k%5)==0?1f:0f);
    for(var entry:absoluteDeltas(base,poses).entrySet()) {
      int node=entry.getKey(); if(node<0||node>=base.nodes().size())continue;
      var delta=entry.getValue(); for(int k=0;k<16;k++) values.put(node*16+k,delta[k]);
    }
    try {
      boolean supported=FbxNativeReactor.writePhysicsGeometry(settings.skinSpace()==SkinSpace.FOLLOW_MESH_NODE,
          positions,normals,tangents,bitangents,drawNodes,drawMeshes,local,world,geometryLocal,geometryWorld,physicsDeltas);
      return supported?frame(seconds):null;
    } catch(AssetFormatException e) { throw new UncheckedIOException(e); }
  }
  /** Applies local retarget deltas after a node evaluation. */
  public synchronized Frame evaluatePhysicsDeltas(int stack,double seconds,Map<Integer,Pose> poses) {
    if(closed) throw new IllegalStateException("FBX player is closed");
    try { evaluateNative(stack,seconds); }
    catch(AssetFormatException e) { throw new UncheckedIOException(e); }
    return evaluatePhysicsDeltas(nodeFrame(seconds),seconds,poses);
  }
  public synchronized Frame evaluatePhysicsDeltas(Frame base,double seconds,Map<Integer,Pose> poses) {
    Objects.requireNonNull(base);Objects.requireNonNull(poses);
    if(!geometryReady || lastStack==Integer.MIN_VALUE || Double.doubleToLongBits(lastSeconds)!=Double.doubleToLongBits(seconds))
      try { evaluateNative(lastStack==Integer.MIN_VALUE?-1:lastStack,seconds); }
      catch(AssetFormatException e) { throw new UncheckedIOException(e); }
    var values=physicsDeltas.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer();
    for(int node=0;node<layout.nodes();node++) for(int k=0;k<16;k++) values.put(node*16+k,(k%5)==0?1f:0f);
    for(var entry:FbxPhysicsGeometry.deltas(base,poses).entrySet()) { int node=entry.getKey();if(node<0||node>=base.nodes().size())continue;var delta=entry.getValue();for(int k=0;k<16;k++)values.put(node*16+k,delta[k]); }
    try {
      boolean supported=FbxNativeReactor.writePhysicsGeometry(settings.skinSpace()==SkinSpace.FOLLOW_MESH_NODE,positions,normals,tangents,bitangents,drawNodes,drawMeshes,local,world,geometryLocal,geometryWorld,physicsDeltas);
      return supported?frame(seconds):null;
    } catch(AssetFormatException e) { throw new UncheckedIOException(e); }
  }
  /** Evaluates and snapshots geometry off-heap, avoiding a transient Java float/double vertex copy. */
  public synchronized NativeGeometry evaluateGeometry(int stack,double seconds) {
    if(closed) throw new IllegalStateException("FBX player is closed");
    if(stack<0 && source.animations().isEmpty() && staticGeometry!=null) return staticGeometry;
    try {
      evaluateNative(stack,seconds);
      long bytes=(long)layout.positions()*Float.BYTES+(long)layout.normals()*Float.BYTES+(long)layout.tangents()*Float.BYTES+(long)layout.bitangents()*Float.BYTES;
      if(bytes>limits.maxBytes()) throw new IllegalStateException("Native FBX geometry snapshot exceeds byte budget: "+bytes+" > "+limits.maxBytes());
      var snapshot=new NativeGeometrySnapshot(copyDirect(positions),copyDirect(normals),copyDirect(tangents),copyDirect(bitangents));
      var frame=nodeFrame(seconds);var geometry=new FbxGeometry().compileNative(source,this,snapshot,seconds);
      var result=new NativeGeometry(frame,geometry);
      if(stack<0 && source.animations().isEmpty()) staticGeometry=result;
      return result;
    } catch (AssetFormatException e) { throw new UncheckedIOException(e); }
  }
  int drawNode(int index) { return drawNodes.duplicate().order(ByteOrder.nativeOrder()).asIntBuffer().get(index); }
  int drawMesh(int index) { return drawMeshes.duplicate().order(ByteOrder.nativeOrder()).asIntBuffer().get(index); }
  int layoutDraws() { return layout.draws(); }
  FloatData direct(ByteBuffer buffer,int offset,int count) {
    var view=buffer.duplicate().order(ByteOrder.nativeOrder());view.position(Math.multiplyExact(offset,Float.BYTES));view.limit(Math.addExact(view.position(),Math.multiplyExact(count,Float.BYTES)));
    return FloatData.direct(view.slice().order(ByteOrder.nativeOrder()),count);
  }
  private static ByteBuffer copyDirect(ByteBuffer source) {
    var out=ByteBuffer.allocateDirect(Math.max(1,source.capacity())).order(ByteOrder.nativeOrder());var view=source.duplicate();view.position(0);view.limit(source.capacity());out.put(view);out.flip();return out;
  }
  private void evaluateNative(int stack,double seconds) throws AssetFormatException {
    FbxNativeReactor.evaluateGeometry(source.source(),limits,meshSelection,skinSelection,layout,stack,seconds,settings.skinSpace()==SkinSpace.FOLLOW_MESH_NODE,
        positions,normals,tangents,bitangents,drawNodes,drawMeshes,local,world,geometryLocal,geometryWorld);
    lastStack=stack;lastSeconds=seconds;geometryReady=true;
  }
  private Frame nodeFrame(double seconds) {
    return new FbxEvaluation.Frame(seconds,nodes(),List.of(),source.elements(),source.details(),source.textures(),List.of(),coverage);
  }
  private List<FbxDocument.Node> nodes() {
    var nodes=new ArrayList<FbxDocument.Node>(layout.nodes());
    for(int i=0;i<layout.nodes();i++) {
      var n=source.nodes().get(i);
      nodes.add(new FbxDocument.Node(n.element(),n.parent(),n.mesh(),n.light(),n.camera(),n.visible(),n.inheritMode(),
          read(local,i*12,12),read(world,i*12,12),read(geometryLocal,i*12,12),read(geometryWorld,i*12,12),n.materials()));
    }
    return nodes;
  }
  private Frame frame(double seconds) {
    var nodes=nodes();
    var draws=new ArrayList<FbxEvaluation.DrawFrame>(layout.draws());int po=0,no=0,to=0,bo=0;
    IntBuffer dn=drawNodes.duplicate().order(ByteOrder.nativeOrder()).asIntBuffer();
    for(int di=0;di<layout.draws();di++) {
      int nodeIndex=dn.get(di);if(nodeIndex<0||nodeIndex>=source.nodes().size()) throw new IllegalStateException("Native FBX draw node out of range");
      int meshIndex=source.nodes().get(nodeIndex).mesh();if(meshIndex<0||meshIndex>=source.meshes().size()) throw new IllegalStateException("Native FBX draw mesh out of range");
      var mesh=source.meshes().get(meshIndex);int corners=mesh.cornerCount();
      DoubleData p=read(positions,po,corners*3);po+=corners*3;
      DoubleData n=mesh.normals().size()==0?DoubleData.EMPTY:read(normals,no,corners*3);if(mesh.normals().size()!=0)no+=corners*3;
      var uvDirections=new ArrayList<FbxEvaluation.UvDirections>(mesh.uvSets().size());
      for(var uv:mesh.uvSets()) {
        DoubleData t=uv.tangents().size()==0?DoubleData.EMPTY:read(tangents,to,corners*3);if(uv.tangents().size()!=0)to+=corners*3;
        DoubleData b=uv.bitangents().size()==0?DoubleData.EMPTY:read(bitangents,bo,corners*3);if(uv.bitangents().size()!=0)bo+=corners*3;
        uvDirections.add(new FbxEvaluation.UvDirections(t,b));
      }
      draws.add(new FbxEvaluation.DrawFrame(nodeIndex,meshIndex,p,n,uvDirections));
    }
    return new FbxEvaluation.Frame(seconds,nodes,List.of(),source.elements(),source.details(),source.textures(),draws,coverage);
  }
  private static Map<Integer,float[]> absoluteDeltas(Frame base,Map<Integer,Pose> poses) {
    var out=new HashMap<Integer,float[]>(poses.size());
    for(var entry:poses.entrySet()){int node=entry.getKey();if(node<0||node>=base.nodes().size())continue;out.put(node,entry.getValue().multiply(FbxPhysicsBinding.nodePose(base.nodes().get(node)).inverse()).matrix().copy());}
    return out;
  }
  private static DoubleData read(ByteBuffer buffer,int offset,int count) {
    float[] out=new float[count];ByteBuffer view=buffer.duplicate().order(ByteOrder.nativeOrder());
    for(int i=0;i<count;i++) { float v=view.getFloat((offset+i)*Float.BYTES);if(!Float.isFinite(v)) throw new IllegalStateException("Native FBX produced non-finite data");out[i]=v; }
    return DoubleData.ownedFloats(out);
  }
  private static ByteBuffer buffer(int scalars) { return bufferBytes(scalars,Float.BYTES); }
  private static long directBufferBytes(FbxNativeReactor.Layout l) {
    try {
      long floats=Math.addExact(Math.addExact(Math.addExact(l.positions(),l.normals()),Math.addExact(l.tangents(),l.bitangents())),Math.multiplyExact((long)l.nodes()*12*4,1));
      long bytes=Math.multiplyExact(floats,Float.BYTES);
      bytes=Math.addExact(bytes,Math.multiplyExact((long)l.draws()*Integer.BYTES*2,1));
      bytes=Math.addExact(bytes,Math.multiplyExact((long)l.nodes()*16,Float.BYTES));
      return bytes;
    } catch(ArithmeticException overflow) { throw new IllegalArgumentException("Native FBX direct buffer size overflow",overflow); }
  }
  private static ByteBuffer bufferBytes(int elements,int bytesPerElement) {
    long bytes=Math.max(1L,Math.multiplyExact((long)elements,bytesPerElement));if(bytes>Integer.MAX_VALUE) throw new IllegalArgumentException("FBX direct buffer is too large");
    return ByteBuffer.allocateDirect((int)bytes).order(ByteOrder.nativeOrder());
  }
  @Override public synchronized void close() { closed=true;frames.clear();staticGeometry=null; }
  private record FrameKey(int stack,long seconds,SkinSpace space) {}
  record NativeGeometrySnapshot(ByteBuffer positions,ByteBuffer normals,ByteBuffer tangents,ByteBuffer bitangents) {}
  public record NativeGeometry(Frame frame,GeometryFrame geometry) {}
}
