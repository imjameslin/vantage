/*
 * decoder_test.m -- headless test of the I2 stream decoder (mcmetal_decoder.m) through the real mcmtl_submit
 * export (architect guard + decoder). OWNER: I2. Built and run by tools/build-native-tests.sh (MTL_DEBUG_LAYER=1:
 * any Metal validation error aborts the process, so a clean run proves the encoded work is valid).
 *
 * Independent of I1 (CONTRACT.md 11.2): the device context comes from mcmtl_device_context_create, resources are
 * created directly with Metal and passed as borrowed handles, pipelines are MCMTLPipeline objects filled by hand
 * with PSO variants compiled from test MSL, surfaces / query pools are wrapper objects built here. Streams are
 * hand-built with the mcmtl_cmd_* structs; results are read back with COPY_TEXTURE_TO_BUFFER records into
 * Shared buffers and checked pixel by pixel.
 *
 * M2 (clear folding, late store actions, pass merging with depth-clear draws, upload hoisting): every M2 case
 * runs under the M1 configuration and eight M2 flag combinations (mcmtl_decoder_test_set_m2) and each readback
 * must equal the M1 bytes exactly; decoder statistics pin down which optimization fired (or must not have).
 * The rest of the suite runs with the environment's flags (default: all M2 optimizations on).
 */
#import "mcmetal_internal.h"
#import "mcmetal_frametrace.h"
#import <QuartzCore/CAMetalLayer.h>
#include <objc/runtime.h>

#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

/* Decoder-private diagnostics (mirror of mcmtl_decoder_stats in mcmetal_decoder.m; structSize is checked). */
typedef struct mcmtl_decoder_stats {
    uint32_t structSize;
    uint32_t reserved;
    uint64_t commandBuffersCommitted;
    uint64_t untimedCommits;
    uint64_t renderEncoders;
    uint64_t blitEncoders;
    uint64_t blitSplits;
    uint64_t setBuffer;
    uint64_t setBufferOffset;
    uint64_t setBytes;
    uint64_t setTexture;
    uint64_t setSampler;
    uint64_t setPipelineState;
    uint64_t setDepthStencilState;
    uint64_t setRasterState;
    uint64_t setScissor;
    uint64_t dummyBindings;
    uint64_t clearQuads;
    uint64_t draws;
    uint64_t standaloneTimestamps;
    uint64_t frames;
    uint64_t passesIn;
    uint64_t passEncoders;
    uint64_t merges;
    uint64_t mergeBreaks;
    uint64_t hoists;
    uint64_t clearsRecorded;
    uint64_t clearsFolded;
    uint64_t clearsDropped;
    uint64_t clearsMaterialized;
    uint64_t clearDraws;
    uint64_t dontCareStores;
    uint64_t lateStores;
    uint64_t submitNs;
    uint64_t drawableWaitNs;
    uint64_t fusedFrames;
    uint64_t fusedPasses;
    uint64_t fusionWaitNs;
    uint64_t fusionRestores;
    uint64_t fusionStaleDropped;
    uint64_t fusionVariantBuilds;
    uint64_t fusionBackoffs;
    uint64_t fusionFallback[13];
    uint64_t presentRecords;
    uint64_t presentsEncoded;
    uint64_t presentsNil;
    uint64_t mailboxRecords;
    uint64_t presentsSkipped;
    uint64_t presentSkip[4];
    uint64_t indirectRecordDraws;
    uint64_t icbGroups;
    uint64_t icbRecords;
    uint64_t icbHits;
    uint64_t icbEncodedRecords;
    uint64_t icbCreated;
    uint64_t icbArgBuffers;
    uint64_t icbVariantBuilds;
    uint64_t icbFallback[9];
    uint64_t occlFrames;
    uint64_t occlSections;
    uint64_t occlCulledSections;
    uint64_t occlWouldCull;
    uint64_t occlSkippedRecords;
    uint64_t occlSkippedQuads;
    uint64_t occlPlannedQuads;
    uint64_t occlGeomQueries;
    uint64_t occlBoxTests;
    uint64_t occlEyeResets;
    uint64_t occlInvalidated;
    uint64_t occlResultFrames;
    uint64_t occlResultsDropped;
    uint64_t occlPlanNs;
    uint64_t occlNoPlan[8];
} mcmtl_decoder_stats;
const mcmtl_decoder_stats *mcmtl_decoder_get_stats(MCMTLDevice *dev);
void mcmtl_decoder_test_raise_at_record(MCMTLDevice *dev, int64_t recordOrdinal);
uint32_t mcmtl_decoder_test_set_m2(MCMTLDevice *dev, uint32_t flags);
uint32_t mcmtl_decoder_test_set_fusion(MCMTLDevice *dev, uint32_t mode, uint32_t testFlags, uint64_t maxWaitNs);
BOOL mcmtl_decoder_test_fusion_stale(MCMTLDevice *dev);
void mcmtl_decoder_test_on_render_pass(MCMTLDevice *dev, void (^block)(MTLRenderPassDescriptor *desc));
uint32_t mcmtl_decoder_test_set_icb(MCMTLDevice *dev, uint32_t mode, uint32_t testFlags, uint32_t minRecords);

/* Terrain ICB fallback reasons (mirror of DEC_ICB_R_* in mcmetal_decoder.m). */
enum { IR_VARIANT = 0, IR_FAILED, IR_FAN, IR_ARGS, IR_SLOT, IR_SMALL, IR_CACHE, IR_ALLOC, IR_OCCLUSION, IR_COUNT };
enum { ICB_TEST_SYNC_VARIANTS = 1u, ICB_TEST_RESET = 2u };

/* Present-fusion fallback reasons (mirror of DEC_FUSE_R_* in mcmetal_decoder.m). */
enum { FR_NOT_FLIPPED = 0, FR_DISABLED, FR_NO_INDEX, FR_MULTI_PRESENT, FR_FORMAT, FR_SIZE, FR_NO_CLEAR, FR_SOURCE_USE,
       FR_VARIANTS, FR_BACKOFF, FR_DRAWABLE_NIL, FR_NOT_REACHED, FR_NO_DRAWABLE, FR_COUNT };
enum { FUSE_TEST_SYNC_VARIANTS = 1u };

/* Mailbox presentation (CONTRACT.md 16.4): skip reasons (mirror of DEC_SKIP_*) and the surface test hooks
 * (mcmetal_surface.m, hidden symbols). */
enum { SK_NO_DRAWABLE = 0, SK_QUEUE_FULL, SK_STALE, SK_DEFERRED, SK_COUNT };
BOOL mcmtl_decoder_test_set_mailbox_late_present(MCMTLDevice *dev, BOOL on);
void mcmtl_mailbox_test_enable(MCMTLSurface *surface, BOOL enable, uint32_t maxQueued, uint64_t queueTimeoutNs,
                               id<CAMetalDrawable> (^acquire)(CAMetalLayer *layer));
void mcmtl_mailbox_test_on_none_ready(MCMTLSurface *surface, void (^block)(void));
BOOL mcmtl_mailbox_test_wait_ready(MCMTLSurface *surface, uint64_t timeoutNs);
void mcmtl_mailbox_test_add_queued(MCMTLSurface *surface, uint32_t n);
void mcmtl_mailbox_test_shutdown(MCMTLSurface *surface);

/* M2 flag bits (mirror of DEC_M2_* in mcmetal_decoder.m). */
enum { M2_FOLD = 1u, M2_LATE_STORE = 2u, M2_MERGE = 4u, M2_HOIST = 8u, M2_CLEAR_DRAW = 16u, M2_ZERO_CLEAR_VERTEX = 32u, M2_ALL = 63u };

static int failures = 0;
static int checks = 0;

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

/* ---------------------------------------------------------------------------------------------- */
/* Globals and small helpers                                                                        */
/* ---------------------------------------------------------------------------------------------- */

static MCMTLDevice *gDev;
static mcmtl_handle gDevHandle;
static id<MTLDevice> gMtl;
static id<MTLLibrary> gLib;
static uint64_t gNextValue = 1;

static inline mcmtl_handle H(id object) {
    return object ? (mcmtl_handle)(uintptr_t)(__bridge void *)object : 0;
}

/* While non-nil, every resource the helpers below create is also kept here (M2 cases record streams inside blocks
 * whose locals die before the submit decodes them). */
static NSMutableArray *gKeep;

static id keep(id object) {
    if (gKeep && object) [gKeep addObject:object];
    return object;
}

static const mcmtl_decoder_stats *stats(void) {
    return mcmtl_decoder_get_stats(gDev);
}

static id<MTLBuffer> new_buffer(NSUInteger length) {
    id<MTLBuffer> b = [gMtl newBufferWithLength:length options:MTLResourceStorageModeShared];
    memset(b.contents, 0, length);
    return keep(b);
}

static id<MTLBuffer> new_buffer_with(const void *bytes, NSUInteger length) {
    return keep([gMtl newBufferWithBytes:bytes length:length options:MTLResourceStorageModeShared]);
}

static id<MTLTexture> new_texture(MTLPixelFormat format, NSUInteger w, NSUInteger h, NSUInteger mips, MTLTextureUsage usage) {
    MTLTextureDescriptor *d = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:format width:w height:h mipmapped:NO];
    d.mipmapLevelCount = mips;
    d.usage = usage;
    d.storageMode = MTLStorageModePrivate;
    return keep([gMtl newTextureWithDescriptor:d]);
}

static id<MTLSamplerState> new_sampler(BOOL linear) {
    MTLSamplerDescriptor *d = [MTLSamplerDescriptor new];
    d.minFilter = d.magFilter = linear ? MTLSamplerMinMagFilterLinear : MTLSamplerMinMagFilterNearest;
    d.sAddressMode = d.tAddressMode = MTLSamplerAddressModeClampToEdge;
    d.supportArgumentBuffers = YES;   /* terrain ICB argument buffers (like mcmtl_sampler_create with MCMTL_TERRAIN_ICB=1) */
    return [gMtl newSamplerStateWithDescriptor:d];
}

/* ---------------------------------------------------------------------------------------------- */
/* Stream writer                                                                                    */
/* ---------------------------------------------------------------------------------------------- */

typedef struct Stream {
    uint8_t *data;
    size_t length;
    size_t capacity;
} Stream;

/* Appends a zeroed record of `bytes` (rounded up to 16) with op/size filled in; the pointer is valid until
 * the next append. */
