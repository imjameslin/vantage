/* Prompt 5 engine integration. Parent registers/runs under its GPU lock.
 * Syntax validation alone does not run any GPU work. Small scene/pipeline setup
 * is copied from farimage_producer_test, with the same canonical shader fixture;
 * no inclusion of another test translation unit or mock publication shortcut.
 */
// Standalone command from checkout root (run only under parent's GPU lock):
// xcrun clang -arch arm64 -mmacosx-version-min=26.0 -O1 -g -fobjc-arc
//   -fobjc-arc-exceptions -fvisibility=hidden -Wall -Wextra -Wno-unused-parameter
//   -Werror=unguarded-availability-new -Werror=return-type
//   -I native/include -I native/src -framework Foundation -framework Metal
//   -framework QuartzCore -framework MetalFX native/tests/farimage_engine_test.m
//   native/src/*.m -o /tmp/farimage_engine_test
// (Join the clang lines above.) Then: MTL_DEBUG_LAYER=1 /tmp/farimage_engine_test
#import "mcmetal_farengine.h"
#import "mcmetal_farmaterial.h"
#include <dispatch/dispatch.h>
#include <math.h>
#include <simd/simd.h>
#include <stdatomic.h>
#include <stdio.h>
#include <string.h>
#include <time.h>

/* Java FFM layout contract. Changing any assertion requires coordinating the
 * parent mirror; no implicit/native-only padding may enter these records. */
#define SIZE(type, size) _Static_assert(sizeof(type)==size, #type " size")
#define OFFSET(type, member, offset) _Static_assert(offsetof(type,member)==offset, #type "." #member)
SIZE(mcmtl_far_engine_config,40);
SIZE(mcmtl_far_engine_source,40);
SIZE(mcmtl_far_engine_camera,176);
SIZE(mcmtl_far_engine_uniforms,64);
SIZE(mcmtl_far_engine_submission,432);
OFFSET(mcmtl_far_engine_submission,source,8);
OFFSET(mcmtl_far_engine_submission,camera,48);
OFFSET(mcmtl_far_engine_submission,geometryRecipe,224);
OFFSET(mcmtl_far_engine_submission,materialVersion,232);
OFFSET(mcmtl_far_engine_submission,shadersByLayer,240);
OFFSET(mcmtl_far_engine_submission,uniforms,320);
OFFSET(mcmtl_far_engine_submission,committedTicketSequence,328);
OFFSET(mcmtl_far_engine_submission,geometryRequiredValue,336);
OFFSET(mcmtl_far_engine_submission,committed,344);
OFFSET(mcmtl_far_engine_submission,clearColor,352);
OFFSET(mcmtl_far_engine_submission,clearDepth,416);
SIZE(mcmtl_far_engine_image,328);
OFFSET(mcmtl_far_engine_image,source,16);
OFFSET(mcmtl_far_engine_image,camera,56);
OFFSET(mcmtl_far_engine_image,sequence,232);
OFFSET(mcmtl_far_engine_image,committedTicketSequence,240);
OFFSET(mcmtl_far_engine_image,geometryRequiredValue,248);
OFFSET(mcmtl_far_engine_image,color,256);
OFFSET(mcmtl_far_engine_image,depth,272);
OFFSET(mcmtl_far_engine_image,visibilityID,288);
OFFSET(mcmtl_far_engine_image,geometryRecipe,304);
OFFSET(mcmtl_far_engine_image,materialVersion,312);
OFFSET(mcmtl_far_engine_image,producerPacket,320);
SIZE(mcmtl_far_engine_uniform_copy,248);
SIZE(mcmtl_far_engine_timing,80);
OFFSET(mcmtl_far_engine_timing,structSize,0);
OFFSET(mcmtl_far_engine_timing,flags,4);
OFFSET(mcmtl_far_engine_timing,sourceTimeNs,8);
OFFSET(mcmtl_far_engine_timing,reserveTimeNs,16);
OFFSET(mcmtl_far_engine_timing,encodeStartNs,24);
OFFSET(mcmtl_far_engine_timing,commitStartNs,32);
OFFSET(mcmtl_far_engine_timing,commitEndNs,40);
OFFSET(mcmtl_far_engine_timing,gpuStartNs,48);
OFFSET(mcmtl_far_engine_timing,gpuEndNs,56);
OFFSET(mcmtl_far_engine_timing,completionNs,64);
OFFSET(mcmtl_far_engine_timing,sequence,72);
#undef SIZE
#undef OFFSET

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
static void miss(mcmtl_handle engine, uint64_t epoch) {
    mcmtl_handle lease=9; uint32_t count=9;
    CHECK(mcmtl_ext_far_engine_acquire(engine,epoch,&lease,&count)==MCMTL_FAR_ENGINE_DECLINED && !lease && !count);
}
static void onMain(dispatch_block_t block) { dispatch_sync(dispatch_get_main_queue(),block); }

