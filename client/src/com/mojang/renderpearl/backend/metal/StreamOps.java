package com.mojang.renderpearl.backend.metal;

import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.StructLayout;

/**
 * Command-stream opcodes, record sizes and field offsets (native/include/mcmetal_stream.h).
 *
 * <p><b>FROZEN CONTRACT FILE (owner: architect).</b> Implementers must not edit it; see
 * docs/metal/CONTRACT.md section 4 for the semantics of every record. Mirror rules: {@code MCMTL_X}
 * becomes {@code X} (with {@code MCMTL_CMD_SIZE_X} becoming {@code SIZE_X}), {@code mcmtl_cmd_<name>.<field>}
 * becomes {@code <NAME>_<FIELD>} and {@code sizeof(mcmtl_cmd_<name>)} becomes {@code <NAME>_SIZEOF}.
 * {@code tools/MetalSmoke.java} cross-checks all of them against the C header.
 *
 * <p>Writing a record (StreamWriter, I4): reserve {@code size} bytes at a 16-byte aligned position,
 * store the first 8 bytes with one {@code memPutLong(address, header(op, size, arg))}, then the fields at
 * {@code address + <NAME>_<FIELD>} with {@code memPutInt/memPutLong/memPutFloat}. Padding bytes must be 0.
 */
public final class StreamOps {
    private StreamOps() {
    }

    // =============================================================================================
    // Constants (mirror of #defines in mcmetal_stream.h)
    // =============================================================================================

    public static final int STREAM_VERSION = 2;
    public static final int STREAM_ALIGNMENT = 16;
    public static final int STREAM_MAX_RECORD = 65520;
    public static final int OP_NOP = 0;
    public static final int OP_BEGIN_PASS = 1;
    public static final int OP_END_PASS = 2;
    public static final int OP_CLEAR_TEXTURE = 3;
    public static final int OP_CLEAR_REGION = 4;
    public static final int OP_COPY_BUFFER = 5;
    public static final int OP_COPY_BUFFER_TO_TEXTURE = 6;
    public static final int OP_COPY_TEXTURE_TO_BUFFER = 7;
    public static final int OP_COPY_TEXTURE = 8;
    public static final int OP_PRESENT = 9;
    public static final int OP_INIT_TEXTURE = 10;
    public static final int OP_UPSCALE = 11;
    public static final int OP_TIMESTAMP = 16;
    public static final int OP_PUSH_DEBUG_GROUP = 17;
    public static final int OP_POP_DEBUG_GROUP = 18;
    public static final int OP_SET_PIPELINE = 32;
    public static final int OP_SET_BUFFER = 33;
    public static final int OP_SET_TEXTURE = 34;
    public static final int OP_CLEAR_BINDING = 35;
    public static final int OP_SET_VERTEX_BUFFER = 36;
    public static final int OP_SET_INDEX_BUFFER = 37;
    public static final int OP_SET_SCISSOR = 38;
    public static final int OP_RESET_SCISSOR = 39;
    public static final int OP_PUSH_CONSTANTS = 40;
    public static final int OP_DRAW = 48;
    public static final int OP_DRAW_INDEXED = 49;
    public static final int OP_DRAW_INDIRECT = 50;
    public static final int OP_DRAW_INDEXED_INDIRECT = 51;
    public static final int OP_LIMIT = 64;
    public static final int STRUCT_CMD_HEADER = 64;
    public static final int STRUCT_CMD_EMPTY = 65;
    public static final int STRUCT_CMD_BEGIN_PASS = 66;
    public static final int STRUCT_CMD_COLOR_ATTACHMENT = 67;
    public static final int STRUCT_CMD_END_PASS = 68;
    public static final int STRUCT_CMD_CLEAR_TEXTURE = 69;
    public static final int STRUCT_CMD_CLEAR_REGION = 70;
    public static final int STRUCT_CMD_COPY_BUFFER = 71;
    public static final int STRUCT_CMD_COPY_BUFFER_TEXTURE = 72;
    public static final int STRUCT_CMD_COPY_TEXTURE = 73;
    public static final int STRUCT_CMD_PRESENT = 74;
    public static final int STRUCT_CMD_INIT_TEXTURE = 75;
    public static final int STRUCT_CMD_TIMESTAMP = 76;
    public static final int STRUCT_CMD_PUSH_DEBUG_GROUP = 77;
    public static final int STRUCT_CMD_SET_PIPELINE = 78;
    public static final int STRUCT_CMD_SET_BUFFER = 79;
    public static final int STRUCT_CMD_SET_TEXTURE = 80;
    public static final int STRUCT_CMD_CLEAR_BINDING = 81;
    public static final int STRUCT_CMD_SET_VERTEX_BUFFER = 82;
    public static final int STRUCT_CMD_SET_INDEX_BUFFER = 83;
    public static final int STRUCT_CMD_SET_SCISSOR = 84;
    public static final int STRUCT_CMD_PUSH_CONSTANTS = 85;
    public static final int STRUCT_CMD_DRAW = 86;
    public static final int STRUCT_CMD_DRAW_INDEXED = 87;
    public static final int STRUCT_CMD_DRAW_INDIRECT = 88;
    public static final int STRUCT_CMD_UPSCALE = 89;
    public static final int SIZE_EMPTY = 16;
    public static final int SIZE_BEGIN_PASS_BASE = 48;
    public static final int SIZE_COLOR_ATTACHMENT = 32;
    public static final int SIZE_END_PASS = 16;
    public static final int SIZE_CLEAR_TEXTURE = 48;
    public static final int SIZE_CLEAR_REGION = 64;
    public static final int SIZE_COPY_BUFFER = 48;
    public static final int SIZE_COPY_BUFFER_TEXTURE = 64;
    public static final int SIZE_COPY_TEXTURE = 64;
    public static final int SIZE_PRESENT = 32;
    public static final int SIZE_INIT_TEXTURE = 16;
    public static final int SIZE_TIMESTAMP = 16;
    public static final int SIZE_PUSH_DEBUG_GROUP_MIN = 16;
    public static final int SIZE_SET_PIPELINE = 16;
    public static final int SIZE_SET_BUFFER = 32;
    public static final int SIZE_SET_TEXTURE = 32;
    public static final int SIZE_CLEAR_BINDING = 16;
    public static final int SIZE_SET_VERTEX_BUFFER = 32;
    public static final int SIZE_SET_INDEX_BUFFER = 16;
    public static final int SIZE_SET_SCISSOR = 32;
    public static final int SIZE_PUSH_CONSTANTS_MIN = 16;
    public static final int SIZE_DRAW = 32;
    public static final int SIZE_DRAW_INDEXED = 32;
    public static final int SIZE_DRAW_INDIRECT = 32;
    public static final int SIZE_UPSCALE = 64;
    public static final int PASS_FLAG_DEPTH_CLEAR = 1;
    public static final int ASPECT_COLOR = 1;
    public static final int ASPECT_DEPTH = 2;
    public static final int ASPECT_STENCIL = 4;
    public static final int COPY_FLAG_DRAW = 1;
    public static final int INIT_TEXTURE_ZERO = 0;
    public static final int BINDING_KIND_BUFFER = 0;
    public static final int BINDING_KIND_TEXTURE = 1;
    public static final int PRESENT_FLAG_NONE = 0;
    public static final int INDIRECT_STRIDE_DRAW = 16;
    public static final int INDIRECT_STRIDE_DRAW_INDEXED = 20;
    public static final int UPSCALE_MODE_LINEAR = 0;
    public static final int UPSCALE_MODE_NEAREST = 1;
    public static final int UPSCALE_MODE_METALFX_SPATIAL = 2;

