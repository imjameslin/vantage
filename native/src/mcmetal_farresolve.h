/* Prompt 5 Phase 1: bounded pair repair and same-frame GPU fallback selection. */
#ifndef MCMETAL_FARRESOLVE_H
#define MCMETAL_FARRESOLVE_H

#import "mcmetal_farwarp.h"

enum { MCMTLFarResolveLayerCount = 2, MCMTLFarResolveMaxDrawCount = 4096 };

/* Layer 0 = opaque, layer 1 = cutout, both warped to the SAME current viewport.
 * R32Float depth, not the original image's Depth32Float. */
typedef struct { MCMTLFarWarpTargets layers[MCMTLFarResolveLayerCount]; } MCMTLFarResolvePair;

typedef struct {
    BOOL sceneMatchesCurrent;
    BOOL materialMatchesCurrent;
    BOOL sourceFresh; /* Caller checks source camera time/frame, NOT completion time. */
} MCMTLFarResolveOlderEligibility;

typedef struct {
    uint32_t holeCount; /* Number of actual viewport pixels invalid in EITHER layer. */
    uint32_t fallback;  /* Exactly 0 or 1; zero tolerance: holeCount != 0. */
} MCMTLFarResolveStats;

typedef struct {
    __unsafe_unretained id<MTLBuffer> source; /* Current sync indexed-indirect records. */
    NSUInteger sourceOffset;
    __unsafe_unretained id<MTLBuffer> fallback;
    NSUInteger fallbackOffset;
    uint32_t count; /* 0..4096 TOTAL records, including BOTH layers' fallback draws. */
    __unsafe_unretained id<MTLBuffer> composite;
    NSUInteger compositeOffset;
    __unsafe_unretained id<MTLBuffer> stats;
    NSUInteger statsOffset;
} MCMTLFarResolveArguments;

@interface MCMTLFarResolve : NSObject
- (instancetype)init NS_UNAVAILABLE;
+ (instancetype)new NS_UNAVAILABLE;
/* WORKER ONLY. Immutable pipelines, no command queue or owned scratch resources. */
- (instancetype)initWithDevice:(id<MTLDevice>)device error:(NSError **)error;

/* GPU reset, bounded per-pixel merge, then final argument selection. No commit,
 * enqueue, wait, CPU readback, shader compilation, texture/buffer allocation,
 * per-frame heap scratch, decoder access, or lease mutation. Metal encoders and
 * small setBytes constants are the only per-call encoding objects.
 *
 * newest/eligible older MUST be successful MCMTLFarWarp outputs for the exact
 * current camera, with ordered writes and immutable inputs. Caller validates
 * newest's current scene/material/freshness before calling. Older is ignored
 * (not inspected or bound) unless non-NULL and ALL three attestations are true.
 * Newest mask >= 2 wins; else eligible older mask >= 2; else zero RGBA/depth/ID
 * and Missing mask. Valid payloads and mask are copied without resampling.
 * Each layer writes every pixel; holeMask (R8Uint) is 1 if either is Missing,
 * else 0. All viewport pixels, including borders/sky, count, with NO tolerance.
 *
 * With zero holes: composite = uint32[4]{3,1,0,0}, every fallback record's five
 * uint32 words = 0. With any hole: composite = {0,0,0,0}, fallback is a bit-exact
 * copy of source (including signed baseVertex bits). Both layer composites MUST
 * use this SAME indirect argument; both fallback lists must use this output
 * table. Never independently choose per layer. Source table must include every
 * synchronous fallback draw for both layers and remain current/immutable until
 * completion. All offsets are byte offsets, 4-byte aligned. Buffers need room
 * for count*20, 16 and sizeof(MCMTLFarResolveStats) bytes respectively. Even
 * count=0 requires source/fallback buffers with 4 accessible bytes at offset.
 * The GPU overwrites stats every call. CPU may inspect ONLY after actual GPU
 * completion and, for managed storage, caller-encoded synchronization.
 *
 * Caller exclusively owns an unqueued, retained-reference MAIN command buffer,
 * with no open encoder. This may be a caller-created sideCB intended for later
 * main-queue submission. All validation precedes encoding. Any NO after encoder
 * creation requires discarding that entire buffer; NO never refreshes outputs.
 * Caller orders warp -> resolve -> indirect draws on this buffer/queue, retains
 * source image leases through actual MAIN completion, and retires abandoned
 * sideCB pins without ever publishing their targets/stats. Producer completion
 * alone does not permit reusing a leased slot. This object never submits a sideCB.
 *
 * Textures: common dimensions 1..16384, tracked standalone single-mip/sample 2D,
 * identity swizzles, ShaderRead inputs / ShaderWrite outputs. Same conservative
 * storage/view exclusions as MCMTLFarWarp. All active texture planes must be
 * distinct, including holeMask. All four buffers must be distinct standalone,
 * tracked, non-sparse private/shared/managed resources, with non-overlapping
 * backing allocations (including no-copy CPU memory). No heaps or remote views.
 * Caller must not alias these resources through external handles or mutate
 * them concurrently. Shared/managed backing overlaps are checked on the CPU.
 *
 * Pure rotation ONLY, as established by MCMTLFarWarp. Unsupported translation,
 * rejected/stale newest inputs or failed warps must keep the ordinary sync path;
 * never resolve old target contents after a rejected warp. No Phase 2 shading,
 * translation visibility proof, near composition or game integration here.
 */
- (BOOL)encodeToCommandBuffer:(id<MTLCommandBuffer>)buffer
                       newest:(MCMTLFarResolvePair)newest
                        older:(const MCMTLFarResolvePair *)older
             olderEligibility:(MCMTLFarResolveOlderEligibility)eligibility
                      targets:(MCMTLFarResolvePair)targets
                     holeMask:(id<MTLTexture>)holeMask
                    arguments:(MCMTLFarResolveArguments)arguments
                        error:(NSError **)error;
@end

#endif
