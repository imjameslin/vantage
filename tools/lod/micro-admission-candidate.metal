// Isolated step-2 candidate. NOT included by the runtime or diagnostic shaders.
// No bindings, vertex evaluation, key writes, depth interpolation or draw changes.
// Call before either triangle writes, after the original address/clip validation.
//
// CONDITIONAL COVERAGE CONTRACT, not a certification of native rasterization:
//  * xy is the original face's binary32 screen projection; bound[k] bounds the
//    absolute native-minus-compute PRE-SNAP error independently in x and y.
//  * Both paths round to the nearest 1/256 pixel. Either adjacent grid point is
//    allowed at an exact midpoint. Native sample centers are (x+.5,y+.5), with
//    clockwise fronts, back-face culling and the existing micro_topLeft rule.
//  * clipSafe means finite positive W and wholly inside the clip volume for the
//    native face too: no clipping, MSAA, sample jitter, viewport/scissor change,
//    conservative raster, depth bias or shader coverage modification.
//  * Endpoint arithmetic uses finite binary32 round-to-nearest operations;
//    adjacent-float expansion rounds the coordinate intervals outward. No
//    floating edge tolerance is used: all snapped coverage tests are integers.
// The empirical bound below copies the old guard's allowance, NOT a proved
// bound on native shader/viewport arithmetic. Supply a justified larger bound
// or keep hardware routing if this contract is not established. Oversized
// bounds/work domains fail to hardware. Viewport units here are native pixels.
//
// EMPTY proves no covered sample under that contract, including all possible
// snaps, and needs its own counter. COMPUTE proves per-TRIANGLE sample coverage
// invariant and rejects potentially covered slivers/orientation changes. It
// does NOT prove exact D32, interpolated attributes or winner order across
// different depths. Existing sloped-depth discrepancies remain unresolved.
// Even stable coverage with multiple possible snaps can change the depth plane.
// Integration must retain native depth/repair wherever exact depth is required.
// The caller may use triangleMask with its nominal snapped interpolation, but
// must not treat this helper as authorization for exact-depth compute output.
//
// Result counters partition inputs: empty + compute + hardware == input.
// Reason counters overlap. Optional record() is diagnostic only: reduce per
// threadgroup before global accumulation if its atomics would affect timing.
// No actual admission-rate or GPU-cost claim accompanies this CPU-only file.

#include <metal_stdlib>
using namespace metal;
#pragma clang fp reassociate(off)

