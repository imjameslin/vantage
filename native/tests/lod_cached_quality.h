#pragma once
// Standalone bounded Prompt7 instrumentation. ObjC++17/ARC; link Foundation/Metal.
//
//   lodcachedquality::Evaluator quality(device, @"native/tests/lod_cached_quality.metal");
//   NSDictionary* m = quality.evaluate(queue, candidateRGBA8, referenceRGBA8);
//   bool passed = [m[@"ok"] boolValue];
//
// Inputs: stored, single-sample 2D RGBA8Unorm textures containing sRGB BYTE codes;
// alpha is ignored. No sRGB views, resampling, Y flip, BGRA or premultiplication.
// Dimensions must match and be <=3024x1964. Region threshold stays 256 even for
// smaller synthetic inputs (unlike PerceptualDiff.compare's area-scaled limit).
// Normal use is the full 3024x1964 panel; no pixel sampling or early gate exit.
//
// Submit producers on this queue before evaluate, or complete/synchronize other
// queues first. Inputs must remain unchanged until evaluate returns. It submits
// and waits for its OWN labeled command buffer. Keep evaluate OUTSIDE renderer
// timers and exclude its instrumentation*Ms from renderer cost. Construction
// compiles PSOs; coordinate all GPU verification with the parent/timing owner.
//
// PerceptualDiff.java definitions: CIEDE2000, 10001 floor(dE*100) bins capped at
// 100, nearest-rank p99.9, sRGB Rec709 luma, clamp-edge separable Gaussian 11/1.5,
// full-image mean SSIM, largest 8-connected component of dE>10. Metal has no
// double: dE/SSIM/reductions use float with fast math and implicit FMA disabled;
// this is definition-compatible, NOT bit-identical to Java double arithmetic.
// Thresholds have no tolerance/slack: mean<1, p999<3, ssim>=.99, region<256.
//
// Per-instance scratch is reused. At the panel size GPU buffers use <176 MiB;
// CPU flood stack <=23 MiB. No full color or dE readback: only byte error flags
// and a 20-byte result. Region scan consumes flags and returns the exact size.
// One evaluate at a time per instance; separate instances can use separate queues.
// Throws std::runtime_error on bad input, allocation, compilation or GPU failure.

#if !defined(__OBJC__) || !defined(__cplusplus) || !__has_feature(objc_arc)
#error "lod_cached_quality.h requires ObjC++ with ARC"
#endif
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <stdexcept>
#include <string>
#include <vector>

namespace lodcachedquality {
namespace detail {
inline constexpr uint32_t Width = 3024, Height = 1964, Bins = 10001, Shards = 64, Threads = 256;
struct Params { uint32_t width, height, count, groups; float weights[11]; };
struct Result { float mean, ssim; uint32_t p999bin, errors, count; };
static_assert(sizeof(Params)==60 && sizeof(Result)==20, "cached quality Metal ABI");
inline void need(bool condition, const std::string& message) {
    if (!condition) throw std::runtime_error("lodcachedquality: " + message);
}
inline std::string errorText(NSError* error) {
    return error ? std::string(error.localizedDescription.UTF8String) : "unknown Metal error";
}
using Clock = std::chrono::steady_clock;
inline double elapsedMs(Clock::time_point start) {
    return std::chrono::duration<double,std::milli>(Clock::now()-start).count();
}

// Destructively marks visited errors; GPU rewrites every flag next frame.
inline uint32_t largestRegion(uint8_t* flags, uint32_t width, uint32_t height,
                              std::vector<uint32_t>& stack, std::array<uint32_t,4>& box) {
    const uint32_t count = width*height;
    uint32_t best = 0, next = 0;
    while (next < count) {
        const auto* found = static_cast<const uint8_t*>(std::memchr(flags+next,1,count-next));
        if (!found) break;
        uint32_t seed = uint32_t(found-flags);
        next = seed+1; flags[seed] = 0; stack.clear(); stack.push_back(seed);
        uint32_t size = 0, x0 = width, y0 = height, x1 = 0, y1 = 0;
        while (!stack.empty()) {
            uint32_t at = stack.back(); stack.pop_back(); ++size;
            uint32_t x = at%width, y = at/width;
            x0 = std::min(x0,x); y0 = std::min(y0,y); x1 = std::max(x1,x); y1 = std::max(y1,y);
            for (uint32_t yy = y ? y-1 : 0; yy <= std::min(y+1,height-1); ++yy)
                for (uint32_t xx = x ? x-1 : 0; xx <= std::min(x+1,width-1); ++xx) {
                    uint32_t q = yy*width+xx;
                    if (flags[q]) { flags[q] = 0; stack.push_back(q); }
                }
        }
        if (size > best) { best = size; box = {x0,y0,x1,y1}; }
    }
    return best;
}
} // namespace detail

class Evaluator {
    __strong id<MTLDevice> device_;
    __strong id<MTLComputePipelineState> pixels_, horizontal_, vertical_, histogram_, finish_;
    __strong id<MTLBuffer> linear_, luma_, moments_, flags_, shards_, hist_, de_, ssim_, result_;
    detail::Params params_{};
    std::vector<uint32_t> stack_;
    uint64_t bufferBytes_ = 0;

