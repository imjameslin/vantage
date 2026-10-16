/*
 * lod_mesh_bench.m -- stage 3 round 4: headless GPU comparison of the two ways to draw LOD quads on the M5
 * (results/STAGE3-R4.md "Mesh shaders"):
 *
 *   A  "indirect"  today's path (LodGpu + mcmetal_clusters.m + TerrainVertexMsl.clusterVertexCompact): a compute kernel
 *                  culls 64-quad clusters (frustum + facing) and appends one uint per surviving quad; one indexed indirect
 *                  draw (6 indices per quad) whose vertex function decodes the quad's 48-byte record per corner.
 *   M  "mesh"      object + mesh shader: the object stage culls 32 clusters per threadgroup (same test) and launches one mesh
 *                  threadgroup per surviving cluster; 64 mesh threads decode one record each (once per quad, not per corner)
 *                  and emit 4 vertices + 2 triangles. No compute pass, no quad list, no index buffer.
 *   MQ "mesh+qc"   M plus per-quad culling in the mesh stage (quad outside the frustum, back-facing, or covering no pixel
 *                  centre: primitive_culled).
 *
 * Scene: synthetic LOD rings like the game's at LOD 256 / tau 8 (level k cells of 2^k blocks between 2^k K / tau * [1, 2)
 * blocks; heightfield terrain + forest canopy columns), every exposed cell face one quad (no merging), 64-quad clusters per
 * (16x16-cell section, facing). Camera at y 180, pitch 18 (the vista). Offscreen 3024x1964 BGRA8 + depth32F, reversed Z.
 * Same fragment function for all paths (atlas texture * colour, fog mix). Median GPU time per command buffer.
 *
 * Build and run: tools/lod-mesh-bench.sh [--frames N] [--tau T] [--yaw DEG]
 */
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#include <math.h>
#include <stdlib.h>
#include <string.h>

static int W = 3024;
static int H = 1964;

typedef struct { float x, y, z, unit; } SecInfo;
typedef struct { uint32_t first; uint32_t count; uint32_t section; uint32_t facing; float mn[3]; float mx[3]; } Cluster;
typedef struct { uint32_t w[12]; } Rec;

static float terrain(float x, float z) {
    return 72.0f + 18.0f * sinf(x / 97.0f) * cosf(z / 131.0f) + 7.0f * sinf(x / 23.0f + z / 37.0f) + 3.0f * cosf(z / 11.0f - x / 17.0f);
}
static int cmpd(const void *a, const void *b) { double x = *(const double *)a, y = *(const double *)b; return x < y ? -1 : x > y; }
static uint32_t hash2(int x, int z) {
    uint32_t h = (uint32_t)x * 0x9E3779B1u ^ (uint32_t)z * 0x85EBCA77u;
    h ^= h >> 15; h *= 0x2C1B3C6Du; h ^= h >> 12; h *= 0x297A2D39u; h ^= h >> 15;
    return h;
}

