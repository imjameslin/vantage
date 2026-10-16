/* Real Metal target readback and consumer lifetime; not a renderer/FPS acceptance test. */
#import "mcmetal_farimage.h"
#include <dispatch/dispatch.h>
#include <stdio.h>
#include <unistd.h>
#include <time.h>

static int checks, failures;
#define CHECK(x) do { checks++; if (!(x)) { failures++; fprintf(stderr, "FAIL line %d: %s\n", __LINE__, #x); } } while (0)

static BOOL produce(MCMTLFarImageOwner *owner, uint64_t frame) {
    @autoreleasepool {
        MCMTLFarImageReservation *r = [owner newReservation];
        MCMTLFarImageSource source = {.epoch = 1, .sourceFrame = frame, .sourceTimeNs = frame * 1000000};
        if (![owner tryReserve:r source:source inputs:@(frame)]) return NO;
        return [r encodeAndSubmit:^BOOL(id<MTLCommandBuffer> cb, MCMTLFarImageView view, NSError **error) {
            for (NSUInteger layer = 0; layer < MCMTLFarImageLayerCount; layer++) {
                MTLRenderPassDescriptor *p = [MTLRenderPassDescriptor renderPassDescriptor];
                p.colorAttachments[0].texture = view.color[layer];
                p.colorAttachments[0].loadAction = MTLLoadActionClear;
                p.colorAttachments[0].storeAction = MTLStoreActionStore;
                p.colorAttachments[0].clearColor = MTLClearColorMake((20 * frame + layer) / 255.0, 17.0 / 255.0, 0, 1);
                p.colorAttachments[1].texture = view.visibilityID[layer];
                p.colorAttachments[1].loadAction = MTLLoadActionClear;
                p.colorAttachments[1].storeAction = MTLStoreActionStore;
                p.colorAttachments[1].clearColor = MTLClearColorMake(100 * frame + layer, 0, 0, 0);
                p.depthAttachment.texture = view.depth[layer];
                p.depthAttachment.loadAction = MTLLoadActionClear;
                p.depthAttachment.storeAction = MTLStoreActionStore;
                p.depthAttachment.clearDepth = (layer + 1) * .25;
                id<MTLRenderCommandEncoder> enc = [cb renderCommandEncoderWithDescriptor:p];
                if (!enc) return NO;
                [enc endEncoding];
            }
            return YES;
        }];
    }
}

/* Test harness only: production acquisition never waits. */
static MCMTLFarImageLease *await_frame(MCMTLFarImageOwner *owner, uint64_t frame) {
    uint64_t deadline = clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW) + 5ull * NSEC_PER_SEC;
    do {
        @autoreleasepool {
            MCMTLFarImageLease *lease = [owner tryAcquireForEpoch:1];
            if (lease && [lease imageAtIndex:0].source.sourceFrame == frame) return lease;
        }
        usleep(1000);
    } while (clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW) < deadline);
    CHECK(NO);
    return nil;
}

int main(void) { @autoreleasepool {
    id<MTLDevice> device = MTLCreateSystemDefaultDevice();
    CHECK(device != nil);
    if (!device) return 1;
    NSError *error = nil;
    CHECK([[MCMTLFarImageOwner alloc] initWithDevice:device width:16385 height:16 epoch:1 error:&error] == nil && error);
    MCMTLFarImageOwner *owner = [[MCMTLFarImageOwner alloc] initWithDevice:device width:16 height:16 epoch:1 error:&error];
    CHECK(owner != nil && error == nil);
    if (!owner) return 1;
    CHECK(produce(owner, 1));
    MCMTLFarImageLease *first = await_frame(owner, 1);
    if (!first) return 1;
    CHECK(first.count == 1);
    MCMTLFarImageView image = [first imageAtIndex:0];
    CHECK(image.width == 16 && image.height == 16 && image.source.sourceTimeNs == 1000000);
    CHECK([(NSNumber *)image.inputs unsignedLongLongValue] == 1);

    id<MTLCommandQueue> near = [device newCommandQueue];
    id<MTLCommandBuffer> consumer = [near commandBuffer];
    id<MTLSharedEvent> gate = [device newSharedEvent];
    [consumer encodeWaitForEvent:gate value:1];
    CHECK([first attachToCommandBuffer:consumer]);
    id<MTLBuffer> pixels[2][3];
    id<MTLBlitCommandEncoder> blit = [consumer blitCommandEncoder];
    for (NSUInteger layer = 0; layer < 2; layer++) {
        id<MTLTexture> textures[3] = {image.color[layer], image.depth[layer], image.visibilityID[layer]};
        for (NSUInteger kind = 0; kind < 3; kind++) {
            CHECK(textures[kind].storageMode == MTLStorageModePrivate);
            pixels[layer][kind] = [device newBufferWithLength:16 * 256 options:MTLResourceStorageModeShared];
            [blit copyFromTexture:textures[kind] sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0, 0, 0)
                      sourceSize:MTLSizeMake(16, 16, 1) toBuffer:pixels[layer][kind] destinationOffset:0
           destinationBytesPerRow:256 destinationBytesPerImage:16 * 256];
        }
    }
    [blit endEncoding];
    dispatch_semaphore_t done = dispatch_semaphore_create(0);
    [consumer addCompletedHandler:^(id<MTLCommandBuffer> cb) { dispatch_semaphore_signal(done); }];
    [consumer commit];
    first = nil; /* Actual GPU consumer now owns the only pin on the first image. */
    CHECK(produce(owner, 2));
    @autoreleasepool {
        MCMTLFarImageLease *second = await_frame(owner, 2);
        CHECK(second.count == 2 && [second imageAtIndex:1].source.sourceFrame == 1);
    }
    CHECK(produce(owner, 3));
    @autoreleasepool {
        MCMTLFarImageLease *third = await_frame(owner, 3);
        CHECK(third.count == 2 && [third imageAtIndex:1].source.sourceFrame == 2);
    }
    CHECK(!produce(owner, 4)); /* Two ready images plus pinned retiring image; no storage overwrite. */
    CHECK([owner invalidateToEpoch:2]);
    CHECK([owner tryAcquireForEpoch:1] == nil && [owner tryAcquireForEpoch:2] == nil);
    [owner stop];
    gate.signaledValue = 1;
    BOOL complete = dispatch_semaphore_wait(done, dispatch_time(DISPATCH_TIME_NOW, 5 * NSEC_PER_SEC)) == 0;
    CHECK(complete && consumer.status == MTLCommandBufferStatusCompleted);
    if (complete) for (NSUInteger layer = 0; layer < 2; layer++) {
        for (NSUInteger y = 0; y < 16; y++) for (NSUInteger x = 0; x < 16; x++) {
            uint8_t *color = (uint8_t *)pixels[layer][0].contents + y * 256 + x * 4;
            float depth = *(float *)((uint8_t *)pixels[layer][1].contents + y * 256 + x * 4);
            uint32_t ident = *(uint32_t *)((uint8_t *)pixels[layer][2].contents + y * 256 + x * 4);
            CHECK(color[0] == 20 + layer && color[1] == 17 && color[2] == 0 && color[3] == 255);
            CHECK(depth == (layer + 1) * .25f);
            CHECK(ident == 100 + layer);
        }
    }
    CHECK(owner.lastError == nil);
    printf("farimage_gpu_test: %d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
} }
