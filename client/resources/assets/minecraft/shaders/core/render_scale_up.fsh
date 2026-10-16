#version 330
#extension GL_ARB_separate_shader_objects : require

// Render scale: the smaller world image onto the window-sized frame. Bilinear, then a light contrast-adaptive sharpen
// (the centre against its four neighbours one source texel away, limited to their range so edges do not ring).
uniform sampler2D InSampler;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    vec2 px = 1.0 / vec2(textureSize(InSampler, 0));
    vec3 c = texture(InSampler, texCoord).rgb;
    vec3 n = texture(InSampler, texCoord + vec2(0.0, px.y)).rgb;
    vec3 s = texture(InSampler, texCoord - vec2(0.0, px.y)).rgb;
    vec3 e = texture(InSampler, texCoord + vec2(px.x, 0.0)).rgb;
    vec3 w = texture(InSampler, texCoord - vec2(px.x, 0.0)).rgb;
    vec3 lo = min(c, min(min(n, s), min(e, w)));
    vec3 hi = max(c, max(max(n, s), max(e, w)));
    vec3 sharp = c + (c - (n + s + e + w) * 0.25) * 0.6;
    fragColor = vec4(clamp(sharp, lo, hi), 1.0);
}
