/* Immutable producer inputs and private cull outputs. Internal; no public ABI. */
#pragma once
#import "mcmetal_clusters.h"
#import <Foundation/Foundation.h>

/* A temporary admission miss, not invalid geometry or allocation failure. */
enum { MCMTLFarSnapshotCapacityError = 2 };

/* Capture on the thread owning the live tables, BEFORE it can mutate them. Items
 * are copied here; vertex/index storage is retained, not copied. lifetime must
 * own the corresponding allocation-range lease, not just these Metal objects.
 * Disjoint arenas need disjoint faceBase ranges in one image generation.
 */
@interface MCMTLFarClusterInput : NSObject
- (instancetype)init NS_UNAVAILABLE;
+ (instancetype)new NS_UNAVAILABLE;
- (instancetype)initWithItems:(NSData *)items
                     vertices:(id<MTLBuffer>)vertices vertexOffset:(NSUInteger)vertexOffset
                       indices:(id<MTLBuffer>)indices indexOffset:(NSUInteger)indexOffset
                     layerCode:(uint32_t)layerCode faceBase:(uint32_t)faceBase
                      lifetime:(id)lifetime;
@property(nonatomic, readonly) NSData *items;
@property(nonatomic, readonly) id<MTLBuffer> vertices;
@property(nonatomic, readonly) id<MTLBuffer> indices;
@property(nonatomic, readonly) NSUInteger vertexOffset, indexOffset;
@property(nonatomic, readonly) uint32_t layerCode, faceBase;
@end

typedef struct {
    __unsafe_unretained MCMTLFarClusterInput *input;
    __unsafe_unretained id<MTLBuffer> quads, items, arguments;
    uint32_t quadCapacity;
} MCMTLFarDraw;

/* Immutable GPU tables/items, validated and uploaded once. Retains the input
 * objects (and therefore their allocation-range leases). Construct on a worker;
 * inputs/tables must remain stable until return. No queue/encoding/submission.
 * The budget covers these GPU buffers plus ALL live snapshots' private buffers,
 * excluding borrowed geometry and CPU metadata. Admission is nonblocking, at
 * most three snapshots, possibly fewer when the budget only fits one or two.
 * No output pooling. A new origin/table/geometry/fade recipe needs a new owner.
 * The cull wrapper and its Metal device must remain unchanged for its lifetime.
 */
@interface MCMTLFarCullGeometry : NSObject
- (instancetype)init NS_UNAVAILABLE;
+ (instancetype)new NS_UNAVAILABLE;
- (instancetype)initWithDevice:(MCMTLDevice *)device
                         inputs:(NSArray<MCMTLFarClusterInput *> *)inputs
                   cullSections:(NSData *)cullSections shaderSections:(NSData *)shaderSections
                    budgetBytes:(uint64_t)budgetBytes error:(NSError **)error;
@property(nonatomic, readonly) MCMTLDevice *cullDevice;
@property(nonatomic, readonly) NSUInteger count, snapshotCapacity;
@property(nonatomic, readonly) uint64_t budgetBytes, immutableBufferBytes, snapshotBufferBytes;
@end

/* Construct on the producer worker from OWNED, immutable CPU snapshots. Never
 * hand a no-copy view of live Java memory to this constructor. Each instance is
 * single-use for culling, has its own outputs, and may remain image-owned for
 * future ID shading. No main-queue command buffer, decoder state or timeline is
 * created/changed. Tables: 32-byte cull section entries, 16-byte shader entries.
 * Caller captures all entries through the highest live slot, with matching IDs.
 * Initial complete capture uses frustum/facing culling, not near-depth history.
 */
@interface MCMTLFarCullSnapshot : NSObject
- (instancetype)init NS_UNAVAILABLE;
+ (instancetype)new NS_UNAVAILABLE;
- (instancetype)initWithDevice:(MCMTLDevice *)device
                         inputs:(NSArray<MCMTLFarClusterInput *> *)inputs
                   cullSections:(NSData *)cullSections
                 shaderSections:(NSData *)shaderSections
                           view:(NSData *)view
                    budgetBytes:(uint64_t)budgetBytes error:(NSError **)error;
/* Refresh: copies/validates only the 128-byte view and allocates fresh private
 * outputs, O(input count), with no metadata scan/copy/upload. budgetBytes is the
 * per-packet limit (shared immutable bytes + this snapshot's private bytes).
 * Retain the snapshot through producer completion AND every visibility-ID
 * consumer's completion; dropping the recipe alone cannot release its inputs.
 * A declined/failed snapshot returns its admission slot without waiting.
 */
- (instancetype)initWithGeometry:(MCMTLFarCullGeometry *)geometry view:(NSData *)view
                     budgetBytes:(uint64_t)budgetBytes error:(NSError **)error;
@property(nonatomic, readonly) NSUInteger count;
@property(nonatomic, readonly) MCMTLDevice *cullDevice;
@property(nonatomic, readonly) MCMTLFarCullGeometry *geometry;
/* Conservative per-packet charge, including shared immutableBufferBytes, as in
 * the original constructor. For aggregate accounting charge geometry once and
 * geometry.snapshotBufferBytes for each live snapshot. */
@property(nonatomic, readonly) uint64_t ownedBufferBytes;
/* Borrowed read-only table: callers must never mutate its contents. */
@property(nonatomic, readonly) id<MTLBuffer> shaderSections;
- (MCMTLFarDraw)drawAtIndex:(NSUInteger)index;

/* Worker only; once. readyValue must identify an ALREADY successfully committed
 * main submission containing the source uploads/material copies. This method
 * cannot prove submission from a bare event value: caller must use the encoder's
 * submission ticket. It encodes a GPU wait, never a CPU wait or hidden commit.
 * On failure discard the entire command buffer. Geometry leases stay held through
 * every consumer of the resulting IDs, not merely through this producer.
 */
- (BOOL)encodeCull:(id<MTLCommandBuffer>)buffer
         readyEvent:(id<MTLSharedEvent>)readyEvent readyValue:(uint64_t)readyValue
              error:(NSError **)error;
@end
