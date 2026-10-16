/*
 * occlusion_test.m -- headless test of the decoder's terrain occlusion culling (MCMTL_OCCLUSION, CONTRACT.md 16.6,
 * docs/metal/OCCLUSION.md) through the real mcmtl_submit export. Built and run by tools/build-native-tests.sh
 * (MTL_DEBUG_LAYER=1). No window: everything renders into offscreen textures.
 *
 * Two decoder devices share one MTLDevice and every resource: OFF (occlusion disabled) and ON (MCMTL_OCCLUSION=1
 * through the test hook, results read 2 frames late like the game with 2 frames in flight). Each frame of a camera
 * path is built once (vanilla-shaped streams: staged upload of the section infos, multidraw terrain records per layer
 * with facing buckets and an exact frustum, an "entity" draw after the opaque terrain, translucent water) and submitted
 * to both; colour and depth must be byte-identical in every frame. Paths: static, fast turns (up to 15 degrees per
 * frame), translation, a zoom, mesh changes that open a hole into culled caves, a vanished occluder, and the
 * benchmark-like view 40 blocks above the terrain.
 *
 * OCCLUSION_BENCH=1: no parity checks; GPU throughput at 3600x2260 with 2 frames in flight, OFF vs ON, for the
 * benchmark-like view (spinning 0.12 degrees per frame = 36 degrees/s at 300 FPS) and a ground-level view into
 * hills and caves (static and spinning). OCCLUSION_BENCH_FRAMES (default 600) frames per block, blocks interleaved.
 */
#import "mcmetal_internal.h"
#include "occlusion_scene.h"

#include <mach/mach_time.h>
#include <stdio.h>
#include <unistd.h>

/* Mirror of mcmtl_decoder_stats (mcmetal_decoder.m); structSize is checked. */
typedef struct mcmtl_decoder_stats {
    uint32_t structSize, reserved;
    uint64_t commandBuffersCommitted, untimedCommits, renderEncoders, blitEncoders, blitSplits, setBuffer, setBufferOffset,
        setBytes, setTexture, setSampler, setPipelineState, setDepthStencilState, setRasterState, setScissor, dummyBindings,
        clearQuads, draws, standaloneTimestamps, frames, passesIn, passEncoders, merges, mergeBreaks, hoists, clearsRecorded,
        clearsFolded, clearsDropped, clearsMaterialized, clearDraws, dontCareStores, lateStores, submitNs, drawableWaitNs,
        fusedFrames, fusedPasses, fusionWaitNs, fusionRestores, fusionStaleDropped, fusionVariantBuilds, fusionBackoffs;
    uint64_t fusionFallback[13];
    uint64_t presentRecords, presentsEncoded, presentsNil, mailboxRecords, presentsSkipped;
    uint64_t presentSkip[4];
    uint64_t indirectRecordDraws, icbGroups, icbRecords, icbHits, icbEncodedRecords, icbCreated, icbArgBuffers, icbVariantBuilds;
    uint64_t icbFallback[9];
    uint64_t occlFrames, occlSections, occlCulledSections, occlWouldCull, occlSkippedRecords, occlSkippedQuads, occlPlannedQuads,
        occlGeomQueries, occlBoxTests, occlEyeResets, occlInvalidated, occlResultFrames, occlResultsDropped, occlPlanNs;
    uint64_t occlNoPlan[8];
} mcmtl_decoder_stats;
const mcmtl_decoder_stats *mcmtl_decoder_get_stats(MCMTLDevice *dev);
uint32_t mcmtl_decoder_test_set_occlusion(MCMTLDevice *dev, uint32_t mode, uint32_t confirm, float nearBlocks, uint32_t minAge);
uint32_t mcmtl_decoder_test_set_fusion(MCMTLDevice *dev, uint32_t mode, uint32_t testFlags, uint64_t maxWaitNs);

static int failures = 0, checks = 0;
#define CHECK(cond, ...)                                                   \
    do {                                                                   \
        checks++;                                                          \
        if (!(cond)) {                                                     \
            failures++;                                                    \
            printf("FAIL %s:%d: ", __FILE__, __LINE__);                    \
            printf(__VA_ARGS__);                                           \
            printf("\n");                                                  \
        }                                                                  \
    } while (0)

static id<MTLDevice> gMtl;
static id<MTLLibrary> gLib;

static inline mcmtl_handle H(id object) { return object ? (mcmtl_handle)(uintptr_t)(__bridge void *)object : 0; }

/* ---------------------------------------------------------------------------------------------- */
/* Stream writer (as decoder_test.m)                                                                */

typedef struct Stream { uint8_t *data; size_t length, capacity; } Stream;

static void *rec_add(Stream *s, uint16_t op, size_t bytes) {
    size_t size = (bytes + 15u) & ~(size_t)15u;
    if (s->length + size > s->capacity) {
        size_t cap = s->capacity ? s->capacity * 2 : 65536;
        while (cap < s->length + size) cap *= 2;
        s->data = realloc(s->data, cap);
        s->capacity = cap;
    }
    uint8_t *p = s->data + s->length;
    memset(p, 0, size);
    s->length += size;
    ((mcmtl_cmd_header *)p)->op = op;
    ((mcmtl_cmd_header *)p)->size = (uint16_t)size;
    return p;
}

