package com.elfmcys.ysm.format.parser.pojo.model;

public class Cube {
    /** YSM legacy custom-model quad: one textured XY plane at a fixed Z. */
    public boolean plane;
    /** CPM Bedrock part flag: retain the opposite faces of zero-thickness boxes. */
    public boolean doubleFace;
    public Float inflate;
    public boolean mirror;
    public float[] origin;
    public float[] pivot;
    public float[] rotation;
    public float[] size;
    public CubeUv uv;
}
