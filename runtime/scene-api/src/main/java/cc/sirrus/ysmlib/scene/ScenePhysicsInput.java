package cc.sirrus.ysmlib.scene;

import java.util.List;
import java.util.Objects;

/** Host environment in metres/seconds. Origin stays double precision; boxes are relative to it.
 * sourceToWorld includes placement, units, handedness and body orientation, never camera transforms. */
public record ScenePhysicsInput(double seconds,double originX,double originY,double originZ,
    Matrix4 sourceToWorld,Vec3 gravity,List<Box> solids,List<Fluid> fluids,Settings settings) {
  public record Settings(boolean worldCollision,double inertia,double fluidDrag,double buoyancy,double teleportDistance) {
    public Settings {
      for(double v:new double[]{inertia,fluidDrag,buoyancy,teleportDistance})
        if(!Double.isFinite(v)||v<0||v>100)throw new IllegalArgumentException("Invalid host physics settings");
      if(teleportDistance<=0)throw new IllegalArgumentException("Invalid teleport threshold");
    }
    public static Settings defaults(){return new Settings(true,1,4,.85,4);}
  }
  public record Box(Vec3 min,Vec3 max) {
    public Box {Objects.requireNonNull(min);Objects.requireNonNull(max);
      if(min.x()>=max.x()||min.y()>=max.y()||min.z()>=max.z())throw new IllegalArgumentException("Empty environment box");}
    public boolean contains(Vec3 p){return p.x()>=min.x()&&p.x()<=max.x()&&p.y()>=min.y()&&p.y()<=max.y()&&p.z()>=min.z()&&p.z()<=max.z();}
    public Vec3 corner(int i){return new Vec3((i&1)==0?min.x():max.x(),(i&2)==0?min.y():max.y(),(i&4)==0?min.z():max.z());}
  }
  /** flow is the host's fluid velocity, in metres/s; density scales the configurable visual buoyancy. */
  public record Fluid(Box volume,Vec3 flow,float density) {
    public Fluid {Objects.requireNonNull(volume);Objects.requireNonNull(flow);
      if(!Float.isFinite(density)||density<0||density>10)throw new IllegalArgumentException("Invalid fluid density");}
  }
  public ScenePhysicsInput {
    for(double v:new double[]{seconds,originX,originY,originZ})if(!Double.isFinite(v))throw new IllegalArgumentException("Non-finite host origin/time");
    Objects.requireNonNull(sourceToWorld);Objects.requireNonNull(gravity);Objects.requireNonNull(settings);
    if(!sourceToWorld.isAffine())throw new IllegalArgumentException("Host physics requires affine placement");
    sourceToWorld.inverse();solids=List.copyOf(solids);fluids=List.copyOf(fluids);
    if(solids.size()>4096||fluids.size()>4096)throw new IllegalArgumentException("Host environment budget exceeded");
  }
}
