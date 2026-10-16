#include <metal_stdlib>
using namespace metal;
#pragma clang fp contract(off)
#pragma clang fp reassociate(off)

// Keep in sync with lod_cached_quality.h. Compile WITHOUT fast math.
constant uint CQBins = 10001;
constant uint CQShards = 64;
struct CQParams { uint width, height, count, groups; float weights[11]; };
struct CQMoments { float x, y, xx, yy, xy; }; // 20 bytes, not float3/float4 aligned
struct CQPartial { float de; uint errors; };
struct CQResult { float mean, ssim; uint p999bin, errors, count; };
float cq_degrees(float x) { return x * 57.29577951308232f; }
float cq_radians(float x) { return x * 0.017453292519943295f; }

float cq_lab_f(float t) {
    return t > 216.0f / 24389.0f ? pow(t, 1.0f / 3.0f)
        : (24389.0f / 27.0f * t + 16.0f) / 116.0f;
}
float3 cq_lab(float3 rgb, constant float* linear) {
    float r = linear[uint(rgb.r)], g = linear[uint(rgb.g)], b = linear[uint(rgb.b)];
    float x = (0.4124564f*r + 0.3575761f*g + 0.1804375f*b) / 0.95047f;
    float y = 0.2126729f*r + 0.7151522f*g + 0.0721750f*b;
    float z = (0.0193339f*r + 0.1191920f*g + 0.9503041f*b) / 1.08883f;
    float fx = cq_lab_f(x), fy = cq_lab_f(y), fz = cq_lab_f(z);
    return float3(116.0f*fy - 16.0f, 500.0f*(fx-fy), 200.0f*(fy-fz));
}
float cq_pow7(float c) { float c2 = c*c; return c2*c2*c2*c; }
float cq_de(float3 p, float3 q) {
    float c1 = length(p.yz), c2 = length(q.yz), cm7 = cq_pow7((c1+c2)*0.5f);
    float g = 0.5f*(1.0f-sqrt(cm7/(cm7+6103515625.0f)));
    float a1 = (1.0f+g)*p.y, a2 = (1.0f+g)*q.y;
    float cp1 = length(float2(a1,p.z)), cp2 = length(float2(a2,q.z));
    float h1 = cp1 == 0 ? 0 : cq_degrees(atan2(p.z,a1));
    float h2 = cp2 == 0 ? 0 : cq_degrees(atan2(q.z,a2));
    if (h1 < 0) h1 += 360;
    if (h2 < 0) h2 += 360;
    float dh = h2-h1;
    if (cp1*cp2 == 0) dh = 0;
    else if (dh > 180) dh -= 360;
    else if (dh < -180) dh += 360;
    float dH = 2.0f*sqrt(cp1*cp2)*sin(cq_radians(dh*0.5f));
    float lm = (p.x+q.x)*0.5f, cm = (cp1+cp2)*0.5f, hm;
    if (cp1*cp2 == 0) hm = h1+h2;
    else if (abs(h1-h2) <= 180) hm = (h1+h2)*0.5f;
    else if (h1+h2 < 360) hm = (h1+h2+360)*0.5f;
    else hm = (h1+h2-360)*0.5f;
    float t = 1.0f - 0.17f*cos(cq_radians(hm-30)) + 0.24f*cos(cq_radians(2*hm))
        + 0.32f*cos(cq_radians(3*hm+6)) - 0.20f*cos(cq_radians(4*hm-63));
    float theta = (hm-275)/25;
    float dt = 30*exp(-theta*theta), cm7p = cq_pow7(cm);
    float rc = 2*sqrt(cm7p/(cm7p+6103515625.0f));
    float lm50 = (lm-50)*(lm-50);
    float sl = 1 + 0.015f*lm50/sqrt(20+lm50), sc = 1+0.045f*cm, sh = 1+0.015f*cm*t;
    float rt = -sin(cq_radians(2*dt))*rc;
    float l = (q.x-p.x)/sl, c = (cp2-cp1)/sc, h = dH/sh;
    return sqrt(max(0.0f,l*l+c*c+h*h+rt*c*h));
}
float cq_luma(float3 c) { return 0.2126f*c.r + 0.7152f*c.g + 0.0722f*c.b; }

kernel void cq_pixels(texture2d<float,access::read> candidate [[texture(0)]],
                      texture2d<float,access::read> reference [[texture(1)]],
                      constant CQParams& p [[buffer(0)]], constant float* linear [[buffer(1)]],
                      device float2* luma [[buffer(2)]], device uchar* flags [[buffer(3)]],
                      device atomic_uint* hist [[buffer(4)]], device CQPartial* partial [[buffer(5)]],
                      uint i [[thread_position_in_grid]], uint tid [[thread_index_in_threadgroup]],
                      uint group [[threadgroup_position_in_grid]], uint lane [[thread_index_in_simdgroup]],
                      uint simd [[simdgroup_index_in_threadgroup]]) {
    threadgroup float sums[8]; threadgroup uint errors[8];
    float de = 0; uint bad = 0, bin = 0;
    if (i < p.count) {
        uint2 xy(i%p.width,i/p.width);
        // Recover the original byte codes; never accept an sRGB-decoding view.
        float3 a = round(candidate.read(xy).rgb*255.0f), b = round(reference.read(xy).rgb*255.0f);
        if (any(a != b)) de = cq_de(cq_lab(a,linear),cq_lab(b,linear));
        luma[i] = float2(cq_luma(a),cq_luma(b));
        bad = de > 10.0f; flags[i] = uchar(bad);
        bin = min(10000u,uint(de*100.0f)); // Java casts float dE*100 toward zero.
    }
    // Common uniform/identical regions issue one atomic per SIMD group. Shards
    // avoid every workgroup contending on the same histogram, even for bin zero.
    uint valid = simd_sum(uint(i < p.count));
    uint low = simd_min(bin), high = simd_max(bin);
    uint offset = (group%CQShards)*CQBins;
    if (low == high) {
        if (lane == 0) atomic_fetch_add_explicit(hist+offset+bin,valid,memory_order_relaxed);
    } else if (i < p.count) atomic_fetch_add_explicit(hist+offset+bin,1u,memory_order_relaxed);
    float s = simd_sum(de); uint e = simd_sum(bad);
    if (lane == 0) { sums[simd] = s; errors[simd] = e; }
    threadgroup_barrier(mem_flags::mem_threadgroup);
    if (tid == 0) {
        CQPartial out = {0,0};
        for (uint j=0;j<8;j++) { out.de += sums[j]; out.errors += errors[j]; }
        partial[group] = out;
    }
}

