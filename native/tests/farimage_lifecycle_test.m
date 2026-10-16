/*
 * Standalone CPU lifecycle test; link with mcmetal_farimage.m using ARC and
 * -fobjc-arc-exceptions, Foundation and Metal. No real device/queue/GPU is created.
 * Fake command buffers deliver completions explicitly, including inside commit.
 * Built by tools/build-native-tests.sh farimage_lifecycle_test.
 */
#import "mcmetal_farimage.h"
#include <stdio.h>
#include <stdlib.h>

#define CHECK(x) do { if (!(x)) { fprintf(stderr, "FAIL line %d: %s\n", __LINE__, #x); abort(); } } while (0)

@class FarTestDevice, FarTestQueue;

@interface FarTestTexture : NSObject
@property(nonatomic, copy) MTLTextureDescriptor *descriptor;
@end
@implementation FarTestTexture
@end

@interface FarTestEvent : NSObject
@property(nonatomic, copy) NSString *label;
@property(nonatomic) uint64_t signaledValue;
@end
@implementation FarTestEvent
@end

@interface FarTestBuffer : NSObject
@property(nonatomic, weak) FarTestDevice *device;
@property(nonatomic, weak) FarTestQueue *queue;
@property(nonatomic) MTLCommandBufferStatus status;
@property(nonatomic, strong) NSError *error;
@property(nonatomic, strong) NSMutableArray *handlers;
@property(nonatomic, strong) FarTestEvent *signal;
@property(nonatomic) uint64_t signalValue;
- (void)addCompletedHandler:(MTLCommandBufferHandler)handler;
- (void)encodeSignalEvent:(id<MTLEvent>)event value:(uint64_t)value;
- (void)commit;
- (void)finish:(BOOL)success;
@end

@interface FarTestQueue : NSObject
@property(nonatomic, weak) FarTestDevice *device;
@property(nonatomic, copy) NSString *label;
@property(nonatomic, strong) NSMutableArray<FarTestBuffer *> *pending;
@property(nonatomic) BOOL completeInsideCommit, throwAfterCompletion, throwBeforeCommit, failAllocation;
@property(nonatomic) NSUInteger allocations;
- (id<MTLCommandBuffer>)commandBuffer;
@end

@interface FarTestDevice : NSObject
@property(nonatomic, strong) FarTestQueue *producerQueue;
@property(nonatomic, strong) NSMutableArray<FarTestTexture *> *textures;
- (id<MTLCommandQueue>)newCommandQueue;
- (id<MTLSharedEvent>)newSharedEvent;
- (id<MTLTexture>)newTextureWithDescriptor:(MTLTextureDescriptor *)descriptor;
@end

@implementation FarTestBuffer
- (instancetype)init {
    if ((self = [super init])) _handlers = [NSMutableArray new];
    return self;
}
- (void)addCompletedHandler:(MTLCommandBufferHandler)handler { [_handlers addObject:[handler copy]]; }
- (void)encodeSignalEvent:(id<MTLEvent>)event value:(uint64_t)value {
    _signal = (FarTestEvent *)event;
    _signalValue = value;
}
- (void)commit {
    CHECK(_status == MTLCommandBufferStatusNotEnqueued);
    if (_queue.throwBeforeCommit) @throw [NSException exceptionWithName:@"commit" reason:@"unknown outcome" userInfo:nil];
    _status = MTLCommandBufferStatusCommitted;
    [_queue.pending addObject:self];
    if (_queue.completeInsideCommit) [self finish:YES];
    if (_queue.throwAfterCompletion) @throw [NSException exceptionWithName:@"commit" reason:@"after callback" userInfo:nil];
}
- (void)finish:(BOOL)success {
    _status = success ? MTLCommandBufferStatusCompleted : MTLCommandBufferStatusError;
    _error = success ? nil : [NSError errorWithDomain:@"fake-metal" code:7 userInfo:nil];
    if (success) _signal.signaledValue = _signalValue;
    /* Keep handlers after completion to check that their references are severed,
     * and to inject a delayed duplicate after a slot has been reused. */
    for (MTLCommandBufferHandler handler in [_handlers copy]) handler((id<MTLCommandBuffer>)self);
    [_queue.pending removeObjectIdenticalTo:self];
}
@end

@implementation FarTestQueue
- (instancetype)init {
    if ((self = [super init])) _pending = [NSMutableArray new];
    return self;
}
- (id<MTLCommandBuffer>)commandBuffer {
    ++_allocations;
    if (_failAllocation) return nil;
    FarTestBuffer *buffer = [FarTestBuffer new];
    buffer.device = _device;
    buffer.queue = self;
    return (id<MTLCommandBuffer>)buffer;
}
@end