static void *rec_add(Stream *s, uint16_t op, size_t bytes) {
    size_t size = (bytes + 15u) & ~(size_t)15u;
    if (s->length + size > s->capacity) {
        size_t cap = s->capacity ? s->capacity * 2 : 4096;
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

static void s_reset(Stream *s) { s->length = 0; }
static void s_free(Stream *s) { free(s->data); s->data = NULL; s->length = s->capacity = 0; }

typedef struct PassDesc {
    uint32_t count;
    __unsafe_unretained id<MTLTexture> colors[8];
    uint32_t levels[8];
    uint32_t clearMask;
    float clear[8][4];
    __unsafe_unretained id<MTLTexture> depth;
    uint32_t depthLevel;
    BOOL depthClear;
    float depthValue;
    BOOL hasArea;
    int32_t area[4];
} PassDesc;

static uint64_t r_begin_pass(Stream *s, const PassDesc *p) {
    uint64_t offset = s->length;
    uint8_t *rec = rec_add(s, MCMTL_OP_BEGIN_PASS, MCMTL_CMD_SIZE_BEGIN_PASS_BASE + MCMTL_CMD_SIZE_COLOR_ATTACHMENT * p->count);
    mcmtl_cmd_begin_pass *c = (mcmtl_cmd_begin_pass *)rec;
    c->colorCount = p->count;
    c->flags = p->depthClear ? MCMTL_PASS_FLAG_DEPTH_CLEAR : 0;
    c->colorClearMask = p->clearMask;
    id<MTLTexture> ref = p->count ? p->colors[0] : p->depth;
    uint32_t level = p->count ? p->levels[0] : p->depthLevel;
    if (p->hasArea) {
        c->renderX = p->area[0];
        c->renderY = p->area[1];
        c->renderWidth = p->area[2];
        c->renderHeight = p->area[3];
    } else {
        c->renderWidth = (int32_t)MAX(1u, (uint32_t)ref.width >> level);
        c->renderHeight = (int32_t)MAX(1u, (uint32_t)ref.height >> level);
    }
    c->depthClearValue = p->depthValue;
    c->depthLevel = p->depthLevel;
    c->depthTexture = H(p->depth);
    mcmtl_cmd_color_attachment *a = (mcmtl_cmd_color_attachment *)(rec + MCMTL_CMD_SIZE_BEGIN_PASS_BASE);
    for (uint32_t i = 0; i < p->count; i++) {
        a[i].texture = H(p->colors[i]);
        a[i].level = p->levels[i];
        memcpy(a[i].clearColor, p->clear[i], sizeof a[i].clearColor);
    }
    return offset;
}

static PassDesc pass1(id<MTLTexture> color, id<MTLTexture> depth) {
    PassDesc p;
    memset(&p, 0, sizeof p);
    p.count = color ? 1 : 0;
    p.colors[0] = color;
    p.depth = depth;
    return p;
}

static void r_end_pass(Stream *s) { rec_add(s, MCMTL_OP_END_PASS, sizeof(mcmtl_cmd_end_pass)); }
static void r_nop(Stream *s) { rec_add(s, MCMTL_OP_NOP, 16); }

static void r_clear_texture(Stream *s, id<MTLTexture> t, uint32_t aspects, uint32_t base, uint32_t count, const float color[4], float depth) {
    mcmtl_cmd_clear_texture *c = rec_add(s, MCMTL_OP_CLEAR_TEXTURE, sizeof *c);
    c->aspects = aspects;
    c->texture = H(t);
    c->baseLevel = base;
    c->levelCount = count;
    c->depth = depth;
    if (color) memcpy(c->color, color, sizeof c->color);
}

static void r_clear_region(Stream *s, uint32_t level, id<MTLTexture> color, id<MTLTexture> depth, int32_t x, int32_t y,
                           int32_t w, int32_t h, const float rgba[4], float d) {
    mcmtl_cmd_clear_region *c = rec_add(s, MCMTL_OP_CLEAR_REGION, sizeof *c);
    c->level = level;
    c->colorTexture = H(color);
    c->depthTexture = H(depth);
    c->x = x; c->y = y; c->width = w; c->height = h;
    if (rgba) memcpy(c->color, rgba, sizeof c->color);
    c->depth = d;
}

static void r_copy_buffer(Stream *s, id<MTLBuffer> src, uint64_t so, id<MTLBuffer> dst, uint64_t dof, uint64_t len) {
    mcmtl_cmd_copy_buffer *c = rec_add(s, MCMTL_OP_COPY_BUFFER, sizeof *c);
    c->srcBuffer = H(src); c->srcOffset = so; c->dstBuffer = H(dst); c->dstOffset = dof; c->length = len;
}

static void r_buffer_texture(Stream *s, uint16_t op, id<MTLBuffer> b, uint64_t off, uint32_t bpr, id<MTLTexture> t,
                             uint32_t level, uint32_t slice, uint32_t x, uint32_t y, uint32_t w, uint32_t h) {
    mcmtl_cmd_copy_buffer_texture *c = rec_add(s, op, sizeof *c);
    c->level = level; c->buffer = H(b); c->bufferOffset = off; c->texture = H(t);
    c->bytesPerRow = bpr; c->bytesPerImage = bpr * h; c->slice = slice;
    c->x = x; c->y = y; c->width = w; c->height = h;
}

/* Readback of a whole level into a fresh Shared buffer (rows packed at bpp * width). */
static id<MTLBuffer> r_readback(Stream *s, id<MTLTexture> t, uint32_t level, uint32_t bpp) {
    uint32_t w = MAX(1u, (uint32_t)t.width >> level), h = MAX(1u, (uint32_t)t.height >> level);
    id<MTLBuffer> b = new_buffer((NSUInteger)w * h * bpp);
    memset(b.contents, 0xCD, b.length);
    r_buffer_texture(s, MCMTL_OP_COPY_TEXTURE_TO_BUFFER, b, 0, w * bpp, t, level, 0, 0, 0, w, h);
    return b;
}

static void r_copy_texture(Stream *s, uint32_t flags, id<MTLTexture> src, id<MTLTexture> dst, uint32_t level, uint32_t sx,
                           uint32_t sy, uint32_t dx, uint32_t dy, uint32_t w, uint32_t h) {
    mcmtl_cmd_copy_texture *c = rec_add(s, MCMTL_OP_COPY_TEXTURE, sizeof *c);
    c->flags = flags; c->srcTexture = H(src); c->dstTexture = H(dst); c->srcLevel = level; c->dstLevel = level;
    c->srcX = sx; c->srcY = sy; c->dstX = dx; c->dstY = dy; c->width = w; c->height = h;
}

static void r_init_texture(Stream *s, id<MTLTexture> t) {
    mcmtl_cmd_init_texture *c = rec_add(s, MCMTL_OP_INIT_TEXTURE, sizeof *c);
    c->mode = MCMTL_INIT_TEXTURE_ZERO;
    c->texture = H(t);
}

static void r_present(Stream *s, MCMTLSurface *surface, id<MTLTexture> t, uint32_t level) {
    mcmtl_cmd_present *c = rec_add(s, MCMTL_OP_PRESENT, sizeof *c);
    c->surface = H(surface); c->texture = H(t); c->level = level;
}

static void r_timestamp(Stream *s, MCMTLQueryPool *pool, uint32_t index) {
    mcmtl_cmd_timestamp *c = rec_add(s, MCMTL_OP_TIMESTAMP, sizeof *c);
    c->index = index;
    c->queryPool = H(pool);
}

static void r_push_group(Stream *s, const char *label) {
    uint32_t len = (uint32_t)strlen(label);
    uint8_t *rec = rec_add(s, MCMTL_OP_PUSH_DEBUG_GROUP, sizeof(mcmtl_cmd_push_debug_group) + len);
    ((mcmtl_cmd_push_debug_group *)rec)->labelLength = len;
    memcpy(rec + sizeof(mcmtl_cmd_push_debug_group), label, len);
}

static void r_pop_group(Stream *s) { rec_add(s, MCMTL_OP_POP_DEBUG_GROUP, 16); }

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

static void r_clear_binding(Stream *s, uint32_t index, uint32_t stages, uint32_t kind) {
    mcmtl_cmd_clear_binding *c = rec_add(s, MCMTL_OP_CLEAR_BINDING, sizeof *c);
    c->index = index; c->stages = stages; c->kind = kind;
}

static void r_set_vb(Stream *s, uint32_t slot, id<MTLBuffer> b, uint64_t offset) {
    mcmtl_cmd_set_vertex_buffer *c = rec_add(s, MCMTL_OP_SET_VERTEX_BUFFER, sizeof *c);
    c->slot = slot; c->buffer = H(b); c->offset = offset;
}

static void r_set_ib(Stream *s, MTLIndexType type, id<MTLBuffer> b) {
    mcmtl_cmd_set_index_buffer *c = rec_add(s, MCMTL_OP_SET_INDEX_BUFFER, sizeof *c);
    c->indexType = (uint32_t)type; c->buffer = H(b);
}

static void r_scissor(Stream *s, int32_t x, int32_t y, int32_t w, int32_t h) {
    mcmtl_cmd_set_scissor *c = rec_add(s, MCMTL_OP_SET_SCISSOR, sizeof *c);
    c->x = x; c->y = y; c->width = w; c->height = h;
}

static void r_reset_scissor(Stream *s) { rec_add(s, MCMTL_OP_RESET_SCISSOR, 16); }

static void r_push_constants(Stream *s, uint32_t stages, const void *data, uint32_t length) {
    uint8_t *rec = rec_add(s, MCMTL_OP_PUSH_CONSTANTS, sizeof(mcmtl_cmd_push_constants) + length);
    ((mcmtl_cmd_push_constants *)rec)->byteLength = length;
    ((mcmtl_cmd_push_constants *)rec)->stages = stages;
    memcpy(rec + sizeof(mcmtl_cmd_push_constants), data, length);
}

static void r_draw(Stream *s, uint32_t vertexCount, uint32_t instanceCount, uint32_t firstVertex, uint32_t firstInstance) {
    mcmtl_cmd_draw *c = rec_add(s, MCMTL_OP_DRAW, sizeof *c);
    c->vertexCount = vertexCount; c->instanceCount = instanceCount; c->firstVertex = firstVertex; c->firstInstance = firstInstance;
}

static void r_draw_indexed(Stream *s, uint32_t indexCount, uint32_t instanceCount, uint32_t firstIndex, int32_t baseVertex,
                           uint32_t firstInstance) {
    mcmtl_cmd_draw_indexed *c = rec_add(s, MCMTL_OP_DRAW_INDEXED, sizeof *c);
    c->indexCount = indexCount; c->instanceCount = instanceCount; c->firstIndex = firstIndex; c->baseVertex = baseVertex;
    c->firstInstance = firstInstance;
}

static void r_draw_indirect(Stream *s, uint16_t op, uint32_t count, id<MTLBuffer> b, uint64_t offset) {
    mcmtl_cmd_draw_indirect *c = rec_add(s, op, sizeof *c);
    c->drawCount = count; c->buffer = H(b); c->offset = offset;
}

/* ---------------------------------------------------------------------------------------------- */
/* Submit                                                                                           */
/* ---------------------------------------------------------------------------------------------- */

static BOOL wait_prefix(uint64_t value) {
    for (int i = 0; i < 50000; i++) {
        if (mcmtl_status_load_u64(&gDev->status->completedValue) >= value) return YES;
        usleep(100);
    }
    return NO;
}

/* mcmtl_submit through the real export. COMMIT uses the next dense value and waits for its completion. */
static int32_t submit_ex(Stream *init, Stream *main, uint32_t flags, mcmtl_submit_result *out, const char *label) {
    mcmtl_submit_info info;
    memset(&info, 0, sizeof info);
    info.structSize = sizeof info;
    info.flags = flags;
    info.initStream = init ? init->data : NULL;
    info.initStreamLength = init ? init->length : 0;
    info.stream = main ? main->data : NULL;
    info.streamLength = main ? main->length : 0;
    info.label = label;
    uint64_t value = 0;
    if (flags & MCMTL_SUBMIT_FLAG_COMMIT) info.signalValue = value = gNextValue++;
    mcmtl_submit_result result;
    memset(&result, 0, sizeof result);
    result.structSize = sizeof result;
    int32_t rc = mcmtl_submit(gDevHandle, &info, &result);
    if (value) {
        CHECK(mcmtl_status_load_u64(&gDev->status->submittedValue) >= value, "value %llu not consumed (rc %d)",
              (unsigned long long)value, rc);
        int32_t w = mcmtl_wait_completed(gDev, value, 5000000000ull);
        CHECK(w == MCMTL_OK, "wait(%llu) -> %d %s", (unsigned long long)value, w, mcmtl_last_error());
        CHECK(wait_prefix(value), "prefix did not reach %llu", (unsigned long long)value);
        CHECK(gDev->currentCommandBuffer == nil, "COMMIT left a command buffer open");
    }
    if (out) *out = result;
    return rc;
}

static int32_t submit(Stream *main, mcmtl_submit_result *out) {
    return submit_ex(NULL, main, MCMTL_SUBMIT_FLAG_COMMIT | MCMTL_SUBMIT_FLAG_FRAME_END, out, NULL);
}

/* Submits `s`, expects MCMTL_OK + COMMITTED, then resets the stream. */
static void submit_ok(Stream *s, const char *what) {
    mcmtl_submit_result res;
    int32_t rc = submit(s, &res);
    CHECK(rc == MCMTL_OK, "%s: submit rc %d (%s)", what, rc, mcmtl_last_error());
    CHECK(res.flags & MCMTL_SUBMIT_RESULT_FLAG_COMMITTED, "%s: not COMMITTED (flags 0x%x)", what, res.flags);
    s_reset(s);
}

/* ---------------------------------------------------------------------------------------------- */
/* Pixel checks                                                                                     */
/* ---------------------------------------------------------------------------------------------- */

static const uint8_t *px(id<MTLBuffer> b, uint32_t width, uint32_t x, uint32_t y) {
    return (const uint8_t *)b.contents + ((size_t)y * width + x) * 4;
}

static BOOL near8(const uint8_t *p, int r, int g, int b, int a) {
    return abs(p[0] - r) <= 2 && abs(p[1] - g) <= 2 && abs(p[2] - b) <= 2 && abs(p[3] - a) <= 2;
}

/* Every pixel of an RGBA8 readback inside rect [x0,x1)x[y0,y1) equals rgba (others unchecked). */
static int count_rect_mismatch(id<MTLBuffer> b, uint32_t width, uint32_t x0, uint32_t y0, uint32_t x1, uint32_t y1,
                               int r, int g, int bl, int a) {
    int bad = 0;
    for (uint32_t y = y0; y < y1; y++)
        for (uint32_t x = x0; x < x1; x++)
            if (!near8(px(b, width, x, y), r, g, bl, a)) bad++;
    return bad;
}

#define CHECK_PX(buf, w, x, y, r, g, b, a, what)                                                               \
    do {                                                                                                       \
        const uint8_t *p_ = px(buf, w, x, y);                                                                  \
        CHECK(near8(p_, r, g, b, a), "%s: pixel (%u,%u) = (%u,%u,%u,%u), expected (%d,%d,%d,%d)", what,         \
              (unsigned)(x), (unsigned)(y), p_[0], p_[1], p_[2], p_[3], r, g, b, a);                            \
    } while (0)

#define CHECK_RECT(buf, w, x0, y0, x1, y1, r, g, b, a, what)                                                   \
    do {                                                                                                       \
        int bad_ = count_rect_mismatch(buf, w, x0, y0, x1, y1, r, g, b, a);                                    \
        if (bad_) {                                                                                            \
            const uint8_t *p_ = px(buf, w, x0, y0);                                                            \
            CHECK(bad_ == 0, "%s: %d pixel(s) in [%u,%u)x[%u,%u) differ from (%d,%d,%d,%d); (%u,%u) = (%u,%u,%u,%u)", \
                  what, bad_, (unsigned)(x0), (unsigned)(x1), (unsigned)(y0), (unsigned)(y1), r, g, b, a,       \
                  (unsigned)(x0), (unsigned)(y0), p_[0], p_[1], p_[2], p_[3]);                                  \
        } else {                                                                                               \
            checks++;                                                                                          \
        }                                                                                                      \
    } while (0)

static float fpx(id<MTLBuffer> b, uint32_t width, uint32_t x, uint32_t y) {
    return ((const float *)b.contents)[(size_t)y * width + x];
}

/* ---------------------------------------------------------------------------------------------- */
/* Test pipelines                                                                                   */
/* ---------------------------------------------------------------------------------------------- */

static NSString *const kTestShaders =
    @"#include <metal_stdlib>\n"
     "using namespace metal;\n"
     "struct VOut { float4 pos [[position]]; float2 uv; float4 color; };\n"
     "struct TexIn { float2 pos [[attribute(0)]]; float2 uv [[attribute(1)]]; };\n"
     "vertex VOut tex_vs(TexIn in [[stage_in]]) { VOut o; o.pos = float4(in.pos, 0.0, 1.0); o.uv = in.uv; o.color = float4(0.0); return o; }\n"
     "fragment float4 tex_fs(VOut in [[stage_in]], texture2d<float> t [[texture(0)]], sampler s [[sampler(0)]],\n"
     "                       constant float4 &tint [[buffer(1)]]) { return t.sample(s, in.uv) * tint; }\n"
     "struct PosIn { float2 pos [[attribute(0)]]; };\n"
     "vertex VOut pos_vs(PosIn in [[stage_in]]) { VOut o; o.pos = float4(in.pos, 0.0, 1.0); o.uv = float2(0.0); o.color = float4(0.0); return o; }\n"
     "fragment float4 ubo_fs(constant float4 &c [[buffer(2)]]) { return c; }\n"
     "struct DepthIn { float3 pos [[attribute(0)]]; };\n"
     "vertex VOut depth_vs(DepthIn in [[stage_in]]) { VOut o; o.pos = float4(in.pos, 1.0); o.uv = float2(0.0); o.color = float4(0.0); return o; }\n"
     "struct InstIn { float2 pos [[attribute(0)]]; float2 offset [[attribute(1)]]; float4 color [[attribute(2)]]; };\n"
     "vertex VOut inst_vs(InstIn in [[stage_in]]) { VOut o; o.pos = float4(in.pos + in.offset, 0.0, 1.0); o.uv = float2(0.0); o.color = in.color; return o; }\n"
     "fragment float4 color_fs(VOut in [[stage_in]]) { return in.color; }\n"
     "fragment float4 dummy_fs(VOut in [[stage_in]], constant float4 &u [[buffer(3)]],\n"
     "                         texture2d<float> t [[texture(1)]], sampler s [[sampler(1)]],\n"
     "                         texturecube<float> c [[texture(2)]], sampler cs [[sampler(2)]],\n"
     "                         texture_buffer<int> tb [[texture(3)]]) {\n"
     "    return u + t.sample(s, float2(0.5)) + c.sample(cs, float3(1.0, 0.0, 0.0)) + float4(float(tb.read(0u).x))\n"
     "           + float4(0.25, 0.25, 0.25, 1.0);\n"
     "}\n"
     "fragment float4 push_fs(constant float4 &pc [[buffer(14)]]) { return pc; }\n";

typedef struct AttrSpec { uint32_t location, format, offset, slot; } AttrSpec;
typedef struct LayoutSpec { uint32_t slot, stride, perInstance; } LayoutSpec;

typedef struct PipeSpec {
    const char *name;
    const char *vs, *fs;
    uint32_t colorCount;
    uint32_t colorFormats[8];
    uint32_t attrCount;
    AttrSpec attrs[8];
    uint32_t layoutCount;
    LayoutSpec layouts[2];
    uint32_t compare;          /* MTLCompareFunction; 0 (Never) means "no depth state" -> Always */
    BOOL depthWrite;
    BOOL fan;
    uint32_t bufferMask[2], textureMask[2], samplerMask[2], cubeMask[2], texelMask[2];
    uint32_t pushSize, pushStages;
} PipeSpec;

static MCMTLPipeline *make_pipeline_with(const PipeSpec *spec, id<MTLFunction> vs, id<MTLFunction> fs) {
    MCMTLPipeline *p = [MCMTLPipeline new];
    p->owner = gDev;
    MTLRenderPipelineDescriptor *d = [MTLRenderPipelineDescriptor new];
    d.label = [NSString stringWithUTF8String:spec->name];
    d.vertexFunction = vs;
    d.fragmentFunction = fs;
    MTLVertexDescriptor *vd = [MTLVertexDescriptor vertexDescriptor];
    for (uint32_t i = 0; i < spec->attrCount; i++) {
        vd.attributes[spec->attrs[i].location].format = (MTLVertexFormat)spec->attrs[i].format;
        vd.attributes[spec->attrs[i].location].offset = spec->attrs[i].offset;
        vd.attributes[spec->attrs[i].location].bufferIndex = 30 - spec->attrs[i].slot;
    }
    uint32_t required = 0;
    for (uint32_t i = 0; i < spec->layoutCount; i++) {
        uint32_t index = 30 - spec->layouts[i].slot;
        vd.layouts[index].stride = spec->layouts[i].stride;
        vd.layouts[index].stepFunction = spec->layouts[i].perInstance ? MTLVertexStepFunctionPerInstance : MTLVertexStepFunctionPerVertex;
        vd.layouts[index].stepRate = 1;
        required |= 1u << index;
    }
    d.vertexDescriptor = vd;
    p->descriptorTemplate = d;
    p->label = d.label;
    p->colorTargetCount = spec->colorCount;
    for (uint32_t i = 0; i < spec->colorCount; i++) p->colorTargetFormats[i] = spec->colorFormats[i];
    p->depthStencilState = mcmtl_device_depth_stencil_state(gDev, spec->compare ? spec->compare : MTLCompareFunctionAlways,
                                                             spec->compare ? spec->depthWrite : NO);
    p->primitiveType = MTLPrimitiveTypeTriangle;
    p->emulation = spec->fan ? MCMTL_EMULATION_TRIANGLE_FAN : MCMTL_EMULATION_NONE;
    p->cullMode = MTLCullModeNone;
    p->fillMode = MTLTriangleFillModeFill;
    p->frontFacing = MTLWindingClockwise;
    p->pushConstantSize = spec->pushSize;
    p->pushConstantStages = spec->pushStages;
    for (int s = 0; s < 2; s++) {
        p->bufferMask[s] = spec->bufferMask[s];
        p->textureMask[s] = spec->textureMask[s];
        p->samplerMask[s] = spec->samplerMask[s];
        p->textureCubeMask[s] = spec->cubeMask[s];
        p->texelBufferMask[s] = spec->texelMask[s];
    }
    p->bufferMask[0] |= required;
    p->requiredVertexBufferMask = required;
    return p;
}

static MCMTLPipeline *make_pipeline(const PipeSpec *spec) {
    return make_pipeline_with(spec, [gLib newFunctionWithName:[NSString stringWithUTF8String:spec->vs]],
                              [gLib newFunctionWithName:[NSString stringWithUTF8String:spec->fs]]);
}

/* Builds and inserts the PSO variant for (color presence mask, depth format) like I1's builder would. */
static void add_variant(MCMTLPipeline *p, uint32_t mask, uint32_t depthFormat) {
    MTLRenderPipelineDescriptor *d = [p->descriptorTemplate copy];
    for (uint32_t i = 0; i < 8; i++) {
        d.colorAttachments[i].pixelFormat = (mask & (1u << i)) ? (MTLPixelFormat)p->colorTargetFormats[i] : MTLPixelFormatInvalid;
    }
    d.depthAttachmentPixelFormat = (MTLPixelFormat)depthFormat;
    d.stencilAttachmentPixelFormat = depthFormat == MTLPixelFormatDepth32Float_Stencil8 ? MTLPixelFormatDepth32Float_Stencil8 : MTLPixelFormatInvalid;
    NSError *error = nil;
    id<MTLRenderPipelineState> pso = [gMtl newRenderPipelineStateWithDescriptor:d error:&error];
    CHECK(pso != nil, "test PSO %s: %s", p->label.UTF8String, error.localizedDescription.UTF8String);
    mcmtl_pipeline_add_variant(p, mcmtl_variant_key(mask, depthFormat), pso);
}

/* Full-screen-ish quads in pixel space of a 16x16 target: NDC x = px / 8 - 1, NDC y = 1 - py / 8 (Metal: NDC +y is
 * row 0; the test shaders do not flip). Writes 6 float2 positions. */
static void quad_px(float *out, float x0, float y0, float x1, float y1, float size) {
    float h = size / 2.0f;
    float ax = x0 / h - 1.0f, bx = x1 / h - 1.0f, ay = 1.0f - y0 / h, by = 1.0f - y1 / h;
    float v[12] = { ax, ay, bx, ay, bx, by, bx, by, ax, by, ax, ay };
    memcpy(out, v, sizeof v);
}

/* ---------------------------------------------------------------------------------------------- */
/* Tests                                                                                            */
/* ---------------------------------------------------------------------------------------------- */

static const MTLTextureUsage kRT = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
static MCMTLPipeline *gSolid;        /* pos_vs + ubo_fs (buffer 2), RGBA8, variants: no depth, D32 */

static MCMTLPipeline *solid_pipeline(void) {
    PipeSpec spec = { .name = "solid", .vs = "pos_vs", .fs = "ubo_fs", .colorCount = 1, .colorFormats = { MTLPixelFormatRGBA8Unorm },
                      .attrCount = 1, .attrs = { { 0, MTLVertexFormatFloat2, 0, 0 } }, .layoutCount = 1, .layouts = { { 0, 8, 0 } },
                      .bufferMask = { 0, 1u << 2 } };
    MCMTLPipeline *p = make_pipeline(&spec);
    add_variant(p, 1, 0);
    add_variant(p, 1, MTLPixelFormatDepth32Float);
    return p;
}

/* Colors in a Shared UBO buffer: index i at offset 256 * i. */
static id<MTLBuffer> color_ubo(const float (*colors)[4], uint32_t count) {
    id<MTLBuffer> b = new_buffer(256u * count);
    for (uint32_t i = 0; i < count; i++) memcpy((uint8_t *)b.contents + 256u * i, colors[i], 16);
    return b;
}

static void test_clears(void) {
    Stream s = {0};
    const float red[4] = {1, 0, 0, 1}, green[4] = {0, 1, 0, 1}, blue[4] = {0, 0, 1, 1};
    id<MTLTexture> color = new_texture(MTLPixelFormatRGBA8Unorm, 8, 8, 2, kRT);
    id<MTLTexture> depth = new_texture(MTLPixelFormatDepth32Float, 8, 8, 1, kRT);
    r_clear_texture(&s, color, MCMTL_ASPECT_COLOR, 0, 2, red, 0);
    r_clear_texture(&s, depth, MCMTL_ASPECT_DEPTH, 0, 1, NULL, 0.25f);
    id<MTLBuffer> c0 = r_readback(&s, color, 0, 4), c1 = r_readback(&s, color, 1, 4), d0 = r_readback(&s, depth, 0, 4);
    submit_ok(&s, "clear texture");
    CHECK_RECT(c0, 8, 0, 0, 8, 8, 255, 0, 0, 255, "CLEAR_TEXTURE level 0");
    CHECK_RECT(c1, 4, 0, 0, 4, 4, 255, 0, 0, 255, "CLEAR_TEXTURE level 1");
    CHECK(fpx(d0, 8, 3, 3) == 0.25f && fpx(d0, 8, 7, 0) == 0.25f, "CLEAR_TEXTURE depth = %f", fpx(d0, 8, 3, 3));

    /* Partial region clear: scissored clear quad on color + depth. */
    uint64_t quadsBefore = stats()->clearQuads;
    r_clear_region(&s, 0, color, depth, 2, 3, 4, 2, green, 0.75f);
    c0 = r_readback(&s, color, 0, 4);
    d0 = r_readback(&s, depth, 0, 4);
    submit_ok(&s, "clear region");
    CHECK(stats()->clearQuads == quadsBefore + 1, "partial CLEAR_REGION must draw one clear quad");
    for (uint32_t y = 0; y < 8; y++) {
        for (uint32_t x = 0; x < 8; x++) {
            BOOL inside = x >= 2 && x < 6 && y >= 3 && y < 5;
            const uint8_t *p = px(c0, 8, x, y);
            if (!(inside ? near8(p, 0, 255, 0, 255) : near8(p, 255, 0, 0, 255)) || fpx(d0, 8, x, y) != (inside ? 0.75f : 0.25f)) {
                CHECK(0, "CLEAR_REGION pixel (%u,%u) = (%u,%u,%u,%u) depth %f (inside %d)", x, y, p[0], p[1], p[2], p[3],
                      fpx(d0, 8, x, y), inside);
                y = 8;
                break;
            }
        }
    }
    /* Full-level region clear (level 1, color only) -> load action clear. */
    r_clear_region(&s, 1, color, nil, 0, 0, 4, 4, blue, 0);
    c1 = r_readback(&s, color, 1, 4);
    submit_ok(&s, "full clear region");
    CHECK(stats()->clearQuads == quadsBefore + 1, "full CLEAR_REGION must not draw a clear quad");
    CHECK_RECT(c1, 4, 0, 0, 4, 4, 0, 0, 255, 255, "full CLEAR_REGION level 1");
    s_free(&s);
    printf("clears: ok\n");
}

static void test_pass_clears_and_render_area(void) {
    Stream s = {0};
    id<MTLTexture> color = new_texture(MTLPixelFormatRGBA8Unorm, 16, 16, 1, kRT);
    id<MTLTexture> depth = new_texture(MTLPixelFormatDepth32Float, 16, 16, 1, kRT);
    const float white[1][4] = { {1, 1, 1, 1} };
    id<MTLBuffer> ubo = color_ubo(white, 1);
    float verts[12];
    quad_px(verts, 0, 0, 16, 16, 16);
    id<MTLBuffer> vb = new_buffer_with(verts, sizeof verts);

    /* Full-area clears. */
    PassDesc p = pass1(color, depth);
    p.clearMask = 1;
    memcpy(p.clear[0], (float[4]){0, 0, 1, 1}, 16);
    p.depthClear = YES;
    p.depthValue = 1.0f;
    r_begin_pass(&s, &p);
    r_end_pass(&s);
    /* Partial area (4,4,8,8): clear quad inside, the rest is loaded. */
    p = pass1(color, depth);
    p.clearMask = 1;
    memcpy(p.clear[0], (float[4]){1, 1, 0, 1}, 16);
    p.depthClear = YES;
    p.depthValue = 0.5f;
    p.hasArea = YES;
    memcpy(p.area, (int32_t[4]){4, 4, 8, 8}, 16);
    r_begin_pass(&s, &p);
    r_end_pass(&s);
    id<MTLBuffer> c = r_readback(&s, color, 0, 4), d = r_readback(&s, depth, 0, 4);
    submit_ok(&s, "pass clears");
    for (uint32_t y = 0; y < 16; y++) {
        for (uint32_t x = 0; x < 16; x++) {
            BOOL inside = x >= 4 && x < 12 && y >= 4 && y < 12;
            const uint8_t *q = px(c, 16, x, y);
            BOOL ok = inside ? near8(q, 255, 255, 0, 255) : near8(q, 0, 0, 255, 255);
            ok = ok && fpx(d, 16, x, y) == (inside ? 0.5f : 1.0f);
            if (!ok) {
                CHECK(0, "render-area clear pixel (%u,%u) = (%u,%u,%u,%u) depth %f inside %d", x, y, q[0], q[1], q[2], q[3],
                      fpx(d, 16, x, y), inside);
                y = 16;
                break;
            }
        }
    }
    checks++;

    /* Draw clipped by the render area (initial scissor), then RESET_SCISSOR restores the render area. */
    p = pass1(color, nil);
    p.hasArea = YES;
    memcpy(p.area, (int32_t[4]){0, 0, 8, 16}, 16);
    r_begin_pass(&s, &p);
    r_set_pipeline(&s, gSolid);
    r_set_buffer(&s, 2, MCMTL_STAGE_FRAGMENT, ubo, 0);
    r_set_vb(&s, 0, vb, 0);
    r_scissor(&s, 0, 0, 2, 2);
    r_reset_scissor(&s);
    r_draw(&s, 6, 1, 0, 0);
    r_end_pass(&s);
    c = r_readback(&s, color, 0, 4);
    submit_ok(&s, "render-area draw");
    CHECK_RECT(c, 16, 0, 0, 8, 16, 255, 255, 255, 255, "draw inside render area");
    CHECK_PX(c, 16, 12, 2, 0, 0, 255, 255, "draw outside render area");
    CHECK_PX(c, 16, 9, 8, 255, 255, 0, 255, "draw outside render area (old clear)");
    s_free(&s);
    printf("pass clears / render area: ok\n");
}

static void test_textured_triangle(void) {
    Stream s = {0};
    id<MTLTexture> target = new_texture(MTLPixelFormatRGBA8Unorm, 16, 16, 1, kRT);
    id<MTLTexture> tex = new_texture(MTLPixelFormatRGBA8Unorm, 4, 4, 1, MTLTextureUsageShaderRead);
    uint8_t texels[4 * 4 * 4];
    for (uint32_t y = 0; y < 4; y++) {
        for (uint32_t x = 0; x < 4; x++) {
            uint8_t *t = &texels[(y * 4 + x) * 4];
            BOOL right = x >= 2, bottom = y >= 2;
            t[0] = (!bottom && !right) || (bottom && right) ? 255 : 0;
            t[1] = (!bottom && right) || (bottom && right) ? 255 : 0;
            t[2] = (bottom && !right) || (bottom && right) ? 255 : 0;
            t[3] = 255;
        }
    }
    id<MTLBuffer> staging = new_buffer(64 + sizeof texels);
    memcpy((uint8_t *)staging.contents + 64, texels, sizeof texels);
    /* Interleaved pos + uv, full screen, uv (0,0) at the top-left pixel. */
    float pos[12];
    quad_px(pos, 0, 0, 16, 16, 16);
    const float uv[12] = { 0, 0, 1, 0, 1, 1, 1, 1, 0, 1, 0, 0 };
    float verts[24];
    for (int i = 0; i < 6; i++) { verts[i * 4] = pos[i * 2]; verts[i * 4 + 1] = pos[i * 2 + 1]; verts[i * 4 + 2] = uv[i * 2]; verts[i * 4 + 3] = uv[i * 2 + 1]; }
    id<MTLBuffer> vb = new_buffer_with(verts, sizeof verts);
    const float tints[2][4] = { {9, 9, 9, 9}, {1, 0.5f, 1, 1} };
    id<MTLBuffer> ubo = color_ubo(tints, 2);
    id<MTLSamplerState> nearest = new_sampler(NO);
    PipeSpec spec = { .name = "textured", .vs = "tex_vs", .fs = "tex_fs", .colorCount = 1, .colorFormats = { MTLPixelFormatRGBA8Unorm },
                      .attrCount = 2, .attrs = { { 0, MTLVertexFormatFloat2, 0, 0 }, { 1, MTLVertexFormatFloat2, 8, 0 } },
                      .layoutCount = 1, .layouts = { { 0, 16, 0 } },
                      .bufferMask = { 0, 1u << 1 }, .textureMask = { 0, 1 }, .samplerMask = { 0, 1 } };
    MCMTLPipeline *pipe = make_pipeline(&spec);
    add_variant(pipe, 1, 0);

    r_buffer_texture(&s, MCMTL_OP_COPY_BUFFER_TO_TEXTURE, staging, 64, 16, tex, 0, 0, 0, 0, 4, 4);
    PassDesc p = pass1(target, nil);
    p.clearMask = 1;
    r_begin_pass(&s, &p);
    r_set_pipeline(&s, pipe);
    r_set_vb(&s, 0, vb, 0);
    r_set_buffer(&s, 1, MCMTL_STAGE_FRAGMENT, ubo, 256);
    r_set_texture(&s, 0, MCMTL_STAGE_FRAGMENT, tex, nearest);
    r_draw(&s, 6, 1, 0, 0);
    r_end_pass(&s);
    id<MTLBuffer> c = r_readback(&s, target, 0, 4);
    submit_ok(&s, "textured triangle");
    CHECK_RECT(c, 16, 0, 0, 8, 8, 255, 0, 0, 255, "textured TL");
    CHECK_RECT(c, 16, 8, 0, 16, 8, 0, 128, 0, 255, "textured TR");
    CHECK_RECT(c, 16, 0, 8, 8, 16, 0, 0, 255, 255, "textured BL");
    CHECK_RECT(c, 16, 8, 8, 16, 16, 255, 128, 255, 255, "textured BR");
    s_free(&s);
    printf("textured triangle (UBO + texture + sampler): ok\n");
}

static void test_indexed_base_vertex(void) {
    Stream s = {0};
    id<MTLTexture> target = new_texture(MTLPixelFormatRGBA8Unorm, 16, 16, 1, kRT);
    /* 4 junk vertices, then the top-left quad (corners), then the bottom-right quad. */
    float verts[12 * 2];
    const float junk[8] = { -1, -1, -1, -1, -1, -1, -1, -1 };
    memcpy(verts, junk, sizeof junk);
    float tl[12], br[12];
    quad_px(tl, 0, 0, 8, 8, 16);
    quad_px(br, 8, 8, 16, 16, 16);
    /* corners of a quad from the 6-vertex list: v0, v1, v2, v4 */
    const int corner[4] = { 0, 1, 2, 4 };
    for (int i = 0; i < 4; i++) { verts[8 + i * 2] = tl[corner[i] * 2]; verts[8 + i * 2 + 1] = tl[corner[i] * 2 + 1]; }
    for (int i = 0; i < 4; i++) { verts[16 + i * 2] = br[corner[i] * 2]; verts[16 + i * 2 + 1] = br[corner[i] * 2 + 1]; }
    id<MTLBuffer> vb = new_buffer_with(verts, sizeof verts);
    const uint16_t idx16[9] = { 7, 7, 7, 0, 1, 2, 0, 2, 3 };    /* firstIndex 3: odd offset (6 bytes) */
    const uint32_t idx32[8] = { 99, 99, 12, 13, 14, 12, 14, 15 }; /* baseVertex -4 -> vertices 8..11 */
    id<MTLBuffer> ib16 = new_buffer_with(idx16, sizeof idx16), ib32 = new_buffer_with(idx32, sizeof idx32);
    const float colors[2][4] = { {1, 0, 0, 1}, {0, 1, 0, 1} };
    id<MTLBuffer> ubo = color_ubo(colors, 2);

    PassDesc p = pass1(target, nil);
    p.clearMask = 1;
    r_begin_pass(&s, &p);
    r_set_pipeline(&s, gSolid);
    r_set_vb(&s, 0, vb, 0);
    r_set_ib(&s, MTLIndexTypeUInt16, ib16);
    r_set_buffer(&s, 2, MCMTL_STAGE_FRAGMENT, ubo, 0);
    r_draw_indexed(&s, 6, 1, 3, 4, 0);
    r_set_ib(&s, MTLIndexTypeUInt32, ib32);
    r_set_buffer(&s, 2, MCMTL_STAGE_FRAGMENT, ubo, 256);
    r_draw_indexed(&s, 6, 1, 2, -4, 0);
    r_end_pass(&s);
    id<MTLBuffer> c = r_readback(&s, target, 0, 4);
    uint64_t offsetsBefore = stats()->setBufferOffset;
    submit_ok(&s, "indexed draws");
    CHECK(stats()->setBufferOffset == offsetsBefore + 1, "UBO offset change must use setFragmentBufferOffset");
    CHECK_RECT(c, 16, 0, 0, 8, 8, 255, 0, 0, 255, "indexed UInt16 firstIndex 3 baseVertex 4");
    CHECK_RECT(c, 16, 8, 8, 16, 16, 0, 255, 0, 255, "indexed UInt32 firstIndex 2 baseVertex -4");
    CHECK_RECT(c, 16, 8, 0, 16, 8, 0, 0, 0, 0, "indexed: untouched quadrant");
    s_free(&s);
    printf("indexed draws (firstIndex, signed baseVertex): ok\n");
}

static void test_instancing(void) {
    Stream s = {0};
    id<MTLTexture> target = new_texture(MTLPixelFormatRGBA8Unorm, 16, 16, 1, kRT);
    /* One 4x4-pixel cell at the top-left; instance i is offset by (i % 4) cells horizontally, (i / 4) vertically. */
    float cell[12];
    quad_px(cell, 0, 0, 4, 4, 16);
    id<MTLBuffer> vb = new_buffer_with(cell, sizeof cell);
    float inst[8][6];
    for (int i = 0; i < 8; i++) {
        inst[i][0] = (float)(i % 4) * 0.5f;
        inst[i][1] = -(float)(i / 4) * 0.5f;
        inst[i][2] = (float)(i + 1) / 8.0f;
        inst[i][3] = 1.0f - (float)i / 8.0f;
        inst[i][4] = 0.5f;
        inst[i][5] = 1.0f;
    }
    id<MTLBuffer> ib = new_buffer_with(inst, sizeof inst);
    const uint16_t indices[6] = { 0, 1, 2, 3, 4, 5 };
    id<MTLBuffer> ixb = new_buffer_with(indices, sizeof indices);
    PipeSpec spec = { .name = "instanced", .vs = "inst_vs", .fs = "color_fs", .colorCount = 1, .colorFormats = { MTLPixelFormatRGBA8Unorm },
                      .attrCount = 3, .attrs = { { 0, MTLVertexFormatFloat2, 0, 0 }, { 1, MTLVertexFormatFloat2, 0, 1 }, { 2, MTLVertexFormatFloat4, 8, 1 } },
                      .layoutCount = 2, .layouts = { { 0, 8, 0 }, { 1, 24, 1 } } };
    MCMTLPipeline *pipe = make_pipeline(&spec);
    add_variant(pipe, 1, 0);
    PassDesc p = pass1(target, nil);
    p.clearMask = 1;
    r_begin_pass(&s, &p);
    r_set_pipeline(&s, pipe);
    r_set_vb(&s, 0, vb, 0);
    r_set_vb(&s, 1, ib, 0);
    r_draw(&s, 6, 2, 0, 1);            /* instances 1, 2 */
    r_set_ib(&s, MTLIndexTypeUInt16, ixb);
    r_draw_indexed(&s, 6, 1, 0, 0, 6); /* instance 6 */
    r_end_pass(&s);
    id<MTLBuffer> c = r_readback(&s, target, 0, 4);
    submit_ok(&s, "instancing");
    for (int i = 0; i < 8; i++) {
        uint32_t cx = (uint32_t)(i % 4) * 4 + 2, cy = (uint32_t)(i / 4) * 4 + 2;
        BOOL drawn = i == 1 || i == 2 || i == 6;
        int r = drawn ? (int)lroundf(inst[i][2] * 255) : 0, g = drawn ? (int)lroundf(inst[i][3] * 255) : 0;
        int b = drawn ? 128 : 0, a = drawn ? 255 : 0;
        CHECK_PX(c, 16, cx, cy, r, g, b, a, "per-instance attribute with baseInstance");
    }
    s_free(&s);
    printf("instancing (baseInstance, direct + indexed): ok\n");
}

static void test_fans(void) {
    Stream s = {0};
    id<MTLTexture> target = new_texture(MTLPixelFormatRGBA8Unorm, 16, 16, 1, kRT);
    /* Square [4,12) as a fan: center then 5 rim points (closing), preceded by 2 junk vertices. */
    const float h = 0.5f;
    const float fan[16] = { 9, 9, 9, 9, 0, 0, -h, h, h, h, h, -h, -h, -h, -h, h };
    id<MTLBuffer> vb = new_buffer_with(fan, sizeof fan);
    const uint32_t indices[8] = { 77, 77, 2, 3, 4, 5, 6, 7 };
    id<MTLBuffer> ib = new_buffer_with(indices, sizeof indices);
    PipeSpec spec = { .name = "fan", .vs = "pos_vs", .fs = "ubo_fs", .colorCount = 1, .colorFormats = { MTLPixelFormatRGBA8Unorm },
                      .attrCount = 1, .attrs = { { 0, MTLVertexFormatFloat2, 0, 0 } }, .layoutCount = 1, .layouts = { { 0, 8, 0 } },
                      .bufferMask = { 0, 1u << 2 }, .fan = YES };
    MCMTLPipeline *pipe = make_pipeline(&spec);
    add_variant(pipe, 1, 0);
    const float colors[2][4] = { {1, 0, 1, 1}, {0, 1, 1, 1} };
    id<MTLBuffer> ubo = color_ubo(colors, 2);

    PassDesc p = pass1(target, nil);
    p.clearMask = 1;
    r_begin_pass(&s, &p);
    r_set_pipeline(&s, pipe);
    r_set_vb(&s, 0, vb, 0);
    r_set_buffer(&s, 2, MCMTL_STAGE_FRAGMENT, ubo, 0);
    r_scissor(&s, 0, 0, 8, 16);
    r_draw(&s, 6, 1, 2, 0);           /* non-indexed fan, firstVertex 2 -> left half of the square */
    r_draw(&s, 2, 1, 0, 0);           /* degenerate fan: no-op */
    r_set_buffer(&s, 2, MCMTL_STAGE_FRAGMENT, ubo, 256);
    r_set_ib(&s, MTLIndexTypeUInt32, ib);
    r_scissor(&s, 8, 0, 8, 16);
    r_draw_indexed(&s, 6, 1, 2, 0, 0); /* indexed fan -> right half */
    r_end_pass(&s);
    id<MTLBuffer> c = r_readback(&s, target, 0, 4);
    submit_ok(&s, "fans");
    CHECK_RECT(c, 16, 4, 4, 8, 12, 255, 0, 255, 255, "non-indexed fan");
    CHECK_RECT(c, 16, 8, 4, 12, 12, 0, 255, 255, 255, "indexed fan");
    CHECK_PX(c, 16, 2, 2, 0, 0, 0, 0, "outside fan");
    CHECK_PX(c, 16, 13, 13, 0, 0, 0, 0, "outside fan");
    s_free(&s);
    printf("triangle fans (draw + drawIndexed emulation): ok\n");
}

static void test_scissor(void) {
    Stream s = {0};
    id<MTLTexture> target = new_texture(MTLPixelFormatRGBA8Unorm, 16, 16, 1, kRT);
    float verts[12];
    quad_px(verts, 0, 0, 16, 16, 16);
    id<MTLBuffer> vb = new_buffer_with(verts, sizeof verts);
    const float colors[1][4] = { {0, 0, 1, 1} };
    id<MTLBuffer> ubo = color_ubo(colors, 1);
    PassDesc p = pass1(target, nil);
    p.clearMask = 1;
    r_begin_pass(&s, &p);
    r_set_pipeline(&s, gSolid);
    r_set_vb(&s, 0, vb, 0);
    r_set_buffer(&s, 2, MCMTL_STAGE_FRAGMENT, ubo, 0);
    r_scissor(&s, 2, 10, 4, 3);
    r_draw(&s, 6, 1, 0, 0);
    r_scissor(&s, 14, 14, 10, 10);    /* clamped to the attachment */
    r_draw(&s, 6, 1, 0, 0);
    r_end_pass(&s);
    id<MTLBuffer> c = r_readback(&s, target, 0, 4);
    submit_ok(&s, "scissor");
    for (uint32_t y = 0; y < 16; y++) {
        for (uint32_t x = 0; x < 16; x++) {
            BOOL inside = (x >= 2 && x < 6 && y >= 10 && y < 13) || (x >= 14 && y >= 14);
            const uint8_t *q = px(c, 16, x, y);
            if (!(inside ? near8(q, 0, 0, 255, 255) : near8(q, 0, 0, 0, 0))) {
                CHECK(0, "scissor pixel (%u,%u) = (%u,%u,%u,%u), inside %d", x, y, q[0], q[1], q[2], q[3], inside);
                y = 16;
                break;
            }
        }
    }
    checks++;
    s_free(&s);
    printf("scissor: ok\n");
}

static void test_copies(void) {
    Stream s = {0};
    /* COPY_BUFFER staging -> X then X -> Y in one blit batch: must be ordered (encoder split). */
    uint8_t pattern[256];
    for (int i = 0; i < 256; i++) pattern[i] = (uint8_t)(i * 7 + 3);
    id<MTLBuffer> staging = new_buffer_with(pattern, sizeof pattern);
    id<MTLBuffer> x = new_buffer(1024), y = new_buffer(1024);
    uint64_t splits = stats()->blitSplits;
    r_copy_buffer(&s, staging, 0, x, 100, 256);
    r_copy_buffer(&s, x, 100, y, 1, 256);
    submit_ok(&s, "buffer copies");
    CHECK(memcmp((uint8_t *)y.contents + 1, pattern, 256) == 0, "COPY_BUFFER X -> Y must see the staging write");
    CHECK(stats()->blitSplits >= splits + 1, "dependent COPY_BUFFERs must split the blit encoder");

    /* Independent copies share one blit encoder. */
    uint64_t encoders = stats()->blitEncoders;
    splits = stats()->blitSplits;
    for (int i = 0; i < 8; i++) r_copy_buffer(&s, staging, (uint64_t)i * 16, x, 512 + (uint64_t)i * 16, 16);
    submit_ok(&s, "independent copies");
    CHECK(stats()->blitEncoders == encoders + 1 && stats()->blitSplits == splits, "independent copies: %llu encoders, %llu splits",
          (unsigned long long)(stats()->blitEncoders - encoders), (unsigned long long)(stats()->blitSplits - splits));
    CHECK(memcmp((uint8_t *)x.contents + 512, pattern, 128) == 0, "batched COPY_BUFFERs");

    /* INIT_TEXTURE (init stream) then an upload into the same texture (main stream): zeros + region. */
    id<MTLTexture> t = new_texture(MTLPixelFormatRGBA8Unorm, 64, 64, 3, MTLTextureUsageShaderRead);
    id<MTLBuffer> junk = new_buffer(64 * 64 * 4);
    memset(junk.contents, 0x5A, junk.length);
    r_buffer_texture(&s, MCMTL_OP_COPY_BUFFER_TO_TEXTURE, junk, 0, 64 * 4, t, 0, 0, 0, 0, 64, 64);
    r_buffer_texture(&s, MCMTL_OP_COPY_BUFFER_TO_TEXTURE, junk, 0, 32 * 4, t, 1, 0, 0, 0, 32, 32);
    submit_ok(&s, "fill junk");
    Stream init = {0};
    r_init_texture(&init, t);
    uint8_t region[4 * 4 * 4];
    memset(region, 0xAB, sizeof region);
    id<MTLBuffer> up = new_buffer_with(region, sizeof region);
    r_buffer_texture(&s, MCMTL_OP_COPY_BUFFER_TO_TEXTURE, up, 0, 16, t, 0, 0, 8, 8, 4, 4);
    id<MTLBuffer> l0 = r_readback(&s, t, 0, 4), l1 = r_readback(&s, t, 1, 4);
    splits = stats()->blitSplits;
    mcmtl_submit_result res;
    int32_t rc = submit_ex(&init, &s, MCMTL_SUBMIT_FLAG_COMMIT, &res, "init + upload");
    CHECK(rc == MCMTL_OK, "init + upload rc %d %s", rc, mcmtl_last_error());
    CHECK(stats()->blitSplits >= splits + 1, "zero fill followed by an upload into the same texture must split");
    CHECK_RECT(l0, 64, 8, 8, 12, 12, 0xAB, 0xAB, 0xAB, 0xAB, "upload after INIT_TEXTURE");
    CHECK_RECT(l0, 64, 0, 0, 64, 8, 0, 0, 0, 0, "INIT_TEXTURE level 0");
    CHECK_RECT(l0, 64, 12, 8, 64, 12, 0, 0, 0, 0, "INIT_TEXTURE level 0 (right of upload)");
    CHECK_RECT(l1, 32, 0, 0, 32, 32, 0, 0, 0, 0, "INIT_TEXTURE level 1");
    s_reset(&s);
    s_free(&init);

    /* INIT_TEXTURE on a render target (clear passes) with a cube + mips. */
    MTLTextureDescriptor *cd = [MTLTextureDescriptor textureCubeDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm size:8 mipmapped:YES];
    cd.usage = kRT;
    cd.storageMode = MTLStorageModePrivate;
    id<MTLTexture> cube = [gMtl newTextureWithDescriptor:cd];
    r_init_texture(&init, cube);
    id<MTLBuffer> face = new_buffer(8 * 8 * 4);
    memset(face.contents, 0x77, face.length);
    r_buffer_texture(&s, MCMTL_OP_COPY_TEXTURE_TO_BUFFER, face, 0, 32, cube, 0, 5, 0, 0, 8, 8);
    rc = submit_ex(&init, &s, MCMTL_SUBMIT_FLAG_COMMIT, &res, NULL);
    CHECK(rc == MCMTL_OK, "cube init rc %d %s", rc, mcmtl_last_error());
    CHECK_RECT(face, 8, 0, 0, 8, 8, 0, 0, 0, 0, "INIT_TEXTURE cube face 5 (render target path)");
    s_reset(&s);
    s_free(&init);

    /* COPY_TEXTURE (blit), COPY_TEXTURE_TO_BUFFER / COPY_BUFFER_TO_TEXTURE with misaligned buffer offsets. */
    id<MTLTexture> a = new_texture(MTLPixelFormatRGBA8Unorm, 8, 8, 1, kRT | MTLTextureUsageShaderRead);
    id<MTLTexture> b = new_texture(MTLPixelFormatRGBA8Unorm, 8, 8, 1, kRT);
    const float red[4] = {1, 0, 0, 1}, gray[4] = {0.5f, 0.25f, 0.75f, 1};
    r_clear_texture(&s, a, MCMTL_ASPECT_COLOR, 0, 1, red, 0);
    r_clear_texture(&s, b, MCMTL_ASPECT_COLOR, 0, 1, gray, 0);
    r_copy_texture(&s, 0, b, a, 0, 1, 1, 4, 5, 3, 2);
    uint8_t rows[2 * 12];
    for (int i = 0; i < 24; i++) rows[i] = (uint8_t)(200 + i);
    id<MTLBuffer> odd = new_buffer(64);
    memcpy((uint8_t *)odd.contents + 1, rows, sizeof rows);     /* offset 1: not a multiple of 4 */
    r_buffer_texture(&s, MCMTL_OP_COPY_BUFFER_TO_TEXTURE, odd, 1, 12, a, 0, 0, 0, 0, 3, 2);
    id<MTLBuffer> rb = r_readback(&s, a, 0, 4);
    id<MTLBuffer> oddOut = new_buffer(64);
    memset(oddOut.contents, 0xEE, 64);
    r_buffer_texture(&s, MCMTL_OP_COPY_TEXTURE_TO_BUFFER, oddOut, 2, 14, a, 0, 0, 4, 5, 3, 2); /* offset 2, pitch 14 */
    submit_ok(&s, "texture copies");
    CHECK_RECT(rb, 8, 4, 5, 7, 7, 128, 64, 191, 255, "COPY_TEXTURE blit");
    CHECK_PX(rb, 8, 3, 5, 255, 0, 0, 255, "COPY_TEXTURE blit (outside)");
    CHECK(memcmp(rb.contents, rows, 12) == 0 && memcmp((uint8_t *)rb.contents + 32, rows + 12, 12) == 0,
          "misaligned COPY_BUFFER_TO_TEXTURE");
    const uint8_t *o = oddOut.contents;
    CHECK(o[0] == 0xEE && o[1] == 0xEE && near8(o + 2, 128, 64, 191, 255) && near8(o + 2 + 8, 128, 64, 191, 255) &&
          near8(o + 2 + 14, 128, 64, 191, 255) && o[2 + 12] == 0xEE && o[2 + 14 + 12] == 0xEE,
          "misaligned COPY_TEXTURE_TO_BUFFER (%u %u %u %u)", o[2], o[3], o[4], o[5]);

    /* COPY_FLAG_DRAW: RGBA8 -> RGBA16Float (nearest draw), D32 -> R32Float, D32 -> D32 blit. */
    id<MTLTexture> half = new_texture(MTLPixelFormatRGBA16Float, 8, 8, 1, kRT);
    const float zero[4] = {0, 0, 0, 0};
    r_clear_texture(&s, half, MCMTL_ASPECT_COLOR, 0, 1, zero, 0);
    r_copy_texture(&s, MCMTL_COPY_FLAG_DRAW, a, half, 0, 4, 5, 0, 0, 3, 2);
    id<MTLTexture> d1 = new_texture(MTLPixelFormatDepth32Float, 8, 8, 1, kRT);
    id<MTLTexture> d2 = new_texture(MTLPixelFormatDepth32Float, 8, 8, 1, kRT);
    id<MTLTexture> r32 = new_texture(MTLPixelFormatR32Float, 8, 8, 1, kRT);
    r_clear_texture(&s, d1, MCMTL_ASPECT_DEPTH, 0, 1, NULL, 0.625f);
    r_clear_texture(&s, d2, MCMTL_ASPECT_DEPTH, 0, 1, NULL, 0.0f);
    r_clear_texture(&s, r32, MCMTL_ASPECT_COLOR, 0, 1, zero, 0);
    r_copy_texture(&s, 0, d1, d2, 0, 0, 0, 2, 2, 4, 4);
    r_copy_texture(&s, MCMTL_COPY_FLAG_DRAW, d1, r32, 0, 0, 0, 1, 1, 2, 2);
    id<MTLBuffer> hb = r_readback(&s, half, 0, 8), db = r_readback(&s, d2, 0, 4), fb = r_readback(&s, r32, 0, 4);
    submit_ok(&s, "copy draws");
    const _Float16 *hp = hb.contents;
    CHECK(fabsf((float)hp[0] - 128 / 255.0f) < 0.01f && fabsf((float)hp[1] - 64 / 255.0f) < 0.01f &&
          fabsf((float)hp[2] - 191 / 255.0f) < 0.01f && fabsf((float)hp[3] - 1.0f) < 0.01f,
          "COPY_FLAG_DRAW RGBA8 -> RGBA16F: (%f %f %f %f)", (float)hp[0], (float)hp[1], (float)hp[2], (float)hp[3]);
    CHECK((float)hp[(1 * 8 + 2) * 4] > 0.4f && (float)hp[(2 * 8 + 0) * 4] == 0.0f && (float)hp[(0 * 8 + 3) * 4] == 0.0f,
          "COPY_FLAG_DRAW region bounds");
    CHECK(fpx(db, 8, 3, 3) == 0.625f && fpx(db, 8, 1, 1) == 0.0f && fpx(db, 8, 6, 6) == 0.0f, "D32 blit copy (%f)", fpx(db, 8, 3, 3));
    CHECK(fpx(fb, 8, 1, 1) == 0.625f && fpx(fb, 8, 2, 2) == 0.625f && fpx(fb, 8, 3, 3) == 0.0f && fpx(fb, 8, 0, 0) == 0.0f,
          "COPY_FLAG_DRAW D32 -> R32F (%f %f)", fpx(fb, 8, 1, 1), fpx(fb, 8, 3, 3));
    s_free(&s);
    printf("copies (buffer, buffer<->texture incl. misaligned, texture blit/draw, init + upload ordering): ok\n");
}

static void test_depth(void) {
    Stream s = {0};
    id<MTLTexture> target = new_texture(MTLPixelFormatRGBA8Unorm, 16, 16, 1, kRT);
    id<MTLTexture> depth = new_texture(MTLPixelFormatDepth32Float, 16, 16, 1, kRT);
    /* Quad A: x [0,12) z 0.8 red; quad B: x [4,16) z 0.3 green. Reverse-Z GREATER_EQUAL, cleared to 0. */
    float a[12], b[12];
    quad_px(a, 0, 0, 12, 16, 16);
    quad_px(b, 4, 0, 16, 16, 16);
    float verts[36];
    for (int i = 0; i < 6; i++) { verts[i * 3] = a[i * 2]; verts[i * 3 + 1] = a[i * 2 + 1]; verts[i * 3 + 2] = 0.8f; }
    for (int i = 0; i < 6; i++) { verts[18 + i * 3] = b[i * 2]; verts[18 + i * 3 + 1] = b[i * 2 + 1]; verts[18 + i * 3 + 2] = 0.3f; }
    id<MTLBuffer> vb = new_buffer_with(verts, sizeof verts);
    PipeSpec spec = { .name = "depth", .vs = "depth_vs", .fs = "ubo_fs", .colorCount = 1, .colorFormats = { MTLPixelFormatRGBA8Unorm },
                      .attrCount = 1, .attrs = { { 0, MTLVertexFormatFloat3, 0, 0 } }, .layoutCount = 1, .layouts = { { 0, 12, 0 } },
                      .bufferMask = { 0, 1u << 2 }, .compare = MTLCompareFunctionGreaterEqual, .depthWrite = YES };
    MCMTLPipeline *pipe = make_pipeline(&spec);
    add_variant(pipe, 1, MTLPixelFormatDepth32Float);
    const float colors[2][4] = { {1, 0, 0, 1}, {0, 1, 0, 1} };
    id<MTLBuffer> ubo = color_ubo(colors, 2);
    PassDesc p = pass1(target, depth);
    p.clearMask = 1;
    p.depthClear = YES;
    p.depthValue = 0.0f;
    r_begin_pass(&s, &p);
    r_set_pipeline(&s, pipe);
    r_set_vb(&s, 0, vb, 0);
    r_set_buffer(&s, 2, MCMTL_STAGE_FRAGMENT, ubo, 0);
    r_draw(&s, 6, 1, 0, 0);
    r_set_buffer(&s, 2, MCMTL_STAGE_FRAGMENT, ubo, 256);
    r_draw(&s, 6, 1, 6, 0);
    r_end_pass(&s);
    id<MTLBuffer> c = r_readback(&s, target, 0, 4), d = r_readback(&s, depth, 0, 4);
    submit_ok(&s, "depth test");
    CHECK_RECT(c, 16, 0, 0, 12, 16, 255, 0, 0, 255, "reverse-Z: nearer quad wins");
    CHECK_RECT(c, 16, 12, 0, 16, 16, 0, 255, 0, 255, "reverse-Z: far quad where nothing nearer");
    CHECK(fabsf(fpx(d, 16, 6, 6) - 0.8f) < 1e-6f && fabsf(fpx(d, 16, 14, 6) - 0.3f) < 1e-6f, "depth values %f %f",
          fpx(d, 16, 6, 6), fpx(d, 16, 14, 6));
    s_free(&s);
    printf("depth test (reverse-Z GREATER_EQUAL): ok\n");
}

static void test_timeline(void) {
    Stream s = {0};
    id<MTLTexture> target = new_texture(MTLPixelFormatRGBA8Unorm, 4, 4, 1, kRT);
    const float c1[4] = {0, 1, 0, 1};
    /* Non-COMMIT submit keeps the command buffer open; the next COMMIT carries its work. */
    r_clear_texture(&s, target, MCMTL_ASPECT_COLOR, 0, 1, c1, 0);
    uint64_t submittedBefore = mcmtl_status_load_u64(&gDev->status->submittedValue);
    mcmtl_submit_result res;
    int32_t rc = submit_ex(NULL, &s, 0, &res, NULL);
    CHECK(rc == MCMTL_OK && !(res.flags & MCMTL_SUBMIT_RESULT_FLAG_COMMITTED), "non-COMMIT submit rc %d flags 0x%x", rc, res.flags);
    CHECK(gDev->currentCommandBuffer != nil, "non-COMMIT submit must leave the command buffer open");
    CHECK(mcmtl_status_load_u64(&gDev->status->submittedValue) == submittedBefore, "non-COMMIT submit consumed a value");
    s_reset(&s);
    id<MTLBuffer> rb = r_readback(&s, target, 0, 4);
    uint64_t value = gNextValue;
    rc = submit(&s, &res);
    CHECK(rc == MCMTL_OK && (res.flags & MCMTL_SUBMIT_RESULT_FLAG_COMMITTED), "COMMIT after non-COMMIT rc %d", rc);
    CHECK(res.recordsDecoded == 1 && res.encodersCreated == 1, "records %u encoders %u", res.recordsDecoded, res.encodersCreated);
    CHECK_RECT(rb, 4, 0, 0, 4, 4, 0, 255, 0, 255, "work of the non-COMMIT submit committed by the next COMMIT");
    CHECK(mcmtl_status_load_u64(&gDev->status->completedValue) >= value, "completed value");
    /* Empty COMMITs still signal dense values. */
    for (int i = 0; i < 3; i++) {
        value = gNextValue;
        rc = submit(NULL, &res);
        CHECK(rc == MCMTL_OK && (res.flags & MCMTL_SUBMIT_RESULT_FLAG_COMMITTED), "empty COMMIT %llu", (unsigned long long)value);
    }
    CHECK(mcmtl_status_load_u64(&gDev->status->completedValue) == gNextValue - 1, "dense timeline: completed %llu, expected %llu",
          (unsigned long long)mcmtl_status_load_u64(&gDev->status->completedValue), (unsigned long long)(gNextValue - 1));
    s_free(&s);
    printf("COMMIT / timeline round trip: ok (completed %llu)\n", (unsigned long long)(gNextValue - 1));
}

static void test_dummies_and_bindings(void) {
    Stream s = {0};
    id<MTLTexture> target = new_texture(MTLPixelFormatRGBA8Unorm, 16, 16, 1, kRT);
    float full[12];
    quad_px(full, 0, 0, 16, 16, 16);
    id<MTLBuffer> vb = new_buffer_with(full, sizeof full);
    PipeSpec dspec = { .name = "dummies", .vs = "pos_vs", .fs = "dummy_fs", .colorCount = 1, .colorFormats = { MTLPixelFormatRGBA8Unorm },
                       .attrCount = 1, .attrs = { { 0, MTLVertexFormatFloat2, 0, 0 } }, .layoutCount = 1, .layouts = { { 0, 8, 0 } },
                       .bufferMask = { 0, 1u << 3 }, .textureMask = { 0, (1u << 1) | (1u << 2) | (1u << 3) },
                       .samplerMask = { 0, (1u << 1) | (1u << 2) }, .cubeMask = { 0, 1u << 2 }, .texelMask = { 0, 1u << 3 } };
    MCMTLPipeline *dummies = make_pipeline(&dspec);
    add_variant(dummies, 1, 0);
    PipeSpec tspec = { .name = "textured2", .vs = "tex_vs", .fs = "tex_fs", .colorCount = 1, .colorFormats = { MTLPixelFormatRGBA8Unorm },
                       .attrCount = 2, .attrs = { { 0, MTLVertexFormatFloat2, 0, 0 }, { 1, MTLVertexFormatFloat2, 0, 0 } },
                       .layoutCount = 1, .layouts = { { 0, 8, 0 } },
                       .bufferMask = { 0, 1u << 1 }, .textureMask = { 0, 1 }, .samplerMask = { 0, 1 } };
    MCMTLPipeline *textured = make_pipeline(&tspec);
    add_variant(textured, 1, 0);
    PipeSpec pspec = { .name = "push", .vs = "pos_vs", .fs = "push_fs", .colorCount = 1, .colorFormats = { MTLPixelFormatRGBA8Unorm },
                       .attrCount = 1, .attrs = { { 0, MTLVertexFormatFloat2, 0, 0 } }, .layoutCount = 1, .layouts = { { 0, 8, 0 } },
                       .bufferMask = { 0, 1u << 14 }, .pushSize = 16, .pushStages = MCMTL_STAGE_FRAGMENT };
    MCMTLPipeline *push = make_pipeline(&pspec);
    add_variant(push, 1, 0);
    id<MTLTexture> white = new_texture(MTLPixelFormatRGBA8Unorm, 2, 2, 1, kRT);
    const float one[4] = {1, 1, 1, 1};
    r_clear_texture(&s, white, MCMTL_ASPECT_COLOR, 0, 1, one, 0);
    const float tints[1][4] = { {1, 1, 1, 1} };
    id<MTLBuffer> ubo = color_ubo(tints, 1);
    id<MTLSamplerState> smp = new_sampler(NO);

    const mcmtl_decoder_stats before = *stats();
    PassDesc p = pass1(target, nil);
    p.clearMask = 1;
    memcpy(p.clear[0], (float[4]){1, 0, 0, 1}, 16);
    r_begin_pass(&s, &p);
    r_set_vb(&s, 0, vb, 0);
    /* Columns 0..3: everything dummied -> 0.25 gray, opaque. */
    r_set_pipeline(&s, dummies);
    r_scissor(&s, 0, 0, 4, 16);
    r_draw(&s, 6, 1, 0, 0);
    /* Columns 4..7: texture set (redundant records must be filtered), then drawn white. */
    r_set_pipeline(&s, textured);
    r_set_pipeline(&s, textured);
    for (int i = 0; i < 3; i++) r_set_buffer(&s, 1, MCMTL_STAGE_FRAGMENT, ubo, 0);
    r_set_texture(&s, 0, MCMTL_STAGE_FRAGMENT, white, smp);
    r_set_texture(&s, 0, MCMTL_STAGE_FRAGMENT, white, smp);
    r_scissor(&s, 4, 0, 4, 16);
    r_draw(&s, 6, 1, 0, 0);
    /* Columns 8..11: CLEAR_BINDING -> dummy texture (zeros) times the tint -> transparent black. */
    r_clear_binding(&s, 0, MCMTL_STAGE_FRAGMENT, MCMTL_BINDING_KIND_TEXTURE);
    r_scissor(&s, 8, 0, 4, 16);
    r_draw(&s, 6, 1, 0, 0);
    /* Columns 12..13: push constants; 14..15: new SET_PIPELINE without pushes -> zero dummy. */
    r_set_pipeline(&s, push);
    const float pc[4] = {0, 1, 0, 1};
    r_push_constants(&s, MCMTL_STAGE_FRAGMENT, pc, sizeof pc);
    r_scissor(&s, 12, 0, 2, 16);
    r_draw(&s, 6, 1, 0, 0);
    r_set_pipeline(&s, push);
    r_scissor(&s, 14, 0, 2, 16);
    r_draw(&s, 6, 1, 0, 0);
    r_end_pass(&s);
    id<MTLBuffer> c = r_readback(&s, target, 0, 4);
    submit_ok(&s, "dummies");
    const mcmtl_decoder_stats *after = stats();
    CHECK_RECT(c, 16, 0, 0, 4, 16, 64, 64, 64, 255, "unset UBO/texture/cube/texel buffer sample zeros");
    CHECK_RECT(c, 16, 4, 0, 8, 16, 255, 255, 255, 255, "texture binding");
    CHECK_RECT(c, 16, 8, 0, 12, 16, 0, 0, 0, 0, "CLEAR_BINDING -> dummy texture");
    CHECK_RECT(c, 16, 12, 0, 14, 16, 0, 255, 0, 255, "push constants");
    CHECK_RECT(c, 16, 14, 0, 16, 16, 0, 0, 0, 0, "unpushed push constants read zeros");
    /* dummies pipeline: UBO 3 + 3 textures + 2 samplers; CLEAR_BINDING: dummy texture 0 (+ dummy sampler);
     * push pipeline without pushes: buffer 14. */
    CHECK(after->dummyBindings - before.dummyBindings == 6, "dummy bindings %llu", (unsigned long long)(after->dummyBindings - before.dummyBindings));
    CHECK(after->setPipelineState - before.setPipelineState == 3, "PSO sets %llu (redundant SET_PIPELINE must be filtered)",
          (unsigned long long)(after->setPipelineState - before.setPipelineState));
    /* setBuffer: vertex buffer 30 + dummy UBO 3 + tint UBO 1 + dummy push 14 = 4 (redundant SET_BUFFERs filtered). */
    CHECK(after->setBuffer - before.setBuffer == 4, "setBuffer calls %llu", (unsigned long long)(after->setBuffer - before.setBuffer));
    /* setTexture: dummy 2D, cube, texel, white at 0, dummy at 0 = 5. */
    CHECK(after->setTexture - before.setTexture == 5, "setTexture calls %llu", (unsigned long long)(after->setTexture - before.setTexture));
    CHECK(after->setBytes - before.setBytes == 1, "setFragmentBytes calls %llu", (unsigned long long)(after->setBytes - before.setBytes));
    s_free(&s);
    printf("dummies, CLEAR_BINDING, push constants, shadow filtering: ok\n");
}

static void test_skips_and_indirect(void) {
    Stream s = {0};
    id<MTLTexture> target = new_texture(MTLPixelFormatRGBA8Unorm, 16, 16, 1, kRT);
    id<MTLTexture> depth = new_texture(MTLPixelFormatDepth32Float, 16, 16, 1, kRT);
    float quads[4][12];
    quad_px(quads[0], 0, 0, 8, 8, 16);
    quad_px(quads[1], 8, 0, 16, 8, 16);
    quad_px(quads[2], 0, 8, 8, 16, 16);
    quad_px(quads[3], 8, 8, 16, 16, 16);
    id<MTLBuffer> vb = new_buffer_with(quads, sizeof quads);
    const uint16_t indices[12] = { 0, 1, 2, 3, 4, 5, 0, 1, 2, 3, 4, 5 };
    id<MTLBuffer> ib = new_buffer_with(indices, sizeof indices);
    /* Indirect arguments at offset 4: two non-indexed draws (quads 0, 1), then two indexed (quads 2, 3). */
    uint32_t args[4 + 8 + 10];
    memset(args, 0, sizeof args);
    uint32_t *d = args + 1;
    d[0] = 6; d[1] = 1; d[2] = 0; d[3] = 0;
    d[4] = 6; d[5] = 1; d[6] = 6; d[7] = 0;
    uint32_t *e = args + 1 + 8;
    e[0] = 6; e[1] = 1; e[2] = 0; e[3] = 12; e[4] = 0;
    e[5] = 6; e[6] = 1; e[7] = 6; e[8] = 18; e[9] = 0;
    id<MTLBuffer> ab = new_buffer_with(args, sizeof args);
    const float colors[1][4] = { {1, 1, 0, 1} };
    id<MTLBuffer> ubo = color_ubo(colors, 1);

    PassDesc p = pass1(target, nil);
    p.clearMask = 1;
    r_begin_pass(&s, &p);
    r_set_pipeline(&s, gSolid);
    r_set_buffer(&s, 2, MCMTL_STAGE_FRAGMENT, ubo, 0);
    r_draw(&s, 6, 1, 0, 0);                 /* no vertex buffer bound yet: skipped */
    r_set_vb(&s, 0, vb, 0);
    r_draw_indirect(&s, MCMTL_OP_DRAW_INDIRECT, 2, ab, 4);
    r_draw_indexed(&s, 6, 1, 0, 0, 0);      /* no index buffer: skipped */
    r_set_ib(&s, MTLIndexTypeUInt16, ib);
    r_draw_indirect(&s, MCMTL_OP_DRAW_INDEXED_INDIRECT, 2, ab, 4 + 32);
    r_end_pass(&s);
    id<MTLBuffer> c = r_readback(&s, target, 0, 4);
    mcmtl_submit_result res;
    int32_t rc = submit(&s, &res);
    s_reset(&s);
    CHECK(rc == MCMTL_OK, "indirect rc %d %s", rc, mcmtl_last_error());
    CHECK(res.drawsSkipped == 2 && (res.flags & MCMTL_SUBMIT_RESULT_FLAG_DRAWS_SKIPPED), "drawsSkipped %u flags 0x%x",
          res.drawsSkipped, res.flags);
    CHECK(res.drawsEncoded == 4, "drawsEncoded %u", res.drawsEncoded);
    CHECK_RECT(c, 16, 0, 0, 16, 16, 255, 255, 0, 255, "drawIndirect + drawIndexedIndirect loops cover all quadrants");

    /* A pass signature without a cached PSO variant: the decoder asks mcmtl_pipeline_build_variant (I1). With the
     * I1 skeleton it fails and the draw is skipped (message once); with the real builder the variant is built
     * lazily, cached on the pipeline, and drawn. Both are correct; anything else is not. */
    p = pass1(target, depth);
    r_begin_pass(&s, &p);
    MCMTLPipeline *noDepth = solid_pipeline();
    noDepth->variantCount = 1;   /* keep only the (RGBA8, no depth) variant */
    r_set_pipeline(&s, noDepth);
    r_set_vb(&s, 0, vb, 0);
    r_set_buffer(&s, 2, MCMTL_STAGE_FRAGMENT, ubo, 0);
    r_draw(&s, 6, 1, 0, 0);
    r_end_pass(&s);
    rc = submit(&s, &res);
    BOOL built = mcmtl_pipeline_find_variant(noDepth, mcmtl_variant_key(1, MTLPixelFormatDepth32Float)) != nil;
    CHECK(rc == MCMTL_OK && (built ? (res.drawsSkipped == 0 && res.drawsEncoded == 1) : res.drawsSkipped == 1),
          "variant miss: rc %d built %d drawsSkipped %u drawsEncoded %u", rc, built, res.drawsSkipped, res.drawsEncoded);
    /* A failed variant is not rebuilt for every SET_PIPELINE (negative cache). */
    if (!built) {
        s_reset(&s);
        uint32_t messagesBefore = gDev->status->messageCount;
        for (int i = 0; i < 2; i++) {
            r_begin_pass(&s, &p);
            r_set_pipeline(&s, noDepth);
            r_end_pass(&s);
        }
        rc = submit(&s, &res);
        CHECK(rc == MCMTL_OK && gDev->status->messageCount == messagesBefore, "failed variant retried (%u new messages)",
              gDev->status->messageCount - messagesBefore);
    }
    s_free(&s);
    printf("indirect draws, missing vertex/index buffer and variant skips: ok\n");
}

static void test_present(void) {
    Stream s = {0};
    /* Source: 16x16 RGBA8, 2 mips; each row y has color (y*16, 255 - y*16, 7, 77). */
    id<MTLTexture> src = new_texture(MTLPixelFormatRGBA8Unorm, 16, 16, 2, kRT);
    uint8_t texels[16 * 16 * 4];
    for (int y = 0; y < 16; y++)
        for (int x = 0; x < 16; x++) {
            uint8_t *t = &texels[(y * 16 + x) * 4];
            t[0] = (uint8_t)(y * 16); t[1] = (uint8_t)(255 - y * 16); t[2] = (uint8_t)(x * 8); t[3] = 77;
        }
    id<MTLBuffer> up = new_buffer_with(texels, sizeof texels);
    r_buffer_texture(&s, MCMTL_OP_COPY_BUFFER_TO_TEXTURE, up, 0, 64, src, 0, 0, 0, 0, 16, 16);
    r_buffer_texture(&s, MCMTL_OP_COPY_BUFFER_TO_TEXTURE, up, 0, 64, src, 1, 0, 0, 0, 8, 8);
    submit_ok(&s, "present source");

    CAMetalLayer *layer = [CAMetalLayer layer];
    layer.device = gMtl;
    layer.pixelFormat = MTLPixelFormatBGRA8Unorm;
    layer.framebufferOnly = NO;
    layer.drawableSize = CGSizeMake(20, 12);
    MCMTLSurface *surface = [MCMTLSurface new];
    surface->owner = gDev;
    surface->layer = layer;
    surface->configuredWidth = 20;
    surface->configuredHeight = 12;

    /* A frame: a pass (GPU work), PRESENT, then a timestamp-free copy after it. */
    id<MTLTexture> scratch = new_texture(MTLPixelFormatRGBA8Unorm, 4, 4, 1, kRT);
    PassDesc p = pass1(scratch, nil);
    p.clearMask = 1;
    r_begin_pass(&s, &p);
    r_end_pass(&s);
    r_present(&s, surface, src, 0);
    id<MTLBuffer> after = r_readback(&s, scratch, 0, 4);
    const mcmtl_decoder_stats before = *stats();
    mcmtl_submit_result res;
    int32_t rc = submit(&s, &res);
    s_reset(&s);
    CHECK(rc == MCMTL_OK, "present rc %d %s", rc, mcmtl_last_error());
    CHECK((res.flags & MCMTL_SUBMIT_RESULT_FLAG_PRESENT_ENCODED) && (res.flags & MCMTL_SUBMIT_RESULT_FLAG_COMMITTED) &&
          !(res.flags & MCMTL_SUBMIT_RESULT_FLAG_DRAWABLE_RESIZED), "present flags 0x%x", res.flags);
    CHECK(res.drawableWidth == 20 && res.drawableHeight == 12, "drawable %ux%u", res.drawableWidth, res.drawableHeight);
    CHECK(stats()->untimedCommits == before.untimedCommits + 1 && stats()->commandBuffersCommitted == before.commandBuffersCommitted + 2,
          "early commit: untimed %llu, committed %llu", (unsigned long long)(stats()->untimedCommits - before.untimedCommits),
          (unsigned long long)(stats()->commandBuffersCommitted - before.commandBuffersCommitted));
    CHECK(surface->drawable != nil && surface->presentedFrames == 1, "drawable retained for present()");
    CHECK(near8(after.contents, 0, 0, 0, 0), "work after PRESENT executed");

    /* Read the drawable back: pixel (x, y) = source (x, 11 - y) for x < 16 (BGRA, alpha 1), black beyond. */
    id<MTLTexture> drawableTexture = surface->drawable.texture;
    id<MTLBuffer> db = new_buffer(20 * 12 * 4);
    id<MTLCommandBuffer> cb = [gDev->queue commandBuffer];
    id<MTLBlitCommandEncoder> be = [cb blitCommandEncoder];
    [be copyFromTexture:drawableTexture sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0, 0, 0) sourceSize:MTLSizeMake(20, 12, 1)
               toBuffer:db destinationOffset:0 destinationBytesPerRow:80 destinationBytesPerImage:80 * 12];
    [be endEncoding];
    [cb commit];
    [cb waitUntilCompleted];
    int bad = 0;
    for (int y = 0; y < 12; y++) {
        for (int x = 0; x < 20; x++) {
            const uint8_t *q = px(db, 20, (uint32_t)x, (uint32_t)y);
            BOOL ok;
            if (x < 16) {
                int sy = 11 - y;
                ok = near8(q, x * 8, 255 - sy * 16, sy * 16, 255);   /* BGRA bytes */
            } else {
                ok = near8(q, 0, 0, 0, 255);
            }
            if (!ok && bad++ == 0) {
                printf("  present pixel (%d,%d) = (%u,%u,%u,%u)\n", x, y, q[0], q[1], q[2], q[3]);
            }
        }
    }
    CHECK(bad == 0, "present pass: %d wrong pixels (flip / swizzle / opaque / clear outside the copy)", bad);
    surface->drawable = nil;   /* what mcmtl_surface_present does */

    /* Level 1 into a configured-size mismatch: DRAWABLE_RESIZED, copy 8x8. */
    surface->configuredWidth = 32;
    r_present(&s, surface, src, 1);
    rc = submit(&s, &res);
    s_reset(&s);
    CHECK(rc == MCMTL_OK && (res.flags & MCMTL_SUBMIT_RESULT_FLAG_PRESENT_ENCODED) && (res.flags & MCMTL_SUBMIT_RESULT_FLAG_DRAWABLE_RESIZED),
          "present level 1: rc %d flags 0x%x", rc, res.flags);
    surface->drawable = nil;

    /* nextDrawable returns nil (layer without device): DRAWABLE_NIL, the value is still committed. */
    CAMetalLayer *dead = [CAMetalLayer layer];
    dead.pixelFormat = MTLPixelFormatBGRA8Unorm;
    dead.drawableSize = CGSizeMake(8, 8);
    MCMTLSurface *deadSurface = [MCMTLSurface new];
    deadSurface->owner = gDev;
    deadSurface->layer = dead;
    r_present(&s, deadSurface, src, 0);
    rc = submit(&s, &res);
    CHECK(rc == MCMTL_OK && (res.flags & MCMTL_SUBMIT_RESULT_FLAG_DRAWABLE_NIL) && !(res.flags & MCMTL_SUBMIT_RESULT_FLAG_PRESENT_ENCODED) &&
          (res.flags & MCMTL_SUBMIT_RESULT_FLAG_COMMITTED), "nil drawable: rc %d flags 0x%x", rc, res.flags);
    s_free(&s);
    printf("present (early commit, flip, nil drawable, resized): ok\n");
}

/* Submits a stream whose record at `badOffset` is bad and checks: rc, errorOffset, value consumed, work before
 * the bad record committed (target red), work after it skipped (not green). */
static void expect_bad(const char *what, Stream *init, Stream *s, uint64_t badOffset, int32_t expectRc, BOOL inInit,
                       id<MTLTexture> target) {
    mcmtl_submit_result res;
    int32_t rc = submit_ex(init, s, MCMTL_SUBMIT_FLAG_COMMIT, &res, NULL);
    CHECK(rc == expectRc, "%s: rc %d, expected %d (%s)", what, rc, expectRc, mcmtl_last_error());
    CHECK(res.errorOffset == badOffset, "%s: errorOffset %llu, expected %llu", what, (unsigned long long)res.errorOffset,
          (unsigned long long)badOffset);
    CHECK(((res.flags & MCMTL_SUBMIT_RESULT_FLAG_ERROR_IN_INIT_STREAM) != 0) == inInit, "%s: init-stream flag", what);
    CHECK(res.flags & MCMTL_SUBMIT_RESULT_FLAG_COMMITTED, "%s: decode errors still commit", what);
    CHECK(strlen(mcmtl_last_error()) > 0, "%s: last error set", what);
    Stream rb = {0};
    id<MTLBuffer> b = r_readback(&rb, target, 0, 4);
    submit_ok(&rb, "readback");
    CHECK_PX(b, 4, 1, 1, 255, 0, 0, 255, what);
    s_free(&rb);
    s_reset(s);
    if (init) s_reset(init);
}

static void test_bad_streams(void) {
    Stream s = {0}, init = {0};
    id<MTLTexture> target = new_texture(MTLPixelFormatRGBA8Unorm, 4, 4, 1, kRT);
    const float red[4] = {1, 0, 0, 1}, green[4] = {0, 1, 0, 1};
    uint64_t bad;

    /* unknown op */
    r_clear_texture(&s, target, MCMTL_ASPECT_COLOR, 0, 1, red, 0);
    bad = s.length;
    rec_add(&s, 12, 16);
    r_clear_texture(&s, target, MCMTL_ASPECT_COLOR, 0, 1, green, 0);
    expect_bad("unknown op", NULL, &s, bad, MCMTL_E_BAD_STREAM, NO, target);

    /* pass op outside a pass */
    r_clear_texture(&s, target, MCMTL_ASPECT_COLOR, 0, 1, red, 0);
    bad = s.length;
    r_draw(&s, 3, 1, 0, 0);
    r_clear_texture(&s, target, MCMTL_ASPECT_COLOR, 0, 1, green, 0);
    expect_bad("pass op outside a pass", NULL, &s, bad, MCMTL_E_BAD_STREAM, NO, target);

    /* encoder op inside a pass (the open pass is ended, its clear kept) */
    PassDesc p = pass1(target, nil);
    p.clearMask = 1;
    memcpy(p.clear[0], red, 16);
    r_begin_pass(&s, &p);
    bad = s.length;
    r_clear_texture(&s, target, MCMTL_ASPECT_COLOR, 0, 1, green, 0);
    r_end_pass(&s);
    expect_bad("encoder op inside a pass", NULL, &s, bad, MCMTL_E_BAD_STREAM, NO, target);

    /* truncated record (size past the end) */
    r_clear_texture(&s, target, MCMTL_ASPECT_COLOR, 0, 1, red, 0);
    bad = s.length;
    mcmtl_cmd_clear_texture *t = rec_add(&s, MCMTL_OP_CLEAR_TEXTURE, sizeof *t);
    t->size = 64;   /* 48 bytes present */
    t->texture = H(target);
    t->aspects = MCMTL_ASPECT_COLOR;
    t->levelCount = 1;
    t->color[1] = 1;
    expect_bad("truncated record", NULL, &s, bad, MCMTL_E_BAD_STREAM, NO, target);

    /* size not a multiple of 16 / below the op minimum */
    r_clear_texture(&s, target, MCMTL_ASPECT_COLOR, 0, 1, red, 0);
    bad = s.length;
    mcmtl_cmd_header *h = rec_add(&s, MCMTL_OP_NOP, 32);
    h->size = 24;
    expect_bad("size not a multiple of 16", NULL, &s, bad, MCMTL_E_BAD_STREAM, NO, target);
    r_clear_texture(&s, target, MCMTL_ASPECT_COLOR, 0, 1, red, 0);
    bad = s.length;
    rec_add(&s, MCMTL_OP_COPY_BUFFER, 32);
    expect_bad("record below the op minimum", NULL, &s, bad, MCMTL_E_BAD_STREAM, NO, target);

    /* non-INIT_TEXTURE op in the init stream (main stream still decoded) */
    r_nop(&init);
    bad = init.length;
    r_clear_texture(&init, target, MCMTL_ASPECT_COLOR, 0, 1, green, 0);
    r_clear_texture(&s, target, MCMTL_ASPECT_COLOR, 0, 1, red, 0);
    expect_bad("CLEAR_TEXTURE in the init stream", &init, &s, bad, MCMTL_E_BAD_STREAM, YES, target);

    /* UPSCALE is rejected in M1 */
    r_clear_texture(&s, target, MCMTL_ASPECT_COLOR, 0, 1, red, 0);
    bad = s.length;
    rec_add(&s, MCMTL_OP_UPSCALE, sizeof(mcmtl_cmd_upscale));
    r_clear_texture(&s, target, MCMTL_ASPECT_COLOR, 0, 1, green, 0);
    expect_bad("UPSCALE", NULL, &s, bad, MCMTL_E_UNSUPPORTED, NO, target);

    /* stream ends inside a pass */
    r_clear_texture(&s, target, MCMTL_ASPECT_COLOR, 0, 1, green, 0);
    p = pass1(target, nil);
    p.clearMask = 1;
    memcpy(p.clear[0], red, 16);
    bad = r_begin_pass(&s, &p);
    expect_bad("unterminated pass", NULL, &s, bad, MCMTL_E_BAD_STREAM, NO, target);
    s_free(&s);
    s_free(&init);
    printf("bad streams (unknown, misplaced, truncated, bad size, init-stream op, UPSCALE, unterminated pass): ok\n");
}

static void test_timestamps(void) {
    if (!gDev->timestampCounterSet) {
        printf("timestamps: SKIPPED (no timestamp counter set)\n");
        return;
    }
    Stream s = {0};
    MTLCounterSampleBufferDescriptor *sd = [MTLCounterSampleBufferDescriptor new];
    sd.counterSet = gDev->timestampCounterSet;
    sd.storageMode = MTLStorageModeShared;
    sd.sampleCount = 16;
    NSError *error = nil;
    MCMTLQueryPool *pool = [MCMTLQueryPool new];
    pool->owner = gDev;
    pool->count = 16;
    pool->sampleBuffer = [gMtl newCounterSampleBufferWithDescriptor:sd error:&error];
    CHECK(pool->sampleBuffer != nil, "sample buffer: %s", error.localizedDescription.UTF8String);
    MCMTLQueryPool *pool2 = [MCMTLQueryPool new];
    pool2->owner = gDev;
    pool2->count = 16;
    pool2->sampleBuffer = [gMtl newCounterSampleBufferWithDescriptor:sd error:&error];

    id<MTLTexture> target = new_texture(MTLPixelFormatRGBA8Unorm, 16, 16, 1, kRT);
    id<MTLBuffer> a = new_buffer(4096), b = new_buffer(4096);
    float verts[12];
    quad_px(verts, 0, 0, 16, 16, 16);
    id<MTLBuffer> vb = new_buffer_with(verts, sizeof verts);
    const float colors[1][4] = { {0, 0, 1, 1} };
    id<MTLBuffer> ubo = color_ubo(colors, 1);
    uint32_t savedFlags = gDev->flags;
    gDev->flags |= MCMTL_DEVICE_FLAG_DEBUG_LABELS;

    r_timestamp(&s, pool, 0);          /* standalone (next op is a copy)                    */
    r_copy_buffer(&s, a, 0, b, 0, 4096);
    r_timestamp(&s, pool, 1);          /* start of the pass (labels in between are skipped) */
    r_timestamp(&s, pool2, 6);         /* second pool: one pool per pass -> standalone before   */
    r_push_group(&s, "frame");
    r_push_group(&s, "pass A");
    PassDesc p = pass1(target, nil);
    p.clearMask = 1;
    r_begin_pass(&s, &p);
    r_push_group(&s, "draws");
    r_set_pipeline(&s, gSolid);
    r_set_vb(&s, 0, vb, 0);
    r_set_buffer(&s, 2, MCMTL_STAGE_FRAGMENT, ubo, 0);
    r_draw(&s, 6, 1, 0, 0);
    r_timestamp(&s, pool, 2);          /* in the pass: end of fragment                      */
    r_pop_group(&s);
    r_end_pass(&s);
    r_pop_group(&s);
    r_timestamp(&s, pool, 3);          /* directly after the pass (POP skipped)             */
    r_timestamp(&s, pool2, 7);         /* second pool: standalone after the pass               */
    r_copy_buffer(&s, b, 0, a, 0, 4096);
    r_timestamp(&s, pool, 4);          /* standalone at the end                             */
    r_pop_group(&s);
    r_push_group(&s, "unbalanced");    /* popped by the decoder at the end                  */
    uint64_t standalone = stats()->standaloneTimestamps;
    mcmtl_submit_result res;
    int32_t rc = submit_ex(NULL, &s, MCMTL_SUBMIT_FLAG_COMMIT, &res, "decoder_test frame");
    CHECK(rc == MCMTL_OK, "timestamps rc %d %s", rc, mcmtl_last_error());
    CHECK(stats()->standaloneTimestamps == standalone + 4, "standalone samples %llu",
          (unsigned long long)(stats()->standaloneTimestamps - standalone));
    NSData *data = [pool->sampleBuffer resolveCounterRange:NSMakeRange(0, 5)];
    NSData *data2 = [pool2->sampleBuffer resolveCounterRange:NSMakeRange(6, 2)];
    const MTLCounterResultTimestamp *ts = data.bytes, *ts2 = data2.bytes;
    CHECK(data && data2, "resolve failed");
    if (data && data2) {
        for (int i = 0; i < 5; i++) {
            CHECK(ts[i].timestamp != 0 && ts[i].timestamp != MTLCounterErrorValue, "timestamp %d = %llu", i,
                  (unsigned long long)ts[i].timestamp);
        }
        CHECK(ts2[0].timestamp != 0 && ts2[1].timestamp != 0, "second pool timestamps %llu %llu",
              (unsigned long long)ts2[0].timestamp, (unsigned long long)ts2[1].timestamp);
        /* Independent encoders may overlap on the GPU, so only orderings that hold by construction are checked:
         * start <= end of one pass, and the standalone samples (their fills serialize on one scratch buffer). */
        CHECK(ts[1].timestamp <= ts[2].timestamp && ts[0].timestamp <= ts[4].timestamp,
              "timestamp order %llu %llu %llu %llu", (unsigned long long)ts[0].timestamp, (unsigned long long)ts[1].timestamp,
              (unsigned long long)ts[2].timestamp, (unsigned long long)ts[4].timestamp);
        printf("timestamps: pass took %llu ns (start %llu .. end %llu), standalone span %llu ns\n",
               (unsigned long long)(ts[2].timestamp - ts[1].timestamp), (unsigned long long)ts[1].timestamp,
               (unsigned long long)ts[2].timestamp, (unsigned long long)(ts[4].timestamp - ts[0].timestamp));
    }
    gDev->flags = savedFlags;
    s_free(&s);
    printf("timestamps + debug groups: ok\n");
}

static void test_exceptions(void) {
    Stream s = {0};
    id<MTLTexture> target = new_texture(MTLPixelFormatRGBA8Unorm, 16, 16, 1, kRT);
    id<MTLBuffer> a = new_buffer(256), b = new_buffer(256);
    float verts[12];
    quad_px(verts, 0, 0, 16, 16, 16);
    id<MTLBuffer> vb = new_buffer_with(verts, sizeof verts);
    const float colors[1][4] = { {0, 1, 0, 1} };
    id<MTLBuffer> ubo = color_ubo(colors, 1);

    /* Exception inside a pass after a blit batch: records 0 copy, 1 copy, 2 BEGIN_PASS, 3 SET_PIPELINE, 4 SET_VB,
     * 5 DRAW (raises). Then an exception while the blit encoder is open (record 1). */
    for (int variant = 0; variant < 2; variant++) {
        r_copy_buffer(&s, a, 0, b, 0, 128);
        r_copy_buffer(&s, a, 128, b, 128, 128);
        PassDesc p = pass1(target, nil);
        p.clearMask = 1;
        r_begin_pass(&s, &p);
        r_set_pipeline(&s, gSolid);
        r_set_vb(&s, 0, vb, 0);
        r_draw(&s, 6, 1, 0, 0);
        r_end_pass(&s);
        mcmtl_decoder_test_raise_at_record(gDev, variant == 0 ? 5 : 1);
        uint64_t value = gNextValue;
        mcmtl_submit_result res;
        int32_t rc = submit(&s, &res);
        s_reset(&s);
        CHECK(rc == MCMTL_E_OBJC_EXCEPTION, "exception %d: rc %d", variant, rc);
        CHECK(strstr(mcmtl_last_error(), "test hook") != NULL, "exception %d: last error '%s'", variant, mcmtl_last_error());
        CHECK(mcmtl_status_load_u64(&gDev->status->submittedValue) >= value, "exception %d: value consumed", variant);
        CHECK(gDev->currentCommandBuffer == nil, "exception %d: no open command buffer", variant);
        CHECK(res.flags & MCMTL_SUBMIT_RESULT_FLAG_COMMITTED, "exception %d: encoded work still committed (flags 0x%x)", variant, res.flags);
    }
    /* Work of a non-COMMIT call followed by a COMMIT call that throws at its first record: the earlier work is
     * still committed with the signal. */
    const float blue[4] = {0, 0, 1, 1};
    r_clear_texture(&s, target, MCMTL_ASPECT_COLOR, 0, 1, blue, 0);
    int32_t rc0 = submit_ex(NULL, &s, 0, NULL, NULL);
    CHECK(rc0 == MCMTL_OK && gDev->currentCommandBuffer != nil, "non-COMMIT before exception: rc %d", rc0);
    s_reset(&s);
    r_copy_buffer(&s, a, 0, b, 0, 16);
    mcmtl_decoder_test_raise_at_record(gDev, 0);
    mcmtl_submit_result res0;
    rc0 = submit(&s, &res0);
    s_reset(&s);
    CHECK(rc0 == MCMTL_E_OBJC_EXCEPTION && (res0.flags & MCMTL_SUBMIT_RESULT_FLAG_COMMITTED), "exception at record 0: rc %d flags 0x%x",
          rc0, res0.flags);
    id<MTLBuffer> earlier = r_readback(&s, target, 0, 4);
    submit_ok(&s, "readback after exception");
    CHECK_RECT(earlier, 16, 0, 0, 16, 16, 0, 0, 255, 255, "work of the earlier non-COMMIT call survives the exception");

    /* The next submit (value + 1) works and renders correctly. */
    PassDesc p = pass1(target, nil);
    p.clearMask = 1;
    r_begin_pass(&s, &p);
    r_set_pipeline(&s, gSolid);
    r_set_vb(&s, 0, vb, 0);
    r_set_buffer(&s, 2, MCMTL_STAGE_FRAGMENT, ubo, 0);
    r_draw(&s, 6, 1, 0, 0);
    r_end_pass(&s);
    id<MTLBuffer> c = r_readback(&s, target, 0, 4);
    submit_ok(&s, "after exception");
    CHECK_RECT(c, 16, 0, 0, 16, 16, 0, 255, 0, 255, "render after an exception");
    s_free(&s);
    printf("exceptions mid-stream (pass open / blit batch open): ok\n");
}

static void test_many_blits(void) {
    /* Hundreds of uploads into one buffer at disjoint ranges plus a reused range (written twice: the later
     * write must win), all between two passes. */
    Stream s = {0};
    id<MTLBuffer> staging = new_buffer(64 * 1024), uber = new_buffer(64 * 1024);
    uint8_t *st = staging.contents;
    for (int i = 0; i < 64 * 1024; i++) st[i] = (uint8_t)(i * 13 + i / 256);
    for (int i = 0; i < 300; i++) r_copy_buffer(&s, staging, (uint64_t)i * 64, uber, (uint64_t)((i * 37) % 300) * 64, 64);
    r_copy_buffer(&s, staging, 40000, uber, 64 * 5, 64);   /* overwrite a range written above */
    submit_ok(&s, "many blits");
    int bad = 0;
    for (int i = 0; i < 300; i++) {
        int slot = (i * 37) % 300;
        const uint8_t *expect = slot == 5 ? st + 40000 : st + i * 64;
        if (memcmp((uint8_t *)uber.contents + slot * 64, expect, 64) != 0) bad++;
    }
    CHECK(bad == 0, "batched uploads: %d wrong ranges", bad);
    s_free(&s);
    printf("blit batching with hazards: ok\n");
}

/* Hot-path cost: 4000 indexed draws, each with a DynamicTransforms-style UBO offset change, plus the frontend's
 * redundant replays (SET_PIPELINE + identical SET_BUFFER / SET_TEXTURE every 8 draws). Checks the filtering
 * counters and prints the decode+encode CPU time (inflated under MTL_DEBUG_LAYER). */
static void test_hot_path(void) {
    enum { DRAWS = 4000 };
    Stream s = {0};
    id<MTLTexture> target = new_texture(MTLPixelFormatRGBA8Unorm, 64, 64, 1, kRT);
    float quad[12];
    quad_px(quad, 0, 0, 64, 64, 64);
    const float corners[8] = { quad[0], quad[1], quad[2], quad[3], quad[4], quad[5], quad[8], quad[9] };
    id<MTLBuffer> vb = new_buffer_with(corners, sizeof corners);
    const uint16_t indices[6] = { 0, 1, 2, 0, 2, 3 };
    id<MTLBuffer> ib = new_buffer_with(indices, sizeof indices);
    id<MTLBuffer> ubo = new_buffer(256u * DRAWS);
    for (uint32_t i = 0; i < DRAWS; i++) {
        float *c = (float *)((uint8_t *)ubo.contents + 256u * i);
        c[0] = (float)(i % 256) / 255.0f; c[1] = 0.5f; c[2] = 1.0f; c[3] = 1.0f;
    }
    const float globals[1][4] = { {0, 0, 0, 0} };
    id<MTLBuffer> globalsUbo = color_ubo(globals, 1);
    PassDesc p = pass1(target, nil);
    p.clearMask = 1;
    r_begin_pass(&s, &p);
    r_set_vb(&s, 0, vb, 0);
    r_set_ib(&s, MTLIndexTypeUInt16, ib);
    for (uint32_t i = 0; i < DRAWS; i++) {
        if ((i & 7) == 0) {
            r_set_pipeline(&s, gSolid);
            r_set_buffer(&s, 1, MCMTL_STAGE_VERTEX | MCMTL_STAGE_FRAGMENT, globalsUbo, 0);   /* replayed global */
        }
        r_set_buffer(&s, 2, MCMTL_STAGE_FRAGMENT, ubo, 256u * i);
        r_draw_indexed(&s, 6, 1, 0, 0, 0);
    }
    r_end_pass(&s);
    id<MTLBuffer> c = r_readback(&s, target, 0, 4);
    const mcmtl_decoder_stats before = *stats();
    mcmtl_submit_result res;
    int32_t rc = submit(&s, &res);
    CHECK(rc == MCMTL_OK && res.drawsEncoded == DRAWS, "hot path rc %d draws %u", rc, res.drawsEncoded);
    const mcmtl_decoder_stats *after = stats();
    CHECK(after->setPipelineState - before.setPipelineState == 1, "PSO set %llu times",
          (unsigned long long)(after->setPipelineState - before.setPipelineState));
    /* vertex buffer + globals (2 stages) + the draw UBO once; then offsets only */
    CHECK(after->setBuffer - before.setBuffer == 4 && after->setBufferOffset - before.setBufferOffset == DRAWS - 1,
          "binding calls: %llu setBuffer, %llu setBufferOffset", (unsigned long long)(after->setBuffer - before.setBuffer),
          (unsigned long long)(after->setBufferOffset - before.setBufferOffset));
    CHECK_PX(c, 64, 10, 10, (int)((DRAWS - 1) % 256), 128, 255, 255, "last draw wins");
    printf("hot path: %u records, %u draws decoded+encoded+committed in %.3f ms CPU (%.0f ns/draw)%s\n",
           res.recordsDecoded, res.drawsEncoded, res.cpuNanos / 1e6, (double)res.cpuNanos / DRAWS,
           getenv("MTL_DEBUG_LAYER") ? " [with MTL_DEBUG_LAYER]" : "");
    s_free(&s);
}

/* ---------------------------------------------------------------------------------------------- */
/* M2: clear folding, late store actions, pass merging, hoisting -- equivalence with M1               */
/* ---------------------------------------------------------------------------------------------- */

/* Every case below records a stream with fresh resources and returns its readback buffers. The harness runs it
 * once per M2 configuration (M1 first) and compares every readback byte for byte with the M1 run; per-config
 * statistics deltas are returned for the case's own expectations. */
typedef NSArray<id<MTLBuffer>> *(^M2Case)(Stream *s);

enum { M2_CONFIGS = 9, CFG_M1 = 0, CFG_ALL = 1, CFG_FOLD_LATE = 2, CFG_NO_HOIST = 3, CFG_NO_CLEAR_DRAW = 4, CFG_FOLD = 5,
       CFG_LATE = 6, CFG_MERGE_ONLY = 7, CFG_FRAGMENT_CLEARS = 8 };
static const uint32_t kM2Configs[M2_CONFIGS] = {
    0, M2_ALL, M2_FOLD | M2_LATE_STORE, M2_ALL & ~M2_HOIST, M2_ALL & ~M2_CLEAR_DRAW, M2_FOLD, M2_LATE_STORE,
    M2_MERGE | M2_HOIST | M2_CLEAR_DRAW | M2_ZERO_CLEAR_VERTEX, M2_ALL & ~M2_ZERO_CLEAR_VERTEX,
};
static const char *const kM2ConfigNames[M2_CONFIGS] = {
    "M1", "all", "fold+late", "no hoist", "no clear draw", "fold only", "late only", "merge without fold",
    "[[depth(any)]] clears only",
};

static BOOL gM2Labels;
static MCMTLPipeline *gDepthGE;        /* depth_vs + ubo_fs, GREATER_EQUAL + write, D32 variant          */
static MCMTLPipeline *gDepthCullFront;  /* same, cull front + depth bias                                  */
static MCMTLPipeline *gDepthCullBack;   /* same, cull back + depth bias                                   */
static MCMTLPipeline *gTex;            /* tex_vs + tex_fs: texture 0 * tint (buffer 1)                    */
static id<MTLSamplerState> gNearest;

static mcmtl_decoder_stats stats_delta(const mcmtl_decoder_stats *before) {
    mcmtl_decoder_stats d = *stats();
    uint64_t *dp = (uint64_t *)((uint8_t *)&d + 8);
    const uint64_t *bp = (const uint64_t *)((const uint8_t *)before + 8);
    for (size_t i = 0; i < (sizeof d - 8) / 8; i++) dp[i] -= bp[i];
    return d;
}

static void run_m2_case(const char *name, M2Case fn, mcmtl_decoder_stats deltas[M2_CONFIGS]) {
    NSArray<id<MTLBuffer>> *reference = nil;
    const uint32_t savedFlags = gDev->flags;
    if (gM2Labels) gDev->flags |= MCMTL_DEVICE_FLAG_DEBUG_LABELS;
    for (int c = 0; c < M2_CONFIGS; c++) {
        const uint32_t previous = mcmtl_decoder_test_set_m2(gDev, kM2Configs[c]);
        Stream s = {0};
        NSMutableArray *outerKeep = gKeep;
        gKeep = [NSMutableArray array];
        NSArray<id<MTLBuffer>> *buffers = fn(&s);
        const mcmtl_decoder_stats before = *stats();
        mcmtl_submit_result res;
        int32_t rc = submit(&s, &res);
        CHECK(rc == MCMTL_OK && (res.flags & MCMTL_SUBMIT_RESULT_FLAG_COMMITTED), "%s [%s]: submit rc %d (%s)", name,
              kM2ConfigNames[c], rc, mcmtl_last_error());
        deltas[c] = stats_delta(&before);
        gKeep = outerKeep;
        mcmtl_decoder_test_set_m2(gDev, previous);
        s_free(&s);
        if (c == CFG_M1) {
            reference = buffers;
            continue;
        }
        CHECK(buffers.count == reference.count, "%s [%s]: readback count", name, kM2ConfigNames[c]);
        for (NSUInteger i = 0; i < buffers.count && i < reference.count; i++) {
            const uint8_t *a = reference[i].contents, *b = buffers[i].contents;
            NSUInteger n = reference[i].length, diff = n;
            for (NSUInteger k = 0; k < n; k++) {
                if (a[k] != b[k]) { diff = k; break; }
            }
            CHECK(diff == n, "%s [%s]: readback %lu differs from M1 at byte %lu (%u vs %u)", name, kM2ConfigNames[c],
                  (unsigned long)i, (unsigned long)diff, diff < n ? a[diff] : 0, diff < n ? b[diff] : 0);
        }
    }
    gDev->flags = savedFlags;
}

/* 6 float3 vertices of the pixel rect [x0,x1)x[y0,y1) of a size x size target at depth z. */
static void quad3(float *out, float x0, float y0, float x1, float y1, float z, float size) {
    float q[12];
    quad_px(q, x0, y0, x1, y1, size);
    for (int i = 0; i < 6; i++) { out[i * 3] = q[i * 2]; out[i * 3 + 1] = q[i * 2 + 1]; out[i * 3 + 2] = z; }
}

/* 6 (x, y, u, v) vertices of a pixel rect with uv 0..1 across it. */
static void quad_uv(float *out, float x0, float y0, float x1, float y1, float size) {
    float q[12];
    quad_px(q, x0, y0, x1, y1, size);
    const float uv[12] = { 0, 0, 1, 0, 1, 1, 1, 1, 0, 1, 0, 0 };
    for (int i = 0; i < 6; i++) { out[i * 4] = q[i * 2]; out[i * 4 + 1] = q[i * 2 + 1]; out[i * 4 + 2] = uv[i * 2]; out[i * 4 + 3] = uv[i * 2 + 1]; }
}

static id<MTLBuffer> color_buffer(float r, float g, float b, float a) {
    const float c[1][4] = { { r, g, b, a } };
    return color_ubo(c, 1);
}

static void r_m2_begin(Stream *s, const PassDesc *p, const char *label) {
    if (gM2Labels) r_push_group(s, label);
    r_begin_pass(s, p);
}

static void r_m2_end(Stream *s) {
    r_end_pass(s);
    if (gM2Labels) r_pop_group(s);
}

static void r_quad(Stream *s, MCMTLPipeline *pipe, id<MTLBuffer> vb, uint32_t quad, id<MTLBuffer> ubo) {
    r_set_pipeline(s, pipe);
    r_set_vb(s, 0, vb, 0);
    r_set_buffer(s, 2, MCMTL_STAGE_FRAGMENT, ubo, 0);
    r_draw(s, 6, 1, quad * 6, 0);
}

static void r_tex_quad(Stream *s, id<MTLBuffer> vb, uint32_t quad, id<MTLTexture> t, id<MTLBuffer> tint) {
    r_set_pipeline(s, gTex);
    r_set_vb(s, 0, vb, 0);
    r_set_buffer(s, 1, MCMTL_STAGE_FRAGMENT, tint, 0);
    r_set_texture(s, 0, MCMTL_STAGE_FRAGMENT, t, gNearest);
    r_draw(s, 6, 1, quad * 6, 0);
}

static PassDesc m2_pass(id<MTLTexture> color, id<MTLTexture> depth) {
    return pass1(color, depth);
}

static void m2_pipelines(void) {
    PipeSpec dspec = { .name = "m2 depth", .vs = "depth_vs", .fs = "ubo_fs", .colorCount = 1, .colorFormats = { MTLPixelFormatRGBA8Unorm },
                       .attrCount = 1, .attrs = { { 0, MTLVertexFormatFloat3, 0, 0 } }, .layoutCount = 1, .layouts = { { 0, 12, 0 } },
                       .bufferMask = { 0, 1u << 2 }, .compare = MTLCompareFunctionGreaterEqual, .depthWrite = YES };
    gDepthGE = make_pipeline(&dspec);
    add_variant(gDepthGE, 1, MTLPixelFormatDepth32Float);
    gDepthCullFront = make_pipeline(&dspec);
    gDepthCullFront->cullMode = MTLCullModeFront;
    gDepthCullFront->depthBiasConstant = 4096.0f;
    gDepthCullFront->depthBiasSlopeScale = 2.0f;
    add_variant(gDepthCullFront, 1, MTLPixelFormatDepth32Float);
    gDepthCullBack = make_pipeline(&dspec);
    gDepthCullBack->cullMode = MTLCullModeBack;
    gDepthCullBack->depthBiasConstant = 4096.0f;
    gDepthCullBack->depthBiasSlopeScale = 2.0f;
    add_variant(gDepthCullBack, 1, MTLPixelFormatDepth32Float);
    PipeSpec tspec = { .name = "m2 textured", .vs = "tex_vs", .fs = "tex_fs", .colorCount = 1, .colorFormats = { MTLPixelFormatRGBA8Unorm },
                       .attrCount = 2, .attrs = { { 0, MTLVertexFormatFloat2, 0, 0 }, { 1, MTLVertexFormatFloat2, 8, 0 } },
                       .layoutCount = 1, .layouts = { { 0, 16, 0 } },
                       .bufferMask = { 0, 1u << 1 }, .textureMask = { 0, 1 }, .samplerMask = { 0, 1 } };
    gTex = make_pipeline(&tspec);
    add_variant(gTex, 1, 0);
    add_variant(gTex, 1, MTLPixelFormatDepth32Float);
    gNearest = new_sampler(NO);
}

/* The vanilla frame shape (spec 9 inventory): C1 color+depth, an upload, C2 (overrides C1), Sky, an upload read
 * only by Main, Main, an upload read only by Hand, C3 depth, Hand (cull + depth bias pipeline), Screen effects,
 * C4 depth, GUI, readbacks. With everything on: C2 folds into Sky's load actions, Sky..GUI share one encoder with
 * two depth-clear draws, both uploads are hoisted. */
static M2Case m2_frame_case(MCMTLPipeline *handPipe) {
    return ^NSArray<id<MTLBuffer>> *(Stream *s) {
        id<MTLTexture> color = new_texture(MTLPixelFormatRGBA8Unorm, 16, 16, 1, kRT);
        id<MTLTexture> depth = new_texture(MTLPixelFormatDepth32Float, 16, 16, 1, kRT);
        float v[6][18];
        quad3(v[0], 0, 0, 16, 16, 0.0f, 16);   /* sky                    */
        quad3(v[1], 0, 0, 8, 16, 0.6f, 16);    /* main left              */
        quad3(v[2], 8, 0, 16, 16, 0.3f, 16);   /* main right             */
        quad3(v[3], 0, 8, 8, 16, 0.2f, 16);    /* hand: bottom left      */
        quad3(v[4], 8, 8, 16, 16, 0.1f, 16);   /* effects: bottom right  */
        quad3(v[5], 4, 4, 12, 12, 0.0f, 16);   /* gui: center            */
        id<MTLBuffer> vb = new_buffer_with(v, sizeof v);
        id<MTLBuffer> sky = color_buffer(0.2f, 0.4f, 0.6f, 1), fx = color_buffer(0, 0, 1, 1), gui = color_buffer(1, 1, 1, 1);
        id<MTLBuffer> main = color_buffer(1, 0, 1, 1), hand = color_buffer(1, 0, 1, 1);   /* overwritten by the uploads */
        id<MTLBuffer> mainStaging = color_buffer(1, 0.5f, 0, 1), handStaging = color_buffer(0, 1, 0, 1);
        id<MTLBuffer> scratch = new_buffer(256), scratchStaging = color_buffer(9, 9, 9, 9);
        const float red[4] = {1, 0, 0, 1}, fog[4] = {0, 0, 0.5f, 1};
        if (gM2Labels) r_push_group(s, "frame");
        r_clear_texture(s, color, MCMTL_ASPECT_COLOR, 0, 1, red, 0);
        r_clear_texture(s, depth, MCMTL_ASPECT_DEPTH, 0, 1, NULL, 0.5f);
        r_copy_buffer(s, scratchStaging, 0, scratch, 0, 16);
        r_clear_texture(s, color, MCMTL_ASPECT_COLOR, 0, 1, fog, 0);
        r_clear_texture(s, depth, MCMTL_ASPECT_DEPTH, 0, 1, NULL, 0.0f);
        PassDesc p = m2_pass(color, depth);
        r_m2_begin(s, &p, "Sky");
        r_quad(s, gDepthGE, vb, 0, sky);
        r_m2_end(s);
        r_copy_buffer(s, mainStaging, 0, main, 0, 16);
        r_m2_begin(s, &p, "Main");
        r_quad(s, gDepthGE, vb, 1, main);
        r_quad(s, gDepthGE, vb, 2, main);
        r_m2_end(s);
        r_copy_buffer(s, handStaging, 0, hand, 0, 16);
        r_clear_texture(s, depth, MCMTL_ASPECT_DEPTH, 0, 1, NULL, 0.0f);
        r_m2_begin(s, &p, "Item in hand");
        r_quad(s, handPipe, vb, 3, hand);
        r_m2_end(s);
        r_m2_begin(s, &p, "Screen effects");
        r_quad(s, gDepthGE, vb, 4, fx);
        r_m2_end(s);
        r_clear_texture(s, depth, MCMTL_ASPECT_DEPTH, 0, 1, NULL, 0.0f);
        r_m2_begin(s, &p, "GUI");
        r_quad(s, gDepthGE, vb, 5, gui);
        r_m2_end(s);
        if (gM2Labels) r_pop_group(s);
        return @[ r_readback(s, color, 0, 4), r_readback(s, depth, 0, 4) ];
    };
}

static void test_m2_frame(void) {
    mcmtl_decoder_stats d[M2_CONFIGS];
    for (int labels = 0; labels < 2; labels++) {
        gM2Labels = labels != 0;
        MCMTLPipeline *hands[2] = { gDepthCullFront, gDepthCullBack };
        for (int h = 0; h < 2; h++) {
            char name[64];
            snprintf(name, sizeof name, "frame (labels %d, hand cull %s)", labels, h ? "back" : "front");
            run_m2_case(name, m2_frame_case(hands[h]), d);
            CHECK(d[CFG_ALL].passesIn == 5 && d[CFG_ALL].passEncoders == 1 && d[CFG_ALL].merges == 4,
                  "%s: passes %llu -> encoders %llu, merges %llu", name, (unsigned long long)d[CFG_ALL].passesIn,
                  (unsigned long long)d[CFG_ALL].passEncoders, (unsigned long long)d[CFG_ALL].merges);
            CHECK(d[CFG_ALL].hoists == 2 && d[CFG_ALL].clearDraws == 2 && d[CFG_ALL].clearsFolded == 2 &&
                  d[CFG_ALL].clearsMaterialized == 0 && d[CFG_ALL].mergeBreaks == 0,
                  "%s: hoists %llu clear draws %llu folded %llu materialized %llu breaks %llu", name,
                  (unsigned long long)d[CFG_ALL].hoists, (unsigned long long)d[CFG_ALL].clearDraws,
                  (unsigned long long)d[CFG_ALL].clearsFolded, (unsigned long long)d[CFG_ALL].clearsMaterialized,
                  (unsigned long long)d[CFG_ALL].mergeBreaks);
            /* M1: 5 pass encoders + 6 clear passes. */
            CHECK(d[CFG_M1].renderEncoders == 11 && d[CFG_ALL].renderEncoders == 1, "%s: render encoders M1 %llu, all %llu", name,
                  (unsigned long long)d[CFG_M1].renderEncoders, (unsigned long long)d[CFG_ALL].renderEncoders);
            /* Without hoisting the uploads end the chain twice: Sky | Main | Hand+Effects+GUI. */
            CHECK(d[CFG_NO_HOIST].passEncoders == 3 && d[CFG_NO_HOIST].hoists == 0, "%s: no hoist: %llu encoders",
                  name, (unsigned long long)d[CFG_NO_HOIST].passEncoders);
            /* Without clear draws the depth clears end the chain: Sky+Main | Hand+Effects | GUI. */
            CHECK(d[CFG_NO_CLEAR_DRAW].passEncoders == 3 && d[CFG_NO_CLEAR_DRAW].clearDraws == 0, "%s: no clear draw: %llu encoders",
                  name, (unsigned long long)d[CFG_NO_CLEAR_DRAW].passEncoders);
            /* Unmerged with late stores: Main's depth is overwritten by C3, Effects' by C4 -> DontCare. */
            CHECK(d[CFG_FOLD_LATE].dontCareStores == 2 && d[CFG_FOLD_LATE].passEncoders == 5 && d[CFG_FOLD_LATE].renderEncoders == 5,
                  "%s: fold+late: %llu DontCare, %llu encoders", name, (unsigned long long)d[CFG_FOLD_LATE].dontCareStores,
                  (unsigned long long)d[CFG_FOLD_LATE].renderEncoders);
        }
    }
    gM2Labels = NO;

    /* Pixel expectations (all on). */
    const uint32_t previous = mcmtl_decoder_test_set_m2(gDev, M2_ALL);
    Stream s = {0};
    NSArray<id<MTLBuffer>> *rb = m2_frame_case(gDepthCullFront)(&s);
    submit_ok(&s, "frame pixels");
    mcmtl_decoder_test_set_m2(gDev, previous);
    id<MTLBuffer> c = rb[0], dpt = rb[1];
    CHECK_PX(c, 16, 1, 1, 255, 128, 0, 255, "frame: Main (uploaded color, hoisted)");
    CHECK_PX(c, 16, 14, 2, 255, 128, 0, 255, "frame: Main right");
    CHECK_PX(c, 16, 8, 8, 255, 255, 255, 255, "frame: GUI after the second depth-clear draw");
    CHECK_PX(c, 16, 13, 14, 0, 0, 255, 255, "frame: Screen effects");
    CHECK(fpx(dpt, 16, 1, 1) == 0.0f && fpx(dpt, 16, 6, 6) == 0.0f && fpx(dpt, 16, 13, 14) == 0.0f,
          "frame depth after C4 + GUI: %f %f %f", fpx(dpt, 16, 1, 1), fpx(dpt, 16, 6, 6), fpx(dpt, 16, 13, 14));
    s_free(&s);
    printf("M2 frame (C1/C2 fold, Sky..GUI merged, 2 depth-clear draws, 2 hoisted uploads, labels on/off): ok\n");
}

static void test_m2_clear_uses(void) {
    mcmtl_decoder_stats d[M2_CONFIGS];

    /* Clear, then sampled through a texture view of it by a pass on another target: materialized first. */
    run_m2_case("clear then sample (view)", ^NSArray<id<MTLBuffer>> *(Stream *s) {
        id<MTLTexture> target = new_texture(MTLPixelFormatRGBA8Unorm, 16, 16, 1, kRT);
        id<MTLTexture> t = new_texture(MTLPixelFormatRGBA8Unorm, 4, 4, 1, kRT);
        id<MTLTexture> view = keep([t newTextureViewWithPixelFormat:MTLPixelFormatRGBA8Unorm]);
        float v[24];
        quad_uv(v, 0, 0, 16, 16, 16);
        id<MTLBuffer> vb = new_buffer_with(v, sizeof v), tint = color_buffer(1, 1, 1, 1);
        const float green[4] = {0, 1, 0, 1};
        r_clear_texture(s, t, MCMTL_ASPECT_COLOR, 0, 1, green, 0);
        PassDesc p = m2_pass(target, nil);
        p.clearMask = 1;
        r_begin_pass(s, &p);
        r_tex_quad(s, vb, 0, view, tint);
        r_end_pass(s);
        return @[ r_readback(s, target, 0, 4) ];
    }, d);
    CHECK(d[CFG_ALL].clearsMaterialized == 1 && d[CFG_ALL].clearsFolded == 0, "clear then sample: materialized %llu",
          (unsigned long long)d[CFG_ALL].clearsMaterialized);

    /* Clear, then copied (blit) and read back. */
    run_m2_case("clear then copy", ^NSArray<id<MTLBuffer>> *(Stream *s) {
        id<MTLTexture> t = new_texture(MTLPixelFormatRGBA8Unorm, 8, 8, 1, kRT);
        id<MTLTexture> u = new_texture(MTLPixelFormatRGBA8Unorm, 8, 8, 1, kRT);
        const float blue[4] = {0, 0, 1, 1}, black[4] = {0, 0, 0, 1};
        r_clear_texture(s, u, MCMTL_ASPECT_COLOR, 0, 1, black, 0);
        r_clear_texture(s, t, MCMTL_ASPECT_COLOR, 0, 1, blue, 0);
        r_copy_texture(s, 0, t, u, 0, 0, 0, 2, 2, 4, 4);
        return @[ r_readback(s, u, 0, 4), r_readback(s, t, 0, 4) ];
    }, d);
    CHECK(d[CFG_ALL].clearsMaterialized == 2, "clear then copy: materialized %llu", (unsigned long long)d[CFG_ALL].clearsMaterialized);

    /* Clear, then a pass with a partial render area: the clear must land first (M1 loads outside the area). */
    run_m2_case("clear then partial render area", ^NSArray<id<MTLBuffer>> *(Stream *s) {
        id<MTLTexture> color = new_texture(MTLPixelFormatRGBA8Unorm, 16, 16, 1, kRT);
        id<MTLTexture> depth = new_texture(MTLPixelFormatDepth32Float, 16, 16, 1, kRT);
        float v[18];
        quad3(v, 0, 0, 16, 16, 0.5f, 16);
        id<MTLBuffer> vb = new_buffer_with(v, sizeof v), ubo = color_buffer(0, 1, 0, 1);
        const float red[4] = {1, 0, 0, 1};
        r_clear_texture(s, color, MCMTL_ASPECT_COLOR, 0, 1, red, 0);
        r_clear_texture(s, depth, MCMTL_ASPECT_DEPTH, 0, 1, NULL, 0.25f);
        PassDesc p = m2_pass(color, depth);
        p.hasArea = YES;
        memcpy(p.area, (int32_t[4]){4, 4, 8, 8}, 16);
        r_begin_pass(s, &p);
        r_quad(s, gDepthGE, vb, 0, ubo);
        r_end_pass(s);
        return @[ r_readback(s, color, 0, 4), r_readback(s, depth, 0, 4) ];
    }, d);
    CHECK(d[CFG_ALL].clearsMaterialized == 2 && d[CFG_ALL].clearsFolded == 0 && d[CFG_ALL].dontCareStores == 0,
          "partial area: materialized %llu folded %llu DontCare %llu", (unsigned long long)d[CFG_ALL].clearsMaterialized,
          (unsigned long long)d[CFG_ALL].clearsFolded, (unsigned long long)d[CFG_ALL].dontCareStores);

    /* All mips cleared, one rendered: level 0 folds, the others are materialized by the readbacks. */
    run_m2_case("clear mips, render one", ^NSArray<id<MTLBuffer>> *(Stream *s) {
        id<MTLTexture> t = new_texture(MTLPixelFormatRGBA8Unorm, 16, 16, 3, kRT);
        float v[12];
        quad_px(v, 4, 4, 12, 12, 16);
        id<MTLBuffer> vb = new_buffer_with(v, sizeof v), ubo = color_buffer(0, 0, 1, 1);
        const float red[4] = {1, 0, 0, 1};
        r_clear_texture(s, t, MCMTL_ASPECT_COLOR, 0, 3, red, 0);
        PassDesc p = m2_pass(t, nil);
        r_begin_pass(s, &p);
        r_quad(s, gSolid, vb, 0, ubo);
        r_end_pass(s);
        return @[ r_readback(s, t, 0, 4), r_readback(s, t, 1, 4), r_readback(s, t, 2, 4) ];
    }, d);
    CHECK(d[CFG_ALL].clearsFolded == 1 && d[CFG_ALL].clearsMaterialized == 2, "mips: folded %llu materialized %llu",
          (unsigned long long)d[CFG_ALL].clearsFolded, (unsigned long long)d[CFG_ALL].clearsMaterialized);

    /* Clear, then a region clear of the same texture; a clear overridden by a pass's own clear; a clear left
     * pending at the end of the call (encoded at the end, read by the next submit). */
    run_m2_case("clear then region clear / pass clear", ^NSArray<id<MTLBuffer>> *(Stream *s) {
        id<MTLTexture> a = new_texture(MTLPixelFormatRGBA8Unorm, 8, 8, 1, kRT);
        id<MTLTexture> b = new_texture(MTLPixelFormatRGBA8Unorm, 8, 8, 1, kRT);
        const float red[4] = {1, 0, 0, 1}, green[4] = {0, 1, 0, 1};
        r_clear_texture(s, a, MCMTL_ASPECT_COLOR, 0, 1, red, 0);
        r_clear_region(s, 0, a, nil, 2, 2, 4, 4, green, 0);
        r_clear_texture(s, b, MCMTL_ASPECT_COLOR, 0, 1, red, 0);
        PassDesc p = m2_pass(b, nil);
        p.clearMask = 1;
        memcpy(p.clear[0], green, 16);
        r_begin_pass(s, &p);
        r_end_pass(s);
        return @[ r_readback(s, a, 0, 4), r_readback(s, b, 0, 4) ];
    }, d);
    CHECK(d[CFG_ALL].clearsMaterialized == 1 && d[CFG_ALL].clearsDropped == 1, "region/pass clear: materialized %llu dropped %llu",
          (unsigned long long)d[CFG_ALL].clearsMaterialized, (unsigned long long)d[CFG_ALL].clearsDropped);

    for (int c = 0; c < M2_CONFIGS; c++) {
        const uint32_t previous = mcmtl_decoder_test_set_m2(gDev, kM2Configs[c]);
        Stream s = {0};
        id<MTLTexture> t = new_texture(MTLPixelFormatRGBA8Unorm, 4, 4, 1, kRT);
        const float yellow[4] = {1, 1, 0, 1};
        r_clear_texture(&s, t, MCMTL_ASPECT_COLOR, 0, 1, yellow, 0);
        submit_ok(&s, "clear at the end of a call");
        id<MTLBuffer> rb = r_readback(&s, t, 0, 4);
        submit_ok(&s, "readback in the next call");
        CHECK_RECT(rb, 4, 0, 0, 4, 4, 255, 255, 0, 255, "pending clear at the end of a call is encoded");
        mcmtl_decoder_test_set_m2(gDev, previous);
        s_free(&s);
    }
    printf("M2 clear folding: sampled (view), copied, partial area, mips, region clear, pass clear, end of call: ok\n");
}

static void test_m2_merge_rules(void) {
    mcmtl_decoder_stats d[M2_CONFIGS];

    /* The next pass samples the open pass's attachment (another mip of it, through a view): no merge. */
    run_m2_case("pass samples the previous pass's attachment", ^NSArray<id<MTLBuffer>> *(Stream *s) {
        id<MTLTexture> a = new_texture(MTLPixelFormatRGBA8Unorm, 16, 16, 2, kRT);
        id<MTLTexture> mip1 = keep([a newTextureViewWithPixelFormat:MTLPixelFormatRGBA8Unorm textureType:MTLTextureType2D
                                                             levels:NSMakeRange(1, 1) slices:NSMakeRange(0, 1)]);
        float q[12], uv[24];
        quad_px(q, 0, 0, 8, 16, 16);
        quad_uv(uv, 8, 0, 16, 16, 16);
        id<MTLBuffer> vb = new_buffer_with(q, sizeof q), vbuv = new_buffer_with(uv, sizeof uv);
        id<MTLBuffer> red = color_buffer(1, 0, 0, 1), tint = color_buffer(1, 1, 1, 1);
        const float green[4] = {0, 1, 0, 1};
        r_clear_texture(s, a, MCMTL_ASPECT_COLOR, 1, 1, green, 0);
        PassDesc p = m2_pass(a, nil);
        p.clearMask = 1;
        r_begin_pass(s, &p);
        r_quad(s, gSolid, vb, 0, red);
        r_end_pass(s);
        p.clearMask = 0;
        r_begin_pass(s, &p);
        r_tex_quad(s, vbuv, 0, mip1, tint);
        r_end_pass(s);
        return @[ r_readback(s, a, 0, 4) ];
    }, d);
    CHECK(d[CFG_ALL].merges == 0 && d[CFG_ALL].clearsMaterialized == 1 && d[CFG_ALL].dontCareStores == 0,
          "sampled attachment: merges %llu materialized %llu DontCare %llu", (unsigned long long)d[CFG_ALL].merges,
          (unsigned long long)d[CFG_ALL].clearsMaterialized, (unsigned long long)d[CFG_ALL].dontCareStores);

    /* An upload between passes whose destination the earlier pass reads: not hoisted, chain ends there. And
     * one into a buffer only the later pass reads: hoisted, merged. */
    for (int variant = 0; variant < 2; variant++) {
        run_m2_case(variant ? "upload read only by the later pass" : "upload read by the earlier pass", ^NSArray<id<MTLBuffer>> *(Stream *s) {
            id<MTLTexture> color = new_texture(MTLPixelFormatRGBA8Unorm, 16, 16, 1, kRT);
            float q[24];
            quad_px(q, 0, 0, 8, 16, 16);
            quad_px(q + 12, 8, 0, 16, 16, 16);
            id<MTLBuffer> vb = new_buffer_with(q, sizeof q);
            id<MTLBuffer> u = new_buffer(512), u2 = new_buffer(512), staging = color_buffer(0, 1, 0, 1);
            const float red[4] = {1, 0, 0, 1};
            memcpy(u.contents, red, 16);
            memcpy((uint8_t *)u.contents + 256, red, 16);
            memcpy(u2.contents, red, 16);
            PassDesc p = m2_pass(color, nil);
            p.clearMask = 1;
            r_begin_pass(s, &p);
            r_set_pipeline(s, gSolid);
            r_set_vb(s, 0, vb, 0);
            r_set_buffer(s, 2, MCMTL_STAGE_FRAGMENT, u, 256);   /* reads u from offset 256 onwards */
            r_draw(s, 6, 1, 0, 0);
            r_end_pass(s);
            /* variant 0: writes u at 256 (read above); variant 1: writes u2 (and u below 256: not read above) */
            if (variant == 0) {
                r_copy_buffer(s, staging, 0, u, 256, 16);
            } else {
                r_copy_buffer(s, staging, 0, u2, 0, 16);
                r_copy_buffer(s, staging, 0, u, 0, 16);
            }
            p.clearMask = 0;
            r_begin_pass(s, &p);
            r_set_pipeline(s, gSolid);
            r_set_vb(s, 0, vb, 0);
            r_set_buffer(s, 2, MCMTL_STAGE_FRAGMENT, variant ? u2 : u, variant ? 0 : 256);
            r_draw(s, 6, 1, 6, 0);
            r_end_pass(s);
            return @[ r_readback(s, color, 0, 4) ];
        }, d);
        if (variant == 0) {
            CHECK(d[CFG_ALL].hoists == 0 && d[CFG_ALL].merges == 0, "upload read by the earlier pass: hoists %llu merges %llu",
                  (unsigned long long)d[CFG_ALL].hoists, (unsigned long long)d[CFG_ALL].merges);
        } else {
            CHECK(d[CFG_ALL].hoists == 2 && d[CFG_ALL].merges == 1, "upload read by the later pass: hoists %llu merges %llu",
                  (unsigned long long)d[CFG_ALL].hoists, (unsigned long long)d[CFG_ALL].merges);
        }
    }
    /* The same for texture uploads: the earlier pass samples the destination (no hoist) / does not (hoist). */
    for (int variant = 0; variant < 2; variant++) {
        run_m2_case(variant ? "texture upload sampled only later" : "texture upload sampled earlier", ^NSArray<id<MTLBuffer>> *(Stream *s) {
            id<MTLTexture> color = new_texture(MTLPixelFormatRGBA8Unorm, 16, 16, 1, kRT);
            id<MTLTexture> t1 = new_texture(MTLPixelFormatRGBA8Unorm, 2, 2, 1, MTLTextureUsageShaderRead);
            id<MTLTexture> t2 = new_texture(MTLPixelFormatRGBA8Unorm, 2, 2, 1, MTLTextureUsageShaderRead);
            float uv[48];
            quad_uv(uv, 0, 0, 8, 16, 16);
            quad_uv(uv + 24, 8, 0, 16, 16, 16);
            id<MTLBuffer> vb = new_buffer_with(uv, sizeof uv), tint = color_buffer(1, 1, 1, 1);
            uint8_t redTexels[16], greenTexels[16];
            for (int i = 0; i < 4; i++) {
                memcpy(redTexels + i * 4, (uint8_t[4]){255, 0, 0, 255}, 4);
                memcpy(greenTexels + i * 4, (uint8_t[4]){0, 255, 0, 255}, 4);
            }
            id<MTLBuffer> redUp = new_buffer_with(redTexels, 16), greenUp = new_buffer_with(greenTexels, 16);
            r_buffer_texture(s, MCMTL_OP_COPY_BUFFER_TO_TEXTURE, redUp, 0, 8, t1, 0, 0, 0, 0, 2, 2);
            r_buffer_texture(s, MCMTL_OP_COPY_BUFFER_TO_TEXTURE, redUp, 0, 8, t2, 0, 0, 0, 0, 2, 2);
            PassDesc p = m2_pass(color, nil);
            p.clearMask = 1;
            r_begin_pass(s, &p);
            r_tex_quad(s, vb, 0, t1, tint);
            r_end_pass(s);
            r_buffer_texture(s, MCMTL_OP_COPY_BUFFER_TO_TEXTURE, greenUp, 0, 8, variant ? t2 : t1, 0, 0, 0, 0, 2, 2);
            p.clearMask = 0;
            r_begin_pass(s, &p);
            r_tex_quad(s, vb, 1, variant ? t2 : t1, tint);
            r_end_pass(s);
            return @[ r_readback(s, color, 0, 4) ];
        }, d);
        CHECK(d[CFG_ALL].hoists == (uint64_t)variant && d[CFG_ALL].merges == (uint64_t)variant, "texture upload variant %d: hoists %llu merges %llu",
              variant, (unsigned long long)d[CFG_ALL].hoists, (unsigned long long)d[CFG_ALL].merges);
    }

    /* A clear of another texture between passes stays pending; the pass sampling it is not merged (K1); a pass
     * that does not sample it is (K2). */
    for (int variant = 0; variant < 2; variant++) {
        run_m2_case(variant ? "gap clear, sampled two passes later" : "gap clear, sampled by the next pass", ^NSArray<id<MTLBuffer>> *(Stream *s) {
            id<MTLTexture> color = new_texture(MTLPixelFormatRGBA8Unorm, 16, 16, 1, kRT);
            id<MTLTexture> t = new_texture(MTLPixelFormatRGBA8Unorm, 2, 2, 1, kRT);
            float q[36], uv[24];
            quad_px(q, 0, 0, 4, 16, 16);
            quad_px(q + 12, 4, 0, 8, 16, 16);
            quad_px(q + 24, 0, 0, 16, 4, 16);
            quad_uv(uv, 8, 0, 16, 16, 16);
            id<MTLBuffer> vb = new_buffer_with(q, sizeof q), vbuv = new_buffer_with(uv, sizeof uv);
            id<MTLBuffer> red = color_buffer(1, 0, 0, 1), tint = color_buffer(1, 1, 1, 1);
            const float green[4] = {0, 1, 0, 1}, blue[4] = {0, 0, 1, 1};
            r_clear_texture(s, t, MCMTL_ASPECT_COLOR, 0, 1, blue, 0);
            PassDesc p = m2_pass(color, nil);
            p.clearMask = 1;
            r_begin_pass(s, &p);
            r_quad(s, gSolid, vb, 0, red);
            r_tex_quad(s, vbuv, 0, t, tint);     /* samples t (blue): its first clear is materialized here */
            r_end_pass(s);
            r_clear_texture(s, t, MCMTL_ASPECT_COLOR, 0, 1, green, 0);
            p.clearMask = 0;
            if (variant) {
                r_begin_pass(s, &p);
                r_quad(s, gSolid, vb, 1, red);
                r_end_pass(s);
            }
            r_begin_pass(s, &p);
            r_tex_quad(s, vbuv, 0, t, tint);
            r_end_pass(s);
            return @[ r_readback(s, color, 0, 4) ];
        }, d);
        CHECK(d[CFG_ALL].merges == (uint64_t)variant && d[CFG_ALL].clearsMaterialized == 2 && d[CFG_ALL].mergeBreaks == 0,
              "gap clear variant %d: merges %llu materialized %llu breaks %llu", variant, (unsigned long long)d[CFG_ALL].merges,
              (unsigned long long)d[CFG_ALL].clearsMaterialized, (unsigned long long)d[CFG_ALL].mergeBreaks);
    }

    /* A partial render area does not continue an encoder. */
    run_m2_case("partial pass after a full pass", ^NSArray<id<MTLBuffer>> *(Stream *s) {
        id<MTLTexture> color = new_texture(MTLPixelFormatRGBA8Unorm, 16, 16, 1, kRT);
        float q[12];
        quad_px(q, 0, 0, 16, 16, 16);
        id<MTLBuffer> vb = new_buffer_with(q, sizeof q), red = color_buffer(1, 0, 0, 1), blue = color_buffer(0, 0, 1, 1);
        PassDesc p = m2_pass(color, nil);
        p.clearMask = 1;
        r_begin_pass(s, &p);
        r_quad(s, gSolid, vb, 0, red);
        r_end_pass(s);
        p.clearMask = 0;
        p.hasArea = YES;
        memcpy(p.area, (int32_t[4]){0, 0, 8, 16}, 16);
        r_begin_pass(s, &p);
        r_quad(s, gSolid, vb, 0, blue);
        r_end_pass(s);
        return @[ r_readback(s, color, 0, 4) ];
    }, d);
    CHECK(d[CFG_ALL].merges == 0, "partial pass merged (%llu)", (unsigned long long)d[CFG_ALL].merges);

    /* Bindings never persist across passes, even inside one encoder: the second pass draws without binding a
     * vertex buffer (skipped, like M1) and with its UBO slot unset (dummy zeros, like M1). */
    run_m2_case("per-pass binding state in a merged encoder", ^NSArray<id<MTLBuffer>> *(Stream *s) {
        id<MTLTexture> color = new_texture(MTLPixelFormatRGBA8Unorm, 16, 16, 1, kRT);
        float q[24];
        quad_px(q, 0, 0, 16, 16, 16);
        quad_px(q + 12, 4, 4, 12, 12, 16);
        id<MTLBuffer> vb = new_buffer_with(q, sizeof q), red = color_buffer(1, 0, 0, 1);
        PassDesc p = m2_pass(color, nil);
        p.clearMask = 1;
        r_begin_pass(s, &p);
        r_quad(s, gSolid, vb, 0, red);
        r_end_pass(s);
        p.clearMask = 0;
        r_begin_pass(s, &p);
        r_set_pipeline(s, gSolid);
        r_draw(s, 6, 1, 6, 0);              /* no vertex buffer in this pass: skipped */
        r_set_vb(s, 0, vb, 0);
        r_draw(s, 6, 1, 6, 0);              /* UBO 2 unset in this pass: dummy (transparent black) */
        r_end_pass(s);
        return @[ r_readback(s, color, 0, 4) ];
    }, d);
    CHECK(d[CFG_ALL].merges == 1, "binding state case not merged (%llu)", (unsigned long long)d[CFG_ALL].merges);

    /* Timestamps between two passes forbid merging them. */
    if (gDev->timestampCounterSet) {
        MTLCounterSampleBufferDescriptor *sd = [MTLCounterSampleBufferDescriptor new];
        sd.counterSet = gDev->timestampCounterSet;
        sd.storageMode = MTLStorageModeShared;
        sd.sampleCount = 8;
        MCMTLQueryPool *pool = [MCMTLQueryPool new];
        pool->owner = gDev;
        pool->count = 8;
        pool->sampleBuffer = [gMtl newCounterSampleBufferWithDescriptor:sd error:nil];
        run_m2_case("timestamp between passes", ^NSArray<id<MTLBuffer>> *(Stream *s) {
            id<MTLTexture> color = new_texture(MTLPixelFormatRGBA8Unorm, 16, 16, 1, kRT);
            float q[24];
            quad_px(q, 0, 0, 8, 16, 16);
            quad_px(q + 12, 8, 0, 16, 16, 16);
            id<MTLBuffer> vb = new_buffer_with(q, sizeof q), red = color_buffer(1, 0, 0, 1);
            PassDesc p = m2_pass(color, nil);
            p.clearMask = 1;
            r_begin_pass(s, &p);
            r_quad(s, gSolid, vb, 0, red);
            r_end_pass(s);
            r_timestamp(s, pool, 0);
            p.clearMask = 0;
            r_begin_pass(s, &p);
            r_quad(s, gSolid, vb, 1, red);
            r_end_pass(s);
            return @[ r_readback(s, color, 0, 4) ];
        }, d);
        CHECK(d[CFG_ALL].merges == 0, "timestamp between passes: merged (%llu)", (unsigned long long)d[CFG_ALL].merges);
    }
    printf("M2 merge rules (sampled attachment, uploads read earlier/later, gap clears, partial area, bindings, timestamps): ok\n");
}

/* Depth-clear draws in a merged encoder with reverse-Z GREATER_EQUAL: the pipeline before the clear culls and
 * biases depth; the clear must write exactly 0 everywhere, and a pass's own depth clear (0.75) exactly 0.75. */
static void test_m2_depth_clear_draw(void) {
    mcmtl_decoder_stats d[M2_CONFIGS];
    MCMTLPipeline *pipes[2] = { gDepthCullFront, gDepthCullBack };
    for (int k = 0; k < 2; k++) {
        MCMTLPipeline *biased = pipes[k];
        run_m2_case(k ? "depth clear draw (after cull back)" : "depth clear draw (after cull front)", ^NSArray<id<MTLBuffer>> *(Stream *s) {
            id<MTLTexture> color = new_texture(MTLPixelFormatRGBA8Unorm, 16, 16, 1, kRT);
            id<MTLTexture> depth = new_texture(MTLPixelFormatDepth32Float, 16, 16, 1, kRT);
            float v[6][18];
            quad3(v[0], 0, 0, 16, 16, 0.8f, 16);
            quad3(v[1], 0, 0, 16, 16, 0.3f, 16);
            quad3(v[2], 0, 0, 16, 16, 0.5f, 16);
            quad3(v[3], 0, 0, 8, 16, 0.9f, 16);
            /* quads 0 and 1 with the opposite winding, so one of each pair survives the cull mode */
            for (int i = 0; i < 6; i++) memcpy(v[4] + i * 3, v[0] + (5 - i) * 3, 12);
            for (int i = 0; i < 6; i++) memcpy(v[5] + i * 3, v[1] + (5 - i) * 3, 12);
            id<MTLBuffer> vb = new_buffer_with(v, sizeof v);
            id<MTLBuffer> red = color_buffer(1, 0, 0, 1), blue = color_buffer(0, 0, 1, 1), green = color_buffer(0, 1, 0, 1),
                          white = color_buffer(1, 1, 1, 1);
            PassDesc p = m2_pass(color, depth);
            p.clearMask = 1;
            p.depthClear = YES;
            p.depthValue = 0.0f;
            r_begin_pass(s, &p);
            r_quad(s, biased, vb, 0, red);
            r_quad(s, biased, vb, 4, red);
            r_end_pass(s);
            r_clear_texture(s, depth, MCMTL_ASPECT_DEPTH, 0, 1, NULL, 0.0f);
            p.clearMask = 0;
            p.depthClear = NO;
            r_begin_pass(s, &p);                 /* 0.3 >= 0 everywhere only if the clear wrote 0 (no cull, no bias) */
            r_quad(s, biased, vb, 1, blue);
            r_quad(s, biased, vb, 5, blue);
            r_end_pass(s);
            p.depthClear = YES;
            p.depthValue = 0.75f;
            r_begin_pass(s, &p);                 /* own clear 0.75 drawn right after the cull + bias pipeline */
            r_quad(s, gDepthGE, vb, 2, green);   /* 0.5 < 0.75: nothing */
            r_end_pass(s);
            p.depthClear = NO;
            r_begin_pass(s, &p);
            r_quad(s, gDepthGE, vb, 3, white);   /* 0.9 >= 0.75 on the left half */
            r_end_pass(s);
            return @[ r_readback(s, color, 0, 4), r_readback(s, depth, 0, 4) ];
        }, d);
        CHECK(d[CFG_ALL].merges == 3 && d[CFG_ALL].clearDraws == 2 && d[CFG_ALL].passEncoders == 1,
              "depth clear draw %d: merges %llu clear draws %llu encoders %llu", k, (unsigned long long)d[CFG_ALL].merges,
              (unsigned long long)d[CFG_ALL].clearDraws, (unsigned long long)d[CFG_ALL].passEncoders);
        CHECK(d[CFG_MERGE_ONLY].merges == 3 && d[CFG_MERGE_ONLY].clearDraws == 2, "depth clear draw %d without fold: merges %llu",
              k, (unsigned long long)d[CFG_MERGE_ONLY].merges);
        CHECK(d[CFG_FRAGMENT_CLEARS].merges == 3 && d[CFG_FRAGMENT_CLEARS].clearDraws == 2, "depth clear draw %d, fragment clears: merges %llu",
              k, (unsigned long long)d[CFG_FRAGMENT_CLEARS].merges);
    }
    /* Exact values (all on). */
    const uint32_t previous = mcmtl_decoder_test_set_m2(gDev, M2_ALL);
    Stream s = {0};
    id<MTLTexture> depth = new_texture(MTLPixelFormatDepth32Float, 16, 16, 1, kRT);
    id<MTLTexture> color = new_texture(MTLPixelFormatRGBA8Unorm, 16, 16, 1, kRT);
    float v[18];
    quad3(v, 0, 0, 16, 16, 0.8f, 16);
    id<MTLBuffer> vb = new_buffer_with(v, sizeof v), red = color_buffer(1, 0, 0, 1);
    PassDesc p = m2_pass(color, depth);
    p.clearMask = 1;
    p.depthClear = YES;
    r_begin_pass(&s, &p);
    r_quad(&s, gDepthGE, vb, 0, red);
    r_end_pass(&s);
    p.clearMask = 0;
    p.depthValue = 0.3125f;
    r_begin_pass(&s, &p);
    r_end_pass(&s);
    id<MTLBuffer> db = r_readback(&s, depth, 0, 4);
    submit_ok(&s, "exact clear draw value");
    int bad = 0;
    for (uint32_t i = 0; i < 256; i++) bad += ((const float *)db.contents)[i] != 0.3125f;
    CHECK(bad == 0, "depth-clear draw: %d texels differ from 0.3125 (e.g. %f)", bad, fpx(db, 16, 3, 3));
    mcmtl_decoder_test_set_m2(gDev, previous);
    s_free(&s);
    printf("M2 depth-clear draws (reverse-Z GEQUAL, cull front/back + depth bias before, exact values): ok\n");
}

/* Late store actions: DontCare only when the next access in the call overwrites the attachment. */
static void test_m2_late_store(void) {
    mcmtl_decoder_stats d[M2_CONFIGS];
    run_m2_case("late store", ^NSArray<id<MTLBuffer>> *(Stream *s) {
        id<MTLTexture> color = new_texture(MTLPixelFormatRGBA8Unorm, 16, 16, 1, kRT);
        id<MTLTexture> depth = new_texture(MTLPixelFormatDepth32Float, 16, 16, 1, kRT);
        id<MTLTexture> other = new_texture(MTLPixelFormatRGBA8Unorm, 16, 16, 1, kRT);
        float v[2][18];
        quad3(v[0], 0, 0, 16, 16, 0.5f, 16);
        quad3(v[1], 4, 4, 12, 12, 0.25f, 16);
        id<MTLBuffer> vb = new_buffer_with(v, sizeof v), red = color_buffer(1, 0, 0, 1), blue = color_buffer(0, 0, 1, 1);
        const float green[4] = {0, 1, 0, 1};
        PassDesc p = m2_pass(color, depth);
        p.clearMask = 1;
        p.depthClear = YES;
        r_begin_pass(s, &p);                     /* P1: color and depth fully overwritten next: DontCare x2 */
        r_quad(s, gDepthGE, vb, 0, red);
        r_end_pass(s);
        PassDesc q = m2_pass(other, nil);        /* an unrelated pass in between */
        q.clearMask = 1;
        r_begin_pass(s, &q);
        r_end_pass(s);
        r_clear_texture(s, depth, MCMTL_ASPECT_DEPTH, 0, 1, NULL, 0.0f);
        r_clear_texture(s, color, MCMTL_ASPECT_COLOR, 0, 1, green, 0);
        p.clearMask = 0;
        p.depthClear = NO;
        r_begin_pass(s, &p);                     /* P2: read back afterwards: Store x2 */
        r_quad(s, gDepthGE, vb, 1, blue);
        r_end_pass(s);
        return @[ r_readback(s, color, 0, 4), r_readback(s, depth, 0, 4), r_readback(s, other, 0, 4) ];
    }, d);
    for (int c = 0; c < M2_CONFIGS; c++) {
        const BOOL late = (kM2Configs[c] & M2_LATE_STORE) != 0;
        CHECK(d[c].dontCareStores == (late ? 2u : 0u), "late store [%s]: DontCare %llu", kM2ConfigNames[c],
              (unsigned long long)d[c].dontCareStores);
    }

    /* Accesses that are not overwrites of the stored level: a clear of another level, a pass on another level,
     * a pass sampling the texture, a full clearing pass that also samples it. Only P0 may use DontCare. */
    run_m2_case("late store: other levels and sampling", ^NSArray<id<MTLBuffer>> *(Stream *s) {
        id<MTLTexture> t = new_texture(MTLPixelFormatRGBA8Unorm, 16, 16, 2, kRT);
        id<MTLTexture> x = new_texture(MTLPixelFormatRGBA8Unorm, 16, 16, 1, kRT);
        id<MTLTexture> mip1 = keep([t newTextureViewWithPixelFormat:MTLPixelFormatRGBA8Unorm textureType:MTLTextureType2D
                                                             levels:NSMakeRange(1, 1) slices:NSMakeRange(0, 1)]);
        float q[12], uv[24];
        quad_px(q, 4, 4, 12, 12, 16);
        quad_uv(uv, 0, 0, 16, 16, 16);
        id<MTLBuffer> vb = new_buffer_with(q, sizeof q), vbuv = new_buffer_with(uv, sizeof uv);
        id<MTLBuffer> blue = color_buffer(0, 0, 1, 1), tint = color_buffer(1, 1, 1, 1);
        const float red[4] = {1, 0, 0, 1}, green[4] = {0, 1, 0, 1}, yellow[4] = {1, 1, 0, 1};
        PassDesc p = m2_pass(t, nil);
        p.clearMask = 1;
        memcpy(p.clear[0], yellow, 16);
        r_begin_pass(s, &p);                        /* P0: next access of (t, 0) is a clear of all levels */
        r_quad(s, gSolid, vb, 0, blue);
        r_end_pass(s);
        r_clear_texture(s, t, MCMTL_ASPECT_COLOR, 0, 2, red, 0);
        p.clearMask = 0;
        r_begin_pass(s, &p);                        /* P1 on (t, 0): next comes a clear of level 1 only, */
        r_quad(s, gSolid, vb, 0, blue);
        r_end_pass(s);
        r_clear_texture(s, t, MCMTL_ASPECT_COLOR, 1, 1, green, 0);
        PassDesc p1 = m2_pass(t, nil);
        p1.levels[0] = 1;
        p1.clearMask = 1;
        r_begin_pass(s, &p1);                       /* a clearing pass on level 1,                     */
        r_end_pass(s);
        PassDesc px = m2_pass(x, nil);
        px.clearMask = 1;
        r_begin_pass(s, &px);                       /* then a pass sampling t: Store                   */
        r_tex_quad(s, vbuv, 0, t, tint);
        r_end_pass(s);
        p.clearMask = 1;
        memcpy(p.clear[0], green, 16);
        r_begin_pass(s, &p);                        /* P4 (t, 0), merged with P5                        */
        r_end_pass(s);
        p.clearMask = 0;
        r_begin_pass(s, &p);                        /* P5: next is a full clearing pass that samples t */
        r_quad(s, gSolid, vb, 0, blue);
        r_end_pass(s);
        p.clearMask = 1;
        r_begin_pass(s, &p);                        /* P6 */
        r_tex_quad(s, vbuv, 0, mip1, tint);
        r_end_pass(s);
        return @[ r_readback(s, x, 0, 4), r_readback(s, t, 0, 4), r_readback(s, t, 1, 4) ];
    }, d);
    for (int c = 0; c < M2_CONFIGS; c++) {
        const BOOL late = (kM2Configs[c] & M2_LATE_STORE) != 0;
        CHECK(d[c].dontCareStores == (late ? 1u : 0u), "late store, other levels [%s]: DontCare %llu", kM2ConfigNames[c],
              (unsigned long long)d[c].dontCareStores);
    }
    printf("M2 late store actions (DontCare before full clears, Store before reads / at the end): ok\n");
}

/* Errors inside a merged chain: an NSException between two merged passes (labels on: open encoder groups and
 * owed command-buffer pops) and a bad record inside a merged pass. The value is consumed, the work before the
 * failure is committed, and the next submit works. */
static void test_m2_failures(void) {
    const uint32_t previous = mcmtl_decoder_test_set_m2(gDev, M2_ALL);
    const uint32_t savedFlags = gDev->flags;
    for (int variant = 0; variant < 2; variant++) {
        gDev->flags |= MCMTL_DEVICE_FLAG_DEBUG_LABELS;
        gM2Labels = YES;
        Stream s = {0};
        NSArray<id<MTLBuffer>> *ignored = m2_frame_case(gDepthGE)(&s);
        (void)ignored;
        /* Find the record ordinal of the POP after Main's END_PASS (between merged passes). */
        uint64_t o = 0, ordinal = 0, target = UINT64_MAX;
        int endPasses = 0;
        while (o < s.length) {
            const mcmtl_cmd_header *h = (const mcmtl_cmd_header *)(s.data + o);
            if (h->op == MCMTL_OP_END_PASS) endPasses++;
            if (variant == 0 && endPasses == 2 && h->op == MCMTL_OP_POP_DEBUG_GROUP && target == UINT64_MAX) target = ordinal;
            if (variant == 1 && endPasses == 3 && h->op == MCMTL_OP_SET_BUFFER && target == UINT64_MAX) {
                ((mcmtl_cmd_set_buffer *)(s.data + o))->index = 20;   /* bad record inside the merged Screen effects pass */
                target = ordinal;
            }
            ordinal++;
            o += h->size;
        }
        if (variant == 0) mcmtl_decoder_test_raise_at_record(gDev, (int64_t)target);
        uint64_t value = gNextValue;
        mcmtl_submit_result res;
        int32_t rc = submit(&s, &res);
        CHECK(rc == (variant == 0 ? MCMTL_E_OBJC_EXCEPTION : MCMTL_E_BAD_STREAM) && (res.flags & MCMTL_SUBMIT_RESULT_FLAG_COMMITTED),
              "merged chain failure %d: rc %d flags 0x%x (%s)", variant, rc, res.flags, mcmtl_last_error());
        CHECK(mcmtl_status_load_u64(&gDev->status->submittedValue) >= value && gDev->currentCommandBuffer == nil,
              "merged chain failure %d: value consumed", variant);
        s_free(&s);
        gM2Labels = NO;
        gDev->flags = savedFlags;
        /* The next submit renders correctly. */
        Stream t = {0};
        NSArray<id<MTLBuffer>> *rb = m2_frame_case(gDepthGE)(&t);
        submit_ok(&t, "after a merged-chain failure");
        CHECK_PX(rb[0], 16, 8, 8, 255, 255, 255, 255, "render after a merged-chain failure");
        s_free(&t);
    }
    mcmtl_decoder_test_set_m2(gDev, previous);
    printf("M2 failures inside a merged chain (exception between passes with labels, bad record in a pass): ok\n");
}

/* PRESENT of a texture with a pending clear: the clear is encoded before the present pass. */
static void test_m2_present_source(void) {
    CAMetalLayer *layer = [CAMetalLayer layer];
    layer.device = gMtl;
    layer.pixelFormat = MTLPixelFormatBGRA8Unorm;
    layer.framebufferOnly = NO;
    layer.drawableSize = CGSizeMake(8, 8);
    MCMTLSurface *surface = [MCMTLSurface new];
    surface->owner = gDev;
    surface->layer = layer;
    surface->configuredWidth = 8;
    surface->configuredHeight = 8;
    uint8_t reference[8 * 8 * 4];
    for (int c = 0; c < M2_CONFIGS; c++) {
        const uint32_t previous = mcmtl_decoder_test_set_m2(gDev, kM2Configs[c]);
        Stream s = {0};
        id<MTLTexture> src = new_texture(MTLPixelFormatRGBA8Unorm, 8, 8, 1, kRT);
        const float teal[4] = {0, 0.5f, 0.5f, 1};
        r_clear_texture(&s, src, MCMTL_ASPECT_COLOR, 0, 1, teal, 0);
        r_present(&s, surface, src, 0);
        submit_ok(&s, "present a pending clear");
        CHECK(surface->drawable != nil, "present [%s]: no drawable", kM2ConfigNames[c]);
        if (!surface->drawable) {
            mcmtl_decoder_test_set_m2(gDev, previous);
            s_free(&s);
            continue;
        }
        id<MTLBuffer> db = new_buffer(8 * 8 * 4);
        id<MTLCommandBuffer> cb = [gDev->queue commandBuffer];
        id<MTLBlitCommandEncoder> be = [cb blitCommandEncoder];
        [be copyFromTexture:surface->drawable.texture sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0, 0, 0)
                 sourceSize:MTLSizeMake(8, 8, 1) toBuffer:db destinationOffset:0 destinationBytesPerRow:32 destinationBytesPerImage:256];
        [be endEncoding];
        [cb commit];
        [cb waitUntilCompleted];
        surface->drawable = nil;
        if (c == CFG_M1) {
            memcpy(reference, db.contents, sizeof reference);
            CHECK(near8(db.contents, 128, 128, 0, 255), "present of a cleared texture (BGRA %u %u %u)", ((uint8_t *)db.contents)[0],
                  ((uint8_t *)db.contents)[1], ((uint8_t *)db.contents)[2]);
        } else {
            CHECK(memcmp(reference, db.contents, sizeof reference) == 0, "present source [%s] differs from M1", kM2ConfigNames[c]);
        }
        mcmtl_decoder_test_set_m2(gDev, previous);
        s_free(&s);
    }
    printf("M2 present of a pending clear: ok\n");
}

