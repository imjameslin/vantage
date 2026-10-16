/* Bounded real-Metal test. Run under tools/gpulock.py functional, never a game.
 * Parent registers the test. CPU expectations come only from authored bytes. */
#import "mcmetal_faralpha.h"
#include <dispatch/dispatch.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

static unsigned checks, failures, comparisons, rejections;
#define CHECK(x) do { ++checks; if (!(x)) { ++failures; fprintf(stderr,"FAIL %d: %s\n",__LINE__,#x); } } while (0)
static void diagnostic(NSError *e) { if (e) fprintf(stderr,"%s\n",e.localizedDescription.UTF8String); }

@interface AlphaPlane : NSObject
@property(nonatomic, strong) id<MTLTexture> texture;
@property(nonatomic, strong) NSArray<NSMutableData *> *mips;
@end
@implementation AlphaPlane
@end

static MTLTextureDescriptor *descriptor(NSUInteger width, NSUInteger height, NSUInteger levels) {
    MTLTextureDescriptor *d=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm
        width:width height:height mipmapped:NO];
    d.mipmapLevelCount=levels; d.storageMode=MTLStorageModeShared;
    d.hazardTrackingMode=MTLHazardTrackingModeTracked; d.usage=MTLTextureUsageShaderRead;
    return d;
}
static void upload(AlphaPlane *p) {
    for (NSUInteger mip=0;mip<p.mips.count;++mip) {
        NSUInteger w=MAX((NSUInteger)1,p.texture.width>>mip), h=MAX((NSUInteger)1,p.texture.height>>mip);
        [p.texture replaceRegion:MTLRegionMake2D(0,0,w,h) mipmapLevel:mip withBytes:p.mips[mip].bytes bytesPerRow:w*4];
    }
}
static AlphaPlane *plane(id<MTLDevice> device, NSUInteger width, NSUInteger height, NSUInteger levels, unsigned rgb) {
    AlphaPlane *p=[AlphaPlane new]; p.texture=[device newTextureWithDescriptor:descriptor(width,height,levels)];
    CHECK(p.texture); if (!p.texture) return nil;
    NSMutableArray *mips=[NSMutableArray array];
    for (NSUInteger mip=0;mip<levels;++mip) {
        NSUInteger count=MAX((NSUInteger)1,width>>mip)*MAX((NSUInteger)1,height>>mip);
        NSMutableData *data=[NSMutableData dataWithLength:count*4]; uint8_t *bytes=data.mutableBytes;
        for (NSUInteger i=0;i<count;++i) {
            bytes[4*i]=(uint8_t)(i+19*mip+rgb*97);
            bytes[4*i+1]=(uint8_t)(i*3+17+rgb*113);
            bytes[4*i+2]=(uint8_t)(255-i+rgb*53);
            bytes[4*i+3]=(uint8_t)(i*37+17*mip); // all 256 alpha values occur in the atlas
        }
        [mips addObject:data];
    }
    p.mips=mips; upload(p); return p;
}
static uint32_t oracle(AlphaPlane *a, AlphaPlane *b, AlphaPlane *l, AlphaPlane *m) {
    // Independent byte equality, no Metal readback, float conversion or shader helpers.
    AlphaPlane *old[2]={a,l}, *current[2]={b,m}; uint32_t different=0;
    for (unsigned pair=0;pair<2;++pair) for (NSUInteger mip=0;mip<old[pair].mips.count;++mip) {
        NSData *left=old[pair].mips[mip], *right=current[pair].mips[mip];
        CHECK(left.length==right.length);
        const uint8_t *x=left.bytes, *y=right.bytes;
        for (NSUInteger i=3;i<left.length;i+=4) different|=(x[i]!=y[i]);
    }
    return different;
}
static BOOL finish(id<MTLCommandBuffer> cb) {
    dispatch_semaphore_t done=dispatch_semaphore_create(0);
    [cb addCompletedHandler:^(id<MTLCommandBuffer> b) { (void)b; dispatch_semaphore_signal(done); }];
    [cb commit];
    BOOL complete=dispatch_semaphore_wait(done,dispatch_time(DISPATCH_TIME_NOW,10*NSEC_PER_SEC))==0;
    CHECK(complete); if (!complete) return NO;
    CHECK(cb.status==MTLCommandBufferStatusCompleted); diagnostic(cb.error);
    return cb.status==MTLCommandBufferStatusCompleted;
}
static BOOL encode(MCMTLFarAlpha *guard, id<MTLCommandBuffer> cb, AlphaPlane *a, AlphaPlane *b,
                   AlphaPlane *l, AlphaPlane *m, id<MTLBuffer> result) {
    NSError *error=nil;
    BOOL ok=[guard encodeToCommandBuffer:cb oldAtlas:a.texture currentAtlas:b.texture
        oldLight:l.texture currentLight:m.texture result:result error:&error];
    CHECK(ok && !error); diagnostic(error); CHECK(cb.status==MTLCommandBufferStatusNotEnqueued);
    return ok;
}
static void compare(const char *label, MCMTLFarAlpha *guard, id<MTLCommandQueue> queue,
                    AlphaPlane *a, AlphaPlane *b, AlphaPlane *l, AlphaPlane *m, BOOL privateResult) {
    @autoreleasepool {
        uint32_t expected=oracle(a,b,l,m);
        id<MTLBuffer> result=[guard.device newBufferWithLength:16 options:privateResult?MTLResourceStorageModePrivate:MTLResourceStorageModeShared];
        id<MTLBuffer> readback=[guard.device newBufferWithLength:16 options:MTLResourceStorageModeShared];
        CHECK(result && readback); if (!result || !readback) return;
        id<MTLCommandBuffer> cb=[queue commandBuffer];
        id<MTLBlitCommandEncoder> blit=[cb blitCommandEncoder];
        [blit fillBuffer:result range:NSMakeRange(0,16) value:0xa5]; [blit endEncoding];
        if (!encode(guard,cb,a,b,l,m,result)) return;
        blit=[cb blitCommandEncoder]; [blit copyFromBuffer:result sourceOffset:0 toBuffer:readback destinationOffset:0 size:16];
        [blit endEncoding]; if (!finish(cb)) return;
        uint32_t actual=0; memcpy(&actual,readback.contents,4); CHECK(actual==expected);
        BOOL suffix=YES; for (unsigned i=4;i<16;++i) suffix&=((const uint8_t *)readback.contents)[i]==0xa5;
        CHECK(suffix); ++comparisons;
        printf("alpha %-28s expected=%u actual=%u suffix=%s\n",label,expected,actual,suffix?"preserved":"BAD");
    }
}