static void r_begin_pass(Stream *s, id<MTLTexture> color, id<MTLTexture> depth, const float clear[4]) {
    uint8_t *rec = rec_add(s, MCMTL_OP_BEGIN_PASS, MCMTL_CMD_SIZE_BEGIN_PASS_BASE + MCMTL_CMD_SIZE_COLOR_ATTACHMENT);
    mcmtl_cmd_begin_pass *c = (mcmtl_cmd_begin_pass *)rec;
    c->colorCount = 1;
    c->flags = MCMTL_PASS_FLAG_DEPTH_CLEAR;
    c->colorClearMask = 1;
    c->renderWidth = (int32_t)color.width;
    c->renderHeight = (int32_t)color.height;
    c->depthClearValue = 0.0f;
    c->depthTexture = H(depth);
    mcmtl_cmd_color_attachment *a = (mcmtl_cmd_color_attachment *)(rec + MCMTL_CMD_SIZE_BEGIN_PASS_BASE);
    a[0].texture = H(color);
    memcpy(a[0].clearColor, clear, sizeof a[0].clearColor);
}
static void r_end_pass(Stream *s) { rec_add(s, MCMTL_OP_END_PASS, sizeof(mcmtl_cmd_end_pass)); }
static void r_set_pipeline(Stream *s, MCMTLPipeline *p) {
    mcmtl_cmd_set_pipeline *c = rec_add(s, MCMTL_OP_SET_PIPELINE, sizeof *c);
    c->pipeline = H(p);
}
static void r_set_buffer(Stream *s, uint32_t index, uint32_t stages, id<MTLBuffer> b, uint64_t offset) {
    mcmtl_cmd_set_buffer *c = rec_add(s, MCMTL_OP_SET_BUFFER, sizeof *c);
    c->index = index; c->stages = stages; c->buffer = H(b); c->offset = offset;
}
static void r_set_texture(Stream *s, uint32_t index, uint32_t stages, id<MTLTexture> t, id<MTLSamplerState> smp) {
    mcmtl_cmd_set_texture *c = rec_add(s, MCMTL_OP_SET_TEXTURE, sizeof *c);
    c->index = index; c->stages = stages; c->texture = H(t); c->sampler = H(smp);
}
static void r_set_vb(Stream *s, uint32_t slot, id<MTLBuffer> b, uint64_t offset) {
    mcmtl_cmd_set_vertex_buffer *c = rec_add(s, MCMTL_OP_SET_VERTEX_BUFFER, sizeof *c);
    c->slot = slot; c->buffer = H(b); c->offset = offset;
}
static void r_set_ib(Stream *s, MTLIndexType type, id<MTLBuffer> b) {
    mcmtl_cmd_set_index_buffer *c = rec_add(s, MCMTL_OP_SET_INDEX_BUFFER, sizeof *c);
    c->indexType = (uint32_t)type; c->buffer = H(b);
}
static void r_draw(Stream *s, uint32_t vertexCount, uint32_t instanceCount) {
    mcmtl_cmd_draw *c = rec_add(s, MCMTL_OP_DRAW, sizeof *c);
    c->vertexCount = vertexCount; c->instanceCount = instanceCount;
}
static void r_draw_indirect(Stream *s, uint32_t count, id<MTLBuffer> b, uint64_t offset) {
    mcmtl_cmd_draw_indirect *c = rec_add(s, MCMTL_OP_DRAW_INDEXED_INDIRECT, sizeof *c);
    c->drawCount = count; c->buffer = H(b); c->offset = offset;
}
static void r_copy_buffer(Stream *s, id<MTLBuffer> src, uint64_t so, id<MTLBuffer> dst, uint64_t dof, uint64_t len) {
    mcmtl_cmd_copy_buffer *c = rec_add(s, MCMTL_OP_COPY_BUFFER, sizeof *c);
    c->srcBuffer = H(src); c->srcOffset = so; c->dstBuffer = H(dst); c->dstOffset = dof; c->length = len;
}
static void r_readback(Stream *s, id<MTLTexture> t, id<MTLBuffer> b) {
    mcmtl_cmd_copy_buffer_texture *c = rec_add(s, MCMTL_OP_COPY_TEXTURE_TO_BUFFER, sizeof *c);
    c->buffer = H(b); c->texture = H(t);
    c->bytesPerRow = (uint32_t)t.width * 4; c->bytesPerImage = c->bytesPerRow * (uint32_t)t.height;
    c->width = (uint32_t)t.width; c->height = (uint32_t)t.height;
}

/* ---------------------------------------------------------------------------------------------- */
/* Devices                                                                                          */

enum { P_SOLID = 0, P_CUTOUT, P_TRANSLUCENT, P_ENTITY, P_COUNT };

typedef struct Dev {
    const char *name;
    MCMTLDevice *dev;
    mcmtl_handle h;
    uint64_t next;
    MCMTLPipeline *pipes[P_COUNT];
} Dev;

static MCMTLPipeline *make_pipeline(Dev *d, int kind) {
    MCMTLPipeline *p = [MCMTLPipeline new];
    p->owner = d->dev;
    MTLRenderPipelineDescriptor *desc = [MTLRenderPipelineDescriptor new];
    static const char *const names[P_COUNT] = { "solid terrain", "cutout terrain", "translucent terrain", "entity" };
    desc.label = [NSString stringWithUTF8String:names[kind]];
    if (kind == P_ENTITY) {
        desc.vertexFunction = [gLib newFunctionWithName:@"entity_vs"];
        desc.fragmentFunction = [gLib newFunctionWithName:@"entity_fs"];
    } else {
        desc.vertexFunction = [gLib newFunctionWithName:@"terrain_vs"];
        desc.fragmentFunction = [gLib newFunctionWithName:kind == P_SOLID ? @"solid_fs" : kind == P_CUTOUT ? @"cutout_fs" : @"translucent_fs"];
        MTLVertexDescriptor *vd = [MTLVertexDescriptor vertexDescriptor];
        const struct { MTLVertexFormat f; uint32_t off, slot; } attrs[6] = {
            { MTLVertexFormatFloat3, 0, 0 }, { MTLVertexFormatUChar4Normalized, 12, 0 }, { MTLVertexFormatFloat2, 16, 0 },
            { MTLVertexFormatShort2, 24, 0 }, { MTLVertexFormatInt3, 0, 1 }, { MTLVertexFormatFloat, 12, 1 } };
        for (int i = 0; i < 6; i++) {
            vd.attributes[i].format = attrs[i].f;
            vd.attributes[i].offset = attrs[i].off;
            vd.attributes[i].bufferIndex = 30 - attrs[i].slot;
        }
        vd.layouts[30].stride = 28;
        vd.layouts[30].stepFunction = MTLVertexStepFunctionPerVertex;
        vd.layouts[29].stride = 16;
        vd.layouts[29].stepFunction = MTLVertexStepFunctionPerInstance;
        vd.layouts[29].stepRate = 1;
        desc.vertexDescriptor = vd;
        p->requiredVertexBufferMask = (1u << 30) | (1u << 29);
    }
    if (kind == P_TRANSLUCENT) {
        desc.colorAttachments[0].blendingEnabled = YES;
        desc.colorAttachments[0].sourceRGBBlendFactor = MTLBlendFactorSourceAlpha;
        desc.colorAttachments[0].destinationRGBBlendFactor = MTLBlendFactorOneMinusSourceAlpha;
        desc.colorAttachments[0].sourceAlphaBlendFactor = MTLBlendFactorOne;
        desc.colorAttachments[0].destinationAlphaBlendFactor = MTLBlendFactorOneMinusSourceAlpha;
    }
    p->descriptorTemplate = desc;
    p->label = desc.label;
    p->colorTargetCount = 1;
    p->colorTargetFormats[0] = MTLPixelFormatRGBA8Unorm;
    p->depthStencilState = mcmtl_device_depth_stencil_state(d->dev, MTLCompareFunctionGreaterEqual, YES);
    p->primitiveType = MTLPrimitiveTypeTriangle;
    p->emulation = MCMTL_EMULATION_NONE;
    p->cullMode = MTLCullModeBack;
    p->fillMode = MTLTriangleFillModeFill;
    p->frontFacing = MTLWindingClockwise;
    if (kind == P_ENTITY) {
        p->bufferMask[0] = (1u << 0) | (1u << 5) | (1u << 6);
    } else {
        p->bufferMask[0] = (1u << 0) | (1u << 1) | (1u << 5) | p->requiredVertexBufferMask;
        p->bufferMask[1] = (1u << 2) | (1u << 5);
        p->textureMask[0] = p->samplerMask[0] = 1u << 2;
        p->textureMask[1] = p->samplerMask[1] = 1u << 0;
    }
    MTLRenderPipelineDescriptor *v = [desc copy];
    v.colorAttachments[0].pixelFormat = MTLPixelFormatRGBA8Unorm;
    v.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
    NSError *error = nil;
    id<MTLRenderPipelineState> pso = [gMtl newRenderPipelineStateWithDescriptor:v error:&error];
    CHECK(pso != nil, "pipeline %s: %s", names[kind], error.localizedDescription.UTF8String);
    mcmtl_pipeline_add_variant(p, mcmtl_variant_key(1, MTLPixelFormatDepth32Float), pso);
    return p;
}

