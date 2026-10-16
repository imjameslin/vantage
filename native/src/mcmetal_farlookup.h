/* Generation-owned inverse visibility-ID map. Internal; no queue or public ABI. */
#pragma once
#import "mcmetal_farsnapshot.h"

enum {
    MCMTLFarFaceSectionMask = 0x001fffffu,
    MCMTLFarFaceArenaShift = 21,
    MCMTLFarFaceLayerShift = 27,
    MCMTLFarFacePartial = 0x80000000u,
    MCMTLFarFaceLayoutBlock = 1,
    MCMTLFarFaceLayoutCompact = 2,
    MCMTLFarFaceMaxArenas = 64
};

/* CPU descriptor, NOT an MSL struct. Borrowed vertices is retained by the lookup.
 * vertexBytes is the accessible buffer suffix, not proof that every face in it
 * belongs to this generation: only nonzero lookup entries authorize a fetch.
 * q = uint64_t(globalID) - faceBase - 1; address = vertexOffset + q * stride.
 * stride = 112 for BLOCK (4 x 28), 48 for compact (3 x uint4).
 * Same buffer/offset/base/layout shares one arena even across SOLID and CUTOUT.
 * Bind vertices per arena (or in an argument buffer); do not upload this struct.
 */
typedef struct {
    __unsafe_unretained id<MTLBuffer> vertices;
    NSUInteger vertexOffset;
    uint64_t vertexBytes;
    uint32_t faceBase, layout;
} MCMTLFarFaceArena;

/* Shader contract: both tables are arrays of uint32, byte offset zero.
 * if (id == 0 || (id >> 6) >= directoryCount) -> invalid.
 * word = directory[id >> 6];
 * if (word & 0x80000000u) {
 *   offset = word & 0x7fffffffu;             // WORD offset in tails, multiple 64
 *   require tailWordCount >= 64 && offset <= tailWordCount - 64;
 *   word = tails[offset + (id & 63u)];
 * }
 * Zero is invalid. Owner: bits 0..20 = section+1; 21..26 = arena slot;
 * bit 27 = layer (0 SOLID, 1 CUTOUT); bits 28..31 MUST be zero.
 * Check section+1 != 0, section < sectionCount, arena < arenaCount.
 * A directory owner covers ALL 64 IDs. Partial pages always have 64 tail words,
 * including zero holes. Bit 31 is never legal in a tail. ID 0 is never mapped.
 * shaderSections[section] is the retained int4 (xyz origin, w float visibility).
 * Layout lives in the arena descriptor, not the owner word.
 *
 * Neither the input metadata nor GPU buffers/contents may be mutated by callers.
 * Retain this object until every GPU lookup/geometry use completes; retaining
 * table/vertex buffers alone does NOT retain geometry allocation-range leases.
 * If bound indirectly, declare the exposed buffers as read resources normally.
 */
@interface MCMTLFarFaceLookup : NSObject
- (instancetype)init NS_UNAVAILABLE;
+ (instancetype)new NS_UNAVAILABLE;
/* Worker only. Reads drawAtIndex:.input.items, NEVER private cull outputs. Keeps
 * geometry, shaderSections and exact vertex buffers; does not keep the snapshot.
 * Inputs and the geometry's device must remain immutable throughout construction.
 * Creates two shared Metal buffers, CPU-initialized and ready for read binding;
 * no command queue, encoder, submission, wait, or live/main state mutation.
 *
 * budgetBytes bounds peak incremental backing bytes: fixed 64 CPU descriptors +
 * 12 bytes per ORIGINAL cluster (in-place sort/merge scratch) + GPU buffer bytes.
 * gpuBufferBytes counts logical allocation lengths: max(4,4*directoryCount) +
 * max(4,4*tailWordCount). Empty tables bind valid 4-byte zero dummy buffers.
 * Excludes retained preexisting geometry/sections/vertices, bounded object and
 * stack overhead, allocator/driver padding. No dense CPU array of arena faces.
 * CPU sorting is O(clusters log clusters), scratch O(clusters); emitting the
 * GPU directory is O(maxID/64), tails O(partial pages*64). Sparse high IDs can
 * therefore exceed budget even with few faces; failure performs no GPU work.
 */
- (instancetype)initWithSnapshot:(MCMTLFarCullSnapshot *)snapshot
                     budgetBytes:(uint64_t)budgetBytes error:(NSError **)error;
@property(nonatomic, readonly) MCMTLFarCullGeometry *geometry;
@property(nonatomic, readonly) id<MTLBuffer> shaderSections;
@property(nonatomic, readonly) id<MTLBuffer> directoryBuffer, tailBuffer;
@property(nonatomic, readonly) uint32_t directoryCount, tailWordCount, sectionCount, arenaCount;
@property(nonatomic, readonly) uint64_t gpuBufferBytes, cpuMetadataBytes, cpuScratchBytes, peakBuildBytes;
/* Out-of-range returns a zero descriptor; buffers are borrowed read-only. */
- (MCMTLFarFaceArena)arenaAtIndex:(NSUInteger)index;
/* CPU convenience/oracle access; reads shared immutable buffers, no GPU readback. */
- (uint32_t)ownerForFaceID:(uint32_t)faceID;
@end