    id<MTLBuffer> buffer(NSUInteger bytes, MTLResourceOptions options, NSString* label) {
        id<MTLBuffer> value = [device_ newBufferWithLength:bytes options:options];
        detail::need(value != nil, "allocation failed: " + std::string(label.UTF8String));
        value.label = label; return value;
    }
    void resize(uint32_t width, uint32_t height) {
        if (params_.width == width && params_.height == height) return;
        // Release old scratch before allocating a new size; never double the cap.
        luma_=nil; moments_=nil; flags_=nil; shards_=nil; hist_=nil; de_=nil; ssim_=nil; result_=nil;
        params_.width = params_.height = 0;
        const uint32_t count = width*height, groups = (count+detail::Threads-1)/detail::Threads;
        const MTLResourceOptions gpu = MTLResourceStorageModePrivate, cpu = MTLResourceStorageModeShared;
        luma_ = buffer(8ull*count,gpu,@"quality luma");
        moments_ = buffer(20ull*count,gpu,@"quality horizontal moments");
        flags_ = buffer(count,cpu,@"quality dE>10 flags");
        shards_ = buffer(4ull*detail::Bins*detail::Shards,gpu,@"quality histogram shards");
        hist_ = buffer(4ull*detail::Bins,gpu,@"quality histogram");
        de_ = buffer(8ull*groups,gpu,@"quality dE partials");
        ssim_ = buffer(4ull*groups,gpu,@"quality SSIM partials");
        result_ = buffer(sizeof(detail::Result),cpu,@"quality result");
        // Fixed panel maximum also bounds CPU memory across size changes.
        if (stack_.capacity() < count) {
            std::vector<uint32_t> fresh; fresh.reserve(count); stack_.swap(fresh);
        }
        bufferBytes_ = 29ull*count+4ull*detail::Bins*(detail::Shards+1)+12ull*groups+sizeof(detail::Result)+1024;
        detail::need(bufferBytes_ <= (176ull<<20), "scratch budget exceeded");
        params_.width=width; params_.height=height; params_.count=count; params_.groups=groups;
    }
    static void dispatch(id<MTLComputeCommandEncoder> encoder, id<MTLComputePipelineState> pipeline, uint32_t groups) {
        [encoder setComputePipelineState:pipeline];
        [encoder dispatchThreadgroups:MTLSizeMake(groups,1,1) threadsPerThreadgroup:MTLSizeMake(detail::Threads,1,1)];
    }
    void texture(id<MTLTexture> t) const {
        detail::need(t && t.device==device_ && t.textureType==MTLTextureType2D && t.sampleCount==1 &&
            t.pixelFormat==MTLPixelFormatRGBA8Unorm && t.storageMode!=MTLStorageModeMemoryless &&
            (t.usage==MTLTextureUsageUnknown || (t.usage&MTLTextureUsageShaderRead)),
            "expected shader-readable, stored 2D RGBA8Unorm texture on this device");
    }
public:
    Evaluator(id<MTLDevice> device, NSString* shaderPath) : device_(device) {
        detail::need(device && shaderPath.length, "expected device and shader source path");
        NSError* error = nil;
        NSString* source = [NSString stringWithContentsOfFile:shaderPath encoding:NSUTF8StringEncoding error:&error];
        detail::need(source != nil, "read shader: " + detail::errorText(error));
        MTLCompileOptions* options = [MTLCompileOptions new];
        options.fastMathEnabled = NO;
        id<MTLLibrary> library = [device newLibraryWithSource:source options:options error:&error];
        detail::need(library != nil, "compile shader: " + detail::errorText(error));
        auto pipeline = [&](NSString* name) -> id<MTLComputePipelineState> {
            id<MTLFunction> fn = [library newFunctionWithName:name];
            detail::need(fn != nil, "missing kernel " + std::string(name.UTF8String));
            id<MTLComputePipelineState> p = [device newComputePipelineStateWithFunction:fn error:&error];
            detail::need(p != nil, "create pipeline: " + detail::errorText(error));
            detail::need(p.threadExecutionWidth==32 && p.maxTotalThreadsPerThreadgroup>=256,
                         "kernels require 32-wide SIMD and 256-thread groups");
            return p;
        };
        pixels_=pipeline(@"cq_pixels"); horizontal_=pipeline(@"cq_horizontal"); vertical_=pipeline(@"cq_vertical");
        histogram_=pipeline(@"cq_histogram"); finish_=pipeline(@"cq_finish");
        // Match Java's float kernel construction/normalization and sRGB transfer.
        float total = 0;
        for (int j=0;j<11;j++) { params_.weights[j]=float(std::exp(-((j-5)*(j-5))/(2*1.5*1.5))); total+=params_.weights[j]; }
        for (float& k : params_.weights) k/=total;
        std::array<float,256> linear{};
        for (unsigned j=0;j<256;j++) {
            double v=j/255.0; linear[j]=float(v<=0.04045 ? v/12.92 : std::pow((v+0.055)/1.055,2.4));
        }
        linear_ = [device newBufferWithBytes:linear.data() length:sizeof(linear) options:MTLResourceStorageModeShared];
        detail::need(linear_ != nil, "allocate sRGB lookup");
    }
    Evaluator(id<MTLDevice> device, const char* shaderPath)
        : Evaluator(device, shaderPath ? [NSString stringWithUTF8String:shaderPath] : nil) {}
    Evaluator(const Evaluator&) = delete;
    Evaluator& operator=(const Evaluator&) = delete;

