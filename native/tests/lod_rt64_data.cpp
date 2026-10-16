// Offline LodData/LodStore converter. No game, GPU, cache writes or new generation.
#include "lod_rt64_data.h"
#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <iostream>
#include <list>
#include <map>
#include <memory>
#include <sstream>
#include <sys/stat.h>
#include <sys/mman.h>
#include <fcntl.h>
#include <unistd.h>
#include <unordered_map>
#include <zlib.h>

#if __BYTE_ORDER__ != __ORDER_LITTLE_ENDIAN__
#error "raw sparse64 writer requires little endian"
#endif
namespace lodrt64 {
namespace fs=std::filesystem;
inline int floorDiv(int x,int d) { int q=x/d; return q-(x%d<0); }
struct Reader {
    const std::vector<uint8_t>& b; size_t p=0;
    uint32_t u(unsigned n) { require(n<=4 && p+n<=b.size(),"truncated data"); uint32_t v=0; while(n--) v=(v<<8)|b[p++]; return v; }
    void skip(size_t n) { require(n<=b.size()-p,"truncated skip"); p+=n; }
    uint32_t var() { uint32_t v=0; for(unsigned i=0;i<5;i++) { auto c=u(1); require(i<4 || c<16,"varint overflow"); v|=(c&127)<<(7*i); if(!(c&128)) return v; } throw std::runtime_error("varint overflow"); }
};
struct Key {
    int level,x,z;
    bool operator==(const Key& k) const { return level==k.level && x==k.x && z==k.z; }
    bool operator<(const Key& k) const { return std::tie(level,z,x)<std::tie(k.level,k.z,k.x); }
};
struct Hash { size_t operator()(const Key& k) const { return (uint64_t(uint32_t(k.x))*0x9e3779b185ebca87ull) ^ (uint64_t(uint32_t(k.z))*0xc2b2ae3d27d4eb4full) ^ k.level; } };
struct Data {
    Key key; int minY,height,source=0; uint64_t nonair=0,trees=0;
    Pos lo{16,INT32_MAX,16}, hi{0,INT32_MIN,0};
    std::vector<uint32_t> material;
    uint32_t at(int x,int y,int z) const { return material[(y*16+z)*16+x]; }
};
struct Region {
    std::string file;
    std::array<uint64_t,1024> offset{};
    std::array<uint32_t,1024> length{};
    int64_t bytes=0,mtime=0;
};
struct Store {
    fs::path real,gen;
    std::array<std::string,8> dirs;
    std::vector<uint8_t> air,opaque;
    bool opaqueOnly=false;
    std::map<std::pair<int,Key>,Region> regions;
    struct Cached { std::shared_ptr<Data> value; std::list<Key>::iterator lru; };
    std::unordered_map<Key,Cached,Hash> cache;
    std::list<Key> lru;
    uint64_t decoded=0,rawBytes=0;
    static int64_t stamp(const struct stat& st) {
#ifdef __APPLE__
        return int64_t(st.st_mtimespec.tv_sec)*1000000000+st.st_mtimespec.tv_nsec;
#else
        return int64_t(st.st_mtim.tv_sec)*1000000000+st.st_mtim.tv_nsec;
#endif
    }
    Region& region(int source,Key k) {
        auto key=std::make_pair(source,k); auto it=regions.find(key);
        if(it!=regions.end()) return it->second;
        Region r; auto file=(source==1?real:gen)/dirs.at(k.level)/("r."+std::to_string(k.x)+"."+std::to_string(k.z)+".lod");
        if(fs::exists(file)) {
            struct stat st{}; require(!stat(file.c_str(),&st),"stat failed");
            r.file=file.string(); r.bytes=st.st_size; r.mtime=stamp(st);
            std::ifstream in(file,std::ios::binary); std::vector<uint8_t> header(4112);
            in.read(reinterpret_cast<char*>(header.data()),header.size()); require(bool(in),"region header truncated: "+r.file);
            Reader b{header}; require(b.u(4)==0x4c4f4431 && int(b.u(4))==k.level && int32_t(b.u(4))==k.x && int32_t(b.u(4))==k.z,"region identity: "+r.file);
            uint64_t off=header.size();
            for(unsigned i=0;i<1024;i++) { r.offset[i]=off; r.length[i]=b.u(4); require(r.length[i] <= (64u<<20),"region blob length cap"); off+=r.length[i]; }
            require(off==uint64_t(r.bytes),"region size mismatch: "+r.file);
        }
        return regions.emplace(key,std::move(r)).first->second;
    }
    std::shared_ptr<Data> decode(Key k,int source) {
        auto& r=region(source,{k.level,floorDiv(k.x,32),floorDiv(k.z,32)});
        int slot=(k.z-floorDiv(k.z,32)*32)*32+(k.x-floorDiv(k.x,32)*32);
        if(r.file.empty() || !r.length[slot]) return {};
        struct stat st{}; require(!stat(r.file.c_str(),&st) && st.st_size==r.bytes && stamp(st)==r.mtime,"store changed during export: "+r.file);
        std::ifstream in(r.file,std::ios::binary); in.seekg(r.offset[slot]);
        std::vector<uint8_t> blob(r.length[slot]); in.read(reinterpret_cast<char*>(blob.data()),blob.size()); require(bool(in),"truncated blob");
        Reader packed{blob}; auto rawLength=packed.u(4); require(rawLength && rawLength <= (64u<<20),"raw blob cap");
        std::vector<uint8_t> raw(rawLength); uLongf dest=raw.size();
        require(uncompress(raw.data(),&dest,blob.data()+4,blob.size()-4)==Z_OK && dest==rawLength,"bad zlib LodData");
        Reader b{raw}; unsigned version=b.u(1); require(version==1 || version==2,"LodData version");
        require(int(b.u(1))==k.level && int32_t(b.u(4))==k.x && int32_t(b.u(4))==k.z,"LodData identity");
        auto d=std::make_shared<Data>(); d->key=k; d->source=source;
        d->minY=int16_t(b.u(2)); d->height=int16_t(b.u(2)); require(d->height>0 && d->height<=4096,"LodData height cap");
        unsigned palette=b.u(2); for(unsigned i=0;i<palette;i++) { auto n=b.u(2); b.skip(n); }
        auto biomes=b.u(4); require(biomes<=16384,"biome count cap"); b.skip(uint64_t(biomes)*(palette>255?2:1));
        d->material.resize(256*d->height);
        for(int z=0;z<16;z++) for(int x=0;x<16;x++) {
            auto runs=b.var(); require(runs && runs<=unsigned(d->height),"bad run count"); int y=0;
            for(unsigned r=0;r<runs;r++) {
                auto id=b.var(); b.u(1); auto len=b.var();
                require(id<air.size() && len && len<=unsigned(d->height-y),"invalid state/run");
                uint32_t mat=(air[id] || (opaqueOnly && !opaque[id]))?0:id+1;
                for(unsigned j=0;j<len;j++) d->material[((y+j)*16+z)*16+x]=mat;
                if(mat) {
                    d->nonair+=len;
                    d->lo[0]=std::min(d->lo[0],x); d->lo[1]=std::min(d->lo[1],y+d->minY); d->lo[2]=std::min(d->lo[2],z);
                    d->hi[0]=std::max(d->hi[0],x+1); d->hi[1]=std::max(d->hi[1],y+int(len)+d->minY); d->hi[2]=std::max(d->hi[2],z+1);
                }
                y+=len;
            }
            require(y==d->height,"incomplete vertical runs");
        }
        if(version==2) { auto trees=b.var(); if(trees) { d->trees=trees-1; b.skip(d->trees*12); } }
        require(b.p==raw.size(),"trailing LodData bytes"); ++decoded; rawBytes+=rawLength;
        return d;
    }
    std::shared_ptr<Data> get(Key k) {
        auto it=cache.find(k);
        if(it!=cache.end()) { lru.splice(lru.begin(),lru,it->second.lru); return it->second.value; }
        auto d=decode(k,1); if(!d) d=decode(k,2);
        lru.push_front(k); cache.emplace(k,Cached{d,lru.begin()});
        while(cache.size()>256) { cache.erase(lru.back()); lru.pop_back(); }
        return d;
    }
};
struct Counts {
    uint64_t requested=0,recovered=0,real=0,generated=0,missing=0,cells=0,nonair=0,blocks=0,trees=0;
};
// Only a small write buffer is resident while building. mmap is read-only and
// used for the final structural walk; it does not copy the multi-GB tree.
template<class Record> struct RecordSink {
    std::string path; std::ofstream output; uint64_t count=0; const Record* mapped=nullptr;
    bool countOnly;
    RecordSink(std::string p,bool dry):path(std::move(p)),countOnly(dry) {
        if(!dry) { output.open(path,std::ios::binary); require(bool(output),"cannot open node spool"); }
    }
    ~RecordSink() { if(mapped) munmap(const_cast<Record*>(mapped),count*sizeof(Record)); }
    uint64_t size() const { return count; }
    bool empty() const { return !count; }
    const Record& operator[](size_t i) const { return mapped[i]; }
    void push_back(Record n) { if(!countOnly) { output.write(reinterpret_cast<const char*>(&n),sizeof(Record)); require(bool(output),"node spool write failed"); } ++count; }
    void complete(Record root,bool overwrite=true) {
        if(countOnly) return;
        if(overwrite) { output.seekp(0); output.write(reinterpret_cast<const char*>(&root),sizeof(Record)); }
        output.close(); require(bool(output),"node spool finalization failed");
        if(!count) return;
        int fd=open(path.c_str(),O_RDONLY); require(fd>=0,"cannot map node spool");
        void* p=mmap(nullptr,count*sizeof(Record),PROT_READ,MAP_PRIVATE,fd,0); close(fd); require(p!=MAP_FAILED,"node spool mmap failed"); mapped=static_cast<const Record*>(p);
    }
};
struct BuildTree { Pos origin{}; uint32_t size=1; RecordSink<Node> nodes; RecordSink<uint32_t> materials; BuildTree(std::string p,bool dry):nodes(p+".bin.tmp",dry),materials(p+"-materials.bin.tmp",dry) {} };
struct Builder {
    Store& store; uint64_t maxNodes;
    BuildTree tree;
    Counts total; std::array<Counts,8> levels{};
    uint64_t repReal=0,repGen=0,repAir=0,repMissing=0,repOutside=0,repAbove=0;
    Pos requestedMin{INT32_MAX,INT32_MAX,INT32_MAX}, requestedMax{INT32_MIN,INT32_MIN,INT32_MIN};
    Pos occupiedMin{INT32_MAX,INT32_MAX,INT32_MAX}, occupiedMax{INT32_MIN,INT32_MIN,INT32_MIN};
    struct Item { Pos p; Node node; };
    std::array<std::vector<Item>,16> buckets;
    Builder(Store& s,uint64_t cap,std::string spool,bool dry=false):store(s),maxNodes(cap),tree(std::move(spool),dry) { tree.nodes.push_back({}); }
    uint32_t representative(Pos p,uint32_t size) {
        unsigned level=__builtin_ctz(size);
        if(level>7) { ++repAbove; return 0; }
        require(p[0]%int(size)==0 && p[1]%int(size)==0 && p[2]%int(size)==0,"misaligned representative");
        auto d=store.get({int(level),floorDiv(p[0],16*size),floorDiv(p[2],16*size)});
        if(!d) { ++repMissing; return 0; }
        int y=floorDiv(p[1],size)-d->minY;
        if(y<0 || y>=d->height) { ++repOutside; return 0; }
        if(d->source==1) ++repReal; else ++repGen;
        auto mat=d->at(floorDiv(p[0],size)-d->key.x*16,y,floorDiv(p[2],size)-d->key.z*16);
        if(!mat) ++repAir;
        return mat;
    }
    Node parent(const std::array<Node,64>& children,Pos p,uint32_t size) {
        uint64_t mask=0; unsigned n=0; bool uniform=true,allTerminal=true; uint32_t material=children[0].material;
        for(unsigned i=0;i<64;i++) {
            const auto& c=children[i];
            if(c.mask || c.material) { mask|=uint64_t(1)<<i; ++n; }
            if(c.mask || c.material!=material) uniform=false;
            if(c.mask) allTerminal=false;
        }
        if(!n) return {};
        if(uniform) return Node{0,0,material};
        if(allTerminal) {
            require(tree.materials.size()+n<=IndexMask,"leaf material index cap exceeded");
            Node out{mask,uint32_t(tree.materials.size())|LeafFlag,representative(p,size)};
            for(unsigned i=0;i<64;i++) if(mask & (uint64_t(1)<<i)) tree.materials.push_back(children[i].material);
            return out;
        }
        require(tree.nodes.size()+n<=maxNodes && tree.nodes.size()+n<=IndexMask,"node cap exceeded; no partial tree published");
        Node out{mask,uint32_t(tree.nodes.size()),representative(p,size)};
        for(unsigned i=0;i<64;i++) if(mask & (uint64_t(1)<<i)) tree.nodes.push_back(children[i]);
        return out;
    }
    Node tile(const Data& d,Pos p,unsigned size) {
        int cell=1<<d.key.level;
        if(size<=unsigned(cell)) {
            int x=floorDiv(p[0],cell)-d.key.x*16, y=floorDiv(p[1],cell)-d.minY, z=floorDiv(p[2],cell)-d.key.z*16;
            if(x<0||x>=16||y<0||y>=d.height||z<0||z>=16) return {};
            return Node{0,0,d.at(x,y,z)};
        }
        // Most of the world is uniform buried filler or air. Scan the source
        // subcube before recursion so these volumes never allocate leaf nodes.
        int x0=floorDiv(p[0],cell)-d.key.x*16, z0=floorDiv(p[2],cell)-d.key.z*16;
        int y0=floorDiv(p[1],cell)-d.minY, width=size/cell;
        bool uniform=y0>=0 && y0+width<=d.height;
        uint32_t mat=uniform?d.at(x0,y0,z0):0;
        for(int y=y0;uniform && y<y0+width;y++) for(int z=z0;uniform && z<z0+width;z++)
            for(int x=x0;x<x0+width;x++) if(d.at(x,y,z)!=mat) { uniform=false; break; }
        if(uniform) return Node{0,0,mat};
        if(y0>=d.height || y0+width<=0) return {};
        std::array<Node,64> children{}; unsigned cs=size/4;
        for(unsigned b=0;b<64;b++) children[b]=tile(d,{int32_t(p[0]+(b&3)*cs),int32_t(p[1]+((b>>2)&3)*cs),int32_t(p[2]+(b>>4)*cs)},cs);
        return parent(children,p,size);
    }
    void add(Key k,std::ostream& missing) {
        auto& c=levels.at(k.level); ++c.requested;
        int cell=1<<k.level, span=16*cell;
        requestedMin[0]=std::min(requestedMin[0],k.x*span); requestedMin[2]=std::min(requestedMin[2],k.z*span);
        requestedMax[0]=std::max(requestedMax[0],(k.x+1)*span); requestedMax[2]=std::max(requestedMax[2],(k.z+1)*span);
        auto d=store.get(k);
        if(!d) { ++c.missing; missing<<k.level<<'\t'<<k.x<<'\t'<<k.z<<"\tabsent_in_matching_real_and_seedgen_store\n"; return; }
        ++c.recovered; if(d->source==1) ++c.real; else ++c.generated;
        c.cells+=d->material.size(); c.nonair+=d->nonair; c.blocks+=d->nonair*uint64_t(cell)*cell*cell; c.trees+=d->trees;
        requestedMin[1]=std::min(requestedMin[1],d->minY*cell); requestedMax[1]=std::max(requestedMax[1],(d->minY+d->height)*cell);
        if(d->nonair) for(int a=0;a<3;a++) {
            int offset=a==0?k.x*span:a==2?k.z*span:0;
            occupiedMin[a]=std::min(occupiedMin[a],offset+d->lo[a]*cell);
            occupiedMax[a]=std::max(occupiedMax[a],offset+d->hi[a]*cell);
        }
        unsigned size=(k.level&1)?span/2:span, depth=__builtin_ctz(size)/2;
        for(int y=floorDiv(d->minY*cell,size)*int(size);y<(d->minY+d->height)*cell;y+=size)
            for(int z=k.z*span;z<(k.z+1)*span;z+=size) for(int x=k.x*span;x<(k.x+1)*span;x+=size) {
                Pos p{x,y,z}; Node root=tile(*d,p,size); if(root.mask || root.material) buckets.at(depth).push_back({p,root});
            }
    }
    Validation finish() {
        require(requestedMin[1]!=INT32_MAX,"no recovered source columns");
        // 256 alignment keeps every representable stored coarse cell aligned,
        // while permitting a root cube to span negative and positive world axes.
        int alignment=256;
        for(unsigned i=0;i<buckets.size();i++) if(!buckets[i].empty()) alignment=std::max(alignment,1<<int(2*i));
        for(int a=0;a<3;a++) tree.origin[a]=floorDiv(requestedMin[a],alignment)*alignment;
        tree.size=1;
        for(int a=0;a<3;a++) while(int64_t(tree.origin[a])+tree.size<requestedMax[a]) {
            require(tree.size<(1u<<28),"root extent cap"); tree.size*=4;
        }
        unsigned rootDepth=__builtin_ctz(tree.size)/2;
        for(unsigned depth=0;depth<rootDepth;depth++) {
            auto& items=buckets.at(depth); if(items.empty()) continue;
            int parentSize=1<<(2*(depth+1)), size=parentSize/4;
            auto parentPos=[&](Pos p) { for(int a=0;a<3;a++) p[a]=tree.origin[a]+floorDiv(p[a]-tree.origin[a],parentSize)*parentSize; return p; };
            std::sort(items.begin(),items.end(),[&](const Item& a,const Item& b) { auto pa=parentPos(a.p),pb=parentPos(b.p); return pa<pb || (pa==pb && a.p<b.p); });
            for(size_t i=0;i<items.size();) {
                Pos p=parentPos(items[i].p); std::array<Node,64> children{}; uint64_t used=0;
                do {
                    auto& item=items[i]; unsigned bit=(item.p[0]-p[0])/size+4*((item.p[1]-p[1])/size)+16*((item.p[2]-p[2])/size);
                    require(bit<64 && !(used & (uint64_t(1)<<bit)),"overlapping source tiles"); used|=uint64_t(1)<<bit; children[bit]=item.node; ++i;
                } while(i<items.size() && parentPos(items[i].p)==p);
                buckets.at(depth+1).push_back({p,parent(children,p,parentSize)});
            }
            std::vector<Item>().swap(items);
        }
        auto& roots=buckets.at(rootDepth);
        require(roots.size()<=1,"multiple roots");
        Node root{};
        if(!roots.empty()) { require(roots[0].p==tree.origin,"root origin mismatch"); root=roots[0].node; }
        tree.nodes.complete(root);
        tree.materials.complete(0,false);
        for(const auto& c:levels) { total.requested+=c.requested; total.recovered+=c.recovered; total.real+=c.real; total.generated+=c.generated; total.missing+=c.missing; total.cells+=c.cells; total.nonair+=c.nonair; total.blocks+=c.blocks; total.trees+=c.trees; }
        Validation v;
        if(tree.nodes.countOnly) { v.occupiedBlocks=total.blocks; v.boundsMin=occupiedMin; v.boundsMax=occupiedMax; return v; }
        v=validate(tree);
        require(v.occupiedBlocks==total.blocks,"tree/source occupied volume differs");
        require(!v.occupiedBlocks || (v.boundsMin==occupiedMin && v.boundsMax==occupiedMax),"tree/source bounds differ");
        return v;
    }
};
inline std::string posJson(Pos p) { return "["+std::to_string(p[0])+","+std::to_string(p[1])+","+std::to_string(p[2])+"]"; }
inline void countsJson(std::ostream& o,const Counts& c) {
    o<<"{\"requested\":"<<c.requested<<",\"recovered\":"<<c.recovered<<",\"real\":"<<c.real<<",\"generated\":"<<c.generated<<",\"missing\":"<<c.missing<<",\"cells\":"<<c.cells<<",\"nonemptyCells\":"<<c.nonair<<",\"occupiedWorldBlocks\":"<<c.blocks<<",\"storedTreeRecordsNotExpanded\":"<<c.trees<<"}";
}
} // namespace lodrt64

