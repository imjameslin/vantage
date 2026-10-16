#import "mcmetal_farconsumer.h"
#import "mcmetal_farengine.h"
#import "mcmetal_farimage.h"
#import "mcmetal_farresolve.h"
#import "mcmetal_farlayerresolve.h"
#import "mcmetal_faralpha.h"
#import "mcmetal_farshade.h"
#import "mcmetal_internal.h"
#include <math.h>
#include <stddef.h>
#include <stdio.h>
#include <stdarg.h>
#include <string.h>
#include <time.h>

_Static_assert(sizeof(MCMTLFarWarpTransform)==144, "FFM warp transform");
_Static_assert(sizeof(mcmtl_far_consumer_fallback)==16, "FFM fallback descriptor");
_Static_assert(sizeof(mcmtl_far_consumer_shading)==352, "FFM shaded descriptor");
_Static_assert(offsetof(mcmtl_far_consumer_shading,committedSequence)==8, "FFM shaded committed sequence");
_Static_assert(offsetof(mcmtl_far_consumer_shading,uniforms)==16, "FFM shaded uniforms");
_Static_assert(offsetof(mcmtl_far_consumer_shading,current)==256, "FFM shaded materials");
_Static_assert(sizeof(mcmtl_far_material_source)==48, "FFM material source");
_Static_assert(sizeof(MCMTLFarShadeUniforms)==240, "Shaded uniform byte layouts");
_Static_assert(sizeof(mcmtl_far_consumer_outputs)==64, "FFM consumer outputs");
_Static_assert(offsetof(mcmtl_far_consumer_outputs,color)==16, "FFM color offset");
_Static_assert(offsetof(mcmtl_far_consumer_outputs,depth)==32, "FFM depth offset");
_Static_assert(offsetof(mcmtl_far_consumer_outputs,fallbackArgs)==48, "FFM fallback offset");
_Static_assert(offsetof(mcmtl_far_consumer_outputs,compositeArgs)==56, "FFM composite offset");
_Static_assert(sizeof(mcmtl_far_consumer_stats)==80, "FFM receipt");
_Static_assert(offsetof(mcmtl_far_consumer_stats,encodedSequence)==8, "FFM receipt sequence");
_Static_assert(offsetof(mcmtl_far_consumer_stats,sourceFrame)==16, "FFM receipt frame");
_Static_assert(offsetof(mcmtl_far_consumer_stats,sourceAgeNs)==32, "FFM receipt age");
_Static_assert(offsetof(mcmtl_far_consumer_stats,holePixels)==48, "FFM receipt holes");
_Static_assert(offsetof(mcmtl_far_consumer_stats,reserved64)==56, "FFM alpha rejection receipt");
_Static_assert(offsetof(mcmtl_far_consumer_stats,syncFallback)==64, "FFM receipt fallback");
_Static_assert(sizeof(MCMTLFarLayerResolveStats)==16, "Layer resolve GPU stats");
_Static_assert(MCMTLFarShadeDiagnosticCount==16, "Layer shade histogram ABI");
_Static_assert(sizeof(mcmtl_far_consumer_layer_outputs)==64, "FFM layer outputs");
_Static_assert(sizeof(mcmtl_far_consumer_layer_stats)==96, "FFM independent layer receipt");
_Static_assert(offsetof(mcmtl_far_consumer_layer_stats,encodedSequence)==8, "FFM layer sequence");
_Static_assert(offsetof(mcmtl_far_consumer_layer_stats,sourceFrame)==16, "FFM layer source frame");
_Static_assert(offsetof(mcmtl_far_consumer_layer_stats,sourceAgeNs)==24, "FFM layer source age");
_Static_assert(offsetof(mcmtl_far_consumer_layer_stats,holePixels)==32, "FFM layer holes");
_Static_assert(offsetof(mcmtl_far_consumer_layer_stats,syncFallback)==48, "FFM layer fallback");
_Static_assert(offsetof(mcmtl_far_consumer_layer_stats,alphaRejected)==56, "FFM layer alpha");
_Static_assert(offsetof(mcmtl_far_consumer_layer_stats,drawCount)==64, "FFM layer draw counts");
_Static_assert(offsetof(mcmtl_far_consumer_layer_stats,reserved)==72, "FFM layer reserved");

_Static_assert(MCMTLFarShadeDiagnosticFullBytes==384 && MCMTLFarShadeDiagnosticSampleWords==80 &&
    MCMTLFarShadeDiagnosticSampleOffsetBytes==64, "Layer shade sample ABI");
enum { ReceiptSlots=3, TargetSets=6 };
/* Runs AFTER unchanged resolve. Every fallback word is copied, preserving signed
 * baseVertex bits. Alpha acceptance touches nothing, including hole fallback. */
static NSString *const shadedFallbackMSL=@
"#include <metal_stdlib>\nusing namespace metal;\n"
"kernel void far_shaded_fallback(device const uint *alpha [[buffer(0)]],\n"
" device const uint *source [[buffer(1)]], device uint *fallback [[buffer(2)]],\n"
" device uint *composite [[buffer(3)]], device uint *stats [[buffer(4)]],\n"
" constant uint &count [[buffer(5)]], uint i [[thread_position_in_grid]]) {\n"
" if (*alpha==0u) return;\n"
" if (i<count*5u) fallback[i]=source[i];\n"
" if (i<4u) composite[i]=0u;\n"
" if (i==0u) stats[1]=1u;\n}\n";
static id object(mcmtl_handle h) { return (__bridge id)(void *)(uintptr_t)h; }
static mcmtl_handle retainHandle(id o) { return (mcmtl_handle)(uintptr_t)(__bridge_retained void *)o; }
static void message(char *out, uint32_t capacity, NSString *text) {
    if (out && capacity) snprintf(out,capacity,"%s",text.UTF8String ?: "");
}
static int32_t failure(char *out, uint32_t capacity, NSString *text) {
    message(out,capacity,text ?: @"Far consumer failed; discard frame reuse");
    return MCMTL_E_UNSUPPORTED;
}

@interface MCMTLFarConsumer : NSObject {
@public
    MCMTLDevice *mainDevice; /* Completion/error reporting owns the wrapper too. */
    id<MTLDevice> device;
    id<MTLCommandQueue> queue; /* Existing MAIN queue, never a producer queue. */
    MCMTLFarWarp *warp;
    MCMTLFarResolve *resolve;
    MCMTLFarAlpha *alpha;
    id<MTLComputePipelineState> shadedFallback;
    id<MTLTexture> color[TargetSets], depth[TargetSets], ids[TargetSets], validity[TargetSets];
    id<MTLTexture> holeMask;
    id<MTLBuffer> packedSource, fallbackArgs, compositeArgs, gpuStats[ReceiptSlots];
    id<MTLBuffer> alphaResult[ReceiptSlots];
    uint32_t width, height, maxDraws;
    os_unfair_lock lock;
    BOOL busy[ReceiptSlots], poisoned;
    uint64_t lastSequence;
    mcmtl_far_consumer_stats receipt;
    /* Optional worker-prepared sidecar. None of these changes old create's
     * resource budget, argument buffers or completed receipt semantics. */
    MCMTLFarLayerResolve *layerResolve;
    id<MTLBuffer> layerFallbackArgs,layerCompositeArgs;
    id<MTLBuffer> layerCodes[ReceiptSlots],layerGpuStats[ReceiptSlots];
    id<MTLBuffer> shadeDiagnostics[ReceiptSlots][2];
    uint32_t layerDiagnosticReceipt[2][16];
    uint64_t layerDiagnosticSequence;
    BOOL layerDiagnosticSampleLogged;
    BOOL layersReady;
    mcmtl_far_consumer_layer_stats layerReceipt;
}
@end
@implementation MCMTLFarConsumer
+ (BOOL)accessInstanceVariablesDirectly { return NO; }
@end

static MCMTLFarWarpTargets targets(MCMTLFarConsumer *c, unsigned index) {
    return (MCMTLFarWarpTargets){c->color[index],c->depth[index],c->ids[index],c->validity[index]};
}
static MCMTLFarResolvePair pair(MCMTLFarConsumer *c, unsigned first) {
    return (MCMTLFarResolvePair){{targets(c,first),targets(c,first+1)}};
}
static id<MTLTexture> texture(MCMTLFarConsumer *c, MTLPixelFormat format, BOOL renderTarget) {
    MTLTextureDescriptor *d=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:format
        width:c->width height:c->height mipmapped:NO];
    d.storageMode=MTLStorageModePrivate;
    d.hazardTrackingMode=MTLHazardTrackingModeTracked;
    d.usage=MTLTextureUsageShaderRead|MTLTextureUsageShaderWrite;
    if (renderTarget) d.usage|=MTLTextureUsageRenderTarget;
    return [c->device newTextureWithDescriptor:d];
}
/* Diagnostic-only completed GPU payload. Never infer readiness from claim on
 * a live buffer; completion copied all 80 words before any of these accesses. */
