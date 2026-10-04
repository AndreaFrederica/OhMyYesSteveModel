package cc.sirrus.ysmlib;

import cc.sirrus.ysmlib.scene.*;
import java.util.*;

/** Inventory preserves source IDs, absolute clip times and sample rates; it performs no retargeting or simulation. */
final class ScenePackageAnimations {
    private ScenePackageAnimations() {}
    static List<SceneAnimation> list(ScenePackageAssets assets,double fallbackFrameRate) {
        if(!Double.isFinite(fallbackFrameRate) || fallbackFrameRate<=0) throw new IllegalArgumentException("Invalid preview stepping rate");
        var output=new ArrayList<SceneAnimation>();
        var sources=new ArrayList<ScenePackage.Source>();sources.add(assets.source().model());sources.addAll(assets.source().animations());
        for(var source:sources) {
            var document=Objects.requireNonNull(assets.documents().get(source.id()),"Missing parsed animation source");
            if(document instanceof ScenePackageAssets.Gltf value) clips(output,source,value.value().scene().animations(),fallbackFrameRate);
            else if(document instanceof ScenePackageAssets.Vrm value) clips(output,source,value.value().scene().animations(),fallbackFrameRate);
            else if(document instanceof ScenePackageAssets.Vrma value) clips(output,source,value.value().animations(),fallbackFrameRate);
            else if(document instanceof ScenePackageAssets.Vmd value) clips(output,source,List.of(value.animation().clip()),fallbackFrameRate);
            else if(document instanceof ScenePackageAssets.Vpd value) clips(output,source,List.of(value.animation()),fallbackFrameRate);
            else if(document instanceof ScenePackageAssets.Bvh value) {
                var bvh=value.value();double fps=1/bvh.frameSeconds();
                add(output,source,0,source.id(),0,bvh.durationSeconds(),fps,fallbackFrameRate);
            } else if(document instanceof ScenePackageAssets.Fbx value) {
                var fbx=value.value();var names=new HashMap<Integer,String>();fbx.elements().forEach(e->names.put(e.id(),e.name()));
                for(int index=0;index<fbx.animations().size();index++) {
                    var clip=fbx.animations().get(index);
                    add(output,source,index,names.getOrDefault(clip.element(),""),clip.begin(),clip.end(),fbx.info().framesPerSecond(),fallbackFrameRate);
                }
            }
            else if(document instanceof ScenePackageAssets.Pmx || document instanceof ScenePackageAssets.Pmd) {
                // MMD models often ship without a VMD. Expose the host
                // generated locomotion clips alongside source animations so
                // Alt+Y and explicit preview controls can select them through
                // the same SceneAnimation API.
                for(String state:List.of("idle","walk","run","sneak","swim","fly","death")) {
                    output.add(new SceneAnimation(
                        new ScenePackagePlayback.Selection("@ysm/generated/" + state,-1),
                        "Generated fallback: " + state,source.path(),new AnimationPreview.Range(0,2,30),30));
                }
            }
            // PMM exposes a project timeline and multiple object tracks, not a source-local model clip list.
        }
        output.sort(Comparator.comparing(a -> a.selection().sourceId().startsWith("@ysm/generated/")));
        return List.copyOf(output);
    }
    private static void clips(List<SceneAnimation> output,ScenePackage.Source source,List<AnimationClip> clips,double fallbackFrameRate) {
        for(int index=0;index<clips.size();index++) {
            var clip=clips.get(index);
            double start=clip.tracks().stream().mapToDouble(t->t.curve().time(0)).min().orElse(0);
            add(output,source,index,clip.name(),start,clip.durationSeconds(),clip.sourceFramesPerSecond(),fallbackFrameRate);
        }
    }
    private static void add(List<SceneAnimation> output,ScenePackage.Source source,int clip,String name,double begin,double end,double fps,double fallback) {
        var range=new AnimationPreview.Range(begin,end,fps>0?fps:fallback);
        output.add(new SceneAnimation(new ScenePackagePlayback.Selection(source.id(),clip),name,source.path(),range,fps));
    }
}