    // =============================================================================================
    // Record sizes and field offsets (mirror of MCMTL_ASSERT_SIZE / MCMTL_ASSERT_OFFSET)
    // =============================================================================================

    // mcmtl_cmd_header (8 bytes)
    public static final long HEADER_SIZEOF = 8;
    public static final long HEADER_OP = 0;
    public static final long HEADER_SIZE = 2;
    public static final long HEADER_ARG = 4;

    // mcmtl_cmd_empty (16 bytes)
    public static final long EMPTY_SIZEOF = 16;
    public static final long EMPTY_OP = 0;
    public static final long EMPTY_SIZE = 2;
    public static final long EMPTY_RESERVED0 = 4;
    public static final long EMPTY_RESERVED1 = 8;

    // mcmtl_cmd_color_attachment (32 bytes)
    public static final long COLOR_ATTACHMENT_SIZEOF = 32;
    public static final long COLOR_ATTACHMENT_TEXTURE = 0;
    public static final long COLOR_ATTACHMENT_LEVEL = 8;
    public static final long COLOR_ATTACHMENT_RESERVED = 12;
    public static final long COLOR_ATTACHMENT_CLEAR_COLOR = 16;

    // mcmtl_cmd_begin_pass (48 bytes)
    public static final long BEGIN_PASS_SIZEOF = 48;
    public static final long BEGIN_PASS_OP = 0;
    public static final long BEGIN_PASS_SIZE = 2;
    public static final long BEGIN_PASS_COLOR_COUNT = 4;
    public static final long BEGIN_PASS_FLAGS = 8;
    public static final long BEGIN_PASS_COLOR_CLEAR_MASK = 12;
    public static final long BEGIN_PASS_RENDER_X = 16;
    public static final long BEGIN_PASS_RENDER_Y = 20;
    public static final long BEGIN_PASS_RENDER_WIDTH = 24;
    public static final long BEGIN_PASS_RENDER_HEIGHT = 28;
    public static final long BEGIN_PASS_DEPTH_CLEAR_VALUE = 32;
    public static final long BEGIN_PASS_DEPTH_LEVEL = 36;
    public static final long BEGIN_PASS_DEPTH_TEXTURE = 40;

