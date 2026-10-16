#pragma once
// Header-only, ObjC++17 / ARC. Link Foundation, Metal, and zlib (-lz).
// Read-only farsight-scene-1 loader; no command queues, GPU work, or timing.
// Wire source: bench/src/bench/LodSnapshot.java; arena ABI: LodGpu.java.
//
//   lodscene::Scene scene; std::string error;
//   if (!lodscene::load(device, directory, scene, error)) { ... }
//   auto &d = scene.draws[i]; auto &g = scene.groups[d.groupOrdinal];
//   bind(scene.arenas[g.arena].buffer, scene.sectionInfos,
//        d.quadBuffer, d.itemBuffer); // all buffer offsets are zero
//   drawIndirect(d.argsBuffer, 0);  // caller supplies 012/230 indices
//
// Arena identity is GLOBAL, shared across layers/groups. Every captured source
// goes to firstQuad * stride in its original 256 MiB arena; never repacked.
// Missing arena spans/section slots are zero, not inferred source geometry.
// groups[].clusters contains the original uint4 input records converted BE->LE;
// LDV buffers contain exact captured LE bytes, including zeroed uint2 holes.
// Empty LDV buffers have a 4-byte zero allocation; their logical counts are 0.
// sections stores sparse original metadata in slot order; sectionIndex maps an
// original slot to that vector (-1 for absent). sectionInfos/table are indexed
// by ORIGINAL slot with strides 16/32 and full 262144-slot address ranges.
//
// load requires LDV1. It verifies all LDS1/LDG1/LDV1 hashes, sizes, references,
// overlap/duplicates and EOF before exposing a result. Textures/uniforms are
// NOT loaded or hashed here: parent uploads them (offline audit checks all).
// load is transactional: failure frees partial resources and leaves out alone.
// Resource cap is 6 GiB for both owned allocations and device.currentAllocatedSize
// observed during loading. Caller must budget its subsequent targets/textures.
// CPU scratch is bounded; no whole uncompressed geometry copy exists.

#if !defined(__OBJC__) || !defined(__cplusplus) || !__has_feature(objc_arc)
#error "lod_scene_data.h requires ObjC++ with ARC"
#endif
#if __BYTE_ORDER__ != __ORDER_LITTLE_ENDIAN__
#error "Captured GPU records require a little-endian host"
#endif

#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#include <CommonCrypto/CommonDigest.h>
#include <zlib.h>
#include <algorithm>
#include <array>
#include <cerrno>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <fcntl.h>
#include <limits>
#include <stdexcept>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <utility>
#include <vector>

