/*
 * Prompt 5, phase 1: internal ARC ownership only; no public ABI or decoder state.
 * Create the owner (including all 18 targets) and reservations on a worker.
 * No scheduling priority is implied by the separate Metal queue. The caller owns
 * display-rate pacing and coalescing into ONE latest pending immutable snapshot.
 */
#ifndef MCMETAL_FARIMAGE_H
#define MCMETAL_FARIMAGE_H

#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#include <stdint.h>

enum {
    MCMTLFarImageSolid = 0,
    MCMTLFarImageCutout = 1,
    MCMTLFarImageLayerCount = 2,
    MCMTLFarImageSlotCount = 3
};

typedef struct {
    uint64_t epoch;               /* Caller advances on world/resource/eligibility changes; nonzero. */
    uint64_t sourceTimeNs;        /* Source CAMERA time in caller's monotonic clock, not completion. */
    uint64_t sourceFrame;
    uint64_t geometryGeneration;  /* Informational identities; these numbers do not lease storage. */
    uint64_t materialGeneration;
} MCMTLFarImageSource;

/* Borrowed view: only valid during the producer block or while its consumer lease
 * is held. Retaining a texture alone does NOT prevent this slot being overwritten.
 * SOLID/CUTOUT stay separate; composition order/depth equality remain caller duties.
 * No translucent representation is provided. Targets are private, tracked, single
 * sample 2D textures: RGBA8Unorm / Depth32Float / R32Uint, RenderTarget|ShaderRead.
 */
typedef struct {
    __unsafe_unretained id<MTLTexture> color[MCMTLFarImageLayerCount];
    __unsafe_unretained id<MTLTexture> depth[MCMTLFarImageLayerCount];
    __unsafe_unretained id<MTLTexture> visibilityID[MCMTLFarImageLayerCount];
    __unsafe_unretained id inputs;
    MCMTLFarImageSource source;
    uint64_t sequence;            /* Unique within this owner; never wraps or crosses owners. */
    NSUInteger width, height;    /* Caller-supplied guard-band dimensions. */
} MCMTLFarImageView;

enum {
    MCMTLFarImageTimingCPUValid = 1u,
    MCMTLFarImageTimingGPUValid = 2u
};
/* Owned CPU metadata, finalized before COMPLETE publication. CPU samples use
 * CLOCK_UPTIME_RAW; source time and sequence remain in the matching image view.
 * GPU times are completed-buffer driver seconds converted to ns. Their span
 * includes waits/contended execution and is not a pure GPU-work measurement.
 * Completion can precede commit return; timestamps are never clamped/reordered.
 */
typedef struct {
    uint64_t reserveTimeNs, encodeStartNs, commitStartNs, commitEndNs;
    uint64_t gpuStartNs, gpuEndNs, completionNs;
    uint32_t flags;
} MCMTLFarImageTiming;

@class MCMTLFarImageReservation, MCMTLFarImageLease;

@interface MCMTLFarImageOwner : NSObject
- (instancetype)init NS_UNAVAILABLE;
+ (instancetype)new NS_UNAVAILABLE;

/* Worker only. Dimensions are immutable. For resize, create a replacement on the
 * worker, stop the old owner, then exchange the caller's active-owner reference.
 * Serialize that exchange/epoch change with final consumer attachment. Existing
 * admitted GPU uses may finish; stop/invalidation cannot retract submitted work.
 * error is optional and construction errors never go through mcmtl error TLS.
 */
- (instancetype)initWithDevice:(id<MTLDevice>)device
                         width:(NSUInteger)width
                        height:(NSUInteger)height
                         epoch:(uint64_t)epoch
                         error:(NSError **)error;

/* Worker-created, single-use after successful admission. Failed offers may retry.
 * The caller transfers a reservation between threads using its own synchronization.
 */
- (MCMTLFarImageReservation *)newReservation;

/* Game-frame offer: try-lock, fixed three-slot scan, metadata copy and retains only.
 * NO allocations, command-buffer creation, compilation, GPU/CPU wait or eviction.
 * NO means contention, stale epoch, stopped owner, occupied producer, or no FREE
 * slot. Keep/coalesce the latest pending snapshot outside this owner and retry.
 * At most ONE reservation/encoding/submitted producer exists, even across epochs.
 *
 * inputs must be an already-created immutable snapshot owning uniform bytes,
 * camera/origin/projections, cull/item/section data, pipeline variants, and REAL
 * geometry allocation-range/epoch leases and immutable material texture versions
 * (or correctly ordered copies). Retaining Metal buffers is NOT geometry range
 * leasing, and generation numbers do not freeze arena ranges or atlas pixels.
 * inputs is retained until the image is retired and every reservation/consumer is
 * gone, including consumers of its IDs. It must not retain this owner or its leases.
 * Destruction can occur on completion threads; caller leases must support that.
 */
