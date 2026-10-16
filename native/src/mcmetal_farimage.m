/*
 * FREE -> PRODUCING -> COMPLETE (only commit + completion success) -> RETIRING
 *      -> FREE (producer finished AND pins == 0).
 * Invalidation can move PRODUCING/COMPLETE to RETIRING but cannot remove either
 * producer work or pins. Two COMPLETE images are kept; publication retires a third.
 * Failed/cancelled producers retire without displacing either successful image.
 *
 * One lock guards all mutable lifecycle state. Frame offers and selection try it;
 * no Metal call, client encoding, or snapshot destruction happens under this lock.
 * A slot generation identifies each reservation and callback; it never wraps.
 * Reservation/lease -> owner -> slots. Owner never owns a reservation, lease or
 * command buffer. Producer handlers hold the owner; consumer handlers hold a lease
 * until their body ends. These edges pin storage without a permanent retain cycle.
 * Commit resolution and GPU completion may race: BOTH must resolve before settling
 * a producer. A throwing commit with no terminal callback deliberately quarantines
 * its slot/admission rather than guessing whether the GPU can still touch it.
 */
#import "mcmetal_farimage.h"
#include <os/lock.h>
#include <limits.h>
#include <math.h>
#include <time.h>

typedef NS_ENUM(uint8_t, FarState) { FarFree, FarProducing, FarComplete, FarRetiring };
typedef NS_ENUM(uint8_t, FarProducer) { FarIdle, FarReserved, FarEncoding, FarSubmitted };

@interface MCMTLFarImageSlot : NSObject {
@public
    __strong id<MTLTexture> color[MCMTLFarImageLayerCount];
    __strong id<MTLTexture> depth[MCMTLFarImageLayerCount];
    __strong id<MTLTexture> visibilityID[MCMTLFarImageLayerCount];
    id inputs;
    MCMTLFarImageSource source;
    MCMTLFarImageTiming timing;
    uint64_t generation;
    NSUInteger pins;
    FarState state;
    FarProducer producer;
    BOOL commitResolved, commitOK, gpuDone, gpuOK;
}
@end
@implementation MCMTLFarImageSlot
@end

@interface MCMTLFarImageOwner () {
@public /* Implementation-private, shared only with the two lease classes below. */
    os_unfair_lock _lock;
    id<MTLDevice> _device;
    id<MTLCommandQueue> _queue;
    id<MTLSharedEvent> _event;
    MCMTLFarImageSlot *__strong _slots[MCMTLFarImageSlotCount];
    NSUInteger _width, _height;
    uint64_t _epoch, _nextGeneration;
    NSInteger _activeSlot;
    BOOL _stopped;
    NSError *_lastError;
}
- (void)recycleRetired;
- (void)recordError:(NSError *)error;
- (void)settleLocked:(NSUInteger)index;
- (void)abortSlot:(NSUInteger)index generation:(uint64_t)generation error:(NSError *)error;
- (void)completedSlot:(NSUInteger)index generation:(uint64_t)generation
             success:(BOOL)success error:(NSError *)error timing:(MCMTLFarImageTiming)timing;
- (void)releasePin:(NSUInteger)index generation:(uint64_t)generation abandon:(BOOL)abandon;
@end

@interface MCMTLFarImageReservation () {
@public
    MCMTLFarImageOwner *_owner;
    NSUInteger _index;
    uint64_t _generation; /* Zero until admission; this object is never reused after admission. */
}
- (instancetype)initWithOwner:(MCMTLFarImageOwner *)owner;
@end

@interface MCMTLFarImageLease () {
@public
    MCMTLFarImageOwner *_owner;
    NSUInteger _count, _indices[2];
    MCMTLFarImageView _images[2];
    MCMTLFarImageTiming _timings[2];
    BOOL _attached;
}
- (instancetype)initWithOwner:(MCMTLFarImageOwner *)owner;
@end

static NSError *far_error(NSString *message) {
    return [NSError errorWithDomain:@"mcmetal.farimage" code:1
                          userInfo:@{NSLocalizedDescriptionKey: message}];
}