namespace lodscene {
inline constexpr uint32_t MaxSections = 1u << 18, MaxArenas = 64, MaxGroups = 4096;
inline constexpr uint64_t ArenaBytes = 256ull << 20, MaxMetalBytes = 6ull << 30, MaxRawBytes = 8ull << 30;

struct LayerSpan {
    int32_t arena = -1;
    uint32_t firstQuad = 0, quadCount = 0, stride = 0;
};
struct Section {
    uint32_t slot = 0;
    std::array<int32_t, 4> origin{}; // x,y,z,level
    std::array<LayerSpan, 3> layers{};
};
struct Arena {
    uint32_t index = 0, stride = 0; // stride=0/buffer=nil if never referenced
    __strong id<MTLBuffer> buffer = nil;
};
struct Group {
    uint32_t ordinal = 0, layer = 0, arena = 0, stride = 0, clusterCount = 0;
    bool fade = false;
    int32_t level = -1; // diagnostic level, -1 means mixed
    uint64_t submittedQuads = 0;
    __strong id<MTLBuffer> clusters = nil;       // LE uint4[clusterCount], full LDG1
};
struct Draw {
    uint32_t groupOrdinal = 0, layerCode = 0, quadCount = 0, usedClusterCount = 0;
    __strong id<MTLBuffer> quadBuffer = nil; // LE uint[quadCount], LDV1
    __strong id<MTLBuffer> itemBuffer = nil; // LE uint2[usedClusterCount], LDV1
    __strong id<MTLBuffer> argsBuffer = nil; // exact LE 20B MTL indexed-draw args
};
struct Stats {
    bool validated = false;
    uint64_t selectedSections = 0, groups = 0, submittedClusters = 0;
    uint64_t payloadQuads = 0, submittedQuads = 0, visibleQuads = 0;
    uint64_t metalBytes = 0, requestedBufferBytes = 0, peakDeviceBytes = 0;
    uint64_t verifiedRawBytes = 0, verifiedGzipBytes = 0;
    uint32_t verifiedFiles = 0, allocatedArenas = 0;
    std::array<uint64_t, 3> payloadByLayer{}, submittedByLayer{}, visibleByLayer{};
    std::array<uint64_t, 8> payloadByLevel{}, submittedByLevel{}, submittedByFacing{}, visibleByLevel{};
    std::array<uint64_t, 2> payloadByStride{}; // [48,112]
};
struct Scene {
    __strong NSDictionary *manifest = nil;
    std::array<int32_t, 3> cullBase{};
    std::vector<Arena> arenas; // indexed by original arena index
    std::vector<Section> sections;
    std::vector<int32_t> sectionIndex; // indexed by original section slot
    __strong id<MTLBuffer> sectionInfos = nil;
    __strong id<MTLBuffer> sectionTable = nil;
    std::vector<Group> groups; // original LDG1 order, never layer-sorted
    std::vector<Draw> draws;   // original LDV1 order; groupOrdinal indexes groups
    Stats stats;
    Scene() = default;
    Scene(const Scene &) = delete;
    Scene &operator=(const Scene &) = delete;
    Scene(Scene &&) noexcept = default;
    Scene &operator=(Scene &&) noexcept = default;
};

namespace detail {
inline void check(bool ok, const std::string &what) {
    if (!ok) throw std::runtime_error(what);
}
inline std::string text(NSString *s) { return s ? std::string(s.UTF8String ?: "") : "(nil)"; }
inline NSDictionary *object(id value, const char *name) {
    check([value isKindOfClass:NSDictionary.class], std::string(name) + ": expected object");
    return (NSDictionary *)value;
}
inline int64_t number(id value, int64_t low, int64_t high, const char *name) {
    check([value isKindOfClass:NSNumber.class] && CFGetTypeID((__bridge CFTypeRef)value) != CFBooleanGetTypeID(),
          std::string(name) + ": expected integer");
    double d = [value doubleValue];
    check(std::isfinite(d) && std::floor(d) == d && d >= double(low) && d <= double(high),
          std::string(name) + ": integer outside bounds");
    int64_t n = [value longLongValue];
    check(n >= low && n <= high, std::string(name) + ": integer outside bounds");
    return n;
}
template<size_t N> inline void equalCounts(id value, const std::array<uint64_t, N> &counts, const char *name) {
    check([value isKindOfClass:NSArray.class] && [value count] == N, std::string(name) + ": array size");
    for (size_t i = 0; i < N; ++i)
        check(uint64_t(number(value[i], 0, MaxRawBytes, name)) == counts[i], std::string(name) + ": count mismatch");
}
inline uint32_t be32(const uint8_t *p) {
    return (uint32_t(p[0]) << 24) | (uint32_t(p[1]) << 16) | (uint32_t(p[2]) << 8) | p[3];
}
inline int32_t si32(uint32_t n) { int32_t out; std::memcpy(&out, &n, 4); return out; }
inline uint32_t le32(const void *p) { uint32_t n; std::memcpy(&n, p, 4); return n; }
inline uint64_t be64(const uint8_t *p) { return (uint64_t(be32(p)) << 32) | be32(p + 4); }
inline uint32_t levelBucket(const Section &s) { return std::min(7, s.origin[3]); }

struct File {
    int fd = -1;
    explicit File(NSString *path) {
        fd = open(path.fileSystemRepresentation, O_RDONLY | O_NOFOLLOW | O_NONBLOCK);
        check(fd >= 0, "open " + text(path) + ": " + std::strerror(errno));
        struct stat st{};
        if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode)) {
            close(fd); fd = -1;
            throw std::runtime_error("not a regular file: " + text(path));
        }
    }
    ~File() { if (fd >= 0) close(fd); }
    File(const File &) = delete;
    uint64_t size() const { struct stat st{}; check(fstat(fd, &st) == 0 && st.st_size >= 0, "file stat failed"); return st.st_size; }
    size_t read(void *dst, size_t n) {
        ssize_t got;
        do { got = ::read(fd, dst, n); } while (got < 0 && errno == EINTR);
        check(got >= 0, "file read failed");
        return size_t(got);
    }
};

