/* Real Metal tests, run under tools/gpulock.py with API + shader validation.
 * Only the harness allocates resources, submits command buffers, and waits.
 * No game, timing, FPS, or Phase 1 motion-acceptance claim.
 */
#import "mcmetal_farresolve.h"
#include <dispatch/dispatch.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static unsigned checks, failures;
#define CHECK(x) do { ++checks; if (!(x)) { ++failures; fprintf(stderr,"FAIL %d: %s\n",__LINE__,#x); } } while (0)
static const MCMTLFarResolveOlderEligibility eligible = {YES,YES,YES};
static const MTLPixelFormat formats[4] = {MTLPixelFormatRGBA8Unorm,MTLPixelFormatR32Float,
                                         MTLPixelFormatR32Uint,MTLPixelFormatR8Uint};

@interface Fixture : NSObject {
@public
    id<MTLDevice> device;
    id<MTLCommandQueue> queue;
    MCMTLFarResolve *resolve;
    id<MTLTexture> src[2][2][4], dst[2][4], holes;
    id<MTLBuffer> upload[2][2][4], original[2][2][4], readback[2][4], holeRead, poison;
    id<MTLBuffer> gpu[4], seed[4], result[4];
    NSUInteger w,h,row;
    MCMTLFarResolveArguments args;
}
@end
@implementation Fixture
@end

/* Trap any hidden submit/wait. A failed later encoder simulates a sideCB that
 * must be discarded after earlier encodes, without executing unfinished work. */
@interface BufferSpy : NSProxy {
@public
    id<MTLCommandBuffer> real;
    unsigned encoders, forbidden, failEncoder;
    BOOL reportUnretained;
}
@end
@implementation BufferSpy
- (NSMethodSignature *)methodSignatureForSelector:(SEL)s { return [(NSObject *)real methodSignatureForSelector:s]; }
- (void)forwardInvocation:(NSInvocation *)i {
    NSString *name=NSStringFromSelector(i.selector);
    if ([name isEqualToString:@"commit"] || [name isEqualToString:@"enqueue"] ||
        [name hasPrefix:@"wait"] || [name hasPrefix:@"encodeWait"]) {
        ++forbidden; @throw [NSException exceptionWithName:@"forbidden" reason:name userInfo:nil];
    }
    [i invokeWithTarget:real];
}
- (BOOL)retainedReferences { return !reportUnretained && real.retainedReferences; }
- (id<MTLComputeCommandEncoder>)computeCommandEncoder {
    ++encoders; return failEncoder==encoders ? nil : [real computeCommandEncoder];
}
@end
static BufferSpy *spy(id<MTLCommandBuffer> cb) { BufferSpy *s=[BufferSpy alloc]; s->real=cb; return s; }

