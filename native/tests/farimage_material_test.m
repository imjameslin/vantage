/* Parent registers/runs under its GPU lock. Do not run alongside a game probe.
 * CPU doubles reject hidden queue/event work; real Metal coverage checks every
 * parent mip and the restricted sampling view across the main/far event boundary.
 */
#import "mcmetal_farmaterial.h"
#include <dispatch/dispatch.h>
#include <math.h>
#include <stdio.h>
#include <string.h>

static unsigned checks, failures;
#define CHECK(x) do { ++checks; if (!(x)) { ++failures; fprintf(stderr,"FAIL %d: %s\n",__LINE__,#x); } } while (0)
static mcmtl_handle h(id o) { return (mcmtl_handle)(uintptr_t)(__bridge void *)o; }

/* Deliberately not full Metal protocol implementations. Only metadata/texture
 * allocation is available. An unexpected selector fails the test, and explicit
 * queue/event/poll traps make the no-submission contract observable without GPU.
 */
@interface MaterialTestDevice : NSObject
@property(nonatomic) NSUInteger allocations, views, forbidden;
- (id<MTLTexture>)newTextureWithDescriptor:(MTLTextureDescriptor *)d;
@end
@interface MaterialTestTexture : NSObject
@property(nonatomic, strong) id<MTLDevice> device;
@property(nonatomic, strong) id<MTLTexture> parentTexture, remoteStorageTexture;
@property(nonatomic, strong) id<MTLBuffer> buffer;
@property(nonatomic, strong) id<MTLHeap> heap;
@property(nonatomic) NSUInteger width, height, depth, arrayLength, sampleCount, mipmapLevelCount;
@property(nonatomic) NSUInteger parentRelativeLevel, parentRelativeSlice;
@property(nonatomic) MTLTextureType textureType;
@property(nonatomic) MTLPixelFormat pixelFormat;
@property(nonatomic) MTLStorageMode storageMode;
@property(nonatomic) MTLHazardTrackingMode hazardTrackingMode;
@property(nonatomic) MTLTextureUsage usage;
@property(nonatomic) MTLTextureSwizzleChannels swizzle;
@property(nonatomic) BOOL isAliasable, isSparse;
@property(nonatomic, getter=isShareable) BOOL shareable;
@property(nonatomic, getter=isFramebufferOnly) BOOL framebufferOnly;
@property(nonatomic) IOSurfaceRef iosurface;
@end
@implementation MaterialTestTexture
- (instancetype)init {
    if ((self = [super init])) {
        _width=16; _height=8; _depth=_arrayLength=_sampleCount=1; _mipmapLevelCount=5;
        _textureType=MTLTextureType2D; _pixelFormat=MTLPixelFormatRGBA8Unorm;
        _storageMode=MTLStorageModePrivate; _hazardTrackingMode=MTLHazardTrackingModeTracked;
        _usage=MTLTextureUsageShaderRead; _swizzle=MTLTextureSwizzleChannelsDefault;
    }
    return self;
}
- (id<MTLTexture>)newTextureViewWithPixelFormat:(MTLPixelFormat)format textureType:(MTLTextureType)type
    levels:(NSRange)levels slices:(NSRange)slices {
    CHECK(format==self.pixelFormat && type==MTLTextureType2D && slices.location==0 && slices.length==1);
    CHECK(levels.length && levels.location<self.mipmapLevelCount && levels.length<=self.mipmapLevelCount-levels.location);
    ((MaterialTestDevice *)(id)self.device).views++;
    MaterialTestTexture *t=[MaterialTestTexture new]; t.device=self.device;
    t.parentTexture=(id<MTLTexture>)self; t.parentRelativeLevel=levels.location;
    t.width=MAX((NSUInteger)1,self.width>>levels.location); t.height=MAX((NSUInteger)1,self.height>>levels.location);
    t.mipmapLevelCount=levels.length;
    return (id<MTLTexture>)t;
}
@end
@implementation MaterialTestDevice
- (id<MTLTexture>)newTextureWithDescriptor:(MTLTextureDescriptor *)d {
    ++_allocations;
    CHECK(d.width && d.width<=16384 && d.height && d.height<=16384);
    CHECK(d.textureType==MTLTextureType2D && d.pixelFormat==MTLPixelFormatRGBA8Unorm);
    CHECK(d.storageMode==MTLStorageModePrivate && d.hazardTrackingMode==MTLHazardTrackingModeTracked);
    CHECK(d.usage==MTLTextureUsageShaderRead && d.depth==1 && d.arrayLength==1 && d.sampleCount==1);
    MaterialTestTexture *t=[MaterialTestTexture new]; t.device=(id<MTLDevice>)self;
    t.width=d.width; t.height=d.height; t.mipmapLevelCount=d.mipmapLevelCount;
    return (id<MTLTexture>)t;
}
- (id)newCommandQueue { ++_forbidden; CHECK(NO); return nil; }
- (id)newCommandQueueWithMaxCommandBufferCount:(NSUInteger)n { (void)n; return [self newCommandQueue]; }
- (id)newSharedEvent { ++_forbidden; CHECK(NO); return nil; }
@end
@interface MaterialTestSampler : NSObject
@property(nonatomic, strong) id<MTLDevice> device;
@end
@implementation MaterialTestSampler
@end
@interface MaterialTestEvent : NSObject
@property(nonatomic, strong) id<MTLDevice> device;
@end
@implementation MaterialTestEvent
- (uint64_t)signaledValue { ((MaterialTestDevice *)(id)self.device).forbidden++; CHECK(NO); return 0; }
@end

