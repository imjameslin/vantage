/* Optional/default-off face-ID shading source only. No device, queue or cache. */
#pragma once
#import "mcmetal_farshader.h"

/* Optional per-layer GPU outcome counters. Exactly one increment per pixel;
 * failures count their FIRST rejection, not every condition that could fail. */
typedef NS_ENUM(uint32_t, MCMTLFarShadeDiagnostic) {
    MCMTLFarShadeDiagnosticGeometry = 0,
    MCMTLFarShadeDiagnosticSky = 1,
    MCMTLFarShadeDiagnosticPriorMissing = 2,
    MCMTLFarShadeDiagnosticPriorUncertain = 3,
    MCMTLFarShadeDiagnosticInvalidInput = 4, /* params, mask/ID/depth consistency */
    MCMTLFarShadeDiagnosticInvalidLookup = 5, /* directory/tail/owner/arena/range */
    MCMTLFarShadeDiagnosticVertexNonfinite = 6, /* original VS output or screen */
    MCMTLFarShadeDiagnosticProjectionClip = 7, /* W/Z, or conservative XY */
    MCMTLFarShadeDiagnosticFlatVarying = 8, /* inconsistent greedyTile */
    MCMTLFarShadeDiagnosticDegenerate = 9, /* triangle area/edge conditioning */
    MCMTLFarShadeDiagnosticTriangle = 10, /* barycentrics, outside/edge/overlap */
    MCMTLFarShadeDiagnosticPerspective = 11, /* weights or reconstructed depth */
    MCMTLFarShadeDiagnosticGradient = 12, /* helper UVs/derivatives */
    MCMTLFarShadeDiagnosticInterpolant = 13, /* interpolated color/fog */
    MCMTLFarShadeDiagnosticAlphaDiscard = 14, /* ORIGINAL fragment discard */
    MCMTLFarShadeDiagnosticFragmentNonfinite = 15,
    MCMTLFarShadeDiagnosticCount = 16
};
enum { MCMTLFarShadeDiagnosticBytes = MCMTLFarShadeDiagnosticCount * sizeof(uint32_t) };
/* Optional first reason-9 sample, appended after the unchanged counters. All
 * offsets below are uint32 WORD offsets relative to SampleOffsetBytes. Float
 * values are raw IEEE bits; vectors are component order, corners are 0..3.
 * claim: 0 absent, 1 claimed. Inspect payload ONLY after successful GPU completion;
 * claim is exclusion, not a live-ready flag. First means first GPU CAS winner,
 * not lowest raster coordinate. Caller zeros the entire 384 bytes before use. */
enum {
    MCMTLFarShadeDiagnosticSampleOffsetBytes = 64,
    MCMTLFarShadeDiagnosticSampleWords = 80,
    MCMTLFarShadeDiagnosticSampleBytes = 320,
    MCMTLFarShadeDiagnosticFullBytes = 384,
    MCMTLFarShadeDiagnosticSampleFlag = 2
};
typedef NS_ENUM(uint32_t, MCMTLFarShadeSampleWord) {
    MCMTLFarShadeSampleClaim = 0, MCMTLFarShadeSampleReason = 1,
    MCMTLFarShadeSampleFace = 2, MCMTLFarShadeSampleX = 3, MCMTLFarShadeSampleY = 4,
    MCMTLFarShadeSampleTriangle = 5, MCMTLFarShadeSampleLayout = 6,
    MCMTLFarShadeSampleSection = 7, MCMTLFarShadeSampleArena = 8, MCMTLFarShadeSampleQuad = 9,
    MCMTLFarShadeSampleAreaBits = 10, MCMTLFarShadeSampleRoundoffBits = 11,
    MCMTLFarShadeSampleLengthsBits = 12, /* 3 words */
    MCMTLFarShadeSampleViewport = 15, /* 2 words */
    MCMTLFarShadeSampleScreenBits = 17, /* 8 words */
    MCMTLFarShadeSampleCornerClipBits = 25, /* 16 words */
    MCMTLFarShadeSampleRawVertexWords = 41, /* BLOCK 28; compact 12 + 16 zero */
    MCMTLFarShadeSampleSectionWords = 69, /* int4, 4 words */
    MCMTLFarShadeSampleCapturedDepthBits = 73,
    MCMTLFarShadeSampleFlags = 74, MCMTLFarShadeSampleReserved = 75 /* 5 zero words */
};