#ifndef LOD_RT64_NO_MAIN
int main(int argc,char** argv) {
    using namespace lodrt64;
    try {
        require(argc>=8 && argc<=10,"usage: rt64-builder realStore genStore config.tsv registry.tsv columns.tsv outputPrefix maxNodes [--count-only] [--opaque-only]");
        bool countOnly=false,opaqueOnly=false;
        for(int i=8;i<argc;i++) { std::string flag=argv[i]; require(flag=="--count-only" || flag=="--opaque-only","unknown flag"); if(flag=="--count-only") countOnly=true; else opaqueOnly=true; }
        auto start=std::chrono::steady_clock::now(); Store store; store.real=argv[1]; store.gen=argv[2]; store.opaqueOnly=opaqueOnly;
        std::ifstream conf(argv[3]); std::string key,value; while(conf>>key>>value) if(key!="generator") store.dirs.at(std::stoi(key))=value;
        for(const auto& dir:store.dirs) require(!dir.empty(),"missing level directory");
        std::ifstream registry(argv[4]); std::string line; while(std::getline(registry,line)) {
            std::istringstream in(line); unsigned id,air,opaque; require(bool(in>>id>>air>>opaque) && id==store.air.size() && air<=1 && opaque<=1,"registry IDs/air/opacity invalid"); store.air.push_back(air); store.opaque.push_back(opaque);
        }
        require(!store.air.empty(),"missing registry");
        std::ifstream input(argv[5]); require(bool(input),"missing column selection");
        std::vector<Key> selected; Key k; while(input>>k.level>>k.x>>k.z) { require(k.level>=0&&k.level<=7 && std::abs(int64_t(k.x))<1000000 && std::abs(int64_t(k.z))<1000000,"selection bounds"); selected.push_back(k); }
        require(input.eof() && !selected.empty() && selected.size()<=262144,"bad column selection"); std::sort(selected.begin(),selected.end());
        for(size_t i=1;i<selected.size();i++) require(!(selected[i]==selected[i-1]),"duplicate column");
        for(auto a:selected) for(int p=a.level+1;p<8;p++) require(!std::binary_search(selected.begin(),selected.end(),Key{p,floorDiv(a.x,1<<(p-a.level)),floorDiv(a.z,1<<(p-a.level))}),"overlapping column selection");
        std::string prefix=argv[6]; Builder b(store,std::stoull(argv[7]),prefix,countOnly); std::ofstream missing(prefix+"-missing.tsv");
        for(size_t i=0;i<selected.size();i++) { b.add(selected[i],missing); if((i+1)%5000==0) std::cerr<<"columns "<<i+1<<"/"<<selected.size()<<" nodes "<<b.tree.nodes.size()<<"\n"; }
        auto v=b.finish();
        std::ofstream sources(prefix+"-files.tsv");
        for(const auto& item:store.regions) if(!item.second.file.empty()) {
            auto& r=item.second; struct stat st{}; require(!stat(r.file.c_str(),&st) && st.st_size==r.bytes && Store::stamp(st)==r.mtime,"store changed before completion: "+r.file);
            sources<<r.file<<'\t'<<r.bytes<<'\t'<<r.mtime<<'\n';
        }
        auto nodeBytes=b.tree.nodes.size()*sizeof(Node),leafBytes=b.tree.materials.size()*4,bytes=nodeBytes+leafBytes;
        if(!countOnly) { fs::rename(prefix+".bin.tmp",prefix+".bin"); fs::rename(prefix+"-materials.bin.tmp",prefix+"-materials.bin"); }
        std::ofstream out(prefix+".json");
        out<<"{\n\"origin\":"<<posJson(b.tree.origin)<<",\"size\":"<<b.tree.size<<",\"nodeCount\":"<<b.tree.nodes.size()<<",\"bytes\":"<<bytes
           <<",\"boundsMin\":"<<posJson(v.occupiedBlocks?v.boundsMin:b.tree.origin)<<",\"boundsMax\":"<<posJson(v.occupiedBlocks?v.boundsMax:b.tree.origin)
           <<",\"sourceBoundsMin\":"<<posJson(b.requestedMin)<<",\"sourceBoundsMax\":"<<posJson(b.requestedMax)<<",\"coverage\":"; countsJson(out,b.total);
        out<<",\"levels\":["; for(unsigned i=0;i<8;i++) { if(i) out<<','; countsJson(out,b.levels[i]); }
        out<<"],\"representatives\":{\"realStore\":"<<b.repReal<<",\"generatedStore\":"<<b.repGen<<",\"storedAir\":"<<b.repAir<<",\"missingColumn\":"<<b.repMissing<<",\"outsideStoredHeight\":"<<b.repOutside<<",\"aboveStoredLevels\":"<<b.repAbove<<"}"
           <<",\"nodeBytes\":"<<nodeBytes<<",\"leafMaterialCount\":"<<b.tree.materials.size()<<",\"leafMaterialBytes\":"<<leafBytes
           <<",\"compactLeaves\":true,\"opaqueOnly\":"<<(opaqueOnly?"true":"false")<<",\"countOnly\":"<<(countOnly?"true":"false")
           <<",\"validation\":{\"structuralWalkPerformed\":"<<(countOnly?"false":"true")<<",\"terminals\":"<<v.terminals<<",\"interiors\":"<<v.interiors<<"}"
           <<",\"decodedBlobsIncludingRepresentativeCacheReloads\":"<<store.decoded<<",\"decodedRawBytes\":"<<store.rawBytes
           <<",\"cpuBuildSeconds\":"<<std::chrono::duration<double>(std::chrono::steady_clock::now()-start).count()<<"}\n";
        require(bool(out),"metadata write failed");
        std::cerr<<(countOnly?"counted ":"wrote ")<<prefix<<".bin: "<<b.tree.nodes.size()<<" nodes, "<<bytes<<" bytes\n";
        return 0;
    } catch(const std::exception& e) { std::cerr<<"rt64: "<<e.what()<<'\n'; return 1; }
}
#endif
