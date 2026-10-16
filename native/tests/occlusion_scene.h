/*
 * occlusion_scene.h -- synthetic Minecraft-like terrain for the occlusion tests (native/tests/occlusion_test.m).
 *
 * World: value-noise hills over a closed box (outside = stone, so the surface never shows its underside), optional
 * cave voids, trees with leaves (cutout, ~35% transparent texels), water in low areas (translucent). Meshing follows
 * vanilla: one quad per face between a block and a neighbour that does not hide it (solid hides everything; leaves
 * keep leaf-to-leaf faces; water shows faces against air only), 28-byte BLOCK vertices, section-relative positions,
 * solid / cutout quads in facing buckets (DOWN, NORTH, EAST, UP, SOUTH, WEST) with their extreme planes, translucent
 * quads unbucketed. A section mesh can be rebuilt after blocks change (scene_remesh).
 */
#ifndef OCCLUSION_SCENE_H
#define OCCLUSION_SCENE_H

#include <math.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#define SC_RD 12
#define SC_HALF (13 * 16)
#define SC_WX (2 * SC_HALF)
#define SC_WY 256
#define SC_NSECY (SC_WY / 16)
#define SC_NSEC_XZ (SC_WX / 16)
#define SC_SEA 54

enum { SB_AIR = 0, SB_GRASS, SB_DIRT, SB_STONE, SB_LOG, SB_LEAVES, SB_WATER };
enum { SF_DOWN = 0, SF_NORTH, SF_EAST, SF_UP, SF_SOUTH, SF_WEST, SF_COUNT };
enum { SL_SOLID = 0, SL_CUTOUT, SL_TRANSLUCENT, SL_COUNT };

typedef struct __attribute__((packed)) {
    float x, y, z;
    uint8_t r, g, b, a;
    float u, v;
    int16_t lu, lv;
} ScVtx;
_Static_assert(sizeof(ScVtx) == 28, "vanilla BLOCK vertex = 28 bytes");

typedef struct {
    ScVtx *v;          /* 4 per quad */
    uint32_t quads, cap;
    uint32_t start[SF_COUNT + 1];   /* bucket quad starts (solid / cutout); translucent: one bucket */
    float plane[SF_COUNT];
} ScMesh;

typedef struct {
    int ox, oy, oz;
    float bmin[3], bmax[3];
    ScMesh layer[SL_COUNT];
    uint32_t heapQuad[SL_COUNT];    /* where the mesh lives in the layer heap (quads) */
    int present;
    int inView;                     /* inside the view-distance circle (meshed) */
} ScSection;

typedef struct {
    uint8_t *blocks;
    int heights[SC_WX][SC_WX];
    ScSection *secs;                /* SC_NSEC_XZ^2 * SC_NSECY, NULL entries outside the view circle */
    int *index;                     /* list of sections with geometry */
    int count;
    float treeDensity;
} ScWorld;

static inline uint8_t sc_blk(const ScWorld *w, int x, int y, int z) {
    if (y < 0) return SB_STONE;
    if (y >= SC_WY) return SB_AIR;
    if (x < -SC_HALF || x >= SC_HALF || z < -SC_HALF || z >= SC_HALF) return SB_STONE;
    return w->blocks[((size_t)(x + SC_HALF) * SC_WY + y) * SC_WX + (z + SC_HALF)];
}
static inline void sc_set(ScWorld *w, int x, int y, int z, uint8_t b) {
    if (y < 0 || y >= SC_WY || x < -SC_HALF || x >= SC_HALF || z < -SC_HALF || z >= SC_HALF) return;
    w->blocks[((size_t)(x + SC_HALF) * SC_WY + y) * SC_WX + (z + SC_HALF)] = b;
}

