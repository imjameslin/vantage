/*
 * mcmetal_common.m -- shared infrastructure of libmcmetal (owner: architect; FROZEN, fully implemented).
 *
 * Contents: ABI helpers, thread-local last error, mcmtl_probe, the @implementation of the wrapper classes,
 * device-context basics, the status page / completion tracking, commit helpers, waits, the mcmtl_submit
 * guard (validation + signal-value consumption around the I2 decoder), the debug-message ring and the
 * depth-stencil state cache. See mcmetal_internal.h for the contracts.
 */
#import "mcmetal_internal.h"
#import "mcmetal_frametrace.h"   /* CONTRACT.md 16.R5-B: two hook sites, inert until the trace ring is enabled */

#include <stdio.h>
#include <string.h>
#include <time.h>

/* ---------------------------------------------------------------------------------------------- */
/* Wrapper class implementations (plain data holders; ARC releases the strong ivars)               */
/* ---------------------------------------------------------------------------------------------- */

@implementation MCMTLDevice
- (void)dealloc {
    /* Completion handlers retain the device, so no handler can touch the page after this point. */
    free(status);
    status = NULL;
}
@end

@implementation MCMTLPipeline
@end

@implementation MCMTLSurface
@end

@implementation MCMTLQueryPool
@end

/* ---------------------------------------------------------------------------------------------- */
/* Thread-local last error                                                                         */
/* ---------------------------------------------------------------------------------------------- */

static _Thread_local char mcmtl_last_error_buffer[MCMTL_LAST_ERROR_CAPACITY];

void mcmtl_clear_error(void) {
    mcmtl_last_error_buffer[0] = '\0';
}

void mcmtl_set_error(const char *format, ...) {
    va_list args;
    va_start(args, format);
    vsnprintf(mcmtl_last_error_buffer, sizeof mcmtl_last_error_buffer, format, args);
    va_end(args);
}

void mcmtl_set_error_ns(NSString *message) {
    const char *utf8 = message ? message.UTF8String : NULL;
    strlcpy(mcmtl_last_error_buffer, utf8 ? utf8 : "(no message)", sizeof mcmtl_last_error_buffer);
}

void mcmtl_set_error_exception(NSException *exception) {
    mcmtl_set_error("Objective-C exception %s: %s",
                    exception.name ? exception.name.UTF8String : "?",
                    exception.reason ? exception.reason.UTF8String : "(no reason)");
}

void mcmtl_set_error_nserror(const char *context, NSError *error) {
    NSString *description = error ? error.localizedDescription : @"(no NSError)";
    mcmtl_set_error("%s: %s (domain %s, code %ld)", context ? context : "error",
                    description ? description.UTF8String : "?",
                    error && error.domain ? error.domain.UTF8String : "-",
                    error ? (long)error.code : 0L);
}

MCMTL_API const char *mcmtl_last_error(void) {
    return mcmtl_last_error_buffer;
}

/* ---------------------------------------------------------------------------------------------- */
/* ABI helpers                                                                                      */
/* ---------------------------------------------------------------------------------------------- */

MCMTL_API uint32_t mcmtl_abi_version(void) {
    return MCMTL_ABI_VERSION;
}

