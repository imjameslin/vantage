#version 330
#extension GL_ARB_separate_shader_objects : require

// Stage 4 (Tessera bloom): 4 bilinear taps (a 4x4 texel box of the larger level). TESSERA_BRIGHT: the first step from
// the frame, into linear light (the whole image blooms; bright parts spill the most).
uniform sampler2D InSampler;
#ifndef TESSERA_BLOOM_HDR
#define TESSERA_BLOOM_HDR 1.0
#endif

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

vec3 tap(vec2 uv) {
    vec3 c = texture(InSampler, uv).rgb;
#ifdef TESSERA_BRIGHT
    // the frame back to the virtual HDR the tonemap compressed (inverse of its per-channel extended Reinhard curve,
    // white at 9, on the sRGB-decoded value, capped near white): bright light sources, sun-lit snow and the sky's glow
    // spill like HDR bloom
    c = clamp(c, 0.0, 1.0);
    vec3 s = mix(pow((c + 0.055) / 1.055, vec3(2.4)), c / 12.92, lessThan(c, vec3(0.04045)));
    s = min(s, vec3(0.995));
    const float W2 = 81.0;
    c = (0.5 * W2) * ((s - 1.0) + sqrt((1.0 - s) * (1.0 - s) + s * (4.0 / W2))) * TESSERA_BLOOM_HDR;
#endif
    return c;
}

void main() {
    vec2 t = 1.0 / vec2(textureSize(InSampler, 0));
    vec3 c = tap(texCoord + t * vec2(-1.0, -1.0)) + tap(texCoord + t * vec2(1.0, -1.0))
        + tap(texCoord + t * vec2(-1.0, 1.0)) + tap(texCoord + t * vec2(1.0, 1.0));
    fragColor = vec4(c * 0.25, 1.0);
}