    // mcmtl_cmd_end_pass (16 bytes)
    public static final long END_PASS_SIZEOF = 16;
    public static final long END_PASS_OP = 0;
    public static final long END_PASS_SIZE = 2;
    public static final long END_PASS_FLAGS = 4;
    public static final long END_PASS_RESERVED = 8;

    // mcmtl_cmd_clear_texture (48 bytes)
    public static final long CLEAR_TEXTURE_SIZEOF = 48;
    public static final long CLEAR_TEXTURE_OP = 0;
    public static final long CLEAR_TEXTURE_SIZE = 2;
    public static final long CLEAR_TEXTURE_ASPECTS = 4;
    public static final long CLEAR_TEXTURE_TEXTURE = 8;
    public static final long CLEAR_TEXTURE_BASE_LEVEL = 16;
    public static final long CLEAR_TEXTURE_LEVEL_COUNT = 20;
    public static final long CLEAR_TEXTURE_SLICE = 24;
    public static final long CLEAR_TEXTURE_DEPTH = 28;
    public static final long CLEAR_TEXTURE_COLOR = 32;

    // mcmtl_cmd_clear_region (64 bytes)
    public static final long CLEAR_REGION_SIZEOF = 64;
    public static final long CLEAR_REGION_OP = 0;
    public static final long CLEAR_REGION_SIZE = 2;
    public static final long CLEAR_REGION_LEVEL = 4;
    public static final long CLEAR_REGION_COLOR_TEXTURE = 8;
    public static final long CLEAR_REGION_DEPTH_TEXTURE = 16;
    public static final long CLEAR_REGION_X = 24;
    public static final long CLEAR_REGION_Y = 28;
    public static final long CLEAR_REGION_WIDTH = 32;
    public static final long CLEAR_REGION_HEIGHT = 36;
    public static final long CLEAR_REGION_COLOR = 40;
    public static final long CLEAR_REGION_DEPTH = 56;
    public static final long CLEAR_REGION_RESERVED = 60;

    // mcmtl_cmd_copy_buffer (48 bytes)
    public static final long COPY_BUFFER_SIZEOF = 48;
    public static final long COPY_BUFFER_OP = 0;
    public static final long COPY_BUFFER_SIZE = 2;
    public static final long COPY_BUFFER_RESERVED = 4;
    public static final long COPY_BUFFER_SRC_BUFFER = 8;
    public static final long COPY_BUFFER_SRC_OFFSET = 16;
    public static final long COPY_BUFFER_DST_BUFFER = 24;
    public static final long COPY_BUFFER_DST_OFFSET = 32;
    public static final long COPY_BUFFER_LENGTH = 40;

    // mcmtl_cmd_copy_buffer_texture (64 bytes)
    public static final long COPY_BUFFER_TEXTURE_SIZEOF = 64;
    public static final long COPY_BUFFER_TEXTURE_OP = 0;
    public static final long COPY_BUFFER_TEXTURE_SIZE = 2;
    public static final long COPY_BUFFER_TEXTURE_LEVEL = 4;
    public static final long COPY_BUFFER_TEXTURE_BUFFER = 8;
    public static final long COPY_BUFFER_TEXTURE_BUFFER_OFFSET = 16;
    public static final long COPY_BUFFER_TEXTURE_TEXTURE = 24;
    public static final long COPY_BUFFER_TEXTURE_BYTES_PER_ROW = 32;
    public static final long COPY_BUFFER_TEXTURE_BYTES_PER_IMAGE = 36;
    public static final long COPY_BUFFER_TEXTURE_SLICE = 40;
    public static final long COPY_BUFFER_TEXTURE_X = 44;
    public static final long COPY_BUFFER_TEXTURE_Y = 48;
    public static final long COPY_BUFFER_TEXTURE_WIDTH = 52;
    public static final long COPY_BUFFER_TEXTURE_HEIGHT = 56;
    public static final long COPY_BUFFER_TEXTURE_BLIT_OPTIONS = 60;

    // mcmtl_cmd_copy_texture (64 bytes)
    public static final long COPY_TEXTURE_SIZEOF = 64;
    public static final long COPY_TEXTURE_OP = 0;
    public static final long COPY_TEXTURE_SIZE = 2;
    public static final long COPY_TEXTURE_FLAGS = 4;
    public static final long COPY_TEXTURE_SRC_TEXTURE = 8;
    public static final long COPY_TEXTURE_DST_TEXTURE = 16;
    public static final long COPY_TEXTURE_SRC_LEVEL = 24;
    public static final long COPY_TEXTURE_DST_LEVEL = 28;
    public static final long COPY_TEXTURE_SRC_SLICE = 32;
    public static final long COPY_TEXTURE_DST_SLICE = 36;
    public static final long COPY_TEXTURE_SRC_X = 40;
    public static final long COPY_TEXTURE_SRC_Y = 44;
    public static final long COPY_TEXTURE_DST_X = 48;
    public static final long COPY_TEXTURE_DST_Y = 52;
    public static final long COPY_TEXTURE_WIDTH = 56;
    public static final long COPY_TEXTURE_HEIGHT = 60;

