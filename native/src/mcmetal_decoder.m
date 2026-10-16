/*
 * mcmetal_decoder.m -- mcmtl_decoder_submit: command-stream decode and Metal encoding (classic API, M1).
 * OWNER: I2 (native-decoder). Contract: docs/metal/CONTRACT.md sections 4, 6 and 8; mcmetal_stream.h;
 * the mcmtl_decoder_submit block in mcmetal_internal.h.
 *
 * The exported mcmtl_submit is the architect's guard in mcmetal_common.m: it validates the arguments and
 * the signal value, then calls mcmtl_decoder_submit (this file) inside @try, and CPU-completes the signal
 * value whenever the decoder did not consume it.
 *
 * Structure of one call (mcmtl_decoder_submit):
 *   @try     { decode the init stream (INIT_TEXTURE / NOP only), then the main stream }
 *   @catch   { record the exception }
 *   @finally { end the open render / blit encoder (each in its own @try), pop leftover command-buffer debug
 *              groups, COMMIT: mcmtl_commit_signaled (a fresh signal-only command buffer if the open one is
 *              poisoned), currentCommandBuffer bookkeeping }
 *
 * Encoding model (M1, classic MTLCommandQueue, tracked hazards, retained references):
 *  - one render encoder per BEGIN_PASS .. END_PASS; transfer records between passes share one lazily opened
 *    blit encoder, split on intra-encoder hazards (per-encoder read/write sets with byte ranges for buffers
 *    and (level, slice, rect) regions for textures, CONTRACT.md 4.5);
 *  - per-encoder binding shadow state (mcmtl_decode_ctx): redundant set* calls are dropped, offset-only
 *    buffer changes use set*BufferOffset, fixed-function state is applied on change only;
 *  - before every draw the dummy policy of CONTRACT.md 6.4 runs (a few mask operations when nothing is
 *    missing) and draws whose pipeline needs an unbound vertex buffer are skipped;
 *  - triangle fans: non-indexed draws use a device-wide fan index buffer; indexed draws are converted on the
 *    CPU into a decoder upload ring (chunks recycled by command-buffer completion handlers);
 *  - partial render-area clears and region clears are scissored clear-quad draws (internal PSOs generated
 *    per attachment signature, compiled once); COPY_FLAG_DRAW copies are nearest-texel draws;
 *  - PRESENT: early untimed commit of the work so far, late nextDrawable, flip/opaque present pass,
 *    presentDrawable on the command buffer that later signals the timeline value;
 *  - TIMESTAMP: classic Apple GPUs sample only at stage boundaries. Timestamps adjacent to a pass become that
 *    pass's sample-buffer attachment (M5 samples only the highest-indexed attachment slot of a pass, so one
 *    pool per pass; samples of empty blit passes resolve to 0), others a blit pass containing one tiny fill.
 *
 * Nothing allocates per draw.
 *
 * M2 (TBDR bandwidth, spec 9.1 B3-B6; CONTRACT.md 4.7). Each optimization has an environment toggle, default
 * ON (MCMTL_M2=0 turns all of them off = the M1 behaviour; MCMTL_M2_<NAME>=0 turns one off):
 *  - index: before decoding the main stream, one pass over its records builds a per-pass summary (offsets,
 *    sampled parent textures, buffer reads, in-pass timestamps). Every look-ahead below reads this index and
 *    the encoder-level records only; it stops at the first malformed record (conservative from there on).
 *  - FOLD (B3): CLEAR_TEXTURE (and full-level CLEAR_REGION is left to M1) becomes a pending clear per
 *    (texture, level, slice); a later clear overrides it. A pass attaching that level over its full render
 *    area takes it as loadAction Clear (its own clear wins). Any other use materializes it first as the M1
 *    clear pass: sampled by the pass about to begin (or by a later pass of its merge chain), copy source or
 *    destination, readback, present source, region clear, INIT_TEXTURE, a partial / empty render area, and
 *    the end of the call.
 *  - LATE_STORE (B5): pass attachments are created with MTLStoreActionUnknown and resolved when the encoder
 *    ends: DontCare iff the next access of that (texture, level) in this call is a full overwrite (a full
 *    clear of that aspect, or a full-area pass clearing it) with no read in between; Store otherwise, at the
 *    end of the call, for stencil, and on every error path.
 *  - MERGE (B4): one render encoder spans consecutive passes with identical attachments when the next pass
 *    covers the full area, loads (or clears depth: CLEAR_DRAW), samples none of the attachments, and the
 *    records between them are only labels, NOPs, hoistable copies and clears (a depth clear of the depth
 *    attachment becomes an in-encoder full-screen [[depth(any)]] draw; clears of other textures stay
 *    pending). At each merge point the per-pass state is reset like a fresh encoder (set masks, vertex /
 *    index buffers, pipeline, scissor = render area) while the real Metal bindings stay in the shadow.
 *    Command-buffer debug groups between merged passes become encoder groups and nest.
 *  - HOIST (B6): COPY_BUFFER / COPY_BUFFER_TO_TEXTURE between merged passes run in a blit encoder before
 *    the merged encoder when no earlier pass of the chain reads the destination (buffers: [offset, end)
 *    of every binding; textures: sampled, attached, or with a pending clear) -- otherwise the chain ends.
 *  - CLEAR_DRAW: the in-encoder depth clears of MERGE (off: chains end at depth clears).
 *  - ZERO_CLEAR_VERTEX: a 0.0 depth clear draw (vanilla's reverse-Z value) has no fragment stage (the vertex z
 *    is +0 at every vertex, so every interpolated depth is exactly +0); other values use [[depth(any)]].
 * Every encoder-creating path breaks an open merge first, so an unplanned record degrades to M1 encoding.
 * Statistics (passes in, encoders out, folds, merges, hoists, DontCare stores...) are logged every 600
 * frames with MCMTL_DECODER_STATS=1 or MCMTL_PROFILE_PASSES.
 *
 * Present fusion (MCMTL_PRESENT_FUSION=1, default; CONTRACT.md 16.3): the surface's layer is mirrored vertically
 * (mcmtl_layer_set_flipped), so a drawable holding the main target's bytes in the main target's memory order shows
 * upright. The planner (dec_fuse_plan, after the index) looks for the frame's PRESENT source S at level L and the
 * last pass that clears S@L over its full area (its own clear or a folded CLEAR_TEXTURE) such that, from there on,
 * S is only ever attached as color 0 at L by later passes (no sampling, copies, other attachments, region clears)
 * and not referenced at all after PRESENT. That pass begins by committing the work so far (untimed), acquiring
 * the drawable and, when it matches S@L (size, BGRA8Unorm layer, opaque), binding drawable.texture as color 0 of
 * every one of those passes (PSO variants MCMTL_VARIANT_KEY_BGRA8_COLOR0, compiled asynchronously beforehand; frames
 * fall back while they compile). PRESENT then only registers presentDrawable: no present pass, and S is neither
 * stored nor read. Rasterization, viewports, scissors and [[position]] are untouched (the drawable has the main
 * target's memory layout), so the drawable receives exactly the bytes S would have (R/B swapped by the format).
 * S is left stale: the decoder remembers (S, L, drawable) and, at the start of the next submit, either drops that
 * state when S@L is overwritten before any read (vanilla: the frame-start clear) or rebuilds S@L from the drawable
 * (programmable-blending copy; the drawable stays framebufferOnly) before a read (screenshots taken between
 * frames). Fallbacks (counted per reason) use the present pass, with a straight copy when the layer is flipped.
 * An adaptive guard turns fusion off for a while when acquiring the drawable before the frame keeps waiting
 * (with 2 drawables a composited layer recycles them only after WindowServer composites the previous frame).
 *
 * Mailbox presentation (MCMTL_MAILBOX=1, default, vsync off only; CONTRACT.md 16.4): the decoder never calls
 * nextDrawable for such a surface. The fusion pass and PRESENT take a drawable parked by the surface's acquirer
 * thread (mcmtl_mailbox_take, non-blocking); without one the frame is rendered into its target as usual and PRESENT
 * registers nothing (no DRAWABLE_NIL; counted in stats.presentSkip[]). No early commit is needed (nothing waits) and
 * the fusion guard is idle. FIFO surfaces keep the blocking path above.
 *
 * Terrain ICB (MCMTL_TERRAIN_ICB=1, default; CONTRACT.md 16.5): a DRAW_INDEXED_INDIRECT of >= 64 records runs as one
 * executeCommandsInBuffer:withRange: of an ICB whose drawIndexedPrimitives commands were encoded on the CPU from the
 * records (final at decode time: CPU-written ring) and are reused while the records and index buffer are unchanged.
 * The pipeline's ICB variant reads textures from an argument buffer at [[buffer(15)]] (Metal refuses ICB pipelines
 * with directly bound textures): MSL rewritten and compiled asynchronously from the kept source. Bit-identical to the
 * per-record path; MCMTL_TERRAIN_ICB=0 restores it exactly.
 *
 * Terrain occlusion culling (MCMTL_OCCLUSION=1, default off; CONTRACT.md 16.6, docs/metal/OCCLUSION.md): multidraw
 * terrain records of sections whose [-2, 18]^3 box had no sample in its last visibility queries (box drawn after the
 * opaque terrain, same eye, entirely on screen) are not encoded. =2 is a dry run (queries and statistics only).
 */
#import "mcmetal_internal.h"

#include <stdio.h>
#include <stdlib.h>
#include <math.h>
#include <os/lock.h>
#include <string.h>

/* ---------------------------------------------------------------------------------------------- */
/* Limits                                                                                           */
/* ---------------------------------------------------------------------------------------------- */

#define DEC_RING_CHUNK_BYTES   (256u * 1024u)   /* decoder upload ring chunk (indexed fans, staging)   */
#define DEC_ZERO_BUFFER_BYTES  (256u * 1024u)   /* INIT_TEXTURE source; grows to one row if needed     */
#define DEC_FAN_MIN_VERTICES   1024u
#define DEC_HZ_SLOTS           512u             /* blit hazard tracker: resources per blit encoder      */
#define DEC_HZ_ENTRIES         2048u            /* accesses per blit encoder                            */
#define DEC_HZ_PER_RESOURCE    128u             /* accesses per resource before a forced split          */
#define DEC_MAX_PASS_TS        8u               /* timestamps attached to one pass                      */
#define DEC_MAX_HANDLED_TS     16u
#define DEC_SUB_ALL            0xFFFFFFFFu      /* texture access covering every level and slice        */
#define DEC_UBO_MASK           0x7FFFu          /* [[buffer(0..14)]]: UBOs + push constants             */
#define DEC_MAX_PENDING        32u              /* pending clears per call (oldest materialized beyond) */
#define DEC_MAX_CHAIN          64u              /* passes continued in one merged encoder               */
#define DEC_MAX_GAP_PENDING    16u              /* textures cleared between the passes of one chain     */
#define DEC_TEX_CACHE          64u              /* per-call texture handle -> parent / buffer cache      */
#define DEC_FUSE_BACKOFF_FRAMES 300u            /* frames without fusion after the drawable waits grew   */
#define DEC_FUSE_BACKOFF_MAX   9600u            /* ... doubling while it keeps re-triggering, up to this */
#define DEC_FUSE_STEADY_FRAMES 600u             /* fused frames that reset the backoff length            */
#define DEC_FUSE_MAX_WAIT_US   400u             /* default MCMTL_PRESENT_FUSION_MAX_WAIT_US             */

/* Present fusion: why a frame with a PRESENT was not fused (stats.fusionFallback[], CONTRACT.md 16.3). */
enum {
    DEC_FUSE_R_NOT_FLIPPED = 0,   /* the layer shows drawable row 0 at the top (unflipped surface / transform reset)  */
    DEC_FUSE_R_DISABLED,          /* MCMTL_PRESENT_FUSION_FLIP_ONLY=1 (diagnostics: flipped layer, present pass)     */
    DEC_FUSE_R_NO_INDEX,          /* no look-ahead (M2 index off, malformed stream, PRESENT not indexed)             */
    DEC_FUSE_R_MULTI_PRESENT,     /* more than one PRESENT in the call                                               */
    DEC_FUSE_R_FORMAT,            /* source not a 2D RGBA8/BGRA8Unorm level, layer not BGRA8Unorm or not opaque      */
    DEC_FUSE_R_SIZE,              /* drawable size != source level size (resize / fullscreen transition)              */
    DEC_FUSE_R_NO_CLEAR,          /* no full-area clear of the source starts the passes that end in PRESENT          */
    DEC_FUSE_R_SOURCE_USE,        /* source read, copied, region-cleared or otherwise attached after that clear, or
                                   * referenced after PRESENT (readbacks, Tracy captures)                           */
    DEC_FUSE_R_VARIANTS,          /* BGRA8 PSO variants still compiling (or failed to build)                         */
    DEC_FUSE_R_BACKOFF,           /* adaptive guard: early nextDrawable waits too long                               */
    DEC_FUSE_R_DRAWABLE_NIL,      /* nextDrawable returned nil                                                        */
    DEC_FUSE_R_NOT_REACHED,       /* planned pass never began (decode error before it)                                */
    DEC_FUSE_R_NO_DRAWABLE,       /* mailbox (CONTRACT.md 16.4): no drawable free at the planned pass, frame not shown */
    DEC_FUSE_REASONS
};

/* Present fusion state of one call (ext->fuseState). SKIP: mailbox, no drawable was free at the planned pass. */
enum { DEC_FUSE_NONE = 0, DEC_FUSE_PLANNED, DEC_FUSE_ACTIVE, DEC_FUSE_FAILED, DEC_FUSE_NIL, DEC_FUSE_DONE, DEC_FUSE_SKIP };

/* Mailbox presentation (CONTRACT.md 16.4): why a PRESENT was not presented (stats.presentSkip[]). */
enum {
    DEC_SKIP_NO_DRAWABLE = 0,     /* no drawable parked: the acquirer thread is still waiting for Core Animation      */
    DEC_SKIP_QUEUE_FULL,          /* MCMTL_MAILBOX_MAX_QUEUED presented frames still wait for the display            */
    DEC_SKIP_STALE,               /* the parked drawable had an outdated size (dropped; a new one is being acquired)  */
    DEC_SKIP_DEFERRED,            /* no drawable at the fusion pass, one arrived before PRESENT: left for the next
                                   * frame, which renders straight into it (MCMTL_MAILBOX_LATE_PRESENT=1 presents it
                                   * with the present pass instead)                                                  */
    DEC_SKIP_REASONS
};

/* Terrain ICB (MCMTL_TERRAIN_ICB, CONTRACT.md 16.5): why a DRAW_INDEXED_INDIRECT record took the per-record path while
 * the mode is on (stats.icbFallback[]). */
enum {
    DEC_ICB_R_VARIANT = 0,        /* the pipeline's ICB PSO variant is still compiling (asynchronously)                  */
    DEC_ICB_R_FAILED,             /* no ICB variant: no MSL kept, signature not rewritable, compile or PSO failure       */
    DEC_ICB_R_FAN,                /* triangle-fan emulation (CPU-converted per record)                                   */
    DEC_ICB_R_ARGS,               /* argument records not CPU-visible, or written by GPU work that has not completed     */
    DEC_ICB_R_SLOT,               /* [[buffer(15)]] (vertex-buffer slot 15) bound or read: it carries the textures      */
    DEC_ICB_R_SMALL,              /* fewer records than MCMTL_TERRAIN_ICB_MIN_RECORDS                                   */
    DEC_ICB_R_CACHE,              /* more indexed indirect draws in one call than ICB cache slots                       */
    DEC_ICB_R_ALLOC,              /* ICB or argument-buffer allocation failed                                            */
    DEC_ICB_R_OCCLUSION,          /* MCMTL_OCCLUSION planned the command: per record (culling, visibility slots)         */
    DEC_ICB_REASONS
};

#define DEC_ICB_SLOTS          128u             /* ICB cache slots: indexed indirect draws per call (lazy)     */
#define DEC_ICB_MIN_RECORDS    64u              /* default MCMTL_TERRAIN_ICB_MIN_RECORDS: one execute costs ~0.6 us
                                                 * CPU + ~0.3 us GPU, one record ~38 ns CPU + ~12 ns GPU saved / spent */
#define DEC_ICB_RETIRED        48u              /* replaced ICBs kept for reuse (creating one costs 0.05-0.4 ms) */
#define DEC_ICB_MIN_CAPACITY   256u             /* commands per ICB (powers of two above)                      */
#define DEC_ICB_USED           32u              /* resources made resident per render encoder (then repeated)  */
#define DEC_ICB_WRITES         256u             /* GPU-written buffers tracked (open addressing)               */
#define DEC_ICB_WRITE_PROBE    16u              /* ... within this many slots of their hash                    */

/* decoder_test hooks (mcmtl_decoder_test_set_icb). */
enum { DEC_ICB_TEST_SYNC_VARIANTS = 1u, DEC_ICB_TEST_RESET = 2u };

/* decoder_test hooks (mcmtl_decoder_test_set_fusion). */
enum { DEC_FUSE_TEST_SYNC_VARIANTS = 1u };

/* M2 optimizations (bit set per device, from the environment; decoder_test overrides it). */
enum {
    DEC_M2_FOLD       = 1u << 0,   /* MCMTL_M2_FOLD: clear folding (B3)                                    */
    DEC_M2_LATE_STORE = 1u << 1,   /* MCMTL_M2_LATE_STORE: store actions decided at encoder end (B5)      */
    DEC_M2_MERGE      = 1u << 2,   /* MCMTL_M2_MERGE: pass merging (B4)                                    */
    DEC_M2_HOIST      = 1u << 3,   /* MCMTL_M2_HOIST: inter-pass copies hoisted before a merged encoder (B6) */
    DEC_M2_CLEAR_DRAW = 1u << 4,   /* MCMTL_M2_CLEAR_DRAW: depth clears between merged passes as draws     */
    DEC_M2_ZERO_CLEAR_VERTEX = 1u << 5, /* MCMTL_M2_ZERO_CLEAR_VERTEX: a 0.0 clear draw without fragment stage  */
    DEC_M2_ALL        = 0x3Fu,
};

static uint32_t dec_m2_env_flags(void) {
    const char *all = getenv("MCMTL_M2");
    if (all && atoi(all) == 0) return 0;
    static const struct { const char *name; uint32_t bit; } kToggles[] = {
        { "MCMTL_M2_FOLD", DEC_M2_FOLD }, { "MCMTL_M2_LATE_STORE", DEC_M2_LATE_STORE }, { "MCMTL_M2_MERGE", DEC_M2_MERGE },
        { "MCMTL_M2_HOIST", DEC_M2_HOIST }, { "MCMTL_M2_CLEAR_DRAW", DEC_M2_CLEAR_DRAW },
        { "MCMTL_M2_ZERO_CLEAR_VERTEX", DEC_M2_ZERO_CLEAR_VERTEX },
    };
    uint32_t flags = DEC_M2_ALL;
    for (size_t i = 0; i < sizeof kToggles / sizeof kToggles[0]; i++) {
        const char *v = getenv(kToggles[i].name);
        if (v && atoi(v) == 0) flags &= ~kToggles[i].bit;
    }
    return flags;
}

/* Minimum record size per op (0 = unknown op). */
static const uint16_t kMinRecordSize[MCMTL_OP_LIMIT] = {
    [MCMTL_OP_NOP] = MCMTL_CMD_SIZE_EMPTY,
    [MCMTL_OP_BEGIN_PASS] = MCMTL_CMD_SIZE_BEGIN_PASS_BASE,
    [MCMTL_OP_END_PASS] = MCMTL_CMD_SIZE_END_PASS,
    [MCMTL_OP_CLEAR_TEXTURE] = MCMTL_CMD_SIZE_CLEAR_TEXTURE,
    [MCMTL_OP_CLEAR_REGION] = MCMTL_CMD_SIZE_CLEAR_REGION,
    [MCMTL_OP_COPY_BUFFER] = MCMTL_CMD_SIZE_COPY_BUFFER,
    [MCMTL_OP_COPY_BUFFER_TO_TEXTURE] = MCMTL_CMD_SIZE_COPY_BUFFER_TEXTURE,
    [MCMTL_OP_COPY_TEXTURE_TO_BUFFER] = MCMTL_CMD_SIZE_COPY_BUFFER_TEXTURE,
    [MCMTL_OP_COPY_TEXTURE] = MCMTL_CMD_SIZE_COPY_TEXTURE,
    [MCMTL_OP_PRESENT] = MCMTL_CMD_SIZE_PRESENT,
    [MCMTL_OP_INIT_TEXTURE] = MCMTL_CMD_SIZE_INIT_TEXTURE,
    [MCMTL_OP_UPSCALE] = MCMTL_CMD_SIZE_UPSCALE,
    [MCMTL_OP_TIMESTAMP] = MCMTL_CMD_SIZE_TIMESTAMP,
    [MCMTL_OP_PUSH_DEBUG_GROUP] = MCMTL_CMD_SIZE_PUSH_DEBUG_GROUP_MIN,
    [MCMTL_OP_POP_DEBUG_GROUP] = MCMTL_CMD_SIZE_EMPTY,
    [MCMTL_OP_SET_PIPELINE] = MCMTL_CMD_SIZE_SET_PIPELINE,
    [MCMTL_OP_SET_BUFFER] = MCMTL_CMD_SIZE_SET_BUFFER,
    [MCMTL_OP_SET_TEXTURE] = MCMTL_CMD_SIZE_SET_TEXTURE,
    [MCMTL_OP_CLEAR_BINDING] = MCMTL_CMD_SIZE_CLEAR_BINDING,
    [MCMTL_OP_SET_VERTEX_BUFFER] = MCMTL_CMD_SIZE_SET_VERTEX_BUFFER,
    [MCMTL_OP_SET_INDEX_BUFFER] = MCMTL_CMD_SIZE_SET_INDEX_BUFFER,
    [MCMTL_OP_SET_SCISSOR] = MCMTL_CMD_SIZE_SET_SCISSOR,
    [MCMTL_OP_RESET_SCISSOR] = MCMTL_CMD_SIZE_EMPTY,
    [MCMTL_OP_PUSH_CONSTANTS] = MCMTL_CMD_SIZE_PUSH_CONSTANTS_MIN,
    [MCMTL_OP_DRAW] = MCMTL_CMD_SIZE_DRAW,
    [MCMTL_OP_DRAW_INDEXED] = MCMTL_CMD_SIZE_DRAW_INDEXED,
    [MCMTL_OP_DRAW_INDIRECT] = MCMTL_CMD_SIZE_DRAW_INDIRECT,
    [MCMTL_OP_DRAW_INDEXED_INDIRECT] = MCMTL_CMD_SIZE_DRAW_INDIRECT,
};

/* ---------------------------------------------------------------------------------------------- */
/* Pixel formats                                                                                    */
/* ---------------------------------------------------------------------------------------------- */

enum { DEC_KIND_FLOAT = 0, DEC_KIND_INT = 1, DEC_KIND_UINT = 2, DEC_KIND_DEPTH = 3, DEC_KIND_STENCIL = 4 };

typedef struct dec_format {
    uint8_t bytes;    /* bytes per pixel (0 = unknown)                                                   */
    uint8_t kind;     /* DEC_KIND_*: shader data type of the format                                     */
    uint8_t depth;    /* has a depth aspect                                                             */
    uint8_t stencil;  /* has a stencil aspect                                                           */
} dec_format;

/* Raw values of formats deprecated in the macOS 27 SDK (still valid enum values). */
#define DEC_PF_DEPTH24_UNORM_STENCIL8 255u
#define DEC_PF_X24_STENCIL8           262u

static dec_format dec_format_info(NSUInteger pixelFormat) {
    switch (pixelFormat) {
        case MTLPixelFormatA8Unorm: case MTLPixelFormatR8Unorm: case MTLPixelFormatR8Unorm_sRGB:
        case MTLPixelFormatR8Snorm:
            return (dec_format){1, DEC_KIND_FLOAT, 0, 0};
        case MTLPixelFormatR8Uint: return (dec_format){1, DEC_KIND_UINT, 0, 0};
        case MTLPixelFormatR8Sint: return (dec_format){1, DEC_KIND_INT, 0, 0};
        case MTLPixelFormatR16Unorm: case MTLPixelFormatR16Snorm: case MTLPixelFormatR16Float:
        case MTLPixelFormatRG8Unorm: case MTLPixelFormatRG8Unorm_sRGB: case MTLPixelFormatRG8Snorm:
        case MTLPixelFormatB5G6R5Unorm: case MTLPixelFormatA1BGR5Unorm: case MTLPixelFormatABGR4Unorm:
        case MTLPixelFormatBGR5A1Unorm:
            return (dec_format){2, DEC_KIND_FLOAT, 0, 0};
        case MTLPixelFormatR16Uint: case MTLPixelFormatRG8Uint: return (dec_format){2, DEC_KIND_UINT, 0, 0};
        case MTLPixelFormatR16Sint: case MTLPixelFormatRG8Sint: return (dec_format){2, DEC_KIND_INT, 0, 0};
        case MTLPixelFormatR32Float: case MTLPixelFormatRG16Unorm: case MTLPixelFormatRG16Snorm:
        case MTLPixelFormatRG16Float: case MTLPixelFormatRGBA8Unorm: case MTLPixelFormatRGBA8Unorm_sRGB:
        case MTLPixelFormatRGBA8Snorm: case MTLPixelFormatBGRA8Unorm: case MTLPixelFormatBGRA8Unorm_sRGB:
        case MTLPixelFormatRGB10A2Unorm: case MTLPixelFormatBGR10A2Unorm: case MTLPixelFormatRG11B10Float:
        case MTLPixelFormatRGB9E5Float:
            return (dec_format){4, DEC_KIND_FLOAT, 0, 0};
        case MTLPixelFormatR32Uint: case MTLPixelFormatRG16Uint: case MTLPixelFormatRGBA8Uint:
        case MTLPixelFormatRGB10A2Uint:
            return (dec_format){4, DEC_KIND_UINT, 0, 0};
        case MTLPixelFormatR32Sint: case MTLPixelFormatRG16Sint: case MTLPixelFormatRGBA8Sint:
            return (dec_format){4, DEC_KIND_INT, 0, 0};
        case MTLPixelFormatRG32Float: case MTLPixelFormatRGBA16Unorm: case MTLPixelFormatRGBA16Snorm:
        case MTLPixelFormatRGBA16Float:
            return (dec_format){8, DEC_KIND_FLOAT, 0, 0};
        case MTLPixelFormatRG32Uint: case MTLPixelFormatRGBA16Uint: return (dec_format){8, DEC_KIND_UINT, 0, 0};
        case MTLPixelFormatRG32Sint: case MTLPixelFormatRGBA16Sint: return (dec_format){8, DEC_KIND_INT, 0, 0};
        case MTLPixelFormatRGBA32Float: return (dec_format){16, DEC_KIND_FLOAT, 0, 0};
        case MTLPixelFormatRGBA32Uint: return (dec_format){16, DEC_KIND_UINT, 0, 0};
        case MTLPixelFormatRGBA32Sint: return (dec_format){16, DEC_KIND_INT, 0, 0};
        case MTLPixelFormatDepth16Unorm: return (dec_format){2, DEC_KIND_DEPTH, 1, 0};
        case MTLPixelFormatDepth32Float: return (dec_format){4, DEC_KIND_DEPTH, 1, 0};
        case MTLPixelFormatStencil8: return (dec_format){1, DEC_KIND_STENCIL, 0, 1};
        case DEC_PF_DEPTH24_UNORM_STENCIL8: return (dec_format){4, DEC_KIND_DEPTH, 1, 1};
        case MTLPixelFormatDepth32Float_Stencil8: return (dec_format){8, DEC_KIND_DEPTH, 1, 1};
        case MTLPixelFormatX32_Stencil8: return (dec_format){8, DEC_KIND_STENCIL, 0, 1};
        case DEC_PF_X24_STENCIL8: return (dec_format){4, DEC_KIND_STENCIL, 0, 1};
        default: return (dec_format){0, DEC_KIND_FLOAT, 0, 0};
    }
}

/* Bytes per pixel on the buffer side of a buffer <-> texture blit (combined depth/stencil formats copy one
 * plane, selected by the blit options). */
static uint32_t dec_copy_bytes_per_pixel(NSUInteger pixelFormat, uint32_t blitOptions) {
    dec_format f = dec_format_info(pixelFormat);
    if (f.depth && f.stencil) {
        if (blitOptions & MTLBlitOptionStencilFromDepthStencil) return 1;
        if (blitOptions & MTLBlitOptionDepthFromDepthStencil) return 4;
    }
    return f.bytes;
}

static inline uint32_t dec_mip(NSUInteger size, uint32_t level) {
    NSUInteger v = level < 32 ? size >> level : 0;
    return v ? (uint32_t)v : 1u;
}

static inline uint32_t dec_slice_count(id<MTLTexture> t) {
    switch (t.textureType) {
        case MTLTextureTypeCube: return 6;
        case MTLTextureTypeCubeArray: return 6 * (uint32_t)t.arrayLength;
        case MTLTextureType2DArray: case MTLTextureType1DArray: return (uint32_t)t.arrayLength;
        default: return 1;
    }
}

/* ---------------------------------------------------------------------------------------------- */
/* Decoder statistics and test hooks (decoder-private API, used by native/tests/decoder_test.m)    */
/* ---------------------------------------------------------------------------------------------- */

/* Cumulative counters per device (render thread). The layout is mirrored by decoder_test.m, which checks
 * structSize. Counting is a handful of increments per encoded Metal call. */
typedef struct mcmtl_decoder_stats {
    uint32_t structSize;
    uint32_t reserved;
    uint64_t commandBuffersCommitted;  /* timed + untimed                                              */
    uint64_t untimedCommits;           /* early commits before nextDrawable                            */
    uint64_t renderEncoders;
    uint64_t blitEncoders;
    uint64_t blitSplits;               /* blit encoder ended because of an intra-encoder hazard         */
    uint64_t setBuffer;                /* set{Vertex,Fragment}Buffer:offset:atIndex:                    */
    uint64_t setBufferOffset;          /* set{Vertex,Fragment}BufferOffset:atIndex:                     */
    uint64_t setBytes;                 /* set{Vertex,Fragment}Bytes (push constants)                    */
    uint64_t setTexture;
    uint64_t setSampler;
    uint64_t setPipelineState;
    uint64_t setDepthStencilState;
    uint64_t setRasterState;           /* cull, winding, fill mode, depth bias                          */
    uint64_t setScissor;
    uint64_t dummyBindings;            /* buffers / textures / samplers bound by the dummy policy       */
    uint64_t clearQuads;
    uint64_t draws;                    /* Metal draw calls                                              */
    uint64_t standaloneTimestamps;     /* timestamps sampled by a dedicated blit pass                   */
    /* M2 */
    uint64_t frames;                   /* FRAME_END submits                                             */
    uint64_t passesIn;                 /* BEGIN_PASS records decoded                                    */
    uint64_t passEncoders;             /* render encoders created for BEGIN_PASS records (encoders out) */
    uint64_t merges;                   /* passes continued in the previous pass's encoder               */
    uint64_t mergeBreaks;              /* merged encoders ended early by an unplanned record            */
    uint64_t hoists;                   /* inter-pass copies encoded before a merged encoder             */
    uint64_t clearsRecorded;           /* (texture, level) clears recorded as pending                   */
    uint64_t clearsFolded;             /* pending clears turned into a pass load action                 */
    uint64_t clearsDropped;            /* pending clears overridden by a later clear (texture or pass)  */
    uint64_t clearsMaterialized;       /* pending clears encoded as clear passes                        */
    uint64_t clearDraws;               /* in-encoder depth-clear draws at merge points                  */
    uint64_t dontCareStores;           /* attachments resolved to DontCare at encoder end               */
    uint64_t lateStores;               /* attachments resolved to Store at encoder end                  */
    /* CPU timing (ns) */
    uint64_t submitNs;                 /* wall time inside mcmtl_decoder_submit                         */
    uint64_t drawableWaitNs;           /* wall time inside -[CAMetalLayer nextDrawable]                 */
    /* Present fusion (CONTRACT.md 16.3) */
    uint64_t fusedFrames;              /* PRESENTs whose frame was rendered straight into the drawable  */
    uint64_t fusedPasses;              /* BEGIN_PASS records whose color 0 was the drawable             */
    uint64_t fusionWaitNs;             /* nextDrawable wait before a fused encoder (in drawableWaitNs)  */
    uint64_t fusionRestores;           /* stale sources rebuilt from the drawable before a read         */
    uint64_t fusionStaleDropped;       /* stale sources overwritten (or unreferenced for a whole frame) */
    uint64_t fusionVariantBuilds;      /* BGRA8 PSO variants compiled (asynchronously)                  */
    uint64_t fusionBackoffs;           /* times the adaptive guard turned fusion off                    */
    uint64_t fusionFallback[DEC_FUSE_REASONS]; /* PRESENTs not fused, by DEC_FUSE_R_*                */
    /* Presentation (CONTRACT.md 16.4) */
    uint64_t presentRecords;           /* PRESENT records decoded (frames rendered for a surface)       */
    uint64_t presentsEncoded;          /* ... presented (fused or present pass; PRESENT_ENCODED)        */
    uint64_t presentsNil;              /* ... blocking nextDrawable returned nil (DRAWABLE_NIL)         */
    uint64_t mailboxRecords;           /* ... decoded while the surface was in mailbox mode             */
    uint64_t presentsSkipped;          /* ... mailbox: rendered but not presented                        */
    uint64_t presentSkip[DEC_SKIP_REASONS]; /* presentsSkipped by DEC_SKIP_*                          */
    /* Terrain ICB (CONTRACT.md 16.5) */
    uint64_t indirectRecordDraws;      /* argument records drawn as one Metal draw each (per-record path)  */
    uint64_t icbGroups;                /* DRAW_INDEXED_INDIRECT records executed as one ICB range        */
    uint64_t icbRecords;               /* argument records in those ranges                               */
    uint64_t icbHits;                  /* ranges whose commands were reused (records, index buffer same) */
    uint64_t icbEncodedRecords;        /* ICB commands encoded on the CPU (cache misses)                 */
    uint64_t icbCreated;               /* MTLIndirectCommandBuffers allocated                            */
    uint64_t icbArgBuffers;            /* texture argument buffers written                               */
    uint64_t icbVariantBuilds;         /* ICB PSO variants requested                                     */
    uint64_t icbFallback[DEC_ICB_REASONS]; /* DRAW_INDEXED_INDIRECT records drawn per record, by DEC_ICB_R_* */
    /* Terrain occlusion culling (MCMTL_OCCLUSION, CONTRACT.md 16.6) */
    uint64_t occlFrames;               /* FRAME_END submits with an occlusion plan                      */
    uint64_t occlSections;             /* sections referenced by planned terrain records               */
    uint64_t occlCulledSections;       /* sections whose records were not encoded                      */
    uint64_t occlWouldCull;            /* dry run (MCMTL_OCCLUSION=2): sections that would be culled     */
    uint64_t occlSkippedRecords;       /* indirect records not encoded                                 */
    uint64_t occlSkippedQuads;         /* their quads (indexCount / 6)                                 */
    uint64_t occlPlannedQuads;         /* quads of all planned terrain records (encoded or not)        */
    uint64_t occlGeomQueries;          /* sections whose opaque records were counted                   */
    uint64_t occlBoxTests;             /* box queries drawn after the opaque terrain                   */
    uint64_t occlEyeResets;            /* camera position / projection changes: evidence dropped       */
    uint64_t occlInvalidated;          /* sections whose evidence a nearby mesh change dropped         */
    uint64_t occlResultFrames;         /* frames of query results applied                              */
    uint64_t occlResultsDropped;       /* frames of results discarded (stale, incomplete)              */
    uint64_t occlPlanNs;               /* CPU time of the planner                                       */
    uint64_t occlNoPlan[8];            /* FRAME_END submits without a plan, by DEC_OCCL_NP_*           */
} mcmtl_decoder_stats;

/* Rate-limited message kinds. */
enum {
    DEC_MSG_NO_PIPELINE, DEC_MSG_VARIANT, DEC_MSG_MISSING_VB, DEC_MSG_NO_INDEX, DEC_MSG_INDEX_RANGE,
    DEC_MSG_INDIRECT, DEC_MSG_FAN, DEC_MSG_COPY, DEC_MSG_CLEAR, DEC_MSG_TIMESTAMP, DEC_MSG_PRESENT,
    DEC_MSG_INIT, DEC_MSG_INTERNAL, DEC_MSG_BINDING, DEC_MSG_COUNT
};

/* ---------------------------------------------------------------------------------------------- */
/* Persistent decoder state (dev->decoderState)                                                     */
/* ---------------------------------------------------------------------------------------------- */

typedef struct dec_hz_slot {
    const void *res;
    uint32_t gen;
    uint32_t head;    /* index + 1 of the newest entry, 0 = none                                          */
    uint32_t count;
    uint32_t pad;
} dec_hz_slot;

typedef struct dec_hz_entry {
    uint32_t next;    /* index + 1, 0 = end                                                              */
    uint32_t write;
    uint32_t texture;
    uint32_t sub;     /* texture: level | slice << 16, DEC_SUB_ALL = everything                          */
    uint64_t lo, hi;  /* buffer: [lo, hi) bytes; texture: x0 | y0 << 32, x1 | y1 << 32 (exclusive)      */
} dec_hz_entry;

/* An access of one blit command. */
typedef struct dec_access {
    const void *res;
    uint32_t write;
    uint32_t texture;
    uint32_t sub;
    uint32_t pad;
    uint64_t lo, hi;
} dec_access;

/* M2 stream index: one entry per BEGIN_PASS .. END_PASS of the main stream (CONTRACT.md 4.7 look-ahead). */
enum { DEC_PASS_HAS_TS = 1u };

typedef struct dec_pass_info {
    uint64_t begin;          /* offset of BEGIN_PASS                                                       */
    uint64_t end;            /* offset of its END_PASS                                                     */
    uint32_t sampledStart;   /* sorted unique parent textures sampled in the pass: idxSampled[start..+n)   */
    uint32_t sampledCount;
    uint32_t readStart;      /* sorted unique buffers read in the pass with the lowest offset bound        */
    uint32_t readCount;
    uint32_t flags;          /* DEC_PASS_*                                                                 */
    uint32_t pipeStart;      /* pipelines set in the pass (present fusion only): idxPipes[start..+n),      */
    uint32_t pipeCount;      /* consecutive repeats removed                                                */
    uint32_t pad;
} dec_pass_info;

/* A buffer read by a pass: every byte from `lo` to the end of the buffer may be read (bindings carry no
 * length). */
typedef struct dec_buf_read {
    const void *buffer;
    uint64_t lo;
} dec_buf_read;

/* Cached internal PSO keyed by a small integer key. */
@interface MCMTLDecoderPSO : NSObject {
@public
    uint32_t key[16];
    id<MTLRenderPipelineState> pso;
}
@end
@implementation MCMTLDecoderPSO
@end

/* Present fusion: BGRA8 variants compiled by Metal's completion handlers, handed to the render thread. */
@interface MCMTLFuseVariant : NSObject {
@public
    MCMTLPipeline              *pipeline;
    uint64_t                    key;
    id<MTLRenderPipelineState>  pso;
    NSString                   *failure;
}
@end
@implementation MCMTLFuseVariant
@end

@interface MCMTLFuseMailbox : NSObject {
@public
    os_unfair_lock              lock;
    NSMutableArray<MCMTLFuseVariant *> *done;
}
@end
@implementation MCMTLFuseMailbox
@end

/* Terrain ICB cache slot (CONTRACT.md 16.5): the ICB of the n-th DRAW_INDEXED_INDIRECT of a call, with the argument
 * records and index buffer its commands were encoded from. Also the entry type of the retired list. */
@interface MCMTLIcbSlot : NSObject {
@public
    id<MTLIndirectCommandBuffer> icb;
    uint32_t                     capacity;       /* commands                                                   */
    uint32_t                     count;          /* commands encoded (= records), 0 = nothing cached           */
    uint8_t                     *records;        /* copy of the 20-byte records (malloc, recordCapacity)       */
    uint32_t                     recordCapacity;
    id<MTLBuffer>                indexBuffer;    /* strong: the encoded commands hold its GPU address, and the
                                                  * object (so its pointer) cannot be reused while cached      */
    uint64_t                     indexLength;
    uint32_t                     indexType;
    uint32_t                     primitiveType;
    uint64_t                     lastUse;        /* timeline value that completes the last GPU use of `icb`     */
}
@end
@implementation MCMTLIcbSlot
- (void)dealloc {
    free(records);
}
@end

/* A buffer written by a decoder copy, readable by the CPU only once `value` completed (terrain ICB records guard). */
typedef struct dec_gpu_write {
    const void *buffer;
    uint64_t value;
} dec_gpu_write;

/* Terrain occlusion culling (MCMTL_OCCLUSION; CONTRACT.md 16.6, docs/metal/OCCLUSION.md). Types used by the
 * persistent state below; the logic lives in the "Terrain occlusion culling" section. */
#define DEC_OCCL_RING 8u                 /* frames of queries in flight / waiting to be read            */

enum { DEC_OCCL_OFF = 0, DEC_OCCL_CULL = 1, DEC_OCCL_DRY = 2 };
/* Why a FRAME_END submit had no plan (stats.occlNoPlan[]). */
enum { DEC_OCCL_NP_NO_INDEX = 0,   /* no look-ahead index (M2 off, malformed stream)                           */
       DEC_OCCL_NP_NO_TERRAIN,     /* no opaque multidraw terrain draw in the call                             */
       DEC_OCCL_NP_PASSES,         /* opaque terrain draws in more than one pass                               */
       DEC_OCCL_NP_UNRESOLVED,     /* records / section origins / uniforms not readable on the CPU at decode   */
       DEC_OCCL_NP_TRANSFORM,      /* terrain draws with different camera transforms                           */
       DEC_OCCL_NP_RECORDS,        /* a record the planner does not handle (instanceCount != 1, out of range)  */
       DEC_OCCL_NP_RING,           /* every query buffer still in flight                                       */
       DEC_OCCL_NP_REASONS };

typedef struct dec_occl_cmd {      /* one terrain DRAW_INDEXED_INDIRECT of the main stream (stream order)       */
    uint64_t offset;               /* stream offset of the record                                              */
    uint32_t pass;                 /* index entry of its pass                                                   */
    uint32_t klass;                /* DEC_OCCL_CLASS_*                                                          */
    const void *pipeline;
    mcmtl_handle vb0, vb1, ib, args;
    uint64_t vb0Offset, vb1Offset, argsOffset;
    mcmtl_handle ubo[3];           /* vertex [[buffer(0)]] Projection, (1) Globals, (5) TerrainUniform          */
    uint64_t uboOffset[3];
    uint32_t drawCount;
    uint32_t firstRec;             /* its records' decisions: occl->recDec[firstRec ..]                         */
    uint32_t cullable;             /* drawn in the opaque terrain's pass                                         */
    uint32_t pad;
} dec_occl_cmd;

typedef struct dec_occl_write {    /* a buffer write of the main stream (COPY_BUFFER: CPU source known)        */
    uint64_t offset;
    const void *dst;
    uint64_t lo, hi;
    const void *src;               /* NULL: no CPU-visible source (readbacks)                                   */
    uint64_t srcOffset;
} dec_occl_write;

typedef struct dec_occl_prior {    /* a buffer write of an earlier call that may not have executed yet         */
    const void *dst;
    uint64_t lo, hi;
    uint64_t value;                /* timeline value whose command buffer executes it; UINT64_MAX until known  */
} dec_occl_prior;

typedef struct dec_occl_sec {      /* per-section state, keyed by the section origin                           */
    int32_t x, y, z;
    uint32_t used;
    uint64_t epoch;                /* state is reset when it differs from occl->epoch                          */
    uint64_t seenFrame;            /* last planned frame whose records referenced it                           */
    uint64_t sig, sigNew;          /* hash of its records (previous planned frame / this frame)                */
    uint64_t evidenceFrom;         /* query results of frames before this one are ignored                       */
    uint64_t retestAfter;          /* candidate whose box was visible: no box query before this frame          */
    uint64_t lastBoxFrame;         /* frame of the newest box result applied                                   */
    uint64_t geomVisibleFrame;     /* frame of the newest geometry result with a sample                        */
    uint32_t boxRun;               /* consecutive box queries with no sample, fully inside the frustum          */
    int32_t geomSlot, boxSlot;     /* this frame's query slots (-1 = none)                                      */
    uint8_t candidate;             /* newest geometry query of its opaque records had no sample                */
    uint8_t culled;                /* this frame: records not encoded                                          */
    uint8_t drawnPrev;             /* encoded in the previous planned frame (valid while prevSeen)             */
    uint8_t prevSeen;              /* this frame: it was in the previous planned frame                          */
    uint8_t hasOpaque, inside, nearEye;
    uint8_t query;                 /* this frame: its encoded opaque records are counted                       */
    float rect[4];                 /* this frame: NDC rect of its box (x0, y0, x1, y1)                         */
} dec_occl_sec;

typedef struct dec_occl_frame {    /* queries of one planned frame (ring entry)                                 */
    uint64_t value;                /* timeline value whose completion makes the results final                  */
    uint64_t frame;
    uint64_t epoch;
    uint32_t state;                /* DEC_OCCL_FS_*                                                             */
    uint32_t slots, geomSlots, cap;
    int32_t *keys;                 /* 3 per slot: section origin                                                */
    uint8_t *flags;                /* DEC_OCCL_SLOT_* per slot                                                  */
} dec_occl_frame;

@interface MCMTLOcclState : NSObject {
@public
    uint32_t mode, confirm, minAge;
    float nearBlocks;
    uint64_t frame;                /* planned-frame clock (FRAME_END submits)                                   */
    uint64_t epoch;                /* bumped by every global reset (eye / projection change, unplanned frame) */
    uint64_t lockKey;
    BOOL haveLock;
    NSMapTable<MCMTLPipeline *, NSNumber *> *classes; /* weak keys: terrain classification per pipeline   */
    /* Index of the call (main stream). */
    dec_occl_cmd *cmds; uint32_t cmdCount, cmdCap;
    dec_occl_write *writes; uint32_t writeCount, writeCap;
    dec_occl_prior *prior; uint32_t priorCount, priorCap;
    uint64_t wildcardValue;        /* a call with writes the index did not see: nothing resolves before it ran */
    /* Sections: open addressing, power-of-two capacity. */
    dec_occl_sec *secs; uint32_t secCap, secUsed;
    uint32_t *cur; uint32_t curCount, curCap;          /* entries of this frame                               */
    int32_t *prevKeys; uint8_t *prevDrawn; uint32_t prevCount, prevCap; /* previous planned frame          */
    uint64_t prevFrame;
    uint32_t *recDec; uint32_t recCount, recCap;       /* per record: DEC_OCCL_REC_* | geometry slot + 1      */
    uint32_t *boxes; uint32_t boxCount, boxCap;        /* entries box-tested this frame                       */
    /* This frame's plan. */
    float vp[16];                  /* Projection x ModelView (camera-relative, like terrain.vsh)               */
    int32_t camBlock[3];
    float camOffset[3];
    uint64_t terrainPassBegin, lastOpaqueOffset;
    uint32_t cmdCursor;
    uint32_t building;             /* ring entry of the frame being decoded                                    */
    dec_occl_frame ring[DEC_OCCL_RING];
    id<MTLBuffer> ringVis[DEC_OCCL_RING];
    id<MTLLibrary> library;
}
@end

@interface MCMTLDecoderState : NSObject {
@public
    id<MTLLibrary>              library;          /* static internal shaders (present)                 */
    NSMutableArray<MCMTLDecoderPSO *> *internalPSOs; /* present / clear / copy variants                */
    id<MTLDepthStencilState>    defaultDSS;       /* Always, no write                                   */
    id<MTLDepthStencilState>    alwaysWriteDSS;   /* Always, write                                      */
    MTLCommandBufferDescriptor *cbDescriptor;
    MTLCommandBufferDescriptor *cbDescriptorValidation;

    id<MTLComputePipelineState> copyPso[3];       /* compute COPY_BUFFER: 16 / 4 / 1 bytes per thread   */
    id<MTLBuffer>               zeroBuffer;       /* INIT_TEXTURE source (zeros)                        */
    id<MTLBuffer>               fanIndex16;       /* [0,1,2, 0,2,3, ...] as UInt16                      */
    uint32_t                    fanVertices16;    /* fan size covered by fanIndex16                     */
    id<MTLBuffer>               fanIndex32;
    uint32_t                    fanVertices32;
    id<MTLBuffer>               tsScratch;        /* target of the tiny fill in timestamp blit passes   */

    /* Upload ring: chunks owned by the open command buffer (render thread) and free chunks (returned by
     * completion handlers on Metal threads, guarded by ringLock). */
    os_unfair_lock              ringLock;
    NSMutableArray<id<MTLBuffer>> *ringFree;
    NSMutableArray<id<MTLBuffer>> *ringPending;
    id<MTLBuffer>               ringChunk;
    NSUInteger                  ringOffset;

    /* Blit-encoder hazard tracker (reset whenever the blit encoder ends). */
    dec_hz_slot                *hzSlots;
    dec_hz_entry               *hzEntries;
    uint32_t                    hzGen;
    uint32_t                    hzEntryCount;
    uint32_t                    hzSlotsUsed;

    NSMutableArray<NSString *> *labelStack;       /* open command-buffer debug groups (this call)       */
    NSMapTable<MCMTLPipeline *, NSMutableSet<NSNumber *> *> *failedVariants; /* weak keys               */
    uint64_t                    messageCounts[DEC_MSG_COUNT];

    mcmtl_decoder_stats         stats;
    mcmtl_decoder_stats         statsLogged;      /* snapshot at the last periodic stats log            */
    int64_t                     testRaiseAtRecord; /* -1 = off; one-shot NSException at that record    */
    void (^testOnRenderPass)(MTLRenderPassDescriptor *desc); /* decoder_test: sees every render pass, nil = off */

    /* M2 (render thread; arrays reused across calls, contents valid for one call). */
    uint32_t                    m2Flags;          /* DEC_M2_*                                           */
    id<MTLLibrary>              depthClearLibrary; /* full-screen [[depth(any)]] clear (lazy)           */
    dec_pass_info              *idxPasses;
    uint32_t                    idxPassCap;
    const void                **idxSampled;
    uint32_t                    idxSampledCap;
    dec_buf_read               *idxReads;
    uint32_t                    idxReadCap;
    uint64_t                   *hoisted;          /* offsets of copies already encoded early (sorted)    */
    uint32_t                    hoistedCap;

    /* Present fusion (CONTRACT.md 16.3; render thread except the mailbox). */
    uint32_t                    fuseMode;         /* 0 off, 1 fuse, 2 flipped layer without fusion       */
    uint32_t                    fuseTestFlags;    /* DEC_FUSE_TEST_*                                     */
    uint64_t                    fuseMaxWaitNs;    /* adaptive guard threshold (0 = never back off)      */
    uint64_t                    fuseWaitEma;      /* ns, moving average of fused early waits             */
    uint32_t                    fuseBackoff;      /* frames left without fusion                          */
    uint32_t                    fuseBackoffPeriod; /* next backoff length (doubles while it re-triggers) */
    uint32_t                    fuseFusedRun;     /* fused frames since the last backoff                 */
    const void                **idxPipes;         /* per-pass pipelines (index)                          */
    uint32_t                    idxPipeCap;
    MCMTLFuseMailbox           *fuseMailbox;
    NSMapTable<MCMTLPipeline *, NSMutableSet<NSNumber *> *> *fusePending; /* weak keys: builds in flight */
    id<MTLTexture>              staleTexture;     /* fused source whose content lives in staleDrawable   */
    uint32_t                    staleLevel;
    id<CAMetalDrawable>         staleDrawable;
    uint64_t                    staleValue;       /* timeline value of the fused frame (its last command buffer) */
    uint32_t                    fuseTraceCalls;   /* MCMTL_FUSION_TRACE: FRAME_END calls still followed   */

    /* Mailbox presentation (CONTRACT.md 16.4). */
    BOOL                        mailboxLatePresent; /* MCMTL_MAILBOX_LATE_PRESENT                        */
    __weak MCMTLSurface        *presentSurface;   /* last PRESENT's surface (stats log)                  */
    mcmtl_mailbox_stats         mailboxLogged;    /* its counters at the last stats log                  */
    const void                 *mailboxLoggedSurface; /* identity of the surface of that snapshot        */
    uint64_t                    statsLoggedNs;    /* time of the last stats log (0 = none yet)           */

    /* Terrain ICB (CONTRACT.md 16.5; render thread). */
    uint32_t                    icbMode;          /* 0 per-record draws, 1 ICB ranges                    */
    uint32_t                    icbTestFlags;     /* DEC_ICB_TEST_*                                      */
    uint32_t                    icbMinRecords;    /* MCMTL_TERRAIN_ICB_MIN_RECORDS                       */
    MTLIndirectCommandBufferDescriptor *icbDescriptor;
    NSMutableArray<MCMTLIcbSlot *> *icbSlots;     /* by ordinal within a call (grown up to DEC_ICB_SLOTS) */
    NSMutableArray<MCMTLIcbSlot *> *icbRetired;   /* replaced while in flight; reused once complete      */
    dec_gpu_write              *icbWrites;        /* DEC_ICB_WRITES entries                              */
    uint64_t                    icbWriteOverflow; /* table full: every buffer suspect until this value   */

    /* Terrain occlusion culling (nil unless MCMTL_OCCLUSION is 1 or 2). */
    MCMTLOcclState             *occl;

    /* Copy-destination statistics (NULL unless MCMTL_COPY_STATS; CONTRACT.md 16.R5-C). */
    struct dec_copy_stats      *copyStats;
}
@end

@implementation MCMTLOcclState
- (void)dealloc {
    free(cmds);
    free(writes);
    free(prior);
    free(secs);
    free(cur);
    free(prevKeys);
    free(prevDrawn);
    free(recDec);
    free(boxes);
    for (uint32_t i = 0; i < DEC_OCCL_RING; i++) {
        free(ring[i].keys);
        free(ring[i].flags);
    }
}
@end

static MCMTLOcclState *dec_occl_create(uint32_t mode, uint32_t confirm, float nearBlocks, uint32_t minAge);
static MCMTLOcclState *dec_occl_env(void);

@implementation MCMTLDecoderState
- (void)dealloc {
    free(hzSlots);
    free(hzEntries);
    free(idxPasses);
    free(idxSampled);
    free(idxReads);
    free(hoisted);
    free(idxPipes);
    free(icbWrites);
    free(copyStats);
}
@end

/* ---------------------------------------------------------------------------------------------- */
/* Copy-destination statistics (MCMTL_COPY_STATS=1, or =N frames per log; CONTRACT.md 16.R5-C)      */
/* ---------------------------------------------------------------------------------------------- */

/* Diagnostics for TAIL-ANALYSIS S3: which buffers / textures the frame's transfer records write, and whether the copy
 * sits in a blit encoder before the frame's first render encoder ("lead", the MST's Blit0: a write there into a
 * resource the previous frame still reads makes the whole frame wait for that frame's fragment work under Metal's
 * tracked hazards) or was hoisted before a merged encoder (B6). Every N FRAME_END submits (default 600) one NSLog line
 * per destination, most copies first, with the label (Java labels every buffer while MCMTL_COPY_STATS is set), the
 * resource size, copies / bytes per frame and the share of frames that wrote it. Off: one NULL test per copy record. */
#define DEC_COPY_DSTS 64u
typedef struct dec_copy_dst {
    const void *object;         /* MTLBuffer / MTLTexture identity (never dereferenced after the copy)            */
    uint64_t    length;         /* buffer length; textures: width | height << 32                                  */
    uint32_t    texture;
    uint32_t    pad;
    uint64_t    copies;
    uint64_t    lead;           /* ... encoded before the frame's first render encoder                             */
    uint64_t    hoisted;        /* ... encoded by an M2 hoist (dec_run_hoists)                                     */
    uint64_t    bytes;
    uint64_t    frames;         /* frames with at least one copy into it                                           */
    uint64_t    lastFrame;
    char        label[96];
} dec_copy_dst;

typedef struct dec_copy_stats {
    uint32_t     interval;
    uint32_t     used;
    uint64_t     frame;         /* FRAME_END submits in this window                                                */
    uint64_t     leadFrames;    /* frames whose lead blit encoded >= 1 copy                                        */
    uint64_t     dropped;       /* copies whose destination did not fit the table                                  */
    BOOL         renderSeen;    /* this frame created a render encoder already                                     */
    BOOL         leadThisFrame;
    BOOL         hoisting;      /* inside dec_run_hoists                                                           */
    dec_copy_dst dsts[DEC_COPY_DSTS];
} dec_copy_stats;

static dec_copy_stats *dec_copy_stats_env(void) {
    const char *v = getenv("MCMTL_COPY_STATS");
    const long n = v ? atol(v) : 0;
    if (n <= 0) return NULL;
    dec_copy_stats *cs = calloc(1, sizeof(dec_copy_stats));
    if (cs) cs->interval = n > 1 ? (uint32_t)n : 600u;
    return cs;
}

static void dec_copy_note(MCMTLDecoderState *ds, __unsafe_unretained id<MTLResource> object, BOOL texture, uint64_t length, uint64_t bytes) {
    dec_copy_stats *cs = ds->copyStats;
    const void *key = (__bridge const void *)object;
    dec_copy_dst *d = NULL;
    for (uint32_t i = 0; i < cs->used; i++) {
        if (cs->dsts[i].object == key && cs->dsts[i].length == length && cs->dsts[i].texture == (uint32_t)texture) {
            d = &cs->dsts[i];
            break;
        }
    }
    if (!d) {
        if (cs->used == DEC_COPY_DSTS) {
            cs->dropped++;
            return;
        }
        d = &cs->dsts[cs->used++];
        memset(d, 0, sizeof *d);
        d->object = key;
        d->length = length;
        d->texture = texture;
        NSString *label = object.label;
        snprintf(d->label, sizeof d->label, "%s", label.length ? label.UTF8String : "(unlabelled)");
    }
    d->copies++;
    d->bytes += bytes;
    if (!cs->renderSeen) {
        d->lead++;
        cs->leadThisFrame = YES;
    }
    if (cs->hoisting) d->hoisted++;
    if (d->lastFrame != cs->frame + 1) {
        d->lastFrame = cs->frame + 1;
        d->frames++;
    }
}

static void dec_copy_frame_end(MCMTLDecoderState *ds) {
    dec_copy_stats *cs = ds->copyStats;
    cs->frame++;
    if (cs->leadThisFrame) cs->leadFrames++;
    cs->leadThisFrame = NO;
    cs->renderSeen = NO;
    if (cs->frame < cs->interval) return;
    const double f = (double)cs->frame;
    uint64_t copies = 0, lead = 0, hoisted = 0, bytes = 0;
    uint32_t order[DEC_COPY_DSTS];
    for (uint32_t i = 0; i < cs->used; i++) {
        const dec_copy_dst *d = &cs->dsts[i];
        copies += d->copies;
        lead += d->lead;
        hoisted += d->hoisted;
        bytes += d->bytes;
        uint32_t j = i;
        while (j > 0 && cs->dsts[order[j - 1]].copies < d->copies) {
            order[j] = order[j - 1];
            j--;
        }
        order[j] = i;
    }
    NSLog(@"mcmtl copy stats (%llu frames): %.1f%% of frames have a copy before their first render encoder; per frame %.2f copies "
          "(%.2f before the first render encoder, %.2f hoisted), %.0f bytes; %u destinations%s",
          (unsigned long long)cs->frame, 100.0 * (double)cs->leadFrames / f, (double)copies / f, (double)lead / f, (double)hoisted / f,
          (double)bytes / f, cs->used, cs->dropped ? " (table full: some copies not attributed)" : "");
    for (uint32_t k = 0; k < cs->used && k < 24; k++) {
        const dec_copy_dst *d = &cs->dsts[order[k]];
        char size[48];
        if (d->texture) {
            snprintf(size, sizeof size, "texture %llux%llu", (unsigned long long)(d->length & 0xFFFFFFFFull), (unsigned long long)(d->length >> 32));
        } else {
            snprintf(size, sizeof size, "buffer %llu B", (unsigned long long)d->length);
        }
        NSLog(@"mcmtl copy stats:   %-48s %-22s per frame %.2f copies (lead %.2f, hoisted %.2f), %.0f bytes; in %.1f%% of frames",
              d->label, size, (double)d->copies / f, (double)d->lead / f, (double)d->hoisted / f, (double)d->bytes / f,
              100.0 * (double)d->frames / f);
    }
    const uint32_t interval = cs->interval;
    memset(cs, 0, sizeof *cs);
    cs->interval = interval;
}

/* ---------------------------------------------------------------------------------------------- */
/* Internal shaders                                                                                 */
/* ---------------------------------------------------------------------------------------------- */

/* Present pass (spec 6.6): full-screen triangle, drawable pixel (x, y) reads source texel
 * (x, rowBase + rowStep * y) at `mip`, alpha forced to 1. Upright layer: rowBase = copyH - 1, rowStep = -1 (the
 * Vulkan / spec flip). Flipped layer (present fusion, drawable row 0 shown at the bottom): rowStep = +1, the copy
 * occupies drawable rows [drawableH - copyH, drawableH) (top of the window), rowBase = copyH - drawableH.
 * Present-fusion restore (CONTRACT.md 16.3): color 0 is the stale source level; the fragment reads the same pixel of a
 * read-only texture that aliases the previous fused frame's drawable IOSurface (the drawable itself is never attached
 * again once presented: on the direct-to-display path that hangs the GPU). */
static NSString *const kStaticShaderSource =
    @"#include <metal_stdlib>\n"
     "using namespace metal;\n"
     "struct mcmtl_vout { float4 pos [[position]]; };\n"
     "vertex mcmtl_vout mcmtl_present_vs(uint vid [[vertex_id]]) {\n"
     "    float2 p = float2((vid << 1) & 2, vid & 2);\n"
     "    mcmtl_vout o; o.pos = float4(p * 2.0 - 1.0, 0.0, 1.0); return o;\n"
     "}\n"
     "struct mcmtl_present_params { int rowBase; int rowStep; uint mip; uint pad; };\n"
     "fragment half4 mcmtl_present_fs(mcmtl_vout in [[stage_in]],\n"
     "                                texture2d<half, access::read> src [[texture(0)]],\n"
     "                                constant mcmtl_present_params &P [[buffer(0)]]) {\n"
     "    uint2 d = uint2(in.pos.xy);\n"
     "    uint2 s = uint2(d.x, uint(P.rowBase + P.rowStep * int(d.y)));\n"
     "    return half4(src.read(s, P.mip).rgb, 1.0h);\n"
     "}\n"
     "fragment float4 mcmtl_restore_fs(mcmtl_vout in [[stage_in]], texture2d<float, access::read> src [[texture(0)]]) {\n"
     "    return src.read(uint2(in.pos.xy));\n"
     "}\n";

/* Parameter block of mcmtl_present_fs. */
typedef struct dec_present_params {
    int32_t rowBase;
    int32_t rowStep;
    uint32_t mip;
    uint32_t pad;
} dec_present_params;

/* Common prologue of the generated clear / copy shaders. The vertex stage emits a full-screen triangle at
 * depth P.depth (buffer 0, first float of the parameter block). */
static NSString *const kGeneratedPrologue =
    @"#include <metal_stdlib>\n"
     "using namespace metal;\n"
     "struct mcmtl_vout { float4 pos [[position]]; };\n"
     "struct mcmtl_quad_params { float depth; float pad0; float pad1; float pad2; float4 colors[8]; int2 offset; uint level; uint pad3; };\n"
     "vertex mcmtl_vout mcmtl_quad_vs(uint vid [[vertex_id]], constant mcmtl_quad_params &P [[buffer(0)]]) {\n"
     "    float2 p = float2((vid << 1) & 2, vid & 2);\n"
     "    mcmtl_vout o; o.pos = float4(p * 2.0 - 1.0, P.depth, 1.0); return o;\n"
     "}\n";

/* Parameter block of the generated shaders (layout of mcmtl_quad_params). */
typedef struct dec_quad_params {
    float depth;
    float pad[3];
    float colors[8][4];
    int32_t offset[2];
    uint32_t level;
    uint32_t pad3;
} dec_quad_params;

static const char *dec_kind_vector(uint32_t kind) {
    switch (kind) {
        case DEC_KIND_INT: return "int4";
        case DEC_KIND_UINT: return "uint4";
        default: return "float4";
    }
}

enum { DEC_PSO_PRESENT = 1, DEC_PSO_CLEAR = 2, DEC_PSO_COPY = 3, DEC_PSO_DEPTH_CLEAR = 4, DEC_PSO_RESTORE = 5, DEC_PSO_OCCL_BOX = 6 };

/* Clear-quad key: key[0] type, key[1..8] color formats, key[9] color write mask, key[10] depth format,
 * key[11] stencil format, key[12] write depth. Copy key: key[0] type, key[1] dst color format (0 for a
 * depth destination), key[2] dst depth format, key[3] dst stencil format, key[4] source kind. */

static void dec_message(MCMTLDevice *dev, MCMTLDecoderState *ds, uint32_t kind, uint32_t *messagedThisCall,
                        const char *format, ...) __attribute__((format(printf, 5, 6)));

static void dec_message(MCMTLDevice *dev, MCMTLDecoderState *ds, uint32_t kind, uint32_t *messagedThisCall,
                        const char *format, ...) {
    if (messagedThisCall) {
        if (*messagedThisCall & (1u << kind)) return;
        *messagedThisCall |= 1u << kind;
    }
    uint64_t n = ++ds->messageCounts[kind];
    if (n > 3 && (n % 600) != 0) return;
    char buffer[768];
    va_list args;
    va_start(args, format);
    vsnprintf(buffer, sizeof buffer, format, args);
    va_end(args);
    if (n > 3) {
        mcmtl_device_message(dev, "decoder: %s (repeated %llu times)", buffer, (unsigned long long)n);
    } else {
        mcmtl_device_message(dev, "decoder: %s", buffer);
    }
}

static MCMTLDecoderPSO *dec_find_pso(MCMTLDecoderState *ds, const uint32_t key[16]) {
    for (MCMTLDecoderPSO *entry in ds->internalPSOs) {
        if (memcmp(entry->key, key, sizeof entry->key) == 0) return entry;
    }
    return nil;
}

static id<MTLRenderPipelineState> dec_store_pso(MCMTLDecoderState *ds, const uint32_t key[16], id<MTLRenderPipelineState> pso) {
    MCMTLDecoderPSO *entry = [MCMTLDecoderPSO new];
    memcpy(entry->key, key, sizeof entry->key);
    entry->pso = pso;
    [ds->internalPSOs addObject:entry];
    return pso;
}

static id<MTLLibrary> dec_compile(MCMTLDevice *dev, NSString *source, NSError **error) {
    MTLCompileOptions *options = [MTLCompileOptions new];
    options.languageVersion = MTLLanguageVersion3_2;
    return [dev->device newLibraryWithSource:source options:options error:error];
}

/* Present PSO for a drawable pixel format (BGRA8Unorm normally). nil on failure (message). */
static id<MTLRenderPipelineState> dec_present_pso(MCMTLDevice *dev, MCMTLDecoderState *ds, NSUInteger pixelFormat,
                                                  uint32_t *messaged) {
    uint32_t key[16] = { DEC_PSO_PRESENT, (uint32_t)pixelFormat };
    MCMTLDecoderPSO *entry = dec_find_pso(ds, key);
    if (entry) return entry->pso;
    MTLRenderPipelineDescriptor *desc = [MTLRenderPipelineDescriptor new];
    desc.label = @"mcmetal present";
    desc.vertexFunction = [ds->library newFunctionWithName:@"mcmtl_present_vs"];
    desc.fragmentFunction = [ds->library newFunctionWithName:@"mcmtl_present_fs"];
    desc.colorAttachments[0].pixelFormat = (MTLPixelFormat)pixelFormat;
    NSError *error = nil;
    id<MTLRenderPipelineState> pso = [dev->device newRenderPipelineStateWithDescriptor:desc error:&error];
    if (!pso) {
        dec_message(dev, ds, DEC_MSG_INTERNAL, messaged, "present PSO for pixel format %lu failed: %s",
                    (unsigned long)pixelFormat, error ? error.localizedDescription.UTF8String : "?");
        return nil;
    }
    return dec_store_pso(ds, key, pso);
}

/* Present-fusion restore PSO: reads the drawable alias (texture 0, any 8-bit unorm format) into color 0 = the stale
 * source format. nil on failure (message; cached so it is not retried every frame). */
static id<MTLRenderPipelineState> dec_restore_pso(MCMTLDevice *dev, MCMTLDecoderState *ds, uint32_t targetFormat, uint32_t *messaged) {
    uint32_t key[16] = { DEC_PSO_RESTORE, targetFormat };
    MCMTLDecoderPSO *entry = dec_find_pso(ds, key);
    if (entry) return entry->pso;
    MTLRenderPipelineDescriptor *desc = [MTLRenderPipelineDescriptor new];
    desc.label = @"mcmetal fusion restore";
    desc.vertexFunction = [ds->library newFunctionWithName:@"mcmtl_present_vs"];
    desc.fragmentFunction = [ds->library newFunctionWithName:@"mcmtl_restore_fs"];
    desc.colorAttachments[0].pixelFormat = (MTLPixelFormat)targetFormat;
    NSError *error = nil;
    id<MTLRenderPipelineState> pso = [dev->device newRenderPipelineStateWithDescriptor:desc error:&error];
    if (!pso) {
        dec_message(dev, ds, DEC_MSG_INTERNAL, messaged, "present fusion restore PSO (-> %u) failed: %s", targetFormat,
                    error ? error.localizedDescription.UTF8String : "?");
    }
    dec_store_pso(ds, key, pso);
    return pso;
}

/* Clear-quad PSO: writes colors[i] to every color slot in key->writeMask (type matching the format),
 * depth through the vertex z (depth compare Always / write per key, set by the caller). */
static id<MTLRenderPipelineState> dec_clear_pso(MCMTLDevice *dev, MCMTLDecoderState *ds, const uint32_t key[16],
                                                uint32_t *messaged) {
    MCMTLDecoderPSO *entry = dec_find_pso(ds, key);
    if (entry) return entry->pso;
    const uint32_t writeMask = key[9];
    NSMutableString *source = [NSMutableString stringWithString:kGeneratedPrologue];
    if (writeMask) {
        [source appendString:@"struct mcmtl_clear_out {\n"];
        for (uint32_t i = 0; i < MCMTL_MAX_COLOR_ATTACHMENTS; i++) {
            if (!(writeMask & (1u << i))) continue;
            [source appendFormat:@"    %s c%u [[color(%u)]];\n", dec_kind_vector(dec_format_info(key[1 + i]).kind), i, i];
        }
        [source appendString:@"};\nfragment mcmtl_clear_out mcmtl_quad_fs(constant mcmtl_quad_params &P [[buffer(0)]]) {\n"
                               "    mcmtl_clear_out o;\n"];
        for (uint32_t i = 0; i < MCMTL_MAX_COLOR_ATTACHMENTS; i++) {
            if (!(writeMask & (1u << i))) continue;
            [source appendFormat:@"    o.c%u = %s(P.colors[%u]);\n", i, dec_kind_vector(dec_format_info(key[1 + i]).kind), i];
        }
        [source appendString:@"    return o;\n}\n"];
    }
    NSError *error = nil;
    id<MTLLibrary> library = dec_compile(dev, source, &error);
    if (!library) {
        dec_message(dev, ds, DEC_MSG_INTERNAL, messaged, "clear shader compile failed: %s",
                    error ? error.localizedDescription.UTF8String : "?");
        return nil;
    }
    MTLRenderPipelineDescriptor *desc = [MTLRenderPipelineDescriptor new];
    desc.label = @"mcmetal clear quad";
    desc.vertexFunction = [library newFunctionWithName:@"mcmtl_quad_vs"];
    desc.fragmentFunction = writeMask ? [library newFunctionWithName:@"mcmtl_quad_fs"] : nil;
    for (uint32_t i = 0; i < MCMTL_MAX_COLOR_ATTACHMENTS; i++) {
        desc.colorAttachments[i].pixelFormat = (MTLPixelFormat)key[1 + i];
        desc.colorAttachments[i].writeMask = (writeMask & (1u << i)) ? MTLColorWriteMaskAll : MTLColorWriteMaskNone;
    }
    desc.depthAttachmentPixelFormat = (MTLPixelFormat)key[10];
    desc.stencilAttachmentPixelFormat = (MTLPixelFormat)key[11];
    id<MTLRenderPipelineState> pso = [dev->device newRenderPipelineStateWithDescriptor:desc error:&error];
    if (!pso) {
        dec_message(dev, ds, DEC_MSG_INTERNAL, messaged, "clear PSO failed: %s",
                    error ? error.localizedDescription.UTF8String : "?");
        return nil;
    }
    return dec_store_pso(ds, key, pso);
}

/* Copy-draw PSO: nearest texel read of the source at (pixel + offset, level), converted to the destination
 * type (color output or [[depth(any)]]). */
static id<MTLRenderPipelineState> dec_copy_pso(MCMTLDevice *dev, MCMTLDecoderState *ds, const uint32_t key[16],
                                               uint32_t *messaged) {
    MCMTLDecoderPSO *entry = dec_find_pso(ds, key);
    if (entry) return entry->pso;
    const uint32_t dstColor = key[1], dstDepth = key[2], dstStencil = key[3], srcKind = key[4];
    NSMutableString *source = [NSMutableString stringWithString:kGeneratedPrologue];
    const char *srcType;
    const char *readExpr;
    switch (srcKind) {
        case DEC_KIND_INT: srcType = "texture2d<int, access::read>"; readExpr = "float4(src.read(s, P.level))"; break;
        case DEC_KIND_UINT: srcType = "texture2d<uint, access::read>"; readExpr = "float4(src.read(s, P.level))"; break;
        case DEC_KIND_DEPTH: srcType = "depth2d<float, access::read>"; readExpr = "float4(src.read(s, P.level), 0.0, 0.0, 1.0)"; break;
        default: srcType = "texture2d<float, access::read>"; readExpr = "src.read(s, P.level)"; break;
    }
    BOOL integerCopy = (srcKind == DEC_KIND_INT || srcKind == DEC_KIND_UINT) && dstColor &&
                       (dec_format_info(dstColor).kind == DEC_KIND_INT || dec_format_info(dstColor).kind == DEC_KIND_UINT);
    if (integerCopy) readExpr = "src.read(s, P.level)";   /* integer to integer: no float round trip */
    if (dstColor) {
        const char *dstType = dec_kind_vector(dec_format_info(dstColor).kind);
        [source appendFormat:@"struct mcmtl_copy_out { %s c0 [[color(0)]]; };\n", dstType];
        [source appendFormat:@"fragment mcmtl_copy_out mcmtl_quad_fs(mcmtl_vout in [[stage_in]], %s src [[texture(0)]],\n"
                              "                                    constant mcmtl_quad_params &P [[buffer(0)]]) {\n"
                              "    uint2 s = uint2(int2(in.pos.xy) + P.offset);\n"
                              "    mcmtl_copy_out o; o.c0 = %s(%s); return o;\n}\n", srcType, dstType, readExpr];
    } else {
        [source appendFormat:@"struct mcmtl_copy_out { float depth [[depth(any)]]; };\n"
                              "fragment mcmtl_copy_out mcmtl_quad_fs(mcmtl_vout in [[stage_in]], %s src [[texture(0)]],\n"
                              "                                    constant mcmtl_quad_params &P [[buffer(0)]]) {\n"
                              "    uint2 s = uint2(int2(in.pos.xy) + P.offset);\n"
                              "    mcmtl_copy_out o; o.depth = float4(%s).x; return o;\n}\n", srcType, readExpr];
    }
    NSError *error = nil;
    id<MTLLibrary> library = dec_compile(dev, source, &error);
    if (!library) {
        dec_message(dev, ds, DEC_MSG_INTERNAL, messaged, "copy shader compile failed: %s",
                    error ? error.localizedDescription.UTF8String : "?");
        return nil;
    }
    MTLRenderPipelineDescriptor *desc = [MTLRenderPipelineDescriptor new];
    desc.label = @"mcmetal copy draw";
    desc.vertexFunction = [library newFunctionWithName:@"mcmtl_quad_vs"];
    desc.fragmentFunction = [library newFunctionWithName:@"mcmtl_quad_fs"];
    desc.colorAttachments[0].pixelFormat = (MTLPixelFormat)dstColor;
    desc.depthAttachmentPixelFormat = (MTLPixelFormat)dstDepth;
    desc.stencilAttachmentPixelFormat = (MTLPixelFormat)dstStencil;
    id<MTLRenderPipelineState> pso = [dev->device newRenderPipelineStateWithDescriptor:desc error:&error];
    if (!pso) {
        dec_message(dev, ds, DEC_MSG_INTERNAL, messaged, "copy PSO failed: %s",
                    error ? error.localizedDescription.UTF8String : "?");
        return nil;
    }
    return dec_store_pso(ds, key, pso);
}

/* M2 in-encoder depth clear: full-screen triangle whose fragment writes P.depth through [[depth(any)]] (exact
 * for every value, unlike interpolated vertex depth), color write masks 0. Used with depth compare Always /
 * write on, cull none, no depth bias. vertexOnly: no fragment stage, the depth is the vertex z -- only used
 * for 0.0, where every vertex has z = +0 and any interpolation yields exactly +0 (cheaper: no shading, hidden
 * surface removal stays on). One PSO per attachment signature (color formats per slot + depth format,
 * depth-only formats) and kind; a failed build is cached as nil so it is not retried every frame. */
static NSString *const kDepthClearFragment =
    @"struct mcmtl_depth_clear_out { float depth [[depth(any)]]; };\n"
     "fragment mcmtl_depth_clear_out mcmtl_depth_clear_fs(constant mcmtl_quad_params &P [[buffer(0)]]) {\n"
     "    mcmtl_depth_clear_out o; o.depth = P.depth; return o;\n"
     "}\n";

static id<MTLRenderPipelineState> dec_depth_clear_pso(MCMTLDevice *dev, MCMTLDecoderState *ds, const uint32_t colorFormats[8],
                                                      uint32_t depthFormat, BOOL vertexOnly, uint32_t *messaged) {
    uint32_t key[16] = { DEC_PSO_DEPTH_CLEAR };
    for (uint32_t i = 0; i < 8; i++) key[1 + i] = colorFormats[i];
    key[9] = depthFormat;
    key[10] = vertexOnly ? 1u : 0u;
    MCMTLDecoderPSO *entry = dec_find_pso(ds, key);
    if (entry) return entry->pso;
    NSError *error = nil;
    if (!ds->depthClearLibrary) {
        ds->depthClearLibrary = dec_compile(dev, [kGeneratedPrologue stringByAppendingString:kDepthClearFragment], &error);
        if (!ds->depthClearLibrary) {
            dec_message(dev, ds, DEC_MSG_INTERNAL, messaged, "depth clear shader compile failed: %s",
                        error ? error.localizedDescription.UTF8String : "?");
            dec_store_pso(ds, key, nil);
            return nil;
        }
    }
    MTLRenderPipelineDescriptor *desc = [MTLRenderPipelineDescriptor new];
    desc.label = @"mcmetal merged depth clear";
    desc.vertexFunction = [ds->depthClearLibrary newFunctionWithName:@"mcmtl_quad_vs"];
    desc.fragmentFunction = vertexOnly ? nil : [ds->depthClearLibrary newFunctionWithName:@"mcmtl_depth_clear_fs"];
    for (uint32_t i = 0; i < MCMTL_MAX_COLOR_ATTACHMENTS; i++) {
        desc.colorAttachments[i].pixelFormat = (MTLPixelFormat)colorFormats[i];
        desc.colorAttachments[i].writeMask = MTLColorWriteMaskNone;
    }
    desc.depthAttachmentPixelFormat = (MTLPixelFormat)depthFormat;
    id<MTLRenderPipelineState> pso = [dev->device newRenderPipelineStateWithDescriptor:desc error:&error];
    if (!pso) {
        dec_message(dev, ds, DEC_MSG_INTERNAL, messaged, "depth clear PSO failed: %s",
                    error ? error.localizedDescription.UTF8String : "?");
    }
    dec_store_pso(ds, key, pso);
    return pso;
}

/* ---------------------------------------------------------------------------------------------- */
/* Device init / shutdown                                                                           */
/* ---------------------------------------------------------------------------------------------- */

static id<MTLTexture> dec_make_dummy_texture(id<MTLDevice> device, BOOL cube) {
    MTLTextureDescriptor *desc = cube
        ? [MTLTextureDescriptor textureCubeDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm size:1 mipmapped:NO]
        : [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:1 height:1 mipmapped:NO];
    desc.usage = MTLTextureUsageShaderRead;
    desc.storageMode = MTLStorageModeShared;
    id<MTLTexture> texture = [device newTextureWithDescriptor:desc];
    if (!texture) return nil;
    const uint32_t zero = 0;
    for (NSUInteger slice = 0; slice < (cube ? 6u : 1u); slice++) {
        [texture replaceRegion:MTLRegionMake2D(0, 0, 1, 1) mipmapLevel:0 slice:slice withBytes:&zero bytesPerRow:4 bytesPerImage:4];
    }
    texture.label = cube ? @"mcmetal dummy cube" : @"mcmetal dummy 2D";
    return texture;
}

static void dec_copy_init(id<MTLDevice> device, MCMTLDecoderState *ds);

BOOL mcmtl_decoder_device_init(MCMTLDevice *dev) {
    if (!dev || !dev->device) {
        mcmtl_set_error("mcmtl_decoder_device_init: no device");
        return NO;
    }
    id<MTLDevice> device = dev->device;

    /* Dummies (CONTRACT.md 6.4). */
    dev->dummyBuffer = [device newBufferWithLength:MCMTL_DUMMY_UNIFORM_BYTES options:MTLResourceStorageModeShared];
    dev->dummyTexture2D = dec_make_dummy_texture(device, NO);
    dev->dummyTextureCube = dec_make_dummy_texture(device, YES);
    if (!dev->dummyBuffer || !dev->dummyTexture2D || !dev->dummyTextureCube) {
        mcmtl_set_error("mcmtl_decoder_device_init: dummy resource allocation failed");
        return NO;
    }
    memset(dev->dummyBuffer.contents, 0, dev->dummyBuffer.length);
    dev->dummyBuffer.label = @"mcmetal dummy UBO";
    {
        NSUInteger align = [device minimumTextureBufferAlignmentForPixelFormat:MTLPixelFormatR8Sint];
        NSUInteger rowBytes = align > 16 ? align : 16;
        MTLTextureDescriptor *desc = [MTLTextureDescriptor textureBufferDescriptorWithPixelFormat:MTLPixelFormatR8Sint
                                                                                            width:16
                                                                                  resourceOptions:dev->dummyBuffer.resourceOptions
                                                                                            usage:MTLTextureUsageShaderRead];
        dev->dummyTexelBuffer = [dev->dummyBuffer newTextureWithDescriptor:desc offset:0 bytesPerRow:rowBytes];
        if (!dev->dummyTexelBuffer) {
            mcmtl_set_error("mcmtl_decoder_device_init: dummy texel buffer failed");
            return NO;
        }
        dev->dummyTexelBuffer.label = @"mcmetal dummy texel buffer";
    }
    {
        MTLSamplerDescriptor *desc = [MTLSamplerDescriptor new];
        desc.sAddressMode = MTLSamplerAddressModeClampToEdge;
        desc.tAddressMode = MTLSamplerAddressModeClampToEdge;
        desc.rAddressMode = MTLSamplerAddressModeClampToEdge;
        desc.minFilter = MTLSamplerMinMagFilterNearest;
        desc.magFilter = MTLSamplerMinMagFilterNearest;
        desc.mipFilter = MTLSamplerMipFilterNotMipmapped;
        desc.normalizedCoordinates = YES;
        desc.supportArgumentBuffers = YES;   /* terrain ICB: dummies go into texture argument buffers too */
        desc.label = @"mcmetal dummy sampler";
        dev->dummySampler = [device newSamplerStateWithDescriptor:desc];
        if (!dev->dummySampler) {
            mcmtl_set_error("mcmtl_decoder_device_init: dummy sampler failed");
            return NO;
        }
    }

    /* Decoder-private state. */
    MCMTLDecoderState *ds = [MCMTLDecoderState new];
    ds->internalPSOs = [NSMutableArray array];
    ds->ringLock = OS_UNFAIR_LOCK_INIT;
    ds->ringFree = [NSMutableArray array];
    ds->ringPending = [NSMutableArray array];
    ds->labelStack = [NSMutableArray array];
    ds->failedVariants = [NSMapTable weakToStrongObjectsMapTable];
    ds->hzSlots = calloc(DEC_HZ_SLOTS, sizeof(dec_hz_slot));
    ds->hzEntries = calloc(DEC_HZ_ENTRIES, sizeof(dec_hz_entry));
    ds->hzGen = 1;
    ds->stats.structSize = (uint32_t)sizeof(mcmtl_decoder_stats);
    ds->statsLogged = ds->stats;
    ds->statsLoggedNs = clock_gettime_nsec_np(CLOCK_UPTIME_RAW);
    ds->testRaiseAtRecord = -1;
    ds->m2Flags = dec_m2_env_flags();
    ds->fuseMode = (uint32_t)mcmtl_present_fusion_env();
    if (ds->fuseMode == 1 && getenv("MCMTL_PRESENT_FUSION_FLIP_ONLY") && atoi(getenv("MCMTL_PRESENT_FUSION_FLIP_ONLY")) != 0) {
        ds->fuseMode = 2;
    }
    {
        const char *w = getenv("MCMTL_PRESENT_FUSION_MAX_WAIT_US");
        ds->fuseMaxWaitNs = (uint64_t)(w ? strtoull(w, NULL, 10) : DEC_FUSE_MAX_WAIT_US) * 1000ull;
    }
    ds->mailboxLatePresent = getenv("MCMTL_MAILBOX_LATE_PRESENT") && atoi(getenv("MCMTL_MAILBOX_LATE_PRESENT")) != 0;
    ds->fuseMailbox = [MCMTLFuseMailbox new];
    ds->fuseMailbox->lock = OS_UNFAIR_LOCK_INIT;
    ds->fuseMailbox->done = [NSMutableArray array];
    ds->fusePending = [NSMapTable weakToStrongObjectsMapTable];
    ds->icbMode = (uint32_t)mcmtl_terrain_icb_env();
    {
        const char *m = getenv("MCMTL_TERRAIN_ICB_MIN_RECORDS");
        ds->icbMinRecords = m && atoi(m) > 0 ? (uint32_t)atoi(m) : DEC_ICB_MIN_RECORDS;
        /* Diagnostics / headless tests: compile ICB variants on the render thread at first use (a hitch), so even the
         * first frames take the ICB path. */
        const char *sync = getenv("MCMTL_TERRAIN_ICB_SYNC");
        if (sync && atoi(sync) != 0) ds->icbTestFlags |= DEC_ICB_TEST_SYNC_VARIANTS;
    }
    dec_copy_init(device, ds);
    ds->icbDescriptor = [MTLIndirectCommandBufferDescriptor new];
    ds->icbDescriptor.commandTypes = MTLIndirectCommandTypeDrawIndexed;
    ds->icbDescriptor.inheritPipelineState = YES;
    ds->icbDescriptor.inheritBuffers = YES;   /* vertex buffers, UBOs, push constants, the texture argument buffer */
    ds->icbDescriptor.maxVertexBufferBindCount = 0;
    ds->icbDescriptor.maxFragmentBufferBindCount = 0;
    ds->icbSlots = [NSMutableArray array];
    ds->icbRetired = [NSMutableArray array];
    ds->icbWrites = calloc(DEC_ICB_WRITES, sizeof(dec_gpu_write));
    ds->occl = dec_occl_env();   /* MCMTL_OCCLUSION (default off; CONTRACT.md 16.6) */
    ds->copyStats = dec_copy_stats_env();   /* MCMTL_COPY_STATS (diagnostics; CONTRACT.md 16.R5-C) */
    if (!ds->hzSlots || !ds->hzEntries || !ds->icbWrites) {
        mcmtl_set_error("mcmtl_decoder_device_init: out of memory");
        return NO;
    }
    ds->defaultDSS = mcmtl_device_depth_stencil_state(dev, MTLCompareFunctionAlways, NO);
    ds->alwaysWriteDSS = mcmtl_device_depth_stencil_state(dev, MTLCompareFunctionAlways, YES);
    ds->cbDescriptor = [MTLCommandBufferDescriptor new];
    ds->cbDescriptor.retainedReferences = YES;
    ds->cbDescriptorValidation = [MTLCommandBufferDescriptor new];
    ds->cbDescriptorValidation.retainedReferences = YES;
    ds->cbDescriptorValidation.errorOptions = MTLCommandBufferErrorOptionEncoderExecutionStatus;
    ds->zeroBuffer = [device newBufferWithLength:DEC_ZERO_BUFFER_BYTES options:MTLResourceStorageModeShared];
    ds->tsScratch = [device newBufferWithLength:16 options:MTLResourceStorageModePrivate];
    if (!ds->defaultDSS || !ds->alwaysWriteDSS || !ds->zeroBuffer || !ds->tsScratch) {
        mcmtl_set_error("mcmtl_decoder_device_init: internal object creation failed");
        return NO;
    }
    memset(ds->zeroBuffer.contents, 0, ds->zeroBuffer.length);
    ds->zeroBuffer.label = @"mcmetal zero buffer";

    NSError *error = nil;
    ds->library = dec_compile(dev, kStaticShaderSource, &error);
    if (!ds->library) {
        mcmtl_set_error_nserror("mcmtl_decoder_device_init: internal shader library", error);
        return NO;
    }
    dev->decoderState = ds;
    /* Precompile the common present PSO so the first frame does not stall on it. */
    if (!dec_present_pso(dev, ds, MTLPixelFormatBGRA8Unorm, NULL)) {
        dev->decoderState = nil;
        mcmtl_set_error("mcmtl_decoder_device_init: present PSO failed");
        return NO;
    }
    return YES;
}

static uint32_t dec_stats_log_enabled(void);
static void dec_log_stats(MCMTLDecoderState *ds);

void mcmtl_decoder_device_shutdown(MCMTLDevice *dev) {
    if (!dev) return;
    MCMTLDecoderState *ds = dev->decoderState;
    /* The statistics since the last periodic log (short headless runs never reach 600 frames). */
    if (ds && dec_stats_log_enabled() && ds->stats.commandBuffersCommitted != ds->statsLogged.commandBuffersCommitted) dec_log_stats(ds);
    dev->decoderState = nil;
    dev->dummyBuffer = nil;
    dev->dummyTexture2D = nil;
    dev->dummyTextureCube = nil;
    dev->dummyTexelBuffer = nil;
    dev->dummySampler = nil;
}

/* Test/diagnostic API (decoder-private; not exported from the dylib). */
const mcmtl_decoder_stats *mcmtl_decoder_get_stats(MCMTLDevice *dev);
void mcmtl_decoder_test_raise_at_record(MCMTLDevice *dev, int64_t recordOrdinal);
uint32_t mcmtl_decoder_test_set_m2(MCMTLDevice *dev, uint32_t flags);
uint32_t mcmtl_decoder_test_set_fusion(MCMTLDevice *dev, uint32_t mode, uint32_t testFlags, uint64_t maxWaitNs);
BOOL mcmtl_decoder_test_fusion_stale(MCMTLDevice *dev);
void mcmtl_decoder_test_on_render_pass(MCMTLDevice *dev, void (^block)(MTLRenderPassDescriptor *desc));
BOOL mcmtl_decoder_test_set_mailbox_late_present(MCMTLDevice *dev, BOOL on);
uint32_t mcmtl_decoder_test_set_icb(MCMTLDevice *dev, uint32_t mode, uint32_t testFlags, uint32_t minRecords);
uint32_t mcmtl_decoder_test_set_occlusion(MCMTLDevice *dev, uint32_t mode, uint32_t confirm, float nearBlocks, uint32_t minAge);

/* Mailbox (CONTRACT.md 16.4): replaces MCMTL_MAILBOX_LATE_PRESENT; returns the previous value. */
BOOL mcmtl_decoder_test_set_mailbox_late_present(MCMTLDevice *dev, BOOL on) {
    MCMTLDecoderState *ds = dev ? dev->decoderState : nil;
    if (!ds) return NO;
    const BOOL previous = ds->mailboxLatePresent;
    ds->mailboxLatePresent = on;
    return previous;
}

/* Replaces the terrain ICB mode (0 per record, 1 ICB), test flags (DEC_ICB_TEST_*) and minimum records per ICB range
 * (0 = 1); DEC_ICB_TEST_RESET also drops every cached ICB. Returns the previous mode. */
uint32_t mcmtl_decoder_test_set_icb(MCMTLDevice *dev, uint32_t mode, uint32_t testFlags, uint32_t minRecords) {
    MCMTLDecoderState *ds = dev ? dev->decoderState : nil;
    if (!ds) return 0;
    uint32_t previous = ds->icbMode;
    ds->icbMode = mode;
    ds->icbTestFlags = testFlags & ~DEC_ICB_TEST_RESET;
    ds->icbMinRecords = minRecords ? minRecords : 1u;
    if (testFlags & DEC_ICB_TEST_RESET) {
        [ds->icbSlots removeAllObjects];
        [ds->icbRetired removeAllObjects];
    }
    return previous;
}

/* Replaces the terrain occlusion state (CONTRACT.md 16.6): mode 0 off, 1 cull, 2 dry run; everything learnt so far
 * is dropped. Only between frames (no query in flight may be read afterwards). Returns the previous mode. */
uint32_t mcmtl_decoder_test_set_occlusion(MCMTLDevice *dev, uint32_t mode, uint32_t confirm, float nearBlocks, uint32_t minAge) {
    MCMTLDecoderState *ds = dev ? dev->decoderState : nil;
    if (!ds) return 0;
    uint32_t previous = ds->occl ? ds->occl->mode : 0;
    ds->occl = dec_occl_create(mode, confirm, nearBlocks, minAge);
    return previous;
}

/* Replaces the present-fusion mode (0 off, 1 fuse, 2 flip only), test flags (DEC_FUSE_TEST_*) and the adaptive guard
 * threshold; resets the guard. Returns the previous mode. */
uint32_t mcmtl_decoder_test_set_fusion(MCMTLDevice *dev, uint32_t mode, uint32_t testFlags, uint64_t maxWaitNs) {
    MCMTLDecoderState *ds = dev ? dev->decoderState : nil;
    if (!ds) return 0;
    uint32_t previous = ds->fuseMode;
    ds->fuseMode = mode;
    ds->fuseTestFlags = testFlags;
    ds->fuseMaxWaitNs = maxWaitNs;
    ds->fuseWaitEma = 0;
    ds->fuseBackoff = 0;
    ds->fuseBackoffPeriod = 0;
    ds->fuseFusedRun = 0;
    return previous;
}

/* YES while a fused source is stale (its content lives in the last fused drawable). */
BOOL mcmtl_decoder_test_fusion_stale(MCMTLDevice *dev) {
    MCMTLDecoderState *ds = dev ? dev->decoderState : nil;
    return ds && ds->staleTexture != nil;
}

/* Calls `block` with every render pass descriptor the decoder encodes, right before its encoder is created (nil =
 * off). Lets decoder_test check which textures a call attaches (present fusion: never a presented drawable). */
void mcmtl_decoder_test_on_render_pass(MCMTLDevice *dev, void (^block)(MTLRenderPassDescriptor *desc)) {
    MCMTLDecoderState *ds = dev ? dev->decoderState : nil;
    if (ds) ds->testOnRenderPass = block;
}

/* Replaces the device's M2 flags (DEC_M2_* bits, 0 = M1 behaviour); returns the previous ones. */
uint32_t mcmtl_decoder_test_set_m2(MCMTLDevice *dev, uint32_t flags) {
    MCMTLDecoderState *ds = dev ? dev->decoderState : nil;
    if (!ds) return 0;
    uint32_t previous = ds->m2Flags;
    ds->m2Flags = flags & DEC_M2_ALL;
    return previous;
}

const mcmtl_decoder_stats *mcmtl_decoder_get_stats(MCMTLDevice *dev) {
    MCMTLDecoderState *ds = dev ? dev->decoderState : nil;
    return ds ? &ds->stats : NULL;
}

void mcmtl_decoder_test_raise_at_record(MCMTLDevice *dev, int64_t recordOrdinal) {
    MCMTLDecoderState *ds = dev ? dev->decoderState : nil;
    if (ds) ds->testRaiseAtRecord = recordOrdinal;
}

/* ---------------------------------------------------------------------------------------------- */
/* Per-call state                                                                                   */
/* ---------------------------------------------------------------------------------------------- */

typedef struct dec_ts {
    __unsafe_unretained MCMTLQueryPool *pool;
    uint32_t index;
    uint32_t pad;
} dec_ts;

/* A recorded but not yet encoded clear of one (texture, level, slice) (M2 B3). */
typedef struct dec_pending {
    __unsafe_unretained id<MTLTexture> texture;   /* parent texture (handle borrowed for the call)          */
    uint32_t level;
    uint32_t slice;
    uint8_t  color;                               /* aspects to clear                                       */
    uint8_t  depth;
    uint8_t  stencil;
    uint8_t  pad;
    float    depthValue;
    float    rgba[4];
} dec_pending;

/* Parsed and validated BEGIN_PASS (no side effects). */
typedef struct dec_pass_parse {
    uint32_t n;                                   /* colorCount                                             */
    uint32_t presentMask;                         /* bit i: color slot i has a texture                      */
    __unsafe_unretained id<MTLTexture> color[MCMTL_MAX_COLOR_ATTACHMENTS];
    uint32_t level[MCMTL_MAX_COLOR_ATTACHMENTS];
    uint32_t colorFormat[MCMTL_MAX_COLOR_ATTACHMENTS]; /* 0 for absent slots                               */
    __unsafe_unretained id<MTLTexture> depth;     /* nil = no depth attachment                              */
    uint32_t depthLevel;
    uint32_t passDepthFormat;                     /* depth texture format (variant key) or 0                */
    uint32_t quadDepthFormat;                     /* format if it has a depth aspect, else 0                */
    uint32_t stencilFormat;                       /* format if it has a stencil aspect, else 0              */
    dec_format depthInfo;
    uint32_t attW, attH;
    int32_t  area[4];                             /* clamped render area                                    */
    BOOL     empty;
    BOOL     full;
    BOOL     depthClear;                          /* effective (non-empty pass with a depth attachment)     */
    uint32_t colorClear;                          /* effective color clear mask                             */
    float    depthClearValue;
} dec_pass_parse;

/* Texture handle -> parent texture (or backing buffer for texel views), cached per call. */
typedef struct dec_tex_res {
    uint64_t handle;
    const void *root;                             /* parent-most texture, NULL for texel views              */
    const void *buffer;                           /* texel views: backing buffer                            */
    uint64_t bufferOffset;
} dec_tex_res;

/* I2-private part of the decode context (ctx->ext). Strong references to everything this call creates. */
typedef struct mcmtl_decoder_ext {
    uint32_t                              commit;          /* MCMTL_SUBMIT_FLAG_COMMIT requested      */
    uint32_t                              failed;          /* first error recorded                    */
    __strong id<MTLCommandBuffer>         commandBuffer;   /* == dev->currentCommandBuffer            */
    __strong id<MTLRenderCommandEncoder>  renderEncoder;
    __strong id<MTLBlitCommandEncoder>    blitEncoder;
    __strong id<MTLComputeCommandEncoder> copyEncoder;     /* compute COPY_BUFFER (shares the blit slot's lifetime) */
    __unsafe_unretained MCMTLDevice       *dev;
    __unsafe_unretained MCMTLDecoderState *ds;
    mcmtl_decode_ctx                     *ctx;
    mcmtl_submit_result                  *result;
    uint64_t                              signalValue;
    BOOL                                  cbHasWork;       /* the open command buffer encodes GPU work */
    BOOL                                  labels;          /* MCMTL_DEVICE_FLAG_DEBUG_LABELS          */
    BOOL                                  pipelineInvalid; /* skip draws until the next SET_PIPELINE  */
    BOOL                                  passEmpty;       /* render area is empty: skip draws        */
    BOOL                                  scissorEmpty;    /* clamped scissor is empty: skip draws    */
    BOOL                                  isInit;          /* decoding the init stream                */
    BOOL                                  streamHasTimestamps;
    uint32_t                              cbGroupDepth;    /* command-buffer debug groups pushed      */
    uint32_t                              encoderGroupDepth;
    uint32_t                              vertexBoundMask; /* [[buffer(15..30)]] bound in this pass    */
    uint32_t                              samplerBoundMask[2];
    uint32_t                              messaged;        /* DEC_MSG_* bits reported in this call    */
    uint64_t                              indexBufferLength; /* length of ctx->indexBuffer           */
    __unsafe_unretained id<MTLBuffer>     lengthCacheBuffer; /* one-entry buffer.length cache        */
    uint64_t                              lengthCacheValue;
    const uint8_t                        *stream;
    uint64_t                              streamLength;
    uint64_t                              passOffset;      /* offset of the open pass's BEGIN_PASS    */
    int64_t                               raiseAt;
    uint64_t                              recordOrdinal;
    /* Timestamps. */
    dec_ts                                startTs[DEC_MAX_PASS_TS];
    uint32_t                              startTsCount;
    dec_ts                                endOverflow[DEC_MAX_PASS_TS];
    uint32_t                              endOverflowCount;
    uint64_t                              handledTs[DEC_MAX_HANDLED_TS];
    uint32_t                              handledTsCount;

    /* ---- M2 ---- */
    uint32_t                              m2;              /* DEC_M2_* for this call                  */
    uint64_t                              curOffset;       /* offset of the record being decoded      */
    /* Main-stream index (valid while `indexed`). */
    BOOL                                  indexed;
    uint32_t                              passCount;
    uint32_t                              passCursor;      /* first index entry not yet begun         */
    uint32_t                              sampledCount;
    uint32_t                              readCount;
    uint64_t                              indexEnd;        /* records at or past it are not indexed   */
    /* Pending clears (B3). */
    uint32_t                              pendingCount;
    dec_pending                           pending[DEC_MAX_PENDING];
    /* Merge chain (B4): planned at its first BEGIN_PASS. */
    BOOL                                  chainActive;     /* the open encoder continues past END_PASS */
    BOOL                                  mergeOpen;       /* between two passes of an open chain     */
    BOOL                                  mergeClearPending; /* a planned depth clear was decoded      */
    float                                 mergeClearValue;
    uint32_t                              chainCount;      /* passes after the first                  */
    uint32_t                              chainNext;
    uint64_t                              chainBegins[DEC_MAX_CHAIN]; /* BEGIN_PASS offsets continued  */
    uint64_t                              chainEnd;        /* END_PASS offset that ends the encoder   */
    uint32_t                              clearDrawCount;  /* gap CLEAR_TEXTUREs consumed as draws    */
    uint32_t                              clearDrawNext;
    uint64_t                              clearDrawOffsets[DEC_MAX_CHAIN];
    uint32_t                              mergeGroupDepth; /* gap debug groups pushed on the encoder  */
    uint32_t                              deferredCbPops;  /* command-buffer pops owed at encoder end */
    /* Hoisted copies (B6): offsets in ds->hoisted, already encoded; skipped when reached. */
    uint32_t                              hoistCount;
    uint32_t                              hoistNext;
    /* The open pass encoder (late store actions, merge checks, depth-clear draws). */
    BOOL                                  encLateStore;    /* attachments created with Unknown stores */
    BOOL                                  encFused;        /* its color 0 is the drawable (fusion)    */
    dec_pass_parse                        enc;             /* attachments of the open pass encoder    */
    dec_tex_res                           texCache[DEC_TEX_CACHE];

    /* ---- Present fusion (CONTRACT.md 16.3) ---- */
    BOOL                                  fuseIndex;       /* index pipelines per pass                */
    uint32_t                              pipeCount;       /* ds->idxPipes entries used               */
    uint32_t                              idxPresentCount; /* PRESENT records in the indexed range    */
    uint64_t                              idxPresentOffset; /* the last of them                       */
    uint32_t                              fuseState;       /* DEC_FUSE_*                              */
    uint32_t                              fuseReason;      /* DEC_FUSE_R_* when FAILED / NIL          */
    uint64_t                              fuseCand;        /* BEGIN_PASS that acquires the drawable   */
    uint64_t                              fusePresent;     /* the PRESENT record                      */
    __unsafe_unretained id<MTLTexture>    fuseTex;         /* PRESENT source (parent texture)         */
    uint32_t                              fuseLevel;
    __unsafe_unretained MCMTLSurface     *fuseSurface;
    __strong id<CAMetalDrawable>          fuseDrawable;    /* acquired before the first fused pass    */
    uint32_t                              mailboxWhy;      /* MCMTL_MAILBOX_* of a SKIP at the fusion pass */

    /* ---- Terrain ICB (CONTRACT.md 16.5) ---- */
    uint32_t                              icbOrdinal;      /* DRAW_INDEXED_INDIRECTs taken by the ICB path (cache slot) */
    BOOL                                  icbPsoBound;     /* the encoder runs an ICB variant: restore basePso before a
                                                            * per-record or direct draw                               */
    uint64_t                              psoKey;          /* variant key SET_PIPELINE selected (BGRA8 flag included) */
    __unsafe_unretained id<MTLRenderPipelineState> basePso; /* the PSO SET_PIPELINE bound                          */
    /* Per render encoder (reset in dec_render_encoder). */
    uint32_t                              icbUsedCount;
    __unsafe_unretained id                icbUsed[DEC_ICB_USED];        /* useResource: done for these          */
    uint32_t                              icbUsedStages[DEC_ICB_USED];
    BOOL                                  icbArgValid[2];               /* [[buffer(15)]] holds the texture handles */
    __unsafe_unretained id<MTLBuffer>     icbArgBuffer[2];
    uint64_t                              icbArgOffset[2];
    __unsafe_unretained id<MTLTexture>    icbArgTex[2][16];
    __unsafe_unretained id<MTLSamplerState> icbArgSmp[2][16];

    /* ---- Terrain occlusion culling (CONTRACT.md 16.6) ---- */
    BOOL                                  occlIndex;       /* the index collects terrain draws/writes  */
    BOOL                                  occlActive;      /* this call has a plan                    */
    BOOL                                  occlVisLive;     /* open encoder has the visibility buffer  */
    BOOL                                  occlQueried;     /* queries were encoded                    */
    BOOL                                  occlBroken;      /* a planned query was not encoded         */
    BOOL                                  occlBoxPending;  /* box queries due (after opaque terrain)  */
    BOOL                                  occlBoxesDone;
    BOOL                                  occlMainStream;  /* the call decoded a main stream          */
    BOOL                                  occlIndexComplete; /* ... and the index covered all of it   */
    uint32_t                              occlCurSlot;     /* visibility slot + 1 enabled, 0 = none   */
} mcmtl_decoder_ext;

#define DEC_MSG(ext, kind, ...) dec_message((ext)->dev, (ext)->ds, (kind), &(ext)->messaged, __VA_ARGS__)

/* buffer.length without a message send when the same buffer is bound repeatedly (per-draw UBO offsets). */
static inline uint64_t dec_buffer_length(mcmtl_decoder_ext *ext, __unsafe_unretained id<MTLBuffer> buffer) {
    if (ext->lengthCacheBuffer != buffer) {
        ext->lengthCacheBuffer = buffer;
        ext->lengthCacheValue = buffer.length;
    }
    return ext->lengthCacheValue;
}

static int32_t dec_fail(mcmtl_decoder_ext *ext, int32_t code, uint64_t offset, const char *format, ...)
    __attribute__((format(printf, 4, 5)));

/* Records the first decode error of the call (message, errorOffset, init-stream flag) and returns `code`. */
static int32_t dec_fail(mcmtl_decoder_ext *ext, int32_t code, uint64_t offset, const char *format, ...) {
    if (!ext->failed) {
        ext->failed = 1;
        char buffer[1024];
        va_list args;
        va_start(args, format);
        vsnprintf(buffer, sizeof buffer, format, args);
        va_end(args);
        mcmtl_set_error("mcmtl_submit: %s stream offset %llu: %s", ext->isInit ? "init" : "main",
                        (unsigned long long)offset, buffer);
        ext->result->errorOffset = offset;
        if (ext->isInit) ext->result->flags |= MCMTL_SUBMIT_RESULT_FLAG_ERROR_IN_INIT_STREAM;
    }
    return code;
}

/* ---------------------------------------------------------------------------------------------- */
/* Upload ring                                                                                      */
/* ---------------------------------------------------------------------------------------------- */

/* Allocates `size` bytes (alignment `align`, power of two) of Shared memory for the open command buffer.
 * Returns the CPU pointer or NULL. */
static void *dec_ring_alloc(mcmtl_decoder_ext *ext, NSUInteger size, NSUInteger align, id<MTLBuffer> __strong *outBuffer,
                            NSUInteger *outOffset) {
    MCMTLDecoderState *ds = ext->ds;
    NSUInteger offset = (ds->ringOffset + align - 1) & ~(align - 1);
    if (!ds->ringChunk || offset + size > ds->ringChunk.length) {
        NSUInteger need = size > DEC_RING_CHUNK_BYTES ? ((size + 65535u) & ~(NSUInteger)65535u) : DEC_RING_CHUNK_BYTES;
        id<MTLBuffer> chunk = nil;
        os_unfair_lock_lock(&ds->ringLock);
        for (NSUInteger i = 0; i < ds->ringFree.count; i++) {
            if (ds->ringFree[i].length >= need) {
                chunk = ds->ringFree[i];
                [ds->ringFree removeObjectAtIndex:i];
                break;
            }
        }
        os_unfair_lock_unlock(&ds->ringLock);
        if (!chunk) {
            chunk = [ext->dev->device newBufferWithLength:need options:MTLResourceStorageModeShared];
            if (!chunk) return NULL;
            chunk.label = @"mcmetal decoder upload ring";
        }
        [ds->ringPending addObject:chunk];
        ds->ringChunk = chunk;
        offset = 0;
    }
    ds->ringOffset = offset + size;
    *outBuffer = ds->ringChunk;
    *outOffset = offset;
    return (uint8_t *)ds->ringChunk.contents + offset;
}

/* The chunks used so far belong to `commandBuffer` (about to be committed): recycle them on completion. */
static void dec_ring_attach(MCMTLDecoderState *ds, id<MTLCommandBuffer> commandBuffer) {
    if (ds->ringPending.count == 0) return;
    NSArray<id<MTLBuffer>> *chunks = [ds->ringPending copy];
    [ds->ringPending removeAllObjects];
    ds->ringChunk = nil;
    ds->ringOffset = 0;
    MCMTLDecoderState *state = ds;
    [commandBuffer addCompletedHandler:^(id<MTLCommandBuffer> done) {
        (void)done;
        os_unfair_lock_lock(&state->ringLock);
        [state->ringFree addObjectsFromArray:chunks];
        os_unfair_lock_unlock(&state->ringLock);
    }];
}

/* The command buffer that used the pending chunks was dropped uncommitted: they are free at once. */
static void dec_ring_recycle(MCMTLDecoderState *ds) {
    if (ds->ringPending.count == 0) return;
    os_unfair_lock_lock(&ds->ringLock);
    [ds->ringFree addObjectsFromArray:ds->ringPending];
    os_unfair_lock_unlock(&ds->ringLock);
    [ds->ringPending removeAllObjects];
    ds->ringChunk = nil;
    ds->ringOffset = 0;
}

/* ---------------------------------------------------------------------------------------------- */
/* Terrain ICB: buffers written by GPU copies (CONTRACT.md 16.5)                                    */
/* ---------------------------------------------------------------------------------------------- */

/* The ICB path encodes draw commands from the argument records on the CPU, at decode time. That is only right when no
 * GPU copy that has not completed yet writes the records (vanilla's are in a CPU-written MAP_WRITE ring; a staged
 * writeToBuffer would be a COPY_BUFFER). Every copy destination is remembered with the timeline value that completes
 * it: the work encoded now commits with submittedValue + 1 (values are dense, CONTRACT.md 5.1; early untimed commits
 * complete before it). */
static inline uint64_t dec_icb_open_value(MCMTLDevice *dev) {
    return mcmtl_status_load_u64(&dev->status->submittedValue) + 1;
}

static void dec_icb_note_gpu_write(MCMTLDevice *dev, MCMTLDecoderState *ds, const void *buffer) {
    if (!ds->icbMode || !buffer) return;
    const uint64_t value = dec_icb_open_value(dev);
    const uint64_t completed = mcmtl_status_load_u64(&dev->status->completedValue);
    /* A buffer's entry always lives in the DEC_ICB_WRITE_PROBE slots after its hash (insert and lookup scan the same
     * window), so lookups stay short however full the table is. */
    const uint64_t h = ((uint64_t)(uintptr_t)buffer >> 4) * 0x9E3779B97F4A7C15ull;
    const uint32_t start = (uint32_t)(h >> 40) & (DEC_ICB_WRITES - 1);
    dec_gpu_write *reuse = NULL;
    for (uint32_t probe = 0; probe < DEC_ICB_WRITE_PROBE; probe++) {
        dec_gpu_write *w = &ds->icbWrites[(start + probe) & (DEC_ICB_WRITES - 1)];
        if (w->buffer == buffer) {
            if (w->value < value) w->value = value;
            return;
        }
        if (!reuse && (!w->buffer || w->value <= completed)) reuse = w;   /* empty, or its copy completed */
    }
    if (reuse) {
        reuse->buffer = buffer;
        reuse->value = value;
        return;
    }
    if (ds->icbWriteOverflow < value) ds->icbWriteOverflow = value;   /* window full: every buffer suspect until done */
}

/* YES if a GPU copy into `buffer` may not have completed. */
static BOOL dec_icb_gpu_written(MCMTLDevice *dev, MCMTLDecoderState *ds, const void *buffer) {
    const uint64_t completed = mcmtl_status_load_u64(&dev->status->completedValue);
    if (ds->icbWriteOverflow > completed) return YES;
    const uint64_t h = ((uint64_t)(uintptr_t)buffer >> 4) * 0x9E3779B97F4A7C15ull;
    const uint32_t start = (uint32_t)(h >> 40) & (DEC_ICB_WRITES - 1);
    for (uint32_t probe = 0; probe < DEC_ICB_WRITE_PROBE; probe++) {
        const dec_gpu_write *w = &ds->icbWrites[(start + probe) & (DEC_ICB_WRITES - 1)];
        if (w->buffer == buffer) return w->value > completed;
    }
    return NO;
}

/* ---------------------------------------------------------------------------------------------- */
/* Blit-encoder hazard tracker (CONTRACT.md 4.5)                                                    */
/* ---------------------------------------------------------------------------------------------- */

static void hz_reset(MCMTLDecoderState *ds) {
    if (ds->hzEntryCount == 0 && ds->hzSlotsUsed == 0) return;
    ds->hzGen++;
    if (ds->hzGen == 0) {
        memset(ds->hzSlots, 0, DEC_HZ_SLOTS * sizeof(dec_hz_slot));
        ds->hzGen = 1;
    }
    ds->hzEntryCount = 0;
    ds->hzSlotsUsed = 0;
}

static dec_hz_slot *hz_slot(MCMTLDecoderState *ds, const void *res, BOOL insert) {
    uint64_t h = ((uint64_t)(uintptr_t)res >> 4) * 0x9E3779B97F4A7C15ull;
    uint32_t index = (uint32_t)(h >> 40) & (DEC_HZ_SLOTS - 1);
    for (uint32_t probe = 0; probe < DEC_HZ_SLOTS; probe++) {
        dec_hz_slot *slot = &ds->hzSlots[(index + probe) & (DEC_HZ_SLOTS - 1)];
        if (slot->gen != ds->hzGen) {
            if (!insert || ds->hzSlotsUsed >= (DEC_HZ_SLOTS * 3) / 4) return NULL;
            slot->gen = ds->hzGen;
            slot->res = res;
            slot->head = 0;
            slot->count = 0;
            ds->hzSlotsUsed++;
            return slot;
        }
        if (slot->res == res) return slot;
    }
    return NULL;
}

static inline BOOL hz_overlap(const dec_access *a, const dec_hz_entry *e) {
    if (a->texture) {
        if (a->sub != DEC_SUB_ALL && e->sub != DEC_SUB_ALL && a->sub != e->sub) return NO;
        uint32_t ax0 = (uint32_t)a->lo, ay0 = (uint32_t)(a->lo >> 32), ax1 = (uint32_t)a->hi, ay1 = (uint32_t)(a->hi >> 32);
        uint32_t ex0 = (uint32_t)e->lo, ey0 = (uint32_t)(e->lo >> 32), ex1 = (uint32_t)e->hi, ey1 = (uint32_t)(e->hi >> 32);
        return ax0 < ex1 && ex0 < ax1 && ay0 < ey1 && ey0 < ay1;
    }
    return a->lo < e->hi && e->lo < a->hi;
}

static BOOL hz_conflicts(MCMTLDecoderState *ds, const dec_access *a) {
    dec_hz_slot *slot = hz_slot(ds, a->res, NO);
    if (!slot) return NO;
    for (uint32_t i = slot->head; i; i = ds->hzEntries[i - 1].next) {
        const dec_hz_entry *e = &ds->hzEntries[i - 1];
        if ((e->write || a->write) && hz_overlap(a, e)) return YES;
    }
    return NO;
}

/* Records an access; NO when the tracker is full (the caller splits the encoder). */
static BOOL hz_record(MCMTLDecoderState *ds, const dec_access *a) {
    dec_hz_slot *slot = hz_slot(ds, a->res, YES);
    if (!slot) return NO;
    if (slot->head && !a->texture) {
        /* Coalesce contiguous buffer ranges of the same kind (sequential staging reads / uploads). */
        dec_hz_entry *head = &ds->hzEntries[slot->head - 1];
        if (head->write == a->write && a->lo <= head->hi && head->lo <= a->hi) {
            if (a->lo < head->lo) head->lo = a->lo;
            if (a->hi > head->hi) head->hi = a->hi;
            return YES;
        }
    }
    if (slot->count >= DEC_HZ_PER_RESOURCE || ds->hzEntryCount >= DEC_HZ_ENTRIES) return NO;
    dec_hz_entry *e = &ds->hzEntries[ds->hzEntryCount++];
    e->next = slot->head;
    e->write = a->write;
    e->texture = a->texture;
    e->sub = a->sub;
    e->lo = a->lo;
    e->hi = a->hi;
    slot->head = ds->hzEntryCount;
    slot->count++;
    return YES;
}

static inline dec_access dec_buffer_access(id<MTLBuffer> buffer, BOOL write, uint64_t offset, uint64_t length) {
    return (dec_access){ (__bridge const void *)buffer, write ? 1u : 0u, 0, 0, 0, offset, offset + length };
}

static inline dec_access dec_texture_access(id<MTLTexture> texture, BOOL write, uint32_t level, uint32_t slice,
                                            uint32_t x, uint32_t y, uint32_t w, uint32_t h) {
    return (dec_access){ (__bridge const void *)texture, write ? 1u : 0u, 1, level | (slice << 16), 0,
                         (uint64_t)x | ((uint64_t)y << 32), (uint64_t)(x + w) | ((uint64_t)(y + h) << 32) };
}

/* ---------------------------------------------------------------------------------------------- */
/* Command buffer and encoders                                                                      */
/* ---------------------------------------------------------------------------------------------- */

/* Diagnostic (MCMTL_SLOW_OP_MS=n, default off): logs every decoder step that takes at least n ms. */
static uint64_t dec_slow_ns(void) {
    static uint64_t v = UINT64_MAX;
    if (v == UINT64_MAX) { const char *e = getenv("MCMTL_SLOW_OP_MS"); v = e ? (uint64_t)(atof(e) * 1e6) : 0; }
    return v;
}
#define DEC_SLOW_BEGIN uint64_t slowT0 = dec_slow_ns() ? clock_gettime_nsec_np(CLOCK_UPTIME_RAW) : 0
#define DEC_SLOW_END(what, a) do { if (slowT0) { uint64_t slowD = clock_gettime_nsec_np(CLOCK_UPTIME_RAW) - slowT0; \
    if (slowD >= dec_slow_ns()) NSLog(@"[mcmetal] slow %s %u: %.2f ms", what, (unsigned)(a), slowD / 1e6); } } while (0)

static id<MTLCommandBuffer> dec_cb(mcmtl_decoder_ext *ext) {
    if (ext->commandBuffer) return ext->commandBuffer;
    MCMTLDevice *dev = ext->dev;
    id<MTLCommandBuffer> cb = dev->currentCommandBuffer;
    if (cb) {
        ext->cbHasWork = YES;   /* only created when a previous call encoded something */
    } else {
        MTLCommandBufferDescriptor *desc = (dev->flags & MCMTL_DEVICE_FLAG_VALIDATION) ? ext->ds->cbDescriptorValidation
                                                                                       : ext->ds->cbDescriptor;
        DEC_SLOW_BEGIN;
        cb = [dev->queue commandBufferWithDescriptor:desc];
        DEC_SLOW_END("newCommandBuffer", 0);
        if (!cb) [NSException raise:NSInternalInconsistencyException format:@"commandBufferWithDescriptor returned nil"];
        if (ext->labels && ext->ctx->info->label) {
            NSString *label = [NSString stringWithUTF8String:ext->ctx->info->label];
            if (label) cb.label = label;
        }
        dev->currentCommandBuffer = cb;
    }
    ext->commandBuffer = cb;
    ext->ctx->commandBuffer = cb;
    return cb;
}

/* Ends an open merged encoder between two of its passes (an unplanned record needs another encoder). */
static void dec_merge_break(mcmtl_decoder_ext *ext);

static void dec_end_blit(mcmtl_decoder_ext *ext) {
    if (ext->copyEncoder) {
        [ext->copyEncoder endEncoding];
        ext->copyEncoder = nil;
    }
    if (ext->blitEncoder) {
        [ext->blitEncoder endEncoding];
        ext->blitEncoder = nil;
        ext->ctx->blitEncoder = nil;
    }
    hz_reset(ext->ds);
}

static void dec_open_blit(mcmtl_decoder_ext *ext) {
    if (ext->mergeOpen) dec_merge_break(ext);
    if (ext->copyEncoder) dec_end_blit(ext);
    id<MTLCommandBuffer> cb = dec_cb(ext);
    DEC_SLOW_BEGIN;
    id<MTLBlitCommandEncoder> encoder = [cb blitCommandEncoder];
    DEC_SLOW_END("blitEncoder", 0);
    if (!encoder) [NSException raise:NSInternalInconsistencyException format:@"blitCommandEncoder returned nil"];
    ext->blitEncoder = encoder;
    ext->ctx->blitEncoder = encoder;
    ext->cbHasWork = YES;
    ext->result->encodersCreated++;
    ext->ds->stats.blitEncoders++;
    hz_reset(ext->ds);
}

/* The shared blit encoder for one blit command with the given accesses: ends and reopens it when the
 * command depends on an earlier command of the open encoder (or the tracker is full). */
static id<MTLBlitCommandEncoder> dec_blit(mcmtl_decoder_ext *ext, const dec_access *accesses, uint32_t count) {
    MCMTLDecoderState *ds = ext->ds;
    if (ext->blitEncoder) {
        for (uint32_t i = 0; i < count; i++) {
            if (hz_conflicts(ds, &accesses[i])) {
                dec_end_blit(ext);
                ds->stats.blitSplits++;
                break;
            }
        }
    }
    if (!ext->blitEncoder) dec_open_blit(ext);
    for (uint32_t i = 0; i < count; i++) {
        if (!hz_record(ds, &accesses[i])) {
            dec_end_blit(ext);
            ds->stats.blitSplits++;
            dec_open_blit(ext);
            for (uint32_t j = 0; j < count; j++) (void)hz_record(ds, &accesses[j]);
            break;
        }
    }
    return ext->blitEncoder;
}

/* Compute COPY_BUFFER (MCMTL_COMPUTE_COPY, default on): every blit encoder makes the AGX driver malloc a large blit
 * context, and under memory pressure that allocation's madvise stalls the render thread 30-60 ms a few times a
 * minute; compute encoders do not. Buffer copies run as a copy kernel in a compute encoder that takes the blit slot
 * (dec_end_blit closes either); texture copies keep the blit encoder. */
static const char *kDecCopySource =
    "#include <metal_stdlib>\n"
    "using namespace metal;\n"
    "kernel void copy16(device const uint4 *s [[buffer(0)]], device uint4 *d [[buffer(1)]], constant uint &n [[buffer(2)]],\n"
    "                   uint i [[thread_position_in_grid]]) { if (i < n) d[i] = s[i]; }\n"
    "kernel void copy4(device const uint *s [[buffer(0)]], device uint *d [[buffer(1)]], constant uint &n [[buffer(2)]],\n"
    "                  uint i [[thread_position_in_grid]]) { if (i < n) d[i] = s[i]; }\n"
    "kernel void copy1(device const uchar *s [[buffer(0)]], device uchar *d [[buffer(1)]], constant uint &n [[buffer(2)]],\n"
    "                  uint i [[thread_position_in_grid]]) { if (i < n) d[i] = s[i]; }\n";

static void dec_copy_init(id<MTLDevice> device, MCMTLDecoderState *ds) {
    const char *e = getenv("MCMTL_COMPUTE_COPY");
    if (e && atoi(e) == 0) return;
    NSError *error = nil;
    MTLCompileOptions *options = [MTLCompileOptions new];
    id<MTLLibrary> lib = [device newLibraryWithSource:@(kDecCopySource) options:options error:&error];
    if (!lib) {
        NSLog(@"[mcmetal] compute copy disabled: %@", error.localizedDescription);
        return;
    }
    NSString *names[3] = { @"copy16", @"copy4", @"copy1" };
    for (int i = 0; i < 3; i++) {
        id<MTLFunction> f = [lib newFunctionWithName:names[i]];
        ds->copyPso[i] = f ? [device newComputePipelineStateWithFunction:f error:&error] : nil;
        if (!ds->copyPso[i]) {
            NSLog(@"[mcmetal] compute copy disabled: %@", error.localizedDescription);
            ds->copyPso[0] = ds->copyPso[1] = ds->copyPso[2] = nil;
            return;
        }
    }
}

/* Encodes a buffer copy with the copy kernel; NO = use the blit path (disabled, or a length beyond uint). */
static BOOL dec_copy_compute(mcmtl_decoder_ext *ext, const dec_access *acc, id<MTLBuffer> src, uint64_t srcOffset,
                             id<MTLBuffer> dst, uint64_t dstOffset, uint64_t length) {
    MCMTLDecoderState *ds = ext->ds;
    if (!ds->copyPso[0] || length > 0xFFFFFFFFull) return NO;
    const uint32_t k = ((srcOffset | dstOffset | length) & 15) == 0 ? 0 : ((srcOffset | dstOffset | length) & 3) == 0 ? 1 : 2;
    const uint32_t unit = k == 0 ? 16 : k == 1 ? 4 : 1;
    if (!ext->copyEncoder) {
        if (ext->blitEncoder || ext->mergeOpen) {
            if (ext->mergeOpen) dec_merge_break(ext);
            dec_end_blit(ext);
        }
        hz_reset(ds);
        id<MTLCommandBuffer> cb = dec_cb(ext);
        ext->copyEncoder = [cb computeCommandEncoderWithDispatchType:MTLDispatchTypeSerial];
        if (!ext->copyEncoder) [NSException raise:NSInternalInconsistencyException format:@"computeCommandEncoder returned nil"];
        ext->cbHasWork = YES;
        ext->result->encodersCreated++;
        ds->stats.blitEncoders++;
    } else {
        BOOL conflict = NO;
        for (uint32_t i = 0; i < 2 && !conflict; i++) conflict = hz_conflicts(ds, &acc[i]);
        if (conflict) {
            [ext->copyEncoder memoryBarrierWithScope:MTLBarrierScopeBuffers];
            ds->stats.blitSplits++;   /* an ordering point, like a blit encoder split */
            hz_reset(ds);
        }
    }
    for (uint32_t i = 0; i < 2; i++) {
        if (!hz_record(ds, &acc[i])) {
            [ext->copyEncoder memoryBarrierWithScope:MTLBarrierScopeBuffers];
            ds->stats.blitSplits++;   /* an ordering point, like a blit encoder split */
            hz_reset(ds);
            (void)hz_record(ds, &acc[0]);
            (void)hz_record(ds, &acc[1]);
            break;
        }
    }
    id<MTLComputeCommandEncoder> ce = ext->copyEncoder;
    id<MTLComputePipelineState> pso = ds->copyPso[k];
    const uint32_t n = (uint32_t)(length / unit);
    [ce setComputePipelineState:pso];
    [ce setBuffer:src offset:(NSUInteger)srcOffset atIndex:0];
    [ce setBuffer:dst offset:(NSUInteger)dstOffset atIndex:1];
    [ce setBytes:&n length:sizeof n atIndex:2];
    const NSUInteger w = pso.maxTotalThreadsPerThreadgroup < 256 ? pso.maxTotalThreadsPerThreadgroup : 256;
    [ce dispatchThreads:MTLSizeMake(n, 1, 1) threadsPerThreadgroup:MTLSizeMake(w, 1, 1)];
    return YES;
}

/* ---- Per-pass GPU profiler (diagnostic; MCMTL_PROFILE_PASSES=1) ----------------------------------
 * Each render pass gets its own command buffer (earlier work is committed untimed, like the PRESENT
 * early commit) and its GPU start/end times are aggregated by encoder label (debug labels need the
 * game's --renderDebugLabels). Every 600 frames (PRESENT records, presented or not: mailbox frames that were not
 * shown still ran on the GPU) the averages are written with NSLog.
 * Splitting adds some overhead; use it to rank passes, not for headline FPS. */
static int dec_profile_enabled(void) {
    static int enabled = -1;
    if (enabled < 0) enabled = getenv("MCMTL_PROFILE_PASSES") ? atoi(getenv("MCMTL_PROFILE_PASSES")) : 0;
    return enabled;
}
static os_unfair_lock g_prof_lock = OS_UNFAIR_LOCK_INIT;
static NSMutableDictionary<NSString *, NSMutableArray<NSNumber *> *> *g_prof;   /* label -> [sumSec, count] */
static uint64_t g_prof_frames;
static const char *g_prof_tag;   /* set by internal passes (clear, present) right before their encoder */

/* MCMTL_PROFILE_SLOW_MS (default 15): a single pass / untimed command buffer above it is logged at once (rare hitches). */
static double dec_profile_slow_ms(void) {
    static double slow = -1;
    if (slow < 0) slow = getenv("MCMTL_PROFILE_SLOW_MS") ? atof(getenv("MCMTL_PROFILE_SLOW_MS")) : 15.0;
    return slow;
}
static void dec_profile_record(NSString *label, double seconds) {
    if (seconds * 1000.0 > dec_profile_slow_ms()) NSLog(@"mcmtl slow pass %.1f ms frame %llu %@", seconds * 1000.0, g_prof_frames, label);
    os_unfair_lock_lock(&g_prof_lock);
    if (!g_prof) g_prof = [NSMutableDictionary dictionary];
    NSMutableArray<NSNumber *> *e = g_prof[label];
    if (!e) g_prof[label] = e = [NSMutableArray arrayWithObjects:@0.0, @0, nil];
    e[0] = @(e[0].doubleValue + seconds);
    e[1] = @(e[1].unsignedLongLongValue + 1);
    os_unfair_lock_unlock(&g_prof_lock);
}

static void dec_profile_frame(void) {
    os_unfair_lock_lock(&g_prof_lock);
    uint64_t frames = ++g_prof_frames;
    NSDictionary *snapshot = nil;
    if (frames % 600 == 0 && g_prof) {
        snapshot = [g_prof copy];
        [g_prof removeAllObjects];
    }
    os_unfair_lock_unlock(&g_prof_lock);
    if (!snapshot) return;
    NSArray *keys = [snapshot keysSortedByValueUsingComparator:^NSComparisonResult(NSArray *a, NSArray *b) {
        return [b[0] compare:a[0]];
    }];
    double total = 0;
    for (NSString *k in keys) total += [snapshot[k][0] doubleValue];
    NSMutableString *out = [NSMutableString stringWithFormat:@"mcmtl pass profile (600 frames): GPU %.3f ms/frame\n", total * 1000.0 / 600.0];
    for (NSString *k in keys) {
        NSArray *e = snapshot[k];
        [out appendFormat:@"  %7.3f ms/frame  %5.2f/frame  %@\n", [e[0] doubleValue] * 1000.0 / 600.0, [e[1] doubleValue] / 600.0, k];
    }
    NSLog(@"%@", out);
}

/* Commit the open command buffer (untimed) so the next encoder starts a fresh one; re-push the
 * command-buffer debug groups so labels survive the split. */
static void dec_profile_split(mcmtl_decoder_ext *ext) {
    if (!ext->commandBuffer || !ext->cbHasWork) return;
    id<MTLCommandBuffer> cb = ext->commandBuffer;
    for (uint32_t i = 0; i < ext->cbGroupDepth; i++) [cb popDebugGroup];
    dec_ring_attach(ext->ds, cb);
    ext->commandBuffer = nil;
    ext->ctx->commandBuffer = nil;
    ext->dev->currentCommandBuffer = nil;
    ext->cbHasWork = NO;
    [cb addCompletedHandler:^(id<MTLCommandBuffer> done) {
        double ms = (done.GPUEndTime - done.GPUStartTime) * 1000.0;
        if (ms > dec_profile_slow_ms()) NSLog(@"mcmtl slow untimed cb %.1f ms frame %llu", ms, g_prof_frames);
    }];
    mcmtl_commit_untimed(ext->dev, cb, ext->signalValue);
    if (dec_profile_enabled() == 2) [cb waitUntilCompleted];   /* serialized: isolated per-pass GPU time */
    id<MTLCommandBuffer> next = dec_cb(ext);
    for (NSString *label in ext->ds->labelStack) [next pushDebugGroup:label];
}

static id<MTLRenderCommandEncoder> dec_render_encoder(mcmtl_decoder_ext *ext, MTLRenderPassDescriptor *desc) {
    if (ext->mergeOpen) dec_merge_break(ext);
    dec_end_blit(ext);
    if (dec_profile_enabled()) dec_profile_split(ext);
    id<MTLCommandBuffer> cb = dec_cb(ext);
    if (dec_profile_enabled()) {
        NSString *label;
        if (g_prof_tag) {
            id<MTLTexture> c0 = desc.colorAttachments[0].texture, d = desc.depthAttachment.texture;
            id<MTLTexture> t = c0 ? c0 : d;
            label = [NSString stringWithFormat:@"[%s %lux%lu%s%s]", g_prof_tag, (unsigned long)t.width, (unsigned long)t.height,
                                                c0 ? " color" : "", d ? " depth" : ""];
        } else if (ext->ds->labelStack.count) {
            label = [ext->ds->labelStack componentsJoinedByString:@"/"];
        } else {
            id<MTLTexture> c0 = desc.colorAttachments[0].texture, d = desc.depthAttachment.texture;
            id<MTLTexture> t = c0 ? c0 : d;
            label = [NSString stringWithFormat:@"(unlabeled %lux%lu c:%s d:%s)", (unsigned long)t.width, (unsigned long)t.height,
                                                c0 ? (desc.colorAttachments[0].loadAction == MTLLoadActionClear ? "clear" : "load") : "-",
                                                d ? (desc.depthAttachment.loadAction == MTLLoadActionClear ? "clear" : "load") : "-"];
        }
        /* A merged encoder (M2) also runs the next chainCount passes. */
        if (!g_prof_tag && ext->chainActive && ext->chainCount) label = [label stringByAppendingFormat:@" (+%u merged)", ext->chainCount];
        g_prof_tag = NULL;
        [cb addCompletedHandler:^(id<MTLCommandBuffer> done) {
            if (done.GPUEndTime > done.GPUStartTime) dec_profile_record(label, done.GPUEndTime - done.GPUStartTime);
        }];
    }
    if (ext->ds->testOnRenderPass) ext->ds->testOnRenderPass(desc);
    DEC_SLOW_BEGIN;
    id<MTLRenderCommandEncoder> encoder = [cb renderCommandEncoderWithDescriptor:desc];
    DEC_SLOW_END("renderEncoder", 0);
    if (!encoder) [NSException raise:NSInternalInconsistencyException format:@"renderCommandEncoderWithDescriptor returned nil"];
    ext->icbUsedCount = 0;   /* terrain ICB: residency and the texture argument buffer are per encoder */
    ext->icbArgValid[0] = ext->icbArgValid[1] = NO;
    ext->icbPsoBound = NO;
    ext->cbHasWork = YES;
    ext->result->encodersCreated++;
    ext->ds->stats.renderEncoders++;
    if (ext->ds->copyStats) ext->ds->copyStats->renderSeen = YES;
    if (ext->labels && ext->ds->labelStack.count) encoder.label = ext->ds->labelStack.lastObject;
    return encoder;
}

/* ---------------------------------------------------------------------------------------------- */
/* Timestamps                                                                                       */
/* ---------------------------------------------------------------------------------------------- */

static inline BOOL dec_is_skippable_op(uint32_t op) {
    return op == MCMTL_OP_NOP || op == MCMTL_OP_PUSH_DEBUG_GROUP || op == MCMTL_OP_POP_DEBUG_GROUP;
}

/* Offset of the first record at or after `offset` that is not NOP / PUSH / POP; UINT64_MAX if none or if
 * a malformed record is reached (the decode loop reports it). */
static uint64_t dec_skip_labels(const uint8_t *s, uint64_t length, uint64_t offset) {
    while (offset + 16 <= length) {
        const mcmtl_cmd_header *h = (const mcmtl_cmd_header *)(s + offset);
        if (h->size < 16 || (h->size & 15) || h->size > length - offset) return UINT64_MAX;
        if (!dec_is_skippable_op(h->op)) return offset;
        offset += h->size;
    }
    return UINT64_MAX;
}

/* Offset of the first record after the timestamp run containing `tsOffset` (skipping labels and further
 * timestamps); UINT64_MAX if none. */
static uint64_t dec_after_ts_run(const uint8_t *s, uint64_t length, uint64_t tsOffset) {
    uint64_t o = tsOffset;
    for (;;) {
        const mcmtl_cmd_header *h = (const mcmtl_cmd_header *)(s + o);
        o = dec_skip_labels(s, length, o + h->size);
        if (o == UINT64_MAX || ((const mcmtl_cmd_header *)(s + o))->op != MCMTL_OP_TIMESTAMP) return o;
    }
}

/* Contract rule 1 (CONTRACT.md 4.4 TIMESTAMP), extended to runs: a timestamp whose run of timestamps ends at a
 * BEGIN_PASS samples that pass's start. (The run that directly follows an END_PASS keeps its last timestamp
 * for the next pass and gives the others to the previous pass's end, see dec_ts_attach_pass.) */
static BOOL dec_ts_precedes_pass(const uint8_t *s, uint64_t length, uint64_t tsOffset) {
    uint64_t next = dec_after_ts_run(s, length, tsOffset);
    return next != UINT64_MAX && ((const mcmtl_cmd_header *)(s + next))->op == MCMTL_OP_BEGIN_PASS;
}

/* -1: malformed (leave it to the decode loop), 0: ignore (no counters / out of range), 1: valid. */
static int dec_ts_resolve(mcmtl_decoder_ext *ext, const mcmtl_cmd_timestamp *c, dec_ts *out) {
    if (!c->queryPool) return -1;
    MCMTLQueryPool *pool = MCMTL_QUERY_POOL(c->queryPool);
    if (!pool->sampleBuffer) return 0;
    if (c->index >= pool->count) {
        DEC_MSG(ext, DEC_MSG_TIMESTAMP, "timestamp index %u out of range (pool of %u)", c->index, pool->count);
        return 0;
    }
    out->pool = pool;
    out->index = c->index;
    return 1;
}

static BOOL dec_ts_handled(mcmtl_decoder_ext *ext, uint64_t offset) {
    for (uint32_t i = 0; i < ext->handledTsCount; i++) {
        if (ext->handledTs[i] == offset) return YES;
    }
    return NO;
}

/* A timestamp not adjacent to a pass: a blit pass containing one 4-byte fill samples it at its start
 * (an empty encoder resolves to 0 on M5). */
static void dec_sample_standalone(mcmtl_decoder_ext *ext, const dec_ts *ts) {
    if (ext->mergeOpen) dec_merge_break(ext);
    dec_end_blit(ext);
    id<MTLCommandBuffer> cb = dec_cb(ext);
    MTLBlitPassDescriptor *desc = [MTLBlitPassDescriptor new];
    MTLBlitPassSampleBufferAttachmentDescriptor *a = desc.sampleBufferAttachments[0];
    a.sampleBuffer = ts->pool->sampleBuffer;
    a.startOfEncoderSampleIndex = ts->index;
    a.endOfEncoderSampleIndex = MTLCounterDontSample;
    id<MTLBlitCommandEncoder> encoder = [cb blitCommandEncoderWithDescriptor:desc];
    if (!encoder) [NSException raise:NSInternalInconsistencyException format:@"blitCommandEncoderWithDescriptor returned nil"];
    [encoder fillBuffer:ext->ds->tsScratch range:NSMakeRange(0, 4) value:0];
    [encoder endEncoding];
    ext->cbHasWork = YES;
    ext->result->encodersCreated++;
    ext->ds->stats.blitEncoders++;
    ext->ds->stats.standaloneTimestamps++;
}

/* Collects the timestamps that attach to the pass starting at passOffset (pending start samples plus
 * in-pass and directly following ones) and fills the descriptor's sample-buffer attachment: start samples use
 * startOfVertex then startOfFragment, end samples endOfFragment then endOfVertex. */
static void dec_ts_attach_pass(mcmtl_decoder_ext *ext, MTLRenderPassDescriptor *desc, uint64_t passOffset, uint32_t passSize) {
    ext->handledTsCount = 0;
    dec_ts endTs[DEC_MAX_PASS_TS];
    uint32_t endCount = 0;
    if (ext->streamHasTimestamps && !ext->isInit) {
        const uint8_t *s = ext->stream;
        const uint64_t length = ext->streamLength;
        uint64_t o = passOffset + passSize;
        BOOL ended = NO;
        while (o + 16 <= length) {
            const mcmtl_cmd_header *h = (const mcmtl_cmd_header *)(s + o);
            if (h->size < 16 || (h->size & 15) || h->size > length - o) break;
            if (h->op == MCMTL_OP_END_PASS) { o += h->size; ended = YES; break; }
            if (h->op == MCMTL_OP_BEGIN_PASS) break;
            if (h->op == MCMTL_OP_TIMESTAMP && endCount < DEC_MAX_PASS_TS && ext->handledTsCount < DEC_MAX_HANDLED_TS) {
                dec_ts ts;
                int r = dec_ts_resolve(ext, (const mcmtl_cmd_timestamp *)h, &ts);
                if (r >= 0) ext->handledTs[ext->handledTsCount++] = o;
                if (r > 0) endTs[endCount++] = ts;
            }
            o += h->size;
        }
        /* The timestamp run directly after END_PASS samples this pass's end, except its last timestamp when the
         * run ends at the next BEGIN_PASS (that one is the next pass's start: rule 1 wins). */
        uint64_t q = ended ? dec_skip_labels(s, length, o) : UINT64_MAX;
        if (q != UINT64_MAX && ((const mcmtl_cmd_header *)(s + q))->op == MCMTL_OP_TIMESTAMP) {
            uint64_t runEnd = dec_after_ts_run(s, length, q);
            BOOL runPrecedesPass = runEnd != UINT64_MAX && ((const mcmtl_cmd_header *)(s + runEnd))->op == MCMTL_OP_BEGIN_PASS;
            while (q != UINT64_MAX && ((const mcmtl_cmd_header *)(s + q))->op == MCMTL_OP_TIMESTAMP) {
                const mcmtl_cmd_header *h = (const mcmtl_cmd_header *)(s + q);
                uint64_t next = dec_skip_labels(s, length, q + h->size);
                BOOL last = next == UINT64_MAX || ((const mcmtl_cmd_header *)(s + next))->op != MCMTL_OP_TIMESTAMP;
                if ((last && runPrecedesPass) || endCount >= DEC_MAX_PASS_TS || ext->handledTsCount >= DEC_MAX_HANDLED_TS) break;
                dec_ts ts;
                int r = dec_ts_resolve(ext, (const mcmtl_cmd_timestamp *)h, &ts);
                if (r < 0) break;
                ext->handledTs[ext->handledTsCount++] = q;
                if (r > 0) endTs[endCount++] = ts;
                q = next;
            }
        }
    }
    if (ext->startTsCount == 0 && endCount == 0) return;

    /* One sample-buffer attachment per render pass: on M5 only the highest-indexed attachment slot is sampled
     * (others resolve to 0, whatever their buffers), so the first pool seen owns the pass's four sample points
     * and every other timestamp is sampled by a dedicated blit pass right before / after the pass. */
    __unsafe_unretained MCMTLQueryPool *pool = nil;
    NSUInteger idx[4] = { MTLCounterDontSample, MTLCounterDontSample, MTLCounterDontSample, MTLCounterDontSample };
    /* idx: startOfVertex, startOfFragment, endOfFragment, endOfVertex */
    for (uint32_t side = 0; side < 2; side++) {
        const dec_ts *list = side == 0 ? ext->startTs : endTs;
        uint32_t count = side == 0 ? ext->startTsCount : endCount;
        for (uint32_t i = 0; i < count; i++) {
            if (!pool) pool = list[i].pool;
            BOOL placed = NO;
            if (list[i].pool == pool) {
                uint32_t first = side == 0 ? 0 : 2;
                for (uint32_t k = first; k < first + 2 && !placed; k++) {
                    if (idx[k] == MTLCounterDontSample) { idx[k] = list[i].index; placed = YES; }
                }
            }
            if (!placed) {
                if (side == 0) {
                    dec_sample_standalone(ext, &list[i]);
                } else if (ext->endOverflowCount < DEC_MAX_PASS_TS) {
                    ext->endOverflow[ext->endOverflowCount++] = list[i];
                }
            }
        }
    }
    if (pool) {
        MTLRenderPassSampleBufferAttachmentDescriptor *a = desc.sampleBufferAttachments[0];
        a.sampleBuffer = pool->sampleBuffer;
        a.startOfVertexSampleIndex = idx[0];
        a.startOfFragmentSampleIndex = idx[1];
        a.endOfFragmentSampleIndex = idx[2];
        a.endOfVertexSampleIndex = idx[3];
    }
    ext->startTsCount = 0;
}

/* ---------------------------------------------------------------------------------------------- */
/* Render-pass state                                                                                */
/* ---------------------------------------------------------------------------------------------- */

static void dec_reset_pass_state(mcmtl_decode_ctx *ctx, mcmtl_decoder_ext *ext) {
    ctx->pipeline = nil;
    ctx->pso = nil;
    ext->basePso = nil;
    ext->icbPsoBound = NO;
    ctx->depthStencilState = nil;
    ctx->cullMode = MTLCullModeNone;
    ctx->fillMode = MTLTriangleFillModeFill;
    ctx->frontFacing = MTLWindingClockwise;
    ctx->depthBias[0] = ctx->depthBias[1] = ctx->depthBias[2] = 0.0f;
    memset(ctx->buffers, 0, sizeof ctx->buffers);
    memset(ctx->bufferOffsets, 0, sizeof ctx->bufferOffsets);
    memset(ctx->textures, 0, sizeof ctx->textures);
    memset(ctx->samplers, 0, sizeof ctx->samplers);
    ctx->bufferSetMask[0] = ctx->bufferSetMask[1] = 0;
    ctx->textureSetMask[0] = ctx->textureSetMask[1] = 0;
    ctx->indexBuffer = nil;
    ctx->indexType = MTLIndexTypeUInt16;
    ext->indexBufferLength = 0;
    ext->vertexBoundMask = 0;
    ext->samplerBoundMask[0] = ext->samplerBoundMask[1] = 0;
    ext->pipelineInvalid = YES;
    ext->encoderGroupDepth = 0;
    ext->scissorEmpty = NO;
}

static void dec_set_scissor(mcmtl_decode_ctx *ctx, mcmtl_decoder_ext *ext, int32_t x, int32_t y, int32_t w, int32_t h) {
    if (ctx->scissor[0] == x && ctx->scissor[1] == y && ctx->scissor[2] == w && ctx->scissor[3] == h) return;
    [ctx->renderEncoder setScissorRect:(MTLScissorRect){ (NSUInteger)x, (NSUInteger)y, (NSUInteger)w, (NSUInteger)h }];
    ctx->scissor[0] = x;
    ctx->scissor[1] = y;
    ctx->scissor[2] = w;
    ctx->scissor[3] = h;
    ext->ds->stats.setScissor++;
}

/* Clamps (x, y, w, h) to the attachment; NO if empty. */
static BOOL dec_clamp_rect(int64_t x, int64_t y, int64_t w, int64_t h, uint32_t width, uint32_t height, int32_t out[4]) {
    int64_t x0 = x < 0 ? 0 : x, y0 = y < 0 ? 0 : y;
    int64_t x1 = x + w, y1 = y + h;
    if (x1 > (int64_t)width) x1 = width;
    if (y1 > (int64_t)height) y1 = height;
    if (x1 <= x0 || y1 <= y0) return NO;
    out[0] = (int32_t)x0;
    out[1] = (int32_t)y0;
    out[2] = (int32_t)(x1 - x0);
    out[3] = (int32_t)(y1 - y0);
    return YES;
}

/* Draws a clear quad into the open render encoder (fresh encoder state assumed or restored by the caller's
 * shadow bookkeeping). colors: 8 RGBA values; writeMask: color slots to write; depth written iff writeDepth. */
static BOOL dec_draw_clear_quad(mcmtl_decode_ctx *ctx, mcmtl_decoder_ext *ext, const uint32_t colorFormats[8],
                                uint32_t writeMask, const float colors[8][4], uint32_t depthFormat, uint32_t stencilFormat,
                                BOOL writeDepth, float depth) {
    uint32_t key[16] = { DEC_PSO_CLEAR };
    for (uint32_t i = 0; i < 8; i++) key[1 + i] = colorFormats[i];
    key[9] = writeMask;
    key[10] = depthFormat;
    key[11] = stencilFormat;
    key[12] = writeDepth ? 1u : 0u;
    id<MTLRenderPipelineState> pso = dec_clear_pso(ext->dev, ext->ds, key, &ext->messaged);
    if (!pso) return NO;
    __unsafe_unretained id<MTLRenderCommandEncoder> re = ctx->renderEncoder;
    dec_quad_params params;
    memset(&params, 0, sizeof params);
    params.depth = depth < 0.0f ? 0.0f : (depth > 1.0f ? 1.0f : depth);
    if (params.depth != params.depth) params.depth = 0.0f;   /* NaN */
    memcpy(params.colors, colors, sizeof params.colors);
    if (pso != ctx->pso) {
        [re setRenderPipelineState:pso];
        ctx->pso = pso;
    }
    id<MTLDepthStencilState> dss = writeDepth ? ext->ds->alwaysWriteDSS : ext->ds->defaultDSS;
    if (dss != ctx->depthStencilState) {
        [re setDepthStencilState:dss];
        ctx->depthStencilState = dss;
    }
    [re setVertexBytes:&params length:sizeof params atIndex:0];
    if (writeMask) [re setFragmentBytes:&params length:sizeof params atIndex:0];
    ctx->buffers[0][0] = nil;   /* bytes replaced whatever buffer was bound at index 0 */
    ctx->buffers[1][0] = nil;
    ctx->bufferSetMask[0] &= ~1u;
    ctx->bufferSetMask[1] &= ~1u;
    [re drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
    ext->ds->stats.clearQuads++;
    return YES;
}

/* ---------------------------------------------------------------------------------------------- */
/* M2: BEGIN_PASS parsing                                                                           */
/* ---------------------------------------------------------------------------------------------- */

enum { DEC_PARSE_OK = 0, DEC_PARSE_SIZE, DEC_PARSE_COLOR_LEVEL, DEC_PARSE_DEPTH_LEVEL, DEC_PARSE_NO_ATTACHMENT };

/* Validates and summarizes a BEGIN_PASS record without side effects (op_begin_pass reports the errors).
 * *bad = the failing color slot for DEC_PARSE_COLOR_LEVEL. */
static int dec_parse_begin_pass(const uint8_t *rec, uint32_t size, dec_pass_parse *p, uint32_t *bad) {
    const mcmtl_cmd_begin_pass *c = (const mcmtl_cmd_begin_pass *)rec;
    const uint32_t n = c->colorCount;
    memset(p, 0, sizeof *p);
    if (n > MCMTL_MAX_COLOR_ATTACHMENTS || size < MCMTL_CMD_SIZE_BEGIN_PASS_BASE + MCMTL_CMD_SIZE_COLOR_ATTACHMENT * n) {
        return DEC_PARSE_SIZE;
    }
    p->n = n;
    const mcmtl_cmd_color_attachment *atts = (const mcmtl_cmd_color_attachment *)(rec + MCMTL_CMD_SIZE_BEGIN_PASS_BASE);
    for (uint32_t i = 0; i < n; i++) {
        if (!atts[i].texture) continue;
        id<MTLTexture> t = MCMTL_TEXTURE(atts[i].texture);
        if (atts[i].level >= t.mipmapLevelCount) {
            *bad = i;
            return DEC_PARSE_COLOR_LEVEL;
        }
        p->color[i] = t;
        p->level[i] = atts[i].level;
        p->presentMask |= 1u << i;
        p->colorFormat[i] = (uint32_t)t.pixelFormat;
        if (!p->attW) {
            p->attW = dec_mip(t.width, atts[i].level);
            p->attH = dec_mip(t.height, atts[i].level);
        }
    }
    if (c->depthTexture) {
        id<MTLTexture> t = MCMTL_TEXTURE(c->depthTexture);
        if (c->depthLevel >= t.mipmapLevelCount) return DEC_PARSE_DEPTH_LEVEL;
        p->depth = t;
        p->depthLevel = c->depthLevel;
        p->depthInfo = dec_format_info(t.pixelFormat);
        p->passDepthFormat = (uint32_t)t.pixelFormat;
        p->quadDepthFormat = p->depthInfo.depth ? p->passDepthFormat : 0;
        p->stencilFormat = p->depthInfo.stencil ? p->passDepthFormat : 0;
        if (!p->attW) {
            p->attW = dec_mip(t.width, c->depthLevel);
            p->attH = dec_mip(t.height, c->depthLevel);
        }
    }
    if (!p->attW) return DEC_PARSE_NO_ATTACHMENT;
    p->empty = !dec_clamp_rect(c->renderX, c->renderY, c->renderWidth, c->renderHeight, p->attW, p->attH, p->area);
    if (p->empty) memset(p->area, 0, sizeof p->area);
    p->full = !p->empty && p->area[0] == 0 && p->area[1] == 0 && (uint32_t)p->area[2] == p->attW && (uint32_t)p->area[3] == p->attH;
    p->colorClear = p->empty ? 0 : (c->colorClearMask & p->presentMask);
    p->depthClear = !p->empty && p->depth && (c->flags & MCMTL_PASS_FLAG_DEPTH_CLEAR);
    p->depthClearValue = c->depthClearValue;
    return DEC_PARSE_OK;
}

static BOOL dec_same_attachments(const dec_pass_parse *a, const dec_pass_parse *b) {
    if (a->presentMask != b->presentMask || a->depth != b->depth) return NO;
    if (a->depth && a->depthLevel != b->depthLevel) return NO;
    for (uint32_t i = 0; i < MCMTL_MAX_COLOR_ATTACHMENTS; i++) {
        if (!(a->presentMask & (1u << i))) continue;
        if (a->color[i] != b->color[i] || a->level[i] != b->level[i]) return NO;
    }
    return YES;
}

/* Is `texture` one of the pass's attachment textures (any level)? */
static BOOL dec_is_attachment_texture(const dec_pass_parse *p, const void *texture) {
    if (p->depth && (__bridge const void *)p->depth == texture) return YES;
    for (uint32_t i = 0; i < MCMTL_MAX_COLOR_ATTACHMENTS; i++) {
        if ((p->presentMask & (1u << i)) && (__bridge const void *)p->color[i] == texture) return YES;
    }
    return NO;
}

/* Is (texture, level, slice 0) one of the pass's attachment subresources? */
static BOOL dec_is_attachment_level(const dec_pass_parse *p, const void *texture, uint32_t level) {
    if (p->depth && (__bridge const void *)p->depth == texture && p->depthLevel == level) return YES;
    for (uint32_t i = 0; i < MCMTL_MAX_COLOR_ATTACHMENTS; i++) {
        if ((p->presentMask & (1u << i)) && (__bridge const void *)p->color[i] == texture && p->level[i] == level) return YES;
    }
    return NO;
}

/* ---------------------------------------------------------------------------------------------- */
/* M2: main-stream index                                                                            */
/* ---------------------------------------------------------------------------------------------- */

static BOOL dec_reserve(void **array, uint32_t *capacity, uint32_t needed, size_t elementSize) {
    if (needed <= *capacity) return YES;
    uint32_t cap = *capacity ? *capacity : 64u;
    while (cap < needed) {
        if (cap >= 0x40000000u) return NO;
        cap <<= 1;
    }
    void *grown = realloc(*array, (size_t)cap * elementSize);
    if (!grown) return NO;
    *array = grown;
    *capacity = cap;
    return YES;
}

static const void *dec_root_texture(id<MTLTexture> t) {
    for (uint32_t i = 0; t && i < 8; i++) {
        id<MTLTexture> parent = t.parentTexture;
        if (!parent) break;
        t = parent;
    }
    return (__bridge const void *)t;
}

/* Parent texture (or backing buffer of a texel view) of a texture handle, cached per call. */
static const dec_tex_res *dec_resolve_texture(mcmtl_decoder_ext *ext, uint64_t handle) {
    dec_tex_res *e = &ext->texCache[(uint32_t)(((handle >> 4) * 0x9E3779B97F4A7C15ull) >> 58) & (DEC_TEX_CACHE - 1)];
    if (e->handle == handle) return e;
    id<MTLTexture> t = MCMTL_TEXTURE(handle);
    e->handle = handle;
    if (t.textureType == MTLTextureTypeTextureBuffer) {
        e->root = NULL;
        e->buffer = (__bridge const void *)t.buffer;
        e->bufferOffset = t.bufferOffset;
    } else {
        e->root = dec_root_texture(t);
        e->buffer = NULL;
        e->bufferOffset = 0;
    }
    return e;
}

static int dec_ptr_compare(const void *a, const void *b) {
    uintptr_t x = (uintptr_t)*(const void *const *)a, y = (uintptr_t)*(const void *const *)b;
    return x < y ? -1 : (x > y ? 1 : 0);
}

static int dec_read_compare(const void *a, const void *b) {
    const dec_buf_read *x = a, *y = b;
    if (x->buffer != y->buffer) return (uintptr_t)x->buffer < (uintptr_t)y->buffer ? -1 : 1;
    return x->lo < y->lo ? -1 : (x->lo > y->lo ? 1 : 0);
}

/* Recently added entries of the pass are checked first (a pass binds a handful of resources over and over), so
 * the per-pass lists stay short before the final sort. */
#define DEC_INDEX_RECENT 8u

static BOOL dec_index_add_sampled(mcmtl_decoder_ext *ext, const dec_pass_info *pi, const void *root) {
    MCMTLDecoderState *ds = ext->ds;
    if (!root) return YES;
    const uint32_t stop = ext->sampledCount - pi->sampledStart > DEC_INDEX_RECENT ? ext->sampledCount - DEC_INDEX_RECENT : pi->sampledStart;
    for (uint32_t i = ext->sampledCount; i > stop; i--) {
        if (ds->idxSampled[i - 1] == root) return YES;
    }
    if (!dec_reserve((void **)&ds->idxSampled, &ds->idxSampledCap, ext->sampledCount + 1, sizeof(void *))) return NO;
    ds->idxSampled[ext->sampledCount++] = root;
    return YES;
}

static BOOL dec_index_add_read(mcmtl_decoder_ext *ext, const dec_pass_info *pi, const void *buffer, uint64_t lo) {
    MCMTLDecoderState *ds = ext->ds;
    if (!buffer) return YES;
    const uint32_t stop = ext->readCount - pi->readStart > DEC_INDEX_RECENT ? ext->readCount - DEC_INDEX_RECENT : pi->readStart;
    for (uint32_t i = ext->readCount; i > stop; i--) {
        dec_buf_read *e = &ds->idxReads[i - 1];
        if (e->buffer == buffer) {
            if (lo < e->lo) e->lo = lo;
            return YES;
        }
    }
    if (!dec_reserve((void **)&ds->idxReads, &ds->idxReadCap, ext->readCount + 1, sizeof(dec_buf_read))) return NO;
    ds->idxReads[ext->readCount++] = (dec_buf_read){ buffer, lo };
    return YES;
}

/* Present fusion: a pipeline set in the pass (only the BGRA8 variant check reads these; repeats among the last few
 * entries are skipped, the rest is harmless duplication). */
static BOOL dec_index_add_pipe(mcmtl_decoder_ext *ext, const dec_pass_info *pi, const void *pipeline) {
    MCMTLDecoderState *ds = ext->ds;
    if (!pipeline) return YES;
    const uint32_t stop = ext->pipeCount - pi->pipeStart > DEC_INDEX_RECENT ? ext->pipeCount - DEC_INDEX_RECENT : pi->pipeStart;
    for (uint32_t i = ext->pipeCount; i > stop; i--) {
        if (ds->idxPipes[i - 1] == pipeline) return YES;
    }
    if (!dec_reserve((void **)&ds->idxPipes, &ds->idxPipeCap, ext->pipeCount + 1, sizeof(void *))) return NO;
    ds->idxPipes[ext->pipeCount++] = pipeline;
    return YES;
}

/* Sorts and deduplicates the sampled textures and buffer reads of the pass just ended. */
static void dec_index_close_pass(mcmtl_decoder_ext *ext, dec_pass_info *pi) {
    MCMTLDecoderState *ds = ext->ds;
    pi->pipeCount = ext->pipeCount - pi->pipeStart;
    uint32_t n = ext->sampledCount - pi->sampledStart;
    if (n > 1) {
        const void **a = ds->idxSampled + pi->sampledStart;
        qsort(a, n, sizeof *a, dec_ptr_compare);
        uint32_t w = 1;
        for (uint32_t r = 1; r < n; r++) {
            if (a[r] != a[w - 1]) a[w++] = a[r];
        }
        n = w;
    }
    pi->sampledCount = n;
    ext->sampledCount = pi->sampledStart + n;
    n = ext->readCount - pi->readStart;
    if (n > 1) {
        dec_buf_read *a = ds->idxReads + pi->readStart;
        qsort(a, n, sizeof *a, dec_read_compare);   /* per buffer, the lowest offset comes first */
        uint32_t w = 1;
        for (uint32_t r = 1; r < n; r++) {
            if (a[r].buffer != a[w - 1].buffer) a[w++] = a[r];
        }
        n = w;
    }
    pi->readCount = n;
    ext->readCount = pi->readStart + n;
}

/* ---------------------------------------------------------------------------------------------- */
/* Terrain occlusion culling (MCMTL_OCCLUSION; CONTRACT.md 16.6, docs/metal/OCCLUSION.md)          */
/* ---------------------------------------------------------------------------------------------- */

/*
 * Sections of multidraw terrain whose whole box is hidden behind the opaque terrain are not encoded. Everything is
 * decided here, from what the stream already carries; Java and the game are unchanged.
 *
 * Queries (Metal visibility results, Boolean, one 8-byte slot each; on a tile-based GPU they are counted on chip and
 * written when the encoder ends, so nothing is stored or split):
 *  - geometry: every encoded opaque (solid / cutout) terrain record counts into its section's slot, against the depth
 *    of the terrain drawn before it;
 *  - box: after the last opaque terrain record of the frame (before the next pipeline or END_PASS, i.e. before
 *    entities, translucent terrain, clouds write depth), the box origin + [-2, 18]^3 of every culled section and of
 *    every candidate (geometry query without a sample) is drawn without colour or depth writes, depth test GEQUAL
 *    (reverse Z), into its own slot. The box contains all of the section's geometry (block models reach one block
 *    out of their cell, plus random offsets), so "no sample of the box passes" means every fragment of the section,
 *    translucent included, is strictly behind the final opaque depth: not encoding it changes no pixel of that frame.
 * Decisions (per FRAME_END submit, before decoding; results are read when their frame's timeline value completed):
 *  - a section is culled iff its last `confirm` box queries had no sample, its box was entirely inside the frustum
 *    in each of those frames and is now, it is farther than `near` blocks, and all those frames had the same eye:
 *    the Projection matrix, CameraBlockPos, CameraOffset and the translation of ModelViewMat are bitwise unchanged
 *    (any change drops all evidence: no culling while the camera moves, bobs or zooms; rotation keeps it, since
 *    occlusion from a fixed point does not depend on the view direction).
 *  - a culled section is box-queried every frame; one sample re-enables it, at the latency of reading results back.
 *  - a section whose records changed (mesh rebuilt, re-uploaded, resorted) or that vanished, and that was encoded in
 *    the previous frame, drops the evidence of every section whose box overlaps its box on screen.
 *  - evidence older than DEC_OCCL_MAX_RESULT_AGE frames does not keep a section culled.
 * Not exact in general (docs/metal/OCCLUSION.md): a box tested with the pixel centres of earlier frames can miss
 * a sub-pixel gap that a rotated camera's pixel centres hit, animated cutout textures can open gaps, and a gap
 * that opens is seen 1-2 frames late. Off by default; MCMTL_OCCLUSION=2 runs the queries and counts what would be
 * culled without culling.
 *
 * Section identity is the origin read from the instance stream (vertex slot 1) at the record's baseInstance. Records,
 * section origins and uniforms are read on the CPU at decode time, resolved through this call's COPY_BUFFER records
 * (the staged upload of the section infos); a range written by a copy of an earlier call that may not have run yet,
 * or by anything else, means no plan for the frame.
 */

enum { DEC_OCCL_CLASS_NONE = 0, DEC_OCCL_CLASS_OPAQUE = 1, DEC_OCCL_CLASS_TRANSLUCENT = 2 };
enum { DEC_OCCL_FS_FREE = 0, DEC_OCCL_FS_BUILDING, DEC_OCCL_FS_SUBMITTED };
enum { DEC_OCCL_SLOT_GEOM = 1u, DEC_OCCL_SLOT_BOX = 2u, DEC_OCCL_SLOT_INSIDE = 4u };
#define DEC_OCCL_REC_CULL       0x80000000u
#define DEC_OCCL_BOX_LO         (-2.0f)
#define DEC_OCCL_BOX_HI         18.0f
#define DEC_OCCL_MAX_CHANGES    48u      /* changed / vanished sections per frame before a global reset        */
#define DEC_OCCL_MAX_BOXES      16384u
#define DEC_OCCL_RETEST_FRAMES  8u       /* candidate with a visible box: next box query after this many frames */
#define DEC_OCCL_MAX_RESULT_AGE 8u       /* culled only while its newest box result is at most this old        */
#define DEC_OCCL_KEEP_FRAMES    2400u    /* section entries unseen this long are dropped at the next rehash    */
#define DEC_OCCL_SENTINEL       UINT64_MAX

static inline BOOL dec_fits(uint64_t a, uint64_t b, uint64_t limit);
static inline void dec_bind_buffer(mcmtl_decode_ctx *ctx, MCMTLDecoderState *ds, uint32_t stage, uint32_t index,
                                   __unsafe_unretained id<MTLBuffer> buffer, uint64_t offset);

static NSString *const kOcclShaderSource =
    @"#include <metal_stdlib>\n"
     "using namespace metal;\n"
     "struct mcmtl_occl_params { float4x4 vp; };\n"
     "struct mcmtl_occl_out { float4 pos [[position]]; };\n"
     "constant ushort kOcclBox[36] = { 0,4,6, 0,6,2, 1,3,7, 1,7,5, 0,1,5, 0,5,4, 2,6,7, 2,7,3, 0,2,3, 0,3,1, 4,5,7, 4,7,6 };\n"
     /* Faces counter-clockwise seen from outside; FLIP_VERTEX_Y like every translated vertex stage, so they are
      * clockwise on screen: front facing Clockwise + cull back, as the terrain pipelines. */
     "vertex mcmtl_occl_out mcmtl_occl_box_vs(uint vid [[vertex_id]], uint iid [[instance_id]],\n"
     "                                        constant mcmtl_occl_params &P [[buffer(16)]],\n"
     "                                        const device float4 *origins [[buffer(17)]]) {\n"
     "    ushort c = kOcclBox[vid];\n"
     "    float3 corner = float3((c & 1) ? 18.0 : -2.0, (c & 2) ? 18.0 : -2.0, (c & 4) ? 18.0 : -2.0);\n"
     "    float4 p = P.vp * float4(origins[iid].xyz + corner, 1.0);\n"
     "    p.y = -p.y;\n"
     "    mcmtl_occl_out o;\n"
     "    o.pos = p;\n"
     "    return o;\n"
     "}\n";

static MCMTLOcclState *dec_occl_create(uint32_t mode, uint32_t confirm, float nearBlocks, uint32_t minAge) {
    if (mode != DEC_OCCL_CULL && mode != DEC_OCCL_DRY) return nil;
    MCMTLOcclState *o = [MCMTLOcclState new];
    o->mode = mode;
    o->confirm = confirm < 1 ? 1 : (confirm > 64 ? 64 : confirm);
    o->nearBlocks = nearBlocks < 0 ? 0 : nearBlocks;
    o->minAge = minAge > DEC_OCCL_RING - 2 ? DEC_OCCL_RING - 2 : minAge;
    o->classes = [NSMapTable weakToStrongObjectsMapTable];
    o->epoch = 1;
    o->building = UINT32_MAX;
    return o;
}

static MCMTLOcclState *dec_occl_env(void) {
    const char *v = getenv("MCMTL_OCCLUSION");
    if (!v) return nil;
    const char *c = getenv("MCMTL_OCCLUSION_CONFIRM");
    const char *n = getenv("MCMTL_OCCLUSION_NEAR");
    const char *a = getenv("MCMTL_OCCLUSION_MIN_AGE");
    return dec_occl_create((uint32_t)atoi(v), c ? (uint32_t)atoi(c) : 2u, n ? (float)atof(n) : 8.0f, a ? (uint32_t)atoi(a) : 0u);
}

/* DEC_OCCL_CLASS_* of a pipeline: vanilla's *_TERRAIN_MULTIDRAW layout (28+ byte vertices with float3 Position at
 * offset 0 in slot 0; 16-byte per-instance ChunkPosition int3 + visibility in slot 1), Projection / Globals /
 * TerrainUniform at vertex [[buffer(0)]], (1), (5) (CONTRACT.md 6.1: TerrainUniform is the only dense UBO), filled
 * triangles. Opaque: no blending, depth GEQUAL with writes, no depth bias. Translucent: blending. */
static uint32_t dec_occl_classify(MCMTLDevice *dev, MCMTLPipeline *p) {
    MTLRenderPipelineDescriptor *d = p->descriptorTemplate;
    MTLVertexDescriptor *vd = d.vertexDescriptor;
    if (!vd) return DEC_OCCL_CLASS_NONE;
    MTLVertexBufferLayoutDescriptor *l0 = vd.layouts[MCMTL_VERTEX_BUFFER_INDEX_BASE];
    MTLVertexBufferLayoutDescriptor *l1 = vd.layouts[MCMTL_VERTEX_BUFFER_INDEX_BASE - 1];
    MTLVertexAttributeDescriptor *pos = vd.attributes[0], *chunk = vd.attributes[4];
    if (l0.stepFunction != MTLVertexStepFunctionPerVertex || l0.stride < 12) return DEC_OCCL_CLASS_NONE;
    if (l1.stepFunction != MTLVertexStepFunctionPerInstance || l1.stepRate != 1 || l1.stride != 16) return DEC_OCCL_CLASS_NONE;
    if (pos.format != MTLVertexFormatFloat3 || pos.bufferIndex != MCMTL_VERTEX_BUFFER_INDEX_BASE || pos.offset != 0) return DEC_OCCL_CLASS_NONE;
    if (chunk.format != MTLVertexFormatInt3 || chunk.bufferIndex != MCMTL_VERTEX_BUFFER_INDEX_BASE - 1 || chunk.offset != 0) {
        return DEC_OCCL_CLASS_NONE;
    }
    const uint32_t ubos = p->bufferMask[0] & 0x3FFFu;
    if ((ubos & 0x23u) != 0x23u || (ubos & 0x3FC0u)) return DEC_OCCL_CLASS_NONE;
    if (p->primitiveType != MTLPrimitiveTypeTriangle || p->emulation != MCMTL_EMULATION_NONE || p->fillMode != MTLTriangleFillModeFill) {
        return DEC_OCCL_CLASS_NONE;
    }
    if (d.colorAttachments[0].blendingEnabled) return DEC_OCCL_CLASS_TRANSLUCENT;
    if (p->depthStencilState != mcmtl_device_depth_stencil_state(dev, MTLCompareFunctionGreaterEqual, YES)) return DEC_OCCL_CLASS_NONE;
    if (p->depthBiasConstant != 0.0f || p->depthBiasSlopeScale != 0.0f) return DEC_OCCL_CLASS_NONE;
    return DEC_OCCL_CLASS_OPAQUE;
}

static uint32_t dec_occl_class(mcmtl_decoder_ext *ext, MCMTLPipeline *p) {
    MCMTLOcclState *O = ext->ds->occl;
    if (!O || !p) return DEC_OCCL_CLASS_NONE;
    NSNumber *n = [O->classes objectForKey:p];
    if (n) return n.unsignedIntValue;
    uint32_t k = dec_occl_classify(ext->dev, p);
    [O->classes setObject:@(k) forKey:p];
    return k;
}

/* Index scan state of the terrain bindings of the current pass (dec_index_build). */
typedef struct dec_occl_scan {
    const void *pipeline;
    uint32_t klass;
    mcmtl_handle vb[2];
    uint64_t vbOffset[2];
    mcmtl_handle ib;
    mcmtl_handle ubo[3];
    uint64_t uboOffset[3];
} dec_occl_scan;

static inline int dec_occl_ubo_slot(uint32_t index) {
    return index == 0 ? 0 : index == 1 ? 1 : index == 5 ? 2 : -1;
}

/* One record of the index scan. Returns NO when an array cannot grow (the index then stops, as for any failure). */
static BOOL dec_occl_index_record(mcmtl_decoder_ext *ext, dec_occl_scan *sc, uint32_t op, const uint8_t *rec, uint64_t offset) {
    MCMTLOcclState *O = ext->ds->occl;
    switch (op) {
        case MCMTL_OP_BEGIN_PASS:
            memset(sc, 0, sizeof *sc);
            break;
        case MCMTL_OP_SET_PIPELINE: {
            const mcmtl_cmd_set_pipeline *c = (const mcmtl_cmd_set_pipeline *)rec;
            sc->pipeline = (const void *)(uintptr_t)c->pipeline;
            sc->klass = c->pipeline ? dec_occl_class(ext, MCMTL_PIPELINE(c->pipeline)) : DEC_OCCL_CLASS_NONE;
            sc->ubo[0] = sc->ubo[1] = sc->ubo[2] = 0;   /* not set since SET_PIPELINE: the decoder binds dummies */
            break;
        }
        case MCMTL_OP_SET_VERTEX_BUFFER: {
            const mcmtl_cmd_set_vertex_buffer *c = (const mcmtl_cmd_set_vertex_buffer *)rec;
            if (c->slot < 2) {
                sc->vb[c->slot] = c->buffer;
                sc->vbOffset[c->slot] = c->offset;
            }
            break;
        }
        case MCMTL_OP_SET_INDEX_BUFFER:
            sc->ib = ((const mcmtl_cmd_set_index_buffer *)rec)->buffer;
            break;
        case MCMTL_OP_SET_BUFFER: {
            const mcmtl_cmd_set_buffer *c = (const mcmtl_cmd_set_buffer *)rec;
            int k = dec_occl_ubo_slot(c->index);
            if (k >= 0 && (c->stages & MCMTL_STAGE_VERTEX)) {
                sc->ubo[k] = c->buffer;
                sc->uboOffset[k] = c->offset;
            }
            break;
        }
        case MCMTL_OP_CLEAR_BINDING: {
            const mcmtl_cmd_clear_binding *c = (const mcmtl_cmd_clear_binding *)rec;
            int k = dec_occl_ubo_slot(c->index);
            if (c->kind == MCMTL_BINDING_KIND_BUFFER && k >= 0 && (c->stages & MCMTL_STAGE_VERTEX)) sc->ubo[k] = 0;
            break;
        }
        case MCMTL_OP_DRAW_INDEXED_INDIRECT: {
            const mcmtl_cmd_draw_indirect *c = (const mcmtl_cmd_draw_indirect *)rec;
            if (sc->klass == DEC_OCCL_CLASS_NONE || !c->buffer || !c->drawCount || !sc->vb[0] || !sc->vb[1] || !sc->ib ||
                !sc->ubo[0] || !sc->ubo[1] || !sc->ubo[2]) {
                break;
            }
            if (!dec_reserve((void **)&O->cmds, &O->cmdCap, O->cmdCount + 1, sizeof(dec_occl_cmd))) return NO;
            dec_occl_cmd *k = &O->cmds[O->cmdCount++];
            memset(k, 0, sizeof *k);
            k->offset = offset;
            k->pass = ext->passCount - 1;
            k->klass = sc->klass;
            k->pipeline = sc->pipeline;
            k->vb0 = sc->vb[0];
            k->vb0Offset = sc->vbOffset[0];
            k->vb1 = sc->vb[1];
            k->vb1Offset = sc->vbOffset[1];
            k->ib = sc->ib;
            k->args = c->buffer;
            k->argsOffset = c->offset;
            k->drawCount = c->drawCount;
            for (int i = 0; i < 3; i++) {
                k->ubo[i] = sc->ubo[i];
                k->uboOffset[i] = sc->uboOffset[i];
            }
            break;
        }
        case MCMTL_OP_COPY_BUFFER: {
            const mcmtl_cmd_copy_buffer *c = (const mcmtl_cmd_copy_buffer *)rec;
            if (!c->dstBuffer || !c->length) break;
            if (!dec_reserve((void **)&O->writes, &O->writeCap, O->writeCount + 1, sizeof(dec_occl_write))) return NO;
            id<MTLBuffer> src = c->srcBuffer ? MCMTL_BUFFER(c->srcBuffer) : nil;
            BOOL cpu = src && dec_fits(c->srcOffset, c->length, dec_buffer_length(ext, src)) && src.storageMode != MTLStorageModePrivate;
            O->writes[O->writeCount++] = (dec_occl_write){ offset, (const void *)(uintptr_t)c->dstBuffer, c->dstOffset, c->dstOffset + c->length,
                                                           cpu ? (const void *)(uintptr_t)c->srcBuffer : NULL, c->srcOffset };
            break;
        }
        case MCMTL_OP_COPY_TEXTURE_TO_BUFFER: {
            const mcmtl_cmd_copy_buffer_texture *c = (const mcmtl_cmd_copy_buffer_texture *)rec;
            if (!c->buffer) break;
            if (!dec_reserve((void **)&O->writes, &O->writeCap, O->writeCount + 1, sizeof(dec_occl_write))) return NO;
            O->writes[O->writeCount++] = (dec_occl_write){ offset, (const void *)(uintptr_t)c->buffer, c->bufferOffset, UINT64_MAX, NULL, 0 };
            break;
        }
        default:
            break;
    }
    return YES;
}

/* CPU pointer to bytes [lo, hi) of a buffer as the GPU will see them at stream offset `before`: the newest COPY_BUFFER
 * of this call that covers the range (read from its source), else the buffer itself when no write of this call or of
 * a not yet completed earlier call touches the range. NULL when unknown. */
static const uint8_t *dec_occl_resolve(mcmtl_decoder_ext *ext, mcmtl_handle handle, uint64_t lo, uint64_t hi, uint64_t before) {
    MCMTLOcclState *O = ext->ds->occl;
    if (!handle || hi <= lo) return NULL;
    id<MTLBuffer> b = MCMTL_BUFFER(handle);
    if (hi > dec_buffer_length(ext, b)) return NULL;
    const void *key = (const void *)(uintptr_t)handle;
    for (uint32_t i = O->writeCount; i-- > 0;) {
        const dec_occl_write *w = &O->writes[i];
        if (w->offset >= before || w->dst != key || w->hi <= lo || w->lo >= hi) continue;
        if (!w->src || w->lo > lo || w->hi < hi) return NULL;
        const uint8_t *base = MCMTL_BUFFER((mcmtl_handle)(uintptr_t)w->src).contents;
        return base ? base + w->srcOffset + (lo - w->lo) : NULL;
    }
    const uint64_t completed = mcmtl_status_load_u64(&ext->dev->status->completedValue);
    if (O->wildcardValue > completed) return NULL;
    for (uint32_t i = 0; i < O->priorCount; i++) {
        const dec_occl_prior *p = &O->prior[i];
        if (p->dst == key && p->hi > lo && p->lo < hi && p->value > completed) return NULL;
    }
    if (b.storageMode == MTLStorageModePrivate) return NULL;
    const uint8_t *base = b.contents;
    return base ? base + lo : NULL;
}

/* Section table. */
static inline uint32_t dec_occl_hash(int32_t x, int32_t y, int32_t z) {
    uint32_t h = (uint32_t)x * 0x9E3779B1u ^ (uint32_t)y * 0x85EBCA77u ^ (uint32_t)z * 0xC2B2AE3Du;
    return h ^ (h >> 15);
}

static dec_occl_sec *dec_occl_find(MCMTLOcclState *O, int32_t x, int32_t y, int32_t z) {
    if (!O->secCap) return NULL;
    const uint32_t mask = O->secCap - 1;
    for (uint32_t i = dec_occl_hash(x, y, z) & mask;; i = (i + 1) & mask) {
        dec_occl_sec *e = &O->secs[i];
        if (!e->used) return NULL;
        if (e->x == x && e->y == y && e->z == z) return e;
    }
}

/* Entry for (x, y, z), inserted when missing. The table never fills: dec_occl_rehash keeps the load <= 1/2. */
static dec_occl_sec *dec_occl_get(MCMTLOcclState *O, int32_t x, int32_t y, int32_t z) {
    const uint32_t mask = O->secCap - 1;
    for (uint32_t i = dec_occl_hash(x, y, z) & mask;; i = (i + 1) & mask) {
        dec_occl_sec *e = &O->secs[i];
        if (!e->used) {
            memset(e, 0, sizeof *e);
            e->used = 1;
            e->x = x;
            e->y = y;
            e->z = z;
            e->geomSlot = e->boxSlot = -1;
            O->secUsed++;
            return e;
        }
        if (e->x == x && e->y == y && e->z == z) return e;
    }
}

/* Before a plan: room for `incoming` more entries at a load <= 1/2; drops entries unseen for DEC_OCCL_KEEP_FRAMES. */
static BOOL dec_occl_rehash(MCMTLOcclState *O, uint32_t incoming) {
    if (O->secCap && (uint64_t)(O->secUsed + incoming) * 2 <= O->secCap) return YES;
    uint32_t live = 0;
    for (uint32_t i = 0; i < O->secCap; i++) {
        if (O->secs[i].used && O->secs[i].seenFrame + DEC_OCCL_KEEP_FRAMES >= O->frame) live++;
    }
    uint32_t cap = 1024;
    while ((uint64_t)(live + incoming) * 2 > cap) cap <<= 1;
    dec_occl_sec *fresh = calloc(cap, sizeof(dec_occl_sec));
    if (!fresh) return NO;
    dec_occl_sec *old = O->secs;
    const uint32_t oldCap = O->secCap;
    O->secs = fresh;
    O->secCap = cap;
    O->secUsed = 0;
    for (uint32_t i = 0; i < oldCap; i++) {
        const dec_occl_sec *e = &old[i];
        if (!e->used || e->seenFrame + DEC_OCCL_KEEP_FRAMES < O->frame) continue;
        dec_occl_sec *n = dec_occl_get(O, e->x, e->y, e->z);
        *n = *e;
    }
    free(old);
    return YES;
}

/* State of an entry from an older epoch (global reset since it was last touched): no evidence. */
static inline void dec_occl_touch(MCMTLOcclState *O, dec_occl_sec *e) {
    if (e->epoch == O->epoch) return;
    e->epoch = O->epoch;
    e->boxRun = 0;
    e->candidate = 0;
    e->culled = 0;
    e->retestAfter = 0;
    e->lastBoxFrame = 0;
    e->evidenceFrom = O->frame;
}

static inline void dec_occl_forget(MCMTLOcclState *O, dec_occl_sec *e) {
    e->boxRun = 0;
    e->candidate = 0;
    e->evidenceFrom = O->frame;
}

/* Global reset: every section loses its evidence; results of earlier frames are dropped. */
static void dec_occl_reset(MCMTLOcclState *O) {
    O->epoch++;
}

/* Clip-space position of a camera-relative point. */
static inline void dec_occl_clip(const float m[16], float x, float y, float z, float out[4]) {
    for (int r = 0; r < 4; r++) out[r] = m[r] * x + m[4 + r] * y + m[8 + r] * z + m[12 + r];
}

/* Box origin + [LO, HI]^3 of a section (camera-relative): fully inside the frustum (with a small margin), and its NDC
 * rect (infinite when a corner is at or behind the eye plane). */
static void dec_occl_box_geometry(const MCMTLOcclState *O, int32_t x, int32_t y, int32_t z, BOOL *inside, float rect[4]) {
    const float rx = (float)(x - O->camBlock[0]) + O->camOffset[0];
    const float ry = (float)(y - O->camBlock[1]) + O->camOffset[1];
    const float rz = (float)(z - O->camBlock[2]) + O->camOffset[2];
    BOOL in = YES, finite = YES;
    float x0 = INFINITY, y0 = INFINITY, x1 = -INFINITY, y1 = -INFINITY;
    for (int c = 0; c < 8; c++) {
        float p[4];
        dec_occl_clip(O->vp, rx + ((c & 1) ? DEC_OCCL_BOX_HI : DEC_OCCL_BOX_LO), ry + ((c & 2) ? DEC_OCCL_BOX_HI : DEC_OCCL_BOX_LO),
                      rz + ((c & 4) ? DEC_OCCL_BOX_HI : DEC_OCCL_BOX_LO), p);
        if (!(p[3] > 1e-3f)) {
            in = NO;
            finite = NO;
            continue;
        }
        const float lim = 0.995f * p[3];
        if (!(fabsf(p[0]) <= lim && fabsf(p[1]) <= lim && p[2] >= 0.0f && p[2] <= p[3])) in = NO;
        const float nx = p[0] / p[3], ny = p[1] / p[3];
        x0 = fminf(x0, nx); x1 = fmaxf(x1, nx);
        y0 = fminf(y0, ny); y1 = fmaxf(y1, ny);
    }
    *inside = in;
    if (finite) {
        rect[0] = x0; rect[1] = y0; rect[2] = x1; rect[3] = y1;
    } else {
        rect[0] = rect[1] = -INFINITY;
        rect[2] = rect[3] = INFINITY;
    }
}

/* The section's box lies entirely outside one plane of the frustum. */
static BOOL dec_occl_box_outside(const MCMTLOcclState *O, int32_t x, int32_t y, int32_t z) {
    const float rx = (float)(x - O->camBlock[0]) + O->camOffset[0];
    const float ry = (float)(y - O->camBlock[1]) + O->camOffset[1];
    const float rz = (float)(z - O->camBlock[2]) + O->camOffset[2];
    uint32_t all = 0x3Fu;
    for (int c = 0; c < 8; c++) {
        float p[4];
        dec_occl_clip(O->vp, rx + ((c & 1) ? DEC_OCCL_BOX_HI : DEC_OCCL_BOX_LO), ry + ((c & 2) ? DEC_OCCL_BOX_HI : DEC_OCCL_BOX_LO),
                      rz + ((c & 4) ? DEC_OCCL_BOX_HI : DEC_OCCL_BOX_LO), p);
        uint32_t out = 0;
        out |= (p[0] < -p[3]) ? 1u : 0u;
        out |= (p[0] > p[3]) ? 2u : 0u;
        out |= (p[1] < -p[3]) ? 4u : 0u;
        out |= (p[1] > p[3]) ? 8u : 0u;
        out |= (p[2] < 0.0f) ? 16u : 0u;
        out |= (p[2] > p[3]) ? 32u : 0u;
        all &= out;
    }
    return all != 0;
}

static inline BOOL dec_occl_overlap(const float a[4], const float b[4]) {
    return a[0] <= b[2] && b[0] <= a[2] && a[1] <= b[3] && b[1] <= a[3];
}

/* Distance from the camera (camera-relative origin) to the section's box. */
static float dec_occl_box_distance(const MCMTLOcclState *O, int32_t x, int32_t y, int32_t z) {
    const int32_t o[3] = { x, y, z };
    float d2 = 0;
    for (int i = 0; i < 3; i++) {
        const float lo = (float)(o[i] - O->camBlock[i]) + O->camOffset[i] + DEC_OCCL_BOX_LO;
        const float hi = lo + (DEC_OCCL_BOX_HI - DEC_OCCL_BOX_LO);
        const float d = lo > 0 ? lo : (hi < 0 ? -hi : 0);
        d2 += d * d;
    }
    return sqrtf(d2);
}

static inline uint64_t dec_occl_mix(uint64_t h) {
    h ^= h >> 33;
    h *= 0xFF51AFD7ED558CCDull;
    h ^= h >> 33;
    h *= 0xC4CEB9FE1A85EC53ull;
    return h ^ (h >> 33);
}

static uint64_t dec_occl_fnv(uint64_t h, const void *data, size_t n) {
    const uint8_t *p = data;
    for (size_t i = 0; i < n; i++) h = (h ^ p[i]) * 0x100000001B3ull;
    return h;
}

/* Applies the results of completed frames (oldest first) to the section entries. */
static void dec_occl_consume(mcmtl_decoder_ext *ext) {
    MCMTLOcclState *O = ext->ds->occl;
    MCMTLDecoderState *ds = ext->ds;
    const uint64_t completed = mcmtl_status_load_u64(&ext->dev->status->completedValue);
    for (;;) {
        dec_occl_frame *f = NULL;
        uint32_t fi = 0;
        for (uint32_t i = 0; i < DEC_OCCL_RING; i++) {
            dec_occl_frame *g = &O->ring[i];
            if (g->state == DEC_OCCL_FS_SUBMITTED && (!f || g->frame < f->frame)) {
                f = g;
                fi = i;
            }
        }
        if (!f || f->value > completed || O->frame - f->frame < O->minAge) return;
        f->state = DEC_OCCL_FS_FREE;
        if (f->epoch != O->epoch) {
            ds->stats.occlResultsDropped++;
            continue;
        }
        if (!O->ringVis[fi]) continue;
        ds->stats.occlResultFrames++;
        const uint64_t *counts = O->ringVis[fi].contents;
        /* Box slots first: a box result decides the section's evidence for that frame. */
        for (uint32_t s = f->geomSlots; s < f->slots; s++) {
            const uint64_t c = counts[s];
            if (c == DEC_OCCL_SENTINEL) continue;   /* never written: the query was not encoded */
            const int32_t *k = &f->keys[3 * s];
            dec_occl_sec *e = dec_occl_find(O, k[0], k[1], k[2]);
            if (!e || e->epoch != O->epoch || f->frame < e->evidenceFrom) continue;
            if (e->lastBoxFrame + DEC_OCCL_MAX_RESULT_AGE < f->frame) e->boxRun = 0;   /* evidence must be recent and consecutive */
            e->lastBoxFrame = f->frame;
            if (c == 0 && (f->flags[s] & DEC_OCCL_SLOT_INSIDE)) {
                e->boxRun++;
            } else {
                e->boxRun = 0;
                if (c) {
                    e->candidate = 0;
                    e->retestAfter = f->frame + DEC_OCCL_RETEST_FRAMES;
                }
            }
        }
        /* Geometry slots (one per run; a section may own several): any sample means visible. */
        for (uint32_t s = 1; s < f->geomSlots; s++) {
            const uint64_t c = counts[s];
            if (c == DEC_OCCL_SENTINEL || !c) continue;
            const int32_t *k = &f->keys[3 * s];
            dec_occl_sec *e = dec_occl_find(O, k[0], k[1], k[2]);
            if (!e || e->epoch != O->epoch || f->frame < e->evidenceFrom) continue;
            e->geomVisibleFrame = f->frame;
            e->candidate = 0;
            if (e->lastBoxFrame != f->frame) e->boxRun = 0;
        }
        for (uint32_t s = 1; s < f->geomSlots; s++) {
            if (counts[s] != 0) continue;
            const int32_t *k = &f->keys[3 * s];
            dec_occl_sec *e = dec_occl_find(O, k[0], k[1], k[2]);
            if (!e || e->epoch != O->epoch || f->frame < e->evidenceFrom) continue;
            if (e->geomVisibleFrame != f->frame && e->lastBoxFrame != f->frame) e->candidate = 1;
        }
    }
}

/* Records the opaque terrain's eye; a change (or the first frame) is a global reset. */
static void dec_occl_lock(mcmtl_decoder_ext *ext, const float proj[16], const uint8_t *globals, const float mv[16]) {
    MCMTLOcclState *O = ext->ds->occl;
    /* Values, not bits: -0 and +0 are the same eye (a rotation matrix built by JOML carries either in its zeros). */
    float key[16 + 3 + 7];
    uint32_t n = 0;
    for (int i = 0; i < 16; i++) key[n++] = proj[i] + 0.0f;
    float cam[3];
    memcpy(cam, globals + 16, 12);                       /* CameraOffset */
    for (int i = 0; i < 3; i++) key[n++] = cam[i] + 0.0f;
    const int mvIdx[7] = { 3, 7, 11, 12, 13, 14, 15 };   /* ModelViewMat: projective row and translation column */
    for (int i = 0; i < 7; i++) key[n++] = mv[mvIdx[i]] + 0.0f;
    uint64_t h = 0xCBF29CE484222325ull;
    h = dec_occl_fnv(h, key, sizeof key);
    h = dec_occl_fnv(h, globals, 12);                    /* CameraBlockPos */
    if (!O->haveLock || h != O->lockKey) {
        O->lockKey = h;
        O->haveLock = YES;
        dec_occl_reset(O);
        ext->ds->stats.occlEyeResets++;
    }
}

/* A FRAME_END submit without a plan: every section was encoded and nothing was learnt; drop all evidence (a mesh
 * change in that frame went unseen). */
static void dec_occl_no_plan(mcmtl_decoder_ext *ext, uint32_t reason) {
    MCMTLOcclState *O = ext->ds->occl;
    ext->ds->stats.occlNoPlan[reason < 8 ? reason : 7]++;
    dec_occl_reset(O);
    O->prevCount = 0;
    O->haveLock = NO;
}

/* The plan of a FRAME_END COMMIT submit (after the index): reads the terrain records, decides which sections are not
 * encoded and which queries run, and prepares this frame's query ring entry. */
static void dec_occl_plan(mcmtl_decoder_ext *ext) {
    MCMTLDecoderState *ds = ext->ds;
    MCMTLOcclState *O = ds->occl;
    const uint64_t t0 = clock_gettime_nsec_np(CLOCK_UPTIME_RAW);
    O->cmdCursor = 0;
    O->boxCount = 0;
    dec_occl_consume(ext);
    if (!ext->indexed) return dec_occl_no_plan(ext, DEC_OCCL_NP_NO_INDEX);

    /* The opaque terrain: one pass, one transform. Commands of a pass the index rolled back are gone. */
    uint32_t keep = 0;
    for (uint32_t i = 0; i < O->cmdCount; i++) {
        if (O->cmds[i].offset < ext->indexEnd && O->cmds[i].pass < ext->passCount) O->cmds[keep++] = O->cmds[i];
    }
    O->cmdCount = keep;
    const dec_occl_cmd *first = NULL;
    uint32_t terrainPass = 0;
    for (uint32_t i = 0; i < O->cmdCount; i++) {
        const dec_occl_cmd *k = &O->cmds[i];
        if (k->klass != DEC_OCCL_CLASS_OPAQUE) continue;
        if (!first) {
            first = k;
            terrainPass = k->pass;
        } else if (k->pass != terrainPass) {
            return dec_occl_no_plan(ext, DEC_OCCL_NP_PASSES);
        }
        O->lastOpaqueOffset = k->offset;
    }
    if (!first) return dec_occl_no_plan(ext, DEC_OCCL_NP_NO_TERRAIN);
    O->terrainPassBegin = ds->idxPasses[terrainPass].begin;
    const uint8_t *proj = dec_occl_resolve(ext, first->ubo[0], first->uboOffset[0], first->uboOffset[0] + 64, first->offset);
    const uint8_t *glob = dec_occl_resolve(ext, first->ubo[1], first->uboOffset[1], first->uboOffset[1] + 32, first->offset);
    const uint8_t *mvb = dec_occl_resolve(ext, first->ubo[2], first->uboOffset[2], first->uboOffset[2] + 64, first->offset);
    if (!proj || !glob || !mvb) return dec_occl_no_plan(ext, DEC_OCCL_NP_UNRESOLVED);
    float P[16], MV[16];
    memcpy(P, proj, 64);
    memcpy(MV, mvb, 64);
    for (uint32_t i = 0; i < O->cmdCount; i++) {
        dec_occl_cmd *k = &O->cmds[i];
        k->cullable = k->pass == terrainPass;
        if (!k->cullable) continue;
        for (int u = 0; u < 3; u++) {
            if (k->ubo[u] == first->ubo[u] && k->uboOffset[u] == first->uboOffset[u]) continue;
            const uint32_t len = u == 1 ? 32 : 64;
            const uint8_t *a = dec_occl_resolve(ext, k->ubo[u], k->uboOffset[u], k->uboOffset[u] + len, k->offset);
            const uint8_t *b = u == 0 ? proj : u == 1 ? glob : mvb;
            if (!a) return dec_occl_no_plan(ext, DEC_OCCL_NP_UNRESOLVED);
            if (memcmp(a, b, len)) return dec_occl_no_plan(ext, DEC_OCCL_NP_TRANSFORM);
        }
    }
    dec_occl_lock(ext, P, glob, MV);
    memcpy(O->camBlock, glob, 12);
    memcpy(O->camOffset, glob + 16, 12);
    for (int c = 0; c < 4; c++) {
        for (int r = 0; r < 4; r++) {
            float s = 0;
            for (int k = 0; k < 4; k++) s += P[k * 4 + r] * MV[c * 4 + k];
            O->vp[c * 4 + r] = s;
        }
    }

    /* A free query ring entry (its buffer is not in flight). */
    uint32_t slotRing = UINT32_MAX;
    for (uint32_t i = 0; i < DEC_OCCL_RING; i++) {
        if (O->ring[i].state == DEC_OCCL_FS_FREE) {
            slotRing = i;
            break;
        }
    }
    if (slotRing == UINT32_MAX) return dec_occl_no_plan(ext, DEC_OCCL_NP_RING);

    /* Records -> sections. */
    uint32_t totalRecs = 0;
    for (uint32_t i = 0; i < O->cmdCount; i++) totalRecs += O->cmds[i].drawCount;
    if (!dec_occl_rehash(O, totalRecs) || !dec_reserve((void **)&O->recDec, &O->recCap, totalRecs, sizeof(uint32_t)) ||
        !dec_reserve((void **)&O->cur, &O->curCap, totalRecs, sizeof(uint32_t))) {
        return dec_occl_no_plan(ext, DEC_OCCL_NP_RECORDS);
    }
    O->curCount = 0;
    O->recCount = 0;
    uint64_t plannedQuads = 0;
    for (uint32_t i = 0; i < O->cmdCount; i++) {
        dec_occl_cmd *k = &O->cmds[i];
        k->firstRec = O->recCount;
        const uint8_t *args = dec_occl_resolve(ext, k->args, k->argsOffset, k->argsOffset + (uint64_t)MCMTL_INDIRECT_STRIDE_DRAW_INDEXED * k->drawCount,
                                               k->offset);
        if (!args || (k->argsOffset & 3u)) return dec_occl_no_plan(ext, DEC_OCCL_NP_UNRESOLVED);
        uint32_t lo = UINT32_MAX, hi = 0;
        for (uint32_t r = 0; r < k->drawCount; r++) {
            const uint32_t *a = (const uint32_t *)(args + MCMTL_INDIRECT_STRIDE_DRAW_INDEXED * r);
            if (!a[0] || !a[1]) continue;
            if (a[1] != 1) return dec_occl_no_plan(ext, DEC_OCCL_NP_RECORDS);
            if (a[4] < lo) lo = a[4];
            if (a[4] > hi) hi = a[4];
        }
        const uint8_t *infos = NULL;
        if (lo <= hi) {
            infos = dec_occl_resolve(ext, k->vb1, k->vb1Offset + 16ull * lo, k->vb1Offset + 16ull * hi + 16, k->offset);
            if (!infos) return dec_occl_no_plan(ext, DEC_OCCL_NP_UNRESOLVED);
        }
        const uint64_t cmdHash = dec_occl_mix((uint64_t)(uintptr_t)k->pipeline ^ dec_occl_mix(k->vb0 ^ (k->vb0Offset << 1)) ^
                                              dec_occl_mix(k->ib + 0x51ull));
        for (uint32_t r = 0; r < k->drawCount; r++) {
            const uint32_t *a = (const uint32_t *)(args + MCMTL_INDIRECT_STRIDE_DRAW_INDEXED * r);
            uint32_t entry = UINT32_MAX;
            if (a[0] && a[1]) {
                const int32_t *o = (const int32_t *)(infos + 16ull * (a[4] - lo));
                dec_occl_sec *e = dec_occl_get(O, o[0], o[1], o[2]);
                dec_occl_touch(O, e);
                if (e->seenFrame != O->frame) {
                    e->prevSeen = e->seenFrame + 1 == O->frame && O->prevFrame + 1 == O->frame;
                    if (!e->prevSeen) dec_occl_forget(O, e);   /* (re)appeared: its state is unknown */
                    e->seenFrame = O->frame;
                    e->sigNew = 0;
                    e->hasOpaque = 0;
                    e->geomSlot = e->boxSlot = -1;
                    O->cur[O->curCount++] = (uint32_t)(e - O->secs);
                }
                e->sigNew += dec_occl_mix(cmdHash ^ dec_occl_mix(((uint64_t)a[0] << 32 | a[2]) ^ dec_occl_mix((uint64_t)(uint32_t)a[3] + 7)));
                if (k->klass == DEC_OCCL_CLASS_OPAQUE) e->hasOpaque = 1;
                entry = (uint32_t)(e - O->secs);
                plannedQuads += a[0] / 6u;
            }
            O->recDec[O->recCount++] = entry;
        }
    }

    /* Geometry of this frame's sections. */
    for (uint32_t i = 0; i < O->curCount; i++) {
        dec_occl_sec *e = &O->secs[O->cur[i]];
        BOOL inside;
        dec_occl_box_geometry(O, e->x, e->y, e->z, &inside, e->rect);
        e->inside = inside;
        e->nearEye = dec_occl_box_distance(O, e->x, e->y, e->z) < O->nearBlocks;
    }

    /* Changes since the previous planned frame: a section encoded then whose records changed or that is gone now drops
     * the evidence of every section whose box overlaps it on screen (and its own). */
    uint32_t changes = 0;
    BOOL global = O->prevFrame + 1 != O->frame;   /* no previous plan to compare against */
    float changed[DEC_OCCL_MAX_CHANGES][4];
    if (!global) {
        for (uint32_t i = 0; i < O->curCount && !global; i++) {
            dec_occl_sec *e = &O->secs[O->cur[i]];
            if (e->prevSeen && e->drawnPrev && e->sigNew != e->sig) {
                if (changes == DEC_OCCL_MAX_CHANGES) global = YES;
                else memcpy(changed[changes++], e->rect, sizeof e->rect);
                dec_occl_forget(O, e);
            }
        }
        for (uint32_t i = 0; i < O->prevCount && !global; i++) {
            if (!O->prevDrawn[i]) continue;
            const int32_t *k = &O->prevKeys[3 * i];
            dec_occl_sec *e = dec_occl_find(O, k[0], k[1], k[2]);
            if (e && e->seenFrame == O->frame) continue;
            if (dec_occl_box_outside(O, k[0], k[1], k[2])) continue;   /* left the view: covers nothing on screen */
            if (changes == DEC_OCCL_MAX_CHANGES) {
                global = YES;
                break;
            }
            BOOL inside;
            dec_occl_box_geometry(O, k[0], k[1], k[2], &inside, changed[changes++]);
        }
    }
    for (uint32_t i = 0; i < O->curCount; i++) {
        dec_occl_sec *e = &O->secs[O->cur[i]];
        e->sig = e->sigNew;
        if (!(e->boxRun || e->candidate)) continue;
        BOOL hit = global;
        for (uint32_t c = 0; c < changes && !hit; c++) hit = dec_occl_overlap(changed[c], e->rect);
        if (hit) {
            dec_occl_forget(O, e);
            ds->stats.occlInvalidated++;
        }
    }

    /* Decisions. */
    dec_occl_frame *f = &O->ring[slotRing];
    uint32_t boxes = 0, culled = 0, would = 0, geomSections = 0;
    if (!dec_reserve((void **)&O->boxes, &O->boxCap, O->curCount, sizeof(uint32_t))) return dec_occl_no_plan(ext, DEC_OCCL_NP_RECORDS);
    for (uint32_t i = 0; i < O->curCount; i++) {
        dec_occl_sec *e = &O->secs[O->cur[i]];
        const BOOL eligible = e->inside && !e->nearEye && e->hasOpaque;
        BOOL cull = eligible && e->boxRun >= O->confirm && e->lastBoxFrame + DEC_OCCL_MAX_RESULT_AGE >= O->frame;
        BOOL box = eligible && (cull || (e->candidate && O->frame >= e->retestAfter)) && boxes < DEC_OCCL_MAX_BOXES;
        if (cull && !box) cull = NO;
        if (O->mode == DEC_OCCL_DRY && cull) {
            would++;
            cull = NO;
        }
        e->culled = cull;
        culled += cull;
        e->query = eligible && !cull;   /* only a section that could be culled needs to become a candidate */
        geomSections += e->query;
        if (box) O->boxes[boxes++] = O->cur[i];
    }
    /* Query slots. Metal allows each visibility offset once per encoder, so a geometry slot covers one run of consecutive
     * encoded opaque records of one section (records of culled sections do not interrupt a run: they are not encoded;
     * any other record does); a section can own several (one per layer). Slot 0 is never used; then the runs, then one
     * slot per box. */
    uint32_t runs = 0;
    for (uint32_t i = 0; i < O->cmdCount; i++) {
        const dec_occl_cmd *k = &O->cmds[i];
        if (!k->cullable || k->klass != DEC_OCCL_CLASS_OPAQUE) continue;
        uint32_t last = UINT32_MAX;
        for (uint32_t r = 0; r < k->drawCount; r++) {
            uint32_t d = O->recDec[k->firstRec + r];
            if (d != UINT32_MAX && O->secs[d].culled) continue;
            if (d != UINT32_MAX && !O->secs[d].query) d = UINT32_MAX;
            if (d != UINT32_MAX && d != last) runs++;
            last = d;
        }
    }
    const uint32_t geomEnd = 1 + runs;
    const uint32_t slots = geomEnd + boxes;
    if (slots > f->cap || !O->ringVis[slotRing]) {
        uint32_t cap = f->cap ? f->cap : 1024;
        while (cap < slots) cap <<= 1;
        int32_t *keys = realloc(f->keys, (size_t)cap * 3 * sizeof(int32_t));
        if (keys) f->keys = keys;
        uint8_t *flags = realloc(f->flags, cap);
        if (flags) f->flags = flags;
        id<MTLBuffer> vis = [ext->dev->device newBufferWithLength:(NSUInteger)cap * 8u options:MTLResourceStorageModeShared];
        if (!keys || !flags || !vis) return dec_occl_no_plan(ext, DEC_OCCL_NP_RING);
        vis.label = @"mcmetal occlusion queries";
        O->ringVis[slotRing] = vis;
        f->cap = cap;
    }
    memset(O->ringVis[slotRing].contents, 0xFF, (size_t)slots * 8u);
    f->flags[0] = 0;

    /* Per record: not encoded (DEC_OCCL_REC_CULL), its run's geometry slot + 1, or 0 (no query). */
    uint64_t skippedQuads = 0, skippedRecs = 0;
    uint32_t slot = 1;
    for (uint32_t i = 0; i < O->cmdCount; i++) {
        const dec_occl_cmd *k = &O->cmds[i];
        uint32_t last = UINT32_MAX, runSlot = 0;
        for (uint32_t r = 0; r < k->drawCount; r++) {
            uint32_t *d = &O->recDec[k->firstRec + r];
            const uint32_t entry = *d;
            if (entry == UINT32_MAX || !k->cullable) {
                *d = 0;
                last = UINT32_MAX;
                continue;
            }
            const dec_occl_sec *e = &O->secs[entry];
            if (e->culled) {
                *d = DEC_OCCL_REC_CULL;
                skippedRecs++;
                continue;
            }
            if (k->klass != DEC_OCCL_CLASS_OPAQUE) {
                *d = 0;
                continue;
            }
            if (!e->query) {
                *d = 0;
                last = UINT32_MAX;
                continue;
            }
            if (entry != last) {
                runSlot = slot++;
                last = entry;
                int32_t *key = &f->keys[3 * runSlot];
                key[0] = e->x; key[1] = e->y; key[2] = e->z;
                f->flags[runSlot] = DEC_OCCL_SLOT_GEOM;
            }
            *d = runSlot + 1u;
        }
    }
    for (uint32_t b = 0; b < boxes; b++) {
        dec_occl_sec *e = &O->secs[O->boxes[b]];
        e->boxSlot = (int32_t)(geomEnd + b);
        int32_t *k = &f->keys[3 * e->boxSlot];
        k[0] = e->x; k[1] = e->y; k[2] = e->z;
        f->flags[e->boxSlot] = DEC_OCCL_SLOT_BOX | (e->inside ? DEC_OCCL_SLOT_INSIDE : 0);
    }
    O->boxCount = boxes;
    f->slots = slots;
    f->geomSlots = geomEnd;
    f->frame = O->frame;
    f->epoch = O->epoch;
    f->value = 0;
    f->state = DEC_OCCL_FS_BUILDING;
    O->building = slotRing;
    if (skippedRecs) {
        /* quads of the skipped records, for the statistics */
        for (uint32_t i = 0; i < O->cmdCount; i++) {
            const dec_occl_cmd *k = &O->cmds[i];
            const uint8_t *args = NULL;
            for (uint32_t r = 0; r < k->drawCount; r++) {
                if (O->recDec[k->firstRec + r] != DEC_OCCL_REC_CULL) continue;
                if (!args) args = dec_occl_resolve(ext, k->args, k->argsOffset,
                                                   k->argsOffset + (uint64_t)MCMTL_INDIRECT_STRIDE_DRAW_INDEXED * k->drawCount, k->offset);
                if (args) skippedQuads += ((const uint32_t *)(args + MCMTL_INDIRECT_STRIDE_DRAW_INDEXED * r))[0] / 6u;
            }
        }
    }

    /* This frame becomes the previous one of the next plan. */
    if (dec_reserve((void **)&O->prevKeys, &O->prevCap, O->curCount, 3 * sizeof(int32_t)) &&
        (O->prevDrawn = realloc(O->prevDrawn, O->prevCap ? O->prevCap : 1)) != NULL) {
        for (uint32_t i = 0; i < O->curCount; i++) {
            dec_occl_sec *e = &O->secs[O->cur[i]];
            e->drawnPrev = !e->culled;
            O->prevKeys[3 * i] = e->x;
            O->prevKeys[3 * i + 1] = e->y;
            O->prevKeys[3 * i + 2] = e->z;
            O->prevDrawn[i] = e->drawnPrev;
        }
        O->prevCount = O->curCount;
    } else {
        O->prevCount = 0;
        dec_occl_reset(O);
    }
    O->prevFrame = O->frame;

    ext->occlActive = YES;
    ds->stats.occlFrames++;
    ds->stats.occlSections += O->curCount;
    ds->stats.occlCulledSections += culled;
    ds->stats.occlWouldCull += would;
    ds->stats.occlSkippedRecords += skippedRecs;
    ds->stats.occlSkippedQuads += skippedQuads;
    ds->stats.occlPlannedQuads += plannedQuads;
    ds->stats.occlGeomQueries += geomSections;
    ds->stats.occlPlanNs += clock_gettime_nsec_np(CLOCK_UPTIME_RAW) - t0;
}

/* One PSO per attachment signature: the box vertex stage, no fragment stage, no colour writes. */
static id<MTLRenderPipelineState> dec_occl_box_pso(mcmtl_decoder_ext *ext) {
    MCMTLDecoderState *ds = ext->ds;
    MCMTLOcclState *O = ds->occl;
    const dec_pass_parse *p = &ext->enc;
    uint32_t key[16] = { DEC_PSO_OCCL_BOX };
    for (uint32_t i = 0; i < MCMTL_MAX_COLOR_ATTACHMENTS; i++) key[1 + i] = (p->presentMask & (1u << i)) ? p->colorFormat[i] : 0;
    key[9] = p->depthInfo.depth ? p->passDepthFormat : 0;
    key[10] = p->stencilFormat;
    MCMTLDecoderPSO *entry = dec_find_pso(ds, key);
    if (entry) return entry->pso;
    NSError *error = nil;
    if (!O->library) {
        O->library = dec_compile(ext->dev, kOcclShaderSource, &error);
        if (!O->library) {
            DEC_MSG(ext, DEC_MSG_INTERNAL, "occlusion box shader compile failed: %s", error ? error.localizedDescription.UTF8String : "?");
            dec_store_pso(ds, key, nil);
            return nil;
        }
    }
    MTLRenderPipelineDescriptor *desc = [MTLRenderPipelineDescriptor new];
    desc.label = @"mcmetal occlusion box";
    desc.vertexFunction = [O->library newFunctionWithName:@"mcmtl_occl_box_vs"];
    for (uint32_t i = 0; i < MCMTL_MAX_COLOR_ATTACHMENTS; i++) {
        desc.colorAttachments[i].pixelFormat = (MTLPixelFormat)key[1 + i];
        desc.colorAttachments[i].writeMask = MTLColorWriteMaskNone;
    }
    desc.depthAttachmentPixelFormat = (MTLPixelFormat)key[9];
    desc.stencilAttachmentPixelFormat = (MTLPixelFormat)key[10];
    id<MTLRenderPipelineState> pso = [ext->dev->device newRenderPipelineStateWithDescriptor:desc error:&error];
    if (!pso) {
        DEC_MSG(ext, DEC_MSG_INTERNAL, "occlusion box PSO failed: %s", error ? error.localizedDescription.UTF8String : "?");
    }
    dec_store_pso(ds, key, pso);
    return pso;
}

/* BEGIN_PASS: the encoder that will hold the opaque terrain gets the frame's visibility buffer. `chainBegins` lists the
 * passes this encoder continues (pass merging). */
static void dec_occl_attach(mcmtl_decoder_ext *ext, MTLRenderPassDescriptor *desc, uint64_t offset) {
    MCMTLOcclState *O = ext->ds->occl;
    if (!ext->occlActive || ext->occlQueried || ext->occlBoxesDone) return;
    BOOL mine = offset == O->terrainPassBegin;
    for (uint32_t i = 0; ext->chainActive && i < ext->chainCount && !mine; i++) mine = ext->chainBegins[i] == O->terrainPassBegin;
    if (!mine || !desc.depthAttachment.texture) return;
    desc.visibilityResultBuffer = O->ringVis[O->building];
    ext->occlVisLive = YES;
}

static void dec_occl_disable_queries(mcmtl_decode_ctx *ctx, mcmtl_decoder_ext *ext) {
    if (!ext->occlCurSlot) return;
    [ctx->renderEncoder setVisibilityResultMode:MTLVisibilityResultModeDisabled offset:0];
    ext->occlCurSlot = 0;
}

/* After the last opaque terrain record (next SET_PIPELINE / END_PASS): one box query per culled or candidate section,
 * against the opaque terrain's depth. */
static void dec_occl_boxes(mcmtl_decode_ctx *ctx, mcmtl_decoder_ext *ext) {
    ext->occlBoxPending = NO;
    ext->occlBoxesDone = YES;
    MCMTLDecoderState *ds = ext->ds;
    MCMTLOcclState *O = ds->occl;
    dec_occl_disable_queries(ctx, ext);
    if (!O->boxCount) return;
    __unsafe_unretained id<MTLRenderCommandEncoder> re = ctx->renderEncoder;
    id<MTLRenderPipelineState> pso = ext->occlVisLive && re && !ext->passEmpty ? dec_occl_box_pso(ext) : nil;
    id<MTLBuffer> ring = nil;
    NSUInteger ringOffset = 0;
    float *origins = pso ? dec_ring_alloc(ext, (NSUInteger)O->boxCount * 16u, 16, &ring, &ringOffset) : NULL;
    if (!origins) {
        ext->occlBroken = YES;
        return;
    }
    for (uint32_t b = 0; b < O->boxCount; b++) {
        const dec_occl_sec *e = &O->secs[O->boxes[b]];
        origins[4 * b] = (float)(e->x - O->camBlock[0]) + O->camOffset[0];
        origins[4 * b + 1] = (float)(e->y - O->camBlock[1]) + O->camOffset[1];
        origins[4 * b + 2] = (float)(e->z - O->camBlock[2]) + O->camOffset[2];
        origins[4 * b + 3] = 0.0f;
    }
    if (pso != ctx->pso) {
        [re setRenderPipelineState:pso];
        ctx->pso = pso;
        ds->stats.setPipelineState++;
    }
    id<MTLDepthStencilState> dss = mcmtl_device_depth_stencil_state(ext->dev, MTLCompareFunctionGreaterEqual, NO);
    if (dss != ctx->depthStencilState) {
        [re setDepthStencilState:dss];
        ctx->depthStencilState = dss;
        ds->stats.setDepthStencilState++;
    }
    if (ctx->cullMode != MTLCullModeBack) {
        [re setCullMode:MTLCullModeBack];
        ctx->cullMode = MTLCullModeBack;
        ds->stats.setRasterState++;
    }
    if (ctx->frontFacing != MTLWindingClockwise) {
        [re setFrontFacingWinding:MTLWindingClockwise];
        ctx->frontFacing = MTLWindingClockwise;
        ds->stats.setRasterState++;
    }
    if (ctx->fillMode != MTLTriangleFillModeFill) {
        [re setTriangleFillMode:MTLTriangleFillModeFill];
        ctx->fillMode = MTLTriangleFillModeFill;
        ds->stats.setRasterState++;
    }
    if (ctx->depthBias[0] != 0.0f || ctx->depthBias[1] != 0.0f || ctx->depthBias[2] != 0.0f) {
        [re setDepthBias:0.0f slopeScale:0.0f clamp:0.0f];
        ctx->depthBias[0] = ctx->depthBias[1] = ctx->depthBias[2] = 0.0f;
        ds->stats.setRasterState++;
    }
    [re setVertexBytes:O->vp length:64 atIndex:16];
    ctx->buffers[0][16] = nil;
    ds->stats.setBytes++;
    dec_bind_buffer(ctx, ds, 0, 17, ring, ringOffset);
    for (uint32_t b = 0; b < O->boxCount; b++) {
        const dec_occl_sec *e = &O->secs[O->boxes[b]];
        [re setVisibilityResultMode:MTLVisibilityResultModeBoolean offset:8u * (NSUInteger)e->boxSlot];
        [re drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:36 instanceCount:1 baseInstance:b];
    }
    [re setVisibilityResultMode:MTLVisibilityResultModeDisabled offset:0];
    ds->stats.draws += O->boxCount;
    ds->stats.occlBoxTests += O->boxCount;
    ext->occlQueried = YES;
}

/* SET_PIPELINE / END_PASS: queries end with the opaque terrain records; the box queries follow the last of them. */
static void dec_occl_before_state_change(mcmtl_decode_ctx *ctx, mcmtl_decoder_ext *ext, BOOL opaqueTerrain) {
    if (ext->occlCurSlot && !opaqueTerrain) dec_occl_disable_queries(ctx, ext);
    if (ext->occlBoxPending && !opaqueTerrain) dec_occl_boxes(ctx, ext);
}

/* The render encoder is about to end. */
static void dec_occl_encoder_end(mcmtl_decoder_ext *ext) {
    if (!ext->occlVisLive) return;
    if (ext->occlBoxPending) ext->occlBroken = YES;   /* boxes due but the encoder ends without them */
    ext->occlVisLive = NO;
    ext->occlCurSlot = 0;
}

/* End of the call: the frame's queries become readable once its timeline value completes; writes of this call stay
 * relevant to later calls until their command buffer has run. */
static void dec_occl_end_call(mcmtl_decoder_ext *ext) {
    MCMTLDecoderState *ds = ext->ds;
    MCMTLOcclState *O = ds->occl;
    if (!O) return;
    const BOOL committed = (ext->result->flags & MCMTL_SUBMIT_RESULT_FLAG_COMMITTED) != 0;
    if (ext->occlActive && O->building != UINT32_MAX) {
        dec_occl_frame *f = &O->ring[O->building];
        const BOOL planned = O->boxCount || f->geomSlots > 1;
        if (committed && !ext->occlBroken && (ext->occlQueried || !planned) && (ext->occlBoxesDone || !O->boxCount)) {
            f->value = ext->signalValue;
            f->state = DEC_OCCL_FS_SUBMITTED;
        } else {
            f->state = DEC_OCCL_FS_FREE;
            if (committed) {
                /* The GPU may still write that buffer: give the entry a fresh one next time. */
                O->ringVis[O->building] = nil;
                f->cap = 0;
            }
            ds->stats.occlResultsDropped++;
        }
        O->building = UINT32_MAX;
    }
    /* Writes of this call (index order) for later calls' resolution; a main stream the index did not cover entirely
     * may have written anything. */
    if (O->wildcardValue == UINT64_MAX && committed) O->wildcardValue = ext->signalValue;
    if (ext->occlMainStream && !ext->occlIndexComplete) O->wildcardValue = committed ? ext->signalValue : UINT64_MAX;
    const uint64_t completed = mcmtl_status_load_u64(&ext->dev->status->completedValue);
    uint32_t w = 0;
    for (uint32_t i = 0; i < O->priorCount; i++) {
        dec_occl_prior p = O->prior[i];
        if (p.value == UINT64_MAX && committed) p.value = ext->signalValue;
        if (p.value != UINT64_MAX && p.value <= completed) continue;
        O->prior[w++] = p;
    }
    O->priorCount = w;
    for (uint32_t i = 0; i < O->writeCount; i++) {
        if (!dec_reserve((void **)&O->prior, &O->priorCap, O->priorCount + 1, sizeof(dec_occl_prior))) break;
        const dec_occl_write *x = &O->writes[i];
        O->prior[O->priorCount++] = (dec_occl_prior){ x->dst, x->lo, x->hi, committed ? ext->signalValue : UINT64_MAX };
    }
    O->writeCount = 0;
    O->cmdCount = 0;
}

/* One pass over the main stream: per pass, the parent textures it samples, the buffers it reads (binding
 * offset onwards) and whether it contains timestamps. Stops at the first record the decode loop would reject
 * structurally (bad size / unknown op / misplaced op) or at an unterminated pass: indexEnd marks where
 * knowledge ends, and every look-ahead treats what lies beyond as unknown (conservative). */
static void dec_index_build(mcmtl_decoder_ext *ext, const uint8_t *s, uint64_t length) {
    MCMTLDecoderState *ds = ext->ds;
    ext->indexed = YES;
    ext->passCount = ext->passCursor = 0;
    ext->sampledCount = ext->readCount = 0;
    ext->pipeCount = 0;
    ext->idxPresentCount = 0;
    ext->idxPresentOffset = 0;
    dec_pass_info *cur = NULL;
    uint64_t lastTexture = 0;
    uint64_t o = 0;
    /* Terrain occlusion: terrain draws and buffer writes of the main stream. */
    MCMTLOcclState *occl = ext->occlIndex ? ds->occl : nil;
    dec_occl_scan occlScan;
    memset(&occlScan, 0, sizeof occlScan);
    if (occl) occl->cmdCount = occl->writeCount = 0;
    while (o + 16 <= length) {
        const mcmtl_cmd_header *h = (const mcmtl_cmd_header *)(s + o);
        const uint32_t op = h->op, size = h->size;
        if (size < 16 || (size & 15) || size > length - o) break;
        if (op >= MCMTL_OP_LIMIT || kMinRecordSize[op] == 0 || size < kMinRecordSize[op]) break;
        if (op == MCMTL_OP_END_PASS || op >= 32) {
            if (!cur) break;
        } else if (op >= 1 && op < 16 && cur) {
            break;
        }
        const uint8_t *rec = s + o;
        BOOL ok = YES;
        switch (op) {
            case MCMTL_OP_BEGIN_PASS: {
                const mcmtl_cmd_begin_pass *c = (const mcmtl_cmd_begin_pass *)rec;
                if (c->colorCount > MCMTL_MAX_COLOR_ATTACHMENTS ||
                    size < MCMTL_CMD_SIZE_BEGIN_PASS_BASE + MCMTL_CMD_SIZE_COLOR_ATTACHMENT * c->colorCount ||
                    !dec_reserve((void **)&ds->idxPasses, &ds->idxPassCap, ext->passCount + 1, sizeof(dec_pass_info))) {
                    ok = NO;
                    break;
                }
                cur = &ds->idxPasses[ext->passCount++];
                memset(cur, 0, sizeof *cur);
                cur->begin = o;
                cur->end = UINT64_MAX;
                cur->sampledStart = ext->sampledCount;
                cur->readStart = ext->readCount;
                cur->pipeStart = ext->pipeCount;
                lastTexture = 0;
                break;
            }
            case MCMTL_OP_END_PASS:
                cur->end = o;
                dec_index_close_pass(ext, cur);
                cur = NULL;
                break;
            case MCMTL_OP_TIMESTAMP:
                if (cur) cur->flags |= DEC_PASS_HAS_TS;
                break;
            case MCMTL_OP_PRESENT:
                ext->idxPresentCount++;
                ext->idxPresentOffset = o;
                break;
            case MCMTL_OP_SET_PIPELINE:
                if (ext->fuseIndex) ok = dec_index_add_pipe(ext, cur, (const void *)(uintptr_t)((const mcmtl_cmd_set_pipeline *)rec)->pipeline);
                break;
            case MCMTL_OP_SET_TEXTURE: {
                const mcmtl_cmd_set_texture *c = (const mcmtl_cmd_set_texture *)rec;
                if (!c->texture || c->texture == lastTexture) break;
                lastTexture = c->texture;
                const dec_tex_res *r = dec_resolve_texture(ext, c->texture);
                ok = r->buffer ? dec_index_add_read(ext, cur, r->buffer, r->bufferOffset) : dec_index_add_sampled(ext, cur, r->root);
                break;
            }
            case MCMTL_OP_SET_BUFFER: {
                const mcmtl_cmd_set_buffer *c = (const mcmtl_cmd_set_buffer *)rec;
                ok = dec_index_add_read(ext, cur, (const void *)(uintptr_t)c->buffer, c->offset);
                break;
            }
            case MCMTL_OP_SET_VERTEX_BUFFER: {
                const mcmtl_cmd_set_vertex_buffer *c = (const mcmtl_cmd_set_vertex_buffer *)rec;
                ok = dec_index_add_read(ext, cur, (const void *)(uintptr_t)c->buffer, c->offset);
                break;
            }
            case MCMTL_OP_SET_INDEX_BUFFER: {
                const mcmtl_cmd_set_index_buffer *c = (const mcmtl_cmd_set_index_buffer *)rec;
                ok = dec_index_add_read(ext, cur, (const void *)(uintptr_t)c->buffer, 0);
                break;
            }
            case MCMTL_OP_DRAW_INDIRECT:
            case MCMTL_OP_DRAW_INDEXED_INDIRECT: {
                const mcmtl_cmd_draw_indirect *c = (const mcmtl_cmd_draw_indirect *)rec;
                ok = dec_index_add_read(ext, cur, (const void *)(uintptr_t)c->buffer, c->offset);
                break;
            }
            default:
                break;
        }
        if (ok && occl) ok = dec_occl_index_record(ext, &occlScan, op, rec, o);
        if (!ok) break;
        o += size;
    }
    ext->occlIndexComplete = !cur && o == length;
    if (cur) {
        /* Unterminated or unindexable pass: nothing from its BEGIN_PASS on is known. */
        o = cur->begin;
        ext->passCount--;
        ext->sampledCount = cur->sampledStart;
        ext->readCount = cur->readStart;
        ext->pipeCount = cur->pipeStart;
    }
    ext->indexEnd = o;
}

/* Index entry of the pass whose BEGIN_PASS is at `offset` (any order). */
static const dec_pass_info *dec_pass_at(mcmtl_decoder_ext *ext, uint64_t offset) {
    const dec_pass_info *passes = ext->ds->idxPasses;
    uint32_t lo = 0, hi = ext->passCount;
    while (lo < hi) {
        uint32_t mid = (lo + hi) / 2;
        if (passes[mid].begin < offset) lo = mid + 1;
        else hi = mid;
    }
    return lo < ext->passCount && passes[lo].begin == offset ? &passes[lo] : NULL;
}

/* Index entry for the BEGIN_PASS being decoded (passes are decoded in order). */
static const dec_pass_info *dec_pass_for_begin(mcmtl_decoder_ext *ext, uint64_t offset) {
    if (!ext->indexed || ext->isInit) return NULL;
    const dec_pass_info *passes = ext->ds->idxPasses;
    while (ext->passCursor < ext->passCount && passes[ext->passCursor].begin < offset) ext->passCursor++;
    return ext->passCursor < ext->passCount && passes[ext->passCursor].begin == offset ? &passes[ext->passCursor] : NULL;
}

static BOOL dec_pass_samples(mcmtl_decoder_ext *ext, const dec_pass_info *pi, const void *texture) {
    const void *const *a = ext->ds->idxSampled + pi->sampledStart;
    uint32_t lo = 0, hi = pi->sampledCount;
    while (lo < hi) {
        uint32_t mid = (lo + hi) / 2;
        if ((uintptr_t)a[mid] < (uintptr_t)texture) lo = mid + 1;
        else hi = mid;
    }
    return lo < pi->sampledCount && a[lo] == texture;
}

/* YES if the pass may read bytes of `buffer` below `hi` (it reads from its lowest binding offset onwards). */
static BOOL dec_pass_reads_below(mcmtl_decoder_ext *ext, const dec_pass_info *pi, const void *buffer, uint64_t hi) {
    const dec_buf_read *a = ext->ds->idxReads + pi->readStart;
    uint32_t lo = 0, n = pi->readCount, top = n;
    while (lo < top) {
        uint32_t mid = (lo + top) / 2;
        if ((uintptr_t)a[mid].buffer < (uintptr_t)buffer) lo = mid + 1;
        else top = mid;
    }
    return lo < n && a[lo].buffer == buffer && a[lo].lo < hi;
}

/* ---------------------------------------------------------------------------------------------- */
/* M2: pending clears (B3)                                                                          */
/* ---------------------------------------------------------------------------------------------- */

static void dec_clear_level(mcmtl_decoder_ext *ext, id<MTLTexture> texture, uint32_t level, uint32_t slice, BOOL clearColor,
                            const float color[4], BOOL clearDepth, double depth, BOOL clearStencil);

static int dec_pending_find(mcmtl_decoder_ext *ext, const void *texture, uint32_t level, uint32_t slice) {
    for (uint32_t i = 0; i < ext->pendingCount; i++) {
        const dec_pending *e = &ext->pending[i];
        if ((__bridge const void *)e->texture == texture && e->level == level && e->slice == slice) return (int)i;
    }
    return -1;
}

static void dec_pending_remove(mcmtl_decoder_ext *ext, uint32_t i) {
    ext->pendingCount--;
    if (i < ext->pendingCount) memmove(&ext->pending[i], &ext->pending[i + 1], (ext->pendingCount - i) * sizeof(dec_pending));
}

/* Encodes pending clear i as the M1 clear pass (any open merged encoder is ended first). */
static void dec_pending_materialize_at(mcmtl_decoder_ext *ext, uint32_t i) {
    dec_pending e = ext->pending[i];
    dec_pending_remove(ext, i);
    if (ext->mergeOpen) dec_merge_break(ext);
    ext->ds->stats.clearsMaterialized++;
    dec_clear_level(ext, e.texture, e.level, e.slice, e.color, e.rgba, e.depth, e.depthValue, e.stencil);
}

static void dec_pending_add(mcmtl_decoder_ext *ext, id<MTLTexture> texture, uint32_t level, uint32_t slice, BOOL color,
                            const float rgba[4], BOOL depth, float depthValue, BOOL stencil) {
    MCMTLDecoderState *ds = ext->ds;
    ds->stats.clearsRecorded++;
    int i = dec_pending_find(ext, (__bridge const void *)texture, level, slice);
    if (i >= 0) {
        /* Nothing used the subresource since the earlier clear (any use materializes it): the later clear
         * overrides the aspects it covers. */
        dec_pending *e = &ext->pending[i];
        if (color) {
            e->color = 1;
            memcpy(e->rgba, rgba, sizeof e->rgba);
        }
        if (depth) {
            e->depth = 1;
            e->depthValue = depthValue;
        }
        if (stencil) e->stencil = 1;
        ds->stats.clearsDropped++;
        return;
    }
    if (ext->pendingCount == DEC_MAX_PENDING) dec_pending_materialize_at(ext, 0);
    dec_pending *e = &ext->pending[ext->pendingCount++];
    memset(e, 0, sizeof *e);
    e->texture = texture;
    e->level = level;
    e->slice = slice;
    e->color = color ? 1 : 0;
    e->depth = depth ? 1 : 0;
    e->stencil = stencil ? 1 : 0;
    e->depthValue = depthValue;
    if (color) memcpy(e->rgba, rgba, sizeof e->rgba);
}

static void dec_pending_materialize_texture(mcmtl_decoder_ext *ext, const void *texture) {
    for (uint32_t i = 0; i < ext->pendingCount;) {
        if ((__bridge const void *)ext->pending[i].texture == texture) dec_pending_materialize_at(ext, i);
        else i++;
    }
}

static void dec_pending_materialize_handle(mcmtl_decoder_ext *ext, uint64_t handle) {
    if (!ext->pendingCount || !handle) return;
    const dec_tex_res *r = dec_resolve_texture(ext, handle);
    if (r->root) dec_pending_materialize_texture(ext, r->root);
}

static void dec_pending_materialize_all(mcmtl_decoder_ext *ext) {
    while (ext->pendingCount) dec_pending_materialize_at(ext, 0);
}

/* Pending clears of textures sampled by any of `count` consecutive indexed passes. They were recorded before
 * the first of them, so encoding them now keeps stream order. */
static void dec_pending_materialize_sampled(mcmtl_decoder_ext *ext, const dec_pass_info *first, uint32_t count) {
    for (uint32_t i = 0; i < ext->pendingCount;) {
        const void *t = (__bridge const void *)ext->pending[i].texture;
        BOOL sampled = NO;
        for (uint32_t k = 0; k < count && !sampled; k++) sampled = dec_pass_samples(ext, first + k, t);
        if (sampled) dec_pending_materialize_at(ext, i);
        else i++;
    }
}

/* ---------------------------------------------------------------------------------------------- */
/* M2: late store actions (B5)                                                                      */
/* ---------------------------------------------------------------------------------------------- */

enum { DEC_ROLE_COLOR = 0, DEC_ROLE_DEPTH = 1 };

/* YES iff the first access of (texture, level) of the given aspect at or after `from` -- encoder-level records
 * and indexed passes of this call's main stream -- overwrites the whole level without reading it: a
 * CLEAR_TEXTURE of that aspect covering the level, or a full-area pass clearing that attachment and not
 * sampling the texture. Anything else, the end of the call, or the end of the index: NO. */
static BOOL g_nao_end;   /* diagnostics (g_mcmtl_diag bit 1): the last scan reached the end of the index without an access */
static BOOL dec_next_access_overwrites(mcmtl_decoder_ext *ext, uint64_t from, const void *texture, uint32_t level, int role) {
    g_nao_end = NO;
    if (!ext->indexed || ext->isInit) return NO;
    const uint8_t *s = ext->stream;
    const uint64_t end = ext->indexEnd;
    uint64_t o = from;
    while (o + 16 <= end) {
        const mcmtl_cmd_header *h = (const mcmtl_cmd_header *)(s + o);
        const uint8_t *rec = s + o;
        switch (h->op) {
            case MCMTL_OP_NOP:
            case MCMTL_OP_TIMESTAMP:
            case MCMTL_OP_PUSH_DEBUG_GROUP:
            case MCMTL_OP_POP_DEBUG_GROUP:
            case MCMTL_OP_COPY_BUFFER:
                break;
            case MCMTL_OP_BEGIN_PASS: {
                const dec_pass_info *pi = dec_pass_at(ext, o);
                if (!pi || pi->end == UINT64_MAX || dec_pass_samples(ext, pi, texture)) return NO;
                const mcmtl_cmd_begin_pass *c = (const mcmtl_cmd_begin_pass *)rec;
                const mcmtl_cmd_color_attachment *atts = (const mcmtl_cmd_color_attachment *)(rec + MCMTL_CMD_SIZE_BEGIN_PASS_BASE);
                BOOL hit = NO, clears = NO;
                if (role == DEC_ROLE_COLOR) {
                    for (uint32_t i = 0; i < c->colorCount && !hit; i++) {
                        if ((const void *)(uintptr_t)atts[i].texture == texture && atts[i].level == level) {
                            hit = YES;
                            clears = (c->colorClearMask >> i) & 1u;
                        }
                    }
                } else if ((const void *)(uintptr_t)c->depthTexture == texture && c->depthLevel == level) {
                    hit = YES;
                    clears = (c->flags & MCMTL_PASS_FLAG_DEPTH_CLEAR) != 0;
                }
                if (hit) {
                    dec_pass_parse p;
                    uint32_t bad = 0;
                    return clears && dec_parse_begin_pass(rec, h->size, &p, &bad) == DEC_PARSE_OK && p.full;
                }
                o = pi->end;   /* continue after its END_PASS */
                h = (const mcmtl_cmd_header *)(s + o);
                break;
            }
            case MCMTL_OP_CLEAR_TEXTURE: {
                const mcmtl_cmd_clear_texture *c = (const mcmtl_cmd_clear_texture *)rec;
                if ((const void *)(uintptr_t)c->texture != texture) break;
                const uint32_t aspect = role == DEC_ROLE_COLOR ? MCMTL_ASPECT_COLOR : MCMTL_ASPECT_DEPTH;
                if (c->slice == 0 && (c->aspects & aspect) && level >= c->baseLevel && level - c->baseLevel < c->levelCount) return YES;
                break;   /* other levels / aspects: this one is untouched */
            }
            case MCMTL_OP_CLEAR_REGION: {
                const mcmtl_cmd_clear_region *c = (const mcmtl_cmd_clear_region *)rec;
                if ((const void *)(uintptr_t)c->colorTexture == texture || (const void *)(uintptr_t)c->depthTexture == texture) return NO;
                break;
            }
            case MCMTL_OP_COPY_BUFFER_TO_TEXTURE:
            case MCMTL_OP_COPY_TEXTURE_TO_BUFFER: {
                const mcmtl_cmd_copy_buffer_texture *c = (const mcmtl_cmd_copy_buffer_texture *)rec;
                if (!c->texture || dec_resolve_texture(ext, c->texture)->root == texture) return NO;
                break;
            }
            case MCMTL_OP_COPY_TEXTURE: {
                const mcmtl_cmd_copy_texture *c = (const mcmtl_cmd_copy_texture *)rec;
                if (!c->srcTexture || !c->dstTexture || dec_resolve_texture(ext, c->srcTexture)->root == texture ||
                    dec_resolve_texture(ext, c->dstTexture)->root == texture) {
                    return NO;
                }
                break;
            }
            case MCMTL_OP_PRESENT: {
                const mcmtl_cmd_present *c = (const mcmtl_cmd_present *)rec;
                if (!c->texture || dec_resolve_texture(ext, c->texture)->root == texture) return NO;
                break;
            }
            case MCMTL_OP_INIT_TEXTURE: {
                const mcmtl_cmd_init_texture *c = (const mcmtl_cmd_init_texture *)rec;
                if (!c->texture || dec_resolve_texture(ext, c->texture)->root == texture) return NO;
                break;
            }
            default:
                return NO;   /* UPSCALE, or anything unexpected */
        }
        o += h->size;
    }
    g_nao_end = YES;
    return NO;
}

/* Round 9 diagnostics (CONTRACT 16.R9): runtime bits set from Java (MetalDiag, abab-switchable) through
 * mcmtl_ext_set_diag. 0 = shipped behaviour. Bit 1: the main target's colour store of a frame that will not be presented
 * (mailbox: no drawable at the fusion pass) is DontCare; bit 2: depth stores at the end of the call are DontCare;
 * bit 4: in-encoder depth clear draws are skipped (changes the image). */
volatile uint32_t g_mcmtl_diag = 0;
MCMTL_API void mcmtl_ext_set_diag(uint32_t flags) { g_mcmtl_diag = flags; }

/* Resolves the Unknown store actions of the open pass encoder (lookFrom = UINT64_MAX: Store everything). */
static void dec_resolve_stores(mcmtl_decoder_ext *ext, id<MTLRenderCommandEncoder> encoder, uint64_t lookFrom) {
    MCMTLDecoderState *ds = ext->ds;
    const dec_pass_parse *e = &ext->enc;
    for (uint32_t i = 0; i < MCMTL_MAX_COLOR_ATTACHMENTS; i++) {
        if (!(e->presentMask & (1u << i))) continue;
        /* A fused color 0 is the drawable: always stored (it is what PRESENT shows / the next fused pass loads). */
        BOOL dontCare = lookFrom != UINT64_MAX && !(i == 0 && ext->encFused) &&
                        dec_next_access_overwrites(ext, lookFrom, (__bridge const void *)e->color[i], e->level[i], DEC_ROLE_COLOR);
        if (!dontCare && (g_mcmtl_diag & 1u) && i == 0 && !ext->encFused && ext->fuseState == DEC_FUSE_SKIP &&
            dec_root_texture(e->color[0]) == (__bridge const void *)ext->fuseTex && e->level[0] == ext->fuseLevel &&
            (lookFrom == UINT64_MAX || g_nao_end)) {
            dontCare = YES;
        }
        [encoder setColorStoreAction:dontCare ? MTLStoreActionDontCare : MTLStoreActionStore atIndex:i];
        if (dontCare) ds->stats.dontCareStores++;
        else ds->stats.lateStores++;
    }
    if (e->depth && e->depthInfo.depth) {
        BOOL dontCare = lookFrom != UINT64_MAX &&
                        dec_next_access_overwrites(ext, lookFrom, (__bridge const void *)e->depth, e->depthLevel, DEC_ROLE_DEPTH);
        if (!dontCare && lookFrom == UINT64_MAX && (g_mcmtl_diag & 2u)) dontCare = YES;
        [encoder setDepthStoreAction:dontCare ? MTLStoreActionDontCare : MTLStoreActionStore];
        if (dontCare) ds->stats.dontCareStores++;
        else ds->stats.lateStores++;
    }
    if (e->depth && e->depthInfo.stencil) {
        [encoder setStencilStoreAction:MTLStoreActionStore];
        ds->stats.lateStores++;
    }
}

/* ---------------------------------------------------------------------------------------------- */
/* M2: merge planning (B4) and upload hoisting (B6)                                                 */
/* ---------------------------------------------------------------------------------------------- */

/* Was the copy at `offset` already encoded early by a chain planned earlier in this call? */
static BOOL dec_hoisted_contains(mcmtl_decoder_ext *ext, uint64_t offset) {
    const uint64_t *a = ext->ds->hoisted;
    uint32_t lo = 0, hi = ext->hoistCount;
    while (lo < hi) {
        uint32_t mid = (lo + hi) / 2;
        if (a[mid] < offset) lo = mid + 1;
        else hi = mid;
    }
    return lo < ext->hoistCount && a[lo] == offset;
}

/* Records being planned for hoisting go after the committed ones: ds->hoisted[hoistCount + planned]. */
static BOOL dec_plan_hoist(mcmtl_decoder_ext *ext, uint32_t *planned, uint64_t offset) {
    MCMTLDecoderState *ds = ext->ds;
    if (!dec_reserve((void **)&ds->hoisted, &ds->hoistedCap, ext->hoistCount + *planned + 1, sizeof(uint64_t))) return NO;
    ds->hoisted[ext->hoistCount + (*planned)++] = offset;
    return YES;
}

/* The depth-clear draw PSO for `value` on the chain's attachments (nil if unavailable). */
static BOOL dec_clear_draw_vertex_only(mcmtl_decoder_ext *ext, float value) {
    return (ext->m2 & DEC_M2_ZERO_CLEAR_VERTEX) && value == 0.0f && !signbit(value);
}

static id<MTLRenderPipelineState> dec_clear_draw_pso(mcmtl_decoder_ext *ext, const dec_pass_parse *p, float value) {
    return dec_depth_clear_pso(ext->dev, ext->ds, p->colorFormat, p->quadDepthFormat, dec_clear_draw_vertex_only(ext, value),
                               &ext->messaged);
}

/* Planner state of one chain. */
typedef struct dec_plan {
    const dec_pass_parse *p0;            /* attachments of the chain                                     */
    uint32_t first;                      /* index entry of the first pass                                */
    uint32_t passes;                     /* chain passes so far (first .. first + passes - 1)            */
    uint32_t planned;                    /* hoists planned so far                                        */
    BOOL     depthOnly;                  /* the depth attachment can take a clear draw                   */
    BOOL     gapClear;                   /* a planned depth clear in the current gap                     */
    float    gapClearValue;
    uint32_t gapPendingCount;
    const void *gapPending[DEC_MAX_GAP_PENDING]; /* other textures cleared between chain passes          */
} dec_plan;

/* CLEAR_TEXTURE between two chain passes: a depth clear of the depth attachment becomes a draw at the next
 * pass; clears of other textures stay pending through the chain (FOLD); clears of a color attachment end the
 * chain. */
static BOOL dec_plan_gap_clear(mcmtl_decoder_ext *ext, dec_plan *pl, const mcmtl_cmd_clear_texture *c, uint64_t offset) {
    const uint32_t m2 = ext->m2;
    if (!c->texture) return NO;
    const void *t = (const void *)(uintptr_t)c->texture;
    const dec_pass_parse *p0 = pl->p0;
    for (uint32_t i = 0; i < MCMTL_MAX_COLOR_ATTACHMENTS; i++) {
        if ((p0->presentMask & (1u << i)) && (__bridge const void *)p0->color[i] == t) return NO;
    }
    if (p0->depth && (__bridge const void *)p0->depth == t) {
        if (!(m2 & DEC_M2_CLEAR_DRAW) || !pl->depthOnly || !(c->aspects & MCMTL_ASPECT_DEPTH) || c->slice != 0) return NO;
        const uint32_t L = p0->depthLevel;
        const uint64_t mips = p0->depth.mipmapLevelCount;
        const uint64_t lo = c->baseLevel;
        uint64_t hi = lo + c->levelCount;
        if (hi > mips) hi = mips;
        const BOOL covers = L >= lo && L < hi;
        const BOOL others = hi > lo && hi - lo > (covers ? 1u : 0u);
        if (others && !(m2 & DEC_M2_FOLD)) return NO;   /* other levels would need a clear pass now */
        if (covers) {
            if (!(c->depth >= 0.0f && c->depth <= 1.0f) || ext->clearDrawCount >= DEC_MAX_CHAIN) return NO;
            ext->clearDrawOffsets[ext->clearDrawCount++] = offset;
            pl->gapClear = YES;
            pl->gapClearValue = c->depth;
        }
        return YES;
    }
    if (!(m2 & DEC_M2_FOLD)) return NO;
    for (uint32_t g = 0; g < pl->gapPendingCount; g++) {
        if (pl->gapPending[g] == t) return YES;
    }
    if (pl->gapPendingCount >= DEC_MAX_GAP_PENDING) return NO;
    pl->gapPending[pl->gapPendingCount++] = t;
    return YES;
}

/* COPY_BUFFER between chain passes: hoistable iff no chain pass so far reads the destination range. */
static BOOL dec_plan_copy(mcmtl_decoder_ext *ext, dec_plan *pl, const mcmtl_cmd_copy_buffer *c, uint64_t offset) {
    if (!(ext->m2 & DEC_M2_HOIST)) return NO;
    if (dec_hoisted_contains(ext, offset)) return YES;   /* already encoded by an earlier (broken) chain */
    if (!c->srcBuffer || !c->dstBuffer) return NO;
    if (c->length) {
        const void *dst = (const void *)(uintptr_t)c->dstBuffer;
        uint64_t hi = c->dstOffset + c->length;
        if (hi < c->dstOffset) hi = UINT64_MAX;
        for (uint32_t k = 0; k < pl->passes; k++) {
            if (dec_pass_reads_below(ext, &ext->ds->idxPasses[pl->first + k], dst, hi)) return NO;
        }
    }
    return dec_plan_hoist(ext, &pl->planned, offset);
}

/* COPY_BUFFER_TO_TEXTURE between chain passes: hoistable iff the destination texture is not an attachment,
 * not sampled by a chain pass so far, and has no pending clear (recorded before the chain or in its gaps). */
static BOOL dec_plan_upload(mcmtl_decoder_ext *ext, dec_plan *pl, const mcmtl_cmd_copy_buffer_texture *c, uint64_t offset) {
    if (!(ext->m2 & DEC_M2_HOIST)) return NO;
    if (dec_hoisted_contains(ext, offset)) return YES;
    if (!c->buffer || !c->texture) return NO;
    const void *root = dec_resolve_texture(ext, c->texture)->root;
    if (!root || dec_is_attachment_texture(pl->p0, root)) return NO;
    for (uint32_t k = 0; k < pl->passes; k++) {
        if (dec_pass_samples(ext, &ext->ds->idxPasses[pl->first + k], root)) return NO;
    }
    for (uint32_t i = 0; i < ext->pendingCount; i++) {
        if ((__bridge const void *)ext->pending[i].texture == root) return NO;
    }
    for (uint32_t g = 0; g < pl->gapPendingCount; g++) {
        if (pl->gapPending[g] == root) return NO;
    }
    return dec_plan_hoist(ext, &pl->planned, offset);
}

/* Can the indexed pass `pi` (BEGIN_PASS at its begin offset) continue the chain's encoder? */
static BOOL dec_plan_accepts(mcmtl_decoder_ext *ext, dec_plan *pl, const dec_pass_info *pi) {
    const uint8_t *rec = ext->stream + pi->begin;
    dec_pass_parse p;
    uint32_t bad = 0;
    if (pi->end == UINT64_MAX || (pi->flags & DEC_PASS_HAS_TS)) return NO;
    if (dec_parse_begin_pass(rec, ((const mcmtl_cmd_header *)rec)->size, &p, &bad) != DEC_PARSE_OK) return NO;
    if (!dec_same_attachments(pl->p0, &p) || !p.full || p.colorClear) return NO;
    if (p.depthClear || pl->gapClear) {
        const float value = p.depthClear ? p.depthClearValue : pl->gapClearValue;
        if (!(ext->m2 & DEC_M2_CLEAR_DRAW) || !pl->depthOnly || !(value >= 0.0f && value <= 1.0f)) return NO;
        if (!dec_clear_draw_pso(ext, pl->p0, value)) return NO;
    }
    /* The pass must not read what the chain renders (tile contents are not visible to sampling) nor a texture
     * cleared in a gap (that clear would need a clear pass before it). */
    for (uint32_t i = 0; i < MCMTL_MAX_COLOR_ATTACHMENTS; i++) {
        if ((pl->p0->presentMask & (1u << i)) && dec_pass_samples(ext, pi, (__bridge const void *)pl->p0->color[i])) return NO;
    }
    if (pl->p0->depth && dec_pass_samples(ext, pi, (__bridge const void *)pl->p0->depth)) return NO;
    for (uint32_t g = 0; g < pl->gapPendingCount; g++) {
        if (dec_pass_samples(ext, pi, pl->gapPending[g])) return NO;
    }
    return YES;
}

/* Plans the chain of passes that share the encoder of the pass beginning at pi0: ext->chainBegins/chainEnd,
 * the gap depth clears drawn in the encoder (ext->clearDrawOffsets) and the copies hoisted before it
 * (ds->hoisted[hoistCount .. hoistCount + *outPlanned), stream order). */
static void dec_plan_chain(mcmtl_decoder_ext *ext, const dec_pass_info *pi0, const dec_pass_parse *p0, uint32_t *outPlanned) {
    MCMTLDecoderState *ds = ext->ds;
    ext->chainActive = NO;
    ext->chainCount = ext->chainNext = 0;
    ext->clearDrawCount = ext->clearDrawNext = 0;
    ext->chainEnd = pi0->end;
    *outPlanned = 0;
    if (!(ext->m2 & DEC_M2_MERGE) || pi0->end == UINT64_MAX || (pi0->flags & DEC_PASS_HAS_TS)) return;
    dec_plan pl;
    memset(&pl, 0, sizeof pl);
    pl.p0 = p0;
    pl.first = (uint32_t)(pi0 - ds->idxPasses);
    pl.passes = 1;
    pl.depthOnly = p0->depth && p0->depthInfo.depth && !p0->depthInfo.stencil;
    const uint8_t *s = ext->stream;
    const uint64_t end = ext->indexEnd;
    uint64_t o = pi0->end + ((const mcmtl_cmd_header *)(s + pi0->end))->size;
    while (ext->chainCount + 1 < DEC_MAX_CHAIN) {
        const uint32_t plannedMark = pl.planned, clearMark = ext->clearDrawCount;
        pl.gapClear = NO;
        BOOL ok = YES;
        uint64_t next = UINT64_MAX;
        while (ok && o + 16 <= end) {
            const mcmtl_cmd_header *h = (const mcmtl_cmd_header *)(s + o);
            const uint8_t *rec = s + o;
            switch (h->op) {
                case MCMTL_OP_NOP:
                case MCMTL_OP_PUSH_DEBUG_GROUP:
                case MCMTL_OP_POP_DEBUG_GROUP:
                    break;
                case MCMTL_OP_BEGIN_PASS:
                    next = o;
                    break;
                case MCMTL_OP_CLEAR_TEXTURE:
                    ok = dec_plan_gap_clear(ext, &pl, (const mcmtl_cmd_clear_texture *)rec, o);
                    break;
                case MCMTL_OP_COPY_BUFFER:
                    ok = dec_plan_copy(ext, &pl, (const mcmtl_cmd_copy_buffer *)rec, o);
                    break;
                case MCMTL_OP_COPY_BUFFER_TO_TEXTURE:
                    ok = dec_plan_upload(ext, &pl, (const mcmtl_cmd_copy_buffer_texture *)rec, o);
                    break;
                default:
                    ok = NO;   /* timestamps, other copies, present, region clears, ...: the chain ends */
                    break;
            }
            if (!ok || next != UINT64_MAX) break;
            o += h->size;
        }
        const uint32_t index = pl.first + pl.passes;
        const dec_pass_info *pi = index < ext->passCount ? &ds->idxPasses[index] : NULL;
        if (!ok || next == UINT64_MAX || !pi || pi->begin != next || !dec_plan_accepts(ext, &pl, pi)) {
            pl.planned = plannedMark;   /* the incomplete gap's hoists and clear draws are dropped */
            ext->clearDrawCount = clearMark;
            break;
        }
        ext->chainBegins[ext->chainCount++] = next;
        ext->chainEnd = pi->end;
        pl.passes++;
        o = pi->end + ((const mcmtl_cmd_header *)(s + pi->end))->size;
    }
    ext->chainActive = ext->chainCount > 0;
    if (!ext->chainActive) {
        ext->chainEnd = pi0->end;
        ext->clearDrawCount = 0;
        pl.planned = 0;
    }
    *outPlanned = pl.planned;
}

static int32_t op_copy_buffer(mcmtl_decoder_ext *ext, const uint8_t *rec, uint64_t offset);
static int32_t op_copy_buffer_to_texture(mcmtl_decoder_ext *ext, const uint8_t *rec, uint64_t offset);

/* Encodes the planned hoists (blit encoder, before the chain's render encoder) and commits them to the
 * sorted list of copies the decode loop skips. */
static int32_t dec_run_hoists(mcmtl_decoder_ext *ext, uint32_t planned) {
    MCMTLDecoderState *ds = ext->ds;
    const uint32_t base = ext->hoistCount;
    int32_t rc = MCMTL_OK;
    uint32_t done = 0;
    if (ds->copyStats) ds->copyStats->hoisting = YES;
    for (; done < planned && rc == MCMTL_OK; done++) {
        const uint64_t o = ds->hoisted[base + done];
        const uint8_t *rec = ext->stream + o;
        rc = ((const mcmtl_cmd_header *)rec)->op == MCMTL_OP_COPY_BUFFER ? op_copy_buffer(ext, rec, o)
                                                                          : op_copy_buffer_to_texture(ext, rec, o);
        ds->stats.hoists++;
    }
    if (ds->copyStats) ds->copyStats->hoisting = NO;
    for (uint32_t i = base; i < base + done; i++) {
        const uint64_t v = ds->hoisted[i];
        uint32_t j = i;
        while (j > 0 && ds->hoisted[j - 1] > v) {
            ds->hoisted[j] = ds->hoisted[j - 1];
            j--;
        }
        ds->hoisted[j] = v;
    }
    ext->hoistCount = base + done;
    return rc;
}

/* The decode loop reached `offset`: YES if it is a copy that was already encoded early. */
static BOOL dec_take_hoisted(mcmtl_decoder_ext *ext, uint64_t offset) {
    const uint64_t *a = ext->ds->hoisted;
    while (ext->hoistNext < ext->hoistCount && a[ext->hoistNext] < offset) ext->hoistNext++;
    if (ext->hoistNext < ext->hoistCount && a[ext->hoistNext] == offset) {
        ext->hoistNext++;
        return YES;
    }
    return NO;
}

/* ---------------------------------------------------------------------------------------------- */
/* Present fusion (CONTRACT.md 16.3)                                                                */
/* ---------------------------------------------------------------------------------------------- */

/* MCMTL_FUSION_TRACE=1 (diagnostics): logs restores and stale decisions, and follows the command buffers of the
 * calls right after a restore (scheduled / completed, status, GPU times). */
static int dec_fuse_trace(void) {
    static int on = -1;
    if (on < 0) {
        const char *v = getenv("MCMTL_FUSION_TRACE");
        on = v && atoi(v) != 0 ? 1 : 0;
    }
    return on;
}

static void dec_fuse_trace_cb(id<MTLCommandBuffer> cb, const char *what, uint64_t value) {
    const CFTimeInterval t0 = CACurrentMediaTime();
    const char *w = what;
    [cb addScheduledHandler:^(id<MTLCommandBuffer> b) {
        (void)b;
        NSLog(@"[fusetrace] %s cb (value %llu) scheduled +%.3f ms", w, (unsigned long long)value, (CACurrentMediaTime() - t0) * 1e3);
    }];
    [cb addCompletedHandler:^(id<MTLCommandBuffer> b) {
        NSLog(@"[fusetrace] %s cb (value %llu) completed +%.3f ms status %ld gpu %.3f ms error %@", w, (unsigned long long)value,
              (CACurrentMediaTime() - t0) * 1e3, (long)b.status, (b.GPUEndTime - b.GPUStartTime) * 1e3, b.error);
    }];
    NSLog(@"[fusetrace] %s cb (value %llu) committing", what, (unsigned long long)value);
}

/* Commits the work encoded so far without a timeline signal (the value-signalling command buffer is still the last
 * one of the call) and continues in a fresh command buffer; keepGroups re-pushes the open command-buffer debug
 * groups there (labels on). No encoder may be open. */
static void dec_commit_early(mcmtl_decoder_ext *ext, BOOL keepGroups) {
    if (!ext->commit || !ext->commandBuffer || !ext->cbHasWork) return;
    dec_end_blit(ext);
    id<MTLCommandBuffer> cb = ext->commandBuffer;
    while (ext->cbGroupDepth) {
        [cb popDebugGroup];
        ext->cbGroupDepth--;
    }
    if (!keepGroups) [ext->ds->labelStack removeAllObjects];
    dec_ring_attach(ext->ds, cb);
    ext->commandBuffer = nil;
    ext->ctx->commandBuffer = nil;
    ext->dev->currentCommandBuffer = nil;
    ext->cbHasWork = NO;
    if (ext->ds->fuseTraceCalls) dec_fuse_trace_cb(cb, "early", ext->signalValue);
    mcmtl_commit_untimed(ext->dev, cb, ext->signalValue);
    ext->ds->stats.commandBuffersCommitted++;
    ext->ds->stats.untimedCommits++;
    if (keepGroups && ext->ds->labelStack.count) {
        id<MTLCommandBuffer> next = dec_cb(ext);
        for (NSString *label in ext->ds->labelStack) [next pushDebugGroup:label];
        ext->cbGroupDepth = (uint32_t)ext->ds->labelStack.count;
    }
}

/* BGRA8 variants compiled since the last call become pipeline variants (render thread; the pipeline cache is not
 * thread-safe). Failures are remembered like any failed variant (frames using that pipeline are never fused). */
static void dec_fuse_drain(MCMTLDevice *dev, MCMTLDecoderState *ds) {
    MCMTLFuseMailbox *mb = ds->fuseMailbox;
    if (!mb) return;
    NSArray<MCMTLFuseVariant *> *done = nil;
    os_unfair_lock_lock(&mb->lock);
    if (mb->done.count) {
        done = [mb->done copy];
        [mb->done removeAllObjects];
    }
    os_unfair_lock_unlock(&mb->lock);
    for (MCMTLFuseVariant *v in done) {
        NSNumber *key = @(v->key);
        [[ds->fusePending objectForKey:v->pipeline] removeObject:key];
        if (v->pso) {
            if (!mcmtl_pipeline_find_variant(v->pipeline, v->key)) mcmtl_pipeline_add_variant(v->pipeline, v->key, v->pso);
            continue;
        }
        NSMutableSet<NSNumber *> *failed = [ds->failedVariants objectForKey:v->pipeline];
        if (!failed) {
            failed = [NSMutableSet set];
            [ds->failedVariants setObject:failed forKey:v->pipeline];
        }
        [failed addObject:key];
        if (v->key & MCMTL_VARIANT_KEY_ICB) {
            dec_message(dev, ds, DEC_MSG_VARIANT, NULL, "terrain ICB: ICB variant of pipeline '%s' failed (%s); its indirect draws stay per record",
                        v->pipeline->label ? v->pipeline->label.UTF8String : "?", v->failure ? v->failure.UTF8String : "?");
        } else {
            dec_message(dev, ds, DEC_MSG_VARIANT, NULL, "present fusion: BGRA8 variant of pipeline '%s' failed (%s); frames using it are not fused",
                        v->pipeline->label ? v->pipeline->label.UTF8String : "?", v->failure ? v->failure.UTF8String : "?");
        }
    }
}

/* YES if the variant exists; otherwise starts its compile once (asynchronously, or synchronously under the test flag)
 * and returns whether it is available now. */
static BOOL dec_fuse_variant(mcmtl_decoder_ext *ext, MCMTLPipeline *p, uint64_t key) {
    if (mcmtl_pipeline_find_variant(p, key)) return YES;
    MCMTLDecoderState *ds = ext->ds;
    NSNumber *keyNumber = @(key);
    NSMutableSet<NSNumber *> *failed = [ds->failedVariants objectForKey:p];
    if (failed && [failed containsObject:keyNumber]) return NO;
    NSMutableSet<NSNumber *> *pending = [ds->fusePending objectForKey:p];
    if (pending && [pending containsObject:keyNumber]) return NO;
    NSString *failure = nil;
    MTLRenderPipelineDescriptor *desc = mcmtl_pipeline_variant_descriptor(p, key, &failure);
    MCMTLFuseMailbox *mb = ds->fuseMailbox;
    ds->stats.fusionVariantBuilds++;
    if (desc && (ds->fuseTestFlags & DEC_FUSE_TEST_SYNC_VARIANTS)) {
        NSError *error = nil;
        id<MTLRenderPipelineState> pso = [ext->dev->device newRenderPipelineStateWithDescriptor:desc error:&error];
        if (pso) {
            mcmtl_pipeline_add_variant(p, key, pso);
            return YES;
        }
        failure = error ? error.localizedDescription : @"(no NSError)";
        desc = nil;
    }
    if (!pending) {
        pending = [NSMutableSet set];
        [ds->fusePending setObject:pending forKey:p];
    }
    [pending addObject:keyNumber];
    if (!desc) {
        MCMTLFuseVariant *v = [MCMTLFuseVariant new];
        v->pipeline = p;
        v->key = key;
        v->failure = failure;
        os_unfair_lock_lock(&mb->lock);
        [mb->done addObject:v];
        os_unfair_lock_unlock(&mb->lock);
        return NO;
    }
    MCMTLPipeline *pipeline = p;
    [ext->dev->device newRenderPipelineStateWithDescriptor:desc
                                        completionHandler:^(id<MTLRenderPipelineState> pso, NSError *error) {
        MCMTLFuseVariant *v = [MCMTLFuseVariant new];
        v->pipeline = pipeline;
        v->key = key;
        v->pso = pso;
        v->failure = pso ? nil : (error ? error.localizedDescription : @"(no NSError)");
        os_unfair_lock_lock(&mb->lock);
        [mb->done addObject:v];
        os_unfair_lock_unlock(&mb->lock);
    }];
    return NO;
}

enum { DEC_FUSE_USE_NONE = 0, DEC_FUSE_USE_COLOR0, DEC_FUSE_USE_OTHER };

/* How a parsed pass attaches S: only as color 0 at level L (DEC_FUSE_USE_COLOR0), any other way (other slot, other
 * level, depth: DEC_FUSE_USE_OTHER), or not at all. Sampling is checked separately (index). */
static int dec_fuse_pass_use(mcmtl_decoder_ext *ext, const uint8_t *rec, const dec_pass_parse *p, const void *S, uint32_t L) {
    const mcmtl_cmd_begin_pass *c = (const mcmtl_cmd_begin_pass *)rec;
    const mcmtl_cmd_color_attachment *atts = (const mcmtl_cmd_color_attachment *)(rec + MCMTL_CMD_SIZE_BEGIN_PASS_BASE);
    int use = DEC_FUSE_USE_NONE;
    for (uint32_t i = 0; i < p->n; i++) {
        if (!atts[i].texture || dec_resolve_texture(ext, atts[i].texture)->root != S) continue;
        if (i != 0 || atts[i].level != L) return DEC_FUSE_USE_OTHER;
        use = DEC_FUSE_USE_COLOR0;
    }
    if (c->depthTexture && dec_resolve_texture(ext, c->depthTexture)->root == S) return DEC_FUSE_USE_OTHER;
    return use;
}

/* Are the BGRA8 variants of every pipeline set in the passes that will render into the drawable available (passes
 * candIdx.. before PRESENT that attach S@L as color 0)? Starts the missing compiles. */
static BOOL dec_fuse_variants_ready(mcmtl_decoder_ext *ext, uint32_t candIdx, uint64_t presentOffset, const void *S, uint32_t L) {
    MCMTLDecoderState *ds = ext->ds;
    BOOL ready = YES;
    for (uint32_t i = candIdx; i < ext->passCount && ds->idxPasses[i].begin < presentOffset; i++) {
        const dec_pass_info *pi = &ds->idxPasses[i];
        const uint8_t *rec = ext->stream + pi->begin;
        dec_pass_parse p;
        uint32_t bad = 0;
        if (dec_parse_begin_pass(rec, ((const mcmtl_cmd_header *)rec)->size, &p, &bad) != DEC_PARSE_OK) return NO;
        if (dec_fuse_pass_use(ext, rec, &p, S, L) != DEC_FUSE_USE_COLOR0) continue;
        const uint64_t key = mcmtl_variant_key(p.presentMask, p.passDepthFormat) | MCMTL_VARIANT_KEY_BGRA8_COLOR0;
        for (uint32_t k = 0; k < pi->pipeCount; k++) {
            MCMTLPipeline *pipe = (__bridge MCMTLPipeline *)ds->idxPipes[pi->pipeStart + k];
            if (!dec_fuse_variant(ext, pipe, key)) ready = NO;   /* keep going: start every missing compile now */
        }
    }
    return ready;
}

/* Plans present fusion for the main stream (after the index): sets ext->fuseState to PLANNED (fuseCand ...) or
 * FAILED (fuseReason). Leaves NONE when no PRESENT was indexed. Rules (CONTRACT.md 16.3):
 *  - exactly one PRESENT; its layer flipped, opaque, BGRA8Unorm, drawableSize == the source level size; the source S
 *    a single-sample 2D RGBA8/BGRA8Unorm texture presented at level L;
 *  - the candidate is the last pass before PRESENT that attaches S@L as color 0 over its full area and clears it
 *    (its own clear, or a CLEAR_TEXTURE of S@L since the previous use of S that FOLD turns into its load action):
 *    then nothing the frame did to S@L before is visible, so the drawable's previous content is irrelevant;
 *  - from the candidate up to PRESENT, S is only attached as color 0 at L (never sampled, copied, region-cleared,
 *    attached elsewhere, re-cleared), and nothing references S after PRESENT;
 *  - every pipeline those passes set has its BGRA8 variant compiled. */
static void dec_fuse_plan(mcmtl_decoder_ext *ext) {
    MCMTLDecoderState *ds = ext->ds;
    ext->fuseState = DEC_FUSE_NONE;
    ext->fuseDrawable = nil;
    if (!ext->indexed || ext->idxPresentCount == 0) return;
#define DEC_FUSE_FAIL(r) do { ext->fuseState = DEC_FUSE_FAILED; ext->fuseReason = (r); return; } while (0)
    if (ds->fuseMode != 1) DEC_FUSE_FAIL(DEC_FUSE_R_DISABLED);
    /* A fused frame's command buffers must be committed (and its timeline value known) before the next call can
     * restore from the drawable; Java's PRESENT always comes with COMMIT. */
    if (!ext->commit || !ext->signalValue) DEC_FUSE_FAIL(DEC_FUSE_R_NO_INDEX);
    if (ext->idxPresentCount > 1) DEC_FUSE_FAIL(DEC_FUSE_R_MULTI_PRESENT);
    const uint64_t P = ext->idxPresentOffset;
    const mcmtl_cmd_present *pr = (const mcmtl_cmd_present *)(ext->stream + P);
    if (!pr->surface || !pr->texture) DEC_FUSE_FAIL(DEC_FUSE_R_NO_INDEX);   /* op_present reports the bad record */
    MCMTLSurface *surface = MCMTL_SURFACE(pr->surface);
    CAMetalLayer *layer = surface->layer;
    if (!layer || !mcmtl_layer_is_flipped(layer)) DEC_FUSE_FAIL(DEC_FUSE_R_NOT_FLIPPED);
    const void *S = dec_resolve_texture(ext, pr->texture)->root;
    if (!S) DEC_FUSE_FAIL(DEC_FUSE_R_FORMAT);
    id<MTLTexture> src = (__bridge id<MTLTexture>)S;
    const uint32_t L = pr->level;
    if (L >= src.mipmapLevelCount || src.textureType != MTLTextureType2D || src.sampleCount != 1 ||
        (src.pixelFormat != MTLPixelFormatRGBA8Unorm && src.pixelFormat != MTLPixelFormatBGRA8Unorm) ||
        layer.pixelFormat != MTLPixelFormatBGRA8Unorm || !layer.opaque) {
        DEC_FUSE_FAIL(DEC_FUSE_R_FORMAT);
    }
    const CGSize drawableSize = layer.drawableSize;
    if ((uint32_t)drawableSize.width != dec_mip(src.width, L) || (uint32_t)drawableSize.height != dec_mip(src.height, L)) {
        DEC_FUSE_FAIL(DEC_FUSE_R_SIZE);
    }
    /* The adaptive guard protects against waiting for a drawable before the frame's main encoder; a mailbox surface
     * never waits (CONTRACT.md 16.4), so a backoff left from blocking mode neither applies nor runs down there. */
    if (ds->fuseBackoff && !mcmtl_mailbox_active(surface)) {
        ds->fuseBackoff--;
        DEC_FUSE_FAIL(DEC_FUSE_R_BACKOFF);
    }

    const uint8_t *s = ext->stream;
    const uint64_t end = ext->indexEnd;
    const BOOL fold = (ext->m2 & DEC_M2_FOLD) != 0;
    uint64_t cand = UINT64_MAX;
    uint32_t candIdx = 0;
    BOOL pendingClear = NO;
    uint32_t why = DEC_FUSE_R_NO_CLEAR;
    BOOL presentSeen = NO;
    uint64_t o = 0;
    while (o + 16 <= end) {
        const mcmtl_cmd_header *h = (const mcmtl_cmd_header *)(s + o);
        const uint8_t *rec = s + o;
        const BOOL after = o > P;
        BOOL touches = NO;   /* any other reference to S: invalidates the candidate */
        switch (h->op) {
            case MCMTL_OP_BEGIN_PASS: {
                const dec_pass_info *pi = dec_pass_at(ext, o);
                if (!pi || pi->end == UINT64_MAX) DEC_FUSE_FAIL(DEC_FUSE_R_NO_INDEX);
                dec_pass_parse p;
                uint32_t bad = 0;
                if (dec_parse_begin_pass(rec, h->size, &p, &bad) != DEC_PARSE_OK) DEC_FUSE_FAIL(DEC_FUSE_R_NO_INDEX);
                const int use = dec_fuse_pass_use(ext, rec, &p, S, L);
                const BOOL samples = dec_pass_samples(ext, pi, S);
                if (use != DEC_FUSE_USE_NONE || samples) {
                    if (after) DEC_FUSE_FAIL(DEC_FUSE_R_SOURCE_USE);
                    if (samples || use == DEC_FUSE_USE_OTHER) {
                        touches = YES;
                    } else {
                        if (cand == UINT64_MAX) {
                            if (p.full && ((p.colorClear & 1u) || pendingClear)) {
                                cand = o;
                                candIdx = (uint32_t)(pi - ds->idxPasses);
                            } else {
                                why = DEC_FUSE_R_NO_CLEAR;
                            }
                        }
                        pendingClear = NO;
                    }
                }
                o = pi->end;   /* continue after its END_PASS */
                h = (const mcmtl_cmd_header *)(s + o);
                break;
            }
            case MCMTL_OP_CLEAR_TEXTURE: {
                const mcmtl_cmd_clear_texture *c = (const mcmtl_cmd_clear_texture *)rec;
                if (!c->texture || dec_resolve_texture(ext, c->texture)->root != S) break;
                if (after) DEC_FUSE_FAIL(DEC_FUSE_R_SOURCE_USE);
                const BOOL coversL = (c->aspects & MCMTL_ASPECT_COLOR) && c->slice == 0 && L >= c->baseLevel &&
                                     L - c->baseLevel < c->levelCount;
                if (coversL) {
                    /* A new clear of S@L: whatever was planned before it is overwritten; the next full pass may fold it. */
                    cand = UINT64_MAX;
                    pendingClear = fold;
                    why = DEC_FUSE_R_NO_CLEAR;
                }
                break;   /* other levels / aspects leave S@L alone */
            }
            case MCMTL_OP_CLEAR_REGION: {
                const mcmtl_cmd_clear_region *c = (const mcmtl_cmd_clear_region *)rec;
                touches = (c->colorTexture && dec_resolve_texture(ext, c->colorTexture)->root == S) ||
                          (c->depthTexture && dec_resolve_texture(ext, c->depthTexture)->root == S);
                break;
            }
            case MCMTL_OP_COPY_BUFFER_TO_TEXTURE:
            case MCMTL_OP_COPY_TEXTURE_TO_BUFFER: {
                const mcmtl_cmd_copy_buffer_texture *c = (const mcmtl_cmd_copy_buffer_texture *)rec;
                touches = c->texture && dec_resolve_texture(ext, c->texture)->root == S;
                break;
            }
            case MCMTL_OP_COPY_TEXTURE: {
                const mcmtl_cmd_copy_texture *c = (const mcmtl_cmd_copy_texture *)rec;
                touches = (c->srcTexture && dec_resolve_texture(ext, c->srcTexture)->root == S) ||
                          (c->dstTexture && dec_resolve_texture(ext, c->dstTexture)->root == S);
                break;
            }
            case MCMTL_OP_INIT_TEXTURE: {
                const mcmtl_cmd_init_texture *c = (const mcmtl_cmd_init_texture *)rec;
                touches = c->texture && dec_resolve_texture(ext, c->texture)->root == S;
                break;
            }
            case MCMTL_OP_UPSCALE:
                DEC_FUSE_FAIL(DEC_FUSE_R_NO_INDEX);   /* rejected by this decoder: the rest of the stream is skipped */
            case MCMTL_OP_PRESENT:
                if (o == P) {
                    if (cand == UINT64_MAX) DEC_FUSE_FAIL(why);
                    presentSeen = YES;
                }
                break;
            default:
                break;
        }
        if (touches) {
            if (after) DEC_FUSE_FAIL(DEC_FUSE_R_SOURCE_USE);
            cand = UINT64_MAX;
            pendingClear = NO;
            why = DEC_FUSE_R_SOURCE_USE;
        }
        o += h->size;
    }
    if (!presentSeen) DEC_FUSE_FAIL(DEC_FUSE_R_NO_INDEX);
    if (!dec_fuse_variants_ready(ext, candIdx, P, S, L)) DEC_FUSE_FAIL(DEC_FUSE_R_VARIANTS);
#undef DEC_FUSE_FAIL
    ext->fuseState = DEC_FUSE_PLANNED;
    ext->fuseCand = cand;
    ext->fusePresent = P;
    ext->fuseTex = src;
    ext->fuseLevel = L;
    ext->fuseSurface = surface;
}

/* At the candidate BEGIN_PASS (load actions resolved): commit the work so far, acquire the drawable, check it matches.
 * YES = bind drawable.texture as color 0. On NO the state says why; an acquired drawable is kept for PRESENT. */
static BOOL dec_fuse_begin(mcmtl_decoder_ext *ext, const dec_pass_parse *p, MTLLoadAction colorLoad0) {
    MCMTLDecoderState *ds = ext->ds;
    if (colorLoad0 != MTLLoadActionClear || !p->full) {
        /* The planned fold did not happen (e.g. the pending clear was materialized early): not provably overwritten. */
        ext->fuseState = DEC_FUSE_FAILED;
        ext->fuseReason = DEC_FUSE_R_NO_CLEAR;
        return NO;
    }
    id<CAMetalDrawable> drawable = nil;
    if (mcmtl_mailbox_active(ext->fuseSurface)) {
        /* Mailbox (CONTRACT.md 16.4): never wait. With no drawable free now, the frame renders into the source as
         * usual and PRESENT does not show it. Nothing would overlap a wait, so no early commit; the guard is idle. */
        uint32_t why = MCMTL_MAILBOX_NONE_READY;
        drawable = mcmtl_mailbox_take(ext->fuseSurface, &why);
        if (!drawable) {
            ext->fuseState = DEC_FUSE_SKIP;
            ext->fuseReason = DEC_FUSE_R_NO_DRAWABLE;
            ext->mailboxWhy = why;
            return NO;
        }
    } else {
        dec_commit_early(ext, YES);   /* the GPU runs the frame's earlier work while nextDrawable may block */
        CAMetalLayer *layer = ext->fuseSurface->layer;
        const uint64_t waitStart = clock_gettime_nsec_np(CLOCK_UPTIME_RAW);
        @autoreleasepool {
            drawable = [layer nextDrawable];
        }
        const uint64_t wait = clock_gettime_nsec_np(CLOCK_UPTIME_RAW) - waitStart;
        ds->stats.drawableWaitNs += wait;
        ds->stats.fusionWaitNs += wait;
        /* Adaptive guard: waiting for a drawable before the frame's main encoder starves the GPU when drawables come
         * back late (2 drawables on a composited layer, FIFO); the present-pass path then waits after committing. A
         * wait only costs GPU time if the previous frame's work had finished when the drawable arrived (its timeline
         * value is signalled); while that frame still runs, the wait is free. */
        const uint64_t previousFrame = ext->signalValue ? ext->signalValue - 1 : 0;
        const BOOL gpuStillBusy = previousFrame && ext->dev->event.signaledValue < previousFrame;
        ds->fuseWaitEma = (ds->fuseWaitEma * 3 + (gpuStillBusy ? 0 : wait)) / 4;
        if (ds->fuseMaxWaitNs && ds->fuseWaitEma > ds->fuseMaxWaitNs) {
            /* Off for a while, then retried; the period doubles while retries keep failing (a persistently
             * drawable-bound setup pays a couple of slow frames per period only). */
            if (ds->fuseBackoffPeriod < DEC_FUSE_BACKOFF_FRAMES) ds->fuseBackoffPeriod = DEC_FUSE_BACKOFF_FRAMES;
            ds->fuseBackoff = ds->fuseBackoffPeriod;
            ds->fuseBackoffPeriod = ds->fuseBackoffPeriod >= DEC_FUSE_BACKOFF_MAX / 2 ? DEC_FUSE_BACKOFF_MAX : ds->fuseBackoffPeriod * 2;
            ds->fuseWaitEma = 0;
            ds->fuseFusedRun = 0;
            ds->stats.fusionBackoffs++;
        } else if (++ds->fuseFusedRun >= DEC_FUSE_STEADY_FRAMES) {
            ds->fuseBackoffPeriod = DEC_FUSE_BACKOFF_FRAMES;   /* fusing steadily again: next backoff starts short */
        }
    }
    if (!drawable) {
        ext->fuseState = DEC_FUSE_NIL;
        ext->fuseReason = DEC_FUSE_R_DRAWABLE_NIL;
        return NO;
    }
    ext->fuseDrawable = drawable;
    id<MTLTexture> t = drawable.texture;
    if (t.width != p->attW || t.height != p->attH) {
        ext->fuseState = DEC_FUSE_FAILED;
        ext->fuseReason = DEC_FUSE_R_SIZE;
        return NO;
    }
    /* A restore reads the frame back through the drawable's IOSurface (dec_fuse_restore), never the drawable. */
    if (t.pixelFormat != MTLPixelFormatBGRA8Unorm || !t.iosurface) {
        ext->fuseState = DEC_FUSE_FAILED;
        ext->fuseReason = DEC_FUSE_R_FORMAT;
        return NO;
    }
    ext->fuseState = DEC_FUSE_ACTIVE;
    return YES;
}

enum { DEC_STALE_NONE = 0, DEC_STALE_OVERWRITE, DEC_STALE_READ };

/* The first thing the stream does with the stale source S@L: overwrite it entirely (full clear, full-area clearing
 * pass that does not sample S, INIT_TEXTURE), read it or write part of it, or nothing. A raw record walk (no index
 * needed); a malformed record ends it (the decode loop stops there too). */
static int dec_fuse_stale_first_use(mcmtl_decoder_ext *ext, const uint8_t *s, uint64_t length, const void *S, uint32_t L) {
    uint64_t o = 0;
    BOOL overwriting = NO;   /* inside a pass that clears S@L over its full area: decided at its END_PASS */
    while (o + 16 <= length) {
        const mcmtl_cmd_header *h = (const mcmtl_cmd_header *)(s + o);
        const uint32_t op = h->op, size = h->size;
        if (size < 16 || (size & 15) || size > length - o || op >= MCMTL_OP_LIMIT || kMinRecordSize[op] == 0 ||
            size < kMinRecordSize[op]) {
            return DEC_STALE_NONE;
        }
        const uint8_t *rec = s + o;
        switch (op) {
            case MCMTL_OP_BEGIN_PASS: {
                dec_pass_parse p;
                uint32_t bad = 0;
                if (dec_parse_begin_pass(rec, size, &p, &bad) != DEC_PARSE_OK) return DEC_STALE_NONE;
                const mcmtl_cmd_begin_pass *c = (const mcmtl_cmd_begin_pass *)rec;
                const mcmtl_cmd_color_attachment *atts = (const mcmtl_cmd_color_attachment *)(rec + MCMTL_CMD_SIZE_BEGIN_PASS_BASE);
                overwriting = NO;
                for (uint32_t i = 0; i < p.n; i++) {
                    if (!atts[i].texture || dec_resolve_texture(ext, atts[i].texture)->root != S || atts[i].level != L) continue;
                    if (!p.full || !((p.colorClear >> i) & 1u)) return DEC_STALE_READ;   /* loads (part of) it */
                    overwriting = YES;
                }
                if (c->depthTexture && dec_resolve_texture(ext, c->depthTexture)->root == S) return DEC_STALE_READ;
                break;
            }
            case MCMTL_OP_END_PASS:
                if (overwriting) return DEC_STALE_OVERWRITE;
                break;
            case MCMTL_OP_SET_TEXTURE: {
                const mcmtl_cmd_set_texture *c = (const mcmtl_cmd_set_texture *)rec;
                if (c->texture && dec_resolve_texture(ext, c->texture)->root == S) return DEC_STALE_READ;
                break;
            }
            case MCMTL_OP_CLEAR_TEXTURE: {
                const mcmtl_cmd_clear_texture *c = (const mcmtl_cmd_clear_texture *)rec;
                if (c->texture && dec_resolve_texture(ext, c->texture)->root == S && (c->aspects & MCMTL_ASPECT_COLOR) &&
                    c->slice == 0 && L >= c->baseLevel && L - c->baseLevel < c->levelCount) {
                    return DEC_STALE_OVERWRITE;
                }
                break;
            }
            case MCMTL_OP_CLEAR_REGION: {
                const mcmtl_cmd_clear_region *c = (const mcmtl_cmd_clear_region *)rec;
                if ((c->colorTexture && dec_resolve_texture(ext, c->colorTexture)->root == S && c->level == L) ||
                    (c->depthTexture && dec_resolve_texture(ext, c->depthTexture)->root == S)) {
                    return DEC_STALE_READ;   /* partial writes keep the rest: restore (a full-level one is rare) */
                }
                break;
            }
            case MCMTL_OP_COPY_BUFFER_TO_TEXTURE:
            case MCMTL_OP_COPY_TEXTURE_TO_BUFFER: {
                const mcmtl_cmd_copy_buffer_texture *c = (const mcmtl_cmd_copy_buffer_texture *)rec;
                if (c->texture && dec_resolve_texture(ext, c->texture)->root == S && c->level == L) return DEC_STALE_READ;
                break;
            }
            case MCMTL_OP_COPY_TEXTURE: {
                const mcmtl_cmd_copy_texture *c = (const mcmtl_cmd_copy_texture *)rec;
                if ((c->srcTexture && dec_resolve_texture(ext, c->srcTexture)->root == S && c->srcLevel == L) ||
                    (c->dstTexture && dec_resolve_texture(ext, c->dstTexture)->root == S && c->dstLevel == L)) {
                    return DEC_STALE_READ;
                }
                break;
            }
            case MCMTL_OP_PRESENT: {
                const mcmtl_cmd_present *c = (const mcmtl_cmd_present *)rec;
                if (c->texture && dec_resolve_texture(ext, c->texture)->root == S && c->level == L) return DEC_STALE_READ;
                break;
            }
            case MCMTL_OP_INIT_TEXTURE: {
                const mcmtl_cmd_init_texture *c = (const mcmtl_cmd_init_texture *)rec;
                if (c->texture && dec_resolve_texture(ext, c->texture)->root == S) return DEC_STALE_OVERWRITE;
                break;
            }
            case MCMTL_OP_UPSCALE: {
                const mcmtl_cmd_upscale *c = (const mcmtl_cmd_upscale *)rec;
                if ((c->srcTexture && dec_resolve_texture(ext, c->srcTexture)->root == S) ||
                    (c->dstTexture && dec_resolve_texture(ext, c->dstTexture)->root == S)) {
                    return DEC_STALE_READ;
                }
                break;
            }
            default:
                break;
        }
        o += size;
    }
    return DEC_STALE_NONE;
}

/* A read-only texture over the IOSurface behind a drawable's texture (nil if it has none or Metal refuses it). The
 * drawable's own texture is framebufferOnly (render target only); this alias can be sampled. */
static id<MTLTexture> dec_drawable_alias(id<MTLDevice> device, id<MTLTexture> drawableTexture) {
    IOSurfaceRef surface = drawableTexture.iosurface;
    if (!surface) return nil;
    MTLTextureDescriptor *td = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:drawableTexture.pixelFormat
                                                                                  width:drawableTexture.width
                                                                                 height:drawableTexture.height
                                                                              mipmapped:NO];
    td.usage = MTLTextureUsageShaderRead;
    td.storageMode = MTLStorageModeShared;
    return [device newTextureWithDescriptor:td iosurface:surface plane:0];
}

/* Rebuilds the stale source level from the previous fused frame's drawable (CONTRACT.md 16.3): one pass whose color 0
 * is the source level (DontCare, Store) and whose fragment reads the same pixel of a read-only alias of the drawable's
 * IOSurface. Byte-exact: unorm8 -> float -> unorm8, R/B swapped by the formats.
 *
 * The drawable is never attached again: its present is registered, and on the direct-to-display path a render pass
 * on a presented (on-screen) drawable blocks on the GPU until the display lets go of the surface, which needs a later
 * present queued behind it: the GPU watchdog kills the command buffer after 5 s and the timeline value is never
 * signalled (the in-game scene runner's "5s timeout reached when waiting for the GPU"). Reading the IOSurface does not
 * wait. The alias is not hazard-tracked against the drawable's texture, so the restore first waits on the GPU for the
 * fused frame's timeline value (its command buffers are committed: fusion only happens in COMMIT calls). */
static void dec_fuse_restore(mcmtl_decoder_ext *ext) {
    MCMTLDecoderState *ds = ext->ds;
    MCMTLDevice *dev = ext->dev;
    id<MTLTexture> target = ds->staleTexture;
    const uint32_t level = ds->staleLevel;
    id<CAMetalDrawable> drawable = ds->staleDrawable;
    id<MTLTexture> dt = drawable.texture;
    if (!dt || dt.width != dec_mip(target.width, level) || dt.height != dec_mip(target.height, level)) {
        DEC_MSG(ext, DEC_MSG_PRESENT, "present fusion: stale source cannot be restored (drawable size changed)");
        return;
    }
    id<MTLTexture> alias = dec_drawable_alias(dev->device, dt);
    id<MTLRenderPipelineState> pso = alias ? dec_restore_pso(dev, ds, (uint32_t)target.pixelFormat, &ext->messaged) : nil;
    if (!pso) {
        if (!alias) DEC_MSG(ext, DEC_MSG_PRESENT, "present fusion: stale source cannot be restored (drawable has no readable IOSurface)");
        return;
    }
    /* GPU-side order after the fused frame's writes into the drawable (skipped once they are known to be done). */
    const uint64_t fusedValue = ds->staleValue;
    if (fusedValue && dev->event.signaledValue < fusedValue &&
        mcmtl_status_load_u64(&dev->status->submittedValue) >= fusedValue) {
        dec_end_blit(ext);
        [dec_cb(ext) encodeWaitForEvent:dev->event value:fusedValue];
    }
    MTLRenderPassDescriptor *desc = [MTLRenderPassDescriptor renderPassDescriptor];
    desc.colorAttachments[0].texture = target;
    desc.colorAttachments[0].level = level;
    desc.colorAttachments[0].loadAction = MTLLoadActionDontCare;
    desc.colorAttachments[0].storeAction = MTLStoreActionStore;
    g_prof_tag = "fusion restore";
    id<MTLRenderCommandEncoder> encoder = dec_render_encoder(ext, desc);
    [encoder setRenderPipelineState:pso];
    [encoder setFragmentTexture:alias atIndex:0];
    [encoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
    [encoder endEncoding];
    /* The drawable object (not just its IOSurface) stays alive until the GPU has read it: Core Animation does not hand
     * its surface out again before that. */
    [ext->commandBuffer addCompletedHandler:^(id<MTLCommandBuffer> done) {
        (void)done;
        (void)drawable;
        (void)alias;
    }];
    ds->stats.fusionRestores++;
    if (dec_fuse_trace()) {
        NSLog(@"[fusetrace] restore at value %llu (call flags 0x%x) from the drawable of value %llu (%p, presentedTime %.6f, now %.6f)",
              (unsigned long long)ext->signalValue, ext->ctx->info->flags, (unsigned long long)fusedValue,
              (__bridge void *)drawable, drawable.presentedTime, CACurrentMediaTime());
        ds->fuseTraceCalls = 6;
    }
}

/* Start of a main stream while the previous fused frame left its source stale: restore it now if the stream reads
 * it before overwriting it (CONTRACT.md 16.3), forget it when overwritten, or when a whole frame never referenced
 * it (vanilla clears the main target at the start of every frame; the drawable must go back to the pool). */
static void dec_fuse_stale(mcmtl_decoder_ext *ext, const uint8_t *s, uint64_t length) {
    MCMTLDecoderState *ds = ext->ds;
    const int use = dec_fuse_stale_first_use(ext, s, length, (__bridge const void *)ds->staleTexture, ds->staleLevel);
    if (dec_fuse_trace() && (use == DEC_STALE_READ || ds->fuseTraceCalls)) {
        NSLog(@"[fusetrace] stale source at call (value %llu, flags 0x%x): %s", (unsigned long long)ext->signalValue,
              ext->ctx->info->flags, use == DEC_STALE_READ ? "read -> restore" : use == DEC_STALE_OVERWRITE ? "overwritten" : "not referenced");
    }
    if (use == DEC_STALE_NONE && !(ext->ctx->info->flags & MCMTL_SUBMIT_FLAG_FRAME_END)) return;   /* keep for later calls */
    if (use == DEC_STALE_READ) {
        dec_fuse_restore(ext);
    } else {
        ds->stats.fusionStaleDropped++;
    }
    ds->staleTexture = nil;
    ds->staleDrawable = nil;
}

/* ---------------------------------------------------------------------------------------------- */
/* Encoder-level records                                                                            */
/* ---------------------------------------------------------------------------------------------- */

static int32_t op_begin_pass(mcmtl_decode_ctx *ctx, mcmtl_decoder_ext *ext, const uint8_t *rec, uint32_t size, uint64_t offset) {
    if (ext->mergeOpen) dec_merge_break(ext);
    const mcmtl_cmd_begin_pass *c = (const mcmtl_cmd_begin_pass *)rec;
    const mcmtl_cmd_color_attachment *atts = (const mcmtl_cmd_color_attachment *)(rec + MCMTL_CMD_SIZE_BEGIN_PASS_BASE);
    dec_pass_parse p;
    uint32_t bad = 0;
    switch (dec_parse_begin_pass(rec, size, &p, &bad)) {
        case DEC_PARSE_OK:
            break;
        case DEC_PARSE_SIZE:
            return dec_fail(ext, MCMTL_E_BAD_STREAM, offset, "BEGIN_PASS colorCount %u does not fit record size %u", c->colorCount, size);
        case DEC_PARSE_COLOR_LEVEL:
            return dec_fail(ext, MCMTL_E_BAD_STREAM, offset, "BEGIN_PASS color %u level %u >= %lu", bad, atts[bad].level,
                            (unsigned long)MCMTL_TEXTURE(atts[bad].texture).mipmapLevelCount);
        case DEC_PARSE_DEPTH_LEVEL:
            return dec_fail(ext, MCMTL_E_BAD_STREAM, offset, "BEGIN_PASS depth level %u >= %lu", c->depthLevel,
                            (unsigned long)MCMTL_TEXTURE(c->depthTexture).mipmapLevelCount);
        default:
            return dec_fail(ext, MCMTL_E_BAD_STREAM, offset, "BEGIN_PASS without attachments");
    }
    MCMTLDecoderState *ds = ext->ds;
    const uint32_t m2 = ext->m2;
    ds->stats.passesIn++;

    /* Load actions as recorded (M1). */
    MTLLoadAction colorLoad[MCMTL_MAX_COLOR_ATTACHMENTS];
    MTLClearColor colorValue[MCMTL_MAX_COLOR_ATTACHMENTS];
    for (uint32_t i = 0; i < MCMTL_MAX_COLOR_ATTACHMENTS; i++) {
        BOOL clear = ((p.colorClear >> i) & 1u) && p.full;
        colorLoad[i] = clear ? MTLLoadActionClear : MTLLoadActionLoad;
        colorValue[i] = i < p.n ? MTLClearColorMake(atts[i].clearColor[0], atts[i].clearColor[1], atts[i].clearColor[2], atts[i].clearColor[3])
                                : MTLClearColorMake(0, 0, 0, 0);
    }
    MTLLoadAction depthLoad = (p.depthClear && p.full) ? MTLLoadActionClear : MTLLoadActionLoad;
    MTLLoadAction stencilLoad = depthLoad;
    double depthValue = c->depthClearValue;

    /* M2: merge chain (B4/B6), then pending clears (B3): those the chain samples are encoded first, those of
     * the attachments fold into the load actions (full area) or are encoded first (partial area). */
    const dec_pass_info *pi = m2 ? dec_pass_for_begin(ext, offset) : NULL;
    uint32_t planned = 0;
    ext->chainActive = NO;
    ext->chainCount = ext->chainNext = 0;
    ext->clearDrawCount = ext->clearDrawNext = 0;
    if (pi) dec_plan_chain(ext, pi, &p, &planned);
    if (ext->pendingCount) {
        if (!pi) dec_pending_materialize_all(ext);
        else dec_pending_materialize_sampled(ext, pi, 1 + ext->chainCount);
    }
    if (ext->pendingCount) {
        for (uint32_t i = 0; i < MCMTL_MAX_COLOR_ATTACHMENTS; i++) {
            if (!(p.presentMask & (1u << i))) continue;
            int k = dec_pending_find(ext, (__bridge const void *)p.color[i], p.level[i], 0);
            if (k < 0) continue;
            if (!p.full) {
                dec_pending_materialize_at(ext, (uint32_t)k);
            } else {
                if ((p.colorClear >> i) & 1u) {
                    ds->stats.clearsDropped++;           /* the pass clears it itself */
                } else {
                    const float *v = ext->pending[k].rgba;
                    colorLoad[i] = MTLLoadActionClear;
                    colorValue[i] = MTLClearColorMake(v[0], v[1], v[2], v[3]);
                    ds->stats.clearsFolded++;
                }
                dec_pending_remove(ext, (uint32_t)k);
            }
        }
        int k = p.depth ? dec_pending_find(ext, (__bridge const void *)p.depth, p.depthLevel, 0) : -1;
        if (k >= 0 && (p.depthInfo.depth || p.depthInfo.stencil)) {
            if (!p.full) {
                dec_pending_materialize_at(ext, (uint32_t)k);
            } else {
                const dec_pending e = ext->pending[k];
                if (p.depthClear) {
                    ds->stats.clearsDropped++;
                } else {
                    if (e.depth) {
                        depthLoad = MTLLoadActionClear;
                        depthValue = e.depthValue;
                    }
                    if (e.stencil) stencilLoad = MTLLoadActionClear;
                    ds->stats.clearsFolded++;
                }
                dec_pending_remove(ext, (uint32_t)k);
            }
        }
    }
    if (planned) {
        int32_t rc = dec_run_hoists(ext, planned);
        if (rc != MCMTL_OK) return rc;
    }

    /* Present fusion (CONTRACT.md 16.3): from the planned pass on, color 0 = S@L is the drawable. */
    BOOL fused = NO;
    if (ext->fuseState == DEC_FUSE_PLANNED && offset == ext->fuseCand) {
        DEC_SLOW_BEGIN;
        fused = dec_fuse_begin(ext, &p, colorLoad[0]);
        DEC_SLOW_END("fuseBegin", 0);
    } else if (ext->fuseState == DEC_FUSE_ACTIVE && offset < ext->fusePresent && (p.presentMask & 1u) &&
               p.level[0] == ext->fuseLevel && dec_root_texture(p.color[0]) == (__bridge const void *)ext->fuseTex) {
        fused = YES;
    }
    id<MTLTexture> drawableTexture = fused ? ext->fuseDrawable.texture : nil;
    if (fused) p.colorFormat[0] = (uint32_t)drawableTexture.pixelFormat;

    const BOOL late = (m2 & DEC_M2_LATE_STORE) != 0;
    const MTLStoreAction store = late ? MTLStoreActionUnknown : MTLStoreActionStore;
    MTLRenderPassDescriptor *desc = [MTLRenderPassDescriptor renderPassDescriptor];
    for (uint32_t i = 0; i < p.n; i++) {
        if (!(p.presentMask & (1u << i))) continue;
        MTLRenderPassColorAttachmentDescriptor *a = desc.colorAttachments[i];
        a.texture = (fused && i == 0) ? drawableTexture : p.color[i];
        a.level = (fused && i == 0) ? 0 : p.level[i];
        a.slice = 0;
        a.loadAction = colorLoad[i];
        if (colorLoad[i] == MTLLoadActionClear) a.clearColor = colorValue[i];
        a.storeAction = store;
    }
    if (p.depth) {
        if (p.depthInfo.depth) {
            MTLRenderPassDepthAttachmentDescriptor *d = desc.depthAttachment;
            d.texture = p.depth;
            d.level = p.depthLevel;
            d.slice = 0;
            d.loadAction = depthLoad;
            d.clearDepth = depthValue;
            d.storeAction = store;
        }
        if (p.depthInfo.stencil) {
            MTLRenderPassStencilAttachmentDescriptor *st = desc.stencilAttachment;
            st.texture = p.depth;
            st.level = p.depthLevel;
            st.slice = 0;
            st.loadAction = stencilLoad;
            st.clearStencil = 0;
            st.storeAction = store;
        }
    }

    /* Timestamps: a merged encoder starts with its first pass and ends with its last one. */
    uint64_t tsOffset = offset;
    uint32_t tsSize = size;
    if (ext->chainActive) {
        tsOffset = ext->chainBegins[ext->chainCount - 1];
        tsSize = ((const mcmtl_cmd_header *)(ext->stream + tsOffset))->size;
    }
    dec_ts_attach_pass(ext, desc, tsOffset, tsSize);
    if (ext->occlActive) dec_occl_attach(ext, desc, offset);
    id<MTLRenderCommandEncoder> encoder = dec_render_encoder(ext, desc);
    ds->stats.passEncoders++;
    ext->renderEncoder = encoder;
    ctx->renderEncoder = encoder;
    ctx->inPass = 1;
    ext->passOffset = offset;
    ext->encLateStore = late;
    ext->encFused = fused;
    if (fused) ds->stats.fusedPasses++;
    ext->enc = p;
    dec_reset_pass_state(ctx, ext);
    ctx->passColorPresentMask = p.presentMask;
    ctx->passDepthFormat = p.passDepthFormat;
    ctx->attachmentWidth = p.attW;
    ctx->attachmentHeight = p.attH;
    ctx->scissor[0] = 0;
    ctx->scissor[1] = 0;
    ctx->scissor[2] = (int32_t)p.attW;
    ctx->scissor[3] = (int32_t)p.attH;
    ext->passEmpty = p.empty;
    memcpy(ctx->renderArea, p.area, sizeof p.area);
    if (p.empty) return MCMTL_OK;
    if (!p.full) {
        dec_set_scissor(ctx, ext, p.area[0], p.area[1], p.area[2], p.area[3]);
        if (p.colorClear || p.depthClear) {
            float colors[8][4];
            memset(colors, 0, sizeof colors);
            for (uint32_t i = 0; i < p.n; i++) memcpy(colors[i], atts[i].clearColor, sizeof colors[i]);
            if (!dec_draw_clear_quad(ctx, ext, p.colorFormat, p.colorClear, colors, p.quadDepthFormat, p.stencilFormat, p.depthClear,
                                     c->depthClearValue)) {
                DEC_MSG(ext, DEC_MSG_CLEAR, "partial render-area clear skipped (internal PSO unavailable)");
            }
        }
    }
    return MCMTL_OK;
}

/* Ends the open pass encoder: pops in-pass groups, resolves late store actions (look-ahead from lookFrom;
 * UINT64_MAX = Store all), and settles the debug groups of a merged encoder (encoder groups pushed between
 * its passes are re-pushed on the command buffer, command-buffer pops owed since its start are done). */
static void dec_end_render_encoder(mcmtl_decode_ctx *ctx, mcmtl_decoder_ext *ext, uint64_t lookFrom) {
    if (ext->renderEncoder) {
        id<MTLRenderCommandEncoder> encoder = ext->renderEncoder;
        dec_occl_encoder_end(ext);
        while (ext->encoderGroupDepth) {
            [encoder popDebugGroup];
            ext->encoderGroupDepth--;
        }
        NSArray<NSString *> *reopen = nil;
        if (ext->mergeGroupDepth) {
            NSMutableArray<NSString *> *stack = ext->ds->labelStack;
            NSUInteger n = ext->mergeGroupDepth <= stack.count ? ext->mergeGroupDepth : stack.count;
            reopen = [stack subarrayWithRange:NSMakeRange(stack.count - n, n)];
            while (ext->mergeGroupDepth) {
                [encoder popDebugGroup];
                ext->mergeGroupDepth--;
            }
        }
        if (ext->encLateStore) dec_resolve_stores(ext, encoder, lookFrom);
        ext->encLateStore = NO;
        ext->encFused = NO;
        ext->renderEncoder = nil;
        ctx->renderEncoder = nil;
        ctx->inPass = 0;
        ext->mergeOpen = NO;
        [encoder endEncoding];
        id<MTLCommandBuffer> cb = ext->commandBuffer;
        while (ext->deferredCbPops) {
            [cb popDebugGroup];
            ext->cbGroupDepth--;
            ext->deferredCbPops--;
        }
        for (NSString *label in reopen) {
            [cb pushDebugGroup:label];
            ext->cbGroupDepth++;
        }
    }
    ctx->inPass = 0;
}

static int32_t op_end_pass(mcmtl_decode_ctx *ctx, mcmtl_decoder_ext *ext, uint32_t size, uint64_t offset) {
    if (ext->occlActive && ctx->renderEncoder) dec_occl_before_state_change(ctx, ext, NO);
    if (ext->chainActive && ext->chainNext < ext->chainCount && offset != ext->chainEnd) {
        /* Merged: the encoder stays open for the next pass of the chain. */
        while (ext->encoderGroupDepth) {
            [ext->renderEncoder popDebugGroup];
            ext->encoderGroupDepth--;
        }
        ctx->inPass = 0;
        ext->mergeOpen = YES;
        return MCMTL_OK;
    }
    ext->chainActive = NO;
    ext->chainCount = ext->chainNext = 0;
    ext->clearDrawCount = ext->clearDrawNext = 0;
    dec_end_render_encoder(ctx, ext, offset + size);
    for (uint32_t i = 0; i < ext->endOverflowCount; i++) dec_sample_standalone(ext, &ext->endOverflow[i]);
    ext->endOverflowCount = 0;
    return MCMTL_OK;
}

static void dec_merge_break(mcmtl_decoder_ext *ext) {
    if (ext->mergeOpen && ext->renderEncoder) {
        ext->ds->stats.mergeBreaks++;
        dec_end_render_encoder(ext->ctx, ext, ext->curOffset);
    }
    ext->mergeOpen = NO;
    ext->chainActive = NO;
    ext->chainCount = ext->chainNext = 0;
    ext->clearDrawCount = ext->clearDrawNext = 0;
    if (ext->mergeClearPending) {
        /* A planned depth clear was taken from the stream but its pass never continued the encoder. */
        ext->mergeClearPending = NO;
        ext->ds->stats.clearsRecorded--;
        if (ext->m2 & DEC_M2_FOLD) {
            dec_pending_add(ext, ext->enc.depth, ext->enc.depthLevel, 0, NO, NULL, YES, ext->mergeClearValue, NO);
        } else {
            static const float zero[4] = {0, 0, 0, 0};
            dec_clear_level(ext, ext->enc.depth, ext->enc.depthLevel, 0, NO, zero, YES, ext->mergeClearValue, NO);
        }
    }
}

/* Per-pass state of a pass continuing a merged encoder: what a fresh encoder would start with (no pipeline,
 * nothing set, no vertex / index buffers) while the shadow keeps describing the real Metal bindings. */
static void dec_reset_merged_pass_state(mcmtl_decode_ctx *ctx, mcmtl_decoder_ext *ext) {
    ctx->pipeline = nil;
    ctx->bufferSetMask[0] = ctx->bufferSetMask[1] = 0;
    ctx->textureSetMask[0] = ctx->textureSetMask[1] = 0;
    ctx->indexBuffer = nil;
    ctx->indexType = MTLIndexTypeUInt16;
    ext->indexBufferLength = 0;
    ext->vertexBoundMask = 0;
    ext->samplerBoundMask[0] = ext->samplerBoundMask[1] = 0;
    ext->pipelineInvalid = YES;
    ext->encoderGroupDepth = 0;
    ext->scissorEmpty = NO;
    ext->passEmpty = NO;
}

/* Full-screen depth clear inside the open encoder (scissor = full area): compare Always, write on, no cull,
 * fill, no depth bias, color writes off; the shadow state is updated to what was set. */
static void dec_draw_depth_clear(mcmtl_decode_ctx *ctx, mcmtl_decoder_ext *ext, id<MTLRenderPipelineState> pso, float value) {
    if (g_mcmtl_diag & 4u) return;   /* round 9 diagnostics only (changes the image): no in-encoder depth clear */
    MCMTLDecoderState *ds = ext->ds;
    __unsafe_unretained id<MTLRenderCommandEncoder> re = ctx->renderEncoder;
    if (pso != ctx->pso) {
        [re setRenderPipelineState:pso];
        ctx->pso = pso;
        ds->stats.setPipelineState++;
    }
    if (ds->alwaysWriteDSS != ctx->depthStencilState) {
        [re setDepthStencilState:ds->alwaysWriteDSS];
        ctx->depthStencilState = ds->alwaysWriteDSS;
        ds->stats.setDepthStencilState++;
    }
    if (ctx->cullMode != MTLCullModeNone) {
        [re setCullMode:MTLCullModeNone];
        ctx->cullMode = MTLCullModeNone;
        ds->stats.setRasterState++;
    }
    if (ctx->fillMode != MTLTriangleFillModeFill) {
        [re setTriangleFillMode:MTLTriangleFillModeFill];
        ctx->fillMode = MTLTriangleFillModeFill;
        ds->stats.setRasterState++;
    }
    if (ctx->depthBias[0] != 0.0f || ctx->depthBias[1] != 0.0f || ctx->depthBias[2] != 0.0f) {
        [re setDepthBias:0.0f slopeScale:0.0f clamp:0.0f];
        ctx->depthBias[0] = ctx->depthBias[1] = ctx->depthBias[2] = 0.0f;
        ds->stats.setRasterState++;
    }
    dec_quad_params params;
    memset(&params, 0, sizeof params);
    params.depth = value;
    [re setVertexBytes:&params length:sizeof params atIndex:0];
    ctx->buffers[0][0] = nil;
    ctx->bufferSetMask[0] &= ~1u;
    if (!dec_clear_draw_vertex_only(ext, value)) {
        [re setFragmentBytes:&params length:sizeof params atIndex:0];
        ctx->buffers[1][0] = nil;
        ctx->bufferSetMask[1] &= ~1u;
    }
    [re drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
    ds->stats.clearDraws++;
}

/* BEGIN_PASS of a planned chain pass: continue the open encoder. Re-checks everything the plan relied on;
 * on any surprise the encoder is ended and the pass begins normally. */
static int32_t op_merge_continue(mcmtl_decode_ctx *ctx, mcmtl_decoder_ext *ext, const uint8_t *rec, uint32_t size, uint64_t offset) {
    MCMTLDecoderState *ds = ext->ds;
    ext->chainNext++;
    const dec_pass_info *pi = dec_pass_for_begin(ext, offset);
    dec_pass_parse p;
    uint32_t bad = 0;
    BOOL ok = pi && dec_parse_begin_pass(rec, size, &p, &bad) == DEC_PARSE_OK && dec_same_attachments(&ext->enc, &p) && p.full &&
              !p.colorClear && ext->renderEncoder;
    for (uint32_t i = 0; ok && i < ext->pendingCount; i++) {
        const dec_pending *e = &ext->pending[i];
        const void *t = (__bridge const void *)e->texture;
        if (dec_pass_samples(ext, pi, t) || (e->slice == 0 && dec_is_attachment_level(&ext->enc, t, e->level))) ok = NO;
    }
    const BOOL needClear = ok && (p.depthClear || ext->mergeClearPending);
    const float value = ok && p.depthClear ? p.depthClearValue : ext->mergeClearValue;
    id<MTLRenderPipelineState> pso = nil;
    if (needClear) {
        BOOL depthOnly = ext->enc.depth && ext->enc.depthInfo.depth && !ext->enc.depthInfo.stencil;
        pso = depthOnly && value >= 0.0f && value <= 1.0f ? dec_clear_draw_pso(ext, &ext->enc, value) : nil;
        if (!pso) ok = NO;
    }
    if (!ok) {
        dec_merge_break(ext);
        return op_begin_pass(ctx, ext, rec, size, offset);
    }
    if (p.depthClear && ext->mergeClearPending) ds->stats.clearsDropped++;
    ext->mergeClearPending = NO;
    ext->mergeOpen = NO;
    ctx->inPass = 1;
    ext->passOffset = offset;
    dec_reset_merged_pass_state(ctx, ext);
    memcpy(ctx->renderArea, p.area, sizeof p.area);
    dec_set_scissor(ctx, ext, p.area[0], p.area[1], p.area[2], p.area[3]);
    if (needClear) dec_draw_depth_clear(ctx, ext, pso, value);
    ds->stats.passesIn++;
    ds->stats.merges++;
    return MCMTL_OK;
}

/* A planned CLEAR_TEXTURE between two chain passes: the attachment level becomes the next pass's clear draw,
 * other levels become pending clears. */
static int32_t op_gap_planned_clear(mcmtl_decoder_ext *ext, const uint8_t *rec) {
    const mcmtl_cmd_clear_texture *c = (const mcmtl_cmd_clear_texture *)rec;
    id<MTLTexture> t = MCMTL_TEXTURE(c->texture);
    const uint32_t levels = (uint32_t)t.mipmapLevelCount;
    uint64_t end = (uint64_t)c->baseLevel + c->levelCount;
    if (end > levels) end = levels;
    for (uint32_t level = c->baseLevel; level < end; level++) {
        if (level == ext->enc.depthLevel) {
            if (ext->mergeClearPending) ext->ds->stats.clearsDropped++;
            ext->mergeClearPending = YES;
            ext->mergeClearValue = c->depth;
            ext->ds->stats.clearsRecorded++;
        } else {
            dec_pending_add(ext, t, level, 0, NO, NULL, YES, c->depth, NO);
        }
    }
    return MCMTL_OK;
}

/* Does a CLEAR_TEXTURE touch a subresource attached to the open encoder? */
static BOOL dec_clear_hits_encoder(mcmtl_decoder_ext *ext, const mcmtl_cmd_clear_texture *c) {
    if (!c->texture || c->slice != 0) return NO;
    const void *t = (const void *)(uintptr_t)c->texture;
    const dec_pass_parse *e = &ext->enc;
    if (e->depth && (__bridge const void *)e->depth == t && e->depthLevel >= c->baseLevel && e->depthLevel - c->baseLevel < c->levelCount) {
        return YES;
    }
    for (uint32_t i = 0; i < MCMTL_MAX_COLOR_ATTACHMENTS; i++) {
        if ((e->presentMask & (1u << i)) && (__bridge const void *)e->color[i] == t && e->level[i] >= c->baseLevel &&
            e->level[i] - c->baseLevel < c->levelCount) {
            return YES;
        }
    }
    return NO;
}

/* A record between two passes of an open merged encoder. YES = handled here (rc set); NO = decode it normally
 * (labels, NOPs, clears of other textures that stay pending) -- after ending the merge if it needs another
 * encoder. */
static BOOL dec_merge_gap(mcmtl_decode_ctx *ctx, mcmtl_decoder_ext *ext, uint32_t op, const uint8_t *rec, uint32_t size,
                          uint64_t offset, int32_t *rc) {
    switch (op) {
        case MCMTL_OP_NOP:
        case MCMTL_OP_PUSH_DEBUG_GROUP:
        case MCMTL_OP_POP_DEBUG_GROUP:
            return NO;
        case MCMTL_OP_BEGIN_PASS:
            if (ext->chainNext < ext->chainCount && offset == ext->chainBegins[ext->chainNext]) {
                *rc = op_merge_continue(ctx, ext, rec, size, offset);
                return YES;
            }
            break;
        case MCMTL_OP_CLEAR_TEXTURE:
            if (ext->clearDrawNext < ext->clearDrawCount && offset == ext->clearDrawOffsets[ext->clearDrawNext]) {
                ext->clearDrawNext++;
                *rc = op_gap_planned_clear(ext, rec);
                return YES;
            }
            if ((ext->m2 & DEC_M2_FOLD) && !dec_clear_hits_encoder(ext, (const mcmtl_cmd_clear_texture *)rec)) return NO;
            break;
        default:
            break;
    }
    dec_merge_break(ext);
    return NO;
}

/* Render pass that clears one level/slice of `texture` (color or depth/stencil per its format). */
static void dec_clear_level(mcmtl_decoder_ext *ext, id<MTLTexture> texture, uint32_t level, uint32_t slice, BOOL clearColor,
                            const float color[4], BOOL clearDepth, double depth, BOOL clearStencil) {
    g_prof_tag = "clear";
    dec_format f = dec_format_info(texture.pixelFormat);
    MTLRenderPassDescriptor *desc = [MTLRenderPassDescriptor renderPassDescriptor];
    if (f.depth || f.stencil) {
        if (f.depth) {
            desc.depthAttachment.texture = texture;
            desc.depthAttachment.level = level;
            desc.depthAttachment.slice = slice;
            desc.depthAttachment.loadAction = clearDepth ? MTLLoadActionClear : MTLLoadActionLoad;
            desc.depthAttachment.clearDepth = depth;
            desc.depthAttachment.storeAction = MTLStoreActionStore;
        }
        if (f.stencil) {
            desc.stencilAttachment.texture = texture;
            desc.stencilAttachment.level = level;
            desc.stencilAttachment.slice = slice;
            desc.stencilAttachment.loadAction = clearStencil ? MTLLoadActionClear : MTLLoadActionLoad;
            desc.stencilAttachment.clearStencil = 0;
            desc.stencilAttachment.storeAction = MTLStoreActionStore;
        }
    } else {
        desc.colorAttachments[0].texture = texture;
        desc.colorAttachments[0].level = level;
        desc.colorAttachments[0].slice = slice;
        desc.colorAttachments[0].loadAction = clearColor ? MTLLoadActionClear : MTLLoadActionLoad;
        desc.colorAttachments[0].clearColor = MTLClearColorMake(color[0], color[1], color[2], color[3]);
        desc.colorAttachments[0].storeAction = MTLStoreActionStore;
    }
    id<MTLRenderCommandEncoder> encoder = dec_render_encoder(ext, desc);
    [encoder endEncoding];
}

static int32_t op_clear_texture(mcmtl_decoder_ext *ext, const uint8_t *rec, uint64_t offset) {
    const mcmtl_cmd_clear_texture *c = (const mcmtl_cmd_clear_texture *)rec;
    if (!c->texture) return dec_fail(ext, MCMTL_E_BAD_STREAM, offset, "CLEAR_TEXTURE without texture");
    id<MTLTexture> t = MCMTL_TEXTURE(c->texture);
    dec_format f = dec_format_info(t.pixelFormat);
    BOOL isDepthStencil = f.depth || f.stencil;
    BOOL clearColor = !isDepthStencil && (c->aspects & MCMTL_ASPECT_COLOR);
    BOOL clearDepth = f.depth && (c->aspects & MCMTL_ASPECT_DEPTH);
    BOOL clearStencil = f.stencil && (c->aspects & MCMTL_ASPECT_STENCIL);
    if (!clearColor && !clearDepth && !clearStencil) {
        DEC_MSG(ext, DEC_MSG_CLEAR, "CLEAR_TEXTURE aspects 0x%x do not match pixel format %lu; skipped", c->aspects,
                (unsigned long)t.pixelFormat);
        return MCMTL_OK;
    }
    if (!(t.usage & MTLTextureUsageRenderTarget)) {
        DEC_MSG(ext, DEC_MSG_CLEAR, "CLEAR_TEXTURE on a texture without RenderTarget usage; skipped");
        return MCMTL_OK;
    }
    if (c->slice >= dec_slice_count(t)) {
        DEC_MSG(ext, DEC_MSG_CLEAR, "CLEAR_TEXTURE slice %u out of range; skipped", c->slice);
        return MCMTL_OK;
    }
    uint32_t levels = (uint32_t)t.mipmapLevelCount;
    uint64_t end = (uint64_t)c->baseLevel + c->levelCount;
    if (end > levels) end = levels;
    const BOOL fold = (ext->m2 & DEC_M2_FOLD) && !ext->isInit;
    for (uint32_t level = c->baseLevel; level < end; level++) {
        if (fold) {
            dec_pending_add(ext, t, level, c->slice, clearColor, c->color, clearDepth, c->depth, clearStencil);
        } else {
            dec_clear_level(ext, t, level, c->slice, clearColor, c->color, clearDepth, c->depth, clearStencil);
        }
    }
    return MCMTL_OK;
}

static int32_t op_clear_region(mcmtl_decoder_ext *ext, const uint8_t *rec, uint64_t offset) {
    const mcmtl_cmd_clear_region *c = (const mcmtl_cmd_clear_region *)rec;
    id<MTLTexture> color = c->colorTexture ? MCMTL_TEXTURE(c->colorTexture) : nil;
    id<MTLTexture> depth = c->depthTexture ? MCMTL_TEXTURE(c->depthTexture) : nil;
    if (!color && !depth) return dec_fail(ext, MCMTL_E_BAD_STREAM, offset, "CLEAR_REGION without textures");
    dec_pending_materialize_handle(ext, c->colorTexture);
    dec_pending_materialize_handle(ext, c->depthTexture);
    if ((color && (c->level >= color.mipmapLevelCount || !(color.usage & MTLTextureUsageRenderTarget))) ||
        (depth && (c->level >= depth.mipmapLevelCount || !(depth.usage & MTLTextureUsageRenderTarget)))) {
        DEC_MSG(ext, DEC_MSG_CLEAR, "CLEAR_REGION level %u out of range or texture not renderable; skipped", c->level);
        return MCMTL_OK;
    }
    dec_format depthInfo = depth ? dec_format_info(depth.pixelFormat) : (dec_format){0};
    if (depth && !depthInfo.depth) {
        DEC_MSG(ext, DEC_MSG_CLEAR, "CLEAR_REGION depth texture has no depth aspect; skipped");
        return MCMTL_OK;
    }
    uint32_t cw = color ? dec_mip(color.width, c->level) : 0, ch = color ? dec_mip(color.height, c->level) : 0;
    uint32_t dw = depth ? dec_mip(depth.width, c->level) : 0, dh = depth ? dec_mip(depth.height, c->level) : 0;
    if (color && depth && (cw != dw || ch != dh)) {
        /* Different sizes cannot share a render pass: clear each one separately. */
        mcmtl_cmd_clear_region part = *c;
        part.depthTexture = 0;
        int32_t rc = op_clear_region(ext, (const uint8_t *)&part, offset);
        if (rc != MCMTL_OK) return rc;
        part = *c;
        part.colorTexture = 0;
        return op_clear_region(ext, (const uint8_t *)&part, offset);
    }
    uint32_t w = color ? cw : dw, h = color ? ch : dh;
    int32_t rect[4];
    if (!dec_clamp_rect(c->x, c->y, c->width, c->height, w, h, rect)) return MCMTL_OK;
    BOOL full = rect[0] == 0 && rect[1] == 0 && (uint32_t)rect[2] == w && (uint32_t)rect[3] == h;

    MTLRenderPassDescriptor *desc = [MTLRenderPassDescriptor renderPassDescriptor];
    if (color) {
        desc.colorAttachments[0].texture = color;
        desc.colorAttachments[0].level = c->level;
        desc.colorAttachments[0].loadAction = full ? MTLLoadActionClear : MTLLoadActionLoad;
        desc.colorAttachments[0].clearColor = MTLClearColorMake(c->color[0], c->color[1], c->color[2], c->color[3]);
        desc.colorAttachments[0].storeAction = MTLStoreActionStore;
    }
    if (depth) {
        desc.depthAttachment.texture = depth;
        desc.depthAttachment.level = c->level;
        desc.depthAttachment.loadAction = full ? MTLLoadActionClear : MTLLoadActionLoad;
        desc.depthAttachment.clearDepth = c->depth;
        desc.depthAttachment.storeAction = MTLStoreActionStore;
        if (depthInfo.stencil) {
            desc.stencilAttachment.texture = depth;
            desc.stencilAttachment.level = c->level;
            desc.stencilAttachment.loadAction = MTLLoadActionLoad;
            desc.stencilAttachment.storeAction = MTLStoreActionStore;
        }
    }
    id<MTLRenderCommandEncoder> encoder = dec_render_encoder(ext, desc);
    if (!full) {
        /* A temporary decode context for the quad (its shadow state must not leak into a pass). */
        mcmtl_decode_ctx quad;
        memset(&quad, 0, sizeof quad);
        quad.renderEncoder = encoder;
        quad.scissor[2] = (int32_t)w;
        quad.scissor[3] = (int32_t)h;
        dec_set_scissor(&quad, ext, rect[0], rect[1], rect[2], rect[3]);
        uint32_t formats[8] = { color ? (uint32_t)color.pixelFormat : 0 };
        float colors[8][4];
        memset(colors, 0, sizeof colors);
        memcpy(colors[0], c->color, sizeof colors[0]);
        if (!dec_draw_clear_quad(&quad, ext, formats, color ? 1u : 0u, colors, depth ? (uint32_t)depth.pixelFormat : 0,
                                 depthInfo.stencil ? (uint32_t)depth.pixelFormat : 0, depth != nil, c->depth)) {
            DEC_MSG(ext, DEC_MSG_CLEAR, "CLEAR_REGION skipped (internal PSO unavailable)");
        }
    }
    [encoder endEncoding];
    return MCMTL_OK;
}

/* Overflow-safe a + b <= limit. */
static inline BOOL dec_fits(uint64_t a, uint64_t b, uint64_t limit) {
    return a <= limit && b <= limit - a;
}

static int32_t op_copy_buffer(mcmtl_decoder_ext *ext, const uint8_t *rec, uint64_t offset) {
    const mcmtl_cmd_copy_buffer *c = (const mcmtl_cmd_copy_buffer *)rec;
    if (!c->srcBuffer || !c->dstBuffer) return dec_fail(ext, MCMTL_E_BAD_STREAM, offset, "COPY_BUFFER with a null buffer");
    if (c->length == 0) return MCMTL_OK;
    id<MTLBuffer> src = MCMTL_BUFFER(c->srcBuffer);
    id<MTLBuffer> dst = MCMTL_BUFFER(c->dstBuffer);
    if (!dec_fits(c->srcOffset, c->length, src.length) || !dec_fits(c->dstOffset, c->length, dst.length)) {
        DEC_MSG(ext, DEC_MSG_COPY, "COPY_BUFFER out of range (src %llu+%llu of %lu, dst %llu of %lu); skipped",
                (unsigned long long)c->srcOffset, (unsigned long long)c->length, (unsigned long)src.length,
                (unsigned long long)c->dstOffset, (unsigned long)dst.length);
        return MCMTL_OK;
    }
    if (src == dst && c->srcOffset < c->dstOffset + c->length && c->dstOffset < c->srcOffset + c->length) {
        DEC_MSG(ext, DEC_MSG_COPY, "COPY_BUFFER with overlapping ranges in one buffer; skipped");
        return MCMTL_OK;
    }
    dec_access acc[2] = { dec_buffer_access(src, NO, c->srcOffset, c->length), dec_buffer_access(dst, YES, c->dstOffset, c->length) };
    if (!dec_copy_compute(ext, acc, src, c->srcOffset, dst, c->dstOffset, c->length)) {
        id<MTLBlitCommandEncoder> be = dec_blit(ext, acc, 2);
        [be copyFromBuffer:src sourceOffset:c->srcOffset toBuffer:dst destinationOffset:c->dstOffset size:c->length];
    }
    dec_icb_note_gpu_write(ext->dev, ext->ds, (__bridge const void *)dst);
    if (ext->ds->copyStats) dec_copy_note(ext->ds, dst, NO, dst.length, c->length);
    return MCMTL_OK;
}

/* Shared validation of COPY_BUFFER_TO_TEXTURE / COPY_TEXTURE_TO_BUFFER. Returns NO (and messages) when the
 * record must be skipped. *outBpp = bytes per pixel on the buffer side (0 if unknown). */
static BOOL dec_check_buffer_texture(mcmtl_decoder_ext *ext, const mcmtl_cmd_copy_buffer_texture *c, id<MTLBuffer> buffer,
                                     id<MTLTexture> texture, const char *what, uint32_t *outBpp, uint64_t *outSpan) {
    if (c->level >= texture.mipmapLevelCount || c->slice >= dec_slice_count(texture)) {
        DEC_MSG(ext, DEC_MSG_COPY, "%s level %u / slice %u out of range; skipped", what, c->level, c->slice);
        return NO;
    }
    uint32_t mw = dec_mip(texture.width, c->level), mh = dec_mip(texture.height, c->level);
    if ((uint64_t)c->x + c->width > mw || (uint64_t)c->y + c->height > mh) {
        DEC_MSG(ext, DEC_MSG_COPY, "%s rect (%u,%u %ux%u) outside level %u (%ux%u); skipped", what, c->x, c->y, c->width,
                c->height, c->level, mw, mh);
        return NO;
    }
    uint32_t bpp = dec_copy_bytes_per_pixel(texture.pixelFormat, c->blitOptions);
    uint64_t rowBytes = (uint64_t)c->width * (bpp ? bpp : 1);
    if (bpp && c->bytesPerRow < rowBytes) {
        DEC_MSG(ext, DEC_MSG_COPY, "%s bytesPerRow %u < row size %llu; skipped", what, c->bytesPerRow, (unsigned long long)rowBytes);
        return NO;
    }
    uint64_t span = (uint64_t)c->bytesPerRow * (c->height - 1) + rowBytes;
    if (!dec_fits(c->bufferOffset, span, buffer.length)) {
        DEC_MSG(ext, DEC_MSG_COPY, "%s buffer range %llu+%llu exceeds buffer length %lu; skipped", what,
                (unsigned long long)c->bufferOffset, (unsigned long long)span, (unsigned long)buffer.length);
        return NO;
    }
    *outBpp = bpp;
    *outSpan = span;
    return YES;
}

static int32_t op_copy_buffer_to_texture(mcmtl_decoder_ext *ext, const uint8_t *rec, uint64_t offset) {
    const mcmtl_cmd_copy_buffer_texture *c = (const mcmtl_cmd_copy_buffer_texture *)rec;
    if (!c->buffer || !c->texture) return dec_fail(ext, MCMTL_E_BAD_STREAM, offset, "COPY_BUFFER_TO_TEXTURE with a null handle");
    dec_pending_materialize_handle(ext, c->texture);
    if (c->width == 0 || c->height == 0) return MCMTL_OK;
    id<MTLBuffer> buffer = MCMTL_BUFFER(c->buffer);
    id<MTLTexture> texture = MCMTL_TEXTURE(c->texture);
    uint32_t bpp = 0;
    uint64_t span = 0;
    if (!dec_check_buffer_texture(ext, c, buffer, texture, "COPY_BUFFER_TO_TEXTURE", &bpp, &span)) return MCMTL_OK;
    if (ext->ds->copyStats) {
        dec_copy_note(ext->ds, texture, YES, (uint64_t)texture.width | (uint64_t)texture.height << 32, (uint64_t)c->bytesPerRow * c->height);
    }
    const MTLSize size = MTLSizeMake(c->width, c->height, 1);
    const MTLOrigin origin = MTLOriginMake(c->x, c->y, 0);
    dec_access texAccess = dec_texture_access(texture, YES, c->level, c->slice, c->x, c->y, c->width, c->height);
    if (bpp == 0 || ((c->bufferOffset % bpp) == 0 && (c->bytesPerRow % bpp) == 0)) {
        dec_access acc[2] = { dec_buffer_access(buffer, NO, c->bufferOffset, span), texAccess };
        id<MTLBlitCommandEncoder> be = dec_blit(ext, acc, 2);
        [be copyFromBuffer:buffer sourceOffset:c->bufferOffset sourceBytesPerRow:c->bytesPerRow
            sourceBytesPerImage:(NSUInteger)c->bytesPerRow * c->height sourceSize:size toTexture:texture
            destinationSlice:c->slice destinationLevel:c->level destinationOrigin:origin options:(MTLBlitOption)c->blitOptions];
        return MCMTL_OK;
    }
    /* Metal requires the source offset and row pitch to be multiples of the pixel size: repack the rows
     * into an aligned scratch range with buffer copies (no alignment rule), then upload from there. */
    const uint64_t rowBytes = (uint64_t)c->width * bpp;
    id<MTLBuffer> scratch = nil;
    NSUInteger scratchOffset = 0;
    if (!dec_ring_alloc(ext, (NSUInteger)(rowBytes * c->height), 16, &scratch, &scratchOffset)) {
        DEC_MSG(ext, DEC_MSG_COPY, "COPY_BUFFER_TO_TEXTURE: scratch allocation failed; skipped");
        return MCMTL_OK;
    }
    dec_access acc[2] = { dec_buffer_access(buffer, NO, c->bufferOffset, span),
                          dec_buffer_access(scratch, YES, scratchOffset, rowBytes * c->height) };
    id<MTLBlitCommandEncoder> be = dec_blit(ext, acc, 2);
    for (uint32_t row = 0; row < c->height; row++) {
        [be copyFromBuffer:buffer sourceOffset:c->bufferOffset + (uint64_t)row * c->bytesPerRow toBuffer:scratch
            destinationOffset:scratchOffset + row * rowBytes size:rowBytes];
    }
    dec_access acc2[2] = { dec_buffer_access(scratch, NO, scratchOffset, rowBytes * c->height), texAccess };
    be = dec_blit(ext, acc2, 2);
    [be copyFromBuffer:scratch sourceOffset:scratchOffset sourceBytesPerRow:rowBytes sourceBytesPerImage:rowBytes * c->height
        sourceSize:size toTexture:texture destinationSlice:c->slice destinationLevel:c->level destinationOrigin:origin
        options:(MTLBlitOption)c->blitOptions];
    return MCMTL_OK;
}

static int32_t op_copy_texture_to_buffer(mcmtl_decoder_ext *ext, const uint8_t *rec, uint64_t offset) {
    const mcmtl_cmd_copy_buffer_texture *c = (const mcmtl_cmd_copy_buffer_texture *)rec;
    if (!c->buffer || !c->texture) return dec_fail(ext, MCMTL_E_BAD_STREAM, offset, "COPY_TEXTURE_TO_BUFFER with a null handle");
    dec_pending_materialize_handle(ext, c->texture);
    if (c->width == 0 || c->height == 0) return MCMTL_OK;
    id<MTLBuffer> buffer = MCMTL_BUFFER(c->buffer);
    id<MTLTexture> texture = MCMTL_TEXTURE(c->texture);
    uint32_t bpp = 0;
    uint64_t span = 0;
    if (!dec_check_buffer_texture(ext, c, buffer, texture, "COPY_TEXTURE_TO_BUFFER", &bpp, &span)) return MCMTL_OK;
    dec_icb_note_gpu_write(ext->dev, ext->ds, (__bridge const void *)buffer);
    const MTLSize size = MTLSizeMake(c->width, c->height, 1);
    const MTLOrigin origin = MTLOriginMake(c->x, c->y, 0);
    dec_access texAccess = dec_texture_access(texture, NO, c->level, c->slice, c->x, c->y, c->width, c->height);
    if (bpp == 0 || ((c->bufferOffset % bpp) == 0 && (c->bytesPerRow % bpp) == 0)) {
        dec_access acc[2] = { texAccess, dec_buffer_access(buffer, YES, c->bufferOffset, span) };
        id<MTLBlitCommandEncoder> be = dec_blit(ext, acc, 2);
        [be copyFromTexture:texture sourceSlice:c->slice sourceLevel:c->level sourceOrigin:origin sourceSize:size
            toBuffer:buffer destinationOffset:c->bufferOffset destinationBytesPerRow:c->bytesPerRow
            destinationBytesPerImage:(NSUInteger)c->bytesPerRow * c->height options:(MTLBlitOption)c->blitOptions];
        return MCMTL_OK;
    }
    const uint64_t rowBytes = (uint64_t)c->width * bpp;
    id<MTLBuffer> scratch = nil;
    NSUInteger scratchOffset = 0;
    if (!dec_ring_alloc(ext, (NSUInteger)(rowBytes * c->height), 16, &scratch, &scratchOffset)) {
        DEC_MSG(ext, DEC_MSG_COPY, "COPY_TEXTURE_TO_BUFFER: scratch allocation failed; skipped");
        return MCMTL_OK;
    }
    dec_access acc[2] = { texAccess, dec_buffer_access(scratch, YES, scratchOffset, rowBytes * c->height) };
    id<MTLBlitCommandEncoder> be = dec_blit(ext, acc, 2);
    [be copyFromTexture:texture sourceSlice:c->slice sourceLevel:c->level sourceOrigin:origin sourceSize:size
        toBuffer:scratch destinationOffset:scratchOffset destinationBytesPerRow:rowBytes
        destinationBytesPerImage:rowBytes * c->height options:(MTLBlitOption)c->blitOptions];
    dec_access acc2[2] = { dec_buffer_access(scratch, NO, scratchOffset, rowBytes * c->height),
                           dec_buffer_access(buffer, YES, c->bufferOffset, span) };
    be = dec_blit(ext, acc2, 2);
    for (uint32_t row = 0; row < c->height; row++) {
        [be copyFromBuffer:scratch sourceOffset:scratchOffset + row * rowBytes toBuffer:buffer
            destinationOffset:c->bufferOffset + (uint64_t)row * c->bytesPerRow size:rowBytes];
    }
    return MCMTL_OK;
}

/* Nearest-texel draw of a source rect into a render-target destination (formats differ). */
static void dec_copy_draw(mcmtl_decoder_ext *ext, const mcmtl_cmd_copy_texture *c, id<MTLTexture> src, id<MTLTexture> dst) {
    dec_format sf = dec_format_info(src.pixelFormat);
    dec_format df = dec_format_info(dst.pixelFormat);
    if (!(src.usage & MTLTextureUsageShaderRead) || sf.kind == DEC_KIND_STENCIL || df.kind == DEC_KIND_STENCIL) {
        DEC_MSG(ext, DEC_MSG_COPY, "COPY_TEXTURE draw: unsupported formats %lu -> %lu or source not readable; skipped",
                (unsigned long)src.pixelFormat, (unsigned long)dst.pixelFormat);
        return;
    }
    id<MTLTexture> view = src;
    if (src.textureType != MTLTextureType2D || c->srcSlice != 0) {
        view = [src newTextureViewWithPixelFormat:src.pixelFormat textureType:MTLTextureType2D
                                           levels:NSMakeRange(0, src.mipmapLevelCount) slices:NSMakeRange(c->srcSlice, 1)];
        if (!view) {
            DEC_MSG(ext, DEC_MSG_COPY, "COPY_TEXTURE draw: source view failed; skipped");
            return;
        }
    }
    uint32_t key[16] = { DEC_PSO_COPY };
    key[1] = df.depth ? 0 : (uint32_t)dst.pixelFormat;
    key[2] = df.depth ? (uint32_t)dst.pixelFormat : 0;
    key[3] = df.stencil ? (uint32_t)dst.pixelFormat : 0;
    key[4] = sf.kind;
    id<MTLRenderPipelineState> pso = dec_copy_pso(ext->dev, ext->ds, key, &ext->messaged);
    if (!pso) return;
    MTLRenderPassDescriptor *desc = [MTLRenderPassDescriptor renderPassDescriptor];
    if (df.depth) {
        desc.depthAttachment.texture = dst;
        desc.depthAttachment.level = c->dstLevel;
        desc.depthAttachment.slice = c->dstSlice;
        desc.depthAttachment.loadAction = MTLLoadActionLoad;
        desc.depthAttachment.storeAction = MTLStoreActionStore;
        if (df.stencil) {
            desc.stencilAttachment.texture = dst;
            desc.stencilAttachment.level = c->dstLevel;
            desc.stencilAttachment.slice = c->dstSlice;
            desc.stencilAttachment.loadAction = MTLLoadActionLoad;
            desc.stencilAttachment.storeAction = MTLStoreActionStore;
        }
    } else {
        desc.colorAttachments[0].texture = dst;
        desc.colorAttachments[0].level = c->dstLevel;
        desc.colorAttachments[0].slice = c->dstSlice;
        desc.colorAttachments[0].loadAction = MTLLoadActionLoad;
        desc.colorAttachments[0].storeAction = MTLStoreActionStore;
    }
    id<MTLRenderCommandEncoder> encoder = dec_render_encoder(ext, desc);
    [encoder setRenderPipelineState:pso];
    if (df.depth) [encoder setDepthStencilState:ext->ds->alwaysWriteDSS];
    [encoder setScissorRect:(MTLScissorRect){ c->dstX, c->dstY, c->width, c->height }];
    dec_quad_params params;
    memset(&params, 0, sizeof params);
    params.offset[0] = (int32_t)c->srcX - (int32_t)c->dstX;
    params.offset[1] = (int32_t)c->srcY - (int32_t)c->dstY;
    params.level = c->srcLevel;
    [encoder setVertexBytes:&params length:sizeof params atIndex:0];
    [encoder setFragmentBytes:&params length:sizeof params atIndex:0];
    [encoder setFragmentTexture:view atIndex:0];
    [encoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
    [encoder endEncoding];
    ext->ds->stats.draws++;
}

static int32_t op_copy_texture(mcmtl_decoder_ext *ext, const uint8_t *rec, uint64_t offset) {
    const mcmtl_cmd_copy_texture *c = (const mcmtl_cmd_copy_texture *)rec;
    if (!c->srcTexture || !c->dstTexture) return dec_fail(ext, MCMTL_E_BAD_STREAM, offset, "COPY_TEXTURE with a null texture");
    dec_pending_materialize_handle(ext, c->srcTexture);
    dec_pending_materialize_handle(ext, c->dstTexture);
    if (c->width == 0 || c->height == 0) return MCMTL_OK;
    id<MTLTexture> src = MCMTL_TEXTURE(c->srcTexture);
    id<MTLTexture> dst = MCMTL_TEXTURE(c->dstTexture);
    if (c->srcLevel >= src.mipmapLevelCount || c->dstLevel >= dst.mipmapLevelCount || c->srcSlice >= dec_slice_count(src) ||
        c->dstSlice >= dec_slice_count(dst) ||
        (uint64_t)c->srcX + c->width > dec_mip(src.width, c->srcLevel) || (uint64_t)c->srcY + c->height > dec_mip(src.height, c->srcLevel) ||
        (uint64_t)c->dstX + c->width > dec_mip(dst.width, c->dstLevel) || (uint64_t)c->dstY + c->height > dec_mip(dst.height, c->dstLevel)) {
        DEC_MSG(ext, DEC_MSG_COPY, "COPY_TEXTURE region out of range; skipped");
        return MCMTL_OK;
    }
    BOOL sameSub = src == dst && c->srcLevel == c->dstLevel && c->srcSlice == c->dstSlice;
    if (sameSub && c->srcX < c->dstX + c->width && c->dstX < c->srcX + c->width && c->srcY < c->dstY + c->height &&
        c->dstY < c->srcY + c->height) {
        DEC_MSG(ext, DEC_MSG_COPY, "COPY_TEXTURE with overlapping regions of one texture; skipped");
        return MCMTL_OK;
    }
    if ((c->flags & MCMTL_COPY_FLAG_DRAW) || src.pixelFormat != dst.pixelFormat) {
        if (!(dst.usage & MTLTextureUsageRenderTarget) || src == dst) {
            DEC_MSG(ext, DEC_MSG_COPY, "COPY_TEXTURE between formats %lu and %lu needs a distinct render-target destination; skipped",
                    (unsigned long)src.pixelFormat, (unsigned long)dst.pixelFormat);
            return MCMTL_OK;
        }
        dec_end_blit(ext);
        dec_copy_draw(ext, c, src, dst);
        return MCMTL_OK;
    }
    dec_access acc[2] = { dec_texture_access(src, NO, c->srcLevel, c->srcSlice, c->srcX, c->srcY, c->width, c->height),
                          dec_texture_access(dst, YES, c->dstLevel, c->dstSlice, c->dstX, c->dstY, c->width, c->height) };
    id<MTLBlitCommandEncoder> be = dec_blit(ext, acc, 2);
    [be copyFromTexture:src sourceSlice:c->srcSlice sourceLevel:c->srcLevel sourceOrigin:MTLOriginMake(c->srcX, c->srcY, 0)
             sourceSize:MTLSizeMake(c->width, c->height, 1) toTexture:dst destinationSlice:c->dstSlice
       destinationLevel:c->dstLevel destinationOrigin:MTLOriginMake(c->dstX, c->dstY, 0)];
    return MCMTL_OK;
}

/* Grows the zero buffer to at least `bytes`. */
static BOOL dec_zero_buffer(mcmtl_decoder_ext *ext, NSUInteger bytes) {
    MCMTLDecoderState *ds = ext->ds;
    if (ds->zeroBuffer.length >= bytes) return YES;
    NSUInteger length = (bytes + 65535u) & ~(NSUInteger)65535u;
    id<MTLBuffer> buffer = [ext->dev->device newBufferWithLength:length options:MTLResourceStorageModeShared];
    if (!buffer) return NO;
    memset(buffer.contents, 0, length);
    buffer.label = @"mcmetal zero buffer";
    ds->zeroBuffer = buffer;   /* the old one stays alive through the command buffers that use it */
    return YES;
}

static int32_t op_init_texture(mcmtl_decoder_ext *ext, const uint8_t *rec, uint64_t offset) {
    const mcmtl_cmd_init_texture *c = (const mcmtl_cmd_init_texture *)rec;
    if (!c->texture) return dec_fail(ext, MCMTL_E_BAD_STREAM, offset, "INIT_TEXTURE without texture");
    if (c->mode != MCMTL_INIT_TEXTURE_ZERO) return dec_fail(ext, MCMTL_E_BAD_STREAM, offset, "INIT_TEXTURE mode %u unknown", c->mode);
    dec_pending_materialize_handle(ext, c->texture);
    id<MTLTexture> t = MCMTL_TEXTURE(c->texture);
    dec_format f = dec_format_info(t.pixelFormat);
    const uint32_t levels = (uint32_t)t.mipmapLevelCount, slices = dec_slice_count(t);
    if (t.usage & MTLTextureUsageRenderTarget) {
        static const float zero[4] = {0, 0, 0, 0};
        for (uint32_t level = 0; level < levels; level++) {
            for (uint32_t slice = 0; slice < slices; slice++) {
                dec_clear_level(ext, t, level, slice, YES, zero, YES, 0.0, YES);
            }
        }
        return MCMTL_OK;
    }
    if (!f.bytes || t.textureType == MTLTextureType3D) {
        DEC_MSG(ext, DEC_MSG_INIT, "INIT_TEXTURE: pixel format %lu / type %lu not supported; skipped",
                (unsigned long)t.pixelFormat, (unsigned long)t.textureType);
        return MCMTL_OK;
    }
    /* Blit zeros in row bands, one pass per plane of combined depth/stencil formats. */
    uint32_t planes[2] = { MTLBlitOptionNone, MTLBlitOptionNone };
    uint32_t planeCount = 1;
    if (f.depth && f.stencil) {
        planes[0] = MTLBlitOptionDepthFromDepthStencil;
        planes[1] = MTLBlitOptionStencilFromDepthStencil;
        planeCount = 2;
    }
    for (uint32_t plane = 0; plane < planeCount; plane++) {
        uint32_t bpp = dec_copy_bytes_per_pixel(t.pixelFormat, planes[plane]);
        for (uint32_t level = 0; level < levels; level++) {
            uint32_t w = dec_mip(t.width, level), h = dec_mip(t.height, level);
            NSUInteger rowBytes = (NSUInteger)w * bpp;
            if (!dec_zero_buffer(ext, rowBytes)) {
                DEC_MSG(ext, DEC_MSG_INIT, "INIT_TEXTURE: zero buffer allocation failed; skipped");
                return MCMTL_OK;
            }
            id<MTLBuffer> zero = ext->ds->zeroBuffer;
            uint32_t rowsPerBand = (uint32_t)(zero.length / rowBytes);
            if (rowsPerBand > h) rowsPerBand = h;
            for (uint32_t slice = 0; slice < slices; slice++) {
                for (uint32_t y = 0; y < h; y += rowsPerBand) {
                    uint32_t rows = h - y < rowsPerBand ? h - y : rowsPerBand;
                    /* Bands are disjoint regions: they share one blit encoder; a later upload into the
                     * texture overlaps them and splits the encoder (CONTRACT.md 4.5). */
                    dec_access acc[2] = { dec_buffer_access(zero, NO, 0, (NSUInteger)rowBytes * rows),
                                          dec_texture_access(t, YES, level, slice, 0, y, w, rows) };
                    id<MTLBlitCommandEncoder> be = dec_blit(ext, acc, 2);
                    [be copyFromBuffer:zero sourceOffset:0 sourceBytesPerRow:rowBytes sourceBytesPerImage:rowBytes * rows
                            sourceSize:MTLSizeMake(w, rows, 1) toTexture:t destinationSlice:slice destinationLevel:level
                     destinationOrigin:MTLOriginMake(0, y, 0) options:(MTLBlitOption)planes[plane]];
                }
            }
        }
    }
    return MCMTL_OK;
}

/* presentDrawable on the call's last command buffer (MCMTL_PRESENT_MODE: 0 presentDrawable:, 1 scheduled-handler
 * present, 2 afterMinimumDuration:0, 3 scheduled present re-asserting the sync mode like MoltenVK). */
static void dec_register_present(mcmtl_decoder_ext *ext, id<CAMetalDrawable> drawable, CAMetalLayer *layer) {
    static int presentMode = -1;
    if (presentMode < 0) {
        const char *pm = getenv("MCMTL_PRESENT_MODE");
        presentMode = pm ? atoi(pm) : 0;
    }
    if (presentMode == 1) {
        id<CAMetalDrawable> d = drawable;
        [dec_cb(ext) addScheduledHandler:^(id<MTLCommandBuffer> unused) { [d present]; }];
    } else if (presentMode == 3) {
        /* MoltenVK-style: present from the scheduled handler and re-assert the sync mode per present. */
        id<CAMetalDrawable> d = drawable;
        CAMetalLayer *l = layer;
        BOOL sync = l.displaySyncEnabled;
        [dec_cb(ext) addScheduledHandler:^(id<MTLCommandBuffer> unused) {
            ((CAMetalLayer *)d.layer).displaySyncEnabled = sync;
            [d present];
        }];
    } else if (presentMode == 2) {
        [dec_cb(ext) presentDrawable:drawable afterMinimumDuration:0.0];
    } else {
        [dec_cb(ext) presentDrawable:drawable];
    }
}

/* DEC_SKIP_* for a MCMTL_MAILBOX_* result of mcmtl_mailbox_take. */
static inline uint32_t dec_skip_reason(uint32_t why) {
    return why == MCMTL_MAILBOX_QUEUE_FULL ? DEC_SKIP_QUEUE_FULL : why == MCMTL_MAILBOX_STALE ? DEC_SKIP_STALE : DEC_SKIP_NO_DRAWABLE;
}

static int32_t op_present(mcmtl_decode_ctx *ctx, mcmtl_decoder_ext *ext, const uint8_t *rec, uint64_t offset) {
    const mcmtl_cmd_present *c = (const mcmtl_cmd_present *)rec;
    if (!c->surface || !c->texture) return dec_fail(ext, MCMTL_E_BAD_STREAM, offset, "PRESENT with a null handle");
    MCMTLSurface *surface = MCMTL_SURFACE(c->surface);
    id<MTLTexture> src = MCMTL_TEXTURE(c->texture);
    MCMTLDevice *dev = ext->dev;
    MCMTLDecoderState *ds = ext->ds;
    CAMetalLayer *layer = surface->layer;
    if (ext->mergeOpen) dec_merge_break(ext);
    /* One PRESENT = one frame rendered for the surface, presented or not (the pass profile's frame unit too). */
    ds->stats.presentRecords++;
    ds->presentSurface = surface;
    const BOOL mailbox = mcmtl_mailbox_active(surface);
    if (mailbox) ds->stats.mailboxRecords++;
    if (dec_profile_enabled()) dec_profile_frame();

    /* Present fusion (CONTRACT.md 16.3): the frame is already in the drawable. Nothing reads or stores the source;
     * it stays stale until the next call overwrites it or restores it from the drawable. */
    if (ext->fuseState == DEC_FUSE_ACTIVE && offset == ext->fusePresent) {
        dec_end_blit(ext);
        id<CAMetalDrawable> drawable = ext->fuseDrawable;
        ext->fuseDrawable = nil;
        ext->fuseState = DEC_FUSE_DONE;
        surface->drawable = drawable;
        id<MTLTexture> target = drawable.texture;
        ext->result->drawableWidth = (uint32_t)target.width;
        ext->result->drawableHeight = (uint32_t)target.height;
        if ((uint32_t)target.width != surface->configuredWidth || (uint32_t)target.height != surface->configuredHeight) {
            ext->result->flags |= MCMTL_SUBMIT_RESULT_FLAG_DRAWABLE_RESIZED;
        }
        mcmtl_mailbox_will_present(surface, drawable);
        dec_register_present(ext, drawable, layer);
        ext->result->flags |= MCMTL_SUBMIT_RESULT_FLAG_PRESENT_ENCODED;
        surface->presentedFrames++;
        ds->stats.presentsEncoded++;
        ds->stats.fusedFrames++;
        ds->staleTexture = ext->fuseTex;
        ds->staleLevel = ext->fuseLevel;
        ds->staleDrawable = drawable;
        ds->staleValue = ext->signalValue;
        return MCMTL_OK;
    }
    const uint32_t fuseState = ext->fuseState;
    if (ds->fuseMode) {
        uint32_t reason = DEC_FUSE_R_NO_INDEX;
        if (fuseState == DEC_FUSE_FAILED || fuseState == DEC_FUSE_NIL || fuseState == DEC_FUSE_SKIP) reason = ext->fuseReason;
        else if (fuseState == DEC_FUSE_PLANNED || fuseState == DEC_FUSE_ACTIVE) reason = DEC_FUSE_R_NOT_REACHED;
        ds->stats.fusionFallback[reason]++;
    }
    /* A drawable acquired before a planned pass that could not use it (size / format) is used here; nil stays nil. */
    id<CAMetalDrawable> drawable = ext->fuseDrawable;
    const BOOL acquired = drawable != nil || fuseState == DEC_FUSE_NIL;
    ext->fuseDrawable = nil;
    ext->fuseState = DEC_FUSE_DONE;
    dec_pending_materialize_handle(ext, c->texture);
    dec_end_blit(ext);

    if (!acquired && mailbox) {
        /* Mailbox (CONTRACT.md 16.4): take a parked drawable or do not present this frame (it was rendered completely;
         * the display keeps the previous one). Never DRAWABLE_NIL: nothing is wrong with the surface. A frame whose
         * fusion pass found no drawable leaves one that arrived since for the next frame, which renders straight into
         * it, unless MCMTL_MAILBOX_LATE_PRESENT=1 (present pass now). */
        uint32_t reason;
        if (fuseState == DEC_FUSE_SKIP && !ds->mailboxLatePresent) {
            /* Deferred = no drawable at the fusion pass, one parked since (a closed queue gate stays what it was). */
            reason = ext->mailboxWhy == MCMTL_MAILBOX_NONE_READY && mcmtl_mailbox_ready(surface) ? DEC_SKIP_DEFERRED
                                                                                               : dec_skip_reason(ext->mailboxWhy);
        } else {
            uint32_t why = MCMTL_MAILBOX_NONE_READY;
            drawable = mcmtl_mailbox_take(surface, &why);
            reason = dec_skip_reason(why);
        }
        if (!drawable) {
            ds->stats.presentsSkipped++;
            ds->stats.presentSkip[reason]++;
            return MCMTL_OK;
        }
    } else if (!acquired) {
        /* Early commit (CONTRACT.md 8.2): the frame's work runs while nextDrawable may block. */
        dec_commit_early(ext, NO);
        if (layer) {
            uint64_t waitStart = clock_gettime_nsec_np(CLOCK_UPTIME_RAW);
            @autoreleasepool {
                drawable = [layer nextDrawable];
            }
            ds->stats.drawableWaitNs += clock_gettime_nsec_np(CLOCK_UPTIME_RAW) - waitStart;
        }
    }
    if (!drawable) {
        ext->result->flags |= MCMTL_SUBMIT_RESULT_FLAG_DRAWABLE_NIL;
        ds->stats.presentsNil++;
        DEC_MSG(ext, DEC_MSG_PRESENT, "nextDrawable returned nil; present skipped");
        return MCMTL_OK;
    }
    surface->drawable = drawable;
    id<MTLTexture> target = drawable.texture;
    const uint32_t dw = (uint32_t)target.width, dh = (uint32_t)target.height;
    ext->result->drawableWidth = dw;
    ext->result->drawableHeight = dh;
    if (dw != surface->configuredWidth || dh != surface->configuredHeight) {
        ext->result->flags |= MCMTL_SUBMIT_RESULT_FLAG_DRAWABLE_RESIZED;
    }
    BOOL sourceOk = c->level < src.mipmapLevelCount && (src.usage & MTLTextureUsageShaderRead) &&
                    dec_format_info(src.pixelFormat).kind == DEC_KIND_FLOAT;
    id<MTLRenderPipelineState> pso = sourceOk ? dec_present_pso(dev, ext->ds, target.pixelFormat, &ext->messaged) : nil;
    uint32_t copyW = 0, copyH = 0;
    if (pso) {
        uint32_t sw = dec_mip(src.width, c->level), sh = dec_mip(src.height, c->level);
        copyW = sw < dw ? sw : dw;
        copyH = sh < dh ? sh : dh;
    } else {
        DEC_MSG(ext, DEC_MSG_PRESENT, "PRESENT source (format %lu, level %u) cannot be presented; drawable cleared",
                (unsigned long)src.pixelFormat, c->level);
    }
    MTLRenderPassDescriptor *desc = [MTLRenderPassDescriptor renderPassDescriptor];
    desc.colorAttachments[0].texture = target;
    desc.colorAttachments[0].loadAction = (pso && copyW == dw && copyH == dh) ? MTLLoadActionDontCare : MTLLoadActionClear;
    desc.colorAttachments[0].clearColor = MTLClearColorMake(0, 0, 0, 1);
    desc.colorAttachments[0].storeAction = MTLStoreActionStore;
    g_prof_tag = "present";
    id<MTLRenderCommandEncoder> encoder = dec_render_encoder(ext, desc);
    if (pso) {
        /* Top-anchored copy. Upright layer: drawable row y (from the top) reads source row copyH - 1 - y. Flipped
         * layer (present fusion: memory row 0 shown at the bottom): the copy keeps the source's memory order and
         * fills drawable rows [dh - copyH, dh), the top of the window. */
        const BOOL flipped = mcmtl_layer_is_flipped(layer);
        const uint32_t y0 = flipped ? dh - copyH : 0;
        dec_present_params params = { flipped ? -(int32_t)y0 : (int32_t)copyH - 1, flipped ? 1 : -1, c->level, 0 };
        [encoder setRenderPipelineState:pso];
        [encoder setViewport:(MTLViewport){ 0.0, (double)y0, (double)copyW, (double)copyH, 0.0, 1.0 }];
        [encoder setScissorRect:(MTLScissorRect){ 0, y0, copyW, copyH }];
        [encoder setFragmentTexture:src atIndex:0];
        [encoder setFragmentBytes:&params length:sizeof params atIndex:0];
        [encoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
    }
    [encoder endEncoding];
    mcmtl_mailbox_will_present(surface, drawable);
    dec_register_present(ext, drawable, layer);
    ext->result->flags |= MCMTL_SUBMIT_RESULT_FLAG_PRESENT_ENCODED;
    surface->presentedFrames++;
    ds->stats.presentsEncoded++;
    return MCMTL_OK;
}

/* ---------------------------------------------------------------------------------------------- */
/* Records valid anywhere                                                                           */
/* ---------------------------------------------------------------------------------------------- */

static int32_t op_timestamp(mcmtl_decode_ctx *ctx, mcmtl_decoder_ext *ext, const uint8_t *rec, uint64_t offset) {
    if (dec_ts_handled(ext, offset)) return MCMTL_OK;
    const mcmtl_cmd_timestamp *c = (const mcmtl_cmd_timestamp *)rec;
    dec_ts ts;
    int r = dec_ts_resolve(ext, c, &ts);
    if (r < 0) return dec_fail(ext, MCMTL_E_BAD_STREAM, offset, "TIMESTAMP without query pool");
    if (r == 0) return MCMTL_OK;
    if (ctx->inPass) {
        /* Not attached at BEGIN_PASS (attachment slots exhausted): sample right after the pass. */
        if (ext->endOverflowCount < DEC_MAX_PASS_TS) ext->endOverflow[ext->endOverflowCount++] = ts;
        return MCMTL_OK;
    }
    if (!ext->isInit && dec_ts_precedes_pass(ext->stream, ext->streamLength, offset) && ext->startTsCount < DEC_MAX_PASS_TS) {
        ext->startTs[ext->startTsCount++] = ts;   /* startOfVertex of the next pass */
        return MCMTL_OK;
    }
    dec_sample_standalone(ext, &ts);
    return MCMTL_OK;
}

static NSString *dec_label(const uint8_t *bytes, uint32_t length) {
    NSString *label = [[NSString alloc] initWithBytes:bytes length:length encoding:NSUTF8StringEncoding];
    return label ? label : @"(invalid UTF-8 label)";
}

static int32_t op_push_debug_group(mcmtl_decode_ctx *ctx, mcmtl_decoder_ext *ext, const uint8_t *rec, uint32_t size, uint64_t offset) {
    const mcmtl_cmd_push_debug_group *c = (const mcmtl_cmd_push_debug_group *)rec;
    if (c->labelLength > MCMTL_MAX_LABEL_BYTES || (uint64_t)sizeof *c + c->labelLength > size) {
        return dec_fail(ext, MCMTL_E_BAD_STREAM, offset, "PUSH_DEBUG_GROUP label length %u does not fit record size %u",
                        c->labelLength, size);
    }
    NSString *label = dec_label(rec + sizeof *c, c->labelLength);
    if (ctx->inPass) {
        [ctx->renderEncoder pushDebugGroup:label];
        ext->encoderGroupDepth++;
    } else if (ext->mergeOpen) {
        /* Between merged passes: an encoder group (re-pushed on the command buffer when the encoder ends). */
        [ext->renderEncoder pushDebugGroup:label];
        ext->mergeGroupDepth++;
        [ext->ds->labelStack addObject:label];
    } else {
        dec_end_blit(ext);
        [dec_cb(ext) pushDebugGroup:label];
        ext->cbGroupDepth++;
        [ext->ds->labelStack addObject:label];
    }
    return MCMTL_OK;
}

static int32_t op_pop_debug_group(mcmtl_decode_ctx *ctx, mcmtl_decoder_ext *ext) {
    if (ctx->inPass) {
        if (ext->encoderGroupDepth) {
            [ctx->renderEncoder popDebugGroup];
            ext->encoderGroupDepth--;
        }
    } else if (ext->mergeOpen) {
        /* Between merged passes: pop an encoder group pushed between them, else owe the command buffer a pop
         * (its group encloses the open encoder; it is popped when the encoder ends). */
        if (ext->mergeGroupDepth) {
            [ext->renderEncoder popDebugGroup];
            ext->mergeGroupDepth--;
            [ext->ds->labelStack removeLastObject];
        } else if (ext->cbGroupDepth > ext->deferredCbPops) {
            ext->deferredCbPops++;
            [ext->ds->labelStack removeLastObject];
        }
    } else if (ext->cbGroupDepth) {
        dec_end_blit(ext);
        [ext->commandBuffer popDebugGroup];
        ext->cbGroupDepth--;
        [ext->ds->labelStack removeLastObject];
    }
    return MCMTL_OK;
}

/* ---------------------------------------------------------------------------------------------- */
/* Pass-level records                                                                               */
/* ---------------------------------------------------------------------------------------------- */

static id<MTLRenderPipelineState> dec_variant(mcmtl_decoder_ext *ext, MCMTLPipeline *p, uint64_t key) {
    id<MTLRenderPipelineState> pso = mcmtl_pipeline_find_variant(p, key);
    if (pso) return pso;
    MCMTLDecoderState *ds = ext->ds;
    NSNumber *keyNumber = @(key);
    NSMutableSet<NSNumber *> *failed = [ds->failedVariants objectForKey:p];
    if (failed && [failed containsObject:keyNumber]) return nil;
    pso = mcmtl_pipeline_variant(p, key);
    if (!pso) {
        if (!failed) {
            failed = [NSMutableSet set];
            [ds->failedVariants setObject:failed forKey:p];
        }
        [failed addObject:keyNumber];
        DEC_MSG(ext, DEC_MSG_VARIANT, "pipeline '%s': no PSO variant for color mask 0x%llx depth format %llu%s; its draws are skipped",
                p->label ? p->label.UTF8String : "?", (unsigned long long)(key & 0xFF),
                (unsigned long long)((key & ~MCMTL_VARIANT_KEY_BGRA8_COLOR0) >> 8),
                (key & MCMTL_VARIANT_KEY_BGRA8_COLOR0) ? " (BGRA8 color 0)" : "");
    }
    return pso;
}

static int32_t op_set_pipeline(mcmtl_decode_ctx *ctx, mcmtl_decoder_ext *ext, const uint8_t *rec) {
    const mcmtl_cmd_set_pipeline *c = (const mcmtl_cmd_set_pipeline *)rec;
    ctx->bufferSetMask[0] = ctx->bufferSetMask[1] = 0;
    ctx->textureSetMask[0] = ctx->textureSetMask[1] = 0;
    MCMTLPipeline *p = c->pipeline ? MCMTL_PIPELINE(c->pipeline) : nil;
    if (ext->occlActive && ctx->renderEncoder) {
        dec_occl_before_state_change(ctx, ext, dec_occl_class(ext, p) == DEC_OCCL_CLASS_OPAQUE);
    }
    ctx->pipeline = p;
    if (!p) {
        ext->pipelineInvalid = YES;
        DEC_MSG(ext, DEC_MSG_NO_PIPELINE, "SET_PIPELINE with a null pipeline; draws skipped");
        return MCMTL_OK;
    }
    uint64_t key = mcmtl_variant_key(ctx->passColorPresentMask, ctx->passDepthFormat);
    if (ext->encFused) key |= MCMTL_VARIANT_KEY_BGRA8_COLOR0;   /* color 0 is the BGRA8 drawable (present fusion) */
    id<MTLRenderPipelineState> pso = dec_variant(ext, p, key);
    if (!pso) {
        ext->pipelineInvalid = YES;
        return MCMTL_OK;
    }
    ext->pipelineInvalid = NO;
    ext->psoKey = key;
    ext->basePso = pso;
    if ((p->bufferMask[1] & (1u << 30)) && (ext->vertexBoundMask & (1u << 30)) && ctx->buffers[0][30]) {
        /* Lit-merge LOD fragments (TerrainVertexMsl.litFragment) read vertex buffer 30 too: carry the bound one over. */
        dec_bind_buffer(ctx, ext->ds, 1, 30, ctx->buffers[0][30], ctx->bufferOffsets[0][30]);
        ctx->bufferSetMask[1] |= 1u << 30;
    }
    MCMTLDecoderState *ds = ext->ds;
    __unsafe_unretained id<MTLRenderCommandEncoder> re = ctx->renderEncoder;
    if (pso != ctx->pso) {
        [re setRenderPipelineState:pso];
        ctx->pso = pso;
        ds->stats.setPipelineState++;
    }
    ext->icbPsoBound = NO;
    /* Integration fix: a pass without a depth attachment always gets the Always/no-write state. Binding the
     * pipeline's depth-testing state there aborts under MTL_DEBUG_LAYER=1 ("sets depth test but ... nil
     * depthAttachment"); Vulkan simply has no depth test without a depth attachment. */
    __unsafe_unretained id<MTLDepthStencilState> dss =
        (p->depthStencilState && ctx->passDepthFormat != 0) ? p->depthStencilState : ds->defaultDSS;
    if (dss != ctx->depthStencilState) {
        [re setDepthStencilState:dss];
        ctx->depthStencilState = dss;
        ds->stats.setDepthStencilState++;
    }
    if (p->cullMode != ctx->cullMode) {
        [re setCullMode:(MTLCullMode)p->cullMode];
        ctx->cullMode = p->cullMode;
        ds->stats.setRasterState++;
    }
    if (p->frontFacing != ctx->frontFacing) {
        [re setFrontFacingWinding:(MTLWinding)p->frontFacing];
        ctx->frontFacing = p->frontFacing;
        ds->stats.setRasterState++;
    }
    if (p->fillMode != ctx->fillMode) {
        [re setTriangleFillMode:(MTLTriangleFillMode)p->fillMode];
        ctx->fillMode = p->fillMode;
        ds->stats.setRasterState++;
    }
    if (p->depthBiasConstant != ctx->depthBias[0] || p->depthBiasSlopeScale != ctx->depthBias[1] ||
        p->depthBiasClamp != ctx->depthBias[2]) {
        [re setDepthBias:p->depthBiasConstant slopeScale:p->depthBiasSlopeScale clamp:p->depthBiasClamp];
        ctx->depthBias[0] = p->depthBiasConstant;
        ctx->depthBias[1] = p->depthBiasSlopeScale;
        ctx->depthBias[2] = p->depthBiasClamp;
        ds->stats.setRasterState++;
    }
    return MCMTL_OK;
}

static inline void dec_bind_buffer(mcmtl_decode_ctx *ctx, MCMTLDecoderState *ds, uint32_t stage, uint32_t index,
                                   __unsafe_unretained id<MTLBuffer> buffer, uint64_t offset) {
    __unsafe_unretained id<MTLRenderCommandEncoder> re = ctx->renderEncoder;
    if (ctx->buffers[stage][index] == buffer) {
        if (ctx->bufferOffsets[stage][index] != offset) {
            if (stage == 0) [re setVertexBufferOffset:offset atIndex:index];
            else [re setFragmentBufferOffset:offset atIndex:index];
            ctx->bufferOffsets[stage][index] = offset;
            ds->stats.setBufferOffset++;
        }
        return;
    }
    if (stage == 0) [re setVertexBuffer:buffer offset:offset atIndex:index];
    else [re setFragmentBuffer:buffer offset:offset atIndex:index];
    ctx->buffers[stage][index] = buffer;
    ctx->bufferOffsets[stage][index] = offset;
    ds->stats.setBuffer++;
}

static inline void dec_bind_texture(mcmtl_decode_ctx *ctx, MCMTLDecoderState *ds, uint32_t stage, uint32_t index,
                                    __unsafe_unretained id<MTLTexture> texture) {
    if (ctx->textures[stage][index] == texture) return;
    if (stage == 0) [ctx->renderEncoder setVertexTexture:texture atIndex:index];
    else [ctx->renderEncoder setFragmentTexture:texture atIndex:index];
    ctx->textures[stage][index] = texture;
    ds->stats.setTexture++;
}

static inline void dec_bind_sampler(mcmtl_decode_ctx *ctx, MCMTLDecoderState *ds, uint32_t stage, uint32_t index,
                                    __unsafe_unretained id<MTLSamplerState> sampler) {
    if (ctx->samplers[stage][index] == sampler) return;
    if (stage == 0) [ctx->renderEncoder setVertexSamplerState:sampler atIndex:index];
    else [ctx->renderEncoder setFragmentSamplerState:sampler atIndex:index];
    ctx->samplers[stage][index] = sampler;
    ds->stats.setSampler++;
}

static int32_t op_set_buffer(mcmtl_decode_ctx *ctx, mcmtl_decoder_ext *ext, const uint8_t *rec, uint64_t offset) {
    const mcmtl_cmd_set_buffer *c = (const mcmtl_cmd_set_buffer *)rec;
    if (c->index > MCMTL_PUSH_CONSTANT_BUFFER_INDEX) {
        return dec_fail(ext, MCMTL_E_BAD_STREAM, offset, "SET_BUFFER index %u > %u", c->index, MCMTL_PUSH_CONSTANT_BUFFER_INDEX);
    }
    const uint32_t bit = 1u << c->index;
    __unsafe_unretained id<MTLBuffer> buffer = c->buffer ? MCMTL_BUFFER(c->buffer) : nil;
    if (buffer && c->offset >= dec_buffer_length(ext, buffer)) {
        DEC_MSG(ext, DEC_MSG_BINDING, "SET_BUFFER offset %llu >= buffer length %llu; binding treated as unset",
                (unsigned long long)c->offset, (unsigned long long)dec_buffer_length(ext, buffer));
        buffer = nil;
    }
    for (uint32_t stage = 0; stage < 2; stage++) {
        if (!(c->stages & (1u << stage))) continue;
        if (!buffer) {
            ctx->bufferSetMask[stage] &= ~bit;
            continue;
        }
        dec_bind_buffer(ctx, ext->ds, stage, c->index, buffer, c->offset);
        ctx->bufferSetMask[stage] |= bit;
    }
    return MCMTL_OK;
}

static int32_t op_set_texture(mcmtl_decode_ctx *ctx, mcmtl_decoder_ext *ext, const uint8_t *rec, uint64_t offset) {
    const mcmtl_cmd_set_texture *c = (const mcmtl_cmd_set_texture *)rec;
    if (c->index >= MCMTL_MAX_TEXTURE_BINDINGS) {
        return dec_fail(ext, MCMTL_E_BAD_STREAM, offset, "SET_TEXTURE index %u >= %u", c->index, MCMTL_MAX_TEXTURE_BINDINGS);
    }
    const uint32_t bit = 1u << c->index;
    __unsafe_unretained id<MTLTexture> texture = c->texture ? MCMTL_TEXTURE(c->texture) : nil;
    __unsafe_unretained id<MTLSamplerState> sampler = c->sampler ? MCMTL_SAMPLER(c->sampler) : nil;
    for (uint32_t stage = 0; stage < 2; stage++) {
        if (!(c->stages & (1u << stage))) continue;
        if (!texture) {
            ctx->textureSetMask[stage] &= ~bit;
            continue;
        }
        dec_bind_texture(ctx, ext->ds, stage, c->index, texture);
        if (sampler) {
            dec_bind_sampler(ctx, ext->ds, stage, c->index, sampler);
            ext->samplerBoundMask[stage] |= bit;
        }
        ctx->textureSetMask[stage] |= bit;
    }
    return MCMTL_OK;
}

static int32_t op_clear_binding(mcmtl_decode_ctx *ctx, mcmtl_decoder_ext *ext, const uint8_t *rec, uint64_t offset) {
    const mcmtl_cmd_clear_binding *c = (const mcmtl_cmd_clear_binding *)rec;
    if (c->kind == MCMTL_BINDING_KIND_BUFFER) {
        if (c->index > MCMTL_PUSH_CONSTANT_BUFFER_INDEX) return dec_fail(ext, MCMTL_E_BAD_STREAM, offset, "CLEAR_BINDING buffer index %u", c->index);
        for (uint32_t stage = 0; stage < 2; stage++) {
            if (c->stages & (1u << stage)) ctx->bufferSetMask[stage] &= ~(1u << c->index);
        }
    } else if (c->kind == MCMTL_BINDING_KIND_TEXTURE) {
        if (c->index >= MCMTL_MAX_TEXTURE_BINDINGS) return dec_fail(ext, MCMTL_E_BAD_STREAM, offset, "CLEAR_BINDING texture index %u", c->index);
        for (uint32_t stage = 0; stage < 2; stage++) {
            if (c->stages & (1u << stage)) ctx->textureSetMask[stage] &= ~(1u << c->index);
        }
    } else {
        return dec_fail(ext, MCMTL_E_BAD_STREAM, offset, "CLEAR_BINDING kind %u unknown", c->kind);
    }
    return MCMTL_OK;
}

static int32_t op_set_vertex_buffer(mcmtl_decode_ctx *ctx, mcmtl_decoder_ext *ext, const uint8_t *rec, uint64_t offset) {
    const mcmtl_cmd_set_vertex_buffer *c = (const mcmtl_cmd_set_vertex_buffer *)rec;
    if (c->slot >= MCMTL_MAX_VERTEX_BUFFERS || !c->buffer) {
        return dec_fail(ext, MCMTL_E_BAD_STREAM, offset, "SET_VERTEX_BUFFER slot %u buffer %s", c->slot, c->buffer ? "set" : "null");
    }
    __unsafe_unretained id<MTLBuffer> buffer = MCMTL_BUFFER(c->buffer);
    const uint32_t index = MCMTL_VERTEX_BUFFER_INDEX_BASE - c->slot;
    if (c->offset >= dec_buffer_length(ext, buffer)) {
        DEC_MSG(ext, DEC_MSG_BINDING, "SET_VERTEX_BUFFER offset %llu >= buffer length %llu; slot left unbound",
                (unsigned long long)c->offset, (unsigned long long)dec_buffer_length(ext, buffer));
        ext->vertexBoundMask &= ~(1u << index);
        return MCMTL_OK;
    }
    dec_bind_buffer(ctx, ext->ds, 0, index, buffer, c->offset);
    ext->vertexBoundMask |= 1u << index;
    if (index == 30 && ctx->pipeline && (ctx->pipeline->bufferMask[1] & (1u << 30))) {
        dec_bind_buffer(ctx, ext->ds, 1, 30, buffer, c->offset);
        ctx->bufferSetMask[1] |= 1u << 30;
    }
    return MCMTL_OK;
}

static int32_t op_set_index_buffer(mcmtl_decode_ctx *ctx, mcmtl_decoder_ext *ext, const uint8_t *rec, uint64_t offset) {
    const mcmtl_cmd_set_index_buffer *c = (const mcmtl_cmd_set_index_buffer *)rec;
    if (c->indexType != MTLIndexTypeUInt16 && c->indexType != MTLIndexTypeUInt32) {
        return dec_fail(ext, MCMTL_E_BAD_STREAM, offset, "SET_INDEX_BUFFER index type %u", c->indexType);
    }
    ctx->indexBuffer = c->buffer ? MCMTL_BUFFER(c->buffer) : nil;
    ctx->indexType = c->indexType;
    ext->indexBufferLength = ctx->indexBuffer ? ctx->indexBuffer.length : 0;
    return MCMTL_OK;
}

static int32_t op_set_scissor(mcmtl_decode_ctx *ctx, mcmtl_decoder_ext *ext, const uint8_t *rec) {
    const mcmtl_cmd_set_scissor *c = (const mcmtl_cmd_set_scissor *)rec;
    if (ext->passEmpty) return MCMTL_OK;
    int32_t r[4];
    if (!dec_clamp_rect(c->x, c->y, c->width, c->height, ctx->attachmentWidth, ctx->attachmentHeight, r)) {
        ext->scissorEmpty = YES;
        return MCMTL_OK;
    }
    ext->scissorEmpty = NO;
    dec_set_scissor(ctx, ext, r[0], r[1], r[2], r[3]);
    return MCMTL_OK;
}

static int32_t op_reset_scissor(mcmtl_decode_ctx *ctx, mcmtl_decoder_ext *ext) {
    if (ext->passEmpty) return MCMTL_OK;
    ext->scissorEmpty = NO;
    dec_set_scissor(ctx, ext, ctx->renderArea[0], ctx->renderArea[1], ctx->renderArea[2], ctx->renderArea[3]);
    return MCMTL_OK;
}

static int32_t op_push_constants(mcmtl_decode_ctx *ctx, mcmtl_decoder_ext *ext, const uint8_t *rec, uint32_t size, uint64_t offset) {
    const mcmtl_cmd_push_constants *c = (const mcmtl_cmd_push_constants *)rec;
    if (c->byteLength > MCMTL_MAX_PUSH_CONSTANT_BYTES || (uint64_t)sizeof *c + c->byteLength > size) {
        return dec_fail(ext, MCMTL_E_BAD_STREAM, offset, "PUSH_CONSTANTS length %u does not fit record size %u", c->byteLength, size);
    }
    if (c->byteLength == 0) return MCMTL_OK;
    const uint8_t *data = rec + sizeof *c;
    const uint32_t index = MCMTL_PUSH_CONSTANT_BUFFER_INDEX;
    if (c->stages & MCMTL_STAGE_VERTEX) {
        [ctx->renderEncoder setVertexBytes:data length:c->byteLength atIndex:index];
        ctx->buffers[0][index] = nil;
        ctx->bufferSetMask[0] |= 1u << index;
        ext->ds->stats.setBytes++;
    }
    if (c->stages & MCMTL_STAGE_FRAGMENT) {
        [ctx->renderEncoder setFragmentBytes:data length:c->byteLength atIndex:index];
        ctx->buffers[1][index] = nil;
        ctx->bufferSetMask[1] |= 1u << index;
        ext->ds->stats.setBytes++;
    }
    return MCMTL_OK;
}

static void dec_skip_draw(mcmtl_decoder_ext *ext, uint32_t kind, const char *why) {
    ext->result->drawsSkipped++;
    DEC_MSG(ext, kind, "draw skipped: %s", why);
}

/* Binds dummies for every slot the pipeline uses but nothing set since SET_PIPELINE (CONTRACT.md 6.4). */
static void dec_apply_dummies(mcmtl_decode_ctx *ctx, mcmtl_decoder_ext *ext, uint32_t stage, uint32_t missingBuffers,
                              uint32_t missingTextures, uint32_t missingSamplers) {
    MCMTLDevice *dev = ext->dev;
    MCMTLDecoderState *ds = ext->ds;
    MCMTLPipeline *p = ctx->pipeline;
    while (missingBuffers) {
        uint32_t index = (uint32_t)__builtin_ctz(missingBuffers);
        missingBuffers &= missingBuffers - 1;
        dec_bind_buffer(ctx, ds, stage, index, dev->dummyBuffer, 0);
        ctx->bufferSetMask[stage] |= 1u << index;
        ds->stats.dummyBindings++;
    }
    while (missingTextures) {
        uint32_t index = (uint32_t)__builtin_ctz(missingTextures);
        uint32_t bit = 1u << index;
        missingTextures &= missingTextures - 1;
        __unsafe_unretained id<MTLTexture> dummy = (p->textureCubeMask[stage] & bit) ? dev->dummyTextureCube
                                                 : (p->texelBufferMask[stage] & bit) ? dev->dummyTexelBuffer
                                                 : dev->dummyTexture2D;
        dec_bind_texture(ctx, ds, stage, index, dummy);
        ctx->textureSetMask[stage] |= bit;
        if (p->samplerMask[stage] & bit) {
            dec_bind_sampler(ctx, ds, stage, index, dev->dummySampler);
            ext->samplerBoundMask[stage] |= bit;
        }
        ds->stats.dummyBindings++;
    }
    missingSamplers &= ~ext->samplerBoundMask[stage];
    while (missingSamplers) {
        uint32_t index = (uint32_t)__builtin_ctz(missingSamplers);
        missingSamplers &= missingSamplers - 1;
        dec_bind_sampler(ctx, ds, stage, index, dev->dummySampler);
        ext->samplerBoundMask[stage] |= 1u << index;
        ds->stats.dummyBindings++;
    }
}

/* Draw-time obligations: pipeline present, required vertex buffers bound, dummies for unset slots. */
static BOOL dec_prepare_draw(mcmtl_decode_ctx *ctx, mcmtl_decoder_ext *ext) {
    if (ext->passEmpty || ext->scissorEmpty) return NO;
    if (ext->pipelineInvalid) {
        dec_skip_draw(ext, DEC_MSG_NO_PIPELINE, "no usable pipeline (SET_PIPELINE missing, null, or its PSO variant failed)");
        return NO;
    }
    MCMTLPipeline *p = ctx->pipeline;
    if (p->requiredVertexBufferMask & ~ext->vertexBoundMask) {
        dec_skip_draw(ext, DEC_MSG_MISSING_VB, "a vertex buffer the pipeline reads was never bound in this pass");
        return NO;
    }
    for (uint32_t stage = 0; stage < 2; stage++) {
        uint32_t missingBuffers = p->bufferMask[stage] & DEC_UBO_MASK & ~ctx->bufferSetMask[stage];
        uint32_t missingTextures = p->textureMask[stage] & ~ctx->textureSetMask[stage];
        uint32_t missingSamplers = p->samplerMask[stage] & ~ext->samplerBoundMask[stage];
        if (missingBuffers | missingTextures | missingSamplers) {
            dec_apply_dummies(ctx, ext, stage, missingBuffers, missingTextures, missingSamplers);
        }
    }
    return YES;
}

/* Fan index buffer covering `vertices` vertices ([0,k,k+1] triples), grown on demand. */
static id<MTLBuffer> dec_fan_indices(mcmtl_decoder_ext *ext, uint32_t vertices, MTLIndexType *outType) {
    MCMTLDecoderState *ds = ext->ds;
    BOOL wide = vertices > 65535u;   /* keep 0xFFFF out of UInt16 index buffers */
    if (!wide && ds->fanVertices16 >= vertices) { *outType = MTLIndexTypeUInt16; return ds->fanIndex16; }
    if (wide && ds->fanVertices32 >= vertices) { *outType = MTLIndexTypeUInt32; return ds->fanIndex32; }
    uint32_t capacity = DEC_FAN_MIN_VERTICES;
    while (capacity < vertices && capacity < 0x40000000u) capacity <<= 1;
    if (!wide && capacity > 65535u) capacity = 65535u;
    const NSUInteger indexSize = wide ? 4u : 2u;
    const NSUInteger count = 3u * (NSUInteger)(capacity - 2u);
    id<MTLBuffer> buffer = [ext->dev->device newBufferWithLength:count * indexSize options:MTLResourceStorageModeShared];
    if (!buffer) return nil;
    buffer.label = @"mcmetal fan indices";
    if (wide) {
        uint32_t *out = buffer.contents;
        for (uint32_t k = 1; k + 1 < capacity; k++) { *out++ = 0; *out++ = k; *out++ = k + 1; }
        ds->fanIndex32 = buffer;
        ds->fanVertices32 = capacity;
        *outType = MTLIndexTypeUInt32;
    } else {
        uint16_t *out = buffer.contents;
        for (uint32_t k = 1; k + 1 < capacity; k++) { *out++ = 0; *out++ = (uint16_t)k; *out++ = (uint16_t)(k + 1); }
        ds->fanIndex16 = buffer;
        ds->fanVertices16 = capacity;
        *outType = MTLIndexTypeUInt16;
    }
    return buffer;
}

static void dec_draw_fan(mcmtl_decode_ctx *ctx, mcmtl_decoder_ext *ext, uint32_t vertexCount, uint32_t instanceCount,
                         uint32_t firstVertex, uint32_t firstInstance) {
    if (vertexCount < 3) return;
    MTLIndexType type = MTLIndexTypeUInt16;
    id<MTLBuffer> indices = dec_fan_indices(ext, vertexCount, &type);
    if (!indices) {
        dec_skip_draw(ext, DEC_MSG_FAN, "fan index buffer allocation failed");
        return;
    }
    [ctx->renderEncoder drawIndexedPrimitives:MTLPrimitiveTypeTriangle indexCount:3u * (vertexCount - 2u) indexType:type
                                  indexBuffer:indices indexBufferOffset:0 instanceCount:instanceCount
                                   baseVertex:(NSInteger)firstVertex baseInstance:firstInstance];
    ext->result->drawsEncoded++;
    ext->ds->stats.draws++;
}

/* Indexed fan: reads the Shared index buffer at decode time and draws the (i0, ik, ik+1) triangle list
 * from the upload ring (spec 4.3 (b)). */
static void dec_draw_indexed_fan(mcmtl_decode_ctx *ctx, mcmtl_decoder_ext *ext, uint32_t indexCount, uint32_t instanceCount,
                                 uint32_t firstIndex, int32_t baseVertex, uint32_t firstInstance) {
    if (indexCount < 3) return;
    id<MTLBuffer> ib = ctx->indexBuffer;
    const uint8_t *base = ib.contents;
    if (!base) {
        dec_skip_draw(ext, DEC_MSG_FAN, "indexed fan needs a CPU-visible index buffer");
        return;
    }
    const NSUInteger triangles = indexCount - 2u;
    id<MTLBuffer> out = nil;
    NSUInteger outOffset = 0;
    uint32_t *dst = dec_ring_alloc(ext, triangles * 3u * 4u, 4, &out, &outOffset);
    if (!dst) {
        dec_skip_draw(ext, DEC_MSG_FAN, "upload ring allocation failed");
        return;
    }
    if (ctx->indexType == MTLIndexTypeUInt32) {
        const uint32_t *src = (const uint32_t *)(base + (NSUInteger)firstIndex * 4u);
        for (NSUInteger k = 1; k + 1 < indexCount; k++) { *dst++ = src[0]; *dst++ = src[k]; *dst++ = src[k + 1]; }
    } else {
        const uint16_t *src = (const uint16_t *)(base + (NSUInteger)firstIndex * 2u);
        for (NSUInteger k = 1; k + 1 < indexCount; k++) { *dst++ = src[0]; *dst++ = src[k]; *dst++ = src[k + 1]; }
    }
    [ctx->renderEncoder drawIndexedPrimitives:MTLPrimitiveTypeTriangle indexCount:triangles * 3u indexType:MTLIndexTypeUInt32
                                  indexBuffer:out indexBufferOffset:outOffset instanceCount:instanceCount
                                   baseVertex:baseVertex baseInstance:firstInstance];
    ext->result->drawsEncoded++;
    ext->ds->stats.draws++;
}

/* ---------------------------------------------------------------------------------------------- */
/* Terrain ICB (MCMTL_TERRAIN_ICB=1, CONTRACT.md 16.5)                                              */
/* ---------------------------------------------------------------------------------------------- */

/* A DRAW_INDEXED_INDIRECT of N records becomes ONE executeCommandsInBuffer:withRange: of an MTLIndirectCommandBuffer
 * (inheritPipelineState + inheritBuffers) holding N drawIndexedPrimitives commands, encoded on the CPU from the records
 * (CPU-written MAP_WRITE ring: final at decode time) and cached: the n-th such draw of a call reuses the n-th slot's
 * commands while its records, index buffer (object -- retained by the slot --, length, type) and primitive type are unchanged
 * (vanilla's TerrainDrawList keeps records identical on most frames), so a hit costs a memcmp and a handful of Metal
 * calls instead of one Metal draw per record. A slot whose ICB the GPU may still execute is never re-encoded in place:
 * it takes a completed retired ICB or a new one.
 *
 * Metal refuses ICB pipelines whose shaders bind textures directly, so the pipeline's ICB variant
 * (MCMTL_VARIANT_KEY_ICB, compiled asynchronously from the kept MSL; per-record draws until it arrives) reads every
 * texture and sampler from an argument buffer at [[buffer(15)]]: MTLResourceIDs of the textures / samplers bound in
 * the shadow state (dummies included), written to the upload ring when they change, plus useResource: for each texture
 * and the index buffer (once per encoder). The shader bodies are unchanged, so results are bit-identical
 * (decoder_test compares bytes with the per-record path; tools/terrain_icb_probe.m checks base vertex / base instance,
 * odd 16-bit index offsets, reset commands, instanceCount > 1 on the GPU). Per-record or direct draws after an ICB range
 * restore the pipeline's own PSO. */

static inline void dec_icb_restore_pso(mcmtl_decode_ctx *ctx, mcmtl_decoder_ext *ext) {
    if (!ext->icbPsoBound) return;
    ext->icbPsoBound = NO;
    if (ext->basePso && ctx->pso != ext->basePso) {
        [ctx->renderEncoder setRenderPipelineState:ext->basePso];
        ctx->pso = ext->basePso;
        ext->ds->stats.setPipelineState++;
    }
}

/* useResource:usage:Read for resources only an ICB command or the argument buffer references; once per render
 * encoder and stage set (repeated when more than DEC_ICB_USED resources are tracked). */
static void dec_icb_use(mcmtl_decode_ctx *ctx, mcmtl_decoder_ext *ext, __unsafe_unretained id<MTLResource> resource, uint32_t stages) {
    for (uint32_t i = 0; i < ext->icbUsedCount; i++) {
        if (ext->icbUsed[i] != resource) continue;
        if ((ext->icbUsedStages[i] & stages) == stages) return;
        ext->icbUsedStages[i] |= stages;
        [ctx->renderEncoder useResource:resource usage:MTLResourceUsageRead stages:(MTLRenderStages)ext->icbUsedStages[i]];
        return;
    }
    if (ext->icbUsedCount < DEC_ICB_USED) {
        ext->icbUsed[ext->icbUsedCount] = resource;
        ext->icbUsedStages[ext->icbUsedCount] = stages;
        ext->icbUsedCount++;
    }
    [ctx->renderEncoder useResource:resource usage:MTLResourceUsageRead stages:(MTLRenderStages)stages];
}

/* The ICB variant of `p` for `key`, or nil with *reason (compile started asynchronously: VARIANT; cannot be built:
 * FAILED). Builds go through the present-fusion mailbox (drained at the start of each call). */
static id<MTLRenderPipelineState> dec_icb_variant(mcmtl_decoder_ext *ext, MCMTLPipeline *p, uint64_t key, uint32_t *reason) {
    id<MTLRenderPipelineState> pso = mcmtl_pipeline_find_variant(p, key);
    if (pso) return pso;
    MCMTLDecoderState *ds = ext->ds;
    NSNumber *keyNumber = @(key);
    NSMutableSet<NSNumber *> *failed = [ds->failedVariants objectForKey:p];
    if (failed && [failed containsObject:keyNumber]) {
        *reason = DEC_ICB_R_FAILED;
        return nil;
    }
    NSMutableSet<NSNumber *> *pending = [ds->fusePending objectForKey:p];
    if (pending && [pending containsObject:keyNumber]) {
        *reason = DEC_ICB_R_VARIANT;
        return nil;
    }
    ds->stats.icbVariantBuilds++;
    id<MTLDevice> device = ext->dev->device;
    if (ds->icbTestFlags & DEC_ICB_TEST_SYNC_VARIANTS) {
        NSString *failure = nil;
        MTLRenderPipelineDescriptor *desc = mcmtl_pipeline_variant_descriptor(p, key, &failure);
        NSError *error = nil;
        pso = desc ? [device newRenderPipelineStateWithDescriptor:desc error:&error] : nil;
        if (pso) {
            mcmtl_pipeline_add_variant(p, key, pso);
            return pso;
        }
        if (!failure) failure = error ? error.localizedDescription : @"(no NSError)";
        if (!failed) {
            failed = [NSMutableSet set];
            [ds->failedVariants setObject:failed forKey:p];
        }
        [failed addObject:keyNumber];
        DEC_MSG(ext, DEC_MSG_VARIANT, "terrain ICB: ICB variant of pipeline '%s' failed (%s); its indirect draws stay per record",
                p->label ? p->label.UTF8String : "?", failure.UTF8String);
        *reason = DEC_ICB_R_FAILED;
        return nil;
    }
    if (!pending) {
        pending = [NSMutableSet set];
        [ds->fusePending setObject:pending forKey:p];
    }
    [pending addObject:keyNumber];
    MCMTLFuseMailbox *mb = ds->fuseMailbox;
    MCMTLPipeline *pipeline = p;
    /* Off the render thread: the ICB form of the shaders is compiled from MSL here. */
    dispatch_async(dispatch_get_global_queue(QOS_CLASS_DEFAULT, 0), ^{
        @autoreleasepool {
            NSString *failure = nil;
            id<MTLRenderPipelineState> built = nil;
            MTLRenderPipelineDescriptor *desc = mcmtl_pipeline_variant_descriptor(pipeline, key, &failure);
            if (desc) {
                NSError *error = nil;
                built = [device newRenderPipelineStateWithDescriptor:desc error:&error];
                if (!built) failure = error ? error.localizedDescription : @"(no NSError)";
            }
            MCMTLFuseVariant *v = [MCMTLFuseVariant new];
            v->pipeline = pipeline;
            v->key = key;
            v->pso = built;
            v->failure = built ? nil : (failure ?: @"(unknown)");
            os_unfair_lock_lock(&mb->lock);
            [mb->done addObject:v];
            os_unfair_lock_unlock(&mb->lock);
        }
    });
    *reason = DEC_ICB_R_VARIANT;
    return nil;
}

/* Encodes the commands of `n` records into `slot` (an ICB the GPU is done with: the slot's own when complete and large
 * enough, else a completed retired one, else a new one). Records with a zero count or an index range outside the index
 * buffer become reset (no-op) commands. NO if no ICB could be allocated. */
static BOOL dec_icb_encode(mcmtl_decoder_ext *ext, MCMTLIcbSlot *slot, const uint8_t *records, uint32_t n,
                           __unsafe_unretained id<MTLBuffer> ib, uint32_t indexType, uint64_t ibLength,
                           uint32_t primitive) {
    MCMTLDecoderState *ds = ext->ds;
    MCMTLDevice *dev = ext->dev;
    const uint64_t completed = mcmtl_status_load_u64(&dev->status->completedValue);
    slot->count = 0;
    if (!slot->icb || slot->capacity < n || slot->lastUse > completed) {
        if (slot->icb) {
            MCMTLIcbSlot *old = [MCMTLIcbSlot new];
            old->icb = slot->icb;
            old->capacity = slot->capacity;
            old->lastUse = slot->lastUse;
            [ds->icbRetired addObject:old];
            if (ds->icbRetired.count > DEC_ICB_RETIRED) [ds->icbRetired removeObjectAtIndex:0];   /* command buffers retain it */
            slot->icb = nil;
            slot->capacity = 0;
        }
        for (NSUInteger i = 0; i < ds->icbRetired.count; i++) {
            MCMTLIcbSlot *r = ds->icbRetired[i];
            if (r->capacity >= n && r->lastUse <= completed) {
                slot->icb = r->icb;
                slot->capacity = r->capacity;
                slot->lastUse = r->lastUse;
                [ds->icbRetired removeObjectAtIndex:i];
                break;
            }
        }
        if (!slot->icb) {
            uint32_t capacity = DEC_ICB_MIN_CAPACITY;
            while (capacity < n && capacity < (1u << 30)) capacity <<= 1;
            slot->icb = [dev->device newIndirectCommandBufferWithDescriptor:ds->icbDescriptor maxCommandCount:capacity
                                                                    options:MTLResourceStorageModeShared];
            if (!slot->icb) return NO;
            slot->icb.label = @"mcmetal terrain ICB";
            slot->capacity = capacity;
            slot->lastUse = 0;
            ds->stats.icbCreated++;
        }
    }
    const size_t bytes = (size_t)n * MCMTL_INDIRECT_STRIDE_DRAW_INDEXED;
    if (slot->recordCapacity < n) {
        uint8_t *grown = realloc(slot->records, (size_t)slot->capacity * MCMTL_INDIRECT_STRIDE_DRAW_INDEXED);
        if (!grown) return NO;
        slot->records = grown;
        slot->recordCapacity = slot->capacity;
    }
    const uint64_t indexSize = indexType == MTLIndexTypeUInt32 ? 4u : 2u;
    __unsafe_unretained id<MTLIndirectCommandBuffer> icb = slot->icb;
    uint32_t outOfRange = 0;
    for (uint32_t i = 0; i < n; i++) {
        uint32_t a[5];
        memcpy(a, records + (size_t)i * MCMTL_INDIRECT_STRIDE_DRAW_INDEXED, sizeof a);
        id<MTLIndirectRenderCommand> cmd = [icb indirectRenderCommandAtIndex:i];
        if (a[0] == 0 || a[1] == 0) {
            [cmd reset];
            continue;
        }
        if (!dec_fits((uint64_t)a[2] * indexSize, (uint64_t)a[0] * indexSize, ibLength)) {
            [cmd reset];
            outOfRange++;
            continue;
        }
        [cmd drawIndexedPrimitives:(MTLPrimitiveType)primitive indexCount:a[0] indexType:(MTLIndexType)indexType indexBuffer:ib
                 indexBufferOffset:(NSUInteger)((uint64_t)a[2] * indexSize) instanceCount:a[1] baseVertex:(NSInteger)(int32_t)a[3]
                      baseInstance:a[4]];
    }
    if (outOfRange) DEC_MSG(ext, DEC_MSG_INDEX_RANGE, "terrain ICB: %u indirect record(s) with an index range outside the index buffer skipped", outOfRange);
    memcpy(slot->records, records, bytes);
    slot->count = n;
    slot->indexBuffer = ib;
    slot->indexLength = ibLength;
    slot->indexType = indexType;
    slot->primitiveType = primitive;
    ds->stats.icbEncodedRecords += n;
    return YES;
}

/* Binds, per stage the pipeline samples in, the argument buffer the ICB variant reads its textures from (written only
 * when the shadow's textures / samplers changed since the last one of this encoder) and makes those textures resident.
 * NO if the upload ring is exhausted. */
static BOOL dec_icb_bind_textures(mcmtl_decode_ctx *ctx, mcmtl_decoder_ext *ext, MCMTLPipeline *p) {
    MCMTLDecoderState *ds = ext->ds;
    const uint32_t index = MCMTL_ICB_ARG_BUFFER_INDEX;
    for (uint32_t stage = 0; stage < 2; stage++) {
        const uint32_t tmask = p->textureMask[stage] & 0xFFFFu, smask = p->samplerMask[stage] & 0xFFFFu;
        const uint32_t mask = tmask | smask;
        if (!mask) continue;
        BOOL same = ext->icbArgValid[stage] && ctx->buffers[stage][index] == ext->icbArgBuffer[stage] &&
                    ctx->bufferOffsets[stage][index] == ext->icbArgOffset[stage];
        for (uint32_t m = mask; same && m; m &= m - 1) {
            const uint32_t i = (uint32_t)__builtin_ctz(m);
            if (((tmask >> i) & 1u) && ext->icbArgTex[stage][i] != ctx->textures[stage][i]) same = NO;
            if (((smask >> i) & 1u) && ext->icbArgSmp[stage][i] != ctx->samplers[stage][i]) same = NO;
        }
        if (!same) {
            const uint32_t top = 32u - (uint32_t)__builtin_clz(mask);
            id<MTLBuffer> buffer = nil;
            NSUInteger offset = 0;
            uint8_t *dst = dec_ring_alloc(ext, 16u * top, 64, &buffer, &offset);
            if (!dst) return NO;
            memset(dst, 0, 16u * top);
            for (uint32_t m = mask; m; m &= m - 1) {
                const uint32_t i = (uint32_t)__builtin_ctz(m);
                if ((tmask >> i) & 1u) {
                    __unsafe_unretained id<MTLTexture> t = ctx->textures[stage][i];
                    const MTLResourceID rid = t.gpuResourceID;
                    memcpy(dst + 16u * i, &rid, 8);
                    ext->icbArgTex[stage][i] = t;
                }
                if ((smask >> i) & 1u) {
                    __unsafe_unretained id<MTLSamplerState> s = ctx->samplers[stage][i];
                    const MTLResourceID rid = s.gpuResourceID;
                    memcpy(dst + 16u * i + 8u, &rid, 8);
                    ext->icbArgSmp[stage][i] = s;
                }
            }
            dec_bind_buffer(ctx, ds, stage, index, buffer, offset);
            ext->icbArgValid[stage] = YES;
            ext->icbArgBuffer[stage] = buffer;
            ext->icbArgOffset[stage] = offset;
            ds->stats.icbArgBuffers++;
        }
        const uint32_t stageBit = stage == 0 ? MTLRenderStageVertex : MTLRenderStageFragment;
        for (uint32_t m = tmask; m; m &= m - 1) dec_icb_use(ctx, ext, ctx->textures[stage][__builtin_ctz(m)], stageBit);
    }
    return YES;
}

enum { DEC_ICB_PER_RECORD = 0, DEC_ICB_EXECUTED, DEC_ICB_PER_RECORD_KEEP_PSO };

/* DRAW_INDEXED_INDIRECT through an ICB (after dec_prepare_draw). DEC_ICB_EXECUTED, or a per-record fallback (reason
 * counted): DEC_ICB_PER_RECORD_KEEP_PSO when the pipeline's ICB variant stays bound for it (an earlier range of the same
 * SET_PIPELINE bound it and the texture argument buffer is current: no PSO ping-pong between small and large groups),
 * DEC_ICB_PER_RECORD to restore the pipeline's own PSO first. */
static int dec_icb_draw(mcmtl_decode_ctx *ctx, mcmtl_decoder_ext *ext, const mcmtl_cmd_draw_indirect *c, __unsafe_unretained id<MTLBuffer> args) {
    MCMTLDecoderState *ds = ext->ds;
    MCMTLPipeline *p = ctx->pipeline;
    const uint32_t n = c->drawCount;
    const uint32_t argBit = 1u << MCMTL_ICB_ARG_BUFFER_INDEX;
    const uint8_t *base = NULL;
    id<MTLRenderPipelineState> icbPso = nil;
    uint32_t reason = DEC_ICB_R_ALLOC;
    if (p->emulation == MCMTL_EMULATION_TRIANGLE_FAN) {
        reason = DEC_ICB_R_FAN;
    } else if (n < ds->icbMinRecords) {
        reason = DEC_ICB_R_SMALL;
    } else if ((ext->vertexBoundMask | p->requiredVertexBufferMask | p->bufferMask[0] | p->bufferMask[1]) & argBit) {
        reason = DEC_ICB_R_SLOT;
    } else if (!(base = args.contents) || dec_icb_gpu_written(ext->dev, ds, (__bridge const void *)args)) {
        reason = DEC_ICB_R_ARGS;
    } else if (ext->icbOrdinal >= DEC_ICB_SLOTS) {
        reason = DEC_ICB_R_CACHE;
    } else if ((icbPso = dec_icb_variant(ext, p, ext->psoKey | MCMTL_VARIANT_KEY_ICB, &reason)) != nil) {
        if (ext->icbOrdinal >= ds->icbSlots.count) [ds->icbSlots addObject:[MCMTLIcbSlot new]];
        MCMTLIcbSlot *slot = ds->icbSlots[ext->icbOrdinal++];
        const uint8_t *records = base + c->offset;
        __unsafe_unretained id<MTLBuffer> ib = ctx->indexBuffer;
        const uint32_t primitive = p->primitiveType;
        BOOL ok = YES;
        if (slot->count == n && slot->indexBuffer == ib && slot->indexType == ctx->indexType &&
            slot->indexLength == ext->indexBufferLength && slot->primitiveType == primitive &&
            memcmp(slot->records, records, (size_t)n * MCMTL_INDIRECT_STRIDE_DRAW_INDEXED) == 0) {
            ds->stats.icbHits++;
        } else {
            ok = dec_icb_encode(ext, slot, records, n, ib, ctx->indexType, ext->indexBufferLength, primitive);
        }
        if (ok) ok = dec_icb_bind_textures(ctx, ext, p);
        if (ok) {
            slot->lastUse = dec_icb_open_value(ext->dev);
            __unsafe_unretained id<MTLRenderCommandEncoder> re = ctx->renderEncoder;
            if (ctx->pso != icbPso) {
                [re setRenderPipelineState:icbPso];
                ctx->pso = icbPso;
                ds->stats.setPipelineState++;
            }
            ext->icbPsoBound = YES;
            dec_icb_use(ctx, ext, ib, MTLRenderStageVertex);
            [re executeCommandsInBuffer:slot->icb withRange:NSMakeRange(0, n)];
            ds->stats.icbGroups++;
            ds->stats.icbRecords += n;
            ds->stats.draws++;   /* one Metal command */
            ext->result->drawsEncoded += n;
            return DEC_ICB_EXECUTED;
        }
        DEC_MSG(ext, DEC_MSG_INTERNAL, "terrain ICB: ICB or argument buffer allocation failed; drawing per record");
        reason = DEC_ICB_R_ALLOC;
    }
    ds->stats.icbFallback[reason]++;
    if (ext->icbPsoBound && (reason == DEC_ICB_R_SMALL || reason == DEC_ICB_R_ARGS || reason == DEC_ICB_R_CACHE) &&
        dec_icb_bind_textures(ctx, ext, p)) {
        return DEC_ICB_PER_RECORD_KEEP_PSO;
    }
    return DEC_ICB_PER_RECORD;
}

static int32_t op_draw(mcmtl_decode_ctx *ctx, mcmtl_decoder_ext *ext, const uint8_t *rec) {
    const mcmtl_cmd_draw *c = (const mcmtl_cmd_draw *)rec;
    if (c->vertexCount == 0 || c->instanceCount == 0) return MCMTL_OK;
    if (!dec_prepare_draw(ctx, ext)) return MCMTL_OK;
    dec_icb_restore_pso(ctx, ext);
    MCMTLPipeline *p = ctx->pipeline;
    if (p->emulation == MCMTL_EMULATION_TRIANGLE_FAN) {
        dec_draw_fan(ctx, ext, c->vertexCount, c->instanceCount, c->firstVertex, c->firstInstance);
        return MCMTL_OK;
    }
    [ctx->renderEncoder drawPrimitives:(MTLPrimitiveType)p->primitiveType vertexStart:c->firstVertex vertexCount:c->vertexCount
                         instanceCount:c->instanceCount baseInstance:c->firstInstance];
    ext->result->drawsEncoded++;
    ext->ds->stats.draws++;
    return MCMTL_OK;
}

static int32_t op_draw_indexed(mcmtl_decode_ctx *ctx, mcmtl_decoder_ext *ext, const uint8_t *rec) {
    const mcmtl_cmd_draw_indexed *c = (const mcmtl_cmd_draw_indexed *)rec;
    if (c->indexCount == 0 || c->instanceCount == 0) return MCMTL_OK;
    if (!ctx->indexBuffer) {
        dec_skip_draw(ext, DEC_MSG_NO_INDEX, "DRAW_INDEXED without an index buffer");
        return MCMTL_OK;
    }
    const uint64_t indexSize = ctx->indexType == MTLIndexTypeUInt32 ? 4u : 2u;
    const uint64_t start = (uint64_t)c->firstIndex * indexSize;
    if (!dec_fits(start, (uint64_t)c->indexCount * indexSize, ext->indexBufferLength)) {
        dec_skip_draw(ext, DEC_MSG_INDEX_RANGE, "index range exceeds the index buffer");
        return MCMTL_OK;
    }
    if (!dec_prepare_draw(ctx, ext)) return MCMTL_OK;
    dec_icb_restore_pso(ctx, ext);
    MCMTLPipeline *p = ctx->pipeline;
    if (p->emulation == MCMTL_EMULATION_TRIANGLE_FAN) {
        dec_draw_indexed_fan(ctx, ext, c->indexCount, c->instanceCount, c->firstIndex, c->baseVertex, c->firstInstance);
        return MCMTL_OK;
    }
    [ctx->renderEncoder drawIndexedPrimitives:(MTLPrimitiveType)p->primitiveType indexCount:c->indexCount
                                    indexType:(MTLIndexType)ctx->indexType indexBuffer:ctx->indexBuffer indexBufferOffset:start
                                instanceCount:c->instanceCount baseVertex:c->baseVertex baseInstance:c->firstInstance];
    ext->result->drawsEncoded++;
    ext->ds->stats.draws++;
    return MCMTL_OK;
}

static int32_t op_draw_indirect(mcmtl_decode_ctx *ctx, mcmtl_decoder_ext *ext, const uint8_t *rec, uint32_t op, uint64_t offset) {
    const mcmtl_cmd_draw_indirect *c = (const mcmtl_cmd_draw_indirect *)rec;
    const BOOL indexed = op == MCMTL_OP_DRAW_INDEXED_INDIRECT;
    if (!c->buffer) return dec_fail(ext, MCMTL_E_BAD_STREAM, offset, "indirect draw without argument buffer");
    if (c->drawCount == 0) return MCMTL_OK;
    /* Terrain occlusion (CONTRACT.md 16.6): the planned command at this offset, if any. */
    const dec_occl_cmd *occlCmd = NULL;
    const uint32_t *occlDec = NULL;
    if (ext->occlActive && indexed) {
        MCMTLOcclState *O = ext->ds->occl;
        while (O->cmdCursor < O->cmdCount && O->cmds[O->cmdCursor].offset < offset) O->cmdCursor++;
        if (O->cmdCursor < O->cmdCount && O->cmds[O->cmdCursor].offset == offset && O->cmds[O->cmdCursor].drawCount == c->drawCount) {
            occlCmd = &O->cmds[O->cmdCursor];
            occlDec = O->recDec + occlCmd->firstRec;
            if (occlCmd->klass == DEC_OCCL_CLASS_OPAQUE && offset == O->lastOpaqueOffset && !ext->occlBoxesDone) ext->occlBoxPending = YES;
        }
    }
    id<MTLBuffer> args = MCMTL_BUFFER(c->buffer);
    const uint64_t stride = indexed ? MCMTL_INDIRECT_STRIDE_DRAW_INDEXED : MCMTL_INDIRECT_STRIDE_DRAW;
    if ((c->offset & 3u) || !dec_fits(c->offset, stride * c->drawCount, args.length)) {
        dec_skip_draw(ext, DEC_MSG_INDIRECT, "indirect argument range misaligned or out of bounds");
        return MCMTL_OK;
    }
    if (indexed && !ctx->indexBuffer) {
        dec_skip_draw(ext, DEC_MSG_NO_INDEX, "DRAW_INDEXED_INDIRECT without an index buffer");
        return MCMTL_OK;
    }
    if (!dec_prepare_draw(ctx, ext)) return MCMTL_OK;
    int icb = DEC_ICB_PER_RECORD;
    if (indexed && ext->ds->icbMode) {   /* terrain ICB */
        if (occlDec) {
            /* Integration: a command the occlusion planner covers is drawn per record, so culled sections stay out
             * and opaque records count into their visibility slots (an ICB range cannot switch them). */
            ext->ds->stats.icbFallback[DEC_ICB_R_OCCLUSION]++;
        } else {
            icb = dec_icb_draw(ctx, ext, c, args);
            if (icb == DEC_ICB_EXECUTED) return MCMTL_OK;
        }
    }
    if (icb != DEC_ICB_PER_RECORD_KEEP_PSO) dec_icb_restore_pso(ctx, ext);
    MCMTLPipeline *p = ctx->pipeline;
    __unsafe_unretained id<MTLRenderCommandEncoder> re = ctx->renderEncoder;
    if (p->emulation == MCMTL_EMULATION_TRIANGLE_FAN) {
        /* Fans: CPU-decode the (Shared) argument records and emulate each draw. */
        const uint8_t *base = args.contents;
        if (!base) {
            dec_skip_draw(ext, DEC_MSG_FAN, "indirect fan draw needs a CPU-visible argument buffer");
            return MCMTL_OK;
        }
        for (uint32_t i = 0; i < c->drawCount; i++) {
            const uint32_t *a = (const uint32_t *)(base + c->offset + stride * i);
            if (indexed) {
                uint64_t indexSize = ctx->indexType == MTLIndexTypeUInt32 ? 4u : 2u;
                if (a[0] == 0 || a[1] == 0) continue;
                if (!dec_fits((uint64_t)a[2] * indexSize, (uint64_t)a[0] * indexSize, ext->indexBufferLength)) {
                    dec_skip_draw(ext, DEC_MSG_INDEX_RANGE, "index range exceeds the index buffer");
                    continue;
                }
                dec_draw_indexed_fan(ctx, ext, a[0], a[1], a[2], (int32_t)a[3], a[4]);
            } else if (a[0] && a[1]) {
                dec_draw_fan(ctx, ext, a[0], a[1], a[2], a[3]);
            }
        }
        return MCMTL_OK;
    }
    const MTLPrimitiveType type = (MTLPrimitiveType)p->primitiveType;
    if (occlDec) {
        /* Records of culled sections are not encoded; opaque records count into their section's visibility slot. */
        const BOOL count = ext->occlVisLive;
        uint32_t encoded = 0;
        for (uint32_t i = 0; i < c->drawCount; i++) {
            const uint32_t d = occlDec[i];
            if (d == DEC_OCCL_REC_CULL) continue;
            if (d && count) {
                if (ext->occlCurSlot != d) {
                    [re setVisibilityResultMode:MTLVisibilityResultModeBoolean offset:8u * (NSUInteger)(d - 1)];
                    ext->occlCurSlot = d;
                    ext->occlQueried = YES;
                }
            } else if (ext->occlCurSlot) {
                [re setVisibilityResultMode:MTLVisibilityResultModeDisabled offset:0];
                ext->occlCurSlot = 0;
            }
            [re drawIndexedPrimitives:type indexType:(MTLIndexType)ctx->indexType indexBuffer:ctx->indexBuffer
                    indexBufferOffset:0 indirectBuffer:args indirectBufferOffset:(NSUInteger)(c->offset + stride * i)];
            encoded++;
        }
        ext->result->drawsEncoded += encoded;
        ext->ds->stats.draws += encoded;
        ext->ds->stats.indirectRecordDraws += encoded;
        return MCMTL_OK;
    }
    for (uint32_t i = 0; i < c->drawCount; i++) {
        NSUInteger argOffset = (NSUInteger)(c->offset + stride * i);
        if (indexed) {
            [re drawIndexedPrimitives:type indexType:(MTLIndexType)ctx->indexType indexBuffer:ctx->indexBuffer
                    indexBufferOffset:0 indirectBuffer:args indirectBufferOffset:argOffset];
        } else {
            [re drawPrimitives:type indirectBuffer:args indirectBufferOffset:argOffset];
        }
    }
    ext->result->drawsEncoded += c->drawCount;
    ext->ds->stats.draws += c->drawCount;
    ext->ds->stats.indirectRecordDraws += c->drawCount;
    return MCMTL_OK;
}

/* ---------------------------------------------------------------------------------------------- */
/* Decode loop                                                                                      */
/* ---------------------------------------------------------------------------------------------- */

static BOOL dec_stream_has_timestamps(const uint8_t *s, uint64_t length) {
    uint64_t o = 0;
    while (o + 16 <= length) {
        const mcmtl_cmd_header *h = (const mcmtl_cmd_header *)(s + o);
        if (h->size < 16 || (h->size & 15) || h->size > length - o) return NO;
        if (h->op == MCMTL_OP_TIMESTAMP) return YES;
        o += h->size;
    }
    return NO;
}

static int32_t dec_stream(mcmtl_decode_ctx *ctx, mcmtl_decoder_ext *ext, const uint8_t *s, uint64_t length, BOOL isInit) {
    ext->stream = s;
    ext->streamLength = length;
    ext->isInit = isInit;
    ext->streamHasTimestamps = !isInit && dec_stream_has_timestamps(s, length);
    ext->startTsCount = 0;
    ext->handledTsCount = 0;
    ext->indexed = NO;
    ext->hoistCount = ext->hoistNext = 0;
    ext->fuseIndex = !isInit && ext->ds->fuseMode == 1;
    ext->fuseState = DEC_FUSE_NONE;
    ext->occlIndex = !isInit && ext->ds->occl != nil;
    if (!isInit) ext->occlMainStream = YES;
    if (!isInit && (ext->m2 & (DEC_M2_FOLD | DEC_M2_LATE_STORE | DEC_M2_MERGE))) dec_index_build(ext, s, length);
    if (!isInit) {
        DEC_SLOW_BEGIN;
        /* Present fusion (CONTRACT.md 16.3): settle the previous fused frame's stale source first (a restore pass
         * precedes everything this stream does), then plan this frame. */
        if (ext->ds->staleTexture) dec_fuse_stale(ext, s, length);
        if (ext->ds->fuseMode) dec_fuse_plan(ext);
        /* Terrain occlusion (CONTRACT.md 16.6): one plan per frame, in its FRAME_END COMMIT submit. */
        if (ext->ds->occl && ext->commit && (ext->ctx->info->flags & MCMTL_SUBMIT_FLAG_FRAME_END)) dec_occl_plan(ext);
        DEC_SLOW_END("plan", 0);
    }
    uint64_t offset = 0;
    while (offset < length) {
        if (length - offset < 16) return dec_fail(ext, MCMTL_E_BAD_STREAM, offset, "truncated record header");
        const uint8_t *rec = s + offset;
        const mcmtl_cmd_header *h = (const mcmtl_cmd_header *)rec;
        const uint32_t op = h->op, size = h->size;
        if (size < 16 || (size & 15) || size > length - offset) {
            return dec_fail(ext, MCMTL_E_BAD_STREAM, offset, "record op %u has invalid size %u (%llu bytes left)", op, size,
                            (unsigned long long)(length - offset));
        }
        if (op >= MCMTL_OP_LIMIT || kMinRecordSize[op] == 0) {
            return dec_fail(ext, MCMTL_E_BAD_STREAM, offset, "unknown op %u", op);
        }
        if (size < kMinRecordSize[op]) {
            return dec_fail(ext, MCMTL_E_BAD_STREAM, offset, "op %u record size %u < %u", op, size, kMinRecordSize[op]);
        }
        if (isInit && op != MCMTL_OP_INIT_TEXTURE && op != MCMTL_OP_NOP) {
            return dec_fail(ext, MCMTL_E_BAD_STREAM, offset, "op %u is not allowed in the init stream", op);
        }
        if (op == MCMTL_OP_END_PASS) {
            if (!ctx->inPass) return dec_fail(ext, MCMTL_E_BAD_STREAM, offset, "END_PASS outside a render pass");
        } else if (op >= 32) {
            if (!ctx->inPass) return dec_fail(ext, MCMTL_E_BAD_STREAM, offset, "pass op %u outside a render pass", op);
        } else if (op >= 1 && op < 16 && ctx->inPass) {
            return dec_fail(ext, MCMTL_E_BAD_STREAM, offset, "encoder op %u inside a render pass", op);
        }
        if (ext->raiseAt >= 0 && (int64_t)ext->recordOrdinal == ext->raiseAt) {
            ext->raiseAt = -1;
            ext->ds->testRaiseAtRecord = -1;
            [NSException raise:@"MCMTLDecoderTestException" format:@"test hook: exception at record %llu (op %u)",
                               (unsigned long long)ext->recordOrdinal, op];
        }
        ext->recordOrdinal++;
        ext->curOffset = offset;
        int32_t rc = MCMTL_OK;
        /* M2: copies hoisted before a merged encoder were encoded already; records between merged passes. */
        if ((ext->hoistCount && dec_take_hoisted(ext, offset)) || (ext->mergeOpen && dec_merge_gap(ctx, ext, op, rec, size, offset, &rc))) {
            if (rc != MCMTL_OK) return rc;
            ext->result->recordsDecoded++;
            offset += size;
            continue;
        }
        DEC_SLOW_BEGIN;
        switch (op) {
            case MCMTL_OP_NOP: break;
            case MCMTL_OP_BEGIN_PASS: rc = op_begin_pass(ctx, ext, rec, size, offset); break;
            case MCMTL_OP_END_PASS: rc = op_end_pass(ctx, ext, size, offset); break;
            case MCMTL_OP_CLEAR_TEXTURE: rc = op_clear_texture(ext, rec, offset); break;
            case MCMTL_OP_CLEAR_REGION: rc = op_clear_region(ext, rec, offset); break;
            case MCMTL_OP_COPY_BUFFER: rc = op_copy_buffer(ext, rec, offset); break;
            case MCMTL_OP_COPY_BUFFER_TO_TEXTURE: rc = op_copy_buffer_to_texture(ext, rec, offset); break;
            case MCMTL_OP_COPY_TEXTURE_TO_BUFFER: rc = op_copy_texture_to_buffer(ext, rec, offset); break;
            case MCMTL_OP_COPY_TEXTURE: rc = op_copy_texture(ext, rec, offset); break;
            case MCMTL_OP_PRESENT: rc = op_present(ctx, ext, rec, offset); break;
            case MCMTL_OP_INIT_TEXTURE: rc = op_init_texture(ext, rec, offset); break;
            case MCMTL_OP_UPSCALE:
                rc = dec_fail(ext, MCMTL_E_UNSUPPORTED, offset, "UPSCALE is reserved for M4 and not supported by this decoder");
                break;
            case MCMTL_OP_TIMESTAMP: rc = op_timestamp(ctx, ext, rec, offset); break;
            case MCMTL_OP_PUSH_DEBUG_GROUP: rc = op_push_debug_group(ctx, ext, rec, size, offset); break;
            case MCMTL_OP_POP_DEBUG_GROUP: rc = op_pop_debug_group(ctx, ext); break;
            case MCMTL_OP_SET_PIPELINE: rc = op_set_pipeline(ctx, ext, rec); break;
            case MCMTL_OP_SET_BUFFER: rc = op_set_buffer(ctx, ext, rec, offset); break;
            case MCMTL_OP_SET_TEXTURE: rc = op_set_texture(ctx, ext, rec, offset); break;
            case MCMTL_OP_CLEAR_BINDING: rc = op_clear_binding(ctx, ext, rec, offset); break;
            case MCMTL_OP_SET_VERTEX_BUFFER: rc = op_set_vertex_buffer(ctx, ext, rec, offset); break;
            case MCMTL_OP_SET_INDEX_BUFFER: rc = op_set_index_buffer(ctx, ext, rec, offset); break;
            case MCMTL_OP_SET_SCISSOR: rc = op_set_scissor(ctx, ext, rec); break;
            case MCMTL_OP_RESET_SCISSOR: rc = op_reset_scissor(ctx, ext); break;
            case MCMTL_OP_PUSH_CONSTANTS: rc = op_push_constants(ctx, ext, rec, size, offset); break;
            case MCMTL_OP_DRAW: rc = op_draw(ctx, ext, rec); break;
            case MCMTL_OP_DRAW_INDEXED: rc = op_draw_indexed(ctx, ext, rec); break;
            case MCMTL_OP_DRAW_INDIRECT:
            case MCMTL_OP_DRAW_INDEXED_INDIRECT: rc = op_draw_indirect(ctx, ext, rec, op, offset); break;
            default: rc = dec_fail(ext, MCMTL_E_BAD_STREAM, offset, "unknown op %u", op); break;
        }
        DEC_SLOW_END("op", op);
        if (rc != MCMTL_OK) return rc;
        ext->result->recordsDecoded++;
        offset += size;
    }
    if (ctx->inPass) {
        return dec_fail(ext, MCMTL_E_BAD_STREAM, ext->passOffset, "stream ends inside the render pass begun at offset %llu",
                        (unsigned long long)ext->passOffset);
    }
    return MCMTL_OK;
}

/* @finally part of mcmtl_decoder_submit: end encoders, commit, command-buffer bookkeeping. */
static void dec_finish(mcmtl_decode_ctx *ctx, mcmtl_decoder_ext *ext, int32_t *rc) {
    MCMTLDevice *dev = ext->dev;
    MCMTLDecoderState *ds = ext->ds;
    BOOL poisoned = NO;
    if (ext->renderEncoder) {
        @try {
            dec_end_render_encoder(ctx, ext, UINT64_MAX);   /* error paths included: Store everything */
        } @catch (NSException *e) {
            poisoned = YES;
            if (*rc == MCMTL_OK) { mcmtl_set_error_exception(e); *rc = MCMTL_E_OBJC_EXCEPTION; }
        }
        ext->renderEncoder = nil;
        ctx->renderEncoder = nil;
        ctx->inPass = 0;
    }
    ext->mergeOpen = NO;
    ext->chainActive = NO;
    /* Look-ahead state never outlives the call (CONTRACT.md 4.7): pending clears are encoded now, in stream
     * order relative to everything that used their textures (each use materialized them first). */
    if (!poisoned && (ext->pendingCount || ext->mergeClearPending)) {
        @try {
            dec_merge_break(ext);   /* re-records a depth clear taken for a merged pass that never began */
            dec_pending_materialize_all(ext);
        } @catch (NSException *e) {
            poisoned = YES;
            if (*rc == MCMTL_OK) { mcmtl_set_error_exception(e); *rc = MCMTL_E_OBJC_EXCEPTION; }
        }
    }
    ext->pendingCount = 0;
    ext->mergeClearPending = NO;
    if (ext->copyEncoder) {
        @try {
            [ext->copyEncoder endEncoding];
        } @catch (NSException *e) {
            poisoned = YES;
            if (*rc == MCMTL_OK) { mcmtl_set_error_exception(e); *rc = MCMTL_E_OBJC_EXCEPTION; }
        }
        ext->copyEncoder = nil;
    }
    if (ext->blitEncoder) {
        @try {
            [ext->blitEncoder endEncoding];
        } @catch (NSException *e) {
            poisoned = YES;
            if (*rc == MCMTL_OK) { mcmtl_set_error_exception(e); *rc = MCMTL_E_OBJC_EXCEPTION; }
        }
        ext->blitEncoder = nil;
        ctx->blitEncoder = nil;
    }
    hz_reset(ds);
    if (ext->commandBuffer && !poisoned) {
        @try {
            while (ext->cbGroupDepth) {
                [ext->commandBuffer popDebugGroup];
                ext->cbGroupDepth--;
            }
        } @catch (NSException *e) {
            poisoned = YES;
            if (*rc == MCMTL_OK) { mcmtl_set_error_exception(e); *rc = MCMTL_E_OBJC_EXCEPTION; }
        }
    }
    [ds->labelStack removeAllObjects];
    if (poisoned) {
        /* The open command buffer cannot be committed: drop it (and the ring chunks only it referenced). */
        mcmtl_device_message(dev, "decoder: an encoder could not be ended; the command buffer for timeline value %llu was dropped",
                             (unsigned long long)ext->signalValue);
        ext->commandBuffer = nil;
        ctx->commandBuffer = nil;
        dev->currentCommandBuffer = nil;
        dec_ring_recycle(ds);
        /* A fused frame in that command buffer never reaches its drawable: nothing to restore from. */
        ds->staleTexture = nil;
        ds->staleDrawable = nil;
    }
    ext->fuseDrawable = nil;   /* acquired for a fused frame whose PRESENT was never decoded: back to the pool */
    if (ext->result->drawsSkipped) ext->result->flags |= MCMTL_SUBMIT_RESULT_FLAG_DRAWS_SKIPPED;

    if (ext->commit) {
        id<MTLCommandBuffer> cb = ext->commandBuffer;
        BOOL fresh = NO;
        for (int attempt = 0; attempt < 2; attempt++) {
            @try {
                if (!cb) {
                    cb = [dev->queue commandBuffer];   /* signal-only command buffer */
                    fresh = YES;
                }
                if (!fresh) dec_ring_attach(ds, cb);
                if (ds->fuseTraceCalls) dec_fuse_trace_cb(cb, "signal", ext->signalValue);
                mcmtl_commit_signaled(dev, cb, ext->signalValue);
                ext->result->flags |= MCMTL_SUBMIT_RESULT_FLAG_COMMITTED;
                ds->stats.commandBuffersCommitted++;
                break;
            } @catch (NSException *e) {
                if (*rc == MCMTL_OK) { mcmtl_set_error_exception(e); *rc = MCMTL_E_OBJC_EXCEPTION; }
                if (!fresh) {
                    dec_ring_recycle(ds);
                    ds->staleTexture = nil;   /* a fused frame in it never reaches its drawable: nothing to restore */
                    ds->staleDrawable = nil;
                }
                cb = nil;   /* retry once with a fresh command buffer; the guard CPU-completes otherwise */
                if (fresh) break;
            }
        }
        ext->commandBuffer = nil;
        ctx->commandBuffer = nil;
        dev->currentCommandBuffer = nil;
    } else if (*rc != MCMTL_OK) {
        /* Contract: only a successful non-COMMIT call keeps the command buffer open. */
        ext->commandBuffer = nil;
        ctx->commandBuffer = nil;
        dev->currentCommandBuffer = nil;
        dec_ring_recycle(ds);
    } else {
        dev->currentCommandBuffer = ext->commandBuffer;
    }
    if (ds->occl) dec_occl_end_call(ext);
}

/* Periodic decoder statistics (MCMTL_DECODER_STATS=1 or MCMTL_PROFILE_PASSES): per-frame averages since the
 * previous log, every 600 FRAME_END submits (MCMTL_DECODER_STATS=N with N > 1: every N). Returns the interval, 0 = off. */
static uint32_t dec_stats_log_enabled(void) {
    static int64_t interval = -1;
    if (interval < 0) {
        const char *v = getenv("MCMTL_DECODER_STATS");
        const long n = v ? atol(v) : 0;
        interval = n > 1 ? n : (n == 1 || dec_profile_enabled()) ? 600 : 0;
    }
    return (uint32_t)interval;
}

static void dec_log_stats(MCMTLDecoderState *ds) {
    const mcmtl_decoder_stats *a = &ds->stats, *b = &ds->statsLogged;
    const double f = a->frames > b->frames ? (double)(a->frames - b->frames) : 1.0;
#define DEC_PER_FRAME(field) ((double)(a->field - b->field) / f)
    NSLog(@"mcmtl decoder stats (%llu frames, M2 flags 0x%x) per frame: passes in %.2f -> pass encoders %.2f "
          "(render encoders %.2f, blit encoders %.2f, command buffers %.2f); merges %.2f, merge breaks %.2f, hoists %.2f; "
          "clears recorded %.2f: folded %.2f, dropped %.2f, materialized %.2f, drawn %.2f; stores DontCare %.2f / Store %.2f; "
          "draws %.1f; CPU submit %.3f ms (of which nextDrawable wait %.3f ms)",
          (unsigned long long)(a->frames - b->frames), ds->m2Flags, DEC_PER_FRAME(passesIn), DEC_PER_FRAME(passEncoders),
          DEC_PER_FRAME(renderEncoders), DEC_PER_FRAME(blitEncoders), DEC_PER_FRAME(commandBuffersCommitted), DEC_PER_FRAME(merges),
          DEC_PER_FRAME(mergeBreaks), DEC_PER_FRAME(hoists), DEC_PER_FRAME(clearsRecorded), DEC_PER_FRAME(clearsFolded),
          DEC_PER_FRAME(clearsDropped), DEC_PER_FRAME(clearsMaterialized), DEC_PER_FRAME(clearDraws), DEC_PER_FRAME(dontCareStores),
          DEC_PER_FRAME(lateStores), DEC_PER_FRAME(draws), DEC_PER_FRAME(submitNs) / 1e6, DEC_PER_FRAME(drawableWaitNs) / 1e6);
    if (ds->fuseMode) {
        static const char *const kReasons[DEC_FUSE_REASONS] = {
            "not-flipped", "flip-only", "no-index", "multi-present", "format", "size", "no-clear", "source-use",
            "variants", "backoff", "drawable-nil", "not-reached", "no-drawable",
        };
        NSMutableString *fallbacks = [NSMutableString string];
        for (uint32_t r = 0; r < DEC_FUSE_REASONS; r++) {
            const uint64_t n = a->fusionFallback[r] - b->fusionFallback[r];
            if (n) [fallbacks appendFormat:@" %s=%llu", kReasons[r], (unsigned long long)n];
        }
        const uint64_t fused = a->fusedFrames - b->fusedFrames;
        NSLog(@"mcmtl present fusion (mode %u): fused %llu of %llu frames (%.2f passes into the drawable per fused frame, "
              "wait before the frame %.3f ms); restores %llu, stale dropped %llu, BGRA8 variant builds %llu, backoffs %llu; "
              "fallbacks:%@",
              ds->fuseMode, (unsigned long long)fused, (unsigned long long)(a->frames - b->frames),
              fused ? (double)(a->fusedPasses - b->fusedPasses) / (double)fused : 0.0,
              fused ? (double)(a->fusionWaitNs - b->fusionWaitNs) / (double)fused / 1e6 : 0.0,
              (unsigned long long)(a->fusionRestores - b->fusionRestores), (unsigned long long)(a->fusionStaleDropped - b->fusionStaleDropped),
              (unsigned long long)(a->fusionVariantBuilds - b->fusionVariantBuilds), (unsigned long long)(a->fusionBackoffs - b->fusionBackoffs),
              fallbacks.length ? fallbacks : @" none");
    }
    /* Presentation (CONTRACT.md 16.4): frames rendered for the surface vs presented vs not presented, per second over
     * the period, plus the surface's acquirer thread and presented-handler counters. */
    const uint64_t records = a->presentRecords - b->presentRecords;
    if (records) {
        const uint64_t nowNs = clock_gettime_nsec_np(CLOCK_UPTIME_RAW);
        const double sec = ds->statsLoggedNs && nowNs > ds->statsLoggedNs ? (double)(nowNs - ds->statsLoggedNs) / 1e9 : 0.0;
        const uint64_t presented = a->presentsEncoded - b->presentsEncoded, skipped = a->presentsSkipped - b->presentsSkipped;
        MCMTLSurface *surface = ds->presentSurface;
        mcmtl_mailbox_stats m, mb0;
        mcmtl_mailbox_get_stats(surface, &m);
        if (ds->mailboxLoggedSurface == (__bridge const void *)surface) mb0 = ds->mailboxLogged;
        else memset(&mb0, 0, sizeof mb0);
        ds->mailboxLogged = m;
        ds->mailboxLoggedSurface = (__bridge const void *)surface;
        const uint64_t acquires = m.acquires - mb0.acquires, shown = m.presentsDisplayed - mb0.presentsDisplayed;
        NSString *mode = m.active ? [NSString stringWithFormat:@"mailbox, %u drawables, queue %u", m.drawableCount, m.maxQueued]
                                  : [NSString stringWithFormat:@"blocking, %u drawables", m.drawableCount];
        NSLog(@"mcmtl present (%@): %llu frames rendered (%.1f/s), presented %llu (%.1f/s; fused %llu), not presented %llu "
              "(no drawable %llu, queue full %llu, deferred %llu, stale %llu), nil %llu; render-thread nextDrawable %.3f ms/frame; "
              "acquirer: %llu drawables, %.3f ms avg wait, nil %llu, discarded %llu; display: shown %llu, dropped by Core Animation "
              "%llu, present->on screen %.2f ms avg; gate timeouts %llu",
              mode, (unsigned long long)records, sec > 0 ? (double)records / sec : 0.0, (unsigned long long)presented,
              sec > 0 ? (double)presented / sec : 0.0, (unsigned long long)(a->fusedFrames - b->fusedFrames), (unsigned long long)skipped,
              (unsigned long long)(a->presentSkip[DEC_SKIP_NO_DRAWABLE] - b->presentSkip[DEC_SKIP_NO_DRAWABLE]),
              (unsigned long long)(a->presentSkip[DEC_SKIP_QUEUE_FULL] - b->presentSkip[DEC_SKIP_QUEUE_FULL]),
              (unsigned long long)(a->presentSkip[DEC_SKIP_DEFERRED] - b->presentSkip[DEC_SKIP_DEFERRED]),
              (unsigned long long)(a->presentSkip[DEC_SKIP_STALE] - b->presentSkip[DEC_SKIP_STALE]),
              (unsigned long long)(a->presentsNil - b->presentsNil), (double)(a->drawableWaitNs - b->drawableWaitNs) / (double)records / 1e6,
              (unsigned long long)acquires, acquires ? (double)(m.acquireWaitNs - mb0.acquireWaitNs) / (double)acquires / 1e6 : 0.0,
              (unsigned long long)(m.acquireNil - mb0.acquireNil), (unsigned long long)(m.discarded - mb0.discarded + m.staleDiscarded - mb0.staleDiscarded),
              (unsigned long long)shown, (unsigned long long)(m.presentsDropped - mb0.presentsDropped),
              shown ? (double)(m.displayLatencyNs - mb0.displayLatencyNs) / (double)shown / 1e6 : 0.0,
              (unsigned long long)(m.gateTimeouts - mb0.gateTimeouts));
    }
    {
        /* Terrain ICB (CONTRACT.md 16.5). Also logged with the mode off: per-record indirect draws per frame. */
        static const char *const kIcbReasons[DEC_ICB_REASONS] = {
            "variant-pending", "variant-failed", "fan", "gpu-written-args", "slot15", "small", "cache-slots", "alloc",
            "occlusion",
        };
        NSMutableString *icbFallbacks = [NSMutableString string];
        for (uint32_t r = 0; r < DEC_ICB_REASONS; r++) {
            const uint64_t n = a->icbFallback[r] - b->icbFallback[r];
            if (n) [icbFallbacks appendFormat:@" %s=%.2f", kIcbReasons[r], (double)n / f];
        }
        NSLog(@"mcmtl terrain ICB (mode %u, MCMTL_TERRAIN_ICB): per frame %.2f ICB ranges executed (%.1f records, %.2f reused, "
              "%.1f records encoded), %.1f indirect records drawn one by one; ICBs created %llu, argument buffers %.2f/frame, "
              "variant builds %llu; per-record fallbacks per frame:%@",
              ds->icbMode, DEC_PER_FRAME(icbGroups), DEC_PER_FRAME(icbRecords), DEC_PER_FRAME(icbHits), DEC_PER_FRAME(icbEncodedRecords),
              DEC_PER_FRAME(indirectRecordDraws), (unsigned long long)(a->icbCreated - b->icbCreated), DEC_PER_FRAME(icbArgBuffers),
              (unsigned long long)(a->icbVariantBuilds - b->icbVariantBuilds), icbFallbacks.length ? icbFallbacks : @" none");
    }
    if (ds->occl) {
        /* Terrain occlusion (CONTRACT.md 16.6): per planned frame. */
        static const char *const kNoPlan[DEC_OCCL_NP_REASONS] = { "no-index", "no-terrain", "passes", "unresolved", "transform", "records", "ring" };
        NSMutableString *noPlan = [NSMutableString string];
        for (uint32_t r = 0; r < DEC_OCCL_NP_REASONS; r++) {
            const uint64_t n = a->occlNoPlan[r] - b->occlNoPlan[r];
            if (n) [noPlan appendFormat:@" %s=%llu", kNoPlan[r], (unsigned long long)n];
        }
        const uint64_t planned = a->occlFrames - b->occlFrames;
        const double pf = planned ? (double)planned : 1.0;
        const double quads = (double)(a->occlPlannedQuads - b->occlPlannedQuads);
        const double skipped = (double)(a->occlSkippedQuads - b->occlSkippedQuads);
        NSLog(@"mcmtl occlusion (mode %u = %s, confirm %u, near %.0f): planned %llu of %llu frames; per planned frame: sections %.1f, "
              "culled %.1f (dry run would cull %.1f), records skipped %.1f, quads skipped %.0f of %.0f (%.1f%%), geometry queries %.1f, "
              "box queries %.1f, planner %.3f ms; eye/projection resets %llu, sections invalidated by changes %llu, result frames "
              "used %llu / dropped %llu; no plan:%@",
              ds->occl->mode, ds->occl->mode == DEC_OCCL_DRY ? "dry run" : "cull", ds->occl->confirm, ds->occl->nearBlocks,
              (unsigned long long)planned, (unsigned long long)(a->frames - b->frames), (double)(a->occlSections - b->occlSections) / pf,
              (double)(a->occlCulledSections - b->occlCulledSections) / pf, (double)(a->occlWouldCull - b->occlWouldCull) / pf,
              (double)(a->occlSkippedRecords - b->occlSkippedRecords) / pf, skipped / pf, quads / pf, quads > 0 ? 100.0 * skipped / quads : 0.0,
              (double)(a->occlGeomQueries - b->occlGeomQueries) / pf, (double)(a->occlBoxTests - b->occlBoxTests) / pf,
              (double)(a->occlPlanNs - b->occlPlanNs) / pf / 1e6, (unsigned long long)(a->occlEyeResets - b->occlEyeResets),
              (unsigned long long)(a->occlInvalidated - b->occlInvalidated), (unsigned long long)(a->occlResultFrames - b->occlResultFrames),
              (unsigned long long)(a->occlResultsDropped - b->occlResultsDropped), noPlan.length ? noPlan : @" none");
    }
#undef DEC_PER_FRAME
    ds->statsLogged = *a;
    ds->statsLoggedNs = clock_gettime_nsec_np(CLOCK_UPTIME_RAW);
}

static int32_t mcmtl_decoder_submit_impl(MCMTLDevice *dev, const mcmtl_submit_info *info, mcmtl_submit_result *result);

int32_t mcmtl_decoder_submit(MCMTLDevice *dev, const mcmtl_submit_info *info, mcmtl_submit_result *result) {
    uint64_t start = clock_gettime_nsec_np(CLOCK_UPTIME_RAW);
    int32_t r = mcmtl_decoder_submit_impl(dev, info, result);
    MCMTLDecoderState *ds = dev->decoderState;
    if (ds) ds->stats.submitNs += clock_gettime_nsec_np(CLOCK_UPTIME_RAW) - start;
    return r;
}

static int32_t mcmtl_decoder_submit_impl(MCMTLDevice *dev, const mcmtl_submit_info *info, mcmtl_submit_result *result) {
    MCMTLDecoderState *ds = dev->decoderState;
    if (!ds || !dev->dummyBuffer) {
        mcmtl_set_error("mcmtl_submit: decoder state missing (mcmtl_decoder_device_init not called or failed)");
        return MCMTL_E_INTERNAL;
    }
    /* Ring chunks of a command buffer that was dropped uncommitted (guard error path) are free again. */
    if (!dev->currentCommandBuffer) dec_ring_recycle(ds);
    [ds->labelStack removeAllObjects];
    DEC_SLOW_BEGIN;
    dec_fuse_drain(dev, ds);   /* present fusion: BGRA8 variants compiled since the last call */

    DEC_SLOW_END("drain", 0);
    mcmtl_decode_ctx ctx;
    memset(&ctx, 0, sizeof ctx);
    mcmtl_decoder_ext ext = { 0 };
    ctx.dev = dev;
    ctx.info = info;
    ctx.result = result;
    ctx.ext = &ext;
    ext.dev = dev;
    ext.ds = ds;
    ext.ctx = &ctx;
    ext.result = result;
    ext.commit = (info->flags & MCMTL_SUBMIT_FLAG_COMMIT) ? 1u : 0u;
    ext.signalValue = info->signalValue;
    ext.labels = (dev->flags & MCMTL_DEVICE_FLAG_DEBUG_LABELS) != 0;
    ext.raiseAt = ds->testRaiseAtRecord;
    ext.pipelineInvalid = YES;
    ext.m2 = ds->m2Flags;
    if (ds->occl && (info->flags & MCMTL_SUBMIT_FLAG_FRAME_END)) ds->occl->frame++;   /* occlusion: the frame clock */
    if (dev->currentCommandBuffer) {
        /* Work of earlier non-COMMIT calls: this call commits it (or, on error, drops it per the contract)
         * even if it encodes nothing itself. */
        ext.commandBuffer = dev->currentCommandBuffer;
        ctx.commandBuffer = ext.commandBuffer;
        ext.cbHasWork = YES;
    }

    int32_t rc = MCMTL_OK;
    @try {
        if (info->initStreamLength) {
            rc = dec_stream(&ctx, &ext, (const uint8_t *)info->initStream, info->initStreamLength, YES);
        }
        if (info->streamLength) {
            int32_t mainRc = dec_stream(&ctx, &ext, (const uint8_t *)info->stream, info->streamLength, NO);
            if (rc == MCMTL_OK) rc = mainRc;
        } else if (ds->staleTexture && (info->flags & MCMTL_SUBMIT_FLAG_FRAME_END)) {
            /* A whole frame without a main stream never referenced the stale fused source (CONTRACT.md 16.3). */
            ds->staleTexture = nil;
            ds->staleDrawable = nil;
            ds->stats.fusionStaleDropped++;
        }
    } @catch (NSException *e) {
        if (!ext.failed) mcmtl_set_error_exception(e);
        ext.failed = 1;
        rc = MCMTL_E_OBJC_EXCEPTION;
    } @catch (id thrown) {
        if (!ext.failed) mcmtl_set_error("Objective-C object thrown in the decoder: %s", [[thrown description] UTF8String]);
        ext.failed = 1;
        rc = MCMTL_E_OBJC_EXCEPTION;
    } @finally {
        DEC_SLOW_BEGIN;
        dec_finish(&ctx, &ext, &rc);
        DEC_SLOW_END("finish", 0);
    }
    if (info->flags & MCMTL_SUBMIT_FLAG_FRAME_END) {
        if (ds->fuseTraceCalls) ds->fuseTraceCalls--;
        if (ds->copyStats) dec_copy_frame_end(ds);
        ds->stats.frames++;
        const uint32_t interval = dec_stats_log_enabled();
        if (interval && (ds->stats.frames % interval) == 0) dec_log_stats(ds);
    }
    return rc;
}

/* ---------------------------------------------------------------------------------------------- */
/* Frame trace (CONTRACT.md 16.R5-B, mcmetal_frametrace.m): counters and effective configuration    */
/* ---------------------------------------------------------------------------------------------- */

#import "mcmetal_frametrace.h"

_Static_assert(DEC_FUSE_REASONS == MCMTL_FT_FUSE_REASONS, "frame trace: fusion fallback reasons changed");
_Static_assert(DEC_SKIP_REASONS == MCMTL_FT_SKIP_REASONS, "frame trace: present skip reasons changed");

uint32_t mcmtl_decoder_ft_counters(MCMTLDevice *dev, uint64_t *out, uint32_t capacity) {
    MCMTLDecoderState *ds = dev ? dev->decoderState : nil;
    if (!ds || !out) return 0;
    const mcmtl_decoder_stats *s = &ds->stats;
    uint64_t v[MCMTL_FT_C_COUNT];
    v[MCMTL_FT_C_FRAMES] = s->frames;
    v[MCMTL_FT_C_CB_COMMITTED] = s->commandBuffersCommitted;
    v[MCMTL_FT_C_UNTIMED_COMMITS] = s->untimedCommits;
    v[MCMTL_FT_C_RENDER_ENCODERS] = s->renderEncoders;
    v[MCMTL_FT_C_BLIT_ENCODERS] = s->blitEncoders;
    v[MCMTL_FT_C_DRAWS] = s->draws;
    v[MCMTL_FT_C_SUBMIT_NS] = s->submitNs;
    v[MCMTL_FT_C_DRAWABLE_WAIT_NS] = s->drawableWaitNs;
    v[MCMTL_FT_C_FUSION_WAIT_NS] = s->fusionWaitNs;
    v[MCMTL_FT_C_FUSED_FRAMES] = s->fusedFrames;
    v[MCMTL_FT_C_FUSION_RESTORES] = s->fusionRestores;
    v[MCMTL_FT_C_FUSION_BACKOFFS] = s->fusionBackoffs;
    v[MCMTL_FT_C_PRESENT_RECORDS] = s->presentRecords;
    v[MCMTL_FT_C_PRESENTS_ENCODED] = s->presentsEncoded;
    v[MCMTL_FT_C_PRESENTS_NIL] = s->presentsNil;
    v[MCMTL_FT_C_MAILBOX_RECORDS] = s->mailboxRecords;
    v[MCMTL_FT_C_PRESENTS_SKIPPED] = s->presentsSkipped;
    v[MCMTL_FT_C_HOISTS] = s->hoists;
    v[MCMTL_FT_C_MERGES] = s->merges;
    v[MCMTL_FT_C_ICB_GROUPS] = s->icbGroups;
    v[MCMTL_FT_C_INDIRECT_RECORD_DRAWS] = s->indirectRecordDraws;
    v[MCMTL_FT_C_OCCL_FRAMES] = s->occlFrames;
    v[MCMTL_FT_C_OCCL_CULLED_SECTIONS] = s->occlCulledSections;
    for (uint32_t r = 0; r < MCMTL_FT_FUSE_REASONS; r++) v[MCMTL_FT_C_FUSE_FALLBACK + r] = s->fusionFallback[r];
    for (uint32_t r = 0; r < MCMTL_FT_SKIP_REASONS; r++) v[MCMTL_FT_C_PRESENT_SKIP + r] = s->presentSkip[r];
    const uint32_t n = capacity < MCMTL_FT_C_COUNT ? capacity : MCMTL_FT_C_COUNT;
    memcpy(out, v, n * sizeof(uint64_t));
    return n;
}

uint32_t mcmtl_decoder_ft_config(MCMTLDevice *dev, char *buffer, uint32_t capacity) {
    MCMTLDecoderState *ds = dev ? dev->decoderState : nil;
    if (!buffer || capacity == 0) return 0;
    if (!ds) {
        const int n = snprintf(buffer, capacity, "\"decoder\": false");
        return n > 0 ? ((uint32_t)n < capacity ? (uint32_t)n : capacity - 1) : 0;
    }
    mcmtl_mailbox_stats mb;
    memset(&mb, 0, sizeof mb);
    MCMTLSurface *surface = ds->presentSurface;
    const BOOL haveSurface = surface != nil;
    if (haveSurface) mcmtl_mailbox_get_stats(surface, &mb);
    const uint32_t m2 = ds->m2Flags;
    const int n = snprintf(buffer, capacity,
        "\"decoder\": true, \"presentFusionEnv\": %d, \"fuseMode\": %u, \"fuseMaxWaitUs\": %llu, \"fuseBackoffFramesLeft\": %u, "
        "\"m2Flags\": %u, \"m2Fold\": %d, \"m2LateStore\": %d, \"m2Merge\": %d, \"m2Hoist\": %d, \"m2ClearDraw\": %d, "
        "\"m2ZeroClearVertex\": %d, \"terrainIcbEnv\": %d, \"icbMode\": %u, \"icbMinRecords\": %u, \"occlusionMode\": %u, "
        "\"occlusionConfirm\": %u, \"occlusionNear\": %.1f, \"occlusionMinAge\": %u, \"mailboxLatePresent\": %d, "
        "\"profilePasses\": %d, \"decoderStatsInterval\": %u, \"debugLabels\": %d, \"validation\": %d, "
        "\"surfaceKnown\": %d, \"mailboxActive\": %u, \"drawableCount\": %u, \"mailboxMaxQueued\": %u",
        mcmtl_present_fusion_env(), ds->fuseMode, (unsigned long long)(ds->fuseMaxWaitNs / 1000ull), ds->fuseBackoff,
        m2, (m2 & DEC_M2_FOLD) != 0, (m2 & DEC_M2_LATE_STORE) != 0, (m2 & DEC_M2_MERGE) != 0, (m2 & DEC_M2_HOIST) != 0,
        (m2 & DEC_M2_CLEAR_DRAW) != 0, (m2 & DEC_M2_ZERO_CLEAR_VERTEX) != 0, mcmtl_terrain_icb_env(), ds->icbMode,
        ds->icbMinRecords, ds->occl ? ds->occl->mode : 0u, ds->occl ? ds->occl->confirm : 0u,
        ds->occl ? (double)ds->occl->nearBlocks : 0.0, ds->occl ? ds->occl->minAge : 0u, ds->mailboxLatePresent ? 1 : 0,
        dec_profile_enabled(), dec_stats_log_enabled(), (dev->flags & MCMTL_DEVICE_FLAG_DEBUG_LABELS) != 0,
        (dev->flags & MCMTL_DEVICE_FLAG_VALIDATION) != 0, haveSurface ? 1 : 0, mb.active, mb.drawableCount, mb.maxQueued);
    if (n < 0) return 0;
    return (uint32_t)n < capacity ? (uint32_t)n : capacity - 1;
}
