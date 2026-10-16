package com.mojang.renderpearl.backend.metal;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendOp;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PolygonMode;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import java.util.OptionalDouble;

/**
 * Raw Metal enum values (macOS 27 SDK, extracted with a compile-time probe) and every RenderPearl -> Metal
 * mapping of spec section 4. The C ABI carries these raw values unchanged (CONTRACT.md 3.1).
 *
 * <p><b>FROZEN CONTRACT FILE (owner: architect).</b> Shared by I3 (textures, samplers, buffers), I4
 * (index types, blit options) and I5 (pipelines). Pure functions, no state.
 */
public final class MetalFormats {
    private MetalFormats() {
    }

    // ---- MTLPixelFormat ----------------------------------------------------------------------
    public static final int PIXEL_FORMAT_INVALID = 0;
    public static final int PIXEL_FORMAT_R8_UNORM = 10;
    public static final int PIXEL_FORMAT_R8_SNORM = 12;
    public static final int PIXEL_FORMAT_R8_UINT = 13;
    public static final int PIXEL_FORMAT_R8_SINT = 14;
    public static final int PIXEL_FORMAT_R16_UNORM = 20;
    public static final int PIXEL_FORMAT_R16_SNORM = 22;
    public static final int PIXEL_FORMAT_R16_UINT = 23;
    public static final int PIXEL_FORMAT_R16_SINT = 24;
    public static final int PIXEL_FORMAT_R16_FLOAT = 25;
    public static final int PIXEL_FORMAT_RG8_UNORM = 30;
    public static final int PIXEL_FORMAT_RG8_SNORM = 32;
    public static final int PIXEL_FORMAT_RG8_UINT = 33;
    public static final int PIXEL_FORMAT_RG8_SINT = 34;
    public static final int PIXEL_FORMAT_R32_UINT = 53;
    public static final int PIXEL_FORMAT_R32_SINT = 54;
    public static final int PIXEL_FORMAT_R32_FLOAT = 55;
    public static final int PIXEL_FORMAT_RG16_UNORM = 60;
    public static final int PIXEL_FORMAT_RG16_SNORM = 62;
    public static final int PIXEL_FORMAT_RG16_UINT = 63;
    public static final int PIXEL_FORMAT_RG16_SINT = 64;
    public static final int PIXEL_FORMAT_RG16_FLOAT = 65;
    public static final int PIXEL_FORMAT_RGBA8_UNORM = 70;
    public static final int PIXEL_FORMAT_RGBA8_SNORM = 72;
    public static final int PIXEL_FORMAT_RGBA8_UINT = 73;
    public static final int PIXEL_FORMAT_RGBA8_SINT = 74;
    public static final int PIXEL_FORMAT_BGRA8_UNORM = 80;
    public static final int PIXEL_FORMAT_RGB10A2_UNORM = 90;
    public static final int PIXEL_FORMAT_RGB10A2_UINT = 91;
    public static final int PIXEL_FORMAT_RG11B10_FLOAT = 92;
    public static final int PIXEL_FORMAT_RG32_UINT = 103;
    public static final int PIXEL_FORMAT_RG32_SINT = 104;
    public static final int PIXEL_FORMAT_RG32_FLOAT = 105;
    public static final int PIXEL_FORMAT_RGBA16_UNORM = 110;
    public static final int PIXEL_FORMAT_RGBA16_SNORM = 112;
    public static final int PIXEL_FORMAT_RGBA16_UINT = 113;
    public static final int PIXEL_FORMAT_RGBA16_SINT = 114;
    public static final int PIXEL_FORMAT_RGBA16_FLOAT = 115;
    public static final int PIXEL_FORMAT_RGBA32_UINT = 123;
    public static final int PIXEL_FORMAT_RGBA32_SINT = 124;
    public static final int PIXEL_FORMAT_RGBA32_FLOAT = 125;
    public static final int PIXEL_FORMAT_DEPTH16_UNORM = 250;
    public static final int PIXEL_FORMAT_DEPTH32_FLOAT = 252;
    public static final int PIXEL_FORMAT_STENCIL8 = 253;
    public static final int PIXEL_FORMAT_DEPTH24_UNORM_STENCIL8 = 255;
    public static final int PIXEL_FORMAT_DEPTH32_FLOAT_STENCIL8 = 260;

