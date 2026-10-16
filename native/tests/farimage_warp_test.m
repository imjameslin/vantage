/* Real native Metal tests; parent registers/runs ONLY after the live game exits.
 * Standalone build (does not need decoder/engine or the image owner):
 * xcrun clang -fobjc-arc -fmodules -fmodules-cache-path=/tmp/farwarp-module-cache \
 *   -Wall -Wextra -I native/src native/src/mcmetal_farwarp.m \
 *   native/tests/farimage_warp_test.m -framework Foundation -framework Metal \
 *   -o /tmp/farimage_warp_test
 * /tmp/farimage_warp_test
 * No timing/FPS/Phase 1 acceptance claim. The fixture owns immutable standalone
 * source textures through completion; production callers must attach their
 * MCMTLFarImageLease before encoding. Only this test harness commits/waits.
 */
#import "mcmetal_farwarp.h"
#include <dispatch/dispatch.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static unsigned checks, failures;
#define CHECK(x) do { ++checks; if (!(x)) { ++failures; fprintf(stderr,"FAIL %d: %s\n",__LINE__,#x); } } while (0)

@interface WarpFixture : NSObject {
@public
    id<MTLDevice> device;
    id<MTLCommandQueue> queue;
    MCMTLFarWarp *warp;
    id<MTLTexture> src[3], dst[4];
    id<MTLBuffer> upload[3], readback[4], original[3], poison[4];
    NSUInteger W, H, w, h, sourceRow, targetRow;
}
@end
@implementation WarpFixture
@end

static id<MTLTexture> texture(id<MTLDevice> d, MTLPixelFormat format, NSUInteger w,
                              NSUInteger h, MTLTextureUsage usage) {
    MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:format
        width:w height:h mipmapped:NO];
    td.storageMode = MTLStorageModePrivate;
    td.hazardTrackingMode = MTLHazardTrackingModeTracked;
    td.usage = usage;
    id<MTLTexture> t = [d newTextureWithDescriptor:td]; CHECK(t != nil); return t;
}

static WarpFixture *fixture(id<MTLDevice> device, id<MTLCommandQueue> queue, MCMTLFarWarp *warp,
                             NSUInteger W, NSUInteger H, NSUInteger w, NSUInteger h) {
    WarpFixture *f = [WarpFixture new];
    f->device = device; f->queue = queue; f->warp = warp; f->W = W; f->H = H; f->w = w; f->h = h;
    f->sourceRow = (W * 4 + 255) & ~(NSUInteger)255;
    f->targetRow = (w * 4 + 255) & ~(NSUInteger)255;
    const MTLPixelFormat sf[] = {MTLPixelFormatRGBA8Unorm, MTLPixelFormatDepth32Float, MTLPixelFormatR32Uint};
    const MTLPixelFormat df[] = {MTLPixelFormatRGBA8Unorm, MTLPixelFormatR32Float, MTLPixelFormatR32Uint, MTLPixelFormatR8Uint};
    for (unsigned k = 0; k < 3; ++k) {
        f->src[k] = texture(device, sf[k], W, H, MTLTextureUsageShaderRead | MTLTextureUsageRenderTarget |
                           MTLTextureUsagePixelFormatView | (k == 1 ? 0 : MTLTextureUsageShaderWrite));
        f->upload[k] = [device newBufferWithLength:f->sourceRow * H options:MTLResourceStorageModeShared];
        f->original[k] = [device newBufferWithLength:f->sourceRow * H options:MTLResourceStorageModeShared];
        CHECK(f->upload[k] && f->original[k]);
        if (!f->src[k] || !f->upload[k] || !f->original[k]) return nil;
        memset(f->upload[k].contents, 0, f->sourceRow * H);
    }
    for (unsigned k = 0; k < 4; ++k) {
        f->dst[k] = texture(device, df[k], w, h, MTLTextureUsageShaderWrite | MTLTextureUsageShaderRead);
        f->readback[k] = [device newBufferWithLength:f->targetRow * h options:MTLResourceStorageModeShared];
        f->poison[k] = [device newBufferWithLength:f->targetRow * h options:MTLResourceStorageModeShared];
        CHECK(f->readback[k] && f->poison[k]);
        if (!f->dst[k] || !f->readback[k] || !f->poison[k]) return nil;
        memset(f->poison[k].contents, 0xa7, f->targetRow * h);
    }
    return f;
}

