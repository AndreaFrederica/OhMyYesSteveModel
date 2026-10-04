package cc.sirrus.ysmlib.scene.physics;

/** A provider owns algorithm code, while each caller owns its world and simulation clock. */
public interface PhysicsProvider {
  String id();
  default PhysicsCapabilities capabilities() { return new PhysicsCapabilities(id(), 0, 0, false); }
  PhysicsWorld createWorld(PhysicsSpec.World settings);
}