static BOOL dev_init(Dev *d, const char *name, BOOL validation, uint32_t occlMode, uint32_t minAge) {
    d->name = name;
    d->dev = mcmtl_device_context_create(gMtl, [NSString stringWithUTF8String:name]);
    if (!d->dev) return NO;
    d->dev->flags = validation ? MCMTL_DEVICE_FLAG_VALIDATION : 0;
    if (!mcmtl_decoder_device_init(d->dev)) return NO;
    CHECK(mcmtl_decoder_get_stats(d->dev)->structSize == sizeof(mcmtl_decoder_stats), "stats struct mismatch");
    d->h = (mcmtl_handle)(uintptr_t)(__bridge void *)d->dev;
    d->next = 1;
    mcmtl_decoder_test_set_fusion(d->dev, 0, 0, 0);
    /* OCCLUSION_TEST_CONFIRM / _NEAR / _MIN_AGE override the ON / DRY settings (stress runs). */
    const char *c = getenv("OCCLUSION_TEST_CONFIRM"), *n = getenv("OCCLUSION_TEST_NEAR"), *a = getenv("OCCLUSION_TEST_MIN_AGE");
    mcmtl_decoder_test_set_occlusion(d->dev, occlMode, c ? (uint32_t)atoi(c) : 2u, n ? (float)atof(n) : 8.0f, a ? (uint32_t)atoi(a) : minAge);
    for (int k = 0; k < P_COUNT; k++) d->pipes[k] = make_pipeline(d, k);
    return YES;
}

static uint64_t dev_submit(Dev *d, Stream *s) {
    mcmtl_submit_info info;
    memset(&info, 0, sizeof info);
    info.structSize = sizeof info;
    info.flags = MCMTL_SUBMIT_FLAG_COMMIT | MCMTL_SUBMIT_FLAG_FRAME_END;
    info.stream = s->data;
    info.streamLength = s->length;
    info.signalValue = d->next++;
    mcmtl_submit_result result;
    memset(&result, 0, sizeof result);
    result.structSize = sizeof result;
    int32_t rc = mcmtl_submit(d->h, &info, &result);
    CHECK(rc == MCMTL_OK && (result.flags & MCMTL_SUBMIT_RESULT_FLAG_COMMITTED), "%s: submit rc %d (%s)", d->name, rc, mcmtl_last_error());
    CHECK(!(result.flags & MCMTL_SUBMIT_RESULT_FLAG_DRAWS_SKIPPED), "%s: draws skipped", d->name);
    return info.signalValue;
}

static void dev_wait(Dev *d, uint64_t value) {
    if (!value) return;
    int32_t w = mcmtl_wait_completed(d->dev, value, 10000000000ull);
    CHECK(w == MCMTL_OK, "%s: wait %llu -> %d", d->name, (unsigned long long)value, w);
    for (int i = 0; i < 100000 && mcmtl_status_load_u64(&d->dev->status->completedValue) < value; i++) usleep(50);
}

/* ---------------------------------------------------------------------------------------------- */
/* Scene resources and frames                                                                       */

#define RING 4
#define MAX_SECS 8192
#define MAX_RECS 65536

typedef struct Res {
    uint32_t w, h;
    id<MTLTexture> color, depth, atlas, lightmap;
    id<MTLSamplerState> atlasSampler, lightSampler;
    id<MTLBuffer> heap[SL_COUNT];
    uint32_t heapUsed[SL_COUNT], heapCap[SL_COUNT];
    id<MTLBuffer> idx16, inst, staging, args, ubo, rbColor, rbDepth;
} Res;

typedef struct Pose {
    double x, y, z;
    float yaw, pitch, fov;
} Pose;

static void upload_section(Res *r, ScSection *s) {
    for (int l = 0; l < SL_COUNT; l++) {
        ScMesh *m = &s->layer[l];
        if (!m->quads) continue;
        CHECK(r->heapUsed[l] + m->quads <= r->heapCap[l], "heap %d full", l);
        if (r->heapUsed[l] + m->quads > r->heapCap[l]) return;
        memcpy((uint8_t *)r->heap[l].contents + (size_t)r->heapUsed[l] * 4 * sizeof(ScVtx), m->v, (size_t)m->quads * 4 * sizeof(ScVtx));
        s->heapQuad[l] = r->heapUsed[l];
        r->heapUsed[l] += m->quads;
    }
}

