#import "mcmetal_farshader.h"
#import <CommonCrypto/CommonDigest.h>
#include <math.h>

static BOOL fail(NSError **error, NSString *reason) {
    if (error) *error = [NSError errorWithDomain:@"MCMTLFarShader" code:1
        userInfo:@{NSLocalizedDescriptionKey:reason}];
    return NO;
}

BOOL mcmtl_farshader_arena(uint64_t base, uint64_t count, MCMTLFarShaderArena *out, NSError **error) {
    if (error) *error = nil;
    if (out) *out = (MCMTLFarShaderArena){0};
    /* firstVertex and the final corner both fit uint; ID zero never wraps. */
    if (!out || !count || count > (1ull << 30) || base > UINT32_MAX || count > UINT32_MAX - base)
        return fail(error, @"Arena range is empty, unaddressable or overflows the nonzero uint ID space");
    *out = (MCMTLFarShaderArena){(uint32_t)base, (uint32_t)count};
    return YES;
}

BOOL mcmtl_farshader_item(MCMTLFarShaderArena arena, uint32_t first, uint32_t count, NSError **error) {
    MCMTLFarShaderArena checked;
    if (!mcmtl_farshader_arena(arena.faceBase, arena.faceCount, &checked, error)) return NO;
    if ((first & 3u) || !count || count > 64 || (uint64_t)(first >> 2u) + count > arena.faceCount)
        return fail(error, @"Cluster item is unaligned or outside its validated original arena face range");
    return YES;
}

@interface MCMTLFarShaderSource ()
- (instancetype)initWithVertex:(NSString *)vertex fragment:(NSString *)fragment
    layout:(MCMTLFarShaderLayout)layout layer:(MCMTLFarShaderLayer)layer;
@end
@implementation MCMTLFarShaderSource
- (instancetype)initWithVertex:(NSString *)vertex fragment:(NSString *)fragment
    layout:(MCMTLFarShaderLayout)layout layer:(MCMTLFarShaderLayer)layer {
    if ((self = [super init])) { _vertex = [vertex copy]; _fragment = [fragment copy]; _layout = layout; _layer = layer; }
    return self;
}
+ (BOOL)accessInstanceVariablesDirectly { return NO; }
@end

static NSUInteger occurrences(NSString *s, NSString *part) {
    return [s componentsSeparatedByString:part].count - 1;
}
static NSRegularExpression *regex(NSString *pattern) {
    return [NSRegularExpression regularExpressionWithPattern:pattern options:0 error:NULL];
}
static BOOL matches(NSString *s, NSString *pattern) {
    return [regex(pattern) numberOfMatchesInString:s options:0 range:NSMakeRange(0, s.length)] != 0;
}

static BOOL supported_vertex_descriptor(MTLVertexDescriptor *d) {
    // The real game retains BLOCK + CHUNK_DATA's descriptor after replacing
    // the vertex function with manual pulling. Full-source admission below
    // proves no stage_in attributes are consumed. Preserve that descriptor;
    // accept only this known layout or Metal's materialized empty descriptor.
    BOOL empty = YES;
    for (NSUInteger i = 0; i < 31; ++i)
        if (d.attributes[i].format != MTLVertexFormatInvalid || d.layouts[i].stride != 0) empty = NO;
    if (empty) return YES;
    const MTLVertexFormat formats[] = {MTLVertexFormatFloat3, MTLVertexFormatUChar4Normalized,
        MTLVertexFormatFloat2, MTLVertexFormatShort2, MTLVertexFormatInt3, MTLVertexFormatFloat};
    const NSUInteger offsets[] = {0, 12, 16, 24, 0, 12};
    for (NSUInteger i = 0; i < 31; ++i) {
        if (i < 6) {
            if (d.attributes[i].format != formats[i] || d.attributes[i].offset != offsets[i]
                || d.attributes[i].bufferIndex != (i < 4 ? 30u : 29u)) return NO;
        } else if (d.attributes[i].format != MTLVertexFormatInvalid) return NO;
        if (i == 29 || i == 30) {
            if (d.layouts[i].stride != (i == 29 ? 16u : 28u) || d.layouts[i].stepRate != 1
                || d.layouts[i].stepFunction != (i == 29 ? MTLVertexStepFunctionPerInstance : MTLVertexStepFunctionPerVertex)) return NO;
        } else if (d.layouts[i].stride != 0) return NO;
    }
    return YES;
}