kernel void cq_horizontal(device const float2* luma [[buffer(0)]],
                          device CQMoments* moments [[buffer(1)]], constant CQParams& p [[buffer(2)]],
                          uint i [[thread_position_in_grid]]) {
    if (i >= p.count) return;
    int x = int(i%p.width); uint row = (i/p.width)*p.width;
    CQMoments m = {0,0,0,0,0};
    for (int j=-5;j<=5;j++) {
        float2 v = luma[row+uint(clamp(x+j,0,int(p.width)-1))];
        float k = p.weights[j+5];
        m.x += k*v.x; m.y += k*v.y;
        m.xx += k*(v.x*v.x); m.yy += k*(v.y*v.y); m.xy += k*(v.x*v.y);
    }
    moments[i] = m;
}

kernel void cq_vertical(device const CQMoments* moments [[buffer(0)]], device float* partial [[buffer(1)]],
                        constant CQParams& p [[buffer(2)]], uint i [[thread_position_in_grid]],
                        uint tid [[thread_index_in_threadgroup]], uint group [[threadgroup_position_in_grid]],
                        uint lane [[thread_index_in_simdgroup]], uint simd [[simdgroup_index_in_threadgroup]]) {
    threadgroup float sums[8];
    float ssim = 0;
    if (i < p.count) {
        uint x = i%p.width; int y = int(i/p.width);
        CQMoments m = {0,0,0,0,0};
        for (int j=-5;j<=5;j++) {
            CQMoments v = moments[uint(clamp(y+j,0,int(p.height)-1))*p.width+x];
            float k = p.weights[j+5];
            m.x += k*v.x; m.y += k*v.y; m.xx += k*v.xx; m.yy += k*v.yy; m.xy += k*v.xy;
        }
        // Java promotes blurred floats to double before subtracting products.
        // Explicit FMA avoids rounding the product before this cancellation.
        float vx = fma(-m.x,m.x,m.xx), vy = fma(-m.y,m.y,m.yy), cov = fma(-m.x,m.y,m.xy);
        float c1 = 6.5025f, c2 = 58.5225f;
        ssim = ((2*m.x*m.y+c1)*(2*cov+c2))/((m.x*m.x+m.y*m.y+c1)*(vx+vy+c2));
    }
    float s = simd_sum(ssim);
    if (lane == 0) sums[simd] = s;
    threadgroup_barrier(mem_flags::mem_threadgroup);
    if (tid == 0) { float total = 0; for (uint j=0;j<8;j++) total += sums[j]; partial[group] = total; }
}

kernel void cq_histogram(device const uint* shards [[buffer(0)]], device uint* hist [[buffer(1)]],
                         uint bin [[thread_position_in_grid]]) {
    if (bin >= CQBins) return;
    uint total = 0;
    for (uint j=0;j<CQShards;j++) total += shards[j*CQBins+bin];
    hist[bin] = total;
}

kernel void cq_finish(device const CQPartial* de [[buffer(0)]], device const float* ssim [[buffer(1)]],
                      device const uint* hist [[buffer(2)]], device CQResult* result [[buffer(3)]],
                      constant CQParams& p [[buffer(4)]], uint tid [[thread_index_in_threadgroup]],
                      uint lane [[thread_index_in_simdgroup]], uint simd [[simdgroup_index_in_threadgroup]]) {
    threadgroup float2 sums[8]; threadgroup uint errors[8];
    // Pairwise GPU reduction, never a multi-million-element serial float sum.
    float2 sum(0); uint bad = 0;
    for (uint i=tid;i<p.groups;i+=256) { sum += float2(de[i].de,ssim[i]); bad += de[i].errors; }
    sum = simd_sum(sum); bad = simd_sum(bad);
    if (lane == 0) { sums[simd] = sum; errors[simd] = bad; }
    threadgroup_barrier(mem_flags::mem_threadgroup);
    if (tid == 0) {
        sum = float2(0); bad = 0;
        for (uint j=0;j<8;j++) { sum += sums[j]; bad += errors[j]; }
        // Exact integer ceil(.999*N) for the bounded image size.
        uint rank = p.count-p.count/1000u, total = 0, percentile = 10000u;
        for (uint j=0;j<CQBins;j++) {
            uint prev = total; total += hist[j];
            if (prev < rank && total >= rank) percentile = j;
        }
        CQResult out = {sum.x/p.count,sum.y/p.count,percentile,bad,total};
        result[0] = out;
    }
}
