/*
 * occlusion_bench.m -- headless GPU measurement: how much would terrain occlusion culling save on this GPU at
 * 3600x2260, and what do the candidate mechanisms cost? (docs/metal/OCCLUSION.md)
 *
 * No window, no drawable: everything renders into offscreen textures. The scene is synthetic Minecraft-like terrain
 * (16^3 sections of unit blocks, faces only between solid and air, cutout leaves on trees), meshed into the vanilla
 * 28-byte terrain vertex format, drawn like the game's terrain today: one indirect record per run of front-facing
 * facing buckets per section-layer (S1), only sections whose tight box meets the exact frustum (S2), a 16-bit quad
 * index buffer, shaders equivalent to vanilla terrain.vsh / terrain.fsh (MULTIDRAW_TERRAIN), reverse-Z GEQUAL depth.
 *
 * Per camera pose it measures (median GPU time of each command buffer, configurations interleaved):
 *   empty      clear + store of the 3600x2260 target only (fixed cost, subtracted from nothing)
 *   full       today's terrain
 *   ideal      only the sections that contribute at least one sample to the final image (oracle: per-section
 *              visibility test of the real geometry against the final depth). This is the upper bound of ANY
 *              section-level occlusion culling, at zero cost.
 *   full+vis   today's terrain with a visibility-result counter per section (Metal occlusion queries)
 *   ideal+box  steady state of query-based culling: the visible sections with counters plus one box test per culled
 *              section after the opaque terrain (what the decoder's MCMTL_OCCLUSION encodes)
 *   full+zst   today's terrain with the depth attachment stored (a depth-pyramid design needs the depth in memory)
 *   full+hiz   full+zst plus a compute pass that builds a 1/16 min-depth tile map from it (HiZ base level)
 * and checks that "ideal" renders exactly the same bytes as "full" (the oracle criterion is pixel-exact).
 *
 * The decoder's implementation (MCMTL_OCCLUSION) is measured through the real mcmtl_submit by
 * OCCLUSION_BENCH=1 build/native-tests/occlusion_test; this program measures the upper bound and the mechanisms alone.
 *
 * Build and run: tools/occlusion-bench.sh [--frames N] [--rounds N] [--caves] [--trees DENSITY] [--loose-boxes]
 *                                         [--min-dist BLOCKS] [--pose NAME]
 *   --loose-boxes  box queries use origin + [-2, 18]^3 (what the decoder knows) instead of the tight mesh bounds
 *   --min-dist     sections whose loose box is nearer than this are never culled
 */
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#include <math.h>
#include <stdlib.h>
#include <string.h>
#include <mach/mach_time.h>

#define W 3600
#define H 2260
#define RD 12
#define HALF (13 * 16)          /* world x, z in [-HALF, HALF) */
#define WX (2 * HALF)
#define WY 256
#define NSECY (WY / 16)
#define NSEC_XZ (WX / 16)
#define MAX_QUADS_PER_DRAW 16383

enum { B_AIR = 0, B_GRASS, B_DIRT, B_STONE, B_LOG, B_LEAVES };
enum { F_DOWN = 0, F_NORTH, F_EAST, F_UP, F_SOUTH, F_WEST, F_COUNT };   /* bucket layout A (no UNASSIGNED) */

typedef struct __attribute__((packed)) {
    float x, y, z;
    uint8_t r, g, b, a;
    float u, v;
    int16_t lu, lv;
} Vtx;
_Static_assert(sizeof(Vtx) == 28, "vanilla BLOCK vertex = 28 bytes");

typedef struct { int32_t x, y, z; float vis; } SectionInfo;
typedef struct { uint32_t indexCount, instanceCount, firstIndex; int32_t baseVertex; uint32_t baseInstance; } Rec;

typedef struct {
    int ox, oy, oz;
    float bmin[3], bmax[3];               /* tight bounds, section-relative */
    /* per layer (0 solid, 1 cutout): vertex start (quads) of each bucket in that layer's heap */
    uint32_t start[2][F_COUNT + 1];
    float plane[2][F_COUNT];              /* bucket extreme plane (section-relative) */
    uint32_t quads[2];
} Section;

static uint8_t *g_world;
static float g_treeDensity = 0.004f;
static int g_tightBoxes = 1;
static float g_minDist = 0;
static int g_heights[WX][WX];
static Section *g_sections;
static int g_sectionCount;
static Vtx *g_heap[2];
static uint32_t g_heapQuads[2], g_heapCap[2];

static inline uint8_t blk(int x, int y, int z) {
    if (y < 0) return B_STONE;
    if (y >= WY) return B_AIR;
    if (x < -HALF || x >= HALF || z < -HALF || z >= HALF) return B_STONE;   /* closed world edge: no faces */
    return g_world[((size_t)(x + HALF) * WY + y) * WX + (z + HALF)];
}
static inline void setblk(int x, int y, int z, uint8_t b) {
    if (y < 0 || y >= WY || x < -HALF || x >= HALF || z < -HALF || z >= HALF) return;
    g_world[((size_t)(x + HALF) * WY + y) * WX + (z + HALF)] = b;
}

/* ---------------------------------------------------------------------------------------------- */
/* noise                                                                                            */

static inline uint32_t hash3(int x, int y, int z) {
    uint32_t h = (uint32_t)x * 374761393u + (uint32_t)y * 668265263u + (uint32_t)z * 2147483647u;
    h = (h ^ (h >> 13)) * 1274126177u;
    return h ^ (h >> 16);
}
static inline float rnd3(int x, int y, int z) { return (hash3(x, y, z) & 0xFFFFFF) / 16777216.0f; }
static inline float smooth(float t) { return t * t * (3 - 2 * t); }
static float vnoise2(float x, float z, int seed) {
    int xi = (int)floorf(x), zi = (int)floorf(z);
    float fx = smooth(x - xi), fz = smooth(z - zi);
    float a = rnd3(xi, seed, zi), b = rnd3(xi + 1, seed, zi), c = rnd3(xi, seed, zi + 1), d = rnd3(xi + 1, seed, zi + 1);
    return (a + (b - a) * fx) + ((c + (d - c) * fx) - (a + (b - a) * fx)) * fz;
}
static float fbm2(float x, float z, int seed) {
    float s = 0, amp = 1, norm = 0;
    for (int o = 0; o < 5; o++) { s += amp * vnoise2(x, z, seed + o * 31); norm += amp; x *= 2.03f; z *= 2.03f; amp *= 0.5f; }
    return s / norm;
}
static float vnoise3(float x, float y, float z, int seed) {
    int xi = (int)floorf(x), yi = (int)floorf(y), zi = (int)floorf(z);
    float fx = smooth(x - xi), fy = smooth(y - yi), fz = smooth(z - zi);
    float v[2][2][2];
    for (int i = 0; i < 2; i++) for (int j = 0; j < 2; j++) for (int k = 0; k < 2; k++)
        v[i][j][k] = rnd3(xi + i + seed * 7919, yi + j, zi + k);
    float x00 = v[0][0][0] + (v[1][0][0] - v[0][0][0]) * fx, x10 = v[0][1][0] + (v[1][1][0] - v[0][1][0]) * fx;
    float x01 = v[0][0][1] + (v[1][0][1] - v[0][0][1]) * fx, x11 = v[0][1][1] + (v[1][1][1] - v[0][1][1]) * fx;
    float y0 = x00 + (x10 - x00) * fy, y1 = x01 + (x11 - x01) * fy;
    return y0 + (y1 - y0) * fz;
}

