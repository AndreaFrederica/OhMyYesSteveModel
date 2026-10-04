package cc.sirrus.ysmlib.scene.mmd;

import cc.sirrus.ysmlib.scene.*;
import java.util.*;

/** Independent RS head/eye/blink policy. The owner advances once per logical frame, then merges
 * the returned overrides after animation/VPD and expressions, before morph/IK/physics evaluation.
 * Render queries never advance its clock. Tracking owners can suppress ordinary head/eye controls.
 */
public final class MmdProceduralControls {
  private static final Vec3 X=new Vec3(1,0,0),Y=new Vec3(0,1,0),Z=new Vec3(0,0,1);
  public record Overrides(Map<Integer,Rotation> bones,Map<Integer,Float> morphs) {
    public Overrides { bones=Map.copyOf(bones);morphs=Map.copyOf(morphs); }
  }
  public interface Snapshot {}
  private record Saved(Object owner,long random,Vec3 head,float pitch,float yaw,float maxEye,
      boolean eyes,boolean blink,boolean tracking,double interval,double duration,double timer,
      double phase,boolean blinking,Overrides current) implements Snapshot {}
  private final Object owner=new Object();
  private final int headBone,leftEye,rightEye,blinkMorph;
  private final List<CompatibilityReport.Diagnostic> diagnostics;
  private Vec3 head=Vec3.ZERO;
  private float pitch,yaw,maxEye=.35f;
  private boolean eyes,blink,tracking,blinking;
  private double interval=4,duration=.15,timer,phase;
  private long random;
  private Overrides current=new Overrides(Map.of(),Map.of());

  public MmdProceduralControls(PmxDocument model,long seed) {
    Objects.requireNonNull(model);random=seed;
    var bones=MmdRig.names(model.bones().stream().map(PmxDocument.Bone::names).toList());
    var morphs=MmdRig.names(model.morphs().stream().map(PmxDocument.Morph::names).toList());
    var warnings=new ArrayList<CompatibilityReport.Diagnostic>();
    headBone=bind(bones,warnings,"head","頭","head","Head","あたま");
    leftEye=bind(bones,warnings,"left eye","左目","eye_L","Eye_L","LeftEye","left_eye","Left_Eye","eyeL","EyeL","左眼","L_Eye","eye.L","Eye.L");
    rightEye=bind(bones,warnings,"right eye","右目","eye_R","Eye_R","RightEye","right_eye","Right_Eye","eyeR","EyeR","右眼","R_Eye","eye.R","Eye.R");
    blinkMorph=bind(morphs,warnings,"blink","まばたき","眨眼","blink","Blink","まばたき両目","ウィンク","wink");
    diagnostics=List.copyOf(warnings);
  }
  public MmdProceduralControls(PmdDocument model,long seed){this(new PmdRuntimeProfile(model).asset,seed);}
  private static int bind(Map<String,List<Integer>> names,List<CompatibilityReport.Diagnostic> warnings,String role,String... aliases) {
    for(String alias:aliases) {
      var found=names.get(alias);if(found==null || found.isEmpty())continue;
      if(found.size()>1)warnings.add(new CompatibilityReport.Diagnostic(CompatibilityReport.Severity.WARNING,role,"Ambiguous procedural binding "+alias+"; using first source index"));
      return found.get(0);
    }
    return -1;
  }
  public List<CompatibilityReport.Diagnostic> diagnostics(){return diagnostics;}
  public void headAngles(Vec3 xyz){head=Objects.requireNonNull(xyz);}
  public void eyeAngles(float x,float y) {
    finite(x);finite(y);pitch=Math.max(-maxEye,Math.min(maxEye,x));yaw=Math.max(-maxEye,Math.min(maxEye,y));
  }
  public void eyeMaxAngle(float radians){finite(radians);maxEye=Math.max(.1f,Math.min(1,radians));eyeAngles(pitch,yaw);}
  public void eyeTracking(boolean enabled){eyes=enabled;}
  public void trackingOwnsBones(boolean enabled){tracking=enabled;}
  public void blinkParameters(double secondsBetween,double secondsDuration) {
    finite(secondsBetween);finite(secondsDuration);
    if(secondsBetween>Double.MAX_VALUE/1.3)throw new IllegalArgumentException("Blink interval overflow");
    interval=Math.max(.5,secondsBetween);duration=Math.max(.05,Math.min(.5,secondsDuration));
  }
  public void autoBlink(boolean enabled) {
    if(enabled==blink)return;
    blink=enabled;blinking=false;phase=0;timer=enabled?nextRandom()*interval:0;
    current=new Overrides(current.bones(),Map.of());
  }
  /** Seeded random values make snapshots/replay deterministic without an unbounded event journal. */
  private double nextRandom() {
    long value=random+=0x9e3779b97f4a7c15L;
    value=(value^(value>>>30))*0xbf58476d1ce4e5b9L;value=(value^(value>>>27))*0x94d049bb133111ebL;
    return ((value^(value>>>31))>>>11)*0x1.0p-53;
  }
  private static void finite(double value){if(!Double.isFinite(value))throw new IllegalArgumentException("Non-finite procedural input");}
  private static Rotation xyz(Vec3 angles){return Rotation.axisAngle(X,angles.x()).multiply(Rotation.axisAngle(Y,angles.y())).multiply(Rotation.axisAngle(Z,angles.z()));}
  public Overrides advance(double elapsedSeconds) {
    finite(elapsedSeconds);if(elapsedSeconds<0)throw new IllegalArgumentException("Negative procedural delta");
    var bones=new LinkedHashMap<Integer,Rotation>();
    if(!tracking) {
      if(headBone>=0)bones.put(headBone,xyz(head));
      if(eyes) {
        var rotation=xyz(new Vec3(pitch,yaw,0));
        // Different aliases may resolve to one bone; apply the eye rotation once.
        if(leftEye>=0)bones.merge(leftEye,rotation,Rotation::multiply);
        if(rightEye>=0 && rightEye!=leftEye)bones.merge(rightEye,rotation,Rotation::multiply);
      }
    }
    Map<Integer,Float> morphs=Map.of();
    if(blink && blinkMorph>=0) {
      if(blinking) {
        phase+=elapsedSeconds/duration;
        if(phase>=1) {
          morphs=Map.of(blinkMorph,0f);blinking=false;phase=0;timer=interval*(.7+nextRandom()*.6);
        } else morphs=Map.of(blinkMorph,(float)Math.sin(phase*Math.PI));
      } else {
        timer-=elapsedSeconds;
        if(timer<=0){blinking=true;phase=0;}
      }
    }
    return current=new Overrides(bones,morphs);
  }
  public Overrides current(){return current;}
  public Snapshot snapshot(){return new Saved(owner,random,head,pitch,yaw,maxEye,eyes,blink,tracking,interval,duration,timer,phase,blinking,current);}
  public void restore(Snapshot snapshot) {
    if(!(snapshot instanceof Saved s) || s.owner()!=owner)throw new IllegalArgumentException("Foreign procedural snapshot");
    random=s.random();head=s.head();pitch=s.pitch();yaw=s.yaw();maxEye=s.maxEye();eyes=s.eyes();blink=s.blink();tracking=s.tracking();
    interval=s.interval();duration=s.duration();timer=s.timer();phase=s.phase();blinking=s.blinking();current=s.current();
  }
}
