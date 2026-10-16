/*
 * mcmetal_clusters.m -- round 12 (a) (docs/metal/CONTRACT.md 16.R12-A): GPU-driven terrain cluster culling.
 *
 * NOT part of the frozen ABI (mcmetal.h is unchanged, still ABI 2): `mcmtl_ext_cluster_cull` / `mcmtl_ext_cluster_prepare` are
 * optional extension symbols resolved by name from Java (MetalClusterCull); a missing symbol means the per-record path.
 *
 * Input per job (Java, TerrainClusters): `clusterCount` items of 16 bytes (uint4) in a CPU-written Shared buffer:
 *   x = first vertex of the cluster in the vertex heap, y = section info index (bits 0-19) | quad count - 1 (20-25) | facing
 *   slot (26-28, TerrainMeshInfo slots) | bounds unusable (29), z / w = section-relative AABB min / max, 10 bits per axis
 *   (x | y << 10 | z << 20) in 1/16 block, biased by 512 (min rounded down, max rounded up).
 * Per section (index = section info index): two float4 = (origin - camera, 0), (eye - origin, 0), computed in double by Java.
 * View (setBytes): 5 frustum planes (camera-relative, a x + b y + c z + d >= 0 inside), facing margin, flags (1 frustum,
 * 2 facing, 4 persistent section table), bounds margin; with viewBytes 128 also camDelta / eyeDelta (float4 at bytes 96 / 112).
 * Stage 3 (docs/stage3/LOD-DESIGN.md, chunk/LodGpu): with flag 4 the section table is persistent and relative to a fixed base
 * point C0 ((origin - C0), (C0 - origin)); the kernel subtracts camDelta = camera - C0 from the first and adds eyeDelta =
 * eye - C0 to the second, so the CPU writes no per-frame section table for the LOD clusters; the first float4's w is then the
 * bounds unit of that section's clusters in blocks (1/16 of an LOD cell: 2^level / 16) instead of 1/16 block.
 *
 * Three dispatches in one compute encoder (serial: each sees the previous one's writes), on a side command buffer of the
 * device's main queue committed now (as the tick kernels, mcmetal_tickfx.m):
 *   1. test: one thread per cluster; counts[c] = quad count if the cluster may be visible, else 0; items[c] = (first vertex,
 *      section) (the vertex function looks clusters up by their input index, so items are not compacted);
 *   2. scan: one threadgroup of 1024 threads, exclusive prefix sum of counts -> offsets, the draw's arguments
 *      (indexCount = 6 * total, instanceCount 1, rest 0: MTLDrawIndexedPrimitivesIndirectArguments);
 *   3. write: 64 threads per cluster: quads[offsets[c] + i] = c << 6 | i for i < counts[c].
 * The output keeps the input order (a deterministic prefix sum, no atomics), so the draw rasterizes the surviving quads in
 * exactly the order the per-record path would.
 *
 * Exactness (culling only removes quads that produce no fragment): a cluster is dropped only if its whole (outward-rounded,
 * margin-widened) box is outside one frustum plane, or if every quad of it lies in one facing bucket (TerrainMeshInfo, exact
 * classification) and the eye is beyond the cluster's extreme plane on the back side by more than the S1 margin (every
 * triangle back-facing; SOLID / CUTOUT cull back faces). Float rounding of the inputs is ~1e-5 block against margins of 1/16
 * and 0.25 block.
 *
 * Ordering: the frame's command buffer (committed later on the same queue) reads quads / items / arguments; they are tracked
 * resources, so Metal orders those reads after this buffer's writes. Java rings every buffer over 4 frames, so no write here
 * waits on an earlier frame's reads.
 */
#import "mcmetal_internal.h"
#import "mcmetal_clusters.h"
#import <objc/runtime.h>
#include <stddef.h>

static char mcmtl_clusters_key;
/* Stage 3 r6: GPU time of the cull command buffers (sum ns, count), read by mcmtl_ext_cluster_cull_stats. */
static _Atomic uint64_t mcmtl_cull_ns;
static _Atomic uint64_t mcmtl_cull_n;

MCMTL_API int32_t mcmtl_ext_cluster_cull_stats(uint64_t *out) {
    if (!out) return MCMTL_E_INVALID_ARGUMENT;
    out[0] = mcmtl_cull_ns;
    out[1] = mcmtl_cull_n;
    return MCMTL_OK;
}