    // ---- MTLVertexFormat ---------------------------------------------------------------------
    public static final int VERTEX_FORMAT_INVALID = 0;
    public static final int VERTEX_FORMAT_UCHAR2 = 1;
    public static final int VERTEX_FORMAT_UCHAR3 = 2;
    public static final int VERTEX_FORMAT_UCHAR4 = 3;
    public static final int VERTEX_FORMAT_CHAR2 = 4;
    public static final int VERTEX_FORMAT_CHAR3 = 5;
    public static final int VERTEX_FORMAT_CHAR4 = 6;
    public static final int VERTEX_FORMAT_UCHAR2_NORMALIZED = 7;
    public static final int VERTEX_FORMAT_UCHAR3_NORMALIZED = 8;
    public static final int VERTEX_FORMAT_UCHAR4_NORMALIZED = 9;
    public static final int VERTEX_FORMAT_CHAR2_NORMALIZED = 10;
    public static final int VERTEX_FORMAT_CHAR3_NORMALIZED = 11;
    public static final int VERTEX_FORMAT_CHAR4_NORMALIZED = 12;
    public static final int VERTEX_FORMAT_USHORT2 = 13;
    public static final int VERTEX_FORMAT_USHORT3 = 14;
    public static final int VERTEX_FORMAT_USHORT4 = 15;
    public static final int VERTEX_FORMAT_SHORT2 = 16;
    public static final int VERTEX_FORMAT_SHORT3 = 17;
    public static final int VERTEX_FORMAT_SHORT4 = 18;
    public static final int VERTEX_FORMAT_USHORT2_NORMALIZED = 19;
    public static final int VERTEX_FORMAT_USHORT3_NORMALIZED = 20;
    public static final int VERTEX_FORMAT_USHORT4_NORMALIZED = 21;
    public static final int VERTEX_FORMAT_SHORT2_NORMALIZED = 22;
    public static final int VERTEX_FORMAT_SHORT3_NORMALIZED = 23;
    public static final int VERTEX_FORMAT_SHORT4_NORMALIZED = 24;
    public static final int VERTEX_FORMAT_HALF2 = 25;
    public static final int VERTEX_FORMAT_HALF3 = 26;
    public static final int VERTEX_FORMAT_HALF4 = 27;
    public static final int VERTEX_FORMAT_FLOAT = 28;
    public static final int VERTEX_FORMAT_FLOAT2 = 29;
    public static final int VERTEX_FORMAT_FLOAT3 = 30;
    public static final int VERTEX_FORMAT_FLOAT4 = 31;
    public static final int VERTEX_FORMAT_INT = 32;
    public static final int VERTEX_FORMAT_INT2 = 33;
    public static final int VERTEX_FORMAT_INT3 = 34;
    public static final int VERTEX_FORMAT_INT4 = 35;
    public static final int VERTEX_FORMAT_UINT = 36;
    public static final int VERTEX_FORMAT_UINT2 = 37;
    public static final int VERTEX_FORMAT_UINT3 = 38;
    public static final int VERTEX_FORMAT_UINT4 = 39;
    public static final int VERTEX_FORMAT_INT1010102_NORMALIZED = 40;
    public static final int VERTEX_FORMAT_UINT1010102_NORMALIZED = 41;
    public static final int VERTEX_FORMAT_UCHAR = 45;
    public static final int VERTEX_FORMAT_CHAR = 46;
    public static final int VERTEX_FORMAT_UCHAR_NORMALIZED = 47;
    public static final int VERTEX_FORMAT_CHAR_NORMALIZED = 48;
    public static final int VERTEX_FORMAT_USHORT = 49;
    public static final int VERTEX_FORMAT_SHORT = 50;
    public static final int VERTEX_FORMAT_USHORT_NORMALIZED = 51;
    public static final int VERTEX_FORMAT_SHORT_NORMALIZED = 52;
    public static final int VERTEX_FORMAT_HALF = 53;
    public static final int VERTEX_FORMAT_FLOAT_RG11B10 = 54;