static NSError *far_exception(NSException *exception) {
    return far_error([NSString stringWithFormat:@"%@: %@", exception.name, exception.reason]);
}

/* Optional driver metadata must never change producer success/lifetime. Fake
 * lifecycle buffers intentionally omit these selectors. No query before success. */
static MCMTLFarImageTiming far_completion_timing(id<MTLCommandBuffer> done,
                                                BOOL success, uint64_t completionNs) {
    MCMTLFarImageTiming timing = {.completionNs = completionNs};
    if (!success) return timing;
    @try {
        if ([done respondsToSelector:@selector(GPUStartTime)] &&
            [done respondsToSelector:@selector(GPUEndTime)]) {
            double start = done.GPUStartTime, end = done.GPUEndTime;
            if (isfinite(start) && isfinite(end) && start > 0 && end > start) {
                double startNs = start * 1e9, endNs = end * 1e9;
                /* Strict upper bound avoids undefined out-of-range float casts. */
                if (isfinite(endNs) && endNs < 0x1p64 && startNs >= 1) {
                    uint64_t first = (uint64_t)startNs, last = (uint64_t)endNs;
                    if (last > first) {
                        timing.gpuStartNs = first;
                        timing.gpuEndNs = last;
                        timing.flags = MCMTLFarImageTimingGPUValid;
                    }
                }
            }
        }
    } @catch (NSException *exception) { (void)exception; }
    return timing;
}

static MCMTLFarImageView far_view(MCMTLFarImageOwner *owner, MCMTLFarImageSlot *slot) {
    MCMTLFarImageView view = {0};
    for (NSUInteger layer = 0; layer < MCMTLFarImageLayerCount; ++layer) {
        view.color[layer] = slot->color[layer];
        view.depth[layer] = slot->depth[layer];
        view.visibilityID[layer] = slot->visibilityID[layer];
    }
    view.inputs = slot->inputs;
    view.source = slot->source;
    view.sequence = slot->generation;
    view.width = owner->_width;
    view.height = owner->_height;
    return view;
}

static BOOL far_newer(MCMTLFarImageSlot *a, MCMTLFarImageSlot *b) {
    if (!b) return YES;
    if (a->source.sourceFrame != b->source.sourceFrame)
        return a->source.sourceFrame > b->source.sourceFrame;
    if (a->source.sourceTimeNs != b->source.sourceTimeNs)
        return a->source.sourceTimeNs > b->source.sourceTimeNs;
    return a->generation > b->generation;
}

/* Caller holds the owner lock. There can be three candidates just at publication. */
static void far_newest_two(MCMTLFarImageOwner *owner, NSInteger best[2]) {
    best[0] = best[1] = -1;
    for (NSUInteger i = 0; i < MCMTLFarImageSlotCount; ++i) {
        MCMTLFarImageSlot *slot = owner->_slots[i];
        if (slot->state != FarComplete || slot->source.epoch != owner->_epoch) continue;
        if (best[0] < 0 || far_newer(slot, owner->_slots[best[0]])) {
            best[1] = best[0];
            best[0] = (NSInteger)i;
        } else if (best[1] < 0 || far_newer(slot, owner->_slots[best[1]])) {
            best[1] = (NSInteger)i;
        }
    }
}

@implementation MCMTLFarImageOwner