/* Rejection-only CB double: any encoder creation exposes validation too late.
 * These cases never reach the GPU, including wrong-device and unretained CBs. */
@interface AlphaRejectBuffer : NSObject
@property(nonatomic, strong) id<MTLDevice> device;
@property(nonatomic) MTLCommandBufferStatus status;
@property(nonatomic) BOOL retainedReferences;
@property(nonatomic) unsigned encoders;
@end
@implementation AlphaRejectBuffer
- (id)blitCommandEncoder { ++_encoders; return nil; }
- (id)computeCommandEncoder { ++_encoders; return nil; }
@end

static void reject(MCMTLFarAlpha *guard, AlphaRejectBuffer *cb, id<MTLTexture> a, id<MTLTexture> b,
                   id<MTLTexture> l, id<MTLTexture> m, id<MTLBuffer> result) {
    NSError *error=nil; unsigned before=cb.encoders;
    BOOL ok=[guard encodeToCommandBuffer:(id)cb oldAtlas:a currentAtlas:b oldLight:l currentLight:m result:result error:&error];
    CHECK(!ok && error && [error.domain isEqualToString:@"mcmetal.far.alpha"]);
    CHECK(cb.encoders==before); ++rejections;
}
static void invalidInputs(MCMTLFarAlpha *guard, AlphaPlane *a, AlphaPlane *b, AlphaPlane *l, AlphaPlane *m) {
    id<MTLDevice> d=guard.device;
    AlphaRejectBuffer *cb=[AlphaRejectBuffer new]; cb.device=d; cb.retainedReferences=YES;
    id<MTLBuffer> result=[d newBufferWithLength:4 options:MTLResourceStorageModeShared];
    reject(guard,nil,a.texture,b.texture,l.texture,m.texture,result);
    cb.retainedReferences=NO; reject(guard,cb,a.texture,b.texture,l.texture,m.texture,result); cb.retainedReferences=YES;
    cb.status=MTLCommandBufferStatusEnqueued; reject(guard,cb,a.texture,b.texture,l.texture,m.texture,result); cb.status=MTLCommandBufferStatusNotEnqueued;
    cb.device=nil; reject(guard,cb,a.texture,b.texture,l.texture,m.texture,result); cb.device=d;
    reject(guard,cb,nil,b.texture,l.texture,m.texture,result);
    reject(guard,cb,a.texture,nil,l.texture,m.texture,result);
    reject(guard,cb,a.texture,b.texture,nil,m.texture,result);
    reject(guard,cb,a.texture,b.texture,l.texture,nil,result);
    reject(guard,cb,a.texture,b.texture,l.texture,m.texture,nil);
    id<MTLBuffer> small=[d newBufferWithLength:1 options:MTLResourceStorageModeShared]; CHECK(small);
    reject(guard,cb,a.texture,b.texture,l.texture,m.texture,small);
    id<MTLBuffer> untracked=[d newBufferWithLength:4 options:MTLResourceStorageModeShared|MTLResourceHazardTrackingModeUntracked]; CHECK(untracked);
    reject(guard,cb,a.texture,b.texture,l.texture,m.texture,untracked);
    // Mismatched dimensions/counts plus unsupported formats, usage and resource shapes.
    for (unsigned kind=0;kind<8;++kind) {
        MTLTextureDescriptor *td=descriptor(a.texture.width,a.texture.height,a.texture.mipmapLevelCount);
        switch (kind) {
            case 0: td.width++; break;
            case 1: td.height++; break;
            case 2: td.mipmapLevelCount--; break;
            case 3: td.pixelFormat=MTLPixelFormatRGBA8Unorm_sRGB; break;
            case 4: td.pixelFormat=MTLPixelFormatBGRA8Unorm; break;
            case 5: td.usage=MTLTextureUsageShaderWrite; break;
            case 6: td.hazardTrackingMode=MTLHazardTrackingModeUntracked; break;
            case 7: td.textureType=MTLTextureType2DArray; td.arrayLength=2; break;
        }
        id<MTLTexture> bad=[d newTextureWithDescriptor:td]; CHECK(bad);
        reject(guard,cb,a.texture,bad,l.texture,m.texture,result);
        // Test both positions, with matching metadata to expose per-resource validation.
        if (kind>=3) reject(guard,cb,bad,bad,l.texture,m.texture,result);
    }
    id<MTLTexture> view=[a.texture newTextureViewWithPixelFormat:MTLPixelFormatRGBA8Unorm
        textureType:MTLTextureType2D levels:NSMakeRange(1,2) slices:NSMakeRange(0,1)]; CHECK(view);
    reject(guard,cb,view,view,l.texture,m.texture,result);
    reject(guard,cb,a.texture,b.texture,l.texture,a.texture,result); // light pair shape mismatch
    // A texture sharing the writable result's storage must reject before clear.
    NSUInteger row=[d minimumLinearTextureAlignmentForPixelFormat:MTLPixelFormatRGBA8Unorm];
    id<MTLBuffer> backing=[d newBufferWithLength:MAX(row,(NSUInteger)4) options:MTLResourceStorageModeShared];
    id<MTLTexture> linear=[backing newTextureWithDescriptor:descriptor(1,1,1) offset:0 bytesPerRow:MAX(row,(NSUInteger)4)];
    CHECK(linear); reject(guard,cb,linear,linear,l.texture,m.texture,backing);
    CHECK(cb.encoders==0);
    printf("alpha input validation: %u rejections before any encoder\n",rejections);
}