    // ---- Blending ----------------------------------------------------------------------------
    public static final int BLEND_FACTOR_ZERO = 0;
    public static final int BLEND_FACTOR_ONE = 1;
    public static final int BLEND_FACTOR_SOURCE_COLOR = 2;
    public static final int BLEND_FACTOR_ONE_MINUS_SOURCE_COLOR = 3;
    public static final int BLEND_FACTOR_SOURCE_ALPHA = 4;
    public static final int BLEND_FACTOR_ONE_MINUS_SOURCE_ALPHA = 5;
    public static final int BLEND_FACTOR_DESTINATION_COLOR = 6;
    public static final int BLEND_FACTOR_ONE_MINUS_DESTINATION_COLOR = 7;
    public static final int BLEND_FACTOR_DESTINATION_ALPHA = 8;
    public static final int BLEND_FACTOR_ONE_MINUS_DESTINATION_ALPHA = 9;
    public static final int BLEND_FACTOR_SOURCE_ALPHA_SATURATED = 10;
    public static final int BLEND_FACTOR_BLEND_COLOR = 11;
    public static final int BLEND_FACTOR_ONE_MINUS_BLEND_COLOR = 12;
    public static final int BLEND_FACTOR_BLEND_ALPHA = 13;
    public static final int BLEND_FACTOR_ONE_MINUS_BLEND_ALPHA = 14;
    public static final int BLEND_OPERATION_ADD = 0;
    public static final int BLEND_OPERATION_SUBTRACT = 1;
    public static final int BLEND_OPERATION_REVERSE_SUBTRACT = 2;
    public static final int BLEND_OPERATION_MIN = 3;
    public static final int BLEND_OPERATION_MAX = 4;
    public static final int COLOR_WRITE_MASK_NONE = 0;
    public static final int COLOR_WRITE_MASK_RED = 8;
    public static final int COLOR_WRITE_MASK_GREEN = 4;
    public static final int COLOR_WRITE_MASK_BLUE = 2;
    public static final int COLOR_WRITE_MASK_ALPHA = 1;
    public static final int COLOR_WRITE_MASK_ALL = 15;

    // ---- MTLCompareFunction ------------------------------------------------------------------
    public static final int COMPARE_NEVER = 0;
    public static final int COMPARE_LESS = 1;
    public static final int COMPARE_EQUAL = 2;
    public static final int COMPARE_LESS_EQUAL = 3;
    public static final int COMPARE_GREATER = 4;
    public static final int COMPARE_NOT_EQUAL = 5;
    public static final int COMPARE_GREATER_EQUAL = 6;
    public static final int COMPARE_ALWAYS = 7;

    // ---- Samplers ----------------------------------------------------------------------------
    public static final int SAMPLER_ADDRESS_CLAMP_TO_EDGE = 0;
    public static final int SAMPLER_ADDRESS_MIRROR_CLAMP_TO_EDGE = 1;
    public static final int SAMPLER_ADDRESS_REPEAT = 2;
    public static final int SAMPLER_ADDRESS_MIRROR_REPEAT = 3;
    public static final int SAMPLER_ADDRESS_CLAMP_TO_ZERO = 4;
    public static final int SAMPLER_MIN_MAG_NEAREST = 0;
    public static final int SAMPLER_MIN_MAG_LINEAR = 1;
    public static final int SAMPLER_MIP_NOT_MIPMAPPED = 0;
    public static final int SAMPLER_MIP_NEAREST = 1;
    public static final int SAMPLER_MIP_LINEAR = 2;

    // ---- Primitives, rasterizer, indices ------------------------------------------------------
    public static final int PRIMITIVE_POINT = 0;
    public static final int PRIMITIVE_LINE = 1;
    public static final int PRIMITIVE_LINE_STRIP = 2;
    public static final int PRIMITIVE_TRIANGLE = 3;
    public static final int PRIMITIVE_TRIANGLE_STRIP = 4;
    public static final int TOPOLOGY_CLASS_UNSPECIFIED = 0;
    public static final int TOPOLOGY_CLASS_POINT = 1;
    public static final int TOPOLOGY_CLASS_LINE = 2;
    public static final int TOPOLOGY_CLASS_TRIANGLE = 3;
    public static final int CULL_NONE = 0;
    public static final int CULL_FRONT = 1;
    public static final int CULL_BACK = 2;
    public static final int FILL_MODE_FILL = 0;
    public static final int FILL_MODE_LINES = 1;
    public static final int WINDING_CLOCKWISE = 0;
    public static final int WINDING_COUNTER_CLOCKWISE = 1;
    public static final int INDEX_TYPE_UINT16 = 0;
    public static final int INDEX_TYPE_UINT32 = 1;
    public static final int VERTEX_STEP_CONSTANT = 0;
    public static final int VERTEX_STEP_PER_VERTEX = 1;
    public static final int VERTEX_STEP_PER_INSTANCE = 2;