- (instancetype)initWithDevice:(id<MTLDevice>)device width:(NSUInteger)width
                        height:(NSUInteger)height epoch:(uint64_t)epoch error:(NSError **)error {
    self = [super init];
    if (!self) return nil;
    _lock = OS_UNFAIR_LOCK_INIT;
    _activeSlot = -1;
    _nextGeneration = 1;
    if (error) *error = nil;
    @try {
        /* Same conservative dimensions as mcmtl_texture_create; reject BEFORE
         * Metal validation, whose invalid-descriptor path can abort the process. */
        if (!device || !width || !height || width > 16384 || height > 16384 || !epoch) {
            if (error) *error = far_error(@"Far-image device/epoch must be nonzero and dimensions within 1..16384");
            return nil;
        }
        _device = device;
        _width = width;
        _height = height;
        _epoch = epoch;
        /* Admission is the bound, not a queue capacity or GPU priority assertion. */
        _queue = [device newCommandQueue];
        _event = [device newSharedEvent];
        if (!_queue || !_event) {
            if (error) *error = far_error(@"Cannot create far-image queue/shared event");
            return nil;
        }
        _queue.label = @"mcmetal far-image producer";
        _event.label = @"mcmetal far-image private completion signal";
        MTLTextureDescriptor *desc = [MTLTextureDescriptor
            texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm
                                        width:width height:height mipmapped:NO];
        desc.storageMode = MTLStorageModePrivate;
        desc.hazardTrackingMode = MTLHazardTrackingModeTracked;
        desc.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
        for (NSUInteger i = 0; i < MCMTLFarImageSlotCount; ++i) {
            MCMTLFarImageSlot *slot = [MCMTLFarImageSlot new];
            if (!slot) {
                if (error) *error = far_error(@"Cannot allocate far-image slot owner");
                return nil;
            }
            _slots[i] = slot;
            for (NSUInteger layer = 0; layer < MCMTLFarImageLayerCount; ++layer) {
                desc.pixelFormat = MTLPixelFormatRGBA8Unorm;
                slot->color[layer] = [device newTextureWithDescriptor:desc];
                desc.pixelFormat = MTLPixelFormatDepth32Float;
                slot->depth[layer] = [device newTextureWithDescriptor:desc];
                desc.pixelFormat = MTLPixelFormatR32Uint;
                slot->visibilityID[layer] = [device newTextureWithDescriptor:desc];
                if (!slot->color[layer] || !slot->depth[layer] || !slot->visibilityID[layer]) {
                    if (error) *error = far_error(@"Cannot allocate all far-image targets");
                    return nil; /* ARC releases the partially created owner and targets. */
                }
            }
        }
    } @catch (NSException *exception) {
        if (error) *error = far_exception(exception);
        return nil;
    }
    return self;
}

- (MCMTLFarImageReservation *)newReservation {
    return [[MCMTLFarImageReservation alloc] initWithOwner:self];
}

- (BOOL)tryReserve:(MCMTLFarImageReservation *)reservation
            source:(MCMTLFarImageSource)source inputs:(id)inputs {
    if (!reservation || reservation->_owner != self || !inputs) return NO;
    if (!os_unfair_lock_trylock(&_lock)) return NO;
    BOOL accepted = NO;
    if (!_stopped && _epoch == source.epoch && _activeSlot < 0 &&
        !reservation->_generation && _nextGeneration != UINT64_MAX) {
        for (NSUInteger i = 0; i < MCMTLFarImageSlotCount; ++i) {
            MCMTLFarImageSlot *slot = _slots[i];
            if (slot->state != FarFree) continue;
            slot->generation = _nextGeneration++;
            slot->source = source;
            slot->timing = (MCMTLFarImageTiming){.reserveTimeNs = clock_gettime_nsec_np(CLOCK_UPTIME_RAW)};
            slot->inputs = inputs; /* FREE has no old snapshot to destroy on the offer thread. */
            slot->pins = 1;
            slot->state = FarProducing;
            slot->producer = FarReserved;
            slot->commitResolved = slot->commitOK = slot->gpuDone = slot->gpuOK = NO;
            reservation->_index = i;
            reservation->_generation = slot->generation;
            _activeSlot = (NSInteger)i;
            accepted = YES;
            break;
        }
    }
    os_unfair_lock_unlock(&_lock);
    return accepted;
}

- (MCMTLFarImageLease *)tryAcquireForEpoch:(uint64_t)epoch {
    MCMTLFarImageLease *lease = [[MCMTLFarImageLease alloc] initWithOwner:self];
    if (!lease || !os_unfair_lock_trylock(&_lock)) return nil;
    if (!_stopped && epoch == _epoch) {
        NSInteger best[2];
        far_newest_two(self, best);
        for (NSUInteger n = 0; n < 2 && best[n] >= 0; ++n) {
            MCMTLFarImageSlot *slot = _slots[best[n]];
            if (slot->pins == NSUIntegerMax) break;
            ++slot->pins;
            lease->_indices[n] = (NSUInteger)best[n];
            lease->_images[n] = far_view(self, slot);
            lease->_timings[n] = slot->timing;
            ++lease->_count;
        }
    }
    os_unfair_lock_unlock(&_lock);
    return lease->_count ? lease : nil;
}