static MCMTLFarWarpSource source(WarpFixture *f) {
    return (MCMTLFarWarpSource){f->src[0], f->src[1], f->src[2]};
}
static MCMTLFarWarpTargets targets(WarpFixture *f) {
    return (MCMTLFarWarpTargets){f->dst[0], f->dst[1], f->dst[2], f->dst[3]};
}
static void *srcPixel(WarpFixture *f, unsigned plane, NSUInteger x, NSUInteger y) {
    return (uint8_t *)f->upload[plane].contents + y * f->sourceRow + x * 4;
}
static void *dstPixel(WarpFixture *f, unsigned plane, NSUInteger x, NSUInteger y) {
    return (uint8_t *)f->readback[plane].contents + y * f->targetRow + x * (plane == 3 ? 1 : 4);
}

/* Independent top-left pixel affine mapping, including fractional jitter.
 * Ratios are rounded once from double, as required for exact integer crops. */
static MCMTLFarWarpTransform crop(WarpFixture *f, double x, double y) {
    double W = f->W, H = f->H, w = f->w, h = f->h;
    MCMTLFarWarpTransform t = {.motion = MCMTLFarWarpMotionPureRotation};
    t.currentToSourceClip[0] = w/W; t.currentToSourceClip[5] = h/H;
    t.currentToSourceClip[10] = t.currentToSourceClip[15] = 1;
    t.currentToSourceClip[12] = (2*x+w-W)/W; t.currentToSourceClip[13] = (H-2*y-h)/H;
    t.sourceToCurrentClip[0] = W/w; t.sourceToCurrentClip[5] = H/h;
    t.sourceToCurrentClip[10] = t.sourceToCurrentClip[15] = 1;
    t.sourceToCurrentClip[12] = (W-w-2*x)/w; t.sourceToCurrentClip[13] = (2*y+h-H)/h;
    return t;
}

static BOOL finish(id<MTLCommandBuffer> cb) {
    dispatch_semaphore_t done = dispatch_semaphore_create(0);
    [cb addCompletedHandler:^(id<MTLCommandBuffer> completed) {
        (void)completed; dispatch_semaphore_signal(done);
    }];
    [cb commit];
    BOOL completed = dispatch_semaphore_wait(done, dispatch_time(DISPATCH_TIME_NOW, 10 * NSEC_PER_SEC)) == 0;
    CHECK(completed);
    if (completed && cb.status != MTLCommandBufferStatusCompleted)
        fprintf(stderr, "%s\n", cb.error.description.UTF8String);
    CHECK(completed && cb.status == MTLCommandBufferStatusCompleted);
    return completed && cb.status == MTLCommandBufferStatusCompleted;
}

static void copyOut(id<MTLBlitCommandEncoder> blit, id<MTLTexture> t, id<MTLBuffer> b, NSUInteger row) {
    [blit copyFromTexture:t sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,0,0)
        sourceSize:MTLSizeMake(t.width,t.height,1) toBuffer:b destinationOffset:0
        destinationBytesPerRow:row destinationBytesPerImage:row*t.height];
}
static void copyIn(id<MTLBlitCommandEncoder> blit, id<MTLBuffer> b, id<MTLTexture> t, NSUInteger row) {
    [blit copyFromBuffer:b sourceOffset:0 sourceBytesPerRow:row sourceBytesPerImage:row*t.height
        sourceSize:MTLSizeMake(t.width,t.height,1) toTexture:t destinationSlice:0 destinationLevel:0
        destinationOrigin:MTLOriginMake(0,0,0)];
}

