#import "mcmetal_farengine.h"
#import "mcmetal_farmaterial.h"
#include <stdatomic.h>
#include <math.h>
#include <stdio.h>
#include <string.h>

_Static_assert(sizeof(mcmtl_far_engine_config) == 40, "FFM config");
_Static_assert(sizeof(mcmtl_far_engine_source) == 40, "FFM source");
_Static_assert(sizeof(mcmtl_far_engine_camera) == 176, "FFM camera");
_Static_assert(sizeof(mcmtl_far_engine_uniforms) == 64, "FFM uniform descriptors");
_Static_assert(sizeof(mcmtl_far_engine_submission) == 432, "FFM submission");
_Static_assert(offsetof(mcmtl_far_engine_submission, uniforms) == 320, "FFM uniforms offset");
_Static_assert(sizeof(mcmtl_far_engine_image) == 328, "FFM image");
_Static_assert(sizeof(mcmtl_far_engine_uniform_copy) == 248, "FFM uniform copy");
_Static_assert(sizeof(mcmtl_far_engine_timing) == 80, "FFM timing receipt");
_Static_assert(MCMTL_FAR_ENGINE_TIMING_CPU_VALID == MCMTLFarImageTimingCPUValid &&
    MCMTL_FAR_ENGINE_TIMING_GPU_VALID == MCMTLFarImageTimingGPUValid, "Timing flags");

static const unsigned slots[4] = {0, 1, 2, 5};
static const NSUInteger sizes[4] = {64, 48, 48, 80};
static const uint32_t uniformMask = (1u<<0)|(1u<<1)|(1u<<2)|(1u<<5);
static const uint32_t geometryMask = (15u<<27)|(1u<<6);
static mcmtl_handle handle(id o) { return (mcmtl_handle)(uintptr_t)(__bridge void *)o; }
static id object(mcmtl_handle h) { return (__bridge id)(void *)(uintptr_t)h; }
static void message(char *out, uint32_t capacity, NSString *text) {
    if (out && capacity) snprintf(out, capacity, "%s", text.UTF8String ?: "");
}
static int32_t failure(char *out, uint32_t capacity, NSString *text) {
    message(out, capacity, text ?: @"Far engine failed"); return MCMTL_E_UNSUPPORTED;
}

@interface MCMTLFarEngineShading ()
@property(nonatomic, readwrite) MCMTLFarShade *solid, *cutout;
@property(nonatomic, readwrite) MCMTLFarShadeGeometry *geometry;
@end
@implementation MCMTLFarEngineShading
+ (BOOL)accessInstanceVariablesDirectly { return NO; }
@end

/* A slot owns this for its entire publication/ID lifetime. It never references
 * engine/owner/lease, so neither the producer nor a consumer creates a cycle. */
@interface MCMTLFarEngineInputs : NSObject {
@public
    mcmtl_far_engine_camera camera;
    uint64_t ticket, required;
    MCMTLFarGeometryRecipe *geometry;
    MCMTLFarMaterialVersion *material;
    MCMTLFarProducerPacket *packet;
    NSArray<NSArray<NSData *> *> *uniforms;
    MCMTLFarEngineShading *shading;
}
@end
@implementation MCMTLFarEngineInputs
+ (BOOL)accessInstanceVariablesDirectly { return NO; }
@end

@interface MCMTLFarEngine : NSObject {
@public
    id<MTLDevice> device;
    MCMTLFarImageOwner *owner;
    MCMTLDevice *cull;
    uint32_t width, height;
    uint64_t packetBudget;
    _Atomic(uint64_t) epoch;
    _Atomic(bool) stopped;
    _Atomic(uint64_t) busy;
    uint64_t nextAttempt; /* Access only while holding busy; never wraps. */
    uint64_t shadeBudget; /* Set once before the first submission. */
    MCMTLFarGeometryRecipe *shadeRecipe;
    NSArray<MCMTLFarShader *> *shadeOriginals;
    MCMTLFarEngineShading *shading;
}
@end
@implementation MCMTLFarEngine
+ (BOOL)accessInstanceVariablesDirectly { return NO; }
@end