static NSString *SRC = @
"#include <metal_stdlib>\n"
"using namespace metal;\n"
"struct SecInfo { float x, y, z, unit; };\n"
"struct Cluster { uint first; uint count; uint section; uint facing; float mn[3]; float mx[3]; };\n"
"struct View { float4x4 vp; float4 planes[6]; float4 cam; float2 vpSize; };\n"
"struct VOut { float4 pos [[position]]; float4 color; float2 uv; float fog; float fog2; int2 tile [[flat]]; };\n"
"static inline bool visibleCluster(constant View& v, device const Cluster& c, device const SecInfo* secs) {\n"
"  SecInfo s = secs[c.section];\n"
"  float3 o = float3(s.x, s.y, s.z);\n"
"  float3 mn = o + float3(c.mn[0], c.mn[1], c.mn[2]) * s.unit;\n"
"  float3 mx = o + float3(c.mx[0], c.mx[1], c.mx[2]) * s.unit;\n"
"  for (int i = 0; i < 6; i++) { float4 p = v.planes[i]; float3 q = select(mn, mx, p.xyz > 0.0); if (dot(p.xyz, q) + p.w < 0.0) return false; }\n"
"  float3 cam = v.cam.xyz;\n"
"  switch (c.facing) { case 0: return cam.y < mx.y; case 1: return cam.y > mn.y; case 2: return cam.z < mx.z; case 3: return cam.z > mn.z;\n"
"    case 4: return cam.x < mx.x; default: return cam.x > mn.x; }\n"
"}\n"
"constant uint QMASK [[function_constant(0)]];\n"
"static inline bool quadCulled(float4 clip[4], uint mask, float2 vpSize) {\n"
"  bool outX0 = true, outX1 = true, outY0 = true, outY1 = true, outZ = true; float2 lo = float2(1e9), hi = float2(-1e9);\n"
"  for (uint k = 0; k < 4; k++) { float4 q = clip[k]; if (q.w <= 0.0) return false;\n"
"    outX0 = outX0 && q.x < -q.w; outX1 = outX1 && q.x > q.w; outY0 = outY0 && q.y < -q.w; outY1 = outY1 && q.y > q.w;\n"
"    float2 sp = (q.xy / q.w * 0.5 + 0.5) * vpSize; lo = min(lo, sp); hi = max(hi, sp); }\n"
"  if ((mask & 1u) && (outX0 || outX1 || outY0 || outY1)) return true;\n"
"  lo -= 1.0 / 256.0; hi += 1.0 / 256.0;\n"
"  if ((mask & 4u) && any(floor(hi - 0.5) < ceil(lo - 0.5))) return true;\n"
"  return false;\n"
"}\n"
"struct Corner { float3 p; float4 col; float2 uv; };\n"
"static inline Corner decode(device const uint4* rb, uint q, uint k, float3 origin, float unit) {\n"
"  uint4 a = rb[3u * q]; uint4 b = rb[3u * q + 1u]; uint4 c = rb[3u * q + 2u];\n"
"  float fa = (k == 1u || k == 2u) ? 1.0 : 0.0; float fb = (k >= 2u) ? 1.0 : 0.0;\n"
"  float3 p = float3(float(a.x & 1023u), float((a.x >> 10u) & 1023u), float((a.x >> 20u) & 1023u));\n"
"  float l1 = float(int(a.y << 18u) >> 20); float l2 = float(int(a.y << 4u) >> 20);\n"
"  uint x1 = a.y & 3u; uint x2 = (a.y >> 14u) & 3u;\n"
"  p += fa * l1 * float3(x1 == 0u, x1 == 1u, x1 == 2u);\n"
"  p += fb * l2 * float3(x2 == 0u, x2 == 1u, x2 == 2u);\n"
"  Corner o; o.p = origin + p * (unit * 0.03125);\n"
"  uint col = k == 0u ? a.z : k == 1u ? a.w : k == 2u ? b.x : b.y;\n"
"  o.col = unpack_unorm4x8_to_float(col);\n"
"  float2 d1 = float2(float(int(c.z << 16u) >> 16), float(int(c.z) >> 16));\n"
"  float2 d2 = float2(float(int(c.w << 16u) >> 16), float(int(c.w) >> 16));\n"
"  o.uv = (float2(float(c.x & 1048575u), float(c.y & 1048575u)) + fa * d1 + fb * d2) * (1.0 / 4096.0);\n"
"  return o;\n"
"}\n"
"static inline VOut shade(constant View& v, Corner c, uint light, texture2d<float> lm) {\n"
"  constexpr sampler ls(filter::linear, address::clamp_to_edge);\n"
"  VOut o; float3 rel = c.p - v.cam.xyz; o.pos = v.vp * float4(rel, 1.0);\n"
"  int2 uv2 = int2(light & 255u, (light >> 8u) & 255u);\n"
"  o.color = c.col * lm.sample(ls, clamp(float2(uv2) / 256.0 + 0.03125, 0.03125, 0.96875), level(0.0)); o.uv = c.uv; o.fog = length(rel);\n"
"  o.fog2 = max(length(rel.xz), abs(rel.y)); o.tile = int2(light >> 16u & 255u, light >> 24u); return o;\n"
"}\n"
// ---- A: compute cull + indexed indirect draw
"struct Args { atomic_uint indexCount; uint instanceCount; uint indexStart; int baseVertex; uint baseInstance; };\n"
"kernel void cullA(device const Cluster* cl [[buffer(0)]], device const SecInfo* secs [[buffer(1)]], constant View& v [[buffer(2)]],\n"
"   device Args* args [[buffer(3)]], device uint* quads [[buffer(4)]], constant uint& n [[buffer(5)]], uint tid [[thread_position_in_grid]]) {\n"
"  if (tid >= n) return;\n"
"  device const Cluster& c = cl[tid];\n"
"  if (!visibleCluster(v, c, secs)) return;\n"
"  uint base = atomic_fetch_add_explicit(&args->indexCount, 6u * c.count, memory_order_relaxed) / 6u;\n"
"  for (uint i = 0; i < c.count; i++) quads[base + i] = tid << 6 | i;\n"
"}\n"
"kernel void cullAQ(device const Cluster* cl [[buffer(0)]], device const SecInfo* secs [[buffer(1)]], constant View& v [[buffer(2)]],\n"
"   device Args* args [[buffer(3)]], device uint* quads [[buffer(4)]], constant uint& n [[buffer(5)]], device const uint4* rb [[buffer(6)]],\n"
"   uint tg [[threadgroup_position_in_grid]], uint lid [[thread_index_in_threadgroup]]) {\n"
"  if (tg >= n) return;\n"
"  device const Cluster& c = cl[tg];\n"
"  bool keep = lid < c.count && visibleCluster(v, c, secs);\n"
"  if (keep) { SecInfo s = secs[c.section]; float4 clip[4];\n"
"    for (uint k = 0; k < 4; k++) clip[k] = v.vp * float4(decode(rb, c.first + lid, k, float3(s.x, s.y, s.z), s.unit).p - v.cam.xyz, 1.0);\n"
"    keep = !quadCulled(clip, QMASK, v.vpSize); }\n"
"  uint slot = simd_prefix_exclusive_sum(keep ? 1u : 0u); uint total = simd_sum(keep ? 1u : 0u);\n"
"  uint base = 0; if (simd_is_first() && total > 0) base = atomic_fetch_add_explicit(&args->indexCount, 6u * total, memory_order_relaxed) / 6u;\n"
"  base = simd_broadcast_first(base);\n"
"  if (keep) quads[base + slot] = tg << 6 | lid;\n"
"}\n"
"struct XV { packed_float3 p; uint col; float2 uv; uint light; };\n"
"kernel void expandX(device const Cluster* cl [[buffer(0)]], device const SecInfo* secs [[buffer(1)]], constant View& v [[buffer(2)]],\n"
"   device Args* args [[buffer(3)]], device XV* out [[buffer(4)]], constant uint& n [[buffer(5)]], device const uint4* rb [[buffer(6)]],\n"
"   uint tg [[threadgroup_position_in_grid]], uint lid [[thread_index_in_threadgroup]]) {\n"
"  if (tg >= n) return;\n"
"  device const Cluster& c = cl[tg];\n"
"  bool keep = lid < c.count && visibleCluster(v, c, secs);\n"
"  uint slot = simd_prefix_exclusive_sum(keep ? 1u : 0u); uint total = simd_sum(keep ? 1u : 0u);\n"
"  uint base = 0; if (simd_is_first() && total > 0) base = atomic_fetch_add_explicit(&args->indexCount, 6u * total, memory_order_relaxed) / 6u;\n"
"  base = simd_broadcast_first(base);\n"
"  if (!keep) return;\n"
"  SecInfo s = secs[c.section]; uint q = c.first + lid; uint light = rb[3u * q + 1u].z;\n"
"  for (uint k = 0; k < 4; k++) { Corner cc = decode(rb, q, k, float3(s.x, s.y, s.z), s.unit); XV x; x.p = packed_float3(cc.p - v.cam.xyz);\n"
"    x.col = pack_float_to_unorm4x8(cc.col); x.uv = cc.uv; x.light = light; out[4u * (base + slot) + k] = x; }\n"
"}\n"
"vertex VOut vsX(uint vid [[vertex_id]], device const XV* xv [[buffer(0)]], constant View& v [[buffer(4)]], texture2d<float> lm [[texture(1)]]) {\n"
"  XV x = xv[vid]; Corner c; c.p = float3(x.p) + v.cam.xyz; c.col = unpack_unorm4x8_to_float(x.col); c.uv = x.uv;\n"
"  return shade(v, c, x.light, lm);\n"
"}\n"
"vertex VOut vsA(uint vid [[vertex_id]], device const uint* quads [[buffer(0)]], device const Cluster* cl [[buffer(1)]], device const SecInfo* secs [[buffer(2)]],\n"
"   device const uint4* rb [[buffer(3)]], constant View& v [[buffer(4)]], texture2d<float> lm [[texture(1)]]) {\n"
"  uint e = quads[vid >> 2u]; device const Cluster& c = cl[e >> 6u]; SecInfo s = secs[c.section];\n"
"  uint q = c.first + (e & 63u);\n"
"  VOut o = shade(v, decode(rb, q, vid & 3u, float3(s.x, s.y, s.z), s.unit), rb[3u * q + 1u].z, lm);\n"
"  if (QMASK & 64u) o.pos = float4(2.0, 2.0, 2.0, 1.0);\n"
"  return o;\n"
"}\n"
"fragment float4 fs(VOut in [[stage_in]], texture2d<float> tex [[texture(0)]], sampler sm [[sampler(0)]]) {\n"
"  if (QMASK & 128u) return in.color;\n"
"  float4 t = tex.sample(sm, in.uv) * in.color;\n"
"  return float4(mix(t.rgb, float3(0.75, 0.82, 1.0), clamp((in.fog - 300.0) / 4000.0, 0.0, 1.0)), 1.0);\n"
"}\n"
// ---- M / MQ: object + mesh
"struct Payload { uint idx[32]; };\n"
"[[object]] void osM(object_data Payload& pl [[payload]], mesh_grid_properties mgp, device const Cluster* cl [[buffer(0)]],\n"
"   device const SecInfo* secs [[buffer(1)]], constant View& v [[buffer(2)]], constant uint& n [[buffer(3)]],\n"
"   uint tid [[thread_position_in_grid]], uint lid [[thread_index_in_threadgroup]]) {\n"
"  bool vis = tid < n && visibleCluster(v, cl[tid], secs);\n"
"  uint slot = simd_prefix_exclusive_sum(vis ? 1u : 0u);\n"
"  if (vis) pl.idx[slot] = tid;\n"
"  uint total = simd_sum(vis ? 1u : 0u);\n"
"  if (lid == 0) mgp.set_threadgroups_per_grid(uint3(total, 1, 1));\n"
"}\n"
"struct MV { float4 pos [[position]]; float4 color; float2 uv; float fog; float fog2; int2 tile [[flat]]; };\n"
"struct MP { bool culled [[primitive_culled]]; };\n"
"using MeshT = metal::mesh<MV, MP, 256, 128, metal::topology::triangle>;\n"
"template <bool QC> static inline void meshBody(MeshT m, const object_data Payload& pl, uint gid, uint lid, device const Cluster* cl,\n"
"   device const SecInfo* secs, device const uint4* rb, constant View& v, texture2d<float> lm) {\n"
"  constexpr sampler ls(filter::linear, address::clamp_to_edge);\n"
"  device const Cluster& c = cl[pl.idx[gid]];\n"
"  uint n = c.count;\n"
"  if (lid == 0) m.set_primitive_count(2u * n);\n"
"  if (lid >= n) return;\n"
"  SecInfo s = secs[c.section]; float3 o = float3(s.x, s.y, s.z);\n"
"  device const uint4* r = rb + 3u * (c.first + lid);\n"
"  uint4 a = r[0]; uint4 b = r[1]; uint4 cc = r[2];\n"
"  float3 p0 = float3(float(a.x & 1023u), float((a.x >> 10u) & 1023u), float((a.x >> 20u) & 1023u));\n"
"  uint x1 = a.y & 3u; uint x2 = (a.y >> 14u) & 3u;\n"
"  float3 e1 = float(int(a.y << 18u) >> 20) * float3(x1 == 0u, x1 == 1u, x1 == 2u);\n"
"  float3 e2 = float(int(a.y << 4u) >> 20) * float3(x2 == 0u, x2 == 1u, x2 == 2u);\n"
"  float sc = s.unit * 0.03125;\n"
"  float2 uv0 = float2(float(cc.x & 1048575u), float(cc.y & 1048575u));\n"
"  float2 d1 = float2(float(int(cc.z << 16u) >> 16), float(int(cc.z) >> 16));\n"
"  float2 d2 = float2(float(int(cc.w << 16u) >> 16), float(int(cc.w) >> 16));\n"
"  uint cols[4] = {a.z, a.w, b.x, b.y};\n"
"  float4 clip[4];\n"
"  for (uint k = 0; k < 4; k++) {\n"
"    float fa = (k == 1u || k == 2u) ? 1.0 : 0.0; float fb = (k >= 2u) ? 1.0 : 0.0;\n"
"    float3 rel = o + (p0 + fa * e1 + fb * e2) * sc - v.cam.xyz;\n"
"    MV out; out.pos = v.vp * float4(rel, 1.0); uint light = b.z;\n"
"    int2 uv2 = int2(light & 255u, (light >> 8u) & 255u);\n"
"    out.color = unpack_unorm4x8_to_float(cols[k]) * lm.sample(ls, clamp(float2(uv2) / 256.0 + 0.03125, 0.03125, 0.96875), level(0.0));\n"
"    out.fog2 = max(length(rel.xz), abs(rel.y)); out.tile = int2(light >> 16u & 255u, light >> 24u);\n"
"    out.uv = (uv0 + fa * d1 + fb * d2) * (1.0 / 4096.0); out.fog = length(rel);\n"
"    clip[k] = out.pos;\n"
"    m.set_vertex(4u * lid + k, out);\n"
"  }\n"
"  uint vb = 4u * lid;\n"
"  m.set_index(6u * lid + 0u, vb); m.set_index(6u * lid + 1u, vb + 1u); m.set_index(6u * lid + 2u, vb + 2u);\n"
"  m.set_index(6u * lid + 3u, vb + 2u); m.set_index(6u * lid + 4u, vb + 3u); m.set_index(6u * lid + 5u, vb);\n"
"  bool cull = QC && quadCulled(clip, QMASK, v.vpSize);\n"
"  MP pr; pr.culled = cull; m.set_primitive(2u * lid, pr); m.set_primitive(2u * lid + 1u, pr);\n"
"}\n"
"[[mesh]] void msM(MeshT m, const object_data Payload& pl [[payload]], uint gid [[threadgroup_position_in_grid]], uint lid [[thread_index_in_threadgroup]],\n"
"   device const Cluster* cl [[buffer(0)]], device const SecInfo* secs [[buffer(1)]], device const uint4* rb [[buffer(2)]], constant View& v [[buffer(3)]], texture2d<float> lm [[texture(1)]]) {\n"
"  meshBody<false>(m, pl, gid, lid, cl, secs, rb, v, lm);\n"
"}\n"
"[[mesh]] void msMQ(MeshT m, const object_data Payload& pl [[payload]], uint gid [[threadgroup_position_in_grid]], uint lid [[thread_index_in_threadgroup]],\n"
"   device const Cluster* cl [[buffer(0)]], device const SecInfo* secs [[buffer(1)]], device const uint4* rb [[buffer(2)]], constant View& v [[buffer(3)]], texture2d<float> lm [[texture(1)]]) {\n"
"  meshBody<true>(m, pl, gid, lid, cl, secs, rb, v, lm);\n"
"}\n"
"struct FIn { float4 pos [[position]]; float4 color; float2 uv; float fog; float fog2; int2 tile [[flat]]; };\n"
"fragment float4 fsM(FIn in [[stage_in]], texture2d<float> tex [[texture(0)]], sampler sm [[sampler(0)]]) {\n"
"  float4 t = tex.sample(sm, in.uv) * in.color;\n"
"  return float4(mix(t.rgb, float3(0.75, 0.82, 1.0), clamp((in.fog - 300.0) / 4000.0, 0.0, 1.0)), 1.0);\n"
"}\n";

