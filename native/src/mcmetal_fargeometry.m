#import "mcmetal_fargeometry.h"
#import "mcmetal_internal.h"
#include <stdatomic.h>
#include <limits.h>
#include <stdio.h>

_Static_assert(sizeof(mcmtl_far_geometry_input) == 56, "FFM input layout");
_Static_assert(offsetof(mcmtl_far_geometry_input, layerCode) == 48, "FFM layer offset");
enum { FAR_GEOMETRY_LEASES = 3 };
static _Atomic(uint64_t) releases[FAR_GEOMETRY_LEASES];
static const uint64_t RELEASED = UINT64_C(1) << 63;

@interface MCMTLFarGeometryLifetime : NSObject
- (BOOL)adopt:(uint64_t)token;
@end
@implementation MCMTLFarGeometryLifetime {
    uint64_t _token;
    unsigned _slot;
}
- (BOOL)adopt:(uint64_t)token {
    if (_token || !token || (token & RELEASED)) return NO;
    // Tokens are unique across all worlds in this Java process. A duplicate is
    // caller misuse, rejected rather than creating two owners of one range lease.
    for (unsigned i=0; i<FAR_GEOMETRY_LEASES; ++i)
        if ((atomic_load_explicit(&releases[i], memory_order_acquire) & ~RELEASED) == token) return NO;
    for (unsigned i=0; i<FAR_GEOMETRY_LEASES; ++i) {
        uint64_t empty=0;
        if (atomic_compare_exchange_strong_explicit(&releases[i], &empty, token, memory_order_acq_rel, memory_order_acquire)) {
            _slot=i; _token=token; return YES;
        }
    }
    return NO;
}
- (void)dealloc {
    if (_token) atomic_store_explicit(&releases[_slot], _token | RELEASED, memory_order_release);
}
@end

static id capture_error(NSError **error, NSString *text);
@interface MCMTLFarGeometryRecipe ()
- (instancetype)initWithDevice:(id<MTLDevice>)device event:(id<MTLSharedEvent>)event inputs:(NSArray *)inputs
    cull:(NSData *)cull shader:(NSData *)shader view:(NSData *)view;
@end
@implementation MCMTLFarGeometryRecipe {
    MCMTLFarCullGeometry *_cullGeometry;
    _Atomic(unsigned) _preparing;
}
- (instancetype)initWithDevice:(id<MTLDevice>)d event:(id<MTLSharedEvent>)event inputs:(NSArray *)inputs
    cull:(NSData *)cull shader:(NSData *)shader view:(NSData *)view {
    if ((self=[super init])) {
        _device=d; _uploadEvent=event; _inputs=[inputs copy];
        _cullSections=cull; _shaderSections=shader; _view=view;
        atomic_init(&_preparing, 0);
    }
    return self;
}
- (MCMTLFarCullGeometry *)prepareCullGeometryWithDevice:(MCMTLDevice *)device
    budgetBytes:(uint64_t)budget error:(NSError **)error {
    if (error) *error=nil;
    if ([NSThread isMainThread] || !device || device->device != _device || !budget)
        return capture_error(error, @"Geometry preparation needs a matching worker cull device and budget");
    unsigned idle=0;
    if (!atomic_compare_exchange_strong_explicit(&_preparing, &idle, 1, memory_order_acquire, memory_order_relaxed))
        return capture_error(error, @"Geometry preparation is busy");
    @try {
        if (_cullGeometry) {
            if (_cullGeometry.cullDevice != device || _cullGeometry.budgetBytes != budget)
                return capture_error(error, @"Prepared geometry device/budget cannot change");
            return _cullGeometry;
        }
        _cullGeometry=[[MCMTLFarCullGeometry alloc] initWithDevice:device inputs:_inputs
            cullSections:_cullSections shaderSections:_shaderSections budgetBytes:budget error:error];
        return _cullGeometry;
    } @catch (NSException *exception) { return capture_error(error, exception.reason); }
      @finally { atomic_store_explicit(&_preparing, 0, memory_order_release); }
}
@end

static BOOL range_ok(id<MTLBuffer> b, uint64_t offset, uint64_t length, id<MTLDevice> d) {
    return b && b.device==d && length && offset <= b.length && length <= b.length-offset && offset%4==0;
}

static id capture_error(NSError **error, NSString *text) {
    if (error) *error=[NSError errorWithDomain:@"mcmetal.far.geometry" code:1
        userInfo:@{NSLocalizedDescriptionKey:text ?: @"Far geometry capture failed"}];
    return nil;
}

@interface MCMTLFarGeometryPins ()
- (instancetype)initWithDevice:(MCMTLDevice *)device inputs:(const mcmtl_far_geometry_input *)inputs
    count:(uint32_t)count lifetime:(MCMTLFarGeometryLifetime *)lifetime;
