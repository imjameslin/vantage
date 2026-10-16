#import "mcmetal_farwarp.h"
#include <math.h>
#include <stddef.h>
#include <string.h>

/* Plain arrays match MSL float4x4/uint2/int2 without a host SIMD dependency. */
typedef struct {
    float currentToSource[16], sourceToCurrent[16];
    uint32_t sourceSize[2], targetSize[2];
    int32_t cropOrigin[2];
    uint32_t exactCrop, padding;
} WarpUniforms;
_Static_assert(sizeof(WarpUniforms) == 160 && offsetof(WarpUniforms, exactCrop) == 152,
               "Far warp host/MSL uniform ABI");

/* No sampler/address clamping: required neighbours outside the guard are holes.
 * Compiled without fast math so finite checks and zero weights retain meaning. */
static NSString *const warpMSL = @
"#include <metal_stdlib>\n"
"using namespace metal;\n"
"struct Params { float4x4 c2s, s2c; uint2 srcSize, dstSize; int2 crop; uint exactCrop, pad; };\n"
"struct Result { float4 color; float depth; uint face; uint mask; };\n"
"constant uint Missing=0, Uncertain=1, Geometry=2, Sky=3;\n"
"Result gather(uint2 xy, constant Params &p, texture2d<float,access::read> color,\n"
"              depth2d<float,access::read> depth, texture2d<uint,access::read> ids) {\n"
"  Result bad={float4(0),0,0,Uncertain};\n"
"  if (p.exactCrop) {\n"
"    int2 q=int2(xy)+p.crop;\n"
"    if (any(q<0) || any(q>=int2(p.srcSize))) { bad.mask=Missing; return bad; }\n"
"    float d=depth.read(uint2(q)); uint face=ids.read(uint2(q)).x;\n"
"    if (!isfinite(d) || d<0 || d>1 || ((d==0)!=(face==0))) return bad;\n"
"    return {color.read(uint2(q)),d,face,face ? Geometry : Sky};\n"
"  }\n"
"  float2 ndc=(float2(xy)+0.5f)/float2(p.dstSize)*float2(2,-2)+float2(-1,1);\n"
"  float4 ray=p.c2s*float4(ndc,0,1);\n"
"  if (!all(isfinite(ray))) return bad;\n"
"  if (ray.w<=0) { bad.mask=Missing; return bad; }\n"
"  float2 srcNDC=ray.xy/ray.w;\n"
"  if (!all(isfinite(srcNDC))) return bad;\n"
"  if (any(abs(srcNDC)>1)) { bad.mask=Missing; return bad; }\n"
"  float2 pixel=(srcNDC*float2(0.5f,-0.5f)+0.5f)*float2(p.srcSize)-0.5f;\n"
"  int2 base=int2(floor(pixel)); float2 f=pixel-float2(base);\n"
"  float weights[4]={(1-f.x)*(1-f.y),f.x*(1-f.y),(1-f.x)*f.y,f.x*f.y};\n"
"  int2 offsets[4]={int2(0,0),int2(1,0),int2(0,1),int2(1,1)};\n"
"  // Test the complete required footprint before any texture reads.\n"
"  for (uint k=0;k<4;k++) if (weights[k]>0) {\n"
"    int2 q=base+offsets[k];\n"
"    if (any(q<0) || any(q>=int2(p.srcSize))) { bad.mask=Missing; return bad; }\n"
"  }\n"
"  float4 c=0; float d=0; uint face=0; bool first=true, sky=false;\n"
"  for (uint k=0;k<4;k++) if (weights[k]>0) {\n"
"    uint2 q=uint2(base+offsets[k]); float z=depth.read(q); uint id=ids.read(q).x;\n"
"    if (!isfinite(z) || z<0 || z>1 || ((z==0)!=(id==0))) return bad;\n"
"    if (first) { face=id; sky=(id==0); first=false; }\n"
"    else if (face!=id) return bad;\n"
"    c+=color.read(q)*weights[k]; d+=z*weights[k];\n"
"  }\n"
"  if (first || !all(isfinite(c)) || !isfinite(d)) return bad;\n"
"  if (sky) return {c,0,0,Sky};\n"
"  float4 current=p.s2c*float4(srcNDC,d,1);\n"
"  if (!all(isfinite(current))) return bad;\n"
"  if (current.w<=0 || current.z<=0 || current.z>current.w ||\n"
"      any(abs(current.xy)>current.w)) { bad.mask=Missing; return bad; }\n"
"  float z=current.z/current.w; if (!isfinite(z)) return bad;\n"
"  if (z<=0 || z>1) { bad.mask=Missing; return bad; }\n"
"  return {c,z,face,Geometry};\n"
"}\n"
"kernel void farwarp(uint2 xy [[thread_position_in_grid]], constant Params &p [[buffer(0)]],\n"
"  texture2d<float,access::read> srcColor [[texture(0)]],\n"
"  depth2d<float,access::read> srcDepth [[texture(1)]],\n"
"  texture2d<uint,access::read> srcID [[texture(2)]],\n"
"  texture2d<float,access::write> dstColor [[texture(3)]],\n"
"  texture2d<float,access::write> dstDepth [[texture(4)]],\n"
"  texture2d<uint,access::write> dstID [[texture(5)]],\n"
"  texture2d<uint,access::write> dstMask [[texture(6)]]) {\n"
"  if (any(xy>=p.dstSize)) return;\n"
"  Result r=gather(xy,p,srcColor,srcDepth,srcID);\n"
"  dstColor.write(r.color,xy); dstDepth.write(float4(r.depth),xy);\n"
"  dstID.write(uint4(r.face),xy); dstMask.write(uint4(r.mask),xy);\n"
"}\n";