/* Complete source hashes, not a few marker substrings: an altered helper cannot
 * silently pass the same fetch/return anchors. Canonicalization changes ONLY
 * entry name and consistently renamed translator numeric identifiers. It does
 * not strip comments, whitespace, preprocessor code, statements or constants.
 * Bounded input/identifier counts keep admission work independent of terrain.
 */
static NSString *fingerprint(NSString *s, NSString *entry) {
    NSString *pat = [NSString stringWithFormat:@"\\b%@\\b", [NSRegularExpression escapedPatternForString:entry]];
    s = [regex(pat) stringByReplacingMatchesInString:s options:0 range:NSMakeRange(0, s.length)
        withTemplate:@"main0"];
    NSArray<NSTextCheckingResult *> *ids = [regex(@"\\b_[0-9]+\\b") matchesInString:s options:0 range:NSMakeRange(0, s.length)];
    if (ids.count > 8192) return nil;
    NSMutableDictionary<NSString *, NSString *> *names = [NSMutableDictionary dictionary];
    NSMutableString *normalized = [NSMutableString string];
    NSUInteger end = 0;
    for (NSTextCheckingResult *m in ids) {
        [normalized appendString:[s substringWithRange:NSMakeRange(end, m.range.location - end)]];
        NSString *name = [s substringWithRange:m.range], *replacement = names[name];
        if (!replacement) names[name] = replacement = [NSString stringWithFormat:@"_%lu", (unsigned long)names.count];
        [normalized appendString:replacement];
        end = NSMaxRange(m.range);
    }
    [normalized appendString:[s substringFromIndex:end]];
    NSData *bytes = [normalized dataUsingEncoding:NSUTF8StringEncoding];
    unsigned char digest[CC_SHA256_DIGEST_LENGTH];
    CC_SHA256(bytes.bytes, (CC_LONG)bytes.length, digest);
    NSMutableString *hex = [NSMutableString stringWithCapacity:64];
    for (unsigned i = 0; i < sizeof digest; ++i) [hex appendFormat:@"%02x", digest[i]];
    return hex;
}

typedef struct { const char *sha; uint32_t layout, layer, vary; } SourceShape;
/* Generated from the full current TerrainVertexMsl clusterVertexLod hand body
 * and TerrainShaderVariants pairedVariant text, independently of ID insertion.
 * vary: 0 original float, 1 vfog, 2 vfog+hvary. Vertex layer is unused; fragment
 * layout is unused (both layouts share the same original shaded fragment).
 * Each fragment group covers runtime UseRgss, literal false, literal true.
 * The test embeds the full unfaded built-in sources; no build/ capture dependency.
 */
