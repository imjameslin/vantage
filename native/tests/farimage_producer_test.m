/* Prompt 5 producer integration tests. Parent registers/runs under the GPU lock.
 * Canonical shader sources come from the parent-owned shared fixture header.
 * The small scene builder below is local (based on farimage_shader_test.m),
 * with no runtime fixture/file dependency or inclusion of another test unit.
 * Tests compile only in setup, then compare real cull + producer MRT against
 * the ORIGINAL source PSOs, using captured binding values (including RGSS,
 * nontrivial fog/lightmap, viewport/scissor and color write masks).
 */
#import "mcmetal_farproducer.h"
#include <dispatch/dispatch.h>
#include <simd/simd.h>
#include <stdio.h>
#include <string.h>
static unsigned checks, failures;
#define CHECK(x) do { ++checks; if (!(x)) { ++failures; fprintf(stderr,"FAIL %d: %s\n",__LINE__,#x); } } while (0)

#import "farimage_shader_fixture.h"

static const NSUInteger W = 64, H = 64, ROW = 256;
static id<MTLBuffer> buffer(id<MTLDevice> d, const void *data, NSUInteger length) {
    id<MTLBuffer> b = [d newBufferWithBytes:data length:length options:MTLResourceStorageModeShared];
    CHECK(b != nil); return b;
}
static id<MTLTexture> target(id<MTLDevice> d, MTLPixelFormat format) {
    MTLTextureDescriptor *desc = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:format width:W height:H mipmapped:NO];
    desc.storageMode = MTLStorageModePrivate;
    desc.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
    id<MTLTexture> t = [d newTextureWithDescriptor:desc]; CHECK(t != nil); return t;
}
static BOOL finish(id<MTLCommandBuffer> cb) {
    dispatch_semaphore_t done = dispatch_semaphore_create(0);
    [cb addCompletedHandler:^(id<MTLCommandBuffer> b) { (void)b; dispatch_semaphore_signal(done); }];
    [cb commit];
    BOOL complete = dispatch_semaphore_wait(done, dispatch_time(DISPATCH_TIME_NOW, 10 * NSEC_PER_SEC)) == 0;
    CHECK(complete);
    if (complete && cb.status != MTLCommandBufferStatusCompleted) fprintf(stderr, "%s\n", cb.error.description.UTF8String);
    CHECK(complete && cb.status == MTLCommandBufferStatusCompleted);
    return complete && cb.status == MTLCommandBufferStatusCompleted;
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

/* These are separate private pixel versions, filled by the committed upload CB.
 * No writer touches them after that copy. Holding live atlas textures alone would
 * not implement this protocol; later live mutations are explicitly tested. */
@interface ProducerMaterials : NSObject <MCMTLFarMaterialLease>
@property(nonatomic, readonly) NSArray<id<MTLTexture>> *versions;
- (instancetype)initWithVersions:(NSArray<id<MTLTexture>> *)versions;
@end
@implementation ProducerMaterials
- (instancetype)initWithVersions:(NSArray<id<MTLTexture>> *)versions {
    if ((self = [super init])) _versions = [versions copy]; return self;
}
- (BOOL)ownsImmutableFarTexture:(id<MTLTexture>)texture { return [_versions containsObject:texture]; }
@end

static MCMTLFarProducerClears clears(void) {
    MCMTLFarProducerClears c = {0};
    for (unsigned l=0;l<2;++l) c.color[l]=MTLClearColorMake(0.125,0.25,0.375,0);
    return c;
}
static id<MTLTexture> materialVersion(id<MTLTexture> live) {
    MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:live.pixelFormat
        width:live.width height:live.height mipmapped:NO];
    td.usage=MTLTextureUsageShaderRead; td.storageMode=MTLStorageModePrivate;
    id<MTLTexture> t=[live.device newTextureWithDescriptor:td]; CHECK(t!=nil); return t;
}
static MCMTLFarImageView imageView(id<MTLDevice> device, NSMutableArray *lifetime) {
    MCMTLFarImageView v={.width=W,.height=H};
    for (unsigned l=0;l<2;++l) {
        id<MTLTexture> c=target(device,MTLPixelFormatRGBA8Unorm), d=target(device,MTLPixelFormatDepth32Float),
            i=target(device,MTLPixelFormatR32Uint);
        [lifetime addObjectsFromArray:@[c,d,i]]; v.color[l]=c; v.depth[l]=d; v.visibilityID[l]=i;
    }
    return v;
}
static id<MTLBuffer> readback(id<MTLCommandBuffer> cb, MCMTLFarImageView v) {
    id<MTLBuffer> b=[cb.device newBufferWithLength:6*ROW*H options:MTLResourceStorageModeShared]; CHECK(b!=nil);
    id<MTLBlitCommandEncoder> blit=[cb blitCommandEncoder]; CHECK(blit!=nil);
    for (unsigned l=0;l<2;++l) {
        NSArray *targets=@[v.color[l],v.depth[l],v.visibilityID[l]];
        for (unsigned k=0;k<3;++k) [blit copyFromTexture:targets[k] sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,0,0)
            sourceSize:MTLSizeMake(W,H,1) toBuffer:b destinationOffset:(3*l+k)*ROW*H
            destinationBytesPerRow:ROW destinationBytesPerImage:ROW*H];
    }
    [blit endEncoding]; return b;
}
static uint32_t boundsWord(unsigned x,unsigned y,unsigned z) { return x|(y<<10)|(z<<20); }
static MCMTLFarCullSnapshot *makeSnapshot(MCMTLDevice *owner, ProducerFixture *f, unsigned layout, unsigned count, BOOL fade) {
    /* Add an actual off-frustum third cluster; it must not appear in GPU output.
     * First two contain degenerate holes and the faces at low6=5 and low6=63. */
    uint32_t a[]={80,(5u<<20)|(1u<<29),0,0, 160,1u|(63u<<20)|(1u<<29),0,0,
        0,7u<<26,boundsWord(896,512,512),boundsWord(912,528,528)};
    uint32_t b[]={16,(1u<<20)|(1u<<29),0,0};
    NSMutableData *itemsA=[NSMutableData dataWithBytes:a length:sizeof a], *itemsB=[NSMutableData dataWithBytes:b length:sizeof b];
    /* Nonzero original binding offsets are intentional. */
    NSMutableData *paddedA=[NSMutableData dataWithLength:f.arenaA.length+32], *paddedB=[NSMutableData dataWithLength:f.arenaB.length+32];
    memcpy((uint8_t *)paddedA.mutableBytes+32,f.arenaA.contents,f.arenaA.length);
    memcpy((uint8_t *)paddedB.mutableBytes+32,f.arenaB.contents,f.arenaB.length);
    id<MTLBuffer> va=buffer(owner->device,paddedA.bytes,paddedA.length), vb=buffer(owner->device,paddedB.bytes,paddedB.length);
    uint32_t ix[4+71*6]={0};
    for (unsigned q=0;q<71;++q) { const unsigned pattern[6]={0,1,2,2,3,0}; for (unsigned k=0;k<6;++k) ix[4+6*q+k]=4*q+pattern[k]; }
    id<MTLBuffer> index=buffer(owner->device,ix,sizeof ix);
    NSMutableArray *inputs=[NSMutableArray array];
    for (unsigned i=0;i<count;++i) {
        MCMTLFarClusterInput *in=[[MCMTLFarClusterInput alloc] initWithItems:i<2?itemsA:itemsB
            vertices:i<2?va:vb vertexOffset:32 indices:index indexOffset:16
            layerCode:(i&1)|(layout==MCMTLFarShaderCompact?8:0)|(fade?16:0) faceBase:i<2?137:900
            lifetime:f]; // Fixture owns nonrecycled geometry; no application allocator exists in this test.
        CHECK(in!=nil); [inputs addObject:in];
    }
    float table[16]={0}; table[3]=table[11]=1.0f/16.0f;
    uint32_t view[32]={0}; ((float *)view)[0]=-1; ((float *)view)[3]=16; view[21]=5;
    MCMTLFarCullSnapshot *snapshot=mcmtl_farproducer_snapshot(owner,inputs,
        [NSData dataWithBytes:table length:sizeof table],
        [NSData dataWithBytes:f.sections.contents length:f.sections.length],
        [NSData dataWithBytes:view length:sizeof view],1u<<20,NULL);
    CHECK(snapshot!=nil);
    memset(itemsA.mutableBytes,0xff,itemsA.length); memset(itemsB.mutableBytes,0xff,itemsB.length);
    return snapshot;
}
static void capture(ProducerFixture *f, NSArray<MCMTLFarShader *> *shaders, ProducerMaterials *materials,
                    MCMTLFarProducerDraw draws[4], NSMutableArray *byteLifetimes) {
    NSArray *buffers=@[f.projection,f.globals,f.fog,f.terrain]; unsigned slots[]={0,1,2,5};
    for (unsigned i=0;i<4;++i) {
        draws[i]=(MCMTLFarProducerDraw){.shader=shaders[i&1], .viewport={4,2,56,60,0.125,0.875},
            .scissor={6,4,48,54}, .depthClipMode=MTLDepthClipModeClamp};
        for (unsigned j=0;j<4;++j) {
            id<MTLBuffer> b=buffers[j];
            NSData *bytes=[NSData dataWithBytesNoCopy:b.contents length:b.length freeWhenDone:NO];
            [byteLifetimes addObject:bytes];
            draws[i].stages[0].buffers[slots[j]]=bytes; draws[i].stages[1].buffers[slots[j]]=bytes;
        }
        draws[i].stages[0].textures[2]=materials.versions[1]; draws[i].stages[1].textures[0]=materials.versions[0];
        draws[i].stages[0].samplers[2]=f.sampler; draws[i].stages[1].samplers[0]=f.sampler;
        draws[i].stages[1].samplerLODClamp[0]=YES; draws[i].stages[1].samplerLODMin[0]=0;
        draws[i].stages[1].samplerLODMax[0]=0;
    }
}
/* Independent ORIGINAL source path. It uses the hand-authored two visible quad
 * references from the existing shader test, with the same original material and
 * uniform values. It never reads the producer's cull outputs or copied uniforms. */