- (BOOL)invalidateToEpoch:(uint64_t)epoch {
    os_unfair_lock_lock(&_lock);
    BOOL changed = !_stopped && epoch > _epoch;
    if (changed) {
        _epoch = epoch;
        for (NSUInteger i = 0; i < MCMTLFarImageSlotCount; ++i)
            if (_slots[i]->state != FarFree) _slots[i]->state = FarRetiring;
    }
    os_unfair_lock_unlock(&_lock);
    if (changed) [self recycleRetired];
    return changed;
}

- (void)stop {
    os_unfair_lock_lock(&_lock);
    _stopped = YES;
    for (NSUInteger i = 0; i < MCMTLFarImageSlotCount; ++i)
        if (_slots[i]->state != FarFree) _slots[i]->state = FarRetiring;
    os_unfair_lock_unlock(&_lock);
    [self recycleRetired];
}

- (NSError *)lastError {
    os_unfair_lock_lock(&_lock);
    NSError *error = _lastError;
    os_unfair_lock_unlock(&_lock);
    return error;
}

- (void)recordError:(NSError *)error {
    if (!error) return;
    __attribute__((objc_precise_lifetime)) NSError *previous = nil;
    os_unfair_lock_lock(&_lock);
    previous = _lastError;
    _lastError = error;
    os_unfair_lock_unlock(&_lock);
    (void)previous;
}

- (void)recycleRetired {
    for (NSUInteger i = 0; i < MCMTLFarImageSlotCount; ++i) {
        /* A caller's generation lease may run arbitrary destruction code. Keep its
         * last reference alive through unlock (ARC otherwise may release early). */
        __attribute__((objc_precise_lifetime)) id retired = nil;
        os_unfair_lock_lock(&_lock);
        MCMTLFarImageSlot *slot = _slots[i];
        if (slot->state == FarRetiring && slot->producer == FarIdle && !slot->pins) {
            retired = slot->inputs;
            slot->inputs = nil;
            slot->state = FarFree;
        }
        os_unfair_lock_unlock(&_lock);
        (void)retired;
    }
}

- (void)settleLocked:(NSUInteger)index {
    MCMTLFarImageSlot *slot = _slots[index];
    if (slot->producer != FarSubmitted || !slot->commitResolved || !slot->gpuDone) return;
    slot->producer = FarIdle;
    _activeSlot = -1;
    if (slot->commitOK && slot->gpuOK && !_stopped && slot->source.epoch == _epoch) {
        if (slot->timing.reserveTimeNs && slot->timing.encodeStartNs &&
            slot->timing.commitStartNs && slot->timing.commitEndNs && slot->timing.completionNs)
            slot->timing.flags |= MCMTLFarImageTimingCPUValid;
        slot->state = FarComplete;
        NSInteger best[2];
        far_newest_two(self, best);
        for (NSUInteger i = 0; i < MCMTLFarImageSlotCount; ++i) {
            if (_slots[i]->state == FarComplete && (NSInteger)i != best[0] && (NSInteger)i != best[1])
                _slots[i]->state = FarRetiring;
        }
    } else {
        slot->state = FarRetiring;
    }
}

- (void)abortSlot:(NSUInteger)index generation:(uint64_t)generation error:(NSError *)error {
    [self recordError:error];
    os_unfair_lock_lock(&_lock);
    MCMTLFarImageSlot *slot = _slots[index];
    if (slot->generation == generation && slot->producer != FarIdle) {
        slot->producer = FarIdle; /* Only used BEFORE any commit attempt. */
        slot->state = FarRetiring;
        _activeSlot = -1;
    }
    os_unfair_lock_unlock(&_lock);
    [self recycleRetired];
}

