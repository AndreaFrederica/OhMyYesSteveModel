package cc.sirrus.ysmlib.scene.vrm;

import cc.sirrus.ysmlib.scene.*;
import java.util.*;

/** Visibility is an index projection of one deformed frame, never a second morph/skin evaluation. */
public final class VrmGeometryViews {
  public record Frame(GeometryFrame firstPerson,GeometryFrame thirdPerson) {}
  private final Map<Integer,VrmViewGeometry.NodeView> views;
  public VrmGeometryViews(VrmViewGeometry source) {
    var map=new HashMap<Integer,VrmViewGeometry.NodeView>();for(var node:source.nodes())map.put(node.node(),node);views=Map.copyOf(map);
  }
  public Frame project(GeometryFrame complete) {
    return new Frame(project(complete,true),project(complete,false));
  }
  private GeometryFrame project(GeometryFrame complete,boolean first) {
    var output=new ArrayList<GeometryFrame.Draw>();boolean unchanged=true;
    for(var draw:complete.draws()) {
      var view=views.get(draw.node());if(view==null){output.add(draw);continue;}
      var selected=first?view.firstPerson():view.thirdPerson();if(selected.isEmpty()){unchanged=false;continue;}
      var primitives=new ArrayList<MeshAsset.Primitive>();boolean same=selected.size()==draw.geometry().primitives().size();
      for(var part:selected) {
        same&=part.sourcePrimitive()==primitives.size();
        var source=draw.geometry().primitives().get(part.sourcePrimitive());var mask=part.geometry();
        if(source.indices()==mask.indices()&&source.topology()==mask.topology())primitives.add(source);
        else{same=false;primitives.add(new MeshAsset.Primitive(mask.topology(),source.attributes(),mask.indices(),source.material(),source.skinning(),source.morphs()));}
      }
      if(same)output.add(draw);else{unchanged=false;output.add(new GeometryFrame.Draw(draw.node(),draw.mesh(),draw.world(),new MeshAsset(draw.geometry().name(),primitives,FloatData.EMPTY),draw.visible(),new IntData(selected.stream().mapToInt(p->draw.sourcePrimitive(p.sourcePrimitive())).toArray())));}
    }
    return unchanged?complete:new GeometryFrame(complete.seconds(),complete.coordinates(),output);
  }
}
