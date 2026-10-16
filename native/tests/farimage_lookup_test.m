/* Default: CPU-only real snapshots with fake Metal buffers, no queues/GPU.
 * --gpu: also run one bounded real-Metal lookup kernel; use tools/gpulock.py
 * functional for this option. The core test runner uses the CPU-only default.
 */
#import "mcmetal_farlookup.h"
#import "mcmetal_internal.h"
#include <dispatch/dispatch.h>
#include <stdio.h>
#include <string.h>

static unsigned checks, failures;
#define CHECK(x) do { ++checks; if (!(x)) { ++failures; fprintf(stderr,"FAIL %d: %s\n",__LINE__,#x); } } while (0)

@interface LookupBuffer : NSObject
@property(nonatomic) NSUInteger length;
@property(nonatomic, strong) id device;
@property(nonatomic, strong) NSMutableData *storage;
- (void *)contents;
@end
@implementation LookupBuffer
- (void *)contents { return _storage.mutableBytes; }
@end
@interface LookupDevice : NSObject
@property(nonatomic) NSUInteger maxBufferLength, allocations;
@property(nonatomic) BOOL failAllocation;
@end
@implementation LookupDevice
- (id<MTLBuffer>)newBufferWithLength:(NSUInteger)length options:(MTLResourceOptions)options {
    ++_allocations;
    if (_failAllocation) { _failAllocation = NO; return nil; }
    LookupBuffer *b = [LookupBuffer new]; b.length = length; b.device = self;
    b.storage = [NSMutableData dataWithLength:length]; return (id)b;
}
- (id<MTLBuffer>)newBufferWithBytes:(const void *)bytes length:(NSUInteger)length options:(MTLResourceOptions)options {
    id<MTLBuffer> b = [self newBufferWithLength:length options:options];
    if (b) memcpy(b.contents, bytes, length);
    return b;
}
@end

/* Negative-test seam: substitute invalid ORIGINAL inputs without constructing
 * invalid GPU snapshots. Normal/lifetime tests use the actual snapshot class. */
@interface LookupSourceStub : NSObject
@property(nonatomic, strong) MCMTLFarCullGeometry *geometry;
@property(nonatomic, strong) id<MTLBuffer> shaderSections;
@property(nonatomic, copy) NSArray<MCMTLFarClusterInput *> *inputs;
- (NSUInteger)count;
- (MCMTLFarDraw)drawAtIndex:(NSUInteger)i;
@end
@implementation LookupSourceStub
- (NSUInteger)count { return _inputs.count; }
- (MCMTLFarDraw)drawAtIndex:(NSUInteger)i { return (MCMTLFarDraw){.input=_inputs[i]}; }
@end

static NSMutableData *itemData(uint32_t firstQuad, uint32_t count, uint32_t section) {
    uint32_t words[4] = {firstQuad * 4, section | ((count - 1) << 20) | (1u << 29), 0, 0};
    return [NSMutableData dataWithBytes:words length:sizeof words];
}
static MCMTLFarClusterInput *input(NSData *items, id<MTLBuffer> vb, NSUInteger offset,
                                  uint32_t base, uint32_t layer, id lease) {
    // Indices are retained/validated by snapshots but never consumed by lookup.
    NSUInteger n = items.length / 16, quads = 0;
    const uint32_t *words = items.bytes;
    for (NSUInteger c = 0; c < n; ++c) quads += ((words[c*4+1] >> 20) & 63u) + 1;
    id<MTLBuffer> ib = [vb.device newBufferWithLength:MAX(4,quads*24) options:MTLResourceStorageModeShared];
    return [[MCMTLFarClusterInput alloc] initWithItems:items vertices:vb vertexOffset:offset
        indices:ib indexOffset:0 layerCode:layer faceBase:base lifetime:lease];
}
static MCMTLFarCullSnapshot *snapshot(MCMTLDevice *device, NSArray *inputs, uint32_t sections) {
    NSMutableData *cull = [NSMutableData dataWithLength:sections*32ull];
    NSMutableData *shader = [NSMutableData dataWithLength:sections*16ull];
    if (sections) ((uint32_t *)shader.mutableBytes)[0] = 173;
    uint32_t view[32] = {0}; view[21] = 4;
    NSError *error = nil;
    MCMTLFarCullSnapshot *s = [[MCMTLFarCullSnapshot alloc] initWithDevice:device inputs:inputs
        cullSections:cull shaderSections:shader view:[NSData dataWithBytes:view length:sizeof view]
        budgetBytes:8u<<20 error:&error];
    CHECK(s && !error); return s;
}
static MCMTLFarFaceLookup *lookup(MCMTLFarCullSnapshot *s, uint64_t budget) {
    NSError *error = nil;
    MCMTLFarFaceLookup *l = [[MCMTLFarFaceLookup alloc] initWithSnapshot:s budgetBytes:budget error:&error];
    CHECK(l && !error); return l;
}

