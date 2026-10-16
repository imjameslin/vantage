#import "mcmetal_farlookup.h"
#include <stdlib.h>
#include <string.h>

typedef struct { uint32_t first, last, owner; } FaceRange; // inclusive bounds
_Static_assert(sizeof(FaceRange) == 12, "Lookup scratch budget");

static void fail(NSError **error, NSInteger code, NSString *message) {
    if (error) *error = [NSError errorWithDomain:@"mcmetal.far.lookup" code:code
        userInfo:@{NSLocalizedDescriptionKey:message}];
}

static BOOL less(FaceRange a, FaceRange b) {
    if (a.first != b.first) return a.first < b.first;
    if (a.last != b.last) return a.last < b.last;
    return a.owner < b.owner;
}
static void sift(FaceRange *r, NSUInteger root, NSUInteger count) {
    while (count >= 2 && root <= (count - 2) / 2) {
        NSUInteger child = 2 * root + 1;
        if (child + 1 < count && less(r[child], r[child + 1])) ++child;
        if (!less(r[root], r[child])) break;
        FaceRange tmp = r[root]; r[root] = r[child]; r[child] = tmp; root = child;
    }
}
/* In-place heapsort: no qsort implementation-dependent auxiliary allocation. */
static void sortRanges(FaceRange *r, NSUInteger count) {
    for (NSUInteger i = count / 2; i; --i) sift(r, i - 1, count);
    for (NSUInteger end = count; end > 1; --end) {
        FaceRange tmp = r[0]; r[0] = r[end - 1]; r[end - 1] = tmp;
        sift(r, 0, end - 1);
    }
}

typedef struct { uint32_t *directory, *tails; uint64_t tailWords; } Pages;
static BOOL flushPage(Pages *p, uint64_t page, const uint32_t words[64]) {
    BOOL uniform = words[0] != 0;
    for (unsigned i = 1; i < 64 && uniform; ++i) uniform = words[i] == words[0];
    if (uniform) {
        if (p->directory) p->directory[page] = words[0];
    } else {
        if (p->tailWords > 0x80000000ull - 64) return NO;
        if (p->directory) {
            p->directory[page] = MCMTLFarFacePartial | (uint32_t)p->tailWords;
            memcpy(p->tails + p->tailWords, words, 64 * sizeof(uint32_t));
        }
        p->tailWords += 64;
    }
    return YES;
}
/* Two identical passes: count tails, then emit into GPU buffers. Full uniform
 * runs skip all face iteration; only boundary pages use the fixed stack array. */
static BOOL buildPages(const FaceRange *r, NSUInteger count, Pages *p) {
    uint64_t pending = UINT64_MAX;
    uint32_t words[64] = {0};
    for (NSUInteger i = 0; i < count; ++i) {
        uint64_t pos = r[i].first, end = (uint64_t)r[i].last + 1;
        while (pos < end) {
            uint64_t page = pos >> 6;
            if (pending != UINT64_MAX && pending != page) {
                if (!flushPage(p, pending, words)) return NO;
                pending = UINT64_MAX;
            }
            if (!(pos & 63) && end - pos >= 64) {
                uint64_t pages = (end - pos) >> 6;
                if (p->directory) for (uint64_t k = 0; k < pages; ++k) p->directory[page + k] = r[i].owner;
                pos += pages * 64;
            } else {
                if (pending == UINT64_MAX) { pending = page; memset(words, 0, sizeof words); }
                uint64_t stop = MIN(end, (page + 1) * 64);
                while (pos < stop) words[(pos++) & 63] = r[i].owner;
            }
        }
    }
    return pending == UINT64_MAX || flushPage(p, pending, words);
}

