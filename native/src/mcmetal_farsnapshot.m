#import "mcmetal_farsnapshot.h"
#import "mcmetal_internal.h"
#include <limits.h>
#include <math.h>
#include <stdatomic.h>

static NSError *snapshot_error(NSString *text) {
    return [NSError errorWithDomain:@"mcmetal.far.snapshot" code:1
                          userInfo:@{NSLocalizedDescriptionKey:text}];
}
static uint64_t snapshot_handle(id object) { return (uint64_t)(uintptr_t)(__bridge void *)object; }
static BOOL room(NSUInteger length, NSUInteger offset, uint64_t bytes) {
    return offset <= length && bytes <= length - offset;
}

@implementation MCMTLFarClusterInput {
    id _lifetime;
}
- (instancetype)initWithItems:(NSData *)items
                     vertices:(id<MTLBuffer>)vertices vertexOffset:(NSUInteger)vertexOffset
                       indices:(id<MTLBuffer>)indices indexOffset:(NSUInteger)indexOffset
                     layerCode:(uint32_t)layerCode faceBase:(uint32_t)faceBase
                      lifetime:(id)lifetime {
    if (!items || !vertices || !indices || !lifetime) return nil;
    if ((self = [super init])) {
        // Always own bytes, even if the source NSData was a no-copy wrapper around live memory.
        _items = [[NSData alloc] initWithBytes:items.bytes length:items.length];
        _vertices = vertices; _indices = indices; _vertexOffset = vertexOffset; _indexOffset = indexOffset;
        _layerCode = layerCode; _faceBase = faceBase; _lifetime = lifetime;
    }
    return self;
}
@end

static NSData *snapshot_view(NSData *view, NSError **error) {
    if (view.length != 128) {
        if (error) *error = snapshot_error(@"Invalid far snapshot view dimensions");
        return nil;
    }
    NSData *copy = [[NSData alloc] initWithBytes:view.bytes length:128];
    const uint32_t *v = copy.bytes;
    if ((v[21] & 4u) == 0 || (v[21] & ~15u) != 0) {
        if (error) *error = snapshot_error(@"Complete far capture needs persistent sections without near-depth occlusion");
        return nil;
    }
    // One integer flag and one dispatch count/padding lane; everything else is a float.
    const float *vf = copy.bytes;
    for (NSUInteger k = 0; k < 32; k++) if (k != 21 && k != 23 && !isfinite(vf[k])) {
        if (error) *error = snapshot_error(@"Nonfinite far capture view");
        return nil;
    }
    return copy;
}

@interface MCMTLFarCullGeometry () {
@package
    NSArray<MCMTLFarClusterInput *> *_inputs;
    NSArray<id<MTLBuffer>> *_resources;
    NSData *_jobs; // Immutable job templates: input handles and validated capacities only.
    id<MTLBuffer> _shaderSections;
    id<MTLDevice> _metalDevice;
    _Atomic(unsigned) _liveSnapshots;
}
- (BOOL)reserveSnapshot;
- (void)releaseSnapshot;
@end