    // ---- Textures and resources ---------------------------------------------------------------
    public static final int TEXTURE_TYPE_2D = 2;
    public static final int TEXTURE_TYPE_2D_ARRAY = 3;
    public static final int TEXTURE_TYPE_CUBE = 5;
    public static final int TEXTURE_TYPE_TEXTURE_BUFFER = 9;
    public static final int TEXTURE_USAGE_SHADER_READ = 1;
    public static final int TEXTURE_USAGE_SHADER_WRITE = 2;
    public static final int TEXTURE_USAGE_RENDER_TARGET = 4;
    public static final int TEXTURE_USAGE_PIXEL_FORMAT_VIEW = 16;
    /** MTLResourceOptions bits (storage mode << 4, cpu cache mode, hazard tracking mode << 8). */
    public static final int RESOURCE_STORAGE_SHARED = 0;
    public static final int RESOURCE_STORAGE_PRIVATE = 32;
    public static final int RESOURCE_CPU_CACHE_WRITE_COMBINED = 1;
    public static final int RESOURCE_HAZARD_TRACKING_UNTRACKED = 256;
    public static final int BLIT_OPTION_NONE = 0;
    public static final int BLIT_OPTION_DEPTH_FROM_DEPTH_STENCIL = 1;
    public static final int BLIT_OPTION_STENCIL_FROM_DEPTH_STENCIL = 2;

    // ---- Misc ---------------------------------------------------------------------------------
    /** MTLLanguageVersion3_2 (MSL 3.2), the version shaders are compiled with (spec 3.3). */
    public static final int LANGUAGE_VERSION_3_2 = 196610;
    /** MTLLanguageVersion4_0 (reserved for the M3 experiment, spec U2). */
    public static final int LANGUAGE_VERSION_4_0 = 262144;
    /** SPIRV-Cross MSL_VERSION encoding of MSL 3.2 (major * 10000 + minor * 100). */
    public static final int SPVC_MSL_VERSION_3_2 = 30200;
    public static final int COMMAND_BUFFER_ERROR_NONE = 0;
    public static final int COMMAND_BUFFER_ERROR_INTERNAL = 1;
    public static final int COMMAND_BUFFER_ERROR_TIMEOUT = 2;
    public static final int COMMAND_BUFFER_ERROR_PAGE_FAULT = 3;
    public static final int COMMAND_BUFFER_ERROR_ACCESS_REVOKED = 4;
    public static final int COMMAND_BUFFER_ERROR_NOT_PERMITTED = 7;
    public static final int COMMAND_BUFFER_ERROR_OUT_OF_MEMORY = 8;
    public static final int COMMAND_BUFFER_ERROR_INVALID_RESOURCE = 9;
    public static final int COMMAND_BUFFER_ERROR_MEMORYLESS = 10;
    public static final int COMMAND_BUFFER_ERROR_DEVICE_REMOVED = 11;
    public static final int COMMAND_BUFFER_ERROR_STACK_OVERFLOW = 12;

    // =============================================================================================
    // Mappings (spec section 4)
    // =============================================================================================