static BOOL run(WarpFixture *f, MCMTLFarWarpTransform t, BOOL upload, BOOL poison) {
    id<MTLCommandBuffer> cb = [f->queue commandBuffer]; CHECK(cb != nil);
    if (!cb) return NO;
    if (upload || poison) {
        id<MTLBlitCommandEncoder> blit = [cb blitCommandEncoder]; CHECK(blit != nil);
        if (!blit) return NO;
        if (upload) for (unsigned k=0;k<3;++k) copyIn(blit, f->upload[k], f->src[k], f->sourceRow);
        if (poison) for (unsigned k=0;k<4;++k) copyIn(blit, f->poison[k], f->dst[k], f->targetRow);
        [blit endEncoding];
    }
    NSError *error = nil;
    BOOL encoded = [f->warp encodeToCommandBuffer:cb source:source(f) targets:targets(f) transform:&t error:&error];
    CHECK(encoded && !error);
    CHECK(cb.status == MTLCommandBufferStatusNotEnqueued);
    if (!encoded) { fprintf(stderr, "%s\n", error.description.UTF8String); return NO; }
    id<MTLBlitCommandEncoder> blit = [cb blitCommandEncoder]; CHECK(blit != nil);
    if (!blit) return NO;
    for (unsigned k=0;k<4;++k) copyOut(blit, f->dst[k], f->readback[k], f->targetRow);
    for (unsigned k=0;k<3;++k) copyOut(blit, f->src[k], f->original[k], f->sourceRow);
    [blit endEncoding];
    if (!finish(cb)) return NO;
    // Reads never mutate any source plane, including payloads of invalid depths.
    for (unsigned k=0;k<3;++k) for (NSUInteger y=0;y<f->H;++y)
        CHECK(memcmp((uint8_t *)f->upload[k].contents+y*f->sourceRow,
                     (uint8_t *)f->original[k].contents+y*f->sourceRow, f->W*4) == 0);
    return YES;
}

static void fillPattern(WarpFixture *f, BOOL sky) {
    for (NSUInteger y=0;y<f->H;++y) for (NSUInteger x=0;x<f->W;++x) {
        uint8_t *c = srcPixel(f,0,x,y);
        for (unsigned k=0;k<4;++k) c[k] = (uint8_t)(x + 53*k + 17*y);
        // Nontrivial mantissa bits detect accidental depth round trips.
        uint32_t bits = 0x3e000001u + ((uint32_t)(x+f->W*y)*7919u % 0x00ffffffu);
        memcpy(srcPixel(f,1,x,y), &bits, sizeof bits);
        *(uint32_t *)srcPixel(f,2,x,y) = 0x81000001u + (uint32_t)(x+y*f->W);
        if (sky && (x+y)%11 == 0) {
            *(float *)srcPixel(f,1,x,y) = 0;
            *(uint32_t *)srcPixel(f,2,x,y) = 0;
        }
    }
    *(float *)srcPixel(f,1,f->W-1,f->H-1) = 1;
    *(uint32_t *)srcPixel(f,2,f->W-1,f->H-1) = 0xfedcba98u;
}

static void expectEmpty(WarpFixture *f, NSUInteger x, NSUInteger y, uint8_t mask) {
    CHECK(*(uint8_t *)dstPixel(f,3,x,y) == mask);
    for (unsigned k=0;k<3;++k) CHECK(*(uint32_t *)dstPixel(f,k,x,y) == 0);
}
static void expectCrop(WarpFixture *f, int ox, int oy) {
    for (NSUInteger y=0;y<f->h;++y) for (NSUInteger x=0;x<f->w;++x) {
        int sx=(int)x+ox, sy=(int)y+oy;
        if (sx<0 || sy<0 || sx>=(int)f->W || sy>=(int)f->H) {
            expectEmpty(f,x,y,MCMTLFarWarpMissing); continue;
        }
        for (unsigned k=0;k<3;++k) CHECK(memcmp(dstPixel(f,k,x,y),srcPixel(f,k,sx,sy),4) == 0);
        CHECK(*(uint8_t *)dstPixel(f,3,x,y) ==
              (*(uint32_t *)srcPixel(f,2,sx,sy) ? MCMTLFarWarpGeometry : MCMTLFarWarpSky));
    }
}