/* ---------------------------------------------------------------------------------------------------- scene building */
static Rec *recs; static size_t nrec, caprec;
static Cluster *cls; static size_t ncl, capcl;
static SecInfo *secs; static size_t nsec, capsec;

static void pushRec(Rec r) { if (nrec == caprec) { caprec = caprec ? caprec * 2 : 1 << 20; recs = realloc(recs, caprec * sizeof(Rec)); } recs[nrec++] = r; }

/* quad in section-local cell coords: corner (x,y,z), edges along axes a1 (len l1) and a2 (len l2), all in cells */
static void quad(float x, float y, float z, int a1, float l1, int a2, float l2, uint32_t col) {
    Rec r; memset(&r, 0, sizeof r);
    int px = (int)lrintf(x * 32), py = (int)lrintf(y * 32), pz = (int)lrintf(z * 32);
    if (px < 0 || px > 1023 || py < 0 || py > 1023 || pz < 0 || pz > 1023) return;
    int L1 = (int)lrintf(l1 * 32), L2 = (int)lrintf(l2 * 32);
    r.w[0] = px | py << 10 | pz << 20;
    r.w[1] = (a1 | (L1 & 4095) << 2) | (a2 | (L2 & 4095) << 2) << 14;
    r.w[2] = r.w[3] = r.w[4] = r.w[5] = col;
    uint32_t tile = hash2(px, pz) & 15;
    r.w[6] = r.w[7] = 0x00F000F0u;
    r.w[8] = (tile * 256) ; r.w[9] = (hash2(pz, py) & 15) * 256;
    r.w[10] = 256 & 0xFFFF; r.w[11] = 256u << 16;
    pushRec(r);
}

