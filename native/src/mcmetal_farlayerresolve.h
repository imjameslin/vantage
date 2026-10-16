/* Default-off exact-camera shading sidecar; legacy pair resolve is unchanged. */
#pragma once
#import "mcmetal_farresolve.h"

typedef struct {
    uint32_t holes[2];
    uint32_t fallback[2];
} MCMTLFarLayerResolveStats;

@interface MCMTLFarLayerResolve : NSObject
- (instancetype)init NS_UNAVAILABLE;
+ (instancetype)new NS_UNAVAILABLE;
/* Worker-only immutable pipelines. Owns no per-frame resources. */
- (instancetype)initWithDevice:(id<MTLDevice>)device error:(NSError **)error;
/* MAIN retained-reference, unqueued command buffer, no open encoder. Inputs
 * are current-material shading outputs at precisely the source camera. SOLID
 * has no discard/blending/A2C; CUTOUT still requires matching material alpha.
 * Each layer independently requires zero uncertain pixels. Buffers have offset
 * zero: source/fallback count*20 bytes (minimum 4), composite 32, layer codes
 * count*4 (minimum 4), alpha 4, stats 16. Codes were CPU-validated as 0/1/2/8/9/10
 * and remain immutable. Translucent records always pass through unchanged.
 * Copy all trusted pixels, zero untrusted output, count holes, then select each
 * layer's exact original args or composite. No allocations, commits, waits or
 * CPU readbacks. Caller preserves per-layer insertion and near depth ordering.
 * All inputs/outputs distinct tracked standalone resources on the same device.
 * Any NO after encoding begins requires abandoning the entire command buffer.
 */
- (BOOL)encodeToCommandBuffer:(id<MTLCommandBuffer>)buffer
                       newest:(MCMTLFarResolvePair)newest
                      targets:(MCMTLFarResolvePair)targets
                   sourceArgs:(id<MTLBuffer>)source
                 fallbackArgs:(id<MTLBuffer>)fallback
                compositeArgs:(id<MTLBuffer>)composite
                   layerCodes:(id<MTLBuffer>)codes
                        alpha:(id<MTLBuffer>)alpha
                        stats:(id<MTLBuffer>)stats
                        count:(uint32_t)count error:(NSError **)error;
@end