/* Independent linear oracle: scans original clusters and compares decoded
 * meaning/buffer address, never uses the production sort, pages or owner packer. */
static void checkID(MCMTLFarFaceLookup *l, NSArray<MCMTLFarClusterInput *> *inputs, uint32_t id) {
    MCMTLFarClusterInput *found = nil; uint32_t section = 0;
    if (id) for (MCMTLFarClusterInput *in in inputs) {
        const uint32_t *w = in.items.bytes;
        for (NSUInteger c = 0; c < in.items.length / 16; ++c) {
            uint64_t first = (uint64_t)in.faceBase + w[4*c] / 4 + 1;
            uint64_t count = ((w[4*c+1] >> 20) & 63) + 1;
            if (id >= first && (uint64_t)id - first < count) { found = in; section = w[4*c+1] & 0xfffff; }
        }
    }
    uint32_t word = [l ownerForFaceID:id];
    CHECK((word != 0) == (found != nil));
    if (!found || !word) return;
    CHECK((word & 0x001fffffu) == section + 1);
    CHECK(((word >> 27) & 1) == (found.layerCode & 1));
    CHECK((word & 0xf0000000u) == 0);
    MCMTLFarFaceArena a = [l arenaAtIndex:(word >> 21) & 63];
    CHECK(a.vertices == found.vertices && a.vertexOffset == found.vertexOffset && a.faceBase == found.faceBase);
    CHECK(a.layout == ((found.layerCode & 8) ? 2u : 1u));
    uint64_t byte = a.vertexOffset + ((uint64_t)id - a.faceBase - 1) * (a.layout == 2 ? 48 : 112);
    CHECK(byte >= a.vertexOffset && byte + (a.layout == 2 ? 48 : 112) <= a.vertices.length);
}
static void reject(MCMTLFarCullSnapshot *s, NSArray *inputs, uint64_t budget, LookupDevice *device) {
    LookupSourceStub *stub = [LookupSourceStub new];
    stub.geometry = s.geometry; stub.shaderSections = s.shaderSections; stub.inputs = inputs;
    NSUInteger before = device.allocations;
    NSError *error = nil;
    CHECK([[MCMTLFarFaceLookup alloc] initWithSnapshot:(id)stub budgetBytes:budget error:&error] == nil && error);
    CHECK(device.allocations == before); // No table allocation before all proofs.
}

