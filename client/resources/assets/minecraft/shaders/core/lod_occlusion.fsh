#version 330
#extension GL_ARB_separate_shader_objects : require

// Stage 3 r3 (CONTRACT 16.S3-LOD-R3): placeholder of the LOD occlusion box pipeline's fragment stage. The Metal backend replaces
// it with hand-written MSL (TerrainVertexMsl#lodOcclusion) that marks the box's visibility entry in the LodOcclusion buffer; this
// GLSL only declares (and reads) the uniform block so the pipeline keeps it.
layout(std140) uniform LodOcclusion {
    vec4 LodOcclusionEntries;
};

layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = LodOcclusionEntries;
}