static id<MTLTexture> texture(id<MTLDevice> d, MTLPixelFormat format, NSUInteger w, NSUInteger h,
                              MTLTextureUsage usage, MTLHazardTrackingMode tracking) {
    MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:format width:w height:h mipmapped:NO];
    td.storageMode=MTLStorageModePrivate; td.hazardTrackingMode=tracking; td.usage=usage;
    id<MTLTexture> t=[d newTextureWithDescriptor:td]; CHECK(t); return t;
}
static id<MTLBuffer> buffer(id<MTLDevice> d, NSUInteger bytes, MTLResourceOptions options) {
    id<MTLBuffer> b=[d newBufferWithLength:bytes options:options]; CHECK(b); return b;
}
static Fixture *fixture(id<MTLDevice> d, id<MTLCommandQueue> q, MCMTLFarResolve *resolve,
                         NSUInteger w, NSUInteger h, uint32_t count, BOOL sharedArgs) {
    Fixture *f=[Fixture new]; f->device=d; f->queue=q; f->resolve=resolve;
    f->w=w; f->h=h; f->row=(w*4+255)&~(NSUInteger)255;
    for (unsigned i=0;i<2;++i) for (unsigned l=0;l<2;++l) for (unsigned p=0;p<4;++p) {
        f->src[i][l][p]=texture(d,formats[p],w,h,MTLTextureUsageShaderRead|MTLTextureUsageShaderWrite|
                                MTLTextureUsagePixelFormatView,MTLHazardTrackingModeTracked);
        f->upload[i][l][p]=buffer(d,f->row*h,MTLResourceStorageModeShared);
        f->original[i][l][p]=buffer(d,f->row*h,MTLResourceStorageModeShared);
        memset(f->upload[i][l][p].contents,0,f->row*h);
    }
    for (unsigned l=0;l<2;++l) for (unsigned p=0;p<4;++p) {
        f->dst[l][p]=texture(d,formats[p],w,h,MTLTextureUsageShaderRead|MTLTextureUsageShaderWrite,
                            MTLHazardTrackingModeTracked);
        f->readback[l][p]=buffer(d,f->row*h,MTLResourceStorageModeShared);
    }
    f->holes=texture(d,MTLPixelFormatR8Uint,w,h,MTLTextureUsageShaderRead|MTLTextureUsageShaderWrite,
                    MTLHazardTrackingModeTracked);
    f->holeRead=buffer(d,f->row*h,MTLResourceStorageModeShared);
    f->poison=buffer(d,f->row*h,MTLResourceStorageModeShared); memset(f->poison.contents,0xa7,f->row*h);
    NSUInteger offsets[4]={12,20,28,36}, sizes[4]={MAX(4,count*20),MAX(4,count*20),16,8};
    for (unsigned k=0;k<4;++k) {
        NSUInteger n=offsets[k]+sizes[k]+68;
        f->gpu[k]=buffer(d,n,sharedArgs ? MTLResourceStorageModeShared : MTLResourceStorageModePrivate);
        f->seed[k]=buffer(d,n,MTLResourceStorageModeShared); memset(f->seed[k].contents,0xa7,n);
        f->result[k]=buffer(d,n,MTLResourceStorageModeShared);
    }
    f->args=(MCMTLFarResolveArguments){.source=f->gpu[0],.sourceOffset=offsets[0],
        .fallback=f->gpu[1],.fallbackOffset=offsets[1],.count=count,
        .composite=f->gpu[2],.compositeOffset=offsets[2],.stats=f->gpu[3],.statsOffset=offsets[3]};
    uint32_t *words=(uint32_t *)((uint8_t *)f->seed[0].contents+offsets[0]);
    for (uint32_t i=0;i<count;++i) {
        words[5*i]=i%4 ? 3*(i+1) : 0; words[5*i+1]=i%5;
        words[5*i+2]=0x90000000u+i; words[5*i+3]=0xfffffff0u-i; words[5*i+4]=0xe1230000u+i;
    }
    return f;
}
static MCMTLFarResolvePair pair(Fixture *f, unsigned i) {
    MCMTLFarResolvePair r={0};
    for (unsigned l=0;l<2;++l) r.layers[l]=(MCMTLFarWarpTargets){f->src[i][l][0],f->src[i][l][1],f->src[i][l][2],f->src[i][l][3]};
    return r;
}
static MCMTLFarResolvePair targets(Fixture *f) {
    MCMTLFarResolvePair r={0};
    for (unsigned l=0;l<2;++l) r.layers[l]=(MCMTLFarWarpTargets){f->dst[l][0],f->dst[l][1],f->dst[l][2],f->dst[l][3]};
    return r;
}
static void *pixel(id<MTLBuffer> b, Fixture *f, unsigned p, NSUInteger x, NSUInteger y) {
    return (uint8_t *)b.contents+y*f->row+x*(p==3 ? 1:4);
}
static uint8_t *mask(Fixture *f, unsigned image, unsigned layer, NSUInteger x, NSUInteger y) {
    return pixel(f->upload[image][layer][3],f,3,x,y);
}
static void fill(Fixture *f) {
    for (unsigned i=0;i<2;++i) for (unsigned l=0;l<2;++l)
        for (NSUInteger y=0;y<f->h;++y) for (NSUInteger x=0;x<f->w;++x) {
            uint8_t *c=pixel(f->upload[i][l][0],f,0,x,y);
            for (unsigned k=0;k<4;++k) c[k]=(uint8_t)(x+29*y+47*l+97*i+31*k);
            uint32_t bits=0x3e000001u+(uint32_t)x+((uint32_t)y<<11)+i*0x800000+l*0x400000;
            memcpy(pixel(f->upload[i][l][1],f,1,x,y),&bits,4);
            *(uint32_t *)pixel(f->upload[i][l][2],f,2,x,y)=0x81000001u+(uint32_t)(x+y*f->w)+i*0x100000+l*0x10000;
            *mask(f,i,l,x,y)=MCMTLFarWarpGeometry;
            if ((x+3*y+i+l)%11==0) {
                *(float *)pixel(f->upload[i][l][1],f,1,x,y)=0;
                *(uint32_t *)pixel(f->upload[i][l][2],f,2,x,y)=0;
                *mask(f,i,l,x,y)=MCMTLFarWarpSky;
            }
        }
}
static void copyIn(id<MTLBlitCommandEncoder> b, id<MTLBuffer> src, id<MTLTexture> dst, NSUInteger row) {
    [b copyFromBuffer:src sourceOffset:0 sourceBytesPerRow:row sourceBytesPerImage:row*dst.height
        sourceSize:MTLSizeMake(dst.width,dst.height,1) toTexture:dst destinationSlice:0 destinationLevel:0
        destinationOrigin:MTLOriginMake(0,0,0)];
}
static void copyOut(id<MTLBlitCommandEncoder> b, id<MTLTexture> src, id<MTLBuffer> dst, NSUInteger row) {
    [b copyFromTexture:src sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,0,0)
        sourceSize:MTLSizeMake(src.width,src.height,1) toBuffer:dst destinationOffset:0
        destinationBytesPerRow:row destinationBytesPerImage:row*src.height];
}
static void seed(id<MTLCommandBuffer> cb, Fixture *f, BOOL sources, BOOL outputs) {
    id<MTLBlitCommandEncoder> b=[cb blitCommandEncoder]; CHECK(b);
    if (sources) for (unsigned i=0;i<2;++i) for (unsigned l=0;l<2;++l) for (unsigned p=0;p<4;++p)
        copyIn(b,f->upload[i][l][p],f->src[i][l][p],f->row);
    if (outputs) {
        for (unsigned l=0;l<2;++l) for (unsigned p=0;p<4;++p) copyIn(b,f->poison,f->dst[l][p],f->row);
        copyIn(b,f->poison,f->holes,f->row);
        for (unsigned k=0;k<4;++k) [b copyFromBuffer:f->seed[k] sourceOffset:0 toBuffer:f->gpu[k]
                                  destinationOffset:0 size:f->gpu[k].length];
    }
    [b endEncoding];
}
static void readback(id<MTLCommandBuffer> cb, Fixture *f) {
    id<MTLBlitCommandEncoder> b=[cb blitCommandEncoder]; CHECK(b);
    for (unsigned l=0;l<2;++l) for (unsigned p=0;p<4;++p) copyOut(b,f->dst[l][p],f->readback[l][p],f->row);
    for (unsigned i=0;i<2;++i) for (unsigned l=0;l<2;++l) for (unsigned p=0;p<4;++p)
        copyOut(b,f->src[i][l][p],f->original[i][l][p],f->row);
    copyOut(b,f->holes,f->holeRead,f->row);
    for (unsigned k=0;k<4;++k) [b copyFromBuffer:f->gpu[k] sourceOffset:0 toBuffer:f->result[k]
                              destinationOffset:0 size:f->gpu[k].length];
    [b endEncoding];
}
static BOOL finish(id<MTLCommandBuffer> cb) {
    dispatch_semaphore_t done=dispatch_semaphore_create(0);
    [cb addCompletedHandler:^(id<MTLCommandBuffer> b) { (void)b; dispatch_semaphore_signal(done); }];
    [cb commit];
    BOOL completed=dispatch_semaphore_wait(done,dispatch_time(DISPATCH_TIME_NOW,20*NSEC_PER_SEC))==0;
    CHECK(completed); CHECK(completed && cb.status==MTLCommandBufferStatusCompleted);
    if (completed && cb.error) fprintf(stderr,"%s\n",cb.error.description.UTF8String);
    return completed && cb.status==MTLCommandBufferStatusCompleted;
}
static BOOL encode(Fixture *f, id<MTLCommandBuffer> cb, const MCMTLFarResolvePair *older,
                    MCMTLFarResolveOlderEligibility e) {
    NSError *error=nil;
    BOOL ok=[f->resolve encodeToCommandBuffer:cb newest:pair(f,0) older:older olderEligibility:e
        targets:targets(f) holeMask:f->holes arguments:f->args error:&error];
    CHECK(ok && !error); if (!ok) fprintf(stderr,"%s\n",error.description.UTF8String);
    return ok;
}
static void expectArgs(Fixture *f, uint32_t holes) {
    NSUInteger offsets[4]={f->args.sourceOffset,f->args.fallbackOffset,f->args.compositeOffset,f->args.statsOffset};
    NSUInteger sizes[4]={0,f->args.count*20,16,8};
    for (unsigned k=0;k<4;++k) {
        uint8_t *actual=f->result[k].contents, *before=f->seed[k].contents;
        for (NSUInteger i=0;i<f->result[k].length;++i)
            if (!k || i<offsets[k] || i>=offsets[k]+sizes[k]) CHECK(actual[i]==before[i]);
    }
    uint32_t *fallback=(uint32_t *)((uint8_t *)f->result[1].contents+offsets[1]);
    uint32_t *source=(uint32_t *)((uint8_t *)f->seed[0].contents+offsets[0]);
    for (unsigned i=0;i<f->args.count*5;++i) CHECK(fallback[i]==(holes ? source[i]:0));
    uint32_t expected[4]={holes ? 0:3,holes ? 0:1,0,0};
    CHECK(!memcmp((uint8_t *)f->result[2].contents+offsets[2],expected,16));
    MCMTLFarResolveStats s; memcpy(&s,(uint8_t *)f->result[3].contents+offsets[3],sizeof s);
    CHECK(s.holeCount==holes); CHECK(s.fallback==(holes!=0));
}
static uint32_t expectPixels(Fixture *f, BOOL older) {
    uint32_t holes=0;
    for (NSUInteger y=0;y<f->h;++y) for (NSUInteger x=0;x<f->w;++x) {
        BOOL hole=NO;
        for (unsigned l=0;l<2;++l) {
            int chosen=*mask(f,0,l,x,y)>=2 ? 0 : (older && *mask(f,1,l,x,y)>=2 ? 1:-1);
            if (chosen<0) hole=YES;
            for (unsigned p=0;p<4;++p) {
                uint32_t zero=0; const void *expected=chosen<0 ? (const void *)&zero :
                    pixel(f->upload[chosen][l][p],f,p,x,y);
                CHECK(!memcmp(pixel(f->readback[l][p],f,p,x,y),expected,p==3 ? 1:4));
            }
        }
        holes+=hole; CHECK(*(uint8_t *)pixel(f->holeRead,f,3,x,y)==(uint8_t)hole);
    }
    return holes;
}
static BOOL run(Fixture *f, BOOL hasOlder, MCMTLFarResolveOlderEligibility e, BOOL poison) {
    id<MTLCommandBuffer> cb=[f->queue commandBuffer]; CHECK(cb);
    seed(cb,f,YES,poison);
    MCMTLFarResolvePair older=pair(f,1);
    BufferSpy *s=spy(cb);
    if (!encode(f,(id<MTLCommandBuffer>)s,hasOlder ? &older:NULL,e)) return NO;
    CHECK(s->encoders==3 && s->forbidden==0); CHECK(cb.status==MTLCommandBufferStatusNotEnqueued);
    readback(cb,f); if (!finish(cb)) return NO;
    uint32_t holes=expectPixels(f,hasOlder && e.sceneMatchesCurrent && e.materialMatchesCurrent && e.sourceFresh);
    expectArgs(f,holes);
    for (unsigned i=0;i<2;++i) for (unsigned l=0;l<2;++l) for (unsigned p=0;p<4;++p)
        for (NSUInteger y=0;y<f->h;++y)
            CHECK(!memcmp(pixel(f->original[i][l][p],f,p,0,y),pixel(f->upload[i][l][p],f,p,0,y),f->w*(p==3 ? 1:4)));
    return YES;
}
static void mergeTests(id<MTLDevice> d, id<MTLCommandQueue> q, MCMTLFarResolve *r) {
    Fixture *f=fixture(d,q,r,257,3,17,NO); fill(f);
    run(f,YES,eligible,YES); // All RGBA byte values, float bits, IDs, sky, newest wins.
    for (NSUInteger x=0;x<f->w;++x) {
        *mask(f,0,0,x,0)=x%2; // Repair uncertain as well as missing.
        *mask(f,0,1,x,1)=x%2;
    }
    run(f,YES,eligible,NO); // Both layers repaired, all outputs/stats overwritten.
    *mask(f,1,1,f->w-1,1)=1;
    run(f,YES,eligible,NO); // Exactly ONE unresolved cutout hole disables BOTH composites.
    *mask(f,1,1,f->w-1,1)=3;
    run(f,YES,eligible,NO); // Hole count must reset to zero after previous fallback.
    MCMTLFarResolveOlderEligibility noScene=eligible,noMaterial=eligible,stale=eligible;
    noScene.sceneMatchesCurrent=NO; noMaterial.materialMatchesCurrent=NO; stale.sourceFresh=NO;
    run(f,YES,noScene,NO); run(f,YES,noMaterial,NO); run(f,YES,stale,NO);
    run(f,NO,eligible,NO); // Absent older even when booleans claim valid.
    f=fixture(d,q,r,19,11,4096,NO); fill(f);
    run(f,NO,eligible,YES); // 4096 records, padded threadgroups never count as holes.
    for (unsigned l=0;l<2;++l) *mask(f,0,l,18,10)=0;
    run(f,NO,eligible,NO); // Bottom-right hole in BOTH layers counts once, 4096 exact args.
    for (unsigned i=0;i<2;++i) for (unsigned l=0;l<2;++l)
        for (NSUInteger y=0;y<f->h;++y) for (NSUInteger x=0;x<f->w;++x) *mask(f,i,l,x,y)=(x+y+l)%2;
    run(f,YES,eligible,NO); // Every actual target pixel counts, never twice per pixel.
    for (unsigned count=0;count<=1;++count) {
        f=fixture(d,q,r,1,1,count,YES); fill(f); run(f,NO,eligible,YES);
        *mask(f,0,0,0,0)=1; run(f,NO,eligible,NO);
    }
}

