/*
 * mcmetal_pipeline.m -- MSL shader functions, render pipelines, PSO variants.
 * OWNER: I1 (native-core). Contract: docs/metal/CONTRACT.md sections 3 and 7; mcmetal.h; spec 2.5, 3.6, 4.2.
 *
 * Threading: mcmtl_shader_create and mcmtl_pipeline_create run on compile worker threads, concurrently
 * (MTLDevice compile calls are thread-safe; nothing here shares mutable state except the architect's
 * locked depth-stencil cache). mcmtl_pipeline_build_variant works on a COPY of the immutable descriptor
 * template, so the decoder may build variants on the render thread while workers create other pipelines.
 *
 * Variants (CONTRACT.md 7.3): key = (color-attachment presence mask, depth format). The template carries
 * functions, vertex descriptor, per-attachment blend state and write masks; a variant copy sets
 * colorAttachments[i].pixelFormat (target format where the pass has an attachment, else Invalid with
 * blending/write mask off), depthAttachmentPixelFormat and stencilAttachmentPixelFormat (same format when
 * it has a stencil aspect; Stencil8 is a stencil-only attachment).
 *
 * Precompile policy: every precompileDepthFormats[i] variant is built before the handle is returned, the
 * variants WITH a depth attachment first, whatever the order in the descriptor. Measured on M5 / macOS 27
 * (core_test, build/probe): for a fragment function that writes [[depth(any)]], Metal validates "shader
 * writes to depth" only on the first PSO built from it and caches the verdict: if that first PSO has no
 * depth attachment, it fails AND every later PSO of that function fails too, even with Depth32Float;
 * if the first PSO has a depth attachment, later depth-less PSOs build and render fine (depth writes are
 * dropped, exactly like Vulkan without a depth attachment). Vanilla's depth-writing fragments (blit_depth,
 * OIT under RENDERPEARL_EXPLICIT_DEPTH_INVARIANCE) therefore need the depth variant built first.
 * Should a depth-less precompile still fail while a depth variant was built, the pipeline is kept (it works
 * in depth passes) and a device message is logged; a later lazy build of that key fails again and the
 * decoder skips those draws.
 */
#import "mcmetal_core.h"
#import "mcmetal_shadersource.h"

#include <ctype.h>
#include <objc/runtime.h>
#include <stdlib.h>
#include <string.h>

/* ---------------------------------------------------------------------------------------------- */
/* Terrain ICB support (CONTRACT.md 16.5)                                                           */
/* ---------------------------------------------------------------------------------------------- */

int mcmtl_terrain_icb_env(void) {
    static int value = -1;
    if (value < 0) {
        const char *v = getenv("MCMTL_TERRAIN_ICB");
        /* Default OFF: measured in game (static scene, serialized per-pass profile) the Main pass costs 3.55 ms of GPU
           with ICB ranges vs 2.16 ms with per-record indirect draws. MCMTL_TERRAIN_ICB=1 opts in. */
        value = v && atoi(v) != 0 ? 1 : 0;
    }
    return value;
}

/* MSL kept for a shader function (associated object) and its lazily compiled ICB form. */
@interface MCMTLShaderSource : NSObject {
@public
    NSString           *source;
    NSString           *entryPoint;
    MTLCompileOptions  *options;
    id<MTLFunction>     icbFunction;   /* compiled ICB form (the original function when it has no textures)   */
    NSString           *icbFailure;    /* why the ICB form cannot be built (remembered)                       */
    uint32_t            textureMask;   /* slots the entry point reads (valid with icbFunction)                */
    uint32_t            samplerMask;
}
@end
@implementation MCMTLShaderSource
@end

static char mcmtl_shader_source_key;
static char mcmtl_far_shader_capture_key;
static os_unfair_lock mcmtl_shader_source_lock = OS_UNFAIR_LOCK_INIT;

/* Optional extension, called once while constructing an opt-in device's shader
 * cache, before compile workers start. Does not enable ICBs or change any shader.
 * Retention is per MTLFunction, so the selected nearest/RGSS/vfog/hand function
 * carries its own exact source, rather than the unselected Prepared template.
 */
MCMTL_API int32_t mcmtl_ext_far_shader_capture_enable(mcmtl_handle device) {
    MCMTL_TRY_BEGIN
        mcmtl_clear_error();
        if (!device) return MCMTL_E_INVALID_ARGUMENT;
        objc_setAssociatedObject(MCMTL_DEVICE(device), &mcmtl_far_shader_capture_key, @YES, OBJC_ASSOCIATION_RETAIN);
        return MCMTL_OK;
    MCMTL_TRY_END(MCMTL_E_OBJC_EXCEPTION)
}

void mcmtl_shader_keep_source(id<MTLFunction> function, NSString *source, NSString *entryPoint, MTLCompileOptions *options) {
    if (!function || !source) return;
    os_unfair_lock_lock(&mcmtl_shader_source_lock);
    if (!objc_getAssociatedObject(function, &mcmtl_shader_source_key)) {
        /* Metal returns one shared MTLFunction per identical source: the first copy kept is as good as any. The kept
         * object references no Metal object, so this forms no retain cycle (see the note in mcmtl_shader_create). */
        MCMTLShaderSource *kept = [MCMTLShaderSource new];
        kept->source = [source copy];
        kept->entryPoint = entryPoint.length ? [entryPoint copy] : @"main0";
        kept->options = [options copy];
        objc_setAssociatedObject(function, &mcmtl_shader_source_key, kept, OBJC_ASSOCIATION_RETAIN);
    }
    os_unfair_lock_unlock(&mcmtl_shader_source_lock);
}

BOOL mcmtl_shader_copy_source(id<MTLFunction> function, NSString **source,
                              NSString **entryPoint, MTLCompileOptions **options) {
    if (source) *source = nil;
    if (entryPoint) *entryPoint = nil;
    if (options) *options = nil;
    if (!function) return NO;
    os_unfair_lock_lock(&mcmtl_shader_source_lock);
    MCMTLShaderSource *kept = objc_getAssociatedObject(function, &mcmtl_shader_source_key);
    // Keep a strong reference before unlock; copying compile options need not hold the global lock.
    os_unfair_lock_unlock(&mcmtl_shader_source_lock);
    if (!kept) return NO;
    if (source) *source = kept->source;
    if (entryPoint) *entryPoint = kept->entryPoint;
    if (options) *options = [kept->options copy];
    return YES;
}

