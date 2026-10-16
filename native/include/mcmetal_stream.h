/*
 * mcmetal_stream.h -- binary command stream recorded by Java (StreamWriter) and decoded by mcmtl_submit.
 *
 * FROZEN CONTRACT FILE (owner: architect). Any change bumps MCMTL_STREAM_VERSION *and* MCMTL_ABI_VERSION
 * (mcmetal.h) and updates StreamOps.java in the same commit. Semantics: docs/metal/CONTRACT.md section 4.
 *
 * Format:
 *  - A stream is a contiguous byte range of records. Every record starts at a 16-byte aligned offset
 *    and begins with { uint16_t op; uint16_t size; uint32_t <named operand>; }.
 *  - `size` is the total record size in bytes, header included: a multiple of 16, >= 16, <= 65520, and
 *    >= the minimum size listed for the op below. The decoder advances by `size`; bytes between the end
 *    of the defined fields and `size` are padding (written as 0 by Java, ignored by native).
 *  - Little-endian, natural alignment, no implicit padding. Offsets are pinned by MCMTL_ASSERT_OFFSET;
 *    the Java mirror constants follow `mcmtl_cmd_<name>.<field>` -> `StreamOps.<NAME>_<FIELD>` and
 *    `MCMTL_CMD_SIZE_<X>` -> `StreamOps.SIZE_<X>`.
 *  - Resources are referenced by mcmtl_handle (+1 handles owned by Java; the decoder never retains or
 *    releases them beyond the command buffer's own retention).
 *  - Variable payloads (labels, push constants) are copied inline at record time, right after the fixed
 *    fields, and padded to 16 bytes. Bulk data (writeToBuffer / writeToTexture sources) is NOT inline:
 *    Java copies it into transient staging memory and records a COPY_* record referencing that buffer.
 *  - Ops 1..15 are encoder-level (outside a render pass), 16..31 are valid anywhere, 32..63 only between
 *    BEGIN_PASS and END_PASS. A stream passed to one mcmtl_submit call must not end inside a pass.
 *  - The init stream (mcmtl_submit_info.initStream) may only contain INIT_TEXTURE and NOP records; it is
 *    decoded before the main stream, so any other op there would break the stream order (BAD_STREAM).
 */
#ifndef MCMETAL_STREAM_H
#define MCMETAL_STREAM_H

#include "mcmetal.h"

