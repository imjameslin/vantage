/*
 * mcmetal_device.m -- device creation/destruction, device info, timeline exports, I1 core state.
 * OWNER: I1 (native-core). Contract: docs/metal/CONTRACT.md sections 3 and 5; mcmetal.h.
 *
 * mcmtl_device_create builds the MCMTLDevice through the architect's mcmtl_device_context_create
 * (queue, shared event, status page, message ring, depth-stencil cache), fills the I1 fields (flags,
 * family/feature masks, MSL version, texel alignment, timestamp counter set), attaches the I1 core state
 * (mcmetal_core.h) and lets the decoder (I2) create its dummies. mcmtl_device_destroy waits for the last
 * committed timeline value, discards a never-committed command buffer, shuts the decoder down and drops
 * the handle (completion handlers retain the device, so the status page outlives them).
 */
#import "mcmetal_core.h"

#import <MetalFX/MetalFX.h>
#include <mach/mach_time.h>
#include <objc/runtime.h>
#include <stdio.h>
#include <string.h>
#include <time.h>

/* Bounded wait for in-flight work in mcmtl_device_destroy. */
#define MCMTL_DESTROY_WAIT_NS 5000000000ull

/* ---------------------------------------------------------------------------------------------- */
/* I1 core state (associated object)                                                               */
/* ---------------------------------------------------------------------------------------------- */

@implementation MCMTLCoreState
@end

static char mcmtl_core_state_key;
static os_unfair_lock mcmtl_core_state_create_lock = OS_UNFAIR_LOCK_INIT;

static uint64_t mcmtl_abs_diff(uint64_t a, uint64_t b) {
    return a > b ? a - b : b - a;
}

static MCMTLCoreState *mcmtl_core_state_new(MCMTLDevice *dev) {
    MCMTLCoreState *core = [MCMTLCoreState new];
    core->lock = OS_UNFAIR_LOCK_INIT;
    core->samplers = [NSMutableDictionary dictionary];
    core->presentPipelines = [NSMutableDictionary dictionary];

    /* Texel buffers: allocations of USAGE_UNIFORM_TEXEL_BUFFER buffers are rounded up to the largest
     * texture-buffer alignment of any format a texel view may use, so alignUp(length, formatAlignment)
     * never exceeds the allocation (spec 3.7). */
    static const MTLPixelFormat texelFormats[] = {
        MTLPixelFormatR8Unorm, MTLPixelFormatR8Sint, MTLPixelFormatR8Uint, MTLPixelFormatR16Float,
        MTLPixelFormatR16Sint, MTLPixelFormatR16Uint, MTLPixelFormatRG8Unorm, MTLPixelFormatR32Float,
        MTLPixelFormatR32Sint, MTLPixelFormatR32Uint, MTLPixelFormatRG16Float, MTLPixelFormatRGBA8Unorm,
        MTLPixelFormatRGBA8Sint, MTLPixelFormatRGBA8Uint, MTLPixelFormatRG32Float, MTLPixelFormatRGBA16Float,
        MTLPixelFormatRGBA16Sint, MTLPixelFormatRGBA16Uint, MTLPixelFormatRGBA32Float,
        MTLPixelFormatRGBA32Sint, MTLPixelFormatRGBA32Uint,
    };
    uint32_t maxAlignment = 16;
    for (size_t i = 0; i < sizeof texelFormats / sizeof texelFormats[0]; i++) {
        NSUInteger a = [dev->device minimumTextureBufferAlignmentForPixelFormat:texelFormats[i]];
        if (a > maxAlignment && a <= 65536) maxAlignment = (uint32_t)a;
    }
    core->maxTexelBufferAlignment = maxAlignment;

    /* Timestamp clock domain. Apple GPUs report sampleTimestamps and classic counter samples in
     * nanoseconds (spec 2.13, measured on M5); detect mach ticks defensively and convert them. */
    mach_timebase_info_data_t timebase = {0, 0};
    mach_timebase_info(&timebase);
    core->timebaseNumer = timebase.numer ? timebase.numer : 1;
    core->timebaseDenom = timebase.denom ? timebase.denom : 1;
    MTLTimestamp cpu = 0, gpu = 0;
    [dev->device sampleTimestamps:&cpu gpuTimestamp:&gpu];
    uint64_t ticksNow = mach_absolute_time();
    uint64_t nsNow = clock_gettime_nsec_np(CLOCK_UPTIME_RAW);
    core->timestampsInTicks = core->timebaseNumer != core->timebaseDenom &&
                              mcmtl_abs_diff(cpu, ticksNow) < mcmtl_abs_diff(cpu, nsNow);
    return core;
}

