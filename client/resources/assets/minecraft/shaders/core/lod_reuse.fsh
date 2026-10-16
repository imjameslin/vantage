#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D ReuseColor;
uniform sampler2D ReuseDepth;
// Farsight turn reuse (LodReuse): the capture is a guard image padded by Params.xy pixels per side.
// Params.z == 0: same camera, exact texel offset. Otherwise pure rotation: CurrentToSource maps a current Metal clip
// point to the guard capture's clip (any depth: rays through the unchanged eye), SourceToCurrent maps the sampled
// guard texel back to current clip for its depth. Sizes = current width/height, guard width/height.
layout(std140) uniform ReuseWarp {
    mat4 CurrentToSource;
    mat4 SourceToCurrent;
    vec4 Sizes;
    vec4 Params;
};
layout(location = 0) out vec4 fragColor;
void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    ivec2 src = pixel + ivec2(Params.xy);
    bool warp = Params.z != 0.0;
    if (warp) {
        vec2 ndc = vec2(2.0 * gl_FragCoord.x / Sizes.x - 1.0, 1.0 - 2.0 * gl_FragCoord.y / Sizes.y);
        vec4 p = CurrentToSource * vec4(ndc, 0.5, 1.0);
        if (p.w <= 0.0) discard;
        vec2 uv = vec2(p.x / p.w + 1.0, 1.0 - p.y / p.w) * 0.5;
        src = ivec2(floor(uv * Sizes.zw));
        if (src.x < 0 || src.y < 0 || src.x >= int(Sizes.z) || src.y >= int(Sizes.w)) discard;
    }
    float depth = texelFetch(ReuseDepth, src, 0).r;
    // Reversed infinite-Z: covered far terrain is strictly positive; zero is the clear value.
    if (depth == 0.0) discard;
    fragColor = texelFetch(ReuseColor, src, 0);
    if (warp) {
        vec2 sndc = vec2(2.0 * (float(src.x) + 0.5) / Sizes.z - 1.0, 1.0 - 2.0 * (float(src.y) + 0.5) / Sizes.w);
        vec4 c = SourceToCurrent * vec4(sndc, depth, 1.0);
        depth = c.z / c.w;
    }
    gl_FragDepth = depth;
}