static void generate(int caves) {
    g_world = calloc((size_t)WX * WY * WX, 1);
    for (int x = -HALF; x < HALF; x++) {
        for (int z = -HALF; z < HALF; z++) {
            float h = 62 + 46 * (fbm2(x / 170.0f, z / 170.0f, 1) - 0.5f) * 2 + 14 * (fbm2(x / 45.0f + 100, z / 45.0f, 9) - 0.5f) * 2;
            int hi = (int)h;
            if (hi < 8) hi = 8;
            if (hi > 200) hi = 200;
            g_heights[x + HALF][z + HALF] = hi;
            for (int y = 0; y < hi; y++) setblk(x, y, z, y == hi - 1 ? B_GRASS : y >= hi - 4 ? B_DIRT : B_STONE);
        }
    }
    if (caves) {
        for (int x = -HALF; x < HALF; x++)
            for (int z = -HALF; z < HALF; z++) {
                int hi = g_heights[x + HALF][z + HALF];
                for (int y = 12; y < hi - 5; y++)
                    if (vnoise3(x / 22.0f, y / 12.0f, z / 22.0f, 5) > 0.70f) setblk(x, y, z, B_AIR);
            }
    }
    /* trees */
    for (int x = -HALF + 3; x < HALF - 3; x++)
        for (int z = -HALF + 3; z < HALF - 3; z++) {
            if (rnd3(x, 777, z) > g_treeDensity) continue;
            int hi = g_heights[x + HALF][z + HALF];
            if (hi > 120) continue;
            for (int dy = -2; dy <= 2; dy++)
                for (int dx = -2; dx <= 2; dx++)
                    for (int dz = -2; dz <= 2; dz++) {
                        int r = dy > 0 ? 1 : 2;
                        if (abs(dx) > r || abs(dz) > r) continue;
                        if (abs(dx) == 2 && abs(dz) == 2 && rnd3(x + dx, hi + dy, z + dz) < 0.5f) continue;
                        if (blk(x + dx, hi + 4 + dy, z + dz) == B_AIR) setblk(x + dx, hi + 4 + dy, z + dz, B_LEAVES);
                    }
            for (int y = hi; y < hi + 5; y++) setblk(x, y, z, B_LOG);
        }
}

/* ---------------------------------------------------------------------------------------------- */
/* meshing                                                                                          */

static const int kDir[F_COUNT][3] = { {0, -1, 0}, {0, 0, -1}, {1, 0, 0}, {0, 1, 0}, {0, 0, 1}, {-1, 0, 0} };

/* Face corners (block-relative), counter-clockwise seen from outside. */
static void face_corners(int f, float c[4][3]) {
    static const float k[F_COUNT][4][3] = {
        [F_DOWN]  = { {0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1} },
        [F_UP]    = { {0, 1, 0}, {0, 1, 1}, {1, 1, 1}, {1, 1, 0} },
        [F_NORTH] = { {0, 0, 0}, {0, 1, 0}, {1, 1, 0}, {1, 0, 0} },
        [F_SOUTH] = { {0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1} },
        [F_WEST]  = { {0, 0, 0}, {0, 0, 1}, {0, 1, 1}, {0, 1, 0} },
        [F_EAST]  = { {1, 0, 0}, {1, 1, 0}, {1, 1, 1}, {1, 0, 1} },
    };
    memcpy(c, k[f], sizeof(float) * 12);
}

static MTLWinding g_winding = MTLWindingCounterClockwise;

static void heap_push(int layer, const Vtx v[4]) {
    if (g_heapQuads[layer] == g_heapCap[layer]) {
        g_heapCap[layer] = g_heapCap[layer] ? g_heapCap[layer] * 2 : 65536;
        g_heap[layer] = realloc(g_heap[layer], (size_t)g_heapCap[layer] * 4 * sizeof(Vtx));
    }
    memcpy(g_heap[layer] + (size_t)g_heapQuads[layer] * 4, v, 4 * sizeof(Vtx));
    g_heapQuads[layer]++;
}

static void tile_uv(uint8_t b, int f, float *u0, float *v0) {
    int tile;
    switch (b) {
        case B_GRASS: tile = f == F_UP ? 0 : f == F_DOWN ? 1 : 2; break;
        case B_DIRT: tile = 1; break;
        case B_LOG: tile = f == F_UP || f == F_DOWN ? 4 : 5; break;
        case B_LEAVES: tile = 6; break;
        default: tile = 3; break;
    }
    *u0 = (tile % 16) * 16 / 256.0f;
    *v0 = (tile / 16) * 16 / 256.0f;
}

static void mesh_world(void) {
    g_sections = calloc((size_t)NSEC_XZ * NSEC_XZ * NSECY, sizeof(Section));
    static const float shade[F_COUNT] = {0.5f, 0.8f, 0.6f, 1.0f, 0.8f, 0.6f};
    for (int sx = 0; sx < NSEC_XZ; sx++)
        for (int sz = 0; sz < NSEC_XZ; sz++) {
            int cx = sx - NSEC_XZ / 2, cz = sz - NSEC_XZ / 2;
            if (cx * cx + cz * cz > RD * RD) continue;   /* view-distance circle around chunk (0, 0) */
            for (int sy = 0; sy < NSECY; sy++) {
                Section s = {0};
                s.ox = cx * 16; s.oy = sy * 16; s.oz = cz * 16;
                for (int i = 0; i < 3; i++) { s.bmin[i] = 1e9f; s.bmax[i] = -1e9f; }
                for (int layer = 0; layer < 2; layer++) {
                    for (int f = 0; f < F_COUNT; f++) {
                        s.start[layer][f] = g_heapQuads[layer];
                        s.plane[layer][f] = (f == F_UP || f == F_EAST || f == F_SOUTH) ? 1e9f : -1e9f;
                        for (int y = 0; y < 16; y++)
                            for (int z = 0; z < 16; z++)
                                for (int x = 0; x < 16; x++) {
                                    int wx = s.ox + x, wy = s.oy + y, wz = s.oz + z;
                                    uint8_t b = blk(wx, wy, wz);
                                    if (b == B_AIR || (layer == 1) != (b == B_LEAVES)) continue;
                                    uint8_t nb = blk(wx + kDir[f][0], wy + kDir[f][1], wz + kDir[f][2]);
                                    if (nb != B_AIR && nb != B_LEAVES) continue;      /* hidden behind an opaque cube */
                                    float c[4][3];
                                    face_corners(f, c);
                                    float u0, v0;
                                    tile_uv(b, f, &u0, &v0);
                                    static const float uvs[4][2] = { {0, 1}, {0, 0}, {1, 0}, {1, 1} };
                                    Vtx q[4];
                                    float sh = shade[f];
                                    uint8_t base[3] = { 255, 255, 255 };
                                    if (b == B_GRASS && f == F_UP) { base[0] = 110; base[1] = 190; base[2] = 80; }
                                    if (b == B_LEAVES) { base[0] = 70; base[1] = 150; base[2] = 60; }
                                    for (int k = 0; k < 4; k++) {
                                        q[k].x = x + c[k][0]; q[k].y = y + c[k][1]; q[k].z = z + c[k][2];
                                        q[k].r = (uint8_t)(base[0] * sh); q[k].g = (uint8_t)(base[1] * sh); q[k].b = (uint8_t)(base[2] * sh); q[k].a = 255;
                                        q[k].u = u0 + uvs[k][0] * (16 / 256.0f); q[k].v = v0 + uvs[k][1] * (16 / 256.0f);
                                        q[k].lu = 240; q[k].lv = 240;
                                        float p[3] = { q[k].x, q[k].y, q[k].z };
                                        for (int i = 0; i < 3; i++) { if (p[i] < s.bmin[i]) s.bmin[i] = p[i]; if (p[i] > s.bmax[i]) s.bmax[i] = p[i]; }
                                    }
                                    float pl = (f == F_DOWN || f == F_UP) ? q[0].y : (f == F_NORTH || f == F_SOUTH) ? q[0].z : q[0].x;
                                    if (f == F_UP || f == F_EAST || f == F_SOUTH) { if (pl < s.plane[layer][f]) s.plane[layer][f] = pl; }
                                    else if (pl > s.plane[layer][f]) s.plane[layer][f] = pl;
                                    heap_push(layer, q);
                                }
                    }
                    s.start[layer][F_COUNT] = g_heapQuads[layer];
                    s.quads[layer] = s.start[layer][F_COUNT] - s.start[layer][0];
                }
                if (s.quads[0] + s.quads[1]) g_sections[g_sectionCount++] = s;
            }
        }
}

/* ---------------------------------------------------------------------------------------------- */
/* math                                                                                             */

