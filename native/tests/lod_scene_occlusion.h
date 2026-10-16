#pragma once
// Offscreen diagnostic only. ObjC++17/ARC; caller owns the command buffer and
// exclusive timing-GPU lock (including construction, which compiles three PSOs).
//
// lodoccl::Query q(dev, scene, width, height, /*capturedCandidatesOnly=*/false);
// q.encode(cb, currentDepth, projection64, terrain80, globals48, cullView128);
// After cb completes: uint32_t* mask = (uint32_t*)q.groupHidden(ordinal).contents;
// 1 = definitely hidden; 0 = keep. Indices are ORIGINAL input-cluster ordinals.
// No compaction, history, frustum/facing rejection, queue, submit, wait, or timer.
// Depth must be current exact-source D32F, sampleCount=1, ShaderRead, stored by
// an ended encoder earlier in this CB (or otherwise explicitly synchronized).
// The caller measures the CB; CPU filtering/readback is not part of GPU cost.
// Resources are retained; original scene buffers/ordering are never modified.
// Captured mode freezes sorted unique (draw.quadBuffer[i] >> 6) at construction;
// recreate Query if that candidate membership changes. Output remains FULL size,
// with unused entries zeroed on every encode. Translucent groups are never queried.
// totalQueried()/groupQueried() count dispatched opaque candidates, excluding water.
// Do not overlap encode calls on different in-flight CBs: pyramid/masks are reused.

#include "lod_scene_data.h"

