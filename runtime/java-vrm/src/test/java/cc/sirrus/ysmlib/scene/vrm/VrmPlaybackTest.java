package cc.sirrus.ysmlib.scene.vrm;

import cc.sirrus.ysmlib.scene.*;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static cc.sirrus.ysmlib.scene.vrm.VrmFixtures.*;

class VrmPlaybackTest {
  private VrmDocument document() throws Exception {
    var json=avatar(false);var nodes=json.getAsJsonArray("nodes");int n=nodes.size();nodes.get(0).getAsJsonObject().getAsJsonArray("children").add(n);
    nodes.add(JsonParser.parseString("{\"translation\":[0,1,0],\"children\":["+(n+1)+"]}"));nodes.add(JsonParser.parseString("{\"translation\":[0,-0.6,0]}"));
    json.getAsJsonObject("extensions").add("VRMC_springBone",JsonParser.parseString("{\"specVersion\":\"1.0\",\"springs\":[{\"joints\":[{\"node\":"+n+",\"gravityDir\":[1,0,0],\"gravityPower\":0.5,\"stiffness\":0.7},{\"node\":"+(n+1)+"}]}]}"));
    return read(json);
  }
  private AnimationClip clip() {
    return new AnimationClip("root motion",List.of(new AnimationClip.Track(AnimationClip.Property.TRANSLATION,0,"","",
        new AnimationCurve(new double[]{0,1,2},new FloatData(0,0,0, .6f,.2f,0, -.2f,0,0),3,false,AnimationCurve.Interpolation.LINEAR,FloatData.EMPTY,FloatData.EMPTY,FloatData.EMPTY))),0);
  }
  @Test void fractionalSamplingForwardAndBackwardSeeksMatchFreshReplay() throws Exception {
    var d=document();var e=new VrmEvaluator(d);var c=clip();VrmPlayback.Source source=t->e.evaluate(c,t,VrmEvaluation.Input.NONE);
    var player=new VrmPlayer(d,source,VrmPlayback.Settings.preview());var fresh=new VrmPlayer(d,source,VrmPlayback.Settings.preview());
    var start=player.frame();player.seek(.5);player.seek(.503);var fractional=player.frame();assertEquals(.503,fractional.pose().seconds());
    for(int i=0;i<10;i++) assertSame(fractional,player.frame());player.seek(1.5);
    var backward=player.seek(.503);var expected=fresh.seek(.503);assertEquals(expected.pose().localMatrices(),backward.pose().localMatrices());
    assertNotEquals(start.pose().localMatrices().get(d.scene().nodes().size()-2),backward.pose().localMatrices().get(d.scene().nodes().size()-2));
    assertEquals(fresh.seek(1.2).pose().globalMatrices(),player.seek(1.2).pose().globalMatrices());
  }
  @Test void failedSourceAndReplayBudgetLeaveLastFrameAndHistoryUsable() throws Exception {
    var d=document();var e=new VrmEvaluator(d);var c=clip();boolean[] fail={false};
    VrmPlayback.Source source=t->{ if(fail[0] && t>.21) throw new IllegalArgumentException("synthetic source failure");return e.evaluate(c,t,VrmEvaluation.Input.NONE); };
    var player=new VrmPlayer(d,source,new VrmPlayback.Settings(1.0/60,60));player.seek(.1);var before=player.frame();fail[0]=true;
    assertThrows(IllegalArgumentException.class,()->player.seek(.3));assertSame(before,player.frame());fail[0]=false;
    var fresh=new VrmPlayer(d,source,VrmPlayback.Settings.preview());assertEquals(fresh.seek(.4).pose().globalMatrices(),player.seek(.4).pose().globalMatrices());
    var frame=player.frame();assertThrows(IllegalArgumentException.class,()->player.seek(50));assertSame(frame,player.frame());
  }
}