static mcmtl_far_material_source source(id parent, id texture, id sampler, uint32_t base, uint32_t count) {
    return (mcmtl_far_material_source){.parent=h(parent),.texture=h(texture),.sampler=h(sampler),
        .baseLevel=base,.levelCount=count,.samplerLODClamp=1,.lodMin=0.25f,.lodMax=2.5f};
}
static MCMTLFarMaterialRecipe *pin(id device, mcmtl_far_material_source *sources, uint64_t budget) {
    NSError *error=nil;
    MCMTLFarMaterialRecipe *r=[[MCMTLFarMaterialRecipe alloc] initWithDevice:device sources:sources budgetBytes:budget error:&error];
    CHECK((r!=nil)==(error==nil)); return r;
}
static MCMTLFarMaterialVersion *prepare(MCMTLFarMaterialRecipe *r) {
    NSError *error=nil; MCMTLFarMaterialVersion *v=[[MCMTLFarMaterialVersion alloc] initWithRecipe:r error:&error];
    CHECK(v!=nil && error==nil); return v;
}

static void cpuContracts(void) { @autoreleasepool {
    MaterialTestDevice *device=[MaterialTestDevice new];
    MaterialTestTexture *atlas=[MaterialTestTexture new]; atlas.device=(id<MTLDevice>)device;
    MaterialTestTexture *light=[MaterialTestTexture new]; light.device=(id<MTLDevice>)device;
    light.width=light.height=4; light.mipmapLevelCount=1;
    MaterialTestSampler *sampler=[MaterialTestSampler new]; sampler.device=(id<MTLDevice>)device;
    id<MTLTexture> view=[(id<MTLTexture>)atlas newTextureViewWithPixelFormat:atlas.pixelFormat textureType:MTLTextureType2D
        levels:NSMakeRange(1,3) slices:NSMakeRange(0,1)];
    mcmtl_far_material_source sources[2]={source(atlas,view,sampler,1,3),source(light,light,sampler,0,1)};
    const uint64_t bytes=(128+32+8+2+1+16)*4;
    MCMTLFarMaterialRecipe *recipe=pin(device,sources,bytes); CHECK(recipe.copyBytes==bytes);
    CHECK(device.allocations==0 && device.views==1 && device.forbidden==0);
    CHECK([recipe sourceParentAtIndex:0]==(id)atlas && [recipe sourceTextureAtIndex:0]==view);
    CHECK([recipe samplerAtIndex:1]==(id)sampler && [recipe sourceParentAtIndex:2]==nil);
    sources[0].lodMin=99; // The recipe owns value metadata, not the caller's mutable struct.
    CHECK([recipe descriptorAtIndex:0].lodMin==0.25f); sources[0].lodMin=0.25f;
    CHECK(pin(device,sources,bytes-1)==nil && pin(device,sources,0)==nil);

    for (unsigned invalid=0;invalid<11;++invalid) {
        mcmtl_far_material_source bad[2]; memcpy(bad,sources,sizeof bad);
        switch (invalid) {
            case 0: bad[0].baseLevel=UINT32_MAX; break;
            case 1: bad[0].levelCount=UINT32_MAX; break;
            case 2: bad[0].levelCount=0; break;
            case 3: bad[0].baseLevel=2; break; // legal range, wrong live view
            case 4: bad[0].texture=h(atlas); break; // partial metadata with full parent
            case 5: bad[0].samplerLODClamp=2; break;
            case 6: bad[0].lodMin=NAN; break;
            case 7: bad[0].lodMax=INFINITY; break;
            case 8: bad[0].lodMin=-1; break;
            case 9: bad[0].lodMax=0; break;
            case 10: bad[0].reserved=1; break;
        }
        CHECK(pin(device,bad,bytes)==nil);
    }
    // Simulated invalid descriptors never reach a Metal allocation/view call.
#define REJECT(field,value) do { __typeof__(atlas.field) saved=atlas.field; atlas.field=(value); \
    CHECK(pin(device,sources,UINT64_MAX)==nil); atlas.field=saved; } while(0)
    REJECT(width,0); REJECT(height,16385); REJECT(mipmapLevelCount,0); REJECT(mipmapLevelCount,6);
    REJECT(textureType,MTLTextureTypeCube); REJECT(pixelFormat,MTLPixelFormatBGRA8Unorm);
    REJECT(depth,2); REJECT(arrayLength,2); REJECT(sampleCount,4);
    REJECT(storageMode,MTLStorageModeMemoryless); REJECT(hazardTrackingMode,MTLHazardTrackingModeUntracked);
    REJECT(usage,MTLTextureUsageRenderTarget); REJECT(heap,(id<MTLHeap>)device); REJECT(isAliasable,YES);
    REJECT(buffer,(id<MTLBuffer>)device); REJECT(iosurface,(IOSurfaceRef)(uintptr_t)1);
    REJECT(shareable,YES); REJECT(framebufferOnly,YES); REJECT(isSparse,YES);
    REJECT(parentTexture,(id<MTLTexture>)light); REJECT(remoteStorageTexture,(id<MTLTexture>)light);
    REJECT(swizzle,MTLTextureSwizzleChannelsMake(MTLTextureSwizzleBlue,MTLTextureSwizzleGreen,MTLTextureSwizzleRed,MTLTextureSwizzleAlpha));
    MaterialTestDevice *other=[MaterialTestDevice new]; REJECT(device,(id<MTLDevice>)other);
#undef REJECT
    sampler.device=(id<MTLDevice>)other; CHECK(pin(device,sources,bytes)==nil); sampler.device=(id<MTLDevice>)device;
    MaterialTestTexture *mockView=(id)view;
    mockView.parentRelativeSlice=1; CHECK(pin(device,sources,bytes)==nil); mockView.parentRelativeSlice=0;
    mockView.parentTexture=(id<MTLTexture>)light; CHECK(pin(device,sources,bytes)==nil); mockView.parentTexture=(id<MTLTexture>)atlas;
    CHECK(device.allocations==0 && device.views==1 && device.forbidden==0);

    MCMTLFarMaterialVersion *version=prepare(recipe);
    CHECK(device.allocations==2 && device.views==2 && device.forbidden==0);
    CHECK(version.copyJobCount==6 && !version.published && !version.readyEvent && !version.readyValue);
    mcmtl_far_material_binding binding=[version bindingAtIndex:0];
    CHECK(binding.desc.baseLevel==1 && binding.desc.levelCount==3 && binding.desc.mipLevels==5);
    CHECK(binding.sampler==h(sampler) && binding.desc.samplerLODClamp==1 && binding.desc.lodMax==2.5f);
    CHECK(![version ownsImmutableFarTexture:MCMTL_TEXTURE(binding.texture)]);
    CHECK(![version ownsImmutableFarTexture:(id)atlas] && ![version ownsImmutableFarTexture:view]);
    for (uint32_t i=0;i<6;++i) {
        mcmtl_far_material_copy_job job={0}; CHECK([version copyJobAtIndex:i into:&job]);
        CHECK(job.materialIndex==(i<5?0u:1u) && job.level==(i<5?i:0));
        CHECK(job.source==(i<5?h(atlas):h(light)) && job.destination!=job.source);
        CHECK(job.width==MAX(1u,(i<5?16u:4u)>>job.level) && job.height==MAX(1u,(i<5?8u:4u)>>job.level));
    }
    mcmtl_far_material_copy_job noJob={.source=99};
    CHECK(![version copyJobAtIndex:NSUIntegerMax into:&noJob] && noJob.source==0);
    MaterialTestEvent *event=[MaterialTestEvent new]; event.device=(id<MTLDevice>)device;
    mcmtl_far_material_copy_dependency dependency={h(event),42,6,1};
    NSError *error=nil;
    CHECK([version publishCommittedCopies:dependency error:&error] && !error);
    CHECK(version.published && version.readyEvent==(id)event && version.readyValue==42);
    CHECK([version ownsImmutableFarTexture:MCMTL_TEXTURE(binding.parent)] && [version ownsImmutableFarTexture:MCMTL_TEXTURE(binding.texture)]);
    CHECK(![version ownsImmutableFarTexture:(id)atlas] && ![version ownsImmutableFarTexture:view] && ![version ownsImmutableFarTexture:nil]);
    id<MTLTexture> alias=[MCMTL_TEXTURE(binding.parent) newTextureViewWithPixelFormat:MTLPixelFormatRGBA8Unorm
        textureType:MTLTextureType2D levels:NSMakeRange(0,1) slices:NSMakeRange(0,1)];
    CHECK(![version ownsImmutableFarTexture:alias]); // Only the exact owned parent/view identities are admitted.
    CHECK(![version copyJobAtIndex:0 into:&noJob] && noJob.source==0);
    dependency.value=43;
    CHECK(![version publishCommittedCopies:dependency error:&error] && error && version.readyValue==42);
    CHECK([version ownsImmutableFarTexture:MCMTL_TEXTURE(binding.texture)]);
    // Real unvalidated MTLSharedEvent reports device=nil (MTLEvent.h). Validation's
    // wrapper may instead return its device, so exercise both representations.
    MaterialTestEvent *shared=[MaterialTestEvent new]; shared.device=nil;
    MCMTLFarMaterialVersion *sharedVersion=prepare(recipe);
    mcmtl_far_material_copy_dependency sharedDependency={h(shared),43,6,1};
    CHECK([sharedVersion publishCommittedCopies:sharedDependency error:&error] && !error);
    CHECK(sharedVersion.published && sharedVersion.readyEvent==(id)shared && sharedVersion.readyValue==43);
    for (unsigned invalid=0;invalid<6;++invalid) {
        MCMTLFarMaterialVersion *badVersion=prepare(recipe);
        mcmtl_far_material_binding b=[badVersion bindingAtIndex:0];
        mcmtl_far_material_copy_dependency bad={h(event),42,6,1};
        MaterialTestEvent *wrongEvent=[MaterialTestEvent new]; wrongEvent.device=(id<MTLDevice>)other;
        switch (invalid) {
            case 0: bad.committed=0; break;
            case 1: bad.copyCount=5; break;
            case 2: bad.value=0; break;
            case 3: bad.event=0; break;
            case 4: bad.event=h(wrongEvent); break;
            case 5: bad.committed=2; break;
        }
        CHECK(![badVersion publishCommittedCopies:bad error:&error] && error);
        CHECK(!badVersion.published && !badVersion.readyEvent && !badVersion.readyValue);
        CHECK(![badVersion ownsImmutableFarTexture:MCMTL_TEXTURE(b.parent)] && [badVersion bindingAtIndex:0].parent==0);
        CHECK(![badVersion copyJobAtIndex:0 into:&noJob]);
        CHECK(![badVersion publishCommittedCopies:dependency error:&error]); // Cannot revive a failed version.
        CHECK(![version ownsImmutableFarTexture:MCMTL_TEXTURE(b.parent)]);
    }
    atlas.width=atlas.height=16384; atlas.mipmapLevelCount=15;
    mcmtl_far_material_source maximum[2]={source(atlas,atlas,sampler,0,15),source(atlas,atlas,sampler,0,15)};
    MCMTLFarMaterialRecipe *maxRecipe=pin(device,maximum,UINT64_MAX);
    MCMTLFarMaterialVersion *maxVersion=prepare(maxRecipe);
    CHECK(maxVersion.copyJobCount==MCMTLFarMaterialMaxCopyJobs);
    CHECK([maxVersion copyJobAtIndex:29 into:&noJob] && noJob.level==14 && noJob.width==1 && noJob.height==1);
    CHECK(device.forbidden==0);
} }

