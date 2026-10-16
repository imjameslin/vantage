/* Independent real-Metal reference for current-material ID shading.
 * Run under tools/gpulock.py functional, optionally MTL_DEBUG_LAYER=1.
 * Six admitted varying/layer pairs, runtime nearest/RGSS, two vertex layouts
 * in every image. Reference interpolation/derivatives come from hardware using
 * the ORIGINAL terrain stages plus the existing ID-only MRT instrumentation.
 * No production reconstruction helper is used to author expected pixels.
 * Color tolerance is <=1 UNORM8 unit; conservative depth <=2e-6. Exact mode
 * requires zero rejected pixels and bit-identical hardware source depth.
 * This is a bounded shading test, not a coverage proof or performance gate.
 */
#import "mcmetal_farshade.h"
#import "mcmetal_farwarp.h"
#include <dispatch/dispatch.h>
#include <simd/simd.h>
#include <math.h>
#include <stdio.h>
#include <string.h>

static unsigned checks, failures;
#define CHECK(x) do { ++checks; if (!(x)) { ++failures; fprintf(stderr,"FAIL %d: %s\n",__LINE__,#x); } } while (0)
#import "farimage_shader_fixture.h"

enum { W=64, H=64, ROW=256, PLANE=ROW*H };
static const uint32_t bases[2]={137,997}, localFaces[2]={2,5};
static const NSUInteger arenaOffsets[2]={16,32};

@interface ShadeFixture : NSObject {
@public
    MCMTLFarShadeUniforms uniforms;
}
@property(nonatomic, strong) MCMTLDevice *owner;
@property(nonatomic, strong) id<MTLCommandQueue> queue;
@property(nonatomic, copy) NSArray<id<MTLBuffer>> *arenas, *items;
@property(nonatomic, strong) id<MTLBuffer> sections, quads, indices;
@property(nonatomic, strong) id<MTLTexture> atlas, lightmap;
@property(nonatomic, strong) id<MTLSamplerState> atlasSampler, lightSampler;
@property(nonatomic, strong) MCMTLFarFaceLookup *lookup;
@property(nonatomic, strong) MCMTLFarWarp *warp;
@end
@implementation ShadeFixture
@end

@interface ShadeImage : NSObject
@property(nonatomic, strong) id<MTLTexture> color, depth, third;
@property(nonatomic, strong) id<MTLTexture> validity;
@property(nonatomic, copy) NSData *bytes;
@end
@implementation ShadeImage
@end

static void diagnostic(NSError *error) { if (error) fprintf(stderr,"%s\n",error.description.UTF8String); }
static id<MTLBuffer> buffer(id<MTLDevice> d, const void *bytes, NSUInteger n) {
    id<MTLBuffer> b=[d newBufferWithBytes:bytes length:n options:MTLResourceStorageModeShared]; CHECK(b); return b;
}
static id<MTLTexture> sizedTexture(id<MTLDevice> d, MTLPixelFormat format, NSUInteger width, NSUInteger height, BOOL shared) {
    MTLTextureDescriptor *t=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:format width:width height:height mipmapped:NO];
    t.storageMode=shared?MTLStorageModeShared:MTLStorageModePrivate;
    t.usage=MTLTextureUsageShaderRead|(shared?0:MTLTextureUsageRenderTarget);
    if (!shared && format!=MTLPixelFormatDepth32Float) t.usage|=MTLTextureUsageShaderWrite;
    id<MTLTexture> result=[d newTextureWithDescriptor:t]; CHECK(result); return result;
}
static id<MTLTexture> texture(id<MTLDevice> d, MTLPixelFormat format, BOOL shared) {
    return sizedTexture(d,format,W,H,shared);
}
static BOOL finish(id<MTLCommandBuffer> cb) {
    dispatch_semaphore_t done=dispatch_semaphore_create(0);
    [cb addCompletedHandler:^(id<MTLCommandBuffer> b) { dispatch_semaphore_signal(done); }];
    [cb commit];
    BOOL ok=dispatch_semaphore_wait(done,dispatch_time(DISPATCH_TIME_NOW,10*NSEC_PER_SEC))==0;
    CHECK(ok); if (!ok) return NO;
    diagnostic(cb.error); CHECK(cb.status==MTLCommandBufferStatusCompleted);
    return cb.status==MTLCommandBufferStatusCompleted;
}
static BOOL readback(ShadeFixture *f, ShadeImage *image, id<MTLCommandBuffer> cb) {
    id<MTLBuffer> out=[f.owner->device newBufferWithLength:PLANE*3 options:MTLResourceStorageModeShared]; CHECK(out);
    if (!out) return NO;
    id<MTLBlitCommandEncoder> blit=[cb blitCommandEncoder]; CHECK(blit); if (!blit) return NO;
    NSArray *planes=@[image.color,image.depth,image.third];
    for (NSUInteger i=0;i<3;++i) [blit copyFromTexture:planes[i] sourceSlice:0 sourceLevel:0
        sourceOrigin:MTLOriginMake(0,0,0) sourceSize:MTLSizeMake(W,H,1) toBuffer:out
        destinationOffset:PLANE*i destinationBytesPerRow:ROW destinationBytesPerImage:PLANE];
    [blit endEncoding]; if (!finish(cb)) return NO;
    image.bytes=[NSData dataWithBytes:out.contents length:PLANE*3]; return YES;
}

/* Independent mesh authoring. Compact has local x=8 and section origin x=1;
 * BLOCK has x=1. Unequal corner lights sample a nonlinear 16x16 lightmap.
 * UV crosses multiple 16-texel greedy seams at minification, including RGSS mips.
 */
static NSData *faceBytes(unsigned layout) {
    unsigned q=localFaces[layout], stride=layout?48:112;
    NSMutableData *data=[NSMutableData dataWithLength:arenaOffsets[layout]+(q+1)*stride];
    uint32_t *r=(uint32_t *)((uint8_t *)data.mutableBytes+arenaOffsets[layout]+q*stride);
    const uint32_t color[4]={0xff80c0ff,0xffc0ff80,0xffff80c0,0xffd0a0e0};
    const unsigned block[4]={0,240,80,176}, sky[4]={224,32,160,0};
    const unsigned corner[4][2]={{0,0},{1,0},{1,1},{0,1}};
    float x=layout?8:1, y=2, z=layout?8:4, sx=6, sy=12;
    if (!layout) {
        for (unsigned k=0;k<4;++k) {
            float p[3]={x+sx*corner[k][0],y+sy*corner[k][1],z};
            float uv[2]={0.0625f+3.5f*corner[k][0],0.0625f+2.5f*corner[k][1]};
            memcpy(r+7*k,p,12); r[7*k+3]=color[k]; memcpy(r+7*k+4,uv,8);
            r[7*k+6]=(block[k]|0x100u)|((sky[k]|0x100u)<<16);
        }
    } else {
        r[0]=(uint32_t)(x*32)|((uint32_t)(y*32)<<10)|((uint32_t)(z*32)<<20);
        r[1]=((uint32_t)(sx*32)<<2)|(1u<<14)|((uint32_t)(sy*32)<<16);
        memcpy(r+2,color,16);
        for (unsigned k=0;k<4;++k) r[6+k/2]|=(block[k]|(sky[k]<<8))<<((k&1)*16);
        r[8]=r[9]=32|(1u<<20); // UV=2 atlas texels, greedyTile=(1,1)
        r[10]=1792; r[11]=1280u<<16; // UV edges in 1/16 atlas texel
    }
    return data;
}

/* mode 0 baseline, 1 changed light, 2 changed atlas RGB plus light, 3 changed fog.
 * Alpha is deliberately independently controlled for coverage experiments. */
static void material(ShadeFixture *f, unsigned mode, uint8_t alpha) {
    float fog[12]={0.18f,0.28f,0.38f,1, 8,30, 10,35, 100,100, 12,0.25f};
    if (mode==3) {
        fog[0]=0.65f; fog[1]=0.31f; fog[2]=0.12f;
        fog[4]=4; fog[5]=20; fog[6]=5; fog[7]=25; fog[10]=8; fog[11]=0.5f;
    }
    memcpy(f->uniforms.fog,fog,sizeof fog);
    for (unsigned mip=0;mip<f.atlas.mipmapLevelCount;++mip) {
        unsigned size=32u>>mip; uint8_t pixels[32*32*4];
        for (unsigned y=0;y<size;++y) for (unsigned x=0;x<size;++x) {
            unsigned o=4*(y*size+x);
            pixels[o]=(uint8_t)(40+(x*29+y*7+mip*19+(mode==2?91:0))%180);
            pixels[o+1]=(uint8_t)(45+(x*9+y*23+mip*11+(mode==2?57:0))%175);
            pixels[o+2]=(uint8_t)(60+(x*17+y*5+mip*37)%160); pixels[o+3]=alpha;
        }
        [f.atlas replaceRegion:MTLRegionMake2D(0,0,size,size) mipmapLevel:mip withBytes:pixels bytesPerRow:size*4];
    }
    uint8_t light[16*16*4];
    for (unsigned y=0;y<16;++y) for (unsigned x=0;x<16;++x) {
        unsigned o=4*(16*y+x);
        light[o]=(uint8_t)(35+(x*x+7*y+(mode?79:0))%210);
        light[o+1]=(uint8_t)(45+(y*y+3*x+(mode?113:0))%200);
        light[o+2]=(uint8_t)(50+(x*y+11*x+(mode?37:0))%195); light[o+3]=255;
    }
    [f.lightmap replaceRegion:MTLRegionMake2D(0,0,16,16) mipmapLevel:0 withBytes:light bytesPerRow:64];
}

