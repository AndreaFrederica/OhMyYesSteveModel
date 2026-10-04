package com.elfmcys.ysm.api.rendering.v0;

import cc.sirrus.ysmlib.scene.FloatData;
import cc.sirrus.ysmlib.scene.Matrix4;
import cc.sirrus.ysmlib.scene.Vec3;
import java.util.Objects;

/** Immutable camera-space draw inputs. Source lights/cameras remain in the separately evaluated scene frame. */
public record SceneView(Matrix4 modelView, Matrix4 projection, Vec3 toLight,
                        Vec3 lightRadiance, Vec3 ambientIrradiance, FloatData tint, boolean orthographic,
                        SceneLightmap lightmap) {
    public SceneView(Matrix4 modelView, Matrix4 projection, Vec3 toLight,
                     Vec3 lightRadiance, Vec3 ambientIrradiance, FloatData tint, boolean orthographic) {
        this(modelView, projection, toLight, lightRadiance, ambientIrradiance, tint, orthographic, SceneLightmap.NONE);
    }
    public SceneView {
        Objects.requireNonNull(modelView);Objects.requireNonNull(projection);Objects.requireNonNull(toLight);
        Objects.requireNonNull(lightRadiance);Objects.requireNonNull(ambientIrradiance);Objects.requireNonNull(tint);
        Objects.requireNonNull(lightmap);
        if(!modelView.isAffine() || tint.size()!=4) throw new IllegalArgumentException("Invalid scene view");
    }
}
