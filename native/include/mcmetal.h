/*
 * mcmetal.h -- C ABI of libmcmetal.dylib, the Objective-C shim behind the RenderPearl Metal backend.
 *
 * FROZEN CONTRACT FILE (owner: architect). Implementers must not edit it. Any change requires bumping
 * MCMTL_ABI_VERSION and updating MtlNative.java / StreamOps.java in the same commit; see
 * docs/metal/CONTRACT.md.
 *
 * Conventions (normative, CONTRACT.md section 3):
 *  - Every export is `MCMTL_API`, has C linkage, and wraps its body in
 *    `@autoreleasepool { @try { ... } @catch { ... } }`. No Objective-C exception ever unwinds into Java.
 *  - Functions return `int32_t` status codes (MCMTL_OK = 0, errors < 0) unless documented as a trivial
 *    getter. On error a human-readable message is available from mcmtl_last_error() on the same thread.
 *  - Outputs go through caller-provided pointers. Every struct starts with `structSize`, which the caller
 *    sets to sizeof(struct) (result structs included); a mismatch returns MCMTL_E_ABI_MISMATCH.
 *  - Handles (`mcmtl_handle`) are +1 retained Objective-C object pointers (`__bridge_retained`), 0 = null.
 *    They are released with mcmtl_release / mcmtl_release_batch (devices and surfaces with their
 *    destroy functions). The concrete class behind each handle kind is listed in CONTRACT.md 3.3.
 *  - Java passes every pointer argument and pointer struct field as a 64-bit integer (FFM JAVA_LONG);
 *    on arm64 (AAPCS64) pointers and uint64_t are passed identically.
 *  - All structs are little-endian, naturally aligned, without implicit padding (explicit `reserved`
 *    fields instead). Offsets are pinned by MCMTL_ASSERT_OFFSET lines; the Java mirror constants follow
 *    the naming rule `mcmtl_<name>.<field>` -> `MtlNative.<NAME>_<FIELD>` (upper snake case), sizes
 *    `MtlNative.<NAME>_SIZEOF`. tools/MetalSmoke.java verifies both directions.
 *  - Enum-valued fields carry raw Metal enum values (MTLPixelFormat, MTLVertexFormat, ...). The Java side
 *    maps RenderPearl enums to them (MetalFormats.java); native code passes them through unchanged.
 */
#ifndef MCMETAL_H
#define MCMETAL_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define MCMTL_API __attribute__((visibility("default")))

/* Bumped on ANY change to mcmetal.h or mcmetal_stream.h. MetalBackend.loadLibrary() requires equality.
 * 2: submit guard + signal-value consumption rule, UPSCALE record reserved, init stream = INIT_TEXTURE only. */
#define MCMTL_ABI_VERSION 2u

