// Prompt6 Step1 only. Serialized isolated GPU spans, never game FPS.
#define main lod_scene_replay_original_main
#include "lod_scene_replay.mm"
#undef main
#include <simd/simd.h>
#include <fstream>
#include <numeric>
#include <memory>

using simd::float3;using simd::float4;using simd::uint4;
struct N64 {uint64_t mask;uint32_t children,material;};
struct P64 {float4 eye,forward,right,up,root,low,high;uint4 dims;float4 lens;uint4 extra;};
struct B64 {float4 low,high;};
static_assert(sizeof(N64)==16 && sizeof(P64)==160 && sizeof(B64)==32);
static float4 v4(NSArray *a,float w) {check(a.count==3,@"Expected xyz");return(float4){[a[0] floatValue],[a[1] floatValue],[a[2] floatValue],w};}
struct Tree64 {
    NSDictionary *meta;NSData *raw;id<MTLBuffer> compact,padded,boxes;std::array<id<MTLBuffer>,3> materials;P64 base{};
    Tree64(id<MTLDevice>dev,NSString*path) {
        meta=json([path stringByAppendingString:@".json"]);
        raw=[NSData dataWithContentsOfFile:[path stringByAppendingString:@".bin"] options:NSDataReadingMappedIfSafe error:nil];
        check(raw&&raw.length && raw.length%16==0,@"Tree raw nodes");
        check(raw.length<=2ull<<30,@"Tree memory cap");
        base.root=v4(meta[@"origin"],[meta[@"size"] floatValue]);
        check(base.root.w<=65536,@"Maximum supported tree depth8");
        base.low=v4(meta[@"boundsMin"],0);base.high=v4(meta[@"boundsMax"],0);
        base.dims=(uint4){3024,1964,(uint32_t)(raw.length/16),0};base.extra.y=1;
        compact=smallBuffer(dev,raw,@"64-tree compact16");
        check(base.dims.z==[meta[@"nodeCount"] unsignedIntValue],@"Node count");
        base.extra.w=[meta[@"leafMaterialCount"] unsignedIntValue];
        std::ifstream in;uint64_t remaining=base.extra.w;
        if(remaining)in.open([[path stringByAppendingString:@"-materials.bin"] fileSystemRepresentation],std::ios::binary);
        check(!remaining||bool(in),@"Compact leaf materials");
        std::vector<uint32_t>chunk(1<<20);
        for(unsigned page=0;page<3;page++) {
            uint64_t count=std::min<uint64_t>(1ull<<29,remaining);remaining-=count;
            materials[page]=[dev newBufferWithLength:std::max<uint64_t>(4,count*2) options:MTLResourceStorageModeShared];
            check(materials[page]!=nil,@"Leaf material GPU page allocation");
            uint16_t*dst=(uint16_t*)materials[page].contents;
            for(uint64_t at=0;at<count;) {
                size_t n=std::min<uint64_t>(chunk.size(),count-at);in.read((char*)chunk.data(),n*4);check(bool(in),@"Leaf material input size");
                for(size_t j=0;j<n;j++){check(chunk[j]>0&&chunk[j]<=65535,@"Lossless uint16 material encoding");dst[at+j]=(uint16_t)chunk[j];}at+=n;
            }
        }
        check(!remaining,@"Leaf page count cap");
        if(base.extra.w){char extra;check(!in.read(&extra,1),@"Trailing material input");}
        const N64*n=(const N64*)raw.bytes;
        for(uint32_t i=0;i<base.dims.z;i++) {
            check(n[i].material<1u<<29,@"Material must fit hit record");
            if(n[i].mask)check(uint64_t(n[i].children&0x7fffffffu)+__builtin_popcountll(n[i].mask)<=(n[i].children&0x80000000u?base.extra.w:base.dims.z),@"Child span");
        }
        struct Work{uint32_t id;float4 box;uint32_t depth;};std::vector<Work>todo{{0,base.root,0}};
        std::vector<B64>coarse;
        while(!todo.empty()) {
            auto w=todo.back();todo.pop_back();N64 q=n[w.id];
            if(!q.material&&!q.mask)continue;
            if(!q.mask||w.depth==3) {coarse.push_back({w.box,(float4){w.box.x+w.box.w,w.box.y+w.box.w,w.box.z+w.box.w,0}});continue;}
            uint64_t bits=q.mask;uint32_t rank=0;float s=w.box.w*.25f;
            while(bits){uint32_t b=__builtin_ctzll(bits);bits&=bits-1;
                float4 box={w.box.x+(b&3)*s,w.box.y+((b>>2)&3)*s,w.box.z+(b>>4)*s,s};
                if(q.children&0x80000000u)coarse.push_back({box,{box.x+s,box.y+s,box.z+s,0}});
                else todo.push_back({q.children+rank,box,w.depth+1});++rank;}
        }
        check(!coarse.empty()&&coarse.size()<65536,@"Coarse occupied-box cap");
        base.extra.x=(uint32_t)coarse.size();
        boxes=smallBuffer(dev,[NSData dataWithBytes:coarse.data() length:coarse.size()*sizeof(B64)],@"Conservative tile boxes");
        printf("Tree %s: %u nodes, %.1f MiB, %u coarse boxes\n",path.UTF8String,base.dims.z,double(raw.length)/(1<<20),base.extra.x);fflush(stdout);
    }
    uint32_t material(uint32_t i)const {return ((const uint16_t*)materials[i>>29].contents)[i&0x1fffffffu];}
    void bindMaterials(id<MTLComputeCommandEncoder>ce)const {for(unsigned i=0;i<3;i++)[ce setBuffer:materials[i] offset:0 atIndex:6+i];}
    void makePadded(id<MTLDevice>d) {
        padded=[d newBufferWithLength:raw.length*2 options:MTLResourceStorageModeShared];check(padded!=nil,@"Padded tree");
        memset(padded.contents,0,padded.length);
        for(NSUInteger i=0;i<raw.length/16;i++)memcpy((char*)padded.contents+32*i,(const char*)raw.bytes+16*i,16);
    }
};
static NSDictionary *summary64(const std::vector<double>&v) {
    check(!v.empty(),@"Timing samples");auto x=v;std::sort(x.begin(),x.end());NSMutableArray*a=[NSMutableArray array];for(double t:v)[a addObject:@(t)];
    return @{@"p99Ms":@(x[std::min(x.size()-1,size_t(ceil(.99*x.size())-1))]),@"maxMs":@(x.back()),
             @"medianMs":@(x[x.size()/2]),@"meanMs":@(std::accumulate(x.begin(),x.end(),0.)/x.size()),@"samples":a};
}
struct Cast64 {
    id<MTLDevice>dev;id<MTLCommandQueue>queue;id<MTLComputePipelineState>cast,tiles,near;
    id<MTLBuffer>hits,stats,mask,starts;Tree64 &tree;std::unique_ptr<Tree64>nearTree;
    P64 initial{};
    Cast64(id<MTLDevice>d,Tree64&t,NSString*shaderPath,NSString*snapshot,NSString*nearPath):dev(d),tree(t) {
        NSError*e=nil;NSString*src=[NSString stringWithContentsOfFile:shaderPath encoding:NSUTF8StringEncoding error:&e];check(src!=nil,e.description);
        MTLCompileOptions*opt=[MTLCompileOptions new];opt.languageVersion=MTLLanguageVersion3_2;opt.mathMode=MTLMathModeFast;
        id<MTLLibrary>lib=[dev newLibraryWithSource:src options:opt error:&e];check(lib!=nil,e.description);
        cast=[dev newComputePipelineStateWithFunction:[lib newFunctionWithName:@"cast"] error:&e];check(cast!=nil,e.description);
        tiles=[dev newComputePipelineStateWithFunction:[lib newFunctionWithName:@"tiles"] error:&e];check(tiles!=nil,e.description);
        near=[dev newComputePipelineStateWithFunction:[lib newFunctionWithName:@"nearCoverage"] error:&e];check(near!=nil,e.description);
        queue=[dev newCommandQueue];check(queue!=nil,@"RT64 queue");
        NSUInteger pixels=3024ull*1964;
        hits=[dev newBufferWithLength:pixels*8 options:MTLResourceStorageModeShared];
        stats=[dev newBufferWithLength:pixels*8 options:MTLResourceStorageModeShared];
        mask=[dev newBufferWithLength:pixels options:MTLResourceStorageModeShared];
        starts=[dev newBufferWithLength:((3024+7)/8)*((1964+7)/8)*4 options:MTLResourceStorageModePrivate];
        check(hits&&stats&&mask&&starts,@"RT64 outputs");memset(mask.contents,0,mask.length);
        initial=tree.base;
        NSData*g=smallGzip([snapshot stringByAppendingPathComponent:@"globals.bin.gz"],48),*m=smallGzip([snapshot stringByAppendingPathComponent:@"terrain.bin.gz"],80),*pr=smallGzip([snapshot stringByAppendingPathComponent:@"projection.bin.gz"],64);
        const int32_t*ip=(const int32_t*)g.bytes;const float*f=(const float*)g.bytes,*matrix=(const float*)m.bytes,*projection=(const float*)pr.bytes;
        initial.eye=(float4){float(ip[0])-f[4],float(ip[1])-f[5],float(ip[2])-f[6],0};
        initial.forward=(float4){-matrix[2],-matrix[6],-matrix[10],0};
        initial.right=(float4){matrix[0],matrix[4],matrix[8],0};initial.up=(float4){matrix[1],matrix[5],matrix[9],0};
        initial.lens=(float4){fabs(projection[5])*1964*.5f,0,projection[14],-projection[10]};
        if(nearPath.length)nearTree=std::make_unique<Tree64>(dev,nearPath);
    }
    P64 pose(const char*kind,int i,int count,uint32_t flags,float tau) {
        P64 p=initial;p.dims.w=flags;p.lens.y=tau;
        float frac=count>1?float(i)/(count-1):0;
        if(strcmp(kind,"turn")==0) {
            float angle=(frac-.5f)*float(M_PI);float c=cos(angle),s=sin(angle);
            for(float4* v:{&p.forward,&p.right,&p.up}){float x=v->x,z=v->z;v->x=c*x+s*z;v->z=-s*x+c*z;}
        } else if(strcmp(kind,"translation")==0)p.eye.x+=(frac-.5f)*32;
        return p;
    }
    uint64_t coverage(const P64&p,bool enabled) {
        if(!enabled||!nearTree){memset(mask.contents,0,mask.length);return 0;}
        P64 n=nearTree->base;n.eye=p.eye;n.forward=p.forward;n.right=p.right;n.up=p.up;n.lens=p.lens;n.lens.y=0;n.dims.w=2;
        id<MTLCommandBuffer>cb=[queue commandBuffer];auto ce=[cb computeCommandEncoder];[ce setComputePipelineState:near];
        [ce setBytes:&n length:sizeof(n) atIndex:0];[ce setBuffer:nearTree->compact offset:0 atIndex:1];[ce setBuffer:mask offset:0 atIndex:2];
        nearTree->bindMaterials(ce);
        [ce dispatchThreads:MTLSizeMake(3024,1964,1) threadsPerThreadgroup:MTLSizeMake(8,8,1)];[ce endEncoding];finish(cb);
        uint64_t nmasked=0;const uint8_t*b=(const uint8_t*)mask.contents;for(NSUInteger j=0;j<mask.length;j++)nmasked+=b[j]!=0;return nmasked;
    }
    double run(P64 p) {
        id<MTLCommandBuffer>cb=[queue commandBuffer];cb.label=@"RT64 far pass only";
        p.extra.y=(p.dims.w&8u)?2:1;
        if(p.dims.w&1u) {auto ce=[cb computeCommandEncoder];ce.label=@"Conservative8x8tile start";[ce setComputePipelineState:tiles];
            [ce setBytes:&p length:sizeof(p) atIndex:0];[ce setBuffer:(p.dims.w&8u)?tree.padded:tree.compact offset:0 atIndex:1];[ce setBuffer:starts offset:0 atIndex:2];
            [ce dispatchThreads:MTLSizeMake((3024+7)/8,(1964+7)/8,1) threadsPerThreadgroup:MTLSizeMake(8,8,1)];[ce endEncoding];}
        auto ce=[cb computeCommandEncoder];ce.label=@"Sparse64perpixel";[ce setComputePipelineState:cast];p.extra.y=(p.dims.w&8u)?2:1;
        [ce setBytes:&p length:sizeof(p) atIndex:0];[ce setBuffer:(p.dims.w&8u)?tree.padded:tree.compact offset:0 atIndex:1];
        [ce setBuffer:starts offset:0 atIndex:2];[ce setBuffer:mask offset:0 atIndex:3];[ce setBuffer:hits offset:0 atIndex:4];[ce setBuffer:stats offset:0 atIndex:5];
        tree.bindMaterials(ce);
        bool coherent=p.dims.w&4u;
        [ce dispatchThreadgroups:coherent?MTLSizeMake((3024+7)/8,(1964+7)/8,1):MTLSizeMake((3024+63)/64,1964,1) threadsPerThreadgroup:MTLSizeMake(64,1,1)];
        [ce endEncoding];return finish(cb);
    }
    NSDictionary *work(P64 p) {
        p.dims.w|=16;run(p);const uint32_t*s=(const uint32_t*)stats.contents,*h=(const uint32_t*)hits.contents;
        uint64_t steps=0,overflow=0,hit=0,unmasked=0;uint32_t maxSteps=0;
        for(NSUInteger j=0;j<mask.length;j++){steps+=s[2*j];overflow+=s[2*j+1];maxSteps=std::max(maxSteps,s[2*j]);hit+=(h[2*j+1]&0x1fffffffu)!=0;unmasked+=!((uint8_t*)mask.contents)[j];}
        check(!overflow,@"Traversal stack/iteration/index overflow: timing cannot qualify");
        check(hit>1000 && unmasked>1000,@"Far scene must produce nonempty unmasked hit workload");
        return @{@"stepsPerScreenPixel":@(double(steps)/mask.length),@"stepsPerUnmaskedPixel":@(double(steps)/std::max<uint64_t>(1,unmasked)),@"maxSteps":@(maxSteps),@"hitPixels":@(hit),@"maskedPixels":@(mask.length-unmasked),@"overflow":@(overflow)};
    }
};
static uint64_t cpuControl(Cast64 &test) {
    // An independent brute-force list of terminal boxes, deliberately limited
    // to the small synthetic fixture. No shader traversal code is shared.
    struct Leaf{float3 lo,hi;uint32_t mat;};std::vector<Leaf>leaves;
    struct W{uint32_t id;float4 box;};std::vector<W>pending{{0,test.tree.base.root}};
    const N64*n=(const N64*)test.tree.raw.bytes;
    while(!pending.empty()) {
        W w=pending.back();pending.pop_back();N64 q=n[w.id];
        if(!q.mask){if(q.material)leaves.push_back({w.box.xyz,w.box.xyz+w.box.w,q.material});continue;}
        uint64_t bits=q.mask;uint32_t rank=0;float s=w.box.w*.25f;
        while(bits){uint32_t b=__builtin_ctzll(bits);bits&=bits-1;float4 box={w.box.x+(b&3)*s,w.box.y+((b>>2)&3)*s,w.box.z+(b>>4)*s,s};
            if(q.children&0x80000000u)leaves.push_back({box.xyz,box.xyz+s,test.tree.material((q.children&0x7fffffffu)+rank)});
            else pending.push_back({q.children+rank,box});++rank;}
    }
    check(!leaves.empty()&&leaves.size()<4096,@"Control leaf limit");uint64_t checks=0,hits=0;
    for(int pose=0;pose<8;pose++) {
        P64 p=test.pose("turn",pose,8,6,0);memset(test.mask.contents,0,test.mask.length);test.run(p);
        const uint32_t*gpu=(const uint32_t*)test.hits.contents;
        for(int y=17;y<1964;y+=71)for(int x=23;x<3024;x+=79) {
            float3 dir=p.forward.xyz+((x+.5f-1512)/p.lens.x)*p.right.xyz-((y+.5f-982)/p.lens.x)*p.up.xyz;
            double best=INFINITY;uint32_t material=0;
            for(auto leaf:leaves) {
                double lo=0,hi=1e8;
                for(int k=0;k<3;k++) {
                    if(fabs(dir[k])<1e-20){if(p.eye[k]<leaf.lo[k]||p.eye[k]>=leaf.hi[k])hi=-1;continue;}
                    double a=(double(leaf.lo[k])-p.eye[k])/dir[k],b=(double(leaf.hi[k])-p.eye[k])/dir[k];lo=std::max(lo,std::min(a,b));hi=std::min(hi,std::max(a,b));
                }
                if(lo<hi&&lo<best){best=lo;material=leaf.mat;}
            }
            NSUInteger pos=y*3024+x;uint32_t actual=gpu[2*pos+1]&0x1fffffffu;
            check(actual==material,[NSString stringWithFormat:@"CPU first-hit mismatch pose%d pixel%d,%d expected%u got%u",pose,x,y,material,actual]);
            if(material){float depth;memcpy(&depth,gpu+2*pos,4);double expected=p.lens.w+p.lens.z/best;
                check(fabs(depth-expected)<std::max(1e-7,fabs(expected)*2e-5),@"CPU hit depth differs");hits++;}
            checks++;
        }
        NSData*reference=[NSData dataWithBytes:test.hits.contents length:test.hits.length];
        for(uint32_t flags:{7u,4u,2u}) {
            p.dims.w=flags;test.run(p);
            check(memcmp(reference.bytes,test.hits.contents,reference.length)==0,@"Synthetic tile/sky/order hit control");
        }
    }
    check(hits>100,@"Control must draw real hits");return checks;
}
int main(int argc,char**argv) {
    @autoreleasepool {
        check(argc>=6,@"rt64 TREE_PREFIX SNAPSHOT SHADER_FILE OUTPUT MODE[screen|full] [NEAR_PREFIX]");
        check(getenv("MC_GPU_LOCK_HELD")&&!strcmp(getenv("MC_GPU_LOCK_HELD"),"timing"),@"Exclusive timing lane required");
        alarm(900);id<MTLDevice>dev=MTLCreateSystemDefaultDevice();check(dev!=nil,@"Metal device");
        NSString*out=@(argv[4]);Tree64 tree(dev,@(argv[1]));
        Cast64 test(dev,tree,@(argv[3]),@(argv[2]),argc>6?@(argv[6]):nil);
        if(!strcmp(argv[5],"control")) {
            uint64_t n=cpuControl(test);writeJson(@{@"cpuBruteForceRays":@(n),@"passed":@YES},[out stringByAppendingPathComponent:@"control.json"]);
            printf("CPU/GPU first-hit control: %llu rays passed\n",(unsigned long long)n);return 0;
        }
        bool full=!strcmp(argv[5],"full");int samples=full?120:12;
        // Stop at2px only for an existing nonair stored representative. Air or
        // unavailable representatives require descent. Step2 image equivalence
        // is deliberately not claimed by this raw-hit cost screen.
        float tau=2.f;
        struct Variant{const char*name;uint32_t flags;bool masked;};
        std::vector<Variant>variants={{"tree64",6,true},{"tile-on",7,true},{"sky-off",4,true},{"row-order",2,true},{"unmasked",6,false}};
        if(full){tree.makePadded(dev);variants.push_back({"padded32",14,true});}
        NSMutableArray*results=[NSMutableArray array];NSMutableArray*controls=[NSMutableArray array];
        for(const char*path:{"standing","turn","translation"}) {
            std::vector<std::vector<double>> times(variants.size());NSMutableArray*activity=[NSMutableArray array];
            P64 p=test.pose(path,0,samples,6,tau);test.coverage(p,true);for(int w=0;w<3;w++)test.run(p);
            // Alternating order per pose. Mask construction and statistics are
            // outside spans; tile start, tracing and hit writes are inside.
            for(int f=0;f<samples;f++) {
                p=test.pose(path,f,samples,6,tau);uint64_t masked=test.coverage(p,true);
                NSData*savedMask=[NSData dataWithBytes:test.mask.contents length:test.mask.length];
                for(size_t j=0;j<variants.size();j++) {
                    size_t k=(f&1)?variants.size()-1-j:j;Variant v=variants[k];
                    memcpy(test.mask.contents,savedMask.bytes,savedMask.length);if(!v.masked)memset(test.mask.contents,0,test.mask.length);
                    p.dims.w=v.flags;times[k].push_back(test.run(p));
                }
                memcpy(test.mask.contents,savedMask.bytes,savedMask.length);
                if(f==0||f==samples/2||f==samples-1) {
                    p.dims.w=6;NSDictionary*stats=test.work(p);[activity addObject:@{@"pose":@(f),@"work":stats,@"nearMasked":@(masked)}];
                    NSData*ref=[NSData dataWithBytes:test.hits.contents length:test.hits.length];
                    for(uint32_t flags:{7u,4u,2u}) {
                        p.dims.w=flags;test.run(p);uint64_t bad=0;const uint64_t*a=(const uint64_t*)ref.bytes,*b=(const uint64_t*)test.hits.contents;
                        for(NSUInteger n=0;n<test.mask.length;n++)bad+=a[n]!=b[n];
                        [controls addObject:@{@"path":@(path),@"pose":@(f),@"flags":@(flags),@"unequalHitRecords":@(bad)}];
                        check(!bad,@"Optimization changes hit records");
                    }
                }
                if(f%20==0){printf("%s pose%d/%d masked%llu\n",path,f,samples,(unsigned long long)masked);fflush(stdout);}
            }
            for(size_t k=0;k<variants.size();k++)[results addObject:@{@"path":@(path),@"variant":@(variants[k].name),@"gpu":summary64(times[k]),@"workSamples":activity}];
            writeJson(@{@"device":dev.name,@"rows":results,@"hitControls":controls,@"tau":@(tau),@"samplesPerPath":@(samples),
                        @"treeBytes":@(tree.raw.length+uint64_t(tree.base.extra.w)*2),@"coarseBoxes":@(tree.base.extra.x),@"MetalAllocatedBytes":@(dev.currentAllocatedSize),
                        @"scope":@"Offscreen raw voxel hits; no shading, game FPS or visual gate. GUI-cleared captured depth replaced by voxel near-coverage proxy; unmasked control included. One GPU command per sample, tile cost included; counter pass untimed.",
                        @"nearProxy":@(test.nearTree!=nullptr)},[out stringByAppendingPathComponent:@"result.json"]);
        }
        puts("RT64 offscreen checkpoint complete");
    }return 0;
}