static BOOL eligible(MCMTLFarEngine *e, uint64_t value) {
    return !atomic_load_explicit(&e->stopped, memory_order_acquire) &&
        value == atomic_load_explicit(&e->epoch, memory_order_acquire);
}
static BOOL cameraValid(mcmtl_far_engine_camera c) {
    for (unsigned i=0; i<3; ++i) if (!isfinite(c.position[i]) || !isfinite(c.renderOrigin[i])) return NO;
    for (unsigned i=0; i<16; ++i) if (!isfinite(c.view[i]) || !isfinite(c.guardProjection[i])) return NO;
    return YES;
}
static BOOL supported(MCMTLFarShader *s) {
    if (!s || s.raster.pushConstantSize || s.raster.pushConstantStages) return NO;
    MCMTLFarShaderBindings b=s.bindings;
    if ((b.requiredVertexBufferMask & ~(geometryMask|uniformMask)) ||
        (b.bufferMask[0] & ~(geometryMask|uniformMask)) || (b.bufferMask[1] & ~uniformMask)) return NO;
    for (unsigned stage=0; stage<2; ++stage) {
        uint32_t texture = stage ? 1u : 1u<<2;
        if ((b.textureMask[stage] & ~texture) || (b.samplerMask[stage] & ~texture) ||
            b.textureCubeMask[stage] || b.texelBufferMask[stage]) return NO;
    }
    return YES;
}

MCMTL_API int32_t mcmtl_ext_far_engine_create(mcmtl_handle h, const mcmtl_far_engine_config *config,
    mcmtl_handle *out, char *error, uint32_t capacity) {
    if (out) *out=0;
    message(error,capacity,nil);
    if (!h || !config || !out || (capacity && !error)) return MCMTL_E_INVALID_ARGUMENT;
    if (config->structSize != sizeof(*config)) return MCMTL_E_ABI_MISMATCH;
    @autoreleasepool { @try {
        mcmtl_far_engine_config c=*config;
        if ([NSThread isMainThread] || c.reserved || !c.epoch || !c.width || !c.height ||
            c.width>16384 || c.height>16384 || !c.packetBufferBudgetBytes || c.packetBufferBudgetBytes>UINT64_MAX/3u ||
            (uint64_t)c.width*c.height*72u>c.targetBudgetBytes)
            return failure(error,capacity,@"Engine requires worker, valid dimensions/epoch and caller budgets");
        MCMTLDevice *main=MCMTL_DEVICE(h);
        if (!main->device) return MCMTL_E_INVALID_ARGUMENT;
        MCMTLFarEngine *e=[MCMTLFarEngine new];
        e->device=main->device; e->width=c.width; e->height=c.height; e->packetBudget=c.packetBufferBudgetBytes;
        atomic_init(&e->epoch,c.epoch); atomic_init(&e->stopped,false); atomic_init(&e->busy,0); e->nextAttempt=1;
        NSError *local=nil;
        e->cull=mcmtl_farproducer_cull_device(e->device,&local);
        if (!e->cull) return failure(error,capacity,local.localizedDescription);
        e->owner=[[MCMTLFarImageOwner alloc] initWithDevice:e->device width:c.width height:c.height epoch:c.epoch error:&local];
        if (!e->owner) return failure(error,capacity,local.localizedDescription);
        *out=(mcmtl_handle)(uintptr_t)(__bridge_retained void *)e;
        return MCMTL_OK;
    } @catch (NSException *ex) { message(error,capacity,ex.reason); return MCMTL_E_OBJC_EXCEPTION; } }
}

