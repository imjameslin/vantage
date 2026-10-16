/*
 * frametrace_test.m -- headless test of the bench frame trace (mcmetal_frametrace.m, CONTRACT.md 16.R5-B) through the
 * real mcmtl_submit export (guard + decoder). Built and run by tools/build-native-tests.sh (MTL_DEBUG_LAYER=1).
 *
 * Checks: nothing is recorded before mcmtl_frametrace_enable (and the decoder counters still work); enable fills the
 * info struct, is idempotent and clamps the capacity; every COMMIT submit gets an entry tagged with its value whose
 * render-thread fields (commit time, FRAME_END, command buffers committed, encoders) and handler fields (GPU start /
 * end / busy in the host clock, completions, SIGNAL_DONE) are consistent with the clock around the submit; a
 * flush-style COMMIT without FRAME_END; ring wrap-around; lock-free aggregation of many concurrent completions of one
 * value (min / max / sum / count exact); several real command buffers (untimed + signalled) attributed to one value;
 * the counters and config exports (JSON parses, effective keys present, counter names complete).
 */
#import "mcmetal_internal.h"
#import "mcmetal_frametrace.h"
#import <QuartzCore/QuartzCore.h>

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static int failures = 0;
static int checks = 0;

#define CHECK(cond, ...)                                                   \
    do {                                                                   \
        checks++;                                                          \
        if (!(cond)) {                                                     \
            failures++;                                                    \
            printf("FAIL %s:%d: ", __FILE__, __LINE__);                    \
            printf(__VA_ARGS__);                                           \
            printf("\n");                                                  \
        }                                                                  \
    } while (0)

static MCMTLDevice *gDev;
static mcmtl_handle gDevHandle;
static id<MTLDevice> gMtl;
static uint64_t gNextValue = 1;
static id<MTLTexture> gTarget;

static inline mcmtl_handle H(id object) {
    return object ? (mcmtl_handle)(uintptr_t)(__bridge void *)object : 0;
}

typedef struct Stream {
    uint8_t *data;
    size_t length;
    size_t capacity;
} Stream;

static void *rec_add(Stream *s, uint16_t op, size_t bytes) {
    size_t size = (bytes + 15u) & ~(size_t)15u;
    if (s->length + size > s->capacity) {
        size_t cap = s->capacity ? s->capacity * 2 : 4096;
        while (cap < s->length + size) cap *= 2;
        s->data = realloc(s->data, cap);
        s->capacity = cap;
    }
    uint8_t *p = s->data + s->length;
    memset(p, 0, size);
    s->length += size;
    ((mcmtl_cmd_header *)p)->op = op;
    ((mcmtl_cmd_header *)p)->size = (uint16_t)size;
    return p;
}

/* A full-target clear: real GPU work (a render pass on a 2048x2048 RGBA8 target). */
static void r_clear(Stream *s, float v) {
    mcmtl_cmd_clear_texture *c = rec_add(s, MCMTL_OP_CLEAR_TEXTURE, sizeof *c);
    c->aspects = MCMTL_ASPECT_COLOR;
    c->texture = H(gTarget);
    c->baseLevel = 0;
    c->levelCount = 1;
    c->color[0] = v;
    c->color[1] = 1.0f - v;
    c->color[2] = 0.25f;
    c->color[3] = 1.0f;
}

static BOOL wait_prefix(uint64_t value) {
    for (int i = 0; i < 50000; i++) {
        if (mcmtl_status_load_u64(&gDev->status->completedValue) >= value) return YES;
        usleep(100);
    }
    return NO;
}

/* mcmtl_submit through the real export with the next dense value; waits for completion. */
static uint64_t submit(Stream *s, uint32_t flags, mcmtl_submit_result *out) {
    mcmtl_submit_info info;
    memset(&info, 0, sizeof info);
    info.structSize = sizeof info;
    info.flags = flags;
    info.stream = s && s->length ? s->data : NULL;
    info.streamLength = s ? s->length : 0;
    const uint64_t value = gNextValue++;
    info.signalValue = value;
    mcmtl_submit_result result;
    memset(&result, 0, sizeof result);
    result.structSize = sizeof result;
    int32_t rc = mcmtl_submit(gDevHandle, &info, &result);
    CHECK(rc == MCMTL_OK, "submit(%llu) rc %d %s", (unsigned long long)value, rc, mcmtl_last_error());
    int32_t w = mcmtl_wait_completed(gDev, value, 5000000000ull);
    CHECK(w == MCMTL_OK, "wait(%llu) -> %d", (unsigned long long)value, w);
    CHECK(wait_prefix(value), "prefix did not reach %llu", (unsigned long long)value);
    if (out) *out = result;
    if (s) s->length = 0;
    return value;
}