MCMTL_API uint32_t mcmtl_abi_struct_size(uint32_t structId) {
    switch (structId) {
        case MCMTL_STRUCT_PROBE_INFO:               return (uint32_t)sizeof(mcmtl_probe_info);
        case MCMTL_STRUCT_DEVICE_DESC:              return (uint32_t)sizeof(mcmtl_device_desc);
        case MCMTL_STRUCT_DEVICE_INFO:              return (uint32_t)sizeof(mcmtl_device_info);
        case MCMTL_STRUCT_STATUS_PAGE:              return (uint32_t)sizeof(mcmtl_status_page);
        case MCMTL_STRUCT_BUFFER_DESC:              return (uint32_t)sizeof(mcmtl_buffer_desc);
        case MCMTL_STRUCT_BUFFER_RESULT:            return (uint32_t)sizeof(mcmtl_buffer_result);
        case MCMTL_STRUCT_TEXTURE_DESC:             return (uint32_t)sizeof(mcmtl_texture_desc);
        case MCMTL_STRUCT_TEXTURE_VIEW_DESC:        return (uint32_t)sizeof(mcmtl_texture_view_desc);
        case MCMTL_STRUCT_TEXEL_VIEW_DESC:          return (uint32_t)sizeof(mcmtl_texel_view_desc);
        case MCMTL_STRUCT_SAMPLER_DESC:             return (uint32_t)sizeof(mcmtl_sampler_desc);
        case MCMTL_STRUCT_OBJECT_RESULT:            return (uint32_t)sizeof(mcmtl_object_result);
        case MCMTL_STRUCT_SHADER_DESC:              return (uint32_t)sizeof(mcmtl_shader_desc);
        case MCMTL_STRUCT_VERTEX_ATTRIBUTE:         return (uint32_t)sizeof(mcmtl_vertex_attribute);
        case MCMTL_STRUCT_VERTEX_LAYOUT:            return (uint32_t)sizeof(mcmtl_vertex_layout);
        case MCMTL_STRUCT_COLOR_TARGET:             return (uint32_t)sizeof(mcmtl_color_target);
        case MCMTL_STRUCT_PIPELINE_DESC:            return (uint32_t)sizeof(mcmtl_pipeline_desc);
        case MCMTL_STRUCT_SURFACE_CONFIG:           return (uint32_t)sizeof(mcmtl_surface_config);
        case MCMTL_STRUCT_SUBMIT_INFO:              return (uint32_t)sizeof(mcmtl_submit_info);
        case MCMTL_STRUCT_SUBMIT_RESULT:            return (uint32_t)sizeof(mcmtl_submit_result);
        case MCMTL_STRUCT_CMD_HEADER:               return (uint32_t)sizeof(mcmtl_cmd_header);
        case MCMTL_STRUCT_CMD_EMPTY:                return (uint32_t)sizeof(mcmtl_cmd_empty);
        case MCMTL_STRUCT_CMD_BEGIN_PASS:           return (uint32_t)sizeof(mcmtl_cmd_begin_pass);
        case MCMTL_STRUCT_CMD_COLOR_ATTACHMENT:     return (uint32_t)sizeof(mcmtl_cmd_color_attachment);
        case MCMTL_STRUCT_CMD_END_PASS:             return (uint32_t)sizeof(mcmtl_cmd_end_pass);
        case MCMTL_STRUCT_CMD_CLEAR_TEXTURE:        return (uint32_t)sizeof(mcmtl_cmd_clear_texture);
        case MCMTL_STRUCT_CMD_CLEAR_REGION:         return (uint32_t)sizeof(mcmtl_cmd_clear_region);
        case MCMTL_STRUCT_CMD_COPY_BUFFER:          return (uint32_t)sizeof(mcmtl_cmd_copy_buffer);
        case MCMTL_STRUCT_CMD_COPY_BUFFER_TEXTURE:  return (uint32_t)sizeof(mcmtl_cmd_copy_buffer_texture);
        case MCMTL_STRUCT_CMD_COPY_TEXTURE:         return (uint32_t)sizeof(mcmtl_cmd_copy_texture);
        case MCMTL_STRUCT_CMD_PRESENT:              return (uint32_t)sizeof(mcmtl_cmd_present);
        case MCMTL_STRUCT_CMD_INIT_TEXTURE:         return (uint32_t)sizeof(mcmtl_cmd_init_texture);
        case MCMTL_STRUCT_CMD_TIMESTAMP:            return (uint32_t)sizeof(mcmtl_cmd_timestamp);
        case MCMTL_STRUCT_CMD_PUSH_DEBUG_GROUP:     return (uint32_t)sizeof(mcmtl_cmd_push_debug_group);
        case MCMTL_STRUCT_CMD_SET_PIPELINE:         return (uint32_t)sizeof(mcmtl_cmd_set_pipeline);
        case MCMTL_STRUCT_CMD_SET_BUFFER:           return (uint32_t)sizeof(mcmtl_cmd_set_buffer);
        case MCMTL_STRUCT_CMD_SET_TEXTURE:          return (uint32_t)sizeof(mcmtl_cmd_set_texture);
        case MCMTL_STRUCT_CMD_CLEAR_BINDING:        return (uint32_t)sizeof(mcmtl_cmd_clear_binding);
        case MCMTL_STRUCT_CMD_SET_VERTEX_BUFFER:    return (uint32_t)sizeof(mcmtl_cmd_set_vertex_buffer);
        case MCMTL_STRUCT_CMD_SET_INDEX_BUFFER:     return (uint32_t)sizeof(mcmtl_cmd_set_index_buffer);
        case MCMTL_STRUCT_CMD_SET_SCISSOR:          return (uint32_t)sizeof(mcmtl_cmd_set_scissor);
        case MCMTL_STRUCT_CMD_PUSH_CONSTANTS:       return (uint32_t)sizeof(mcmtl_cmd_push_constants);
        case MCMTL_STRUCT_CMD_DRAW:                 return (uint32_t)sizeof(mcmtl_cmd_draw);
        case MCMTL_STRUCT_CMD_DRAW_INDEXED:         return (uint32_t)sizeof(mcmtl_cmd_draw_indexed);
        case MCMTL_STRUCT_CMD_DRAW_INDIRECT:        return (uint32_t)sizeof(mcmtl_cmd_draw_indirect);
        case MCMTL_STRUCT_CMD_UPSCALE:              return (uint32_t)sizeof(mcmtl_cmd_upscale);
        default:                                    return 0;
    }
}

