/*
 * core_test.m -- headless test of every I1 (native-core) export. OWNER: I1. CONTRACT.md 11.1.
 * Built and run by tools/build-native-tests.sh --run (MTL_DEBUG_LAYER=1: any invalid Metal call aborts,
 * so passing also means the exports never hand Metal an invalid descriptor).
 *
 * Covers: device create/info/destroy (+ error paths, leak check with in-flight work), timeline wait and
 * timestamps, buffers (Shared/Private/write-combined, texel rounding, OOM), textures (2D/cube/depth,
 * usages, storage), texture views (partial mips, cube, cube face; content checked through a blit), texel
 * views (read back through a real draw), samplers (dedup + every address/filter/mip/LOD field verified by
 * sampling), timestamp query pools (fresh pool unavailable, real GPU samples resolved to ns), MSL compile
 * (real SPIRV-Cross output of vanilla shaders, failures with logs, thread-local errors under concurrency),
 * pipelines (vertex descriptor, blending, write masks, depth state, precompiled + lazy + overflow variants,
 * concurrent creation, every validation error), end-to-end rendering with the real vanilla terrain
 * pipeline (vertex formats incl. Short2/Int3/per-instance, baseVertex/baseInstance, hybrid bindings, Y flip,
 * winding/culling, depth), a depth-only pipeline, the present pass (flip, alpha 1, swizzle, uncovered
 * clear, mip level) and the CAMetalLayer surface (properties, configure, drawable acquire/present/resize),
 * release / release_batch with weak-reference leak checks. Never relies on the I2 decoder.
 */
#import "mcmetal_core.h"

#import <QuartzCore/CATransaction.h>
#include <dispatch/dispatch.h>
#include <float.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include "core_test_msl.h"

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

#define CHECK_RC(expr, expected, what)                                                                   \
    do {                                                                                                 \
        int32_t rc_ = (expr);                                                                            \
        CHECK(rc_ == (expected), "%s: rc %d (expected %d) last error '%s'", what, rc_, (int)(expected), \
              mcmtl_last_error());                                                                       \
    } while (0)

static mcmtl_handle g_device;      /* +1 handle of the main test device */
static MCMTLDevice *g_dev;         /* borrowed view of it */

/* GpuBuffer usage bits (RenderPearl). */
enum { USAGE_MAP_WRITE = 2, USAGE_COPY_DST = 8, USAGE_VERTEX = 32, USAGE_INDEX = 64, USAGE_UNIFORM = 128, USAGE_TEXEL = 256 };

/* ---------------------------------------------------------------------------------------------- */
/* Helpers                                                                                          */
/* ---------------------------------------------------------------------------------------------- */

static mcmtl_handle make_buffer(uint64_t size, uint32_t usage, uint32_t options, const char *label, mcmtl_buffer_result *outResult) {
    mcmtl_buffer_desc d = {.structSize = sizeof d, .usage = usage, .size = size, .resourceOptions = options, .label = label};
    mcmtl_buffer_result r = {.structSize = sizeof r};
    int32_t rc = mcmtl_buffer_create(g_device, &d, &r);
    CHECK(rc == MCMTL_OK && r.handle, "buffer_create(%llu, usage %u, options 0x%x): %d %s", (unsigned long long)size, usage, options, rc,
          mcmtl_last_error());
    if (outResult) *outResult = r;
    return r.handle;
}

static mcmtl_handle make_texture(uint32_t format, uint32_t type, uint32_t w, uint32_t h, uint32_t mips, uint32_t usage, const char *label) {
    mcmtl_texture_desc d = {.structSize = sizeof d, .pixelFormat = format, .textureType = type, .width = w, .height = h, .depth = 1,
                            .mipLevels = mips, .arrayLength = 1, .textureUsage = usage, .resourceOptions = MTLResourceStorageModePrivate,
                            .label = label};
    mcmtl_object_result r = {.structSize = sizeof r};
    int32_t rc = mcmtl_texture_create(g_device, &d, &r);
    CHECK(rc == MCMTL_OK && r.handle, "texture_create(%s): %d %s", label ? label : "", rc, mcmtl_last_error());
    return r.handle;
}

static mcmtl_sampler_desc sampler_desc(uint32_t address, uint32_t minMag, uint32_t mip, uint32_t aniso, float lodMin, float lodMax) {
    mcmtl_sampler_desc d = {.structSize = sizeof d, .sAddressMode = address, .tAddressMode = address,
                            .rAddressMode = MTLSamplerAddressModeRepeat, .minFilter = minMag, .magFilter = minMag, .mipFilter = mip,
                            .maxAnisotropy = aniso, .lodMinClamp = lodMin, .lodMaxClamp = lodMax,
                            .compareFunction = MTLCompareFunctionNever, .flags = MCMTL_SAMPLER_FLAG_SUPPORT_ARGUMENT_BUFFERS};
    return d;
}

static mcmtl_handle make_sampler(const mcmtl_sampler_desc *d) {
    mcmtl_object_result r = {.structSize = sizeof r};
    int32_t rc = mcmtl_sampler_create(g_device, d, &r);
    CHECK(rc == MCMTL_OK && r.handle, "sampler_create: %d %s", rc, mcmtl_last_error());
    return r.handle;
}

static int32_t shader_rc(uint32_t stage, const char *src, size_t len, const char *entry, uint32_t flags, uint32_t version, const char *label,
                         mcmtl_handle *out) {
    mcmtl_shader_desc d = {.structSize = sizeof d, .stage = stage, .languageVersion = version, .flags = flags, .source = src,
                           .sourceLength = len, .entryPoint = entry, .label = label};
    *out = 0;
    return mcmtl_shader_create(g_device, &d, out);
}

static mcmtl_handle make_shader(uint32_t stage, const char *src, const char *label) {
    mcmtl_handle h = 0;
    int32_t rc = shader_rc(stage, src, strlen(src), "main0", 0, MTLLanguageVersion3_2, label, &h);
    CHECK(rc == MCMTL_OK && h, "shader_create(%s): %d %s", label, rc, mcmtl_last_error());
    return h;
}

/* Commits through the architect's signalling path (dense timeline values) and waits with the I1 export. */
static BOOL commit_and_wait(id<MTLCommandBuffer> cb, const char *what) {
    uint64_t value = mcmtl_status_load_u64(&g_dev->status->submittedValue) + 1;
    mcmtl_commit_signaled(g_dev, cb, value);
    int32_t rc = mcmtl_timeline_wait(g_device, value, 5000000000ull);
    CHECK(rc == MCMTL_OK, "%s: timeline_wait(%llu) -> %d %s", what, (unsigned long long)value, rc, mcmtl_last_error());
    [cb waitUntilCompleted];
    CHECK(cb.status == MTLCommandBufferStatusCompleted, "%s: command buffer status %ld (%s)", what, (long)cb.status,
          cb.error ? cb.error.localizedDescription.UTF8String : "-");
    return rc == MCMTL_OK && cb.status == MTLCommandBufferStatusCompleted;
}

static void upload_texture(id<MTLTexture> t, uint32_t level, uint32_t slice, uint32_t w, uint32_t h, uint32_t bpp, const void *data) {
    @autoreleasepool {
        id<MTLBuffer> staging = [g_dev->device newBufferWithBytes:data length:w * h * bpp options:MTLResourceStorageModeShared];
        id<MTLCommandBuffer> cb = [g_dev->queue commandBuffer];
        id<MTLBlitCommandEncoder> blit = [cb blitCommandEncoder];
        [blit copyFromBuffer:staging sourceOffset:0 sourceBytesPerRow:w * bpp sourceBytesPerImage:w * h * bpp
                  sourceSize:MTLSizeMake(w, h, 1) toTexture:t destinationSlice:slice destinationLevel:level
           destinationOrigin:MTLOriginMake(0, 0, 0)];
        [blit endEncoding];
        commit_and_wait(cb, "upload");
    }
}

static void encode_readback(id<MTLCommandBuffer> cb, id<MTLTexture> t, uint32_t level, uint32_t w, uint32_t h, uint32_t bpp, id<MTLBuffer> out) {
    id<MTLBlitCommandEncoder> blit = [cb blitCommandEncoder];
    [blit copyFromTexture:t sourceSlice:0 sourceLevel:level sourceOrigin:MTLOriginMake(0, 0, 0) sourceSize:MTLSizeMake(w, h, 1)
                 toBuffer:out destinationOffset:0 destinationBytesPerRow:w * bpp destinationBytesPerImage:w * h * bpp];
    [blit endEncoding];
}

static NSData *read_texture(id<MTLTexture> t, uint32_t level, uint32_t w, uint32_t h, uint32_t bpp) {
    @autoreleasepool {
        id<MTLBuffer> out = [g_dev->device newBufferWithLength:w * h * bpp options:MTLResourceStorageModeShared];
        id<MTLCommandBuffer> cb = [g_dev->queue commandBuffer];
        encode_readback(cb, t, level, w, h, bpp, out);
        commit_and_wait(cb, "readback");
        return [NSData dataWithBytes:out.contents length:w * h * bpp];
    }
}

static MTLRenderPassDescriptor *pass_descriptor(id<MTLTexture> color0, id<MTLTexture> color1, id<MTLTexture> depth, MTLClearColor clear0,
                                                MTLClearColor clear1, double clearDepth) {
    MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
    if (color0) {
        rp.colorAttachments[0].texture = color0;
        rp.colorAttachments[0].loadAction = MTLLoadActionClear;
        rp.colorAttachments[0].clearColor = clear0;
        rp.colorAttachments[0].storeAction = MTLStoreActionStore;
    }
    if (color1) {
        rp.colorAttachments[1].texture = color1;
        rp.colorAttachments[1].loadAction = MTLLoadActionClear;
        rp.colorAttachments[1].clearColor = clear1;
        rp.colorAttachments[1].storeAction = MTLStoreActionStore;
    }
    if (depth) {
        rp.depthAttachment.texture = depth;
        rp.depthAttachment.loadAction = MTLLoadActionClear;
        rp.depthAttachment.clearDepth = clearDepth;
        rp.depthAttachment.storeAction = MTLStoreActionStore;
    }
    return rp;
}

/* A plain pipeline over the real vanilla screenquad vertex shader (no vertex input). */
static mcmtl_pipeline_desc simple_pipeline_desc(mcmtl_handle vs, mcmtl_handle fs, uint32_t colorFormat, uint32_t precompile0) {
    mcmtl_pipeline_desc d;
    memset(&d, 0, sizeof d);
    d.structSize = sizeof d;
    d.vertexFunction = vs;
    d.fragmentFunction = fs;
    d.primitiveType = MTLPrimitiveTypeTriangle;
    d.cullMode = MTLCullModeNone;
    d.frontFacing = MTLWindingClockwise;
    d.depthCompare = MTLCompareFunctionAlways;
    d.colorTargetCount = colorFormat ? 1 : 0;
    d.colorTargets[0].pixelFormat = colorFormat;
    d.colorTargets[0].writeMask = MTLColorWriteMaskAll;
    d.precompileDepthFormatCount = 1;
    d.precompileDepthFormats[0] = precompile0;
    return d;
}

static mcmtl_handle make_pipeline(const mcmtl_pipeline_desc *d, const char *what) {
    mcmtl_handle h = 0;
    int32_t rc = mcmtl_pipeline_create(g_device, d, &h);
    CHECK(rc == MCMTL_OK && h, "pipeline_create(%s): %d %s", what, rc, mcmtl_last_error());
    return h;
}

static BOOL near_u8(uint8_t a, double expected, int tolerance) {
    double e = expected < 0 ? 0 : (expected > 255 ? 255 : expected);
    return fabs((double)a - e) <= tolerance;
}

static uint32_t message_count(void) {
    return __atomic_load_n(&g_dev->status->messageCount, __ATOMIC_ACQUIRE);
}

/* Polls (2 s max) until `condition` holds; completion handlers may hold the last reference briefly. */
static BOOL wait_until(BOOL (^condition)(void)) {
    for (int i = 0; i < 400; i++) {
        @autoreleasepool {
            if (condition()) return YES;
        }
        usleep(5000);
    }
    return NO;
}

/* True once the object behind a __weak variable has been deallocated (leak check). */
#define WAIT_NIL(weakVar) wait_until(^BOOL { return (weakVar) == nil; })

/* ---------------------------------------------------------------------------------------------- */
/* Device                                                                                           */
/* ---------------------------------------------------------------------------------------------- */

static void test_device_errors(void) {
    mcmtl_device_desc desc = {.structSize = sizeof desc, .framesInFlight = 2};
    mcmtl_handle h = 123;
    CHECK_RC(mcmtl_device_create(NULL, &h), MCMTL_E_INVALID_ARGUMENT, "device_create(NULL desc)");
    CHECK(h == 0, "outDevice must be zeroed on failure");
    CHECK_RC(mcmtl_device_create(&desc, NULL), MCMTL_E_INVALID_ARGUMENT, "device_create(NULL out)");
    desc.structSize = 8;
    CHECK_RC(mcmtl_device_create(&desc, &h), MCMTL_E_ABI_MISMATCH, "device_create(bad structSize)");
    desc.structSize = sizeof desc;
    desc.flags = MCMTL_DEVICE_FLAG_MTL4;
    CHECK_RC(mcmtl_device_create(&desc, &h), MCMTL_E_UNSUPPORTED, "device_create(MTL4)");
    CHECK(strstr(mcmtl_last_error(), "MTL4") != NULL, "MTL4 error message: %s", mcmtl_last_error());
    desc.flags = MCMTL_DEVICE_FLAG_UNTRACKED;
    CHECK_RC(mcmtl_device_create(&desc, &h), MCMTL_E_UNSUPPORTED, "device_create(UNTRACKED)");
    desc.flags = 1u << 9;
    CHECK_RC(mcmtl_device_create(&desc, &h), MCMTL_E_INVALID_ARGUMENT, "device_create(unknown flag)");
    CHECK(h == 0, "no handle on failure");
    CHECK_RC(mcmtl_device_destroy(0), MCMTL_E_INVALID_ARGUMENT, "device_destroy(0)");
    mcmtl_device_info info = {.structSize = sizeof info};
    CHECK_RC(mcmtl_device_get_info(0, &info), MCMTL_E_INVALID_ARGUMENT, "get_info(0)");

    /* A second device with VALIDATION, destroyed with work in flight: destroy waits, nothing leaks. */
    __weak MCMTLDevice *weakDevice = nil;
    @autoreleasepool {
        desc.flags = MCMTL_DEVICE_FLAG_VALIDATION;
        desc.label = "core_test aux";
        mcmtl_handle aux = 0;
        CHECK_RC(mcmtl_device_create(&desc, &aux), MCMTL_OK, "device_create(aux)");
        if (aux) {
            MCMTLDevice *dev = MCMTL_DEVICE(aux);
            weakDevice = dev;
            CHECK(dev->flags == MCMTL_DEVICE_FLAG_VALIDATION, "aux flags 0x%x", dev->flags);
            CHECK([dev->queue.label isEqualToString:@"core_test aux"], "queue label '%s'", dev->queue.label.UTF8String);
            id<MTLBuffer> scratch = [dev->device newBufferWithLength:1 << 20 options:MTLResourceStorageModePrivate];
            for (uint64_t v = 1; v <= 3; v++) {
                id<MTLCommandBuffer> cb = [dev->queue commandBuffer];
                id<MTLBlitCommandEncoder> blit = [cb blitCommandEncoder];
                [blit fillBuffer:scratch range:NSMakeRange(0, scratch.length) value:(uint8_t)v];
                [blit endEncoding];
                mcmtl_commit_signaled(dev, cb, v);
            }
            dev = nil;
            CHECK_RC(mcmtl_device_destroy(aux), MCMTL_OK, "device_destroy(aux with work in flight)");
        }
    }
    CHECK(WAIT_NIL(weakDevice), "destroyed device context was leaked");
}

static void test_device_info(void) {
    mcmtl_device_info info;
    memset(&info, 0, sizeof info);
    info.structSize = 4;
    CHECK_RC(mcmtl_device_get_info(g_device, &info), MCMTL_E_ABI_MISMATCH, "get_info(bad structSize)");
    info.structSize = sizeof info;
    CHECK_RC(mcmtl_device_get_info(g_device, &info), MCMTL_OK, "get_info");
    id<MTLDevice> d = g_dev->device;
    CHECK(info.structSize == sizeof info, "structSize kept");
    CHECK((info.familyMask & MCMTL_FAMILY_APPLE7) && info.familyMask == mcmtl_query_family_mask(d), "familyMask 0x%x", info.familyMask);
    CHECK(info.maxBufferLength == (uint64_t)d.maxBufferLength && info.maxBufferLength >= (1ull << 30), "maxBufferLength %llu",
          (unsigned long long)info.maxBufferLength);
    CHECK(info.recommendedMaxWorkingSetSize > 0, "recommendedMaxWorkingSetSize");
    CHECK(info.statusPage != 0 && info.statusPage == (uint64_t)(uintptr_t)g_dev->status, "statusPage 0x%llx",
          (unsigned long long)info.statusPage);
    CHECK(info.maxTextureSize2D == 16384 && info.maxColorAttachments == 8 && info.maxSamplerAnisotropy == 16 &&
              info.minUniformOffsetAlignment == 16, "limits %u %u %u %u", info.maxTextureSize2D, info.maxColorAttachments,
          info.maxSamplerAnisotropy, info.minUniformOffsetAlignment);
    uint32_t texelAlign = info.minTexelBufferAlignment;
    CHECK(texelAlign >= 1 && texelAlign <= 256 && (texelAlign & (texelAlign - 1)) == 0 &&
              texelAlign == [d minimumTextureBufferAlignmentForPixelFormat:MTLPixelFormatR8Sint] &&
              texelAlign == g_dev->minTexelBufferAlignment, "minTexelBufferAlignment %u", texelAlign);
    CHECK(info.mslLanguageVersion == 196610 && g_dev->mslLanguageVersion == 196610, "msl %u", info.mslLanguageVersion);
    CHECK(info.osMajor >= 26, "osMajor %u", info.osMajor);
    CHECK(((info.featureMask & MCMTL_FEATURE_UNIFIED_MEMORY) != 0) == d.hasUnifiedMemory, "unified memory bit");
    CHECK(((info.featureMask & MCMTL_FEATURE_TIMESTAMP_COUNTER_SET) != 0) == (g_dev->timestampCounterSet != nil), "timestamp bit");
    CHECK(((info.featureMask & MCMTL_FEATURE_COUNTER_SAMPLING_STAGE_BOUNDARY) != 0) ==
              [d supportsCounterSampling:MTLCounterSamplingPointAtStageBoundary], "stage boundary bit");
    CHECK(!(info.featureMask & MCMTL_FEATURE_MTL4_DECODER), "M1 never reports the MTL4 decoder");
    CHECK(info.featureMask == g_dev->featureMask && g_dev->familyMask == info.familyMask, "masks stored on the device");
    CHECK(strlen(info.name) > 0 && strcmp(info.name, d.name.UTF8String) == 0, "name '%s'", info.name);
    CHECK(strstr(info.driverInfo, "MSL 3.2") && strstr(info.driverInfo, "classic") && strstr(info.driverInfo, "macOS"), "driverInfo '%s'",
          info.driverInfo);
    CHECK(g_dev->flags == MCMTL_DEVICE_FLAG_DEBUG_LABELS, "flags 0x%x", g_dev->flags);
    printf("device: '%s' / '%s' families 0x%x features 0x%x texelAlign %u maxBuffer %llu MiB\n", info.name, info.driverInfo,
           info.familyMask, info.featureMask, texelAlign, (unsigned long long)(info.maxBufferLength >> 20));
}

