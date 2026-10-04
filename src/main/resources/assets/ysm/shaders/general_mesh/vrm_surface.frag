#version 150
/*HOST_LIGHTMAP*/
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
uniform vec3 ShadeColor;
uniform vec3 Emission;
uniform vec3 RimColor;
uniform float ShadeShift;
uniform float ShadeToony;
uniform float RimPower;
uniform int AlphaMode;
uniform float AlphaCutoff;
uniform int Unlit;
uniform int Orthographic;
uniform vec4 HostTint;
uniform vec3 ToLight;
uniform vec3 LightRadiance;
uniform vec3 Ambient;
uniform float Time;
uniform float AnimationScrollX;
uniform float AnimationScrollY;
uniform float AnimationRotation;
uniform int OutlinePass;
uniform int OutlineMode;
uniform float OutlineWidth;
uniform vec3 OutlineColor;
uniform float OutlineLightingMix;
uniform vec3 MatcapColor;
out vec4 Color;
#ifdef BASE_MAP
uniform sampler2D BaseTexture;
uniform mat3 BaseUvTransform;
#endif
#ifdef NORMAL_MAP
uniform sampler2D NormalTexture;
uniform mat3 NormalUvTransform;
#endif
#ifdef EMISSIVE_MAP
uniform sampler2D EmissiveTexture;
uniform mat3 EmissiveUvTransform;
#endif
#ifdef SHADE_MAP
uniform sampler2D ShadeTexture;
uniform mat3 ShadeUvTransform;
#endif
#ifdef SHIFT_MAP
uniform sampler2D ShiftTexture;
uniform mat3 ShiftUvTransform;
#endif
#ifdef MATCAP_MAP
uniform sampler2D MatcapTexture;
uniform mat3 MatcapUvTransform;
#endif
#ifdef RIM_MAP
uniform sampler2D RimTexture;
uniform mat3 RimUvTransform;
#endif
#ifdef UVMASK_MAP
uniform sampler2D UvMaskTexture;
uniform mat3 UvMaskUvTransform;
#endif
#ifdef OUTLINE_WIDTH_MAP
uniform sampler2D OutlineWidthTexture;
uniform mat3 OutlineWidthUvTransform;
#endif
#ifdef RECEIVE_SHADOW_MAP
uniform sampler2D ReceiveShadowTexture;
uniform mat3 ReceiveShadowUvTransform;
#endif
#ifdef SHADING_GRADE_MAP
uniform sampler2D ShadingGradeTexture;
uniform mat3 ShadingGradeUvTransform;
#endif

