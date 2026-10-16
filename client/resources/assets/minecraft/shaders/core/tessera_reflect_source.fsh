#version 330
#extension GL_ARB_separate_shader_objects : require
#extension GL_ARB_texture_gather : require

// Stage 4 (Tessera water reflections): this frame's world image at half resolution (rgb: 2x2 average) and the distance
// of its surface from the camera (a; sky = 65000), addressed like gl_FragCoord. The next frame's water traces it.
#include <minecraft:fog.glsl>
#include <minecraft:tessera.glsl>

uniform sampler2D InSampler;
uniform sampler2D DepthSampler;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;
#ifdef TESSERA_REFLECT_AO
// the AO in the same pass (a second target, half resolution like this one): tessera_ao.glsl over DepthSampler
layout(location = 1) out vec4 aoColor;
#define TESSERA_AO_DEPTH DepthSampler
#include <minecraft:tessera_ao.glsl>
#endif

void main() {
#ifndef TESSERA
    fragColor = vec4(0.0);
#else
    ivec2 base = ivec2(floor(gl_FragCoord.xy)) * 2;
    ivec2 hi = ivec2(TesseraSizes.xy) - 1;
    vec3 c = texelFetch(InSampler, min(base, hi), 0).rgb + texelFetch(InSampler, min(base + ivec2(1, 0), hi), 0).rgb
        + texelFetch(InSampler, min(base + ivec2(0, 1), hi), 0).rgb + texelFetch(InSampler, min(base + ivec2(1, 1), hi), 0).rgb;
    vec2 px = vec2(base) + 0.5;
    float d = texelFetch(DepthSampler, min(base, hi), 0).r;
    float dist = 65000.0;
    if (d > 0.0) {
        vec2 ndc = vec2(2.0 * px.x / TesseraSizes.x - 1.0, TesseraSunColor.w * (2.0 * px.y / TesseraSizes.y - 1.0));
        vec4 wp = TesseraInvViewProj * vec4(ndc, d, 1.0);
        dist = min(length(wp.xyz / wp.w), 65000.0);
    }
    fragColor = vec4(c * 0.25, dist);
#ifdef TESSERA_REFLECT_AO
    float ao = tesseraAoVisibility();
    aoColor = vec4(ao, ao, ao, 1.0);
#endif
#endif
}
