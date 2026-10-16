#import "mcmetal_farproducer.h"
#import <objc/runtime.h>
#include <math.h>

extern int32_t mcmtl_ext_cluster_prepare(mcmtl_handle device);
static char preparedDeviceKey;
static const uint32_t geometryMask = (15u << 27) | (1u << MCMTLFarShaderArenaBuffer);
static BOOL fail(NSError **error, NSString *message) {
    if (error) *error = [NSError errorWithDomain:@"mcmetal.far.producer" code:1
                                      userInfo:@{NSLocalizedDescriptionKey:message}];
    return NO;
}
static BOOL fits(id<MTLBuffer> b, id<MTLDevice> device, NSUInteger offset, uint64_t size) {
    return b && b.device == device && b.hazardTrackingMode != MTLHazardTrackingModeUntracked &&
        offset <= b.length && size <= b.length - offset;
}

MCMTLDevice *mcmtl_farproducer_cull_device(id<MTLDevice> device, NSError **error) {
    if (error) *error = nil;
    @try {
        if ([NSThread isMainThread] || !device) { fail(error, @"Cull preparation requires a worker and Metal device"); return nil; }
        MCMTLDevice *wrapper = [MCMTLDevice new];
        wrapper->device = device; wrapper->mslLanguageVersion = MTLLanguageVersion3_2;
        if (mcmtl_ext_cluster_prepare((mcmtl_handle)(uintptr_t)(__bridge void *)wrapper) != MCMTL_OK) {
            fail(error, [NSString stringWithFormat:@"Cull preparation failed: %s", mcmtl_last_error()]); return nil;
        }
        // Store the Metal device too: the native wrapper's public ivar must not
        // be replaced after preparation. This association does not retain self.
        objc_setAssociatedObject(wrapper, &preparedDeviceKey, device, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
        return wrapper;
    } @catch (NSException *e) { fail(error, [NSString stringWithFormat:@"Far cull preparation: %@", e.reason]); return nil; }
}
static BOOL prepared(MCMTLDevice *wrapper) {
    return wrapper && wrapper->device && objc_getAssociatedObject(wrapper, &preparedDeviceKey) == wrapper->device &&
        !wrapper->queue && !wrapper->event && !wrapper->currentCommandBuffer && !wrapper->decoderState;
}
MCMTLFarCullSnapshot *mcmtl_farproducer_snapshot(MCMTLDevice *cullDevice,
    NSArray<MCMTLFarClusterInput *> *inputs, NSData *cullSections, NSData *shaderSections,
    NSData *view, uint64_t budgetBytes, NSError **error) {
    if (error) *error = nil;
    @try {
        if ([NSThread isMainThread] || !prepared(cullDevice)) {
            fail(error, @"Snapshot requires a prepared isolated worker cull wrapper"); return nil;
        }
        MCMTLFarCullSnapshot *snapshot = [[MCMTLFarCullSnapshot alloc] initWithDevice:cullDevice inputs:inputs
            cullSections:cullSections shaderSections:shaderSections view:view budgetBytes:budgetBytes error:error];
        return snapshot;
    } @catch (NSException *e) { fail(error, [NSString stringWithFormat:@"Far snapshot capture: %@", e.reason]); return nil; }
}

/* Never expose the writable uniform MTLBuffers or captured binding tables. */
@interface MCMTLFarProducerOwnedDraw : NSObject {
@public
    MCMTLFarShader *shader;
    __strong id<MTLBuffer> uniforms[2][MCMTLFarProducerBufferCount];
    __strong id<MTLTexture> textures[2][MCMTLFarProducerTextureCount];
    __strong id<MTLSamplerState> samplers[2][MCMTLFarProducerTextureCount];
    BOOL lodClamp[2][MCMTLFarProducerTextureCount];
    float lodMin[2][MCMTLFarProducerTextureCount], lodMax[2][MCMTLFarProducerTextureCount];
    MTLViewport viewport;
    MTLScissorRect scissor;
    MTLDepthClipMode depthClip;
}
@end
@implementation MCMTLFarProducerOwnedDraw
+ (BOOL)accessInstanceVariablesDirectly { return NO; }
@end

@interface MCMTLFarProducerPacket () {
@public
    id<MTLDevice> _device;
    MCMTLDevice *_cullDevice;
    NSArray<MCMTLFarProducerOwnedDraw *> *_draws;
    id<MCMTLFarMaterialLease> _materialLease;
    NSUInteger _width, _height;
    MCMTLFarProducerClears _clears;
    BOOL _encoded;
}
@end

static BOOL validView(MCMTLFarProducerDraw d, NSUInteger w, NSUInteger h) {
    MTLViewport v = d.viewport; MTLScissorRect s = d.scissor;
    return isfinite(v.originX) && isfinite(v.originY) && isfinite(v.width) && isfinite(v.height) &&
        isfinite(v.znear) && isfinite(v.zfar) && v.originX >= 0 && v.originY >= 0 && v.width > 0 && v.height > 0 &&
        v.originX + v.width <= w && v.originY + v.height <= h &&
        v.znear >= 0 && v.znear <= 1 && v.zfar >= 0 && v.zfar <= 1 &&
        s.width && s.height && s.x <= w && s.y <= h && s.width <= w-s.x && s.height <= h-s.y &&
        (d.depthClipMode == MTLDepthClipModeClip || d.depthClipMode == MTLDepthClipModeClamp);
}
static BOOL validGeometry(MCMTLFarCullSnapshot *snapshot, NSUInteger i, id<MTLDevice> device, MCMTLFarShader *shader) {
    MCMTLFarDraw d = [snapshot drawAtIndex:i]; MCMTLFarClusterInput *in = d.input;
    if (!in || (in.layerCode & ~9u) || (in.layerCode & 1u) != shader.layer ||
        ((in.layerCode & 8u) ? MCMTLFarShaderCompact : MCMTLFarShaderBlock) != shader.layout ||
        !d.quadCapacity || in.items.length % 16 || !in.items.length || in.vertexOffset % 4 || in.indexOffset % 4 ||
        !fits(in.vertices, device, in.vertexOffset, 0) ||
        !fits(in.indices, device, in.indexOffset, (uint64_t)d.quadCapacity * 24) ||
        !fits(d.arguments, device, 0, sizeof(MTLDrawIndexedPrimitivesIndirectArguments)) ||
        !fits(d.quads, device, 0, (uint64_t)d.quadCapacity * 4) ||
        !fits(d.items, device, 0, in.items.length / 2) ||
        !fits(snapshot.shaderSections, device, 0, 16)) return NO;
    // MCMTLFarCullGeometry validated every immutable item against these retained
    // arena/table/index ranges and computed quadCapacity once. The layout and
    // buffer-device checks above establish that the shader consumes that same
    // proof. Rewalking all clusters here would restore a terrain-sized CPU cost
    // on every camera-only refresh, despite the shared immutable geometry owner.
    return snapshot.geometry != nil;
}
static BOOL validBindings(MCMTLFarProducerDraw d, id<MTLDevice> device,
                          id<MCMTLFarMaterialLease> lease, NSError **error) {
    MCMTLFarShaderBindings b = d.shader.bindings;
    MCMTLFarShaderRaster r = d.shader.raster;
    if (r.primitiveType != MTLPrimitiveTypeTriangle || r.fillMode != MTLTriangleFillModeFill ||
        r.cullMode > MTLCullModeBack || r.frontFacing > MTLWindingCounterClockwise ||
        !isfinite(r.depthBiasConstant) || !isfinite(r.depthBiasSlopeScale) || !isfinite(r.depthBiasClamp) ||
        r.pushConstantSize > MCMTL_MAX_PUSH_CONSTANT_BYTES || (r.pushConstantStages & ~3u) ||
        (b.requiredVertexBufferMask & 0x80000000u)) return fail(error, @"Invalid original raster/binding metadata");
    for (unsigned s = 0; s < 2; ++s) {
        if ((b.bufferMask[s] & 0x80000000u) || ((b.textureMask[s] | b.samplerMask[s]) & ~0xffffu) ||
            b.textureCubeMask[s] || b.texelBufferMask[s]) return fail(error, @"Unsupported resource mask for built-in 2D terrain");
        uint32_t required = b.bufferMask[s] | (s == 0 ? b.requiredVertexBufferMask : 0);
        if (r.pushConstantSize && (r.pushConstantStages & (1u << s))) required |= 1u << 14;
        for (unsigned k = 0; k < MCMTLFarProducerBufferCount; ++k) {
            NSData *bytes = d.stages[s].buffers[k];
            if (s == 0 && (geometryMask & (1u << k))) {
                if (bytes) return fail(error, @"Captured uniform collides with producer geometry/arena slot");
                continue;
            }
            /* The admitted full-source fingerprints use these constant structs.
             * Masks alone cannot prove that a four-byte Projection is safe. */
            NSUInteger minimum = k == 0 ? 64 : (k == 1 || k == 2) ? 48 : k == 5 ? 80 : 1;
            if (((required & (1u << k)) && bytes.length < minimum) || (bytes && (!bytes.length || bytes.length > device.maxBufferLength)) ||
                (k == 14 && r.pushConstantSize && (r.pushConstantStages & (1u << s)) && bytes.length < r.pushConstantSize))
                return fail(error, @"Missing, empty or undersized original uniform/push-constant bytes");
        }
        for (unsigned k = 0; k < MCMTLFarProducerTextureCount; ++k) {
            id<MTLTexture> t = d.stages[s].textures[k]; id<MTLSamplerState> sampler = d.stages[s].samplers[k];
            if (((b.textureMask[s] & (1u << k)) && !t) || (t &&
                (t.device != device || t.textureType != MTLTextureType2D || t.sampleCount != 1 ||
                 t.storageMode == MTLStorageModeMemoryless || t.hazardTrackingMode == MTLHazardTrackingModeUntracked ||
                 !(t.usage & MTLTextureUsageShaderRead) || !lease || ![lease ownsImmutableFarTexture:t])))
                return fail(error, @"Missing/wrong-device material texture or immutable pixel-version lease");
            if (((b.samplerMask[s] & (1u << k)) && !sampler) || (sampler && sampler.device != device))
                return fail(error, @"Missing or wrong-device original sampler");
            if (d.stages[s].samplerLODClamp[k] && (!sampler || !isfinite(d.stages[s].samplerLODMin[k]) ||
                !isfinite(d.stages[s].samplerLODMax[k]) || d.stages[s].samplerLODMin[k] < 0 ||
                d.stages[s].samplerLODMax[k] < d.stages[s].samplerLODMin[k]))
                return fail(error, @"Invalid original sampler LOD clamp");
        }
    }
    return YES;
}

@implementation MCMTLFarProducerPacket
+ (BOOL)accessInstanceVariablesDirectly { return NO; }
- (instancetype)initWithCullDevice:(MCMTLDevice *)cullDevice snapshot:(MCMTLFarCullSnapshot *)snapshot
    draws:(const MCMTLFarProducerDraw *)draws count:(NSUInteger)count width:(NSUInteger)width height:(NSUInteger)height
    clears:(MCMTLFarProducerClears)clears materialLease:(id<MCMTLFarMaterialLease>)materialLease error:(NSError **)error {
    if (error) *error = nil;
    if (!(self = [super init])) return nil;
    @try {
        if ([NSThread isMainThread] || !prepared(cullDevice) || !snapshot ||
            snapshot.cullDevice != cullDevice ||
            count != snapshot.count || count > 4096 || (count && !draws) || !width || !height ||
            width > 16384 || height > 16384 || snapshot.shaderSections.device != cullDevice->device) {
            fail(error, @"Far packet requires worker construction, snapshot with exact isolated cull wrapper, and bounded dimensions"); return nil;
        }
        _device = cullDevice->device; _cullDevice = cullDevice; _snapshot = snapshot;
        _width = width; _height = height; _clears = clears; _count = count; _materialLease = materialLease;
        for (NSUInteger l = 0; l < MCMTLFarImageLayerCount; ++l) {
            MTLClearColor c = clears.color[l];
            if (!isfinite(c.red) || !isfinite(c.green) || !isfinite(c.blue) || !isfinite(c.alpha) ||
                !isfinite(clears.depth[l]) || clears.depth[l] < 0 || clears.depth[l] > 1) {
                fail(error, @"Nonfinite color or invalid depth clear"); return nil;
            }
        }
        /* Validate later draws too, before allocating uniforms or preparing cull PSOs. */
        for (NSUInteger i = 0; i < count; ++i) {
            MCMTLFarShader *s = draws[i].shader;
            if (!s || s.layer >= MCMTLFarImageLayerCount || s.pipelineState.device != _device ||
                s.depthStencilState.device != _device || s.colorFormat != MTLPixelFormatRGBA8Unorm ||
                s.depthFormat != MTLPixelFormatDepth32Float || !validView(draws[i], width, height) ||
                !validGeometry(snapshot, i, _device, s)) {
                fail(error, @"Invalid draw, viewport, geometry bounds or SOLID/CUTOUT pipeline/target format"); return nil;
            }
            if (!validBindings(draws[i], _device, materialLease, error)) return nil;
        }
        NSMutableArray *owned = [NSMutableArray arrayWithCapacity:count];
        for (NSUInteger i = 0; i < count; ++i) {
            MCMTLFarProducerOwnedDraw *out = [MCMTLFarProducerOwnedDraw new];
            out->shader = draws[i].shader; out->viewport = draws[i].viewport; out->scissor = draws[i].scissor;
            out->depthClip = draws[i].depthClipMode;
            for (unsigned s = 0; s < 2; ++s) {
                for (unsigned k = 0; k < MCMTLFarProducerBufferCount; ++k) {
                    NSData *bytes = draws[i].stages[s].buffers[k];
                    if (!bytes) continue;
                    out->uniforms[s][k] = [_device newBufferWithBytes:bytes.bytes length:bytes.length options:MTLResourceStorageModeShared];
                    if (!out->uniforms[s][k]) { fail(error, @"Cannot allocate owned uniform copy"); return nil; }
                }
                for (unsigned k = 0; k < MCMTLFarProducerTextureCount; ++k) {
                    out->textures[s][k] = draws[i].stages[s].textures[k]; out->samplers[s][k] = draws[i].stages[s].samplers[k];
                    out->lodClamp[s][k] = draws[i].stages[s].samplerLODClamp[k];
                    out->lodMin[s][k] = draws[i].stages[s].samplerLODMin[k]; out->lodMax[s][k] = draws[i].stages[s].samplerLODMax[k];
                }
            }
            [owned addObject:out];
        }
        _draws = [owned copy];
        return self;
    } @catch (NSException *e) { fail(error, [NSString stringWithFormat:@"Far packet capture: %@", e.reason]); return nil; }
}
@end

static id<MTLTexture> rootTexture(id<MTLTexture> t) {
    while (t.parentTexture) t = t.parentTexture;
    return t;
}
static BOOL validImage(MCMTLFarProducerPacket *p, MCMTLFarImageView image, NSError **error) {
    if (image.width != p->_width || image.height != p->_height) return fail(error, @"Image dimensions differ from captured guard band");
    __unsafe_unretained id<MTLTexture> targets[6] = {image.color[0], image.depth[0], image.visibilityID[0],
                                                   image.color[1], image.depth[1], image.visibilityID[1]};
    const MTLPixelFormat formats[3] = {MTLPixelFormatRGBA8Unorm, MTLPixelFormatDepth32Float, MTLPixelFormatR32Uint};
    for (unsigned i = 0; i < 6; ++i) {
        id<MTLTexture> t = targets[i];
        if (!t || t.device != p->_device || t.textureType != MTLTextureType2D || t.width != image.width || t.height != image.height ||
            t.sampleCount != 1 || t.mipmapLevelCount != 1 || t.arrayLength != 1 || t.pixelFormat != formats[i%3] ||
            t.storageMode != MTLStorageModePrivate || t.hazardTrackingMode == MTLHazardTrackingModeUntracked || t.heap || t.buffer ||
            (t.usage & (MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead)) != (MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead))
            return fail(error, @"Image requires six distinct private tracked 2D color/depth/ID targets");
        for (unsigned j = 0; j < i; ++j) if (rootTexture(targets[j]) == rootTexture(t))
            return fail(error, @"Image attachments alias across layers");
        for (MCMTLFarProducerOwnedDraw *d in p->_draws) for (unsigned s = 0; s < 2; ++s)
            for (unsigned k = 0; k < MCMTLFarProducerTextureCount; ++k)
                if (d->textures[s][k] && rootTexture(d->textures[s][k]) == rootTexture(t))
                    return fail(error, @"Material snapshot aliases a producer output");
    }
    return YES;
}
static void bindDraw(id<MTLRenderCommandEncoder> re, MCMTLFarProducerOwnedDraw *d, MCMTLFarDraw geometry,
                     id<MTLBuffer> sections) {
    MCMTLFarShaderRaster r = d->shader.raster;
    [re setRenderPipelineState:d->shader.pipelineState]; [re setDepthStencilState:d->shader.depthStencilState];
    [re setCullMode:r.cullMode]; [re setFrontFacingWinding:r.frontFacing]; [re setTriangleFillMode:r.fillMode];
    [re setDepthBias:r.depthBiasConstant slopeScale:r.depthBiasSlopeScale clamp:r.depthBiasClamp];
    [re setViewport:d->viewport]; [re setScissorRect:d->scissor]; [re setDepthClipMode:d->depthClip];
    for (unsigned s = 0; s < 2; ++s) {
        for (unsigned k = 0; k < MCMTLFarProducerBufferCount; ++k) {
            if (s == 0 && (geometryMask & (1u << k))) continue;
            if (s == 0) [re setVertexBuffer:d->uniforms[s][k] offset:0 atIndex:k];
            else [re setFragmentBuffer:d->uniforms[s][k] offset:0 atIndex:k];
        }
        for (unsigned k = 0; k < MCMTLFarProducerTextureCount; ++k) {
            if (s == 0) {
                [re setVertexTexture:d->textures[s][k] atIndex:k];
                if (d->lodClamp[s][k]) [re setVertexSamplerState:d->samplers[s][k] lodMinClamp:d->lodMin[s][k] lodMaxClamp:d->lodMax[s][k] atIndex:k];
                else [re setVertexSamplerState:d->samplers[s][k] atIndex:k];
            } else {
                [re setFragmentTexture:d->textures[s][k] atIndex:k];
                if (d->lodClamp[s][k]) [re setFragmentSamplerState:d->samplers[s][k] lodMinClamp:d->lodMin[s][k] lodMaxClamp:d->lodMax[s][k] atIndex:k];
                else [re setFragmentSamplerState:d->samplers[s][k] atIndex:k];
            }
        }
    }
    uint32_t base = geometry.input.faceBase;
    [re setVertexBytes:&base length:sizeof base atIndex:MCMTLFarShaderArenaBuffer];
    [re setVertexBuffer:geometry.input.vertices offset:geometry.input.vertexOffset atIndex:30];
    [re setVertexBuffer:sections offset:0 atIndex:29];
    [re setVertexBuffer:geometry.quads offset:0 atIndex:28];
    [re setVertexBuffer:geometry.items offset:0 atIndex:27];
}
BOOL mcmtl_farproducer_encode(MCMTLFarProducerPacket *p, MCMTLFarCullSnapshot *snapshot,
    id<MTLCommandBuffer> buffer, MCMTLFarImageView image,
    id<MTLSharedEvent> readyEvent, uint64_t readyValue, NSError **error) {
    if (error) *error = nil;
    id<MTLRenderCommandEncoder> re = nil;
    @try {
        if ([NSThread isMainThread] || !p || snapshot != p.snapshot || p->_encoded || !buffer ||
            !prepared(p->_cullDevice) || snapshot.cullDevice != p->_cullDevice ||
            buffer.status != MTLCommandBufferStatusNotEnqueued || buffer.device != p->_device || !buffer.retainedReferences ||
            !readyEvent || (readyEvent.device && readyEvent.device != p->_device) || !readyValue)
            return fail(error, @"Producer needs worker, matching fresh packet/snapshot, unqueued retained buffer and committed upload dependency");
        if (!validImage(p, image, error)) return NO;
        p->_encoded = YES;
        if (![snapshot encodeCull:buffer readyEvent:readyEvent readyValue:readyValue error:error]) return NO;
        for (NSUInteger layer = 0; layer < MCMTLFarImageLayerCount; ++layer) {
            MTLRenderPassDescriptor *pass = [MTLRenderPassDescriptor renderPassDescriptor];
            pass.colorAttachments[0].texture = image.color[layer]; pass.colorAttachments[0].loadAction = MTLLoadActionClear;
            pass.colorAttachments[0].storeAction = MTLStoreActionStore; pass.colorAttachments[0].clearColor = p->_clears.color[layer];
            pass.colorAttachments[1].texture = image.visibilityID[layer]; pass.colorAttachments[1].loadAction = MTLLoadActionClear;
            pass.colorAttachments[1].storeAction = MTLStoreActionStore; pass.colorAttachments[1].clearColor = MTLClearColorMake(0,0,0,0);
            pass.depthAttachment.texture = image.depth[layer]; pass.depthAttachment.loadAction = MTLLoadActionClear;
            pass.depthAttachment.storeAction = MTLStoreActionStore; pass.depthAttachment.clearDepth = p->_clears.depth[layer];
            re = [buffer renderCommandEncoderWithDescriptor:pass];
            if (!re) return fail(error, @"Cannot create far MRT render encoder; discard command buffer");
            for (NSUInteger i = 0; i < p.count; ++i) {
                MCMTLFarProducerOwnedDraw *d = p->_draws[i];
                if (d->shader.layer != layer) continue;
                MCMTLFarDraw g = [snapshot drawAtIndex:i];
                bindDraw(re, d, g, snapshot.shaderSections);
                [re drawIndexedPrimitives:d->shader.raster.primitiveType indexType:MTLIndexTypeUInt32
                    indexBuffer:g.input.indices indexBufferOffset:g.input.indexOffset indirectBuffer:g.arguments indirectBufferOffset:0];
            }
            [re endEncoding]; re = nil;
        }
        return YES;
    } @catch (NSException *e) {
        if (re) { @try { [re endEncoding]; } @catch (NSException *ignored) { (void)ignored; } }
        return fail(error, [NSString stringWithFormat:@"Far producer encoding: %@; discard command buffer", e.reason]);
    }
}
