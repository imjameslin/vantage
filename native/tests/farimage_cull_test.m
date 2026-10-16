/* Prompt 5: actual GPU output and queue-isolation checks for encode-only culling. */
#import "mcmetal_internal.h"
#import "mcmetal_clusters.h"
#include <dispatch/dispatch.h>
#include <stdio.h>
#include <string.h>

extern int32_t mcmtl_ext_cluster_cull_stats(uint64_t *out);
static int checks, failures;
#define CHECK(x) do { checks++; if (!(x)) { failures++; fprintf(stderr, "FAIL line %d: %s\n", __LINE__, #x); } } while (0)
static uint64_t handle(id object) { return (uint64_t)(uintptr_t)(__bridge void *)object; }

static BOOL finish(id<MTLCommandBuffer> cb) {
    dispatch_semaphore_t done = dispatch_semaphore_create(0);
    [cb addCompletedHandler:^(id<MTLCommandBuffer> unused) { dispatch_semaphore_signal(done); }];
    [cb commit];
    BOOL complete = dispatch_semaphore_wait(done, dispatch_time(DISPATCH_TIME_NOW, 5 * NSEC_PER_SEC)) == 0;
    CHECK(complete);
    if (complete) CHECK(cb.status == MTLCommandBufferStatusCompleted);
    return complete && cb.status == MTLCommandBufferStatusCompleted;
}