static void orderedPrivate(MCMTLFarAlpha *guard, id<MTLCommandQueue> queue,
                           AlphaPlane *a, AlphaPlane *b, AlphaPlane *l, AlphaPlane *m) {
    // Upload all mips on GPU in an earlier same-queue CB. Comparison sees private
    // textures, consumes its result with a later blit, and needs no intermediate wait.
    MTLTextureDescriptor *td=descriptor(b.texture.width,b.texture.height,b.texture.mipmapLevelCount);
    td.storageMode=MTLStorageModePrivate;
    id<MTLTexture> current=[guard.device newTextureWithDescriptor:td]; CHECK(current); if (!current) return;
    AlphaPlane *privatePlane=[AlphaPlane new]; privatePlane.texture=current; privatePlane.mips=b.mips;
    id<MTLCommandBuffer> uploadCB=[queue commandBuffer]; id<MTLBlitCommandEncoder> blit=[uploadCB blitCommandEncoder];
    for (NSUInteger mip=0;mip<b.mips.count;++mip)
        [blit copyFromTexture:b.texture sourceSlice:0 sourceLevel:mip sourceOrigin:MTLOriginMake(0,0,0)
            sourceSize:MTLSizeMake(MAX((NSUInteger)1,b.texture.width>>mip),MAX((NSUInteger)1,b.texture.height>>mip),1)
            toTexture:current destinationSlice:0 destinationLevel:mip destinationOrigin:MTLOriginMake(0,0,0)];
    [blit endEncoding]; [uploadCB commit];
    compare("same-queue private upload",guard,queue,a,privatePlane,l,m,YES);
    CHECK(uploadCB.status==MTLCommandBufferStatusCompleted); diagnostic(uploadCB.error);
}