    // mcmtl_cmd_present (32 bytes)
    public static final long PRESENT_SIZEOF = 32;
    public static final long PRESENT_OP = 0;
    public static final long PRESENT_SIZE = 2;
    public static final long PRESENT_FLAGS = 4;
    public static final long PRESENT_SURFACE = 8;
    public static final long PRESENT_TEXTURE = 16;
    public static final long PRESENT_LEVEL = 24;
    public static final long PRESENT_RESERVED = 28;

    // mcmtl_cmd_init_texture (16 bytes)
    public static final long INIT_TEXTURE_SIZEOF = 16;
    public static final long INIT_TEXTURE_OP = 0;
    public static final long INIT_TEXTURE_SIZE = 2;
    public static final long INIT_TEXTURE_MODE = 4;
    public static final long INIT_TEXTURE_TEXTURE = 8;

    // mcmtl_cmd_upscale (64 bytes) -- M4, reserved: Java records it only once the decoder supports it
    public static final long UPSCALE_SIZEOF = 64;
    public static final long UPSCALE_OP = 0;
    public static final long UPSCALE_SIZE = 2;
    public static final long UPSCALE_MODE = 4;
    public static final long UPSCALE_SRC_TEXTURE = 8;
    public static final long UPSCALE_SRC_LEVEL = 16;
    public static final long UPSCALE_DST_LEVEL = 20;
    public static final long UPSCALE_DST_TEXTURE = 24;
    public static final long UPSCALE_SRC_X = 32;
    public static final long UPSCALE_SRC_Y = 36;
    public static final long UPSCALE_SRC_W = 40;
    public static final long UPSCALE_SRC_H = 44;
    public static final long UPSCALE_DST_X = 48;
    public static final long UPSCALE_DST_Y = 52;
    public static final long UPSCALE_DST_W = 56;
    public static final long UPSCALE_DST_H = 60;

    // mcmtl_cmd_timestamp (16 bytes)
    public static final long TIMESTAMP_SIZEOF = 16;
    public static final long TIMESTAMP_OP = 0;
    public static final long TIMESTAMP_SIZE = 2;
    public static final long TIMESTAMP_INDEX = 4;
    public static final long TIMESTAMP_QUERY_POOL = 8;

    // mcmtl_cmd_push_debug_group (8 bytes)
    public static final long PUSH_DEBUG_GROUP_SIZEOF = 8;
    public static final long PUSH_DEBUG_GROUP_OP = 0;
    public static final long PUSH_DEBUG_GROUP_SIZE = 2;
    public static final long PUSH_DEBUG_GROUP_LABEL_LENGTH = 4;

    // mcmtl_cmd_set_pipeline (16 bytes)
    public static final long SET_PIPELINE_SIZEOF = 16;
    public static final long SET_PIPELINE_OP = 0;
    public static final long SET_PIPELINE_SIZE = 2;
    public static final long SET_PIPELINE_RESERVED = 4;
    public static final long SET_PIPELINE_PIPELINE = 8;

    // mcmtl_cmd_set_buffer (32 bytes)
    public static final long SET_BUFFER_SIZEOF = 32;
    public static final long SET_BUFFER_OP = 0;
    public static final long SET_BUFFER_SIZE = 2;
    public static final long SET_BUFFER_INDEX = 4;
    public static final long SET_BUFFER_STAGES = 8;
    public static final long SET_BUFFER_RESERVED = 12;
    public static final long SET_BUFFER_BUFFER = 16;
    public static final long SET_BUFFER_OFFSET = 24;

    // mcmtl_cmd_set_texture (32 bytes)
    public static final long SET_TEXTURE_SIZEOF = 32;
    public static final long SET_TEXTURE_OP = 0;
    public static final long SET_TEXTURE_SIZE = 2;
    public static final long SET_TEXTURE_INDEX = 4;
    public static final long SET_TEXTURE_STAGES = 8;
    public static final long SET_TEXTURE_RESERVED = 12;
    public static final long SET_TEXTURE_TEXTURE = 16;
    public static final long SET_TEXTURE_SAMPLER = 24;

    // mcmtl_cmd_clear_binding (16 bytes)
    public static final long CLEAR_BINDING_SIZEOF = 16;
    public static final long CLEAR_BINDING_OP = 0;
    public static final long CLEAR_BINDING_SIZE = 2;
    public static final long CLEAR_BINDING_INDEX = 4;
    public static final long CLEAR_BINDING_STAGES = 8;
    public static final long CLEAR_BINDING_KIND = 12;

    // mcmtl_cmd_set_vertex_buffer (32 bytes)
    public static final long SET_VERTEX_BUFFER_SIZEOF = 32;
    public static final long SET_VERTEX_BUFFER_OP = 0;
    public static final long SET_VERTEX_BUFFER_SIZE = 2;
    public static final long SET_VERTEX_BUFFER_SLOT = 4;
    public static final long SET_VERTEX_BUFFER_BUFFER = 8;
    public static final long SET_VERTEX_BUFFER_OFFSET = 16;
    public static final long SET_VERTEX_BUFFER_RESERVED = 24;

