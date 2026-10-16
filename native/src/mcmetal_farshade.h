/* Default-off visibility-ID shading. No scheduler, queue, or game integration. */
#pragma once
#import "mcmetal_farlookup.h"
#import "mcmetal_farshader.h"
#import "mcmetal_farshade_source.h"

@class MCMTLFarShadeGeometry;

/* Exact current bound bytes, same layouts as the admitted original stages. */
typedef struct {
    uint8_t projection[64], globals[48], fog[48], terrain[80];
} MCMTLFarShadeUniforms;
typedef struct {
    __unsafe_unretained id<MTLTexture> atlas, lightmap;
    __unsafe_unretained id<MTLSamplerState> atlasSampler, lightSampler;
    BOOL atlasLODClamp, lightLODClamp;
    float atlasLODMin, atlasLODMax, lightLODMin, lightLODMax;
} MCMTLFarShadeMaterials;
typedef struct {
    __unsafe_unretained id<MTLTexture> ids, validity, depth; /* R32Uint / R8Uint / R32Float, ShaderRead */
    /* Caller proves the same camera sampling footprint (including an exact
     * integer crop and the 2x2 helper-lane phase used by alpha sampling),
     * unchanged geometry, and alpha visibility separately.
     * Enables source-authoritative coverage/depth, NOT motion admission. */
    BOOL exactCoverage;
} MCMTLFarShadeSource;
typedef struct {
    __unsafe_unretained id<MTLTexture> color, depth, validity;
    /* RGBA8Unorm / R32Float / R8Uint, RenderTarget | ShaderRead. */
} MCMTLFarShadeTargets;

@interface MCMTLFarShade : NSObject
- (instancetype)init NS_UNAVAILABLE;
+ (instancetype)new NS_UNAVAILABLE;
/* Worker only. Both vertex sources and the common layer fragment must be exact
 * admitted built-ins, with the same varying form. Uses caller's original options
 * (copied); never compiles from a render-frame call or modifies an original PSO.
 */
- (instancetype)initWithDevice:(id<MTLDevice>)device
                    blockVertex:(NSString *)blockVertex blockEntry:(NSString *)blockEntry
                  compactVertex:(NSString *)compactVertex compactEntry:(NSString *)compactEntry
                       fragment:(NSString *)fragment fragmentEntry:(NSString *)fragmentEntry
                          layer:(MCMTLFarShaderLayer)layer options:(MTLCompileOptions *)options
                          error:(NSError **)error;
/* Worker convenience for the actually admitted producer programs. Uses their
 * retained ORIGINAL stages/options, no live pipeline-cache lookup. Both layouts
 * must share the same layer, fragment and compiler settings. Nonzero depth bias
 * is unsupported by the reconstructed depth and therefore rejected.
 */
- (instancetype)initWithBlockProgram:(MCMTLFarShader *)block
                     compactProgram:(MCMTLFarShader *)compact error:(NSError **)error;
@property(nonatomic, readonly) id<MTLDevice> device;
@property(nonatomic, readonly) MCMTLFarShaderLayer layer;

/* Worker-only per-generation argument preparation, bounded by budgetBytes.
 * The caller may share one result across SOLID/CUTOUT programs on this device:
 * their fixed argument layout is identical. Keeps allocation-range leases.
 */
- (MCMTLFarShadeGeometry *)prepareLookup:(MCMTLFarFaceLookup *)lookup
                            budgetBytes:(uint64_t)budgetBytes error:(NSError **)error;

/* Encode one full-screen triangle, no commit/enqueue/wait/readback/compilation.
 * Caller owns an unqueued retained-reference buffer with no open encoder.
 * All source writes and CURRENT material updates must precede this pass on its
 * queue or through already-committed dependencies. A side CB submitted before
 * pending main-stream material writes is NOT current-material shading.
 *
 * Caller pins source image IDs/depth/validity and immutable lookup through actual completion.
 * The encoding attaches an additional geometry hold to this CB before reads.
 * Does not repair missing visibility: masks <2 remain missing/uncertain, sky is
 * 3, reconstructed geometry is 2, rejected geometry/current alpha is uncertain.
 * IDs must describe this geometry and layer at this exact current camera.
 * Geometry covered only by old/hidden/alpha-discarded faces cannot be invented
 * from these IDs. A separate coverage proof/replay and fallback remain required.
 * exactCoverage uses the supplied depth verbatim and admits XY-clipped corners
 * and raster-edge ties. Otherwise conservative edge/clip rejection is retained.
 * Source depth is mandatory in both modes, same dimensions as IDs/validity.
 *
 * Any NO after encoder creation requires discarding the entire buffer. Targets
 * and input planes must be distinct tracked, standalone 2D, one mip/sample.
 * No texture/buffer allocations occur here; small setBytes constants are copied.
 */
- (BOOL)encodeToCommandBuffer:(id<MTLCommandBuffer>)buffer
                      geometry:(MCMTLFarShadeGeometry *)geometry
                        source:(MCMTLFarShadeSource)source
                       targets:(MCMTLFarShadeTargets)targets
                      uniforms:(const MCMTLFarShadeUniforms *)uniforms
                     materials:(MCMTLFarShadeMaterials)materials error:(NSError **)error;
/* Additive diagnostic overload. nil is exactly the normal path above. A nonnil
 * same-device tracked, standalone buffer (shared/private/managed, >=64 bytes)
 * selects a worker-precompiled diagnostic pipeline, buffer 13 at offset zero.
 * At >=MCMTLFarShadeDiagnosticFullBytes (384), flags bit 1 also enables the
 * first reason-9 sample at byte 64. Buffers of 64..383 bytes remain counters-only.
 * No internal readback/wait/allocation. Caller zeros 64 bytes, or the entire
 * 384 bytes for samples, before encoding, owns them through actual CB completion, and
 * reads only after completion (synchronize managed storage / blit private).
 * Use a separate buffer per layer and in-flight image. A retained-reference CB
 * pins the buffer. Counters are uint32_t[16], MCMTLFarShadeDiagnostic indices;
 * their sum is viewport pixel count for one completed encode. These diagnose
 * first rejection only and never change the 0/1/2/3 mask or admission policy.
 */
- (BOOL)encodeToCommandBuffer:(id<MTLCommandBuffer>)buffer
                      geometry:(MCMTLFarShadeGeometry *)geometry
                        source:(MCMTLFarShadeSource)source
                       targets:(MCMTLFarShadeTargets)targets
                      uniforms:(const MCMTLFarShadeUniforms *)uniforms
                     materials:(MCMTLFarShadeMaterials)materials
                   diagnostics:(id<MTLBuffer>)diagnostics error:(NSError **)error;
@end

@interface MCMTLFarShadeGeometry : NSObject
- (instancetype)init NS_UNAVAILABLE;
+ (instancetype)new NS_UNAVAILABLE;
@property(nonatomic, readonly) MCMTLFarFaceLookup *lookup;
@property(nonatomic, readonly) uint64_t ownedBufferBytes;
@end
