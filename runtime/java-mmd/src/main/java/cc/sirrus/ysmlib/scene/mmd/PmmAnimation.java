package cc.sirrus.ysmlib.scene.mmd;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.AssetFormatException;
import java.util.*;
import static cc.sirrus.ysmlib.scene.AnimationClip.Property.*;
import static cc.sirrus.ysmlib.scene.AnimationCurve.Interpolation.*;

/** Preserve PMM scene-only channels alongside the shared MMD curves. Project frames always use 30 fps. */
public final class PmmAnimation {
  public PmmAnimationSet compile(PmmDocument d) throws AssetFormatException {
    PmmValidation.validate(d);var models=new ArrayList<AnimationImport>();var accessories=new ArrayList<AnimationImport>();
    for(var model:d.models()) models.add(model(model));
    for(var a:d.accessories()) {
      var builder=new Builder(a.name());
      for(var key:a.keys()) {
        long f=key.key().frame();var s=key.state();builder.add(TRANSLATION,"",f,vec(s.translation()),LINEAR);
        builder.add(ACCESSORY_EULER,"",f,vec(s.euler()),LINEAR);builder.add(SCALE,"",f,new float[]{s.scale(),s.scale(),s.scale()},LINEAR);
        builder.add(OPACITY,"",f,new float[]{s.opacity()},LINEAR);builder.add(DISPLAY,"",f,new float[]{s.visible()?1:0},STEP);
        builder.add(ACCESSORY_PARENT,"",f,parent(s.parent()),STEP);builder.add(SHADOW_MODE,"",f,new float[]{s.shadow()},STEP);
      }
      accessories.add(builder.finish("pmm.accessory",CompatibilityReport.Level.READ,"Curves are sampled; accessory attachment and drawing require the project host"));
    }
    var cameras=new ArrayList<VmdDocument.CameraKey>();
    for(var k:d.camera().keys()) {
      byte[] bezier=new byte[24];for(int c=0;c<6;c++) for(int j=0;j<4;j++) bezier[c*4+j]=k.interpolation().get(c*4+new int[]{0,2,1,3}[j]);
      cameras.add(new VmdDocument.CameraKey(k.key().frame(),k.distance(),k.target(),k.euler(),new ByteData(bezier),k.fov(),k.orthographic()==0));
    }
    var lights=d.light().keys().stream().map(k->new VmdDocument.LightKey(k.key().frame(),k.color(),k.direction())).toList();
    var shadows=d.shadow()==null?List.<VmdDocument.ShadowKey>of():d.shadow().keys().stream().map(k->new VmdDocument.ShadowKey(k.key().frame(),k.mode(),k.distance())).toList();
    var base=new VmdAnimation().compile(new VmdDocument("PMM scene","PMM scene",List.of(),List.of(),cameras,lights,shadows,List.of(),6,d.source()));
    var scene=new Builder(base);
    for(var k:d.camera().keys()) scene.add(CAMERA_PARENT,"",k.key().frame(),parent(k.parent()),STEP);
    if(d.gravity()!=null) for(var k:d.gravity().keys()) {
      long f=k.key().frame();scene.add(GRAVITY_ACCELERATION,"",f,new float[]{k.acceleration()},LINEAR);
      scene.add(GRAVITY_DIRECTION,"",f,vec(k.direction()),LINEAR);scene.add(GRAVITY_NOISE,"",f,new float[]{k.noise()},STEP);
      scene.add(GRAVITY_NOISE_ENABLED,"",f,new float[]{k.noiseEnabled()},STEP);
    }
    return new PmmAnimationSet(models,accessories,scene.finish("pmm.scene-binding",CompatibilityReport.Level.READ,"Camera parents, animated gravity/noise and multi-model physics need a project session"));
  }
  private AnimationImport model(PmmDocument.Model m) throws AssetFormatException {
    int[] bones=PmmValidation.owners(m.boneKeys(),m.boneNames().size(),PmmDocument.BoneKey::key),morphs=PmmValidation.owners(m.morphKeys(),m.morphNames().size(),PmmDocument.MorphKey::key);
    var boneKeys=new ArrayList<VmdDocument.BoneKey>();var morphKeys=new ArrayList<VmdDocument.MorphKey>();var properties=new ArrayList<VmdDocument.PropertyKey>();
    for(int i=0;i<bones.length;i++) {
      var k=m.boneKeys().get(i);byte[] bezier=new byte[64];for(int c=0;c<4;c++) for(int j=0;j<4;j++) bezier[c+j*4]=k.interpolation().get(c*4+j);
      boneKeys.add(new VmdDocument.BoneKey(m.boneNames().get(bones[i]),k.key().frame(),k.translation(),k.rotation(),new ByteData(bezier)));
    }
    for(int i=0;i<morphs.length;i++) { var k=m.morphKeys().get(i);morphKeys.add(new VmdDocument.MorphKey(m.morphNames().get(morphs[i]),k.key().frame(),k.weight())); }
    for(var k:m.modelKeys()) {
      var ik=new ArrayList<VmdDocument.IkSwitch>();for(int i=0;i<m.ikBones().size();i++) ik.add(new VmdDocument.IkSwitch(m.boneNames().get(m.ikBones().get(i)),k.ikEnabled().unsigned(i)!=0));
      properties.add(new VmdDocument.PropertyKey(k.key().frame(),k.visible()!=0,ik));
    }
    var base=new VmdAnimation().compile(new VmdDocument("PMM model",m.name(),boneKeys,morphKeys,List.of(),List.of(),List.of(),properties,6,ByteData.EMPTY));
    var builder=new Builder(base);
    for(int i=0;i<bones.length;i++) {
      var k=m.boneKeys().get(i);
      builder.add(BONE_PHYSICS_ENABLED,m.boneNames().get(bones[i]),k.key().frame(),new float[]{k.physicsDisabled()==0?1:0},STEP);
    }
    for(var k:m.modelKeys()) for(int i=0;i<m.outsideBones().size();i++) builder.add(OUTSIDE_PARENT,m.boneNames().get(m.outsideBones().get(i)),k.key().frame(),parent(k.outsideParents().get(i)),STEP);
    return builder.finish("pmm.model-binding",m.outsideBones().size()>0?CompatibilityReport.Level.READ:CompatibilityReport.Level.EVALUATED,
        "Bone/morph/IK/display/physics switches share MMD evaluation; outside parents need project binding");
  }
  private static float[] vec(Vec3 v) { return new float[]{v.x(),v.y(),v.z()}; }
  private static float[] parent(PmmDocument.Parent p) { return new float[]{p.model(),p.bone()}; }
  private record Target(AnimationClip.Property property,String name,AnimationCurve.Interpolation interpolation) {}
  private static final class Builder {
    final String name;final List<AnimationClip.Track> tracks=new ArrayList<>();final List<CompatibilityReport.Diagnostic> diagnostics=new ArrayList<>();
    final Map<Target,TreeMap<Long,float[]>> channels=new LinkedHashMap<>();
    Builder(String name) { this.name=name; }
    Builder(AnimationImport base) { this(base.clip().name());tracks.addAll(base.clip().tracks());diagnostics.addAll(base.compatibility().diagnostics()); }
    void add(AnimationClip.Property p,String binding,long frame,float[] values,AnimationCurve.Interpolation mode) { channels.computeIfAbsent(new Target(p,binding,mode),ignored->new TreeMap<>()).put(frame,values); }
    AnimationImport finish(String feature,CompatibilityReport.Level level,String detail) {
      for(var entry:channels.entrySet()) {
        var keys=entry.getValue();int width=keys.firstEntry().getValue().length;double[] times=new double[keys.size()];float[] values=new float[keys.size()*width];int i=0;
        for(var key:keys.entrySet()) { times[i]=key.getKey()/30d;System.arraycopy(key.getValue(),0,values,i*width,width);i++; }
        var target=entry.getKey();tracks.add(new AnimationClip.Track(target.property(),-1,target.name(),"",new AnimationCurve(times,new FloatData(values),width,false,target.interpolation(),FloatData.EMPTY,FloatData.EMPTY,FloatData.EMPTY,new IntData())));
      }
      return new AnimationImport(new AnimationClip(name,tracks,30),new CompatibilityReport(List.of(new CompatibilityReport.Feature(feature,level,true,detail)),diagnostics));
    }
  }
}