@implementation MCMTLFarCullGeometry
- (instancetype)initWithDevice:(MCMTLDevice *)device
                         inputs:(NSArray<MCMTLFarClusterInput *> *)inputs
                   cullSections:(NSData *)cullSections
                 shaderSections:(NSData *)shaderSections
                    budgetBytes:(uint64_t)budgetBytes error:(NSError **)error {
    if (error) *error = nil;
    if (!(self = [super init])) return nil;
    if (!device || !device->device || !inputs || inputs.count > 4096 || !cullSections.length || cullSections.length % 32
        || cullSections.length / 32 > (1u << 20) || shaderSections.length != cullSections.length / 2
        || !budgetBytes) {
        if (error) *error = snapshot_error(@"Invalid far geometry table dimensions/budget");
        return nil;
    }
    _inputs = [inputs copy];
    _cullDevice = device;
    _metalDevice = device->device;
    _count = inputs.count;
    _budgetBytes = budgetBytes;
    _immutableBufferBytes = cullSections.length + shaderSections.length;
    NSMutableData *capacities = [NSMutableData dataWithLength:_count * sizeof(uint32_t)];
    uint32_t *caps = capacities.mutableBytes;
    NSMutableData *idRanges = [NSMutableData dataWithLength:_count * 2 * sizeof(uint64_t)];
    uint64_t *ranges = idRanges.mutableBytes;
    // Validate ALL input bounds before any Metal allocation/encoding. Kernel data cannot escape its snapshots.
    for (NSUInteger j = 0; j < _count; j++) {
        MCMTLFarClusterInput *in = _inputs[j];
        NSUInteger n = in.items.length / 16;
        BOOL compact = (in.layerCode & 8u) != 0;
        if (!n || in.items.length % 16 || n > (1u << 26) || (in.layerCode & ~31u)
            || (in.layerCode & 7u) > 1 || in.vertices.device != device->device || in.indices.device != device->device
            || in.vertexOffset % 4 || in.indexOffset % 4) {
            if (error) *error = snapshot_error(@"Unsupported or invalid opaque far cluster input");
            return nil;
        }
        const uint32_t *items = in.items.bytes;
        uint64_t quads = 0;
        uint64_t firstID = UINT64_MAX, lastID = 0;
        for (NSUInteger c = 0; c < n; c++) {
            uint32_t first = items[c * 4], section = items[c * 4 + 1] & 0xfffffu;
            uint32_t q = ((items[c * 4 + 1] >> 20) & 63u) + 1;
            uint64_t endFace = (uint64_t)(first >> 2) + q;
            // LodCompact.RECORD_BYTES = 48 (three uint4), BLOCK = four 28-byte vertices.
            if ((first & 3u) || section >= cullSections.length / 32
                || !room(in.vertices.length, in.vertexOffset, endFace * (compact ? 48u : 112u))
                || (uint64_t)in.faceBase + endFace > UINT32_MAX) {
                if (error) *error = snapshot_error(@"Cluster refers outside geometry/section/visibility-ID range");
                return nil;
            }
            firstID = MIN(firstID, (uint64_t)in.faceBase + (first >> 2) + 1);
            lastID = MAX(lastID, (uint64_t)in.faceBase + endFace);
            quads += q;
        }
        for (NSUInteger k = 0; k < j; k++) {
            MCMTLFarClusterInput *other = _inputs[k];
            BOOL sameArena = other.vertices == in.vertices && other.vertexOffset == in.vertexOffset;
            if ((sameArena && (other.faceBase != in.faceBase || ((other.layerCode ^ in.layerCode) & 8u)))
                || (!sameArena && firstID <= ranges[2*k+1] && ranges[2*k] <= lastID)) {
                if (error) *error = snapshot_error(@"Visibility IDs alias different arena geometry");
                return nil;
            }
        }
        ranges[2*j] = firstID; ranges[2*j+1] = lastID;
        if (quads > UINT32_MAX / 6u || !room(in.indices.length, in.indexOffset, quads * 24u)) {
            if (error) *error = snapshot_error(@"Far snapshot index capacity is too small");
            return nil;
        }
        caps[j] = (uint32_t)quads;
        _immutableBufferBytes += n * 16ull;
        // Output8 + scratch8 per cluster; quad list4; Shared indirect arguments32.
        _snapshotBufferBytes += n * 16ull + quads * 4ull + 32ull;
        if (in.items.length > _metalDevice.maxBufferLength || quads * 4ull > _metalDevice.maxBufferLength
            || _metalDevice.maxBufferLength < 32) {
            if (error) *error = snapshot_error(@"Far geometry exceeds Metal buffer capacity");
            return nil;
        }
    }
    if (_immutableBufferBytes > budgetBytes || _snapshotBufferBytes > budgetBytes - _immutableBufferBytes
        || cullSections.length > _metalDevice.maxBufferLength || shaderSections.length > _metalDevice.maxBufferLength) {
        if (error) *error = snapshot_error(@"Far geometry and one snapshot exceed buffer budget/capacity");
        return nil;
    }
    _snapshotCapacity = _snapshotBufferBytes ? MIN(3ull, (budgetBytes - _immutableBufferBytes) / _snapshotBufferBytes) : 3;
    atomic_init(&_liveSnapshots, 0);
    NSMutableArray<id<MTLBuffer>> *resources = [NSMutableArray array];
    id<MTLBuffer> (^copyBytes)(NSData *) = ^id<MTLBuffer>(NSData *data) {
        if (data.length > device->device.maxBufferLength) return nil;
        id<MTLBuffer> b = [device->device newBufferWithBytes:data.bytes length:data.length options:MTLResourceStorageModeShared];
        if (b) [resources addObject:b];
        return b;
    };
    id<MTLBuffer> table = copyBytes(cullSections);
    _shaderSections = copyBytes(shaderSections);
    if (!table || !_shaderSections) {
        if (error) *error = snapshot_error(@"Cannot allocate far section tables");
        return nil;
    }
    NSMutableData *templates = [NSMutableData dataWithLength:_count * sizeof(mcmtl_cluster_job)];
    mcmtl_cluster_job *jobs = templates.mutableBytes;
    for (NSUInteger j = 0; j < _count; j++) {
        MCMTLFarClusterInput *in = _inputs[j];
        NSUInteger n = in.items.length / 16;
        id<MTLBuffer> items = copyBytes(in.items);
        if (!items) {
            if (error) *error = snapshot_error(@"Cannot allocate immutable far cluster items");
            return nil;
        }
        jobs[j] = (mcmtl_cluster_job){ .itemsBuffer = snapshot_handle(items), .sectionsBuffer = snapshot_handle(table),
            .clusterCount = (uint32_t)n, .quadCapacity = caps[j] };
    }
    _jobs = [templates copy];
    _resources = [resources copy];
    return self;
}
- (BOOL)reserveSnapshot {
    // Fixed work, no blocking/spinning. A racing decline can be retried on a later refresh.
    unsigned previous = atomic_fetch_add_explicit(&_liveSnapshots, 1, memory_order_relaxed);
    if (previous < _snapshotCapacity) return YES;
    atomic_fetch_sub_explicit(&_liveSnapshots, 1, memory_order_relaxed);
    return NO;
}
- (void)releaseSnapshot { atomic_fetch_sub_explicit(&_liveSnapshots, 1, memory_order_relaxed); }
@end

