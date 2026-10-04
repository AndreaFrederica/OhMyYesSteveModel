#version 150
/*DEFINES*/
in vec3 ViewPosition;
#ifdef HAS_NORMAL
in vec3 ViewNormal;
#endif
#ifdef HAS_TANGENT
in vec3 ViewTangent;
in vec3 ViewBitangent;
#endif
#ifdef HAS_COLOR
in vec4 VertexTint;
#endif
/*UV_DECLARATIONS*/
uniform vec4 BaseColor;
uniform float Metallic;
uniform float Roughness;
uniform vec3 Emissive;
uniform int AlphaMode;
uniform float AlphaCutoff;
uniform bool Unlit;
uniform vec4 HostTint;
// The host supplies view-space illumination; source scene lights remain separate extension data.
uniform vec3 ToLight;
uniform vec3 LightRadiance;
uniform vec3 DiffuseIrradiance;
out vec4 Color;
#ifdef BASE_MAP
uniform sampler2D BaseTexture;
uniform mat3 BaseUvTransform;
#endif
#ifdef MR_MAP
uniform sampler2D MrTexture;
uniform mat3 MrUvTransform;
#endif
#ifdef EMISSIVE_MAP
uniform sampler2D EmissiveTexture;
uniform mat3 EmissiveUvTransform;
#endif
#ifdef NORMAL_MAP
uniform sampler2D NormalTexture;
uniform mat3 NormalUvTransform;
uniform float NormalScale;
#endif
#ifdef OCCLUSION_MAP
uniform sampler2D OcclusionTexture;
uniform mat3 OcclusionUvTransform;
uniform float OcclusionStrength;
#endif
const float PI = 3.141592653589793;
uniform bool Orthographic;
vec3 linearBrdf(vec3 base, float metallic, float roughness, vec3 n, vec3 v, vec3 l) {
    float nl = max(dot(n,l),0.0), nv = max(dot(n,v),0.0);
    if (nl <= 0.0 || nv <= 0.0) return vec3(0.0);
    vec3 halfway = v+l;
    if (dot(halfway,halfway)==0.0) return vec3(0.0);
    vec3 h = normalize(halfway);
    float nh = max(dot(n,h),0.0), vh = max(dot(v,h),0.0);
    float alpha = roughness*roughness;
    // Regularize only the floating-point singularity of an ideal delta reflection.
    float a2 = max(alpha*alpha,1e-12);
    float denominator = (1.0-nh)*(1.0+nh)+nh*nh*a2;
    float distribution = a2/(PI*denominator*denominator);
    float visibility = 0.5/(nl*sqrt(nv*nv*(1.0-a2)+a2)+nv*sqrt(nl*nl*(1.0-a2)+a2));
    float schlick = pow(clamp(1.0-vh,0.0,1.0),5.0);
    float dielectricFresnel = 0.04+0.96*schlick;
    vec3 metalFresnel = base+(vec3(1.0)-base)*schlick;
    float specular = visibility*distribution;
    vec3 dielectric = (1.0-dielectricFresnel)*base/PI+dielectricFresnel*specular;
    return nl*mix(dielectric,metalFresnel*specular,metallic);
}
void main() {
    vec4 base = BaseColor;
#ifdef BASE_MAP
    base *= texture(BaseTexture,(BaseUvTransform*vec3(BASE_UV,1.0)).xy);
#endif
#ifdef HAS_COLOR
    base *= VertexTint;
#endif
    float opacity = base.a;
    if (AlphaMode == 0) opacity = 1.0;
    else if (AlphaMode == 1) { if (opacity < AlphaCutoff) discard; opacity = 1.0; }
    if (Unlit) { Color = vec4(base.rgb,opacity)*HostTint; return; }
    vec3 emission = Emissive;
#ifdef EMISSIVE_MAP
    emission *= texture(EmissiveTexture,(EmissiveUvTransform*vec3(EMISSIVE_UV,1.0)).xy).rgb;
#endif
#ifndef HAS_NORMAL
    Color = vec4(base.rgb+emission,opacity)*HostTint;
#else
    vec3 n = normalize(ViewNormal);
#ifdef NORMAL_MAP
    vec3 mapped = texture(NormalTexture,(NormalUvTransform*vec3(NORMAL_UV,1.0)).xy).rgb*2.0-1.0;
    mapped.xy *= NormalScale;
    n = normalize(mapped.x*ViewTangent+mapped.y*ViewBitangent+mapped.z*ViewNormal);
#endif
    if (!gl_FrontFacing) n = -n;
    float metallic = Metallic, roughness = Roughness;
#ifdef MR_MAP
    vec4 mr = texture(MrTexture,(MrUvTransform*vec3(MR_UV,1.0)).xy);
    metallic *= mr.b; roughness *= mr.g;
#endif
    roughness = clamp(roughness,0.0,1.0); metallic = clamp(metallic,0.0,1.0);
    float occlusion = 1.0;
#ifdef OCCLUSION_MAP
    occlusion = mix(1.0,texture(OcclusionTexture,(OcclusionUvTransform*vec3(OCCLUSION_UV,1.0)).xy).r,OcclusionStrength);
#endif
    vec3 v = !Orthographic && dot(ViewPosition,ViewPosition)>0.0 ? normalize(-ViewPosition) : vec3(0.0,0.0,1.0);
    vec3 lit = vec3(0.0);
    if (dot(ToLight,ToLight)>0.0) lit = linearBrdf(base.rgb,metallic,roughness,n,v,normalize(ToLight))*LightRadiance;
    vec3 ambient = base.rgb*(1.0-0.04)*(1.0-metallic)*DiffuseIrradiance/PI;
    Color = vec4(lit+ambient*occlusion+emission,opacity)*HostTint;
#endif
}