/* ---------------------------------------------------------------------------------------------- */
/* Probe                                                                                            */
/* ---------------------------------------------------------------------------------------------- */

uint32_t mcmtl_query_family_mask(id<MTLDevice> mtlDevice) {
    if (!mtlDevice) return 0;
    uint32_t mask = 0;
    if ([mtlDevice supportsFamily:MTLGPUFamilyApple7])  mask |= MCMTL_FAMILY_APPLE7;
    if ([mtlDevice supportsFamily:MTLGPUFamilyApple8])  mask |= MCMTL_FAMILY_APPLE8;
    if ([mtlDevice supportsFamily:MTLGPUFamilyApple9])  mask |= MCMTL_FAMILY_APPLE9;
    if ([mtlDevice supportsFamily:MTLGPUFamilyApple10]) mask |= MCMTL_FAMILY_APPLE10;
    if ([mtlDevice supportsFamily:MTLGPUFamilyMac2])    mask |= MCMTL_FAMILY_MAC2;
    if ([mtlDevice supportsFamily:MTLGPUFamilyMetal3])  mask |= MCMTL_FAMILY_METAL3;
    if ([mtlDevice supportsFamily:MTLGPUFamilyMetal4])  mask |= MCMTL_FAMILY_METAL4;
    return mask;
}

MCMTL_API int32_t mcmtl_probe(mcmtl_probe_info *outInfo) {
    MCMTL_TRY_BEGIN
        mcmtl_clear_error();
        if (!outInfo) {
            mcmtl_set_error("mcmtl_probe: outInfo is NULL");
            return MCMTL_E_INVALID_ARGUMENT;
        }
        if (outInfo->structSize != sizeof(mcmtl_probe_info)) {
            mcmtl_set_error("mcmtl_probe: structSize %u != %zu", outInfo->structSize, sizeof(mcmtl_probe_info));
            return MCMTL_E_ABI_MISMATCH;
        }
        memset(outInfo, 0, sizeof *outInfo);
        outInfo->structSize = (uint32_t)sizeof(mcmtl_probe_info);

        NSOperatingSystemVersion os = NSProcessInfo.processInfo.operatingSystemVersion;
        outInfo->osMajor = (uint32_t)os.majorVersion;
        outInfo->osMinor = (uint32_t)os.minorVersion;
        outInfo->osPatch = (uint32_t)os.patchVersion;

        id<MTLDevice> device = MTLCreateSystemDefaultDevice();
        if (!device) {
            mcmtl_set_error("MTLCreateSystemDefaultDevice() returned nil");
            return MCMTL_E_NO_DEVICE;
        }
        outInfo->familyMask = mcmtl_query_family_mask(device);
        outInfo->hasUnifiedMemory = device.hasUnifiedMemory ? 1u : 0u;
        outInfo->maxBufferLength = (uint64_t)device.maxBufferLength;
        outInfo->recommendedMaxWorkingSetSize = device.recommendedMaxWorkingSetSize;
        strlcpy(outInfo->deviceName, device.name ? device.name.UTF8String : "", sizeof outInfo->deviceName);
        return MCMTL_OK;
    MCMTL_TRY_END(MCMTL_E_OBJC_EXCEPTION)
}

/* ---------------------------------------------------------------------------------------------- */
/* Device context basics                                                                            */
/* ---------------------------------------------------------------------------------------------- */

