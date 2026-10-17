#version 150
/*DEFINES*/
in vec3 Position;
uniform mat4 ModelView;
uniform mat4 Projection;
uniform mat3 NormalMatrix;
uniform int OutlinePass;
uniform int OutlineMode;
uniform float OutlineWidth;
uniform float OutlineMaxDistance;
#ifdef OUTLINE_WIDTH_MAP
uniform sampler2D OutlineWidthTexture;
uniform mat3 OutlineWidthUvTransform;
#endif
out vec3 ViewPosition;
#ifdef HAS_NORMAL
in vec3 Normal;
out vec3 ViewNormal;
#endif
#ifdef HAS_TANGENT
in vec4 Tangent;
out vec3 ViewTangent;
out vec3 ViewBitangent;
#endif
#ifdef HAS_COLOR
in vec4 VertexColor;
out vec4 VertexTint;
#endif
/*UV_DECLARATIONS*/
void main() {
/*UV_FORWARD*/
    vec4 position = ModelView * vec4(Position, 1.0);
    ViewPosition = position.xyz;
    gl_Position = Projection * position;
#ifdef HAS_NORMAL
    vec3 objectNormal = normalize(Normal);
    vec3 transformedNormal = NormalMatrix * objectNormal;
    ViewNormal = normalize(transformedNormal);
    if (OutlinePass == 1 && OutlineMode != 0) {
        float width = OutlineWidth;
#ifdef OUTLINE_WIDTH_MAP
        // Width masks alter geometry, never alpha; the source UV is not animated.
        vec4 mask = textureLod(OutlineWidthTexture, (OutlineWidthUvTransform * vec3(OUTLINE_WIDTH_UV, 1.0)).xy, 0.0);
#ifdef OUTLINE_WIDTH_GREEN_CHANNEL
        width *= mask.g;
#else
        width *= mask.r;
#endif
#endif
#ifdef MTOON_LEGACY
        if (OutlineMode == 2) {
            vec2 projectedNormal = (Projection * vec4(transformedNormal, 0.0)).xy;
            float magnitude = length(projectedNormal);
            vec2 direction = magnitude > 0.00001 ? projectedNormal / magnitude : vec2(0.0);
            direction.x *= abs(Projection[0][0] / Projection[1][1]);
            gl_Position.xy += width * min(gl_Position.w, OutlineMaxDistance) * direction * clamp(1.0 - abs(ViewNormal.z), 0.0, 1.0);
        } else {
#endif
            vec3 offset = width * length(transformedNormal) * objectNormal;
#ifndef MTOON_LEGACY
            if (OutlineMode == 2) offset *= -position.z / Projection[1][1];
#endif
            gl_Position = Projection * (position + ModelView * vec4(offset, 0.0));
#ifdef MTOON_LEGACY
        }
#endif
        // Keep unexpanded/coplanar outline vertices behind the surface.
        gl_Position.z += 1e-6 * gl_Position.w;
    }
#endif
    gl_PointSize = 1.0;
#ifdef HAS_TANGENT
    ViewTangent = normalize(mat3(ModelView) * Tangent.xyz);
    ViewBitangent = normalize(cross(ViewNormal, ViewTangent)) * Tangent.w;
#endif
#ifdef HAS_COLOR
    VertexTint = VertexColor;
#endif
}
