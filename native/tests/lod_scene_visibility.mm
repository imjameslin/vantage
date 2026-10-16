/* Diagnostic: measure the original workload after an ORACLE has identified
 * source quads contributing opaque pixels. Selection needs the full original
 * draw and CPU readback. Its reduced draw cost is NOT a deployable culler/FPS.
 * Water is retained in full, including opaque geometry visible through it.
 */
#pragma push_macro("main")
#undef main
#define main lod_scene_replay_baseline_main
#include "lod_scene_replay.mm"
#pragma pop_macro("main")
#include "lod_scene_occlusion.h"
#include "lod_scene_compact.h"

struct Faces {
    std::vector<std::vector<uint8_t>> bits;
    uint64_t count=0,pixels=0;
    explicit Faces(size_t arenas):bits(arenas){}
    bool has(uint32_t arena,uint32_t quad)const {
        return arena<bits.size() && !bits[arena].empty() && (bits[arena][quad>>3]&(1u<<(quad&7)));
    }
    void add(uint32_t arena,uint32_t quad) {
        check(arena<bits.size() && quad<(1u<<23),@"Face ID outside original arena");
        if(bits[arena].empty())bits[arena].resize(1u<<20);
        uint8_t &b=bits[arena][quad>>3];uint8_t bit=1u<<(quad&7);
        if(!(b&bit)){b|=bit;count++;}pixels++;
    }
};
static Faces finalFaces(Target &target,size_t arenas) {
    check(target.identity!=nil,@"Identity readback required");Faces out(arenas);
    for(NSUInteger y=0;y<target.height;y++) {
        const uint32_t *row=(const uint32_t*)((uint8_t*)target.readback.contents+(2*target.height+y)*target.rowBytes());
        for(NSUInteger x=0;x<target.width;x++)if(row[x]) {
            uint32_t id=row[x]-1;out.add(id>>23,id&((1u<<23)-1));
        }
    }
    return out;
}
static std::vector<ReplayDraw> originalDraws(const lodscene::Scene &scene) {
    std::vector<ReplayDraw> result;
    for(const auto &d:scene.draws) {
        const auto &g=scene.groups.at(d.groupOrdinal);
        result.push_back({(int)d.layerCode,d.quadCount,scene.arenas.at(g.arena).buffer,
                          d.quadBuffer,d.itemBuffer,d.argsBuffer,g.arena<<23});
    }
    return result;
}
static std::vector<ReplayDraw> selectedDraws(id<MTLDevice> dev,const lodscene::Scene &scene,const Faces &faces,
                                            NSMutableArray *rows) {
    std::vector<ReplayDraw> result;uint64_t found=0;
    for(const auto &d:scene.draws) {
        const auto &g=scene.groups.at(d.groupOrdinal);std::vector<uint32_t> selected;
        const uint32_t *quads=(const uint32_t*)d.quadBuffer.contents,*items=(const uint32_t*)d.itemBuffer.contents;
        for(uint32_t i=0;i<d.quadCount;i++) {
            uint32_t e=quads[i],q=items[2*(e>>6)]/4+(e&63);
            bool visible=faces.has(g.arena,q);
            if(visible){check(g.layer!=2,@"Water unexpectedly wrote opaque face ID");found++;}
            if(g.layer==2 || visible)selected.push_back(e);
        }
        [rows addObject:@{@"group":@(g.ordinal),@"layer":@(g.layer),@"level":@(g.level),
                           @"originalQuads":@(d.quadCount),@"selectedQuads":@(selected.size())}];
        if(selected.empty())continue;
        id<MTLBuffer> qb=[dev newBufferWithBytes:selected.data() length:selected.size()*4 options:MTLResourceStorageModeShared];
        uint32_t args[5]={(uint32_t)selected.size()*6,1,0,0,0};
        id<MTLBuffer> ab=[dev newBufferWithBytes:args length:20 options:MTLResourceStorageModeShared];
        check(qb&&ab,@"Selected-list allocation");
        result.push_back({(int)d.layerCode,(uint32_t)selected.size(),scene.arenas.at(g.arena).buffer,
                          qb,d.itemBuffer,ab,g.arena<<23});
    }
    check(found==faces.count,@"Every final opaque ID must identify exactly one input quad");
    return result;
}
static bool exact(NSDictionary *r) {
    return [r[@"colorMismatchPixels"] unsignedLongLongValue]==0 &&
           [r[@"depthBitMismatchPixels"] unsignedLongLongValue]==0 &&
           [r[@"invalidDepthPixels"] unsignedLongLongValue]==0;
}
static NSDictionary *clusterGranularity(const lodscene::Scene &scene,const Faces &faces) {
    uint64_t contributingClusters=0,minRetained=0;std::array<uint64_t,8> visibleByLevel{},quadsByLevel{};
    for(const auto &d:scene.draws) {
        const auto &g=scene.groups.at(d.groupOrdinal);const uint32_t *entries=(const uint32_t*)d.quadBuffer.contents;
        const uint32_t *items=(const uint32_t*)d.itemBuffer.contents;
        std::vector<uint8_t> keep(d.usedClusterCount);
        for(uint32_t i=0;i<d.quadCount;i++) {
            uint32_t e=entries[i],c=e>>6,slot=items[2*c+1],q=items[2*c]/4+(e&63);
            const auto &section=scene.sections.at(scene.sectionIndex.at(slot));
            uint32_t level=std::min(7,section.origin[3]);quadsByLevel[level]++;
            if(faces.has(g.arena,q)){keep[c]=1;visibleByLevel[level]++;}
        }
        for(uint8_t b:keep)contributingClusters+=b!=0;
        for(uint32_t i=0;i<d.quadCount;i++)minRetained+=g.layer==2 || keep[entries[i]>>6];
    }
    NSMutableArray *levels=[NSMutableArray array];for(size_t i=0;i<8;i++)if(quadsByLevel[i])
        [levels addObject:@{@"level":@(i),@"drawnQuads":@(quadsByLevel[i]),@"finalOpaqueQuads":@(visibleByLevel[i])}];
    return @{@"opaqueClustersWithFinalPixels":@(contributingClusters),@"minimumRetainedQuadsAtOriginalClusterGranularity":@(minRetained),
              @"byLevel":levels,@"qualification":@"Oracle grouping limit: retain every input quad in a cluster containing any final opaque face, plus all water. Real conservative bounds may retain substantially more."};
}
static bool diagnosticBound(NSDictionary *r) {
    // Changing MRT layout / removing other primitives produced sparse one-code
    // color residuals, including alpha, but identical depth. Do not describe
    // that as bit exact. Keep the initial failed strict run and check the saved
    // images with PerceptualDiff separately before interpreting the timings.
    return [r[@"depthBitMismatchPixels"] unsignedLongLongValue]==0 &&
           [r[@"invalidDepthPixels"] unsignedLongLongValue]==0 &&
           [r[@"maxColorByteDifference"] unsignedIntValue]<=1 &&
           [r[@"colorMismatchPixels"] unsignedLongLongValue]<=1000;
}
static NSDictionary *timingRow(NSString *name,std::vector<double> samples) {
    NSMutableArray *a=[NSMutableArray array];for(double t:samples)[a addObject:@(t)];
    std::sort(samples.begin(),samples.end());
    return @{@"name":name,@"medianGpuMs":@(samples[samples.size()/2]),@"minGpuMs":@(samples.front()),
              @"maxGpuMs":@(samples.back()),@"gpuMsSamples":a};
}
static NSDictionary *validateCompaction(const lodscene::Scene &scene,lodoccl::Query &query,
                                        lodcompact::Filter &filter,const Faces &faces,
                                        const std::vector<id<MTLBuffer>> *membership=nullptr) {
    uint64_t kept=0,removed=0,removedVisible=0,hiddenClusters=0;
    NSMutableArray *groups=[NSMutableArray array];
    for(const auto &d:scene.draws) {
        const auto &g=scene.groups.at(d.groupOrdinal);
        const uint32_t *hidden=(const uint32_t*)query.groupHidden(g.ordinal).contents;
        const uint32_t *input=(const uint32_t*)d.quadBuffer.contents,*items=(const uint32_t*)d.itemBuffer.contents;
        const uint32_t *output=(const uint32_t*)filter.groupQuads(g.ordinal).contents;
        const uint32_t *args=(const uint32_t*)filter.groupArgs(g.ordinal).contents;
        const uint32_t *map=membership?(const uint32_t*)membership->at(g.ordinal).contents:nullptr;
        uint32_t n=0;
        for(uint32_t c=0;c<query.groupClusterCount(g.ordinal);c++){check(hidden[c]<=1,@"Hidden bit must be boolean");hiddenClusters+=hidden[c];}
        for(uint32_t i=0;i<d.quadCount;i++) {
            uint32_t e=input[i],c=e>>6;
            uint32_t mask=map?map[i]:c;check(mask<query.groupClusterCount(g.ordinal),@"Membership mask range");
            if(hidden[mask]) {
                removed++;removedVisible+=faces.has(g.arena,items[2*c]/4+(e&63));
                check(g.layer!=2,@"Translucent geometry must not be culled");
            } else {
                check(output[n]==e,@"Compaction must match stable CPU-filtered source entries");n++;kept++;
            }
        }
        check(args[0]==n*6 && args[1]==1 && args[2]==0 && args[3]==0 && args[4]==0,@"Compacted indirect args");
        [groups addObject:@{@"group":@(g.ordinal),@"layer":@(g.layer),@"original":@(d.quadCount),@"kept":@(n)}];
    }
    return @{@"keptQuads":@(kept),@"removedQuads":@(removed),@"hiddenClusters":@(hiddenClusters),
              @"removedFinalOpaqueQuads":@(removedVisible),@"stableCompactionMatchesCPU":@YES,@"groups":groups};
}
int main(int argc,char **argv) {
    try { @autoreleasepool {
        check(argc==5,@"Usage: lod_scene_visibility SNAPSHOT SHADERS OUTPUT quality|timing");alarm(175);
        NSString *dir=@(argv[1]),*shaders=@(argv[2]),*out=@(argv[3]),*mode=@(argv[4]);
        check([mode isEqual:@"quality"]||[mode isEqual:@"timing"],@"Mode");bool quality=[mode isEqual:@"quality"];
        check(getenv("MC_GPU_LOCK_HELD")&&strcmp(getenv("MC_GPU_LOCK_HELD"),"timing")==0,@"Timing lock required");
        id<MTLDevice> dev=MTLCreateSystemDefaultDevice();check(dev!=nil,@"Metal device");
        NSString *dump=[out stringByAppendingPathComponent:@"identity-shaders"];
        check([[NSFileManager defaultManager] createDirectoryAtPath:dump withIntermediateDirectories:YES attributes:nil error:nil],@"Shader dump directory");
        auto normal=pipelines(dev,shaders),ids=pipelines(dev,shaders,true,dump);
        lodscene::Scene scene;std::string error;
        fprintf(stdout,"Loading original complete scene for final-face audit...\n");fflush(stdout);
        check(lodscene::load(dev,dir,scene,error),[NSString stringWithUTF8String:error.c_str()]);
        Materials materials(dev,dir,scene.manifest);
        Replay original(dev,scene.sectionInfos,materials,normal,originalDraws(scene));
        Replay identity(dev,scene.sectionInfos,materials,ids,originalDraws(scene));
        Target reference(dev,3024,1964),marked(dev,3024,1964,true),reduced(dev,3024,1964);
        original.render(reference,7,true,3,false);original.render(reference,7,true,1,true);
        identity.render(marked,7,true,3,false);identity.render(marked,7,true,1,true);
        NSDictionary *instrumentation=compare(reference,marked);
        Faces faces=finalFaces(marked,scene.arenas.size());
        check(faces.count>0 && faces.pixels>10000,@"Identity coverage sanity");
        NSMutableArray *rows=[NSMutableArray array];
        Replay oracle(dev,scene.sectionInfos,materials,normal,selectedDraws(dev,scene,faces,rows));
        oracle.render(reduced,7,true,3,false);oracle.render(reduced,7,true,1,true);
        NSDictionary *parity=compare(reference,reduced);
        NSMutableDictionary *result=[@{@"schema":@"farsight-scene-visibility-1",@"mode":mode,@"device":dev.name,
            @"inputVisibleQuads":@(scene.stats.visibleQuads),@"finalOpaqueQuads":@(faces.count),@"finalOpaquePixels":@(faces.pixels),
            @"waterQuadsRetained":@(scene.stats.visibleByLayer[2]),@"groups":rows,
            @"identityInstrumentationParity":instrumentation,@"oracleParity":parity,
            @"scope":@"Frozen full-scene, original source quads and production shaders. Final opaque face IDs measured via auxiliary target; all translucent quads retained. Same captured camera only.",
            @"qualification":@"ORACLE diagnostic: reduced draw excludes full reference render, identity pass, CPU readback and selection. Not a deployable optimization, game FPS result, flying result or complete renderer budget."} mutableCopy];
        fprintf(stdout,"%llu of %llu submitted opaque quads appear in %llu final pixels; %llu water quads kept\n",
            (unsigned long long)faces.count,(unsigned long long)(scene.stats.visibleQuads-scene.stats.visibleByLayer[2]),
            (unsigned long long)faces.pixels,(unsigned long long)scene.stats.visibleByLayer[2]);
        fprintf(stdout,"Instrumentation mismatch: color %llu / depth %llu; oracle: color %llu / depth %llu\n",
            [instrumentation[@"colorMismatchPixels"] unsignedLongLongValue],[instrumentation[@"depthBitMismatchPixels"] unsignedLongLongValue],
            [parity[@"colorMismatchPixels"] unsignedLongLongValue],[parity[@"depthBitMismatchPixels"] unsignedLongLongValue]);fflush(stdout);
        check(dev.currentAllocatedSize<6ull<<30,@"Total Metal allocation cap (6 GiB)");
        result[@"MetalAllocatedBytes"]=@(dev.currentAllocatedSize);
        result[@"bitExact"]=@(exact(instrumentation)&&exact(parity));
        result[@"diagnosticColorBound"]=@"Exact depth; at most 1000 pixels differ by at most one byte code per channel. This is not bit-exact color. Saved full images require the separate perceptual check.";
        bool ok=diagnosticBound(instrumentation)&&diagnosticBound(parity);
        result[@"clusterGranularity"]=clusterGranularity(scene,faces);
        if(quality) {
            png(reference,[out stringByAppendingPathComponent:@"reference.png"]);
            png(reduced,[out stringByAppendingPathComponent:@"oracle.png"]);
            png(marked,[out stringByAppendingPathComponent:@"identity.png"]);
            rawOutput(reference,[out stringByAppendingPathComponent:@"reference.bin.gz"]);
            rawOutput(reduced,[out stringByAppendingPathComponent:@"oracle.bin.gz"]);
        }
        // An optimistic scheduling test: the oracle-selected source faces form
        // the CURRENT-CAMERA occluder prepass. Real operation must obtain useful
        // source IDs from prior visibility and survive motion/disocclusion.
        // This test includes the actual GPU pyramid, queries and stable list
        // compaction, but it does not pretend obtaining that oracle seed is free.
        lodoccl::Query query(dev,scene,3024,1964,true);
        lodcompact::Filter filter(dev,scene);
        const std::vector<id<MTLBuffer>> *membership=nullptr;
        result[@"queriedBoundsGroups"]=@(query.totalQueried());
        result[@"queriedOriginalClusters"]=@(query.totalQueried()); // compatibility with earlier audit results
        Target occluders(dev,3024,1964);
        id<MTLBuffer> cull=smallBuffer(dev,smallGzip([dir stringByAppendingPathComponent:@"cull-view.bin.gz"],128),@"Cull view");
        std::vector<id<MTLBuffer>> masks;for(const auto &g:scene.groups)masks.push_back(query.groupHidden(g.ordinal));
        auto compactDraws=originalDraws(scene);
        check(compactDraws.size()==scene.draws.size(),@"Original draw alignment");
        for(size_t i=0;i<compactDraws.size();i++) {
            uint32_t g=scene.draws[i].groupOrdinal;
            compactDraws[i].quads=filter.groupQuads(g);compactDraws[i].args=filter.groupArgs(g);
        }
        Replay recovery(dev,scene.sectionInfos,materials,normal,std::move(compactDraws));
        auto discover=[&](id<MTLCommandBuffer> cb) {
            query.encode(cb,occluders.depth,materials.projection,materials.terrain,materials.globals,cull);
            filter.encode(cb,masks);
        };
        auto candidate=[&](int repeats,bool readback,bool emptySeed) {
            id<MTLCommandBuffer> cb=[original.queue commandBuffer];
            for(int i=0;i<repeats;i++) {
                oracle.encode(cb,occluders,emptySeed?0:3,true);
                discover(cb);
                recovery.encode(cb,reduced,7,true);
            }
            if(readback)reduced.copy(cb);
            return finish(cb)/repeats;
        };
        candidate(3,false,false);candidate(1,true,false);
        NSDictionary *occlusionParity=compare(reference,reduced);
        NSDictionary *selection=validateCompaction(scene,query,filter,faces,membership);
        result[@"occlusionParity"]=occlusionParity;result[@"occlusionSelection"]=selection;
        result[@"occlusionQualification"]=@"Optimistic seeded current-view source-geometry prepass + padded minimum depth pyramid + conservative cluster tests + GPU stable compaction + original shader recovery. Discovery/compaction are included, but obtaining the oracle seed is not. Fixed captured camera only; no temporal/motion or game FPS claim.";
        ok=ok && diagnosticBound(occlusionParity) && [selection[@"removedFinalOpaqueQuads"] unsignedLongLongValue]==0;
        printf("Occlusion kept %llu / %llu quads; culled visible IDs %llu; color/depth errors %llu/%llu\n",
            [selection[@"keptQuads"] unsignedLongLongValue],(unsigned long long)scene.stats.visibleQuads,
            [selection[@"removedFinalOpaqueQuads"] unsignedLongLongValue],
            [occlusionParity[@"colorMismatchPixels"] unsignedLongLongValue],[occlusionParity[@"depthBitMismatchPixels"] unsignedLongLongValue]);fflush(stdout);
        check(dev.currentAllocatedSize<6ull<<30,@"Total candidate Metal allocation cap (6 GiB)");
        result[@"MetalAllocatedBytes"]=@(dev.currentAllocatedSize);
        if(quality) {
            // Reduced now contains the real cull/compact/recovery result.
            png(reduced,[out stringByAppendingPathComponent:@"occlusion.png"]);
            rawOutput(reduced,[out stringByAppendingPathComponent:@"occlusion.bin.gz"]);
            candidate(1,true,true);
            NSDictionary *emptySelection=validateCompaction(scene,query,filter,faces,membership);
            result[@"emptySeedSelection"]=emptySelection;result[@"emptySeedParity"]=compare(reference,reduced);
            ok=ok && [emptySelection[@"removedQuads"] unsignedLongLongValue]==0 && diagnosticBound(result[@"emptySeedParity"]);
            // Closed-fixture disocclusion test: change the camera but keep the
            // old oracle source IDs as the seed. Re-rasterize them at the NEW
            // camera. Compare against all the ORIGINAL captured input entries,
            // not a newly captured/frustum-complete game view. This checks safe
            // recovery from stale source selection, not full flying quality.
            uint8_t savedGlobals[48];memcpy(savedGlobals,materials.globals.contents,48);
            const int32_t offsets[][3]={{16,0,0},{0,16,0},{0,0,-32}};
            NSMutableArray *motion=[NSMutableArray array];
            for(int p=0;p<3;p++) {
                memcpy(materials.globals.contents,savedGlobals,48);
                int32_t *position=(int32_t*)materials.globals.contents;
                for(int k=0;k<3;k++)position[k]+=offsets[p][k];
                original.render(reference,7,true,1,true);identity.render(marked,7,true,1,true);
                Faces current=finalFaces(marked,scene.arenas.size());
                uint64_t newlyVisible=0;
                for(const auto &d:scene.draws) {
                    const auto &g=scene.groups.at(d.groupOrdinal);
                    const uint32_t *q=(const uint32_t*)d.quadBuffer.contents,*it=(const uint32_t*)d.itemBuffer.contents;
                    for(uint32_t i=0;i<d.quadCount;i++) {
                        uint32_t face=it[2*(q[i]>>6)]/4+(q[i]&63);
                        newlyVisible+=current.has(g.arena,face)&&!faces.has(g.arena,face);
                    }
                }
                candidate(1,true,false);
                NSDictionary *stats=validateCompaction(scene,query,filter,current,membership),*image=compare(reference,reduced);
                [motion addObject:@{@"cameraBlockOffset":@[@(offsets[p][0]),@(offsets[p][1]),@(offsets[p][2])],
                    @"newlyVisibleSourceQuads":@(newlyVisible),@"selection":stats,@"parity":image}];
                ok=ok && newlyVisible>0 && [stats[@"removedFinalOpaqueQuads"] unsignedLongLongValue]==0 && diagnosticBound(image);
                printf("Camera offset %d,%d,%d: new opaque faces %llu, culled final IDs %llu, color/depth errors %llu/%llu\n",
                    offsets[p][0],offsets[p][1],offsets[p][2],(unsigned long long)newlyVisible,
                    [stats[@"removedFinalOpaqueQuads"] unsignedLongLongValue],[image[@"colorMismatchPixels"] unsignedLongLongValue],
                    [image[@"depthBitMismatchPixels"] unsignedLongLongValue]);fflush(stdout);
                png(reference,[out stringByAppendingPathComponent:[NSString stringWithFormat:@"motion-%d-reference.png",p]]);
                png(reduced,[out stringByAppendingPathComponent:[NSString stringWithFormat:@"motion-%d-occlusion.png",p]]);
            }
            memcpy(materials.globals.contents,savedGlobals,48);
            result[@"closedFixtureDisocclusion"]=motion;
            result[@"motionQualification"]=@"Only camera translation over the frozen captured candidate set, which is NOT frustum-complete at new views. Old source face IDs are re-rasterized at each new camera; actual depth queries/filtering recover newly visible faces within that fixed set. This does not qualify live flying or LOD streaming.";
        } else if(ok) {
            std::array<std::vector<double>,3> samples;
            for(int i=0;i<9;i++)for(int j=0;j<3;j++) {
                int k=(i+j)%3;
                if(k==2)samples[k].push_back(candidate(4,false,false));
                else {Replay &r=k?oracle:original;samples[k].push_back(r.render(reduced,7,true,4,false));}
            }
            result[@"timings"]=@[timingRow(@"original",samples[0]),timingRow(@"oracle-selected-original-quads",samples[1]),timingRow(@"oracle-seeded-gpu-occlusion",samples[2])];
            result[@"timingNotes"]=@"Nine rotating three-case blocks, four passes per GPU timestamp. Oracle-only omits selection. Seeded GPU occlusion includes source prepass, pyramid, cluster queries, stable compaction and original recovery, but omits initial oracle-seed discovery. No readback in timed command buffers.";
            for(NSDictionary *r in result[@"timings"])printf("%s: median %.6f ms\n",[r[@"name"] UTF8String],[r[@"medianGpuMs"] doubleValue]);
        }
        result[@"status"]=ok?@"passed":@"failed";
        writeJson(result,[out stringByAppendingPathComponent:@"result.json"]);
        check(ok,@"Identity instrumentation or oracle exceeded diagnostic color/depth bound");
    }} catch(const std::exception &e) {fprintf(stderr,"FAIL: %s\n",e.what());return 1;}
    return 0;
}