    // mcmtl_cmd_set_index_buffer (16 bytes)
    public static final long SET_INDEX_BUFFER_SIZEOF = 16;
    public static final long SET_INDEX_BUFFER_OP = 0;
    public static final long SET_INDEX_BUFFER_SIZE = 2;
    public static final long SET_INDEX_BUFFER_INDEX_TYPE = 4;
    public static final long SET_INDEX_BUFFER_BUFFER = 8;

    // mcmtl_cmd_set_scissor (32 bytes)
    public static final long SET_SCISSOR_SIZEOF = 32;
    public static final long SET_SCISSOR_OP = 0;
    public static final long SET_SCISSOR_SIZE = 2;
    public static final long SET_SCISSOR_RESERVED0 = 4;
    public static final long SET_SCISSOR_X = 8;
    public static final long SET_SCISSOR_Y = 12;
    public static final long SET_SCISSOR_WIDTH = 16;
    public static final long SET_SCISSOR_HEIGHT = 20;
    public static final long SET_SCISSOR_RESERVED1 = 24;

    // mcmtl_cmd_push_constants (16 bytes)
    public static final long PUSH_CONSTANTS_SIZEOF = 16;
    public static final long PUSH_CONSTANTS_OP = 0;
    public static final long PUSH_CONSTANTS_SIZE = 2;
    public static final long PUSH_CONSTANTS_BYTE_LENGTH = 4;
    public static final long PUSH_CONSTANTS_STAGES = 8;
    public static final long PUSH_CONSTANTS_RESERVED = 12;

    // mcmtl_cmd_draw (32 bytes)
    public static final long DRAW_SIZEOF = 32;
    public static final long DRAW_OP = 0;
    public static final long DRAW_SIZE = 2;
    public static final long DRAW_VERTEX_COUNT = 4;
    public static final long DRAW_INSTANCE_COUNT = 8;
    public static final long DRAW_FIRST_VERTEX = 12;
    public static final long DRAW_FIRST_INSTANCE = 16;
    public static final long DRAW_RESERVED0 = 20;
    public static final long DRAW_RESERVED1 = 24;

    // mcmtl_cmd_draw_indexed (32 bytes)
    public static final long DRAW_INDEXED_SIZEOF = 32;
    public static final long DRAW_INDEXED_OP = 0;
    public static final long DRAW_INDEXED_SIZE = 2;
    public static final long DRAW_INDEXED_INDEX_COUNT = 4;
    public static final long DRAW_INDEXED_INSTANCE_COUNT = 8;
    public static final long DRAW_INDEXED_FIRST_INDEX = 12;
    public static final long DRAW_INDEXED_BASE_VERTEX = 16;
    public static final long DRAW_INDEXED_FIRST_INSTANCE = 20;
    public static final long DRAW_INDEXED_RESERVED = 24;

    // mcmtl_cmd_draw_indirect (32 bytes)
    public static final long DRAW_INDIRECT_SIZEOF = 32;
    public static final long DRAW_INDIRECT_OP = 0;
    public static final long DRAW_INDIRECT_SIZE = 2;
    public static final long DRAW_INDIRECT_DRAW_COUNT = 4;
    public static final long DRAW_INDIRECT_BUFFER = 8;
    public static final long DRAW_INDIRECT_OFFSET = 16;
    public static final long DRAW_INDIRECT_RESERVED = 24;

    /** {@code StructLayout}s of every record (documentation and verification; hot paths use the offsets). */
    public static final class Layouts {
        private Layouts() {
        }

        /** {@code mcmtl_cmd_header} (8 bytes). */
        public static final StructLayout HEADER = MemoryLayout.structLayout(
            JAVA_SHORT.withName("op"),
            JAVA_SHORT.withName("size"),
            JAVA_INT.withName("arg")).withName("mcmtl_cmd_header");

        /** {@code mcmtl_cmd_empty} (16 bytes). */
        public static final StructLayout EMPTY = MemoryLayout.structLayout(
            JAVA_SHORT.withName("op"),
            JAVA_SHORT.withName("size"),
            JAVA_INT.withName("reserved0"),
            JAVA_LONG.withName("reserved1")).withName("mcmtl_cmd_empty");

        /** {@code mcmtl_cmd_color_attachment} (32 bytes). */
        public static final StructLayout COLOR_ATTACHMENT = MemoryLayout.structLayout(
            JAVA_LONG.withName("texture"),
            JAVA_INT.withName("level"),
            JAVA_INT.withName("reserved"),
            MemoryLayout.sequenceLayout(4, JAVA_FLOAT).withName("clearColor")).withName("mcmtl_cmd_color_attachment");

