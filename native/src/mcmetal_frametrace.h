/*
 * mcmetal_frametrace.h -- per-frame trace for the bench (docs/metal/CONTRACT.md 16.R5-B).
 *
 * NOT part of the frozen ABI: mcmetal.h and MCMTL_ABI_VERSION are unchanged. The four exports below are optional
 * diagnostics. Java (MetalFrameTrace) looks them up by name only when asked to (-Dmcmetal.frameTrace=true, or the
 * bench's one-shot config/counters queries) and treats a missing symbol as "no native trace".
 *
 * Mechanism. Off until mcmtl_frametrace_enable: mcmtl_ft_global stays NULL and the two hook sites in
 * mcmetal_common.m (mcmtl_submit, the shared completion-handler body) cost one relaxed pointer load + branch.
 * Enabled, the device gets a ring of mcmtl_ft_entry (one cache line each) indexed by timeline value & (capacity - 1):
 *  - mcmtl_submit (render thread) resets the value's entry BEFORE decoding (so before any command buffer attributed to
 *    the value is committed), then fills commitNs and the per-submit decoder-counter deltas (drawable wait, presented /
 *    fused / skipped, encoders, command buffers committed) after the decode returned.
 *  - Every completion handler of a command buffer attributed to the value (the signalling one and the untimed early
 *    commits) folds GPUStartTime / GPUEndTime in lock-free: atomic min / max / add on the entry, then cbCompleted++.
 *    Handlers run on Metal's completion threads, possibly concurrently for one value: every field they touch is only
 *    ever updated with __atomic read-modify-writes; the render thread never writes those fields after the reset.
 *  - Readers (Java, end of a bench run) read the entries in place; an entry is identified by its `value` tag (a
 *    mismatching tag = overwritten by value + k * capacity, or never written).
 * The only race left is a completion handler delayed by more than `capacity` frames (a reset of its entry for a newer
 * value while it updates): impossible with two frames in flight and capacity >= 256; at worst one diagnostic entry
 * would be wrong. The ring is never freed while the process lives (handlers may still read it after a re-enable).
 *
 * Time base: GPU times are CFTimeInterval seconds of the host clock (mach_absolute_time, same as CACurrentMediaTime),
 * stored as nanoseconds; CLOCK_UPTIME_RAW and HotSpot's System.nanoTime() use the same clock on macOS.
 * mcmtl_frametrace_clock returns a (CLOCK_UPTIME_RAW, CACurrentMediaTime) pair so Java can verify and convert.
 */
#ifndef MCMETAL_FRAMETRACE_H
#define MCMETAL_FRAMETRACE_H

#include "mcmetal.h"

