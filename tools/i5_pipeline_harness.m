/*
 * i5_pipeline_harness.m -- TEST ONLY (owner: I5). Linked by tools/i5-pipeline-test.sh into
 * build/i5test/libmcmetal_i5test.dylib together with every .m file of native/src EXCEPT mcmetal_pipeline.m, so
 * that I5's Java pipeline code (MetalRenderPipeline.compile -> ShaderCache -> mcmtl_shader_create ->
 * mcmtl_pipeline_create) runs end to end against a real Metal device before I1's implementation lands.
 *
 * It provides:
 *  - a minimal reference implementation of the three I1 pipeline entry points, written from the
 *    TODO(I1) notes in mcmetal_pipeline.m and CONTRACT.md 7 (it is never shipped: build-client.sh only
 *    compiles native/src);
 *  - i5_device_create: an MCMTLDevice built with mcmtl_device_context_create (the I2 test recipe);
 *  - i5_pipeline_reflect: builds a variant with MTLPipelineOptionBindingInfo and reports, per stage, which
 *    Metal buffer/texture/sampler indices the functions really use, next to the masks Java put into
 *    mcmtl_pipeline_desc (the decoder binds and dummies exactly those);
 *  - i5_render_smoke: draws once with every masked slot bound the way the decoder will (CONTRACT.md 6.4
 *    dummies, a zero vertex buffer per layout) -- under MTL_DEBUG_LAYER=1 any missing binding, wrong
 *    texture type or vertex-descriptor mismatch aborts;
 *  - i5_render: draws real vertex data with caller-provided UBO contents into an RGBA8 + Depth32Float
 *    target and reads the pixels back (orientation, winding, culling, blending checks).
 */
#import "mcmetal_internal.h"

#define I5_API MCMTL_API

/* ---------------------------------------------------------------------------------------------- */
/* Reference I1 pipeline exports (test only)                                                      */
/* ---------------------------------------------------------------------------------------------- */

#ifdef I5_HARNESS_REFERENCE_PIPELINE /* only with tools/i5-pipeline-test.sh --reference-pipeline (the real I1 code is the default) */
MCMTL_API int32_t mcmtl_shader_create(mcmtl_handle device, const mcmtl_shader_desc *desc, mcmtl_handle *outFunction) {
    MCMTL_TRY_BEGIN
        if (outFunction) *outFunction = 0;
        if (!device || !desc || !outFunction || !desc->source || !desc->entryPoint) {
            mcmtl_set_error("mcmtl_shader_create: invalid argument");
            return MCMTL_E_INVALID_ARGUMENT;
        }
        if (desc->structSize != sizeof(mcmtl_shader_desc)) {
            mcmtl_set_error("mcmtl_shader_create: structSize %u != %zu", desc->structSize, sizeof(mcmtl_shader_desc));
            return MCMTL_E_ABI_MISMATCH;
        }
        MCMTLDevice *dev = MCMTL_DEVICE(device);
        NSString *source = [[NSString alloc] initWithBytes:desc->source length:(NSUInteger)desc->sourceLength encoding:NSUTF8StringEncoding];
        if (!source) {
            mcmtl_set_error("mcmtl_shader_create: source is not UTF-8");
            return MCMTL_E_INVALID_ARGUMENT;
        }
        MTLCompileOptions *options = [MTLCompileOptions new];
        options.languageVersion = (MTLLanguageVersion)desc->languageVersion;
        if (desc->flags & MCMTL_SHADER_FLAG_PRESERVE_INVARIANCE) options.preserveInvariance = YES;
        if (desc->flags & MCMTL_SHADER_FLAG_SAFE_MATH) options.mathMode = MTLMathModeSafe;
        NSError *error = nil;
        id<MTLLibrary> library = [dev->device newLibraryWithSource:source options:options error:&error];
        if (!library) {
            mcmtl_set_error_nserror("newLibraryWithSource", error);
            return MCMTL_E_COMPILE_FAILED;
        }
        id<MTLFunction> function = [library newFunctionWithName:[NSString stringWithUTF8String:desc->entryPoint]];
        if (!function) {
            mcmtl_set_error("mcmtl_shader_create: no function named %s", desc->entryPoint);
            return MCMTL_E_COMPILE_FAILED;
        }
        if (desc->label) function.label = [NSString stringWithUTF8String:desc->label];
        *outFunction = mcmtl_handle_retain(function);
        return MCMTL_OK;
    MCMTL_TRY_END(MCMTL_E_OBJC_EXCEPTION)
}
#endif /* I5_HARNESS_REFERENCE_PIPELINE */