        /** {@code mcmtl_cmd_begin_pass} (48 bytes). */
        public static final StructLayout BEGIN_PASS = MemoryLayout.structLayout(
            JAVA_SHORT.withName("op"),
            JAVA_SHORT.withName("size"),
            JAVA_INT.withName("colorCount"),
            JAVA_INT.withName("flags"),
            JAVA_INT.withName("colorClearMask"),
            JAVA_INT.withName("renderX"),
            JAVA_INT.withName("renderY"),
            JAVA_INT.withName("renderWidth"),
            JAVA_INT.withName("renderHeight"),
            JAVA_FLOAT.withName("depthClearValue"),
            JAVA_INT.withName("depthLevel"),
            JAVA_LONG.withName("depthTexture")).withName("mcmtl_cmd_begin_pass");

        /** {@code mcmtl_cmd_end_pass} (16 bytes). */
        public static final StructLayout END_PASS = MemoryLayout.structLayout(
            JAVA_SHORT.withName("op"),
            JAVA_SHORT.withName("size"),
            JAVA_INT.withName("flags"),
            JAVA_LONG.withName("reserved")).withName("mcmtl_cmd_end_pass");

        /** {@code mcmtl_cmd_clear_texture} (48 bytes). */
        public static final StructLayout CLEAR_TEXTURE = MemoryLayout.structLayout(
            JAVA_SHORT.withName("op"),
            JAVA_SHORT.withName("size"),
            JAVA_INT.withName("aspects"),
            JAVA_LONG.withName("texture"),
            JAVA_INT.withName("baseLevel"),
            JAVA_INT.withName("levelCount"),
            JAVA_INT.withName("slice"),
            JAVA_FLOAT.withName("depth"),
            MemoryLayout.sequenceLayout(4, JAVA_FLOAT).withName("color")).withName("mcmtl_cmd_clear_texture");

        /** {@code mcmtl_cmd_clear_region} (64 bytes). */
        public static final StructLayout CLEAR_REGION = MemoryLayout.structLayout(
            JAVA_SHORT.withName("op"),
            JAVA_SHORT.withName("size"),
            JAVA_INT.withName("level"),
            JAVA_LONG.withName("colorTexture"),
            JAVA_LONG.withName("depthTexture"),
            JAVA_INT.withName("x"),
            JAVA_INT.withName("y"),
            JAVA_INT.withName("width"),
            JAVA_INT.withName("height"),
            MemoryLayout.sequenceLayout(4, JAVA_FLOAT).withName("color"),
            JAVA_FLOAT.withName("depth"),
            JAVA_INT.withName("reserved")).withName("mcmtl_cmd_clear_region");

        /** {@code mcmtl_cmd_copy_buffer} (48 bytes). */
        public static final StructLayout COPY_BUFFER = MemoryLayout.structLayout(
            JAVA_SHORT.withName("op"),
            JAVA_SHORT.withName("size"),
            JAVA_INT.withName("reserved"),
            JAVA_LONG.withName("srcBuffer"),
            JAVA_LONG.withName("srcOffset"),
            JAVA_LONG.withName("dstBuffer"),
            JAVA_LONG.withName("dstOffset"),
            JAVA_LONG.withName("length")).withName("mcmtl_cmd_copy_buffer");

        /** {@code mcmtl_cmd_copy_buffer_texture} (64 bytes). */
        public static final StructLayout COPY_BUFFER_TEXTURE = MemoryLayout.structLayout(
            JAVA_SHORT.withName("op"),
            JAVA_SHORT.withName("size"),
            JAVA_INT.withName("level"),
            JAVA_LONG.withName("buffer"),
            JAVA_LONG.withName("bufferOffset"),
            JAVA_LONG.withName("texture"),
            JAVA_INT.withName("bytesPerRow"),
            JAVA_INT.withName("bytesPerImage"),
            JAVA_INT.withName("slice"),
            JAVA_INT.withName("x"),
            JAVA_INT.withName("y"),
            JAVA_INT.withName("width"),
            JAVA_INT.withName("height"),
            JAVA_INT.withName("blitOptions")).withName("mcmtl_cmd_copy_buffer_texture");

        /** {@code mcmtl_cmd_copy_texture} (64 bytes). */
        public static final StructLayout COPY_TEXTURE = MemoryLayout.structLayout(
            JAVA_SHORT.withName("op"),
            JAVA_SHORT.withName("size"),
            JAVA_INT.withName("flags"),
            JAVA_LONG.withName("srcTexture"),
            JAVA_LONG.withName("dstTexture"),
            JAVA_INT.withName("srcLevel"),
            JAVA_INT.withName("dstLevel"),
            JAVA_INT.withName("srcSlice"),
            JAVA_INT.withName("dstSlice"),
            JAVA_INT.withName("srcX"),
            JAVA_INT.withName("srcY"),
            JAVA_INT.withName("dstX"),
            JAVA_INT.withName("dstY"),
            JAVA_INT.withName("width"),
            JAVA_INT.withName("height")).withName("mcmtl_cmd_copy_texture");