MCMTL_API int32_t mcmtl_ext_far_engine_enable_shading(mcmtl_handle h,uint64_t budget,
    char *error,uint32_t capacity) {
    message(error,capacity,nil);
    if (!h || !budget || (capacity && !error)) return MCMTL_E_INVALID_ARGUMENT;
    @try {
        MCMTLFarEngine *e=object(h);
        if ([NSThread isMainThread] || ![e isKindOfClass:MCMTLFarEngine.class] ||
            atomic_load_explicit(&e->stopped,memory_order_acquire) || e->nextAttempt!=1 ||
            atomic_load_explicit(&e->busy,memory_order_acquire) || e->shadeBudget)
            return failure(error,capacity,@"Enable shading once on worker before first submission");
        e->shadeBudget=budget; return MCMTL_OK;
    } @catch (NSException *ex) { message(error,capacity,ex.reason); return MCMTL_E_OBJC_EXCEPTION; }
}

/* Called only under busy. Cost/allocation is on the worker; frame-side acquire
 * never traverses inputs. Packet/snapshot themselves validate geometry ranges,
 * ID disjointness, material ownership and the precise pipeline/device formats. */
static int32_t submit(MCMTLFarEngine *e, uint64_t attempt, mcmtl_far_engine_submission s,
    NSData *refreshView, char *error, uint32_t capacity) {
    MCMTLFarGeometryRecipe *g=object(s.geometryRecipe);
    MCMTLFarMaterialVersion *m=object(s.materialVersion);
    if (!g || !m || !s.inputCount || s.inputCount>4096 || s.inputCount!=g.inputs.count || !s.uniforms ||
        s.reserved || s.committed!=1 || !s.committedTicketSequence || !s.geometryRequiredValue ||
        s.committedTicketSequence<s.geometryRequiredValue || !cameraValid(s.camera) ||
        g.device!=e->device || !g.uploadEvent || (g.uploadEvent.device && g.uploadEvent.device!=e->device) || !m.isPublished ||
        m.readyEvent!=g.uploadEvent || !m.readyValue || s.committedTicketSequence<m.readyValue)
        return failure(error,capacity,@"Invalid capture or committed geometry/material ticket dependency");
    for (unsigned code=0; code<MCMTL_FAR_ENGINE_SHADER_COUNT; ++code)
        if (code!=0 && code!=1 && code!=8 && code!=9 && s.shadersByLayer[code])
            return failure(error,capacity,@"Unsupported shader layer code");

    MCMTLFarEngineInputs *inputs=[MCMTLFarEngineInputs new];
    inputs->geometry=g; inputs->material=m; inputs->camera=s.camera;
    inputs->ticket=s.committedTicketSequence; inputs->required=s.geometryRequiredValue;
    NSMutableArray *owned=[NSMutableArray arrayWithCapacity:s.inputCount];
    /* This array holds only nonowning draw references. owned/shaders/material
     * keep every object alive until the packet has copied and retained them. */
    NSMutableData *drawData=[NSMutableData dataWithLength:s.inputCount*sizeof(MCMTLFarProducerDraw)];
    MCMTLFarProducerDraw *draws=drawData.mutableBytes;
    NSMutableArray *shaders=[NSMutableArray arrayWithCapacity:s.inputCount];
    uint64_t uniformBytes=0;
    for (uint32_t i=0; i<s.inputCount; ++i) {
        uint32_t code=g.inputs[i].layerCode;
        if (code!=0 && code!=1 && code!=8 && code!=9) return failure(error,capacity,@"Unsupported geometry layer code");
        MCMTLFarShader *shader=object(s.shadersByLayer[code]);
        if (!supported(shader)) return failure(error,capacity,@"Unknown built-in binding slot or push constants");
        [shaders addObject:shader];
        MCMTLFarShaderBindings b=shader.bindings;
        uint32_t masks[2]={b.bufferMask[0]|b.requiredVertexBufferMask,b.bufferMask[1]};
        mcmtl_far_engine_uniforms in=s.uniforms[i];
        mcmtl_far_bytes bytes[4]={in.projection,in.globals,in.fog,in.terrain};
        NSMutableArray *copies=[NSMutableArray arrayWithCapacity:4];
        draws[i].shader=shader;
        draws[i].viewport=(MTLViewport){0,0,e->width,e->height,0,1};
        draws[i].scissor=(MTLScissorRect){0,0,e->width,e->height};
        draws[i].depthClipMode=MTLDepthClipModeClip;
        for (unsigned u=0; u<4; ++u) {
            uint32_t bit=1u<<slots[u];
            BOOL needed=((masks[0]|masks[1]) & bit)!=0;
            if ((needed || bytes[u].length || bytes[u].bytes) && (!bytes[u].bytes || bytes[u].length!=sizes[u]))
                return failure(error,capacity,@"Built-in UBO must have its exact captured size");
            NSData *copy=bytes[u].length ? [NSData dataWithBytes:bytes[u].bytes length:sizes[u]] : [NSData data];
            if (u==0 && copy.length && memcmp(copy.bytes,s.camera.guardProjection,64))
                return failure(error,capacity,@"Source camera and bound guard projection differ");
            [copies addObject:copy];
            for (unsigned stage=0; stage<2; ++stage) if (masks[stage] & bit) {
                draws[i].stages[stage].buffers[slots[u]]=copy;
                uniformBytes+=copy.length;
            }
        }
        [owned addObject:[copies copy]];
        for (unsigned stage=0; stage<2; ++stage) {
            unsigned slot=stage ? 0 : 2;
            mcmtl_far_material_binding binding=[m bindingAtIndex:stage ? MCMTLFarMaterialAtlas : MCMTLFarMaterialLightmap];
            if (b.textureMask[stage] & (1u<<slot)) draws[i].stages[stage].textures[slot]=object(binding.texture);
            if (b.samplerMask[stage] & (1u<<slot)) {
                draws[i].stages[stage].samplers[slot]=object(binding.sampler);
                draws[i].stages[stage].samplerLODClamp[slot]=binding.desc.samplerLODClamp!=0;
                draws[i].stages[stage].samplerLODMin[slot]=binding.desc.lodMin;
                draws[i].stages[stage].samplerLODMax[slot]=binding.desc.lodMax;
            }
        }
    }
    inputs->uniforms=[owned copy];
    if (uniformBytes>=e->packetBudget) return failure(error,capacity,@"Packet uniform buffer budget exhausted");
    if (!eligible(e,s.source.epoch)) return MCMTL_FAR_ENGINE_DECLINED;
    MCMTLFarImageSource source={s.source.epoch,s.source.sourceTimeNs,s.source.sourceFrame,
        s.source.geometryGeneration,s.source.materialGeneration};
    MCMTLFarImageReservation *reservation=[e->owner newReservation];
    if (!reservation) return failure(error,capacity,@"Cannot allocate reservation");
    // Reserve a free image BEFORE allocating its private cull outputs. If main
    // consumers still hold all three slots, defer this refresh without allocation
    // or a spurious snapshot-capacity error. Failed setup abandons the reservation.
    if (![e->owner tryReserve:reservation source:source inputs:inputs]) return MCMTL_FAR_ENGINE_DECLINED;
    NSError *local=nil;
    // One cache per immutable recipe; camera-only refreshes never re-upload or
    // revalidate the terrain metadata. Preserve the old per-image packet limit
    // and at-most-three-images aggregate bound. Uniform presence may change;
    // it must not change the cache's fixed aggregate budget.
    MCMTLFarCullGeometry *geometry=[g prepareCullGeometryWithDevice:e->cull budgetBytes:e->packetBudget*3u error:&local];
    if (!geometry) return failure(error,capacity,local.localizedDescription);
    MCMTLFarCullSnapshot *snapshot=[[MCMTLFarCullSnapshot alloc] initWithGeometry:geometry
        view:refreshView ?: g.view budgetBytes:e->packetBudget-uniformBytes error:&local];
    if (!snapshot) {
        // A retired image becomes free before its previous inputs are destroyed
        // outside the owner lock. During that tiny interval the immutable owner
        // can still have three snapshots. It is backpressure, not a fatal error.
        if (local.code==MCMTLFarSnapshotCapacityError && [local.domain isEqualToString:@"mcmetal.far.snapshot"])
            return MCMTL_FAR_ENGINE_DECLINED;
        return failure(error,capacity,local.localizedDescription);
    }
    if (e->shadeBudget) {
        NSMutableArray *programs=[NSMutableArray arrayWithCapacity:4];
        const unsigned codes[4]={0,1,8,9};
        for (unsigned i=0;i<4;++i) {
            MCMTLFarShader *p=object(s.shadersByLayer[codes[i]]);
            if (!supported(p) || p.pipelineState.device!=e->device ||
                p.layer!=(codes[i]&1u) || p.layout!=(i<2?MCMTLFarShaderBlock:MCMTLFarShaderCompact))
                return failure(error,capacity,@"Shading needs all four actual paired producer programs");
            [programs addObject:p];
        }
        if (e->shading && (e->shadeRecipe!=g || ![programs isEqualToArray:e->shadeOriginals]))
            return failure(error,capacity,@"Shading generation changed; replace the bounded prototype engine");
        if (!e->shading) {
            MCMTLFarEngineShading *shading=[MCMTLFarEngineShading new];
            shading.solid=[[MCMTLFarShade alloc] initWithBlockProgram:programs[0] compactProgram:programs[2] error:&local];
            if (!shading.solid) return failure(error,capacity,local.localizedDescription);
            shading.cutout=[[MCMTLFarShade alloc] initWithBlockProgram:programs[1] compactProgram:programs[3] error:&local];
            if (!shading.cutout) return failure(error,capacity,local.localizedDescription);
            // Reserve space for the fixed argument table before constructing
            // lookup GPU tables; the lookup's own peak scratch bound still applies.
            const uint64_t argumentReserve=64u<<10;
            if (e->shadeBudget<=argumentReserve) return failure(error,capacity,@"Shading lookup budget too small");
            MCMTLFarFaceLookup *lookup=[[MCMTLFarFaceLookup alloc] initWithSnapshot:snapshot
                budgetBytes:e->shadeBudget-argumentReserve error:&local];
            if (!lookup) return failure(error,capacity,local.localizedDescription);
            shading.geometry=[shading.solid prepareLookup:lookup budgetBytes:argumentReserve error:&local];
            if (!shading.geometry) return failure(error,capacity,local.localizedDescription);
            fprintf(stderr,"[mcmetal] Far shade lookup prepared: GPU %.3f MiB, CPU scratch %.3f MiB, peak %.3f MiB, budget %.3f MiB\n",
                lookup.gpuBufferBytes/1048576.0,lookup.cpuScratchBytes/1048576.0,
                lookup.peakBuildBytes/1048576.0,e->shadeBudget/1048576.0);
            e->shading=shading; e->shadeRecipe=g; e->shadeOriginals=[programs copy];
        }
        inputs->shading=e->shading;
    }
    MCMTLFarProducerClears clears={0};
    for (unsigned l=0; l<2; ++l) {
        clears.color[l]=MTLClearColorMake(s.clearColor[l][0],s.clearColor[l][1],s.clearColor[l][2],s.clearColor[l][3]);
        clears.depth[l]=s.clearDepth[l];
    }
    inputs->packet=[[MCMTLFarProducerPacket alloc] initWithCullDevice:e->cull snapshot:snapshot draws:draws
        count:s.inputCount width:e->width height:e->height clears:clears materialLease:m error:&local];
    if (!inputs->packet) return failure(error,capacity,local.localizedDescription);
    BOOL accepted=[reservation encodeAndSubmit:^BOOL(id<MTLCommandBuffer> cb, MCMTLFarImageView image, NSError **encodeError) {
        if (!mcmtl_farproducer_encode(inputs->packet,snapshot,cb,image,g.uploadEvent,s.committedTicketSequence,encodeError)) return NO;
        /* Only the producer admission flag is released here. Consumer lifetime
         * is exclusively protected by its owned image lease/main completion. */
        [cb addCompletedHandler:^(id<MTLCommandBuffer> done) {
            (void)done;
            uint64_t expected=attempt;
            atomic_compare_exchange_strong_explicit(&e->busy,&expected,0,memory_order_acq_rel,memory_order_acquire);
        }];
        return YES;
    }];
    if (accepted) return MCMTL_OK;
    if (!eligible(e,s.source.epoch)) return MCMTL_FAR_ENGINE_DECLINED;
    return failure(error,capacity,e->owner.lastError.localizedDescription ?: @"Far submission failed");
}

