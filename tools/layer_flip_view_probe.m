/*
 * layer_flip_view_probe.m -- the present-fusion layer flip on an SDL-style view (docs/metal/CONTRACT.md 16.3).
 *
 * SDL's Metal view is a layer-backed NSView whose backing layer is the CAMetalLayer (AppKit owns its geometry:
 * anchorPoint, position, bounds, and it resets `transform` to identity on every resize). This probe builds the same
 * hierarchy in a window that is never ordered on screen and checks, by compositing the content view's layer tree
 * offscreen with CARenderer after each step, that
 *   - a plain transform flip shows the drawable upside down in place, but AppKit's resize drops it;
 *   - the real mcmtl_layer_set_flipped (mcmetal_surface.m, linked in) shows it upside down (memory row 0 at the
 *     bottom of the window), covering exactly the view's rectangle, and keeps doing so across window and view
 *     resizes with no re-apply.
 * No screen capture, no visible window.
 *
 * Build and run: tools/layer-flip-probe.sh
 */
#import <AppKit/AppKit.h>
#import <QuartzCore/QuartzCore.h>
#import <Metal/Metal.h>
#include <unistd.h>
#import "mcmetal_internal.h"

/* A plain flip (what the layer would get without mcmtl_layer_set_flipped's setter overrides). */
static void apply_plain_flip(CALayer *layer) {
    const CGFloat h = layer.bounds.size.height;
    const CGFloat ay = layer.anchorPoint.y;
    layer.affineTransform = CGAffineTransformMake(1, 0, 0, -1, 0, h * (1.0 - 2.0 * ay));
}

@interface ProbeMetalView : NSView
@end
@implementation ProbeMetalView
+ (Class)layerClass { return [CAMetalLayer class]; }
- (BOOL)wantsUpdateLayer { return YES; }
- (CALayer *)makeBackingLayer { return [CAMetalLayer layer]; }
@end

static id<MTLDevice> gDev;
static id<MTLCommandQueue> gQueue;

/* Fills the drawable: memory rows 0..h/2-1 red, the rest blue; presents. */
static void present_pattern(CAMetalLayer *ml, int w, int h) {
    ml.drawableSize = CGSizeMake(w, h);
    id<CAMetalDrawable> d = [ml nextDrawable];
    NSMutableData *px = [NSMutableData dataWithLength:(NSUInteger)w * h * 4];
    uint8_t *p = px.mutableBytes;
    for (int y = 0; y < h; y++)
        for (int x = 0; x < w; x++) {
            uint8_t *q = p + ((size_t)y * w + x) * 4;
            if (y < h / 2) { q[0] = 0; q[1] = 0; q[2] = 255; } else { q[0] = 255; q[1] = 0; q[2] = 0; }
            q[3] = 255;
        }
    [d.texture replaceRegion:MTLRegionMake2D(0, 0, w, h) mipmapLevel:0 withBytes:p bytesPerRow:w * 4];
    id<MTLCommandBuffer> cb = [gQueue commandBuffer];
    [cb presentDrawable:d];
    [cb commit];
    [cb waitUntilCompleted];
    usleep(50000);
    [CATransaction flush];
}

/* Composites `root` (W x H points, scale 1) and prints the color of column x = cx, rows top..bottom. */
static NSString *composite(CALayer *root, int W, int H, int cx) {
    MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm
                                                                                  width:W height:H mipmapped:NO];
    td.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
    td.storageMode = MTLStorageModeShared;
    id<MTLTexture> out = [gDev newTextureWithDescriptor:td];
    CARenderer *r = [CARenderer rendererWithMTLTexture:out options:@{}];
    r.layer = root;
    r.bounds = CGRectMake(0, 0, W, H);
    [CATransaction flush];
    [r beginFrameAtTime:CACurrentMediaTime() timeStamp:NULL];
    [r addUpdateRect:r.bounds];
    [r render];
    [r endFrame];
    id<MTLCommandBuffer> cb = [gQueue commandBuffer];
    [cb commit];
    [cb waitUntilCompleted];
    usleep(20000);
    NSMutableData *buf = [NSMutableData dataWithLength:(NSUInteger)W * H * 4];
    [out getBytes:buf.mutableBytes bytesPerRow:W * 4 fromRegion:MTLRegionMake2D(0, 0, W, H) mipmapLevel:0];
    const uint8_t *b = buf.bytes;
    NSMutableString *s = [NSMutableString string];
    /* CARenderer's texture is bottom-up (memory row 0 = bottom of the window): print the window top first. */
    for (int y = H - 1; y >= 0; y--) {
        const uint8_t *q = b + ((size_t)y * W + cx) * 4;
        char c = q[2] > 200 && q[0] < 50 ? 'R' : (q[0] > 200 && q[2] < 50 ? 'B' : (q[0] < 20 && q[1] < 20 && q[2] < 20 ? 'k' : '?'));
        [s appendFormat:@"%c", c];
    }
    return s;
}