static ShadeFixture *fixture(MCMTLDevice *owner, unsigned layer) {
    ShadeFixture *f=[ShadeFixture new]; f.owner=owner; f.queue=[owner->device newCommandQueue]; CHECK(f.queue);
    matrix_float4x4 p=matrix_identity_float4x4, model=matrix_identity_float4x4;
    p.columns[0].x=p.columns[1].y=0.125f; p.columns[2].z=0.0625f;
    p.columns[3].x=p.columns[3].y=-1; p.columns[0].w=0.015625f; // perspective interpolation
    memcpy(f->uniforms.projection,&p,64); memcpy(f->uniforms.terrain,&model,64);
    int size[2]={32,32}; memcpy(f->uniforms.terrain+64,size,8);
    float screen[2]={W,H}; memcpy(f->uniforms.globals+32,screen,8);
    float fog[12]={0.18f,0.28f,0.38f,1, 8,30, 10,35, 100,100, 12,0.25f};
    memcpy(f->uniforms.fog,fog,sizeof fog);
    uint32_t sections[8]={0,0,0,0x3f800000,1,0,0,0x3f800000};
    f.sections=buffer(owner->device,sections,sizeof sections);
    uint32_t indices[6]={0,1,2,2,3,0}, quad=0;
    f.indices=buffer(owner->device,indices,sizeof indices); f.quads=buffer(owner->device,&quad,4);
    NSMutableArray *arenas=[NSMutableArray array], *items=[NSMutableArray array], *inputs=[NSMutableArray array];
    for (unsigned i=0;i<2;++i) {
        NSData *data=faceBytes(i); id<MTLBuffer> vb=buffer(owner->device,data.bytes,data.length);
        if (!vb) return nil; [arenas addObject:vb];
        uint32_t pull[2]={localFaces[i]*4,i}, original[4]={localFaces[i]*4,i|(1u<<29),0,0};
        [items addObject:buffer(owner->device,pull,sizeof pull)];
        MCMTLFarClusterInput *in=[[MCMTLFarClusterInput alloc] initWithItems:[NSData dataWithBytes:original length:sizeof original]
            vertices:vb vertexOffset:arenaOffsets[i] indices:f.indices indexOffset:0 layerCode:layer|(i?8:0)
            faceBase:bases[i] lifetime:@"shade-test immutable arena lease"];
        CHECK(in); if (!in) return nil; [inputs addObject:in];
    }
    f.arenas=arenas; f.items=items;
    uint32_t view[32]={0}; view[21]=4; NSError *error=nil;
    MCMTLFarCullSnapshot *snapshot=[[MCMTLFarCullSnapshot alloc] initWithDevice:owner inputs:inputs
        cullSections:[NSMutableData dataWithLength:64] shaderSections:[NSData dataWithBytes:sections length:sizeof sections]
        view:[NSData dataWithBytes:view length:sizeof view] budgetBytes:1u<<20 error:&error];
    CHECK(snapshot && !error); diagnostic(error); if (!snapshot) return nil;
    f.lookup=[[MCMTLFarFaceLookup alloc] initWithSnapshot:snapshot budgetBytes:1u<<20 error:&error];
    CHECK(f.lookup && !error && f.lookup.arenaCount==2); diagnostic(error); if (!f.lookup) return nil;
    f.warp=[[MCMTLFarWarp alloc] initWithDevice:owner->device error:&error];
    CHECK(f.warp && !error); diagnostic(error); if (!f.warp) return nil;
    MTLTextureDescriptor *td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:32 height:32 mipmapped:YES];
    td.storageMode=MTLStorageModeShared; td.usage=MTLTextureUsageShaderRead;
    f.atlas=[owner->device newTextureWithDescriptor:td]; td.width=td.height=16; td.mipmapLevelCount=1;
    f.lightmap=[owner->device newTextureWithDescriptor:td];
    MTLSamplerDescriptor *sd=[MTLSamplerDescriptor new]; sd.minFilter=sd.magFilter=MTLSamplerMinMagFilterLinear;
    sd.mipFilter=MTLSamplerMipFilterLinear; sd.sAddressMode=sd.tAddressMode=MTLSamplerAddressModeClampToEdge;
    f.atlasSampler=[owner->device newSamplerStateWithDescriptor:sd]; sd.mipFilter=MTLSamplerMipFilterNotMipmapped;
    f.lightSampler=[owner->device newSamplerStateWithDescriptor:sd];
    CHECK(f.atlas && f.lightmap && f.atlasSampler && f.lightSampler);
    return f.atlas && f.lightmap && f.atlasSampler && f.lightSampler ? f : nil;
}

static MCMTLFarShader *referencePipeline(MCMTLDevice *owner, unsigned layout, unsigned vary, unsigned layer, MTLCompileOptions *opts) {
    NSString *vs=vertexFixture(layout,vary), *fs=fragmentFixture(vary,layer,0); NSError *error=nil;
    id<MTLLibrary> vl=[owner->device newLibraryWithSource:vs options:opts error:&error]; diagnostic(error); CHECK(vl);
    id<MTLLibrary> fl=[owner->device newLibraryWithSource:fs options:opts error:&error]; diagnostic(error); CHECK(fl);
    if (!vl || !fl) return nil;
    MCMTLPipeline *p=[MCMTLPipeline new]; p->owner=owner; p->label=@"shade independent terrain reference";
    MTLRenderPipelineDescriptor *d=[MTLRenderPipelineDescriptor new]; p->descriptorTemplate=d;
    d.vertexFunction=[vl newFunctionWithName:@"main0"]; d.fragmentFunction=[fl newFunctionWithName:@"main0"];
    d.vertexDescriptor=gameVertexDescriptor(); d.colorAttachments[0].pixelFormat=MTLPixelFormatRGBA8Unorm;
    d.inputPrimitiveTopology=MTLPrimitiveTopologyClassTriangle; d.rasterSampleCount=1;
    p->colorTargetCount=1; p->colorTargetFormats[0]=MTLPixelFormatRGBA8Unorm;
    MTLDepthStencilDescriptor *dd=[MTLDepthStencilDescriptor new]; dd.depthCompareFunction=MTLCompareFunctionGreaterEqual; dd.depthWriteEnabled=YES;
    p->depthStencilState=[owner->device newDepthStencilStateWithDescriptor:dd];
    p->primitiveType=MTLPrimitiveTypeTriangle; p->fillMode=MTLTriangleFillModeFill;
    p->frontFacing=MTLWindingClockwise; p->cullMode=MTLCullModeNone;
    p->requiredVertexBufferMask=15u<<27;
    p->bufferMask[0]=(1u<<0)|(1u<<1)|(1u<<5)|(vary?1u<<2:0); p->bufferMask[1]=(1u<<1)|(1u<<2)|(1u<<5);
    p->textureMask[0]=p->samplerMask[0]=1u<<2; p->textureMask[1]=p->samplerMask[1]=1;
    MCMTLFarShader *result=mcmtl_farshader_compile(p,mcmtl_variant_key(1,MTLPixelFormatDepth32Float),
        vs,fs,@"main0",@"main0",opts,layout,layer,&error);
    CHECK(result && !error); diagnostic(error); return result;
}

static ShadeImage *referenceCrop(ShadeFixture *f, NSArray<MCMTLFarShader *> *pipelines, unsigned guard) {
    // Real hardware rasterization into a larger guard image, then the production
    // exact integer crop. Expected shading comes only from the original stages.
    // Even crop origin preserves the 2x2 helper-lane sampling footprint.
    NSUInteger width=W+2*guard,height=H+2*guard;
    ShadeImage *out=[ShadeImage new]; id<MTLDevice> d=f.owner->device;
    id<MTLTexture> hwColor=sizedTexture(d,MTLPixelFormatRGBA8Unorm,width,height,NO);
    id<MTLTexture> hwDepth=sizedTexture(d,MTLPixelFormatDepth32Float,width,height,NO);
    id<MTLTexture> hwIDs=sizedTexture(d,MTLPixelFormatR32Uint,width,height,NO);
    out.color=texture(d,MTLPixelFormatRGBA8Unorm,NO); out.depth=texture(d,MTLPixelFormatR32Float,NO);
    out.third=texture(d,MTLPixelFormatR32Uint,NO);
    out.validity=texture(d,MTLPixelFormatR8Uint,NO);
    if (!hwColor || !hwDepth || !hwIDs || !out.color || !out.depth || !out.third || !out.validity) return nil;
    MTLRenderPassDescriptor *pass=[MTLRenderPassDescriptor renderPassDescriptor];
    pass.colorAttachments[0].texture=hwColor; pass.colorAttachments[1].texture=hwIDs;
    for (unsigned i=0;i<2;++i) {
        pass.colorAttachments[i].loadAction=MTLLoadActionClear; pass.colorAttachments[i].storeAction=MTLStoreActionStore;
        pass.colorAttachments[i].clearColor=MTLClearColorMake(0,0,0,0); // shade sky contract, including alpha
    }
    pass.depthAttachment.texture=hwDepth; pass.depthAttachment.loadAction=MTLLoadActionClear;
    pass.depthAttachment.storeAction=MTLStoreActionStore; pass.depthAttachment.clearDepth=0;
    id<MTLCommandBuffer> cb=[f.queue commandBuffer]; id<MTLRenderCommandEncoder> e=[cb renderCommandEncoderWithDescriptor:pass]; CHECK(e); if (!e) return nil;
    MCMTLFarShadeUniforms u=f->uniforms;
    matrix_float4x4 p; memcpy(&p,u.projection,64);
    for (unsigned i=0;i<4;++i) { p.columns[i].x*=((float)W/width); p.columns[i].y*=((float)H/height); }
    memcpy(u.projection,&p,64); float screen[2]={width,height}; memcpy(u.globals+32,screen,8);
    const void *ubos[]={u.projection,u.globals,u.fog,u.terrain};
    const unsigned sizes[]={64,48,48,80}, slots[]={0,1,2,5};
    for (unsigned i=0;i<4;++i) { [e setVertexBytes:ubos[i] length:sizes[i] atIndex:slots[i]]; [e setFragmentBytes:ubos[i] length:sizes[i] atIndex:slots[i]]; }
    [e setViewport:(MTLViewport){0,0,width,height,0,1}]; [e setScissorRect:(MTLScissorRect){0,0,width,height}];
    [e setVertexTexture:f.lightmap atIndex:2]; [e setVertexSamplerState:f.lightSampler atIndex:2];
    [e setFragmentTexture:f.atlas atIndex:0]; [e setFragmentSamplerState:f.atlasSampler atIndex:0];
    [e setVertexBuffer:f.sections offset:0 atIndex:29]; [e setVertexBuffer:f.quads offset:0 atIndex:28];
    for (unsigned i=0;i<2;++i) {
        MCMTLFarShader *p=pipelines[i]; [e setRenderPipelineState:p.pipelineState]; [e setDepthStencilState:p.depthStencilState];
        [e setCullMode:p.raster.cullMode]; [e setFrontFacingWinding:p.raster.frontFacing];
        [e setVertexBuffer:f.arenas[i] offset:arenaOffsets[i] atIndex:30]; [e setVertexBuffer:f.items[i] offset:0 atIndex:27];
        [e setVertexBytes:&bases[i] length:4 atIndex:6];
        [e drawIndexedPrimitives:MTLPrimitiveTypeTriangle indexCount:6 indexType:MTLIndexTypeUInt32 indexBuffer:f.indices indexBufferOffset:0];
    }
    [e endEncoding];
    MCMTLFarWarpTransform transform={.motion=MCMTLFarWarpMotionPureRotation};
    transform.currentToSourceClip[0]=(float)((double)W/width);
    transform.currentToSourceClip[5]=(float)((double)H/height);
    transform.sourceToCurrentClip[0]=(float)((double)width/W);
    transform.sourceToCurrentClip[5]=(float)((double)height/H);
    transform.currentToSourceClip[10]=transform.currentToSourceClip[15]=1;
    transform.sourceToCurrentClip[10]=transform.sourceToCurrentClip[15]=1;
    NSError *error=nil;
    BOOL ok=[f.warp encodeToCommandBuffer:cb source:(MCMTLFarWarpSource){hwColor,hwDepth,hwIDs}
        targets:(MCMTLFarWarpTargets){out.color,out.depth,out.third,out.validity} transform:&transform error:&error];
    CHECK(ok && !error); diagnostic(error); if (!ok) return nil;
    return readback(f,out,cb)?out:nil;
}
static ShadeImage *reference(ShadeFixture *f, NSArray<MCMTLFarShader *> *pipelines) {
    return referenceCrop(f,pipelines,0);
}