static int32_t submit_entry(mcmtl_handle h, const mcmtl_far_engine_submission *s,
    const mcmtl_far_bytes *view, BOOL requireView, char *error, uint32_t capacity) {
    message(error,capacity,nil);
    if (!h || !s || (capacity && !error)) return MCMTL_E_INVALID_ARGUMENT;
    if (s->structSize!=sizeof(*s)) return MCMTL_E_ABI_MISMATCH;
    if (requireView && (!view || !view->bytes || view->length != 128)) return MCMTL_E_INVALID_ARGUMENT;
    @autoreleasepool { @try {
        if ([NSThread isMainThread]) return failure(error,capacity,@"Far submit requires worker");
        MCMTLFarEngine *e=object(h);
        if (!eligible(e,s->source.epoch)) return MCMTL_FAR_ENGINE_DECLINED;
        uint64_t expected=0;
        if (!atomic_compare_exchange_strong_explicit(&e->busy,&expected,UINT64_MAX,memory_order_acq_rel,memory_order_acquire))
            return MCMTL_FAR_ENGINE_DECLINED;
        if (e->nextAttempt==UINT64_MAX) {
            atomic_store_explicit(&e->busy,0,memory_order_release);
            return failure(error,capacity,@"Producer attempt sequence exhausted");
        }
        uint64_t attempt=e->nextAttempt++;
        atomic_store_explicit(&e->busy,attempt,memory_order_release);
        int32_t result=MCMTL_E_INTERNAL;
        @try {
            // Admission happens before even this small copy. No live Java memory survives the call.
            NSData *refreshView=requireView ? [NSData dataWithBytes:view->bytes length:128] : nil;
            result=submit(e,attempt,*s,refreshView,error,capacity);
        }
        @finally {
            if (result!=MCMTL_OK) {
                uint64_t active=attempt;
                atomic_compare_exchange_strong_explicit(&e->busy,&active,0,memory_order_acq_rel,memory_order_acquire);
            }
        }
        return result;
    } @catch (NSException *ex) { message(error,capacity,ex.reason); return MCMTL_E_OBJC_EXCEPTION; } }
}

