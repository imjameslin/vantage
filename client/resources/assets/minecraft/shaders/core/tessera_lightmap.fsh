#version 330
#extension GL_ARB_separate_shader_objects : require

// Stage 4 (Tessera): the terrain copy of the lightmap (fallback when the lightmap kernel cannot write its twin). Half
// the colour (the twin's scale: terrain.fsh doubles it); alpha = 0.5 + 0.5 * sky light level of the texel
// (lightmap.fsh's sky_level), which terrain.fsh stores in the main target for the lighting pass.
uniform sampler2D Lightmap;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    float sky_level = floor(texCoord.y * 16) / 15;
    fragColor = vec4(texelFetch(Lightmap, ivec2(gl_FragCoord.xy), 0).rgb * 0.5, 0.5 + 0.5 * sky_level);
}
