#import "mcmetal_farlayerresolve.h"
#include <stddef.h>

typedef struct {uint32_t width,height,count,reserved;} LayerUniforms;
_Static_assert(sizeof(MCMTLFarLayerResolveStats)==16 && offsetof(MCMTLFarLayerResolveStats,fallback)==8,"Layer resolve GPU ABI");
static NSString *const layerMSL=@
"#include <metal_stdlib>\n"
"using namespace metal;\n"
"struct Params { uint2 size; uint count, reserved; };\n"
"struct Stats { atomic_uint holes[2]; uint fallback[2]; };\n"
"kernel void reset(device Stats &s [[buffer(0)]]) {\n"
" for(uint l=0;l<2;l++) {atomic_store_explicit(&s.holes[l],0u,memory_order_relaxed);s.fallback[l]=0;}\n"
"}\n"
"bool copyLayer(uint2 xy, texture2d<float,access::read> c, texture2d<float,access::read> d,\n"
" texture2d<uint,access::read> id, texture2d<uint,access::read> mask,\n"
" texture2d<float,access::write> oc, texture2d<float,access::write> od,\n"
" texture2d<uint,access::write> oi, texture2d<uint,access::write> om) {\n"
" uint m=mask.read(xy).x; bool valid=m>=2u;\n"
" oc.write(valid?c.read(xy):float4(0),xy);od.write(valid?d.read(xy):float4(0),xy);\n"
" oi.write(valid?id.read(xy):uint4(0),xy);om.write(valid?uint4(m):uint4(0),xy);\n"
" return !valid;\n"
"}\n"
"kernel void copyCount(uint2 xy [[thread_position_in_grid]], uint ti [[thread_index_in_threadgroup]],\n"
" constant Params &p [[buffer(0)]], device Stats &s [[buffer(1)]],\n"
" texture2d<float,access::read> c0 [[texture(0)]],texture2d<float,access::read> d0 [[texture(1)]],\n"
" texture2d<uint,access::read> i0 [[texture(2)]],texture2d<uint,access::read> m0 [[texture(3)]],\n"
" texture2d<float,access::write> oc0 [[texture(4)]],texture2d<float,access::write> od0 [[texture(5)]],\n"
" texture2d<uint,access::write> oi0 [[texture(6)]],texture2d<uint,access::write> om0 [[texture(7)]],\n"
" texture2d<float,access::read> c1 [[texture(8)]],texture2d<float,access::read> d1 [[texture(9)]],\n"
" texture2d<uint,access::read> i1 [[texture(10)]],texture2d<uint,access::read> m1 [[texture(11)]],\n"
" texture2d<float,access::write> oc1 [[texture(12)]],texture2d<float,access::write> od1 [[texture(13)]],\n"
" texture2d<uint,access::write> oi1 [[texture(14)]],texture2d<uint,access::write> om1 [[texture(15)]]) {\n"
" threadgroup atomic_uint n[2];\n"
" if(ti==0) {atomic_store_explicit(&n[0],0u,memory_order_relaxed);atomic_store_explicit(&n[1],0u,memory_order_relaxed);}\n"
" threadgroup_barrier(mem_flags::mem_threadgroup);\n"
" if(all(xy<p.size)) {\n"
"  if(copyLayer(xy,c0,d0,i0,m0,oc0,od0,oi0,om0)) atomic_fetch_add_explicit(&n[0],1u,memory_order_relaxed);\n"
"  if(copyLayer(xy,c1,d1,i1,m1,oc1,od1,oi1,om1)) atomic_fetch_add_explicit(&n[1],1u,memory_order_relaxed);\n"
" }\n"
" threadgroup_barrier(mem_flags::mem_threadgroup);\n"
" if(ti==0) for(uint l=0;l<2;l++) {uint k=atomic_load_explicit(&n[l],memory_order_relaxed);if(k)atomic_fetch_add_explicit(&s.holes[l],k,memory_order_relaxed);}\n"
"}\n"
"kernel void selectArgs(uint i [[thread_position_in_grid]],constant Params &p [[buffer(0)]],\n"
" device Stats &s [[buffer(1)]],device const uint *src [[buffer(2)]],device uint *dst [[buffer(3)]],\n"
" device uint *composite [[buffer(4)]],device const uint *codes [[buffer(5)]],device const uint *alpha [[buffer(6)]]) {\n"
" bool fall[2]={atomic_load_explicit(&s.holes[0],memory_order_relaxed)!=0u,\n"
"               atomic_load_explicit(&s.holes[1],memory_order_relaxed)!=0u || *alpha!=0u};\n"
" if(i<2u) s.fallback[i]=uint(fall[i]);\n"
" if(i<8u) {uint l=i/4u,j=i%4u;composite[i]=fall[l]?0u:(j==0u?3u:(j==1u?1u:0u));}\n"
" if(i<p.count*5u) {uint layer=codes[i/5u]&7u;dst[i]=(layer>=2u || fall[min(layer,1u)])?src[i]:0u;}\n"
"}\n"
;