static void test_timeline_and_timestamps(void) {
    CHECK(mcmtl_timeline_completed(0) == 0, "completed(0)");
    CHECK(mcmtl_timeline_completed(g_device) == 0, "fresh timeline");
    CHECK_RC(mcmtl_timeline_wait(g_device, 1, 1000000), MCMTL_E_INVALID_ARGUMENT, "wait for an uncommitted value");
    CHECK_RC(mcmtl_timeline_wait(g_device, 0, 0), MCMTL_OK, "wait(0)");
    CHECK_RC(mcmtl_timeline_wait(0, 1, 0), MCMTL_E_INVALID_ARGUMENT, "wait(device 0)");
    @autoreleasepool {
        commit_and_wait([g_dev->queue commandBuffer], "empty signal");
    }
    BOOL advanced = NO;
    for (int i = 0; i < 1000 && !advanced; i++) {
        advanced = mcmtl_timeline_completed(g_device) >= 1;
        if (!advanced) usleep(1000);
    }
    CHECK(advanced, "completed value must reach 1 (got %llu)", (unsigned long long)mcmtl_timeline_completed(g_device));

    uint64_t cpu = 1, gpu = 1;
    CHECK_RC(mcmtl_device_sample_timestamps(g_device, NULL, &gpu), MCMTL_E_INVALID_ARGUMENT, "sample_timestamps(NULL)");
    CHECK_RC(mcmtl_device_sample_timestamps(0, &cpu, &gpu), MCMTL_E_INVALID_ARGUMENT, "sample_timestamps(0)");
    CHECK(cpu == 0 && gpu == 0, "outputs zeroed on failure");
    uint64_t before = clock_gettime_nsec_np(CLOCK_UPTIME_RAW);
    CHECK_RC(mcmtl_device_sample_timestamps(g_device, &cpu, &gpu), MCMTL_OK, "sample_timestamps");
    uint64_t after = clock_gettime_nsec_np(CLOCK_UPTIME_RAW);
    CHECK(cpu + 1000000 >= before && cpu <= after + 1000000, "cpu timestamp %llu not in the ns domain [%llu, %llu]",
          (unsigned long long)cpu, (unsigned long long)before, (unsigned long long)after);
    CHECK(gpu > 0 && llabs((long long)(gpu - cpu)) < 1000000000LL, "gpu %llu vs cpu %llu", (unsigned long long)gpu, (unsigned long long)cpu);
    usleep(20000);
    uint64_t cpu2 = 0, gpu2 = 0;
    CHECK_RC(mcmtl_device_sample_timestamps(g_device, &cpu2, &gpu2), MCMTL_OK, "sample_timestamps 2");
    uint64_t delta = gpu2 - gpu;
    CHECK(delta >= 15000000ull && delta <= 500000000ull, "20 ms sleep -> gpu delta %llu ns", (unsigned long long)delta);
}

/* ---------------------------------------------------------------------------------------------- */
/* Buffers                                                                                          */
/* ---------------------------------------------------------------------------------------------- */

static void test_buffers(void) {
    mcmtl_buffer_result r;
    mcmtl_handle h = make_buffer(1000, USAGE_VERTEX | USAGE_MAP_WRITE, MTLResourceStorageModeShared, "core vertex buffer", &r);
    __weak id<MTLBuffer> weakBuffer = nil;
    @autoreleasepool {
        id<MTLBuffer> b = MCMTL_BUFFER(h);
        weakBuffer = b;
        CHECK(r.contents != 0 && r.contents == (uint64_t)(uintptr_t)b.contents, "contents");
        CHECK(r.gpuAddress != 0 && r.gpuAddress == b.gpuAddress, "gpuAddress");
        /* 4c123c2: every MTLBuffer is rounded up to 16 bytes (std140 vec3 tail). */
        CHECK(r.length == 1008 && b.length == 1008, "length %llu", (unsigned long long)r.length);
        CHECK(b.storageMode == MTLStorageModeShared && b.cpuCacheMode == MTLCPUCacheModeDefaultCache, "storage/cache");
        CHECK([b.label isEqualToString:@"core vertex buffer"], "label");
        memset((void *)(uintptr_t)r.contents, 0xAB, 1000);
        CHECK(((uint8_t *)b.contents)[999] == 0xAB, "contents writable");
    }
    CHECK_RC(mcmtl_release(g_device, h), MCMTL_OK, "release buffer");
    CHECK(WAIT_NIL(weakBuffer), "released buffer leaked");

    h = make_buffer(4096, USAGE_MAP_WRITE, MTLResourceCPUCacheModeWriteCombined, NULL, &r);
    @autoreleasepool {
        CHECK(MCMTL_BUFFER(h).cpuCacheMode == MTLCPUCacheModeWriteCombined && r.contents, "write-combined buffer");
    }
    mcmtl_release(g_device, h);

    uint32_t granule = mcmtl_core_state(g_dev)->maxTexelBufferAlignment;
    h = make_buffer(13, USAGE_TEXEL | USAGE_COPY_DST, 0, NULL, &r);
    CHECK(r.length >= 13 && r.length % granule == 0 && r.length % g_dev->minTexelBufferAlignment == 0,
          "texel buffer 13 B -> %llu (granule %u)", (unsigned long long)r.length, granule);
    mcmtl_release(g_device, h);
    h = make_buffer(4096, USAGE_TEXEL, 0, NULL, &r);
    CHECK(r.length == 4096, "aligned texel buffer keeps its size (%llu)", (unsigned long long)r.length);
    mcmtl_release(g_device, h);
    h = make_buffer(256, USAGE_UNIFORM, 0, NULL, &r);
    CHECK(r.length == 256, "non-texel buffers are not rounded");
    mcmtl_release(g_device, h);

    h = make_buffer(256, USAGE_COPY_DST, MTLResourceStorageModePrivate, NULL, &r);
    @autoreleasepool {
        CHECK(r.contents == 0 && r.gpuAddress != 0 && MCMTL_BUFFER(h).storageMode == MTLStorageModePrivate, "private buffer");
    }
    mcmtl_release(g_device, h);

    struct { uint64_t size; uint32_t options; int32_t expected; const char *what; } bad[] = {
        {0, 0, MCMTL_E_INVALID_ARGUMENT, "size 0"},
        {1ull << 60, 0, MCMTL_E_OUT_OF_MEMORY, "absurd size"},
        {(uint64_t)g_dev->device.maxBufferLength + 1, 0, MCMTL_E_OUT_OF_MEMORY, "maxBufferLength + 1"},
        {64, MTLResourceStorageModeMemoryless, MCMTL_E_UNSUPPORTED, "memoryless buffer"},
        {64, MTLResourceStorageModePrivate | MTLResourceCPUCacheModeWriteCombined, MCMTL_E_INVALID_ARGUMENT, "write-combined private"},
        {64, MTLResourceHazardTrackingModeUntracked, MCMTL_E_UNSUPPORTED, "untracked without the M2 flag"},
        {64, 1u << 12, MCMTL_E_INVALID_ARGUMENT, "unknown option bits"},
        {64, 4u << 4, MCMTL_E_INVALID_ARGUMENT, "invalid storage mode"},
    };
    for (size_t i = 0; i < sizeof bad / sizeof bad[0]; i++) {
        mcmtl_buffer_desc d = {.structSize = sizeof d, .size = bad[i].size, .resourceOptions = bad[i].options};
        mcmtl_buffer_result res = {.structSize = sizeof res, .handle = 77};
        CHECK_RC(mcmtl_buffer_create(g_device, &d, &res), bad[i].expected, bad[i].what);
        CHECK(res.handle == 0, "%s: no handle", bad[i].what);
    }
    mcmtl_buffer_desc d = {.structSize = 4, .size = 64};
    mcmtl_buffer_result res = {.structSize = sizeof res};
    CHECK_RC(mcmtl_buffer_create(g_device, &d, &res), MCMTL_E_ABI_MISMATCH, "buffer desc structSize");
    d.structSize = sizeof d;
    res.structSize = 8;
    CHECK_RC(mcmtl_buffer_create(g_device, &d, &res), MCMTL_E_ABI_MISMATCH, "buffer result structSize");
    res.structSize = sizeof res;
    CHECK_RC(mcmtl_buffer_create(g_device, NULL, &res), MCMTL_E_INVALID_ARGUMENT, "NULL buffer desc");
    CHECK_RC(mcmtl_buffer_create(0, &d, &res), MCMTL_E_INVALID_ARGUMENT, "buffer on device 0");
}

/* ---------------------------------------------------------------------------------------------- */
/* Textures and views                                                                               */
/* ---------------------------------------------------------------------------------------------- */

static void test_textures_and_views(void) {
    const uint32_t rtUsage = MTLTextureUsageShaderRead | MTLTextureUsageRenderTarget;
    mcmtl_texture_desc desc = {.structSize = sizeof desc, .pixelFormat = MTLPixelFormatRGBA8Unorm, .textureType = MTLTextureType2D,
                               .width = 64, .height = 32, .depth = 1, .mipLevels = 7, .arrayLength = 1, .textureUsage = rtUsage,
                               .resourceOptions = MTLResourceStorageModePrivate, .label = "core color"};
    mcmtl_object_result r = {.structSize = sizeof r};
    CHECK_RC(mcmtl_texture_create(g_device, &desc, &r), MCMTL_OK, "texture 2D");
    mcmtl_handle tex = r.handle;
    id<MTLTexture> t = MCMTL_TEXTURE(tex);
    CHECK(t.textureType == MTLTextureType2D && t.pixelFormat == MTLPixelFormatRGBA8Unorm && t.width == 64 && t.height == 32 &&
              t.mipmapLevelCount == 7 && t.arrayLength == 1 && t.sampleCount == 1, "2D texture properties");
    CHECK(t.usage == rtUsage && t.storageMode == MTLStorageModePrivate && [t.label isEqualToString:@"core color"], "usage/storage/label");
    CHECK(r.resourceId != 0 && r.resourceId == t.gpuResourceID._impl, "resourceId");

    desc.pixelFormat = MTLPixelFormatRGBA8Unorm;
    desc.textureType = MTLTextureTypeCube;
    desc.width = desc.height = 16;
    desc.mipLevels = 5;
    desc.textureUsage = MTLTextureUsageShaderRead;
    desc.label = NULL;
    CHECK_RC(mcmtl_texture_create(g_device, &desc, &r), MCMTL_OK, "cube texture");
    mcmtl_handle cube = r.handle;
    CHECK(MCMTL_TEXTURE(cube).textureType == MTLTextureTypeCube && MCMTL_TEXTURE(cube).arrayLength == 1 &&
              MCMTL_TEXTURE(cube).mipmapLevelCount == 5, "cube properties");

    mcmtl_handle depth = make_texture(MTLPixelFormatDepth32Float, MTLTextureType2D, 64, 32, 1, rtUsage, "core depth");
    mcmtl_handle depthStencil = make_texture(MTLPixelFormatDepth32Float_Stencil8, MTLTextureType2D, 8, 8, 1, MTLTextureUsageRenderTarget, NULL);
    mcmtl_handle unknownUsage = make_texture(MTLPixelFormatR8Unorm, MTLTextureType2D, 8, 8, 4, MTLTextureUsageUnknown, NULL);
    CHECK(MCMTL_TEXTURE(depth).pixelFormat == MTLPixelFormatDepth32Float, "depth format");
    mcmtl_texture_desc memoryless = {.structSize = sizeof memoryless, .pixelFormat = MTLPixelFormatDepth32Float, .textureType = 2,
                                     .width = 8, .height = 8, .depth = 1, .mipLevels = 1, .arrayLength = 1,
                                     .textureUsage = MTLTextureUsageRenderTarget, .resourceOptions = MTLResourceStorageModeMemoryless};
    CHECK_RC(mcmtl_texture_create(g_device, &memoryless, &r), MCMTL_OK, "memoryless depth render target");
    CHECK(MCMTL_TEXTURE(r.handle).storageMode == MTLStorageModeMemoryless, "memoryless storage");
    mcmtl_release(g_device, r.handle);

    struct { void (^patch)(mcmtl_texture_desc *); int32_t expected; const char *what; } bad[] = {
        {^(mcmtl_texture_desc *d) { d->pixelFormat = 0; }, MCMTL_E_INVALID_ARGUMENT, "pixel format 0"},
        {^(mcmtl_texture_desc *d) { d->textureType = MTLTextureType2DArray; }, MCMTL_E_UNSUPPORTED, "2D array"},
        {^(mcmtl_texture_desc *d) { d->textureType = MTLTextureType3D; }, MCMTL_E_UNSUPPORTED, "3D"},
        {^(mcmtl_texture_desc *d) { d->width = 0; }, MCMTL_E_INVALID_ARGUMENT, "width 0"},
        {^(mcmtl_texture_desc *d) { d->height = 16385; }, MCMTL_E_INVALID_ARGUMENT, "height 16385"},
        {^(mcmtl_texture_desc *d) { d->textureType = MTLTextureTypeCube; d->height = 8; }, MCMTL_E_INVALID_ARGUMENT, "non-square cube"},
        {^(mcmtl_texture_desc *d) { d->mipLevels = 8; }, MCMTL_E_INVALID_ARGUMENT, "too many mips"},
        {^(mcmtl_texture_desc *d) { d->mipLevels = 0; }, MCMTL_E_INVALID_ARGUMENT, "0 mips"},
        {^(mcmtl_texture_desc *d) { d->depth = 2; }, MCMTL_E_INVALID_ARGUMENT, "depth 2"},
        {^(mcmtl_texture_desc *d) { d->arrayLength = 2; }, MCMTL_E_INVALID_ARGUMENT, "arrayLength 2"},
        {^(mcmtl_texture_desc *d) { d->textureUsage = 0x100; }, MCMTL_E_INVALID_ARGUMENT, "unknown usage"},
        {^(mcmtl_texture_desc *d) { d->resourceOptions = MTLResourceStorageModeMemoryless; }, MCMTL_E_INVALID_ARGUMENT,
         "memoryless with ShaderRead"},
        {^(mcmtl_texture_desc *d) { d->resourceOptions |= MTLResourceHazardTrackingModeUntracked; }, MCMTL_E_UNSUPPORTED, "untracked"},
        {^(mcmtl_texture_desc *d) { d->structSize = 12; }, MCMTL_E_ABI_MISMATCH, "structSize"},
    };
    for (size_t i = 0; i < sizeof bad / sizeof bad[0]; i++) {
        mcmtl_texture_desc d = {.structSize = sizeof d, .pixelFormat = MTLPixelFormatRGBA8Unorm, .textureType = 2, .width = 64, .height = 32,
                                .depth = 1, .mipLevels = 1, .arrayLength = 1, .textureUsage = rtUsage,
                                .resourceOptions = MTLResourceStorageModePrivate};
        bad[i].patch(&d);
        mcmtl_object_result res = {.structSize = sizeof res, .handle = 5};
        CHECK_RC(mcmtl_texture_create(g_device, &d, &res), bad[i].expected, bad[i].what);
        CHECK(res.handle == 0, "%s: no handle", bad[i].what);
    }
    if (!g_dev->device.depth24Stencil8PixelFormatSupported) {
        mcmtl_texture_desc d = {.structSize = sizeof d, .pixelFormat = MTLPixelFormatDepth24Unorm_Stencil8, .textureType = 2, .width = 8,
                                .height = 8, .depth = 1, .mipLevels = 1, .arrayLength = 1, .textureUsage = MTLTextureUsageRenderTarget,
                                .resourceOptions = MTLResourceStorageModePrivate};
        mcmtl_object_result res = {.structSize = sizeof res};
        CHECK_RC(mcmtl_texture_create(g_device, &d, &res), MCMTL_E_UNSUPPORTED, "Depth24Unorm_Stencil8 on Apple silicon");
    }
    mcmtl_object_result smallResult = {.structSize = 3};
    CHECK_RC(mcmtl_texture_create(g_device, &desc, &smallResult), MCMTL_E_ABI_MISMATCH, "texture result structSize");

    /* ---- views ---- */
    mcmtl_texture_view_desc vd = {.structSize = sizeof vd, .pixelFormat = MTLPixelFormatRGBA8Unorm, .textureType = MTLTextureType2D,
                                  .baseLevel = 1, .levelCount = 3, .baseSlice = 0, .sliceCount = 1, .texture = tex};
    CHECK_RC(mcmtl_texture_view_create(g_device, &vd, &r), MCMTL_OK, "partial 2D view");
    mcmtl_handle view = r.handle;
    id<MTLTexture> v = MCMTL_TEXTURE(view);
    CHECK(v.textureType == MTLTextureType2D && v.mipmapLevelCount == 3 && v.parentTexture == t && v.parentRelativeLevel == 1 &&
              v.width == 32 && v.height == 16 && v.pixelFormat == MTLPixelFormatRGBA8Unorm, "2D view properties");
    CHECK(r.resourceId != 0, "view resourceId");

    mcmtl_texture_view_desc cd = {.structSize = sizeof cd, .pixelFormat = MTLPixelFormatRGBA8Unorm, .textureType = MTLTextureTypeCube,
                                  .baseLevel = 2, .levelCount = 2, .baseSlice = 0, .sliceCount = 6, .texture = cube};
    CHECK_RC(mcmtl_texture_view_create(g_device, &cd, &r), MCMTL_OK, "partial cube view");
    CHECK(MCMTL_TEXTURE(r.handle).textureType == MTLTextureTypeCube && MCMTL_TEXTURE(r.handle).mipmapLevelCount == 2 &&
              MCMTL_TEXTURE(r.handle).width == 4, "cube view keeps the cube type");
    mcmtl_release(g_device, r.handle);
    mcmtl_texture_view_desc defaults = {.structSize = sizeof defaults, .baseLevel = 6, .levelCount = 1, .texture = tex};
    CHECK_RC(mcmtl_texture_view_create(g_device, &defaults, &r), MCMTL_OK, "view with default format/type/slices");
    CHECK(MCMTL_TEXTURE(r.handle).width == 1 && MCMTL_TEXTURE(r.handle).height == 1, "smallest mip view");
    mcmtl_release(g_device, r.handle);
    mcmtl_texture_view_desc face = {.structSize = sizeof face, .textureType = MTLTextureType2D, .baseLevel = 0, .levelCount = 5,
                                    .baseSlice = 3, .sliceCount = 1, .texture = cube};
    CHECK_RC(mcmtl_texture_view_create(g_device, &face, &r), MCMTL_OK, "2D view of a cube face");
    CHECK(MCMTL_TEXTURE(r.handle).textureType == MTLTextureType2D && MCMTL_TEXTURE(r.handle).parentRelativeSlice == 3, "cube face view");
    mcmtl_release(g_device, r.handle);

    struct { mcmtl_texture_view_desc d; int32_t expected; const char *what; } badViews[] = {
        {{.structSize = sizeof(mcmtl_texture_view_desc), .levelCount = 0, .texture = tex}, MCMTL_E_INVALID_ARGUMENT, "levelCount 0"},
        {{.structSize = sizeof(mcmtl_texture_view_desc), .baseLevel = 7, .levelCount = 1, .texture = tex}, MCMTL_E_INVALID_ARGUMENT, "baseLevel 7"},
        {{.structSize = sizeof(mcmtl_texture_view_desc), .baseLevel = 5, .levelCount = 3, .texture = tex}, MCMTL_E_INVALID_ARGUMENT, "5 + 3 > 7"},
        {{.structSize = sizeof(mcmtl_texture_view_desc), .pixelFormat = MTLPixelFormatBGRA8Unorm, .levelCount = 1, .texture = tex},
         MCMTL_E_INVALID_ARGUMENT, "format change without PixelFormatView"},
        {{.structSize = sizeof(mcmtl_texture_view_desc), .textureType = MTLTextureTypeCube, .levelCount = 1, .sliceCount = 1, .texture = cube},
         MCMTL_E_INVALID_ARGUMENT, "cube view with 1 slice"},
        {{.structSize = sizeof(mcmtl_texture_view_desc), .textureType = MTLTextureType2D, .levelCount = 1, .sliceCount = 2, .texture = tex},
         MCMTL_E_INVALID_ARGUMENT, "2 slices of a 2D texture"},
        {{.structSize = sizeof(mcmtl_texture_view_desc), .textureType = MTLTextureTypeCube, .levelCount = 1, .texture = tex},
         MCMTL_E_INVALID_ARGUMENT, "cube view of a 2D texture"},
        {{.structSize = sizeof(mcmtl_texture_view_desc), .levelCount = 1, .texture = 0}, MCMTL_E_INVALID_ARGUMENT, "texture 0"},
        {{.structSize = 7, .levelCount = 1, .texture = tex}, MCMTL_E_ABI_MISMATCH, "structSize"},
    };
    for (size_t i = 0; i < sizeof badViews / sizeof badViews[0]; i++) {
        mcmtl_object_result res = {.structSize = sizeof res, .handle = 9};
        CHECK_RC(mcmtl_texture_view_create(g_device, &badViews[i].d, &res), badViews[i].expected, badViews[i].what);
        CHECK(res.handle == 0, "%s: no handle", badViews[i].what);
    }

    /* Content: the view's level 0/1 must be the parent's levels 1/2. */
    uint32_t mip1[32 * 16], mip2[16 * 8];
    for (size_t i = 0; i < 32 * 16; i++) mip1[i] = 0xFF112233u;
    for (size_t i = 0; i < 16 * 8; i++) mip2[i] = 0xFF445566u;
    upload_texture(t, 1, 0, 32, 16, 4, mip1);
    upload_texture(t, 2, 0, 16, 8, 4, mip2);
    NSData *l0 = read_texture(v, 0, 32, 16, 4);
    NSData *l1 = read_texture(v, 1, 16, 8, 4);
    CHECK(((const uint32_t *)l0.bytes)[0] == 0xFF112233u && ((const uint32_t *)l0.bytes)[32 * 16 - 1] == 0xFF112233u,
          "view level 0 = parent level 1 (got 0x%08x)", ((const uint32_t *)l0.bytes)[0]);
    CHECK(((const uint32_t *)l1.bytes)[5] == 0xFF445566u, "view level 1 = parent level 2 (got 0x%08x)", ((const uint32_t *)l1.bytes)[5]);

    /* A view keeps its parent alive after the parent handle is released. */
    __weak id<MTLTexture> weakParent = nil;
    @autoreleasepool {
        weakParent = t;
    }
    t = nil;
    v = nil;
    mcmtl_release(g_device, tex);
    @autoreleasepool {
        CHECK(weakParent != nil, "view must retain its parent texture");
    }
    mcmtl_release(g_device, view);
    CHECK(WAIT_NIL(weakParent), "parent released with its last view");
    mcmtl_handle rest[] = {cube, depth, depthStencil, unknownUsage};
    CHECK_RC(mcmtl_release_batch(g_device, rest, 4), MCMTL_OK, "release textures");
}

