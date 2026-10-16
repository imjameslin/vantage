// TASK E probe 2: does making a fresh 128 MiB buffer resident on a side queue (background thread) stall the main
// queue? The main thread submits a small "frame" command buffer every 1 ms and records commit->completion latency.
// Variants: (A) baseline, (B) main thread uses a fresh untouched buffer in-frame (vanilla), (C) background thread
// creates + GPU-pre-touches on a side queue, then the main thread uses it, (D) background CPU pre-touch only.
// Standalone (no game; docs/metal/CONTRACT.md 16.R5-E); build and run holding the GPU lock:
//   xcrun clang -O2 -fobjc-arc -framework Foundation -framework Metal tools/heap_residency_probe2.m -o build/heap_residency_probe2
//   lockf -k /tmp/mc-gpu.lock build/heap_residency_probe2
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#include <mach/mach_time.h>
#include <pthread.h>

static double now_ms(void) {
    static mach_timebase_info_data_t tb;
    if (tb.denom == 0) mach_timebase_info(&tb);
    return (double)mach_absolute_time() * tb.numer / tb.denom / 1e6;
}

static id<MTLDevice> dev;
static id<MTLCommandQueue> mainQ;
static id<MTLCommandQueue> sideQ;
static id<MTLBuffer> src, smallDst;
static id<MTLBuffer> volatile reserved;
static volatile int bgMode;
static volatile double bgStart, bgEnd;

static void *bg(void *arg) {
    @autoreleasepool {
        usleep(100 * 1000);
        bgStart = now_ms();
        id<MTLBuffer> b = [dev newBufferWithLength:128u << 20 options:MTLResourceStorageModeShared];
        if (bgMode == 1) {
            id<MTLCommandBuffer> cb = [sideQ commandBuffer];
            id<MTLBlitCommandEncoder> blit = [cb blitCommandEncoder];
            [blit fillBuffer:b range:NSMakeRange(0, 4) value:0];
            [blit endEncoding];
            [cb commit];
            [cb waitUntilCompleted];
        } else if (bgMode == 2) {
            for (NSUInteger o = 0; o < b.length; o += 16384) ((volatile char *)b.contents)[o] = 0;
        }
        bgEnd = now_ms();
        reserved = b;
    }
    return NULL;
}

static void run(const char *name, int mode) {
    // mode 0: baseline, 1: vanilla (main creates + uses at frame 150), 2: bg GPU pre-touch then main uses, 3: bg CPU touch
    reserved = nil;
    bgMode = mode == 2 ? 1 : (mode == 3 ? 2 : 0);
    pthread_t t;
    if (mode >= 2) pthread_create(&t, NULL, bg, NULL);
    double worst = 0, sum = 0, worstAt = 0;
    int n = 0;
    double worstCommit = 0;
    id<MTLBuffer> used = nil;
    double start = now_ms();
    for (int frame = 0; frame < 400; frame++) {
        double tf = now_ms();
        id<MTLCommandBuffer> cb = [mainQ commandBuffer];
        id<MTLBlitCommandEncoder> blit = [cb blitCommandEncoder];
        [blit copyFromBuffer:src sourceOffset:0 toBuffer:smallDst destinationOffset:0 size:4096];
        id<MTLBuffer> target = nil;
        if (mode == 1 && frame == 150) {
            used = [dev newBufferWithLength:128u << 20 options:MTLResourceStorageModeShared];
            target = used;
        } else if (mode >= 2 && used == nil && reserved != nil && now_ms() - bgEnd > 20) {
            used = reserved;
            target = used;
        }
        if (target) [blit copyFromBuffer:src sourceOffset:0 toBuffer:target destinationOffset:0 size:4096];
        [blit endEncoding];
        double c0 = now_ms();
        [cb commit];
        double c1 = now_ms();
        [cb waitUntilCompleted];
        double done = now_ms();
        double lat = done - c0;
        if (c1 - c0 > worstCommit) worstCommit = c1 - c0;
        if (lat > worst) { worst = lat; worstAt = c0 - start; }
        sum += lat; n++;
        if (target) printf("    frame %d uses the 128 MiB buffer: commit->done %.3f ms\n", frame, lat);
        while (now_ms() - tf < 1.0) {}
    }
    if (mode >= 2) pthread_join(t, NULL);
    printf("  %-44s mean %.3f ms, worst %.3f ms at t=%.0f ms, worst commit call %.3f ms", name, sum / n, worst, worstAt, worstCommit);
    if (mode >= 2) printf(", background %.2f ms at t=%.0f..%.0f", bgEnd - bgStart, bgStart - start, bgEnd - start);
    printf("\n");
}

int main(void) {
    @autoreleasepool {
        dev = MTLCreateSystemDefaultDevice();
        mainQ = [dev newCommandQueue];
        sideQ = [dev newCommandQueue];
        src = [dev newBufferWithLength:4096 options:MTLResourceStorageModeShared];
        smallDst = [dev newBufferWithLength:4096 options:MTLResourceStorageModeShared];
        for (int round = 0; round < 2; round++) {
            run("A baseline", 0);
            run("B vanilla: fresh buffer used in-frame", 1);
            run("C bg create + GPU pre-touch (side queue)", 2);
            run("D bg create + CPU pre-touch", 3);
        }
    }
    return 0;
}