@end
@implementation MCMTLFarGeometryPins {
    id<MTLDevice> _device;
    id<MTLSharedEvent> _event;
    NSArray<id<MTLBuffer>> *_buffers;
    NSData *_descriptors;
    MCMTLFarGeometryLifetime *_lifetime;
}
- (instancetype)initWithDevice:(MCMTLDevice *)device inputs:(const mcmtl_far_geometry_input *)inputs
    count:(uint32_t)count lifetime:(MCMTLFarGeometryLifetime *)lifetime {
    if ((self=[super init])) {
        _device=device->device; _event=device->event; _lifetime=lifetime;
        _descriptors=[NSData dataWithBytes:inputs length:count*sizeof(*inputs)];
        NSMutableArray *buffers=[NSMutableArray arrayWithCapacity:2*count];
        for (uint32_t i=0; i<count; ++i) {
            [buffers addObject:MCMTL_BUFFER(inputs[i].vertices)];
            [buffers addObject:MCMTL_BUFFER(inputs[i].indices)];
        }
        _buffers=[buffers copy];
    }
    return self;
}
- (MCMTLFarGeometryRecipe *)captureItems:(NSArray<NSData *> *)items
    cullSections:(NSData *)cull shaderSections:(NSData *)shader view:(NSData *)view
    budgetBytes:(uint64_t)budget error:(NSError **)error {
    if (error) *error=nil;
    @try {
        NSUInteger count=_descriptors.length/sizeof(mcmtl_far_geometry_input);
        if ([NSThread isMainThread] || !items || items.count!=count || !cull.length || cull.length%32
            || cull.length/32>(1u<<20) || shader.length!=cull.length/2 || view.length!=128
            || !budget || budget>(64u<<20)) return capture_error(error, @"Invalid worker capture dimensions/budget");
        uint64_t bytes=cull.length+shader.length+view.length;
        if (bytes>budget) return capture_error(error, @"Metadata budget exhausted");
        const mcmtl_far_geometry_input *in=_descriptors.bytes;
        for (NSUInteger i=0; i<count; ++i) {
            NSData *data=items[i];
            if (!data.length || data.length%16 || data.length>budget-bytes) return capture_error(error, @"Invalid cluster metadata size");
            const uint32_t *words=data.bytes;
            uint64_t quads=0, quadBytes=(in[i].layerCode&8u)?48u:112u;
            for (NSUInteger j=0; j<data.length/16; ++j) {
                uint64_t first=words[j*4], q=((words[j*4+1]>>20)&63u)+1, end=first/4+q;
                if (first%4 || (words[j*4+1]&0xfffffu)>=cull.length/32
                    || end>in[i].vertexBytes/quadBytes || end>UINT32_MAX-in[i].faceBase)
                    return capture_error(error, @"Cluster outside leased geometry/section/ID range");
                quads+=q;
            }
            if (quads>UINT32_MAX/6u || quads>in[i].indexBytes/24u) return capture_error(error, @"Index slice too small");
            bytes+=data.length;
        }
        NSMutableArray *owned=[NSMutableArray arrayWithCapacity:count];
        for (NSUInteger i=0; i<count; ++i) {
            MCMTLFarClusterInput *copy=[[MCMTLFarClusterInput alloc] initWithItems:items[i]
                vertices:_buffers[i*2] vertexOffset:in[i].vertexOffset
                indices:_buffers[i*2+1] indexOffset:in[i].indexOffset
                layerCode:in[i].layerCode faceBase:in[i].faceBase lifetime:_lifetime];
            if (!copy) return capture_error(error, @"Could not copy far input");
            [owned addObject:copy];
        }
        return [[MCMTLFarGeometryRecipe alloc] initWithDevice:_device event:_event inputs:owned
            cull:[NSData dataWithBytes:cull.bytes length:cull.length]
            shader:[NSData dataWithBytes:shader.bytes length:shader.length]
            view:[NSData dataWithBytes:view.bytes length:view.length]];
    } @catch (NSException *exception) { return capture_error(error, exception.reason); }
}
@end

