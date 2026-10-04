package cc.sirrus.ysmlib.scene.vrm;

import cc.sirrus.ysmlib.scene.*;
import java.util.*;

/** Source shader defaults remain versioned. No lossy VRM 0 -> MToon 1 conversion. */
final class VrmMaterialDefaults {
  static final String MTOON="VRMC_materials_mtoon/";
  static final List<String> LEGACY_TEXTURES=List.of("_MainTex","_ShadeTexture","_BumpMap","_EmissionMap","_OutlineWidthTexture",
      "_ReceiveShadowTexture","_RimTexture","_ShadingGradeTexture","_SphereAdd","_UvAnimMaskTexture");
  private VrmMaterialDefaults() {}
  static Map<String,FloatData> values(VrmDocument document,int index) {
    var material=document.scene().materials().get(index);var out=new LinkedHashMap<>(material.parameters());
    if(VrmJson.extensions(material.metadata()).has("VRMC_materials_mtoon")) {
      vector(out,MTOON+"shadeColorFactor",1,1,1);vector(out,MTOON+"matcapFactor",1,1,1);
      vector(out,MTOON+"parametricRimColorFactor",0,0,0);vector(out,MTOON+"outlineColorFactor",0,0,0);
      scalar(out,MTOON+"shadingShiftFactor",0);scalar(out,MTOON+"shadingToonyFactor",.9f);scalar(out,MTOON+"giEqualizationFactor",.9f);
      scalar(out,MTOON+"rimLightingMixFactor",1);scalar(out,MTOON+"parametricRimFresnelPowerFactor",5);scalar(out,MTOON+"parametricRimLiftFactor",0);
      scalar(out,MTOON+"outlineWidthFactor",0);scalar(out,MTOON+"outlineLightingMixFactor",1);
      for(String key:List.of("uvAnimationScrollXSpeedFactor","uvAnimationScrollYSpeedFactor","uvAnimationRotationSpeedFactor")) scalar(out,MTOON+key,0);
    }
    if(document.version()==VrmDocument.Version.VRM_0) {
      var legacy=document.legacyMaterials().isEmpty()?null:document.legacyMaterials().get(index);
      if(legacy!=null) out.putAll(legacy.values());
      boolean toon=legacy!=null && legacy.shader().equals("VRM/MToon");
      if(toon) {
        vector(out,"_Color",1,1,1,1);vector(out,"_ShadeColor",.97f,.81f,.86f,1);vector(out,"_RimColor",0,0,0,1);
        vector(out,"_EmissionColor",0,0,0,1);vector(out,"_OutlineColor",0,0,0,1);
        String[] names={"_Cutoff","_BumpScale","_ReceiveShadowRate","_ShadingGradeRate","_ShadeShift","_ShadeToony","_LightColorAttenuation",
          "_IndirectLightIntensity","_RimLightingMix","_RimFresnelPower","_RimLift","_OutlineWidth","_OutlineScaledMaxDistance","_OutlineLightingMix",
          "_UvAnimScrollX","_UvAnimScrollY","_UvAnimRotation","_MToonVersion","_DebugMode","_BlendMode","_OutlineWidthMode","_OutlineColorMode",
          "_CullMode","_OutlineCullMode","_SrcBlend","_DstBlend","_ZWrite","_AlphaToMask"};
        float[] defaults={.5f,1,1,1,0,.9f,0,.1f,0,1,0,.5f,1,1,0,0,0,39,0,0,0,0,2,1,1,0,1,0};
        for(int i=0;i<names.length;i++) scalar(out,names[i],defaults[i]);
      }
      out.putIfAbsent("_Color",out.getOrDefault("baseColor",new FloatData(1,1,1,1)));
      var emission=out.getOrDefault("emissive",new FloatData(0,0,0));out.putIfAbsent("_EmissionColor",new FloatData(emission.get(0),emission.get(1),emission.get(2),1));
      for(String slot:LEGACY_TEXTURES) {
        var exported=out.get(slot); // VRM 0 serializes offset.xy,scale.zw; Unity's _ST is scale.xy,offset.zw.
        if(exported!=null && exported.size()!=4) throw new IllegalArgumentException("Invalid legacy texture transform: "+slot);
        out.putIfAbsent(slot+"_ST",exported==null?new FloatData(1,1,0,0):new FloatData(exported.get(2),exported.get(3),exported.get(0),exported.get(1)));
      }
    }
    return out;
  }
  private static void scalar(Map<String,FloatData> out,String key,float value) { vector(out,key,value); }
  private static void vector(Map<String,FloatData> out,String key,float... values) { out.putIfAbsent(key,new FloatData(values)); }
}
