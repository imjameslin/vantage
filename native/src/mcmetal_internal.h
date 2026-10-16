/*
 * mcmetal_internal.h -- Objective-C types and helpers shared by the native modules of libmcmetal.
 *
 * FROZEN CONTRACT FILE (owner: architect). Not part of the C ABI (Java never sees it).
 *
 * Module ownership (docs/metal/CONTRACT.md section 2):
 *   mcmetal_common.m     architect  error TLS, probe, ABI helpers, device-context basics, status page,
 *                                   debug-message ring, command-buffer completion tracking, commit
 *                                   helpers, the mcmtl_submit export (guard around the decoder),
 *                                   depth-stencil cache, @implementation of the classes below
 *   mcmetal_device.m     I1         mcmtl_device_*, mcmtl_timeline_*
 *   mcmetal_resources.m  I1         buffers, textures, views, texel views, samplers, query pools, release
 *   mcmetal_pipeline.m   I1         shaders, pipelines, PSO variants (mcmtl_pipeline_build_variant)
 *   mcmetal_surface.m    I1         CAMetalLayer surface, configure, present, destroy
 *   mcmetal_decoder.m    I2         mcmtl_decoder_submit (decode + encode + commit), dummies,
 *                                   internal PSOs, present pass
 *
 * Object model: every mcmtl_handle is a +1 retained Objective-C object. Buffers, textures, views, texel
 * views, samplers and shader functions are the raw Metal objects (id<MTLBuffer>, id<MTLTexture>,
 * id<MTLSamplerState>, id<MTLFunction>). Devices, pipelines, surfaces and query pools are the wrapper
 * classes declared below. Wrapper classes expose @public instance variables so the decoder's hot path
 * reads them without message sends; each ivar documents which module writes it.
 *
 * Threading: MCMTLDevice caches marked "cacheLock" and the message ring are thread-safe. Everything
 * else on the wrappers is written once before the handle is returned to Java, or only on the render
 * thread (decoder, surface functions).
 */
#ifndef MCMETAL_INTERNAL_H
#define MCMETAL_INTERNAL_H

#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import <QuartzCore/CAMetalLayer.h>
#include <os/lock.h>
#include <stdarg.h>
#include <stdatomic.h>

#include "mcmetal.h"
#include "mcmetal_stream.h"

/* ---------------------------------------------------------------------------------------------- */
/* Entry-point guard                                                                               */
/* ---------------------------------------------------------------------------------------------- */

/* Every MCMTL_API function body is written as
 *
 *     MCMTL_API int32_t mcmtl_x(...) {
 *         MCMTL_TRY_BEGIN
 *             ... body; must `return` on every path ...
 *         MCMTL_TRY_END(MCMTL_E_OBJC_EXCEPTION)
 *     }
 *
 * which expands to @autoreleasepool { @try { body } @catch { record error; return err; } } return err;
 * Trivial getters use the same macro with their "invalid" value (0).
 *
 * The @catch returns straight out of the export: it runs no cleanup of the body's state. Code that must
 * restore invariants on an exception (the decoder: end open encoders, consume the signal value) needs its
 * own inner @try/@catch/@finally (see mcmtl_decoder_submit). The shim is built with -fobjc-arc-exceptions,
 * so ARC releases strong locals (drawables, encoders, command buffers) while an exception unwinds. */
#define MCMTL_TRY_BEGIN @autoreleasepool { @try {
#define MCMTL_TRY_END(onException)                                                   \
        } @catch (NSException *mcmtl_exception_) {                                   \
            mcmtl_set_error_exception(mcmtl_exception_);                             \
            return (onException);                                                    \
        } @catch (id mcmtl_thrown_) {                                                \
            mcmtl_set_error("Objective-C object thrown: %s",                         \
                            [[mcmtl_thrown_ description] UTF8String]);              \
            return (onException);                                                    \
        }                                                                            \
    }                                                                                \
    return (onException);

/* ---------------------------------------------------------------------------------------------- */
/* Handle bridging                                                                                 */
/* ---------------------------------------------------------------------------------------------- */

