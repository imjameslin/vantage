/*
 * mcmetal_frametrace.m -- per-frame trace for the bench (CONTRACT.md 16.R5-B; design and thread-safety notes in
 * mcmetal_frametrace.h). Diagnostics only: nothing here runs unless Java enabled the ring or queried the config.
 */
#import "mcmetal_internal.h"
#import "mcmetal_frametrace.h"
#import <QuartzCore/QuartzCore.h>

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

typedef struct mcmtl_ft_state {
    const void     *dev;                      /* traced device (identity only, not retained)                  */
    mcmtl_ft_entry *ring;
    uint64_t        capacity;
    uint64_t        mask;
    uint64_t        before[MCMTL_FT_C_COUNT]; /* render thread: decoder counters when the current submit began */
    uint32_t        beforeCount;
    uint64_t        beforeValue;              /* value the snapshot belongs to                                 */
} mcmtl_ft_state;

void *mcmtl_ft_global;   /* mcmtl_ft_state *, published with release, read with acquire (relaxed for the on/off test) */

static const char *const kCounterNames[MCMTL_FT_C_COUNT] = {
    "frames", "commandBuffersCommitted", "untimedCommits", "renderEncoders", "blitEncoders", "draws", "submitNs",
    "drawableWaitNs", "fusionWaitNs", "fusedFrames", "fusionRestores", "fusionBackoffs", "presentRecords",
    "presentsEncoded", "presentsNil", "mailboxRecords", "presentsSkipped", "hoists", "merges", "icbGroups",
    "indirectRecordDraws", "occlFrames", "occlCulledSections",
    "fuseFallback.not-flipped", "fuseFallback.flip-only", "fuseFallback.no-index", "fuseFallback.multi-present",
    "fuseFallback.format", "fuseFallback.size", "fuseFallback.no-clear", "fuseFallback.source-use",
    "fuseFallback.variants", "fuseFallback.backoff", "fuseFallback.drawable-nil", "fuseFallback.not-reached",
    "fuseFallback.no-drawable",
    "presentSkip.no-drawable", "presentSkip.queue-full", "presentSkip.stale", "presentSkip.deferred",
};

static inline mcmtl_ft_state *ft_state_for(MCMTLDevice *dev) {
    mcmtl_ft_state *ft = __atomic_load_n((mcmtl_ft_state **)&mcmtl_ft_global, __ATOMIC_ACQUIRE);
    return ft && ft->dev == (__bridge const void *)dev ? ft : NULL;
}

static inline uint64_t ft_media_ns(double seconds) {
    return seconds > 0 ? (uint64_t)(seconds * 1e9) : 0;
}

/* ---------------------------------------------------------------------------------------------- */
/* Hooks                                                                                            */
/* ---------------------------------------------------------------------------------------------- */

int mcmtl_ft_submit_begin(MCMTLDevice *dev, uint64_t value, uint32_t submitFlags) {
    mcmtl_ft_state *ft = ft_state_for(dev);
    if (!ft || value == 0) return 0;
    mcmtl_ft_entry *e = &ft->ring[value & ft->mask];
    /* Invalidate first, so a (practically impossible) handler of value - capacity sees a foreign tag. */
    __atomic_store_n(&e->value, 0, __ATOMIC_RELEASE);
    __atomic_store_n(&e->commitNs, 0, __ATOMIC_RELAXED);
    __atomic_store_n(&e->gpuStartNs, UINT64_MAX, __ATOMIC_RELAXED);
    __atomic_store_n(&e->gpuEndNs, 0, __ATOMIC_RELAXED);
    __atomic_store_n(&e->gpuBusyNs, 0, __ATOMIC_RELAXED);
    __atomic_store_n(&e->drawableWaitNs, 0, __ATOMIC_RELAXED);
    __atomic_store_n(&e->cbCommitted, 0, __ATOMIC_RELAXED);
    __atomic_store_n(&e->cbCompleted, 0, __ATOMIC_RELAXED);
    __atomic_store_n(&e->flags, (submitFlags & MCMTL_SUBMIT_FLAG_FRAME_END) ? MCMTL_FT_FLAG_FRAME_END : 0u, __ATOMIC_RELAXED);
    e->encoders = 0;
    e->fuseFallback = 0;
    e->presentSkip = 0;
    /* Publish: every command buffer attributed to `value` is committed after this store (the decoder runs next), and
     * Metal invokes its completion handler after the commit, so handlers always see the reset entry. */
    __atomic_store_n(&e->value, value, __ATOMIC_RELEASE);
    ft->beforeCount = mcmtl_decoder_ft_counters(dev, ft->before, MCMTL_FT_C_COUNT);
    ft->beforeValue = value;
    return 1;
}

