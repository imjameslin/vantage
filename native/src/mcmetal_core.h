/*
 * mcmetal_core.h -- private declarations shared by the I1 (native-core) files:
 * mcmetal_device.m, mcmetal_resources.m, mcmetal_pipeline.m, mcmetal_surface.m.
 * OWNER: I1 (native-core). Not part of the C ABI and not part of the frozen internal.h contract.
 *
 * I1 keeps a small amount of per-device state that the frozen MCMTLDevice class has no ivar for
 * (sampler-state cache, present pipelines, the timestamp clock domain, the texel-buffer allocation
 * granule). It lives in an MCMTLCoreState attached to the MCMTLDevice as an Objective-C associated
 * object: created by mcmtl_device_create before the handle is published (so later reads are plain loads
 * of immutable fields), and lazily (thread-safe) for device contexts built by tests with
 * mcmtl_device_context_create.
 */
#ifndef MCMETAL_CORE_H
#define MCMETAL_CORE_H

#import "mcmetal_internal.h"

/* RenderPearl GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER (mcmtl_buffer_desc.usage). */
#define MCMTL_CORE_USAGE_UNIFORM_TEXEL_BUFFER 256u

/* Upper bound of the sampler-state cache (vanilla needs ~40 distinct states). */
#define MCMTL_CORE_MAX_CACHED_SAMPLERS 4096u

/* MTLCounterSampleBuffer size limit on Apple GPUs (32 KiB of 8-byte timestamp samples). */
#define MCMTL_CORE_MAX_TIMESTAMP_SAMPLES 4096u

@interface MCMTLCoreState : NSObject {
@public
    /* Immutable after creation. */
    uint32_t maxTexelBufferAlignment;   /* max minimumTextureBufferAlignment over the texel formats    */
    BOOL     timestampsInTicks;          /* sampleTimestamps/counter values are mach ticks, not ns      */
    uint32_t timebaseNumer;              /* mach_timebase_info (ticks -> ns)                            */
    uint32_t timebaseDenom;

    /* Guarded by lock. */
    os_unfair_lock lock;
    NSMutableDictionary<NSData *, id<MTLSamplerState>> *samplers;          /* key: sampler desc bytes  */
    NSMutableDictionary<NSNumber *, id<MTLRenderPipelineState>> *presentPipelines; /* key: pixel format */
    id<MTLLibrary> presentLibrary;

    /* Render thread only (present pass). */
    MTLRenderPassDescriptor *presentPassDescriptor;
}
@end

/* The device's core state (never nil for a valid device; created on first use if needed). */
MCMTLCoreState *mcmtl_core_state(MCMTLDevice *dev);

/* Drops the core state of a device that is being destroyed (caches, present pipelines). */
void mcmtl_core_state_release(MCMTLDevice *dev);

/* Converts a sampleTimestamps / counter-sample value to nanoseconds (identity on Apple GPUs, which
 * already report nanoseconds; mach ticks are converted with the timebase). */
uint64_t mcmtl_core_timestamp_ns(MCMTLCoreState *core, uint64_t value);

/* ---- Pixel-format helpers (raw MTLPixelFormat values) ------------------------------------------ */

static inline BOOL mcmtl_core_format_has_depth(uint32_t f) {
    return f == MTLPixelFormatDepth16Unorm || f == MTLPixelFormatDepth32Float ||
           f == MTLPixelFormatDepth24Unorm_Stencil8 || f == MTLPixelFormatDepth32Float_Stencil8;
}

static inline BOOL mcmtl_core_format_has_stencil(uint32_t f) {
    return f == MTLPixelFormatStencil8 || f == MTLPixelFormatDepth24Unorm_Stencil8 ||
           f == MTLPixelFormatDepth32Float_Stencil8 || f == MTLPixelFormatX32_Stencil8 ||
           f == MTLPixelFormatX24_Stencil8;
}

static inline BOOL mcmtl_core_format_is_depth_stencil(uint32_t f) {
    return mcmtl_core_format_has_depth(f) || mcmtl_core_format_has_stencil(f);
}

