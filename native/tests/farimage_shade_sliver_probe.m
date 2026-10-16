/* Standalone diagnostic probe; deliberately NOT registered as a core test.
 * Bounded reproduction, not an admission-policy change.
 * Reuse the independent ORIGINAL hardware stages, mesh/light/atlas fixtures and
 * worker setup; do not run the included broad test. Only this file is owned here.
 * Run under tools/gpulock.py functional with MTL_DEBUG_LAYER=1.
 * A 3024x64 strip reproduces the 3024x1964 viewport-based area threshold without
 * allocating/rasterizing a full game image. No guard crop or camera difference.
 * Original hardware renders each triangle separately as an ownership oracle.
 * Color comparisons retain the parent test's 1-LSB tolerance; depth is bit-exact.
 * Pathological-case mismatch counts are observations, not silently widened
 * tolerances. Assertions cover the normal/control parity and reproduced blocker.
 * KNOWN BASELINE (Apple M5, Metal validation, 2026-10-09): 633 checks, 7 failures,
 * exit 1. Nearest sampling exceeds the unchanged 1-LSB tolerance on 21 normal
 * BLOCK channels plus one compact channel repeated across six cases: 27 channels
 * total, maximum 2 LSB. RGSS has no color failures; accepted depth is bit-exact.
 * The 12 comparisons reproduce 3,504 reason-9 rejections. Keep these failures
 * visible; this receipt is not a passing parity gate or proof about a game face.
 */
#define main farimage_shade_full_test_unused_main
#include "farimage_shade_test.m"
#undef main
#include <float.h>

enum { SW=3024, SH=64, SROW=(SW*4+255)&~255, SPLANE=SROW*SH };
typedef struct { const char *name; float xy[4][2]; BOOL blocked; } SliverCase;
static const SliverCase cases[]={
    {"normal_nonplanar", {{4,8},{28,8},{28,56},{4,56}}, NO},
    {"thin_0.02px", {{4,20.49f},{28,20.49f},{28,20.51f},{4,20.51f}}, NO},
    {"thin_0.004px", {{4,20.498f},{28,20.498f},{28,20.502f},{4,20.502f}}, YES},
    {"first_nearly_collinear", {{4,8},{16.004f,32},{28,56},{4,56}}, YES},
    {"first_collapsed", {{4,8},{4,8},{28,56},{4,56}}, YES},
    {"second_collapsed", {{4,8},{28,8},{28,56},{4,8}}, YES},
};
static unsigned comparisons, observedColorBad, observedDepthBad, observedRejected;

static id<MTLTexture> sliverTexture(ShadeFixture *f, MTLPixelFormat format, BOOL shared) {
    return sizedTexture(f.owner->device,format,SW,SH,shared);
}
static BOOL sliverReadback(ShadeFixture *f, ShadeImage *out, id<MTLCommandBuffer> cb) {
    id<MTLBuffer> bytes=[f.owner->device newBufferWithLength:3*SPLANE options:MTLResourceStorageModeShared];
    CHECK(bytes); if (!bytes) return NO;
    id<MTLBlitCommandEncoder> e=[cb blitCommandEncoder]; CHECK(e); if (!e) return NO;
    NSArray *planes=@[out.color,out.depth,out.third];
    for (unsigned i=0;i<3;++i) [e copyFromTexture:planes[i] sourceSlice:0 sourceLevel:0
        sourceOrigin:MTLOriginMake(0,0,0) sourceSize:MTLSizeMake(SW,SH,1) toBuffer:bytes
        destinationOffset:i*SPLANE destinationBytesPerRow:SROW destinationBytesPerImage:SPLANE];
    [e endEncoding]; if (!finish(cb)) return NO;
    out.bytes=[NSData dataWithBytes:bytes.contents length:3*SPLANE]; return YES;
}

/* triangles: -1 = original 012,230 draw; 0/1 = hardware isolation oracle.
 * All IDs, colors, clipping, interpolation, derivatives and depths are produced
 * by the original admitted hardware stages, never by CPU/shade reconstruction. */
