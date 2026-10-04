package cc.sirrus.ysmlib.scene.mmd;

import cc.sirrus.ysmlib.scene.*;
import java.util.*;

/** Generates a small YSM-compatible locomotion clip when an MMD package has no VMD/VPD. */
public final class MmdBasicAnimationFactory {
  private MmdBasicAnimationFactory() {}

  public static AnimationClip forState(PmxDocument source,String state) {
    Objects.requireNonNull(source);var bones=new ArrayList<NamedBone>();
    for(var bone:source.bones()) bones.add(new NamedBone(bone.names().local(),bone.names().universal(),bone.ik()!=null));
    return build(bones,state,Map.of());
  }
  public static AnimationClip forState(PmdDocument source,String state) {
    Objects.requireNonNull(source);var bones=new ArrayList<NamedBone>();
    for(int i=0;i<source.bones().size();i++) {
      String english=source.english()==null || i>=source.english().bones().size()?"":source.english().bones().get(i);
      int index=i;bones.add(new NamedBone(source.bones().get(i).name(),english,source.ik().stream().anyMatch(k->k.controller()==index)));
    }
    return build(bones,state,Map.of());
  }

  public static AnimationClip forState(PmxDocument source,String state,Map<String,SceneModelProfile.BoneBinding> mappings) {
    return build(source.bones().stream().map(b->new NamedBone(b.names().local(),b.names().universal(),b.ik()!=null)).toList(),state,mappings);
  }
  public static AnimationClip forState(PmdDocument source,String state,Map<String,SceneModelProfile.BoneBinding> mappings) {
    var bones=new ArrayList<NamedBone>();for(int i=0;i<source.bones().size();i++) {int index=i;bones.add(new NamedBone(source.bones().get(i).name(),
        source.english()==null || i>=source.english().bones().size()?"":source.english().bones().get(i),source.ik().stream().anyMatch(k->k.controller()==index)));}
    return build(bones,state,mappings);
  }
  private static String semanticKey(Semantic s) {
    return switch(s.kind) { case CHEST -> "chest";case ARM -> s.left?"leftUpperArm":"rightUpperArm";case LEG -> s.left?"leftUpperLeg":"rightUpperLeg";default -> ""; };
  }
  private static AnimationClip build(List<NamedBone> bones,String state,Map<String,SceneModelProfile.BoneBinding> mappings) {
    String mode=normalize(state);double amplitude=switch(mode) {
      case "run" -> .9; case "walk" -> .55; case "sneak","sneaking" -> .25;
      case "swim","fly" -> .65; case "death" -> 1.0; default -> .08;
    };
    var tracks=new ArrayList<AnimationClip.Track>();
    for(int boneIndex=0;boneIndex<bones.size();boneIndex++) {
      var bone=bones.get(boneIndex);Semantic semantic=bone.ik?new Semantic(Kind.NONE,false):role(bone.local,bone.universal);
      SceneModelProfile.BoneBinding binding=mappings.get(semanticKey(semantic));
      if(binding!=null && binding.index()!=boneIndex) semantic=new Semantic(Kind.NONE,false);
      for(var entry:mappings.entrySet()) if(entry.getValue().index()==boneIndex) {
        semantic=switch(entry.getKey()) {
          case "chest" -> new Semantic(Kind.CHEST,false);
          case "leftUpperArm" -> new Semantic(Kind.ARM,true);case "rightUpperArm" -> new Semantic(Kind.ARM,false);
          case "leftUpperLeg" -> new Semantic(Kind.LEG,true);case "rightUpperLeg" -> new Semantic(Kind.LEG,false);
          default -> semantic;
        };binding=entry.getValue();
      }
      if(semantic.kind==Kind.NONE) continue;
      double phase=(semantic.left?Math.PI:0)+(semantic.kind==Kind.ARM?Math.PI:0);
      double[] times={0,.25,.5,.75,1,1.25,1.5,1.75,2};float[] values=new float[times.length*4];
      for(int i=0;i<times.length;i++) {
        double angle;
        if(mode.equals("death")) angle=semantic.kind==Kind.CHEST && i>0?-1.15:0;
        else if(semantic.kind==Kind.CHEST) angle=Math.sin(times[i]*Math.PI)*amplitude*.12;
        else angle=Math.sin(times[i]*Math.PI*2+phase)*amplitude;
        Rotation q=Rotation.axisAngle(new Vec3(1,0,0),angle*(binding==null?1:binding.weight()));
        if(binding!=null) {
          var basis=Rotation.axisAngle(new Vec3(0,0,1),Math.toRadians(binding.roll())).multiply(Rotation.axisAngle(new Vec3(0,1,0),Math.toRadians(binding.yaw())))
              .multiply(Rotation.axisAngle(new Vec3(1,0,0),Math.toRadians(binding.pitch())));
          q=basis.multiply(q).multiply(basis.inverse());
        }
        values[i*4]=q.x();values[i*4+1]=q.y();values[i*4+2]=q.z();values[i*4+3]=q.w();
      }
      tracks.add(new AnimationClip.Track(AnimationClip.Property.ROTATION,boneIndex,bone.local,"",
          new AnimationCurve(times,new FloatData(values),4,true,AnimationCurve.Interpolation.LINEAR,
              FloatData.EMPTY,FloatData.EMPTY,FloatData.EMPTY)));
    }
    // The fallback drives the leg bones directly; foot IK would otherwise pin them
    // to their rest targets. Only the conventional leg controllers are affected.
    if(tracks.stream().anyMatch(t -> t.property()==AnimationClip.Property.ROTATION)) {
      for(var bone:bones) if(mappings.entrySet().stream().anyMatch(e -> e.getKey().endsWith("Ik") && e.getValue().index()>=0 && e.getValue().name().equals(bone.local)) || matches(new String[]{normalize(bone.local),normalize(bone.universal)},
          "左足IK","右足IK","左つま先IK","右つま先IK","leftlegik","rightlegik","lefttoeik","righttoeik"))
        tracks.add(new AnimationClip.Track(AnimationClip.Property.IK_ENABLED,-1,bone.local,"",
            new AnimationCurve(new double[]{0},new FloatData(0),1,false,AnimationCurve.Interpolation.STEP,
                FloatData.EMPTY,FloatData.EMPTY,FloatData.EMPTY)));
    }
    return new AnimationClip("ysm-generated-"+mode,tracks,30);
  }

  private enum Kind { NONE,CHEST,ARM,LEG }
  private record Semantic(Kind kind,boolean left) {}
  private static Semantic role(String local,String universal) {
    String[] n={normalize(local),normalize(universal)};
    if(matches(n,"upperbody","chest","spine","上半身","胸")) return new Semantic(Kind.CHEST,false);
    if(matches(n,"leftarm","armleft","左腕")) return new Semantic(Kind.ARM,true);
    if(matches(n,"rightarm","armright","右腕")) return new Semantic(Kind.ARM,false);
    if(matches(n,"leftleg","legleft","leftthigh","左足")) return new Semantic(Kind.LEG,true);
    if(matches(n,"rightleg","legright","rightthigh","右足")) return new Semantic(Kind.LEG,false);
    return new Semantic(Kind.NONE,false);
  }
  private static boolean matches(String[] names,String... values) {
    for(String name:names) for(String value:values) if(name.equals(normalize(value))) return true;
    return false;
  }
  private static String normalize(String value) { return java.text.Normalizer.normalize(Objects.requireNonNullElse(value,""),java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\u4e00-\u9fffぁ-んァ-ン]+",""); }
  private record NamedBone(String local,String universal,boolean ik) {}
}
