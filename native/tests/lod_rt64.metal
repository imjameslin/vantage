#include <metal_stdlib>
using namespace metal;

// Offscreen Prompt 6 kill test only. No production shaders or renderer hooks.
// Mirroring makes all ray components positive in child-index space. Every cell
// a ray crosses then has an increasing x+4y+16z index. ctz visits only occupied
// children in that order; there is no scan through empty voxel cells.
struct Node { ulong mask; uint children; uint material; };
struct Params {
    float4 eye, forward, right, up, root, low, high;
    uint4 dims; // width,height,nodeCount,flags: tile1,sky2,coherent4,padded8,stats16
    float4 lens; // focal length(px), tau, depth scale, depth bias
    uint4 extra; // coarse boxes, node stride(uint4), near-only flag, reserved
};
struct Box { float4 low, high; };
struct Trace { float t; uint material,normal,steps,overflow; };

inline Node nodeAt(device const uint4 *data, uint i, constant Params &p) {
    uint4 v=data[i*p.extra.y];
    return Node{ulong(v.x)|(ulong(v.y)<<32),v.z,v.w};
}
inline uint materialAt(uint i,device const ushort*a,device const ushort*b,device const ushort*c) {
    uint offset=i&0x1fffffffu;
    return i<(1u<<29)?a[offset]:(i<(2u<<29)?b[offset]:c[offset]);
}
inline ulong mirrorMask(ulong m,uint flip) {
    if(flip&1) m=((m&0x5555555555555555ul)<<1)|((m>>1)&0x5555555555555555ul);
    if(flip&2) m=((m&0x3333333333333333ul)<<2)|((m>>2)&0x3333333333333333ul);
    if(flip&4) m=((m&0x0f0f0f0f0f0f0f0ful)<<4)|((m>>4)&0x0f0f0f0f0f0f0f0ful);
    if(flip&8) m=((m&0x00ff00ff00ff00fful)<<8)|((m>>8)&0x00ff00ff00ff00fful);
    if(flip&16)m=((m&0x0000ffff0000fffful)<<16)|((m>>16)&0x0000ffff0000fffful);
    if(flip&32)m=(m<<32)|(m>>32);
    return m;
}
inline bool intersect(float3 o,float3 d,float3 lo,float3 hi,float start,float end,
                      thread float &enter,thread float &leave,thread uint &normal) {
    // Explicit parallel slabs avoid zero*infinity NaNs on voxel boundaries.
    float3 a,b;
    for(uint k=0;k<3;k++) {
        if(abs(d[k])<1e-20f) {
            if(o[k]<lo[k]||o[k]>=hi[k]) return false;
            a[k]=-INFINITY;b[k]=INFINITY;
        } else {float v=(lo[k]-o[k])/d[k],w=(hi[k]-o[k])/d[k];a[k]=min(v,w);b[k]=max(v,w);}
    }
    uint axis=a.x>=a.y?(a.x>=a.z?0u:2u):(a.y>=a.z?1u:2u);
    enter=max(start,a[axis]);leave=min(end,min(b.x,min(b.y,b.z)));
    normal=axis*2u+uint(d[axis]<0);
    return enter<leave;
}
inline ulong candidates(Node n,float4 box,float3 o,float3 d,float begin,float end,uint flip) {
    // Trim the occupied-bit set to the ray segment's axis-aligned cell range.
    float cs=box.w*.25f;
    float3 a=(o+d*begin-box.xyz)/cs,b=(o+d*end-box.xyz)/cs;
    uint3 lo=uint3(clamp(floor(min(a,b)-1e-4f),0.f,3.f));
    uint3 hi=uint3(clamp(floor(max(a,b)+1e-4f),0.f,3.f));
    ulong x=0,y=0,z=0;
    for(uint k=0;k<4;k++) {
        if(k>=lo.x&&k<=hi.x)x|=0x1111111111111111ul<<k;
        if(k>=lo.y&&k<=hi.y)y|=0x000f000f000f000ful<<(k*4);
        if(k>=lo.z&&k<=hi.z)z|=0xfffful<<(k*16);
    }
    return mirrorMask(n.mask&x&y&z,flip);
}
inline Trace trace64(device const uint4 *data,constant Params &p,float3 d,float start,float end,
                     device const ushort*mat0,device const ushort*mat1,device const ushort*mat2) {
    Trace hit={0,0,0,0,0};float a,b;uint normal;
    float3 o=p.eye.xyz;
    if(p.dims.w&2u)if(!intersect(o,d,p.low.xyz,p.high.xyz,start,end,a,b,normal))return hit;
    if(!intersect(o,d,p.root.xyz,p.root.xyz+p.root.w,start,end,a,b,normal))return hit;
    uint flip=(d.x<0?3u:0u)|(d.y<0?12u:0u)|(d.z<0?48u:0u);
    ulong masks[8];uint nodes[8];float4 box=p.root;int depth=0;Node root=nodeAt(data,0,p);
    if(!root.mask) {if(root.material){hit.t=a;hit.material=root.material;hit.normal=normal;}return hit;}
    masks[0]=candidates(root,p.root,o,d,a,b,flip);nodes[0]=0;
    // Safety exhaustion is counted and makes the test invalid, never a sky hit.
    for(uint iter=0;iter<16384;iter++) {
        if(depth<0)return hit;
        if(!masks[depth]) {
            depth--;float size=box.w*4.f;
            box=float4(p.root.xyz+floor((box.xyz-p.root.xyz)/size)*size,size);
            continue;
        }
        uint mirrored=uint(ctz(masks[depth]));masks[depth]&=masks[depth]-1;
        uint cell=mirrored^flip;Node parent=nodeAt(data,nodes[depth],p);
        bool leaf=parent.children&0x80000000u;
        uint index=(parent.children&0x7fffffffu)+uint(popcount(parent.mask&((cell==0)?0ul:((1ul<<cell)-1ul))));
        if(index>=(leaf?p.extra.w:p.dims.z)){hit.overflow=1;return hit;}
        float size=box.w*.25f;
        float3 low=box.xyz+float3(cell&3u,(cell>>2)&3u,cell>>4)*size;
        hit.steps++;
        if(!intersect(o,d,low,low+size,start,end,a,b,normal))continue;
        if(leaf) {hit.t=a;hit.normal=normal;hit.material=materialAt(index,mat0,mat1,mat2);return hit;}
        Node n=nodeAt(data,index,p);
        // Representative material is enabled only when audited source parents
        // exist. tau=0 is a finer-detail control, not a claimed optimized pass.
        bool footprint=n.material && p.lens.y>0 && size*p.lens.x<=max(a,1e-3f)*p.lens.y;
        if(!n.mask||footprint) {
            if(n.material){hit.t=a;hit.material=n.material;hit.normal=normal;return hit;}
            continue;
        }
        if(depth==7){hit.overflow=1;return hit;}
        box=float4(low,size);++depth;nodes[depth]=index;masks[depth]=candidates(n,box,o,d,a,b,flip);
    }
    hit.overflow=1;return hit;
}
inline float3 ray(constant Params&p,float2 pixel) {
    float2 uv=(pixel-float2(p.dims.xy)*.5f)/p.lens.x;
    return p.forward.xyz+uv.x*p.right.xyz-uv.y*p.up.xyz;
}