#define reject(...) rejectAt(__LINE__, __VA_ARGS__)
static void rejectAt(unsigned line, Fixture *f, id<MTLCommandBuffer> cb, MCMTLFarResolvePair n,
                    const MCMTLFarResolvePair *old, MCMTLFarResolvePair out,
                    id<MTLTexture> holes, MCMTLFarResolveArguments args) {
    NSError *error=nil;
    BOOL accepted=[f->resolve encodeToCommandBuffer:cb newest:n older:old olderEligibility:eligible
        targets:out holeMask:holes arguments:args error:&error];
    if (accepted) fprintf(stderr,"Unexpected acceptance of invalid input at line %u\n",line);
    CHECK(!accepted); CHECK(error);
}
static void validationTests(id<MTLDevice> d, id<MTLCommandQueue> q, MCMTLFarResolve *r) {
    Fixture *f=fixture(d,q,r,9,7,4,NO); fill(f);
    id<MTLCommandBuffer> cb=[q commandBuffer]; BufferSpy *s=spy(cb);
    MCMTLFarResolvePair n=pair(f,0),o=pair(f,1),t=targets(f),bad;
    reject(f,nil,n,&o,t,f->holes,f->args);
    bad=n; bad.layers[0].validity=nil; reject(f,(id<MTLCommandBuffer>)s,bad,&o,t,f->holes,f->args);
    bad=t; bad.layers[0].color=n.layers[0].color; reject(f,(id<MTLCommandBuffer>)s,n,&o,bad,f->holes,f->args);
    bad=t; bad.layers[1].depth=t.layers[0].depth; reject(f,(id<MTLCommandBuffer>)s,n,&o,bad,f->holes,f->args);
    reject(f,(id<MTLCommandBuffer>)s,n,&n,t,f->holes,f->args);
    reject(f,(id<MTLCommandBuffer>)s,n,&o,t,t.layers[1].validity,f->args);
    id<MTLTexture> wrong=texture(d,MTLPixelFormatR32Float,8,7,MTLTextureUsageShaderWrite,MTLHazardTrackingModeTracked);
    bad=t; bad.layers[1].depth=wrong; reject(f,(id<MTLCommandBuffer>)s,n,&o,bad,f->holes,f->args);
    id<MTLTexture> readOnly=texture(d,MTLPixelFormatRGBA8Unorm,9,7,MTLTextureUsageShaderRead,MTLHazardTrackingModeTracked);
    bad=t; bad.layers[0].color=readOnly; reject(f,(id<MTLCommandBuffer>)s,n,&o,bad,f->holes,f->args);
    id<MTLTexture> writeOnly=texture(d,MTLPixelFormatRGBA8Unorm,9,7,MTLTextureUsageShaderWrite,MTLHazardTrackingModeTracked);
    bad=n; bad.layers[0].color=writeOnly; reject(f,(id<MTLCommandBuffer>)s,bad,&o,t,f->holes,f->args);
    id<MTLTexture> view=[n.layers[0].color newTextureViewWithPixelFormat:MTLPixelFormatRGBA8Unorm]; CHECK(view);
    bad=n; bad.layers[0].color=view; reject(f,(id<MTLCommandBuffer>)s,bad,&o,t,f->holes,f->args);
    id<MTLTexture> untracked=texture(d,MTLPixelFormatRGBA8Unorm,9,7,MTLTextureUsageShaderRead,MTLHazardTrackingModeUntracked);
    bad=n; bad.layers[0].color=untracked; reject(f,(id<MTLCommandBuffer>)s,bad,&o,t,f->holes,f->args);
    MCMTLFarResolveArguments a=f->args;
    a.count=4097; reject(f,(id<MTLCommandBuffer>)s,n,&o,t,f->holes,a);
    a=f->args; a.source=nil; reject(f,(id<MTLCommandBuffer>)s,n,&o,t,f->holes,a);
    a=f->args; a.sourceOffset=NSUIntegerMax-3; reject(f,(id<MTLCommandBuffer>)s,n,&o,t,f->holes,a);
    a=f->args; a.fallbackOffset++; reject(f,(id<MTLCommandBuffer>)s,n,&o,t,f->holes,a);
    a=f->args; a.statsOffset=f->gpu[3].length-4; reject(f,(id<MTLCommandBuffer>)s,n,&o,t,f->holes,a);
    a=f->args; a.compositeOffset=f->gpu[2].length-12; reject(f,(id<MTLCommandBuffer>)s,n,&o,t,f->holes,a);
    a=f->args; a.fallbackOffset=f->gpu[1].length-4; reject(f,(id<MTLCommandBuffer>)s,n,&o,t,f->holes,a);
    a=f->args; a.fallback=a.source; reject(f,(id<MTLCommandBuffer>)s,n,&o,t,f->holes,a);
    a=f->args; a.stats=a.composite; reject(f,(id<MTLCommandBuffer>)s,n,&o,t,f->holes,a);
    id<MTLBuffer> ub=buffer(d,256,MTLResourceStorageModeShared|MTLResourceHazardTrackingModeUntracked);
    a=f->args; a.source=ub; reject(f,(id<MTLCommandBuffer>)s,n,&o,t,f->holes,a);
    /* Different MTLBuffer objects over the SAME no-copy allocation must fail. */
    void *memory=NULL; CHECK(posix_memalign(&memory,16384,16384)==0);
    id<MTLBuffer> noCopyA=[d newBufferWithBytesNoCopy:memory length:16384 options:MTLResourceStorageModeShared deallocator:nil];
    id<MTLBuffer> noCopyB=[d newBufferWithBytesNoCopy:memory length:16384 options:MTLResourceStorageModeShared deallocator:nil];
    CHECK(noCopyA && noCopyB);
    a=f->args; a.source=noCopyA; a.fallback=noCopyB; reject(f,(id<MTLCommandBuffer>)s,n,&o,t,f->holes,a);
    a=f->args; noCopyA=nil; noCopyB=nil; free(memory);
    id<MTLCommandBuffer> unretained=[q commandBufferWithUnretainedReferences];
    if (!unretained.retainedReferences) reject(f,unretained,n,&o,t,f->holes,f->args);
    else {
        // Shader validation can promote unretained requests to retained buffers.
        // Exercise the actual reported-NO rejection through a forwarding wrapper.
        fprintf(stderr,"Metal validation promoted unretained CB; checking reported-NO via proxy\n");
        BufferSpy *unretainedSpy=spy(unretained); unretainedSpy->reportUnretained=YES;
        reject(f,(id<MTLCommandBuffer>)unretainedSpy,n,&o,t,f->holes,f->args);
        CHECK(unretainedSpy->encoders==0 && !unretainedSpy->forbidden);
    }
    CHECK(s->encoders==0 && s->forbidden==0); CHECK(cb.status==MTLCommandBufferStatusNotEnqueued);
    // Invalid old fields are never inspected when the caller marks the image stale.
    MCMTLFarResolvePair empty={0}; MCMTLFarResolveOlderEligibility stale=eligible; stale.sourceFresh=NO;
    seed(cb,f,YES,YES); CHECK(encode(f,(id<MTLCommandBuffer>)s,&empty,stale));
    readback(cb,f); if (finish(cb)) { expectArgs(f,0); expectPixels(f,NO); }
    reject(f,cb,n,&o,t,f->holes,f->args); // completed rejected
    cb=[q commandBuffer]; [cb enqueue]; reject(f,cb,n,&o,t,f->holes,f->args); finish(cb);
}