static void exactTests(id<MTLDevice> d, id<MTLCommandQueue> q, MCMTLFarWarp *warp) {
    WarpFixture *f = fixture(d,q,warp,256,5,256,5); if (!f) return;
    fillPattern(f,YES); // Every possible byte in EACH RGBA channel, including alpha and sky.
    if (run(f,crop(f,0,0),YES,YES)) expectCrop(f,0,0);
    f = fixture(d,q,warp,37,29,23,17); if (!f) return;
    fillPattern(f,YES);
    if (run(f,crop(f,7,5),YES,YES)) expectCrop(f,7,5);
    // Partial guard, non-multiple threadgroups; previously populated outputs must disappear.
    if (run(f,crop(f,-2,-1),NO,NO)) expectCrop(f,-2,-1);
    if (run(f,crop(f,100,100),NO,NO))
        for (NSUInteger y=0;y<f->h;++y) for (NSUInteger x=0;x<f->w;++x) expectEmpty(f,x,y,MCMTLFarWarpMissing);
    // Reuse exactly the same caller-owned targets for a NEW source's color/depth/ID.
    for (NSUInteger y=0;y<f->H;++y) for (NSUInteger x=0;x<f->W;++x) {
        memset(srcPixel(f,0,x,y),37,4); *(float *)srcPixel(f,1,x,y)=0.875f;
        *(uint32_t *)srcPixel(f,2,x,y)=0xff004321u;
    }
    if (run(f,crop(f,7,5),YES,NO)) expectCrop(f,7,5);
    // Canonical inverse coefficients can cancel poorly at extreme crop ratios;
    // the exact integer path must still preserve the lone edge pixel.
    f=fixture(d,q,warp,16376,1,1,1); if (!f) return;
    fillPattern(f,NO);
    if (run(f,crop(f,16375,0),YES,YES)) expectCrop(f,16375,0);
}

/* Independent geometric oracle. Camera looks along +Z. Its projection includes
 * Metal's Y flip: clip=(fx*X,-fy*Y,near,Z). Rays and plane intersections below
 * NEVER use either clip-to-clip matrix or any implementation sampling code.
 * Rotation R maps current camera vectors to source camera vectors. */
typedef struct { double R[3][3], sfx, sfy, cfx, cfy, sn, cn; } CameraPair;
static CameraPair cameras(WarpFixture *f, double yaw, double pitch) {
    double cy=cos(yaw), sy=sin(yaw), cp=cos(pitch), sp=sin(pitch);
    CameraPair c = {.R={{cy,sy*sp,sy*cp},{0,cp,-sp},{-sy,cy*sp,cy*cp}},
        .sfx=64.0/f->W,.sfy=64.0/f->H,.cfx=64.0/f->w,.cfy=64.0/f->h,.sn=0.75,.cn=1.25};
    return c;
}
static void cameraTransform(float out[16], const double R[3][3], double fx, double fy,
                            double gx, double gy, double nearRatio) {
    memset(out,0,16*sizeof(float));
    out[0]=gx*R[0][0]/fx; out[4]=-gx*R[0][1]/fy; out[12]=gx*R[0][2];
    out[1]=-gy*R[1][0]/fx; out[5]=gy*R[1][1]/fy; out[13]=-gy*R[1][2];
    out[10]=nearRatio;
    out[3]=R[2][0]/fx; out[7]=-R[2][1]/fy; out[15]=R[2][2];
}
static MCMTLFarWarpTransform rotation(CameraPair c) {
    MCMTLFarWarpTransform t = {.motion=MCMTLFarWarpMotionPureRotation};
    cameraTransform(t.currentToSourceClip,c.R,c.cfx,c.cfy,c.sfx,c.sfy,c.sn/c.cn);
    double invR[3][3]; for (unsigned i=0;i<3;++i) for (unsigned j=0;j<3;++j) invR[i][j]=c.R[j][i];
    cameraTransform(t.sourceToCurrentClip,invR,c.sfx,c.sfy,c.cfx,c.cfy,c.cn/c.sn);
    return t;
}
static void worldRay(CameraPair c, double x, double y, NSUInteger w, NSUInteger h, double out[3]) {
    double local[3]={(2*(x+0.5)/w-1)/c.cfx, -(1-2*(y+0.5)/h)/c.cfy, 1};
    for (unsigned i=0;i<3;++i) { out[i]=0; for (unsigned j=0;j<3;++j) out[i]+=c.R[i][j]*local[j]; }
}
static double dot3(const double a[3], const double b[3]) { return a[0]*b[0]+a[1]*b[1]+a[2]*b[2]; }

