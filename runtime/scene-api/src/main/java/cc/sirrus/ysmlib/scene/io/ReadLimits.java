package cc.sirrus.ysmlib.scene.io;

/** Per-import allocation limits, separate from transport or archive authorization. */
public record ReadLimits(int maxBytes,int maxElements,int maxStringBytes) {
  public static final ReadLimits DEFAULT=new ReadLimits(256*1024*1024,4_000_000,1024*1024);
  public ReadLimits {
    if(maxBytes<1 || maxElements<1 || maxStringBytes<1 || maxStringBytes>maxBytes)
      throw new IllegalArgumentException("Invalid read limits");
  }
}