@implementation MCMTLFarFaceLookup {
    MCMTLFarFaceArena _arenas[MCMTLFarFaceMaxArenas];
    NSArray<id<MTLBuffer>> *_vertices; // exact independent retains, in arena order
}
- (instancetype)initWithSnapshot:(MCMTLFarCullSnapshot *)snapshot
                     budgetBytes:(uint64_t)budgetBytes error:(NSError **)error {
    if (error) *error = nil;
    if (!(self = [super init])) return nil;
    FaceRange *ranges = NULL;
    @try {
        if ([NSThread isMainThread]) { fail(error, 1, @"Face lookup construction is worker-only"); return nil; }
        _geometry = snapshot.geometry;
        _shaderSections = snapshot.shaderSections;
        id<MTLDevice> device = _shaderSections.device;
        if (!snapshot || !_geometry || !device || snapshot.count != _geometry.count || snapshot.count > 4096
            || !_shaderSections.length || _shaderSections.length % 16 || _shaderSections.length / 16 > (1u << 20)) {
            fail(error, 1, @"Invalid immutable far snapshot/section table"); return nil;
        }
        _sectionCount = (uint32_t)(_shaderSections.length / 16);
        _cpuMetadataBytes = sizeof _arenas;
        if (budgetBytes < _cpuMetadataBytes + 8) { fail(error, 2, @"Lookup budget cannot hold descriptors and empty tables"); return nil; }
        uint64_t count = 0;
        for (NSUInteger j = 0; j < snapshot.count; ++j) {
            MCMTLFarClusterInput *in = [snapshot drawAtIndex:j].input;
            NSUInteger n = in.items.length / 16;
            if (!in || !n || in.items.length % 16 || n > (1u << 26) || (in.layerCode & ~9u)
                || in.vertices.device != device || in.vertexOffset % 4 || in.vertexOffset > in.vertices.length) {
                fail(error, 1, @"Invalid original cluster input, layer or vertex range"); return nil;
            }
            if (n > (SIZE_MAX / sizeof(FaceRange)) - count) { fail(error, 2, @"Cluster scratch overflow"); return nil; }
            count += n;
        }
        _cpuScratchBytes = count * sizeof(FaceRange);
        if (_cpuScratchBytes > budgetBytes - _cpuMetadataBytes - 8) {
            fail(error, 2, @"Cluster scratch exceeds lookup peak budget"); return nil;
        }
        if (count && !(ranges = malloc((size_t)_cpuScratchBytes))) { fail(error, 3, @"Cannot allocate cluster scratch"); return nil; }
        NSUInteger used = 0;
        for (NSUInteger j = 0; j < snapshot.count; ++j) {
            MCMTLFarClusterInput *in = [snapshot drawAtIndex:j].input;
            uint32_t layout = (in.layerCode & 8u) ? MCMTLFarFaceLayoutCompact : MCMTLFarFaceLayoutBlock;
            uint32_t slot = 0;
            for (; slot < _arenaCount; ++slot) {
                MCMTLFarFaceArena a = _arenas[slot];
                if (a.vertices != in.vertices || a.vertexOffset != in.vertexOffset) continue;
                if (a.faceBase != in.faceBase || a.layout != layout) {
                    fail(error, 1, @"One vertex arena has incompatible base/layout meanings"); return nil;
                }
                break;
            }
            if (slot == _arenaCount) {
                if (_arenaCount == MCMTLFarFaceMaxArenas) { fail(error, 1, @"More than 64 unique vertex arenas"); return nil; }
                _arenas[_arenaCount++] = (MCMTLFarFaceArena){in.vertices, in.vertexOffset,
                    in.vertices.length - in.vertexOffset, in.faceBase, layout};
            }
            const uint32_t *items = in.items.bytes;
            for (NSUInteger c = 0; c < in.items.length / 16; ++c) {
                uint32_t first = items[4*c], section = items[4*c+1] & 0xfffffu;
                uint32_t quads = ((items[4*c+1] >> 20) & 63u) + 1;
                uint64_t q = first >> 2, end = q + quads;
                uint64_t stride = layout == MCMTLFarFaceLayoutCompact ? 48 : 112;
                if ((first & 3u) || section >= _sectionCount || end > _arenas[slot].vertexBytes / stride
                    || (uint64_t)in.faceBase + end > UINT32_MAX || used >= count) {
                    fail(error, 1, @"Cluster refers outside face ID, section or leased vertex bounds"); return nil;
                }
                uint32_t word = (section + 1u) | (slot << MCMTLFarFaceArenaShift)
                    | ((in.layerCode & 1u) << MCMTLFarFaceLayerShift);
                ranges[used++] = (FaceRange){(uint32_t)(in.faceBase + q + 1), (uint32_t)(in.faceBase + end), word};
            }
        }
        if (used != count) { fail(error, 1, @"Original cluster metadata changed during construction"); return nil; }
        sortRanges(ranges, used);
        NSUInteger merged = 0;
        for (NSUInteger i = 0; i < used; ++i) {
            FaceRange r = ranges[i];
            if (merged) {
                FaceRange *last = &ranges[merged - 1];
                if (r.first <= last->last && r.owner != last->owner) {
                    fail(error, 1, @"Overlapping face IDs have incompatible arena/section/layer meanings"); return nil;
                }
                if ((uint64_t)r.first <= (uint64_t)last->last + 1 && r.owner == last->owner) {
                    last->last = MAX(last->last, r.last); continue;
                }
            }
            ranges[merged++] = r;
        }
        _directoryCount = merged ? (ranges[merged - 1].last >> 6) + 1 : 0;
        Pages sizing = {0};
        if (!buildPages(ranges, merged, &sizing)) { fail(error, 2, @"Partial-page offsets exceed 31 bits"); return nil; }
        _tailWordCount = (uint32_t)sizing.tailWords;
        uint64_t directoryBytes = MAX(4ull, (uint64_t)_directoryCount * 4);
        uint64_t tailBytes = MAX(4ull, sizing.tailWords * 4);
        _gpuBufferBytes = directoryBytes + tailBytes;
        uint64_t cpuBytes = _cpuMetadataBytes + _cpuScratchBytes;
        if (directoryBytes > device.maxBufferLength || tailBytes > device.maxBufferLength
            || directoryBytes > SIZE_MAX || tailBytes > SIZE_MAX || _gpuBufferBytes > budgetBytes - cpuBytes) {
            fail(error, 2, [NSString stringWithFormat:@"Lookup tables exceed peak budget or Metal buffer capacity: directory=%llu tails=%llu CPU=%llu budget=%llu maxBuffer=%llu",
                (unsigned long long)directoryBytes,(unsigned long long)tailBytes,(unsigned long long)cpuBytes,
                (unsigned long long)budgetBytes,(unsigned long long)device.maxBufferLength]); return nil;
        }
        _peakBuildBytes = cpuBytes + _gpuBufferBytes;
        _directoryBuffer = [device newBufferWithLength:(NSUInteger)directoryBytes options:MTLResourceStorageModeShared];
        _tailBuffer = [device newBufferWithLength:(NSUInteger)tailBytes options:MTLResourceStorageModeShared];
        if (!_directoryBuffer.contents || !_tailBuffer.contents) { fail(error, 3, @"Cannot allocate shared lookup tables"); return nil; }
        memset(_directoryBuffer.contents, 0, (size_t)directoryBytes);
        memset(_tailBuffer.contents, 0, (size_t)tailBytes);
        Pages output = {_directoryBuffer.contents, _tailBuffer.contents, 0};
        if (!buildPages(ranges, merged, &output) || output.tailWords != sizing.tailWords) {
            fail(error, 1, @"Inconsistent lookup page counts"); return nil;
        }
        NSMutableArray<id<MTLBuffer>> *vertices = [NSMutableArray arrayWithCapacity:_arenaCount];
        for (uint32_t i = 0; i < _arenaCount; ++i) [vertices addObject:_arenas[i].vertices];
        _vertices = [vertices copy];
        return self;
    } @catch (NSException *e) {
        fail(error, 3, [NSString stringWithFormat:@"Face lookup construction: %@", e.reason]); return nil;
    } @finally { free(ranges); }
}
+ (BOOL)accessInstanceVariablesDirectly { return NO; }
- (MCMTLFarFaceArena)arenaAtIndex:(NSUInteger)index {
    return index < _arenaCount ? _arenas[index] : (MCMTLFarFaceArena){0};
}
- (uint32_t)ownerForFaceID:(uint32_t)faceID {
    if (!faceID || (faceID >> 6) >= _directoryCount) return 0;
    uint32_t word = ((const uint32_t *)_directoryBuffer.contents)[faceID >> 6];
    if (word & MCMTLFarFacePartial) {
        uint32_t offset = word & 0x7fffffffu;
        if ((offset & 63u) || _tailWordCount < 64 || offset > _tailWordCount - 64) return 0;
        word = ((const uint32_t *)_tailBuffer.contents)[offset + (faceID & 63u)];
    }
    uint32_t section = word & MCMTLFarFaceSectionMask;
    if ((word & 0xf0000000u) || !section || section > _sectionCount
        || ((word >> MCMTLFarFaceArenaShift) & 63u) >= _arenaCount) return 0;
    return word;
}
@end