void mcmtl_ft_submit_end(MCMTLDevice *dev, uint64_t value, const mcmtl_submit_result *result) {
    mcmtl_ft_state *ft = ft_state_for(dev);
    if (!ft || value == 0 || ft->beforeValue != value) return;
    mcmtl_ft_entry *e = &ft->ring[value & ft->mask];
    if (__atomic_load_n(&e->value, __ATOMIC_ACQUIRE) != value) return;
    uint64_t after[MCMTL_FT_C_COUNT];
    const uint32_t n = mcmtl_decoder_ft_counters(dev, after, MCMTL_FT_C_COUNT);
    uint64_t d[MCMTL_FT_C_COUNT];
    for (uint32_t i = 0; i < MCMTL_FT_C_COUNT; i++) {
        d[i] = (i < n && i < ft->beforeCount && after[i] >= ft->before[i]) ? after[i] - ft->before[i] : 0;
    }
    uint32_t flags = MCMTL_FT_FLAG_SUBMITTED;
    if (!result || !(result->flags & MCMTL_SUBMIT_RESULT_FLAG_COMMITTED)) flags |= MCMTL_FT_FLAG_NOT_COMMITTED;
    if (d[MCMTL_FT_C_PRESENT_RECORDS]) flags |= MCMTL_FT_FLAG_PRESENT_RECORD;
    if (d[MCMTL_FT_C_PRESENTS_ENCODED]) flags |= MCMTL_FT_FLAG_PRESENTED;
    if (d[MCMTL_FT_C_FUSED_FRAMES]) flags |= MCMTL_FT_FLAG_FUSED;
    if (d[MCMTL_FT_C_PRESENTS_SKIPPED]) flags |= MCMTL_FT_FLAG_SKIPPED;
    if (d[MCMTL_FT_C_PRESENTS_NIL]) flags |= MCMTL_FT_FLAG_NIL;
    if (d[MCMTL_FT_C_MAILBOX_RECORDS]) flags |= MCMTL_FT_FLAG_MAILBOX;
    if (d[MCMTL_FT_C_UNTIMED_COMMITS]) flags |= MCMTL_FT_FLAG_UNTIMED;
    uint8_t fallback = 0, skip = 0;
    for (uint32_t r = 0; r < MCMTL_FT_FUSE_REASONS && !fallback; r++) {
        if (d[MCMTL_FT_C_FUSE_FALLBACK + r]) fallback = (uint8_t)(r + 1);
    }
    for (uint32_t r = 0; r < MCMTL_FT_SKIP_REASONS && !skip; r++) {
        if (d[MCMTL_FT_C_PRESENT_SKIP + r]) skip = (uint8_t)(r + 1);
    }
    const uint64_t encoders = d[MCMTL_FT_C_RENDER_ENCODERS] + d[MCMTL_FT_C_BLIT_ENCODERS];
    /* Render-thread-only fields: handlers never write them. */
    e->drawableWaitNs = d[MCMTL_FT_C_DRAWABLE_WAIT_NS];
    e->cbCommitted = (uint32_t)MIN(d[MCMTL_FT_C_CB_COMMITTED], (uint64_t)UINT32_MAX);
    e->encoders = (uint16_t)MIN(encoders, (uint64_t)UINT16_MAX);
    e->fuseFallback = fallback;
    e->presentSkip = skip;
    e->commitNs = clock_gettime_nsec_np(CLOCK_UPTIME_RAW);
    /* Shared with the handlers: atomic or. Release: a reader that sees SUBMITTED sees the fields above. */
    __atomic_fetch_or(&e->flags, flags, __ATOMIC_RELEASE);
}

