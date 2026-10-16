// TASK E probe (docs/metal/CONTRACT.md 16.R5-E): cost of a fresh 128 MiB Shared MTLBuffer on first GPU use, untouched vs
// CPU pre-touched (one write per 16 KiB page) vs GPU pre-touched (separate queue). Prints wall times of create / touch /
// commit / wait and GPU times. Standalone (no game); build and run holding the GPU lock:
//   xcrun clang -O2 -fobjc-arc -framework Foundation -framework Metal tools/heap_residency_probe.m -o build/heap_residency_probe
//   lockf -k /tmp/mc-gpu.lock build/heap_residency_probe
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#include <mach/mach_time.h>

static double now_ms(void) {
    static mach_timebase_info_data_t tb;
    if (tb.denom == 0) mach_timebase_info(&tb);
    return (double)mach_absolute_time() * tb.numer / tb.denom / 1e6;
}

static void use_on_gpu(id<MTLCommandQueue> q, id<MTLBuffer> src, id<MTLBuffer> dst, NSUInteger offsets, const char *what) {
    double t0 = now_ms();
    id<MTLCommandBuffer> cb = [q commandBuffer];
    id<MTLBlitCommandEncoder> blit = [cb blitCommandEncoder];
    NSUInteger step = dst.length / offsets;
    for (NSUInteger i = 0; i < offsets; i++) {
        [blit copyFromBuffer:src sourceOffset:0 toBuffer:dst destinationOffset:i * step size:4096];
    }
    [blit endEncoding];
    double t1 = now_ms();
    [cb commit];
    double t2 = now_ms();
    [cb waitUntilCompleted];
    double t3 = now_ms();
    printf("    %-34s encode %.3f ms, commit %.3f ms, commit->done %.3f ms, GPU %.3f ms\n", what, t1 - t0, t2 - t1, t3 - t2,
           (cb.GPUEndTime - cb.GPUStartTime) * 1e3);
}

int main(void) {
    @autoreleasepool {
        id<MTLDevice> dev = MTLCreateSystemDefaultDevice();
        id<MTLCommandQueue> q = [dev newCommandQueue];
        id<MTLCommandQueue> side = [dev newCommandQueue];
        id<MTLBuffer> src = [dev newBufferWithLength:4096 options:MTLResourceStorageModeShared];
        memset(src.contents, 7, 4096);
        const NSUInteger size = 128u << 20;
        // warm-up
        use_on_gpu(q, src, src, 1, "warm-up");
        for (int round = 0; round < 2; round++) {
            printf("round %d\n", round);
            for (int variant = 0; variant < 4; variant++) {
                double t0 = now_ms();
                id<MTLBuffer> b = [dev newBufferWithLength:size options:MTLResourceStorageModeShared];
                double t1 = now_ms();
                const char *name = "untouched";
                if (variant == 1) {
                    name = "CPU pre-touched";
                    for (NSUInteger o = 0; o < size; o += 16384) ((volatile char *)b.contents)[o] = 0;
                } else if (variant == 2) {
                    name = "GPU pre-touched (side queue)";
                    id<MTLCommandBuffer> cb = [side commandBuffer];
                    id<MTLBlitCommandEncoder> blit = [cb blitCommandEncoder];
                    [blit fillBuffer:b range:NSMakeRange(0, 4) value:0];
                    [blit endEncoding];
                    [cb commit];
                    [cb waitUntilCompleted];
                } else if (variant == 3) {
                    name = "CPU+GPU pre-touched";
                    for (NSUInteger o = 0; o < size; o += 16384) ((volatile char *)b.contents)[o] = 0;
                    id<MTLCommandBuffer> cb = [side commandBuffer];
                    id<MTLBlitCommandEncoder> blit = [cb blitCommandEncoder];
                    [blit fillBuffer:b range:NSMakeRange(0, 4) value:0];
                    [blit endEncoding];
                    [cb commit];
                    [cb waitUntilCompleted];
                }
                double t2 = now_ms();
                printf("  %s: create %.3f ms, pre-touch %.3f ms\n", name, t1 - t0, t2 - t1);
                use_on_gpu(q, src, b, 1, "first use (1 x 4 KiB at 0)");
                use_on_gpu(q, src, b, 64, "second use (64 x 4 KiB spread)");
                use_on_gpu(q, src, b, 64, "third use (same)");
                double r0 = now_ms();
                b = nil;
                double r1 = now_ms();
                printf("    release %.3f ms\n", r1 - r0);
            }
        }
    }
    return 0;
}
