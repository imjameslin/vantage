#import "mcmetal_faralpha.h"
#include <stdint.h>

static NSString *const alphaMSL = @
"#include <metal_stdlib>\n"
"using namespace metal;\n"
"kernel void faralpha(texture2d<float, access::read> oldMaterial [[texture(0)]],\n"
"                     texture2d<float, access::read> currentMaterial [[texture(1)]],\n"
"                     device atomic_uint *mismatch [[buffer(0)]],\n"
"                     constant uint &mip [[buffer(1)]],\n"
"                     uint2 p [[thread_position_in_grid]]) {\n"
"    if (p.x >= oldMaterial.get_width(mip) || p.y >= oldMaterial.get_height(mip)) return;\n"
// RGBA8Unorm's 256 alpha values map injectively to float. No interpolation,
// epsilon, reduction, RGB comparison, or sampler/clamp participates here.
"    if (oldMaterial.read(p, mip).a != currentMaterial.read(p, mip).a)\n"
"        atomic_fetch_or_explicit(mismatch, 1u, memory_order_relaxed);\n"
"}\n";

static BOOL fail(NSError **error, NSString *message) {
    if (error) *error=[NSError errorWithDomain:@"mcmetal.far.alpha" code:1
        userInfo:@{NSLocalizedDescriptionKey:message ?: @"Far alpha failure"}];
    return NO;
}
static BOOL supportedStorage(MTLStorageMode mode) {
    return mode==MTLStorageModePrivate || mode==MTLStorageModeShared || mode==MTLStorageModeManaged;
}
static BOOL texture(id<MTLTexture> t, id<MTLDevice> device) {
    if (!t || t.device!=device || t.pixelFormat!=MTLPixelFormatRGBA8Unorm ||
        t.textureType!=MTLTextureType2D || t.sampleCount!=1 || t.depth!=1 || t.arrayLength!=1 ||
        !t.width || t.width>16384 || !t.height || t.height>16384 ||
        !(t.usage&MTLTextureUsageShaderRead) || t.hazardTrackingMode!=MTLHazardTrackingModeTracked ||
        !supportedStorage(t.storageMode) || t.parentTexture || t.heap || t.buffer || t.isAliasable ||
        t.iosurface || t.shareable || t.framebufferOnly || t.remoteStorageTexture || t.isSparse) return NO;
    MTLTextureSwizzleChannels s=t.swizzle;
    if (s.red!=MTLTextureSwizzleRed || s.green!=MTLTextureSwizzleGreen ||
        s.blue!=MTLTextureSwizzleBlue || s.alpha!=MTLTextureSwizzleAlpha) return NO;
    NSUInteger maxLevels=1;
    for (NSUInteger n=MAX(t.width,t.height); n>1; n>>=1) ++maxLevels;
    return t.mipmapLevelCount && t.mipmapLevelCount<=maxLevels;
}
static BOOL pair(id<MTLTexture> a, id<MTLTexture> b, id<MTLDevice> device) {
    return texture(a,device) && texture(b,device) && a.width==b.width && a.height==b.height &&
        a.mipmapLevelCount==b.mipmapLevelCount;
}
static BOOL output(id<MTLBuffer> b, id<MTLDevice> device) {
    // Texture inputs cannot alias this writer: all texture parents are standalone
    // allocations, excluding buffer backing, heaps, IOSurfaces and remote storage.
    return b && b.device==device && b.length>=sizeof(uint32_t) && b.gpuAddress && !(b.gpuAddress&3u) &&
        b.hazardTrackingMode==MTLHazardTrackingModeTracked && supportedStorage(b.storageMode) &&
        !b.heap && !b.isAliasable && !b.remoteStorageBuffer && b.sparseBufferTier==MTLBufferSparseTierNone;
}

