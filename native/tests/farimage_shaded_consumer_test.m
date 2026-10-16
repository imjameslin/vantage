/* SHADED sidecar integration against real engine leases. No game launch.
 * Reuse the established test-only engine/Metal fixtures, not its run/expectations.
 * Build with the normal native flags and all native source files; run under gpulock.
 */
#define main originalConsumerMain
#define run originalConsumerRun
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wunused-function"
#include "farimage_consumer_test.m"
#pragma clang diagnostic pop
#undef run
#undef main

_Static_assert(sizeof(mcmtl_far_consumer_shading)==352,"shaded ABI");
_Static_assert(offsetof(mcmtl_far_consumer_shading,committedSequence)==8,"sequence ABI");
_Static_assert(offsetof(mcmtl_far_consumer_shading,uniforms)==16,"uniform ABI");
_Static_assert(offsetof(mcmtl_far_consumer_shading,current[0])==256,"atlas ABI");
_Static_assert(offsetof(mcmtl_far_consumer_shading,current[1])==304,"light ABI");
_Static_assert(offsetof(mcmtl_far_consumer_stats,reserved64)==56,"alpha receipt ABI");

static MCMTLFarGeometryRecipe *shadedGeometryLayers(MCMTLDevice *main,ProducerFixture *block,ProducerFixture *compact,BOOL blockOnly,unsigned clippedLayers) {
    unsigned count=blockOnly?2:4;
    // The old consumer fixture intentionally overlaps layer IDs. Shading needs
    // four disjoint original arena address spaces, including both material layers.
    const uint32_t codes[4]={0,1,8,9}, bases[4]={137,337,1000,1400};
    mcmtl_far_geometry_input desc[4]={0};
    NSMutableArray *arenas=[NSMutableArray array];
    for (unsigned i=0;i<count;++i) {
        ProducerFixture *f=i<2?block:compact;
        id<MTLBuffer> arena=buffer(main->device,f.arenaA.contents,f.arenaA.length);
        if (i<2 && (clippedLayers&(1u<<i))) ((float *)arena.contents)[25*28+2]=-4;
        // Duplicate the first two positions: one hardware triangle still draws,
        // but shade reconstruction must report reason 9 for its degenerate peer.
        if (i<2 && (clippedLayers&(256u<<i)))
            memcpy((float *)arena.contents+25*28,(float *)arena.contents+25*28+7,12);
        [arenas addObject:arena];
        desc[i]=(mcmtl_far_geometry_input){h(arena),0,arena.length,h(f.indices),0,f.indices.length,codes[i],bases[i]};
    }
    mcmtl_handle pins=0,recipe=0; char error[512]={0};
    static uint64_t token=923;
    CHECK(mcmtl_ext_far_geometry_pin(h(main),token++,desc,count,&pins)==MCMTL_OK);
    if (!pins) return nil;
    uint32_t item[4]={25*4,0,512u|(512u<<10)|(576u<<20),640u|(768u<<10)|(576u<<20)};
    float cull[8]={0}; cull[3]=1.0f/16.0f;
    uint32_t view[32]={0}; ((float *)view)[0]=-1; ((float *)view)[3]=16; view[21]=5;
    mcmtl_far_bytes items[4]; for (unsigned i=0;i<4;++i) items[i]=(mcmtl_far_bytes){item,sizeof item};
    mcmtl_far_bytes tables[3]={{cull,sizeof cull},{block.sections.contents,16},{view,sizeof view}};
    CHECK(mcmtl_ext_far_geometry_capture(pins,items,count,tables,1u<<20,&recipe,error,sizeof error)==MCMTL_OK);
    if (!recipe) fprintf(stderr,"geometry: %s\n",error);
    mcmtl_ext_far_geometry_drop(pins);
    return recipe?(__bridge_transfer id)(void *)(uintptr_t)recipe:nil;
}
static MCMTLFarGeometryRecipe *shadedGeometry(MCMTLDevice *main,ProducerFixture *block,ProducerFixture *compact,BOOL blockOnly) {
    return shadedGeometryLayers(main,block,compact,blockOnly,0);
}
static int32_t shadedEncode(mcmtl_handle c,mcmtl_handle lease,MCMTLFarWarpTransform *t,
    const mcmtl_far_consumer_fallback *draws,uint32_t count,uint64_t seq,const mcmtl_far_consumer_shading *s) {
    __block int32_t result=0;
    onMain(^{ char error[512]={0};
        result=mcmtl_ext_far_consumer_encode_shaded(c,lease,t,draws,count,seq,s,error,sizeof error);
        if (result<0 && error[0]) fprintf(stderr,"expected/diagnostic shaded: %s\n",error);
    });
    return result;
}
static void assertArgs(ConsumerReadback *r,const uint32_t expected[10],BOOL fallback) {
    CHECK(completed(r.command)); const uint32_t *a=r.args.contents;
    for (unsigned i=0;i<10;++i) CHECK(a[i]==(fallback?expected[i]:0));
    for (unsigned i=10;i<15;++i) CHECK(a[i]==0); // Unused third draw never stale.
    CHECK(a[15]==(fallback?0:3) && a[16]==(fallback?0:1) && !a[17] && !a[18]);
}
static void uploadMaterial(MCMTLDevice *main,id<MTLTexture> texture,const uint8_t *pixels,uint64_t seq) {
    NSUInteger pitch=texture.width*4;
    id<MTLBuffer> staging=[main->device newBufferWithBytes:pixels length:pitch*texture.height options:MTLResourceStorageModeShared];
    id<MTLCommandBuffer> cb=[main->queue commandBuffer]; id<MTLBlitCommandEncoder> b=[cb blitCommandEncoder];
    [b copyFromBuffer:staging sourceOffset:0 sourceBytesPerRow:pitch sourceBytesPerImage:pitch*texture.height
        sourceSize:MTLSizeMake(texture.width,texture.height,1) toTexture:texture destinationSlice:0
        destinationLevel:0 destinationOrigin:MTLOriginMake(0,0,0)];
    [b endEncoding]; [cb encodeSignalEvent:main->event value:seq]; [cb commit];
    // The real Java bridge gets this from mcmtl_submit. Test commands bypass the
    // stream but mirror its CPU publication AFTER commit, never signaledValue.
    mcmtl_status_store_u64(&main->status->submittedValue,seq);
}
static void validateReceipt(mcmtl_handle c,uint64_t seq,uint32_t alpha,BOOL fallback,uint32_t draws) {
    mcmtl_far_consumer_stats r=receipt(c,seq);
    CHECK(r.flags==MCMTL_FAR_CONSUMER_RECEIPT_VALID);
    CHECK(r.reserved64==alpha && r.syncFallback==(uint32_t)fallback);
    CHECK(r.imageCount==1 && r.drawCount==draws && r.sourceFrame[0]==10 && !r.sourceFrame[1]);
    CHECK(!r.sourceAgeNs[1]);
    CHECK(r.syncFallback==(r.holePixels!=0 || r.reserved64!=0));
}
_Static_assert(sizeof(mcmtl_far_consumer_layer_stats)==96,"layer stats ABI");
_Static_assert(sizeof(mcmtl_far_consumer_layer_outputs)==64,"layer outputs ABI");
static int32_t layerEncode(mcmtl_handle c,mcmtl_handle lease,MCMTLFarWarpTransform *t,
    const mcmtl_far_consumer_fallback *draws,const uint32_t *codes,uint32_t count,uint64_t seq,
    const mcmtl_far_consumer_shading *s) {
    __block int32_t result;
    onMain(^{char error[512]={0};
        result=mcmtl_ext_far_consumer_encode_shaded_layers(c,lease,t,draws,codes,count,seq,s,error,sizeof error);
        if(result<0 && error[0]) fprintf(stderr,"layer encode: %s\n",error);
    });
    return result;
}
static mcmtl_far_consumer_layer_stats layerReceipt(mcmtl_handle c,uint64_t seq) {
    mcmtl_far_consumer_layer_stats r={.structSize=sizeof r};
    NSDate *until=[NSDate dateWithTimeIntervalSinceNow:10];
    do {
        int32_t result=mcmtl_ext_far_consumer_layer_stats(c,&r);
        if(result==MCMTL_OK && (r.encodedSequence==seq || (r.flags&MCMTL_FAR_CONSUMER_POISONED))) return r;
        [NSThread sleepForTimeInterval:0.001];
    } while(until.timeIntervalSinceNow>0);
    CHECK(NO);return r;
}
static ConsumerReadback *layerReadback(MCMTLDevice *main,mcmtl_far_consumer_layer_outputs o) {
    ConsumerReadback *r=[ConsumerReadback new];
    r.args=[main->device newBufferWithLength:o.maxDraws*20+32 options:MTLResourceStorageModeShared];
    r.pixels=[main->device newBufferWithLength:4*256*H options:MTLResourceStorageModeShared];
    r.command=[main->queue commandBuffer];id<MTLBlitCommandEncoder> b=[r.command blitCommandEncoder];
    [b copyFromBuffer:obj(o.fallbackArgs) sourceOffset:0 toBuffer:r.args destinationOffset:0 size:o.maxDraws*20];
    [b copyFromBuffer:obj(o.compositeArgs) sourceOffset:0 toBuffer:r.args destinationOffset:o.maxDraws*20 size:32];
    mcmtl_handle tex[4]={o.color[0],o.color[1],o.depth[0],o.depth[1]};
    for(unsigned i=0;i<4;++i) [b copyFromTexture:obj(tex[i]) sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,0,0)
        sourceSize:MTLSizeMake(W,H,1) toBuffer:r.pixels destinationOffset:i*256*H destinationBytesPerRow:256 destinationBytesPerImage:256*H];
    [b endEncoding];[r.command commit];return r;
}
static void layerArgs(ConsumerReadback *r,const uint32_t *source,const uint32_t *codes,unsigned n,BOOL solid,BOOL cutout) {
    CHECK(completed(r.command)); const uint32_t *p=r.args.contents;
    for(unsigned i=0;i<n;++i) {
        unsigned layer=codes[i]&7u;BOOL copy=layer==2 || (layer==0?solid:cutout);
        CHECK(!memcmp(p+5*i,copy?source+5*i:(uint32_t[5]){0},20));
    }
    for(unsigned i=n*5;i<7*5;++i) CHECK(!p[i]);
    CHECK(!memcmp(p+35,solid?(uint32_t[4]){0}:(uint32_t[4]){3,1,0,0},16));
    CHECK(!memcmp(p+39,cutout?(uint32_t[4]){0}:(uint32_t[4]){3,1,0,0},16));
}
static void layerDiagnosticCheck(mcmtl_handle c,uint64_t seq,mcmtl_far_consumer_layer_stats r) {
    uint32_t bins[32]={0};
    CHECK(mcmtl_ext_far_consumer_layer_diagnostics(c,seq,bins,sizeof bins)==MCMTL_OK);
    for(unsigned layer=0;layer<2;++layer) {
        uint64_t sum=0;for(unsigned i=0;i<16;++i)sum+=bins[layer*16+i];
        CHECK(sum==W*H);
        CHECK((uint64_t)bins[layer*16]+bins[layer*16+1]+r.holePixels[layer]==W*H);
    }
}
static void runLayers(MCMTLDevice *main,mcmtl_handle engine,mcmtl_far_engine_config config,
    mcmtl_far_engine_submission submission,ProducerFixture *block,ProducerFixture *compact,
    MCMTLFarWarpTransform t,mcmtl_far_consumer_shading shade) {
    char error[512]={0};mcmtl_handle c=0;
    CHECK(mcmtl_ext_far_consumer_create(h(main),W,H,7,W*H*79+7*40+52,&c,error,sizeof error)==MCMTL_OK);
    if(!c)return;
    mcmtl_far_consumer_layer_outputs o={.structSize=sizeof o};
    CHECK(mcmtl_ext_far_consumer_layer_outputs(c,&o,error,sizeof error)==MCMTL_FAR_CONSUMER_DECLINED);
    CHECK(!o.fallbackArgs && !o.compositeArgs);
    const uint64_t extra=7*32+2384;
    CHECK(mcmtl_ext_far_consumer_enable_layers(c,extra-1,error,sizeof error)<0);
    onMain(^{CHECK(mcmtl_ext_far_consumer_enable_layers(c,extra,NULL,0)==MCMTL_E_INVALID_ARGUMENT);});
    CHECK(mcmtl_ext_far_consumer_enable_layers(c,extra,error,sizeof error)==MCMTL_OK);
    CHECK(mcmtl_ext_far_consumer_enable_layers(c,extra,error,sizeof error)==MCMTL_FAR_CONSUMER_DECLINED);
    CHECK(mcmtl_ext_far_consumer_layer_outputs(c,&o,error,sizeof error)==MCMTL_OK);
    CHECK([(id<MTLBuffer>)obj(o.compositeArgs) length]==32);
    mcmtl_far_consumer_outputs old={.structSize=sizeof old};
    CHECK(mcmtl_ext_far_consumer_outputs(c,&old,error,sizeof error)==MCMTL_OK);
    CHECK(old.fallbackArgs!=o.fallbackArgs && old.compositeArgs!=o.compositeArgs);
    CHECK(old.color[0]==o.color[0] && old.depth[1]==o.depth[1]);
    uint32_t words[30];for(unsigned i=0;i<6;++i) {
        words[5*i]=6*(i+1);words[5*i+1]=i+1;words[5*i+2]=31+i;
        words[5*i+3]=(uint32_t)(-31-(int)i);words[5*i+4]=73+i;
    }
    id<MTLBuffer> staging=buffer(main->device,words,sizeof words);
    id<MTLBuffer> current=[main->device newBufferWithLength:sizeof words options:MTLResourceStorageModePrivate];
    id<MTLCommandBuffer> cb=[main->queue commandBuffer];id<MTLBlitCommandEncoder> b=[cb blitCommandEncoder];
    [b copyFromBuffer:staging sourceOffset:0 toBuffer:current destinationOffset:0 size:sizeof words];
    [b endEncoding];[cb commit];
    uint8_t light[4]={170,190,210,255};uploadMaterial(main,block.lightmap,light,100);shade.committedSequence=100;shade.flags=3;
    mcmtl_far_consumer_fallback draws[6];for(unsigned i=0;i<6;++i)draws[i]=(mcmtl_far_consumer_fallback){h(current),i*20};
    uint32_t codes[6]={0,2,9,8,10,1};uint64_t seq=0;
    mcmtl_handle lease=acquire(engine,41,1);
    uint32_t badCodes[6];memcpy(badCodes,codes,sizeof codes);badCodes[2]=3;
    CHECK(layerEncode(c,lease,&t,draws,badCodes,6,1,&shade)==MCMTL_E_INVALID_ARGUMENT);
    CHECK(layerEncode(c,lease,&t,draws,NULL,6,1,&shade)==MCMTL_E_INVALID_ARGUMENT);
    mcmtl_far_consumer_shading bad=shade;bad.flags=5;
    CHECK(layerEncode(c,lease,&t,draws,codes,6,1,&bad)==MCMTL_E_INVALID_ARGUMENT);
    CHECK(layerEncode(c,lease,&t,draws,codes,6,++seq,&shade)==MCMTL_OK);mcmtl_ext_far_engine_drop(lease);
    ConsumerReadback *normal=layerReadback(main,o);layerArgs(normal,words,codes,6,NO,NO);
    mcmtl_far_consumer_layer_stats r=layerReceipt(c,seq);
    CHECK(r.flags==MCMTL_FAR_CONSUMER_RECEIPT_VALID && !r.holePixels[0] && !r.holePixels[1]);
    CHECK(!r.alphaRejected[0] && !r.alphaRejected[1] && r.drawCount[0]==2 && r.drawCount[1]==2);
    CHECK(r.sourceFrame==10);for(unsigned i=0;i<6;++i)CHECK(!r.reserved[i]);
    layerDiagnosticCheck(c,seq,r);
    mcmtl_far_consumer_stats oldStats={.structSize=sizeof oldStats};
    CHECK(mcmtl_ext_far_consumer_stats(c,&oldStats)==MCMTL_OK && !oldStats.flags && !oldStats.encodedSequence);
    // Hidden alpha changes reject CUTOUT alone; each code visits every original
    // list slot, including translucent entries separating opaque draws.
    light[3]=254;uploadMaterial(main,block.lightmap,light,101);shade.committedSequence=101;
    for(unsigned rotation=0;rotation<6;++rotation) {
        if(rotation) {uint32_t first=codes[0];memmove(codes,codes+1,5*4);codes[5]=first;}
        lease=acquire(engine,41,1);CHECK(layerEncode(c,lease,&t,draws,codes,6,++seq,&shade)==MCMTL_OK);mcmtl_ext_far_engine_drop(lease);
        layerArgs(layerReadback(main,o),words,codes,6,NO,YES);r=layerReceipt(c,seq);
        CHECK(r.flags==MCMTL_FAR_CONSUMER_RECEIPT_VALID && !r.holePixels[0] && !r.holePixels[1]);
        CHECK(!r.syncFallback[0] && r.syncFallback[1] && !r.alphaRejected[0] && r.alphaRejected[1]);
        layerDiagnosticCheck(c,seq,r);
    }
    light[3]=255;uploadMaterial(main,block.lightmap,light,102);shade.committedSequence=102;
    shade.flags=1;lease=acquire(engine,41,1);
    CHECK(layerEncode(c,lease,&t,draws,codes,6,++seq,&shade)==MCMTL_OK);mcmtl_ext_far_engine_drop(lease);
    ConsumerReadback *plain=layerReadback(main,o);layerArgs(plain,words,codes,6,NO,NO);
    CHECK(!memcmp(plain.pixels.contents,normal.pixels.contents,4*256*H));r=layerReceipt(c,seq);
    uint32_t bins[33];memset(bins,0xab,sizeof bins);
    CHECK(mcmtl_ext_far_consumer_layer_diagnostics(c,seq,bins,127)==MCMTL_E_INVALID_ARGUMENT && bins[0]==0xabababab);
    CHECK(mcmtl_ext_far_consumer_layer_diagnostics(c,seq,bins,128)==MCMTL_FAR_CONSUMER_DECLINED);
    for(unsigned i=0;i<32;++i)CHECK(!bins[i]);CHECK(bins[32]==0xabababab);
    CHECK(mcmtl_ext_far_consumer_layer_diagnostics(c,seq-1,bins,128)==MCMTL_FAR_CONSUMER_DECLINED);
    // Near-clipped BLOCK geometry makes only ONE layer uncertain. The other
    // layer must still composite with exact retained depth, even with unused
    // compact programs prepared in the engine bundle.
    shade.flags=3;
    for(unsigned clippedLayer=0;clippedLayer<2;++clippedLayer) { @autoreleasepool {
        MCMTLFarGeometryRecipe *g=shadedGeometryLayers(main,block,compact,YES,1u<<clippedLayer);CHECK(g);
        mcmtl_handle e=0;CHECK(mcmtl_ext_far_engine_create(h(main),&config,&e,error,sizeof error)==MCMTL_OK);
        CHECK(mcmtl_ext_far_engine_enable_shading(e,4u<<20,error,sizeof error)==MCMTL_OK);
        mcmtl_far_engine_submission s=submission;s.inputCount=2;s.geometryRecipe=h(g);
        CHECK(mcmtl_ext_far_engine_submit(e,&s,error,sizeof error)==MCMTL_OK);
        lease=acquire(e,41,1);CHECK(layerEncode(c,lease,&t,draws,codes,6,++seq,&shade)==MCMTL_OK);mcmtl_ext_far_engine_drop(lease);
        ConsumerReadback *holes=layerReadback(main,o);layerArgs(holes,words,codes,6,clippedLayer==0,clippedLayer==1);
        r=layerReceipt(c,seq);CHECK(r.flags==MCMTL_FAR_CONSUMER_RECEIPT_VALID);
        CHECK(r.holePixels[clippedLayer]>100 && !r.holePixels[1-clippedLayer]);
        CHECK(!r.alphaRejected[0] && !r.alphaRejected[1]);layerDiagnosticCheck(c,seq,r);
        CHECK(!memcmp((uint8_t *)holes.pixels.contents+(3-clippedLayer)*256*H,
            (uint8_t *)normal.pixels.contents+(3-clippedLayer)*256*H,256*H));
        onMain(^{CHECK(mcmtl_ext_far_engine_stop(e)==MCMTL_OK);});mcmtl_ext_far_engine_drop(e);
      }
      // Mirror Java's release polling. There are exactly three global geometry
      // pin slots; this temporary fixture must free its slot before the next.
      uint64_t tokens[3]={0};NSDate *until=[NSDate dateWithTimeIntervalSinceNow:10];
      unsigned released=0;
      do {released=mcmtl_ext_far_geometry_poll(tokens,3);if(released)break;[NSThread sleepForTimeInterval:0.001];}
      while(until.timeIntervalSinceNow>0);
      CHECK(released!=0);
    }
    // An actually rasterized face with a collapsed peer triangle exercises the
    // first-reason-9 sample. CUTOUT first must not consume the SOLID log allowance;
    // two SOLID receipts then produce only one report. Diagnostics-off output and
    // hole fallback must match, with no sample logging on that first receipt.
    for(unsigned pass=0;pass<3;++pass) { @autoreleasepool {
        unsigned layer=pass==0?1:0;
        MCMTLFarGeometryRecipe *g=shadedGeometryLayers(main,block,compact,YES,256u<<layer);CHECK(g);
        mcmtl_handle e=0;CHECK(mcmtl_ext_far_engine_create(h(main),&config,&e,error,sizeof error)==MCMTL_OK);
        CHECK(mcmtl_ext_far_engine_enable_shading(e,4u<<20,error,sizeof error)==MCMTL_OK);
        mcmtl_far_engine_submission s=submission;s.inputCount=2;s.geometryRecipe=h(g);
        CHECK(mcmtl_ext_far_engine_submit(e,&s,error,sizeof error)==MCMTL_OK);
        shade.flags=1;lease=acquire(e,41,1);
        CHECK(layerEncode(c,lease,&t,draws,codes,6,++seq,&shade)==MCMTL_OK);mcmtl_ext_far_engine_drop(lease);
        ConsumerReadback *off=layerReadback(main,o);layerArgs(off,words,codes,6,layer==0,layer==1);
        shade.flags=3;lease=acquire(e,41,1);
        CHECK(layerEncode(c,lease,&t,draws,codes,6,++seq,&shade)==MCMTL_OK);mcmtl_ext_far_engine_drop(lease);
        ConsumerReadback *on=layerReadback(main,o);layerArgs(on,words,codes,6,layer==0,layer==1);
        CHECK(!memcmp(off.pixels.contents,on.pixels.contents,4*256*H));
        r=layerReceipt(c,seq);CHECK(r.flags==MCMTL_FAR_CONSUMER_RECEIPT_VALID);
        CHECK(r.holePixels[layer]>100 && !r.holePixels[1-layer]);layerDiagnosticCheck(c,seq,r);
        uint32_t histogram[32]={0};
        CHECK(mcmtl_ext_far_consumer_layer_diagnostics(c,seq,histogram,sizeof histogram)==MCMTL_OK);
        CHECK(histogram[layer*16+9]==r.holePixels[layer] && !histogram[(1-layer)*16+9]);
        onMain(^{CHECK(mcmtl_ext_far_engine_stop(e)==MCMTL_OK);});mcmtl_ext_far_engine_drop(e);
      }
      uint64_t tokens[3]={0};NSDate *until=[NSDate dateWithTimeIntervalSinceNow:10];unsigned released=0;
      do {released=mcmtl_ext_far_geometry_poll(tokens,3);if(released)break;[NSThread sleepForTimeInterval:0.001];}
      while(until.timeIntervalSinceNow>0);CHECK(released!=0);
    }
    // Legacy APIs retain their own receipt and argument buffers. Shared sequence
    // order remains strict across entry points.
    uint64_t lastLayer=seq;
    lease=acquire(engine,41,1);CHECK(encode(c,lease,&t,1,draws,6,++seq)==MCMTL_OK);mcmtl_ext_far_engine_drop(lease);
    CHECK(completed(readback(main,old).command));oldStats=receipt(c,seq);
    CHECK(oldStats.flags==MCMTL_FAR_CONSUMER_RECEIPT_VALID && !oldStats.reserved64);
    CHECK(layerReceipt(c,lastLayer).encodedSequence==lastLayer);
    layerDiagnosticCheck(c,lastLayer,r);
    lease=acquire(engine,41,1);CHECK(layerEncode(c,lease,&t,draws,codes,6,seq,&shade)==MCMTL_E_INVALID_ARGUMENT);
    CHECK(layerEncode(c,lease,&t,NULL,NULL,0,++seq,&shade)==MCMTL_OK);mcmtl_ext_far_engine_drop(lease);
    layerArgs(layerReadback(main,o),words,codes,0,NO,NO);r=layerReceipt(c,seq);
    CHECK(!r.drawCount[0] && !r.drawCount[1]);
    // Per-receipt code buffers cannot be overwritten by later CPU calls. Queue
    // three differently ordered lists behind a test-only GPU gate; a fourth
    // declines without consuming the lease. Readbacks interleave in MAIN order.
    id<MTLSharedEvent> gate=[main->device newSharedEvent];cb=[main->queue commandBuffer];
    [cb encodeWaitForEvent:gate value:1];[cb commit];
    NSMutableArray<ConsumerReadback *> *pending=[NSMutableArray array];uint32_t queuedCodes[3][6];
    light[3]=254;uploadMaterial(main,block.lightmap,light,103);shade.committedSequence=103;
    for(unsigned i=0;i<3;++i) {
        uint32_t first=codes[0];memmove(codes,codes+1,20);codes[5]=first;memcpy(queuedCodes[i],codes,sizeof codes);
        lease=acquire(engine,41,1);CHECK(layerEncode(c,lease,&t,draws,codes,6,++seq,&shade)==MCMTL_OK);
        mcmtl_ext_far_engine_drop(lease);[pending addObject:layerReadback(main,o)];
    }
    lease=acquire(engine,41,1);CHECK(layerEncode(c,lease,&t,draws,codes,6,seq+1,&shade)==MCMTL_FAR_CONSUMER_DECLINED);
    gate.signaledValue=1;
    for(unsigned i=0;i<3;++i)layerArgs(pending[i],words,queuedCodes[i],6,NO,YES);
    r=layerReceipt(c,seq);layerDiagnosticCheck(c,seq,r);
    CHECK(layerEncode(c,lease,&t,draws,codes,6,++seq,&shade)==MCMTL_OK);mcmtl_ext_far_engine_drop(lease);
    r=layerReceipt(c,seq);CHECK(r.flags==MCMTL_FAR_CONSUMER_RECEIPT_VALID);
    // Completion holds the source lease and enabled consumer, including the new
    // resolver/code/diagnostic resources, after caller drops all its ownership.
    gate=[main->device newSharedEvent];cb=[main->queue commandBuffer];[cb encodeWaitForEvent:gate value:1];[cb commit];
    lease=acquire(engine,41,1);__weak id weakLease=obj(lease),weakConsumer=obj(c);
    CHECK(layerEncode(c,lease,&t,draws,codes,6,++seq,&shade)==MCMTL_OK);
    mcmtl_ext_far_engine_drop(lease);dropOutputs(o);dropOutputs(old);mcmtl_ext_far_engine_drop(c);
    CHECK(weakLease && weakConsumer);gate.signaledValue=1;CHECK(weakGone(&weakLease));CHECK(weakGone(&weakConsumer));
    CHECK(!main->status->deviceLost);
}
static void run(void) { @autoreleasepool {
    id<MTLDevice> device=MTLCreateSystemDefaultDevice(); CHECK(device); if (!device) return;
    MCMTLDevice *main=mcmtl_device_context_create(device,@"shaded consumer main queue"); CHECK(main); if (!main) return;
    ProducerFixture *block=fixture(device,MCMTLFarShaderBlock), *compact=fixture(device,MCMTLFarShaderCompact);
    MCMTLFarGeometryRecipe *g=shadedGeometry(main,block,compact,NO); CHECK(g); if (!g) return;
    char error[512]={0}; mcmtl_handle engine=0,c=0;
    // 2x guard image is cropped to the central viewport, exercising nonidentity
    // transform admission and reconstruction in CURRENT viewport coordinates.
    const uint32_t guardW=2*W,guardH=2*H;
    mcmtl_far_engine_config config={sizeof config,guardW,guardH,0,41,guardW*guardH*72,1u<<20};
    CHECK(mcmtl_ext_far_engine_create(h(main),&config,&engine,error,sizeof error)==MCMTL_OK);
    CHECK(mcmtl_ext_far_engine_enable_shading(engine,4u<<20,error,sizeof error)==MCMTL_OK);
    const uint64_t budget=W*H*79+3*40+16+3*(8+4);
    CHECK(mcmtl_ext_far_consumer_create(h(main),W,H,3,budget-1,&c,error,sizeof error)<0 && !c);
    CHECK(mcmtl_ext_far_consumer_create(h(main),W,H,3,budget,&c,error,sizeof error)==MCMTL_OK);
    if (!engine || !c) { fprintf(stderr,"creation: %s\n",error); return; }
    mcmtl_far_consumer_outputs o={.structSize=sizeof o};
    CHECK(mcmtl_ext_far_consumer_outputs(c,&o,error,sizeof error)==MCMTL_OK);
    NSMutableArray *shaders=[NSMutableArray array];
    MTLCompileOptions *opts=[MTLCompileOptions new]; opts.languageVersion=MTLLanguageVersion3_2; opts.mathMode=MTLMathModeFast;
    mcmtl_far_engine_submission submission={.structSize=sizeof submission,.inputCount=4,
        .source={41,clock_gettime_nsec_np(CLOCK_UPTIME_RAW),10,7,7},.geometryRecipe=h(g),
        .committedTicketSequence=7,.geometryRequiredValue=6,.committed=1};
    for (unsigned i=0;i<4;++i) {
        unsigned layout=i<2?MCMTLFarShaderBlock:MCMTLFarShaderCompact,layer=i&1,code=i<2?i:i+6;
        NSString *v=vertexFixture(layout,0),*f=fragmentFixture(0,layer,0);
        MCMTLPipeline *p=originalPipeline(main,v,f,@"main0",@"main0",opts,0,NO);
        MCMTLFarShader *shader=mcmtl_farshader_compile(p,mcmtl_variant_key(1,MTLPixelFormatDepth32Float),
            v,f,@"main0",@"main0",opts,layout,layer,NULL);
        CHECK(shader); if (!shader) return;
        [shaders addObject:shader]; submission.shadersByLayer[code]=h(shader);
    }
    float guardProjection[16]; memcpy(guardProjection,block.projection.contents,sizeof guardProjection);
    for (unsigned col=0;col<4;++col) { guardProjection[4*col]*=.5f; guardProjection[4*col+1]*=.5f; }
    memcpy(submission.camera.guardProjection,guardProjection,sizeof guardProjection);
    matrix_float4x4 identity=matrix_identity_float4x4; memcpy(submission.camera.view,&identity,64);
    mcmtl_far_engine_uniforms uniforms[4];
    for (unsigned i=0;i<4;++i) uniforms[i]=(mcmtl_far_engine_uniforms){
        {guardProjection,64},{block.globals.contents,48},{block.fog.contents,48},{block.terrain.contents,80}};
    submission.uniforms=uniforms;
    mcmtl_far_material_source sources[2]={
        {h(block.atlas),h(block.atlas),h(block.sampler),0,1,1,0,0,0},
        {h(block.lightmap),h(block.lightmap),h(block.sampler),0,1,1,0,0,0}};
    MCMTLFarMaterialRecipe *recipe=[[MCMTLFarMaterialRecipe alloc] initWithDevice:device sources:sources budgetBytes:1u<<20 error:NULL];
    MCMTLFarMaterialVersion *material=[[MCMTLFarMaterialVersion alloc] initWithRecipe:recipe error:NULL]; CHECK(material); if (!material) return;
    submission.materialVersion=h(material);
    id<MTLCommandBuffer> upload=[main->queue commandBuffer]; id<MTLBlitCommandEncoder> copies=[upload blitCommandEncoder];
    for (NSUInteger i=0;i<material.copyJobCount;++i) {
        mcmtl_far_material_copy_job job={0}; CHECK([material copyJobAtIndex:i into:&job]);
        [copies copyFromTexture:obj(job.source) sourceSlice:0 sourceLevel:job.level sourceOrigin:MTLOriginMake(0,0,0)
            sourceSize:MTLSizeMake(job.width,job.height,1) toTexture:obj(job.destination)
            destinationSlice:0 destinationLevel:job.level destinationOrigin:MTLOriginMake(0,0,0)];
    }
    [copies endEncoding]; [upload encodeSignalEvent:main->event value:7]; [upload commit];
    mcmtl_status_store_u64(&main->status->submittedValue,7);
    CHECK(([material publishCommittedCopies:(mcmtl_far_material_copy_dependency){h(main->event),7,(uint32_t)material.copyJobCount,1} error:NULL]));
    int submitted=mcmtl_ext_far_engine_submit(engine,&submission,error,sizeof error);
    CHECK(submitted==MCMTL_OK); if (submitted) { fprintf(stderr,"submit: %s\n",error); return; }
    mcmtl_handle lease=acquire(engine,41,1); CHECK(lease); if (!lease) return;
    CHECK(mcmtl_far_engine_shading(lease,0)!=nil && !mcmtl_far_engine_shading(lease,1));
    const uint32_t expected[10]={6,1,7,(uint32_t)-11,19,12,3,13,17,23};
    uint8_t scattered[64]={0}; memcpy(scattered+4,expected,20); memcpy(scattered+32,expected+5,20);
    id<MTLBuffer> staging=buffer(device,scattered,sizeof scattered);
    id<MTLBuffer> current=[device newBufferWithLength:sizeof scattered options:MTLResourceStorageModePrivate];
    mcmtl_far_consumer_fallback draws[2]={{h(current),4},{h(current),32}};
    id<MTLCommandBuffer> cull=[main->queue commandBuffer]; id<MTLBlitCommandEncoder> fill=[cull blitCommandEncoder];
    [fill copyFromBuffer:staging sourceOffset:0 toBuffer:current destinationOffset:0 size:sizeof scattered];
    [fill endEncoding]; [cull encodeSignalEvent:main->event value:8]; [cull commit];
    mcmtl_status_store_u64(&main->status->submittedValue,8);
    MCMTLFarWarpTransform t=transform(0); t.currentToSourceClip[0]=t.currentToSourceClip[5]=.5f;
    t.sourceToCurrentClip[0]=t.sourceToCurrentClip[5]=2;
    mcmtl_far_consumer_shading shade={.structSize=sizeof shade,.flags=1,.committedSequence=8};
    memcpy(shade.uniforms,block.projection.contents,64); memcpy(shade.uniforms+64,block.globals.contents,48);
    memcpy(shade.uniforms+112,block.fog.contents,48); memcpy(shade.uniforms+160,block.terrain.contents,80);
    memcpy(shade.current,sources,sizeof sources);
    CHECK(mcmtl_ext_far_consumer_encode_shaded(c,lease,&t,draws,2,1,&shade,NULL,0)==MCMTL_E_INVALID_ARGUMENT); // Worker.
    mcmtl_far_consumer_shading bad=shade; bad.structSize--;
    CHECK(shadedEncode(c,lease,&t,draws,2,1,&bad)==MCMTL_E_ABI_MISMATCH);
    bad=shade; bad.flags=0; CHECK(shadedEncode(c,lease,&t,draws,2,1,&bad)==MCMTL_E_INVALID_ARGUMENT);
    bad=shade; bad.flags=3; CHECK(shadedEncode(c,lease,&t,draws,2,1,&bad)==MCMTL_E_INVALID_ARGUMENT);
    bad=shade; bad.committedSequence=0; CHECK(shadedEncode(c,lease,&t,draws,2,1,&bad)==MCMTL_E_INVALID_ARGUMENT);
    bad=shade; bad.committedSequence=9; CHECK(shadedEncode(c,lease,&t,draws,2,1,&bad)==MCMTL_E_INVALID_ARGUMENT);
    // Symmetric INTEGER crop with odd helper offset must still decline: 128->62
    // gives x=33. This is checked natively, independent of the Java helper.
    mcmtl_handle oddConsumer=0;
    CHECK(mcmtl_ext_far_consumer_create(h(main),W-2,H,3,budget,&oddConsumer,error,sizeof error)==MCMTL_OK);
    MCMTLFarWarpTransform odd=t; odd.currentToSourceClip[0]=(float)((double)(W-2)/guardW);
    odd.sourceToCurrentClip[0]=(float)((double)guardW/(W-2));
    CHECK(shadedEncode(oddConsumer,lease,&odd,draws,2,1,&shade)==MCMTL_FAR_CONSUMER_DECLINED);
    mcmtl_ext_far_engine_drop(oddConsumer);
    MCMTLFarWarpTransform wrong=t; wrong.currentToSourceClip[0]=nextafterf(.5f,1);
    CHECK(shadedEncode(c,lease,&wrong,draws,2,1,&shade)==MCMTL_FAR_CONSUMER_DECLINED);
    wrong=t; wrong.sourceToCurrentClip[0]=nextafterf(2,3);
    CHECK(shadedEncode(c,lease,&wrong,draws,2,1,&shade)==MCMTL_FAR_CONSUMER_DECLINED);
    wrong=t; wrong.currentToSourceClip[12]=.015625f; wrong.sourceToCurrentClip[12]=-.03125f;
    CHECK(shadedEncode(c,lease,&wrong,draws,2,1,&shade)==MCMTL_FAR_CONSUMER_DECLINED); // Exact crop, off-center.
    wrong=t; wrong.motion=MCMTLFarWarpMotionTranslation;
    CHECK(shadedEncode(c,lease,&wrong,draws,2,1,&shade)==MCMTL_FAR_CONSUMER_DECLINED);
    wrong=t; wrong.translation[0]=NAN;
    CHECK(shadedEncode(c,lease,&wrong,draws,2,1,&shade)==MCMTL_FAR_CONSUMER_DECLINED);
    bad=shade; bad.current[0].samplerLODClamp=0;
    CHECK(shadedEncode(c,lease,&t,draws,2,1,&bad)<0);
    bad=shade; bad.current[0].lodMax=1;
    CHECK(shadedEncode(c,lease,&t,draws,2,1,&bad)<0);
    bad=shade; bad.current[0].levelCount=2;
    CHECK(shadedEncode(c,lease,&t,draws,2,1,&bad)<0);
    MTLSamplerDescriptor *different=[MTLSamplerDescriptor new]; different.sAddressMode=MTLSamplerAddressModeRepeat;
    id<MTLSamplerState> sampler=[device newSamplerStateWithDescriptor:different]; CHECK(sampler!=block.sampler);
    bad=shade; bad.current[0].sampler=h(sampler);
    CHECK(shadedEncode(c,lease,&t,draws,2,1,&bad)<0);
    // Original encode still works on the SAME consumer and does not inspect the
    // material-alpha scratch or require the new descriptor.
    CHECK(encode(c,lease,&t,1,draws,2,1)==MCMTL_OK); mcmtl_ext_far_engine_drop(lease);
    ConsumerReadback *original=readback(main,o); assertArgs(original,expected,NO); validateReceipt(c,1,0,NO,2);
    // All preceding rejected shaded attempts left the lease reusable above.
    uint8_t atlas[32*16*4];
    [block.atlas getBytes:atlas bytesPerRow:128 fromRegion:MTLRegionMake2D(0,0,32,16) mipmapLevel:0];
    for (unsigned i=0;i<32*16;++i) { atlas[4*i]=255-atlas[4*i]; atlas[4*i+1]/=2; }
    uploadMaterial(main,block.atlas,atlas,9); shade.committedSequence=9;
    lease=acquire(engine,41,1);
    CHECK(shadedEncode(c,lease,&t,draws,2,2,&shade)==MCMTL_OK);
    CHECK(shadedEncode(c,lease,&t,draws,2,2,&shade)==MCMTL_E_INVALID_ARGUMENT);
    CHECK(shadedEncode(c,lease,&t,draws,2,3,&shade)==MCMTL_FAR_CONSUMER_DECLINED);
    mcmtl_ext_far_engine_drop(lease);
    ConsumerReadback *rgb=readback(main,o); assertArgs(rgb,expected,NO); validateReceipt(c,2,0,NO,2);
    unsigned changed=0;
    const uint8_t *before=original.pixels.contents,*after=rgb.pixels.contents;
    for (unsigned i=0;i<2*256*H;++i) changed+=before[i]!=after[i]; CHECK(changed>100);
    CHECK(!memcmp(before+2*256*H,after+2*256*H,2*256*H)); // Exact preserved hardware depth.
    mcmtl_far_consumer_stats stats=receipt(c,2); CHECK(!stats.holePixels);
    // Change a hidden/non-sampled alpha texel: shading itself still has zero holes,
    // but the independent whole-parent guard MUST restore synchronous draws.
    atlas[3]=0; uploadMaterial(main,block.atlas,atlas,10); shade.committedSequence=10;
    lease=acquire(engine,41,1); CHECK(shadedEncode(c,lease,&t,draws,2,3,&shade)==MCMTL_OK); mcmtl_ext_far_engine_drop(lease);
    ConsumerReadback *alpha=readback(main,o); assertArgs(alpha,expected,YES); validateReceipt(c,3,1,YES,2);
    stats=receipt(c,3); CHECK(!stats.holePixels && !main->status->deviceLost);
    atlas[3]=255; uploadMaterial(main,block.atlas,atlas,11); shade.committedSequence=11;
    lease=acquire(engine,41,1); CHECK(shadedEncode(c,lease,&t,draws,2,4,&shade)==MCMTL_OK); mcmtl_ext_far_engine_drop(lease);
    ConsumerReadback *reset=readback(main,o); assertArgs(reset,expected,NO); validateReceipt(c,4,0,NO,2);
    uint8_t light[4]={170,190,210,254}; uploadMaterial(main,block.lightmap,light,12); shade.committedSequence=12;
    lease=acquire(engine,41,1); CHECK(shadedEncode(c,lease,&t,draws,2,5,&shade)==MCMTL_OK); mcmtl_ext_far_engine_drop(lease);
    ConsumerReadback *lightAlpha=readback(main,o); assertArgs(lightAlpha,expected,YES); validateReceipt(c,5,1,YES,2);
    // Alpha rejection remains fallback for zero draws, clears all previously-used
    // indirect words, and never poisons a normally functioning consumer.
    lease=acquire(engine,41,1); CHECK(shadedEncode(c,lease,&t,NULL,0,6,&shade)==MCMTL_OK); mcmtl_ext_far_engine_drop(lease);
    ConsumerReadback *zero=readback(main,o); CHECK(completed(zero.command));
    for (unsigned i=0;i<19;++i) CHECK(((uint32_t *)zero.args.contents)[i]==0);
    validateReceipt(c,6,1,YES,0);
    // Three receipt slots: hold the MAIN queue, queue changing materials and three
    // sidecars without waiting, then verify each GPU result and the final receipt.
    id<MTLSharedEvent> gate=[device newSharedEvent]; id<MTLCommandBuffer> blocked=[main->queue commandBuffer];
    [blocked encodeWaitForEvent:gate value:1]; [blocked commit];
    NSMutableArray<ConsumerReadback *> *pending=[NSMutableArray array];
    uint64_t start=clock_gettime_nsec_np(CLOCK_UPTIME_RAW);
    for (unsigned i=0;i<3;++i) {
        light[3]=i==1?254:255; uploadMaterial(main,block.lightmap,light,13+i); shade.committedSequence=13+i;
        lease=acquire(engine,41,1); CHECK(shadedEncode(c,lease,&t,draws,2,7+i,&shade)==MCMTL_OK);
        mcmtl_ext_far_engine_drop(lease); [pending addObject:readback(main,o)];
    }
    lease=acquire(engine,41,1); CHECK(shadedEncode(c,lease,&t,draws,2,10,&shade)==MCMTL_FAR_CONSUMER_DECLINED);
    CHECK(clock_gettime_nsec_np(CLOCK_UPTIME_RAW)-start<1000000000ull); // Structural no-wait, NOT performance.
    gate.signaledValue=1;
    for (unsigned i=0;i<3;++i) assertArgs(pending[i],expected,i==1);
    validateReceipt(c,9,0,NO,2);
    CHECK(shadedEncode(c,lease,&t,draws,2,10,&shade)==MCMTL_OK); mcmtl_ext_far_engine_drop(lease);
    validateReceipt(c,10,0,NO,2); // Busy decline did not consume the fourth lease.
    CHECK(!main->status->deviceLost);
    // A near-plane-clipped quad is legitimately visible to hardware, but outside
    // current shade reconstruction's admission. It must produce ordinary hole
    // fallback even with unchanged alpha. ONLY BLOCK inputs are drawn here;
    // paired compact shader preparation must not invent compact geometry.
    ProducerFixture *clipped=fixture(device,MCMTLFarShaderBlock);
    ((float *)clipped.arenaA.contents)[25*28+2]=-4;
    MCMTLFarGeometryRecipe *clippedGeometry=shadedGeometry(main,clipped,compact,YES);
    CHECK(clippedGeometry);
    mcmtl_handle clippedEngine=0;
    CHECK(mcmtl_ext_far_engine_create(h(main),&config,&clippedEngine,error,sizeof error)==MCMTL_OK);
    CHECK(mcmtl_ext_far_engine_enable_shading(clippedEngine,4u<<20,error,sizeof error)==MCMTL_OK);
    mcmtl_far_engine_submission clippedSubmission=submission;
    clippedSubmission.inputCount=2; clippedSubmission.geometryRecipe=h(clippedGeometry);
    submitted=mcmtl_ext_far_engine_submit(clippedEngine,&clippedSubmission,error,sizeof error);
    CHECK(submitted==MCMTL_OK);
    if (submitted) { fprintf(stderr,"clipped submit: %s\n",error); return; }
    lease=acquire(clippedEngine,41,1); CHECK(mcmtl_far_engine_shading(lease,0)!=nil);
    CHECK(shadedEncode(c,lease,&t,draws,2,11,&shade)==MCMTL_OK); mcmtl_ext_far_engine_drop(lease);
    ConsumerReadback *holes=readback(main,o); assertArgs(holes,expected,YES); validateReceipt(c,11,0,YES,2);
    stats=receipt(c,11); CHECK(stats.holePixels>100 && !stats.reserved64);
    // Combined cause: same real holes AND changed alpha is also normal fallback.
    light[3]=254; uploadMaterial(main,block.lightmap,light,16); shade.committedSequence=16;
    lease=acquire(clippedEngine,41,1);
    CHECK(shadedEncode(c,lease,&t,draws,2,12,&shade)==MCMTL_OK); mcmtl_ext_far_engine_drop(lease);
    ConsumerReadback *both=readback(main,o); assertArgs(both,expected,YES); validateReceipt(c,12,1,YES,2);
    stats=receipt(c,12); CHECK(stats.holePixels>100 && stats.reserved64==1 && !main->status->deviceLost);
    onMain(^{CHECK(mcmtl_ext_far_engine_stop(clippedEngine)==MCMTL_OK);}); mcmtl_ext_far_engine_drop(clippedEngine);
    // Old API receipt must clear the additive alpha field even after a rejected
    // shaded frame (same consumer/ring). It reads immutable image pixels only.
    lease=acquire(engine,41,1); CHECK(encode(c,lease,&t,1,draws,2,13)==MCMTL_OK);
    mcmtl_ext_far_engine_drop(lease);
    ConsumerReadback *oldAfter=readback(main,o); assertArgs(oldAfter,expected,NO); validateReceipt(c,13,0,NO,2);
    CHECK(!memcmp(oldAfter.pixels.contents,original.pixels.contents,4*256*H));
    runLayers(main,engine,config,submission,block,compact,t,shade);
    // Accepted sidecar retains both consumer and source lease through actual GPU
    // completion even after all external +1s and exported resources are dropped.
    gate=[device newSharedEvent]; blocked=[main->queue commandBuffer];
    [blocked encodeWaitForEvent:gate value:1]; [blocked commit];
    lease=acquire(engine,41,1); __weak id weakLease=obj(lease),weakConsumer=obj(c);
    CHECK(shadedEncode(c,lease,&t,draws,2,14,&shade)==MCMTL_OK);
    mcmtl_ext_far_engine_drop(lease); dropOutputs(o); mcmtl_ext_far_engine_drop(c);
    CHECK(weakLease && weakConsumer);
    onMain(^{ CHECK(mcmtl_ext_far_engine_stop(engine)==MCMTL_OK); }); mcmtl_ext_far_engine_drop(engine);
    gate.signaledValue=1; CHECK(weakGone(&weakLease)); CHECK(weakGone(&weakConsumer));
} }
int main(void) { @autoreleasepool {
    __block _Atomic(bool) done=false;
    dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED,0),^{ @autoreleasepool { run(); atomic_store(&done,true); } });
    NSDate *deadline=[NSDate dateWithTimeIntervalSinceNow:120];
    while (!atomic_load(&done) && deadline.timeIntervalSinceNow>0)
        [[NSRunLoop currentRunLoop] runUntilDate:[NSDate dateWithTimeIntervalSinceNow:0.01]];
    if (!atomic_load(&done)) { fprintf(stderr,"farimage_shaded_consumer_test: worker timeout\n"); return 1; }
    printf("farimage_shaded_consumer_test: %u checks, %u failures\n",checks,failures); return failures?1:0;
} }