static mcmtl_far_engine_timing checkTiming(mcmtl_handle lease, uint32_t index) {
    mcmtl_far_engine_image image={.structSize=sizeof image};
    mcmtl_far_engine_timing timing={.structSize=sizeof timing};
    CHECK(mcmtl_ext_far_engine_image(lease,index,&image)==MCMTL_OK);
    CHECK(mcmtl_ext_far_engine_timing(lease,index,&timing)==MCMTL_OK);
    CHECK(timing.structSize==80 && timing.flags==3);
    CHECK(timing.sourceTimeNs==image.source.sourceTimeNs && timing.sequence==image.sequence);
    CHECK(timing.reserveTimeNs>0 && timing.reserveTimeNs<=timing.encodeStartNs &&
          timing.encodeStartNs<=timing.commitStartNs && timing.commitStartNs<=timing.commitEndNs);
    // Completion may race commit return. Only the actual causal bounds apply.
    CHECK(timing.completionNs>=timing.commitStartNs && timing.completionNs<=clock_gettime_nsec_np(CLOCK_UPTIME_RAW));
    CHECK(timing.gpuStartNs>0 && timing.gpuEndNs>timing.gpuStartNs);
    mcmtl_far_engine_timing again={.structSize=sizeof again};
    CHECK(mcmtl_ext_far_engine_timing(lease,index,&again)==MCMTL_OK && !memcmp(&again,&timing,sizeof timing));
    return timing;
}

static void badTiming(mcmtl_handle lease, uint32_t index, uint32_t size, int32_t result) {
    mcmtl_far_engine_timing timing, zero={0};
    memset(&timing,0xa5,sizeof timing); timing.structSize=size;
    CHECK(mcmtl_ext_far_engine_timing(lease,index,&timing)==result);
    CHECK(!memcmp(&timing,&zero,sizeof timing));
}

/* The FFM result must name exactly the owner's six targets. The direct native
 * consumer attaches BEFORE reads, then drops +1 immediately: only actual main
 * GPU completion may release the resulting protection. Readback is test only. */
