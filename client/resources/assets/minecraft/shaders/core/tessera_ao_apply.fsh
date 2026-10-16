#version 330
#extension GL_ARB_separate_shader_objects : require

// Stage 4 (Tessera ambient occlusion): the half-resolution AO, softened by 4 bilinear taps, multiplied onto the frame
// (blend DST_COLOR, ZERO).
uniform sampler2D InSampler;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    vec2 t = 1.0 / vec2(textureSize(InSampler, 0));
    float ao = (texture(InSampler, texCoord + t * vec2(-0.75, -0.75)).r + texture(InSampler, texCoord + t * vec2(0.75, -0.75)).r
        + texture(InSampler, texCoord + t * vec2(-0.75, 0.75)).r + texture(InSampler, texCoord + t * vec2(0.75, 0.75)).r) * 0.25;
    fragColor = vec4(vec3(ao), 1.0);
}
