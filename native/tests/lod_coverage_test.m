/* Focused, headless coverage-extension regression. Run tools/lod-coverage-test.sh to
 * compile current native sources into unique temporary outputs and run with Metal API
 * and GPU shader validation. The runner removes its temporary directory on exit.
 * No game, surfaces, shared build outputs, or production waits.
 */
#import "mcmetal_internal.h"
#import "mcmetal_clusters.h"
#include <dispatch/dispatch.h>
#include <stdio.h>
#include <string.h>

extern int32_t mcmtl_ext_cluster_cull(mcmtl_handle, const void *, uint32_t, const void *, uint32_t);
extern int32_t mcmtl_ext_lod_cull(mcmtl_handle, const void *, uint32_t, const void *, uint32_t, const void *, const void *);
extern int32_t mcmtl_ext_lod_cull_sec(mcmtl_handle, const void *, uint32_t, const void *, uint32_t, const void *, const void *, const void *);
static int checks, failures;
#define CHECK(x) do { checks++; if (!(x)) { failures++; fprintf(stderr, "FAIL line %d: %s (%s)\n", __LINE__, #x, mcmtl_last_error()); } } while (0)
enum { N = 67, PAD = 64, STRIDE = 4096, MASK_OFFSET = 7, SLOTS = 8, MAX_SLOT = (1u << 20) - 1u };
static uint64_t handle(id object) { return (uint64_t)(uintptr_t)(__bridge void *)object; }
static uint32_t bounds(int v) { uint32_t b = (uint32_t)(512 + v); return b | (b << 10) | (b << 20); }
static uint32_t slotOf(uint32_t c) { return c == N - 1 ? MAX_SLOT : c % SLOTS; }
static BOOL invalidBounds(uint32_t sec) { return sec == 2 || sec == 3 || sec == MAX_SLOT; }
static uint32_t quadCount(uint32_t c) { return 1 + c % 4; }
static BOOL hidden(uint32_t c, const uint8_t *mask, uint32_t count) { return slotOf(c) < count && mask[slotOf(c)] != 0; }
static id<MTLBuffer> buffer(MCMTLDevice *dev, NSUInteger length) {
    id<MTLBuffer> b = [dev->device newBufferWithLength:length options:MTLResourceStorageModeShared];
    CHECK(b != nil);
    if (!b) exit(1);
    memset(b.contents, 0, length);
    return b;
}
/* A queue fence is only for reading test results; the extension itself never waits. */
static void finish(id<MTLCommandBuffer> cb) {
    dispatch_semaphore_t done = dispatch_semaphore_create(0);
    [cb addCompletedHandler:^(id<MTLCommandBuffer> unused) { dispatch_semaphore_signal(done); }];
    [cb commit];
    if (dispatch_semaphore_wait(done, dispatch_time(DISPATCH_TIME_NOW, 10 * NSEC_PER_SEC)) != 0) {
        fprintf(stderr, "GPU test timed out\n"); exit(1);
    }
    CHECK(cb.status == MTLCommandBufferStatusCompleted);
    if (cb.status != MTLCommandBufferStatusCompleted) { fprintf(stderr, "%s\n", cb.error.description.UTF8String); exit(1); }
}

