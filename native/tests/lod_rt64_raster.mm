// Prompt6 Step1 sidecar: original shaded far raster, frozen captured view only.
// Build/run exclusively through tools/lod/rt64-raster.py after other work is idle.
#define main lod_scene_replay_original_main
#include "lod_scene_replay.mm"
#undef main

static constexpr unsigned RasterWarmups = 5, RasterSamples = 120;

static NSDictionary *rasterSummary(const std::vector<double> &samples) {
    check(samples.size()==RasterSamples,@"Expected exactly 120 single-pass samples");
    auto sorted=samples;std::sort(sorted.begin(),sorted.end());
    NSMutableArray *values=[NSMutableArray array];
    for(double ms:samples)[values addObject:@(ms)];
    return @{@"p99Ms":@(sorted[size_t(std::ceil(.99*sorted.size()))-1]),
             @"maxMs":@(sorted.back()),
             @"medianMs":@((sorted[sorted.size()/2-1]+sorted[sorted.size()/2])*.5),
             @"samples":values,@"sampleCount":@(samples.size()),
             @"percentileMethod":@"p99 nearest rank (119th of 120); median mean of middle two"};
}

static double rasterPass(Replay &replay,Target &target,NSMutableArray *spans) {
    id<MTLCommandBuffer> cb=[replay.queue commandBuffer];check(cb!=nil,@"Raster command buffer");
    cb.label=@"RT64 baseline: ONE original all-layer far raster pass";
    replay.encode(cb,target,7,true); // Exactly one pass, exact captured indirect arguments.
    double ms=finish(cb);           // GPUStartTime..GPUEndTime, no averaging/divisor.
    check(cb.GPUStartTime>0 && cb.GPUEndTime>cb.GPUStartTime,@"Invalid GPU timestamp span");
    if(spans)[spans addObject:@{@"GPUStartTime":@(cb.GPUStartTime),@"GPUEndTime":@(cb.GPUEndTime)}];
    return ms;
}