static float sampleFloat(const uint32_t *word) {
    float value; memcpy(&value,word,sizeof value); return value;
}
static BOOL validSolidSample(const uint32_t *s,uint32_t width,uint32_t height) {
    if (s[MCMTLFarShadeSampleClaim]!=1 || s[MCMTLFarShadeSampleReason]!=MCMTLFarShadeDiagnosticDegenerate ||
        !s[MCMTLFarShadeSampleFace] || s[MCMTLFarShadeSampleX]>=width || s[MCMTLFarShadeSampleY]>=height ||
        s[MCMTLFarShadeSampleTriangle]>1 || (s[MCMTLFarShadeSampleLayout]!=1 && s[MCMTLFarShadeSampleLayout]!=2) ||
        s[MCMTLFarShadeSampleArena]>=64 || s[MCMTLFarShadeSampleViewport]!=width || s[MCMTLFarShadeSampleViewport+1]!=height ||
        s[MCMTLFarShadeSampleFlags]!=3) return NO;
    float depth=sampleFloat(s+MCMTLFarShadeSampleCapturedDepthBits);
    if (!isfinite(depth) || depth<=0 || depth>1) return NO;
    for (unsigned i=MCMTLFarShadeSampleReserved;i<MCMTLFarShadeDiagnosticSampleWords;++i) if (s[i]) return NO;
    return YES;
}
static void sampleAppend(char *text,size_t capacity,size_t *used,const char *format,...)
    __attribute__((format(printf,4,5)));
static void sampleAppend(char *text,size_t capacity,size_t *used,const char *format,...) {
    if (*used>=capacity-1) return;
    va_list args;va_start(args,format);
    int n=vsnprintf(text+*used,capacity-*used,format,args);va_end(args);
    if (n>0) *used+=(size_t)n<capacity-*used ? (size_t)n : capacity-*used-1;
}
static void logSolidSample(uint64_t sequence,uint64_t sourceFrame,const uint32_t *s) {
    char text[4096]={0};size_t used=0;
    sampleAppend(text,sizeof text,&used,"[mcmetal] far shade SOLID reason9 sample sequence=%llu sourceFrame=%llu faceID=%u xy=(%u,%u) triangle=%u layout=%u arena=%u section=%u q=%u area=%a roundoff=%a lengths=(%a,%a,%a) viewport=(%u,%u)",
        (unsigned long long)sequence,(unsigned long long)sourceFrame,s[MCMTLFarShadeSampleFace],
        s[MCMTLFarShadeSampleX],s[MCMTLFarShadeSampleY],s[MCMTLFarShadeSampleTriangle],s[MCMTLFarShadeSampleLayout],
        s[MCMTLFarShadeSampleArena],s[MCMTLFarShadeSampleSection],s[MCMTLFarShadeSampleQuad],
        (double)sampleFloat(s+MCMTLFarShadeSampleAreaBits),(double)sampleFloat(s+MCMTLFarShadeSampleRoundoffBits),
        (double)sampleFloat(s+MCMTLFarShadeSampleLengthsBits),(double)sampleFloat(s+MCMTLFarShadeSampleLengthsBits+1),
        (double)sampleFloat(s+MCMTLFarShadeSampleLengthsBits+2),s[MCMTLFarShadeSampleViewport],s[MCMTLFarShadeSampleViewport+1]);
    sampleAppend(text,sizeof text,&used," screen4=[");
    for (unsigned i=0;i<4;++i) sampleAppend(text,sizeof text,&used,"%s(%a,%a)",i?",":"",
        (double)sampleFloat(s+MCMTLFarShadeSampleScreenBits+i*2),(double)sampleFloat(s+MCMTLFarShadeSampleScreenBits+i*2+1));
    sampleAppend(text,sizeof text,&used,"] clip4=[");
    for (unsigned i=0;i<4;++i) sampleAppend(text,sizeof text,&used,"%s(%a,%a,%a,%a)",i?",":"",
        (double)sampleFloat(s+MCMTLFarShadeSampleCornerClipBits+i*4),(double)sampleFloat(s+MCMTLFarShadeSampleCornerClipBits+i*4+1),
        (double)sampleFloat(s+MCMTLFarShadeSampleCornerClipBits+i*4+2),(double)sampleFloat(s+MCMTLFarShadeSampleCornerClipBits+i*4+3));
    sampleAppend(text,sizeof text,&used,"] rawVertexWords=[");
    unsigned words=s[MCMTLFarShadeSampleLayout]==1?28:12;
    for (unsigned i=0;i<words;++i) sampleAppend(text,sizeof text,&used,"%s%08x",i?",":"",s[MCMTLFarShadeSampleRawVertexWords+i]);
    int32_t section[4];memcpy(section,s+MCMTLFarShadeSampleSectionWords,sizeof section);
    sampleAppend(text,sizeof text,&used,"] sectionInt4=(%d,%d,%d,%d) capturedDepth=%a capturedDepthBits=%08x flags=%u",
        section[0],section[1],section[2],section[3],(double)sampleFloat(s+MCMTLFarShadeSampleCapturedDepthBits),
        s[MCMTLFarShadeSampleCapturedDepthBits],s[MCMTLFarShadeSampleFlags]);
    // One bounded stderr write, outside the receipt lock, diagnostics only. The
    // device message ring truncates at 1024 bytes, so cannot carry this payload.
    fprintf(stderr,"%s\n",text);
}
static void poison(MCMTLFarConsumer *c) {
    os_unfair_lock_lock(&c->lock);
    c->poisoned=YES;
    c->receipt=(mcmtl_far_consumer_stats){.structSize=sizeof(c->receipt),.flags=MCMTL_FAR_CONSUMER_POISONED};
    os_unfair_lock_unlock(&c->lock);
}

MCMTL_API int32_t mcmtl_ext_far_consumer_create(mcmtl_handle h, uint32_t width,
    uint32_t height, uint32_t maxDraws, uint64_t budget, mcmtl_handle *out,
    char *error, uint32_t capacity) {
    if (out) *out=0;
    message(error,capacity,nil);
    if (!h || !out || (capacity && !error)) return MCMTL_E_INVALID_ARGUMENT;
    @autoreleasepool { @try {
        if ([NSThread isMainThread] || !width || !height || width>16384 || height>16384 ||
            !maxDraws || maxDraws>MCMTL_FAR_CONSUMER_MAX_DRAWS || !budget)
            return failure(error,capacity,@"Consumer requires worker, dimensions 1..16384, draws 1..4096 and explicit budget");
        const uint64_t bytes=(uint64_t)width*height*79u+(uint64_t)maxDraws*40u+
            16u+ReceiptSlots*(sizeof(MCMTLFarResolveStats)+sizeof(uint32_t));
        if (bytes>budget) return failure(error,capacity,@"Consumer GPU resource budget exhausted");
        MCMTLDevice *main=object(h);
        if (![main isKindOfClass:MCMTLDevice.class] || !main->device || !main->queue || !main->status || main->queue.device!=main->device)
            return MCMTL_E_INVALID_ARGUMENT;
        MCMTLFarConsumer *c=[MCMTLFarConsumer new];
        c->mainDevice=main; c->device=main->device; c->queue=main->queue; c->width=width; c->height=height; c->maxDraws=maxDraws;
        c->lock=OS_UNFAIR_LOCK_INIT; c->receipt.structSize=sizeof(c->receipt);
        NSError *local=nil;
        c->warp=[[MCMTLFarWarp alloc] initWithDevice:c->device error:&local];
        if (!c->warp) return failure(error,capacity,local.localizedDescription);
        c->resolve=[[MCMTLFarResolve alloc] initWithDevice:c->device error:&local];
        if (!c->resolve) return failure(error,capacity,local.localizedDescription);
        c->alpha=[[MCMTLFarAlpha alloc] initWithDevice:c->device error:&local];
        if (!c->alpha) return failure(error,capacity,local.localizedDescription);
        id<MTLLibrary> library=[c->device newLibraryWithSource:shadedFallbackMSL options:nil error:&local];
        if (!library) return failure(error,capacity,local.localizedDescription);
        id<MTLFunction> function=[library newFunctionWithName:@"far_shaded_fallback"];
        if (!function) return failure(error,capacity,@"Missing shaded fallback kernel");
        c->shadedFallback=[c->device newComputePipelineStateWithFunction:function error:&local];
        if (!c->shadedFallback) return failure(error,capacity,local.localizedDescription);
        for (unsigned i=0; i<TargetSets; ++i) {
            BOOL shaded=i==2 || i==3;
            c->color[i]=texture(c,MTLPixelFormatRGBA8Unorm,shaded);
            c->depth[i]=texture(c,MTLPixelFormatR32Float,shaded);
            c->ids[i]=texture(c,MTLPixelFormatR32Uint,NO);
            c->validity[i]=texture(c,MTLPixelFormatR8Uint,shaded);
            if (!c->color[i] || !c->depth[i] || !c->ids[i] || !c->validity[i]) return MCMTL_E_OUT_OF_MEMORY;
        }
        c->holeMask=texture(c,MTLPixelFormatR8Uint,NO);
        MTLResourceOptions privateOptions=MTLResourceStorageModePrivate|MTLResourceHazardTrackingModeTracked;
        c->packedSource=[c->device newBufferWithLength:maxDraws*20u options:privateOptions];
        c->fallbackArgs=[c->device newBufferWithLength:maxDraws*20u options:privateOptions];
        c->compositeArgs=[c->device newBufferWithLength:16 options:privateOptions];
        if (!c->holeMask || !c->packedSource || !c->fallbackArgs || !c->compositeArgs) return MCMTL_E_OUT_OF_MEMORY;
        for (unsigned i=0; i<ReceiptSlots; ++i) {
            c->gpuStats[i]=[c->device newBufferWithLength:sizeof(MCMTLFarResolveStats)
                options:MTLResourceStorageModeShared|MTLResourceHazardTrackingModeTracked];
            if (!c->gpuStats[i] || !c->gpuStats[i].contents) return MCMTL_E_OUT_OF_MEMORY;
            c->alphaResult[i]=[c->device newBufferWithLength:sizeof(uint32_t)
                options:MTLResourceStorageModeShared|MTLResourceHazardTrackingModeTracked];
            if (!c->alphaResult[i] || !c->alphaResult[i].contents) return MCMTL_E_OUT_OF_MEMORY;
        }
        *out=retainHandle(c);
        return MCMTL_OK;
    } @catch (NSException *ex) { message(error,capacity,ex.reason); return MCMTL_E_OBJC_EXCEPTION; } }
}