static void res_init(Res *r, ScWorld *w, uint32_t width, uint32_t height) {
    for (int l = 0; l < SL_COUNT; l++) r->heapUsed[l] = r->heapCap[l] = 0;
    r->w = width;
    r->h = height;
    MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:width height:height mipmapped:NO];
    td.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
    td.storageMode = MTLStorageModePrivate;
    r->color = [gMtl newTextureWithDescriptor:td];
    td.pixelFormat = MTLPixelFormatDepth32Float;
    r->depth = [gMtl newTextureWithDescriptor:td];
    MTLTextureDescriptor *ad = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:256 height:256 mipmapped:YES];
    ad.mipmapLevelCount = 5;
    r->atlas = [gMtl newTextureWithDescriptor:ad];
    for (int lvl = 0; lvl < 5; lvl++) {
        int s = 256 >> lvl;
        uint8_t *px = malloc((size_t)s * s * 4);
        for (int y = 0; y < s; y++)
            for (int x = 0; x < s; x++) {
                int tile = ((y << lvl) / 16) * 16 + (x << lvl) / 16;
                float n = sc_rnd3((x << lvl) & 15, ((y << lvl) & 15) + 100 * lvl, tile);
                uint8_t v = (uint8_t)(170 + 85 * n), a = 255;
                if (tile == 6 && sc_rnd3(x, y + 1000 * lvl, 99) < 0.35f) a = 0;   /* leaves: holes */
                uint8_t *p = px + 4 * (y * s + x);
                p[0] = v; p[1] = v; p[2] = v; p[3] = a;
            }
        [r->atlas replaceRegion:MTLRegionMake2D(0, 0, s, s) mipmapLevel:lvl withBytes:px bytesPerRow:s * 4];
        free(px);
    }
    MTLTextureDescriptor *ld = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:16 height:16 mipmapped:NO];
    r->lightmap = [gMtl newTextureWithDescriptor:ld];
    uint8_t lm[16 * 16 * 4];
    memset(lm, 255, sizeof lm);
    [r->lightmap replaceRegion:MTLRegionMake2D(0, 0, 16, 16) mipmapLevel:0 withBytes:lm bytesPerRow:64];
    MTLSamplerDescriptor *sd = [MTLSamplerDescriptor new];
    sd.minFilter = sd.magFilter = MTLSamplerMinMagFilterLinear;
    sd.mipFilter = MTLSamplerMipFilterLinear;
    sd.sAddressMode = sd.tAddressMode = MTLSamplerAddressModeClampToEdge;
    r->atlasSampler = [gMtl newSamplerStateWithDescriptor:sd];
    sd.mipFilter = MTLSamplerMipFilterNotMipmapped;
    r->lightSampler = [gMtl newSamplerStateWithDescriptor:sd];
    uint32_t quads[SL_COUNT] = {0};
    for (int i = 0; i < w->count; i++)
        for (int l = 0; l < SL_COUNT; l++) quads[l] += w->secs[w->index[i]].layer[l].quads;
    for (int l = 0; l < SL_COUNT; l++) {
        r->heapCap[l] = quads[l] + quads[l] / 2 + 65536;
        r->heap[l] = [gMtl newBufferWithLength:(NSUInteger)r->heapCap[l] * 4 * sizeof(ScVtx) options:MTLResourceStorageModeShared];
    }
    for (int i = 0; i < w->count; i++) upload_section(r, &w->secs[w->index[i]]);
    uint16_t *idx = malloc(16383 * 12);
    for (int q = 0; q < 16383; q++) {
        uint16_t v = (uint16_t)(4 * q);
        uint16_t six[6] = { v, (uint16_t)(v + 1), (uint16_t)(v + 2), (uint16_t)(v + 2), (uint16_t)(v + 3), v };
        memcpy(idx + 6 * q, six, 12);
    }
    r->idx16 = [gMtl newBufferWithBytes:idx length:16383 * 12 options:MTLResourceStorageModeShared];
    free(idx);
    r->inst = [gMtl newBufferWithLength:16 * MAX_SECS options:MTLResourceStorageModeShared];
    r->staging = [gMtl newBufferWithLength:16 * MAX_SECS * RING options:MTLResourceStorageModeShared];
    r->args = [gMtl newBufferWithLength:20 * MAX_RECS * RING options:MTLResourceStorageModeShared];
    r->ubo = [gMtl newBufferWithLength:1024 * 2 * RING + 16 * 64 * RING options:MTLResourceStorageModeShared];
    r->rbColor = [gMtl newBufferWithLength:(NSUInteger)width * height * 4 options:MTLResourceStorageModeShared];
    r->rbDepth = [gMtl newBufferWithLength:(NSUInteger)width * height * 4 options:MTLResourceStorageModeShared];
}

typedef struct Frame {
    uint32_t ring;
    uint32_t sections, recs[SL_COUNT];
    uint64_t argsOffset[SL_COUNT];
    uint64_t uboBase, cubesOffset;
    uint32_t cubes;
    uint64_t quads;
} Frame;

typedef struct SortItem { float d; int i; } SortItem;
static int sort_near(const void *a, const void *b) {
    float x = ((const SortItem *)a)->d, y = ((const SortItem *)b)->d;
    return x < y ? -1 : x > y;
}