    /**
     * MTLPixelFormat for a texture format, or {@link #PIXEL_FORMAT_INVALID} when Metal has none (every
     * 3-component format and D24_UNORM_S8_UINT on Apple silicon): callers throw IllegalArgumentException.
     */
    public static int pixelFormat(GpuFormat format) {
        return switch (format) {
            case R8_UNORM -> PIXEL_FORMAT_R8_UNORM;
            case R8_SNORM -> PIXEL_FORMAT_R8_SNORM;
            case RG8_UNORM -> PIXEL_FORMAT_RG8_UNORM;
            case RG8_SNORM -> PIXEL_FORMAT_RG8_SNORM;
            case RGBA8_UNORM -> PIXEL_FORMAT_RGBA8_UNORM;
            case RGBA8_SNORM -> PIXEL_FORMAT_RGBA8_SNORM;
            case R16_UNORM -> PIXEL_FORMAT_R16_UNORM;
            case R16_SNORM -> PIXEL_FORMAT_R16_SNORM;
            case RG16_UNORM -> PIXEL_FORMAT_RG16_UNORM;
            case RG16_SNORM -> PIXEL_FORMAT_RG16_SNORM;
            case RGBA16_UNORM -> PIXEL_FORMAT_RGBA16_UNORM;
            case RGBA16_SNORM -> PIXEL_FORMAT_RGBA16_SNORM;
            case R8_UINT -> PIXEL_FORMAT_R8_UINT;
            case R8_SINT -> PIXEL_FORMAT_R8_SINT;
            case RG8_UINT -> PIXEL_FORMAT_RG8_UINT;
            case RG8_SINT -> PIXEL_FORMAT_RG8_SINT;
            case RGBA8_UINT -> PIXEL_FORMAT_RGBA8_UINT;
            case RGBA8_SINT -> PIXEL_FORMAT_RGBA8_SINT;
            case R16_UINT -> PIXEL_FORMAT_R16_UINT;
            case R16_SINT -> PIXEL_FORMAT_R16_SINT;
            case RG16_UINT -> PIXEL_FORMAT_RG16_UINT;
            case RG16_SINT -> PIXEL_FORMAT_RG16_SINT;
            case RGBA16_UINT -> PIXEL_FORMAT_RGBA16_UINT;
            case RGBA16_SINT -> PIXEL_FORMAT_RGBA16_SINT;
            case R32_UINT -> PIXEL_FORMAT_R32_UINT;
            case R32_SINT -> PIXEL_FORMAT_R32_SINT;
            case RG32_UINT -> PIXEL_FORMAT_RG32_UINT;
            case RG32_SINT -> PIXEL_FORMAT_RG32_SINT;
            case RGBA32_UINT -> PIXEL_FORMAT_RGBA32_UINT;
            case RGBA32_SINT -> PIXEL_FORMAT_RGBA32_SINT;
            case R16_FLOAT -> PIXEL_FORMAT_R16_FLOAT;
            case RG16_FLOAT -> PIXEL_FORMAT_RG16_FLOAT;
            case RGBA16_FLOAT -> PIXEL_FORMAT_RGBA16_FLOAT;
            case R32_FLOAT -> PIXEL_FORMAT_R32_FLOAT;
            case RG32_FLOAT -> PIXEL_FORMAT_RG32_FLOAT;
            case RGBA32_FLOAT -> PIXEL_FORMAT_RGBA32_FLOAT;
            case RGB10A2_UNORM -> PIXEL_FORMAT_RGB10A2_UNORM;
            case RGB10A2_UINT -> PIXEL_FORMAT_RGB10A2_UINT;
            case RG11B10_FLOAT -> PIXEL_FORMAT_RG11B10_FLOAT;
            case D32_FLOAT -> PIXEL_FORMAT_DEPTH32_FLOAT;
            case D32_FLOAT_S8_UINT -> PIXEL_FORMAT_DEPTH32_FLOAT_STENCIL8;
            case D16_UNORM -> PIXEL_FORMAT_DEPTH16_UNORM;
            case S8_UINT -> PIXEL_FORMAT_STENCIL8;
            case RGB8_UNORM, RGB8_SNORM, RGB16_UNORM, RGB16_SNORM, RGB8_UINT, RGB8_SINT, RGB16_UINT, RGB16_SINT, RGB32_UINT, RGB32_SINT,
                RGB16_FLOAT, RGB32_FLOAT, D24_UNORM_S8_UINT -> PIXEL_FORMAT_INVALID;
        };
    }

