/* Prompt 5 phase 1. Internal worker capture/encoding; no game/decoder integration. */
#pragma once
#import "mcmetal_farimage.h"
#import "mcmetal_farsnapshot.h"
#import "mcmetal_farshader.h"

/* Worker setup only. Creates an isolated native wrapper for this Metal device
 * and prepares its cull PSOs, without creating a queue or touching a live/main
 * MCMTLDevice's associated PSO cache. Share one prepared wrapper on the worker.
 * The second factory constructs snapshots with this wrapper. Packet construction
 * checks the actual snapshot.cullDevice identity (direct construction with this
 * same prepared wrapper also works).
 */
MCMTLDevice *mcmtl_farproducer_cull_device(id<MTLDevice> device, NSError **error);
MCMTLFarCullSnapshot *mcmtl_farproducer_snapshot(MCMTLDevice *cullDevice,
    NSArray<MCMTLFarClusterInput *> *inputs, NSData *cullSections, NSData *shaderSections,
    NSData *view, uint64_t budgetBytes, NSError **error);

/* A REAL material-version lease, implemented by the caller's snapshot owner.
 * YES promises exclusive immutable pixel storage (including mip levels, parent
 * textures and heap aliases) until this lease is destroyed. Retaining a live
 * atlas/lightmap is NOT this promise. For copied versions, the supplied committed
 * upload event must follow the copy and all its inputs; later main work may only
 * change the original. The packet retains this lease through all image/ID users.
 */
@protocol MCMTLFarMaterialLease <NSObject>
- (BOOL)ownsImmutableFarTexture:(id<MTLTexture>)texture;
@end

enum { MCMTLFarProducerBufferCount = 31, MCMTLFarProducerTextureCount = 16 };
typedef struct {
    /* Exact bound ranges, copied even for no-copy NSData wrappers. A captured
     * buffer binding supplies its bytes starting at its ORIGINAL bound offset.
     * Supply the actual dummy bytes too when the original decoder bound a dummy.
     * Vertex 30/29/28/27 and 6 are supplied exclusively by the producer.
     * Push constants, if used, are supplied at buffer 14 for each declared stage.
     */
    __unsafe_unretained NSData *buffers[MCMTLFarProducerBufferCount];
    __unsafe_unretained id<MTLTexture> textures[MCMTLFarProducerTextureCount];
    __unsafe_unretained id<MTLSamplerState> samplers[MCMTLFarProducerTextureCount];
    /* Preserve set*SamplerState:lodMinClamp:lodMaxClamp: when it was used. */
    BOOL samplerLODClamp[MCMTLFarProducerTextureCount];
    float samplerLODMin[MCMTLFarProducerTextureCount], samplerLODMax[MCMTLFarProducerTextureCount];
} MCMTLFarProducerBindings;

typedef struct {
    __unsafe_unretained MCMTLFarShader *shader;
    MCMTLFarProducerBindings stages[2]; /* 0 vertex, 1 fragment */
    MTLViewport viewport;
    MTLScissorRect scissor;
    MTLDepthClipMode depthClipMode;
} MCMTLFarProducerDraw;

typedef struct {
    MTLClearColor color[MCMTLFarImageLayerCount];
    double depth[MCMTLFarImageLayerCount]; /* Exact source clear; reverse Z commonly 0. */
} MCMTLFarProducerClears;

@interface MCMTLFarProducerPacket : NSObject
- (instancetype)init NS_UNAVAILABLE;
+ (instancetype)new NS_UNAVAILABLE;
/* Worker only. One draw per snapshot input, in ORIGINAL order; no sorting,
 * density reduction or new projection. Caller supplies the already-captured
 * guard-band projection bytes and corresponding cull view/viewport/scissor.
 * Exactly SOLID and CUTOUT are represented; water/translucency and dither-fade
 * are unsupported (layerCode bit16 is rejected, as are faded far shaders).
 *
 * cullDevice MUST come from the worker factory above; snapshot.cullDevice must
 * equal that exact wrapper. Raw/main wrappers and unrelated snapshots
 * fail before allocation/encoding. No shader compilation occurs in this init.
 * Geometry/index ranges must remain leased; indices use 4*q+{0,1,2,2,3,0}.
 * The packet copies metadata/uniforms, owns its private uniform buffers, retains
 * shaders/material versions/snapshot, and should be the far-image inputs owner
 * (or be retained by that owner). Never mutate exposed snapshot GPU buffers.
 */
- (instancetype)initWithCullDevice:(MCMTLDevice *)cullDevice
                          snapshot:(MCMTLFarCullSnapshot *)snapshot
                             draws:(const MCMTLFarProducerDraw *)draws count:(NSUInteger)count
                             width:(NSUInteger)width height:(NSUInteger)height
                            clears:(MCMTLFarProducerClears)clears
                     materialLease:(id<MCMTLFarMaterialLease>)materialLease
                             error:(NSError **)error;
@property(nonatomic, readonly) MCMTLFarCullSnapshot *snapshot;
@property(nonatomic, readonly) NSUInteger count;
@end

/* Worker, encode-only, one successful attempt per snapshot. Prevalidates the
 * ENTIRE packet/image/dependency before touching the command buffer. Exactly one
 * cull invocation, then one indirect ORIGINAL MRT draw per input, split into two
 * clear/store passes, even if a layer is empty. No commit/enqueue/CPU wait, queue
 * creation, compilation, decoder/timeline mutation, or projection adjustment.
 *
 * Caller must own the unqueued retained-reference buffer, with no open encoder,
 * and keep packet alive through GPU completion and every image/ID consumer.
 * readyEvent/value MUST come from an ALREADY SUCCESSFULLY COMMITTED submission
 * containing source uploads/material copies. Bare event values cannot prove
 * commitment: enforce this using the parent's submission ticket, never by
 * checking signaledValue (committed work need not have completed).
 * Any NO after encoding begins requires discarding the whole command buffer.
 */
BOOL mcmtl_farproducer_encode(MCMTLFarProducerPacket *packet, MCMTLFarCullSnapshot *snapshot,
    id<MTLCommandBuffer> buffer, MCMTLFarImageView image,
    id<MTLSharedEvent> readyEvent, uint64_t readyValue, NSError **error);