static ShadeImage *sliverHardware(ShadeFixture *f, NSArray<MCMTLFarShader *> *hardware, int triangle) {
    ShadeImage *out=[ShadeImage new];
    out.color=sliverTexture(f,MTLPixelFormatRGBA8Unorm,NO);
    out.depth=sliverTexture(f,MTLPixelFormatDepth32Float,NO);
    out.third=sliverTexture(f,MTLPixelFormatR32Uint,NO);
    if (!out.color || !out.depth || !out.third) return nil;
    MTLRenderPassDescriptor *pass=[MTLRenderPassDescriptor renderPassDescriptor];
    pass.colorAttachments[0].texture=out.color; pass.colorAttachments[1].texture=out.third;
    for (unsigned i=0;i<2;++i) {
        pass.colorAttachments[i].loadAction=MTLLoadActionClear;
        pass.colorAttachments[i].storeAction=MTLStoreActionStore;
        pass.colorAttachments[i].clearColor=MTLClearColorMake(0,0,0,0);
    }
    pass.depthAttachment.texture=out.depth; pass.depthAttachment.clearDepth=0;
    pass.depthAttachment.loadAction=MTLLoadActionClear; pass.depthAttachment.storeAction=MTLStoreActionStore;
    id<MTLCommandBuffer> cb=[f.queue commandBuffer];
    id<MTLRenderCommandEncoder> e=[cb renderCommandEncoderWithDescriptor:pass]; CHECK(e); if (!e) return nil;
    const void *ubos[]={f->uniforms.projection,f->uniforms.globals,f->uniforms.fog,f->uniforms.terrain};
    const unsigned sizes[]={64,48,48,80}, slots[]={0,1,2,5};
    for (unsigned i=0;i<4;++i) {
        [e setVertexBytes:ubos[i] length:sizes[i] atIndex:slots[i]];
        [e setFragmentBytes:ubos[i] length:sizes[i] atIndex:slots[i]];
    }
    [e setViewport:(MTLViewport){0,0,SW,SH,0,1}]; [e setScissorRect:(MTLScissorRect){0,0,SW,SH}];
    [e setVertexTexture:f.lightmap atIndex:2]; [e setVertexSamplerState:f.lightSampler atIndex:2];
    [e setFragmentTexture:f.atlas atIndex:0]; [e setFragmentSamplerState:f.atlasSampler atIndex:0];
    [e setVertexBuffer:f.sections offset:0 atIndex:29]; [e setVertexBuffer:f.quads offset:0 atIndex:28];
    for (unsigned a=0;a<2;++a) {
        MCMTLFarShader *p=hardware[a]; [e setRenderPipelineState:p.pipelineState]; [e setDepthStencilState:p.depthStencilState];
        [e setCullMode:p.raster.cullMode]; [e setFrontFacingWinding:p.raster.frontFacing];
        [e setVertexBuffer:f.arenas[a] offset:arenaOffsets[a] atIndex:30];
        [e setVertexBuffer:f.items[a] offset:0 atIndex:27]; [e setVertexBytes:&bases[a] length:4 atIndex:6];
        [e drawIndexedPrimitives:MTLPrimitiveTypeTriangle indexCount:triangle<0?6:3
            indexType:MTLIndexTypeUInt32 indexBuffer:f.indices indexBufferOffset:triangle==1?12:0];
    }
    [e endEncoding]; if (!sliverReadback(f,out,cb)) return nil;
    // Exact source contract is R32Float. Preserve captured D32 bytes verbatim;
    // this explicit test-only readback also supplies the independent ID mask.
    out.depth=sliverTexture(f,MTLPixelFormatR32Float,YES);
    out.validity=sliverTexture(f,MTLPixelFormatR8Uint,YES);
    if (!out.depth || !out.validity) return nil;
    [out.depth replaceRegion:MTLRegionMake2D(0,0,SW,SH) mipmapLevel:0
        withBytes:(const uint8_t *)out.bytes.bytes+SPLANE bytesPerRow:SROW];
    NSMutableData *m=[NSMutableData dataWithLength:SW*SH]; uint8_t *mask=m.mutableBytes;
    for (unsigned y=0;y<SH;++y) {
        const uint32_t *ids=(const uint32_t *)((const uint8_t *)out.bytes.bytes+2*SPLANE+y*SROW);
        for (unsigned x=0;x<SW;++x) mask[y*SW+x]=ids[x]?2:3;
    }
    [out.validity replaceRegion:MTLRegionMake2D(0,0,SW,SH) mipmapLevel:0 withBytes:mask bytesPerRow:SW];
    return out;
}