/* FRAME_END submits are counted (the periodic MCMTL_DECODER_STATS log fires every 600 of them). */
static void test_m2_frame_counter(void) {
    const uint64_t frames = stats()->frames;
    mcmtl_submit_result res;
    for (int i = 0; i < 600; i++) submit(NULL, &res);
    submit_ex(NULL, NULL, MCMTL_SUBMIT_FLAG_COMMIT, &res, NULL);   /* not a frame end */
    CHECK(stats()->frames == frames + 600, "frames counted %llu", (unsigned long long)(stats()->frames - frames));
    printf("M2 frame counter (stats log every 600 frames with MCMTL_DECODER_STATS=1): ok\n");
}

/* ---------------------------------------------------------------------------------------------- */
/* Present fusion (CONTRACT.md 16.3)                                                                */
/* ---------------------------------------------------------------------------------------------- */

/* An offscreen surface like the game's: BGRA8 CAMetalLayer, opaque, `flipped` = the vertical mirror present fusion
 * needs. framebufferOnly NO lets the test read the drawable back (the decoder never samples it either way). */
static MCMTLSurface *fusion_surface(uint32_t w, uint32_t h, BOOL flipped, BOOL framebufferOnly, BOOL withDevice) {
    CAMetalLayer *layer = [CAMetalLayer layer];
    if (withDevice) layer.device = gMtl;
    layer.pixelFormat = MTLPixelFormatBGRA8Unorm;
    layer.framebufferOnly = framebufferOnly;
    layer.opaque = YES;
    layer.drawableSize = CGSizeMake(w, h);
    if (flipped) layer.affineTransform = CGAffineTransformMake(1, 0, 0, -1, 0, 0);
    MCMTLSurface *surface = [MCMTLSurface new];
    surface->owner = gDev;
    surface->layer = layer;
    surface->configuredWidth = w;
    surface->configuredHeight = h;
    return keep(surface);
}