@interface CoverageFixture : NSObject {
@public
    MCMTLDevice *dev;
    id<MTLBuffer> items, sections, out, quads, args, scratch, mask, vis, boxes, params, sitems, secOcc;
    mcmtl_cluster_job jobs[2];
    mcmtl_lod_occl oc;
    mcmtl_lod_sec sec;
    uint32_t view[32], extra[4];
}
- (instancetype)initWithDevice:(MCMTLDevice *)device;
- (void)resetOutputs;
- (void)verifyMask:(const uint8_t *)bytes count:(uint32_t)count ordered:(BOOL)ordered;
@end
@implementation CoverageFixture
- (instancetype)initWithDevice:(MCMTLDevice *)device {
    if (!(self = [super init])) return nil;
    dev = device;
    items = buffer(dev, PAD + 16 * N); sections = buffer(dev, PAD + 32 * SLOTS);
    out = buffer(dev, PAD + 2 * STRIDE); quads = buffer(dev, PAD + 2 * STRIDE);
    args = buffer(dev, PAD + 2 * STRIDE); scratch = buffer(dev, PAD + 2 * STRIDE);
    mask = buffer(dev, MASK_OFFSET + (1u << 20) + 4);
    vis = buffer(dev, PAD + 4096); boxes = buffer(dev, PAD + 8 * 256);
    params = buffer(dev, PAD + 64); sitems = buffer(dev, PAD + 16 * (SLOTS + 1));
    secOcc = buffer(dev, PAD + (1u << 20));
    for (uint32_t c = 0; c < N; c++) {
        uint32_t slot = slotOf(c);
        uint32_t it[4] = {32 * c, slot | ((quadCount(c) - 1) << 20) | (4u << 26)
            | (invalidBounds(slot) ? 1u << 29 : 0), bounds(-16), bounds(16)};
        memcpy((uint8_t *)items.contents + PAD + 16 * c, it, sizeof it);
    }
    for (uint32_t i = 0; i < SLOTS + 1; i++) {
        uint32_t slot = i == SLOTS ? MAX_SLOT : i;
        uint32_t it[4] = {0, slot | (invalidBounds(slot) ? 1u << 29 : 0), bounds(-16), bounds(16)};
        memcpy((uint8_t *)sitems.contents + PAD + 16 * i, it, sizeof it);
        if (i < SLOTS) {
            float values[8] = {0, 64 + 16 * (float)i, 10, 0.0625f, 0, 0, 0, 0};
            memcpy((uint8_t *)sections.contents + PAD + 32 * i, values, sizeof values);
        }
    }
    for (uint32_t j = 0; j < 2; j++) {
        jobs[j] = (mcmtl_cluster_job){.itemsBuffer = handle(items), .itemsOffset = PAD,
            .sectionsBuffer = handle(sections), .sectionsOffset = PAD, .outItemsBuffer = handle(out), .outItemsOffset = PAD + STRIDE * j,
            .quadsBuffer = handle(quads), .quadsOffset = PAD + STRIDE * j, .argsBuffer = handle(args), .argsOffset = PAD + STRIDE * j,
            .scratchBuffer = handle(scratch), .scratchOffset = PAD + STRIDE * j, .clusterCount = N, .quadCapacity = 4 * N};
        extra[2 * j] = 128 * j; extra[2 * j + 1] = N * j;
    }
    oc = (mcmtl_lod_occl){.visBuffer = handle(vis), .visOffset = PAD, .boxesBuffer = handle(boxes), .boxesOffset = PAD,
        .paramsBuffer = handle(params), .paramsOffset = PAD, .boxCapacity = 256, .stamp = 12, .visEntries = 1024,
        .eyeMin = 2, .prevKey = UINT32_MAX - 1};
    sec = (mcmtl_lod_sec){.sitemsBuffer = handle(sitems), .sitemsOffset = PAD,
        .secOccBuffer = handle(secOcc), .secOccOffset = PAD, .count = SLOTS + 1, .visBase = 512};
    memset(view, 0, sizeof view);
    for (int i = 0; i < 5; i++) { float d = 10000; memcpy(&view[4 * i + 3], &d, 4); }
    return self;
}
- (void)resetOutputs {
    memset(out.contents, 0xa5, out.length); memset(quads.contents, 0xa5, quads.length);
    memset(args.contents, 0xa5, args.length); memset(scratch.contents, 0xa5, scratch.length);
    memset(params.contents, 0, params.length); memset(secOcc.contents, 0xa5, secOcc.length);
}
- (void)verifyMask:(const uint8_t *)bytes count:(uint32_t)count ordered:(BOOL)ordered {
    uint32_t expected[N * 4], total = 0;
    for (uint32_t c = 0; c < N; c++) if (!hidden(c, bytes, count))
        for (uint32_t q = 0; q < quadCount(c); q++) expected[total++] = (c << 6) | q;
    for (uint32_t j = 0; j < 2; j++) {
        const uint32_t *a = (const uint32_t *)((const uint8_t *)args.contents + jobs[j].argsOffset);
        CHECK(a[0] == 6 * total && a[1] == 1 && a[2] == 0 && a[3] == 0 && a[4] == 0);
        const uint32_t *actual = (const uint32_t *)((const uint8_t *)quads.contents + jobs[j].quadsOffset);
        BOOL seen[N][4] = {{NO}};
        for (uint32_t q = 0; q < total; q++) {
            uint32_t c = actual[q] >> 6, k = actual[q] & 63;
            BOOL valid = c < N && k < quadCount(c) && !hidden(c, bytes, count);
            CHECK(valid);
            if (valid) { CHECK(!seen[c][k]); seen[c][k] = YES; }
            if (ordered) CHECK(actual[q] == expected[q]);
        }
        CHECK(actual[total] == 0xa5a5a5a5u);
        const uint32_t *oi = (const uint32_t *)((const uint8_t *)out.contents + jobs[j].outItemsOffset);
        for (uint32_t c = 0; c < N; c++) CHECK(oi[2*c] == 32*c && oi[2*c+1] == slotOf(c));
        CHECK(oi[2*N] == 0xa5a5a5a5u);
    }
    for (uint32_t i = 0; i < PAD; i++) {
        CHECK(((const uint8_t *)args.contents)[i] == 0xa5);
        CHECK(((const uint8_t *)quads.contents)[i] == 0xa5);
        CHECK(((const uint8_t *)out.contents)[i] == 0xa5);
    }
}
@end

