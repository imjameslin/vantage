/* Real engine leases, current-main-queue argument ordering and nonblocking declines.
 * Run under tools/gpulock.py functional (MTL_DEBUG_LAYER=1).
 * GPU waits below are bounded TEST-ONLY waits on the worker, never the consumer.
 */
#import "mcmetal_farconsumer.h"
#import "mcmetal_farengine.h"
#import "mcmetal_farmaterial.h"
#import "mcmetal_farwarp.h"
#import "mcmetal_farimage.h"
#include <dispatch/dispatch.h>
#include <math.h>
#include <simd/simd.h>
#include <stdatomic.h>
#include <stdio.h>
#include <string.h>
#include <time.h>

_Static_assert(sizeof(mcmtl_far_consumer_outputs)==64, "output ABI");
_Static_assert(sizeof(mcmtl_far_consumer_stats)==80, "stats ABI");
_Static_assert(sizeof(MCMTLFarWarpTransform)==144, "transform ABI");
_Static_assert(sizeof(mcmtl_far_consumer_fallback)==16, "fallback ABI");
static unsigned checks, failures;
#define CHECK(x) do { ++checks; if (!(x)) { ++failures; fprintf(stderr,"FAIL %d: %s\n",__LINE__,#x); } } while (0)
#import "farimage_shader_fixture.h"
static const NSUInteger W=64, H=64;
static mcmtl_handle h(id o) { return (mcmtl_handle)(uintptr_t)(__bridge void *)o; }
static id obj(mcmtl_handle value) { return (__bridge id)(void *)(uintptr_t)value; }
static id<MTLBuffer> buffer(id<MTLDevice> d, const void *data, NSUInteger length) {
    id<MTLBuffer> b = [d newBufferWithBytes:data length:length options:MTLResourceStorageModeShared];
    CHECK(b != nil); return b;
}
@interface ProducerFixture : NSObject
@property id<MTLDevice> device;
@property id<MTLCommandQueue> queue;
@property id<MTLBuffer> projection, globals, fog, terrain, sections, arenaA, arenaB, itemsA, itemsB, quads, indices;
@property id<MTLTexture> atlas, lightmap;
@property id<MTLSamplerState> sampler;
@end
@implementation ProducerFixture
@end

/* One encoded arena face, independently authored equivalent BLOCK and compact
 * geometry. q=25 and q=103 force nonzero item.x and low6=63. The second arena
 * uses q=5 with a different base and an equal-depth quad drawn last. */
static void face(void *dst, MCMTLFarShaderLayout layout, unsigned q,
                 float x, float y, float sx, float sy, float z, BOOL checker) {
    const uint32_t color[4] = {0xff80c0ff, 0xffc0ff80, 0xffff80c0, 0xffd0a0e0};
    const float corners[4][2] = {{0,0},{1,0},{1,1},{0,1}};
    float u = checker ? 0 : 0.75f, v = checker ? 0 : 0.5f;
    float du = checker ? 0.5f : 0, dv = checker ? 1 : 0;
    if (layout == MCMTLFarShaderBlock) {
        uint32_t *w = (uint32_t *)dst + q * 28;
        for (unsigned k = 0; k < 4; ++k, w += 7) {
            float p[3] = {x + sx * corners[k][0], y + sy * corners[k][1], z};
            float uv[2] = {u + du * corners[k][0], v + dv * corners[k][1]};
            memcpy(w, p, 12); w[3] = color[k]; memcpy(w + 4, uv, 8); w[6] = 0x00e000e0;
        }
    } else {
        uint32_t *r = (uint32_t *)dst + q * 12;
        r[0] = (uint32_t)(x * 32) | ((uint32_t)(y * 32) << 10) | ((uint32_t)(z * 32) << 20);
        r[1] = ((uint32_t)(sx * 32) << 2) | (1u << 14) | ((uint32_t)(sy * 32) << 16);
        memcpy(r + 2, color, 16); r[6] = r[7] = 0xe0e0e0e0;
        r[8] = (uint32_t)(u * 32 * 16); r[9] = (uint32_t)(v * 16 * 16);
        r[10] = (uint32_t)(du * 32 * 16); r[11] = (uint32_t)(dv * 16 * 16) << 16;
    }
}
static ProducerFixture *fixture(id<MTLDevice> d, MCMTLFarShaderLayout layout) {
    ProducerFixture *f = [ProducerFixture new]; f.device = d; f.queue = [d newCommandQueue]; CHECK(f.queue != nil);
    matrix_float4x4 projection = matrix_identity_float4x4;
    projection.columns[0].x = 0.125f; projection.columns[1].y = 0.125f;
    projection.columns[2].z = 0.0625f; projection.columns[3].x = projection.columns[3].y = -1;
    f.projection = buffer(d, &projection, sizeof projection);
    uint8_t globals[48] = {0}; float screen[2] = {64,64}; memcpy(globals + 32, screen, 8);
    ((int *)globals)[11] = 1; /* Runtime path really exercises RGSS. */
    f.globals = buffer(d, globals, sizeof globals);
    /* Nontrivial color fog but no transparency change; paired fog may differ
     * from baseline, so parity is always checked against its own exact variant. */
    float fog[12] = {0.18f,0.28f,0.38f,1, 8,80, 16,80, 100,100, 32,0.5f};
    f.fog = buffer(d, fog, sizeof fog);
    uint8_t terrain[80] = {0}; matrix_float4x4 model = matrix_identity_float4x4;
    memcpy(terrain, &model, sizeof model); ((int *)terrain)[16] = 32; ((int *)terrain)[17] = 16;
    f.terrain = buffer(d, terrain, sizeof terrain);
    uint32_t sections[8] = {0,0,0,0x3f800000, 0,0,0,0x3f800000};
    f.sections = buffer(d, sections, sizeof sections);
    NSUInteger stride = layout == MCMTLFarShaderBlock ? 112 : 48;
    NSMutableData *a = [NSMutableData dataWithLength:128 * stride], *b = [NSMutableData dataWithLength:6 * stride];
    face(a.mutableBytes, layout, 25, 0,0,8,16,4,NO);
    face(a.mutableBytes, layout, 103, 2,2,12,12,12,YES);
    face(b.mutableBytes, layout, 5, 4,4,4,4,12,NO);
    f.arenaA = buffer(d, a.bytes, a.length); f.arenaB = buffer(d, b.bytes, b.length);
    uint32_t itemsA[4] = {20*4,0, 40*4,1}, itemsB[2] = {4*4,0};
    f.itemsA = buffer(d, itemsA, sizeof itemsA); f.itemsB = buffer(d, itemsB, sizeof itemsB);
    uint32_t quads[5] = {5,64|63, 64|63,5, 1};
    f.quads = buffer(d, quads, sizeof quads);
    uint32_t indices[12] = {0,1,2,2,3,0, 4,5,6,6,7,4}; f.indices = buffer(d, indices, sizeof indices);
    MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:32 height:16 mipmapped:NO];
    td.storageMode = MTLStorageModeShared; td.usage = MTLTextureUsageShaderRead;
    f.atlas = [d newTextureWithDescriptor:td];
    uint8_t pixels[32 * 16 * 4];
    for (unsigned y = 0; y < 16; ++y) for (unsigned x = 0; x < 32; ++x) {
        unsigned o = (y * 32 + x) * 4;
        pixels[o] = 70 + x * 4; pixels[o+1] = 100 + y * 4; pixels[o+2] = 190;
        pixels[o+3] = x < 16 && ((x / 4 + y / 4) & 1) ? 0 : 255;
    }
    [f.atlas replaceRegion:MTLRegionMake2D(0,0,32,16) mipmapLevel:0 withBytes:pixels bytesPerRow:128];
    td.width = td.height = 1; f.lightmap = [d newTextureWithDescriptor:td];
    uint8_t light[4] = {240,230,220,255};
    [f.lightmap replaceRegion:MTLRegionMake2D(0,0,1,1) mipmapLevel:0 withBytes:light bytesPerRow:4];
    MTLSamplerDescriptor *sd = [MTLSamplerDescriptor new]; sd.minFilter = sd.magFilter = MTLSamplerMinMagFilterLinear;
    sd.sAddressMode = sd.tAddressMode = MTLSamplerAddressModeClampToEdge;
    f.sampler = [d newSamplerStateWithDescriptor:sd]; CHECK(f.atlas && f.lightmap && f.sampler);
    return f;
}


