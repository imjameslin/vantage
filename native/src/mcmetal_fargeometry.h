/* Render-thread native pins for Java LodFarGeometry. No Metal allocation/encoding. */
#pragma once
#include "mcmetal.h"
#include <stddef.h>

typedef struct {
    mcmtl_handle vertices;
    uint64_t vertexOffset, vertexBytes;
    mcmtl_handle indices;
    uint64_t indexOffset, indexBytes;
    uint32_t layerCode, faceBase;
} mcmtl_far_geometry_input;

/* Pins EXACT current buffers and allocation lease before Java can rebind/close
 * them. O(input count), no walk/copy of cluster/section tables on the game thread.
 * On success native owns releaseToken; on failure Java still owns it. The token
 * must represent a real LodGeometryLeases lease, not only a generation number.
 */
MCMTL_API int32_t mcmtl_ext_far_geometry_pin(mcmtl_handle device, uint64_t releaseToken,
    const mcmtl_far_geometry_input *inputs, uint32_t count, mcmtl_handle *outPins);
typedef struct { const void *bytes; uint64_t length; } mcmtl_far_bytes;
/* Worker-only metadata copy. Returns +1 MCMTLFarGeometryRecipe. Dropping pins
 * afterwards does not release the allocation until the recipe/descendants end. */
MCMTL_API int32_t mcmtl_ext_far_geometry_capture(mcmtl_handle pins,
    const mcmtl_far_bytes *items, uint32_t count, const mcmtl_far_bytes *tables, uint64_t budget,
    mcmtl_handle *outRecipe, char *error, uint32_t errorCapacity);
MCMTL_API void mcmtl_ext_far_geometry_drop(mcmtl_handle pins);
/* Borrowed event from a retained worker recipe (not a pins object). */
MCMTL_API mcmtl_handle mcmtl_ext_far_geometry_event(mcmtl_handle recipe);
/* Render thread, including menus. Bounded lock-free scan, at most three tokens.
 * Full registry declines capture; an existing release is never overwritten. */
MCMTL_API uint32_t mcmtl_ext_far_geometry_poll(uint64_t *tokens, uint32_t capacity);

#ifdef __OBJC__
#import "mcmetal_farsnapshot.h"
@interface MCMTLFarGeometryRecipe : NSObject
@property(nonatomic, readonly) id<MTLDevice> device;
@property(nonatomic, readonly) id<MTLSharedEvent> uploadEvent;
@property(nonatomic, readonly) NSArray<MCMTLFarClusterInput *> *inputs;
@property(nonatomic, readonly) NSData *cullSections, *shaderSections, *view;
/* Optional worker-only preparation, cached after its first successful call.
 * Validates/uploads immutable tables/items once; later calls return the SAME
 * owner without walking metadata. The first success fixes the cull wrapper and
 * aggregate GPU buffer budget; mismatches and concurrent preparation decline.
 * Use an isolated prepared producer cull wrapper. Does not prepare PSOs, create
 * queues, encode, commit, wait, or change decoder state. No new C ABI required.
 * Refresh via MCMTLFarCullSnapshot initWithGeometry:view:budgetBytes:error: with
 * a fresh owned 128-byte view; recipe.view remains the original captured view.
 * Caller replaces this recipe when its leased geometry, section tables (incl.
 * render/cull origin basis), face IDs, selection or fade metadata changes.
 */
- (MCMTLFarCullGeometry *)prepareCullGeometryWithDevice:(MCMTLDevice *)device
    budgetBytes:(uint64_t)budget error:(NSError **)error;
@end
@interface MCMTLFarGeometryPins : NSObject
/* Worker: Java geometry metadata must already be immutable. All bytes are copied
 * before this method returns, including no-copy NSData wrappers. Geometry itself
 * is never copied. Descendant inputs retain the range lease after pins is dropped.
 * No GPU resources, queues, submits, waits or error-TLS mutation occur here. */
- (MCMTLFarGeometryRecipe *)captureItems:(NSArray<NSData *> *)items
    cullSections:(NSData *)cull shaderSections:(NSData *)shader view:(NSData *)view
    budgetBytes:(uint64_t)budget error:(NSError **)error;
@end
#endif