static void cull(CoverageFixture *f, BOOL occlusion, BOOL section, uint32_t count) {
    int32_t rc = mcmtl_ext_lod_cull_coverage(handle(f->dev), f->jobs, 2, f->view, f->view[21] == 0 ? 92 : 128,
        occlusion ? &f->oc : NULL, occlusion ? f->extra : NULL, section ? &f->sec : NULL,
        handle(f->mask), MASK_OFFSET, count);
    CHECK(rc == MCMTL_OK);
    if (rc != MCMTL_OK) exit(1);
    finish([f->dev->queue commandBuffer]);
}
static void verifyOcclusion(CoverageFixture *f, const uint8_t *mask, uint32_t count, BOOL section) {
    const uint32_t *p = (const uint32_t *)((const uint8_t *)f->params.contents + PAD);
    const uint32_t *v = (const uint32_t *)((const uint8_t *)f->vis.contents + PAD);
    const uint32_t *b = (const uint32_t *)((const uint8_t *)f->boxes.contents + PAD);
    uint32_t expected = 0;
    if (section) {
        for (uint32_t i = 0; i < SLOTS + 1; i++) {
            uint32_t s = i == SLOTS ? MAX_SLOT : i;
            BOOL hide = s < count && mask[s] != 0;
            CHECK(((const uint8_t *)f->secOcc.contents)[PAD + s] == (hide ? 1 : 0));
            if (!hide && !invalidBounds(s)) expected++;
            if (hide && s < SLOTS) CHECK(v[f->sec.visBase + s] == (f->oc.stamp << 1 | 1));
        }
    } else {
        for (uint32_t j = 0; j < 2; j++) for (uint32_t c = 0; c < N; c++) {
            if (!hidden(c, mask, count) && !invalidBounds(slotOf(c))) expected++;
            if (hidden(c, mask, count)) CHECK(v[f->extra[2*j] + c] == (f->oc.stamp << 1 | 1));
        }
    }
    CHECK(p[5] == expected && p[0] == 18 * expected && p[6] == 0 && p[7] == 0);
    for (uint32_t i = 0; i < p[5] && i < 256; i++) {
        uint32_t slot = section ? b[2*i] : slotOf(b[2*i] % N);
        CHECK(slot >= count || mask[slot] == 0);
    }
}

