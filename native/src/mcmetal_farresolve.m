#import "mcmetal_farresolve.h"
#include <stddef.h>

/* uint2 followed by two uints, shared verbatim with MSL. */
typedef struct { uint32_t width, height, useOlder, count; } ResolveUniforms;
_Static_assert(sizeof(ResolveUniforms) == 16, "Far resolve uniform ABI");
_Static_assert(sizeof(MCMTLFarResolveStats) == 8 && offsetof(MCMTLFarResolveStats, fallback) == 4,
               "Far resolve GPU stats ABI");
_Static_assert(sizeof(MTLDrawPrimitivesIndirectArguments) == 16 &&
               sizeof(MTLDrawIndexedPrimitivesIndirectArguments) == 20, "Metal draw argument ABI");

static NSString *const resolveMSL = @
"#include <metal_stdlib>\n"
"using namespace metal;\n"
"struct Params { uint2 size; uint useOlder, count; };\n"
"struct Stats { atomic_uint holes; uint fallback; };\n"
"kernel void reset(device Stats &s [[buffer(0)]]) {\n"
"  atomic_store_explicit(&s.holes,0u,memory_order_relaxed); s.fallback=0;\n"
"}\n"
"bool mergeLayer(uint2 xy, bool useOlder,\n"
"  texture2d<float,access::read> nc, texture2d<float,access::read> nd,\n"
"  texture2d<uint,access::read> ni, texture2d<uint,access::read> nm,\n"
"  texture2d<float,access::read> oc, texture2d<float,access::read> od,\n"
"  texture2d<uint,access::read> oi, texture2d<uint,access::read> om,\n"
"  texture2d<float,access::write> dc, texture2d<float,access::write> dd,\n"
"  texture2d<uint,access::write> di, texture2d<uint,access::write> dm) {\n"
"  uint mask=nm.read(xy).x; float4 color=0; float depth=0; uint face=0;\n"
"  if (mask>=2) { color=nc.read(xy); depth=nd.read(xy).x; face=ni.read(xy).x; }\n"
"  else {\n"
"    mask=useOlder ? om.read(xy).x : 0u;\n"
"    if (mask>=2) { color=oc.read(xy); depth=od.read(xy).x; face=oi.read(xy).x; }\n"
"    else mask=0;\n"
"  }\n"
"  dc.write(color,xy); dd.write(float4(depth),xy); di.write(uint4(face),xy); dm.write(uint4(mask),xy);\n"
"  return mask<2;\n"
"}\n"
"kernel void merge(uint2 xy [[thread_position_in_grid]], uint ti [[thread_index_in_threadgroup]],\n"
"  constant Params &p [[buffer(0)]], device Stats &s [[buffer(1)]],\n"
"  texture2d<float,access::read> nc0 [[texture(0)]], texture2d<float,access::read> nd0 [[texture(1)]],\n"
"  texture2d<uint,access::read> ni0 [[texture(2)]], texture2d<uint,access::read> nm0 [[texture(3)]],\n"
"  texture2d<float,access::read> oc0 [[texture(4)]], texture2d<float,access::read> od0 [[texture(5)]],\n"
"  texture2d<uint,access::read> oi0 [[texture(6)]], texture2d<uint,access::read> om0 [[texture(7)]],\n"
"  texture2d<float,access::write> dc0 [[texture(8)]], texture2d<float,access::write> dd0 [[texture(9)]],\n"
"  texture2d<uint,access::write> di0 [[texture(10)]], texture2d<uint,access::write> dm0 [[texture(11)]],\n"
"  texture2d<float,access::read> nc1 [[texture(12)]], texture2d<float,access::read> nd1 [[texture(13)]],\n"
"  texture2d<uint,access::read> ni1 [[texture(14)]], texture2d<uint,access::read> nm1 [[texture(15)]],\n"
"  texture2d<float,access::read> oc1 [[texture(16)]], texture2d<float,access::read> od1 [[texture(17)]],\n"
"  texture2d<uint,access::read> oi1 [[texture(18)]], texture2d<uint,access::read> om1 [[texture(19)]],\n"
"  texture2d<float,access::write> dc1 [[texture(20)]], texture2d<float,access::write> dd1 [[texture(21)]],\n"
"  texture2d<uint,access::write> di1 [[texture(22)]], texture2d<uint,access::write> dm1 [[texture(23)]],\n"
"  texture2d<uint,access::write> holeMask [[texture(24)]]) {\n"
"  threadgroup atomic_uint holes;\n"
"  if (ti==0) atomic_store_explicit(&holes,0u,memory_order_relaxed);\n"
"  threadgroup_barrier(mem_flags::mem_threadgroup);\n"
"  if (all(xy<p.size)) {\n"
"    bool a=mergeLayer(xy,p.useOlder,nc0,nd0,ni0,nm0,oc0,od0,oi0,om0,dc0,dd0,di0,dm0);\n"
"    bool b=mergeLayer(xy,p.useOlder,nc1,nd1,ni1,nm1,oc1,od1,oi1,om1,dc1,dd1,di1,dm1);\n"
"    uint hole=uint(a||b); holeMask.write(uint4(hole),xy);\n"
"    if (hole) atomic_fetch_add_explicit(&holes,1u,memory_order_relaxed);\n"
"  }\n"
"  threadgroup_barrier(mem_flags::mem_threadgroup);\n"
"  if (ti==0) { uint n=atomic_load_explicit(&holes,memory_order_relaxed);\n"
"    if (n) atomic_fetch_add_explicit(&s.holes,n,memory_order_relaxed); }\n"
"}\n"
"kernel void selectArgs(uint i [[thread_position_in_grid]], constant Params &p [[buffer(0)]],\n"
"  device Stats &s [[buffer(1)]], const device uint *src [[buffer(2)]],\n"
"  device uint *dst [[buffer(3)]], device uint *composite [[buffer(4)]]) {\n"
"  bool fallback=atomic_load_explicit(&s.holes,memory_order_relaxed)!=0;\n"
"  if (i==0) s.fallback=uint(fallback);\n"
"  if (i<4) composite[i]=fallback ? 0u : (i==0 ? 3u : (i==1 ? 1u : 0u));\n"
"  if (i<p.count*5) dst[i]=fallback ? src[i] : 0u;\n"
"}\n";