int main(int argc,char **argv) {
    @autoreleasepool {
        check(argc==4,@"Usage: lod_rt64_raster SNAPSHOT SHADERS OUTPUT");
        check(getenv("MC_GPU_LOCK_HELD") && !strcmp(getenv("MC_GPU_LOCK_HELD"),"timing"),@"Exclusive timing lane required");
        check(getenv("MTL_DEBUG_LAYER") && !strcmp(getenv("MTL_DEBUG_LAYER"),"0") &&
              getenv("MTL_SHADER_VALIDATION") && !strcmp(getenv("MTL_SHADER_VALIDATION"),"0"),@"Validation must be 0 for timing");
        alarm(900);
        NSString *snapshot=@(argv[1]),*shaders=@(argv[2]),*out=@(argv[3]);
        id<MTLDevice> dev=MTLCreateSystemDefaultDevice();check(dev!=nil,@"Metal device");
        auto psos=pipelines(dev,shaders,false); // Original sources: no identity instrumentation.
        lodscene::Scene scene;std::string error;
        puts("Loading and validating original LDS/LDG/LDV buffers...");fflush(stdout);
        bool loaded=lodscene::load(dev,snapshot,scene,error);
        check(loaded,[NSString stringWithUTF8String:error.c_str()]);
        check(scene.stats.validated,@"All captured geometry references must validate");
        const auto &s=scene.stats;
        check(scene.draws.size()==[scene.manifest[@"visible"][@"draws"] unsignedIntegerValue],@"All LDV records required");
        Materials materials(dev,snapshot,scene.manifest);
        std::vector<ReplayDraw> draws;
        std::array<uint64_t,3> quads{},nonempty{};
        NSMutableArray *records=[NSMutableArray array];
        for(const auto &d:scene.draws) {
            const auto &g=scene.groups.at(d.groupOrdinal);
            unsigned layer=d.layerCode&7;check(layer<3,@"Unsupported LDV layer cannot be omitted");
            quads[layer]+=d.quadCount;nonempty[layer]+=d.quadCount!=0;
            draws.push_back({(int)d.layerCode,d.quadCount,scene.arenas.at(g.arena).buffer,
                             d.quadBuffer,d.itemBuffer,d.argsBuffer});
            [records addObject:@{@"groupOrdinal":@(d.groupOrdinal),@"layerCode":@(d.layerCode),
                                 @"retainedQuads":@(d.quadCount),@"arena":@(g.arena)}];
        }
        check(quads==s.visibleByLayer && quads[0]+quads[1]+quads[2]==s.visibleQuads && s.visibleQuads>0,
              @"All retained captured quads must be included");
        Replay replay(dev,scene.sectionInfos,materials,psos,std::move(draws));
        Target target(dev,3024,1964,false);
        check(dev.currentAllocatedSize<6ull<<30,@"Total raster Metal memory cap (6 GiB)");
        // No motion variant: LDV is already frustum/visibility filtered. Neither
        // turning this list nor guessing a translation proves candidate completeness.
        for(unsigned w=0;w<RasterWarmups;w++) {
            @autoreleasepool { rasterPass(replay,target,nil); }
        }
        std::vector<double> samples;samples.reserve(RasterSamples);
        NSMutableArray *spans=[NSMutableArray array];
        for(unsigned i=0;i<RasterSamples;i++) {
            @autoreleasepool { samples.push_back(rasterPass(replay,target,spans)); }
        }
        // Separate, untimed blit of the final sample. Never load the GUI-cleared
        // captured frame depth, and never append readback to a measured command.
        id<MTLCommandBuffer> copy=[replay.queue commandBuffer];check(copy!=nil,@"Readback command");
        target.copy(copy);finish(copy);
        NSDictionary *coverage=compare(target,target);
        check([coverage[@"coveredDepthPixels"] unsignedLongLongValue]>1000 &&
              [coverage[@"invalidDepthPixels"] unsignedLongLongValue]==0,@"Far raster must produce real valid depth");
        png(target,[out stringByAppendingPathComponent:@"standing.png"]);
        NSDictionary *gpu=rasterSummary(samples);
        NSDictionary *result=@{
            @"schema":@"farsight-rt64-raster-1",@"status":@"passed",@"device":dev.name,
            @"path":@"standing",@"variant":@"original-shaded-far-raster",@"gpu":gpu,@"gpuSpansSeconds":spans,
            @"parameters":@{@"width":@3024,@"height":@1964,@"warmupPasses":@(RasterWarmups),
                @"measuredSamples":@(RasterSamples),@"passesPerSample":@1,@"layerMask":@7,
                @"indirect":@YES,@"nearDepth":@NO,@"identityInstrumentation":@NO,
                @"MetalAPIValidation":@0,@"MetalShaderValidation":@0,@"camera":@"frozen captured uniforms"},
            @"timingScope":@"Serialized GPUStartTime..GPUEndTime for one clear, original shaded far raster pass and color/depth stores. No sample division. Loading, compilation, warmup, readback, culling, near terrain, entities, streaming, presentation and CPU frame work excluded.",
            @"motion":@{@"status":@"standing-only",@"allCandidateMembershipCompleteForMotion":@NO,
                @"reason":@"LDV is capture-view visibility filtered; complete turn/translation candidate membership is unproven. No moving raster baseline is reported."},
            @"comparison":@{@"peer":@"64-tree raw-hit traversal",@"requiredPath":@"standing",
                @"requiredVariant":@"unmasked",@"requiredSize":@[@3024,@1964],
                @"equivalentShading":@NO,@"equivalentGeometryProven":@NO,
                @"label":@"Original shaded far raster versus 64-tree raw-hit traversal at the same captured view; workload timing only, not equivalent shading, visual parity, or game FPS.",
                @"viewCheck":@"Match capturedView in wrapper provenance before comparing; masked near-proxy and moving rows are separate diagnostics."},
            @"depth":@"Reverse-Z greater-equal, cleared to zero each pass. No near depth or near proxy. Post-GUI captured frame depth is not used.",
            @"geometry":@{@"sourceQuads":@(s.payloadQuads),@"retainedQuads":@(s.visibleQuads),
                @"retainedQuadsByLayer":@[@(quads[0]),@(quads[1]),@(quads[2])],
                @"nonemptyDrawsByLayer":@[@(nonempty[0]),@(nonempty[1]),@(nonempty[2])],
                @"layerOrder":@[@"solid",@"cutout",@"translucent"],@"ldvRecords":records,
                @"allRetainedCapturedDraws":@YES,@"loaderValidated":@YES,
                @"verifiedGeometryFiles":@(s.verifiedFiles),@"verifiedGeometryRawBytes":@(s.verifiedRawBytes)},
            @"readbackControl":@{@"coveredDepthPixels":coverage[@"coveredDepthPixels"],
                @"invalidDepthPixels":coverage[@"invalidDepthPixels"],@"maxReverseDepth":coverage[@"maxReverseDepth"],
                @"scope":@"Final sample has nonempty, finite depth; not a new visual-parity test."},
            @"MetalAllocatedBytes":@(dev.currentAllocatedSize)};
        writeJson(result,[out stringByAppendingPathComponent:@"native-result.json"]);
        printf("standing shaded far raster: p99 %.3f ms, max %.3f ms, median %.3f ms (120 single passes; warmup 5)\n",
               [gpu[@"p99Ms"] doubleValue],[gpu[@"maxMs"] doubleValue],[gpu[@"medianMs"] doubleValue]);
        puts("Comparison peer: standing unmasked 64-tree raw hits; NOT equivalent shading or geometry.");
    }
    return 0;
}