MCMTL_API int32_t mcmtl_ext_far_engine_submit(mcmtl_handle h, const mcmtl_far_engine_submission *s,
    char *error, uint32_t capacity) {
    return submit_entry(h,s,NULL,NO,error,capacity);
}
MCMTL_API int32_t mcmtl_ext_far_engine_submit_view(mcmtl_handle h, const mcmtl_far_engine_submission *s,
    const mcmtl_far_bytes *view, char *error, uint32_t capacity) {
    return submit_entry(h,s,view,YES,error,capacity);
}

MCMTL_API int32_t mcmtl_ext_far_engine_acquire(mcmtl_handle h, uint64_t epoch,
    mcmtl_handle *outLease, uint32_t *outCount) {
    if (outLease) *outLease=0;
    if (outCount) *outCount=0;
    if (!h || !epoch || !outLease || !outCount) return MCMTL_E_INVALID_ARGUMENT;
    @autoreleasepool { @try {
        MCMTLFarEngine *e=object(h);
        MCMTLFarImageLease *lease=[e->owner tryAcquireForEpoch:epoch];
        if (!lease) return MCMTL_FAR_ENGINE_DECLINED;
        *outCount=(uint32_t)lease.count;
        *outLease=(mcmtl_handle)(uintptr_t)(__bridge_retained void *)lease;
        return MCMTL_OK;
    } @catch (NSException *ex) { (void)ex; return MCMTL_E_OBJC_EXCEPTION; } }
}

