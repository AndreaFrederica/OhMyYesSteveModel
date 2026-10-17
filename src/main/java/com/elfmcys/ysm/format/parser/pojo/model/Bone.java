package com.elfmcys.ysm.format.parser.pojo.model;

import java.util.ArrayList;

public class Bone {
    public ArrayList<Cube> cubes;
    public boolean debug;
    public float inflate;
    public boolean mirror;
    public String name;
    public String parent;
    /** Optional explicit YSM locator name for imported multi-root groups. */
    public String locator;
    public float[] pivot;

    public float[] rotation;
}