/* Builds the CPU side of one frame (records, section infos, uniforms) into ring region `ring`. */
static Frame build_frame(Res *r, ScWorld *w, const Pose *p, uint32_t ring) {
    Frame f;
    memset(&f, 0, sizeof f);
    f.ring = ring;
    ScM4 proj = sc_persp(p->fov * (float)M_PI / 180.0f, (float)r->w / r->h, 0.05f, 1024.0f);
    ScM4 view = sc_view(p->yaw, p->pitch);
    ScFrustum fr = sc_frustum(sc_mul(proj, view));
    static SortItem items[MAX_SECS * 2];
    int n = 0;
    for (int k = 0; k < w->count; k++) {
        ScSection *s = &w->secs[w->index[k]];
        if (!s->present) continue;
        const float m = 1.0f / 64;
        if (!sc_box_visible(&fr, (float)(s->ox + s->bmin[0] - m - p->x), (float)(s->oy + s->bmin[1] - m - p->y), (float)(s->oz + s->bmin[2] - m - p->z),
                            (float)(s->ox + s->bmax[0] + m - p->x), (float)(s->oy + s->bmax[1] + m - p->y), (float)(s->oz + s->bmax[2] + m - p->z))) {
            continue;
        }
        float dx = (float)(s->ox + 8 - p->x), dy = (float)(s->oy + 8 - p->y), dz = (float)(s->oz + 8 - p->z);
        items[n++] = (SortItem){ dx * dx + dy * dy + dz * dz, w->index[k] };
    }
    qsort(items, n, sizeof items[0], sort_near);
    int32_t *infos = (int32_t *)((uint8_t *)r->staging.contents + (size_t)16 * MAX_SECS * ring);
    for (int i = 0; i < n; i++) {
        ScSection *s = &w->secs[items[i].i];
        infos[4 * i] = s->ox; infos[4 * i + 1] = s->oy; infos[4 * i + 2] = s->oz;
        ((float *)infos)[4 * i + 3] = 1.0f;
    }
    f.sections = (uint32_t)n;
    uint32_t *rec = (uint32_t *)((uint8_t *)r->args.contents + (size_t)20 * MAX_RECS * ring);
    uint32_t total = 0;
    for (int l = 0; l < SL_COUNT; l++) {
        f.argsOffset[l] = (uint64_t)20 * MAX_RECS * ring + 20ull * total;
        uint32_t before = total;
        for (int ii = 0; ii < n; ii++) {
            const int i = l == SL_TRANSLUCENT ? n - 1 - ii : ii;   /* translucent far to near */
            ScSection *s = &w->secs[items[i].i];
            ScMesh *m = &s->layer[l];
            if (!m->quads) continue;
            double ex = p->x - s->ox, ey = p->y - s->oy, ez = p->z - s->oz;
            int vis[SF_COUNT];
            const double eps = 0.25;
            vis[SF_DOWN] = ey < m->plane[SF_DOWN] + eps;
            vis[SF_NORTH] = ez < m->plane[SF_NORTH] + eps;
            vis[SF_EAST] = ex > m->plane[SF_EAST] - eps;
            vis[SF_UP] = ey > m->plane[SF_UP] - eps;
            vis[SF_SOUTH] = ez > m->plane[SF_SOUTH] - eps;
            vis[SF_WEST] = ex < m->plane[SF_WEST] + eps;
            if (l == SL_TRANSLUCENT) for (int k = 0; k < SF_COUNT; k++) vis[k] = 1;
            int b = 0;
            while (b < SF_COUNT) {
                while (b < SF_COUNT && (!vis[b] || m->start[b] == m->start[b + 1])) b++;
                if (b >= SF_COUNT) break;
                int e = b;
                while (e + 1 < SF_COUNT && (vis[e + 1] || m->start[e + 1] == m->start[e + 2])) e++;
                for (uint32_t q = m->start[b]; q < m->start[e + 1]; q += 16383) {
                    uint32_t cnt = m->start[e + 1] - q < 16383 ? m->start[e + 1] - q : 16383;
                    if (total >= MAX_RECS) break;
                    uint32_t *a = rec + 5 * total++;
                    a[0] = 6 * cnt; a[1] = 1; a[2] = 0; a[3] = 4 * (s->heapQuad[l] + q); a[4] = (uint32_t)i;
                    f.quads += cnt;
                }
                b = e + 1;
            }
        }
        f.recs[l] = total - before;
    }
    /* uniforms */
    f.uboBase = (uint64_t)1024 * 2 * ring;
    uint8_t *u = (uint8_t *)r->ubo.contents + f.uboBase;
    memset(u, 0, 1024);
    memcpy(u, proj.m, 64);                                           /* Projection @ 0 */
    int bx = (int)floor(p->x), by = (int)floor(p->y), bz = (int)floor(p->z);
    int32_t *g = (int32_t *)(u + 256);                               /* Globals @ 256 */
    g[0] = bx; g[1] = by; g[2] = bz;
    ((float *)g)[4] = (float)(bx - p->x); ((float *)g)[5] = (float)(by - p->y); ((float *)g)[6] = (float)(bz - p->z);
    ((float *)g)[8] = (float)r->w; ((float *)g)[9] = (float)r->h;
    float *fog = (float *)(u + 512);                                 /* Fog @ 512 */
    fog[0] = 0.62f; fog[1] = 0.75f; fog[2] = 1.0f; fog[3] = 1.0f;
    fog[4] = 1e6f; fog[5] = 2e6f; fog[6] = SC_RD * 16 - 19.2f; fog[7] = SC_RD * 16; fog[8] = 1e6f; fog[9] = 1e6f;
    uint8_t *t = u + 768;                                            /* TerrainUniform @ 768 */
    memcpy(t, view.m, 64);
    ((int32_t *)(t + 64))[0] = 256; ((int32_t *)(t + 64))[1] = 256;
    /* a few "entities" (camera-relative cubes) in front of the camera, drawn after the opaque terrain */
    f.cubesOffset = (uint64_t)1024 * 2 * RING + 16 * 64 * ring;
    float *cubes = (float *)((uint8_t *)r->ubo.contents + f.cubesOffset);
    f.cubes = 16;
    for (int i = 0; i < 16; i++) {
        float a = (p->yaw + 180.0f + (i - 8) * 4.0f) * (float)M_PI / 180.0f;
        float d = 6.0f + (float)(i % 4) * 9.0f;
        cubes[4 * i] = -sinf(a) * d;
        cubes[4 * i + 1] = -1.5f + (float)(i % 3);
        cubes[4 * i + 2] = cosf(a) * d;
        cubes[4 * i + 3] = 0;
    }
    return f;
}

static void build_stream(Stream *s, Dev *d, Res *r, const Frame *f, BOOL readback) {
    s->length = 0;
    r_copy_buffer(s, r->staging, (uint64_t)16 * MAX_SECS * f->ring, r->inst, 0, 16ull * (f->sections ? f->sections : 1));
    const float sky[4] = { 0.62f, 0.75f, 1.0f, 1.0f };
    r_begin_pass(s, r->color, r->depth, sky);
    const int order[4] = { SL_SOLID, SL_CUTOUT, -1, SL_TRANSLUCENT };
    for (int k = 0; k < 4; k++) {
        const int l = order[k];
        if (l < 0) {
            r_set_pipeline(s, d->pipes[P_ENTITY]);
            r_set_buffer(s, 0, MCMTL_STAGE_VERTEX, r->ubo, f->uboBase);
            r_set_buffer(s, 5, MCMTL_STAGE_VERTEX, r->ubo, f->uboBase + 768);
            r_set_buffer(s, 6, MCMTL_STAGE_VERTEX, r->ubo, f->cubesOffset);
            r_draw(s, 36, f->cubes);
            continue;
        }
        r_set_pipeline(s, d->pipes[l]);
        r_set_buffer(s, 0, MCMTL_STAGE_VERTEX, r->ubo, f->uboBase);
        r_set_buffer(s, 1, MCMTL_STAGE_VERTEX, r->ubo, f->uboBase + 256);
        r_set_buffer(s, 2, MCMTL_STAGE_FRAGMENT, r->ubo, f->uboBase + 512);
        r_set_buffer(s, 5, MCMTL_STAGE_VERTEX | MCMTL_STAGE_FRAGMENT, r->ubo, f->uboBase + 768);
        r_set_texture(s, 0, MCMTL_STAGE_FRAGMENT, r->atlas, r->atlasSampler);
        r_set_texture(s, 2, MCMTL_STAGE_VERTEX, r->lightmap, r->lightSampler);
        if (f->recs[l]) {
            r_set_vb(s, 0, r->heap[l], 0);
            r_set_vb(s, 1, r->inst, 0);
            r_set_ib(s, MTLIndexTypeUInt16, r->idx16);
            r_draw_indirect(s, f->recs[l], r->args, f->argsOffset[l]);
        }
    }
    r_end_pass(s);
    if (readback) {
        r_readback(s, r->color, r->rbColor);
        r_readback(s, r->depth, r->rbDepth);
    }
}

/* ---------------------------------------------------------------------------------------------- */
/* Parity                                                                                           */

typedef struct Totals { uint64_t frames, diffFrames, diffPixels, culledSections, skippedQuads, plannedQuads; } Totals;

static uint8_t *gRefColor, *gRefDepth;
static Dev *gDry;              /* MCMTL_OCCLUSION=2 device: queries only, must never cull */
static uint64_t gDryDiff, gDryWould;