static MCMTLPipeline *originalPipeline(MCMTLDevice *owner, NSString *v, NSString *f,
    NSString *ve, NSString *fe, MTLCompileOptions *opts, unsigned vary, BOOL maskGreen) {
    NSError *error = nil;
    id<MTLLibrary> vl = [owner->device newLibraryWithSource:v options:opts error:&error];
    CHECK(vl != nil); if (!vl) { fprintf(stderr, "%s\n", error.description.UTF8String); return nil; }
    id<MTLLibrary> fl = [owner->device newLibraryWithSource:f options:opts error:&error];
    CHECK(fl != nil); if (!fl) { fprintf(stderr, "%s\n", error.description.UTF8String); return nil; }
    MCMTLPipeline *p = [MCMTLPipeline new]; p->owner = owner; p->label = @"farshader test builtin cutout/solid";
    MTLRenderPipelineDescriptor *d = [MTLRenderPipelineDescriptor new]; p->descriptorTemplate = d;
    d.vertexFunction = [vl newFunctionWithName:ve]; d.fragmentFunction = [fl newFunctionWithName:fe];
    d.colorAttachments[0].pixelFormat = MTLPixelFormatRGBA8Unorm;
    d.colorAttachments[0].writeMask = maskGreen ? MTLColorWriteMaskRed | MTLColorWriteMaskBlue | MTLColorWriteMaskAlpha : MTLColorWriteMaskAll;
    /* Nondefault dormant blend settings must survive even with blending off. */
    d.colorAttachments[0].rgbBlendOperation = MTLBlendOperationSubtract;
    d.colorAttachments[0].sourceRGBBlendFactor = MTLBlendFactorSourceAlpha;
    d.colorAttachments[0].destinationRGBBlendFactor = MTLBlendFactorOneMinusSourceAlpha;
    d.inputPrimitiveTopology = MTLPrimitiveTopologyClassTriangle;
    if (vary == 2) { d.vertexDescriptor = gameVertexDescriptor(); d.inputPrimitiveTopology = MTLPrimitiveTopologyClassUnspecified; }
    d.rasterSampleCount = 1; d.supportIndirectCommandBuffers = NO;
    /* Like the production template, depth format is supplied by the variant. */
    d.depthAttachmentPixelFormat = MTLPixelFormatInvalid;
    p->colorTargetCount = 1; p->colorTargetFormats[0] = MTLPixelFormatRGBA8Unorm;
    MTLDepthStencilDescriptor *dd = [MTLDepthStencilDescriptor new];
    dd.depthCompareFunction = MTLCompareFunctionGreaterEqual; dd.depthWriteEnabled = YES;
    p->depthStencilState = [owner->device newDepthStencilStateWithDescriptor:dd];
    p->primitiveType = MTLPrimitiveTypeTriangle; p->fillMode = MTLTriangleFillModeFill;
    p->frontFacing = MTLWindingClockwise; p->cullMode = MTLCullModeBack;
    p->requiredVertexBufferMask = 15u << 27;
    p->bufferMask[0] = (1u << 0) | (1u << 1) | (1u << 5) | (vary ? 1u << 2 : 0);
    p->bufferMask[1] = (1u << 1) | (1u << 2) | (1u << 5);
    p->textureMask[0] = p->samplerMask[0] = 1u << 2; p->textureMask[1] = p->samplerMask[1] = 1;
    /* Sentinels: compiling the sidecar may not read/append/rewrite these caches. */
    p->variantCount = 1; p->variantKeys[0] = 0xdeadbeef;
    p->overflowVariants = [NSMutableDictionary dictionary];
    CHECK(d.vertexFunction && d.fragmentFunction && p->depthStencilState);
    return p;
}

static MCMTLFarGeometryRecipe *geometry(MCMTLDevice *main, ProducerFixture *block, ProducerFixture *compact) {
    mcmtl_far_geometry_input desc[4]={0};
    uint32_t codes[4]={0,1,8,9};
    for (unsigned i=0; i<4; ++i) {
        ProducerFixture *f=i<2 ? block : compact;
        desc[i]=(mcmtl_far_geometry_input){h(f.arenaA),0,f.arenaA.length,h(f.indices),0,f.indices.length,codes[i],i<2?137:1000};
    }
    static uint64_t token=100;
    mcmtl_handle pins=0;
    CHECK(mcmtl_ext_far_geometry_pin(h(main),token++ ,desc,4,&pins)==MCMTL_OK);
    if (!pins) return nil;
    // Real q=25 face bounds in 1/16 units, biased by 512. Keep them usable:
    // bit29 would deliberately bypass frustum tests and hide a view-override bug.
    uint32_t item[4]={25*4,0,512u|(512u<<10)|(576u<<20),640u|(768u<<10)|(576u<<20)};
    float cull[8]={0}; cull[3]=1.0f/16.0f;
    uint32_t view[32]={0}; ((float *)view)[0]=-1; ((float *)view)[3]=16; view[21]=5;
    mcmtl_far_bytes items[4];
    for (unsigned i=0; i<4; ++i) items[i]=(mcmtl_far_bytes){item,sizeof item};
    mcmtl_far_bytes tables[3]={{cull,sizeof cull},{block.sections.contents,16},{view,sizeof view}};
    mcmtl_handle recipe=0; char error[256]={0};
    CHECK(mcmtl_ext_far_geometry_capture(pins,items,4,tables,1u<<20,&recipe,error,sizeof error)==MCMTL_OK);
    mcmtl_ext_far_geometry_drop(pins);
    return recipe ? (__bridge_transfer id)(void *)(uintptr_t)recipe : nil;
}

