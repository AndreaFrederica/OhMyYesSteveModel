package com.elfmcys.ysm.client.animation;

import cc.sirrus.ysmlib.scene.*;
import com.elfmcys.ysm.capability.PlayerAnimatableCapability;
import com.elfmcys.ysm.geckolib3.core.molang.util.StringPool;
import com.elfmcys.ysm.molang.runtime.Struct;
import java.util.*;

/** Face ownership uses the existing model-scoped roaming state and FULL/DELTA replay. */
public final class GeneralExpressions {
    private GeneralExpressions() {}
    private static final String ANIMATION="ysm_face_animation",TIME_HIGH="ysm_face_time_hi",TIME_LOW="ysm_face_time_lo";
    public record FaceAnimation(String name,double seconds) {}
    private static List<String> faceClips(SceneModelProfile profile){return profile.features().animations().entrySet().stream().filter(e->e.getValue().tracks().stream().allMatch(t->t.kind()==SceneModelFeatures.Target.EXPRESSION)).map(Map.Entry::getKey).sorted().toList();}
    public static boolean faceOnly(SceneModelProfile profile,String source){return source.startsWith("@ysm/profile/")&&faceClips(profile).contains(source.substring(13));}
    /** Split world ticks into exact float integers so late joiners resume the same clip phase. */
    public static void play(PlayerAnimatableCapability cap,String source,boolean loop) {
        var profile=cap.getModelRenderTarget().generalMeshResources().profile();var names=faceClips(profile);int index=names.indexOf(source.substring(13));if(index<0)throw new IllegalArgumentException("Not a face clip");
        var player=net.minecraft.client.Minecraft.getInstance().player;long ticks=player.level().getGameTime();
        var roaming=cap.getRoamingStruct();if(!cap.isLocalPlayer()||roaming==null)throw new IllegalStateException("Local face state unavailable");
        reserve(roaming,List.of(ANIMATION,TIME_HIGH,TIME_LOW));
        roaming.putProperty(StringPool.computeIfAbsent(TIME_HIGH),(float)(ticks>>>16));roaming.putProperty(StringPool.computeIfAbsent(TIME_LOW),(float)(ticks&65535));
        roaming.putProperty(StringPool.computeIfAbsent(ANIMATION),(float)((index+1)*(loop?1:-1)));cap.handleRoamingVarsChanges();
    }
    public static FaceAnimation animation(SceneModelProfile profile,Struct roaming,double ticks) {
        if(roaming==null)return null;float slot=number(roaming,ANIMATION);if(slot==0||slot!=(int)slot)return null;
        var names=faceClips(profile);int index=(int)Math.abs(slot)-1;if(index<0||index>=names.size())return null;
        double start=number(roaming,TIME_HIGH)*65536d+number(roaming,TIME_LOW),seconds=Math.max(0,(ticks-start)/20d);String name=names.get(index);double duration=profile.features().animations().get(name).duration();
        if(slot<0&&seconds>=duration)return null;return new FaceAnimation(name,slot>0?seconds%duration:seconds);
    }
    private static float number(Struct roaming,String name){var value=roaming.getProperty(StringPool.computeIfAbsent(name));return value instanceof Number n&&Float.isFinite(n.floatValue())?n.floatValue():0;}
    private static void reserve(Struct roaming,List<String> variables){if(roaming instanceof com.elfmcys.ysm.client.animation.molang.roaming.LocalRoamingStruct local){var existing=local.snapshotValues().keySet();long added=variables.stream().map(StringPool::computeIfAbsent).filter(n->!existing.contains(n)).count();if(existing.size()+added>64)throw new IllegalStateException("Face state exceeds roaming's 64 variable limit");}}
    public static String variable(String semantic) {
        try {var bytes=java.security.MessageDigest.getInstance("SHA-256").digest(semantic.getBytes(java.nio.charset.StandardCharsets.UTF_8));return "ysm_face_"+HexFormat.of().formatHex(bytes,0,10);}
        catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    public static Map<String,Float> read(SceneModelProfile profile,Struct roaming) {
        if(roaming==null)return Map.of();var out=new LinkedHashMap<String,Float>();
        for(String name:profile.features().expressions().keySet()){Object raw=roaming.getProperty(StringPool.computeIfAbsent(variable(name)));
            if(raw instanceof Number n&&Float.isFinite(n.floatValue())&&n.floatValue()>=1&&n.floatValue()<=2)out.put(name,n.floatValue()-1);}
        return Map.copyOf(out);
    }
    /** null releases this semantic channel; zero explicitly suppresses it. */
    public static void set(PlayerAnimatableCapability cap,String name,Float weight) {
        var target=cap.getModelRenderTarget();if(!cap.isLocalPlayer()||target==null||target.generalMeshResources()==null||cap.getRoamingStruct()==null)throw new IllegalStateException("Local scene is not ready");
        if(!target.generalMeshResources().profile().features().expressions().containsKey(name))throw new IllegalArgumentException("Expression unavailable: "+name);
        if(weight!=null&&(!Float.isFinite(weight)||weight<0||weight>1))throw new IllegalArgumentException("Expression weight outside [0,1]");
        reserve(cap.getRoamingStruct(),List.of(variable(name)));cap.getRoamingStruct().putProperty(StringPool.computeIfAbsent(variable(name)),weight==null?0f:weight+1);
        cap.handleRoamingVarsChanges();
    }
    public static void clear(PlayerAnimatableCapability cap){var target=cap.getModelRenderTarget();if(target==null||target.generalMeshResources()==null)return;var roaming=cap.getRoamingStruct();if(roaming==null||!cap.isLocalPlayer())return;for(String name:target.generalMeshResources().profile().features().expressions().keySet())if(number(roaming,variable(name))!=0)set(cap,name,null);if(number(roaming,ANIMATION)!=0){roaming.putProperty(StringPool.computeIfAbsent(ANIMATION),0f);cap.handleRoamingVarsChanges();}}
}