@implementation FarTestDevice
- (instancetype)init {
    if ((self = [super init])) _textures = [NSMutableArray new];
    return self;
}
- (id<MTLCommandQueue>)newCommandQueue {
    _producerQueue = [FarTestQueue new];
    _producerQueue.device = self;
    return (id<MTLCommandQueue>)_producerQueue;
}
- (id<MTLSharedEvent>)newSharedEvent { return (id<MTLSharedEvent>)[FarTestEvent new]; }
- (id<MTLTexture>)newTextureWithDescriptor:(MTLTextureDescriptor *)descriptor {
    FarTestTexture *texture = [FarTestTexture new];
    texture.descriptor = descriptor;
    [_textures addObject:texture];
    return (id<MTLTexture>)texture;
}
@end

static MCMTLFarImageSource source(uint64_t epoch, uint64_t frame) {
    return (MCMTLFarImageSource){ .epoch = epoch, .sourceFrame = frame, .sourceTimeNs = frame * 100,
                                .geometryGeneration = 41, .materialGeneration = 52 };
}

static MCMTLFarImageOwner *make_owner(FarTestDevice *device) {
    NSError *error;
    MCMTLFarImageOwner *owner = [[MCMTLFarImageOwner alloc] initWithDevice:(id<MTLDevice>)device
                                                                   width:80 height:48 epoch:1 error:&error];
    CHECK(owner && !error && device.textures.count == 18);
    for (FarTestTexture *texture in device.textures) {
        MTLTextureDescriptor *d = texture.descriptor;
        CHECK(d.width == 80 && d.height == 48 && d.sampleCount == 1 && d.mipmapLevelCount == 1);
        CHECK(d.storageMode == MTLStorageModePrivate && d.hazardTrackingMode == MTLHazardTrackingModeTracked);
        CHECK(d.usage == (MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead));
    }
    return owner;
}

static BOOL encode_ok(id<MTLCommandBuffer> buffer, MCMTLFarImageView image, NSError **error) {
    (void)buffer; (void)error;
    CHECK(image.width == 80 && image.height == 48 && image.inputs && image.sequence);
    for (NSUInteger n = 0; n < 2; ++n) {
        CHECK(((FarTestTexture *)image.color[n]).descriptor.pixelFormat == MTLPixelFormatRGBA8Unorm);
        CHECK(((FarTestTexture *)image.depth[n]).descriptor.pixelFormat == MTLPixelFormatDepth32Float);
        CHECK(((FarTestTexture *)image.visibilityID[n]).descriptor.pixelFormat == MTLPixelFormatR32Uint);
    }
    CHECK(image.color[0] != image.color[1] && image.depth[0] != image.depth[1] &&
          image.visibilityID[0] != image.visibilityID[1]);
    return YES;
}

static BOOL submit(MCMTLFarImageReservation *reservation) {
    return [reservation encodeAndSubmit:^BOOL(id<MTLCommandBuffer> b, MCMTLFarImageView v, NSError **e) {
        return encode_ok(b, v, e);
    }];
}

static FarTestBuffer *produce(MCMTLFarImageOwner *owner, FarTestDevice *device, uint64_t epoch, uint64_t frame) {
    @autoreleasepool {
        MCMTLFarImageReservation *r = [owner newReservation];
        CHECK([owner tryReserve:r source:source(epoch, frame) inputs:[NSObject new]]);
        CHECK(submit(r));
        return device.producerQueue.pending.lastObject;
    }
}

static void selection_and_pins(void) {
    FarTestDevice *device = [FarTestDevice new];
    MCMTLFarImageOwner *owner = make_owner(device);
    MCMTLFarImageReservation *spare = [owner newReservation];
    FarTestBuffer *first = produce(owner, device, 1, 10);
    CHECK(![owner tryAcquireForEpoch:1]);
    NSUInteger allocations = device.producerQueue.allocations;
    CHECK(![owner tryReserve:spare source:source(1, 11) inputs:device]);
    CHECK(device.producerQueue.allocations == allocations); /* Offers cannot allocate a CB. */
    [first finish:YES];

    FarTestBuffer *consumer = [FarTestBuffer new];
    consumer.device = device;
    __weak MCMTLFarImageLease *weakLease;
    @autoreleasepool {
        MCMTLFarImageLease *lease = [owner tryAcquireForEpoch:1];
        weakLease = lease;
        CHECK(lease.count == 1 && [lease imageAtIndex:0].source.sourceFrame == 10);
        CHECK([lease attachToCommandBuffer:(id<MTLCommandBuffer>)consumer]);
        CHECK(![lease attachToCommandBuffer:(id<MTLCommandBuffer>)consumer]);
        [consumer commit];
    }
    CHECK(weakLease); /* Actual command buffer holds the only consumer lease. */
    [produce(owner, device, 1, 20) finish:YES];
    [produce(owner, device, 1, 30) finish:YES];
    @autoreleasepool {
        MCMTLFarImageLease *pair = [owner tryAcquireForEpoch:1];
        CHECK(pair.count == 2);
        MCMTLFarImageView a = [pair imageAtIndex:0], b = [pair imageAtIndex:1];
        CHECK(a.source.sourceFrame == 30 && b.source.sourceFrame == 20 && a.sequence != b.sequence);
        CHECK(a.color[0] != b.color[0] && a.color[1] != b.color[1]);
        CHECK(a.source.sourceTimeNs == 3000 && a.source.geometryGeneration == 41 && a.source.materialGeneration == 52);
    }
    CHECK(![owner tryReserve:spare source:source(1, 40) inputs:device]); /* Third slot pinned by first consumer. */
    [consumer finish:NO]; /* Even a failed consumer must release its pin. */
    CHECK(!weakLease && owner.lastError);
    CHECK([owner tryReserve:spare source:source(1, 40) inputs:[NSObject new]]);
    CHECK(submit(spare));
    [first finish:YES]; /* Delayed old callback cannot complete/publish the new generation. */
    CHECK([owner tryAcquireForEpoch:1].count == 2);
    [device.producerQueue.pending.lastObject finish:NO];
    @autoreleasepool {
        MCMTLFarImageLease *pair = [owner tryAcquireForEpoch:1];
        CHECK([pair imageAtIndex:0].source.sourceFrame == 30 && [pair imageAtIndex:1].source.sourceFrame == 20);
    }
    [owner stop];
}

