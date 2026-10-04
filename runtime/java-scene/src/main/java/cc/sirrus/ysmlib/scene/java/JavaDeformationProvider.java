package cc.sirrus.ysmlib.scene.java;

import cc.sirrus.ysmlib.scene.*;

/** Portable deformation oracle, independent of optional acceleration. */
public final class JavaDeformationProvider implements DeformationProvider {
  public String id() { return "java-deformation-1"; }
  public Session compile(MeshAsset.Primitive mesh) {
    var deformer=new Deformer();
    return (palette,weights,policy)->deformer.deform(mesh,palette,weights,
        policy==SceneProvider.NormalPolicy.MMD_WEIGHTED_ROTATION?Deformer.NormalMode.MMD_WEIGHTED_ROTATION:Deformer.NormalMode.INVERSE_TRANSPOSE);
  }
}
