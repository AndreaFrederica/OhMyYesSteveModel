package cc.sirrus.ysmlib.scene;

import java.util.*;

/** Portable per-model authoring settings. Preview transport/physics switches are intentionally absent. */
public record SceneModelProfile(int schemaVersion,String profileId,Placement placement,
    Map<String,BoneBinding> bones,Map<String,Action> actions,Presentation presentation,Metadata metadata,Retarget retarget,
    List<Transition> transitions,HeldItems heldItems) {
  public SceneModelProfile(int schemaVersion,String profileId,Placement placement,Map<String,BoneBinding> bones,
      Map<String,Action> actions,Presentation presentation,Metadata metadata,Retarget retarget,List<Transition> transitions) {
    this(schemaVersion,profileId,placement,bones,actions,presentation,metadata,retarget,transitions,HeldItems.defaults());
  }
  public static final String PACKAGE_PATH="_omysm/profile.json";
  public static final String SUFFIX=".omysm.json";
  public static final List<String> ROLES=List.of("root","hips","spine","chest","neck","head",
      "leftUpperArm","leftLowerArm","leftHand","rightUpperArm","rightLowerArm","rightHand",
      "leftUpperLeg","leftLowerLeg","leftFoot","leftToes","rightUpperLeg","rightLowerLeg","rightFoot","rightToes",
      "leftLegIk","rightLegIk","leftToeIk","rightToeIk",
      "leftThumb","leftIndex","leftMiddle","leftRing","leftLittle","rightThumb","rightIndex","rightMiddle","rightRing","rightLittle");
  public SceneModelProfile {
    if(schemaVersion!=1) throw new IllegalArgumentException("Unsupported model profile schema: "+schemaVersion);
    UUID.fromString(Objects.requireNonNull(profileId));Objects.requireNonNull(placement);Objects.requireNonNull(presentation);Objects.requireNonNull(metadata);Objects.requireNonNull(retarget);Objects.requireNonNull(heldItems);
    bones=Map.copyOf(bones);actions=Map.copyOf(actions);transitions=List.copyOf(transitions);
    var edges=new HashSet<List<String>>();
    for(var edge:transitions)if(!edges.add(List.of(edge.from(),edge.to())))throw new IllegalArgumentException("Duplicate transition: "+edge.from()+" -> "+edge.to());
    var occupied=new HashSet<Integer>();
    for(var e:bones.entrySet()) {
      checkBindingKey(e.getKey());
      if(e.getValue().index()>=0 && !occupied.add(e.getValue().index())) throw new IllegalArgumentException("Bone assigned to multiple roles: "+e.getValue().name());
    }
    for(String name:actions.keySet()) if(name.isBlank() || name.length()>128 || name.startsWith("scene/") || name.startsWith("#"))
      throw new IllegalArgumentException("Invalid action name: "+name);
  }
  public static boolean customBinding(String key){return key.startsWith("bone:");}
  public static void checkBindingKey(String key){
    if(!ROLES.contains(key)&&!(customBinding(key)&&!key.substring(5).isBlank()&&key.length()<=128))
      throw new IllegalArgumentException("Expected humanoid role or bone:<binding name>: "+key);
  }
  /** A concrete one-shot clip between two logical host states; interruption follows the newly requested state. */
  public record Transition(String from,String to,Action animation) {
    public Transition {
      Objects.requireNonNull(from);Objects.requireNonNull(to);Objects.requireNonNull(animation);
      if(from.isBlank()||to.isBlank()||from.length()>128||to.length()>128||from.equals(to))throw new IllegalArgumentException("Invalid transition states");
      if(animation.loop()||animation.path().equals("REST")||animation.path().startsWith("@ysm/"))
        throw new IllegalArgumentException("Transition requires a non-looping source clip");
    }
  }
  public enum SizeMode { SCALE, HEIGHT }
  public record Placement(double metersPerUnit,SizeMode sizeMode,double scale,double height,
      double referenceHeight,double footY,double x,double y,double z,double yaw) {
    public Placement {
      positive(metersPerUnit,"metersPerUnit");positive(scale,"scale");positive(height,"height");positive(referenceHeight,"referenceHeight");
      Objects.requireNonNull(sizeMode);for(double n:new double[]{footY,x,y,z,yaw}) finite(n);
      double actual=sizeMode==SizeMode.HEIGHT?height/referenceHeight:metersPerUnit*scale;positive(actual,"effectiveScale");
    }
    public double effectiveScale() { return sizeMode==SizeMode.HEIGHT?height/referenceHeight:metersPerUnit*scale; }
    public double actualHeight() { return referenceHeight*effectiveScale(); }
  }
  /** Index is checked against name and parent name; -1 explicitly leaves a role unbound. Degrees use source local axes. */
  public record BoneBinding(int index,String name,String parent,double pitch,double yaw,double roll,double weight,double restPitch,double restYaw,double restRoll) {
    public BoneBinding(int index,String name,String parent,double pitch,double yaw,double roll,double weight) {
      this(index,name,parent,pitch,yaw,roll,weight,0,0,0);
    }
    public BoneBinding withReference(double x,double y,double z) {return new BoneBinding(index,name,parent,pitch,yaw,roll,weight,x,y,z);}
    public BoneBinding withAxes(double x,double y,double z,double w) {return new BoneBinding(index,name,parent,x,y,z,w,restPitch,restYaw,restRoll);}
    public Rotation referenceRotation() {return euler(restPitch,restYaw,restRoll);}
    public BoneBinding {
      if(index< -1) throw new IllegalArgumentException("Invalid bone index");Objects.requireNonNull(name);Objects.requireNonNull(parent);
      finite(pitch);finite(yaw);finite(roll);finite(weight);finite(restPitch);finite(restYaw);finite(restRoll);if(weight<0 || weight>1) throw new IllegalArgumentException("Bone weight outside [0,1]");
    }
  }
  /** Item offsets are metres in the converted bone axes; scale is independent of source file units. */
  public record HandSocket(boolean enabled,String binding,double x,double y,double z,double pitch,double yaw,double roll,double scale) {
    public HandSocket {checkBindingKey(binding);for(double n:new double[]{x,y,z,pitch,yaw,roll})finite(n);positive(scale,"item scale");}
    public static HandSocket defaults(String role){return new HandSocket(true,role,0,-.0625,-.1,-90,0,0,1);}
  }
  public enum FirstPersonItems { VANILLA, MODEL, HIDDEN }
  public record HeldItems(boolean enabled,FirstPersonItems firstPerson,HandSocket left,HandSocket right) {
    public HeldItems {Objects.requireNonNull(firstPerson);Objects.requireNonNull(left);Objects.requireNonNull(right);}
    public static HeldItems defaults(){return new HeldItems(true,FirstPersonItems.VANILLA,HandSocket.defaults("leftHand"),HandSocket.defaults("rightHand"));}
  }
  public static Rotation euler(double pitch,double yaw,double roll){
    return Rotation.axisAngle(new Vec3(0,0,1),Math.toRadians(roll)).multiply(Rotation.axisAngle(new Vec3(0,1,0),Math.toRadians(yaw))).multiply(Rotation.axisAngle(new Vec3(1,0,0),Math.toRadians(pitch)));
  }
  /** path is a stable package path, @ysm/generated/state, or the explicit REST token. */
  public record Action(String path,int clip,boolean loop) {
    public Action {
      Objects.requireNonNull(path);if(!path.equals("REST") && !path.startsWith("@ysm/generated/")) ScenePackage.checkedPath(path);
      if(clip< -1) throw new IllegalArgumentException("Invalid clip");
      if((path.equals("REST") || path.startsWith("@ysm/generated/")) && clip!=-1)throw new IllegalArgumentException("Rest/generated actions have no clip index");
    }
    public ScenePackagePlayback.Selection resolve(ScenePackage source) {
      if(path.equals("REST")) return ScenePackagePlayback.Selection.REST;
      if(path.startsWith("@ysm/generated/")) return new ScenePackagePlayback.Selection(path,clip);
      if(path.equals(source.model().path())) return new ScenePackagePlayback.Selection(source.model().id(),clip);
      return source.animations().stream().filter(a->a.path().equals(path)).findFirst()
          .map(a->new ScenePackagePlayback.Selection(a.id(),clip)).orElseThrow(()->new IllegalArgumentException("Missing animation: "+path));
    }
  }
  public record Presentation(boolean outlines,double outlineScale) {
    public Presentation { finite(outlineScale);if(outlineScale<0 || outlineScale>10) throw new IllegalArgumentException("Outline scale outside [0,10]"); }
  }
  /** Mirrors YSM metadata; avatar is a package-relative image path, never an absolute host path. */
  public record Metadata(String name,String tips,License license,List<Author> authors,List<Pair> links) {
    public Metadata {text(name);text(tips);Objects.requireNonNull(license);authors=List.copyOf(authors);links=List.copyOf(links);}
    public static Metadata empty(){return new Metadata("","",new License("All Rights Reserved",""),List.of(),List.of());}
  }
  public record License(String type,String desc){public License{text(type);text(desc);}}
  /** Source bone names are separate from target role bindings and from action selection. */
  public record Retarget(Map<String,String> sourceBones,double translationScale,String sourceModel) {
    public Retarget(Map<String,String> sourceBones,double translationScale){this(sourceBones,translationScale,"@ysm/default");}
    public Retarget {sourceBones=Map.copyOf(sourceBones);positive(translationScale,"translationScale");
      if(!Objects.requireNonNull(sourceModel).equals("@ysm/default")&&!sourceModel.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("sourceModel must be @ysm/default or a lowercase model content hash");
      for(var e:sourceBones.entrySet()){checkBindingKey(e.getKey());if(e.getValue().isBlank())throw new IllegalArgumentException("Empty YSM source bone: "+e.getKey());text(e.getValue());}}
    public Retarget withBindings(Map<String,String> value,double scale){return new Retarget(value,scale,sourceModel);}
    public static Retarget empty(){return new Retarget(Map.of(),1);}
  }
  public record Pair(String key,String value){public Pair{text(key);text(value);}}
  public record Author(String name,String role,List<Pair> contacts,String comment,String avatar){
    public Author{text(name);text(role);text(comment);text(avatar);contacts=List.copyOf(contacts);if(!avatar.isEmpty())ScenePackage.checkedPath(avatar);}
  }
  private static void text(String value){Objects.requireNonNull(value);if(value.length()>65536)throw new IllegalArgumentException("Metadata text exceeds 65536 characters");}
  public static SceneModelProfile defaults(double meters,double referenceHeight,double footY) {
    return new SceneModelProfile(1,UUID.randomUUID().toString(),new Placement(meters,SizeMode.SCALE,1,1.8,referenceHeight,footY,0,0,0,0),Map.of(),Map.of(),new Presentation(true,1),Metadata.empty(),Retarget.empty(),List.of());
  }
  public SceneModelProfile withPlacement(Placement value) { return new SceneModelProfile(schemaVersion,profileId,value,bones,actions,presentation,metadata,retarget,transitions,heldItems); }
  public SceneModelProfile withBones(Map<String,BoneBinding> value) { return new SceneModelProfile(schemaVersion,profileId,placement,value,actions,presentation,metadata,retarget,transitions,heldItems); }
  public SceneModelProfile withActions(Map<String,Action> value) { return new SceneModelProfile(schemaVersion,profileId,placement,bones,value,presentation,metadata,retarget,transitions,heldItems); }
  public SceneModelProfile withPresentation(Presentation value) { return new SceneModelProfile(schemaVersion,profileId,placement,bones,actions,value,metadata,retarget,transitions,heldItems); }
  public SceneModelProfile withMetadata(Metadata value) { return new SceneModelProfile(schemaVersion,profileId,placement,bones,actions,presentation,value,retarget,transitions,heldItems); }
  public SceneModelProfile withRetarget(Retarget value) { return new SceneModelProfile(schemaVersion,profileId,placement,bones,actions,presentation,metadata,value,transitions,heldItems); }
  public SceneModelProfile withTransitions(List<Transition> value) { return new SceneModelProfile(schemaVersion,profileId,placement,bones,actions,presentation,metadata,retarget,value,heldItems); }
  public SceneModelProfile withHeldItems(HeldItems value) { return new SceneModelProfile(schemaVersion,profileId,placement,bones,actions,presentation,metadata,retarget,transitions,value); }
  public void validateAnimations(ScenePackage source,List<SceneAnimation> clips) {
    for(var a:actions.values()) {
      var selection=a.resolve(source);
      if(!selection.equals(ScenePackagePlayback.Selection.REST)&&clips.stream().noneMatch(c->c.selection().equals(selection)))
        throw new IllegalArgumentException("Unavailable animation: "+a.path()+" clip="+a.clip());
    }
    for(var edge:transitions) {
      var selection=edge.animation().resolve(source);
      var clip=clips.stream().filter(c->c.selection().equals(selection)).findFirst()
        .orElseThrow(()->new IllegalArgumentException("Unavailable transition: "+edge.from()+" -> "+edge.to()));
      if(clip.range().end()<=clip.range().start())throw new IllegalArgumentException("Transition clip must have positive duration: "+edge.animation().path());
    }
  }
  private static void finite(double n) { if(!Double.isFinite(n) || Math.abs(n)>1e9) throw new IllegalArgumentException("Non-finite or excessive profile value"); }
  private static void positive(double n,String name) { finite(n);if(n<1e-9) throw new IllegalArgumentException(name+" must be positive"); }
}