static void lifecycleTests(id<MTLDevice> d, id<MTLCommandQueue> q, MCMTLFarResolve *r) {
    Fixture *f=fixture(d,q,r,9,7,7,YES); fill(f);
    id<MTLCommandBuffer> upstream=[q commandBuffer]; seed(upstream,f,YES,YES); CHECK(finish(upstream));
    MCMTLFarResolvePair old=pair(f,1);
    @autoreleasepool {
        id<MTLCommandBuffer> abandoned=[q commandBuffer]; BufferSpy *s=spy(abandoned);
        CHECK(encode(f,(id<MTLCommandBuffer>)s,&old,eligible));
        CHECK(abandoned.status==MTLCommandBufferStatusNotEnqueued && !s->forbidden);
        for (unsigned k=0;k<4;++k) CHECK(!memcmp(f->gpu[k].contents,f->seed[k].contents,f->gpu[k].length));
        // No commit. The caller must discard pins and may never publish this result.
    }
    for (unsigned failAt=1;failAt<=3;++failAt) { @autoreleasepool {
        BufferSpy *s=spy([q commandBuffer]); s->failEncoder=failAt;
        NSError *error=nil;
        CHECK(![r encodeToCommandBuffer:(id<MTLCommandBuffer>)s newest:pair(f,0) older:&old olderEligibility:eligible
            targets:targets(f) holeMask:f->holes arguments:f->args error:&error]);
        CHECK(error && s->encoders==failAt && !s->forbidden);
        CHECK(s->real.status==MTLCommandBufferStatusNotEnqueued);
    }}
    id<MTLCommandBuffer> inspect=[q commandBuffer]; readback(inspect,f); CHECK(finish(inspect));
    for (unsigned k=0;k<4;++k) CHECK(!memcmp(f->result[k].contents,f->seed[k].contents,f->gpu[k].length));
    for (unsigned l=0;l<2;++l) for (unsigned p=0;p<4;++p)
        for (NSUInteger y=0;y<f->h;++y) for (NSUInteger x=0;x<f->w;++x)
            CHECK(!memcmp(pixel(f->readback[l][p],f,p,x,y),"\xa7\xa7\xa7\xa7",p==3 ? 1:4));
    // Actual main-queue order, three separate CBs; only downstream completion is waited.
    *mask(f,0,1,8,6)=0; *mask(f,1,1,8,6)=1;
    upstream=[q commandBuffer]; seed(upstream,f,YES,YES); [upstream commit];
    id<MTLCommandBuffer> sideCB=[q commandBuffer]; BufferSpy *s=spy(sideCB);
    CHECK(encode(f,(id<MTLCommandBuffer>)s,&old,eligible));
    CHECK(!s->forbidden && sideCB.status==MTLCommandBufferStatusNotEnqueued);
    [sideCB commit];
    id<MTLCommandBuffer> downstream=[q commandBuffer]; readback(downstream,f);
    if (finish(downstream)) { expectPixels(f,YES); expectArgs(f,1); }
    CHECK(upstream.status==MTLCommandBufferStatusCompleted && sideCB.status==MTLCommandBufferStatusCompleted);
}