#ifdef __cplusplus
extern "C" {
#endif

#define MCMTL_STREAM_VERSION    2u   /* 2: UPSCALE reserved, init stream = INIT_TEXTURE only */
#define MCMTL_STREAM_ALIGNMENT  16u
#define MCMTL_STREAM_MAX_RECORD 65520u

/* ---------------------------------------------------------------------------------------------- */
/* Opcodes                                                                                         */
/* ---------------------------------------------------------------------------------------------- */

#define MCMTL_OP_NOP                     0u  /* padding; any size >= 16                               */
/* encoder-level (outside a render pass) */
#define MCMTL_OP_BEGIN_PASS              1u  /* createRenderPass                                       */
#define MCMTL_OP_END_PASS                2u  /* submitRenderPass                                       */
#define MCMTL_OP_CLEAR_TEXTURE           3u  /* clearColorTexture / clearDepthTexture / both (2 records) */
#define MCMTL_OP_CLEAR_REGION            4u  /* clearColorAndDepthTextures(..., x, y, w, h, mip)        */
#define MCMTL_OP_COPY_BUFFER             5u  /* copyToBuffer, writeToBuffer (staging -> dst)            */
#define MCMTL_OP_COPY_BUFFER_TO_TEXTURE  6u  /* writeToTexture (staging), copyBufferToTexture          */
#define MCMTL_OP_COPY_TEXTURE_TO_BUFFER  7u  /* copyTextureToBuffer (both overloads)                   */
#define MCMTL_OP_COPY_TEXTURE            8u  /* copyTextureToTexture                                   */
#define MCMTL_OP_PRESENT                 9u  /* GpuSurfaceBackend.blitFromTexture                      */
#define MCMTL_OP_INIT_TEXTURE           10u  /* zero-fill a new texture (init stream)                  */
#define MCMTL_OP_UPSCALE                11u  /* M4 reserved (render scale / MetalFX); M1 rejects it    */
/* valid inside and outside a render pass */
#define MCMTL_OP_TIMESTAMP              16u  /* writeTimestamp (encoder and pass level)                */
#define MCMTL_OP_PUSH_DEBUG_GROUP       17u  /* pass label / RenderPass.pushDebugGroup (labels only)   */
#define MCMTL_OP_POP_DEBUG_GROUP        18u
/* render-pass level (between BEGIN_PASS and END_PASS) */
#define MCMTL_OP_SET_PIPELINE           32u
#define MCMTL_OP_SET_BUFFER             33u  /* setUniform: UNIFORM_BUFFER                             */
#define MCMTL_OP_SET_TEXTURE            34u  /* setUniform: COMBINED_IMAGE_SAMPLER / TEXEL_BUFFER      */
#define MCMTL_OP_CLEAR_BINDING          35u  /* setUniform(index, null)                                */
#define MCMTL_OP_SET_VERTEX_BUFFER      36u  /* setVertexBuffer (non-null only)                        */
#define MCMTL_OP_SET_INDEX_BUFFER       37u
#define MCMTL_OP_SET_SCISSOR            38u  /* enableScissor                                          */
#define MCMTL_OP_RESET_SCISSOR          39u  /* disableScissor (= render area)                         */
#define MCMTL_OP_PUSH_CONSTANTS         40u
#define MCMTL_OP_DRAW                   48u
#define MCMTL_OP_DRAW_INDEXED           49u
#define MCMTL_OP_DRAW_INDIRECT          50u  /* stride 16 (MTLDrawPrimitivesIndirectArguments)         */
#define MCMTL_OP_DRAW_INDEXED_INDIRECT  51u  /* stride 20 (MTLDrawIndexedPrimitivesIndirectArguments)  */
#define MCMTL_OP_LIMIT                  64u  /* every valid op is < MCMTL_OP_LIMIT                     */

/* Struct ids for mcmtl_abi_struct_size(). */
#define MCMTL_STRUCT_CMD_HEADER                 64u
#define MCMTL_STRUCT_CMD_EMPTY                  65u
#define MCMTL_STRUCT_CMD_BEGIN_PASS             66u
#define MCMTL_STRUCT_CMD_COLOR_ATTACHMENT       67u
#define MCMTL_STRUCT_CMD_END_PASS               68u
#define MCMTL_STRUCT_CMD_CLEAR_TEXTURE          69u
#define MCMTL_STRUCT_CMD_CLEAR_REGION           70u
#define MCMTL_STRUCT_CMD_COPY_BUFFER            71u
#define MCMTL_STRUCT_CMD_COPY_BUFFER_TEXTURE    72u
#define MCMTL_STRUCT_CMD_COPY_TEXTURE           73u
#define MCMTL_STRUCT_CMD_PRESENT                74u
#define MCMTL_STRUCT_CMD_INIT_TEXTURE           75u
#define MCMTL_STRUCT_CMD_TIMESTAMP              76u
#define MCMTL_STRUCT_CMD_PUSH_DEBUG_GROUP       77u
#define MCMTL_STRUCT_CMD_SET_PIPELINE           78u
#define MCMTL_STRUCT_CMD_SET_BUFFER             79u
#define MCMTL_STRUCT_CMD_SET_TEXTURE            80u
#define MCMTL_STRUCT_CMD_CLEAR_BINDING          81u
#define MCMTL_STRUCT_CMD_SET_VERTEX_BUFFER      82u
#define MCMTL_STRUCT_CMD_SET_INDEX_BUFFER       83u
#define MCMTL_STRUCT_CMD_SET_SCISSOR            84u
#define MCMTL_STRUCT_CMD_PUSH_CONSTANTS         85u
#define MCMTL_STRUCT_CMD_DRAW                   86u
#define MCMTL_STRUCT_CMD_DRAW_INDEXED           87u
#define MCMTL_STRUCT_CMD_DRAW_INDIRECT          88u
#define MCMTL_STRUCT_CMD_UPSCALE                89u

/* Record sizes (fixed-size records) and minimum sizes (variable-size records). */
#define MCMTL_CMD_SIZE_EMPTY                    16u  /* NOP, POP_DEBUG_GROUP, RESET_SCISSOR             */
#define MCMTL_CMD_SIZE_BEGIN_PASS_BASE          48u  /* + 32 * colorCount                              */
#define MCMTL_CMD_SIZE_COLOR_ATTACHMENT         32u
#define MCMTL_CMD_SIZE_END_PASS                 16u
#define MCMTL_CMD_SIZE_CLEAR_TEXTURE            48u
#define MCMTL_CMD_SIZE_CLEAR_REGION             64u
#define MCMTL_CMD_SIZE_COPY_BUFFER              48u
#define MCMTL_CMD_SIZE_COPY_BUFFER_TEXTURE      64u
#define MCMTL_CMD_SIZE_COPY_TEXTURE             64u
#define MCMTL_CMD_SIZE_PRESENT                  32u
#define MCMTL_CMD_SIZE_INIT_TEXTURE             16u
#define MCMTL_CMD_SIZE_TIMESTAMP                16u
#define MCMTL_CMD_SIZE_PUSH_DEBUG_GROUP_MIN     16u  /* align16(8 + labelLength)                      */
#define MCMTL_CMD_SIZE_SET_PIPELINE             16u
#define MCMTL_CMD_SIZE_SET_BUFFER               32u
#define MCMTL_CMD_SIZE_SET_TEXTURE              32u
#define MCMTL_CMD_SIZE_CLEAR_BINDING            16u
#define MCMTL_CMD_SIZE_SET_VERTEX_BUFFER        32u
#define MCMTL_CMD_SIZE_SET_INDEX_BUFFER         16u
#define MCMTL_CMD_SIZE_SET_SCISSOR              32u
#define MCMTL_CMD_SIZE_PUSH_CONSTANTS_MIN       16u  /* 16 + align16(byteLength)                      */
#define MCMTL_CMD_SIZE_DRAW                     32u
#define MCMTL_CMD_SIZE_DRAW_INDEXED             32u
#define MCMTL_CMD_SIZE_DRAW_INDIRECT            32u
#define MCMTL_CMD_SIZE_UPSCALE                  64u

/* Flag and enum values used inside records. */
#define MCMTL_PASS_FLAG_DEPTH_CLEAR      (1u << 0)  /* BEGIN_PASS: depth loadAction = Clear (depthClearValue) */
#define MCMTL_ASPECT_COLOR               (1u << 0)  /* CLEAR_TEXTURE.aspects                            */
#define MCMTL_ASPECT_DEPTH               (1u << 1)
#define MCMTL_ASPECT_STENCIL             (1u << 2)  /* cleared to 0; RenderPearl never requests it      */
#define MCMTL_COPY_FLAG_DRAW             (1u << 0)  /* COPY_TEXTURE: formats differ -> nearest-sampling draw into dst (RenderTarget) */
#define MCMTL_INIT_TEXTURE_ZERO          0u         /* INIT_TEXTURE.mode: all levels and slices to 0     */
#define MCMTL_BINDING_KIND_BUFFER        0u         /* CLEAR_BINDING.kind                               */
#define MCMTL_BINDING_KIND_TEXTURE       1u         /* texture + sampler at the same index              */
#define MCMTL_PRESENT_FLAG_NONE          0u         /* PRESENT.flags; scaling modes reserved for M4     */
#define MCMTL_INDIRECT_STRIDE_DRAW       16u
#define MCMTL_INDIRECT_STRIDE_DRAW_INDEXED 20u
#define MCMTL_UPSCALE_MODE_LINEAR        0u         /* UPSCALE.mode: bilinear draw                      */
#define MCMTL_UPSCALE_MODE_NEAREST       1u         /* nearest draw                                     */
#define MCMTL_UPSCALE_MODE_METALFX_SPATIAL 2u       /* MTLFXSpatialScaler (MCMTL_FEATURE_METALFX_SPATIAL) */

/* ---------------------------------------------------------------------------------------------- */
/* Records                                                                                         */
/* ---------------------------------------------------------------------------------------------- */

/* Generic view of the first 8 bytes of every record. */
typedef struct mcmtl_cmd_header {
    uint16_t op;              /* 0 MCMTL_OP_*                                                          */
    uint16_t size;            /* 2 total record bytes                                                  */
    uint32_t arg;             /* 4 op-specific operand (named per record below)                        */
} mcmtl_cmd_header;           /* 8 */
MCMTL_ASSERT_SIZE(mcmtl_cmd_header, 8);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_header, op, 0);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_header, size, 2);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_header, arg, 4);

