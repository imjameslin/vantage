package com.mojang.renderpearl.backend.metal;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import com.mojang.renderpearl.api.device.GpuDeviceLossException;
import com.mojang.renderpearl.api.device.GpuOutOfMemoryException;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.Library;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;

/**
 * Java 25 FFM bindings for {@code libmcmetal.dylib} (native/include/mcmetal.h).
 *
 * <p><b>FROZEN CONTRACT FILE (owner: architect).</b> Implementers must not edit it; see
 * docs/metal/CONTRACT.md. Every constant below mirrors a {@code #define} or a struct offset of
 * {@code mcmetal.h}: {@code MCMTL_X} becomes {@code X}, {@code mcmtl_<name>.<field>} becomes
 * {@code <NAME>_<FIELD>} and {@code sizeof(mcmtl_<name>)} becomes {@code <NAME>_SIZEOF}.
 * {@code tools/MetalSmoke.java} cross-checks all of them against the C headers.
 *
 * <p>Conventions:
 * <ul>
 *   <li>Load order: {@link Loader#load()} (dlopen through LWJGL), then the first downcall wrapper binds all
 *       downcall handles (nested holder {@code Downcalls}; a missing symbol fails its initialization),
 *       then {@link #verifyAbi()}. Constants, {@link Layouts}, {@link #statusPage}, {@link #statusLong},
 *       {@link #statusInt}, {@link #statusName}, {@link #exceptionFor}, {@link #check} and
 *       {@link #lastError()} (returns "" while the library is not loaded) work WITHOUT the library, so
 *       pure-Java tests of I3/I4/I5 need no dylib (CONTRACT.md 11.3).</li>
 *   <li>Every pointer argument and every pointer struct field is a {@code long} address (AAPCS64 passes
 *       pointers and 64-bit integers identically). Structs are written with
 *       {@code MemoryUtil.memPutInt/memPutLong(address + OFFSET, value)}, usually into
 *       {@code MemoryStack} memory; set {@code STRUCT_SIZE} fields (result structs included).</li>
 *   <li>Handles are {@code long} (+1 retained Objective-C objects, 0 = null).</li>
 *   <li>Wrappers return the raw {@code int} status; use {@link #check(int, String)} (same thread,
 *       immediately) to turn a failure into the matching Java exception.</li>
 *   <li>Only {@link #abiVersion()} and {@link #timelineCompleted(long)} are bound as critical
 *       downcalls; everything else may block and is non-critical.</li>
 * </ul>
 */
public final class MtlNative {
    private MtlNative() {
    }

    // =============================================================================================
    // Constants (mirror of #defines in mcmetal.h)
    // =============================================================================================

    public static final int ABI_VERSION = 2;
    public static final int OK = 0;
    public static final int E_INVALID_ARGUMENT = -1;
    public static final int E_OUT_OF_MEMORY = -2;
    public static final int E_UNSUPPORTED = -3;
    public static final int E_COMPILE_FAILED = -4;
    public static final int E_DEVICE_LOST = -5;
    public static final int E_TIMEOUT = -6;
    public static final int E_BAD_STREAM = -7;
    public static final int E_NO_DEVICE = -8;
    public static final int E_OBJC_EXCEPTION = -9;
    public static final int E_UNIMPLEMENTED = -10;
    public static final int E_ABI_MISMATCH = -11;
    public static final int E_SURFACE = -12;
    public static final int E_INTERNAL = -99;
    public static final int MAX_COLOR_ATTACHMENTS = 8;
    public static final int MAX_VERTEX_ATTRIBUTES = 31;
    public static final int MAX_VERTEX_BUFFERS = 16;
    public static final int VERTEX_BUFFER_INDEX_BASE = 30;
    public static final int MAX_UNIFORM_BUFFER_INDEX = 13;
    public static final int PUSH_CONSTANT_BUFFER_INDEX = 14;
    public static final int MAX_TEXTURE_BINDINGS = 16;
    public static final int MAX_PUSH_CONSTANT_BYTES = 4096;
    public static final int MAX_LABEL_BYTES = 1024;
    public static final int MAX_DEBUG_MESSAGES = 64;
    public static final int LAST_ERROR_CAPACITY = 8192;
    public static final long WAIT_FOREVER = -1L;
    public static final long TIMESTAMP_UNAVAILABLE = -1L;
    public static final int STAGE_VERTEX = 1;
    public static final int STAGE_FRAGMENT = 2;
    public static final int FAMILY_APPLE7 = 1;
    public static final int FAMILY_APPLE8 = 2;
    public static final int FAMILY_APPLE9 = 4;
    public static final int FAMILY_APPLE10 = 8;
    public static final int FAMILY_MAC2 = 16;
    public static final int FAMILY_METAL3 = 32;
    public static final int FAMILY_METAL4 = 64;
    public static final int FEATURE_COUNTER_SAMPLING_STAGE_BOUNDARY = 1;
    public static final int FEATURE_TIMESTAMP_COUNTER_SET = 2;
    public static final int FEATURE_METALFX_SPATIAL = 4;
    public static final int FEATURE_UNIFIED_MEMORY = 8;
    public static final int FEATURE_MTL4_DECODER = 16;
    public static final int STRUCT_PROBE_INFO = 1;
    public static final int STRUCT_DEVICE_DESC = 2;
    public static final int STRUCT_DEVICE_INFO = 3;
    public static final int STRUCT_STATUS_PAGE = 4;
    public static final int STRUCT_BUFFER_DESC = 5;
    public static final int STRUCT_BUFFER_RESULT = 6;
    public static final int STRUCT_TEXTURE_DESC = 7;
    public static final int STRUCT_TEXTURE_VIEW_DESC = 8;
    public static final int STRUCT_TEXEL_VIEW_DESC = 9;
    public static final int STRUCT_SAMPLER_DESC = 10;
    public static final int STRUCT_OBJECT_RESULT = 11;
    public static final int STRUCT_SHADER_DESC = 12;
    public static final int STRUCT_VERTEX_ATTRIBUTE = 13;
    public static final int STRUCT_VERTEX_LAYOUT = 14;
    public static final int STRUCT_COLOR_TARGET = 15;
    public static final int STRUCT_PIPELINE_DESC = 16;
    public static final int STRUCT_SURFACE_CONFIG = 17;
    public static final int STRUCT_SUBMIT_INFO = 18;
    public static final int STRUCT_SUBMIT_RESULT = 19;
    public static final int DEVICE_FLAG_DEBUG_LABELS = 1;
    public static final int DEVICE_FLAG_VALIDATION = 2;
    public static final int DEVICE_FLAG_MTL4 = 4;
    public static final int DEVICE_FLAG_UNTRACKED = 8;
    public static final int SAMPLER_FLAG_SUPPORT_ARGUMENT_BUFFERS = 1;
    public static final int SHADER_FLAG_PRESERVE_INVARIANCE = 1;
    public static final int SHADER_FLAG_SAFE_MATH = 2;
    public static final int PIPELINE_FLAG_HAS_DEPTH_STATE = 1;
    public static final int PIPELINE_FLAG_SUPPORT_ICB = 2;
    public static final int EMULATION_NONE = 0;
    public static final int EMULATION_TRIANGLE_FAN = 1;
    public static final int SURFACE_FLAG_DISPLAY_SYNC = 1;
    public static final int SUBMIT_FLAG_COMMIT = 1;
    public static final int SUBMIT_FLAG_FRAME_END = 2;
    public static final int SUBMIT_FLAG_ASYNC = 4;
    public static final int SUBMIT_RESULT_FLAG_COMMITTED = 1;
    public static final int SUBMIT_RESULT_FLAG_PRESENT_ENCODED = 2;
    public static final int SUBMIT_RESULT_FLAG_DRAWABLE_NIL = 4;
    public static final int SUBMIT_RESULT_FLAG_DRAWABLE_RESIZED = 8;
    public static final int SUBMIT_RESULT_FLAG_DEVICE_LOST = 16;
    public static final int SUBMIT_RESULT_FLAG_DRAWS_SKIPPED = 32;
    public static final int SUBMIT_RESULT_FLAG_ERROR_IN_INIT_STREAM = 64;