static void invalidation_and_abandonment(void) {
    FarTestDevice *device = [FarTestDevice new];
    MCMTLFarImageOwner *owner = make_owner(device);
    __weak id weakInputs;
    @autoreleasepool {
        id inputs = [NSObject new];
        weakInputs = inputs;
        MCMTLFarImageReservation *reserved = [owner newReservation];
        CHECK([owner tryReserve:reserved source:source(1, 1) inputs:inputs]);
        inputs = nil;
        CHECK([owner invalidateToEpoch:2]);
        CHECK(![owner invalidateToEpoch:1] && ![owner invalidateToEpoch:2]);
        CHECK(weakInputs && ![owner tryAcquireForEpoch:1] && ![owner tryAcquireForEpoch:2]);
        CHECK(!submit(reserved));
        CHECK(weakInputs); /* Invalidation/cancellation cannot revoke an existing pin. */
    }
    CHECK(!weakInputs);
    FarTestBuffer *old = produce(owner, device, 2, 2);
    CHECK([owner invalidateToEpoch:3]);
    MCMTLFarImageReservation *next = [owner newReservation];
    CHECK(![owner tryReserve:next source:source(3, 3) inputs:device]);
    [old finish:YES];
    CHECK(![owner tryAcquireForEpoch:3]);
    [produce(owner, device, 3, 3) finish:YES];
    @autoreleasepool {
        MCMTLFarImageLease *lease = [owner tryAcquireForEpoch:3];
        FarTestBuffer *unused = [FarTestBuffer new];
        unused.device = device;
        CHECK([owner invalidateToEpoch:4]);
        CHECK(![lease attachToCommandBuffer:(id<MTLCommandBuffer>)unused]);
    }
    /* Dropping an accepted but unsubmitted reservation cancels producer admission. */
    @autoreleasepool {
        MCMTLFarImageReservation *abandoned = [owner newReservation];
        CHECK([owner tryReserve:abandoned source:source(4, 4) inputs:[NSObject new]]);
    }
    CHECK([owner tryReserve:next source:source(4, 5) inputs:[NSObject new]]);
    [owner stop];
    CHECK(!submit(next) && ![owner tryAcquireForEpoch:4]);
}