/* One entry-point parameter of the MSL signature: [begin, end) in the UTF-8 source. */
typedef struct mcmtl_msl_param {
    const char *begin, *end;
} mcmtl_msl_param;

static NSString *mcmtl_msl_slice(const char *begin, const char *end) {
    while (begin < end && (*begin == ' ' || *begin == '\n' || *begin == '\t' || *begin == '\r')) begin++;
    while (end > begin && (end[-1] == ' ' || end[-1] == '\n' || end[-1] == '\t' || end[-1] == '\r')) end--;
    return [[NSString alloc] initWithBytes:begin length:(NSUInteger)(end - begin) encoding:NSUTF8StringEncoding];
}

/* "[[kind(N)]]" in [begin, end): N, or -1. */
static int mcmtl_msl_attribute_index(const char *begin, const char *end, const char *kind) {
    const size_t k = strlen(kind);
    for (const char *p = begin; p + k + 4 < end; p++) {
        if (p[0] != '[' || p[1] != '[') continue;
        const char *q = p + 2;
        while (q < end && *q == ' ') q++;
        if ((size_t)(end - q) <= k || strncmp(q, kind, k) != 0 || q[k] != '(') continue;
        return (int)strtol(q + k + 1, NULL, 10);
    }
    return -1;
}

NSString *mcmtl_msl_icb_rewrite(NSString *source, NSString *entryPoint, uint32_t argIndex, uint32_t *textureMask,
                                uint32_t *samplerMask, NSString **failure) {
    if (textureMask) *textureMask = 0;
    if (samplerMask) *samplerMask = 0;
    const char *src = source.UTF8String;
    const char *name = (entryPoint.length ? entryPoint : @"main0").UTF8String;
    if (!src || !name) {
        if (failure) *failure = @"no source";
        return nil;
    }
    const size_t nameLength = strlen(name);
    /* The entry point: a line starting with "vertex " or "fragment " whose declarator is `name(`. */
    const char *decl = NULL, *open = NULL;
    for (const char *line = src; line && *line; line = strchr(line, '\n') ? strchr(line, '\n') + 1 : NULL) {
        if (strncmp(line, "vertex ", 7) != 0 && strncmp(line, "fragment ", 9) != 0) continue;
        const char *eol = strchr(line, '\n');
        if (!eol) eol = line + strlen(line);
        for (const char *p = line; p + nameLength < eol; p++) {
            if (strncmp(p, name, nameLength) == 0 && p[nameLength] == '(' && p > line && p[-1] == ' ') {
                decl = line;
                open = p + nameLength;
                break;
            }
        }
        if (decl) break;
    }
    if (!decl) {
        if (failure) *failure = [NSString stringWithFormat:@"entry point %s not found", name];
        return nil;
    }
    /* Parameters: split at top-level commas (parentheses, angle and square brackets nest). */
    mcmtl_msl_param params[64];
    uint32_t paramCount = 0;
    int paren = 0, angle = 0, square = 0;
    const char *close = NULL, *start = open + 1;
    for (const char *p = open; *p; p++) {
        const char c = *p;
        if (c == '(') paren++;
        else if (c == ')') {
            if (--paren == 0) {
                close = p;
                break;
            }
        } else if (c == '<') angle++;
        else if (c == '>' && angle > 0) angle--;
        else if (c == '[') square++;
        else if (c == ']' && square > 0) square--;
        else if (c == ',' && paren == 1 && angle == 0 && square == 0) {
            if (paramCount == 64) {
                if (failure) *failure = @"too many entry-point parameters";
                return nil;
            }
            params[paramCount++] = (mcmtl_msl_param){ start, p };
            start = p + 1;
        }
    }
    if (!close) {
        if (failure) *failure = @"unterminated entry-point signature";
        return nil;
    }
    if (paramCount == 64) {
        if (failure) *failure = @"too many entry-point parameters";
        return nil;
    }
    params[paramCount++] = (mcmtl_msl_param){ start, close };
    const char *body = close + 1;
    while (*body == ' ' || *body == '\n' || *body == '\r' || *body == '\t') body++;
    if (*body != '{') {
        if (failure) *failure = @"entry-point body not found";
        return nil;
    }

    NSString *texType[16] = { nil }, *texName[16] = { nil }, *smpName[16] = { nil };
    uint32_t tmask = 0, smask = 0;
    NSMutableArray<NSString *> *kept = [NSMutableArray array];
    for (uint32_t i = 0; i < paramCount; i++) {
        const int t = mcmtl_msl_attribute_index(params[i].begin, params[i].end, "texture");
        const int s = t < 0 ? mcmtl_msl_attribute_index(params[i].begin, params[i].end, "sampler") : -1;
        if (t < 0 && s < 0) {
            NSString *param = mcmtl_msl_slice(params[i].begin, params[i].end);
            if (mcmtl_msl_attribute_index(params[i].begin, params[i].end, "buffer") == (int)argIndex) {
                if (failure) *failure = [NSString stringWithFormat:@"[[buffer(%u)]] is already used", argIndex];
                return nil;
            }
            if (param.length) [kept addObject:param];
            continue;
        }
        const int slot = t >= 0 ? t : s;
        /* `type name [[texture(n)]]`: the name is the last identifier before "[[". */
        const char *attr = strstr(params[i].begin, "[[");
        if (!attr || attr > params[i].end || slot < 0 || slot >= 16) {
            if (failure) *failure = [NSString stringWithFormat:@"unsupported resource parameter slot %d", slot];
            return nil;
        }
        const char *nameEnd = attr;
        while (nameEnd > params[i].begin && nameEnd[-1] == ' ') nameEnd--;
        const char *nameBegin = nameEnd;
        while (nameBegin > params[i].begin && (isalnum((unsigned char)nameBegin[-1]) || nameBegin[-1] == '_')) nameBegin--;
        NSString *type = mcmtl_msl_slice(params[i].begin, nameBegin);
        NSString *paramName = mcmtl_msl_slice(nameBegin, nameEnd);
        if (!paramName.length || !type.length || [type containsString:@"array<"] || [type containsString:@"&"] ||
            [type containsString:@"*"]) {
            if (failure) *failure = [NSString stringWithFormat:@"unsupported resource parameter '%@ %@'", type, paramName];
            return nil;
        }
        const uint32_t bit = 1u << slot;
        if (t >= 0) {
            if (tmask & bit) {
                if (failure) *failure = [NSString stringWithFormat:@"texture slot %d declared twice", slot];
                return nil;
            }
            tmask |= bit;
            texType[slot] = type;
            texName[slot] = paramName;
        } else {
            if (smask & bit) {
                if (failure) *failure = [NSString stringWithFormat:@"sampler slot %d declared twice", slot];
                return nil;
            }
            smask |= bit;
            smpName[slot] = paramName;
        }
    }
    if (textureMask) *textureMask = tmask;
    if (samplerMask) *samplerMask = smask;
    if (!(tmask | smask)) return source;

    /* Argument buffer layout: slot n = texture MTLResourceID at 16n, sampler MTLResourceID at 16n + 8. */
    const uint32_t top = 32u - (uint32_t)__builtin_clz(tmask | smask);
    NSMutableString *out = [NSMutableString stringWithCapacity:source.length + 1024];
    [out appendString:[[NSString alloc] initWithBytes:src length:(NSUInteger)(decl - src) encoding:NSUTF8StringEncoding]];
    [out appendString:@"struct mcmtl_icb_args\n{\n"];
    for (uint32_t n = 0; n < top; n++) {
        if (tmask & (1u << n)) [out appendFormat:@"    %@ t%u;\n", texType[n], n];
        else [out appendFormat:@"    ulong tpad%u;\n", n];
        if (smask & (1u << n)) [out appendFormat:@"    sampler s%u;\n", n];
        else [out appendFormat:@"    ulong spad%u;\n", n];
    }
    [out appendString:@"};\n\n"];
    [out appendString:[[NSString alloc] initWithBytes:decl length:(NSUInteger)(open + 1 - decl) encoding:NSUTF8StringEncoding]];
    for (NSString *param in kept) [out appendFormat:@"%@, ", param];
    [out appendFormat:@"constant mcmtl_icb_args& mcmtl_icb [[buffer(%u)]])\n{\n", argIndex];
    for (uint32_t n = 0; n < top; n++) {
        if (tmask & (1u << n)) [out appendFormat:@"    %@ %@ = mcmtl_icb.t%u;\n", texType[n], texName[n], n];
        if (smask & (1u << n)) [out appendFormat:@"    sampler %@ = mcmtl_icb.s%u;\n", smpName[n], n];
    }
    [out appendString:[NSString stringWithUTF8String:body + 1]];
    return out;
}

