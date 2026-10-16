/*
 * lodray_bench.m -- stage 3 round 5: headless estimate of a ray-marched far field at Voxy's detail (results/STAGE3-R5.md).
 *
 * Voxy's selection (shaders/lod/hierarchical/screenspace.glsl: descend while the projected area of a 32-voxel section exceeds
 * subDivisionSize^2 = 64^2 px) keeps voxels at <= ~2 px: our tau 2. Rasterizing that is ~16x the quads of our tau 8 (which
 * already costs 0.86 ms at LOD 256). A ray march costs per covered pixel instead. This bench measures that cost.
 *
 * Scene (synthetic, like the snowy taiga bench world): fBm hills (y 60-170) with sea level 63, spruce-like trees on a jittered
 * 6-block grid (60 %), LOD nodes selected like LodManager (level = floor(log2(tau d / K)), quadtree from level-5 roots) out to
 * R chunks; each node = 16 x 16 columns of cells of 2^k blocks, each column a top-down list of runs (lo, hi, material) in cells
 * (what the LOD store's column-run encoding already is). GPU data:
 *   map    uint per chunk (2R x 2R): node slot | level << 28 (EMPTY = none)
 *   maxh   per-chunk max top (y + 64, 0 = empty) and its max mips (2R >> m)^2
 *   cols   uint2 per column (run offset, run count | top cell << 8)
 *   runs   uint per run (lo cell 9 bits | hi cell 9 bits | material 14 bits)
 * Kernel (one thread per pixel, 3024 x 1964): clip to the world box, hierarchical 2D DDA over the max mips (skip cells whose max
 * top is below the ray segment), per chunk a 2D DDA over the node's columns, per column the runs (top-down when descending):
 * first hit = side / top / bottom face; colour = per-face texel from a 16 x 16 tile atlas (block-repeating uv, mip by footprint)
 * x face shade x fog. Variants: "beam" = a 1/8-res conservative pre-pass that stores per 8 x 8 tile the t where any ray of the
 * tile could first reach terrain (cone-dilated max tops), full-res rays start there.
 *
 * Output: median GPU ms per frame for each view and variant, mean iterations per pixel, an image (build/native-tests/lodray-*.png).
 *
 * Build and run: tools/lodray-bench.sh [--frames N] [--radius R] [--tau T]
 */
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import <ImageIO/ImageIO.h>
#import <CoreGraphics/CoreGraphics.h>
#import <UniformTypeIdentifiers/UniformTypeIdentifiers.h>
#include <math.h>
#include <stdlib.h>
#include <string.h>

static int W = 3024, H = 1964;
static int DBG = 0;
static int R = 512;          // LOD radius in chunks
static double TAU = 2.0;     // px per cell (Voxy ~2)
static int MAXLEVEL = 5;
static const int MINY = -64;
static const uint32_t EMPTY = 0xFFFFFFFFu;

static uint32_t hash2(int x, int z) {
    uint32_t h = (uint32_t)x * 0x9E3779B1u ^ (uint32_t)z * 0x85EBCA77u;
    h ^= h >> 15; h *= 0x2C1B3C6Du; h ^= h >> 12; h *= 0x297A2D39u; h ^= h >> 15;
    return h;
}
static float vnoise(float x, float z) {
    int xi = (int)floorf(x), zi = (int)floorf(z);
    float fx = x - xi, fz = z - zi;
    fx = fx * fx * (3 - 2 * fx); fz = fz * fz * (3 - 2 * fz);
    float a = (hash2(xi, zi) & 0xFFFF) / 65535.0f, b = (hash2(xi + 1, zi) & 0xFFFF) / 65535.0f;
    float c = (hash2(xi, zi + 1) & 0xFFFF) / 65535.0f, d = (hash2(xi + 1, zi + 1) & 0xFFFF) / 65535.0f;
    return (a + (b - a) * fx) + ((c + (d - c) * fx) - (a + (b - a) * fx)) * fz;
}
static float terrainH(float x, float z) {
    float h = 0, amp = 1, f = 1.0f / 700.0f, n = 0;
    for (int o = 0; o < 6; o++) { h += amp * vnoise(x * f, z * f); n += amp; amp *= 0.5f; f *= 2.0f; }
    h /= n;
    float ridge = vnoise(x / 260.0f + 50, z / 260.0f + 50);
    return 52.0f + 120.0f * h * h + 18.0f * ridge;
}
enum { M_SNOWGRASS = 1, M_DIRT, M_STONE, M_LEAVES, M_LOG, M_WATER, M_SNOW };

typedef struct { int32_t lo, hi; uint32_t mat; } Run;   // blocks [lo, hi)

static int cmpd(const void *a, const void *b) { double x = *(const double *)a, y = *(const double *)b; return x < y ? -1 : x > y; }

