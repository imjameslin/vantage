/* CPU-only pins/copies/reuse/release handoff. Fake buffers count allocations;
 * no Metal device, command queue, encoder or GPU call is made. */
#import "mcmetal_fargeometry.h"
#import "mcmetal_internal.h"
#include <dispatch/dispatch.h>
#include <stdio.h>
#include <math.h>
static unsigned checks, failures;
#define CHECK(x) do { ++checks; if (!(x)) { ++failures; fprintf(stderr,"FAIL %d: %s\n",__LINE__,#x); } } while (0)
@interface FakeFarBuffer : NSObject
@property(nonatomic) NSUInteger length;
@property(nonatomic, strong) id device;
@property(nonatomic, strong) NSMutableData *storage;
- (void *)contents;
@end
@implementation FakeFarBuffer
- (void *)contents { return _storage.mutableBytes; }
@end
@interface FakeFarDevice : NSObject
@property(nonatomic) NSUInteger maxBufferLength, uploads, allocations;
@property(nonatomic) BOOL failAllocation;
@end
@implementation FakeFarDevice
- (id<MTLBuffer>)newBufferWithLength:(NSUInteger)length options:(MTLResourceOptions)options {
    ++_allocations;
    if (_failAllocation) { _failAllocation=NO; return nil; }
    FakeFarBuffer *b=[FakeFarBuffer new]; b.length=length; b.device=self;
    b.storage=[NSMutableData dataWithLength:length]; return (id)b;
}
- (id<MTLBuffer>)newBufferWithBytes:(const void *)bytes length:(NSUInteger)length options:(MTLResourceOptions)options {
    ++_uploads;
    id<MTLBuffer> b=[self newBufferWithLength:length options:options];
    if (b) memcpy(b.contents,bytes,length);
    return b;
}
@end
static mcmtl_handle ptr(id o) { return (mcmtl_handle)(uintptr_t)(__bridge void *)o; }