MCMTL_API int32_t mcmtl_ext_far_consumer_outputs(mcmtl_handle h,
    mcmtl_far_consumer_outputs *out, char *error, uint32_t capacity) {
    message(error,capacity,nil);
    if (!out) return MCMTL_E_INVALID_ARGUMENT;
    uint32_t size=out->structSize;
    *out=(mcmtl_far_consumer_outputs){.structSize=sizeof(*out)};
    if (!h || (capacity && !error)) return MCMTL_E_INVALID_ARGUMENT;
    if (size!=sizeof(*out)) return MCMTL_E_ABI_MISMATCH;
    @autoreleasepool { @try {
        MCMTLFarConsumer *c=object(h);
        if (![c isKindOfClass:MCMTLFarConsumer.class]) return MCMTL_E_INVALID_ARGUMENT;
        if (!os_unfair_lock_trylock(&c->lock)) return MCMTL_FAR_CONSUMER_DECLINED;
        BOOL dead=c->poisoned;
        os_unfair_lock_unlock(&c->lock);
        if (dead) return MCMTL_FAR_CONSUMER_DECLINED;
        // These fixed ivars contain valid objects from successful construction.
        // No allocation or fallible operation occurs after the first +1 export.
        mcmtl_far_consumer_outputs result={.structSize=sizeof(result),.width=c->width,
            .height=c->height,.maxDraws=c->maxDraws};
        for (unsigned i=0; i<2; ++i) { result.color[i]=retainHandle(c->color[4+i]); result.depth[i]=retainHandle(c->depth[4+i]); }
        result.fallbackArgs=retainHandle(c->fallbackArgs); result.compositeArgs=retainHandle(c->compositeArgs);
        *out=result;
        return MCMTL_OK;
    } @catch (NSException *ex) { message(error,capacity,ex.reason); return MCMTL_E_OBJC_EXCEPTION; } }
}

MCMTL_API int32_t mcmtl_ext_far_consumer_enable_layers(mcmtl_handle h,
    uint64_t extraBudget, char *error, uint32_t capacity) {
    message(error,capacity,nil);
    if (!h || !extraBudget || (capacity && !error) || [NSThread isMainThread]) return MCMTL_E_INVALID_ARGUMENT;
    @autoreleasepool { @try {
        MCMTLFarConsumer *c=object(h);
        if (![c isKindOfClass:MCMTLFarConsumer.class]) return MCMTL_E_INVALID_ARGUMENT;
        const uint64_t required=(uint64_t)c->maxDraws*32u+2384u;
        if (extraBudget<required) return failure(error,capacity,@"Layer consumer extra GPU budget exhausted");
        if (!os_unfair_lock_trylock(&c->lock)) return MCMTL_FAR_CONSUMER_DECLINED;
        BOOL unavailable=c->poisoned || c->layersReady || c->lastSequence;
        for (unsigned i=0;i<ReceiptSlots;++i) unavailable|=c->busy[i];
        os_unfair_lock_unlock(&c->lock);
        if (unavailable) return MCMTL_FAR_CONSUMER_DECLINED;
        NSError *local=nil;
        MCMTLFarLayerResolve *resolver=[[MCMTLFarLayerResolve alloc] initWithDevice:c->device error:&local];
        if (!resolver) return failure(error,capacity,local.localizedDescription);
        const MTLResourceOptions privateOptions=MTLResourceStorageModePrivate|MTLResourceHazardTrackingModeTracked;
        const MTLResourceOptions sharedOptions=MTLResourceStorageModeShared|MTLResourceHazardTrackingModeTracked;
        id<MTLBuffer> fallback=[c->device newBufferWithLength:c->maxDraws*20u options:privateOptions];
        id<MTLBuffer> composite=[c->device newBufferWithLength:32 options:privateOptions];
        id<MTLBuffer> codes[ReceiptSlots],stats[ReceiptSlots],diagnostics[ReceiptSlots][2];
        if (!fallback || !composite) return MCMTL_E_OUT_OF_MEMORY;
        for (unsigned i=0;i<ReceiptSlots;++i) {
            codes[i]=[c->device newBufferWithLength:c->maxDraws*4u options:sharedOptions];
            stats[i]=[c->device newBufferWithLength:sizeof(MCMTLFarLayerResolveStats) options:sharedOptions];
            if (!codes[i].contents || !stats[i].contents) return MCMTL_E_OUT_OF_MEMORY;
            for (unsigned layer=0;layer<2;++layer) {
                diagnostics[i][layer]=[c->device newBufferWithLength:MCMTLFarShadeDiagnosticFullBytes options:sharedOptions];
                if (!diagnostics[i][layer].contents) return MCMTL_E_OUT_OF_MEMORY;
            }
        }
        // Publish the immutable preparation atomically, only while still unused.
        // A caller racing setup with an encode gets a decline, never replacement
        // of resources already referenced by an admitted command buffer.
        if (!os_unfair_lock_trylock(&c->lock)) return MCMTL_FAR_CONSUMER_DECLINED;
        unavailable=c->poisoned || c->layersReady || c->lastSequence;
        for (unsigned i=0;i<ReceiptSlots;++i) unavailable|=c->busy[i];
        if (!unavailable) {
            c->layerResolve=resolver; c->layerFallbackArgs=fallback; c->layerCompositeArgs=composite;
            for (unsigned i=0;i<ReceiptSlots;++i) {
                c->layerCodes[i]=codes[i]; c->layerGpuStats[i]=stats[i];
                for (unsigned layer=0;layer<2;++layer) c->shadeDiagnostics[i][layer]=diagnostics[i][layer];
            }
            c->layerReceipt=(mcmtl_far_consumer_layer_stats){.structSize=sizeof(c->layerReceipt)};
            c->layersReady=YES;
        }
        os_unfair_lock_unlock(&c->lock);
        return unavailable?MCMTL_FAR_CONSUMER_DECLINED:MCMTL_OK;
    } @catch (NSException *ex) { message(error,capacity,ex.reason); return MCMTL_E_OBJC_EXCEPTION; } }
}

MCMTL_API int32_t mcmtl_ext_far_consumer_layer_outputs(mcmtl_handle h,
    mcmtl_far_consumer_layer_outputs *out, char *error, uint32_t capacity) {
    message(error,capacity,nil);
    if (!out) return MCMTL_E_INVALID_ARGUMENT;
    uint32_t size=out->structSize;
    *out=(mcmtl_far_consumer_layer_outputs){.structSize=sizeof(*out)};
    if (!h || (capacity && !error)) return MCMTL_E_INVALID_ARGUMENT;
    if (size!=sizeof(*out)) return MCMTL_E_ABI_MISMATCH;
    @autoreleasepool { @try {
        MCMTLFarConsumer *c=object(h);
        if (![c isKindOfClass:MCMTLFarConsumer.class]) return MCMTL_E_INVALID_ARGUMENT;
        if (!os_unfair_lock_trylock(&c->lock)) return MCMTL_FAR_CONSUMER_DECLINED;
        BOOL unavailable=c->poisoned || !c->layersReady;
        os_unfair_lock_unlock(&c->lock);
        if (unavailable) return MCMTL_FAR_CONSUMER_DECLINED;
        mcmtl_far_consumer_layer_outputs result={.structSize=sizeof(result),.width=c->width,
            .height=c->height,.maxDraws=c->maxDraws};
        for (unsigned i=0;i<2;++i) { result.color[i]=retainHandle(c->color[4+i]); result.depth[i]=retainHandle(c->depth[4+i]); }
        result.fallbackArgs=retainHandle(c->layerFallbackArgs); result.compositeArgs=retainHandle(c->layerCompositeArgs);
        *out=result; return MCMTL_OK;
    } @catch (NSException *ex) { message(error,capacity,ex.reason); return MCMTL_E_OBJC_EXCEPTION; } }
}