MCMTLDevice *mcmtl_device_context_create(id<MTLDevice> mtlDevice, NSString *queueLabel) {
    if (!mtlDevice) {
        mcmtl_set_error("mcmtl_device_context_create: no MTLDevice");
        return nil;
    }
    MCMTLDevice *dev = [MCMTLDevice new];
    dev->device = mtlDevice;
    dev->queue = [mtlDevice newCommandQueueWithMaxCommandBufferCount:64];
    if (!dev->queue) {
        mcmtl_set_error("newCommandQueueWithMaxCommandBufferCount: returned nil");
        return nil;
    }
    dev->queue.label = queueLabel ? queueLabel : @"mcmetal";
    dev->event = [mtlDevice newSharedEvent];
    if (!dev->event) {
        mcmtl_set_error("newSharedEvent returned nil");
        return nil;
    }
    dev->event.label = @"mcmetal timeline";
    dev->event.signaledValue = 0;
    dev->status = calloc(1, sizeof(mcmtl_status_page));
    if (!dev->status) {
        mcmtl_set_error("status page allocation failed");
        return nil;
    }
    dev->messageLock = OS_UNFAIR_LOCK_INIT;
    dev->messages = [NSMutableArray arrayWithCapacity:MCMTL_MAX_DEBUG_MESSAGES];
    dev->cacheLock = OS_UNFAIR_LOCK_INIT;
    dev->depthStencilStates = [NSMutableDictionary dictionary];
    dev->completionLock = OS_UNFAIR_LOCK_INIT;
    dev->completionPending = [NSMutableIndexSet indexSet];
    return dev;
}

/* ---------------------------------------------------------------------------------------------- */
/* Debug-message ring                                                                               */
/* ---------------------------------------------------------------------------------------------- */

void mcmtl_device_message(MCMTLDevice *dev, const char *format, ...) {
    if (!dev || !format) return;
    char buffer[1024];
    va_list args;
    va_start(args, format);
    vsnprintf(buffer, sizeof buffer, format, args);
    va_end(args);
    for (char *p = buffer; *p; p++) {
        if (*p == '\n' || *p == '\r') *p = ' ';
    }
    NSString *message = [NSString stringWithUTF8String:buffer];
    if (!message) message = @"(undecodable message)";
    os_unfair_lock_lock(&dev->messageLock);
    [dev->messages addObject:message];
    while (dev->messages.count > MCMTL_MAX_DEBUG_MESSAGES) {
        [dev->messages removeObjectAtIndex:0];
    }
    os_unfair_lock_unlock(&dev->messageLock);
    if (dev->status) {
        __atomic_fetch_add(&dev->status->messageCount, 1u, __ATOMIC_RELEASE);
    }
    if (dev->flags & MCMTL_DEVICE_FLAG_VALIDATION) {
        NSLog(@"[mcmetal] %@", message);
    }
}

MCMTL_API int32_t mcmtl_device_copy_messages(mcmtl_handle device, char *buffer, uint32_t capacity, uint32_t *outLength) {
    MCMTL_TRY_BEGIN
        mcmtl_clear_error();
        if (!device || (!buffer && capacity > 0)) {
            mcmtl_set_error("mcmtl_device_copy_messages: invalid argument");
            return MCMTL_E_INVALID_ARGUMENT;
        }
        MCMTLDevice *dev = MCMTL_DEVICE(device);
        NSString *joined;
        os_unfair_lock_lock(&dev->messageLock);
        joined = [dev->messages componentsJoinedByString:@"\n"];
        os_unfair_lock_unlock(&dev->messageLock);
        uint32_t written = 0;
        if (capacity > 0) {
            const char *utf8 = joined.UTF8String;
            size_t length = utf8 ? strlen(utf8) : 0;
            if (length > capacity - 1) length = capacity - 1;
            if (length > 0) memcpy(buffer, utf8, length);
            buffer[length] = '\0';
            written = (uint32_t)length;
        }
        if (outLength) *outLength = written;
        return MCMTL_OK;
    MCMTL_TRY_END(MCMTL_E_OBJC_EXCEPTION)
}

/* ---------------------------------------------------------------------------------------------- */
/* Completion tracking and waits                                                                    */
/* ---------------------------------------------------------------------------------------------- */

/* Marks `value` complete and advances status->completedValue over the contiguous prefix. Values that
 * complete out of order wait in dev->completionPending (no window limit: the prefix never skips a value
 * whose work may still be running). Returns the new prefix. */
static uint64_t mcmtl_mark_value_complete(MCMTLDevice *dev, uint64_t value) {
    mcmtl_status_page *sp = dev->status;
    if (!sp || value == 0) return sp ? mcmtl_status_load_u64(&sp->completedValue) : 0;
    os_unfair_lock_lock(&dev->completionLock);
    uint64_t completed = __atomic_load_n(&sp->completedValue, __ATOMIC_RELAXED);
    if (value == completed + 1) {
        completed = value;
        NSMutableIndexSet *pending = dev->completionPending;
        while (pending.count > 0 && [pending containsIndex:(NSUInteger)(completed + 1)]) {
            [pending removeIndex:(NSUInteger)(completed + 1)];
            completed++;
        }
        mcmtl_status_store_u64(&sp->completedValue, completed);
    } else if (value > completed + 1) {
        if (value < (uint64_t)NSNotFound) {
            [dev->completionPending addIndex:(NSUInteger)value];
        }
    }
    os_unfair_lock_unlock(&dev->completionLock);
    return completed;
}