static mcmtl_ft_entry *entry(const mcmtl_ft_info *info, uint64_t value) {
    mcmtl_ft_entry *ring = (mcmtl_ft_entry *)(uintptr_t)info->ring;
    return &ring[value & (info->capacity - 1)];
}

/* Completion handlers advance completedValue before... no: they update the entry first, then the prefix. Still, the
 * handler of an untimed command buffer is not ordered with the prefix: poll until every committed one reported. */
static BOOL wait_handlers(const mcmtl_ft_entry *e, uint32_t expected) {
    for (int i = 0; i < 20000; i++) {
        if (__atomic_load_n(&e->cbCompleted, __ATOMIC_ACQUIRE) >= expected) return YES;
        usleep(100);
    }
    return NO;
}

static uint64_t now_uptime(void) {
    return clock_gettime_nsec_np(CLOCK_UPTIME_RAW);
}

static void test_off(void) {
    CHECK(!mcmtl_ft_on(), "trace must be off before enable");
    uint64_t c0[MCMTL_FT_C_COUNT], c1[MCMTL_FT_C_COUNT];
    CHECK(mcmtl_frametrace_counters(gDevHandle, c0, MCMTL_FT_C_COUNT) == MCMTL_FT_C_COUNT, "counters count");
    Stream s = { 0 };
    r_clear(&s, 0.5f);
    submit(&s, MCMTL_SUBMIT_FLAG_COMMIT | MCMTL_SUBMIT_FLAG_FRAME_END, NULL);
    CHECK(mcmtl_frametrace_counters(gDevHandle, c1, MCMTL_FT_C_COUNT) == MCMTL_FT_C_COUNT, "counters count");
    CHECK(c1[MCMTL_FT_C_FRAMES] == c0[MCMTL_FT_C_FRAMES] + 1, "frames counter +1 (%llu -> %llu)",
          (unsigned long long)c0[MCMTL_FT_C_FRAMES], (unsigned long long)c1[MCMTL_FT_C_FRAMES]);
    CHECK(c1[MCMTL_FT_C_CB_COMMITTED] >= c0[MCMTL_FT_C_CB_COMMITTED] + 1, "command buffers counter");
    CHECK(mcmtl_frametrace_counters(gDevHandle, c1, 3) == 3, "counters honour capacity");
    CHECK(mcmtl_frametrace_counters(0, c1, 3) == 0, "counters without device");
    free(s.data);
    printf("off: ok\n");
}