/* NOP, POP_DEBUG_GROUP, RESET_SCISSOR: no operands. */
typedef struct mcmtl_cmd_empty {
    uint16_t op;              /*  0 */
    uint16_t size;            /*  2 16 (NOP: any multiple of 16)                                       */
    uint32_t reserved0;       /*  4 0 */
    uint64_t reserved1;       /*  8 0 */
} mcmtl_cmd_empty;            /* 16 */
MCMTL_ASSERT_SIZE(mcmtl_cmd_empty, 16);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_empty, op, 0);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_empty, size, 2);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_empty, reserved0, 4);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_empty, reserved1, 8);

/* One color attachment slot of BEGIN_PASS. */
typedef struct mcmtl_cmd_color_attachment {
    mcmtl_handle texture;     /*  0 parent texture handle (MetalTexture), 0 = unused slot              */
    uint32_t level;           /*  8 mip level rendered = view.baseMipLevel(); slice is always 0        */
    uint32_t reserved;        /* 12 0 */
    float    clearColor[4];   /* 16 RGBA; used iff bit i of colorClearMask                             */
} mcmtl_cmd_color_attachment; /* 32 */
MCMTL_ASSERT_SIZE(mcmtl_cmd_color_attachment, 32);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_color_attachment, texture, 0);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_color_attachment, level, 8);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_color_attachment, reserved, 12);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_color_attachment, clearColor, 16);

