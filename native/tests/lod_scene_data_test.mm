// Retained 18-case loader harness. Run: python3 tools/lod/scene-data-test.py
// Exact tested native logic; only the header include path changed on promotion.
#include "lod_scene_data.h"
#include <sys/resource.h>
#include <signal.h>
#include <cstdio>
using namespace lodscene;
struct Digest {
    CC_SHA256_CTX ctx;
    Digest() { CC_SHA256_Init(&ctx); }
    void bytes(const void *p,uint64_t n) {
        auto *b=static_cast<const uint8_t *>(p);
        while(n) { uint32_t k=uint32_t(std::min<uint64_t>(n,1<<20)); CC_SHA256_Update(&ctx,b,k); b+=k;n-=k; }
    }
    void u32(uint32_t n) { uint8_t b[]={uint8_t(n>>24),uint8_t(n>>16),uint8_t(n>>8),uint8_t(n)};bytes(b,4); }
    void u64(uint64_t n) {u32(uint32_t(n>>32));u32(uint32_t(n));}
    NSString *finish() { unsigned char h[32];CC_SHA256_Final(h,&ctx);char out[65];for(int i=0;i<32;i++)std::snprintf(out+2*i,3,"%02x",h[i]);return [NSString stringWithUTF8String:out]; }
};
static NSDictionary *reconstruct(const Scene &s) {
    Digest lds;lds.u32(0x4c445331);lds.u32(uint32_t(s.sections.size()));
    for(const auto &sec:s.sections) {
        lds.u32(sec.slot);for(auto n:sec.origin)lds.u32(uint32_t(n));
        lds.bytes(static_cast<const uint8_t *>(s.sectionInfos.contents)+16ull*sec.slot,16);
        lds.bytes(static_cast<const uint8_t *>(s.sectionTable.contents)+32ull*sec.slot,32);
        for(const auto &sp:sec.layers) {
            lds.u32(uint32_t(sp.arena));lds.u32(sp.firstQuad);lds.u32(sp.quadCount);lds.u32(sp.stride);lds.u64(uint64_t(sp.quadCount)*sp.stride);
            if(sp.arena>=0)lds.bytes(static_cast<const uint8_t *>(s.arenas[sp.arena].buffer.contents)+uint64_t(sp.firstQuad)*sp.stride,uint64_t(sp.quadCount)*sp.stride);
        }
    }
    Digest ldg;ldg.u32(0x4c444731);ldg.u32(uint32_t(s.groups.size()));
    std::array<uint8_t,65536> encoded{};
    for(const auto &g:s.groups) {
        ldg.u32(g.layer);ldg.u32(g.arena);ldg.u32(g.stride);ldg.u32(g.fade);ldg.u32(uint32_t(g.level));ldg.u32(g.clusterCount);ldg.u64(g.submittedQuads);
        auto *v=static_cast<const uint32_t *>(g.clusters.contents);size_t left=size_t(g.clusterCount)*4;
        while(left) {size_t n=std::min(left,encoded.size()/4);for(size_t i=0;i<n;i++){uint32_t x=v[i];encoded[4*i]=x>>24;encoded[4*i+1]=x>>16;encoded[4*i+2]=x>>8;encoded[4*i+3]=x;}ldg.bytes(encoded.data(),4*n);left-=n;v+=n;}
    }
    Digest ldv;ldv.u32(0x4c445631);ldv.u32(uint32_t(s.draws.size()));
    for(const auto &d:s.draws) {
        ldv.u32(d.groupOrdinal);ldv.u32(d.layerCode);ldv.u32(d.quadCount);ldv.u32(d.usedClusterCount);
        ldv.bytes(d.argsBuffer.contents,20);ldv.bytes(d.quadBuffer.contents,4ull*d.quadCount);ldv.bytes(d.itemBuffer.contents,8ull*d.usedClusterCount);
    }
    NSDictionary *hashes=@{@"sections.lds.gz":lds.finish(),@"groups.ldg.gz":ldg.finish(),@"visible.ldv.gz":ldv.finish()};
    for(NSString *name in hashes)detail::check([hashes[name] isEqual:s.manifest[@"files"][name][@"rawSha256"]],"loaded-buffer reconstruction hash mismatch: "+detail::text(name));
    return hashes;
}
static NSDictionary *loadTest(id<MTLDevice> device, NSString *path, NSString *reject, bool real) {
    Scene s;s.stats.submittedQuads=123456789;std::string error;
    double start=NSProcessInfo.processInfo.systemUptime;
    bool ok=load(device,path,s,error);
    if(reject.length) {
        detail::check(!ok && error.find(detail::text(reject))!=std::string::npos,"unexpected rejection for "+detail::text(path)+": "+error);
        detail::check(s.stats.submittedQuads==123456789 && s.arenas.empty() && !s.sectionInfos,"load failure mutated output");
        return @{@"case":path.lastPathComponent,@"status":@"rejected-as-expected",@"error":[NSString stringWithUTF8String:error.c_str()]};
    }
    detail::check(ok,"load failed: "+error);detail::check(s.stats.validated,"not validated");
    NSDictionary *hashes=reconstruct(s);
    if(!real) {
        detail::check(s.sections.size()==3 && s.groups.size()==5 && s.draws.size()==5 && s.stats.visibleQuads==5,"tiny counts");
        const auto &sp=s.sections[0].layers[0];
        detail::check(sp.firstQuad==8 && s.arenas[0].buffer.length==ArenaBytes,"tiny original arena offsets/length");
        const uint32_t *words=static_cast<const uint32_t *>(s.arenas[0].buffer.contents);
        detail::check(words[8*48/4]==3 && words[0]==0,"tiny original source placement/zero hole");
        detail::check(s.sectionIndex[MaxSections-1]==2 && s.sectionIndex[4]==-1,"original max/absent slots");
        auto *info=static_cast<const int32_t *>(s.sectionInfos.contents);
        detail::check(info[3*4]==-16 && info[(MaxSections-1)*4]==-256,"negative origins/original slots");
    }
    struct rusage usage{};getrusage(RUSAGE_SELF,&usage);
    return @{@"case":path.lastPathComponent,@"status":@"passed",@"loadAndRehashSeconds":@(NSProcessInfo.processInfo.systemUptime-start),
             @"selectedSections":@(s.stats.selectedSections),@"groups":@(s.groups.size()),@"draws":@(s.draws.size()),
             @"sourceQuads":@(s.stats.payloadQuads),@"submittedClusters":@(s.stats.submittedClusters),@"visibleQuads":@(s.stats.visibleQuads),
             @"arenas":@(s.stats.allocatedArenas),@"metalBytes":@(s.stats.metalBytes),@"requestedBufferBytes":@(s.stats.requestedBufferBytes),
             @"peakDeviceBytes":@(s.stats.peakDeviceBytes),@"processPeakRssBytes":@(usage.ru_maxrss),
             @"verifiedRawBytes":@(s.stats.verifiedRawBytes),@"verifiedGzipBytes":@(s.stats.verifiedGzipBytes),@"verifiedFiles":@(s.stats.verifiedFiles),
             @"loadedBufferReconstructionSha256":hashes};
}
int main(int argc,char **argv) {@autoreleasepool {
    alarm(120);
    if(argc!=4){std::fprintf(stderr,"usage: loader-test fixtures capture report.json\n");return 2;}
    try {
        id<MTLDevice> device=MTLCreateSystemDefaultDevice();detail::check(device!=nil,"no Metal device");
        NSString *root=[NSString stringWithUTF8String:argv[1]];
        NSData *data=[NSData dataWithContentsOfFile:[root stringByAppendingPathComponent:@"cases.json"]];
        NSArray *cases=[NSJSONSerialization JSONObjectWithData:data options:0 error:nil];detail::check(cases!=nil,"missing cases.json");
        NSMutableArray *results=[NSMutableArray array];
        for(NSDictionary *c in cases) {@autoreleasepool {
            NSDictionary *r=loadTest(device,[root stringByAppendingPathComponent:c[@"case"]],c[@"reject"],false);[results addObject:r];
            std::fprintf(stderr,"%s: %s\n",[c[@"case"] UTF8String],[r[@"status"] UTF8String]);
        }}
        NSDictionary *full=std::strcmp(argv[2],"-")==0 ? @{@"status":@"skipped-parent-validates-full-capture"} : loadTest(device,[NSString stringWithUTF8String:argv[2]],@"",true);
        NSDictionary *report=@{@"status":@"passed",@"nativeCases":results,@"capture":full,@"device":device.name,@"gpuCommandsSubmitted":@0};
        NSData *json=[NSJSONSerialization dataWithJSONObject:report options:NSJSONWritingPrettyPrinted|NSJSONWritingSortedKeys error:nil];
        detail::check([json writeToFile:[NSString stringWithUTF8String:argv[3]] atomically:YES],"report write failed");
        std::fwrite(json.bytes,1,json.length,stdout);std::fputc('\n',stdout);return 0;
    } catch(const std::exception &e){std::fprintf(stderr,"ERROR: %s\n",e.what());return 1;}
}}
