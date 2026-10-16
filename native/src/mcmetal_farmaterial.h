/* Prompt 5: built-in atlas/lightmap OWNED pixel versions. No stream/queue access. */
#pragma once
#include "mcmetal.h"

enum {
    MCMTLFarMaterialAtlas = 0, MCMTLFarMaterialLightmap = 1,
    MCMTLFarMaterialCount = 2, MCMTLFarMaterialMaxCopyJobs = 30
};

/* Borrowed live handles at pin time. texture is the actual sampling handle:
 * parent for a full range, or a direct, identity-swizzle view of parent.
 * The pin retains all three objects; the caller must keep them live during pin.
 * sampler is already immutable; its exact descriptor is preserved by retention.
 * The optional encoder LOD override is separate from the sampler's own clamps.
 */
typedef struct {
    mcmtl_handle parent, texture, sampler;
    uint32_t baseLevel, levelCount, samplerLODClamp;
    float lodMin, lodMax;
    uint32_t reserved; /* zero */
} mcmtl_far_material_source;

/* Immutable value metadata, returned by copy (never a mutable Metal descriptor).
 * All parent mip levels are copied, including levels outside the sampling view.
 */
typedef struct {
    uint32_t width, height, pixelFormat, mipLevels, baseLevel, levelCount;
    uint32_t samplerLODClamp;
    float lodMin, lodMax;
    uint32_t reserved;
} mcmtl_far_material_desc;

/* Borrowed handles valid while the version is retained. Before publication these
 * are copy targets only. After publication they are READ ONLY, including every
 * parent mip and any subsequently created aliases. Never initialize, clear,
 * upload, generate mips, make purgeable or write these allocations again.
 */
typedef struct {
    mcmtl_handle parent, texture, sampler;
    mcmtl_far_material_desc desc;
} mcmtl_far_material_binding;

/* One full 2D mip copy, slice/origin zero, depth one, same level on both parents.
 * Borrowed handles valid only while the prepared version is retained and has
 * not been published/failed. Record EVERY job on the MAIN existing stream, after
 * all source writes/dependencies and before the ticket's signal. The coordinator
 * must freeze/revalidate the intended source revision until recording; pinning
 * alone does not freeze pixels. No copy encoder is created by this component.
 */
typedef struct {
    mcmtl_handle source, destination;
    uint32_t materialIndex, level, width, height;
} mcmtl_far_material_copy_job;

/* CONTRACT supplied by the coordinator, NOT native evidence of commitment:
 * committed == 1 attests that every job and all its inputs precede event/value
 * in an ALREADY SUCCESSFULLY COMMITTED main-stream submission ticket, and that
 * no future writer will touch any destination. copyCount must equal copyJobCount.
 * A Java ticket cannot be proved from a bare native event/value. Do not use
 * signaledValue as a substitute (a committed submission may still be pending).
 * Failed/abandoned submissions must never publish a version. Retained references
 * or Java deferred releases must protect both parents through stream decoding;
 * publication releases the version's source recipe. Keep the version alive
 * through GPU completion and every far-image/ID user, and pass this exact
 * event/value to mcmtl_farproducer_encode for its far-queue wait.
 */
typedef struct {
    mcmtl_handle event;
    uint64_t value;
    uint32_t copyCount, committed;
} mcmtl_far_material_copy_dependency;

/* Optional FFM bridge, ABI 2 unchanged. Errors are local, never decoder TLS.
 * All object inputs must be valid live handles of the declared type.
 * pin: render thread, CPU metadata/retains only; exactly two sources.
 * prepare: worker only, private tracked 2D allocations/views, no GPU submission.
 * budgetBytes bounds total logical RGBA mip bytes (driver padding is excluded).
 * Supported: device-owned tracked 2D RGBA8Unorm, single sample/slice, <=16384.
 * Rejects heaps/aliases, external/buffer/sparse/memoryless textures, nested or
 * reinterpreted/swizzled views, mismatched devices and invalid view/clamp bounds.
 */
