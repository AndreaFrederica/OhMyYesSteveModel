package cc.sirrus.ysmlib.scene.io;

import java.io.IOException;

public final class AssetFormatException extends IOException {
  public AssetFormatException(String message) { super(message); }
  public AssetFormatException(String message,Throwable cause) { super(message,cause); }
}