/* ---------------------------------------------------------------------------------------------- */
/* Texel views: created by I1, read by a real draw                                                  */
/* ---------------------------------------------------------------------------------------------- */

static const char TEXEL_FS[] =
    "#include <metal_stdlib>\n"
    "#include <simd/simd.h>\n"
    "using namespace metal;\n"
    "struct main0_out { int4 fragValue [[color(0)]]; };\n"
    "fragment main0_out main0(float4 gl_FragCoord [[position]], texture_buffer<int> CloudFaces [[texture(3)]])\n"
    "{\n"
    "    main0_out out = {};\n"
    "    out.fragValue = int4(CloudFaces.read(uint(int(gl_FragCoord.x))).x, 7, 0, 1);\n"
    "    return out;\n"
    "}\n";

static void test_texel_views(mcmtl_handle screenquad) {
    mcmtl_buffer_result br;
    mcmtl_handle buffer = make_buffer(64, USAGE_TEXEL | USAGE_MAP_WRITE, 0, "core texel buffer", &br);
    int8_t *bytes = (int8_t *)(uintptr_t)br.contents;
    for (int i = 0; i < 64; i++) bytes[i] = (int8_t)(i * 7 - 100);

    mcmtl_texel_view_desc d = {.structSize = sizeof d, .pixelFormat = MTLPixelFormatR8Sint, .bytesPerTexel = 1, .buffer = buffer,
                               .offset = 0, .length = 64};
    mcmtl_object_result r = {.structSize = sizeof r};
    CHECK_RC(mcmtl_texel_view_create(g_device, &d, &r), MCMTL_OK, "R8Sint texel view");
    mcmtl_handle view = r.handle;
    id<MTLTexture> v = MCMTL_TEXTURE(view);
    CHECK(v.textureType == MTLTextureTypeTextureBuffer && v.width == 64 && v.buffer == MCMTL_BUFFER(buffer) && v.bufferOffset == 0 &&
              v.bufferBytesPerRow >= 64 && v.bufferBytesPerRow % g_dev->minTexelBufferAlignment == 0 &&
              v.pixelFormat == MTLPixelFormatR8Sint && (v.usage & MTLTextureUsageShaderRead), "texel view properties (bytesPerRow %lu)",
          (unsigned long)v.bufferBytesPerRow);
    CHECK([v.label isEqualToString:@"core texel buffer"], "texel view label");

    d.pixelFormat = MTLPixelFormatRGBA8Uint;
    d.bytesPerTexel = 4;
    CHECK_RC(mcmtl_texel_view_create(g_device, &d, &r), MCMTL_OK, "RGBA8Uint texel view");
    CHECK(MCMTL_TEXTURE(r.handle).width == 16, "RGBA8Uint width");
    mcmtl_release(g_device, r.handle);

    mcmtl_handle odd = make_buffer(13, USAGE_UNIFORM, 0, NULL, NULL);
    mcmtl_texel_view_desc oddDesc = {.structSize = sizeof oddDesc, .pixelFormat = MTLPixelFormatR8Sint, .bytesPerTexel = 1, .buffer = odd,
                                     .length = 13};
    CHECK_RC(mcmtl_texel_view_create(g_device, &oddDesc, &r), MCMTL_OK, "texel view over an unrounded buffer");
    CHECK(MCMTL_TEXTURE(r.handle).width == 13, "unrounded width");
    mcmtl_release(g_device, r.handle);
    mcmtl_release(g_device, odd);

    struct { mcmtl_texel_view_desc d; int32_t expected; const char *what; } bad[] = {
        {{.structSize = sizeof(mcmtl_texel_view_desc), .pixelFormat = 0, .bytesPerTexel = 1, .buffer = buffer, .length = 64},
         MCMTL_E_UNSUPPORTED, "format 0"},
        {{.structSize = sizeof(mcmtl_texel_view_desc), .pixelFormat = MTLPixelFormatDepth32Float, .bytesPerTexel = 4, .buffer = buffer,
          .length = 64}, MCMTL_E_UNSUPPORTED, "depth format"},
        {{.structSize = sizeof(mcmtl_texel_view_desc), .pixelFormat = MTLPixelFormatR8Sint, .bytesPerTexel = 0, .buffer = buffer,
          .length = 64}, MCMTL_E_INVALID_ARGUMENT, "bytesPerTexel 0"},
        {{.structSize = sizeof(mcmtl_texel_view_desc), .pixelFormat = MTLPixelFormatR8Sint, .bytesPerTexel = 1, .buffer = buffer,
          .length = 0}, MCMTL_E_INVALID_ARGUMENT, "length 0"},
        {{.structSize = sizeof(mcmtl_texel_view_desc), .pixelFormat = MTLPixelFormatR8Sint, .bytesPerTexel = 1, .buffer = buffer,
          .offset = 16, .length = 64}, MCMTL_E_INVALID_ARGUMENT, "range past the end"},
        {{.structSize = sizeof(mcmtl_texel_view_desc), .pixelFormat = MTLPixelFormatR8Sint, .bytesPerTexel = 1, .buffer = buffer,
          .offset = 4, .length = 32}, MCMTL_E_INVALID_ARGUMENT, "misaligned offset"},
        {{.structSize = sizeof(mcmtl_texel_view_desc), .pixelFormat = MTLPixelFormatR8Sint, .bytesPerTexel = 1, .buffer = 0, .length = 64},
         MCMTL_E_INVALID_ARGUMENT, "buffer 0"},
        {{.structSize = 9, .pixelFormat = MTLPixelFormatR8Sint, .bytesPerTexel = 1, .buffer = buffer, .length = 64}, MCMTL_E_ABI_MISMATCH,
         "structSize"},
    };
    for (size_t i = 0; i < sizeof bad / sizeof bad[0]; i++) {
        mcmtl_object_result res = {.structSize = sizeof res, .handle = 3};
        CHECK_RC(mcmtl_texel_view_create(g_device, &bad[i].d, &res), bad[i].expected, bad[i].what);
        CHECK(res.handle == 0, "%s: no handle", bad[i].what);
    }

    /* Draw: every pixel x of a 64x1 R32Sint target reads texel x through the view. */
    mcmtl_handle fs = make_shader(MCMTL_STAGE_FRAGMENT, TEXEL_FS, "core texel fs");
    mcmtl_pipeline_desc pd = simple_pipeline_desc(screenquad, fs, MTLPixelFormatR32Sint, 0);
    pd.textureMask[1] = pd.texelBufferMask[1] = 1u << 3;
    mcmtl_handle pipeline = make_pipeline(&pd, "texel");
    mcmtl_handle target = make_texture(MTLPixelFormatR32Sint, MTLTextureType2D, 64, 1, 1, MTLTextureUsageRenderTarget, "texel target");
    if (pipeline && target) {
        MCMTLPipeline *p = MCMTL_PIPELINE(pipeline);
        id<MTLRenderPipelineState> pso = mcmtl_pipeline_find_variant(p, mcmtl_variant_key(1, 0));
        CHECK(pso != nil, "texel pipeline precompiled (mask 1, no depth)");
        @autoreleasepool {
            id<MTLCommandBuffer> cb = [g_dev->queue commandBuffer];
            id<MTLRenderCommandEncoder> enc = [cb renderCommandEncoderWithDescriptor:pass_descriptor(MCMTL_TEXTURE(target), nil, nil,
                                                                                              MTLClearColorMake(0, 0, 0, 0),
                                                                                              MTLClearColorMake(0, 0, 0, 0), 0)];
            [enc setRenderPipelineState:pso];
            [enc setFragmentTexture:v atIndex:3];
            [enc drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
            [enc endEncoding];
            commit_and_wait(cb, "texel draw");
        }
        NSData *out = read_texture(MCMTL_TEXTURE(target), 0, 64, 1, 4);
        const int32_t *values = out.bytes;
        int mismatches = 0;
        for (int i = 0; i < 64; i++) {
            if (values[i] != (int32_t)(int8_t)(i * 7 - 100)) mismatches++;
        }
        CHECK(mismatches == 0, "texel buffer read back through the view: %d mismatches (v[0] %d, v[63] %d)", mismatches, values[0], values[63]);
    }
    v = nil;
    mcmtl_handle all[] = {pipeline, fs, target, view, buffer};
    mcmtl_release_batch(g_device, all, 5);
}

/* ---------------------------------------------------------------------------------------------- */
/* Samplers: dedup + every field verified by sampling                                               */
/* ---------------------------------------------------------------------------------------------- */

static const char SAMPLE_FS[] =
    "#include <metal_stdlib>\n"
    "using namespace metal;\n"
    "struct Params { float4 uvLod; };\n"
    "struct main0_out { float4 fragColor [[color(0)]]; };\n"
    "fragment main0_out main0(constant Params& _p [[buffer(5)]], texture2d<float> Sampler0 [[texture(0)]], sampler Sampler0Smplr [[sampler(0)]])\n"
    "{\n"
    "    main0_out out = {};\n"
    "    out.fragColor = Sampler0.sample(Sampler0Smplr, _p.uvLod.xy, level(_p.uvLod.z));\n"
    "    return out;\n"
    "}\n";

static float sample_once(id<MTLRenderPipelineState> pso, id<MTLTexture> src, mcmtl_handle sampler, id<MTLTexture> target, float u, float lod) {
    @autoreleasepool {
        float params[4] = {u, 0.5f, lod, 0};
        id<MTLCommandBuffer> cb = [g_dev->queue commandBuffer];
        id<MTLRenderCommandEncoder> enc = [cb renderCommandEncoderWithDescriptor:pass_descriptor(target, nil, nil,
                                                                                          MTLClearColorMake(-1, -1, -1, -1),
                                                                                          MTLClearColorMake(0, 0, 0, 0), 0)];
        [enc setRenderPipelineState:pso];
        [enc setFragmentTexture:src atIndex:0];
        [enc setFragmentSamplerState:MCMTL_SAMPLER(sampler) atIndex:0];
        [enc setFragmentBytes:params length:sizeof params atIndex:5];
        [enc drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
        [enc endEncoding];
        commit_and_wait(cb, "sample");
        NSData *out = read_texture(target, 0, 1, 1, 16);
        return ((const float *)out.bytes)[0];
    }
}

static void test_samplers(mcmtl_handle screenquad) {
    /* Dedup across every combination vanilla can produce (address x filter x mip x anisotropy x LOD). */
    const uint32_t addresses[] = {MTLSamplerAddressModeRepeat, MTLSamplerAddressModeClampToEdge};
    const uint32_t filters[] = {MTLSamplerMinMagFilterNearest, MTLSamplerMinMagFilterLinear};
    const uint32_t mips[] = {MTLSamplerMipFilterNotMipmapped, MTLSamplerMipFilterNearest, MTLSamplerMipFilterLinear};
    const uint32_t anisos[] = {1, 16};
    const float lods[] = {0.25f, 4.0f, FLT_MAX};
    mcmtl_handle handles[2][2][2][3][2][3];
    int created = 0;
    for (int a = 0; a < 2; a++) for (int mi = 0; mi < 2; mi++) for (int ma = 0; ma < 2; ma++) for (int mp = 0; mp < 3; mp++)
        for (int an = 0; an < 2; an++) for (int l = 0; l < 3; l++) {
            mcmtl_sampler_desc d = sampler_desc(addresses[a], filters[mi], mips[mp], anisos[an], 0.0f, lods[l]);
            d.tAddressMode = addresses[1 - a];
            d.magFilter = filters[ma];
            mcmtl_object_result r = {.structSize = sizeof r};
            int32_t rc = mcmtl_sampler_create(g_device, &d, &r);
            CHECK(rc == MCMTL_OK && r.handle && r.resourceId, "sampler combo a%d min%d mag%d mip%d aniso%u lod%g: %d %s", a, mi, ma, mp,
                  anisos[an], (double)lods[l], rc, mcmtl_last_error());
            handles[a][mi][ma][mp][an][l] = r.handle;
            created++;
        }
    NSMutableSet *unique = [NSMutableSet set];
    for (int i = 0; i < created; i++) [unique addObject:MCMTL_OBJ(((mcmtl_handle *)handles)[i])];
    CHECK((int)unique.count == created, "%d distinct descriptors -> %lu distinct states", created, (unsigned long)unique.count);
    mcmtl_sampler_desc same = sampler_desc(addresses[1], filters[1], mips[2], 16, 0.0f, FLT_MAX);
    same.tAddressMode = addresses[0];
    mcmtl_handle again = make_sampler(&same);
    CHECK(again == handles[1][1][1][2][1][2], "identical descriptor must return the cached state");
    unique = nil;
    mcmtl_release(g_device, again);
    mcmtl_release_batch(g_device, (const mcmtl_handle *)handles, (uint32_t)created);
    mcmtl_sampler_desc noArg = sampler_desc(MTLSamplerAddressModeRepeat, 0, 0, 1, 0, FLT_MAX);
    noArg.flags = 0;
    mcmtl_object_result nr = {.structSize = sizeof nr};
    CHECK_RC(mcmtl_sampler_create(g_device, &noArg, &nr), MCMTL_OK, "sampler without argument-buffer support");
    CHECK(nr.resourceId == 0, "no resourceId without SUPPORT_ARGUMENT_BUFFERS");
    mcmtl_release(g_device, nr.handle);

    struct { void (^patch)(mcmtl_sampler_desc *); const char *what; int32_t expected; } bad[] = {
        {^(mcmtl_sampler_desc *d) { d->maxAnisotropy = 0; }, "anisotropy 0", MCMTL_E_INVALID_ARGUMENT},
        {^(mcmtl_sampler_desc *d) { d->maxAnisotropy = 17; }, "anisotropy 17", MCMTL_E_INVALID_ARGUMENT},
        {^(mcmtl_sampler_desc *d) { d->lodMinClamp = -1; }, "negative lodMin", MCMTL_E_INVALID_ARGUMENT},
        {^(mcmtl_sampler_desc *d) { d->lodMinClamp = 3; d->lodMaxClamp = 2; }, "lodMax < lodMin", MCMTL_E_INVALID_ARGUMENT},
        {^(mcmtl_sampler_desc *d) { d->lodMaxClamp = NAN; }, "NaN lod", MCMTL_E_INVALID_ARGUMENT},
        {^(mcmtl_sampler_desc *d) { d->sAddressMode = 6; }, "address 6", MCMTL_E_INVALID_ARGUMENT},
        {^(mcmtl_sampler_desc *d) { d->minFilter = 2; }, "min filter 2", MCMTL_E_INVALID_ARGUMENT},
        {^(mcmtl_sampler_desc *d) { d->mipFilter = 3; }, "mip filter 3", MCMTL_E_INVALID_ARGUMENT},
        {^(mcmtl_sampler_desc *d) { d->compareFunction = 8; }, "compare 8", MCMTL_E_INVALID_ARGUMENT},
        {^(mcmtl_sampler_desc *d) { d->flags = 2; }, "unknown flag", MCMTL_E_INVALID_ARGUMENT},
        {^(mcmtl_sampler_desc *d) { d->structSize = 44; }, "structSize", MCMTL_E_ABI_MISMATCH},
    };
    for (size_t i = 0; i < sizeof bad / sizeof bad[0]; i++) {
        mcmtl_sampler_desc d = sampler_desc(MTLSamplerAddressModeRepeat, 1, 2, 4, 0, FLT_MAX);
        bad[i].patch(&d);
        mcmtl_object_result r = {.structSize = sizeof r, .handle = 1};
        CHECK_RC(mcmtl_sampler_create(g_device, &d, &r), bad[i].expected, bad[i].what);
        CHECK(r.handle == 0, "%s: no handle", bad[i].what);
    }

    /* Semantics: a 2x1 texture (texel 0 = 0, texel 1 = 1) and a 2x2/1x1 mip chain (level 0 = 1, level 1 = 0). */
    mcmtl_handle fs = make_shader(MCMTL_STAGE_FRAGMENT, SAMPLE_FS, "core sample fs");
    mcmtl_pipeline_desc pd = simple_pipeline_desc(screenquad, fs, MTLPixelFormatRGBA32Float, 0);
    pd.bufferMask[1] = 1u << 5;
    pd.textureMask[1] = pd.samplerMask[1] = 1u;
    mcmtl_handle pipeline = make_pipeline(&pd, "sample");
    mcmtl_handle strip = make_texture(MTLPixelFormatRGBA8Unorm, MTLTextureType2D, 2, 1, 1, MTLTextureUsageShaderRead, "strip");
    mcmtl_handle chain = make_texture(MTLPixelFormatRGBA8Unorm, MTLTextureType2D, 2, 2, 2, MTLTextureUsageShaderRead, "chain");
    mcmtl_handle target = make_texture(MTLPixelFormatRGBA32Float, MTLTextureType2D, 1, 1, 1, MTLTextureUsageRenderTarget, "sample target");
    if (!pipeline || !strip || !chain || !target) return;
    const uint32_t stripTexels[2] = {0xFF000000u, 0xFFFFFFFFu};
    const uint32_t chain0[4] = {0xFFFFFFFFu, 0xFFFFFFFFu, 0xFFFFFFFFu, 0xFFFFFFFFu};
    const uint32_t chain1[1] = {0xFF000000u};
    upload_texture(MCMTL_TEXTURE(strip), 0, 0, 2, 1, 4, stripTexels);
    upload_texture(MCMTL_TEXTURE(chain), 0, 0, 2, 2, 4, chain0);
    upload_texture(MCMTL_TEXTURE(chain), 1, 0, 1, 1, 4, chain1);
    id<MTLRenderPipelineState> pso = mcmtl_pipeline_find_variant(MCMTL_PIPELINE(pipeline), mcmtl_variant_key(1, 0));
    const uint32_t clamp = MTLSamplerAddressModeClampToEdge, repeat = MTLSamplerAddressModeRepeat;
    const uint32_t nearest = MTLSamplerMinMagFilterNearest, linear = MTLSamplerMinMagFilterLinear;
    struct { mcmtl_sampler_desc d; BOOL useChain; float u, lod, expected; const char *what; } cases[] = {
        {sampler_desc(clamp, nearest, 0, 1, 0, FLT_MAX), NO, 0.30f, 0, 0.0f, "nearest picks texel 0"},
        {sampler_desc(clamp, nearest, 0, 1, 0, FLT_MAX), NO, 0.70f, 0, 1.0f, "nearest picks texel 1"},
        {sampler_desc(clamp, linear, 0, 1, 0, FLT_MAX), NO, 0.50f, 0, 0.5f, "linear blends"},
        {sampler_desc(clamp, linear, 0, 1, 0, FLT_MAX), NO, 1.00f, 0, 1.0f, "clamp-to-edge at u = 1"},
        {sampler_desc(repeat, linear, 0, 1, 0, FLT_MAX), NO, 1.00f, 0, 0.5f, "repeat wraps at u = 1"},
        {sampler_desc(repeat, nearest, 0, 1, 0, FLT_MAX), NO, 1.25f, 0, 0.0f, "repeat u = 1.25 -> texel 0"},
        {sampler_desc(clamp, nearest, 0, 1, 0, FLT_MAX), NO, 1.25f, 0, 1.0f, "clamp u = 1.25 -> texel 1"},
        {sampler_desc(clamp, linear, 0, 16, 0, FLT_MAX), NO, 0.50f, 0, 0.5f, "anisotropy 16 sampler"},
        {sampler_desc(clamp, linear, MTLSamplerMipFilterLinear, 1, 0, FLT_MAX), YES, 0.5f, 1, 0.0f, "mip level 1"},
        {sampler_desc(clamp, linear, MTLSamplerMipFilterLinear, 1, 0, 0.0f), YES, 0.5f, 1, 1.0f, "lodMaxClamp 0 keeps level 0"},
        {sampler_desc(clamp, linear, MTLSamplerMipFilterNotMipmapped, 1, 0, FLT_MAX), YES, 0.5f, 1, 1.0f, "not mipmapped -> base level"},
        {sampler_desc(clamp, linear, MTLSamplerMipFilterLinear, 1, 1.0f, FLT_MAX), YES, 0.5f, 0, 0.0f, "lodMinClamp 1 forces level 1"},
        {sampler_desc(clamp, linear, MTLSamplerMipFilterLinear, 1, 0, FLT_MAX), YES, 0.5f, 0.5f, 0.5f, "linear mip blend"},
    };
    for (size_t i = 0; i < sizeof cases / sizeof cases[0]; i++) {
        mcmtl_handle s = make_sampler(&cases[i].d);
        float value = sample_once(pso, MCMTL_TEXTURE(cases[i].useChain ? chain : strip), s, MCMTL_TEXTURE(target), cases[i].u, cases[i].lod);
        CHECK(fabsf(value - cases[i].expected) < 0.02f, "sampler semantics '%s': got %f expected %f", cases[i].what, (double)value,
              (double)cases[i].expected);
        mcmtl_release(g_device, s);
    }
    mcmtl_handle all[] = {pipeline, fs, strip, chain, target};
    mcmtl_release_batch(g_device, all, 5);
}

/* ---------------------------------------------------------------------------------------------- */
/* Timestamp query pools                                                                            */
/* ---------------------------------------------------------------------------------------------- */

static const char CONSTANT_FS[] =
    "#include <metal_stdlib>\n"
    "using namespace metal;\n"
    "struct main0_out { float4 fragColor [[color(0)]]; };\n"
    "fragment main0_out main0() { main0_out out = {}; out.fragColor = float4(0.25, 0.5, 0.75, 1.0); return out; }\n";

static void test_query_pools(mcmtl_handle screenquad) {
    mcmtl_handle pool = 7;
    CHECK_RC(mcmtl_query_pool_create(g_device, 0, &pool), MCMTL_E_INVALID_ARGUMENT, "query pool of 0");
    CHECK(pool == 0, "no handle");
    CHECK_RC(mcmtl_query_pool_create(g_device, 4, NULL), MCMTL_E_INVALID_ARGUMENT, "query pool NULL out");
    CHECK_RC(mcmtl_query_pool_create(g_device, 1024, &pool), MCMTL_OK, "query pool 1024 (Tracy)");
    MCMTLQueryPool *p = MCMTL_QUERY_POOL(pool);
    CHECK(p->count == 1024 && p->owner == g_dev, "pool fields");
    CHECK((p->sampleBuffer != nil) == (g_dev->timestampCounterSet != nil), "sample buffer iff the GPU has timestamps");
    uint64_t *values = malloc(1024 * sizeof(uint64_t));
    CHECK_RC(mcmtl_query_pool_resolve(g_device, pool, 0, 1024, values), MCMTL_OK, "resolve fresh pool");
    int available = 0;
    for (int i = 0; i < 1024; i++) available += values[i] != MCMTL_TIMESTAMP_UNAVAILABLE;
    CHECK(available == 0, "fresh pool: %d samples available", available);
    CHECK_RC(mcmtl_query_pool_resolve(g_device, pool, 1024, 1, values), MCMTL_E_INVALID_ARGUMENT, "resolve past the end");
    CHECK_RC(mcmtl_query_pool_resolve(g_device, pool, 1000, 100, values), MCMTL_E_INVALID_ARGUMENT, "resolve overlapping the end");
    CHECK_RC(mcmtl_query_pool_resolve(g_device, pool, 0, 0, NULL), MCMTL_OK, "resolve 0 samples");
    CHECK_RC(mcmtl_query_pool_resolve(g_device, pool, 0, 4, NULL), MCMTL_E_INVALID_ARGUMENT, "resolve into NULL");
    CHECK_RC(mcmtl_query_pool_resolve(g_device, 0, 0, 4, values), MCMTL_E_INVALID_ARGUMENT, "resolve pool 0");

    uint32_t messagesBefore = message_count();
    mcmtl_handle big = 0;
    CHECK_RC(mcmtl_query_pool_create(g_device, 8192, &big), MCMTL_OK, "query pool beyond the counter-buffer limit");
    CHECK(MCMTL_QUERY_POOL(big)->sampleBuffer == nil && message_count() > messagesBefore, "oversized pool degrades with a message");
    CHECK_RC(mcmtl_query_pool_resolve(g_device, big, 8000, 4, values), MCMTL_OK, "resolve oversized pool");
    CHECK(values[0] == MCMTL_TIMESTAMP_UNAVAILABLE && values[3] == MCMTL_TIMESTAMP_UNAVAILABLE, "oversized pool samples unavailable");
    mcmtl_release(g_device, big);

    /* Real GPU samples at stage boundaries (classic sampling on Apple GPUs) resolve to nanoseconds. */
    if (p->sampleBuffer && (g_dev->featureMask & MCMTL_FEATURE_COUNTER_SAMPLING_STAGE_BOUNDARY)) {
        mcmtl_handle fs = make_shader(MCMTL_STAGE_FRAGMENT, CONSTANT_FS, "core constant fs");
        mcmtl_pipeline_desc pd = simple_pipeline_desc(screenquad, fs, MTLPixelFormatRGBA8Unorm, 0);
        mcmtl_handle pipeline = make_pipeline(&pd, "timestamps");
        mcmtl_handle target = make_texture(MTLPixelFormatRGBA8Unorm, MTLTextureType2D, 64, 64, 1, MTLTextureUsageRenderTarget, "ts target");
        uint64_t cpuBefore = 0, gpuBefore = 0, cpuAfter = 0, gpuAfter = 0;
        mcmtl_device_sample_timestamps(g_device, &cpuBefore, &gpuBefore);
        @autoreleasepool {
            MTLRenderPassDescriptor *rp = pass_descriptor(MCMTL_TEXTURE(target), nil, nil, MTLClearColorMake(0, 0, 0, 1),
                                                          MTLClearColorMake(0, 0, 0, 0), 0);
            rp.sampleBufferAttachments[0].sampleBuffer = p->sampleBuffer;
            rp.sampleBufferAttachments[0].startOfVertexSampleIndex = 3;
            rp.sampleBufferAttachments[0].endOfVertexSampleIndex = MTLCounterDontSample;
            rp.sampleBufferAttachments[0].startOfFragmentSampleIndex = MTLCounterDontSample;
            rp.sampleBufferAttachments[0].endOfFragmentSampleIndex = 4;
            id<MTLCommandBuffer> cb = [g_dev->queue commandBuffer];
            id<MTLRenderCommandEncoder> enc = [cb renderCommandEncoderWithDescriptor:rp];
            [enc setRenderPipelineState:mcmtl_pipeline_find_variant(MCMTL_PIPELINE(pipeline), mcmtl_variant_key(1, 0))];
            [enc drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
            [enc endEncoding];
            commit_and_wait(cb, "timestamp pass");
        }
        mcmtl_device_sample_timestamps(g_device, &cpuAfter, &gpuAfter);
        CHECK_RC(mcmtl_query_pool_resolve(g_device, pool, 0, 8, values), MCMTL_OK, "resolve written samples");
        CHECK(values[3] != MCMTL_TIMESTAMP_UNAVAILABLE && values[4] != MCMTL_TIMESTAMP_UNAVAILABLE, "samples 3 and 4 written");
        CHECK(values[0] == MCMTL_TIMESTAMP_UNAVAILABLE && values[2] == MCMTL_TIMESTAMP_UNAVAILABLE && values[5] == MCMTL_TIMESTAMP_UNAVAILABLE,
              "unwritten neighbours stay unavailable");
        CHECK(values[4] >= values[3], "end of fragment %llu >= start of vertex %llu", (unsigned long long)values[4],
              (unsigned long long)values[3]);
        CHECK(values[3] + 1000000000ull >= gpuBefore && values[4] <= gpuAfter + 1000000000ull,
              "samples [%llu, %llu] in the sampleTimestamps ns domain [%llu, %llu]", (unsigned long long)values[3],
              (unsigned long long)values[4], (unsigned long long)gpuBefore, (unsigned long long)gpuAfter);
        printf("timestamps: pass took %llu ns on the GPU\n", (unsigned long long)(values[4] - values[3]));
        mcmtl_handle all[] = {pipeline, fs, target};
        mcmtl_release_batch(g_device, all, 3);
    }
    free(values);
    __weak MCMTLQueryPool *weakPool = p;
    p = nil;
    mcmtl_release(g_device, pool);
    CHECK(WAIT_NIL(weakPool), "query pool leaked");
}

/* ---------------------------------------------------------------------------------------------- */
/* Shaders                                                                                          */
/* ---------------------------------------------------------------------------------------------- */

static const char VALID_VS_TEMPLATE[] =
    "#include <metal_stdlib>\n"
    "using namespace metal;\n"
    "struct main0_out { float4 gl_Position [[position]]; };\n"
    "// unique %d\n"
    "vertex main0_out main0(uint gl_VertexIndex [[vertex_id]]) { main0_out out = {}; out.gl_Position = float4(float(gl_VertexIndex), 0, 0, 1); return out; }\n";

static const char BAD_VS_TEMPLATE[] =
    "#include <metal_stdlib>\n"
    "using namespace metal;\n"
    "struct main0_out { float4 gl_Position [[position]]; };\n"
    "vertex main0_out main0(uint gl_VertexIndex [[vertex_id]]) { main0_out out = {}; out.gl_Position = core_bad_marker_%d; return out; }\n";

static void test_shaders(void) {
    mcmtl_handle h = 0;
    /* Real SPIRV-Cross output, source NOT NUL-terminated (sourceLength is authoritative). */
    size_t len = strlen(CORE_MSL_TERRAIN_VS);
    char *copy = malloc(len);
    memcpy(copy, CORE_MSL_TERRAIN_VS, len);
    CHECK_RC(shader_rc(MCMTL_STAGE_VERTEX, copy, len, "main0", 0, MTLLanguageVersion3_2, "core/terrain", &h), MCMTL_OK, "terrain vs");
    free(copy);
    if (h) {
        id<MTLFunction> f = MCMTL_FUNCTION(h);
        CHECK(f.functionType == MTLFunctionTypeVertex && [f.name isEqualToString:@"main0"] && [f.label isEqualToString:@"core/terrain"],
              "terrain vs function");
        CHECK(f.vertexAttributes.count == 6, "terrain vs has 6 vertex attributes (%lu)", (unsigned long)f.vertexAttributes.count);
        mcmtl_release(g_device, h);
    }
    const struct { uint32_t stage; const char *src; const char *what; } valid[] = {
        {MCMTL_STAGE_FRAGMENT, CORE_MSL_TERRAIN_FS, "terrain fs"},
        {MCMTL_STAGE_VERTEX, CORE_MSL_SCREENQUAD_VS, "screenquad vs"},
        {MCMTL_STAGE_FRAGMENT, CORE_MSL_BLIT_DEPTH_FS, "blit_depth fs"},
        {MCMTL_STAGE_VERTEX, CORE_MSL_CLOUDS_VS, "clouds vs"},
        {MCMTL_STAGE_FRAGMENT, CORE_MSL_CLOUDS_FS, "clouds fs"},
    };
    for (size_t i = 0; i < sizeof valid / sizeof valid[0]; i++) {
        CHECK_RC(shader_rc(valid[i].stage, valid[i].src, strlen(valid[i].src), "main0", 0, 0, NULL, &h), MCMTL_OK, valid[i].what);
        mcmtl_release(g_device, h);
    }
    CHECK_RC(shader_rc(MCMTL_STAGE_FRAGMENT, CORE_MSL_TERRAIN_FS, strlen(CORE_MSL_TERRAIN_FS), "main0",
                       MCMTL_SHADER_FLAG_PRESERVE_INVARIANCE | MCMTL_SHADER_FLAG_SAFE_MATH, MTLLanguageVersion3_1, "flags", &h),
             MCMTL_OK, "invariance + safe math + MSL 3.1");
    mcmtl_release(g_device, h);

    /* Failures. */
    const char *garbage = "this is not MSL { }";
    CHECK_RC(shader_rc(MCMTL_STAGE_VERTEX, garbage, strlen(garbage), "main0", 0, 0, "garbage", &h), MCMTL_E_COMPILE_FAILED, "garbage MSL");
    CHECK(h == 0 && strstr(mcmtl_last_error(), "garbage") && strlen(mcmtl_last_error()) > 40, "compile log in last error: %s",
          mcmtl_last_error());
    CHECK_RC(shader_rc(MCMTL_STAGE_VERTEX, CORE_MSL_SCREENQUAD_VS, strlen(CORE_MSL_SCREENQUAD_VS), "main1", 0, 0, NULL, &h),
             MCMTL_E_COMPILE_FAILED, "missing entry point");
    CHECK(strstr(mcmtl_last_error(), "main1") != NULL, "entry point named in the error");
    CHECK_RC(shader_rc(MCMTL_STAGE_VERTEX, CORE_MSL_TERRAIN_FS, strlen(CORE_MSL_TERRAIN_FS), "main0", 0, 0, NULL, &h),
             MCMTL_E_COMPILE_FAILED, "fragment source as a vertex shader");
    CHECK_RC(shader_rc(3, CORE_MSL_TERRAIN_FS, strlen(CORE_MSL_TERRAIN_FS), "main0", 0, 0, NULL, &h), MCMTL_E_INVALID_ARGUMENT, "stage 3");
    CHECK_RC(shader_rc(MCMTL_STAGE_VERTEX, CORE_MSL_TERRAIN_VS, 0, "main0", 0, 0, NULL, &h), MCMTL_E_INVALID_ARGUMENT, "length 0");
    CHECK_RC(shader_rc(MCMTL_STAGE_VERTEX, NULL, 10, "main0", 0, 0, NULL, &h), MCMTL_E_INVALID_ARGUMENT, "NULL source");
    CHECK_RC(shader_rc(MCMTL_STAGE_VERTEX, CORE_MSL_TERRAIN_VS, strlen(CORE_MSL_TERRAIN_VS), "main0", 4, 0, NULL, &h),
             MCMTL_E_INVALID_ARGUMENT, "unknown flag");
    CHECK_RC(shader_rc(MCMTL_STAGE_VERTEX, CORE_MSL_TERRAIN_VS, strlen(CORE_MSL_TERRAIN_VS), "main0", 0, 12345, NULL, &h),
             MCMTL_E_INVALID_ARGUMENT, "unknown language version");
    const char invalidUtf8[] = {'\xff', '\xfe', 'x', 'y'};
    CHECK_RC(shader_rc(MCMTL_STAGE_VERTEX, invalidUtf8, sizeof invalidUtf8, "main0", 0, 0, NULL, &h), MCMTL_E_INVALID_ARGUMENT, "invalid UTF-8");
    mcmtl_shader_desc d = {.structSize = 8};
    CHECK_RC(mcmtl_shader_create(g_device, &d, &h), MCMTL_E_ABI_MISMATCH, "shader structSize");
    CHECK_RC(mcmtl_shader_create(g_device, NULL, &h), MCMTL_E_INVALID_ARGUMENT, "NULL shader desc");

    /* Compile workers are concurrent: per-thread results and thread-local last errors must not mix. */
    enum { N = 16 };
    int32_t rcStorage[N];
    mcmtl_handle handleStorage[N];
    BOOL matchStorage[N];
    int32_t *rcs = rcStorage;
    mcmtl_handle *handles = handleStorage;
    BOOL *errorMatches = matchStorage;
    dispatch_apply(N, dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^(size_t i) {
        char src[1024];
        snprintf(src, sizeof src, (i % 2) ? BAD_VS_TEMPLATE : VALID_VS_TEMPLATE, (int)i);
        rcs[i] = shader_rc(MCMTL_STAGE_VERTEX, src, strlen(src), "main0", 0, 0, NULL, &handles[i]);
        char marker[64];
        snprintf(marker, sizeof marker, "core_bad_marker_%d", (int)i);
        errorMatches[i] = (i % 2) ? strstr(mcmtl_last_error(), marker) != NULL : mcmtl_last_error()[0] == '\0';
    });
    for (int i = 0; i < N; i++) {
        CHECK(rcs[i] == ((i % 2) ? MCMTL_E_COMPILE_FAILED : MCMTL_OK), "concurrent compile %d -> %d", i, rcs[i]);
        CHECK(errorMatches[i], "concurrent compile %d: thread-local last error mismatch", i);
        mcmtl_release(g_device, handles[i]);
    }
}

/* ---------------------------------------------------------------------------------------------- */
/* Pipelines                                                                                        */
/* ---------------------------------------------------------------------------------------------- */

/* The descriptor I5 builds for pipeline/solid_terrain_multidraw (CONTRACT.md 7.2): BLOCK in slot 0
 * ([[buffer(30)]]) and CHUNK_DATA_INSTANCED in slot 1 ([[buffer(29)]], per instance). */
static mcmtl_pipeline_desc terrain_desc(mcmtl_handle vs, mcmtl_handle fs) {
    mcmtl_pipeline_desc d;
    memset(&d, 0, sizeof d);
    d.structSize = sizeof d;
    d.flags = MCMTL_PIPELINE_FLAG_HAS_DEPTH_STATE;
    d.vertexFunction = vs;
    d.fragmentFunction = fs;
    d.label = "pipeline/solid_terrain_multidraw";
    d.primitiveType = MTLPrimitiveTypeTriangle;
    d.cullMode = MTLCullModeBack;
    d.fillMode = MTLTriangleFillModeFill;
    d.frontFacing = MTLWindingClockwise;
    d.depthCompare = MTLCompareFunctionGreaterEqual;
    d.depthWriteEnabled = 1;
    d.colorTargetCount = 1;
    d.colorTargets[0] = (mcmtl_color_target){.pixelFormat = MTLPixelFormatRGBA8Unorm, .writeMask = MTLColorWriteMaskAll};
    d.attributeCount = 6;
    d.attributes[0] = (mcmtl_vertex_attribute){0, MTLVertexFormatFloat3, 0, 30};
    d.attributes[1] = (mcmtl_vertex_attribute){1, MTLVertexFormatUChar4Normalized, 12, 30};
    d.attributes[2] = (mcmtl_vertex_attribute){2, MTLVertexFormatFloat2, 16, 30};
    d.attributes[3] = (mcmtl_vertex_attribute){3, MTLVertexFormatShort2, 24, 30};
    d.attributes[4] = (mcmtl_vertex_attribute){4, MTLVertexFormatInt3, 0, 29};
    d.attributes[5] = (mcmtl_vertex_attribute){5, MTLVertexFormatFloat, 12, 29};
    d.layoutCount = 2;
    d.layouts[0] = (mcmtl_vertex_layout){30, 28, MTLVertexStepFunctionPerVertex, 1};
    d.layouts[1] = (mcmtl_vertex_layout){29, 16, MTLVertexStepFunctionPerInstance, 1};
    d.bufferMask[0] = (1u << 0) | (1u << 1) | (1u << 5) | (1u << 29) | (1u << 30);
    d.bufferMask[1] = (1u << 1) | (1u << 2) | (1u << 5);
    d.textureMask[0] = d.samplerMask[0] = 1u << 2;
    d.textureMask[1] = d.samplerMask[1] = 1u << 0;
    d.requiredVertexBufferMask = (1u << 29) | (1u << 30);
    d.precompileDepthFormatCount = 1;
    d.precompileDepthFormats[0] = MTLPixelFormatDepth32Float;
    return d;
}

static const char DEPTH_COLOR_FS[] =
    "#include <metal_stdlib>\n"
    "using namespace metal;\n"
    "struct main0_out { float4 fragColor [[color(0)]]; float gl_FragDepth [[depth(any)]]; };\n"
    "fragment main0_out main0(float4 gl_FragCoord [[position]])\n"
    "{\n"
    "    main0_out out = {};\n"
    "    out.fragColor = float4(1.0);\n"
    "    out.gl_FragDepth = gl_FragCoord.z;\n"
    "    return out;\n"
    "}\n";

static const char MRT_FS[] =
    "#include <metal_stdlib>\n"
    "using namespace metal;\n"
    "struct Params { float4 c0; float4 c1; };\n"
    "struct main0_out { float4 color0 [[color(0)]]; float4 color1 [[color(1)]]; };\n"
    "struct main0_in { float2 texCoord [[user(locn0)]]; };\n"
    "fragment main0_out main0(main0_in in [[stage_in]], constant Params& _p [[buffer(5)]])\n"
    "{\n"
    "    main0_out out = {};\n"
    "    out.color0 = _p.c0;\n"
    "    out.color1 = _p.c1;\n"
    "    return out;\n"
    "}\n";

static void test_pipelines(mcmtl_handle terrainVs, mcmtl_handle terrainFs, mcmtl_handle screenquad) {
    mcmtl_pipeline_desc d = terrain_desc(terrainVs, terrainFs);
    mcmtl_handle h = make_pipeline(&d, "solid_terrain_multidraw");
    if (!h) return;
    MCMTLPipeline *p = MCMTL_PIPELINE(h);
    const uint64_t key = mcmtl_variant_key(1, MTLPixelFormatDepth32Float);
    CHECK(p->owner == g_dev && p->variantCount == 1 && p->variantKeys[0] == key && p->variantStates[0] != nil, "precompiled variant");
    CHECK([p->label isEqualToString:@"pipeline/solid_terrain_multidraw"] && [p->descriptorTemplate.label isEqualToString:p->label], "label");
    CHECK(p->colorTargetCount == 1 && p->colorTargetFormats[0] == MTLPixelFormatRGBA8Unorm, "color targets");
    CHECK(p->depthStencilState == mcmtl_device_depth_stencil_state(g_dev, MTLCompareFunctionGreaterEqual, YES), "cached depth-stencil state");
    CHECK(p->primitiveType == MTLPrimitiveTypeTriangle && p->emulation == 0 && p->cullMode == MTLCullModeBack &&
              p->fillMode == MTLTriangleFillModeFill && p->frontFacing == MTLWindingClockwise, "raster state");
    CHECK(p->bufferMask[0] == d.bufferMask[0] && p->bufferMask[1] == d.bufferMask[1] && p->textureMask[0] == d.textureMask[0] &&
              p->samplerMask[1] == d.samplerMask[1] && p->requiredVertexBufferMask == d.requiredVertexBufferMask, "masks copied");
    MTLVertexDescriptor *vd = p->descriptorTemplate.vertexDescriptor;
    CHECK(vd.attributes[3].format == MTLVertexFormatShort2 && vd.attributes[3].offset == 24 && vd.attributes[3].bufferIndex == 30 &&
              vd.attributes[4].format == MTLVertexFormatInt3 && vd.attributes[4].bufferIndex == 29 && vd.layouts[29].stride == 16 &&
              vd.layouts[29].stepFunction == MTLVertexStepFunctionPerInstance && vd.layouts[30].stride == 28, "vertex descriptor");

    /* Lazy variants: missing color attachment, other depth formats, stencil; then overflow past 8. */
    const uint64_t lazyKeys[] = {
        mcmtl_variant_key(1, 0), mcmtl_variant_key(0, MTLPixelFormatDepth32Float), mcmtl_variant_key(1, MTLPixelFormatDepth32Float_Stencil8),
        mcmtl_variant_key(1, MTLPixelFormatStencil8), mcmtl_variant_key(1, MTLPixelFormatDepth16Unorm), mcmtl_variant_key(0, 0),
        mcmtl_variant_key(0, MTLPixelFormatDepth32Float_Stencil8), mcmtl_variant_key(0, MTLPixelFormatDepth16Unorm),
        mcmtl_variant_key(0, MTLPixelFormatStencil8),
    };
    for (size_t i = 0; i < sizeof lazyKeys / sizeof lazyKeys[0]; i++) {
        id<MTLRenderPipelineState> pso = mcmtl_pipeline_variant(p, lazyKeys[i]);
        CHECK(pso != nil, "lazy variant 0x%llx", (unsigned long long)lazyKeys[i]);
        CHECK(mcmtl_pipeline_variant(p, lazyKeys[i]) == pso, "variant 0x%llx cached", (unsigned long long)lazyKeys[i]);
    }
    CHECK(p->variantCount == MCMTL_PIPELINE_INLINE_VARIANTS && p->overflowVariants.count == 2, "inline %u + overflow %lu variants",
          p->variantCount, (unsigned long)p->overflowVariants.count);
    CHECK(p->descriptorTemplate.colorAttachments[0].pixelFormat == MTLPixelFormatRGBA8Unorm &&
              p->descriptorTemplate.depthAttachmentPixelFormat == MTLPixelFormatInvalid &&
              p->descriptorTemplate.stencilAttachmentPixelFormat == MTLPixelFormatInvalid, "template never mutated by variant builds");
    uint32_t before = message_count();
    CHECK(mcmtl_pipeline_build_variant(p, mcmtl_variant_key(1, MTLPixelFormatRGBA8Unorm)) == nil && message_count() == before + 1,
          "bad depth format in a key -> nil + one device message");
    MCMTLPipeline *bare = [MCMTLPipeline new];   /* decoder tests build pipelines by hand, without a template */
    bare->owner = g_dev;
    CHECK(mcmtl_pipeline_build_variant(bare, key) == nil && mcmtl_pipeline_build_variant(nil, key) == nil, "no template -> nil");

    /* Concurrent creation from compile workers. */
    enum { N = 8 };
    mcmtl_handle handleStorage[N];
    int32_t rcStorage[N];
    mcmtl_handle *handles = handleStorage;
    int32_t *rcs = rcStorage;
    const mcmtl_pipeline_desc *shared = &d;
    dispatch_apply(N, dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^(size_t i) {
        handles[i] = 0;
        rcs[i] = mcmtl_pipeline_create(g_device, shared, &handles[i]);
    });
    for (int i = 0; i < N; i++) {
        CHECK(rcs[i] == MCMTL_OK && handles[i], "concurrent pipeline_create %d -> %d", i, rcs[i]);
        mcmtl_release(g_device, handles[i]);
    }

    /* Real clouds pipeline: no vertex input (vertex_id + texel buffer), translucent blending. */
    mcmtl_handle cloudsVs = make_shader(MCMTL_STAGE_VERTEX, CORE_MSL_CLOUDS_VS, "clouds vs");
    mcmtl_handle cloudsFs = make_shader(MCMTL_STAGE_FRAGMENT, CORE_MSL_CLOUDS_FS, "clouds fs");
    mcmtl_pipeline_desc cd = simple_pipeline_desc(cloudsVs, cloudsFs, MTLPixelFormatRGBA8Unorm, MTLPixelFormatDepth32Float);
    cd.flags = MCMTL_PIPELINE_FLAG_HAS_DEPTH_STATE;
    cd.depthCompare = MTLCompareFunctionGreaterEqual;
    cd.depthWriteEnabled = 1;
    cd.colorTargets[0] = (mcmtl_color_target){MTLPixelFormatRGBA8Unorm, MTLColorWriteMaskAll, 1, MTLBlendOperationAdd, MTLBlendOperationAdd,
                                              MTLBlendFactorSourceAlpha, MTLBlendFactorOneMinusSourceAlpha, MTLBlendFactorOne,
                                              MTLBlendFactorOneMinusSourceAlpha, 0};
    cd.bufferMask[0] = (1u << 0) | (1u << 4) | (1u << 5);
    cd.textureMask[0] = cd.texelBufferMask[0] = 1u << 3;
    cd.bufferMask[1] = 1u << 2;
    mcmtl_handle clouds = make_pipeline(&cd, "clouds");

    /* Real blit_depth (no color target, writes depth) without a depth state: both precompiles build. */
    mcmtl_handle blitDepthFs = make_shader(MCMTL_STAGE_FRAGMENT, CORE_MSL_BLIT_DEPTH_FS, "blit_depth fs");
    mcmtl_pipeline_desc bd = simple_pipeline_desc(screenquad, blitDepthFs, 0, MTLPixelFormatDepth32Float);
    bd.precompileDepthFormatCount = 2;
    bd.precompileDepthFormats[1] = 0;
    mcmtl_handle blitDepth = make_pipeline(&bd, "blit_depth without depth state");
    CHECK(blitDepth && MCMTL_PIPELINE(blitDepth)->variantCount == 2, "depth-only pipeline: both precompiled variants");

    /* A color + depth writing fragment (like the OIT shaders under RENDERPEARL_EXPLICIT_DEPTH_INVARIANCE)
     * without a depth state, precompile list in the "wrong" order {Invalid, Depth32Float}: Metal judges
     * "shader writes to depth" on the first PSO of a function and caches the verdict, so the depth variant
     * must be built first; then the depth-less variant builds and renders (depth writes dropped, as on
     * Vulkan). */
    mcmtl_handle depthColorFs = make_shader(MCMTL_STAGE_FRAGMENT, DEPTH_COLOR_FS, "depth+color fs");
    mcmtl_pipeline_desc dc = simple_pipeline_desc(screenquad, depthColorFs, MTLPixelFormatRGBA8Unorm, 0);
    dc.precompileDepthFormatCount = 2;
    dc.precompileDepthFormats[1] = MTLPixelFormatDepth32Float;
    before = message_count();
    mcmtl_handle depthWriter = make_pipeline(&dc, "depth writer without depth state");
    CHECK(depthWriter && MCMTL_PIPELINE(depthWriter)->variantCount == 2 && MCMTL_PIPELINE(depthWriter)->variantKeys[0] ==
              mcmtl_variant_key(1, MTLPixelFormatDepth32Float) && message_count() == before,
          "depth variant built first, then the depth-less one");
    if (depthWriter) {
        mcmtl_handle target = make_texture(MTLPixelFormatRGBA8Unorm, MTLTextureType2D, 4, 4, 1, MTLTextureUsageRenderTarget, "dw target");
        @autoreleasepool {
            id<MTLCommandBuffer> cb = [g_dev->queue commandBuffer];
            id<MTLRenderCommandEncoder> enc = [cb renderCommandEncoderWithDescriptor:pass_descriptor(MCMTL_TEXTURE(target), nil, nil,
                                                                                              MTLClearColorMake(0, 0, 0, 0),
                                                                                              MTLClearColorMake(0, 0, 0, 0), 0)];
            [enc setRenderPipelineState:mcmtl_pipeline_variant(MCMTL_PIPELINE(depthWriter), mcmtl_variant_key(1, 0))];
            [enc drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
            [enc endEncoding];
            commit_and_wait(cb, "depth writer in a depth-less pass");
        }
        const uint8_t *px = read_texture(MCMTL_TEXTURE(target), 0, 4, 4, 4).bytes;
        CHECK(px[0] == 255 && px[63] == 255, "depth writer rendered in a depth-less pass (%u)", px[0]);
        mcmtl_release(g_device, target);
    }
    /* A fresh depth-writing function whose only precompile is depth-less fails cleanly with the log. */
    char salted[1024];
    snprintf(salted, sizeof salted, "%s// fresh %u\n", DEPTH_COLOR_FS, arc4random());
    mcmtl_handle freshFs = make_shader(MCMTL_STAGE_FRAGMENT, salted, "fresh depth writer");
    dc.fragmentFunction = freshFs;
    dc.precompileDepthFormatCount = 1;
    dc.precompileDepthFormats[0] = 0;
    mcmtl_handle none = 0;
    CHECK_RC(mcmtl_pipeline_create(g_device, &dc, &none), MCMTL_E_COMPILE_FAILED, "only a depth-less variant of a depth writer");
    CHECK(none == 0 && strstr(mcmtl_last_error(), "depth") != NULL, "PSO error text: %s", mcmtl_last_error());
    if (none) mcmtl_release(g_device, none);
    mcmtl_release(g_device, freshFs);

    /* PSO-level failure: the descriptor lacks an attribute the vertex function reads. */
    mcmtl_pipeline_desc missing = terrain_desc(terrainVs, terrainFs);
    missing.attributeCount = 5;
    CHECK_RC(mcmtl_pipeline_create(g_device, &missing, &none), MCMTL_E_COMPILE_FAILED, "vertex attribute missing from the descriptor");

    /* Validation errors never reach Metal (the debug layer would abort). */
    struct { void (^patch)(mcmtl_pipeline_desc *); int32_t expected; const char *what; } bad[] = {
        {^(mcmtl_pipeline_desc *x) { x->vertexFunction = 0; }, MCMTL_E_INVALID_ARGUMENT, "no vertex function"},
        {^(mcmtl_pipeline_desc *x) { x->vertexFunction = x->fragmentFunction; }, MCMTL_E_INVALID_ARGUMENT, "fragment function as vertex"},
        {^(mcmtl_pipeline_desc *x) { x->fragmentFunction = x->vertexFunction; }, MCMTL_E_INVALID_ARGUMENT, "vertex function as fragment"},
        {^(mcmtl_pipeline_desc *x) { x->attributes[1].offset = 14; }, MCMTL_E_INVALID_ARGUMENT, "attribute offset 14"},
        {^(mcmtl_pipeline_desc *x) { x->attributes[5].bufferIndex = 28; }, MCMTL_E_INVALID_ARGUMENT, "attribute without a layout"},
        {^(mcmtl_pipeline_desc *x) { x->attributes[5].location = 4; }, MCMTL_E_INVALID_ARGUMENT, "duplicate location"},
        {^(mcmtl_pipeline_desc *x) { x->attributes[5].format = 43; }, MCMTL_E_INVALID_ARGUMENT, "invalid vertex format"},
        {^(mcmtl_pipeline_desc *x) { x->layouts[0].stride = 0; }, MCMTL_E_INVALID_ARGUMENT, "stride 0"},
        {^(mcmtl_pipeline_desc *x) { x->layouts[0].stride = 30; }, MCMTL_E_INVALID_ARGUMENT, "stride 30"},
        {^(mcmtl_pipeline_desc *x) { x->layouts[1].bufferIndex = 14; }, MCMTL_E_INVALID_ARGUMENT, "layout at buffer 14"},
        {^(mcmtl_pipeline_desc *x) { x->layouts[1].bufferIndex = 30; }, MCMTL_E_INVALID_ARGUMENT, "duplicate layout"},
        {^(mcmtl_pipeline_desc *x) { x->layouts[1].stepRate = 0; }, MCMTL_E_INVALID_ARGUMENT, "step rate 0"},
        {^(mcmtl_pipeline_desc *x) { x->colorTargets[0].pixelFormat = MTLPixelFormatRGBA8Uint; x->colorTargets[0].blendingEnabled = 1; },
         MCMTL_E_INVALID_ARGUMENT, "blending an integer target"},
        {^(mcmtl_pipeline_desc *x) { x->colorTargets[0].pixelFormat = MTLPixelFormatDepth32Float; }, MCMTL_E_INVALID_ARGUMENT,
         "depth format as a color target"},
        {^(mcmtl_pipeline_desc *x) { x->colorTargets[0].writeMask = 0x10; }, MCMTL_E_INVALID_ARGUMENT, "Unspecialized write mask"},
        {^(mcmtl_pipeline_desc *x) { x->colorTargets[0].sourceRGBBlendFactor = 19; }, MCMTL_E_INVALID_ARGUMENT, "Unspecialized blend factor"},
        {^(mcmtl_pipeline_desc *x) { x->colorTargetCount = 9; }, MCMTL_E_INVALID_ARGUMENT, "9 color targets"},
        {^(mcmtl_pipeline_desc *x) { x->precompileDepthFormats[0] = MTLPixelFormatRGBA8Unorm; }, MCMTL_E_INVALID_ARGUMENT, "color precompile"},
        {^(mcmtl_pipeline_desc *x) { x->precompileDepthFormatCount = 5; }, MCMTL_E_INVALID_ARGUMENT, "5 precompile formats"},
        {^(mcmtl_pipeline_desc *x) { x->textureMask[1] = 1u << 16; }, MCMTL_E_INVALID_ARGUMENT, "texture mask bit 16"},
        {^(mcmtl_pipeline_desc *x) { x->textureCubeMask[1] = 1u << 4; }, MCMTL_E_INVALID_ARGUMENT, "cube mask outside texture mask"},
        {^(mcmtl_pipeline_desc *x) { x->bufferMask[0] |= 1u << 31; }, MCMTL_E_INVALID_ARGUMENT, "buffer mask bit 31"},
        {^(mcmtl_pipeline_desc *x) { x->requiredVertexBufferMask |= 1u << 3; }, MCMTL_E_INVALID_ARGUMENT, "required vertex buffer 3"},
        {^(mcmtl_pipeline_desc *x) { x->flags |= 1u << 5; }, MCMTL_E_INVALID_ARGUMENT, "unknown pipeline flag"},
        {^(mcmtl_pipeline_desc *x) { x->emulation = MCMTL_EMULATION_TRIANGLE_FAN; x->primitiveType = MTLPrimitiveTypePoint; },
         MCMTL_E_INVALID_ARGUMENT, "fan emulation of points"},
        {^(mcmtl_pipeline_desc *x) { x->cullMode = 3; }, MCMTL_E_INVALID_ARGUMENT, "cull mode 3"},
        {^(mcmtl_pipeline_desc *x) { x->depthCompare = 8; }, MCMTL_E_INVALID_ARGUMENT, "compare 8"},
        {^(mcmtl_pipeline_desc *x) { x->pushConstantSize = 8192; }, MCMTL_E_INVALID_ARGUMENT, "push constants 8 KiB"},
        {^(mcmtl_pipeline_desc *x) { x->structSize = 1200; }, MCMTL_E_ABI_MISMATCH, "structSize"},
    };
    for (size_t i = 0; i < sizeof bad / sizeof bad[0]; i++) {
        mcmtl_pipeline_desc x = terrain_desc(terrainVs, terrainFs);
        bad[i].patch(&x);
        mcmtl_handle out = 9;
        CHECK_RC(mcmtl_pipeline_create(g_device, &x, &out), bad[i].expected, bad[i].what);
        CHECK(out == 0, "%s: no handle", bad[i].what);
    }
    if (!g_dev->device.depth24Stencil8PixelFormatSupported) {
        mcmtl_pipeline_desc x = terrain_desc(terrainVs, terrainFs);
        x.precompileDepthFormats[0] = MTLPixelFormatDepth24Unorm_Stencil8;
        CHECK_RC(mcmtl_pipeline_create(g_device, &x, &none), MCMTL_E_UNSUPPORTED, "Depth24Unorm_Stencil8 precompile");
    }

    /* Releasing the handle frees the pipeline and its variants. */
    __weak MCMTLPipeline *weakPipeline = p;
    __weak id<MTLRenderPipelineState> weakVariant = nil;
    @autoreleasepool {
        weakVariant = p->variantStates[0];
    }
    p = nil;
    mcmtl_release(g_device, h);
    CHECK(WAIT_NIL(weakPipeline) && WAIT_NIL(weakVariant), "pipeline or its variants leaked");
    mcmtl_handle all[] = {clouds, cloudsVs, cloudsFs, blitDepth, blitDepthFs, depthWriter, depthColorFs};
    mcmtl_release_batch(g_device, all, sizeof all / sizeof all[0]);
}

/* Two color targets: blending, write masks, attachment-presence variants. */
static void test_blend_and_masks(mcmtl_handle screenquad) {
    mcmtl_handle fs = make_shader(MCMTL_STAGE_FRAGMENT, MRT_FS, "core mrt fs");
    mcmtl_pipeline_desc d = simple_pipeline_desc(screenquad, fs, MTLPixelFormatRGBA8Unorm, MTLPixelFormatDepth32Float);
    d.colorTargetCount = 2;
    d.colorTargets[0] = (mcmtl_color_target){MTLPixelFormatRGBA8Unorm, MTLColorWriteMaskAll, 1, MTLBlendOperationAdd, MTLBlendOperationAdd,
                                             MTLBlendFactorSourceAlpha, MTLBlendFactorOneMinusSourceAlpha, MTLBlendFactorOne,
                                             MTLBlendFactorOneMinusSourceAlpha, 0};
    /* RenderPearl mask R|G (3) -> MTLColorWriteMask Red|Green (8|4) as MetalFormats.colorWriteMask maps it. */
    d.colorTargets[1] = (mcmtl_color_target){MTLPixelFormatRGBA16Float, MTLColorWriteMaskRed | MTLColorWriteMaskGreen, 1, MTLBlendOperationAdd,
                                             MTLBlendOperationAdd, MTLBlendFactorOne, MTLBlendFactorOne, MTLBlendFactorOne, MTLBlendFactorOne, 0};
    d.bufferMask[1] = 1u << 5;
    d.precompileDepthFormatCount = 2;
    d.precompileDepthFormats[1] = 0;
    mcmtl_handle h = make_pipeline(&d, "mrt");
    mcmtl_handle c0 = make_texture(MTLPixelFormatRGBA8Unorm, MTLTextureType2D, 4, 4, 1, MTLTextureUsageRenderTarget, "mrt c0");
    mcmtl_handle c1 = make_texture(MTLPixelFormatRGBA16Float, MTLTextureType2D, 4, 4, 1, MTLTextureUsageRenderTarget, "mrt c1");
    if (!h || !c0 || !c1) return;
    MCMTLPipeline *p = MCMTL_PIPELINE(h);
    CHECK(p->variantCount == 2 && mcmtl_pipeline_find_variant(p, mcmtl_variant_key(3, MTLPixelFormatDepth32Float)) &&
              mcmtl_pipeline_find_variant(p, mcmtl_variant_key(3, 0)), "both precompiled variants");
    const float params[8] = {1, 0, 0, 0.5f, 0.25f, 0.5f, 0.75f, 1.0f};
    struct { uint32_t mask; const char *what; } passes[] = {{3, "both attachments"}, {1, "only color 0"}, {2, "only color 1"}};
    for (size_t i = 0; i < 3; i++) {
        @autoreleasepool {
            uint32_t mask = passes[i].mask;
            id<MTLRenderPipelineState> pso = mcmtl_pipeline_variant(p, mcmtl_variant_key(mask, 0));
            CHECK(pso != nil, "variant %s", passes[i].what);
            id<MTLCommandBuffer> cb = [g_dev->queue commandBuffer];
            MTLRenderPassDescriptor *rp = pass_descriptor((mask & 1) ? MCMTL_TEXTURE(c0) : nil, (mask & 2) ? MCMTL_TEXTURE(c1) : nil, nil,
                                                          MTLClearColorMake(0, 0, 1, 1), MTLClearColorMake(0.1, 0.1, 0.1, 0.1), 0);
            id<MTLRenderCommandEncoder> enc = [cb renderCommandEncoderWithDescriptor:rp];
            [enc setRenderPipelineState:pso];
            [enc setFragmentBytes:params length:sizeof params atIndex:5];
            [enc drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
            [enc endEncoding];
            commit_and_wait(cb, passes[i].what);
            if (mask & 1) {
                const uint8_t *px = read_texture(MCMTL_TEXTURE(c0), 0, 4, 4, 4).bytes;
                /* src (1,0,0,0.5) over dst (0,0,1,1): rgb = src*a + dst*(1-a), a = 0.5*1 + 1*(1-0.5). */
                CHECK(near_u8(px[0], 127.5, 1) && near_u8(px[1], 0, 1) && near_u8(px[2], 127.5, 1) && near_u8(px[3], 255, 1),
                      "%s: blended color0 = %u %u %u %u", passes[i].what, px[0], px[1], px[2], px[3]);
            }
            if (mask & 2) {
                const uint16_t *hp = read_texture(MCMTL_TEXTURE(c1), 0, 4, 4, 8).bytes;
                float v[4];
                for (int k = 0; k < 4; k++) {
                    __fp16 half;
                    memcpy(&half, &hp[k], 2);
                    v[k] = (float)half;
                }
                CHECK(fabsf(v[0] - 0.35f) < 0.01f && fabsf(v[1] - 0.6f) < 0.01f && fabsf(v[2] - 0.1f) < 0.01f && fabsf(v[3] - 0.1f) < 0.01f,
                      "%s: additive + write mask R|G on color1 = %f %f %f %f", passes[i].what, (double)v[0], (double)v[1], (double)v[2],
                      (double)v[3]);
            }
        }
    }
    mcmtl_handle all[] = {h, fs, c0, c1};
    mcmtl_release_batch(g_device, all, 4);
}

/* ---------------------------------------------------------------------------------------------- */
/* End-to-end: the real vanilla terrain pipeline                                                   */
/* ---------------------------------------------------------------------------------------------- */

typedef struct __attribute__((packed)) {
    float position[3];
    uint8_t color[4];
    float uv0[2];
    int16_t uv2[2];
} block_vertex;   /* DefaultVertexFormat.BLOCK, 28 bytes */

typedef struct {
    int32_t chunkPosition[3];
    float chunkVisibility;
} chunk_instance;  /* DefaultVertexFormat.CHUNK_DATA_INSTANCED, 16 bytes */

static void atlas_texel(uint32_t c, uint32_t r, uint8_t out[4]) {
    out[0] = (uint8_t)(c * 60 + 20);
    out[1] = (uint8_t)(r * 60 + 20);
    out[2] = (uint8_t)(200 - c * 20 - r * 10);
    out[3] = 255;
}

static void test_terrain_render(mcmtl_handle vs, mcmtl_handle fs) {
    _Static_assert(sizeof(block_vertex) == 28, "BLOCK vertex");
    mcmtl_pipeline_desc d = terrain_desc(vs, fs);
    mcmtl_handle h = make_pipeline(&d, "terrain render");
    if (!h) return;
    MCMTLPipeline *p = MCMTL_PIPELINE(h);

    /* Atlas 4x4 (sampled nearest via the shader's sampleNearest), lightmap 16x16 white except texel (15, 0). */
    uint8_t atlas[4 * 4 * 4];
    for (uint32_t r = 0; r < 4; r++) for (uint32_t c = 0; c < 4; c++) atlas_texel(c, r, &atlas[(r * 4 + c) * 4]);
    uint32_t lightmap[16 * 16];
    for (int i = 0; i < 256; i++) lightmap[i] = 0xFFFFFFFFu;
    lightmap[15] = 0xFF6496FFu;   /* R 255, G 150, B 100, A 255 at (x 15, y 0) */
    mcmtl_handle atlasTex = make_texture(MTLPixelFormatRGBA8Unorm, MTLTextureType2D, 4, 4, 1, MTLTextureUsageShaderRead, "atlas");
    mcmtl_handle lightTex = make_texture(MTLPixelFormatRGBA8Unorm, MTLTextureType2D, 16, 16, 1, MTLTextureUsageShaderRead, "lightmap");
    upload_texture(MCMTL_TEXTURE(atlasTex), 0, 0, 4, 4, 4, atlas);
    upload_texture(MCMTL_TEXTURE(lightTex), 0, 0, 16, 16, 4, lightmap);
    mcmtl_sampler_desc nearestDesc = sampler_desc(MTLSamplerAddressModeClampToEdge, MTLSamplerMinMagFilterNearest,
                                                  MTLSamplerMipFilterLinear, 1, 0, FLT_MAX);
    mcmtl_sampler_desc linearDesc = sampler_desc(MTLSamplerAddressModeClampToEdge, MTLSamplerMinMagFilterLinear,
                                                 MTLSamplerMipFilterNotMipmapped, 1, 0, 0.25f);
    mcmtl_handle atlasSampler = make_sampler(&nearestDesc);
    mcmtl_handle lightSampler = make_sampler(&linearDesc);

    /* UBOs (std140 layouts as emitted in the MSL structs). */
    mcmtl_buffer_result projection, globals, terrainUbo, fog, vertices, instances, indices;
    mcmtl_handle projectionBuf = make_buffer(256, USAGE_UNIFORM, 0, "Projection", &projection);
    mcmtl_handle globalsBuf = make_buffer(256, USAGE_UNIFORM, 0, "Globals", &globals);
    mcmtl_handle terrainBuf = make_buffer(256, USAGE_UNIFORM, 0, "TerrainUniform", &terrainUbo);
    mcmtl_handle fogBuf = make_buffer(256, USAGE_UNIFORM, 0, "Fog", &fog);
    float *m = (float *)(uintptr_t)projection.contents;
    memset(m, 0, 256);
    m[0] = m[5] = m[10] = m[15] = 1;
    uint8_t *g = (uint8_t *)(uintptr_t)globals.contents;
    memset(g, 0, 256);
    const int32_t cameraBlockPos[3] = {100, 0, -7};
    memcpy(g + 0, cameraBlockPos, 12);   /* CameraBlockPos; CameraOffset (16) = 0; UseRgss (44) = 0 */
    float *t = (float *)(uintptr_t)terrainUbo.contents;
    memset(t, 0, 256);
    t[0] = t[5] = t[10] = t[15] = 1;      /* ModelViewMat */
    ((int32_t *)t)[16] = 4;               /* TextureSize */
    ((int32_t *)t)[17] = 4;
    float *f = (float *)(uintptr_t)fog.contents;
    memset(f, 0, 256);
    f[0] = 1;                             /* FogColor red: visible if fog were applied */
    f[3] = 1;
    f[4] = 100; f[5] = 200; f[6] = 100; f[7] = 200;

    /* Two quads drawn as GL-CCW triangles through the QUADS index pattern: instance 0 left half, instance 1
     * shifted right by its ChunkPosition (Int3), drawn with baseVertex 4 / baseInstance 1. */
    block_vertex quad[8];
    const float corners[4][2] = {{-1, -1}, {0, -1}, {0, 1}, {-1, 1}};
    const float uvs[4][2] = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
    for (int q = 0; q < 2; q++) {
        for (int i = 0; i < 4; i++) {
            block_vertex *v = &quad[q * 4 + i];
            v->position[0] = corners[i][0];
            v->position[1] = corners[i][1];
            v->position[2] = 0.5f;
            v->color[0] = 255; v->color[1] = q ? 128 : 255; v->color[2] = 255; v->color[3] = 255;
            v->uv0[0] = uvs[i][0];
            v->uv0[1] = uvs[i][1];
            v->uv2[0] = q ? 240 : 0;
            v->uv2[1] = 0;
        }
    }
    chunk_instance chunks[2] = {{{100, 0, -7}, 1.0f}, {{101, 0, -7}, 1.0f}};
    const uint16_t quadIndices[6] = {0, 1, 2, 2, 3, 0};
    mcmtl_handle vertexBuf = make_buffer(sizeof quad, USAGE_VERTEX, 0, "terrain vertices", &vertices);
    mcmtl_handle instanceBuf = make_buffer(sizeof chunks, USAGE_VERTEX, 0, "chunk data", &instances);
    mcmtl_handle indexBuf = make_buffer(sizeof quadIndices, USAGE_INDEX, 0, "quad indices", &indices);
    memcpy((void *)(uintptr_t)vertices.contents, quad, sizeof quad);
    memcpy((void *)(uintptr_t)instances.contents, chunks, sizeof chunks);
    memcpy((void *)(uintptr_t)indices.contents, quadIndices, sizeof quadIndices);

    mcmtl_handle color = make_texture(MTLPixelFormatRGBA8Unorm, MTLTextureType2D, 32, 16, 1,
                                      MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead, "main color");
    mcmtl_handle depth = make_texture(MTLPixelFormatDepth32Float, MTLTextureType2D, 32, 16, 1,
                                      MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead, "main depth");
    @autoreleasepool {
        id<MTLCommandBuffer> cb = [g_dev->queue commandBuffer];
        id<MTLRenderCommandEncoder> enc = [cb renderCommandEncoderWithDescriptor:pass_descriptor(MCMTL_TEXTURE(color), nil, MCMTL_TEXTURE(depth),
                                                                                          MTLClearColorMake(0, 0, 0, 0),
                                                                                          MTLClearColorMake(0, 0, 0, 0), 0.0)];
        /* What SET_PIPELINE applies (CONTRACT.md 4.4), from the pipeline object. */
        [enc setRenderPipelineState:mcmtl_pipeline_variant(p, mcmtl_variant_key(1, MTLPixelFormatDepth32Float))];
        [enc setDepthStencilState:p->depthStencilState];
        [enc setCullMode:(MTLCullMode)p->cullMode];
        [enc setFrontFacingWinding:(MTLWinding)p->frontFacing];
        [enc setTriangleFillMode:(MTLTriangleFillMode)p->fillMode];
        [enc setDepthBias:p->depthBiasConstant slopeScale:p->depthBiasSlopeScale clamp:p->depthBiasClamp];
        /* Hybrid slots: Projection 0, Globals 1, Fog 2, TerrainUniform 5; Sampler0 0, Sampler2 2. */
        [enc setVertexBuffer:MCMTL_BUFFER(projectionBuf) offset:0 atIndex:0];
        [enc setVertexBuffer:MCMTL_BUFFER(globalsBuf) offset:0 atIndex:1];
        [enc setVertexBuffer:MCMTL_BUFFER(terrainBuf) offset:0 atIndex:5];
        [enc setFragmentBuffer:MCMTL_BUFFER(globalsBuf) offset:0 atIndex:1];
        [enc setFragmentBuffer:MCMTL_BUFFER(fogBuf) offset:0 atIndex:2];
        [enc setFragmentBuffer:MCMTL_BUFFER(terrainBuf) offset:0 atIndex:5];
        [enc setVertexTexture:MCMTL_TEXTURE(lightTex) atIndex:2];
        [enc setVertexSamplerState:MCMTL_SAMPLER(lightSampler) atIndex:2];
        [enc setFragmentTexture:MCMTL_TEXTURE(atlasTex) atIndex:0];
        [enc setFragmentSamplerState:MCMTL_SAMPLER(atlasSampler) atIndex:0];
        [enc setVertexBuffer:MCMTL_BUFFER(vertexBuf) offset:0 atIndex:30];   /* slot 0 */
        [enc setVertexBuffer:MCMTL_BUFFER(instanceBuf) offset:0 atIndex:29]; /* slot 1 */
        [enc drawIndexedPrimitives:(MTLPrimitiveType)p->primitiveType indexCount:6 indexType:MTLIndexTypeUInt16
                       indexBuffer:MCMTL_BUFFER(indexBuf) indexBufferOffset:0 instanceCount:1 baseVertex:0 baseInstance:0];
        [enc drawIndexedPrimitives:(MTLPrimitiveType)p->primitiveType indexCount:6 indexType:MTLIndexTypeUInt16
                       indexBuffer:MCMTL_BUFFER(indexBuf) indexBufferOffset:0 instanceCount:1 baseVertex:4 baseInstance:1];
        [enc endEncoding];
        commit_and_wait(cb, "terrain draw");
    }
    const uint8_t *px = read_texture(MCMTL_TEXTURE(color), 0, 32, 16, 4).bytes;
    const float *dp = read_texture(MCMTL_TEXTURE(depth), 0, 32, 16, 4).bytes;
    int bad = 0, badDepth = 0;
    for (uint32_t y = 0; y < 16; y++) {
        for (uint32_t x = 0; x < 32; x++) {
            uint8_t texel[4];
            atlas_texel((x % 16) / 4, y / 4, texel);   /* memory row 0 = NDC y -1 = v 0 (GL/Vulkan layout) */
            double e[4] = {texel[0], texel[1], texel[2], texel[3]};
            if (x >= 16) {
                e[1] *= (128.0 / 255.0) * (150.0 / 255.0);   /* vertex Color G x lightmap(15, 0) G */
                e[2] *= 100.0 / 255.0;
            }
            const uint8_t *q = &px[(y * 32 + x) * 4];
            if (!near_u8(q[0], e[0], 2) || !near_u8(q[1], e[1], 2) || !near_u8(q[2], e[2], 2) || !near_u8(q[3], e[3], 2)) {
                if (bad++ < 4) {
                    printf("  terrain pixel (%u, %u) = %u %u %u %u, expected %.0f %.0f %.0f %.0f\n", x, y, q[0], q[1], q[2], q[3], e[0], e[1],
                           e[2], e[3]);
                }
            }
            if (fabsf(dp[y * 32 + x] - 0.5f) > 1e-6f) badDepth++;
        }
    }
    CHECK(bad == 0, "real terrain pipeline: %d of 512 pixels wrong", bad);
    CHECK(badDepth == 0, "terrain depth: %d pixels != 0.5", badDepth);
    mcmtl_handle all[] = {h, atlasTex, lightTex, atlasSampler, lightSampler, projectionBuf, globalsBuf, terrainBuf, fogBuf,
                          vertexBuf, instanceBuf, indexBuf, color, depth};
    CHECK_RC(mcmtl_release_batch(g_device, all, sizeof all / sizeof all[0]), MCMTL_OK, "release terrain objects");
}

/* Depth-only pipeline (real blit_depth: no color target, writes [[depth(any)]]). */
static void test_depth_only_render(mcmtl_handle screenquad) {
    mcmtl_handle fs = make_shader(MCMTL_STAGE_FRAGMENT, CORE_MSL_BLIT_DEPTH_FS, "blit_depth");
    mcmtl_pipeline_desc d = simple_pipeline_desc(screenquad, fs, 0, MTLPixelFormatDepth32Float);
    d.flags = MCMTL_PIPELINE_FLAG_HAS_DEPTH_STATE;
    d.depthCompare = MTLCompareFunctionAlways;
    d.depthWriteEnabled = 1;
    d.textureMask[1] = d.samplerMask[1] = 1u << 3;
    mcmtl_handle h = make_pipeline(&d, "blit_depth");
    mcmtl_handle src = make_texture(MTLPixelFormatR32Float, MTLTextureType2D, 8, 8, 1, MTLTextureUsageShaderRead, "depth source");
    mcmtl_handle dst = make_texture(MTLPixelFormatDepth32Float, MTLTextureType2D, 8, 8, 1, MTLTextureUsageRenderTarget, "depth target");
    mcmtl_sampler_desc sd = sampler_desc(MTLSamplerAddressModeClampToEdge, MTLSamplerMinMagFilterNearest, MTLSamplerMipFilterNotMipmapped, 1,
                                         0, 0.25f);
    mcmtl_handle sampler = make_sampler(&sd);
    if (!h || !src || !dst) return;
    float values[64];
    for (int i = 0; i < 64; i++) values[i] = (float)i / 64.0f;
    upload_texture(MCMTL_TEXTURE(src), 0, 0, 8, 8, 4, values);
    MCMTLPipeline *p = MCMTL_PIPELINE(h);
    @autoreleasepool {
        id<MTLCommandBuffer> cb = [g_dev->queue commandBuffer];
        id<MTLRenderCommandEncoder> enc = [cb renderCommandEncoderWithDescriptor:pass_descriptor(nil, nil, MCMTL_TEXTURE(dst),
                                                                                          MTLClearColorMake(0, 0, 0, 0),
                                                                                          MTLClearColorMake(0, 0, 0, 0), 1.0)];
        [enc setRenderPipelineState:mcmtl_pipeline_variant(p, mcmtl_variant_key(0, MTLPixelFormatDepth32Float))];
        [enc setDepthStencilState:p->depthStencilState];
        [enc setFragmentTexture:MCMTL_TEXTURE(src) atIndex:3];
        [enc setFragmentSamplerState:MCMTL_SAMPLER(sampler) atIndex:3];
        [enc drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
        [enc endEncoding];
        commit_and_wait(cb, "blit depth");
    }
    const float *out = read_texture(MCMTL_TEXTURE(dst), 0, 8, 8, 4).bytes;
    int bad = 0;
    for (int i = 0; i < 64; i++) bad += fabsf(out[i] - values[i]) > 1e-6f;
    CHECK(bad == 0, "depth-only pipeline copied depth: %d mismatches (out[9] %f)", bad, (double)out[9]);
    mcmtl_handle all[] = {h, fs, src, dst, sampler};
    mcmtl_release_batch(g_device, all, 5);
}

/* ---------------------------------------------------------------------------------------------- */
/* Present pass and surface                                                                         */
/* ---------------------------------------------------------------------------------------------- */

static void fill_pattern(uint8_t *rgba, uint32_t w, uint32_t h, uint32_t seed) {
    for (uint32_t y = 0; y < h; y++) {
        for (uint32_t x = 0; x < w; x++) {
            uint8_t *p = &rgba[(y * w + x) * 4];
            p[0] = (uint8_t)(x * 29 + seed);
            p[1] = (uint8_t)(y * 31 + seed * 3);
            p[2] = (uint8_t)(100 + seed);
            p[3] = 77;   /* forced to 255 by the present pass */
        }
    }
}

/* dst (BGRA8 bytes) pixel (x, y) must be src (RGBA8) pixel (x, copyH - 1 - y), alpha 255; outside the copy
 * rectangle (0, 0, 0, 255). Returns the number of wrong pixels. */
static int check_present(const uint8_t *bgra, uint32_t dw, uint32_t dh, const uint8_t *rgba, uint32_t sw, uint32_t sh) {
    uint32_t cw = dw < sw ? dw : sw, ch = dh < sh ? dh : sh;
    int bad = 0;
    for (uint32_t y = 0; y < dh; y++) {
        for (uint32_t x = 0; x < dw; x++) {
            const uint8_t *d = &bgra[(y * dw + x) * 4];
            uint8_t e[4] = {0, 0, 0, 255};   /* B G R A */
            if (x < cw && y < ch) {
                const uint8_t *s = &rgba[((ch - 1 - y) * sw + x) * 4];
                e[0] = s[2];
                e[1] = s[1];
                e[2] = s[0];
            }
            if (memcmp(d, e, 4) != 0) {
                if (bad++ < 3) printf("  present (%u, %u) = %u %u %u %u, expected %u %u %u %u\n", x, y, d[0], d[1], d[2], d[3], e[0], e[1], e[2], e[3]);
            }
        }
    }
    return bad;
}

static void test_present_pass(void) {
    NSString *failure = nil;
    id<MTLRenderPipelineState> a = mcmtl_core_present_pipeline(g_dev, MTLPixelFormatBGRA8Unorm, &failure);
    id<MTLRenderPipelineState> b = mcmtl_core_present_pipeline(g_dev, MTLPixelFormatBGRA8Unorm, &failure);
    id<MTLRenderPipelineState> c = mcmtl_core_present_pipeline(g_dev, MTLPixelFormatRGBA8Unorm, &failure);
    CHECK(a && a == b && c && c != a, "present PSO cached per format (%s)", failure ? failure.UTF8String : "-");

    uint8_t src8[8 * 8 * 4], lvl1[8 * 8 * 4];
    fill_pattern(src8, 8, 8, 1);
    fill_pattern(lvl1, 8, 8, 9);
    mcmtl_handle src = make_texture(MTLPixelFormatRGBA8Unorm, MTLTextureType2D, 8, 8, 1, MTLTextureUsageShaderRead | MTLTextureUsageRenderTarget,
                                    "present src");
    mcmtl_handle mipped = make_texture(MTLPixelFormatRGBA8Unorm, MTLTextureType2D, 16, 16, 2, MTLTextureUsageShaderRead, "present mips");
    upload_texture(MCMTL_TEXTURE(src), 0, 0, 8, 8, 4, src8);
    upload_texture(MCMTL_TEXTURE(mipped), 1, 0, 8, 8, 4, lvl1);
    struct { uint32_t dw, dh; mcmtl_handle source; uint32_t level; const uint8_t *expected; const char *what; } cases[] = {
        {12, 10, src, 0, src8, "drawable larger than the source (uncovered area cleared)"},
        {4, 4, src, 0, src8, "drawable smaller than the source (top-left anchored)"},
        {8, 8, src, 0, src8, "exact size (DontCare load)"},
        {8, 8, mipped, 1, lvl1, "mip level 1 of a 16x16 source"},
    };
    for (size_t i = 0; i < sizeof cases / sizeof cases[0]; i++) {
        mcmtl_handle dst = make_texture(MTLPixelFormatBGRA8Unorm, MTLTextureType2D, cases[i].dw, cases[i].dh, 1, MTLTextureUsageRenderTarget,
                                        "present dst");
        @autoreleasepool {
            id<MTLCommandBuffer> cb = [g_dev->queue commandBuffer];
            failure = nil;
            BOOL ok = mcmtl_core_encode_present(g_dev, cb, MCMTL_TEXTURE(dst), MCMTL_TEXTURE(cases[i].source), cases[i].level, &failure);
            CHECK(ok, "%s: %s", cases[i].what, failure ? failure.UTF8String : "-");
            commit_and_wait(cb, cases[i].what);
        }
        NSData *out = read_texture(MCMTL_TEXTURE(dst), 0, cases[i].dw, cases[i].dh, 4);
        int bad = check_present(out.bytes, cases[i].dw, cases[i].dh, cases[i].expected, 8, 8);
        CHECK(bad == 0, "present %s: %d wrong pixels", cases[i].what, bad);
        mcmtl_release(g_device, dst);
    }
    CHECK(mcmtl_core_present_pipeline(g_dev, MTLPixelFormatBGRA8Unorm, NULL) == a, "present PSO reused");
    /* Invalid sources are refused before anything is encoded. */
    mcmtl_handle cube = make_texture(MTLPixelFormatRGBA8Unorm, MTLTextureTypeCube, 8, 8, 1, MTLTextureUsageShaderRead, NULL);
    mcmtl_handle depthTex = make_texture(MTLPixelFormatDepth32Float, MTLTextureType2D, 8, 8, 1,
                                         MTLTextureUsageShaderRead | MTLTextureUsageRenderTarget, NULL);
    mcmtl_handle dst = make_texture(MTLPixelFormatBGRA8Unorm, MTLTextureType2D, 8, 8, 1, MTLTextureUsageRenderTarget, NULL);
    @autoreleasepool {
        id<MTLCommandBuffer> cb = [g_dev->queue commandBuffer];
        failure = nil;
        CHECK(!mcmtl_core_encode_present(g_dev, cb, MCMTL_TEXTURE(dst), MCMTL_TEXTURE(src), 1, &failure) && failure, "level past the mip count");
        CHECK(!mcmtl_core_encode_present(g_dev, cb, MCMTL_TEXTURE(dst), MCMTL_TEXTURE(cube), 0, &failure), "cube source");
        CHECK(!mcmtl_core_encode_present(g_dev, cb, MCMTL_TEXTURE(dst), MCMTL_TEXTURE(depthTex), 0, &failure), "depth source");
        CHECK(!mcmtl_core_encode_present(g_dev, cb, nil, MCMTL_TEXTURE(src), 0, &failure), "no destination");
        commit_and_wait(cb, "empty after refused presents");
    }
    mcmtl_handle all[] = {src, mipped, cube, depthTex, dst};
    mcmtl_release_batch(g_device, all, 5);
}

static void test_surface(void) {
    CAMetalLayer *layer = [CAMetalLayer layer];
    mcmtl_handle s = 5;
    CHECK_RC(mcmtl_surface_create(0, (__bridge void *)layer, &s), MCMTL_E_INVALID_ARGUMENT, "surface on device 0");
    CHECK(s == 0, "no handle");
    CHECK_RC(mcmtl_surface_create(g_device, NULL, &s), MCMTL_E_INVALID_ARGUMENT, "NULL layer");
    NSObject *notALayer = [NSObject new];
    CHECK_RC(mcmtl_surface_create(g_device, (__bridge void *)notALayer, &s), MCMTL_E_SURFACE, "not a CAMetalLayer");
    CHECK_RC(mcmtl_surface_create(g_device, (__bridge void *)layer, NULL), MCMTL_E_INVALID_ARGUMENT, "NULL out");
    CHECK_RC(mcmtl_surface_create(g_device, (__bridge void *)layer, &s), MCMTL_OK, "surface_create");
    if (!s) return;
    MCMTLSurface *surface = MCMTL_SURFACE(s);
    CHECK(layer.device == g_dev->device && layer.pixelFormat == MTLPixelFormatBGRA8Unorm && layer.framebufferOnly && layer.opaque &&
              !layer.wantsExtendedDynamicRangeContent && layer.maximumDrawableCount == 3 && layer.allowsNextDrawableTimeout &&
              !layer.presentsWithTransaction && layer.displaySyncEnabled, "layer properties (spec 6.2)");
    CFStringRef colorspace = layer.colorspace ? CFCopyDescription(layer.colorspace) : NULL;
    CHECK(colorspace && [(__bridge NSString *)colorspace rangeOfString:@"sRGB"].location != NSNotFound, "sRGB colorspace (%s)",
          colorspace ? [(__bridge NSString *)colorspace UTF8String] : "nil");
    if (colorspace) CFRelease(colorspace);
    CHECK(surface->owner == g_dev && surface->layer == layer && surface->drawable == nil && surface->configFlags == MCMTL_SURFACE_FLAG_DISPLAY_SYNC,
          "surface fields");

    struct { mcmtl_surface_config c; int32_t expected; const char *what; } bad[] = {
        {{.structSize = sizeof(mcmtl_surface_config), .width = 0, .height = 10}, MCMTL_E_INVALID_ARGUMENT, "width 0"},
        {{.structSize = sizeof(mcmtl_surface_config), .width = 16385, .height = 10}, MCMTL_E_INVALID_ARGUMENT, "width 16385"},
        {{.structSize = sizeof(mcmtl_surface_config), .width = 10, .height = 10, .flags = 2}, MCMTL_E_INVALID_ARGUMENT, "unknown flag"},
        {{.structSize = sizeof(mcmtl_surface_config), .width = 10, .height = 10, .maximumDrawableCount = 4}, MCMTL_E_INVALID_ARGUMENT, "4 drawables"},
        {{.structSize = sizeof(mcmtl_surface_config), .width = 10, .height = 10, .maximumDrawableCount = 1}, MCMTL_E_INVALID_ARGUMENT, "1 drawable"},
        {{.structSize = 12, .width = 10, .height = 10}, MCMTL_E_ABI_MISMATCH, "structSize"},
    };
    for (size_t i = 0; i < sizeof bad / sizeof bad[0]; i++) CHECK_RC(mcmtl_surface_configure(s, &bad[i].c), bad[i].expected, bad[i].what);
    CHECK_RC(mcmtl_surface_configure(s, NULL), MCMTL_E_INVALID_ARGUMENT, "NULL config");
    /* IMMEDIATE: with the mailbox (CONTRACT.md 16.4, MCMTL_MAILBOX != 0, the default) the native side presents without
     * blocking and configures MCMTL_MAILBOX_DRAWABLES (default 3) drawables whatever Java asked for; with MCMTL_MAILBOX=0
     * Java's count (2) applies as before. FIFO turns the mailbox off and keeps the count. */
    const BOOL mailboxEnv = !getenv("MCMTL_MAILBOX") || atoi(getenv("MCMTL_MAILBOX")) != 0;
    const NSUInteger mailboxDrawables = getenv("MCMTL_MAILBOX_DRAWABLES") && atoi(getenv("MCMTL_MAILBOX_DRAWABLES")) == 2 ? 2 : 3;
    const NSUInteger immediateDrawables = getenv("MCMTL_DRAWABLES") ? (NSUInteger)atoi(getenv("MCMTL_DRAWABLES"))
                                                                    : (mailboxEnv ? mailboxDrawables : 2);
    mcmtl_surface_config config = {.structSize = sizeof config, .width = 640, .height = 360, .flags = 0, .maximumDrawableCount = 2};
    CHECK_RC(mcmtl_surface_configure(s, &config), MCMTL_OK, "configure IMMEDIATE");
    CHECK(layer.drawableSize.width == 640 && layer.drawableSize.height == 360 && !layer.displaySyncEnabled &&
              layer.maximumDrawableCount == immediateDrawables && surface->configuredWidth == 640 && surface->configuredHeight == 360 &&
              surface->configFlags == 0, "configured layer (drawables %lu, expected %lu)", (unsigned long)layer.maximumDrawableCount,
          (unsigned long)immediateDrawables);
    CHECK(mcmtl_mailbox_active(surface) == mailboxEnv, "IMMEDIATE: mailbox %s", mailboxEnv ? "on" : "off");
    config = (mcmtl_surface_config){.structSize = sizeof config, .width = 8, .height = 8, .flags = MCMTL_SURFACE_FLAG_DISPLAY_SYNC};
    CHECK_RC(mcmtl_surface_configure(s, &config), MCMTL_OK, "configure FIFO");
    CHECK(layer.displaySyncEnabled && layer.maximumDrawableCount == immediateDrawables && layer.drawableSize.width == 8,
          "FIFO keeps the drawable count");
    CHECK(!mcmtl_mailbox_active(surface), "FIFO: mailbox off (blocking nextDrawable)");
    {
        mcmtl_mailbox_stats m;
        mcmtl_mailbox_get_stats(surface, &m);
        CHECK(m.parked == 0, "FIFO: no drawable parked by the acquirer");
    }
    CHECK_RC(mcmtl_surface_present(s), MCMTL_OK, "present without a drawable");
    CHECK_RC(mcmtl_surface_present(0), MCMTL_E_INVALID_ARGUMENT, "present(0)");
    CHECK_RC(mcmtl_surface_configure(0, &config), MCMTL_E_INVALID_ARGUMENT, "configure(0)");

    /* Acquire + present pass + presentDrawable on an offscreen layer (framebufferOnly off to read it back). */
    layer.framebufferOnly = NO;
    uint8_t rgba[8 * 8 * 4];
    fill_pattern(rgba, 8, 8, 5);
    mcmtl_handle src = make_texture(MTLPixelFormatRGBA8Unorm, MTLTextureType2D, 8, 8, 1, MTLTextureUsageShaderRead, "surface src");
    upload_texture(MCMTL_TEXTURE(src), 0, 0, 8, 8, 4, rgba);
    for (int round = 0; round < 2; round++) {
        if (round == 1) {
            [CATransaction begin];
            [CATransaction setDisableActions:YES];
            layer.drawableSize = CGSizeMake(10, 6);   /* behind configure's back: next drawable is "resized" */
            [CATransaction commit];
        }
        uint32_t w = 0, h = 0;
        NSData *pixels = nil;
        uint32_t flags = 0;
        @autoreleasepool {
            id<MTLCommandBuffer> cb = [g_dev->queue commandBuffer];
            NSString *failure = nil;
            flags = mcmtl_core_surface_encode_present(surface, cb, MCMTL_TEXTURE(src), 0, &w, &h, &failure);
            CHECK(flags & MCMTL_CORE_PRESENT_ENCODED, "round %d: present encoded (flags 0x%x, %s)", round, flags,
                  failure ? failure.UTF8String : "-");
            if (flags & MCMTL_CORE_PRESENT_ENCODED) {
                id<MTLBuffer> out = [g_dev->device newBufferWithLength:w * h * 4 options:MTLResourceStorageModeShared];
                encode_readback(cb, surface->drawable.texture, 0, w, h, 4, out);
                commit_and_wait(cb, "surface present");
                pixels = [NSData dataWithBytes:out.contents length:w * h * 4];
            } else {
                commit_and_wait(cb, "surface present (not encoded)");
            }
        }
        CHECK(round == 0 ? (w == 8 && h == 8 && !(flags & MCMTL_CORE_DRAWABLE_RESIZED))
                         : (w == 10 && h == 6 && (flags & MCMTL_CORE_DRAWABLE_RESIZED)),
              "round %d: drawable %ux%u flags 0x%x", round, w, h, flags);
        CHECK(surface->drawable != nil && surface->presentedFrames == (uint32_t)round + 1, "round %d: drawable held until present()", round);
        if (pixels) CHECK(check_present(pixels.bytes, w, h, rgba, 8, 8) == 0, "round %d: presented image", round);
        CHECK_RC(mcmtl_surface_present(s), MCMTL_OK, "present");
        CHECK(surface->drawable == nil, "present() releases the drawable");
    }
    mcmtl_release(g_device, src);

    __weak MCMTLSurface *weakSurface = surface;
    surface = nil;
    CHECK_RC(mcmtl_surface_destroy(s), MCMTL_OK, "surface_destroy");
    CHECK(WAIT_NIL(weakSurface), "surface leaked");
    CHECK(layer.device == g_dev->device, "the layer itself is untouched by destroy");
    CHECK_RC(mcmtl_surface_destroy(0), MCMTL_E_INVALID_ARGUMENT, "destroy(0)");
}

/* ---------------------------------------------------------------------------------------------- */
/* Release                                                                                          */
/* ---------------------------------------------------------------------------------------------- */

static void test_release(void) {
    CHECK_RC(mcmtl_release_batch(g_device, NULL, 1), MCMTL_E_INVALID_ARGUMENT, "release_batch(NULL, 1)");
    CHECK_RC(mcmtl_release_batch(g_device, NULL, 0), MCMTL_OK, "release_batch(NULL, 0)");
    CHECK_RC(mcmtl_release(g_device, 0), MCMTL_OK, "release(0)");
    mcmtl_handle buffer = make_buffer(64, USAGE_UNIFORM, 0, NULL, NULL);
    mcmtl_handle texture = make_texture(MTLPixelFormatR8Unorm, MTLTextureType2D, 4, 4, 1, MTLTextureUsageShaderRead, NULL);
    mcmtl_handle pool = 0;
    mcmtl_query_pool_create(g_device, 4, &pool);
    /* Unique source: Metal returns the SAME cached MTLFunction for a source compiled earlier (e.g. the
     * screenquad handle still held by main), which would stay alive for an unrelated reason. */
    char unique[1024];
    snprintf(unique, sizeof unique, VALID_VS_TEMPLATE, (int)arc4random_uniform(1000000) + 1000);
    mcmtl_handle function = make_shader(MCMTL_STAGE_VERTEX, unique, "release");
    __weak id wb = nil, wt = nil, wp = nil, wf = nil;
    @autoreleasepool {
        wb = MCMTL_OBJ(buffer);
        wt = MCMTL_OBJ(texture);
        wp = MCMTL_OBJ(pool);
        wf = MCMTL_OBJ(function);
    }
    mcmtl_handle batch[] = {buffer, 0, texture, pool, 0, function};
    CHECK_RC(mcmtl_release_batch(g_device, batch, 6), MCMTL_OK, "release_batch with zeros");
    CHECK(WAIT_NIL(wb), "release_batch frees the buffer");
    CHECK(WAIT_NIL(wt), "release_batch frees the texture");
    CHECK(WAIT_NIL(wp), "release_batch frees the query pool");
    CHECK(WAIT_NIL(wf), "release_batch frees the shader function");
}

/* ---------------------------------------------------------------------------------------------- */

int main(void) {
    __weak MCMTLDevice *weakDevice = nil;
    __weak id<MTLSamplerState> weakCachedSampler = nil;
    @autoreleasepool {
        CHECK(mcmtl_abi_version() == MCMTL_ABI_VERSION, "abi version");
        test_device_errors();

        mcmtl_device_desc desc = {.structSize = sizeof desc, .flags = MCMTL_DEVICE_FLAG_DEBUG_LABELS, .framesInFlight = 2, .label = "core_test"};
        int32_t rc = mcmtl_device_create(&desc, &g_device);
        CHECK(rc == MCMTL_OK && g_device, "device_create: %d %s", rc, mcmtl_last_error());
        if (!g_device) return 1;
        g_dev = MCMTL_DEVICE(g_device);
        weakDevice = g_dev;

        test_device_info();
        test_timeline_and_timestamps();
        test_buffers();
        test_textures_and_views();
        mcmtl_handle screenquad = make_shader(MCMTL_STAGE_VERTEX, CORE_MSL_SCREENQUAD_VS, "core/screenquad");
        test_texel_views(screenquad);
        test_samplers(screenquad);
        test_query_pools(screenquad);
        test_shaders();
        mcmtl_handle terrainVs = make_shader(MCMTL_STAGE_VERTEX, CORE_MSL_TERRAIN_VS, "core/terrain vs");
        mcmtl_handle terrainFs = make_shader(MCMTL_STAGE_FRAGMENT, CORE_MSL_TERRAIN_FS, "core/terrain fs");
        test_pipelines(terrainVs, terrainFs, screenquad);
        test_blend_and_masks(screenquad);
        test_terrain_render(terrainVs, terrainFs);
        test_depth_only_render(screenquad);
        test_present_pass();
        test_surface();
        test_release();
        mcmtl_handle shaders[] = {screenquad, terrainVs, terrainFs};
        mcmtl_release_batch(g_device, shaders, 3);

        /* A sampler state stays cached (dedup) until the device goes away. */
        mcmtl_sampler_desc sd = sampler_desc(MTLSamplerAddressModeMirrorRepeat, 1, 2, 8, 0, 3.0f);
        mcmtl_handle sampler = make_sampler(&sd);
        weakCachedSampler = MCMTL_SAMPLER(sampler);
        mcmtl_release(g_device, sampler);
        @autoreleasepool {
            CHECK(weakCachedSampler != nil, "released sampler stays in the device cache");
        }

        char messages[4096];
        uint32_t length = 0;
        mcmtl_device_copy_messages(g_device, messages, sizeof messages, &length);
        printf("device messages (%u):\n  %s\n", message_count(), length ? messages : "(none)");
        uint64_t completed = mcmtl_timeline_completed(g_device);
        printf("timeline: submitted %llu, completed %llu\n", (unsigned long long)mcmtl_status_load_u64(&g_dev->status->submittedValue),
               (unsigned long long)completed);
        g_dev = nil;
        CHECK_RC(mcmtl_device_destroy(g_device), MCMTL_OK, "device_destroy");
        g_device = 0;
    }
    CHECK(WAIT_NIL(weakDevice), "device context leaked");
    CHECK(WAIT_NIL(weakCachedSampler), "sampler cache outlived the device");
    printf("%s (%d checks, %d failure%s)\n", failures ? "core_test FAILED" : "core_test PASSED", checks, failures, failures == 1 ? "" : "s");
    return failures ? 1 : 0;
}
