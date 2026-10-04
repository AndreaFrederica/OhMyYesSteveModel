// Minecraft's 16x16 map already includes time, weather, dimension, gamma and vision effects.
uniform bool HasHostLightmap;
uniform sampler2D HostLightmap;
uniform ivec2 HostLightCoordinates;

vec3 hostLightNumeric() {
    return HasHostLightmap ? texelFetch(HostLightmap, HostLightCoordinates, 0).rgb : vec3(1.0);
}

vec3 hostLightLinear() {
    vec3 value = hostLightNumeric();
    return mix(value / 12.92, pow((value + 0.055) / 1.055, vec3(2.4)),
               step(vec3(0.04045), value));
}