MCMTLCoreState *mcmtl_core_state(MCMTLDevice *dev) {
    if (!dev) return nil;
    MCMTLCoreState *core = objc_getAssociatedObject(dev, &mcmtl_core_state_key);
    if (core) return core;
    MCMTLCoreState *created = mcmtl_core_state_new(dev);   /* outside the lock: may call into Metal */
    os_unfair_lock_lock(&mcmtl_core_state_create_lock);
    core = objc_getAssociatedObject(dev, &mcmtl_core_state_key);
    if (!core) {
        objc_setAssociatedObject(dev, &mcmtl_core_state_key, created, OBJC_ASSOCIATION_RETAIN);
        core = created;
    }
    os_unfair_lock_unlock(&mcmtl_core_state_create_lock);
    return core;
}

void mcmtl_core_state_release(MCMTLDevice *dev) {
    if (!dev) return;
    os_unfair_lock_lock(&mcmtl_core_state_create_lock);
    objc_setAssociatedObject(dev, &mcmtl_core_state_key, nil, OBJC_ASSOCIATION_RETAIN);
    os_unfair_lock_unlock(&mcmtl_core_state_create_lock);
}

uint64_t mcmtl_core_timestamp_ns(MCMTLCoreState *core, uint64_t value) {
    if (!core || !core->timestampsInTicks) return value;
    return (uint64_t)(((__uint128_t)value * core->timebaseNumer) / core->timebaseDenom);
}

/* ---------------------------------------------------------------------------------------------- */
/* Capabilities                                                                                     */
/* ---------------------------------------------------------------------------------------------- */

static id<MTLCounterSet> mcmtl_find_timestamp_counter_set(id<MTLDevice> device) {
    for (id<MTLCounterSet> set in device.counterSets) {
        if ([set.name isEqualToString:MTLCommonCounterSetTimestamp]) return set;
    }
    return nil;
}

static uint32_t mcmtl_query_feature_mask(id<MTLDevice> device, id<MTLCounterSet> timestampSet) {
    uint32_t mask = 0;
    if ([device supportsCounterSampling:MTLCounterSamplingPointAtStageBoundary]) {
        mask |= MCMTL_FEATURE_COUNTER_SAMPLING_STAGE_BOUNDARY;
    }
    if (timestampSet) mask |= MCMTL_FEATURE_TIMESTAMP_COUNTER_SET;
    if ([MTLFXSpatialScalerDescriptor supportsDevice:device]) mask |= MCMTL_FEATURE_METALFX_SPATIAL;
    if (device.hasUnifiedMemory) mask |= MCMTL_FEATURE_UNIFIED_MEMORY;
    /* MCMTL_FEATURE_MTL4_DECODER: only when the device runs the Metal 4 decoder (M3); never in M1. */
    return mask;
}

/* "Metal (macOS 27.0 26A428), MSL 3.2, classic" */
static void mcmtl_format_driver_info(MCMTLDevice *dev, char *out, size_t capacity) {
    NSOperatingSystemVersion os = NSProcessInfo.processInfo.operatingSystemVersion;
    NSString *versionString = NSProcessInfo.processInfo.operatingSystemVersionString;
    char build[32] = "";
    NSRange r = [versionString rangeOfString:@"Build "];
    if (r.location != NSNotFound) {
        NSString *tail = [versionString substringFromIndex:r.location + r.length];
        NSRange close = [tail rangeOfString:@")"];
        if (close.location != NSNotFound) tail = [tail substringToIndex:close.location];
        strlcpy(build, tail.UTF8String ? tail.UTF8String : "", sizeof build);
    }
    char version[32];
    if (os.patchVersion) {
        snprintf(version, sizeof version, "%ld.%ld.%ld", (long)os.majorVersion, (long)os.minorVersion, (long)os.patchVersion);
    } else {
        snprintf(version, sizeof version, "%ld.%ld", (long)os.majorVersion, (long)os.minorVersion);
    }
    uint32_t msl = dev->mslLanguageVersion;
    snprintf(out, capacity, "Metal%s (macOS %s%s%s), MSL %u.%u, classic",
             (dev->familyMask & MCMTL_FAMILY_METAL4) ? " 4" : "", version, build[0] ? " " : "", build,
             msl >> 16, msl & 0xFFFFu);
}

/* ---------------------------------------------------------------------------------------------- */
/* Exports                                                                                          */
/* ---------------------------------------------------------------------------------------------- */

