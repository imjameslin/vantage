/* Prompt 5 phase 1: optional, worker-built visibility MRT for exact LOD pulls.
 * Internal ARC API; retains admitted original sources, no scheduling or encoding.
 */
#ifndef MCMETAL_FARSHADER_H
#define MCMETAL_FARSHADER_H
#import "mcmetal_internal.h"

typedef NS_ENUM(uint32_t, MCMTLFarShaderLayout) {
    MCMTLFarShaderBlock = 1,       /* 28-byte BLOCK, four vertices per arena face. */
    MCMTLFarShaderCompact = 2     /* 48-byte compact face; item.x still in vertices. */
};
typedef NS_ENUM(uint32_t, MCMTLFarShaderLayer) {
    MCMTLFarShaderSolid = 0,
    MCMTLFarShaderCutout = 1
};
enum { MCMTLFarShaderArenaBuffer = 6, MCMTLFarShaderIDAttachment = 1 };

/* Arena-wide, not per-face: assign disjoint ranges within ONE owned snapshot.
 * IDs are 1 + faceBase + (items[e >> 6].x >> 2) + (e & 63); zero is background.
 * faceCount covers original arena addresses, including holes. Geometry ranges,
 * items, sections and material versions must stay immutable and leased for every
 * producer AND ID consumer. No address recycling until that snapshot retires.
 * Only faceBase (four bytes) is bound at vertex buffer 6, once per arena draw.
 */
typedef struct { uint32_t faceBase, faceCount; } MCMTLFarShaderArena;
BOOL mcmtl_farshader_arena(uint64_t faceBase, uint64_t faceCount,
                          MCMTLFarShaderArena *outArena, NSError **error);
/* Validate each snapshot cluster allocation once (O(clusters), no face registry).
 * localQuadCount is 1..64; every emitted e low6 must be < this count, its high
 * bits must index this immutable item table, and item.y must index its sections.
 * quads/index buffers must be in bounds and use 4*q+{0,1,2,2,3,0}. Caller owns
 * these cull/index bounds and disjointness between arenas; never rebase item.x
 * to the compacted output ordinal. This helper does not inspect GPU buffers.
 */
BOOL mcmtl_farshader_item(MCMTLFarShaderArena arena, uint32_t firstVertex,
                         uint32_t localQuadCount, NSError **error);

/* Immutable source-only result; usable for rejection tests without a GPU.
 * Recognized forms: current non-faded greedy BLOCK/compact cluster hand pulls,
 * original float varyings, vfog, and vfog+hvary; runtime/nearest/RGSS branches.
 * No generic shader parser: complete-source fingerprints tolerate only renaming
 * the entry point and SPIRV-Cross _<number> identifiers (consistently). Unknown
 * bodies, layouts, diagnostics, lean variants, macros or extra entry points fail.
 * Fingerprints pin current TerrainVertexMsl/TerrainShaderVariants forms. Changes
 * require reviewing the new complete source and running parity before admission.
 * Faded shaders ALWAYS fail: guard bands move gl_FragCoord and the original
 * dither origin is not yet restored. No fade coverage equivalence is claimed.
 */
@interface MCMTLFarShaderSource : NSObject
- (instancetype)init NS_UNAVAILABLE;
+ (instancetype)new NS_UNAVAILABLE;
@property(nonatomic, readonly, copy) NSString *vertex;
@property(nonatomic, readonly, copy) NSString *fragment;
@property(nonatomic, readonly) MCMTLFarShaderLayout layout;
@property(nonatomic, readonly) MCMTLFarShaderLayer layer;
@end
MCMTLFarShaderSource *mcmtl_farshader_rewrite(NSString *vertex, NSString *fragment,
    NSString *vertexEntry, NSString *fragmentEntry, MCMTLFarShaderLayout layout,
    MCMTLFarShaderLayer layer, NSError **error);

typedef struct {
    uint32_t primitiveType, cullMode, fillMode, frontFacing;
    float depthBiasConstant, depthBiasSlopeScale, depthBiasClamp;
    uint32_t pushConstantSize, pushConstantStages;
} MCMTLFarShaderRaster;
typedef struct {
    uint32_t bufferMask[2], textureMask[2], samplerMask[2];
    uint32_t textureCubeMask[2], texelBufferMask[2];
    uint32_t requiredVertexBufferMask;
} MCMTLFarShaderBindings;

@interface MCMTLFarShader : NSObject
- (instancetype)init NS_UNAVAILABLE;
+ (instancetype)new NS_UNAVAILABLE;
@property(nonatomic, readonly, strong) id<MTLRenderPipelineState> pipelineState;
@property(nonatomic, readonly, strong) id<MTLDepthStencilState> depthStencilState;
@property(nonatomic, readonly) MCMTLFarShaderRaster raster;
@property(nonatomic, readonly) MCMTLFarShaderBindings bindings;
@property(nonatomic, readonly) MCMTLFarShaderLayout layout;
@property(nonatomic, readonly) MCMTLFarShaderLayer layer;
@property(nonatomic, readonly) MTLPixelFormat colorFormat;
@property(nonatomic, readonly) MTLPixelFormat depthFormat;
/* Immutable original stage provenance, retained only by this optional program.
 * A worker may construct current-material shading without consulting a mutable
 * pipeline cache after resource reload. These are NOT the ID-injected sources.
 * Compile options are returned as an owned copy so callers cannot mutate them.
 */
@property(nonatomic, readonly, copy) NSString *originalVertex, *originalFragment;
@property(nonatomic, readonly, copy) NSString *originalVertexEntry, *originalFragmentEntry;
- (MTLCompileOptions *)copyOriginalCompileOptions;
@end

/* WORKER ONLY (main thread explicitly rejected); caller must also keep this off
 * a non-main render thread. Synchronous compilation, never on offer/encode path.
 * Caller supplies the EXACT selected built-in pipeline's sources, entry names
 * and compile options, pins original for this call, and must not mutate options
 * concurrently. No lookup in source-retention machinery or variant caches.
 * variantKey names the original selected attachment variant: color mask 1,
 * Depth32Float, optional BGRA8_COLOR0; ICB variants are unsupported. Descriptor
 * is copied; only functions, resolved original formats and new color1 R32Uint
 * change. All existing flags (including supportIndirectCommandBuffers) survive;
 * ICB is never enabled here. Color0 blending, extra MRTs, vertex descriptors,
 * emulation, MSAA and occupied vertex buffer6/user locn6 are rejected.
 * The result owns PSO/depth state and VALUE copies of raster/binding metadata.
 * Apply raster/depth state plus the packet's viewport/scissor/depth-clip state;
 * bind all original resources and the extra arena base. Clear ID=0, store all
 * three attachments, preserve SOLID/CUTOUT draw order, and draw geometry once.
 * Errors are local NSError values; no main-device TLS, decoder or cache writes.
 */
MCMTLFarShader *mcmtl_farshader_compile(MCMTLPipeline *original, uint64_t variantKey,
    NSString *vertex, NSString *fragment, NSString *vertexEntry, NSString *fragmentEntry,
    MTLCompileOptions *options, MCMTLFarShaderLayout layout, MCMTLFarShaderLayer layer,
    NSError **error);
#endif
