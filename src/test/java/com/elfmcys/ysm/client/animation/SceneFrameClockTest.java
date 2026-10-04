package com.elfmcys.ysm.client.animation;

import cc.sirrus.ysmlib.scene.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SceneFrameClockTest {
    private SceneStateTransitions transitions() {
        var source=new ScenePackage(new ScenePackage.Source("model","a.pmx",ScenePackage.Format.PMX),
                new ScenePackage.Settings(.08,-1,cc.sirrus.ysmlib.scene.fbx.FbxEvaluation.SkinSpace.BIND_WORLD),
                List.of(new ScenePackage.Source("down","down.vmd",ScenePackage.Format.VMD)),List.of(),
                Map.of("a.pmx",new ByteData(new byte[0]),"down.vmd",new ByteData(new byte[0])));
        var clip=new SceneAnimation(new ScenePackagePlayback.Selection("down",0),"down","down.vmd",new AnimationPreview.Range(0,1,30),30);
        var profile=SceneModelProfile.defaults(.08,20,0).withTransitions(List.of(
                new SceneModelProfile.Transition("idle","sneaking",new SceneModelProfile.Action("down.vmd",0,false))));
        return new SceneStateTransitions(profile,source,List.of(clip));
    }

    @Test void multiplePassesAndDecreasingPartialTicksCannotRewindTransitionsOrAdvanceTwice() {
        var clock=new SceneFrameClock();var transitions=transitions();
        assertTrue(clock.beginFrame(100,50));assertNull(transitions.update("idle",clock.seconds()));
        assertTrue(clock.beginFrame(101,50.05));var active=transitions.update("sneaking",clock.seconds());assertNotNull(active);
        // A second world/first-person pass with a smaller partial tick used to throw in update().
        assertFalse(clock.beginFrame(101,50.04900072));assertEquals(50.05,clock.seconds());
        assertTrue(clock.beginFrame(102,50.0495));assertSame(active,transitions.update("sneaking",clock.seconds()));
        assertTrue(clock.beginFrame(103,50.5));assertSame(active,transitions.update("sneaking",clock.seconds()));
        assertTrue(clock.beginFrame(104,51.1));assertNull(transitions.update("sneaking",clock.seconds()));
        // The Lib's strict contract stays intact; the host is responsible for normalizing its inputs.
        assertThrows(IllegalArgumentException.class,()->transitions.update("idle",50));
    }

    @Test void pausedTimeAndIndependentInstancesDoNotShareOrRestartTheirClocks() {
        var first=new SceneFrameClock();var second=new SceneFrameClock();
        assertTrue(first.beginFrame(8,100));assertTrue(first.beginFrame(9,100));assertEquals(100,first.seconds());
        assertTrue(second.beginFrame(9,0));assertEquals(0,second.seconds());
        assertFalse(first.beginFrame(9,200));assertEquals(100,first.seconds());
        assertThrows(IllegalArgumentException.class,()->first.beginFrame(10,Double.NaN));
        assertTrue(first.beginFrame(10,100.05));assertEquals(100.05,first.seconds());
        assertThrows(IllegalArgumentException.class,()->first.beginFrame(8,100.1));
    }
}