static BOOL completed(id<MTLCommandBuffer> cb) {
    NSDate *until=[NSDate dateWithTimeIntervalSinceNow:10];
    while (cb.status<MTLCommandBufferStatusCompleted && until.timeIntervalSinceNow>0) [NSThread sleepForTimeInterval:0.001];
    CHECK(cb.status==MTLCommandBufferStatusCompleted);
    return cb.status==MTLCommandBufferStatusCompleted;
}
static mcmtl_handle acquireFrame(mcmtl_handle engine, uint64_t epoch, unsigned count, uint64_t frame) {
    NSDate *until=[NSDate dateWithTimeIntervalSinceNow:10];
    mcmtl_handle lease=0; uint32_t found=0;
    do {
        int32_t result=mcmtl_ext_far_engine_acquire(engine,epoch,&lease,&found);
        if (result==MCMTL_OK && found==count) {
            mcmtl_far_engine_image image={.structSize=sizeof image};
            if (!frame || (mcmtl_ext_far_engine_image(lease,0,&image)==MCMTL_OK && image.source.sourceFrame==frame)) return lease;
        }
        mcmtl_ext_far_engine_drop(lease); lease=0;
        if (result<0) break;
        [NSThread sleepForTimeInterval:0.001];
    } while (until.timeIntervalSinceNow>0);
    CHECK(NO); return 0;
}
static mcmtl_handle acquire(mcmtl_handle engine, uint64_t epoch, unsigned count) {
    return acquireFrame(engine,epoch,count,0);
}
static void onMain(dispatch_block_t block) { dispatch_sync(dispatch_get_main_queue(),block); }
static MCMTLFarWarpTransform transform(int cropX) {
    MCMTLFarWarpTransform t={.motion=MCMTLFarWarpMotionPureRotation};
    for (unsigned i=0; i<16; i+=5) t.currentToSourceClip[i]=t.sourceToCurrentClip[i]=1;
    t.currentToSourceClip[12]=(float)(2.0*cropX/W);
    t.sourceToCurrentClip[12]=(float)(-2.0*cropX/W);
    return t;
}
static int32_t encode(mcmtl_handle consumer, mcmtl_handle lease, MCMTLFarWarpTransform *t,
    uint32_t images, const mcmtl_far_consumer_fallback *d, uint32_t count, uint64_t sequence) {
    __block int32_t result=0;
    onMain(^{ char error[512]={0}; result=mcmtl_ext_far_consumer_encode(consumer,lease,t,images,d,count,sequence,error,sizeof error);
        if (result<0 && error[0]) fprintf(stderr,"expected/diagnostic encode: %s\n",error); });
    return result;
}
static mcmtl_far_consumer_stats receipt(mcmtl_handle consumer, uint64_t sequence) {
    mcmtl_far_consumer_stats s={.structSize=sizeof s};
    NSDate *until=[NSDate dateWithTimeIntervalSinceNow:10];
    do {
        int32_t result=mcmtl_ext_far_consumer_stats(consumer,&s);
        if (result==MCMTL_OK && s.encodedSequence==sequence) return s;
        [NSThread sleepForTimeInterval:0.001];
    } while (until.timeIntervalSinceNow>0);
    CHECK(NO); return s;
}
static void dropOutputs(mcmtl_far_consumer_outputs o) {
    for (unsigned i=0;i<2;++i) { mcmtl_ext_far_engine_drop(o.color[i]); mcmtl_ext_far_engine_drop(o.depth[i]); }
    mcmtl_ext_far_engine_drop(o.fallbackArgs); mcmtl_ext_far_engine_drop(o.compositeArgs);
}
@interface ConsumerReadback : NSObject
@property id<MTLBuffer> args, pixels;
@property id<MTLCommandBuffer> command;
@end
@implementation ConsumerReadback
@end
static ConsumerReadback *readback(MCMTLDevice *main, mcmtl_far_consumer_outputs o) { @autoreleasepool {
    ConsumerReadback *r=[ConsumerReadback new];
    r.args=[main->device newBufferWithLength:o.maxDraws*20+16 options:MTLResourceStorageModeShared];
    r.pixels=[main->device newBufferWithLength:4*256*H options:MTLResourceStorageModeShared];
    r.command=[main->queue commandBuffer];
    id<MTLBlitCommandEncoder> b=[r.command blitCommandEncoder];
    [b copyFromBuffer:obj(o.fallbackArgs) sourceOffset:0 toBuffer:r.args destinationOffset:0 size:o.maxDraws*20];
    [b copyFromBuffer:obj(o.compositeArgs) sourceOffset:0 toBuffer:r.args destinationOffset:o.maxDraws*20 size:16];
    mcmtl_handle tex[4]={o.color[0],o.color[1],o.depth[0],o.depth[1]};
    for (unsigned i=0;i<4;++i) [b copyFromTexture:obj(tex[i]) sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,0,0)
        sourceSize:MTLSizeMake(W,H,1) toBuffer:r.pixels destinationOffset:i*256*H
        destinationBytesPerRow:256 destinationBytesPerImage:256*H];
    [b endEncoding]; [r.command commit]; return r;
} }
static BOOL weakGone(__weak id *value) {
    NSDate *until=[NSDate dateWithTimeIntervalSinceNow:10];
    do { @autoreleasepool { if (!*value) return YES; } [NSThread sleepForTimeInterval:0.001]; }
    while (until.timeIntervalSinceNow>0);
    return NO;
}

/* Controlled error-path injection around a real, fully encoded Metal CB. This
 * does not deliberately fault the GPU. Completion mode reports Timeout to every
 * installed handler; commit mode throws while work is still unqueued.
 */