static inline uint32_t sc_hash3(int x, int y, int z) {
    uint32_t h = (uint32_t)x * 374761393u + (uint32_t)y * 668265263u + (uint32_t)z * 2147483647u;
    h = (h ^ (h >> 13)) * 1274126177u;
    return h ^ (h >> 16);
}
static inline float sc_rnd3(int x, int y, int z) { return (sc_hash3(x, y, z) & 0xFFFFFF) / 16777216.0f; }
static inline float sc_smooth(float t) { return t * t * (3 - 2 * t); }
static float sc_noise2(float x, float z, int seed) {
    int xi = (int)floorf(x), zi = (int)floorf(z);
    float fx = sc_smooth(x - xi), fz = sc_smooth(z - zi);
    float a = sc_rnd3(xi, seed, zi), b = sc_rnd3(xi + 1, seed, zi), c = sc_rnd3(xi, seed, zi + 1), d = sc_rnd3(xi + 1, seed, zi + 1);
    float ab = a + (b - a) * fx, cd = c + (d - c) * fx;
    return ab + (cd - ab) * fz;
}
static float sc_fbm2(float x, float z, int seed) {
    float s = 0, amp = 1, norm = 0;
    for (int o = 0; o < 5; o++) { s += amp * sc_noise2(x, z, seed + o * 31); norm += amp; x *= 2.03f; z *= 2.03f; amp *= 0.5f; }
    return s / norm;
}
static float sc_noise3(float x, float y, float z, int seed) {
    int xi = (int)floorf(x), yi = (int)floorf(y), zi = (int)floorf(z);
    float fx = sc_smooth(x - xi), fy = sc_smooth(y - yi), fz = sc_smooth(z - zi);
    float v[2][2][2];
    for (int i = 0; i < 2; i++) for (int j = 0; j < 2; j++) for (int k = 0; k < 2; k++) v[i][j][k] = sc_rnd3(xi + i + seed * 7919, yi + j, zi + k);
    float x00 = v[0][0][0] + (v[1][0][0] - v[0][0][0]) * fx, x10 = v[0][1][0] + (v[1][1][0] - v[0][1][0]) * fx;
    float x01 = v[0][0][1] + (v[1][0][1] - v[0][0][1]) * fx, x11 = v[0][1][1] + (v[1][1][1] - v[0][1][1]) * fx;
    float y0 = x00 + (x10 - x00) * fy, y1 = x01 + (x11 - x01) * fy;
    return y0 + (y1 - y0) * fz;
}

static void sc_generate(ScWorld *w, int caves, float trees) {
    w->blocks = calloc((size_t)SC_WX * SC_WY * SC_WX, 1);
    w->treeDensity = trees;
    for (int x = -SC_HALF; x < SC_HALF; x++)
        for (int z = -SC_HALF; z < SC_HALF; z++) {
            float h = 62 + 46 * (sc_fbm2(x / 170.0f, z / 170.0f, 1) - 0.5f) * 2 + 14 * (sc_fbm2(x / 45.0f + 100, z / 45.0f, 9) - 0.5f) * 2;
            int hi = (int)h;
            if (hi < 8) hi = 8;
            if (hi > 200) hi = 200;
            w->heights[x + SC_HALF][z + SC_HALF] = hi;
            for (int y = 0; y < hi; y++) sc_set(w, x, y, z, y == hi - 1 ? SB_GRASS : y >= hi - 4 ? SB_DIRT : SB_STONE);
            for (int y = hi; y <= SC_SEA; y++) sc_set(w, x, y, z, SB_WATER);
        }
    if (caves) {
        for (int x = -SC_HALF; x < SC_HALF; x++)
            for (int z = -SC_HALF; z < SC_HALF; z++) {
                int hi = w->heights[x + SC_HALF][z + SC_HALF];
                for (int y = 12; y < hi - 5; y++)
                    if (sc_noise3(x / 22.0f, y / 12.0f, z / 22.0f, 5) > 0.70f) sc_set(w, x, y, z, SB_AIR);
            }
    }
    for (int x = -SC_HALF + 3; x < SC_HALF - 3; x++)
        for (int z = -SC_HALF + 3; z < SC_HALF - 3; z++) {
            if (sc_rnd3(x, 777, z) > trees) continue;
            int hi = w->heights[x + SC_HALF][z + SC_HALF];
            if (hi > 120 || hi <= SC_SEA) continue;
            for (int dy = -2; dy <= 2; dy++)
                for (int dx = -2; dx <= 2; dx++)
                    for (int dz = -2; dz <= 2; dz++) {
                        int r = dy > 0 ? 1 : 2;
                        if (abs(dx) > r || abs(dz) > r) continue;
                        if (abs(dx) == 2 && abs(dz) == 2 && sc_rnd3(x + dx, hi + dy, z + dz) < 0.5f) continue;
                        if (sc_blk(w, x + dx, hi + 4 + dy, z + dz) == SB_AIR) sc_set(w, x + dx, hi + 4 + dy, z + dz, SB_LEAVES);
                    }
            for (int y = hi; y < hi + 5; y++) sc_set(w, x, y, z, SB_LOG);
        }
}

static const int kScDir[SF_COUNT][3] = { {0, -1, 0}, {0, 0, -1}, {1, 0, 0}, {0, 1, 0}, {0, 0, 1}, {-1, 0, 0} };