/* BEGIN_PASS: size = 48 + 32 * colorCount, followed by colorCount mcmtl_cmd_color_attachment. */
typedef struct mcmtl_cmd_begin_pass {
    uint16_t op;              /*  0 MCMTL_OP_BEGIN_PASS                                                */
    uint16_t size;            /*  2 */
    uint32_t colorCount;      /*  4 0..8 = descriptor.colorAttachments().size() (null slots included)  */
    uint32_t flags;           /*  8 MCMTL_PASS_FLAG_*                                                  */
    uint32_t colorClearMask;  /* 12 bit i: color i loadAction = Clear, else Load                       */
    int32_t  renderX;         /* 16 render area (memory rows, no flip)                                 */
    int32_t  renderY;         /* 20 */
    int32_t  renderWidth;     /* 24 */
    int32_t  renderHeight;    /* 28 */
    float    depthClearValue; /* 32 used iff MCMTL_PASS_FLAG_DEPTH_CLEAR                               */
    uint32_t depthLevel;      /* 36 mip level of the depth attachment                                  */
    mcmtl_handle depthTexture;/* 40 parent depth texture handle, 0 = no depth attachment               */
} mcmtl_cmd_begin_pass;       /* 48 */
MCMTL_ASSERT_SIZE(mcmtl_cmd_begin_pass, 48);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_begin_pass, op, 0);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_begin_pass, size, 2);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_begin_pass, colorCount, 4);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_begin_pass, flags, 8);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_begin_pass, colorClearMask, 12);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_begin_pass, renderX, 16);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_begin_pass, renderY, 20);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_begin_pass, renderWidth, 24);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_begin_pass, renderHeight, 28);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_begin_pass, depthClearValue, 32);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_begin_pass, depthLevel, 36);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_begin_pass, depthTexture, 40);

/* END_PASS. */
typedef struct mcmtl_cmd_end_pass {
    uint16_t op;              /*  0 */
    uint16_t size;            /*  2 16 */
    uint32_t flags;           /*  4 0 (reserved: store-action hints, G11)                              */
    uint64_t reserved;        /*  8 0 */
} mcmtl_cmd_end_pass;         /* 16 */
MCMTL_ASSERT_SIZE(mcmtl_cmd_end_pass, 16);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_end_pass, op, 0);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_end_pass, size, 2);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_end_pass, flags, 4);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_end_pass, reserved, 8);

/* CLEAR_TEXTURE: clear levels [baseLevel, baseLevel+levelCount) of slice `slice`. */
typedef struct mcmtl_cmd_clear_texture {
    uint16_t op;              /*  0 */
    uint16_t size;            /*  2 48 */
    uint32_t aspects;         /*  4 MCMTL_ASPECT_* to clear                                            */
    mcmtl_handle texture;     /*  8 */
    uint32_t baseLevel;       /* 16 0 */
    uint32_t levelCount;      /* 20 RenderPearl: all mips (texture.getMipLevels())                     */
    uint32_t slice;           /* 24 0 */
    float    depth;           /* 28 used with MCMTL_ASPECT_DEPTH (the double from the API, cast)       */
    float    color[4];        /* 32 RGBA, used with MCMTL_ASPECT_COLOR                                 */
} mcmtl_cmd_clear_texture;    /* 48 */
MCMTL_ASSERT_SIZE(mcmtl_cmd_clear_texture, 48);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_clear_texture, op, 0);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_clear_texture, size, 2);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_clear_texture, aspects, 4);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_clear_texture, texture, 8);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_clear_texture, baseLevel, 16);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_clear_texture, levelCount, 20);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_clear_texture, slice, 24);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_clear_texture, depth, 28);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_clear_texture, color, 32);

/* CLEAR_REGION: clear rect (x, y, w, h) of `level` of a color texture and/or a depth texture. */
typedef struct mcmtl_cmd_clear_region {
    uint16_t op;              /*  0 */
    uint16_t size;            /*  2 64 */
    uint32_t level;           /*  4 */
    mcmtl_handle colorTexture;/*  8 0 = none                                                           */
    mcmtl_handle depthTexture;/* 16 0 = none                                                           */
    int32_t  x;               /* 24 memory rows, no flip                                               */
    int32_t  y;               /* 28 */
    int32_t  width;           /* 32 */
    int32_t  height;          /* 36 */
    float    color[4];        /* 40 */
    float    depth;           /* 56 */
    uint32_t reserved;        /* 60 */
} mcmtl_cmd_clear_region;     /* 64 */
MCMTL_ASSERT_SIZE(mcmtl_cmd_clear_region, 64);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_clear_region, op, 0);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_clear_region, size, 2);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_clear_region, level, 4);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_clear_region, colorTexture, 8);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_clear_region, depthTexture, 16);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_clear_region, x, 24);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_clear_region, y, 28);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_clear_region, width, 32);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_clear_region, height, 36);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_clear_region, color, 40);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_clear_region, depth, 56);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_clear_region, reserved, 60);

/* COPY_BUFFER: copyFromBuffer:sourceOffset:toBuffer:destinationOffset:size:. */
typedef struct mcmtl_cmd_copy_buffer {
    uint16_t op;              /*  0 */
    uint16_t size;            /*  2 48 */
    uint32_t reserved;        /*  4 0 */
    mcmtl_handle srcBuffer;   /*  8 */
    uint64_t srcOffset;       /* 16 */
    mcmtl_handle dstBuffer;   /* 24 */
    uint64_t dstOffset;       /* 32 */
    uint64_t length;          /* 40 bytes                                                              */
} mcmtl_cmd_copy_buffer;      /* 48 */
MCMTL_ASSERT_SIZE(mcmtl_cmd_copy_buffer, 48);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_copy_buffer, op, 0);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_copy_buffer, size, 2);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_copy_buffer, reserved, 4);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_copy_buffer, srcBuffer, 8);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_copy_buffer, srcOffset, 16);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_copy_buffer, dstBuffer, 24);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_copy_buffer, dstOffset, 32);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_copy_buffer, length, 40);

