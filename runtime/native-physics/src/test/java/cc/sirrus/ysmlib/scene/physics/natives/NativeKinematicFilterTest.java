package cc.sirrus.ysmlib.scene.physics.natives;

import cc.sirrus.ysmlib.scene.*;
import cc.sirrus.ysmlib.scene.physics.*;
import cc.sirrus.ysmlib.scene.physics.wasm.WasmPhysicsProvider;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;
import static cc.sirrus.ysmlib.scene.physics.natives.NativePhysicsConformanceTest.*;

class NativeKinematicFilterTest {
  static PhysicsSpec.Body sphere(PhysicsSpec.Motion motion,float x,int mask) {
    return new PhysicsSpec.Body(PhysicsSpec.Shape.SPHERE,new Vec3(.5f,0,0),at(x,0,0),motion,
        motion==PhysicsSpec.Motion.DYNAMIC?1:0,0,0,0,0,.04f,1,mask);
  }
  @ParameterizedTest @EnumSource(PhysicsSpec.Motion.class)
  void filterMatchesRsMixedKinematicRuleAndRetainsMasks(PhysicsSpec.Motion other) {
    for(boolean enabled:new boolean[]{false,true})for(int mask:new int[]{0,65535}) {
      var settings=new PhysicsSpec.World(Vec3.ZERO,1f/60,10).withKinematicFilter(enabled);
      try(var n=nativeProvider().createWorld(settings);var w=new WasmPhysicsProvider().createWorld(settings)) {
        for(var world:List.of(n,w)){world.addBody(sphere(other,0,mask));world.addBody(sphere(PhysicsSpec.Motion.DYNAMIC,.75f,mask));}
        var ns=n.snapshot();var ws=w.snapshot();
        for(int step=1;step<=20;step++){n.step();w.step();states(w,n,step);}
        var result=n.bodyStates();boolean collides=mask!=0 && !(enabled && other==PhysicsSpec.Motion.KINEMATIC);
        assertEquals(collides,result.get(1).pose().position().x()>.76f,"other="+other+" filter="+enabled+" mask="+mask);
        n.restore(ns);w.restore(ws);
        for(int step=1;step<=20;step++){n.step();w.step();states(w,n,step);}
        assertEquals(result,n.bodyStates(),"Replay must keep the construction policy");
      }
    }
  }
  @Test void temporaryKinematicSwitchRebuildsPairDecisionAndRestoresDynamicContact() {
    var settings=new PhysicsSpec.World(Vec3.ZERO,1f/60,10).withKinematicFilter(true);
    try(var n=nativeProvider().createWorld(settings);var w=new WasmPhysicsProvider().createWorld(settings)) {
      for(var world:List.of(n,w)) {
        world.addBody(sphere(PhysicsSpec.Motion.DYNAMIC,0,65535));world.addBody(sphere(PhysicsSpec.Motion.DYNAMIC,.75f,65535));
        world.setKinematic(0,true);
      }
      n.step();w.step();states(w,n,1);assertEquals(.75f,n.bodyStates().get(1).pose().position().x());
      for(var world:List.of(n,w)){world.setKinematic(0,false);world.step();}
      states(w,n,2);assertTrue(n.bodyStates().get(1).pose().position().x()>.76f);
    }
    assertTrue(settings.withoutReplay().kinematicFilter());
  }
}