static inline void ft_atomic_min(uint64_t *p, uint64_t v) {
    uint64_t cur = __atomic_load_n(p, __ATOMIC_RELAXED);
    while (v < cur && !__atomic_compare_exchange_n(p, &cur, v, true, __ATOMIC_RELAXED, __ATOMIC_RELAXED)) {
    }
}

static inline void ft_atomic_max(uint64_t *p, uint64_t v) {
    uint64_t cur = __atomic_load_n(p, __ATOMIC_RELAXED);
    while (v > cur && !__atomic_compare_exchange_n(p, &cur, v, true, __ATOMIC_RELAXED, __ATOMIC_RELAXED)) {
    }
}

void mcmtl_ft_on_completed(MCMTLDevice *dev, uint64_t value, double gpuStart, double gpuEnd, int failed, int timed) {
    mcmtl_ft_state *ft = ft_state_for(dev);
    if (!ft || value == 0) return;
    mcmtl_ft_entry *e = &ft->ring[value & ft->mask];
    if (__atomic_load_n(&e->value, __ATOMIC_ACQUIRE) != value) return;
    if (gpuEnd > gpuStart && gpuStart > 0) {
        const uint64_t s = ft_media_ns(gpuStart), t = ft_media_ns(gpuEnd);
        ft_atomic_min(&e->gpuStartNs, s);
        ft_atomic_max(&e->gpuEndNs, t);
        __atomic_fetch_add(&e->gpuBusyNs, t - s, __ATOMIC_RELAXED);
    }
    const uint32_t flags = (timed ? MCMTL_FT_FLAG_SIGNAL_DONE : 0u) | (failed ? MCMTL_FT_FLAG_FAILED : 0u);
    if (flags) __atomic_fetch_or(&e->flags, flags, __ATOMIC_RELAXED);
    /* Last, with release: a reader that sees cbCompleted == cbCommitted sees every handler's GPU times. */
    __atomic_fetch_add(&e->cbCompleted, 1u, __ATOMIC_RELEASE);
}

void mcmtl_ft_test_disable(void) {
    __atomic_store_n((mcmtl_ft_state **)&mcmtl_ft_global, NULL, __ATOMIC_RELEASE);
}

/* ---------------------------------------------------------------------------------------------- */
/* Exports                                                                                          */
/* ---------------------------------------------------------------------------------------------- */

static void ft_fill_info(const mcmtl_ft_state *ft, mcmtl_ft_info *out) {
    out->entrySize = (uint32_t)sizeof(mcmtl_ft_entry);
    out->ring = (uint64_t)(uintptr_t)ft->ring;
    out->capacity = ft->capacity;
    out->uptimeNs = clock_gettime_nsec_np(CLOCK_UPTIME_RAW);
    out->mediaNs = ft_media_ns(CACurrentMediaTime());
    out->reserved = 0;
}

MCMTL_API int32_t mcmtl_frametrace_enable(mcmtl_handle device, uint32_t capacityLog2, mcmtl_ft_info *outInfo) {
    MCMTL_TRY_BEGIN
        mcmtl_clear_error();
        if (!device || !outInfo) {
            mcmtl_set_error("mcmtl_frametrace_enable: %s is NULL", device ? "outInfo" : "device");
            return MCMTL_E_INVALID_ARGUMENT;
        }
        if (outInfo->structSize != sizeof(mcmtl_ft_info)) {
            mcmtl_set_error("mcmtl_frametrace_enable: outInfo->structSize %u != %zu", outInfo->structSize, sizeof(mcmtl_ft_info));
            return MCMTL_E_ABI_MISMATCH;
        }
        MCMTLDevice *dev = MCMTL_DEVICE(device);
        mcmtl_ft_state *ft = ft_state_for(dev);
        if (!ft) {
            const uint32_t log2 = capacityLog2 < 8 ? 8 : (capacityLog2 > 24 ? 24 : capacityLog2);
            ft = calloc(1, sizeof *ft);
            void *ring = NULL;
            const size_t bytes = ((size_t)1 << log2) * sizeof(mcmtl_ft_entry);
            if (!ft || posix_memalign(&ring, 64, bytes) != 0) {
                free(ft);
                mcmtl_set_error("mcmtl_frametrace_enable: cannot allocate %zu bytes", bytes);
                return MCMTL_E_OUT_OF_MEMORY;
            }
            memset(ring, 0, bytes);
            ft->dev = (__bridge const void *)dev;
            ft->ring = ring;
            ft->capacity = (uint64_t)1 << log2;
            ft->mask = ft->capacity - 1;
            /* A previous state (other device) stays allocated: its handlers may still dereference it. */
            __atomic_store_n((mcmtl_ft_state **)&mcmtl_ft_global, ft, __ATOMIC_RELEASE);
        }
        ft_fill_info(ft, outInfo);
        return MCMTL_OK;
    MCMTL_TRY_END(MCMTL_E_OBJC_EXCEPTION)
}