static BOOL i5_has_stencil(uint32_t format) {
    return format == MTLPixelFormatStencil8 || format == MTLPixelFormatDepth32Float_Stencil8 || format == 255u /* Depth24Unorm_Stencil8 */;
}

static MTLRenderPipelineDescriptor *i5_variant_descriptor(MCMTLPipeline *p, uint64_t key) {
    MTLRenderPipelineDescriptor *d = [p->descriptorTemplate copy];
    uint32_t mask = (uint32_t)(key & 0xFFu);
    uint32_t depth = (uint32_t)(key >> 8);
    for (uint32_t i = 0; i < MCMTL_MAX_COLOR_ATTACHMENTS; i++) {
        BOOL present = i < p->colorTargetCount && (mask & (1u << i)) && p->colorTargetFormats[i] != 0;
        d.colorAttachments[i].pixelFormat = present ? (MTLPixelFormat)p->colorTargetFormats[i] : MTLPixelFormatInvalid;
        if (!present) {
            d.colorAttachments[i].blendingEnabled = NO;
        }
    }
    d.depthAttachmentPixelFormat = (MTLPixelFormat)depth;
    d.stencilAttachmentPixelFormat = i5_has_stencil(depth) ? (MTLPixelFormat)depth : MTLPixelFormatInvalid;
    return d;
}

#ifdef I5_HARNESS_REFERENCE_PIPELINE
id<MTLRenderPipelineState> mcmtl_pipeline_build_variant(MCMTLPipeline *p, uint64_t key) {
    if (!p || !p->owner) return nil;
    @autoreleasepool {
        NSError *error = nil;
        id<MTLRenderPipelineState> pso = [p->owner->device newRenderPipelineStateWithDescriptor:i5_variant_descriptor(p, key) error:&error];
        if (!pso) {
            mcmtl_set_error_nserror("newRenderPipelineState", error);
            mcmtl_device_message(p->owner, "variant 0x%llx of %s failed: %s", (unsigned long long)key, p->label.UTF8String ?: "?",
                                 error.localizedDescription.UTF8String ?: "?");
        }
        return pso;
    }
}