    NSDictionary* evaluate(id<MTLCommandQueue> queue, id<MTLTexture> candidate, id<MTLTexture> reference) {
        const auto start = detail::Clock::now();
        detail::need(queue && queue.device==device_, "queue device mismatch");
        texture(candidate); texture(reference);
        detail::need(candidate.width==reference.width && candidate.height==reference.height &&
            candidate.width>0 && candidate.height>0 && candidate.width<=detail::Width && candidate.height<=detail::Height,
            "expected matching dimensions bounded by 3024x1964");
        resize(uint32_t(candidate.width),uint32_t(candidate.height));
        id<MTLCommandBuffer> cb = [queue commandBuffer];
        detail::need(cb != nil, "create command buffer"); cb.label=@"Prompt7 quality instrumentation (exclude from renderer timing)";
        id<MTLBlitCommandEncoder> clear = [cb blitCommandEncoder];
        detail::need(clear != nil, "create clear encoder");
        [clear fillBuffer:shards_ range:NSMakeRange(0,shards_.length) value:0]; [clear endEncoding];
        // Separate encoders make producer/consumer dependencies explicit for tracked resources.
        auto encoder = [&]() -> id<MTLComputeCommandEncoder> {
            id<MTLComputeCommandEncoder> e=[cb computeCommandEncoder]; detail::need(e!=nil,"create compute encoder"); return e;
        };
        id<MTLComputeCommandEncoder> e=encoder();
        [e setTexture:candidate atIndex:0]; [e setTexture:reference atIndex:1];
        [e setBytes:&params_ length:sizeof(params_) atIndex:0]; [e setBuffer:linear_ offset:0 atIndex:1];
        [e setBuffer:luma_ offset:0 atIndex:2]; [e setBuffer:flags_ offset:0 atIndex:3];
        [e setBuffer:shards_ offset:0 atIndex:4]; [e setBuffer:de_ offset:0 atIndex:5];
        dispatch(e,pixels_,params_.groups); [e endEncoding];
        e=encoder(); [e setBuffer:luma_ offset:0 atIndex:0]; [e setBuffer:moments_ offset:0 atIndex:1];
        [e setBytes:&params_ length:sizeof(params_) atIndex:2]; dispatch(e,horizontal_,params_.groups); [e endEncoding];
        e=encoder(); [e setBuffer:moments_ offset:0 atIndex:0]; [e setBuffer:ssim_ offset:0 atIndex:1];
        [e setBytes:&params_ length:sizeof(params_) atIndex:2]; dispatch(e,vertical_,params_.groups); [e endEncoding];
        e=encoder(); [e setBuffer:shards_ offset:0 atIndex:0]; [e setBuffer:hist_ offset:0 atIndex:1];
        dispatch(e,histogram_,(detail::Bins+255)/256); [e endEncoding];
        e=encoder(); [e setBuffer:de_ offset:0 atIndex:0]; [e setBuffer:ssim_ offset:0 atIndex:1];
        [e setBuffer:hist_ offset:0 atIndex:2]; [e setBuffer:result_ offset:0 atIndex:3];
        [e setBytes:&params_ length:sizeof(params_) atIndex:4]; dispatch(e,finish_,1); [e endEncoding];
        [cb commit]; [cb waitUntilCompleted];
        detail::need(cb.status==MTLCommandBufferStatusCompleted,"GPU evaluation failed: "+detail::errorText(cb.error));
        detail::Result r; std::memcpy(&r,result_.contents,sizeof(r));
        detail::need(r.count==params_.count && r.errors<=r.count && r.p999bin<detail::Bins &&
            std::isfinite(r.mean) && r.mean>=0 && std::isfinite(r.ssim), "invalid GPU result");
        const auto regionStart=detail::Clock::now();
        std::array<uint32_t,4> box{};
        uint32_t region = r.errors ? detail::largestRegion(static_cast<uint8_t*>(flags_.contents),params_.width,params_.height,stack_,box) : 0;
        const double regionMs=detail::elapsedMs(regionStart), p999=r.p999bin/100.0;
        const bool meanOk=r.mean<1.0, p999Ok=p999<3.0, ssimOk=double(r.ssim)>=0.99, regionOk=region<256;
        NSString* where=region ? [NSString stringWithFormat:@"%u,%u %ux%u",box[0],box[1],box[2]-box[0]+1,box[3]-box[1]+1] : @"";
        return @{@"mean":@(r.mean), @"p999":@(p999), @"ssim":@(r.ssim), @"region":@(region), @"regionMax":@256,
            @"where":where, @"errorPixels":@(r.errors), @"width":@(params_.width), @"height":@(params_.height),
            @"meanOk":@(meanOk), @"p999Ok":@(p999Ok), @"ssimOk":@(ssimOk), @"regionOk":@(regionOk),
            @"ok":@(meanOk && p999Ok && ssimOk && regionOk), @"scratchBufferBytes":@(bufferBytes_),
            @"instrumentationGpuMs":@(std::max(0.0,cb.GPUEndTime-cb.GPUStartTime)*1000.0),
            @"instrumentationRegionCpuMs":@(regionMs), @"instrumentationWallMs":@(detail::elapsedMs(start))};
    }
};
} // namespace lodcachedquality