static void planeTest(id<MTLDevice> d, id<MTLCommandQueue> q, MCMTLFarWarp *warp, BOOL tilted) {
    WarpFixture *f=fixture(d,q,warp,80,64,36,28); if (!f) return;
    CameraPair c=cameras(f,0.11,-0.07);
    const double normal[3]={tilted ? 0.31 : 0, tilted ? -0.23 : 0, 1};
    const double distance=8;
    for (NSUInteger y=0;y<f->H;++y) for (NSUInteger x=0;x<f->W;++x) {
        double ray[3]={(2*(x+0.5)/f->W-1)/c.sfx,-(1-2*(y+0.5)/f->H)/c.sfy,1};
        double hitDistance=distance/dot3(normal,ray);
        *(float *)srcPixel(f,1,x,y)=(float)(c.sn/hitDistance);
        *(uint32_t *)srcPixel(f,2,x,y)=0xf1234567u;
        uint8_t *color=srcPixel(f,0,x,y); color[0]=2*x; color[1]=3*y; color[2]=x+y; color[3]=200;
    }
    if (!run(f,rotation(c),YES,YES)) return;
    for (NSUInteger y=0;y<f->h;++y) for (NSUInteger x=0;x<f->w;++x) {
        double ray[3]; worldRay(c,x,y,f->w,f->h,ray);
        double hitDistance=distance/dot3(normal,ray);
        double expectedDepth=c.cn/hitDistance;
        double sx=(c.sfx*ray[0]/ray[2]+1)*f->W/2-0.5;
        double sy=(1+c.sfy*ray[1]/ray[2])*f->H/2-0.5;
        CHECK(sx>=0 && sx<f->W-1 && sy>=0 && sy<f->H-1);
        CHECK(*(uint8_t *)dstPixel(f,3,x,y)==MCMTLFarWarpGeometry);
        CHECK(*(uint32_t *)dstPixel(f,2,x,y)==0xf1234567u);
        CHECK(fabs(*(float *)dstPixel(f,1,x,y)-expectedDepth)<2e-6);
        const double expectedColor[4]={2*sx,3*sy,sx+sy,200};
        for (unsigned k=0;k<4;++k) CHECK(fabs(((uint8_t *)dstPixel(f,0,x,y))[k]-expectedColor[k])<=0.501);
    }
}

static void rotatedGuardTest(id<MTLDevice> d, id<MTLCommandQueue> q, MCMTLFarWarp *warp) {
    WarpFixture *f=fixture(d,q,warp,32,32,32,32); if (!f) return;
    CameraPair c=cameras(f,0.29,0.08);
    for (NSUInteger y=0;y<32;++y) for (NSUInteger x=0;x<32;++x) {
        memset(srcPixel(f,0,x,y),119,4);
        *(float *)srcPixel(f,1,x,y)=(float)(c.sn/5);
        *(uint32_t *)srcPixel(f,2,x,y)=73;
    }
    if (!run(f,rotation(c),YES,YES)) return;
    unsigned hits=0, misses=0;
    for (NSUInteger y=0;y<32;++y) for (NSUInteger x=0;x<32;++x) {
        double ray[3]; worldRay(c,x,y,32,32,ray);
        double sx=(c.sfx*ray[0]/ray[2]+1)*16-0.5;
        double sy=(1+c.sfy*ray[1]/ray[2])*16-0.5;
        // Bilinear support must fit. This reference is direct camera geometry,
        // not a replay of the kernel's clip transforms or float calculations.
        if (sx<0 || sy<0 || sx>31 || sy>31 || ray[2]<=0) {
            ++misses; expectEmpty(f,x,y,MCMTLFarWarpMissing);
        } else {
            ++hits;
            CHECK(*(uint8_t *)dstPixel(f,3,x,y)==MCMTLFarWarpGeometry);
            CHECK(*(uint32_t *)dstPixel(f,2,x,y)==73);
            CHECK(fabs(*(float *)dstPixel(f,1,x,y)-c.cn*ray[2]/5)<2e-6);
        }
    }
    CHECK(hits>0 && misses>0);
}