MCMTL_API int32_t mcmtl_pipeline_create(mcmtl_handle device, const mcmtl_pipeline_desc *d, mcmtl_handle *outPipeline) {
    MCMTL_TRY_BEGIN
        if (outPipeline) *outPipeline = 0;
        if (!device || !d || !outPipeline) {
            mcmtl_set_error("mcmtl_pipeline_create: invalid argument");
            return MCMTL_E_INVALID_ARGUMENT;
        }
        if (d->structSize != sizeof(mcmtl_pipeline_desc)) {
            mcmtl_set_error("mcmtl_pipeline_create: structSize %u != %zu", d->structSize, sizeof(mcmtl_pipeline_desc));
            return MCMTL_E_ABI_MISMATCH;
        }
        if (!d->vertexFunction || !d->fragmentFunction || d->colorTargetCount > MCMTL_MAX_COLOR_ATTACHMENTS
            || d->attributeCount > MCMTL_MAX_VERTEX_ATTRIBUTES || d->layoutCount > MCMTL_MAX_VERTEX_BUFFERS
            || d->precompileDepthFormatCount > 4) {
            mcmtl_set_error("mcmtl_pipeline_create: invalid descriptor");
            return MCMTL_E_INVALID_ARGUMENT;
        }
        MCMTLDevice *dev = MCMTL_DEVICE(device);
        MTLRenderPipelineDescriptor *t = [MTLRenderPipelineDescriptor new];
        t.vertexFunction = MCMTL_FUNCTION(d->vertexFunction);
        t.fragmentFunction = MCMTL_FUNCTION(d->fragmentFunction);
        if (d->label) t.label = [NSString stringWithUTF8String:d->label];
        MTLVertexDescriptor *vd = [MTLVertexDescriptor vertexDescriptor];
        for (uint32_t i = 0; i < d->attributeCount; i++) {
            const mcmtl_vertex_attribute *a = &d->attributes[i];
            vd.attributes[a->location].format = (MTLVertexFormat)a->format;
            vd.attributes[a->location].offset = a->offset;
            vd.attributes[a->location].bufferIndex = a->bufferIndex;
        }
        for (uint32_t i = 0; i < d->layoutCount; i++) {
            const mcmtl_vertex_layout *l = &d->layouts[i];
            vd.layouts[l->bufferIndex].stride = l->stride;
            vd.layouts[l->bufferIndex].stepFunction = (MTLVertexStepFunction)l->stepFunction;
            vd.layouts[l->bufferIndex].stepRate = l->stepRate;
        }
        t.vertexDescriptor = vd;
        t.inputPrimitiveTopology = (MTLPrimitiveTopologyClass)d->topologyClass;
        t.rasterSampleCount = 1;
        if (d->flags & MCMTL_PIPELINE_FLAG_SUPPORT_ICB) t.supportIndirectCommandBuffers = YES;
        uint32_t presentMask = 0;
        MCMTLPipeline *p = [MCMTLPipeline new];
        p->owner = dev;
        p->label = t.label;
        p->colorTargetCount = d->colorTargetCount;
        for (uint32_t i = 0; i < d->colorTargetCount; i++) {
            const mcmtl_color_target *c = &d->colorTargets[i];
            p->colorTargetFormats[i] = c->pixelFormat;
            if (c->pixelFormat == 0) continue;
            presentMask |= 1u << i;
            MTLRenderPipelineColorAttachmentDescriptor *ca = t.colorAttachments[i];
            ca.writeMask = (MTLColorWriteMask)c->writeMask;
            ca.blendingEnabled = c->blendingEnabled != 0;
            ca.rgbBlendOperation = (MTLBlendOperation)c->rgbBlendOperation;
            ca.alphaBlendOperation = (MTLBlendOperation)c->alphaBlendOperation;
            ca.sourceRGBBlendFactor = (MTLBlendFactor)c->sourceRGBBlendFactor;
            ca.destinationRGBBlendFactor = (MTLBlendFactor)c->destinationRGBBlendFactor;
            ca.sourceAlphaBlendFactor = (MTLBlendFactor)c->sourceAlphaBlendFactor;
            ca.destinationAlphaBlendFactor = (MTLBlendFactor)c->destinationAlphaBlendFactor;
        }
        p->descriptorTemplate = t;
        p->depthStencilState = mcmtl_device_depth_stencil_state(dev, d->depthCompare, d->depthWriteEnabled != 0);
        p->primitiveType = d->primitiveType;
        p->emulation = d->emulation;
        p->cullMode = d->cullMode;
        p->fillMode = d->fillMode;
        p->frontFacing = d->frontFacing;
        p->depthBiasConstant = d->depthBiasConstant;
        p->depthBiasSlopeScale = d->depthBiasSlopeScale;
        p->depthBiasClamp = d->depthBiasClamp;
        p->pushConstantSize = d->pushConstantSize;
        p->pushConstantStages = d->pushConstantStages;
        for (int s = 0; s < 2; s++) {
            p->bufferMask[s] = d->bufferMask[s];
            p->textureMask[s] = d->textureMask[s];
            p->samplerMask[s] = d->samplerMask[s];
            p->textureCubeMask[s] = d->textureCubeMask[s];
            p->texelBufferMask[s] = d->texelBufferMask[s];
        }
        p->requiredVertexBufferMask = d->requiredVertexBufferMask;
        for (uint32_t i = 0; i < d->precompileDepthFormatCount; i++) {
            uint64_t key = mcmtl_variant_key(presentMask, d->precompileDepthFormats[i]);
            id<MTLRenderPipelineState> pso = mcmtl_pipeline_build_variant(p, key);
            if (!pso) return MCMTL_E_COMPILE_FAILED; /* last error set by build_variant */
            mcmtl_pipeline_add_variant(p, key, pso);
        }
        *outPipeline = mcmtl_handle_retain(p);
        return MCMTL_OK;
    MCMTL_TRY_END(MCMTL_E_OBJC_EXCEPTION)
}