MCMTL_API int32_t mcmtl_ext_far_engine_image(mcmtl_handle h, uint32_t index, mcmtl_far_engine_image *out) {
    if (!h || !out) return MCMTL_E_INVALID_ARGUMENT;
    if (out->structSize!=sizeof(*out)) return MCMTL_E_ABI_MISMATCH;
    *out=(mcmtl_far_engine_image){.structSize=sizeof(*out)};
    @autoreleasepool { @try {
        MCMTLFarImageLease *lease=object(h);
        if (index>=lease.count) return MCMTL_E_INVALID_ARGUMENT;
        MCMTLFarImageView v=[lease imageAtIndex:index];
        MCMTLFarEngineInputs *in=v.inputs;
        if (![in isKindOfClass:MCMTLFarEngineInputs.class]) return MCMTL_E_INVALID_ARGUMENT;
        out->width=(uint32_t)v.width; out->height=(uint32_t)v.height; out->inputCount=(uint32_t)in->packet.count;
        out->source=(mcmtl_far_engine_source){v.source.epoch,v.source.sourceTimeNs,v.source.sourceFrame,
            v.source.geometryGeneration,v.source.materialGeneration};
        out->camera=in->camera; out->sequence=v.sequence; out->committedTicketSequence=in->ticket; out->geometryRequiredValue=in->required;
        for (unsigned l=0; l<2; ++l) {
            out->color[l]=handle(v.color[l]); out->depth[l]=handle(v.depth[l]); out->visibilityID[l]=handle(v.visibilityID[l]);
        }
        out->geometryRecipe=handle(in->geometry); out->materialVersion=handle(in->material); out->producerPacket=handle(in->packet);
        return MCMTL_OK;
    } @catch (NSException *ex) { (void)ex; return MCMTL_E_OBJC_EXCEPTION; } }
}