/* Borrow the object behind a handle (no retain). */
#define MCMTL_OBJ(h)       ((__bridge id)(void *)(uintptr_t)(h))
#define MCMTL_BUFFER(h)    ((__bridge id<MTLBuffer>)(void *)(uintptr_t)(h))
#define MCMTL_TEXTURE(h)   ((__bridge id<MTLTexture>)(void *)(uintptr_t)(h))
#define MCMTL_SAMPLER(h)   ((__bridge id<MTLSamplerState>)(void *)(uintptr_t)(h))
#define MCMTL_FUNCTION(h)  ((__bridge id<MTLFunction>)(void *)(uintptr_t)(h))
#define MCMTL_DEVICE(h)    ((__bridge MCMTLDevice *)(void *)(uintptr_t)(h))
#define MCMTL_PIPELINE(h)  ((__bridge MCMTLPipeline *)(void *)(uintptr_t)(h))
#define MCMTL_SURFACE(h)   ((__bridge MCMTLSurface *)(void *)(uintptr_t)(h))
#define MCMTL_QUERY_POOL(h) ((__bridge MCMTLQueryPool *)(void *)(uintptr_t)(h))

/* Transfer +1 ownership of `obj` to a new handle (0 for nil). */
static inline mcmtl_handle mcmtl_handle_retain(id obj) {
    return obj ? (mcmtl_handle)(uintptr_t)(__bridge_retained void *)obj : 0;
}

/* Drop the +1 reference held by a handle (0 ignored). */
static inline void mcmtl_handle_release(mcmtl_handle h) {
    if (h) CFRelease((CFTypeRef)(uintptr_t)h);
}

/* ---------------------------------------------------------------------------------------------- */
/* Wrapper classes (@implementation in mcmetal_common.m)                                           */
/* ---------------------------------------------------------------------------------------------- */

#define MCMTL_PIPELINE_INLINE_VARIANTS 8u

/* Size of dev->dummyBuffer: 64 KiB, the usual GL/Vulkan uniform-buffer range limit for the same
 * RenderPearl shaders (far above any vanilla UBO), so a dummy bound for a missing UBO of any portable size
 * passes MTL_DEBUG_LAYER buffer-length checks. */
#define MCMTL_DUMMY_UNIFORM_BYTES 65536u

/* Device context. Handle = +1 MCMTLDevice. */
@interface MCMTLDevice : NSObject {
@public
    /* --- written by mcmtl_device_context_create (common.m); immutable afterwards --- */
    id<MTLDevice>             device;
    id<MTLCommandQueue>       queue;
    id<MTLSharedEvent>        event;              /* timeline; signaledValue starts at 0              */
    mcmtl_status_page        *status;             /* calloc'd, freed in -dealloc (completion handlers
                                                   * retain the device, so it outlives them)           */
    os_unfair_lock            messageLock;        /* guards messages                                   */
    NSMutableArray<NSString *> *messages;         /* debug-message ring, <= MCMTL_MAX_DEBUG_MESSAGES   */
    os_unfair_lock            cacheLock;          /* guards depthStencilStates                         */
    NSMutableDictionary<NSNumber *, id<MTLDepthStencilState>> *depthStencilStates;
    os_unfair_lock            completionLock;     /* guards completionPending (common.m only)          */
    NSMutableIndexSet        *completionPending;  /* values > completedValue + 1 that completed out of
                                                   * order (unbounded; usually empty) (common.m only)  */

    /* --- written by mcmtl_device_create (I1) before the handle is returned --- */
    uint32_t                  flags;              /* mcmtl_device_desc.flags                           */
    uint32_t                  familyMask;         /* MCMTL_FAMILY_*                                    */
    uint32_t                  featureMask;        /* MCMTL_FEATURE_*                                   */
    uint32_t                  mslLanguageVersion; /* MTLLanguageVersion used by mcmtl_shader_create     */
    uint32_t                  minTexelBufferAlignment;
    id<MTLCounterSet>         timestampCounterSet;/* nil if the GPU has none                           */

    /* --- render thread, owned by the decoder (I2); the submit guard (common.m) sets it to nil on
     *     every error path --- */
    id<MTLCommandBuffer>      currentCommandBuffer; /* open command buffer between a non-COMMIT submit
                                                     * and the next COMMIT; nil otherwise. Never holds
                                                     * an open encoder between calls.                  */