#endif /* I5_HARNESS_REFERENCE_PIPELINE */

/* ---------------------------------------------------------------------------------------------- */
/* Test helpers                                                                                   */
/* ---------------------------------------------------------------------------------------------- */

static id<MTLBuffer> g_zeroBuffer;       /* 64 KiB zeros: dummy UBO, push constants, vertex data */
static id<MTLTexture> g_dummy2D, g_dummyCube, g_dummyTexel;
static id<MTLSamplerState> g_dummySampler;

/* Creates a device context (+1 MCMTLDevice) and the decoder-style dummies the smoke render binds.
 * *outStatusPage receives the status page address (MtlNative.statusPage). */
I5_API int32_t i5_device_create(mcmtl_handle *outDevice, uint64_t *outStatusPage) {
    MCMTL_TRY_BEGIN
        id<MTLDevice> mtl = MTLCreateSystemDefaultDevice();
        if (!mtl) {
            mcmtl_set_error("no Metal device");
            return MCMTL_E_NO_DEVICE;
        }
        MCMTLDevice *dev = mcmtl_device_context_create(mtl, @"i5 test queue");
        if (!dev) return MCMTL_E_INTERNAL;
        dev->mslLanguageVersion = MTLLanguageVersion3_2;
        dev->minTexelBufferAlignment = 16;
        g_zeroBuffer = [mtl newBufferWithLength:MCMTL_DUMMY_UNIFORM_BYTES options:MTLResourceStorageModeShared];
        memset(g_zeroBuffer.contents, 0, MCMTL_DUMMY_UNIFORM_BYTES);
        MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:1 height:1 mipmapped:NO];
        td.usage = MTLTextureUsageShaderRead;
        td.storageMode = MTLStorageModeShared;
        g_dummy2D = [mtl newTextureWithDescriptor:td];
        MTLTextureDescriptor *cd = [MTLTextureDescriptor textureCubeDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm size:1 mipmapped:NO];
        cd.usage = MTLTextureUsageShaderRead;
        cd.storageMode = MTLStorageModeShared;
        g_dummyCube = [mtl newTextureWithDescriptor:cd];
        MTLTextureDescriptor *bd = [MTLTextureDescriptor textureBufferDescriptorWithPixelFormat:MTLPixelFormatR8Sint width:16
                                                                                resourceOptions:MTLResourceStorageModeShared
                                                                                          usage:MTLTextureUsageShaderRead];
        g_dummyTexel = [g_zeroBuffer newTextureWithDescriptor:bd offset:0 bytesPerRow:16];
        MTLSamplerDescriptor *sd = [MTLSamplerDescriptor new];
        g_dummySampler = [mtl newSamplerStateWithDescriptor:sd];
        if (outStatusPage) *outStatusPage = (uint64_t)(uintptr_t)dev->status;
        *outDevice = mcmtl_handle_retain(dev);
        return MCMTL_OK;
    MCMTL_TRY_END(MCMTL_E_OBJC_EXCEPTION)
}

/* Reflection of the variant (all non-null targets, depthFormat). out[0..9]: reflected vertex
 * buffer/texture/sampler/cube/texel masks, fragment ditto. out[10..19]: the pipeline's own masks in the
 * same order (from mcmtl_pipeline_desc). out[20]: requiredVertexBufferMask. */