/* The drawable PRESENT left on the surface, read back (w x h BGRA8); releases it like mcmtl_surface_present. */
static id<MTLBuffer> take_drawable(MCMTLSurface *surface, uint32_t w, uint32_t h) {
    id<CAMetalDrawable> d = surface->drawable;
    surface->drawable = nil;
    if (!d) return nil;
    id<MTLBuffer> b = new_buffer((NSUInteger)w * h * 4);
    id<MTLCommandBuffer> cb = [gDev->queue commandBuffer];
    id<MTLBlitCommandEncoder> be = [cb blitCommandEncoder];
    [be copyFromTexture:d.texture sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0, 0, 0) sourceSize:MTLSizeMake(w, h, 1)
               toBuffer:b destinationOffset:0 destinationBytesPerRow:w * 4 destinationBytesPerImage:(NSUInteger)w * h * 4];
    [be endEncoding];
    [cb commit];
    [cb waitUntilCompleted];
    return b;
}

/* Pixels where a BGRA drawable differs from an RGBA readback of the same rectangle (alpha compared iff withAlpha). */
static int count_bgra_mismatch(id<MTLBuffer> bgra, id<MTLBuffer> rgba, uint32_t w, uint32_t h, BOOL withAlpha) {
    int bad = 0;
    const uint8_t *d = bgra.contents, *r = rgba.contents;
    for (uint32_t i = 0; i < w * h; i++) {
        const uint8_t *p = d + i * 4, *q = r + i * 4;
        if (p[0] != q[2] || p[1] != q[1] || p[2] != q[0] || (withAlpha ? p[3] != q[3] : p[3] != 255)) bad++;
    }
    return bad;
}

