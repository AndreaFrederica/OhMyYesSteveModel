package cc.sirrus.ysmlib.scene.mmd;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static cc.sirrus.ysmlib.scene.mmd.PmmDocument.*;

/** Bounded PMM 1/2 reader. Layout reference: nanoem's MIT document component; no editor/runtime dependency. */
public final class PmmReader {
  public PmmDocument read(ByteData source,PmmModelResolver resolver,ReadLimits limits) throws IOException { return new Reader(source,resolver,limits).read(); }
  private static final class Reader {
    final ByteData source;final BinaryInput in;final PmmModelResolver resolver;int version;
    Reader(ByteData source,PmmModelResolver resolver,ReadLimits limits) throws AssetFormatException { this.source=source;this.resolver=Objects.requireNonNull(resolver);in=new BinaryInput(source,limits); }
    String fixed(int size) throws AssetFormatException { return in.fixed(size,BinaryInput.MS932); }
    String string() throws AssetFormatException { return fixed(in.u8()); }
    Key key(int implicit) throws AssetFormatException { return new Key(implicit<0?in.i32():implicit,in.u32(),in.i32(),in.i32()); }
    Parent parent() throws AssetFormatException { return new Parent(in.i32(),in.i32()); }
    void ints(Map<String,Number> state,String... names) throws AssetFormatException { for(String name:names) state.put(name,in.i32()); }
    void bytes(Map<String,Number> state,String... names) throws AssetFormatException { for(String name:names) state.put(name,in.u8()); }
    void floats(Map<String,Number> state,String... names) throws AssetFormatException { for(String name:names) state.put(name,in.f32()); }
    IntData indices() throws AssetFormatException { int n=in.count(4);int[] v=new int[n];for(int i=0;i<n;i++) v[i]=in.i32();return new IntData(v); }
    List<String> strings() throws AssetFormatException { int n=in.count(1);var result=new ArrayList<String>(n);for(int i=0;i<n;i++) result.add(string());return result; }
    BoneKey boneKey(int implicit) throws AssetFormatException {
      var k=key(implicit);var interpolation=in.bytes(16);var t=in.vec3();var r=in.floats(4);int selected=in.u8(),disabled=version==2?in.u8():0;
      return new BoneKey(k,interpolation,t,r,selected,disabled);
    }
    MorphKey morphKey(int implicit) throws AssetFormatException { return new MorphKey(key(implicit),in.f32(),in.u8()); }
    ModelKey modelKey(int implicit,int ik,int outside) throws AssetFormatException {
      var k=key(implicit);int visible=in.u8();var enabled=in.bytes(ik);var parents=new ArrayList<Parent>();
      in.count(outside,8);for(int i=0;i<outside;i++) parents.add(parent());return new ModelKey(k,visible,enabled,parents,in.u8());
    }
    Model model() throws IOException {
      int id=in.u8();String name=version==2?string():fixed(20),english=version==2?string():"",path=fixed(256);
      var state=new LinkedHashMap<String,Number>();List<String> bones,morphs;IntData ik,outside;
      if(version==2) { bytes(state,"fixedTracks");bones=strings();morphs=strings();ik=indices();outside=indices(); }
      else {
        var schema=Objects.requireNonNull(resolver.resolve(path),"PMM model resolver returned null");bones=schema.bones();morphs=schema.morphs();ik=schema.ikBones();outside=new IntData();
        in.count((long)bones.size()+morphs.size()+ik.size(),1);
      }
      bytes(state,"drawOrder","visible");ints(state,"selectedBone","selectedMorphEye","selectedMorphLip","selectedMorphBrow","selectedMorphOther");
      var expansion=in.bytes(in.u8());ints(state,"verticalScroll","lastFrame");
      var boneKeys=new ArrayList<BoneKey>();in.count(bones.size(),version==2?58:57);for(int i=0;i<bones.size();i++) boneKeys.add(boneKey(i));
      int n=in.count(version==2?62:61);for(int i=0;i<n;i++) boneKeys.add(boneKey(-1));
      var morphKeys=new ArrayList<MorphKey>();in.count(morphs.size(),17);for(int i=0;i<morphs.size();i++) morphKeys.add(morphKey(i));
      n=in.count(21);for(int i=0;i<n;i++) morphKeys.add(morphKey(-1));
      var modelKeys=new ArrayList<ModelKey>();modelKeys.add(modelKey(0,ik.size(),outside.size()));n=in.count(18+ik.size());
      for(int i=0;i<n;i++) modelKeys.add(modelKey(-1,ik.size(),outside.size()));
      var boneStates=new ArrayList<BoneState>();in.count(bones.size(),version==2?31:34);
      for(int i=0;i<bones.size();i++) {
        var t=in.vec3();var r=in.floats(4);int unknown=version==1?in.i32():0,dirty=in.u8(),disabled=version==2?in.u8():0,selected=in.u8();
        boneStates.add(new BoneState(t,r,dirty,disabled,selected,unknown));
      }
      var morphStates=in.floats(morphs.size());var ikStates=in.bytes(ik.size());var parents=new ArrayList<ParentState>();in.count(outside.size(),16);
      for(int i=0;i<outside.size();i++) parents.add(new ParentState(in.u32(),in.u32(),parent()));
      if(version==2) { bytes(state,"blendEnabled");floats(state,"edgeWidth");bytes(state,"selfShadowEnabled","transformOrder"); }
      return new Model(id,name,english,path,bones,morphs,ik,outside,boneKeys,morphKeys,modelKeys,boneStates,morphStates,ikStates,parents,expansion,state);
    }
    AccessoryState accessoryState(boolean current) throws AssetFormatException {
      int packed=in.u8();var parent=parent();var t=in.vec3();float scale=current?in.f32():0;var euler=in.vec3();if(!current) scale=in.f32();
      return new AccessoryState(packed,parent,t,euler,scale,in.u8());
    }
    AccessoryKey accessoryKey(int implicit) throws AssetFormatException { return new AccessoryKey(key(implicit),accessoryState(false),in.u8()); }
    Accessory accessory() throws AssetFormatException {
      int id=in.u8();String name=fixed(100),path=fixed(256);int order=in.u8();var keys=new ArrayList<AccessoryKey>();keys.add(accessoryKey(0));
      int n=in.count(55);for(int i=0;i<n;i++) keys.add(accessoryKey(-1));
      return new Accessory(id,name,path,order,keys,accessoryState(true),version==2?in.u8():0);
    }
    CameraKey cameraKey(int implicit) throws AssetFormatException {
      var k=key(implicit);float distance=in.f32();var target=in.vec3();var euler=in.vec3();var parent=version==2?parent():Parent.NONE;
      return new CameraKey(k,distance,target,euler,parent,in.bytes(24),in.u8(),in.i32(),in.u8());
    }
    Camera camera() throws AssetFormatException {
      var keys=new ArrayList<CameraKey>();keys.add(cameraKey(0));int n=in.count(version==2?82:74);for(int i=0;i<n;i++) keys.add(cameraKey(-1));
      return new Camera(keys,in.vec3(),in.vec3(),in.vec3(),in.u8());
    }
    LightKey lightKey(int implicit) throws AssetFormatException { return new LightKey(key(implicit),in.vec3(),in.vec3(),in.u8()); }
    Light light() throws AssetFormatException {
      var keys=new ArrayList<LightKey>();keys.add(lightKey(0));int n=in.count(41);for(int i=0;i<n;i++) keys.add(lightKey(-1));return new Light(keys,in.vec3(),in.vec3());
    }
    GravityKey gravityKey(int implicit) throws AssetFormatException { return new GravityKey(key(implicit),in.u8(),in.i32(),in.f32(),in.vec3(),in.u8()); }
    Gravity gravity() throws AssetFormatException {
      float acceleration=in.f32();int noise=in.i32();var direction=in.vec3();int enabled=in.u8();var keys=new ArrayList<GravityKey>();keys.add(gravityKey(0));
      int n=in.count(38);for(int i=0;i<n;i++) keys.add(gravityKey(-1));return new Gravity(keys,acceleration,noise,direction,enabled);
    }
    ShadowKey shadowKey(int implicit) throws AssetFormatException { return new ShadowKey(key(implicit),in.u8(),in.f32(),in.u8()); }
    Shadow shadow() throws AssetFormatException {
      int enabled=in.u8();float distance=in.f32();var keys=new ArrayList<ShadowKey>();keys.add(shadowKey(0));int n=in.count(22);
      for(int i=0;i<n;i++) keys.add(shadowKey(-1));return new Shadow(keys,enabled,distance);
    }
    PmmDocument read() throws IOException {
      String signature=in.fixed(30,StandardCharsets.US_ASCII);
      version=switch(signature) { case "Polygon Movie maker 0001"->1;case "Polygon Movie maker 0002"->2;default->throw in.error("Unsupported PMM signature"); };
      var settings=new LinkedHashMap<String,Number>();ints(settings,"outputWidth","outputHeight","timelineWidth");floats(settings,"cameraFov");
      bytes(settings,"editingCLA","expandCamera","expandLight","expandAccessory","expandBone","expandMorph");if(version==2) bytes(settings,"expandShadow");
      bytes(settings,"selectedModel");int n=in.count(in.u8(),1);var modelLabels=new ArrayList<String>();if(version==1) for(int i=0;i<n;i++) modelLabels.add(fixed(20));
      var models=new ArrayList<Model>();for(int i=0;i<n;i++) models.add(model());
      var camera=camera();var light=light();bytes(settings,"selectedAccessory");ints(settings,"accessoryScroll");
      n=in.count(in.u8(),1);var accessoryLabels=new ArrayList<String>();for(int i=0;i<n;i++) accessoryLabels.add(fixed(100));
      var accessories=new ArrayList<Accessory>();for(int i=0;i<n;i++) accessories.add(accessory());
      ints(settings,"currentFrame","horizontalScroll","scrollThumb","editingMode");bytes(settings,"cameraLookMode","loop","beginEnabled","endEnabled");ints(settings,"beginFrame","endFrame");
      int audioEnabled=in.u8();var audio=new Media(fixed(256),audioEnabled,0,0,1);
      int x=in.i32(),y=in.i32();float scale=in.f32();String path=fixed(256);var video=new Media(path,in.i32(),x,y,scale);
      x=in.i32();y=in.i32();scale=in.f32();path=fixed(256);var image=new Media(path,in.u8(),x,y,scale);
      bytes(settings,"information","gridAxis","groundShadow");floats(settings,"preferredFps");ints(settings,"screenCaptureMode","accessoryAfterModels");floats(settings,"groundShadowBrightness");
      Gravity gravity=null;Shadow shadow=null;ByteData matrix=ByteData.EMPTY;IntData selections=new IntData();
      if(version==2) {
        bytes(settings,"translucentGroundShadow","physicsMode");gravity=gravity();shadow=shadow();ints(settings,"edgeRed","edgeGreen","edgeBlue");bytes(settings,"blackBackground");ints(settings,"cameraLookModel","cameraLookBone");
        matrix=in.bytes(64);bytes(settings,"followLookAt","unknownBoolean","physicsGround");ints(settings,"currentFrameText");
        if(in.remaining()>0) {
          int hasSelections=in.u8();settings.put("hasModelSelections",hasSelections);
          if(hasSelections!=0) { int[] entries=new int[models.size()*2];in.count(models.size(),5);for(int i=0;i<models.size();i++) { entries[i*2]=in.u8();entries[i*2+1]=in.i32(); }selections=new IntData(entries); }
        }
      }
      // Older writers and editor extensions use tails outside the published layout. Preserve them explicitly.
      var tail=in.bytes(in.remaining());var document=new PmmDocument(version,models,accessories,camera,light,gravity,shadow,audio,video,image,settings,modelLabels,accessoryLabels,matrix,selections,tail,source);
      PmmValidation.validate(document);return document;
    }
  }
}
