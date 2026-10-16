/*
 * terrain_icb_probe.m -- headless probe + benchmark for collapsing terrain indirect draws (spec U4, task B).
 *
 * A terrain-shaped workload at the game's 3600x2260: ~400 sections, ~860 stride-20 indexed indirect records
 * (MTLDrawIndexedPrimitivesIndirectArguments layout, CPU-written Shared ring), 28-byte BLOCK vertices in 3 uber
 * buffers, per-instance CHUNK_DATA (int3 + float, 16 bytes) at vertex slot 1 with baseInstance = section index, a
 * fixed 16-bit quad index buffer, SOLID + CUTOUT layers (6 draw groups), a terrain-like vertex shader (transform,
 * fog distances, lightmap sample in the vertex stage) and fragment shader (atlas sample, cutout discard, fog).
 *
 * Paths (all drawn into the same RGBA8 + Depth32Float target, reverse-Z GEQUAL like vanilla):
 *   indirect   one drawIndexedPrimitives:indirectBuffer: per record (the M1/M2 decoder)
 *   direct     the records read on the CPU, one direct drawIndexedPrimitives per record (zero counts skipped)
 *   icb-cpu    ICB (inheritPipelineState + inheritBuffers) encoded on the CPU every frame, one
 *              executeCommandsInBuffer:withRange: per group
 *   icb-cache  the same ICB encoded once, only executed per frame (lower bound: what a record-bytes cache gives)
 *   icb-gpu    ICB encoded by a compute kernel from the records (compute encoder before the render pass)
 *   direct, textures in arg buffer   the direct path with the shaders ICB pipelines need (textures and samplers read
 *              from an argument buffer: Metal refuses ICB-capable PSOs whose shaders bind textures directly)
 *   none / one draw   baselines (an empty pass is fast-cleared; one draw pays the full clear + store)
 * U4 checks (docs/renderpearl/00-metal-backend-spec.md U4, CONTRACT.md 16.5): every path's bytes equal the indirect
 * path's (baseVertex, baseInstance, index offsets, inherited vertex and fragment textures/samplers through the argument
 * buffer, inherited depth/cull state); plus edge cases under MTL_DEBUG_LAYER=1: reset commands inside an executed range,
 * odd 16-bit index offsets (2 + 12k bytes), instanceCount 2 with baseInstance, negative baseVertex; and which shaders an
 * ICB-capable PSO accepts.
 *
 * GPU time per frame: each frame is its own command buffer, committed and waited for (serialized), GPU time =
 * GPUEndTime - GPUStartTime; the paths are interleaved frame by frame. CPU time: wall time of encoding the terrain draws
 * (the decoder's share). PROBE_PIPELINED=1: CPU only, 3 frames in flight, no waits. PROBE_SPLIT=k: k executes per group
 * in icb-cache (isolates the cost of one executeCommandsInBuffer).
 *
 * Build + run: tools/terrain-icb-probe.sh [frames=200] [rounds=3] [scene=terrain|overhead|overhead-tile]
 */
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define W 3600u
#define H 2260u

static const char *kShaders =
"#include <metal_stdlib>\n"
"using namespace metal;\n"
"struct VIn { float3 pos [[attribute(0)]]; float4 color [[attribute(1)]]; float2 uv0 [[attribute(2)]]; int2 uv2 [[attribute(3)]];\n"
"             int3 chunkPos [[attribute(4)]]; float chunkVis [[attribute(5)]]; };\n"
"struct Globals { float4x4 proj; float4x4 mv; int4 cameraBlockPos; float4 cameraOffset; float4 fogColor; float4 fog; };\n"
"struct VOut { float4 position [[position]]; float sph; float cyl; float4 color; float2 uv; float vis; };\n"
"vertex VOut terrain_vs(VIn in [[stage_in]], constant Globals &g [[buffer(2)]], texture2d<float> lightmap [[texture(2)]],\n"
"                       sampler ls [[sampler(2)]]) {\n"
"    VOut o;\n"
"    float3 pos = in.pos + float3(in.chunkPos - g.cameraBlockPos.xyz) + g.cameraOffset.xyz;\n"
"    o.position = g.proj * (g.mv * float4(pos, 1.0));\n"
"    o.sph = length(pos);\n"
"    o.cyl = max(length(pos.xz), abs(pos.y));\n"
"    o.color = in.color * lightmap.sample(ls, clamp(float2(in.uv2) / 256.0, float2(0.5 / 16.0), float2(15.5 / 16.0)));\n"
"    o.uv = in.uv0;\n"
"    float dist = length(pos);\n"
"    o.vis = mix(1.0, in.chunkVis, clamp((dist - 16.0) / 16.0, 0.0, 1.0));\n"
"    return o;\n"
"}\n"
"fragment float4 terrain_fs(VOut in [[stage_in]], texture2d<float> atlas [[texture(0)]], sampler s [[sampler(0)]],\n"
"                           constant Globals &g [[buffer(2)]], constant float &cutout [[buffer(3)]]) {\n"
"    float4 color = atlas.sample(s, in.uv) * in.color;\n"
"    color = mix(g.fogColor * float4(1, 1, 1, color.a), color, in.vis);\n"
"    if (color.a < cutout) discard_fragment();\n"
"    float f = smoothstep(g.fog.x, g.fog.y, in.sph);\n"
"    return float4(mix(color.rgb, g.fogColor.rgb, f * g.fogColor.a), color.a);\n"
"}\n"
"struct Tex { texture2d<float> atlas; sampler s; texture2d<float> lightmap; sampler ls; };\n"
"vertex VOut terrain_vs_ab(VIn in [[stage_in]], constant Globals &g [[buffer(2)]], constant Tex &t [[buffer(4)]]) {\n"
"    VOut o;\n"
"    float3 pos = in.pos + float3(in.chunkPos - g.cameraBlockPos.xyz) + g.cameraOffset.xyz;\n"
"    o.position = g.proj * (g.mv * float4(pos, 1.0));\n"
"    o.sph = length(pos);\n"
"    o.cyl = max(length(pos.xz), abs(pos.y));\n"
"    o.color = in.color * t.lightmap.sample(t.ls, clamp(float2(in.uv2) / 256.0, float2(0.5 / 16.0), float2(15.5 / 16.0)));\n"
"    o.uv = in.uv0;\n"
"    float dist = length(pos);\n"
"    o.vis = mix(1.0, in.chunkVis, clamp((dist - 16.0) / 16.0, 0.0, 1.0));\n"
"    return o;\n"
"}\n"
"fragment float4 terrain_fs_ab(VOut in [[stage_in]], constant Tex &t [[buffer(4)]],\n"
"                           constant Globals &g [[buffer(2)]], constant float &cutout [[buffer(3)]]) {\n"
"    float4 color = t.atlas.sample(t.s, in.uv) * in.color;\n"
"    color = mix(g.fogColor * float4(1, 1, 1, color.a), color, in.vis);\n"
"    if (color.a < cutout) discard_fragment();\n"
"    float f = smoothstep(g.fog.x, g.fog.y, in.sph);\n"
"    return float4(mix(color.rgb, g.fogColor.rgb, f * g.fogColor.a), color.a);\n"
"}\n"
"struct ICBContainer { command_buffer cmds; };\n"
"struct EncodeParams { uint count; uint icbBase; uint recordBase; uint pad; };\n"
"kernel void encode_icb(device const uint *records [[buffer(0)]], constant EncodeParams &p [[buffer(1)]],\n"
"                       device ICBContainer *icb [[buffer(2)]], device const ushort *indices [[buffer(3)]],\n"
"                       uint i [[thread_position_in_grid]]) {\n"
"    if (i >= p.count) return;\n"
"    device const uint *r = records + (p.recordBase + i) * 5u;\n"
"    render_command cmd(icb->cmds, p.icbBase + i);\n"
"    if (r[0] == 0u || r[1] == 0u) { cmd.reset(); return; }\n"
"    cmd.draw_indexed_primitives(primitive_type::triangle, r[0], indices + r[2], r[1], (int)r[3], r[4]);\n"
"}\n";

