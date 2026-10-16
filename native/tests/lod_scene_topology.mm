// Offline draw-topology experiment. Original scene/shaders are immutable inputs.
#define main lod_scene_replay_original_main
#include "lod_scene_replay.mm"
#undef main

struct Topology {
    Replay &original;
    std::map<int,Pipeline> &instanced,&procedural;
    id<MTLBuffer> quadIndices;
    std::vector<id<MTLBuffer>> instanceArgs,proceduralArgs;
    uint64_t argumentChecks=0;
    Topology(id<MTLDevice> dev,Replay &r,std::map<int,Pipeline> &b,std::map<int,Pipeline> &c)
      :original(r),instanced(b),procedural(c) {
        const uint32_t corners[]={0,1,2,2,3,0};
        quadIndices=smallBuffer(dev,[NSData dataWithBytes:corners length:24],@"One exact quad index buffer");
        for(const auto &d:r.draws) {
            const uint32_t *a=(const uint32_t*)d.args.contents;
            check(d.args.length==20 && a[0]==6*d.count && a[1]==1 && a[2]==0 && a[3]==0 && a[4]==0,@"Original arguments");
            argumentChecks++;
            const uint32_t bi[]={6,d.count,0,0,0},ci[]={6*d.count,1,0,0};
            instanceArgs.push_back(smallBuffer(dev,[NSData dataWithBytes:bi length:20],@"Immutable six-index instance arguments"));
            proceduralArgs.push_back(smallBuffer(dev,[NSData dataWithBytes:ci length:16],@"Immutable procedural draw arguments"));
        }
    }
    void encode(id<MTLCommandBuffer> cb,Target &t,int mode,bool indirect=true) {
        if(mode==0) {original.encode(cb,t,7,indirect);return;}
        // mode 3 is a quality-only control: this binding path, original PSO and
        // original huge index buffer. It proves that topology is the only change.
        auto &psos=mode==1?instanced:(mode==2?procedural:original.psos);
        MTLRenderPassDescriptor *pass=[MTLRenderPassDescriptor renderPassDescriptor];
        auto color=pass.colorAttachments[0];color.texture=t.color;
        color.loadAction=MTLLoadActionClear;color.storeAction=MTLStoreActionStore;
        const float *fog=(const float*)original.materials.fog.contents;
        color.clearColor=MTLClearColorMake(fog[0],fog[1],fog[2],1);
        pass.depthAttachment.texture=t.depth;pass.depthAttachment.clearDepth=0;
        pass.depthAttachment.loadAction=MTLLoadActionClear;pass.depthAttachment.storeAction=MTLStoreActionStore;
        id<MTLRenderCommandEncoder> re=[cb renderCommandEncoderWithDescriptor:pass];check(re!=nil,@"Topology render encoder");
        [re setViewport:(MTLViewport){0,0,(double)t.width,(double)t.height,0,1}];
        [re setScissorRect:(MTLScissorRect){0,0,t.width,t.height}];
        [re setDepthClipMode:MTLDepthClipModeClip];[re setDepthBias:0 slopeScale:0 clamp:0];
        original.materials.bind(re,false);[re setVertexBuffer:original.sections offset:0 atIndex:29];
        for(int layer=0;layer<3;layer++) {
            int bound=-1;
            for(size_t i=0;i<original.draws.size();i++) {
                const auto &d=original.draws[i];if((d.code&7)!=layer||!d.count)continue;
                const Pipeline &p=psos.at(d.code);
                if(bound!=d.code) {
                    [re setRenderPipelineState:p.state];[re setDepthStencilState:p.depth];
                    [re setCullMode:p.cull];[re setFrontFacingWinding:p.front];bound=d.code;
                }
                [re setVertexBuffer:d.arena offset:0 atIndex:30];
                [re setVertexBuffer:d.quads offset:0 atIndex:28];[re setVertexBuffer:d.items offset:0 atIndex:27];
                if(mode==2) {
                    if(indirect)[re drawPrimitives:MTLPrimitiveTypeTriangle indirectBuffer:proceduralArgs[i] indirectBufferOffset:0];
                    else [re drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:(NSUInteger)d.count*6 instanceCount:1 baseInstance:0];
                } else {
                    id<MTLBuffer> indices=mode==1?quadIndices:original.indices;
                    id<MTLBuffer> args=mode==1?instanceArgs[i]:d.args;
                    if(indirect)[re drawIndexedPrimitives:MTLPrimitiveTypeTriangle indexType:MTLIndexTypeUInt32 indexBuffer:indices indexBufferOffset:0 indirectBuffer:args indirectBufferOffset:0];
                    else [re drawIndexedPrimitives:MTLPrimitiveTypeTriangle indexCount:mode==1?6:(NSUInteger)d.count*6 indexType:MTLIndexTypeUInt32 indexBuffer:indices indexBufferOffset:0 instanceCount:mode==1?d.count:1 baseVertex:0 baseInstance:0];
                }
            }
        }
        [re endEncoding];
    }
    double run(Target &t,int mode,int repeats,bool readback,bool indirect=true) {
        id<MTLCommandBuffer> cb=[original.queue commandBuffer];check(cb!=nil,@"Topology command buffer");
        for(int n=0;n<repeats;n++)encode(cb,t,mode,indirect);
        if(readback)t.copy(cb);
        return finish(cb)/repeats;
    }
};
static uint64_t mappingTests() {
    uint64_t checks=0;
    const uint32_t order[]={0,1,2,2,3,0};std::mt19937 rng(700512);
    const uint32_t edges[]={0,1,2,63,64,65,1023,65535,4610539,5999999};
    for(unsigned n=0;n<4106;n++) {
        uint32_t q=n<10?edges[n]:rng()%6000000u;
        for(unsigned k=0;k<6;k++) {
            uint32_t indexed=q*4+order[k],physical=q*6+k;
            uint32_t logical=(physical/6)*4+order[physical%6];
            check(indexed==logical,@"Procedural logical vertex differs");checks++;
            check(q==(indexed>>2) && order[k]==(indexed&3),@"Instance fetch/corner differs");checks++;
        }
    }
    return checks;
}
static bool parityAllowed(NSDictionary *d) {
    return [d[@"depthBitMismatchPixels"] unsignedLongLongValue]==0 &&
           [d[@"invalidDepthPixels"] unsignedLongLongValue]==0 &&
           [d[@"coveredDepthPixels"] unsignedLongLongValue]>10000 &&
           [d[@"colorMismatchPixels"] unsignedLongLongValue]<=1000 &&
           [d[@"maxColorByteDifference"] unsignedIntValue]<=1;
}
static bool exact(NSDictionary *d) {
    return [d[@"colorMismatchPixels"] unsignedLongLongValue]==0 && [d[@"depthBitMismatchPixels"] unsignedLongLongValue]==0;
}
int main(int argc,char **argv) {
    @autoreleasepool {
        check(argc==5,@"Usage: topology SNAPSHOT SHADER_ROOT OUTPUT quality|timing");alarm(175);
        NSString *dir=@(argv[1]),*shaderRoot=@(argv[2]),*out=@(argv[3]),*mode=@(argv[4]);
        bool quality=[mode isEqual:@"quality"];
        check(quality||[mode isEqual:@"timing"],@"Mode");
        check(getenv("MC_GPU_LOCK_HELD")&&strcmp(getenv("MC_GPU_LOCK_HELD"),"timing")==0,@"Timing lane required");
        uint64_t mapping=mappingTests();
        id<MTLDevice> dev=MTLCreateSystemDefaultDevice();check(dev!=nil,@"Metal device");
        auto a=pipelines(dev,[shaderRoot stringByAppendingPathComponent:@"original"]);
        auto b=pipelines(dev,[shaderRoot stringByAppendingPathComponent:@"instanced"]);
        auto c=pipelines(dev,[shaderRoot stringByAppendingPathComponent:@"procedural"]);
        lodscene::Scene scene;std::string error;
        check(lodscene::load(dev,dir,scene,error),[NSString stringWithUTF8String:error.c_str()]);
        check(scene.stats.validated,@"Scene validation");Materials materials(dev,dir,scene.manifest);
        std::vector<ReplayDraw> draws;
        for(const auto &d:scene.draws) {
            const auto &g=scene.groups.at(d.groupOrdinal);
            draws.push_back({(int)d.layerCode,d.quadCount,scene.arenas.at(g.arena).buffer,d.quadBuffer,d.itemBuffer,d.argsBuffer});
        }
        Replay baseline(dev,scene.sectionInfos,materials,a,std::move(draws));Topology probe(dev,baseline,b,c);
        Target target(dev,3024,1964),reference(dev,3024,1964);
        check(dev.currentAllocatedSize<6ull<<30,@"6 GiB total Metal memory cap");
        NSMutableDictionary *result=[@{@"schema":@"farsight-scene-topology-1",@"status":@"running",@"mode":mode,
            @"device":dev.name,@"width":@3024,@"height":@1964,@"sourceQuads":@(scene.stats.payloadQuads),
            @"visibleQuads":@(scene.stats.visibleQuads),@"groups":@(scene.draws.size()),
            @"visibleByLayer":@[@(scene.stats.visibleByLayer[0]),@(scene.stats.visibleByLayer[1]),@(scene.stats.visibleByLayer[2])],
            @"mappingChecks":@(mapping),@"argumentChecks":@(probe.argumentChecks),
            @"MetalAllocatedBytes":@(dev.currentAllocatedSize),@"originalIndexBufferBytes":@(baseline.indices.length),
            @"instancedIndexBufferBytes":@24,@"proceduralIndexBufferBytes":@0,
            @"qualification":@"Frozen LOD-only raster diagnostic. Not game FPS, temporal/zoom qualification, or a production default.",
            @"preservation":@"Original source arenas/addresses/visible entries/items, SOLID then CUTOUT then water and original group order; original paired shading, uniforms, atlas mips/samplers, PSO numeric state and triangle 012/230 order. Only vertex fetch addressing and corresponding draw arguments differ."} mutableCopy];
        const char *names[]={"indexed-original","instanced-quad","procedural-six"};
        if(quality) {
            probe.run(target,0,1,true);memcpy(reference.readback.contents,target.readback.contents,target.readback.length);
            probe.run(target,0,3,false);probe.run(target,0,1,true);
            result[@"coldWarmOriginalControl"]=compare(reference,target);
            memcpy(reference.readback.contents,target.readback.contents,target.readback.length);
            NSFileManager *fm=[NSFileManager defaultManager];
            auto save=[&](NSString *name) {
                NSString *p=[out stringByAppendingPathComponent:name];NSError *err=nil;
                check([fm createDirectoryAtPath:p withIntermediateDirectories:YES attributes:nil error:&err],err.description);
                png(target,[p stringByAppendingPathComponent:@"scene.png"]);
                rawOutput(target,[p stringByAppendingPathComponent:@"native-rows.bin.gz"]);
            };
            save(@"indexed-original");NSMutableDictionary *controls=[NSMutableDictionary dictionary];
            probe.run(target,0,1,true);controls[@"originalWarmRepeat"]=compare(reference,target);
            probe.run(target,0,1,true,false);controls[@"originalDirectIndirect"]=compare(reference,target);
            probe.run(target,3,3,false);probe.run(target,3,1,true);controls[@"originalBindingPathMirror"]=compare(reference,target);
            bool ok=true;for(NSString *key in controls)ok=ok&&exact(controls[key])&&parityAllowed(controls[key]);
            NSMutableArray *parities=[NSMutableArray array];
            for(int k=1;k<=2;k++) {
                probe.run(target,k,3,false);probe.run(target,k,1,true);
                NSDictionary *parity=compare(reference,target);save(@(names[k]));
                NSMutableDictionary *row=[@{@"name":@(names[k]),@"againstOriginal":parity,
                    @"bitExact":@(exact(parity)),@"withinExplicitSparseTolerance":@(parityAllowed(parity))} mutableCopy];
                // Preserve this candidate's image, compare direct/indirect, then
                // restore original reference bytes from a fresh original draw.
                std::vector<uint8_t> originalCopy(reference.readback.length);
                memcpy(originalCopy.data(),reference.readback.contents,originalCopy.size());
                memcpy(reference.readback.contents,target.readback.contents,target.readback.length);
                probe.run(target,k,1,true,false);row[@"candidateDirectIndirect"]=compare(reference,target);
                probe.run(target,k,1,true);row[@"candidateWarmRepeat"]=compare(reference,target);
                memcpy(reference.readback.contents,originalCopy.data(),originalCopy.size());
                ok=ok&&parityAllowed(parity)&&exact(row[@"candidateDirectIndirect"])&&exact(row[@"candidateWarmRepeat"]);
                [parities addObject:row];
                printf("%s: color %llu pixels (max byte %u), depth %llu bits/pixels; exact=%s\n",names[k],
                    [parity[@"colorMismatchPixels"] unsignedLongLongValue],[parity[@"maxColorByteDifference"] unsignedIntValue],
                    [parity[@"depthBitMismatchPixels"] unsignedLongLongValue],exact(parity)?"yes":"no");
            }
            probe.run(target,0,1,true);controls[@"originalAfterVariants"]=compare(reference,target);
            ok=ok&&exact(controls[@"originalAfterVariants"]);
            baseline.render(target,0,true,1,true);auto empty=compare(target,target);
            result[@"emptyControlDepthPixels"]=empty[@"coveredDepthPixels"];
            ok=ok&&[empty[@"coveredDepthPixels"] unsignedLongLongValue]==0;
            result[@"controls"]=controls;result[@"parities"]=parities;
            result[@"warmupPassesPerCase"]=@3;
            result[@"tolerance"]=@"Depth bit-exact; color at most 1000 mismatched pixels with each changed channel <=1/255. Any sparse nonzero result is explicitly NOT bit-exact. Separate PerceptualDiff required before timing.";
            result[@"status"]=ok?@"passed":@"failed";writeJson(result,[out stringByAppendingPathComponent:@"result.json"]);
            check(ok,@"Topology color/depth/controls");printf("QUALITY PASSED\n");
        } else {
            for(int warm=0;warm<3;warm++)for(int k=0;k<3;k++)probe.run(target,k,1,false);
            std::array<std::vector<double>,3> samples;std::mt19937 rng(20261008);
            NSMutableArray *orders=[NSMutableArray array];
            for(int block=0;block<9;block++) {
                std::array<int,3> order={0,1,2};std::shuffle(order.begin(),order.end(),rng);
                NSMutableArray *row=[NSMutableArray array];
                for(int k:order) {samples[k].push_back(probe.run(target,k,4,false));[row addObject:@(names[k])];}
                [orders addObject:row];
            }
            NSMutableArray *rows=[NSMutableArray array];
            for(int k=0;k<3;k++) {
                auto sorted=samples[k];std::sort(sorted.begin(),sorted.end());NSMutableArray *values=[NSMutableArray array];double sum=0;
                for(double v:samples[k]){[values addObject:@(v)];sum+=v;}
                [rows addObject:@{@"name":@(names[k]),@"medianGpuMs":@(sorted[4]),@"meanGpuMs":@(sum/9),
                    @"minGpuMs":@(sorted.front()),@"maxGpuMs":@(sorted.back()),@"gpuMsSamples":values}];
                printf("%s: median %.6f ms, range %.6f..%.6f ms\n",names[k],sorted[4],sorted.front(),sorted.back());
            }
            result[@"timings"]=rows;result[@"interleavedOrders"]=orders;result[@"warmupPassesPerCase"]=@3;
            result[@"measuredBlocksPerCase"]=@9;result[@"passesPerBlock"]=@4;
            result[@"timingNotes"]=@"GPUStartTime to GPUEndTime /4, four independent clear/store passes; no uploads, argument edits or readbacks in timed command buffers; original frozen buffers shared by every case. All variants use indirect drawing. Includes full retained LOD geometry, cutout and water; excludes near world/culling/streaming/present. No FPS inference.";
            result[@"status"]=@"passed";writeJson(result,[out stringByAppendingPathComponent:@"result.json"]);
        }
    }
    return 0;
}