static BOOL fail(NSError **error, NSString *message) {
    if (error) *error = [NSError errorWithDomain:@"mcmetal.far.resolve" code:1
        userInfo:@{NSLocalizedDescriptionKey:message}];
    return NO;
}

/* Keep the same conservative no-alias texture contract as mcmetal_farwarp. */
static BOOL validTexture(id<MTLTexture> t, id<MTLDevice> d, MTLPixelFormat format,
                         NSUInteger w, NSUInteger h, MTLTextureUsage usage) {
    if (!t || t.device != d || t.pixelFormat != format || t.textureType != MTLTextureType2D ||
        t.width != w || t.height != h || !w || !h || w > 16384 || h > 16384 ||
        t.depth != 1 || t.arrayLength != 1 || t.mipmapLevelCount != 1 || t.sampleCount != 1 ||
        (t.usage & usage) != usage || t.hazardTrackingMode != MTLHazardTrackingModeTracked ||
        (t.storageMode != MTLStorageModePrivate && t.storageMode != MTLStorageModeShared &&
         t.storageMode != MTLStorageModeManaged) ||
        t.parentTexture || t.heap || t.isAliasable || t.buffer || t.iosurface || t.shareable ||
        t.framebufferOnly || t.remoteStorageTexture ||
        ![t respondsToSelector:@selector(isSparse)] || t.isSparse) return NO;
    MTLTextureSwizzleChannels s = t.swizzle;
    return s.red == MTLTextureSwizzleRed && s.green == MTLTextureSwizzleGreen &&
           s.blue == MTLTextureSwizzleBlue && s.alpha == MTLTextureSwizzleAlpha;
}

static BOOL validBuffer(id<MTLBuffer> b, id<MTLDevice> d, NSUInteger offset, NSUInteger bytes) {
    return b && b.device == d && b.hazardTrackingMode == MTLHazardTrackingModeTracked &&
        (b.storageMode == MTLStorageModePrivate || b.storageMode == MTLStorageModeShared ||
         b.storageMode == MTLStorageModeManaged) && !b.heap && !b.isAliasable && !b.remoteStorageBuffer &&
        b.sparseBufferTier == MTLBufferSparseTierNone && b.gpuAddress &&
        !(offset & 3) && offset <= b.length && bytes <= b.length - offset;
}
static BOOL overlap(uint64_t a, NSUInteger an, uint64_t b, NSUInteger bn) {
    return a <= b ? b - a < an : a - b < bn;
}
static BOOL buffersAlias(id<MTLBuffer> a, id<MTLBuffer> b) {
    if (a == b || overlap(a.gpuAddress, a.length, b.gpuAddress, b.length)) return YES;
    if (a.storageMode != MTLStorageModePrivate && b.storageMode != MTLStorageModePrivate) {
        uintptr_t ap = (uintptr_t)a.contents, bp = (uintptr_t)b.contents;
        if (!ap || !bp || overlap(ap, a.length, bp, b.length)) return YES;
    }
    return NO;
}