I5_API int32_t i5_pipeline_reflect(mcmtl_handle pipeline, uint32_t depthFormat, uint32_t *out) {
    MCMTL_TRY_BEGIN
        MCMTLPipeline *p = MCMTL_PIPELINE(pipeline);
        uint32_t presentMask = 0;
        for (uint32_t i = 0; i < p->colorTargetCount; i++) {
            if (p->colorTargetFormats[i]) presentMask |= 1u << i;
        }
        MTLRenderPipelineDescriptor *d = i5_variant_descriptor(p, mcmtl_variant_key(presentMask, depthFormat));
        MTLAutoreleasedRenderPipelineReflection reflection = nil;
        NSError *error = nil;
        id<MTLRenderPipelineState> pso = [p->owner->device newRenderPipelineStateWithDescriptor:d options:MTLPipelineOptionBindingInfo
                                                                                     reflection:&reflection error:&error];
        if (!pso) {
            mcmtl_set_error_nserror("reflect", error);
            return MCMTL_E_COMPILE_FAILED;
        }
        memset(out, 0, 21 * sizeof(uint32_t));
        NSArray<id<MTLBinding>> *stages[2] = {reflection.vertexBindings, reflection.fragmentBindings};
        for (int s = 0; s < 2; s++) {
            uint32_t *o = out + s * 5;
            for (id<MTLBinding> b in stages[s]) {
                if (!b.used) continue;
                uint32_t bit = b.index < 32 ? 1u << b.index : 0;
                switch (b.type) {
                    case MTLBindingTypeBuffer: o[0] |= bit; break;
                    case MTLBindingTypeTexture: {
                        o[1] |= bit;
                        MTLTextureType tt = ((id<MTLTextureBinding>)b).textureType;
                        if (tt == MTLTextureTypeCube) o[3] |= bit;
                        if (tt == MTLTextureTypeTextureBuffer) o[4] |= bit;
                        break;
                    }
                    case MTLBindingTypeSampler: o[2] |= bit; break;
                    default: break;
                }
            }
            uint32_t *m = out + 10 + s * 5;
            m[0] = p->bufferMask[s];
            m[1] = p->textureMask[s];
            m[2] = p->samplerMask[s];
            m[3] = p->textureCubeMask[s];
            m[4] = p->texelBufferMask[s];
        }
        out[20] = p->requiredVertexBufferMask;
        return MCMTL_OK;
    MCMTL_TRY_END(MCMTL_E_OBJC_EXCEPTION)
}

static void i5_apply_state(id<MTLRenderCommandEncoder> enc, MCMTLPipeline *p, id<MTLRenderPipelineState> pso) {
    [enc setRenderPipelineState:pso];
    [enc setDepthStencilState:p->depthStencilState];
    [enc setCullMode:(MTLCullMode)p->cullMode];
    [enc setFrontFacingWinding:(MTLWinding)p->frontFacing];
    [enc setTriangleFillMode:(MTLTriangleFillMode)p->fillMode];
    [enc setDepthBias:p->depthBiasConstant slopeScale:p->depthBiasSlopeScale clamp:p->depthBiasClamp];
}

/* Binds what the decoder binds before a draw when nothing was set (CONTRACT.md 6.4) plus a zero vertex
 * buffer at every required index; `setBufferMask[s]` excludes slots the caller already bound. */
static void i5_bind_dummies(id<MTLRenderCommandEncoder> enc, MCMTLPipeline *p, const uint32_t setBufferMask[2]) {
    for (int s = 0; s < 2; s++) {
        for (uint32_t i = 0; i < 31; i++) {
            uint32_t bit = 1u << i;
            if (!(p->bufferMask[s] & bit) || (setBufferMask[s] & bit)) continue;
            if (s == 0) [enc setVertexBuffer:g_zeroBuffer offset:0 atIndex:i];
            else [enc setFragmentBuffer:g_zeroBuffer offset:0 atIndex:i];
        }
        for (uint32_t i = 0; i < MCMTL_MAX_TEXTURE_BINDINGS; i++) {
            uint32_t bit = 1u << i;
            if (p->textureMask[s] & bit) {
                id<MTLTexture> t = (p->textureCubeMask[s] & bit) ? g_dummyCube : (p->texelBufferMask[s] & bit) ? g_dummyTexel : g_dummy2D;
                if (s == 0) [enc setVertexTexture:t atIndex:i];
                else [enc setFragmentTexture:t atIndex:i];
            }
            if (p->samplerMask[s] & bit) {
                if (s == 0) [enc setVertexSamplerState:g_dummySampler atIndex:i];
                else [enc setFragmentSamplerState:g_dummySampler atIndex:i];
            }
        }
    }
}