static void silhouetteTests(id<MTLDevice> d, id<MTLCommandQueue> q, MCMTLFarWarp *warp) {
    WarpFixture *f=fixture(d,q,warp,8,8,8,8); if (!f) return;
    for (NSUInteger y=0;y<f->H;++y) for (NSUInteger x=0;x<f->W;++x) {
        BOOL sky=(y%2==1);
        // Last row has geometry: its off-texture next row has ZERO weight.
        if (y==7) sky=NO;
        *(float *)srcPixel(f,1,x,y)=sky ? 0 : (float)(0.25+x/32.0);
        *(uint32_t *)srcPixel(f,2,x,y)=sky ? 0 : x<4 ? 7 : 9;
        uint8_t *col=srcPixel(f,0,x,y); col[0]=20*x; col[1]=10*y; col[2]=33; col[3]=241;
    }
    for (unsigned attempt=0;attempt<2;++attempt) {
        double fraction=attempt ? 1.0/65536 : 0.5;
        if (!run(f,crop(f,fraction,0),YES,YES)) continue;
        for (NSUInteger y=0;y<f->h;++y) for (NSUInteger x=0;x<f->w;++x) {
            if (x==7) { expectEmpty(f,x,y,MCMTLFarWarpMissing); continue; }
            BOOL sky=(y%2==1 && y!=7);
            if (!sky && x==3) { expectEmpty(f,x,y,MCMTLFarWarpUncertain); continue; }
            CHECK(*(uint8_t *)dstPixel(f,3,x,y)==(sky ? MCMTLFarWarpSky : MCMTLFarWarpGeometry));
            CHECK(*(uint32_t *)dstPixel(f,2,x,y)==(sky ? 0 : x<4 ? 7u : 9u));
            double expected=sky ? 0 : 0.25+(x+fraction)/32.0;
            CHECK(fabs(*(float *)dstPixel(f,1,x,y)-expected)<1e-6);
            CHECK(abs(((uint8_t *)dstPixel(f,0,x,y))[0]-(int)lround(20*(x+fraction)))<=1);
        }
    }
    // Mixed sky/geometry with both rows REQUIRED, even though all IDs in each
    // individual row agree. Previously trusted results must become uncertain.
    if (run(f,crop(f,0,0.5),NO,NO)) {
        for (NSUInteger y=0;y<6;++y) for (NSUInteger x=0;x<8;++x) expectEmpty(f,x,y,MCMTLFarWarpUncertain);
        for (NSUInteger x=0;x<8;++x) expectEmpty(f,x,7,MCMTLFarWarpMissing);
    }
    // A sole unknown ID at positive depth can NEVER pass the same-ID rule.
    *(uint32_t *)srcPixel(f,2,2,0)=0;
    if (run(f,crop(f,0.5,0),YES,NO)) {
        expectEmpty(f,1,0,MCMTLFarWarpUncertain); expectEmpty(f,2,0,MCMTLFarWarpUncertain);
    }
}

static void invalidPixelTests(id<MTLDevice> d, id<MTLCommandQueue> q, MCMTLFarWarp *warp) {
    WarpFixture *f=fixture(d,q,warp,8,8,8,8); if (!f) return;
    fillPattern(f,NO);
    const float badDepth[6]={NAN,INFINITY,-0.25f,1.25f,0,0.5f};
    for (NSUInteger x=0;x<6;++x) {
        *(float *)srcPixel(f,1,x,0)=badDepth[x]; *(uint32_t *)srcPixel(f,2,x,0)=x==5 ? 0 : 15;
    }
    if (run(f,crop(f,0,0),YES,YES)) for (NSUInteger x=0;x<6;++x) expectEmpty(f,x,0,MCMTLFarWarpUncertain);
    if (run(f,crop(f,0.5,0),NO,NO)) for (NSUInteger x=0;x<6;++x) expectEmpty(f,x,0,MCMTLFarWarpUncertain);
    for (NSUInteger y=0;y<8;++y) for (NSUInteger x=0;x<8;++x) {
        *(float *)srcPixel(f,1,x,y)=0.75f; *(uint32_t *)srcPixel(f,2,x,y)=19;
    }
    // Valid source depth transforms outside the current reversed-Z clip range.
    MCMTLFarWarpTransform t=crop(f,0,0); t.currentToSourceClip[10]=0.5f; t.sourceToCurrentClip[10]=2;
    if (run(f,t,YES,NO)) for (NSUInteger y=0;y<8;++y) for (NSUInteger x=0;x<8;++x) expectEmpty(f,x,y,MCMTLFarWarpMissing);
    // Nonzero geometry ID at the exact current far-plane boundary cannot be sky
    // or trusted geometry. Check both homogeneous zero and negative depth.
    t=crop(f,0,0); t.currentToSourceClip[14]=0.75f; t.sourceToCurrentClip[14]=-0.75f;
    if (run(f,t,NO,NO)) for (NSUInteger y=0;y<8;++y) for (NSUInteger x=0;x<8;++x) expectEmpty(f,x,y,MCMTLFarWarpMissing);
    t.currentToSourceClip[14]=1; t.sourceToCurrentClip[14]=-1;
    if (run(f,t,NO,NO)) for (NSUInteger y=0;y<8;++y) for (NSUInteger x=0;x<8;++x) expectEmpty(f,x,y,MCMTLFarWarpMissing);
    // Source ray behind the source camera (180-degree pure yaw); never trust it.
    CameraPair c=cameras(f,acos(-1.0),0);
    if (run(f,rotation(c),NO,NO)) for (NSUInteger y=0;y<8;++y) for (NSUInteger x=0;x<8;++x) expectEmpty(f,x,y,MCMTLFarWarpMissing);
}