/* The ICB form of `function` (compiled once, cached on its kept source). nil + *failure if unavailable. */
static id<MTLFunction> mcmtl_icb_function(MCMTLDevice *dev, id<MTLFunction> function, uint32_t stageTextureMask,
                                          uint32_t stageSamplerMask, NSString **failure) {
    if (!function) return nil;
    os_unfair_lock_lock(&mcmtl_shader_source_lock);
    MCMTLShaderSource *kept = objc_getAssociatedObject(function, &mcmtl_shader_source_key);
    id<MTLFunction> cached = kept ? kept->icbFunction : nil;
    NSString *knownFailure = kept ? kept->icbFailure : nil;
    os_unfair_lock_unlock(&mcmtl_shader_source_lock);
    if (!kept) {
        *failure = [NSString stringWithFormat:@"no MSL source kept for function '%@' (not created by mcmtl_shader_create with MCMTL_TERRAIN_ICB on)",
                                              function.name];
        return nil;
    }
    if (knownFailure) {
        *failure = knownFailure;
        return nil;
    }
    uint32_t tmask = 0, smask = 0;
    if (!cached) {
        NSString *why = nil;
        NSString *rewritten = mcmtl_msl_icb_rewrite(kept->source, kept->entryPoint, MCMTL_ICB_ARG_BUFFER_INDEX, &tmask, &smask, &why);
        id<MTLFunction> built = nil;
        if (rewritten == kept->source) {
            built = function;   /* no textures: the function itself is ICB-compatible */
        } else if (rewritten) {
            NSError *error = nil;
            id<MTLLibrary> library = [dev->device newLibraryWithSource:rewritten options:kept->options error:&error];
            built = library ? [library newFunctionWithName:kept->entryPoint] : nil;
            if (!built) why = [NSString stringWithFormat:@"ICB form of '%@' failed to compile: %@", function.name,
                                                         error ? error.localizedDescription : @"(entry point missing)"];
        }
        os_unfair_lock_lock(&mcmtl_shader_source_lock);
        if (built) {
            if (!kept->icbFunction) {
                kept->icbFunction = built;
                kept->textureMask = tmask;
                kept->samplerMask = smask;
            }
            cached = kept->icbFunction;
        } else if (!kept->icbFailure) {
            kept->icbFailure = why ?: @"ICB rewrite failed";
        }
        knownFailure = kept->icbFailure;
        os_unfair_lock_unlock(&mcmtl_shader_source_lock);
        if (!cached) {
            *failure = knownFailure;
            return nil;
        }
    }
    os_unfair_lock_lock(&mcmtl_shader_source_lock);
    tmask = kept->textureMask;
    smask = kept->samplerMask;
    os_unfair_lock_unlock(&mcmtl_shader_source_lock);
    /* The decoder fills the argument buffer from the pipeline's binding masks: a slot the shader reads but the masks
     * miss would read a null handle. */
    if ((tmask & ~stageTextureMask) || (smask & ~stageSamplerMask)) {
        *failure = [NSString stringWithFormat:@"function '%@' reads texture slots 0x%x / sampler slots 0x%x outside the pipeline's "
                                              "binding masks 0x%x / 0x%x", function.name, tmask, smask, stageTextureMask, stageSamplerMask];
        return nil;
    }
    return cached;
}

/* ---------------------------------------------------------------------------------------------- */
/* Shaders                                                                                          */
/* ---------------------------------------------------------------------------------------------- */

static os_unfair_lock mcmtl_function_label_lock = OS_UNFAIR_LOCK_INIT;

static BOOL mcmtl_is_known_language_version(uint32_t v) {
    switch (v) {
        case 65536: case 65537: case 65538:                          /* 1.0 - 1.2 */
        case 131072: case 131073: case 131074: case 131075: case 131076: /* 2.0 - 2.4 */
        case 196608: case 196609: case 196610:                       /* 3.0 - 3.2 */
        case 262144:                                                 /* 4.0 */
            return YES;
        default:
            return NO;
    }
}