typedef struct { float m[16]; } M4;   /* column-major */
static M4 m4_mul(M4 a, M4 b) {
    M4 r;
    for (int c = 0; c < 4; c++) for (int rr = 0; rr < 4; rr++) {
        float s = 0;
        for (int k = 0; k < 4; k++) s += a.m[k * 4 + rr] * b.m[c * 4 + k];
        r.m[c * 4 + rr] = s;
    }
    return r;
}
static M4 m4_persp_reverse(float fovy, float aspect, float n, float f) {
    /* JOML setPerspective(fovy, aspect, zNear = f, zFar = n, zZeroToOne = true): reverse-Z, depth 1 at n, 0 at f */
    float t = 1.0f / tanf(fovy * 0.5f);
    M4 r = {{0}};
    r.m[0] = t / aspect; r.m[5] = t;
    float zn = f, zf = n;
    r.m[10] = zf / (zn - zf);
    r.m[11] = -1;
    r.m[14] = zn * zf / (zn - zf);
    return r;
}
static M4 m4_rot_x(float a) { M4 r = {{1,0,0,0, 0,cosf(a),sinf(a),0, 0,-sinf(a),cosf(a),0, 0,0,0,1}}; return r; }
static M4 m4_rot_y(float a) { M4 r = {{cosf(a),0,-sinf(a),0, 0,1,0,0, sinf(a),0,cosf(a),0, 0,0,0,1}}; return r; }

typedef struct {
    const char *name;
    double x, y, z;     /* eye */
    float yaw, pitch;   /* degrees, Minecraft convention */
} Pose;

typedef struct { float p[6][4]; } Frustum;
static Frustum frustum_of(M4 vp) {
    Frustum fr;
    const float *m = vp.m;
    float row[4][4];
    for (int r = 0; r < 4; r++) for (int c = 0; c < 4; c++) row[r][c] = m[c * 4 + r];
    for (int i = 0; i < 4; i++) {
        fr.p[0][i] = row[3][i] + row[0][i];
        fr.p[1][i] = row[3][i] - row[0][i];
        fr.p[2][i] = row[3][i] + row[1][i];
        fr.p[3][i] = row[3][i] - row[1][i];
        fr.p[4][i] = row[2][i];               /* z >= 0 (far, reverse-Z) */
        fr.p[5][i] = row[3][i] - row[2][i];   /* z <= w (near) */
    }
    return fr;
}
static int box_visible(const Frustum *fr, float x0, float y0, float z0, float x1, float y1, float z1) {
    for (int i = 0; i < 6; i++) {
        const float *p = fr->p[i];
        float d = p[0] * (p[0] >= 0 ? x1 : x0) + p[1] * (p[1] >= 0 ? y1 : y0) + p[2] * (p[2] >= 0 ? z1 : z0) + p[3];
        if (d < 0) return 0;
    }
    return 1;
}

/* ---------------------------------------------------------------------------------------------- */
/* shaders                                                                                          */

static NSString *const kShaders =
    @"#include <metal_stdlib>\n"
     "using namespace metal;\n"
     "struct Proj { float4x4 proj; };\n"
     "struct Globals { packed_int3 cameraBlockPos; float glint; packed_float3 cameraOffset; float gameTime; float2 screen; int blur; int rgss; };\n"
     "struct Terrain { float4x4 mv; int2 texSize; };\n"
     "struct Fog { float4 color; float envStart, envEnd, rdStart, rdEnd, skyEnd, cloudsEnd; };\n"
     "struct SectionInfo { packed_int3 origin; float vis; };\n"
     "struct VIn { float3 pos [[attribute(0)]]; float4 color [[attribute(1)]]; float2 uv0 [[attribute(2)]];\n"
     "             short2 uv2 [[attribute(3)]]; int3 chunk [[attribute(4)]]; float vis [[attribute(5)]]; };\n"
     "struct VOut { float4 position [[position, invariant]]; float sph; float cyl; float4 color; float2 uv; float vis; };\n"
     "vertex VOut terrain_vs(VIn in [[stage_in]], constant Proj &P [[buffer(0)]], constant Globals &G [[buffer(1)]],\n"
     "                       constant Terrain &T [[buffer(5)]], texture2d<float> lightmap [[texture(2)]], sampler ls [[sampler(2)]]) {\n"
     "    float3 pos = in.pos + float3(in.chunk - int3(G.cameraBlockPos)) + float3(G.cameraOffset);\n"
     "    VOut o;\n"
     "    o.position = P.proj * (T.mv * float4(pos, 1.0));\n"
     "    o.sph = length(pos);\n"
     "    o.cyl = max(length(pos.xz), abs(pos.y));\n"
     "    float2 luv = clamp(float2(in.uv2) / 256.0 + 0.5 / 16.0, float2(0.5 / 16.0), float2(15.5 / 16.0));\n"
     "    o.color = in.color * lightmap.sample(ls, luv, level(0));\n"
     "    o.uv = in.uv0;\n"
     "    o.vis = mix(1.0, in.vis, clamp((length(pos) - 16.0) / 16.0, 0.0, 1.0));\n"
     "    return o;\n"
     "}\n"
     "static float lin_fog(float d, float s, float e) { return d <= s ? 0.0 : d >= e ? 1.0 : (d - s) / (e - s); }\n"
     "static float4 terrain_color(VOut in, constant Fog &F, constant Terrain &T, texture2d<float> atlas, sampler as, thread bool &keep, float cutout) {\n"
     "    float2 pixelSize = 1.0 / float2(T.texSize);\n"
     "    float2 du = dfdx(in.uv), dv = dfdy(in.uv);\n"
     "    float2 tss = sqrt(du * du + dv * dv);\n"
     "    float2 tc = in.uv / pixelSize;\n"
     "    float2 center = round(tc) - 0.5;\n"
     "    float2 off = clamp((tc - center - 0.5) * pixelSize / tss + 0.5, 0.0, 1.0);\n"
     "    float4 c = atlas.sample(as, (center + off) * pixelSize, gradient2d(du, dv)) * in.color;\n"
     "    c = mix(F.color * float4(1, 1, 1, c.a), c, in.vis);\n"
     "    keep = c.a >= cutout;\n"
     "    float fv = max(lin_fog(in.sph, F.envStart, F.envEnd), lin_fog(in.cyl, F.rdStart, F.rdEnd));\n"
     "    return float4(mix(c.rgb, F.color.rgb, fv * F.color.a), c.a);\n"
     "}\n"
     "fragment float4 solid_fs(VOut in [[stage_in]], constant Fog &F [[buffer(2)]], constant Terrain &T [[buffer(5)]],\n"
     "                         texture2d<float> atlas [[texture(0)]], sampler as [[sampler(0)]]) {\n"
     "    bool keep; return terrain_color(in, F, T, atlas, as, keep, -1.0);\n"
     "}\n"
     "fragment float4 cutout_fs(VOut in [[stage_in]], constant Fog &F [[buffer(2)]], constant Terrain &T [[buffer(5)]],\n"
     "                          texture2d<float> atlas [[texture(0)]], sampler as [[sampler(0)]]) {\n"
     "    bool keep; float4 c = terrain_color(in, F, T, atlas, as, keep, 0.5);\n"
     "    if (!keep) discard_fragment();\n"
     "    return c;\n"
     "}\n"
     /* box test: 36 vertices of the section box [-2, 18]^3 (section-relative), from the instance's origin */
     "struct BoxOut { float4 position [[position, invariant]]; };\n"
     "vertex BoxOut box_vs(uint vid [[vertex_id]], uint iid [[instance_id]], constant Proj &P [[buffer(0)]],\n"
     "                     constant Globals &G [[buffer(1)]], constant Terrain &T [[buffer(5)]],\n"
     "                     const device SectionInfo *sections [[buffer(29)]], const device float4 *bounds [[buffer(28)]]) {\n"
     "    const ushort idx[36] = {0,4,6, 0,6,2, 1,3,7, 1,7,5, 0,1,5, 0,5,4, 2,6,7, 2,7,3, 0,2,3, 0,3,1, 4,5,7, 4,7,6};\n"
     "    ushort c = idx[vid];\n"
     "    float3 lo = bounds[2 * iid].xyz, hi = bounds[2 * iid + 1].xyz;\n"
     "    float3 corner = float3((c & 1) ? hi.x : lo.x, (c & 2) ? hi.y : lo.y, (c & 4) ? hi.z : lo.z);\n"
     "    float3 pos = corner + float3(int3(sections[iid].origin) - int3(G.cameraBlockPos)) + float3(G.cameraOffset);\n"
     "    BoxOut o; o.position = P.proj * (T.mv * float4(pos, 1.0)); return o;\n"
     "}\n"
     /* HiZ base level: min (farthest, reverse-Z) depth of 16x16 tiles */
     "kernel void hiz_tiles(depth2d<float, access::read> depth [[texture(0)]], texture2d<float, access::write> out [[texture(1)]],\n"
     "                      uint2 tg [[threadgroup_position_in_grid]], uint2 tid [[thread_position_in_threadgroup]],\n"
     "                      uint lane [[thread_index_in_simdgroup]], uint sg [[simdgroup_index_in_threadgroup]]) {\n"
     "    threadgroup float partial[8];\n"
     "    uint2 base = tg * 16;\n"
     "    float m = 1.0;\n"
     "    for (uint i = 0; i < 2; i++) {\n"
     "        uint2 p = base + uint2(tid.x, tid.y * 2 + i);\n"
     "        if (p.x < depth.get_width() && p.y < depth.get_height()) m = min(m, depth.read(p));\n"
     "    }\n"
     "    m = simd_min(m);\n"
     "    if (lane == 0) partial[sg] = m;\n"
     "    threadgroup_barrier(mem_flags::mem_threadgroup);\n"
     "    if (tid.x == 0 && tid.y == 0) {\n"
     "        float r = partial[0];\n"
     "        for (uint i = 1; i < 4; i++) r = min(r, partial[i]);\n"
     "        out.write(float4(r), tg);\n"
     "    }\n"
     "}\n";