    /* --- created by mcmtl_decoder_device_init (I2), released by mcmtl_decoder_device_shutdown --- */
    id<MTLBuffer>             dummyBuffer;        /* >= MCMTL_DUMMY_UNIFORM_BYTES (64 KiB) of zeros:
                                                   * missing UBOs of any size, push constants (14)    */
    id<MTLTexture>            dummyTexture2D;     /* 1x1 RGBA8Unorm zeros, ShaderRead                  */
    id<MTLTexture>            dummyTextureCube;   /* 1x1 cube RGBA8Unorm zeros, ShaderRead             */
    id<MTLTexture>            dummyTexelBuffer;   /* 16 bytes R8Sint texture buffer, zeros             */
    id<MTLSamplerState>       dummySampler;       /* clamp-to-edge, nearest, not mipmapped              */
    id                        decoderState;       /* I2-private persistent state (internal PSOs, fan
                                                   * index buffers, upload rings); opaque to I1        */
}
@end

/* Render pipeline. Handle = +1 MCMTLPipeline. All ivars are written by mcmtl_pipeline_create (I1)
 * before the handle is returned, except the variant cache, which the decoder also extends (render
 * thread only, through mcmtl_pipeline_variant). */
@interface MCMTLPipeline : NSObject {
@public
    MCMTLDevice                  *owner;
    MTLRenderPipelineDescriptor  *descriptorTemplate; /* functions, vertex descriptor, blend state, label;
                                                       * attachment formats are patched per variant     */
    NSString                     *label;
    uint32_t                      colorTargetCount;
    uint32_t                      colorTargetFormats[MCMTL_MAX_COLOR_ATTACHMENTS]; /* 0 = null target */

    /* PSO variant cache, keyed by mcmtl_variant_key(). */
    uint32_t                      variantCount;
    uint64_t                      variantKeys[MCMTL_PIPELINE_INLINE_VARIANTS];
    __strong id<MTLRenderPipelineState> variantStates[MCMTL_PIPELINE_INLINE_VARIANTS];
    NSMutableDictionary<NSNumber *, id<MTLRenderPipelineState>> *overflowVariants; /* lazily created */

    /* Encoder state applied by SET_PIPELINE (each only on change). */
    id<MTLDepthStencilState>      depthStencilState;
    uint32_t                      primitiveType;      /* MTLPrimitiveType for draws                    */
    uint32_t                      emulation;          /* MCMTL_EMULATION_*                             */
    uint32_t                      cullMode;
    uint32_t                      fillMode;
    uint32_t                      frontFacing;
    float                         depthBiasConstant;
    float                         depthBiasSlopeScale;
    float                         depthBiasClamp;
    uint32_t                      pushConstantSize;
    uint32_t                      pushConstantStages;

    /* Used-resource masks, [0] = vertex, [1] = fragment (copied from mcmtl_pipeline_desc). */
    uint32_t                      bufferMask[2];
    uint32_t                      textureMask[2];
    uint32_t                      samplerMask[2];
    uint32_t                      textureCubeMask[2];
    uint32_t                      texelBufferMask[2];
    uint32_t                      requiredVertexBufferMask;
}
@end

@class MCMTLMailbox;

/* Surface. Handle = +1 MCMTLSurface. */
@interface MCMTLSurface : NSObject {
@public
    MCMTLDevice          *owner;            /* surface_create (I1)                                     */
    CAMetalLayer         *layer;            /* surface_create (I1); retained                           */
    id<CAMetalDrawable>   drawable;         /* set by the decoder (I2) when PRESENT acquires a drawable;
                                             * cleared by mcmtl_surface_present / destroy (I1)          */
    uint32_t              configuredWidth;  /* surface_configure (I1)                                  */
    uint32_t              configuredHeight;
    uint32_t              configFlags;      /* MCMTL_SURFACE_FLAG_*                                    */
    uint32_t              presentedFrames;  /* diagnostics, incremented by the decoder                 */
    MCMTLMailbox         *mailbox;          /* non-blocking presentation state (CONTRACT.md 16.4):
                                             * created by surface_create (nil on hand-built test
                                             * surfaces), released by surface_destroy                   */
}
@end

/* Non-blocking ("mailbox") presentation with vsync off (CONTRACT.md 16.4; mcmetal_surface.m). While a surface's
 * mailbox is active (configured without MCMTL_SURFACE_FLAG_DISPLAY_SYNC and MCMTL_MAILBOX != 0), a helper thread
 * blocks in -[CAMetalLayer nextDrawable] and parks the drawable it gets; the render thread only ever takes a
 * parked drawable (never waits) and skips presenting a frame when there is none. Render thread unless noted. */
