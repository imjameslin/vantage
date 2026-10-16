#include "lod_scene_compact.h"
#include <cstdio>
#include <signal.h>
#include <random>
#include <functional>
using lodscene::detail::check;
static uint64_t checks = 0;
static void expect(bool b, const char* what) { ++checks; check(b, what); }
static void rejects(const std::function<void()>& f, const char* needle) {
    bool threw = false;
    try { f(); } catch (const std::runtime_error& e) { threw = std::strstr(e.what(), needle) != nullptr; }
    expect(threw, needle);
}
static id<MTLBuffer> buffer(id<MTLDevice> dev, NSUInteger n, MTLResourceOptions opt = MTLResourceStorageModeShared) {
    auto b = [dev newBufferWithLength:std::max<NSUInteger>(4, n) options:opt];
    expect(b != nil, "fixture buffer"); return b;
}
static void finish(id<MTLCommandBuffer> cb) {
    dispatch_semaphore_t sem = dispatch_semaphore_create(0);
    [cb addCompletedHandler:^(id<MTLCommandBuffer>){ dispatch_semaphore_signal(sem); }];
    [cb commit];
    expect(dispatch_semaphore_wait(sem, dispatch_time(DISPATCH_TIME_NOW, 10*NSEC_PER_SEC)) == 0, "GPU timeout");
    expect(cb.status == MTLCommandBufferStatusCompleted && !cb.error, "GPU error");
}
static NSDictionary* mappedTests(id<MTLDevice> dev, id<MTLCommandQueue> queue,
                                 const lodscene::Scene& scene,
                                 const std::vector<std::vector<uint32_t>>& inputs) {
    NSMutableArray* runs = [NSMutableArray array];
    std::mt19937 rng(0x817390ab);
    uint64_t added = 0;
    for (uint32_t layout=0; layout<2; ++layout) { @autoreleasepool {
        std::vector<id<MTLBuffer>> membership, masks, staged;
        std::vector<uint32_t> maskCounts;
        for (size_t g=0; g<inputs.size(); ++g) {
            const uint32_t sizes[] = {1,131071,17,257};
            uint32_t n = sizes[g%4]; maskCounts.push_back(n);
            auto ids = inputs[g].empty() ? nil : buffer(dev, 4ull*inputs[g].size());
            membership.push_back(ids);
            auto* dst = static_cast<uint32_t*>(ids.contents);
            for (size_t i=0; i<inputs[g].size(); ++i) {
                // Layout 0: entry-specific, sparse and nonmonotonic. Layout 1:
                // group repeated source-cluster indices without regrouping entries.
                dst[i] = layout==0 ? uint32_t((uint64_t(i/3)*7919+(i%3)*31+g*65521)%n)
                                   : uint32_t((uint64_t(inputs[g][i]>>6)*61+g)%n);
            }
            if (layout==0 && !inputs[g].empty()) dst[0]=n-1; // largest legal ID, including > original cluster count
            staged.push_back(buffer(dev, 4ull*n));
            masks.push_back(buffer(dev, 4ull*n, MTLResourceStorageModePrivate));
        }
        // Every invalid constructor must throw before there is any GPU encode.
        auto wrongMembership = membership; wrongMembership.pop_back();
        rejects([&]{lodcompact::Filter f(dev,scene,wrongMembership,maskCounts);},"vectors");
        auto wrongCounts = maskCounts; wrongCounts.pop_back();
        rejects([&]{lodcompact::Filter f(dev,scene,membership,wrongCounts);},"vectors");
        wrongCounts=maskCounts;wrongCounts[0]=0; // even an empty draw requires a real mask
        rejects([&]{lodcompact::Filter f(dev,scene,membership,wrongCounts);},"at least one");
        wrongCounts=maskCounts;wrongCounts.back()=0; // absent draw also requires a real mask
        rejects([&]{lodcompact::Filter f(dev,scene,membership,wrongCounts);},"at least one");
        wrongMembership=membership;wrongMembership[3]=nil;
        rejects([&]{lodcompact::Filter f(dev,scene,wrongMembership,maskCounts);},"membership uint buffer ABI");
        wrongMembership=membership;wrongMembership[3]=buffer(dev,4);
        rejects([&]{lodcompact::Filter f(dev,scene,wrongMembership,maskCounts);},"membership uint buffer ABI");
        wrongMembership=membership;wrongMembership[3]=buffer(dev,4ull*inputs[3].size()+1);
        rejects([&]{lodcompact::Filter f(dev,scene,wrongMembership,maskCounts);},"membership uint buffer ABI");
        wrongMembership=membership;wrongMembership[3]=buffer(dev,4ull*inputs[3].size(),MTLResourceStorageModeShared|MTLResourceHazardTrackingModeUntracked);
        rejects([&]{lodcompact::Filter f(dev,scene,wrongMembership,maskCounts);},"membership uint buffer ABI");
        wrongMembership=membership;wrongMembership[3]=buffer(dev,4ull*inputs[3].size(),MTLResourceStorageModePrivate);
        rejects([&]{lodcompact::Filter f(dev,scene,wrongMembership,maskCounts);},"CPU contents");
        wrongMembership=membership;wrongMembership[1]=buffer(dev,4);
        *static_cast<uint32_t*>(wrongMembership[1].contents)=maskCounts[1];
        rejects([&]{lodcompact::Filter f(dev,scene,wrongMembership,maskCounts);},"membership ID");
        *static_cast<uint32_t*>(wrongMembership[1].contents)=UINT32_MAX;
        rejects([&]{lodcompact::Filter f(dev,scene,wrongMembership,maskCounts);},"membership ID");
        wrongMembership=membership;wrongMembership[3]=buffer(dev,4ull*inputs[3].size());
        memcpy(wrongMembership[3].contents,membership[3].contents,4ull*inputs[3].size());
        static_cast<uint32_t*>(wrongMembership[3].contents)[inputs[3].size()-1]=maskCounts[3];
        rejects([&]{lodcompact::Filter f(dev,scene,wrongMembership,maskCounts);},"membership ID"); // must validate the full array

        lodcompact::Filter mapped(dev,scene,membership,maskCounts);
        added=std::max(added,mapped.allocatedBytes());
        expect(mapped.groupCapacity()==inputs.size(),"mapped group capacity");
        for(size_t g=0;g<inputs.size();++g)expect(mapped.groupCapacity(g)==inputs[g].size(),"mapped original quad capacity");
        auto cb=[queue commandBuffer];
        auto wrongMasks=masks;wrongMasks[0]=nil;
        rejects([&]{mapped.encode(cb,wrongMasks);},"mask ABI");
        wrongMasks=masks;wrongMasks[1]=buffer(dev,4ull*scene.groups[1].clusterCount);
        rejects([&]{mapped.encode(cb,wrongMasks);},"mask ABI"); // new mask count is larger than original
        finish(cb);
        for(uint32_t mode=0;mode<6;++mode) {
            for(size_t g=0;g<inputs.size();++g) {
                auto* h=static_cast<uint32_t*>(staged[g].contents);
                for(uint32_t i=0;i<maskCounts[g];++i)
                    h[i]=mode==0 ? 0 : mode==1 ? 1 : mode==2 ? i%2 : mode==3 ? uint32_t(rng()%3==0)
                         : mode==4 ? (scene.groups[g].layer==2 ? 0u : uint32_t(rng()%2)) : (i%3==0 ? 1u : UINT32_MAX);
                memset(mapped.groupArgs(g).contents,0xb7,20);
                memset(mapped.groupQuads(g).contents,0xab,mapped.groupQuads(g).length);
            }
            cb=[queue commandBuffer];auto blit=[cb blitCommandEncoder];
            for(size_t g=0;g<inputs.size();++g)
                [blit copyFromBuffer:staged[g] sourceOffset:0 toBuffer:masks[g] destinationOffset:0 size:staged[g].length];
            [blit endEncoding];mapped.encode(cb,masks);finish(cb);
            uint64_t retained=0;
            for(size_t g=0;g<inputs.size();++g) {
                const auto* ids=static_cast<const uint32_t*>(membership[g].contents);
                const auto* h=static_cast<const uint32_t*>(staged[g].contents);
                std::vector<uint32_t> ref;
                for(size_t i=0;i<inputs[g].size();++i)if(h[ids[i]]!=1)ref.push_back(inputs[g][i]);
                uint32_t args[5]={(uint32_t)ref.size()*6u,1,0,0,0};
                expect(memcmp(mapped.groupArgs(g).contents,args,20)==0,"mapped exact five args fields");
                expect(ref.empty() || memcmp(mapped.groupQuads(g).contents,ref.data(),4ull*ref.size())==0,"mapped stable original-entry CPU reference");
                if(mode==4 && scene.groups[g].layer==2)expect(ref.size()==inputs[g].size(),"zero-mask water retained");
                for(const auto& d:scene.draws)if(d.groupOrdinal==g) {
                    expect(inputs[g].empty() || memcmp(d.quadBuffer.contents,inputs[g].data(),4ull*inputs[g].size())==0,"mapped input untouched");
                    uint32_t originalArgs[5]={d.quadCount*6u,1,0,0,0};
                    expect(memcmp(d.argsBuffer.contents,originalArgs,20)==0,"mapped original args untouched");
                }
                retained+=ref.size();
            }
            [runs addObject:@{@"layout":@(layout),@"mode":@(mode),@"outputQuads":@(retained),@"stableOriginalEntries":@YES}];
        }
    }}
    lodscene::Scene empty;empty.stats.validated=true;
    lodcompact::Filter emptyMapped(dev,empty,std::vector<id<MTLBuffer>>{},std::vector<uint32_t>{});
    auto cb=[queue commandBuffer];emptyMapped.encode(cb,{});finish(cb);
    expect(emptyMapped.groupCapacity()==0,"mapped empty scene");
    return @{@"cases":runs,@"maxAddedMetalBytes":@(added),@"originalGroupOrderAndEntriesPreserved":@YES};
}
int main(int argc, char** argv) { @autoreleasepool {
    alarm(60);
    try {
        expect(argc == 2, "report arg");
        expect(std::getenv("MC_GPU_LOCK_HELD") && std::strcmp(std::getenv("MC_GPU_LOCK_HELD"), "timing") == 0, "timing lane");
        id<MTLDevice> dev = MTLCreateSystemDefaultDevice(); expect(dev != nil, "Metal device");
        id<MTLCommandQueue> queue = [dev newCommandQueue]; expect(queue != nil, "queue");
        lodscene::Scene scene; scene.stats.validated = true;
        const std::vector<uint32_t> counts = {0,1,31,32,33,255,256,257,511,512,513,65535,65536,65537,131585,0};
        std::vector<std::vector<uint32_t>> inputs(counts.size());
        std::vector<id<MTLBuffer>> masks, staged;
        std::mt19937 rng(0x42ef7131);
        for (size_t g=0; g<counts.size(); ++g) {
            lodscene::Group group; group.ordinal = (uint32_t)g; group.layer = g%3;
            group.clusterCount = g==counts.size()-1 ? 0 : 100003;
            scene.groups.push_back(group);
            auto src = buffer(dev, 4ull*group.clusterCount);
            auto gpu = buffer(dev, 4ull*group.clusterCount, MTLResourceStorageModePrivate);
            masks.push_back(gpu); staged.push_back(src);
            if (g==counts.size()-1) continue; // no draw at all
            lodscene::Draw draw; draw.groupOrdinal = (uint32_t)g; draw.layerCode = group.layer+8;
            draw.quadCount = counts[g]; draw.usedClusterCount = group.clusterCount;
            draw.quadBuffer = buffer(dev, 4ull*draw.quadCount);
            draw.itemBuffer = buffer(dev, 8ull*draw.usedClusterCount);
            draw.argsBuffer = buffer(dev, 20);
            for (uint32_t i=0; i<draw.quadCount; ++i) {
                // Repeated, sparse, out-of-order original clusters and low quad IDs.
                uint32_t c = (uint64_t(i)*7919+g*31+(i%9)*65521)%group.clusterCount;
                inputs[g].push_back(c*64u + (rng()&63u));
            }
            if (draw.quadCount) memcpy(draw.quadBuffer.contents, inputs[g].data(), 4ull*draw.quadCount);
            uint32_t args[5] = {draw.quadCount*6,1,0,0,0}; memcpy(draw.argsBuffer.contents,args,20);
            scene.draws.insert(scene.draws.begin(), draw); // original draw order differs from ordinal order
        }
        lodcompact::Filter filter(dev, scene);
        expect(filter.groupCapacity()==scene.groups.size(), "group capacity");
        expect(filter.allocatedBytes()<(128ull<<20), "128 MiB budget");
        for(size_t g=0; g<counts.size(); ++g) expect(filter.groupCapacity(g)==counts[g], "quad capacity");
        rejects([&]{filter.groupQuads(counts.size());},"group ordinal");
        rejects([&]{filter.groupArgs(counts.size());},"group ordinal");
        rejects([&]{filter.groupCapacity(counts.size());},"group ordinal");
        // ABI failures happen before an encoder is appended; the same CB remains usable.
        id<MTLCommandBuffer> validation = [queue commandBuffer];
        rejects([&]{ filter.encode(nil, masks); }, "uncommitted");
        auto invalid = masks; invalid.pop_back();
        rejects([&]{filter.encode(validation,invalid);}, "mask vector");
        invalid=masks; invalid[3]=nil;
        rejects([&]{filter.encode(validation,invalid);}, "mask ABI");
        invalid=masks; invalid[3]=buffer(dev,4);
        rejects([&]{filter.encode(validation,invalid);}, "mask ABI");
        invalid=masks; invalid[3]=buffer(dev,4ull*scene.groups[3].clusterCount+1);
        rejects([&]{filter.encode(validation,invalid);}, "mask ABI");
        invalid=masks; invalid[3]=buffer(dev,4ull*scene.groups[3].clusterCount,MTLResourceStorageModeShared|MTLResourceHazardTrackingModeUntracked);
        rejects([&]{filter.encode(validation,invalid);}, "mask ABI");
        invalid=masks; invalid[15]=filter.groupQuads(15);
        rejects([&]{filter.encode(validation,invalid);}, "aliases");
        [validation commit]; [validation waitUntilCompleted];
        rejects([&]{filter.encode(validation,masks);}, "uncommitted");
        // Nil mask is permitted only for a zero-cluster group.
        masks.back()=nil;
        NSMutableArray* runs = [NSMutableArray array];
        for (uint32_t mode=0; mode<8; ++mode) {
            uint64_t keptTotal=0, inputTotal=0;
            for(size_t g=0; g<counts.size(); ++g) {
                uint32_t* m=(uint32_t*)staged[g].contents;
                for(uint32_t c=0;c<scene.groups[g].clusterCount;++c)
                    m[c] = mode==0 || mode==6 ? 0u : mode==1 || mode==7 ? 1u : mode==2 ? c%2 : mode==3 ? uint32_t(c%17==0) : mode==4 ? uint32_t(rng()%3==0) : (rng()%5);
                // Guard args for all five fields and overwrite old tails between cases.
                memset(filter.groupArgs(g).contents, 0xb7, 20);
                memset(filter.groupQuads(g).contents, 0xab, filter.groupQuads(g).length);
            }
            id<MTLCommandBuffer> cb=[queue commandBuffer];
            id<MTLBlitCommandEncoder> b=[cb blitCommandEncoder];
            for(size_t g=0;g<counts.size();++g) if(masks[g])
                [b copyFromBuffer:staged[g] sourceOffset:0 toBuffer:masks[g] destinationOffset:0 size:staged[g].length];
            [b endEncoding];
            filter.encode(cb,masks); finish(cb);
            for(size_t g=0;g<counts.size();++g) {
                const uint32_t* m=(const uint32_t*)staged[g].contents;
                std::vector<uint32_t> ref;
                for(uint32_t e:inputs[g]) if(m[e>>6]!=1u)ref.push_back(e);
                const uint32_t* args=(const uint32_t*)filter.groupArgs(g).contents;
                uint32_t expected[5]={(uint32_t)ref.size()*6u,1,0,0,0};
                expect(memcmp(args,expected,20)==0,"exact five args fields");
                expect(!ref.size() || memcmp(filter.groupQuads(g).contents,ref.data(),ref.size()*4)==0,"exact stable CPU order");
                const auto it=std::find_if(scene.draws.begin(),scene.draws.end(),[&](const auto& d){return d.groupOrdinal==g;});
                if(it!=scene.draws.end()) expect(!inputs[g].size() || memcmp(it->quadBuffer.contents,inputs[g].data(),inputs[g].size()*4)==0,"input list unchanged");
                keptTotal+=ref.size();inputTotal+=inputs[g].size();
            }
            [runs addObject:@{@"mode":@(mode),@"inputQuads":@(inputTotal),@"outputQuads":@(keptTotal),@"stableExact":@YES}];
        }
        // Same CB reuse: the first args/output copy must survive a second filter.
        auto first=buffer(dev,20);auto second=buffer(dev,20);
        id<MTLCommandBuffer> chain=[queue commandBuffer];
        filter.encode(chain,masks);
        id<MTLBlitCommandEncoder> c=[chain blitCommandEncoder];
        [c copyFromBuffer:filter.groupArgs(14) sourceOffset:0 toBuffer:first destinationOffset:0 size:20];
        for(size_t g=0;g<masks.size();++g)if(masks[g])[c fillBuffer:masks[g] range:NSMakeRange(0,masks[g].length) value:0];
        [c endEncoding]; filter.encode(chain,masks);
        c=[chain blitCommandEncoder];[c copyFromBuffer:filter.groupArgs(14) sourceOffset:0 toBuffer:second destinationOffset:0 size:20];[c endEncoding];finish(chain);
        expect(((uint32_t*)first.contents)[0]==0,"chain all hidden first");
        expect(((uint32_t*)second.contents)[0]==counts[14]*6,"chain no hidden second");
        lodscene::Scene empty;empty.stats.validated=true;lodcompact::Filter emptyFilter(dev,empty);
        id<MTLCommandBuffer> emptyCb=[queue commandBuffer];emptyFilter.encode(emptyCb,{});finish(emptyCb);
        expect(emptyFilter.groupCapacity()==0,"empty scene");
        lodscene::Scene bad;rejects([&]{lodcompact::Filter f(dev,bad);},"validated");
        bad.stats.validated=true;lodscene::Group g;g.ordinal=1;bad.groups.push_back(g);
        rejects([&]{lodcompact::Filter f(dev,bad);},"group ABI");
        const uint64_t legacyChecks=checks;
        expect(legacyChecks==516,"all original 516 checks retained");
        NSDictionary* mapped=mappedTests(dev,queue,scene,inputs);
        NSDictionary* report=@{@"status":@"passed",@"checks":@(checks),@"groups":@(counts.size()),@"cases":runs,
             @"legacyChecks":@(legacyChecks),@"mapped":mapped,
             @"addedMetalBytes":@(filter.allocatedBytes()),@"deviceAllocatedBytes":@(dev.currentAllocatedSize),
             @"device":dev.name,@"MetalAPIValidation":@(std::getenv("MTL_DEBUG_LAYER") && std::strcmp(std::getenv("MTL_DEBUG_LAYER"),"1")==0),
             @"fullSnapshotUsed":@NO,@"gameLaunched":@NO};
        NSData* out=[NSJSONSerialization dataWithJSONObject:report options:NSJSONWritingPrettyPrinted|NSJSONWritingSortedKeys error:nil];
        expect([out writeToFile:[NSString stringWithUTF8String:argv[1]] atomically:YES],"report write");
        fwrite(out.bytes,1,out.length,stdout);fputc('\n',stdout);return 0;
    } catch(const std::exception& e) { fprintf(stderr,"ERROR: %s\n",e.what());return 1; }
} }
