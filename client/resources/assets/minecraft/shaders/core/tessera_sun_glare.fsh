#version 330
#extension GL_ARB_separate_shader_objects : require
#extension GL_ARB_texture_gather : require

// Stage 4 (Tessera sun glare): an overexposed square sun. The frame is 8-bit, so this works
// in a virtual HDR: the sun core (tesseraSunCore, far above white) where the pixel is open sky, plus its wide bloom
// (tesseraSunBloom, analytic: the core blurred at seven widths) scaled by how much of the sun is visible (SunVisSampler, the
// 1x1 tessera_sun_vis pass; InSampler = the half-res reflection source, a = distance, sky = 65000). Drawn as a full-screen
// triangle (no vertex-stage reads) cut to the sun's screen box by a scissor (Tessera#sunGlareBox). The
// frame pixel is linearised, the HDR light added and both tonemapped; the difference is added (additive blend), so
// pixels without sun light keep their colour exactly.
#define FogColor vec4(0.0)   // no Fog block bound (the glare never uses the fog colour)
#include <minecraft:tessera.glsl>

uniform sampler2D InSampler;
uniform sampler2D SunVisSampler;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

void main() {
#ifndef TESSERA
    fragColor = vec4(0.0);
#else
    if (TesseraSunDisc.w <= 0.0) {
        fragColor = vec4(0.0);
        return;
    }
    float vis = texelFetch(SunVisSampler, ivec2(0), 0).r;
    vec2 pndc = vec2(2.0 * gl_FragCoord.x / TesseraSizes.x - 1.0, TesseraSunColor.w * (2.0 * gl_FragCoord.y / TesseraSizes.y - 1.0));
    vec4 wp = TesseraInvViewProj * vec4(pndc, 0.5, 1.0);
    vec4 wq = TesseraInvViewProj * vec4(pndc, 0.25, 1.0);
    vec3 d = normalize(wq.xyz / wq.w - wp.xyz / wp.w);   // two points on the ray: the view bob moves the eye off the origin
    vec2 q = tesseraSunPlane(d);
    // this pixel: the frame (half-res copy) and whether it is open sky (the core only shows there)
    ivec2 t = ivec2(gl_FragCoord.xy * 0.5);
    vec4 here = texelFetch(InSampler, min(t, textureSize(InSampler, 0) - 1), 0);
    // the bloom, faded out towards the edge of this pass's box round the sun (Tessera#sunGlareBox); the sky's own wide
    // glare is added by the sky and the clouds themselves (tesseraSkyGlareOver)
    vec3 hdr = q.x < 1.0e2 ? tesseraSunBloom(q) * (vis * (1.0 - smoothstep(0.55, 0.8, length(q)))) : vec3(0.0);
    float open = tesseraSkyOpen(d, here.a);
    if (open > 0.0) {
        // through haze or faint cloud the core dims and reddens (blue and green go first)
        hdr += tesseraSunCore(q) * (0.88 * open * vec3(1.0, open, open * open));
    }
    // little light here: skip the two tonemaps
    if (max(hdr.r, max(hdr.g, hdr.b)) < 2.0e-4) {
        fragColor = vec4(0.0);
        return;
    }
    vec3 base = tesseraLinear(here.rgb);
    vec3 delta = tesseraTonemap(base + hdr) - tesseraTonemap(base);
    fragColor = vec4(max(delta, 0.0) * TesseraSunDisc.w, 1.0);
#endif
}