/* COPY_BUFFER_TO_TEXTURE and COPY_TEXTURE_TO_BUFFER share this layout. The texture region is
 * (x, y, width, height) of (level, slice); the buffer side starts at bufferOffset with the given pitches.
 * Java guarantees bufferOffset and bytesPerRow are multiples of the texel size (spec 2.3). */
typedef struct mcmtl_cmd_copy_buffer_texture {
    uint16_t op;              /*  0 */
    uint16_t size;            /*  2 64 */
    uint32_t level;           /*  4 texture mip level                                                  */
    mcmtl_handle buffer;      /*  8 */
    uint64_t bufferOffset;    /* 16 */
    mcmtl_handle texture;     /* 24 */
    uint32_t bytesPerRow;     /* 32 */
    uint32_t bytesPerImage;   /* 36 */
    uint32_t slice;           /* 40 cube face for uploads, else 0                                      */
    uint32_t x;               /* 44 */
    uint32_t y;               /* 48 */
    uint32_t width;           /* 52 */
    uint32_t height;          /* 56 */
    uint32_t blitOptions;     /* 60 MTLBlitOption (DepthFromDepthStencil 1 for combined depth/stencil) */
} mcmtl_cmd_copy_buffer_texture; /* 64 */
MCMTL_ASSERT_SIZE(mcmtl_cmd_copy_buffer_texture, 64);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_copy_buffer_texture, op, 0);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_copy_buffer_texture, size, 2);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_copy_buffer_texture, level, 4);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_copy_buffer_texture, buffer, 8);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_copy_buffer_texture, bufferOffset, 16);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_copy_buffer_texture, texture, 24);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_copy_buffer_texture, bytesPerRow, 32);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_copy_buffer_texture, bytesPerImage, 36);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_copy_buffer_texture, slice, 40);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_copy_buffer_texture, x, 44);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_copy_buffer_texture, y, 48);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_copy_buffer_texture, width, 52);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_copy_buffer_texture, height, 56);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_copy_buffer_texture, blitOptions, 60);

/* COPY_TEXTURE: copyFromTexture:...toTexture:... (or a nearest-sampling draw with MCMTL_COPY_FLAG_DRAW). */
typedef struct mcmtl_cmd_copy_texture {
    uint16_t op;              /*  0 */
    uint16_t size;            /*  2 64 */
    uint32_t flags;           /*  4 MCMTL_COPY_FLAG_*                                                  */
    mcmtl_handle srcTexture;  /*  8 */
    mcmtl_handle dstTexture;  /* 16 */
    uint32_t srcLevel;        /* 24 */
    uint32_t dstLevel;        /* 28 */
    uint32_t srcSlice;        /* 32 */
    uint32_t dstSlice;        /* 36 */
    uint32_t srcX;            /* 40 */
    uint32_t srcY;            /* 44 */
    uint32_t dstX;            /* 48 */
    uint32_t dstY;            /* 52 */
    uint32_t width;           /* 56 */
    uint32_t height;          /* 60 */
} mcmtl_cmd_copy_texture;     /* 64 */
MCMTL_ASSERT_SIZE(mcmtl_cmd_copy_texture, 64);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_copy_texture, op, 0);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_copy_texture, size, 2);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_copy_texture, flags, 4);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_copy_texture, srcTexture, 8);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_copy_texture, dstTexture, 16);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_copy_texture, srcLevel, 24);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_copy_texture, dstLevel, 28);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_copy_texture, srcSlice, 32);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_copy_texture, dstSlice, 36);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_copy_texture, srcX, 40);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_copy_texture, srcY, 44);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_copy_texture, dstX, 48);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_copy_texture, dstY, 52);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_copy_texture, width, 56);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_copy_texture, height, 60);

/* PRESENT: acquire the surface's next drawable during decode, render the flip/opaque present pass from
 * (texture, level), register presentDrawable on the command buffer (spec 6.6). */
typedef struct mcmtl_cmd_present {
    uint16_t op;              /*  0 */
    uint16_t size;            /*  2 32 */
    uint32_t flags;           /*  4 MCMTL_PRESENT_FLAG_* (0)                                           */
    mcmtl_handle surface;     /*  8 MCMTLSurface handle                                                */
    mcmtl_handle texture;     /* 16 parent texture of the presented view                               */
    uint32_t level;           /* 24 view.baseMipLevel()                                                */
    uint32_t reserved;        /* 28 0 */
} mcmtl_cmd_present;          /* 32 */
MCMTL_ASSERT_SIZE(mcmtl_cmd_present, 32);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_present, op, 0);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_present, size, 2);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_present, flags, 4);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_present, surface, 8);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_present, texture, 16);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_present, level, 24);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_present, reserved, 28);