static id<MTLTexture> validity(ShadeFixture *f, ShadeImage *source) {
    id<MTLTexture> mask=texture(f.owner->device,MTLPixelFormatR8Uint,YES); uint8_t bytes[W*H];
    const uint32_t *ids=(const uint32_t *)((const uint8_t *)source.bytes.bytes+2*PLANE);
    for (unsigned i=0;i<W*H;++i) bytes[i]=ids[i]?2:3;
    [mask replaceRegion:MTLRegionMake2D(0,0,W,H) mipmapLevel:0 withBytes:bytes bytesPerRow:W]; return mask;
}
static ShadeImage *shadeWithDiagnostics(ShadeFixture *f, MCMTLFarShade *program, MCMTLFarShadeGeometry *geometry,
                                ShadeImage *source, id<MTLTexture> mask, BOOL checkPin, BOOL exact, id<MTLBuffer> counters) {
    ShadeImage *out=[ShadeImage new]; id<MTLDevice> d=f.owner->device;
    out.color=texture(d,MTLPixelFormatRGBA8Unorm,NO); out.depth=texture(d,MTLPixelFormatR32Float,NO); out.third=texture(d,MTLPixelFormatR8Uint,NO);
    if (!out.color || !out.depth || !out.third) return nil;
    if (!mask) mask=source.validity; if (!mask) return nil;
    MCMTLFarShadeSource src={source.third,mask,source.depth,exact}; MCMTLFarShadeTargets dst={out.color,out.depth,out.third};
    MCMTLFarShadeMaterials mat={.atlas=f.atlas,.lightmap=f.lightmap,.atlasSampler=f.atlasSampler,.lightSampler=f.lightSampler};
    NSError *error=nil; id<MTLCommandBuffer> cb=[f.queue commandBuffer];
    BOOL ok=NO;
    __weak MCMTLFarShadeGeometry *weakPrepared=nil;
    @autoreleasepool {
        MCMTLFarShadeGeometry *prepared=checkPin?[program prepareLookup:f.lookup budgetBytes:1u<<20 error:&error]:geometry;
        CHECK(prepared && !error); if (!prepared) { diagnostic(error); return nil; }
        weakPrepared=prepared;
        if (counters) ok=[program encodeToCommandBuffer:cb geometry:prepared source:src targets:dst uniforms:&f->uniforms
            materials:mat diagnostics:counters error:&error];
        else ok=[program encodeToCommandBuffer:cb geometry:prepared source:src targets:dst uniforms:&f->uniforms materials:mat error:&error];
    }
    CHECK(ok && !error); diagnostic(error); if (!ok) return nil;
    if (checkPin) CHECK(weakPrepared!=nil); // only the uncommitted CB owns this fresh preparation
    return readback(f,out,cb)?out:nil;
}
static ShadeImage *shadeWithMask(ShadeFixture *f, MCMTLFarShade *program, MCMTLFarShadeGeometry *geometry,
                                ShadeImage *source, id<MTLTexture> mask, BOOL checkPin, BOOL exact) {
    return shadeWithDiagnostics(f,program,geometry,source,mask,checkPin,exact,nil);
}
static ShadeImage *shade(ShadeFixture *f, MCMTLFarShade *program, MCMTLFarShadeGeometry *geometry, ShadeImage *source) {
    return shadeWithMask(f,program,geometry,source,nil,NO,NO);
}

/* Independent CPU geometry projection, used ONLY to bound allowed uncertainty
 * near raster edges; it supplies no expected color/interpolation/depth values. */
static float edgeDistance(uint32_t id, unsigned x, unsigned y) {
    unsigned arena=id==bases[0]+localFaces[0]+1?0:1;
    const unsigned corner[4][2]={{0,0},{1,0},{1,1},{0,1}};
    double p[4][2];
    for (unsigned k=0;k<4;++k) {
        double wx=(arena?9:1)+6*corner[k][0], wy=2+12*corner[k][1], w=1+wx/64;
        p[k][0]=((wx/8-1)/w*.5+.5)*W; p[k][1]=((wy/8-1)/w*.5+.5)*H;
    }
    const unsigned edge[5][2]={{0,1},{1,2},{2,3},{3,0},{0,2}}; double closest=1e9;
    for (unsigned i=0;i<5;++i) {
        unsigned a=edge[i][0], b=edge[i][1]; double dx=p[b][0]-p[a][0], dy=p[b][1]-p[a][1];
        double distance=fabs(dx*(y+.5-p[a][1])-dy*(x+.5-p[a][0]))/hypot(dx,dy);
        closest=fmin(closest,distance);
    }
    return (float)closest;
}

static void compare(ShadeImage *want, ShadeImage *got, unsigned vary, unsigned layer, unsigned rgss, unsigned mode, BOOL exact) {
    if (!want || !got) { CHECK(NO); return; }
    const uint8_t *a=want.bytes.bytes, *b=got.bytes.bytes;
    const uint32_t *ids=(const uint32_t *)(a+2*PLANE); const float *za=(const float *)(a+PLANE), *zb=(const float *)(b+PLANE);
    unsigned counts[2]={0}, uncertain=0, badMask=0, badColor=0, badDepth=0, differing=0, maxColor=0, depthBits=0; float maxDepth=0;
    for (unsigned y=0;y<H;++y) for (unsigned x=0;x<W;++x) {
        unsigned k=y*W+x; uint8_t mask=b[2*PLANE+y*ROW+x];
        if (ids[k]) {
            if (ids[k]==bases[0]+localFaces[0]+1) ++counts[0];
            else if (ids[k]==bases[1]+localFaces[1]+1) ++counts[1]; else ++badMask;
        }
        if (ids[k] && mask==1) {
            ++uncertain;
            // Source module explicitly rejects its 1/256-pixel edge band.
            // Admit only independently verified nearby edges, never arbitrary holes.
            if (exact || edgeDistance(ids[k],x,y)>0.006f) ++badMask;
            if (zb[k]!=0 || b[4*k] || b[4*k+1] || b[4*k+2] || b[4*k+3]) ++badMask;
            continue;
        }
        if (mask!=(ids[k]?2:3)) ++badMask;
        for (unsigned c=0;c<4;++c) { unsigned delta=(unsigned)abs((int)a[4*k+c]-(int)b[4*k+c]);
            maxColor=MAX(maxColor,delta); differing+=delta!=0; badColor+=delta>1; }
        float dz=fabsf(za[k]-zb[k]); maxDepth=fmaxf(maxDepth,dz);
        badDepth+=!isfinite(zb[k])||dz>2e-6f;
        depthBits+=memcmp(za+k,zb+k,4)!=0;
    }
    printf("shade exact=%u vary=%u layer=%u rgss=%u material=%u: faces=%u/%u edgeUncertain=%u maskBad=%u colorBad=%u maxLSB=%u differingChannels=%u depthBad=%u maxDepth=%.9g depthBits=%u\n",
        exact,vary,layer,rgss,mode,counts[0],counts[1],uncertain,badMask,badColor,maxColor,differing,badDepth,maxDepth,depthBits);
    CHECK(counts[0]>100 && counts[1]>100); CHECK(uncertain<(counts[0]+counts[1])/20);
    CHECK(!badMask); CHECK(!badColor); CHECK(!badDepth);
    if (exact) { CHECK(!uncertain); CHECK(!depthBits); }
}

static void invalidMasks(ShadeFixture *f, MCMTLFarShade *program, MCMTLFarShadeGeometry *geometry, ShadeImage *source) {
    const uint32_t *ids=(const uint32_t *)((const uint8_t *)source.bytes.bytes+2*PLANE);
    uint8_t masks[W*H]; unsigned selected[5]={0}, count=0, background=0;
    for (unsigned i=0;i<W*H;++i) {
        masks[i]=ids[i]?2:3;
        if (ids[i] && count<4) selected[count++]=i;
        if (!ids[i]) background=i;
    }
    CHECK(count==4 && ids[background]==0); if (count!=4 || ids[background]) return;
    selected[4]=background;
    const uint8_t prior[5]={0,1,7,3,2}, expected[5]={0,1,1,1,1};
    for (unsigned i=0;i<5;++i) masks[selected[i]]=prior[i];
    id<MTLTexture> mask=texture(f.owner->device,MTLPixelFormatR8Uint,YES);
    [mask replaceRegion:MTLRegionMake2D(0,0,W,H) mipmapLevel:0 withBytes:masks bytesPerRow:W];
    ShadeImage *out=shadeWithMask(f,program,geometry,source,mask,YES,YES); if (!out) { CHECK(NO); return; }
    const uint8_t *b=out.bytes.bytes; const float *depth=(const float *)(b+PLANE); BOOL correct=YES;
    for (unsigned i=0;i<5;++i) { unsigned k=selected[i];
        correct &= b[2*PLANE+(k/W)*ROW+k%W]==expected[i] && depth[k]==0;
        for (unsigned c=0;c<4;++c) correct &= b[4*k+c]==0;
    }
    CHECK(correct);
}