int main(void) { @autoreleasepool {
    mcmtl_device_desc desc = {.structSize = sizeof desc, .framesInFlight = 2};
    mcmtl_handle deviceHandle = 0;
    CHECK(mcmtl_device_create(&desc, &deviceHandle) == MCMTL_OK);
    if (!deviceHandle) return 1;
    MCMTLDevice *dev = MCMTL_DEVICE(deviceHandle);
    id<MTLCommandQueue> far = [dev->device newCommandQueue];
    CHECK(far != nil && far != dev->queue);
    id<MTLBuffer> items = [dev->device newBufferWithLength:32 options:MTLResourceStorageModeShared];
    id<MTLBuffer> sections = [dev->device newBufferWithLength:32 options:MTLResourceStorageModeShared];
    id<MTLBuffer> out = [dev->device newBufferWithLength:16 options:MTLResourceStorageModeShared];
    id<MTLBuffer> quads = [dev->device newBufferWithLength:12 options:MTLResourceStorageModeShared];
    id<MTLBuffer> args = [dev->device newBufferWithLength:32 options:MTLResourceStorageModeShared];
    id<MTLBuffer> scratch = [dev->device newBufferWithLength:16 options:MTLResourceStorageModeShared];
    CHECK(items && sections && out && quads && args && scratch);
    if (!items || !sections || !out || !quads || !args || !scratch) return 1;
    uint32_t source[8] = {0, (1u << 20) | (1u << 29), 0, 0, 8, (1u << 29), 0, 0};
    memcpy(items.contents, source, sizeof source);
    memset(sections.contents, 0, sections.length);
    ((float *)sections.contents)[3] = 1.0f / 16.0f;
    mcmtl_cluster_job job = {
        .itemsBuffer = handle(items), .sectionsBuffer = handle(sections), .outItemsBuffer = handle(out),
        .quadsBuffer = handle(quads), .argsBuffer = handle(args), .scratchBuffer = handle(scratch),
        .clusterCount = 2, .quadCapacity = 3
    };
    uint32_t view[32] = {0};
    view[21] = 4; /* Persistent LOD section table, no frustum/facing rejection in this fixture. */
    uint64_t counters[2] = {0};
    CHECK(mcmtl_ext_cluster_cull_stats(counters) == MCMTL_OK && counters[1] == 0);
    id<MTLCommandBuffer> cb = [far commandBuffer];
    memset(args.contents, 0xa5, args.length);
    CHECK(mcmtl_clusters_encode(dev, cb, &job, 1, view, sizeof view, NULL, NULL, NULL) == MCMTL_OK);
    CHECK(cb.status == MTLCommandBufferStatusNotEnqueued); /* Encoding never commits behind caller's back. */
    CHECK(dev->currentCommandBuffer == nil && dev->event.signaledValue == 0);
    if (finish(cb)) {
        const uint32_t expectedArgs[5] = {18, 1, 0, 0, 0}, expectedQuads[3] = {0, 1, 64}, expectedItems[4] = {0, 0, 8, 0};
        CHECK(memcmp(args.contents, expectedArgs, sizeof expectedArgs) == 0);
        CHECK(memcmp(quads.contents, expectedQuads, sizeof expectedQuads) == 0);
        CHECK(memcmp(out.contents, expectedItems, sizeof expectedItems) == 0);
    }
    CHECK(mcmtl_ext_cluster_cull_stats(counters) == MCMTL_OK && counters[1] == 0);
    CHECK(mcmtl_clusters_encode(dev, cb, &job, 1, view, sizeof view, NULL, NULL, NULL) == MCMTL_E_INVALID_ARGUMENT);
    CHECK(mcmtl_clusters_encode(dev, nil, &job, 1, view, sizeof view, NULL, NULL, NULL) == MCMTL_E_INVALID_ARGUMENT);

    /* An invalid later job must reject the batch before the valid first job writes anything. */
    mcmtl_cluster_job invalid[2] = {job, job};
    invalid[1].quadCapacity = 1;
    memset(args.contents, 0x63, args.length);
    cb = [far commandBuffer];
    CHECK(mcmtl_clusters_encode(dev, cb, invalid, 2, view, sizeof view, NULL, NULL, NULL) == MCMTL_E_INVALID_ARGUMENT);
    CHECK(cb.status == MTLCommandBufferStatusNotEnqueued);
    if (finish(cb)) for (NSUInteger i = 0; i < args.length; i++) CHECK(((uint8_t *)args.contents)[i] == 0x63);

    /* A stalled far queue must not stall an unrelated command on the game's queue. */
    id<MTLSharedEvent> gate = [dev->device newSharedEvent];
    cb = [far commandBuffer];
    [cb encodeWaitForEvent:gate value:1];
    CHECK(mcmtl_clusters_encode(dev, cb, &job, 1, view, sizeof view, NULL, NULL, NULL) == MCMTL_OK);
    dispatch_semaphore_t farDone = dispatch_semaphore_create(0);
    [cb addCompletedHandler:^(id<MTLCommandBuffer> unused) { dispatch_semaphore_signal(farDone); }];
    [cb commit];
    id<MTLBuffer> marker = [dev->device newBufferWithLength:16 options:MTLResourceStorageModeShared];
    id<MTLCommandBuffer> main = [dev->queue commandBuffer];
    id<MTLBlitCommandEncoder> blit = [main blitCommandEncoder];
    [blit fillBuffer:marker range:NSMakeRange(0, 16) value:0x7b];
    [blit endEncoding];
    if (finish(main)) CHECK(((uint8_t *)marker.contents)[0] == 0x7b);
    CHECK(cb.status != MTLCommandBufferStatusCompleted);
    gate.signaledValue = 1;
    CHECK(dispatch_semaphore_wait(farDone, dispatch_time(DISPATCH_TIME_NOW, 5 * NSEC_PER_SEC)) == 0);
    CHECK(cb.status == MTLCommandBufferStatusCompleted);
    CHECK(((uint32_t *)args.contents)[0] == 18);
    CHECK(dev->currentCommandBuffer == nil && dev->event.signaledValue == 0);

    /* The fused, section-occlusion producer path uses the same private output contract. */
    id<MTLBuffer> vis = [dev->device newBufferWithLength:512 options:MTLResourceStorageModeShared];
    id<MTLBuffer> boxes = [dev->device newBufferWithLength:64 options:MTLResourceStorageModeShared];
    id<MTLBuffer> params = [dev->device newBufferWithLength:64 options:MTLResourceStorageModeShared];
    id<MTLBuffer> sitems = [dev->device newBufferWithLength:16 options:MTLResourceStorageModeShared];
    id<MTLBuffer> secOcc = [dev->device newBufferWithLength:1u << 20 options:MTLResourceStorageModeShared];
    memset(vis.contents, 0, vis.length); memset(params.contents, 0, params.length); memset(sitems.contents, 0, sitems.length);
    ((uint32_t *)sitems.contents)[1] = 1u << 29;
    mcmtl_lod_occl oc = {.visBuffer = handle(vis), .boxesBuffer = handle(boxes), .paramsBuffer = handle(params),
        .boxCapacity = 8, .stamp = 1, .visEntries = 128, .eyeMin = 2, .prevKey = 0xfffffffe};
    mcmtl_lod_sec sec = {.sitemsBuffer = handle(sitems), .secOccBuffer = handle(secOcc), .count = 1, .visBase = 64};
    uint32_t extra[2] = {0, 0};
    view[21] = 4 | 8 | 16;
    cb = [far commandBuffer];
    CHECK(mcmtl_clusters_encode(dev, cb, &job, 1, view, sizeof view, &oc, extra, &sec) == MCMTL_OK);
    CHECK(cb.status == MTLCommandBufferStatusNotEnqueued);
    if (finish(cb)) {
        CHECK(((uint32_t *)args.contents)[0] == 18 && ((uint32_t *)args.contents)[1] == 1);
        const uint32_t expected[3] = {0, 1, 64};
        CHECK(memcmp(quads.contents, expected, sizeof expected) == 0);
        CHECK(((uint8_t *)secOcc.contents)[0] == 0);
    }
    CHECK(mcmtl_ext_cluster_cull_stats(counters) == MCMTL_OK && counters[1] == 0);
    mcmtl_device_destroy(deviceHandle);
    printf("farimage_cull_test: %d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
} }