static void resultLifetime(MCMTLFarAlpha *guard, id<MTLCommandQueue> queue, AlphaPlane *a, AlphaPlane *l) {
    id<MTLCommandBuffer> cb=[queue commandBuffer];
    id<MTLBuffer> readback=[guard.device newBufferWithLength:4 options:MTLResourceStorageModeShared]; CHECK(readback);
    __weak id<MTLBuffer> weakResult=nil;
    @autoreleasepool {
        id<MTLBuffer> result=[guard.device newBufferWithLength:4 options:MTLResourceStorageModePrivate];
        weakResult=result; CHECK(result); if (!encode(guard,cb,a,a,l,l,result)) return;
        // No test closure captures result. CB resource ownership must hold it.
    }
    @autoreleasepool {
        id<MTLBuffer> retained=weakResult; CHECK(retained);
        if (!retained) return;
        id<MTLBlitCommandEncoder> blit=[cb blitCommandEncoder];
        [blit copyFromBuffer:retained sourceOffset:0 toBuffer:readback destinationOffset:0 size:4]; [blit endEncoding];
    }
    if (finish(cb)) { uint32_t value=99; memcpy(&value,readback.contents,4); CHECK(value==0); }
    printf("alpha caller-owned result: retained by uncommitted CB through GPU completion\n");
}

static void run(id<MTLDevice> device) { @autoreleasepool {
    NSError *error=nil;
    CHECK([[MCMTLFarAlpha alloc] initWithDevice:nil error:&error]==nil && error);
    MCMTLFarAlpha *guard=[[MCMTLFarAlpha alloc] initWithDevice:device error:&error];
    CHECK(guard && !error && guard.device==device); diagnostic(error); if (!guard) return;
    id<MTLCommandQueue> queue=[device newCommandQueue]; CHECK(queue); if (!queue) return;
    AlphaPlane *a=plane(device,17,19,5,0), *b=plane(device,17,19,5,0);
    AlphaPlane *l=plane(device,5,3,3,0), *m=plane(device,5,3,3,0);
    if (!a || !b || !l || !m) return;
    compare("distinct equal pixels",guard,queue,a,b,l,m,NO);
    compare("identical input handles",guard,queue,a,a,l,l,YES);
    compare("all inputs read-alias",guard,queue,a,a,a,a,NO);
    b=plane(device,17,19,5,1); m=plane(device,5,3,3,1); if (!b || !m) return;
    CHECK(memcmp(a.mips[0].bytes,b.mips[0].bytes,a.mips[0].length)!=0);
    compare("RGB-only every mip both",guard,queue,a,b,l,m,YES);
    for (unsigned pair=0;pair<2;++pair) {
        AlphaPlane *p=pair?m:b;
        for (NSUInteger mip=0;mip<p.mips.count;++mip) {
            NSMutableData *data=p.mips[mip]; uint8_t *bytes=data.mutableBytes;
            NSUInteger alpha=data.length-1; uint8_t saved=bytes[alpha]; bytes[alpha]^=1; upload(p);
            char label[64]; snprintf(label,sizeof label,"%s mip%lu last alpha +/-1",pair?"light":"atlas",(unsigned long)mip);
            CHECK(oracle(a,b,l,m)==1); compare(label,guard,queue,a,b,l,m,YES);
            bytes[alpha]=saved; upload(p);
        }
    }
    // Many simultaneous differences across both materials/mips exercise atomic OR.
    for (AlphaPlane *p in @[b,m]) for (NSMutableData *data in p.mips) {
        uint8_t *bytes=data.mutableBytes; for (NSUInteger i=3;i<data.length;i+=4) bytes[i]^=255;
    }
    upload(b); upload(m); compare("all alpha changed",guard,queue,a,b,l,m,YES);
    orderedPrivate(guard,queue,a,a,l,l);
    orderedPrivate(guard,queue,a,b,l,l);
    invalidInputs(guard,a,b,l,m); resultLifetime(guard,queue,a,l);
    // Reuse the guard after mismatches; every call must clear a stale result word.
    compare("equal after mismatch",guard,queue,a,a,l,l,NO);
    AlphaPlane *partial=plane(device,7,2,2,0);
    if (partial) compare("equal partial mip chain",guard,queue,partial,partial,l,l,NO);
} }

int main(void) { @autoreleasepool {
    id<MTLDevice> device=MTLCreateSystemDefaultDevice(); CHECK(device); if (!device) return 1;
    NSError *error=nil; CHECK([[MCMTLFarAlpha alloc] initWithDevice:device error:&error]==nil && error);
    dispatch_group_t group=dispatch_group_create();
    dispatch_group_async(group,dispatch_get_global_queue(QOS_CLASS_USER_INITIATED,0),^{ run(device); });
    if (dispatch_group_wait(group,dispatch_time(DISPATCH_TIME_NOW,60*NSEC_PER_SEC))) {
        fprintf(stderr,"FAIL: bounded alpha test exceeded 60 seconds\n"); return 1;
    }
    printf("farimage_alpha_test: %u comparisons, %u rejections, %u checks, %u failures\n",comparisons,rejections,checks,failures);
    return failures?1:0;
} }
