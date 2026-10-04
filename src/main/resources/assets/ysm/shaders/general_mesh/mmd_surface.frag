#version 150
/*HOST_LIGHTMAP*/

in vec3 viewPosition;
in vec3 viewNormal;
in vec2 uv;
in vec4 uv1;
out vec4 Color;

uniform vec4 Diffuse;
uniform vec3 Ambient;
uniform vec3 Specular;
uniform float Shininess;
uniform vec3 LightColor;
uniform vec3 ToLight;
uniform vec4 HostTint;
uniform int VertexColor;
uniform int HasDiffuse;
uniform int SphereMode;
uniform int HasToon;
uniform sampler2D DiffuseTexture;
uniform sampler2D SphereTexture;
uniform sampler2D ToonTexture;
uniform vec4 TextureMultiply;
uniform vec4 TextureAdd;
uniform vec4 SphereMultiply;
uniform vec4 SphereAdd;
uniform vec4 ToonMultiply;
uniform vec4 ToonAdd;
uniform int EdgePass;
uniform vec4 EdgeColor;
uniform bool Orthographic;

// MMD material morphs blend texture RGB with white; the factor's alpha is not opacity.
// Formula checked against the fixed Saba and babylon-mmd implementations.
vec3 modulate(vec3 sampleColor, vec4 multiply, vec4 add) {
    vec3 value = mix(vec3(1.0), sampleColor * multiply.rgb, multiply.a);
    return clamp(value + (value - vec3(1.0)) * add.a, 0.0, 1.0) + add.rgb;
}

vec3 direction(vec3 value) {
    float squared = dot(value, value);
    return squared > 0.0 ? value * inversesqrt(squared) : vec3(0.0);
}

void main() {
    if (EdgePass != 0) {
        Color = EdgeColor * HostTint;
        Color.rgb *= hostLightNumeric();
        if (Color.a <= 0.0) discard;
        return;
    }
    vec3 normal = direction(viewNormal);
    vec3 light = direction(ToLight);
    vec3 color = clamp(Diffuse.rgb * LightColor + Ambient, 0.0, 1.0);
    float alpha = clamp(Diffuse.a, 0.0, 1.0);
    if (VertexColor != 0) {
        color *= uv1.rgb;
        alpha *= uv1.a;
    }
    if (HasDiffuse != 0) {
        vec4 texel = texture(DiffuseTexture, uv);
        color *= modulate(texel.rgb, TextureMultiply, TextureAdd);
        alpha *= texel.a;
    }
    if (SphereMode != 0) {
        // Source images and authored UVs both use the top-left convention.
        vec2 sphereUv = SphereMode == 3 ? uv1.xy : normal.xy * 0.5 + 0.5;
        vec4 texel = texture(SphereTexture, sphereUv);
        vec3 value = modulate(texel.rgb, SphereMultiply, SphereAdd);
        if (SphereMode == 2) color += value;
        else { color *= value; alpha *= texel.a; }
    }
    if (HasToon != 0) {
        float coordinate = clamp(0.5 - dot(normal, light), 0.0, 1.0);
        color *= modulate(texture(ToonTexture, vec2(0.0, coordinate)).rgb, ToonMultiply, ToonAdd);
    }
    if (Shininess > 0.0) {
        vec3 halfway = direction((Orthographic ? vec3(0.0,0.0,1.0) : direction(-viewPosition)) + light);
        color += Specular * LightColor * pow(max(0.0, dot(normal, halfway)), Shininess);
    }
    Color = vec4(color * hostLightNumeric(), alpha) * HostTint;
    if (Color.a <= 0.0) discard;
}