static ShadeImage *sliverShade(ShadeFixture *f, MCMTLFarShade *program, MCMTLFarShadeGeometry *g,
                              ShadeImage *source, id<MTLBuffer> counters) {
    ShadeImage *out=[ShadeImage new]; out.color=sliverTexture(f,MTLPixelFormatRGBA8Unorm,NO);
    out.depth=sliverTexture(f,MTLPixelFormatR32Float,NO); out.third=sliverTexture(f,MTLPixelFormatR8Uint,NO);
    if (!out.color || !out.depth || !out.third) return nil;
    id<MTLCommandBuffer> cb=[f.queue commandBuffer];
    id<MTLBlitCommandEncoder> clear=[cb blitCommandEncoder]; CHECK(clear); if (!clear) return nil;
    [clear fillBuffer:counters range:NSMakeRange(0,MCMTLFarShadeDiagnosticBytes) value:0]; [clear endEncoding];
    MCMTLFarShadeMaterials mat={.atlas=f.atlas,.lightmap=f.lightmap,.atlasSampler=f.atlasSampler,.lightSampler=f.lightSampler};
    NSError *error=nil;
    BOOL ok=[program encodeToCommandBuffer:cb geometry:g
        source:(MCMTLFarShadeSource){source.third,source.validity,source.depth,YES}
        targets:(MCMTLFarShadeTargets){out.color,out.depth,out.third} uniforms:&f->uniforms
        materials:mat diagnostics:counters error:&error];
    CHECK(ok && !error); diagnostic(error); if (!ok) return nil;
    return sliverReadback(f,out,cb)?out:nil;
}

