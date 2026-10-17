package cc.sirrus.ysmlib.scene.fbx;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.*;
import com.dylibso.chicory.compiler.MachineFactoryCompiler;
import com.dylibso.chicory.runtime.*;
import com.dylibso.chicory.wasi.WasiPreview1;
import com.dylibso.chicory.wasm.*;
import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;

/** Isolated memory-only FBX session. The public scene adapter owns conversion and dependency capabilities. */
final class FbxReactor implements AutoCloseable {
  private static final class Code {
    static final WasmModule MODULE=load();
    static final Function<Instance,Machine> FACTORY=MachineFactoryCompiler.compile(MODULE);
    static WasmModule load() {
      try(var input=FbxReactor.class.getResourceAsStream("/cc/sirrus/ysmlib/fbx/ufbx.wasm")) {
        if(input==null) throw new IOException("Bundled ufbx reactor is missing");return Parser.parse(input);
      } catch(IOException e) { throw new UncheckedIOException(e); }
    }
  }
  private final WasiPreview1 wasi;
  private final Instance instance;
  private final ReadLimits limits;
  private final TypedLayout typed;
  private final int countsPtr;
  private final int positionsPtr,normalsPtr,tangentsPtr,bitangentsPtr,drawNodesPtr,drawMeshesPtr;
  private final int localPtr,worldPtr,geometryLocalPtr,geometryWorldPtr;
  private boolean closed;

