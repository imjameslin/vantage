/* Real cull isolation after source table mutation and an outstanding upload dependency. */
#import "mcmetal_farsnapshot.h"
#import "mcmetal_internal.h"
#include <dispatch/dispatch.h>
#include <stdio.h>
#include <string.h>

static int checks, failures;
#define CHECK(x) do { checks++; if (!(x)) { failures++; fprintf(stderr,"FAIL line %d: %s\n",__LINE__,#x); } } while (0)
static BOOL finish(id<MTLCommandBuffer> cb) {
    dispatch_semaphore_t done = dispatch_semaphore_create(0);
    [cb addCompletedHandler:^(id<MTLCommandBuffer> b) { dispatch_semaphore_signal(done); }];
    [cb commit];
    BOOL ok = dispatch_semaphore_wait(done, dispatch_time(DISPATCH_TIME_NOW, 5*NSEC_PER_SEC)) == 0;
    CHECK(ok); if (ok) CHECK(cb.status == MTLCommandBufferStatusCompleted);
    return ok && cb.status == MTLCommandBufferStatusCompleted;
}
static MCMTLFarClusterInput *input(NSData *items, id<MTLBuffer> vertex, id<MTLBuffer> index, uint32_t layer,
                                  uint32_t base, id lifetime) {
    return [[MCMTLFarClusterInput alloc] initWithItems:items vertices:vertex vertexOffset:0
        indices:index indexOffset:0 layerCode:layer faceBase:base lifetime:lifetime];
}
static MCMTLFarCullSnapshot *snapshot(MCMTLDevice *device, NSArray *inputs, NSData *table, NSData *sections,
                                     NSData *view, uint64_t budget, NSError **error) {
    return [[MCMTLFarCullSnapshot alloc] initWithDevice:device inputs:inputs cullSections:table
        shaderSections:sections view:view budgetBytes:budget error:error];
}

static void reusable(MCMTLDevice *device, id<MTLCommandQueue> far, id<MTLSharedEvent> ready) { @autoreleasepool {
    id<MTLBuffer> vertices=[device->device newBufferWithLength:3*112 options:MTLResourceStorageModeShared];
    id<MTLBuffer> indices=[device->device newBufferWithLength:3*24 options:MTLResourceStorageModeShared];
    // Two known [0,1]^3 boxes, facing bucket UNASSIGNED, three quads total.
    uint32_t lo=512u|(512u<<10)|(512u<<20), hi=528u|(528u<<10)|(528u<<20);
    uint32_t words[8]={0,(1u<<20)|(4u<<26),lo,hi,8,4u<<26,lo,hi};
    NSMutableData *items=[NSMutableData dataWithBytes:words length:sizeof words];
    NSMutableData *table=[NSMutableData dataWithLength:32], *sections=[NSMutableData dataWithLength:16];
    ((float *)table.mutableBytes)[3]=1.0f/16;
    ((uint32_t *)sections.mutableBytes)[0]=73;
    NSMutableData *view=[NSMutableData dataWithLength:128];
    ((uint32_t *)view.mutableBytes)[21]=5; // Persistent sections + frustum, no facing/occlusion.
    ((float *)view.mutableBytes)[0]=1; // x >= 0; camera translation +10 rejects both boxes.
    NSError *error=nil;
    MCMTLFarClusterInput *in=input(items,vertices,indices,0,300,@"range lease");
    MCMTLFarCullGeometry *geometry=[[MCMTLFarCullGeometry alloc] initWithDevice:device inputs:@[in]
        cullSections:table shaderSections:sections budgetBytes:308 error:&error];
    CHECK(geometry && !error && geometry.snapshotCapacity==3);
    CHECK(geometry.immutableBufferBytes==80 && geometry.snapshotBufferBytes==76);
    if (!geometry) return;
    MCMTLFarCullSnapshot *a=[[MCMTLFarCullSnapshot alloc] initWithGeometry:geometry view:view budgetBytes:156 error:&error];
    ((float *)view.mutableBytes)[24]=10;
    MCMTLFarCullSnapshot *b=[[MCMTLFarCullSnapshot alloc] initWithGeometry:geometry view:view budgetBytes:156 error:&error];
    ((float *)view.mutableBytes)[24]=0;
    MCMTLFarCullSnapshot *c=[[MCMTLFarCullSnapshot alloc] initWithGeometry:geometry view:view budgetBytes:156 error:&error];
    CHECK(a && b && c && !error);
    if (!a || !b || !c) return;
    CHECK(a.geometry==b.geometry && b.geometry==c.geometry && a.shaderSections==b.shaderSections);
    CHECK([[MCMTLFarCullSnapshot alloc] initWithGeometry:geometry view:view budgetBytes:156 error:&error]==nil && error);
    // Poison every original table and view after preparation/capture; no refresh may alias them.
    memset(items.mutableBytes,0xff,items.length); memset(table.mutableBytes,0xff,table.length);
    memset(sections.mutableBytes,0xff,sections.length); memset(view.mutableBytes,0xff,view.length);
    MCMTLFarDraw da=[a drawAtIndex:0], db=[b drawAtIndex:0], dc=[c drawAtIndex:0];
    CHECK(da.items!=db.items && db.items!=dc.items && da.items!=dc.items);
    CHECK(da.quads!=db.quads && db.quads!=dc.quads && da.quads!=dc.quads);
    CHECK(da.arguments!=db.arguments && db.arguments!=dc.arguments && da.arguments!=dc.arguments);
    memset(db.arguments.contents,0x7d,db.arguments.length);
    id<MTLCommandBuffer> cb=[far commandBuffer];
    CHECK([a encodeCull:cb readyEvent:ready readyValue:1 error:&error] && !error);
    CHECK(cb.status==MTLCommandBufferStatusNotEnqueued && device->currentCommandBuffer==nil);
    if (!finish(cb)) return;
    uint8_t savedItems[16], savedQuads[12], savedArgs[20];
    memcpy(savedItems,da.items.contents,sizeof savedItems); memcpy(savedQuads,da.quads.contents,sizeof savedQuads);
    memcpy(savedArgs,da.arguments.contents,sizeof savedArgs);
    CHECK(((uint32_t *)da.arguments.contents)[0]==18 && ((uint8_t *)db.arguments.contents)[0]==0x7d);
    cb=[far commandBuffer];
    CHECK([b encodeCull:cb readyEvent:ready readyValue:1 error:&error] && !error);
    CHECK([c encodeCull:cb readyEvent:ready readyValue:1 error:&error] && !error);
    if (!finish(cb)) return;
    CHECK(((uint32_t *)db.arguments.contents)[0]==0 && ((uint32_t *)db.arguments.contents)[1]==1);
    CHECK(((uint32_t *)dc.arguments.contents)[0]==18);
    CHECK(memcmp(da.items.contents,savedItems,sizeof savedItems)==0);
    CHECK(memcmp(da.quads.contents,savedQuads,sizeof savedQuads)==0);
    CHECK(memcmp(da.arguments.contents,savedArgs,sizeof savedArgs)==0);
    CHECK(memcmp(dc.items.contents,savedItems,sizeof savedItems)==0);
    CHECK(memcmp(dc.quads.contents,savedQuads,sizeof savedQuads)==0);
    CHECK(((uint32_t *)a.shaderSections.contents)[0]==73 && ((const uint32_t *)in.items.bytes)[0]==0);
    CHECK(![b encodeCull:[far commandBuffer] readyEvent:ready readyValue:1 error:&error] && error);
} }

