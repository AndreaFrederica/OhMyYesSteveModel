package cc.sirrus.ysmlib.tools.scene;
import cc.sirrus.ysmlib.*;
import cc.sirrus.ysmlib.scene.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SceneToolTest {
    @Test void transitionAndCustomBindingsRoundTripThroughCliWithoutChangingSources()throws Exception {
        Path model=fixture();byte[] original=Files.readAllBytes(model);
        assertEquals(0,cli("init",model.toString()));
        assertEquals(0,cli("transition",model.toString(),"--from","idle","--to","sneaking","--path","动作.vmd"));
        var session=SceneAuthoring.open(model,s->{});
        assertEquals(1,session.profile().transitions().size());assertFalse(session.profile().transitions().get(0).animation().loop());
        assertEquals(0,cli("set",model.toString(),"--field","bones","--value","{}"));
        assertEquals(0,cli("bind",model.toString(),"--role","bone:customControl","--bone","0"));
        assertEquals(0,cli("set",model.toString(),"--field","retarget.sourceModel","--value","a".repeat(64)));
        assertEquals(0,cli("source-bind",model.toString(),"--role","bone:customControl","--source","SourceControl"));
        session=SceneAuthoring.open(model,s->{});
        assertEquals("a".repeat(64),session.profile().retarget().sourceModel());
        assertEquals("SourceControl",session.profile().retarget().sourceBones().get("bone:customControl"));
        assertEquals(0,cli("transition",model.toString(),"--from","idle","--to","sneaking","--path","AUTO"));
        assertTrue(SceneAuthoring.open(model,s->{}).profile().transitions().isEmpty());
        assertArrayEquals(original,Files.readAllBytes(model));
    }
    @Test void cliReferenceAndSocketEditsSurviveRebindingAndUnrelatedChanges()throws Exception {
        var model=fixture();assertEquals(0,cli("init",model.toString()));
        assertEquals(0,cli("set",model.toString(),"--field","bones","--value","{}"));
        assertEquals(0,cli("bind",model.toString(),"--role","leftHand","--bone","0"));
        assertEquals(0,cli("set",model.toString(),"--field","bones.leftHand.restRoll","--value","-80"));
        assertEquals(0,cli("bind",model.toString(),"--role","leftHand","--bone","0","--yaw","180"));
        assertEquals(0,cli("set",model.toString(),"--field","heldItems.left.x","--value","0.1"));
        assertEquals(0,cli("set",model.toString(),"--field","heldItems.firstPerson","--value","MODEL"));
        assertEquals(0,cli("set",model.toString(),"--field","placement.height","--value","1.9"));
        var p=SceneAuthoring.open(model,s->{}).profile();assertEquals(-80,p.bones().get("leftHand").restRoll());assertEquals(180,p.bones().get("leftHand").yaw());
        assertEquals(.1,p.heldItems().left().x());assertEquals(SceneModelProfile.FirstPersonItems.MODEL,p.heldItems().firstPerson());
        assertEquals(p.heldItems(),p.withBones(p.bones()).withPlacement(p.placement()).withRetarget(p.retarget()).withMetadata(p.metadata()).withActions(p.actions()).withPresentation(p.presentation()).withTransitions(p.transitions()).heldItems());
    }
    @TempDir Path temp;
    Path fixture()throws Exception{var model=temp.resolve("角色.pmx");try(var in=getClass().getResourceAsStream("/mmd-oracle/skin.pmx")){Files.copy(in,model);}try(var in=getClass().getResourceAsStream("/mmd-oracle/skin.vmd")){Files.copy(in,temp.resolve("动作.vmd"));}return model;}
    @Test void cliEditsSidecarWithoutTouchingSourceAndPreservesMetadata()throws Exception{
        Path model=fixture();byte[] before=Files.readAllBytes(model);long timestamp=Files.getLastModifiedTime(model).toMillis();
        assertEquals(0,cli("init",model.toString()));
        assertEquals(0,cli("set",model.toString(),"--field","metadata.name","--value","测试模型"));
        assertEquals(0,cli("action",model.toString(),"--name","walk","--path","动作.vmd","--loop","true"));
        assertEquals(0,cli("automap",model.toString()));
        var session=SceneAuthoring.open(model,s->{});assertEquals("测试模型",session.profile().metadata().name());assertEquals("动作.vmd",session.profile().actions().get("walk").path());
        assertArrayEquals(before,Files.readAllBytes(model));assertEquals(timestamp,Files.getLastModifiedTime(model).toMillis());
        assertNotEquals(0,cli("bind",model.toString(),"--role","head","--bone","999999"));
        assertEquals(session.profile(),SceneAuthoring.open(model,s->{}).profile());
    }
    @Test void mappedPlaybackIsTheProductionPlayerIncludingBackwardSeekAndRendering()throws Exception{
        var session=SceneAuthoring.open(fixture(),s->{});var action=new SceneModelProfile.Action("动作.vmd",0,true);session.profile(session.profile().withActions(Map.of("walk",action)));
        try(var mapped=session.playback(session.action("walk"));var reference=YsmRuntime.scenes().playback(session.assets(),action.resolve(session.assets().source()),ScenePackagePlayback.Settings.previewUi().withPhysics(false).withModelProfile(session.profile()),SceneAuthoring.LIMITS)){
            for(double t:new double[]{0,.5,1,.25}){
                var a=mapped.seek(t);var b=reference.seek(t);
                assertEquals(((ScenePackagePlayback.Mmd)b.details()).value().pose(),((ScenePackagePlayback.Mmd)a.details()).value().pose());
                assertEquals(b.thirdPerson(),a.thirdPerson());
            }
            var rendered=ScenePreview.render(mapped.seek(.5),session.profile(),session.bones(),320,320,0,-8,1,-1);
            assertTrue(rendered.triangles()>0);assertTrue(rendered.coveredPixels()>100,"Preview must contain real mesh pixels, not only skeleton labels");
        }
    }
    @Test void saveConflictAndLosslessPackageRoundTrip()throws Exception{
        var session=SceneAuthoring.open(fixture(),s->{});session.save(SceneAuthoring.sidecar(session.source()));var other=SceneAuthoring.open(session.source(),s->{});
        session.profile(ProfileEdits.set(session.profile(),"metadata.tips","Do not redistribute"));session.save(SceneAuthoring.sidecar(session.source()));
        assertThrows(IOException.class,()->other.save(SceneAuthoring.sidecar(session.source())));
        var output=temp.resolve("export.yscene");session.pack(output);var reopened=SceneAuthoring.open(output,s->{});
        assertEquals(session.profile(),reopened.profile());
        assertArrayEquals(Files.readAllBytes(session.source()),reopened.assets().source().files().get(session.source().getFileName().toString()).copy());
        assertThrows(FileAlreadyExistsException.class,()->session.pack(output));
    }
    @Test void metadataAndSourceBindingsRoundTripAndSurvivePlacementChanges(){
        var profile=SceneModelProfile.defaults(.08,20,0).withMetadata(new SceneModelProfile.Metadata("name","tip",new SceneModelProfile.License("CC-BY","credit"),List.of(new SceneModelProfile.Author("A","model",List.of(new SceneModelProfile.Pair("site","https://example.invalid")),"comment","avatar.png")),List.of(new SceneModelProfile.Pair("project","https://example.invalid"))))
            .withRetarget(new SceneModelProfile.Retarget(Map.of("head","Head"),.5));
        var edited=ProfileEdits.set(profile,"placement.scale","2");var round=ProfileEdits.parse(ProfileEdits.json(edited));assertEquals(profile.metadata(),round.metadata());assertEquals(profile.retarget(),round.retarget());assertEquals(2,round.placement().scale());
    }
    private int cli(String...args){return SceneTool.run(args,new PrintStream(new ByteArrayOutputStream()),new PrintStream(new ByteArrayOutputStream()));}
}
