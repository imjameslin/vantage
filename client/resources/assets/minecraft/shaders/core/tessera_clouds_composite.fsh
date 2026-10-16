#version 330
#extension GL_ARB_separate_shader_objects : require

// Stage 4 (Tessera clouds, half resolution): the clouds marched at half resolution (core/tessera_clouds.fsh with
// TESSERA_CLOUDS_LOW: premultiplied colour, depth at the cloud surface, cleared to 0 = none), drawn over the frame
// where vanilla draws its clouds. Colour bilinear; depth the nearest of the 2 x 2 texels under the filter (reversed Z:
// the largest), so the soft edge passes the depth test against the sky and terrain in front still clips per frame pixel.
uniform sampler2D InSampler;
uniform sampler2D CloudDepth;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 c = texture(InSampler, texCoord);
    if (c.a < 1.0 / 255.0) discard;
    ivec2 size = textureSize(CloudDepth, 0);
    ivec2 i = clamp(ivec2(floor(texCoord * vec2(size) - 0.5)), ivec2(0), size - 2);
    float z = max(max(texelFetch(CloudDepth, i, 0).r, texelFetch(CloudDepth, i + ivec2(1, 0), 0).r),
        max(texelFetch(CloudDepth, i + ivec2(0, 1), 0).r, texelFetch(CloudDepth, i + ivec2(1, 1), 0).r));
    fragColor = c;
    gl_FragDepth = z;
}