static void all_consumers_and_abandoned_buffer(void) {
    FarTestDevice *device = [FarTestDevice new];
    MCMTLFarImageOwner *owner = make_owner(device);
    __weak id weakInputs;
    @autoreleasepool {
        id inputs = [NSObject new];
        weakInputs = inputs;
        MCMTLFarImageReservation *r = [owner newReservation];
        CHECK([owner tryReserve:r source:source(1, 1) inputs:inputs] && submit(r));
    }
    CHECK(weakInputs);
    [device.producerQueue.pending.lastObject finish:YES];
    FarTestBuffer *a = [FarTestBuffer new], *b = [FarTestBuffer new];
    a.device = b.device = device;
    __weak MCMTLFarImageLease *abandonedLease;
    @autoreleasepool {
        MCMTLFarImageLease *first = [owner tryAcquireForEpoch:1];
        MCMTLFarImageLease *second = [owner tryAcquireForEpoch:1];
        CHECK([first attachToCommandBuffer:(id<MTLCommandBuffer>)a]);
        CHECK([second attachToCommandBuffer:(id<MTLCommandBuffer>)b]);
        [a commit]; [b commit];
        /* A third consumer never commits; destruction of its buffer drops its pin. */
        FarTestBuffer *unused = [FarTestBuffer new];
        unused.device = device;
        MCMTLFarImageLease *lease = [owner tryAcquireForEpoch:1];
        abandonedLease = lease;
        CHECK([lease attachToCommandBuffer:(id<MTLCommandBuffer>)unused]);
        CHECK([owner invalidateToEpoch:2]);
    }
    CHECK(!abandonedLease && weakInputs);
    [b finish:YES]; /* Out-of-order consumers: the first still uses the IDs. */
    CHECK(weakInputs);
    [a finish:YES];
    CHECK(!weakInputs);
    @autoreleasepool {
        MCMTLFarImageReservation *r = [owner newReservation];
        CHECK([owner tryReserve:r source:source(2, 2) inputs:[NSObject new]]);
        CHECK(![r encodeAndSubmit:^BOOL(id<MTLCommandBuffer> buffer, MCMTLFarImageView view, NSError **error) {
            (void)buffer; (void)view; (void)error;
            CHECK([owner invalidateToEpoch:3]); /* Invalidation DURING client encoding. */
            return YES;
        }]);
        CHECK(device.producerQueue.pending.count == 0 && ![owner tryAcquireForEpoch:3]);
    }
    [owner stop];
}

static void commit_races_and_lifetime(void) {
    FarTestDevice *device = [FarTestDevice new];
    MCMTLFarImageOwner *owner = make_owner(device);
    device.producerQueue.completeInsideCommit = YES;
    @autoreleasepool {
        MCMTLFarImageReservation *r = [owner newReservation];
        CHECK([owner tryReserve:r source:source(1, 1) inputs:[NSObject new]] && submit(r));
        CHECK([owner tryAcquireForEpoch:1].count == 1);
    }
    device.producerQueue.throwAfterCompletion = YES;
    @autoreleasepool {
        MCMTLFarImageReservation *r = [owner newReservation];
        CHECK([owner tryReserve:r source:source(1, 2) inputs:[NSObject new]] && !submit(r));
        MCMTLFarImageLease *lease = [owner tryAcquireForEpoch:1];
        CHECK(lease.count == 1 && [lease imageAtIndex:0].source.sourceFrame == 1);
    }
    device.producerQueue.throwAfterCompletion = NO;
    device.producerQueue.completeInsideCommit = NO;
    device.producerQueue.failAllocation = YES;
    @autoreleasepool {
        MCMTLFarImageReservation *r = [owner newReservation];
        CHECK([owner tryReserve:r source:source(1, 3) inputs:[NSObject new]] && !submit(r));
    }
    device.producerQueue.failAllocation = NO;
    @autoreleasepool {
        MCMTLFarImageReservation *r = [owner newReservation];
        CHECK([owner tryReserve:r source:source(1, 4) inputs:[NSObject new]]);
        CHECK(![r encodeAndSubmit:^BOOL(id<MTLCommandBuffer> b, MCMTLFarImageView v, NSError **e) {
            (void)b; (void)v; (void)e;
            @throw [NSException exceptionWithName:@"encode" reason:@"test" userInfo:nil];
        }]);
    }
    CHECK(owner.lastError);
    [owner stop];

    /* No external owner/reservation remains: the producer handler keeps them alive,
     * then releases its owner even though the completed buffer is still retained. */
    __weak MCMTLFarImageOwner *weakOwner;
    FarTestBuffer *pending;
    @autoreleasepool {
        FarTestDevice *otherDevice = [FarTestDevice new];
        MCMTLFarImageOwner *other = make_owner(otherDevice);
        weakOwner = other;
        pending = produce(other, otherDevice, 1, 1);
        [other stop];
    }
    CHECK(weakOwner);
    [pending finish:YES];
    CHECK(!weakOwner);

    /* An ambiguous throwing commit has no safe CPU cancellation: quarantine. */
    FarTestDevice *badDevice = [FarTestDevice new];
    MCMTLFarImageOwner *bad = make_owner(badDevice);
    badDevice.producerQueue.throwBeforeCommit = YES;
    @autoreleasepool {
        MCMTLFarImageReservation *r = [bad newReservation];
        CHECK([bad tryReserve:r source:source(1, 1) inputs:[NSObject new]] && !submit(r));
    }
    CHECK(![bad tryReserve:[bad newReservation] source:source(1, 2) inputs:[NSObject new]]);
    CHECK(![bad tryAcquireForEpoch:1] && bad.lastError);
    [bad stop];
}

int main(void) {
    @autoreleasepool {
        selection_and_pins();
        invalidation_and_abandonment();
        all_consumers_and_abandoned_buffer();
        commit_races_and_lifetime();
    }
    puts("farimage_lifecycle_test: passed (fake Metal; CPU only)");
    return 0;
}