MCMTL_API int32_t mcmtl_shader_create(mcmtl_handle device, const mcmtl_shader_desc *desc, mcmtl_handle *outFunction) {
    MCMTL_TRY_BEGIN
        mcmtl_clear_error();
        if (outFunction) *outFunction = 0;
        if (!device || !desc || !outFunction) {
            mcmtl_set_error("mcmtl_shader_create: NULL argument");
            return MCMTL_E_INVALID_ARGUMENT;
        }
        if (desc->structSize != sizeof(mcmtl_shader_desc)) {
            mcmtl_set_error("mcmtl_shader_create: structSize %u != %zu", desc->structSize, sizeof(mcmtl_shader_desc));
            return MCMTL_E_ABI_MISMATCH;
        }
        MCMTLDevice *dev = MCMTL_DEVICE(device);
        if (desc->stage != MCMTL_STAGE_VERTEX && desc->stage != MCMTL_STAGE_FRAGMENT) {
            mcmtl_set_error("mcmtl_shader_create: stage %u must be MCMTL_STAGE_VERTEX or MCMTL_STAGE_FRAGMENT", desc->stage);
            return MCMTL_E_INVALID_ARGUMENT;
        }
        if (!desc->source || desc->sourceLength == 0 || desc->sourceLength > (64u << 20)) {
            mcmtl_set_error("mcmtl_shader_create: source missing or %llu bytes", (unsigned long long)desc->sourceLength);
            return MCMTL_E_INVALID_ARGUMENT;
        }
        const uint32_t knownFlags = MCMTL_SHADER_FLAG_PRESERVE_INVARIANCE | MCMTL_SHADER_FLAG_SAFE_MATH;
        if (desc->flags & ~knownFlags) {
            mcmtl_set_error("mcmtl_shader_create: unknown flags 0x%x", desc->flags & ~knownFlags);
            return MCMTL_E_INVALID_ARGUMENT;
        }
        const uint32_t languageVersion = desc->languageVersion ? desc->languageVersion : dev->mslLanguageVersion;
        if (!mcmtl_is_known_language_version(languageVersion)) {
            mcmtl_set_error("mcmtl_shader_create: unknown MTLLanguageVersion %u", languageVersion);
            return MCMTL_E_INVALID_ARGUMENT;
        }
        NSString *source = [[NSString alloc] initWithBytes:desc->source length:(NSUInteger)desc->sourceLength encoding:NSUTF8StringEncoding];
        if (!source) {
            mcmtl_set_error("mcmtl_shader_create: source is not valid UTF-8");
            return MCMTL_E_INVALID_ARGUMENT;
        }
        NSString *entryPoint = desc->entryPoint ? [NSString stringWithUTF8String:desc->entryPoint] : @"main0";
        if (!entryPoint.length) {
            mcmtl_set_error("mcmtl_shader_create: empty or invalid entry point");
            return MCMTL_E_INVALID_ARGUMENT;
        }
        const char *stageName = desc->stage == MCMTL_STAGE_VERTEX ? "vertex" : "fragment";

        MTLCompileOptions *options = [MTLCompileOptions new];
        options.languageVersion = (MTLLanguageVersion)languageVersion;
        options.mathMode = (desc->flags & MCMTL_SHADER_FLAG_SAFE_MATH) ? MTLMathModeSafe : MTLMathModeFast;
        options.preserveInvariance = (desc->flags & MCMTL_SHADER_FLAG_PRESERVE_INVARIANCE) != 0;

        NSError *error = nil;
        id<MTLLibrary> library = [dev->device newLibraryWithSource:source options:options error:&error];
        if (!library) {
            const char *label = desc->label ? desc->label : "";
            mcmtl_set_error("MSL compile of %s shader '%s' failed: %s", stageName, label,
                            error ? error.localizedDescription.UTF8String : "(no NSError)");
            return MCMTL_E_COMPILE_FAILED;
        }
        id<MTLFunction> function = [library newFunctionWithName:entryPoint];
        if (!function) {
            mcmtl_set_error("MSL %s shader '%s': entry point '%s' not found", stageName, desc->label ? desc->label : "",
                            entryPoint.UTF8String);
            return MCMTL_E_COMPILE_FAILED;
        }
        const MTLFunctionType expected = desc->stage == MCMTL_STAGE_VERTEX ? MTLFunctionTypeVertex : MTLFunctionTypeFragment;
        if (function.functionType != expected) {
            mcmtl_set_error("MSL %s shader '%s': entry point '%s' is not a %s function", stageName, desc->label ? desc->label : "",
                            entryPoint.UTF8String, stageName);
            return MCMTL_E_COMPILE_FAILED;
        }
        if (desc->label) {
            NSString *label = [NSString stringWithUTF8String:desc->label];
            if (label.length) {
                library.label = label;
                /* Metal hands out ONE shared MTLFunction object per identical source, even across libraries
                 * (measured): serialize the (nonatomic) label setter between compile workers. */
                os_unfair_lock_lock(&mcmtl_function_label_lock);
                function.label = label;
                os_unfair_lock_unlock(&mcmtl_function_label_lock);
            }
        }
        /* The MTLFunction keeps everything it needs from its library alive (PSOs and variants build fine
         * after the last library reference is gone, see core_test). Do NOT attach the library to the function
         * (e.g. as an associated object): libraries cache their functions (Metal even returns the same
         * function object for identical source), so that would form a retain cycle and leak both forever. */
        if (mcmtl_terrain_icb_env() || objc_getAssociatedObject(dev, &mcmtl_far_shader_capture_key))
            mcmtl_shader_keep_source(function, source, entryPoint, options);
        *outFunction = mcmtl_handle_retain(function);
        return MCMTL_OK;
    MCMTL_TRY_END(MCMTL_E_OBJC_EXCEPTION)
}

/* ---------------------------------------------------------------------------------------------- */
/* Variants                                                                                         */
/* ---------------------------------------------------------------------------------------------- */

/* The descriptor of the variant `key` (a copy of the template with the key's attachment formats). Returns nil and a
 * reason on failure. MCMTL_VARIANT_KEY_BGRA8_COLOR0 (present fusion, CONTRACT.md 16.3): color slot 0 is the BGRA8Unorm
 * drawable instead of the pipeline's RGBA8Unorm target -- the same bytes with R and B swapped in memory, so blending
 * and every value written are identical. */