static MCMTLPipeline *gTexBlend;   /* tex_vs + tex_fs with source-over blending (GUI), RGBA8 */

typedef struct FusionFrame {
    __unsafe_unretained id<MTLTexture> color, depth;
    __unsafe_unretained id<MTLBuffer> vb;      /* 5 depth quads (float3)                         */
    __unsafe_unretained id<MTLBuffer> guiVb;   /* 1 textured quad (x y u v)                      */
    __unsafe_unretained id<MTLBuffer> ubos[5];
    __unsafe_unretained id<MTLTexture> sampled; /* sampled by the GUI pass (a different texture) */
} FusionFrame;

/* Resources of one fusion frame (kept alive by gKeep). */
static FusionFrame fusion_frame_resources(uint32_t size) {
    FusionFrame f;
    f.color = new_texture(MTLPixelFormatRGBA8Unorm, size, size, 1, kRT);
    f.depth = new_texture(MTLPixelFormatDepth32Float, size, size, 1, kRT);
    float v[5][18];
    const float s = (float)size;
    quad3(v[0], 0, 0, s, s, 0.0f, s);                  /* sky                  */
    quad3(v[1], 0, 0, s / 2, s, 0.6f, s);              /* main left            */
    quad3(v[2], s / 2, 0, s, s, 0.3f, s);              /* main right           */
    quad3(v[3], 0, s / 2, s / 2, s, 0.2f, s);          /* hand: bottom left    */
    quad3(v[4], s / 2, s / 2, s, s, 0.1f, s);          /* effects              */
    f.vb = new_buffer_with(v, sizeof v);
    float g[24];
    quad_uv(g, s / 4, s / 8, 3 * s / 4, 5 * s / 8, s); /* GUI, off-center so a flip would show */
    f.guiVb = new_buffer_with(g, sizeof g);
    f.ubos[0] = color_buffer(0.2f, 0.4f, 0.6f, 1);
    f.ubos[1] = color_buffer(1, 0.5f, 0, 1);
    f.ubos[2] = color_buffer(0, 1, 0, 1);
    f.ubos[3] = color_buffer(0, 0, 1, 1);
    f.ubos[4] = color_buffer(1, 1, 1, 0.5f);   /* GUI tint: translucent alpha, kept in the target's alpha */
    f.sampled = new_texture(MTLPixelFormatRGBA8Unorm, 4, 4, 1, kRT);
    return f;
}

/* The vanilla frame shape ending in PRESENT of the main color (C1, upload, C2, Sky, Main, C3, Hand, Effects, C4, GUI
 * textured from another texture), like m2_frame_case. partialSky: Sky renders a partial area (no full clear). */
static void r_fusion_frame(Stream *s, const FusionFrame *f, MCMTLSurface *surface, BOOL partialSky) {
    const float red[4] = {1, 0, 0, 1}, fog[4] = {0, 0, 0.5f, 1}, purple[4] = {0.5f, 0, 0.5f, 1};
    r_clear_texture(s, f->sampled, MCMTL_ASPECT_COLOR, 0, 1, purple, 0);
    r_clear_texture(s, f->color, MCMTL_ASPECT_COLOR, 0, 1, red, 0);
    r_clear_texture(s, f->depth, MCMTL_ASPECT_DEPTH, 0, 1, NULL, 0.5f);
    r_clear_texture(s, f->color, MCMTL_ASPECT_COLOR, 0, 1, fog, 0);
    r_clear_texture(s, f->depth, MCMTL_ASPECT_DEPTH, 0, 1, NULL, 0.0f);
    PassDesc p = m2_pass(f->color, f->depth);
    PassDesc sky = p;
    if (partialSky) {
        sky.hasArea = YES;
        sky.area[0] = 0;
        sky.area[1] = 0;
        sky.area[2] = (int32_t)f->color.width - 2;
        sky.area[3] = (int32_t)f->color.height;
    }
    r_m2_begin(s, &sky, "Sky");
    r_quad(s, gDepthGE, f->vb, 0, f->ubos[0]);
    r_m2_end(s);
    r_m2_begin(s, &p, "Main");
    r_quad(s, gDepthGE, f->vb, 1, f->ubos[1]);
    r_quad(s, gDepthGE, f->vb, 2, f->ubos[1]);
    r_m2_end(s);
    r_clear_texture(s, f->depth, MCMTL_ASPECT_DEPTH, 0, 1, NULL, 0.0f);
    r_m2_begin(s, &p, "Item in hand");
    r_quad(s, gDepthCullFront, f->vb, 3, f->ubos[2]);
    r_m2_end(s);
    r_m2_begin(s, &p, "Screen effects");
    r_quad(s, gDepthGE, f->vb, 4, f->ubos[3]);
    r_m2_end(s);
    r_clear_texture(s, f->depth, MCMTL_ASPECT_DEPTH, 0, 1, NULL, 0.0f);
    r_m2_begin(s, &p, "GUI");
    r_set_pipeline(s, gTexBlend);
    r_set_vb(s, 0, f->guiVb, 0);
    r_set_buffer(s, 1, MCMTL_STAGE_FRAGMENT, f->ubos[4], 0);
    r_set_texture(s, 0, MCMTL_STAGE_FRAGMENT, f->sampled, gNearest);
    r_draw(s, 6, 1, 0, 0);
    r_m2_end(s);
    r_present(s, surface, f->color, 0);
}

static mcmtl_decoder_stats fusion_submit(Stream *s, mcmtl_submit_result *res, const char *what) {
    const mcmtl_decoder_stats before = *stats();
    int32_t rc = submit(s, res);
    CHECK(rc == MCMTL_OK && (res->flags & MCMTL_SUBMIT_RESULT_FLAG_COMMITTED), "%s: rc %d flags 0x%x (%s)", what, rc, res->flags,
          mcmtl_last_error());
    s_reset(s);
    return stats_delta(&before);
}

static uint64_t fallbacks(const mcmtl_decoder_stats *d) {
    uint64_t n = 0;
    for (int r = 0; r < FR_COUNT; r++) n += d->fusionFallback[r];
    return n;
}

/* Reference: the same frame without fusion (mode 0), main color and depth read back after PRESENT. */
static NSArray<id<MTLBuffer>> *fusion_reference(uint32_t size, uint32_t m2) {
    const uint32_t prevM2 = mcmtl_decoder_test_set_m2(gDev, m2);
    const uint32_t prevMode = mcmtl_decoder_test_set_fusion(gDev, 0, 0, 0);
    Stream s = {0};
    FusionFrame f = fusion_frame_resources(size);
    MCMTLSurface *surface = fusion_surface(size, size, YES, NO, YES);
    r_fusion_frame(&s, &f, surface, NO);
    id<MTLBuffer> color = r_readback(&s, f.color, 0, 4), depth = r_readback(&s, f.depth, 0, 4);
    mcmtl_submit_result res;
    mcmtl_decoder_stats d = fusion_submit(&s, &res, "fusion reference");
    CHECK(d.fusedFrames == 0 && fallbacks(&d) == 0, "mode 0: no fusion accounting (%llu fused)", (unsigned long long)d.fusedFrames);
    /* Mode 0 with a flipped layer: the present pass keeps memory order (straight copy, alpha 1). */
    id<MTLBuffer> drawable = take_drawable(surface, size, size);
    CHECK(drawable && count_bgra_mismatch(drawable, color, size, size, NO) == 0, "flipped-layer present pass is a straight copy");
    s_free(&s);
    mcmtl_decoder_test_set_fusion(gDev, prevMode, 0, 0);
    mcmtl_decoder_test_set_m2(gDev, prevM2);
    return @[ color, depth ];
}

/* Fused frame vs the reference: drawable bytes == main target bytes (alpha included), then the next submit reads the
 * stale main target back (restore from the drawable) and checks it byte for byte, plus the depth. */
static void test_fusion_frames(void) {
    const uint32_t size = 16;
    const uint32_t configs[] = { M2_ALL, M2_FOLD | M2_LATE_STORE, M2_ALL & ~M2_HOIST, M2_FOLD, M2_MERGE | M2_HOIST | M2_CLEAR_DRAW, 0 };
    const char *names[] = { "all", "fold+late (unmerged)", "no hoist", "fold only", "merge without fold", "M1" };
    for (int framebufferOnly = 0; framebufferOnly < 2; framebufferOnly++) {
        for (size_t c = 0; c < sizeof configs / sizeof configs[0]; c++) {
            if (framebufferOnly && c > 0) break;   /* production layer: one config is enough (restore path) */
            NSArray<id<MTLBuffer>> *ref = fusion_reference(size, configs[c]);
            const uint32_t prevM2 = mcmtl_decoder_test_set_m2(gDev, configs[c]);
            const uint32_t prevMode = mcmtl_decoder_test_set_fusion(gDev, 1, FUSE_TEST_SYNC_VARIANTS, 0);
            Stream s = {0};
            FusionFrame f = fusion_frame_resources(size);
            MCMTLSurface *surface = fusion_surface(size, size, YES, framebufferOnly != 0, YES);
            r_fusion_frame(&s, &f, surface, NO);
            mcmtl_submit_result res;
            mcmtl_decoder_stats d = fusion_submit(&s, &res, names[c]);
            const BOOL expectFused = (configs[c] & M2_FOLD) != 0;   /* without FOLD no pass clears the main target */
            CHECK((res.flags & MCMTL_SUBMIT_RESULT_FLAG_PRESENT_ENCODED) && res.drawableWidth == size,
                  "[%s] present flags 0x%x", names[c], res.flags);
            CHECK(d.fusedFrames == (expectFused ? 1u : 0u) && fallbacks(&d) == (expectFused ? 0u : 1u),
                  "[%s] fused %llu, fallbacks %llu (no-clear %llu)", names[c], (unsigned long long)d.fusedFrames,
                  (unsigned long long)fallbacks(&d), (unsigned long long)d.fusionFallback[FR_NO_CLEAR]);
            /* M1 has no look-ahead index; merge without fold indexes but no pass clears the main target. */
            if (!expectFused) CHECK(d.fusionFallback[configs[c] ? FR_NO_CLEAR : FR_NO_INDEX] == 1, "[%s] fallback reason", names[c]);
            if (expectFused) {
                const BOOL merged = (configs[c] & M2_MERGE) && (configs[c] & M2_CLEAR_DRAW);
                /* Merged: one encoder (Sky..GUI) into the drawable, no present pass; unmerged: 5 passes into it. The
                 * other render encoder is the clear of the GUI's sampled texture. */
                CHECK(d.fusedPasses == (merged ? 1u : 5u), "[%s] passes into the drawable %llu", names[c],
                      (unsigned long long)d.fusedPasses);
                if (merged) CHECK(d.renderEncoders == 2, "[%s] render encoders %llu (no present pass)", names[c],
                                  (unsigned long long)d.renderEncoders);
                /* Merged: the sampled texture's clear is materialized before the chain and committed early, before
                 * nextDrawable; unmerged it is materialized at the GUI pass (nothing to commit early). */
                CHECK(d.untimedCommits == (merged ? 1u : 0u) && d.commandBuffersCommitted == d.untimedCommits + 1,
                      "[%s] early commit before the drawable: %llu/%llu", names[c], (unsigned long long)d.untimedCommits,
                      (unsigned long long)d.commandBuffersCommitted);
                CHECK(mcmtl_decoder_test_fusion_stale(gDev), "[%s] main target left stale", names[c]);
            }
            if (!framebufferOnly) {
                id<MTLBuffer> drawable = take_drawable(surface, size, size);
                int bad = drawable ? count_bgra_mismatch(drawable, ref[0], size, size, expectFused) : -1;
                CHECK(bad == 0, "[%s] drawable differs from the unfused main target in %d pixels", names[c], bad);
            } else {
                surface->drawable = nil;
            }
            /* Next submit: screenshot-style readback of the main target first (between frames). */
            id<MTLBuffer> color = r_readback(&s, f.color, 0, 4), depth = r_readback(&s, f.depth, 0, 4);
            d = fusion_submit(&s, &res, "readback after the fused frame");
            CHECK(d.fusionRestores == (expectFused ? 1u : 0u), "[%s] restores %llu", names[c], (unsigned long long)d.fusionRestores);
            CHECK(!mcmtl_decoder_test_fusion_stale(gDev), "[%s] stale state released", names[c]);
            CHECK(memcmp(color.contents, ref[0].contents, size * size * 4) == 0, "[%s%s] main target after restore differs",
                  names[c], framebufferOnly ? ", framebufferOnly drawable" : "");
            CHECK(memcmp(depth.contents, ref[1].contents, size * size * 4) == 0, "[%s] depth differs", names[c]);
            s_free(&s);
            mcmtl_decoder_test_set_fusion(gDev, prevMode, 0, 0);
            mcmtl_decoder_test_set_m2(gDev, prevM2);
        }
    }
    printf("present fusion: fused frames == unfused bytes (merged / unmerged / M1 fallback), restore of the stale target: ok\n");
}

/* Raw mcmtl_submit that returns at once (no completion wait); returns the committed value (0 without COMMIT). */
static uint64_t submit_nowait(Stream *main, uint32_t flags, mcmtl_submit_result *out) {
    mcmtl_submit_info info;
    memset(&info, 0, sizeof info);
    info.structSize = sizeof info;
    info.flags = flags;
    info.stream = main ? main->data : NULL;
    info.streamLength = main ? main->length : 0;
    uint64_t value = 0;
    if (flags & MCMTL_SUBMIT_FLAG_COMMIT) info.signalValue = value = gNextValue++;
    memset(out, 0, sizeof *out);
    out->structSize = sizeof *out;
    int32_t rc = mcmtl_submit(gDevHandle, &info, out);
    CHECK(rc == MCMTL_OK, "submit_nowait: rc %d (%s)", rc, mcmtl_last_error());
    return value;
}

