package cc.sirrus.ysmlib.scene;

/** Immutable geometry plan. Repeated views of one pose never evaluate animation or advance physics. */
@FunctionalInterface
public interface SceneGeometry {
  GeometryFrame compile(ScenePose pose);
}