    // =============================================================================================
    // Struct sizes and field offsets (mirror of MCMTL_ASSERT_SIZE / MCMTL_ASSERT_OFFSET in mcmetal.h).
    // Array fields give the offset of element 0; element i of an int array is at +4*i, of a struct
    // array at +i*<ELEMENT>_SIZEOF.
    // =============================================================================================

    // mcmtl_probe_info (168 bytes)
    public static final long PROBE_INFO_SIZEOF = 168;
    public static final long PROBE_INFO_STRUCT_SIZE = 0;
    public static final long PROBE_INFO_FAMILY_MASK = 4;
    public static final long PROBE_INFO_OS_MAJOR = 8;
    public static final long PROBE_INFO_OS_MINOR = 12;
    public static final long PROBE_INFO_OS_PATCH = 16;
    public static final long PROBE_INFO_HAS_UNIFIED_MEMORY = 20;
    public static final long PROBE_INFO_MAX_BUFFER_LENGTH = 24;
    public static final long PROBE_INFO_RECOMMENDED_MAX_WORKING_SET_SIZE = 32;
    public static final long PROBE_INFO_DEVICE_NAME = 40;

    // mcmtl_device_desc (24 bytes)
    public static final long DEVICE_DESC_SIZEOF = 24;
    public static final long DEVICE_DESC_STRUCT_SIZE = 0;
    public static final long DEVICE_DESC_FLAGS = 4;
    public static final long DEVICE_DESC_FRAMES_IN_FLIGHT = 8;
    public static final long DEVICE_DESC_RESERVED = 12;
    public static final long DEVICE_DESC_LABEL = 16;

    // mcmtl_device_info (328 bytes)
    public static final long DEVICE_INFO_SIZEOF = 328;
    public static final long DEVICE_INFO_STRUCT_SIZE = 0;
    public static final long DEVICE_INFO_FAMILY_MASK = 4;
    public static final long DEVICE_INFO_MAX_BUFFER_LENGTH = 8;
    public static final long DEVICE_INFO_RECOMMENDED_MAX_WORKING_SET_SIZE = 16;
    public static final long DEVICE_INFO_STATUS_PAGE = 24;
    public static final long DEVICE_INFO_MAX_TEXTURE_SIZE2D = 32;
    public static final long DEVICE_INFO_MAX_COLOR_ATTACHMENTS = 36;
    public static final long DEVICE_INFO_MAX_SAMPLER_ANISOTROPY = 40;
    public static final long DEVICE_INFO_MIN_UNIFORM_OFFSET_ALIGNMENT = 44;
    public static final long DEVICE_INFO_MIN_TEXEL_BUFFER_ALIGNMENT = 48;
    public static final long DEVICE_INFO_MSL_LANGUAGE_VERSION = 52;
    public static final long DEVICE_INFO_OS_MAJOR = 56;
    public static final long DEVICE_INFO_OS_MINOR = 60;
    public static final long DEVICE_INFO_OS_PATCH = 64;
    public static final long DEVICE_INFO_FEATURE_MASK = 68;
    public static final long DEVICE_INFO_NAME = 72;
    public static final long DEVICE_INFO_DRIVER_INFO = 200;

    // mcmtl_status_page (64 bytes)
    public static final long STATUS_PAGE_SIZEOF = 64;
    public static final long STATUS_PAGE_COMPLETED_VALUE = 0;
    public static final long STATUS_PAGE_SUBMITTED_VALUE = 8;
    public static final long STATUS_PAGE_LAST_ERROR_VALUE = 16;
    public static final long STATUS_PAGE_ERROR_COUNT = 24;
    public static final long STATUS_PAGE_LAST_ERROR_CODE = 28;
    public static final long STATUS_PAGE_DEVICE_LOST = 32;
    public static final long STATUS_PAGE_MESSAGE_COUNT = 36;
    public static final long STATUS_PAGE_LAST_GPU_TIME_NS = 40;
    public static final long STATUS_PAGE_RESERVED0 = 48;
    public static final long STATUS_PAGE_RESERVED1 = 56;

    // mcmtl_buffer_desc (32 bytes)
    public static final long BUFFER_DESC_SIZEOF = 32;
    public static final long BUFFER_DESC_STRUCT_SIZE = 0;
    public static final long BUFFER_DESC_USAGE = 4;
    public static final long BUFFER_DESC_SIZE = 8;
    public static final long BUFFER_DESC_RESOURCE_OPTIONS = 16;
    public static final long BUFFER_DESC_RESERVED = 20;
    public static final long BUFFER_DESC_LABEL = 24;

    // mcmtl_buffer_result (40 bytes)
    public static final long BUFFER_RESULT_SIZEOF = 40;
    public static final long BUFFER_RESULT_STRUCT_SIZE = 0;
    public static final long BUFFER_RESULT_RESERVED = 4;
    public static final long BUFFER_RESULT_HANDLE = 8;
    public static final long BUFFER_RESULT_CONTENTS = 16;
    public static final long BUFFER_RESULT_GPU_ADDRESS = 24;
    public static final long BUFFER_RESULT_LENGTH = 32;

    // mcmtl_texture_desc (48 bytes)
    public static final long TEXTURE_DESC_SIZEOF = 48;
    public static final long TEXTURE_DESC_STRUCT_SIZE = 0;
    public static final long TEXTURE_DESC_PIXEL_FORMAT = 4;
    public static final long TEXTURE_DESC_TEXTURE_TYPE = 8;
    public static final long TEXTURE_DESC_WIDTH = 12;
    public static final long TEXTURE_DESC_HEIGHT = 16;
    public static final long TEXTURE_DESC_DEPTH = 20;
    public static final long TEXTURE_DESC_MIP_LEVELS = 24;
    public static final long TEXTURE_DESC_ARRAY_LENGTH = 28;
    public static final long TEXTURE_DESC_TEXTURE_USAGE = 32;
    public static final long TEXTURE_DESC_RESOURCE_OPTIONS = 36;
    public static final long TEXTURE_DESC_LABEL = 40;

    // mcmtl_object_result (24 bytes)
    public static final long OBJECT_RESULT_SIZEOF = 24;
    public static final long OBJECT_RESULT_STRUCT_SIZE = 0;
    public static final long OBJECT_RESULT_RESERVED = 4;
    public static final long OBJECT_RESULT_HANDLE = 8;
    public static final long OBJECT_RESULT_RESOURCE_ID = 16;

    // mcmtl_texture_view_desc (40 bytes)
    public static final long TEXTURE_VIEW_DESC_SIZEOF = 40;
    public static final long TEXTURE_VIEW_DESC_STRUCT_SIZE = 0;
    public static final long TEXTURE_VIEW_DESC_PIXEL_FORMAT = 4;
    public static final long TEXTURE_VIEW_DESC_TEXTURE_TYPE = 8;
    public static final long TEXTURE_VIEW_DESC_BASE_LEVEL = 12;
    public static final long TEXTURE_VIEW_DESC_LEVEL_COUNT = 16;
    public static final long TEXTURE_VIEW_DESC_BASE_SLICE = 20;
    public static final long TEXTURE_VIEW_DESC_SLICE_COUNT = 24;
    public static final long TEXTURE_VIEW_DESC_RESERVED = 28;
    public static final long TEXTURE_VIEW_DESC_TEXTURE = 32;

    // mcmtl_texel_view_desc (40 bytes)
    public static final long TEXEL_VIEW_DESC_SIZEOF = 40;
    public static final long TEXEL_VIEW_DESC_STRUCT_SIZE = 0;
    public static final long TEXEL_VIEW_DESC_PIXEL_FORMAT = 4;
    public static final long TEXEL_VIEW_DESC_BYTES_PER_TEXEL = 8;
    public static final long TEXEL_VIEW_DESC_RESERVED = 12;
    public static final long TEXEL_VIEW_DESC_BUFFER = 16;
    public static final long TEXEL_VIEW_DESC_OFFSET = 24;
    public static final long TEXEL_VIEW_DESC_LENGTH = 32;