static MTLRenderPipelineDescriptor *mcmtl_variant_descriptor(MCMTLPipeline *p, uint64_t key, NSString **failure) {
    if (!p || !p->owner || !p->descriptorTemplate) {
        *failure = @"pipeline has no descriptor template";
        return nil;
    }
    const BOOL bgra0 = (key & MCMTL_VARIANT_KEY_BGRA8_COLOR0) != 0;
    const BOOL icb = (key & MCMTL_VARIANT_KEY_ICB) != 0;
    key &= ~(MCMTL_VARIANT_KEY_BGRA8_COLOR0 | MCMTL_VARIANT_KEY_ICB);
    const uint32_t colorMask = (uint32_t)(key & 0xFFu);
    const uint64_t depthKey = key >> 8;
    const uint32_t depthFormat = (uint32_t)depthKey;
    if (depthKey > 0xFFFFFFFFull || (depthFormat != 0 && !mcmtl_core_format_is_depth_stencil(depthFormat))) {
        *failure = [NSString stringWithFormat:@"variant key 0x%llx: %llu is not a depth/stencil pixel format",
                                              (unsigned long long)key, (unsigned long long)depthKey];
        return nil;
    }
    MCMTLDevice *dev = p->owner;
    if (depthFormat == MTLPixelFormatDepth24Unorm_Stencil8 && !dev->device.depth24Stencil8PixelFormatSupported) {
        *failure = @"Depth24Unorm_Stencil8 is not supported on this GPU";
        return nil;
    }
    if (bgra0) {
        const uint32_t f0 = (colorMask & 1u) && p->colorTargetCount > 0 ? p->colorTargetFormats[0] : 0;
        if (f0 != MTLPixelFormatRGBA8Unorm && f0 != MTLPixelFormatBGRA8Unorm) {
            *failure = [NSString stringWithFormat:@"variant key 0x%llx: color 0 format %u has no BGRA8Unorm equivalent",
                                                  (unsigned long long)key, f0];
            return nil;
        }
    }
    MTLRenderPipelineDescriptor *descriptor = [p->descriptorTemplate copy];
    for (uint32_t i = 0; i < MCMTL_MAX_COLOR_ATTACHMENTS; i++) {
        MTLRenderPipelineColorAttachmentDescriptor *attachment = descriptor.colorAttachments[i];
        uint32_t format = (colorMask & (1u << i)) && i < p->colorTargetCount ? p->colorTargetFormats[i] : 0;
        if (i == 0 && bgra0) format = MTLPixelFormatBGRA8Unorm;
        attachment.pixelFormat = (MTLPixelFormat)format;
        if (format == 0) {
            attachment.blendingEnabled = NO;
            attachment.writeMask = MTLColorWriteMaskNone;
        }
    }
    descriptor.depthAttachmentPixelFormat = mcmtl_core_format_has_depth(depthFormat) ? (MTLPixelFormat)depthFormat : MTLPixelFormatInvalid;
    descriptor.stencilAttachmentPixelFormat = mcmtl_core_format_has_stencil(depthFormat) ? (MTLPixelFormat)depthFormat : MTLPixelFormatInvalid;
    if (icb) {
        /* Terrain ICB (CONTRACT.md 16.5): the same pipeline with shaders that read textures from the argument buffer. */
        id<MTLFunction> vs = mcmtl_icb_function(dev, descriptor.vertexFunction, p->textureMask[0], p->samplerMask[0], failure);
        if (!vs) return nil;
        id<MTLFunction> fs = nil;
        if (descriptor.fragmentFunction) {
            fs = mcmtl_icb_function(dev, descriptor.fragmentFunction, p->textureMask[1], p->samplerMask[1], failure);
            if (!fs) return nil;
        }
        descriptor.vertexFunction = vs;
        descriptor.fragmentFunction = fs;
        descriptor.supportIndirectCommandBuffers = YES;
    }
    return descriptor;
}

MTLRenderPipelineDescriptor *mcmtl_pipeline_variant_descriptor(MCMTLPipeline *pipeline, uint64_t key, NSString **failure) {
    NSString *reason = nil;
    MTLRenderPipelineDescriptor *descriptor = nil;
    @try {
        descriptor = mcmtl_variant_descriptor(pipeline, key, &reason);
    } @catch (NSException *exception) {
        reason = [NSString stringWithFormat:@"exception %@: %@", exception.name, exception.reason];
        descriptor = nil;
    }
    if (failure) *failure = reason;
    return descriptor;
}

/* Builds the PSO for `key` on a copy of the template. Returns nil and a reason on failure. */
static id<MTLRenderPipelineState> mcmtl_build_variant_impl(MCMTLPipeline *p, uint64_t key, NSString **failure) {
    MTLRenderPipelineDescriptor *descriptor = mcmtl_variant_descriptor(p, key, failure);
    if (!descriptor) return nil;
    MCMTLDevice *dev = p->owner;
    const uint32_t colorMask = (uint32_t)(key & 0xFFu);
    const uint32_t depthFormat = (uint32_t)((key & ~(MCMTL_VARIANT_KEY_BGRA8_COLOR0 | MCMTL_VARIANT_KEY_ICB)) >> 8);
    NSError *error = nil;
    id<MTLRenderPipelineState> pso = [dev->device newRenderPipelineStateWithDescriptor:descriptor error:&error];
    if (!pso) {
        *failure = [NSString stringWithFormat:@"PSO variant (color mask 0x%x, depth format %u) of '%@' failed: %@", colorMask,
                                              depthFormat, p->label ?: @"", error ? error.localizedDescription : @"(no NSError)"];
    }
    return pso;
}

id<MTLRenderPipelineState> mcmtl_pipeline_build_variant(MCMTLPipeline *pipeline, uint64_t key) {
    @autoreleasepool {
        NSString *failure = nil;
        id<MTLRenderPipelineState> pso = nil;
        @try {
            pso = mcmtl_build_variant_impl(pipeline, key, &failure);
        } @catch (NSException *exception) {
            failure = [NSString stringWithFormat:@"exception %@: %@", exception.name, exception.reason];
            pso = nil;
        }
        if (!pso && pipeline && pipeline->owner) {
            mcmtl_device_message(pipeline->owner, "mcmtl_pipeline_build_variant: %s", failure ? failure.UTF8String : "failed");
        }
        return pso;
    }
}