static void parity_frame(Dev *off, Dev *on, Res *r, ScWorld *w, const Pose *p, uint32_t frameNo, const char *phase, Totals *t, Totals *all) {
    static Stream s;
    Frame f = build_frame(r, w, p, frameNo % RING);
    build_stream(&s, off, r, &f, YES);
    dev_wait(off, dev_submit(off, &s));
    const size_t bytes = (size_t)r->w * r->h * 4;
    memcpy(gRefColor, r->rbColor.contents, bytes);
    memcpy(gRefDepth, r->rbDepth.contents, bytes);
    const mcmtl_decoder_stats before = *mcmtl_decoder_get_stats(on->dev);
    build_stream(&s, on, r, &f, YES);
    dev_wait(on, dev_submit(on, &s));
    const mcmtl_decoder_stats *after = mcmtl_decoder_get_stats(on->dev);
    const uint32_t *c0 = (const uint32_t *)gRefColor, *c1 = r->rbColor.contents;
    const uint32_t *d0 = (const uint32_t *)gRefDepth, *d1 = r->rbDepth.contents;
    uint64_t diff = 0;
    for (size_t i = 0; i < (size_t)r->w * r->h; i++) diff += (c0[i] != c1[i]) || (d0[i] != d1[i]);
    if (gDry) {
        const uint64_t would0 = mcmtl_decoder_get_stats(gDry->dev)->occlWouldCull;
        build_stream(&s, gDry, r, &f, YES);
        dev_wait(gDry, dev_submit(gDry, &s));
        gDryWould += mcmtl_decoder_get_stats(gDry->dev)->occlWouldCull - would0;
        CHECK(mcmtl_decoder_get_stats(gDry->dev)->occlCulledSections == 0, "dry run culled");
        for (size_t i = 0; i < (size_t)r->w * r->h; i++) gDryDiff += (c0[i] != c1[i]) || (d0[i] != d1[i]);
    }
    const uint64_t culled = after->occlCulledSections - before.occlCulledSections;
    t->frames++;
    t->diffFrames += diff != 0;
    t->diffPixels += diff;
    t->culledSections += culled;
    t->skippedQuads += after->occlSkippedQuads - before.occlSkippedQuads;
    t->plannedQuads += after->occlPlannedQuads - before.occlPlannedQuads;
    if (diff) {
        printf("  %s frame %u: %llu pixel(s) differ (pose %.2f %.2f %.2f yaw %.1f pitch %.1f fov %.1f; %llu sections culled)\n", phase,
               frameNo, (unsigned long long)diff, p->x, p->y, p->z, p->yaw, p->pitch, p->fov, (unsigned long long)culled);
    }
    all->frames++;
    all->diffFrames += diff != 0;
    all->diffPixels += diff;
    all->culledSections += culled;
    all->skippedQuads += after->occlSkippedQuads - before.occlSkippedQuads;
    all->plannedQuads += after->occlPlannedQuads - before.occlPlannedQuads;
}

static void report(const char *phase, const Totals *t) {
    printf("  %-44s %4llu frames: %llu differ (%llu px); culled %.1f sections/frame, %.1f%% of terrain quads skipped\n", phase,
           (unsigned long long)t->frames, (unsigned long long)t->diffFrames, (unsigned long long)t->diffPixels,
           t->frames ? (double)t->culledSections / t->frames : 0.0, t->plannedQuads ? 100.0 * t->skippedQuads / t->plannedQuads : 0.0);
}

/* Re-meshes every section touching block box [x0, x1] x [y0, y1] x [z0, z1] (plus neighbours) into fresh heap space. */
static void remesh_box(Res *r, ScWorld *w, int x0, int y0, int z0, int x1, int y1, int z1) {
    for (int sx = (x0 - 1 + SC_HALF) >> 4; sx <= (x1 + 1 + SC_HALF) >> 4; sx++)
        for (int sz = (z0 - 1 + SC_HALF) >> 4; sz <= (z1 + 1 + SC_HALF) >> 4; sz++)
            for (int sy = (y0 - 1) >> 4; sy <= (y1 + 1) >> 4; sy++) {
                if (sx < 0 || sz < 0 || sy < 0 || sx >= SC_NSEC_XZ || sz >= SC_NSEC_XZ || sy >= SC_NSECY) continue;
                ScSection *s = sc_section(w, sx, sy, sz);
                if (!s->inView) continue;
                sc_mesh_section(w, s);
                upload_section(r, s);
            }
}