/* Raises the event to `prefix` (a contiguous completed prefix, so every value <= prefix is done) if the
 * event is behind: wakes waiters for values that completed without a GPU signal (failed command buffers,
 * CPU completion). Never raises it past a value whose work may still run. */
static void mcmtl_raise_event_to_prefix(MCMTLDevice *dev, uint64_t prefix) {
    if (prefix > 0 && dev->event.signaledValue < prefix) dev->event.signaledValue = prefix;
}

static BOOL mcmtl_is_device_loss_error(NSInteger code) {
    switch (code) {
        case MTLCommandBufferErrorInternal:
        case MTLCommandBufferErrorTimeout:
        case MTLCommandBufferErrorPageFault:
        case MTLCommandBufferErrorAccessRevoked:
        case MTLCommandBufferErrorDeviceRemoved:
            return YES;
        default:
            return NO;
    }
}

/* Completion-handler part shared by timed and untimed command buffers: error bookkeeping + GPU time.
 * Returns YES if the command buffer failed. */
static BOOL mcmtl_record_completion(MCMTLDevice *dev, id<MTLCommandBuffer> done, uint64_t value, BOOL timed) {
    mcmtl_status_page *sp = dev->status;
    BOOL failed = done.status == MTLCommandBufferStatusError;
    if (failed) {
        NSError *error = done.error;
        NSInteger code = error ? error.code : MTLCommandBufferErrorInternal;
        __atomic_store_n(&sp->lastErrorCode, (int32_t)code, __ATOMIC_RELEASE);
        mcmtl_status_store_u64(&sp->lastErrorValue, value);
        __atomic_fetch_add(&sp->errorCount, 1u, __ATOMIC_RELEASE);
        if (mcmtl_is_device_loss_error(code)) {
            __atomic_store_n(&sp->deviceLost, 1u, __ATOMIC_RELEASE);
        }
        NSString *description = error ? error.localizedDescription : @"unknown error";
        mcmtl_device_message(dev, "%s command buffer for timeline value %llu failed: %s (MTLCommandBufferError %ld)",
                             timed ? "signalling" : "early-commit", (unsigned long long)value,
                             description ? description.UTF8String : "?", (long)code);
    }
    CFTimeInterval start = done.GPUStartTime;
    CFTimeInterval end = done.GPUEndTime;
    if (end > start) {
        mcmtl_status_store_u64(&sp->lastGpuTimeNs, (uint64_t)((end - start) * 1e9));
    }
    if (mcmtl_ft_on()) mcmtl_ft_on_completed(dev, value, start, end, failed, timed);
    return failed;
}

void mcmtl_track_command_buffer(MCMTLDevice *dev, id<MTLCommandBuffer> commandBuffer, uint64_t value) {
    if (!dev || !commandBuffer) return;
    [commandBuffer addCompletedHandler:^(id<MTLCommandBuffer> done) {
        @autoreleasepool {
            if (!dev->status) return;
            BOOL failed = mcmtl_record_completion(dev, done, value, YES);
            uint64_t prefix = mcmtl_mark_value_complete(dev, value);
            /* A failed command buffer's signal may never execute, and a prefix that jumped over
             * CPU-completed values is ahead of the last GPU signal: wake their waiters. */
            if (failed || prefix > value) mcmtl_raise_event_to_prefix(dev, prefix);
        }
    }];
}

void mcmtl_track_untimed_command_buffer(MCMTLDevice *dev, id<MTLCommandBuffer> commandBuffer, uint64_t attributedValue) {
    if (!dev || !commandBuffer) return;
    [commandBuffer addCompletedHandler:^(id<MTLCommandBuffer> done) {
        @autoreleasepool {
            if (!dev->status) return;
            (void)mcmtl_record_completion(dev, done, attributedValue, NO);
        }
    }];
}