static void normalCases(MCMTLDevice *device, LookupDevice *fake) { @autoreleasepool {
    id<MTLBuffer> a = [device->device newBufferWithLength:300*112+16 options:MTLResourceStorageModeShared];
    id<MTLBuffer> b = [device->device newBufferWithLength:160*48+32 options:MTLResourceStorageModeShared];
    // Unaligned base=60: split page 0, complete uniform page 1, mixed page 2.
    // Deliberately unsorted, overlapping compatible and adjacent ranges merge.
    NSMutableData *items = itemData(67,32,2);
    [items appendData:itemData(3,64,2)];
    [items appendData:itemData(0,3,2)];
    [items appendData:itemData(20,20,2)];
    MCMTLFarClusterInput *solid = input(items,a,16,60,0,@"lease-a");
    MCMTLFarClusterInput *cutout = input(itemData(99,30,3),a,16,60,1,@"lease-a");
    MCMTLFarClusterInput *compact = input(itemData(2,64,1),b,32,997,9,@"lease-b");
    // A distant singleton exercises sparse directories without a dense face map.
    MCMTLFarClusterInput *sparse = input(itemData(100,1,0),b,32,997,8,@"lease-b");
    NSArray *inputs = @[solid,cutout,compact,sparse];
    MCMTLFarCullSnapshot *s = snapshot(device,inputs,4);
    if (!s) return;
    // Poison every cull output; lookup must use the ORIGINAL input items.
    for (NSUInteger i=0; i<s.count; ++i) {
        MCMTLFarDraw d = [s drawAtIndex:i];
        memset(d.items.contents,0xff,d.items.length); memset(d.quads.contents,0xff,d.quads.length);
    }
    memset(items.mutableBytes,0xff,items.length); // input already copied this metadata
    MCMTLFarFaceLookup *l = lookup(s,1u<<20);
    if (!l) return;
    CHECK(l.arenaCount == 2 && l.sectionCount == 4 && l.geometry == s.geometry && l.shaderSections == s.shaderSections);
    CHECK(l.cpuScratchBytes == 7*12 && l.cpuMetadataBytes == 64*sizeof(MCMTLFarFaceArena));
    CHECK(l.directoryCount == (1098u>>6)+1 && l.tailWordCount == 5*64);
    CHECK(((const uint32_t *)l.directoryBuffer.contents)[1] == 3); // section 2, arena 0, solid
    CHECK(((const uint32_t *)l.directoryBuffer.contents)[3] == 0);
    CHECK(l.peakBuildBytes == l.cpuMetadataBytes+l.cpuScratchBytes+l.gpuBufferBytes);
    CHECK(l.gpuBufferBytes == l.directoryBuffer.length+l.tailBuffer.length);
    CHECK([l arenaAtIndex:2].vertices == nil && [l ownerForFaceID:UINT32_MAX] == 0);
    for (uint32_t id=0; id<1200; ++id) checkID(l,inputs,id);
    MCMTLFarFaceLookup *exact = lookup(s,l.peakBuildBytes);
    CHECK(exact.gpuBufferBytes == l.gpuBufferBytes);
    NSUInteger before = fake.allocations; NSError *error = nil;
    CHECK([[MCMTLFarFaceLookup alloc] initWithSnapshot:s budgetBytes:l.peakBuildBytes-1 error:&error] == nil && error);
    CHECK(fake.allocations == before);
    CHECK([[MCMTLFarFaceLookup alloc] initWithSnapshot:s budgetBytes:0 error:NULL] == nil);
    CHECK(fake.allocations == before);
    NSUInteger max = fake.maxBufferLength; fake.maxBufferLength = l.tailBuffer.length-1;
    CHECK([[MCMTLFarFaceLookup alloc] initWithSnapshot:s budgetBytes:1u<<20 error:&error] == nil && error);
    CHECK(fake.allocations == before); fake.maxBufferLength = max;
    fake.failAllocation = YES;
    CHECK([[MCMTLFarFaceLookup alloc] initWithSnapshot:s budgetBytes:1u<<20 error:&error] == nil && error);
} }

