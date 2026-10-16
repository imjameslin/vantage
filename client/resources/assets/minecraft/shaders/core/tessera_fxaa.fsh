#version 330
#extension GL_ARB_separate_shader_objects : require

// Stage 4 (Tessera anti-aliasing): a light FXAA (Lottes' fast edge filter) over the finished world image, at 75 %
// moderate strength, and a light sharpening everywhere else. InSampler = a copy of the frame
// (bilinear).
uniform sampler2D InSampler;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

float luma(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

void main() {
    vec2 px = 1.0 / vec2(textureSize(InSampler, 0));
    vec3 m = texture(InSampler, texCoord).rgb;
    // the 2x2 averages around each corner (bilinear at half-pixel offsets)
    float nw = luma(texture(InSampler, texCoord + vec2(-0.5, -0.5) * px).rgb);
    float ne = luma(texture(InSampler, texCoord + vec2(0.5, -0.5) * px).rgb);
    float sw = luma(texture(InSampler, texCoord + vec2(-0.5, 0.5) * px).rgb);
    float se = luma(texture(InSampler, texCoord + vec2(0.5, 0.5) * px).rgb);
    float lm = luma(m);
    float lo = min(lm, min(min(nw, ne), min(sw, se)));
    float hi = max(lm, max(max(nw, ne), max(sw, se)));
    if (hi - lo < max(0.0312, hi * 0.125)) {
        // not an edge: a light unsharp mask instead (a moderate sharpening, for use without TAA)
        vec3 n4 = texture(InSampler, texCoord + vec2(px.x, 0.0)).rgb + texture(InSampler, texCoord - vec2(px.x, 0.0)).rgb
            + texture(InSampler, texCoord + vec2(0.0, px.y)).rgb + texture(InSampler, texCoord - vec2(0.0, px.y)).rgb;
        fragColor = vec4(m * 1.125 - n4 * 0.03125, 1.0);
        return;
    }
    // blur along the edge: perpendicular to the luma gradient
    vec2 dir = vec2(-((nw + ne) - (sw + se)), (nw + sw) - (ne + se));
    float reduce = max((nw + ne + sw + se) * (0.25 * 0.125), 1.0 / 128.0);
    float scale = 1.0 / (min(abs(dir.x), abs(dir.y)) + reduce);
    dir = clamp(dir * scale, vec2(-8.0), vec2(8.0)) * px;
    vec3 a = 0.5 * (texture(InSampler, texCoord + dir * (1.0 / 3.0 - 0.5)).rgb + texture(InSampler, texCoord + dir * (2.0 / 3.0 - 0.5)).rgb);
    vec3 b = a * 0.5 + 0.25 * (texture(InSampler, texCoord - dir * 0.5).rgb + texture(InSampler, texCoord + dir * 0.5).rgb);
    float lb = luma(b);
    vec3 aa = (lb < lo || lb > hi) ? a : b;
    fragColor = vec4(mix(m, aa, 0.75), 1.0);
}