void mcmtl_commit_signaled(MCMTLDevice *dev, id<MTLCommandBuffer> commandBuffer, uint64_t value) {
    [commandBuffer encodeSignalEvent:dev->event value:value];
    mcmtl_track_command_buffer(dev, commandBuffer, value);
    [commandBuffer commit];
    /* Published only after a successful commit: submittedValue >= value <=> the value is consumed. */
    mcmtl_status_raise_u64(&dev->status->submittedValue, value);
}

void mcmtl_commit_untimed(MCMTLDevice *dev, id<MTLCommandBuffer> commandBuffer, uint64_t attributedValue) {
    mcmtl_track_untimed_command_buffer(dev, commandBuffer, attributedValue);
    [commandBuffer commit];
}

void mcmtl_complete_value_on_cpu(MCMTLDevice *dev, uint64_t value) {
    if (!dev || !dev->status || value == 0) return;
    mcmtl_status_raise_u64(&dev->status->submittedValue, value);
    uint64_t prefix = mcmtl_mark_value_complete(dev, value);
    mcmtl_raise_event_to_prefix(dev, prefix);
}

void mcmtl_submit_abort(MCMTLDevice *dev, uint64_t signalValue) {
    if (!dev) return;
    dev->currentCommandBuffer = nil;
    if (signalValue != 0 && dev->status && mcmtl_status_load_u64(&dev->status->submittedValue) < signalValue) {
        mcmtl_complete_value_on_cpu(dev, signalValue);
    }
}

static uint64_t mcmtl_monotonic_ns(void) {
    return clock_gettime_nsec_np(CLOCK_UPTIME_RAW);
}

int32_t mcmtl_wait_completed(MCMTLDevice *dev, uint64_t value, uint64_t timeoutNs) {
    if (!dev || !dev->status) {
        mcmtl_set_error("mcmtl_wait_completed: no device");
        return MCMTL_E_INVALID_ARGUMENT;
    }
    mcmtl_status_page *sp = dev->status;
    if (mcmtl_status_load_u64(&sp->completedValue) >= value) return MCMTL_OK;
    if (value > mcmtl_status_load_u64(&sp->submittedValue)) {
        mcmtl_set_error("wait for timeline value %llu that was never committed (submitted %llu)",
                        (unsigned long long)value, (unsigned long long)mcmtl_status_load_u64(&sp->submittedValue));
        return MCMTL_E_INVALID_ARGUMENT;
    }
    const uint64_t start = mcmtl_monotonic_ns();
    const uint64_t sliceMs = 10;
    for (;;) {
        if (mcmtl_status_load_u64(&sp->completedValue) >= value) return MCMTL_OK;
        if (__atomic_load_n(&sp->deviceLost, __ATOMIC_ACQUIRE)) {
            mcmtl_set_error("GPU device lost while waiting for timeline value %llu (MTLCommandBufferError %d)",
                            (unsigned long long)value, __atomic_load_n(&sp->lastErrorCode, __ATOMIC_ACQUIRE));
            return MCMTL_E_DEVICE_LOST;
        }
        /* The GPU executed the signal of `value` (and, queue order, of everything before it): its work no
         * longer runs. Do not wait for the completion handler that advances completedValue. The event is
         * only ever raised by GPU signals or to a contiguous completed prefix, so this is never early. */
        if (dev->event.signaledValue >= value) return MCMTL_OK;
        uint64_t elapsed = mcmtl_monotonic_ns() - start;
        if (timeoutNs != MCMTL_WAIT_FOREVER && elapsed >= timeoutNs) {
            mcmtl_set_error("timed out after %llu ns waiting for timeline value %llu (completed %llu)",
                            (unsigned long long)elapsed, (unsigned long long)value,
                            (unsigned long long)mcmtl_status_load_u64(&sp->completedValue));
            return MCMTL_E_TIMEOUT;
        }
        uint64_t waitMs = sliceMs;
        if (timeoutNs != MCMTL_WAIT_FOREVER) {
            uint64_t remainingMs = (timeoutNs - elapsed + 999999ull) / 1000000ull;
            if (remainingMs < waitMs) waitMs = remainingMs;
        }
        @autoreleasepool {
            [dev->event waitUntilSignaledValue:value timeoutMS:waitMs];
        }
    }
}

/* Optional extension (not in the frozen ABI; CONTRACT.md 16.R8-1): busy-poll until `value` completed (status page or
 * the event's GPU-signaled value), at most `spinNs`. MCMTL_OK when reached, MCMTL_E_TIMEOUT when the budget ran out
 * (no error string: the caller then takes the blocking mcmtl_timeline_wait), MCMTL_E_DEVICE_LOST as the blocking wait.
 * Keeps the render thread runnable while the GPU finishes the previous frame (scheduler / clock placement). */