static void negativeInputs(ShadeFixture *f, MCMTLFarShade *program, MCMTLFarShadeGeometry *geometry, ShadeImage *source) {
    id<MTLDevice> device=f.owner->device;
    id<MTLTexture> color=texture(device,MTLPixelFormatRGBA8Unorm,NO), depth=texture(device,MTLPixelFormatR32Float,NO);
    id<MTLTexture> valid=texture(device,MTLPixelFormatR8Uint,NO), mask=validity(f,source);
    if (!color || !depth || !valid || !mask) { CHECK(NO); return; }
    MCMTLFarShadeSource src={source.third,mask,source.depth,YES}; MCMTLFarShadeTargets dst={color,depth,valid};
    MCMTLFarShadeMaterials mat={.atlas=f.atlas,.lightmap=f.lightmap,.atlasSampler=f.atlasSampler,.lightSampler=f.lightSampler};
    void (^reject)(MCMTLFarShadeSource,MCMTLFarShadeTargets,MCMTLFarShadeMaterials,const MCMTLFarShadeUniforms *)=
        ^(MCMTLFarShadeSource s,MCMTLFarShadeTargets t,MCMTLFarShadeMaterials m,const MCMTLFarShadeUniforms *u) {
        NSError *error=nil; id<MTLCommandBuffer> cb=[f.queue commandBuffer];
        BOOL accepted=[program encodeToCommandBuffer:cb geometry:geometry source:s targets:t uniforms:u materials:m error:&error];
        CHECK(!accepted && error && cb.status==MTLCommandBufferStatusNotEnqueued);
    };
    reject(src,dst,mat,NULL);
    // Use raw bits so compiler NaN assumptions cannot sanitize the negative inputs.
    uint32_t nan=0x7fc00000u, infinity=0x7f800000u;
    MCMTLFarShadeUniforms u=f->uniforms; memcpy(u.projection,&nan,4); reject(src,dst,mat,&u);
    for (unsigned offset=12;offset<=36;offset+=4) {
        u=f->uniforms; memcpy(u.globals+offset,&infinity,4); reject(src,dst,mat,&u);
    }
    u=f->uniforms; memcpy(u.fog+44,&nan,4); reject(src,dst,mat,&u);
    u=f->uniforms; memcpy(u.terrain+60,&infinity,4); reject(src,dst,mat,&u);
    u=f->uniforms; memset(u.terrain+64,0,4); reject(src,dst,mat,&u);
    int32_t negative=-1; u=f->uniforms; memcpy(u.terrain+68,&negative,4); reject(src,dst,mat,&u);
    // Same-format alias, independent format errors, and atlas/output overlap.
    MCMTLFarShadeSource alias=src; alias.validity=valid; reject(alias,dst,mat,&f->uniforms);
    alias=src; alias.depth=depth; reject(alias,dst,mat,&f->uniforms);
    MCMTLFarShadeSource wrongIDs=src; wrongIDs.ids=color; reject(wrongIDs,dst,mat,&f->uniforms);
    MCMTLFarShadeSource wrongDepth=src;
    id<MTLTexture> d32=texture(device,MTLPixelFormatDepth32Float,NO);
    id<MTLTexture> wrongSize=sizedTexture(device,MTLPixelFormatR32Float,W-1,H,YES);
    for (unsigned exact=0;exact<2;++exact) {
        wrongDepth=src; wrongDepth.exactCoverage=exact;
        wrongDepth.depth=nil; reject(wrongDepth,dst,mat,&f->uniforms);
        wrongDepth.depth=d32; reject(wrongDepth,dst,mat,&f->uniforms);
        wrongDepth.depth=wrongSize; reject(wrongDepth,dst,mat,&f->uniforms);
    }
    MCMTLFarShadeTargets wrongTarget={color,d32,valid}; reject(src,wrongTarget,mat,&f->uniforms);
    MCMTLFarShadeMaterials aliasedMaterial=mat; aliasedMaterial.atlas=color; reject(src,dst,aliasedMaterial,&f->uniforms);
    id<MTLBuffer> shortCounters=[device newBufferWithLength:MCMTLFarShadeDiagnosticBytes-1 options:MTLResourceStorageModeShared];
    id<MTLBuffer> untracked=[device newBufferWithLength:MCMTLFarShadeDiagnosticBytes
        options:MTLResourceStorageModeShared|MTLResourceHazardTrackingModeUntracked];
    for (id<MTLBuffer> counters in @[shortCounters,untracked,f.lookup.directoryBuffer,f.arenas[0]]) {
        NSError *failure=nil; id<MTLCommandBuffer> cb=[f.queue commandBuffer];
        CHECK(![program encodeToCommandBuffer:cb geometry:geometry source:src targets:dst uniforms:&f->uniforms
            materials:mat diagnostics:counters error:&failure] && failure && cb.status==MTLCommandBufferStatusNotEnqueued);
    }
    NSError *error=nil;
    for (unsigned i=0;i<2;++i) {
        MTLCompileOptions *bad=nil;
        if (i) { bad=[MTLCompileOptions new]; bad.languageVersion=MTLLanguageVersion3_2; bad.preprocessorMacros=@{@"UNRECOGNIZED":@1}; }
        MCMTLFarShade *rejected=[[MCMTLFarShade alloc] initWithDevice:device blockVertex:vertexFixture(1,0) blockEntry:@"main0"
            compactVertex:vertexFixture(2,0) compactEntry:@"main0" fragment:fragmentFixture(0,0,0) fragmentEntry:@"main0"
            layer:MCMTLFarShaderSolid options:bad error:&error];
        CHECK(!rejected && error);
    }
}

static void invalidDepths(ShadeFixture *f, MCMTLFarShade *program, MCMTLFarShadeGeometry *geometry, ShadeImage *source) {
    const uint32_t *ids=(const uint32_t *)((const uint8_t *)source.bytes.bytes+2*PLANE);
    unsigned selected[5]={0}, count=0;
    uint32_t depth[W*H]; memcpy(depth,(const uint8_t *)source.bytes.bytes+PLANE,sizeof depth);
    for (unsigned k=0;k<W*H;++k) {
        if (ids[k] && count<4) selected[count++]=k;
        if (!ids[k]) selected[4]=k;
    }
    CHECK(count==4 && !ids[selected[4]]);
    const uint32_t bad[5]={0,0x7fc00000u,0x3f800001u,0xbf000000u,0x3f000000u};
    for (unsigned i=0;i<5;++i) depth[selected[i]]=bad[i];
    id<MTLTexture> changed=texture(f.owner->device,MTLPixelFormatR32Float,YES);
    [changed replaceRegion:MTLRegionMake2D(0,0,W,H) mipmapLevel:0 withBytes:depth bytesPerRow:ROW];
    ShadeImage *input=[ShadeImage new]; input.depth=changed; input.third=source.third; input.validity=source.validity;
    ShadeImage *out=shadeWithMask(f,program,geometry,input,nil,NO,YES); CHECK(out); if (!out) return;
    const uint8_t *b=out.bytes.bytes; const float *z=(const float *)(b+PLANE);
    for (unsigned i=0;i<5;++i) { unsigned k=selected[i];
        CHECK(b[2*PLANE+(k/W)*ROW+k%W]==1 && z[k]==0);
        CHECK(!b[4*k] && !b[4*k+1] && !b[4*k+2] && !b[4*k+3]);
    }
}

static void exactSpecialCases(MCMTLDevice *owner, NSArray *hardware, MCMTLFarShade *program, unsigned vary, unsigned layer) {
    for (unsigned clipped=0;clipped<2;++clipped) { @autoreleasepool {
        ShadeFixture *f=fixture(owner,layer); if (!f) return;
        matrix_float4x4 p; memcpy(&p,f->uniforms.projection,64);
        if (!clipped) {
            // Both layouts have 24 pixel centres exactly on the shared diagonal.
            p.columns[0].w=0; p.columns[1].y=0.0625f; p.columns[3].y=-0.75f;
            // BLOCK permits arbitrary nonplanar corners; retain its two original
            // primitives and their independent depth/fog/light interpolation.
            uint32_t *face=(uint32_t *)((uint8_t *)f.arenas[0].contents+arenaOffsets[0]+localFaces[0]*112);
            const float z[4]={4,6,5,4};
            for (unsigned k=0;k<4;++k) memcpy(face+7*k+2,z+k,4);
        } else {
            // Both quads extend beyond current X and Y bounds but fit the guard.
            p.columns[0].x=p.columns[1].y=0.25f;
            p.columns[3].x=p.columns[3].y=-2;
        }
        memcpy(f->uniforms.projection,&p,64);
        NSError *error=nil; MCMTLFarShadeGeometry *g=[program prepareLookup:f.lookup budgetBytes:1u<<20 error:&error];
        CHECK(g && !error); diagnostic(error); if (!g) return;
        for (unsigned rgss=0;rgss<2;++rgss) {
            memcpy(f->uniforms.globals+44,&rgss,4); material(f,0,255);
            ShadeImage *source=referenceCrop(f,hardware,32); CHECK(source); if (!source) return;
            if (!clipped) {
                const uint32_t *ids=(const uint32_t *)((const uint8_t *)source.bytes.bytes+2*PLANE);
                unsigned onDiagonal=0;
                for (unsigned arena=0;arena<2;++arena) for (unsigned k=0;k<24;++k)
                    onDiagonal+=ids[(12+k)*W+(4+32*arena+k)]==bases[arena]+localFaces[arena]+1;
                CHECK(onDiagonal==48);
            }
            for (unsigned mode=0;mode<=3;++mode) {
                material(f,mode,255);
                ShadeImage *fresh=mode?referenceCrop(f,hardware,32):source;
                CHECK(fresh); if (!fresh) return;
                CHECK(!memcmp((const uint8_t *)source.bytes.bytes+PLANE,(const uint8_t *)fresh.bytes.bytes+PLANE,2*PLANE));
                printf("exact case=%s crop=(32,32) ",clipped?"clippedXY":"sharedDiagonalNonplanar");
                compare(fresh,shadeWithMask(f,program,g,source,nil,NO,YES),vary,layer,rgss,mode,YES);
            }
            if (clipped && rgss==0) {
                ShadeImage *out=shade(f,program,g,source); CHECK(out); if (!out) return;
                const uint32_t *ids=(const uint32_t *)((const uint8_t *)source.bytes.bytes+2*PLANE);
                const uint8_t *m=out.bytes.bytes; unsigned rejected=0, total=0;
                for (unsigned k=0;k<W*H;++k) if (ids[k]) { ++total; rejected+=m[2*PLANE+(k/W)*ROW+k%W]==1; }
                CHECK(total>200 && rejected==total);
                printf("conservative clippedXY rejected=%u/%u (%.3f%%); exact path requires caller coverage proof\n",rejected,total,100.0*rejected/total);
            }
        }
    } }
}

static void checkDiagnosticImage(ShadeFixture *f, MCMTLFarShade *program, MCMTLFarShadeGeometry *geometry,
                                ShadeImage *source, BOOL exact, const uint32_t expected[MCMTLFarShadeDiagnosticCount]) {
    uint32_t initial[MCMTLFarShadeDiagnosticCount+4]={0};
    for (unsigned i=MCMTLFarShadeDiagnosticCount;i<MCMTLFarShadeDiagnosticCount+4;++i) initial[i]=0xdeadbeefu;
    id<MTLBuffer> counters=buffer(f.owner->device,initial,sizeof initial);
    ShadeImage *plain=shadeWithMask(f,program,geometry,source,nil,NO,exact);
    ShadeImage *counted=shadeWithDiagnostics(f,program,geometry,source,nil,NO,exact,counters);
    CHECK(plain && counted); if (!plain || !counted) return;
    // Pixel data must match exactly, including invalid output; ignore row padding.
    BOOL identical=YES;
    for (unsigned plane=0;plane<3;++plane) for (unsigned y=0;y<H;++y)
        identical&=!memcmp((const uint8_t *)plain.bytes.bytes+plane*PLANE+y*ROW,
            (const uint8_t *)counted.bytes.bytes+plane*PLANE+y*ROW,plane==2?W:ROW);
    CHECK(identical);
    const uint32_t *actual=counters.contents; unsigned total=0;
    for (unsigned i=0;i<MCMTLFarShadeDiagnosticCount;++i) {
        if (actual[i]!=expected[i]) fprintf(stderr,"diagnostic layer=%u reason=%u actual=%u expected=%u\n",program.layer,i,actual[i],expected[i]);
        CHECK(actual[i]==expected[i]); total+=actual[i];
    }
    CHECK(total==W*H);
    CHECK(!memcmp(actual+MCMTLFarShadeDiagnosticCount,initial+MCMTLFarShadeDiagnosticCount,16));
    printf("diagnostics layer=%u exact=%u total=%u geometry=%u sky=%u missing=%u uncertain=%u input=%u lookup=%u vertex=%u clip=%u degenerate=%u triangle=%u alpha=%u identical=%u\n",
        program.layer,exact,total,actual[0],actual[1],actual[2],actual[3],actual[4],actual[5],actual[6],actual[7],actual[9],actual[10],actual[14],identical);
}