@interface ConsumerFailureBuffer : NSProxy {
@public
    id<MTLCommandBuffer> real;
    BOOL completionFailure, throwCommit, reportFailure;
}
@end
@implementation ConsumerFailureBuffer
- (NSMethodSignature *)methodSignatureForSelector:(SEL)s { return [(NSObject *)real methodSignatureForSelector:s]; }
- (void)forwardInvocation:(NSInvocation *)i { [i invokeWithTarget:real]; }
- (MTLCommandBufferStatus)status { return reportFailure ? MTLCommandBufferStatusError : real.status; }
- (NSError *)error { return reportFailure ? [NSError errorWithDomain:MTLCommandBufferErrorDomain code:MTLCommandBufferErrorTimeout userInfo:nil] : real.error; }
- (void)addCompletedHandler:(MTLCommandBufferHandler)handler {
    BOOL inject=completionFailure;
    [real addCompletedHandler:^(id<MTLCommandBuffer> done) {
        if (!inject) { handler(done); return; }
        ConsumerFailureBuffer *failure=[ConsumerFailureBuffer alloc]; failure->real=done; failure->reportFailure=YES;
        handler((id<MTLCommandBuffer>)failure);
    }];
}
- (void)commit {
    if (throwCommit) @throw [NSException exceptionWithName:@"ConsumerTestCommitFailure" reason:@"injected uncertain commit" userInfo:nil];
    [real commit];
}
@end
@interface ConsumerFailureQueue : NSProxy {
@public
    id<MTLCommandQueue> real;
    BOOL throwCommit;
}
@end
@implementation ConsumerFailureQueue
- (NSMethodSignature *)methodSignatureForSelector:(SEL)s { return [(NSObject *)real methodSignatureForSelector:s]; }
- (void)forwardInvocation:(NSInvocation *)i { [i invokeWithTarget:real]; }
- (id<MTLCommandBuffer>)commandBuffer {
    ConsumerFailureBuffer *cb=[ConsumerFailureBuffer alloc]; cb->real=[real commandBuffer];
    cb->completionFailure=!throwCommit; cb->throwCommit=throwCommit;
    return (id<MTLCommandBuffer>)cb;
}
@end

static void failurePaths(id<MTLDevice> device, mcmtl_handle engine,
    const mcmtl_far_consumer_fallback *draws, uint64_t budget) {
    for (unsigned mode=0;mode<2;++mode) { @autoreleasepool {
        MCMTLDevice *fault=mcmtl_device_context_create(device,@"consumer fault injection queue"); CHECK(fault);
        ConsumerFailureQueue *q=[ConsumerFailureQueue alloc]; q->real=fault->queue; q->throwCommit=mode==1;
        fault->queue=(id<MTLCommandQueue>)q;
        fault->status->submittedValue=fault->status->completedValue=17; fault->event.signaledValue=17;
        mcmtl_handle consumer=0; char error[512]={0};
        CHECK(mcmtl_ext_far_consumer_create(h(fault),W,H,3,budget,&consumer,error,sizeof error)==MCMTL_OK); if (!consumer) continue;
        mcmtl_handle lease=acquire(engine,43,1); __weak id weakLease=obj(lease);
        MCMTLFarWarpTransform t=transform(0);
        int32_t result=encode(consumer,lease,&t,1,draws,2,1);
        CHECK(mode==0 ? result==MCMTL_OK : result==MCMTL_E_OBJC_EXCEPTION);
        mcmtl_ext_far_engine_drop(lease);
        mcmtl_far_consumer_stats s={.structSize=sizeof s};
        NSDate *until=[NSDate dateWithTimeIntervalSinceNow:10];
        do {
            mcmtl_ext_far_consumer_stats(consumer,&s);
            if ((s.flags&MCMTL_FAR_CONSUMER_POISONED) && __atomic_load_n(&fault->status->deviceLost,__ATOMIC_ACQUIRE)) break;
            [NSThread sleepForTimeInterval:0.001];
        } while (until.timeIntervalSinceNow>0);
        CHECK(s.flags==MCMTL_FAR_CONSUMER_POISONED && !s.encodedSequence);
        CHECK(fault->status->deviceLost==1 && fault->status->errorCount==1 && fault->status->lastErrorValue==18);
        CHECK(fault->status->lastErrorCode==(mode==0?MTLCommandBufferErrorTimeout:MTLCommandBufferErrorInternal));
        CHECK(fault->status->submittedValue==17 && fault->status->completedValue==17 && fault->event.signaledValue==17);
        CHECK(fault->messages.count>0);
        mcmtl_far_consumer_outputs out={.structSize=sizeof out};
        CHECK(mcmtl_ext_far_consumer_outputs(consumer,&out,NULL,0)==MCMTL_FAR_CONSUMER_DECLINED && !out.color[0] && !out.fallbackArgs);
        CHECK(weakGone(&weakLease));
        lease=acquire(engine,43,1);
        CHECK(encode(consumer,lease,&t,1,draws,2,2)==MCMTL_FAR_CONSUMER_DECLINED);
        mcmtl_ext_far_engine_drop(lease); mcmtl_ext_far_engine_drop(consumer);
    } }
}

/* Exercise exported handles as an actual fullscreen indirect render consumer:
 * RGBA8 + R32Float sampled through texture2d<float>, fragment depth -> D32.
 * All allocation/compilation here is test setup on the worker, outside encode.
 */