  FbxReactor(ByteData source,ReadLimits limits) throws AssetFormatException {
    this.limits=Objects.requireNonNull(limits);Objects.requireNonNull(source);
    if(source.size()==0 || source.size()>limits.maxBytes()) throw new AssetFormatException("FBX input byte budget exceeded");
    wasi=WasiPreview1.builder().build();
    try {
      instance=Instance.builder(Code.MODULE).withMachineFactory(Code.FACTORY)
          .withImportValues(ImportValues.builder().addFunction(wasi.toHostFunctions()).build()).build();
      if(call("ysm_fbx_abi")!=2) throw new IllegalStateException("FBX reactor ABI mismatch");
      int pointer=call("malloc",source.size());if(pointer==0) throw new AssetFormatException("FBX input memory exhausted");
      try {
        instance.memory().write(pointer,source.copy());
        // The source budget is also the upper bound for the bounded ufbx result and
        // snapshot.  Fixed 64/128 MiB caps rejected real Blender/Unity FBX files whose
        // retained DOM is larger than the encoded file even though the caller granted a
        // valid import budget.
        checked("ysm_fbx_load",pointer,source.size(),limits.maxBytes(),limits.maxBytes(),limits.maxElements(),limits.maxStringBytes());
      } finally { instance.export("free").apply(pointer); }
      countsPtr=malloc(Integer.BYTES*6);
      int[] counts=new int[6];
      checked("ysm_fbx_geometry_counts",countsPtr,countsPtr+4,countsPtr+8,countsPtr+12,countsPtr+16,countsPtr+20);
      for(int i=0;i<counts.length;i++) counts[i]=readInt(countsPtr+i*4);
      typed=new TypedLayout(counts[0],counts[1],counts[2],counts[3],counts[4],counts[5]);
      long bytes=typed.bytes();if(bytes>limits.maxBytes())throw new AssetFormatException("FBX typed geometry budget exceeded: "+bytes+" > "+limits.maxBytes());
      positionsPtr=mallocBytes(typed.positions*Float.BYTES);normalsPtr=mallocBytes(typed.normals*Float.BYTES);
      tangentsPtr=mallocBytes(typed.tangents*Float.BYTES);bitangentsPtr=mallocBytes(typed.bitangents*Float.BYTES);
      drawNodesPtr=mallocBytes(typed.draws*Integer.BYTES);drawMeshesPtr=mallocBytes(typed.draws*Integer.BYTES);
      localPtr=mallocBytes(typed.nodes*12*Float.BYTES);worldPtr=mallocBytes(typed.nodes*12*Float.BYTES);
      geometryLocalPtr=mallocBytes(typed.nodes*12*Float.BYTES);geometryWorldPtr=mallocBytes(typed.nodes*12*Float.BYTES);
    } catch(RuntimeException|Error|AssetFormatException failure) { wasi.close();throw failure; }
  }
  synchronized JsonObject source() throws AssetFormatException { return snapshot(1,false); }
  synchronized JsonObject evaluate(int stack,double seconds) throws AssetFormatException {
    return evaluate(stack,seconds,false);
  }
  synchronized JsonObject evaluate(int stack,double seconds,boolean followMesh) throws AssetFormatException {
    ensureOpen();if(!Double.isFinite(seconds)) throw new IllegalArgumentException("Non-finite FBX time");
    checked("ysm_fbx_evaluate",stack,Double.doubleToRawLongBits(seconds));return snapshot(0,followMesh);
  }
  synchronized FbxEvaluation.Frame evaluateTyped(FbxDocument source,int stack,double seconds,boolean followMesh,CompatibilityReport coverage) throws AssetFormatException {
    ensureOpen();if(!Double.isFinite(seconds))throw new IllegalArgumentException("Non-finite FBX time");
    checked("ysm_fbx_evaluate",stack,Double.doubleToRawLongBits(seconds));
    int result=call("ysm_fbx_geometry_write",positionsPtr,typed.positions,normalsPtr,typed.normals,tangentsPtr,typed.tangents,bitangentsPtr,typed.bitangents,
        drawNodesPtr,drawMeshesPtr,typed.draws,localPtr,worldPtr,geometryLocalPtr,geometryWorldPtr,typed.nodes,followMesh?1:0,0,0);
    if(result<0)throw new AssetFormatException(error());
    var nodes=new ArrayList<FbxDocument.Node>(typed.nodes);for(int i=0;i<typed.nodes;i++) {
      var n=source.nodes().get(i);nodes.add(new FbxDocument.Node(n.element(),n.parent(),n.mesh(),n.light(),n.camera(),n.visible(),n.inheritMode(),
          readDoubles(localPtr,i*12,12),readDoubles(worldPtr,i*12,12),readDoubles(geometryLocalPtr,i*12,12),readDoubles(geometryWorldPtr,i*12,12),n.materials()));
    }
    var meshByTyped=new HashMap<Integer,Integer>();for(int i=0;i<source.meshes().size();i++) {
      var mesh=source.meshes().get(i);for(var e:source.elements())if(e.id()==mesh.element())meshByTyped.put(e.typedId(),i);
    }
    var draws=new ArrayList<FbxEvaluation.DrawFrame>(typed.draws);int po=0,no=0,to=0,bo=0;
    for(int di=0;di<typed.draws;di++) {
      int node=readInt(drawNodesPtr+di*4),meshTyped=readInt(drawMeshesPtr+di*4),mesh=meshByTyped.getOrDefault(meshTyped,-1);
      if(node<0||node>=source.nodes().size()||mesh<0||mesh>=source.meshes().size())throw new AssetFormatException("FBX typed draw index out of range");
      var m=source.meshes().get(mesh);int corners=m.cornerCount();var p=readFloats(positionsPtr,po,corners*3);po+=corners*3;
      var n=m.normals().size()==0?DoubleData.EMPTY:readFloats(normalsPtr,no,corners*3);if(m.normals().size()!=0)no+=corners*3;
      var uvs=new ArrayList<FbxEvaluation.UvDirections>(m.uvSets().size());for(var uv:m.uvSets()) {
        var t=uv.tangents().size()==0?DoubleData.EMPTY:readFloats(tangentsPtr,to,corners*3);if(uv.tangents().size()!=0)to+=corners*3;
        var b=uv.bitangents().size()==0?DoubleData.EMPTY:readFloats(bitangentsPtr,bo,corners*3);if(uv.bitangents().size()!=0)bo+=corners*3;uvs.add(new FbxEvaluation.UvDirections(t,b));
      }
      draws.add(new FbxEvaluation.DrawFrame(node,mesh,p,n,uvs));
    }
    return new FbxEvaluation.Frame(seconds,nodes,List.of(),source.elements(),source.details(),source.textures(),draws,coverage);
  }
  synchronized JsonObject evaluateDetails() throws AssetFormatException {
    ensureOpen();return snapshot(2,false);
  }
  private JsonObject snapshot(int source,boolean followMesh) throws AssetFormatException {
    ensureOpen();checked("ysm_fbx_snapshot",source,followMesh?1:0);int size=call("ysm_fbx_output_size");
    if(size<0 || size>limits.maxBytes()) throw new AssetFormatException("FBX snapshot output budget exceeded");
    int pointer=call("ysm_fbx_output");var bytes=instance.memory().readBytes(pointer,size);
    // Parse directly from the UTF-8 bytes. The previous String intermediary
    // kept a second full copy of large FBX snapshots alive during Gson tree
    // construction and could exhaust the heap before the typed document was
    // available. JsonReader still validates the complete document while
    // keeping the peak transfer lower.
    try (var input=new ByteArrayInputStream(bytes);
         var reader=new JsonReader(new InputStreamReader(input,StandardCharsets.UTF_8))) {
      return JsonParser.parseReader(reader).getAsJsonObject();
    }
    catch(JsonParseException|IllegalStateException e) { throw new AssetFormatException("Invalid internal FBX snapshot",e); }
    catch(IOException e) { throw new AssetFormatException("Unable to read internal FBX snapshot",e); }
  }
  private int call(String name,long... arguments) { return (int)instance.export(name).apply(arguments)[0]; }
  private void checked(String name,long... arguments) throws AssetFormatException {
    if(call(name,arguments)>=0) return;
    byte[] bytes=instance.memory().readBytes(call("ysm_fbx_error"),2048);int end=0;while(end<bytes.length&&bytes[end]!=0) end++;
    throw new AssetFormatException(new String(bytes,0,end,StandardCharsets.UTF_8));
  }
  private void ensureOpen() { if(closed) throw new IllegalStateException("FBX reactor is closed"); }
  private int malloc(long bytes) { if(bytes<=0||bytes>Integer.MAX_VALUE)throw new IllegalArgumentException("FBX typed buffer size overflow");return mallocBytes((int)bytes); }
  private int mallocBytes(int bytes) { int p=call("malloc",Math.max(1,bytes));if(p==0)throw new IllegalArgumentException("FBX typed buffer allocation failed");return p; }
  private int readInt(int pointer) { return ByteBuffer.wrap(instance.memory().readBytes(pointer,4)).order(ByteOrder.LITTLE_ENDIAN).getInt(); }
  private float readFloat(int pointer) { return ByteBuffer.wrap(instance.memory().readBytes(pointer,4)).order(ByteOrder.LITTLE_ENDIAN).getFloat(); }
  private DoubleData readFloats(int pointer,int offset,int count) { float[] out=new float[count];byte[] raw=instance.memory().readBytes(pointer+offset*4,count*4);var b=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);for(int i=0;i<count;i++){float v=b.getFloat();if(!Float.isFinite(v))throw new IllegalStateException("Native FBX produced non-finite data");out[i]=v;}return DoubleData.ownedFloats(out); }
  private DoubleData readDoubles(int pointer,int offset,int count) { return readFloats(pointer,offset,count); }
  private String error() { byte[] bytes=instance.memory().readBytes(call("ysm_fbx_error"),2048);int end=0;while(end<bytes.length&&bytes[end]!=0)end++;return new String(bytes,0,end,StandardCharsets.UTF_8); }
  @Override public synchronized void close() { if(closed) return;closed=true;try { instance.export("ysm_fbx_destroy").apply(); } finally { wasi.close(); } }
  private record TypedLayout(int draws,int positions,int normals,int tangents,int bitangents,int nodes) {
    long bytes() { return Math.addExact(Math.addExact(Math.addExact(Math.addExact(Math.multiplyExact((long)positions,4),Math.multiplyExact((long)normals,4)),Math.multiplyExact((long)tangents,4)),Math.multiplyExact((long)bitangents,4)),Math.addExact(Math.multiplyExact((long)draws,8),Math.multiplyExact((long)nodes,48))); }
  }
}
