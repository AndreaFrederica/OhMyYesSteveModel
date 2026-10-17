package cc.sirrus.ysmlib.scene.fbx;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.*;
import java.io.UncheckedIOException;
import java.util.*;

/** Native-independent FBX transform/curve session. Source deformer/constraint gaps are explicitly rejected. */
public final class FbxPlayer implements FbxEvaluation {
  private static final int FRAME_CACHE_LIMIT = Integer.getInteger("ysm.fbx.frameCacheEntries", 1);
  private final FbxReactor reactor;
  private final FbxDocument source;
  private final CompatibilityReport coverage;
  private final Settings settings;
  /** Small bounded cache for repeated editor scrubs/seek probes. Dynamic arrays are never retained unboundedly. */
  private final Map<FrameKey,Frame> frameCache = Collections.synchronizedMap(new LinkedHashMap<>(8, .75f, true) {
    @Override protected boolean removeEldestEntry(Map.Entry<FrameKey,Frame> eldest) { return size() > Math.max(0, FRAME_CACHE_LIMIT); }
  });
  public FbxPlayer(FbxDocument source,ReadLimits limits) throws AssetFormatException {
    this(source,limits,Settings.DEFAULT);
  }
  public FbxPlayer(FbxDocument source,ReadLimits limits,Settings settings) throws AssetFormatException {
    this.source=Objects.requireNonNull(source);
    this.settings=Objects.requireNonNull(settings);
    // Pinned ufbx element enumeration: line/NURBS/trim/procedural geometry are not polygon meshes.
    for(var element:source.elements()) if(element.type()>=7 && element.type()<=12)
      throw new UnsupportedOperationException("FBX non-polygon geometry needs a dedicated adapter: "+element.name()+" (type "+element.type()+")");
    if(source.constraintCount()!=0) throw new UnsupportedOperationException("FBX constraints require source-compatible evaluation before playback");
    for(int meshIndex=0;meshIndex<source.meshes().size();meshIndex++) {
      var mesh=source.meshes().get(meshIndex);
      try { FbxSkinningSupport.validate(source,meshIndex); }
      catch(IllegalArgumentException unsupportedSkin) {
        throw new UnsupportedOperationException(unsupportedSkin.getMessage(),unsupportedSkin);
      }
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
    var key=new FrameKey(stack,Double.doubleToLongBits(seconds),settings.skinSpace());
    var cached=frameCache.get(key);if(cached!=null)return cached;
    try {
      var frame=reactor.evaluateTyped(source,stack,seconds,settings.skinSpace()==SkinSpace.FOLLOW_MESH_NODE,coverage);
      // Lights and cameras are small dynamic records. Read only those fields
      // after the typed geometry pass instead of serializing the whole frame.
      var details=FbxSnapshots.dynamicDetails(reactor.evaluateDetails(),source.details());
      frame=new FbxEvaluation.Frame(frame.seconds(),frame.nodes(),frame.meshes(),frame.elements(),details,frame.textures(),frame.draws(),frame.compatibility());
      frameCache.put(key,frame);return frame;
    }
    catch(AssetFormatException e) { throw new UncheckedIOException(e); }
  }
  private record FrameKey(int stack,long seconds,SkinSpace skinSpace) {}
  @Override public void close() { frameCache.clear(); reactor.close(); }
}