- (void)completedSlot:(NSUInteger)index generation:(uint64_t)generation
             success:(BOOL)success error:(NSError *)error timing:(MCMTLFarImageTiming)timing {
    os_unfair_lock_lock(&_lock);
    MCMTLFarImageSlot *slot = _slots[index];
    BOOL matches = slot->generation == generation && slot->producer == FarSubmitted && !slot->gpuDone;
    if (matches) {
        slot->timing.completionNs = timing.completionNs;
        slot->timing.gpuStartNs = timing.gpuStartNs;
        slot->timing.gpuEndNs = timing.gpuEndNs;
        slot->timing.flags |= timing.flags;
        slot->gpuDone = YES;
        slot->gpuOK = success;
        [self settleLocked:index];
    }
    os_unfair_lock_unlock(&_lock);
    if (matches && !success) [self recordError:error ?: far_error(@"Far-image GPU producer failed")];
    [self recycleRetired];
}

- (void)releasePin:(NSUInteger)index generation:(uint64_t)generation abandon:(BOOL)abandon {
    os_unfair_lock_lock(&_lock);
    MCMTLFarImageSlot *slot = _slots[index];
    if (slot->generation == generation && slot->pins) {
        --slot->pins;
        if (abandon && slot->producer == FarReserved) {
            slot->producer = FarIdle;
            slot->state = FarRetiring;
            _activeSlot = -1;
        }
    }
    os_unfair_lock_unlock(&_lock);
    [self recycleRetired];
}
@end

@implementation MCMTLFarImageReservation
- (instancetype)initWithOwner:(MCMTLFarImageOwner *)owner {
    if ((self = [super init])) _owner = owner;
    return self;
}

- (void)dealloc {
    if (_generation) [_owner releasePin:_index generation:_generation abandon:YES];
}

- (BOOL)encodeAndSubmit:(BOOL (^)(id<MTLCommandBuffer>, MCMTLFarImageView, NSError **))encode {
    /* Pins the reservation even if client code drops its last external reference. */
    __attribute__((objc_precise_lifetime)) MCMTLFarImageReservation *keepAlive = self;
    MCMTLFarImageOwner *owner = keepAlive->_owner;
    const NSUInteger index = keepAlive->_index;
    const uint64_t generation = keepAlive->_generation;
    if (!generation || !encode) return NO;
    os_unfair_lock_lock(&owner->_lock);
    MCMTLFarImageSlot *slot = owner->_slots[index];
    BOOL reserved = slot->generation == generation && slot->producer == FarReserved;
    BOOL eligible = reserved && !owner->_stopped && slot->source.epoch == owner->_epoch;
    if (reserved) {
        slot->producer = FarEncoding;
        slot->timing.encodeStartNs = clock_gettime_nsec_np(CLOCK_UPTIME_RAW);
    }
    os_unfair_lock_unlock(&owner->_lock);
    if (!reserved) return NO;
    if (!eligible) {
        [owner abortSlot:index generation:generation error:nil];
        return NO;
    }

    BOOL attemptedCommit = NO, committed = NO;
    uint64_t commitStartNs = 0, commitEndNs = 0;
    NSError *failure = nil;
    @try {
        id<MTLCommandBuffer> buffer = [owner->_queue commandBuffer];
        if (!buffer) {
            failure = far_error(@"Cannot allocate far-image command buffer");
        } else if (!encode(buffer, far_view(owner, slot), &failure)) {
            if (!failure) failure = far_error(@"Far-image producer encoding declined");
        } else {
            os_unfair_lock_lock(&owner->_lock);
            eligible = !owner->_stopped && slot->source.epoch == owner->_epoch;
            if (eligible) slot->producer = FarSubmitted;
            os_unfair_lock_unlock(&owner->_lock);
            if (eligible) {
                /* This event is private: a signalled value is NOT success/publication.
                 * Consumers see only COMPLETED images, so need no far-queue wait. */
                [buffer encodeSignalEvent:owner->_event value:generation];
                __block MCMTLFarImageOwner *completionOwner = owner;
                [buffer addCompletedHandler:^(id<MTLCommandBuffer> done) {
                    const uint64_t completionNs = clock_gettime_nsec_np(CLOCK_UPTIME_RAW);
                    @autoreleasepool {
                        __attribute__((objc_precise_lifetime)) MCMTLFarImageOwner *finishedOwner = completionOwner;
                        completionOwner = nil;
                        if (!finishedOwner) return;
                        BOOL success = done.status == MTLCommandBufferStatusCompleted;
                        MCMTLFarImageTiming timing = far_completion_timing(done, success, completionNs);
                        [finishedOwner completedSlot:index generation:generation
                                            success:success error:done.error timing:timing];
                    }
                }];
                attemptedCommit = YES;
                commitStartNs = clock_gettime_nsec_np(CLOCK_UPTIME_RAW);
                @try {
                    [buffer commit];
                    committed = YES;
                } @finally {
                    commitEndNs = clock_gettime_nsec_np(CLOCK_UPTIME_RAW);
                }
            }
        }
    } @catch (NSException *exception) {
        failure = far_exception(exception);
    }
    if (!attemptedCommit) {
        [owner abortSlot:index generation:generation error:failure];
    } else {
        [owner recordError:failure];
        os_unfair_lock_lock(&owner->_lock);
        if (slot->generation == generation && slot->producer == FarSubmitted) {
            slot->timing.commitStartNs = commitStartNs;
            slot->timing.commitEndNs = commitEndNs;
            slot->commitResolved = YES;
            slot->commitOK = committed;
            if (!committed) slot->state = FarRetiring;
            [owner settleLocked:index];
        }
        os_unfair_lock_unlock(&owner->_lock);
        [owner recycleRetired];
    }
    return committed;
}
@end