static void sampleOutputs(MCMTLDevice *main, mcmtl_far_consumer_outputs o, ConsumerReadback *expected) { @autoreleasepool {
    NSString *msl=@"#include <metal_stdlib>\nusing namespace metal;\n"
        "struct V { float4 p [[position]]; };\n"
        "vertex V vertexMain(uint i [[vertex_id]]) { float2 p[3]={float2(-1,-1),float2(3,-1),float2(-1,3)}; return {float4(p[i],0,1)}; }\n"
        "struct F { float4 color [[color(0)]]; float depth [[depth(any)]]; };\n"
        "fragment F fragmentMain(V in [[stage_in]], texture2d<float> color [[texture(0)]], texture2d<float> depth [[texture(1)]], sampler s [[sampler(0)]]) {\n"
        " float2 uv=in.p.xy/float2(color.get_width(),color.get_height()); return {color.sample(s,uv,level(0)),depth.sample(s,uv,level(0)).r}; }\n";
    NSError *error=nil;
    id<MTLLibrary> library=[main->device newLibraryWithSource:msl options:nil error:&error];
    CHECK(library); if (!library) { fprintf(stderr,"composite shader: %s\n",error.description.UTF8String); return; }
    MTLRenderPipelineDescriptor *p=[MTLRenderPipelineDescriptor new];
    p.vertexFunction=[library newFunctionWithName:@"vertexMain"]; p.fragmentFunction=[library newFunctionWithName:@"fragmentMain"];
    p.colorAttachments[0].pixelFormat=MTLPixelFormatRGBA8Unorm; p.depthAttachmentPixelFormat=MTLPixelFormatDepth32Float;
    id<MTLRenderPipelineState> pipeline=[main->device newRenderPipelineStateWithDescriptor:p error:&error];
    CHECK(pipeline); if (!pipeline) { fprintf(stderr,"composite pipeline: %s\n",error.description.UTF8String); return; }
    MTLDepthStencilDescriptor *dd=[MTLDepthStencilDescriptor new]; dd.depthCompareFunction=MTLCompareFunctionAlways; dd.depthWriteEnabled=YES;
    id<MTLDepthStencilState> depthState=[main->device newDepthStencilStateWithDescriptor:dd];
    MTLSamplerDescriptor *sd=[MTLSamplerDescriptor new]; sd.minFilter=sd.magFilter=MTLSamplerMinMagFilterNearest;
    sd.sAddressMode=sd.tAddressMode=MTLSamplerAddressModeClampToEdge;
    id<MTLSamplerState> sampler=[main->device newSamplerStateWithDescriptor:sd];
    id<MTLTexture> color[2], depth[2];
    for (unsigned i=0;i<2;++i) {
        MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:W height:H mipmapped:NO];
        td.storageMode=MTLStorageModePrivate; td.usage=MTLTextureUsageRenderTarget;
        color[i]=[main->device newTextureWithDescriptor:td]; td.pixelFormat=MTLPixelFormatDepth32Float;
        depth[i]=[main->device newTextureWithDescriptor:td]; CHECK(color[i] && depth[i]);
    }
    id<MTLBuffer> result=[main->device newBufferWithLength:4*256*H options:MTLResourceStorageModeShared];
    id<MTLCommandBuffer> cb=[main->queue commandBuffer];
    for (unsigned i=0;i<2;++i) {
        MTLRenderPassDescriptor *rp=[MTLRenderPassDescriptor renderPassDescriptor];
        rp.colorAttachments[0].texture=color[i]; rp.colorAttachments[0].loadAction=MTLLoadActionClear; rp.colorAttachments[0].storeAction=MTLStoreActionStore;
        rp.depthAttachment.texture=depth[i]; rp.depthAttachment.loadAction=MTLLoadActionClear; rp.depthAttachment.storeAction=MTLStoreActionStore;
        id<MTLRenderCommandEncoder> enc=[cb renderCommandEncoderWithDescriptor:rp];
        [enc setRenderPipelineState:pipeline]; [enc setDepthStencilState:depthState];
        [enc setFragmentTexture:obj(o.color[i]) atIndex:0]; [enc setFragmentTexture:obj(o.depth[i]) atIndex:1];
        [enc setFragmentSamplerState:sampler atIndex:0];
        [enc drawPrimitives:MTLPrimitiveTypeTriangle indirectBuffer:obj(o.compositeArgs) indirectBufferOffset:0];
        [enc endEncoding];
    }
    id<MTLBlitCommandEncoder> b=[cb blitCommandEncoder];
    for (unsigned plane=0;plane<4;++plane) [b copyFromTexture:plane<2?color[plane]:depth[plane-2]
        sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,0,0) sourceSize:MTLSizeMake(W,H,1)
        toBuffer:result destinationOffset:plane*256*H destinationBytesPerRow:256 destinationBytesPerImage:256*H];
    [b endEncoding]; [cb commit]; CHECK(completed(cb));
    for (unsigned plane=0;plane<4;++plane) CHECK(!memcmp((uint8_t *)result.contents+plane*256*H,
        (uint8_t *)expected.pixels.contents+plane*256*H,256*H));
} }