/* ---------------------------------------------------------------------------------------------- */

typedef struct {
    id<MTLDevice> dev;
    id<MTLCommandQueue> q;
    id<MTLRenderPipelineState> solid, cutout, solidNoWrite, cutoutNoWrite, box;
    id<MTLComputePipelineState> hiz;
    __unsafe_unretained id<MTLBuffer> visCur;   /* timing runs: one visibility buffer per frame in flight */
    uint32_t visNext;
    id<MTLDepthStencilState> dssWrite, dssTest;
    id<MTLBuffer> heap[2], index16, sectionBuf, boundsBuf, recBuf, visBuf, visRing[4], proj, globals, terrainU, fog;
    id<MTLTexture> color, depth, depthStored, hizTex, atlas, lightmap;
    id<MTLSamplerState> atlasSampler, lightSampler;
} Gpu;

typedef struct {
    uint32_t recStart[2], recCount[2];   /* records in recBuf per layer */
    uint32_t *recSection;                /* section index per record */
    int nrec;
    int *drawnSections;                  /* sections with at least one record */
    int ndrawn;
    uint64_t quads;
} DrawList;

static void build_draw_list(Gpu *g, const Pose *pose, const Frustum *fr, const uint8_t *keep /* per section or NULL */, DrawList *dl,
                            Rec *recs) {
    int n = 0;
    dl->quads = 0;
    dl->ndrawn = 0;
    static uint8_t *drawnFlag;
    if (!drawnFlag) drawnFlag = calloc(g_sectionCount, 1);
    memset(drawnFlag, 0, g_sectionCount);
    for (int layer = 0; layer < 2; layer++) {
        dl->recStart[layer] = n;
        for (int i = 0; i < g_sectionCount; i++) {
            Section *s = &g_sections[i];
            if (!s->quads[layer]) continue;
            if (keep && !keep[i]) continue;
            float m = 1.0f / 64;
            if (!box_visible(fr, (float)(s->ox + s->bmin[0] - m - pose->x), (float)(s->oy + s->bmin[1] - m - pose->y), (float)(s->oz + s->bmin[2] - m - pose->z),
                             (float)(s->ox + s->bmax[0] + m - pose->x), (float)(s->oy + s->bmax[1] + m - pose->y), (float)(s->oz + s->bmax[2] + m - pose->z)))
                continue;
            double ex = pose->x - s->ox, ey = pose->y - s->oy, ez = pose->z - s->oz;
            const double eps = 0.25;
            int vis[F_COUNT];
            vis[F_DOWN] = ey < s->plane[layer][F_DOWN] + eps;
            vis[F_NORTH] = ez < s->plane[layer][F_NORTH] + eps;
            vis[F_EAST] = ex > s->plane[layer][F_EAST] - eps;
            vis[F_UP] = ey > s->plane[layer][F_UP] - eps;
            vis[F_SOUTH] = ez > s->plane[layer][F_SOUTH] - eps;
            vis[F_WEST] = ex < s->plane[layer][F_WEST] + eps;
            /* runs of visible non-empty buckets, gaps of empty buckets bridged */
            int f = 0;
            while (f < F_COUNT) {
                while (f < F_COUNT && (!vis[f] || s->start[layer][f] == s->start[layer][f + 1])) f++;
                if (f >= F_COUNT) break;
                uint32_t q0 = s->start[layer][f];
                int e = f;
                while (e + 1 < F_COUNT && (vis[e + 1] || s->start[layer][e + 1] == s->start[layer][e + 2])) e++;
                uint32_t q1 = s->start[layer][e + 1];
                for (uint32_t q = q0; q < q1; q += MAX_QUADS_PER_DRAW) {
                    uint32_t cnt = q1 - q < MAX_QUADS_PER_DRAW ? q1 - q : MAX_QUADS_PER_DRAW;
                    recs[n] = (Rec){ 6 * cnt, 1, 0, (int32_t)(4 * q), (uint32_t)i };
                    dl->recSection[n] = i;
                    n++;
                    dl->quads += cnt;
                }
                f = e + 1;
            }
            if (!drawnFlag[i]) { drawnFlag[i] = 1; dl->drawnSections[dl->ndrawn++] = i; }
        }
        dl->recCount[layer] = n - dl->recStart[layer];
    }
    dl->nrec = n;
}

static MTLRenderPassDescriptor *pass_desc(Gpu *g, BOOL storeDepth, BOOL vis) {
    MTLRenderPassDescriptor *d = [MTLRenderPassDescriptor renderPassDescriptor];
    d.colorAttachments[0].texture = g->color;
    d.colorAttachments[0].loadAction = MTLLoadActionClear;
    d.colorAttachments[0].clearColor = MTLClearColorMake(0.62, 0.75, 1.0, 1.0);
    d.colorAttachments[0].storeAction = MTLStoreActionStore;
    d.depthAttachment.texture = storeDepth ? g->depthStored : g->depth;
    d.depthAttachment.loadAction = MTLLoadActionClear;
    d.depthAttachment.clearDepth = 0.0;
    d.depthAttachment.storeAction = storeDepth ? MTLStoreActionStore : MTLStoreActionDontCare;
    if (vis) d.visibilityResultBuffer = g->visCur ? g->visCur : g->visBuf;
    return d;
}

static void bind_common(Gpu *g, id<MTLRenderCommandEncoder> re) {
    [re setVertexBuffer:g->proj offset:0 atIndex:0];
    [re setVertexBuffer:g->globals offset:0 atIndex:1];
    [re setVertexBuffer:g->terrainU offset:0 atIndex:5];
    [re setFragmentBuffer:g->fog offset:0 atIndex:2];
    [re setFragmentBuffer:g->terrainU offset:0 atIndex:5];
    [re setVertexTexture:g->lightmap atIndex:2];
    [re setVertexSamplerState:g->lightSampler atIndex:2];
    [re setFragmentTexture:g->atlas atIndex:0];
    [re setFragmentSamplerState:g->atlasSampler atIndex:0];
    [re setVertexBuffer:g->sectionBuf offset:0 atIndex:29];
    [re setVertexBuffer:g->boundsBuf offset:0 atIndex:28];
    [re setCullMode:MTLCullModeBack];
    [re setFrontFacingWinding:g_winding];
    [re setDepthStencilState:g->dssWrite];
}