@implementation MCMTLFarCullSnapshot {
    NSArray<id<MTLBuffer>> *_resources;
    NSData *_view;
    NSMutableData *_jobs;
    BOOL _encoded, _reserved;
}
- (instancetype)initWithDevice:(MCMTLDevice *)device
                         inputs:(NSArray<MCMTLFarClusterInput *> *)inputs
                   cullSections:(NSData *)cullSections shaderSections:(NSData *)shaderSections
                           view:(NSData *)view budgetBytes:(uint64_t)budgetBytes error:(NSError **)error {
    if (error) *error = nil;
    // Preserve fail-closed view rejection before any Metal allocation in the legacy path.
    NSData *copy = snapshot_view(view, error);
    if (!copy) return nil;
    MCMTLFarCullGeometry *geometry = [[MCMTLFarCullGeometry alloc] initWithDevice:device inputs:inputs
        cullSections:cullSections shaderSections:shaderSections budgetBytes:budgetBytes error:error];
    if (!geometry) return nil;
    return [self initWithGeometry:geometry view:copy budgetBytes:budgetBytes error:error];
}
- (instancetype)initWithGeometry:(MCMTLFarCullGeometry *)geometry view:(NSData *)view
                     budgetBytes:(uint64_t)budgetBytes error:(NSError **)error {
    if (error) *error = nil;
    if (!(self = [super init])) return nil;
    if (!geometry || geometry.cullDevice->device != geometry->_metalDevice
        || geometry.immutableBufferBytes > budgetBytes
        || geometry.snapshotBufferBytes > budgetBytes - geometry.immutableBufferBytes) {
        if (error) *error = snapshot_error(@"Invalid far geometry device or snapshot buffer budget");
        return nil;
    }
    _view = snapshot_view(view, error);
    if (!_view) return nil;
    if (![geometry reserveSnapshot]) {
        if (error) *error = [NSError errorWithDomain:@"mcmetal.far.snapshot" code:MCMTLFarSnapshotCapacityError
            userInfo:@{NSLocalizedDescriptionKey:@"Far geometry live snapshot capacity exhausted"}];
        return nil;
    }
    _geometry = geometry; _reserved = YES;
    _count = geometry.count;
    _ownedBufferBytes = geometry.immutableBufferBytes + geometry.snapshotBufferBytes;
    _shaderSections = geometry->_shaderSections;
    _jobs = [geometry->_jobs mutableCopy];
    mcmtl_cluster_job *jobs = _jobs.mutableBytes;
    NSMutableArray<id<MTLBuffer>> *resources = [NSMutableArray arrayWithCapacity:_count * 4];
    id<MTLBuffer> (^allocate)(NSUInteger) = ^id<MTLBuffer>(NSUInteger bytes) {
        id<MTLBuffer> b = [geometry->_metalDevice newBufferWithLength:bytes options:MTLResourceStorageModeShared];
        if (b) [resources addObject:b];
        return b;
    };
    for (NSUInteger j = 0; j < _count; j++) {
        NSUInteger n = jobs[j].clusterCount;
        id<MTLBuffer> out = allocate(n * 8), scratch = allocate(n * 8);
        id<MTLBuffer> quads = allocate((NSUInteger)jobs[j].quadCapacity * 4), args = allocate(32);
        if (!out || !scratch || !quads || !args) {
            if (error) *error = snapshot_error(@"Cannot allocate private far cull outputs");
            return nil;
        }
        jobs[j].outItemsBuffer = snapshot_handle(out); jobs[j].scratchBuffer = snapshot_handle(scratch);
        jobs[j].quadsBuffer = snapshot_handle(quads); jobs[j].argsBuffer = snapshot_handle(args);
    }
    _resources = [resources copy];
    return self;
}
- (void)dealloc { if (_reserved) [_geometry releaseSnapshot]; }
- (MCMTLFarDraw)drawAtIndex:(NSUInteger)index {
    if (index >= _count) return (MCMTLFarDraw){0};
    const mcmtl_cluster_job *jobs = _jobs.bytes;
    return (MCMTLFarDraw){ .input = _geometry->_inputs[index], .quads = MCMTL_BUFFER(jobs[index].quadsBuffer),
        .items = MCMTL_BUFFER(jobs[index].outItemsBuffer), .arguments = MCMTL_BUFFER(jobs[index].argsBuffer),
        .quadCapacity = jobs[index].quadCapacity };
}
- (MCMTLDevice *)cullDevice { return _geometry.cullDevice; }
- (BOOL)encodeCull:(id<MTLCommandBuffer>)buffer
         readyEvent:(id<MTLSharedEvent>)readyEvent readyValue:(uint64_t)readyValue error:(NSError **)error {
    if (error) *error = nil;
    MCMTLDevice *device = _geometry.cullDevice;
    if (_encoded || !buffer || buffer.status != MTLCommandBufferStatusNotEnqueued
        || device->device != _geometry->_metalDevice
        || buffer.commandQueue.device != device->device || !readyEvent
        || (readyEvent.device && readyEvent.device != device->device) || !readyValue) {
        if (error) *error = snapshot_error(@"Far cull needs one fresh command buffer and a committed upload dependency");
        return NO;
    }
    _encoded = YES;
    [buffer encodeWaitForEvent:readyEvent value:readyValue];
    int32_t status = mcmtl_clusters_encode(device, buffer, _jobs.bytes, (uint32_t)_count, _view.bytes, (uint32_t)_view.length, NULL, NULL, NULL);
    if (status != MCMTL_OK) {
        if (error) *error = snapshot_error([NSString stringWithFormat:@"Far cull encoding failed (%d): %s", status, mcmtl_last_error()]);
        return NO;
    }
    return YES;
}
@end