static void test_frames(mcmtl_ft_info *info) {
    memset(info, 0, sizeof *info);
    info->structSize = 1;
    CHECK(mcmtl_frametrace_enable(gDevHandle, 10, info) == MCMTL_E_ABI_MISMATCH, "enable must check structSize");
    info->structSize = sizeof *info;
    CHECK(mcmtl_frametrace_enable(0, 10, info) == MCMTL_E_INVALID_ARGUMENT, "enable without device");
    CHECK(mcmtl_frametrace_enable(gDevHandle, 3, info) == MCMTL_OK, "enable: %s", mcmtl_last_error());
    CHECK(info->capacity == 256, "capacity clamped to 2^8, got %llu", (unsigned long long)info->capacity);
    CHECK(info->entrySize == sizeof(mcmtl_ft_entry) && info->entrySize == 64, "entry size %u", info->entrySize);
    CHECK(info->ring != 0 && (info->ring & 63) == 0, "ring 64-byte aligned");
    const int64_t clockDelta = (int64_t)info->uptimeNs - (int64_t)info->mediaNs;
    CHECK(clockDelta > -100000 && clockDelta < 100000, "CLOCK_UPTIME_RAW vs CACurrentMediaTime differ by %lld ns",
          (long long)clockDelta);
    mcmtl_ft_info again = { .structSize = sizeof again };
    CHECK(mcmtl_frametrace_enable(gDevHandle, 20, &again) == MCMTL_OK && again.ring == info->ring && again.capacity == 256,
          "enable is idempotent for the same device");
    CHECK(mcmtl_ft_on(), "trace on after enable");

    Stream s = { 0 };
    for (int f = 0; f < 24; f++) {
        for (int k = 0; k < 6; k++) r_clear(&s, (float)((f + k) % 7) / 7.0f);
        const uint64_t before = now_uptime();
        mcmtl_submit_result res;
        const uint64_t v = submit(&s, MCMTL_SUBMIT_FLAG_COMMIT | MCMTL_SUBMIT_FLAG_FRAME_END, &res);
        const uint64_t after = now_uptime();
        mcmtl_ft_entry *e = entry(info, v);
        CHECK(e->value == v, "entry tag %llu != value %llu", (unsigned long long)e->value, (unsigned long long)v);
        CHECK(e->cbCommitted >= 1, "frame %d: cbCommitted %u", f, e->cbCommitted);
        CHECK(wait_handlers(e, e->cbCommitted), "frame %d: %u of %u handlers", f, e->cbCompleted, e->cbCommitted);
        const uint32_t flags = __atomic_load_n(&e->flags, __ATOMIC_ACQUIRE);
        CHECK(flags & MCMTL_FT_FLAG_FRAME_END, "frame %d: FRAME_END flag (0x%x)", f, flags);
        CHECK(flags & MCMTL_FT_FLAG_SUBMITTED, "frame %d: SUBMITTED flag (0x%x)", f, flags);
        CHECK(flags & MCMTL_FT_FLAG_SIGNAL_DONE, "frame %d: SIGNAL_DONE flag (0x%x)", f, flags);
        CHECK(!(flags & (MCMTL_FT_FLAG_FAILED | MCMTL_FT_FLAG_NOT_COMMITTED | MCMTL_FT_FLAG_PRESENT_RECORD)),
              "frame %d: unexpected flags 0x%x", f, flags);
        CHECK(e->commitNs >= before && e->commitNs <= after, "frame %d: commitNs outside the submit call", f);
        CHECK(e->encoders >= 1, "frame %d: encoders %u", f, e->encoders);
        CHECK(e->gpuStartNs != UINT64_MAX && e->gpuEndNs > e->gpuStartNs, "frame %d: GPU interval [%llu, %llu]", f,
              (unsigned long long)e->gpuStartNs, (unsigned long long)e->gpuEndNs);
        /* Same host clock: the GPU ran after the submit began and finished before the wait returned. */
        CHECK(e->gpuStartNs + 50000 >= before && e->gpuEndNs <= after + 50000, "frame %d: GPU [%lld, %lld] ns vs submit "
              "window [0, %lld]", f, (long long)(e->gpuStartNs - before), (long long)(e->gpuEndNs - before), (long long)(after - before));
        CHECK(e->gpuBusyNs > 0 && e->gpuBusyNs <= (e->gpuEndNs - e->gpuStartNs) * (uint64_t)e->cbCompleted,
              "frame %d: busy %llu", f, (unsigned long long)e->gpuBusyNs);
        CHECK(res.cpuNanos > 0, "cpuNanos");
    }
    /* A flush-on-wait style commit (no FRAME_END) gets an entry without the FRAME_END flag. */
    r_clear(&s, 0.1f);
    const uint64_t flushValue = submit(&s, MCMTL_SUBMIT_FLAG_COMMIT, NULL);
    mcmtl_ft_entry *fe = entry(info, flushValue);
    CHECK(fe->value == flushValue && !(fe->flags & MCMTL_FT_FLAG_FRAME_END) && (fe->flags & MCMTL_FT_FLAG_SUBMITTED),
          "flush commit entry (tag %llu, flags 0x%x)", (unsigned long long)fe->value, fe->flags);
    /* An empty frame still commits a signal-only command buffer. */
    const uint64_t emptyValue = submit(&s, MCMTL_SUBMIT_FLAG_COMMIT | MCMTL_SUBMIT_FLAG_FRAME_END, NULL);
    mcmtl_ft_entry *ee = entry(info, emptyValue);
    CHECK(ee->value == emptyValue && ee->cbCommitted >= 1 && wait_handlers(ee, ee->cbCommitted), "empty frame entry");

    /* Wrap-around: 300 more values through a 256-entry ring; each index holds the newest value. */
    const uint64_t first = gNextValue;
    for (int i = 0; i < 300; i++) submit(&s, MCMTL_SUBMIT_FLAG_COMMIT | MCMTL_SUBMIT_FLAG_FRAME_END, NULL);
    const uint64_t last = gNextValue - 1;
    int wrongTags = 0;
    for (uint64_t v = last - 255; v <= last; v++) {
        if (entry(info, v)->value != v) wrongTags++;
    }
    CHECK(wrongTags == 0, "wrap-around: %d of the newest 256 values have a foreign tag", wrongTags);
    CHECK(entry(info, first)->value != first, "the oldest value must be overwritten after a wrap");
    free(s.data);
    printf("frames: ok (%llu values, ring %llu)\n", (unsigned long long)(gNextValue - 1), (unsigned long long)info->capacity);
}