/* Regression for the in-game GPU hang of round 3 (scene runner: "5s timeout reached when waiting for the GPU" after a
 * screenshot that followed a fused frame). The restore of the stale main target used to attach the previous fused
 * frame's drawable as a render target (Load/Store, programmable blending). That drawable's present is registered:
 * full-screen on the direct-to-display path it is on screen, and a render pass on it blocks on the GPU until the display
 * releases the surface, which needs a later present queued behind it; the GPU watchdog then kills the command buffer
 * (kIOGPUCommandBufferCallbackErrorTimeout) and the frame's timeline value is never signalled. An offscreen drawable is
 * never on a display, so the hang cannot happen here; the test checks the rule instead: after its present, a drawable
 * is never attached to a render pass again, and the restore (which reads the drawable's IOSurface) is still
 * byte-exact while the fused frame has not run yet (the GPU queue is held: the restore waits on the GPU for the fused
 * frame's timeline value). Also: a frame submitted without COMMIT is never fused (its value is unknown). */
static void test_fusion_restore_presented_drawable(void) {
    const uint32_t size = 64;
    NSArray<id<MTLBuffer>> *ref = fusion_reference(size, M2_ALL);
    const uint32_t prevM2 = mcmtl_decoder_test_set_m2(gDev, M2_ALL);
    const uint32_t prevMode = mcmtl_decoder_test_set_fusion(gDev, 1, FUSE_TEST_SYNC_VARIANTS, 0);
    for (int held = 0; held < 2; held++) {
        Stream s = {0};
        FusionFrame f = fusion_frame_resources(size);
        MCMTLSurface *surface = fusion_surface(size, size, YES, YES, YES);   /* framebufferOnly, like the game's layer */
        /* held: the queue runs nothing until the test lets it, so the restore is encoded before the fused frame ran. */
        id<MTLSharedEvent> gate = held ? [gMtl newSharedEvent] : nil;
        if (gate) {
            id<MTLCommandBuffer> cb = [gDev->queue commandBuffer];
            [cb encodeWaitForEvent:gate value:1];
            [cb commit];
        }
        r_fusion_frame(&s, &f, surface, NO);
        mcmtl_submit_result res;
        const mcmtl_decoder_stats before = *stats();
        const uint64_t fusedValue = submit_nowait(&s, MCMTL_SUBMIT_FLAG_COMMIT | MCMTL_SUBMIT_FLAG_FRAME_END, &res);
        s_reset(&s);
        mcmtl_decoder_stats d = stats_delta(&before);
        CHECK(d.fusedFrames == 1 && (res.flags & MCMTL_SUBMIT_RESULT_FLAG_PRESENT_ENCODED), "[held %d] fused %llu, flags 0x%x",
              held, (unsigned long long)d.fusedFrames, res.flags);
        id<CAMetalDrawable> presented = surface->drawable;
        __unsafe_unretained id<MTLTexture> presentedTexture = presented.texture;
        surface->drawable = nil;   /* mcmtl_surface_present */
        CHECK(presentedTexture != nil, "[held %d] presented drawable", held);
        if (gate) CHECK(gDev->event.signaledValue < fusedValue, "[held %d] the fused frame is held back", held);

        /* Next call: screenshot-style readback of the stale main target first. */
        __block int passes = 0, attached = 0;
        mcmtl_decoder_test_on_render_pass(gDev, ^(MTLRenderPassDescriptor *desc) {
            passes++;
            for (NSUInteger i = 0; i < 8; i++) {
                if (desc.colorAttachments[i].texture == presentedTexture) attached++;
            }
        });
        id<MTLBuffer> color = r_readback(&s, f.color, 0, 4);
        const mcmtl_decoder_stats before2 = *stats();
        const uint64_t readValue = submit_nowait(&s, MCMTL_SUBMIT_FLAG_COMMIT | MCMTL_SUBMIT_FLAG_FRAME_END, &res);
        s_reset(&s);
        mcmtl_decoder_test_on_render_pass(gDev, nil);
        d = stats_delta(&before2);
        if (gate) gate.signaledValue = 1;
        int32_t w = mcmtl_wait_completed(gDev, readValue, 5000000000ull);
        CHECK(w == MCMTL_OK && wait_prefix(readValue), "[held %d] readback call completes (%d: %s)", held, w, mcmtl_last_error());
        CHECK(d.fusionRestores == 1 && passes == 1, "[held %d] restores %llu, render passes %d", held,
              (unsigned long long)d.fusionRestores, passes);
        CHECK(attached == 0, "[held %d] the presented drawable was attached to %d render pass(es) after its present", held, attached);
        CHECK(memcmp(color.contents, ref[0].contents, (size_t)size * size * 4) == 0, "[held %d] main target after restore differs",
              held);
        s_free(&s);
        presented = nil;
    }

    /* Without COMMIT the frame is presented through the present pass, not fused. */
    {
        Stream s = {0};
        FusionFrame f = fusion_frame_resources(size);
        MCMTLSurface *surface = fusion_surface(size, size, YES, YES, YES);
        r_fusion_frame(&s, &f, surface, NO);
        mcmtl_submit_result res;
        const mcmtl_decoder_stats before = *stats();
        int32_t rc = submit_ex(NULL, &s, 0, &res, "frame without COMMIT");
        mcmtl_decoder_stats d = stats_delta(&before);
        CHECK(rc == MCMTL_OK && d.fusedFrames == 0 && d.fusionFallback[FR_NO_INDEX] == 1 && !mcmtl_decoder_test_fusion_stale(gDev),
              "no COMMIT: fused %llu, no-index fallbacks %llu", (unsigned long long)d.fusedFrames,
              (unsigned long long)d.fusionFallback[FR_NO_INDEX]);
        s_reset(&s);
        submit_ex(NULL, NULL, MCMTL_SUBMIT_FLAG_COMMIT | MCMTL_SUBMIT_FLAG_FRAME_END, &res, "commit it");
        surface->drawable = nil;
        s_free(&s);
    }
    mcmtl_decoder_test_set_fusion(gDev, prevMode, 0, 0);
    mcmtl_decoder_test_set_m2(gDev, prevM2);
    printf("present fusion restore: never attaches a presented drawable, waits for the fused frame, exact; no fusion without COMMIT: ok\n");
}

static void test_fusion_fallbacks(void) {
    const uint32_t size = 16;
    const uint32_t prevMode = mcmtl_decoder_test_set_fusion(gDev, 1, FUSE_TEST_SYNC_VARIANTS, 0);
    const uint32_t prevM2 = mcmtl_decoder_test_set_m2(gDev, M2_ALL);
    NSArray<id<MTLBuffer>> *ref = fusion_reference(size, M2_ALL);
    Stream s = {0};
    mcmtl_submit_result res;
    mcmtl_decoder_stats d;

    /* Drawable size != source: present pass, flipped orientation: drawable rows [0, 12) = source rows [0, 12)
     * (shown at the top of the window), columns 16..19 cleared black. */
    {
        FusionFrame f = fusion_frame_resources(size);
        MCMTLSurface *surface = fusion_surface(20, 12, YES, NO, YES);
        r_fusion_frame(&s, &f, surface, NO);
        d = fusion_submit(&s, &res, "size mismatch");
        CHECK(d.fusedFrames == 0 && d.fusionFallback[FR_SIZE] == 1, "size mismatch: fused %llu, size fallbacks %llu",
              (unsigned long long)d.fusedFrames, (unsigned long long)d.fusionFallback[FR_SIZE]);
        id<MTLBuffer> db = take_drawable(surface, 20, 12);
        int bad = 0;
        for (uint32_t y = 0; y < 12 && db; y++)
            for (uint32_t x = 0; x < 20; x++) {
                const uint8_t *p = px(db, 20, x, y);
                if (x < 16) {
                    const uint8_t *q = px(ref[0], 16, x, y);
                    if (p[0] != q[2] || p[1] != q[1] || p[2] != q[0] || p[3] != 255) bad++;
                } else if (!near8(p, 0, 0, 0, 255)) {
                    bad++;
                }
            }
        CHECK(db && bad == 0, "size mismatch: %d wrong drawable pixels", bad);
        CHECK(!mcmtl_decoder_test_fusion_stale(gDev), "size mismatch: nothing stale");
    }
    /* A readback of the source after PRESENT (Tracy capture): not fused, readback correct. */
    {
        FusionFrame f = fusion_frame_resources(size);
        MCMTLSurface *surface = fusion_surface(size, size, YES, NO, YES);
        r_fusion_frame(&s, &f, surface, NO);
        id<MTLBuffer> after = r_readback(&s, f.color, 0, 4);
        d = fusion_submit(&s, &res, "readback after PRESENT");
        CHECK(d.fusedFrames == 0 && d.fusionFallback[FR_SOURCE_USE] == 1, "later readback: fused %llu, source-use %llu",
              (unsigned long long)d.fusedFrames, (unsigned long long)d.fusionFallback[FR_SOURCE_USE]);
        CHECK(memcmp(after.contents, ref[0].contents, size * size * 4) == 0, "later readback content");
        id<MTLBuffer> db = take_drawable(surface, size, size);
        CHECK(db && count_bgra_mismatch(db, ref[0], size, size, NO) == 0, "later readback: drawable (present pass)");
    }
    /* The first pass on the source covers a partial area: no provable full overwrite, not fused. */
    {
        FusionFrame f = fusion_frame_resources(size);
        MCMTLSurface *surface = fusion_surface(size, size, YES, NO, YES);
        r_fusion_frame(&s, &f, surface, YES);
        d = fusion_submit(&s, &res, "partial first pass");
        CHECK(d.fusedFrames == 0 && d.fusionFallback[FR_NO_CLEAR] == 1, "partial first pass: fused %llu, no-clear %llu",
              (unsigned long long)d.fusedFrames, (unsigned long long)d.fusionFallback[FR_NO_CLEAR]);
        surface->drawable = nil;
    }
    /* The source is sampled after its clearing pass (menu blur: GUI before blur, a pass on another target sampling
     * the main target, GUI after blur loading it): not fused (the last pass loads, nothing clears after the read). */
    {
        FusionFrame f = fusion_frame_resources(size);
        MCMTLSurface *surface = fusion_surface(size, size, YES, NO, YES);
        const float fog[4] = {0, 0, 0.5f, 1};
        id<MTLTexture> blur = new_texture(MTLPixelFormatRGBA8Unorm, size, size, 1, kRT);
        r_clear_texture(&s, f.color, MCMTL_ASPECT_COLOR, 0, 1, fog, 0);
        PassDesc p = m2_pass(f.color, nil);
        r_begin_pass(&s, &p);
        r_end_pass(&s);
        PassDesc b = m2_pass(blur, nil);
        r_begin_pass(&s, &b);
        r_set_pipeline(&s, gTex);
        r_set_vb(&s, 0, f.guiVb, 0);
        r_set_buffer(&s, 1, MCMTL_STAGE_FRAGMENT, f.ubos[4], 0);
        r_set_texture(&s, 0, MCMTL_STAGE_FRAGMENT, f.color, gNearest);
        r_draw(&s, 6, 1, 0, 0);
        r_end_pass(&s);
        r_begin_pass(&s, &p);
        r_end_pass(&s);
        r_present(&s, surface, f.color, 0);
        d = fusion_submit(&s, &res, "sampled after the clear");
        CHECK(d.fusedFrames == 0 && d.fusionFallback[FR_NO_CLEAR] == 1, "sampled source: fused %llu, no-clear %llu (source-use %llu)",
              (unsigned long long)d.fusedFrames, (unsigned long long)d.fusionFallback[FR_NO_CLEAR],
              (unsigned long long)d.fusionFallback[FR_SOURCE_USE]);
        surface->drawable = nil;
    }
    /* nextDrawable returns nil (layer without device): DRAWABLE_NIL, the main target is rendered normally. */
    {
        FusionFrame f = fusion_frame_resources(size);
        MCMTLSurface *surface = fusion_surface(size, size, YES, NO, NO);
        r_fusion_frame(&s, &f, surface, NO);
        d = fusion_submit(&s, &res, "nil drawable");
        CHECK((res.flags & MCMTL_SUBMIT_RESULT_FLAG_DRAWABLE_NIL) && !(res.flags & MCMTL_SUBMIT_RESULT_FLAG_PRESENT_ENCODED),
              "nil drawable: flags 0x%x", res.flags);
        CHECK(d.fusedFrames == 0 && d.fusionFallback[FR_DRAWABLE_NIL] == 1 && d.fusedPasses == 0, "nil drawable: fallback reason");
        CHECK(!mcmtl_decoder_test_fusion_stale(gDev), "nil drawable: nothing stale");
        id<MTLBuffer> color = r_readback(&s, f.color, 0, 4);
        fusion_submit(&s, &res, "nil drawable readback");
        CHECK(memcmp(color.contents, ref[0].contents, size * size * 4) == 0, "nil drawable: main target rendered");
    }
    /* Upright layer (MCMTL_PRESENT_FUSION=0 surface): not fused, classic flipped present pass. */
    {
        FusionFrame f = fusion_frame_resources(size);
        MCMTLSurface *surface = fusion_surface(size, size, NO, NO, YES);
        r_fusion_frame(&s, &f, surface, NO);
        d = fusion_submit(&s, &res, "upright layer");
        CHECK(d.fusedFrames == 0 && d.fusionFallback[FR_NOT_FLIPPED] == 1, "upright layer: not-flipped fallback");
        id<MTLBuffer> db = take_drawable(surface, size, size);
        int bad = 0;
        for (uint32_t y = 0; y < size && db; y++)
            for (uint32_t x = 0; x < size; x++) {
                const uint8_t *p = px(db, size, x, y), *q = px(ref[0], size, x, size - 1 - y);
                if (p[0] != q[2] || p[1] != q[1] || p[2] != q[0] || p[3] != 255) bad++;
            }
        CHECK(db && bad == 0, "upright layer: classic flip, %d wrong pixels", bad);
    }
    /* Flip-only diagnostics (mode 2): flipped layer, present pass. */
    {
        mcmtl_decoder_test_set_fusion(gDev, 2, FUSE_TEST_SYNC_VARIANTS, 0);
        FusionFrame f = fusion_frame_resources(size);
        MCMTLSurface *surface = fusion_surface(size, size, YES, NO, YES);
        r_fusion_frame(&s, &f, surface, NO);
        d = fusion_submit(&s, &res, "flip only");
        CHECK(d.fusedFrames == 0 && d.fusionFallback[FR_DISABLED] == 1, "flip only: disabled fallback");
        id<MTLBuffer> db = take_drawable(surface, size, size);
        CHECK(db && count_bgra_mismatch(db, ref[0], size, size, NO) == 0, "flip only: straight copy");
        mcmtl_decoder_test_set_fusion(gDev, 1, FUSE_TEST_SYNC_VARIANTS, 0);
    }
    /* Stale target overwritten first by the next frame (vanilla: the frame-start clear): dropped, no restore. Then a
     * FRAME_END submit that never references a stale target drops it too. */
    {
        FusionFrame f = fusion_frame_resources(size);
        MCMTLSurface *surface = fusion_surface(size, size, YES, YES, YES);
        r_fusion_frame(&s, &f, surface, NO);
        d = fusion_submit(&s, &res, "fused frame 1");
        CHECK(d.fusedFrames == 1 && mcmtl_decoder_test_fusion_stale(gDev), "fused frame 1");
        surface->drawable = nil;
        r_fusion_frame(&s, &f, surface, NO);
        d = fusion_submit(&s, &res, "fused frame 2");
        CHECK(d.fusedFrames == 1 && d.fusionStaleDropped == 1 && d.fusionRestores == 0, "frame 2: stale dropped %llu, restores %llu",
              (unsigned long long)d.fusionStaleDropped, (unsigned long long)d.fusionRestores);
        surface->drawable = nil;
        submit_ex(NULL, &s, MCMTL_SUBMIT_FLAG_COMMIT, &res, NULL);   /* flush-style call, no FRAME_END: kept */
        CHECK(mcmtl_decoder_test_fusion_stale(gDev), "non-frame call keeps the stale state");
        d = fusion_submit(&s, &res, "empty frame");
        CHECK(d.fusionStaleDropped == 1 && !mcmtl_decoder_test_fusion_stale(gDev), "unreferenced for a frame: dropped");
    }
    /* Adaptive guard: with a 1 ns budget the first fused frame's wait turns fusion off for a while. */
    {
        mcmtl_decoder_test_set_fusion(gDev, 1, FUSE_TEST_SYNC_VARIANTS, 1);
        FusionFrame f = fusion_frame_resources(size);
        MCMTLSurface *surface = fusion_surface(size, size, YES, YES, YES);
        r_fusion_frame(&s, &f, surface, NO);
        d = fusion_submit(&s, &res, "guard frame 1");
        CHECK(d.fusedFrames == 1 && d.fusionBackoffs == 1, "guard: fused %llu, backoffs %llu", (unsigned long long)d.fusedFrames,
              (unsigned long long)d.fusionBackoffs);
        surface->drawable = nil;
        r_fusion_frame(&s, &f, surface, NO);
        d = fusion_submit(&s, &res, "guard frame 2");
        CHECK(d.fusedFrames == 0 && d.fusionFallback[FR_BACKOFF] == 1, "guard: backoff fallback");
        surface->drawable = nil;
        mcmtl_decoder_test_set_fusion(gDev, 1, FUSE_TEST_SYNC_VARIANTS, 0);
    }
    /* BGRA8 variants compile asynchronously: the first frame falls back, a later one fuses. */
    {
        mcmtl_decoder_test_set_fusion(gDev, 1, 0, 0);
        PipeSpec dspec = { .name = "fusion async", .vs = "depth_vs", .fs = "ubo_fs", .colorCount = 1,
                           .colorFormats = { MTLPixelFormatRGBA8Unorm }, .attrCount = 1, .attrs = { { 0, MTLVertexFormatFloat3, 0, 0 } },
                           .layoutCount = 1, .layouts = { { 0, 12, 0 } }, .bufferMask = { 0, 1u << 2 },
                           .compare = MTLCompareFunctionGreaterEqual, .depthWrite = YES };
        MCMTLPipeline *fresh = keep(make_pipeline(&dspec));
        add_variant(fresh, 1, MTLPixelFormatDepth32Float);
        FusionFrame f = fusion_frame_resources(size);
        MCMTLSurface *surface = fusion_surface(size, size, YES, NO, YES);
        MCMTLPipeline *saved = gDepthGE;
        gDepthGE = fresh;
        r_fusion_frame(&s, &f, surface, NO);
        d = fusion_submit(&s, &res, "async variants frame 1");
        CHECK(d.fusedFrames == 0 && d.fusionFallback[FR_VARIANTS] == 1 && d.fusionVariantBuilds >= 1,
              "async: first frame falls back (variants %llu, builds %llu)", (unsigned long long)d.fusionFallback[FR_VARIANTS],
              (unsigned long long)d.fusionVariantBuilds);
        surface->drawable = nil;
        const uint64_t key = mcmtl_variant_key(1, MTLPixelFormatDepth32Float) | MCMTL_VARIANT_KEY_BGRA8_COLOR0;
        for (int i = 0; i < 500 && !mcmtl_pipeline_find_variant(fresh, key); i++) {
            usleep(10000);
            submit_ex(NULL, NULL, MCMTL_SUBMIT_FLAG_COMMIT, &res, NULL);   /* the drain happens at the start of a call */
        }
        CHECK(mcmtl_pipeline_find_variant(fresh, key) != nil, "async: BGRA8 variant arrived");
        r_fusion_frame(&s, &f, surface, NO);
        d = fusion_submit(&s, &res, "async variants frame 2");
        CHECK(d.fusedFrames == 1, "async: a later frame fuses (fallbacks %llu)", (unsigned long long)fallbacks(&d));
        id<MTLBuffer> db = take_drawable(surface, size, size);
        CHECK(db && count_bgra_mismatch(db, ref[0], size, size, YES) == 0, "async: fused drawable bytes");
        gDepthGE = saved;
        mcmtl_decoder_test_set_fusion(gDev, 1, FUSE_TEST_SYNC_VARIANTS, 0);
        r_readback(&s, f.color, 0, 4);
        fusion_submit(&s, &res, "async: settle");
    }
    s_free(&s);
    mcmtl_decoder_test_set_m2(gDev, prevM2);
    mcmtl_decoder_test_set_fusion(gDev, prevMode, 0, 400000ull);
    printf("present fusion fallbacks (size, later readback, partial pass, sampled source, nil drawable, upright layer, "
           "flip only, stale drop, adaptive guard, async variants): ok\n");
}

/* The layer flip of mcmetal_surface.m: survives AppKit-style geometry resets, follows bounds, and is removable. */
static void test_fusion_layer_flip(void) {
    CAMetalLayer *layer = [CAMetalLayer layer];
    Class original = object_getClass(layer);
    layer.anchorPoint = CGPointMake(0, 0);
    layer.bounds = CGRectMake(0, 0, 10, 8);
    mcmtl_layer_set_flipped(layer, YES);
    CHECK(mcmtl_layer_is_flipped(layer) && layer.affineTransform.ty == 8.0, "flip installed (ty %.1f)", layer.affineTransform.ty);
    layer.bounds = CGRectMake(0, 0, 10, 12);
    layer.affineTransform = CGAffineTransformMake(1, 0, 0, 1, 0, 0);   /* what AppKit does on a resize */
    CHECK(mcmtl_layer_is_flipped(layer) && layer.affineTransform.ty == 12.0, "flip kept across resize (ty %.1f)",
          layer.affineTransform.ty);
    layer.transform = CATransform3DIdentity;
    layer.anchorPoint = CGPointMake(0.5, 0.5);
    CHECK(mcmtl_layer_is_flipped(layer) && layer.affineTransform.ty == 0.0, "centered anchor: ty %.1f", layer.affineTransform.ty);
    mcmtl_layer_set_flipped(layer, NO);
    CHECK(!mcmtl_layer_is_flipped(layer) && object_getClass(layer) == original, "flip removed, class restored");
    layer.affineTransform = CGAffineTransformMake(1, 0, 0, -1, 0, 0);
    CHECK(mcmtl_layer_is_flipped(layer), "plain transforms work again");
    printf("present fusion layer flip (AppKit resets, bounds, anchor, removal): ok\n");
}

/* ---------------------------------------------------------------------------------------------- */
/* Mailbox presentation (CONTRACT.md 16.4)                                                          */
/* ---------------------------------------------------------------------------------------------- */

/* Acquisitions started by a fake drawable source (shared with its block, which outlives the test locals). */
@interface MBCounter : NSObject {
@public
    _Atomic int calls;
}
@end
@implementation MBCounter
@end

/* A mailbox surface whose acquirer thread takes a real drawable from the offscreen layer only once the test hands out
 * a permit: the test decides when a drawable "comes back from the display". `permits` nil = the real nextDrawable. */
typedef struct MBRig {
    __unsafe_unretained MCMTLSurface *surface;          /* kept by gKeep */
    __unsafe_unretained dispatch_semaphore_t permits;   /* kept by gKeep */
    __unsafe_unretained MBCounter *counter;             /* kept by gKeep */
} MBRig;

static MBRig mb_rig(uint32_t size, BOOL fake, uint32_t maxQueued, uint64_t queueTimeoutNs) {
    MBRig rig;
    rig.surface = fusion_surface(size, size, YES, NO, YES);
    rig.permits = fake ? keep(dispatch_semaphore_create(0)) : nil;
    rig.counter = keep([MBCounter new]);
    dispatch_semaphore_t permits = rig.permits;
    MBCounter *counter = rig.counter;
    id<CAMetalDrawable> (^acquire)(CAMetalLayer *) = nil;
    if (fake) {
        acquire = ^id<CAMetalDrawable>(CAMetalLayer *layer) {
            atomic_fetch_add(&counter->calls, 1);
            if (dispatch_semaphore_wait(permits, dispatch_time(DISPATCH_TIME_NOW, 2 * NSEC_PER_SEC)) != 0) return nil;
            return [layer nextDrawable];
        };
    }
    mcmtl_mailbox_test_enable(rig.surface, YES, maxQueued, queueTimeoutNs, acquire);
    return rig;
}

static mcmtl_mailbox_stats mb_stats(MCMTLSurface *surface) {
    mcmtl_mailbox_stats m;
    mcmtl_mailbox_get_stats(surface, &m);
    return m;
}

/* Waits until the queue gate counts no present as waiting for the display (offscreen layers run the presented
 * handlers at the next refresh). */
static BOOL mb_wait_handled(MCMTLSurface *surface) {
    for (int i = 0; i < 2000; i++) {
        if (mb_stats(surface).presentsPending == 0) return YES;
        usleep(1000);
    }
    return NO;
}

static BOOL mb_wait_thread(MCMTLSurface *surface, BOOL running) {
    for (int i = 0; i < 3000; i++) {
        if ((mb_stats(surface).threadRunning != 0) == running) return YES;
        usleep(1000);
    }
    return NO;
}

/* Hands out one drawable and waits until the acquirer parked it. */
static BOOL mb_release_one(MBRig *rig) {
    dispatch_semaphore_signal(rig->permits);
    return mcmtl_mailbox_test_wait_ready(rig->surface, 2000000000ull);
}

/* Shuts the rig down like mcmtl_surface_destroy and waits for its acquirer thread to exit. */
static BOOL mb_shutdown(MBRig *rig) {
    mcmtl_mailbox_test_shutdown(rig->surface);
    if (rig->permits) for (int i = 0; i < 4; i++) dispatch_semaphore_signal(rig->permits);   /* unblock the fake source */
    return mb_wait_thread(rig->surface, NO);
}

/* Frames submitted like the game's: COMMIT | FRAME_END, the drawable released after each submit (mcmtl_surface_present),
 * two frames in flight (the throttle waits for frame N-2's GPU signal, not for its completion handler, which Core
 * Animation holds until the present is processed). Returns the wall time in ms. */
static double mb_run_frames(Stream *s, const FusionFrame *f, MCMTLSurface *surface, uint32_t frames, uint32_t *presented,
                            uint32_t *nils) {
    *presented = *nils = 0;
    const uint64_t t0 = clock_gettime_nsec_np(CLOCK_UPTIME_RAW);
    for (uint32_t i = 0; i < frames; i++) {
        r_fusion_frame(s, f, surface, NO);
        mcmtl_submit_info info;
        memset(&info, 0, sizeof info);
        info.structSize = sizeof info;
        info.flags = MCMTL_SUBMIT_FLAG_COMMIT | MCMTL_SUBMIT_FLAG_FRAME_END;
        info.stream = s->data;
        info.streamLength = s->length;
        info.signalValue = gNextValue++;
        mcmtl_submit_result res;
        memset(&res, 0, sizeof res);
        res.structSize = sizeof res;
        const int32_t rc = mcmtl_submit(gDevHandle, &info, &res);
        CHECK(rc == MCMTL_OK && (res.flags & MCMTL_SUBMIT_RESULT_FLAG_COMMITTED), "frame %u: rc %d flags 0x%x", i, rc, res.flags);
        if (res.flags & MCMTL_SUBMIT_RESULT_FLAG_PRESENT_ENCODED) (*presented)++;
        if (res.flags & MCMTL_SUBMIT_RESULT_FLAG_DRAWABLE_NIL) (*nils)++;
        surface->drawable = nil;
        s_reset(s);
        if (info.signalValue > 2) mcmtl_wait_completed(gDev, info.signalValue - 2, 5000000000ull);
    }
    const double ms = (double)(clock_gettime_nsec_np(CLOCK_UPTIME_RAW) - t0) / 1e6;
    CHECK(mcmtl_wait_completed(gDev, gNextValue - 1, 5000000000ull) == MCMTL_OK && wait_prefix(gNextValue - 1), "frames completed");
    return ms;
}

/* Main-target readback in a submit of its own (screenshot-style, between frames: restores a fused frame's target). */
static id<MTLBuffer> mb_readback(Stream *s, id<MTLTexture> t) {
    id<MTLBuffer> b = r_readback(s, t, 0, 4);
    mcmtl_submit_result res;
    fusion_submit(s, &res, "mailbox readback");
    return b;
}

static void test_mailbox(void) {
    const uint32_t size = 16;
    const uint32_t prevMode = mcmtl_decoder_test_set_fusion(gDev, 1, FUSE_TEST_SYNC_VARIANTS, 0);
    const uint32_t prevM2 = mcmtl_decoder_test_set_m2(gDev, M2_ALL);
    Stream s = {0};
    mcmtl_submit_result res;
    mcmtl_decoder_stats d;
    FusionFrame f = fusion_frame_resources(size);
    float *tint = (float *)f.ubos[3].contents;   /* the screen-effects quad's red makes every frame distinct */
    const uint32_t kNotShown = MCMTL_SUBMIT_RESULT_FLAG_PRESENT_ENCODED | MCMTL_SUBMIT_RESULT_FLAG_DRAWABLE_NIL |
                               MCMTL_SUBMIT_RESULT_FLAG_DRAWABLE_RESIZED;

    MBRig rig = mb_rig(size, YES, 1, 30000000ull);   /* fake source, queue 1, 30 ms gate timeout */
    CAMetalLayer *layer = rig.surface->layer;
    CHECK(mcmtl_mailbox_active(rig.surface) && mb_wait_thread(rig.surface, YES), "mailbox on, acquirer running");

    /* A. No blocking: Core Animation has no free drawable (the test holds all three, so a blocking nextDrawable would
     * wait 1 s and fail) and the acquirer has none either. Each frame returns at once, rendered completely, not
     * presented, counted as skipped, and never reported as DRAWABLE_NIL (the surface is fine). */
    id<CAMetalDrawable> held[3];
    for (int i = 0; i < 3; i++) {
        @autoreleasepool { held[i] = [layer nextDrawable]; }   /* no pool may keep them past `held` */
    }
    CHECK(held[0] && held[1] && held[2], "test holds the layer's 3 drawables");
    id<MTLBuffer> skippedRef[3];
    for (int i = 0; i < 3; i++) {
        tint[0] = 0.1f * (float)(i + 1);
        r_fusion_frame(&s, &f, rig.surface, NO);
        d = fusion_submit(&s, &res, "mailbox: no drawable");
        CHECK(!(res.flags & kNotShown), "no drawable: frame %d flags 0x%x (no present, no nil, no resize)", i, res.flags);
        CHECK(res.cpuNanos < 250000000ull, "no drawable: frame %d took %.1f ms in mcmtl_submit (blocked?)", i, res.cpuNanos / 1e6);
        CHECK(d.presentRecords == 1 && d.mailboxRecords == 1 && d.presentsSkipped == 1 && d.presentSkip[SK_NO_DRAWABLE] == 1 &&
              d.presentsEncoded == 0 && d.presentsNil == 0, "no drawable: frame %d skipped %llu (no drawable %llu)", i,
              (unsigned long long)d.presentsSkipped, (unsigned long long)d.presentSkip[SK_NO_DRAWABLE]);
        CHECK(d.fusionFallback[FR_NO_DRAWABLE] == 1 && d.fusedFrames == 0 && d.fusedPasses == 0, "no drawable: fusion fallback");
        CHECK(d.drawableWaitNs == 0 && d.untimedCommits == 0, "no drawable: no nextDrawable on the render thread, no early commit");
        CHECK(!mcmtl_decoder_test_fusion_stale(gDev), "no drawable: main target holds the frame");
        skippedRef[i] = mb_readback(&s, f.color);
    }
    CHECK(memcmp(skippedRef[0].contents, skippedRef[1].contents, size * size * 4) != 0 &&
          memcmp(skippedRef[1].contents, skippedRef[2].contents, size * size * 4) != 0, "skipped frames differ (sky color)");
    for (int i = 0; i < 3; i++) held[i] = nil;   /* never presented: straight back to the pool */

    /* B. Newest frame: a drawable comes back; the next frame to reach the fusion pass renders straight into it and is
     * presented. No older frame is ever queued, so the display gets this frame, not one of the skipped ones. */
    CHECK(mb_release_one(&rig), "B: drawable parked");
    tint[0] = 0.9f;
    r_fusion_frame(&s, &f, rig.surface, NO);
    d = fusion_submit(&s, &res, "mailbox: drawable ready");
    CHECK((res.flags & MCMTL_SUBMIT_RESULT_FLAG_PRESENT_ENCODED) && !(res.flags & MCMTL_SUBMIT_RESULT_FLAG_DRAWABLE_NIL),
          "B: presented (flags 0x%x)", res.flags);
    CHECK(d.fusedFrames == 1 && d.presentsEncoded == 1 && d.presentsSkipped == 0 && d.untimedCommits == 0 && d.drawableWaitNs == 0,
          "B: fused %llu, encoded %llu, early commits %llu", (unsigned long long)d.fusedFrames, (unsigned long long)d.presentsEncoded,
          (unsigned long long)d.untimedCommits);
    id<MTLBuffer> shown = take_drawable(rig.surface, size, size);
    id<MTLBuffer> newest = mb_readback(&s, f.color);   /* restored from the drawable */
    CHECK(shown && count_bgra_mismatch(shown, newest, size, size, YES) == 0, "B: the drawable holds the newest frame");
    CHECK(memcmp(newest.contents, skippedRef[2].contents, size * size * 4) != 0, "B: not the last skipped frame");
    CHECK(mb_stats(rig.surface).presentsRegistered == 1, "B: one present registered");

    /* C. Queue gate: while a presented frame still waits for the display (simulated), a parked drawable is not taken;
     * after MCMTL_MAILBOX_QUEUE_TIMEOUT_MS the gate stops waiting for a handler that never comes. */
    CHECK(mb_wait_handled(rig.surface), "C: presented handler ran");
    CHECK(mb_release_one(&rig), "C: drawable parked");
    mcmtl_mailbox_test_add_queued(rig.surface, 1);
    r_fusion_frame(&s, &f, rig.surface, NO);
    d = fusion_submit(&s, &res, "mailbox: queue full");
    CHECK(!(res.flags & kNotShown) && d.presentsSkipped == 1 && d.presentSkip[SK_QUEUE_FULL] == 1,
          "C: queue full skip (flags 0x%x, queue-full %llu)", res.flags, (unsigned long long)d.presentSkip[SK_QUEUE_FULL]);
    CHECK(mb_stats(rig.surface).parked == 1, "C: the drawable stays parked");
    usleep(40000);
    r_fusion_frame(&s, &f, rig.surface, NO);
    d = fusion_submit(&s, &res, "mailbox: gate timeout");
    CHECK((res.flags & MCMTL_SUBMIT_RESULT_FLAG_PRESENT_ENCODED) && d.fusedFrames == 1 && mb_stats(rig.surface).gateTimeouts == 1,
          "C: gate timeout -> presented (flags 0x%x, timeouts %llu)", res.flags, (unsigned long long)mb_stats(rig.surface).gateTimeouts);
    take_drawable(rig.surface, size, size);
    mb_readback(&s, f.color);

    /* D. A drawable that arrives after the fusion pass missed: by default left parked for the next frame (which renders
     * straight into it); with MCMTL_MAILBOX_LATE_PRESENT=1 presented at once through the present pass. */
    CHECK(mb_wait_handled(rig.surface), "D: handlers");
    /* The default (deferred) case first, whatever MCMTL_MAILBOX_LATE_PRESENT the environment sets. */
    const BOOL envLate = mcmtl_decoder_test_set_mailbox_late_present(gDev, NO);
    __block BOOL fired = NO;
    MBRig *rp = &rig;
    void (^arrive)(void) = ^{
        if (fired) return;
        fired = YES;
        mb_release_one(rp);
    };
    mcmtl_mailbox_test_on_none_ready(rig.surface, arrive);
    r_fusion_frame(&s, &f, rig.surface, NO);
    d = fusion_submit(&s, &res, "mailbox: deferred");
    CHECK(fired && !(res.flags & kNotShown) && d.presentSkip[SK_DEFERRED] == 1 && mb_stats(rig.surface).parked == 1,
          "D: deferred (fired %d, flags 0x%x, deferred %llu)", fired, res.flags, (unsigned long long)d.presentSkip[SK_DEFERRED]);
    r_fusion_frame(&s, &f, rig.surface, NO);
    d = fusion_submit(&s, &res, "mailbox: after deferral");
    CHECK((res.flags & MCMTL_SUBMIT_RESULT_FLAG_PRESENT_ENCODED) && d.fusedFrames == 1, "D: the next frame fuses into it");
    take_drawable(rig.surface, size, size);
    mb_readback(&s, f.color);
    CHECK(mb_wait_handled(rig.surface), "D: handlers (late)");
    mcmtl_decoder_test_set_mailbox_late_present(gDev, YES);
    fired = NO;
    tint[0] = 0.5f;
    r_fusion_frame(&s, &f, rig.surface, NO);
    d = fusion_submit(&s, &res, "mailbox: late present");
    CHECK(fired && (res.flags & MCMTL_SUBMIT_RESULT_FLAG_PRESENT_ENCODED) && d.fusedFrames == 0 && d.presentsEncoded == 1 &&
          d.fusionFallback[FR_NO_DRAWABLE] == 1, "D: late present through the present pass (flags 0x%x)", res.flags);
    shown = take_drawable(rig.surface, size, size);
    id<MTLBuffer> late = mb_readback(&s, f.color);
    CHECK(shown && count_bgra_mismatch(shown, late, size, size, NO) == 0, "D: late present = straight copy of the frame");
    mcmtl_decoder_test_set_mailbox_late_present(gDev, envLate);
    mcmtl_mailbox_test_on_none_ready(rig.surface, nil);

    /* E. A parked drawable of an outdated size (the layer was resized after it was acquired) is dropped, not shown. */
    CHECK(mb_wait_handled(rig.surface), "E: handlers");
    CHECK(mb_release_one(&rig), "E: drawable parked");
    layer.drawableSize = CGSizeMake(20, 12);
    r_fusion_frame(&s, &f, rig.surface, NO);
    d = fusion_submit(&s, &res, "mailbox: stale size");
    CHECK(!(res.flags & kNotShown) && d.presentSkip[SK_STALE] == 1 && mb_stats(rig.surface).staleDiscarded == 1,
          "E: stale drawable dropped (flags 0x%x, stale %llu)", res.flags, (unsigned long long)d.presentSkip[SK_STALE]);
    layer.drawableSize = CGSizeMake(size, size);

    /* F. Vsync on (mailbox off): today's blocking path exactly: early commit, nextDrawable on the render thread, fused.
     * The frame equals the last skipped frame of A rendered with the same content: skipped frames were complete. */
    const int callsBefore = atomic_load(&rig.counter->calls);
    mcmtl_mailbox_test_enable(rig.surface, NO, 1, 30000000ull, nil);
    CHECK(!mcmtl_mailbox_active(rig.surface) && mb_stats(rig.surface).parked == 0, "F: mailbox off, nothing parked");
    tint[0] = 0.1f * (float)(2 + 1);   /* frame 2 of A */
    r_fusion_frame(&s, &f, rig.surface, NO);
    d = fusion_submit(&s, &res, "mailbox off (FIFO)");
    CHECK((res.flags & MCMTL_SUBMIT_RESULT_FLAG_PRESENT_ENCODED) && d.fusedFrames == 1 && d.untimedCommits == 1 &&
          d.mailboxRecords == 0 && d.presentsSkipped == 0, "F: blocking path (flags 0x%x, early commits %llu)", res.flags,
          (unsigned long long)d.untimedCommits);
    shown = take_drawable(rig.surface, size, size);
    CHECK(shown && count_bgra_mismatch(shown, skippedRef[2], size, size, YES) == 0, "F: presented frame == skipped frame of A");
    CHECK(memcmp(mb_readback(&s, f.color).contents, skippedRef[2].contents, size * size * 4) == 0, "F: restored main target");
    CHECK(atomic_load(&rig.counter->calls) <= callsBefore, "F: no acquisition started while off (%d -> %d)", callsBefore,
          atomic_load(&rig.counter->calls));

    /* G. Shutdown while the acquirer waits inside nextDrawable (fake source): it exits by itself and drops what it
     * gets. Then no drawable is held anywhere: all three of the layer's drawables can be acquired at once. */
    mcmtl_mailbox_test_enable(rig.surface, YES, 1, 30000000ull, nil);   /* back on; keeps the fake source */
    usleep(5000);
    CHECK(mb_shutdown(&rig), "G: acquirer thread exited");
    CHECK(!mcmtl_mailbox_active(rig.surface) && mb_stats(rig.surface).parked == 0, "G: nothing parked after shutdown");
    usleep(60000);   /* presented drawables come back at the next refreshes */
    for (int i = 0; i < 3; i++) {
        const uint64_t t0 = clock_gettime_nsec_np(CLOCK_UPTIME_RAW);
        @autoreleasepool { held[i] = [layer nextDrawable]; }
        const uint64_t t = clock_gettime_nsec_np(CLOCK_UPTIME_RAW) - t0;
        CHECK(held[i] && t < 300000000ull, "G: drawable %d free (%.1f ms): none leaked", i, t / 1e6);
    }
    for (int i = 0; i < 3; i++) held[i] = nil;

    /* H. The real source: an offscreen layer hands drawables back only at the display refresh, like a composited
     * fullscreen layer with 3 drawables (DIRECT-TO-DISPLAY.md). Frames submitted like the game's (2 in flight, the
     * throttle waits for the GPU signal) never wait for it; about one frame per refresh is presented, the rest are
     * rendered and not shown. The same surface with the mailbox off waits in nextDrawable every frame. */
    {
        MBRig real = mb_rig(size, NO, 1, 100000000ull);
        CHECK(mb_wait_thread(real.surface, YES), "H: acquirer running");
        mcmtl_decoder_stats before = *stats();
        const uint32_t frames = 240;
        uint32_t presented = 0, nils = 0;
        const double ms = mb_run_frames(&s, &f, real.surface, frames, &presented, &nils);
        d = stats_delta(&before);
        CHECK(nils == 0 && presented >= 1 && d.presentsEncoded == presented && d.presentsEncoded + d.presentsSkipped == frames &&
              d.drawableWaitNs == 0, "H: %u frames in %.1f ms: presented %u, skipped %llu, nil %u, render-thread wait %llu ns",
              frames, ms, presented, (unsigned long long)d.presentsSkipped, nils, (unsigned long long)d.drawableWaitNs);
        CHECK(presented <= (uint32_t)(ms / 4.0) + 3, "H: presents bounded by the refresh (%u in %.1f ms)", presented, ms);
        mcmtl_mailbox_test_enable(real.surface, NO, 1, 100000000ull, nil);   /* blocking path, same surface */
        before = *stats();
        const uint32_t blockingFrames = 30;
        uint32_t blockingPresented = 0, blockingNils = 0;
        const double blockingMs = mb_run_frames(&s, &f, real.surface, blockingFrames, &blockingPresented, &blockingNils);
        d = stats_delta(&before);
        CHECK(blockingPresented == blockingFrames && blockingNils == 0 && d.presentsSkipped == 0, "H: blocking presents every frame");
        CHECK(ms / frames < 0.5 * blockingMs / blockingFrames, "H: mailbox %.3f ms/frame vs blocking %.3f ms/frame", ms / frames,
              blockingMs / blockingFrames);
        printf("  real offscreen source (drawables back at the refresh): mailbox %.0f FPS (presented %u of %u), "
               "blocking nextDrawable %.0f FPS (presented %u of %u)\n", frames * 1000.0 / ms, presented, frames,
               blockingFrames * 1000.0 / blockingMs, blockingPresented, blockingFrames);
        mb_readback(&s, f.color);
        CHECK(mb_shutdown(&real), "H: acquirer thread exited");
    }
    s_free(&s);
    mcmtl_decoder_test_set_m2(gDev, prevM2);
    mcmtl_decoder_test_set_fusion(gDev, prevMode, 0, 400000ull);
    printf("mailbox presentation (no blocking, newest frame, queue gate, deferred / late present, stale size, FIFO unchanged, "
           "shutdown without leaks, real source): ok\n");
}