MCMTL_API int32_t mcmtl_ext_far_engine_timing(mcmtl_handle h, uint32_t index, mcmtl_far_engine_timing *out) {
    if (!out) return MCMTL_E_INVALID_ARGUMENT;
    uint32_t size = out->structSize;
    *out = (mcmtl_far_engine_timing){0};
    if (!h) return MCMTL_E_INVALID_ARGUMENT;
    if (size != sizeof(*out)) return MCMTL_E_ABI_MISMATCH;
    @autoreleasepool { @try {
        MCMTLFarImageLease *lease = object(h);
        if (![lease isKindOfClass:MCMTLFarImageLease.class] || index >= lease.count)
            return MCMTL_E_INVALID_ARGUMENT;
        MCMTLFarImageTiming timing;
        if (![lease timingAtIndex:index into:&timing]) return MCMTL_FAR_ENGINE_DECLINED;
        MCMTLFarImageView image = [lease imageAtIndex:index];
        *out = (mcmtl_far_engine_timing){
            .structSize = sizeof(*out), .flags = timing.flags,
            .sourceTimeNs = image.source.sourceTimeNs, .reserveTimeNs = timing.reserveTimeNs,
            .encodeStartNs = timing.encodeStartNs, .commitStartNs = timing.commitStartNs,
            .commitEndNs = timing.commitEndNs, .gpuStartNs = timing.gpuStartNs,
            .gpuEndNs = timing.gpuEndNs, .completionNs = timing.completionNs, .sequence = image.sequence
        };
        return MCMTL_OK;
    } @catch (NSException *ex) { (void)ex; return MCMTL_E_OBJC_EXCEPTION; } }
}