MCMTL_API int32_t mcmtl_ext_timeline_spin(mcmtl_handle device, uint64_t value, uint64_t spinNs, uint32_t mode) {
    MCMTL_TRY_BEGIN
        if (!device) return MCMTL_E_INVALID_ARGUMENT;
        MCMTLDevice *dev = MCMTL_DEVICE(device);
        if (!dev->status) return MCMTL_E_INVALID_ARGUMENT;
        mcmtl_status_page *sp = dev->status;
        if (value > mcmtl_status_load_u64(&sp->submittedValue)) return MCMTL_E_INVALID_ARGUMENT;
        const uint64_t start = mcmtl_monotonic_ns();
        uint32_t n = 0;
        for (;;) {
            if (mcmtl_status_load_u64(&sp->completedValue) >= value) return MCMTL_OK;
            if (dev->event.signaledValue >= value) return MCMTL_OK;
            if (__atomic_load_n(&sp->deviceLost, __ATOMIC_ACQUIRE)) return MCMTL_E_DEVICE_LOST;
            if ((++n & 3u) == 0u && mcmtl_monotonic_ns() - start >= spinNs) return MCMTL_E_TIMEOUT;
            if (mode == 1u) __builtin_arm_wfe(); else __builtin_arm_yield();
        }
    MCMTL_TRY_END(MCMTL_E_OBJC_EXCEPTION)
}

/* ---------------------------------------------------------------------------------------------- */
/* mcmtl_submit: guard around the decoder (CONTRACT.md 4.2, mcmetal.h consumption rule)            */
/* ---------------------------------------------------------------------------------------------- */

static int32_t mcmtl_submit_validate(MCMTLDevice *dev, const mcmtl_submit_info *info, mcmtl_submit_result *result) {
    if (info->structSize != sizeof(mcmtl_submit_info)) {
        mcmtl_set_error("mcmtl_submit: info->structSize %u != %zu", info->structSize, sizeof(mcmtl_submit_info));
        return MCMTL_E_ABI_MISMATCH;
    }
    if (!result) {
        mcmtl_set_error("mcmtl_submit: outResult is NULL");
        return MCMTL_E_INVALID_ARGUMENT;
    }
    if (result->structSize != sizeof(mcmtl_submit_result)) {
        mcmtl_set_error("mcmtl_submit: outResult->structSize %u != %zu", result->structSize, sizeof(mcmtl_submit_result));
        return MCMTL_E_ABI_MISMATCH;
    }
    const uint32_t known = MCMTL_SUBMIT_FLAG_COMMIT | MCMTL_SUBMIT_FLAG_FRAME_END | MCMTL_SUBMIT_FLAG_ASYNC;
    if (info->flags & ~known) {
        mcmtl_set_error("mcmtl_submit: unknown flags 0x%x", info->flags & ~known);
        return MCMTL_E_INVALID_ARGUMENT;
    }
    if (info->flags & MCMTL_SUBMIT_FLAG_ASYNC) {
        mcmtl_set_error("mcmtl_submit: MCMTL_SUBMIT_FLAG_ASYNC is reserved (B16)");
        return MCMTL_E_UNSUPPORTED;
    }
    if ((info->initStreamLength % MCMTL_STREAM_ALIGNMENT) != 0 || (info->initStreamLength && !info->initStream) ||
        (info->streamLength % MCMTL_STREAM_ALIGNMENT) != 0 || (info->streamLength && !info->stream)) {
        mcmtl_set_error("mcmtl_submit: stream lengths must be multiples of 16 with non-NULL pointers (init %llu, main %llu)",
                        (unsigned long long)info->initStreamLength, (unsigned long long)info->streamLength);
        return MCMTL_E_BAD_STREAM;
    }
    if (info->flags & MCMTL_SUBMIT_FLAG_COMMIT) {
        uint64_t submitted = mcmtl_status_load_u64(&dev->status->submittedValue);
        if (info->signalValue != submitted + 1) {
            mcmtl_set_error("mcmtl_submit: signalValue %llu != submittedValue + 1 = %llu (Java and native timelines diverged)",
                            (unsigned long long)info->signalValue, (unsigned long long)(submitted + 1));
            return MCMTL_E_INVALID_ARGUMENT;
        }
    }
    return MCMTL_OK;
}

