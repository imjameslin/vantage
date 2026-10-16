/*
 * layer_flip_probe.m -- which CALayer property flips the *displayed* contents of a CAMetalLayer?
 *
 * Present fusion (docs/metal/CONTRACT.md 16.3) renders the frame straight into the drawable with the same memory
 * orientation as the main target (memory row 0 = bottom of the picture), so the layer must show drawable row 0 at
 * the bottom. This probe needs no window and no screen capture: it renders a known pattern into a drawable
 * (memory rows 0..H/2-1 red, the rest blue, a green marker at memory (0, 0)), presents it, then composites the
 * layer tree offscreen with CARenderer (the same Core Animation renderer the WindowServer uses) into an MTLTexture
 * and reads that back. The same test runs on a plain CALayer whose contents is an IOSurface (what a presented
 * drawable is) as a control.
 *
 * Build and run: tools/layer-flip-probe.sh
 */
#import <Foundation/Foundation.h>
#import <QuartzCore/QuartzCore.h>
#import <Metal/Metal.h>
#import <IOSurface/IOSurface.h>
#include <objc/message.h>
#include <unistd.h>

static const int W = 8, H = 8;

static void fill_pattern(uint8_t *bgra, size_t bpr) {
    for (int y = 0; y < H; y++)
        for (int x = 0; x < W; x++) {
            uint8_t *p = bgra + y * bpr + x * 4;
            if (y < H / 2) { p[0] = 0; p[1] = 0; p[2] = 255; p[3] = 255; }   /* red (BGRA bytes) */
            else           { p[0] = 255; p[1] = 0; p[2] = 0; p[3] = 255; }   /* blue             */
            if (y == 0 && x == 0) { p[0] = 0; p[1] = 255; p[2] = 0; }        /* green: memory (0, 0) */
        }
}

static char classify(const uint8_t *p) {
    if (p[2] > 200 && p[1] < 50 && p[0] < 50) return 'R';
    if (p[0] > 200 && p[1] < 50 && p[2] < 50) return 'B';
    if (p[1] > 200 && p[0] < 50 && p[2] < 50) return 'G';
    if (p[0] < 20 && p[1] < 20 && p[2] < 20) return 'k';
    return '?';
}

/* Composites `root` with CARenderer; returns the first two pixels of every output row, top (memory row 0) first. */
static NSString *render_tree(id<MTLDevice> dev, id<MTLCommandQueue> q, CALayer *root) {
    MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm
                                                                                  width:W height:H mipmapped:NO];
    td.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
    td.storageMode = MTLStorageModeShared;
    id<MTLTexture> out = [dev newTextureWithDescriptor:td];
    CARenderer *r = [CARenderer rendererWithMTLTexture:out options:@{}];
    r.layer = root;
    r.bounds = CGRectMake(0, 0, W, H);
    [CATransaction flush];
    [r beginFrameAtTime:CACurrentMediaTime() timeStamp:NULL];
    [r addUpdateRect:r.bounds];
    [r render];
    [r endFrame];
    id<MTLCommandBuffer> cb = [q commandBuffer];
    [cb commit];
    [cb waitUntilCompleted];
    usleep(20000);
    uint8_t buf[W * H * 4];
    [out getBytes:buf bytesPerRow:W * 4 fromRegion:MTLRegionMake2D(0, 0, W, H) mipmapLevel:0];
    NSMutableString *s = [NSMutableString string];
    for (int y = 0; y < H; y++) {
        for (int x = 0; x < 2; x++) [s appendFormat:@"%c", classify(buf + (y * W + x) * 4)];
        if (y + 1 < H) [s appendString:@"|"];
    }
    return s;
}

static void set_contents_transform(CALayer *layer, CGAffineTransform t) {
    SEL sel = NSSelectorFromString(@"setContentsTransform:");
    if ([layer respondsToSelector:sel]) ((void (*)(id, SEL, CGAffineTransform))objc_msgSend)(layer, sel, t);
}

typedef void (^Config)(CALayer *content, CALayer *root);