    /** MTLVertexFormat for a vertex attribute format, or {@link #VERTEX_FORMAT_INVALID} (depth/stencil, RGB10A2_UINT). */
    public static int vertexFormat(GpuFormat format) {
        return switch (format) {
            case R8_UNORM -> VERTEX_FORMAT_UCHAR_NORMALIZED;
            case R8_SNORM -> VERTEX_FORMAT_CHAR_NORMALIZED;
            case RG8_UNORM -> VERTEX_FORMAT_UCHAR2_NORMALIZED;
            case RG8_SNORM -> VERTEX_FORMAT_CHAR2_NORMALIZED;
            case RGB8_UNORM -> VERTEX_FORMAT_UCHAR3_NORMALIZED;
            case RGB8_SNORM -> VERTEX_FORMAT_CHAR3_NORMALIZED;
            case RGBA8_UNORM -> VERTEX_FORMAT_UCHAR4_NORMALIZED;
            case RGBA8_SNORM -> VERTEX_FORMAT_CHAR4_NORMALIZED;
            case R16_UNORM -> VERTEX_FORMAT_USHORT_NORMALIZED;
            case R16_SNORM -> VERTEX_FORMAT_SHORT_NORMALIZED;
            case RG16_UNORM -> VERTEX_FORMAT_USHORT2_NORMALIZED;
            case RG16_SNORM -> VERTEX_FORMAT_SHORT2_NORMALIZED;
            case RGB16_UNORM -> VERTEX_FORMAT_USHORT3_NORMALIZED;
            case RGB16_SNORM -> VERTEX_FORMAT_SHORT3_NORMALIZED;
            case RGBA16_UNORM -> VERTEX_FORMAT_USHORT4_NORMALIZED;
            case RGBA16_SNORM -> VERTEX_FORMAT_SHORT4_NORMALIZED;
            case R8_UINT -> VERTEX_FORMAT_UCHAR;
            case R8_SINT -> VERTEX_FORMAT_CHAR;
            case RG8_UINT -> VERTEX_FORMAT_UCHAR2;
            case RG8_SINT -> VERTEX_FORMAT_CHAR2;
            case RGB8_UINT -> VERTEX_FORMAT_UCHAR3;
            case RGB8_SINT -> VERTEX_FORMAT_CHAR3;
            case RGBA8_UINT -> VERTEX_FORMAT_UCHAR4;
            case RGBA8_SINT -> VERTEX_FORMAT_CHAR4;
            case R16_UINT -> VERTEX_FORMAT_USHORT;
            case R16_SINT -> VERTEX_FORMAT_SHORT;
            case RG16_UINT -> VERTEX_FORMAT_USHORT2;
            case RG16_SINT -> VERTEX_FORMAT_SHORT2;
            case RGB16_UINT -> VERTEX_FORMAT_USHORT3;
            case RGB16_SINT -> VERTEX_FORMAT_SHORT3;
            case RGBA16_UINT -> VERTEX_FORMAT_USHORT4;
            case RGBA16_SINT -> VERTEX_FORMAT_SHORT4;
            case R32_UINT -> VERTEX_FORMAT_UINT;
            case R32_SINT -> VERTEX_FORMAT_INT;
            case RG32_UINT -> VERTEX_FORMAT_UINT2;
            case RG32_SINT -> VERTEX_FORMAT_INT2;
            case RGB32_UINT -> VERTEX_FORMAT_UINT3;
            case RGB32_SINT -> VERTEX_FORMAT_INT3;
            case RGBA32_UINT -> VERTEX_FORMAT_UINT4;
            case RGBA32_SINT -> VERTEX_FORMAT_INT4;
            case R16_FLOAT -> VERTEX_FORMAT_HALF;
            case RG16_FLOAT -> VERTEX_FORMAT_HALF2;
            case RGB16_FLOAT -> VERTEX_FORMAT_HALF3;
            case RGBA16_FLOAT -> VERTEX_FORMAT_HALF4;
            case R32_FLOAT -> VERTEX_FORMAT_FLOAT;
            case RG32_FLOAT -> VERTEX_FORMAT_FLOAT2;
            case RGB32_FLOAT -> VERTEX_FORMAT_FLOAT3;
            case RGBA32_FLOAT -> VERTEX_FORMAT_FLOAT4;
            case RGB10A2_UNORM -> VERTEX_FORMAT_UINT1010102_NORMALIZED;
            case RG11B10_FLOAT -> VERTEX_FORMAT_FLOAT_RG11B10;
            case RGB10A2_UINT, D32_FLOAT, D32_FLOAT_S8_UINT, D24_UNORM_S8_UINT, D16_UNORM, S8_UINT -> VERTEX_FORMAT_INVALID;
        };
    }

    /** True for pixel formats with a stencil aspect (PSO stencilAttachmentPixelFormat, blit options). */
    public static boolean hasStencil(int pixelFormat) {
        return pixelFormat == PIXEL_FORMAT_STENCIL8
            || pixelFormat == PIXEL_FORMAT_DEPTH24_UNORM_STENCIL8
            || pixelFormat == PIXEL_FORMAT_DEPTH32_FLOAT_STENCIL8;
    }

    /** True for pixel formats with a depth aspect. */
    public static boolean hasDepth(int pixelFormat) {
        return pixelFormat == PIXEL_FORMAT_DEPTH16_UNORM
            || pixelFormat == PIXEL_FORMAT_DEPTH32_FLOAT
            || pixelFormat == PIXEL_FORMAT_DEPTH24_UNORM_STENCIL8
            || pixelFormat == PIXEL_FORMAT_DEPTH32_FLOAT_STENCIL8;
    }