static void diagnosticOutcomes(ShadeFixture *f, MCMTLFarShade *program, MCMTLFarShadeGeometry *g, ShadeImage *source) {
    const uint32_t *ids=(const uint32_t *)((const uint8_t *)source.bytes.bytes+2*PLANE);
    unsigned faces=0, selected[5]={0};
    for (unsigned k=0;k<W*H;++k) if (ids[k]) { if (faces<5) selected[faces]=k; ++faces; }
    CHECK(faces==1858); if (faces<5) return;
    uint32_t expected[MCMTLFarShadeDiagnosticCount]={0};
    expected[MCMTLFarShadeDiagnosticGeometry]=faces; expected[MCMTLFarShadeDiagnosticSky]=W*H-faces;
    checkDiagnosticImage(f,program,g,source,YES,expected);
    expected[MCMTLFarShadeDiagnosticGeometry]-=5; expected[MCMTLFarShadeDiagnosticTriangle]=5;
    checkDiagnosticImage(f,program,g,source,NO,expected);
    expected[MCMTLFarShadeDiagnosticTriangle]=0;
    uint32_t changedIDs[W*H], changedDepth[W*H]; uint8_t masks[W*H];
    memcpy(changedIDs,ids,sizeof changedIDs); memcpy(changedDepth,(const uint8_t *)source.bytes.bytes+PLANE,sizeof changedDepth);
    for (unsigned k=0;k<W*H;++k) masks[k]=ids[k]?2:3;
    masks[selected[0]]=0; masks[selected[1]]=1; changedIDs[selected[2]]=UINT32_MAX;
    changedDepth[selected[3]]=0x7fc00000u; masks[selected[4]]=7;
    ShadeImage *bad=[ShadeImage new];
    bad.third=texture(f.owner->device,MTLPixelFormatR32Uint,YES);
    bad.depth=texture(f.owner->device,MTLPixelFormatR32Float,YES);
    bad.validity=texture(f.owner->device,MTLPixelFormatR8Uint,YES);
    [bad.third replaceRegion:MTLRegionMake2D(0,0,W,H) mipmapLevel:0 withBytes:changedIDs bytesPerRow:ROW];
    [bad.depth replaceRegion:MTLRegionMake2D(0,0,W,H) mipmapLevel:0 withBytes:changedDepth bytesPerRow:ROW];
    [bad.validity replaceRegion:MTLRegionMake2D(0,0,W,H) mipmapLevel:0 withBytes:masks bytesPerRow:W];
    expected[MCMTLFarShadeDiagnosticPriorMissing]=expected[MCMTLFarShadeDiagnosticPriorUncertain]=1;
    expected[MCMTLFarShadeDiagnosticInvalidLookup]=1; expected[MCMTLFarShadeDiagnosticInvalidInput]=2;
    checkDiagnosticImage(f,program,g,bad,YES,expected);
    memset(expected,0,sizeof expected); expected[MCMTLFarShadeDiagnosticSky]=W*H-faces;
    MCMTLFarShadeUniforms saved=f->uniforms;
    const unsigned reasons[]={MCMTLFarShadeDiagnosticProjectionClip,MCMTLFarShadeDiagnosticDegenerate,MCMTLFarShadeDiagnosticVertexNonfinite};
    for (unsigned i=0;i<3;++i) {
        matrix_float4x4 p; memcpy(&p,saved.projection,64);
        if (i==0) p.columns[3].z=-100; // finite input, impossible current Z
        else if (i==1) p.columns[0].x=p.columns[3].x=0; // collapsed screen X
        else p.columns[0].x=0x1.fffffep127f; // finite UBO, overflowing original VS
        memcpy(f->uniforms.projection,&p,64); expected[reasons[i]]=faces;
        checkDiagnosticImage(f,program,g,source,YES,expected); expected[reasons[i]]=0;
    }
    f->uniforms=saved;
}

static BOOL identicalPixels(ShadeImage *a, ShadeImage *b) {
    if (!a || !b) return NO;
    for (unsigned p=0;p<3;++p) for (unsigned y=0;y<H;++y)
        if (memcmp((const uint8_t *)a.bytes.bytes+p*PLANE+y*ROW,
                   (const uint8_t *)b.bytes.bytes+p*PLANE+y*ROW,p==2?W:ROW)) return NO;
    return YES;
}
static float sampleFloat(const uint32_t *sample,unsigned word) {
    float value; memcpy(&value,sample+word,4); return value;
}
static void diagnosticSample(ShadeFixture *f, MCMTLFarShade *program, MCMTLFarShadeGeometry *g, ShadeImage *source) {
    CHECK(MCMTLFarShadeDiagnosticBytes==64 && MCMTLFarShadeDiagnosticFullBytes==384 &&
        MCMTLFarShadeDiagnosticSampleOffsetBytes==64 && MCMTLFarShadeDiagnosticSampleWords==80);
    uint32_t zeros[96]={0};
    id<MTLBuffer> small=buffer(f.owner->device,zeros,64),full=buffer(f.owner->device,zeros,384);
    ShadeImage *plain=shadeWithMask(f,program,g,source,nil,NO,YES);
    ShadeImage *a=shadeWithDiagnostics(f,program,g,source,nil,NO,YES,small);
    ShadeImage *b=shadeWithDiagnostics(f,program,g,source,nil,NO,YES,full);
    CHECK(identicalPixels(plain,a) && identicalPixels(a,b));
    CHECK(!memcmp(small.contents,full.contents,64));
    CHECK(!memcmp((uint8_t *)full.contents+64,zeros,320)); // no reason 9, no claim

    MCMTLFarShadeUniforms saved=f->uniforms;
    matrix_float4x4 p; memcpy(&p,saved.projection,64);
    p.columns[0].x=p.columns[3].x=0; memcpy(f->uniforms.projection,&p,64);
    const uint32_t *ids=(const uint32_t *)((const uint8_t *)source.bytes.bytes+2*PLANE);
    for (unsigned layout=0;layout<2;++layout) {
        // Many simultaneous reason-9 invocations compete for one record, but
        // isolate each layout so both raw record formats must be exercised.
        uint8_t masks[W*H]; unsigned failuresExpected=0;
        uint32_t face=bases[layout]+localFaces[layout]+1;
        for (unsigned k=0;k<W*H;++k) { masks[k]=ids[k]==face?2:ids[k]?0:3; failuresExpected+=ids[k]==face; }
        id<MTLTexture> mask=texture(f.owner->device,MTLPixelFormatR8Uint,YES);
        [mask replaceRegion:MTLRegionMake2D(0,0,W,H) mipmapLevel:0 withBytes:masks bytesPerRow:W];
        uint32_t initial[100]={0}; for (unsigned k=96;k<100;++k) initial[k]=0xdeadbeefu;
        id<MTLBuffer> guarded=buffer(f.owner->device,initial,sizeof initial);
        memset(small.contents,0,64);
        a=shadeWithDiagnostics(f,program,g,source,mask,NO,YES,small);
        b=shadeWithDiagnostics(f,program,g,source,mask,NO,YES,guarded);
        CHECK(identicalPixels(a,b) && !memcmp(small.contents,guarded.contents,64));
        const uint32_t *c=guarded.contents,*s=c+MCMTLFarShadeDiagnosticSampleOffsetBytes/4;
        CHECK(c[MCMTLFarShadeDiagnosticDegenerate]==failuresExpected);
        CHECK(s[MCMTLFarShadeSampleClaim]==1 && s[MCMTLFarShadeSampleReason]==9);
        CHECK(s[MCMTLFarShadeSampleFace]==face && s[MCMTLFarShadeSampleTriangle]==0);
        CHECK(s[MCMTLFarShadeSampleLayout]==layout+1 && s[MCMTLFarShadeSampleArena]==layout &&
              s[MCMTLFarShadeSampleSection]==layout && s[MCMTLFarShadeSampleQuad]==localFaces[layout]);
        unsigned x=s[MCMTLFarShadeSampleX],y=s[MCMTLFarShadeSampleY];
        CHECK(x<W && y<H); if (x<W && y<H) {
            CHECK(ids[y*W+x]==face);
            CHECK(s[MCMTLFarShadeSampleCapturedDepthBits]==((const uint32_t *)((const uint8_t *)source.bytes.bytes+PLANE))[y*W+x]);
        }
        CHECK(s[MCMTLFarShadeSampleViewport]==W && s[MCMTLFarShadeSampleViewport+1]==H);
        CHECK(sampleFloat(s,MCMTLFarShadeSampleAreaBits)==0);
        CHECK(sampleFloat(s,MCMTLFarShadeSampleRoundoffBits)==16.0f*0x1p-23f*W);
        const unsigned corner[4][2]={{0,0},{1,0},{1,1},{0,1}};
        float sy[4];
        for (unsigned k=0;k<4;++k) {
            float wx=(layout?9:1)+6*corner[k][0],wy=2+12*corner[k][1];
            float clip[4]={0,1-wy/8,(layout?8:4)/16.0f,1+wx/64};
            CHECK(!memcmp(s+MCMTLFarShadeSampleCornerClipBits+4*k,clip,sizeof clip));
            sy[k]=(-clip[1]/clip[3]*0.5f+0.5f)*H;
            CHECK(sampleFloat(s,MCMTLFarShadeSampleScreenBits+2*k)==W*0.5f);
            CHECK(fabsf(sampleFloat(s,MCMTLFarShadeSampleScreenBits+2*k+1)-sy[k])<1e-4f);
        }
        const float lengths[3]={fabsf(sy[2]-sy[1]),fabsf(sy[0]-sy[2]),fabsf(sy[1]-sy[0])};
        for (unsigned k=0;k<3;++k) CHECK(fabsf(sampleFloat(s,MCMTLFarShadeSampleLengthsBits+k)-lengths[k])<1e-4f);
        unsigned words=layout?12:28;
        const uint8_t *vb=f.arenas[layout].contents;
        CHECK(!memcmp(s+MCMTLFarShadeSampleRawVertexWords,vb+arenaOffsets[layout]+localFaces[layout]*words*4,words*4));
        for (unsigned k=words;k<28;++k) CHECK(s[MCMTLFarShadeSampleRawVertexWords+k]==0);
        CHECK(!memcmp(s+MCMTLFarShadeSampleSectionWords,(const uint8_t *)f.lookup.shaderSections.contents+layout*16,16));
        CHECK(s[MCMTLFarShadeSampleFlags]==3);
        for (unsigned k=MCMTLFarShadeSampleReserved;k<80;++k) CHECK(s[k]==0);
        CHECK(!memcmp(c+96,initial+96,16));
        printf("reason9 sample layout=%u face=%u pixel=%u,%u competing=%u guarded=1\n",layout+1,face,x,y,failuresExpected);

        // A preclaimed record is immutable even with many competing failures.
        uint32_t held[80]; memcpy(held,s,sizeof held);
        memset(guarded.contents,0,64);
        b=shadeWithDiagnostics(f,program,g,source,mask,NO,YES,guarded);
        CHECK(identicalPixels(a,b) && !memcmp((uint8_t *)guarded.contents+64,held,sizeof held));
        CHECK(!memcmp((uint8_t *)guarded.contents+384,initial+96,16));
        if (!layout) {
            uint8_t below[383]; memset(below,0,sizeof below); memset(below+64,0xa5,sizeof below-64);
            id<MTLBuffer> shortSample=buffer(f.owner->device,below,sizeof below);
            b=shadeWithDiagnostics(f,program,g,source,mask,NO,YES,shortSample);
            CHECK(identicalPixels(a,b) && !memcmp(small.contents,shortSample.contents,64));
            CHECK(!memcmp((uint8_t *)shortSample.contents+64,below+64,sizeof below-64));
        }
    }
    f->uniforms=saved;
}