/* ---------------------------------------------------------------------------------------------- */
/* Pipelines                                                                                        */
/* ---------------------------------------------------------------------------------------------- */

static int32_t mcmtl_validate_pipeline_desc(MCMTLDevice *dev, const mcmtl_pipeline_desc *d) {
    const uint32_t knownFlags = MCMTL_PIPELINE_FLAG_HAS_DEPTH_STATE | MCMTL_PIPELINE_FLAG_SUPPORT_ICB;
    if (d->flags & ~knownFlags) {
        mcmtl_set_error("mcmtl_pipeline_create: unknown flags 0x%x", d->flags & ~knownFlags);
        return MCMTL_E_INVALID_ARGUMENT;
    }
    if (!d->vertexFunction) {
        mcmtl_set_error("mcmtl_pipeline_create: vertexFunction is 0");
        return MCMTL_E_INVALID_ARGUMENT;
    }
    if (MCMTL_FUNCTION(d->vertexFunction).functionType != MTLFunctionTypeVertex) {
        mcmtl_set_error("mcmtl_pipeline_create: vertexFunction is not a vertex function");
        return MCMTL_E_INVALID_ARGUMENT;
    }
    if (d->fragmentFunction && MCMTL_FUNCTION(d->fragmentFunction).functionType != MTLFunctionTypeFragment) {
        mcmtl_set_error("mcmtl_pipeline_create: fragmentFunction is not a fragment function");
        return MCMTL_E_INVALID_ARGUMENT;
    }
    if (d->primitiveType > MTLPrimitiveTypeTriangleStrip || d->topologyClass > MTLPrimitiveTopologyClassTriangle ||
        d->emulation > MCMTL_EMULATION_TRIANGLE_FAN || d->cullMode > MTLCullModeBack || d->fillMode > MTLTriangleFillModeLines ||
        d->frontFacing > MTLWindingCounterClockwise || d->depthCompare > MTLCompareFunctionAlways || d->depthWriteEnabled > 1) {
        mcmtl_set_error("mcmtl_pipeline_create: invalid raster/depth enum (primitive %u, topology %u, emulation %u, cull %u, "
                        "fill %u, winding %u, compare %u, write %u)", d->primitiveType, d->topologyClass, d->emulation, d->cullMode,
                        d->fillMode, d->frontFacing, d->depthCompare, d->depthWriteEnabled);
        return MCMTL_E_INVALID_ARGUMENT;
    }
    if (d->emulation == MCMTL_EMULATION_TRIANGLE_FAN && d->primitiveType != MTLPrimitiveTypeTriangle) {
        mcmtl_set_error("mcmtl_pipeline_create: fan emulation needs primitiveType Triangle");
        return MCMTL_E_INVALID_ARGUMENT;
    }
    if (d->pushConstantSize > MCMTL_MAX_PUSH_CONSTANT_BYTES || (d->pushConstantStages & ~(MCMTL_STAGE_VERTEX | MCMTL_STAGE_FRAGMENT))) {
        mcmtl_set_error("mcmtl_pipeline_create: push constants %u bytes, stages 0x%x", d->pushConstantSize, d->pushConstantStages);
        return MCMTL_E_INVALID_ARGUMENT;
    }
    if (d->colorTargetCount > MCMTL_MAX_COLOR_ATTACHMENTS || d->attributeCount > MCMTL_MAX_VERTEX_ATTRIBUTES ||
        d->layoutCount > MCMTL_MAX_VERTEX_BUFFERS || d->precompileDepthFormatCount > 4) {
        mcmtl_set_error("mcmtl_pipeline_create: counts out of range (targets %u, attributes %u, layouts %u, precompile %u)",
                        d->colorTargetCount, d->attributeCount, d->layoutCount, d->precompileDepthFormatCount);
        return MCMTL_E_INVALID_ARGUMENT;
    }
    for (uint32_t i = 0; i < d->colorTargetCount; i++) {
        const mcmtl_color_target *t = &d->colorTargets[i];
        if (t->pixelFormat == 0) continue;
        if (mcmtl_core_format_is_depth_stencil(t->pixelFormat)) {
            mcmtl_set_error("mcmtl_pipeline_create: color target %u has depth/stencil format %u", i, t->pixelFormat);
            return MCMTL_E_INVALID_ARGUMENT;
        }
        if (t->writeMask > MTLColorWriteMaskAll || t->blendingEnabled > 1 || t->rgbBlendOperation > MTLBlendOperationMax ||
            t->alphaBlendOperation > MTLBlendOperationMax || t->sourceRGBBlendFactor > MTLBlendFactorOneMinusSource1Alpha ||
            t->destinationRGBBlendFactor > MTLBlendFactorOneMinusSource1Alpha ||
            t->sourceAlphaBlendFactor > MTLBlendFactorOneMinusSource1Alpha ||
            t->destinationAlphaBlendFactor > MTLBlendFactorOneMinusSource1Alpha) {
            mcmtl_set_error("mcmtl_pipeline_create: color target %u has an invalid write mask / blend enum", i);
            return MCMTL_E_INVALID_ARGUMENT;
        }
        if (t->blendingEnabled && mcmtl_core_format_is_integer(t->pixelFormat)) {
            mcmtl_set_error("mcmtl_pipeline_create: color target %u: blending is not supported for integer format %u", i,
                            t->pixelFormat);
            return MCMTL_E_INVALID_ARGUMENT;
        }
    }
    /* Vertex input: attributes in [[buffer(15..30)]] (30 - slot), 4-byte aligned offsets, one layout per
     * referenced buffer index. */
    uint32_t layoutMask = 0;
    for (uint32_t i = 0; i < d->layoutCount; i++) {
        const mcmtl_vertex_layout *l = &d->layouts[i];
        const uint32_t first = MCMTL_VERTEX_BUFFER_INDEX_BASE + 1 - MCMTL_MAX_VERTEX_BUFFERS;   /* 15 */
        if (l->bufferIndex < first || l->bufferIndex > MCMTL_VERTEX_BUFFER_INDEX_BASE || (layoutMask & (1u << l->bufferIndex))) {
            mcmtl_set_error("mcmtl_pipeline_create: layout %u: buffer index %u invalid or duplicated (15..30)", i, l->bufferIndex);
            return MCMTL_E_INVALID_ARGUMENT;
        }
        if (l->stride == 0 || l->stride % 4 != 0 ||
            (l->stepFunction != MTLVertexStepFunctionPerVertex && l->stepFunction != MTLVertexStepFunctionPerInstance) ||
            l->stepRate == 0) {
            mcmtl_set_error("mcmtl_pipeline_create: layout %u: stride %u (multiple of 4, > 0), step function %u, step rate %u",
                            i, l->stride, l->stepFunction, l->stepRate);
            return MCMTL_E_INVALID_ARGUMENT;
        }
        layoutMask |= 1u << l->bufferIndex;
    }
    uint32_t locationMask = 0;
    for (uint32_t i = 0; i < d->attributeCount; i++) {
        const mcmtl_vertex_attribute *a = &d->attributes[i];
        if (a->location >= MCMTL_MAX_VERTEX_ATTRIBUTES || (locationMask & (1u << a->location))) {
            mcmtl_set_error("mcmtl_pipeline_create: attribute %u: location %u invalid or duplicated", i, a->location);
            return MCMTL_E_INVALID_ARGUMENT;
        }
        /* MTLVertexFormat: 1..42 and 45..55 (UChar .. FloatRGB9E5) in the macOS 27 SDK. */
        if (a->format == MTLVertexFormatInvalid || a->format == 43 || a->format == 44 || a->format > MTLVertexFormatFloatRGB9E5) {
            mcmtl_set_error("mcmtl_pipeline_create: attribute %u (location %u): invalid vertex format %u", i, a->location, a->format);
            return MCMTL_E_INVALID_ARGUMENT;
        }
        if (a->offset % 4 != 0) {
            mcmtl_set_error("mcmtl_pipeline_create: attribute %u (location %u): offset %u is not a multiple of 4", i, a->location,
                            a->offset);
            return MCMTL_E_INVALID_ARGUMENT;
        }
        if (a->bufferIndex > 30 || !(layoutMask & (1u << a->bufferIndex))) {
            mcmtl_set_error("mcmtl_pipeline_create: attribute %u (location %u): buffer index %u has no layout", i, a->location,
                            a->bufferIndex);
            return MCMTL_E_INVALID_ARGUMENT;
        }
        locationMask |= 1u << a->location;
    }
    /* Masks index the decoder's fixed-size shadow arrays: keep them in range. */
    for (uint32_t s = 0; s < 2; s++) {
        if ((d->bufferMask[s] & ~0x7FFFFFFFu) || (d->textureMask[s] & ~0xFFFFu) || (d->samplerMask[s] & ~0xFFFFu) ||
            (d->textureCubeMask[s] & ~d->textureMask[s]) || (d->texelBufferMask[s] & ~d->textureMask[s]) ||
            (d->textureCubeMask[s] & d->texelBufferMask[s])) {
            mcmtl_set_error("mcmtl_pipeline_create: stage %u masks out of range (buffers 0x%x, textures 0x%x, samplers 0x%x, "
                            "cube 0x%x, texel 0x%x)", s, d->bufferMask[s], d->textureMask[s], d->samplerMask[s],
                            d->textureCubeMask[s], d->texelBufferMask[s]);
            return MCMTL_E_INVALID_ARGUMENT;
        }
    }
    if (d->requiredVertexBufferMask & ~0x7FFF8000u) {
        mcmtl_set_error("mcmtl_pipeline_create: requiredVertexBufferMask 0x%x outside buffers 15..30", d->requiredVertexBufferMask);
        return MCMTL_E_INVALID_ARGUMENT;
    }
    for (uint32_t i = 0; i < d->precompileDepthFormatCount; i++) {
        uint32_t f = d->precompileDepthFormats[i];
        if (f != 0 && !mcmtl_core_format_is_depth_stencil(f)) {
            mcmtl_set_error("mcmtl_pipeline_create: precompileDepthFormats[%u] = %u is not a depth/stencil format", i, f);
            return MCMTL_E_INVALID_ARGUMENT;
        }
        if (f == MTLPixelFormatDepth24Unorm_Stencil8 && !dev->device.depth24Stencil8PixelFormatSupported) {
            mcmtl_set_error("mcmtl_pipeline_create: Depth24Unorm_Stencil8 is not supported on this GPU");
            return MCMTL_E_UNSUPPORTED;
        }
    }
    return MCMTL_OK;
}

