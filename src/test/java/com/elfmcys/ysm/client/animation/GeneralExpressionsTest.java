package com.elfmcys.ysm.client.animation;

import cc.sirrus.ysmlib.scene.*;
import com.elfmcys.ysm.geckolib3.core.molang.util.StringPool;
import com.elfmcys.ysm.client.animation.molang.roaming.RemoteRoamingStruct;
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GeneralExpressionsTest {
    SceneModelProfile profile(){
        var expression=new SceneModelFeatures.Expression(List.of(new SceneModelFeatures.ExpressionTarget(SceneModelFeatures.Target.MMD_MORPH,-1,0,"blink",1)),List.of(),0,0);
        var track=new SceneModelFeatures.Track(SceneModelFeatures.Target.EXPRESSION,"blink",AnimationClip.Property.MORPH_WEIGHTS,SceneModelFeatures.Interpolation.LINEAR,List.of(new SceneModelFeatures.Key(0,List.of(0f)),new SceneModelFeatures.Key(2,List.of(1f))));
        return SceneModelProfile.defaults(.08,18,0).withFeatures(new SceneModelFeatures(Map.of("blink",expression),Map.of("face",new SceneModelFeatures.Clip(2,30,List.of(track))),List.of(),List.of(),SceneModelFeatures.SocketSpace.BONE,SceneModelFeatures.Procedural.defaults(),SceneModelFeatures.FirstPerson.defaults()));
    }
    @Test void fullSnapshotRestoresHeldZeroAndReleaseAndRemainsModelScoped(){
        var values=new Int2FloatOpenHashMap();String key=GeneralExpressions.variable("blink");assertEquals(29,key.length());
        values.put(StringPool.computeIfAbsent(key),1.75f);var roaming=new RemoteRoamingStruct(values);
        assertEquals(Map.of("blink",.75f),GeneralExpressions.read(profile(),roaming));assertEquals(Map.of(),GeneralExpressions.read(SceneModelProfile.defaults(.08,18,0),roaming));
        values.put(StringPool.computeIfAbsent(key),1f);assertEquals(Map.of("blink",0f),GeneralExpressions.read(profile(),roaming));
        values.put(StringPool.computeIfAbsent(key),0f);assertTrue(GeneralExpressions.read(profile(),roaming).isEmpty());
    }
    @Test void lateJoinResumesFacePhaseAndOneShotReleasesAtEnd(){
        long start=123456789;var values=new Int2FloatOpenHashMap();values.put(StringPool.computeIfAbsent("ysm_face_time_hi"),(float)(start>>>16));values.put(StringPool.computeIfAbsent("ysm_face_time_lo"),(float)(start&65535));values.put(StringPool.computeIfAbsent("ysm_face_animation"),1f);
        var full=new RemoteRoamingStruct(new Int2FloatOpenHashMap(values));assertEquals(1.5,GeneralExpressions.animation(profile(),full,start+70).seconds(),1e-8);assertTrue(GeneralExpressions.faceOnly(profile(),"@ysm/profile/face"));
        values.put(StringPool.computeIfAbsent("ysm_face_animation"),-1f);var once=new RemoteRoamingStruct(values);assertEquals(1.5,GeneralExpressions.animation(profile(),once,start+30).seconds(),1e-8);assertNull(GeneralExpressions.animation(profile(),once,start+40));
        values.put(StringPool.computeIfAbsent("ysm_face_animation"),0f);assertNull(GeneralExpressions.animation(profile(),once,start+40));
    }
}