float animationMask() {
#ifdef UVMASK_MAP
    vec2 uv = (UvMaskUvTransform * vec3(UVMASK_UV, 1.0)).xy;
    vec4 value = texture(UvMaskTexture, uv);
#ifdef UVMASK_BLUE_CHANNEL
    return value.b;
#else
    return value.r;
#endif
#else
    return 1.0;
#endif
}
vec2 animationUv(vec2 uv, int mode) {
    if (mode == 0) return uv;
    float time = Time * animationMask();
    float c;
    float s;
    float dx;
    float dy;
    if (mode == 1) {
        float a = AnimationRotation * time;
        c = cos(a); s = sin(a); dx = uv.x - .5; dy = uv.y - .5;
        return vec2(c * dx - s * dy + .5 + AnimationScrollX * time,
                    s * dx + c * dy + .5 + AnimationScrollY * time);
    }
    float x = uv.x + AnimationScrollX * time;
    float y = uv.y - AnimationScrollY * time;
    float a = -6.28318530718 * AnimationRotation * time;
    c = cos(a); s = sin(a); dx = x - .5; dy = y - .5;
    return vec2(c * dx - s * dy + .5, s * dx + c * dy + .5);
}
void main() {
    vec4 base = BaseColor;
#ifdef BASE_MAP
    base *= texture(BaseTexture, BASE_UV_VALUE);
#endif
#ifdef HAS_COLOR
    base *= VertexTint;
#endif
    float opacity = base.a;
    if (AlphaMode == 0) opacity = 1.0;
    else if (AlphaMode == 1) { if (opacity < AlphaCutoff) discard; opacity = 1.0; }
#ifdef OUTLINE_WIDTH_MAP
    float outlineWidthFactor = texture(OutlineWidthTexture, OUTLINE_WIDTH_UV_VALUE).r;
#else
    float outlineWidthFactor = 1.0;
#endif
    vec3 emission = Emission;
#ifdef EMISSIVE_MAP
    emission *= texture(EmissiveTexture, EMISSIVE_UV_VALUE).rgb;
#endif
    if (OutlinePass == 1) {
        vec3 outline = mix(OutlineColor, OutlineColor * (Ambient + LightRadiance), clamp(OutlineLightingMix, 0.0, 1.0));
        outline *= mix(vec3(1.0), hostLightLinear(), clamp(OutlineLightingMix, 0.0, 1.0));
        Color = vec4(outline, opacity * outlineWidthFactor) * HostTint;
        return;
    }
    if (Unlit == 1) { Color = vec4(base.rgb + emission, opacity) * HostTint; return; }
#ifndef HAS_NORMAL
    Color = vec4(base.rgb * hostLightLinear() + emission, opacity) * HostTint;
#else
    vec3 n = normalize(ViewNormal);
#ifdef NORMAL_MAP
    vec3 mapped = texture(NormalTexture, NORMAL_UV_VALUE).rgb * 2.0 - 1.0;
    n = normalize(mapped.x * ViewTangent + mapped.y * ViewBitangent + mapped.z * ViewNormal);
#endif
    if (!gl_FrontFacing) n = -n;
    vec3 l = dot(ToLight, ToLight) > 0.0 ? normalize(ToLight) : vec3(0.0, 1.0, 0.0);
    float ndl = dot(n, l);
    float width = max(0.001, ShadeToony * 0.5);
    float toon = smoothstep(ShadeShift - width, ShadeShift + width, ndl);
    vec3 shaded = mix(base.rgb * ShadeColor, base.rgb, toon);
#ifdef SHADE_MAP
    shaded *= texture(ShadeTexture, SHADE_UV_VALUE).rgb;
#endif
#ifdef SHIFT_MAP
    float shift = texture(ShiftTexture, SHIFT_UV_VALUE).r;
    toon = smoothstep(ShadeShift + shift - width, ShadeShift + shift + width, ndl);
    shaded = mix(base.rgb * ShadeColor, base.rgb, toon);
#endif
    vec3 lit = shaded * (Ambient + toon * LightRadiance);
#ifdef SHADING_GRADE_MAP
    lit *= texture(ShadingGradeTexture, SHADING_GRADE_UV_VALUE).r;
#endif
#ifdef RECEIVE_SHADOW_MAP
    lit *= texture(ReceiveShadowTexture, RECEIVE_SHADOW_UV_VALUE).rgb;
#endif
    vec3 view = Orthographic == 0 && dot(ViewPosition, ViewPosition) > 0.0 ? normalize(-ViewPosition) : vec3(0.0, 0.0, 1.0);
    float rim = pow(max(0.0, 1.0 - max(dot(n, view), 0.0)), max(0.001, RimPower));
#ifdef RIM_MAP
    rim *= texture(RimTexture, RIM_UV_VALUE).r;
#endif
#ifdef MATCAP_MAP
    vec2 matcapUv = n.xy * .5 + .5;
    vec3 matcap = texture(MatcapTexture, (MatcapUvTransform * vec3(matcapUv, 1.0)).xy).rgb;
    lit += matcap * MatcapColor;
#endif
    Color = vec4((lit + RimColor * rim) * hostLightLinear() + emission, opacity) * HostTint;
#endif
}