static void ffmLifetime(void) { @autoreleasepool {
    MaterialTestDevice *device=[MaterialTestDevice new];
    MCMTLDevice *wrapper=[MCMTLDevice new]; wrapper->device=(id<MTLDevice>)device;
    __weak id weakSource, weakView, weakSampler, weakParent, weakDestView;
    mcmtl_handle version=0, retainedParent=0;
    char error[128]={0};
    @autoreleasepool {
        MaterialTestTexture *parent=[MaterialTestTexture new]; parent.device=(id<MTLDevice>)device;
        MaterialTestSampler *sampler=[MaterialTestSampler new]; sampler.device=(id<MTLDevice>)device;
        id<MTLTexture> view=[(id<MTLTexture>)parent newTextureViewWithPixelFormat:parent.pixelFormat
            textureType:MTLTextureType2D levels:NSMakeRange(1,3) slices:NSMakeRange(0,1)];
        mcmtl_far_material_source sources[2]={source(parent,view,sampler,1,3),source(parent,parent,sampler,0,5)};
        weakSource=parent; weakView=view; weakSampler=sampler;
        mcmtl_handle recipe=0;
        CHECK(mcmtl_ext_far_material_pin(h(wrapper),sources,4096,&recipe,error,sizeof error)==MCMTL_OK && recipe && !error[0]);
        CHECK(mcmtl_ext_far_material_prepare(recipe,&version,error,sizeof error)==MCMTL_OK && version && !error[0]);
        mcmtl_ext_far_material_drop(recipe);
        CHECK(mcmtl_ext_far_material_copy_count(version)==10);
        mcmtl_far_material_binding b={0}; CHECK(mcmtl_ext_far_material_binding(version,0,&b)==MCMTL_OK);
        weakParent=MCMTL_OBJ(b.parent); weakDestView=MCMTL_OBJ(b.texture);
        CHECK(mcmtl_ext_far_material_retain_parent(version,0,&retainedParent)==MCMTL_OK && retainedParent==b.parent);
    }
    CHECK(weakSource && weakView && weakSampler && weakParent && weakDestView);
    @autoreleasepool {
        MaterialTestEvent *event=[MaterialTestEvent new]; event.device=(id<MTLDevice>)device;
        mcmtl_far_material_copy_dependency dep={h(event),99,10,1};
        CHECK(mcmtl_ext_far_material_publish(version,&dep,error,sizeof error)==MCMTL_OK && !error[0]);
    }
    CHECK(!weakSource && !weakView && weakSampler); // Successful commit contract drops source pin, keeps exact sampler.
    @autoreleasepool { mcmtl_ext_far_material_drop(version); }
    CHECK(!weakSampler && !weakDestView && weakParent); // Java's independent +1 parent survives the lease.
    mcmtl_handle_release(retainedParent); CHECK(!weakParent);
    mcmtl_far_material_binding b={.parent=99}; mcmtl_far_material_copy_job job={.source=99};
    CHECK(mcmtl_ext_far_material_binding(0,0,&b)==MCMTL_E_INVALID_ARGUMENT && b.parent==0);
    CHECK(mcmtl_ext_far_material_copy_job(0,0,&job)==MCMTL_E_INVALID_ARGUMENT && job.source==0);
    CHECK(mcmtl_ext_far_material_prepare(0,&version,error,sizeof error)==MCMTL_E_INVALID_ARGUMENT && !version);
    CHECK(mcmtl_ext_far_material_copy_count(0)==0);
    mcmtl_ext_far_material_drop(0); CHECK(device.forbidden==0);
} }