/* INIT_TEXTURE: initialize a freshly created texture (recorded into the init stream). */
typedef struct mcmtl_cmd_init_texture {
    uint16_t op;              /*  0 */
    uint16_t size;            /*  2 16 */
    uint32_t mode;            /*  4 MCMTL_INIT_TEXTURE_ZERO                                            */
    mcmtl_handle texture;     /*  8 */
} mcmtl_cmd_init_texture;     /* 16 */
MCMTL_ASSERT_SIZE(mcmtl_cmd_init_texture, 16);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_init_texture, op, 0);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_init_texture, size, 2);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_init_texture, mode, 4);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_init_texture, texture, 8);

/* UPSCALE (M4, layout frozen now; M1 decoders reject it with MCMTL_E_UNSUPPORTED like a bad record and
 * Java never records it in M1). Scales the rect (srcX, srcY, srcW, srcH) of srcTexture/srcLevel into the
 * rect (dstX, dstY, dstW, dstH) of dstTexture/dstLevel at a point between arbitrary records, e.g. the
 * world render target (0.67-0.75 scale) into the native-resolution main target before the GUI pass
 * (spec 9.2 G1). Rects are in memory rows (no flip). The decoder owns every MetalFX object and
 * intermediate (scaler per (format, sizes), an output texture with the usage MetalFX requires, a copy or
 * draw into dstTexture when dstTexture's usage does not suffice); Java textures keep the usage
 * MetalFormats.textureUsage gives them. Encoder-level op: ends the open blit encoder like a pass. */
typedef struct mcmtl_cmd_upscale {
    uint16_t op;              /*  0 MCMTL_OP_UPSCALE                                                   */
    uint16_t size;            /*  2 64 */
    uint32_t mode;            /*  4 MCMTL_UPSCALE_MODE_*                                               */
    mcmtl_handle srcTexture;  /*  8 parent texture handle                                              */
    uint32_t srcLevel;        /* 16 */
    uint32_t dstLevel;        /* 20 */
    mcmtl_handle dstTexture;  /* 24 parent texture handle (RENDER_ATTACHMENT)                          */
    int32_t  srcX;            /* 32 */
    int32_t  srcY;            /* 36 */
    int32_t  srcW;            /* 40 */
    int32_t  srcH;            /* 44 */
    int32_t  dstX;            /* 48 */
    int32_t  dstY;            /* 52 */
    int32_t  dstW;            /* 56 */
    int32_t  dstH;            /* 60 */
} mcmtl_cmd_upscale;          /* 64 */
MCMTL_ASSERT_SIZE(mcmtl_cmd_upscale, 64);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_upscale, op, 0);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_upscale, size, 2);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_upscale, mode, 4);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_upscale, srcTexture, 8);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_upscale, srcLevel, 16);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_upscale, dstLevel, 20);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_upscale, dstTexture, 24);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_upscale, srcX, 32);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_upscale, srcY, 36);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_upscale, srcW, 40);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_upscale, srcH, 44);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_upscale, dstX, 48);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_upscale, dstY, 52);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_upscale, dstW, 56);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_upscale, dstH, 60);

/* TIMESTAMP: write sample `index` of the query pool at this point of the stream. */
typedef struct mcmtl_cmd_timestamp {
    uint16_t op;              /*  0 */
    uint16_t size;            /*  2 16 */
    uint32_t index;           /*  4 < pool count (Java bounds-checks)                                  */
    mcmtl_handle queryPool;   /*  8 MCMTLQueryPool handle                                              */
} mcmtl_cmd_timestamp;        /* 16 */
MCMTL_ASSERT_SIZE(mcmtl_cmd_timestamp, 16);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_timestamp, op, 0);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_timestamp, size, 2);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_timestamp, index, 4);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_timestamp, queryPool, 8);

/* PUSH_DEBUG_GROUP: labelLength UTF-8 bytes (no NUL) follow at offset 8; size = align16(8 + labelLength).
 * Outside a pass the group is pushed on the command buffer (the decoder first ends an open blit encoder:
 * Metal forbids command-buffer debug groups while an encoder is active), inside on the render encoder. */
typedef struct mcmtl_cmd_push_debug_group {
    uint16_t op;              /*  0 */
    uint16_t size;            /*  2 */
    uint32_t labelLength;     /*  4 bytes, <= MCMTL_MAX_LABEL_BYTES                                    */
} mcmtl_cmd_push_debug_group; /*  8 (+ label) */
MCMTL_ASSERT_SIZE(mcmtl_cmd_push_debug_group, 8);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_push_debug_group, op, 0);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_push_debug_group, size, 2);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_push_debug_group, labelLength, 4);