- (BOOL)tryReserve:(MCMTLFarImageReservation *)reservation
            source:(MCMTLFarImageSource)source
            inputs:(id)inputs;

/* Nonblocking selection (try-lock); may allocate a consumer lease. Only successful
 * COMPLETE images in epoch are selected, newest then older by source frame/time,
 * with sequence breaking ties. Nil on miss; count is otherwise 1 or 2, never aliases.
 * The lease pins both images even before attachment. Drop it to abandon selection.
 */
- (MCMTLFarImageLease *)tryAcquireForEpoch:(uint64_t)epoch;

/* Immediately revoke eligibility, never free pinned/in-flight storage or wait for
 * GPU work. A strictly greater epoch is required (NO on equal/older/stopped).
 * stop is permanent and idempotent; use it for shutdown or owner replacement.
 */
- (BOOL)invalidateToEpoch:(uint64_t)epoch;
- (void)stop;

/* Bounded local diagnostic: latest producer/consumer error, nil until failure.
 * Read off the offer path. Completion handlers never touch unrelated error TLS.
 */
- (NSError *)lastError;
@end

@interface MCMTLFarImageReservation : NSObject
- (instancetype)init NS_UNAVAILABLE;
+ (instancetype)new NS_UNAVAILABLE;

/* Worker only, once after admission. The owner creates a retained-reference command
 * buffer on its own queue, passes it here, appends its private event signal, installs
 * completion tracking and commits. YES means commit accepted, NOT image publication.
 * Return NO from encode to discard. Set its optional error for a local diagnostic.
 * End ALL encoders before returning, on failure too. Initialize/store all six targets
 * (including empty layers); no prior pixel contents are promised on slot reuse.
 *
 * The block may ONLY encode: do not enqueue, commit, retain, wait on, or asynchronously
 * use the buffer/view. Use an autorelease pool on the worker. GPU upload waits, if
 * needed, must depend on an ALREADY COMMITTED main-queue submission. Never make the
 * main queue wait for this producer's future work. No shader compilation here is
 * needed by the owner; caller pipelines must already exist and be snapshot-owned.
 *
 * Dropping an unsubmitted reservation cancels it. Keeping ANY accepted reservation
 * alive pins its slot, even after invalidation, failure or completion; release it
 * after this method returns. Owner/callback references keep pending work alive.
 */
- (BOOL)encodeAndSubmit:(BOOL (^)(id<MTLCommandBuffer> buffer,
                                MCMTLFarImageView image, NSError **error))encode;
@end

@interface MCMTLFarImageLease : NSObject
- (instancetype)init NS_UNAVAILABLE;
+ (instancetype)new NS_UNAVAILABLE;
@property(nonatomic, readonly) NSUInteger count;
- (MCMTLFarImageView)imageAtIndex:(NSUInteger)index;
/* Bounded immutable receipt copy, no locks/Metal calls; NO zeros the output.
 * The same lease/actual-consumer-completion lifetime protects image and timing. */
- (BOOL)timingAtIndex:(NSUInteger)index into:(MCMTLFarImageTiming *)outTiming;

/* Once, BEFORE encoding any reads, on the thread exclusively owning this unqueued
 * command buffer, on the same Metal device. Rechecks epoch/stop eligibility; NO
 * means do not use these images (discard the buffer if handler installation failed).
 * On YES, encode reads and commit normally. The actual completion handler pins this
 * lease until completion, even if the caller drops it or the owner is invalidated.
 * An abandoned uncommitted buffer must be released to drop its handler/pin. Holding
 * the lease yourself also continues to pin storage. For another command buffer,
 * acquire another lease. Consumers may READ targets/inputs only; never mutate them.
 * Invalidation after this admission allows that already-admitted use to finish.
 */
- (BOOL)attachToCommandBuffer:(id<MTLCommandBuffer>)buffer;
@end

#endif /* MCMETAL_FARIMAGE_H */