/* Column of cell size s at block origin (x0, z0): runs in blocks, top-down. Returns the count. */
static int buildColumn(int x0, int z0, int s, Run *out, int level) {
    float cx = x0 + s * 0.5f, cz = z0 + s * 0.5f;
    float h = terrainH(cx, cz);
    int top = (int)floorf(h);
    int n = 0;
    Run leaf[8]; int nl = 0;
    // trees on a jittered 6-block grid
    int g = 6;
    int gx0 = (int)floorf((x0 - 4.0f) / g), gx1 = (int)floorf((x0 + s + 4.0f) / g);
    int gz0 = (int)floorf((z0 - 4.0f) / g), gz1 = (int)floorf((z0 + s + 4.0f) / g);
    for (int gz = gz0; gz <= gz1 && nl < 8; gz++) for (int gx = gx0; gx <= gx1 && nl < 8; gx++) {
        uint32_t hh = hash2(gx * 7 + 13, gz * 11 + 5);
        if ((hh & 1023) > 614) continue;
        float tx = gx * g + (int)(hh >> 10 & 3) + 1.5f, tz = gz * g + (int)(hh >> 12 & 3) + 1.5f;
        int gy = (int)floorf(terrainH(tx, tz));
        if (gy < 64) continue;
        float th = 7 + (int)(hh >> 14 & 3) * 1.5f;   // tree height
        float r0 = 2.6f;
        float dx = fmaxf(fabsf(tx - cx) - (level ? s * 0.5f - 0.5f : 0), 0), dz = fmaxf(fabsf(tz - cz) - (level ? s * 0.5f - 0.5f : 0), 0);
        float dist = sqrtf(dx * dx + dz * dz);
        float yb = gy + 3, yt = gy + 1 + th;
        if (dist <= r0) {
            float ytop = yt - dist * (yt - yb) / r0;
            if (ytop > yb) leaf[nl++] = (Run){(int)floorf(yb), (int)ceilf(ytop), M_LEAVES};
        }
        if (DBG) printf("  tree g %d %d hh %u t %.1f %.1f gy %d dist %.2f\n", gx, gz, hh & 1023, tx, tz, gy, dist);
        if (level == 0 && fabsf(tx - cx) < 0.6f && fabsf(tz - cz) < 0.6f && nl < 8) leaf[nl++] = (Run){gy + 1, (int)yb, M_LOG};
    }
    // merge leaf / log intervals (keep the higher priority = leaves over log where overlapping: simple union by start)
    for (int i = 0; i < nl; i++) for (int j = i + 1; j < nl; j++) if (leaf[j].hi > leaf[i].hi) { Run t = leaf[i]; leaf[i] = leaf[j]; leaf[j] = t; }
    int cur = 0x7FFFFFFF;
    for (int i = 0; i < nl; i++) {
        Run r = leaf[i];
        if (r.hi > cur) r.hi = cur;
        if (r.lo < top + 1) r.lo = top + 1;
        if (r.hi <= r.lo) continue;
        out[n++] = r; cur = r.lo;
    }
    bool snow = top > 70;
    if (top < 63) {
        out[n++] = (Run){top + 1, 64, M_WATER};
        out[n++] = (Run){top - 2, top + 1, M_DIRT};
    } else {
        out[n++] = (Run){top, top + 1, snow ? M_SNOWGRASS : M_SNOWGRASS};
        out[n++] = (Run){top - 3, top, M_DIRT};
    }
    { int lo0 = out[n - 1].lo; out[n++] = (Run){MINY, lo0, M_STONE}; }
    // to cells
    for (int i = 0; i < n; i++) {
        int lo = (int)floorf((float)(out[i].lo - MINY) / s + 0.5f), hi = (int)floorf((float)(out[i].hi - MINY) / s + 0.5f);
        if (hi <= lo) hi = lo + (i == n - 1 ? 1 : 0);
        out[i].lo = lo; out[i].hi = hi;
    }
    int m = 0;   // drop empty runs, clip overlaps top-down
    int prevLo = 0x7FFFFFFF;
    for (int i = 0; i < n; i++) {
        Run r = out[i];
        if (r.hi > prevLo) r.hi = prevLo;
        if (r.hi <= r.lo) continue;
        out[m++] = r; prevLo = r.lo;
    }
    return m;
}

