package cc.sirrus.ysmlib.scene.fbx;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.*;
import java.io.UncheckedIOException;
import java.util.*;

/** Native-independent FBX transform/curve session. Source deformer/constraint gaps are explicitly rejected. */
public final class FbxPlayer implements FbxEvaluation {
  private final FbxReactor reactor;
  private final CompatibilityReport coverage;
  private final Settings settings;
  public FbxPlayer(FbxDocument source,ReadLimits limits) throws AssetFormatException {
    this(source,limits,Settings.DEFAULT);
  }
  public FbxPlayer(FbxDocument source,ReadLimits limits,Settings settings) throws AssetFormatException {
    Objects.requireNonNull(source);
    this.settings=Objects.requireNonNull(settings);
    // Pinned ufbx element enumeration: line/NURBS/trim/procedural geometry are not polygon meshes.
    for(var element:source.elements()) if(element.type()>=7 && element.type()<=12)
      throw new UnsupportedOperationException("FBX non-polygon geometry needs a dedicated adapter: "+element.name()+" (type "+element.type()+")");
    if(source.constraintCount()!=0) throw new UnsupportedOperationException("FBX constraints require source-compatible evaluation before playback");
    for(var mesh:source.meshes()) {
      if(mesh.skins().size()>1) throw new UnsupportedOperationException("FBX multiple skin deformers require an ordered source adapter");
      if(mesh.cacheDeformers()!=0 || mesh.subdivisionLevel()!=0) throw new UnsupportedOperationException("FBX cache/subdivision evaluation is not yet connected");
    }
    for(var skin:source.skins()) for(var cluster:skin.clusters()) if(!cluster.linkMode().isEmpty()&&!cluster.linkMode().equals("Normalize"))
      throw new UnsupportedOperationException("FBX cluster link mode requires a dedicated adapter: "+cluster.linkMode());
    coverage=new CompatibilityReport(List.of(new CompatibilityReport.Feature("fbx.transforms",CompatibilityReport.Level.EVALUATED,true,
        "Original curves, layers, pivot and inherit modes evaluated in the pinned ufbx reactor"),
        new CompatibilityReport.Feature("fbx.geometry",CompatibilityReport.Level.EVALUATED,true,
        "Per-instance sparse in-between morphs followed by full-weight skin; authored corner normals and all UV directions retained"),
        new CompatibilityReport.Feature("fbx.light-shadow",CompatibilityReport.Level.EXTERNAL_EFFECT,false,
        "Animated light and shadow properties provided to host effect extensions"),
        new CompatibilityReport.Feature("fbx.materials-and-other-elements",CompatibilityReport.Level.READ,true,
        "Source properties preserved; shared-scene, material and host adapters remain in progress")),List.of());
    reactor=new FbxReactor(source.source(),limits);
  }
  public Frame evaluate(int stack,double seconds) {
    try { return FbxSnapshots.frame(reactor.evaluate(stack,seconds,settings.skinSpace()==SkinSpace.FOLLOW_MESH_NODE),seconds,coverage); }
    catch(AssetFormatException e) { throw new UncheckedIOException(e); }
  }
  @Override public void close() { reactor.close(); }
}