static void invalid(MCMTLDevice *device, LookupDevice *fake) { @autoreleasepool {
    id<MTLBuffer> a = [device->device newBufferWithLength:200*112 options:MTLResourceStorageModeShared];
    MCMTLFarClusterInput *one = input(itemData(0,4,0),a,0,7,0,@"lease");
    MCMTLFarCullSnapshot *s = snapshot(device,@[one],2);
    if (!s) return;
    // Real snapshot permits duplicate same-arena input ranges; lookup rejects a
    // different section or layer meaning even though arena address agrees.
    for (uint32_t different=0; different<2; ++different) {
        MCMTLFarClusterInput *bad = input(itemData(2,4,different?0:1),a,0,7,different?1:0,@"lease");
        MCMTLFarCullSnapshot *conflict = snapshot(device,@[one,bad],2);
        reject(conflict,@[one,bad],1u<<20,fake);
    }
    reject(s,@[input(itemData(0,1,2),a,0,7,0,@"lease")],1u<<20,fake);
    reject(s,@[input(itemData(200,1,0),a,0,7,0,@"lease")],1u<<20,fake);
    reject(s,@[input(itemData(0,4,0),a,0,UINT32_MAX-2,0,@"lease")],1u<<20,fake);
    reject(s,@[input(itemData(0,1,0),a,3,7,0,@"lease")],1u<<20,fake);
    reject(s,@[input(itemData(0,1,0),a,a.length+4,7,0,@"lease")],1u<<20,fake);
    reject(s,@[input(itemData(0,1,0),a,0,7,16,@"lease")],1u<<20,fake);
    NSMutableData *bad = itemData(0,1,0); ((uint32_t *)bad.mutableBytes)[0] = 1;
    reject(s,@[input(bad,a,0,7,0,@"lease")],1u<<20,fake);
    reject(s,@[input([NSMutableData dataWithLength:15],a,0,7,0,@"lease")],1u<<20,fake);
    // Large sparse ID rejected by directory budget before any huge allocation.
    reject(s,@[input(itemData(0,1,0),a,0,UINT32_MAX-1,0,@"lease")],1u<<20,fake);
    id<MTLBuffer> other = [device->device newBufferWithLength:112 options:MTLResourceStorageModeShared];
    MCMTLFarClusterInput *same = input(itemData(0,1,0),a,0,7,0,@"lease");
    MCMTLFarCullSnapshot *two = snapshot(device,@[same,same],2);
    // Distinct buffers cannot claim the same ID, even with equal section/layer.
    reject(two,@[same,input(itemData(0,1,0),other,0,7,0,@"lease")],1u<<20,fake);
    reject(two,@[same,input(itemData(4,1,0),a,0,8,0,@"lease")],1u<<20,fake);
    reject(two,@[same,input(itemData(4,1,0),a,0,7,8,@"lease")],1u<<20,fake);
    LookupBuffer *foreign = [LookupBuffer new]; foreign.device = [NSObject new]; foreign.length = 112;
    MCMTLFarClusterInput *foreignInput = [[MCMTLFarClusterInput alloc] initWithItems:itemData(0,1,0)
        vertices:(id)foreign vertexOffset:0 indices:one.indices indexOffset:0 layerCode:0 faceBase:7 lifetime:@"lease"];
    reject(s,@[foreignInput],1u<<20,fake);
} }

