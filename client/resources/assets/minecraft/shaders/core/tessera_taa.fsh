#version 330
#extension GL_ARB_separate_shader_objects : require
#extension GL_ARB_texture_gather : require

// Stage 4 (Tessera temporal anti-aliasing): the jittered frame (InSampler) blended into the reprojected history
// (HistSampler, bilinear, RGBA8). Reprojection by camera motion only: each pixel's distance from the half-res world image
// (DistSampler a, sky = 65000) puts it in the world, the previous matrix finds it in the last frame. The history is
// clamped to this frame's plus-shaped neighbourhood (RGB box), so moving things and disocclusions do not ghost.
#define FogColor vec4(0.0)
#include <minecraft:tessera.glsl>

uniform sampler2D InSampler;
uniform sampler2D HistSampler;
uniform sampler2D DistSampler;
#ifdef TESSERA_AO
uniform sampler2D AoSampler;   // the half-res AO (r = visibility): multiplied in here so the history filters it too
#endif

layout(location = 0) out vec4 fragColor;

void main() {
    ivec2 pix = ivec2(gl_FragCoord.xy);
    ivec2 size = textureSize(InSampler, 0);
    vec3 c = texelFetch(InSampler, pix, 0).rgb;
#ifdef TESSERA_AO
    // the AO at this pixel (4 bilinear taps, like the composite's), applied to the frame and its neighbourhood: computed
    // from the jittered depth it changes every frame on fine geometry (leaves), so it belongs in the temporal blend
    vec2 aoUv = gl_FragCoord.xy / vec2(size);
    vec2 ta = 1.0 / vec2(textureSize(AoSampler, 0));
    float ao = (texture(AoSampler, aoUv + ta * vec2(-0.75, -0.75)).r + texture(AoSampler, aoUv + ta * vec2(0.75, -0.75)).r
        + texture(AoSampler, aoUv + ta * vec2(-0.75, 0.75)).r + texture(AoSampler, aoUv + ta * vec2(0.75, 0.75)).r) * 0.25;
    c *= ao;
#else
    float ao = 1.0;
#endif
#ifdef TESSERA
    if (TesseraPrevCamera.w == 0.0) {
        fragColor = vec4(c, 1.0);
        return;
    }
    // the plus-shaped neighbourhood (4 fetches): the history's clamp box and the sharpening blur
    ivec2 hi2 = size - 1;
    vec3 n0 = texelFetch(InSampler, clamp(pix + ivec2(-1, 0), ivec2(0), hi2), 0).rgb;
    vec3 n1 = texelFetch(InSampler, clamp(pix + ivec2(1, 0), ivec2(0), hi2), 0).rgb;
    vec3 n2 = texelFetch(InSampler, clamp(pix + ivec2(0, -1), ivec2(0), hi2), 0).rgb;
    vec3 n3 = texelFetch(InSampler, clamp(pix + ivec2(0, 1), ivec2(0), hi2), 0).rgb;
    n0 *= ao; n1 *= ao; n2 *= ao; n3 *= ao;
    vec3 lo = min(c, min(min(n0, n1), min(n2, n3)));
    vec3 hi = max(c, max(max(n0, n1), max(n2, n3)));
    // the pixel's world point (camera-relative), then where it was last frame
    vec2 ndc = vec2(gl_FragCoord.x / float(size.x) * 2.0 - 1.0, TesseraSunColor.w * (gl_FragCoord.y / float(size.y) * 2.0 - 1.0));
    vec4 q = TesseraInvViewProj * vec4(ndc, 0.5, 1.0);
    vec4 q2 = TesseraInvViewProj * vec4(ndc, 0.25, 1.0);
    vec3 qa = q.xyz / q.w;
    vec3 qb = q2.xyz / q2.w;
    vec3 dir = normalize(qb - qa);   // two points on the ray: the view bob moves the eye off the origin
    vec3 eye = 2.0 * qa - qb;
    float dist = texelFetch(DistSampler, min(pix >> 1, textureSize(DistSampler, 0) - 1), 0).a;
    vec4 pc = dist >= 60000.0 ? TesseraPrevViewProj * vec4(dir, 0.0) : TesseraPrevViewProj * vec4(eye + dir * dist + TesseraPrevCamera.xyz, 1.0);
    vec2 prev = vec2(pc.x / pc.w + 1.0, TesseraSunColor.w * pc.y / pc.w + 1.0) * 0.5;
    if (pc.w <= 0.0 || any(lessThan(prev, vec2(0.0))) || any(greaterThan(prev, vec2(1.0)))) {
        fragColor = vec4(c, 1.0);
        return;
    }
    vec3 h = clamp(texture(HistSampler, prev).rgb, lo, hi);
    // faster turnover while moving (the bilinear history softens with every reprojected step)
    float moved = length((prev - gl_FragCoord.xy / vec2(size)) * vec2(size));
    float w = mix(TesseraProbe.z == 8.0 ? 0.05 : 0.1, 0.25, clamp(moved * 0.25, 0.0, 1.0));   // probe 8: slower when still
    vec3 r = mix(h, c, w);
    // (the sharpen against the history's softness is applied on display, tessera_bloom_up TESSERA_TAA_BASE: in the
    // history it fed this frame's jittered neighbours back in every frame, a shimmer on fine textures)
    // half a step of noise: 8-bit rounding cannot hold a pixel short of its target (blend weight x difference < 1/255)
    r += (fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715)))) - 0.5) / 255.0;
    fragColor = vec4(r, 1.0);
#else
    fragColor = vec4(c, 1.0);
#endif
}
