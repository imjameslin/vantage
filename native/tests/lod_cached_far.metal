#include <metal_stdlib>
using namespace metal;
struct CachedView { float4 eye,right,up,forward,lens; uint4 dims; };
struct CubeAddress { uint face; float2 uv; };
inline float3 cf(uint f){return f==0?float3(1,0,0):f==1?float3(-1,0,0):f==2?float3(0,1,0):f==3?float3(0,-1,0):f==4?float3(0,0,1):float3(0,0,-1);}
inline float3 cu(uint f){return f==2?float3(0,0,-1):f==3?float3(0,0,1):float3(0,1,0);}
inline float3 cr(uint f){return cross(cf(f),cu(f));}
inline CubeAddress addr(float3 d) {
    float3 a=abs(d); uint f=a.x>=a.y&&a.x>=a.z?(d.x>=0?0:1):a.y>=a.z?(d.y>=0?2:3):(d.z>=0?4:5);
    float z=max(dot(d,cf(f)),1.e-8f);return {f,clamp(float2(dot(d,cr(f)),dot(d,cu(f)))/z*.5f+.5f,0.f,0.999999f)};
}
kernel void cachedWarp(texture2d_array<float,access::sample> colors [[texture(0)]],
                       depth2d_array<float,access::sample> depths [[texture(1)]],
                       texture2d_array<uint,access::read> ids [[texture(2)]],
                       texture2d<float,access::write> out [[texture(3)]],
                       texture2d<float,access::write> outDepth [[texture(4)]],
                       constant CachedView &v [[buffer(0)]], device const float4 *eyes [[buffer(1)]],
                       device atomic_uint *stats [[buffer(2)]],uint2 xy [[thread_position_in_grid]]) {
    if(any(xy>=v.dims.xy))return;
    float2 screen=(float2(xy)+.5f-float2(v.dims.xy)*.5f)/v.lens.x;
    float3 ray=v.forward.xyz+screen.x*v.right.xyz+screen.y*v.up.xyz;
    float3 direction=ray,world=0; CubeAddress a; uint tile=0,id=0; float z=0; float4 tileEye;
    constexpr sampler nearest(coord::normalized,filter::nearest,address::clamp_to_edge);
    constexpr sampler linear(coord::normalized,filter::linear,address::clamp_to_edge);
    uint side=v.dims.z/v.dims.w;
    // Backward fixed-point depth warp. Same-eye rotation needs one lookup.
    for(uint iter=0;iter<4;iter++) {
        a=addr(direction);uint2 p=uint2(a.uv*v.dims.z);tile=a.face*side*side+(p.y/v.dims.w)*side+p.x/v.dims.w;
        tileEye=eyes[tile];z=depths.sample(nearest,a.uv,a.face);id=ids.read(p,a.face).x;
        if(z<=0)break;
        float dist=v.lens.z/(z+v.lens.w);
        world=tileEye.xyz+(cf(a.face)+(2*a.uv.x-1)*cr(a.face)+(2*a.uv.y-1)*cu(a.face))*dist;
        if(distance_squared(tileEye.xyz,v.eye.xyz)<1.e-12f)break;
        float t=dot(world-v.eye.xyz,cf(a.face))/max(1.e-6f,dot(ray,cf(a.face)));
        direction=v.eye.xyz+ray*t-tileEye.xyz;
    }
    float error=0;bool hole=false;float outputZ=0;
    if(z>0) {
        float3 p=world-v.eye.xyz;float d=dot(p,v.forward.xyz);
        float2 actual=float2(dot(p,v.right.xyz),dot(p,v.up.xyz))/max(d,1.e-6f)*v.lens.x;
        error=length(actual-screen*v.lens.x);hole=d<=0||error>1.25f||id==0;
        outputZ=d>0?v.lens.z/d-v.lens.w:0;
        // Discontinuous source depth is uncertain during translation.
        if(distance_squared(tileEye.xyz,v.eye.xyz)>1.e-8f) {
            float zn=depths.sample(nearest,a.uv+float2(1.f/v.dims.z,0),a.face);
            hole|=abs(zn-z)>max(.000001f,z*.03f);
        }
    }
    out.write(colors.sample(linear,a.uv,a.face),xy);outDepth.write(float4(outputZ),xy);
    if(hole)atomic_fetch_add_explicit(stats+tile*4,1,memory_order_relaxed);
    // One visibility/age sample per 8x8 area. Hole count is exact per pixel.
    if(((xy.x|xy.y)&7)==0) {
        atomic_fetch_add_explicit(stats+tile*4+1,64,memory_order_relaxed);
        atomic_fetch_max_explicit(stats+tile*4+2,as_type<uint>(max(0.f,error)),memory_order_relaxed);
    }
}
// Exact same-eye rotation has no disocclusion, search, ID fetch or hole atomics.
// This is a measured lower-cost specialization, not a translation substitute.
kernel void cachedRotation(texture2d_array<float,access::sample> colors [[texture(0)]],
                       depth2d_array<float,access::sample> depths [[texture(1)]],
                       texture2d<float,access::write> out [[texture(3)]],
                       texture2d<float,access::write> outDepth [[texture(4)]],
                       constant CachedView &v [[buffer(0)]],uint2 xy [[thread_position_in_grid]]) {
    if(any(xy>=v.dims.xy))return;
    float2 screen=(float2(xy)+.5f-float2(v.dims.xy)*.5f)/v.lens.x;
    float3 ray=v.forward.xyz+screen.x*v.right.xyz+screen.y*v.up.xyz;
    CubeAddress a=addr(ray);
    constexpr sampler linear(coord::normalized,filter::linear,address::clamp_to_edge);
    constexpr sampler nearest(coord::normalized,filter::nearest,address::clamp_to_edge);
    float z=depths.sample(nearest,a.uv,a.face);
    out.write(colors.sample(linear,a.uv,a.face),xy);
    outDepth.write(float4(z>0?(z+v.lens.w)*dot(ray,cf(a.face))-v.lens.w:0),xy);
}
