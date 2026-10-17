package cc.sirrus.ysmlib.scene.fbx;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.*;
import com.google.gson.stream.JsonReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Bounded, memory-only document reader. External dependency access is never inferred from stored file paths. */
public final class FbxReader {
  public FbxDocument read(ByteData source,ReadLimits limits) throws AssetFormatException {
    if (!Boolean.getBoolean("ysm.runtime.fbxWasmOnly") && !Boolean.getBoolean("ysm.runtime.javaOnly")) {
      try {
        var nativeSnapshot=FbxNativeReactor.source(source,limits);
        try (var input=nativeSnapshot.input();
             var reader=new JsonReader(new InputStreamReader(input,StandardCharsets.UTF_8))) {
          boolean compactMorphs=Boolean.getBoolean("ysm.runtime.fbxCompactMorphSource")
              || (!Boolean.getBoolean("ysm.runtime.javaOnly")&&!Boolean.getBoolean("ysm.runtime.fbxWasmOnly")&&FbxNativeReactor.available());
          return FbxSnapshots.document(source,reader,compactMorphs);
        }
        catch (RuntimeException malformed) {
          throw new AssetFormatException("Native FBX snapshot is malformed (bytes="+nativeSnapshot.size()+"): "+nativeSnapshot.preview(),malformed);
        } catch (OutOfMemoryError exhausted) {
          throw new AssetFormatException("Native FBX snapshot exceeded the JVM working budget (bytes="+nativeSnapshot.size()+")",exhausted);
        } catch (java.io.IOException malformed) {
          throw new AssetFormatException("Native FBX snapshot is malformed",malformed);
        }
      } catch (UnsatisfiedLinkError|SecurityException|IllegalStateException unavailable) {
        System.getLogger(FbxReader.class.getName()).log(System.Logger.Level.DEBUG,"Native FBX unavailable; using WASM reactor",unavailable);
      }
    }
    try(var reactor=new FbxReactor(source,limits)) { return FbxSnapshots.document(source,reactor.source()); }
    catch(IllegalArgumentException|IllegalStateException|ArithmeticException e) { throw new AssetFormatException("Invalid FBX source: "+e.getMessage(),e); }
  }
}