static void limitsAndLifetime(MCMTLDevice *device, LookupDevice *fake) { @autoreleasepool {
    NSMutableArray *inputs = [NSMutableArray array];
    for (uint32_t i=0; i<65; ++i) {
        id<MTLBuffer> v = [device->device newBufferWithLength:112 options:MTLResourceStorageModeShared];
        [inputs addObject:input(itemData(0,1,0),v,0,i*100,i&1,@"lease")];
    }
    MCMTLFarCullSnapshot *sixtyFive = snapshot(device,inputs,1);
    reject(sixtyFive,inputs,1u<<20,fake);
    [inputs removeLastObject];
    MCMTLFarCullSnapshot *sixtyFour = snapshot(device,inputs,1);
    MCMTLFarFaceLookup *all = lookup(sixtyFour,1u<<20);
    CHECK(all.arenaCount == 64);
    checkID(all,inputs,6301);
    MCMTLFarFaceLookup *empty = lookup(snapshot(device,@[],1),1u<<20);
    CHECK(empty.arenaCount == 0 && empty.directoryCount == 0 && empty.tailWordCount == 0 && empty.gpuBufferBytes == 8);
    CHECK([empty ownerForFaceID:0] == 0 && [empty ownerForFaceID:1] == 0);

    __weak id weakLease = nil, weakVertex = nil, weakSections = nil, weakOutput = nil;
    __weak MCMTLFarCullSnapshot *weakSnapshot = nil;
    __weak MCMTLFarCullGeometry *weakGeometry = nil;
    MCMTLFarFaceLookup * __attribute__((objc_precise_lifetime)) held = nil;
    @autoreleasepool {
        NSObject *lease = [NSObject new]; weakLease = lease;
        id<MTLBuffer> v = [device->device newBufferWithLength:112 options:MTLResourceStorageModeShared]; weakVertex = v;
        MCMTLFarCullSnapshot *s = snapshot(device,@[input(itemData(0,1,0),v,0,0,0,lease)],1);
        weakSnapshot = s; weakGeometry = s.geometry; weakSections = s.shaderSections; weakOutput = [s drawAtIndex:0].items;
        held = lookup(s,1u<<20);
    }
    @autoreleasepool {
        CHECK(!weakSnapshot && !weakOutput);
        CHECK(weakGeometry && weakLease && weakVertex && weakSections);
        CHECK([held ownerForFaceID:1] == 1 && ((uint32_t *)held.shaderSections.contents)[0] == 173);
        held = nil;
    } // Drain temporary strong/autoreleased loads of the weak references too.
    CHECK(!weakGeometry && !weakLease && !weakVertex && !weakSections);
} }

static uint32_t randomWord(uint32_t *state) { *state = *state*1664525u+1013904223u; return *state; }
static void sampledOracle(MCMTLDevice *device) { @autoreleasepool {
    id<MTLBuffer> vb = [device->device newBufferWithLength:8192*112 options:MTLResourceStorageModeShared];
    NSMutableArray *inputs = [NSMutableArray array]; uint32_t state=12345, q=0;
    // 96 nonoverlapping ranges, random gaps/counts/sections/layers, reverse order.
    for (unsigned i=0; i<96; ++i) {
        q += randomWord(&state)%17;
        uint32_t n=1+randomWord(&state)%64, section=randomWord(&state)%7;
        [inputs insertObject:input(itemData(q,n,section),vb,0,37,i&1,@"lease") atIndex:0]; q+=n;
    }
    MCMTLFarFaceLookup *l = lookup(snapshot(device,inputs,7),1u<<20);
    if (!l) return;
    for (unsigned i=0; i<1024; ++i) checkID(l,inputs,randomWord(&state)%(q+128));
    for (MCMTLFarClusterInput *in in inputs) {
        const uint32_t *w=in.items.bytes; uint32_t first=38+w[0]/4, n=((w[1]>>20)&63)+1;
        checkID(l,inputs,first-1); checkID(l,inputs,first); checkID(l,inputs,first+n-1); checkID(l,inputs,first+n);
    }
} }