MCMTL_API int32_t mcmtl_ext_far_material_pin(mcmtl_handle device,
    const mcmtl_far_material_source *sources, uint64_t budgetBytes, mcmtl_handle *outRecipe,
    char *error, uint32_t errorCapacity);
MCMTL_API int32_t mcmtl_ext_far_material_prepare(mcmtl_handle recipe, mcmtl_handle *outVersion,
    char *error, uint32_t errorCapacity);
MCMTL_API int32_t mcmtl_ext_far_material_binding(mcmtl_handle version, uint32_t index,
    mcmtl_far_material_binding *outBinding);
MCMTL_API uint32_t mcmtl_ext_far_material_copy_count(mcmtl_handle version);
MCMTL_API int32_t mcmtl_ext_far_material_copy_job(mcmtl_handle version, uint32_t index,
    mcmtl_far_material_copy_job *outJob);
/* Each success returns a new +1 parent handle for Java's MetalTexture constructor.
 * Wrap directly with copy-destination usage; do not schedule normal texture init.
 * Java owns/deferred-releases this additional reference, independently of lease.
 */
MCMTL_API int32_t mcmtl_ext_far_material_retain_parent(mcmtl_handle version, uint32_t index,
    mcmtl_handle *outParent);
MCMTL_API int32_t mcmtl_ext_far_material_publish(mcmtl_handle version,
    const mcmtl_far_material_copy_dependency *dependency, char *error, uint32_t errorCapacity);
/* Any thread; once per +1 recipe/version. Parent handles use normal mcmtl_release. */
MCMTL_API void mcmtl_ext_far_material_drop(mcmtl_handle object);

#ifdef __OBJC__
#import "mcmetal_farproducer.h"

@interface MCMTLFarMaterialRecipe : NSObject
- (instancetype)init NS_UNAVAILABLE;
+ (instancetype)new NS_UNAVAILABLE;
- (instancetype)initWithDevice:(id<MTLDevice>)device
                        sources:(const mcmtl_far_material_source *)sources
                    budgetBytes:(uint64_t)budgetBytes error:(NSError **)error;
@property(nonatomic, readonly) id<MTLDevice> device;
@property(nonatomic, readonly) uint64_t copyBytes;
- (mcmtl_far_material_desc)descriptorAtIndex:(NSUInteger)index;
- (id<MTLTexture>)sourceParentAtIndex:(NSUInteger)index;
- (id<MTLTexture>)sourceTextureAtIndex:(NSUInteger)index;
- (id<MTLSamplerState>)samplerAtIndex:(NSUInteger)index;
@end

@interface MCMTLFarMaterialVersion : NSObject <MCMTLFarMaterialLease>
- (instancetype)init NS_UNAVAILABLE;
+ (instancetype)new NS_UNAVAILABLE;
- (instancetype)initWithRecipe:(MCMTLFarMaterialRecipe *)recipe error:(NSError **)error;
@property(nonatomic, readonly) NSUInteger copyJobCount;
@property(nonatomic, readonly, getter=isPublished) BOOL published;
@property(nonatomic, readonly) id<MTLSharedEvent> readyEvent;
@property(nonatomic, readonly) uint64_t readyValue;
- (mcmtl_far_material_binding)bindingAtIndex:(NSUInteger)index;
- (BOOL)copyJobAtIndex:(NSUInteger)index into:(mcmtl_far_material_copy_job *)job;
/* One attempt, serialized with all version access by the capture coordinator.
 * Invalid first publication permanently fails the prepared version. Repeat
 * publication returns NO; an existing successful immutable lease stays valid.
 * This does not encode, commit, enqueue, wait, poll or perform CPU readback.
 */
- (BOOL)publishCommittedCopies:(mcmtl_far_material_copy_dependency)dependency error:(NSError **)error;
@end
#endif