// One 64 KiB compressed buffer and one 1 MiB decompression buffer. Geometry is
// copied directly from this bounded scratch into the final shared MTLBuffer.
class Blob {
    File file_;
    std::string name_, expectedSha_;
    uint64_t raw_, gzip_, produced_ = 0, consumed_ = 0;
    z_stream z_{};
    CC_SHA256_CTX hash_{};
    bool initialized_ = false, ended_ = false;
    std::vector<uint8_t> input_{std::vector<uint8_t>(65536)}, output_{std::vector<uint8_t>(1 << 20)};
    size_t at_ = 0, have_ = 0;
    void need(bool ok, const char *what) const {
        check(ok, name_ + "@" + std::to_string(consumed_) + ": " + what);
    }
    bool fill() {
        if (at_ != have_) return true;
        at_ = have_ = 0;
        while (!ended_) {
            if (!z_.avail_in) {
                size_t n = file_.read(input_.data(), input_.size());
                need(n != 0, "truncated gzip");
                z_.next_in = input_.data(); z_.avail_in = uInt(n);
            }
            z_.next_out = output_.data();
            z_.avail_out = uInt(std::min<uint64_t>(output_.size(), raw_ - produced_ + 1));
            uInt offered = z_.avail_out, before = z_.avail_in;
            int code = inflate(&z_, Z_NO_FLUSH);
            need(code == Z_OK || code == Z_STREAM_END, "invalid gzip/CRC");
            have_ = offered - z_.avail_out;
            produced_ += have_;
            need(produced_ <= raw_, "decompressed bytes exceed rawBytes");
            CC_SHA256_Update(&hash_, output_.data(), CC_LONG(have_));
            if (code == Z_STREAM_END) {
                uint8_t extra;
                need(z_.avail_in == 0 && file_.read(&extra, 1) == 0, "trailing/concatenated gzip data");
                need(produced_ == raw_, "rawBytes mismatch");
                std::array<unsigned char, CC_SHA256_DIGEST_LENGTH> digest{};
                CC_SHA256_Final(digest.data(), &hash_);
                static constexpr char hex[] = "0123456789abcdef";
                std::string sha;
                for (auto byte : digest) { sha += hex[byte >> 4]; sha += hex[byte & 15]; }
                need(sha == expectedSha_, "rawSha256 mismatch");
                ended_ = true;
            }
            if (have_) return true;
            need(ended_ || z_.avail_in < before, "gzip made no progress");
        }
        return false;
    }
public:
    Blob(NSString *dir, NSDictionary *files, NSString *name)
        : file_([dir stringByAppendingPathComponent:name]), name_(text(name)) {
        NSDictionary *meta = object(files[name], "blob metadata");
        raw_ = number(meta[@"rawBytes"], 0, MaxRawBytes, "rawBytes");
        gzip_ = number(meta[@"gzipBytes"], 18, MaxRawBytes + (64 << 20), "gzipBytes");
        expectedSha_ = text(meta[@"rawSha256"]);
        need(file_.size() == gzip_, "gzipBytes mismatch");
        check(inflateInit2(&z_, 31) == Z_OK, "zlib initialization failed");
        initialized_ = true;
        CC_SHA256_Init(&hash_);
    }
    ~Blob() { if (initialized_) inflateEnd(&z_); }
    Blob(const Blob &) = delete;
    uint64_t remaining() const { return raw_ - consumed_; }
    void read(void *dst, uint64_t bytes) {
        need(bytes <= remaining(), "record/payload exceeds remaining raw bytes");
        auto *out = static_cast<uint8_t *>(dst);
        while (bytes) {
            need(fill(), "truncated record/payload");
            size_t n = size_t(std::min<uint64_t>(bytes, have_ - at_));
            std::memcpy(out, output_.data() + at_, n);
            at_ += n; consumed_ += n; bytes -= n; out += n;
        }
    }
    uint32_t u32() { uint8_t b[4]; read(b, 4); return be32(b); }
    void finish(Stats &stats) {
        need(!fill() && consumed_ == raw_, "unexpected trailing raw data");
        stats.verifiedRawBytes += raw_; stats.verifiedGzipBytes += gzip_; ++stats.verifiedFiles;
    }
};

inline void mark(std::vector<uint8_t> &bits, uint64_t start, uint32_t n) {
    while (n) {
        uint32_t shift = start & 7, take = std::min(n, 8 - shift);
        uint8_t mask = uint8_t(((1u << take) - 1) << shift);
        check(start / 8 < bits.size(), "internal coverage range overflow");
        auto &byte = bits[size_t(start / 8)];
        check((byte & mask) == 0, "duplicate/overlapping submitted source range");
        byte |= mask; start += take; n -= take;
    }
}