static void run(void) { @autoreleasepool {
    id<MTLDevice> device=MTLCreateSystemDefaultDevice(); CHECK(device); if (!device) return;
    MCMTLDevice *main=mcmtl_device_context_create(device,@"far consumer test main queue"); CHECK(main); if (!main) return;
    ProducerFixture *block=fixture(device,MCMTLFarShaderBlock), *compact=fixture(device,MCMTLFarShaderCompact);
    MCMTLFarGeometryRecipe *g=geometry(main,block,compact); CHECK(g); if (!g) return;
    char error[512]={0}; mcmtl_handle engine=0, consumer=0;
    mcmtl_far_engine_config config={sizeof config,W,H,0,41,W*H*72,1u<<20};
    CHECK(mcmtl_ext_far_engine_create(h(main),&config,&engine,error,sizeof error)==MCMTL_OK); if (!engine) return;
    const uint64_t budget=W*H*79+3*40+16+3*8+3*4; // Three alpha-rejection result words.
    CHECK(mcmtl_ext_far_consumer_create(h(main),W,H,3,budget-1,&consumer,error,sizeof error)<0 && !consumer && error[0]);
    CHECK(mcmtl_ext_far_consumer_create(h(main),W,H,3,0,&consumer,error,sizeof error)<0 && !consumer);
    CHECK(mcmtl_ext_far_consumer_create(h(main),0,H,3,budget,&consumer,error,sizeof error)<0 && !consumer);
    CHECK(mcmtl_ext_far_consumer_create(h(main),W,H,4097,budget,&consumer,error,sizeof error)<0 && !consumer);
    __block mcmtl_handle mainCreate=7;
    onMain(^{CHECK(mcmtl_ext_far_consumer_create(h(main),W,H,3,budget,&mainCreate,NULL,0)<0 && !mainCreate);});
    CHECK(mcmtl_ext_far_consumer_create(h(main),W,H,3,budget,&consumer,error,sizeof error)==MCMTL_OK && !error[0]);
    if (!consumer) { fprintf(stderr,"create: %s\n",error); onMain(^{mcmtl_ext_far_engine_stop(engine);}); mcmtl_ext_far_engine_drop(engine); return; }
    mcmtl_far_consumer_outputs o={.structSize=sizeof o};
    CHECK(mcmtl_ext_far_consumer_outputs(consumer,&o,error,sizeof error)==MCMTL_OK && !error[0]);
    CHECK(o.width==W && o.height==H && o.maxDraws==3);
    CHECK([(id<MTLBuffer>)obj(o.fallbackArgs) length]==60 && [(id<MTLBuffer>)obj(o.compositeArgs) length]==16);
    CHECK([(id<MTLTexture>)obj(o.color[0]) pixelFormat]==MTLPixelFormatRGBA8Unorm &&
        [(id<MTLTexture>)obj(o.depth[0]) pixelFormat]==MTLPixelFormatR32Float);
    mcmtl_far_consumer_outputs again={.structSize=sizeof again};
    CHECK(mcmtl_ext_far_consumer_outputs(consumer,&again,NULL,0)==MCMTL_OK && !memcmp(&again,&o,sizeof o));
    dropOutputs(again); // Each call grants an independent +1 for exactly six handles.
    again.structSize=63;
    CHECK(mcmtl_ext_far_consumer_outputs(consumer,&again,NULL,0)==MCMTL_E_ABI_MISMATCH && !again.color[0] && !again.fallbackArgs);
    mcmtl_far_consumer_stats initial={.structSize=sizeof initial};
    CHECK(mcmtl_ext_far_consumer_stats(consumer,&initial)==MCMTL_OK && !initial.flags && !initial.encodedSequence);

    NSMutableArray *shaders=[NSMutableArray array];
    MTLCompileOptions *opts=[MTLCompileOptions new]; opts.languageVersion=MTLLanguageVersion3_2; opts.mathMode=MTLMathModeFast;
    mcmtl_far_engine_submission s={.structSize=sizeof s,.inputCount=4,
        .source={41,clock_gettime_nsec_np(CLOCK_UPTIME_RAW),10,7,7},.geometryRecipe=h(g),
        .committedTicketSequence=7,.geometryRequiredValue=6,.committed=1};
    memcpy(s.camera.guardProjection,block.projection.contents,64);
    matrix_float4x4 identity=matrix_identity_float4x4; memcpy(s.camera.view,&identity,64);
    for (unsigned i=0;i<4;++i) {
        unsigned layout=i<2?MCMTLFarShaderBlock:MCMTLFarShaderCompact, layer=i&1, code=i<2?i:i+6;
        NSString *v=vertexFixture(layout,0), *f=fragmentFixture(0,layer,0);
        MCMTLPipeline *p=originalPipeline(main,v,f,@"main0",@"main0",opts,0,NO);
        MCMTLFarShader *shader=mcmtl_farshader_compile(p,mcmtl_variant_key(1,MTLPixelFormatDepth32Float),
            v,f,@"main0",@"main0",opts,layout,layer,NULL);
        CHECK(shader); if (!shader) return;
        [shaders addObject:shader]; s.shadersByLayer[code]=h(shader);
    }
    mcmtl_far_engine_uniforms uniforms[4];
    for (unsigned i=0;i<4;++i) uniforms[i]=(mcmtl_far_engine_uniforms){
        {block.projection.contents,64},{block.globals.contents,48},{block.fog.contents,48},{block.terrain.contents,80}};
    s.uniforms=uniforms;
    mcmtl_far_material_source sources[2]={
        {h(block.atlas),h(block.atlas),h(block.sampler),0,1,1,0,0,0},
        {h(block.lightmap),h(block.lightmap),h(block.sampler),0,1,1,0,0,0}};
    MCMTLFarMaterialRecipe *recipe=[[MCMTLFarMaterialRecipe alloc] initWithDevice:device sources:sources budgetBytes:1u<<20 error:NULL];
    MCMTLFarMaterialVersion *material=[[MCMTLFarMaterialVersion alloc] initWithRecipe:recipe error:NULL]; CHECK(material); if (!material) return;
    s.materialVersion=h(material);
    id<MTLCommandBuffer> upload=[main->queue commandBuffer];
    id<MTLBlitCommandEncoder> copies=[upload blitCommandEncoder];
    for (NSUInteger i=0;i<material.copyJobCount;++i) {
        mcmtl_far_material_copy_job job={0}; CHECK([material copyJobAtIndex:i into:&job]);
        [copies copyFromTexture:obj(job.source) sourceSlice:0 sourceLevel:job.level sourceOrigin:MTLOriginMake(0,0,0)
            sourceSize:MTLSizeMake(job.width,job.height,1) toTexture:obj(job.destination)
            destinationSlice:0 destinationLevel:job.level destinationOrigin:MTLOriginMake(0,0,0)];
    }
    [copies endEncoding]; [upload encodeSignalEvent:main->event value:7]; [upload commit];
    mcmtl_far_material_copy_dependency dependency={h(main->event),7,(uint32_t)material.copyJobCount,1};
    CHECK([material publishCommittedCopies:dependency error:NULL]);
    CHECK(mcmtl_ext_far_engine_submit(engine,&s,error,sizeof error)==MCMTL_OK);
    mcmtl_handle first=acquire(engine,41,1); CHECK(first); mcmtl_ext_far_engine_drop(first);
    // Same semantic material, separate immutable version and single-use ticket.
    // Ticket identity must NOT prohibit a caller-proven equivalent older image.
    MCMTLFarMaterialVersion *equivalent=[[MCMTLFarMaterialVersion alloc] initWithRecipe:recipe error:NULL];
    CHECK(equivalent && equivalent!=material);
    id<MTLCommandBuffer> secondUpload=[main->queue commandBuffer];
    id<MTLBlitCommandEncoder> secondCopies=[secondUpload blitCommandEncoder];
    for (NSUInteger i=0;i<equivalent.copyJobCount;++i) {
        mcmtl_far_material_copy_job job={0}; CHECK([equivalent copyJobAtIndex:i into:&job]);
        [secondCopies copyFromTexture:obj(job.source) sourceSlice:0 sourceLevel:job.level sourceOrigin:MTLOriginMake(0,0,0)
            sourceSize:MTLSizeMake(job.width,job.height,1) toTexture:obj(job.destination)
            destinationSlice:0 destinationLevel:job.level destinationOrigin:MTLOriginMake(0,0,0)];
    }
    [secondCopies endEncoding]; [secondUpload encodeSignalEvent:main->event value:8]; [secondUpload commit];
    dependency=(mcmtl_far_material_copy_dependency){h(main->event),8,(uint32_t)equivalent.copyJobCount,1};
    CHECK([equivalent publishCommittedCopies:dependency error:NULL]);
    s.materialVersion=h(equivalent); s.source.materialGeneration=8; s.committedTicketSequence=8;
    s.source.sourceFrame=11; s.source.sourceTimeNs=clock_gettime_nsec_np(CLOCK_UPTIME_RAW);
    CHECK(mcmtl_ext_far_engine_submit(engine,&s,error,sizeof error)==MCMTL_OK);
    mcmtl_handle lease=acquireFrame(engine,41,2,11); CHECK(lease); if (!lease) return;
    mcmtl_far_engine_image newest={.structSize=sizeof newest}, older={.structSize=sizeof older};
    CHECK(mcmtl_ext_far_engine_image(lease,0,&newest)==MCMTL_OK && mcmtl_ext_far_engine_image(lease,1,&older)==MCMTL_OK);
    CHECK(newest.materialVersion!=older.materialVersion && newest.source.materialGeneration==8 && older.source.materialGeneration==7);

    const uint32_t expected[10]={6,1,7,(uint32_t)-11,19, 12,3,13,17,23};
    uint8_t scattered[64]={0}; memcpy(scattered+4,expected,20); memcpy(scattered+32,expected+5,20);
    id<MTLBuffer> staging=buffer(device,scattered,sizeof scattered);
    id<MTLBuffer> current=[device newBufferWithLength:sizeof scattered options:MTLResourceStorageModePrivate];
    mcmtl_far_consumer_fallback d[2]={{h(current),4},{h(current),32}};
    // Current args are GPU-written on the MAIN queue. No CPU read/mapping of them.
    id<MTLCommandBuffer> cull=[main->queue commandBuffer];
    id<MTLBlitCommandEncoder> fill=[cull blitCommandEncoder];
    [fill copyFromBuffer:staging sourceOffset:0 toBuffer:current destinationOffset:0 size:sizeof scattered];
    [fill endEncoding]; [cull commit];
    MCMTLFarWarpTransform t[2]={transform(0),transform(0)};
    CHECK(mcmtl_ext_far_consumer_encode(consumer,lease,t,1,d,2,1,NULL,0)==MCMTL_E_INVALID_ARGUMENT); // Worker call.
    CHECK(encode(consumer,lease,t,1,d,2,1)==MCMTL_OK);
    CHECK(encode(consumer,lease,t,1,d,2,1)==MCMTL_E_INVALID_ARGUMENT); // Nonincreasing sequence.
    CHECK(encode(consumer,lease,t,1,d,2,2)==MCMTL_FAR_CONSUMER_DECLINED); // Exactly one lease attachment.
    mcmtl_ext_far_engine_drop(lease);
    ConsumerReadback *clean=readback(main,o); CHECK(completed(clean.command));
    const uint32_t *a=clean.args.contents;
    for (unsigned i=0;i<15;++i) CHECK(a[i]==0);
    CHECK(a[15]==3 && a[16]==1 && a[17]==0 && a[18]==0);
    mcmtl_far_consumer_stats rs=receipt(consumer,1);
    CHECK(rs.flags==MCMTL_FAR_CONSUMER_RECEIPT_VALID && !rs.holePixels && !rs.syncFallback && rs.imageCount==1 && rs.drawCount==2);
    CHECK(rs.sourceFrame[0]==11 && !rs.sourceFrame[1] && rs.sourceAgeNs[0]>0 && !rs.sourceAgeNs[1]);
    const float *z=(const float *)((const uint8_t *)clean.pixels.contents+2*256*H);
    unsigned geometryPixels=0; for (unsigned i=0;i<W*H;++i) geometryPixels+=z[i]>0;
    CHECK(geometryPixels>100);
    sampleOutputs(main,o,clean);

    // A one-pixel guard miss makes BOTH composites zero and preserves ALL five
    // argument words for BOTH draws, including signed baseVertex bit patterns.
    lease=acquire(engine,41,2); t[0]=transform(1);
    CHECK(encode(consumer,lease,t,1,d,2,2)==MCMTL_OK); mcmtl_ext_far_engine_drop(lease);
    ConsumerReadback *hole=readback(main,o); CHECK(completed(hole.command));
    a=hole.args.contents; CHECK(!memcmp(a,expected,sizeof expected));
    for (unsigned i=10;i<19;++i) CHECK(a[i]==0);
    rs=receipt(consumer,2); CHECK(rs.holePixels==H && rs.syncFallback==1);

    // Older repair restores that exact missing column, including both layers.
    lease=acquire(engine,41,2); t[1]=transform(0);
    CHECK(encode(consumer,lease,t,2,d,2,3)==MCMTL_OK); mcmtl_ext_far_engine_drop(lease);
    ConsumerReadback *repair=readback(main,o); CHECK(completed(repair.command));
    a=repair.args.contents; for (unsigned i=0;i<15;++i) CHECK(a[i]==0);
    CHECK(a[15]==3 && a[16]==1);
    rs=receipt(consumer,3); CHECK(!rs.holePixels && !rs.syncFallback && rs.imageCount==2 && rs.sourceFrame[1]==10);
    for (unsigned plane=0;plane<4;++plane) for (unsigned y=0;y<H;++y)
        CHECK(!memcmp((uint8_t *)repair.pixels.contents+plane*256*H+y*256+4*(W-1),
            (uint8_t *)clean.pixels.contents+plane*256*H+y*256+4*(W-1),4));

    // Precommit failures leave the complete earlier output intact; second-image
    // bad transform is discovered after first-image encoding, but never queued.
    lease=acquire(engine,41,2); t[1].sourceToCurrentClip[0]=NAN;
    CHECK(encode(consumer,lease,t,2,d,2,4)<0); mcmtl_ext_far_engine_drop(lease);
    ConsumerReadback *unchanged=readback(main,o); CHECK(completed(unchanged.command));
    CHECK(!memcmp(unchanged.pixels.contents,repair.pixels.contents,4*256*H));
    CHECK(!memcmp(unchanged.args.contents,repair.args.contents,76));
    rs=receipt(consumer,3); CHECK(rs.encodedSequence==3);
    lease=acquire(engine,41,2); t[0]=transform(0); t[1]=transform(0);
    t[0].motion=MCMTLFarWarpMotionTranslation;
    CHECK(encode(consumer,lease,t,1,d,2,4)==MCMTL_FAR_CONSUMER_DECLINED);
    t[0]=transform(0); t[0].translation[0]=0.001f;
    CHECK(encode(consumer,lease,t,1,d,2,4)==MCMTL_FAR_CONSUMER_DECLINED);
    t[0]=transform(0); t[0].translation[1]=INFINITY;
    CHECK(encode(consumer,lease,t,1,d,2,4)<0); mcmtl_ext_far_engine_drop(lease);
    lease=acquire(engine,41,2); t[0]=transform(0);
    mcmtl_far_consumer_fallback bad[2]={{h(current),4},{h(current),UINT64_MAX-3}};
    CHECK(encode(consumer,lease,t,1,bad,2,4)<0);
    bad[1]=(mcmtl_far_consumer_fallback){o.fallbackArgs,0}; CHECK(encode(consumer,lease,t,1,bad,2,4)<0);
    CHECK(encode(consumer,lease,t,0,d,2,4)<0);
    CHECK(encode(consumer,lease,t,1,d,4,4)<0);
    CHECK(encode(consumer,lease,t,1,NULL,0,4)==MCMTL_OK); mcmtl_ext_far_engine_drop(lease);
    ConsumerReadback *empty=readback(main,o); CHECK(completed(empty.command));
    rs=receipt(consumer,4); CHECK(!rs.drawCount && !rs.syncFallback);

    // A REAL fog change is a caller-side scene mismatch, even with identical
    // geometry/material epoch metadata. Caller checks immutable engine uniforms
    // and omits older. Native intentionally cannot infer semantic scene keys.
    float changedFog[12]; memcpy(changedFog,block.fog.contents,sizeof changedFog); changedFog[0]=0.93f;
    mcmtl_far_engine_uniforms changedUniforms[4]; memcpy(changedUniforms,uniforms,sizeof uniforms);
    for (unsigned i=0;i<4;++i) changedUniforms[i].fog=(mcmtl_far_bytes){changedFog,sizeof changedFog};
    s.uniforms=changedUniforms; s.source.sourceFrame=12; s.source.sourceTimeNs=clock_gettime_nsec_np(CLOCK_UPTIME_RAW);
    CHECK(mcmtl_ext_far_engine_submit(engine,&s,error,sizeof error)==MCMTL_OK);
    lease=acquireFrame(engine,41,2,12);
    mcmtl_far_engine_uniform_copy newestUniform={.structSize=sizeof newestUniform}, olderUniform={.structSize=sizeof olderUniform};
    CHECK(mcmtl_ext_far_engine_uniform(lease,0,0,&newestUniform)==MCMTL_OK &&
        mcmtl_ext_far_engine_uniform(lease,1,0,&olderUniform)==MCMTL_OK);
    BOOL olderSceneMatches=memcmp(newestUniform.fog,olderUniform.fog,sizeof newestUniform.fog)==0;
    CHECK(!olderSceneMatches); t[0]=transform(1); t[1]=transform(0);
    CHECK(encode(consumer,lease,t,olderSceneMatches?2:1,d,2,5)==MCMTL_OK); mcmtl_ext_far_engine_drop(lease);
    ConsumerReadback *sceneMismatch=readback(main,o); CHECK(completed(sceneMismatch.command));
    CHECK(!memcmp(sceneMismatch.args.contents,expected,sizeof expected));
    rs=receipt(consumer,5); CHECK(rs.imageCount==1 && rs.holePixels==H && rs.syncFallback);

    // Queue a gate ONLY in the test. Three successful calls must return with GPU
    // work still pending; the fourth declines instead of waiting/allocating.
    id<MTLSharedEvent> gate=[device newSharedEvent];
    id<MTLCommandBuffer> blocker=[main->queue commandBuffer];
    [blocker encodeWaitForEvent:gate value:1]; [blocker commit];
    __weak id weakLeases[3];
    ConsumerReadback *ordered[3];
    for (unsigned i=0;i<3;++i) { @autoreleasepool {
        lease=acquire(engine,41,2); weakLeases[i]=obj(lease);
        t[0]=transform(i==1?1:0);
        CHECK(encode(consumer,lease,t,1,d,2,10+i)==MCMTL_OK);
        mcmtl_ext_far_engine_drop(lease); lease=0;
        CHECK(weakLeases[i]!=nil);
        ordered[i]=readback(main,o); // Emulates each main frame consuming outputs before next sideCB.
    } }
    lease=acquire(engine,41,2);
    CHECK(encode(consumer,lease,t,1,d,2,13)==MCMTL_FAR_CONSUMER_DECLINED); mcmtl_ext_far_engine_drop(lease);
    CHECK(gate.signaledValue==0 && blocker.status<MTLCommandBufferStatusCompleted);
    // Stop/invalidate can't release a pinned image read already admitted on main.
    onMain(^{CHECK(mcmtl_ext_far_engine_invalidate(engine,42)==MCMTL_OK);});
    for (unsigned i=0;i<3;++i) CHECK(weakLeases[i]!=nil);
    gate.signaledValue=1;
    for (unsigned i=0;i<3;++i) {
        CHECK(completed(ordered[i].command)); a=ordered[i].args.contents;
        if (i==1) CHECK(!memcmp(a,expected,sizeof expected) && a[15]==0);
        else CHECK(a[0]==0 && a[5]==0 && a[15]==3);
        CHECK(weakGone(&weakLeases[i]));
    }
    rs=receipt(consumer,12); CHECK(!rs.holePixels && !rs.syncFallback && rs.encodedSequence==12);
    clean.command=nil; hole.command=nil; repair.command=nil; unchanged.command=nil; empty.command=nil; sceneMismatch.command=nil;
    for (unsigned i=0;i<3;++i) ordered[i].command=nil;

    // An acquired lease is rejected after epoch invalidation and may never
    // expose old targets. Create one new epoch image for final ownership tests.
    s.source.epoch=42; s.source.sourceFrame=12; s.source.sourceTimeNs=clock_gettime_nsec_np(CLOCK_UPTIME_RAW);
    CHECK(mcmtl_ext_far_engine_submit(engine,&s,error,sizeof error)==MCMTL_OK);
    lease=acquire(engine,42,1);
    onMain(^{CHECK(mcmtl_ext_far_engine_invalidate(engine,43)==MCMTL_OK);});
    t[0]=transform(0); CHECK(encode(consumer,lease,t,1,d,2,13)==MCMTL_FAR_CONSUMER_DECLINED);
    mcmtl_ext_far_engine_drop(lease);
    s.source.epoch=43; s.source.sourceFrame=13;
    CHECK(mcmtl_ext_far_engine_submit(engine,&s,error,sizeof error)==MCMTL_OK);
    lease=acquire(engine,43,1); mcmtl_ext_far_engine_drop(lease);
    failurePaths(device,engine,d,budget);
    lease=acquire(engine,43,1);
    id<MTLSharedEvent> finalGate=[device newSharedEvent];
    id<MTLCommandBuffer> finalBlock=[main->queue commandBuffer];
    [finalBlock encodeWaitForEvent:finalGate value:1]; [finalBlock commit];
    __weak id weakConsumer=obj(consumer), weakLease=obj(lease), weakColor=obj(o.color[0]);
    CHECK(encode(consumer,lease,t,1,d,2,13)==MCMTL_OK);
    mcmtl_ext_far_engine_drop(lease); lease=0;
    dropOutputs(o); mcmtl_ext_far_engine_drop(consumer); consumer=0;
    CHECK(weakConsumer && weakLease && weakColor); // Only completion holds consumer and lease now.
    onMain(^{CHECK(mcmtl_ext_far_engine_stop(engine)==MCMTL_OK);}); mcmtl_ext_far_engine_drop(engine);
    finalGate.signaledValue=1;
    CHECK(weakGone(&weakConsumer)); CHECK(weakGone(&weakLease));
    CHECK(weakGone(&weakColor)); // Exactly one reference released per exported handle/call.
} }

int main(void) { @autoreleasepool {
    __block _Atomic(bool) done=false;
    dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED,0),^{ @autoreleasepool { run(); atomic_store(&done,true); } });
    NSDate *deadline=[NSDate dateWithTimeIntervalSinceNow:120];
    while (!atomic_load(&done) && deadline.timeIntervalSinceNow>0)
        [[NSRunLoop currentRunLoop] runUntilDate:[NSDate dateWithTimeIntervalSinceNow:0.01]];
    if (!atomic_load(&done)) { fprintf(stderr,"farimage_consumer_test: worker timeout\n"); return 1; }
    printf("farimage_consumer_test: %u checks, %u failures\n",checks,failures); return failures?1:0;
} }