static void inspectAndConsume(mcmtl_handle lease, MCMTLDevice *main, mcmtl_far_engine_submission expected, BOOL visibleGeometry) {
    (void)checkTiming(lease,0);
    mcmtl_far_engine_image image={.structSize=sizeof image};
    CHECK(mcmtl_ext_far_engine_image(lease,0,&image)==MCMTL_OK);
    CHECK(image.width==W && image.height==H && image.inputCount==4 && image.sequence);
    CHECK(!memcmp(&image.source,&expected.source,sizeof image.source));
    CHECK(!memcmp(&image.camera,&expected.camera,sizeof image.camera));
    CHECK(image.committedTicketSequence==expected.committedTicketSequence && image.geometryRequiredValue==expected.geometryRequiredValue);
    CHECK(image.geometryRecipe==expected.geometryRecipe && image.materialVersion==expected.materialVersion && image.producerPacket);
    MCMTLFarImageView view=[(MCMTLFarImageLease *)obj(lease) imageAtIndex:0];
    mcmtl_handle targets[6]={image.color[0],image.depth[0],image.visibilityID[0],image.color[1],image.depth[1],image.visibilityID[1]};
    for (unsigned l=0; l<2; ++l) {
        CHECK(image.color[l]==h(view.color[l]) && image.depth[l]==h(view.depth[l]) && image.visibilityID[l]==h(view.visibilityID[l]));
        CHECK(view.color[l].pixelFormat==MTLPixelFormatRGBA8Unorm && view.depth[l].pixelFormat==MTLPixelFormatDepth32Float &&
              view.visibilityID[l].pixelFormat==MTLPixelFormatR32Uint);
    }
    for (unsigned i=0; i<6; ++i) for (unsigned j=0; j<i; ++j) CHECK(targets[i]!=targets[j]);
    for (unsigned i=0; i<4; ++i) {
        mcmtl_far_engine_uniform_copy copy={.structSize=sizeof copy};
        CHECK(mcmtl_ext_far_engine_uniform(lease,0,i,&copy)==MCMTL_OK);
        CHECK(copy.presentMask==39 && !memcmp(copy.projection,expected.camera.guardProjection,64));
        CHECK(((int *)copy.globals)[11]==1 && ((int *)copy.terrain)[16]==32);
    }
    id<MTLCommandBuffer> consumer=[main->queue commandBuffer];
    CHECK(mcmtl_ext_far_engine_attach(lease,h(consumer))==MCMTL_OK);
    CHECK(mcmtl_ext_far_engine_attach(lease,h(consumer))==MCMTL_FAR_ENGINE_DECLINED);
    id<MTLBuffer> readback=[main->device newBufferWithLength:6*256*H options:MTLResourceStorageModeShared];
    id<MTLBlitCommandEncoder> blit=[consumer blitCommandEncoder];
    for (unsigned i=0; i<6; ++i) [blit copyFromTexture:obj(targets[i]) sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,0,0)
        sourceSize:MTLSizeMake(W,H,1) toBuffer:readback destinationOffset:i*256*H destinationBytesPerRow:256 destinationBytesPerImage:256*H];
    [blit endEncoding];
    mcmtl_ext_far_engine_drop(lease);
    [consumer commit];
    if (!completed(consumer)) return;
    for (unsigned l=0; l<2; ++l) {
        const uint32_t *ids=(const uint32_t *)((const uint8_t *)readback.contents+(3*l+2)*256*H);
        unsigned visible=0, invalid=0;
        for (unsigned i=0; i<W*H; ++i) { visible+=ids[i]!=0; invalid+=ids[i]!=0 && ids[i]!=1026; }
        CHECK(visibleGeometry ? visible>100 && !invalid : visible==0);
        if (!visibleGeometry) {
            const uint32_t *color=(const uint32_t *)((const uint8_t *)readback.contents+3*l*256*H);
            const float *depth=(const float *)((const uint8_t *)readback.contents+(3*l+1)*256*H);
            unsigned changed=0;
            for (unsigned i=0; i<W*H; ++i) changed+=color[i]!=0 || depth[i]!=0;
            CHECK(changed==0);
        }
    }
}

