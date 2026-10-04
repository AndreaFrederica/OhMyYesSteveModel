package cc.sirrus.ysmlib.scene.mmd;

import cc.sirrus.ysmlib.scene.ByteData;
import java.io.IOException;
import java.util.Locale;

/** Pinned, unmodified Saba resources; source and licensing details ship beside the images. */
public final class MmdSharedToons {
  private MmdSharedToons() {}
  public static ByteData read(int index) throws IOException {
    if(index<0 || index>9) throw new IllegalArgumentException("Shared MMD toon index must be in [0,9]");
    String path=String.format(Locale.ROOT,"/ysmlib/mmd/toon/toon%02d.bmp",index+1);
    try(var stream=MmdSharedToons.class.getResourceAsStream(path)) {
      if(stream==null) throw new IOException("Missing library MMD toon resource: "+path);
      // The fixed assets are under 4 KiB. A broken distribution cannot silently allocate an arbitrary stream.
      byte[] bytes=stream.readNBytes(4097);
      if(bytes.length>4096) throw new IOException("Invalid library MMD toon resource size");
      return new ByteData(bytes);
    }
  }
}