/* Face corners (block-relative), counter-clockwise seen from outside (vanilla FaceInfo order up to rotation). */
static void sc_face(int f, float c[4][3]) {
    static const float k[SF_COUNT][4][3] = {
        [SF_DOWN]  = { {0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1} },
        [SF_UP]    = { {0, 1, 0}, {0, 1, 1}, {1, 1, 1}, {1, 1, 0} },
        [SF_NORTH] = { {0, 0, 0}, {0, 1, 0}, {1, 1, 0}, {1, 0, 0} },
        [SF_SOUTH] = { {0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1} },
        [SF_WEST]  = { {0, 0, 0}, {0, 0, 1}, {0, 1, 1}, {0, 1, 0} },
        [SF_EAST]  = { {1, 0, 0}, {1, 1, 0}, {1, 1, 1}, {1, 0, 1} },
    };
    memcpy(c, k[f], sizeof(float) * 12);
}

static void sc_tile(uint8_t b, int f, float *u0, float *v0) {
    int tile;
    switch (b) {
        case SB_GRASS: tile = f == SF_UP ? 0 : f == SF_DOWN ? 1 : 2; break;
        case SB_DIRT: tile = 1; break;
        case SB_LOG: tile = f == SF_UP || f == SF_DOWN ? 4 : 5; break;
        case SB_LEAVES: tile = 6; break;
        case SB_WATER: tile = 7; break;
        default: tile = 3; break;
    }
    *u0 = (tile % 16) * 16 / 256.0f;
    *v0 = (tile / 16) * 16 / 256.0f;
}

static inline int sc_layer_of(uint8_t b) { return b == SB_LEAVES ? SL_CUTOUT : b == SB_WATER ? SL_TRANSLUCENT : SL_SOLID; }

/* Is the face of block b toward neighbour nb drawn? */
static inline int sc_face_drawn(uint8_t b, uint8_t nb) {
    if (nb == SB_AIR) return 1;
    if (b == SB_WATER) return 0;                    /* water: faces against air only */
    if (nb == SB_LEAVES || nb == SB_WATER) return 1;
    return 0;                                       /* opaque neighbour */
}

static void sc_mesh_push(ScMesh *m, const ScVtx q[4]) {
    if (m->quads == m->cap) {
        m->cap = m->cap ? m->cap * 2 : 256;
        m->v = realloc(m->v, (size_t)m->cap * 4 * sizeof(ScVtx));
    }
    memcpy(m->v + (size_t)m->quads * 4, q, 4 * sizeof(ScVtx));
    m->quads++;
}

static void sc_mesh_section(const ScWorld *w, ScSection *s) {
    static const float shade[SF_COUNT] = {0.5f, 0.8f, 0.6f, 1.0f, 0.8f, 0.6f};
    static const float uvs[4][2] = { {0, 1}, {0, 0}, {1, 0}, {1, 1} };
    for (int i = 0; i < 3; i++) { s->bmin[i] = 1e9f; s->bmax[i] = -1e9f; }
    for (int l = 0; l < SL_COUNT; l++) {
        ScMesh *m = &s->layer[l];
        m->quads = 0;
        for (int f = 0; f < SF_COUNT; f++) {
            m->start[f] = m->quads;
            m->plane[f] = (f == SF_UP || f == SF_EAST || f == SF_SOUTH) ? 1e9f : -1e9f;
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++) {
                        int wx = s->ox + x, wy = s->oy + y, wz = s->oz + z;
                        uint8_t b = sc_blk(w, wx, wy, wz);
                        if (b == SB_AIR || sc_layer_of(b) != l) continue;
                        uint8_t nb = sc_blk(w, wx + kScDir[f][0], wy + kScDir[f][1], wz + kScDir[f][2]);
                        if (!sc_face_drawn(b, nb)) continue;
                        float c[4][3];
                        sc_face(f, c);
                        float u0, v0;
                        sc_tile(b, f, &u0, &v0);
                        uint8_t base[4] = { 255, 255, 255, 255 };
                        if (b == SB_GRASS && f == SF_UP) { base[0] = 110; base[1] = 190; base[2] = 80; }
                        if (b == SB_LEAVES) { base[0] = 70; base[1] = 150; base[2] = 60; }
                        if (b == SB_WATER) { base[0] = 60; base[1] = 90; base[2] = 220; base[3] = 160; }
                        ScVtx q[4];
                        for (int k = 0; k < 4; k++) {
                            q[k].x = x + c[k][0];
                            q[k].y = y + c[k][1] - (b == SB_WATER && c[k][1] > 0.5f ? 0.125f : 0.0f);
                            q[k].z = z + c[k][2];
                            q[k].r = (uint8_t)(base[0] * shade[f]); q[k].g = (uint8_t)(base[1] * shade[f]);
                            q[k].b = (uint8_t)(base[2] * shade[f]); q[k].a = base[3];
                            q[k].u = u0 + uvs[k][0] * (16 / 256.0f); q[k].v = v0 + uvs[k][1] * (16 / 256.0f);
                            q[k].lu = 240; q[k].lv = 240;
                            float p[3] = { q[k].x, q[k].y, q[k].z };
                            for (int i = 0; i < 3; i++) { if (p[i] < s->bmin[i]) s->bmin[i] = p[i]; if (p[i] > s->bmax[i]) s->bmax[i] = p[i]; }
                        }
                        float pl = (f == SF_DOWN || f == SF_UP) ? q[0].y : (f == SF_NORTH || f == SF_SOUTH) ? q[0].z : q[0].x;
                        if (f == SF_UP || f == SF_EAST || f == SF_SOUTH) { if (pl < m->plane[f]) m->plane[f] = pl; }
                        else if (pl > m->plane[f]) m->plane[f] = pl;
                        sc_mesh_push(m, q);
                    }
        }
        m->start[SF_COUNT] = m->quads;
    }
    s->present = s->layer[0].quads + s->layer[1].quads + s->layer[2].quads > 0;
}