static BOOL fail(NSError **error, NSString *message) {
    if (error) *error = [NSError errorWithDomain:@"mcmetal.far.warp" code:1
        userInfo:@{NSLocalizedDescriptionKey:message}];
    return NO;
}

static BOOL validDeclaration(const MCMTLFarWarpTransform *t) {
    if (!t || t->motion != MCMTLFarWarpMotionPureRotation) return NO;
    for (unsigned i = 0; i < 3; ++i)
        if (!isfinite(t->translation[i]) || t->translation[i] != 0) return NO;
    const float *a = t->currentToSourceClip, *b = t->sourceToCurrentClip;
    for (unsigned i = 0; i < 16; ++i) if (!isfinite(a[i]) || !isfinite(b[i])) return NO;
    /* The optical centre is (0,0,z,0) in either perspective clip space. Do not
     * hide any translation behind a small epsilon or an asserted motion enum. */
    if (a[8] != 0 || a[9] != 0 || a[11] != 0 || a[10] <= 0 ||
        b[8] != 0 || b[9] != 0 || b[11] != 0 || b[10] <= 0) return NO;
    return YES;
}

static BOOL inverseConsistent(const MCMTLFarWarpTransform *t) {
    const float *a = t->currentToSourceClip, *b = t->sourceToCurrentClip;
    for (unsigned r = 0; r < 4; ++r) for (unsigned c = 0; c < 4; ++c) {
        double ab = 0, ba = 0;
        for (unsigned k = 0; k < 4; ++k) {
            ab += (double)a[k*4+r] * b[c*4+k];
            ba += (double)b[k*4+r] * a[c*4+k];
        }
        /* Absolute residual, BOTH directions, accumulated in double. A relative
         * norm tolerance could accept a badly conditioned non-inverse pair. */
        if (fabs(ab - (r == c)) > 0.0002 || fabs(ba - (r == c)) > 0.0002) return NO;
    }
    return YES;
}

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

/* Recognize only exactly canonical integer crop matrices. This is NOT a UV
 * epsilon snap: an arbitrarily small representable noncanonical fraction must
 * still undergo the same-ID footprint test. Depth has an exact identity map. */
static void recognizeCrop(WarpUniforms *u) {
    double W = u->sourceSize[0], H = u->sourceSize[1], w = u->targetSize[0], h = u->targetSize[1];
    double x = round(((double)u->currentToSource[12]*W + W - w)*0.5);
    double y = round((H - h - (double)u->currentToSource[13]*H)*0.5);
    /* Farther-away crops cannot overlap; use the ordinary missing path instead. */
    if (x < -16384 || x > 16384 || y < -16384 || y > 16384) return;
    float a[16] = {0}, b[16] = {0};
    a[0] = (float)(w/W); a[5] = (float)(h/H); a[10] = a[15] = 1;
    a[12] = (float)((2*x+w-W)/W); a[13] = (float)((H-2*y-h)/H);
    b[0] = (float)(W/w); b[5] = (float)(H/h); b[10] = b[15] = 1;
    b[12] = (float)((W-w-2*x)/w); b[13] = (float)((2*y+h-H)/h);
    for (unsigned i = 0; i < 16; ++i)
        if (a[i] != u->currentToSource[i] || b[i] != u->sourceToCurrent[i]) return;
    u->exactCrop = 1; u->cropOrigin[0] = (int32_t)x; u->cropOrigin[1] = (int32_t)y;
}

