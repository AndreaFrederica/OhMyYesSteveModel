package cc.sirrus.ysmlib.scene;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SceneStateTransitionsTest {
    private final ScenePackage source=new ScenePackage(new ScenePackage.Source("model","a.pmx",ScenePackage.Format.PMX),
        new ScenePackage.Settings(.08,-1,cc.sirrus.ysmlib.scene.fbx.FbxEvaluation.SkinSpace.BIND_WORLD),
        List.of(new ScenePackage.Source("down","down.vmd",ScenePackage.Format.VMD)),List.of(),
        Map.of("a.pmx",new ByteData(new byte[0]),"down.vmd",new ByteData(new byte[0])));
    private final SceneAnimation clip=new SceneAnimation(new ScenePackagePlayback.Selection("down",0),"down","down.vmd",new AnimationPreview.Range(2,3,30),30);
    private final SceneModelProfile profile=SceneModelProfile.defaults(.08,20,0).withTransitions(List.of(
        new SceneModelProfile.Transition("idle","sneaking",new SceneModelProfile.Action("down.vmd",0,false))));

    @Test void playsOnceThenEntersDestinationWithoutRestartingOnRepeatedRequests() {
        var player=new SceneStateTransitions(profile,source,List.of(clip));
        assertNull(player.update("idle",10));
        var edge=player.update("sneaking",10.1);assertNotNull(edge);
        assertSame(edge,player.update("sneaking",10.9));
        assertNull(player.update("sneaking",11.2));
        assertNull(player.update("sneaking",12));
    }
    @Test void newStateInterruptsAndInitialStateDoesNotInventAnEntryTransition() {
        var player=new SceneStateTransitions(profile,source,List.of(clip));
        assertNull(player.update("sneaking",0));assertNull(player.update("idle",1));
        assertNotNull(player.update("sneaking",2));assertNull(player.update("death",2.1));
        assertNull(player.update("death",20));
        assertThrows(IllegalArgumentException.class,()->player.update("idle",0));
    }
    @Test void invalidAndUnboundedTransitionsFailBeforePlayback() {
        assertThrows(IllegalArgumentException.class,()->new SceneStateTransitions(profile,source,List.of()));
        assertThrows(IllegalArgumentException.class,()->profile.withTransitions(List.of(profile.transitions().get(0),profile.transitions().get(0))));
        assertThrows(IllegalArgumentException.class,()->new SceneModelProfile.Transition("idle","walk",new SceneModelProfile.Action("down.vmd",0,true)));
        var still=new SceneAnimation(clip.selection(),"still","down.vmd",new AnimationPreview.Range(0,0,30),30);
        assertThrows(IllegalArgumentException.class,()->new SceneStateTransitions(profile,source,List.of(still)));
    }
}
