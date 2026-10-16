/* Optional frame consumer extension; ABI 2 and the stream decoder are unchanged. */
#pragma once
#include "mcmetal.h"
#include "mcmetal_farmaterial.h"
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

enum {
    MCMTL_FAR_CONSUMER_DECLINED = 1,
    MCMTL_FAR_CONSUMER_RECEIPT_VALID = 1,
    MCMTL_FAR_CONSUMER_POISONED = 2,
    MCMTL_FAR_CONSUMER_DEFAULT_BUDGET = 512 * 1024 * 1024,
    MCMTL_FAR_CONSUMER_MAX_DRAWS = 4096
};

/* 16 bytes PER DRAW, in the original current-frame draw order. Each points to
 * one 20-byte MTLDrawIndexedPrimitivesIndirectArguments record, produced by
 * the MAIN queue's current cull. offset must be divisible by four. Borrowed
 * buffers stay alive through encode; Metal then retains them until completion.
 * Never supply a previous frame's outputs or any consumer-owned buffer.
 */
typedef struct { mcmtl_handle buffer; uint64_t offset; } mcmtl_far_consumer_fallback;

/* Additive, EXACTLY 352 bytes; old encode API stays unchanged. flags must be 1:
 * caller attests the same exact camera/geometry/visibility inputs as the newest
 * leased image. Native ALSO requires the exact canonical centered integer crop
 * in BOTH transform matrices, with even-pixel offsets preserving the original
 * 2x2 fragment-helper phase (dimension differences divisible by four). This
 * does not admit rotation or translation.
 * committedSequence must be nonzero and <= the MAIN device submittedValue.
 * ALL current-material writes and cull writes must precede this committed main
 * submission, with no intervening material writes before this sidecar completes.
 * No event/CPU wait is inserted. A pending, uncommitted stream does not qualify.
 * uniforms are Projection64, Globals48, Fog48, Terrain80, exact CURRENT bytes.
 * current are atlas/lightmap sources (48 bytes each). Mip/view/sampler/clamp
 * metadata must match the source image; RGB may change. GPU checks every alpha
 * texel/mip of both parents and falls back on any difference.
 */
typedef struct {
    uint32_t structSize, flags;             /* 0,4: 352,1 */
    uint64_t committedSequence;             /* 8 */
    uint8_t uniforms[240];                  /* 16 */
    mcmtl_far_material_source current[2];   /* 256,304 */
} mcmtl_far_consumer_shading;

/* EXACTLY 64 bytes. Initialize structSize=64 before calling outputs.
 * Each SUCCESSFUL export grants exactly one +1 for EACH of the six handles.
 * Drop each +1 exactly once using mcmtl_ext_far_engine_drop (or mcmtl_release).
 * The consumer independently retains these same objects. Failure grants none.
 * Solid and cutout stay separate. Read only after successful encode, in a
 * subsequently committed main-queue frame stream. Never read after a decline.
 */
typedef struct {
    uint32_t structSize, width, height, maxDraws;
    mcmtl_handle color[2];        /* offset 16: RGBA8Unorm */
    mcmtl_handle depth[2];        /* offset 32: R32Float, shader-readable depth */
    mcmtl_handle fallbackArgs;    /* offset 48: maxDraws * 20 bytes */
    mcmtl_handle compositeArgs;   /* offset 56: 16-byte nonindexed draw args */
} mcmtl_far_consumer_outputs;

/* EXACTLY 80 bytes. Initialize structSize=80 before calling stats.
 * CPU snapshot of the newest successfully COMPLETED encode, never GPU reads
 * or waits on the querying thread. Until first completion VALID is clear.
 * encodedSequence is the caller's strictly increasing, nonzero receiptSequence.
 * sourceFrame/sourceAgeNs are newest,older; absent older entries are zero.
 * Age is measured at encode in CLOCK_UPTIME_RAW nanoseconds (mach_absolute_time
 * domain), clamped to zero for a future source timestamp. holePixels counts
 * viewport pixels still untrusted in either layer after repair. syncFallback
 * is 1 iff holes or (for the shaded API) changed material alpha require ALL
 * current draws. On GPU/commit failure POISONED is set and VALID cleared permanently.
 */
typedef struct {
    uint32_t structSize, flags;   /* offsets 0,4 */
    uint64_t encodedSequence;    /* 8 */
    uint64_t sourceFrame[2];     /* 16 */
    uint64_t sourceAgeNs[2];     /* 32 */
    uint64_t holePixels;         /* 48 */
    uint64_t reserved64;         /* 56: shaded API alphaRejected (0/1); old API zero */
    uint32_t syncFallback, imageCount, drawCount, reserved; /* 64,68,72,76 */
} mcmtl_far_consumer_stats;

/* Additive independent-layer output ABI. Same 64-byte VALUE layout and six +1
 * handles as outputs, but uses separate argument buffers. compositeArgs is 32
 * bytes: SOLID uint32[4] at offset 0, CUTOUT at 16. fallbackArgs preserves the
 * caller's original draw order; layerCodes chooses each record's policy.
 * Only encode_shaded_layers authorizes these outputs. Old encode paths never
 * authorize them, and encode_shaded_layers never authorizes old output args.
 */
