#version 330
#extension GL_ARB_separate_shader_objects : require

// Stage 4 (Tessera bloom): 3x3 tent from the smaller level, added onto the larger one (ADDITIVE blend). TESSERA_COMPOSITE:
// the last step, mixed over the frame with the vignette.
uniform sampler2D InSampler;
#ifdef TESSERA_AO
uniform sampler2D AoSampler;   // the half-res AO (r = visibility), multiplied in here too
#endif
#ifdef TESSERA_TAA_BASE
uniform sampler2D BaseSampler; // the TAA result: the frame under the blend, written whole (no blend, no copy draw)
#endif

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    vec2 t = 1.0 / vec2(textureSize(InSampler, 0));
#ifdef TESSERA_COMPOSITE
    // full resolution from the 1/4 level: 4 bilinear taps (a smooth tent); the blur is mixed over the frame at
    // strength 0.12 (our 4 summed levels x 0.98 / 4), then the vignette (darker
    // corners, less where bright) multiplied on, in one blend (ONE, ONE_MINUS_SRC_ALPHA):
    // frame' = (frame (1 - 0.12) + blur 0.12) v = blur 0.12 v + frame (1 - (1 - (1 - 0.12) v))
    vec3 c = (texture(InSampler, texCoord + t * vec2(-0.5, -0.5)).rgb + texture(InSampler, texCoord + t * vec2(0.5, -0.5)).rgb
        + texture(InSampler, texCoord + t * vec2(-0.5, 0.5)).rgb + texture(InSampler, texCoord + t * vec2(0.5, 0.5)).rgb) * (0.25 * 0.98 / 4.0);
    // the HDR blur (tessera_bloom_down's TESSERA_BRIGHT) through the tonemap's per-channel curve (tessera.glsl
    // tesseraReinhard, white at 9) and sRGB encode
    vec3 ca = max(c, 0.0);
    ca = ca * (1.0 + ca * (1.0 / 81.0)) / (1.0 + ca);
    vec3 blur = mix(1.055 * pow(ca, vec3(1.0 / 2.4)) - 0.055, 12.92 * ca, lessThan(ca, vec3(0.0031308)));
    vec2 r = texCoord - 0.5;
    float v = 1.0 - dot(r, r) * (1.0 - clamp(dot(blur, vec3(0.2126, 0.7152, 0.0722)), 0.0, 1.0));
#ifdef TESSERA_AO
    // frame' = (frame ao (1 - 0.12) + blur 0.12) v: the AO joins the frame's factor (4 bilinear taps, like tessera_ao_apply)
    vec2 ta = 1.0 / vec2(textureSize(AoSampler, 0));
    float ao = (texture(AoSampler, texCoord + ta * vec2(-0.75, -0.75)).r + texture(AoSampler, texCoord + ta * vec2(0.75, -0.75)).r
        + texture(AoSampler, texCoord + ta * vec2(-0.75, 0.75)).r + texture(AoSampler, texCoord + ta * vec2(0.75, 0.75)).r) * 0.25;
    fragColor = vec4(blur * (0.12 * v), 1.0 - 0.88 * v * ao);
#else
    fragColor = vec4(blur * (0.12 * v), 1.0 - 0.88 * v);
#endif
#ifdef TESSERA_TAA_BASE
    // the TAA result with a light sharpen against its softness (display only: the history stays unsharpened)
    ivec2 bp = ivec2(gl_FragCoord.xy);
    ivec2 bhi = textureSize(BaseSampler, 0) - 1;
    vec3 base = texelFetch(BaseSampler, bp, 0).rgb;
    vec3 around = texelFetch(BaseSampler, clamp(bp + ivec2(-1, 0), ivec2(0), bhi), 0).rgb + texelFetch(BaseSampler, clamp(bp + ivec2(1, 0), ivec2(0), bhi), 0).rgb
        + texelFetch(BaseSampler, clamp(bp + ivec2(0, -1), ivec2(0), bhi), 0).rgb + texelFetch(BaseSampler, clamp(bp + ivec2(0, 1), ivec2(0), bhi), 0).rgb;
    base = max(base + (base - (around * 0.125 + base * 0.5)) * 0.25, vec3(0.0));
    fragColor = vec4(fragColor.rgb + base * (1.0 - fragColor.a), 1.0);
#endif
#else
    vec3 c = texture(InSampler, texCoord).rgb * 4.0;
    c += (texture(InSampler, texCoord + vec2(t.x, 0.0)).rgb + texture(InSampler, texCoord - vec2(t.x, 0.0)).rgb
        + texture(InSampler, texCoord + vec2(0.0, t.y)).rgb + texture(InSampler, texCoord - vec2(0.0, t.y)).rgb) * 2.0;
    c += texture(InSampler, texCoord + t).rgb + texture(InSampler, texCoord - t).rgb
        + texture(InSampler, texCoord + vec2(t.x, -t.y)).rgb + texture(InSampler, texCoord + vec2(-t.x, t.y)).rgb;
    fragColor = vec4(c * (1.0 / 16.0), 1.0);
#endif
}