MCMTL_API int32_t mcmtl_ext_far_consumer_layer_stats(mcmtl_handle h,
    mcmtl_far_consumer_layer_stats *out) {
    if (!out) return MCMTL_E_INVALID_ARGUMENT;
    uint32_t size=out->structSize;
    *out=(mcmtl_far_consumer_layer_stats){.structSize=sizeof(*out)};
    if (!h) return MCMTL_E_INVALID_ARGUMENT;
    if (size!=sizeof(*out)) return MCMTL_E_ABI_MISMATCH;
    @autoreleasepool { @try {
        MCMTLFarConsumer *c=object(h);
        if (![c isKindOfClass:MCMTLFarConsumer.class]) return MCMTL_E_INVALID_ARGUMENT;
        if (!os_unfair_lock_trylock(&c->lock)) return MCMTL_FAR_CONSUMER_DECLINED;
        BOOL enabled=c->layersReady;
        if (c->poisoned) out->flags=MCMTL_FAR_CONSUMER_POISONED;
        else if (enabled) *out=c->layerReceipt;
        os_unfair_lock_unlock(&c->lock);
        return enabled?MCMTL_OK:MCMTL_FAR_CONSUMER_DECLINED;
    } @catch (NSException *ex) { (void)ex; return MCMTL_E_OBJC_EXCEPTION; } }
}

MCMTL_API int32_t mcmtl_ext_far_consumer_layer_diagnostics(mcmtl_handle h,
    uint64_t sequence, uint32_t *outCounts, uint32_t capacityBytes) {
    if (!outCounts || capacityBytes<128) return MCMTL_E_INVALID_ARGUMENT;
    memset(outCounts,0,128);
    if (!h || !sequence) return MCMTL_E_INVALID_ARGUMENT;
    @autoreleasepool { @try {
        MCMTLFarConsumer *c=object(h);
        if (![c isKindOfClass:MCMTLFarConsumer.class]) return MCMTL_E_INVALID_ARGUMENT;
        if (!os_unfair_lock_trylock(&c->lock)) return MCMTL_FAR_CONSUMER_DECLINED;
        BOOL valid=!c->poisoned && c->layersReady && c->layerDiagnosticSequence==sequence &&
            c->layerReceipt.encodedSequence==sequence && (c->layerReceipt.flags&MCMTL_FAR_CONSUMER_RECEIPT_VALID);
        if (valid) memcpy(outCounts,c->layerDiagnosticReceipt,128);
        os_unfair_lock_unlock(&c->lock);
        return valid?MCMTL_OK:MCMTL_FAR_CONSUMER_DECLINED;
    } @catch (NSException *ex) { (void)ex; return MCMTL_E_OBJC_EXCEPTION; } }
}

static BOOL validFallback(MCMTLFarConsumer *c, mcmtl_far_consumer_fallback d) {
    id<MTLBuffer> b=object(d.buffer);
    if (!b || ![b conformsToProtocol:@protocol(MTLBuffer)] || b.device!=c->device ||
        (d.offset&3u) || d.offset>b.length || b.length-d.offset<20 ||
        b.hazardTrackingMode!=MTLHazardTrackingModeTracked || b.heap || b.isAliasable ||
        b.remoteStorageBuffer || b.sparseBufferTier!=MTLBufferSparseTierNone ||
        (b.storageMode!=MTLStorageModePrivate && b.storageMode!=MTLStorageModeShared && b.storageMode!=MTLStorageModeManaged) ||
        b==c->packedSource || b==c->fallbackArgs || b==c->compositeArgs ||
        b==c->layerFallbackArgs || b==c->layerCompositeArgs) return NO;
    for (unsigned i=0; i<ReceiptSlots; ++i)
        if (b==c->gpuStats[i] || b==c->alphaResult[i] || b==c->layerCodes[i] || b==c->layerGpuStats[i] ||
            b==c->shadeDiagnostics[i][0] || b==c->shadeDiagnostics[i][1]) return NO;
    return YES;
}

MCMTL_API int32_t mcmtl_ext_far_consumer_encode(mcmtl_handle h, mcmtl_handle leaseHandle,
    const void *transformsPointer, uint32_t imageCount, const mcmtl_far_consumer_fallback *descriptors,
    uint32_t drawCount, uint64_t sequence, char *error, uint32_t capacity) {
    message(error,capacity,nil);
    if (!h || !leaseHandle || !transformsPointer || imageCount<1 || imageCount>2 ||
        (drawCount && !descriptors) || !sequence || (capacity && !error) || ![NSThread isMainThread])
        return MCMTL_E_INVALID_ARGUMENT;
    @autoreleasepool {
        MCMTLFarConsumer *c=nil;
        id<MTLCommandBuffer> cb=nil;
        id<MTLBlitCommandEncoder> blit=nil;
        int slot=-1;
        BOOL committed=NO, commitAttempted=NO;
        uint64_t attributedValue=0;
        @try {
            c=object(h);
            MCMTLFarImageLease *lease=object(leaseHandle);
            if (![c isKindOfClass:MCMTLFarConsumer.class] || ![lease isKindOfClass:MCMTLFarImageLease.class] ||
                drawCount>c->maxDraws || imageCount>lease.count) return MCMTL_E_INVALID_ARGUMENT;
            if (__atomic_load_n(&c->mainDevice->status->deviceLost,__ATOMIC_ACQUIRE)) {
                poison(c); return MCMTL_FAR_CONSUMER_DECLINED;
            }
            MCMTLFarWarpTransform transforms[2];
            memcpy(transforms,transformsPointer,imageCount*sizeof(*transforms));
            for (unsigned i=0; i<imageCount; ++i) {
                if (transforms[i].motion==MCMTLFarWarpMotionTranslation) return MCMTL_FAR_CONSUMER_DECLINED;
                for (unsigned j=0; j<3; ++j)
                    if (isfinite(transforms[i].translation[j]) && transforms[i].translation[j]!=0)
                        return MCMTL_FAR_CONSUMER_DECLINED;
            }
            // Immutable stack metadata only. Warp rechecks every transform and
            // texture contract, including finite/inverse/optical-centre checks.
            mcmtl_far_engine_image images[2]={{.structSize=sizeof(*images)},{.structSize=sizeof(*images)}};
            for (unsigned i=0; i<imageCount; ++i) {
                if (mcmtl_ext_far_engine_image(leaseHandle,i,&images[i])!=MCMTL_OK || !images[i].sequence ||
                    !images[i].source.epoch || !images[i].width || !images[i].height)
                    return failure(error,capacity,@"Lease does not contain a complete engine image");
            }
            if (imageCount==2 && (images[0].sequence==images[1].sequence ||
                images[0].source.epoch!=images[1].source.epoch ||
                images[0].source.geometryGeneration!=images[1].source.geometryGeneration))
                return failure(error,capacity,@"Older image does not match the newest geometry/epoch");
            // materialGeneration identifies a capture/copy ticket, not material
            // content. The caller separately proves BOTH images match current
            // revisions, shaders, fog/settings and scene before selecting two.
            for (unsigned i=0; i<drawCount; ++i) if (!validFallback(c,descriptors[i]))
                return failure(error,capacity,@"Invalid current main-queue fallback argument buffer/range");
            if (!os_unfair_lock_trylock(&c->lock)) return MCMTL_FAR_CONSUMER_DECLINED;
            if (c->poisoned) { os_unfair_lock_unlock(&c->lock); return MCMTL_FAR_CONSUMER_DECLINED; }
            if (sequence<=c->lastSequence) { os_unfair_lock_unlock(&c->lock); return MCMTL_E_INVALID_ARGUMENT; }
            for (unsigned i=0; i<ReceiptSlots; ++i) if (!c->busy[i]) { slot=(int)i; c->busy[i]=YES; break; }
            os_unfair_lock_unlock(&c->lock);
            if (slot<0) return MCMTL_FAR_CONSUMER_DECLINED;
            cb=[c->queue commandBuffer];
            if (!cb) return MCMTL_E_OUT_OF_MEMORY;
            cb.label=@"mcmetal far consumer main-queue warp/resolve";
            if (![lease attachToCommandBuffer:cb]) return MCMTL_FAR_CONSUMER_DECLINED;
            NSError *local=nil;
            for (unsigned i=0; i<imageCount; ++i) for (unsigned layer=0; layer<2; ++layer) {
                MCMTLFarWarpSource source={object(images[i].color[layer]),object(images[i].depth[layer]),object(images[i].visibilityID[layer])};
                if (![c->warp encodeToCommandBuffer:cb source:source targets:targets(c,2*i+layer)
                    transform:&transforms[i] error:&local]) return failure(error,capacity,local.localizedDescription);
            }
            blit=[cb blitCommandEncoder];
            if (!blit) return MCMTL_E_OUT_OF_MEMORY;
            // Clear unused records too: exporting maxDraws never exposes a prior
            // frame's indirect records. All source copies execute after current cull.
            [blit fillBuffer:c->packedSource range:NSMakeRange(0,c->packedSource.length) value:0];
            [blit fillBuffer:c->fallbackArgs range:NSMakeRange(0,c->fallbackArgs.length) value:0];
            for (unsigned i=0; i<drawCount; ++i) [blit copyFromBuffer:object(descriptors[i].buffer)
                sourceOffset:descriptors[i].offset toBuffer:c->packedSource destinationOffset:i*20u size:20];
            [blit endEncoding]; blit=nil;
            MCMTLFarResolvePair older=pair(c,2);
            MCMTLFarResolveArguments args={.source=c->packedSource,.fallback=c->fallbackArgs,.count=drawCount,
                .composite=c->compositeArgs,.stats=c->gpuStats[slot]};
            if (![c->resolve encodeToCommandBuffer:cb newest:pair(c,0) older:imageCount==2 ? &older : NULL
                olderEligibility:(MCMTLFarResolveOlderEligibility){YES,YES,YES} targets:pair(c,4)
                holeMask:c->holeMask arguments:args error:&local]) return failure(error,capacity,local.localizedDescription);
            mcmtl_far_consumer_stats receipt={.structSize=sizeof(receipt),.flags=MCMTL_FAR_CONSUMER_RECEIPT_VALID,
                .encodedSequence=sequence,.imageCount=imageCount,.drawCount=drawCount};
            const uint64_t now=clock_gettime_nsec_np(CLOCK_UPTIME_RAW);
            for (unsigned i=0; i<imageCount; ++i) {
                receipt.sourceFrame[i]=images[i].source.sourceFrame;
                receipt.sourceAgeNs[i]=now>=images[i].source.sourceTimeNs ? now-images[i].source.sourceTimeNs : 0;
            }
            __block MCMTLFarConsumer *completionConsumer=c;
            __block MCMTLFarImageLease *completionLease=lease;
            // Attribute errors to the following main timeline submission while
            // leaving BOTH timeline values and the main event entirely alone.
            uint64_t submitted=mcmtl_status_load_u64(&c->mainDevice->status->submittedValue);
            attributedValue=submitted==UINT64_MAX ? submitted : submitted+1;
            mcmtl_track_untimed_command_buffer(c->mainDevice,cb,attributedValue);
            [cb addCompletedHandler:^(id<MTLCommandBuffer> done) { @autoreleasepool {
                MCMTLFarConsumer *owner=completionConsumer;
                __attribute__((objc_precise_lifetime)) MCMTLFarImageLease *heldLease=completionLease;
                completionConsumer=nil; completionLease=nil;
                (void)heldLease;
                // Slot remains busy through this copy, even if later command
                // buffers have already executed and their callbacks ran first.
                MCMTLFarResolveStats gpu={0};
                BOOL success=done.status==MTLCommandBufferStatusCompleted;
                if (success) memcpy(&gpu,owner->gpuStats[slot].contents,sizeof(gpu));
                if (gpu.holeCount>(uint64_t)owner->width*owner->height || gpu.fallback!=(gpu.holeCount!=0)) success=NO;
                os_unfair_lock_lock(&owner->lock);
                if (!success) owner->poisoned=YES;
                if (owner->poisoned) owner->receipt=(mcmtl_far_consumer_stats){.structSize=sizeof(receipt),.flags=MCMTL_FAR_CONSUMER_POISONED};
                else if (receipt.encodedSequence>owner->receipt.encodedSequence) {
                    mcmtl_far_consumer_stats completedReceipt=receipt;
                    completedReceipt.holePixels=gpu.holeCount; completedReceipt.syncFallback=gpu.fallback;
                    owner->receipt=completedReceipt;
                }
                owner->busy[slot]=NO;
                os_unfair_lock_unlock(&owner->lock);
            } }];
            // A prior in-flight GPU failure may have arrived during encoding.
            // Do not submit more work after observing its poison flag.
            if (!os_unfair_lock_trylock(&c->lock)) return MCMTL_FAR_CONSUMER_DECLINED;
            BOOL dead=c->poisoned;
            os_unfair_lock_unlock(&c->lock);
            if (dead) return MCMTL_FAR_CONSUMER_DECLINED;
            commitAttempted=YES;
            [cb commit];
            committed=YES;
            os_unfair_lock_lock(&c->lock);
            c->lastSequence=sequence;
            os_unfair_lock_unlock(&c->lock);
            return MCMTL_OK;
        } @catch (NSException *ex) {
            if (commitAttempted) {
                poison(c); // Commit outcome is unknowable; never reuse stale resources.
                mcmtl_status_page *status=c->mainDevice->status;
                __atomic_store_n(&status->lastErrorCode,(int32_t)MTLCommandBufferErrorInternal,__ATOMIC_RELEASE);
                mcmtl_status_store_u64(&status->lastErrorValue,attributedValue);
                __atomic_fetch_add(&status->errorCount,1u,__ATOMIC_RELEASE);
                __atomic_store_n(&status->deviceLost,1u,__ATOMIC_RELEASE);
                mcmtl_device_message(c->mainDevice,"Far consumer commit outcome unknown: %s",ex.reason.UTF8String ?: "exception");
            }
            message(error,capacity,ex.reason);
            return MCMTL_E_OBJC_EXCEPTION;
        } @finally {
            if (blit) { @try { [blit endEncoding]; } @catch (NSException *ignored) { (void)ignored; } }
            // Retained-reference unqueued buffers release both attachment and
            // completion captures when discarded. No targets become authorized.
            cb=nil;
            if (!committed && slot>=0) {
                os_unfair_lock_lock(&c->lock); c->busy[slot]=NO; os_unfair_lock_unlock(&c->lock);
            }
        }
    }
}

