#version 330
#extension GL_ARB_separate_shader_objects : require

// Stage 4 (Tessera bloom combine): the three coarser levels (tessera_bloom_tiles, InSampler) upsampled onto the first
// level (ADDITIVE blend): each one bilinear tap at this texel's spot in its tile, kept inside the tile.
uniform sampler2D InSampler;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

vec3 tile(vec2 f, float scale, float ox, vec2 sk, vec2 inv) {
    vec2 p = f / scale;
    vec2 lo = vec2(0.5);
    vec2 hi = sk - 0.5;
#ifdef TESSERA_COMBINE_BOX
    vec3 c = texture(InSampler, (vec2(ox, 0.0) + clamp(p + vec2(-0.5, -0.5), lo, hi)) * inv).rgb
        + texture(InSampler, (vec2(ox, 0.0) + clamp(p + vec2(0.5, -0.5), lo, hi)) * inv).rgb
        + texture(InSampler, (vec2(ox, 0.0) + clamp(p + vec2(-0.5, 0.5), lo, hi)) * inv).rgb
        + texture(InSampler, (vec2(ox, 0.0) + clamp(p + vec2(0.5, 0.5), lo, hi)) * inv).rgb;
    return c * 0.25;
#else
    return texture(InSampler, (vec2(ox, 0.0) + clamp(p, lo, hi)) * inv).rgb;
#endif
}

void main() {
    // the tiles texture: the first level's width, the second level's height
    ivec2 size0 = textureSize(InSampler, 0);
    ivec2 s1 = ivec2(size0.x / 2, size0.y);
    ivec2 s2 = s1 / 2;
    ivec2 s3 = s2 / 2;
    float ox2 = float(s1.x + 2);
    float ox3 = ox2 + float(s2.x + 2);
    vec2 inv = 1.0 / vec2(size0);
    vec2 f = gl_FragCoord.xy;
    vec3 c = tile(f, 2.0, 0.0, vec2(s1), inv) + tile(f, 4.0, ox2, vec2(s2), inv) + tile(f, 8.0, ox3, vec2(s3), inv);
    fragColor = vec4(c, 1.0);
}