/* Encodes the terrain of `dl` (records at recBuf offset 0). visMode: 0 none, 1 counting per section. */
static void encode_terrain(Gpu *g, id<MTLRenderCommandEncoder> re, const DrawList *dl, int visMode, id<MTLRenderPipelineState> solid,
                           id<MTLRenderPipelineState> cutout) {
    bind_common(g, re);
    for (int layer = 0; layer < 2; layer++) {
        if (!dl->recCount[layer]) continue;
        [re setRenderPipelineState:layer ? cutout : solid];
        [re setVertexBuffer:g->heap[layer] offset:0 atIndex:30];
        uint32_t lastSec = UINT32_MAX;
        for (uint32_t r = dl->recStart[layer]; r < dl->recStart[layer] + dl->recCount[layer]; r++) {
            if (visMode && dl->recSection[r] != lastSec) {
                lastSec = dl->recSection[r];
                [re setVisibilityResultMode:MTLVisibilityResultModeCounting offset:8 * lastSec];
            }
            [re drawIndexedPrimitives:MTLPrimitiveTypeTriangle indexType:MTLIndexTypeUInt16 indexBuffer:g->index16 indexBufferOffset:0
                       indirectBuffer:g->recBuf indirectBufferOffset:20 * r];
        }
    }
    if (visMode) [re setVisibilityResultMode:MTLVisibilityResultModeDisabled offset:0];
}

static void encode_boxes(Gpu *g, id<MTLRenderCommandEncoder> re, const int *secs, int n) {
    [re setRenderPipelineState:g->box];
    [re setDepthStencilState:g->dssTest];
    [re setCullMode:MTLCullModeBack];   /* faces are CCW from outside; an eye inside a box never culls it */
    for (int i = 0; i < n; i++) {
        [re setVisibilityResultMode:MTLVisibilityResultModeCounting offset:8 * secs[i]];
        [re drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:36 instanceCount:1 baseInstance:secs[i]];
    }
    [re setVisibilityResultMode:MTLVisibilityResultModeDisabled offset:0];
}

static void set_uniforms(Gpu *g, const Pose *pose) {
    M4 proj = m4_persp_reverse(77.0f * (float)M_PI / 180.0f, (float)W / H, 0.05f, 1024.0f);
    M4 view = m4_mul(m4_rot_x(pose->pitch * (float)M_PI / 180.0f), m4_rot_y((pose->yaw + 180.0f) * (float)M_PI / 180.0f));
    memcpy(g->proj.contents, proj.m, 64);
    int bx = (int)floor(pose->x), by = (int)floor(pose->y), bz = (int)floor(pose->z);
    uint8_t *gl = g->globals.contents;
    memset(gl, 0, 48);
    ((int32_t *)gl)[0] = bx; ((int32_t *)gl)[1] = by; ((int32_t *)gl)[2] = bz;
    ((float *)gl)[4] = (float)(bx - pose->x); ((float *)gl)[5] = (float)(by - pose->y); ((float *)gl)[6] = (float)(bz - pose->z);
    ((float *)gl)[8] = W; ((float *)gl)[9] = H;
    uint8_t *t = g->terrainU.contents;
    memcpy(t, view.m, 64);
    ((int32_t *)(t + 64))[0] = 256; ((int32_t *)(t + 64))[1] = 256;
    float *f = g->fog.contents;
    f[0] = 0.62f; f[1] = 0.75f; f[2] = 1.0f; f[3] = 1.0f;
    f[4] = 1e6f; f[5] = 2e6f; f[6] = RD * 16 - 19.2f; f[7] = RD * 16; f[8] = 1e6f; f[9] = 1e6f;
}

static M4 view_proj(const Pose *pose) {
    M4 proj = m4_persp_reverse(77.0f * (float)M_PI / 180.0f, (float)W / H, 0.05f, 1024.0f);
    M4 view = m4_mul(m4_rot_x(pose->pitch * (float)M_PI / 180.0f), m4_rot_y((pose->yaw + 180.0f) * (float)M_PI / 180.0f));
    return m4_mul(proj, view);
}

static double now_ms(void) {
    static mach_timebase_info_data_t tb;
    if (!tb.denom) mach_timebase_info(&tb);
    return (double)mach_absolute_time() * tb.numer / tb.denom / 1e6;
}

static int cmp_double(const void *a, const void *b) {
    double x = *(const double *)a, y = *(const double *)b;
    return x < y ? -1 : x > y;
}

enum { C_EMPTY, C_FULL, C_IDEAL, C_FULL_VIS, C_IDEAL_BOX, C_FULL_ZST, C_FULL_HIZ, C_COUNT };
static const char *kConfigNames[C_COUNT] = { "empty", "full", "ideal", "full+vis", "ideal+box", "full+zst", "full+hiz" };

static void encode_config(Gpu *g, id<MTLCommandBuffer> cb, int c, const DrawList *full, const DrawList *ideal, const int *culled, int nculled,
                          id<MTLBuffer> fullRecs, id<MTLBuffer> idealRecs) {
    BOOL zst = c == C_FULL_ZST || c == C_FULL_HIZ;
    BOOL vis = c == C_FULL_VIS || c == C_IDEAL_BOX;
    id<MTLRenderCommandEncoder> re = [cb renderCommandEncoderWithDescriptor:pass_desc(g, zst, vis)];
    const DrawList *dl = (c == C_IDEAL || c == C_IDEAL_BOX) ? ideal : full;
    g->recBuf = (c == C_IDEAL || c == C_IDEAL_BOX) ? idealRecs : fullRecs;
    if (c != C_EMPTY) encode_terrain(g, re, dl, vis, g->solid, g->cutout);
    if (c == C_IDEAL_BOX) encode_boxes(g, re, culled, nculled);
    [re endEncoding];
    if (c == C_FULL_HIZ) {
        id<MTLComputeCommandEncoder> ce = [cb computeCommandEncoder];
        [ce setComputePipelineState:g->hiz];
        [ce setTexture:g->depthStored atIndex:0];
        [ce setTexture:g->hizTex atIndex:1];
        [ce dispatchThreadgroups:MTLSizeMake((W + 15) / 16, (H + 15) / 16, 1) threadsPerThreadgroup:MTLSizeMake(16, 8, 1)];
        [ce endEncoding];
    }
}

static void readback(Gpu *g, id<MTLTexture> tex, uint8_t *out, size_t bpp) {
    id<MTLBuffer> rb = [g->dev newBufferWithLength:(NSUInteger)W * H * bpp options:MTLResourceStorageModeShared];
    id<MTLCommandBuffer> cb = [g->q commandBuffer];
    id<MTLBlitCommandEncoder> be = [cb blitCommandEncoder];
    [be copyFromTexture:tex sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0, 0, 0) sourceSize:MTLSizeMake(W, H, 1)
               toBuffer:rb destinationOffset:0 destinationBytesPerRow:W * bpp destinationBytesPerImage:(NSUInteger)W * H * bpp];
    [be endEncoding];
    [cb commit];
    [cb waitUntilCompleted];
    memcpy(out, rb.contents, (size_t)W * H * bpp);
}

