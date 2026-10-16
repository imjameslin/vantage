#version 330
#extension GL_ARB_separate_shader_objects : require

// Stage 4 (Tessera bloom tiles): the bloom's three coarser levels (1/2, 1/4, 1/8 of the first level, InSampler) in one
// pass, side by side in one texture of the first level's size (tile k at x = ox_k, y = 0; 2-texel gaps), each texel a box
// average of the first level over twice its own footprint (bilinear taps on texel corners). One pass instead of a chain
// of small dependent ones; tessera_bloom_combine adds them back onto the first level.
uniform sampler2D InSampler;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

// the average of S x S bilinear taps on texel corners round center (a box of 2S x 2S texels), S fixed per tile
vec3 boxTaps2(vec2 center, vec2 inv) {
    vec3 c = vec3(0.0);
    for (int j = 0; j < 2; j++) {
        for (int i = 0; i < 2; i++) {
            c += textureLod(InSampler, (center - 2.0 + vec2(2 * i + 1, 2 * j + 1)) * inv, 0.0).rgb;
        }
    }
    return c * (1.0 / 4.0);
}

vec3 boxTaps4(vec2 center, vec2 inv) {
    vec3 c = vec3(0.0);
    for (int j = 0; j < 4; j++) {
        for (int i = 0; i < 4; i++) {
            c += textureLod(InSampler, (center - 4.0 + vec2(2 * i + 1, 2 * j + 1)) * inv, 0.0).rgb;
        }
    }
    return c * (1.0 / 16.0);
}

vec3 boxTaps8(vec2 center, vec2 inv) {
    vec3 c = vec3(0.0);
    for (int j = 0; j < 8; j++) {
        for (int i = 0; i < 8; i++) {
            c += textureLod(InSampler, (center - 8.0 + vec2(2 * i + 1, 2 * j + 1)) * inv, 0.0).rgb;
        }
    }
    return c * (1.0 / 64.0);
}

void main() {
    ivec2 size0 = textureSize(InSampler, 0);
    ivec2 px = ivec2(floor(gl_FragCoord.xy));
    ivec2 s1 = size0 / 2;
    ivec2 s2 = s1 / 2;
    ivec2 s3 = s2 / 2;
    int ox2 = s1.x + 2;
    int ox3 = ox2 + s2.x + 2;
    int k;
    ivec2 q;
    ivec2 sk;
    if (px.x < s1.x) {
        k = 1; q = px; sk = s1;
    } else if (px.x >= ox2 && px.x < ox2 + s2.x) {
        k = 2; q = px - ivec2(ox2, 0); sk = s2;
    } else if (px.x >= ox3 && px.x < ox3 + s3.x) {
        k = 3; q = px - ivec2(ox3, 0); sk = s3;
    } else {
        fragColor = vec4(0.0);
        return;
    }
    if (q.y >= sk.y) {
        fragColor = vec4(0.0);
        return;
    }
    // fixed loop counts per tile (unrolled) and level-0 taps (no derivatives)
    vec2 inv = 1.0 / vec2(size0);
    vec3 c;
    if (k == 1) c = boxTaps2(vec2(q * 2) + 1.0, inv);
    else if (k == 2) c = boxTaps4(vec2(q * 4) + 2.0, inv);
    else c = boxTaps8(vec2(q * 8) + 4.0, inv);
    fragColor = vec4(c, 1.0);
}