static id<MTLTexture> i5_target(id<MTLDevice> mtl, uint32_t format, uint32_t w, uint32_t h) {
    MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:(MTLPixelFormat)format width:w height:h mipmapped:NO];
    td.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
    td.storageMode = MTLStorageModePrivate;
    return [mtl newTextureWithDescriptor:td];
}

/* One draw of 3 zero vertices with every masked slot dummied, into attachments of the pipeline's own
 * formats (+ depthFormat if non-zero). Fails on PSO or command-buffer errors; Metal API validation
 * (MTL_DEBUG_LAYER=1) aborts the process on binding errors. */
I5_API int32_t i5_render_smoke(mcmtl_handle pipeline, uint32_t depthFormat) {
    MCMTL_TRY_BEGIN
        MCMTLPipeline *p = MCMTL_PIPELINE(pipeline);
        MCMTLDevice *dev = p->owner;
        uint32_t presentMask = 0;
        MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
        for (uint32_t i = 0; i < p->colorTargetCount; i++) {
            if (!p->colorTargetFormats[i]) continue;
            presentMask |= 1u << i;
            rp.colorAttachments[i].texture = i5_target(dev->device, p->colorTargetFormats[i], 8, 8);
            rp.colorAttachments[i].loadAction = MTLLoadActionClear;
            rp.colorAttachments[i].storeAction = MTLStoreActionStore;
        }
        if (depthFormat) {
            rp.depthAttachment.texture = i5_target(dev->device, depthFormat, 8, 8);
            rp.depthAttachment.loadAction = MTLLoadActionClear;
            rp.depthAttachment.clearDepth = 0.0;
            rp.depthAttachment.storeAction = MTLStoreActionStore;
        }
        if (!presentMask && !depthFormat) {
            rp.renderTargetWidth = 8;
            rp.renderTargetHeight = 8;
        }
        id<MTLRenderPipelineState> pso = mcmtl_pipeline_variant(p, mcmtl_variant_key(presentMask, depthFormat));
        if (!pso) return MCMTL_E_COMPILE_FAILED;
        id<MTLCommandBuffer> cb = [dev->queue commandBuffer];
        id<MTLRenderCommandEncoder> enc = [cb renderCommandEncoderWithDescriptor:rp];
        i5_apply_state(enc, p, pso);
        const uint32_t none[2] = {0, 0};
        i5_bind_dummies(enc, p, none);
        [enc drawPrimitives:(MTLPrimitiveType)p->primitiveType vertexStart:0 vertexCount:3 instanceCount:1 baseInstance:0];
        [enc endEncoding];
        [cb commit];
        [cb waitUntilCompleted];
        if (cb.status != MTLCommandBufferStatusCompleted) {
            mcmtl_set_error("command buffer failed: %s", cb.error.localizedDescription.UTF8String ?: "?");
            return MCMTL_E_INTERNAL;
        }
        return MCMTL_OK;
    MCMTL_TRY_END(MCMTL_E_OBJC_EXCEPTION)
}

/* Draws vertexCount vertices from `vertexData` (bound at [[buffer(30 - vertexSlot)]]) with caller UBOs
 * (uboSlots[i] on both stages where the pipeline's masks use it) into a w x h RGBA8Unorm color target
 * (cleared to clearColor) + Depth32Float (cleared to clearDepth), then copies the color target into
 * outRgba (w * h * 4 bytes, memory row 0 first). Unset masked slots get the decoder's dummies. */