MCMTL_API int32_t mcmtl_device_create(const mcmtl_device_desc *desc, mcmtl_handle *outDevice) {
    MCMTL_TRY_BEGIN
        mcmtl_clear_error();
        if (outDevice) *outDevice = 0;
        if (!desc || !outDevice) {
            mcmtl_set_error("mcmtl_device_create: %s is NULL", desc ? "outDevice" : "desc");
            return MCMTL_E_INVALID_ARGUMENT;
        }
        if (desc->structSize != sizeof(mcmtl_device_desc)) {
            mcmtl_set_error("mcmtl_device_create: structSize %u != %zu", desc->structSize, sizeof(mcmtl_device_desc));
            return MCMTL_E_ABI_MISMATCH;
        }
        const uint32_t known = MCMTL_DEVICE_FLAG_DEBUG_LABELS | MCMTL_DEVICE_FLAG_VALIDATION |
                               MCMTL_DEVICE_FLAG_MTL4 | MCMTL_DEVICE_FLAG_UNTRACKED;
        if (desc->flags & ~known) {
            mcmtl_set_error("mcmtl_device_create: unknown flags 0x%x", desc->flags & ~known);
            return MCMTL_E_INVALID_ARGUMENT;
        }
        if (desc->flags & MCMTL_DEVICE_FLAG_MTL4) {
            mcmtl_set_error("mcmtl_device_create: MCMTL_DEVICE_FLAG_MTL4 (Metal 4 decoder) is not available in M1");
            return MCMTL_E_UNSUPPORTED;
        }
        if (desc->flags & MCMTL_DEVICE_FLAG_UNTRACKED) {
            mcmtl_set_error("mcmtl_device_create: MCMTL_DEVICE_FLAG_UNTRACKED (untracked hazards) is not available in M1");
            return MCMTL_E_UNSUPPORTED;
        }

        id<MTLDevice> device = MTLCreateSystemDefaultDevice();
        if (!device) {
            mcmtl_set_error("MTLCreateSystemDefaultDevice() returned nil");
            return MCMTL_E_NO_DEVICE;
        }
        uint32_t familyMask = mcmtl_query_family_mask(device);
        if (!(familyMask & MCMTL_FAMILY_APPLE7)) {
            mcmtl_set_error("Metal device '%s' does not support MTLGPUFamilyApple7 (families 0x%x)",
                            device.name ? device.name.UTF8String : "?", familyMask);
            return MCMTL_E_NO_DEVICE;
        }

        NSString *label = desc->label ? [NSString stringWithUTF8String:desc->label] : nil;
        MCMTLDevice *dev = mcmtl_device_context_create(device, label.length ? label : @"mcmetal");
        if (!dev) return MCMTL_E_NO_DEVICE;   /* last error set by the context */

        id<MTLCounterSet> timestampSet = mcmtl_find_timestamp_counter_set(device);
        dev->flags = desc->flags;
        dev->familyMask = familyMask;
        dev->featureMask = mcmtl_query_feature_mask(device, timestampSet);
        dev->mslLanguageVersion = (uint32_t)MTLLanguageVersion3_2;
        NSUInteger texelAlignment = [device minimumTextureBufferAlignmentForPixelFormat:MTLPixelFormatR8Sint];
        dev->minTexelBufferAlignment = (uint32_t)(texelAlignment ? texelAlignment : 16);
        dev->timestampCounterSet = timestampSet;
        (void)mcmtl_core_state(dev);

        if (!mcmtl_decoder_device_init(dev)) {
            if (mcmtl_last_error()[0] == '\0') mcmtl_set_error("mcmtl_decoder_device_init failed");
            mcmtl_decoder_device_shutdown(dev);
            mcmtl_core_state_release(dev);
            return MCMTL_E_INTERNAL;
        }
        *outDevice = mcmtl_handle_retain(dev);
        return MCMTL_OK;
    MCMTL_TRY_END(MCMTL_E_OBJC_EXCEPTION)
}

MCMTL_API int32_t mcmtl_device_destroy(mcmtl_handle device) {
    MCMTL_TRY_BEGIN
        mcmtl_clear_error();
        if (!device) {
            mcmtl_set_error("mcmtl_device_destroy: device is 0");
            return MCMTL_E_INVALID_ARGUMENT;
        }
        MCMTLDevice *dev = MCMTL_DEVICE(device);
        /* Never-committed work is discarded (CONTRACT.md 5.7: there is no final submit). */
        dev->currentCommandBuffer = nil;
        if (dev->status) {
            uint64_t submitted = mcmtl_status_load_u64(&dev->status->submittedValue);
            if (submitted > 0) {
                int32_t rc = mcmtl_wait_completed(dev, submitted, MCMTL_DESTROY_WAIT_NS);
                if (rc != MCMTL_OK) {
                    /* Device loss or a hung GPU: release anyway. In-flight command buffers retain their
                     * resources and their completion handlers retain the device context. */
                    mcmtl_device_message(dev, "mcmtl_device_destroy: GPU not idle (%s); releasing anyway", mcmtl_last_error());
                }
            }
        }
        mcmtl_decoder_device_shutdown(dev);
        mcmtl_core_state_release(dev);
        mcmtl_handle_release(device);
        mcmtl_clear_error();
        return MCMTL_OK;
    MCMTL_TRY_END(MCMTL_E_OBJC_EXCEPTION)
}