namespace lodoccl {
namespace detail {
inline void need(bool ok, const std::string& reason) {
    if (!ok) throw std::runtime_error("lodoccl: " + reason);
}
inline uint32_t power2(uint32_t n) { uint32_t p=1; while(p<n)p*=2; return p; }
struct alignas(16) Params {
    uint32_t width, height, sectionCount, clusterCount;
    int32_t cullBase[4];
    uint32_t layer, queryCount, capturedCandidatesOnly, unused;
};
static_assert(sizeof(Params)==48, "MSL query parameters");

inline NSString* source() {
    return @R"MSL(
#include <metal_stdlib>
using namespace metal;
#pragma clang fp reassociate(off)
struct Globals { packed_int3 CameraBlockPos; float GlintAlpha; packed_float3 CameraOffset; float GameTime;
                 float2 ScreenSize; int MenuBlurRadius; int UseRgss; };
struct Terrain { float4x4 ModelViewMat; int2 TextureSize; };
struct View { float4 planes[5]; float eps; uint flags; float margin; uint n; float4 camDelta,eyeDelta; };
struct Params { uint width,height,sectionCount,clusterCount; int4 cullBase; uint layer,queryCount,capturedCandidatesOnly,unused; };

kernel void lodoccl_copy(texture2d<float,access::read> src [[texture(0)]],
                         texture2d<float,access::write> dst [[texture(1)]], uint2 p [[thread_position_in_grid]]) {
    if(any(p>=uint2(dst.get_width(),dst.get_height())))return;
    float z=all(p<uint2(src.get_width(),src.get_height()))?src.read(p).x:0.0f;
    dst.write(float4(isfinite(z) && z>=0.0f && z<=1.0f ? z : 0.0f),p);
}
kernel void lodoccl_reduce(texture2d<float,access::read> src [[texture(0)]],
                           texture2d<float,access::write> dst [[texture(1)]], uint2 p [[thread_position_in_grid]]) {
    if(any(p>=uint2(dst.get_width(),dst.get_height())))return;
    float z=1.0f;
    for(uint y=0;y<2;y++)for(uint x=0;x<2;x++) {
        // Replicate a collapsed 1-pixel axis, so rectangular pyramids stay valid.
        uint2 q=min(p*2+uint2(x,y),uint2(src.get_width()-1,src.get_height()-1));
        float v=src.read(q).x;
        z=min(z,isfinite(v) && v>=0.0f && v<=1.0f ? v : 0.0f);
    }
    dst.write(float4(z),p);
}
bool lodoccl_hidden(float3 lo,float3 hi,float3 delta,constant float4x4& projection,
                    constant Terrain& terrain,constant Globals& globals,constant Params& p,
                    texture2d<float,access::read> depth) {
    float4x4 model=terrain.ModelViewMat;
    for(uint i=0;i<4;i++)if(any(!isfinite(projection[i])) || any(!isfinite(model[i])))return false;
    float4x4 matrix=projection*model;
    float4x4 ap=float4x4(abs(projection[0]),abs(projection[1]),abs(projection[2]),abs(projection[3]));
    float4x4 at=float4x4(abs(model[0]),abs(model[1]),abs(model[2]),abs(model[3]));
    float4x4 magnitude=ap*at;
    float2 low=float2(1e30f),high=float2(-1e30f);
    float nearest=0.0f;
    for(uint i=0;i<8;i++) {
        float3 local=select(lo,hi,bool3((i&1)!=0,(i&2)!=0,(i&4)!=0));
        // Same association as BOTH frozen BLOCK112 and compact48 vertex shaders.
        float3 pos=(local+delta)+float3(globals.CameraOffset);
        float4 c=fma(matrix[2],float4(pos.z),fma(matrix[1],float4(pos.y),fma(matrix[0],float4(pos.x),matrix[3])));
        // Cover matrix/product rounding differences vs the production fast-math VS.
        // Bounds are also expanded before projection; cancellation cannot erase this
        // absolute-product error estimate. Large/ill-conditioned cases keep.
        float4 error=(magnitude*float4(abs(pos),1.0f))*0x1p-17f+float4(0x1p-22f);
        if(any(!isfinite(c)) || any(!isfinite(error)) || any(error<0.0f))return false;
        float wl=c.w-error.w,wh=c.w+error.w;
        if(wl<=1e-4f || c.z-error.z<=0.0f || c.z+error.z>=wl)return false;
        c.y=-c.y; // production VS Y flip, then Metal viewport Y -> native texture rows
        float2 nl=min((c.xy-error.xy)/wl,(c.xy-error.xy)/wh);
        float2 nh=max((c.xy+error.xy)/wl,(c.xy+error.xy)/wh);
        low=min(low,float2(nl.x,-nh.y)*0.5f+0.5f);
        high=max(high,float2(nh.x,-nl.y)*0.5f+0.5f);
        nearest=max(nearest,(c.z+error.z)/wl);
    }
    if(any(!isfinite(low)) || any(!isfinite(high)) || !isfinite(nearest))return false;
    // Include pixel boundaries and raster snapping. Any edge/clipped query keeps.
    float2 dims=float2(p.width,p.height);
    float2 a=floor(low*dims)-1.0f,b=ceil(high*dims)+1.0f;
    if(any(a<0.0f) || any(b>=dims) || any(b<a))return false;
    uint2 ia=uint2(a),ib=uint2(b);uint mip=0;
    while(any(ib-ia>1) && mip+1<depth.get_num_mip_levels()) { ia/=2;ib/=2;mip++; }
    // A coarse cell may include extra pixels or padded clear depth: false keeps only.
    float farthest=1.0f;
    for(uint y=ia.y;y<=ib.y;y++)for(uint x=ia.x;x<=ib.x;x++)farthest=min(farthest,depth.read(uint2(x,y),mip).x);
    // GEQUAL: equality must stay visible. Extra slack covers D32/interpolation noise.
    float threshold=nearest+max(1e-7f,nearest*2e-4f);
    return threshold<farthest;
}
kernel void lodoccl_query(device const uint4* clusters [[buffer(0)]],device const float4* table [[buffer(1)]],
                          device const int4* infos [[buffer(2)]],constant float4x4& projection [[buffer(3)]],
                          constant Terrain& terrain [[buffer(4)]],constant Globals& globals [[buffer(5)]],
                          constant View& view [[buffer(6)]],constant Params& p [[buffer(7)]],
                          device uint* hidden [[buffer(8)]],device const uint* candidates [[buffer(9)]],
                          texture2d<float,access::read> depth [[texture(0)]],uint index [[thread_position_in_grid]]) {
    if(index>=p.queryCount)return;
    uint c=p.capturedCandidatesOnly?candidates[index]:index;
    if(c>=p.clusterCount)return;
    // Full masks, including inactive holes, were zeroed by the preceding blit.
    if(p.layer==2 || !(view.flags&4u) || !isfinite(view.margin) || view.margin<0.0f || view.margin>16.0f)return;
    if(any(!isfinite(float3(globals.CameraOffset))) || any(abs(float3(globals.CameraOffset))>1.0f) ||
       any(globals.ScreenSize!=float2(p.width,p.height)))return;
    uint4 it=clusters[c];uint slot=it.y&0xfffffu;
    if(slot>=p.sectionCount || (it.y&(1u<<29)) || (it.z>>30) || (it.w>>30))return;
    float4 origin=table[2*slot];int3 world=infos[slot].xyz;
    int3 fromBase=as_type<int3>(uint3(world)-uint3(p.cullBase.xyz));
    if(any(!isfinite(origin)) || origin.w<=0.0f || any(origin.xyz!=float3(fromBase)))return;
    // Table origin is RELATIVE to CullBase. Reconstructing absolute floats then
    // subtracting camera loses precision; the original VS uses integer subtraction.
    float3 delta=float3(as_type<int3>(uint3(world)-uint3(int3(globals.CameraBlockPos))));
    float3 lo=float3(int3(it.z&1023u,(it.z>>10)&1023u,(it.z>>20)&1023u)-512)*origin.w;
    float3 hi=float3(int3(it.w&1023u,(it.w>>10)&1023u,(it.w>>20)&1023u)-512)*origin.w;
    if(any(!isfinite(lo)) || any(!isfinite(hi)) || any(lo>hi) || any(abs(delta)>1048576.0f) ||
       any(max(abs(lo),abs(hi))>1048576.0f))return;
    float3 pad=view.margin+max(float3(0.0625f),(abs(delta)+max(abs(lo),abs(hi))+1.0f)*0x1p-18f);
    hidden[c]=lodoccl_hidden(lo-pad,hi+pad,delta,projection,terrain,globals,p,depth)?1u:0u;
}
)MSL";
}
} // namespace detail

