// Bounded, CPU-only LRT1 -> BFC1 face-brick codec and exhaustive decoder audit.
// macOS: clang++ -O2 -std=c++17 tools/lod/brick-face-audit.cc -o build/lod-brick-faces-N
// Run --self-test OUTDIR, or under gpulock functional: INPUT.bin INPUT.json OUTDIR.
// OUTDIR must be a NEW build/lod-brick-faces-* directory. No GPU/client dependencies.
#include <CommonCrypto/CommonDigest.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <limits>
#include <numeric>
#include <set>
#include <sstream>
#include <stdexcept>
#include <string>
#include <sys/resource.h>
#include <tuple>
#include <vector>

namespace fs = std::filesystem;
using U = uint64_t;
constexpr uint32_t MAX_QUADS = 2000000;
constexpr U MAX_FACES = 8000000, MAX_QUERY_WORK = 64000000;
constexpr uint32_t MAX_AREA = 4096;
constexpr int LIMIT = (1 << 23) - 1; // unit coordinates stay exactly float32 representable
using Quad = std::array<std::array<float, 4>, 4>;
using SHA = std::array<uint8_t, 32>;
static_assert(sizeof(Quad) == 64);
void need(bool b, const std::string& why) { if (!b) throw std::runtime_error(why); }
SHA sha(const void* p, size_t n) {
    need(n <= UINT32_MAX, "SHA buffer too large");
    SHA out{}; CC_SHA256(p, CC_LONG(n), out.data()); return out;
}
std::string hex(const SHA& s) {
    std::ostringstream o; for (auto c : s) o << std::hex << std::setw(2) << std::setfill('0') << int(c);
    return o.str();
}
SHA file_sha(const fs::path& p) {
    CC_SHA256_CTX ctx; CC_SHA256_Init(&ctx);
    std::ifstream in(p, std::ios::binary); need(bool(in), "hash open failed");
    std::array<char, 65536> b{};
    while (in.read(b.data(), b.size()) || in.gcount()) CC_SHA256_Update(&ctx, b.data(), CC_LONG(in.gcount()));
    need(in.eof(), "hash read failed"); SHA out{}; CC_SHA256_Final(out.data(), &ctx); return out;
}
void put(std::ostream& o, U v, int bytes) { for (int i = 0; i < bytes; ++i) o.put(char(v >> (8*i))); }
U get(std::istream& in, int bytes) {
    U v = 0; for (int i=0; i<bytes; ++i) { int c=in.get(); need(c != EOF, "truncated codec"); v |= U(c) << (8*i); } return v;
}
int32_t geti(std::istream& in) { uint32_t u=uint32_t(get(in,4)); int32_t i; std::memcpy(&i,&u,4); return i; }
enum Reason : uint8_t { OK, NONFINITE, NOT_RECT, FRACTIONAL, RANGE, AREA_CAP, TOTAL_CAP, CONFLICT, FALLBACK_TOUCH, QUERY_CAP, REASON_COUNT };
const char* reason_names[] = {"retained", "nonfinite_or_nonunit_w", "nonrectangle_or_winding", "fractional_rectangle", "coordinate_range", "per_source_expansion_cap", "total_expansion_cap", "duplicate_overlap_or_opposing_face", "conservative_fallback_contact", "query_budget_global_fallback"};
// 32-byte source-local mapping, storing q0 and its ordered q0->q1/q0->q3 edges.
// axes = axisA | axisB<<8; q2 = q0 + edgeA + edgeB. IDs are immutable fixture IDs.
struct Map { uint32_t id; int32_t o[3], ea, eb; uint32_t axes, reserved; };
static_assert(sizeof(Map)==32);
// Internal 20-byte face: canonical (plane,u,v,source,axis*2+positive), then
// in-place encoded (brickX,brickY,brickZ,source,slot). Never volume occupancy.
struct Face { int32_t x,y,z; uint32_t id,tag; };
static_assert(sizeof(Face)==20);
struct Limits { uint32_t area=MAX_AREA; U faces=MAX_FACES, query=MAX_QUERY_WORK; };
struct Audit {
    std::vector<Quad> q;
    std::vector<Map> maps;
    std::vector<uint8_t> reason;
    std::vector<Face> faces;
    SHA input_sha{};
    U integral=0, fractional=0, expanded=0, conflict_groups=0, opposing_groups=0, query_work=0;
    bool query_exhausted=false;
    Limits limits;
};
int axis(const Map& m) { return 3-int(m.axes&255)-int((m.axes>>8)&255); }
int positive(const Map& m) {
    int a=m.axes&255,b=(m.axes>>8)&255;
    int parity=((a+1)%3==b)?1:-1;
    return parity * (m.ea>0?1:-1) * (m.eb>0?1:-1) > 0;
}
Quad reconstruct(const Map& m) {
    Quad q{}; for (auto& p:q) { for (int j=0;j<3;++j) p[j]=float(m.o[j]); p[3]=1; }
    int a=m.axes&255,b=(m.axes>>8)&255;
    q[1][a]+=float(m.ea); q[2][a]+=float(m.ea); q[2][b]+=float(m.eb); q[3][b]+=float(m.eb);
    return q;
}
// No epsilon snapping: all original float32 corner values must match exactly.
Reason classify(const Quad& q, uint32_t id, Map& m, bool& integral, bool& fractional) {
    for (auto p:q) { for (float x:p) if (!std::isfinite(x)) return NONFINITE; if (p[3]!=1) return NONFINITE; }
    int a=-1,b=-1;
    for (int j=0;j<3;++j) {
        if (q[1][j]!=q[0][j]) { if (a!=-1) return NOT_RECT; a=j; }
        if (q[3][j]!=q[0][j]) { if (b!=-1) return NOT_RECT; b=j; }
        if (double(q[2][j])!=double(q[1][j])+double(q[3][j])-double(q[0][j])) return NOT_RECT;
    }
    if (a<0 || b<0 || a==b) return NOT_RECT;
    for (auto p:q) for (int j=0;j<3;++j) if (std::trunc(p[j])!=p[j]) { fractional=true; return FRACTIONAL; }
    integral=true;
    for (auto p:q) for (int j=0;j<3;++j) if (std::abs(double(p[j]))>LIMIT) return RANGE;
    m={}; m.id=id; for(int j=0;j<3;++j) m.o[j]=int32_t(q[0][j]);
    m.ea=int32_t(double(q[1][a])-q[0][a]); m.eb=int32_t(double(q[3][b])-q[0][b]); m.axes=uint32_t(a|(b<<8));
    return OK;
}
U area(const Map& m) { return U(std::abs(int64_t(m.ea)))*U(std::abs(int64_t(m.eb))); }
std::array<int32_t,5> extents(const Map& m) {
    auto q=reconstruct(m); int n=axis(m),u=(n+1)%3,v=(n+2)%3;
    int32_t u0=int32_t(q[0][u]),u1=u0,v0=int32_t(q[0][v]),v1=v0;
    for (auto p:q) { u0=std::min(u0,int32_t(p[u])); u1=std::max(u1,int32_t(p[u])); v0=std::min(v0,int32_t(p[v])); v1=std::max(v1,int32_t(p[v])); }
    return {m.o[n],u0,u1,v0,v1};
}
auto geomkey(const Face& f) { return std::make_tuple(f.tag/2,f.x,f.y,f.z); }
bool geomless(const Face& a,const Face& b) { return std::make_tuple(geomkey(a),a.tag,a.id)<std::make_tuple(geomkey(b),b.tag,b.id); }
void audit(Audit& s) {
    need(s.q.size()<=MAX_QUADS, "source count cap");
    s.maps.resize(s.q.size()); s.reason.resize(s.q.size());
    U total=0;
    for(uint32_t i=0;i<s.q.size();++i) {
        bool integ=false,frac=false;
        Reason r=classify(s.q[i],i,s.maps[i],integ,frac); s.integral+=integ; s.fractional+=frac;
        if(r==OK) {
            U a=area(s.maps[i]);
            if(a>s.limits.area) r=AREA_CAP;
            else if(a>s.limits.faces-total) r=TOTAL_CAP;
            else total+=a;
        }
        s.reason[i]=r;
    }
    s.faces.reserve(size_t(total));
    for(uint32_t i=0;i<s.q.size();++i) if(s.reason[i]==OK) {
        auto e=extents(s.maps[i]); uint32_t tag=uint32_t(axis(s.maps[i])*2+positive(s.maps[i]));
        for(int u=e[1];u<e[2];++u) for(int v=e[3];v<e[4];++v) s.faces.push_back({e[0],u,v,i,tag});
    }
    s.expanded=s.faces.size(); std::sort(s.faces.begin(),s.faces.end(),geomless);
    for(size_t i=0;i<s.faces.size();) {
        size_t j=i+1; while(j<s.faces.size() && geomkey(s.faces[j])==geomkey(s.faces[i])) ++j;
        if(j-i>1) {
            ++s.conflict_groups;
            if(s.faces[i].tag!=s.faces[j-1].tag) ++s.opposing_groups;
            for(size_t k=i;k<j;++k) s.reason[s.faces[k].id]=CONFLICT;
        }
        i=j;
    }
    // All sources rejected before expansion also participate in conservative
    // contact detection. Their closed position AABB overapproximates both
    // triangles; strictly open tangential cell intervals exclude shared edges.
    // This can over-reject rotated/fractional sources, but cannot lose a face.
    // Sources rejected by the duplicate pass already had ALL cells in that pass.
    for(uint32_t id=0;id<s.q.size() && !s.query_exhausted;++id) {
        Reason r=Reason(s.reason[id]);
        if(r==OK || r==CONFLICT || r==FALLBACK_TOUCH) continue;
        if(r==NONFINITE || r==RANGE) { s.query_exhausted=true; break; }
        double lo[3]={INFINITY,INFINITY,INFINITY},hi[3]={-INFINITY,-INFINITY,-INFINITY};
        for(auto p:s.q[id]) for(int a=0;a<3;++a) { lo[a]=std::min(lo[a],double(p[a])); hi[a]=std::max(hi[a],double(p[a])); }
        for(int a=0;a<3;++a) if(lo[a]<-LIMIT || hi[a]>LIMIT) s.query_exhausted=true;
        for(int n=0;n<3 && !s.query_exhausted;++n) {
            int u=(n+1)%3,v=(n+2)%3;
            // Cells whose OPEN interiors intersect the tangential intervals.
            double u0=std::floor(lo[u]),u1=std::ceil(hi[u])-1;
            double v0=std::floor(lo[v]),v1=std::ceil(hi[v])-1;
            if(lo[u]==hi[u] && lo[u]!=std::floor(lo[u])) u1=u0;
            if(lo[v]==hi[v] && lo[v]!=std::floor(lo[v])) v1=v0;
            double p0=std::ceil(lo[n]),p1=std::floor(hi[n]);
            if(u0>u1 || v0>v1 || p0>p1) continue;
            if(p1-p0>double(s.limits.query-s.query_work)) { s.query_exhausted=true; break; }
            for(int p=int(p0);p<=int(p1) && !s.query_exhausted;++p) {
                if(++s.query_work>s.limits.query) { s.query_exhausted=true; break; }
                for(int row=int(u0);row<=int(u1) && !s.query_exhausted;++row) {
                    if(++s.query_work>s.limits.query) { s.query_exhausted=true; break; }
                    auto it=std::lower_bound(s.faces.begin(),s.faces.end(),std::make_tuple(uint32_t(n),p,row,int(v0)),
                        [](const Face& f,const auto& k){return geomkey(f)<k;});
                    while(it!=s.faces.end() && int(it->tag/2)==n && it->x==p && it->y==row && it->z<=v1) {
                        if(++s.query_work>s.limits.query) { s.query_exhausted=true; break; }
                        if(s.reason[it->id]==OK) s.reason[it->id]=FALLBACK_TOUCH;
                        ++it;
                    }
                }
            }
        }
    }
    if(s.query_exhausted) for(auto& r:s.reason) if(r==OK) r=QUERY_CAP;
    s.faces.erase(std::remove_if(s.faces.begin(),s.faces.end(),[&](const Face& f){return s.reason[f.id]!=OK;}),s.faces.end());
    std::cerr<<"audit: "<<s.q.size()<<" sources, "<<s.expanded<<" expanded, "<<s.faces.size()<<" retained faces\n";
}
int floor_div(int a,int b) { int q=a/b; return q-(a%b<0); }
Face encode_face(const Face& f,int B) {
    int n=int(f.tag/2),pos=int(f.tag%2),c[3]; c[n]=f.x-pos; c[(n+1)%3]=f.y; c[(n+2)%3]=f.z;
    int b[3],l[3]; for(int j=0;j<3;++j) { b[j]=floor_div(c[j],B); l[j]=c[j]-b[j]*B; }
    uint32_t slot=uint32_t(((l[2]*B+l[1])*B+l[0])*6+int(f.tag));
    return {b[0],b[1],b[2],f.id,slot};
}
Face decode_face(const Face& f,int B) {
    uint32_t tag=f.tag%6,cell=f.tag/6; int n=int(tag/2),pos=int(tag%2);
    int c[3]={f.x*B+int(cell%uint32_t(B)),f.y*B+int(cell/uint32_t(B)%uint32_t(B)),f.z*B+int(cell/uint32_t(B*B))};
    return {c[n]+pos,c[(n+1)%3],c[(n+2)%3],f.id,tag};
}
auto brickkey(const Face& f) { return std::tie(f.x,f.y,f.z); }
bool brickless(const Face& a,const Face& b) { return std::tie(a.x,a.y,a.z,a.tag,a.id)<std::tie(b.x,b.y,b.z,b.tag,b.id); }
void write_map(std::ostream& o,const Map& m) {
    put(o,m.id,4); for(int x:m.o) put(o,uint32_t(x),4); put(o,uint32_t(m.ea),4); put(o,uint32_t(m.eb),4); put(o,m.axes,4); put(o,0,4);
}
Map read_map(std::istream& in) {
    Map m{}; m.id=uint32_t(get(in,4)); for(auto& x:m.o) x=geti(in); m.ea=geti(in); m.eb=geti(in); m.axes=uint32_t(get(in,4)); m.reserved=uint32_t(get(in,4)); return m;
}
struct Layout {
    int side=0; U sources=0,faces=0,bricks=0,cells=0,fallback=0,bytes=0,max_faces=0;
    std::array<U,8> histogram{}; // faces/brick: <=1,4,16,64,256,1024,4096,24576
    SHA hash{};
};
std::array<U,8> hist_bounds={1,4,16,64,256,1024,4096,24576};
// Reads the actual encoded file. Direct source/local-cell coverage bitset proves
// a bijection; no checksum collision or image sample is used as correctness proof.
void verify(const fs::path& path,const Audit& s,const Layout& st) {
    std::ifstream in(path,std::ios::binary); char magic[4]; in.read(magic,4); need(std::memcmp(magic,"BFC1",4)==0,"codec magic");
    need(get(in,4)==1,"codec version"); int B=int(get(in,4)); need(B==st.side,"brick side");
    U N=get(in,4),E=get(in,4),K=get(in,4),Q=get(in,4); need(get(in,4)==0,"reserved");
    U F=get(in,8),mo=get(in,8),bo=get(in,8),fo=get(in,8),qo=get(in,8),end=get(in,8);
    SHA h{}; in.read(reinterpret_cast<char*>(h.data()),32); need(h==s.input_sha,"input digest binding"); need(get(in,8)==0 && get(in,8)==0,"header reserved");
    need(N==s.q.size() && E==st.sources && K==st.bricks && Q==st.fallback && F==st.faces,"counts");
    need(mo==128 && bo==mo+32*E && fo==bo+32*K && qo==fo+8*F && end==qo+68*Q && end==fs::file_size(path),"section bounds");
    std::vector<uint32_t> ids; ids.reserve(size_t(E)); std::vector<uint8_t> source_seen(size_t(N),0);
    uint32_t last=0;
    for(U i=0;i<E;++i) {
        Map m=read_map(in); need(m.id<N && (i==0 || m.id>last) && s.reason[m.id]==OK,"source identity/order"); last=m.id;
        need(std::memcmp(&m,&s.maps[m.id],32)==0,"source mapping identity");
        auto rebuilt=reconstruct(m); need(rebuilt==s.q[m.id],"ordered source corners / original diagonal");
        ids.push_back(m.id); source_seen[m.id]=1;
    }
    std::vector<U> offsets(size_t(N)+1,0);
    for(size_t i=0;i<N;++i) offsets[i+1]=offsets[i]+(s.reason[i]==OK?area(s.maps[i]):0);
    need(offsets.back()==F,"source areas"); std::vector<uint8_t> covered(size_t((F+7)/8),0);
    U seen_faces=0; Face previous{}; bool have_previous=false;
    // One descriptor at a time: no K-sized auxiliary allocation.
    for(U k=0;k<K;++k) {
        in.seekg(std::streamoff(bo+32*k)); Face b{}; b.x=geti(in); b.y=geti(in); b.z=geti(in);
        U count=get(in,4),start=get(in,8); need(get(in,8)==0,"descriptor reserved");
        need(count>0 && count<=U(6*B*B*B) && start==seen_faces && start+count<=F,"brick range");
        need(!have_previous || brickkey(previous)<brickkey(b),"brick order/uniqueness"); previous=b; have_previous=true;
        in.seekg(std::streamoff(fo+8*start)); uint32_t last_slot=0;
        for(U j=0;j<count;++j) {
            uint32_t slot=uint32_t(get(in,2)); need(get(in,2)==0,"face reserved"); U mapid=get(in,4);
            need(slot<uint32_t(6*B*B*B) && (j==0 || slot>last_slot) && mapid<E,"slot/source range or duplicate"); last_slot=slot;
            b.tag=slot; b.id=ids[size_t(mapid)]; auto f=decode_face(b,B); const Map& m=s.maps[f.id]; auto e=extents(m);
            need(f.tag==uint32_t(axis(m)*2+positive(m)) && f.x==e[0] && f.y>=e[1] && f.y<e[2] && f.z>=e[3] && f.z<e[4],"canonical face/source membership");
            U cell=offsets[f.id]+U(f.y-e[1])*U(e[4]-e[3])+U(f.z-e[3]);
            uint8_t mask=uint8_t(1u<<(cell%8)); need((covered[size_t(cell/8)]&mask)==0,"duplicate canonical source cell"); covered[size_t(cell/8)]|=mask;
            ++seen_faces;
        }
    }
    need(seen_faces==F,"face cardinality");
    for(U i=0;i<F;++i) need((covered[size_t(i/8)]&(1u<<(i%8)))!=0,"missing canonical source cell");
    in.seekg(std::streamoff(qo)); last=0;
    for(U i=0;i<Q;++i) {
        uint32_t id=uint32_t(get(in,4)); need(id<N && s.reason[id]!=OK && source_seen[id]==0 && (i==0 || id>last),"fallback partition/order");
        Quad q{}; in.read(reinterpret_cast<char*>(&q),64); need(bool(in) && std::memcmp(&q,&s.q[id],64)==0,"fallback float4 bit identity");
        source_seen[id]=1; last=id;
    }
    for(auto c:source_seen) need(c==1,"missing source");
}
Layout codec(Audit& s,int B,const fs::path& path) {
    Layout st; st.side=B; st.faces=s.faces.size();
    std::vector<uint32_t> ord(s.q.size(),UINT32_MAX);
    for(uint32_t i=0;i<s.q.size();++i) if(s.reason[i]==OK) ord[i]=uint32_t(st.sources++);
    st.fallback=s.q.size()-st.sources;
    for(auto& f:s.faces) f=encode_face(f,B);
    std::sort(s.faces.begin(),s.faces.end(),brickless);
    for(size_t i=0;i<s.faces.size();) {
        size_t j=i+1; while(j<s.faces.size() && brickkey(s.faces[j])==brickkey(s.faces[i])) ++j;
        U n=j-i; ++st.bricks; st.max_faces=std::max(st.max_faces,n);
        ++st.histogram[size_t(std::lower_bound(hist_bounds.begin(),hist_bounds.end(),n)-hist_bounds.begin())];
        uint32_t lastcell=UINT32_MAX;
        for(size_t k=i;k<j;++k) { uint32_t cell=s.faces[k].tag/6; if(cell!=lastcell) ++st.cells; lastcell=cell; }
        i=j;
    }
    U mo=128,bo=mo+32*st.sources,fo=bo+32*st.bricks,qo=fo+8*st.faces;
    st.bytes=qo+68*st.fallback;
    need(!fs::exists(path),"codec output exists");
    std::ofstream out(path,std::ios::binary); need(bool(out),"codec output open");
    out.write("BFC1",4); put(out,1,4); put(out,uint32_t(B),4); put(out,s.q.size(),4); put(out,st.sources,4); put(out,st.bricks,4); put(out,st.fallback,4); put(out,0,4);
    for(U v:{st.faces,mo,bo,fo,qo,st.bytes}) put(out,v,8);
    out.write(reinterpret_cast<const char*>(s.input_sha.data()),32); put(out,0,8); put(out,0,8);
    for(uint32_t i=0;i<s.q.size();++i) if(s.reason[i]==OK) write_map(out,s.maps[i]);
    for(size_t i=0;i<s.faces.size();) {
        size_t j=i+1; while(j<s.faces.size() && brickkey(s.faces[j])==brickkey(s.faces[i])) ++j;
        auto& f=s.faces[i]; put(out,uint32_t(f.x),4); put(out,uint32_t(f.y),4); put(out,uint32_t(f.z),4); put(out,j-i,4); put(out,i,8); put(out,0,8); i=j;
    }
    for(auto f:s.faces) { put(out,f.tag,2); put(out,0,2); put(out,ord[f.id],4); }
    for(uint32_t i=0;i<s.q.size();++i) if(s.reason[i]!=OK) { put(out,i,4); out.write(reinterpret_cast<const char*>(&s.q[i]),64); }
    out.close(); need(bool(out) && fs::file_size(path)==st.bytes,"codec write failed");
    verify(path,s,st); st.hash=file_sha(path);
    for(auto& f:s.faces) f=decode_face(f,B);
    std::cerr<<"codec "<<B<<": "<<st.bricks<<" bricks; "<<st.bytes<<" bytes; exhaustive decode PASS\n";
    return st;
}
void layout_json(std::ostream& o,const Layout& x) {
    U slots=U(6*x.side*x.side*x.side)*x.bricks;
    o<<"{\"side\":"<<x.side<<",\"nonempty_bricks\":"<<x.bricks<<",\"occupied_owner_cells\":"<<x.cells
     <<",\"face_slots\":"<<slots<<",\"face_occupancy\":"<<(slots?double(x.faces)/double(slots):0)
     <<",\"cell_occupancy\":"<<(slots?double(x.cells)/double(slots/6):0)<<",\"faces_per_brick_mean\":"<<(x.bricks?double(x.faces)/double(x.bricks):0)
     <<",\"max_faces_per_brick\":"<<x.max_faces<<",\"face_count_histogram_upper_bounds\":[1,4,16,64,256,1024,4096,24576],\"face_count_histogram\":[";
    for(size_t i=0;i<8;++i) o<<(i?",":"")<<x.histogram[i];
    o<<"],\"descriptor_bytes\":"<<32*x.bricks<<",\"source_mapping_bytes\":"<<32*x.sources
     <<",\"fallback_position_bytes\":"<<64*x.fallback<<",\"fallback_source_id_bytes\":"<<4*x.fallback
     <<",\"sparse_slot_and_padding_bytes\":"<<4*x.faces<<",\"sparse_source_reference_bytes\":"<<4*x.faces
     <<",\"sparse_codec_bytes\":"<<x.bytes<<",\"dense_grid_source_reference_bytes\":"<<4*slots
     <<",\"dense_occupied_reference_bytes\":"<<4*x.faces<<",\"dense_empty_slot_bytes\":"<<4*(slots-x.faces)
     <<",\"dense_codec_bytes\":"<<128+32*x.bricks+32*x.sources+68*x.fallback+4*slots
     <<",\"as_brick_aabb_bytes\":"<<24*x.bricks<<",\"as_fallback_uint32_index_bytes\":"<<24*x.fallback
     <<",\"candidate_as_primitives\":"<<x.bricks+2*x.fallback
     <<",\"codec_sha256\":\""<<hex(x.hash)<<"\",\"exhaustive_source_face_coverage\":true}";
}
void summary(const fs::path& out,const Audit& s,const std::vector<Layout>& layouts,const std::string& kind) {
    std::array<U,REASON_COUNT> counts{}; for(auto r:s.reason) ++counts[r];
    rusage usage{}; getrusage(RUSAGE_SELF,&usage);
    std::ofstream o(out); o<<std::setprecision(12)<<"{\n\"kind\":\""<<kind<<"\",\n\"input_sha256\":\""<<hex(s.input_sha)<<"\",\n\"quads\":"<<s.q.size()
     <<",\n\"original_triangles\":"<<2*s.q.size()<<",\n\"integral_rectangle_quads_before_caps_or_conflicts\":"<<s.integral
     <<",\n\"fractional_rectangle_quads\":"<<s.fractional<<",\n\"expanded_unit_faces_before_conflicts\":"<<s.expanded
     <<",\n\"retained_original_quads\":"<<counts[OK]<<",\n\"retained_unit_faces\":"<<s.faces.size()
     <<",\n\"fallback_triangles\":"<<2*(s.q.size()-counts[OK])<<",\n\"conflicting_geometric_cells\":"<<s.conflict_groups
     <<",\n\"opposing_winding_cells\":"<<s.opposing_groups<<",\n\"fallback_contact_query_work\":"<<s.query_work
     <<",\n\"global_query_fallback\":"<<(s.query_exhausted?"true":"false")
     <<",\n\"caps\":{\"source_quads\":"<<MAX_QUADS<<",\"faces_per_source\":"<<s.limits.area<<",\"expanded_faces\":"<<s.limits.faces<<",\"fallback_query_work\":"<<s.limits.query<<"}"
     <<",\n\"peak_process_rss_bytes_macos\":"<<usage.ru_maxrss<<",\n\"source_partition\":{";
    for(int i=0;i<REASON_COUNT;++i) o<<(i?",":"")<<"\""<<reason_names[i]<<"\":"<<counts[size_t(i)];
    o<<"},\n\"layouts\":[";
    for(size_t i=0;i<layouts.size();++i) { if(i) o<<","; layout_json(o,layouts[i]); }
    o<<"],\n\"claim\":\"Exact source geometry representation and modeled metadata/AS-input counts only; no ray cost, FPS, full-scene quality, shading payload or hardware AS-memory measurement.\"\n}\n";
    need(bool(o),"summary write");
}
Quad rect(int n,int pos,float p,float u,float v,float w=1,float h=1) {
    Quad q{}; int a=(n+1)%3,b=(n+2)%3;
    for(auto& c:q) { c[n]=p; c[a]=u; c[b]=v; c[3]=1; }
    q[1][a]+=w; q[2][a]+=w; q[2][b]+=h; q[3][b]+=h;
    if(!pos) std::swap(q[1],q[3]); return q;
}
void self_test(const fs::path& dir) {
    struct Case { std::string name; std::vector<Quad> q; U accepted,faces; Limits limits{}; };
    std::vector<Case> cases;
    std::vector<Quad> all;
    for(int n=0;n<3;++n) for(int pos=0;pos<2;++pos) all.push_back(rect(n,pos,float(64*(n*2+pos)), -17,-9,3,2));
    cases.push_back({"six-orientations-negative",all,6,36});
    cases.push_back({"brick-boundaries",{rect(2,1,8,7,15,3,3),rect(2,0,-16,-17,-9,3,3)},2,18});
    cases.push_back({"hole-and-distinct-source-attributes",{rect(2,1,0,0,0),rect(2,1,0,2,0)},2,2});
    cases.push_back({"duplicate-whole-sources",{rect(2,1,0,0,0,2,1),rect(2,1,0,0,0)},0,0});
    cases.push_back({"opposite-interior-face",{rect(0,1,0,0,0),rect(0,0,0,0,0)},0,0});
    cases.push_back({"partial-overlap",{rect(1,1,0,0,0,3,2),rect(1,1,0,2,1,2,2)},0,0});
    cases.push_back({"coplanar-shared-edge",{rect(2,1,0,0,0),rect(2,1,0,1,0)},2,2});
    cases.push_back({"fractional-disjoint",{rect(2,1,.5f,0,0),rect(2,1,8,0,0)},1,1});
    cases.push_back({"fractional-overlap",{rect(2,1,0,0,0),rect(2,1,0,.25f,.25f,.5f,.5f)},0,0});
    cases.push_back({"fractional-shared-edge",{rect(2,1,0,0,0),rect(2,1,0,1,0,.5f,1)},1,1});
    cases.push_back({"fractional-interior-crossing",{rect(2,1,0,0,0),rect(0,1,.5f,0,-.5f,1,1)},0,0});
    Quad rotated={{{0,0,0,1},{1,1,0,1},{0,2,0,1},{-1,1,0,1}}};
    cases.push_back({"rotated-overlap",{rect(2,1,0,0,0),rotated},0,0});
    Quad bow=rect(2,1,0,0,0); std::swap(bow[1],bow[2]);
    cases.push_back({"bowtie-winding-fallback",{bow,rect(2,1,3,0,0)},1,1});
    Quad deg=rect(0,1,0,0,0,0,1);
    cases.push_back({"degenerate-fallback",{deg},0,0});
    cases.push_back({"per-source-cap",{rect(2,1,0,0,0,65,65)},0,0});
    cases.push_back({"total-cap-disjoint",{rect(2,1,0,0,0,2,1),rect(2,1,4,0,0)},1,2,{4096,2,MAX_QUERY_WORK}});
    cases.push_back({"cap-fallback-overlap",{rect(2,1,0,0,0),rect(2,1,0,0,0,65,65)},0,0});
    cases.push_back({"query-cap-safe-global-fallback",{rect(2,1,0,0,0),rect(2,1,0,.25f,.25f,.5f,.5f)},0,0,{4096,MAX_FACES,0}});
    Quad nonfinite=rect(2,1,0,0,0); nonfinite[0][0]=std::numeric_limits<float>::quiet_NaN();
    cases.push_back({"nonfinite-safe-global-fallback",{nonfinite,rect(2,1,5,0,0)},0,0});
    cases.push_back({"range-safe-global-fallback",{rect(2,1,8388608,0,0),rect(2,1,5,0,0)},0,0});
    // Per-corner affine parameters and ORIGINAL piecewise triangle weights,
    // including a cell crossed by q0->q2. Values deliberately nonhomogeneous.
    U interpolation_checks=0;
    for(int a=0;a<3;++a) for(int b=0;b<3;++b) if(a!=b) for(int sa:{-1,1}) for(int sb:{-1,1}) {
        Map m{19,{-17,8,-9},sa*3,sb*2,uint32_t(a|(b<<8)),0}; auto q=reconstruct(m);
        Map parsed{}; bool integ=false,frac=false; need(classify(q,19,parsed,integ,frac)==OK && positive(m)==positive(parsed),"signed source mapping");
        for(int ti=0;ti<=12;++ti) for(int si=0;si<=12;++si) {
            double t=double(ti)/12,s=double(si)/12,pa=m.o[a]+t*m.ea,pb=m.o[b]+s*m.eb;
            double t2=(pa-m.o[a])/m.ea,s2=(pb-m.o[b])/m.eb;
            std::array<double,4> w=t>=s?std::array<double,4>{1-t,t-s,s,0}:std::array<double,4>{1-s,0,t,s-t};
            std::array<double,4> w2=t2>=s2?std::array<double,4>{1-t2,t2-s2,s2,0}:std::array<double,4>{1-s2,0,t2,s2-t2};
            double x=0,y=0; std::array<double,4> attr={2,7,29,-11};
            for(int k=0;k<4;++k) { x+=w[k]*attr[k]; y+=w2[k]*attr[k]; }
            need(std::abs(x-y)<1e-11,"original-triangle interpolation"); ++interpolation_checks;
        }
    }
    std::ofstream report(dir/"self-tests.json"); report<<"{\"cases\":[";
    for(size_t i=0;i<cases.size();++i) {
        auto& c=cases[i]; Audit s; s.q=c.q; s.limits=c.limits; s.input_sha=sha(s.q.data(),s.q.size()*64); audit(s);
        U accepted=U(std::count(s.reason.begin(),s.reason.end(),uint8_t(OK)));
        need(accepted==c.accepted && s.faces.size()==c.faces,"self-test counts: "+c.name);
        for(int B:{8,16}) codec(s,B,dir/(c.name+"-"+std::to_string(B)+".bfc"));
        report<<(i?",":"")<<"\""<<c.name<<"\"";
    }
    // Negative control: corrupt an actual source reference; decoder must reject.
    Audit s; s.q={rect(2,1,0,0,0),rect(2,1,0,2,0)}; audit(s);
    auto path=dir/"negative-control.bfc"; auto st=codec(s,8,path);
    { std::fstream f(path,std::ios::binary|std::ios::in|std::ios::out); f.seekp(std::streamoff(128+32*st.sources+32*st.bricks+4)); put(f,1,4); }
    bool rejected=false; try { verify(path,s,st); } catch(const std::runtime_error&) { rejected=true; }
    need(rejected,"negative control was accepted");
    report<<"],\"case_count\":"<<cases.size()<<",\"brick_sizes\":[8,16],\"source_interpolation_checks\":"<<interpolation_checks<<",\"corrupt_source_reference_rejected\":true,\"pass\":true}\n";
    std::cout<<"PASS "<<cases.size()<<" synthetic cases x 2 brick sizes; "<<interpolation_checks<<" interpolation checks; corruption rejected\n";
}
int main(int argc,char** argv) {
    try {
        need(argc==3 || argc==4,"usage: brick-face-audit --self-test NEW_OUTDIR | INPUT.bin INPUT.json NEW_OUTDIR");
        bool test=std::string(argv[1])=="--self-test"; need(test?argc==3:argc==4,"argument count");
        fs::path dir=argv[test?2:3];
        need(dir.parent_path()=="build" && dir.filename().string().find("lod-brick-faces-")==0,"output must be build/lod-brick-faces-* directly");
        need(!fs::exists(dir) && fs::create_directory(dir),"output directory exists or cannot be created");
        if(test) { self_test(dir); return 0; }
        need(std::getenv("MC_GPU_LOCK_HELD") && std::string(std::getenv("MC_GPU_LOCK_HELD"))=="functional","real audit must run via gpulock functional");
        fs::path input=argv[1],metadata=argv[2];
        need(fs::file_size(input)>=64 && fs::file_size(input)<=64+64*U(MAX_QUADS),"fixture size cap");
        std::ifstream in(input,std::ios::binary); char magic[4]; in.read(magic,4); need(std::memcmp(magic,"LRT1",4)==0,"LRT1 magic");
        need(get(in,4)==1,"LRT1 version"); uint32_t N=uint32_t(get(in,4)); need(get(in,4)==0,"LRT1 flags");
        need(N>0 && N<=MAX_QUADS && fs::file_size(input)==64+64*U(N),"fixture length/count");
        uint32_t endian=1; need(*reinterpret_cast<char*>(&endian)==1,"little-endian host required");
        Audit s; s.input_sha=file_sha(input);
        need(fs::file_size(metadata)<=1048576,"metadata size cap");
        std::ifstream meta(metadata); std::string text((std::istreambuf_iterator<char>(meta)),std::istreambuf_iterator<char>());
        need(text.size()<=1048576 && text.find("\"sha256\": \""+hex(s.input_sha)+"\"")!=std::string::npos,"forest.json SHA mismatch");
        need(hex(s.input_sha)=="a960ea23fb9b2cdc0e7618c67c00801672741bec96b607f6595b0cbc57289663","immutable forest identity");
        need(N==1558323,"immutable forest count");
        s.q.resize(N); in.seekg(64); in.read(reinterpret_cast<char*>(s.q.data()),std::streamsize(64*U(N))); need(bool(in),"fixture read");
        audit(s); std::vector<Layout> layouts;
        for(int B:{8,16}) layouts.push_back(codec(s,B,dir/("forest-"+std::to_string(B)+".bfc")));
        need(file_sha(input)==s.input_sha,"input changed during audit");
        summary(dir/"audit.json",s,layouts,"cached three-dimensional SOLID subset; CPU functional audit");
        std::cout<<"PASS: "<<dir.string()<<"/audit.json\n";
    } catch(const std::exception& e) { std::cerr<<"FAIL: "<<e.what()<<"\n"; return 1; }
}
