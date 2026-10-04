package cc.sirrus.ysmlib.scene.vrm;

import cc.sirrus.ysmlib.scene.*;
import java.util.*;
import static cc.sirrus.ysmlib.scene.vrm.VrmDocument.*;

/** Composes weights from immutable bases on every call; repeated preview sampling cannot accumulate drift. */
public final class VrmExpressionEvaluator implements VrmExpressions {
  private static final String MTOON="VRMC_materials_mtoon/";
  private final VrmDocument document;
  private final Map<String,Expression> expressions;
  private final List<Map<String,FloatData>> bases;
  private final List<Map<String,Uv>> textureBases;
  private record Uv(float sx,float sy,float ox,float oy,float angle) {
    float[] matrix(float[] v) { float c=(float)Math.cos(angle),s=(float)Math.sin(angle);return new float[]{c*v[0],s*v[0],0,-s*v[1],c*v[1],0,v[2],v[3],1}; }
    float[] values() { return new float[]{sx,sy,ox,oy}; }
  }
  public VrmExpressionEvaluator(VrmDocument document) {
    this.document=Objects.requireNonNull(document);var map=new LinkedHashMap<String,Expression>();
    for(var e:document.expressions()) if(map.put(e.key(),e)!=null) throw new IllegalArgumentException("Duplicate expression key");expressions=Map.copyOf(map);
    var b=new ArrayList<Map<String,FloatData>>();var tb=new ArrayList<Map<String,Uv>>();
    for(int m=0;m<document.scene().materials().size();m++) {
      var material=document.scene().materials().get(m);var base=VrmMaterialDefaults.values(document,m);var textures=new LinkedHashMap<String,Uv>();
      for(var entry:material.textures().entrySet()) {
        var ext=VrmJson.object(VrmJson.extensions(entry.getValue().metadata()),"KHR_texture_transform");
        var scale=VrmJson.vector(ext,"scale",2,1,1);var offset=VrmJson.vector(ext,"offset",2,0,0);
        textures.put(entry.getKey(),new Uv(scale.get(0),scale.get(1),offset.get(0),offset.get(1),VrmJson.number(ext,"rotation",0)));
      }
      b.add(Map.copyOf(base));tb.add(Map.copyOf(textures));
    }bases=List.copyOf(b);textureBases=List.copyOf(tb);
    // Reject an unevaluable custom shader bind explicitly; its raw source is still available in the document.
    for(var e:document.expressions()) for(var bind:e.materials()) {
      String key=parameter(bind);if(key==null) continue;FloatData base=bases.get(bind.material()).get(key);
      if(base==null) throw new IllegalArgumentException("No declared base for material property: "+bind.property());
      if(bind.target().size()<base.size()) throw new IllegalArgumentException("Material expression component count mismatch");
    }
  }
  public Frame evaluate(Map<String,Float> weights) { return evaluate(weights,Map.of()); }
  public Frame evaluate(Map<String,Float> weights,Map<String,Category> customCategories) {
    for(var e:weights.entrySet()) { if(!expressions.containsKey(e.getKey())) throw new IllegalArgumentException("Unknown expression: "+e.getKey());if(e.getValue()==null || !Float.isFinite(e.getValue())) throw new IllegalArgumentException("Non-finite expression weight"); }
    for(var e:customCategories.entrySet()) {
      var expression=expressions.get(e.getKey());if(expression==null || expression.category()!=Category.OTHER || e.getValue()==null) throw new IllegalArgumentException("Category override requires a custom/non-procedural expression");
    }
    var effective=new LinkedHashMap<String,Float>();var categories=new HashMap<String,Category>();var overrides=new EnumMap<Category,Float>(Category.class);
    for(var e:document.expressions()) {
      float w=Math.max(0,Math.min(1,weights.getOrDefault(e.key(),0f)));if(e.binary()) w=w>.5f?1:0;
      effective.put(e.key(),w);Category category=customCategories.getOrDefault(e.key(),e.category());categories.put(e.key(),category);
      for(var target:List.of(Category.BLINK,Category.MOUTH,Category.LOOK_AT)) {
        if(category==target) continue;
        var operation=switch(target) { case BLINK->e.blink();case MOUTH->e.mouth();case LOOK_AT->e.lookAt();default->throw new AssertionError(); };
        float amount=switch(operation) { case NONE->0;case BLOCK->w>0?1:0;case BLEND->w; };
        overrides.merge(target,amount,Float::sum);
      }
    }
    for(var e:document.expressions()) {
      float amount=overrides.getOrDefault(categories.get(e.key()),0f),w=effective.get(e.key());
      effective.put(e.key(),e.binary()&&amount>0?0:w*Math.max(0,1-amount));
    }
    var morphs=new ArrayList<float[]>();for(var node:document.scene().nodes()) {
      int count=node.mesh()==-1?0:document.scene().meshes().get(node.mesh()).primitives().get(0).morphs().size();morphs.add(new float[count]);
    }
    var materials=new ArrayList<Map<String,float[]>>();var textures=new ArrayList<Map<String,float[]>>();
    for(int m=0;m<bases.size();m++) { var values=new LinkedHashMap<String,float[]>();bases.get(m).forEach((k,v)->values.put(k,v.copy()));materials.add(values);
      var uv=new LinkedHashMap<String,float[]>();textureBases.get(m).forEach((k,v)->uv.put(k,v.values()));textures.add(uv); }
    for(var e:document.expressions()) {
      float weight=effective.get(e.key());if(weight==0) continue;
      for(var bind:e.morphs()) morphs.get(bind.node())[bind.index()]+=weight*bind.weight();
      for(var bind:e.materials()) {
        String key=parameter(bind);if(key==null) continue;float[] target=materials.get(bind.material()).get(key);var base=bases.get(bind.material()).get(key);
        for(int i=0;i<target.length;i++) {
          if(bind.property().endsWith("_ST_S") && i!=0 && i!=2 || bind.property().endsWith("_ST_T") && i!=1 && i!=3) continue;
          target[i]+=(bind.target().get(i)-base.get(i))*weight;
        }
        if(document.version()==Version.VRM_0 && key.equals("_MainTex_ST")) {
          var t=bind.target();boolean s=bind.property().endsWith("_ST_S"),v=bind.property().endsWith("_ST_T");
          float sx=v?base.get(0):t.get(0),sy=s?base.get(1):t.get(1),ox=v?base.get(2):t.get(2),oy=s?base.get(3):t.get(3);
          applyTexture(textures,bind.material(),new FloatData(sx,sy),new FloatData(ox,1-oy-sy),weight,!v,!s);
        }
      }
      for(var bind:e.textures()) applyTexture(textures,bind.material(),bind.scale(),bind.offset(),weight);
    }
    var output=new ArrayList<MaterialState>();for(int m=0;m<materials.size();m++) {
      var values=new LinkedHashMap<String,FloatData>();materials.get(m).forEach((k,v)->values.put(k,new FloatData(v)));
      // Legacy shader colors remain in their source color space. They are not glTF linear factors.
      var uv=new LinkedHashMap<String,FloatData>();for(var entry:textures.get(m).entrySet()) uv.put(entry.getKey(),new FloatData(textureBases.get(m).get(entry.getKey()).matrix(entry.getValue())));
      output.add(new MaterialState(values,uv));
    }
    return new Frame(effective,morphs.stream().map(FloatData::new).toList(),output);
  }
  private String parameter(MaterialBind bind) {
    if(document.version()==Version.VRM_0) return bind.property().endsWith("_ST_S") || bind.property().endsWith("_ST_T")?bind.property().substring(0,bind.property().length()-2):bind.property();
    var material=document.scene().materials().get(bind.material());boolean toon=VrmJson.extensions(material.metadata()).has("VRMC_materials_mtoon");
    return switch(bind.property()) {
      case "color" -> "baseColor";
      case "emissionColor" -> !toon && material.workflow().equals("unlit")?null:"emissive";
      case "shadeColor" -> toon?MTOON+"shadeColorFactor":null;
      case "matcapColor" -> toon?MTOON+"matcapFactor":null;
      case "rimColor" -> toon?MTOON+"parametricRimColorFactor":null;
      case "outlineColor" -> toon?MTOON+"outlineColorFactor":null;
      default -> throw new IllegalArgumentException("Unknown VRM material property");
    };
  }
  private void applyTexture(List<Map<String,float[]>> textures,int material,FloatData scale,FloatData offset,float weight) {
    applyTexture(textures,material,scale,offset,weight,true,true);
  }
  private void applyTexture(List<Map<String,float[]>> textures,int material,FloatData scale,FloatData offset,float weight,boolean horizontal,boolean vertical) {
    for(var entry:textures.get(material).entrySet()) {
      if(entry.getKey().equals(MTOON+"matcapTexture")) continue;
      float[] base=textureBases.get(material).get(entry.getKey()).values(),target={scale.get(0),scale.get(1),offset.get(0),offset.get(1)},out=entry.getValue();
      for(int i=0;i<4;i++) if(i%2==0?horizontal:vertical) out[i]+=(target[i]-base[i])*weight;
    }
  }
}