MCMTL_API void mcmtl_frametrace_clock(uint64_t *outPair) {
    if (!outPair) return;
    outPair[0] = clock_gettime_nsec_np(CLOCK_UPTIME_RAW);
    outPair[1] = ft_media_ns(CACurrentMediaTime());
}

MCMTL_API uint32_t mcmtl_frametrace_counters(mcmtl_handle device, uint64_t *out, uint32_t capacity) {
    MCMTL_TRY_BEGIN
        if (!device || !out || capacity == 0) return 0;
        return mcmtl_decoder_ft_counters(MCMTL_DEVICE(device), out, capacity);
    MCMTL_TRY_END(0)
}

/* Appends printf output to buf[*len, cap) keeping a NUL; tracks the would-be length past truncation. */
static void ft_append(char *buf, uint32_t cap, uint64_t *len, const char *format, ...) __attribute__((format(printf, 4, 5)));
static void ft_append(char *buf, uint32_t cap, uint64_t *len, const char *format, ...) {
    va_list args;
    va_start(args, format);
    const uint64_t room = *len < cap ? cap - *len : 0;
    const int n = vsnprintf(room ? buf + *len : NULL, (size_t)room, format, args);
    va_end(args);
    if (n > 0) *len += (uint64_t)n;
}

MCMTL_API int32_t mcmtl_frametrace_config(mcmtl_handle device, char *buffer, uint32_t capacity, uint32_t *outLength) {
    MCMTL_TRY_BEGIN
        mcmtl_clear_error();
        if (!device || !buffer || capacity < 2) {
            mcmtl_set_error("mcmtl_frametrace_config: NULL device/buffer or capacity < 2");
            return MCMTL_E_INVALID_ARGUMENT;
        }
        MCMTLDevice *dev = MCMTL_DEVICE(device);
        uint64_t len = 0;
        buffer[0] = '\0';
        ft_append(buffer, capacity, &len, "{");
        char decoder[4096];
        const uint32_t dlen = mcmtl_decoder_ft_config(dev, decoder, sizeof decoder);
        if (dlen > 0 && dlen < sizeof decoder) ft_append(buffer, capacity, &len, "%s, ", decoder);
        const mcmtl_ft_state *ft = ft_state_for(dev);
        ft_append(buffer, capacity, &len, "\"traceRingEntries\": %llu, \"counterNames\": [",
                  (unsigned long long)(ft ? ft->capacity : 0));
        for (uint32_t i = 0; i < MCMTL_FT_C_COUNT; i++) {
            ft_append(buffer, capacity, &len, "%s\"%s\"", i ? ", " : "", kCounterNames[i]);
        }
        ft_append(buffer, capacity, &len, "]}");
        const uint64_t written = len < capacity ? len : capacity - 1;
        buffer[written] = '\0';
        if (outLength) *outLength = (uint32_t)written;
        if (len >= capacity) {
            mcmtl_set_error("mcmtl_frametrace_config: truncated (%llu bytes needed)", (unsigned long long)len + 1);
            return MCMTL_E_INVALID_ARGUMENT;
        }
        return MCMTL_OK;
    MCMTL_TRY_END(MCMTL_E_OBJC_EXCEPTION)
}