static BOOL fail(NSError **error, NSString *message) {
    if (error) *error = [NSError errorWithDomain:@"mcmetal.far.layerresolve" code:1
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

@implementation MCMTLFarLayerResolve {
 id<MTLDevice> _device;
 id<MTLComputePipelineState> _reset,_copy,_select;
 MTLSize _copyThreads;
 NSUInteger _selectThreads;
}
+ (BOOL)accessInstanceVariablesDirectly {return NO;}
- (instancetype)initWithDevice:(id<MTLDevice>)device error:(NSError **)error {
 if(error)*error=nil;
 if(!(self=[super init]))return nil;
 @try {
  if(!device || [NSThread isMainThread]) {fail(error,@"Layer resolve requires worker and device");return nil;}
  MTLCompileOptions *o=[MTLCompileOptions new];o.mathMode=MTLMathModeSafe;
  o.mathFloatingPointFunctions=MTLMathFloatingPointFunctionsPrecise;
  id<MTLLibrary> lib=[device newLibraryWithSource:layerMSL options:o error:error];if(!lib)return nil;
  id<MTLFunction> a=[lib newFunctionWithName:@"reset"],b=[lib newFunctionWithName:@"copyCount"],c=[lib newFunctionWithName:@"selectArgs"];
  if(!a||!b||!c){fail(error,@"Missing layer resolve kernel");return nil;}
  _reset=[device newComputePipelineStateWithFunction:a error:error];if(!_reset)return nil;
  _copy=[device newComputePipelineStateWithFunction:b error:error];if(!_copy)return nil;
  _select=[device newComputePipelineStateWithFunction:c error:error];if(!_select)return nil;
  NSUInteger w=MIN((NSUInteger)8,_copy.threadExecutionWidth),h=w?MIN((NSUInteger)8,_copy.maxTotalThreadsPerThreadgroup/w):0;
  _selectThreads=MIN((NSUInteger)64,_select.maxTotalThreadsPerThreadgroup);
  if(!w||!h||!_selectThreads){fail(error,@"Invalid layer resolve threadgroup limits");return nil;}
  _device=device;_copyThreads=MTLSizeMake(w,h,1);return self;
 } @catch(NSException *e){fail(error,e.reason);return nil;}
}
- (BOOL)encodeToCommandBuffer:(id<MTLCommandBuffer>)buffer newest:(MCMTLFarResolvePair)newest
 targets:(MCMTLFarResolvePair)targets sourceArgs:(id<MTLBuffer>)source fallbackArgs:(id<MTLBuffer>)fallback
 compositeArgs:(id<MTLBuffer>)composite layerCodes:(id<MTLBuffer>)codes alpha:(id<MTLBuffer>)alpha
 stats:(id<MTLBuffer>)stats count:(uint32_t)count error:(NSError **)error {
 if(error)*error=nil;id<MTLComputeCommandEncoder> e=nil;
 @try {
  if(!buffer||buffer.device!=_device||buffer.status!=MTLCommandBufferStatusNotEnqueued||!buffer.retainedReferences)
   return fail(error,@"Layer resolve requires unqueued retained-reference same-device command buffer");
  if(count>MCMTLFarResolveMaxDrawCount)return fail(error,@"Layer resolve draw count exceeds bound");
  NSUInteger w=targets.layers[0].color.width,h=targets.layers[0].color.height;
  __unsafe_unretained id<MTLTexture> ts[16];
  const MTLPixelFormat formats[4]={MTLPixelFormatRGBA8Unorm,MTLPixelFormatR32Float,MTLPixelFormatR32Uint,MTLPixelFormatR8Uint};
  for(unsigned l=0;l<2;l++) {
   MCMTLFarWarpTargets p[2]={newest.layers[l],targets.layers[l]};
   for(unsigned j=0;j<2;j++){unsigned at=l*8+j*4;ts[at]=p[j].color;ts[at+1]=p[j].depth;ts[at+2]=p[j].visibilityID;ts[at+3]=p[j].validity;}
  }
  for(unsigned i=0;i<16;i++) {
   if(!validTexture(ts[i],_device,formats[i%4],w,h,i%8>=4?MTLTextureUsageShaderWrite:MTLTextureUsageShaderRead))
    return fail(error,@"Unsupported layer resolve texture");
   for(unsigned j=0;j<i;j++)if(ts[i]==ts[j])return fail(error,@"Layer resolve planes must not alias");
  }
  __unsafe_unretained id<MTLBuffer> bs[6]={source,fallback,composite,codes,alpha,stats};
  NSUInteger bytes=MAX((NSUInteger)4,(NSUInteger)count*20),codeBytes=MAX((NSUInteger)4,(NSUInteger)count*4);
  const NSUInteger sizes[6]={bytes,bytes,32,codeBytes,4,sizeof(MCMTLFarLayerResolveStats)};
  for(unsigned i=0;i<6;i++) {
   if(!validBuffer(bs[i],_device,0,sizes[i]))return fail(error,@"Unsupported layer resolve buffer");
   for(unsigned j=0;j<i;j++)if(buffersAlias(bs[i],bs[j]))return fail(error,@"Layer resolve buffers must not alias");
  }
  LayerUniforms u={(uint32_t)w,(uint32_t)h,count,0};
  e=[buffer computeCommandEncoder];if(!e)return fail(error,@"Layer reset encoder failed; discard buffer");
  [e setComputePipelineState:_reset];[e setBuffer:stats offset:0 atIndex:0];
  [e dispatchThreadgroups:MTLSizeMake(1,1,1) threadsPerThreadgroup:MTLSizeMake(1,1,1)];[e endEncoding];e=nil;
  e=[buffer computeCommandEncoder];if(!e)return fail(error,@"Layer copy encoder failed; discard buffer");
  [e setComputePipelineState:_copy];[e setBytes:&u length:sizeof(u) atIndex:0];[e setBuffer:stats offset:0 atIndex:1];
  for(unsigned i=0;i<16;i++)[e setTexture:ts[i] atIndex:i];
  [e dispatchThreadgroups:MTLSizeMake((w+_copyThreads.width-1)/_copyThreads.width,(h+_copyThreads.height-1)/_copyThreads.height,1) threadsPerThreadgroup:_copyThreads];[e endEncoding];e=nil;
  e=[buffer computeCommandEncoder];if(!e)return fail(error,@"Layer select encoder failed; discard buffer");
  [e setComputePipelineState:_select];[e setBytes:&u length:sizeof(u) atIndex:0];[e setBuffer:stats offset:0 atIndex:1];
  [e setBuffer:source offset:0 atIndex:2];[e setBuffer:fallback offset:0 atIndex:3];[e setBuffer:composite offset:0 atIndex:4];
  [e setBuffer:codes offset:0 atIndex:5];[e setBuffer:alpha offset:0 atIndex:6];
  NSUInteger words=MAX((NSUInteger)8,(NSUInteger)count*5);
  [e dispatchThreadgroups:MTLSizeMake((words+_selectThreads-1)/_selectThreads,1,1) threadsPerThreadgroup:MTLSizeMake(_selectThreads,1,1)];[e endEncoding];e=nil;return YES;
 } @catch(NSException *ex) {
  if(e){@try{[e endEncoding];}@catch(NSException *ignored){(void)ignored;}}
  return fail(error,ex.reason?:@"Layer resolve failed; discard command buffer");
 }
}
@end