#define MCMTL_ASSERT_SIZE(type, size) _Static_assert(sizeof(type) == (size), "sizeof(" #type ") != " #size)
#define MCMTL_ASSERT_OFFSET(type, field, off) \
    _Static_assert(offsetof(type, field) == (off), "offsetof(" #type ", " #field ") != " #off)

/* Opaque object handle: +1 retained Objective-C object pointer, 0 = none. */
typedef uint64_t mcmtl_handle;

/* ------------------------------------------------------------------------------------------------ */
/* Status codes                                                                                      */
/* ------------------------------------------------------------------------------------------------ */

#define MCMTL_OK                     0
#define MCMTL_E_INVALID_ARGUMENT    -1  /* bad handle/value; Java: IllegalArgumentException            */
#define MCMTL_E_OUT_OF_MEMORY       -2  /* newBuffer/newTexture returned nil; Java: GpuOutOfMemoryException */
#define MCMTL_E_UNSUPPORTED         -3  /* format/feature not supported; Java: IllegalArgumentException */
#define MCMTL_E_COMPILE_FAILED      -4  /* MSL compile or PSO creation failed (log in last_error)      */
#define MCMTL_E_DEVICE_LOST         -5  /* a command buffer failed with a device-loss class error      */
#define MCMTL_E_TIMEOUT             -6  /* wait timed out (expected outcome of mcmtl_timeline_wait)    */
#define MCMTL_E_BAD_STREAM          -7  /* malformed command stream (unknown op, bad size, op misplaced) */
#define MCMTL_E_NO_DEVICE           -8  /* no Metal device / required GPU family missing               */
#define MCMTL_E_OBJC_EXCEPTION      -9  /* an Objective-C exception was caught (reason in last_error)  */
#define MCMTL_E_UNIMPLEMENTED      -10  /* skeleton stub                                               */
#define MCMTL_E_ABI_MISMATCH       -11  /* structSize mismatch                                          */
#define MCMTL_E_SURFACE            -12  /* CAMetalLayer / drawable problem                              */
#define MCMTL_E_INTERNAL           -99

/* ------------------------------------------------------------------------------------------------ */
/* Limits and fixed binding indices (CONTRACT.md section 6)                                         */
/* ------------------------------------------------------------------------------------------------ */

#define MCMTL_MAX_COLOR_ATTACHMENTS        8u
#define MCMTL_MAX_VERTEX_ATTRIBUTES       31u   /* MTLVertexDescriptor attributes 0..30                 */
#define MCMTL_MAX_VERTEX_BUFFERS          16u   /* RenderPearl vertex buffer slots 0..15                */
#define MCMTL_VERTEX_BUFFER_INDEX_BASE    30u   /* RenderPearl slot s -> [[buffer(30 - s)]]             */
#define MCMTL_MAX_UNIFORM_BUFFER_INDEX    13u   /* UBOs use [[buffer(0..13)]]                           */
#define MCMTL_PUSH_CONSTANT_BUFFER_INDEX  14u   /* push constants use [[buffer(14)]]                    */
#define MCMTL_MAX_TEXTURE_BINDINGS        16u   /* [[texture(0..15)]] / [[sampler(0..15)]] per stage    */
#define MCMTL_MAX_PUSH_CONSTANT_BYTES   4096u
#define MCMTL_MAX_LABEL_BYTES           1024u
#define MCMTL_MAX_DEBUG_MESSAGES          64u
#define MCMTL_LAST_ERROR_CAPACITY       8192u   /* bytes of the thread-local error buffer incl. NUL     */
#define MCMTL_WAIT_FOREVER        UINT64_MAX     /* mcmtl_timeline_wait timeout                          */
#define MCMTL_TIMESTAMP_UNAVAILABLE UINT64_MAX   /* mcmtl_query_pool_resolve: sample not available       */

/* Shader stage bits (pipeline masks, stream records). */
#define MCMTL_STAGE_VERTEX    1u
#define MCMTL_STAGE_FRAGMENT  2u

/* GPU family bits (mcmtl_probe_info.familyMask, mcmtl_device_info.familyMask). */
#define MCMTL_FAMILY_APPLE7   (1u << 0)
#define MCMTL_FAMILY_APPLE8   (1u << 1)
#define MCMTL_FAMILY_APPLE9   (1u << 2)
#define MCMTL_FAMILY_APPLE10  (1u << 3)
#define MCMTL_FAMILY_MAC2     (1u << 4)
#define MCMTL_FAMILY_METAL3   (1u << 5)
#define MCMTL_FAMILY_METAL4   (1u << 6)

/* Feature bits (mcmtl_device_info.featureMask). */
#define MCMTL_FEATURE_COUNTER_SAMPLING_STAGE_BOUNDARY (1u << 0) /* supportsCounterSampling:AtStageBoundary */
#define MCMTL_FEATURE_TIMESTAMP_COUNTER_SET           (1u << 1) /* common counter set "timestamp" present   */
#define MCMTL_FEATURE_METALFX_SPATIAL                 (1u << 2) /* MTLFXSpatialScaler supported (M4)        */
#define MCMTL_FEATURE_UNIFIED_MEMORY                  (1u << 3)
#define MCMTL_FEATURE_MTL4_DECODER                    (1u << 4) /* device runs the Metal 4 decoder (M3)     */

/* ------------------------------------------------------------------------------------------------ */
/* Library / ABI (implemented in mcmetal_common.m)                                                   */
/* ------------------------------------------------------------------------------------------------ */

/* Struct ids for mcmtl_abi_struct_size(). Records of mcmetal_stream.h use ids >= 64. */
#define MCMTL_STRUCT_PROBE_INFO          1u
#define MCMTL_STRUCT_DEVICE_DESC         2u
#define MCMTL_STRUCT_DEVICE_INFO         3u
#define MCMTL_STRUCT_STATUS_PAGE         4u
#define MCMTL_STRUCT_BUFFER_DESC         5u
#define MCMTL_STRUCT_BUFFER_RESULT       6u
#define MCMTL_STRUCT_TEXTURE_DESC        7u
#define MCMTL_STRUCT_TEXTURE_VIEW_DESC   8u
#define MCMTL_STRUCT_TEXEL_VIEW_DESC     9u
#define MCMTL_STRUCT_SAMPLER_DESC       10u
#define MCMTL_STRUCT_OBJECT_RESULT      11u
#define MCMTL_STRUCT_SHADER_DESC        12u
#define MCMTL_STRUCT_VERTEX_ATTRIBUTE   13u
#define MCMTL_STRUCT_VERTEX_LAYOUT      14u
#define MCMTL_STRUCT_COLOR_TARGET       15u
#define MCMTL_STRUCT_PIPELINE_DESC      16u
#define MCMTL_STRUCT_SURFACE_CONFIG     17u
#define MCMTL_STRUCT_SUBMIT_INFO        18u
#define MCMTL_STRUCT_SUBMIT_RESULT      19u

/* Trivial getter (critical-downcall safe): MCMTL_ABI_VERSION of the loaded library. */
MCMTL_API uint32_t mcmtl_abi_version(void);

/* Trivial getter: sizeof the struct with the given MCMTL_STRUCT_* / MCMTL_STRUCT_CMD_* id, 0 if unknown. */
MCMTL_API uint32_t mcmtl_abi_struct_size(uint32_t structId);

/* Thread-local, NUL-terminated UTF-8 message describing the most recent failure on the calling thread
 * ("" if none). Valid until the next mcmtl_* call on the same thread. Never NULL. */
MCMTL_API const char *mcmtl_last_error(void);

/* ------------------------------------------------------------------------------------------------ */
/* Probe (mcmetal_common.m): used by MetalBackend.loadLibrary() before any device exists             */
/* ------------------------------------------------------------------------------------------------ */

typedef struct mcmtl_probe_info {
    uint32_t structSize;                    /*   0 = sizeof(mcmtl_probe_info)                         */
    uint32_t familyMask;                    /*   4 MCMTL_FAMILY_*                                     */
    uint32_t osMajor;                       /*   8 NSProcessInfo.operatingSystemVersion               */
    uint32_t osMinor;                       /*  12                                                    */
    uint32_t osPatch;                       /*  16                                                    */
    uint32_t hasUnifiedMemory;              /*  20 0/1                                                */
    uint64_t maxBufferLength;               /*  24                                                    */
    uint64_t recommendedMaxWorkingSetSize;  /*  32                                                    */
    char     deviceName[128];               /*  40 UTF-8, NUL-terminated (truncated)                  */
} mcmtl_probe_info;                         /* 168 */
MCMTL_ASSERT_SIZE(mcmtl_probe_info, 168);
MCMTL_ASSERT_OFFSET(mcmtl_probe_info, structSize, 0);
MCMTL_ASSERT_OFFSET(mcmtl_probe_info, familyMask, 4);
MCMTL_ASSERT_OFFSET(mcmtl_probe_info, osMajor, 8);
MCMTL_ASSERT_OFFSET(mcmtl_probe_info, osMinor, 12);
MCMTL_ASSERT_OFFSET(mcmtl_probe_info, osPatch, 16);
MCMTL_ASSERT_OFFSET(mcmtl_probe_info, hasUnifiedMemory, 20);
MCMTL_ASSERT_OFFSET(mcmtl_probe_info, maxBufferLength, 24);
MCMTL_ASSERT_OFFSET(mcmtl_probe_info, recommendedMaxWorkingSetSize, 32);
MCMTL_ASSERT_OFFSET(mcmtl_probe_info, deviceName, 40);

/* MTLCreateSystemDefaultDevice() + capability query; creates nothing persistent.
 * MCMTL_OK, or MCMTL_E_NO_DEVICE when no Metal device exists (info still has OS version filled in). */
MCMTL_API int32_t mcmtl_probe(mcmtl_probe_info *outInfo);

/* ------------------------------------------------------------------------------------------------ */
/* Device, timeline, status page (mcmetal_device.m; message ring in mcmetal_common.m)                */
/* ------------------------------------------------------------------------------------------------ */

#define MCMTL_DEVICE_FLAG_DEBUG_LABELS     (1u << 0) /* GpuDebugOptions.useLabels: labels + debug groups  */
#define MCMTL_DEVICE_FLAG_VALIDATION       (1u << 1) /* useValidationLayers: errorOptions = EncoderExecutionStatus, extra checks */
#define MCMTL_DEVICE_FLAG_MTL4             (1u << 2) /* M3: Metal 4 decoder. M1 returns MCMTL_E_UNSUPPORTED */
#define MCMTL_DEVICE_FLAG_UNTRACKED        (1u << 3) /* M2: untracked hazards + unretained refs + fence chain */

typedef struct mcmtl_device_desc {
    uint32_t structSize;      /*  0 */
    uint32_t flags;           /*  4 MCMTL_DEVICE_FLAG_*                                                 */
    uint32_t framesInFlight;  /*  8 2 (informational; the Java timeline enforces it)                     */
    uint32_t reserved;        /* 12 0                                                                    */
    const char *label;        /* 16 queue label (UTF-8, NUL-terminated) or NULL                         */
} mcmtl_device_desc;          /* 24 */
MCMTL_ASSERT_SIZE(mcmtl_device_desc, 24);
MCMTL_ASSERT_OFFSET(mcmtl_device_desc, structSize, 0);
MCMTL_ASSERT_OFFSET(mcmtl_device_desc, flags, 4);
MCMTL_ASSERT_OFFSET(mcmtl_device_desc, framesInFlight, 8);
MCMTL_ASSERT_OFFSET(mcmtl_device_desc, reserved, 12);
MCMTL_ASSERT_OFFSET(mcmtl_device_desc, label, 16);

typedef struct mcmtl_device_info {
    uint32_t structSize;                    /*   0 */
    uint32_t familyMask;                    /*   4 MCMTL_FAMILY_*                                     */
    uint64_t maxBufferLength;               /*   8 device.maxBufferLength                             */
    uint64_t recommendedMaxWorkingSetSize;  /*  16                                                    */
    uint64_t statusPage;                    /*  24 address of this device's mcmtl_status_page         */
    uint32_t maxTextureSize2D;              /*  32 16384                                              */
    uint32_t maxColorAttachments;           /*  36 8                                                  */
    uint32_t maxSamplerAnisotropy;          /*  40 16                                                 */
    uint32_t minUniformOffsetAlignment;     /*  44 16 (spec section 7)                                */
    uint32_t minTexelBufferAlignment;       /*  48 minimumTextureBufferAlignmentForPixelFormat(R8Sint) */
    uint32_t mslLanguageVersion;            /*  52 MTLLanguageVersion used for shaders (3.2 = 196610)  */
    uint32_t osMajor;                       /*  56 */
    uint32_t osMinor;                       /*  60 */
    uint32_t osPatch;                       /*  64 */
    uint32_t featureMask;                   /*  68 MCMTL_FEATURE_*                                    */
    char     name[128];                     /*  72 device.name, UTF-8, NUL-terminated                 */
    char     driverInfo[128];               /* 200 e.g. "Metal (macOS 27.0 26A428), MSL 3.2, classic"  */
} mcmtl_device_info;                        /* 328 */
MCMTL_ASSERT_SIZE(mcmtl_device_info, 328);
MCMTL_ASSERT_OFFSET(mcmtl_device_info, structSize, 0);
MCMTL_ASSERT_OFFSET(mcmtl_device_info, familyMask, 4);
MCMTL_ASSERT_OFFSET(mcmtl_device_info, maxBufferLength, 8);
MCMTL_ASSERT_OFFSET(mcmtl_device_info, recommendedMaxWorkingSetSize, 16);
MCMTL_ASSERT_OFFSET(mcmtl_device_info, statusPage, 24);
MCMTL_ASSERT_OFFSET(mcmtl_device_info, maxTextureSize2D, 32);
MCMTL_ASSERT_OFFSET(mcmtl_device_info, maxColorAttachments, 36);
MCMTL_ASSERT_OFFSET(mcmtl_device_info, maxSamplerAnisotropy, 40);
MCMTL_ASSERT_OFFSET(mcmtl_device_info, minUniformOffsetAlignment, 44);
MCMTL_ASSERT_OFFSET(mcmtl_device_info, minTexelBufferAlignment, 48);
MCMTL_ASSERT_OFFSET(mcmtl_device_info, mslLanguageVersion, 52);
MCMTL_ASSERT_OFFSET(mcmtl_device_info, osMajor, 56);
MCMTL_ASSERT_OFFSET(mcmtl_device_info, osMinor, 60);
MCMTL_ASSERT_OFFSET(mcmtl_device_info, osPatch, 64);
MCMTL_ASSERT_OFFSET(mcmtl_device_info, featureMask, 68);
MCMTL_ASSERT_OFFSET(mcmtl_device_info, name, 72);
MCMTL_ASSERT_OFFSET(mcmtl_device_info, driverInfo, 200);

/* Shared-memory status page, one per device, valid from mcmtl_device_create until mcmtl_device_destroy.
 * Native code writes it with __atomic builtins (release order); Java reads it with acquire loads and no
 * downcall (CONTRACT.md 5.3). Never written by Java. */
typedef struct mcmtl_status_page {
    uint64_t completedValue;  /*  0 largest v such that every committed value <= v has completed       */
    uint64_t submittedValue;  /*  8 highest timeline value committed by mcmtl_submit                    */
    uint64_t lastErrorValue;  /* 16 timeline value of the most recent failed command buffer             */
    uint32_t errorCount;      /* 24 number of failed command buffers (monotonic)                        */
    int32_t  lastErrorCode;   /* 28 MTLCommandBufferError code of the most recent failure               */
    uint32_t deviceLost;      /* 32 sticky 1 after Timeout/PageFault/DeviceRemoved/AccessRevoked/Internal */
    uint32_t messageCount;    /* 36 debug messages pushed so far (monotonic; see copy_messages)         */
    uint64_t lastGpuTimeNs;   /* 40 GPUEndTime - GPUStartTime of the latest completed command buffer    */
    uint64_t reserved0;       /* 48 */
    uint64_t reserved1;       /* 56 */
} mcmtl_status_page;          /* 64 */
MCMTL_ASSERT_SIZE(mcmtl_status_page, 64);
MCMTL_ASSERT_OFFSET(mcmtl_status_page, completedValue, 0);
MCMTL_ASSERT_OFFSET(mcmtl_status_page, submittedValue, 8);
MCMTL_ASSERT_OFFSET(mcmtl_status_page, lastErrorValue, 16);
MCMTL_ASSERT_OFFSET(mcmtl_status_page, errorCount, 24);
MCMTL_ASSERT_OFFSET(mcmtl_status_page, lastErrorCode, 28);
MCMTL_ASSERT_OFFSET(mcmtl_status_page, deviceLost, 32);
MCMTL_ASSERT_OFFSET(mcmtl_status_page, messageCount, 36);
MCMTL_ASSERT_OFFSET(mcmtl_status_page, lastGpuTimeNs, 40);
MCMTL_ASSERT_OFFSET(mcmtl_status_page, reserved0, 48);
MCMTL_ASSERT_OFFSET(mcmtl_status_page, reserved1, 56);

/* Creates the device context: MTLDevice, MTLCommandQueue, MTLSharedEvent (signaledValue 0), status page,
 * caches, decoder state (dummies). Blocking (ms). MCMTL_E_NO_DEVICE / MCMTL_E_UNSUPPORTED on failure. */
MCMTL_API int32_t mcmtl_device_create(const mcmtl_device_desc *desc, mcmtl_handle *outDevice);

/* Waits for every committed command buffer, releases decoder state and the device handle. The caller
 * must already have released every other handle it owns. Blocking. */
MCMTL_API int32_t mcmtl_device_destroy(mcmtl_handle device);

/* Fills *outInfo (structSize must be set). Thread-safe. */
MCMTL_API int32_t mcmtl_device_get_info(mcmtl_handle device, mcmtl_device_info *outInfo);

/* Trivial getter (critical-downcall safe): statusPage.completedValue, i.e. the largest timeline value v
 * such that every committed value <= v has completed (values are dense: 1, 2, 3, ...). Same number Java
 * reads from the status page without a downcall. Returns 0 for a 0 handle. */
MCMTL_API uint64_t mcmtl_timeline_completed(mcmtl_handle device);

/* Blocks until the timeline reaches `value` or `timeoutNs` elapses (MCMTL_WAIT_FOREVER = no limit).
 * MCMTL_OK when reached: statusPage.completedValue >= value, or the GPU already executed the signal of
 * `value` (event.signaledValue >= value; command buffers of one queue execute in commit order, spec 1.5)
 * while the completion handlers that advance completedValue have not run yet -- so completedValue may
 * still be < value for a few microseconds after MCMTL_OK. MCMTL_E_TIMEOUT; MCMTL_E_DEVICE_LOST when a
 * device-loss error was recorded; MCMTL_E_INVALID_ARGUMENT when value > statusPage.submittedValue (would
 * never complete). Never critical: may block. */
MCMTL_API int32_t mcmtl_timeline_wait(mcmtl_handle device, uint64_t value, uint64_t timeoutNs);

/* [device sampleTimestamps:gpuTimestamp:]; both values in nanoseconds on Apple GPUs (spec 2.13). */
MCMTL_API int32_t mcmtl_device_sample_timestamps(mcmtl_handle device, uint64_t *outCpuNs, uint64_t *outGpuNs);

/* Copies the retained debug messages (command-buffer errors, decoder warnings), oldest first, separated
 * by '\n', NUL-terminated when capacity > 0, truncated to capacity. *outLength = bytes written excluding
 * the NUL. Thread-safe. (mcmetal_common.m) */
MCMTL_API int32_t mcmtl_device_copy_messages(mcmtl_handle device, char *buffer, uint32_t capacity, uint32_t *outLength);

/* ------------------------------------------------------------------------------------------------ */
/* Resources (mcmetal_resources.m). Thread-safe; Java calls them from the render thread.             */
/* ------------------------------------------------------------------------------------------------ */

typedef struct mcmtl_buffer_desc {
    uint32_t structSize;       /*  0 */
    uint32_t usage;            /*  4 RenderPearl GpuBuffer.USAGE_* bits; USAGE_UNIFORM_TEXEL_BUFFER (256)
                                *    makes native round the allocation up to minTexelBufferAlignment      */
    uint64_t size;             /*  8 requested bytes (> 0)                                              */
    uint32_t resourceOptions;  /* 16 MTLResourceOptions (storage/cache/hazard bits; M1: Shared 0, optionally
                                *    CPUCacheModeWriteCombined 1)                                         */
    uint32_t reserved;         /* 20 0 */
    const char *label;         /* 24 UTF-8 NUL-terminated or NULL (only when debug labels are on)       */
} mcmtl_buffer_desc;           /* 32 */
MCMTL_ASSERT_SIZE(mcmtl_buffer_desc, 32);
MCMTL_ASSERT_OFFSET(mcmtl_buffer_desc, structSize, 0);
MCMTL_ASSERT_OFFSET(mcmtl_buffer_desc, usage, 4);
MCMTL_ASSERT_OFFSET(mcmtl_buffer_desc, size, 8);
MCMTL_ASSERT_OFFSET(mcmtl_buffer_desc, resourceOptions, 16);
MCMTL_ASSERT_OFFSET(mcmtl_buffer_desc, reserved, 20);
MCMTL_ASSERT_OFFSET(mcmtl_buffer_desc, label, 24);

typedef struct mcmtl_buffer_result {
    uint32_t structSize;       /*  0 */
    uint32_t reserved;         /*  4 */
    mcmtl_handle handle;       /*  8 +1 id<MTLBuffer>                                                   */
    uint64_t contents;         /* 16 CPU address of the contents (Shared storage), 0 for Private        */
    uint64_t gpuAddress;       /* 24 buffer.gpuAddress                                                  */
    uint64_t length;           /* 32 allocated length (>= requested size)                               */
} mcmtl_buffer_result;         /* 40 */
MCMTL_ASSERT_SIZE(mcmtl_buffer_result, 40);
MCMTL_ASSERT_OFFSET(mcmtl_buffer_result, structSize, 0);
MCMTL_ASSERT_OFFSET(mcmtl_buffer_result, reserved, 4);
MCMTL_ASSERT_OFFSET(mcmtl_buffer_result, handle, 8);
MCMTL_ASSERT_OFFSET(mcmtl_buffer_result, contents, 16);
MCMTL_ASSERT_OFFSET(mcmtl_buffer_result, gpuAddress, 24);
MCMTL_ASSERT_OFFSET(mcmtl_buffer_result, length, 32);

/* newBufferWithLength:options:. Contents undefined. MCMTL_E_OUT_OF_MEMORY if Metal returns nil. */
MCMTL_API int32_t mcmtl_buffer_create(mcmtl_handle device, const mcmtl_buffer_desc *desc, mcmtl_buffer_result *out);

typedef struct mcmtl_texture_desc {
    uint32_t structSize;       /*  0 */
    uint32_t pixelFormat;      /*  4 MTLPixelFormat (never 0)                                           */
    uint32_t textureType;      /*  8 MTLTextureType: Type2D (2) or Cube (5)                             */
    uint32_t width;            /* 12 */
    uint32_t height;           /* 16 */
    uint32_t depth;            /* 20 1                                                                  */
    uint32_t mipLevels;        /* 24 */
    uint32_t arrayLength;      /* 28 1 (a cube texture has arrayLength 1 and 6 implicit faces)          */
    uint32_t textureUsage;     /* 32 MTLTextureUsage bits                                               */
    uint32_t resourceOptions;  /* 36 MTLResourceOptions (M1: StorageModePrivate 32)                     */
    const char *label;         /* 40 UTF-8 NUL-terminated or NULL                                       */
} mcmtl_texture_desc;          /* 48 */
MCMTL_ASSERT_SIZE(mcmtl_texture_desc, 48);
MCMTL_ASSERT_OFFSET(mcmtl_texture_desc, structSize, 0);
MCMTL_ASSERT_OFFSET(mcmtl_texture_desc, pixelFormat, 4);
MCMTL_ASSERT_OFFSET(mcmtl_texture_desc, textureType, 8);
MCMTL_ASSERT_OFFSET(mcmtl_texture_desc, width, 12);
MCMTL_ASSERT_OFFSET(mcmtl_texture_desc, height, 16);
MCMTL_ASSERT_OFFSET(mcmtl_texture_desc, depth, 20);
MCMTL_ASSERT_OFFSET(mcmtl_texture_desc, mipLevels, 24);
MCMTL_ASSERT_OFFSET(mcmtl_texture_desc, arrayLength, 28);
MCMTL_ASSERT_OFFSET(mcmtl_texture_desc, textureUsage, 32);
MCMTL_ASSERT_OFFSET(mcmtl_texture_desc, resourceOptions, 36);
MCMTL_ASSERT_OFFSET(mcmtl_texture_desc, label, 40);

/* Result of texture / texture view / texel view / sampler creation. */
typedef struct mcmtl_object_result {
    uint32_t structSize;       /*  0 */
    uint32_t reserved;         /*  4 */
    mcmtl_handle handle;       /*  8 +1 retained object                                                 */
    uint64_t resourceId;       /* 16 gpuResourceID._impl (argument tables / Metal 4), 0 if none         */
} mcmtl_object_result;         /* 24 */
MCMTL_ASSERT_SIZE(mcmtl_object_result, 24);
MCMTL_ASSERT_OFFSET(mcmtl_object_result, structSize, 0);
MCMTL_ASSERT_OFFSET(mcmtl_object_result, reserved, 4);
MCMTL_ASSERT_OFFSET(mcmtl_object_result, handle, 8);
MCMTL_ASSERT_OFFSET(mcmtl_object_result, resourceId, 16);

/* newTextureWithDescriptor:. Handle = +1 id<MTLTexture>. MCMTL_E_OUT_OF_MEMORY on nil. */
MCMTL_API int32_t mcmtl_texture_create(mcmtl_handle device, const mcmtl_texture_desc *desc, mcmtl_object_result *out);

typedef struct mcmtl_texture_view_desc {
    uint32_t structSize;       /*  0 */
    uint32_t pixelFormat;      /*  4 same as the parent                                                 */
    uint32_t textureType;      /*  8 same as the parent (Type2D or Cube)                                */
    uint32_t baseLevel;        /* 12 */
    uint32_t levelCount;       /* 16 >= 1                                                               */
    uint32_t baseSlice;        /* 20 0                                                                  */
    uint32_t sliceCount;       /* 24 1, or 6 for cube                                                   */
    uint32_t reserved;         /* 28 */
    mcmtl_handle texture;      /* 32 parent texture handle                                              */
} mcmtl_texture_view_desc;     /* 40 */
MCMTL_ASSERT_SIZE(mcmtl_texture_view_desc, 40);
MCMTL_ASSERT_OFFSET(mcmtl_texture_view_desc, structSize, 0);
MCMTL_ASSERT_OFFSET(mcmtl_texture_view_desc, pixelFormat, 4);
MCMTL_ASSERT_OFFSET(mcmtl_texture_view_desc, textureType, 8);
MCMTL_ASSERT_OFFSET(mcmtl_texture_view_desc, baseLevel, 12);
MCMTL_ASSERT_OFFSET(mcmtl_texture_view_desc, levelCount, 16);
MCMTL_ASSERT_OFFSET(mcmtl_texture_view_desc, baseSlice, 20);
MCMTL_ASSERT_OFFSET(mcmtl_texture_view_desc, sliceCount, 24);
MCMTL_ASSERT_OFFSET(mcmtl_texture_view_desc, reserved, 28);
MCMTL_ASSERT_OFFSET(mcmtl_texture_view_desc, texture, 32);

/* newTextureViewWithPixelFormat:textureType:levels:slices:. Handle = +1 id<MTLTexture> (the view keeps
 * its parent alive). Java only calls it for partial mip ranges (full range = parent handle). */
MCMTL_API int32_t mcmtl_texture_view_create(mcmtl_handle device, const mcmtl_texture_view_desc *desc, mcmtl_object_result *out);

typedef struct mcmtl_texel_view_desc {
    uint32_t structSize;       /*  0 */
    uint32_t pixelFormat;      /*  4 MTLPixelFormat of the texel buffer (vanilla: R8Sint 14)            */
    uint32_t bytesPerTexel;    /*  8 */
    uint32_t reserved;         /* 12 */
    mcmtl_handle buffer;       /* 16 */
    uint64_t offset;           /* 24 0 (RenderPearl binds whole buffers)                                */
    uint64_t length;           /* 32 bytes visible = GpuBuffer.size()                                   */
} mcmtl_texel_view_desc;       /* 40 */
MCMTL_ASSERT_SIZE(mcmtl_texel_view_desc, 40);
MCMTL_ASSERT_OFFSET(mcmtl_texel_view_desc, structSize, 0);
MCMTL_ASSERT_OFFSET(mcmtl_texel_view_desc, pixelFormat, 4);
MCMTL_ASSERT_OFFSET(mcmtl_texel_view_desc, bytesPerTexel, 8);
MCMTL_ASSERT_OFFSET(mcmtl_texel_view_desc, reserved, 12);
MCMTL_ASSERT_OFFSET(mcmtl_texel_view_desc, buffer, 16);
MCMTL_ASSERT_OFFSET(mcmtl_texel_view_desc, offset, 24);
MCMTL_ASSERT_OFFSET(mcmtl_texel_view_desc, length, 32);

/* [buffer newTextureWithDescriptor:textureBufferDescriptor(width = length / bytesPerTexel)
 *   offset:offset bytesPerRow:alignUp(length, minTexelBufferAlignment)]. Handle = +1 id<MTLTexture>. */
MCMTL_API int32_t mcmtl_texel_view_create(mcmtl_handle device, const mcmtl_texel_view_desc *desc, mcmtl_object_result *out);

#define MCMTL_SAMPLER_FLAG_SUPPORT_ARGUMENT_BUFFERS (1u << 0)

typedef struct mcmtl_sampler_desc {
    uint32_t structSize;       /*  0 */
    uint32_t sAddressMode;     /*  4 MTLSamplerAddressMode                                              */
    uint32_t tAddressMode;     /*  8 */
    uint32_t rAddressMode;     /* 12 */
    uint32_t minFilter;        /* 16 MTLSamplerMinMagFilter                                             */
    uint32_t magFilter;        /* 20 */
    uint32_t mipFilter;        /* 24 MTLSamplerMipFilter                                                */
    uint32_t maxAnisotropy;    /* 28 1..16                                                              */
    float    lodMinClamp;      /* 32 */
    float    lodMaxClamp;      /* 36 FLT_MAX when RenderPearl maxLod is empty                           */
    uint32_t compareFunction;  /* 40 MTLCompareFunction (RenderPearl: Never = 0 = no compare)          */
    uint32_t flags;            /* 44 MCMTL_SAMPLER_FLAG_*                                               */
} mcmtl_sampler_desc;          /* 48 */
MCMTL_ASSERT_SIZE(mcmtl_sampler_desc, 48);
MCMTL_ASSERT_OFFSET(mcmtl_sampler_desc, structSize, 0);
MCMTL_ASSERT_OFFSET(mcmtl_sampler_desc, sAddressMode, 4);
MCMTL_ASSERT_OFFSET(mcmtl_sampler_desc, tAddressMode, 8);
MCMTL_ASSERT_OFFSET(mcmtl_sampler_desc, rAddressMode, 12);
MCMTL_ASSERT_OFFSET(mcmtl_sampler_desc, minFilter, 16);
MCMTL_ASSERT_OFFSET(mcmtl_sampler_desc, magFilter, 20);
MCMTL_ASSERT_OFFSET(mcmtl_sampler_desc, mipFilter, 24);
MCMTL_ASSERT_OFFSET(mcmtl_sampler_desc, maxAnisotropy, 28);
MCMTL_ASSERT_OFFSET(mcmtl_sampler_desc, lodMinClamp, 32);
MCMTL_ASSERT_OFFSET(mcmtl_sampler_desc, lodMaxClamp, 36);
MCMTL_ASSERT_OFFSET(mcmtl_sampler_desc, compareFunction, 40);
MCMTL_ASSERT_OFFSET(mcmtl_sampler_desc, flags, 44);

/* newSamplerStateWithDescriptor: (normalizedCoordinates = YES). Handle = +1 id<MTLSamplerState>. */
MCMTL_API int32_t mcmtl_sampler_create(mcmtl_handle device, const mcmtl_sampler_desc *desc, mcmtl_object_result *out);

/* Timestamp query pool of `count` samples (classic: MTLCounterSampleBuffer of the "timestamp" common
 * counter set, Shared). Always succeeds when count > 0: if the GPU has no timestamp counters the pool is
 * created without a sample buffer and resolves every sample as MCMTL_TIMESTAMP_UNAVAILABLE.
 * Handle = +1 MCMTLQueryPool. */
MCMTL_API int32_t mcmtl_query_pool_create(mcmtl_handle device, uint32_t count, mcmtl_handle *outPool);

/* Resolves samples [first, first+count) into outValuesNs (nanoseconds, GPU timestamp domain).
 * Samples never written or not yet resolvable read MCMTL_TIMESTAMP_UNAVAILABLE. Java calls it only for
 * samples whose command buffer completed. Non-blocking. */
MCMTL_API int32_t mcmtl_query_pool_resolve(mcmtl_handle device, mcmtl_handle pool, uint32_t first, uint32_t count, uint64_t *outValuesNs);

/* Releases one +1 handle of any kind except devices and surfaces (buffers, textures, views, texel views,
 * samplers, shader functions, pipelines, query pools). 0 is ignored. Called only after the GPU timeline
 * passed the object's last use (Java deferred-release FIFO). M3 also removes it from the residency set. */
MCMTL_API int32_t mcmtl_release(mcmtl_handle device, mcmtl_handle object);

/* Releases `count` handles (same rules as mcmtl_release); zeros are skipped. */
MCMTL_API int32_t mcmtl_release_batch(mcmtl_handle device, const mcmtl_handle *objects, uint32_t count);

/* ------------------------------------------------------------------------------------------------ */
/* Shaders and pipelines (mcmetal_pipeline.m). Thread-safe: called from compile worker threads.      */
/* ------------------------------------------------------------------------------------------------ */

#define MCMTL_SHADER_FLAG_PRESERVE_INVARIANCE (1u << 0) /* MTLCompileOptions.preserveInvariance (B13)   */
#define MCMTL_SHADER_FLAG_SAFE_MATH           (1u << 1) /* mathMode = Safe instead of the default Fast  */

typedef struct mcmtl_shader_desc {
    uint32_t structSize;       /*  0 */
    uint32_t stage;            /*  4 MCMTL_STAGE_VERTEX or MCMTL_STAGE_FRAGMENT (labels, validation)    */
    uint32_t languageVersion;  /*  8 MTLLanguageVersion raw value (3.2 = 196610)                        */
    uint32_t flags;            /* 12 MCMTL_SHADER_FLAG_*                                                */
    const char *source;        /* 16 MSL source, UTF-8, need not be NUL-terminated                      */
    uint64_t sourceLength;     /* 24 bytes                                                              */
    const char *entryPoint;    /* 32 NUL-terminated ("main0")                                           */
    const char *label;         /* 40 NUL-terminated or NULL                                             */
} mcmtl_shader_desc;           /* 48 */
MCMTL_ASSERT_SIZE(mcmtl_shader_desc, 48);
MCMTL_ASSERT_OFFSET(mcmtl_shader_desc, structSize, 0);
MCMTL_ASSERT_OFFSET(mcmtl_shader_desc, stage, 4);
MCMTL_ASSERT_OFFSET(mcmtl_shader_desc, languageVersion, 8);
MCMTL_ASSERT_OFFSET(mcmtl_shader_desc, flags, 12);
MCMTL_ASSERT_OFFSET(mcmtl_shader_desc, source, 16);
MCMTL_ASSERT_OFFSET(mcmtl_shader_desc, sourceLength, 24);
MCMTL_ASSERT_OFFSET(mcmtl_shader_desc, entryPoint, 32);
MCMTL_ASSERT_OFFSET(mcmtl_shader_desc, label, 40);

/* newLibraryWithSource:options:error: + newFunctionWithName:. Handle = +1 id<MTLFunction> (keeps its
 * library alive). MCMTL_E_COMPILE_FAILED with the compiler log in mcmtl_last_error(). Blocking (ms). */
MCMTL_API int32_t mcmtl_shader_create(mcmtl_handle device, const mcmtl_shader_desc *desc, mcmtl_handle *outFunction);

typedef struct mcmtl_vertex_attribute {
    uint32_t location;         /*  0 [[attribute(location)]] = MTLVertexDescriptor.attributes[location]  */
    uint32_t format;           /*  4 MTLVertexFormat                                                    */
    uint32_t offset;           /*  8 bytes, multiple of 4                                               */
    uint32_t bufferIndex;      /* 12 Metal buffer index = 30 - RenderPearl slot                         */
} mcmtl_vertex_attribute;      /* 16 */
MCMTL_ASSERT_SIZE(mcmtl_vertex_attribute, 16);
MCMTL_ASSERT_OFFSET(mcmtl_vertex_attribute, location, 0);
MCMTL_ASSERT_OFFSET(mcmtl_vertex_attribute, format, 4);
MCMTL_ASSERT_OFFSET(mcmtl_vertex_attribute, offset, 8);
MCMTL_ASSERT_OFFSET(mcmtl_vertex_attribute, bufferIndex, 12);

typedef struct mcmtl_vertex_layout {
    uint32_t bufferIndex;      /*  0 Metal buffer index = 30 - RenderPearl slot                         */
    uint32_t stride;           /*  4 bytes, multiple of 4                                               */
    uint32_t stepFunction;     /*  8 MTLVertexStepFunction: PerVertex 1 / PerInstance 2                 */
    uint32_t stepRate;         /* 12 max(1, RenderPearl stepRate)                                       */
} mcmtl_vertex_layout;         /* 16 */
MCMTL_ASSERT_SIZE(mcmtl_vertex_layout, 16);
MCMTL_ASSERT_OFFSET(mcmtl_vertex_layout, bufferIndex, 0);
MCMTL_ASSERT_OFFSET(mcmtl_vertex_layout, stride, 4);
MCMTL_ASSERT_OFFSET(mcmtl_vertex_layout, stepFunction, 8);
MCMTL_ASSERT_OFFSET(mcmtl_vertex_layout, stepRate, 12);

typedef struct mcmtl_color_target {
    uint32_t pixelFormat;                 /*  0 MTLPixelFormat, 0 (Invalid) for a null target            */
    uint32_t writeMask;                   /*  4 MTLColorWriteMask (bit-reversed from RenderPearl)        */
    uint32_t blendingEnabled;             /*  8 0/1                                                      */
    uint32_t rgbBlendOperation;           /* 12 MTLBlendOperation                                        */
    uint32_t alphaBlendOperation;         /* 16 */
    uint32_t sourceRGBBlendFactor;        /* 20 MTLBlendFactor                                           */
    uint32_t destinationRGBBlendFactor;   /* 24 */
    uint32_t sourceAlphaBlendFactor;      /* 28 */
    uint32_t destinationAlphaBlendFactor; /* 32 */
    uint32_t reserved;                    /* 36 */
} mcmtl_color_target;                     /* 40 */
MCMTL_ASSERT_SIZE(mcmtl_color_target, 40);
MCMTL_ASSERT_OFFSET(mcmtl_color_target, pixelFormat, 0);
MCMTL_ASSERT_OFFSET(mcmtl_color_target, writeMask, 4);
MCMTL_ASSERT_OFFSET(mcmtl_color_target, blendingEnabled, 8);
MCMTL_ASSERT_OFFSET(mcmtl_color_target, rgbBlendOperation, 12);
MCMTL_ASSERT_OFFSET(mcmtl_color_target, alphaBlendOperation, 16);
MCMTL_ASSERT_OFFSET(mcmtl_color_target, sourceRGBBlendFactor, 20);
MCMTL_ASSERT_OFFSET(mcmtl_color_target, destinationRGBBlendFactor, 24);
MCMTL_ASSERT_OFFSET(mcmtl_color_target, sourceAlphaBlendFactor, 28);
MCMTL_ASSERT_OFFSET(mcmtl_color_target, destinationAlphaBlendFactor, 32);
MCMTL_ASSERT_OFFSET(mcmtl_color_target, reserved, 36);

#define MCMTL_PIPELINE_FLAG_HAS_DEPTH_STATE (1u << 0) /* CreateInfo.depthStencilState() != null           */
#define MCMTL_PIPELINE_FLAG_SUPPORT_ICB     (1u << 1) /* M4: supportIndirectCommandBuffers = YES          */

#define MCMTL_EMULATION_NONE          0u
#define MCMTL_EMULATION_TRIANGLE_FAN  1u /* draws are converted to triangle lists by the decoder (spec 4.3) */

/* Pipeline description. Array masks are indexed [0] = vertex stage, [1] = fragment stage; bit n refers
 * to Metal index n. The masks drive the decoder's dummy-binding policy (CONTRACT.md 6.4). */
typedef struct mcmtl_pipeline_desc {
    uint32_t structSize;                  /*    0 */
    uint32_t flags;                       /*    4 MCMTL_PIPELINE_FLAG_*                               */
    mcmtl_handle vertexFunction;          /*    8 handle from mcmtl_shader_create (stage VERTEX)       */
    mcmtl_handle fragmentFunction;        /*   16 handle from mcmtl_shader_create (stage FRAGMENT)     */
    const char *label;                    /*   24 NUL-terminated or NULL                              */
    uint32_t primitiveType;               /*   32 MTLPrimitiveType used for draws (fans: Triangle)    */
    uint32_t topologyClass;               /*   36 MTLPrimitiveTopologyClass (Point for POINTS, else 0) */
    uint32_t emulation;                   /*   40 MCMTL_EMULATION_*                                   */
    uint32_t cullMode;                    /*   44 MTLCullMode (None 0 / Back 2)                       */
    uint32_t fillMode;                    /*   48 MTLTriangleFillMode (Fill 0 / Lines 1)              */
    uint32_t frontFacing;                 /*   52 MTLWinding (always Clockwise 0, spec section 5)      */
    uint32_t depthCompare;                /*   56 MTLCompareFunction (Always 7 when no depth state)   */
    uint32_t depthWriteEnabled;           /*   60 0/1                                                 */
    float    depthBiasConstant;           /*   64 encoder setDepthBias:                               */
    float    depthBiasSlopeScale;         /*   68 slopeScale:                                         */
    float    depthBiasClamp;              /*   72 clamp: (0)                                          */
    uint32_t pushConstantSize;            /*   76 bytes, 0 = none                                     */
    uint32_t pushConstantStages;          /*   80 MCMTL_STAGE_* bits whose MSL reads [[buffer(14)]]    */
    uint32_t colorTargetCount;            /*   84 0..8 = CreateInfo.colorTargetStates().size()        */
    mcmtl_color_target colorTargets[8];   /*   88 */
    uint32_t attributeCount;              /*  408 0..31                                               */
    uint32_t layoutCount;                 /*  412 0..16                                               */
    mcmtl_vertex_attribute attributes[31];/*  416 */
    mcmtl_vertex_layout layouts[16];      /*  912 */
    uint32_t bufferMask[2];               /* 1168 Metal buffer indices read per stage (UBOs, 14, vertex buffers) */
    uint32_t textureMask[2];              /* 1176 texture indices read per stage                      */
    uint32_t samplerMask[2];              /* 1184 sampler indices read per stage                      */
    uint32_t textureCubeMask[2];          /* 1192 subset of textureMask declared texturecube<>         */
    uint32_t texelBufferMask[2];          /* 1200 subset of textureMask declared texture_buffer<>      */
    uint32_t requiredVertexBufferMask;    /* 1208 Metal buffer indices that must hold a vertex buffer  */
    uint32_t precompileDepthFormatCount;  /* 1212 0..4                                                */
    uint32_t precompileDepthFormats[4];   /* 1216 depth MTLPixelFormats to precompile variants for     */
    uint32_t reserved[4];                 /* 1232 */
} mcmtl_pipeline_desc;                    /* 1248 */
MCMTL_ASSERT_SIZE(mcmtl_pipeline_desc, 1248);
MCMTL_ASSERT_OFFSET(mcmtl_pipeline_desc, structSize, 0);
MCMTL_ASSERT_OFFSET(mcmtl_pipeline_desc, flags, 4);
MCMTL_ASSERT_OFFSET(mcmtl_pipeline_desc, vertexFunction, 8);
MCMTL_ASSERT_OFFSET(mcmtl_pipeline_desc, fragmentFunction, 16);
MCMTL_ASSERT_OFFSET(mcmtl_pipeline_desc, label, 24);
MCMTL_ASSERT_OFFSET(mcmtl_pipeline_desc, primitiveType, 32);
MCMTL_ASSERT_OFFSET(mcmtl_pipeline_desc, topologyClass, 36);
MCMTL_ASSERT_OFFSET(mcmtl_pipeline_desc, emulation, 40);
MCMTL_ASSERT_OFFSET(mcmtl_pipeline_desc, cullMode, 44);
MCMTL_ASSERT_OFFSET(mcmtl_pipeline_desc, fillMode, 48);
MCMTL_ASSERT_OFFSET(mcmtl_pipeline_desc, frontFacing, 52);
MCMTL_ASSERT_OFFSET(mcmtl_pipeline_desc, depthCompare, 56);
MCMTL_ASSERT_OFFSET(mcmtl_pipeline_desc, depthWriteEnabled, 60);
MCMTL_ASSERT_OFFSET(mcmtl_pipeline_desc, depthBiasConstant, 64);
MCMTL_ASSERT_OFFSET(mcmtl_pipeline_desc, depthBiasSlopeScale, 68);
MCMTL_ASSERT_OFFSET(mcmtl_pipeline_desc, depthBiasClamp, 72);
MCMTL_ASSERT_OFFSET(mcmtl_pipeline_desc, pushConstantSize, 76);
MCMTL_ASSERT_OFFSET(mcmtl_pipeline_desc, pushConstantStages, 80);
MCMTL_ASSERT_OFFSET(mcmtl_pipeline_desc, colorTargetCount, 84);
MCMTL_ASSERT_OFFSET(mcmtl_pipeline_desc, colorTargets, 88);
MCMTL_ASSERT_OFFSET(mcmtl_pipeline_desc, attributeCount, 408);
MCMTL_ASSERT_OFFSET(mcmtl_pipeline_desc, layoutCount, 412);
MCMTL_ASSERT_OFFSET(mcmtl_pipeline_desc, attributes, 416);
MCMTL_ASSERT_OFFSET(mcmtl_pipeline_desc, layouts, 912);
MCMTL_ASSERT_OFFSET(mcmtl_pipeline_desc, bufferMask, 1168);
MCMTL_ASSERT_OFFSET(mcmtl_pipeline_desc, textureMask, 1176);
MCMTL_ASSERT_OFFSET(mcmtl_pipeline_desc, samplerMask, 1184);
MCMTL_ASSERT_OFFSET(mcmtl_pipeline_desc, textureCubeMask, 1192);
MCMTL_ASSERT_OFFSET(mcmtl_pipeline_desc, texelBufferMask, 1200);
MCMTL_ASSERT_OFFSET(mcmtl_pipeline_desc, requiredVertexBufferMask, 1208);
MCMTL_ASSERT_OFFSET(mcmtl_pipeline_desc, precompileDepthFormatCount, 1212);
MCMTL_ASSERT_OFFSET(mcmtl_pipeline_desc, precompileDepthFormats, 1216);
MCMTL_ASSERT_OFFSET(mcmtl_pipeline_desc, reserved, 1232);

/* Builds an MCMTLPipeline: vertex descriptor, blend state, depth-stencil state (device cache), raster
 * state, masks, and precompiles one PSO variant per precompileDepthFormats[i] with every color target
 * present. Further variants (missing attachments, other depth formats) are built lazily by the decoder.
 * Handle = +1 MCMTLPipeline. MCMTL_E_COMPILE_FAILED with the reason in mcmtl_last_error(). Blocking. */
MCMTL_API int32_t mcmtl_pipeline_create(mcmtl_handle device, const mcmtl_pipeline_desc *desc, mcmtl_handle *outPipeline);

/* ------------------------------------------------------------------------------------------------ */
/* Surface (mcmetal_surface.m). Render thread (= AppKit main thread) only.                           */
/* ------------------------------------------------------------------------------------------------ */

#define MCMTL_SURFACE_FLAG_DISPLAY_SYNC (1u << 0) /* layer.displaySyncEnabled = YES (PresentMode.FIFO)  */

typedef struct mcmtl_surface_config {
    uint32_t structSize;             /*  0 */
    uint32_t width;                  /*  4 drawableSize.width in pixels, 1..16384                        */
    uint32_t height;                 /*  8 drawableSize.height in pixels, 1..16384                       */
    uint32_t flags;                  /* 12 MCMTL_SURFACE_FLAG_*                                         */
    uint32_t maximumDrawableCount;   /* 16 2 or 3; 0 = leave unchanged                                  */
    uint32_t reserved;               /* 20 */
} mcmtl_surface_config;              /* 24 */
MCMTL_ASSERT_SIZE(mcmtl_surface_config, 24);
MCMTL_ASSERT_OFFSET(mcmtl_surface_config, structSize, 0);
MCMTL_ASSERT_OFFSET(mcmtl_surface_config, width, 4);
MCMTL_ASSERT_OFFSET(mcmtl_surface_config, height, 8);
MCMTL_ASSERT_OFFSET(mcmtl_surface_config, flags, 12);
MCMTL_ASSERT_OFFSET(mcmtl_surface_config, maximumDrawableCount, 16);
MCMTL_ASSERT_OFFSET(mcmtl_surface_config, reserved, 20);

/* Wraps the CAMetalLayer returned by SDL_Metal_GetLayer (retained) and sets, inside a CATransaction with
 * actions disabled: device, pixelFormat = BGRA8Unorm, framebufferOnly = YES, opaque = YES, colorspace =
 * sRGB, wantsExtendedDynamicRangeContent = NO, maximumDrawableCount = 3, allowsNextDrawableTimeout = YES,
 * presentsWithTransaction = NO. Handle = +1 MCMTLSurface. */
MCMTL_API int32_t mcmtl_surface_create(mcmtl_handle device, void *caMetalLayer, mcmtl_handle *outSurface);

/* drawableSize and displaySyncEnabled (CATransaction, actions disabled). No GPU wait. */
MCMTL_API int32_t mcmtl_surface_configure(mcmtl_handle surface, const mcmtl_surface_config *config);

/* Called by MetalSurface.present() after the frame's submit. Classic: releases the drawable acquired by
 * the PRESENT record (presentDrawable: was registered before commit). Metal 4: [drawable present] then
 * release. No drawable -> no-op. Never fails for recoverable conditions (returns MCMTL_OK). */
MCMTL_API int32_t mcmtl_surface_present(mcmtl_handle surface);

/* Releases any drawable and the layer reference, then the surface handle. The caller already waited for
 * the last frame that presented to it. Java calls SDL_Metal_DestroyView afterwards. */
MCMTL_API int32_t mcmtl_surface_destroy(mcmtl_handle surface);

/* ------------------------------------------------------------------------------------------------ */
/* Submission (guard: mcmetal_common.m; decoding: mcmetal_decoder.m). Render thread only.            */
/* ------------------------------------------------------------------------------------------------ */

#define MCMTL_SUBMIT_FLAG_COMMIT     (1u << 0) /* encode signal(signalValue) + commit the command buffer */
#define MCMTL_SUBMIT_FLAG_FRAME_END  (1u << 1) /* last commit of a frame (CommandEncoderBackend.submit) */
#define MCMTL_SUBMIT_FLAG_ASYNC      (1u << 2) /* reserved (B16); M1-M3 return MCMTL_E_UNSUPPORTED       */

#define MCMTL_SUBMIT_RESULT_FLAG_COMMITTED            (1u << 0) /* the command buffer was committed      */
#define MCMTL_SUBMIT_RESULT_FLAG_PRESENT_ENCODED      (1u << 1) /* a PRESENT acquired a drawable, encoded
                                                                 * the present pass, registered presentDrawable */
#define MCMTL_SUBMIT_RESULT_FLAG_DRAWABLE_NIL         (1u << 2) /* a PRESENT got a nil drawable; skipped  */
#define MCMTL_SUBMIT_RESULT_FLAG_DRAWABLE_RESIZED     (1u << 3) /* drawable size != configured size       */
#define MCMTL_SUBMIT_RESULT_FLAG_DEVICE_LOST          (1u << 4) /* statusPage.deviceLost is set           */
#define MCMTL_SUBMIT_RESULT_FLAG_DRAWS_SKIPPED        (1u << 5) /* >= 1 draw skipped (see drawsSkipped)  */
#define MCMTL_SUBMIT_RESULT_FLAG_ERROR_IN_INIT_STREAM (1u << 6) /* errorOffset refers to the init stream  */

typedef struct mcmtl_submit_info {
    uint32_t structSize;        /*  0 */
    uint32_t flags;             /*  4 MCMTL_SUBMIT_FLAG_*                                               */
    const void *initStream;     /*  8 records decoded first: INIT_TEXTURE and NOP only (anything else is
                                 *    MCMTL_E_BAD_STREAM), or NULL                                       */
    uint64_t initStreamLength;  /* 16 bytes, multiple of 16                                             */
    const void *stream;         /* 24 main records, or NULL                                             */
    uint64_t streamLength;      /* 32 bytes, multiple of 16                                             */
    uint64_t signalValue;       /* 40 timeline value signaled before commit (COMMIT): == Java pendingSeq,
                                 *    exactly (previous COMMIT's value + 1); the first COMMIT uses 1     */
    const char *label;          /* 48 command buffer label or NULL                                      */
    uint64_t reserved;          /* 56 0 */
} mcmtl_submit_info;            /* 64 */
MCMTL_ASSERT_SIZE(mcmtl_submit_info, 64);
MCMTL_ASSERT_OFFSET(mcmtl_submit_info, structSize, 0);
MCMTL_ASSERT_OFFSET(mcmtl_submit_info, flags, 4);
MCMTL_ASSERT_OFFSET(mcmtl_submit_info, initStream, 8);
MCMTL_ASSERT_OFFSET(mcmtl_submit_info, initStreamLength, 16);
MCMTL_ASSERT_OFFSET(mcmtl_submit_info, stream, 24);
MCMTL_ASSERT_OFFSET(mcmtl_submit_info, streamLength, 32);
MCMTL_ASSERT_OFFSET(mcmtl_submit_info, signalValue, 40);
MCMTL_ASSERT_OFFSET(mcmtl_submit_info, label, 48);
MCMTL_ASSERT_OFFSET(mcmtl_submit_info, reserved, 56);

typedef struct mcmtl_submit_result {
    uint32_t structSize;        /*  0 */
    uint32_t flags;             /*  4 MCMTL_SUBMIT_RESULT_FLAG_*                                        */
    uint64_t completedValue;    /*  8 statusPage.completedValue snapshot at return                      */
    uint64_t errorOffset;       /* 16 byte offset of the first rejected record (status != OK)           */
    uint32_t recordsDecoded;    /* 24 */
    uint32_t drawsEncoded;      /* 28 */
    uint32_t drawsSkipped;      /* 32 missing vertex/index buffer, pipeline variant failure, ...         */
    uint32_t encodersCreated;   /* 36 render + blit encoders (diagnostics)                              */
    uint32_t drawableWidth;     /* 40 size of the drawable acquired by PRESENT (0 if none)              */
    uint32_t drawableHeight;    /* 44 */
    uint64_t cpuNanos;          /* 48 wall time spent inside mcmtl_submit                               */
    uint64_t reserved;          /* 56 */
} mcmtl_submit_result;          /* 64 */
MCMTL_ASSERT_SIZE(mcmtl_submit_result, 64);
MCMTL_ASSERT_OFFSET(mcmtl_submit_result, structSize, 0);
MCMTL_ASSERT_OFFSET(mcmtl_submit_result, flags, 4);
MCMTL_ASSERT_OFFSET(mcmtl_submit_result, completedValue, 8);
MCMTL_ASSERT_OFFSET(mcmtl_submit_result, errorOffset, 16);
MCMTL_ASSERT_OFFSET(mcmtl_submit_result, recordsDecoded, 24);
MCMTL_ASSERT_OFFSET(mcmtl_submit_result, drawsEncoded, 28);
MCMTL_ASSERT_OFFSET(mcmtl_submit_result, drawsSkipped, 32);
MCMTL_ASSERT_OFFSET(mcmtl_submit_result, encodersCreated, 36);
MCMTL_ASSERT_OFFSET(mcmtl_submit_result, drawableWidth, 40);
MCMTL_ASSERT_OFFSET(mcmtl_submit_result, drawableHeight, 44);
MCMTL_ASSERT_OFFSET(mcmtl_submit_result, cpuNanos, 48);
MCMTL_ASSERT_OFFSET(mcmtl_submit_result, reserved, 56);

/* Decodes initStream then stream into the device's current command buffer (created on demand), ends every
 * encoder it opened, and with MCMTL_SUBMIT_FLAG_COMMIT encodes signalEvent(signalValue), installs the
 * completion handler and commits. Blocking: a PRESENT record calls [layer nextDrawable] (up to ~1 s).
 * Never critical.
 *
 * Signal-value consumption rule (CONTRACT.md 4.2): when `device` and `info` are non-NULL and info->flags
 * has MCMTL_SUBMIT_FLAG_COMMIT, EVERY return path -- success, decode errors, validation errors (bad
 * structSize, NULL or bad outResult, unsupported flags, signalValue != statusPage.submittedValue + 1) and
 * caught Objective-C exceptions -- consumes info->signalValue exactly once: either a committed command
 * buffer signals it, or it is completed on the CPU. On return statusPage.submittedValue >= signalValue and
 * the device holds no open command buffer. MCMTL_SUBMIT_RESULT_FLAG_COMMITTED is set only for a real GPU
 * commit. A decode error skips the bad record and the rest of that stream, still commits, and returns the
 * error with errorOffset. A signalValue that is not submittedValue + 1 is rejected before decoding
 * (MCMTL_E_INVALID_ARGUMENT; the value is CPU-completed if it is ahead of submittedValue). With NULL
 * `device`/`info` nothing can be consumed: MCMTL_E_INVALID_ARGUMENT (a Java bug).
 * The export is the guard in mcmetal_common.m; it calls the decoder (mcmtl_decoder_submit, I2). */
MCMTL_API int32_t mcmtl_submit(mcmtl_handle device, const mcmtl_submit_info *info, mcmtl_submit_result *outResult);

#ifdef __cplusplus
}
#endif

#endif /* MCMETAL_H */