typedef struct __attribute__((packed)) Vertex {
    float pos[3];
    uint8_t color[4];
    float uv0[2];
    int16_t uv2[2];
} Vertex;
_Static_assert(sizeof(Vertex) == 28, "BLOCK vertex is 28 bytes");

typedef struct Instance { int32_t chunkPos[3]; float vis; } Instance;
typedef struct Record { uint32_t indexCount, instanceCount, firstIndex; int32_t baseVertex; uint32_t baseInstance; } Record;
typedef struct Globals { float proj[16]; float mv[16]; int32_t cameraBlockPos[4]; float cameraOffset[4]; float fogColor[4]; float fog[4]; } Globals;

typedef struct Group { uint32_t layer, vb, first, count; } Group;

enum { P_INDIRECT, P_DIRECT, P_DIRECT_AB, P_DIRECT_ICBPSO, P_ICB_CPU, P_ICB_CACHE, P_ICB_GPU, P_NONE, P_ONE, P_COUNT };
static const char *kPathNames[P_COUNT] = { "indirect (per-record, current)", "direct (CPU-decoded records)",
                                           "direct, textures in arg buffer", "direct, ICB-capable PSO (same)", "icb-cpu (encode every frame)",
                                           "icb-cache (encoded once)", "icb-gpu (compute-encoded)",
                                           "none (pass without terrain draws)", "one draw (first record only)" };

static uint32_t rng_state = 12345u;
static uint32_t rnd(void) { rng_state = rng_state * 1664525u + 1013904223u; return rng_state >> 8; }

static uint64_t now_ns(void) { return clock_gettime_nsec_np(CLOCK_UPTIME_RAW); }

/* U4 edge cases (16x16 target): direct draws vs the same records in an ICB, under the debug layer when enabled. */
static const char *kEdgeShaders =
"#include <metal_stdlib>\nusing namespace metal;\n"
"struct EIn { float2 pos [[attribute(0)]]; float4 color [[attribute(1)]]; };\n"
"struct EOut { float4 position [[position]]; float4 color; };\n"
"vertex EOut edge_vs(EIn in [[stage_in]]) { EOut o; o.position = float4(in.pos, 0, 1); o.color = in.color; return o; }\n"
"fragment float4 edge_fs(EOut in [[stage_in]]) { return in.color; }\n"
"fragment float4 edge_tex_fs(EOut in [[stage_in]], texture2d<float> t [[texture(0)]], sampler s [[sampler(0)]]) {\n"
"    return t.sample(s, in.color.xy); }\n"
"vertex EOut edge_tex_vs(EIn in [[stage_in]], texture2d<float> t [[texture(2)]], sampler s [[sampler(2)]]) {\n"
"    EOut o; o.position = float4(in.pos, 0, 1) + t.sample(s, in.pos, level(0.0)); o.color = in.color; return o; }\n";

