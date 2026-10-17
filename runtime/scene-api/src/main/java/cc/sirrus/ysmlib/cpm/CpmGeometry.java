package cc.sirrus.ysmlib.cpm;

import java.util.*;

/** CPM's Bedrock geometry source, in authored pixels/degrees. This is not baked YSM ModelData. */
public record CpmGeometry(String formatVersion,List<Model> models,CpmValue.ObjectValue source) {
    public CpmGeometry { models=List.copyOf(models);Objects.requireNonNull(source); }
    public record Vector(double x,double y,double z) { public static final Vector ZERO=new Vector(0,0,0); }
    public record Model(String identifier,int textureWidth,int textureHeight,List<Bone> bones,CpmValue.ObjectValue source) {
        public Model { bones=List.copyOf(bones);Objects.requireNonNull(source); }
    }
    /** An absent parent is empty; source order and absolute pivots are preserved. */
    public record Bone(String name,String parent,Vector pivot,Vector rotation,List<Cube> cubes,CpmValue.ObjectValue source) {
        public Bone { cubes=List.copyOf(cubes);Objects.requireNonNull(source); }
    }
    /** UV is retained as source data, including per-face layouts not consumed by the original CPM reader. */
    public record Cube(Vector origin,Vector size,Vector pivot,Vector rotation,CpmValue uv,
                       double inflate,boolean mirror,CpmValue.ObjectValue source) {}
}