static const SourceShape vertexShapes[] = {
    {"758ca55c5805ae4eced9701342ad05b62bf3193f94465846ffb436219ea801ed", 1, 0, 0},
    {"fc99cfbb509afbdfefa598771f175001c99cb5cc87236168275ce291297f7106", 1, 0, 1},
    {"8c4fb70d574647db5a6e7f8c01411bb9bffdb54ed78762dca78613ea13d54b05", 1, 0, 2},
    {"605b241c84287a7e573aef3cb27224a0499e8680b32f05e88dbf0aca67a53592", 2, 0, 0},
    {"0f594547fac4bf15bddecbf7ab6cf0741b7b8eb5bcde9b1cd36f4155892c97ee", 2, 0, 1},
    {"2da92da85e8f0211838cdb7e0b76371af6f81d09a9ecc88ab90ee797581bb2e7", 2, 0, 2},
};
static const SourceShape fragmentShapes[] = {
    {"19c47309a72e163567b99cdd1a00fba8b88da89fa3b24b17b5a4e89c23cb9217", 0, 0, 0},
    {"ef9e678e84e848df11964b35ba9c2f1f42a76fc8d4ae083a5f7a0106990b3cc8", 0, 0, 0},
    {"6d584a83774f332b2bc064f898d0ad72e147764dcc04e1d01b422e8262468456", 0, 0, 0},
    {"fe3e524620af63e5674ac449e35b11efae00a1868d1ceaa789d63bf9d58ebe78", 0, 0, 1},
    {"af48ab8cca9850e570657cbacd1925b8f9b6843fb27867b8690166b18fa8c382", 0, 0, 1},
    {"9af219ba6720eb296f24b2e8a1acb053d6a391763af8cc2cf692332d810cc942", 0, 0, 1},
    {"3729827c71eaf42d295a9ee3ace9eb005b00fff86bf3f3341d79ed5b47e75c91", 0, 0, 2},
    {"d2365bf8c70a6b700aafb07c65819dd237a7e8c7b08d799731dc1c2138a6acb1", 0, 0, 2},
    {"33a1c24f0f86816920dc525a05bd239afcd01caf40ff92fc521e5483039cadb7", 0, 0, 2},
    {"d4138ed314cc783689ef6e36269425632ea0f0f85b7a96bba7f334408382b2a6", 0, 1, 0},
    {"3b60abc5b8b634bd7298c3ab7269bc808add2fbabce8d0ef8544aacde287f2c3", 0, 1, 0},
    {"7ccfdb9006dfb68c9bff4383019475dab68650db803a8b2036d7df0d0afcceac", 0, 1, 0},
    {"f6c7ab0bb0a4055172fecdcf5fcc80db4effa8a273b981de1e6863ab12514201", 0, 1, 1},
    {"9e423a28578cb3ae867bfed4869d528968957b722156954001fd44cf5487eee5", 0, 1, 1},
    {"b3f1189da79dd0addb5bceb65f9ebc6b72cee3eb868bed38e544f029cd339d50", 0, 1, 1},
    {"868aa2da07924b64a237a2645c89080f7de582f70f653b20045a5381d26386e0", 0, 1, 2},
    {"b4a238f24d2e56dbf1884eeb95c079f182ff0fe040b6f7b5f58fa0f572ee093a", 0, 1, 2},
    {"3f3b728f1baab30269580dd1256fb0bf210a5a39256fff236d6d877115a4935a", 0, 1, 2},
};

