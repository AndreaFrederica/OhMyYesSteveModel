#version 150
// Variant declarations and UV forwarding are supplied by GltfSurfaceProgram.
/*DEFINES*/
in vec3 Position;
uniform mat4 ModelView;
uniform mat4 Projection;
out vec3 ViewPosition;
#ifdef HAS_NORMAL
in vec3 Normal;
uniform mat3 NormalMatrix;
out vec3 ViewNormal;
#endif
#ifdef HAS_TANGENT
in vec4 Tangent;
uniform float TransformSign;
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
    ViewPosition = position.xyz;
    gl_Position = Projection * position;
    gl_PointSize = 1.0;
#ifdef HAS_NORMAL
    ViewNormal = normalize(NormalMatrix * Normal);
#endif
#ifdef HAS_TANGENT
    ViewTangent = normalize(mat3(ModelView) * Tangent.xyz);
    ViewBitangent = normalize(cross(ViewNormal, ViewTangent)) * Tangent.w * TransformSign;
#endif
#ifdef HAS_COLOR
    VertexTint = VertexColor;
#endif
/*UV_FORWARD*/
}
