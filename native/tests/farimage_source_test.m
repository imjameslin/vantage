/* Actual shader creation: opt-in retention keeps the selected variant and options, not the ICB form. */
#import "mcmetal_internal.h"
#import "mcmetal_shadersource.h"
#include <stdio.h>
#include <string.h>
extern int32_t mcmtl_ext_far_shader_capture_enable(mcmtl_handle);
static int checks, failures;
#define CHECK(x) do { checks++; if (!(x)) { failures++; fprintf(stderr,"FAIL line %d: %s\n",__LINE__,#x); } } while (0)
static mcmtl_handle shader(mcmtl_handle device, const char *source, uint32_t flags) {
    mcmtl_shader_desc d={.structSize=sizeof d,.stage=MCMTL_STAGE_VERTEX,.languageVersion=MTLLanguageVersion3_2,
        .flags=flags,.source=source,.sourceLength=strlen(source),.entryPoint="main0"};
    mcmtl_handle h=0; CHECK(mcmtl_shader_create(device,&d,&h)==MCMTL_OK); return h;
}
int main(void) { @autoreleasepool {
    mcmtl_device_desc desc={.structSize=sizeof desc,.framesInFlight=2};
    mcmtl_handle dev=0; CHECK(mcmtl_device_create(&desc,&dev)==MCMTL_OK); if (!dev) return 1;
    const char *sourceA="#include <metal_stdlib>\nusing namespace metal; vertex float4 main0(uint n [[vertex_id]]) { return float4(float(n),0,0,1); }";
    const char *sourceB="#include <metal_stdlib>\nusing namespace metal; vertex float4 main0(uint n [[vertex_id]]) { return float4(float(n),1,0,1); }";
    mcmtl_handle a=shader(dev,sourceA,0);
    NSString *text=nil,*entry=nil; MTLCompileOptions *options=nil;
    CHECK(!mcmtl_terrain_icb_env()); // Test runner must not enable the rejected ICB experiment.
    CHECK(!mcmtl_shader_copy_source(MCMTL_FUNCTION(a),&text,&entry,&options) && !text && !entry && !options);
    CHECK(mcmtl_ext_far_shader_capture_enable(0)==MCMTL_E_INVALID_ARGUMENT);
    CHECK(mcmtl_ext_far_shader_capture_enable(dev)==MCMTL_OK);
    mcmtl_handle b=shader(dev,sourceB,MCMTL_SHADER_FLAG_SAFE_MATH|MCMTL_SHADER_FLAG_PRESERVE_INVARIANCE);
    CHECK(mcmtl_shader_copy_source(MCMTL_FUNCTION(b),&text,&entry,&options));
    CHECK([text isEqualToString:@(sourceB)] && [entry isEqualToString:@"main0"]);
    CHECK(options.languageVersion==MTLLanguageVersion3_2 && options.mathMode==MTLMathModeSafe && options.preserveInvariance);
    options.mathMode=MTLMathModeFast; options.preserveInvariance=NO;
    MTLCompileOptions *again=nil;
    CHECK(mcmtl_shader_copy_source(MCMTL_FUNCTION(b),NULL,NULL,&again));
    CHECK(again!=options && again.mathMode==MTLMathModeSafe && again.preserveInvariance);
    // Re-creating the first variant with capture enabled keeps its own body and fast options.
    mcmtl_handle capturedA=shader(dev,sourceA,0);
    CHECK(mcmtl_shader_copy_source(MCMTL_FUNCTION(capturedA),&text,&entry,&options));
    CHECK([text isEqualToString:@(sourceA)] && options.mathMode==MTLMathModeFast && !options.preserveInvariance);
    CHECK(mcmtl_shader_copy_source(MCMTL_FUNCTION(b),&text,NULL,NULL) && [text isEqualToString:@(sourceB)]);
    CHECK(!mcmtl_shader_copy_source(nil,&text,&entry,&options) && !text && !entry && !options);
    mcmtl_release(dev,a); mcmtl_release(dev,b); mcmtl_release(dev,capturedA); mcmtl_device_destroy(dev);
    printf("farimage_source_test: %d checks, %d failures\n",checks,failures);
    return failures?1:0;
}}