// One conservative cone ray per8x8 tile against occupied coarse tree boxes.
// Expand each box by the maximum ray divergence at its farthest corner. The
// earliest expanded intersection is a lower bound for EVERY ray in the tile.
kernel void tiles(constant Params&p [[buffer(0)]],device const uint4*data [[buffer(1)]],
                  device float*starts [[buffer(2)]],uint2 tile [[thread_position_in_grid]]) {
    uint2 count=(p.dims.xy+7u)/8u;if(any(tile>=count))return;
    float2 center=min(float2(tile*8u)+4.f,float2(p.dims.xy)-.5f);
    float3 d=ray(p,center);float earliest=INFINITY;
    float cone=5.657f/p.lens.x;
    // Traverse just the top three occupied levels. Expanded sibling bounds can
    // overlap, so never trust the first coarse hit: retain the minimum and prune
    // only boxes whose conservative entry is already beyond that bound.
    ulong masks[4];uint nodes[4];int depth=0;float4 box=p.root;
    Node root=nodeAt(data,0,p);masks[0]=root.mask;nodes[0]=0;
    if(!root.mask&&root.material)earliest=0;
    while(depth>=0) {
        if(!masks[depth]) {depth--;float size=box.w*4.f;
            box=float4(p.root.xyz+floor((box.xyz-p.root.xyz)/size)*size,size);continue;}
        uint cell=uint(ctz(masks[depth]));masks[depth]&=masks[depth]-1;
        Node parent=nodeAt(data,nodes[depth],p);
        bool leaf=parent.children&0x80000000u;
        uint index=(parent.children&0x7fffffffu)+uint(popcount(parent.mask&((cell==0)?0ul:((1ul<<cell)-1ul))));
        float size=box.w*.25f;float3 low=box.xyz+float3(cell&3u,(cell>>2)&3u,cell>>4)*size;
        float3 extent=max(abs(low-p.eye.xyz),abs(low+size-p.eye.xyz));
        float expand=length(extent)*cone+.002f;float a,b;uint normal;
        if(!intersect(p.eye.xyz,d,low-expand,low+size+expand,0,earliest,a,b,normal))continue;
        if(leaf){earliest=min(earliest,a);continue;}
        Node child=nodeAt(data,index,p);
        if(depth==2||!child.mask){earliest=min(earliest,a);continue;}
        ++depth;box=float4(low,size);nodes[depth]=index;masks[depth]=child.mask;
    }
    starts[tile.y*count.x+tile.x]=max(0.f,earliest-.002f);
}
kernel void cast(constant Params&p [[buffer(0)]],device const uint4*nodes [[buffer(1)]],
                 device const float*starts [[buffer(2)]],device const uchar*nearMask [[buffer(3)]],
                 device uint2*hits [[buffer(4)]],device uint2*stats [[buffer(5)]],
                 device const ushort*mat0 [[buffer(6)]],device const ushort*mat1 [[buffer(7)]],device const ushort*mat2 [[buffer(8)]],
                 uint2 group [[threadgroup_position_in_grid]],uint lane [[thread_index_in_threadgroup]]) {
    uint2 pixel;
    if(p.dims.w&4u) { // Morton order within an8x8screen tile.
        uint x=(lane&1u)|((lane>>1)&2u)|((lane>>2)&4u);
        uint y=((lane>>1)&1u)|((lane>>2)&2u)|((lane>>3)&4u);
        pixel=group*8u+uint2(x,y);
    } else pixel=uint2(group.x*64u+lane,group.y);
    if(any(pixel>=p.dims.xy))return;
    uint pos=pixel.y*p.dims.x+pixel.x;Trace h={0,0,0,0,0};
    float start=(p.dims.w&1u)?starts[(pixel.y/8u)*((p.dims.x+7u)/8u)+pixel.x/8u]:0;
    if(!nearMask[pos] && isfinite(start))h=trace64(nodes,p,ray(p,float2(pixel)+.5f),start,1e8f,mat0,mat1,mat2);
    // Hit position is losslessly derivable from the same axial distance and
    // camera ray. Compact output avoids an unnecessary float3 per pixel.
    float z=h.material?p.lens.w+p.lens.z/h.t:0.f;
    hits[pos]=uint2(as_type<uint>(z),h.material|(h.normal<<29));
    if(p.dims.w&16u)stats[pos]=uint2(h.steps,h.overflow);
}

// Read-only voxel coverage proxy for near terrain, generated outside timings.
// The capture's final GUI-cleared depth cannot be passed off as near depth.
kernel void nearCoverage(constant Params&p [[buffer(0)]],device const uint4*nodes [[buffer(1)]],
                         device uchar*mask [[buffer(2)]],device const ushort*mat0 [[buffer(6)]],
                         device const ushort*mat1 [[buffer(7)]],device const ushort*mat2 [[buffer(8)]],uint2 pixel [[thread_position_in_grid]]) {
    if(any(pixel>=p.dims.xy))return;
    float3 d=ray(p,float2(pixel)+.5f);float maxT=192.f/max(abs(d.x),abs(d.z));
    Trace h=trace64(nodes,p,d,0,maxT,mat0,mat1,mat2);
    mask[pixel.y*p.dims.x+pixel.x]=h.material!=0;
}