    // mcmtl_sampler_desc (48 bytes)
    public static final long SAMPLER_DESC_SIZEOF = 48;
    public static final long SAMPLER_DESC_STRUCT_SIZE = 0;
    public static final long SAMPLER_DESC_S_ADDRESS_MODE = 4;
    public static final long SAMPLER_DESC_T_ADDRESS_MODE = 8;
    public static final long SAMPLER_DESC_R_ADDRESS_MODE = 12;
    public static final long SAMPLER_DESC_MIN_FILTER = 16;
    public static final long SAMPLER_DESC_MAG_FILTER = 20;
    public static final long SAMPLER_DESC_MIP_FILTER = 24;
    public static final long SAMPLER_DESC_MAX_ANISOTROPY = 28;
    public static final long SAMPLER_DESC_LOD_MIN_CLAMP = 32;
    public static final long SAMPLER_DESC_LOD_MAX_CLAMP = 36;
    public static final long SAMPLER_DESC_COMPARE_FUNCTION = 40;
    public static final long SAMPLER_DESC_FLAGS = 44;

    // mcmtl_shader_desc (48 bytes)
    public static final long SHADER_DESC_SIZEOF = 48;
    public static final long SHADER_DESC_STRUCT_SIZE = 0;
    public static final long SHADER_DESC_STAGE = 4;
    public static final long SHADER_DESC_LANGUAGE_VERSION = 8;
    public static final long SHADER_DESC_FLAGS = 12;
    public static final long SHADER_DESC_SOURCE = 16;
    public static final long SHADER_DESC_SOURCE_LENGTH = 24;
    public static final long SHADER_DESC_ENTRY_POINT = 32;
    public static final long SHADER_DESC_LABEL = 40;

    // mcmtl_vertex_attribute (16 bytes)
    public static final long VERTEX_ATTRIBUTE_SIZEOF = 16;
    public static final long VERTEX_ATTRIBUTE_LOCATION = 0;
    public static final long VERTEX_ATTRIBUTE_FORMAT = 4;
    public static final long VERTEX_ATTRIBUTE_OFFSET = 8;
    public static final long VERTEX_ATTRIBUTE_BUFFER_INDEX = 12;

    // mcmtl_vertex_layout (16 bytes)
    public static final long VERTEX_LAYOUT_SIZEOF = 16;
    public static final long VERTEX_LAYOUT_BUFFER_INDEX = 0;
    public static final long VERTEX_LAYOUT_STRIDE = 4;
    public static final long VERTEX_LAYOUT_STEP_FUNCTION = 8;
    public static final long VERTEX_LAYOUT_STEP_RATE = 12;

    // mcmtl_color_target (40 bytes)
    public static final long COLOR_TARGET_SIZEOF = 40;
    public static final long COLOR_TARGET_PIXEL_FORMAT = 0;
    public static final long COLOR_TARGET_WRITE_MASK = 4;
    public static final long COLOR_TARGET_BLENDING_ENABLED = 8;
    public static final long COLOR_TARGET_RGB_BLEND_OPERATION = 12;
    public static final long COLOR_TARGET_ALPHA_BLEND_OPERATION = 16;
    public static final long COLOR_TARGET_SOURCE_RGB_BLEND_FACTOR = 20;
    public static final long COLOR_TARGET_DESTINATION_RGB_BLEND_FACTOR = 24;
    public static final long COLOR_TARGET_SOURCE_ALPHA_BLEND_FACTOR = 28;
    public static final long COLOR_TARGET_DESTINATION_ALPHA_BLEND_FACTOR = 32;
    public static final long COLOR_TARGET_RESERVED = 36;

    // mcmtl_pipeline_desc (1248 bytes)
    public static final long PIPELINE_DESC_SIZEOF = 1248;
    public static final long PIPELINE_DESC_STRUCT_SIZE = 0;
    public static final long PIPELINE_DESC_FLAGS = 4;
    public static final long PIPELINE_DESC_VERTEX_FUNCTION = 8;
    public static final long PIPELINE_DESC_FRAGMENT_FUNCTION = 16;
    public static final long PIPELINE_DESC_LABEL = 24;
    public static final long PIPELINE_DESC_PRIMITIVE_TYPE = 32;
    public static final long PIPELINE_DESC_TOPOLOGY_CLASS = 36;
    public static final long PIPELINE_DESC_EMULATION = 40;
    public static final long PIPELINE_DESC_CULL_MODE = 44;
    public static final long PIPELINE_DESC_FILL_MODE = 48;
    public static final long PIPELINE_DESC_FRONT_FACING = 52;
    public static final long PIPELINE_DESC_DEPTH_COMPARE = 56;
    public static final long PIPELINE_DESC_DEPTH_WRITE_ENABLED = 60;
    public static final long PIPELINE_DESC_DEPTH_BIAS_CONSTANT = 64;
    public static final long PIPELINE_DESC_DEPTH_BIAS_SLOPE_SCALE = 68;
    public static final long PIPELINE_DESC_DEPTH_BIAS_CLAMP = 72;
    public static final long PIPELINE_DESC_PUSH_CONSTANT_SIZE = 76;
    public static final long PIPELINE_DESC_PUSH_CONSTANT_STAGES = 80;
    public static final long PIPELINE_DESC_COLOR_TARGET_COUNT = 84;
    public static final long PIPELINE_DESC_COLOR_TARGETS = 88;
    public static final long PIPELINE_DESC_ATTRIBUTE_COUNT = 408;
    public static final long PIPELINE_DESC_LAYOUT_COUNT = 412;
    public static final long PIPELINE_DESC_ATTRIBUTES = 416;
    public static final long PIPELINE_DESC_LAYOUTS = 912;
    public static final long PIPELINE_DESC_BUFFER_MASK = 1168;
    public static final long PIPELINE_DESC_TEXTURE_MASK = 1176;
    public static final long PIPELINE_DESC_SAMPLER_MASK = 1184;
    public static final long PIPELINE_DESC_TEXTURE_CUBE_MASK = 1192;
    public static final long PIPELINE_DESC_TEXEL_BUFFER_MASK = 1200;
    public static final long PIPELINE_DESC_REQUIRED_VERTEX_BUFFER_MASK = 1208;
    public static final long PIPELINE_DESC_PRECOMPILE_DEPTH_FORMAT_COUNT = 1212;
    public static final long PIPELINE_DESC_PRECOMPILE_DEPTH_FORMATS = 1216;
    public static final long PIPELINE_DESC_RESERVED = 1232;

    // mcmtl_surface_config (24 bytes)
    public static final long SURFACE_CONFIG_SIZEOF = 24;
    public static final long SURFACE_CONFIG_STRUCT_SIZE = 0;
    public static final long SURFACE_CONFIG_WIDTH = 4;
    public static final long SURFACE_CONFIG_HEIGHT = 8;
    public static final long SURFACE_CONFIG_FLAGS = 12;
    public static final long SURFACE_CONFIG_MAXIMUM_DRAWABLE_COUNT = 16;
    public static final long SURFACE_CONFIG_RESERVED = 20;

    // mcmtl_submit_info (64 bytes)
    public static final long SUBMIT_INFO_SIZEOF = 64;
    public static final long SUBMIT_INFO_STRUCT_SIZE = 0;
    public static final long SUBMIT_INFO_FLAGS = 4;
    public static final long SUBMIT_INFO_INIT_STREAM = 8;
    public static final long SUBMIT_INFO_INIT_STREAM_LENGTH = 16;
    public static final long SUBMIT_INFO_STREAM = 24;
    public static final long SUBMIT_INFO_STREAM_LENGTH = 32;
    public static final long SUBMIT_INFO_SIGNAL_VALUE = 40;
    public static final long SUBMIT_INFO_LABEL = 48;
    public static final long SUBMIT_INFO_RESERVED = 56;