static int u4_edge_checks(id<MTLDevice> dev, id<MTLCommandQueue> q) {
    NSError *err = nil;
    id<MTLLibrary> lib = [dev newLibraryWithSource:@(kEdgeShaders) options:nil error:&err];
    if (!lib) { printf("edge shaders: %s\n", err.localizedDescription.UTF8String); return 1; }
    MTLVertexDescriptor *vd = [MTLVertexDescriptor vertexDescriptor];
    vd.attributes[0].format = MTLVertexFormatFloat2; vd.attributes[0].bufferIndex = 0;
    vd.attributes[1].format = MTLVertexFormatFloat4; vd.attributes[1].bufferIndex = 1;
    vd.layouts[0].stride = 8; vd.layouts[1].stride = 16; vd.layouts[1].stepFunction = MTLVertexStepFunctionPerInstance;
    /* Which shaders an ICB-capable PSO accepts. */
    const char *pairs[3][2] = { { "edge_vs", "edge_fs" }, { "edge_vs", "edge_tex_fs" }, { "edge_tex_vs", "edge_fs" } };
    for (int i = 0; i < 3; i++) {
        MTLRenderPipelineDescriptor *pd = [MTLRenderPipelineDescriptor new];
        pd.vertexFunction = [lib newFunctionWithName:@(pairs[i][0])];
        pd.fragmentFunction = [lib newFunctionWithName:@(pairs[i][1])];
        pd.vertexDescriptor = vd;
        pd.colorAttachments[0].pixelFormat = MTLPixelFormatRGBA8Unorm;
        pd.supportIndirectCommandBuffers = YES;
        err = nil;
        id<MTLRenderPipelineState> pso = [dev newRenderPipelineStateWithDescriptor:pd error:&err];
        printf("U4 ICB-capable PSO with %-11s + %-11s: %s\n", pairs[i][0], pairs[i][1], pso ? "accepted" : err.localizedDescription.UTF8String);
    }
    MTLRenderPipelineDescriptor *pd = [MTLRenderPipelineDescriptor new];
    pd.vertexFunction = [lib newFunctionWithName:@"edge_vs"];
    pd.fragmentFunction = [lib newFunctionWithName:@"edge_fs"];
    pd.vertexDescriptor = vd;
    pd.colorAttachments[0].pixelFormat = MTLPixelFormatRGBA8Unorm;
    pd.supportIndirectCommandBuffers = YES;
    id<MTLRenderPipelineState> pso = [dev newRenderPipelineStateWithDescriptor:pd error:&err];
    if (!pso) { printf("edge PSO: %s\n", err.localizedDescription.UTF8String); return 1; }
    /* 5 quads in a 2x2 grid (quad 4 = quad 0's place), vertices 4..23 (base vertex -4 with indices +4) */
    float v[24][2];
    for (int k = 0; k < 5; k++) {
        const float x0 = (k % 2) ? 0 : -1, y0 = ((k / 2) % 2) ? 0 : -1;
        const float px[4] = { x0, x0 + 1, x0 + 1, x0 }, py[4] = { y0, y0, y0 + 1, y0 + 1 };
        for (int i = 0; i < 4; i++) { v[4 + k * 4 + i][0] = px[i]; v[4 + k * 4 + i][1] = py[i]; }
    }
    id<MTLBuffer> vb = [dev newBufferWithBytes:v length:sizeof v options:0];
    const float colors[8][4] = { {1,0,0,1}, {0,1,0,1}, {0,0,1,1}, {1,1,0,1}, {0,1,1,1}, {1,0,1,1}, {1,1,1,1}, {.5,.5,.5,1} };
    id<MTLBuffer> ib1 = [dev newBufferWithBytes:colors length:sizeof colors options:0];
    /* indices: one pad index first, so quad k starts at index 1 + 6k (byte offset 2 + 12k: not 4-byte aligned) */
    uint16_t idx[1 + 30];
    idx[0] = 0;
    for (int k = 0; k < 5; k++) {
        const uint16_t b = (uint16_t)(8 + 4 * k);
        const uint16_t t[6] = { b, (uint16_t)(b + 1), (uint16_t)(b + 2), (uint16_t)(b + 2), (uint16_t)(b + 3), b };
        memcpy(&idx[1 + 6 * k], t, sizeof t);
    }
    id<MTLBuffer> ib = [dev newBufferWithBytes:idx length:sizeof idx options:0];
    /* {indexCount, instanceCount, firstIndex, baseVertex, baseInstance}: plain, zero count (reset), 2 instances, base 5 */
    const int32_t recs[4][5] = { { 6, 1, 1, -4, 0 }, { 0, 1, 7, -4, 1 }, { 6, 2, 13, -4, 2 }, { 6, 1, 19, -4, 5 } };
    MTLIndirectCommandBufferDescriptor *d = [MTLIndirectCommandBufferDescriptor new];
    d.commandTypes = MTLIndirectCommandTypeDrawIndexed;
    d.inheritBuffers = YES;
    d.inheritPipelineState = YES;
    id<MTLIndirectCommandBuffer> icb = [dev newIndirectCommandBufferWithDescriptor:d maxCommandCount:4 options:MTLResourceStorageModeShared];
    for (int i = 0; i < 4; i++) {
        id<MTLIndirectRenderCommand> c = [icb indirectRenderCommandAtIndex:i];
        if (recs[i][0] == 0) { [c reset]; continue; }
        [c drawIndexedPrimitives:MTLPrimitiveTypeTriangle indexCount:(NSUInteger)recs[i][0] indexType:MTLIndexTypeUInt16 indexBuffer:ib
               indexBufferOffset:(NSUInteger)recs[i][2] * 2 instanceCount:(NSUInteger)recs[i][1] baseVertex:recs[i][3]
                    baseInstance:(NSUInteger)recs[i][4]];
    }
    MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:16 height:16 mipmapped:NO];
    td.usage = MTLTextureUsageRenderTarget;
    td.storageMode = MTLStorageModeShared;
    uint32_t out[2][256];
    for (int mode = 0; mode < 2; mode++) {
        id<MTLTexture> t = [dev newTextureWithDescriptor:td];
        MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
        rp.colorAttachments[0].texture = t;
        rp.colorAttachments[0].loadAction = MTLLoadActionClear;
        rp.colorAttachments[0].storeAction = MTLStoreActionStore;
        id<MTLCommandBuffer> cb = [q commandBuffer];
        id<MTLRenderCommandEncoder> re = [cb renderCommandEncoderWithDescriptor:rp];
        [re setRenderPipelineState:pso];
        [re setVertexBuffer:vb offset:0 atIndex:0];
        [re setVertexBuffer:ib1 offset:0 atIndex:1];
        if (mode == 0) {
            for (int i = 0; i < 4; i++) {
                if (!recs[i][0]) continue;
                [re drawIndexedPrimitives:MTLPrimitiveTypeTriangle indexCount:(NSUInteger)recs[i][0] indexType:MTLIndexTypeUInt16 indexBuffer:ib
                        indexBufferOffset:(NSUInteger)recs[i][2] * 2 instanceCount:(NSUInteger)recs[i][1] baseVertex:recs[i][3]
                             baseInstance:(NSUInteger)recs[i][4]];
            }
        } else {
            [re useResource:ib usage:MTLResourceUsageRead stages:MTLRenderStageVertex];
            [re executeCommandsInBuffer:icb withRange:NSMakeRange(0, 4)];
        }
        [re endEncoding];
        [cb commit];
        [cb waitUntilCompleted];
        if (cb.status != MTLCommandBufferStatusCompleted) { printf("edge: command buffer failed\n"); return 1; }
        [t getBytes:out[mode] bytesPerRow:64 fromRegion:MTLRegionMake2D(0, 0, 16, 16) mipmapLevel:0];
    }
    int diff = 0, drawn = 0;
    for (int i = 0; i < 256; i++) {
        diff += out[0][i] != out[1][i];
        drawn += out[1][i] != 0xFF000000u;
    }
    printf("U4 edge cases (reset command in range, odd 16-bit index offsets, instanceCount 2 + baseInstance, negative baseVertex): "
           "%s (%d differing of 256 px, %d drawn)\n", diff == 0 && drawn > 0 ? "identical to direct draws" : "DIFFERENT", diff, drawn);
    return diff != 0 || drawn == 0;
}