        /** {@code mcmtl_cmd_present} (32 bytes). */
        public static final StructLayout PRESENT = MemoryLayout.structLayout(
            JAVA_SHORT.withName("op"),
            JAVA_SHORT.withName("size"),
            JAVA_INT.withName("flags"),
            JAVA_LONG.withName("surface"),
            JAVA_LONG.withName("texture"),
            JAVA_INT.withName("level"),
            JAVA_INT.withName("reserved")).withName("mcmtl_cmd_present");

        /** {@code mcmtl_cmd_init_texture} (16 bytes). */
        public static final StructLayout INIT_TEXTURE = MemoryLayout.structLayout(
            JAVA_SHORT.withName("op"),
            JAVA_SHORT.withName("size"),
            JAVA_INT.withName("mode"),
            JAVA_LONG.withName("texture")).withName("mcmtl_cmd_init_texture");

        /** {@code mcmtl_cmd_upscale} (64 bytes). */
        public static final StructLayout UPSCALE = MemoryLayout.structLayout(
            JAVA_SHORT.withName("op"),
            JAVA_SHORT.withName("size"),
            JAVA_INT.withName("mode"),
            JAVA_LONG.withName("srcTexture"),
            JAVA_INT.withName("srcLevel"),
            JAVA_INT.withName("dstLevel"),
            JAVA_LONG.withName("dstTexture"),
            JAVA_INT.withName("srcX"),
            JAVA_INT.withName("srcY"),
            JAVA_INT.withName("srcW"),
            JAVA_INT.withName("srcH"),
            JAVA_INT.withName("dstX"),
            JAVA_INT.withName("dstY"),
            JAVA_INT.withName("dstW"),
            JAVA_INT.withName("dstH")).withName("mcmtl_cmd_upscale");

        /** {@code mcmtl_cmd_timestamp} (16 bytes). */
        public static final StructLayout TIMESTAMP = MemoryLayout.structLayout(
            JAVA_SHORT.withName("op"),
            JAVA_SHORT.withName("size"),
            JAVA_INT.withName("index"),
            JAVA_LONG.withName("queryPool")).withName("mcmtl_cmd_timestamp");

        /** {@code mcmtl_cmd_push_debug_group} (8 bytes). */
        public static final StructLayout PUSH_DEBUG_GROUP = MemoryLayout.structLayout(
            JAVA_SHORT.withName("op"),
            JAVA_SHORT.withName("size"),
            JAVA_INT.withName("labelLength")).withName("mcmtl_cmd_push_debug_group");

        /** {@code mcmtl_cmd_set_pipeline} (16 bytes). */
        public static final StructLayout SET_PIPELINE = MemoryLayout.structLayout(
            JAVA_SHORT.withName("op"),
            JAVA_SHORT.withName("size"),
            JAVA_INT.withName("reserved"),
            JAVA_LONG.withName("pipeline")).withName("mcmtl_cmd_set_pipeline");

        /** {@code mcmtl_cmd_set_buffer} (32 bytes). */
        public static final StructLayout SET_BUFFER = MemoryLayout.structLayout(
            JAVA_SHORT.withName("op"),
            JAVA_SHORT.withName("size"),
            JAVA_INT.withName("index"),
            JAVA_INT.withName("stages"),
            JAVA_INT.withName("reserved"),
            JAVA_LONG.withName("buffer"),
            JAVA_LONG.withName("offset")).withName("mcmtl_cmd_set_buffer");

        /** {@code mcmtl_cmd_set_texture} (32 bytes). */
        public static final StructLayout SET_TEXTURE = MemoryLayout.structLayout(
            JAVA_SHORT.withName("op"),
            JAVA_SHORT.withName("size"),
            JAVA_INT.withName("index"),
            JAVA_INT.withName("stages"),
            JAVA_INT.withName("reserved"),
            JAVA_LONG.withName("texture"),
            JAVA_LONG.withName("sampler")).withName("mcmtl_cmd_set_texture");

        /** {@code mcmtl_cmd_clear_binding} (16 bytes). */
        public static final StructLayout CLEAR_BINDING = MemoryLayout.structLayout(
            JAVA_SHORT.withName("op"),
            JAVA_SHORT.withName("size"),
            JAVA_INT.withName("index"),
            JAVA_INT.withName("stages"),
            JAVA_INT.withName("kind")).withName("mcmtl_cmd_clear_binding");

        /** {@code mcmtl_cmd_set_vertex_buffer} (32 bytes). */
        public static final StructLayout SET_VERTEX_BUFFER = MemoryLayout.structLayout(
            JAVA_SHORT.withName("op"),
            JAVA_SHORT.withName("size"),
            JAVA_INT.withName("slot"),
            JAVA_LONG.withName("buffer"),
            JAVA_LONG.withName("offset"),
            JAVA_LONG.withName("reserved")).withName("mcmtl_cmd_set_vertex_buffer");

        /** {@code mcmtl_cmd_set_index_buffer} (16 bytes). */
        public static final StructLayout SET_INDEX_BUFFER = MemoryLayout.structLayout(
            JAVA_SHORT.withName("op"),
            JAVA_SHORT.withName("size"),
            JAVA_INT.withName("indexType"),
            JAVA_LONG.withName("buffer")).withName("mcmtl_cmd_set_index_buffer");