/* No epsilon snapping and no reliance on flags alone. An exact same-camera
 * guard crop must have symmetric EVEN-PIXEL padding and these exact two matrices;
 * arbitrary integer shifts, rotations and fractional footprint changes decline. */
static BOOL centeredCrop(MCMTLFarConsumer *c, const mcmtl_far_engine_image *image,
                         const MCMTLFarWarpTransform *t) {
    if (t->motion!=MCMTLFarWarpMotionPureRotation ||
        image->width<c->width || image->height<c->height ||
        ((image->width-c->width)&3u) || ((image->height-c->height)&3u)) return NO;
    for (unsigned i=0;i<3;++i) if (t->translation[i]!=0) return NO; // Includes NaN.
    float a[16]={0}, b[16]={0};
    a[0]=(float)((double)c->width/image->width); a[5]=(float)((double)c->height/image->height);
    b[0]=(float)((double)image->width/c->width); b[5]=(float)((double)image->height/c->height);
    a[10]=a[15]=b[10]=b[15]=1;
    for (unsigned i=0;i<16;++i)
        if (t->currentToSourceClip[i]!=a[i] || t->sourceToCurrentClip[i]!=b[i]) return NO;
    return YES;
}

static BOOL consumerTexture(MCMTLFarConsumer *c,id<MTLTexture> texture) {
    if (texture==c->holeMask) return YES;
    for (unsigned i=0;i<TargetSets;++i)
        if (texture==c->color[i] || texture==c->depth[i] || texture==c->ids[i] || texture==c->validity[i]) return YES;
    return NO;
}

/* Reuse the material recipe's full texture/view validation, without making a
 * version or copying/allocating GPU resources. This bounded, two-entry CPU pin
 * retains the live parents/views/samplers while the command buffer is encoded. */
static MCMTLFarMaterialRecipe *shadedMaterials(MCMTLFarConsumer *c,
    MCMTLFarMaterialVersion *old, const mcmtl_far_consumer_shading *s,
    mcmtl_far_material_binding previous[2], NSError **error) {
    if (![old isKindOfClass:MCMTLFarMaterialVersion.class] || !old.published) return nil;
    MCMTLFarMaterialRecipe *current=[[MCMTLFarMaterialRecipe alloc] initWithDevice:c->device
        sources:s->current budgetBytes:UINT64_MAX error:error];
    if (!current) return nil;
    for (unsigned i=0;i<2;++i) {
        previous[i]=[old bindingAtIndex:i];
        mcmtl_far_material_desc desc=[current descriptorAtIndex:i];
        if (!previous[i].parent || memcmp(&desc,&previous[i].desc,sizeof desc) ||
            [current samplerAtIndex:i]!=object(previous[i].sampler) ||
            consumerTexture(c,[current sourceParentAtIndex:i]) || consumerTexture(c,[current sourceTextureAtIndex:i])) return nil;
    }
    return current;
}