static void warpIntegration(id<MTLDevice> d, id<MTLCommandQueue> q, MCMTLFarResolve *r) {
    NSError *error=nil; MCMTLFarWarp *warp=[[MCMTLFarWarp alloc] initWithDevice:d error:&error];
    CHECK(warp && !error); if (!warp) return;
    Fixture *f=fixture(d,q,r,9,7,5,NO); fill(f);
    id<MTLTexture> source[2][3];
    id<MTLCommandBuffer> cb=[q commandBuffer]; seed(cb,f,NO,YES);
    id<MTLBlitCommandEncoder> b=[cb blitCommandEncoder];
    for (unsigned l=0;l<2;++l) for (unsigned p=0;p<3;++p) {
        source[l][p]=texture(d,p==1 ? MTLPixelFormatDepth32Float:formats[p],f->w,f->h,
                            MTLTextureUsageShaderRead,MTLHazardTrackingModeTracked);
        copyIn(b,f->upload[0][l][p],source[l][p],f->row);
    }
    [b endEncoding];
    for (unsigned i=0;i<2;++i) for (unsigned l=0;l<2;++l) {
        MCMTLFarWarpTransform t={.motion=MCMTLFarWarpMotionPureRotation};
        for (unsigned k=0;k<16;k+=5) t.currentToSourceClip[k]=t.sourceToCurrentClip[k]=1;
        if (i==0) { t.currentToSourceClip[12]=(float)(2.0/f->w); t.sourceToCurrentClip[12]=(float)(-2.0/f->w); }
        MCMTLFarWarpSource src={source[l][0],source[l][1],source[l][2]};
        CHECK([warp encodeToCommandBuffer:cb source:src targets:pair(f,i).layers[l] transform:&t error:&error] && !error);
    }
    MCMTLFarResolvePair older=pair(f,1);
    CHECK(encode(f,cb,&older,eligible)); readback(cb,f); if (!finish(cb)) return;
    expectArgs(f,0);
    for (unsigned l=0;l<2;++l) for (NSUInteger y=0;y<f->h;++y) for (NSUInteger x=0;x<f->w;++x) {
        NSUInteger sx=x+1<f->w ? x+1:x;
        for (unsigned p=0;p<3;++p)
            CHECK(!memcmp(pixel(f->readback[l][p],f,p,x,y),pixel(f->upload[0][l][p],f,p,sx,y),4));
        uint8_t expected=*(uint32_t *)pixel(f->upload[0][l][2],f,2,sx,y) ? 2:3;
        CHECK(*(uint8_t *)pixel(f->readback[l][3],f,3,x,y)==expected);
        CHECK(*(uint8_t *)pixel(f->holeRead,f,3,x,y)==0);
    }
    // The same real warp leaves one full uncovered column without older repair.
    cb=[q commandBuffer]; CHECK(encode(f,cb,NULL,eligible)); readback(cb,f);
    if (finish(cb)) expectArgs(f,(uint32_t)f->h);
    // Translation rejection leaves the caller on its original synchronous path.
    cb=[q commandBuffer]; BufferSpy *s=spy(cb);
    MCMTLFarWarpTransform t={.motion=MCMTLFarWarpMotionTranslation};
    for (unsigned k=0;k<16;k+=5) t.currentToSourceClip[k]=t.sourceToCurrentClip[k]=1;
    CHECK((![warp encodeToCommandBuffer:(id<MTLCommandBuffer>)s
        source:(MCMTLFarWarpSource){source[0][0],source[0][1],source[0][2]}
        targets:pair(f,0).layers[0] transform:&t error:&error] && error));
    CHECK(s->encoders==0 && !s->forbidden); // Do not resolve stale warp output after NO.
}

