#version 330
#extension GL_ARB_separate_shader_objects : require

// Stage 4 (Tessera): one texel per pixel, copied (the TAA result back onto the frame; formats differ, so no blit).
uniform sampler2D InSampler;

layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = vec4(texelFetch(InSampler, ivec2(gl_FragCoord.xy), 0).rgb, 1.0);
}