MCMTL_API int32_t mcmtl_ext_far_engine_uniform(mcmtl_handle h, uint32_t index, uint32_t input,
    mcmtl_far_engine_uniform_copy *out) {
    if (!h || !out) return MCMTL_E_INVALID_ARGUMENT;
    if (out->structSize!=sizeof(*out)) return MCMTL_E_ABI_MISMATCH;
    *out=(mcmtl_far_engine_uniform_copy){.structSize=sizeof(*out)};
    @autoreleasepool { @try {
        MCMTLFarImageLease *lease=object(h);
        if (index>=lease.count) return MCMTL_E_INVALID_ARGUMENT;
        MCMTLFarEngineInputs *in=[lease imageAtIndex:index].inputs;
        if (![in isKindOfClass:MCMTLFarEngineInputs.class] || input>=in->uniforms.count) return MCMTL_E_INVALID_ARGUMENT;
        uint8_t *dest[4]={out->projection,out->globals,out->fog,out->terrain};
        for (unsigned u=0; u<4; ++u) {
            NSData *bytes=in->uniforms[input][u];
            if (bytes.length) { memcpy(dest[u],bytes.bytes,sizes[u]); out->presentMask|=1u<<slots[u]; }
        }
        return MCMTL_OK;
    } @catch (NSException *ex) { (void)ex; return MCMTL_E_OBJC_EXCEPTION; } }
}

MCMTL_API int32_t mcmtl_ext_far_engine_attach(mcmtl_handle h, mcmtl_handle buffer) {
    if (!h || !buffer) return MCMTL_E_INVALID_ARGUMENT;
    @autoreleasepool { @try {
        return [(MCMTLFarImageLease *)object(h) attachToCommandBuffer:object(buffer)] ? MCMTL_OK : MCMTL_FAR_ENGINE_DECLINED;
    } @catch (NSException *ex) { (void)ex; return MCMTL_E_OBJC_EXCEPTION; } }
}

MCMTLFarEngineShading *mcmtl_far_engine_shading(mcmtl_handle h,uint32_t index) {
    if (!h) return nil;
    @try {
        MCMTLFarImageLease *lease=object(h);
        if (![lease isKindOfClass:MCMTLFarImageLease.class] || index>=lease.count) return nil;
        MCMTLFarEngineInputs *inputs=[lease imageAtIndex:index].inputs;
        return [inputs isKindOfClass:MCMTLFarEngineInputs.class]?inputs->shading:nil;
    } @catch(NSException *ex) { (void)ex; return nil; }
}

MCMTL_API int32_t mcmtl_ext_far_engine_invalidate(mcmtl_handle h, uint64_t epoch) {
    if (!h || !epoch) return MCMTL_E_INVALID_ARGUMENT;
    @autoreleasepool { @try {
        if (![NSThread isMainThread]) return MCMTL_E_INVALID_ARGUMENT;
        MCMTLFarEngine *e=object(h);
        if (![e->owner invalidateToEpoch:epoch]) return MCMTL_FAR_ENGINE_DECLINED;
        atomic_store_explicit(&e->epoch,epoch,memory_order_release);
        return MCMTL_OK;
    } @catch (NSException *ex) { (void)ex; return MCMTL_E_OBJC_EXCEPTION; } }
}
MCMTL_API int32_t mcmtl_ext_far_engine_stop(mcmtl_handle h) {
    if (!h) return MCMTL_E_INVALID_ARGUMENT;
    @autoreleasepool { @try {
        if (![NSThread isMainThread]) return MCMTL_E_INVALID_ARGUMENT;
        MCMTLFarEngine *e=object(h);
        atomic_store_explicit(&e->stopped,true,memory_order_release);
        [e->owner stop]; return MCMTL_OK;
    } @catch (NSException *ex) { (void)ex; return MCMTL_E_OBJC_EXCEPTION; } }
}
MCMTL_API int32_t mcmtl_ext_far_engine_error(mcmtl_handle h, char *out, uint32_t capacity) {
    message(out,capacity,nil);
    if (!h || !out || !capacity) return MCMTL_E_INVALID_ARGUMENT;
    @autoreleasepool { @try {
        MCMTLFarEngine *e=object(h); message(out,capacity,e->owner.lastError.localizedDescription); return MCMTL_OK;
    } @catch (NSException *ex) { message(out,capacity,ex.reason); return MCMTL_E_OBJC_EXCEPTION; } }
}
MCMTL_API void mcmtl_ext_far_engine_drop(mcmtl_handle h) {
    @autoreleasepool { @try { if (h) { id released=(__bridge_transfer id)(void *)(uintptr_t)h; (void)released; } }
        @catch (NSException *ex) { (void)ex; } }
}
