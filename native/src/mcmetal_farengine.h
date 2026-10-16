/* Prompt 5 phase 1, optional FFM coordinator. No production game consumer yet.
 * ABI 2 stays unchanged; this extension never enables itself or schedules work.
 */
#pragma once
#include "mcmetal_fargeometry.h"

#ifdef __cplusplus
extern "C" {
#endif

enum { MCMTL_FAR_ENGINE_DECLINED = 1, MCMTL_FAR_ENGINE_SHADER_COUNT = 10 };

typedef struct {
    uint32_t structSize, width, height, reserved; /* reserved = 0 */
    uint64_t epoch;
    /* Logical target bytes: 3 slots * 2 layers * (RGBA8 + D32 + R32).
     * packetBufferBudgetBytes bounds each image's cull + uniform GPU buffers;
     * up to three images may stay pinned. Driver padding, PSOs, bounded CPU
     * metadata and imported geometry/material allocations are excluded.
     */
    uint64_t targetBudgetBytes, packetBufferBudgetBytes;
} mcmtl_far_engine_config;

typedef struct {
    uint64_t epoch, sourceTimeNs, sourceFrame, geometryGeneration, materialGeneration;
} mcmtl_far_engine_source;

/* Column-major matrices, captured by caller in the same frame as the uniforms
 * and recipe's cull view. Opaque to the engine: no projection/origin adjustment
 * or inverse-matrix guess. guardProjection is the actual projection in slot 0.
 */
typedef struct {
    double position[3], renderOrigin[3];
    float view[16], guardProjection[16];
} mcmtl_far_engine_camera;

/* One descriptor per geometry input, in original order. Slot order 0,1,2,5.
 * Bytes begin at the original bound offset. Exact sizes 64,48,48,80; absent
 * unused bindings are {NULL,0}. Both stages use these same built-in UBO values.
 * All descriptors and bytes must be stable through submit; native copies them.
 */
typedef struct { mcmtl_far_bytes projection, globals, fog, terrain; } mcmtl_far_engine_uniforms;

typedef struct {
    uint32_t structSize, inputCount;
    mcmtl_far_engine_source source;
    mcmtl_far_engine_camera camera;
    mcmtl_handle geometryRecipe, materialVersion;
    /* Actual layer codes index this array: 0/1 BLOCK, 8/9 compact. Other entries
     * must be zero. Only entries referenced by an input need a shader handle. */
    mcmtl_handle shadersByLayer[MCMTL_FAR_ENGINE_SHADER_COUNT];
    const mcmtl_far_engine_uniforms *uniforms;
    /* Java attests COMMITTED, not merely encoded/enqueued: this exact main
     * timeline sequence follows all geometry uploads and material copies.
     * Must be >= geometryRequiredValue AND published material.readyValue.
     * Material.readyEvent must equal geometry.uploadEvent on the actual device.
     * No signaledValue check: an already committed ticket may still be pending.
     */
    uint64_t committedTicketSequence, geometryRequiredValue;
    uint32_t committed, reserved; /* committed = 1, reserved = 0 */
    double clearColor[2][4], clearDepth[2];
} mcmtl_far_engine_submission;

/* Value result; borrowed handles remain valid ONLY while the returned lease is
 * retained. Textures and inputs are READ ONLY; retaining textures alone does
 * not prevent slot reuse. Geometry/material/packet handles must NOT be dropped.
 */
typedef struct {
    uint32_t structSize, width, height, inputCount;
    mcmtl_far_engine_source source;
    mcmtl_far_engine_camera camera;
    uint64_t sequence, committedTicketSequence, geometryRequiredValue;
    mcmtl_handle color[2], depth[2], visibilityID[2];
    mcmtl_handle geometryRecipe, materialVersion, producerPacket;
} mcmtl_far_engine_image;

typedef struct {
    uint32_t structSize, presentMask; /* slot bits 0,1,2,5 */
    uint8_t projection[64], globals[48], fog[48], terrain[80];
} mcmtl_far_engine_uniform_copy;

enum {
    MCMTL_FAR_ENGINE_TIMING_CPU_VALID = 1u,
    MCMTL_FAR_ENGINE_TIMING_GPU_VALID = 2u
};
/* Additive 80-byte receipt; existing ABI 2 records remain unchanged. CPU samples
 * use CLOCK_UPTIME_RAW. sourceTimeNs is the caller's unchanged source-camera time
 * (use that same clock for source-age decomposition). GPU fields are driver
 * GPUStartTime/GPUEndTime seconds converted to ns only on successful completion;
 * flag 2 requires a finite, positive, ordered span. That span includes waits and
 * contended execution, not just GPU work. Unavailable GPU fields are zero.
 * completionNs may precede commitEndNs: callback/commit races are preserved.
 */
typedef struct {
    uint32_t structSize, flags;  /* 0,4: size = 80; flags 1 = CPU valid, 2 = GPU valid */
    uint64_t sourceTimeNs;      /* 8 */
    uint64_t reserveTimeNs;     /* 16: owner slot admission */
    uint64_t encodeStartNs;     /* 24: owner begins encoding (incl. buffer creation) */
    uint64_t commitStartNs;     /* 32: immediately before commit */
    uint64_t commitEndNs;       /* 40: commit return */
    uint64_t gpuStartNs;        /* 48 */
    uint64_t gpuEndNs;          /* 56 */
    uint64_t completionNs;     /* 64: producer completion callback entry */
    uint64_t sequence;         /* 72: same identity as leased image */
} mcmtl_far_engine_timing;

/* Worker only (also keep off a non-main render thread). device is MCMTLDevice.
 * Creates an image owner with its own queue and an isolated prepared cull wrapper.
 * All handles are live objects of the declared type, retained by caller during
 * each call. Returns +1 engine. Dimensions immutable; replace for resize.
 */
MCMTL_API int32_t mcmtl_ext_far_engine_create(mcmtl_handle device,
    const mcmtl_far_engine_config *config, mcmtl_handle *outEngine, char *error, uint32_t capacity);
/* Optional worker setup BEFORE the first submit/publication. Prepares per-recipe
 * ID lookup + shade programs on first submission, bounded by an additional
 * lookup/argument allocation budget. All four actual programs 0/1/8/9 are then
 * required. Does not alter the old create/submit ABI or enable normal rendering.
 */
MCMTL_API int32_t mcmtl_ext_far_engine_enable_shading(mcmtl_handle engine,
    uint64_t lookupBudgetBytes, char *error, uint32_t capacity);
/* Worker only, no internal worker/dispatch. 0 = committed far submission (NOT
 * publication); 1 = busy/stale/stopped/no slot; negative = local failure.
 * One producer across capture/encode/in-flight, including epoch changes.
 * Exactly one existing cull, one original draw per input; full guard viewport
 * and scissor, depthClipModeClip. No main-queue access, texture copies, main
 * commits or CPU waits. Only the far queue waits for the committed dependency.
 */
MCMTL_API int32_t mcmtl_ext_far_engine_submit(mcmtl_handle engine,
    const mcmtl_far_engine_submission *submission, char *error, uint32_t capacity);
/* Additive worker entry point: retain the same immutable geometry, but cull with
 * THIS refresh's owned 128-byte view (planes, flags, camera/eye deltas). The caller
 * must express deltas relative to that recipe's original cull-table origin and
 * provide matching current camera/uniform/source-time data in submission.
 * The bytes are copied before return; they never modify the recipe or older images.
 * A non-null descriptor and exact length are required. Same busy/commit/lifetime
 * contract as submit; the old entry point continues using the recipe's own view.
 */
MCMTL_API int32_t mcmtl_ext_far_engine_submit_view(mcmtl_handle engine,
    const mcmtl_far_engine_submission *submission, const mcmtl_far_bytes *cullView,
    char *error, uint32_t capacity);

/* Bounded try-acquire: newest + older COMPLETE in epoch, 1..2 distinct images.
 * 0 = success, +1 lease (MCMTLFarImageLease); 1 = miss, outputs zero. No GPU
 * reads/waits. Inspection only copies owned CPU metadata and borrowed handles.
 *
 * CONSUMER CONTRACT: keep the owned lease until ACTUAL MAIN GPU COMPLETION.
 * For a direct native consumer, attachToCommandBuffer (bridge below) installs
 * that hold before reads. For a future Java stream consumer, Java must defer
 * dropping this +1 lease to that stream's current main timeline completion,
 * including decode lifetime, and serialize epoch validation with final use.
 * Never release against PRODUCER completion. Abandoned reads may drop promptly;
 * stop/invalidate do not retract reads already admitted by the caller.
 */
MCMTL_API int32_t mcmtl_ext_far_engine_acquire(mcmtl_handle engine, uint64_t epoch,
    mcmtl_handle *outLease, uint32_t *outCount);
MCMTL_API int32_t mcmtl_ext_far_engine_image(mcmtl_handle lease, uint32_t index,
    mcmtl_far_engine_image *outImage);
/* Bounded CPU-only copy from a retained lease, protected by the same actual
 * consumer-completion lifetime as image(). No Metal calls, locks or waits.
 * outTiming must point to 80 writable bytes with structSize = 80 on EVERY call.
 * 0 = complete CPU receipt (GPU validity is independent); 1 = unavailable;
 * negative = bad arguments/size. Every failure zeros all 80 output bytes.
 */
MCMTL_API int32_t mcmtl_ext_far_engine_timing(mcmtl_handle lease, uint32_t index,
    mcmtl_far_engine_timing *outTiming);
MCMTL_API int32_t mcmtl_ext_far_engine_uniform(mcmtl_handle lease, uint32_t imageIndex,
    uint32_t inputIndex, mcmtl_far_engine_uniform_copy *outUniforms);
MCMTL_API int32_t mcmtl_ext_far_engine_attach(mcmtl_handle lease, mcmtl_handle commandBuffer);
/* Main thread only; no GPU wait. Invalidate requires a strictly greater epoch;
 * stop is permanent/idempotent. Neither waits for the worker's capture/encode.
 */
MCMTL_API int32_t mcmtl_ext_far_engine_invalidate(mcmtl_handle engine, uint64_t epoch);
MCMTL_API int32_t mcmtl_ext_far_engine_stop(mcmtl_handle engine);
/* Off the frame path: most recent owner encode/GPU error, empty until failure.
 * Synchronous call failures instead use their error buffer. Never decoder TLS.
 */
MCMTL_API int32_t mcmtl_ext_far_engine_error(mcmtl_handle engine, char *error, uint32_t capacity);
/* Any thread, once per +1 engine or acquired lease; never on borrowed handles.
 * Stop before dropping the active engine. Outstanding leases/work remain alive.
 */
MCMTL_API void mcmtl_ext_far_engine_drop(mcmtl_handle engineOrLease);

#ifdef __cplusplus
}
#endif

#ifdef __OBJC__
#import "mcmetal_farshade.h"
@interface MCMTLFarEngineShading : NSObject
@property(nonatomic, readonly) MCMTLFarShade *solid, *cutout;
@property(nonatomic, readonly) MCMTLFarShadeGeometry *geometry;
@end
/* Borrowed immutable preparation; live only while lease pins the image. No
 * locks, compilation, allocation or GPU reads. nil if shading was not prepared.
 */
MCMTLFarEngineShading *mcmtl_far_engine_shading(mcmtl_handle lease,uint32_t index);
#endif
