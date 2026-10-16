/* Prompt 5 sidecar tests. Parent owns build registration and the GPU lock.
 * Link mcmetal_farshader.m and the native library (MCMTLDevice/MCMTLPipeline).
 * --sources-only exercises rejection without creating any Metal object.
 * Default: actual offscreen byte-for-byte RGBA/depth parity and R32Uint IDs.
 * Embedded fixtures are the full current non-faded built-in LOD hand-pull and
 * translated fragment sources (TerrainVertexMsl / TerrainShaderVariants), not
 * toy shader substitutes. No capture directory or Java/client build is needed.
 * Runtime options: MSL 3.2, fast math, as in the retained replay. This is a
 * regression test, not a Prompt5 performance/motion/guard-band acceptance gate.
 */
#import "mcmetal_farshader.h"
#include <dispatch/dispatch.h>
#include <simd/simd.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static unsigned checks, failures;
#define CHECK(x) do { ++checks; if (!(x)) { ++failures; fprintf(stderr, "FAIL %d: %s\n", __LINE__, #x); } } while (0)

#import "farimage_shader_fixture.h"

static MCMTLFarShaderSource *rewrite(NSString *v, NSString *f, MCMTLFarShaderLayout layout, MCMTLFarShaderLayer layer) {
    NSError *error = nil;
    MCMTLFarShaderSource *s = mcmtl_farshader_rewrite(v, f, @"main0", @"main0", layout, layer, &error);
    CHECK((s != nil) == (error == nil));
    return s;
}
static void sourceTests(void) {
    for (unsigned layout = 1; layout <= 2; ++layout) for (unsigned vary = 0; vary < 3; ++vary)
    for (unsigned layer = 0; layer < 2; ++layer) for (unsigned sampling = 0; sampling < 3; ++sampling) {
        NSString *v = vertexFixture(layout, vary), *f = fragmentFixture(vary, layer, sampling);
        MCMTLFarShaderSource *s = rewrite(v, f, layout, layer);
        CHECK(s != nil && s.layout == layout && s.layer == layer);
        if (!s) continue;
        /* Removing ONLY the additions recovers both sources byte for byte. */
        NSString *plainV = replace(s.vertex, @", constant uint& mcmtl_far_arena [[buffer(6)]]", @"");
        plainV = replace(plainV, @"\n    uint mcmtl_far_id [[user(locn6), flat]];", @"");
        plainV = replace(plainV, @"\n    out.mcmtl_far_id = 1u + mcmtl_far_arena + (mcmtl_item.x >> 2u) + (mcmtl_e & 63u);", @"");
        NSString *plainF = replace(s.fragment, @"\n    uint mcmtl_far_id [[user(locn6), flat]];", @"");
        plainF = replace(plainF, @"\n    uint mcmtl_far_id [[color(1)]];", @"");
        plainF = replace(plainF, @"    out.mcmtl_far_id = in.mcmtl_far_id;\n", @"");
        CHECK([v isEqualToString:plainV] && [f isEqualToString:plainF]);
        if (layer == 1) CHECK([s.fragment rangeOfString:@"discard_fragment();" options:NSBackwardsSearch].location <
                              [s.fragment rangeOfString:@"out.mcmtl_far_id ="].location);
    }
    NSString *v = blockSource(), *f = fragmentSource();
    NSArray<NSString *> *badV = @[
        replace(v, @"mcmtl_e >> 6u", @"mcmtl_e >> 5u"),
        replace(v, @"mcmtl_item.x + 4u", @"0u + 4u"),
        replace(v, @"7u * v", @"8u * v"), /* Same anchors, different BLOCK stride. */
        replace(v, @"[[buffer(0)]]", @"[[ buffer ( 6 ) ]]"),
        replace(v, @"locn5", @"locn6"),
        replace(v, @"    return out;", @"    if (mcmtl_vid == 0u) return out;\n    return out;"),
        replace(v, @"    uint2 mcmtl_item = mcmtl_items[mcmtl_e >> 6u];", @"    uint2 mcmtl_item = mcmtl_items[mcmtl_e >> 6u];\n    uint2 mcmtl_item = mcmtl_items[mcmtl_e >> 6u];"),
        replace(v, @"return length(pos);", @"return 0.0;"), /* Altered built-in helper. */
        [v stringByAppendingString:v], [@"#define mcmtl_fetch custom_fetch\n" stringByAppendingString:v],
        replace(v, @"float3 pos =", @"float3 mcmtl_far_id; float3 pos =")
    ];
    for (NSString *bad in badV) CHECK(rewrite(bad, f, MCMTLFarShaderBlock, MCMTLFarShaderCutout) == nil);
    NSArray<NSString *> *badF = @[
        replace(f, @"locn5", @"locn6"),
        replace(f, @"[[color(0)]]", @"[[color(1)]]"),
        replace(f, @"    return out;", @"    return out;\n    return out;"),
        replace(f, @"color.w < 0.5", @"color.w < 0.1"),
        replace(f, @"    return out;", @"    float lodB = 0.0;\n    return out;"),
        replace(f, @"    return out;", @"    float4 gl_FragCoord;\n    return out;"),
        [f stringByAppendingString:f], [@"#define discard_fragment()\n" stringByAppendingString:f]
    ];
    for (NSString *bad in badF) CHECK(rewrite(v, bad, MCMTLFarShaderBlock, MCMTLFarShaderCutout) == nil);
    NSString *fade = replace(f, @"sampler Sampler0Smplr [[sampler(0)]])",
        @"sampler Sampler0Smplr [[sampler(0)]], float4 gl_FragCoord [[position]])");
    fade = replace(fade, @"    if (color.w < 0.5)",
        @"    float lodT = in.chunkVisibility >= 2.0 ? in.chunkVisibility - 2.0 : in.chunkVisibility;\n"
         "    float lodB = fract(52.98291778564453125 * fract(dot(floor(gl_FragCoord.xy), float2(0.067110560834407806396484375, 0.005837149918079376220703125))));\n"
         "    if ((in.chunkVisibility >= 2.0) == (lodB < lodT)) discard_fragment();\n"
         "    if (color.w < 0.5)");
    NSError *fadeError = nil;
    CHECK(mcmtl_farshader_rewrite(v, fade, @"main0", @"main0", MCMTLFarShaderBlock, MCMTLFarShaderCutout, &fadeError) == nil);
    CHECK([fadeError.localizedDescription containsString:@"guard origin"]);
    CHECK(rewrite(v, f, MCMTLFarShaderCompact, MCMTLFarShaderCutout) == nil);
    CHECK(rewrite(v, f, MCMTLFarShaderBlock, MCMTLFarShaderSolid) == nil);
    CHECK(rewrite(v, f, MCMTLFarShaderBlock, (MCMTLFarShaderLayer)2) == nil);
    CHECK(rewrite(v, f, (MCMTLFarShaderLayout)3, MCMTLFarShaderCutout) == nil);
    CHECK(rewrite(vertexFixture(MCMTLFarShaderCompact, 2), f, MCMTLFarShaderCompact, MCMTLFarShaderCutout) == nil);
    CHECK(rewrite(@"", f, MCMTLFarShaderBlock, MCMTLFarShaderCutout) == nil);
    CHECK(rewrite([v stringByPaddingToLength:131073 withString:@" " startingAtIndex:0], f, MCMTLFarShaderBlock, MCMTLFarShaderCutout) == nil);
    NSString *renamed = replace(v, @"main0(", @"farVertex(");
    renamed = replace(renamed, @"_102", @"_7102");
    CHECK(mcmtl_farshader_rewrite(renamed, replace(f, @"main0(", @"farFragment("), @"farVertex", @"farFragment",
        MCMTLFarShaderBlock, MCMTLFarShaderCutout, NULL) != nil);
    CHECK(mcmtl_farshader_rewrite(v, f, @"missing", @"main0", MCMTLFarShaderBlock, MCMTLFarShaderCutout, NULL) == nil);
    CHECK(mcmtl_farshader_rewrite(v, f, @"main0.*", @"main0", MCMTLFarShaderBlock, MCMTLFarShaderCutout, NULL) == nil);
    MCMTLFarShaderArena a;
    CHECK(mcmtl_farshader_arena(137, 128, &a, NULL));
    CHECK(mcmtl_farshader_item(a, 40 * 4, 64, NULL));
    CHECK(!mcmtl_farshader_item(a, 161, 1, NULL));
    CHECK(!mcmtl_farshader_item(a, 128 * 4, 1, NULL));
    CHECK(!mcmtl_farshader_item(a, 100 * 4, 64, NULL));
    CHECK(!mcmtl_farshader_item(a, 0, 65, NULL));
    CHECK(!mcmtl_farshader_item(a, 0, 0, NULL));
    CHECK(mcmtl_farshader_arena(UINT32_MAX - 1ull, 1, &a, NULL));
    CHECK(a.faceBase + 1u == UINT32_MAX && mcmtl_farshader_item(a, 0, 1, NULL));
    CHECK(mcmtl_farshader_arena(0, 1ull << 30, &a, NULL));
    CHECK(mcmtl_farshader_item(a, UINT32_MAX - 3, 1, NULL));
    CHECK(!mcmtl_farshader_item(a, UINT32_MAX - 3, 2, NULL));
    CHECK(!mcmtl_farshader_arena(0, (1ull << 30) + 1, &a, NULL));
    CHECK(!mcmtl_farshader_arena(UINT32_MAX, 1, &a, NULL));
    CHECK(!mcmtl_farshader_arena(UINT64_MAX, UINT64_MAX, &a, NULL));
    CHECK(!mcmtl_farshader_arena(0, 0, &a, NULL));
    CHECK(!mcmtl_farshader_arena(0, 1, NULL, NULL));
}

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

