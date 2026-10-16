#version 330
#extension GL_ARB_separate_shader_objects : require
#extension GL_ARB_texture_gather : require

// Stage 4 (Tessera sun visibility): one pixel, r = how much of the sun is open sky, for the sun glare. 13 taps of the
// half-res frame's world distance round the sun (InSampler a = distance, sky = 65000), with the previous frame's matrix
// (close enough for a glare). Its own 1x1 pass, so the glare's vertex stage reads nothing (a vertex stage that reads
// stalls its whole pass: ~1.4 ms measured).
#define FogColor vec4(0.0)
#include <minecraft:tessera.glsl>

uniform sampler2D InSampler;

layout(location = 0) out vec4 fragColor;

void main() {
    float vis = 0.0;
#ifdef TESSERA
    vec4 c = TesseraPrevViewProj * vec4(TesseraSunDisc.xyz * 1000.0, 1.0);
    if (c.w > 0.0 && TesseraSunDisc.w > 0.0) {
        vec2 ndc = c.xy / c.w;
        vec2 frag = vec2(ndc.x + 1.0, TesseraSunColor.w * ndc.y + 1.0) * 0.5 * TesseraSizes.xy;
        ivec2 size = textureSize(InSampler, 0);
        float r = TesseraSizes.y * 0.012;
        for (int i = 0; i < 13; i++) {
            float a = float(i) * 2.3999632;
            vec2 o = i == 0 ? vec2(0.0) : vec2(cos(a), sin(a)) * r * sqrt(float(i) / 12.0);
            ivec2 t = ivec2((frag + o) * 0.5);
            if (t.x >= 0 && t.y >= 0 && t.x < size.x && t.y < size.y) {
                vis += tesseraSkyOpen(TesseraSunDisc.xyz, texelFetch(InSampler, t, 0).a);
            }
        }
        vis /= 13.0;
    }
#endif
    fragColor = vec4(vis, 0.0, 0.0, 1.0);
}
