package com.elfmcys.ysm.client.animation;

import cc.sirrus.ysmlib.scene.*;
import java.util.*;

/** Host action layers retain their own clocks across body clip changes. */
public final class GeneralInteractionController {
    private static final class Active {
        final SceneModelFeatures.LayerRule rule;final double start;double stop=Double.NaN;
        Active(SceneModelFeatures.LayerRule rule,double time){this.rule=rule;start=time;}
    }
    private final List<Active> active=new ArrayList<>();
    public void reset(){active.clear();}
    public List<ScenePackagePlayback.Layer> update(SceneModelProfile profile,ScenePackage source,List<SceneAnimation> clips,
            List<SceneModelFeatures.ItemContext> contexts,double time,boolean retargeted) {
        var selected=new LinkedHashSet<SceneModelFeatures.LayerRule>();
        for(var context:contexts){SceneModelFeatures.LayerRule best=null;for(var rule:profile.features().layers())if((rule.mode()==SceneModelFeatures.LayerMode.ANY||(rule.mode()==SceneModelFeatures.LayerMode.YSM_CONTROLLER)==retargeted)&&rule.match().matches(context)&&(best==null||rule.priority()>=best.priority()))best=rule;if(best!=null)selected.add(best);}
        for(var a:active)if(Double.isNaN(a.stop)&&!selected.contains(a.rule))a.stop=time;
        for(var rule:selected)if(active.stream().noneMatch(a->Double.isNaN(a.stop)&&a.rule.equals(rule)))active.add(new Active(rule,time));
        active.sort(Comparator.comparingInt(a->a.rule.priority()));
        var result=new ArrayList<ScenePackagePlayback.Layer>();var iterator=active.iterator();
        while(iterator.hasNext()){
            var a=iterator.next();double fade=a.rule.fadeIn()==0?1:Math.min(1,(time-a.start)/a.rule.fadeIn());
            if(!Double.isNaN(a.stop)){double initial=a.rule.fadeIn()==0?1:Math.min(1,(a.stop-a.start)/a.rule.fadeIn());fade=a.rule.fadeOut()==0?0:initial*Math.max(0,1-(time-a.stop)/a.rule.fadeOut());if(fade<=0){iterator.remove();continue;}}
            var selection=a.rule.animation().resolve(source);boolean controller=selection.sourceId().equals("@ysm/controller");var clip=controller?null:clips.stream().filter(c->c.selection().equals(selection)).findFirst().orElseThrow(()->new IllegalArgumentException("Missing interaction animation: "+a.rule.id()));
            double elapsed=Math.max(0,time-a.start)*a.rule.speed(),duration=controller?1:clip.range().end()-clip.range().start();
            double seconds=controller?0:clip.range().start()+(a.rule.animation().loop()&&duration>0?elapsed%duration:Math.min(duration,elapsed));
            var bones=new HashSet<Integer>();a.rule.bones().forEach(b->bones.add(SceneFeatureBindings.requiredBone(profile,b)));
            var morphs=new HashSet<Integer>();for(String name:a.rule.expressions())for(var t:profile.features().expressions().get(name).targets())if(t.kind()==SceneModelFeatures.Target.MMD_MORPH)morphs.add(t.index());
            result.add(new ScenePackagePlayback.Layer(selection,seconds,(float)(a.rule.weight()*fade),bones,morphs,Set.copyOf(a.rule.expressions())));
        }return List.copyOf(result);
    }
}