static inline ScSection *sc_section(ScWorld *w, int sx, int sy, int sz) {
    return &w->secs[((size_t)sx * SC_NSEC_XZ + sz) * SC_NSECY + sy];
}

static void sc_mesh_world(ScWorld *w) {
    w->secs = calloc((size_t)SC_NSEC_XZ * SC_NSEC_XZ * SC_NSECY, sizeof(ScSection));
    w->index = calloc((size_t)SC_NSEC_XZ * SC_NSEC_XZ * SC_NSECY, sizeof(int));
    w->count = 0;
    for (int sx = 0; sx < SC_NSEC_XZ; sx++)
        for (int sz = 0; sz < SC_NSEC_XZ; sz++) {
            int cx = sx - SC_NSEC_XZ / 2, cz = sz - SC_NSEC_XZ / 2;
            if (cx * cx + cz * cz > SC_RD * SC_RD) continue;
            for (int sy = 0; sy < SC_NSECY; sy++) {
                ScSection *s = sc_section(w, sx, sy, sz);
                s->ox = cx * 16; s->oy = sy * 16; s->oz = cz * 16;
                s->inView = 1;
                sc_mesh_section(w, s);
                w->index[w->count++] = (int)(((size_t)sx * SC_NSEC_XZ + sz) * SC_NSECY + sy);
            }
        }
}

/* ---------------------------------------------------------------------------------------------- */
/* math (column-major, JOML conventions)                                                            */

typedef struct { float m[16]; } ScM4;
static ScM4 sc_mul(ScM4 a, ScM4 b) {
    ScM4 r;
    for (int c = 0; c < 4; c++) for (int rr = 0; rr < 4; rr++) {
        float s = 0;
        for (int k = 0; k < 4; k++) s += a.m[k * 4 + rr] * b.m[c * 4 + k];
        r.m[c * 4 + rr] = s;
    }
    return r;
}
/* JOML setPerspective(fovy, aspect, zNear = far, zFar = near, zZeroToOne = true): reverse Z, depth 1 at near, 0 at far. */
static ScM4 sc_persp(float fovy, float aspect, float n, float f) {
    float t = 1.0f / tanf(fovy * 0.5f);
    ScM4 r = {{0}};
    r.m[0] = t / aspect; r.m[5] = t;
    float zn = f, zf = n;
    r.m[10] = zf / (zn - zf);
    r.m[11] = -1;
    r.m[14] = zn * zf / (zn - zf);
    return r;
}
static ScM4 sc_rot_x(float a) { ScM4 r = {{1,0,0,0, 0,cosf(a),sinf(a),0, 0,-sinf(a),cosf(a),0, 0,0,0,1}}; return r; }
static ScM4 sc_rot_y(float a) { ScM4 r = {{cosf(a),0,-sinf(a),0, 0,1,0,0, sinf(a),0,cosf(a),0, 0,0,0,1}}; return r; }

/* Minecraft view rotation: pitch about X, then yaw + 180 about Y. */
static ScM4 sc_view(float yawDeg, float pitchDeg) {
    return sc_mul(sc_rot_x(pitchDeg * (float)M_PI / 180.0f), sc_rot_y((yawDeg + 180.0f) * (float)M_PI / 180.0f));
}

