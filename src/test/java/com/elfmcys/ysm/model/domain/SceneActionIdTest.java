package com.elfmcys.ysm.model.domain;
import cc.sirrus.ysmlib.scene.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SceneActionIdTest {
    private SceneAnimation clip(String id,String path) {
        return new SceneAnimation(new ScenePackagePlayback.Selection(id,0),"same-name",path,new AnimationPreview.Range(0,2,30),30);
    }
    @Test void stableAcrossEnumerationButNotAcrossSourceOrMode() {
        var a=clip("motion-0","dance.vmd");var b=clip("motion-18","dance.vmd");
        assertEquals(SceneActionId.of(a,false),SceneActionId.of(b,false));
        assertNotEquals(SceneActionId.of(a,false),SceneActionId.of(clip("motion-0","other.vmd"),false));
        assertNotEquals(SceneActionId.of(a,false),SceneActionId.of(a,true));
        assertNotEquals(SceneActionId.of(clip("@ysm/generated/walk","model.pmx"),false),SceneActionId.of(clip("@ysm/generated/run","model.pmx"),false));
    }
    @Test void nonceRestartsWithoutExpandingServerWhitelist() {
        var id=SceneActionId.of(clip("motion","dance.vmd"),true);
        var first=SceneActionId.request(id,1);var second=SceneActionId.request(id,-1);
        assertNotEquals(first,second);assertEquals(id,SceneActionId.base(first));assertEquals(id,SceneActionId.base(second));
        assertTrue(SceneActionId.looping(first));
        for(String bad:new String[]{id+"~1",first+"~0000000000000000",id+"/evil","../dance.vmd","alias",null})
            assertEquals("",SceneActionId.base(bad));
    }
}