static void reject(WarpFixture *f, id<MTLCommandBuffer> cb, MCMTLFarWarpSource s,
                   MCMTLFarWarpTargets out, const MCMTLFarWarpTransform *t) {
    NSError *error=nil;
    CHECK(![f->warp encodeToCommandBuffer:cb source:s targets:out transform:t error:&error]);
    CHECK(error != nil);
}
static void validationTests(id<MTLDevice> d, id<MTLCommandQueue> q, MCMTLFarWarp *warp) {
    WarpFixture *f=fixture(d,q,warp,8,8,8,8); if (!f) return;
    fillPattern(f,NO);
    MCMTLFarWarpTransform good=crop(f,0,0), bad;
    // These failures all leave the SAME command buffer usable for a valid pass.
    id<MTLCommandBuffer> cb=[q commandBuffer];
    reject(f,cb,source(f),targets(f),NULL);
    reject(f,nil,source(f),targets(f),&good);
    bad=good; bad.motion=MCMTLFarWarpMotionUnspecified; reject(f,cb,source(f),targets(f),&bad);
    bad=good; bad.motion=MCMTLFarWarpMotionTranslation; reject(f,cb,source(f),targets(f),&bad);
    bad=good; bad.translation[0]=1e-30f; reject(f,cb,source(f),targets(f),&bad);
    bad=good; bad.translation[1]=NAN; reject(f,cb,source(f),targets(f),&bad);
    bad=good; bad.translation[2]=INFINITY; reject(f,cb,source(f),targets(f),&bad);
    bad=good; bad.currentToSourceClip[0]=NAN; reject(f,cb,source(f),targets(f),&bad);
    bad=good; bad.sourceToCurrentClip[5]=INFINITY; reject(f,cb,source(f),targets(f),&bad);
    bad=good; memset(bad.currentToSourceClip,0,sizeof bad.currentToSourceClip); reject(f,cb,source(f),targets(f),&bad);
    bad=good; bad.sourceToCurrentClip[0]=1.01f; reject(f,cb,source(f),targets(f),&bad);
    bad=good; bad.currentToSourceClip[0]=bad.sourceToCurrentClip[0]=0; reject(f,cb,source(f),targets(f),&bad);
    // A genuinely inverse pair encoding translation must fail even if the
    // caller incorrectly labels it pure rotation and claims zero displacement.
    bad=good; bad.currentToSourceClip[8]=1e-20f; bad.sourceToCurrentClip[8]=-1e-20f;
    reject(f,cb,source(f),targets(f),&bad);
    MCMTLFarWarpSource s=source(f); s.depth=nil; reject(f,cb,s,targets(f),&good);
    MCMTLFarWarpTargets out=targets(f); out.depth=f->src[1]; reject(f,cb,source(f),out,&good);
    out=targets(f); out.color=f->src[0]; reject(f,cb,source(f),out,&good); // actual read/write overlap
    out=targets(f); out.visibilityID=f->src[2]; reject(f,cb,source(f),out,&good);
    id<MTLTexture> wrongSize=texture(d,MTLPixelFormatR32Uint,7,8,MTLTextureUsageShaderWrite);
    out=targets(f); out.visibilityID=wrongSize; reject(f,cb,source(f),out,&good);
    id<MTLTexture> wrongUsage=texture(d,MTLPixelFormatRGBA8Unorm,8,8,MTLTextureUsageShaderRead);
    out=targets(f); out.color=wrongUsage; reject(f,cb,source(f),out,&good);
    id<MTLTexture> unreadable=texture(d,MTLPixelFormatRGBA8Unorm,8,8,MTLTextureUsageShaderWrite);
    s=source(f); s.color=unreadable; reject(f,cb,s,targets(f),&good);
    id<MTLTexture> view=[f->src[0] newTextureViewWithPixelFormat:MTLPixelFormatRGBA8Unorm]; CHECK(view != nil);
    out=targets(f); out.color=view; reject(f,cb,source(f),out,&good);
    MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:8 height:8 mipmapped:NO];
    td.usage=MTLTextureUsageShaderWrite; td.storageMode=MTLStorageModePrivate;
    td.hazardTrackingMode=MTLHazardTrackingModeUntracked;
    id<MTLTexture> untracked=[d newTextureWithDescriptor:td]; CHECK(untracked != nil);
    out=targets(f); out.color=untracked; reject(f,cb,source(f),out,&good);
    id<MTLCommandBuffer> unretained=[q commandBufferWithUnretainedReferences];
    reject(f,unretained,source(f),targets(f),&good);
    // Device mismatch test runs where a second physical device is available.
    for (id<MTLDevice> other in MTLCopyAllDevices()) if (other != d && other.registryID != d.registryID) {
        id<MTLCommandQueue> otherQueue=[other newCommandQueue];
        reject(f,[otherQueue commandBuffer],source(f),targets(f),&good);
        id<MTLTexture> foreign=texture(other,MTLPixelFormatRGBA8Unorm,8,8,MTLTextureUsageShaderWrite);
        out=targets(f); out.color=foreign; reject(f,cb,source(f),out,&good);
    }
    CHECK(cb.status==MTLCommandBufferStatusNotEnqueued);
    id<MTLBlitCommandEncoder> blit=[cb blitCommandEncoder]; CHECK(blit != nil);
    for (unsigned k=0;k<3;++k) copyIn(blit,f->upload[k],f->src[k],f->sourceRow);
    [blit endEncoding];
    NSError *error=nil;
    CHECK([warp encodeToCommandBuffer:cb source:source(f) targets:targets(f) transform:&good error:&error] && !error);
    blit=[cb blitCommandEncoder];
    for (unsigned k=0;k<4;++k) copyOut(blit,f->dst[k],f->readback[k],f->targetRow);
    [blit endEncoding];
    if (finish(cb)) expectCrop(f,0,0);
    reject(f,cb,source(f),targets(f),&good); // completed buffers cannot be encoded again
}