/* SET_PIPELINE. */
typedef struct mcmtl_cmd_set_pipeline {
    uint16_t op;              /*  0 */
    uint16_t size;            /*  2 16 */
    uint32_t reserved;        /*  4 0 */
    mcmtl_handle pipeline;    /*  8 MCMTLPipeline handle                                               */
} mcmtl_cmd_set_pipeline;     /* 16 */
MCMTL_ASSERT_SIZE(mcmtl_cmd_set_pipeline, 16);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_set_pipeline, op, 0);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_set_pipeline, size, 2);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_set_pipeline, reserved, 4);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_set_pipeline, pipeline, 8);

/* SET_BUFFER: uniform buffer at [[buffer(index)]] for the given stages. */
typedef struct mcmtl_cmd_set_buffer {
    uint16_t op;              /*  0 */
    uint16_t size;            /*  2 32 */
    uint32_t index;           /*  4 Metal buffer index 0..13                                           */
    uint32_t stages;          /*  8 MCMTL_STAGE_* (never 0)                                            */
    uint32_t reserved;        /* 12 0 */
    mcmtl_handle buffer;      /* 16 */
    uint64_t offset;          /* 24 multiple of 16                                                     */
} mcmtl_cmd_set_buffer;       /* 32 */
MCMTL_ASSERT_SIZE(mcmtl_cmd_set_buffer, 32);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_set_buffer, op, 0);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_set_buffer, size, 2);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_set_buffer, index, 4);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_set_buffer, stages, 8);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_set_buffer, reserved, 12);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_set_buffer, buffer, 16);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_set_buffer, offset, 24);

/* SET_TEXTURE: texture at [[texture(index)]] and, when sampler != 0, sampler at [[sampler(index)]]. */
typedef struct mcmtl_cmd_set_texture {
    uint16_t op;              /*  0 */
    uint16_t size;            /*  2 32 */
    uint32_t index;           /*  4 Metal texture/sampler index 0..15                                  */
    uint32_t stages;          /*  8 MCMTL_STAGE_* (never 0)                                            */
    uint32_t reserved;        /* 12 0 */
    mcmtl_handle texture;     /* 16 sampling texture: view handle, parent handle (full range) or texel view */
    mcmtl_handle sampler;     /* 24 sampler handle, 0 for texel buffers                                */
} mcmtl_cmd_set_texture;      /* 32 */
MCMTL_ASSERT_SIZE(mcmtl_cmd_set_texture, 32);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_set_texture, op, 0);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_set_texture, size, 2);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_set_texture, index, 4);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_set_texture, stages, 8);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_set_texture, reserved, 12);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_set_texture, texture, 16);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_set_texture, sampler, 24);

/* CLEAR_BINDING: the binding at `index` is unset (dummy before the next draw if the pipeline uses it). */
typedef struct mcmtl_cmd_clear_binding {
    uint16_t op;              /*  0 */
    uint16_t size;            /*  2 16 */
    uint32_t index;           /*  4 */
    uint32_t stages;          /*  8 */
    uint32_t kind;            /* 12 MCMTL_BINDING_KIND_*                                               */
} mcmtl_cmd_clear_binding;    /* 16 */
MCMTL_ASSERT_SIZE(mcmtl_cmd_clear_binding, 16);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_clear_binding, op, 0);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_clear_binding, size, 2);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_clear_binding, index, 4);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_clear_binding, stages, 8);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_clear_binding, kind, 12);

/* SET_VERTEX_BUFFER: RenderPearl slot -> vertex-stage [[buffer(30 - slot)]]. */
typedef struct mcmtl_cmd_set_vertex_buffer {
    uint16_t op;              /*  0 */
    uint16_t size;            /*  2 32 */
    uint32_t slot;            /*  4 0..15                                                              */
    mcmtl_handle buffer;      /*  8 */
    uint64_t offset;          /* 16 */
    uint64_t reserved;        /* 24 0 */
} mcmtl_cmd_set_vertex_buffer;/* 32 */
MCMTL_ASSERT_SIZE(mcmtl_cmd_set_vertex_buffer, 32);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_set_vertex_buffer, op, 0);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_set_vertex_buffer, size, 2);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_set_vertex_buffer, slot, 4);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_set_vertex_buffer, buffer, 8);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_set_vertex_buffer, offset, 16);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_set_vertex_buffer, reserved, 24);

/* SET_INDEX_BUFFER: whole buffer at offset 0 (draws select the start with firstIndex). */
typedef struct mcmtl_cmd_set_index_buffer {
    uint16_t op;              /*  0 */
    uint16_t size;            /*  2 16 */
    uint32_t indexType;       /*  4 MTLIndexType: UInt16 0 / UInt32 1                                  */
    mcmtl_handle buffer;      /*  8 */
} mcmtl_cmd_set_index_buffer; /* 16 */
MCMTL_ASSERT_SIZE(mcmtl_cmd_set_index_buffer, 16);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_set_index_buffer, op, 0);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_set_index_buffer, size, 2);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_set_index_buffer, indexType, 4);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_set_index_buffer, buffer, 8);