static NSString *mcmtl_clusters_source(void) {
    return @"#include <metal_stdlib>\n"
        "using namespace metal;\n"
        "struct View { float4 planes[5]; float eps; uint flags; float margin; uint n; float4 camDelta; float4 eyeDelta; };\n"
        "inline bool covered(uint sec, device const uchar* coverage, constant uint2& cr) {\n"
        "    return sec < cr.y && coverage[cr.x + sec] != 0u;\n"
        "}\n"
        "kernel void mcmtl_cluster_test(device const uint4* items [[buffer(0)]], device const float4* sections [[buffer(1)]],\n"
        "                               constant View& v [[buffer(2)]], device uint* counts [[buffer(3)]], device uint2* outItems [[buffer(4)]],\n"
        "                               device const uchar* coverage [[buffer(12)]], constant uint2& cr [[buffer(13)]],\n"
        "                               uint c [[thread_position_in_grid]]) {\n"
        "    if (c >= v.n) return;\n"
        "    uint4 it = items[c];\n"
        "    uint sec = it.y & 0xFFFFFu;\n"
        "    uint q = ((it.y >> 20) & 63u) + 1u;\n"
        "    uint slot = (it.y >> 26) & 7u;\n"
        "    outItems[c] = uint2(it.x, sec);\n"
        "    bool vis = !covered(sec, coverage, cr);\n"
        "    if (vis && ((it.y >> 29) & 1u) == 0u) {\n"
        "        float4 o4 = sections[2u * sec];\n"
        "        float3 o = o4.xyz;\n"
        "        float3 e = sections[2u * sec + 1u].xyz;\n"
        "        float unit = 0.0625;\n"
        "        if ((v.flags & 4u) != 0u) { o -= v.camDelta.xyz; e += v.eyeDelta.xyz; unit = o4.w; }\n"
        "        float3 lo = float3(int3(int(it.z & 1023u), int((it.z >> 10) & 1023u), int((it.z >> 20) & 1023u)) - 512) * unit;\n"
        "        float3 hi = float3(int3(int(it.w & 1023u), int((it.w >> 10) & 1023u), int((it.w >> 20) & 1023u)) - 512) * unit;\n"
        "        if ((v.flags & 1u) != 0u) {\n"
        "            float3 mn = o + lo - v.margin;\n"
        "            float3 mx = o + hi + v.margin;\n"
        "            for (uint i = 0u; i < 5u; i++) {\n"
        "                float4 p = v.planes[i];\n"
        "                float3 s = select(mn, mx, p.xyz >= 0.0);\n"
        "                if (p.x * s.x + p.y * s.y + p.z * s.z + p.w < 0.0) { vis = false; }\n"
        "            }\n"
        "        }\n"
        "        if (vis && (v.flags & 2u) != 0u) {\n"
        "            switch (slot) {\n"
        "                case 0u: vis = e.y < hi.y + v.eps; break;\n"   /* DOWN: plane = max y */
        "                case 1u: vis = e.z < hi.z + v.eps; break;\n"   /* NORTH: max z */
        "                case 2u: vis = e.x > lo.x - v.eps; break;\n"   /* EAST: min x */
        "                case 3u: vis = e.y > lo.y - v.eps; break;\n"   /* UP: min y */
        "                case 5u: vis = e.z > lo.z - v.eps; break;\n"   /* SOUTH: min z */
        "                case 6u: vis = e.x < hi.x + v.eps; break;\n"   /* WEST: max x */
        "                default: break;\n"                             /* UNASSIGNED */
        "            }\n"
        "        }\n"
        "    }\n"
        "    counts[c] = vis ? q : 0u;\n"
        "}\n"
        "kernel void mcmtl_cluster_scan(device const uint* counts [[buffer(0)]], device uint* offsets [[buffer(1)]], device uint* args [[buffer(2)]],\n"
        "                               constant uint& n [[buffer(3)]], uint t [[thread_position_in_threadgroup]],\n"
        "                               uint lane [[thread_index_in_simdgroup]], uint sg [[simdgroup_index_in_threadgroup]]) {\n"
        "    threadgroup uint partial[32];\n"
        "    uint per = (n + 1023u) / 1024u;\n"
        "    uint begin = min(t * per, n);\n"
        "    uint end = min(begin + per, n);\n"
        "    uint sum = 0u;\n"
        "    for (uint i = begin; i < end; i++) { sum += counts[i]; }\n"
        "    uint pre = simd_prefix_exclusive_sum(sum);\n"
        "    if (lane == 31u) { partial[sg] = pre + sum; }\n"
        "    threadgroup_barrier(mem_flags::mem_threadgroup);\n"
        "    if (sg == 0u) { partial[lane] = simd_prefix_exclusive_sum(partial[lane]); }\n"
        "    threadgroup_barrier(mem_flags::mem_threadgroup);\n"
        "    uint run = partial[sg] + pre;\n"
        "    for (uint i = begin; i < end; i++) { offsets[i] = run; run += counts[i]; }\n"
        "    if (t == 1023u) { args[0] = 6u * run; args[1] = 1u; args[2] = 0u; args[3] = 0u; args[4] = 0u; }\n"
        "}\n"
        "kernel void mcmtl_cluster_write(device const uint* counts [[buffer(0)]], device const uint* offsets [[buffer(1)]], device uint* quads [[buffer(2)]],\n"
        "                                constant uint& n [[buffer(3)]], uint gid [[thread_position_in_grid]]) {\n"
        "    uint c = gid >> 6;\n"
        "    uint i = gid & 63u;\n"
        "    if (c >= n || i >= counts[c]) return;\n"
        "    quads[offsets[c] + i] = (c << 6) | i;\n"
        "}\n"
        /* Stage 3 (flag 4, LOD lists of 10^5+ clusters, mostly culled): one thread per cluster writes its quads. */
        "kernel void mcmtl_cluster_write_loop(device const uint* counts [[buffer(0)]], device const uint* offsets [[buffer(1)]], device uint* quads [[buffer(2)]],\n"
        "                                constant uint& n [[buffer(3)]], uint c [[thread_position_in_grid]]) {\n"
        "    if (c >= n) return;\n"
        "    uint k = counts[c];\n"
        "    uint o = offsets[c];\n"
        "    for (uint i = 0u; i < k; i++) { quads[o + i] = (c << 6) | i; }\n"
        "}\n"
        "kernel void mcmtl_cluster_write_ordered(device const uint* counts [[buffer(0)]], device const uint* groupOffsets [[buffer(1)]],\n"
        "                                device uint* quads [[buffer(2)]], constant uint& n [[buffer(3)]], uint c [[thread_position_in_grid]]) {\n"
        "    if (c >= n) return;\n"
        "    uint k = counts[c];\n"
        "    uint o = groupOffsets[c >> 8] + counts[n + c];\n"
        "    for (uint i = 0u; i < k; i++) { quads[o + i] = (c << 6) | i; }\n"
        "}\n"
        /* Stage 3 r3 (LOD occlusion, CONTRACT 16.S3-LOD-R3): the test of mcmtl_cluster_test plus the previous frame's box
         * visibility. vis[e] = stamp << 1 | visible, e = visBase + (first quad >> 3) (arena ranges are 8-quad aligned, so an
         * entry is shared only by clusters of one section-layer). A cluster that passed frustum + facing and lies entirely
         * inside the four side planes and in front of the eye is "eligible": its box is appended to the box list (drawn after
         * the opaque LOD, writing stamp << 1 | 1 for every fragment that passes the depth test) and it is skipped this frame
         * iff its entry says "tested in the previous frame, no box fragment visible". Every other cluster writes "visible"
         * (stamp << 1 | 1): entries never claim a test that did not happen. Shared entries only add visibility. */
        "struct Occl { uint stamp; uint boxCapacity; uint visBase; uint itemsBase; uint visEntries; float eyeMin; uint prevKey; uint pad1; };\n"
        "kernel void mcmtl_lod_test_occl(device const uint4* items [[buffer(0)]], device const float4* sections [[buffer(1)]],\n"
        "                               constant View& v [[buffer(2)]], device uint* counts [[buffer(3)]], device uint2* outItems [[buffer(4)]],\n"
        "                               device atomic_uint* visb [[buffer(5)]], device uint2* boxes [[buffer(6)]], device atomic_uint* params [[buffer(7)]],\n"
        "                               constant Occl& oc [[buffer(8)]], device const uchar* coverage [[buffer(12)]],\n"
        "                               constant uint2& cr [[buffer(13)]], uint c [[thread_position_in_grid]]) {\n"
        "    if (c >= v.n) return;\n"
        "    uint4 it = items[c];\n"
        "    uint sec = it.y & 0xFFFFFu;\n"
        "    uint q = ((it.y >> 20) & 63u) + 1u;\n"
        "    uint slot = (it.y >> 26) & 7u;\n"
        "    outItems[c] = uint2(it.x, sec);\n"
        "    bool vis = !covered(sec, coverage, cr);\n"
        "    bool inside = false;\n"
        "    if (vis && ((it.y >> 29) & 1u) == 0u) {\n"
        "        float4 o4 = sections[2u * sec];\n"
        "        float3 o = o4.xyz - v.camDelta.xyz;\n"
        "        float3 e = sections[2u * sec + 1u].xyz + v.eyeDelta.xyz;\n"
        "        float unit = o4.w;\n"
        "        float3 lo = float3(int3(int(it.z & 1023u), int((it.z >> 10) & 1023u), int((it.z >> 20) & 1023u)) - 512) * unit;\n"
        "        float3 hi = float3(int3(int(it.w & 1023u), int((it.w >> 10) & 1023u), int((it.w >> 20) & 1023u)) - 512) * unit;\n"
        "        float3 mn = o + lo - v.margin;\n"
        "        float3 mx = o + hi + v.margin;\n"
        "        inside = (v.flags & 1u) != 0u;\n"
        "        if ((v.flags & 1u) != 0u) {\n"
        "            for (uint i = 0u; i < 5u; i++) {\n"
        "                float4 p = v.planes[i];\n"
        "                float3 s = select(mn, mx, p.xyz >= 0.0);\n"
        "                if (p.x * s.x + p.y * s.y + p.z * s.z + p.w < 0.0) { vis = false; }\n"
        "                float3 t = select(mx, mn, p.xyz >= 0.0);\n"
        "                if (p.x * t.x + p.y * t.y + p.z * t.z + p.w < (i == 4u ? oc.eyeMin : 0.0)) { inside = false; }\n"
        "            }\n"
        "        }\n"
        "        if (vis && (v.flags & 2u) != 0u) {\n"
        "            switch (slot) {\n"
        "                case 0u: vis = e.y < hi.y + v.eps; break;\n"
        "                case 1u: vis = e.z < hi.z + v.eps; break;\n"
        "                case 2u: vis = e.x > lo.x - v.eps; break;\n"
        "                case 3u: vis = e.y > lo.y - v.eps; break;\n"
        "                case 5u: vis = e.z > lo.z - v.eps; break;\n"
        "                case 6u: vis = e.x < hi.x + v.eps; break;\n"
        "                default: break;\n"
        "            }\n"
        "        }\n"
        "    }\n"
        "    uint vi = oc.visBase + (it.x >> 5u);\n"
        "    if (vi < oc.visEntries) {\n"
        "        bool eligible = vis && inside;\n"
        "        uint old = atomic_load_explicit(&visb[vi], memory_order_relaxed);\n"
        "        if (eligible) {\n"
        "            uint b = atomic_fetch_add_explicit(&params[5], 1u, memory_order_relaxed);\n"
        "            if (b < oc.boxCapacity) { boxes[b] = uint2(oc.itemsBase + c, vi); } else { eligible = false; }\n"
        "        }\n"
        "        atomic_fetch_max_explicit(&visb[vi], (oc.stamp << 1) | (eligible ? 0u : 1u), memory_order_relaxed);\n"
        "        if (eligible && old == oc.prevKey) {\n"
        "            vis = false;\n"
        "            atomic_fetch_add_explicit(&params[6], q, memory_order_relaxed);\n"
        "            atomic_fetch_add_explicit(&params[7], 1u, memory_order_relaxed);\n"
        "        }\n"
        "    }\n"
        "    counts[c] = vis ? q : 0u;\n"
        "}\n"
        /* Stage 3 r4 (view flag 8, CONTRACT 16.S3-LOD-R4): mcmtl_lod_test_occl's test fused with the output: a surviving cluster
         * reserves its quads with one atomic per SIMD group on the draw's index count (args[0], zeroed by the CPU before the
         * dispatch) and writes them itself, so a job is ONE dispatch instead of test -> single-threadgroup scan -> write, and the
         * jobs of a frame run concurrently. The output order is no longer the input order (LOD only: opaque LOD faces never tie
         * in depth with each other except across dissolving levels, whose dithers are each other's inverse). */
        "kernel void mcmtl_lod_cull_fused(device const uint4* items [[buffer(0)]], device const float4* sections [[buffer(1)]],\n"
        "                               constant View& v [[buffer(2)]], device atomic_uint* args [[buffer(3)]], device uint2* outItems [[buffer(4)]],\n"
        "                               device atomic_uint* visb [[buffer(5)]], device uint2* boxes [[buffer(6)]], device atomic_uint* params [[buffer(7)]],\n"
        "                               constant Occl& oc [[buffer(8)]], device uint* quads [[buffer(9)]], device const uchar* secOcc [[buffer(10)]],\n"
        "                               device uint* counts [[buffer(11)]], device const uchar* coverage [[buffer(12)]], constant uint2& cr [[buffer(13)]],\n"
        "                               uint c [[thread_position_in_grid]], uint tid [[thread_position_in_threadgroup]],\n"
        "                               uint tg [[threadgroup_position_in_grid]], uint lane [[thread_index_in_simdgroup]],\n"
        "                               uint sg [[simdgroup_index_in_threadgroup]]) {\n"
        "    bool live = c < v.n;\n"
        "    uint4 it = live ? items[c] : uint4(0u, 1u << 29, 0u, 0u);\n"
        "    uint sec = it.y & 0xFFFFFu;\n"
        "    uint q = ((it.y >> 20) & 63u) + 1u;\n"
        "    uint slot = (it.y >> 26) & 7u;\n"
        "    if (live) { outItems[c] = uint2(it.x, sec); }\n"
        /* Hidden and padded lanes must reach every SIMD collective with zero contribution. */
        "    bool vis = live && !covered(sec, coverage, cr);\n"
        "    bool inside = false;\n"
        "    if (vis && ((it.y >> 29) & 1u) == 0u) {\n"
        "        float4 o4 = sections[2u * sec];\n"
        "        float3 o = o4.xyz - v.camDelta.xyz;\n"
        "        float3 e = sections[2u * sec + 1u].xyz + v.eyeDelta.xyz;\n"
        "        float unit = o4.w;\n"
        "        float3 lo = float3(int3(int(it.z & 1023u), int((it.z >> 10) & 1023u), int((it.z >> 20) & 1023u)) - 512) * unit;\n"
        "        float3 hi = float3(int3(int(it.w & 1023u), int((it.w >> 10) & 1023u), int((it.w >> 20) & 1023u)) - 512) * unit;\n"
        "        float3 mn = o + lo - v.margin;\n"
        "        float3 mx = o + hi + v.margin;\n"
        "        inside = (v.flags & 1u) != 0u;\n"
        "        if ((v.flags & 1u) != 0u) {\n"
        "            for (uint i = 0u; i < 5u; i++) {\n"
        "                float4 p = v.planes[i];\n"
        "                float3 s = select(mn, mx, p.xyz >= 0.0);\n"
        "                if (p.x * s.x + p.y * s.y + p.z * s.z + p.w < 0.0) { vis = false; }\n"
        "                float3 t = select(mx, mn, p.xyz >= 0.0);\n"
        "                if (p.x * t.x + p.y * t.y + p.z * t.z + p.w < (i == 4u ? oc.eyeMin : 0.0)) { inside = false; }\n"
        "            }\n"
        "        }\n"
        "        if (vis && (v.flags & 2u) != 0u) {\n"
        "            switch (slot) {\n"
        "                case 0u: vis = e.y < hi.y + v.eps; break;\n"
        "                case 1u: vis = e.z < hi.z + v.eps; break;\n"
        "                case 2u: vis = e.x > lo.x - v.eps; break;\n"
        "                case 3u: vis = e.y > lo.y - v.eps; break;\n"
        "                case 5u: vis = e.z > lo.z - v.eps; break;\n"
        "                case 6u: vis = e.x < hi.x + v.eps; break;\n"
        "                default: break;\n"
        "            }\n"
        "        }\n"
        "    }\n"
        "    uint vi = oc.visBase + (it.x >> 5u);\n"
        "    if ((v.flags & 16u) != 0u) {\n"   /* r6 section occlusion: the section test of this frame decided (frustum-out or occluded) */
        "        if (live && secOcc[sec] != 0u) { vis = false; }\n"
        "    } else if (live && vi < oc.visEntries) {\n"
        "        bool eligible = vis && inside;\n"
        "        uint old = atomic_load_explicit(&visb[vi], memory_order_relaxed);\n"
        "        if (eligible) {\n"
        "            uint b = atomic_fetch_add_explicit(&params[5], 1u, memory_order_relaxed);\n"
        "            if (b < oc.boxCapacity) { boxes[b] = uint2(oc.itemsBase + c, vi); } else { eligible = false; }\n"
        "        }\n"
        "        atomic_fetch_max_explicit(&visb[vi], (oc.stamp << 1) | (eligible ? 0u : 1u), memory_order_relaxed);\n"
        "        if (eligible && old == oc.prevKey) {\n"
        "            vis = false;\n"
        "            atomic_fetch_add_explicit(&params[6], q, memory_order_relaxed);\n"
        "            atomic_fetch_add_explicit(&params[7], 1u, memory_order_relaxed);\n"
        "        }\n"
        "    }\n"
        "    uint mine = vis ? q : 0u;\n"
        /* Round 7 (Occl.pad1 = 1: LOD translucent jobs): order-preserving output. Unsorted water / ice need a fixed order: with the
         * atomic reservation, ice drawn before the water under it hid that water some frames (lighter ice flashing). Scratch (uints):
         * [0, n) counts, [n, 2n) offset inside the 256-cluster threadgroup, [2n, 2n + G) threadgroup totals, [2n + G, 2n + 2G) their
         * exclusive scan (mcmtl_cluster_scan over G = ceil(n / 256)); mcmtl_cluster_write_ordered then writes in input order. */
        "    if (oc.pad1 != 0u) {\n"
        "        threadgroup uint part[8];\n"
        "        if (tid < 8u) { part[tid] = 0u; }\n"
        "        threadgroup_barrier(mem_flags::mem_threadgroup);\n"
        "        uint pre = simd_prefix_exclusive_sum(mine);\n"
        "        uint tot = simd_sum(mine);\n"
        "        if (lane == 0u) { part[sg] = tot; }\n"
        "        threadgroup_barrier(mem_flags::mem_threadgroup);\n"
        "        uint base = 0u;\n"
        "        for (uint i = 0u; i < sg; i++) { base += part[i]; }\n"
        "        if (live) { counts[c] = mine; counts[v.n + c] = base + pre; }\n"
        "        if (tid == 0u) { uint g = 0u; for (uint i = 0u; i < 8u; i++) { g += part[i]; } counts[2u * v.n + tg] = g; }\n"
        "        return;\n"
        "    }\n"
        "    uint pre = simd_prefix_exclusive_sum(mine);\n"
        "    uint total = simd_sum(mine);\n"
        "    uint base = 0u;\n"
        "    if (simd_is_first() && total != 0u) { base = atomic_fetch_add_explicit(&args[0], 6u * total, memory_order_relaxed) / 6u; }\n"
        "    base = simd_broadcast_first(base);\n"
        "    uint o = base + pre;\n"
        "    for (uint i = 0u; i < mine; i++) { quads[o + i] = (c << 6) | i; }\n"
        "}\n"
        /* Stage 3 r6 (view flag 16): section-level occlusion. One thread per drawn section (sitems: uint4 like a cluster item, x unused,
         * y = section slot | bounds unusable << 29, z / w = the union of its clusters' bounds): frustum test, then one box per
         * section entirely inside the view (entry secBase + slot); secOcc[slot] = 1 if the section is outside the frustum or its box
         * had no visible fragment OCCL_LAG frames ago; the fused cluster kernel then drops all its clusters. */
        "kernel void mcmtl_lod_sec_test(device const uint4* sitems [[buffer(0)]], device const float4* sections [[buffer(1)]],\n"
        "                               constant View& v [[buffer(2)]], device atomic_uint* visb [[buffer(5)]], device uint2* boxes [[buffer(6)]],\n"
        "                               device atomic_uint* params [[buffer(7)]], constant Occl& oc [[buffer(8)]], device uchar* secOcc [[buffer(10)]],\n"
        "                               constant uint2& sp [[buffer(11)]], device const uchar* coverage [[buffer(12)]],\n"
        "                               constant uint2& cr [[buffer(13)]], uint c [[thread_position_in_grid]]) {\n"
        "    if (c >= sp.y) return;\n"
        "    uint4 it = sitems[c];\n"
        "    uint sec = it.y & 0xFFFFFu;\n"
        "    bool vis = !covered(sec, coverage, cr);\n"
        "    bool inside = false;\n"
        "    if (vis && ((it.y >> 29) & 1u) == 0u) {\n"
        "        float4 o4 = sections[2u * sec];\n"
        "        float3 o = o4.xyz - v.camDelta.xyz;\n"
        "        float unit = o4.w;\n"
        "        float3 lo = float3(int3(int(it.z & 1023u), int((it.z >> 10) & 1023u), int((it.z >> 20) & 1023u)) - 512) * unit;\n"
        "        float3 hi = float3(int3(int(it.w & 1023u), int((it.w >> 10) & 1023u), int((it.w >> 20) & 1023u)) - 512) * unit;\n"
        "        float3 mn = o + lo - v.margin;\n"
        "        float3 mx = o + hi + v.margin;\n"
        "        inside = (v.flags & 1u) != 0u;\n"
        "        if ((v.flags & 1u) != 0u) {\n"
        "            for (uint i = 0u; i < 5u; i++) {\n"
        "                float4 p = v.planes[i];\n"
        "                float3 s = select(mn, mx, p.xyz >= 0.0);\n"
        "                if (p.x * s.x + p.y * s.y + p.z * s.z + p.w < 0.0) { vis = false; }\n"
        "                float3 t = select(mx, mn, p.xyz >= 0.0);\n"
        "                if (p.x * t.x + p.y * t.y + p.z * t.z + p.w < (i == 4u ? oc.eyeMin : 0.0)) { inside = false; }\n"
        "            }\n"
        "        }\n"
        "    }\n"
        "    bool occluded = false;\n"
        "    uint vi = sp.x + sec;\n"
        "    if (vi < oc.visEntries) {\n"
        "        bool eligible = vis && inside;\n"
        "        uint old = atomic_load_explicit(&visb[vi], memory_order_relaxed);\n"
        "        if (eligible) {\n"
        "            uint b = atomic_fetch_add_explicit(&params[5], 1u, memory_order_relaxed);\n"
        "            if (b < oc.boxCapacity) { boxes[b] = uint2(c, vi); } else { eligible = false; }\n"
        "        }\n"
        "        atomic_fetch_max_explicit(&visb[vi], (oc.stamp << 1) | (eligible ? 0u : 1u), memory_order_relaxed);\n"
        "        occluded = eligible && old == oc.prevKey;\n"
        "        if (occluded) { atomic_fetch_add_explicit(&params[7], 1u, memory_order_relaxed); }\n"
        "    }\n"
        "    secOcc[sec] = (!vis || occluded) ? (uchar)1 : (uchar)0;\n"
        "}\n"
        /* After every job: the box draw's arguments (3 quads = 18 indices per box, MTLDrawIndexedPrimitivesIndirectArguments). */
        "kernel void mcmtl_lod_box_args(device uint* params [[buffer(0)]], constant uint& cap [[buffer(1)]], uint t [[thread_position_in_grid]]) {\n"
        "    if (t != 0u) return;\n"
        "    uint n = min(params[5], cap);\n"
        "    params[0] = 18u * n; params[1] = 1u; params[2] = 0u; params[3] = 0u; params[4] = 0u;\n"
        "}\n";
}