class Query {
    struct Job {
        uint32_t count,layer,queryCount;
        __strong id<MTLBuffer> clusters,hidden,candidates;
    };
    __strong id<MTLDevice> device_;
    __strong id<MTLTexture> pyramid_;
    __strong NSMutableArray<id<MTLTexture>>* levels_;
    __strong id<MTLBuffer> table_,infos_;
    __strong id<MTLComputePipelineState> copy_,reduce_,query_;
    std::vector<Job> jobs_;
    detail::Params params_{};
    uint64_t allocated_=0,totalQueried_=0;
    static constexpr uint64_t MaxAdded=128ull<<20;
    void budget() const {
        detail::need(allocated_<=MaxAdded && device_.currentAllocatedSize<=lodscene::MaxMetalBytes,"GPU resource budget exceeded");
    }
    static void dispatch(id<MTLComputeCommandEncoder> e,id<MTLComputePipelineState> p,NSUInteger w,NSUInteger h) {
        [e setComputePipelineState:p];
        NSUInteger tw=std::min<NSUInteger>(16,p.threadExecutionWidth),th=std::min<NSUInteger>(8,p.maxTotalThreadsPerThreadgroup/tw);
        [e dispatchThreads:MTLSizeMake(w,h,1) threadsPerThreadgroup:MTLSizeMake(tw,th,1)];
    }
public:
    Query(id<MTLDevice> dev,const lodscene::Scene& scene,uint32_t width,uint32_t height,bool capturedCandidatesOnly=false):device_(dev),table_(scene.sectionTable),infos_(scene.sectionInfos) {
        detail::need(dev && scene.stats.validated,"expected validated scene/device");
        detail::need(width && height && width<=4096 && height<=2048,"unsupported dimensions");
        detail::need(table_ && infos_ && table_.device==dev && infos_.device==dev,"section buffer device");
        uint64_t slots=std::min(table_.length/32,infos_.length/16);
        detail::need(slots>0 && slots<=lodscene::MaxSections && scene.groups.size()<=lodscene::MaxGroups,"section/group cap");
        params_.width=width;params_.height=height;params_.sectionCount=(uint32_t)slots;
        params_.capturedCandidatesOnly=capturedCandidatesOnly;
        for(unsigned i=0;i<3;i++)params_.cullBase[i]=scene.cullBase[i];
        uint32_t w=detail::power2(width),h=detail::power2(height);
        uint64_t estimate=0;for(uint32_t x=w,y=h;;x=std::max(1u,x/2),y=std::max(1u,y/2)) { estimate+=4ull*x*y;if(x==1&&y==1)break; }
        for(const auto& g:scene.groups) {
            detail::need(g.ordinal==jobs_.size() && g.layer<=2 && g.clusterCount && g.clusters && g.clusters.device==dev &&
                g.clusters.length>=16ull*g.clusterCount,"input group ABI");
            estimate+=(4ull*g.clusterCount+16383)&~16383ull;
            jobs_.push_back(Job{g.clusterCount,g.layer,g.layer==2?0:g.clusterCount,g.clusters,nil,nil});
        }
        detail::need(estimate<=MaxAdded && dev.currentAllocatedSize<=lodscene::MaxMetalBytes-estimate,"insufficient GPU budget");
        // Bounded CPU scratch: one membership bit per input cluster, then sorted
        // unique original IDs. Never duplicate all captured quad entries in RAM.
        std::vector<std::vector<uint32_t>> candidates(jobs_.size());
        if(capturedCandidatesOnly) {
            for(const auto& draw:scene.draws)
                detail::need(draw.groupOrdinal<jobs_.size() && (!draw.quadCount || (draw.quadBuffer &&
                    draw.quadBuffer.device==dev && draw.quadBuffer.contents && draw.quadBuffer.length>=4ull*draw.quadCount)),"captured draw ABI");
            for(size_t ordinal=0;ordinal<jobs_.size();ordinal++) {
                auto& j=jobs_[ordinal];j.queryCount=0;if(j.layer==2)continue;
                std::vector<uint64_t> bits((uint64_t(j.count)+63)/64);
                for(const auto& draw:scene.draws)if(draw.groupOrdinal==ordinal) {
                    const auto* entries=static_cast<const uint32_t*>(draw.quadBuffer.contents);
                    for(uint32_t i=0;i<draw.quadCount;i++) {
                        uint32_t c=entries[i]>>6;detail::need(c<j.count,"captured cluster outside original group");
                        bits[c/64]|=1ull<<(c%64);
                    }
                }
                auto& ids=candidates[ordinal];
                for(uint32_t c=0;c<j.count;c++)if(bits[c/64]&(1ull<<(c%64)))ids.push_back(c);
                j.queryCount=(uint32_t)ids.size();
                if(j.queryCount)estimate+=(4ull*j.queryCount+16383)&~16383ull;
                detail::need(estimate<=MaxAdded,"candidate buffer budget exceeded");
            }
        }
        detail::need(dev.currentAllocatedSize<=lodscene::MaxMetalBytes-estimate,"insufficient candidate GPU budget");
        for(const auto& j:jobs_)totalQueried_+=j.queryCount;
        NSError* error=nil;MTLCompileOptions* options=[MTLCompileOptions new];
        options.languageVersion=MTLLanguageVersion3_2;options.mathMode=MTLMathModeSafe;
        id<MTLLibrary> library=[dev newLibraryWithSource:detail::source() options:options error:&error];
        detail::need(library!=nil,lodscene::detail::text(error.description));
        auto pipeline=[&](NSString* name) {
            id<MTLComputePipelineState> p=[dev newComputePipelineStateWithFunction:[library newFunctionWithName:name] error:&error];
            detail::need(p!=nil,lodscene::detail::text(error.description));return p;
        };
        copy_=pipeline(@"lodoccl_copy");reduce_=pipeline(@"lodoccl_reduce");query_=pipeline(@"lodoccl_query");
        MTLTextureDescriptor* td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatR32Float width:w height:h mipmapped:YES];
        td.storageMode=MTLStorageModePrivate;td.usage=MTLTextureUsageShaderRead|MTLTextureUsageShaderWrite|MTLTextureUsagePixelFormatView;
        pyramid_=[dev newTextureWithDescriptor:td];detail::need(pyramid_!=nil,"pyramid allocation");
        pyramid_.label=@"lodoccl padded reverse-Z minimum";allocated_+=pyramid_.allocatedSize;budget();
        levels_=[NSMutableArray array];
        for(NSUInteger i=0;i<pyramid_.mipmapLevelCount;i++) {
            id<MTLTexture> v=[pyramid_ newTextureViewWithPixelFormat:MTLPixelFormatR32Float textureType:MTLTextureType2D levels:NSMakeRange(i,1) slices:NSMakeRange(0,1)];
            detail::need(v!=nil,"pyramid mip view");[levels_ addObject:v];
        }
        for(size_t ordinal=0;ordinal<jobs_.size();ordinal++) {
            auto& j=jobs_[ordinal];
            j.hidden=[dev newBufferWithLength:4ull*j.count options:MTLResourceStorageModeShared];
            detail::need(j.hidden!=nil,"hidden mask allocation");memset(j.hidden.contents,0,j.hidden.length);
            allocated_+=j.hidden.allocatedSize;budget();
            if(capturedCandidatesOnly && j.queryCount) {
                j.candidates=[dev newBufferWithBytes:candidates[ordinal].data() length:4ull*j.queryCount options:MTLResourceStorageModeShared];
                detail::need(j.candidates!=nil,"candidate buffer allocation");allocated_+=j.candidates.allocatedSize;budget();
            }
        }
    }
    Query(const Query&)=delete;Query& operator=(const Query&)=delete;
    uint64_t allocatedBytes()const{return allocated_;}
    uint64_t totalQueried()const{return totalQueried_;}
    uint32_t pyramidWidth()const{return (uint32_t)pyramid_.width;}
    uint32_t pyramidHeight()const{return (uint32_t)pyramid_.height;}
    uint32_t groupClusterCount(size_t ordinal)const { detail::need(ordinal<jobs_.size(),"group ordinal");return jobs_[ordinal].count; }
    uint32_t groupQueried(size_t ordinal)const { detail::need(ordinal<jobs_.size(),"group ordinal");return jobs_[ordinal].queryCount; }
    id<MTLBuffer> groupHidden(size_t ordinal)const { detail::need(ordinal<jobs_.size(),"group ordinal");return jobs_[ordinal].hidden; }

    void encode(id<MTLCommandBuffer> cb,id<MTLTexture> depth,id<MTLBuffer> projection,id<MTLBuffer> terrain,
                id<MTLBuffer> globals,id<MTLBuffer> cullView) {
        detail::need(cb && cb.device==device_ && cb.status<MTLCommandBufferStatusCommitted,"uncommitted command buffer/device");
        detail::need(depth && depth.device==device_ && depth.width==params_.width && depth.height==params_.height &&
            depth.pixelFormat==MTLPixelFormatDepth32Float && depth.textureType==MTLTextureType2D && depth.sampleCount==1 &&
            (depth.usage&MTLTextureUsageShaderRead),"current D32F ShaderRead depth/dimensions");
        id<MTLBuffer> inputs[]={projection,terrain,globals,cullView};const NSUInteger sizes[]={64,80,48,128};
        for(unsigned i=0;i<4;i++)detail::need(inputs[i] && inputs[i].device==device_ && inputs[i].length>=sizes[i],"uniform buffer ABI");
        budget();
        id<MTLBlitCommandEncoder> clear=[cb blitCommandEncoder];detail::need(clear!=nil,"mask clear encoder");
        clear.label=@"lodoccl clear full original-cluster masks";
        for(const auto& j:jobs_)[clear fillBuffer:j.hidden range:NSMakeRange(0,j.hidden.length) value:0];
        [clear endEncoding];
        id<MTLComputeCommandEncoder> e=[cb computeCommandEncoder];detail::need(e!=nil,"compute encoder");
        e.label=@"lodoccl depth minimum pyramid and original-cluster masks";
        [e setTexture:depth atIndex:0];[e setTexture:levels_[0] atIndex:1];
        dispatch(e,copy_,pyramid_.width,pyramid_.height);[e memoryBarrierWithScope:MTLBarrierScopeTextures];
        for(NSUInteger i=1;i<levels_.count;i++) {
            [e setTexture:levels_[i-1] atIndex:0];[e setTexture:levels_[i] atIndex:1];
            dispatch(e,reduce_,[levels_[i] width],[levels_[i] height]);[e memoryBarrierWithScope:MTLBarrierScopeTextures];
        }
        [e setTexture:pyramid_ atIndex:0];[e setTexture:nil atIndex:1];
        [e setBuffer:table_ offset:0 atIndex:1];[e setBuffer:infos_ offset:0 atIndex:2];
        for(unsigned i=0;i<4;i++)[e setBuffer:inputs[i] offset:0 atIndex:3+i];
        [e setComputePipelineState:query_];
        for(const auto& j:jobs_) {
            if(!j.queryCount)continue;
            detail::Params p=params_;p.clusterCount=j.count;p.layer=j.layer;p.queryCount=j.queryCount;
            [e setBytes:&p length:sizeof(p) atIndex:7];[e setBuffer:j.clusters offset:0 atIndex:0];[e setBuffer:j.hidden offset:0 atIndex:8];
            [e setBuffer:j.candidates?j.candidates:j.clusters offset:0 atIndex:9];
            [e dispatchThreads:MTLSizeMake(j.queryCount,1,1) threadsPerThreadgroup:MTLSizeMake(std::min<NSUInteger>(128,query_.maxTotalThreadsPerThreadgroup),1,1)];
        }
        [e endEncoding];
    }
};
} // namespace lodoccl
