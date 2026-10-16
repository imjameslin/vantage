// Architecture screen: original surface visibility without opaque RGB shading.
// No color resolve exists here. A fast result alone cannot count as game FPS.
#define main lod_scene_original_main
#include "lod_scene_replay.mm"
#undef main

static NSString *replaceStruct(NSString *s,NSString *name,NSString *body) {
    NSRange a=[s rangeOfString:[@"struct " stringByAppendingString:name]];
    check(a.location!=NSNotFound,@"Shader struct start");
    NSRange z=[s rangeOfString:@"};" options:0 range:NSMakeRange(a.location,s.length-a.location)];
    check(z.location!=NSNotFound,@"Shader struct end");
    return [s stringByReplacingCharactersInRange:NSMakeRange(a.location,z.location+2-a.location) withString:body];
}
static NSString *minimalSource(NSString *s,int code,bool vertex) {
    check((code&7)<2,@"Minimal visibility excludes water");
    bool plainSolid=(code&7)==0 && !(code&16);
    if(!vertex && plainSolid)return @R"MSL(
#include <metal_stdlib>
using namespace metal;
struct main0_in {uint capturedFace [[user(locn6), flat]];};
struct main0_out {uint capturedFace [[color(1)]];};
fragment main0_out main0(main0_in in [[stage_in]]) {return {in.capturedFace};}
)MSL";
    s=identitySource(s,vertex);
    if(vertex) {
        if(plainSolid || !(code&16)) {
            // Preserve every original fetch/position/billboard/matrix operation,
            // including its exact association, before the first fog operation.
            NSRange entry=[s rangeOfString:@"vertex main0_out main0("];
            check(entry.location!=NSNotFound,@"Captured vertex entry");
            NSRange cut=[s rangeOfString:@"    float3 param = pos;" options:0 range:NSMakeRange(entry.location,s.length-entry.location)];
            check(cut.location!=NSNotFound,@"Captured vertex body shape");
            NSString *tail=plainSolid?@"":@R"MSL(
    int2 param_2 = in.UV2 & int2(255);
    out.greedyTile = (in.UV2 >> int2(8)) & int2(255);
    out.vertexColor = half4(0.0h,0.0h,0.0h,half(in.Color.w * sample_lightmap(Sampler2, Sampler2Smplr, param_2).w));
    out.texCoord0 = in.UV0;
    float dist = length(pos);
    float mcmtl_vis = mix(1.0, in.ChunkVisibility, fast::clamp((dist - 16.0) / 16.0, 0.0, 1.0));
    out.mcmtlFogVis = half2(0.0h,half(mix(mcFog.FogColor.w, 1.0, mcmtl_vis)));
)MSL";
            s=[[s substringToIndex:cut.location] stringByAppendingString:[tail stringByAppendingString:
               @"    out.gl_Position.y = -(out.gl_Position.y);\n    return out;\n}\n"]];
            if(plainSolid)s=replaceStruct(s,@"main0_out",@"struct main0_out {uint capturedFace [[user(locn6), flat]]; float4 gl_Position [[position]];};");
        }
        // Fade variants retain the original vertex path and dither inputs.
        // They are uncommon, but omitting them would invalidate this screen.
    } else {
        s=once(s,@"    float4 fragColor [[color(0)]];",@"");
        NSRange a=[s rangeOfString:@"    out.fragColor = " options:NSBackwardsSearch];
        check(a.location!=NSNotFound,@"Original fragment color output");
        NSRange b=[s rangeOfString:@";" options:0 range:NSMakeRange(a.location,s.length-a.location)];
        check(b.location!=NSNotFound,@"Original fragment output statement");
        s=[s stringByReplacingCharactersInRange:NSMakeRange(a.location,b.location+1-a.location) withString:@""];
        // Original sampling, lightmap alpha, fog alpha, cutout threshold and
        // fade discard stay present. The compiler can delete unused RGB work.
        check(![s containsString:@"out.fragColor"],@"RGB output removed");
    }
    return s;
}
static std::map<int,Pipeline> minimalPipelines(id<MTLDevice> dev,NSString *dir,NSString *dump) {
    NSDictionary *manifest=json([dir stringByAppendingPathComponent:@"manifest.json"]);
    std::map<int,Pipeline> out;
    for(NSDictionary *d in manifest[@"pipelines"]) {
        int code=(int)number(d,@"layerCode");if((code&7)==2)continue;
        id<MTLFunction> functions[2];
        for(int k=0;k<2;k++) {
            NSString *key=k?@"fragmentFile":@"vertexFile",*name=d[key];NSError *error=nil;
            NSString *s=[NSString stringWithContentsOfFile:[dir stringByAppendingPathComponent:name] encoding:NSUTF8StringEncoding error:&error];
            check(s!=nil,error.description);s=minimalSource(s,code,k==0);
            check([s writeToFile:[dump stringByAppendingPathComponent:name.lastPathComponent] atomically:YES encoding:NSUTF8StringEncoding error:&error],error.description);
            MTLCompileOptions *opts=[MTLCompileOptions new];opts.languageVersion=MTLLanguageVersion3_2;opts.mathMode=MTLMathModeFast;
            id<MTLLibrary> lib=[dev newLibraryWithSource:s options:opts error:&error];check(lib!=nil,error.description);
            functions[k]=[lib newFunctionWithName:d[k?@"fragmentEntry":@"vertexEntry"]];check(functions[k]!=nil,@"Minimal shader entry");
        }
        MTLRenderPipelineDescriptor *p=[MTLRenderPipelineDescriptor new];p.vertexFunction=functions[0];p.fragmentFunction=functions[1];
        p.rasterSampleCount=1;p.depthAttachmentPixelFormat=MTLPixelFormatDepth32Float;
        p.inputPrimitiveTopology=(MTLPrimitiveTopologyClass)number(d,@"topologyClass");
        p.colorAttachments[1].pixelFormat=MTLPixelFormatR32Uint;
        p.colorAttachments[1].blendingEnabled=NO;p.colorAttachments[1].writeMask=MTLColorWriteMaskAll;
        NSError *error=nil;Pipeline q;q.state=[dev newRenderPipelineStateWithDescriptor:p error:&error];check(q.state!=nil,error.description);
        MTLDepthStencilDescriptor *ds=[MTLDepthStencilDescriptor new];
        ds.depthCompareFunction=(MTLCompareFunction)number(d,@"depthCompare");ds.depthWriteEnabled=number(d,@"depthWriteEnabled");
        check(ds.depthCompareFunction==MTLCompareFunctionGreaterEqual && ds.depthWriteEnabled,@"Original opaque reverse depth");
        q.depth=[dev newDepthStencilStateWithDescriptor:ds];check(q.depth!=nil,@"Depth state");
        q.cull=(MTLCullMode)number(d,@"cullMode");q.front=(MTLWinding)number(d,@"frontFacing");out.emplace(code,q);
    }
    check(out.size()==8,@"All eight opaque format/fade pipeline variants compiled");return out;
}
struct Minimal {
    Replay &original;std::map<int,Pipeline> &psos;
    double run(Target &t,int repeats,bool readback) {
        id<MTLCommandBuffer> cb=[original.queue commandBuffer];check(cb!=nil,@"Minimal command buffer");
        for(int n=0;n<repeats;n++) {
            MTLRenderPassDescriptor *p=[MTLRenderPassDescriptor renderPassDescriptor];
            p.depthAttachment.texture=t.depth;p.depthAttachment.clearDepth=0;
            p.depthAttachment.loadAction=MTLLoadActionClear;p.depthAttachment.storeAction=MTLStoreActionStore;
            auto a=p.colorAttachments[1];a.texture=t.identity;a.clearColor=MTLClearColorMake(0,0,0,0);
            a.loadAction=MTLLoadActionClear;a.storeAction=MTLStoreActionStore;
            id<MTLRenderCommandEncoder> e=[cb renderCommandEncoderWithDescriptor:p];check(e!=nil,@"Minimal render encoder");
            [e setViewport:(MTLViewport){0,0,(double)t.width,(double)t.height,0,1}];
            [e setScissorRect:(MTLScissorRect){0,0,t.width,t.height}];[e setDepthClipMode:MTLDepthClipModeClip];[e setDepthBias:0 slopeScale:0 clamp:0];
            original.materials.bind(e,false);[e setVertexBuffer:original.sections offset:0 atIndex:29];
            for(int layer=0;layer<2;layer++) {
                int bound=-1;
                for(const auto &d:original.draws)if((d.code&7)==layer&&d.count) {
                    const auto &state=psos.at(d.code);
                    if(bound!=d.code){[e setRenderPipelineState:state.state];[e setDepthStencilState:state.depth];[e setCullMode:state.cull];[e setFrontFacingWinding:state.front];bound=d.code;}
                    [e setVertexBuffer:d.arena offset:0 atIndex:30];[e setVertexBuffer:d.quads offset:0 atIndex:28];[e setVertexBuffer:d.items offset:0 atIndex:27];
                    [e setVertexBytes:&d.identityBase length:4 atIndex:6];
                    [e drawIndexedPrimitives:MTLPrimitiveTypeTriangle indexType:MTLIndexTypeUInt32 indexBuffer:original.indices indexBufferOffset:0 indirectBuffer:d.args indirectBufferOffset:0];
                }
            }
            [e endEncoding];
        }
        if(readback) {
            id<MTLBlitCommandEncoder> b=[cb blitCommandEncoder];
            for(int i=1;i<=2;i++)[b copyFromTexture:i==1?t.depth:t.identity sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,0,0)
              sourceSize:MTLSizeMake(t.width,t.height,1) toBuffer:t.readback destinationOffset:i*t.rowBytes()*t.height
              destinationBytesPerRow:t.rowBytes() destinationBytesPerImage:t.rowBytes()*t.height];
            [b endEncoding];
        }
        return finish(cb)/repeats;
    }
};
static NSDictionary *depthIds(Target &a,Target &b) {
    uint64_t depth=0,identity=0,covered=0;
    for(NSUInteger y=0;y<a.height;y++) {
        const uint32_t *ad=(const uint32_t*)((uint8_t*)a.readback.contents+(a.height+y)*a.rowBytes());
        const uint32_t *bd=(const uint32_t*)((uint8_t*)b.readback.contents+(b.height+y)*b.rowBytes());
        const uint32_t *ai=(const uint32_t*)((uint8_t*)a.readback.contents+(2*a.height+y)*a.rowBytes());
        const uint32_t *bi=(const uint32_t*)((uint8_t*)b.readback.contents+(2*b.height+y)*b.rowBytes());
        for(NSUInteger x=0;x<a.width;x++){depth+=ad[x]!=bd[x];identity+=ai[x]!=bi[x];covered+=ai[x]!=0;}
    }
    return @{@"depthMismatchPixels":@(depth),@"sourceIdMismatchPixels":@(identity),@"opaqueCoveragePixels":@(covered)};
}
int main(int argc,char **argv) {
    @autoreleasepool {
        check(argc==5,@"Usage: minvis SNAPSHOT SHADERS OUTPUT quality|timing");alarm(175);
        NSString *dir=@(argv[1]),*shaders=@(argv[2]),*out=@(argv[3]),*mode=@(argv[4]);bool quality=[mode isEqual:@"quality"];
        check(quality||[mode isEqual:@"timing"],@"Mode");
        check(getenv("MC_GPU_LOCK_HELD")&&strcmp(getenv("MC_GPU_LOCK_HELD"),"timing")==0,@"Exclusive GPU lock");
        id<MTLDevice> dev=MTLCreateSystemDefaultDevice();check(dev!=nil,@"Metal device");
        NSString *dump=[out stringByAppendingPathComponent:@"minimal-shaders"];
        check([[NSFileManager defaultManager]createDirectoryAtPath:dump withIntermediateDirectories:YES attributes:nil error:nil],@"Shader dump");
        auto normal=pipelines(dev,shaders),ids=pipelines(dev,shaders,true),minimal=minimalPipelines(dev,shaders,dump);
        lodscene::Scene scene;std::string error;check(lodscene::load(dev,dir,scene,error),[NSString stringWithUTF8String:error.c_str()]);
        Materials m(dev,dir,scene.manifest);std::vector<ReplayDraw> draws;
        for(const auto &d:scene.draws){const auto &g=scene.groups.at(d.groupOrdinal);draws.push_back({(int)d.layerCode,d.quadCount,scene.arenas.at(g.arena).buffer,d.quadBuffer,d.itemBuffer,d.argsBuffer,g.arena<<23});}
        Replay original(dev,scene.sectionInfos,m,normal,draws),marked(dev,scene.sectionInfos,m,ids,draws);Minimal probe{original,minimal};
        Target ref(dev,3024,1964,true),candidate(dev,3024,1964,true),color(dev,3024,1964);
        check(dev.currentAllocatedSize<6ull<<30,@"6 GiB Metal allocation cap");
        marked.render(ref,3,true,3,false);marked.render(ref,3,true,1,true);probe.run(candidate,3,false);probe.run(candidate,1,true);
        NSDictionary *parity=depthIds(ref,candidate);bool ok=[parity[@"depthMismatchPixels"]unsignedLongLongValue]==0&&[parity[@"sourceIdMismatchPixels"]unsignedLongLongValue]==0&&[parity[@"opaqueCoveragePixels"]unsignedLongLongValue]>10000;
        NSMutableDictionary *result=[@{@"schema":@"farsight-scene-minvis-1",@"mode":mode,@"device":dev.name,@"sourceQuads":@(scene.stats.payloadQuads),@"inputVisibleQuads":@(scene.stats.visibleQuads),@"opaqueInputQuads":@(scene.stats.visibleQuads-scene.stats.visibleByLayer[2]),@"waterQuads":@(scene.stats.visibleByLayer[2]),@"depthIdParity":parity,@"MetalAllocatedBytes":@(dev.currentAllocatedSize),@"qualification":@"Visibility precursor only. Original opaque source geometry/order/position and cutout alpha/fade coverage; writes depth and source ID, omits opaque RGB shading, resolve, water, near world, culling, streaming, CPU and present. Not an optimization or game FPS. Water is measured separately as context, not an additive frame estimate."}mutableCopy];
        if(quality) {
            NSMutableArray *motion=[NSMutableArray array];uint8_t saved[48];memcpy(saved,m.globals.contents,48);
            const int32_t shifts[][3]={{16,0,0},{0,16,0},{0,0,-32}};
            for(int i=0;i<3;i++) {
                memcpy(m.globals.contents,saved,48);int32_t *v=(int32_t*)m.globals.contents;for(int k=0;k<3;k++)v[k]+=shifts[i][k];
                marked.render(ref,3,true,1,true);probe.run(candidate,1,true);NSDictionary *d=depthIds(ref,candidate);
                [motion addObject:@{@"offset":@[@(shifts[i][0]),@(shifts[i][1]),@(shifts[i][2])],@"parity":d}];
                ok=ok&&[d[@"depthMismatchPixels"]unsignedLongLongValue]==0&&[d[@"sourceIdMismatchPixels"]unsignedLongLongValue]==0;
            }
            memcpy(m.globals.contents,saved,48);result[@"closedCandidateTranslationChecks"]=motion;
        } else if(ok) {
            std::array<std::vector<double>,4> samples;
            for(int warm=0;warm<3;warm++){original.render(color,7,true,1,false);probe.run(candidate,1,false);original.render(color,4,true,1,false);original.render(color,3,true,1,false);}
            for(int block=0;block<9;block++)for(int j=0;j<4;j++) {
                int k=(block+j)%4;samples[k].push_back(k==1?probe.run(candidate,4,false):original.render(color,k==0?7:(k==2?4:3),true,4,false));
            }
            NSArray *names=@[@"original-all-layers",@"minimal-opaque-visibility-only",@"original-water-only-context",@"original-opaque-shaded"];
            NSMutableArray *rows=[NSMutableArray array];
            for(int k=0;k<4;k++){NSMutableArray *values=[NSMutableArray array];for(double t:samples[k])[values addObject:@(t)];auto sorted=samples[k];std::sort(sorted.begin(),sorted.end());
                [rows addObject:@{@"name":names[k],@"medianGpuMs":@(sorted[4]),@"minGpuMs":@(sorted.front()),@"maxGpuMs":@(sorted.back()),@"gpuMsSamples":values}];
                printf("%s %.6f ms\n",[names[k]UTF8String],sorted[4]);}
            result[@"timings"]=rows;result[@"timingMethod"]=@"Nine rotating four-case blocks, four clear/store passes per GPU timestamp; three warmups per case. No readbacks in timed command buffers. Minimal path has only D32F and R32Uint attachments, no RGB target.";
        }
        result[@"status"]=ok?@"passed":@"failed";writeJson(result,[out stringByAppendingPathComponent:@"result.json"]);
        printf("Depth/ID mismatch %llu/%llu; %s\n",[parity[@"depthMismatchPixels"]unsignedLongLongValue],[parity[@"sourceIdMismatchPixels"]unsignedLongLongValue],ok?"PASS":"FAIL");check(ok,@"Visibility depth/source parity");
    }
    return 0;
}
