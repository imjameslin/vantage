/* Exact-camera material-alpha prerequisite. No queue or consumer integration. */
#pragma once
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>

@interface MCMTLFarAlpha : NSObject
- (instancetype)init NS_UNAVAILABLE;
+ (instancetype)new NS_UNAVAILABLE;
/* Worker only: compiles the immutable compute PSO, creates no queue. */
- (instancetype)initWithDevice:(id<MTLDevice>)device error:(NSError **)error;
@property(nonatomic, readonly) id<MTLDevice> device;

/* GPU-only exact alpha comparison; RGB is ignored. Compares EVERY texel of
 * EVERY mip in both pairs, including lightmap alpha. Pass the full parent
 * textures, not sampling views. Each old/current pair must match dimensions
 * and mip count: tracked, identity-swizzle RGBA8Unorm, ShaderRead, standalone
 * 2D, single slice/sample, <=16384 per dimension. Partial mip chains are valid
 * if identical in length. Views, heaps, buffer-backed/external/sparse textures
 * and memoryless storage are unsupported. Input read aliases are allowed.
 *
 * result is caller-owned, same-device, tracked standalone MTLBuffer, >=4 bytes,
 * shared/private/managed, not sparse/heap/aliasable/remote. Offset zero is the
 * uint32 mismatch word: a blit clears exactly 4 bytes, then compute atomically
 * ORs 1 for any changed alpha. Other bytes are untouched. Zero is meaningful
 * ONLY after successful GPU execution and with the caller's ordering below.
 *
 * Requires an unqueued retained-reference command buffer with no open encoder.
 * Validates every resource before creating any encoder. No resource allocation,
 * CPU pixel/result access, queue creation, enqueue, commit, or wait occurs here.
 * The command buffer retains the PSO/textures/result through completion; the
 * caller ALSO keeps the source image's immutable material lease until complete.
 * Never write old materials or their aliases. Current material writes must
 * precede this pass on the SAME queue (or an explicit committed dependency),
 * and guard-result consumption/re-shading must follow it, with no intervening
 * current-material writes. A pending main-stream write is not such a dependency.
 * Keep result exclusive until its GPU consumers finish; don't concurrently
 * overwrite, purge, alias, or read back any in-flight resources.
 *
 * Alpha equality is only one prerequisite: the caller must separately establish
 * the same exact camera, geometry (including vertex color alpha), visibility
 * inputs, shader/raster state, and sampling/view/LOD clamps. This does not prove
 * coverage after rotation/translation or recover previously absent geometry.
 * Any NO after encoder creation requires discarding the entire command buffer.
 */
- (BOOL)encodeToCommandBuffer:(id<MTLCommandBuffer>)buffer
                    oldAtlas:(id<MTLTexture>)oldAtlas
                currentAtlas:(id<MTLTexture>)currentAtlas
                    oldLight:(id<MTLTexture>)oldLight
                currentLight:(id<MTLTexture>)currentLight
                      result:(id<MTLBuffer>)result error:(NSError **)error;
@end
