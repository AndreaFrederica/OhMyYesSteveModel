package cc.sirrus.ysmlib.scene.fbx;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.io.*;
import org.junit.jupiter.api.Test;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class FbxDocumentTest {
  static ByteData sample(String name) throws Exception {
    try(var input=FbxDocumentTest.class.getResourceAsStream("/ufbx/"+name)) { return new ByteData(Objects.requireNonNull(input,name).readAllBytes()); }
  }
  static FbxDocument read(String name) throws Exception { return new FbxReader().read(sample(name),ReadLimits.DEFAULT); }
  @Test void typedSourceOwnsAllShapeAndMaterialDataAfterReactorCloses() throws Exception {
    var source=read("maya_blend_inbetween_7500_ascii.fbx");
    assertEquals(5,source.blendShapes().size());assertEquals(2,source.details().blendChannels().size());
    assertEquals(3,source.details().blendChannels().get(1).keys().size());
    var material=source.details().materials().get(0);assertEquals("lambert",material.shadingModel());
    assertEquals(.5,material.fbx().get("diffuse_color").value().get(0),1e-12);
    assertEquals(20,material.fbx().size());assertEquals(56,material.pbr().size());
    assertThrows(UnsupportedOperationException.class,()->material.fbx().clear());
    assertThrows(UnsupportedOperationException.class,()->source.blendShapes().clear());
    assertEquals(sample("maya_blend_inbetween_7500_ascii.fbx"),source.source());
    var uv=read("maya_uv_set_tangent_w_7700_ascii.fbx").meshes().get(0);
    assertTrue(uv.uvSets().stream().anyMatch(v->v.tangentW().size()>0));
    for(var v:uv.uvSets()) if(v.tangentW().size()>0) assertEquals(uv.cornerCount(),v.tangentW().size());
  }
  @Test void lightAnimationMatchesUpstreamDccReferenceAndExposesShadowParameters() throws Exception {
    var source=read("maya_anim_light_7500_ascii.fbx");
    double[][] refs={{0,3.072,.148,.095,.440},{12,1.638,.102,.136,.335},{24,1.948,.020,.208,.149},
        {32,3.676,.010,.220,.113},{40,4.801,.118,.195,.115},{48,3.690,.288,.155,.117},{56,1.565,.421,.124,.119},{60,1.145,.442,.119,.119}};
    // Upstream test/test_animation.h: maya_anim_light reference values, rounded to three decimals.
    try(var player=new FbxPlayer(source,ReadLimits.DEFAULT)) {
      for(var ref:refs) { var light=player.evaluate(-1,ref[0]/24).details().lights().get(0);
        assertEquals(ref[1],light.intensity(),.001);for(int i=0;i<3;i++) assertEquals(ref[2+i],light.color().get(i),.001);
        assertEquals(source.details().lights().get(0).castShadows(),light.castShadows());
      }
      var a=player.evaluate(-1,.5);player.evaluate(-1,2);assertEquals(a,player.evaluate(-1,.5));
    }
    var cameras=read("maya_camera_light_axes_y_up_7700_ascii.fbx").details().cameras();
    assertFalse(cameras.isEmpty());assertTrue(cameras.get(0).parameters().get("far_plane").get(0)>0);
  }
  @Test void constraintsRetainTargetsButCannotMasqueradeAsEvaluated() throws Exception {
    var source=read("maya_constraint_zoo_7500_ascii.fbx");assertTrue(source.constraintCount()>=3);
    assertTrue(source.details().constraints().stream().anyMatch(c->!c.targets().isEmpty()));
    assertThrows(UnsupportedOperationException.class,()->new FbxPlayer(source,ReadLimits.DEFAULT));
  }
  @Test void authoredNormalsSurviveSkinningAndProjectionDoesNotSkinTwice() throws Exception {
    var original=sample("maya_transformed_skin_7700_ascii.fbx");String text=new String(original.copy(),StandardCharsets.UTF_8);
    var pattern=java.util.regex.Pattern.compile("(Normals: \\*(\\d+) \\{\\s*a:)\\s*[^}]+}");var matcher=pattern.matcher(text);assertTrue(matcher.find());
    int count=Integer.parseInt(matcher.group(2));String[] values=new String[count];for(int i=0;i<count;i++) values[i]=i%3==0?"1":"0";
    String changed=matcher.replaceFirst(java.util.regex.Matcher.quoteReplacement(matcher.group(1)+" "+String.join(",",values)+"}"));
    var reader=new FbxReader();var source=reader.read(original,ReadLimits.DEFAULT);var authored=reader.read(new ByteData(changed.getBytes(StandardCharsets.UTF_8)),ReadLimits.DEFAULT);
    try(var a=new FbxPlayer(source,ReadLimits.DEFAULT);var b=new FbxPlayer(authored,ReadLimits.DEFAULT)) {
      var frame=a.evaluate(-1,.2);var custom=b.evaluate(-1,.2);
      assertEquals(frame.draws().get(0).positions(),custom.draws().get(0).positions());
      assertNotEquals(frame.draws().get(0).normals(),custom.draws().get(0).normals());
      var geometry=new FbxGeometry().compile(authored,custom);assertEquals(custom.draws().size(),geometry.draws().size());
      var primitive=geometry.draws().get(0).geometry().primitives().get(0);assertNull(primitive.skinning());assertEquals(Matrix4.IDENTITY,geometry.draws().get(0).world());
      for(int i=0;i<primitive.attributes().get("POSITION").values().size();i++) assertEquals((float)custom.draws().get(0).positions().get(i),primitive.attributes().get("POSITION").values().get(i));
      assertEquals(authored.nodes().get(custom.draws().get(0).node()).materials().get(0),primitive.material());
    }
  }
  @Test void snapshotBudgetCountsPropertiesAndAttributesAndStringLimitIsEnforced() throws Exception {
    var source=sample("maya_cube_7500_binary.fbx");
    assertThrows(AssetFormatException.class,()->new FbxReader().read(source,new ReadLimits(1024*1024,100,1024)));
    assertThrows(AssetFormatException.class,()->new FbxReader().read(source,new ReadLimits(1024*1024,100000,2)));
  }
  @Test void textureAccessRequiresExplicitCapabilityAndHonorsAggregateBudget() throws Exception {
    var source=read("blender_293_textures_7400_binary.fbx");assertTrue(source.textures().stream().anyMatch(t->t.type()==0));
    var resolver=new FbxDependencies();assertThrows(AssetFormatException.class,()->resolver.resolve(source,AssetResolver.NONE,ReadLimits.DEFAULT));
    var reads=new ArrayList<String>();var bytes=new ByteData(new byte[]{1,2,3});
    var assets=resolver.resolve(source,reference->{ reads.add(reference);return bytes; },ReadLimits.DEFAULT);
    assertFalse(reads.isEmpty());assertEquals(reads.size(),new HashSet<>(reads).size());
    assertTrue(assets.images().values().stream().allMatch(i->!i.embedded()&&i.bytes().equals(bytes)));
    assertThrows(AssetFormatException.class,()->resolver.resolve(source,ref->bytes,new ReadLimits(source.source().size()+1,1000,1000)));
    var embedded=read("blender_293_embedded_textures_7400_binary.fbx");
    var embeddedAssets=resolver.resolve(embedded,ref->{ fail("Embedded texture attempted external access: "+ref);return bytes; },ReadLimits.DEFAULT);
    assertFalse(embeddedAssets.images().isEmpty());assertTrue(embeddedAssets.images().values().stream().allMatch(i->i.embedded()&&i.bytes().size()>0));
  }
  @Test void originalMayaMorphReferenceMeshesMatchAtAllEightInBetweenTimes() throws Exception {
    var source=read("maya_blend_inbetween_7500_ascii.fbx");
    try(var player=new FbxPlayer(source,ReadLimits.DEFAULT)) {
      for(int frame:new int[]{1,30,60,65,71,80,89,120}) compareObj(source,player.evaluate(-1,frame/24.0),"maya_blend_inbetween_"+frame+".obj");
    }
  }
  @Test void originalMayaLinearDqAndBlendedDqReferencePositionsMatch() throws Exception {
    for(String prefix:List.of("maya_dual_quaternion","maya_dual_quaternion_scale")) {
      var source=read(prefix+"_7500_ascii.fbx");assertEquals(2,source.skins().get(0).method());
      try(var player=new FbxPlayer(source,ReadLimits.DEFAULT)) { compareObj(source,player.evaluate(-1,0),prefix+".obj"); }
    }
    var blended=read("maya_dq_weights_7500_ascii.fbx");assertEquals(3,blended.skins().get(0).method());
    try(var player=new FbxPlayer(blended,ReadLimits.DEFAULT)) {
      for(int f:new int[]{10,18}) compareObj(blended,player.evaluate(-1,f/24.0),"maya_dq_weights_"+f+".obj");
    }
    for(String prefix:List.of("maya_transformed_skin","maya_instanced_skin")) {
      var source=read(prefix+"_7700_ascii.fbx");
      try(var player=new FbxPlayer(source,ReadLimits.DEFAULT,new FbxEvaluation.Settings(FbxEvaluation.SkinSpace.FOLLOW_MESH_NODE))) {
        for(int f:new int[]{0,4,8}) compareObj(source,player.evaluate(-1,f/24.0),prefix+"_"+f+".obj");
      }
    }
  }
  @Test void combinedMorphAndFiveBoneWeightsMatchOriginalBlenderDepsgraph() throws Exception {
    try(var input=FbxDocumentTest.class.getResourceAsStream("/blender-oracle/weighted-morph.fbx");
        var expectedInput=FbxDocumentTest.class.getResourceAsStream("/blender-oracle/weighted-morph.json")) {
      var source=new FbxReader().read(new ByteData(Objects.requireNonNull(input).readAllBytes()),ReadLimits.DEFAULT);
      assertEquals(1,source.meshes().get(0).skins().size());assertEquals(1,source.meshes().get(0).blendDeformers().size());
      assertTrue(source.skins().get(0).vertices().stream().allMatch(v->v.count()==5));
      var refs=JsonParser.parseString(new String(Objects.requireNonNull(expectedInput).readAllBytes(),StandardCharsets.UTF_8)).getAsJsonArray();
      try(var player=new FbxPlayer(source,ReadLimits.DEFAULT)) {
        for(var value:refs) { var ref=value.getAsJsonObject();double time=ref.get("seconds").getAsDouble();
          var actual=player.evaluate(0,time).draws().get(0).positions();var expected=ref.getAsJsonArray("positions");
          assertEquals(expected.size(),actual.size());
          for(int i=0;i<actual.size();i++) assertEquals(expected.get(i).getAsDouble(),actual.get(i),2e-6,"Blender frame "+ref.get("frame")+" component "+i);
        }
      }
    }
  }
  @Test void nativeReaderAndEvaluatorMatchWasmForARealAnimatedMesh() throws Exception {
    boolean nativeAvailable=FbxNativeReactor.available();
    if(Boolean.getBoolean("ysm.runtime.fbxNativeRequired")) assertTrue(nativeAvailable,FbxNativeReactor.unavailableReason());
    else org.junit.jupiter.api.Assumptions.assumeTrue(nativeAvailable, "native FBX library not installed: "+FbxNativeReactor.unavailableReason());
    var source=new FbxReader().read(sample("maya_transformed_skin_7700_ascii.fbx"),ReadLimits.DEFAULT);
    try(var javaPlayer=new FbxPlayer(source,ReadLimits.DEFAULT);
        var nativePlayer=FbxNativePlayer.tryCreate(source,ReadLimits.DEFAULT,FbxEvaluation.Settings.DEFAULT)) {
      assertNotNull(nativePlayer);
      try(nativePlayer) {
        var expected=javaPlayer.evaluate(-1,.2).draws().get(0);
        var actual=nativePlayer.evaluate(-1,.2).draws().get(0);
        assertEquals(expected.positions().size(),actual.positions().size());
        for(int i=0;i<expected.positions().size();i++) assertEquals(expected.positions().get(i),actual.positions().get(i),2e-5);
      }
    }
    var uvSource=new FbxReader().read(sample("maya_uv_set_tangent_w_7700_ascii.fbx"),ReadLimits.DEFAULT);
    try(var javaPlayer=new FbxPlayer(uvSource,ReadLimits.DEFAULT);
        var nativePlayer=FbxNativePlayer.tryCreate(uvSource,ReadLimits.DEFAULT,FbxEvaluation.Settings.DEFAULT)) {
      assertNotNull(nativePlayer);
      try(nativePlayer) {
        var expected=javaPlayer.evaluate(-1,.0);var actual=nativePlayer.evaluate(-1,.0);
        assertEquals(expected.draws().size(),actual.draws().size());
        for(int i=0;i<expected.draws().size();i++) {
          var a=expected.draws().get(i);var b=actual.draws().get(i);
          assertEquals(a.positions().size(),b.positions().size());assertEquals(a.normals().size(),b.normals().size());
          for(int k=0;k<a.positions().size();k++) assertEquals(a.positions().get(k),b.positions().get(k),2e-5);
          for(int k=0;k<a.normals().size();k++) assertEquals(a.normals().get(k),b.normals().get(k),2e-5);
          assertEquals(a.uvDirections().size(),b.uvDirections().size());
          for(int u=0;u<a.uvDirections().size();u++) {
            var at=a.uvDirections().get(u);var bt=b.uvDirections().get(u);assertEquals(at.tangents().size(),bt.tangents().size());assertEquals(at.bitangents().size(),bt.bitangents().size());
            for(int k=0;k<at.tangents().size();k++) assertEquals(at.tangents().get(k),bt.tangents().get(k),2e-5);
            for(int k=0;k<at.bitangents().size();k++) assertEquals(at.bitangents().get(k),bt.bitangents().get(k),2e-5);
          }
        }
      }
    }
  }
  @Test void nativeSnapshotsCanBeParsedConcurrently() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(FbxNativeReactor.available(), FbxNativeReactor.unavailableReason());
    var inputs=List.of(sample("maya_transformed_skin_7700_ascii.fbx"),sample("maya_uv_set_tangent_w_7700_ascii.fbx"));
    var pool=Executors.newFixedThreadPool(4);
    try {
      var jobs=new ArrayList<Future<FbxDocument>>();
      for(int i=0;i<12;i++) {
        var input=inputs.get(i%inputs.size());
        jobs.add(pool.submit(() -> new FbxReader().read(input,ReadLimits.DEFAULT)));
      }
      for(var job:jobs) {
        var document=job.get(30,TimeUnit.SECONDS);
        assertFalse(document.meshes().isEmpty());
      }
    } finally {
      pool.shutdownNow();
    }
  }
  @Test void nativeLinearPhysicsOverrideWritesResidentGeometryWithoutJson() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(FbxNativeReactor.available(), FbxNativeReactor.unavailableReason());
    var source=read("maya_transformed_skin_7700_ascii.fbx");
    assertTrue(source.skins().stream().allMatch(s->s.method()==0||s.method()==1));
    var evaluation=FbxNativePlayer.tryCreate(source,ReadLimits.DEFAULT,FbxEvaluation.Settings.DEFAULT);assertNotNull(evaluation);
    try(var player=(FbxNativePlayer)evaluation) {
      var base=player.evaluate(-1,.2);var poses=new LinkedHashMap<Integer,Pose>();
      for(int i=0;i<base.nodes().size();i++) { var p=FbxPhysicsBinding.nodePose(base.nodes().get(i));poses.put(i,new Pose(p.position().add(new Vec3(.01f,0,0)),p.rotation())); }
      var physical=player.evaluatePhysics(-1,.2,poses);assertNotNull(physical);
      var before=base.draws().get(0).positions();var after=physical.draws().get(0).positions();assertEquals(before.size(),after.size());
      boolean moved=false;for(int i=0;i<after.size();i++) { assertTrue(Double.isFinite(after.get(i)));if(Math.abs(after.get(i)-before.get(i))>1e-6)moved=true; }
      assertTrue(moved,"native physics override did not change any evaluated vertex");
      var expected=FbxPhysicsGeometry.apply(source,base,new FbxGeometry().compile(source,base),poses);
      var actual=new FbxGeometry().compile(source,physical);
      var expectedPositions=expected.draws().get(0).geometry().primitives().get(0).attributes().get("POSITION").values();
      var actualPositions=actual.draws().get(0).geometry().primitives().get(0).attributes().get("POSITION").values();
      assertEquals(expectedPositions.size(),actualPositions.size());for(int i=0;i<actualPositions.size();i++) assertEquals(expectedPositions.get(i),actualPositions.get(i),2e-4f,"physics vertex "+i);
    }
  }
  @Test void nativeDualQuaternionPhysicsOverrideKeepsDccSkinningStable() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(FbxNativeReactor.available(), FbxNativeReactor.unavailableReason());
    for(String name:List.of("maya_dual_quaternion_7500_ascii.fbx","maya_dual_quaternion_scale_7500_ascii.fbx","maya_dq_weights_7500_ascii.fbx")) {
      var source=read(name);var evaluation=FbxNativePlayer.tryCreate(source,ReadLimits.DEFAULT,FbxEvaluation.Settings.DEFAULT);assertNotNull(evaluation,name);
      try(var player=(FbxNativePlayer)evaluation) {
        var base=player.evaluate(-1,.25);var poses=new LinkedHashMap<Integer,Pose>();
        for(int i=0;i<base.nodes().size();i++) { var pose=FbxPhysicsBinding.nodePose(base.nodes().get(i));poses.put(i,pose); }
        var physical=player.evaluatePhysics(-1,.25,poses);assertNotNull(physical,name);
        assertEquals(base.draws().size(),physical.draws().size(),name);
        for(int d=0;d<base.draws().size();d++) {
          var a=base.draws().get(d).positions();var b=physical.draws().get(d).positions();assertEquals(a.size(),b.size(),name);
          for(int i=0;i<a.size();i++) assertEquals(a.get(i),b.get(i),3e-4,name+" vertex "+i);
        }
        var movedPoses=new LinkedHashMap<Integer,Pose>();for(int i=0;i<base.nodes().size();i++) { var pose=FbxPhysicsBinding.nodePose(base.nodes().get(i));movedPoses.put(i,new Pose(pose.position().add(new Vec3(.003f,0,0)),pose.rotation())); }
        var moved=player.evaluatePhysics(-1,.25,movedPoses);assertNotNull(moved,name+" moved");
        for(var draw:moved.draws()) for(int i=0;i<draw.positions().size();i++) assertTrue(Double.isFinite(draw.positions().get(i)),name+" moved finite");
      }
    }
  }
  /** OBJ files are unmodified DCC exports in the upstream corpus, not output from our bridge. */
  private static void compareObj(FbxDocument source,FbxEvaluation.Frame frame,String name) throws Exception {
    String text=new String(sample(name).copy(),StandardCharsets.UTF_8);var positions=new ArrayList<double[]>();
    var groups=new LinkedHashMap<String,List<Integer>>();List<Integer> corners=null;
    for(String line:text.split("\\R")) {
      String[] words=line.trim().split("\\s+");
      if(words[0].equals("v")) positions.add(new double[]{Double.parseDouble(words[1]),Double.parseDouble(words[2]),Double.parseDouble(words[3])});
      else if(words[0].equals("g")) corners=groups.computeIfAbsent(words[1],k->new ArrayList<>());
      else if(words[0].equals("f")) { assertNotNull(corners,name);for(int i=1;i<words.length;i++) corners.add(Integer.parseInt(words[i].split("/")[0])-1); }
    }
    var elementNames=new HashMap<Integer,String>();source.elements().forEach(e->elementNames.put(e.id(),e.name()));
    int checked=0;
    for(var draw:frame.draws()) {
      String nodeName=elementNames.get(source.nodes().get(draw.node()).element());var expected=groups.get(nodeName);if(expected==null) continue;
      assertEquals(expected.size()*3,draw.positions().size(),name+" "+nodeName);
      for(int i=0;i<expected.size();i++) for(int k=0;k<3;k++) assertEquals(positions.get(expected.get(i))[k]*source.info().sourceUnitMeters(),draw.positions().get(i*3+k),2e-6,name+" "+nodeName+" corner "+i+" axis "+k);
      checked++;
    }
    assertEquals(groups.values().stream().filter(g->!g.isEmpty()).count(),checked,name+" matched nodes");
  }
}