static void cutoutCoverage(ShadeFixture *f, NSArray *hardware, MCMTLFarShade *program, MCMTLFarShadeGeometry *geometry, ShadeImage *opaque) {
    material(f,0,0); ShadeImage *transparent=reference(f,hardware), *rejected=shade(f,program,geometry,opaque);
    if (!transparent || !rejected) { CHECK(NO); return; }
    const uint32_t *oldIDs=(const uint32_t *)((const uint8_t *)opaque.bytes.bytes+2*PLANE);
    const uint32_t *emptyIDs=(const uint32_t *)((const uint8_t *)transparent.bytes.bytes+2*PLANE);
    const uint8_t *m=rejected.bytes.bytes; unsigned discarded=0, wrong=0;
    for (unsigned y=0;y<H;++y) for (unsigned x=0;x<W;++x) { unsigned k=y*W+x;
        wrong+=emptyIDs[k]!=0;
        if (oldIDs[k]) { ++discarded; wrong+=m[2*PLANE+y*ROW+x]!=1; }
    }
    CHECK(discarded>200 && wrong==0);
    uint32_t expected[MCMTLFarShadeDiagnosticCount]={0};
    expected[MCMTLFarShadeDiagnosticAlphaDiscard]=discarded; expected[MCMTLFarShadeDiagnosticSky]=W*H-discarded;
    checkDiagnosticImage(f,program,geometry,opaque,YES,expected);
    material(f,0,255); ShadeImage *fresh=reference(f,hardware), *missing=shade(f,program,geometry,transparent);
    if (!fresh || !missing) { CHECK(NO); return; }
    const uint32_t *newIDs=(const uint32_t *)((const uint8_t *)fresh.bytes.bytes+2*PLANE); m=missing.bytes.bytes;
    unsigned reveals=0, falseRecovered=0;
    for (unsigned y=0;y<H;++y) for (unsigned x=0;x<W;++x) { unsigned k=y*W+x;
        if (newIDs[k]) { ++reveals; falseRecovered+=m[2*PLANE+y*ROW+x]!=3; }
    }
    CHECK(reveals==discarded && falseRecovered==0);
    printf("KNOWN LIMITATION: %u cutout samples reject as uncertain after alpha removal; %u newly opaque samples remain old sky IDs. Full visibility replay is required.\n",discarded,reveals);
}

/* Actual e3d81616 capture: direct clip positions preserve native raster setup.
 * Diagnostic varyings expose hardware primitive ownership/interpolation rather
 * than assuming that float-projected pixel-centre coverage matches rasterization. */
static const float thinClip[4][4]={
    {-0x1.178152p+8f,-0x1.def91ap+7f,0x1.7fbb36p-5f,0x1.d4e4ecp+9f},
    {-0x1.178152p+8f,-0x1.dc94fp+7f,0x1.7fb906p-5f,0x1.d50c78p+9f},
    {-0x1.1739d4p+8f,-0x1.dbda08p+7f,0x1.7fbf56p-5f,0x1.d49a14p+9f},
    {-0x1.1739d4p+8f,-0x1.de3e32p+7f,0x1.7fc186p-5f,0x1.d47286p+9f}
};
static const uint32_t thinRaw[28]={
    0x41200000,0x41100000,0x40800000,0xff2e492e,0x3f0c0000,0x3f380000,0x00e80004,
    0x41200000,0x41000000,0x40800000,0xff2e492e,0x3f0c0000,0x3f3a0000,0x00e80008,
    0x41200000,0x41000000,0x40a00000,0xff2e492e,0x3f0e0000,0x3f3a0000,0x00e80008,
    0x41200000,0x41100000,0x40a00000,0xff2e492e,0x3f0e0000,0x3f380000,0x00e80004
};
static double thinEdge(const double a[2],const double b[2],const double c[2]) {
    return (b[0]-a[0])*(c[1]-a[1])-(b[1]-a[1])*(c[0]-a[0]);
}
static void thinReplay(void) { @autoreleasepool {
    double screen[4][2]; const unsigned triangles[2][3]={{0,1,2},{2,3,0}};
    for (unsigned k=0;k<4;++k) {
        screen[k][0]=((double)thinClip[k][0]/thinClip[k][3]*.5+.5)*3024;
        screen[k][1]=(-(double)thinClip[k][1]/thinClip[k][3]*.5+.5)*1964;
    }
    const double center[2]={1061.5,1231.5};
    for (unsigned t=0;t<2;++t) {
        const unsigned *ix=triangles[t]; double area=thinEdge(screen[ix[0]],screen[ix[1]],screen[ix[2]]),bary[3],sum=0;
        for (unsigned k=0;k<3;++k) { bary[k]=thinEdge(screen[ix[(k+1)%3]],screen[ix[(k+2)%3]],center)/area; sum+=bary[k]/thinClip[ix[k]][3]; }
        printf("thin CPU tri=%u area=%.12g bary=(%.12g,%.12g,%.12g) perspective=(%.12g,%.12g,%.12g)\n",
            t,area,bary[0],bary[1],bary[2],bary[0]/thinClip[ix[0]][3]/sum,bary[1]/thinClip[ix[1]][3]/sum,bary[2]/thinClip[ix[2]][3]/sum);
        CHECK(area>0.006 && area<0.007);
    }
    id<MTLDevice> d=MTLCreateSystemDefaultDevice(); CHECK(d); if (!d) return;
    id<MTLCommandQueue> queue=[d newCommandQueue]; NSError *error=nil;
    NSString *source=@"#include <metal_stdlib>\nusing namespace metal;\n"
        "struct V {float4 position [[position]]; float3 w; float2 uv; uint tri [[flat]];};\n"
        "vertex V v(uint id [[vertex_id]],constant float4 *clip [[buffer(0)]],constant uint *raw [[buffer(1)]]) {\n"
        "uint ix[6]={0,1,2,2,3,0};uint k=ix[id];float3 w=0;w[id%3]=1;\n"
        "return {clip[k],w,float2(as_type<float>(raw[k*7+4]),as_type<float>(raw[k*7+5])),id/3};}\n"
        "struct R {float4 weights [[color(0)]];float4 uvDx [[color(1)]];float4 dyZ [[color(2)]];};\n"
        "fragment R f(V v [[stage_in]]) {return {float4(v.w,float(v.tri+1)),float4(v.uv,dfdx(v.uv)),float4(dfdy(v.uv),v.position.z,1)};}\n";
    MTLCompileOptions *options=[MTLCompileOptions new]; options.languageVersion=MTLLanguageVersion3_2; options.mathMode=MTLMathModeFast;
    id<MTLLibrary> lib=[d newLibraryWithSource:source options:options error:&error]; diagnostic(error); CHECK(lib); if (!lib) return;
    MTLRenderPipelineDescriptor *pd=[MTLRenderPipelineDescriptor new];
    pd.vertexFunction=[lib newFunctionWithName:@"v"]; pd.fragmentFunction=[lib newFunctionWithName:@"f"];
    for (unsigned i=0;i<3;++i) pd.colorAttachments[i].pixelFormat=MTLPixelFormatRGBA32Float;
    pd.depthAttachmentPixelFormat=MTLPixelFormatDepth32Float;
    id<MTLRenderPipelineState> pipeline=[d newRenderPipelineStateWithDescriptor:pd error:&error]; diagnostic(error); CHECK(pipeline); if (!pipeline) return;
    MTLDepthStencilDescriptor *dd=[MTLDepthStencilDescriptor new]; dd.depthCompareFunction=MTLCompareFunctionGreaterEqual; dd.depthWriteEnabled=YES;
    id<MTLDepthStencilState> ds=[d newDepthStencilStateWithDescriptor:dd];
    MTLRenderPassDescriptor *pass=[MTLRenderPassDescriptor renderPassDescriptor]; NSMutableArray *textures=[NSMutableArray array];
    for (unsigned i=0;i<3;++i) {
        id<MTLTexture> texture=sizedTexture(d,MTLPixelFormatRGBA32Float,3024,1964,NO); [textures addObject:texture];
        pass.colorAttachments[i].texture=texture; pass.colorAttachments[i].loadAction=MTLLoadActionClear; pass.colorAttachments[i].storeAction=MTLStoreActionStore;
        pass.colorAttachments[i].clearColor=MTLClearColorMake(0,0,0,0);
    }
    pass.depthAttachment.texture=sizedTexture(d,MTLPixelFormatDepth32Float,3024,1964,NO);
    pass.depthAttachment.loadAction=MTLLoadActionClear; pass.depthAttachment.storeAction=MTLStoreActionStore; pass.depthAttachment.clearDepth=0;
    for (unsigned draw=0;draw<3;++draw) {
    id<MTLCommandBuffer> cb=[queue commandBuffer]; id<MTLRenderCommandEncoder> e=[cb renderCommandEncoderWithDescriptor:pass];
    [e setRenderPipelineState:pipeline]; [e setDepthStencilState:ds]; [e setCullMode:MTLCullModeNone];
    [e setViewport:(MTLViewport){0,0,3024,1964,0,1}]; [e setScissorRect:(MTLScissorRect){1059,1229,6,6}];
    [e setVertexBytes:thinClip length:sizeof thinClip atIndex:0]; [e setVertexBytes:thinRaw length:sizeof thinRaw atIndex:1];
    [e drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:draw==2?3:0 vertexCount:draw?3:6]; [e endEncoding];
    id<MTLBuffer> result=[d newBufferWithLength:4*256*6 options:MTLResourceStorageModeShared];
    id<MTLBlitCommandEncoder> blit=[cb blitCommandEncoder];
    for (unsigned i=0;i<4;++i) [blit copyFromTexture:i<3?textures[i]:pass.depthAttachment.texture sourceSlice:0 sourceLevel:0
        sourceOrigin:MTLOriginMake(1059,1229,0) sourceSize:MTLSizeMake(6,6,1) toBuffer:result destinationOffset:i*256*6
        destinationBytesPerRow:256 destinationBytesPerImage:256*6];
    [blit endEncoding]; if (!finish(cb)) return;
    unsigned covered=0;
    for (unsigned y=0;y<6;++y) for (unsigned x=0;x<6;++x) {
        float *w=(float *)((uint8_t *)result.contents+y*256+x*16); if (!w[3]) continue; ++covered;
        float *uv=(float *)((uint8_t *)result.contents+256*6+y*256+x*16),*dy=(float *)((uint8_t *)result.contents+2*256*6+y*256+x*16);
        uint32_t *z=(uint32_t *)((uint8_t *)result.contents+3*256*6+y*256+x*4);
        printf("thin HW draw=%u xy=%u,%u tri=%g weights=(%.12g,%.12g,%.12g) uv=(%.12g,%.12g) dx=(%.12g,%.12g) dy=(%.12g,%.12g) z=%08x\n",
            draw,1059+x,1229+y,w[3]-1,w[0],w[1],w[2],uv[0],uv[1],uv[2],uv[3],dy[0],dy[1],*z);
        CHECK(1059+x==1061 && 1229+y==1231 && w[3]==2 && *z==0x3851a0f1u);
    }
    CHECK(covered==(draw==1?0:1));
    }
} }