MCMTLFarShaderSource *mcmtl_farshader_rewrite(NSString *vertex, NSString *fragment,
    NSString *ve, NSString *fe, MCMTLFarShaderLayout layout, MCMTLFarShaderLayer layer, NSError **error) {
    if (error) *error = nil;
    if (!vertex.length || !fragment.length || vertex.length > 131072 || fragment.length > 131072 ||
        !ve.length || !fe.length || ve.length > 128 || fe.length > 128 ||
        !matches(ve, @"\\A[A-Za-z_][A-Za-z0-9_]*\\z") || !matches(fe, @"\\A[A-Za-z_][A-Za-z0-9_]*\\z") ||
        (layout != MCMTLFarShaderBlock && layout != MCMTLFarShaderCompact) ||
        (layer != MCMTLFarShaderSolid && layer != MCMTLFarShaderCutout)) {
        fail(error, @"Expected bounded original LOD sources, identifiers and a known opaque layout/layer"); return nil;
    }
    for (NSString *s in @[vertex, fragment]) {
        if (matches(s, @"locn\\s*6\\b|mcmtl_far_|capturedFace") ||
            [s containsString:@"lodB"] || [s containsString:@"LOD_FADE"] || [s containsString:@"gl_FragCoord"]) {
            fail(error, @"Occupied ID varying/name, or faded/screen-space shader (guard origin unsupported)"); return nil;
        }
    }
    if (matches(vertex, @"\\[\\[\\s*buffer\\s*\\(\\s*6\\s*\\)") ||
        [vertex containsString:@"[[stage_in]]"]) {
        fail(error, @"Vertex buffer 6 occupied or shader is not an exact cluster pull"); return nil;
    }
    NSString *vh = fingerprint(vertex, ve), *fh = fingerprint(fragment, fe);
    const SourceShape *v = NULL, *f = NULL;
    for (NSUInteger i = 0; i < sizeof vertexShapes / sizeof *vertexShapes; ++i)
        if (vertexShapes[i].layout == layout && [vh isEqualToString:@(vertexShapes[i].sha)]) v = &vertexShapes[i];
    for (NSUInteger i = 0; i < sizeof fragmentShapes / sizeof *fragmentShapes; ++i)
        if (fragmentShapes[i].layer == layer && [fh isEqualToString:@(fragmentShapes[i].sha)]) f = &fragmentShapes[i];
    if (!v || !f || v->vary != f->vary) {
        fail(error, @"Unrecognized complete built-in source, layout/layer mismatch or unpaired varying variant"); return nil;
    }
    NSString *vsig = [NSString stringWithFormat:@"vertex main0_out %@(", ve];
    NSString *fsig = [NSString stringWithFormat:@"fragment main0_out %@(", fe];
    NSString *item = @"    uint2 mcmtl_item = mcmtl_items[mcmtl_e >> 6u];";
    NSString *ret = @"    return out;";
    /* Even admitted whole-stage forms must have exactly one insertion site. */
    for (NSString *s in @[vertex, fragment]) {
        if (occurrences(s, @"struct main0_out\n{") != 1 || occurrences(s, @"struct main0_in\n{") != 1 || occurrences(s, ret) != 1) {
            fail(error, @"Ambiguous shader struct/return insertion point"); return nil;
        }
    }
    if (occurrences(vertex, vsig) != 1 || occurrences(fragment, fsig) != 1 || occurrences(vertex, item) != 1) {
        fail(error, @"Expected exactly one original entry point and cluster item fetch"); return nil;
    }
    NSRange start = [vertex rangeOfString:vsig];
    NSRange end = [vertex rangeOfString:@")\n{" options:0 range:NSMakeRange(start.location, vertex.length - start.location)];
    if (end.location == NSNotFound) { fail(error, @"Unknown vertex signature terminator"); return nil; }
    vertex = [vertex stringByReplacingCharactersInRange:NSMakeRange(end.location, 0)
        withString:@", constant uint& mcmtl_far_arena [[buffer(6)]]"];
    vertex = [vertex stringByReplacingOccurrencesOfString:@"struct main0_out\n{" withString:
        @"struct main0_out\n{\n    uint mcmtl_far_id [[user(locn6), flat]];"];
    vertex = [vertex stringByReplacingOccurrencesOfString:item withString:[item stringByAppendingString:
        @"\n    out.mcmtl_far_id = 1u + mcmtl_far_arena + (mcmtl_item.x >> 2u) + (mcmtl_e & 63u);"]];
    fragment = [fragment stringByReplacingOccurrencesOfString:@"struct main0_in\n{" withString:
        @"struct main0_in\n{\n    uint mcmtl_far_id [[user(locn6), flat]];"];
    fragment = [fragment stringByReplacingOccurrencesOfString:@"struct main0_out\n{" withString:
        @"struct main0_out\n{\n    uint mcmtl_far_id [[color(1)]];"];
    fragment = [fragment stringByReplacingOccurrencesOfString:ret withString:
        @"    out.mcmtl_far_id = in.mcmtl_far_id;\n    return out;"];
    return [[MCMTLFarShaderSource alloc] initWithVertex:vertex fragment:fragment layout:layout layer:layer];
}

@interface MCMTLFarShader ()
- (instancetype)initWithPipeline:(MCMTLPipeline *)original state:(id<MTLRenderPipelineState>)state
    descriptor:(MTLRenderPipelineDescriptor *)descriptor layout:(MCMTLFarShaderLayout)layout layer:(MCMTLFarShaderLayer)layer
    vertex:(NSString *)vertex fragment:(NSString *)fragment vertexEntry:(NSString *)vertexEntry
    fragmentEntry:(NSString *)fragmentEntry options:(MTLCompileOptions *)options;
