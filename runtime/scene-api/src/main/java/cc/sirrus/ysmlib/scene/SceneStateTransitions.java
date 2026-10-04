package cc.sirrus.ysmlib.scene;

import java.util.*;

/** Per-instance transition clock. The host still decides logical states and evaluates animations. */
public final class SceneStateTransitions {
    public record Active(SceneModelProfile.Transition edge,SceneAnimation animation) {}
    private final Map<List<String>,Active> edges;
    private String requested;
    private Active active;
    private double started,lastTime=Double.NEGATIVE_INFINITY;

    public SceneStateTransitions(SceneModelProfile profile,ScenePackage source,List<SceneAnimation> clips) {
        profile.validateAnimations(source,clips);
        var values=new HashMap<List<String>,Active>();
        for(var edge:profile.transitions()) {
            var selection=edge.animation().resolve(source);
            var animation=clips.stream().filter(c->c.selection().equals(selection)).findFirst().orElseThrow();
            values.put(List.of(edge.from(),edge.to()),new Active(edge,animation));
        }
        edges=Map.copyOf(values);
    }

    /** A changed request interrupts immediately, using the previous requested destination as the new origin. */
    public Active update(String state,double seconds) {
        if(state==null||state.isBlank()||!Double.isFinite(seconds)||seconds<lastTime)
            throw new IllegalArgumentException("Invalid state or non-monotonic transition clock");
        lastTime=seconds;
        if(!state.equals(requested)) {
            active=requested==null?null:edges.get(List.of(requested,state));
            requested=state;started=seconds;
        }
        if(active!=null&&seconds-started>=active.animation().range().end()-active.animation().range().start())active=null;
        return active;
    }
}