static NSString *SRC = @
"#include <metal_stdlib>\n"
"using namespace metal;\n"
"struct P { float4 cam; float4 fwd; float4 right; float4 up; float4 misc; int4 grid; };\n"
// misc: tanX, tanY, tmax, fogEnd ; grid: R (chunks), mips, beam tile, flags
"constant uint EMPTY = 0xFFFFFFFFu;\nconstant int M_START = 4;\n"
"struct Hit { float t; uint mat; uint face; float3 p; };\n"
"static inline float3 rayDir(constant P& p, float2 px, float2 size) {\n"
"  float2 ndc = (px / size) * 2.0 - 1.0; ndc.y = -ndc.y;\n"
"  return normalize(p.fwd.xyz + ndc.x * p.misc.x * p.right.xyz + ndc.y * p.misc.y * p.up.xyz);\n"
"}\n"
// column runs: returns true with hit
"static inline bool column(device const uint2* cols, device const uint* runs, uint ci, float3 o, float3 d, float tA, float tB, uint faceA, int s, thread Hit& h) {\n"
"  uint2 c = cols[ci];\n"
"  uint cnt = c.y & 0xFFu;\n"
"  if (cnt == 0u) return false;\n"
"  float topY = float(int(c.y >> 8u) * s - 64);\n"
"  float yA = o.y + d.y * tA, yB = o.y + d.y * tB;\n"
"  if (min(yA, yB) >= topY) return false;\n"
"  if (d.y <= 0.0) {\n"
"    for (uint i = 0u; i < cnt; i++) {\n"
"      uint r = runs[c.x + i];\n"
"      float lo = float(int(r & 511u) * s - 64), hi = float(int((r >> 9u) & 511u) * s - 64);\n"
"      if (yA < lo) continue;\n"
"      if (yA <= hi) { h.t = tA; h.face = faceA; h.mat = r >> 18u; return true; }\n"
"      if (yB < hi) { h.t = (hi - o.y) / d.y; h.face = 0u; h.mat = r >> 18u; return true; }\n"
"      return false;\n"
"    }\n"
"  } else {\n"
"    for (int i = int(cnt) - 1; i >= 0; i--) {\n"
"      uint r = runs[c.x + uint(i)];\n"
"      float lo = float(int(r & 511u) * s - 64), hi = float(int((r >> 9u) & 511u) * s - 64);\n"
"      if (yA > hi) continue;\n"
"      if (yA >= lo) { h.t = tA; h.face = faceA; h.mat = r >> 18u; return true; }\n"
"      if (yB > lo) { h.t = (lo - o.y) / d.y; h.face = 1u; h.mat = r >> 18u; return true; }\n"
"      return false;\n"
"    }\n"
"  }\n"
"  return false;\n"
"}\n"
"static inline bool march(constant P& p, device const uint* map, device const ushort* maxh, device const uint* mipOff, device const uint2* cols,\n"
"    device const uint* runs, float3 o, float3 d, float t, float tEnd, int mstart, float cone, thread Hit& h, thread uint& iters) {\n"
"  int R = p.grid.x; int M = p.grid.y;\n"
"  float3 inv = 1.0 / d;\n"
"  float wmin = -16.0 * R, wmax = 16.0 * R;\n"
"  // world box clip (y: -64 .. 320)\n"
"  float3 bl = float3(wmin, -64.0, wmin), bh = float3(wmax, p.cam.w, wmax);\n"
"  float3 t0 = (bl - o) * inv, t1 = (bh - o) * inv;\n"
"  float3 tn = min(t0, t1), tf = max(t0, t1);\n"
"  float tin = max(max(tn.x, tn.y), tn.z), tout = min(min(tf.x, tf.y), tf.z);\n"
"  t = max(t, tin); tEnd = min(tEnd, tout);\n"
"  if (t >= tEnd) { h.face = 10u; return false; }\n"
"  int m = mstart;\n"
"  uint faceIn = 0u;\n"
"  for (uint it = 0u; it < 1000u; it++) {\n"
"    iters++;\n"
"    if (t >= tEnd) { h.face = 11u; return false; }\n"
"    float cs = 16.0 * float(1 << m);\n"
"    float3 q = o + d * t; q.xz += select(float2(-2e-3), float2(2e-3), d.xz > 0.0);\n"
"    int2 c = int2(floor((q.xz - wmin) / cs));\n"
"    int dim = (2 * R) >> m;\n"
"    if (any(c < 0) || any(c >= dim)) { h.face = 12u; return false; }\n"
"    float2 lo = float2(c) * cs + wmin, hi = lo + cs;\n"
"    float2 tx = (select(lo, hi, d.xz > 0.0) - o.xz) * inv.xz;\n"
"    float tExit = min(tx.x, tx.y);\n"
"    float yA = o.y + d.y * t, yB = o.y + d.y * tExit;\n"
"    float mh = float(maxh[mipOff[m] + uint(c.y * dim + c.x)]);\n"
"    float coneR = cone * tExit;\n"
"    bool xe = tx.x < tx.y;\n"
"    int ax = xe ? (d.x > 0.0 ? c.x + 1 : c.x) : (d.z > 0.0 ? c.y + 1 : c.y);\n"
"    int up = ((ax & 1) == 0) ? 1 : 0;\n"
"    if (mh == 0.0 || min(yA, yB) - coneR >= mh - 64.0) { t = tExit; faceIn = xe ? 2u : 3u; m = min(m + up, M - 1); continue; }\n"
"    if (cone > 0.0) { int mmin = clamp(int(ceil(log2(max(coneR, 1.0) / 16.0))), 0, M - 1); if (m > mmin) { m--; continue; } h.t = t; return true; }\n"
"    if (m > 0) { m--; continue; }\n"
"    // chunk level\n"
"    uint e = map[uint(c.y * dim + c.x)];\n"
"    if (e == EMPTY) { t = tExit; faceIn = xe ? 2u : 3u; m = min(m + up, M - 1); continue; }\n"
"    uint k = e >> 28u; uint slot = e & 0x0FFFFFFFu; int s = 1 << k;\n"
"    int2 nodeC = (c >> int(k)) << int(k);\n"
"    float2 nOrg = float2(nodeC) * 16.0 + wmin;\n"
"    float fs = float(s);\n"
"    float3 q2 = q;\n"
"    int2 cell = clamp(int2(floor((q2.xz - nOrg) / fs)), 0, 15);\n"
"    int2 st = int2(d.x > 0.0 ? 1 : -1, d.z > 0.0 ? 1 : -1);\n"
"    float2 nb = nOrg + (float2(cell) + select(float2(0.0), float2(1.0), d.xz > 0.0)) * fs;\n"
"    float2 tMax = (nb - o.xz) * inv.xz;\n"
"    float2 tDel = fs * abs(inv.xz);\n"
"    float ta = t; uint fa = faceIn;\n"
"    for (uint j = 0u; j < 48u; j++) {\n"
"      iters++;\n"
"      float tb = min(min(tMax.x, tMax.y), tExit);\n"
"      if (column(cols, runs, slot * 256u + uint(cell.y * 16 + cell.x), o, d, ta, tb, fa, s, h)) { return true; }\n"
"      if (tb >= tExit) break;\n"
"      if (tMax.x < tMax.y) { ta = tMax.x; tMax.x += tDel.x; cell.x += st.x; fa = 2u; } else { ta = tMax.y; tMax.y += tDel.y; cell.y += st.y; fa = 3u; }\n"
"      if (any(cell < 0) || any(cell > 15)) break;\n"
"    }\n"
"    t = tExit; faceIn = xe ? 2u : 3u; m = min(m + up, M - 1);\n"
"  }\n"
"  return false;\n"
"}\n"
"constant float3 MATC[8] = { float3(0.5), float3(0.92,0.95,0.98), float3(0.45,0.33,0.22), float3(0.5,0.5,0.52), float3(0.18,0.32,0.2), float3(0.4,0.3,0.18), float3(0.2,0.3,0.7), float3(0.95) };\n"
"static inline float4 shade(constant P& p, Hit h, float3 o, float3 d, texture2d<float> atlas, sampler smp) {\n"
"  float3 hp = o + d * h.t;\n"
"  float shadeF = h.face == 0u ? 1.0 : h.face == 1u ? 0.5 : h.face == 2u ? 0.6 : 0.8;\n"
"  float2 uv = h.face <= 1u ? hp.xz : h.face == 2u ? hp.zy : hp.xy;\n"
"  uv = fract(uv);\n"
"  float2 tile = float2(float(h.mat & 7u), float(h.face == 0u ? 0u : 1u));\n"
"  float lod = log2(max(h.t * p.misc.w, 1e-3));\n"
"  float3 tex = atlas.sample(smp, (tile + uv) * (1.0 / 8.0), level(lod)).rgb;\n"
"  float3 c = MATC[h.mat & 7u] * tex * shadeF;\n"
"  float fog = clamp(h.t / p.misc.z, 0.0, 1.0);\n"
"  return float4(mix(c, float3(0.7, 0.8, 1.0), fog * fog), 1.0);\n"
"}\n"
"kernel void rayKernel(constant P& p [[buffer(0)]], device const uint* map [[buffer(1)]], device const ushort* maxh [[buffer(2)]],\n"
"    device const uint* mipOff [[buffer(3)]], device const uint2* cols [[buffer(4)]], device const uint* runs [[buffer(5)]],\n"
"    device const float* beam [[buffer(6)]], device atomic_uint* stats [[buffer(7)]], texture2d<float, access::write> out [[texture(0)]],\n"
"    texture2d<float> atlas [[texture(1)]], device float* tPix [[buffer(8)]], device const float* mask [[buffer(9)]], uint2 gid [[thread_position_in_grid]]) {\n"
"  if (gid.x >= out.get_width() || gid.y >= out.get_height()) return;\n"
"  uint pix = gid.y * out.get_width() + gid.x;\n"
"  constexpr sampler smp(filter::linear, mip_filter::linear, address::repeat);\n"
"  float2 size = float2(out.get_width(), out.get_height());\n"
"  float3 o = p.cam.xyz; float3 d = rayDir(p, float2(gid) + 0.5, size);\n"
"  if ((p.grid.w & 8) != 0) { float tm = mask[pix]; if (tm >= 0.0 && tm * length(d.xz) < p.up.w) return; }\n"
"  float t0 = 0.0;\n"
"  int tile = p.grid.z;\n"
"  if (tile > 0) { uint bw = (out.get_width() + uint(tile) - 1u) / uint(tile); t0 = beam[(gid.y / uint(tile)) * bw + gid.x / uint(tile)]; }\n"
"  if (tile < 0) { float tp = tPix[pix]; t0 = tp < 0.0 ? -1.0 : max(0.0, tp - p.right.w); }\n"
"  Hit h; uint iters = 0u;\n"
"  float4 col = float4(0.55, 0.7, 1.0, 1.0);\n"
"  h.face = 13u;\n"
"  bool hit = t0 >= 0.0 && march(p, map, maxh, mipOff, cols, runs, o, d, t0, p.misc.z, 0, 0.0, h, iters);\n"
"  if (hit) col = shade(p, h, o, d, atlas, smp);\n"
"  if ((p.grid.w & 4) != 0) tPix[pix] = hit ? h.t : -1.0;\n"
"  if (!hit && (p.grid.w & 2) != 0) col = float4(h.face == 10u, h.face == 11u, h.face == 12u, 1.0) * 0.5 + float4(0, 0, 0, 0.5) + (h.face == 13u ? float4(0.5, 0.5, 0, 0) : 0);\n"
"  if ((p.grid.w & 1) != 0) { atomic_fetch_add_explicit(&stats[0], iters, memory_order_relaxed); }\n"
"  out.write(col, gid);\n"
"}\n"
// beam pre-pass: one ray per tile centre, cone angle = tile diagonal
"kernel void beamKernel(constant P& p [[buffer(0)]], device const uint* map [[buffer(1)]], device const ushort* maxh [[buffer(2)]],\n"
"    device const uint* mipOff [[buffer(3)]], device const uint2* cols [[buffer(4)]], device const uint* runs [[buffer(5)]],\n"
"    device float* beam [[buffer(6)]], device atomic_uint* stats [[buffer(7)]], constant uint2& outSize [[buffer(8)]], device const ushort* maxhD [[buffer(9)]], uint2 gid [[thread_position_in_grid]]) {\n"
"  int tile = p.grid.z; uint bw = (outSize.x + uint(tile) - 1u) / uint(tile), bh = (outSize.y + uint(tile) - 1u) / uint(tile);\n"
"  if (gid.x >= bw || gid.y >= bh) return;\n"
"  float2 size = float2(outSize);\n"
"  float2 c = (float2(gid) + 0.5) * float(tile);\n"
"  float3 o = p.cam.xyz; float3 d = rayDir(p, c, size);\n"
"  // cone half-angle covering the tile (tan ~ tile diagonal / focal length)\n"
"  float cone = 0.75 * float(tile) * 1.4143 * p.misc.y * 2.0 / size.y;\n"
"  Hit h; uint iters = 0u;\n"
"  float t = 0.0;\n"
"  if (march(p, map, maxhD, mipOff, cols, runs, o, d, 0.0, p.misc.z, M_START, cone, h, iters)) t = max(0.0, h.t - 2.0); else t = -1.0;\n"
"  // the cone test is per chunk only: a chunk whose dilated max top reaches the cone stops the beam (conservative)\n"
"  beam[gid.y * bw + gid.x] = t;\n"
"  if ((p.grid.w & 1) != 0) { atomic_fetch_add_explicit(&stats[1], iters, memory_order_relaxed); }\n"
"}\n";