MCMTL_API int32_t mcmtl_submit(mcmtl_handle device, const mcmtl_submit_info *info, mcmtl_submit_result *outResult) {
    MCMTL_TRY_BEGIN
        mcmtl_clear_error();
        const uint64_t startNs = mcmtl_monotonic_ns();
        if (!device || !info) {
            mcmtl_set_error("mcmtl_submit: %s is NULL (no signal value can be consumed)", device ? "info" : "device");
            return MCMTL_E_INVALID_ARGUMENT;
        }
        MCMTLDevice *dev = MCMTL_DEVICE(device);
        if (!dev->status) {
            mcmtl_set_error("mcmtl_submit: device has no status page");
            return MCMTL_E_INVALID_ARGUMENT;
        }
        const BOOL commit = (info->flags & MCMTL_SUBMIT_FLAG_COMMIT) != 0;
        const uint64_t value = info->signalValue;
        const BOOL resultValid = outResult && outResult->structSize == sizeof(mcmtl_submit_result);

        int32_t rc = mcmtl_submit_validate(dev, info, outResult);
        int ftTraced = 0;
        if (rc == MCMTL_OK) {
            memset(outResult, 0, sizeof *outResult);
            outResult->structSize = (uint32_t)sizeof(mcmtl_submit_result);
            if (commit && mcmtl_ft_on()) ftTraced = mcmtl_ft_submit_begin(dev, value, info->flags);
            @try {
                rc = mcmtl_decoder_submit(dev, info, outResult);
            } @catch (NSException *exception) {
                mcmtl_set_error_exception(exception);
                rc = MCMTL_E_OBJC_EXCEPTION;
            } @catch (id thrown) {
                mcmtl_set_error("Objective-C object thrown by the decoder: %s", [[thrown description] UTF8String]);
                rc = MCMTL_E_OBJC_EXCEPTION;
            }
        }

        BOOL aborted = NO;
        if (commit && mcmtl_status_load_u64(&dev->status->submittedValue) < value) {
            if (rc == MCMTL_OK) {
                mcmtl_set_error("mcmtl_submit: decoder returned MCMTL_OK without consuming signal value %llu",
                                (unsigned long long)value);
                rc = MCMTL_E_INTERNAL;
            }
            mcmtl_device_message(dev, "submit for timeline value %llu aborted (%s); value completed on the CPU",
                                 (unsigned long long)value, mcmtl_last_error());
            mcmtl_submit_abort(dev, value);
            aborted = YES;
        }
        if (rc != MCMTL_OK) {
            dev->currentCommandBuffer = nil;
        }
        if (resultValid) {
            if (aborted) outResult->flags &= ~MCMTL_SUBMIT_RESULT_FLAG_COMMITTED;
            if (__atomic_load_n(&dev->status->deviceLost, __ATOMIC_ACQUIRE)) {
                outResult->flags |= MCMTL_SUBMIT_RESULT_FLAG_DEVICE_LOST;
            }
            outResult->completedValue = mcmtl_status_load_u64(&dev->status->completedValue);
            outResult->cpuNanos = mcmtl_monotonic_ns() - startNs;
        }
        if (ftTraced) mcmtl_ft_submit_end(dev, value, resultValid ? outResult : NULL);
        return rc;
    MCMTL_TRY_END(MCMTL_E_OBJC_EXCEPTION)
}

/* ---------------------------------------------------------------------------------------------- */
/* Depth-stencil state cache                                                                        */
/* ---------------------------------------------------------------------------------------------- */

id<MTLDepthStencilState> mcmtl_device_depth_stencil_state(MCMTLDevice *dev, uint32_t compareFunction, BOOL depthWriteEnabled) {
    if (!dev) return nil;
    NSNumber *key = @((compareFunction << 1) | (depthWriteEnabled ? 1u : 0u));
    os_unfair_lock_lock(&dev->cacheLock);
    id<MTLDepthStencilState> state = dev->depthStencilStates[key];
    os_unfair_lock_unlock(&dev->cacheLock);
    if (state) return state;
    MTLDepthStencilDescriptor *descriptor = [MTLDepthStencilDescriptor new];
    descriptor.depthCompareFunction = (MTLCompareFunction)compareFunction;
    descriptor.depthWriteEnabled = depthWriteEnabled;
    id<MTLDepthStencilState> created = [dev->device newDepthStencilStateWithDescriptor:descriptor];
    if (!created) return nil;
    os_unfair_lock_lock(&dev->cacheLock);
    state = dev->depthStencilStates[key];
    if (!state) {
        dev->depthStencilStates[key] = created;
        state = created;
    }
    os_unfair_lock_unlock(&dev->cacheLock);
    return state;
}
