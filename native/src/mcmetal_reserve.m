/*
 * mcmetal_reserve.m -- TASK E pregrow (docs/metal/CONTRACT.md 16.R5-E): make a freshly created buffer GPU-resident off
 * the render thread.
 *
 * NOT part of the frozen ABI (mcmetal.h is unchanged, still ABI 2): `mcmtl_ext_buffer_make_resident` is an optional
 * extension symbol. MetalHeapReserve resolves it by name when it first needs it and falls back to touching the pages on
 * the CPU if it is missing, so Java and native can never disagree about it.
 *
 * Why: on M5 / macOS 27 `newBufferWithLength:` of a 128 MiB Shared buffer takes ~0.02 ms, but the first command buffer
 * that references it pays ~3.3-4.4 ms between commit and completion (pages zero-filled, wired and mapped for the GPU);
 * that was the in-frame terrain heap hitch. A CPU page touch removes only ~2/3 of it (~1.4 ms remain). A 4-byte fill on a
 * separate command queue makes the whole buffer resident (~3.2-4.5 ms on the calling thread), after which the main
 * queue's first use costs nothing extra; measured with a 1 ms frame loop on the main queue, the side submission did not
 * delay it (worst 0.19-0.25 ms against a 0.17 ms mean). Probes: tools/heap_residency_probe.m, heap_residency_probe2.m.
 */
#import "mcmetal_internal.h"
#import <objc/runtime.h>

static char mcmtl_reserve_queue_key;

/* Makes `buffer` resident: a fill of zeros over its first 4 bytes (a fresh buffer's content; contents are undefined
 * by the API anyway), committed on a side command queue the MCMTLDevice owns (created on first use, associated with
 * the device object, released with it) and waited for on the calling thread. Thread-safe: queue creation is
 * @synchronized on the device, MTLCommandQueue / MTLCommandBuffer creation and commit are thread-safe, and the device's
 * main queue, shared event, status page and decoder are never touched. The caller guarantees that no other command
 * uses `buffer` (MetalHeapReserve calls it right after creating the buffer, before Java ever sees it). Blocks for the
 * residency work. MCMTL_E_DEVICE_LOST if the side command buffer did not complete. */
MCMTL_API int32_t mcmtl_ext_buffer_make_resident(mcmtl_handle device, mcmtl_handle buffer) {
    MCMTL_TRY_BEGIN
        mcmtl_clear_error();
        if (!device || !buffer) {
            mcmtl_set_error("mcmtl_ext_buffer_make_resident: %s is 0", device ? "buffer" : "device");
            return MCMTL_E_INVALID_ARGUMENT;
        }
        MCMTLDevice *dev = MCMTL_DEVICE(device);
        id<MTLBuffer> target = MCMTL_BUFFER(buffer);
        if (target.length < 4) {
            return MCMTL_OK;
        }
        id<MTLCommandQueue> queue;
        @synchronized (dev) {
            queue = objc_getAssociatedObject(dev, &mcmtl_reserve_queue_key);
            if (!queue) {
                queue = [dev->device newCommandQueue];
                if (!queue) {
                    mcmtl_set_error("mcmtl_ext_buffer_make_resident: newCommandQueue returned nil");
                    return MCMTL_E_OUT_OF_MEMORY;
                }
                queue.label = @"mcmetal heap reserve";
                objc_setAssociatedObject(dev, &mcmtl_reserve_queue_key, queue, OBJC_ASSOCIATION_RETAIN);
            }
        }
        id<MTLCommandBuffer> cb = [queue commandBuffer];
        id<MTLBlitCommandEncoder> blit = [cb blitCommandEncoder];
        [blit fillBuffer:target range:NSMakeRange(0, 4) value:0];
        [blit endEncoding];
        [cb commit];
        [cb waitUntilCompleted];
        if (cb.status != MTLCommandBufferStatusCompleted) {
            mcmtl_set_error_nserror("mcmtl_ext_buffer_make_resident: side command buffer failed", cb.error);
            return MCMTL_E_DEVICE_LOST;
        }
        return MCMTL_OK;
    MCMTL_TRY_END(MCMTL_E_OBJC_EXCEPTION)
}
