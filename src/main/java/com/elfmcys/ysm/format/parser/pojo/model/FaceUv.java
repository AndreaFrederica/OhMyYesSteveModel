package com.elfmcys.ysm.format.parser.pojo.model;

import com.google.gson.annotations.SerializedName;

public class FaceUv {
    public float[] uv;

    @SerializedName("uv_size")
    public float[] uvSize;

    /** Internal adapter flag: reverse the GeoBuilder UV winding for this face. */
    @com.google.gson.annotations.Expose(serialize = false, deserialize = false)
    public boolean flip;
/*
// 暂未实现
    @SerializedName("uv_rotation")
    public Integer uvRotation;

 */
}