enum {
    MCMTL_MAILBOX_TAKEN = 0,      /* a drawable was returned                                                  */
    MCMTL_MAILBOX_NONE_READY,     /* the helper thread is still waiting for Core Animation                    */
    MCMTL_MAILBOX_QUEUE_FULL,     /* MCMTL_MAILBOX_MAX_QUEUED presented frames still wait for the display     */
    MCMTL_MAILBOX_STALE,          /* the parked drawable's size no longer matched layer.drawableSize (dropped) */
};
/* YES while the surface presents without blocking (cheap; no lock). */
BOOL mcmtl_mailbox_active(MCMTLSurface *surface);
/* Takes the parked drawable without blocking; nil with *outWhy = MCMTL_MAILBOX_* otherwise. */
id<CAMetalDrawable> mcmtl_mailbox_take(MCMTLSurface *surface, uint32_t *outWhy);
/* YES if a drawable is parked right now (diagnostics: a frame that skipped could have used it). */
BOOL mcmtl_mailbox_ready(MCMTLSurface *surface);
/* Call before registering the present of `drawable` on `surface` (any mode): counts it as queued for display and
 * installs the presented handler that ends the count and records display statistics. No-op without a mailbox. */
void mcmtl_mailbox_will_present(MCMTLSurface *surface, id<CAMetalDrawable> drawable);

/* Cumulative counters of one surface's presentation (decoder stats log). */
typedef struct mcmtl_mailbox_stats {
    uint32_t active;             /* mailbox presentation on                                       */
    uint32_t drawableCount;      /* layer.maximumDrawableCount                                    */
    uint32_t maxQueued;          /* MCMTL_MAILBOX_MAX_QUEUED (0 = no limit)                       */
    uint32_t threadRunning;      /* the helper thread exists (exits after mcmtl_surface_destroy)  */
    uint32_t parked;             /* a drawable is parked right now                                */
    uint32_t reserved;
    uint64_t acquires;          /* nextDrawable calls returned on the helper thread              */
    uint64_t acquireWaitNs;      /* time the helper thread spent inside nextDrawable              */
    uint64_t acquireNil;         /* nextDrawable returned nil (helper thread backs off)           */
    uint64_t discarded;          /* drawables dropped unpresented: configure, mode change, destroy */
    uint64_t staleDiscarded;     /* parked drawables dropped because the layer size changed       */
    uint64_t gateTimeouts;       /* queue gate given up after MCMTL_MAILBOX_QUEUE_TIMEOUT_MS      */
    uint64_t presentsRegistered; /* mcmtl_mailbox_will_present calls                              */
    uint64_t presentsHandled;    /* presented handlers that ran                                   */
    uint64_t presentsPending;    /* presents the queue gate still counts as waiting for display   */
    uint64_t presentsDisplayed;  /* ... with presentedTime > 0 (on screen)                        */
    uint64_t presentsDropped;    /* ... with presentedTime == 0 (Core Animation never showed it)  */
    uint64_t displayLatencyNs;   /* sum over displayed presents: registration -> on screen        */
} mcmtl_mailbox_stats;
void mcmtl_mailbox_get_stats(MCMTLSurface *surface, mcmtl_mailbox_stats *out);

/* Present fusion (CONTRACT.md 16.3; mcmetal_surface.m). MCMTL_PRESENT_FUSION: 1 (default) = flipped layer + the
 * decoder renders the frame's last passes straight into the drawable; 0 = the M1/M2 present pass, layer untouched. */
int mcmtl_present_fusion_env(void);
/* YES iff the layer displays drawable memory row 0 at the bottom (vertical mirror transform). Render thread. */
BOOL mcmtl_layer_is_flipped(CALayer *layer);
/* Installs (kept across AppKit geometry updates) or removes the vertical mirror. Render (= main) thread. */
void mcmtl_layer_set_flipped(CALayer *layer, BOOL flipped);

/* Timestamp query pool. Handle = +1 MCMTLQueryPool. */
@interface MCMTLQueryPool : NSObject {
@public
    MCMTLDevice                *owner;        /* query_pool_create (I1)                                */
    id<MTLCounterSampleBuffer>  sampleBuffer; /* nil when timestamps are unsupported                   */
    uint32_t                    count;
}
@end

/* ---------------------------------------------------------------------------------------------- */
/* Errors and messages (mcmetal_common.m)                                                          */
/* ---------------------------------------------------------------------------------------------- */

/* Thread-local last error (what mcmtl_last_error() returns). */
void mcmtl_clear_error(void);
void mcmtl_set_error(const char *format, ...) __attribute__((format(printf, 1, 2)));
void mcmtl_set_error_ns(NSString *message);
void mcmtl_set_error_exception(NSException *exception);
void mcmtl_set_error_nserror(const char *context, NSError *error);