namespace micro_admission {
enum Route : uint { HARDWARE = 0, EMPTY = 1, COMPUTE = 2 };
enum Reason : uint {
    INPUT = 1u << 0, CLIP = 1u << 1, SIZE = 1u << 2,
    BOUND_BUDGET = 1u << 3, COVERAGE = 1u << 4,
    ORIENTATION = 1u << 5, SLIVER = 1u << 6
};
struct Result {
    uint route, reasons, snapVariantVertices;
    int2 first; uint2 extent;
    // Bit (y-first.y)*extent.x+(x-first.x); at most 4x4 pixels per triangle.
    uint triangleMask[2]; // Consume ONLY on COMPUTE. EMPTY has two zero masks.
};
struct Box { int2 lo, hi; };
struct AreaRange { int lo, hi; uint maxEdgeSquared; };
struct EdgeClass { bool allPass, allFail; };
struct Counters {
    atomic_uint inputQuads, emptyQuads, computeQuads, hardwareQuads;
    atomic_uint snapVariantQuads, input, clip, size, boundBudget;
    atomic_uint coverage, orientation, sliver;
};

bool finite(float x) { return (as_type<uint>(x) & 0x7f800000u) != 0x7f800000u; }
bool finite(float2 x) { return all((as_type<uint2>(x) & 0x7f800000u) != 0x7f800000u); }
float down(float x) {
    uint b = as_type<uint>(x);
    if ((b & 0x7fffffffu) == 0) return as_type<float>(0x80000001u);
    return as_type<float>((b & 0x80000000u) ? b + 1u : b - 1u);
}
float up(float x) {
    uint b = as_type<uint>(x);
    if ((b & 0x7fffffffu) == 0) return as_type<float>(1u);
    return as_type<float>((b & 0x80000000u) ? b - 1u : b + 1u);
}
float2 empirical_bound(float2 xy) {
    return max(abs(xy) * 0.00000011920928955078125f, float2(1.0f / 8192.0f));
}
Box snapped_box(float2 xy, float2 bound) {
    Box b;
    for (uint axis = 0; axis < 2; ++axis) {
        float s = xy[axis] * 256.0f, r = bound[axis] * 256.0f;
        // n can be a nearest integer iff [s-r,s+r] intersects [n-.5,n+.5].
        // Inclusive endpoints deliberately retain BOTH choices at exact ties.
        b.lo[axis] = int(ceil(down(down(s - r) - 0.5f)));
        b.hi[axis] = int(floor(up(up(s + r) + 0.5f)));
    }
    return b;
}
uint corners(Box b) { return (b.lo.x == b.hi.x ? 1u : 2u) * (b.lo.y == b.hi.y ? 1u : 2u); }
int2 corner(Box b, uint n) {
    uint nx = b.lo.x == b.hi.x ? 1u : 2u;
    return int2(n % nx ? b.hi.x : b.lo.x, n / nx ? b.hi.y : b.lo.y);
}
int edge(int2 a, int2 b, int2 p) {
    int2 e = b - a, q = p - a;
    return e.x * q.y - e.y * q.x;
}
bool top_left(int2 a, int2 b) {
    int2 e = b - a;
    return e.y < 0 || (e.y == 0 && e.x > 0);
}
uint length_squared(int2 d) { return uint(d.x * d.x + d.y * d.y); }
uint max_edge_squared(Box a, Box b) {
    // Independent endpoint coordinates attain both farthest axis distances at
    // one box-corner pair. Differences remain within candidate's 1024 bound.
    return length_squared(max(b.hi-a.lo, a.hi-b.lo));
}
AreaRange area_range(Box a, Box b, Box c) {
    AreaRange r = {2147483647, -2147483647,
        max(max_edge_squared(a,b), max(max_edge_squared(b,c), max_edge_squared(c,a)))};
    bool fixedC = all(c.lo == c.hi);
    for (uint i = 0; i < corners(a); ++i)
        for (uint j = 0; j < corners(b); ++j) {
            int2 av = corner(a,i), bv = corner(b,j), e = bv-av;
            // edge(a,b,c) has c coefficients (-e.y,e.x). Select exact
            // extrema instead of enumerating c; a/b corner extrema remain.
            int2 low(e.y > 0 ? c.hi.x : c.lo.x, e.x < 0 ? c.hi.y : c.lo.y);
            int2 high(e.y > 0 ? c.lo.x : c.hi.x, e.x < 0 ? c.lo.y : c.hi.y);
            int minimum = edge(av,bv,low);
            int maximum = fixedC ? minimum : edge(av,bv,high);
            r.lo = min(r.lo,minimum); r.hi = max(r.hi,maximum);
        }
    return r;
}
EdgeClass classify_edge(Box a, Box b, int2 p) {
    EdgeClass r = {true, true};
    bool fixedB = all(b.lo == b.hi);
    for (uint i = 0; i < corners(a); ++i) {
        int2 av = corner(a,i), q = p-av;
        // With a fixed, b coefficients are (q.y,-q.x). Strict extrema
        // decide the entire b box; zero extrema retain the original tie scan.
        int2 low(q.y < 0 ? b.hi.x : b.lo.x, q.x > 0 ? b.hi.y : b.lo.y);
        int2 high(q.y < 0 ? b.lo.x : b.hi.x, q.x > 0 ? b.lo.y : b.hi.y);
        int minimum = edge(av,low,p);
        int maximum = fixedB ? minimum : edge(av,high,p);
        if (minimum > 0) r.allFail = false;
        else if (maximum < 0) r.allPass = false;
        else if (minimum < 0 && maximum > 0) return {false, false};
        else for (uint j = 0; j < corners(b); ++j) {
            int2 bv = corner(b,j);
            int e = edge(av,bv,p);
            bool pass = e > 0 || (e == 0 && top_left(av,bv));
            r.allPass = r.allPass && pass;
            r.allFail = r.allFail && !pass;
            if (!r.allPass && !r.allFail) return r;
        }
        if (!r.allPass && !r.allFail) return r;
    }
    return r;
}
int floor_div256(int n) { return n >= 0 ? n / 256 : -((-n + 255) / 256); }
int ceil_div256(int n) { return -floor_div256(-n); }

Result candidate(thread const float2 *xy, thread const float2 *bound,
                 uint2 viewport, bool clipSafe, float threshold) {
    Result r = {HARDWARE, 0u, 0u, int2(0), uint2(0), {0u, 0u}};
    if (!clipSafe) { r.reasons = CLIP; return r; }
    if (any(viewport == 0u) || any(viewport > 16384u) || !finite(threshold)) {
        r.reasons = INPUT; return r;
    }
    float2 lo = xy[0], hi = xy[0]; Box box[4];
    for (uint k = 0; k < 4; ++k) {
        if (!finite(xy[k]) || !finite(bound[k]) || any(bound[k] < 0.0f)
            || any(xy[k] < 0.0f) || any(xy[k] > float2(viewport))) {
            r.reasons = INPUT; return r;
        }
        if (any(bound[k] > 1.0f/256.0f)) { r.reasons = BOUND_BUDGET; return r; }
        lo = min(lo, xy[k]); hi = max(hi, xy[k]);
        box[k] = snapped_box(xy[k], bound[k]);
        r.snapVariantVertices += uint(any(box[k].lo != box[k].hi));
    }
    if (threshold <= 0.0f || threshold > 2.0f || any(hi-lo > threshold)) {
        r.reasons = SIZE; return r;
    }
    int2 lower = box[0].lo, upper = box[0].hi;
    for (uint k = 1; k < 4; ++k) { lower = min(lower, box[k].lo); upper = max(upper, box[k].hi); }
    // Bounds also guarantee 32-bit determinant/length arithmetic cannot overflow:
    // all coordinate differences are <=1024; widened altitude products use ulong.
    if (any(upper-lower > 1024)) { r.reasons = BOUND_BUDGET; return r; }
    r.first = max(int2(0), int2(ceil_div256(lower.x-128), ceil_div256(lower.y-128)));
    int2 last = min(int2(viewport)-1, int2(floor_div256(upper.x-128), floor_div256(upper.y-128)));
    if (any(last < r.first)) { r.route = EMPTY; return r; }
    r.extent = uint2(last-r.first+1);
    if (any(r.extent > 4u)) { r.reasons = BOUND_BUDGET; return r; }

    constexpr uint order[6] = {0, 1, 2, 2, 3, 0};
    for (uint t = 0; t < 2; ++t) {
        uint i = order[3*t], j = order[3*t+1], k = order[3*t+2];
        AreaRange ar = area_range(box[i], box[j], box[k]);
        if (ar.hi <= 0) continue; // Every possible triangle is culled/degenerate.
        float2 e = xy[j]-xy[i], q = xy[k]-xy[i];
        float original = e.x*q.y-e.y*q.x;
        for (uint y = 0; y < r.extent.y; ++y) for (uint x = 0; x < r.extent.x; ++x) {
            int2 p = (r.first+int2(x,y))*256+128;
            EdgeClass a = classify_edge(box[j], box[k], p);
            if (a.allFail) continue;
            EdgeClass b = classify_edge(box[k], box[i], p);
            if (b.allFail) continue;
            EdgeClass c = classify_edge(box[i], box[j], p);
            if (c.allFail) continue;
            if (ar.lo <= 0 || !finite(original) || original <= 0) r.reasons |= ORIENTATION;
            // .125 pixels ==32 subpixels; min(area)/max(edge length) is a
            // conservative altitude lower bound across ALL possible snaps.
            if (ar.lo > 0 && ulong(ar.lo)*ulong(ar.lo) < 1024ul*ulong(ar.maxEdgeSquared)) r.reasons |= SLIVER;
            if (!a.allPass || !b.allPass || !c.allPass || ar.lo <= 0) r.reasons |= COVERAGE;
            else r.triangleMask[t] |= 1u << (y*r.extent.x+x);
        }
    }
    if (r.reasons) return r;
    r.route = (r.triangleMask[0] | r.triangleMask[1]) ? COMPUTE : EMPTY;
    return r;
}

void record(device Counters &c, Result r) {
    atomic_fetch_add_explicit(&c.inputQuads, 1u, memory_order_relaxed);
    if (r.route == EMPTY) atomic_fetch_add_explicit(&c.emptyQuads, 1u, memory_order_relaxed);
    else if (r.route == COMPUTE) atomic_fetch_add_explicit(&c.computeQuads, 1u, memory_order_relaxed);
    else atomic_fetch_add_explicit(&c.hardwareQuads, 1u, memory_order_relaxed);
    if (r.snapVariantVertices) atomic_fetch_add_explicit(&c.snapVariantQuads, 1u, memory_order_relaxed);
#define MICRO_ADMISSION_REASON(BIT, FIELD) if (r.reasons & BIT) atomic_fetch_add_explicit(&c.FIELD, 1u, memory_order_relaxed)
    MICRO_ADMISSION_REASON(INPUT, input); MICRO_ADMISSION_REASON(CLIP, clip);
    MICRO_ADMISSION_REASON(SIZE, size); MICRO_ADMISSION_REASON(BOUND_BUDGET, boundBudget);
    MICRO_ADMISSION_REASON(COVERAGE, coverage); MICRO_ADMISSION_REASON(ORIENTATION, orientation);
    MICRO_ADMISSION_REASON(SLIVER, sliver);
#undef MICRO_ADMISSION_REASON
}
} // namespace micro_admission