@implementation MCMTLFarAlpha {
    id<MTLComputePipelineState> _pipeline;
    MTLSize _threads;
}
+ (BOOL)accessInstanceVariablesDirectly { return NO; }
- (instancetype)initWithDevice:(id<MTLDevice>)device error:(NSError **)error {
    if (error) *error=nil;
    if (!(self=[super init])) return nil;
    @try {
        if (!device || [NSThread isMainThread]) {
            fail(error,@"Far alpha construction requires a worker and Metal device"); return nil;
        }
        MTLCompileOptions *options=[MTLCompileOptions new];
        options.mathMode=MTLMathModeSafe;
        options.mathFloatingPointFunctions=MTLMathFloatingPointFunctionsPrecise;
        id<MTLLibrary> library=[device newLibraryWithSource:alphaMSL options:options error:error];
        if (!library) return nil;
        id<MTLFunction> function=[library newFunctionWithName:@"faralpha"];
        if (!function) { fail(error,@"Missing faralpha kernel"); return nil; }
        _pipeline=[device newComputePipelineStateWithFunction:function error:error];
        if (!_pipeline) return nil;
        NSUInteger width=MIN((NSUInteger)8,_pipeline.threadExecutionWidth);
        NSUInteger height=width ? MIN((NSUInteger)8,_pipeline.maxTotalThreadsPerThreadgroup/width) : 0;
        if (!width || !height) { fail(error,@"Invalid far alpha threadgroup limits"); return nil; }
        _threads=MTLSizeMake(width,height,1); _device=device;
        return self;
    } @catch (NSException *e) { fail(error,e.reason); return nil; }
}
- (BOOL)encodeToCommandBuffer:(id<MTLCommandBuffer>)buffer
                    oldAtlas:(id<MTLTexture>)oldAtlas currentAtlas:(id<MTLTexture>)currentAtlas
                    oldLight:(id<MTLTexture>)oldLight currentLight:(id<MTLTexture>)currentLight
                      result:(id<MTLBuffer>)result error:(NSError **)error {
    if (error) *error=nil;
    id<MTLBlitCommandEncoder> blit=nil;
    id<MTLComputeCommandEncoder> encoder=nil;
    @try {
        if (!buffer || buffer.device!=_device || buffer.status!=MTLCommandBufferStatusNotEnqueued ||
            !buffer.retainedReferences)
            return fail(error,@"Far alpha requires a same-device unqueued retained-reference command buffer");
        if (!pair(oldAtlas,currentAtlas,_device) || !pair(oldLight,currentLight,_device))
            return fail(error,@"Far alpha requires matching full tracked RGBA8 2D material parents");
        if (!output(result,_device))
            return fail(error,@"Far alpha result must be a standalone tracked same-device buffer of at least 4 bytes");
        blit=[buffer blitCommandEncoder];
        if (!blit) return fail(error,@"Cannot create far alpha clear encoder; discard command buffer");
        blit.label=@"mcmetal far alpha clear";
        [blit fillBuffer:result range:NSMakeRange(0,sizeof(uint32_t)) value:0];
        [blit endEncoding]; blit=nil;
        encoder=[buffer computeCommandEncoder];
        if (!encoder) return fail(error,@"Cannot create far alpha comparison encoder; discard command buffer");
        encoder.label=@"mcmetal far all-mip material alpha";
        [encoder setComputePipelineState:_pipeline];
        [encoder setBuffer:result offset:0 atIndex:0];
        __unsafe_unretained id<MTLTexture> old[2]={oldAtlas,oldLight}, current[2]={currentAtlas,currentLight};
        for (unsigned i=0;i<2;++i) {
            [encoder setTexture:old[i] atIndex:0]; [encoder setTexture:current[i] atIndex:1];
            for (uint32_t mip=0;mip<old[i].mipmapLevelCount;++mip) {
                NSUInteger width=MAX((NSUInteger)1,old[i].width>>mip), height=MAX((NSUInteger)1,old[i].height>>mip);
                [encoder setBytes:&mip length:sizeof mip atIndex:1];
                [encoder dispatchThreadgroups:MTLSizeMake((width+_threads.width-1)/_threads.width,
                    (height+_threads.height-1)/_threads.height,1) threadsPerThreadgroup:_threads];
            }
        }
        [encoder endEncoding]; encoder=nil;
        return YES;
    } @catch (NSException *e) {
        if (blit) { @try { [blit endEncoding]; } @catch (NSException *ignored) { (void)ignored; } }
        if (encoder) { @try { [encoder endEncoding]; } @catch (NSException *ignored) { (void)ignored; } }
        return fail(error,e.reason ?: @"Far alpha encoding exception; discard command buffer");
    }
}
@end