    // mcmtl_submit_result (64 bytes)
    public static final long SUBMIT_RESULT_SIZEOF = 64;
    public static final long SUBMIT_RESULT_STRUCT_SIZE = 0;
    public static final long SUBMIT_RESULT_FLAGS = 4;
    public static final long SUBMIT_RESULT_COMPLETED_VALUE = 8;
    public static final long SUBMIT_RESULT_ERROR_OFFSET = 16;
    public static final long SUBMIT_RESULT_RECORDS_DECODED = 24;
    public static final long SUBMIT_RESULT_DRAWS_ENCODED = 28;
    public static final long SUBMIT_RESULT_DRAWS_SKIPPED = 32;
    public static final long SUBMIT_RESULT_ENCODERS_CREATED = 36;
    public static final long SUBMIT_RESULT_DRAWABLE_WIDTH = 40;
    public static final long SUBMIT_RESULT_DRAWABLE_HEIGHT = 44;
    public static final long SUBMIT_RESULT_CPU_NANOS = 48;
    public static final long SUBMIT_RESULT_RESERVED = 56;

    /** {@code StructLayout}s of every mcmetal.h struct (documentation and verification; hot paths use the offsets). */
    public static final class Layouts {
        private Layouts() {
        }

        /** {@code mcmtl_probe_info} (168 bytes). */
        public static final StructLayout PROBE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("structSize"),
            JAVA_INT.withName("familyMask"),
            JAVA_INT.withName("osMajor"),
            JAVA_INT.withName("osMinor"),
            JAVA_INT.withName("osPatch"),
            JAVA_INT.withName("hasUnifiedMemory"),
            JAVA_LONG.withName("maxBufferLength"),
            JAVA_LONG.withName("recommendedMaxWorkingSetSize"),
            MemoryLayout.sequenceLayout(128, JAVA_BYTE).withName("deviceName")).withName("mcmtl_probe_info");

        /** {@code mcmtl_device_desc} (24 bytes). */
        public static final StructLayout DEVICE_DESC = MemoryLayout.structLayout(
            JAVA_INT.withName("structSize"),
            JAVA_INT.withName("flags"),
            JAVA_INT.withName("framesInFlight"),
            JAVA_INT.withName("reserved"),
            JAVA_LONG.withName("label")).withName("mcmtl_device_desc");