int main(void) { @autoreleasepool {
    mcmtl_device_desc desc = {.structSize=sizeof desc,.framesInFlight=2};
    mcmtl_handle h=0;
    CHECK(mcmtl_device_create(&desc,&h)==MCMTL_OK);
    if (!h) return 1;
    MCMTLDevice *device=MCMTL_DEVICE(h);
    id<MTLBuffer> vertices=[device->device newBufferWithLength:3*112 options:MTLResourceStorageModeShared];
    id<MTLBuffer> compact=[device->device newBufferWithLength:3*48 options:MTLResourceStorageModeShared];
    id<MTLBuffer> small=[device->device newBufferWithLength:3*16 options:MTLResourceStorageModeShared];
    id<MTLBuffer> indices=[device->device newBufferWithLength:3*24 options:MTLResourceStorageModeShared];
    CHECK(vertices && compact && small && indices);
    uint32_t itemWords[8]={0,(1u<<20)|(1u<<29),0,0,8,1u<<29,0,0};
    NSMutableData *items=[NSMutableData dataWithBytes:itemWords length:sizeof itemWords];
    NSMutableData *table=[NSMutableData dataWithLength:32], *sections=[NSMutableData dataWithLength:16];
    ((float *)table.mutableBytes)[3]=1.0f/16.0f;
    ((int32_t *)sections.mutableBytes)[0]=43;
    NSMutableData *view=[NSMutableData dataWithLength:128];
    ((uint32_t *)view.mutableBytes)[21]=4;
    NSError *error=nil;
    MCMTLFarClusterInput *block=input(items,vertices,indices,0,100,@"geometry lease");
    MCMTLFarClusterInput *packed=input(items,compact,indices,9,200,@"geometry lease");
    CHECK(block && packed);
    // Snapshot input must copy even an NSData no-copy alias of Java-managed/live memory.
    NSData *alias=[NSData dataWithBytesNoCopy:itemWords length:sizeof itemWords freeWhenDone:NO];
    MCMTLFarClusterInput *fromAlias=input(alias,vertices,indices,0,100,@"lease");
    itemWords[0]=99999;
    CHECK(((const uint32_t *)fromAlias.items.bytes)[0]==0);
    CHECK(snapshot(device,@[input(items,small,indices,9,200,@"lease")],table,sections,view,1000,&error)==nil && error);
    CHECK(snapshot(device,@[block],table,sections,view,100,&error)==nil && error);
    CHECK(snapshot(device,@[input(items,vertices,indices,2,100,@"lease")],table,sections,view,1000,&error)==nil && error);
    CHECK(snapshot(device,@[input(items,vertices,indices,0,UINT32_MAX-1,@"lease")],table,sections,view,1000,&error)==nil && error);
    CHECK(snapshot(device,@[block,input(items,compact,indices,9,100,@"lease")],table,sections,view,1000,&error)==nil && error);
    CHECK(snapshot(device,@[block,input(items,vertices,indices,1,200,@"lease")],table,sections,view,1000,&error)==nil && error);
    MCMTLFarCullSnapshot *first=snapshot(device,@[block,packed],table,sections,view,1000,&error);
    CHECK(first && !error && first.count==2 && first.ownedBufferBytes==264);
    if (!first) return 1;
    MCMTLFarDraw d0=[first drawAtIndex:0], d1=[first drawAtIndex:1];
    CHECK(d0.quadCapacity==3 && d1.quadCapacity==3 && d0.input.faceBase==100 && d1.input.faceBase==200);
    CHECK(d0.arguments!=d1.arguments && d0.items!=d1.items && d0.quads!=d1.quads);
    CHECK([first drawAtIndex:2].input==nil);
    // Live source tables are changed after capture; GPU must read the immutable copies.
    memset(items.mutableBytes,0xff,items.length);
    memset(table.mutableBytes,0xff,table.length);
    memset(sections.mutableBytes,0xff,sections.length);
    memset(view.mutableBytes,0xff,view.length);
    CHECK(((int32_t *)first.shaderSections.contents)[0]==43);
    CHECK(((const uint32_t *)block.items.bytes)[0]==0);

    id<MTLSharedEvent> uploadReady=[device->device newSharedEvent], gate=[device->device newSharedEvent];
    id<MTLCommandBuffer> upload=[device->queue commandBuffer];
    [upload encodeWaitForEvent:gate value:1];
    [upload encodeSignalEvent:uploadReady value:1];
    [upload commit]; // Already committed, not yet complete: no CPU wait before offering far work.
    id<MTLCommandQueue> far=[device->device newCommandQueue];
    id<MTLCommandBuffer> cb=[far commandBuffer];
    CHECK(![first encodeCull:cb readyEvent:uploadReady readyValue:0 error:&error] && error);
    CHECK([first encodeCull:cb readyEvent:uploadReady readyValue:1 error:&error] && !error);
    CHECK(cb.status==MTLCommandBufferStatusNotEnqueued && device->currentCommandBuffer==nil);
    memset(d0.arguments.contents,0x5b,32);
    dispatch_semaphore_t done=dispatch_semaphore_create(0);
    [cb addCompletedHandler:^(id<MTLCommandBuffer> b) { dispatch_semaphore_signal(done); }];
    [cb commit];
    CHECK(dispatch_semaphore_wait(done,dispatch_time(DISPATCH_TIME_NOW,10*NSEC_PER_MSEC))!=0);
    CHECK(((uint8_t *)d0.arguments.contents)[0]==0x5b && uploadReady.signaledValue==0);
    gate.signaledValue=1;
    CHECK(dispatch_semaphore_wait(done,dispatch_time(DISPATCH_TIME_NOW,5*NSEC_PER_SEC))==0);
    CHECK(cb.status==MTLCommandBufferStatusCompleted);
    const uint32_t expectedArgs[5]={18,1,0,0,0}, expectedQuads[3]={0,1,64}, expectedItems[4]={0,0,8,0};
    for (NSUInteger j=0;j<2;j++) {
        MCMTLFarDraw d=[first drawAtIndex:j];
        CHECK(memcmp(d.arguments.contents,expectedArgs,sizeof expectedArgs)==0);
        CHECK(memcmp(d.quads.contents,expectedQuads,sizeof expectedQuads)==0);
        CHECK(memcmp(d.items.contents,expectedItems,sizeof expectedItems)==0);
    }
    id<MTLCommandBuffer> unused=[far commandBuffer];
    CHECK(![first encodeCull:unused readyEvent:uploadReady readyValue:1 error:&error] && error);
    CHECK(finish(unused));
    reusable(device,far,uploadReady);

    // Rendered IDs can outlive world close: a snapshot owns its external allocation lifetime token.
    __weak NSObject *weakLifetime=nil;
    @autoreleasepool {
        NSObject *lease=[NSObject new]; weakLifetime=lease;
        uint32_t single[4]={0,1u<<29,0,0};
        NSData *one=[NSData dataWithBytes:single length:sizeof single];
        MCMTLFarClusterInput *owned=input(one,vertices,indices,0,100,lease);
        lease=nil;
        CHECK(weakLifetime!=nil && owned.items.length==16);
    }
    CHECK(weakLifetime==nil);
    first=nil; block=nil; packed=nil; fromAlias=nil; cb=nil; upload=nil; unused=nil;
    mcmtl_device_destroy(h);
    printf("farimage_snapshot_test: %d checks, %d failures\n",checks,failures);
    return failures?1:0;
}}