        /** {@code mcmtl_cmd_set_scissor} (32 bytes). */
        public static final StructLayout SET_SCISSOR = MemoryLayout.structLayout(
            JAVA_SHORT.withName("op"),
            JAVA_SHORT.withName("size"),
            JAVA_INT.withName("reserved0"),
            JAVA_INT.withName("x"),
            JAVA_INT.withName("y"),
            JAVA_INT.withName("width"),
            JAVA_INT.withName("height"),
            JAVA_LONG.withName("reserved1")).withName("mcmtl_cmd_set_scissor");

        /** {@code mcmtl_cmd_push_constants} (16 bytes). */
        public static final StructLayout PUSH_CONSTANTS = MemoryLayout.structLayout(
            JAVA_SHORT.withName("op"),
            JAVA_SHORT.withName("size"),
            JAVA_INT.withName("byteLength"),
            JAVA_INT.withName("stages"),
            JAVA_INT.withName("reserved")).withName("mcmtl_cmd_push_constants");

        /** {@code mcmtl_cmd_draw} (32 bytes). */
        public static final StructLayout DRAW = MemoryLayout.structLayout(
            JAVA_SHORT.withName("op"),
            JAVA_SHORT.withName("size"),
            JAVA_INT.withName("vertexCount"),
            JAVA_INT.withName("instanceCount"),
            JAVA_INT.withName("firstVertex"),
            JAVA_INT.withName("firstInstance"),
            JAVA_INT.withName("reserved0"),
            JAVA_LONG.withName("reserved1")).withName("mcmtl_cmd_draw");

        /** {@code mcmtl_cmd_draw_indexed} (32 bytes). */
        public static final StructLayout DRAW_INDEXED = MemoryLayout.structLayout(
            JAVA_SHORT.withName("op"),
            JAVA_SHORT.withName("size"),
            JAVA_INT.withName("indexCount"),
            JAVA_INT.withName("instanceCount"),
            JAVA_INT.withName("firstIndex"),
            JAVA_INT.withName("baseVertex"),
            JAVA_INT.withName("firstInstance"),
            JAVA_LONG.withName("reserved")).withName("mcmtl_cmd_draw_indexed");

        /** {@code mcmtl_cmd_draw_indirect} (32 bytes). */
        public static final StructLayout DRAW_INDIRECT = MemoryLayout.structLayout(
            JAVA_SHORT.withName("op"),
            JAVA_SHORT.withName("size"),
            JAVA_INT.withName("drawCount"),
            JAVA_LONG.withName("buffer"),
            JAVA_LONG.withName("offset"),
            JAVA_LONG.withName("reserved")).withName("mcmtl_cmd_draw_indirect");

    }

    // =============================================================================================
    // Helpers
    // =============================================================================================

    /**
     * The first 8 bytes of a record as one little-endian long: {@code op} (bits 0..15), {@code size}
     * (bits 16..31), the record's named 32-bit operand at offset 4 (bits 32..63).
     */
    public static long header(int op, int size, int arg) {
        return (op & 0xFFFFL) | ((size & 0xFFFFL) << 16) | ((long)arg << 32);
    }

    /** Rounds up to {@link #STREAM_ALIGNMENT} (16). */
    public static int align16(int bytes) {
        return (bytes + 15) & ~15;
    }

    /** Size of a BEGIN_PASS record with {@code colorCount} color attachment slots. */
    public static int beginPassSize(int colorCount) {
        return SIZE_BEGIN_PASS_BASE + SIZE_COLOR_ATTACHMENT * colorCount;
    }

    /** Size of a PUSH_DEBUG_GROUP record carrying {@code labelBytes} UTF-8 bytes. */
    public static int pushDebugGroupSize(int labelBytes) {
        return Math.max(SIZE_PUSH_DEBUG_GROUP_MIN, align16((int)PUSH_DEBUG_GROUP_SIZEOF + labelBytes));
    }

    /** Size of a PUSH_CONSTANTS record carrying {@code byteLength} bytes. */
    public static int pushConstantsSize(int byteLength) {
        return SIZE_PUSH_CONSTANTS_MIN + align16(byteLength);
    }

    /** True for ops that are only valid between BEGIN_PASS and END_PASS. */
    public static boolean isPassOp(int op) {
        return op >= 32 && op < OP_LIMIT;
    }

    /** True for ops allowed in the init stream (INIT_TEXTURE and NOP; CONTRACT.md 4.2). */
    public static boolean isInitStreamOp(int op) {
        return op == OP_INIT_TEXTURE || op == OP_NOP;
    }

    /**
     * True for ops that are only valid outside a render pass: 1..15 except {@code END_PASS}, which is in the
     * encoder-level number range but only valid inside a pass (CONTRACT.md 4.1, integration amendment).
     */
    public static boolean isEncoderOp(int op) {
        return op >= 1 && op < 16 && op != OP_END_PASS;
    }
}