static void thinOriginalParity(void) { @autoreleasepool {
    MCMTLDevice *owner=[MCMTLDevice new]; owner->device=MTLCreateSystemDefaultDevice();
    ShadeFixture *f=fixture(owner,0); CHECK(f); if(!f)return; id<MTLDevice> d=owner->device;
    MTLTextureDescriptor *atlas=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:2048 height:2048 mipmapped:YES];
    atlas.mipmapLevelCount=5; atlas.storageMode=MTLStorageModeShared; atlas.usage=MTLTextureUsageShaderRead;
    f.atlas=[d newTextureWithDescriptor:atlas]; CHECK(f.atlas);
    // A game-sized atlas with a limited mip chain, so huge thin-face gradients
    // cannot hide UV errors by reducing every texture read to a single texel.
    for(unsigned mip=0;mip<5;++mip) {
        unsigned n=2048u>>mip; NSMutableData *pixels=[NSMutableData dataWithLength:n*n*4]; uint8_t *v=pixels.mutableBytes;
        for(unsigned y=0;y<n;++y)for(unsigned x=0;x<n;++x){unsigned k=4*(y*n+x);v[k]=30+(x*29+y*7)%200;v[k+1]=40+(x*5+y*31)%190;v[k+2]=50+(x*17+y*13)%180;v[k+3]=255;}
        [f.atlas replaceRegion:MTLRegionMake2D(0,0,n,n) mipmapLevel:mip withBytes:v bytesPerRow:n*4];
    }
    uint8_t light[16*16*4];for(unsigned k=0;k<256;++k){light[4*k]=80+k%160;light[4*k+1]=140+k%110;light[4*k+2]=100+k%150;light[4*k+3]=255;}
    [f.lightmap replaceRegion:MTLRegionMake2D(0,0,16,16) mipmapLevel:0 withBytes:light bytesPerRow:64];
    int textureSize[2]={2048,2048};memcpy(f->uniforms.terrain+64,textureSize,8);
    float fog[12]={.18,.28,.38,1, 800,1800,900,1900,3000,3000,800,.25};memcpy(f->uniforms.fog,fog,48);
    int32_t section[4]={0,64,-1008,0x3f800000};id<MTLBuffer> sb=buffer(d,section,16),vb=buffer(d,thinRaw,sizeof thinRaw);
    uint32_t zero=0,item[2]={0,0},indices[6]={0,1,2,2,3,0};
    id<MTLBuffer> items=buffer(d,item,8),quad=buffer(d,&zero,4),index=buffer(d,indices,24);
    uint32_t one=1;id<MTLBuffer> directory=buffer(d,&one,4),tail=buffer(d,&zero,4);
    uint32_t arenas[64*4]={0,1,1,0};
    MTLCompileOptions *options=[MTLCompileOptions new];options.languageVersion=MTLLanguageVersion3_2;options.mathMode=MTLMathModeFast;
    // Float and half varying forms; runtime nearest/RGSS. Exactly this captured
    // face, native viewport and canonical source guard, no general scene matrix.
    for(unsigned vary=0;vary<=2;vary+=2) { @autoreleasepool {
        NSError *error=nil;NSString *vs=vertexFixture(1,vary),*fs=fragmentFixture(vary,0,0);
        MCMTLFarShaderSource *original=mcmtl_farshader_rewrite(vs,fs,@"main0",@"main0",1,0,&error);CHECK(original);if(!original)return;
        NSString *rvs=replace(original.vertex,@"constant Projection&",@"constant float4 *testClip [[buffer(16)]], constant Projection&");
        rvs=replace(rvs,@"    return out;",@"    out.gl_Position=testClip[mcmtl_vid&3u];\n    return out;");
        id<MTLLibrary> vl=[d newLibraryWithSource:rvs options:options error:&error];diagnostic(error);CHECK(vl);if(!vl)return;
        id<MTLLibrary> fl=[d newLibraryWithSource:original.fragment options:options error:&error];diagnostic(error);CHECK(fl);if(!fl)return;
        NSString *generated=mcmtl_farshade_source(vs,@"main0",vertexFixture(2,vary),@"main0",fs,@"main0",0,&error);
        // Test-only post-VS injection uses the identical captured clip bytes in
        // both pipelines. All original light/fog/fragment formulas stay intact.
        generated=replace(generated,@"sourceDepth [[texture(5)]]",@"sourceDepth [[texture(5)]], constant float4 *testClip [[buffer(16)]]");
        generated=replace(generated,@"  float2 screen[4];",@"  for(uint k=0;k<4u;k++) corners[k].clip=testClip[k];\n  float2 screen[4];");
        id<MTLLibrary> sl=[d newLibraryWithSource:generated options:options error:&error];diagnostic(error);CHECK(sl);if(!sl)return;
        MTLRenderPipelineDescriptor *pd=[MTLRenderPipelineDescriptor new];pd.vertexFunction=[vl newFunctionWithName:@"main0"];pd.fragmentFunction=[fl newFunctionWithName:@"main0"];
        pd.colorAttachments[0].pixelFormat=MTLPixelFormatRGBA8Unorm;pd.colorAttachments[1].pixelFormat=MTLPixelFormatR32Uint;pd.depthAttachmentPixelFormat=MTLPixelFormatDepth32Float;
        id<MTLRenderPipelineState> reference=[d newRenderPipelineStateWithDescriptor:pd error:&error];diagnostic(error);CHECK(reference);if(!reference)return;
        id<MTLFunction> sf=[sl newFunctionWithName:@"farshade_fragment_diagnostics"];
        pd.vertexFunction=[sl newFunctionWithName:@"farshade_vertex"];pd.fragmentFunction=sf;pd.depthAttachmentPixelFormat=MTLPixelFormatInvalid;
        pd.colorAttachments[1].pixelFormat=MTLPixelFormatR32Float;pd.colorAttachments[2].pixelFormat=MTLPixelFormatR8Uint;
        id<MTLRenderPipelineState> shade=[d newRenderPipelineStateWithDescriptor:pd error:&error];diagnostic(error);CHECK(shade);if(!shade)return;
        id<MTLArgumentEncoder> ae=[sf newArgumentEncoderWithBufferIndex:7];id<MTLBuffer> arguments=[d newBufferWithLength:ae.encodedLength options:MTLResourceStorageModeShared];
        [ae setArgumentBuffer:arguments offset:0];for(unsigned i=0;i<64;++i)[ae setBuffer:i?nil:vb offset:0 atIndex:i];
        MTLDepthStencilDescriptor *dd=[MTLDepthStencilDescriptor new];dd.depthWriteEnabled=YES;dd.depthCompareFunction=MTLCompareFunctionGreaterEqual;
        id<MTLDepthStencilState> depthState=[d newDepthStencilStateWithDescriptor:dd];
        for(unsigned guard=0;guard<2;++guard) { @autoreleasepool {
            unsigned width=guard?3780:3024,height=guard?2456:1964,gx=guard?378:0,gy=guard?246:0;
            float sourceClip[4][4];memcpy(sourceClip,thinClip,sizeof sourceClip);
            for(unsigned k=0;k<4;++k){sourceClip[k][0]=(float)((double)thinClip[k][0]*3024/width);sourceClip[k][1]=(float)((double)thinClip[k][1]*1964/height);}
            id<MTLTexture> hc=sizedTexture(d,MTLPixelFormatRGBA8Unorm,width,height,NO),hd=sizedTexture(d,MTLPixelFormatDepth32Float,width,height,NO),hi=sizedTexture(d,MTLPixelFormatR32Uint,width,height,NO);
            NSArray *inputs=@[sizedTexture(d,MTLPixelFormatRGBA8Unorm,3024,1964,NO),sizedTexture(d,MTLPixelFormatR32Float,3024,1964,NO),sizedTexture(d,MTLPixelFormatR32Uint,3024,1964,NO),sizedTexture(d,MTLPixelFormatR8Uint,3024,1964,NO)];
            MCMTLFarWarpTargets in={inputs[0],inputs[1],inputs[2],inputs[3]};
            NSArray *outputs=@[sizedTexture(d,MTLPixelFormatRGBA8Unorm,3024,1964,NO),sizedTexture(d,MTLPixelFormatR32Float,3024,1964,NO),sizedTexture(d,MTLPixelFormatR8Uint,3024,1964,NO)];
            MCMTLFarWarpTransform transform={.motion=MCMTLFarWarpMotionPureRotation};
            transform.currentToSourceClip[0]=(float)(3024.0/width);transform.currentToSourceClip[5]=(float)(1964.0/height);
            transform.sourceToCurrentClip[0]=(float)(width/3024.0);transform.sourceToCurrentClip[5]=(float)(height/1964.0);
            transform.currentToSourceClip[10]=transform.currentToSourceClip[15]=transform.sourceToCurrentClip[10]=transform.sourceToCurrentClip[15]=1;
            for(unsigned rgss=0;rgss<2;++rgss) {
                memcpy(f->uniforms.globals+44,&rgss,4);id<MTLCommandBuffer> cb=[f.queue commandBuffer];
                MTLRenderPassDescriptor *rp=[MTLRenderPassDescriptor renderPassDescriptor];rp.colorAttachments[0].texture=hc;rp.colorAttachments[1].texture=hi;
                for(unsigned j=0;j<2;++j){rp.colorAttachments[j].loadAction=MTLLoadActionClear;rp.colorAttachments[j].storeAction=MTLStoreActionStore;rp.colorAttachments[j].clearColor=MTLClearColorMake(0,0,0,0);}
                rp.depthAttachment.texture=hd;rp.depthAttachment.loadAction=MTLLoadActionClear;rp.depthAttachment.storeAction=MTLStoreActionStore;rp.depthAttachment.clearDepth=0;
                id<MTLRenderCommandEncoder> e=[cb renderCommandEncoderWithDescriptor:rp];[e setRenderPipelineState:reference];[e setDepthStencilState:depthState];[e setCullMode:MTLCullModeNone];
                [e setViewport:(MTLViewport){0,0,width,height,0,1}];[e setScissorRect:(MTLScissorRect){1059+gx,1229+gy,6,6}];
                const void *ubo[]={f->uniforms.projection,f->uniforms.globals,f->uniforms.fog,f->uniforms.terrain};unsigned slots[]={0,1,2,5},sizes[]={64,48,48,80};
                for(unsigned j=0;j<4;++j){[e setVertexBytes:ubo[j] length:sizes[j] atIndex:slots[j]];[e setFragmentBytes:ubo[j] length:sizes[j] atIndex:slots[j]];}
                [e setVertexBuffer:vb offset:0 atIndex:30];[e setVertexBuffer:sb offset:0 atIndex:29];[e setVertexBuffer:quad offset:0 atIndex:28];[e setVertexBuffer:items offset:0 atIndex:27];
                [e setVertexBytes:&zero length:4 atIndex:6];[e setVertexBytes:sourceClip length:sizeof sourceClip atIndex:16];[e setVertexTexture:f.lightmap atIndex:2];[e setVertexSamplerState:f.lightSampler atIndex:2];
                [e setFragmentTexture:f.atlas atIndex:0];[e setFragmentSamplerState:f.atlasSampler atIndex:0];
                [e drawIndexedPrimitives:MTLPrimitiveTypeTriangle indexCount:6 indexType:MTLIndexTypeUInt32 indexBuffer:index indexBufferOffset:0];[e endEncoding];
                BOOL warped=[f.warp encodeToCommandBuffer:cb source:(MCMTLFarWarpSource){hc,hd,hi} targets:in transform:&transform error:&error];CHECK(warped);diagnostic(error);if(!warped)return;
                rp=[MTLRenderPassDescriptor renderPassDescriptor];for(unsigned j=0;j<3;++j){rp.colorAttachments[j].texture=outputs[j];rp.colorAttachments[j].loadAction=MTLLoadActionDontCare;rp.colorAttachments[j].storeAction=MTLStoreActionStore;}
                e=[cb renderCommandEncoderWithDescriptor:rp];[e setRenderPipelineState:shade];[e setCullMode:MTLCullModeNone];
                [e setViewport:(MTLViewport){0,0,3024,1964,0,1}];[e setScissorRect:(MTLScissorRect){1059,1229,6,6}];
                for(unsigned j=0;j<4;++j)[e setFragmentBytes:ubo[j] length:sizes[j] atIndex:slots[j]];
                [e setFragmentBuffer:arguments offset:0 atIndex:7];[e setFragmentBytes:arenas length:sizeof arenas atIndex:8];[e setFragmentBuffer:directory offset:0 atIndex:9];[e setFragmentBuffer:tail offset:0 atIndex:10];[e setFragmentBuffer:sb offset:0 atIndex:11];
                uint32_t params[8]={1,0,1,1,3024,1964,0,3};[e setFragmentBytes:params length:32 atIndex:12];
                uint32_t empty[96]={0};id<MTLBuffer> diagnostics=buffer(d,empty,sizeof empty);[e setFragmentBuffer:diagnostics offset:0 atIndex:13];
                [e setFragmentBytes:thinClip length:sizeof thinClip atIndex:16];[e useResource:vb usage:MTLResourceUsageRead stages:MTLRenderStageFragment];
                [e setFragmentTexture:f.atlas atIndex:0];[e setFragmentSamplerState:f.atlasSampler atIndex:0];[e setFragmentTexture:f.lightmap atIndex:2];[e setFragmentSamplerState:f.lightSampler atIndex:2];
                [e setFragmentTexture:in.visibilityID atIndex:3];[e setFragmentTexture:in.validity atIndex:4];[e setFragmentTexture:in.depth atIndex:5];[e drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];[e endEncoding];
                NSArray *planes=@[inputs[0],inputs[1],inputs[2],outputs[0],outputs[1],outputs[2]];
                id<MTLBuffer> result=[d newBufferWithLength:6*256*6 options:MTLResourceStorageModeShared];id<MTLBlitCommandEncoder> blit=[cb blitCommandEncoder];
                for(unsigned j=0;j<6;++j)[blit copyFromTexture:planes[j] sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(1059,1229,0) sourceSize:MTLSizeMake(6,6,1) toBuffer:result destinationOffset:j*256*6 destinationBytesPerRow:256 destinationBytesPerImage:256*6];
                [blit endEncoding];if(!finish(cb))return;
                const uint8_t *r=result.contents;unsigned covered=0,holes=0,maxDelta=0,badColor=0,badDepth=0;
                for(unsigned y=0;y<6;++y)for(unsigned x=0;x<6;++x){unsigned k=y*256+4*x;uint32_t id;memcpy(&id,r+2*256*6+k,4);uint8_t mask=r[5*256*6+y*256+x];
                    if(id){++covered;CHECK(x==2 && y==2);holes+=mask!=2;for(unsigned j=0;j<4;++j){unsigned delta=abs((int)r[k+j]-(int)r[3*256*6+k+j]);maxDelta=MAX(maxDelta,delta);badColor+=delta>1;}badDepth+=memcmp(r+256*6+k,r+4*256*6+k,4)!=0;}
                    else CHECK(mask==3);
                }
                const uint32_t *bins=diagnostics.contents;
                printf("thin original vary=%u guard=%u rgss=%u covered=%u rejected=%u maxLSB=%u colorBad=%u depthBitsBad=%u reason9=%u\n",vary,guard,rgss,covered,holes,maxDelta,badColor,badDepth,bins[9]);
                CHECK(covered==1 && !holes && !badColor && !badDepth && bins[0]==1 && bins[1]==35 && !bins[9]);
            }
        } }
    } }
} }