/* Appends to the device's debug-message ring (thread-safe), bumps status->messageCount and also logs
 * with NSLog when MCMTL_DEVICE_FLAG_VALIDATION is set. Use for warnings Java should surface in crash
 * reports (skipped draws, failed variants, command-buffer errors). Rate-limit repeated warnings. */
void mcmtl_device_message(MCMTLDevice *dev, const char *format, ...) __attribute__((format(printf, 2, 3)));

/* ---------------------------------------------------------------------------------------------- */
/* Device context basics (mcmetal_common.m)                                                        */
/* ---------------------------------------------------------------------------------------------- */

/* Creates an MCMTLDevice with device, queue (labelled), event (signaledValue 0), status page, message
 * ring and caches. Does not set flags/familyMask/... and does not create decoder state: callers
 * (mcmtl_device_create, tests) do that. Returns nil and sets the last error on failure. */
MCMTLDevice *mcmtl_device_context_create(id<MTLDevice> mtlDevice, NSString *queueLabel);

/* MCMTL_FAMILY_* mask for a device (supportsFamily: queries). */
uint32_t mcmtl_query_family_mask(id<MTLDevice> mtlDevice);

/* Timeline values are dense: Java signals 1, 2, 3, ... (each COMMIT uses the previous value + 1) and
 * status->completedValue is the largest v such that every value <= v has completed, so it is correct
 * even if command buffers complete out of order. */

/* Installs the completion handler for a command buffer that will signal `value`. MUST be called before
 * [cb commit] for every command buffer that signals the timeline (mcmtl_commit_signaled does it). The
 * handler (Metal thread, no upcalls): records errors in the status page and message ring (setting
 * deviceLost for Timeout, PageFault, DeviceRemoved, AccessRevoked, Internal), updates lastGpuTimeNs,
 * marks `value` complete, then raises the event to the new contiguous prefix if it is behind (a failed
 * command buffer's signal may never execute; the event never goes past a value whose work may still run). */
void mcmtl_track_command_buffer(MCMTLDevice *dev, id<MTLCommandBuffer> commandBuffer, uint64_t value);

/* Same error reporting (status page, message ring, deviceLost) for a command buffer that does NOT signal
 * the timeline -- the decoder's early commit before nextDrawable (CONTRACT.md 8.2). `attributedValue` is
 * the value signalled by the command buffer that follows it (reported as lastErrorValue); nothing is
 * marked complete. */
void mcmtl_track_untimed_command_buffer(MCMTLDevice *dev, id<MTLCommandBuffer> commandBuffer, uint64_t attributedValue);

/* The only way native code commits a value-signalling command buffer: encodeSignalEvent(value),
 * mcmtl_track_command_buffer, [cb commit], then status->submittedValue = value (in that order, so a
 * submittedValue >= value always means the value is consumed). All encoders on `cb` must be ended. */
void mcmtl_commit_signaled(MCMTLDevice *dev, id<MTLCommandBuffer> commandBuffer, uint64_t value);

/* Commits a command buffer without a timeline signal (mcmtl_track_untimed_command_buffer + commit). */
void mcmtl_commit_untimed(MCMTLDevice *dev, id<MTLCommandBuffer> commandBuffer, uint64_t attributedValue);

/* Error path of a COMMIT submit: drops dev->currentCommandBuffer WITHOUT committing it (the caller ended
 * or abandoned its encoders) and, if status->submittedValue < signalValue, completes signalValue on the
 * CPU. Idempotent. Used by the mcmtl_submit guard and available to the decoder. */
void mcmtl_submit_abort(MCMTLDevice *dev, uint64_t signalValue);

/* Marks `value` complete without GPU work: raises status->submittedValue to `value`, marks it complete and
 * raises the event to the new contiguous prefix (never to `value` itself while earlier values are still
 * outstanding: a waiter for an earlier value must not see it as signaled). Last resort for failure paths
 * in which a value that Java expects to be signaled could not be committed: the timeline must advance. */
void mcmtl_complete_value_on_cpu(MCMTLDevice *dev, uint64_t value);