@implementation MCMTLFarResolve {
    id<MTLDevice> _device;
    id<MTLComputePipelineState> _reset, _merge, _select;
    MTLSize _mergeThreads;
    NSUInteger _selectThreads;
}
+ (BOOL)accessInstanceVariablesDirectly { return NO; }
- (instancetype)initWithDevice:(id<MTLDevice>)device error:(NSError **)error {
    if (error) *error = nil;
    if (!(self = [super init])) return nil;
    @try {
        if (!device || [NSThread isMainThread]) {
            fail(error, @"Far resolve creation requires a worker and Metal device"); return nil;
        }
        MTLCompileOptions *options = [MTLCompileOptions new];
        options.mathMode = MTLMathModeSafe;
        options.mathFloatingPointFunctions = MTLMathFloatingPointFunctionsPrecise;
        id<MTLLibrary> library = [device newLibraryWithSource:resolveMSL options:options error:error];
        if (!library) return nil;
        id<MTLFunction> reset = [library newFunctionWithName:@"reset"];
        id<MTLFunction> merge = [library newFunctionWithName:@"merge"];
        id<MTLFunction> select = [library newFunctionWithName:@"selectArgs"];
        if (!reset || !merge || !select) { fail(error, @"Missing far resolve kernel"); return nil; }
        _reset = [device newComputePipelineStateWithFunction:reset error:error];
        if (!_reset) return nil;
        _merge = [device newComputePipelineStateWithFunction:merge error:error];
        if (!_merge) return nil;
        _select = [device newComputePipelineStateWithFunction:select error:error];
        if (!_select) return nil;
        _device = device;
        NSUInteger w = MIN((NSUInteger)8, _merge.threadExecutionWidth);
        NSUInteger h = w ? MIN((NSUInteger)8, _merge.maxTotalThreadsPerThreadgroup / w) : 0;
        _selectThreads = MIN((NSUInteger)64, _select.maxTotalThreadsPerThreadgroup);
        if (!w || !h || !_selectThreads) { fail(error, @"Invalid far resolve threadgroup limits"); return nil; }
        _mergeThreads = MTLSizeMake(w,h,1);
        return self;
    } @catch (NSException *e) { fail(error, e.reason ?: @"Far resolve creation exception"); return nil; }
}

