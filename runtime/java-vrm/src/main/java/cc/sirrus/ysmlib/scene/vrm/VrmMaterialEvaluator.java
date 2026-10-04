package cc.sirrus.ysmlib.scene.vrm;

import cc.sirrus.ysmlib.scene.*;
import java.util.*;
import static cc.sirrus.ysmlib.scene.vrm.VrmMaterialDefaults.MTOON;
import static cc.sirrus.ysmlib.scene.vrm.VrmJson.*;

/** MToon 0 keeps its original shader inputs, render queue, extra textures and UV algorithm. */
public final class VrmMaterialEvaluator implements VrmMaterials {
  private final VrmDocument document;
  private final List<VrmExpressions.MaterialState> bases;
  public VrmMaterialEvaluator(VrmDocument document) {
    this.document=Objects.requireNonNull(document);
    bases=new VrmExpressionEvaluator(document).evaluate(Map.of()).materials();
  }
  public Frame evaluate(List<VrmExpressions.MaterialState> expressions,double seconds) {
    if(!Double.isFinite(seconds) || seconds<0) throw new IllegalArgumentException("Invalid material time");
    var input=expressions==null?bases:expressions;if(input.size()!=document.scene().materials().size()) throw new IllegalArgumentException("Material state count mismatch");
    var output=new ArrayList<Material>();for(int m=0;m<input.size();m++) {
      var source=document.scene().materials().get(m);var old=document.legacyMaterials().isEmpty()?null:document.legacyMaterials().get(m);
      boolean legacy=old!=null && !old.shader().equals("VRM_USE_GLTFSHADER");var state=input.get(m);
      output.add(legacy?legacy(m,old,state,seconds):modern(m,source,state,seconds));
    }return new Frame(output);
  }
  private Material modern(int m,SceneAsset.Material source,VrmExpressions.MaterialState state,double seconds) {
    var ext=extensions(source.metadata());boolean toon=ext.has("VRMC_materials_mtoon");var t=object(ext,"VRMC_materials_mtoon");if(toon) version(t,"VRMC_materials_mtoon");
    var parameters=new LinkedHashMap<>(state.parameters());
    boolean blend=source.alphaMode().equals("BLEND"),mask=source.alphaMode().equals("MASK"),zwrite=toon && bool(t,"transparentWithZWrite",false);
    int offset=toon?integer(t,"renderQueueOffsetNumber",0):0;
    if(offset<(blend&&!zwrite?-9:0) || offset>(blend&&zwrite?9:0)) throw new IllegalArgumentException("MToon queue offset outside alpha-mode range");
    String outline=toon?string(t,"outlineWidthMode","none"):"none";
    if(!Set.of("none","worldCoordinates","screenCoordinates").contains(outline)) throw new IllegalArgumentException("Invalid MToon outline mode");
    var textures=new LinkedHashMap<String,Texture>();
    for(var e:source.textures().entrySet()) {
      String key=e.getKey();boolean matcap=key.equals(MTOON+"matcapTexture"),uvMask=key.equals(MTOON+"uvAnimationMaskTexture");
      var binding=withTransform(e.getValue(),state.textureTransforms().getOrDefault(key,e.getValue().uvTransform()));
      int channel=key.equals(MTOON+"shadingShiftTexture")?0:key.equals(MTOON+"outlineWidthMultiplyTexture")?1:uvMask?2:-1;
      boolean srgb=Set.of("baseColor","emissive",MTOON+"shadeMultiplyTexture",MTOON+"matcapTexture",MTOON+"rimMultiplyTexture").contains(key);
      boolean animated=toon && Set.of("baseColor","emissive","normal",MTOON+"shadeMultiplyTexture",MTOON+"shadingShiftTexture",MTOON+"rimMultiplyTexture",MTOON+"outlineWidthMultiplyTexture").contains(key);
      textures.put(key,new Texture(binding,srgb?Transfer.SRGB:Transfer.LINEAR,channel,matcap?UvRule.MATCAP:animated?UvRule.MTOON_1:UvRule.STATIC));
    }
    var render=new RenderState(source.alphaMode(),source.alphaCutoff(),!blend||zwrite,source.doubleSided()?Cull.NONE:Cull.BACK,
        blend?5:1,blend?10:0,false,blend?(zwrite?2:3):mask?1:0,offset,-1,outline,Cull.FRONT);
    var features=new ArrayList<CompatibilityReport.Feature>();
    features.add(new CompatibilityReport.Feature(toon?"vrm.material.mtoon1":"vrm.material.gltf",CompatibilityReport.Level.EVALUATED,true,"Material inputs and UV semantics; host shader remains separate"));
    for(String extension:ext.keySet()) if(!Set.of("VRMC_materials_mtoon","KHR_materials_unlit").contains(extension))
      features.add(new CompatibilityReport.Feature("vrm.material.extension/"+extension,CompatibilityReport.Level.READ,true,"Source preserved; material extension evaluation requires an adapter"));
    var diagnostics=new ArrayList<CompatibilityReport.Diagnostic>();
    if(toon&&!t.has("shadeColorFactor")) diagnostics.add(new CompatibilityReport.Diagnostic(CompatibilityReport.Severity.INFO,"materials/"+m,
        "Missing shadeColorFactor uses the JSON schema's white default, also used by UniVRM; the prose and three-vrm use black. Explicit source values are unambiguous."));
    return new Material(m,toon?"VRMC_materials_mtoon":source.workflow(),toon?Shader.MTOON_1:Shader.GLTF,ParameterSpace.GLTF_LINEAR,parameters,textures,Map.of(),render,seconds,new CompatibilityReport(features,diagnostics));
  }
  private Material legacy(int m,VrmDocument.LegacyMaterial old,VrmExpressions.MaterialState state,double seconds) {
    boolean toon=old.shader().equals("VRM/MToon"),unlit=Set.of("VRM/UnlitTexture","VRM/UnlitCutout","VRM/UnlitTransparent","VRM/UnlitTransparentZWrite").contains(old.shader());
    var p=state.parameters();boolean blend=toon?old.keywords().getOrDefault("_ALPHABLEND_ON",false):old.shader().contains("Transparent");
    boolean mask=toon?old.keywords().getOrDefault("_ALPHATEST_ON",false):old.shader().equals("VRM/UnlitCutout");
    boolean depth=toon?scalar(p,"_ZWrite",1)!=0:!blend||old.shader().endsWith("ZWrite");
    int queue=old.renderQueue()==-1?(blend?3000:mask?2450:2000):old.renderQueue();
    int mode=toon?enumValue(p,"_OutlineWidthMode",0,2):0;
    var render=new RenderState(blend?"BLEND":mask?"MASK":"OPAQUE",scalar(p,"_Cutoff",.5f),depth,toon?cull(p,"_CullMode",2):Cull.BACK,
        toon?enumValue(p,"_SrcBlend",1,10):blend?5:1,toon?enumValue(p,"_DstBlend",0,10):blend?10:0,toon&&scalar(p,"_AlphaToMask",0)!=0,
        blend?(depth?2:3):mask?1:0,0,queue,mode==0?"none":mode==1?"worldCoordinates":"screenCoordinates",toon?cull(p,"_OutlineCullMode",1):Cull.FRONT);
    var textures=new LinkedHashMap<String,Texture>();
    for(var e:old.textures().entrySet()) {
      if(e.getValue()<0) continue;String key=e.getKey();boolean matcap=key.equals("_SphereAdd");
      var st=p.getOrDefault(toon?"_MainTex_ST":key+"_ST",new FloatData(1,1,0,0));if(st.size()!=4) throw new IllegalArgumentException("Invalid legacy texture transform");
      var matrix=matcap?new FloatData(1,0,0,0,1,0,0,0,1):new FloatData(st.get(0),0,0,0,st.get(1),0,st.get(2),1-st.get(1)-st.get(3),1);
      boolean known=VrmMaterialDefaults.LEGACY_TEXTURES.contains(key);
      boolean channel=Set.of("_ReceiveShadowTexture","_ShadingGradeTexture","_OutlineWidthTexture","_UvAnimMaskTexture").contains(key);
      boolean animated=toon&&!matcap&&!key.equals("_OutlineWidthTexture")&&!key.equals("_UvAnimMaskTexture");
      var binding=new SceneAsset.TextureBinding(e.getValue(),0,matrix,key.equals("_BumpMap")?scalar(p,"_BumpScale",1):1,Map.of());
      // UniVRM 0's actual texture importer creates every slot except normals as sRGB, including masks.
      textures.put(key,new Texture(binding,!known?Transfer.SOURCE_DEFINED:key.equals("_BumpMap")?Transfer.LINEAR:Transfer.SRGB,channel?0:-1,matcap?UvRule.MATCAP:animated?UvRule.MTOON_0:UvRule.STATIC));
    }
    boolean supported=toon||unlit;var features=List.of(new CompatibilityReport.Feature("vrm.material."+old.shader(),supported?CompatibilityReport.Level.EVALUATED:CompatibilityReport.Level.UNSUPPORTED,true,
        supported?"Versioned source shader inputs and UV semantics; host shader remains separate":"Custom shader source retained; requires an explicit host adapter"));
    return new Material(m,old.shader(),toon?Shader.MTOON_0:unlit?Shader.LEGACY_UNLIT:Shader.CUSTOM,ParameterSpace.LEGACY_SHADER,p,textures,old.keywords(),render,seconds,new CompatibilityReport(features,List.of()));
  }
  public FloatData uv(Material material,String slot,float u,float v,float mask) {
    if(!Float.isFinite(u)||!Float.isFinite(v)||!Float.isFinite(mask)||mask<0||mask>1) throw new IllegalArgumentException("Invalid material UV/mask");
    var texture=material.textures().get(slot);if(texture==null) throw new IllegalArgumentException("Unknown material texture slot: "+slot);
    double x=u,y=v;var matrix=texture.binding().uvTransform();var p=material.parameters();double time=material.seconds()*mask;
    if(texture.uvRule()==UvRule.MTOON_1) {
      double a=scalar(p,MTOON+"uvAnimationRotationSpeedFactor",0)*time,c=Math.cos(a),s=Math.sin(a),dx=x-.5,dy=y-.5;
      x=c*dx-s*dy+.5+scalar(p,MTOON+"uvAnimationScrollXSpeedFactor",0)*time;
      y=s*dx+c*dy+.5+scalar(p,MTOON+"uvAnimationScrollYSpeedFactor",0)*time;
    }
    double tx=matrix.get(0)*x+matrix.get(3)*y+matrix.get(6),ty=matrix.get(1)*x+matrix.get(4)*y+matrix.get(7);x=tx;y=ty;
    if(texture.uvRule()==UvRule.MTOON_0) {
      // Source order: texture transform, scroll, then rotate in bottom-left UVs; rotations are turns/second.
      x+=scalar(p,"_UvAnimScrollX",0)*time;y-=scalar(p,"_UvAnimScrollY",0)*time;
      double a=-2*Math.PI*scalar(p,"_UvAnimRotation",0)*time,c=Math.cos(a),s=Math.sin(a),dx=x-.5,dy=y-.5;
      x=c*dx-s*dy+.5;y=s*dx+c*dy+.5;
    }
    return new FloatData((float)x,(float)y);
  }
  private static SceneAsset.TextureBinding withTransform(SceneAsset.TextureBinding b,FloatData transform) { return new SceneAsset.TextureBinding(b.texture(),b.texCoord(),transform,b.scale(),b.metadata()); }
  private static float scalar(Map<String,FloatData> p,String name,float fallback) {
    var value=p.get(name);if(value==null) return fallback;if(value.size()!=1) throw new IllegalArgumentException("Invalid scalar material property: "+name);return value.get(0);
  }
  private static int enumValue(Map<String,FloatData> p,String name,int fallback,int max) {
    float value=scalar(p,name,fallback);if(value<0||value>max||value!=(int)value) throw new IllegalArgumentException("Invalid material enum: "+name);return (int)value;
  }
  private static Cull cull(Map<String,FloatData> p,String name,int fallback) { return Cull.values()[enumValue(p,name,fallback,2)]; }
}