/* Blocking wait used by mcmtl_timeline_wait and native code: returns MCMTL_OK once
 * status->completedValue >= value or event.signaledValue >= value (the GPU executed the signal; command
 * buffers of one queue execute in commit order, spec 1.5 (a); completion handlers may still lag),
 * MCMTL_E_TIMEOUT after timeoutNs (MCMTL_WAIT_FOREVER = never), MCMTL_E_DEVICE_LOST if status->deviceLost
 * is set before completion, MCMTL_E_INVALID_ARGUMENT if value > status->submittedValue. Waits on the
 * shared event in short slices (CPU-completed values are noticed at the next slice, <= 10 ms). */
int32_t mcmtl_wait_completed(MCMTLDevice *dev, uint64_t value, uint64_t timeoutNs);

/* Atomic helpers for the status page (release stores / acquire loads). */
static inline uint64_t mcmtl_status_load_u64(const uint64_t *field) {
    return __atomic_load_n(field, __ATOMIC_ACQUIRE);
}
static inline void mcmtl_status_store_u64(uint64_t *field, uint64_t value) {
    __atomic_store_n(field, value, __ATOMIC_RELEASE);
}
/* *field = max(*field, value), atomically. */
static inline void mcmtl_status_raise_u64(uint64_t *field, uint64_t value) {
    uint64_t current = __atomic_load_n(field, __ATOMIC_RELAXED);
    while (current < value &&
           !__atomic_compare_exchange_n(field, &current, value, 1, __ATOMIC_RELEASE, __ATOMIC_RELAXED)) {
    }
}

/* Cached MTLDepthStencilState for (compare, write) (thread-safe, cacheLock). Never nil for valid input. */
id<MTLDepthStencilState> mcmtl_device_depth_stencil_state(MCMTLDevice *dev, uint32_t compareFunction, BOOL depthWriteEnabled);

/* ---------------------------------------------------------------------------------------------- */
/* Pipeline variants                                                                               */
/* ---------------------------------------------------------------------------------------------- */

/* Variant key: which color slots have an attachment in the pass (bit i) + the pass's depth format. */
static inline uint64_t mcmtl_variant_key(uint32_t colorPresentMask, uint32_t depthPixelFormat) {
    return (uint64_t)(colorPresentMask & 0xFFu) | ((uint64_t)depthPixelFormat << 8);
}

/* Variant key flag (present fusion, decoder only; CONTRACT.md 16.3): color slot 0 is a BGRA8Unorm drawable where the
 * pipeline targets RGBA8Unorm (or BGRA8Unorm). The variant is otherwise identical (blend state, write masks). */
#define MCMTL_VARIANT_KEY_BGRA8_COLOR0 (1ull << 63)

/* Variant key flag (terrain ICB, decoder only; CONTRACT.md 16.5): the variant executes indexed indirect draws from an
 * MTLIndirectCommandBuffer. supportIndirectCommandBuffers = YES, and both shader functions are recompiled from their
 * MSL with every [[texture(n)]] / [[sampler(n)]] entry-point parameter read from an argument buffer at
 * [[buffer(MCMTL_ICB_ARG_BUFFER_INDEX)]] instead (Metal rejects ICB pipelines whose shaders bind textures directly).
 * The argument buffer holds, for texture slot n, the texture's MTLResourceID at byte 16n and the sampler's at 16n + 8
 * (mcmtl_msl_icb_rewrite). Building such a variant compiles MSL: never on the render thread. */
#define MCMTL_VARIANT_KEY_ICB (1ull << 62)
#define MCMTL_ICB_ARG_BUFFER_INDEX 15u   /* vertex-buffer slot 15's index: vanilla never binds that slot */

/* MCMTL_TERRAIN_ICB (CONTRACT.md 16.5): 1 (default) = indexed indirect draws run through ICBs (the decoder builds ICB
 * variants; mcmtl_shader_create keeps MSL sources; samplers support argument buffers); 0 = the per-record path, and
 * none of that happens. Read once. */
int mcmtl_terrain_icb_env(void);

/* Keeps the MSL source (with its compile options and entry point) of a shader function so its ICB variant can be
 * compiled later (mcmtl_shader_create does this when MCMTL_TERRAIN_ICB=1; tests call it directly). Thread-safe. */
void mcmtl_shader_keep_source(id<MTLFunction> function, NSString *source, NSString *entryPoint, MTLCompileOptions *options);

