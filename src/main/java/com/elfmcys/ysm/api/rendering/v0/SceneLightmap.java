package com.elfmcys.ysm.api.rendering.v0;

/** Borrowed host lightmap for this draw only; block/sky are independent Minecraft levels (0..15). */
public record SceneLightmap(int textureId, int block, int sky) {
    /** Preview and independent render hosts supply their own illumination. */
    public static final SceneLightmap NONE = new SceneLightmap(0, 0, 0);

    public SceneLightmap {
        if (textureId < 0 || block < 0 || block > 15 || sky < 0 || sky > 15)
            throw new IllegalArgumentException("Invalid scene lightmap");
    }
    public boolean enabled() { return textureId != 0; }
}