static int cmp_double(const void *a, const void *b) {
    double x = *(const double *)a, y = *(const double *)b;
    return x < y ? -1 : x > y;
}

int main(int argc, const char **argv) {
    @autoreleasepool {
        uint32_t frames = argc > 1 ? (uint32_t)atoi(argv[1]) : 200;
        uint32_t rounds = argc > 2 ? (uint32_t)atoi(argv[2]) : 3;
        const BOOL overhead = argc > 3 && strncmp(argv[3], "overhead", 8) == 0;
        const BOOL oneTile = argc > 3 && strcmp(argv[3], "overhead-tile") == 0;   /* every quad in one tile: no store cost */
        const uint32_t split = getenv("PROBE_SPLIT") ? (uint32_t)MAX(1, atoi(getenv("PROBE_SPLIT"))) : 1u;
        const BOOL pipelined = getenv("PROBE_PIPELINED") != NULL;   /* CPU only: 3 frames in flight, no waits (full CPU clocks) */
        dispatch_semaphore_t inflight = dispatch_semaphore_create(3);
        id<MTLDevice> dev = MTLCreateSystemDefaultDevice();
        id<MTLCommandQueue> queue = [dev newCommandQueue];
        printf("device %s, scene %s\n", dev.name.UTF8String, overhead ? "overhead (1 tiny quad per record)" : "terrain");
        NSError *err = nil;
        MTLCompileOptions *opts = [MTLCompileOptions new];
        opts.languageVersion = MTLLanguageVersion3_2;
        id<MTLLibrary> lib = [dev newLibraryWithSource:@(kShaders) options:opts error:&err];
        if (!lib) { printf("shader error: %s\n", err.localizedDescription.UTF8String); return 1; }
        if (u4_edge_checks(dev, queue)) return 1;

        /* --- world: sections on a 20x20 screen grid, quads in two depth layers per section ------------------ */
        const uint32_t sections = 400, vbCount = 3;
        const uint32_t quadsPerSection = overhead ? 1 : 692;
        Instance *inst = calloc(sections, sizeof *inst);
        NSMutableData *vdata[3] = { [NSMutableData data], [NSMutableData data], [NSMutableData data] };
        uint32_t secVb[400], secBase[400];
        for (uint32_t s = 0; s < sections; s++) {
            const uint32_t gx = s % 20, gy = s / 20;
            inst[s].chunkPos[0] = oneTile ? 0 : (int32_t)(gx * 16);
            inst[s].chunkPos[1] = oneTile ? 0 : (int32_t)(gy * 16);
            inst[s].chunkPos[2] = (int32_t)((s * 7) % 13) * 16;
            inst[s].vis = 1.0f;
            const uint32_t vb = s % vbCount;
            secVb[s] = vb;
            secBase[s] = (uint32_t)(vdata[vb].length / sizeof(Vertex));
            /* 692 quads: 2 layers of 18x19 ~= 342 + a few: layer 0 near (z=12), layer 1 behind (z=4) */
            for (uint32_t q = 0; q < quadsPerSection; q++) {
                const uint32_t layer = q < quadsPerSection / 2 ? 0 : 1;
                const uint32_t k = layer ? q - quadsPerSection / 2 : q;
                const float cell = 16.0f / 19.0f;
                float x0 = (float)(k % 19) * cell, y0 = (float)((k / 19) % 19) * cell;
                float x1 = x0 + cell, y1 = y0 + cell;
                if (overhead) { x1 = x0 + 0.05f; y1 = y0 + 0.05f; }
                const float z = layer ? 4.0f : 12.0f;
                Vertex v[4];
                const float px[4] = { x0, x1, x1, x0 }, py[4] = { y0, y0, y1, y1 };
                for (int i = 0; i < 4; i++) {
                    v[i].pos[0] = px[i]; v[i].pos[1] = py[i]; v[i].pos[2] = z;
                    v[i].color[0] = (uint8_t)(128 + (rnd() & 127)); v[i].color[1] = (uint8_t)(128 + (rnd() & 127));
                    v[i].color[2] = (uint8_t)(128 + (rnd() & 127)); v[i].color[3] = 255;
                    v[i].uv0[0] = (float)((k * 3 + i) % 64) / 64.0f; v[i].uv0[1] = (float)((k * 5 + i * 3) % 64) / 64.0f;
                    v[i].uv2[0] = (int16_t)(rnd() % 240); v[i].uv2[1] = (int16_t)(rnd() % 240);
                }
                [vdata[vb] appendBytes:v length:sizeof v];
            }
        }
        /* records: per section, SOLID 1-2 runs (first ~70% of quads), CUTOUT 0-1 run (the rest), shuffled sizes */
        Record *recs = calloc(4096, sizeof *recs);
        uint32_t recLayer[4096], recVb[4096];
        uint32_t n = 0;
        for (uint32_t s = 0; s < sections; s++) {
            const uint32_t solidQuads = overhead ? 1 : quadsPerSection * 7 / 10;
            const uint32_t runs = overhead ? 1 : 1 + (rnd() % 100 < 60 ? 1 : 0);   /* avg 1.6 */
            uint32_t q0 = 0;
            for (uint32_t r = 0; r < runs; r++) {
                uint32_t q1 = r + 1 == runs ? solidQuads : q0 + solidQuads / runs;
                recs[n] = (Record){ (q1 - q0) * 6, 1, q0 * 6, (int32_t)secBase[s], s };
                recLayer[n] = 0; recVb[n] = secVb[s]; n++;
                q0 = q1;
            }
            if (!overhead || s % 2) {
                if (overhead) { recs[n] = (Record){ 6, 1, 0, (int32_t)secBase[s], s }; }
                else if (rnd() % 100 < 55) { recs[n] = (Record){ (quadsPerSection - solidQuads) * 6, 1, solidQuads * 6, (int32_t)secBase[s], s }; }
                else continue;
                recLayer[n] = 1; recVb[n] = secVb[s]; n++;
            }
        }
        /* group by (layer, vb) like ChunkSectionsToRender: records of one group contiguous in the ring */
        Record *ordered = calloc(n, sizeof *ordered);
        static Group groups[6];
        uint32_t groupCount = 0, o = 0;
        uint64_t totalIdx = 0;
        for (uint32_t layer = 0; layer < 2; layer++) {
            for (uint32_t vb = 0; vb < vbCount; vb++) {
                Group g = { layer, vb, o, 0 };
                for (uint32_t i = 0; i < n; i++) {
                    if (recLayer[i] == layer && recVb[i] == vb) { ordered[o++] = recs[i]; g.count++; totalIdx += recs[i].indexCount; }
                }
                if (g.count) groups[groupCount++] = g;
            }
        }
        printf("sections %u, records %u in %u groups, quads %llu (%.0f per record)\n", sections, n, groupCount,
               (unsigned long long)(totalIdx / 6), (double)totalIdx / 6.0 / n);

        NSMutableArray<id<MTLBuffer>> *vbs = [NSMutableArray array];
        for (uint32_t i = 0; i < vbCount; i++) [vbs addObject:[dev newBufferWithBytes:vdata[i].bytes length:vdata[i].length options:MTLResourceStorageModeShared]];
        id<MTLBuffer> instBuf = [dev newBufferWithBytes:inst length:sections * sizeof *inst options:MTLResourceStorageModeShared];
        /* the ring: records at a non-zero offset like the game's MAP_WRITE ring slice */
        const NSUInteger ringOffset = 4096;
        id<MTLBuffer> ring = [dev newBufferWithLength:ringOffset + n * sizeof(Record) options:MTLResourceStorageModeShared];
        memcpy((uint8_t *)ring.contents + ringOffset, ordered, n * sizeof(Record));
        /* fixed 16-bit quad index buffer, 16384 quads */
        const uint32_t quadCap = 16384;
        id<MTLBuffer> qib = [dev newBufferWithLength:quadCap * 6 * 2 options:MTLResourceStorageModeShared];
        uint16_t *ix = qib.contents;
        for (uint32_t q = 0; q < quadCap; q++) {
            uint16_t b = (uint16_t)(q * 4);
            ix[q * 6 + 0] = b; ix[q * 6 + 1] = b + 1; ix[q * 6 + 2] = b + 2; ix[q * 6 + 3] = b + 2; ix[q * 6 + 4] = b + 3; ix[q * 6 + 5] = b;
        }
        Globals glob;
        memset(&glob, 0, sizeof glob);
        /* ortho: x in [0,320) -> [-1,1], y in [0,320) -> [-1,1], world z in [0, 208+16) -> reverse-Z depth (near = 1) */
        float *P = glob.proj;
        P[0] = 2.0f / 320.0f; P[5] = 2.0f / 320.0f; P[10] = 1.0f / 256.0f; P[12] = -1.0f; P[13] = -1.0f; P[14] = 0.0f; P[15] = 1.0f;
        float *M = glob.mv;
        M[0] = M[5] = M[10] = M[15] = 1.0f;
        glob.fogColor[0] = 0.6f; glob.fogColor[1] = 0.7f; glob.fogColor[2] = 0.9f; glob.fogColor[3] = 1.0f;
        glob.fog[0] = 150.0f; glob.fog[1] = 400.0f;
        id<MTLBuffer> globBuf = [dev newBufferWithBytes:&glob length:sizeof glob options:MTLResourceStorageModeShared];

        /* textures: atlas 1024^2 RGBA8 with alpha holes, lightmap 16x16 */
        MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:1024 height:1024 mipmapped:NO];
        td.usage = MTLTextureUsageShaderRead;
        id<MTLTexture> atlas = [dev newTextureWithDescriptor:td];
        uint32_t *px = malloc(1024 * 1024 * 4);
        for (uint32_t i = 0; i < 1024 * 1024; i++) px[i] = (rnd() & 0x00FFFFFFu) | ((i % 7) == 0 ? 0u : 0xFF000000u);
        [atlas replaceRegion:MTLRegionMake2D(0, 0, 1024, 1024) mipmapLevel:0 withBytes:px bytesPerRow:4096];
        td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:16 height:16 mipmapped:NO];
        td.usage = MTLTextureUsageShaderRead;
        id<MTLTexture> lightmap = [dev newTextureWithDescriptor:td];
        for (uint32_t i = 0; i < 256; i++) px[i] = 0xFF000000u | ((i * 16) & 0xFF) | (((i * 7) & 0xFF) << 8) | (((255 - i) & 0xFF) << 16);
        [lightmap replaceRegion:MTLRegionMake2D(0, 0, 16, 16) mipmapLevel:0 withBytes:px bytesPerRow:64];
        free(px);
        MTLSamplerDescriptor *sd = [MTLSamplerDescriptor new];
        sd.supportArgumentBuffers = YES;
        id<MTLSamplerState> nearest = [dev newSamplerStateWithDescriptor:sd];
        sd.minFilter = sd.magFilter = MTLSamplerMinMagFilterLinear;
        id<MTLSamplerState> linear = [dev newSamplerStateWithDescriptor:sd];

        td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:W height:H mipmapped:NO];
        td.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
        td.storageMode = MTLStorageModePrivate;
        id<MTLTexture> color = [dev newTextureWithDescriptor:td];
        td.pixelFormat = MTLPixelFormatDepth32Float;
        id<MTLTexture> depth = [dev newTextureWithDescriptor:td];

        /* pipelines: plain and ICB-capable */
        MTLVertexDescriptor *vd = [MTLVertexDescriptor vertexDescriptor];
        vd.attributes[0].format = MTLVertexFormatFloat3; vd.attributes[0].offset = 0; vd.attributes[0].bufferIndex = 0;
        vd.attributes[1].format = MTLVertexFormatUChar4Normalized; vd.attributes[1].offset = 12; vd.attributes[1].bufferIndex = 0;
        vd.attributes[2].format = MTLVertexFormatFloat2; vd.attributes[2].offset = 16; vd.attributes[2].bufferIndex = 0;
        vd.attributes[3].format = MTLVertexFormatShort2; vd.attributes[3].offset = 24; vd.attributes[3].bufferIndex = 0;
        vd.attributes[4].format = MTLVertexFormatInt3; vd.attributes[4].offset = 0; vd.attributes[4].bufferIndex = 1;
        vd.attributes[5].format = MTLVertexFormatFloat; vd.attributes[5].offset = 12; vd.attributes[5].bufferIndex = 1;
        vd.layouts[0].stride = 28;
        vd.layouts[1].stride = 16; vd.layouts[1].stepFunction = MTLVertexStepFunctionPerInstance; vd.layouts[1].stepRate = 1;
        MTLRenderPipelineDescriptor *pd = [MTLRenderPipelineDescriptor new];
        pd.vertexFunction = [lib newFunctionWithName:@"terrain_vs"];
        pd.fragmentFunction = [lib newFunctionWithName:@"terrain_fs"];
        pd.vertexDescriptor = vd;
        pd.colorAttachments[0].pixelFormat = MTLPixelFormatRGBA8Unorm;
        pd.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
        uint64_t t0 = now_ns();
        id<MTLRenderPipelineState> pso = [dev newRenderPipelineStateWithDescriptor:pd error:&err];
        uint64_t t1 = now_ns();
        pd.vertexFunction = [lib newFunctionWithName:@"terrain_vs_ab"];
        pd.fragmentFunction = [lib newFunctionWithName:@"terrain_fs_ab"];
        id<MTLRenderPipelineState> psoAb = [dev newRenderPipelineStateWithDescriptor:pd error:&err];
        uint64_t t2 = now_ns();
        pd.supportIndirectCommandBuffers = YES;
        id<MTLRenderPipelineState> psoIcb = [dev newRenderPipelineStateWithDescriptor:pd error:&err];
        uint64_t t3 = now_ns();
        if (!pso || !psoIcb || !psoAb) { printf("PSO error: %s\n", err.localizedDescription.UTF8String); return 1; }
        printf("PSO compile: plain %.2f ms, arg-buffer textures %.2f ms, + supportIndirectCommandBuffers %.2f ms\n",
               (t1 - t0) / 1e6, (t2 - t1) / 1e6, (t3 - t2) / 1e6);
        /* textures for the arg-buffer shaders (Metal 3 bindless: resource ids written into a buffer) */
        id<MTLBuffer> texArg = [dev newBufferWithLength:64 options:MTLResourceStorageModeShared];
        id<MTLComputePipelineState> encodeKernel = [dev newComputePipelineStateWithFunction:[lib newFunctionWithName:@"encode_icb"] error:&err];
        if (!encodeKernel) { printf("kernel error: %s\n", err.localizedDescription.UTF8String); return 1; }
        MTLDepthStencilDescriptor *dd = [MTLDepthStencilDescriptor new];
        dd.depthCompareFunction = MTLCompareFunctionGreaterEqual;
        dd.depthWriteEnabled = YES;
        id<MTLDepthStencilState> dss = [dev newDepthStencilStateWithDescriptor:dd];

        MTLIndirectCommandBufferDescriptor *icbd = [MTLIndirectCommandBufferDescriptor new];
        icbd.commandTypes = MTLIndirectCommandTypeDrawIndexed;
        icbd.inheritPipelineState = YES;
        icbd.inheritBuffers = YES;
        icbd.maxVertexBufferBindCount = 0;
        icbd.maxFragmentBufferBindCount = 0;
        id<MTLIndirectCommandBuffer> icbCpu = [dev newIndirectCommandBufferWithDescriptor:icbd maxCommandCount:n options:MTLResourceStorageModeShared];
        id<MTLIndirectCommandBuffer> icbCache = [dev newIndirectCommandBufferWithDescriptor:icbd maxCommandCount:n options:MTLResourceStorageModeShared];
        id<MTLIndirectCommandBuffer> icbGpu = [dev newIndirectCommandBufferWithDescriptor:icbd maxCommandCount:n options:MTLResourceStorageModePrivate];
        id<MTLBuffer> icbArg = [dev newBufferWithLength:sizeof(MTLResourceID) options:MTLResourceStorageModeShared];
        *(MTLResourceID *)icbArg.contents = icbGpu.gpuResourceID;
        {
            MTLResourceID *ids = texArg.contents;
            ids[0] = atlas.gpuResourceID; ids[1] = nearest.gpuResourceID; ids[2] = lightmap.gpuResourceID; ids[3] = linear.gpuResourceID;
        }
        const Record *ringRecs = (const Record *)((const uint8_t *)ring.contents + ringOffset);
        for (uint32_t i = 0; i < n; i++) {
            id<MTLIndirectRenderCommand> c = [icbCache indirectRenderCommandAtIndex:i];
            const Record *r = &ringRecs[i];
            [c drawIndexedPrimitives:MTLPrimitiveTypeTriangle indexCount:r->indexCount indexType:MTLIndexTypeUInt16 indexBuffer:qib
                   indexBufferOffset:(NSUInteger)r->firstIndex * 2u instanceCount:r->instanceCount baseVertex:r->baseVertex baseInstance:r->baseInstance];
        }
        static const float cutoutValues[2] = { -1.0f, 0.1f };

        id<MTLBuffer> readback = [dev newBufferWithLength:(NSUInteger)W * H * 4 options:MTLResourceStorageModeShared];
        __block NSMutableData *reference = nil;
        int (^runFrame)(uint32_t, BOOL, double *, double *) = ^int(uint32_t path, BOOL check, double *gpuOut, double *cpuOut) {
                    @autoreleasepool {
                        id<MTLCommandBuffer> cb = [queue commandBuffer];
                        uint64_t c0 = 0, c1 = 0;
                        if (path == P_ICB_GPU) {
                            c0 = now_ns();
                            id<MTLComputeCommandEncoder> ce = [cb computeCommandEncoder];
                            [ce setComputePipelineState:encodeKernel];
                            [ce setBuffer:ring offset:ringOffset atIndex:0];
                            [ce setBuffer:icbArg offset:0 atIndex:2];
                            [ce setBuffer:qib offset:0 atIndex:3];
                            [ce useResource:icbGpu usage:MTLResourceUsageWrite];
                            const uint32_t params[4] = { n, 0, 0, 0 };
                            [ce setBytes:params length:sizeof params atIndex:1];
                            [ce dispatchThreads:MTLSizeMake(n, 1, 1) threadsPerThreadgroup:MTLSizeMake(64, 1, 1)];
                            [ce endEncoding];
                            c1 = now_ns();
                        }
                        MTLRenderPassDescriptor *rp = [MTLRenderPassDescriptor renderPassDescriptor];
                        rp.colorAttachments[0].texture = color;
                        rp.colorAttachments[0].loadAction = MTLLoadActionClear;
                        rp.colorAttachments[0].clearColor = MTLClearColorMake(0.1, 0.2, 0.3, 1.0);
                        rp.colorAttachments[0].storeAction = MTLStoreActionStore;
                        rp.depthAttachment.texture = depth;
                        rp.depthAttachment.loadAction = MTLLoadActionClear;
                        rp.depthAttachment.clearDepth = 0.0;
                        rp.depthAttachment.storeAction = MTLStoreActionDontCare;
                        id<MTLRenderCommandEncoder> re = [cb renderCommandEncoderWithDescriptor:rp];
                        const BOOL icbPath = path >= P_ICB_CPU;
                        [re setRenderPipelineState:icbPath || path == P_DIRECT_ICBPSO ? psoIcb : path == P_DIRECT_AB ? psoAb : pso];
                        if (icbPath || path == P_DIRECT_AB || path == P_DIRECT_ICBPSO) {
                            [re setVertexBuffer:texArg offset:0 atIndex:4];
                            [re setFragmentBuffer:texArg offset:0 atIndex:4];
                            [re useResource:atlas usage:MTLResourceUsageRead stages:MTLRenderStageFragment];
                            [re useResource:lightmap usage:MTLResourceUsageRead stages:MTLRenderStageVertex];
                        }
                        [re setDepthStencilState:dss];
                        [re setCullMode:MTLCullModeBack];
                        [re setFrontFacingWinding:MTLWindingCounterClockwise];
                        [re setVertexBuffer:globBuf offset:0 atIndex:2];
                        [re setFragmentBuffer:globBuf offset:0 atIndex:2];
                        [re setVertexTexture:lightmap atIndex:2];
                        [re setVertexSamplerState:linear atIndex:2];
                        [re setFragmentTexture:atlas atIndex:0];
                        [re setFragmentSamplerState:nearest atIndex:0];
                        [re setVertexBuffer:instBuf offset:0 atIndex:1];
                        const uint64_t e0 = now_ns();
                        if (icbPath) [re useResource:qib usage:MTLResourceUsageRead stages:MTLRenderStageVertex];
                        if (path == P_ICB_CPU) {
                            /* encode every record (what the decoder would do per frame without a cache) */
                            for (uint32_t i = 0; i < n; i++) {
                                const Record *r = &ringRecs[i];
                                id<MTLIndirectRenderCommand> c = [icbCpu indirectRenderCommandAtIndex:i];
                                if (r->indexCount == 0 || r->instanceCount == 0) { [c reset]; continue; }
                                [c drawIndexedPrimitives:MTLPrimitiveTypeTriangle indexCount:r->indexCount indexType:MTLIndexTypeUInt16
                                             indexBuffer:qib indexBufferOffset:(NSUInteger)r->firstIndex * 2u instanceCount:r->instanceCount
                                              baseVertex:r->baseVertex baseInstance:r->baseInstance];
                            }
                        }
                        uint32_t lastLayer = UINT32_MAX;
                        for (uint32_t gi = 0; gi < groupCount; gi++) {
                            const Group *g = &groups[gi];
                            if (g->layer != lastLayer) {
                                [re setFragmentBytes:&cutoutValues[g->layer] length:sizeof(float) atIndex:3];
                                lastLayer = g->layer;
                            }
                            [re setVertexBuffer:vbs[g->vb] offset:0 atIndex:0];
                            switch (path) {
                                case P_INDIRECT:
                                    for (uint32_t i = 0; i < g->count; i++) {
                                        [re drawIndexedPrimitives:MTLPrimitiveTypeTriangle indexType:MTLIndexTypeUInt16 indexBuffer:qib indexBufferOffset:0
                                                   indirectBuffer:ring indirectBufferOffset:ringOffset + (NSUInteger)(g->first + i) * 20u];
                                    }
                                    break;
                                case P_DIRECT:
                                case P_DIRECT_AB:
                                case P_DIRECT_ICBPSO:
                                    for (uint32_t i = 0; i < g->count; i++) {
                                        const Record *r = &ringRecs[g->first + i];
                                        if (r->indexCount == 0 || r->instanceCount == 0) continue;
                                        [re drawIndexedPrimitives:MTLPrimitiveTypeTriangle indexCount:r->indexCount indexType:MTLIndexTypeUInt16
                                                      indexBuffer:qib indexBufferOffset:(NSUInteger)r->firstIndex * 2u instanceCount:r->instanceCount
                                                       baseVertex:r->baseVertex baseInstance:r->baseInstance];
                                    }
                                    break;
                                case P_ICB_CPU:
                                    [re executeCommandsInBuffer:icbCpu withRange:NSMakeRange(g->first, g->count)];
                                    break;
                                case P_ICB_CACHE:
                                    /* PROBE_SPLIT=k: k executes per group (isolates the GPU cost of one execute) */
                                    for (uint32_t k = 0, step = (g->count + split - 1) / split; k < g->count; k += step) {
                                        [re executeCommandsInBuffer:icbCache withRange:NSMakeRange(g->first + k, MIN(step, g->count - k))];
                                    }
                                    break;
                                case P_ICB_GPU:
                                    [re executeCommandsInBuffer:icbGpu withRange:NSMakeRange(g->first, g->count)];
                                    break;
                                case P_NONE: break;
                                case P_ONE:
                                    if (gi == 0) [re drawIndexedPrimitives:MTLPrimitiveTypeTriangle indexCount:ringRecs[0].indexCount indexType:MTLIndexTypeUInt16 indexBuffer:qib indexBufferOffset:(NSUInteger)ringRecs[0].firstIndex * 2u instanceCount:1 baseVertex:ringRecs[0].baseVertex baseInstance:ringRecs[0].baseInstance];
                                    break;
                            }
                        }
                        const uint64_t e1 = now_ns();
                        [re endEncoding];
                        *cpuOut = (double)(e1 - e0 + c1 - c0) / 1e3;
                        if (check) {
                            id<MTLBlitCommandEncoder> be = [cb blitCommandEncoder];
                            [be copyFromTexture:color sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0, 0, 0) sourceSize:MTLSizeMake(W, H, 1)
                                       toBuffer:readback destinationOffset:0 destinationBytesPerRow:W * 4 destinationBytesPerImage:(NSUInteger)W * H * 4];
                            [be endEncoding];
                        }
                        if (pipelined && !check) {
                            dispatch_semaphore_wait(inflight, DISPATCH_TIME_FOREVER);
                            [cb addCompletedHandler:^(id<MTLCommandBuffer> done) { dispatch_semaphore_signal(inflight); }];
                        }
                        [cb commit];
                        if (pipelined && !check) { *gpuOut = 0; return 0; }
                        [cb waitUntilCompleted];
                        if (cb.status != MTLCommandBufferStatusCompleted) {
                            printf("command buffer failed: %s\n", cb.error.localizedDescription.UTF8String);
                            exit(1);
                        }
                        *gpuOut = (cb.GPUEndTime - cb.GPUStartTime) * 1000.0;
                        if (check) {
                            if (path == P_INDIRECT) {
                                reference = [NSMutableData dataWithBytes:readback.contents length:readback.length];
                                const uint32_t *p = readback.contents;
                                uint32_t clearPx = 0;
                                for (NSUInteger i = 0; i < (NSUInteger)W * H; i++) clearPx += p[i] == 0xFF4D331Au;
                                printf("U4 reference (indirect path): %.1f%% of pixels still clear color\n", 100.0 * clearPx / ((double)W * H));
                            } else if (path < P_NONE) {
                                const uint32_t *a = reference.bytes, *b = readback.contents;
                                uint64_t diff = 0;
                                for (NSUInteger i = 0; i < (NSUInteger)W * H; i++) diff += a[i] != b[i];
                                printf("U4 %-34s bytes vs indirect path: %s (%llu differing pixels)\n", kPathNames[path], diff ? "DIFFERENT" : "identical",
                                       (unsigned long long)diff);
                            }
                        }
                    }
            return 0;
        };
        /* warm-up + U4 byte checks */
        for (uint32_t path = 0; path < P_COUNT; path++) {
            double g, c;
            for (int i = 0; i < 3; i++) runFrame(path, i == 2, &g, &c);
        }
        /* paths interleaved frame by frame, so background GPU load hits all of them alike */
        const uint32_t total = frames * rounds;
        double *gpu = calloc((size_t)P_COUNT * total, sizeof(double)), *cpu = calloc((size_t)P_COUNT * total, sizeof(double));
        for (uint32_t f = 0; f < total; f++) {
            for (uint32_t path = 0; path < P_COUNT; path++) runFrame(path, NO, &gpu[path * total + f], &cpu[path * total + f]);
        }
        printf("\n%u frames per path (interleaved); GPU ms per frame: median, 25th percentile; CPU encode us per frame: median, mean\n", total);
        printf("%-36s %9s %9s %11s %9s\n", "path", "GPU med", "GPU p25", "CPU med", "CPU mean");
        for (uint32_t path = 0; path < P_COUNT; path++) {
            double *g = &gpu[path * total], *c = &cpu[path * total];
            double cs = 0;
            for (uint32_t i = 0; i < total; i++) cs += c[i];
            qsort(g, total, sizeof(double), cmp_double);
            qsort(c, total, sizeof(double), cmp_double);
            printf("%-36s %9.4f %9.4f %11.1f %9.1f\n", kPathNames[path], g[total / 2], g[total / 4], c[total / 2], cs / total);
        }
    }
    return 0;
}