typedef mcmtl_far_consumer_outputs mcmtl_far_consumer_layer_outputs;

/* EXACTLY 96 bytes; a separate completed-receipt API, not an interpretation of
 * the old 80-byte receipt. Array order SOLID,CUTOUT. Only layer encodes update
 * this receipt; only old encode/encode_shaded update the old receipt. Shared
 * poisoning remains permanent for BOTH APIs. One monotonic sequence domain is
 * shared by every encode entry point on this consumer.
 */
typedef struct {
    uint32_t structSize, flags;    /* 0,4: size 96; same VALID/POISONED flag bits */
    uint64_t encodedSequence;      /* 8 */
    uint64_t sourceFrame;          /* 16: newest image only */
    uint64_t sourceAgeNs;          /* 24 */
    uint64_t holePixels[2];        /* 32,40: zero tolerance in each layer */
    uint32_t syncFallback[2];      /* 48,52 */
    uint32_t alphaRejected[2];     /* 56,60 */
    uint32_t drawCount[2];         /* 64,68: opaque BLOCK + compact; excludes translucent */
    uint32_t reserved[6];          /* 72..95: zero */
} mcmtl_far_consumer_layer_stats;

/* WORKER ONLY. device is the existing MCMTLDevice; retain it for this call.
 * Creates +1 consumer, six fixed target sets plus a hole mask (79 bytes/pixel),
 * warp/resolve/alpha pipelines, fixed indirect buffers and bounded receipt storage.
 * Three 4-byte alpha results add 12 logical buffer bytes; sets 2/3 additionally
 * permit render-target writes for shading, without increasing logical texel bytes.
 * targetBudgetBytes must be explicit and nonzero; caller's default should be
 * MCMTL_FAR_CONSUMER_DEFAULT_BUDGET or lower. Includes all owned GPU buffers
 * and logical texture bytes; driver padding and pipeline allocations excluded.
 * Width/height 1..16384, maxDraws 1..4096. No per-frame resource allocation.
 * The consumer retains the existing MAIN device queue; it never creates a queue.
 */
MCMTL_API int32_t mcmtl_ext_far_consumer_create(mcmtl_handle device,
    uint32_t width, uint32_t height, uint32_t maxDraws, uint64_t targetBudgetBytes,
    mcmtl_handle *outConsumer, char *error, uint32_t capacity);
MCMTL_API int32_t mcmtl_ext_far_consumer_outputs(mcmtl_handle consumer,
    mcmtl_far_consumer_outputs *outOutputs, char *error, uint32_t capacity);

/* MAIN THREAD ONLY. transformsPointer points to imageCount contiguous
 * MCMTLFarWarpTransform records (144 bytes each), newest then older; capacity
 * for two is recommended. imageCount is 1 or 2, caller-selected only after scene
 * and material eligibility. Source metadata is checked via far_engine_image.
 * Selecting 2 explicitly attests that BOTH images match CURRENT geometry,
 * material revisions/content, shaders, fog/settings and freshness. Native checks
 * epoch/geometryGeneration; materialGeneration is a capture ticket identity,
 * NOT a semantic content key, and may differ for equivalent immutable copies.
 * If only older is ineligible, select 1; if newest is ineligible, do not encode.
 * Only explicit PureRotation with exactly zero translation is supported;
 * translation is DECLINED even if its displacement happens to be zero.
 * fallbackDescPointer points to drawCount 16-byte records; NULL iff count=0.
 * 0 <= drawCount <= maxDraws. All pointers need remain stable only during call.
 *
 * Caller commits current cull output writes FIRST, then calls this entry point,
 * then commits the original main frame stream reading exported outputs. Any
 * earlier frame commands reading these outputs must already be committed.
 * This queues a retained-reference sideCB on device->queue, like cluster cull.
 * All validation/encoding precedes commit. Lease attachment precedes ALL source
 * reads. Only completed producer images are consumed: no producer queue wait,
 * CPU wait, compilation or texture/buffer allocation. A bounded full receipt
 * ring declines instead of waiting. Successful completion retains both consumer
 * and lease; caller still drops its own +1 lease exactly once after this call.
 * Even an abandoned encode may consume the lease's one attachment; reacquire.
 *
 * 0 = sideCB committed. 1 = unsupported translation, stale/already attached
 * lease, busy receipt storage or permanently poisoned consumer: caller encodes
 * ordinary synchronous terrain. Negative = invalid arguments/local failure;
 * unqueued work is discarded. No nonzero result authorizes reading outputs.
 * GPU failure permanently poisons the consumer; replace it on the worker.
 * SideCB completion reports GPU errors through the retained main-device status
 * and message ring using untimed tracking. Uncertain commit marks device lost.
 * Neither path signals/advances the main timeline or the producer timeline.
 * A submitted main stream must use normal device-loss handling on GPU failure.
 */