MCMTL_API int32_t mcmtl_ext_far_consumer_encode_shaded(mcmtl_handle h,
    mcmtl_handle leaseHandle, const void *transformPointer,
    const mcmtl_far_consumer_fallback *descriptors, uint32_t drawCount,
    uint64_t sequence, const mcmtl_far_consumer_shading *shading,
    char *error, uint32_t capacity) {
    message(error,capacity,nil);
    if (!h || !leaseHandle || !transformPointer || !shading ||
        (drawCount && !descriptors) || !sequence || (capacity && !error) || ![NSThread isMainThread])
        return MCMTL_E_INVALID_ARGUMENT;
    if (shading->structSize!=sizeof(*shading)) return MCMTL_E_ABI_MISMATCH;
    if (shading->flags!=1 || !shading->committedSequence) return MCMTL_E_INVALID_ARGUMENT;
    @autoreleasepool {
        MCMTLFarConsumer *c=nil;
        id<MTLCommandBuffer> cb=nil;
        id<MTLBlitCommandEncoder> blit=nil;
        id<MTLComputeCommandEncoder> override=nil;
        int slot=-1;
        BOOL committed=NO,commitAttempted=NO;
        uint64_t attributedValue=0;
        @try {
            c=object(h);
            MCMTLFarImageLease *lease=object(leaseHandle);
            if (![c isKindOfClass:MCMTLFarConsumer.class] || ![lease isKindOfClass:MCMTLFarImageLease.class] ||
                drawCount>c->maxDraws || !lease.count) return MCMTL_E_INVALID_ARGUMENT;
            if (__atomic_load_n(&c->mainDevice->status->deviceLost,__ATOMIC_ACQUIRE)) {
                poison(c); return MCMTL_FAR_CONSUMER_DECLINED;
            }
            mcmtl_far_consumer_shading s; memcpy(&s,shading,sizeof s);
            const uint64_t submitted=mcmtl_status_load_u64(&c->mainDevice->status->submittedValue);
            if (s.committedSequence>submitted) return MCMTL_E_INVALID_ARGUMENT;
            MCMTLFarWarpTransform transform; memcpy(&transform,transformPointer,sizeof transform);
            mcmtl_far_engine_image image={.structSize=sizeof image};
            if (mcmtl_ext_far_engine_image(leaseHandle,0,&image)!=MCMTL_OK || !image.sequence ||
                !image.source.epoch || !image.width || !image.height)
                return failure(error,capacity,@"Lease does not contain a complete engine image");
            if (!centeredCrop(c,&image,&transform)) return MCMTL_FAR_CONSUMER_DECLINED;
            MCMTLFarEngineShading *program=mcmtl_far_engine_shading(leaseHandle,0);
            if (!program || !program.solid || !program.cutout || !program.geometry)
                return MCMTL_FAR_CONSUMER_DECLINED;
            if (program.solid.device!=c->device || program.cutout.device!=c->device ||
                program.solid.layer!=MCMTLFarShaderSolid || program.cutout.layer!=MCMTLFarShaderCutout)
                return failure(error,capacity,@"Lease shading tuple has mismatched device/layers");
            NSError *local=nil;
            mcmtl_far_material_binding previous[2]={0};
            MCMTLFarMaterialRecipe *current=shadedMaterials(c,object(image.materialVersion),&s,previous,&local);
            if (!current) return failure(error,capacity,local.localizedDescription ?: @"Current material view/sampler/clamp metadata differs from the image");
            for (unsigned i=0;i<drawCount;++i) if (!validFallback(c,descriptors[i]))
                return failure(error,capacity,@"Invalid current main-queue fallback argument buffer/range");
            if (!os_unfair_lock_trylock(&c->lock)) return MCMTL_FAR_CONSUMER_DECLINED;
            if (c->poisoned) { os_unfair_lock_unlock(&c->lock); return MCMTL_FAR_CONSUMER_DECLINED; }
            if (sequence<=c->lastSequence) { os_unfair_lock_unlock(&c->lock); return MCMTL_E_INVALID_ARGUMENT; }
            for (unsigned i=0;i<ReceiptSlots;++i) if (!c->busy[i]) { slot=(int)i; c->busy[i]=YES; break; }
            os_unfair_lock_unlock(&c->lock);
            if (slot<0) return MCMTL_FAR_CONSUMER_DECLINED;
            cb=[c->queue commandBuffer];
            if (!cb) return MCMTL_E_OUT_OF_MEMORY;
            cb.label=@"mcmetal far consumer current-material shade";
            if (![lease attachToCommandBuffer:cb]) return MCMTL_FAR_CONSUMER_DECLINED;
            if (![c->alpha encodeToCommandBuffer:cb oldAtlas:object(previous[0].parent)
                currentAtlas:[current sourceParentAtIndex:0] oldLight:object(previous[1].parent)
                currentLight:[current sourceParentAtIndex:1] result:c->alphaResult[slot] error:&local])
                return failure(error,capacity,local.localizedDescription);
            for (unsigned layer=0;layer<2;++layer) {
                MCMTLFarWarpSource source={object(image.color[layer]),object(image.depth[layer]),object(image.visibilityID[layer])};
                if (![c->warp encodeToCommandBuffer:cb source:source targets:targets(c,layer)
                    transform:&transform error:&local]) return failure(error,capacity,local.localizedDescription);
            }
            blit=[cb blitCommandEncoder];
            if (!blit) return MCMTL_E_OUT_OF_MEMORY;
            [blit fillBuffer:c->packedSource range:NSMakeRange(0,c->packedSource.length) value:0];
            [blit fillBuffer:c->fallbackArgs range:NSMakeRange(0,c->fallbackArgs.length) value:0];
            for (unsigned i=0;i<drawCount;++i) [blit copyFromBuffer:object(descriptors[i].buffer)
                sourceOffset:descriptors[i].offset toBuffer:c->packedSource destinationOffset:i*20u size:20];
            for (unsigned layer=0;layer<2;++layer) [blit copyFromTexture:c->ids[layer]
                sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,0,0)
                sourceSize:MTLSizeMake(c->width,c->height,1) toTexture:c->ids[layer+2]
                destinationSlice:0 destinationLevel:0 destinationOrigin:MTLOriginMake(0,0,0)];
            [blit endEncoding]; blit=nil;
            MCMTLFarShadeUniforms uniforms; memcpy(&uniforms,s.uniforms,sizeof uniforms);
            MCMTLFarShadeMaterials materials={
                .atlas=[current sourceTextureAtIndex:0],.lightmap=[current sourceTextureAtIndex:1],
                .atlasSampler=[current samplerAtIndex:0],.lightSampler=[current samplerAtIndex:1],
                .atlasLODClamp=s.current[0].samplerLODClamp!=0,.lightLODClamp=s.current[1].samplerLODClamp!=0,
                .atlasLODMin=s.current[0].lodMin,.atlasLODMax=s.current[0].lodMax,
                .lightLODMin=s.current[1].lodMin,.lightLODMax=s.current[1].lodMax};
            for (unsigned layer=0;layer<2;++layer) {
                MCMTLFarShade *shader=layer ? program.cutout : program.solid;
                MCMTLFarShadeSource source={c->ids[layer],c->validity[layer],c->depth[layer],YES};
                MCMTLFarShadeTargets target={c->color[layer+2],c->depth[layer+2],c->validity[layer+2]};
                if (![shader encodeToCommandBuffer:cb geometry:program.geometry source:source targets:target
                    uniforms:&uniforms materials:materials error:&local]) return failure(error,capacity,local.localizedDescription);
            }
            MCMTLFarResolveArguments args={.source=c->packedSource,.fallback=c->fallbackArgs,.count=drawCount,
                .composite=c->compositeArgs,.stats=c->gpuStats[slot]};
            if (![c->resolve encodeToCommandBuffer:cb newest:pair(c,2) older:NULL
                olderEligibility:(MCMTLFarResolveOlderEligibility){NO,NO,NO} targets:pair(c,4)
                holeMask:c->holeMask arguments:args error:&local]) return failure(error,capacity,local.localizedDescription);
            override=[cb computeCommandEncoder];
            if (!override) return MCMTL_E_OUT_OF_MEMORY;
            override.label=@"mcmetal far alpha conditional synchronous fallback";
            [override setComputePipelineState:c->shadedFallback];
            [override setBuffer:c->alphaResult[slot] offset:0 atIndex:0];
            [override setBuffer:c->packedSource offset:0 atIndex:1];
            [override setBuffer:c->fallbackArgs offset:0 atIndex:2];
            [override setBuffer:c->compositeArgs offset:0 atIndex:3];
            [override setBuffer:c->gpuStats[slot] offset:0 atIndex:4];
            [override setBytes:&drawCount length:sizeof drawCount atIndex:5];
            [override dispatchThreads:MTLSizeMake(MAX(4u,drawCount*5u),1,1)
                threadsPerThreadgroup:MTLSizeMake(MIN((NSUInteger)64,c->shadedFallback.maxTotalThreadsPerThreadgroup),1,1)];
            [override endEncoding]; override=nil;
            mcmtl_far_consumer_stats receipt={.structSize=sizeof receipt,.flags=MCMTL_FAR_CONSUMER_RECEIPT_VALID,
                .encodedSequence=sequence,.imageCount=1,.drawCount=drawCount};
            const uint64_t now=clock_gettime_nsec_np(CLOCK_UPTIME_RAW);
            receipt.sourceFrame[0]=image.source.sourceFrame;
            receipt.sourceAgeNs[0]=now>=image.source.sourceTimeNs ? now-image.source.sourceTimeNs : 0;
            __block MCMTLFarConsumer *completionConsumer=c;
            __block MCMTLFarImageLease *completionLease=lease;
            // Same error attribution as original encode; no timeline signaling.
            attributedValue=submitted==UINT64_MAX ? submitted : submitted+1;
            mcmtl_track_untimed_command_buffer(c->mainDevice,cb,attributedValue);
            [cb addCompletedHandler:^(id<MTLCommandBuffer> done) { @autoreleasepool {
                MCMTLFarConsumer *owner=completionConsumer;
                __attribute__((objc_precise_lifetime)) MCMTLFarImageLease *heldLease=completionLease;
                completionConsumer=nil; completionLease=nil; (void)heldLease;
                MCMTLFarResolveStats gpu={0}; uint32_t alphaRejected=0;
                BOOL success=done.status==MTLCommandBufferStatusCompleted;
                if (success) {
                    memcpy(&gpu,owner->gpuStats[slot].contents,sizeof gpu);
                    memcpy(&alphaRejected,owner->alphaResult[slot].contents,sizeof alphaRejected);
                }
                if (gpu.holeCount>(uint64_t)owner->width*owner->height || alphaRejected>1 ||
                    gpu.fallback!=(gpu.holeCount!=0 || alphaRejected!=0)) success=NO;
                os_unfair_lock_lock(&owner->lock);
                if (!success) owner->poisoned=YES;
                if (owner->poisoned) owner->receipt=(mcmtl_far_consumer_stats){.structSize=sizeof receipt,.flags=MCMTL_FAR_CONSUMER_POISONED};
                else if (receipt.encodedSequence>owner->receipt.encodedSequence) {
                    mcmtl_far_consumer_stats completedReceipt=receipt;
                    completedReceipt.holePixels=gpu.holeCount; completedReceipt.syncFallback=gpu.fallback;
                    completedReceipt.reserved64=alphaRejected; owner->receipt=completedReceipt;
                }
                owner->busy[slot]=NO;
                os_unfair_lock_unlock(&owner->lock);
            } }];
            if (!os_unfair_lock_trylock(&c->lock)) return MCMTL_FAR_CONSUMER_DECLINED;
            BOOL dead=c->poisoned;
            os_unfair_lock_unlock(&c->lock);
            if (dead) return MCMTL_FAR_CONSUMER_DECLINED;
            commitAttempted=YES; [cb commit]; committed=YES;
            os_unfair_lock_lock(&c->lock); c->lastSequence=sequence; os_unfair_lock_unlock(&c->lock);
            return MCMTL_OK;
        } @catch (NSException *ex) {
            if (commitAttempted) {
                poison(c);
                mcmtl_status_page *status=c->mainDevice->status;
                __atomic_store_n(&status->lastErrorCode,(int32_t)MTLCommandBufferErrorInternal,__ATOMIC_RELEASE);
                mcmtl_status_store_u64(&status->lastErrorValue,attributedValue);
                __atomic_fetch_add(&status->errorCount,1u,__ATOMIC_RELEASE);
                __atomic_store_n(&status->deviceLost,1u,__ATOMIC_RELEASE);
                mcmtl_device_message(c->mainDevice,"Shaded far consumer commit outcome unknown: %s",ex.reason.UTF8String ?: "exception");
            }
            message(error,capacity,ex.reason); return MCMTL_E_OBJC_EXCEPTION;
        } @finally {
            if (blit) { @try { [blit endEncoding]; } @catch (NSException *ignored) { (void)ignored; } }
            if (override) { @try { [override endEncoding]; } @catch (NSException *ignored) { (void)ignored; } }
            cb=nil;
            if (!committed && slot>=0) {
                os_unfair_lock_lock(&c->lock); c->busy[slot]=NO; os_unfair_lock_unlock(&c->lock);
            }
        }
    }
}