/* Many concurrent completions of one value: the lock-free min / max / sum / count must be exact. */
static void test_concurrent_aggregation(const mcmtl_ft_info *info) {
    const uint64_t value = gNextValue + 1000;   /* never submitted: entry driven by the hooks directly */
    CHECK(mcmtl_ft_submit_begin(gDev, value, MCMTL_SUBMIT_FLAG_FRAME_END) == 1, "begin");
    const int n = 4000;
    const double base = 1000.0;   /* seconds; a realistic uptime magnitude */
    dispatch_apply((size_t)n, dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^(size_t i) {
        const double start = base + (double)i * 1e-6;          /* i us */
        const double end = start + 1e-6 * (double)(1 + i % 5);  /* 1..5 us long */
        mcmtl_ft_on_completed(gDev, value, start, end, (int)(i == 17), (int)(i == n - 1));
    });
    mcmtl_ft_entry *e = entry(info, value);
    CHECK(e->cbCompleted == (uint32_t)n, "completions %u != %d", e->cbCompleted, n);
    const uint64_t expectStart = (uint64_t)(base * 1e9);
    const uint64_t lastStart = (uint64_t)((base + (double)(n - 1) * 1e-6) * 1e9);
    CHECK(e->gpuStartNs >= expectStart && e->gpuStartNs <= expectStart + 1, "min start %llu vs %llu",
          (unsigned long long)e->gpuStartNs, (unsigned long long)expectStart);
    uint64_t maxEnd = 0, sum = 0;
    for (int i = 0; i < n; i++) {
        const double start = base + (double)i * 1e-6;
        const double end = start + 1e-6 * (double)(1 + i % 5);
        const uint64_t s = (uint64_t)(start * 1e9), t = (uint64_t)(end * 1e9);
        if (t > maxEnd) maxEnd = t;
        sum += t - s;
    }
    CHECK(e->gpuEndNs == maxEnd, "max end %llu vs %llu", (unsigned long long)e->gpuEndNs, (unsigned long long)maxEnd);
    CHECK(e->gpuBusyNs == sum, "busy sum %llu vs %llu", (unsigned long long)e->gpuBusyNs, (unsigned long long)sum);
    CHECK((e->flags & MCMTL_FT_FLAG_FAILED) && (e->flags & MCMTL_FT_FLAG_SIGNAL_DONE), "flags 0x%x", e->flags);
    CHECK(lastStart > expectStart, "sanity");
    /* A completion for a value whose entry holds another tag is ignored. */
    const uint32_t before = e->cbCompleted;
    mcmtl_ft_on_completed(gDev, value + info->capacity, base, base + 1.0, 0, 1);
    CHECK(e->cbCompleted == before && e->value == value, "foreign-value completion must be ignored");
    /* Another device is not traced. */
    MCMTLDevice *other = [MCMTLDevice new];
    CHECK(mcmtl_ft_submit_begin(other, value, 0) == 0, "another device must not be traced");
    mcmtl_ft_on_completed(other, value, base, base + 1.0, 0, 1);
    CHECK(e->cbCompleted == before, "another device's completion must be ignored");
    printf("aggregation: ok (%d concurrent completions)\n", n);
}

/* Real command buffers: two untimed (early commits) and the signalling one, all attributed to one value. */
static void test_untimed(const mcmtl_ft_info *info) {
    const uint64_t value = gNextValue++;
    CHECK(mcmtl_ft_submit_begin(gDev, value, MCMTL_SUBMIT_FLAG_FRAME_END) == 1, "begin");
    const uint64_t t0 = now_uptime();
    for (int i = 0; i < 3; i++) {
        @autoreleasepool {
            id<MTLCommandBuffer> cb = [gDev->queue commandBuffer];
            MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
            rp.colorAttachments[0].texture = gTarget;
            rp.colorAttachments[0].loadAction = MTLLoadActionClear;
            rp.colorAttachments[0].storeAction = MTLStoreActionStore;
            rp.colorAttachments[0].clearColor = MTLClearColorMake(0.1 * i, 0.2, 0.3, 1.0);
            [[cb renderCommandEncoderWithDescriptor:rp] endEncoding];
            if (i < 2) {
                mcmtl_commit_untimed(gDev, cb, value);
            } else {
                mcmtl_commit_signaled(gDev, cb, value);
            }
        }
    }
    mcmtl_submit_result res;
    memset(&res, 0, sizeof res);
    res.flags = MCMTL_SUBMIT_RESULT_FLAG_COMMITTED;
    mcmtl_ft_submit_end(gDev, value, &res);
    CHECK(mcmtl_wait_completed(gDev, value, 5000000000ull) == MCMTL_OK, "wait");
    mcmtl_ft_entry *e = entry(info, value);
    CHECK(wait_handlers(e, 3), "3 handlers, got %u", e->cbCompleted);
    const uint64_t t1 = now_uptime();
    CHECK(e->value == value, "tag");
    CHECK(e->gpuStartNs + 50000 >= t0 && e->gpuEndNs <= t1 + 50000 && e->gpuEndNs > e->gpuStartNs, "GPU interval in window");
    CHECK(e->gpuBusyNs <= e->gpuEndNs - e->gpuStartNs + 1000, "busy %llu within span %llu (serial queue)",
          (unsigned long long)e->gpuBusyNs, (unsigned long long)(e->gpuEndNs - e->gpuStartNs));
    CHECK((e->flags & MCMTL_FT_FLAG_SIGNAL_DONE) && (e->flags & MCMTL_FT_FLAG_SUBMITTED), "flags 0x%x", e->flags);
    printf("untimed: ok\n");
}