/* The ICB form of SPIRV-Cross MSL (see MCMTL_VARIANT_KEY_ICB): the entry point `entryPoint` loses its [[texture(n)]] and
 * [[sampler(n)]] parameters, gains `constant mcmtl_icb_args& mcmtl_icb [[buffer(argIndex)]]` and starts by copying the
 * handles into locals of the same names, so the body (and every helper it calls) is unchanged. Returns the source
 * unchanged when the entry point has no textures. *textureMask / *samplerMask: slots read. nil + *failure when the
 * signature cannot be rewritten (texture arrays, slot >= 16, [[buffer(argIndex)]] already used, no entry point). */
NSString *mcmtl_msl_icb_rewrite(NSString *source, NSString *entryPoint, uint32_t argIndex, uint32_t *textureMask,
                                uint32_t *samplerMask, NSString **failure);

/* The variant descriptor for `key` (a copy of the template with the key's formats; what
 * mcmtl_pipeline_build_variant compiles), for asynchronous compiles. nil + *failure when the key cannot be built.
 * Thread-safe like mcmtl_pipeline_build_variant. With MCMTL_VARIANT_KEY_ICB it compiles the rewritten shader functions
 * (cached per function), so call it off the render thread. Implemented by I1 in mcmetal_pipeline.m. */
MTLRenderPipelineDescriptor *mcmtl_pipeline_variant_descriptor(MCMTLPipeline *pipeline, uint64_t key, NSString **failure);

/* Builds (does not cache) the PSO for `key` from a COPY of the template (`[p->descriptorTemplate copy]`;
 * the template itself is never mutated after mcmtl_pipeline_create, which is what makes this thread-safe):
 * colorAttachments[i].pixelFormat = (mask bit i ? colorTargetFormats[i] : Invalid),
 * depthAttachmentPixelFormat = key's depth format and stencilAttachmentPixelFormat = the same format when
 * it has a stencil aspect (Depth32Float_Stencil8, Depth24Unorm_Stencil8, X32_Stencil8...). Returns nil and
 * appends a device message on failure. Implemented by I1 in mcmetal_pipeline.m. */
id<MTLRenderPipelineState> mcmtl_pipeline_build_variant(MCMTLPipeline *pipeline, uint64_t key);

static inline id<MTLRenderPipelineState> mcmtl_pipeline_find_variant(MCMTLPipeline *p, uint64_t key) {
    for (uint32_t i = 0; i < p->variantCount; i++) {
        if (p->variantKeys[i] == key) return p->variantStates[i];
    }
    return p->overflowVariants ? p->overflowVariants[@(key)] : nil;
}

/* Inserts a variant (callers: mcmtl_pipeline_create before publishing the handle; the decoder; tests). */
static inline void mcmtl_pipeline_add_variant(MCMTLPipeline *p, uint64_t key, id<MTLRenderPipelineState> pso) {
    if (!pso) return;
    if (p->variantCount < MCMTL_PIPELINE_INLINE_VARIANTS) {
        p->variantKeys[p->variantCount] = key;
        p->variantStates[p->variantCount] = pso;
        p->variantCount++;
        return;
    }
    if (!p->overflowVariants) p->overflowVariants = [NSMutableDictionary dictionary];
    p->overflowVariants[@(key)] = pso;
}

/* Find, else build and cache. Render thread (decoder) only once the pipeline handle is published. */
static inline id<MTLRenderPipelineState> mcmtl_pipeline_variant(MCMTLPipeline *p, uint64_t key) {
    id<MTLRenderPipelineState> pso = mcmtl_pipeline_find_variant(p, key);
    if (!pso) {
        pso = mcmtl_pipeline_build_variant(p, key);
        mcmtl_pipeline_add_variant(p, key, pso);
    }
    return pso;
}

/* ---------------------------------------------------------------------------------------------- */
/* Decoder hooks (I2, mcmetal_decoder.m)                                                           */
/* ---------------------------------------------------------------------------------------------- */

/* Called by mcmtl_device_create after the context exists: creates the dummies listed on MCMTLDevice
 * and any decoder-private state (dev->decoderState). Returns NO and sets the last error on failure. */
BOOL mcmtl_decoder_device_init(MCMTLDevice *dev);

/* Called by mcmtl_device_destroy after the GPU is idle: releases dummies and decoder state. */
void mcmtl_decoder_device_shutdown(MCMTLDevice *dev);