/* Opt-in GPU benchmark (FUSION_BENCH=1; not part of the pass/fail run): a vanilla-shaped frame at the game's
 * 3600x2260 (C1/C2, Sky, Main, C3, Hand, C4, translucent GUI, PRESENT on a framebufferOnly drawable), frames
 * submitted back to back with two in flight like the game's throttle. Mode 0 = present pass on an upright layer,
 * mode 2 = present pass (straight copy) on a flipped layer, mode 1 = fused, and the last row = fused with mailbox
 * presentation (CONTRACT.md 16.4). GPU time per frame from the decoder's command buffers is not visible here, so
 * throughput (GPU-bound) is the measure. */
static void bench_fusion(void) {
    /* With MCMTL_PROFILE_PASSES=2 the decoder logs isolated GPU time per encoder every 600 frames: run
     * FUSION_BENCH_FRAMES=600 (no warm-up) to get one log per mode. Headless drawables recycle at display refresh,
     * so the blocking modes are capped at the refresh rate here; the mailbox row is not (it presents ~1 per refresh). */
    const uint32_t w = 3600, h = 2260;
    const uint32_t frames = getenv("FUSION_BENCH_FRAMES") ? (uint32_t)atoi(getenv("FUSION_BENCH_FRAMES")) : 400;
    const uint32_t warm = getenv("FUSION_BENCH_FRAMES") ? 0 : 60;
    FusionFrame f;
    f.color = new_texture(MTLPixelFormatRGBA8Unorm, w, h, 1, kRT);
    f.depth = new_texture(MTLPixelFormatDepth32Float, w, h, 1, kRT);
    float v[5][18];
    quad3(v[0], 0, 0, 16, 16, 0.0f, 16);
    quad3(v[1], 0, 0, 8, 16, 0.6f, 16);
    quad3(v[2], 8, 0, 16, 16, 0.3f, 16);
    quad3(v[3], 0, 8, 8, 16, 0.2f, 16);
    quad3(v[4], 8, 8, 16, 16, 0.1f, 16);
    f.vb = new_buffer_with(v, sizeof v);
    float g[24];
    quad_uv(g, 2, 1, 14, 15, 16);
    f.guiVb = new_buffer_with(g, sizeof g);
    f.ubos[0] = color_buffer(0.2f, 0.4f, 0.6f, 1);
    f.ubos[1] = color_buffer(1, 0.5f, 0, 1);
    f.ubos[2] = color_buffer(0, 1, 0, 1);
    f.ubos[3] = color_buffer(0, 0, 1, 1);
    f.ubos[4] = color_buffer(1, 1, 1, 0.5f);
    f.sampled = new_texture(MTLPixelFormatRGBA8Unorm, 4, 4, 1, kRT);
    const uint32_t modes[4] = { 0, 2, 1, 1 };
    const char *names[4] = { "present pass, upright layer (MCMTL_PRESENT_FUSION=0)", "present pass, flipped layer (flip only)",
                             "fused (MCMTL_PRESENT_FUSION=1)", "fused + mailbox (MCMTL_MAILBOX=1, vsync off)" };
    const uint32_t prevM2 = mcmtl_decoder_test_set_m2(gDev, M2_ALL);
    const uint32_t savedFlags = gDev->flags;
    gDev->flags &= ~MCMTL_DEVICE_FLAG_VALIDATION;
    for (int round = 0; round < 2; round++) {
        for (int m = 0; m < 4; m++) {
            const uint32_t prevMode = mcmtl_decoder_test_set_fusion(gDev, modes[m], FUSE_TEST_SYNC_VARIANTS, 0);
            MCMTLSurface *surface = fusion_surface(w, h, modes[m] != 0, YES, YES);
            surface->layer.displaySyncEnabled = NO;   /* IMMEDIATE, like vsync off in game */
            if (getenv("FUSION_BENCH_DRAWABLES")) surface->layer.maximumDrawableCount = (NSUInteger)atoi(getenv("FUSION_BENCH_DRAWABLES"));
            if (m == 3) {
                mcmtl_mailbox_test_enable(surface, YES, 1, 100000000ull, nil);   /* the real nextDrawable on the acquirer */
                mcmtl_mailbox_test_wait_ready(surface, 1000000000ull);
            }
            Stream s = {0};
            const mcmtl_decoder_stats before = *stats();
            uint64_t t0 = 0, cpu = 0;
            for (uint32_t i = 0; i < warm + frames; i++) {
                if (i == warm) {
                    t0 = clock_gettime_nsec_np(CLOCK_UPTIME_RAW);
                    cpu = 0;
                }
                r_fusion_frame(&s, &f, surface, NO);
                mcmtl_submit_info info;
                memset(&info, 0, sizeof info);
                info.structSize = sizeof info;
                info.flags = MCMTL_SUBMIT_FLAG_COMMIT | MCMTL_SUBMIT_FLAG_FRAME_END;
                info.stream = s.data;
                info.streamLength = s.length;
                info.signalValue = gNextValue++;
                mcmtl_submit_result res;
                memset(&res, 0, sizeof res);
                res.structSize = sizeof res;
                mcmtl_submit(gDevHandle, &info, &res);
                cpu += res.cpuNanos;
                surface->drawable = nil;
                s_reset(&s);
                if (info.signalValue > 2) mcmtl_wait_completed(gDev, info.signalValue - 2, 5000000000ull);   /* 2 in flight */
            }
            mcmtl_wait_completed(gDev, gNextValue - 1, 5000000000ull);
            const double sec = (double)(clock_gettime_nsec_np(CLOCK_UPTIME_RAW) - t0) / 1e9;
            const mcmtl_decoder_stats d = stats_delta(&before);
            printf("  bench %s: %.3f ms/frame (%.0f FPS), CPU submit %.3f ms/frame, presented %llu/%u (fused %llu), "
                   "render encoders %.2f/frame\n",
                   names[m], sec * 1000.0 / frames, frames / sec, (double)cpu / frames / 1e6, (unsigned long long)d.presentsEncoded,
                   warm + frames, (unsigned long long)d.fusedFrames, (double)d.renderEncoders / (warm + frames));
            if (m == 3) mcmtl_mailbox_test_shutdown(surface);
            s_free(&s);
            mcmtl_decoder_test_set_fusion(gDev, prevMode, 0, 400000ull);
        }
    }
    gDev->flags = savedFlags;
    mcmtl_decoder_test_set_m2(gDev, prevM2);
}

/* ---------------------------------------------------------------------------------------------- */
/* Bench frame trace: per-value present attribution (CONTRACT.md 16.R5-B; r5-int review fix)       */
/* ---------------------------------------------------------------------------------------------- */

/* The ring entry mcmtl_ft_submit_end filled for `value` must say exactly what the decoder's own counters say happened
 * in that submit (`d` = the stats delta of that one submit): present record / presented / fused / skipped / nil /
 * mailbox / untimed flags, the first fusion-fallback and present-skip reasons (+1), drawable wait, command buffers,
 * encoders; plus the flags each case must (not) have. A wrong counter index or delta-to-flag mapping fails here. */
static void ft_check(const mcmtl_ft_info *info, uint64_t value, const mcmtl_decoder_stats *d, const char *what, uint32_t must,
                     uint32_t mustNot) {
    mcmtl_ft_entry *e = &((mcmtl_ft_entry *)(uintptr_t)info->ring)[value & (info->capacity - 1)];
    CHECK(e->value == value, "%s: entry tag %llu, value %llu", what, (unsigned long long)e->value, (unsigned long long)value);
    const uint32_t f = __atomic_load_n(&e->flags, __ATOMIC_ACQUIRE);
    CHECK((f & must) == must && !(f & mustNot), "%s: flags 0x%x (must 0x%x, must not 0x%x)", what, f, must, mustNot);
    CHECK(((f & MCMTL_FT_FLAG_PRESENT_RECORD) != 0) == (d->presentRecords > 0), "%s: PRESENT_RECORD vs %llu records", what,
          (unsigned long long)d->presentRecords);
    CHECK(((f & MCMTL_FT_FLAG_PRESENTED) != 0) == (d->presentsEncoded > 0), "%s: PRESENTED vs %llu encoded", what,
          (unsigned long long)d->presentsEncoded);
    CHECK(((f & MCMTL_FT_FLAG_FUSED) != 0) == (d->fusedFrames > 0), "%s: FUSED vs %llu fused", what, (unsigned long long)d->fusedFrames);
    CHECK(((f & MCMTL_FT_FLAG_SKIPPED) != 0) == (d->presentsSkipped > 0), "%s: SKIPPED vs %llu skipped", what,
          (unsigned long long)d->presentsSkipped);
    CHECK(((f & MCMTL_FT_FLAG_NIL) != 0) == (d->presentsNil > 0), "%s: NIL vs %llu nil", what, (unsigned long long)d->presentsNil);
    CHECK(((f & MCMTL_FT_FLAG_MAILBOX) != 0) == (d->mailboxRecords > 0), "%s: MAILBOX vs %llu mailbox records", what,
          (unsigned long long)d->mailboxRecords);
    CHECK(((f & MCMTL_FT_FLAG_UNTIMED) != 0) == (d->untimedCommits > 0), "%s: UNTIMED vs %llu untimed", what,
          (unsigned long long)d->untimedCommits);
    int fr = -1, sk = -1;
    for (int r = 0; r < FR_COUNT && fr < 0; r++) if (d->fusionFallback[r]) fr = r;
    for (int r = 0; r < SK_COUNT && sk < 0; r++) if (d->presentSkip[r]) sk = r;
    CHECK(e->fuseFallback == (fr < 0 ? 0 : fr + 1), "%s: fuseFallback %u, decoder reason %d", what, e->fuseFallback, fr);
    CHECK(e->presentSkip == (sk < 0 ? 0 : sk + 1), "%s: presentSkip %u, decoder reason %d", what, e->presentSkip, sk);
    CHECK(e->drawableWaitNs == d->drawableWaitNs, "%s: drawableWaitNs %llu vs %llu", what, (unsigned long long)e->drawableWaitNs,
          (unsigned long long)d->drawableWaitNs);
    CHECK(e->cbCommitted == d->commandBuffersCommitted, "%s: cbCommitted %u vs %llu", what, e->cbCommitted,
          (unsigned long long)d->commandBuffersCommitted);
    const uint64_t enc = d->renderEncoders + d->blitEncoders;
    CHECK(e->encoders == (enc > 65535 ? 65535 : enc), "%s: encoders %u vs %llu", what, e->encoders, (unsigned long long)enc);
}

static void test_frametrace_present(void) {
    const uint32_t size = 16;
    mcmtl_ft_info info = { .structSize = sizeof info };
    CHECK(mcmtl_frametrace_enable(gDevHandle, 8, &info) == MCMTL_OK, "frametrace enable: %s", mcmtl_last_error());
    const uint32_t prevM2 = mcmtl_decoder_test_set_m2(gDev, M2_ALL);
    const uint32_t prevMode = mcmtl_decoder_test_set_fusion(gDev, 1, FUSE_TEST_SYNC_VARIANTS, 0);
    Stream s = {0};
    mcmtl_submit_result res;
    FusionFrame f = fusion_frame_resources(size);
    const uint32_t frame = MCMTL_FT_FLAG_FRAME_END | MCMTL_FT_FLAG_SUBMITTED | MCMTL_FT_FLAG_PRESENT_RECORD;

    /* 1. Fused: rendered straight into the drawable. */
    MCMTLSurface *flipped = fusion_surface(size, size, YES, NO, YES);
    r_fusion_frame(&s, &f, flipped, NO);
    mcmtl_decoder_stats d = fusion_submit(&s, &res, "ft: fused");
    CHECK(d.fusedFrames == 1, "ft: the frame fused (%llu)", (unsigned long long)d.fusedFrames);
    ft_check(&info, gNextValue - 1, &d, "fused", frame | MCMTL_FT_FLAG_PRESENTED | MCMTL_FT_FLAG_FUSED,
             MCMTL_FT_FLAG_SKIPPED | MCMTL_FT_FLAG_NIL | MCMTL_FT_FLAG_MAILBOX);
    flipped->drawable = nil;
    (void)r_readback(&s, f.color, 0, 4);   /* restore the stale main target (between frames, as a screenshot would) */
    d = fusion_submit(&s, &res, "ft: restore");
    ft_check(&info, gNextValue - 1, &d, "restore (no present)", MCMTL_FT_FLAG_FRAME_END | MCMTL_FT_FLAG_SUBMITTED,
             MCMTL_FT_FLAG_PRESENT_RECORD | MCMTL_FT_FLAG_PRESENTED);

    /* 2. Present pass with a fallback reason: an unflipped layer cannot be fused. */
    MCMTLSurface *upright = fusion_surface(size, size, NO, NO, YES);
    r_fusion_frame(&s, &f, upright, NO);
    d = fusion_submit(&s, &res, "ft: present pass");
    CHECK(d.presentsEncoded == 1 && d.fusedFrames == 0 && d.fusionFallback[FR_NOT_FLIPPED] == 1, "ft: present pass, not flipped");
    ft_check(&info, gNextValue - 1, &d, "present pass", frame | MCMTL_FT_FLAG_PRESENTED, MCMTL_FT_FLAG_FUSED | MCMTL_FT_FLAG_SKIPPED);
    CHECK(((mcmtl_ft_entry *)(uintptr_t)info.ring)[(gNextValue - 1) & (info.capacity - 1)].fuseFallback == FR_NOT_FLIPPED + 1,
          "present pass: fuseFallback = not flipped");
    upright->drawable = nil;

    /* 3. nextDrawable returns nil (layer without a device). */
    MCMTLSurface *dead = fusion_surface(size, size, YES, NO, NO);
    r_fusion_frame(&s, &f, dead, NO);
    d = fusion_submit(&s, &res, "ft: nil drawable");
    CHECK(d.presentsNil == 1 && d.presentsEncoded == 0, "ft: nil drawable (%llu)", (unsigned long long)d.presentsNil);
    ft_check(&info, gNextValue - 1, &d, "nil drawable", frame | MCMTL_FT_FLAG_NIL, MCMTL_FT_FLAG_PRESENTED | MCMTL_FT_FLAG_FUSED);
    (void)r_readback(&s, f.color, 0, 4);
    (void)fusion_submit(&s, &res, "ft: readback after nil");

    /* 4. Mailbox frame skipped: no drawable ready (the test holds the layer's three). */
    MBRig rig = mb_rig(size, YES, 1, 30000000ull);
    CHECK(mcmtl_mailbox_active(rig.surface) && mb_wait_thread(rig.surface, YES), "ft: mailbox on");
    id<CAMetalDrawable> held[3];
    for (int i = 0; i < 3; i++) {
        @autoreleasepool { held[i] = [rig.surface->layer nextDrawable]; }
    }
    r_fusion_frame(&s, &f, rig.surface, NO);
    d = fusion_submit(&s, &res, "ft: mailbox skip");
    CHECK(d.presentsSkipped == 1 && d.presentSkip[SK_NO_DRAWABLE] == 1, "ft: mailbox skip (%llu)", (unsigned long long)d.presentsSkipped);
    ft_check(&info, gNextValue - 1, &d, "mailbox skip", frame | MCMTL_FT_FLAG_SKIPPED | MCMTL_FT_FLAG_MAILBOX,
             MCMTL_FT_FLAG_PRESENTED | MCMTL_FT_FLAG_NIL);
    CHECK(((mcmtl_ft_entry *)(uintptr_t)info.ring)[(gNextValue - 1) & (info.capacity - 1)].presentSkip == SK_NO_DRAWABLE + 1,
          "mailbox skip: presentSkip = no drawable");
    (void)r_readback(&s, f.color, 0, 4);
    (void)fusion_submit(&s, &res, "ft: readback after skip");
    for (int i = 0; i < 3; i++) held[i] = nil;
    CHECK(mb_shutdown(&rig), "ft: mailbox rig shut down");

    s_free(&s);
    mcmtl_decoder_test_set_fusion(gDev, prevMode, 0, 0);
    mcmtl_decoder_test_set_m2(gDev, prevM2);
    mcmtl_ft_test_disable();
    printf("frame trace present attribution (fused, present pass + reason, nil drawable, mailbox skip + reason): ok\n");
}

static void test_fusion(void) {
    PipeSpec tspec = { .name = "fusion gui", .vs = "tex_vs", .fs = "tex_fs", .colorCount = 1, .colorFormats = { MTLPixelFormatRGBA8Unorm },
                       .attrCount = 2, .attrs = { { 0, MTLVertexFormatFloat2, 0, 0 }, { 1, MTLVertexFormatFloat2, 8, 0 } },
                       .layoutCount = 1, .layouts = { { 0, 16, 0 } },
                       .bufferMask = { 0, 1u << 1 }, .textureMask = { 0, 1 }, .samplerMask = { 0, 1 } };
    gTexBlend = make_pipeline(&tspec);
    MTLRenderPipelineDescriptor *t = [gTexBlend->descriptorTemplate copy];
    t.colorAttachments[0].blendingEnabled = YES;
    t.colorAttachments[0].sourceRGBBlendFactor = MTLBlendFactorSourceAlpha;
    t.colorAttachments[0].destinationRGBBlendFactor = MTLBlendFactorOneMinusSourceAlpha;
    t.colorAttachments[0].sourceAlphaBlendFactor = MTLBlendFactorOne;
    t.colorAttachments[0].destinationAlphaBlendFactor = MTLBlendFactorOneMinusSourceAlpha;
    gTexBlend->descriptorTemplate = t;
    add_variant(gTexBlend, 1, MTLPixelFormatDepth32Float);
    test_fusion_layer_flip();
    test_fusion_frames();
    test_fusion_restore_presented_drawable();
    test_fusion_fallbacks();
    test_mailbox();
    test_frametrace_present();   /* needs the fusion pipelines and gKeep of test_m2 */
    if (getenv("FUSION_BENCH")) bench_fusion();
    gTexBlend = nil;
}

/* ---------------------------------------------------------------------------------------------- */
/* Terrain ICB (MCMTL_TERRAIN_ICB, CONTRACT.md 16.5)                                                */
/* ---------------------------------------------------------------------------------------------- */

/* The real SPIRV-Cross output of vanilla's MULTIDRAW terrain shaders (core/terrain, used by core_test). */
#include "core_test_msl.h"

/* A terrain-shaped scene drawn by vanilla's real terrain MSL: sections with CHUNK_DATA_INSTANCED at vertex slot 1
 * (baseInstance = section), BLOCK vertices (28 bytes) in `heaps` "uber" vertex buffers, three layers like
 * ChunkSectionsToRender (SOLID and CUTOUT: the fixed 16-bit quad index buffer, firstIndex 0 and baseVertex per run like
 * TerrainRuns; TRANSLUCENT: per-heap 32-bit "sorted" index buffers, firstIndex per run, baseVertex 0, blended), each its
 * own pipeline, one DRAW_INDEXED_INDIRECT per (layer, heap) group, records in a Shared ring (stride 20). */
#define TERRAIN_MAX_HEAPS 64u

typedef struct TerrainScene {
    MCMTLPipeline *pipes[3];
    uint32_t w, h, heaps;
    id<MTLTexture> color, depth, atlas, atlas2, lightmap;
    id<MTLSamplerState> nearest, linear;
    id<MTLBuffer> ubo;                    /* Projection @0, Globals @256, Fog @512, TerrainUniform @768        */
    id<MTLBuffer> inst;                   /* int3 ChunkPosition + float ChunkVisibility per section            */
    id<MTLBuffer> vbs[TERRAIN_MAX_HEAPS];
    id<MTLBuffer> qib;                    /* 16-bit quads                                                       */
    id<MTLBuffer> sorted[TERRAIN_MAX_HEAPS]; /* 32-bit, per heap, quads in reverse order                        */
    id<MTLBuffer> ring;                   /* argument records                                                   */
    uint32_t groupCount;
    uint32_t groupLayer[3 * TERRAIN_MAX_HEAPS], groupVb[3 * TERRAIN_MAX_HEAPS], groupFirst[3 * TERRAIN_MAX_HEAPS],
        groupRecords[3 * TERRAIN_MAX_HEAPS];
    uint32_t records, quads, sections;
} TerrainScene;

typedef struct TerrainVertex {
    float pos[3];
    uint8_t color[4];
    float uv0[2];
    int16_t uv2[2];
} __attribute__((packed)) TerrainVertex;

static uint32_t gTerrainRng = 0x1234567u;
static uint32_t terrain_rnd(void) {
    gTerrainRng = gTerrainRng * 1664525u + 1013904223u;
    return gTerrainRng >> 8;
}

/* Terrain pipeline from the real MSL (sources kept for the ICB rewrite iff keepSource); blend = translucent. */
static MCMTLPipeline *terrain_pipeline(BOOL keepSource, uint32_t cull, BOOL blend, const char *name) {
    MTLCompileOptions *options = [MTLCompileOptions new];
    options.languageVersion = MTLLanguageVersion3_2;
    NSString *vsSource = @(CORE_MSL_TERRAIN_VS), *fsSource = @(CORE_MSL_TERRAIN_FS);
    if (!keepSource) {
        /* Metal shares one MTLFunction per identical source: a different source gets functions without kept MSL. */
        vsSource = [vsSource stringByAppendingString:@"// no kept source\n"];
        fsSource = [fsSource stringByAppendingString:@"// no kept source\n"];
    }
    NSError *error = nil;
    id<MTLLibrary> vl = [gMtl newLibraryWithSource:vsSource options:options error:&error];
    CHECK(vl != nil, "terrain VS: %s", error.localizedDescription.UTF8String);
    id<MTLLibrary> fl = [gMtl newLibraryWithSource:fsSource options:options error:&error];
    CHECK(fl != nil, "terrain FS: %s", error.localizedDescription.UTF8String);
    id<MTLFunction> vs = [vl newFunctionWithName:@"main0"], fs = [fl newFunctionWithName:@"main0"];
    if (keepSource) {
        mcmtl_shader_keep_source(vs, vsSource, @"main0", options);
        mcmtl_shader_keep_source(fs, fsSource, @"main0", options);
    }
    PipeSpec spec = { .name = name, .colorCount = 1, .colorFormats = { MTLPixelFormatRGBA8Unorm },
                      .attrCount = 6,
                      .attrs = { { 0, MTLVertexFormatFloat3, 0, 0 }, { 1, MTLVertexFormatUChar4Normalized, 12, 0 },
                                 { 2, MTLVertexFormatFloat2, 16, 0 }, { 3, MTLVertexFormatShort2, 24, 0 },
                                 { 4, MTLVertexFormatInt3, 0, 1 }, { 5, MTLVertexFormatFloat, 12, 1 } },
                      .layoutCount = 2, .layouts = { { 0, 28, 0 }, { 1, 16, 1 } },
                      .compare = MTLCompareFunctionGreaterEqual, .depthWrite = YES,
                      .bufferMask = { (1u << 0) | (1u << 1) | (1u << 5), (1u << 1) | (1u << 2) | (1u << 5) },
                      .textureMask = { 1u << 2, 1u << 0 }, .samplerMask = { 1u << 2, 1u << 0 } };
    MCMTLPipeline *p = make_pipeline_with(&spec, vs, fs);
    if (blend) {
        MTLRenderPipelineDescriptor *t = [p->descriptorTemplate copy];
        t.colorAttachments[0].blendingEnabled = YES;
        t.colorAttachments[0].sourceRGBBlendFactor = MTLBlendFactorSourceAlpha;
        t.colorAttachments[0].destinationRGBBlendFactor = MTLBlendFactorOneMinusSourceAlpha;
        t.colorAttachments[0].sourceAlphaBlendFactor = MTLBlendFactorOne;
        t.colorAttachments[0].destinationAlphaBlendFactor = MTLBlendFactorOneMinusSourceAlpha;
        p->descriptorTemplate = t;
    }
    p->cullMode = cull;
    p->frontFacing = MTLWindingCounterClockwise;
    add_variant(p, 1, MTLPixelFormatDepth32Float);
    return keep(p);
}

static id<MTLTexture> terrain_texture(uint32_t w, uint32_t h, uint32_t seed, BOOL translucent) {
    MTLTextureDescriptor *d = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:w height:h mipmapped:NO];
    d.usage = MTLTextureUsageShaderRead;
    d.storageMode = MTLStorageModeShared;
    id<MTLTexture> t = [gMtl newTextureWithDescriptor:d];
    uint32_t *px = malloc((size_t)w * h * 4);
    uint32_t r = seed;
    for (uint32_t i = 0; i < w * h; i++) {
        r = r * 1664525u + 1013904223u;
        px[i] = (r >> 8) | (translucent ? ((r & 0x3Fu) + 0x80u) << 24 : 0xFF000000u);
    }
    [t replaceRegion:MTLRegionMake2D(0, 0, w, h) mipmapLevel:0 withBytes:px bytesPerRow:w * 4];
    free(px);
    return keep(t);
}

/* dense: the benchmark shape (two 1-block layers of quads covering each section's screen cell); otherwise random quads of
 * mixed winding, sizes and depths, plus a zero-count and an instanceCount 2 record in the first SOLID group. Per section
 * the quads split 50 / 20 / 30 % into SOLID (1-2 runs), CUTOUT (one run, 60 %) and TRANSLUCENT (one run, 45 %). */
static TerrainScene terrain_scene(uint32_t w, uint32_t h, uint32_t gridX, uint32_t gridY, uint32_t quadsPerSection, uint32_t heaps,
                                  BOOL dense, BOOL keepSource) {
    TerrainScene sc = {0};
    gTerrainRng = 0x1234567u;
    sc.w = w;
    sc.h = h;
    sc.heaps = heaps = MIN(MAX(heaps, 1u), TERRAIN_MAX_HEAPS);
    sc.pipes[0] = terrain_pipeline(keepSource, MTLCullModeBack, NO, "terrain solid");
    sc.pipes[1] = terrain_pipeline(keepSource, MTLCullModeBack, NO, "terrain cutout");
    sc.pipes[2] = terrain_pipeline(keepSource, MTLCullModeBack, YES, "terrain translucent");
    sc.color = new_texture(MTLPixelFormatRGBA8Unorm, w, h, 1, kRT);
    sc.depth = new_texture(MTLPixelFormatDepth32Float, w, h, 1, kRT);
    sc.atlas = terrain_texture(64, 64, 7, NO);
    sc.atlas2 = terrain_texture(64, 64, 99, YES);
    sc.lightmap = terrain_texture(16, 16, 3, NO);
    sc.nearest = keep(new_sampler(NO));
    sc.linear = keep(new_sampler(YES));
    const uint32_t sections = gridX * gridY;
    sc.sections = sections;
    const float worldW = 16.0f * gridX, worldH = 16.0f * gridY;
    /* UBOs (std140 layouts of the MSL structs). */
    sc.ubo = new_buffer(1024);
    uint8_t *u = sc.ubo.contents;
    float *proj = (float *)u;   /* ortho: x [0, worldW) -> [-1, 1], y [0, worldH) -> [1, -1] (the shader flips y), z / 256 */
    proj[0] = 2.0f / worldW; proj[5] = -2.0f / worldH; proj[10] = 1.0f / 256.0f; proj[12] = -1.0f; proj[13] = 1.0f; proj[15] = 1.0f;
    int32_t *globals = (int32_t *)(u + 256);
    globals[0] = globals[1] = globals[2] = 0;   /* CameraBlockPos */
    ((float *)globals)[3] = 0.0f;               /* GlintAlpha */
    ((float *)globals)[4] = 0.25f;              /* CameraOffset */
    ((float *)globals)[5] = 0.0f;
    ((float *)globals)[6] = 0.0f;
    ((float *)globals)[8] = (float)w;           /* ScreenSize */
    ((float *)globals)[9] = (float)h;
    globals[11] = 0;                            /* UseRgss: sampleNearest */
    float *fog = (float *)(u + 512);
    fog[0] = 0.5f; fog[1] = 0.6f; fog[2] = 0.7f; fog[3] = 1.0f;
    fog[4] = 40.0f; fog[5] = 400.0f; fog[6] = 150.0f; fog[7] = 300.0f; fog[8] = 400.0f; fog[9] = 400.0f;
    float *tu = (float *)(u + 768);
    tu[0] = tu[5] = tu[10] = tu[15] = 1.0f;     /* ModelViewMat */
    ((int32_t *)tu)[16] = 64;                   /* TextureSize */
    ((int32_t *)tu)[17] = 64;

    sc.inst = new_buffer((sections + 1) * 16u);
    for (uint32_t s = 0; s <= sections; s++) {
        int32_t *ip = (int32_t *)((uint8_t *)sc.inst.contents + 16u * s);
        const uint32_t gs = s % sections;
        ip[0] = (int32_t)(gs % gridX) * 16;
        ip[1] = (int32_t)(gs / gridX) * 16;
        ip[2] = (int32_t)((gs * 7) % 13) * 16;
        ((float *)ip)[3] = dense ? 1.0f : 0.6f + 0.4f * (float)(terrain_rnd() % 100) / 100.0f;
    }
    /* Vertices per heap; per section: its vertex base and quad count. */
    NSMutableData *verts[TERRAIN_MAX_HEAPS];
    for (uint32_t i = 0; i < heaps; i++) verts[i] = [NSMutableData data];
    uint32_t *secVb = calloc(sections, 4), *secBase = calloc(sections, 4), *secQuads = calloc(sections, 4);
    for (uint32_t s = 0; s < sections; s++) {
        const uint32_t vb = s % heaps;
        secVb[s] = vb;
        secBase[s] = (uint32_t)(verts[vb].length / sizeof(TerrainVertex));
        const uint32_t nq = dense ? quadsPerSection : quadsPerSection / 2 + terrain_rnd() % (quadsPerSection / 2 + 1);
        secQuads[s] = nq;
        for (uint32_t q = 0; q < nq; q++) {
            float x0, y0, x1, y1, z;
            BOOL flip = NO;
            if (dense) {
                const uint32_t layer = q < nq / 2 ? 0 : 1, k = layer ? q - nq / 2 : q;
                const float cell = 16.0f / 19.0f;
                x0 = (float)(k % 19) * cell;
                y0 = (float)((k / 19) % 19) * cell;
                x1 = x0 + cell;
                y1 = y0 + cell;
                z = layer ? 4.0f : 12.0f;
            } else {
                x0 = (float)(terrain_rnd() % 14);
                y0 = (float)(terrain_rnd() % 14);
                x1 = x0 + 1.0f + (float)(terrain_rnd() % 6) * 0.75f;
                y1 = y0 + 1.0f + (float)(terrain_rnd() % 6) * 0.75f;
                z = 1.0f + (float)(terrain_rnd() % 14);
                flip = (terrain_rnd() % 5) == 0;   /* back-facing: culled */
            }
            TerrainVertex v[4];
            const float px[4] = { x0, x1, x1, x0 }, py[4] = { y0, y0, y1, y1 };
            for (int i = 0; i < 4; i++) {
                const int j = flip ? 3 - i : i;
                v[i].pos[0] = px[j];
                v[i].pos[1] = py[j];
                v[i].pos[2] = z;
                v[i].color[0] = (uint8_t)(128 + terrain_rnd() % 128);
                v[i].color[1] = (uint8_t)(128 + terrain_rnd() % 128);
                v[i].color[2] = (uint8_t)(128 + terrain_rnd() % 128);
                v[i].color[3] = 255;
                v[i].uv0[0] = (float)((q * 3 + (uint32_t)i) % 64) / 64.0f;
                v[i].uv0[1] = (float)((q * 5 + (uint32_t)i * 3) % 64) / 64.0f;
                v[i].uv2[0] = (int16_t)(terrain_rnd() % 240);
                v[i].uv2[1] = (int16_t)(terrain_rnd() % 240);
            }
            [verts[vb] appendBytes:v length:sizeof v];
        }
    }
    uint32_t vbQuads[TERRAIN_MAX_HEAPS];
    for (uint32_t i = 0; i < heaps; i++) {
        if (verts[i].length == 0) [verts[i] setLength:sizeof(TerrainVertex) * 4];
        sc.vbs[i] = new_buffer_with(verts[i].bytes, verts[i].length);
        vbQuads[i] = (uint32_t)(verts[i].length / sizeof(TerrainVertex) / 4);
    }
    const uint32_t quadCap = 16384;
    sc.qib = new_buffer(quadCap * 12u);
    uint16_t *qi = sc.qib.contents;
    for (uint32_t q = 0; q < quadCap; q++) {
        const uint16_t b = (uint16_t)(q * 4);
        const uint16_t t[6] = { b, (uint16_t)(b + 1), (uint16_t)(b + 2), (uint16_t)(b + 2), (uint16_t)(b + 3), b };
        memcpy(qi + 6 * q, t, sizeof t);
    }
    for (uint32_t i = 0; i < heaps; i++) {
        sc.sorted[i] = new_buffer(MAX(1u, vbQuads[i]) * 24u + 64u);
        uint32_t *si = sc.sorted[i].contents;
        for (uint32_t k = 0; k < vbQuads[i]; k++) {   /* quad k of the sorted list = quad (n - 1 - k) */
            const uint32_t b = (vbQuads[i] - 1 - k) * 4;
            const uint32_t t[6] = { b, b + 1, b + 2, b + 2, b + 3, b };
            memcpy(si + 6 * k, t, sizeof t);
        }
    }
    /* Records grouped by (layer, heap) like ChunkSectionsToRender; a group's records are contiguous in the ring. */
    const uint32_t maxRecords = sections * 4 + 16;
    uint32_t (*recs)[5] = calloc(maxRecords, sizeof *recs);
    uint32_t n = 0, quadsDrawn = 0;
    for (uint32_t layer = 0; layer < 3; layer++) {
        for (uint32_t vb = 0; vb < heaps; vb++) {
            const uint32_t first = n;
            for (uint32_t s = 0; s < sections; s++) {
                if (secVb[s] != vb) continue;
                const uint32_t nq = secQuads[s], solidEnd = nq / 2, cutoutEnd = nq * 7 / 10;
                if (layer == 0) {
                    const uint32_t runs = 1 + (terrain_rnd() % 100 < 60 ? 1 : 0);
                    uint32_t q0 = 0;
                    for (uint32_t r = 0; r < runs; r++) {
                        const uint32_t q1 = r + 1 == runs ? solidEnd : q0 + solidEnd / runs;
                        if (q1 > q0) {
                            const uint32_t rec[5] = { (q1 - q0) * 6, 1, 0, secBase[s] + 4 * q0, s };
                            memcpy(recs[n++], rec, sizeof rec);
                            quadsDrawn += q1 - q0;
                        }
                        q0 = q1;
                    }
                } else if (layer == 1) {
                    if (cutoutEnd > solidEnd && terrain_rnd() % 100 < 60) {
                        const uint32_t rec[5] = { (cutoutEnd - solidEnd) * 6, 1, 0, secBase[s] + 4 * solidEnd, s };
                        memcpy(recs[n++], rec, sizeof rec);
                        quadsDrawn += cutoutEnd - solidEnd;
                    }
                } else if (nq > cutoutEnd && terrain_rnd() % 100 < 45) {
                    /* the section's quads [cutoutEnd, nq) in the heap's reverse-sorted list */
                    const uint32_t base = secBase[s] / 4;
                    const uint32_t lo = vbQuads[vb] - 1 - (base + nq - 1);   /* sorted position of the last quad */
                    const uint32_t rec[5] = { (nq - cutoutEnd) * 6, 1, lo * 6, 0, s };
                    memcpy(recs[n++], rec, sizeof rec);
                    quadsDrawn += nq - cutoutEnd;
                }
            }
            if (!dense && layer == 0 && vb == 0 && n > first + 2) {
                const uint32_t zero[5] = { 0, 1, 0, 0, 0 };                    /* zero count: drawn as nothing */
                memcpy(recs[n++], zero, sizeof zero);
                uint32_t twice[5];
                memcpy(twice, recs[first], sizeof twice);
                twice[1] = 2;                                                  /* instances s and s + 1 */
                memcpy(recs[n++], twice, sizeof twice);
            }
            if (n > first) {
                sc.groupLayer[sc.groupCount] = layer;
                sc.groupVb[sc.groupCount] = vb;
                sc.groupFirst[sc.groupCount] = first;
                sc.groupRecords[sc.groupCount] = n - first;
                sc.groupCount++;
            }
        }
    }
    sc.records = n;
    sc.quads = quadsDrawn;
    sc.ring = new_buffer(256 + (size_t)n * 20u);   /* records at offset 256, like a ring slice */
    memcpy((uint8_t *)sc.ring.contents + 256, recs, (size_t)n * 20u);
    free(recs);
    free(secVb);
    free(secBase);
    free(secQuads);
    return sc;
}