MCMTL_API int32_t mcmtl_ext_far_consumer_encode_shaded_layers(mcmtl_handle h,
    mcmtl_handle leaseHandle, const void *transformPointer,
    const mcmtl_far_consumer_fallback *descriptors, const uint32_t *codes, uint32_t drawCount,
    uint64_t sequence, const mcmtl_far_consumer_shading *shading,
    char *error, uint32_t capacity) {
    message(error,capacity,nil);
    if (!h || !leaseHandle || !transformPointer || !shading ||
        (drawCount && (!descriptors || !codes)) || !sequence || (capacity && !error) || ![NSThread isMainThread])
        return MCMTL_E_INVALID_ARGUMENT;
    if (shading->structSize!=sizeof(*shading)) return MCMTL_E_ABI_MISMATCH;
    if ((shading->flags!=1 && shading->flags!=3) || !shading->committedSequence) return MCMTL_E_INVALID_ARGUMENT;
    @autoreleasepool {
        MCMTLFarConsumer *c=nil;
        id<MTLCommandBuffer> cb=nil;
        id<MTLBlitCommandEncoder> blit=nil;
        int slot=-1;
        BOOL committed=NO,commitAttempted=NO;
        uint64_t attributedValue=0;
        @try {
            c=object(h);
            MCMTLFarImageLease *lease=object(leaseHandle);
            if (![c isKindOfClass:MCMTLFarConsumer.class] || ![lease isKindOfClass:MCMTLFarImageLease.class] ||
                drawCount>c->maxDraws || !lease.count) return MCMTL_E_INVALID_ARGUMENT;
            if (__atomic_load_n(&c->mainDevice->status->deviceLost,__ATOMIC_ACQUIRE)) {
                poison(c); return MCMTL_FAR_CONSUMER_DECLINED;
            }
            if (!os_unfair_lock_trylock(&c->lock)) return MCMTL_FAR_CONSUMER_DECLINED;
            BOOL ready=c->layersReady;
            os_unfair_lock_unlock(&c->lock);
            if (!ready) return MCMTL_FAR_CONSUMER_DECLINED;
            uint32_t counts[2]={0};
            for (unsigned i=0;i<drawCount;++i) {
                uint32_t code=codes[i];
                if (!(code<=2 || (code>=8 && code<=10))) return MCMTL_E_INVALID_ARGUMENT;
                if ((code&7u)<2) ++counts[code&1u];
            }
            mcmtl_far_consumer_shading s; memcpy(&s,shading,sizeof s);
            const BOOL diagnostics=(s.flags&2u)!=0;
            const uint64_t submitted=mcmtl_status_load_u64(&c->mainDevice->status->submittedValue);
            if (s.committedSequence>submitted) return MCMTL_E_INVALID_ARGUMENT;
            MCMTLFarWarpTransform transform; memcpy(&transform,transformPointer,sizeof transform);
            mcmtl_far_engine_image image={.structSize=sizeof image};
            if (mcmtl_ext_far_engine_image(leaseHandle,0,&image)!=MCMTL_OK || !image.sequence ||
                !image.source.epoch || !image.width || !image.height)
                return failure(error,capacity,@"Lease does not contain a complete engine image");
            if (!centeredCrop(c,&image,&transform)) return MCMTL_FAR_CONSUMER_DECLINED;
            MCMTLFarEngineShading *program=mcmtl_far_engine_shading(leaseHandle,0);
            if (!program || !program.solid || !program.cutout || !program.geometry)
                return MCMTL_FAR_CONSUMER_DECLINED;
            if (program.solid.device!=c->device || program.cutout.device!=c->device ||
                program.solid.layer!=MCMTLFarShaderSolid || program.cutout.layer!=MCMTLFarShaderCutout)
                return failure(error,capacity,@"Lease shading tuple has mismatched device/layers");
            NSError *local=nil;
            mcmtl_far_material_binding previous[2]={0};
            MCMTLFarMaterialRecipe *current=shadedMaterials(c,object(image.materialVersion),&s,previous,&local);
            if (!current) return failure(error,capacity,local.localizedDescription ?: @"Current material view/sampler/clamp metadata differs from the image");
            for (unsigned i=0;i<drawCount;++i) if (!validFallback(c,descriptors[i]))
                return failure(error,capacity,@"Invalid current main-queue fallback argument buffer/range");
            if (!os_unfair_lock_trylock(&c->lock)) return MCMTL_FAR_CONSUMER_DECLINED;
            if (c->poisoned) { os_unfair_lock_unlock(&c->lock); return MCMTL_FAR_CONSUMER_DECLINED; }
            if (sequence<=c->lastSequence) { os_unfair_lock_unlock(&c->lock); return MCMTL_E_INVALID_ARGUMENT; }
            for (unsigned i=0;i<ReceiptSlots;++i) if (!c->busy[i]) { slot=(int)i; c->busy[i]=YES; break; }
            os_unfair_lock_unlock(&c->lock);
            if (slot<0) return MCMTL_FAR_CONSUMER_DECLINED;
            if (drawCount) memcpy(c->layerCodes[slot].contents,codes,drawCount*4u);
            cb=[c->queue commandBuffer];
            if (!cb) return MCMTL_E_OUT_OF_MEMORY;
            cb.label=@"mcmetal far consumer independent layer shade";
            if (![lease attachToCommandBuffer:cb]) return MCMTL_FAR_CONSUMER_DECLINED;
            if (![c->alpha encodeToCommandBuffer:cb oldAtlas:object(previous[0].parent)
                currentAtlas:[current sourceParentAtIndex:0] oldLight:object(previous[1].parent)
                currentLight:[current sourceParentAtIndex:1] result:c->alphaResult[slot] error:&local])
                return failure(error,capacity,local.localizedDescription);
            for (unsigned layer=0;layer<2;++layer) {
                MCMTLFarWarpSource source={object(image.color[layer]),object(image.depth[layer]),object(image.visibilityID[layer])};
                if (![c->warp encodeToCommandBuffer:cb source:source targets:targets(c,layer)
                    transform:&transform error:&local]) return failure(error,capacity,local.localizedDescription);
            }
            blit=[cb blitCommandEncoder];
            if (!blit) return MCMTL_E_OUT_OF_MEMORY;
            [blit fillBuffer:c->packedSource range:NSMakeRange(0,c->packedSource.length) value:0];
            [blit fillBuffer:c->layerFallbackArgs range:NSMakeRange(0,c->layerFallbackArgs.length) value:0];
            if (diagnostics) for (unsigned layer=0;layer<2;++layer)
                [blit fillBuffer:c->shadeDiagnostics[slot][layer] range:NSMakeRange(0,MCMTLFarShadeDiagnosticFullBytes) value:0];
            for (unsigned i=0;i<drawCount;++i) [blit copyFromBuffer:object(descriptors[i].buffer)
                sourceOffset:descriptors[i].offset toBuffer:c->packedSource destinationOffset:i*20u size:20];
            for (unsigned layer=0;layer<2;++layer) [blit copyFromTexture:c->ids[layer]
                sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,0,0)
                sourceSize:MTLSizeMake(c->width,c->height,1) toTexture:c->ids[layer+2]
                destinationSlice:0 destinationLevel:0 destinationOrigin:MTLOriginMake(0,0,0)];
            [blit endEncoding]; blit=nil;
            MCMTLFarShadeUniforms uniforms; memcpy(&uniforms,s.uniforms,sizeof uniforms);
            MCMTLFarShadeMaterials materials={
                .atlas=[current sourceTextureAtIndex:0],.lightmap=[current sourceTextureAtIndex:1],
                .atlasSampler=[current samplerAtIndex:0],.lightSampler=[current samplerAtIndex:1],
                .atlasLODClamp=s.current[0].samplerLODClamp!=0,.lightLODClamp=s.current[1].samplerLODClamp!=0,
                .atlasLODMin=s.current[0].lodMin,.atlasLODMax=s.current[0].lodMax,
                .lightLODMin=s.current[1].lodMin,.lightLODMax=s.current[1].lodMax};
            for (unsigned layer=0;layer<2;++layer) {
                MCMTLFarShade *shader=layer ? program.cutout : program.solid;
                MCMTLFarShadeSource source={c->ids[layer],c->validity[layer],c->depth[layer],YES};
                MCMTLFarShadeTargets target={c->color[layer+2],c->depth[layer+2],c->validity[layer+2]};
                if (![shader encodeToCommandBuffer:cb geometry:program.geometry source:source targets:target
                    uniforms:&uniforms materials:materials diagnostics:diagnostics?c->shadeDiagnostics[slot][layer]:nil error:&local]) return failure(error,capacity,local.localizedDescription);
            }
            if (![c->layerResolve encodeToCommandBuffer:cb newest:pair(c,2) targets:pair(c,4)
                sourceArgs:c->packedSource fallbackArgs:c->layerFallbackArgs compositeArgs:c->layerCompositeArgs
                layerCodes:c->layerCodes[slot] alpha:c->alphaResult[slot] stats:c->layerGpuStats[slot]
                count:drawCount error:&local]) return failure(error,capacity,local.localizedDescription);
            mcmtl_far_consumer_layer_stats receipt={.structSize=sizeof receipt,.flags=MCMTL_FAR_CONSUMER_RECEIPT_VALID,
                .encodedSequence=sequence,.sourceFrame=image.source.sourceFrame,.drawCount={counts[0],counts[1]}};
            const uint64_t now=clock_gettime_nsec_np(CLOCK_UPTIME_RAW);
            receipt.sourceAgeNs=now>=image.source.sourceTimeNs ? now-image.source.sourceTimeNs : 0;
            __block MCMTLFarConsumer *completionConsumer=c;
            __block MCMTLFarImageLease *completionLease=lease;
            // Same error attribution as original encode; no timeline signaling.
            attributedValue=submitted==UINT64_MAX ? submitted : submitted+1;
            mcmtl_track_untimed_command_buffer(c->mainDevice,cb,attributedValue);
            [cb addCompletedHandler:^(id<MTLCommandBuffer> done) { @autoreleasepool {
                MCMTLFarConsumer *owner=completionConsumer;
                __attribute__((objc_precise_lifetime)) MCMTLFarImageLease *heldLease=completionLease;
                completionConsumer=nil; completionLease=nil; (void)heldLease;
                MCMTLFarLayerResolveStats gpu={0}; uint32_t alphaRejected=0,counters[2][16]={{0}},samples[2][MCMTLFarShadeDiagnosticSampleWords]={{0}};
                BOOL success=done.status==MTLCommandBufferStatusCompleted;
                if (success) {
                    memcpy(&gpu,owner->layerGpuStats[slot].contents,sizeof gpu);
                    memcpy(&alphaRejected,owner->alphaResult[slot].contents,sizeof alphaRejected);
                    if (diagnostics) for (unsigned layer=0;layer<2;++layer) {
                        const uint8_t *bytes=owner->shadeDiagnostics[slot][layer].contents;
                        memcpy(counters[layer],bytes,sizeof counters[layer]);
                        memcpy(samples[layer],bytes+MCMTLFarShadeDiagnosticSampleOffsetBytes,sizeof samples[layer]);
                    }
                }
                const uint64_t pixels=(uint64_t)owner->width*owner->height;
                if (alphaRejected>1) success=NO;
                for (unsigned layer=0;layer<2;++layer) {
                    if (gpu.holes[layer]>pixels ||
                        gpu.fallback[layer]!=(gpu.holes[layer]!=0 || (layer==1 && alphaRejected!=0))) success=NO;
                    if (diagnostics) {
                        uint64_t total=0;
                        for (unsigned bin=0;bin<16;++bin) total+=counters[layer][bin];
                        if (total!=pixels || (uint64_t)counters[layer][0]+counters[layer][1]+gpu.holes[layer]!=pixels) success=NO;
                    }
                }
                BOOL reportSample=NO;
                os_unfair_lock_lock(&owner->lock);
                if (!success) owner->poisoned=YES;
                if (!owner->poisoned && diagnostics && !owner->layerDiagnosticSampleLogged &&
                    counters[0][MCMTLFarShadeDiagnosticDegenerate] && validSolidSample(samples[0],owner->width,owner->height)) {
                    owner->layerDiagnosticSampleLogged=YES;reportSample=YES;
                }
                if (owner->poisoned) {
                    owner->receipt=(mcmtl_far_consumer_stats){.structSize=sizeof(owner->receipt),.flags=MCMTL_FAR_CONSUMER_POISONED};
                    owner->layerReceipt=(mcmtl_far_consumer_layer_stats){.structSize=sizeof(receipt),.flags=MCMTL_FAR_CONSUMER_POISONED};
                    owner->layerDiagnosticSequence=0;
                } else if (receipt.encodedSequence>owner->layerReceipt.encodedSequence) {
                    mcmtl_far_consumer_layer_stats completedReceipt=receipt;
                    for (unsigned layer=0;layer<2;++layer) {
                        completedReceipt.holePixels[layer]=gpu.holes[layer];
                        completedReceipt.syncFallback[layer]=gpu.fallback[layer];
                        completedReceipt.alphaRejected[layer]=layer==1 ? alphaRejected : 0;
                    }
                    owner->layerReceipt=completedReceipt;
                    owner->layerDiagnosticSequence=diagnostics?receipt.encodedSequence:0;
                    memcpy(owner->layerDiagnosticReceipt,counters,sizeof counters);
                }
                owner->busy[slot]=NO;
                os_unfair_lock_unlock(&owner->lock);
                if (reportSample) logSolidSample(receipt.encodedSequence,receipt.sourceFrame,samples[0]);
            } }];
            if (!os_unfair_lock_trylock(&c->lock)) return MCMTL_FAR_CONSUMER_DECLINED;
            BOOL dead=c->poisoned;
            os_unfair_lock_unlock(&c->lock);
            if (dead) return MCMTL_FAR_CONSUMER_DECLINED;
            commitAttempted=YES; [cb commit]; committed=YES;
            os_unfair_lock_lock(&c->lock); c->lastSequence=sequence; os_unfair_lock_unlock(&c->lock);
            return MCMTL_OK;
        } @catch (NSException *ex) {
            if (commitAttempted) {
                poison(c);
                mcmtl_status_page *status=c->mainDevice->status;
                __atomic_store_n(&status->lastErrorCode,(int32_t)MTLCommandBufferErrorInternal,__ATOMIC_RELEASE);
                mcmtl_status_store_u64(&status->lastErrorValue,attributedValue);
                __atomic_fetch_add(&status->errorCount,1u,__ATOMIC_RELEASE);
                __atomic_store_n(&status->deviceLost,1u,__ATOMIC_RELEASE);
                mcmtl_device_message(c->mainDevice,"Shaded far consumer commit outcome unknown: %s",ex.reason.UTF8String ?: "exception");
            }
            message(error,capacity,ex.reason); return MCMTL_E_OBJC_EXCEPTION;
        } @finally {
            if (blit) { @try { [blit endEncoding]; } @catch (NSException *ignored) { (void)ignored; } }
            cb=nil;
            if (!committed && slot>=0) {
                os_unfair_lock_lock(&c->lock); c->busy[slot]=NO; os_unfair_lock_unlock(&c->lock);
            }
        }
    }
}

MCMTL_API int32_t mcmtl_ext_far_consumer_stats(mcmtl_handle h, mcmtl_far_consumer_stats *out) {
    if (!out) return MCMTL_E_INVALID_ARGUMENT;
    uint32_t size=out->structSize;
    *out=(mcmtl_far_consumer_stats){.structSize=sizeof(*out)};
    if (!h) return MCMTL_E_INVALID_ARGUMENT;
    if (size!=sizeof(*out)) return MCMTL_E_ABI_MISMATCH;
    @autoreleasepool { @try {
        MCMTLFarConsumer *c=object(h);
        if (![c isKindOfClass:MCMTLFarConsumer.class]) return MCMTL_E_INVALID_ARGUMENT;
        if (!os_unfair_lock_trylock(&c->lock)) return MCMTL_FAR_CONSUMER_DECLINED;
        *out=c->receipt;
        os_unfair_lock_unlock(&c->lock);
        return MCMTL_OK;
    } @catch (NSException *ex) { (void)ex; return MCMTL_E_OBJC_EXCEPTION; } }
}