/* {test, scan, write} pipelines, compiled once per device (fast math, as every game shader). */
static NSArray<id<MTLComputePipelineState>> *mcmtl_clusters_psos(MCMTLDevice *dev) {
    NSArray<id<MTLComputePipelineState>> *psos;
    @synchronized (dev) {
        psos = objc_getAssociatedObject(dev, &mcmtl_clusters_key);
        if (psos) return psos;
        MTLCompileOptions *options = [MTLCompileOptions new];
        options.languageVersion = (MTLLanguageVersion)(dev->mslLanguageVersion ? dev->mslLanguageVersion : MTLLanguageVersion3_2);
        options.mathMode = MTLMathModeFast;
        NSError *error = nil;
        id<MTLLibrary> lib = [dev->device newLibraryWithSource:mcmtl_clusters_source() options:options error:&error];
        if (!lib) {
            mcmtl_set_error_nserror("mcmtl_ext_cluster_cull: kernel compile failed", error);
            return nil;
        }
        NSMutableArray<id<MTLComputePipelineState>> *list = [NSMutableArray arrayWithCapacity:4];
        for (NSString *name in @[@"mcmtl_cluster_test", @"mcmtl_cluster_scan", @"mcmtl_cluster_write", @"mcmtl_cluster_write_loop",
                                 @"mcmtl_lod_test_occl", @"mcmtl_lod_box_args",
                                 @"mcmtl_lod_cull_fused", @"mcmtl_lod_sec_test", @"mcmtl_cluster_write_ordered"]) {
            id<MTLFunction> fn = [lib newFunctionWithName:name];
            id<MTLComputePipelineState> pso = fn ? [dev->device newComputePipelineStateWithFunction:fn error:&error] : nil;
            if (!pso) {
                mcmtl_set_error_nserror("mcmtl_ext_cluster_cull: pipeline creation failed", error);
                return nil;
            }
            [list addObject:pso];
        }
        if (list[1].maxTotalThreadsPerThreadgroup < 1024 || list[1].threadExecutionWidth != 32) {
            mcmtl_set_error("mcmtl_ext_cluster_cull: the scan needs 1024 threads per threadgroup and SIMD width 32 (%lu, %lu)",
                            (unsigned long)list[1].maxTotalThreadsPerThreadgroup, (unsigned long)list[1].threadExecutionWidth);
            return nil;
        }
        psos = [list copy];
        objc_setAssociatedObject(dev, &mcmtl_clusters_key, psos, OBJC_ASSOCIATION_RETAIN);
    }
    return psos;
}

