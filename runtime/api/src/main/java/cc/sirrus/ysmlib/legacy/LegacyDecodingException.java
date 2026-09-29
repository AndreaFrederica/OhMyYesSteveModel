package cc.sirrus.ysmlib.legacy;

import java.io.IOException;

/** Stable capability failures, independent of host-specific status codes. */
public final class LegacyDecodingException extends IOException {
  public enum Reason {
    UNSUPPORTED_VERSION,
    RESOURCE_LIMIT
  }

  private final Reason reason;

  public LegacyDecodingException(Reason reason, String message) {
    super(message);
    this.reason = java.util.Objects.requireNonNull(reason);
  }

  public Reason reason() {
    return reason;
  }
}
