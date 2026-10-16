// Actual-alpha visibility-change census. Previous source geometry is rendered
// at the current view; missing source selection is an ORACLE, not a culler.
#define main lod_scene_visibility_original_main
#include "lod_scene_visibility.mm"
#undef main
#include <simd/simd.h>

static void cap(id<MTLDevice> dev) {
    check(dev.currentAllocatedSize<=lodscene::MaxMetalBytes,@"Metal memory cap");
}

struct ReusePose {const char *name;float x,y,z,zoom,yawDegrees;};
static simd_float4x4 localYaw(float degrees) {
    float a=degrees*(float(M_PI)/180),c=std::cos(a),s=std::sin(a);
    simd_float4x4 m=matrix_identity_float4x4;m.columns[0]={c,0,-s,0};m.columns[2]={s,0,c,0};return m;
}
static void reusePose(Materials &m,const uint8_t* globals,simd_float4x4 projection,ReusePose p) {
    std::memcpy(m.globals.contents,globals,48);float *offset=(float*)((uint8_t*)m.globals.contents+16);
    offset[0]-=p.x;offset[1]-=p.y;offset[2]-=p.z;
    projection=simd_mul(projection,localYaw(p.yawDegrees));
    for(int k=0;k<4;k++){projection.columns[k].x*=p.zoom;projection.columns[k].y*=p.zoom;}
    std::memcpy(m.projection.contents,&projection,64);
}
static const uint32_t* reuseRow(const Target &t,NSUInteger y,int plane) {
    return (const uint32_t*)((const uint8_t*)t.readback.contents+(plane*t.height+y)*t.rowBytes());
}
static bool reuseHas(const Faces &set,uint32_t id) {
    if(!id)return false;uint32_t a=id-1;return set.has(a>>23,a&((1u<<23)-1));
}
struct Change {Faces current,joined,novel;NSDictionary *stats;};
static Change changedSources(Target &current,Target &seed,const Faces &previous,size_t arenas) {
    Change out{finalFaces(current,arenas),previous,Faces(arenas),nil};
    uint64_t novelPixels=0,holes=0,filled=0,wrongIDs=0;
    std::array<uint64_t,4> activeTiles{};constexpr unsigned sizes[]={2,4,8,16};
    std::array<std::vector<uint8_t>,4> tiles;
    for(int k=0;k<4;k++)tiles[k].resize(((current.width+sizes[k]-1)/sizes[k])*((current.height+sizes[k]-1)/sizes[k]));
    for(NSUInteger y=0;y<current.height;y++) {
        auto a=reuseRow(current,y,2),b=reuseRow(seed,y,2);
        for(NSUInteger x=0;x<current.width;x++) {
            wrongIDs+=a[x]!=b[x];
            if(a[x]&&!reuseHas(previous,a[x])) {
                ++novelPixels;holes+=b[x]==0;filled+=b[x]!=0;uint32_t id=a[x]-1;
                out.novel.add(id>>23,id&((1u<<23)-1));out.joined.add(id>>23,id&((1u<<23)-1));
                for(int k=0;k<4;k++){auto at=(y/sizes[k])*((current.width+sizes[k]-1)/sizes[k])+x/sizes[k];if(!tiles[k][at]){tiles[k][at]=1;++activeTiles[k];}}
            }
        }
    }
    check(novelPixels==holes+filled&&out.joined.count==previous.count+out.novel.count,@"Novel source accounting");
    auto tileRows=[NSMutableArray array];for(int k=0;k<4;k++)[tileRows addObject:@{@"size":@(sizes[k]),@"tilesWithNewSourcePixels":@(activeTiles[k]),@"totalTiles":@(tiles[k].size())}];
    out.stats=@{@"currentVisibleSources":@(out.current.count),@"currentCoveredPixels":@(out.current.pixels),@"previousSources":@(previous.count),@"newWinningSources":@(out.novel.count),@"newWinningSourcePixels":@(novelPixels),@"newPixelsAtSeedHoles":@(holes),@"newPixelsOverFilledSeed":@(filled),@"allSourceIdDifferences":@(wrongIDs),@"previousPlusNewSources":@(out.joined.count),@"newPixelFractionOfCovered":@(out.current.pixels?double(novelPixels)/out.current.pixels:0),@"tiles":tileRows};
    return out;
}
int main(int argc,char **argv) {
 try {@autoreleasepool {
    check(argc==5,@"Usage: reuse SNAPSHOT SHADERS OUTPUT quality|timing");alarm(175);
    NSString *dir=@(argv[1]),*shaders=@(argv[2]),*out=@(argv[3]),*mode=@(argv[4]);bool quality=[mode isEqual:@"quality"];check(quality||[mode isEqual:@"timing"],@"Mode");
    check(getenv("MC_GPU_LOCK_HELD")&&strcmp(getenv("MC_GPU_LOCK_HELD"),"timing")==0,@"Exclusive GPU lock");
    auto dev=MTLCreateSystemDefaultDevice();check(dev!=nil,@"Metal device");
    NSString *dump=[out stringByAppendingPathComponent:@"identity-shaders"];check([[NSFileManager defaultManager]createDirectoryAtPath:dump withIntermediateDirectories:YES attributes:nil error:nil],@"Shader directory");
    auto normal=pipelines(dev,shaders),idPipes=pipelines(dev,shaders,true,dump);lodscene::Scene scene;std::string error;
    check(lodscene::load(dev,dir,scene,error),[NSString stringWithUTF8String:error.c_str()]);Materials materials(dev,dir,scene.manifest);
    uint8_t savedGlobals[48];std::memcpy(savedGlobals,materials.globals.contents,48);simd_float4x4 savedProjection;std::memcpy(&savedProjection,materials.projection.contents,64);
    Replay original(dev,scene.sectionInfos,materials,normal,originalDraws(scene)),identity(dev,scene.sectionInfos,materials,idPipes,originalDraws(scene));
    // Source buffers use <4.2GiB without a second RT hierarchy; reserve 600MiB
    // for the four native outputs/readbacks and transient selected lists.
    check(dev.currentAllocatedSize+600ull*1024*1024<=lodscene::MaxMetalBytes,@"Reuse target/list preflight");
    Target full(dev,3024,1964,true),seed(dev,3024,1964,true),repaired(dev,3024,1964),reference(dev,3024,1964);cap(dev);
    identity.render(full,7,true,2,false);identity.render(full,7,true,1,true);Faces previous=finalFaces(full,scene.arenas.size());
    check(previous.count>0&&previous.pixels>10000,@"Initial visible source census");
    auto baseRows=[NSMutableArray array];auto seedDraws=selectedDraws(dev,scene,previous,baseRows);cap(dev);
    Replay seedReplay(dev,scene.sectionInfos,materials,idPipes,seedDraws);
    constexpr float frameMove=10.9f/700.0f,frameYaw=90.0f/700.0f;
    const ReusePose poses[]={
        {"same-view",0,0,0,1,0},{"flight-x-1-frame",frameMove,0,0,1,0},
        {"flight-z-1-frame",0,0,frameMove,1,0},{"flight-y-1-frame",0,frameMove,0,1,0},
        {"flight-x-4-frames",frameMove*4,0,0,1,0},{"flight-x-16-frames",frameMove*16,0,0,1,0},
        {"flight-x-64-frames",frameMove*64,0,0,1,0},{"x-16-blocks",16,0,0,1,0},
        {"zoom-1.001",0,0,0,1.001f,0},{"zoom-1.01",0,0,0,1.01f,0},
        {"zoom-2",0,0,0,2,0},{"local-yaw-1-frame",0,0,0,1,frameYaw},
        {"local-yaw-4-frames",0,0,0,1,frameYaw*4},
        {"flight-and-local-yaw",frameMove,0,0,1,frameYaw}
    };
    auto rows=[NSMutableArray array],timings=[NSMutableArray array];bool oracleOk=true;
    for(uint32_t i=0;i<sizeof(poses)/sizeof(poses[0]);i++) {@autoreleasepool {
        auto p=poses[i];reusePose(materials,savedGlobals,savedProjection,p);
        original.render(reference,7,true,1,true);identity.render(full,7,true,1,true);seedReplay.render(seed,7,true,1,true);
        auto changed=changedSources(full,seed,previous,scene.arenas.size());
        auto sourceRows=[NSMutableArray array];auto repairedDraws=selectedDraws(dev,scene,changed.joined,sourceRows);cap(dev);
        Replay fixed(dev,scene.sectionInfos,materials,normal,std::move(repairedDraws));fixed.render(repaired,7,true,1,true);
        auto parity=compare(reference,repaired),seedImage=compare(reference,seed),instrumentation=compare(reference,full);
        bool exactDepth=[parity[@"depthBitMismatchPixels"]unsignedLongLongValue]==0;
        bool ok=diagnosticBound(parity)&&diagnosticBound(instrumentation);oracleOk&=ok;
        [rows addObject:@{@"pose":@(p.name),@"eyeOffset":@[@(p.x),@(p.y),@(p.z)],@"zoom":@(p.zoom),@"localYawDegrees":@(p.yawDegrees),@"sourceChange":changed.stats,@"seedImage":seedImage,@"oracleRepairedImage":parity,@"identityInstrumentation":instrumentation,@"oracleDiagnosticPassed":@(ok),@"oracleDepthExact":@(exactDepth)}];
        printf("%s newFaces%llu newPixels%llu filled%llu holes%llu repairedDepthDiff%llu\n",p.name,[changed.stats[@"newWinningSources"]unsignedLongLongValue],[changed.stats[@"newWinningSourcePixels"]unsignedLongLongValue],[changed.stats[@"newPixelsOverFilledSeed"]unsignedLongLongValue],[changed.stats[@"newPixelsAtSeedHoles"]unsignedLongLongValue],[parity[@"depthBitMismatchPixels"]unsignedLongLongValue]);fflush(stdout);
        if(quality&&(i==1||i==7||i==13)) {
            NSString *prefix=[out stringByAppendingPathComponent:@(p.name)];png(reference,[prefix stringByAppendingString:@"-reference.png"]);png(seed,[prefix stringByAppendingString:@"-seed-only.png"]);png(repaired,[prefix stringByAppendingString:@"-oracle-repaired.png"]);
        }
        if(!quality&&(i==1||i==7||i==13)&&ok) {
            std::array<std::vector<double>,2> samples;auto run=[&](int k,int n){return k?fixed.render(repaired,7,true,n,false):original.render(reference,7,true,n,false);};
            for(int w=0;w<3;w++)for(int k=0;k<2;k++)run(k,1);
            for(int b=0;b<9;b++)for(int j=0;j<2;j++){int k=(b+j)%2;samples[k].push_back(run(k,4));}
            auto arms=[NSMutableArray array];NSArray *names=@[@"original-all-sources",@"previous-plus-oracle-new-sources"];
            for(int k=0;k<2;k++){auto values=samples[k];std::sort(values.begin(),values.end());auto raw=[NSMutableArray array];for(double v:samples[k])[raw addObject:@(v)];[arms addObject:@{@"name":names[k],@"medianGpuMs":@(values[4]),@"minGpuMs":@(values.front()),@"maxGpuMs":@(values.back()),@"gpuMsSamples":raw}];printf("%s %s %.6fms\n",p.name,[names[k]UTF8String],values[4]);}
            [timings addObject:@{@"pose":@(p.name),@"arms":arms}];
        }
    }}
    writeJson(@{@"schema":@"farsight-scene-reuse-census-1",@"mode":mode,@"initialVisibleSources":@(previous.count),@"initialCoveredPixels":@(previous.pixels),@"initialGroups":baseRows,@"poses":rows,@"timings":timings,@"MetalAllocatedBytes":@(dev.currentAllocatedSize),@"oracleDiagnosticPassed":@(oracleOk),@"status":oracleOk?@"passed-oracle-diagnostic-only":@"failed",@"visualQualityQualified":@NO,@"scope":@"Original production shaders/alpha/water and current camera uniforms on fixed captured candidate lists. Every pose compares against the SAME initial visible owner set, not a continually updated cache. Oracle union includes all missing current winning source owners. Full reference/ID draw, CPU selection/readback and recovery discovery excluded from timings. No executable repair/culling, live scene updates, full game or FPS evidence."},[out stringByAppendingPathComponent:@"result.json"]);return oracleOk?0:3;
 }}catch(NSException *e){fprintf(stderr,"reuse: %s\n",e.reason.UTF8String);return 2;}catch(const std::exception &e){fprintf(stderr,"reuse: %s\n",e.what());return 2;}
}
