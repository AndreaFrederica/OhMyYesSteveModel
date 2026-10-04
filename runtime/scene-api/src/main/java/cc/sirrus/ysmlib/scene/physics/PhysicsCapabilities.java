package cc.sirrus.ysmlib.scene.physics;

/** Read-only implementation information used by diagnostics and safe provider selection. */
public record PhysicsCapabilities(String implementation, int abi, long featureBits, boolean nativeAcceleration) {
  public PhysicsCapabilities {
    if (implementation == null || implementation.isBlank() || abi < 0 || featureBits < 0) {
      throw new IllegalArgumentException("Invalid physics capability descriptor");
    }
  }
}