static void sliverCompare(unsigned ci, unsigned rgss, ShadeImage *source, ShadeImage *tri0,
                          ShadeImage *tri1, ShadeImage *got, id<MTLBuffer> counters) {
    if (!source || !tri0 || !tri1 || !got) { CHECK(NO); return; }
    unsigned covered[2]={0}, accepted[2]={0}, rejected[2]={0}, colorBad[2]={0}, depthBad[2]={0};
    unsigned triCount[2][2]={{0}}, overlap[2]={0}, rejectedFrom[2]={0};
    unsigned maxLSB[2]={0}, different[2]={0}, colorPixels[2]={0}, maskBad=0, oracleBad=0, sky=0;
    for (unsigned y=0;y<SH;++y) {
        const uint8_t *a=(const uint8_t *)source.bytes.bytes+y*SROW, *b=(const uint8_t *)got.bytes.bytes+y*SROW;
        const uint8_t *t[2]={(const uint8_t *)tri0.bytes.bytes+y*SROW,(const uint8_t *)tri1.bytes.bytes+y*SROW};
        const uint32_t *ids=(const uint32_t *)(a+2*SPLANE);
        for (unsigned x=0;x<SW;++x) {
            unsigned mask=b[2*SPLANE+x];
            if (!ids[x]) { ++sky; maskBad+=mask!=3; continue; }
            unsigned arena=ids[x]==bases[0]+localFaces[0]+1?0:1;
            if (ids[x]!=bases[arena]+localFaces[arena]+1) ++oracleBad;
            ++covered[arena]; unsigned owners=0, ownerTriangle=0;
            for (unsigned j=0;j<2;++j) if (((const uint32_t *)(t[j]+2*SPLANE))[x]==ids[x]) {
                ++owners; ownerTriangle=j; ++triCount[arena][j]; if (mask==1 && arena==0) ++rejectedFrom[j];
            }
            overlap[arena]+=owners==2; oracleBad+=owners==0;
            if (owners==1) oracleBad+=memcmp(a+4*x,t[ownerTriangle]+4*x,4)!=0 ||
                memcmp(a+SPLANE+4*x,t[ownerTriangle]+SPLANE+4*x,4)!=0;
            if (mask==1) {
                ++rejected[arena];
                maskBad+=((const uint32_t *)(b+SPLANE))[x]!=0 || ((const uint32_t *)b)[x]!=0;
                continue;
            }
            maskBad+=mask!=2; ++accepted[arena]; unsigned badPixel=0;
            for (unsigned c=0;c<4;++c) {
                unsigned delta=abs((int)a[4*x+c]-(int)b[4*x+c]);
                maxLSB[arena]=MAX(maxLSB[arena],delta); different[arena]+=delta!=0;
                colorBad[arena]+=delta>1; badPixel|=delta>1;
            }
            colorPixels[arena]+=badPixel;
            if (ci==0 && badPixel && colorPixels[arena]<=4) {
                printf("  colorMismatch arena=%s xy=(%u,%u) hardwareTriangle=%u hardwareRGBA=(%u,%u,%u,%u) shadeRGBA=(%u,%u,%u,%u)\n",
                    arena?"compact":"BLOCK",x,y,ownerTriangle,a[4*x],a[4*x+1],a[4*x+2],a[4*x+3],
                    b[4*x],b[4*x+1],b[4*x+2],b[4*x+3]);
            }
            depthBad[arena]+=memcmp(a+SPLANE+4*x,b+SPLANE+4*x,4)!=0;
        }
    }
    const uint32_t *r=counters.contents; unsigned total=0, rejectReasons=0;
    for (unsigned i=0;i<MCMTLFarShadeDiagnosticCount;++i) { total+=r[i]; if (i>=2) rejectReasons+=r[i]; }
    CHECK(total==SW*SH && r[0]==accepted[0]+accepted[1] && r[1]==sky);
    CHECK(rejectReasons==rejected[0]+rejected[1] && !maskBad && !oracleBad);
    CHECK(covered[0]>0 && covered[1]>0);
    CHECK(!rejected[1] && !colorBad[1] && !depthBad[1]); // compact control
    if (ci==0) CHECK(!rejected[0] && !colorBad[0] && !depthBad[0] && triCount[0][0] && triCount[0][1]);
    if (cases[ci].blocked) CHECK(rejected[0]==covered[0] && r[MCMTLFarShadeDiagnosticDegenerate]==covered[0]);
    printf("sliver case=%s rgss=%u BLOCK covered=%u triangles=%u/%u overlap=%u accepted=%u rejected=%u rejectedFrom=%u/%u colorBadChannels=%u colorBadPixels=%u differingChannels=%u maxLSB=%u depthBitMismatch=%u; compact covered=%u rejected=%u colorBad=%u depthBits=%u\n",
        cases[ci].name,rgss,covered[0],triCount[0][0],triCount[0][1],overlap[0],accepted[0],rejected[0],rejectedFrom[0],rejectedFrom[1],
        colorBad[0],colorPixels[0],different[0],maxLSB[0],depthBad[0],covered[1],rejected[1],colorBad[1],depthBad[1]);
    printf("  reasons[0..15]=["); for (unsigned i=0;i<MCMTLFarShadeDiagnosticCount;++i) printf("%s%u",i?",":"",r[i]);
    printf("] maskBad=%u hardwareOracleBad=%u\n",maskBad,oracleBad);
    ++comparisons; observedColorBad+=colorBad[0]+colorBad[1]; observedDepthBad+=depthBad[0]+depthBad[1];
    observedRejected+=rejected[0]+rejected[1];
}