MCMTL_API int32_t mcmtl_pipeline_create(mcmtl_handle device, const mcmtl_pipeline_desc *desc, mcmtl_handle *outPipeline) {
    MCMTL_TRY_BEGIN
        mcmtl_clear_error();
        if (outPipeline) *outPipeline = 0;
        if (!device || !desc || !outPipeline) {
            mcmtl_set_error("mcmtl_pipeline_create: NULL argument");
            return MCMTL_E_INVALID_ARGUMENT;
        }
        if (desc->structSize != sizeof(mcmtl_pipeline_desc)) {
            mcmtl_set_error("mcmtl_pipeline_create: structSize %u != %zu", desc->structSize, sizeof(mcmtl_pipeline_desc));
            return MCMTL_E_ABI_MISMATCH;
        }
        MCMTLDevice *dev = MCMTL_DEVICE(device);
        int32_t rc = mcmtl_validate_pipeline_desc(dev, desc);
        if (rc != MCMTL_OK) return rc;

        NSString *label = desc->label ? [NSString stringWithUTF8String:desc->label] : nil;
        MTLRenderPipelineDescriptor *t = [MTLRenderPipelineDescriptor new];
        t.vertexFunction = MCMTL_FUNCTION(desc->vertexFunction);
        t.fragmentFunction = desc->fragmentFunction ? MCMTL_FUNCTION(desc->fragmentFunction) : nil;
        if (label.length) t.label = label;
        t.rasterSampleCount = 1;
        t.alphaToCoverageEnabled = NO;
        t.alphaToOneEnabled = NO;
        t.rasterizationEnabled = YES;
        t.inputPrimitiveTopology = (MTLPrimitiveTopologyClass)desc->topologyClass;
        t.supportIndirectCommandBuffers = (desc->flags & MCMTL_PIPELINE_FLAG_SUPPORT_ICB) != 0;
        if (desc->attributeCount > 0) {
            MTLVertexDescriptor *vd = [MTLVertexDescriptor vertexDescriptor];
            for (uint32_t i = 0; i < desc->layoutCount; i++) {
                const mcmtl_vertex_layout *l = &desc->layouts[i];
                MTLVertexBufferLayoutDescriptor *layout = vd.layouts[l->bufferIndex];
                layout.stride = l->stride;
                layout.stepFunction = (MTLVertexStepFunction)l->stepFunction;
                layout.stepRate = l->stepRate;
            }
            for (uint32_t i = 0; i < desc->attributeCount; i++) {
                const mcmtl_vertex_attribute *a = &desc->attributes[i];
                MTLVertexAttributeDescriptor *attribute = vd.attributes[a->location];
                attribute.format = (MTLVertexFormat)a->format;
                attribute.offset = a->offset;
                attribute.bufferIndex = a->bufferIndex;
            }
            t.vertexDescriptor = vd;
        }
        uint32_t presentMask = 0;
        for (uint32_t i = 0; i < desc->colorTargetCount; i++) {
            const mcmtl_color_target *ct = &desc->colorTargets[i];
            MTLRenderPipelineColorAttachmentDescriptor *a = t.colorAttachments[i];
            a.pixelFormat = (MTLPixelFormat)ct->pixelFormat;
            if (ct->pixelFormat == 0) {
                a.blendingEnabled = NO;
                a.writeMask = MTLColorWriteMaskNone;
                continue;
            }
            presentMask |= 1u << i;
            a.writeMask = (MTLColorWriteMask)ct->writeMask;
            a.blendingEnabled = ct->blendingEnabled != 0;
            a.rgbBlendOperation = (MTLBlendOperation)ct->rgbBlendOperation;
            a.alphaBlendOperation = (MTLBlendOperation)ct->alphaBlendOperation;
            a.sourceRGBBlendFactor = (MTLBlendFactor)ct->sourceRGBBlendFactor;
            a.destinationRGBBlendFactor = (MTLBlendFactor)ct->destinationRGBBlendFactor;
            a.sourceAlphaBlendFactor = (MTLBlendFactor)ct->sourceAlphaBlendFactor;
            a.destinationAlphaBlendFactor = (MTLBlendFactor)ct->destinationAlphaBlendFactor;
        }

        MCMTLPipeline *p = [MCMTLPipeline new];
        p->owner = dev;
        p->descriptorTemplate = t;   /* never mutated from here on */
        p->label = label.length ? label : nil;
        p->colorTargetCount = desc->colorTargetCount;
        for (uint32_t i = 0; i < desc->colorTargetCount; i++) p->colorTargetFormats[i] = desc->colorTargets[i].pixelFormat;
        const BOOL hasDepthState = (desc->flags & MCMTL_PIPELINE_FLAG_HAS_DEPTH_STATE) != 0;
        p->depthStencilState = hasDepthState
            ? mcmtl_device_depth_stencil_state(dev, desc->depthCompare, desc->depthWriteEnabled != 0)
            : mcmtl_device_depth_stencil_state(dev, MTLCompareFunctionAlways, NO);
        if (!p->depthStencilState) {
            mcmtl_set_error("mcmtl_pipeline_create: depth-stencil state (compare %u, write %u) could not be created",
                            desc->depthCompare, desc->depthWriteEnabled);
            return MCMTL_E_OUT_OF_MEMORY;
        }
        p->primitiveType = desc->primitiveType;
        p->emulation = desc->emulation;
        p->cullMode = desc->cullMode;
        p->fillMode = desc->fillMode;
        p->frontFacing = desc->frontFacing;
        p->depthBiasConstant = desc->depthBiasConstant;
        p->depthBiasSlopeScale = desc->depthBiasSlopeScale;
        p->depthBiasClamp = desc->depthBiasClamp;
        p->pushConstantSize = desc->pushConstantSize;
        p->pushConstantStages = desc->pushConstantStages;
        for (uint32_t s = 0; s < 2; s++) {
            p->bufferMask[s] = desc->bufferMask[s];
            p->textureMask[s] = desc->textureMask[s];
            p->samplerMask[s] = desc->samplerMask[s];
            p->textureCubeMask[s] = desc->textureCubeMask[s];
            p->texelBufferMask[s] = desc->texelBufferMask[s];
        }
        p->requiredVertexBufferMask = desc->requiredVertexBufferMask;

        /* Precompile (all non-null targets, depth format f) for every requested f: depth formats first (pass 0),
         * then the depth-less variant (pass 1); see the precompile policy at the top of this file. */
        uint32_t built = 0, builtWithDepth = 0;
        NSString *firstFailure = nil;
        NSString *depthlessFailure = nil;
        for (uint32_t pass = 0; pass < 2; pass++) {
            for (uint32_t i = 0; i < desc->precompileDepthFormatCount; i++) {
                const uint32_t format = desc->precompileDepthFormats[i];
                if ((format == 0) != (pass == 1)) continue;
                const uint64_t key = mcmtl_variant_key(presentMask, format);
                if (mcmtl_pipeline_find_variant(p, key)) continue;   /* duplicate format */
                NSString *failure = nil;
                id<MTLRenderPipelineState> pso = mcmtl_build_variant_impl(p, key, &failure);
                if (!pso) {
                    if (format == 0) {
                        depthlessFailure = failure;
                    } else if (!firstFailure) {
                        firstFailure = failure;
                    }
                    continue;
                }
                mcmtl_pipeline_add_variant(p, key, pso);
                built++;
                if (format != 0) builtWithDepth++;
            }
        }
        if (firstFailure || (depthlessFailure && builtWithDepth == 0)) {
            NSString *reason = firstFailure ?: depthlessFailure;
            mcmtl_set_error("mcmtl_pipeline_create: %s", reason.UTF8String);
            return MCMTL_E_COMPILE_FAILED;
        }
        if (depthlessFailure) {
            mcmtl_device_message(dev, "pipeline '%s': depth-less variant unavailable (%s)", label.UTF8String ?: "",
                                 depthlessFailure.UTF8String);
        }
        (void)built;
        *outPipeline = mcmtl_handle_retain(p);
        return MCMTL_OK;
    MCMTL_TRY_END(MCMTL_E_OBJC_EXCEPTION)
}