static void gpu(void) { @autoreleasepool {
    MCMTLDevice *device = [MCMTLDevice new]; device->device = MTLCreateSystemDefaultDevice();
    CHECK(device->device != nil); if (!device->device) return;
    id<MTLBuffer> v = [device->device newBufferWithLength:160*112 options:MTLResourceStorageModeShared];
    NSMutableData *items = itemData(3,64,0); [items appendData:itemData(0,3,0)];
    NSArray *inputs = @[input(items,v,0,60,0,@"gpu lease"),input(itemData(67,20,1),v,0,60,1,@"gpu lease")];
    MCMTLFarFaceLookup *l = lookup(snapshot(device,inputs,2),1u<<20);
    if (!l) return;
    // One 256-thread dispatch: independently decode directory/tail words.
    NSString *source = @"#include <metal_stdlib>\nusing namespace metal;\n"
        "kernel void lookup(device const uint *d [[buffer(0)]], device const uint *t [[buffer(1)]],"
        "device uint *out [[buffer(2)]], constant uint4 &n [[buffer(3)]], uint id [[thread_position_in_grid]]) {"
        "uint w=0; if(id && (id>>6)<n.x) { w=d[id>>6]; if(w&0x80000000u) { uint o=w&0x7fffffffu;"
        "w=(!(o&63u) && n.y>=64 && o<=n.y-64)?t[o+(id&63u)]:0; }"
        "uint s=w&0x1fffffu; if((w&0xf0000000u)||!s||s>n.z||((w>>21)&63u)>=n.w) w=0; } out[id]=w; }";
    NSError *error=nil;
    id<MTLLibrary> lib=[device->device newLibraryWithSource:source options:nil error:&error]; CHECK(lib && !error);
    if (!lib) return;
    id<MTLComputePipelineState> pso=[device->device newComputePipelineStateWithFunction:[lib newFunctionWithName:@"lookup"] error:&error];
    CHECK(pso && !error); if (!pso) return;
    id<MTLBuffer> out=[device->device newBufferWithLength:256*4 options:MTLResourceStorageModeShared];
    id<MTLCommandQueue> queue=[device->device newCommandQueue];
    id<MTLCommandBuffer> cb=[queue commandBuffer]; id<MTLComputeCommandEncoder> enc=[cb computeCommandEncoder];
    CHECK(out && queue && cb && enc); if (!out || !enc) return;
    uint32_t bounds[4]={l.directoryCount,l.tailWordCount,l.sectionCount,l.arenaCount};
    [enc setComputePipelineState:pso]; [enc setBuffer:l.directoryBuffer offset:0 atIndex:0];
    [enc setBuffer:l.tailBuffer offset:0 atIndex:1]; [enc setBuffer:out offset:0 atIndex:2];
    [enc setBytes:bounds length:sizeof bounds atIndex:3];
    [enc dispatchThreads:MTLSizeMake(256,1,1) threadsPerThreadgroup:MTLSizeMake(32,1,1)]; [enc endEncoding];
    dispatch_semaphore_t done=dispatch_semaphore_create(0);
    [cb addCompletedHandler:^(id<MTLCommandBuffer> b) { (void)l; dispatch_semaphore_signal(done); }];
    [cb commit]; BOOL finished=dispatch_semaphore_wait(done,dispatch_time(DISPATCH_TIME_NOW,5*NSEC_PER_SEC))==0;
    CHECK(finished); if (!finished) return;
    CHECK(cb.status==MTLCommandBufferStatusCompleted); if (cb.status!=MTLCommandBufferStatusCompleted) return;
    for (uint32_t id=0; id<256; ++id) { checkID(l,inputs,id); CHECK(((uint32_t *)out.contents)[id]==[l ownerForFaceID:id]); }
} }

int main(int argc, const char **argv) { @autoreleasepool {
    BOOL useGPU = argc == 2 && strcmp(argv[1],"--gpu") == 0;
    if (argc > 1 && !useGPU) { fprintf(stderr,"usage: farimage_lookup_test [--gpu]\n"); return 2; }
    NSError *error=nil;
    CHECK([[MCMTLFarFaceLookup alloc] initWithSnapshot:nil budgetBytes:4096 error:&error]==nil && error);
    CHECK([error.localizedDescription containsString:@"worker-only"]);
    dispatch_group_t group=dispatch_group_create();
    dispatch_group_async(group,dispatch_get_global_queue(QOS_CLASS_USER_INITIATED,0),^{ @autoreleasepool {
        MCMTLDevice *device=[MCMTLDevice new]; LookupDevice *fake=[LookupDevice new];
        fake.maxBufferLength=16u<<20; device->device=(id)fake;
        normalCases(device,fake); invalid(device,fake); limitsAndLifetime(device,fake); sampledOracle(device);
        if (useGPU) gpu();
    } });
    if (dispatch_group_wait(group,dispatch_time(DISPATCH_TIME_NOW,30*NSEC_PER_SEC))) {
        fprintf(stderr,"FAIL: lookup tests exceeded 30 seconds\n"); return 1;
    }
    printf("farimage_lookup_test: %u checks, %u failures (%s)\n",checks,failures,useGPU?"CPU + GPU":"CPU only");
    return failures ? 1 : 0;
} }
