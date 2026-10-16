/* Source/admission checks: --sources-only (no Metal device).
 * Default also compiles every paired vary/layer/sampling form and its fullscreen
 * MRT pipeline. Run under tools/gpulock.py functional; parent owns image parity.
 */
#import "mcmetal_farshade_source.h"
#include <stdio.h>
#include <string.h>

static unsigned checks, failures;
#define CHECK(x) do { ++checks; if (!(x)) { ++failures; fprintf(stderr,"FAIL %d: %s\n",__LINE__,#x); } } while (0)
#import "farimage_shader_fixture.h"

static NSString *generate(NSString *b, NSString *c, NSString *f, unsigned layer) {
    NSError *error=nil;
    NSString *result=mcmtl_farshade_source(b,@"main0",c,@"main0",f,@"main0",layer,&error);
    CHECK((result!=nil)==(error==nil));
    return result;
}
static BOOL compile(id<MTLDevice> device, NSString *source) {
    MTLCompileOptions *options=[MTLCompileOptions new];
    options.languageVersion=MTLLanguageVersion3_2; options.mathMode=MTLMathModeFast;
    NSError *error=nil;
    id<MTLLibrary> library=[device newLibraryWithSource:source options:options error:&error];
    if (!library) fprintf(stderr,"library: %s\n",error.description.UTF8String);
    CHECK(library!=nil); if (!library) return NO;
    id<MTLFunction> v=[library newFunctionWithName:@"farshade_vertex"], f=[library newFunctionWithName:@"farshade_fragment"];
    CHECK(v!=nil && f!=nil);
    MTLRenderPipelineDescriptor *d=[MTLRenderPipelineDescriptor new];
    d.vertexFunction=v; d.fragmentFunction=f;
    d.colorAttachments[0].pixelFormat=MTLPixelFormatRGBA8Unorm;
    d.colorAttachments[1].pixelFormat=MTLPixelFormatR32Float;
    d.colorAttachments[2].pixelFormat=MTLPixelFormatR8Uint;
    MTLRenderPipelineReflection *reflection=nil;
    id<MTLRenderPipelineState> state=[device newRenderPipelineStateWithDescriptor:d
        options:MTLPipelineOptionBindingInfo reflection:&reflection error:&error];
    if (!state) fprintf(stderr,"pipeline: %s\n",error.description.UTF8String);
    CHECK(state!=nil);
    BOOL counterBinding=NO;
    for (id<MTLBinding> binding in reflection.fragmentBindings)
        counterBinding|=binding.type==MTLBindingTypeBuffer && binding.index==13;
    CHECK(!counterBinding); // no diagnostic resource at all on the ordinary PSO
    id<MTLArgumentEncoder> arguments=[f newArgumentEncoderWithBufferIndex:7];
    CHECK(arguments!=nil && arguments.encodedLength>=64*sizeof(uint64_t));
    d.fragmentFunction=[library newFunctionWithName:@"farshade_fragment_diagnostics"];
    CHECK(d.fragmentFunction); if (!d.fragmentFunction) return NO;
    id<MTLRenderPipelineState> diagnostic=[device newRenderPipelineStateWithDescriptor:d
        options:MTLPipelineOptionBindingInfo reflection:&reflection error:&error];
    if (!diagnostic) fprintf(stderr,"diagnostic pipeline: %s\n",error.description.UTF8String);
    CHECK(diagnostic);
    counterBinding=NO;
    for (id<MTLBinding> binding in reflection.fragmentBindings)
        counterBinding|=binding.type==MTLBindingTypeBuffer && binding.index==13;
    CHECK(counterBinding);
    id<MTLArgumentEncoder> diagnosticArguments=[d.fragmentFunction newArgumentEncoderWithBufferIndex:7];
    CHECK(diagnosticArguments.encodedLength==arguments.encodedLength);
    return state!=nil && arguments!=nil && diagnostic!=nil;
}
static void run(BOOL gpu) {
    id<MTLDevice> device=gpu ? MTLCreateSystemDefaultDevice() : nil;
    CHECK(!gpu || device!=nil); if (gpu && !device) return;
    for (unsigned vary=0;vary<3;vary++) for (unsigned layer=0;layer<2;layer++)
    for (unsigned sampling=0;sampling<3;sampling++) { @autoreleasepool {
        NSString *b=vertexFixture(MCMTLFarShaderBlock,vary), *c=vertexFixture(MCMTLFarShaderCompact,vary);
        NSString *f=fragmentFixture(vary,layer,sampling), *s=generate(b,c,f,layer);
        CHECK(s!=nil); if (!s) continue;
        CHECK(![s containsString:@"discard_fragment"] && ![s containsString:@"dfdx("] && ![s containsString:@"dfdy("]);
        CHECK(![s containsString:@"mcmtl_quads"] && ![s containsString:@"mcmtl_items"] && ![s containsString:@"mcmtl_far_id"]);
        CHECK(![s containsString:@"[[depth("] && [s containsString:@"uint mask [[color(2)]]"] && [s containsString:@"float depth [[color(1)]]"]);
        CHECK([s containsString:@"sourceDepth [[texture(5)]]"] && [s containsString:@"uint layer, flags"] &&
              [s containsString:@"p.flags&~3u"] && [s containsString:@"exact ? capturedDepth :"]);
        CHECK([s containsString:@"#define FARSHADE_SAMPLE() ((void)0)"] &&
              [s containsString:@"atomic_compare_exchange_weak_explicit(counters+16"] &&
              [s containsString:@"vb[ulong(q)*ulong(stride)+k]"]);
        // The full position/light/fog suffix of BOTH admitted VS bodies survives.
        for (NSString *v in @[b,c]) {
            NSRange body=[v rangeOfString:@"    float3 pos ="];
            CHECK(body.location!=NSNotFound && [s containsString:[v substringFromIndex:body.location]]);
        }
        // Original FS body changes only derivative input and discard reporting.
        NSRange body=[f rangeOfString:@"    main0_out out = {};"];
        NSString *fs=[f substringFromIndex:body.location];
        fs=replace(fs,@"dfdx(in.texCoord0)",@"dUVdx");
        fs=replace(fs,@"dfdy(in.texCoord0)",@"dUVdy");
        if (layer==MCMTLFarShaderCutout) fs=replace(fs,@"discard_fragment();",@"farshadeDiscarded = true; return main0_out{};");
        CHECK([s containsString:fs]);
        CHECK([s containsString:@"farshade_block::Projection"] && [s containsString:@"farshade_compact::Projection"] &&
              [s containsString:@"constant farshade_fs::TerrainUniform*"]);
        if (vary==2) CHECK([s containsString:@"out.vertexColor = half4(in.Color * sample_lightmap"] &&
                          [s containsString:@"in.vertexColor=half4(color)"] && [s containsString:@"in.mcmtlFogVis=half2(fogValue.xy)"]);
        if (gpu && !compile(device,s)) return;
    } }
    NSString *b=vertexFixture(MCMTLFarShaderBlock,0), *c=vertexFixture(MCMTLFarShaderCompact,0);
    NSString *f=fragmentFixture(0,MCMTLFarShaderCutout,0);
    CHECK(generate(b,vertexFixture(MCMTLFarShaderCompact,1),f,MCMTLFarShaderCutout)==nil);
    CHECK(generate(b,c,fragmentFixture(1,MCMTLFarShaderCutout,0),MCMTLFarShaderCutout)==nil);
    CHECK(generate(c,b,f,MCMTLFarShaderCutout)==nil);
    CHECK(generate(b,c,f,MCMTLFarShaderSolid)==nil);
    CHECK(generate(replace(b,@"return length(pos);",@"return 0.0;"),c,f,MCMTLFarShaderCutout)==nil);
    CHECK(generate(b,c,replace(f,@"color.w < 0.5",@"color.w < 0.1"),MCMTLFarShaderCutout)==nil);
    CHECK(generate(nil,c,f,MCMTLFarShaderCutout)==nil);
    CHECK(generate(b,c,f,2)==nil);
    // Numeric translator names can differ between every original stage.
    b=replace(replace(b,@"main0(",@"blockEntry("),@"_108",@"_8108");
    c=replace(replace(c,@"main0(",@"compactEntry("),@"_108",@"_9108");
    f=replace(replace(f,@"main0(",@"fragmentEntry("),@"_395",@"_9395");
    NSError *error=nil;
    NSString *s=mcmtl_farshade_source(b,@"blockEntry",c,@"compactEntry",f,@"fragmentEntry",MCMTLFarShaderCutout,&error);
    CHECK(s!=nil && error==nil);
    if (s && gpu) compile(device,s);
    CHECK(mcmtl_farshade_source(b,@"missing",c,@"compactEntry",f,@"fragmentEntry",MCMTLFarShaderCutout,NULL)==nil);
}
int main(int argc, const char *argv[]) { @autoreleasepool {
    BOOL gpu=!(argc==2 && !strcmp(argv[1],"--sources-only"));
    run(gpu);
    printf("farimage_shade_source_test: %u checks, %u failures (%s)\n",checks,failures,gpu?"Metal compile":"sources only");
    return failures ? 1 : 0;
} }