MCMTL_API int32_t mcmtl_ext_far_consumer_encode(mcmtl_handle consumer, mcmtl_handle lease,
    const void *transformsPointer, uint32_t imageCount,
    const mcmtl_far_consumer_fallback *fallbackDescPointer, uint32_t drawCount,
    uint64_t receiptSequence, char *error, uint32_t capacity);

/* MAIN ONLY, optional SHADED sidecar; consumes ONLY newest image. Same ownership,
 * queue ordering, receipt ring, sequence/error and lifetime rules as old encode.
 * Requires the lease's worker-prepared shading tuple; no frame compilation.
 * Zero-hole policy is unchanged. GPU alpha rejection restores ALL packed current
 * fallback draws and clears composite args; it is a normal fallback, not poison.
 * All validation and encoding precedes commit. Nonzero authorizes no outputs.
 */
MCMTL_API int32_t mcmtl_ext_far_consumer_encode_shaded(mcmtl_handle consumer,
    mcmtl_handle lease, const void *transform,
    const mcmtl_far_consumer_fallback *fallbackDescPointer, uint32_t drawCount,
    uint64_t receiptSequence, const mcmtl_far_consumer_shading *shading,
    char *error, uint32_t capacity);

/* Optional worker preparation, before ANY encode. Allocates no new full-size
 * images. extraBudgetBytes bounds incremental GPU allocations only, separately
 * from create's budget: maxDraws*32 + 2384 bytes. Includes three receipt slots,
 * draw-layer tables and six 384-byte diagnostic buffers. One successful enable.
 * The original create/outputs/encode APIs remain usable without enabling this.
 */
MCMTL_API int32_t mcmtl_ext_far_consumer_enable_layers(mcmtl_handle consumer,
    uint64_t extraBudgetBytes, char *error, uint32_t capacity);
MCMTL_API int32_t mcmtl_ext_far_consumer_layer_outputs(mcmtl_handle consumer,
    mcmtl_far_consumer_layer_outputs *outOutputs, char *error, uint32_t capacity);

/* MAIN ONLY, exact-camera shaded admission unchanged. flags=1 normally; 3
 * additionally selects optional per-pixel shader diagnostic counters and the
 * first degenerate-face sample. At most one valid SOLID sample is logged per
 * consumer, from completed GPU results, with its source frame and receipt. Old
 * encode_shaded continues requiring flags=1 and has no diagnostic overhead.
 * layerCodes is drawCount uint32 values in the same order as descriptors:
 * only 0/1/2/8/9/10 (SOLID/CUTOUT/TRANSLUCENT BLOCK and compact). Translucent
 * records ALWAYS pass through unchanged; the output keeps the FULL list indexing
 * including intervening translucent draws. Caller attests that
 * these are the actual current draw pipelines and preserves normal layer/depth
 * ordering when reading the two composite arguments. Native copies the codes
 * into this receipt's bounded slot before submitting; no later pointer use.
 * No CPU/GPU wait, allocation of GPU resources or frame shader compilation.
 * Only a successful layer encode authorizes layer_outputs. Any nonzero result
 * means the caller uses the complete ordinary synchronous path for this frame.
 * Audited SOLID built-ins never discard, write depth/samplemask, blend or use
 * alpha-to-coverage: material alpha affects color only. SOLID therefore falls
 * back iff its hole count is nonzero. CUTOUT falls back on holes OR changed
 * material alpha. Separate source-layer depth retains SOLID behind CUTOUT.
 */
MCMTL_API int32_t mcmtl_ext_far_consumer_encode_shaded_layers(mcmtl_handle consumer,
    mcmtl_handle lease, const void *transform,
    const mcmtl_far_consumer_fallback *fallbackDescPointer, const uint32_t *layerCodes,
    uint32_t drawCount, uint64_t receiptSequence, const mcmtl_far_consumer_shading *shading,
    char *error, uint32_t capacity);
MCMTL_API int32_t mcmtl_ext_far_consumer_layer_stats(mcmtl_handle consumer,
    mcmtl_far_consumer_layer_stats *outStats);
/* Optional raw uint32 counts[2][16], 128 bytes; index meanings are exactly
 * MCMTLFarShadeDiagnostic. Requires capacityBytes>=128 and the latest completed
 * layer receipt's nonzero sequence. DECLINED if that receipt had diagnostics off
 * or no longer matches. Never waits or reads GPU memory; the completion copied
 * these counters under the same receipt lock. Zeros all 128 bytes on failure
 * if outCounts!=NULL and capacityBytes>=128; smaller buffers are left untouched.
 */
MCMTL_API int32_t mcmtl_ext_far_consumer_layer_diagnostics(mcmtl_handle consumer,
    uint64_t receiptSequence, uint32_t *outCounts, uint32_t capacityBytes);

/* Any thread; lock contention returns DECLINED with a zero value result.
 * Drop a consumer once per +1 with existing mcmtl_ext_far_engine_drop.
 */
MCMTL_API int32_t mcmtl_ext_far_consumer_stats(mcmtl_handle consumer,
    mcmtl_far_consumer_stats *outStats);

#ifdef __cplusplus
}
#endif