int main(void) { @autoreleasepool {
    mcmtl_device_desc desc = {.structSize = sizeof desc, .framesInFlight = 2};
    mcmtl_handle device = 0;
    CHECK(mcmtl_device_create(&desc, &device) == MCMTL_OK);
    if (!device) return 1;
    CoverageFixture *f = [[CoverageFixture alloc] initWithDevice:MCMTL_DEVICE(device)];
    uint8_t *mask = (uint8_t *)f->mask.contents + MASK_OFFSET;
    const uint8_t mixed[SLOTS] = {1, 0, 255, 0, 0, 128, 0, 0};
    /* Prefix/suffix poison exposes lost backing offsets or reads beyond coverageCount. */
    memset(f->mask.contents, 0xff, f->mask.length); memcpy(mask, mixed, sizeof mixed);
    const uint32_t modes[] = {0, 4 | 1, 4 | 8 | 16, 4 | 1, 4 | 1 | 8, 4 | 1 | 8 | 16, 4 | 1 | 8 | 16};
    for (uint32_t mode = 0; mode < sizeof modes / sizeof modes[0]; mode++) {
        BOOL occlusion = mode >= 3, section = mode == 5;
        f->view[21] = modes[mode]; [f resetOutputs]; memset(f->vis.contents, 0, f->vis.length);
        cull(f, occlusion, section, SLOTS);
        [f verifyMask:mask count:SLOTS ordered:mode < 4];
        if (occlusion) verifyOcclusion(f, mask, SLOTS, section);
    }
    /* A shorter valid mask is a bounded prefix: other slots, even UINT20_MAX, stay visible. */
    f->view[21] = 4 | 1 | 8; [f resetOutputs]; memset(f->vis.contents, 0, f->vis.length);
    cull(f, YES, NO, 3); [f verifyMask:mask count:3 ordered:NO];
    /* Every SIMD lane covered, including the last partial group and maximum section slot. */
    f->view[21] = 4 | 1 | 8 | 16;
    memset(mask, 0xff, 1u << 20); [f resetOutputs];
    cull(f, YES, YES, 1u << 20); [f verifyMask:mask count:1u << 20 ordered:NO];
    verifyOcclusion(f, mask, 1u << 20, YES);
    /* A covered max slot must skip bounds/table reads even when its bounds are marked usable. */
    uint32_t *lastItem = (uint32_t *)((uint8_t *)f->items.contents + PAD + 16 * (N - 1));
    uint32_t *lastSection = (uint32_t *)((uint8_t *)f->sitems.contents + PAD + 16 * SLOTS);
    lastItem[1] &= ~(1u << 29); lastSection[1] &= ~(1u << 29); [f resetOutputs];
    cull(f, YES, YES, 1u << 20); [f verifyMask:mask count:1u << 20 ordered:NO];
    lastItem[1] |= 1u << 29; lastSection[1] |= 1u << 29;
    /* Fresh mask generation uncovers ALL slots; invalid bounds must draw as well. */
    memset(mask, 0, 1u << 20); [f resetOutputs];
    cull(f, YES, NO, 1u << 20); [f verifyMask:mask count:1u << 20 ordered:NO];

    /* Covered entries with stale occlusion become ineligible/visible. Reusing that visibility
     * generation on uncover must not revive the stale key. Exercise serial, fused and sections. */
    for (uint32_t mode = 0; mode < 3; mode++) {
        f->view[21] = 4 | 1 | (mode ? 8 : 0) | (mode == 2 ? 16 : 0);
        f->oc.stamp = 12; f->oc.prevKey = 20;
        uint32_t *v = (uint32_t *)((uint8_t *)f->vis.contents + PAD);
        for (uint32_t i = 0; i < 1024; i++) v[i] = 20;
        memset(mask, 0xff, 1u << 20); [f resetOutputs];
        cull(f, YES, mode == 2, 1u << 20); [f verifyMask:mask count:1u << 20 ordered:mode == 0];
        verifyOcclusion(f, mask, 1u << 20, mode == 2);
        memset(mask, 0, 1u << 20); f->oc.stamp++; [f resetOutputs];
        cull(f, YES, mode == 2, 1u << 20); [f verifyMask:mask count:1u << 20 ordered:mode == 0];
        /* Another ring generation still holds the old occluded key: caller invalidates trust. */
        for (uint32_t i = 0; i < 1024; i++) v[i] = 20;
        f->oc.stamp++; f->oc.prevKey = UINT32_MAX - 1; [f resetOutputs];
        cull(f, YES, mode == 2, SLOTS); [f verifyMask:mask count:SLOTS ordered:mode == 0];
        verifyOcclusion(f, mask, SLOTS, mode == 2);
    }

    /* Private arguments disable fusion; the ordinary occlusion fallback must still mask. */
    memcpy(mask, mixed, sizeof mixed); f->view[21] = 4 | 1 | 8 | 16; [f resetOutputs];
    id<MTLBuffer> privateArgs = [f->dev->device newBufferWithLength:f->args.length options:MTLResourceStorageModePrivate];
    CHECK(privateArgs != nil);
    for (int j = 0; j < 2; j++) f->jobs[j].argsBuffer = handle(privateArgs);
    cull(f, YES, YES, SLOTS);
    id<MTLCommandBuffer> copy = [f->dev->queue commandBuffer];
    id<MTLBlitCommandEncoder> blit = [copy blitCommandEncoder];
    for (int j = 0; j < 2; j++) [blit copyFromBuffer:privateArgs sourceOffset:f->jobs[j].argsOffset
        toBuffer:f->args destinationOffset:f->jobs[j].argsOffset size:20];
    [blit endEncoding]; finish(copy); [f verifyMask:mask count:SLOTS ordered:YES];
    for (int j = 0; j < 2; j++) f->jobs[j].argsBuffer = handle(f->args);

    /* Exact-end, unaligned byte range in a GPU-only mask: no CPU contents are required. */
    id<MTLBuffer> privateMask = [f->dev->device newBufferWithLength:8 options:MTLResourceStorageModePrivate];
    CHECK(privateMask != nil);
    copy = [f->dev->queue commandBuffer]; blit = [copy blitCommandEncoder];
    [blit fillBuffer:privateMask range:NSMakeRange(0, 8) value:255]; [blit endEncoding]; [copy commit];
    f->view[21] = 4 | 1 | 8; [f resetOutputs];
    CHECK(mcmtl_ext_lod_cull_coverage(device, f->jobs, 2, f->view, 128, &f->oc, f->extra, NULL,
        handle(privateMask), 7, 1) == MCMTL_OK);
    finish([f->dev->queue commandBuffer]);
    const uint8_t firstCovered = 255;
    [f verifyMask:&firstCovered count:1 ordered:NO];

    /* Two immutable mask generations may be queued while GPU progress is stalled. Neither
     * call waits or replaces another call's mask binding; output storage is exclusive. */
    CoverageFixture *next = [[CoverageFixture alloc] initWithDevice:f->dev];
    memset(mask, 0xff, 1u << 20); [f resetOutputs]; [next resetOutputs];
    f->view[21] = next->view[21] = 4 | 1 | 8;
    id<MTLSharedEvent> gate = [f->dev->device newSharedEvent];
    id<MTLCommandBuffer> stalled = [f->dev->queue commandBuffer];
    [stalled encodeWaitForEvent:gate value:1]; [stalled commit];
    CHECK(mcmtl_ext_lod_cull_coverage(device, f->jobs, 2, f->view, 128, &f->oc, f->extra, NULL,
        handle(f->mask), MASK_OFFSET, 1u << 20) == MCMTL_OK);
    CHECK(mcmtl_ext_lod_cull_coverage(device, next->jobs, 2, next->view, 128, &next->oc, next->extra, NULL,
        handle(next->mask), MASK_OFFSET, 1u << 20) == MCMTL_OK);
    CHECK(stalled.status != MTLCommandBufferStatusCompleted);
    gate.signaledValue = 1; finish([f->dev->queue commandBuffer]);
    [f verifyMask:mask count:1u << 20 ordered:NO];
    [next verifyMask:(const uint8_t *)next->mask.contents + MASK_OFFSET count:1u << 20 ordered:NO];

    /* Existing exports must ignore the last mask, with their original struct/view layouts. */
    memset(mask, 0xff, 1u << 20);
    for (uint32_t mode = 0; mode < 4; mode++) {
        f->view[21] = mode == 0 ? 0 : (4 | 1 | 8 | (mode == 3 ? 16 : 0));
        [f resetOutputs]; memset(f->vis.contents, 0, f->vis.length);
        int32_t rc;
        if (mode == 0) rc = mcmtl_ext_cluster_cull(device, f->jobs, 2, f->view, 92);
        else if (mode == 1) rc = mcmtl_ext_lod_cull(device, f->jobs, 2, f->view, 128, NULL, NULL);
        else if (mode == 2) rc = mcmtl_ext_lod_cull(device, f->jobs, 2, f->view, 128, &f->oc, f->extra);
        else rc = mcmtl_ext_lod_cull_sec(device, f->jobs, 2, f->view, 128, &f->oc, f->extra, &f->sec);
        CHECK(rc == MCMTL_OK); finish([f->dev->queue commandBuffer]);
        [f verifyMask:NULL count:0 ordered:mode < 2];
    }

    /* Bad ranges/handles, including integer overflow and zero-job calls, fail before writes. */
    [f resetOutputs];
    const struct { uint64_t buffer, offset; uint32_t count; } bad[] = {
        {0, 0, SLOTS}, {handle(@"not a Metal buffer"), 0, SLOTS}, {handle(f->mask), 0, 0},
        {handle(f->mask), 0, (1u << 20) + 1}, {handle(f->mask), f->mask.length, 1},
        {handle(f->mask), f->mask.length - 1, 2}, {handle(f->mask), UINT64_MAX - 3, SLOTS}
    };
    for (uint32_t i = 0; i < sizeof bad / sizeof bad[0]; i++) {
        for (uint32_t jobs = 0; jobs <= 2; jobs += 2)
            CHECK(mcmtl_ext_lod_cull_coverage(device, f->jobs, jobs, f->view, 128, &f->oc, f->extra, &f->sec,
                bad[i].buffer, bad[i].offset, bad[i].count) == MCMTL_E_INVALID_ARGUMENT);
    }
    CHECK(mcmtl_ext_lod_cull_coverage(device, f->jobs, 2, f->view, 128, NULL, NULL, &f->sec,
        handle(f->mask), MASK_OFFSET, SLOTS) == MCMTL_E_INVALID_ARGUMENT);
    CHECK(mcmtl_ext_lod_cull_coverage(device, f->jobs, 2, f->view, 128, &f->oc, NULL, NULL,
        handle(f->mask), MASK_OFFSET, SLOTS) == MCMTL_E_INVALID_ARGUMENT);
    mcmtl_cluster_job badJobs[2] = {f->jobs[0], f->jobs[1]}; badJobs[1].quadCapacity = 0;
    CHECK(mcmtl_ext_lod_cull_coverage(device, badJobs, 2, f->view, 128, &f->oc, f->extra, &f->sec,
        handle(f->mask), MASK_OFFSET, SLOTS) == MCMTL_E_INVALID_ARGUMENT);
    finish([f->dev->queue commandBuffer]);
    for (NSUInteger i = 0; i < f->args.length; i++) CHECK(((const uint8_t *)f->args.contents)[i] == 0xa5);
    CHECK(f->dev->currentCommandBuffer == nil && f->dev->event.signaledValue == 0);
    mcmtl_device_destroy(device);
    printf("lod_coverage_test: %d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
} }