/* SET_SCISSOR: setScissorRect unchanged (memory rows; Java validated it lies inside the render area). */
typedef struct mcmtl_cmd_set_scissor {
    uint16_t op;              /*  0 */
    uint16_t size;            /*  2 32 */
    uint32_t reserved0;       /*  4 0 */
    int32_t  x;               /*  8 */
    int32_t  y;               /* 12 */
    int32_t  width;           /* 16 */
    int32_t  height;          /* 20 */
    uint64_t reserved1;       /* 24 0 */
} mcmtl_cmd_set_scissor;      /* 32 */
MCMTL_ASSERT_SIZE(mcmtl_cmd_set_scissor, 32);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_set_scissor, op, 0);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_set_scissor, size, 2);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_set_scissor, reserved0, 4);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_set_scissor, x, 8);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_set_scissor, y, 12);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_set_scissor, width, 16);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_set_scissor, height, 20);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_set_scissor, reserved1, 24);

/* PUSH_CONSTANTS: byteLength bytes follow at offset 16; size = 16 + align16(byteLength). Bound at
 * [[buffer(14)]] for `stages` (classic: set{Vertex,Fragment}Bytes). */
typedef struct mcmtl_cmd_push_constants {
    uint16_t op;              /*  0 */
    uint16_t size;            /*  2 */
    uint32_t byteLength;      /*  4 <= MCMTL_MAX_PUSH_CONSTANT_BYTES                                   */
    uint32_t stages;          /*  8 the pipeline's pushConstantStages                                  */
    uint32_t reserved;        /* 12 0 */
} mcmtl_cmd_push_constants;   /* 16 (+ data) */
MCMTL_ASSERT_SIZE(mcmtl_cmd_push_constants, 16);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_push_constants, op, 0);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_push_constants, size, 2);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_push_constants, byteLength, 4);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_push_constants, stages, 8);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_push_constants, reserved, 12);

/* DRAW: drawPrimitives (fan pipelines: emulated, spec 4.3). */
typedef struct mcmtl_cmd_draw {
    uint16_t op;              /*  0 */
    uint16_t size;            /*  2 32 */
    uint32_t vertexCount;     /*  4 */
    uint32_t instanceCount;   /*  8 */
    uint32_t firstVertex;     /* 12 */
    uint32_t firstInstance;   /* 16 */
    uint32_t reserved0;       /* 20 0 */
    uint64_t reserved1;       /* 24 0 */
} mcmtl_cmd_draw;             /* 32 */
MCMTL_ASSERT_SIZE(mcmtl_cmd_draw, 32);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_draw, op, 0);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_draw, size, 2);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_draw, vertexCount, 4);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_draw, instanceCount, 8);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_draw, firstVertex, 12);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_draw, firstInstance, 16);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_draw, reserved0, 20);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_draw, reserved1, 24);

/* DRAW_INDEXED: drawIndexedPrimitives with indexBufferOffset = firstIndex * indexSize. */
typedef struct mcmtl_cmd_draw_indexed {
    uint16_t op;              /*  0 */
    uint16_t size;            /*  2 32 */
    uint32_t indexCount;      /*  4 */
    uint32_t instanceCount;   /*  8 */
    uint32_t firstIndex;      /* 12 in indices                                                         */
    int32_t  baseVertex;      /* 16 signed                                                             */
    uint32_t firstInstance;   /* 20 */
    uint64_t reserved;        /* 24 0 */
} mcmtl_cmd_draw_indexed;     /* 32 */
MCMTL_ASSERT_SIZE(mcmtl_cmd_draw_indexed, 32);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_draw_indexed, op, 0);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_draw_indexed, size, 2);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_draw_indexed, indexCount, 4);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_draw_indexed, instanceCount, 8);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_draw_indexed, firstIndex, 12);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_draw_indexed, baseVertex, 16);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_draw_indexed, firstInstance, 20);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_draw_indexed, reserved, 24);

/* DRAW_INDIRECT / DRAW_INDEXED_INDIRECT: drawCount tightly packed argument records starting at offset
 * (stride MCMTL_INDIRECT_STRIDE_DRAW / _DRAW_INDEXED). The indexed form uses the bound index buffer. */
typedef struct mcmtl_cmd_draw_indirect {
    uint16_t op;              /*  0 */
    uint16_t size;            /*  2 32 */
    uint32_t drawCount;       /*  4 */
    mcmtl_handle buffer;      /*  8 */
    uint64_t offset;          /* 16 multiple of 4                                                      */
    uint64_t reserved;        /* 24 0 */
} mcmtl_cmd_draw_indirect;    /* 32 */
MCMTL_ASSERT_SIZE(mcmtl_cmd_draw_indirect, 32);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_draw_indirect, op, 0);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_draw_indirect, size, 2);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_draw_indirect, drawCount, 4);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_draw_indirect, buffer, 8);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_draw_indirect, offset, 16);
MCMTL_ASSERT_OFFSET(mcmtl_cmd_draw_indirect, reserved, 24);

#ifdef __cplusplus
}
#endif

#endif /* MCMETAL_STREAM_H */
