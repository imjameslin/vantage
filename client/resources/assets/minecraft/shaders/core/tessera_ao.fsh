#version 330
#extension GL_ARB_separate_shader_objects : require
#extension GL_ARB_texture_gather : require

// Stage 4 (Tessera ambient occlusion, TesseraBloom.ambientOcclusion): half-resolution screen-space AO from the main
// depth (InSampler) and the frame's TesseraInvViewProj. 8 spiral samples in a disk whose world radius grows with
// distance (contact shadows near, valley shading far on Farsight terrain). Output r = visibility (1 = open).
#include <minecraft:fog.glsl>
#include <minecraft:tessera.glsl>

#include <minecraft:tessera_ao.glsl>

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

void main() {
#ifdef TESSERA
    float ao = tesseraAoVisibility();
    fragColor = vec4(ao, ao, ao, 1.0);
#else
    fragColor = vec4(1.0);
#endif
}