MCMTL_API int32_t mcmtl_device_get_info(mcmtl_handle device, mcmtl_device_info *outInfo) {
    MCMTL_TRY_BEGIN
        mcmtl_clear_error();
        if (!device || !outInfo) {
            mcmtl_set_error("mcmtl_device_get_info: %s is NULL", device ? "outInfo" : "device");
            return MCMTL_E_INVALID_ARGUMENT;
        }
        if (outInfo->structSize != sizeof(mcmtl_device_info)) {
            mcmtl_set_error("mcmtl_device_get_info: structSize %u != %zu", outInfo->structSize, sizeof(mcmtl_device_info));
            return MCMTL_E_ABI_MISMATCH;
        }
        MCMTLDevice *dev = MCMTL_DEVICE(device);
        id<MTLDevice> mtl = dev->device;
        memset(outInfo, 0, sizeof *outInfo);
        outInfo->structSize = (uint32_t)sizeof(mcmtl_device_info);
        outInfo->familyMask = dev->familyMask;
        outInfo->maxBufferLength = (uint64_t)mtl.maxBufferLength;
        outInfo->recommendedMaxWorkingSetSize = mtl.recommendedMaxWorkingSetSize;
        outInfo->statusPage = (uint64_t)(uintptr_t)dev->status;
        outInfo->maxTextureSize2D = 16384;     /* Apple7+ 2D limit */
        outInfo->maxColorAttachments = MCMTL_MAX_COLOR_ATTACHMENTS;
        outInfo->maxSamplerAnisotropy = 16;
        outInfo->minUniformOffsetAlignment = 16; /* spec 7 [P]: constant-buffer offsets, std140 alignment */
        outInfo->minTexelBufferAlignment = dev->minTexelBufferAlignment;
        outInfo->mslLanguageVersion = dev->mslLanguageVersion;
        NSOperatingSystemVersion os = NSProcessInfo.processInfo.operatingSystemVersion;
        outInfo->osMajor = (uint32_t)os.majorVersion;
        outInfo->osMinor = (uint32_t)os.minorVersion;
        outInfo->osPatch = (uint32_t)os.patchVersion;
        outInfo->featureMask = dev->featureMask;
        strlcpy(outInfo->name, mtl.name ? mtl.name.UTF8String : "", sizeof outInfo->name);
        mcmtl_format_driver_info(dev, outInfo->driverInfo, sizeof outInfo->driverInfo);
        return MCMTL_OK;
    MCMTL_TRY_END(MCMTL_E_OBJC_EXCEPTION)
}

MCMTL_API uint64_t mcmtl_timeline_completed(mcmtl_handle device) {
    MCMTL_TRY_BEGIN
        if (!device) return 0;
        MCMTLDevice *dev = MCMTL_DEVICE(device);
        return dev->status ? mcmtl_status_load_u64(&dev->status->completedValue) : 0;
    MCMTL_TRY_END(0)
}

MCMTL_API int32_t mcmtl_timeline_wait(mcmtl_handle device, uint64_t value, uint64_t timeoutNs) {
    MCMTL_TRY_BEGIN
        mcmtl_clear_error();
        if (!device) {
            mcmtl_set_error("mcmtl_timeline_wait: device is 0");
            return MCMTL_E_INVALID_ARGUMENT;
        }
        return mcmtl_wait_completed(MCMTL_DEVICE(device), value, timeoutNs);
    MCMTL_TRY_END(MCMTL_E_OBJC_EXCEPTION)
}

MCMTL_API int32_t mcmtl_device_sample_timestamps(mcmtl_handle device, uint64_t *outCpuNs, uint64_t *outGpuNs) {
    MCMTL_TRY_BEGIN
        mcmtl_clear_error();
        if (outCpuNs) *outCpuNs = 0;
        if (outGpuNs) *outGpuNs = 0;
        if (!device || !outCpuNs || !outGpuNs) {
            mcmtl_set_error("mcmtl_device_sample_timestamps: NULL argument");
            return MCMTL_E_INVALID_ARGUMENT;
        }
        MCMTLDevice *dev = MCMTL_DEVICE(device);
        MTLTimestamp cpu = 0, gpu = 0;
        [dev->device sampleTimestamps:&cpu gpuTimestamp:&gpu];
        MCMTLCoreState *core = mcmtl_core_state(dev);
        *outCpuNs = mcmtl_core_timestamp_ns(core, cpu);
        *outGpuNs = mcmtl_core_timestamp_ns(core, gpu);
        return MCMTL_OK;
    MCMTL_TRY_END(MCMTL_E_OBJC_EXCEPTION)
}