static void run(void) { @autoreleasepool {
    MCMTLDevice *owner=[MCMTLDevice new]; owner->device=MTLCreateSystemDefaultDevice(); CHECK(owner->device); if (!owner->device) return;
    MTLCompileOptions *opts=[MTLCompileOptions new]; opts.languageVersion=MTLLanguageVersion3_2; opts.mathMode=MTLMathModeFast;
    for (unsigned vary=0;vary<3;++vary) for (unsigned layer=0;layer<2;++layer) { @autoreleasepool {
        ShadeFixture *f=fixture(owner,layer); if (!f) continue;
        MCMTLFarShader *block=referencePipeline(owner,1,vary,layer,opts), *compact=referencePipeline(owner,2,vary,layer,opts);
        if (!block || !compact) continue; NSArray *hardware=@[block,compact]; NSError *error=nil;
        MCMTLFarShade *program=[[MCMTLFarShade alloc] initWithBlockProgram:block compactProgram:compact error:&error];
        CHECK(program && !error); diagnostic(error); if (!program) continue;
        CHECK([[MCMTLFarShade alloc] initWithBlockProgram:compact compactProgram:block error:&error]==nil && error);
        CHECK([[MCMTLFarShade alloc] initWithBlockProgram:block compactProgram:block error:&error]==nil && error);
        MCMTLFarShadeGeometry *geometry=[program prepareLookup:f.lookup budgetBytes:1u<<20 error:&error];
        CHECK(geometry && !error); diagnostic(error); if (!geometry) continue;
        for (unsigned rgss=0;rgss<2;++rgss) {
            memcpy(f->uniforms.globals+44,&rgss,4); material(f,0,255);
            ShadeImage *source=reference(f,hardware); if (!source) continue;
            compare(source,shade(f,program,geometry,source),vary,layer,rgss,0,NO);
            compare(source,shadeWithMask(f,program,geometry,source,nil,NO,YES),vary,layer,rgss,0,YES);
            if (vary==0 && rgss==0) diagnosticOutcomes(f,program,geometry,source);
            if (vary==0 && layer==0 && rgss==0) {
                invalidMasks(f,program,geometry,source);
                negativeInputs(f,program,geometry,source);
                invalidDepths(f,program,geometry,source);
                diagnosticSample(f,program,geometry,source);
            }
            for (unsigned mode=1;mode<=3;++mode) {
                material(f,mode,255); ShadeImage *fresh=reference(f,hardware);
                if (!fresh) continue;
                CHECK(memcmp((const uint8_t *)source.bytes.bytes+2*PLANE,(const uint8_t *)fresh.bytes.bytes+2*PLANE,PLANE)==0);
                CHECK(memcmp(source.bytes.bytes,fresh.bytes.bytes,PLANE)!=0); // truly changed material, stable IDs
                compare(fresh,shade(f,program,geometry,source),vary,layer,rgss,mode,NO);
                compare(fresh,shadeWithMask(f,program,geometry,source,nil,NO,YES),vary,layer,rgss,mode,YES);
            }
            if (vary==0 && layer==1 && rgss==1) cutoutCoverage(f,hardware,program,geometry,source);
        }
        exactSpecialCases(owner,hardware,program,vary,layer);
    } }
} }

int main(int argc,const char *argv[]) { @autoreleasepool {
    dispatch_group_t group=dispatch_group_create();
    BOOL thin=argc==2 && !strcmp(argv[1],"--thin-replay");
    dispatch_group_async(group,dispatch_get_global_queue(QOS_CLASS_USER_INITIATED,0),^{ if(thin){thinReplay();thinOriginalParity();}else run(); });
    if (dispatch_group_wait(group,dispatch_time(DISPATCH_TIME_NOW,120*NSEC_PER_SEC))) {
        fprintf(stderr,"FAIL: bounded shade test exceeded 120 seconds\n"); return 1;
    }
    printf("farimage_shade_test: %u checks, %u failures\n",checks,failures); return failures?1:0;
} }