int main(int argc, char **argv) {
    @autoreleasepool {
        int frames = 60; int onlyMask = -1; float tau = 8.0f; float yawDeg = 200.0f; float radius = 4096.0f;
        for (int i = 1; i < argc; i++) {
            if (!strcmp(argv[i], "--frames") && i + 1 < argc) frames = atoi(argv[++i]);
            else if (!strcmp(argv[i], "--tau") && i + 1 < argc) tau = atof(argv[++i]);
            else if (!strcmp(argv[i], "--yaw") && i + 1 < argc) yawDeg = atof(argv[++i]);
            else if (!strcmp(argv[i], "--radius") && i + 1 < argc) radius = atof(argv[++i]);
            else if (!strcmp(argv[i], "--res") && i + 2 < argc) { W = atoi(argv[i + 1]); H = atoi(argv[i + 2]); i += 2; }
            else if (!strcmp(argv[i], "--only") && i + 1 < argc) onlyMask = atoi(argv[++i]);
        }
        const float K = 1402.0f, real = 192.0f;
        /* rings: level k between max(real, 2^k K / tau) and 2^(k+1) K / tau (last level to radius) */
        for (int k = 0; k <= 5; k++) {
            float c = (float)(1 << k);
            float r0 = fmaxf(real, c * K / tau), r1 = k == 5 ? radius : fminf(radius, 2 * c * K / tau);
            if (r1 <= r0) continue;
            int sspan = 16 * (1 << k);
            int s0 = (int)floorf(-r1 / sspan), s1 = (int)ceilf(r1 / sspan);
            for (int sz = s0; sz < s1; sz++) for (int sx = s0; sx < s1; sx++) {
                float bx = sx * sspan, bz = sz * sspan;
                /* section must hold at least one cell centre within the ring */
                float hmin = 1e9f; int any = 0;
                int top[18][18];
                for (int j = -1; j <= 16; j++) for (int i = -1; i <= 16; i++) {
                    float cx = bx + (i + 0.5f) * c, cz = bz + (j + 0.5f) * c;
                    int g = (int)floorf(terrain(cx, cz) / c);
                    /* forest: share of tree columns in the cell (4 % of block columns), canopy ~8 blocks above ground */
                    int trees = 0; int n = (int)c;
                    for (int dz = 0; dz < n && dz < 4; dz++) for (int dx = 0; dx < n && dx < 4; dx++)
                        trees += (hash2((int)(bx + i * c) + dx * (n / 4 > 0 ? n / 4 : 1), (int)(bz + j * c) + dz * (n / 4 > 0 ? n / 4 : 1)) & 255) < 60;
                    int t = g;
                    if (trees > 0) t = g + (int)ceilf(8.0f / c);
                    top[j + 1][i + 1] = t;
                    if (i >= 0 && i < 16 && j >= 0 && j < 16) {
                        float d = hypotf(cx, cz);
                        if (d >= r0 && d < r1) any = 1;
                        if (g < hmin) hmin = g;
                    }
                }
                if (!any) continue;
                int oy = (int)hmin - 2;
                size_t first = nrec;
                /* faces per facing so clusters are single-facing: 0 down 1 up 2 north(-z) 3 south(+z) 4 west(-x) 5 east(+x) */
                size_t ffirst[6]; size_t fcount[6];
                for (int f = 1; f < 6; f++) {
                    ffirst[f] = nrec;
                    for (int j = 0; j < 16; j++) for (int i = 0; i < 16; i++) {
                        float cx = bx + (i + 0.5f) * c, cz = bz + (j + 0.5f) * c;
                        float d = hypotf(cx, cz);
                        if (d < r0 || d >= r1) continue;
                        int t = top[j + 1][i + 1];
                        float y = t - oy;
                        int canopy = t > (int)floorf(terrain(cx, cz) / c);
                        uint32_t col = canopy ? 0xFF406040u : 0xFFF0F0F0u;
                        if (f == 1) { quad(i, y, j, 2, 1, 0, 1, col); continue; }
                        int nt = f == 2 ? top[j][i + 1] : f == 3 ? top[j + 2][i + 1] : f == 4 ? top[j + 1][i] : top[j + 1][i + 2];
                        if (nt >= t) continue;
                        float h = fminf(t - nt, 31 - (nt - oy));
                        if (h <= 0) continue;
                        float yb = nt - oy;
                        uint32_t sc = canopy ? 0xFF305030u : 0xFF808890u;
                        if (f == 2) quad(i, yb, j, 1, h, 0, 1, sc);
                        else if (f == 3) quad(i, yb, j + 1, 0, 1, 1, h, sc);
                        else if (f == 4) quad(i, yb, j, 2, 1, 1, h, sc);
                        else quad(i + 1, yb, j, 1, h, 2, 1, sc);
                    }
                    fcount[f] = nrec - ffirst[f];
                }
                (void)first;
                if (nsec == capsec) { capsec = capsec ? capsec * 2 : 4096; secs = realloc(secs, capsec * sizeof(SecInfo)); }
                secs[nsec] = (SecInfo){bx, oy * c, bz, c};
                for (int f = 1; f < 6; f++) {
                    for (size_t q = 0; q < fcount[f]; q += 64) {
                        if (ncl == capcl) { capcl = capcl ? capcl * 2 : 1 << 16; cls = realloc(cls, capcl * sizeof(Cluster)); }
                        Cluster cc = {(uint32_t)(ffirst[f] + q), (uint32_t)(fcount[f] - q < 64 ? fcount[f] - q : 64), (uint32_t)nsec, (uint32_t)f,
                                      {1e9f, 1e9f, 1e9f}, {-1e9f, -1e9f, -1e9f}};
                        for (uint32_t u = 0; u < cc.count; u++) {
                            Rec *r = &recs[cc.first + u];
                            float p[3] = {(r->w[0] & 1023) / 32.0f, (r->w[0] >> 10 & 1023) / 32.0f, (r->w[0] >> 20 & 1023) / 32.0f};
                            int a1 = r->w[1] & 3, a2 = r->w[1] >> 14 & 3;
                            float l1 = ((int32_t)(r->w[1] << 18) >> 20) / 32.0f, l2 = ((int32_t)(r->w[1] << 4) >> 20) / 32.0f;
                            for (int k = 0; k < 4; k++) {
                                float q3[3] = {p[0], p[1], p[2]};
                                if (k == 1 || k == 2) q3[a1] += l1;
                                if (k >= 2) q3[a2] += l2;
                                for (int a = 0; a < 3; a++) { cc.mn[a] = fminf(cc.mn[a], q3[a]); cc.mx[a] = fmaxf(cc.mx[a], q3[a]); }
                            }
                        }
                        cls[ncl++] = cc;
                    }
                }
                nsec++;
            }
        }
        /* facing ids in the shader: 0 down 1 up 2 north 3 south 4 west 5 east (camera tests use the cluster box) */
        printf("scene: %zu quads, %zu clusters, %zu sections (tau %.1f, radius %.0f, yaw %.0f)\n", nrec, ncl, nsec, tau, radius, yawDeg);

        id<MTLDevice> dev = MTLCreateSystemDefaultDevice();
        printf("device: %s, mesh shaders %s\n", dev.name.UTF8String, [dev supportsFamily:MTLGPUFamilyMetal3] ? "yes (Metal 3)" : "no");
        NSError *err = nil;
        MTLCompileOptions *co = [MTLCompileOptions new];
        co.languageVersion = MTLLanguageVersion3_1;
        id<MTLLibrary> lib = [dev newLibraryWithSource:SRC options:co error:&err];
        if (!lib) { printf("compile: %s\n", err.description.UTF8String); return 1; }
        id<MTLBuffer> recB = [dev newBufferWithBytes:recs length:nrec * sizeof(Rec) options:MTLResourceStorageModeShared];
        id<MTLBuffer> clB = [dev newBufferWithBytes:cls length:ncl * sizeof(Cluster) options:MTLResourceStorageModeShared];
        id<MTLBuffer> secB = [dev newBufferWithBytes:secs length:nsec * sizeof(SecInfo) options:MTLResourceStorageModeShared];
        id<MTLBuffer> quadB = [dev newBufferWithLength:nrec * 4 options:MTLResourceStorageModePrivate];
        id<MTLBuffer> xB = [dev newBufferWithLength:nrec * 4 * 28 options:MTLResourceStorageModePrivate];
        id<MTLBuffer> argB = [dev newBufferWithLength:64 options:MTLResourceStorageModeShared];
        size_t nidx = nrec * 6;
        uint32_t *idx = malloc(nidx * 4);
        for (size_t q = 0; q < nrec; q++) { uint32_t v = 4 * q; uint32_t s[6] = {v, v + 1, v + 2, v + 2, v + 3, v}; memcpy(idx + 6 * q, s, 24); }
        id<MTLBuffer> idxB = [dev newBufferWithBytes:idx length:nidx * 4 options:MTLResourceStorageModeShared];
        free(idx);

        /* view: camera at (6.5, 180, 6.5), pitch 18 down, yaw (Minecraft: yaw 0 = +z, 90 = -x) */
        struct { float vp[16]; float planes[24]; float cam[4]; float vpSize[2]; float pad[2]; } view;
        memset(&view, 0, sizeof view);
        float yaw = yawDeg * M_PI / 180.0f, pitch = 18.0f * M_PI / 180.0f;
        float fx = -sinf(yaw) * cosf(pitch), fy = -sinf(pitch), fz = cosf(yaw) * cosf(pitch);
        float rx = cosf(yaw), rz = sinf(yaw);   /* right = forward x up, horizontal */
        float ux = -rz * fy * 0 + 0, uy = 0, uz = 0;
        /* up = right x forward */
        ux = 0 * fz - rz * fy; uy = rz * fx - rx * fz; uz = rx * fy - 0 * fx;
        /* hmm: right = (rx, 0, rz); up = right x forward */
        ux = 0.0f * fz - rz * fy; uy = rz * fx - rx * fz; uz = rx * fy - 0.0f * fx;
        float fov = 70.0f * M_PI / 180.0f, aspect = (float)W / H, near = 0.05f;
        float f = 1.0f / tanf(fov / 2);
        /* view matrix rows: right, up, -forward ; reversed-Z infinite projection: clip.z = near, clip.w = -zview */
        float V[16] = {rx, ux, -fx, 0, 0, uy, -fy, 0, rz, uz, -fz, 0, 0, 0, 0, 1};   /* column-major */
        float P[16] = {f / aspect, 0, 0, 0, 0, f, 0, 0, 0, 0, 0, -1, 0, 0, near, 0};
        for (int c2 = 0; c2 < 4; c2++) for (int r2 = 0; r2 < 4; r2++) {
            float s = 0; for (int k = 0; k < 4; k++) s += P[k * 4 + r2] * V[c2 * 4 + k];
            view.vp[c2 * 4 + r2] = s;
        }
        /* frustum planes from vp rows (camera-relative => add cam later): row i of M */
        float *M = view.vp;
        #define ROW(i, k) M[(k) * 4 + (i)]
        float pl[6][4];
        for (int k = 0; k < 4; k++) {
            pl[0][k] = ROW(3, k) + ROW(0, k); pl[1][k] = ROW(3, k) - ROW(0, k);
            pl[2][k] = ROW(3, k) + ROW(1, k); pl[3][k] = ROW(3, k) - ROW(1, k);
            pl[4][k] = ROW(2, k); pl[5][k] = ROW(3, k) - ROW(2, k);
        }
        float cam[3] = {6.5f, 180.0f, 6.5f};
        for (int i = 0; i < 6; i++) {
            float w = pl[i][3] - (pl[i][0] * cam[0] + pl[i][1] * cam[1] + pl[i][2] * cam[2]);
            view.planes[i * 4] = pl[i][0]; view.planes[i * 4 + 1] = pl[i][1]; view.planes[i * 4 + 2] = pl[i][2]; view.planes[i * 4 + 3] = w;
        }
        view.planes[5 * 4] = 0; view.planes[5 * 4 + 1] = 0; view.planes[5 * 4 + 2] = 0; view.planes[5 * 4 + 3] = 1;   /* no far plane */
        view.cam[0] = cam[0]; view.cam[1] = cam[1]; view.cam[2] = cam[2];
        view.vpSize[0] = W; view.vpSize[1] = H;
        /* facing ids: swap to the shader's convention (the cluster's facing f = 1 up, 2 north, 3 south, 4 west, 5 east) */

        /* targets */
        MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm width:W height:H mipmapped:NO];
        td.usage = MTLTextureUsageRenderTarget; td.storageMode = MTLStorageModePrivate;
        id<MTLTexture> color = [dev newTextureWithDescriptor:td];
        MTLTextureDescriptor *dd = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatDepth32Float width:W height:H mipmapped:NO];
        dd.usage = MTLTextureUsageRenderTarget; dd.storageMode = MTLStorageModeMemoryless;
        id<MTLTexture> depth = [dev newTextureWithDescriptor:dd];
        MTLTextureDescriptor *xd = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:4096 height:4096 mipmapped:YES];
        id<MTLTexture> atlas = [dev newTextureWithDescriptor:xd];
        {
            uint32_t *px = malloc(4096 * 4096 * 4);
            for (int i = 0; i < 4096 * 4096; i++) px[i] = 0xFF000000u | (hash2(i, i >> 12) & 0x3F3F3F) + 0x909090;
            [atlas replaceRegion:MTLRegionMake2D(0, 0, 4096, 4096) mipmapLevel:0 withBytes:px bytesPerRow:4096 * 4];
            free(px);
            id<MTLCommandBuffer> cb = [[dev newCommandQueue] commandBuffer];
            id<MTLBlitCommandEncoder> be = [cb blitCommandEncoder]; [be generateMipmapsForTexture:atlas]; [be endEncoding];
            [cb commit]; [cb waitUntilCompleted];
        }
        MTLTextureDescriptor *ld = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:16 height:16 mipmapped:NO];
        id<MTLTexture> lmT = [dev newTextureWithDescriptor:ld];
        { uint32_t px[256]; for (int i = 0; i < 256; i++) px[i] = 0xFFFFFFFFu - (uint32_t)(i * 0x010101); [lmT replaceRegion:MTLRegionMake2D(0, 0, 16, 16) mipmapLevel:0 withBytes:px bytesPerRow:64]; }
        MTLSamplerDescriptor *sd = [MTLSamplerDescriptor new];
        sd.minFilter = MTLSamplerMinMagFilterNearest; sd.magFilter = MTLSamplerMinMagFilterNearest; sd.mipFilter = MTLSamplerMipFilterLinear;
        id<MTLSamplerState> samp = [dev newSamplerStateWithDescriptor:sd];
        MTLDepthStencilDescriptor *dsd = [MTLDepthStencilDescriptor new];
        dsd.depthCompareFunction = MTLCompareFunctionGreaterEqual; dsd.depthWriteEnabled = YES;
        id<MTLDepthStencilState> ds = [dev newDepthStencilStateWithDescriptor:dsd];

        /* paths: kind 0 empty, 1 compute + indirect (mask: per-quad cull in the compute kernel), 2 object + mesh (mask in the mesh stage) */
        typedef struct { const char *name; int kind; uint32_t mask; } Path;
        Path paths[] = {{"empty", 0, 0}, {"A indirect", 1, 0}, {"AQ1 A+frustum/q", 1, 1}, {"AQ4 A+nosample/q", 1, 4}, {"AQ5 A+both/q", 1, 5},
                        {"X expand", 3, 0}, {"A novs-raster", 1, 64}, {"A flat fs", 1, 128}, {"M mesh", 2, 0}, {"MQ1 M+frustum/q", 2, 1}, {"MQ4 M+nosample/q", 2, 4}, {"MQ5 M+both/q", 2, 5}};
        int np = sizeof paths / sizeof paths[0];
        if (onlyMask >= 0) { int k = 0; for (int i = 0; i < np; i++) if (i < 2 || (onlyMask >> i & 1)) paths[k++] = paths[i]; np = k; }
        id<MTLComputePipelineState> cp[16]; id<MTLRenderPipelineState> rpS[16];
        MTLRenderPipelineDescriptor *rd = [MTLRenderPipelineDescriptor new];
                for (int i = 0; i < np; i++) {
            MTLFunctionConstantValues *fc = [MTLFunctionConstantValues new];
            uint32_t m = paths[i].mask; [fc setConstantValue:&m type:MTLDataTypeUInt atIndex:0];
            if (paths[i].kind == 1) {
                MTLRenderPipelineDescriptor *rd2 = [MTLRenderPipelineDescriptor new];
                rd2.vertexFunction = [lib newFunctionWithName:@"vsA" constantValues:fc error:&err]; rd2.fragmentFunction = [lib newFunctionWithName:@"fs" constantValues:fc error:&err];
                rd2.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm; rd2.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
                rpS[i] = [dev newRenderPipelineStateWithDescriptor:rd2 error:&err];
                id<MTLFunction> fn = [lib newFunctionWithName:(m & 15) ? @"cullAQ" : @"cullA" constantValues:fc error:&err];
                cp[i] = [dev newComputePipelineStateWithFunction:fn error:&err];
                if (!cp[i]) { printf("cp: %s\n", err.description.UTF8String); return 1; }
            } else if (paths[i].kind == 3) {
                MTLRenderPipelineDescriptor *rd2 = [MTLRenderPipelineDescriptor new];
                rd2.vertexFunction = [lib newFunctionWithName:@"vsX" constantValues:fc error:&err]; rd2.fragmentFunction = [lib newFunctionWithName:@"fs" constantValues:fc error:&err];
                rd2.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm; rd2.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
                rpS[i] = [dev newRenderPipelineStateWithDescriptor:rd2 error:&err];
                cp[i] = [dev newComputePipelineStateWithFunction:[lib newFunctionWithName:@"expandX" constantValues:fc error:&err] error:&err];
                if (!cp[i] || !rpS[i]) { printf("X: %s\n", err.description.UTF8String); return 1; }
            } else if (paths[i].kind == 2) {
                MTLMeshRenderPipelineDescriptor *md = [MTLMeshRenderPipelineDescriptor new];
                md.objectFunction = [lib newFunctionWithName:@"osM" constantValues:fc error:&err];
                md.meshFunction = [lib newFunctionWithName:m ? @"msMQ" : @"msM" constantValues:fc error:&err];
                md.fragmentFunction = [lib newFunctionWithName:@"fsM" constantValues:fc error:&err];
                md.payloadMemoryLength = 128; md.maxTotalThreadsPerObjectThreadgroup = 32; md.maxTotalThreadsPerMeshThreadgroup = 64;
                md.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm; md.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
                rpS[i] = [dev newRenderPipelineStateWithMeshDescriptor:md options:0 reflection:nil error:&err];
                if (!rpS[i]) { printf("pM: %s\n", err.description.UTF8String); return 1; }
            }
        }
        id<MTLCommandQueue> queue = [dev newCommandQueue];
        uint32_t ncl32 = (uint32_t)ncl;
        double *t[16]; for (int i = 0; i < np; i++) t[i] = calloc(frames, sizeof(double));
        uint32_t visQuads[16] = {0};
        id<MTLBuffer> readB = [dev newBufferWithLength:W * 4 * 64 options:MTLResourceStorageModeShared];
        uint64_t sum[16] = {0};
        for (int fr = 0; fr < frames + 5; fr++) {
            for (int path = 0; path < np; path++) {
                id<MTLCommandBuffer> cb = [queue commandBuffer];
                int kind = paths[path].kind;
                if (kind == 1 || kind == 3) {
                    memset(argB.contents, 0, 20); ((uint32_t *)argB.contents)[1] = 1;
                    id<MTLComputeCommandEncoder> ce = [cb computeCommandEncoder];
                    [ce setComputePipelineState:cp[path]];
                    [ce setBuffer:clB offset:0 atIndex:0]; [ce setBuffer:secB offset:0 atIndex:1]; [ce setBytes:&view length:sizeof view atIndex:2];
                    [ce setBuffer:argB offset:0 atIndex:3]; [ce setBuffer:kind == 3 ? xB : quadB offset:0 atIndex:4]; [ce setBytes:&ncl32 length:4 atIndex:5];
                    [ce setBuffer:recB offset:0 atIndex:6];
                    if ((paths[path].mask & 15) || kind == 3) [ce dispatchThreadgroups:MTLSizeMake(ncl, 1, 1) threadsPerThreadgroup:MTLSizeMake(64, 1, 1)];
                    else [ce dispatchThreads:MTLSizeMake(ncl, 1, 1) threadsPerThreadgroup:MTLSizeMake(64, 1, 1)];
                    [ce endEncoding];
                }
                MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
                rp.colorAttachments[0].texture = color; rp.colorAttachments[0].loadAction = MTLLoadActionClear;
                rp.colorAttachments[0].storeAction = MTLStoreActionStore; rp.colorAttachments[0].clearColor = MTLClearColorMake(0.6, 0.7, 1.0, 1);
                rp.depthAttachment.texture = depth; rp.depthAttachment.loadAction = MTLLoadActionClear; rp.depthAttachment.clearDepth = 0.0;
                rp.depthAttachment.storeAction = MTLStoreActionDontCare;
                id<MTLRenderCommandEncoder> re = [cb renderCommandEncoderWithDescriptor:rp];
                [re setDepthStencilState:ds]; [re setCullMode:MTLCullModeNone];
                [re setFragmentTexture:atlas atIndex:0]; [re setFragmentSamplerState:samp atIndex:0];
                [re setVertexTexture:lmT atIndex:1]; [re setMeshTexture:lmT atIndex:1];
                if (kind == 3) {
                    [re setRenderPipelineState:rpS[path]];
                    [re setVertexBuffer:xB offset:0 atIndex:0]; [re setVertexBytes:&view length:sizeof view atIndex:4];
                    [re drawIndexedPrimitives:MTLPrimitiveTypeTriangle indexType:MTLIndexTypeUInt32 indexBuffer:idxB indexBufferOffset:0 indirectBuffer:argB indirectBufferOffset:0];
                } else if (kind == 1) {
                    [re setRenderPipelineState:rpS[path]];
                    [re setVertexBuffer:quadB offset:0 atIndex:0]; [re setVertexBuffer:clB offset:0 atIndex:1]; [re setVertexBuffer:secB offset:0 atIndex:2];
                    [re setVertexBuffer:recB offset:0 atIndex:3]; [re setVertexBytes:&view length:sizeof view atIndex:4];
                    [re drawIndexedPrimitives:MTLPrimitiveTypeTriangle indexType:MTLIndexTypeUInt32 indexBuffer:idxB indexBufferOffset:0 indirectBuffer:argB indirectBufferOffset:0];
                } else if (kind == 2) {
                    [re setRenderPipelineState:rpS[path]];
                    [re setObjectBuffer:clB offset:0 atIndex:0]; [re setObjectBuffer:secB offset:0 atIndex:1]; [re setObjectBytes:&view length:sizeof view atIndex:2];
                    [re setObjectBytes:&ncl32 length:4 atIndex:3];
                    [re setMeshBuffer:clB offset:0 atIndex:0]; [re setMeshBuffer:secB offset:0 atIndex:1]; [re setMeshBuffer:recB offset:0 atIndex:2];
                    [re setMeshBytes:&view length:sizeof view atIndex:3];
                    [re drawMeshThreadgroups:MTLSizeMake((ncl + 31) / 32, 1, 1) threadsPerObjectThreadgroup:MTLSizeMake(32, 1, 1) threadsPerMeshThreadgroup:MTLSizeMake(64, 1, 1)];
                }
                [re endEncoding];
                if (fr == 0) {   /* image check: checksum of rows 900..963 (identical image expected for every path) */
                    id<MTLBlitCommandEncoder> be = [cb blitCommandEncoder];
                    [be copyFromTexture:color sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0, 900, 0) sourceSize:MTLSizeMake(W, 64, 1)
                               toBuffer:readB destinationOffset:0 destinationBytesPerRow:W * 4 destinationBytesPerImage:W * 4 * 64];
                    [be endEncoding];
                }
                [cb commit]; [cb waitUntilCompleted];
                if (cb.error) { printf("cb error %s\n", cb.error.description.UTF8String); return 1; }
                if (fr == 0) { uint64_t h = 1469598103934665603ULL; const uint8_t *b2 = readB.contents; for (size_t i = 0; i < (size_t)W * 4 * 64; i++) h = (h ^ b2[i]) * 1099511628211ULL; sum[path] = h; }
                if (kind == 1 || kind == 3) visQuads[path] = ((uint32_t *)argB.contents)[0] / 6;
                if (fr >= 5) t[path][fr - 5] = (cb.GPUEndTime - cb.GPUStartTime) * 1e3;
            }
        }
        double med[16];
        for (int p = 0; p < np; p++) { qsort(t[p], frames, sizeof(double), cmpd); med[p] = t[p][frames / 2]; }
        for (int p = 0; p < np; p++)
            printf("%-18s median %.3f ms (p10 %.3f p90 %.3f) over empty %.3f ms  vs A %+6.1f %%  quads %u  image %s\n", paths[p].name, med[p], t[p][frames / 10],
                   t[p][frames * 9 / 10], med[p] - med[0], p ? 100.0 * ((med[p] - med[0]) / (med[1] - med[0]) - 1.0) : 0.0, visQuads[p],
                   p == 0 ? "-" : sum[p] == sum[1] ? "same as A" : "DIFFERS");
    }
    return 0;
}
