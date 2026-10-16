/* FFM recipe lifetime/provenance, with actual shader_create functions and pipeline metadata. */
#import "mcmetal_farprogram.h"
#import "mcmetal_farshader.h"
#include <dispatch/dispatch.h>
#include <stdio.h>
#include <string.h>
extern int32_t mcmtl_ext_far_shader_capture_enable(mcmtl_handle);
static unsigned checks, failures;
#define CHECK(x) do { ++checks; if (!(x)) { ++failures; fprintf(stderr,"FAIL %d: %s\n",__LINE__,#x); } } while (0)
#import "farimage_shader_fixture.h"

static mcmtl_handle function(mcmtl_handle device, NSString *source, uint32_t stage) {
    NSData *bytes = [source dataUsingEncoding:NSUTF8StringEncoding];
    mcmtl_shader_desc d = {.structSize=sizeof d, .stage=stage, .languageVersion=MTLLanguageVersion3_2,
        .source=bytes.bytes, .sourceLength=bytes.length, .entryPoint="main0"};
    mcmtl_handle out = 0; CHECK(mcmtl_shader_create(device, &d, &out)==MCMTL_OK); return out;
}

static void run(void) { @autoreleasepool {
    mcmtl_device_desc dd = {.structSize=sizeof dd, .framesInFlight=2};
    mcmtl_handle dev=0; CHECK(mcmtl_device_create(&dd,&dev)==MCMTL_OK); if (!dev) return;
    CHECK(mcmtl_ext_far_shader_capture_enable(dev)==MCMTL_OK);
    for (unsigned layout=1; layout<=2; ++layout) {
        mcmtl_handle v=function(dev,vertexFixture(layout,2),MCMTL_STAGE_VERTEX);
        mcmtl_handle f=function(dev,fragmentFixture(2,MCMTLFarShaderCutout,1),MCMTL_STAGE_FRAGMENT);
        // Use a real MCMTLPipeline with production functions; no mutable variant/cache state is needed by the recipe.
        MCMTLPipeline *p=[MCMTLPipeline new]; p->owner=MCMTL_DEVICE(dev);
        p->label=@"cutout_lod_terrain_cluster"; p->colorTargetCount=1; p->colorTargetFormats[0]=MTLPixelFormatRGBA8Unorm;
        p->primitiveType=MTLPrimitiveTypeTriangle; p->frontFacing=MTLWindingClockwise; p->cullMode=MTLCullModeBack;
        p->requiredVertexBufferMask=15u<<27; p->bufferMask[0]=(1<<0)|(1<<1)|(1<<2)|(1<<5);
        p->bufferMask[1]=(1<<1)|(1<<2)|(1<<5); p->textureMask[0]=p->samplerMask[0]=1<<2;
        p->textureMask[1]=p->samplerMask[1]=1;
        MTLRenderPipelineDescriptor *pd=[MTLRenderPipelineDescriptor new];
        pd.vertexFunction=MCMTL_FUNCTION(v); pd.fragmentFunction=MCMTL_FUNCTION(f);
        pd.colorAttachments[0].pixelFormat=MTLPixelFormatRGBA8Unorm;
        pd.inputPrimitiveTopology=MTLPrimitiveTopologyClassUnspecified; pd.vertexDescriptor=gameVertexDescriptor();
        p->descriptorTemplate=pd;
        MTLDepthStencilDescriptor *depth=[MTLDepthStencilDescriptor new]; depth.depthCompareFunction=MTLCompareFunctionGreaterEqual;
        depth.depthWriteEnabled=YES; p->depthStencilState=[p->owner->device newDepthStencilStateWithDescriptor:depth];
        mcmtl_handle ph=(mcmtl_handle)(uintptr_t)(__bridge void *)p, recipe=0, program=0;
        uint32_t code=1|(layout==2?8:0);
        CHECK(mcmtl_ext_far_program_pin(dev,ph,code|16,&recipe)==MCMTL_E_INVALID_ARGUMENT && recipe==0);
        CHECK(mcmtl_ext_far_program_pin(dev,ph,code,&recipe)==MCMTL_OK && recipe!=0);
        __weak MCMTLPipeline *weak=p;
        p=nil; pd=nil; mcmtl_release(dev,v); mcmtl_release(dev,f);
        CHECK(weak!=nil); // Recipe, not the game/cache, now owns the original functions/pipeline.
        char error[512]={0};
        CHECK(mcmtl_ext_far_program_compile(recipe,&program,error,sizeof error)==MCMTL_OK && program!=0 && !error[0]);
        if (error[0]) fprintf(stderr,"%s\n",error);
        mcmtl_ext_far_program_drop(recipe);
        CHECK(weak==nil);
        MCMTLFarShader *side=(__bridge MCMTLFarShader *)(void *)(uintptr_t)program;
        CHECK(side.layout==layout && side.layer==MCMTLFarShaderCutout && side.pipelineState!=nil);
        side=nil; mcmtl_ext_far_program_drop(program);
    }
    mcmtl_handle result=99;
    CHECK(mcmtl_ext_far_program_pin(0,0,0,&result)==MCMTL_E_INVALID_ARGUMENT && result==0);
    CHECK(mcmtl_ext_far_program_compile(0,&result,NULL,0)==MCMTL_E_INVALID_ARGUMENT && result==0);
    mcmtl_ext_far_program_drop(0);
    mcmtl_device_destroy(dev);
} }
int main(void) { @autoreleasepool {
    dispatch_semaphore_t done=dispatch_semaphore_create(0);
    dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED,0),^{run();dispatch_semaphore_signal(done);});
    CHECK(dispatch_semaphore_wait(done,dispatch_time(DISPATCH_TIME_NOW,30*NSEC_PER_SEC))==0);
    printf("farimage_program_test: %u checks, %u failures\n",checks,failures); return failures?1:0;
} }