@implementation MCMTLFarImageLease
- (instancetype)initWithOwner:(MCMTLFarImageOwner *)owner {
    if ((self = [super init])) _owner = owner;
    return self;
}

- (NSUInteger)count { return _count; }

- (MCMTLFarImageView)imageAtIndex:(NSUInteger)index {
    if (index < _count) return _images[index];
    MCMTLFarImageView empty = {0};
    return empty;
}

- (BOOL)timingAtIndex:(NSUInteger)index into:(MCMTLFarImageTiming *)out {
    if (!out) return NO;
    *out = (MCMTLFarImageTiming){0};
    if (index >= _count || !(_timings[index].flags & MCMTLFarImageTimingCPUValid)) return NO;
    *out = _timings[index];
    return YES;
}

- (void)dealloc {
    for (NSUInteger n = 0; n < _count; ++n)
        [_owner releasePin:_indices[n] generation:_images[n].sequence abandon:NO];
}

- (BOOL)attachToCommandBuffer:(id<MTLCommandBuffer>)buffer {
    if (!buffer || buffer.device != _owner->_device || buffer.status != MTLCommandBufferStatusNotEnqueued)
        return NO;
    os_unfair_lock_lock(&_owner->_lock);
    BOOL eligible = !_attached && !_owner->_stopped && _count > 0;
    for (NSUInteger n = 0; eligible && n < _count; ++n) {
        MCMTLFarImageSlot *slot = _owner->_slots[_indices[n]];
        /* RETIRING due to age is still readable by a pinned lease. Epoch retirement
         * is different: an old lease cannot admit a new consumer after invalidation. */
        eligible = _images[n].source.epoch == _owner->_epoch &&
                   slot->generation == _images[n].sequence && slot->producer == FarIdle;
    }
    if (eligible) _attached = YES;
    os_unfair_lock_unlock(&_owner->_lock);
    if (!eligible) return NO;

    /* A block-held reference, not an owner-held command buffer: abandonment by
     * releasing an uncommitted buffer also releases the pin. Clear it on completion
     * even if Metal/the caller retains the command buffer and its handler afterward. */
    __block MCMTLFarImageLease *completionLease = self;
    @try {
        [buffer addCompletedHandler:^(id<MTLCommandBuffer> done) {
            @autoreleasepool {
                __attribute__((objc_precise_lifetime)) MCMTLFarImageLease *lease = completionLease;
                completionLease = nil;
                if (lease && done.status != MTLCommandBufferStatusCompleted)
                    [lease->_owner recordError:done.error ?: far_error(@"Far-image consumer failed")];
            }
        }];
    } @catch (NSException *exception) {
        [_owner recordError:far_exception(exception)];
        return NO;
    }
    return YES;
}
@end