MCMTL_API int32_t mcmtl_ext_far_geometry_pin(mcmtl_handle handle, uint64_t token,
    const mcmtl_far_geometry_input *inputs, uint32_t count, mcmtl_handle *out) {
    if (out) *out=0;
    if (!handle || !out || !token || token>INT64_MAX || !inputs || !count || count>4096)
        return MCMTL_E_INVALID_ARGUMENT;
    @autoreleasepool { @try {
        MCMTLDevice *d=MCMTL_DEVICE(handle);
        if (!d->device || !d->event) return MCMTL_E_INVALID_ARGUMENT;
        for (uint32_t i=0; i<count; ++i) {
            const mcmtl_far_geometry_input *in=&inputs[i];
            if ((in->layerCode & ~9u) || (in->layerCode & 7u)>1
                || !range_ok(MCMTL_BUFFER(in->vertices),in->vertexOffset,in->vertexBytes,d->device)
                || !range_ok(MCMTL_BUFFER(in->indices),in->indexOffset,in->indexBytes,d->device)) return MCMTL_E_INVALID_ARGUMENT;
        }
        MCMTLFarGeometryLifetime *life=[MCMTLFarGeometryLifetime new];
        MCMTLFarGeometryPins *pins=[[MCMTLFarGeometryPins alloc] initWithDevice:d inputs:inputs count:count lifetime:life];
        if (!pins) return MCMTL_E_OUT_OF_MEMORY;
        // Last: every earlier failure leaves the allocation token Java-owned.
        if (![life adopt:token]) return MCMTL_E_UNSUPPORTED;
        *out=(mcmtl_handle)(uintptr_t)(__bridge_retained void *)pins;
        return MCMTL_OK;
    } @catch (NSException *exception) { (void)exception; return MCMTL_E_OBJC_EXCEPTION; } }
}

MCMTL_API void mcmtl_ext_far_geometry_drop(mcmtl_handle recipe) {
    if (recipe) { id released=(__bridge_transfer id)(void *)(uintptr_t)recipe; (void)released; }
}

MCMTL_API mcmtl_handle mcmtl_ext_far_geometry_event(mcmtl_handle recipe) {
    if (!recipe) return 0;
    MCMTLFarGeometryRecipe *r=(__bridge id)(void *)(uintptr_t)recipe;
    return (mcmtl_handle)(uintptr_t)(__bridge void *)r.uploadEvent;
}

MCMTL_API int32_t mcmtl_ext_far_geometry_capture(mcmtl_handle handle,
    const mcmtl_far_bytes *items, uint32_t count, const mcmtl_far_bytes *tables, uint64_t budget,
    mcmtl_handle *out, char *message, uint32_t capacity) {
    if (out) *out=0;
    if (message && capacity) message[0]=0;
    if (!tables) return MCMTL_E_INVALID_ARGUMENT;
    mcmtl_far_bytes cull=tables[0], shader=tables[1], view=tables[2];
    if (!handle || !out || !items || !count || count>4096 || (capacity && !message)
        || !cull.bytes || !shader.bytes || !view.bytes || !budget || budget>(64u<<20)
        || cull.length>budget || shader.length>budget-cull.length
        || view.length>budget-cull.length-shader.length) return MCMTL_E_INVALID_ARGUMENT;
    @autoreleasepool { @try {
        NSMutableArray *data=[NSMutableArray arrayWithCapacity:count];
        uint64_t used=cull.length+shader.length+view.length;
        for (uint32_t i=0; i<count; ++i) {
            if (!items[i].bytes || !items[i].length || items[i].length>budget-used) return MCMTL_E_INVALID_ARGUMENT;
            [data addObject:[NSData dataWithBytesNoCopy:(void *)items[i].bytes length:items[i].length freeWhenDone:NO]];
            used+=items[i].length;
        }
        MCMTLFarGeometryPins *pins=(__bridge id)(void *)(uintptr_t)handle;
        NSError *error=nil;
        MCMTLFarGeometryRecipe *recipe=[pins captureItems:data
            cullSections:[NSData dataWithBytesNoCopy:(void *)cull.bytes length:cull.length freeWhenDone:NO]
            shaderSections:[NSData dataWithBytesNoCopy:(void *)shader.bytes length:shader.length freeWhenDone:NO]
            view:[NSData dataWithBytesNoCopy:(void *)view.bytes length:view.length freeWhenDone:NO]
            budgetBytes:budget error:&error];
        if (!recipe) {
            if (message && capacity) snprintf(message,capacity,"%s",error.localizedDescription.UTF8String ?: "Geometry capture failed");
            return MCMTL_E_UNSUPPORTED;
        }
        *out=(mcmtl_handle)(uintptr_t)(__bridge_retained void *)recipe;
        return MCMTL_OK;
    } @catch (NSException *exception) {
        if (message && capacity) snprintf(message,capacity,"%s",exception.reason.UTF8String ?: "Geometry exception");
        return MCMTL_E_OBJC_EXCEPTION;
    } }
}

MCMTL_API uint32_t mcmtl_ext_far_geometry_poll(uint64_t *tokens, uint32_t capacity) {
    if (!tokens || !capacity) return 0;
    uint32_t count=0;
    for (unsigned i=0; i<FAR_GEOMETRY_LEASES && count<capacity; ++i) {
        uint64_t value=atomic_load_explicit(&releases[i], memory_order_acquire);
        if ((value & RELEASED) && atomic_compare_exchange_strong_explicit(&releases[i], &value, 0,
            memory_order_acq_rel, memory_order_acquire)) tokens[count++]=value & ~RELEASED;
    }
    return count;
}