@interface Fixture : NSObject
@property id<MTLDevice> device;
@property id<MTLCommandQueue> queue;
@property id<MTLBuffer> projection, globals, fog, terrain, sections, arenaA, arenaB, itemsA, itemsB, quads, indices;
@property id<MTLTexture> atlas, lightmap;
@property id<MTLSamplerState> sampler;
@end
@implementation Fixture
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
static Fixture *fixture(id<MTLDevice> d, MCMTLFarShaderLayout layout) {
    Fixture *f = [Fixture new]; f.device = d; f.queue = [d newCommandQueue]; CHECK(f.queue != nil);
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

static NSData *render(Fixture *f, id<MTLRenderPipelineState> state, id<MTLDepthStencilState> depthState,
    MCMTLFarShaderRaster raster, MTLPixelFormat format, BOOL ids, BOOL reverse, uint32_t secondBase) {
    id<MTLTexture> color = target(f.device, format), depth = target(f.device, MTLPixelFormatDepth32Float);
    id<MTLTexture> identity = ids ? target(f.device, MTLPixelFormatR32Uint) : nil;
    id<MTLBuffer> readback = [f.device newBufferWithLength:ROW * H * 3 options:MTLResourceStorageModeShared];
    CHECK(readback != nil); if (!color || !depth || !readback || (ids && !identity)) return nil;
    MTLRenderPassDescriptor *pass = [MTLRenderPassDescriptor renderPassDescriptor];
    pass.colorAttachments[0].texture = color; pass.colorAttachments[0].loadAction = MTLLoadActionClear;
    pass.colorAttachments[0].storeAction = MTLStoreActionStore;
    pass.colorAttachments[0].clearColor = MTLClearColorMake(0.125,0.25,0.375,0);
    pass.depthAttachment.texture = depth; pass.depthAttachment.loadAction = MTLLoadActionClear;
    pass.depthAttachment.storeAction = MTLStoreActionStore; pass.depthAttachment.clearDepth = 0;
    if (ids) {
        pass.colorAttachments[1].texture = identity; pass.colorAttachments[1].loadAction = MTLLoadActionClear;
        pass.colorAttachments[1].storeAction = MTLStoreActionStore; pass.colorAttachments[1].clearColor = MTLClearColorMake(0,0,0,0);
    }
    id<MTLCommandBuffer> cb = [f.queue commandBuffer];
    id<MTLRenderCommandEncoder> re = [cb renderCommandEncoderWithDescriptor:pass]; CHECK(re != nil);
    [re setRenderPipelineState:state]; [re setDepthStencilState:depthState];
    [re setCullMode:raster.cullMode]; [re setFrontFacingWinding:raster.frontFacing]; [re setTriangleFillMode:raster.fillMode];
    [re setDepthBias:raster.depthBiasConstant slopeScale:raster.depthBiasSlopeScale clamp:raster.depthBiasClamp];
    [re setViewport:(MTLViewport){0,0,W,H,0,1}]; [re setScissorRect:(MTLScissorRect){0,0,W,H}];
    [re setDepthClipMode:MTLDepthClipModeClip];
    NSArray<id<MTLBuffer>> *ubos = @[f.projection, f.globals, f.fog, f.terrain]; unsigned slots[] = {0,1,2,5};
    for (unsigned i = 0; i < 4; ++i) {
        [re setVertexBuffer:ubos[i] offset:0 atIndex:slots[i]]; [re setFragmentBuffer:ubos[i] offset:0 atIndex:slots[i]];
    }
    [re setVertexTexture:f.lightmap atIndex:2]; [re setVertexSamplerState:f.sampler atIndex:2];
    [re setFragmentTexture:f.atlas atIndex:0]; [re setFragmentSamplerState:f.sampler atIndex:0];
    [re setVertexBuffer:f.sections offset:0 atIndex:29];
    [re setVertexBuffer:f.arenaA offset:0 atIndex:30]; [re setVertexBuffer:f.itemsA offset:0 atIndex:27];
    [re setVertexBuffer:f.quads offset:reverse ? 8 : 0 atIndex:28];
    uint32_t base = 137;
    if (ids) [re setVertexBytes:&base length:4 atIndex:MCMTLFarShaderArenaBuffer];
    [re drawIndexedPrimitives:raster.primitiveType indexCount:12 indexType:MTLIndexTypeUInt32 indexBuffer:f.indices indexBufferOffset:0];
    [re setVertexBuffer:f.arenaB offset:0 atIndex:30]; [re setVertexBuffer:f.itemsB offset:0 atIndex:27];
    [re setVertexBuffer:f.quads offset:16 atIndex:28];
    if (ids) [re setVertexBytes:&secondBase length:4 atIndex:MCMTLFarShaderArenaBuffer];
    [re drawIndexedPrimitives:raster.primitiveType indexCount:6 indexType:MTLIndexTypeUInt32 indexBuffer:f.indices indexBufferOffset:0];
    [re endEncoding];
    id<MTLBlitCommandEncoder> blit = [cb blitCommandEncoder];
    NSArray<id<MTLTexture>> *targets = ids ? @[color,depth,identity] : @[color,depth];
    for (NSUInteger i = 0; i < targets.count; ++i)
        [blit copyFromTexture:targets[i] sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,0,0) sourceSize:MTLSizeMake(W,H,1)
            toBuffer:readback destinationOffset:ROW * H * i destinationBytesPerRow:ROW destinationBytesPerImage:ROW * H];
    [blit endEncoding];
    if (!finish(cb)) return nil;
    return [NSData dataWithBytes:readback.contents length:ROW * H * targets.count];
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

static void pipelineRejections(MCMTLPipeline *p, NSString *v, NSString *f, MTLCompileOptions *opts) {
    uint64_t key = mcmtl_variant_key(1, MTLPixelFormatDepth32Float);
    MTLRenderPipelineDescriptor *d = p->descriptorTemplate;
    BOOL (^rejected)(uint64_t) = ^BOOL(uint64_t k) {
        NSError *error = nil;
        MCMTLFarShader *s = mcmtl_farshader_compile(p, k, v, f, @"main0", @"main0", opts,
            MCMTLFarShaderBlock, MCMTLFarShaderCutout, &error);
        CHECK(error != nil); return s == nil;
    };
    CHECK(rejected(key | MCMTL_VARIANT_KEY_ICB));
    CHECK(rejected(mcmtl_variant_key(3, MTLPixelFormatDepth32Float)));
    CHECK(rejected(mcmtl_variant_key(1, MTLPixelFormatDepth16Unorm)));
    p->bufferMask[0] |= 1u << 6; CHECK(rejected(key)); p->bufferMask[0] &= ~(1u << 6);
    p->requiredVertexBufferMask |= 1u << 6; CHECK(rejected(key)); p->requiredVertexBufferMask &= ~(1u << 6);
    d.colorAttachments[0].blendingEnabled = YES; CHECK(rejected(key)); d.colorAttachments[0].blendingEnabled = NO;
    d.colorAttachments[1].pixelFormat = MTLPixelFormatR32Uint; CHECK(rejected(key)); d.colorAttachments[1].pixelFormat = MTLPixelFormatInvalid;
    d.colorAttachments[7].pixelFormat = MTLPixelFormatRGBA8Unorm; CHECK(rejected(key)); d.colorAttachments[7].pixelFormat = MTLPixelFormatInvalid;
    d.vertexDescriptor = [MTLVertexDescriptor vertexDescriptor];
    d.vertexDescriptor.attributes[0].format = MTLVertexFormatFloat3;
    CHECK(rejected(key)); d.vertexDescriptor = nil;
    d.rasterSampleCount = 4; CHECK(rejected(key)); d.rasterSampleCount = 1;
    d.rasterizationEnabled = NO; CHECK(rejected(key)); d.rasterizationEnabled = YES;
    p->emulation = MCMTL_EMULATION_TRIANGLE_FAN; CHECK(rejected(key)); p->emulation = MCMTL_EMULATION_NONE;
    p->fillMode = MTLTriangleFillModeLines; CHECK(rejected(key)); p->fillMode = MTLTriangleFillModeFill;
    p->label = @"translucent_lod_compact_terrain_cluster"; CHECK(rejected(key)); p->label = @"cutout_lod_greedy_terrain_cluster";
    p->label = @"cutout_lod_greedy_fade_terrain_cluster"; CHECK(rejected(key)); p->label = @"cutout_lod_greedy_terrain_cluster";
    opts.preprocessorMacros = @{@"color":@"changed"}; CHECK(rejected(key)); opts.preprocessorMacros = nil;
    CHECK(mcmtl_farshader_compile(p, key, v, f, @"main0", @"main0", nil, MCMTLFarShaderBlock, MCMTLFarShaderCutout, NULL) == nil);
    CHECK(mcmtl_farshader_compile(p, key, v, f, @"missing", @"main0", opts, MCMTLFarShaderBlock, MCMTLFarShaderCutout, NULL) == nil);
}

static void gpuTests(void) {
    CHECK(![NSThread isMainThread]);
    id<MTLDevice> device = MTLCreateSystemDefaultDevice(); CHECK(device != nil); if (!device) return;
    MCMTLDevice *owner = [MCMTLDevice new]; owner->device = device;
    for (unsigned layout = 1; layout <= 2; ++layout) for (unsigned vary = 0; vary < 3; ++vary)
    for (unsigned layer = 0; layer < 2; ++layer) for (unsigned sampling = 0; sampling < 3; ++sampling) @autoreleasepool {
        NSString *v = vertexFixture(layout, vary), *f = fragmentFixture(vary, layer, sampling);
        NSString *ve = @"main0", *fe = @"main0";
        BOOL renamed = layout == 2 && vary == 2 && sampling == 2;
        if (renamed) { ve = @"lodVertex"; fe = @"lodFragment"; v = replace(v, @"main0(", @"lodVertex("); f = replace(f, @"main0(", @"lodFragment("); }
        MTLCompileOptions *opts = [MTLCompileOptions new]; opts.languageVersion = MTLLanguageVersion3_2;
        opts.mathMode = sampling == 2 ? MTLMathModeSafe : MTLMathModeFast;
        MCMTLPipeline *p = originalPipeline(owner, v, f, ve, fe, opts, vary, sampling == 2); if (!p) continue;
        if (layout == 1 && vary == 0 && layer == 1 && sampling == 0) pipelineRejections(p, v, f, opts);
        uint64_t key = mcmtl_variant_key(1, MTLPixelFormatDepth32Float);
        if (sampling == 2) key |= MCMTL_VARIANT_KEY_BGRA8_COLOR0;
        NSError *error = nil;
        id<MTLFunction> originalV = p->descriptorTemplate.vertexFunction, originalF = p->descriptorTemplate.fragmentFunction;
        MCMTLFarShader *side = mcmtl_farshader_compile(p, key, v, f, ve, fe, opts, layout, layer, &error);
        CHECK(side != nil && error == nil);
        if (!side) { fprintf(stderr, "layout=%u vary=%u layer=%u sampling=%u: %s\n", layout,vary,layer,sampling,error.description.UTF8String); continue; }
        CHECK(side.depthStencilState == p->depthStencilState && side.layout == layout && side.layer == layer);
        CHECK(side.pipelineState.device == device && !side.pipelineState.supportIndirectCommandBuffers);
        CHECK(side.colorFormat == (sampling == 2 ? MTLPixelFormatBGRA8Unorm : MTLPixelFormatRGBA8Unorm));
        CHECK(side.depthFormat == MTLPixelFormatDepth32Float);
        CHECK([side.originalVertex isEqualToString:v] && [side.originalFragment isEqualToString:f]);
        CHECK([side.originalVertexEntry isEqualToString:ve] && [side.originalFragmentEntry isEqualToString:fe]);
        MTLCompileOptions *savedOptions=[side copyOriginalCompileOptions];
        CHECK(savedOptions!=opts && savedOptions.languageVersion==opts.languageVersion &&
            savedOptions.mathMode==opts.mathMode && savedOptions.preserveInvariance==opts.preserveInvariance);
        MTLMathMode originalMath=opts.mathMode;
        savedOptions.mathMode=opts.mathMode=originalMath==MTLMathModeSafe?MTLMathModeFast:MTLMathModeSafe;
        CHECK([side copyOriginalCompileOptions].mathMode==originalMath);
        opts.mathMode=originalMath;
        MCMTLFarShaderBindings bindings = side.bindings;
        CHECK(bindings.requiredVertexBufferMask == (p->requiredVertexBufferMask | (1u << 6)));
        for (unsigned stage = 0; stage < 2; ++stage) {
            CHECK(bindings.bufferMask[stage] == (p->bufferMask[stage] | (stage == 0 ? 1u << 6 : 0)));
            CHECK(bindings.textureMask[stage] == p->textureMask[stage] && bindings.samplerMask[stage] == p->samplerMask[stage]);
            CHECK(bindings.textureCubeMask[stage] == p->textureCubeMask[stage] && bindings.texelBufferMask[stage] == p->texelBufferMask[stage]);
        }
        CHECK(p->descriptorTemplate.vertexFunction == originalV && p->descriptorTemplate.fragmentFunction == originalF);
        CHECK(p->descriptorTemplate.colorAttachments[1].pixelFormat == MTLPixelFormatInvalid);
        CHECK(p->descriptorTemplate.colorAttachments[0].pixelFormat == MTLPixelFormatRGBA8Unorm);
        CHECK(p->descriptorTemplate.depthAttachmentPixelFormat == MTLPixelFormatInvalid);
        CHECK(!p->descriptorTemplate.supportIndirectCommandBuffers && opts.preprocessorMacros.count == 0);
        CHECK(p->variantCount == 1 && p->variantKeys[0] == 0xdeadbeef && p->variantStates[0] == nil && p->overflowVariants.count == 0);
        MTLRenderPipelineDescriptor *reference = [p->descriptorTemplate copy];
        reference.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float; reference.colorAttachments[0].pixelFormat = side.colorFormat;
        id<MTLRenderPipelineState> original = [device newRenderPipelineStateWithDescriptor:reference error:&error];
        CHECK(original != nil); if (!original) { fprintf(stderr, "%s\n", error.description.UTF8String); continue; }
        Fixture *scene = fixture(device, layout);
        MCMTLFarShaderArena a, b;
        uint32_t secondBase = sampling == 2 ? UINT32_MAX - 6u : 900u;
        CHECK(mcmtl_farshader_arena(137, 128, &a, NULL) && mcmtl_farshader_arena(secondBase, 6, &b, NULL));
        CHECK(mcmtl_farshader_item(a, 80, 6, NULL) && mcmtl_farshader_item(a, 160, 64, NULL) && mcmtl_farshader_item(b, 16, 2, NULL));
        NSData *previous = nil;
        for (unsigned reverse = 0; reverse < 2; ++reverse) {
            NSData *plain = render(scene, original, p->depthStencilState, side.raster, side.colorFormat, NO, reverse, secondBase);
            NSData *ids = render(scene, side.pipelineState, side.depthStencilState, side.raster, side.colorFormat, YES, reverse, secondBase);
            CHECK(plain != nil && ids != nil); if (!plain || !ids) continue;
            CHECK(memcmp(plain.bytes, ids.bytes, ROW * H) == 0);
            CHECK(memcmp((const uint8_t *)plain.bytes + ROW * H, (const uint8_t *)ids.bytes + ROW * H, ROW * H) == 0);
            if (previous) CHECK([previous isEqualToData:ids]); previous = ids;
            const float *depth = (const float *)((const uint8_t *)plain.bytes + ROW * H);
            const uint32_t *faceIDs = (const uint32_t *)((const uint8_t *)ids.bytes + 2 * ROW * H);
            unsigned background = 0, near = 0, equal = 0, alphaHoles = 0, revealed = 0, errors = 0;
            for (unsigned y = 0; y < H; ++y) for (unsigned x = 0; x < W; ++x) {
                unsigned at = y * W + x; uint32_t expected = 0;
                BOOL eq = x >= 16 && x < 32 && y >= 16 && y < 32;
                if (depth[at] == 0.25f) { expected = 163; ++background; }
                else if (depth[at] == 0.75f) { expected = eq ? secondBase + 6u : 241; if (eq) ++equal; else ++near; }
                else if (depth[at] != 0) ++errors;
                if (faceIDs[at] != expected) ++errors;
                BOOL insideNear = x >= 8 && x < 56 && y >= 8 && y < 56;
                if (insideNear && x >= 32 && depth[at] == 0) ++alphaHoles;
                if (insideNear && x < 32 && !eq && depth[at] == 0.25f) ++revealed;
            }
            CHECK(errors == 0 && background > 100 && near > 100 && equal == 256);
            CHECK(layer == MCMTLFarShaderCutout ? alphaHoles > 100 && revealed > 100 : alphaHoles == 0 && revealed == 0);
        }
        /* Fixed state is owned/copied, unaffected by later caller metadata edits. */
        MCMTLFarShaderRaster saved = side.raster;
        p->cullMode = MTLCullModeFront; p->frontFacing = MTLWindingCounterClockwise; p->depthBiasConstant = 12;
        CHECK(side.raster.cullMode == saved.cullMode && side.raster.frontFacing == saved.frontFacing && side.raster.depthBiasConstant == saved.depthBiasConstant);
        p->bufferMask[0] = 0; CHECK(side.bindings.bufferMask[0] == bindings.bufferMask[0]);
        p->depthStencilState = nil; CHECK(side.depthStencilState != nil);
        CHECK(owner->currentCommandBuffer == nil && owner->event == nil);
    }
}

int main(int argc, const char **argv) { @autoreleasepool {
    sourceTests();
    NSError *error = nil;
    CHECK(mcmtl_farshader_compile(nil, 0, nil, nil, nil, nil, nil, MCMTLFarShaderBlock, MCMTLFarShaderSolid, &error) == nil);
    CHECK([error.localizedDescription containsString:@"worker-only"]);
    BOOL sourcesOnly = argc == 2 && strcmp(argv[1], "--sources-only") == 0;
    if (argc > 1 && !sourcesOnly) { fprintf(stderr, "usage: farimage_shader_test [--sources-only]\n"); return 2; }
    /* Use async + semaphore: dispatch_sync may execute on the main thread. */
    if (!sourcesOnly) {
        dispatch_semaphore_t done = dispatch_semaphore_create(0);
        dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{ @autoreleasepool { gpuTests(); dispatch_semaphore_signal(done); } });
        dispatch_semaphore_wait(done, DISPATCH_TIME_FOREVER);
    }
    printf("farimage_shader_test: %u checks, %u failures%s\n", checks, failures, sourcesOnly ? " (source-only)" : "");
    return failures ? 1 : 0;
} }
