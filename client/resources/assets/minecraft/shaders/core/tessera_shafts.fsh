#version 330
#extension GL_ARB_separate_shader_objects : require
#extension GL_ARB_texture_gather : require

// Stage 4 (Tessera god rays): for each quarter-resolution pixel, the share of its light shafts' air (up to the surface the
// reflection source saw there, or the full range for the sky) that the shadow map shadows: r, 0 = all lit. The next
// frame's terrain adds only the lit share of its shafts and the sky takes out the shadowed share (tesseraShafts).
#include <minecraft:fog.glsl>
#include <minecraft:tessera.glsl>

uniform sampler2D DistSampler;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

void main() {
#ifndef TESSERA
    fragColor = vec4(0.0);
#else
    // quarter resolution: one reflection-source texel of each 2x2 (the nearest surface of them would also do; the
    // shafts are smooth and the terrain reads them filtered)
    ivec2 ip = ivec2(floor(gl_FragCoord.xy));
    ivec2 rp = min(ip * 2, textureSize(DistSampler, 0) - 1);
    float dist = min(texelFetch(DistSampler, rp, 0).a, TESSERA_SHAFT_RANGE);
    vec2 px = vec2(rp) * 2.0 + 1.0;
    vec2 ndc = vec2(2.0 * px.x / TesseraSizes.x - 1.0, TesseraSunColor.w * (2.0 * px.y / TesseraSizes.y - 1.0));
    vec4 wp = TesseraInvViewProj * vec4(ndc, 0.5, 1.0);
    vec4 wq = TesseraInvViewProj * vec4(ndc, 0.25, 1.0);
    vec3 d = normalize(wq.xyz / wq.w - wp.xyz / wp.w);   // two points on the ray: the view bob moves the eye off the origin
    float w = tesseraShaftWeight(dist);
    float shade = w > 1.0e-5 ? clamp(1.0 - tesseraShaftAir(d, dist, w) / w, 0.0, 1.0) : 0.0;
    fragColor = vec4(shade, 0.0, 0.0, 1.0);
#endif
}
