package cc.sirrus.ysmlib.scene.mmd;

import cc.sirrus.ysmlib.scene.*;
import java.util.*;

/** Read-only projection of a published MMD frame; neither skinning nor physics is applied twice. */
public final class MmdGeometry {
  public GeometryFrame compile(MeshAsset source,MmdPlayback.Frame frame,SceneAsset.Coordinates coordinates) {
    Objects.requireNonNull(source);Objects.requireNonNull(frame);Objects.requireNonNull(coordinates);
    if(coordinates.rightHanded() || !coordinates.upAxis().equals("Y")) throw new IllegalArgumentException("MMD geometry remains in left-handed Y-up source space");
    if(source.primitives().size()!=frame.primitives().size()) throw new IllegalArgumentException("MMD frame primitive layout differs from source");
    var primitives=new ArrayList<MeshAsset.Primitive>();
    for(int i=0;i<source.primitives().size();i++) {
      var p=source.primitives().get(i);var attributes=frame.primitives().get(i);
      if(attributes.get("POSITION").count()!=p.vertexCount()) throw new IllegalArgumentException("MMD frame vertex layout differs from source");
      primitives.add(new MeshAsset.Primitive(p.topology(),attributes,p.indices(),p.material(),null,List.of()));
    }
    var mesh=new MeshAsset(source.name(),primitives,FloatData.EMPTY);
    return new GeometryFrame(frame.seconds(),coordinates,List.of(new GeometryFrame.Draw(0,0,Matrix4.IDENTITY,mesh,frame.pose().visible())));
  }
}