static void run_parity(Dev *off, Dev *on, uint32_t width, uint32_t height) {
    printf("parity: %ux%u, ON reads query results 2 frames late\n", width, height);
    static ScWorld w;
    memset(&w, 0, sizeof w);
    sc_generate(&w, 1, 0.004f);
    sc_mesh_world(&w);
    Res r;
    res_init(&r, &w, width, height);
    gRefColor = malloc((size_t)width * height * 4);
    gRefDepth = malloc((size_t)width * height * 4);
    /* ground pose: lowest point within 24 blocks of the origin */
    int gx = 0, gz = 0, gh = 1 << 30;
    for (int x = -24; x <= 24; x++)
        for (int z = -24; z <= 24; z++)
            if (w.heights[x + SC_HALF][z + SC_HALF] < gh) { gh = w.heights[x + SC_HALF][z + SC_HALF]; gx = x; gz = z; }
    if (gh <= SC_SEA) gh = SC_SEA + 1;
    uint32_t frameNo = 0;
    Totals all = {0};
    Pose p = { gx + 0.5, gh + 1.62, gz + 0.5, 30.0f, 0.0f, 77.0f };

    Totals t = {0};
    for (int i = 0; i < 24; i++) parity_frame(off, on, &r, &w, &p, frameNo++, "ground static", &t, &all);
    report("ground, static", &t);
    CHECK(t.culledSections > 0, "occlusion never culled at the ground pose");

    memset(&t, 0, sizeof t);
    for (int i = 0; i < 120; i++) {
        p.yaw += 3.0f;
        p.pitch = 12.0f * sinf(i * 0.11f);
        parity_frame(off, on, &r, &w, &p, frameNo++, "ground turning 3 deg/frame", &t, &all);
    }
    report("ground, turning 3 deg/frame, pitch +-12", &t);

    memset(&t, 0, sizeof t);
    for (int i = 0; i < 48; i++) {
        p.yaw += (i & 8) ? -15.0f : 15.0f;
        p.pitch = 30.0f * sinf(i * 0.37f);
        parity_frame(off, on, &r, &w, &p, frameNo++, "ground fast turns", &t, &all);
    }
    report("ground, fast turns 15 deg/frame, pitch +-30", &t);

    memset(&t, 0, sizeof t);
    for (int i = 0; i < 16; i++) parity_frame(off, on, &r, &w, &p, frameNo++, "ground static 2", &t, &all);
    for (int i = 0; i < 10; i++) {
        p.fov = 77.0f - 5.0f * (float)i;
        parity_frame(off, on, &r, &w, &p, frameNo++, "zoom", &t, &all);
    }
    for (int i = 0; i < 16; i++) parity_frame(off, on, &r, &w, &p, frameNo++, "zoomed static", &t, &all);
    p.fov = 77.0f;
    report("ground, static / zoom in / zoomed", &t);

    /* Open a hole into the ground in front of the camera (reveals culled caves), then close it again. */
    memset(&t, 0, sizeof t);
    for (int i = 0; i < 12; i++) parity_frame(off, on, &r, &w, &p, frameNo++, "before dig", &t, &all);
    {
        float a = (p.yaw + 180.0f) * (float)M_PI / 180.0f;
        int cx = (int)floor(p.x - sinf(a) * 10), cz = (int)floor(p.z + cosf(a) * 10);
        int top = w.heights[cx + SC_HALF][cz + SC_HALF];
        for (int x = cx - 3; x <= cx + 3; x++)
            for (int z = cz - 3; z <= cz + 3; z++)
                for (int y = top - 40; y < top + 4; y++) sc_set(&w, x, y, z, SB_AIR);
        remesh_box(&r, &w, cx - 3, top - 40, cz - 3, cx + 3, top + 4, cz + 3);
        p.pitch = 40.0f;
        for (int i = 0; i < 24; i++) parity_frame(off, on, &r, &w, &p, frameNo++, "dug a pit", &t, &all);
        for (int x = cx - 3; x <= cx + 3; x++)
            for (int z = cz - 3; z <= cz + 3; z++)
                for (int y = top - 40; y < top; y++) sc_set(&w, x, y, z, SB_STONE);
        remesh_box(&r, &w, cx - 3, top - 40, cz - 3, cx + 3, top + 4, cz + 3);
        for (int i = 0; i < 24; i++) parity_frame(off, on, &r, &w, &p, frameNo++, "filled the pit", &t, &all);
    }
    report("mesh changes: pit dug into caves and refilled", &t);

    /* Translation: walking and flying (no culling while the eye moves; it must not break anything either). */
    memset(&t, 0, sizeof t);
    p.pitch = 5.0f;
    for (int i = 0; i < 40; i++) {
        p.x += 0.21 * sin(i * 0.1);
        p.z += 0.17;
        p.y += 0.05 * sin(i * 0.3);
        p.yaw += 1.0f;
        parity_frame(off, on, &r, &w, &p, frameNo++, "moving", &t, &all);
    }
    for (int i = 0; i < 20; i++) parity_frame(off, on, &r, &w, &p, frameNo++, "stopped", &t, &all);
    report("moving 0.2 blocks/frame, then stopped", &t);

    /* Benchmark-like: 40 blocks above the terrain, pitch 12, spinning. */
    memset(&t, 0, sizeof t);
    Pose b = { 0.5, w.heights[SC_HALF][SC_HALF] + 41.62, 0.5, 0.0f, 12.0f, 77.0f };
    for (int i = 0; i < 180; i++) {
        b.yaw += 2.0f;
        parity_frame(off, on, &r, &w, &b, frameNo++, "bench spin", &t, &all);
    }
    report("40 above the terrain, spinning 2 deg/frame", &t);
    memset(&t, 0, sizeof t);
    for (int i = 0; i < 24; i++) parity_frame(off, on, &r, &w, &b, frameNo++, "bench static", &t, &all);
    report("40 above the terrain, static", &t);
    memset(&t, 0, sizeof t);
    for (int i = 0; i < 120; i++) {
        b.yaw += 0.12f;
        parity_frame(off, on, &r, &w, &b, frameNo++, "bench slow spin", &t, &all);
    }
    report("40 above the terrain, spinning 0.12 deg/frame", &t);

    printf("  %-44s %4llu frames: %llu differ (%llu px); culled %.1f sections/frame, %.1f%% of terrain quads skipped\n", "ALL",
           (unsigned long long)all.frames, (unsigned long long)all.diffFrames, (unsigned long long)all.diffPixels,
           (double)all.culledSections / all.frames, all.plannedQuads ? 100.0 * all.skippedQuads / all.plannedQuads : 0.0);
    CHECK(all.diffFrames == 0, "%llu of %llu frames differ with occlusion culling", (unsigned long long)all.diffFrames,
          (unsigned long long)all.frames);
    if (gDry) {
        printf("  dry run (MCMTL_OCCLUSION=2): %llu pixel(s) differ, would have culled %.1f sections/frame\n", (unsigned long long)gDryDiff,
               (double)gDryWould / all.frames);
        CHECK(gDryDiff == 0 && gDryWould > 0, "dry run: %llu px differ, %llu would-cull", (unsigned long long)gDryDiff, (unsigned long long)gDryWould);
    }
    free(gRefColor);
    free(gRefDepth);
}

/* ---------------------------------------------------------------------------------------------- */
/* GPU throughput                                                                                   */

static double now_ms(void) {
    static mach_timebase_info_data_t tb;
    if (!tb.denom) mach_timebase_info(&tb);
    return (double)mach_absolute_time() * tb.numer / tb.denom / 1e6;
}

/* `frames` frames with 2 in flight; returns ms per frame (wall, GPU-bound) and the serialized GPU time of the last frames. */
static double run_block(Dev *d, Res *r, ScWorld *w, Pose *p, float yawStep, int frames, uint32_t *frameNo, double *gpuMs) {
    static Stream s;
    uint64_t values[2] = {0, 0};
    double t0 = 0;
    for (int i = 0; i < frames + 20; i++) {
        if (i == 20) {
            dev_wait(d, values[(i + 1) & 1]);
            t0 = now_ms();
        }
        dev_wait(d, values[i & 1]);   /* frame i - 2 */
        p->yaw += yawStep;
        Frame f = build_frame(r, w, p, (*frameNo)++ % RING);
        build_stream(&s, d, r, &f, NO);
        values[i & 1] = dev_submit(d, &s);
    }
    dev_wait(d, values[0]);
    dev_wait(d, values[1]);
    double ms = (now_ms() - t0) / frames;
    /* serialized: a few frames committed and waited for alone */
    double sum = 0;
    for (int i = 0; i < 12; i++) {
        p->yaw += yawStep;
        Frame f = build_frame(r, w, p, (*frameNo)++ % RING);
        build_stream(&s, d, r, &f, NO);
        dev_wait(d, dev_submit(d, &s));
        if (i >= 4) sum += mcmtl_status_load_u64(&d->dev->status->lastGpuTimeNs) / 1e6;
    }
    *gpuMs = sum / 8;
    return ms;
}

static int cmp_d(const void *a, const void *b) { double x = *(const double *)a, y = *(const double *)b; return x < y ? -1 : x > y; }