MCMTL_API int32_t mcmtl_ext_cluster_prepare(mcmtl_handle device) {
    MCMTL_TRY_BEGIN
        mcmtl_clear_error();
        if (!device) {
            mcmtl_set_error("mcmtl_ext_cluster_prepare: device is 0");
            return MCMTL_E_INVALID_ARGUMENT;
        }
        return mcmtl_clusters_psos(MCMTL_DEVICE(device)) ? MCMTL_OK : MCMTL_E_COMPILE_FAILED;
    MCMTL_TRY_END(MCMTL_E_OBJC_EXCEPTION)
}

static BOOL mcmtl_clusters_fits(id<MTLBuffer> b, uint64_t offset, uint64_t bytes) {
    return b && offset <= b.length && bytes <= b.length - offset;
}

typedef struct {
    uint64_t buffer, offset;
    uint32_t count;
} mcmtl_cluster_coverage;

/* Encodes `jobCount` jobs (one cluster list each, sharing the view) into one compute encoder of a side command buffer and
 * commits it. `view` = 5 float4 planes, then eps, flags, margin (36 floats-worth; 92 bytes are read). All-or-nothing: a job
 * whose buffers are too small fails the call before anything is encoded. */
static int32_t mcmtl_cluster_cull_impl(mcmtl_handle device, const void *jobsPtr, uint32_t jobCount, const void *view, uint32_t viewBytes,
    const mcmtl_lod_occl *occl, const uint32_t *jobExtra, const mcmtl_lod_sec *sec, const mcmtl_cluster_coverage *coverage, id<MTLCommandBuffer> supplied);