/* Returns one MSL library with farshade_vertex / farshade_fragment. Both original
 * VS+FS pairs must pass mcmtl_farshader_rewrite admission (including the SAME
 * vary mode); its rewritten output is never used. Only admitted originals are
 * adapted into namespaced helpers. nil + local NSError on rejection.
 *
 * Fragment-stage bindings (read inputs immutable and held through completion;
 * optional diagnostics are GPU-writable and exclusively owned by this encode):
 *   buffer 0/1/2/5: original Projection(64)/Globals(48)/Fog(48)/Terrain(80).
 *   buffer 7: argument buffer struct Lookup {
 *       array<device const uint*,64> vertices [[id(0)]]; };
 *     Each pointer already includes vertexOffset; declare indirect read usage.
 *   buffer 8: uint4 arenas[64] = {faceBase, layout(1 BLOCK/2 compact),
 *                               faceCountLo, 0}; accessible complete face count.
 *   buffer 9/10: uint directory[] / tails[], per mcmetal_farlookup.h.
 *   buffer 11: int4 sections[].
 *   buffer 12: 32 bytes: uint4 {directoryCount,tailWordCount,sectionCount,arenaCount}
 *     at 0; uint2 viewport at 16; uint layer at 24; uint flags at 28 (bit 0 exact
 *     coverage, bit 1 first reason-9 sample enabled, other bits zero).
 *   buffer 13: ONLY farshade_fragment_diagnostics, device atomic_uint[16],
 *     MCMTLFarShadeDiagnostic indices. Normal entry has no counter binding or
 *     counter operations. Diagnostic entry returns identical color/depth/masks.
 *     With flags bit 1: buffer >=384 bytes, sample at byte 64 per constants above.
 *   texture/sampler 0: original atlas; texture/sampler 2: original lightmap.
 *   texture 3: R32Uint current-pixel face IDs, read; texture 4: R8Uint current
 *     validity, read; texture 5: R32Float source depth, read, mandatory in both
 *     modes. All match viewport dimensions. Prior masks 0/1 survive; ID zero
 *     requires mask 3 and zero depth; positive IDs require mask 2 and depth (0,1].
 * Parent preserves original sampler LOD clamps, including lightmap now in FS.
 * Fullscreen triangle: vertexCount=3, no vertex bindings, viewport origin zero,
 * single sample, no culling/blending/depth attachment. color0 RGBA8Unorm;
 * color1 R32Float depth (source-exact in exact mode); color2 R8Uint validity. Every path writes:
 * missing=0 / uncertain=1 (zero color/depth), geometry=2, sky=3 (zero color/depth).
 * Sky means ONLY source ID=0 AND mask=3; it does not supply a sky color.
 * This pass never repairs prior missing/uncertain pixels.
 *
 * Conservative coverage rejects any clipped quad, degenerate triangle, differing
 * flat greedyTile, or pixel within 1/256 pixel + float error allowance of ANY
 * triangle edge (including the shared diagonal). Helper lanes extrapolate the
 * SAME triangle, unwrapped, at its 2x2 pixel centers; invalid/ill-conditioned
 * perspective denominators reject. This is conservative float reconstruction,
 * not bit-identical fixed-point rasterization or a depth-bias/MSAA/cull replay.
 * Exact coverage instead trusts source IDs/depth, admits XY clipping and picks
 * the nearest inside triangle at raster-edge ties without clamping barycentrics.
 * Caller must prove identical camera sampling footprint (including helper-lane
 * phase), unchanged geometry and alpha visibility separately. Nonfinite,
 * ill-conditioned and near/far-plane
 * crossing geometry still rejects. This does not establish coverage under motion.
 * Parent supplies its original compile options and valid uniforms/materials.
 * Wrapper guards use bitwise finite tests even with fast math; the unchanged
 * original formulas retain the caller's math semantics.
 * SHADING ONLY: absent/newly revealed IDs (especially animated cutout) require
 * a conservative guard or visibility replay. No coverage completeness claim.
 */
NSString *mcmtl_farshade_source(NSString *blockVS, NSString *blockEntry,
    NSString *compactVS, NSString *compactEntry, NSString *fragment,
    NSString *fragmentEntry, MCMTLFarShaderLayer layer, NSError **error);