int main(int argc, const char **argv) {
    @autoreleasepool {
        int frames = 120, rounds = 5, caves = 0;
        const char *onlyPose = NULL;
        for (int i = 1; i < argc; i++) {
            if (!strcmp(argv[i], "--frames") && i + 1 < argc) frames = atoi(argv[++i]);
            else if (!strcmp(argv[i], "--rounds") && i + 1 < argc) rounds = atoi(argv[++i]);
            else if (!strcmp(argv[i], "--caves")) caves = 1;
            else if (!strcmp(argv[i], "--loose-boxes")) g_tightBoxes = 0;
            else if (!strcmp(argv[i], "--min-dist") && i + 1 < argc) g_minDist = (float)atof(argv[++i]);
            else if (!strcmp(argv[i], "--trees") && i + 1 < argc) g_treeDensity = (float)atof(argv[++i]);
            else if (!strcmp(argv[i], "--pose") && i + 1 < argc) onlyPose = argv[++i];
        }
        double t0 = now_ms();
        generate(caves);
        mesh_world();
        printf("world: %d sections with geometry, %u solid + %u cutout quads (%.0f ms)%s, trees %.4f, %s boxes\n", g_sectionCount, g_heapQuads[0],
               g_heapQuads[1], now_ms() - t0, caves ? ", caves" : "", g_treeDensity, g_tightBoxes ? "tight" : "loose [-2, 18]");
        printf("sections nearer than %.1f blocks (box distance) are never culled\n", g_minDist > 0.5 ? g_minDist : 0.5);

        Gpu G = {0}, *g = &G;
        g->dev = MTLCreateSystemDefaultDevice();
        g->q = [g->dev newCommandQueue];
        NSError *err = nil;
        MTLCompileOptions *opts = [MTLCompileOptions new];
        opts.preserveInvariance = YES;
        id<MTLLibrary> lib = [g->dev newLibraryWithSource:kShaders options:opts error:&err];
        if (!lib) { fprintf(stderr, "shader compile failed: %s\n", err.description.UTF8String); return 1; }
        MTLVertexDescriptor *vd = [MTLVertexDescriptor vertexDescriptor];
        vd.attributes[0].format = MTLVertexFormatFloat3; vd.attributes[0].offset = 0; vd.attributes[0].bufferIndex = 30;
        vd.attributes[1].format = MTLVertexFormatUChar4Normalized; vd.attributes[1].offset = 12; vd.attributes[1].bufferIndex = 30;
        vd.attributes[2].format = MTLVertexFormatFloat2; vd.attributes[2].offset = 16; vd.attributes[2].bufferIndex = 30;
        vd.attributes[3].format = MTLVertexFormatShort2; vd.attributes[3].offset = 24; vd.attributes[3].bufferIndex = 30;
        vd.attributes[4].format = MTLVertexFormatInt3; vd.attributes[4].offset = 0; vd.attributes[4].bufferIndex = 29;
        vd.attributes[5].format = MTLVertexFormatFloat; vd.attributes[5].offset = 12; vd.attributes[5].bufferIndex = 29;
        vd.layouts[30].stride = 28; vd.layouts[30].stepFunction = MTLVertexStepFunctionPerVertex;
        vd.layouts[29].stride = 16; vd.layouts[29].stepFunction = MTLVertexStepFunctionPerInstance; vd.layouts[29].stepRate = 1;
        id<MTLRenderPipelineState> (^pso)(NSString *, NSString *, BOOL, BOOL) = ^id<MTLRenderPipelineState>(NSString *vs, NSString *fs, BOOL write, BOOL useVd) {
            MTLRenderPipelineDescriptor *d = [MTLRenderPipelineDescriptor new];
            d.vertexFunction = [lib newFunctionWithName:vs];
            d.fragmentFunction = fs ? [lib newFunctionWithName:fs] : nil;
            if (useVd) d.vertexDescriptor = vd;
            d.colorAttachments[0].pixelFormat = MTLPixelFormatBGRA8Unorm;
            d.colorAttachments[0].writeMask = write ? MTLColorWriteMaskAll : MTLColorWriteMaskNone;
            d.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
            NSError *e = nil;
            id<MTLRenderPipelineState> s = [g->dev newRenderPipelineStateWithDescriptor:d error:&e];
            if (!s) { fprintf(stderr, "pso %s failed: %s\n", vs.UTF8String, e.description.UTF8String); exit(1); }
            return s;
        };
        g->solid = pso(@"terrain_vs", @"solid_fs", YES, YES);
        g->cutout = pso(@"terrain_vs", @"cutout_fs", YES, YES);
        g->solidNoWrite = pso(@"terrain_vs", @"solid_fs", NO, YES);
        g->cutoutNoWrite = pso(@"terrain_vs", @"cutout_fs", NO, YES);
        g->box = pso(@"box_vs", nil, NO, NO);
        g->hiz = [g->dev newComputePipelineStateWithFunction:[lib newFunctionWithName:@"hiz_tiles"] error:&err];
        MTLDepthStencilDescriptor *dsd = [MTLDepthStencilDescriptor new];
        dsd.depthCompareFunction = MTLCompareFunctionGreaterEqual;
        dsd.depthWriteEnabled = YES;
        g->dssWrite = [g->dev newDepthStencilStateWithDescriptor:dsd];
        dsd.depthWriteEnabled = NO;
        g->dssTest = [g->dev newDepthStencilStateWithDescriptor:dsd];

        for (int l = 0; l < 2; l++)
            g->heap[l] = [g->dev newBufferWithBytes:g_heap[l] length:(NSUInteger)g_heapQuads[l] * 4 * sizeof(Vtx) options:MTLResourceStorageModeShared];
        uint16_t *idx = malloc(MAX_QUADS_PER_DRAW * 6 * 2);
        for (int q = 0; q < MAX_QUADS_PER_DRAW; q++) {
            uint16_t v = (uint16_t)(4 * q);
            uint16_t six[6] = { v, (uint16_t)(v + 1), (uint16_t)(v + 2), (uint16_t)(v + 2), (uint16_t)(v + 3), v };
            memcpy(idx + 6 * q, six, 12);
        }
        g->index16 = [g->dev newBufferWithBytes:idx length:MAX_QUADS_PER_DRAW * 12 options:MTLResourceStorageModeShared];
        SectionInfo *si = malloc(sizeof(SectionInfo) * g_sectionCount);
        for (int i = 0; i < g_sectionCount; i++) si[i] = (SectionInfo){ g_sections[i].ox, g_sections[i].oy, g_sections[i].oz, 1.0f };
        g->sectionBuf = [g->dev newBufferWithBytes:si length:sizeof(SectionInfo) * g_sectionCount options:MTLResourceStorageModeShared];
        g->boundsBuf = [g->dev newBufferWithLength:32 * (NSUInteger)g_sectionCount options:MTLResourceStorageModeShared];
        for (int i = 0; i < g_sectionCount; i++) {
            float *b = (float *)g->boundsBuf.contents + 8 * i;
            for (int k = 0; k < 3; k++) {
                b[k] = g_tightBoxes ? g_sections[i].bmin[k] - 0.25f : -2.0f;
                b[4 + k] = g_tightBoxes ? g_sections[i].bmax[k] + 0.25f : 18.0f;
            }
        }
        g->visBuf = [g->dev newBufferWithLength:8 * (NSUInteger)g_sectionCount options:MTLResourceStorageModeShared];
        for (int k = 0; k < 4; k++) g->visRing[k] = [g->dev newBufferWithLength:8 * (NSUInteger)g_sectionCount options:MTLResourceStorageModeShared];
        g->proj = [g->dev newBufferWithLength:256 options:MTLResourceStorageModeShared];
        g->globals = [g->dev newBufferWithLength:256 options:MTLResourceStorageModeShared];
        g->terrainU = [g->dev newBufferWithLength:256 options:MTLResourceStorageModeShared];
        g->fog = [g->dev newBufferWithLength:256 options:MTLResourceStorageModeShared];

        MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatBGRA8Unorm width:W height:H mipmapped:NO];
        td.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
        td.storageMode = MTLStorageModePrivate;
        g->color = [g->dev newTextureWithDescriptor:td];
        td.pixelFormat = MTLPixelFormatDepth32Float;
        g->depth = [g->dev newTextureWithDescriptor:td];
        g->depthStored = [g->dev newTextureWithDescriptor:td];
        MTLTextureDescriptor *hd = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatR32Float width:(W + 15) / 16
                                                                                    height:(H + 15) / 16 mipmapped:NO];
        hd.usage = MTLTextureUsageShaderWrite | MTLTextureUsageShaderRead;
        hd.storageMode = MTLStorageModePrivate;
        g->hizTex = [g->dev newTextureWithDescriptor:hd];

        /* atlas: 256x256, 16x16 tiles of noise; tile 6 = leaves with ~35% transparent texels; mipmapped */
        MTLTextureDescriptor *ad = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:256 height:256 mipmapped:YES];
        ad.mipmapLevelCount = 5;
        g->atlas = [g->dev newTextureWithDescriptor:ad];
        for (int lvl = 0; lvl < 5; lvl++) {
            int s = 256 >> lvl;
            uint8_t *px = malloc((size_t)s * s * 4);
            for (int y = 0; y < s; y++)
                for (int x = 0; x < s; x++) {
                    int tx = (x << lvl) / 16, ty = (y << lvl) / 16;
                    int tile = ty * 16 + tx;
                    float n = rnd3((x << lvl) & 15, ((y << lvl) & 15) + 100 * lvl, tile);
                    uint8_t v = (uint8_t)(170 + 85 * n);
                    uint8_t a = 255;
                    if (tile == 6 && rnd3(x, y + 1000 * lvl, 99) < 0.35f) a = 0;
                    uint8_t *p = px + 4 * (y * s + x);
                    p[0] = v; p[1] = v; p[2] = v; p[3] = a;
                }
            [g->atlas replaceRegion:MTLRegionMake2D(0, 0, s, s) mipmapLevel:lvl withBytes:px bytesPerRow:s * 4];
            free(px);
        }
        MTLTextureDescriptor *ld = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:16 height:16 mipmapped:NO];
        g->lightmap = [g->dev newTextureWithDescriptor:ld];
        uint8_t lm[16 * 16 * 4];
        memset(lm, 255, sizeof lm);
        [g->lightmap replaceRegion:MTLRegionMake2D(0, 0, 16, 16) mipmapLevel:0 withBytes:lm bytesPerRow:64];
        MTLSamplerDescriptor *sd = [MTLSamplerDescriptor new];
        sd.minFilter = MTLSamplerMinMagFilterLinear; sd.magFilter = MTLSamplerMinMagFilterLinear; sd.mipFilter = MTLSamplerMipFilterLinear;
        sd.sAddressMode = MTLSamplerAddressModeClampToEdge; sd.tAddressMode = MTLSamplerAddressModeClampToEdge;
        g->atlasSampler = [g->dev newSamplerStateWithDescriptor:sd];
        sd.mipFilter = MTLSamplerMipFilterNotMipmapped;
        g->lightSampler = [g->dev newSamplerStateWithDescriptor:sd];

        /* camera poses */
        int h00 = g_heights[HALF][HALF];
        /* lowest point within 24 blocks of the origin: a valley floor for the ground pose */
        int gx = 0, gz = 0, gh = 1 << 30;
        for (int x = -24; x <= 24; x++)
            for (int z = -24; z <= 24; z++)
                if (g_heights[x + HALF][z + HALF] < gh) { gh = g_heights[x + HALF][z + HALF]; gx = x; gz = z; }
        Pose poses[] = {
            { "bench yaw 0   (40 above, pitch 12)", 0.5, h00 + 40 + 1.62, 0.5, 0, 12 },
            { "bench yaw 90  (40 above, pitch 12)", 0.5, h00 + 40 + 1.62, 0.5, 90, 12 },
            { "bench yaw 180 (40 above, pitch 12)", 0.5, h00 + 40 + 1.62, 0.5, 180, 12 },
            { "bench yaw 270 (40 above, pitch 12)", 0.5, h00 + 40 + 1.62, 0.5, 270, 12 },
            { "ground yaw 0  (valley, eye 1.62)", gx + 0.5, gh + 1.62, gz + 0.5, 0, 0 },
            { "ground yaw 90 (valley, eye 1.62)", gx + 0.5, gh + 1.62, gz + 0.5, 90, 0 },
            { "ground yaw 180 (valley, eye 1.62)", gx + 0.5, gh + 1.62, gz + 0.5, 180, 0 },
            { "ground yaw 270 (valley, eye 1.62)", gx + 0.5, gh + 1.62, gz + 0.5, 270, 0 },
        };
        int nposes = sizeof poses / sizeof poses[0];

        /* winding calibration: a closed scene seen with back-face culling must equal the unculled image */
        Rec *recsFull = malloc(sizeof(Rec) * 200000), *recsIdeal = malloc(sizeof(Rec) * 200000);
        DrawList full = {0}, ideal = {0};
        full.recSection = malloc(4 * 200000); ideal.recSection = malloc(4 * 200000);
        full.drawnSections = malloc(4 * g_sectionCount); ideal.drawnSections = malloc(4 * g_sectionCount);
        uint8_t *img0 = malloc((size_t)W * H * 4), *img1 = malloc((size_t)W * H * 4);
        uint8_t *keep = malloc(g_sectionCount);
        int *culled = malloc(4 * g_sectionCount);

        for (int attempt = 0; attempt < 2; attempt++) {
            set_uniforms(g, &poses[0]);
            Frustum fr0 = frustum_of(view_proj(&poses[0]));
            build_draw_list(g, &poses[0], &fr0, NULL, &full, recsFull);
            id<MTLBuffer> rb0 = [g->dev newBufferWithBytes:recsFull length:sizeof(Rec) * (full.nrec ? full.nrec : 1) options:MTLResourceStorageModeShared];
            id<MTLCommandBuffer> cb = [g->q commandBuffer];
            encode_config(g, cb, C_FULL, &full, &full, NULL, 0, rb0, rb0);
            [cb commit];
            [cb waitUntilCompleted];
            readback(g, g->color, img0, 4);
            long ground = 0;
            for (int x = 0; x < W; x++) {
                const uint8_t *p = img0 + 4 * ((size_t)(H - 1) * W + x);   /* memory row H-1 = bottom of the picture? count both */
                const uint8_t *q = img0 + 4 * (size_t)x;
                if (!(p[0] == 255 && p[1] == 191 && p[2] == 158)) ground++;
                if (!(q[0] == 255 && q[1] == 191 && q[2] == 158)) ground++;
            }
            printf("winding %s: %.0f%% of the first+last rows show terrain\n", g_winding == MTLWindingCounterClockwise ? "CCW" : "CW", 50.0 * ground / W);
            if (ground > W / 2) break;
            g_winding = g_winding == MTLWindingCounterClockwise ? MTLWindingClockwise : MTLWindingCounterClockwise;
        }

        printf("%-36s %6s %6s %8s | %8s %8s %8s %8s %8s %8s %8s | %s\n", "pose", "secs", "recs", "quads", kConfigNames[0], kConfigNames[1],
               kConfigNames[2], kConfigNames[3], kConfigNames[4], kConfigNames[5], kConfigNames[6], "ideal culls");
        for (int pi = 0; pi < nposes; pi++) {
            const Pose *pose = &poses[pi];
            if (onlyPose && !strstr(pose->name, onlyPose)) continue;
            set_uniforms(g, pose);
            M4 vp = view_proj(pose);
            Frustum fr = frustum_of(vp);
            build_draw_list(g, pose, &fr, NULL, &full, recsFull);
            id<MTLBuffer> fullRecs = [g->dev newBufferWithBytes:recsFull length:sizeof(Rec) * (full.nrec ? full.nrec : 1) options:MTLResourceStorageModeShared];

            /* oracle: full terrain with depth stored, then every drawn section's geometry again (GEQUAL, no writes) with a
             * per-section counter. A counter of 0 = no fragment at the final depth anywhere = removable without changing a
             * pixel (opaque layers: the final colour of a sample is the last fragment at the maximum depth). */
            memset(g->visBuf.contents, 0, 8 * (NSUInteger)g_sectionCount);
            {
                id<MTLCommandBuffer> cb = [g->q commandBuffer];
                g->recBuf = fullRecs;
                id<MTLRenderCommandEncoder> re = [cb renderCommandEncoderWithDescriptor:pass_desc(g, YES, YES)];
                encode_terrain(g, re, &full, 0, g->solid, g->cutout);
                [re setDepthStencilState:g->dssTest];
                for (int layer = 0; layer < 2; layer++) {
                    if (!full.recCount[layer]) continue;
                    [re setRenderPipelineState:layer ? g->cutoutNoWrite : g->solidNoWrite];
                    [re setVertexBuffer:g->heap[layer] offset:0 atIndex:30];
                    for (uint32_t r = full.recStart[layer]; r < full.recStart[layer] + full.recCount[layer]; r++) {
                        [re setVisibilityResultMode:MTLVisibilityResultModeCounting offset:8 * full.recSection[r]];
                        [re drawIndexedPrimitives:MTLPrimitiveTypeTriangle indexType:MTLIndexTypeUInt16 indexBuffer:g->index16 indexBufferOffset:0
                                   indirectBuffer:fullRecs indirectBufferOffset:20 * r];
                    }
                }
                [re endEncoding];
                [cb commit];
                [cb waitUntilCompleted];
            }
            const uint64_t *counts = g->visBuf.contents;
            memset(keep, 0, g_sectionCount);
            int nculled = 0;
            for (int k = 0; k < full.ndrawn; k++) {
                int s = full.drawnSections[k];
                const Section *sc = &g_sections[s];
                double dx = fmax(fmax(sc->ox - 2 - pose->x, pose->x - (sc->ox + 18)), 0.0);
                double dy = fmax(fmax(sc->oy - 2 - pose->y, pose->y - (sc->oy + 18)), 0.0);
                double dz = fmax(fmax(sc->oz - 2 - pose->z, pose->z - (sc->oz + 18)), 0.0);
                if (counts[s] || sqrt(dx * dx + dy * dy + dz * dz) < (g_minDist > 0.5 ? g_minDist : 0.5)) keep[s] = 1;
                else culled[nculled++] = s;
            }
            build_draw_list(g, pose, &fr, keep, &ideal, recsIdeal);
            id<MTLBuffer> idealRecs = [g->dev newBufferWithBytes:recsIdeal length:sizeof(Rec) * (ideal.nrec ? ideal.nrec : 1) options:MTLResourceStorageModeShared];

            /* exactness of the oracle: the ideal list renders the same bytes as the full list */
            for (int which = 0; which < 2; which++) {
                id<MTLCommandBuffer> cb = [g->q commandBuffer];
                encode_config(g, cb, which ? C_IDEAL : C_FULL, &full, &ideal, culled, nculled, fullRecs, idealRecs);
                [cb commit];
                [cb waitUntilCompleted];
                readback(g, g->color, which ? img1 : img0, 4);
            }
            long diff = 0, sky = 0;
            for (size_t i = 0; i < (size_t)W * H; i++) {
                if (memcmp(img0 + 4 * i, img1 + 4 * i, 4)) diff++;
                if (img0[4 * i] == 255 && img0[4 * i + 1] == 191 && img0[4 * i + 2] == 158) sky++;
            }
            /* boxes of the culled sections must test occluded against this frame's depth (else the steady state would
             * re-enable them): count how many do */
            int boxesOccluded = 0;
            {
                memset(g->visBuf.contents, 0xFF, 8 * (NSUInteger)g_sectionCount);
                id<MTLCommandBuffer> cb = [g->q commandBuffer];
                g->recBuf = idealRecs;
                id<MTLRenderCommandEncoder> re = [cb renderCommandEncoderWithDescriptor:pass_desc(g, NO, YES)];
                encode_terrain(g, re, &ideal, 0, g->solid, g->cutout);
                encode_boxes(g, re, culled, nculled);
                [re endEncoding];
                [cb commit];
                [cb waitUntilCompleted];
                for (int k = 0; k < nculled; k++) boxesOccluded += counts[culled[k]] == 0;
            }

            /* timing, configurations interleaved, after a warm-up that brings the GPU clock up */
            for (int f = 0; f < 300; f++) {
                id<MTLCommandBuffer> cb = [g->q commandBuffer];
                encode_config(g, cb, C_FULL, &full, &ideal, culled, nculled, fullRecs, idealRecs);
                [cb commit];
                if (f == 299) [cb waitUntilCompleted];
            }
            /* Two metrics per configuration and round (configuration order rotated every round):
             *  thr: pipelined throughput, (last GPUEndTime - first GPUStartTime) / frames with 3 frames in flight. This is
             *       what a frame costs when frames overlap on the GPU, as in the game. Per-command-buffer spans would be
             *       wrong here: consecutive command buffers overlap (vertex of N+1 during fragment of N).
             *  ser: median GPU span of one frame committed and waited for alone (no overlap). */
            double thr[C_COUNT][32], ser[C_COUNT][32];
            double cpu[C_COUNT] = {0};
            int nr = rounds < 32 ? rounds : 32;
            for (int r = 0; r < nr; r++) {
                for (int ci = 0; ci < C_COUNT; ci++) {
                    int c = (ci + r) % C_COUNT;
                    double *st = calloc(frames, sizeof(double)), *en = calloc(frames, sizeof(double));
                    id<MTLCommandBuffer> last = nil;
                    dispatch_semaphore_t inflight = dispatch_semaphore_create(3);
                    for (int f = 0; f < frames + 10; f++) {
                        dispatch_semaphore_wait(inflight, DISPATCH_TIME_FOREVER);
                        id<MTLCommandBuffer> cb = [g->q commandBuffer];
                        double e0 = now_ms();
                        g->visCur = g->visRing[g->visNext++ & 3];
                        encode_config(g, cb, c, &full, &ideal, culled, nculled, fullRecs, idealRecs);
                        g->visCur = nil;
                        if (f >= 10) cpu[c] += now_ms() - e0;
                        int slot = f - 10;
                        [cb addCompletedHandler:^(id<MTLCommandBuffer> b) {
                            if (slot >= 0) { st[slot] = b.GPUStartTime; en[slot] = b.GPUEndTime; }
                            dispatch_semaphore_signal(inflight);
                        }];
                        [cb commit];
                        last = cb;
                    }
                    [last waitUntilCompleted];
                    for (int k = 0; k < 3; k++) dispatch_semaphore_wait(inflight, DISPATCH_TIME_FOREVER);
                    for (int k = 0; k < 3; k++) dispatch_semaphore_signal(inflight);
                    double lo = st[0], hi = en[0];
                    for (int k = 0; k < frames; k++) { if (st[k] < lo) lo = st[k]; if (en[k] > hi) hi = en[k]; }
                    thr[c][r] = (hi - lo) * 1000.0 / frames;
                    int nser = frames / 2 > 8 ? frames / 2 : 8;
                    for (int f = 0; f < nser; f++) {
                        id<MTLCommandBuffer> cb = [g->q commandBuffer];
                        encode_config(g, cb, c, &full, &ideal, culled, nculled, fullRecs, idealRecs);
                        [cb commit];
                        [cb waitUntilCompleted];
                        st[f] = (cb.GPUEndTime - cb.GPUStartTime) * 1000.0;
                    }
                    qsort(st, nser, sizeof(double), cmp_double);
                    ser[c][r] = st[nser / 2];
                    free(st);
                    free(en);
                }
            }
            double best[C_COUNT], bser[C_COUNT];
            for (int c = 0; c < C_COUNT; c++) {
                qsort(thr[c], nr, sizeof(double), cmp_double);
                qsort(ser[c], nr, sizeof(double), cmp_double);
                best[c] = thr[c][nr / 2];
                bser[c] = ser[c][nr / 2];
            }
            uint64_t culledQuads = full.quads - ideal.quads;
            printf("%-36s %6d %6d %8llu | %8.3f %8.3f %8.3f %8.3f %8.3f %8.3f %8.3f | %d secs, %llu quads (%.0f%%), %d recs; "
                   "%d/%d boxes occluded; image diff %ld px; sky %.0f%%\n",
                   pose->name, full.ndrawn, full.nrec, (unsigned long long)full.quads, best[0], best[1], best[2], best[3], best[4], best[5], best[6],
                   nculled, (unsigned long long)culledQuads, 100.0 * culledQuads / (full.quads ? full.quads : 1), full.nrec - ideal.nrec,
                   boxesOccluded, nculled, diff, 100.0 * sky / ((double)W * H));
            printf("%-36s   serialized ms: %8.3f %8.3f %8.3f %8.3f %8.3f %8.3f %8.3f\n", "", bser[0], bser[1], bser[2], bser[3], bser[4], bser[5], bser[6]);
            printf("%-36s   cpu encode ms/frame: full %.3f ideal %.3f full+vis %.3f ideal+box %.3f; ideal saves %.3f ms GPU (pipelined) = %.3f ms / 100k quads\n",
                   "", cpu[C_FULL] / (frames * nr), cpu[C_IDEAL] / (frames * nr), cpu[C_FULL_VIS] / (frames * nr),
                   cpu[C_IDEAL_BOX] / (frames * nr), best[C_FULL] - best[C_IDEAL],
                   culledQuads ? (best[C_FULL] - best[C_IDEAL]) / (culledQuads / 1e5) : 0.0);
            fflush(stdout);
        }
    }
    return 0;
}