int32_t mcmtl_clusters_encode(MCMTLDevice *device, id<MTLCommandBuffer> commandBuffer,
    const mcmtl_cluster_job *jobs, uint32_t jobCount, const void *view, uint32_t viewBytes,
    const mcmtl_lod_occl *occlusion, const uint32_t *jobExtra, const mcmtl_lod_sec *sections) {
    if (!device || !commandBuffer || commandBuffer.device != device->device
        || commandBuffer.status >= MTLCommandBufferStatusCommitted || (sections && !occlusion)) {
        mcmtl_set_error("mcmtl_clusters_encode: invalid device, command buffer or section inputs");
        return MCMTL_E_INVALID_ARGUMENT;
    }
    return mcmtl_cluster_cull_impl((mcmtl_handle)(uintptr_t)(__bridge void *)device, jobs, jobCount, view, viewBytes,
        occlusion, jobExtra, sections, NULL, commandBuffer);
}

/* Stage 3 r6: the LOD cull with section-level occlusion (view flag 16 set by the caller). */
MCMTL_API int32_t mcmtl_ext_lod_cull_sec(mcmtl_handle device, const void *jobsPtr, uint32_t jobCount, const void *view, uint32_t viewBytes,
                                         const void *occl, const void *jobExtra, const void *sec) {
    if (!sec || !occl) { mcmtl_set_error("mcmtl_ext_lod_cull_sec: invalid argument"); return MCMTL_E_INVALID_ARGUMENT; }
    return mcmtl_cluster_cull_impl(device, jobsPtr, jobCount, view, viewBytes, (const mcmtl_lod_occl *)occl, (const uint32_t *)jobExtra,
                                   (const mcmtl_lod_sec *)sec, NULL, nil);
}