typedef struct { float cam[4], fwd[4], right[4], up[4], misc[4]; int32_t grid[4]; } Params;

static void savePng(id<MTLTexture> tex, NSString *path) {
    int w = (int)tex.width, h = (int)tex.height;
    uint8_t *px = malloc((size_t)w * h * 4);
    [tex getBytes:px bytesPerRow:w * 4 fromRegion:MTLRegionMake2D(0, 0, w, h) mipmapLevel:0];
    CGColorSpaceRef cs = CGColorSpaceCreateDeviceRGB();
    CGContextRef ctx = CGBitmapContextCreate(px, w, h, 8, w * 4, cs, kCGImageAlphaPremultipliedLast);
    CGImageRef img = CGBitmapContextCreateImage(ctx);
    CGImageDestinationRef dst = CGImageDestinationCreateWithURL((__bridge CFURLRef)[NSURL fileURLWithPath:path], (__bridge CFStringRef)UTTypePNG.identifier, 1, NULL);
    CGImageDestinationAddImage(dst, img, NULL);
    CGImageDestinationFinalize(dst);
    CFRelease(dst); CGImageRelease(img); CGContextRelease(ctx); CGColorSpaceRelease(cs); free(px);
}

typedef struct { int level, nx, nz; } NodeRef;

int main(int argc, char **argv) {
    @autoreleasepool {
        int frames = 30;
        const char *outDir = "build/native-tests";
        for (int i = 1; i < argc; i++) {
            if (!strcmp(argv[i], "--frames") && i + 1 < argc) frames = atoi(argv[++i]);
            else if (!strcmp(argv[i], "--radius") && i + 1 < argc) R = atoi(argv[++i]);
            else if (!strcmp(argv[i], "--tau") && i + 1 < argc) TAU = atof(argv[++i]);
            else if (!strcmp(argv[i], "--out") && i + 1 < argc) outDir = argv[++i];
            else if (!strcmp(argv[i], "--debug")) { DBG = 1;
                for (int x = 0; x < 12; x++) { Run r[16]; int k = buildColumn(x * 3, -300 - x * 50, 1, r, 0); printf("x %d:", x); for (int j = 0; j < k; j++) printf(" [%d,%d)m%u", r[j].lo, r[j].hi, r[j].mat); printf("\n"); }
                return 0;
            }
        }
        double K = H / 2.0 / tan(70.0 * M_PI / 180.0 / 2.0);
        // ---- node selection (quadtree from level-MAXLEVEL roots, distance from the camera at chunk (0,0)) ----
        int dim = 2 * R;
        uint32_t *map = malloc(sizeof(uint32_t) * dim * dim);
        for (int i = 0; i < dim * dim; i++) map[i] = EMPTY;
        uint16_t *top0 = calloc((size_t)dim * dim, 2);
        int capNodes = 1 << 20, nNodes = 0;
        NodeRef *nodes = malloc(sizeof(NodeRef) * capNodes);
        NodeRef *stack = malloc(sizeof(NodeRef) * 1000000); int sp = 0;
        int rootN = (2 * R) >> MAXLEVEL;
        for (int z = 0; z < rootN; z++) for (int x = 0; x < rootN; x++) stack[sp++] = (NodeRef){MAXLEVEL, x - rootN / 2, z - rootN / 2};
        while (sp > 0) {
            NodeRef n = stack[--sp];
            int size = 1 << n.level;   // chunks
            double x0 = n.nx * size * 16.0, z0 = n.nz * size * 16.0, x1 = x0 + size * 16.0, z1 = z0 + size * 16.0;
            double dx = fmax(fmax(x0, -x1), 0), dz = fmax(fmax(z0, -z1), 0);   // camera at (8, 8) ~ 0
            double d = sqrt(dx * dx + dz * dz);
            if (d > R * 16.0) continue;
            int want = d < 1 ? 0 : (int)floor(log2(TAU * d / K));
            if (want < 0) want = 0;
            if (want < n.level) {
                for (int q = 0; q < 4; q++) stack[sp++] = (NodeRef){n.level - 1, n.nx * 2 + (q & 1), n.nz * 2 + (q >> 1)};
                continue;
            }
            nodes[nNodes++] = n;
        }
        // ---- columns ----
        size_t capCols = (size_t)nNodes * 256, capRuns = capCols * 6;
        uint32_t *cols = malloc(capCols * 8), *runs = malloc(capRuns * 4);
        size_t nRuns = 0;
        long perLevel[8] = {0};
        NSDate *g0 = [NSDate date];
        for (int i = 0; i < nNodes; i++) {
            NodeRef n = nodes[i]; int s = 1 << n.level; int size = s;
            perLevel[n.level]++;
            for (int cz = 0; cz < 16; cz++) for (int cx = 0; cx < 16; cx++) {
                int bx = n.nx * size * 16 + cx * s, bz = n.nz * size * 16 + cz * s;
                Run r[16]; int k = buildColumn(bx, bz, s, r, n.level);
                size_t ci = (size_t)i * 256 + cz * 16 + cx;
                int topCell = k > 0 ? r[0].hi : 0;
                cols[2 * ci] = (uint32_t)nRuns; cols[2 * ci + 1] = (uint32_t)k | (uint32_t)topCell << 8;
                for (int j = 0; j < k; j++) runs[nRuns++] = (uint32_t)r[j].lo | (uint32_t)r[j].hi << 9 | r[j].mat << 18;
                int topY = topCell * s + MINY;
                // max top per chunk covered by this column
                int c0x = (bx >> 4) + R, c0z = (bz >> 4) + R, cn = s >= 16 ? s / 16 : 1;
                for (int a = 0; a < cn; a++) for (int b = 0; b < cn; b++) {
                    int mx = c0x + a, mz = c0z + b;
                    if (mx < 0 || mz < 0 || mx >= dim || mz >= dim) continue;
                    uint16_t v = (uint16_t)(topY + 64);
                    if (v > top0[mz * dim + mx]) top0[mz * dim + mx] = v;
                }
            }
            for (int a = 0; a < size; a++) for (int b = 0; b < size; b++) {
                int mx = n.nx * size + a + R, mz = n.nz * size + b + R;
                if (mx >= 0 && mz >= 0 && mx < dim && mz < dim) map[mz * dim + mx] = (uint32_t)i | (uint32_t)n.level << 28;
            }
        }
        printf("nodes %d (L0 %ld L1 %ld L2 %ld L3 %ld L4 %ld L5 %ld), columns %zu, runs %zu (%.2f per column), %.0f MB, built in %.1f s\n", nNodes,
            perLevel[0], perLevel[1], perLevel[2], perLevel[3], perLevel[4], perLevel[5], (size_t)nNodes * 256, nRuns, nRuns / (nNodes * 256.0),
            ((size_t)nNodes * 256 * 8 + nRuns * 4) / 1048576.0, -[g0 timeIntervalSinceNow]);
        // ---- max mips ----
        int mips = 1; while ((dim >> mips) >= 1 && mips < 10) mips++;
        uint32_t mipOff[16]; size_t total = 0;
        for (int m = 0; m < mips; m++) { mipOff[m] = (uint32_t)total; int dm = dim >> m; total += (size_t)dm * dm; }
        uint16_t *maxh = calloc(total, 2);
        memcpy(maxh, top0, (size_t)dim * dim * 2);
        for (int m = 1; m < mips; m++) {
            int dm = dim >> m, dp = dim >> (m - 1);
            for (int z = 0; z < dm; z++) for (int x = 0; x < dm; x++) {
                uint16_t v = 0;
                for (int q = 0; q < 4; q++) { uint16_t w = maxh[mipOff[m - 1] + (2 * z + (q >> 1)) * dp + 2 * x + (q & 1)]; if (w > v) v = w; }
                maxh[mipOff[m] + z * dm + x] = v;
            }
        }
        uint16_t *maxhD = calloc(total, 2);   // per mip: max over the 3 x 3 neighbourhood (beam pre-pass)
        for (int m = 0; m < mips; m++) {
            int dm = dim >> m;
            for (int z = 0; z < dm; z++) for (int x = 0; x < dm; x++) {
                uint16_t v = 0;
                for (int b = -1; b <= 1; b++) for (int a = -1; a <= 1; a++) {
                    int xx = x + a, zz = z + b;
                    if (xx < 0 || zz < 0 || xx >= dm || zz >= dm) continue;
                    uint16_t w = maxh[mipOff[m] + zz * dm + xx]; if (w > v) v = w;
                }
                maxhD[mipOff[m] + z * dm + x] = v;
            }
        }
        // ---- Metal ----
        id<MTLDevice> dev = MTLCreateSystemDefaultDevice();
        NSError *err = nil;
        NSString *waveSrc = [NSString stringWithContentsOfFile:@"tools/lodray_wave.metal" encoding:NSUTF8StringEncoding error:&err];
        if (!waveSrc) { printf("tools/lodray_wave.metal: %s\n", err.description.UTF8String); return 1; }
        id<MTLLibrary> lib = [dev newLibraryWithSource:[SRC stringByAppendingString:waveSrc] options:nil error:&err];
        if (!lib) { printf("compile: %s\n", err.description.UTF8String); return 1; }
        id<MTLComputePipelineState> ray = [dev newComputePipelineStateWithFunction:[lib newFunctionWithName:@"rayKernel"] error:&err];
        id<MTLComputePipelineState> waveP = [dev newComputePipelineStateWithFunction:[lib newFunctionWithName:@"waveKernel"] error:&err];
        id<MTLComputePipelineState> resolveP = [dev newComputePipelineStateWithFunction:[lib newFunctionWithName:@"resolveKernel"] error:&err];
        id<MTLComputePipelineState> clearP = [dev newComputePipelineStateWithFunction:[lib newFunctionWithName:@"clearKernel"] error:&err];
        id<MTLComputePipelineState> beamP = [dev newComputePipelineStateWithFunction:[lib newFunctionWithName:@"beamKernel"] error:&err];
        printf("ray pipeline: max threads/tg %lu, simd %lu; beam %lu\n", (unsigned long)ray.maxTotalThreadsPerThreadgroup, (unsigned long)ray.threadExecutionWidth, (unsigned long)beamP.maxTotalThreadsPerThreadgroup);
        id<MTLBuffer> bMap = [dev newBufferWithBytes:map length:sizeof(uint32_t) * dim * dim options:MTLResourceStorageModeShared];
        id<MTLBuffer> bMax = [dev newBufferWithBytes:maxh length:total * 2 options:MTLResourceStorageModeShared];
        id<MTLBuffer> bMaxD = [dev newBufferWithBytes:maxhD length:total * 2 options:MTLResourceStorageModeShared];
        id<MTLBuffer> bOff = [dev newBufferWithBytes:mipOff length:sizeof(mipOff) options:MTLResourceStorageModeShared];
        id<MTLBuffer> bCols = [dev newBufferWithBytes:cols length:(size_t)nNodes * 256 * 8 options:MTLResourceStorageModeShared];
        id<MTLBuffer> bRuns = [dev newBufferWithBytes:runs length:nRuns * 4 + 16 options:MTLResourceStorageModeShared];
        id<MTLBuffer> bStats = [dev newBufferWithLength:64 options:MTLResourceStorageModeShared];
        int tile = 8;
        int bw = (W + tile - 1) / tile, bh = (H + tile - 1) / tile;
        id<MTLBuffer> bIds = [dev newBufferWithLength:(size_t)8192 * H * 4 options:MTLResourceStorageModePrivate];
        uint16_t *topsA = malloc((size_t)nNodes * 256 * 2);
        for (size_t i = 0; i < (size_t)nNodes * 256; i++) { uint32_t hy = cols[2 * i + 1]; int s2 = 1 << nodes[i >> 8].level; topsA[i] = (hy & 0xFF) == 0 ? 0 : (uint16_t)((int)(hy >> 8) * s2 - 64 + 64); }
        id<MTLBuffer> bTops = [dev newBufferWithBytes:topsA length:(size_t)nNodes * 256 * 2 options:MTLResourceStorageModeShared];
        id<MTLBuffer> bLineTop = [dev newBufferWithLength:8192 * 4 options:MTLResourceStorageModePrivate];
        int32_t *nodeInfo = malloc(16 * (size_t)nNodes);
        for (int i = 0; i < nNodes; i++) { int sz = 1 << nodes[i].level; nodeInfo[4 * i] = nodes[i].nx * sz * 16; nodeInfo[4 * i + 1] = nodes[i].nz * sz * 16; nodeInfo[4 * i + 2] = nodes[i].level; nodeInfo[4 * i + 3] = 0; }
        id<MTLBuffer> bNodes = [dev newBufferWithBytes:nodeInfo length:16 * (size_t)nNodes options:MTLResourceStorageModeShared];
        id<MTLBuffer> bMask = [dev newBufferWithLength:(size_t)W * H * 4 options:MTLResourceStorageModeShared];
        id<MTLBuffer> bTpix = [dev newBufferWithLength:(size_t)W * H * 4 options:MTLResourceStorageModePrivate];
        id<MTLBuffer> bBeam = [dev newBufferWithLength:(size_t)bw * bh * 4 options:MTLResourceStorageModePrivate];
        MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:W height:H mipmapped:NO];
        td.usage = MTLTextureUsageShaderWrite | MTLTextureUsageShaderRead;
        id<MTLTexture> out = [dev newTextureWithDescriptor:td];
        // atlas: 8 x 8 tiles of 16 px, noisy texels, mipmapped
        MTLTextureDescriptor *ad = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:128 height:128 mipmapped:YES];
        id<MTLTexture> atlas = [dev newTextureWithDescriptor:ad];
        uint8_t *ap = malloc(128 * 128 * 4);
        for (int i = 0; i < 128 * 128; i++) { uint8_t v = 170 + (hash2(i, 7) & 63); ap[4 * i] = ap[4 * i + 1] = ap[4 * i + 2] = v; ap[4 * i + 3] = 255; }
        [atlas replaceRegion:MTLRegionMake2D(0, 0, 128, 128) mipmapLevel:0 withBytes:ap bytesPerRow:128 * 4];
        id<MTLCommandQueue> qu = [dev newCommandQueue];
        { id<MTLCommandBuffer> cb = [qu commandBuffer]; id<MTLBlitCommandEncoder> be = [cb blitCommandEncoder]; [be generateMipmapsForTexture:atlas]; [be endEncoding]; [cb commit]; [cb waitUntilCompleted]; }

        struct { const char *name; float y, pitch, yaw; } views[] = {
            {"vista", 180, 18, 200}, {"far", 180, 6, 200}, {"spot", 110, 12, 200}, {"low", 90, 2, 120},
        };
        for (int v = 0; v < 4; v++) {
            Params p; memset(&p, 0, sizeof p);
            float yaw = views[v].yaw * (float)M_PI / 180.0f, pitch = views[v].pitch * (float)M_PI / 180.0f;
            // Minecraft: yaw 0 = +z, yaw 90 = -x; pitch positive = down
            float fx = -sinf(yaw) * cosf(pitch), fy = -sinf(pitch), fz = cosf(yaw) * cosf(pitch);
            float rx = -cosf(yaw), rz = -sinf(yaw);   // right
            float ux = fy * rz - fz * 0, uy = fz * rx - fx * rz, uz = fx * 0 - fy * rx;   // up = right x fwd ... computed below
            // up = cross(right, fwd)
            ux = 0 * fz - rz * fy; uy = rz * fx - rx * fz; uz = rx * fy - 0 * fx;
            if (uy < 0) { ux = -ux; uy = -uy; uz = -uz; }
            p.cam[0] = 6.5f; p.cam[1] = views[v].y; p.cam[2] = 6.5f;
            p.fwd[0] = fx; p.fwd[1] = fy; p.fwd[2] = fz; p.right[0] = rx; p.right[2] = rz; p.up[0] = ux; p.up[1] = uy; p.up[2] = uz;
            float tanY = tanf(70.0f * (float)M_PI / 360.0f);
            p.misc[0] = tanY * W / H; p.misc[1] = tanY; p.misc[2] = R * 16.0f; p.misc[3] = 16.0f * 2.0f * tanY / H;   // texels per px per t
            p.up[3] = getenv("LODRAY_NEAR") ? atof(getenv("LODRAY_NEAR")) : 0.0f;
            {   // oracle hit distances for the near mask (in game: real terrain covers these pixels, early-Z skips them)
                Params q = p; q.grid[0] = R; q.grid[1] = mips; q.grid[2] = 0; q.grid[3] = 4; q.right[3] = 0; q.misc[0] = tanf(70.0f * (float)M_PI / 360.0f) * W / H;
                q.misc[1] = tanf(70.0f * (float)M_PI / 360.0f); q.misc[2] = R * 16.0f; q.misc[3] = 0.01f; q.cam[3] = (float)maxh[mipOff[mips - 1]] - 64.0f + 1.0f;
                id<MTLCommandBuffer> cb = [qu commandBuffer];
                id<MTLComputeCommandEncoder> ce = [cb computeCommandEncoder];
                [ce setComputePipelineState:ray];
                [ce setBytes:&q length:sizeof q atIndex:0];
                [ce setBuffer:bMap offset:0 atIndex:1]; [ce setBuffer:bMax offset:0 atIndex:2]; [ce setBuffer:bOff offset:0 atIndex:3];
                [ce setBuffer:bCols offset:0 atIndex:4]; [ce setBuffer:bRuns offset:0 atIndex:5]; [ce setBuffer:bBeam offset:0 atIndex:6];
                [ce setBuffer:bStats offset:0 atIndex:7]; [ce setBuffer:bMask offset:0 atIndex:8]; [ce setBuffer:bMask offset:0 atIndex:9];
                [ce setTexture:out atIndex:0]; [ce setTexture:atlas atIndex:1];
                [ce dispatchThreads:MTLSizeMake(W, H, 1) threadsPerThreadgroup:MTLSizeMake(8, 8, 1)];
                [ce endEncoding]; [cb commit]; [cb waitUntilCompleted];
                float *mk = bMask.contents; long near = 0, sky = 0;
                float fl = sqrtf(p.fwd[0] * p.fwd[0] + p.fwd[2] * p.fwd[2]);
                for (long i = 0; i < (long)W * H; i++) { if (mk[i] < 0) sky++; }
                (void)fl; (void)near;
                printf("%s: sky %.1f %% of pixels\n", views[v].name, 100.0 * sky / ((double)W * H));
            }
            p.right[3] = getenv("LODRAY_MARGIN") ? atof(getenv("LODRAY_MARGIN")) : 6.0f;
            if (getenv("LODRAY_SKIPALL")) p.misc[2] = 0.0f;
            p.grid[0] = R; p.grid[1] = mips; p.cam[3] = (float)maxh[mipOff[mips - 1]] - 64.0f + 1.0f;
            // wave variant parameters: vertical vanishing point, line range, distance segments of equal estimated step count
            struct { float vp[4]; float seg[36]; } wp; memset(&wp, 0, sizeof wp);
            {
                float K = H / 2.0f / tanf(70.0f * (float)M_PI / 360.0f);
                float sgn = p.fwd[1] < 0 ? -1.0f : 1.0f;   // looking down: nadir; up: zenith
                float zc = sgn * p.fwd[1], yc = sgn * p.up[1], xc = 0;
                wp.vp[0] = K * xc / zc; wp.vp[1] = K * yc / zc;
                float vt = H / 2.0f, uvp = wp.vp[0], vvp = wp.vp[1];
                float uh = fabsf(uvp + (W / 2.0f - uvp) * (vt - vvp) / (-H / 2.0f - vvp));
                if (uh < W / 2.0f) uh = W / 2.0f;
                wp.vp[2] = 2.0f * ceilf(uh); wp.vp[3] = ceilf(uh);
                float r0 = getenv("LODRAY_NEAR") ? atof(getenv("LODRAY_NEAR")) : 0.5f, r1 = R * 16.0f;
                double tot = 0; int N = 200000; double dr = (r1 - r0) / N;
                for (int i = 0; i < N; i++) { double r = r0 + (i + 0.5) * dr; double lv = floor(log2(fmax(TAU * r / K, 1.0))); tot += 1.3 * dr / pow(2.0, lv); }
                double acc = 0; int j = 1; wp.seg[0] = r0;
                for (int i = 0; i < N && j < 32; i++) { double r = r0 + (i + 0.5) * dr; double lv = floor(log2(fmax(TAU * r / K, 1.0))); acc += 1.3 * dr / pow(2.0, lv); if (acc >= tot * j / 32.0) wp.seg[j++] = (float)(r0 + (i + 1) * dr); }
                while (j <= 32) wp.seg[j++] = r1;
            }
            for (int variant = 0; variant < 4; variant++) {
                p.grid[2] = variant == 1 ? tile : variant == 2 ? -1 : 0;
                double *t = malloc(sizeof(double) * frames), *tw = malloc(sizeof(double) * frames);
                uint32_t itRay = 0, itBeam = 0;
                for (int f = 0; f < frames + 3; f++) {
                    int statF = variant == 2 ? 1 : 0;
                    p.grid[3] = ((f == statF) ? 1 : 0) | (getenv("LODRAY_DBG") ? 2 : 0) | (variant == 2 ? 4 : 0) | (getenv("LODRAY_NEAR") ? 8 : 0) | (getenv("LODRAY_NOPIX") ? 16 : 0) | (getenv("LODRAY_NOTOP") ? 32 : 0) | (getenv("LODRAY_NOMAP") ? 64 : 0) | (getenv("LODRAY_PASS1") ? 128 : 0) | (getenv("LODRAY_PASS0") ? 256 : 0);
                    if (variant == 2) p.grid[2] = f == 0 ? 0 : -1;
                    if (variant == 3) p.grid[2] = 0;
                    if (f == statF) memset(bStats.contents, 0, 64);
                    id<MTLCommandBuffer> cb = [qu commandBuffer];
                    id<MTLComputeCommandEncoder> ce = [cb computeCommandEncoder];
                    if (variant == 3) {
                        [ce setComputePipelineState:waveP];
                        [ce setBytes:&p length:sizeof p atIndex:0];
                        [ce setBuffer:bMap offset:0 atIndex:1]; [ce setBuffer:bCols offset:0 atIndex:4]; [ce setBuffer:bRuns offset:0 atIndex:5];
                        [ce setBuffer:bStats offset:0 atIndex:7]; [ce setBytes:&wp length:sizeof wp atIndex:10];
                        [ce setTexture:out atIndex:0]; [ce setTexture:atlas atIndex:1];
                        [ce setBuffer:bIds offset:0 atIndex:11]; [ce setBuffer:bLineTop offset:0 atIndex:14]; [ce setBuffer:bTops offset:0 atIndex:15];
                        [ce dispatchThreads:MTLSizeMake((NSUInteger)wp.vp[2] * 32, 1, 1) threadsPerThreadgroup:MTLSizeMake(256, 1, 1)];
                        [ce endEncoding]; [cb commit]; [cb waitUntilCompleted];
                        double tWave = (cb.GPUEndTime - cb.GPUStartTime) * 1e3;
                        cb = [qu commandBuffer]; ce = [cb computeCommandEncoder];
                        [ce setComputePipelineState:resolveP];
                        [ce setBytes:&p length:sizeof p atIndex:0]; [ce setBuffer:bCols offset:0 atIndex:4]; [ce setBuffer:bRuns offset:0 atIndex:5];
                        [ce setBuffer:bIds offset:0 atIndex:11]; [ce setBuffer:bNodes offset:0 atIndex:13]; [ce setBuffer:bLineTop offset:0 atIndex:14];
                        [ce setBytes:&wp length:sizeof wp atIndex:10];
                        [ce setTexture:out atIndex:0]; [ce setTexture:atlas atIndex:1];
                        [ce dispatchThreads:MTLSizeMake(W, H, 1) threadsPerThreadgroup:MTLSizeMake(8, 8, 1)];
                        [ce endEncoding]; [cb commit]; [cb waitUntilCompleted];
                        if (f == statF) { itRay = ((uint32_t *)bStats.contents)[0]; }
                        if (f >= 3) { t[f - 3] = tWave + (cb.GPUEndTime - cb.GPUStartTime) * 1e3; tw[f - 3] = tWave; }
                        continue;
                    }
                    if (variant == 1) {
                        [ce setComputePipelineState:beamP];
                        [ce setBytes:&p length:sizeof p atIndex:0];
                        [ce setBuffer:bMap offset:0 atIndex:1]; [ce setBuffer:bMax offset:0 atIndex:2]; [ce setBuffer:bOff offset:0 atIndex:3];
                        [ce setBuffer:bCols offset:0 atIndex:4]; [ce setBuffer:bRuns offset:0 atIndex:5]; [ce setBuffer:bBeam offset:0 atIndex:6];
                        [ce setBuffer:bStats offset:0 atIndex:7];
                        uint32_t os[2] = {(uint32_t)W, (uint32_t)H}; [ce setBytes:os length:8 atIndex:8];
                        [ce setBuffer:bMaxD offset:0 atIndex:9];
                        [ce dispatchThreads:MTLSizeMake(bw, bh, 1) threadsPerThreadgroup:MTLSizeMake(8, 8, 1)];
                    }
                    [ce setComputePipelineState:ray];
                    [ce setBytes:&p length:sizeof p atIndex:0];
                    [ce setBuffer:bMap offset:0 atIndex:1]; [ce setBuffer:bMax offset:0 atIndex:2]; [ce setBuffer:bOff offset:0 atIndex:3];
                    [ce setBuffer:bCols offset:0 atIndex:4]; [ce setBuffer:bRuns offset:0 atIndex:5]; [ce setBuffer:bBeam offset:0 atIndex:6];
                    [ce setBuffer:bStats offset:0 atIndex:7];
                    [ce setTexture:out atIndex:0]; [ce setTexture:atlas atIndex:1]; [ce setBuffer:bTpix offset:0 atIndex:8]; [ce setBuffer:bMask offset:0 atIndex:9];
                    [ce dispatchThreads:MTLSizeMake(W, H, 1) threadsPerThreadgroup:MTLSizeMake(8, 8, 1)];
                    [ce endEncoding];
                    [cb commit]; [cb waitUntilCompleted];
                    if (f == statF) { itRay = ((uint32_t *)bStats.contents)[0]; itBeam = ((uint32_t *)bStats.contents)[1]; }
                    if (f >= 3) t[f - 3] = (cb.GPUEndTime - cb.GPUStartTime) * 1e3;
                }
                qsort(t, frames, sizeof(double), cmpd); qsort(tw, frames, sizeof(double), cmpd);
                if (variant == 3) printf("       wave pass %.3f ms, resolve %.3f ms\n", tw[frames / 2], t[frames / 2] - tw[frames / 2]);
                printf("%-6s %-5s median %.3f ms (p10 %.3f p90 %.3f)  iterations/px %.1f  beam iterations/tile %.1f\n", views[v].name, variant == 1 ? "beam" : variant == 2 ? "temp" : variant == 3 ? "wave" : "plain",
                    t[frames / 2], t[frames / 10], t[frames * 9 / 10], itRay / (double)(W * H), variant == 1 ? itBeam / (double)(bw * bh) : 0.0);
                free(t);
                NSString *path = [NSString stringWithFormat:@"%s/lodray-%s-%s.png", outDir, views[v].name, variant == 1 ? "beam" : variant == 2 ? "temp" : variant == 3 ? "wave" : "plain"];
                savePng(out, path);
            }
        }
    }
    return 0;
}