    /** MTLBlitOption for texture <-> buffer copies of {@code format}: depth plane of combined depth/stencil. */
    public static int blitOptions(GpuFormat format) {
        return format == GpuFormat.D32_FLOAT_S8_UINT || format == GpuFormat.D24_UNORM_S8_UINT ? BLIT_OPTION_DEPTH_FROM_DEPTH_STENCIL : BLIT_OPTION_NONE;
    }

    public static int blendFactor(BlendFactor factor) {
        return switch (factor) {
            case ZERO -> BLEND_FACTOR_ZERO;
            case ONE -> BLEND_FACTOR_ONE;
            case SRC_COLOR -> BLEND_FACTOR_SOURCE_COLOR;
            case ONE_MINUS_SRC_COLOR -> BLEND_FACTOR_ONE_MINUS_SOURCE_COLOR;
            case SRC_ALPHA -> BLEND_FACTOR_SOURCE_ALPHA;
            case ONE_MINUS_SRC_ALPHA -> BLEND_FACTOR_ONE_MINUS_SOURCE_ALPHA;
            case DST_COLOR -> BLEND_FACTOR_DESTINATION_COLOR;
            case ONE_MINUS_DST_COLOR -> BLEND_FACTOR_ONE_MINUS_DESTINATION_COLOR;
            case DST_ALPHA -> BLEND_FACTOR_DESTINATION_ALPHA;
            case ONE_MINUS_DST_ALPHA -> BLEND_FACTOR_ONE_MINUS_DESTINATION_ALPHA;
            case SRC_ALPHA_SATURATE -> BLEND_FACTOR_SOURCE_ALPHA_SATURATED;
            case CONSTANT_COLOR -> BLEND_FACTOR_BLEND_COLOR;
            case ONE_MINUS_CONSTANT_COLOR -> BLEND_FACTOR_ONE_MINUS_BLEND_COLOR;
            case CONSTANT_ALPHA -> BLEND_FACTOR_BLEND_ALPHA;
            case ONE_MINUS_CONSTANT_ALPHA -> BLEND_FACTOR_ONE_MINUS_BLEND_ALPHA;
        };
    }

    public static int blendOperation(BlendOp op) {
        return switch (op) {
            case ADD -> BLEND_OPERATION_ADD;
            case SUBTRACT -> BLEND_OPERATION_SUBTRACT;
            case REVERSE_SUBTRACT -> BLEND_OPERATION_REVERSE_SUBTRACT;
            case MIN -> BLEND_OPERATION_MIN;
            case MAX -> BLEND_OPERATION_MAX;
        };
    }

    /** RenderPearl write mask (R=1, G=2, B=4, A=8) -> MTLColorWriteMask (R=8, G=4, B=2, A=1). */
    public static int colorWriteMask(int renderPearlMask) {
        return ((renderPearlMask & 1) << 3) | ((renderPearlMask & 2) << 1) | ((renderPearlMask & 4) >> 1) | ((renderPearlMask & 8) >> 3);
    }

    public static int compareFunction(CompareOp op) {
        return switch (op) {
            case ALWAYS_PASS -> COMPARE_ALWAYS;
            case NEVER_PASS -> COMPARE_NEVER;
            case LESS_THAN -> COMPARE_LESS;
            case LESS_THAN_OR_EQUAL -> COMPARE_LESS_EQUAL;
            case EQUAL -> COMPARE_EQUAL;
            case NOT_EQUAL -> COMPARE_NOT_EQUAL;
            case GREATER_THAN -> COMPARE_GREATER;
            case GREATER_THAN_OR_EQUAL -> COMPARE_GREATER_EQUAL;
        };
    }

    public static int addressMode(AddressMode mode) {
        return switch (mode) {
            case REPEAT -> SAMPLER_ADDRESS_REPEAT;
            case CLAMP_TO_EDGE -> SAMPLER_ADDRESS_CLAMP_TO_EDGE;
        };
    }

    public static int minMagFilter(FilterMode mode) {
        return switch (mode) {
            case NEAREST -> SAMPLER_MIN_MAG_NEAREST;
            case LINEAR -> SAMPLER_MIN_MAG_LINEAR;
        };
    }

    /** Spec 2.2: NotMipmapped iff maxLod is present and <= 0.25, else Linear. */
    public static int mipFilter(OptionalDouble maxLod) {
        return maxLod.isPresent() && maxLod.getAsDouble() <= 0.25 ? SAMPLER_MIP_NOT_MIPMAPPED : SAMPLER_MIP_LINEAR;
    }