static void test_config(void) {
    char buf[8192];
    uint32_t len = 0;
    CHECK(mcmtl_frametrace_config(gDevHandle, buf, 4, &len) == MCMTL_E_INVALID_ARGUMENT && len == 3 && strlen(buf) == 3,
          "config must truncate and report it");
    int32_t rc = mcmtl_frametrace_config(gDevHandle, buf, sizeof buf, &len);
    CHECK(rc == MCMTL_OK && len == strlen(buf), "config rc %d %s", rc, mcmtl_last_error());
    NSError *error = nil;
    NSDictionary *json = [NSJSONSerialization JSONObjectWithData:[NSData dataWithBytes:buf length:len] options:0 error:&error];
    CHECK([json isKindOfClass:[NSDictionary class]], "config is not a JSON object: %s (%s)", buf,
          error.localizedDescription.UTF8String);
    for (NSString *key in @[ @"decoder", @"fuseMode", @"presentFusionEnv", @"m2Flags", @"icbMode", @"occlusionMode",
                             @"mailboxActive", @"profilePasses", @"traceRingEntries", @"counterNames" ]) {
        CHECK(json[key] != nil, "config lacks %s", key.UTF8String);
    }
    NSArray *names = json[@"counterNames"];
    CHECK([names isKindOfClass:[NSArray class]] && names.count == MCMTL_FT_C_COUNT, "counterNames: %lu",
          (unsigned long)names.count);
    CHECK([names[MCMTL_FT_C_FUSED_FRAMES] isEqual:@"fusedFrames"] && [names[MCMTL_FT_C_PRESENT_SKIP + 3] isEqual:@"presentSkip.deferred"],
          "counter name order");
    CHECK([json[@"traceRingEntries"] intValue] == 256, "traceRingEntries");
    uint64_t pair[2];
    mcmtl_frametrace_clock(pair);
    CHECK(pair[0] > 0 && llabs((long long)pair[0] - (long long)pair[1]) < 100000, "clock pair");
    printf("config: ok (%u bytes)\n", len);
}

int main(void) {
    setvbuf(stdout, NULL, _IOLBF, 0);
    @autoreleasepool {
        gMtl = MTLCreateSystemDefaultDevice();
        gDev = mcmtl_device_context_create(gMtl, @"frametrace_test");
        CHECK(gDev != nil, "device context: %s", mcmtl_last_error());
        if (!gDev) return 1;
        gDev->flags = MCMTL_DEVICE_FLAG_VALIDATION;
        CHECK(mcmtl_decoder_device_init(gDev), "decoder init: %s", mcmtl_last_error());
        gDevHandle = (mcmtl_handle)(uintptr_t)(__bridge void *)gDev;
        MTLTextureDescriptor *d = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm
                                                                                     width:2048 height:2048 mipmapped:NO];
        d.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
        d.storageMode = MTLStorageModePrivate;
        gTarget = [gMtl newTextureWithDescriptor:d];

        test_off();
        mcmtl_ft_info info;
        test_frames(&info);
        test_concurrent_aggregation(&info);
        test_untimed(&info);
        test_config();
        mcmtl_ft_test_disable();
        CHECK(!mcmtl_ft_on(), "disabled");
    }
    printf("frametrace_test: %d checks, %d failures\n", checks, failures);
    return failures ? 1 : 0;
}
