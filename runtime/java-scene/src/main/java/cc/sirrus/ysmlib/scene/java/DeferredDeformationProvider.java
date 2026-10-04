package cc.sirrus.ysmlib.scene.java;
import cc.sirrus.ysmlib.scene.*;
/** Host GPU owner explicitly requests pose-only frames and must resolve them before drawing. */
public final class DeferredDeformationProvider implements DeformationProvider {
  public String id(){return "host-deferred-deformation";}
  public boolean deferred(){return true;}
  public Session compile(MeshAsset.Primitive mesh){return (palette,weights,policy)->mesh.attributes();}
}