static void run(void) { @autoreleasepool {
    MCMTLDevice *owner=[MCMTLDevice new];
    FakeFarDevice *metal=[FakeFarDevice new]; metal.maxBufferLength=4096;
    owner->device=(id)metal; owner->event=(id)[NSObject new];
    id originalDevice=owner->device, originalEvent=owner->event;
    FakeFarBuffer *vb=[FakeFarBuffer new], *ib=[FakeFarBuffer new];
    vb.device=ib.device=owner->device; vb.length=1024; ib.length=256;
    mcmtl_far_geometry_input input={ptr(vb),112,112,ptr(ib),24,24,0,173};
    uint64_t tokens[3]={0}; mcmtl_handle pin=0;
    CHECK(mcmtl_ext_far_geometry_poll(tokens,3)==0);
    CHECK(mcmtl_ext_far_geometry_pin(ptr(owner),1,&input,1,&pin)==MCMTL_OK && pin);
    mcmtl_handle duplicate=9;
    CHECK(mcmtl_ext_far_geometry_pin(ptr(owner),1,&input,1,&duplicate)==MCMTL_E_UNSUPPORTED && !duplicate);
    MCMTLFarGeometryRecipe *recipe=nil;
    __weak MCMTLFarCullGeometry *weakGeometry=nil;
    NSArray * __attribute__((objc_precise_lifetime)) descendants=nil;
    @autoreleasepool {
        NSMutableData *items=[NSMutableData dataWithLength:16], *cull=[NSMutableData dataWithLength:32];
        NSMutableData *shader=[NSMutableData dataWithLength:16], *view=[NSMutableData dataWithLength:128];
        ((uint32_t *)view.mutableBytes)[21]=6;
        ((uint32_t *)cull.mutableBytes)[0]=71;
        ((uint32_t *)shader.mutableBytes)[0]=29;
        MCMTLFarGeometryPins *pins=(__bridge id)(void *)(uintptr_t)pin;
        NSError *error=nil;
        recipe=[pins captureItems:@[items] cullSections:cull shaderSections:shader view:view budgetBytes:191 error:&error];
        CHECK(recipe==nil && error!=nil); // 192 owned bytes, budget enforced.
        ((uint32_t *)items.mutableBytes)[0]=4;
        CHECK([pins captureItems:@[items] cullSections:cull shaderSections:shader view:view budgetBytes:4096 error:NULL]==nil);
        ((uint32_t *)items.mutableBytes)[0]=0;
        // Closing/recreating the main native wrapper cannot replace captured event/device identities.
        owner->event=nil; owner->device=nil;
        mcmtl_far_bytes item={items.bytes,items.length};
        mcmtl_far_bytes tables[]={{cull.bytes,cull.length},{shader.bytes,shader.length},{view.bytes,view.length}};
        mcmtl_handle copied=0; char message[128]={0};
        CHECK(mcmtl_ext_far_geometry_capture(pin,&item,1,NULL,4096,&copied,message,sizeof message)==MCMTL_E_INVALID_ARGUMENT && !copied);
        CHECK(mcmtl_ext_far_geometry_capture(pin,&item,1,tables,4096,&copied,message,sizeof message)==MCMTL_OK && copied && !message[0]);
        recipe=(__bridge_transfer id)(void *)(uintptr_t)copied;
        CHECK(recipe!=nil && recipe.device==originalDevice && recipe.uploadEvent==originalEvent);
        memset(items.mutableBytes,0xff,items.length); memset(cull.mutableBytes,0xff,cull.length);
        memset(shader.mutableBytes,0xff,shader.length); memset(view.mutableBytes,0xff,view.length);
        CHECK(((const uint32_t *)recipe.inputs[0].items.bytes)[0]==0);
        CHECK(((const uint32_t *)recipe.cullSections.bytes)[0]==71);
        CHECK(((const uint32_t *)recipe.shaderSections.bytes)[0]==29);
        CHECK(((const uint32_t *)recipe.view.bytes)[21]==6);
        CHECK(recipe.inputs[0].vertices==(id)vb && recipe.inputs[0].indices==(id)ib);
        CHECK(recipe.inputs[0].vertexOffset==112 && recipe.inputs[0].indexOffset==24 && recipe.inputs[0].faceBase==173);
        MCMTLDevice *cullDevice=[MCMTLDevice new]; cullDevice->device=originalDevice;
        CHECK(metal.uploads==0 && metal.allocations==0);
        CHECK([recipe prepareCullGeometryWithDevice:cullDevice budgetBytes:115 error:&error]==nil && error);
        metal.maxBufferLength=31;
        CHECK([recipe prepareCullGeometryWithDevice:cullDevice budgetBytes:220 error:&error]==nil && error);
        metal.maxBufferLength=4096;
        CHECK(metal.uploads==0); // Entire immutable input is validated before any allocation.
        MCMTLFarCullGeometry *geometry=[recipe prepareCullGeometryWithDevice:cullDevice budgetBytes:220 error:&error];
        weakGeometry=geometry;
        CHECK(geometry && !error && geometry.count==1 && geometry.snapshotCapacity==3);
        if (!geometry) return;
        CHECK(geometry.immutableBufferBytes==64 && geometry.snapshotBufferBytes==52 && metal.uploads==3);
        CHECK([recipe prepareCullGeometryWithDevice:cullDevice budgetBytes:220 error:&error]==geometry && !error);
        CHECK([recipe prepareCullGeometryWithDevice:cullDevice budgetBytes:221 error:&error]==nil && error);
        MCMTLDevice *other=[MCMTLDevice new]; other->device=originalDevice;
        CHECK([recipe prepareCullGeometryWithDevice:other budgetBytes:220 error:&error]==nil && error);
        NSMutableData *fresh=[recipe.view mutableCopy];
        NSUInteger allocated=metal.allocations;
        CHECK([[MCMTLFarCullSnapshot alloc] initWithGeometry:geometry view:fresh budgetBytes:115 error:&error]==nil && error);
        ((uint32_t *)fresh.mutableBytes)[21]=20; // Near-depth history is never allowed here.
        CHECK([[MCMTLFarCullSnapshot alloc] initWithGeometry:geometry view:fresh budgetBytes:220 error:&error]==nil && error);
        ((uint32_t *)fresh.mutableBytes)[21]=6;
        ((float *)fresh.mutableBytes)[24]=NAN;
        CHECK([[MCMTLFarCullSnapshot alloc] initWithGeometry:geometry view:fresh budgetBytes:220 error:&error]==nil && error);
        ((float *)fresh.mutableBytes)[24]=128;
        CHECK([[MCMTLFarCullSnapshot alloc] initWithGeometry:geometry view:[NSMutableData dataWithLength:92] budgetBytes:220 error:&error]==nil && error);
        cullDevice->device=nil;
        CHECK([[MCMTLFarCullSnapshot alloc] initWithGeometry:geometry view:fresh budgetBytes:220 error:&error]==nil && error);
        cullDevice->device=originalDevice;
        CHECK(metal.allocations==allocated && metal.uploads==3);
        metal.failAllocation=YES;
        CHECK([[MCMTLFarCullSnapshot alloc] initWithGeometry:geometry view:fresh budgetBytes:220 error:&error]==nil && error);
        MCMTLFarCullSnapshot *second=nil;
        @autoreleasepool {
            MCMTLFarCullSnapshot *first=[[MCMTLFarCullSnapshot alloc] initWithGeometry:geometry view:recipe.view budgetBytes:220 error:&error];
            second=[[MCMTLFarCullSnapshot alloc] initWithGeometry:geometry view:fresh budgetBytes:220 error:&error];
            MCMTLFarCullSnapshot *third=[[MCMTLFarCullSnapshot alloc] initWithGeometry:geometry view:fresh budgetBytes:220 error:&error];
            CHECK(first && second && third && !error && first.ownedBufferBytes==116);
            CHECK(first.geometry==geometry && second.geometry==geometry && first.shaderSections==second.shaderSections);
            MCMTLFarDraw a=[first drawAtIndex:0], b=[second drawAtIndex:0];
            CHECK(a.input==b.input && a.items!=b.items && a.quads!=b.quads && a.arguments!=b.arguments);
            if (first && second) {
                memset(a.items.contents,0x37,a.items.length); memset(a.quads.contents,0x37,a.quads.length);
                memset(a.arguments.contents,0x37,a.arguments.length);
                CHECK(((uint8_t *)b.items.contents)[0]==0 && ((uint8_t *)b.quads.contents)[0]==0 && ((uint8_t *)b.arguments.contents)[0]==0);
            }
            allocated=metal.allocations;
            CHECK([[MCMTLFarCullSnapshot alloc] initWithGeometry:geometry view:fresh budgetBytes:220 error:&error]==nil && error);
            CHECK(metal.allocations==allocated && metal.uploads==3);
        }
        @autoreleasepool {
            MCMTLFarCullSnapshot *again=[[MCMTLFarCullSnapshot alloc] initWithGeometry:geometry view:fresh budgetBytes:220 error:&error];
            CHECK(again && !error && metal.uploads==3); // Retirement admits fresh outputs; no metadata reupload.
            CHECK(again.geometry.cullDevice==cullDevice);
        }
        CHECK(((const uint32_t *)recipe.view.bytes)[24]==0 && ((const uint32_t *)recipe.inputs[0].items.bytes)[0]==0);
        CHECK(((const uint32_t *)second.shaderSections.contents)[0]==29);
        for (NSUInteger capacity=1; capacity<=2; ++capacity) { @autoreleasepool {
            uint64_t budget=64+capacity*52;
            MCMTLFarCullGeometry *limited=[[MCMTLFarCullGeometry alloc] initWithDevice:cullDevice
                inputs:recipe.inputs cullSections:recipe.cullSections shaderSections:recipe.shaderSections
                budgetBytes:budget error:&error];
            CHECK(limited && !error && limited.snapshotCapacity==capacity);
            NSMutableArray *held=[NSMutableArray array];
            for (NSUInteger j=0; j<capacity; ++j) {
                MCMTLFarCullSnapshot *s=[[MCMTLFarCullSnapshot alloc] initWithGeometry:limited
                    view:fresh budgetBytes:budget error:&error];
                CHECK(s && !error); if (s) [held addObject:s];
            }
            allocated=metal.allocations;
            CHECK([[MCMTLFarCullSnapshot alloc] initWithGeometry:limited view:fresh budgetBytes:budget error:&error]==nil && error);
            CHECK(metal.allocations==allocated);
        } }
        mcmtl_ext_far_geometry_drop(pin); pin=0; pins=nil;
        CHECK(mcmtl_ext_far_geometry_poll(tokens,3)==0);
        descendants=@[second]; recipe=nil;
    }
    CHECK(mcmtl_ext_far_geometry_poll(tokens,3)==0);
    CHECK(weakGeometry!=nil); // A visibility-ID consumer owns the snapshot after recipe and pins retire.
    descendants=nil;
    CHECK(weakGeometry==nil);
    CHECK(mcmtl_ext_far_geometry_poll(tokens,3)==1 && tokens[0]==1);
    CHECK(mcmtl_ext_far_geometry_poll(tokens,3)==0);
    owner->device=originalDevice; owner->event=originalEvent;
    // Full registry and partial polling retain every token without overwrite.
    mcmtl_handle pins[3]={0};
    for (uint64_t i=0; i<3; ++i) CHECK(mcmtl_ext_far_geometry_pin(ptr(owner),i+2,&input,1,&pins[i])==MCMTL_OK);
    CHECK(mcmtl_ext_far_geometry_pin(ptr(owner),5,&input,1,&pin)==MCMTL_E_UNSUPPORTED && !pin);
    mcmtl_ext_far_geometry_drop(pins[1]);
    CHECK(mcmtl_ext_far_geometry_poll(NULL,3)==0);
    CHECK(mcmtl_ext_far_geometry_poll(tokens,0)==0);
    CHECK(mcmtl_ext_far_geometry_poll(tokens,1)==1 && tokens[0]==3);
    CHECK(mcmtl_ext_far_geometry_pin(ptr(owner),5,&input,1,&pin)==MCMTL_OK);
    mcmtl_ext_far_geometry_drop(pin); mcmtl_ext_far_geometry_drop(pins[0]); mcmtl_ext_far_geometry_drop(pins[2]);
    CHECK(mcmtl_ext_far_geometry_poll(tokens,3)==3);
    CHECK(tokens[0]+tokens[1]+tokens[2]==11);
    // Invalid slice, layer and token never enter the release registry.
    input.vertexOffset=1024;
    CHECK(mcmtl_ext_far_geometry_pin(ptr(owner),6,&input,1,&pin)==MCMTL_E_INVALID_ARGUMENT && !pin);
    input.vertexOffset=112; input.layerCode=16;
    CHECK(mcmtl_ext_far_geometry_pin(ptr(owner),6,&input,1,&pin)==MCMTL_E_INVALID_ARGUMENT && !pin);
    input.layerCode=0;
    CHECK(mcmtl_ext_far_geometry_pin(ptr(owner),0,&input,1,&pin)==MCMTL_E_INVALID_ARGUMENT && !pin);
    CHECK(mcmtl_ext_far_geometry_pin(ptr(owner),UINT64_MAX,&input,1,&pin)==MCMTL_E_INVALID_ARGUMENT && !pin);
    CHECK(mcmtl_ext_far_geometry_poll(tokens,3)==0);
    mcmtl_ext_far_geometry_drop(0);
} }
int main(void) { @autoreleasepool {
    dispatch_semaphore_t done=dispatch_semaphore_create(0);
    dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED,0),^{ run(); dispatch_semaphore_signal(done); });
    CHECK(dispatch_semaphore_wait(done,dispatch_time(DISPATCH_TIME_NOW,10*NSEC_PER_SEC))==0);
    printf("farimage_geometry_test: %u checks, %u failures (CPU only)\n",checks,failures); return failures?1:0;
} }