typedef struct { float p[6][4]; } ScFrustum;
static ScFrustum sc_frustum(ScM4 vp) {
    ScFrustum fr;
    float row[4][4];
    for (int r = 0; r < 4; r++) for (int c = 0; c < 4; c++) row[r][c] = vp.m[c * 4 + r];
    for (int i = 0; i < 4; i++) {
        fr.p[0][i] = row[3][i] + row[0][i];
        fr.p[1][i] = row[3][i] - row[0][i];
        fr.p[2][i] = row[3][i] + row[1][i];
        fr.p[3][i] = row[3][i] - row[1][i];
        fr.p[4][i] = row[2][i];
        fr.p[5][i] = row[3][i] - row[2][i];
    }
    return fr;
}
static int sc_box_visible(const ScFrustum *fr, float x0, float y0, float z0, float x1, float y1, float z1) {
    for (int i = 0; i < 6; i++) {
        const float *p = fr->p[i];
        if (p[0] * (p[0] >= 0 ? x1 : x0) + p[1] * (p[1] >= 0 ? y1 : y0) + p[2] * (p[2] >= 0 ? z1 : z0) + p[3] < 0) return 0;
    }
    return 1;
}

/* ---------------------------------------------------------------------------------------------- */
/* Shaders: equivalent of vanilla terrain.vsh / terrain.fsh (MULTIDRAW_TERRAIN) as the Metal backend translates them
 * (spvc FLIP_VERTEX_Y: y negated at the end of the vertex stage; pipelines use front facing Clockwise), plus a plain
 * "entity" pipeline that writes depth after the opaque terrain.                                     */

static const char *const kScShaders =
    "#include <metal_stdlib>\n"
    "using namespace metal;\n"
    "struct Proj { float4x4 proj; };\n"
    "struct Globals { packed_int3 cameraBlockPos; float glint; packed_float3 cameraOffset; float gameTime; float2 screen; int blur; int rgss; };\n"
    "struct Terrain { float4x4 mv; int2 texSize; };\n"
    "struct Fog { float4 color; float envStart, envEnd, rdStart, rdEnd, skyEnd, cloudsEnd; };\n"
    "struct VIn { float3 pos [[attribute(0)]]; float4 color [[attribute(1)]]; float2 uv0 [[attribute(2)]];\n"
    "             short2 uv2 [[attribute(3)]]; int3 chunk [[attribute(4)]]; float vis [[attribute(5)]]; };\n"
    "struct VOut { float4 position [[position]]; float sph; float cyl; float4 color; float2 uv; float vis; };\n"
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
    "    o.position.y = -o.position.y;\n"
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
    "fragment float4 translucent_fs(VOut in [[stage_in]], constant Fog &F [[buffer(2)]], constant Terrain &T [[buffer(5)]],\n"
    "                               texture2d<float> atlas [[texture(0)]], sampler as [[sampler(0)]]) {\n"
    "    bool keep; float4 c = terrain_color(in, F, T, atlas, as, keep, 0.1);\n"
    "    if (!keep) discard_fragment();\n"
    "    return c;\n"
    "}\n"
    /* entity: camera-relative unit cubes from a float4 per instance (xyz, colour index), drawn with the world matrices */
    "struct EOut { float4 position [[position]]; float4 color; };\n"
    "vertex EOut entity_vs(uint vid [[vertex_id]], uint iid [[instance_id]], constant Proj &P [[buffer(0)]],\n"
    "                      constant Terrain &T [[buffer(5)]], const device float4 *cubes [[buffer(6)]]) {\n"
    "    const ushort idx[36] = {0,4,6, 0,6,2, 1,3,7, 1,7,5, 0,1,5, 0,5,4, 2,6,7, 2,7,3, 0,2,3, 0,3,1, 4,5,7, 4,7,6};\n"
    "    ushort c = idx[vid];\n"
    "    float3 p = cubes[iid].xyz + float3(c & 1, (c >> 1) & 1, (c >> 2) & 1) * 0.9;\n"
    "    EOut o; o.position = P.proj * (T.mv * float4(p, 1.0)); o.position.y = -o.position.y;\n"
    "    o.color = float4(0.9, 0.2 + 0.1 * float(vid / 6), 0.3, 1.0); return o;\n"
    "}\n"
    "fragment float4 entity_fs(EOut in [[stage_in]]) { return in.color; }\n";

#endif
