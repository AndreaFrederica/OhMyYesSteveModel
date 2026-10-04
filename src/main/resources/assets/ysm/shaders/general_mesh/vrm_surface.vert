#version 150
/*DEFINES*/
in vec3 Position;
uniform mat4 ModelView;
uniform mat4 Projection;
uniform mat3 NormalMatrix;
uniform int OutlinePass;
uniform int OutlineMode;
uniform float OutlineWidth;
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
    vec4 position = ModelView * vec4(Position, 1.0);
#ifdef HAS_NORMAL
    ViewNormal = normalize(NormalMatrix * Normal);
    if (OutlinePass == 1 && OutlineMode == 1) position.xyz += ViewNormal * OutlineWidth;
#endif
    ViewPosition = position.xyz;
    gl_Position = Projection * position;
    if (OutlinePass == 1 && OutlineMode == 2) {
#ifdef HAS_NORMAL
        vec4 clipNormal = Projection * vec4(ViewNormal, 0.0);
        vec2 direction = length(clipNormal.xy) > 0.00001 ? normalize(clipNormal.xy) : vec2(0.0);
        gl_Position.xy += direction * OutlineWidth * gl_Position.w;
#endif
    }
    gl_PointSize = 1.0;
#ifdef HAS_TANGENT
    ViewTangent = normalize(mat3(ModelView) * Tangent.xyz);
    ViewBitangent = normalize(cross(ViewNormal, ViewTangent)) * Tangent.w;
#endif
#ifdef HAS_COLOR
    VertexTint = VertexColor;
#endif
/*UV_FORWARD*/
}