@end
@implementation MCMTLFarShader {
    MTLCompileOptions *_originalOptions;
}
- (instancetype)initWithPipeline:(MCMTLPipeline *)p state:(id<MTLRenderPipelineState>)state
    descriptor:(MTLRenderPipelineDescriptor *)d layout:(MCMTLFarShaderLayout)layout layer:(MCMTLFarShaderLayer)layer
    vertex:(NSString *)vertex fragment:(NSString *)fragment vertexEntry:(NSString *)vertexEntry
    fragmentEntry:(NSString *)fragmentEntry options:(MTLCompileOptions *)options {
    if ((self = [super init])) {
        _pipelineState = state; _depthStencilState = p->depthStencilState;
        _layout = layout; _layer = layer;
        _originalVertex=[vertex copy]; _originalFragment=[fragment copy];
        _originalVertexEntry=[vertexEntry copy]; _originalFragmentEntry=[fragmentEntry copy];
        _originalOptions=[options copy];
        _colorFormat = d.colorAttachments[0].pixelFormat; _depthFormat = d.depthAttachmentPixelFormat;
        _raster = (MCMTLFarShaderRaster){p->primitiveType, p->cullMode, p->fillMode, p->frontFacing,
            p->depthBiasConstant, p->depthBiasSlopeScale, p->depthBiasClamp, p->pushConstantSize, p->pushConstantStages};
        for (unsigned s = 0; s < 2; ++s) {
            _bindings.bufferMask[s] = p->bufferMask[s]; _bindings.textureMask[s] = p->textureMask[s];
            _bindings.samplerMask[s] = p->samplerMask[s]; _bindings.textureCubeMask[s] = p->textureCubeMask[s];
            _bindings.texelBufferMask[s] = p->texelBufferMask[s];
        }
        _bindings.bufferMask[0] |= 1u << MCMTLFarShaderArenaBuffer;
        _bindings.requiredVertexBufferMask = p->requiredVertexBufferMask | (1u << MCMTLFarShaderArenaBuffer);
    }
    return self;
}
- (MTLCompileOptions *)copyOriginalCompileOptions { return [_originalOptions copy]; }
+ (BOOL)accessInstanceVariablesDirectly { return NO; }
@end