static NSUInteger extent(NSUInteger n, NSUInteger mip) { return MAX((NSUInteger)1,n>>mip); }
static uint8_t pixel(unsigned seed, NSUInteger mip, NSUInteger x, NSUInteger y, unsigned channel) {
    return (uint8_t)(seed + 29*mip + 3*x + 7*y + 61*channel);
}
static id<MTLTexture> texture(id<MTLDevice> device, NSUInteger width, NSUInteger height, NSUInteger mips) {
    MTLTextureDescriptor *d=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm
        width:width height:height mipmapped:NO];
    d.mipmapLevelCount=mips; d.storageMode=MTLStorageModePrivate;
    d.hazardTrackingMode=MTLHazardTrackingModeTracked; d.usage=MTLTextureUsageShaderRead;
    id<MTLTexture> t=[device newTextureWithDescriptor:d]; CHECK(t!=nil); return t;
}
static void upload(id<MTLBlitCommandEncoder> blit, id<MTLTexture> t, unsigned seed) {
    for (NSUInteger mip=0;mip<t.mipmapLevelCount;++mip) {
        NSUInteger w=extent(t.width,mip), height=extent(t.height,mip), row=256;
        id<MTLBuffer> b=[t.device newBufferWithLength:row*height options:MTLResourceStorageModeShared]; CHECK(b!=nil);
        uint8_t *data=b.contents;
        for (NSUInteger y=0;y<height;++y) for (NSUInteger x=0;x<w;++x)
            for (unsigned c=0;c<4;++c) data[y*row+x*4+c]=pixel(seed,mip,x,y,c);
        [blit copyFromBuffer:b sourceOffset:0 sourceBytesPerRow:row sourceBytesPerImage:row*height
            sourceSize:MTLSizeMake(w,height,1) toTexture:t destinationSlice:0 destinationLevel:mip destinationOrigin:MTLOriginMake(0,0,0)];
    }
}
@interface MaterialRead : NSObject
@property(nonatomic, strong) id<MTLBuffer> buffer;
@property(nonatomic) NSUInteger width, height, expectedMip;
@property(nonatomic) unsigned seed;
@end
@implementation MaterialRead
@end
static void readTexture(id<MTLBlitCommandEncoder> blit, id<MTLTexture> t, NSUInteger base,
    unsigned seed, NSMutableArray<MaterialRead *> *reads) {
    for (NSUInteger mip=0;mip<t.mipmapLevelCount;++mip) {
        MaterialRead *r=[MaterialRead new]; r.width=extent(t.width,mip); r.height=extent(t.height,mip);
        r.expectedMip=base+mip; r.seed=seed;
        r.buffer=[t.device newBufferWithLength:256*r.height options:MTLResourceStorageModeShared]; CHECK(r.buffer!=nil);
        [blit copyFromTexture:t sourceSlice:0 sourceLevel:mip sourceOrigin:MTLOriginMake(0,0,0)
            sourceSize:MTLSizeMake(r.width,r.height,1) toBuffer:r.buffer destinationOffset:0
            destinationBytesPerRow:256 destinationBytesPerImage:256*r.height];
        [reads addObject:r];
    }
}
static void verify(NSArray<MaterialRead *> *reads) {
    for (MaterialRead *r in reads) {
        const uint8_t *data=r.buffer.contents; BOOL equal=YES;
        for (NSUInteger y=0;y<r.height;++y) for (NSUInteger x=0;x<r.width;++x)
            for (unsigned c=0;c<4;++c) if (data[y*256+x*4+c]!=pixel(r.seed,r.expectedMip,x,y,c)) equal=NO;
        CHECK(equal);
    }
}