static NSData *sourceImage(ProducerFixture *f, MCMTLFarCullSnapshot *snapshot, NSArray *original,
                          NSArray<MCMTLFarShader *> *shaders, const MCMTLFarProducerDraw draws[4]) {
    NSMutableArray *storage=[NSMutableArray array]; MCMTLFarImageView v=imageView(f.device,storage);
    id<MTLCommandBuffer> cb=[f.queue commandBuffer];
    for (unsigned layer=0;layer<2;++layer) {
        MTLRenderPassDescriptor *pass=[MTLRenderPassDescriptor renderPassDescriptor];
        pass.colorAttachments[0].texture=v.color[layer]; pass.colorAttachments[0].loadAction=MTLLoadActionClear;
        pass.colorAttachments[0].storeAction=MTLStoreActionStore; pass.colorAttachments[0].clearColor=clears().color[layer];
        pass.depthAttachment.texture=v.depth[layer]; pass.depthAttachment.loadAction=MTLLoadActionClear;
        pass.depthAttachment.storeAction=MTLStoreActionStore; pass.depthAttachment.clearDepth=0;
        id<MTLRenderCommandEncoder> re=[cb renderCommandEncoderWithDescriptor:pass]; CHECK(re!=nil);
        MCMTLFarShaderRaster r=shaders[layer].raster;
        [re setRenderPipelineState:original[layer]]; [re setDepthStencilState:shaders[layer].depthStencilState];
        [re setCullMode:r.cullMode]; [re setTriangleFillMode:r.fillMode]; [re setFrontFacingWinding:r.frontFacing];
        [re setDepthBias:r.depthBiasConstant slopeScale:r.depthBiasSlopeScale clamp:r.depthBiasClamp];
        for (unsigned i=layer;i<4;i+=2) {
            [re setViewport:draws[i].viewport]; [re setScissorRect:draws[i].scissor]; [re setDepthClipMode:draws[i].depthClipMode];
            NSArray *ubos=@[f.projection,f.globals,f.fog,f.terrain]; unsigned slots[]={0,1,2,5};
            for (unsigned j=0;j<4;++j) {
                [re setVertexBuffer:ubos[j] offset:0 atIndex:slots[j]]; [re setFragmentBuffer:ubos[j] offset:0 atIndex:slots[j]];
            }
            [re setVertexTexture:f.lightmap atIndex:2]; [re setVertexSamplerState:f.sampler atIndex:2];
            [re setFragmentTexture:f.atlas atIndex:0]; [re setFragmentSamplerState:f.sampler lodMinClamp:0 lodMaxClamp:0 atIndex:0];
            MCMTLFarDraw g=[snapshot drawAtIndex:i];
            [re setVertexBuffer:g.input.vertices offset:g.input.vertexOffset atIndex:30];
            [re setVertexBuffer:f.sections offset:0 atIndex:29];
            [re setVertexBuffer:i<2?f.itemsA:f.itemsB offset:0 atIndex:27];
            [re setVertexBuffer:f.quads offset:i<2?0:16 atIndex:28];
            [re drawIndexedPrimitives:r.primitiveType indexCount:i<2?12:6 indexType:MTLIndexTypeUInt32
                indexBuffer:g.input.indices indexBufferOffset:g.input.indexOffset];
        }
        [re endEncoding];
    }
    /* Read only the original path's color and depth; original has no ID output. */
    id<MTLBuffer> out=[f.device newBufferWithLength:6*ROW*H options:MTLResourceStorageModeShared];
    id<MTLBlitCommandEncoder> blit=[cb blitCommandEncoder];
    for (unsigned l=0;l<2;++l) for (unsigned k=0;k<2;++k)
        [blit copyFromTexture:k?v.depth[l]:v.color[l] sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,0,0)
            sourceSize:MTLSizeMake(W,H,1) toBuffer:out destinationOffset:(3*l+k)*ROW*H destinationBytesPerRow:ROW destinationBytesPerImage:ROW*H];
    [blit endEncoding];
    return finish(cb)?[NSData dataWithBytes:out.contents length:out.length]:nil;
}
static MCMTLFarProducerPacket *packet(MCMTLDevice *owner, MCMTLFarCullSnapshot *s, const MCMTLFarProducerDraw *draws,
                                    ProducerMaterials *m, NSError **error) {
    return [[MCMTLFarProducerPacket alloc] initWithCullDevice:owner snapshot:s draws:draws count:s.count
        width:W height:H clears:clears() materialLease:m error:error];
}
static void compare(NSData *reference, id<MTLBuffer> output) {
    const uint8_t *actual=output.contents, *plain=reference.bytes;
    for (unsigned l=0;l<2;++l) {
        CHECK(memcmp(actual+3*l*ROW*H,plain+3*l*ROW*H,2*ROW*H)==0);
        const float *z=(const float *)(plain+(3*l+1)*ROW*H);
        const uint32_t *ids=(const uint32_t *)(actual+(3*l+2)*ROW*H);
        unsigned far=0,near=0,equal=0,holes=0,bad=0;
        for (unsigned y=0;y<H;++y) for (unsigned x=0;x<W;++x) {
            unsigned at=y*W+x; uint32_t expected=0;
            BOOL eq=x>=18&&x<32&&y>=17&&y<32;
            if (z[at]>0 && z[at]<0.5f) { expected=163; ++far; }
            else if (z[at]>=0.5f) { expected=eq?906:241; if(eq) ++equal; else ++near; }
            if (ids[at]!=expected) ++bad;
            if (x>=32&&x<52&&y>=10&&y<50&&z[at]==0) ++holes;
        }
        CHECK(bad==0 && far>30 && near>30 && equal==210);
        CHECK(l?holes>20:holes==0);
    }
}
static void gpuTests(void) {
    id<MTLDevice> metal=MTLCreateSystemDefaultDevice(); CHECK(metal!=nil); if (!metal) return;
    MCMTLDevice *owner=[MCMTLDevice new]; owner->device=metal; owner->queue=[metal newCommandQueue]; owner->event=[metal newSharedEvent];
    MCMTLDevice *cull=mcmtl_farproducer_cull_device(metal,NULL); CHECK(cull && cull!=owner); if (!cull) return;
    CHECK(cull->queue==nil && cull->event==nil && cull->currentCommandBuffer==nil && cull->decoderState==nil);
    for (unsigned layout=1;layout<=2;++layout) for (unsigned vary=0;vary<3;++vary) @autoreleasepool {
        MTLCompileOptions *opts=[MTLCompileOptions new]; opts.languageVersion=MTLLanguageVersion3_2; opts.mathMode=MTLMathModeFast;
        NSMutableArray<MCMTLFarShader *> *shaders=[NSMutableArray array]; NSMutableArray *original=[NSMutableArray array];
        for (unsigned l=0;l<2;++l) {
            NSString *v=vertexFixture(layout,vary), *f=fragmentFixture(vary,l,0);
            MCMTLPipeline *p=originalPipeline(owner,v,f,@"main0",@"main0",opts,vary,l==1); CHECK(p!=nil); if (!p) return;
            NSError *error=nil;
            MCMTLFarShader *s=mcmtl_farshader_compile(p,mcmtl_variant_key(1,MTLPixelFormatDepth32Float),v,f,@"main0",@"main0",opts,layout,l,&error);
            CHECK(s!=nil && !error); if (!s) { fprintf(stderr,"%s\n",error.description.UTF8String); return; }
            [shaders addObject:s];
            MTLRenderPipelineDescriptor *d=[p->descriptorTemplate copy]; d.depthAttachmentPixelFormat=MTLPixelFormatDepth32Float;
            id<MTLRenderPipelineState> source=[metal newRenderPipelineStateWithDescriptor:d error:&error]; CHECK(source!=nil); if (!source) return;
            [original addObject:source];
        }
        ProducerFixture *f=fixture(metal,layout); MCMTLFarCullSnapshot *s=makeSnapshot(cull,f,layout,4,NO);
        ProducerMaterials *materials=[[ProducerMaterials alloc] initWithVersions:@[materialVersion(f.atlas),materialVersion(f.lightmap)]];
        MCMTLFarProducerDraw draws[4]; NSMutableArray *bytes=[NSMutableArray array]; capture(f,shaders,materials,draws,bytes);
        NSError *error=nil;
        CHECK(packet(owner,s,draws,materials,&error)==nil && error); // Same Metal device, wrong native owner.
        CHECK(packet(cull,s,draws,nil,&error)==nil && error); // A retain of mutable material is not a lease.
        NSData *saved=draws[3].stages[1].buffers[5]; draws[3].stages[1].buffers[5]=nil;
        CHECK(packet(cull,s,draws,materials,&error)==nil && error); draws[3].stages[1].buffers[5]=saved;
        NSData *shortProjection=[NSData dataWithBytes:"abcd" length:4];
        NSData *projection=draws[3].stages[0].buffers[0]; draws[3].stages[0].buffers[0]=shortProjection;
        CHECK(packet(cull,s,draws,materials,&error)==nil && error); draws[3].stages[0].buffers[0]=projection;
        id<MTLTexture> atlas=draws[3].stages[1].textures[0]; draws[3].stages[1].textures[0]=f.atlas;
        CHECK(packet(cull,s,draws,materials,&error)==nil && error); draws[3].stages[1].textures[0]=atlas;
        draws[3].stages[0].buffers[6]=saved;
        CHECK(packet(cull,s,draws,materials,&error)==nil && error); draws[3].stages[0].buffers[6]=nil;
        draws[3].scissor.width=W;
        CHECK(packet(cull,s,draws,materials,&error)==nil && error); draws[3].scissor.width=48;
        id<MTLSamplerState> sampler=draws[3].stages[1].samplers[0]; draws[3].stages[1].samplers[0]=nil;
        CHECK(packet(cull,s,draws,materials,&error)==nil && error); draws[3].stages[1].samplers[0]=sampler;
        CHECK(packet(cull,makeSnapshot(cull,f,layout,4,YES),draws,materials,&error)==nil && error);
        MCMTLFarProducerPacket *p=packet(cull,s,draws,materials,&error); CHECK(p && !error); if (!p) return;
        NSData *reference=sourceImage(f,s,original,shaders,draws); CHECK(reference!=nil); if (!reference) return;
        NSMutableArray *storage=[NSMutableArray array]; MCMTLFarImageView image=imageView(metal,storage);
        image.inputs=p;
        id<MTLSharedEvent> gate=[metal newSharedEvent], ready=[metal newSharedEvent];
        id<MTLCommandBuffer> upload=[owner->queue commandBuffer]; [upload encodeWaitForEvent:gate value:1];
        id<MTLBlitCommandEncoder> blit=[upload blitCommandEncoder];
        NSArray *live=@[f.atlas,f.lightmap];
        for (unsigned i=0;i<2;++i) {
            id<MTLTexture> t=live[i];
            [blit copyFromTexture:t sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,0,0)
                sourceSize:MTLSizeMake(t.width,t.height,1) toTexture:materials.versions[i] destinationSlice:0 destinationLevel:0 destinationOrigin:MTLOriginMake(0,0,0)];
        }
        [blit endEncoding]; [upload encodeSignalEvent:ready value:1]; [upload commit]; // Committed, deliberately incomplete.
        CHECK(ready.signaledValue==0);
        id<MTLCommandBuffer> cb=[f.queue commandBuffer];
        memset([s drawAtIndex:0].arguments.contents,0xa5,32);
        CHECK(!mcmtl_farproducer_encode(p,s,cb,image,ready,0,&error) && error);
        MCMTLFarImageView bad=image; bad.color[1]=bad.color[0];
        CHECK(!mcmtl_farproducer_encode(p,s,cb,bad,ready,1,&error) && error);
        bad=image; bad.width=W-1;
        CHECK(!mcmtl_farproducer_encode(p,s,cb,bad,ready,1,&error) && error);
        bad=image; bad.visibilityID[1]=image.color[1];
        CHECK(!mcmtl_farproducer_encode(p,s,cb,bad,ready,1,&error) && error);
        CHECK(((uint8_t *)[s drawAtIndex:0].arguments.contents)[0]==0xa5);
        /* Packet owns bytes, even though every captured NSData aliases live UBO storage. */
        for (id<MTLBuffer> b in @[f.projection,f.globals,f.fog,f.terrain,f.sections]) memset(b.contents,0xff,b.length);
        memset(draws,0,sizeof draws); [bytes removeAllObjects];
        CHECK(mcmtl_farproducer_encode(p,s,cb,image,ready,1,&error) && !error);
        CHECK(cb.status==MTLCommandBufferStatusNotEnqueued && owner->currentCommandBuffer==nil && owner->event.signaledValue==0);
        CHECK(!mcmtl_farproducer_encode(p,s,cb,image,ready,1,&error) && error);
        id<MTLBuffer> out=readback(cb,image);
        dispatch_semaphore_t done=dispatch_semaphore_create(0);
        [cb addCompletedHandler:^(id<MTLCommandBuffer> unused){ dispatch_semaphore_signal(done); }]; [cb commit];
        CHECK(dispatch_semaphore_wait(done,dispatch_time(DISPATCH_TIME_NOW,10*NSEC_PER_MSEC))!=0);
        CHECK(((uint8_t *)[s drawAtIndex:0].arguments.contents)[0]==0xa5);
        /* Later source writes are ordered AFTER the snapshot copy on the source
         * queue, and may race the producer safely because pixels are separate. */
        id<MTLBuffer> zeros=[metal newBufferWithLength:256*16 options:MTLResourceStorageModeShared]; memset(zeros.contents,0,zeros.length);
        id<MTLCommandBuffer> mutate=[owner->queue commandBuffer]; blit=[mutate blitCommandEncoder];
        for (id<MTLTexture> t in live) [blit copyFromBuffer:zeros sourceOffset:0 sourceBytesPerRow:256 sourceBytesPerImage:256*t.height
            sourceSize:MTLSizeMake(t.width,t.height,1) toTexture:t destinationSlice:0 destinationLevel:0 destinationOrigin:MTLOriginMake(0,0,0)];
        [blit endEncoding];
        gate.signaledValue=1;
        CHECK(finish(mutate));
        CHECK(dispatch_semaphore_wait(done,dispatch_time(DISPATCH_TIME_NOW,10*NSEC_PER_SEC))==0);
        CHECK(cb.status==MTLCommandBufferStatusCompleted);
        if (cb.status!=MTLCommandBufferStatusCompleted) { fprintf(stderr,"%s\n",cb.error.description.UTF8String); return; }
        compare(reference,out);
        for (unsigned i=0;i<4;++i) {
            MCMTLFarDraw g=[s drawAtIndex:i]; const uint32_t *args=g.arguments.contents;
            CHECK(args[0]==(i<2?420:12) && args[1]==1 && args[2]==0 && args[3]==0 && args[4]==0);
            const uint32_t *q=g.quads.contents; CHECK(q[0]==0 && q[i<2?69:1]==(i<2?127:1));
        }
        /* Reuse dirty outputs with zero draws: BOTH opaque layers must clear all
         * attachments, with ID background zero, including the empty CUTOUT layer. */
        uint32_t emptyView[32]={0}; emptyView[21]=4; float table[8]={0}; table[3]=1.0f/16;
        uint32_t sections[4]={0,0,0,0x3f800000};
        NSData *ct=[NSData dataWithBytes:table length:sizeof table], *st=[NSData dataWithBytes:sections length:sizeof sections],
            *cv=[NSData dataWithBytes:emptyView length:sizeof emptyView];
        // Actual constructor owner differs despite identical underlying Metal resources.
        MCMTLFarCullSnapshot *raw=[[MCMTLFarCullSnapshot alloc] initWithDevice:owner inputs:@[]
            cullSections:ct shaderSections:st view:cv budgetBytes:1024 error:&error];
        CHECK(raw && raw.cullDevice==owner && packet(cull,raw,NULL,nil,&error)==nil && error);
        CHECK(mcmtl_farproducer_snapshot(owner,@[],ct,st,cv,1024,&error)==nil && error);
        MCMTLFarCullSnapshot *empty=mcmtl_farproducer_snapshot(cull,@[],ct,st,cv,1024,&error);
        MCMTLFarProducerPacket *emptyPacket=packet(cull,empty,NULL,nil,&error); CHECK(emptyPacket && !error);
        cb=[f.queue commandBuffer]; CHECK(mcmtl_farproducer_encode(emptyPacket,empty,cb,image,ready,1,&error));
        out=readback(cb,image); if (finish(cb)) {
            const uint8_t *data=out.contents;
            for (unsigned l=0;l<2;++l) {
                BOOL clear=YES;
                for (unsigned i=0;i<W*H;++i) {
                    const uint8_t *color=data+3*l*ROW*H+4*i;
                    if (color[0]!=32 || color[1]!=64 || color[2]!=96 || color[3]!=0 ||
                        ((const float *)(data+(3*l+1)*ROW*H))[i]!=0 || ((const uint32_t *)(data+(3*l+2)*ROW*H))[i]!=0) clear=NO;
                }
                CHECK(clear);
            }
        }
        CHECK(owner->currentCommandBuffer==nil && owner->event.signaledValue==0);
    }
}
int main(void) { @autoreleasepool {
    dispatch_semaphore_t done=dispatch_semaphore_create(0);
    dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED,0), ^{ @autoreleasepool { gpuTests(); dispatch_semaphore_signal(done); } });
    dispatch_semaphore_wait(done,DISPATCH_TIME_FOREVER);
    printf("farimage_producer_test: %u checks, %u failures\n",checks,failures);
    return failures?1:0;
} }