#ifdef __cplusplus
extern "C" {
#endif

/* mcmtl_ft_entry.flags */
#define MCMTL_FT_FLAG_FRAME_END       (1u << 0)  /* the submit had MCMTL_SUBMIT_FLAG_FRAME_END                     */
#define MCMTL_FT_FLAG_SUBMITTED       (1u << 1)  /* mcmtl_submit returned (render-thread fields are final)          */
#define MCMTL_FT_FLAG_NOT_COMMITTED   (1u << 2)  /* no real GPU commit (decode error path / CPU-completed value)    */
#define MCMTL_FT_FLAG_PRESENT_RECORD  (1u << 3)  /* a PRESENT record was decoded                                    */
#define MCMTL_FT_FLAG_PRESENTED       (1u << 4)  /* ... and presented (fused or present pass)                       */
#define MCMTL_FT_FLAG_FUSED           (1u << 5)  /* ... rendered straight into the drawable (CONTRACT.md 16.3)      */
#define MCMTL_FT_FLAG_SKIPPED         (1u << 6)  /* ... mailbox: rendered, not presented (CONTRACT.md 16.4)         */
#define MCMTL_FT_FLAG_NIL             (1u << 7)  /* ... blocking nextDrawable returned nil                          */
#define MCMTL_FT_FLAG_MAILBOX         (1u << 8)  /* ... surface in mailbox mode                                     */
#define MCMTL_FT_FLAG_UNTIMED         (1u << 9)  /* >= 1 untimed (early) command buffer committed for this value    */
#define MCMTL_FT_FLAG_SIGNAL_DONE     (1u << 16) /* completion handler of the signalling command buffer ran         */
#define MCMTL_FT_FLAG_FAILED          (1u << 17) /* a command buffer of this value failed                           */

/* One timeline value. 64 bytes, 64-byte aligned (no false sharing between neighbouring values). */
typedef struct mcmtl_ft_entry {
    uint64_t value;          /*  0 timeline value described (0 = never written); set last by the reset (release)    */
    uint64_t commitNs;       /*  8 CLOCK_UPTIME_RAW ns when mcmtl_submit was about to return (render thread)         */
    uint64_t gpuStartNs;     /* 16 min GPUStartTime over completed command buffers, ns; UINT64_MAX = none (atomic) */
    uint64_t gpuEndNs;       /* 24 max GPUEndTime, ns; 0 = none (atomic)                                          */
    uint64_t gpuBusyNs;      /* 32 sum of (GPUEndTime - GPUStartTime) (atomic)                                     */
    uint64_t drawableWaitNs; /* 40 nextDrawable wait inside this submit (decoder drawableWaitNs delta)            */
    uint32_t cbCommitted;    /* 48 command buffers committed by this submit (decoder delta, timed + untimed)      */
    uint32_t cbCompleted;    /* 52 completion handlers run for this value (atomic)                                */
    uint32_t flags;          /* 56 MCMTL_FT_FLAG_* (atomic or)                                                    */
    uint16_t encoders;       /* 60 render + blit encoders created by this submit (saturating)                     */
    uint8_t  fuseFallback;   /* 62 1 + DEC_FUSE_R_* reason of a PRESENT that was not fused, 0 = none              */
    uint8_t  presentSkip;    /* 63 1 + DEC_SKIP_* reason of a mailbox frame that was not presented, 0 = none       */
} mcmtl_ft_entry;
MCMTL_ASSERT_SIZE(mcmtl_ft_entry, 64);
MCMTL_ASSERT_OFFSET(mcmtl_ft_entry, value, 0);
MCMTL_ASSERT_OFFSET(mcmtl_ft_entry, commitNs, 8);
MCMTL_ASSERT_OFFSET(mcmtl_ft_entry, gpuStartNs, 16);
MCMTL_ASSERT_OFFSET(mcmtl_ft_entry, gpuEndNs, 24);
MCMTL_ASSERT_OFFSET(mcmtl_ft_entry, gpuBusyNs, 32);
MCMTL_ASSERT_OFFSET(mcmtl_ft_entry, drawableWaitNs, 40);
MCMTL_ASSERT_OFFSET(mcmtl_ft_entry, cbCommitted, 48);
MCMTL_ASSERT_OFFSET(mcmtl_ft_entry, cbCompleted, 52);
MCMTL_ASSERT_OFFSET(mcmtl_ft_entry, flags, 56);
MCMTL_ASSERT_OFFSET(mcmtl_ft_entry, encoders, 60);
MCMTL_ASSERT_OFFSET(mcmtl_ft_entry, fuseFallback, 62);
MCMTL_ASSERT_OFFSET(mcmtl_ft_entry, presentSkip, 63);

typedef struct mcmtl_ft_info {
    uint32_t structSize;     /*  0 caller sets sizeof(mcmtl_ft_info)                                                  */
    uint32_t entrySize;      /*  4 sizeof(mcmtl_ft_entry)                                                             */
    uint64_t ring;           /*  8 address of mcmtl_ft_entry[capacity] (valid until process exit)                     */
    uint64_t capacity;       /* 16 entries (power of two)                                                             */
    uint64_t uptimeNs;       /* 24 CLOCK_UPTIME_RAW ns ...                                                            */
    uint64_t mediaNs;        /* 32 ... and CACurrentMediaTime() ns, sampled back to back at return                     */
    uint64_t reserved;       /* 40 */
} mcmtl_ft_info;             /* 48 */
MCMTL_ASSERT_SIZE(mcmtl_ft_info, 48);
MCMTL_ASSERT_OFFSET(mcmtl_ft_info, ring, 8);
MCMTL_ASSERT_OFFSET(mcmtl_ft_info, capacity, 16);
MCMTL_ASSERT_OFFSET(mcmtl_ft_info, uptimeNs, 24);
MCMTL_ASSERT_OFFSET(mcmtl_ft_info, mediaNs, 32);

/* Cumulative decoder counters returned by mcmtl_frametrace_counters (order fixed; names in the config JSON). */
enum {
    MCMTL_FT_C_FRAMES = 0, MCMTL_FT_C_CB_COMMITTED, MCMTL_FT_C_UNTIMED_COMMITS, MCMTL_FT_C_RENDER_ENCODERS,
    MCMTL_FT_C_BLIT_ENCODERS, MCMTL_FT_C_DRAWS, MCMTL_FT_C_SUBMIT_NS, MCMTL_FT_C_DRAWABLE_WAIT_NS, MCMTL_FT_C_FUSION_WAIT_NS,
    MCMTL_FT_C_FUSED_FRAMES, MCMTL_FT_C_FUSION_RESTORES, MCMTL_FT_C_FUSION_BACKOFFS, MCMTL_FT_C_PRESENT_RECORDS,
    MCMTL_FT_C_PRESENTS_ENCODED, MCMTL_FT_C_PRESENTS_NIL, MCMTL_FT_C_MAILBOX_RECORDS, MCMTL_FT_C_PRESENTS_SKIPPED,
    MCMTL_FT_C_HOISTS, MCMTL_FT_C_MERGES, MCMTL_FT_C_ICB_GROUPS, MCMTL_FT_C_INDIRECT_RECORD_DRAWS, MCMTL_FT_C_OCCL_FRAMES,
    MCMTL_FT_C_OCCL_CULLED_SECTIONS,
    MCMTL_FT_C_FUSE_FALLBACK,                                  /* + 13 reasons (DEC_FUSE_R_*)                 */
    MCMTL_FT_C_PRESENT_SKIP = MCMTL_FT_C_FUSE_FALLBACK + 13,   /* + 4 reasons (DEC_SKIP_*)                    */
    MCMTL_FT_C_COUNT = MCMTL_FT_C_PRESENT_SKIP + 4
};
#define MCMTL_FT_FUSE_REASONS 13u
#define MCMTL_FT_SKIP_REASONS 4u

/* Allocates (first call per device) and installs the ring; capacityLog2 is clamped to [8, 24]. Idempotent for the
 * same device (returns the existing ring); a different device replaces the traced device (the old ring is leaked on
 * purpose). Render thread. */
MCMTL_API int32_t mcmtl_frametrace_enable(mcmtl_handle device, uint32_t capacityLog2, mcmtl_ft_info *outInfo);
/* Trivial: outPair[0] = CLOCK_UPTIME_RAW ns, outPair[1] = CACurrentMediaTime() ns. Any thread. */
MCMTL_API void mcmtl_frametrace_clock(uint64_t *outPair);
/* Copies min(capacity, MCMTL_FT_C_COUNT) cumulative decoder counters into out; returns the number copied (0 without a
 * decoder). Render thread (the decoder writes them there). Works whether or not the ring is enabled. */
MCMTL_API uint32_t mcmtl_frametrace_counters(mcmtl_handle device, uint64_t *out, uint32_t capacity);
/* Writes a JSON object with the EFFECTIVE native configuration (decoder state, surface mailbox state, trace ring, counter
 * names), NUL-terminated and truncated to capacity; *outLength = bytes excluding the NUL. Render thread. */
MCMTL_API int32_t mcmtl_frametrace_config(mcmtl_handle device, char *buffer, uint32_t capacity, uint32_t *outLength);

#ifdef __OBJC__
@class MCMTLDevice;
/* Non-NULL once a ring is installed (mcmetal_frametrace.m). Hooks check it before calling in. */
extern void *mcmtl_ft_global;
static inline int mcmtl_ft_on(void) {
    return __builtin_expect(__atomic_load_n(&mcmtl_ft_global, __ATOMIC_RELAXED) != NULL, 0);
}
/* mcmtl_submit hooks (common.m, render thread): begin before the decoder runs, end before the export returns.
 * begin returns 1 if the value is traced (end must then be called). */
int mcmtl_ft_submit_begin(MCMTLDevice *dev, uint64_t value, uint32_t submitFlags);
void mcmtl_ft_submit_end(MCMTLDevice *dev, uint64_t value, const mcmtl_submit_result *result);
/* Completion-handler hook (common.m, Metal completion threads). */
void mcmtl_ft_on_completed(MCMTLDevice *dev, uint64_t value, double gpuStart, double gpuEnd, int failed, int timed);
/* Decoder side (mcmetal_decoder.m, render thread). */
uint32_t mcmtl_decoder_ft_counters(MCMTLDevice *dev, uint64_t *out, uint32_t capacity);
/* Appends `"key": value, ...` pairs (no braces, no trailing comma) describing the effective decoder configuration. */
uint32_t mcmtl_decoder_ft_config(MCMTLDevice *dev, char *buffer, uint32_t capacity);
/* Tests only (hidden): drop the installed ring (it is not freed) so later submits are untraced. */
void mcmtl_ft_test_disable(void);
#endif

#ifdef __cplusplus
}
#endif

#endif /* MCMETAL_FRAMETRACE_H */