        /** {@code mcmtl_device_info} (328 bytes). */
        public static final StructLayout DEVICE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("structSize"),
            JAVA_INT.withName("familyMask"),
            JAVA_LONG.withName("maxBufferLength"),
            JAVA_LONG.withName("recommendedMaxWorkingSetSize"),
            JAVA_LONG.withName("statusPage"),
            JAVA_INT.withName("maxTextureSize2D"),
            JAVA_INT.withName("maxColorAttachments"),
            JAVA_INT.withName("maxSamplerAnisotropy"),
            JAVA_INT.withName("minUniformOffsetAlignment"),
            JAVA_INT.withName("minTexelBufferAlignment"),
            JAVA_INT.withName("mslLanguageVersion"),
            JAVA_INT.withName("osMajor"),
            JAVA_INT.withName("osMinor"),
            JAVA_INT.withName("osPatch"),
            JAVA_INT.withName("featureMask"),
            MemoryLayout.sequenceLayout(128, JAVA_BYTE).withName("name"),
            MemoryLayout.sequenceLayout(128, JAVA_BYTE).withName("driverInfo")).withName("mcmtl_device_info");

        /** {@code mcmtl_status_page} (64 bytes). */
        public static final StructLayout STATUS_PAGE = MemoryLayout.structLayout(
            JAVA_LONG.withName("completedValue"),
            JAVA_LONG.withName("submittedValue"),
            JAVA_LONG.withName("lastErrorValue"),
            JAVA_INT.withName("errorCount"),
            JAVA_INT.withName("lastErrorCode"),
            JAVA_INT.withName("deviceLost"),
            JAVA_INT.withName("messageCount"),
            JAVA_LONG.withName("lastGpuTimeNs"),
            JAVA_LONG.withName("reserved0"),
            JAVA_LONG.withName("reserved1")).withName("mcmtl_status_page");

        /** {@code mcmtl_buffer_desc} (32 bytes). */
        public static final StructLayout BUFFER_DESC = MemoryLayout.structLayout(
            JAVA_INT.withName("structSize"),
            JAVA_INT.withName("usage"),
            JAVA_LONG.withName("size"),
            JAVA_INT.withName("resourceOptions"),
            JAVA_INT.withName("reserved"),
            JAVA_LONG.withName("label")).withName("mcmtl_buffer_desc");

        /** {@code mcmtl_buffer_result} (40 bytes). */
        public static final StructLayout BUFFER_RESULT = MemoryLayout.structLayout(
            JAVA_INT.withName("structSize"),
            JAVA_INT.withName("reserved"),
            JAVA_LONG.withName("handle"),
            JAVA_LONG.withName("contents"),
            JAVA_LONG.withName("gpuAddress"),
            JAVA_LONG.withName("length")).withName("mcmtl_buffer_result");

        /** {@code mcmtl_texture_desc} (48 bytes). */
        public static final StructLayout TEXTURE_DESC = MemoryLayout.structLayout(
            JAVA_INT.withName("structSize"),
            JAVA_INT.withName("pixelFormat"),
            JAVA_INT.withName("textureType"),
            JAVA_INT.withName("width"),
            JAVA_INT.withName("height"),
            JAVA_INT.withName("depth"),
            JAVA_INT.withName("mipLevels"),
            JAVA_INT.withName("arrayLength"),
            JAVA_INT.withName("textureUsage"),
            JAVA_INT.withName("resourceOptions"),
            JAVA_LONG.withName("label")).withName("mcmtl_texture_desc");

        /** {@code mcmtl_object_result} (24 bytes). */
        public static final StructLayout OBJECT_RESULT = MemoryLayout.structLayout(
            JAVA_INT.withName("structSize"),
            JAVA_INT.withName("reserved"),
            JAVA_LONG.withName("handle"),
            JAVA_LONG.withName("resourceId")).withName("mcmtl_object_result");

        /** {@code mcmtl_texture_view_desc} (40 bytes). */
        public static final StructLayout TEXTURE_VIEW_DESC = MemoryLayout.structLayout(
            JAVA_INT.withName("structSize"),
            JAVA_INT.withName("pixelFormat"),
            JAVA_INT.withName("textureType"),
            JAVA_INT.withName("baseLevel"),
            JAVA_INT.withName("levelCount"),
            JAVA_INT.withName("baseSlice"),
            JAVA_INT.withName("sliceCount"),
            JAVA_INT.withName("reserved"),
            JAVA_LONG.withName("texture")).withName("mcmtl_texture_view_desc");

        /** {@code mcmtl_texel_view_desc} (40 bytes). */
        public static final StructLayout TEXEL_VIEW_DESC = MemoryLayout.structLayout(
            JAVA_INT.withName("structSize"),
            JAVA_INT.withName("pixelFormat"),
            JAVA_INT.withName("bytesPerTexel"),
            JAVA_INT.withName("reserved"),
            JAVA_LONG.withName("buffer"),
            JAVA_LONG.withName("offset"),
            JAVA_LONG.withName("length")).withName("mcmtl_texel_view_desc");

        /** {@code mcmtl_sampler_desc} (48 bytes). */
        public static final StructLayout SAMPLER_DESC = MemoryLayout.structLayout(
            JAVA_INT.withName("structSize"),
            JAVA_INT.withName("sAddressMode"),
            JAVA_INT.withName("tAddressMode"),
            JAVA_INT.withName("rAddressMode"),
            JAVA_INT.withName("minFilter"),
            JAVA_INT.withName("magFilter"),
            JAVA_INT.withName("mipFilter"),
            JAVA_INT.withName("maxAnisotropy"),
            JAVA_FLOAT.withName("lodMinClamp"),
            JAVA_FLOAT.withName("lodMaxClamp"),
            JAVA_INT.withName("compareFunction"),
            JAVA_INT.withName("flags")).withName("mcmtl_sampler_desc");

        /** {@code mcmtl_shader_desc} (48 bytes). */
        public static final StructLayout SHADER_DESC = MemoryLayout.structLayout(
            JAVA_INT.withName("structSize"),
            JAVA_INT.withName("stage"),
            JAVA_INT.withName("languageVersion"),
            JAVA_INT.withName("flags"),
            JAVA_LONG.withName("source"),
            JAVA_LONG.withName("sourceLength"),
            JAVA_LONG.withName("entryPoint"),
            JAVA_LONG.withName("label")).withName("mcmtl_shader_desc");

        /** {@code mcmtl_vertex_attribute} (16 bytes). */
        public static final StructLayout VERTEX_ATTRIBUTE = MemoryLayout.structLayout(
            JAVA_INT.withName("location"),
            JAVA_INT.withName("format"),
            JAVA_INT.withName("offset"),
            JAVA_INT.withName("bufferIndex")).withName("mcmtl_vertex_attribute");

        /** {@code mcmtl_vertex_layout} (16 bytes). */
        public static final StructLayout VERTEX_LAYOUT = MemoryLayout.structLayout(
            JAVA_INT.withName("bufferIndex"),
            JAVA_INT.withName("stride"),
            JAVA_INT.withName("stepFunction"),
            JAVA_INT.withName("stepRate")).withName("mcmtl_vertex_layout");

        /** {@code mcmtl_color_target} (40 bytes). */
        public static final StructLayout COLOR_TARGET = MemoryLayout.structLayout(
            JAVA_INT.withName("pixelFormat"),
            JAVA_INT.withName("writeMask"),
            JAVA_INT.withName("blendingEnabled"),
            JAVA_INT.withName("rgbBlendOperation"),
            JAVA_INT.withName("alphaBlendOperation"),
            JAVA_INT.withName("sourceRGBBlendFactor"),
            JAVA_INT.withName("destinationRGBBlendFactor"),
            JAVA_INT.withName("sourceAlphaBlendFactor"),
            JAVA_INT.withName("destinationAlphaBlendFactor"),
            JAVA_INT.withName("reserved")).withName("mcmtl_color_target");

        /** {@code mcmtl_pipeline_desc} (1248 bytes). */
        public static final StructLayout PIPELINE_DESC = MemoryLayout.structLayout(
            JAVA_INT.withName("structSize"),
            JAVA_INT.withName("flags"),
            JAVA_LONG.withName("vertexFunction"),
            JAVA_LONG.withName("fragmentFunction"),
            JAVA_LONG.withName("label"),
            JAVA_INT.withName("primitiveType"),
            JAVA_INT.withName("topologyClass"),
            JAVA_INT.withName("emulation"),
            JAVA_INT.withName("cullMode"),
            JAVA_INT.withName("fillMode"),
            JAVA_INT.withName("frontFacing"),
            JAVA_INT.withName("depthCompare"),
            JAVA_INT.withName("depthWriteEnabled"),
            JAVA_FLOAT.withName("depthBiasConstant"),
            JAVA_FLOAT.withName("depthBiasSlopeScale"),
            JAVA_FLOAT.withName("depthBiasClamp"),
            JAVA_INT.withName("pushConstantSize"),
            JAVA_INT.withName("pushConstantStages"),
            JAVA_INT.withName("colorTargetCount"),
            MemoryLayout.sequenceLayout(8, COLOR_TARGET).withName("colorTargets"),
            JAVA_INT.withName("attributeCount"),
            JAVA_INT.withName("layoutCount"),
            MemoryLayout.sequenceLayout(31, VERTEX_ATTRIBUTE).withName("attributes"),
            MemoryLayout.sequenceLayout(16, VERTEX_LAYOUT).withName("layouts"),
            MemoryLayout.sequenceLayout(2, JAVA_INT).withName("bufferMask"),
            MemoryLayout.sequenceLayout(2, JAVA_INT).withName("textureMask"),
            MemoryLayout.sequenceLayout(2, JAVA_INT).withName("samplerMask"),
            MemoryLayout.sequenceLayout(2, JAVA_INT).withName("textureCubeMask"),
            MemoryLayout.sequenceLayout(2, JAVA_INT).withName("texelBufferMask"),
            JAVA_INT.withName("requiredVertexBufferMask"),
            JAVA_INT.withName("precompileDepthFormatCount"),
            MemoryLayout.sequenceLayout(4, JAVA_INT).withName("precompileDepthFormats"),
            MemoryLayout.sequenceLayout(4, JAVA_INT).withName("reserved")).withName("mcmtl_pipeline_desc");

        /** {@code mcmtl_surface_config} (24 bytes). */
        public static final StructLayout SURFACE_CONFIG = MemoryLayout.structLayout(
            JAVA_INT.withName("structSize"),
            JAVA_INT.withName("width"),
            JAVA_INT.withName("height"),
            JAVA_INT.withName("flags"),
            JAVA_INT.withName("maximumDrawableCount"),
            JAVA_INT.withName("reserved")).withName("mcmtl_surface_config");

        /** {@code mcmtl_submit_info} (64 bytes). */
        public static final StructLayout SUBMIT_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("structSize"),
            JAVA_INT.withName("flags"),
            JAVA_LONG.withName("initStream"),
            JAVA_LONG.withName("initStreamLength"),
            JAVA_LONG.withName("stream"),
            JAVA_LONG.withName("streamLength"),
            JAVA_LONG.withName("signalValue"),
            JAVA_LONG.withName("label"),
            JAVA_LONG.withName("reserved")).withName("mcmtl_submit_info");

        /** {@code mcmtl_submit_result} (64 bytes). */
        public static final StructLayout SUBMIT_RESULT = MemoryLayout.structLayout(
            JAVA_INT.withName("structSize"),
            JAVA_INT.withName("flags"),
            JAVA_LONG.withName("completedValue"),
            JAVA_LONG.withName("errorOffset"),
            JAVA_INT.withName("recordsDecoded"),
            JAVA_INT.withName("drawsEncoded"),
            JAVA_INT.withName("drawsSkipped"),
            JAVA_INT.withName("encodersCreated"),
            JAVA_INT.withName("drawableWidth"),
            JAVA_INT.withName("drawableHeight"),
            JAVA_LONG.withName("cpuNanos"),
            JAVA_LONG.withName("reserved")).withName("mcmtl_submit_result");

    }

    // =============================================================================================
    // Library loading
    // =============================================================================================

    /**
     * Loads {@code libmcmetal.dylib}. Packaged in the client jar at
     * {@code macos/arm64/com/mojang/renderpearl/backend/metal/libmcmetal.dylib} (plus
     * {@code META-INF/.../libmcmetal.dylib.sha1}) and loaded with LWJGL's
     * {@code Library.loadNative(MtlNative.class, MODULE, "mcmetal", true)}, which extracts it into
     * {@code org.lwjgl.system.SharedLibraryExtractPath} (the launcher's natives directory) and re-extracts
     * whenever the CRC differs. For development, {@code -Dmcmetal.library=/abs/path/libmcmetal.dylib}
     * loads a file directly. Nested class: loading does not initialize {@link MtlNative}.
     */
    public static final class Loader {
        public static final String MODULE = "com.mojang.renderpearl.backend.metal";
        public static final String LIBRARY_NAME = "mcmetal";
        public static final String LIBRARY_PATH_PROPERTY = "mcmetal.library";
        public static final String RESOURCE_PATH = "macos/arm64/com/mojang/renderpearl/backend/metal/libmcmetal.dylib";

        private static volatile @Nullable SharedLibrary library;

        private Loader() {
        }

        /**
         * Loads the library once (idempotent, thread-safe). Throws {@link UnsatisfiedLinkError} (or another
         * {@link Error}/{@link RuntimeException}) when it cannot be found or dlopen fails; callers map that
         * to {@code BackendCreationException}.
         */
        public static synchronized SharedLibrary load() {
            SharedLibrary loaded = library;
            if (loaded == null) {
                String override = System.getProperty(LIBRARY_PATH_PROPERTY);
                if (override != null && !override.isBlank()) {
                    loaded = Library.loadNative(MtlNative.class, MODULE, Path.of(override).toAbsolutePath().toString(), false);
                } else {
                    loaded = Library.loadNative(MtlNative.class, MODULE, LIBRARY_NAME, true);
                }
                library = loaded;
            }
            return loaded;
        }

        public static boolean isLoaded() {
            return library != null;
        }

        /** Absolute path of the loaded dylib, or null if not loaded. */
        public static @Nullable String path() {
            SharedLibrary loaded = library;
            return loaded == null ? null : loaded.getPath();
        }

        static long address(String symbol) {
            SharedLibrary loaded = library;
            if (loaded == null) {
                throw new IllegalStateException("libmcmetal is not loaded; call MtlNative.Loader.load() first");
            }
            long address = loaded.getFunctionAddress(symbol);
            if (address == 0L) {
                throw new UnsatisfiedLinkError("libmcmetal: missing symbol " + symbol);
            }
            return address;
        }
    }

    // =============================================================================================
    // Downcall handles: bound when the nested holder initializes, i.e. at the first downcall wrapper
    // call (requires Loader.load()). Keeping them out of MtlNative.<clinit> lets the constants and pure
    // helpers be used without the library.
    // =============================================================================================

    static final class Downcalls {
        private Downcalls() {
        }

        private static final Linker LINKER = Linker.nativeLinker();

        private static MethodHandle bind(String symbol, FunctionDescriptor descriptor) {
            return LINKER.downcallHandle(MemorySegment.ofAddress(Loader.address(symbol)), descriptor);
        }

        private static MethodHandle bindCritical(String symbol, FunctionDescriptor descriptor) {
            return LINKER.downcallHandle(MemorySegment.ofAddress(Loader.address(symbol)), descriptor, Linker.Option.critical(false));
        }

        // mcmetal_common.m
        static final MethodHandle MH_ABI_VERSION = bindCritical("mcmtl_abi_version", FunctionDescriptor.of(JAVA_INT));
        static final MethodHandle MH_ABI_STRUCT_SIZE = bind("mcmtl_abi_struct_size", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
        static final MethodHandle MH_LAST_ERROR = bind("mcmtl_last_error", FunctionDescriptor.of(JAVA_LONG));
        static final MethodHandle MH_PROBE = bind("mcmtl_probe", FunctionDescriptor.of(JAVA_INT, JAVA_LONG));
        static final MethodHandle MH_DEVICE_COPY_MESSAGES = bind(
            "mcmtl_device_copy_messages", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG)
        );
        // mcmetal_device.m
        static final MethodHandle MH_DEVICE_CREATE = bind("mcmtl_device_create", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG));
        static final MethodHandle MH_DEVICE_DESTROY = bind("mcmtl_device_destroy", FunctionDescriptor.of(JAVA_INT, JAVA_LONG));
        static final MethodHandle MH_DEVICE_GET_INFO = bind("mcmtl_device_get_info", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG));
        static final MethodHandle MH_TIMELINE_COMPLETED = bindCritical("mcmtl_timeline_completed", FunctionDescriptor.of(JAVA_LONG, JAVA_LONG));
        static final MethodHandle MH_TIMELINE_WAIT = bind("mcmtl_timeline_wait", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG));
        static final MethodHandle MH_DEVICE_SAMPLE_TIMESTAMPS = bind(
            "mcmtl_device_sample_timestamps", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG)
        );
        // mcmetal_resources.m
        static final MethodHandle MH_BUFFER_CREATE = bind("mcmtl_buffer_create", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG));
        static final MethodHandle MH_TEXTURE_CREATE = bind("mcmtl_texture_create", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG));
        static final MethodHandle MH_TEXTURE_VIEW_CREATE = bind(
            "mcmtl_texture_view_create", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG)
        );
        static final MethodHandle MH_TEXEL_VIEW_CREATE = bind("mcmtl_texel_view_create", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG));
        static final MethodHandle MH_SAMPLER_CREATE = bind("mcmtl_sampler_create", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG));
        static final MethodHandle MH_QUERY_POOL_CREATE = bind("mcmtl_query_pool_create", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_LONG));
        static final MethodHandle MH_QUERY_POOL_RESOLVE = bind(
            "mcmtl_query_pool_resolve", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_LONG)
        );
        static final MethodHandle MH_RELEASE = bind("mcmtl_release", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG));
        static final MethodHandle MH_RELEASE_BATCH = bind("mcmtl_release_batch", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT));
        // mcmetal_pipeline.m
        static final MethodHandle MH_SHADER_CREATE = bind("mcmtl_shader_create", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG));
        static final MethodHandle MH_PIPELINE_CREATE = bind("mcmtl_pipeline_create", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG));
        // mcmetal_surface.m
        static final MethodHandle MH_SURFACE_CREATE = bind("mcmtl_surface_create", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG));
        static final MethodHandle MH_SURFACE_CONFIGURE = bind("mcmtl_surface_configure", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG));
        static final MethodHandle MH_SURFACE_PRESENT = bind("mcmtl_surface_present", FunctionDescriptor.of(JAVA_INT, JAVA_LONG));
        static final MethodHandle MH_SURFACE_DESTROY = bind("mcmtl_surface_destroy", FunctionDescriptor.of(JAVA_INT, JAVA_LONG));
        // mcmetal_decoder.m
        static final MethodHandle MH_SUBMIT = bind("mcmtl_submit", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_LONG));
    }

    // =============================================================================================
    // Typed wrappers (one per export; arguments in C order; pointers/handles as long)
    // =============================================================================================

    /** {@code uint32_t mcmtl_abi_version(void)} (critical). */
    public static int abiVersion() {
        try {
            return (int)Downcalls.MH_ABI_VERSION.invokeExact();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code uint32_t mcmtl_abi_struct_size(uint32_t structId)}. */
    public static int abiStructSize(int structId) {
        try {
            return (int)Downcalls.MH_ABI_STRUCT_SIZE.invokeExact(structId);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code const char *mcmtl_last_error(void)}: address of the calling thread's error string. */
    public static long lastErrorAddress() {
        try {
            return (long)Downcalls.MH_LAST_ERROR.invokeExact();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code int32_t mcmtl_probe(mcmtl_probe_info *outInfo)}. */
    public static int probe(long outInfo) {
        try {
            return (int)Downcalls.MH_PROBE.invokeExact(outInfo);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code int32_t mcmtl_device_copy_messages(mcmtl_handle device, char *buffer, uint32_t capacity, uint32_t *outLength)}. */
    public static int deviceCopyMessages(long device, long buffer, int capacity, long outLength) {
        try {
            return (int)Downcalls.MH_DEVICE_COPY_MESSAGES.invokeExact(device, buffer, capacity, outLength);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code int32_t mcmtl_device_create(const mcmtl_device_desc *desc, mcmtl_handle *outDevice)}. */
    public static int deviceCreate(long desc, long outDevice) {
        try {
            return (int)Downcalls.MH_DEVICE_CREATE.invokeExact(desc, outDevice);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code int32_t mcmtl_device_destroy(mcmtl_handle device)}. */
    public static int deviceDestroy(long device) {
        try {
            return (int)Downcalls.MH_DEVICE_DESTROY.invokeExact(device);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code int32_t mcmtl_device_get_info(mcmtl_handle device, mcmtl_device_info *outInfo)}. */
    public static int deviceGetInfo(long device, long outInfo) {
        try {
            return (int)Downcalls.MH_DEVICE_GET_INFO.invokeExact(device, outInfo);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code uint64_t mcmtl_timeline_completed(mcmtl_handle device)} (critical). */
    public static long timelineCompleted(long device) {
        try {
            return (long)Downcalls.MH_TIMELINE_COMPLETED.invokeExact(device);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code int32_t mcmtl_timeline_wait(mcmtl_handle device, uint64_t value, uint64_t timeoutNs)}; {@link #WAIT_FOREVER} = -1. */
    public static int timelineWait(long device, long value, long timeoutNs) {
        try {
            return (int)Downcalls.MH_TIMELINE_WAIT.invokeExact(device, value, timeoutNs);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code int32_t mcmtl_device_sample_timestamps(mcmtl_handle device, uint64_t *outCpuNs, uint64_t *outGpuNs)}. */
    public static int deviceSampleTimestamps(long device, long outCpuNs, long outGpuNs) {
        try {
            return (int)Downcalls.MH_DEVICE_SAMPLE_TIMESTAMPS.invokeExact(device, outCpuNs, outGpuNs);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code int32_t mcmtl_buffer_create(mcmtl_handle device, const mcmtl_buffer_desc *desc, mcmtl_buffer_result *out)}. */
    public static int bufferCreate(long device, long desc, long out) {
        try {
            return (int)Downcalls.MH_BUFFER_CREATE.invokeExact(device, desc, out);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code int32_t mcmtl_texture_create(mcmtl_handle device, const mcmtl_texture_desc *desc, mcmtl_object_result *out)}. */
    public static int textureCreate(long device, long desc, long out) {
        try {
            return (int)Downcalls.MH_TEXTURE_CREATE.invokeExact(device, desc, out);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code int32_t mcmtl_texture_view_create(mcmtl_handle device, const mcmtl_texture_view_desc *desc, mcmtl_object_result *out)}. */
    public static int textureViewCreate(long device, long desc, long out) {
        try {
            return (int)Downcalls.MH_TEXTURE_VIEW_CREATE.invokeExact(device, desc, out);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code int32_t mcmtl_texel_view_create(mcmtl_handle device, const mcmtl_texel_view_desc *desc, mcmtl_object_result *out)}. */
    public static int texelViewCreate(long device, long desc, long out) {
        try {
            return (int)Downcalls.MH_TEXEL_VIEW_CREATE.invokeExact(device, desc, out);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code int32_t mcmtl_sampler_create(mcmtl_handle device, const mcmtl_sampler_desc *desc, mcmtl_object_result *out)}. */
    public static int samplerCreate(long device, long desc, long out) {
        try {
            return (int)Downcalls.MH_SAMPLER_CREATE.invokeExact(device, desc, out);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code int32_t mcmtl_query_pool_create(mcmtl_handle device, uint32_t count, mcmtl_handle *outPool)}. */
    public static int queryPoolCreate(long device, int count, long outPool) {
        try {
            return (int)Downcalls.MH_QUERY_POOL_CREATE.invokeExact(device, count, outPool);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code int32_t mcmtl_query_pool_resolve(mcmtl_handle device, mcmtl_handle pool, uint32_t first, uint32_t count, uint64_t *outValuesNs)}. */
    public static int queryPoolResolve(long device, long pool, int first, int count, long outValuesNs) {
        try {
            return (int)Downcalls.MH_QUERY_POOL_RESOLVE.invokeExact(device, pool, first, count, outValuesNs);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code int32_t mcmtl_release(mcmtl_handle device, mcmtl_handle object)}. */
    public static int release(long device, long object) {
        try {
            return (int)Downcalls.MH_RELEASE.invokeExact(device, object);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code int32_t mcmtl_release_batch(mcmtl_handle device, const mcmtl_handle *objects, uint32_t count)}. */
    public static int releaseBatch(long device, long objects, int count) {
        try {
            return (int)Downcalls.MH_RELEASE_BATCH.invokeExact(device, objects, count);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code int32_t mcmtl_shader_create(mcmtl_handle device, const mcmtl_shader_desc *desc, mcmtl_handle *outFunction)}. */
    public static int shaderCreate(long device, long desc, long outFunction) {
        try {
            return (int)Downcalls.MH_SHADER_CREATE.invokeExact(device, desc, outFunction);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code int32_t mcmtl_pipeline_create(mcmtl_handle device, const mcmtl_pipeline_desc *desc, mcmtl_handle *outPipeline)}. */
    public static int pipelineCreate(long device, long desc, long outPipeline) {
        try {
            return (int)Downcalls.MH_PIPELINE_CREATE.invokeExact(device, desc, outPipeline);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code int32_t mcmtl_surface_create(mcmtl_handle device, void *caMetalLayer, mcmtl_handle *outSurface)}. */
    public static int surfaceCreate(long device, long caMetalLayer, long outSurface) {
        try {
            return (int)Downcalls.MH_SURFACE_CREATE.invokeExact(device, caMetalLayer, outSurface);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code int32_t mcmtl_surface_configure(mcmtl_handle surface, const mcmtl_surface_config *config)}. */
    public static int surfaceConfigure(long surface, long config) {
        try {
            return (int)Downcalls.MH_SURFACE_CONFIGURE.invokeExact(surface, config);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code int32_t mcmtl_surface_present(mcmtl_handle surface)}. */
    public static int surfacePresent(long surface) {
        try {
            return (int)Downcalls.MH_SURFACE_PRESENT.invokeExact(surface);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /** {@code int32_t mcmtl_surface_destroy(mcmtl_handle surface)}. */
    public static int surfaceDestroy(long surface) {
        try {
            return (int)Downcalls.MH_SURFACE_DESTROY.invokeExact(surface);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    /**
     * {@code int32_t mcmtl_submit(mcmtl_handle device, const mcmtl_submit_info *info, mcmtl_submit_result *outResult)}.
     * With SUBMIT_FLAG_COMMIT and non-null device/info the signal value is consumed on every return path
     * (CONTRACT.md 4.2): call {@code timeline.onCommitted(seq)} whatever the status.
     */
    public static int submit(long device, long info, long outResult) {
        try {
            return (int)Downcalls.MH_SUBMIT.invokeExact(device, info, outResult);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // =============================================================================================
    // Helpers
    // =============================================================================================

    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (t instanceof Error error) {
            throw error;
        }
        return new IllegalStateException("libmcmetal downcall failed", t);
    }

    /** The calling thread's last native error message ("" if none). Call right after the failing downcall. */
    public static String lastError() {
        if (!Loader.isLoaded()) {
            return "";
        }
        long address = lastErrorAddress();
        if (address == 0L) {
            return "";
        }
        String message = MemoryUtil.memUTF8Safe(address);
        return message == null ? "" : message;
    }

    /** Symbolic name of a status code ("MCMTL_OK", "MCMTL_E_TIMEOUT", ...). */
    public static String statusName(int status) {
        return switch (status) {
            case OK -> "MCMTL_OK";
            case E_INVALID_ARGUMENT -> "MCMTL_E_INVALID_ARGUMENT";
            case E_OUT_OF_MEMORY -> "MCMTL_E_OUT_OF_MEMORY";
            case E_UNSUPPORTED -> "MCMTL_E_UNSUPPORTED";
            case E_COMPILE_FAILED -> "MCMTL_E_COMPILE_FAILED";
            case E_DEVICE_LOST -> "MCMTL_E_DEVICE_LOST";
            case E_TIMEOUT -> "MCMTL_E_TIMEOUT";
            case E_BAD_STREAM -> "MCMTL_E_BAD_STREAM";
            case E_NO_DEVICE -> "MCMTL_E_NO_DEVICE";
            case E_OBJC_EXCEPTION -> "MCMTL_E_OBJC_EXCEPTION";
            case E_UNIMPLEMENTED -> "MCMTL_E_UNIMPLEMENTED";
            case E_ABI_MISMATCH -> "MCMTL_E_ABI_MISMATCH";
            case E_SURFACE -> "MCMTL_E_SURFACE";
            case E_INTERNAL -> "MCMTL_E_INTERNAL";
            default -> "MCMTL_STATUS_" + status;
        };
    }

    /**
     * Maps a failed status to the exception the RenderPearl contract expects (CONTRACT.md 3.2):
     * OUT_OF_MEMORY -> {@link GpuOutOfMemoryException}, DEVICE_LOST -> {@link GpuDeviceLossException},
     * INVALID_ARGUMENT/UNSUPPORTED -> {@link IllegalArgumentException}, UNIMPLEMENTED ->
     * {@link UnsupportedOperationException}, everything else -> {@link IllegalStateException}. Reads
     * {@link #lastError()}, so call it on the failing thread right after the downcall.
     */
    public static RuntimeException exceptionFor(int status, String what) {
        String message = what + " failed: " + statusName(status) + ": " + lastError();
        return switch (status) {
            case E_OUT_OF_MEMORY -> new GpuOutOfMemoryException(message);
            case E_DEVICE_LOST -> new GpuDeviceLossException(message);
            case E_INVALID_ARGUMENT, E_UNSUPPORTED -> new IllegalArgumentException(message);
            case E_UNIMPLEMENTED -> new UnsupportedOperationException(message);
            default -> new IllegalStateException(message);
        };
    }

    /** Throws {@link #exceptionFor(int, String)} unless {@code status == OK}. */
    public static void check(int status, String what) {
        if (status != OK) {
            throw exceptionFor(status, what);
        }
    }

    private static final VarHandle STATUS_LONG = JAVA_LONG.varHandle();
    private static final VarHandle STATUS_INT = JAVA_INT.varHandle();

    /** A {@code MemorySegment} over the device's {@code mcmtl_status_page} (address from {@code mcmtl_device_info.statusPage}). */
    public static MemorySegment statusPage(long address) {
        return MemorySegment.ofAddress(address).reinterpret(STATUS_PAGE_SIZEOF);
    }

    /** Acquire-load of a 64-bit status-page field, e.g. {@code statusLong(page, STATUS_PAGE_COMPLETED_VALUE)}. */
    public static long statusLong(MemorySegment page, long offset) {
        return (long)STATUS_LONG.getAcquire(page, offset);
    }

    /** Acquire-load of a 32-bit status-page field, e.g. {@code statusInt(page, STATUS_PAGE_ERROR_COUNT)}. */
    public static int statusInt(MemorySegment page, long offset) {
        return (int)STATUS_INT.getAcquire(page, offset);
    }

    /** Java-side sizes of every struct with an id, for {@link #verifyAbi()}: {id, size} pairs. */
    private static final long[][] STRUCT_SIZES = {
        {STRUCT_PROBE_INFO, PROBE_INFO_SIZEOF},
        {STRUCT_DEVICE_DESC, DEVICE_DESC_SIZEOF},
        {STRUCT_DEVICE_INFO, DEVICE_INFO_SIZEOF},
        {STRUCT_STATUS_PAGE, STATUS_PAGE_SIZEOF},
        {STRUCT_BUFFER_DESC, BUFFER_DESC_SIZEOF},
        {STRUCT_BUFFER_RESULT, BUFFER_RESULT_SIZEOF},
        {STRUCT_TEXTURE_DESC, TEXTURE_DESC_SIZEOF},
        {STRUCT_TEXTURE_VIEW_DESC, TEXTURE_VIEW_DESC_SIZEOF},
        {STRUCT_TEXEL_VIEW_DESC, TEXEL_VIEW_DESC_SIZEOF},
        {STRUCT_SAMPLER_DESC, SAMPLER_DESC_SIZEOF},
        {STRUCT_OBJECT_RESULT, OBJECT_RESULT_SIZEOF},
        {STRUCT_SHADER_DESC, SHADER_DESC_SIZEOF},
        {STRUCT_VERTEX_ATTRIBUTE, VERTEX_ATTRIBUTE_SIZEOF},
        {STRUCT_VERTEX_LAYOUT, VERTEX_LAYOUT_SIZEOF},
        {STRUCT_COLOR_TARGET, COLOR_TARGET_SIZEOF},
        {STRUCT_PIPELINE_DESC, PIPELINE_DESC_SIZEOF},
        {STRUCT_SURFACE_CONFIG, SURFACE_CONFIG_SIZEOF},
        {STRUCT_SUBMIT_INFO, SUBMIT_INFO_SIZEOF},
        {STRUCT_SUBMIT_RESULT, SUBMIT_RESULT_SIZEOF},
        {StreamOps.STRUCT_CMD_HEADER, StreamOps.HEADER_SIZEOF},
        {StreamOps.STRUCT_CMD_EMPTY, StreamOps.EMPTY_SIZEOF},
        {StreamOps.STRUCT_CMD_BEGIN_PASS, StreamOps.BEGIN_PASS_SIZEOF},
        {StreamOps.STRUCT_CMD_COLOR_ATTACHMENT, StreamOps.COLOR_ATTACHMENT_SIZEOF},
        {StreamOps.STRUCT_CMD_END_PASS, StreamOps.END_PASS_SIZEOF},
        {StreamOps.STRUCT_CMD_CLEAR_TEXTURE, StreamOps.CLEAR_TEXTURE_SIZEOF},
        {StreamOps.STRUCT_CMD_CLEAR_REGION, StreamOps.CLEAR_REGION_SIZEOF},
        {StreamOps.STRUCT_CMD_COPY_BUFFER, StreamOps.COPY_BUFFER_SIZEOF},
        {StreamOps.STRUCT_CMD_COPY_BUFFER_TEXTURE, StreamOps.COPY_BUFFER_TEXTURE_SIZEOF},
        {StreamOps.STRUCT_CMD_COPY_TEXTURE, StreamOps.COPY_TEXTURE_SIZEOF},
        {StreamOps.STRUCT_CMD_PRESENT, StreamOps.PRESENT_SIZEOF},
        {StreamOps.STRUCT_CMD_INIT_TEXTURE, StreamOps.INIT_TEXTURE_SIZEOF},
        {StreamOps.STRUCT_CMD_TIMESTAMP, StreamOps.TIMESTAMP_SIZEOF},
        {StreamOps.STRUCT_CMD_PUSH_DEBUG_GROUP, StreamOps.PUSH_DEBUG_GROUP_SIZEOF},
        {StreamOps.STRUCT_CMD_SET_PIPELINE, StreamOps.SET_PIPELINE_SIZEOF},
        {StreamOps.STRUCT_CMD_SET_BUFFER, StreamOps.SET_BUFFER_SIZEOF},
        {StreamOps.STRUCT_CMD_SET_TEXTURE, StreamOps.SET_TEXTURE_SIZEOF},
        {StreamOps.STRUCT_CMD_CLEAR_BINDING, StreamOps.CLEAR_BINDING_SIZEOF},
        {StreamOps.STRUCT_CMD_SET_VERTEX_BUFFER, StreamOps.SET_VERTEX_BUFFER_SIZEOF},
        {StreamOps.STRUCT_CMD_SET_INDEX_BUFFER, StreamOps.SET_INDEX_BUFFER_SIZEOF},
        {StreamOps.STRUCT_CMD_SET_SCISSOR, StreamOps.SET_SCISSOR_SIZEOF},
        {StreamOps.STRUCT_CMD_PUSH_CONSTANTS, StreamOps.PUSH_CONSTANTS_SIZEOF},
        {StreamOps.STRUCT_CMD_DRAW, StreamOps.DRAW_SIZEOF},
        {StreamOps.STRUCT_CMD_DRAW_INDEXED, StreamOps.DRAW_INDEXED_SIZEOF},
        {StreamOps.STRUCT_CMD_DRAW_INDIRECT, StreamOps.DRAW_INDIRECT_SIZEOF},
        {StreamOps.STRUCT_CMD_UPSCALE, StreamOps.UPSCALE_SIZEOF},
    };

    /**
     * Verifies that the loaded library speaks this ABI: {@code mcmtl_abi_version() == ABI_VERSION} and every
     * struct size reported by {@code mcmtl_abi_struct_size} equals the Java mirror (including the
     * stream records of {@link StreamOps}). Returns a list of human-readable mismatches (empty = OK).
     */
    public static List<String> verifyAbi() {
        List<String> problems = new ArrayList<>();
        int version = abiVersion();
        if (version != ABI_VERSION) {
            problems.add("ABI version: library " + version + ", Java " + ABI_VERSION);
        }
        for (long[] entry : STRUCT_SIZES) {
            int nativeSize = abiStructSize((int)entry[0]);
            if (nativeSize != entry[1]) {
                problems.add("struct id " + entry[0] + ": native sizeof " + nativeSize + ", Java " + entry[1]);
            }
        }
        return problems;
    }

    /** Number of struct sizes {@link #verifyAbi()} compares. */
    public static int verifiedStructCount() {
        return STRUCT_SIZES.length;
    }
}