class Loader {
    __strong id<MTLDevice> device_;
    __strong NSString *dir_;
    __strong NSDictionary *files_ = nil, *geometry_ = nil, *gpu_ = nil;
    Scene &s_;
    uint32_t sections_ = 0, groups_ = 0;
    uint64_t clusters_ = 0, quads_ = 0;
    std::vector<uint64_t> coverageBase_;
    id<MTLBuffer> buffer(uint64_t bytes, NSString *label) {
        bytes = std::max<uint64_t>(4, bytes);
        check(bytes <= device_.maxBufferLength && bytes <= MaxMetalBytes - s_.stats.metalBytes,
              "Metal buffer size/aggregate cap exceeded");
        check(device_.currentAllocatedSize <= MaxMetalBytes - bytes, "device Metal allocation exceeds 6 GiB cap");
        id<MTLBuffer> out = [device_ newBufferWithLength:NSUInteger(bytes) options:MTLResourceStorageModeShared];
        check(out != nil && out.contents != nullptr, "shared Metal buffer allocation failed: " + text(label));
        out.label = label;
        s_.stats.requestedBufferBytes += bytes;
        s_.stats.metalBytes += out.allocatedSize;
        s_.stats.peakDeviceBytes = std::max<uint64_t>(s_.stats.peakDeviceBytes, device_.currentAllocatedSize);
        check(s_.stats.metalBytes <= MaxMetalBytes && s_.stats.peakDeviceBytes <= MaxMetalBytes,
              "Metal allocatedSize exceeds 6 GiB cap");
        std::memset(out.contents, 0, size_t(bytes));
        return out;
    }
    void manifest() {
        File file([dir_ stringByAppendingPathComponent:@"complete.json"]);
        check(file.size() <= (1 << 20), "manifest exceeds 1 MiB cap");
        std::vector<uint8_t> data(size_t(file.size()));
        for (size_t at = 0; at < data.size();) {
            size_t n = file.read(data.data() + at, data.size() - at);
            check(n != 0, "truncated manifest"); at += n;
        }
        uint8_t tail; check(file.read(&tail, 1) == 0, "manifest changed during read");
        NSError *error = nil;
        id json = [NSJSONSerialization JSONObjectWithData:[NSData dataWithBytes:data.data() length:data.size()] options:0 error:&error];
        check(json != nil, "invalid manifest JSON: " + text(error.localizedDescription));
        s_.manifest = object(json, "manifest");
        check([s_.manifest[@"schema"] isEqual:@"farsight-scene-1"] && [s_.manifest[@"complete"] isEqual:@YES],
              "unsupported/incomplete snapshot");
        files_ = object(s_.manifest[@"files"], "files");
        check(files_.count > 0 && files_.count <= 256, "manifest file count cap");
        uint64_t raw = 0, gzip = 0;
        for (id name in files_) {
            check([name isKindOfClass:NSString.class], "invalid blob name");
            std::string n = text(name);
            check(n.size() > 3 && n.size() <= 100 && n.substr(n.size() - 3) == ".gz" &&
                  n[0] != '.' && n.find_first_not_of("abcdefghijklmnopqrstuvwxyz0123456789._-") == std::string::npos,
                  "unsafe blob name");
            NSDictionary *m = object(files_[name], "file");
            uint64_t size = number(m[@"rawBytes"], 0, MaxRawBytes, "rawBytes");
            check(size <= MaxRawBytes - raw, "aggregate raw input exceeds 8 GiB"); raw += size;
            size = number(m[@"gzipBytes"], 18, MaxRawBytes + (64 << 20), "gzipBytes");
            check(size <= MaxRawBytes + (64 << 20) - gzip, "aggregate gzip input cap"); gzip += size;
            check([m[@"rawSha256"] isKindOfClass:NSString.class], "missing rawSha256");
            std::string sha = text(m[@"rawSha256"]);
            check(sha.size() == 64 && sha.find_first_not_of("0123456789abcdef") == std::string::npos, "invalid rawSha256");
        }
        geometry_ = object(s_.manifest[@"geometry"], "geometry");
        sections_ = uint32_t(number(geometry_[@"selectedSections"], 1, MaxSections, "selectedSections"));
        groups_ = uint32_t(number(geometry_[@"groups"], 1, MaxGroups, "groups"));
        clusters_ = number(geometry_[@"submittedClusters"], 1, MaxRawBytes / 16, "submittedClusters");
        quads_ = number(geometry_[@"submittedQuads"], 1, MaxRawBytes / 48, "submittedQuads");
        id base = s_.manifest[@"cullBase"];
        check([base isKindOfClass:NSArray.class] && [base count] == 3, "cullBase size");
        for (int i = 0; i < 3; ++i) s_.cullBase[i] = int32_t(number(base[i], INT32_MIN, INT32_MAX, "cullBase"));
        NSDictionary *state = object(object(s_.manifest[@"lod"], "lod")[@"state"], "lod.state");
        check([state[@"idle"] isEqual:@YES], "capture is not settled");
        gpu_ = object(state[@"gpu"], "lod.state.gpu");
        check(number(gpu_[@"fading"], 0, MaxSections, "fading") == 0, "unsupported fading geometry");
        uint32_t arenas = uint32_t(number(gpu_[@"arenas"], 1, MaxArenas, "arenas"));
        // Tiny self-test captures may omit telemetry-only arenaReservedBytes.
        if (gpu_[@"arenaReservedBytes"])
            check(uint64_t(number(gpu_[@"arenaReservedBytes"], 0, int64_t(ArenaBytes * MaxArenas), "arenaReservedBytes")) == ArenaBytes * arenas,
                  "arena capacity differs from production 256 MiB ABI");
        s_.arenas.resize(arenas);
        for (uint32_t i = 0; i < arenas; ++i) s_.arenas[i].index = i;
        object(s_.manifest[@"visible"], "required LDV1 visible metadata");
        for (NSString *name in @[@"sections.lds.gz", @"groups.ldg.gz", @"visible.ldv.gz"])
            object(files_[name], "required geometry blob");
        s_.stats.selectedSections = sections_; s_.stats.groups = groups_;
    }
    void sections() {
        Blob b(dir_, files_, @"sections.lds.gz");
        check(b.u32() == 0x4c445331 && b.u32() == sections_, "LDS1 magic/count mismatch");
        check(sections_ <= b.remaining() / 140, "LDS1 count exceeds available headers");
        s_.sectionInfos = buffer(16ull * MaxSections, @"scene original section info");
        s_.sectionTable = buffer(32ull * MaxSections, @"scene original section table");
        s_.sections.reserve(sections_);
        s_.sectionIndex.assign(MaxSections, -1);
        coverageBase_.resize(size_t(MaxSections) * 3);
        std::array<std::vector<uint64_t>, MaxArenas> spans;
        int64_t last = -1;
        for (uint32_t i = 0; i < sections_; ++i) {
            uint8_t head[68]; b.read(head, sizeof(head));
            Section sec; sec.slot = be32(head);
            check(sec.slot < MaxSections && int64_t(sec.slot) > last, "LDS1 slot order/range"); last = sec.slot;
            for (int k = 0; k < 4; ++k) sec.origin[k] = si32(be32(head + 4 + 4 * k));
            check(sec.origin[3] >= 0 && sec.origin[3] <= 30, "LDS1 level outside bounds");
            uint32_t info[4] = {uint32_t(sec.origin[0]), uint32_t(sec.origin[1]), uint32_t(sec.origin[2]), 0x3f800000};
            check(std::memcmp(info, head + 20, 16) == 0, "LDS1 LE info origin/settled weight mismatch");
            float table[8]{};
            for (int k = 0; k < 3; ++k) {
                table[k] = float(si32(uint32_t(sec.origin[k]) - uint32_t(s_.cullBase[k])));
                table[k + 4] = float(si32(uint32_t(s_.cullBase[k]) - uint32_t(sec.origin[k])));
            }
            table[3] = float(1u << sec.origin[3]) / 16.0f;
            check(std::memcmp(table, head + 36, 32) == 0, "LDS1 LE table/cullBase/scale mismatch");
            std::memcpy(static_cast<uint8_t *>(s_.sectionInfos.contents) + 16ull * sec.slot, head + 20, 16);
            std::memcpy(static_cast<uint8_t *>(s_.sectionTable.contents) + 32ull * sec.slot, head + 36, 32);
            for (uint32_t layer = 0; layer < 3; ++layer) {
                uint8_t h[24]; b.read(h, sizeof(h));
                auto &span = sec.layers[layer];
                span.arena = si32(be32(h)); span.firstQuad = be32(h + 4);
                span.quadCount = be32(h + 8); span.stride = be32(h + 12);
                uint64_t bytes = be64(h + 16);
                if (span.arena == -1) {
                    check(span.firstQuad == 0 && span.quadCount == 0 && span.stride == 0 && bytes == 0, "LDS1 absent layer fields");
                    continue;
                }
                check(span.arena >= 0 && size_t(span.arena) < s_.arenas.size(), "LDS1 arena index outside manifest");
                check((span.stride == 48 || span.stride == 112) && span.quadCount > 0 && span.firstQuad % 8 == 0,
                      "LDS1 stride/count/alignment invalid");
                uint64_t end = uint64_t(span.firstQuad) + span.quadCount;
                check(end <= ArenaBytes / span.stride && bytes == uint64_t(span.quadCount) * span.stride,
                      "LDS1 payload byte count/arena range invalid");
                check(bytes <= b.remaining(), "LDS1 truncated layer payload");
                auto &arena = s_.arenas[span.arena];
                if (!arena.buffer) {
                    arena.stride = span.stride;
                    arena.buffer = buffer(ArenaBytes, [NSString stringWithFormat:@"scene original arena %d", span.arena]);
                    ++s_.stats.allocatedArenas;
                }
                check(arena.stride == span.stride, "LDS1 mixed strides in one arena");
                spans[span.arena].push_back((uint64_t(span.firstQuad) << 32) | end);
                coverageBase_[size_t(sec.slot) * 3 + layer] = s_.stats.payloadQuads;
                s_.stats.payloadQuads += span.quadCount;
                check(s_.stats.payloadQuads <= MaxRawBytes / 48, "payload count cap");
                s_.stats.payloadByLayer[layer] += span.quadCount;
                s_.stats.payloadByLevel[levelBucket(sec)] += span.quadCount;
                s_.stats.payloadByStride[span.stride == 112] += span.quadCount;
                b.read(static_cast<uint8_t *>(arena.buffer.contents) + uint64_t(span.firstQuad) * span.stride, bytes);
            }
            s_.sectionIndex[sec.slot] = int32_t(s_.sections.size());
            s_.sections.push_back(sec);
        }
        b.finish(s_.stats);
        for (auto &ranges : spans) {
            std::sort(ranges.begin(), ranges.end()); uint32_t end = 0;
            for (uint64_t range : ranges) {
                check((range >> 32) >= end, "LDS1 overlapping source allocations"); end = uint32_t(range);
            }
        }
        equalCounts(geometry_[@"payloadQuadsByLayer"], s_.stats.payloadByLayer, "payloadQuadsByLayer");
        equalCounts(geometry_[@"payloadQuadsByLevel"], s_.stats.payloadByLevel, "payloadQuadsByLevel");
    }
    const Section &section(uint32_t slot) const {
        check(slot < MaxSections && s_.sectionIndex[slot] >= 0, "cluster references missing section");
        return s_.sections[size_t(s_.sectionIndex[slot])];
    }
    void groups() {
        Blob b(dir_, files_, @"groups.ldg.gz");
        check(b.u32() == 0x4c444731 && b.u32() == groups_, "LDG1 magic/count mismatch");
        check(groups_ <= b.remaining() / 48, "LDG1 groups exceed available records");
        s_.groups.reserve(groups_);
        std::vector<uint8_t> covered(size_t((s_.stats.payloadQuads + 7) / 8)), referenced(MaxSections);
        for (uint32_t ordinal = 0; ordinal < groups_; ++ordinal) {
            uint8_t h[32]; b.read(h, sizeof(h));
            Group group; group.ordinal = ordinal; group.layer = be32(h); group.arena = be32(h + 4);
            group.stride = be32(h + 8); uint32_t fade = be32(h + 12); group.level = si32(be32(h + 16));
            group.clusterCount = be32(h + 20); group.submittedQuads = be64(h + 24);
            check(group.layer < 3 && group.arena < s_.arenas.size() && s_.arenas[group.arena].buffer &&
                  (group.stride == 48 || group.stride == 112) && group.stride == s_.arenas[group.arena].stride,
                  "LDG1 layer/arena/stride mismatch");
            check(fade == 0 && group.level >= -1 && group.level <= 30, "LDG1 fade/diagnostic level unsupported");
            check(group.clusterCount > 0 && group.clusterCount <= group.submittedQuads &&
                  group.submittedQuads <= uint64_t(group.clusterCount) * 64 && group.submittedQuads <= ArenaBytes / group.stride,
                  "LDG1 cluster/quad count outside bounds");
            uint64_t bytes = uint64_t(group.clusterCount) * 16;
            check(bytes <= b.remaining(), "LDG1 truncated clusters");
            group.clusters = buffer(bytes, @"scene original input clusters");
            b.read(group.clusters.contents, bytes);
            auto *items = static_cast<uint32_t *>(group.clusters.contents);
            uint64_t count = 0;
            for (uint32_t c = 0; c < group.clusterCount; ++c) {
                auto *p = items + size_t(c) * 4;
                for (int k = 0; k < 4; ++k) p[k] = be32(reinterpret_cast<uint8_t *>(p + k));
                uint32_t slot = p[1] & 0xfffff, n = ((p[1] >> 20) & 63) + 1, face = (p[1] >> 26) & 7;
                check(p[0] <= INT32_MAX && p[0] % 4 == 0 && (p[1] >> 30) == 0 && face < 7,
                      "LDG1 vertex/flags/facing invalid");
                if (p[1] & (1u << 29)) check(p[2] == 0 && p[3] == 0, "LDG1 unusable bounds nonzero");
                else {
                    check((p[2] >> 30) == 0 && (p[3] >> 30) == 0, "LDG1 bounds reserved bits");
                    for (uint32_t shift : {0u, 10u, 20u})
                        check(((p[2] >> shift) & 1023) <= ((p[3] >> shift) & 1023), "LDG1 inverted bounds");
                }
                const auto &sec = section(slot); const auto &span = sec.layers[group.layer];
                uint32_t first = p[0] / 4;
                check(span.arena == int32_t(group.arena) && span.stride == group.stride && first >= span.firstQuad &&
                      uint64_t(first) + n <= uint64_t(span.firstQuad) + span.quadCount, "LDG1 cluster outside matching source arena/range");
                check(group.level == -1 || group.level == sec.origin[3], "LDG1 diagnostic level mismatch");
                mark(covered, coverageBase_[size_t(slot) * 3 + group.layer] + first - span.firstQuad, n);
                referenced[slot] = 1; count += n;
                s_.stats.submittedByLayer[group.layer] += n;
                s_.stats.submittedByLevel[levelBucket(sec)] += n;
                s_.stats.submittedByFacing[face] += n;
            }
            check(count == group.submittedQuads, "LDG1 group quad sum mismatch");
            s_.stats.submittedClusters += group.clusterCount; s_.stats.submittedQuads += count;
            s_.groups.push_back(std::move(group));
        }
        b.finish(s_.stats);
        check(s_.stats.submittedClusters == clusters_ && s_.stats.submittedQuads == quads_, "LDG1 manifest totals mismatch");
        check(uint64_t(std::count(referenced.begin(), referenced.end(), uint8_t(1))) == sections_, "unreferenced selected sections");
        check(uint64_t(number(gpu_[@"drawnSections"], 0, MaxSections, "drawnSections")) == sections_ &&
              uint64_t(number(gpu_[@"drawnClusters"], 0, MaxRawBytes, "drawnClusters")) == clusters_ &&
              uint64_t(number(gpu_[@"drawnQuads"], 0, MaxRawBytes, "drawnQuads")) == quads_, "GPU manifest count mismatch");
        equalCounts(gpu_[@"drawnQuadsByLevel"], s_.stats.submittedByLevel, "drawnQuadsByLevel");
        equalCounts(gpu_[@"drawnQuadsBySlot"], s_.stats.submittedByFacing, "drawnQuadsBySlot");
        std::vector<uint64_t>().swap(coverageBase_);
    }
    void visible() {
        NSDictionary *meta = object(s_.manifest[@"visible"], "visible");
        check([meta[@"file"] isEqual:@"visible.ldv.gz"] &&
              uint64_t(number(meta[@"draws"], 1, MaxGroups, "visible.draws")) == groups_, "LDV1 manifest file/draw count");
        uint64_t expected = number(meta[@"quads"], 0, quads_, "visible.quads");
        Blob b(dir_, files_, @"visible.ldv.gz");
        check(b.u32() == 0x4c445631 && b.u32() == groups_, "LDV1 magic/count mismatch");
        s_.draws.reserve(groups_);
        for (auto &group : s_.groups) {
            uint8_t h[16]; b.read(h, sizeof(h));
            Draw draw; draw.groupOrdinal = be32(h); draw.layerCode = be32(h + 4);
            draw.quadCount = be32(h + 8); draw.usedClusterCount = be32(h + 12);
            check(draw.groupOrdinal == group.ordinal && draw.layerCode == group.layer + (group.stride == 48 ? 8 : 0), "LDV1 group order/layer code mismatch");
            check(draw.quadCount <= group.submittedQuads && draw.usedClusterCount <= group.clusterCount &&
                  ((draw.quadCount == 0) == (draw.usedClusterCount == 0)), "LDV1 visible/used count outside bounds");
            uint64_t entriesBytes = 4ull * draw.quadCount, itemsBytes = 8ull * draw.usedClusterCount;
            check(b.remaining() >= 20 && entriesBytes + itemsBytes <= b.remaining() - 20, "LDV1 truncated payloads");
            draw.argsBuffer = buffer(20, @"scene exact indexed indirect args");
            b.read(draw.argsBuffer.contents, 20);
            const auto *args = static_cast<const uint32_t *>(draw.argsBuffer.contents);
            check(uint64_t(args[0]) == 6ull * draw.quadCount && args[1] == 1 && args[2] == 0 && args[3] == 0 && args[4] == 0,
                  "LDV1 indirect args mismatch");
            draw.quadBuffer = buffer(entriesBytes, @"scene exact visible entries");
            draw.itemBuffer = buffer(itemsBytes, @"scene exact uint2 visible items");
            b.read(draw.quadBuffer.contents, entriesBytes); b.read(draw.itemBuffer.contents, itemsBytes);
            const auto *entries = static_cast<const uint32_t *>(draw.quadBuffer.contents);
            const auto *items = static_cast<const uint32_t *>(draw.itemBuffer.contents);
            const auto *clusters = static_cast<const uint32_t *>(group.clusters.contents);
            std::vector<uint64_t> masks(draw.usedClusterCount);
            uint32_t used = 0;
            for (uint32_t i = 0; i < draw.quadCount; ++i) {
                uint32_t c = entries[i] >> 6, local = entries[i] & 63;
                check(c < draw.usedClusterCount, "LDV1 packed cluster outside used count");
                const auto *input = clusters + size_t(c) * 4;
                check(local <= ((input[1] >> 20) & 63), "LDV1 local quad outside original group cluster");
                uint64_t mask = 1ull << local;
                check((masks[c] & mask) == 0, "LDV1 duplicate visible source quad"); masks[c] |= mask;
                uint32_t slot = input[1] & 0xfffff; const auto &sec = section(slot); const auto &span = sec.layers[group.layer];
                uint64_t source = uint64_t(input[0]) / 4 + local;
                check(span.arena == int32_t(group.arena) && source >= span.firstQuad && source < uint64_t(span.firstQuad) + span.quadCount,
                      "LDV1 source outside matching arena/section span");
                used = std::max(used, c + 1);
                ++s_.stats.visibleByLayer[group.layer]; ++s_.stats.visibleByLevel[levelBucket(sec)];
            }
            check(used == draw.usedClusterCount, "LDV1 usedClusterCount maximum mismatch");
            for (uint32_t c = 0; c < used; ++c) {
                uint32_t first = masks[c] ? clusters[size_t(c) * 4] : 0;
                uint32_t slot = masks[c] ? clusters[size_t(c) * 4 + 1] & 0xfffff : 0;
                check(items[size_t(c) * 2] == first && items[size_t(c) * 2 + 1] == slot, "LDV1 uint2 item source/zeroed hole mismatch");
            }
            s_.stats.visibleQuads += draw.quadCount;
            s_.draws.push_back(std::move(draw));
        }
        b.finish(s_.stats);
        check(s_.stats.visibleQuads == expected, "LDV1 visible manifest quad sum mismatch");
    }
public:
    Loader(id<MTLDevice> device, NSString *dir, Scene &scene) : device_(device), dir_(dir), s_(scene) {}
    void run() {
        check(device_ != nil && dir_ != nil && [dir_ isKindOfClass:NSString.class], "device/directory required");
        check(device_.currentAllocatedSize <= MaxMetalBytes, "device already exceeds 6 GiB Metal cap");
        manifest(); sections(); groups(); visible();
        s_.stats.validated = true;
    }
};
} // namespace detail

inline bool load(id<MTLDevice> device, NSString *directory, Scene &out, std::string &error) {
    @autoreleasepool {
        @try {
            try {
                Scene next;
                detail::Loader(device, directory, next).run();
                out = std::move(next); error.clear();
                return true;
            } catch (const std::exception &e) { error = e.what(); }
        } @catch (NSException *exception) {
            error = "Objective-C exception: " + detail::text(exception.reason);
        }
    }
    return false;
}
} // namespace lodscene