    /** lodMaxClamp: maxLod or Float.MAX_VALUE. */
    public static float lodMaxClamp(OptionalDouble maxLod) {
        return maxLod.isPresent() ? (float)maxLod.getAsDouble() : Float.MAX_VALUE;
    }

    /** MTLPrimitiveType used for draws (fans are drawn as triangle lists after emulation). */
    public static int primitiveType(PrimitiveTopology topology) {
        return switch (topology) {
            case QUADS, LINES, TRIANGLES, TRIANGLE_FAN -> PRIMITIVE_TRIANGLE;
            case TRIANGLE_STRIP -> PRIMITIVE_TRIANGLE_STRIP;
            case DEBUG_LINES -> PRIMITIVE_LINE;
            case DEBUG_LINE_STRIP -> PRIMITIVE_LINE_STRIP;
            case POINTS -> PRIMITIVE_POINT;
        };
    }

    /** MTLPrimitiveTopologyClass for the PSO: Point for POINTS, otherwise Unspecified. */
    public static int topologyClass(PrimitiveTopology topology) {
        return topology == PrimitiveTopology.POINTS ? TOPOLOGY_CLASS_POINT : TOPOLOGY_CLASS_UNSPECIFIED;
    }

    /** MtlNative.EMULATION_TRIANGLE_FAN for TRIANGLE_FAN, else EMULATION_NONE. */
    public static int emulation(PrimitiveTopology topology) {
        return topology == PrimitiveTopology.TRIANGLE_FAN ? MtlNative.EMULATION_TRIANGLE_FAN : MtlNative.EMULATION_NONE;
    }

    public static int indexType(IndexType type) {
        return switch (type) {
            case SHORT -> INDEX_TYPE_UINT16;
            case INT -> INDEX_TYPE_UINT32;
        };
    }

    public static int cullMode(boolean cull) {
        return cull ? CULL_BACK : CULL_NONE;
    }

    public static int fillMode(PolygonMode mode) {
        return switch (mode) {
            case FILL -> FILL_MODE_FILL;
            case WIREFRAME -> FILL_MODE_LINES;
        };
    }

    /** Spec 2.2: Cube iff USAGE_CUBEMAP_COMPATIBLE, else 2D. */
    public static int textureType(@GpuTexture.Usage int usage) {
        return (usage & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0 ? TEXTURE_TYPE_CUBE : TEXTURE_TYPE_2D;
    }

    /**
     * Spec 2.2: ShaderRead iff TEXTURE_BINDING or COPY_SRC (the present pass samples any blit source),
     * RenderTarget iff RENDER_ATTACHMENT. Never ShaderWrite / PixelFormatView (they disable lossless
     * compression).
     */
    public static int textureUsage(@GpuTexture.Usage int usage) {
        int result = 0;
        if ((usage & (GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_SRC)) != 0) {
            result |= TEXTURE_USAGE_SHADER_READ;
        }
        if ((usage & GpuTexture.USAGE_RENDER_ATTACHMENT) != 0) {
            result |= TEXTURE_USAGE_RENDER_TARGET;
        }
        if ((usage & USAGE_EXT_SHADER_WRITE) != 0) {
            result |= TEXTURE_USAGE_SHADER_WRITE;
        }
        return result;
    }

    /**
     * Backend-private usage bit (round 7, CONTRACT 16.R7-1): ShaderWrite for the textures a compute kernel writes
     * ({@link MetalTickFx}: the two lightmap copies). Never set by vanilla code; only {@link MetalTickFx#createWritable}
     * passes it, straight to {@link MetalTexture#create}.
     */
    public static final int USAGE_EXT_SHADER_WRITE = 1 << 24;

    /** MTLResourceOptions for textures (M1: Private, tracked). */
    public static int textureResourceOptions() {
        return RESOURCE_STORAGE_PRIVATE;
    }

    /**
     * MTLResourceOptions for buffers (M1: Shared, tracked). Write-combined only for MAP_WRITE without
     * MAP_READ and only when {@code allowWriteCombined} (a benchmark toggle, spec U14); never for readback.
     */
    public static int bufferResourceOptions(@GpuBuffer.Usage int usage, boolean allowWriteCombined) {
        boolean writeOnly = (usage & GpuBuffer.USAGE_MAP_WRITE) != 0 && (usage & GpuBuffer.USAGE_MAP_READ) == 0;
        return RESOURCE_STORAGE_SHARED | (allowWriteCombined && writeOnly ? RESOURCE_CPU_CACHE_WRITE_COMBINED : 0);
    }
}