static void bench_view(Dev *off, Dev *on, Res *r, ScWorld *w, Pose start, float yawStep, const char *name, int frames, int rounds) {
    uint32_t frameNo = 0;
    double offMs[16], onMs[16], offGpu[16], onGpu[16];
    mcmtl_decoder_stats b0 = *mcmtl_decoder_get_stats(on->dev);
    for (int k = 0; k < rounds; k++) {
        Pose a = start, c = start;
        offMs[k] = run_block(off, r, w, &a, yawStep, frames, &frameNo, &offGpu[k]);
        onMs[k] = run_block(on, r, w, &c, yawStep, frames, &frameNo, &onGpu[k]);
    }
    const mcmtl_decoder_stats *b1 = mcmtl_decoder_get_stats(on->dev);
    qsort(offMs, rounds, sizeof(double), cmp_d);
    qsort(onMs, rounds, sizeof(double), cmp_d);
    qsort(offGpu, rounds, sizeof(double), cmp_d);
    qsort(onGpu, rounds, sizeof(double), cmp_d);
    const double pf = (double)(b1->occlFrames - b0.occlFrames);
    printf("  %-40s pipelined %.3f -> %.3f ms/frame (%+.3f); serialized GPU %.3f -> %.3f ms (%+.3f); per frame: %.0f sections, "
           "%.1f culled, %.0f of %.0f quads skipped (%.1f%%), %.0f box queries, planner %.3f ms\n",
           name, offMs[rounds / 2], onMs[rounds / 2], onMs[rounds / 2] - offMs[rounds / 2], offGpu[rounds / 2], onGpu[rounds / 2],
           onGpu[rounds / 2] - offGpu[rounds / 2], (b1->occlSections - b0.occlSections) / pf, (b1->occlCulledSections - b0.occlCulledSections) / pf,
           (b1->occlSkippedQuads - b0.occlSkippedQuads) / pf, (b1->occlPlannedQuads - b0.occlPlannedQuads) / pf,
           100.0 * (b1->occlSkippedQuads - b0.occlSkippedQuads) / (double)(b1->occlPlannedQuads - b0.occlPlannedQuads + 1),
           (b1->occlBoxTests - b0.occlBoxTests) / pf, (b1->occlPlanNs - b0.occlPlanNs) / pf / 1e6);
    fflush(stdout);
}

static void run_bench(Dev *off, Dev *on, int caves) {
    const char *fr = getenv("OCCLUSION_BENCH_FRAMES");
    int frames = fr ? atoi(fr) : 600;
    int rounds = 5;
    static ScWorld w;
    memset(&w, 0, sizeof w);
    sc_generate(&w, caves, 0.004f);
    sc_mesh_world(&w);
    Res r;
    res_init(&r, &w, 3600, 2260);
    int gx = 0, gz = 0, gh = 1 << 30;
    for (int x = -24; x <= 24; x++)
        for (int z = -24; z <= 24; z++)
            if (w.heights[x + SC_HALF][z + SC_HALF] < gh) { gh = w.heights[x + SC_HALF][z + SC_HALF]; gx = x; gz = z; }
    if (gh <= SC_SEA) gh = SC_SEA + 1;
    printf("throughput at 3600x2260, %d frames x %d rounds per view (OFF / ON interleaved), world %s caves:\n", frames, rounds,
           caves ? "with" : "without");
    Pose bench = { 0.5, w.heights[SC_HALF][SC_HALF] + 41.62, 0.5, 0.0f, 12.0f, 77.0f };
    bench_view(off, on, &r, &w, bench, 0.12f, "bench view, spinning 0.12 deg/frame", frames, rounds);
    Pose ground = { gx + 0.5, gh + 1.62, gz + 0.5, 30.0f, 0.0f, 77.0f };
    bench_view(off, on, &r, &w, ground, 0.0f, "ground view into hills, static", frames, rounds);
    bench_view(off, on, &r, &w, ground, 0.12f, "ground view into hills, spinning", frames, rounds);
}

int main(void) {
    setvbuf(stdout, NULL, _IOLBF, 0);
    @autoreleasepool {
        gMtl = MTLCreateSystemDefaultDevice();
        NSError *error = nil;
        MTLCompileOptions *options = [MTLCompileOptions new];
        options.languageVersion = MTLLanguageVersion3_2;
        gLib = [gMtl newLibraryWithSource:[NSString stringWithUTF8String:kScShaders] options:options error:&error];
        CHECK(gLib != nil, "scene shaders: %s", error.localizedDescription.UTF8String);
        if (!gLib) return 1;
        const BOOL bench = getenv("OCCLUSION_BENCH") && atoi(getenv("OCCLUSION_BENCH"));
        Dev off, on;
        CHECK(dev_init(&off, "occlusion OFF", !bench, 0, 0), "device OFF: %s", mcmtl_last_error());
        CHECK(dev_init(&on, "occlusion ON", !bench, 1, 2), "device ON: %s", mcmtl_last_error());
        if (failures) return 1;
        if (bench) {
            run_bench(&off, &on, 0);
            run_bench(&off, &on, 1);
        } else {
            Dev dry;
            CHECK(dev_init(&dry, "occlusion DRY", YES, 2, 2), "device DRY: %s", mcmtl_last_error());
            gDry = &dry;
            uint32_t w = 1280, h = 800;   /* OCCLUSION_TEST_SIZE=WxH (e.g. 3600x2260) */
            const char *size = getenv("OCCLUSION_TEST_SIZE");
            if (size && sscanf(size, "%ux%u", &w, &h) != 2) w = 1280, h = 800;
            run_parity(&off, &on, w, h);
            gDry = NULL;
            mcmtl_decoder_device_shutdown(dry.dev);
        }
        const mcmtl_decoder_stats *st = mcmtl_decoder_get_stats(on.dev);
        printf("ON device: %llu planned frames, %llu eye resets, %llu invalidated, result frames %llu used / %llu dropped, no plan: "
               "%llu %llu %llu %llu %llu %llu %llu\n",
               (unsigned long long)st->occlFrames, (unsigned long long)st->occlEyeResets, (unsigned long long)st->occlInvalidated,
               (unsigned long long)st->occlResultFrames, (unsigned long long)st->occlResultsDropped, (unsigned long long)st->occlNoPlan[0],
               (unsigned long long)st->occlNoPlan[1], (unsigned long long)st->occlNoPlan[2], (unsigned long long)st->occlNoPlan[3],
               (unsigned long long)st->occlNoPlan[4], (unsigned long long)st->occlNoPlan[5], (unsigned long long)st->occlNoPlan[6]);
        mcmtl_decoder_device_shutdown(off.dev);
        mcmtl_decoder_device_shutdown(on.dev);
    }
    printf("occlusion_test: %d checks, %d failures\n", checks, failures);
    printf(failures == 0 ? "OCCLUSION TEST PASSED\n" : "OCCLUSION TEST FAILED\n");
    return failures == 0 ? 0 : 1;
}