int main(void) { @autoreleasepool {
    id<MTLDevice> device=MTLCreateSystemDefaultDevice(); CHECK(device != nil); if (!device) return 1;
    NSError *error=nil;
    // Creation must fail on the main thread BEFORE compiling anything.
    CHECK([[MCMTLFarWarp alloc] initWithDevice:device error:&error]==nil && error);
    dispatch_semaphore_t done=dispatch_semaphore_create(0);
    dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED,0), ^{ @autoreleasepool {
        NSError *workerError=nil;
        CHECK([[MCMTLFarWarp alloc] initWithDevice:nil error:&workerError]==nil && workerError);
        MCMTLFarWarp *warp=[[MCMTLFarWarp alloc] initWithDevice:device error:&workerError];
        CHECK(warp && !workerError);
        if (!warp) fprintf(stderr,"%s\n",workerError.description.UTF8String);
        id<MTLCommandQueue> queue=[device newCommandQueue]; CHECK(queue != nil);
        if (warp && queue) {
            exactTests(device,queue,warp);
            planeTest(device,queue,warp,NO);
            planeTest(device,queue,warp,YES);
            rotatedGuardTest(device,queue,warp);
            silhouetteTests(device,queue,warp);
            invalidPixelTests(device,queue,warp);
            validationTests(device,queue,warp);
        }
        dispatch_semaphore_signal(done);
    }});
    if (dispatch_semaphore_wait(done,dispatch_time(DISPATCH_TIME_NOW,120*NSEC_PER_SEC))) {
        fprintf(stderr,"farimage_warp_test: harness timeout\n"); return 1;
    }
    printf("farimage_warp_test: %u checks, %u failures\n",checks,failures);
    return failures ? 1 : 0;
} }