int main(void) {
    @autoreleasepool {
        id<MTLDevice> dev = MTLCreateSystemDefaultDevice();
        id<MTLCommandQueue> q = [dev newCommandQueue];
        NSArray<NSString *> *names = @[
            @"none", @"geometryFlipped = YES", @"superlayer geometryFlipped = YES", @"transform = scale(1, -1)",
            @"affineTransform = scale(1, -1)", @"contentsRect = (0, 1, 1, -1)", @"contentsTransform (private) = flip",
        ];
        Config configs[] = {
            ^(CALayer *c, CALayer *r) {},
            ^(CALayer *c, CALayer *r) { c.geometryFlipped = YES; },
            ^(CALayer *c, CALayer *r) { r.geometryFlipped = YES; },
            ^(CALayer *c, CALayer *r) { c.transform = CATransform3DMakeScale(1, -1, 1); },
            ^(CALayer *c, CALayer *r) { c.affineTransform = CGAffineTransformMakeScale(1, -1); },
            ^(CALayer *c, CALayer *r) { c.contentsRect = CGRectMake(0, 1, 1, -1); },
            ^(CALayer *c, CALayer *r) { set_contents_transform(c, CGAffineTransformMake(1, 0, 0, -1, 0, 1)); },
        };
        printf("Pattern in drawable memory: rows 0..%d red (R), rows %d..%d blue (B), green (G) at memory (0,0).\n",
               H / 2 - 1, H / 2, H - 1);
        printf("Output: CARenderer texture memory rows 0..%d, first two pixels each ('k' = black, '?' = other). CARenderer\n"
               "renders bottom-up (row 0 = bottom of the window): 'none' ends in GR = drawable row 0 at the top (upright);\n"
               "a flip shows GR first = drawable row 0 at the bottom, which present fusion needs.\n", H - 1);
        for (int kind = 0; kind < 2; kind++) {
            printf("== %s\n", kind == 0 ? "CAMetalLayer + presented drawable" : "CALayer.contents = IOSurface (control)");
            for (NSUInteger i = 0; i < names.count; i++) {
                @autoreleasepool {
                    [CATransaction begin];
                    [CATransaction setDisableActions:YES];
                    CALayer *root = [CALayer layer];
                    root.frame = CGRectMake(0, 0, W, H);
                    root.backgroundColor = CGColorGetConstantColor(kCGColorBlack);
                    CALayer *content;
                    if (kind == 0) {
                        CAMetalLayer *ml = [CAMetalLayer layer];
                        ml.device = dev;
                        ml.pixelFormat = MTLPixelFormatBGRA8Unorm;
                        ml.framebufferOnly = NO;
                        ml.drawableSize = CGSizeMake(W, H);
                        ml.opaque = YES;
                        content = ml;
                    } else {
                        content = [CALayer layer];
                    }
                    content.frame = CGRectMake(0, 0, W, H);
                    [root addSublayer:content];
                    configs[i](content, root);
                    [CATransaction commit];
                    if (kind == 0) {
                        CAMetalLayer *ml = (CAMetalLayer *)content;
                        id<CAMetalDrawable> d = [ml nextDrawable];
                        uint8_t pattern[W * H * 4];
                        fill_pattern(pattern, W * 4);
                        [d.texture replaceRegion:MTLRegionMake2D(0, 0, W, H) mipmapLevel:0 withBytes:pattern bytesPerRow:W * 4];
                        id<MTLCommandBuffer> cb = [q commandBuffer];
                        [cb presentDrawable:d];
                        [cb commit];
                        [cb waitUntilCompleted];
                        usleep(50000);
                        [CATransaction flush];
                    } else {
                        IOSurfaceRef surf = IOSurfaceCreate((__bridge CFDictionaryRef) @{
                            (id)kIOSurfaceWidth : @(W), (id)kIOSurfaceHeight : @(H), (id)kIOSurfaceBytesPerElement : @4,
                            (id)kIOSurfacePixelFormat : @((uint32_t)'BGRA')
                        });
                        IOSurfaceLock(surf, 0, NULL);
                        fill_pattern(IOSurfaceGetBaseAddress(surf), IOSurfaceGetBytesPerRow(surf));
                        IOSurfaceUnlock(surf, 0, NULL);
                        [CATransaction begin];
                        [CATransaction setDisableActions:YES];
                        content.contents = (__bridge id)surf;
                        [CATransaction commit];
                        CFRelease(surf);
                    }
                    NSString *rows = render_tree(dev, q, root);
                    printf("  %-38s %s\n", names[i].UTF8String, rows.UTF8String);
                }
            }
        }
    }
    return 0;
}