int main(void) { @autoreleasepool {
    id<MTLDevice> d=MTLCreateSystemDefaultDevice(); CHECK(d); if (!d) return 1;
    NSError *error=nil;
    CHECK([[MCMTLFarResolve alloc] initWithDevice:d error:&error]==nil && error);
    dispatch_semaphore_t done=dispatch_semaphore_create(0);
    dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED,0), ^{ @autoreleasepool {
        NSError *workerError=nil;
        CHECK([[MCMTLFarResolve alloc] initWithDevice:nil error:&workerError]==nil && workerError);
        MCMTLFarResolve *r=[[MCMTLFarResolve alloc] initWithDevice:d error:&workerError];
        CHECK(r && !workerError); if (!r) fprintf(stderr,"%s\n",workerError.description.UTF8String);
        id<MTLCommandQueue> q=[d newCommandQueue]; CHECK(q);
        if (r && q) {
            mergeTests(d,q,r); validationTests(d,q,r); lifecycleTests(d,q,r); warpIntegration(d,q,r);
        }
        dispatch_semaphore_signal(done);
    }});
    if (dispatch_semaphore_wait(done,dispatch_time(DISPATCH_TIME_NOW,120*NSEC_PER_SEC))) {
        fprintf(stderr,"farimage_resolve_test: harness timeout\n"); return 1;
    }
    printf("farimage_resolve_test: %u checks, %u failures\n",checks,failures);
    return failures ? 1:0;
} }
