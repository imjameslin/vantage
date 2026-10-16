// Prompt7 offscreen prototype. No production path or game FPS claim.
#define main lod_scene_replay_original_main
#include "lod_scene_replay.mm"
#undef main
#include <simd/simd.h>
#include <numeric>
#include "lod_cached_quality.h"
using namespace simd;
struct View7 {float4 eye,right,up,forward,lens;uint4 dims;};
struct Camera7 {float3 eye,right,up,forward;float sx,sy,cx=0,cy=0;};
static float3 cubeF(unsigned f){const float3 a[]={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};return a[f];}
static float3 cubeU(unsigned f){return f==2?(float3){0,0,-1}:f==3?(float3){0,0,1}:(float3){0,1,0};}
static Camera7 cubeCamera(float3 eye,unsigned f){float3 F=cubeF(f),U=cubeU(f);return {eye,cross(F,U),U,F,1,1};}
static NSDictionary* summary7(std::vector<double> v){if(v.empty())return @{};std::sort(v.begin(),v.end());return @{@"medianMs":@(v[v.size()/2]),@"p99Ms":@(v[std::min(v.size()-1,size_t(ceil(v.size()*.99)-1))]),@"maxMs":@(v.back()),@"count":@(v.size())};}
struct Bound7 {float3 center,extent;bool valid;};
struct Raster7 {
    id<MTLDevice> dev;lodscene::Scene scene;std::unique_ptr<Materials> mat;std::map<int,Pipeline> psos;
    std::unique_ptr<Replay> replay;std::vector<std::vector<Bound7>> bounds;float za,zb;Camera7 original;
    Raster7(id<MTLDevice>d,NSString*snapshot,NSString*shaders):dev(d) {
        std::string error;check(lodscene::load(dev,snapshot,scene,error),@(error.c_str()));
        check(scene.stats.validated&&scene.stats.payloadQuads==scene.stats.submittedQuads,@"Complete captured membership required");
        mat=std::make_unique<Materials>(dev,snapshot,scene.manifest);psos=pipelines(dev,shaders,true,nil,true);
        float *pr=(float*)mat->projection.contents,*m=(float*)mat->terrain.contents,*g=(float*)mat->globals.contents;
        int32_t *gi=(int32_t*)g;za=pr[10];zb=pr[14];
        original={{gi[0]-g[4],gi[1]-g[5],gi[2]-g[6]},{m[0],m[4],m[8]},{m[1],m[5],m[9]},{-m[2],-m[6],-m[10]},pr[0],pr[5]};
        std::vector<ReplayDraw> draws;
        for(const auto &group:scene.groups) {
            auto quads=[dev newBufferWithLength:group.submittedQuads*4 options:MTLResourceStorageModeShared];
            auto items=[dev newBufferWithLength:group.clusterCount*8 options:MTLResourceStorageModeShared];
            check(quads&&items,@"Complete draw lists");auto*src=(uint32_t*)group.clusters.contents,*it=(uint32_t*)items.contents,*q=(uint32_t*)quads.contents;
            std::vector<Bound7> bs;bs.reserve(group.clusterCount);uint32_t at=0;
            for(uint32_t c=0;c<group.clusterCount;c++){
                auto*p=src+c*4;it[c*2]=p[0];it[c*2+1]=p[1]&0xfffff;uint32_t n=((p[1]>>20)&63)+1;
                for(uint32_t j=0;j<n;j++)q[at++]=(c<<6)|j;
                const auto&s=scene.sections.at(scene.sectionIndex.at(p[1]&0xfffff));float unit=ldexpf(1.f,s.origin[3])/16;
                float3 lo,hi;for(int k=0;k<3;k++){lo[k]=s.origin[k]+(int((p[2]>>(k*10))&1023)-512)*unit-.03125f;hi[k]=s.origin[k]+(int((p[3]>>(k*10))&1023)-512)*unit+.03125f;}
                bs.push_back({(lo+hi)*.5f,(hi-lo)*.5f,(p[1]&(1u<<29))==0});
            }
            check(at==group.submittedQuads,@"Full cluster membership");bounds.push_back(std::move(bs));
            draws.push_back({int(group.layer+(group.stride==48?8:0)),at,scene.arenas.at(group.arena).buffer,quads,items,nil,group.arena<<23});
        }
        replay=std::make_unique<Replay>(dev,scene.sectionInfos,*mat,psos,std::move(draws));
        printf("Full captured raster: %llu quads, %llu clusters, %.2f GiB allocated\n",scene.stats.submittedQuads,scene.stats.submittedClusters,double(dev.currentAllocatedSize)/(1ull<<30));fflush(stdout);
    }
    uint64_t prepare(Camera7 c,unsigned width,unsigned height){
        float*pr=(float*)mat->projection.contents;memset(pr,0,64);pr[0]=c.sx;pr[5]=c.sy;pr[8]=c.cx;pr[9]=c.cy;pr[10]=za;pr[11]=-1;pr[14]=zb;
        float*m=(float*)mat->terrain.contents;memset(m,0,64);for(int k=0;k<3;k++){m[4*k]=c.right[k];m[4*k+1]=c.up[k];m[4*k+2]=-c.forward[k];}m[15]=1;
        float*g=(float*)mat->globals.contents;int32_t*gi=(int32_t*)g;for(int k=0;k<3;k++){gi[k]=floorf(c.eye[k]);g[k+4]=gi[k]-c.eye[k];}g[8]=width;g[9]=height;
        float3 planes[]={c.forward*(1-c.cx)+c.right*c.sx,c.forward*(1+c.cx)-c.right*c.sx,c.forward*(1-c.cy)+c.up*c.sy,c.forward*(1+c.cy)-c.up*c.sy,c.forward};
        uint64_t total=0;
        for(size_t i=0;i<scene.groups.size();i++){
            auto&draw=replay->draws[i];auto*dst=(uint32_t*)draw.quads.contents;auto*src=(uint32_t*)scene.groups[i].clusters.contents;uint32_t at=0;
            for(uint32_t k=0;k<bounds[i].size();k++){
                const auto&b=bounds[i][k];bool visible=true;
                if(b.valid)for(auto p:planes)if(dot(p,b.center-c.eye)+dot(abs(p),b.extent)<0){visible=false;break;}
                if(visible){uint32_t n=((src[k*4+1]>>20)&63)+1;for(uint32_t j=0;j<n;j++)dst[at++]=(k<<6)|j;}
            }
            draw.count=at;total+=at;
        }return total;
    }
    double render(Target&t,Camera7 c){prepare(c,t.width,t.height);auto cb=[replay->queue commandBuffer];replay->encode(cb,t,7,false);return finish(cb);}
};
struct Cache7 {
    unsigned res,tile,side,n;id<MTLDevice>dev;id<MTLTexture>color,depth,ids,warpedDepth;id<MTLBuffer>eyes,stats;
    id<MTLComputePipelineState>warp,rotation;std::unique_ptr<Target> tileTarget;std::vector<unsigned> ages;
    Cache7(id<MTLDevice>d,unsigned r,unsigned ts,NSString*source):res(r),tile(ts),side(r/ts),n(6*side*side),dev(d),ages(n){
        check(r%ts==0,@"Tile alignment");MTLTextureDescriptor*td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:r height:r mipmapped:NO];
        td.textureType=MTLTextureType2DArray;td.arrayLength=6;td.storageMode=MTLStorageModePrivate;td.usage=MTLTextureUsageRenderTarget|MTLTextureUsageShaderRead|MTLTextureUsagePixelFormatView;
        color=[dev newTextureWithDescriptor:td];td.pixelFormat=MTLPixelFormatDepth32Float;depth=[dev newTextureWithDescriptor:td];td.pixelFormat=MTLPixelFormatR32Uint;ids=[dev newTextureWithDescriptor:td];
        check(color&&depth&&ids,@"360 cache allocation");
        td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatR32Float width:3024 height:1964 mipmapped:NO];td.storageMode=MTLStorageModePrivate;td.usage=MTLTextureUsageShaderRead|MTLTextureUsageShaderWrite;warpedDepth=[dev newTextureWithDescriptor:td];
        eyes=[dev newBufferWithLength:n*16 options:MTLResourceStorageModeShared];stats=[dev newBufferWithLength:n*16 options:MTLResourceStorageModeShared];
        NSError*e=nil;NSString*text=[NSString stringWithContentsOfFile:source encoding:NSUTF8StringEncoding error:&e];check(text!=nil,e.description);
        MTLCompileOptions*opts=[MTLCompileOptions new];opts.languageVersion=MTLLanguageVersion3_2;auto lib=[dev newLibraryWithSource:text options:opts error:&e];check(lib!=nil,e.description);
        warp=[dev newComputePipelineStateWithFunction:[lib newFunctionWithName:@"cachedWarp"] error:&e];check(warp!=nil,e.description);rotation=[dev newComputePipelineStateWithFunction:[lib newFunctionWithName:@"cachedRotation"] error:&e];check(rotation!=nil,e.description);
        tileTarget=std::make_unique<Target>(dev,tile,tile,true);
    }
    void initialize(Raster7&r,float3 eye){
        Target face(dev,res,res,true);face.readback=nil;
        for(unsigned f=0;f<6;f++){
            face.color=[color newTextureViewWithPixelFormat:color.pixelFormat textureType:MTLTextureType2D levels:NSMakeRange(0,1) slices:NSMakeRange(f,1)];
            face.depth=[depth newTextureViewWithPixelFormat:depth.pixelFormat textureType:MTLTextureType2D levels:NSMakeRange(0,1) slices:NSMakeRange(f,1)];
            face.identity=[ids newTextureViewWithPixelFormat:ids.pixelFormat textureType:MTLTextureType2D levels:NSMakeRange(0,1) slices:NSMakeRange(f,1)];
            double ms=r.render(face,cubeCamera(eye,f));printf("cube face %u %.3fms\n",f,ms);fflush(stdout);
        }
        auto*dst=(float4*)eyes.contents;for(unsigned i=0;i<n;i++){dst[i]=(float4){eye.x,eye.y,eye.z,0};ages[i]=0;}
    }
    double project(Raster7&r,Target&output,Camera7 c,unsigned frame,bool fast=false){
        if(!fast)memset(stats.contents,0,stats.length);View7 v={{c.eye.x,c.eye.y,c.eye.z,float(frame)},{c.right.x,c.right.y,c.right.z,0},{c.up.x,c.up.y,c.up.z,0},{c.forward.x,c.forward.y,c.forward.z,0},{c.sy*1964*.5f,0,r.zb,r.za},{3024,1964,res,tile}};
        auto cb=[r.replay->queue commandBuffer];auto ce=[cb computeCommandEncoder];[ce setComputePipelineState:fast?rotation:warp];
        [ce setTexture:color atIndex:0];[ce setTexture:depth atIndex:1];[ce setTexture:ids atIndex:2];[ce setTexture:output.color atIndex:3];[ce setTexture:warpedDepth atIndex:4];
        [ce setBytes:&v length:sizeof(v) atIndex:0];[ce setBuffer:eyes offset:0 atIndex:1];[ce setBuffer:stats offset:0 atIndex:2];
        [ce dispatchThreads:MTLSizeMake(3024,1964,1) threadsPerThreadgroup:MTLSizeMake(8,8,1)];[ce endEncoding];return finish(cb);
    }
    double repair(Raster7&r,Camera7 current,unsigned index,unsigned frame){
        unsigned face=index/(side*side),tx=index%side,ty=(index/side)%side;
        Camera7 c=cubeCamera(current.eye,face);c.sx=c.sy=side;c.cx=(2.f*(tx+.5f)/side-1)*side;c.cy=(2.f*(ty+.5f)/side-1)*side;
        r.prepare(c,tile,tile);auto cb=[r.replay->queue commandBuffer];r.replay->encode(cb,*tileTarget,7,false);
        auto blit=[cb blitCommandEncoder];id<MTLTexture>src[]={tileTarget->color,tileTarget->depth,tileTarget->identity},dst[]={color,depth,ids};
        for(int k=0;k<3;k++)[blit copyFromTexture:src[k] sourceSlice:0 sourceLevel:0 sourceOrigin:MTLOriginMake(0,0,0) sourceSize:MTLSizeMake(tile,tile,1) toTexture:dst[k] destinationSlice:face destinationLevel:0 destinationOrigin:MTLOriginMake(tx*tile,ty*tile,0)];
        [blit endEncoding];double ms=finish(cb);((float4*)eyes.contents)[index]=(float4){current.eye.x,current.eye.y,current.eye.z,float(frame)};ages[index]=frame;return ms;
    }
    uint64_t holes(){uint64_t sum=0;auto*s=(uint32_t*)stats.contents;for(unsigned i=0;i<n;i++)sum+=s[i*4];return sum;}
    unsigned priority(unsigned frame){auto*s=(uint32_t*)stats.contents;unsigned best=0;double score=-1;for(unsigned i=0;i<n;i++){float error;memcpy(&error,s+i*4+2,4);double v=s[i*4]*1.e9+(s[i*4+1]?1.e6+1.e5*error+frame-ages[i]:0);if(v>score){score=v;best=i;}}return best;}
};
int main(int argc,char**argv){@autoreleasepool{
    check(argc==6,@"Usage: cached_far SNAPSHOT SHADERS WARP_SOURCE OUT FRAMES");alarm(3600);
    check(getenv("MC_GPU_LOCK_HELD")&&!strcmp(getenv("MC_GPU_LOCK_HELD"),"timing"),@"Timing lock required");
    check(getenv("MTL_DEBUG_LAYER")&&!strcmp(getenv("MTL_DEBUG_LAYER"),"0")&&getenv("MTL_SHADER_VALIDATION")&&!strcmp(getenv("MTL_SHADER_VALIDATION"),"0"),@"Validation off");
    NSString*out=@(argv[4]);unsigned frames=atoi(argv[5]);check(frames>=10&&frames<=7000,@"Frame count");auto dev=MTLCreateSystemDefaultDevice();check(dev!=nil,@"Metal");
    unsigned resolution=getenv("CACHED_FACE_SIZE")?atoi(getenv("CACHED_FACE_SIZE")):8192;check(resolution==4096||resolution==8192,@"Audited cache sizes only");
    Raster7 raster(dev,@(argv[1]),@(argv[2]));Cache7 cache(dev,resolution,128,@(argv[3]));
    Target candidate(dev,3024,1964,true),reference(dev,3024,1964,true);
    auto td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm width:3024 height:1964 mipmapped:NO];td.storageMode=MTLStorageModePrivate;td.usage=MTLTextureUsageShaderRead|MTLTextureUsageShaderWrite|MTLTextureUsageRenderTarget;candidate.color=[dev newTextureWithDescriptor:td];candidate.depth=cache.warpedDepth;candidate.identity=nil;
    printf("Total Metal %.3f GiB; cube alone %.3f GiB\n",double(dev.currentAllocatedSize)/(1ull<<30),double(6ull*resolution*resolution*12)/(1ull<<30));fflush(stdout);
    check(dev.currentAllocatedSize<std::min<NSUInteger>(12ull<<30,dev.recommendedMaxWorkingSetSize),@"Offscreen working-set cap");
    cache.initialize(raster,raster.original.eye);
    raster.render(reference,raster.original);
    for(int i=0;i<10;i++)cache.project(raster,candidate,raster.original,0);
    std::vector<double>ms;uint64_t maxHoles=0;
    for(unsigned f=0;f<frames;f++){ms.push_back(cache.project(raster,candidate,raster.original,f));maxHoles=std::max(maxHoles,cache.holes());}
    auto cb=[raster.replay->queue commandBuffer];candidate.copy(cb);reference.copy(cb);finish(cb);png(candidate,[out stringByAppendingPathComponent:@"standing-cache.png"]);png(reference,[out stringByAppendingPathComponent:@"standing-reference.png"]);
    std::vector<double> turnMs,fastMs;
    for(unsigned f=0;f<frames;f++){
        float angle=2*M_PI*f/700.f;Camera7 c=raster.original;
        float co=cosf(angle),si=sinf(angle);auto rotate=[&](float3 v){return (float3){co*v.x+si*v.z,v.y,-si*v.x+co*v.z};};
        c.forward=rotate(c.forward);c.right=rotate(c.right);c.up=rotate(c.up);
        turnMs.push_back(cache.project(raster,candidate,c,f));fastMs.push_back(cache.project(raster,candidate,c,f,true));
    }
    // The standing reference is a true full render with immutable inputs.
    // Reusing those exact oracle bytes is valid; every generated candidate frame
    // below is compared, without counting quality instrumentation as render time.
    lodcachedquality::Evaluator quality(dev,"native/tests/lod_cached_quality.metal");
    NSMutableArray *budgetRows=[NSMutableArray array];
    for(double budget:{.15,.25,.4}) {
        cache.initialize(raster,raster.original.eye);
        cache.project(raster,candidate,raster.original,0);
        double estimate=.05;std::vector<double>warpTimes,repairTimes,totalTimes;
        NSMutableArray *perFrame=[NSMutableArray array];double worstSsim=2;unsigned failed=0,over=0;NSDictionary*worst=nil;
        NSString*label=[NSString stringWithFormat:@"standing-b%.2f",budget];
        for(unsigned f=1;f<=120;f++){@autoreleasepool{
            double repairMs=0;unsigned tiles=0;
            while(tiles<4 && repairMs+estimate<=budget) {
                unsigned t=cache.priority(f);double cost=cache.repair(raster,raster.original,t,f);
                repairMs+=cost;estimate=std::max(estimate,cost);tiles++;
            }
            double warpMs=cache.project(raster,candidate,raster.original,f,true);
            NSDictionary*m=quality.evaluate(raster.replay->queue,candidate.color,reference.color);
            failed+=![m[@"ok"] boolValue];over+=repairMs>budget;
            warpTimes.push_back(warpMs);repairTimes.push_back(repairMs);totalTimes.push_back(warpMs+repairMs);
            [perFrame addObject:@{@"frame":@(f),@"warpMs":@(warpMs),@"repairMs":@(repairMs),@"tiles":@(tiles),@"quality":m}];
            if([m[@"ssim"] doubleValue]<worstSsim) {
                worstSsim=[m[@"ssim"] doubleValue];worst=m;
                auto copy=[raster.replay->queue commandBuffer];candidate.copy(copy);finish(copy);
                png(candidate,[out stringByAppendingPathComponent:[label stringByAppendingString:@"-worst.png"]]);
            }
        }}
        [budgetRows addObject:@{@"budgetMs":@(budget),@"warp":summary7(warpTimes),@"repair":summary7(repairTimes),@"total":summary7(totalTimes),@"failedFrames":@(failed),@"repairOverrunFrames":@(over),@"worstQualityBySSIM":worst,@"frames":perFrame}];
        printf("budget %.2f: %u/120 quality failures, worst SSIM %.5f\n",budget,failed,worstSsim);fflush(stdout);
    }
    NSDictionary*result=@{@"schema":@"farsight-cached-7-prototype",@"stage":@"standing kill screen; general and fast rotation timing; flying/zoom not evaluated",@"standingBudgetScreens":budgetRows,@"warp":summary7(ms),@"turnWarp":summary7(turnMs),@"rotationFast":summary7(fastMs),@"maxHolePixels":@(maxHoles),@"MetalAllocatedBytes":@(dev.currentAllocatedSize),@"cacheBytes":@(6ull*resolution*resolution*12),@"cacheFaceSize":@(resolution),@"sourceQuads":@(raster.scene.stats.submittedQuads),@"samples":@(frames),@"validation":@0,@"width":@3024,@"height":@1964};
    writeJson(result,[out stringByAppendingPathComponent:@"floor.json"]);printf("%s\n",result.description.UTF8String);
}}