/* Integer color formats: not blendable, not filterable. */
static inline BOOL mcmtl_core_format_is_integer(uint32_t f) {
    switch (f) {
        case MTLPixelFormatR8Uint: case MTLPixelFormatR8Sint:
        case MTLPixelFormatR16Uint: case MTLPixelFormatR16Sint:
        case MTLPixelFormatRG8Uint: case MTLPixelFormatRG8Sint:
        case MTLPixelFormatR32Uint: case MTLPixelFormatR32Sint:
        case MTLPixelFormatRG16Uint: case MTLPixelFormatRG16Sint:
        case MTLPixelFormatRGBA8Uint: case MTLPixelFormatRGBA8Sint:
        case MTLPixelFormatRGB10A2Uint:
        case MTLPixelFormatRG32Uint: case MTLPixelFormatRG32Sint:
        case MTLPixelFormatRGBA16Uint: case MTLPixelFormatRGBA16Sint:
        case MTLPixelFormatRGBA32Uint: case MTLPixelFormatRGBA32Sint:
            return YES;
        default:
            return NO;
    }
}

static inline uint64_t mcmtl_core_align_up(uint64_t value, uint64_t alignment) {
    if (alignment <= 1) return value;
    uint64_t rem = value % alignment;
    return rem ? value + (alignment - rem) : value;
}

/* ---- Present pass (spec 6.6), mcmetal_surface.m ------------------------------------------------ */

/* Flags returned by mcmtl_core_surface_encode_present (same bits as mcmtl_submit_result.flags). */
#define MCMTL_CORE_PRESENT_ENCODED  MCMTL_SUBMIT_RESULT_FLAG_PRESENT_ENCODED
#define MCMTL_CORE_DRAWABLE_NIL     MCMTL_SUBMIT_RESULT_FLAG_DRAWABLE_NIL
#define MCMTL_CORE_DRAWABLE_RESIZED MCMTL_SUBMIT_RESULT_FLAG_DRAWABLE_RESIZED

/* Cached present PSO for a destination pixel format (BGRA8Unorm for CAMetalLayer drawables): full-screen
 * triangle, fragment reads src at (x, copyH - 1 - y) from mip `level`, alpha forced to 1. Thread-safe.
 * Returns nil and sets *failure on error. */
id<MTLRenderPipelineState> mcmtl_core_present_pipeline(MCMTLDevice *dev, MTLPixelFormat format, NSString **failure);

/* Encodes the present pass into `commandBuffer` (no open encoder allowed): render pass on `dst` (load
 * DontCare when the copy covers dst, else Clear(0,0,0,1); store Store), viewport/scissor
 * (0, 0, copyW, copyH) with copyW = min(dst.width, max(1, src.width >> level)) (same for H), one
 * triangle. `src` must be a 2D texture with ShaderRead usage and `level` < its mip count. Render thread.
 * Returns NO and sets *failure (nothing encoded) on invalid input. */
BOOL mcmtl_core_encode_present(MCMTLDevice *dev, id<MTLCommandBuffer> commandBuffer, id<MTLTexture> dst,
                               id<MTLTexture> src, uint32_t level, NSString **failure);

/* The complete classic PRESENT step after the decoder's early commit (CONTRACT.md 8.2 steps 1-4 minus the
 * commit): [layer nextDrawable] (blocking), nil -> MCMTL_CORE_DRAWABLE_NIL; else surface->drawable =
 * drawable, outWidth/outHeight = drawable size, MCMTL_CORE_DRAWABLE_RESIZED if it differs from the
 * configured size, present pass from (src, level), [commandBuffer presentDrawable:], presentedFrames++,
 * MCMTL_CORE_PRESENT_ENCODED. Returns the flags; on an encode failure (bad source) the drawable is
 * dropped, *failure is set and only DRAWABLE flags are returned. Render thread. */
uint32_t mcmtl_core_surface_encode_present(MCMTLSurface *surface, id<MTLCommandBuffer> commandBuffer,
                                           id<MTLTexture> src, uint32_t level, uint32_t *outWidth,
                                           uint32_t *outHeight, NSString **failure);

#endif /* MCMETAL_CORE_H */