MCMTL_API int32_t mcmtl_ext_cluster_cull(mcmtl_handle device, const void *jobsPtr, uint32_t jobCount, const void *view, uint32_t viewBytes) {
    return mcmtl_cluster_cull_impl(device, jobsPtr, jobCount, view, viewBytes, NULL, NULL, NULL, NULL, nil);
}

/* Stage 3 r3: the LOD cull (flag-4 view) with box occlusion; `jobExtra` = per job (visBase, itemsBase) uints. occl == NULL
 * is mcmtl_ext_cluster_cull. */
MCMTL_API int32_t mcmtl_ext_lod_cull(mcmtl_handle device, const void *jobsPtr, uint32_t jobCount, const void *view, uint32_t viewBytes,
                                     const void *occl, const void *jobExtra) {
    return mcmtl_cluster_cull_impl(device, jobsPtr, jobCount, view, viewBytes, (const mcmtl_lod_occl *)occl, (const uint32_t *)jobExtra, NULL, NULL, nil);
}

/* Coverage is an optional symbol, with no added view flags or shared-layout fields. */
MCMTL_API int32_t mcmtl_ext_lod_cull_coverage(mcmtl_handle device, const void *jobsPtr, uint32_t jobCount,
    const void *view, uint32_t viewBytes, const void *occl, const void *jobExtra, const void *sec,
    uint64_t coverageBuffer, uint64_t coverageOffset, uint32_t coverageCount) {
    const mcmtl_cluster_coverage coverage = {coverageBuffer, coverageOffset, coverageCount};
    return mcmtl_cluster_cull_impl(device, jobsPtr, jobCount, view, viewBytes, (const mcmtl_lod_occl *)occl,
        (const uint32_t *)jobExtra, (const mcmtl_lod_sec *)sec, &coverage, nil);
}