- (BOOL)encodeToCommandBuffer:(id<MTLCommandBuffer>)buffer newest:(MCMTLFarResolvePair)newest
                        older:(const MCMTLFarResolvePair *)older
             olderEligibility:(MCMTLFarResolveOlderEligibility)eligibility
                      targets:(MCMTLFarResolvePair)targets holeMask:(id<MTLTexture>)holeMask
                    arguments:(MCMTLFarResolveArguments)args error:(NSError **)error {
    if (error) *error = nil;
    id<MTLComputeCommandEncoder> encoder = nil;
    @try {
        if (!buffer || buffer.device != _device || buffer.status != MTLCommandBufferStatusNotEnqueued ||
            !buffer.retainedReferences)
            return fail(error, @"Expected unqueued retained-reference main command buffer on the same device");
        BOOL useOlder = older && eligibility.sceneMatchesCurrent &&
                        eligibility.materialMatchesCurrent && eligibility.sourceFresh;
        NSUInteger w = targets.layers[0].color.width, h = targets.layers[0].color.height;
        __unsafe_unretained id<MTLTexture> textures[25];
        const MTLPixelFormat formats[4] = {MTLPixelFormatRGBA8Unorm, MTLPixelFormatR32Float,
                                           MTLPixelFormatR32Uint, MTLPixelFormatR8Uint};
        for (unsigned l = 0; l < 2; ++l) {
            const MCMTLFarWarpTargets planes[3] = {newest.layers[l],
                useOlder ? older->layers[l] : newest.layers[l], targets.layers[l]};
            for (unsigned p = 0; p < 3; ++p) {
                unsigned i = l*12+p*4;
                textures[i] = planes[p].color; textures[i+1] = planes[p].depth;
                textures[i+2] = planes[p].visibilityID; textures[i+3] = planes[p].validity;
            }
        }
        textures[24] = holeMask;
        for (unsigned i = 0; i < 25; ++i) {
            /* Unused older bindings reuse newest read-only planes; never inspect stale resources. */
            if (i < 24 && i%12 >= 4 && i%12 < 8 && !useOlder) continue;
            BOOL output = i == 24 || i%12 >= 8;
            if (!validTexture(textures[i], _device, i == 24 ? MTLPixelFormatR8Uint : formats[i%4], w, h,
                              output ? MTLTextureUsageShaderWrite : MTLTextureUsageShaderRead))
                return fail(error, @"Invalid far resolve device/format/dimensions/usage or unsupported texture storage");
            for (unsigned j = 0; j < i; ++j) {
                if (j%12 >= 4 && j%12 < 8 && !useOlder) continue;
                if (textures[i] == textures[j]) return fail(error, @"Far resolve texture planes must be distinct");
            }
        }
        if (args.count > MCMTLFarResolveMaxDrawCount)
            return fail(error, @"Far resolve supports at most 4096 indexed-indirect records total");
        __unsafe_unretained id<MTLBuffer> buffers[4] = {args.source,args.fallback,args.composite,args.stats};
        const NSUInteger offsets[4] = {args.sourceOffset,args.fallbackOffset,args.compositeOffset,args.statsOffset};
        NSUInteger bytes = MAX((NSUInteger)4, (NSUInteger)args.count*20);
        const NSUInteger sizes[4] = {bytes,bytes,16,sizeof(MCMTLFarResolveStats)};
        for (unsigned i = 0; i < 4; ++i) {
            if (!validBuffer(buffers[i],_device,offsets[i],sizes[i]))
                return fail(error, @"Invalid far resolve buffer storage, offset, size or device");
            for (unsigned j = 0; j < i; ++j) if (buffersAlias(buffers[i],buffers[j]))
                return fail(error, @"Far resolve buffer allocations must not alias");
        }
        ResolveUniforms u = {(uint32_t)w,(uint32_t)h,(uint32_t)useOlder,args.count};
        /* Separate encoders + tracked resources order reset -> merge -> final,
         * including on caller-owned sideCBs. No CPU read of decision or args. */
        encoder = [buffer computeCommandEncoder];
        if (!encoder) return fail(error, @"Could not create far resolve reset encoder; discard command buffer");
        [encoder setComputePipelineState:_reset];
        [encoder setBuffer:args.stats offset:args.statsOffset atIndex:0];
        [encoder dispatchThreadgroups:MTLSizeMake(1,1,1) threadsPerThreadgroup:MTLSizeMake(1,1,1)];
        [encoder endEncoding]; encoder = nil;
        encoder = [buffer computeCommandEncoder];
        if (!encoder) return fail(error, @"Could not create far resolve merge encoder; discard command buffer");
        [encoder setComputePipelineState:_merge];
        [encoder setBytes:&u length:sizeof u atIndex:0];
        [encoder setBuffer:args.stats offset:args.statsOffset atIndex:1];
        for (unsigned i = 0; i < 25; ++i) [encoder setTexture:textures[i] atIndex:i];
        [encoder dispatchThreadgroups:MTLSizeMake((w+_mergeThreads.width-1)/_mergeThreads.width,
            (h+_mergeThreads.height-1)/_mergeThreads.height,1) threadsPerThreadgroup:_mergeThreads];
        [encoder endEncoding]; encoder = nil;
        encoder = [buffer computeCommandEncoder];
        if (!encoder) return fail(error, @"Could not create far resolve selection encoder; discard command buffer");
        [encoder setComputePipelineState:_select];
        [encoder setBytes:&u length:sizeof u atIndex:0];
        [encoder setBuffer:args.stats offset:args.statsOffset atIndex:1];
        [encoder setBuffer:args.source offset:args.sourceOffset atIndex:2];
        [encoder setBuffer:args.fallback offset:args.fallbackOffset atIndex:3];
        [encoder setBuffer:args.composite offset:args.compositeOffset atIndex:4];
        NSUInteger words = MAX((NSUInteger)4, (NSUInteger)args.count*5);
        [encoder dispatchThreadgroups:MTLSizeMake((words+_selectThreads-1)/_selectThreads,1,1)
                threadsPerThreadgroup:MTLSizeMake(_selectThreads,1,1)];
        [encoder endEncoding];
        return YES;
    } @catch (NSException *e) {
        if (encoder) { @try { [encoder endEncoding]; } @catch (NSException *ignored) { (void)ignored; } }
        return fail(error, e.reason ?: @"Far resolve encoding exception; discard command buffer");
    }
}
@end
