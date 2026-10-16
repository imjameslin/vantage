#import <simd/simd.h>
#include "lod_scene_occlusion.h"
#include <random>
#include <iostream>
#include <unistd.h>
static void ck(bool v,const char* s){if(!v)throw std::runtime_error(s);}
static id<MTLBuffer> buf(id<MTLDevice>d,NSUInteger n){auto b=[d newBufferWithLength:n options:MTLResourceStorageModeShared];ck(b!=nil,"buffer");memset(b.contents,0,n);return b;}
static uint32_t pack(simd_float3 p,bool up){uint32_t q=0;for(int i=0;i<3;i++){int a=(int)(up?ceil(p[i]*16):floor(p[i]*16))+512;ck(a>=0&&a<1024,"pack");q|=uint32_t(a)<<(10*i);}return q;}
static simd_float4x4 ident(){return matrix_identity_float4x4;}
static void finish(id<MTLCommandBuffer>b){dispatch_semaphore_t s=dispatch_semaphore_create(0);[b addCompletedHandler:^(id<MTLCommandBuffer>){dispatch_semaphore_signal(s);}];[b commit];ck(dispatch_semaphore_wait(s,dispatch_time(DISPATCH_TIME_NOW,20*NSEC_PER_SEC))==0,"GPU timeout");ck(b.status==MTLCommandBufferStatusCompleted,"GPU failure");}
int main(int argc,char**argv){@autoreleasepool{try{
    alarm(55);ck(argc==2,"output JSON argument");ck(getenv("MC_GPU_LOCK_HELD")&&std::string(getenv("MC_GPU_LOCK_HELD"))=="timing","exclusive lock");
    id<MTLDevice>d=MTLCreateSystemDefaultDevice();auto queue=[d newCommandQueue];
    const uint32_t W=97,H=61,N=128;const NSUInteger row=512;
    lodscene::Scene scene;scene.stats.validated=true;scene.cullBase={999424,-1000448,2048};
    scene.sectionTable=buf(d,32);scene.sectionInfos=buf(d,16);
    int32_t origin[4]={1000000,-1000000,2000,0x3f800000};memcpy(scene.sectionInfos.contents,origin,16);
    float* table=(float*)scene.sectionTable.contents;for(int i=0;i<3;i++){table[i]=float(origin[i]-scene.cullBase[i]);table[4+i]=-table[i];}table[3]=1.f/16;
    for(unsigned layer=0;layer<3;layer++){lodscene::Group g;g.ordinal=layer;g.layer=layer;g.stride=layer?48:112;g.clusterCount=N;g.clusters=buf(d,16*N);scene.groups.push_back(g);}
    auto projection=buf(d,64),terrain=buf(d,80),globals=buf(d,48),view=buf(d,128);
    int32_t camera[3]={1000003,-999998,2003};memcpy(globals.contents,camera,12);
    float* gf=(float*)globals.contents;gf[4]=-.25f;gf[5]=-.375f;gf[6]=-.5f;gf[8]=W;gf[9]=H;
    ((uint32_t*)view.contents)[21]=4;((float*)view.contents)[22]=1.f/16;
    // Deliberately unrelated cull-camera delta: the render camera comes from Globals.
    ((float*)view.contents)[24]=10000;((float*)view.contents)[25]=-10000;
    std::vector<simd_float3> lo(N),hi(N);std::mt19937 gen(501);std::uniform_real_distribution<float> uni(0,1);
    for(unsigned i=0;i<N;i++){simd_float3 c={-6+12*uni(gen),-3+6*uni(gen),-3-15*uni(gen)};lo[i]=c-(simd_float3){.15f,.15f,.15f};hi[i]=c+(simd_float3){.15f,.15f,.15f};}
    lo[0]={-.2f,-.2f,-8.2f};hi[0]={.2f,.2f,-7.8f};
    lo[1]={-.05f,-.05f,-.55f};hi[1]={.05f,.05f,-.45f};
    lo[3]={-.1f,-.1f,-.2f};hi[3]={.1f,.1f,.2f};
    lo[4]={20,-1,-2};hi[4]={22,1,-1.8f};
    simd_float3 adjustment={3.25f,2.375f,3.5f};for(unsigned i=0;i<N;i++){lo[i]+=adjustment;hi[i]+=adjustment;}
    const unsigned corners[36]={0,2,1,1,2,3,4,5,6,5,7,6,0,1,4,1,5,4,2,6,3,3,6,7,0,4,2,2,4,6,1,3,5,3,7,5};
    auto vertices=buf(d,N*36*16),live=buf(d,N*4),upload=buf(d,row*H);
    MTLTextureDescriptor* td=[MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatDepth32Float width:W height:H mipmapped:NO];
    td.storageMode=MTLStorageModePrivate;td.usage=MTLTextureUsageShaderRead|MTLTextureUsageRenderTarget;
    auto depth=[d newTextureWithDescriptor:td];ck(depth!=nil,"D32F allocation");
    NSString* source=@R"MSL(#include <metal_stdlib>
using namespace metal;
struct G {packed_int3 camera;float a;packed_float3 offset;float b;float2 screen;int c,e;};
struct O {float4 p[[position]];uint id[[flat]];};
vertex O vs(uint v[[vertex_id]],device const float4* pos[[buffer(0)]],constant float4x4& p[[buffer(1)]],constant float4x4& t[[buffer(2)]],constant int4& s[[buffer(3)]],constant G& g[[buffer(4)]]) {
 float3 x=(pos[v].xyz+float3(s.xyz-int3(g.camera)))+float3(g.offset);float4x4 m=p*t;
 float4 c=fma(m[2],float4(x.z),fma(m[1],float4(x.y),fma(m[0],float4(x.x),m[3])));c.y=-c.y;return O{c,v/36};}
[[early_fragment_tests]] fragment void fs(O in[[stage_in]],device atomic_uint* live[[buffer(0)]]){atomic_store_explicit(live+in.id,1u,memory_order_relaxed);}
)MSL";
    NSError* error=nil;MTLCompileOptions* opts=[MTLCompileOptions new];opts.languageVersion=MTLLanguageVersion3_2;opts.mathMode=MTLMathModeFast;
    auto library=[d newLibraryWithSource:source options:opts error:&error];ck(library!=nil,error.description.UTF8String);
    MTLRenderPipelineDescriptor* pd=[MTLRenderPipelineDescriptor new];pd.vertexFunction=[library newFunctionWithName:@"vs"];pd.fragmentFunction=[library newFunctionWithName:@"fs"];pd.depthAttachmentPixelFormat=MTLPixelFormatDepth32Float;
    auto pso=[d newRenderPipelineStateWithDescriptor:pd error:&error];ck(pso!=nil,error.description.UTF8String);
    MTLDepthStencilDescriptor* ds=[MTLDepthStencilDescriptor new];ds.depthCompareFunction=MTLCompareFunctionGreaterEqual;ds.depthWriteEnabled=NO;auto state=[d newDepthStencilStateWithDescriptor:ds];
    // Unsorted entries, repeated cluster IDs, and large holes in original IDs.
    const std::vector<std::vector<uint32_t>> entries={{127*64,31*64+1,0,9*64,64,31*64,64*64,95*64,127*64+1},{95*64,64,9*64,64*64,9*64+1},{127*64}};
    for(unsigned group=0;group<3;group++){lodscene::Draw draw;draw.groupOrdinal=group;draw.quadCount=(uint32_t)entries[group].size();draw.quadBuffer=buf(d,4*draw.quadCount);memcpy(draw.quadBuffer.contents,entries[group].data(),4*draw.quadCount);scene.draws.push_back(draw);}
    lodoccl::Query query(d,scene,W,H),sparse(d,scene,W,H,true);ck(query.pyramidWidth()==128&&query.pyramidHeight()==64,"padding");
    ck(query.totalQueried()==2*N&&sparse.totalQueried()==11&&sparse.groupQueried(0)==7&&sparse.groupQueried(1)==4&&sparse.groupQueried(2)==0,"candidate counts");
    auto savedDraws=scene.draws;scene.draws.clear();lodoccl::Query empty(d,scene,W,H,true);scene.draws=std::move(savedDraws);ck(empty.totalQueried()==0,"empty candidates");
    uint64_t hiddenTotal=0,visibleTotal=0,checks=0,candidateChecks=0;NSMutableArray* results=[NSMutableArray array];
    for(unsigned phase=0;phase<10;phase++){
        simd_float4x4 p={};float zoom=phase>=5?1.7f:1.f;p.columns[0].x=zoom/((float)W/H);p.columns[1].y=zoom;p.columns[2].w=-1;p.columns[3].z=.1f;
        simd_float4x4 t=ident();if(phase>=5){float a=.18f;t.columns[0]={cosf(a),0,-sinf(a),0};t.columns[2]={sinf(a),0,cosf(a),0};t.columns[3]={.08f,-.04f,-.2f,1};}
        memcpy(projection.contents,&p,64);memcpy(terrain.contents,&t,64);memcpy(globals.contents,camera,12);if(phase>=5)((int32_t*)globals.contents)[0]++;
        gf[8]=phase==6?W+1:W;table[0]=float(origin[0]-scene.cullBase[0])+(phase==7?1:0);((uint32_t*)view.contents)[21]=phase==8?0:4;
        for(unsigned i=0;i<N;i++){
            simd_float3 a=lo[i],b=hi[i];if(phase==1&&i==0){a=lo[1];b=hi[1];}
            uint32_t mn=pack(a,false),mx=pack(b,true);
            // Oracle uses the exact outward-quantized source box, WITHOUT helper padding.
            for(int k=0;k<3;k++){a[k]=float(int((mn>>(10*k))&1023)-512)/16;b[k]=float(int((mx>>(10*k))&1023)-512)/16;}
            for(unsigned k=0;k<36;k++){unsigned c=corners[k];simd_float4 v={(c&1)?b.x:a.x,(c&2)?b.y:a.y,(c&4)?b.z:a.z,1};((simd_float4*)vertices.contents)[i*36+k]=v;}
            for(auto& g:scene.groups){auto it=(uint32_t*)g.clusters.contents+4*i;it[0]=16*i;it[1]=(3u<<20)|(i==2?(1u<<29):i==6?1:0);it[2]=i==7?mx:mn;it[3]=i==7?mn:mx;if(i==8)it[2]|=1u<<31;}
        }
        for(unsigned y=0;y<H;y++)for(unsigned x=0;x<W;x++){
            float z=.065f;if(phase==2||phase==5||phase==9)z=y<H/2?.07f:.014f;
            if((phase==2||phase==5||phase==9)&&x>=W/2-2&&x<=W/2+2)z=0;
            if(phase==3)z=0;if(phase==4)z=NAN;*(float*)((uint8_t*)upload.contents+y*row+x*4)=z;
        }
        memset(live.contents,0,live.length);
        for(unsigned group=0;group<3;group++){memset(sparse.groupHidden(group).contents,255,4*N);memset(empty.groupHidden(group).contents,255,4*N);}
        auto cb=[queue commandBuffer];auto blit=[cb blitCommandEncoder];
        [blit copyFromBuffer:upload sourceOffset:0 sourceBytesPerRow:row sourceBytesPerImage:row*H sourceSize:MTLSizeMake(W,H,1) toTexture:depth destinationSlice:0 destinationLevel:0 destinationOrigin:MTLOriginMake(0,0,0)];[blit endEncoding];
        query.encode(cb,depth,projection,terrain,globals,view);
        sparse.encode(cb,depth,projection,terrain,globals,view);empty.encode(cb,depth,projection,terrain,globals,view);
        MTLRenderPassDescriptor* rp=[MTLRenderPassDescriptor renderPassDescriptor];rp.depthAttachment.texture=depth;rp.depthAttachment.loadAction=MTLLoadActionLoad;rp.depthAttachment.storeAction=MTLStoreActionStore;
        auto e=[cb renderCommandEncoderWithDescriptor:rp];[e setRenderPipelineState:pso];[e setDepthStencilState:state];[e setCullMode:MTLCullModeNone];
        [e setVertexBuffer:vertices offset:0 atIndex:0];[e setVertexBuffer:projection offset:0 atIndex:1];[e setVertexBuffer:terrain offset:0 atIndex:2];[e setVertexBuffer:scene.sectionInfos offset:0 atIndex:3];[e setVertexBuffer:globals offset:0 atIndex:4];[e setFragmentBuffer:live offset:0 atIndex:0];[e drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:N*36];[e endEncoding];finish(cb);
        unsigned hidden=0,visible=0;auto raster=(uint32_t*)live.contents;
        for(unsigned group=0;group<3;group++){auto mask=(uint32_t*)query.groupHidden(group).contents;
            for(unsigned i=0;i<N;i++){checks++;ck(mask[i]<=1,"boolean mask");
                if(group==2||phase==3||phase==4||phase==6||phase==7||phase==8||i==2||i==3||i==4||i==6||i==7||i==8)ck(mask[i]==0,"uncertain/translucent did not keep");
                if(group==0){hidden+=mask[i];visible+=raster[i]!=0;}if(mask[i]&&raster[i]){std::cerr<<"false occlusion phase="<<phase<<" group="<<group<<" cluster="<<i<<"\n";throw std::runtime_error("raster-visible box hidden");}
                bool active=false;for(uint32_t entry:entries[group])active|=(entry>>6)==i;
                ck(((uint32_t*)sparse.groupHidden(group).contents)[i]==(active?mask[i]:0),"sparse original-ID association or inactive hole");
                ck(((uint32_t*)empty.groupHidden(group).contents)[i]==0,"empty mask not cleared");candidateChecks+=2;
            }
        }
        if(phase==0){ck(((uint32_t*)query.groupHidden(0).contents)[0]==1,"behind proof");ck(((uint32_t*)query.groupHidden(0).contents)[1]==0,"front proof");}
        if(phase==1)ck(((uint32_t*)query.groupHidden(0).contents)[0]==0,"new foreground hidden by stale mask");
        hiddenTotal+=hidden;visibleTotal+=visible;[results addObject:@{@"phase":@(phase),@"hiddenOpaqueClusters":@(hidden),@"rasterVisibleBoxes":@(visible)}];
    }
    ck(hiddenTotal>20&&visibleTotal>20,"nontrivial oracle");
    NSDictionary* result=@{@"passed":@YES,@"checks":@(checks),@"candidateChecks":@(candidateChecks),@"fullTotalQueried":@(query.totalQueried()),@"sparseTotalQueried":@(sparse.totalQueried()),@"emptyTotalQueried":@(empty.totalQueried()),@"phases":results,@"falseOcclusions":@0,@"width":@(W),@"height":@(H),@"paddedWidth":@(query.pyramidWidth()),@"paddedHeight":@(query.pyramidHeight()),@"helperAllocatedBytes":@(query.allocatedBytes()),@"sparseAllocatedBytes":@(sparse.allocatedBytes()),@"deviceAllocatedBytes":@(d.currentAllocatedSize),@"scope":@"Tiny synthetic boxes vs independent fast-math raster depth oracle; no full snapshot run"};
    NSData* data=[NSJSONSerialization dataWithJSONObject:result options:NSJSONWritingPrettyPrinted|NSJSONWritingSortedKeys error:&error];ck([data writeToFile:@(argv[1]) atomically:YES],"write result");printf("PASS %llu checks, %llu hidden / %llu raster-visible across phases\n",(unsigned long long)checks,(unsigned long long)hiddenTotal,(unsigned long long)visibleTotal);return 0;
}catch(const std::exception&e){fprintf(stderr,"FAIL %s\n",e.what());return 1;}}}