MCMTLFarShader *mcmtl_farshader_compile(MCMTLPipeline *p, uint64_t key,
    NSString *vertex, NSString *fragment, NSString *ve, NSString *fe, MTLCompileOptions *options,
    MCMTLFarShaderLayout layout, MCMTLFarShaderLayer layer, NSError **error) {
    if (error) *error = nil;
    @try {
        if ([NSThread isMainThread]) { fail(error, @"Far shader compilation is worker-only"); return nil; }
        uint64_t plain = key & ~MCMTL_VARIANT_KEY_BGRA8_COLOR0;
        if (!p || !p->owner || !p->owner->device || !p->descriptorTemplate || !p->depthStencilState || !options ||
            plain != mcmtl_variant_key(1, MTLPixelFormatDepth32Float)) {
            fail(error, @"Missing original pipeline/options or unsupported attachment/ICB variant"); return nil;
        }
        MTLRenderPipelineDescriptor *d = [p->descriptorTemplate copy];
        if (!d.vertexFunction || !d.fragmentFunction || ![d.vertexFunction.name isEqualToString:ve] ||
            ![d.fragmentFunction.name isEqualToString:fe] || d.vertexFunction.device != p->owner->device ||
            d.fragmentFunction.device != p->owner->device ||
            p->colorTargetCount != 1 || p->colorTargetFormats[0] != d.colorAttachments[0].pixelFormat ||
            (p->colorTargetFormats[0] != MTLPixelFormatRGBA8Unorm && p->colorTargetFormats[0] != MTLPixelFormatBGRA8Unorm) ||
            d.colorAttachments[0].blendingEnabled || !supported_vertex_descriptor(d.vertexDescriptor) || d.rasterSampleCount != 1 || !d.rasterizationEnabled ||
            (d.inputPrimitiveTopology != MTLPrimitiveTopologyClassTriangle && d.inputPrimitiveTopology != MTLPrimitiveTopologyClassUnspecified)
            || p->primitiveType != MTLPrimitiveTypeTriangle ||
            p->emulation != MCMTL_EMULATION_NONE || p->fillMode != MTLTriangleFillModeFill ||
            ((p->bufferMask[0] | p->requiredVertexBufferMask) & (1u << MCMTLFarShaderArenaBuffer)) ||
            [p->label.lowercaseString containsString:@"translucent"] ||
            [p->label.lowercaseString containsString:@"_fade_"] ||
            !isfinite(p->depthBiasConstant) || !isfinite(p->depthBiasSlopeScale) || !isfinite(p->depthBiasClamp)) {
            fail(error, [NSString stringWithFormat:@"Unsupported original pipeline state: entries=%@/%@ device=%d targets=%u format=%lu/%u blend=%d vertexDescriptor=%@ samples=%lu raster=%d topology=%lu/%u emulation=%u fill=%u masks=%x/%x label=%@",
                d.vertexFunction.name, d.fragmentFunction.name,
                d.vertexFunction.device == p->owner->device && d.fragmentFunction.device == p->owner->device,
                p->colorTargetCount, (unsigned long)d.colorAttachments[0].pixelFormat, p->colorTargetFormats[0],
                d.colorAttachments[0].blendingEnabled, d.vertexDescriptor, (unsigned long)d.rasterSampleCount,
                d.rasterizationEnabled, (unsigned long)d.inputPrimitiveTopology, p->primitiveType,
                p->emulation, p->fillMode, p->bufferMask[0], p->requiredVertexBufferMask, p->label]); return nil;
        }
        for (NSUInteger i = 1; i < MCMTL_MAX_COLOR_ATTACHMENTS; ++i) {
            if (d.colorAttachments[i].pixelFormat != MTLPixelFormatInvalid || d.colorAttachments[i].blendingEnabled) {
                fail(error, @"Original pipeline already uses another color attachment"); return nil;
            }
        }
        MCMTLFarShaderSource *source = mcmtl_farshader_rewrite(vertex, fragment, ve, fe, layout, layer, error);
        if (!source) return nil;
        /* Options are caller-owned provenance, copied before either compilation.
         * Preprocessor/function-constant specialization could defeat source
         * admission; these built-in forms use neither. No guessed math mode. */
        MTLCompileOptions *copy = [options copy];
        if (copy.preprocessorMacros.count || d.vertexFunction.functionConstantsDictionary.count ||
            d.fragmentFunction.functionConstantsDictionary.count) {
            fail(error, @"Unrecognized preprocessor or function-constant specialization"); return nil;
        }
        NSError *underlying = nil;
        id<MTLDevice> device = p->owner->device;
        id<MTLLibrary> vl = [device newLibraryWithSource:source.vertex options:copy error:&underlying];
        if (!vl) { fail(error, [NSString stringWithFormat:@"ID vertex compile: %@", underlying]); return nil; }
        id<MTLLibrary> fl = [device newLibraryWithSource:source.fragment options:copy error:&underlying];
        if (!fl) { fail(error, [NSString stringWithFormat:@"ID fragment compile: %@", underlying]); return nil; }
        d.vertexFunction = [vl newFunctionWithName:ve]; d.fragmentFunction = [fl newFunctionWithName:fe];
        if (!d.vertexFunction || !d.fragmentFunction) { fail(error, @"Compiled ID entry point missing"); return nil; }
        if (key & MCMTL_VARIANT_KEY_BGRA8_COLOR0) d.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;
        d.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
        d.stencilAttachmentPixelFormat = MTLPixelFormatInvalid;
        d.colorAttachments[1].pixelFormat = MTLPixelFormatR32Uint;
        d.colorAttachments[1].blendingEnabled = NO;
        d.colorAttachments[1].writeMask = MTLColorWriteMaskAll;
        id<MTLRenderPipelineState> state = [device newRenderPipelineStateWithDescriptor:d error:&underlying];
        if (!state) { fail(error, [NSString stringWithFormat:@"ID pipeline compile: %@", underlying]); return nil; }
        return [[MCMTLFarShader alloc] initWithPipeline:p state:state descriptor:d layout:layout layer:layer
            vertex:vertex fragment:fragment vertexEntry:ve fragmentEntry:fe options:copy];
    } @catch (NSException *exception) {
        fail(error, [NSString stringWithFormat:@"Far shader exception: %@", exception.reason]); return nil;
    }
}