@implementation MCMTLFarWarp {
    id<MTLDevice> _device;
    id<MTLComputePipelineState> _pipeline;
    MTLSize _threads;
}
+ (BOOL)accessInstanceVariablesDirectly { return NO; }
- (instancetype)initWithDevice:(id<MTLDevice>)device error:(NSError **)error {
    if (error) *error = nil;
    if (!(self = [super init])) return nil;
    @try {
        if (!device || [NSThread isMainThread]) {
            fail(error, @"Far warp creation requires a worker and Metal device"); return nil;
        }
        MTLCompileOptions *options = [MTLCompileOptions new];
        options.mathMode = MTLMathModeSafe;
        options.mathFloatingPointFunctions = MTLMathFloatingPointFunctionsPrecise;
        id<MTLLibrary> library = [device newLibraryWithSource:warpMSL options:options error:error];
        if (!library) return nil;
        id<MTLFunction> function = [library newFunctionWithName:@"farwarp"];
        if (!function) { fail(error, @"Missing farwarp kernel"); return nil; }
        _pipeline = [device newComputePipelineStateWithFunction:function error:error];
        if (!_pipeline) return nil;
        _device = device;
        NSUInteger width = MIN((NSUInteger)8, _pipeline.threadExecutionWidth);
        NSUInteger height = width ? MIN((NSUInteger)8, _pipeline.maxTotalThreadsPerThreadgroup / width) : 0;
        if (!width || !height) { fail(error, @"Invalid far warp threadgroup limits"); return nil; }
        _threads = MTLSizeMake(width, height, 1);
        return self;
    } @catch (NSException *e) { fail(error, e.reason ?: @"Far warp creation exception"); return nil; }
}
- (BOOL)encodeToCommandBuffer:(id<MTLCommandBuffer>)buffer source:(MCMTLFarWarpSource)source
                      targets:(MCMTLFarWarpTargets)targets transform:(const MCMTLFarWarpTransform *)transform
                        error:(NSError **)error {
    if (error) *error = nil;
    id<MTLComputeCommandEncoder> encoder = nil;
    @try {
        if (!buffer || buffer.device != _device || buffer.status != MTLCommandBufferStatusNotEnqueued ||
            !buffer.retainedReferences || !validDeclaration(transform))
            return fail(error, @"Expected unqueued retained-reference buffer, same device, inverse finite clip transforms and explicitly zero-translation rotation");
        __unsafe_unretained id<MTLTexture> textures[7] = {source.color, source.depth, source.visibilityID,
            targets.color, targets.depth, targets.visibilityID, targets.validity};
        const MTLPixelFormat formats[7] = {MTLPixelFormatRGBA8Unorm, MTLPixelFormatDepth32Float,
            MTLPixelFormatR32Uint, MTLPixelFormatRGBA8Unorm, MTLPixelFormatR32Float,
            MTLPixelFormatR32Uint, MTLPixelFormatR8Uint};
        for (unsigned i = 0; i < 7; ++i) {
            NSUInteger w = i < 3 ? source.color.width : targets.color.width;
            NSUInteger h = i < 3 ? source.color.height : targets.color.height;
            if (!validTexture(textures[i], _device, formats[i], w, h,
                              i < 3 ? MTLTextureUsageShaderRead : MTLTextureUsageShaderWrite))
                return fail(error, @"Invalid far warp device/format/dimensions/usage or unsupported aliased texture storage");
            for (unsigned j = 0; j < i; ++j) if (textures[i] == textures[j])
                return fail(error, @"Far warp source and target planes must not overlap");
        }
        WarpUniforms u = {0};
        memcpy(u.currentToSource, transform->currentToSourceClip, sizeof u.currentToSource);
        memcpy(u.sourceToCurrent, transform->sourceToCurrentClip, sizeof u.sourceToCurrent);
        u.sourceSize[0] = (uint32_t)source.color.width; u.sourceSize[1] = (uint32_t)source.color.height;
        u.targetSize[0] = (uint32_t)targets.color.width; u.targetSize[1] = (uint32_t)targets.color.height;
        recognizeCrop(&u);
        /* Exact equality to BOTH canonical crop matrices proves their inverse
         * relationship without a cancellation-sensitive float product. For a
         * 1-pixel crop from a 16K source, that product can lose >2e-4 solely to
         * correct coefficient rounding. The integer path uses neither matrix. */
        if (!u.exactCrop && !inverseConsistent(transform))
            return fail(error, @"Far warp clip transforms are not mutually inverse within absolute residual 2e-4");
        encoder = [buffer computeCommandEncoder];
        if (!encoder) return fail(error, @"Could not create far warp encoder; discard command buffer");
        [encoder setComputePipelineState:_pipeline];
        [encoder setBytes:&u length:sizeof u atIndex:0];
        for (unsigned i = 0; i < 7; ++i) [encoder setTexture:textures[i] atIndex:i];
        [encoder dispatchThreadgroups:MTLSizeMake((u.targetSize[0]+_threads.width-1)/_threads.width,
            (u.targetSize[1]+_threads.height-1)/_threads.height, 1) threadsPerThreadgroup:_threads];
        [encoder endEncoding];
        return YES;
    } @catch (NSException *e) {
        if (encoder) { @try { [encoder endEncoding]; } @catch (NSException *ignored) { (void)ignored; } }
        return fail(error, e.reason ?: @"Far warp encoding exception; discard command buffer");
    }
}
@end
