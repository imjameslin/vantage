#import "mcmetal_farprogram.h"
#import "mcmetal_farshader.h"
#import "mcmetal_shadersource.h"
#include <stdio.h>

@interface MCMTLFarProgramRecipe : NSObject
@property(nonatomic, strong) MCMTLPipeline *pipeline;
@property(nonatomic) uint32_t layerCode;
@end
@implementation MCMTLFarProgramRecipe
@end

MCMTL_API int32_t mcmtl_ext_far_program_pin(mcmtl_handle device, mcmtl_handle selected,
                                         uint32_t code, mcmtl_handle *outRecipe) {
    if (outRecipe) *outRecipe = 0;
    if (!device || !selected || !outRecipe || (code & ~9u) || (code & 7u) > 1)
        return MCMTL_E_INVALID_ARGUMENT;
    @try {
        MCMTLPipeline *pipeline = MCMTL_PIPELINE(selected);
        if (pipeline->owner != MCMTL_DEVICE(device)) return MCMTL_E_INVALID_ARGUMENT;
        MCMTLFarProgramRecipe *recipe = [MCMTLFarProgramRecipe new];
        recipe.pipeline = pipeline; recipe.layerCode = code;
        *outRecipe = (mcmtl_handle)(uintptr_t)(__bridge_retained void *)recipe;
        return MCMTL_OK;
    } @catch (NSException *exception) { (void)exception; return MCMTL_E_OBJC_EXCEPTION; }
}

static int32_t program_error(char *out, uint32_t cap, NSString *message, int32_t status) {
    if (out && cap) snprintf(out, cap, "%s", message.UTF8String ?: "Far program failed");
    return status;
}

MCMTL_API int32_t mcmtl_ext_far_program_compile(mcmtl_handle handle, mcmtl_handle *outProgram,
                                             char *message, uint32_t capacity) {
    if (outProgram) *outProgram = 0;
    if (message && capacity) message[0] = 0;
    if (!handle || !outProgram || (capacity && !message)) return MCMTL_E_INVALID_ARGUMENT;
    @autoreleasepool { @try {
        MCMTLFarProgramRecipe *recipe = (__bridge MCMTLFarProgramRecipe *)(void *)(uintptr_t)handle;
        MCMTLPipeline *pipeline = recipe.pipeline;
        NSString *v = nil, *f = nil, *ve = nil, *fe = nil;
        MTLCompileOptions *vo = nil, *fo = nil;
        if (!mcmtl_shader_copy_source(pipeline->descriptorTemplate.vertexFunction, &v, &ve, &vo)
            || !mcmtl_shader_copy_source(pipeline->descriptorTemplate.fragmentFunction, &f, &fe, &fo))
            return program_error(message, capacity, @"Selected LOD function has no captured source", MCMTL_E_UNSUPPORTED);
        // shader_create configures exactly these fields; mismatched stages cannot share options.
        if (vo.languageVersion != fo.languageVersion || vo.mathMode != fo.mathMode
            || vo.preserveInvariance != fo.preserveInvariance || vo.preprocessorMacros.count || fo.preprocessorMacros.count)
            return program_error(message, capacity, @"Selected LOD stages have differing compile options", MCMTL_E_UNSUPPORTED);
        NSError *error = nil;
        MCMTLFarShader *program = mcmtl_farshader_compile(pipeline, mcmtl_variant_key(1, MTLPixelFormatDepth32Float),
            v, f, ve, fe, vo, (recipe.layerCode & 8u) ? MCMTLFarShaderCompact : MCMTLFarShaderBlock,
            (MCMTLFarShaderLayer)(recipe.layerCode & 7u), &error);
        if (!program) return program_error(message, capacity, error.localizedDescription, MCMTL_E_UNSUPPORTED);
        *outProgram = (mcmtl_handle)(uintptr_t)(__bridge_retained void *)program;
        return MCMTL_OK;
    } @catch (NSException *exception) {
        return program_error(message, capacity, exception.reason, MCMTL_E_OBJC_EXCEPTION);
    } }
}

MCMTL_API void mcmtl_ext_far_program_drop(mcmtl_handle object) {
    if (object) { id released = (__bridge_transfer id)(void *)(uintptr_t)object; (void)released; }
}

MCMTL_API mcmtl_handle mcmtl_ext_far_program_retain(mcmtl_handle program) {
    if (!program) return 0;
    id object=(__bridge id)(void *)(uintptr_t)program;
    return (mcmtl_handle)(uintptr_t)(__bridge_retained void *)object;
}