static void runSlivers(void) { @autoreleasepool {
    MCMTLDevice *owner=[MCMTLDevice new]; owner->device=MTLCreateSystemDefaultDevice(); CHECK(owner->device); if (!owner->device) return;
    printf("device=%s viewport=%ux%u exact=true vary=0 layer=SOLID roundoffFloor=%.9gpx\n",
        owner->device.name.UTF8String,SW,SH,16.0*FLT_EPSILON*SW);
    MTLCompileOptions *opts=[MTLCompileOptions new]; opts.languageVersion=MTLLanguageVersion3_2; opts.mathMode=MTLMathModeFast;
    MCMTLFarShader *block=referencePipeline(owner,1,0,0,opts), *compact=referencePipeline(owner,2,0,0,opts);
    if (!block || !compact) return;
    NSArray *hardware=@[block,compact]; NSError *error=nil;
    MCMTLFarShade *program=[[MCMTLFarShade alloc] initWithBlockProgram:block compactProgram:compact error:&error];
    CHECK(program && !error); diagnostic(error); if (!program) return;
    for (unsigned ci=0;ci<sizeof cases/sizeof *cases;++ci) { @autoreleasepool {
        ShadeFixture *f=fixture(owner,0); if (!f) continue;
        matrix_float4x4 p=matrix_identity_float4x4;
        p.columns[0].x=8.0f/SW; p.columns[1].y=8.0f/SH; p.columns[2].z=0.0625f;
        p.columns[3].x=p.columns[3].y=-1;
        memcpy(f->uniforms.projection,&p,64); float screen[2]={SW,SH}; memcpy(f->uniforms.globals+32,screen,8);
        // Positions map to screen (4*x,4*y) through the ORIGINAL VS, including
        // its Y inversion. Corner colors/light UVs/greedy UVs remain nonuniform.
        uint32_t *face=(uint32_t *)((uint8_t *)f.arenas[0].contents+arenaOffsets[0]+localFaces[0]*112);
        const float z[4]={4,6,5,4};
        for (unsigned k=0;k<4;++k) {
            float pos[3]={cases[ci].xy[k][0]/4,cases[ci].xy[k][1]/4,z[k]}; memcpy(face+7*k,pos,12);
        }
        // All test-only buffer authoring ends before preparation or any GPU use.
        MCMTLFarShadeGeometry *g=[program prepareLookup:f.lookup budgetBytes:1u<<20 error:&error];
        CHECK(g && !error); diagnostic(error); if (!g) continue;
        id<MTLBuffer> counters=[owner->device newBufferWithLength:MCMTLFarShadeDiagnosticBytes options:MTLResourceStorageModeShared];
        CHECK(counters); if (!counters) continue;
        for (unsigned rgss=0;rgss<2;++rgss) { @autoreleasepool {
            memcpy(f->uniforms.globals+44,&rgss,4); material(f,0,255);
            ShadeImage *source=sliverHardware(f,hardware,-1);
            ShadeImage *tri0=sliverHardware(f,hardware,0), *tri1=sliverHardware(f,hardware,1);
            if (!source || !tri0 || !tri1) continue;
            sliverCompare(ci,rgss,source,tri0,tri1,sliverShade(f,program,g,source,counters),counters);
        } }
    } }
} }

int main(void) { @autoreleasepool {
    setvbuf(stdout,NULL,_IOLBF,0);
    dispatch_group_t group=dispatch_group_create();
    dispatch_group_async(group,dispatch_get_global_queue(QOS_CLASS_USER_INITIATED,0),^{ runSlivers(); });
    if (dispatch_group_wait(group,dispatch_time(DISPATCH_TIME_NOW,120*NSEC_PER_SEC))) {
        fprintf(stderr,"FAIL: bounded sliver diagnostic exceeded 120 seconds\n"); return 1;
    }
    CHECK(comparisons==12);
    printf("farimage_shade_sliver_probe: %u comparisons, %u rejected geometry samples, %u accepted color channels >1LSB, %u accepted depth-bit mismatches; %u checks, %u failures\n",
        comparisons,observedRejected,observedColorBad,observedDepthBad,checks,failures);
    return failures?1:0;
} }