static void gpuParity(void) { @autoreleasepool {
    id<MTLDevice> device=MTLCreateSystemDefaultDevice(); CHECK(device!=nil); if (!device) return;
    id<MTLCommandQueue> mainQueue=[device newCommandQueue], farQueue=[device newCommandQueue];
    id<MTLSharedEvent> event=[device newSharedEvent], gate=[device newSharedEvent];
    CHECK(mainQueue && farQueue && event && gate); if (!mainQueue || !farQueue || !event || !gate) return;
    id<MTLTexture> atlas=texture(device,16,8,5), light=texture(device,4,4,1);
    id<MTLTexture> view=[atlas newTextureViewWithPixelFormat:atlas.pixelFormat textureType:MTLTextureType2D
        levels:NSMakeRange(1,3) slices:NSMakeRange(0,1)];
    MTLSamplerDescriptor *sd=[MTLSamplerDescriptor new]; sd.minFilter=sd.magFilter=MTLSamplerMinMagFilterLinear;
    sd.mipFilter=MTLSamplerMipFilterLinear; sd.lodMinClamp=0.125f; sd.lodMaxClamp=4;
    sd.sAddressMode=MTLSamplerAddressModeRepeat; sd.tAddressMode=MTLSamplerAddressModeClampToEdge;
    id<MTLSamplerState> sampler=[device newSamplerStateWithDescriptor:sd]; CHECK(view && sampler);
    mcmtl_far_material_source sources[2]={source(atlas,view,sampler,1,3),source(light,light,sampler,0,1)};
    sources[1].samplerLODClamp=0;
    MCMTLFarMaterialRecipe *recipe=pin(device,sources,4096); if (!recipe) return;
    __block MCMTLFarMaterialVersion *version=nil;
    dispatch_sync(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED,0), ^{ @autoreleasepool { version=prepare(recipe); } });
    if (!version) return;
    mcmtl_far_material_binding bindings[2]={[version bindingAtIndex:0],[version bindingAtIndex:1]};
    CHECK(bindings[0].sampler==h(sampler) && bindings[0].desc.baseLevel==1 && bindings[0].desc.levelCount==3);
    CHECK(bindings[0].desc.samplerLODClamp==1 && bindings[0].desc.lodMin==0.25f && bindings[0].desc.lodMax==2.5f);
    CHECK(bindings[1].desc.samplerLODClamp==0 && bindings[1].parent==bindings[1].texture);
    id<MTLCommandBuffer> main=[mainQueue commandBuffer];
    [main encodeWaitForEvent:gate value:1]; // Make publication-before-completion deterministic.
    id<MTLBlitCommandEncoder> blit=[main blitCommandEncoder]; upload(blit,atlas,19); upload(blit,light,127);
    for (NSUInteger i=0;i<version.copyJobCount;++i) {
        mcmtl_far_material_copy_job job={0}; CHECK([version copyJobAtIndex:i into:&job]);
        [blit copyFromTexture:MCMTL_TEXTURE(job.source) sourceSlice:0 sourceLevel:job.level sourceOrigin:MTLOriginMake(0,0,0)
            sourceSize:MTLSizeMake(job.width,job.height,1) toTexture:MCMTL_TEXTURE(job.destination)
            destinationSlice:0 destinationLevel:job.level destinationOrigin:MTLOriginMake(0,0,0)];
    }
    [blit endEncoding]; [main encodeSignalEvent:event value:7];
    CHECK(main.status==MTLCommandBufferStatusNotEnqueued && event.signaledValue==0);
    CHECK(![version ownsImmutableFarTexture:MCMTL_TEXTURE(bindings[0].texture)]);
    [main commit]; // Only this test/coordinator submits the MAIN copy work.
    NSError *error=nil; mcmtl_far_material_copy_dependency dep={h(event),7,(uint32_t)version.copyJobCount,1};
    CHECK([version publishCommittedCopies:dep error:&error] && !error && event.signaledValue==0);
    recipe=nil; // Version now owns only its destinations, sampler, metadata and dependency.
    for (unsigned pass=0;pass<2;++pass) {
        if (pass) {
            main=[mainQueue commandBuffer]; blit=[main blitCommandEncoder];
            upload(blit,atlas,116); upload(blit,light,224); [blit endEncoding];
            [main encodeSignalEvent:event value:8]; [main commit];
        }
        id<MTLCommandBuffer> far=[farQueue commandBuffer];
        [far encodeWaitForEvent:version.readyEvent value:pass?8:version.readyValue];
        blit=[far blitCommandEncoder]; NSMutableArray<MaterialRead *> *reads=[NSMutableArray array];
        readTexture(blit,MCMTL_TEXTURE(bindings[0].parent),0,19,reads);
        readTexture(blit,MCMTL_TEXTURE(bindings[0].texture),1,19,reads);
        readTexture(blit,MCMTL_TEXTURE(bindings[1].texture),0,127,reads);
        readTexture(blit,atlas,0,pass?116:19,reads); readTexture(blit,view,1,pass?116:19,reads);
        readTexture(blit,light,0,pass?224:127,reads); [blit endEncoding];
        // Register before commit; Metal disallows adding handlers after completion.
        dispatch_semaphore_t done=dispatch_semaphore_create(0);
        [far addCompletedHandler:^(id<MTLCommandBuffer> ignored) { (void)ignored; dispatch_semaphore_signal(done); }];
        [far commit]; if (!pass) gate.signaledValue=1;
        BOOL finished=dispatch_semaphore_wait(done,dispatch_time(DISPATCH_TIME_NOW,10*NSEC_PER_SEC))==0;
        CHECK(finished && far.status==MTLCommandBufferStatusCompleted);
        if (!finished || far.status!=MTLCommandBufferStatusCompleted) {
            fprintf(stderr,"far material GPU: %s\n",far.error.description.UTF8String); return;
        }
        verify(reads);
        CHECK([version ownsImmutableFarTexture:MCMTL_TEXTURE(bindings[0].texture)] && ![version ownsImmutableFarTexture:atlas]);
    }
} }

int main(void) { @autoreleasepool {
    cpuContracts(); ffmLifetime(); gpuParity();
    printf("farimage_material_test: %u checks, %u failures\n",checks,failures);
    return failures?1:0;
} }
