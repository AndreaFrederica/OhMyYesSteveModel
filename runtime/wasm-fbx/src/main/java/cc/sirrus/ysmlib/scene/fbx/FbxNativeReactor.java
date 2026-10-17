package cc.sirrus.ysmlib.scene.fbx;

import cc.sirrus.ysmlib.scene.ByteData;
import cc.sirrus.ysmlib.scene.io.AssetFormatException;
import cc.sirrus.ysmlib.scene.io.ReadLimits;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;

/** Optional native ufbx bridge. One bounded source scene may be reused between frames. */
final class FbxNativeReactor {
  private static final Object LOAD_LOCK = new Object();
  private static volatile Path loaded;
  private static volatile Throwable loadFailure;
  private FbxNativeReactor() {}

  static synchronized SnapshotJson source(ByteData bytes, ReadLimits limits) throws AssetFormatException {
    ensureLoaded();
    // The native reactor owns one global ufbx output arena.  A direct
    // ByteBuffer view into that arena becomes dangling as soon as another
    // catalog worker loads a scene or calls nRelease().  Copy the snapshot to
    // a Java byte[] while the JNI mutex is held; parsing can then safely run
    // concurrently with the next native scene.
    var output=bytes.withReadOnlyArray(input -> nSource(input, limits.maxBytes(), limits.maxBytes(), limits.maxElements(), limits.maxStringBytes()));
    if(output==null) throw new AssetFormatException("Native FBX bridge failed: "+nError());
    int size=output.length;if(size<0) throw new AssetFormatException("Native FBX snapshot has invalid size");
    return new SnapshotJson(ByteBuffer.wrap(output),size);
  }
  static synchronized JsonResult evaluate(ByteData bytes, ReadLimits limits, int stack, double seconds, boolean followMesh) throws AssetFormatException {
    ensureLoaded();
    var output=bytes.withReadOnlyArray(input -> nEvaluate(input, limits.maxBytes(), limits.maxBytes(), limits.maxElements(), limits.maxStringBytes(), stack, seconds, followMesh));
    return call(output);
  }
  /** Loads one resident ufbx scene and applies the selected source Skin per mesh. */
  static synchronized Layout prepare(ByteData bytes, ReadLimits limits, int[] meshSelection, int[] skinSelection) throws AssetFormatException {
    ensureLoaded();
    var values=bytes.withReadOnlyArray(input -> nPrepareSelected(input, limits.maxBytes(), limits.maxBytes(), limits.maxElements(), limits.maxStringBytes(), meshSelection, skinSelection));
    if(values==null) throw new AssetFormatException("Native FBX bridge failed: "+nError());
    if(values.length!=6) throw new AssetFormatException("Native FBX layout ABI is malformed");
    return new Layout(values[0],values[1],values[2],values[3],values[4],values[5]);
  }
  static void evaluateGeometry(Layout layout,int stack,double seconds,boolean followMesh,
      ByteBuffer positions,ByteBuffer normals,ByteBuffer tangents,ByteBuffer bitangents,
      ByteBuffer drawNodes,ByteBuffer drawMeshes,ByteBuffer local,ByteBuffer world,
      ByteBuffer geometryLocal,ByteBuffer geometryWorld) throws AssetFormatException {
    ensureLoaded();
    if(nGeometryEvaluate(stack,seconds,followMesh,positions,normals,tangents,bitangents,drawNodes,drawMeshes,local,world,geometryLocal,geometryWorld)!=0)
      throw new AssetFormatException("Native FBX geometry evaluation failed: "+nError());
  }
  /** Re-establishes and evaluates the single native resident scene atomically. */
  static synchronized void evaluateGeometry(ByteData bytes, ReadLimits limits, int[] meshSelection, int[] skinSelection,
      Layout expected, int stack, double seconds, boolean followMesh,
      ByteBuffer positions, ByteBuffer normals, ByteBuffer tangents, ByteBuffer bitangents,
      ByteBuffer drawNodes, ByteBuffer drawMeshes, ByteBuffer local, ByteBuffer world,
      ByteBuffer geometryLocal, ByteBuffer geometryWorld) throws AssetFormatException {
    var actual=prepare(bytes,limits,meshSelection,skinSelection);
    if (!actual.equals(expected)) throw new AssetFormatException("Native FBX layout changed while evaluating");
    if(nGeometryEvaluate(stack,seconds,followMesh,positions,normals,tangents,bitangents,drawNodes,drawMeshes,local,world,geometryLocal,geometryWorld)!=0)
      throw new AssetFormatException("Native FBX geometry evaluation failed: "+nError());
  }
  static void evaluateNodes(Layout layout,int stack,double seconds,ByteBuffer local,ByteBuffer world,ByteBuffer geometryLocal,ByteBuffer geometryWorld) throws AssetFormatException {
    ensureLoaded();if(nEvaluateNodes(stack,seconds,layout.nodes(),local,world,geometryLocal,geometryWorld)!=0)
      throw new AssetFormatException("Native FBX node evaluation failed: "+nError());
  }
  static synchronized void evaluateNodes(ByteData bytes, ReadLimits limits, int[] meshSelection, int[] skinSelection,
      Layout expected, int stack, double seconds, ByteBuffer local, ByteBuffer world,
      ByteBuffer geometryLocal, ByteBuffer geometryWorld) throws AssetFormatException {
    var actual=prepare(bytes,limits,meshSelection,skinSelection);
    if (!actual.equals(expected)) throw new AssetFormatException("Native FBX layout changed while evaluating nodes");
    if(nEvaluateNodes(stack,seconds,expected.nodes(),local,world,geometryLocal,geometryWorld)!=0)
      throw new AssetFormatException("Native FBX node evaluation failed: "+nError());
  }
  /** Writes the already evaluated resident scene with physics deltas. Returns false only when ufbx's DQ transform cannot represent the input (eg. shear). */
  static boolean writePhysicsGeometry(boolean followMesh,ByteBuffer positions,ByteBuffer normals,ByteBuffer tangents,ByteBuffer bitangents,
      ByteBuffer drawNodes,ByteBuffer drawMeshes,ByteBuffer local,ByteBuffer world,ByteBuffer geometryLocal,ByteBuffer geometryWorld,ByteBuffer physicsDeltas) throws AssetFormatException {
    ensureLoaded();int result=nGeometryWrite(followMesh,positions,normals,tangents,bitangents,drawNodes,drawMeshes,local,world,geometryLocal,geometryWorld,physicsDeltas);
    if(result<0) throw new AssetFormatException("Native FBX physics geometry failed: "+nError());
    return result==0;
  }
  static boolean available() {
    try { ensureLoaded(); return true; } catch (Throwable failure) { loadFailure=failure; return false; }
  }
  static synchronized void release() { if (loaded != null) nRelease(); }
  static String unavailableReason() { return loadFailure==null?"unknown":loadFailure.toString(); }
  private static JsonResult call(byte[] output) throws AssetFormatException {
    if (output == null) throw new AssetFormatException("Native FBX bridge failed: " + nError());
    return new JsonResult(output);
  }
  private static void ensureLoaded() {
    if (loaded != null) return;
    synchronized (LOAD_LOCK) {
      if (loaded != null) return;
      if (Boolean.getBoolean("ysm.runtime.javaOnly") || Boolean.getBoolean("ysm.runtime.fbxWasmOnly"))
        throw new IllegalStateException("Native FBX disabled by configuration");
      var override = System.getProperty("ysm.runtime.fbxLibrary", "");
      Path path = override.isBlank() ? findDefault() : Path.of(override);
      if (path == null || !Files.isRegularFile(path)) throw new UnsatisfiedLinkError("Native FBX library not installed");
      System.load(path.toAbsolutePath().normalize().toString());
      if (nAbi() != 1) throw new UnsatisfiedLinkError("ysmlib FBX ABI mismatch");
      loaded = path;
    }
  }
  private static Path findDefault() {
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
    String platform = os.startsWith("windows") ? "windows" : os.startsWith("mac") ? "macos" : os.startsWith("linux") ? "linux" : "";
    String cpu = arch.equals("amd64") || arch.equals("x86_64") ? "x64" : arch.equals("aarch64") || arch.equals("arm64") ? "arm64" : "";
    if (platform.isEmpty() || cpu.isEmpty()) return null;
    String name = platform.equals("windows") ? "ysmlib_fbx.dll" : platform.equals("macos") ? "libysmlib_fbx.dylib" : "libysmlib_fbx.so";
    return Path.of(System.getProperty("ysm.runtime.nativeDir", "ysmlib/natives")).resolve(platform + "-" + cpu).resolve(name);
  }
  record JsonResult(byte[] bytes) {
    /* JNI already returns an owned Java byte[]; cloning it here doubled the
       peak for large snapshots before parsing even began. The result is
       package-private and consumed synchronously by FbxReader. */
    String text() { return new String(bytes, StandardCharsets.UTF_8); }
  }
  record SnapshotJson(ByteBuffer buffer,int size) {
    InputStream input() {
      var view=buffer.asReadOnlyBuffer();view.position(0);view.limit(size);
      return new InputStream() {
        @Override public int read() { return view.hasRemaining()?Byte.toUnsignedInt(view.get()):-1; }
        @Override public int read(byte[] target,int offset,int length) {
          if(!view.hasRemaining()) return -1;if(length==0) return 0;int n=Math.min(length,view.remaining());view.get(target,offset,n);return n;
        }
        @Override public void close() throws IOException { }
      };
    }
    String preview() {
      var view=buffer.asReadOnlyBuffer();view.position(0);int n=Math.min(160,Math.min(size,view.remaining()));byte[] bytes=new byte[n];view.get(bytes);return new String(bytes,StandardCharsets.UTF_8);
    }
  }
  record Layout(int draws,int positions,int normals,int tangents,int bitangents,int nodes) {}
  private static native int nAbi();
  private static native byte[] nSource(byte[] source, int memoryBytes, int outputBytes, int elements, int stringBytes);
  private static native byte[] nEvaluate(byte[] source, int memoryBytes, int outputBytes, int elements, int stringBytes, int stack, double seconds, boolean followMesh);
  private static native int[] nPrepareSelected(byte[] source, int memoryBytes, int outputBytes, int elements, int stringBytes, int[] meshElements, int[] skinElements);
  private static native int nGeometryEvaluate(int stack,double seconds,boolean followMesh,ByteBuffer positions,ByteBuffer normals,ByteBuffer tangents,ByteBuffer bitangents,ByteBuffer drawNodes,ByteBuffer drawMeshes,ByteBuffer local,ByteBuffer world,ByteBuffer geometryLocal,ByteBuffer geometryWorld);
  private static native int nEvaluateNodes(int stack,double seconds,int nodes,ByteBuffer local,ByteBuffer world,ByteBuffer geometryLocal,ByteBuffer geometryWorld);
  private static native int nGeometryWrite(boolean followMesh,ByteBuffer positions,ByteBuffer normals,ByteBuffer tangents,ByteBuffer bitangents,ByteBuffer drawNodes,ByteBuffer drawMeshes,ByteBuffer local,ByteBuffer world,ByteBuffer geometryLocal,ByteBuffer geometryWorld,ByteBuffer physicsDeltas);
  private static native String nError();
  private static native void nRelease();
}