static int32_t mcmtl_cluster_cull_impl(mcmtl_handle device, const void *jobsPtr, uint32_t jobCount, const void *view, uint32_t viewBytes,
                                       const mcmtl_lod_occl *occl, const uint32_t *jobExtra, const mcmtl_lod_sec *sec, const mcmtl_cluster_coverage *coverage, id<MTLCommandBuffer> supplied) {
    MCMTL_TRY_BEGIN
        mcmtl_clear_error();
        if (!device || (!jobsPtr && jobCount) || jobCount > 4096u || !view || viewBytes < 92u || (viewBytes != 92u && viewBytes != 128u)) {
            mcmtl_set_error("mcmtl_ext_cluster_cull: invalid argument");
            return MCMTL_E_INVALID_ARGUMENT;
        }
        uint32_t flagsIn;
        memcpy(&flagsIn, (const uint8_t *)view + 84, 4);
        MCMTLDevice *dev = MCMTL_DEVICE(device);
        id<MTLBuffer> coverageBuffer = coverage ? MCMTL_BUFFER(coverage->buffer) : nil;
        if (coverage && (!coverageBuffer || ![coverageBuffer conformsToProtocol:@protocol(MTLBuffer)]
                         || coverageBuffer.device != dev->device || coverage->count == 0u || coverage->count > (1u << 20)
                         || !mcmtl_clusters_fits(coverageBuffer, coverage->offset, coverage->count)
                         || (sec && !occl))) {
            mcmtl_set_error("mcmtl_ext_lod_cull_coverage: invalid coverage buffer/range or section inputs");
            return MCMTL_E_INVALID_ARGUMENT;
        }
        if (jobCount == 0) return MCMTL_OK;
        const mcmtl_cluster_job *jobs = (const mcmtl_cluster_job *)jobsPtr;
        for (uint32_t j = 0; j < jobCount; j++) {
            const mcmtl_cluster_job *job = &jobs[j];
            uint64_t n = job->clusterCount;
            if (n == 0 || n > (1u << 26) || !mcmtl_clusters_fits(MCMTL_BUFFER(job->itemsBuffer), job->itemsOffset, 16u * n)
                || !mcmtl_clusters_fits(MCMTL_BUFFER(job->outItemsBuffer), job->outItemsOffset, 8u * n)
                || !mcmtl_clusters_fits(MCMTL_BUFFER(job->quadsBuffer), job->quadsOffset, 4u * (uint64_t)job->quadCapacity)
                || !mcmtl_clusters_fits(MCMTL_BUFFER(job->argsBuffer), job->argsOffset, 20u)
                || !mcmtl_clusters_fits(MCMTL_BUFFER(job->scratchBuffer), job->scratchOffset, 8u * n)
                || !MCMTL_BUFFER(job->sectionsBuffer) || job->quadCapacity < n || job->quadCapacity > 64u * n) {
                mcmtl_set_error("mcmtl_ext_cluster_cull: job %u invalid (%llu clusters)", j, (unsigned long long)n);
                return MCMTL_E_INVALID_ARGUMENT;
            }
        }
        if (occl && (!jobExtra || viewBytes != 128u || !mcmtl_clusters_fits(MCMTL_BUFFER(occl->visBuffer), occl->visOffset, 4u * (uint64_t)occl->visEntries)
                     || !mcmtl_clusters_fits(MCMTL_BUFFER(occl->boxesBuffer), occl->boxesOffset, 8u * (uint64_t)occl->boxCapacity)
                     || !mcmtl_clusters_fits(MCMTL_BUFFER(occl->paramsBuffer), occl->paramsOffset, 64u))) {
            mcmtl_set_error("mcmtl_ext_lod_cull: occlusion buffers invalid");
            return MCMTL_E_INVALID_ARGUMENT;
        }
        if (sec && (!MCMTL_BUFFER(sec->sitemsBuffer) || !mcmtl_clusters_fits(MCMTL_BUFFER(sec->sitemsBuffer), sec->sitemsOffset, 16u * (uint64_t)sec->count)
                    || !mcmtl_clusters_fits(MCMTL_BUFFER(sec->secOccBuffer), sec->secOccOffset, 1u << 20))) {
            mcmtl_set_error("mcmtl_ext_lod_cull_sec: section buffers invalid");
            return MCMTL_E_INVALID_ARGUMENT;
        }
        NSArray<id<MTLComputePipelineState>> *psos = mcmtl_clusters_psos(dev);
        if (!psos) return MCMTL_E_COMPILE_FAILED;
        id<MTLCommandBuffer> cb = supplied ?: [dev->queue commandBuffer];
        if (!supplied) cb.label = @"mcmetal terrain cluster cull (R12-A)";
        if (!cb) { mcmtl_set_error("cluster cull command buffer allocation failed"); return MCMTL_E_OUT_OF_MEMORY; }
        /* Stage 3 r4: view flag 8 = fused LOD cull (mcmtl_lod_cull_fused; occlusion path only, args buffers CPU-visible). */
        BOOL fused = occl && (flagsIn & 8u) != 0u;
        for (uint32_t j = 0; fused && j < jobCount; j++) {
            id<MTLBuffer> a = MCMTL_BUFFER(jobs[j].argsBuffer);
            if (a.storageMode == MTLStorageModePrivate || a.contents == NULL) fused = NO;
        }
        for (uint32_t j = 0; fused && j < jobCount; j++) {
            uint64_t n = jobs[j].clusterCount, groups = (n + 255u) / 256u;
            if ((jobExtra[2u * j] >> 31) != 0u && !mcmtl_clusters_fits(MCMTL_BUFFER(jobs[j].scratchBuffer), jobs[j].scratchOffset, 8u * n + 8u * groups)) {
                mcmtl_set_error("mcmtl_ext_lod_cull: ordered job %u scratch too small", j);
                return MCMTL_E_INVALID_ARGUMENT;
            }
        }
        if (fused) {
            for (uint32_t j = 0; j < jobCount; j++) {
                uint32_t *a = (uint32_t *)((uint8_t *)MCMTL_BUFFER(jobs[j].argsBuffer).contents + jobs[j].argsOffset);
                a[0] = 0u; a[1] = 1u; a[2] = 0u; a[3] = 0u; a[4] = 0u;
            }
        }
        id<MTLComputeCommandEncoder> enc = [cb computeCommandEncoderWithDispatchType:fused ? MTLDispatchTypeConcurrent : MTLDispatchTypeSerial];
        /* 92 bytes, or 128 with the stage-3 LOD camera deltas (bytes 96-127; flag 4, below). */
        uint8_t viewData[128];
        memset(viewData, 0, sizeof viewData);
        memcpy(viewData, view, 92);
        if (viewBytes == 128u) memcpy(viewData + 96, (const uint8_t *)view + 96, 32);
        uint32_t viewFlags;
        memcpy(&viewFlags, viewData + 84, 4);
        const BOOL lodLists = (viewFlags & 4u) != 0u;
        /* The new entry point permits a NULL section prepass. Never consult stale secOcc
         * (or the dummy binding) when no section decision was encoded for this call. */
        if (coverage && (!sec || sec->count == 0u || !fused)) {
            viewFlags &= ~16u;
            memcpy(viewData + 84, &viewFlags, 4);
        }
        /* Bind at a uint-aligned backing offset, retaining arbitrary byte subranges via cr.x.
         * Legacy calls bind a real dummy byte and count zero, so every slot is uncovered. */
        uint32_t cr[2] = {coverage ? (uint32_t)(coverage->offset & 3u) : 0u, coverage ? coverage->count : 0u};
        if (coverage) {
            [enc setBuffer:coverageBuffer offset:(NSUInteger)(coverage->offset & ~UINT64_C(3)) atIndex:12];
        } else {
            uint32_t zero = 0u;
            [enc setBytes:&zero length:sizeof zero atIndex:12];
        }
        [enc setBytes:cr length:sizeof cr atIndex:13];
        if (sec && fused && sec->count > 0u) {
            uint32_t oc[8] = {occl->stamp, occl->boxCapacity, 0u, 0u, occl->visEntries, 0u, occl->prevKey, 0u};
            memcpy(&oc[5], &occl->eyeMin, 4);
            uint32_t sp[2] = {sec->visBase, sec->count};
            [enc setComputePipelineState:psos[7]];
            [enc setBuffer:MCMTL_BUFFER(sec->sitemsBuffer) offset:(NSUInteger)sec->sitemsOffset atIndex:0];
            [enc setBuffer:MCMTL_BUFFER(jobs[0].sectionsBuffer) offset:(NSUInteger)jobs[0].sectionsOffset atIndex:1];
            [enc setBytes:viewData length:sizeof viewData atIndex:2];
            [enc setBuffer:MCMTL_BUFFER(occl->visBuffer) offset:(NSUInteger)occl->visOffset atIndex:5];
            [enc setBuffer:MCMTL_BUFFER(occl->boxesBuffer) offset:(NSUInteger)occl->boxesOffset atIndex:6];
            [enc setBuffer:MCMTL_BUFFER(occl->paramsBuffer) offset:(NSUInteger)occl->paramsOffset atIndex:7];
            [enc setBytes:oc length:sizeof oc atIndex:8];
            [enc setBuffer:MCMTL_BUFFER(sec->secOccBuffer) offset:(NSUInteger)sec->secOccOffset atIndex:10];
            [enc setBytes:sp length:sizeof sp atIndex:11];
            [enc dispatchThreads:MTLSizeMake(sec->count, 1, 1) threadsPerThreadgroup:MTLSizeMake(256, 1, 1)];
            [enc memoryBarrierWithScope:MTLBarrierScopeBuffers];
        }
        for (uint32_t j = 0; j < jobCount; j++) {
            const mcmtl_cluster_job *job = &jobs[j];
            uint32_t n = job->clusterCount;
            memcpy(viewData + 92, &n, 4);
            id<MTLBuffer> scratch = MCMTL_BUFFER(job->scratchBuffer);
            NSUInteger countsOffset = (NSUInteger)job->scratchOffset, offsetsOffset = (NSUInteger)(job->scratchOffset + 4u * (uint64_t)n);
            if (fused) {
                uint32_t oc[8] = {occl->stamp, occl->boxCapacity, jobExtra[2u * j] & 0x7FFFFFFFu, jobExtra[2u * j + 1u], occl->visEntries, 0u, occl->prevKey, jobExtra[2u * j] >> 31};
                memcpy(&oc[5], &occl->eyeMin, 4);
                [enc setComputePipelineState:psos[6]];
                [enc setBuffer:MCMTL_BUFFER(job->itemsBuffer) offset:(NSUInteger)job->itemsOffset atIndex:0];
                [enc setBuffer:MCMTL_BUFFER(job->sectionsBuffer) offset:(NSUInteger)job->sectionsOffset atIndex:1];
                [enc setBytes:viewData length:sizeof viewData atIndex:2];
                [enc setBuffer:MCMTL_BUFFER(job->argsBuffer) offset:(NSUInteger)job->argsOffset atIndex:3];
                [enc setBuffer:MCMTL_BUFFER(job->outItemsBuffer) offset:(NSUInteger)job->outItemsOffset atIndex:4];
                [enc setBuffer:MCMTL_BUFFER(occl->visBuffer) offset:(NSUInteger)occl->visOffset atIndex:5];
                [enc setBuffer:MCMTL_BUFFER(occl->boxesBuffer) offset:(NSUInteger)occl->boxesOffset atIndex:6];
                [enc setBuffer:MCMTL_BUFFER(occl->paramsBuffer) offset:(NSUInteger)occl->paramsOffset atIndex:7];
                [enc setBytes:oc length:sizeof oc atIndex:8];
                [enc setBuffer:MCMTL_BUFFER(job->quadsBuffer) offset:(NSUInteger)job->quadsOffset atIndex:9];
                [enc setBuffer:scratch offset:countsOffset atIndex:11];
                if (sec) {
                    [enc setBuffer:MCMTL_BUFFER(sec->secOccBuffer) offset:(NSUInteger)sec->secOccOffset atIndex:10];
                } else {
                    [enc setBuffer:MCMTL_BUFFER(occl->visBuffer) offset:(NSUInteger)occl->visOffset atIndex:10];
                }
                /* whole SIMD groups (the reservation is per SIMD group): round the grid up to 32 */
                [enc dispatchThreads:MTLSizeMake((n + 31u) & ~31u, 1, 1) threadsPerThreadgroup:MTLSizeMake(256, 1, 1)];
                continue;
            }
            [enc setComputePipelineState:psos[occl ? 4 : 0]];
            [enc setBuffer:MCMTL_BUFFER(job->itemsBuffer) offset:(NSUInteger)job->itemsOffset atIndex:0];
            [enc setBuffer:MCMTL_BUFFER(job->sectionsBuffer) offset:(NSUInteger)job->sectionsOffset atIndex:1];
            [enc setBytes:viewData length:sizeof viewData atIndex:2];
            [enc setBuffer:scratch offset:countsOffset atIndex:3];
            [enc setBuffer:MCMTL_BUFFER(job->outItemsBuffer) offset:(NSUInteger)job->outItemsOffset atIndex:4];
            if (occl) {
                uint32_t oc[8] = {occl->stamp, occl->boxCapacity, jobExtra[2u * j] & 0x7FFFFFFFu, jobExtra[2u * j + 1u], occl->visEntries, 0u, occl->prevKey, jobExtra[2u * j] >> 31};
                memcpy(&oc[5], &occl->eyeMin, 4);
                [enc setBuffer:MCMTL_BUFFER(occl->visBuffer) offset:(NSUInteger)occl->visOffset atIndex:5];
                [enc setBuffer:MCMTL_BUFFER(occl->boxesBuffer) offset:(NSUInteger)occl->boxesOffset atIndex:6];
                [enc setBuffer:MCMTL_BUFFER(occl->paramsBuffer) offset:(NSUInteger)occl->paramsOffset atIndex:7];
                [enc setBytes:oc length:sizeof oc atIndex:8];
            }
            [enc dispatchThreads:MTLSizeMake(n, 1, 1) threadsPerThreadgroup:MTLSizeMake(MIN((NSUInteger)256, (NSUInteger)n), 1, 1)];
            [enc setComputePipelineState:psos[1]];
            [enc setBuffer:scratch offset:countsOffset atIndex:0];
            [enc setBuffer:scratch offset:offsetsOffset atIndex:1];
            [enc setBuffer:MCMTL_BUFFER(job->argsBuffer) offset:(NSUInteger)job->argsOffset atIndex:2];
            [enc setBytes:&n length:4 atIndex:3];
            [enc dispatchThreadgroups:MTLSizeMake(1, 1, 1) threadsPerThreadgroup:MTLSizeMake(1024, 1, 1)];
            [enc setComputePipelineState:psos[lodLists ? 3 : 2]];
            [enc setBuffer:scratch offset:countsOffset atIndex:0];
            [enc setBuffer:scratch offset:offsetsOffset atIndex:1];
            [enc setBuffer:MCMTL_BUFFER(job->quadsBuffer) offset:(NSUInteger)job->quadsOffset atIndex:2];
            [enc setBytes:&n length:4 atIndex:3];
            if (lodLists) {
                [enc dispatchThreads:MTLSizeMake(n, 1, 1) threadsPerThreadgroup:MTLSizeMake(MIN((NSUInteger)256, (NSUInteger)n), 1, 1)];
            } else {
                [enc dispatchThreads:MTLSizeMake(64u * (NSUInteger)n, 1, 1) threadsPerThreadgroup:MTLSizeMake(256, 1, 1)];
            }
        }
        if (fused) {
            /* Ordered (translucent) jobs: scan their counts, then write their quads in cluster order. */
            BOOL ordered = NO;
            for (uint32_t j = 0; j < jobCount; j++) ordered |= (jobExtra[2u * j] >> 31) != 0u;
            if (ordered) {
                [enc memoryBarrierWithScope:MTLBarrierScopeBuffers];
                for (uint32_t pass = 0; pass < 2; pass++) {
                    if (pass == 1) [enc memoryBarrierWithScope:MTLBarrierScopeBuffers];
                    for (uint32_t j = 0; j < jobCount; j++) {
                        if ((jobExtra[2u * j] >> 31) == 0u) continue;
                        const mcmtl_cluster_job *job = &jobs[j];
                        uint32_t n = job->clusterCount;
                        id<MTLBuffer> scratch = MCMTL_BUFFER(job->scratchBuffer);
                        NSUInteger countsOffset = (NSUInteger)job->scratchOffset;
                        uint32_t groups = (n + 255u) / 256u;
                        NSUInteger groupsOffset = (NSUInteger)(job->scratchOffset + 8u * (uint64_t)n);
                        if (pass == 0) {
                            [enc setComputePipelineState:psos[1]];
                            [enc setBuffer:scratch offset:groupsOffset atIndex:0];
                            [enc setBuffer:scratch offset:groupsOffset + 4u * groups atIndex:1];
                            [enc setBuffer:MCMTL_BUFFER(job->argsBuffer) offset:(NSUInteger)job->argsOffset atIndex:2];
                            [enc setBytes:&groups length:4 atIndex:3];
                            [enc dispatchThreadgroups:MTLSizeMake(1, 1, 1) threadsPerThreadgroup:MTLSizeMake(1024, 1, 1)];
                        } else {
                            [enc setComputePipelineState:psos[8]];
                            [enc setBuffer:scratch offset:countsOffset atIndex:0];
                            [enc setBuffer:scratch offset:groupsOffset + 4u * groups atIndex:1];
                            [enc setBuffer:MCMTL_BUFFER(job->quadsBuffer) offset:(NSUInteger)job->quadsOffset atIndex:2];
                            [enc setBytes:&n length:4 atIndex:3];
                            [enc dispatchThreads:MTLSizeMake(n, 1, 1) threadsPerThreadgroup:MTLSizeMake(MIN((NSUInteger)256, (NSUInteger)n), 1, 1)];
                        }
                    }
                }
            }
        }
        if (occl) {
            if (fused) [enc memoryBarrierWithScope:MTLBarrierScopeBuffers];
            uint32_t cap = occl->boxCapacity;
            [enc setComputePipelineState:psos[5]];
            [enc setBuffer:MCMTL_BUFFER(occl->paramsBuffer) offset:(NSUInteger)occl->paramsOffset atIndex:0];
            [enc setBytes:&cap length:4 atIndex:1];
            [enc dispatchThreads:MTLSizeMake(1, 1, 1) threadsPerThreadgroup:MTLSizeMake(1, 1, 1)];
        }
        [enc endEncoding];
        if (!supplied) {
            [cb addCompletedHandler:^(id<MTLCommandBuffer> done) {
                double t = done.GPUEndTime - done.GPUStartTime;
                if (t > 0.0 && t < 1.0) { mcmtl_cull_ns += (uint64_t)(t * 1e9); mcmtl_cull_n += 1; }
            }];
            [cb commit];
        }
        return MCMTL_OK;
    MCMTL_TRY_END(MCMTL_E_OBJC_EXCEPTION)
}