static void run(void) { @autoreleasepool {
    id<MTLDevice> device=MTLCreateSystemDefaultDevice(); CHECK(device!=nil); if (!device) return;
    MCMTLDevice *main=[MCMTLDevice new]; main->device=device; main->queue=[device newCommandQueue]; main->event=[device newSharedEvent];
    ProducerFixture *block=fixture(device,MCMTLFarShaderBlock), *compact=fixture(device,MCMTLFarShaderCompact);
    MCMTLFarGeometryRecipe *g=geometry(main,block,compact); CHECK(g!=nil); if (!g) return;
    mcmtl_far_engine_config config={sizeof config,W,H,0,41,W*H*72,1u<<20};
    mcmtl_handle engine=0; char error[512]={0};
    mcmtl_far_engine_config tooSmall=config; --tooSmall.targetBudgetBytes;
    CHECK(mcmtl_ext_far_engine_create(h(main),&tooSmall,&engine,error,sizeof error)<0 && !engine && error[0]);
    CHECK(mcmtl_ext_far_engine_create(h(main),&config,&engine,error,sizeof error)==MCMTL_OK && engine && !error[0]);
    if (!engine) return;
    __block mcmtl_handle mainCreate=9;
    onMain(^{ CHECK(mcmtl_ext_far_engine_create(h(main),&config,&mainCreate,NULL,0)<0 && !mainCreate); });
    CHECK(mcmtl_ext_far_engine_stop(engine)==MCMTL_E_INVALID_ARGUMENT); // Worker cannot stop active engine.

    NSMutableArray *shaders=[NSMutableArray array];
    MTLCompileOptions *opts=[MTLCompileOptions new]; opts.languageVersion=MTLLanguageVersion3_2; opts.mathMode=MTLMathModeFast;
    mcmtl_far_engine_submission s={.structSize=sizeof s,.inputCount=4,.source={41,1000000,10,7,9},
        .geometryRecipe=h(g),.committedTicketSequence=7,.geometryRequiredValue=6,.committed=1};
    memcpy(s.camera.guardProjection,block.projection.contents,64);
    matrix_float4x4 identity=matrix_identity_float4x4;
    memcpy(s.camera.view,&identity,64); s.camera.position[0]=123.5; s.camera.renderOrigin[2]=32;
    for (unsigned i=0; i<4; ++i) {
        unsigned layout=i<2?MCMTLFarShaderBlock:MCMTLFarShaderCompact, layer=i&1, code=i<2?i:i+6;
        NSString *v=vertexFixture(layout,0), *f=fragmentFixture(0,layer,0);
        MCMTLPipeline *p=originalPipeline(main,v,f,@"main0",@"main0",opts,0,NO);
        MCMTLFarShader *shader=mcmtl_farshader_compile(p,mcmtl_variant_key(1,MTLPixelFormatDepth32Float),
            v,f,@"main0",@"main0",opts,layout,layer,NULL);
        CHECK(shader!=nil); if (!shader) { onMain(^{mcmtl_ext_far_engine_stop(engine);}); mcmtl_ext_far_engine_drop(engine); return; }
        [shaders addObject:shader]; s.shadersByLayer[code]=h(shader);
    }
    mcmtl_far_engine_uniforms uniforms[4];
    for (unsigned i=0; i<4; ++i) uniforms[i]=(mcmtl_far_engine_uniforms){
        {block.projection.contents,64},{block.globals.contents,48},{block.fog.contents,48},{block.terrain.contents,80}};
    s.uniforms=uniforms;
    mcmtl_far_material_source sources[2]={
        {h(block.atlas),h(block.atlas),h(block.sampler),0,1,1,0,0,0},
        {h(block.lightmap),h(block.lightmap),h(block.sampler),0,1,1,0,0,0}};
    MCMTLFarMaterialRecipe *recipe=[[MCMTLFarMaterialRecipe alloc] initWithDevice:device sources:sources budgetBytes:1u<<20 error:NULL];
    MCMTLFarMaterialVersion *material=[[MCMTLFarMaterialVersion alloc] initWithRecipe:recipe error:NULL];
    CHECK(material!=nil); if (!material) { onMain(^{mcmtl_ext_far_engine_stop(engine);}); mcmtl_ext_far_engine_drop(engine); return; }
    s.materialVersion=h(material);
    CHECK(mcmtl_ext_far_engine_submit(engine,&s,error,sizeof error)<0 && error[0]); // Unpublished copy rejected.
    miss(engine,41);

    /* Actual main copy stream is committed but held on a test-controlled gate.
     * Engine must never poll/wait for upload completion on CPU or add a main
     * dependency on its own future GPU work. No texture copies inside engine. */
    id<MTLSharedEvent> gate=[device newSharedEvent];
    id<MTLCommandBuffer> upload=[main->queue commandBuffer];
    [upload encodeWaitForEvent:gate value:1];
    id<MTLBlitCommandEncoder> copies=[upload blitCommandEncoder];
    for (NSUInteger i=0; i<material.copyJobCount; ++i) {
        mcmtl_far_material_copy_job job={0}; CHECK([material copyJobAtIndex:i into:&job]);
        [copies copyFromTexture:obj(job.source) sourceSlice:0 sourceLevel:job.level sourceOrigin:MTLOriginMake(0,0,0)
            sourceSize:MTLSizeMake(job.width,job.height,1) toTexture:obj(job.destination)
            destinationSlice:0 destinationLevel:job.level destinationOrigin:MTLOriginMake(0,0,0)];
    }
    [copies endEncoding]; [upload encodeSignalEvent:main->event value:7]; [upload commit];
    mcmtl_far_material_copy_dependency dependency={h(main->event),7,(uint32_t)material.copyJobCount,1};
    CHECK([material publishCommittedCopies:dependency error:NULL]);
    CHECK(main->event.signaledValue<7);
    __block mcmtl_far_engine_submission onMainSource=s;
    onMain(^{CHECK(mcmtl_ext_far_engine_submit(engine,&onMainSource,NULL,0)<0);});
    CHECK(mcmtl_ext_far_engine_submit(engine,&s,error,sizeof error)==MCMTL_OK && !error[0]);
    CHECK(main->event.signaledValue<7 && !main->currentCommandBuffer && !main->decoderState);
    CHECK(mcmtl_ext_far_engine_submit(engine,&s,error,sizeof error)==MCMTL_FAR_ENGINE_DECLINED && !error[0]);
    miss(engine,41);
    mcmtl_far_engine_submission expected=s;
    uint8_t savedGlobals[48]; memcpy(savedGlobals,block.globals.contents,48);
    memset(block.globals.contents,0,48); // Native source metadata and GPU uniforms must be independent.
    s.camera.position[0]=-999;
    gate.signaledValue=1;
    CHECK(completed(upload));
    mcmtl_handle first=acquire(engine,41,1); if (!first) { onMain(^{mcmtl_ext_far_engine_stop(engine);}); mcmtl_ext_far_engine_drop(engine); return; }
    badTiming(first,1,80,MCMTL_E_INVALID_ARGUMENT); // Missing second image.
    badTiming(first,UINT32_MAX,80,MCMTL_E_INVALID_ARGUMENT);
    badTiming(first,0,79,MCMTL_E_ABI_MISMATCH);
    badTiming(0,0,80,MCMTL_E_INVALID_ARGUMENT);
    badTiming(engine,0,80,MCMTL_E_INVALID_ARGUMENT);
    CHECK(mcmtl_ext_far_engine_timing(first,0,NULL)==MCMTL_E_INVALID_ARGUMENT);
    inspectAndConsume(first,main,expected,YES); // Consumes and drops +1.
    memcpy(block.globals.contents,savedGlobals,48); s.camera=expected.camera;

    // Failures may not replace the completed image or poison the next offer.
    mcmtl_far_engine_submission bad=s;
    bad.committed=0; CHECK(mcmtl_ext_far_engine_submit(engine,&bad,error,sizeof error)<0 && error[0]);
    bad=s; bad.committedTicketSequence=6; CHECK(mcmtl_ext_far_engine_submit(engine,&bad,error,sizeof error)<0);
    bad=s; bad.geometryRequiredValue=8; CHECK(mcmtl_ext_far_engine_submit(engine,&bad,error,sizeof error)<0);
    bad=s; bad.source.epoch=40; CHECK(mcmtl_ext_far_engine_submit(engine,&bad,error,sizeof error)==MCMTL_FAR_ENGINE_DECLINED);
    uint64_t savedLength=uniforms[3].terrain.length; uniforms[3].terrain.length=64;
    CHECK(mcmtl_ext_far_engine_submit(engine,&s,error,sizeof error)<0); uniforms[3].terrain.length=savedLength;
    bad=s; bad.shadersByLayer[2]=s.shadersByLayer[0]; CHECK(mcmtl_ext_far_engine_submit(engine,&bad,error,sizeof error)<0);
    id<MTLSharedEvent> actual=main->event; main->event=[device newSharedEvent];
    @autoreleasepool {
        MCMTLFarGeometryRecipe *wrong=geometry(main,block,compact); bad=s; bad.geometryRecipe=h(wrong);
        CHECK(mcmtl_ext_far_engine_submit(engine,&bad,error,sizeof error)<0);
    }
    main->event=actual;
    uint32_t freshView[32]; memcpy(freshView,g.view.bytes,sizeof freshView);
    mcmtl_far_bytes view={freshView,sizeof freshView};
    CHECK(mcmtl_ext_far_engine_submit_view(engine,&s,NULL,error,sizeof error)==MCMTL_E_INVALID_ARGUMENT);
    view.length=127;
    CHECK(mcmtl_ext_far_engine_submit_view(engine,&s,&view,error,sizeof error)==MCMTL_E_INVALID_ARGUMENT);
    view.length=sizeof freshView;
    ((float *)freshView)[3]=NAN;
    CHECK(mcmtl_ext_far_engine_submit_view(engine,&s,&view,error,sizeof error)<0);
    memcpy(freshView,g.view.bytes,sizeof freshView);
    freshView[21]|=16; // Near-depth history is not a complete far-image capture.
    CHECK(mcmtl_ext_far_engine_submit_view(engine,&s,&view,error,sizeof error)<0);
    memcpy(freshView,g.view.bytes,sizeof freshView);
    mcmtl_handle unchanged=acquire(engine,41,1);
    mcmtl_far_engine_image image={.structSize=sizeof image};
    CHECK(mcmtl_ext_far_engine_image(unchanged,0,&image)==MCMTL_OK && image.source.sourceFrame==10);
    @autoreleasepool {
        MCMTLFarProducerPacket *packet=obj(image.producerPacket);
        MCMTLFarCullGeometry *cached=packet.snapshot.geometry;
        // Deterministically model delayed destruction outside the owner's lock:
        // image slots are available, but all three snapshot admissions are held.
        MCMTLFarCullSnapshot *pinA=[[MCMTLFarCullSnapshot alloc] initWithGeometry:cached view:g.view budgetBytes:1u<<20 error:NULL];
        MCMTLFarCullSnapshot *pinB=[[MCMTLFarCullSnapshot alloc] initWithGeometry:cached view:g.view budgetBytes:1u<<20 error:NULL];
        CHECK(pinA && pinB);
        CHECK(mcmtl_ext_far_engine_submit(engine,&s,error,sizeof error)==MCMTL_FAR_ENGINE_DECLINED && !error[0]);
        CHECK(pinA.geometry==cached && pinB.geometry==cached); // Both holds span submission.
    }
    mcmtl_ext_far_engine_drop(unchanged);

    s.source.sourceFrame=20; s.source.sourceTimeNs=2000000;
    // Same recipe/leases, but this camera excludes every face. Old submit used
    // the original view; ignoring this new argument would draw >100 pixels.
    ((float *)freshView)[3]=-1000;
    CHECK(mcmtl_ext_far_engine_submit_view(engine,&s,&view,error,sizeof error)==MCMTL_OK);
    memset(freshView,0,sizeof freshView); // No caller-owned view bytes may escape.
    CHECK(((const float *)g.view.bytes)[3]==16); // Original recipe stays immutable.
    mcmtl_handle pair=acquire(engine,41,2);
    inspectAndConsume(pair,main,s,NO);
    pair=acquire(engine,41,2);
    CHECK(mcmtl_ext_far_engine_image(pair,0,&image)==MCMTL_OK && image.source.sourceFrame==20);
    uint64_t newest=image.sequence; mcmtl_handle newestColor=image.color[0];
    CHECK(mcmtl_ext_far_engine_image(pair,1,&image)==MCMTL_OK && image.source.sourceFrame==10 && image.sequence!=newest && image.color[0]!=newestColor);
    CHECK(mcmtl_ext_far_engine_image(pair,2,&image)==MCMTL_E_INVALID_ARGUMENT);
    badTiming(pair,2,80,MCMTL_E_INVALID_ARGUMENT);
    mcmtl_far_engine_timing previousTiming=checkTiming(pair,0);
    (void)checkTiming(pair,1);
    mcmtl_ext_far_engine_drop(pair);
    // Recycle more than three slots. Reusing immutable buffers must not pin a
    // spent private-output admission forever or overwrite an older image.
    for (uint64_t frame=21; frame<=24; ++frame) {
        s.source.sourceFrame=frame; s.source.sourceTimeNs=frame*100000;
        BOOL visible=frame%2!=0;
        memcpy(freshView,g.view.bytes,sizeof freshView);
        ((float *)freshView)[3]=-1000;
        int32_t result=visible ? mcmtl_ext_far_engine_submit(engine,&s,error,sizeof error)
            : mcmtl_ext_far_engine_submit_view(engine,&s,&view,error,sizeof error);
        CHECK(result==MCMTL_OK);
        mcmtl_handle cycle=acquireFrame(engine,41,2,frame);
        if (cycle) {
            mcmtl_far_engine_timing older=checkTiming(cycle,1);
            CHECK(!memcmp(&older,&previousTiming,sizeof older)); // Reuse cannot overwrite an older receipt.
            previousTiming=checkTiming(cycle,0);
            CHECK(previousTiming.sequence!=older.sequence && previousTiming.sourceTimeNs==s.source.sourceTimeNs);
            inspectAndConsume(cycle,main,s,visible);
        }
    }
    pair=acquireFrame(engine,41,2,24);
    CHECK(mcmtl_ext_far_engine_image(pair,0,&image)==MCMTL_OK && image.source.sourceFrame==24);
    newestColor=image.color[0];

    id<MTLSharedEvent> stopGate=[device newSharedEvent];
    id<MTLCommandBuffer> nextUpload=[main->queue commandBuffer];
    [nextUpload encodeWaitForEvent:stopGate value:1]; [nextUpload encodeSignalEvent:main->event value:8]; [nextUpload commit];
    s.committedTicketSequence=8; s.source.sourceFrame=30;
    CHECK(mcmtl_ext_far_engine_submit(engine,&s,error,sizeof error)==MCMTL_OK);
    onMain(^{
        CHECK(mcmtl_ext_far_engine_invalidate(engine,42)==MCMTL_OK);
        CHECK(mcmtl_ext_far_engine_invalidate(engine,42)==MCMTL_FAR_ENGINE_DECLINED);
    });
    miss(engine,41); miss(engine,42);
    CHECK(mcmtl_ext_far_engine_submit(engine,&s,error,sizeof error)==MCMTL_FAR_ENGINE_DECLINED);
    s.source.epoch=42;
    CHECK(mcmtl_ext_far_engine_submit(engine,&s,error,sizeof error)==MCMTL_FAR_ENGINE_DECLINED); // Previous epoch still in flight.
    onMain(^{CHECK(mcmtl_ext_far_engine_stop(engine)==MCMTL_OK); CHECK(mcmtl_ext_far_engine_stop(engine)==MCMTL_OK);});
    CHECK(mcmtl_ext_far_engine_submit(engine,&s,error,sizeof error)==MCMTL_FAR_ENGINE_DECLINED);
    stopGate.signaledValue=1; CHECK(completed(nextUpload));
    miss(engine,41); miss(engine,42);
    CHECK(mcmtl_ext_far_engine_image(pair,0,&image)==MCMTL_OK && image.source.sourceFrame==24 && image.color[0]==newestColor);
    mcmtl_far_engine_timing retiredTiming=checkTiming(pair,0);
    CHECK(!memcmp(&retiredTiming,&previousTiming,sizeof retiredTiming)); // Held lease survives stop/invalidation.
    CHECK(mcmtl_ext_far_engine_attach(pair,h([main->queue commandBuffer]))==MCMTL_FAR_ENGINE_DECLINED);
    mcmtl_ext_far_engine_drop(pair);
    __weak id pendingEngine=(__bridge id)(void *)(uintptr_t)engine;
    mcmtl_ext_far_engine_drop(engine);
    // Stop/drop are nonwaiting. The producer callback keeps the engine alive
    // until GPU completion; drain that lifetime before releasing the GPU lock.
    NSDate *drain=[NSDate dateWithTimeIntervalSinceNow:10];
    while (drain.timeIntervalSinceNow>0) {
        @autoreleasepool {
            if (!pendingEngine) break;
            [NSThread sleepForTimeInterval:0.001];
        }
    }
    CHECK(!pendingEngine);
} }

int main(void) { @autoreleasepool {
    __block _Atomic(bool) done=false;
    dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED,0),^{ @autoreleasepool { run(); atomic_store(&done,true); } });
    // Service explicit main-thread invalidation/stop while the worker runs.
    NSDate *deadline=[NSDate dateWithTimeIntervalSinceNow:120];
    while (!atomic_load(&done) && deadline.timeIntervalSinceNow>0)
        [[NSRunLoop currentRunLoop] runUntilDate:[NSDate dateWithTimeIntervalSinceNow:0.01]];
    if (!atomic_load(&done)) { fprintf(stderr,"farimage_engine_test: worker timeout\n"); return 1; }
    printf("farimage_engine_test: %u checks, %u failures\n",checks,failures); return failures?1:0;
} }
