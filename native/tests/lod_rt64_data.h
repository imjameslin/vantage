#pragma once
// CPU-only sparse64 ABI shared with the offscreen GPU probe. No Metal dependency.
// tree.bin is exactly Node[nodeCount], little endian, root index 0. Metadata is
// tree.json: origin[3], size (power of four in WORLD BLOCKS), boundsMin/Max
// (occupied, max exclusive), nodeCount. Node child order: x+4*y+16*z.
// mask==0 is terminal; material==0 is empty (only an empty root is retained).
// mask!=0 with material==0 is occupied with air/unknown representative: DESCEND.
// Interior materials are NOT occupancy. Never skip an interior on material==0.
// childBase bit31: occupied children are uint32 materials in a separate array,
// at (childBase & 0x7fffffff)+rank(mask,bit), terminal at parent.size/4.
// Otherwise childBase indexes nodes, not bytes. Children need not follow parent in
// memory, but all direct children are contiguous in original-mask rank order.
#include <array>
#include <cmath>
#include <cstdint>
#include <fstream>
#include <limits>
#include <stdexcept>
#include <string>
#include <vector>

namespace lodrt64 {
inline constexpr uint32_t LeafFlag=0x80000000u, IndexMask=0x7fffffffu;
struct Node { uint64_t mask = 0; uint32_t childBase = 0; uint32_t material = 0; };
static_assert(sizeof(Node) == 16, "sparse64 ABI");
static_assert(offsetof(Node, childBase) == 8 && offsetof(Node, material) == 12, "sparse64 offsets");
using Pos = std::array<int32_t, 3>;
inline unsigned rank(uint64_t mask, unsigned bit) {
    return __builtin_popcountll(mask & ((uint64_t(1) << bit) - 1));
}
struct Tree {
    Pos origin{};
    uint32_t size = 1;
    std::vector<Node> nodes;
    std::vector<uint32_t> materials;
};
inline void require(bool ok, const std::string& why) { if (!ok) throw std::runtime_error(why); }
inline bool power4(uint32_t n) { return n && !(n & (n-1)) && (n & 0x55555555u); }
inline Tree loadRaw(const std::string& file, Pos origin, uint32_t size, uint64_t maxNodes = 100000000, const std::string& materialsFile = "") {
    require(power4(size), "root extent is not power4");
    std::ifstream in(file, std::ios::binary | std::ios::ate);
    require(bool(in), "cannot open tree");
    auto bytes = in.tellg();
    require(bytes >= 16 && uint64_t(bytes) % 16 == 0 && uint64_t(bytes)/16 <= maxNodes, "tree byte count");
    Tree t{origin, size, std::vector<Node>(size_t(bytes)/16), {}};
    in.seekg(0); in.read(reinterpret_cast<char*>(t.nodes.data()), bytes);
    require(bool(in), "truncated tree");
    if(!materialsFile.empty()) {
        std::ifstream m(materialsFile,std::ios::binary|std::ios::ate); require(bool(m),"cannot open leaf materials"); auto n=m.tellg();
        require(n>=0 && uint64_t(n)%4==0 && uint64_t(n)/4<=IndexMask,"leaf material byte count"); t.materials.resize(uint64_t(n)/4);
        m.seekg(0); m.read(reinterpret_cast<char*>(t.materials.data()),n); require(bool(m),"truncated leaf materials");
    }
    return t;
}
inline uint32_t sample(const Tree& t, Pos p) {
    Pos o = t.origin;
    for (int a=0;a<3;a++) if (int64_t(p[a]) < o[a] || int64_t(p[a]) >= int64_t(o[a])+t.size) return 0;
    uint32_t index=0, size=t.size;
    for (;;) {
        require(index < t.nodes.size(), "child out of bounds");
        const auto& n = t.nodes[index];
        if (!n.mask) return n.material;
        require(size >= 4, "interior below unit cell");
        size /= 4;
        unsigned c[3];
        for(int a=0;a<3;a++) { c[a] = (p[a]-o[a])/size; o[a] += c[a]*size; }
        unsigned bit = c[0]+4*c[1]+16*c[2];
        if (!(n.mask & (uint64_t(1)<<bit))) return 0;
        if(n.childBase & LeafFlag) {
            uint64_t i=(n.childBase & IndexMask)+rank(n.mask,bit);
            require(i<t.materials.size(),"leaf material out of bounds"); return t.materials[i];
        }
        index=n.childBase+rank(n.mask,bit);
    }
}
struct Validation {
    uint64_t terminals=0, interiors=0, occupiedBlocks=0;
    Pos boundsMin{INT32_MAX,INT32_MAX,INT32_MAX}, boundsMax{INT32_MIN,INT32_MIN,INT32_MIN};
};
template<class TreeLike> inline Validation validate(const TreeLike& t) {
    require(power4(t.size) && !t.nodes.empty(), "invalid root");
    struct Visit { uint32_t index, size; Pos p; bool scalar; };
    std::vector<Visit> todo{{0,t.size,t.origin,false}};
    std::vector<uint8_t> seen((t.nodes.size()+7)/8);
    std::vector<uint8_t> seenMaterials((t.materials.size()+7)/8);
    Validation v;
    uint64_t count=0,scalarCount=0;
    while (!todo.empty()) {
        auto item=todo.back(); todo.pop_back();
        auto& marks=item.scalar?seenMaterials:seen;
        require(item.index < (item.scalar?t.materials.size():t.nodes.size()), "child out of bounds");
        require(!(marks[item.index/8] & (1u<<(item.index%8))), "shared/cyclic child");
        marks[item.index/8] |= 1u<<(item.index%8);
        if(item.scalar) ++scalarCount; else ++count;
        Node n=item.scalar?Node{0,0,t.materials[item.index]}:t.nodes[item.index];
        if (!n.mask) {
            require(n.material || (!item.scalar && item.index==0), "empty child must be absent");
            if (!n.material) continue;
            ++v.terminals;
            uint64_t volume=uint64_t(item.size)*item.size*item.size;
            require(volume <= UINT64_MAX-v.occupiedBlocks, "occupied volume overflow");
            v.occupiedBlocks+=volume;
            for (int a=0;a<3;a++) {
                v.boundsMin[a]=std::min(v.boundsMin[a],item.p[a]);
                v.boundsMax[a]=std::max(v.boundsMax[a],int32_t(item.p[a]+item.size));
            }
            continue;
        }
        ++v.interiors;
        unsigned used=__builtin_popcountll(n.mask);
        bool scalar=n.childBase & LeafFlag; uint32_t base=n.childBase & IndexMask;
        require(item.size>=4 && uint64_t(base)+used<=(scalar?t.materials.size():t.nodes.size()), "invalid child range/size");
        auto childSize=item.size/4;
        for(unsigned b=0;b<64;b++) if(n.mask & (uint64_t(1)<<b)) {
            Pos p=item.p;
            p[0]+=(b&3)*childSize; p[1]+=((b>>2)&3)*childSize; p[2]+=(b>>4)*childSize;
            todo.push_back({base+rank(n.mask,b),childSize,p,scalar});
        }
    }
    require(count==t.nodes.size(), "unreachable nodes");
    require(scalarCount==t.materials.size(), "unreachable leaf materials");
    return v;
}
} // namespace lodrt64