I5_API int32_t i5_render(mcmtl_handle pipeline, uint32_t w, uint32_t h, const float *clearColor, float clearDepth,
                         uint32_t vertexSlot, const void *vertexData, uint64_t vertexBytes, uint32_t vertexCount,
                         uint32_t uboCount, const uint32_t *uboSlots, const void *const *uboData, const uint64_t *uboBytes,
                         uint8_t *outRgba) {
    MCMTL_TRY_BEGIN
        MCMTLPipeline *p = MCMTL_PIPELINE(pipeline);
        MCMTLDevice *dev = p->owner;
        id<MTLDevice> mtl = dev->device;
        if (p->colorTargetCount != 1 || p->colorTargetFormats[0] != MTLPixelFormatRGBA8Unorm) {
            mcmtl_set_error("i5_render needs one RGBA8Unorm target");
            return MCMTL_E_INVALID_ARGUMENT;
        }
        id<MTLTexture> color = i5_target(mtl, MTLPixelFormatRGBA8Unorm, w, h);
        id<MTLTexture> depth = i5_target(mtl, MTLPixelFormatDepth32Float, w, h);
        MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
        rp.colorAttachments[0].texture = color;
        rp.colorAttachments[0].loadAction = MTLLoadActionClear;
        rp.colorAttachments[0].clearColor = MTLClearColorMake(clearColor[0], clearColor[1], clearColor[2], clearColor[3]);
        rp.colorAttachments[0].storeAction = MTLStoreActionStore;
        rp.depthAttachment.texture = depth;
        rp.depthAttachment.loadAction = MTLLoadActionClear;
        rp.depthAttachment.clearDepth = clearDepth;
        rp.depthAttachment.storeAction = MTLStoreActionDontCare;
        id<MTLRenderPipelineState> pso = mcmtl_pipeline_variant(p, mcmtl_variant_key(1u, MTLPixelFormatDepth32Float));
        if (!pso) return MCMTL_E_COMPILE_FAILED;
        id<MTLBuffer> vb = [mtl newBufferWithBytes:vertexData length:(NSUInteger)vertexBytes options:MTLResourceStorageModeShared];
        NSMutableArray *keep = [NSMutableArray array];
        id<MTLCommandBuffer> cb = [dev->queue commandBuffer];
        id<MTLRenderCommandEncoder> enc = [cb renderCommandEncoderWithDescriptor:rp];
        i5_apply_state(enc, p, pso);
        uint32_t set[2] = {0, 0};
        for (uint32_t i = 0; i < uboCount; i++) {
            uint64_t len = uboBytes[i] < 256 ? 256 : uboBytes[i];
            id<MTLBuffer> ubo = [mtl newBufferWithLength:(NSUInteger)len options:MTLResourceStorageModeShared];
            memset(ubo.contents, 0, (size_t)len);
            memcpy(ubo.contents, uboData[i], (size_t)uboBytes[i]);
            [keep addObject:ubo];
            uint32_t bit = 1u << uboSlots[i];
            if (p->bufferMask[0] & bit) { [enc setVertexBuffer:ubo offset:0 atIndex:uboSlots[i]]; set[0] |= bit; }
            if (p->bufferMask[1] & bit) { [enc setFragmentBuffer:ubo offset:0 atIndex:uboSlots[i]]; set[1] |= bit; }
        }
        uint32_t vbIndex = MCMTL_VERTEX_BUFFER_INDEX_BASE - vertexSlot;
        [enc setVertexBuffer:vb offset:0 atIndex:vbIndex];
        set[0] |= 1u << vbIndex;
        i5_bind_dummies(enc, p, set);
        [enc drawPrimitives:(MTLPrimitiveType)p->primitiveType vertexStart:0 vertexCount:vertexCount instanceCount:1 baseInstance:0];
        [enc endEncoding];
        id<MTLBuffer> readback = [mtl newBufferWithLength:(NSUInteger)w * h * 4 options:MTLResourceStorageModeShared];
        id<MTLBlitCommandEncoder> blit = [cb blitCommandEncoder];
        [blit copyFromTexture:color sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0, 0, 0) sourceSize:MTLSizeMake(w, h, 1)
                     toBuffer:readback destinationOffset:0 destinationBytesPerRow:(NSUInteger)w * 4 destinationBytesPerImage:(NSUInteger)w * h * 4];
        [blit endEncoding];
        [cb commit];
        [cb waitUntilCompleted];
        if (cb.status != MTLCommandBufferStatusCompleted) {
            mcmtl_set_error("command buffer failed: %s", cb.error.localizedDescription.UTF8String ?: "?");
            return MCMTL_E_INTERNAL;
        }
        memcpy(outRgba, readback.contents, (size_t)w * h * 4);
        return MCMTL_OK;
    MCMTL_TRY_END(MCMTL_E_OBJC_EXCEPTION)
}
