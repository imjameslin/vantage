/* Optional cluster cull extension and internal far-producer encoding. Not part of ABI 2. */
#pragma once
#import <Metal/Metal.h>
#include "mcmetal.h"
#include <stdint.h>
@class MCMTLDevice;

/* Layouts also consumed by MetalClusterCull's existing optional FFM exports. */
typedef struct {
    uint64_t itemsBuffer, itemsOffset;
    uint64_t sectionsBuffer, sectionsOffset;
    uint64_t outItemsBuffer, outItemsOffset;
    uint64_t quadsBuffer, quadsOffset;
    uint64_t argsBuffer, argsOffset;
    uint64_t scratchBuffer, scratchOffset;
    uint32_t clusterCount, quadCapacity;
} mcmtl_cluster_job;
_Static_assert(sizeof(mcmtl_cluster_job) == 104, "mcmtl_cluster_job layout");

typedef struct {
    uint64_t visBuffer, visOffset;
    uint64_t boxesBuffer, boxesOffset;
    uint64_t paramsBuffer, paramsOffset;
    uint32_t boxCapacity, stamp, visEntries;
    float eyeMin;
    uint32_t prevKey;
    uint32_t pad[3];
} mcmtl_lod_occl;
_Static_assert(sizeof(mcmtl_lod_occl) == 80, "mcmtl_lod_occl layout");

typedef struct {
    uint64_t sitemsBuffer, sitemsOffset;
    uint64_t secOccBuffer, secOccOffset;
    uint32_t count, visBase;
    uint32_t pad[2];
} mcmtl_lod_sec;
_Static_assert(sizeof(mcmtl_lod_sec) == 48, "mcmtl_lod_sec layout");

/* Same jobs/view/occlusion/sections layouts and main-queue side-buffer behavior as
 * mcmtl_ext_lod_cull_sec. occlusion and sections may be NULL; sections requires occlusion,
 * and occlusion requires jobExtra. A live MTLBuffer on this device must contain
 * coverageCount bytes (1..2^20) starting at coverageOffset (any byte offset).
 * Byte i != 0 hides section slot i, including clusters with unusable bounds. Slots
 * >= coverageCount are uncovered. Invalid mask/range rejects the whole batch before encoding.
 * Caller owns the immutable, GPU-ready mask through cull completion, independently of the
 * item ring, and clears old coverage before safely reusing each physical mask buffer.
 * Covered occlusion entries are marked ineligible/visible and produce no boxes. On coverage
 * changes the caller must also invalidate temporal occlusion trust (occlusion.prevKey) for
 * every affected visibility-ring generation, and invalidate image/cull reuse before reuse.
 * No implicit upload, wait, completion token, or change to existing optional exports/ABI 2.
 */
#ifdef __cplusplus
extern "C" {
#endif
MCMTL_API int32_t mcmtl_ext_lod_cull_coverage(mcmtl_handle device,
    const void *jobs, uint32_t jobCount, const void *view, uint32_t viewBytes,
    const void *occlusion, const void *jobExtra, const void *sections,
    uint64_t coverageBuffer, uint64_t coverageOffset, uint32_t coverageCount);
#ifdef __cplusplus
}
#endif

/* Encode only: never commit, create another command buffer, or touch decoder/timeline state.
 * Call on the producer worker with an uncommitted buffer from its own queue, no open encoder.
 * Caller owns immutable input generations and EXCLUSIVE output/scratch/argument buffers through
 * completion. Fused culling initializes Shared arguments on the CPU before encoding, so a mere
 * GPU event wait does not make reusing another in-flight job's output storage safe.
 * Producer encodes any upload-ready event wait before this call. It must discard this command
 * buffer on failure. Shader compilation may occur on first use; never call from a game-frame offer.
 * Existing public cull exports retain their main-queue side-buffer behavior and timing counters.
 */
int32_t mcmtl_clusters_encode(MCMTLDevice *device, id<MTLCommandBuffer> commandBuffer,
    const mcmtl_cluster_job *jobs, uint32_t jobCount, const void *view, uint32_t viewBytes,
    const mcmtl_lod_occl *occlusion, const uint32_t *jobExtra, const mcmtl_lod_sec *sections);