typedef struct TerrainFrameOptions {
    BOOL swapAtlas;          /* SET_TEXTURE atlas2 before the second SOLID group                             */
    BOOL directDraw;         /* a DRAW_INDEXED with the last terrain pipeline after the indirect draws       */
    BOOL slot15;             /* vertex-buffer slot 15 bound before the draws                                */
    __unsafe_unretained id<MTLBuffer> copyRecordsFrom; /* COPY_BUFFER of the records into the ring first        */
    __unsafe_unretained MCMTLSurface *present; /* CLEAR_TEXTUREs + a loading pass + PRESENT of the color target */
} TerrainFrameOptions;

/* Like ChunkSectionsToRender.renderLayers: per layer setPipeline, the instance buffer, then per group the vertex buffer,
 * index buffer and one drawIndexedIndirect. */
static void r_terrain_frame(Stream *s, const TerrainScene *sc, const TerrainFrameOptions *o) {
    const float sky[4] = { 0.1f, 0.2f, 0.3f, 1.0f };
    if (o && o->copyRecordsFrom) r_copy_buffer(s, o->copyRecordsFrom, 256, sc->ring, 256, (uint64_t)sc->records * 20u);
    if (o && o->present) {
        r_clear_texture(s, sc->color, MCMTL_ASPECT_COLOR, 0, 1, sky, 0);
        r_clear_texture(s, sc->depth, MCMTL_ASPECT_DEPTH, 0, 1, NULL, 0.0f);
    }
    PassDesc p = pass1(sc->color, sc->depth);
    if (!(o && o->present)) {
        p.clearMask = 1;
        memcpy(p.clear[0], sky, sizeof sky);
        p.depthClear = YES;
        p.depthValue = 0.0f;
    }
    r_begin_pass(s, &p);
    if (o && o->slot15) r_set_vb(s, 15, sc->inst, 0);
    uint32_t lastLayer = UINT32_MAX;
    for (uint32_t g = 0; g < sc->groupCount; g++) {
        const uint32_t layer = sc->groupLayer[g];
        if (layer != lastLayer) {
            lastLayer = layer;
            r_set_pipeline(s, sc->pipes[layer]);
            r_set_buffer(s, 0, MCMTL_STAGE_VERTEX, sc->ubo, 0);
            r_set_buffer(s, 1, MCMTL_STAGE_VERTEX | MCMTL_STAGE_FRAGMENT, sc->ubo, 256);
            r_set_buffer(s, 2, MCMTL_STAGE_FRAGMENT, sc->ubo, 512);
            r_set_buffer(s, 5, MCMTL_STAGE_VERTEX | MCMTL_STAGE_FRAGMENT, sc->ubo, 768);
            r_set_texture(s, 0, MCMTL_STAGE_FRAGMENT, sc->atlas, sc->nearest);
            r_set_texture(s, 2, MCMTL_STAGE_VERTEX, sc->lightmap, sc->linear);
            r_set_vb(s, 1, sc->inst, 0);
        }
        if (o && o->swapAtlas && g == 1) r_set_texture(s, 0, MCMTL_STAGE_FRAGMENT, sc->atlas2, sc->nearest);
        r_set_vb(s, 0, sc->vbs[sc->groupVb[g]], 0);
        if (layer == 2) r_set_ib(s, MTLIndexTypeUInt32, sc->sorted[sc->groupVb[g]]);
        else r_set_ib(s, MTLIndexTypeUInt16, sc->qib);
        r_draw_indirect(s, MCMTL_OP_DRAW_INDEXED_INDIRECT, sc->groupRecords[g], sc->ring, 256 + (uint64_t)sc->groupFirst[g] * 20u);
    }
    if (o && o->directDraw) {
        r_set_ib(s, MTLIndexTypeUInt16, sc->qib);
        r_set_vb(s, 0, sc->vbs[0], 0);
        r_draw_indexed(s, 60, 1, 0, 0, 3);
    }
    r_end_pass(s);
    if (o && o->present) r_present(s, o->present, sc->color, 0);
}

/* Renders one frame under ICB mode `mode` (sync variants) and returns [color, depth] readbacks; *d = stats delta. */
static NSArray<id<MTLBuffer>> *terrain_render(const TerrainScene *sc, uint32_t mode, const TerrainFrameOptions *o, mcmtl_decoder_stats *d,
                                              const char *what) {
    mcmtl_decoder_test_set_icb(gDev, mode, ICB_TEST_SYNC_VARIANTS, 0);
    Stream s = {0};
    r_terrain_frame(&s, sc, o);
    id<MTLBuffer> c = r_readback(&s, sc->color, 0, 4), z = r_readback(&s, sc->depth, 0, 4);
    const mcmtl_decoder_stats before = *stats();
    mcmtl_submit_result res;
    int32_t rc = submit(&s, &res);
    CHECK(rc == MCMTL_OK && (res.flags & MCMTL_SUBMIT_RESULT_FLAG_COMMITTED), "%s: rc %d (%s)", what, rc, mcmtl_last_error());
    if (d) *d = stats_delta(&before);
    s_free(&s);
    return @[ c, z ];
}

static NSUInteger first_diff(id<MTLBuffer> a, id<MTLBuffer> b) {
    const uint8_t *p = a.contents, *q = b.contents;
    for (NSUInteger i = 0; i < a.length; i++) {
        if (p[i] != q[i]) return i;
    }
    return NSNotFound;
}

#define CHECK_SAME(ref, got, what)                                                                                  \
    do {                                                                                                          \
        for (NSUInteger k_ = 0; k_ < 2; k_++) {                                                                   \
            NSUInteger at_ = first_diff((ref)[k_], (got)[k_]);                                                    \
            CHECK(at_ == NSNotFound, "%s: %s differs from the per-record path at byte %lu", what, k_ ? "depth" : "color", \
                  (unsigned long)at_);                                                                            \
        }                                                                                                         \
    } while (0)

static uint64_t icb_fallbacks(const mcmtl_decoder_stats *d) {
    uint64_t n = 0;
    for (int r = 0; r < IR_COUNT; r++) n += d->icbFallback[r];
    return n;
}

static void test_terrain_icb_rewrite(void) {
    uint32_t t = 0, s = 0;
    NSString *failure = nil;
    NSString *vs = mcmtl_msl_icb_rewrite(@(CORE_MSL_TERRAIN_VS), @"main0", MCMTL_ICB_ARG_BUFFER_INDEX, &t, &s, &failure);
    CHECK(vs && t == 4u && s == 4u, "terrain VS rewrite: masks 0x%x/0x%x (%s)", t, s, failure.UTF8String);
    CHECK([vs containsString:@"constant mcmtl_icb_args& mcmtl_icb [[buffer(15)]]"] && ![vs containsString:@"[[texture(2)]]"] &&
          [vs containsString:@"texture2d<float> Sampler2 = mcmtl_icb.t2;"] && [vs containsString:@"sampler Sampler2Smplr = mcmtl_icb.s2;"],
          "terrain VS rewrite text");
    NSString *fs = mcmtl_msl_icb_rewrite(@(CORE_MSL_TERRAIN_FS), @"main0", MCMTL_ICB_ARG_BUFFER_INDEX, &t, &s, &failure);
    CHECK(fs && t == 1u && s == 1u, "terrain FS rewrite: masks 0x%x/0x%x", t, s);
    NSString *clouds = mcmtl_msl_icb_rewrite(@(CORE_MSL_CLOUDS_VS), @"main0", MCMTL_ICB_ARG_BUFFER_INDEX, &t, &s, &failure);
    CHECK(clouds && t == 8u && s == 0u && [clouds containsString:@"texture_buffer<int> CloudFaces = mcmtl_icb.t3;"] &&
          [clouds containsString:@"uint gl_VertexIndex [[vertex_id]], constant mcmtl_icb_args&"], "clouds VS rewrite (texel buffer)");
    NSString *plain = @(CORE_MSL_CLOUDS_FS);
    CHECK(mcmtl_msl_icb_rewrite(plain, @"main0", MCMTL_ICB_ARG_BUFFER_INDEX, &t, &s, &failure) == plain && t == 0 && s == 0,
          "no textures: source unchanged");
    CHECK(!mcmtl_msl_icb_rewrite(@(CORE_MSL_TERRAIN_VS), @"main0", 5, &t, &s, &failure), "[[buffer(5)]] taken: refused");
    CHECK(!mcmtl_msl_icb_rewrite(@"vertex float4 main0(array<texture2d<float>, 2> t [[texture(0)]]) { return 0; }", @"main0", 15, &t, &s,
                                 &failure), "texture array refused");
    CHECK(!mcmtl_msl_icb_rewrite(@(CORE_MSL_TERRAIN_VS), @"nope", 15, &t, &s, &failure), "missing entry point refused");
    /* The rewritten sources compile and make an ICB-capable PSO (Metal rejects the originals). */
    MTLCompileOptions *options = [MTLCompileOptions new];
    options.languageVersion = MTLLanguageVersion3_2;
    NSError *error = nil;
    id<MTLLibrary> vl = [gMtl newLibraryWithSource:vs options:options error:&error];
    id<MTLLibrary> fl = vl ? [gMtl newLibraryWithSource:fs options:options error:&error] : nil;
    CHECK(vl && fl, "rewritten terrain MSL compiles: %s", error.localizedDescription.UTF8String);
    printf("terrain ICB MSL rewrite (terrain VS/FS, clouds texel buffer, refusals): ok\n");
}

static void test_terrain_icb(void) {
    NSMutableArray *outerKeep = gKeep;
    gKeep = [NSMutableArray array];
    test_terrain_icb_rewrite();
    const uint32_t prevMode = mcmtl_decoder_test_set_icb(gDev, 0, ICB_TEST_RESET, 0);
    const uint32_t prevFusion = mcmtl_decoder_test_set_fusion(gDev, 0, 0, 0);
    TerrainScene sc = terrain_scene(96, 64, 6, 4, 40, 3, NO, YES);
    uint32_t layersUsed = 0;
    for (uint32_t g = 0; g < sc.groupCount; g++) layersUsed |= 1u << sc.groupLayer[g];
    CHECK(sc.groupCount >= 7 && layersUsed == 7u && sc.records > 40, "scene: %u groups, layers 0x%x, %u records", sc.groupCount,
          layersUsed, sc.records);
    mcmtl_decoder_stats d;

    /* Reference (per-record), then ICB: first frame encodes every group, the second reuses them all. */
    NSArray *ref = terrain_render(&sc, 0, NULL, &d, "per-record");
    CHECK(d.indirectRecordDraws == sc.records && d.icbGroups == 0, "per-record: %llu records drawn one by one",
          (unsigned long long)d.indirectRecordDraws);
    {
        const uint8_t *c = ((id<MTLBuffer>)ref[0]).contents;
        uint32_t sky = 0;
        for (uint32_t i = 0; i < sc.w * sc.h; i++) sky += near8(c + 4 * i, 26, 51, 77, 255);
        CHECK(sky > 0 && sky < sc.w * sc.h * 9 / 10, "scene covers part of the target (%u of %u sky pixels)", sky, sc.w * sc.h);
    }
    mcmtl_decoder_test_set_icb(gDev, 0, ICB_TEST_RESET, 0);
    NSArray *got = terrain_render(&sc, 1, NULL, &d, "ICB frame 1");
    CHECK_SAME(ref, got, "ICB frame 1");
    CHECK(d.icbGroups == sc.groupCount && d.icbRecords == sc.records && d.icbHits == 0 && d.icbEncodedRecords == sc.records &&
          d.indirectRecordDraws == 0 && icb_fallbacks(&d) == 0 && d.icbVariantBuilds == 3,
          "ICB frame 1: groups %llu records %llu hits %llu encoded %llu per-record %llu fallbacks %llu builds %llu",
          (unsigned long long)d.icbGroups, (unsigned long long)d.icbRecords, (unsigned long long)d.icbHits,
          (unsigned long long)d.icbEncodedRecords, (unsigned long long)d.indirectRecordDraws, (unsigned long long)icb_fallbacks(&d),
          (unsigned long long)d.icbVariantBuilds);
    CHECK(d.draws == sc.groupCount, "one Metal draw command per group (%llu)", (unsigned long long)d.draws);
    got = terrain_render(&sc, 1, NULL, &d, "ICB frame 2");
    CHECK_SAME(ref, got, "ICB frame 2 (cached)");
    CHECK(d.icbHits == sc.groupCount && d.icbEncodedRecords == 0 && d.icbCreated == 0, "frame 2: hits %llu encoded %llu created %llu",
          (unsigned long long)d.icbHits, (unsigned long long)d.icbEncodedRecords, (unsigned long long)d.icbCreated);

    /* Changed records (the ICB of frame 2 completed: re-encoded in place); then restored. */
    {
        uint32_t *r = (uint32_t *)((uint8_t *)sc.ring.contents + 256);
        uint32_t saved[10];
        memcpy(saved, r, sizeof saved);
        r[0] = 0;          /* record 0 drawn as nothing */
        r[5 + 4] = 5;      /* record 1 at section 5's position */
        mcmtl_decoder_test_set_icb(gDev, 0, 0, 0);
        NSArray *ref2 = terrain_render(&sc, 0, NULL, &d, "per-record, changed records");
        got = terrain_render(&sc, 1, NULL, &d, "ICB, changed records");
        CHECK_SAME(ref2, got, "ICB, changed records");
        CHECK(d.icbHits == sc.groupCount - 1 && d.icbEncodedRecords == sc.groupRecords[0] && d.icbCreated == 0,
              "changed group re-encoded in place: hits %llu encoded %llu created %llu", (unsigned long long)d.icbHits,
              (unsigned long long)d.icbEncodedRecords, (unsigned long long)d.icbCreated);
        memcpy(r, saved, sizeof saved);
        got = terrain_render(&sc, 1, NULL, &d, "ICB, records restored");
        CHECK_SAME(ref, got, "ICB, records restored");
    }
    /* In flight: frames submitted without waiting (non-COMMIT calls) never re-encode an ICB the GPU may still run. */
    {
        uint32_t *r = (uint32_t *)((uint8_t *)sc.ring.contents + 256);
        mcmtl_decoder_test_set_icb(gDev, 1, ICB_TEST_SYNC_VARIANTS, 0);
        Stream s = {0};
        r_terrain_frame(&s, &sc, NULL);
        mcmtl_submit_result res;
        const mcmtl_decoder_stats before = *stats();
        int32_t rc = submit_ex(NULL, &s, 0, &res, NULL);   /* encoded, not committed: the open command buffer uses the ICBs */
        CHECK(rc == MCMTL_OK, "open call rc %d", rc);
        s_reset(&s);
        const uint32_t saved = r[2];
        r[2] = r[2] ? 0 : 6;   /* a different record for group 0 */
        r_terrain_frame(&s, &sc, NULL);
        id<MTLBuffer> c = r_readback(&s, sc.color, 0, 4), z = r_readback(&s, sc.depth, 0, 4);
        rc = submit(&s, &res);
        d = stats_delta(&before);
        CHECK(rc == MCMTL_OK && d.icbCreated == 1 && d.icbEncodedRecords == sc.groupRecords[0],
              "busy ICB replaced: created %llu encoded %llu", (unsigned long long)d.icbCreated, (unsigned long long)d.icbEncodedRecords);
        s_free(&s);
        NSArray *ref3 = terrain_render(&sc, 0, NULL, &d, "per-record, busy case");
        CHECK_SAME(ref3, (@[ c, z ]), "ICB, busy slot replaced");
        r[2] = saved;
    }
    /* A texture change between groups (argument buffer rewritten), a direct draw after the ranges (the pipeline's own PSO
     * restored), all M2 configurations' merges irrelevant here: bytes equal the per-record path. */
    {
        TerrainFrameOptions o = { .swapAtlas = YES, .directDraw = YES };
        NSArray *ref4 = terrain_render(&sc, 0, &o, &d, "per-record, atlas swap + direct draw");
        got = terrain_render(&sc, 1, &o, &d, "ICB, atlas swap + direct draw");
        CHECK_SAME(ref4, got, "ICB, atlas swap + direct draw");
        /* Argument buffers: lightmap + atlas, atlas2 (second SOLID group), atlas again (CUTOUT re-binds it; TRANSLUCENT
         * binds the same textures: reused). PSO binds: base + ICB variant per layer, and the base again for the direct draw. */
        CHECK(d.icbGroups == sc.groupCount && d.icbArgBuffers == 4 && d.setPipelineState == 7,
              "argument buffers %llu, PSO binds %llu", (unsigned long long)d.icbArgBuffers, (unsigned long long)d.setPipelineState);
    }
    /* Mixed sizes: groups below MCMTL_TERRAIN_ICB_MIN_RECORDS draw per record, with the ICB variant kept bound once an
     * earlier group of the same pipeline used it (no PSO ping-pong). */
    {
        uint32_t threshold = 0;
        for (uint32_t g = 0; g < sc.groupCount; g++) threshold = MAX(threshold, sc.groupRecords[g]);
        mcmtl_decoder_test_set_icb(gDev, 1, ICB_TEST_SYNC_VARIANTS, threshold);
        Stream s = {0};
        r_terrain_frame(&s, &sc, NULL);
        id<MTLBuffer> c = r_readback(&s, sc.color, 0, 4), z = r_readback(&s, sc.depth, 0, 4);
        const mcmtl_decoder_stats before = *stats();
        mcmtl_submit_result res;
        submit(&s, &res);
        d = stats_delta(&before);
        s_free(&s);
        CHECK_SAME(ref, (@[ c, z ]), "mixed group sizes");
        CHECK(d.icbGroups >= 1 && d.icbGroups < sc.groupCount && d.icbGroups + d.icbFallback[IR_SMALL] == sc.groupCount &&
              d.setPipelineState <= 4,
              "mixed sizes: %llu ICB ranges, %llu small, %llu PSO binds", (unsigned long long)d.icbGroups,
              (unsigned long long)d.icbFallback[IR_SMALL], (unsigned long long)d.setPipelineState);
    }
    /* Fallbacks draw per record (same bytes): records written by a GPU copy in flight, vertex slot 15 bound, small ranges,
     * a pipeline without kept MSL (variant fails once, remembered). */
    {
        id<MTLBuffer> staging = new_buffer_with(sc.ring.contents, sc.ring.length);
        TerrainFrameOptions o = { .copyRecordsFrom = staging };
        got = terrain_render(&sc, 1, &o, &d, "ICB, records copied by the GPU");
        CHECK_SAME(ref, got, "records copied by the GPU");
        CHECK(d.icbFallback[IR_ARGS] == sc.groupCount && d.icbGroups == 0, "GPU-written records: %llu fallbacks",
              (unsigned long long)d.icbFallback[IR_ARGS]);
        got = terrain_render(&sc, 1, NULL, &d, "ICB, copy completed");
        CHECK(d.icbGroups == sc.groupCount, "after the copy completed: ICB again (%llu)", (unsigned long long)d.icbGroups);
        TerrainFrameOptions o15 = { .slot15 = YES };
        NSArray *ref15 = terrain_render(&sc, 0, &o15, &d, "per-record, slot 15");
        got = terrain_render(&sc, 1, &o15, &d, "ICB, slot 15 bound");
        CHECK_SAME(ref15, got, "slot 15 bound");
        CHECK(d.icbFallback[IR_SLOT] == sc.groupCount, "slot 15: %llu fallbacks", (unsigned long long)d.icbFallback[IR_SLOT]);
        mcmtl_decoder_test_set_icb(gDev, 1, ICB_TEST_SYNC_VARIANTS, 1000);
        Stream s = {0};
        r_terrain_frame(&s, &sc, NULL);
        const mcmtl_decoder_stats before = *stats();
        mcmtl_submit_result res;
        submit(&s, &res);
        d = stats_delta(&before);
        CHECK(d.icbFallback[IR_SMALL] == sc.groupCount, "min records: %llu small fallbacks", (unsigned long long)d.icbFallback[IR_SMALL]);
        s_free(&s);
        TerrainScene bare = sc;
        for (int l = 0; l < 3; l++) bare.pipes[l] = terrain_pipeline(NO, MTLCullModeBack, l == 2, "terrain, no kept MSL");
        NSArray *refBare = terrain_render(&bare, 0, NULL, &d, "per-record, no kept MSL");
        got = terrain_render(&bare, 1, NULL, &d, "ICB, no kept MSL");
        CHECK_SAME(refBare, got, "no kept MSL");
        CHECK(d.icbFallback[IR_FAILED] == sc.groupCount && d.icbVariantBuilds == 3, "no kept MSL: failed %llu, builds %llu",
              (unsigned long long)d.icbFallback[IR_FAILED], (unsigned long long)d.icbVariantBuilds);
        got = terrain_render(&bare, 1, NULL, &d, "ICB, no kept MSL again");
        CHECK(d.icbVariantBuilds == 0 && d.icbFallback[IR_FAILED] == sc.groupCount, "failed variant not retried");
    }
    /* Asynchronous variant: the first frame draws per record while it compiles, a later one runs the ICBs. */
    {
        TerrainScene fresh = sc;
        for (int l = 0; l < 3; l++) fresh.pipes[l] = terrain_pipeline(YES, MTLCullModeNone, l == 2, "terrain, cull none");
        mcmtl_decoder_test_set_icb(gDev, 0, 0, 0);
        NSArray *refFresh = terrain_render(&fresh, 0, NULL, &d, "per-record, cull none");
        mcmtl_decoder_test_set_icb(gDev, 1, 0, 0);
        Stream s = {0};
        r_terrain_frame(&s, &fresh, NULL);
        const mcmtl_decoder_stats before = *stats();
        mcmtl_submit_result res;
        submit(&s, &res);
        d = stats_delta(&before);
        CHECK(d.icbFallback[IR_VARIANT] == sc.groupCount && d.icbVariantBuilds == 3, "async: first frame per record (%llu pending)",
              (unsigned long long)d.icbFallback[IR_VARIANT]);
        s_free(&s);
        const uint64_t key = mcmtl_variant_key(1, MTLPixelFormatDepth32Float) | MCMTL_VARIANT_KEY_ICB;
        for (int i = 0; i < 500 && !(mcmtl_pipeline_find_variant(fresh.pipes[0], key) && mcmtl_pipeline_find_variant(fresh.pipes[1], key) &&
                                     mcmtl_pipeline_find_variant(fresh.pipes[2], key)); i++) {
            usleep(10000);
            submit_ex(NULL, NULL, MCMTL_SUBMIT_FLAG_COMMIT, &res, NULL);   /* drained at the start of a call */
        }
        CHECK(mcmtl_pipeline_find_variant(fresh.pipes[2], key) != nil, "async ICB variants arrived");
        mcmtl_decoder_test_set_icb(gDev, 1, 0, 0);
        Stream s2 = {0};
        r_terrain_frame(&s2, &fresh, NULL);
        id<MTLBuffer> c = r_readback(&s2, fresh.color, 0, 4), z = r_readback(&s2, fresh.depth, 0, 4);
        const mcmtl_decoder_stats before2 = *stats();
        submit(&s2, &res);
        d = stats_delta(&before2);
        CHECK(d.icbGroups == sc.groupCount, "async: later frame through ICBs (%llu)", (unsigned long long)d.icbGroups);
        CHECK_SAME(refFresh, (@[ c, z ]), "async variant, cull none");
        s_free(&s2);
    }
    /* Present fusion: the terrain pass renders into the drawable through the BGRA8 + ICB variant. */
    {
        mcmtl_decoder_test_set_fusion(gDev, 1, FUSE_TEST_SYNC_VARIANTS, 0);
        const uint32_t prevM2 = mcmtl_decoder_test_set_m2(gDev, M2_ALL);
        MCMTLSurface *surface = fusion_surface(sc.w, sc.h, YES, NO, YES);
        TerrainFrameOptions o = { .present = surface };
        mcmtl_decoder_test_set_icb(gDev, 0, 0, 0);
        Stream s = {0};
        r_terrain_frame(&s, &sc, &o);
        mcmtl_submit_result res;
        mcmtl_decoder_stats f0 = fusion_submit(&s, &res, "fused, per record");
        id<MTLBuffer> ref5 = take_drawable(surface, sc.w, sc.h);
        mcmtl_decoder_test_set_icb(gDev, 1, ICB_TEST_SYNC_VARIANTS, 0);
        r_terrain_frame(&s, &sc, &o);
        mcmtl_decoder_stats f1 = fusion_submit(&s, &res, "fused, ICB");
        id<MTLBuffer> got5 = take_drawable(surface, sc.w, sc.h);
        CHECK(f0.fusedFrames == 1 && f1.fusedFrames == 1 && f1.icbGroups == sc.groupCount, "fused frames %llu/%llu, ICB groups %llu",
              (unsigned long long)f0.fusedFrames, (unsigned long long)f1.fusedFrames, (unsigned long long)f1.icbGroups);
        CHECK(ref5 && got5 && first_diff(ref5, got5) == NSNotFound, "fused drawable: ICB == per record");
        r_readback(&s, sc.color, 0, 4);   /* settle the stale fused source */
        fusion_submit(&s, &res, "fused: settle");
        s_free(&s);
        mcmtl_decoder_test_set_m2(gDev, prevM2);
        mcmtl_decoder_test_set_fusion(gDev, 0, 0, 0);
    }
    mcmtl_decoder_test_set_icb(gDev, prevMode, ICB_TEST_RESET, 0);
    mcmtl_decoder_test_set_fusion(gDev, prevFusion, 0, 400000ull);
    gKeep = outerKeep;
    printf("terrain ICB (bytes == per-record path: cached, re-encoded, busy slot, atlas swap, direct draw, GPU-written records, "
           "slot 15, small ranges, no MSL, async variant, fused drawable): ok\n");
}

/* Opt-in benchmark (TERRAIN_BENCH=1): ~860 terrain records (400 sections, ~240k quads, SOLID / CUTOUT / TRANSLUCENT) at
 * 3600x2260 with vanilla's terrain shaders, per-record path vs ICB ranges (records unchanged: cache hits; and records
 * changing every frame: CPU encode), interleaved frame by frame. Each frame is one command buffer, committed and waited
 * for (serialized GPU time from the status page); CPU = mcmtl_submit wall time (decode + encode + commit).
 * TERRAIN_BENCH_FRAMES (default 300); TERRAIN_BENCH_HEAPS (default 1 = one group per layer, like the game at RD 12 with
 * 128 MiB heaps; 43 = ~129 small groups, like tools/terrain-drawlist-bench.sh's small-heap check);
 * TERRAIN_BENCH_MIN_RECORDS (default 64, the decoder's MCMTL_TERRAIN_ICB_MIN_RECORDS default; 1 = every group). */
static void bench_terrain_icb(void) {
    NSMutableArray *outerKeep = gKeep;
    gKeep = [NSMutableArray array];
    const uint32_t frames = getenv("TERRAIN_BENCH_FRAMES") ? (uint32_t)atoi(getenv("TERRAIN_BENCH_FRAMES")) : 300;
    const uint32_t prevMode = mcmtl_decoder_test_set_icb(gDev, 0, ICB_TEST_RESET, 0);
    const uint32_t prevFusion = mcmtl_decoder_test_set_fusion(gDev, 0, 0, 0);
    const uint32_t savedFlags = gDev->flags;
    gDev->flags &= ~MCMTL_DEVICE_FLAG_VALIDATION;
    const uint32_t heaps = getenv("TERRAIN_BENCH_HEAPS") ? (uint32_t)atoi(getenv("TERRAIN_BENCH_HEAPS")) : 1;
    const uint32_t minRecords = getenv("TERRAIN_BENCH_MIN_RECORDS") ? (uint32_t)atoi(getenv("TERRAIN_BENCH_MIN_RECORDS")) : 64;
    TerrainScene sc = terrain_scene(3600, 2260, 20, 20, 692, heaps, YES, YES);
    /* Phase 1 interleaves the per-record path with ICB hits; phase 2 the per-record path with ICB frames whose records
     * change every frame (every group's first record alternates between its own count and one quad less), so each of
     * those frames encodes every command again. The per-record path never touches the ICB cache. */
    const uint32_t modes = 4;
    const char *names[4] = { "per-record indirect draws (phase 1)", "ICB, records unchanged (cache hits)",
                             "per-record indirect draws (phase 2)", "ICB, records change every frame (CPU encode)" };
    double *gpu = calloc((size_t)modes * frames, sizeof(double)), *cpu = calloc((size_t)modes * frames, sizeof(double));
    uint32_t *r = (uint32_t *)((uint8_t *)sc.ring.contents + 256);
    printf("terrain bench: %u sections, %u records in %u groups (%u heaps x 3 layers), %u quads, %ux%u, ICB for groups of >= %u records\n",
           sc.sections, sc.records, sc.groupCount, sc.heaps, sc.quads, sc.w, sc.h, MAX(minRecords, 1u));
    Stream s = {0};
    for (uint32_t phase = 0; phase < 2; phase++) {
        for (uint32_t i = 0; i < frames + 20; i++) {
            for (uint32_t k = 0; k < 2; k++) {
                const uint32_t m = phase * 2 + k;
                const BOOL change = m == 3 && (i & 1);
                mcmtl_decoder_test_set_icb(gDev, k, ICB_TEST_SYNC_VARIANTS, minRecords);
                if (change) {
                    for (uint32_t g = 0; g < sc.groupCount; g++) r[5 * sc.groupFirst[g]] -= 6;
                }
                r_terrain_frame(&s, &sc, NULL);
                mcmtl_submit_result res;
                submit(&s, &res);
                s_reset(&s);
                if (change) {
                    for (uint32_t g = 0; g < sc.groupCount; g++) r[5 * sc.groupFirst[g]] += 6;
                }
                if (i >= 20) {
                    gpu[m * frames + i - 20] = (double)mcmtl_status_load_u64(&gDev->status->lastGpuTimeNs) / 1e6;
                    cpu[m * frames + i - 20] = (double)res.cpuNanos / 1e3;
                }
            }
        }
    }
    s_free(&s);
    printf("  %-50s %10s %10s %12s %12s\n", "mode", "GPU med", "GPU p25", "CPU med us", "CPU mean us");
    for (uint32_t m = 0; m < modes; m++) {
        double *g = &gpu[m * frames], *c = &cpu[m * frames];
        double sum = 0;
        for (uint32_t i = 0; i < frames; i++) sum += c[i];
        qsort_b(g, frames, sizeof(double), ^int(const void *a, const void *b) {
            double x = *(const double *)a, y = *(const double *)b;
            return x < y ? -1 : x > y;
        });
        qsort_b(c, frames, sizeof(double), ^int(const void *a, const void *b) {
            double x = *(const double *)a, y = *(const double *)b;
            return x < y ? -1 : x > y;
        });
        printf("  %-50s %8.3fms %8.3fms %12.1f %12.1f\n", names[m], g[frames / 2], g[frames / 4], c[frames / 2], sum / frames);
    }
    free(gpu);
    free(cpu);
    gDev->flags = savedFlags;
    mcmtl_decoder_test_set_icb(gDev, prevMode, ICB_TEST_RESET, 0);
    mcmtl_decoder_test_set_fusion(gDev, prevFusion, 0, 400000ull);
    gKeep = outerKeep;
}

static void test_m2(void) {
    gKeep = [NSMutableArray array];   /* direct callers of the case blocks below keep everything until the end */
    m2_pipelines();
    test_m2_frame();
    test_m2_clear_uses();
    test_m2_merge_rules();
    test_m2_depth_clear_draw();
    test_m2_late_store();
    test_m2_failures();
    test_m2_present_source();
    test_m2_frame_counter();
    test_fusion();
    gDepthGE = gDepthCullFront = gDepthCullBack = gTex = nil;
    gKeep = nil;
}


int main(void) {
    setvbuf(stdout, NULL, _IOLBF, 0);   /* keep progress lines when a crash or a validation abort ends the run */
    @autoreleasepool {
        gMtl = MTLCreateSystemDefaultDevice();
        gDev = mcmtl_device_context_create(gMtl, @"decoder_test");
        CHECK(gDev != nil, "device context: %s", mcmtl_last_error());
        if (!gDev) return 1;
        gDev->flags = MCMTL_DEVICE_FLAG_VALIDATION;
        for (id<MTLCounterSet> set in gMtl.counterSets) {
            if ([set.name isEqualToString:MTLCommonCounterSetTimestamp]) gDev->timestampCounterSet = set;
        }
        CHECK(mcmtl_decoder_device_init(gDev), "decoder init: %s", mcmtl_last_error());
        CHECK(stats() && stats()->structSize == sizeof(mcmtl_decoder_stats), "stats struct mismatch");
        gDevHandle = (mcmtl_handle)(uintptr_t)(__bridge void *)gDev;
        NSError *error = nil;
        MTLCompileOptions *options = [MTLCompileOptions new];
        options.languageVersion = MTLLanguageVersion3_2;
        gLib = [gMtl newLibraryWithSource:kTestShaders options:options error:&error];
        CHECK(gLib != nil, "test shaders: %s", error.localizedDescription.UTF8String);
        if (!gLib) return 1;
        gSolid = solid_pipeline();

        test_clears();
        test_pass_clears_and_render_area();
        test_textured_triangle();
        test_indexed_base_vertex();
        test_instancing();
        test_fans();
        test_scissor();
        test_copies();
        test_many_blits();
        test_depth();
        test_timeline();
        test_dummies_and_bindings();
        test_skips_and_indirect();
        test_present();
        test_bad_streams();
        test_timestamps();
        test_exceptions();
        test_hot_path();
        test_m2();
        test_terrain_icb();
        if (getenv("TERRAIN_BENCH")) bench_terrain_icb();

        const mcmtl_decoder_stats *st = stats();
        printf("stats: %llu command buffers (%llu untimed), %llu render / %llu blit encoders, %llu splits, %llu draws, "
               "%llu dummy bindings, %llu clear quads\n",
               (unsigned long long)st->commandBuffersCommitted, (unsigned long long)st->untimedCommits,
               (unsigned long long)st->renderEncoders, (unsigned long long)st->blitEncoders, (unsigned long long)st->blitSplits,
               (unsigned long long)st->draws, (unsigned long long)st->dummyBindings, (unsigned long long)st->clearQuads);
        gSolid = nil;
        mcmtl_decoder_device_shutdown(gDev);
    }
    printf("%s (%d checks, %d failure%s)\n", failures ? "decoder_test FAILED" : "decoder_test PASSED", checks, failures,
           failures == 1 ? "" : "s");
    return failures ? 1 : 0;
}