int main(void) {
    @autoreleasepool {
        [NSApplication sharedApplication];
        gDev = MTLCreateSystemDefaultDevice();
        gQueue = [gDev newCommandQueue];
        const int W = 12, H = 12;
        NSWindow *window = [[NSWindow alloc] initWithContentRect:NSMakeRect(0, 0, W, H) styleMask:NSWindowStyleMaskBorderless
                                                          backing:NSBackingStoreBuffered defer:NO];
        NSView *content = window.contentView;
        content.wantsLayer = YES;
        content.layer.backgroundColor = CGColorGetConstantColor(kCGColorBlack);
        /* The SDL view sits at (2, 2) inside the content view, 8 x 8 points: content outside it stays black. */
        ProbeMetalView *view = [[ProbeMetalView alloc] initWithFrame:NSMakeRect(2, 2, 8, 8)];
        view.wantsLayer = YES;
        view.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
        [content addSubview:view];
        CAMetalLayer *ml = (CAMetalLayer *)view.layer;
        ml.device = gDev;
        ml.pixelFormat = MTLPixelFormatBGRA8Unorm;
        ml.framebufferOnly = NO;
        ml.opaque = YES;
        printf("SDL-style view layer: class %s anchorPoint (%.2f, %.2f) position (%.1f, %.1f) bounds %.0fx%.0f geometryFlipped %d\n",
               object_getClassName(ml), ml.anchorPoint.x, ml.anchorPoint.y, ml.position.x, ml.position.y, ml.bounds.size.width,
               ml.bounds.size.height, ml.geometryFlipped);
        printf("Window column x=4, top..bottom (R = drawable memory rows 0..h/2-1, B = the rest, k = black):\n");

        int failures = 0;
        present_pattern(ml, 8, 8);
        NSString *s = composite(content.layer, W, H, 4);
        printf("  unflipped:                           %s\n", s.UTF8String);
        failures += ![s isEqualToString:@"kkRRRRBBBBkk"];

        [CATransaction begin];
        [CATransaction setDisableActions:YES];
        apply_plain_flip(ml);
        [CATransaction commit];
        present_pattern(ml, 8, 8);
        s = composite(content.layer, W, H, 4);
        printf("  plain transform flip:                %s\n", s.UTF8String);
        failures += ![s isEqualToString:@"kkBBBBRRRRkk"];
        [window setContentSize:NSMakeSize(W, H + 4)];
        [content layoutSubtreeIfNeeded];
        [CATransaction flush];
        present_pattern(ml, 8, 12);
        s = composite(content.layer, W, H + 4, 4);
        printf("    after an AppKit resize:            %s  (transform d = %.0f: AppKit reset it)\n", s.UTF8String, ml.affineTransform.d);

        mcmtl_layer_set_flipped(ml, YES);
        present_pattern(ml, 8, 12);
        s = composite(content.layer, W, H + 4, 4);
        printf("  mcmtl_layer_set_flipped:             %s\n", s.UTF8String);
        failures += ![s isEqualToString:@"kkBBBBBBRRRRRRkk"];
        [window setContentSize:NSMakeSize(W, H + 8)];
        [content layoutSubtreeIfNeeded];
        [CATransaction flush];
        present_pattern(ml, 8, 16);
        s = composite(content.layer, W, H + 8, 4);
        printf("    after an AppKit window resize:     %s  (flipped %d)\n", s.UTF8String, mcmtl_layer_is_flipped(ml));
        failures += ![s isEqualToString:@"kkBBBBBBBBRRRRRRRRkk"];
        [view setFrameSize:NSMakeSize(8, 6)];
        [content layoutSubtreeIfNeeded];
        [CATransaction flush];
        present_pattern(ml, 8, 6);
        s = composite(content.layer, W, H + 8, 4);
        printf("    after an AppKit view resize:       %s  (flipped %d)\n", s.UTF8String, mcmtl_layer_is_flipped(ml));
        failures += ![s isEqualToString:@"kkkkkkkkkkkkBBBRRRkk"];
        mcmtl_layer_set_flipped(ml, NO);
        present_pattern(ml, 8, 6);
        s = composite(content.layer, W, H + 8, 4);
        printf("  removed:                             %s\n", s.UTF8String);
        failures += ![s isEqualToString:@"kkkkkkkkkkkkRRRBBBkk"];
        printf("%s\n", failures ? "LAYER FLIP VIEW PROBE FAILED" : "LAYER FLIP VIEW PROBE PASSED");
        return failures ? 1 : 0;
    }
}
