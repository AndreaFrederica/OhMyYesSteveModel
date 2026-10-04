#version 150

in vec3 Position;
in vec3 Normal;
in vec2 Uv;
in float EdgeScale;
#ifdef ADDITIONAL_UV
in vec4 AdditionalUv;
#endif

uniform mat4 ModelView;
uniform mat4 Projection;
uniform mat3 NormalMatrix;
uniform int VertexColor;
uniform float PointSize;
uniform int EdgePass;
uniform float EdgeSize;
uniform vec2 Viewport;

out vec3 viewPosition;
out vec3 viewNormal;
out vec2 uv;
out vec4 uv1;

void main() {
    vec4 position = ModelView * vec4(Position, 1.0);
    gl_Position = Projection * position;
    gl_PointSize = PointSize;
    viewPosition = position.xyz;
    viewNormal = NormalMatrix * Normal;
    if (EdgePass != 0) {
        // Pixel-width extrusion, with the per-vertex PMX/PMD edge factor retained after deformation.
        // Convert the direction to screen pixels before normalizing. GUI projection can flip Y;
        // expanding with an unprojected view normal moves those edges into the visible surface.
        vec2 edgeNormal = (Projection * vec4(viewNormal, 0.0)).xy * Viewport;
        float squared = dot(edgeNormal, edgeNormal);
        if (squared > 0.0) gl_Position.xy += edgeNormal * inversesqrt(squared)
                * (2.0 / Viewport) * EdgeSize * EdgeScale * gl_Position.w;
    }
    uv = Uv;
#ifdef ADDITIONAL_UV
    uv1 = AdditionalUv;
#else
    uv1 = vec4(0.0);
#endif
}