/* The decoder proper (I2). Called only by the mcmtl_submit export (common.m), which has already:
 * validated device/info/outResult (non-NULL, structSize), rejected unknown/ASYNC flags and stream
 * lengths that are not multiples of 16, checked signalValue == status->submittedValue + 1 for COMMIT,
 * zero-filled *result (structSize kept), cleared the last error. After it returns (or throws) the guard
 * fills result->completedValue / cpuNanos / the DEVICE_LOST flag and, for COMMIT, enforces the
 * consumption rule of mcmetal.h: if status->submittedValue < signalValue it calls mcmtl_submit_abort and
 * clears MCMTL_SUBMIT_RESULT_FLAG_COMMITTED (and turns MCMTL_OK into MCMTL_E_INTERNAL).
 *
 * Required structure (CONTRACT.md 4.2):
 *     @try { decode initStream, then stream }
 *     @catch (NSException *e) { mcmtl_set_error_exception(e); rc = MCMTL_E_OBJC_EXCEPTION; }
 *     @finally {
 *         end every open encoder, each inside its own @try (an encoder that cannot be ended poisons its
 *         command buffer: abandon it uncommitted and, for COMMIT, commit a fresh command buffer that only
 *         signals, or call mcmtl_submit_abort);
 *         COMMIT: mcmtl_commit_signaled(dev, cb, signalValue) and result->flags |= COMMITTED;
 *         every path: dev->currentCommandBuffer = nil unless this is a successful non-COMMIT call.
 *     }
 * Decoder look-ahead state (pending clears, deferred store actions, merged encoders, hoisted uploads)
 * never outlives one call: everything is materialized before it returns, whatever FRAME_END says. */
int32_t mcmtl_decoder_submit(MCMTLDevice *dev, const mcmtl_submit_info *info, mcmtl_submit_result *result);

/* Per-call decode context of mcmtl_submit (classic decoder). Semantics owned by I2; the layout is
 * frozen so the shadow-state model of spec section 3.4 is reviewable, and `ext` carries any additional
 * I2-private state. Object fields are __unsafe_unretained: the decoder keeps strong references to
 * what it creates in local variables / dev->currentCommandBuffer, and Java keeps every handle alive. */
typedef struct mcmtl_decode_ctx {
    __unsafe_unretained MCMTLDevice                 *dev;
    const mcmtl_submit_info                         *info;
    mcmtl_submit_result                             *result;
    __unsafe_unretained id<MTLCommandBuffer>         commandBuffer;   /* == dev->currentCommandBuffer   */
    __unsafe_unretained id<MTLRenderCommandEncoder>  renderEncoder;   /* open render encoder or nil     */
    __unsafe_unretained id<MTLBlitCommandEncoder>    blitEncoder;     /* open blit encoder or nil       */

    /* Current pass (BEGIN_PASS .. END_PASS). */
    uint32_t inPass;
    uint32_t passColorPresentMask;      /* bit i: color attachment i present                         */
    uint32_t passDepthFormat;           /* MTLPixelFormat of the depth attachment, 0 if none          */
    uint32_t attachmentWidth;           /* size of the attachments at their levels                   */
    uint32_t attachmentHeight;
    int32_t  renderArea[4];             /* x, y, w, h                                                 */

    /* Pipeline and fixed-function shadow state (spec 3.4). */
    __unsafe_unretained MCMTLPipeline               *pipeline;
    __unsafe_unretained id<MTLRenderPipelineState>   pso;
    __unsafe_unretained id<MTLDepthStencilState>     depthStencilState;
    uint32_t cullMode, fillMode, frontFacing;
    float    depthBias[3];              /* constant, slopeScale, clamp                                */
    int32_t  scissor[4];

    /* Binding shadow per stage ([0] vertex, [1] fragment) and per Metal index. */
    __unsafe_unretained id<MTLBuffer>        buffers[2][31];
    uint64_t                                 bufferOffsets[2][31];
    __unsafe_unretained id<MTLTexture>       textures[2][MCMTL_MAX_TEXTURE_BINDINGS];
    __unsafe_unretained id<MTLSamplerState>  samplers[2][MCMTL_MAX_TEXTURE_BINDINGS];
    uint32_t bufferSetMask[2];          /* bit n: [[buffer(n)]] set since the last SET_PIPELINE       */
    uint32_t textureSetMask[2];         /* bit n: [[texture(n)]] set since the last SET_PIPELINE      */

    /* Index buffer (SET_INDEX_BUFFER). */
    __unsafe_unretained id<MTLBuffer>        indexBuffer;
    uint32_t indexType;                 /* MTLIndexType                                               */

    void *ext;                          /* I2-private                                                 */
} mcmtl_decode_ctx;

#endif /* MCMETAL_INTERNAL_H */
