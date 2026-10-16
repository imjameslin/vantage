#pragma once
// Offscreen diagnostic helper; ObjC++17/ARC. Include after/instead of scene_data.
//
// lodcompact::Filter f(dev, scene);
// std::vector<id<MTLBuffer>> masks; // one uint[original clusterCount] per group
// f.encode(cb, masks);            // after the mask producer's encoder has ended
// bind(f.groupQuads(ordinal), originalDraw.itemBuffer, original arena/sections);
// drawIndexedIndirect(f.groupArgs(ordinal), 0);
//
// Optional per-ENTRY microgroup mapping (source geometry/order stays original):
// lodcompact::Filter mapped(dev, scene, memberships, maskCounts);
// memberships[g] is a CPU-readable, tracked uint[original draw.quadCount],
// indexed by input ENTRY position, not encoded source cluster/quad ID.
// maskCounts[g] >= 1 is the independently chosen hidden-mask length for group g.
// Both vectors match original scene.groups.size(); absent/empty draws may use
// nil memberships, but still require maskCounts[g] >= 1 and a mask at encode.
// Constructor validates all membership IDs < maskCounts[g] using CPU contents;
// caller must supply CPU-authored/coherent immutable data, retained unchanged
// for the filter's lifetime. No synchronization/readback is done by the helper.
// Mapped membership is bound at compute buffer 7; only mapped kernels read it.
// The legacy constructor still selects masks by encodedEntry >> 6.
//
// Exactly mask value 1 removes an entry. Zero retains it; unexpected values
// conservatively retain it too. No layer special case: give water zero masks.
// Stable within each group, including repeated/nonmonotonic cluster references.
// Caller keeps original group/draw order, item tables and arena addresses.
// groupCapacity() is the number of original groups; groupCapacity(i) is that
// group's maximum OUTPUT QUAD count (the captured draw's input quad count).
// Missing/empty draws have capacity 0 and freshly written args {0,1,0,0,0}.
// Output tail past args.indexCount/6 is unspecified. All args are 20-byte LE
// MTLDrawIndexedPrimitivesIndirectArguments; baseVertex/baseInstance stay zero.
//
// No queue, commit, wait, readback, CPU mask scan or scene mutation. Three GPU
// stages: block counts, per-group prefix, stable scatter. Explicit buffer
// barriers separate them. Inputs/masks/output must be tracked Metal resources.
// Retains input buffers. Scene must have passed lodscene::load. Caller owns an
// uncommitted command buffer on this device and ends all other encoders first.
// Do not overlap uses across command buffers: masks must not change between
// count/scatter, and prior draws must finish reading output before its reuse.
// Construction compiles three PSOs; exclusive timing GPU lock is caller-owned.
// Added allocations are <128 MiB; device.currentAllocatedSize <=6 GiB is checked
// during construction and on every encode (including the caller's resources).

#include "lod_scene_data.h"

namespace lodcompact {
namespace detail {
inline void need(bool ok, const std::string& reason) {
    if (!ok) throw std::runtime_error("lodcompact: " + reason);
}
inline constexpr uint32_t BlockSize = 256;
inline constexpr uint64_t MaxAdded = 128ull << 20;
struct Params { uint32_t count, clusters, blocks, mapped; };
static_assert(sizeof(Params) == 16, "MSL compaction parameter ABI");
inline NSString* source() {
    return @R"MSL(
#include <metal_stdlib>
using namespace metal;
struct Params { uint count, clusters, blocks, mapped; };
// Out-of-range cluster references cannot occur in a validated scene. Guard
// anyway, so a damaged list never reads outside the mask and fails open.
inline uint keepEntry(uint entry, uint i, device const uint* hidden,
                      device const uint* memberships, constant Params& p) {
    uint cluster = entry >> 6u;
    if (p.mapped) cluster = memberships[i];
    return cluster >= p.clusters ? 1u : uint(hidden[cluster] != 1u);
}
kernel void lodcompact_count(device const uint* input [[buffer(0)]],
                             device const uint* hidden [[buffer(1)]],
                             device uint* counts [[buffer(2)]], constant Params& p [[buffer(6)]],
                             device const uint* memberships [[buffer(7)]],
                             uint block [[threadgroup_position_in_grid]],
                             uint tid [[thread_index_in_threadgroup]],
                             uint lane [[thread_index_in_simdgroup]],
                             uint sg [[simdgroup_index_in_threadgroup]],
                             uint sw [[threads_per_simdgroup]]) {
    threadgroup uint sums[256];
    uint i = block * 256u + tid;
    uint keep = i < p.count ? keepEntry(input[i], i, hidden, memberships, p) : 0u;
    uint sum = simd_sum(keep);
    if (lane == 0u) sums[sg] = sum;
    threadgroup_barrier(mem_flags::mem_threadgroup);
    if (tid == 0u) {
        uint total = 0u;
        for (uint j = 0u; j < 256u / sw; ++j) total += sums[j];
        counts[block] = total;
    }
}
kernel void lodcompact_prefix(device const uint* counts [[buffer(2)]],
                              device uint* offsets [[buffer(3)]],
                              device uint* args [[buffer(5)]], constant Params& p [[buffer(6)]],
                              uint tid [[thread_index_in_threadgroup]],
                              uint lane [[thread_index_in_simdgroup]],
                              uint sg [[simdgroup_index_in_threadgroup]],
                              uint sw [[threads_per_simdgroup]]) {
    threadgroup uint sums[256];
    threadgroup uint carry;
    if (tid == 0u) carry = 0u;
    threadgroup_barrier(mem_flags::mem_threadgroup);
    for (uint base = 0u; base < p.blocks; base += 256u) {
        uint i = base + tid;
        uint value = i < p.blocks ? counts[i] : 0u;
        uint local = simd_prefix_exclusive_sum(value);
        uint sum = simd_sum(value);
        if (lane == 0u) sums[sg] = sum;
        threadgroup_barrier(mem_flags::mem_threadgroup);
        uint offset = carry + local;
        for (uint j = 0u; j < sg; ++j) offset += sums[j];
        if (i < p.blocks) offsets[i] = offset;
        // Every reader must consume old carry/sums before they are updated.
        threadgroup_barrier(mem_flags::mem_threadgroup);
        if (tid == 0u) {
            uint total = 0u;
            for (uint j = 0u; j < 256u / sw; ++j) total += sums[j];
            carry += total;
        }
        threadgroup_barrier(mem_flags::mem_threadgroup);
    }
    if (tid == 0u) {
        args[0] = carry * 6u;
        args[1] = 1u; args[2] = 0u; args[3] = 0u; args[4] = 0u;
    }
}
kernel void lodcompact_scatter(device const uint* input [[buffer(0)]],
                               device const uint* hidden [[buffer(1)]],
                               device const uint* offsets [[buffer(3)]],
                               device uint* output [[buffer(4)]], constant Params& p [[buffer(6)]],
                               device const uint* memberships [[buffer(7)]],
                               uint block [[threadgroup_position_in_grid]],
                               uint tid [[thread_index_in_threadgroup]],
                               uint lane [[thread_index_in_simdgroup]],
                               uint sg [[simdgroup_index_in_threadgroup]],
                               uint sw [[threads_per_simdgroup]]) {
    threadgroup uint sums[256];
    uint i = block * 256u + tid;
    uint entry = i < p.count ? input[i] : 0u;
    uint keep = i < p.count ? keepEntry(entry, i, hidden, memberships, p) : 0u;
    uint local = simd_prefix_exclusive_sum(keep);
    uint sum = simd_sum(keep);
    if (lane == 0u) sums[sg] = sum;
    threadgroup_barrier(mem_flags::mem_threadgroup);
    uint offset = offsets[block] + local;
    for (uint j = 0u; j < sg; ++j) offset += sums[j];
    if (keep) output[offset] = entry;
    (void)sw;
}
)MSL";
}
} // namespace detail

class Filter {
    struct Job {
        detail::Params params{};
        __strong id<MTLBuffer> input = nil, output = nil, scratch = nil, args = nil, membership = nil;
        NSUInteger offsetsAt = 0;
        bool hasDraw = false;
    };
    __strong id<MTLDevice> device_;
    __strong id<MTLComputePipelineState> count_, prefix_, scatter_;
    __strong id<MTLBuffer> dummy_;
    std::vector<Job> jobs_;
    uint64_t allocated_ = 0;
    void budget() const {
        detail::need(allocated_ < detail::MaxAdded && device_.currentAllocatedSize <= lodscene::MaxMetalBytes,
                     "GPU allocation budget exceeded");
    }
    static uint64_t rounded(uint64_t n) { return (std::max(4ull, n) + 16383ull) & ~16383ull; }
    id<MTLBuffer> buffer(NSUInteger bytes, NSString* label) {
        id<MTLBuffer> b = [device_ newBufferWithLength:std::max<NSUInteger>(4, bytes)
                                            options:MTLResourceStorageModeShared | MTLResourceHazardTrackingModeTracked];
        detail::need(b != nil, "buffer allocation");
        b.label = label; allocated_ += b.allocatedSize; budget();
        return b;
    }
    void validBuffer(id<MTLBuffer> b, uint64_t minBytes, const char* what) const {
        detail::need(b && b.device == device_ && b.length >= minBytes && (b.length & 3u) == 0u &&
                     b.hazardTrackingMode != MTLHazardTrackingModeUntracked, what);
    }
    const Job& job(size_t ordinal) const {
        detail::need(ordinal < jobs_.size(), "group ordinal outside capacity"); return jobs_[ordinal];
    }
    Filter(id<MTLDevice> dev, const lodscene::Scene& scene,
           const std::vector<id<MTLBuffer>>* memberships, const std::vector<uint32_t>* maskCounts) : device_(dev) {
        detail::need(dev && scene.stats.validated, "expected validated scene/device");
        detail::need(scene.groups.size() <= lodscene::MaxGroups && scene.draws.size() <= scene.groups.size(), "group/draw count ABI");
        if (memberships)
            detail::need(maskCounts && memberships->size() == scene.groups.size() && maskCounts->size() == scene.groups.size(),
                         "membership/mask-count vectors must match original group count");
        jobs_.resize(scene.groups.size());
        for (size_t i = 0; i < scene.groups.size(); ++i) {
            const auto& g = scene.groups[i];
            detail::need(g.ordinal == i && g.layer <= 2 && g.clusterCount <= (1u << 26), "original group ABI");
            jobs_[i].params.clusters = g.clusterCount;
        }
        for (const auto& d : scene.draws) {
            detail::need(d.groupOrdinal < jobs_.size(), "draw group ordinal");
            auto& j = jobs_[d.groupOrdinal];
            detail::need(!j.hasDraw && d.quadCount <= UINT32_MAX / 6u && d.usedClusterCount <= j.params.clusters,
                         "duplicate draw/count ABI");
            validBuffer(d.quadBuffer, 4ull * d.quadCount, "input quad buffer ABI/device/hazard mode");
            j.hasDraw = true; j.input = d.quadBuffer; j.params.count = d.quadCount;
            j.params.blocks = (d.quadCount + detail::BlockSize - 1u) / detail::BlockSize;
        }
        // Validate the complete mapped ABI before compilation/allocation. Keep
        // the ORIGINAL cluster count above for the original draw/item ABI checks.
        if (memberships) for (size_t i = 0; i < jobs_.size(); ++i) {
            auto& j = jobs_[i];
            detail::need((*maskCounts)[i] >= 1, "mapped mask count must be at least one");
            auto b = (*memberships)[i];
            if (b || j.params.count) {
                validBuffer(b, 4ull * j.params.count, "membership uint buffer ABI/device/hazard mode");
                detail::need(b.storageMode != MTLStorageModePrivate && b.storageMode != MTLStorageModeMemoryless,
                             "membership CPU contents required");
                const uint32_t* ids = static_cast<const uint32_t*>(b.contents);
                detail::need(ids != nullptr, "membership CPU contents required");
                for (uint32_t n = 0; n < j.params.count; ++n)
                    detail::need(ids[n] < (*maskCounts)[i], "membership ID outside mapped mask count");
            }
            j.membership = b; j.params.clusters = (*maskCounts)[i]; j.params.mapped = 1;
        }
        uint64_t estimate = rounded(4);
        for (auto& j : jobs_) {
            j.offsetsAt = (4ull * j.params.blocks + 255ull) & ~255ull;
            estimate += rounded(4ull * j.params.count) + rounded(j.offsetsAt + 4ull * j.params.blocks) + rounded(20);
        }
        detail::need(estimate < detail::MaxAdded && dev.currentAllocatedSize <= lodscene::MaxMetalBytes - estimate,
                     "insufficient GPU allocation budget");
        NSError* error = nil; MTLCompileOptions* options = [MTLCompileOptions new];
        options.languageVersion = MTLLanguageVersion3_2; options.mathMode = MTLMathModeSafe;
        id<MTLLibrary> library = [dev newLibraryWithSource:detail::source() options:options error:&error];
        detail::need(library != nil, lodscene::detail::text(error.description));
        auto pipeline = [&](NSString* name) {
            id<MTLComputePipelineState> p = [dev newComputePipelineStateWithFunction:[library newFunctionWithName:name] error:&error];
            detail::need(p != nil, lodscene::detail::text(error.description));
            detail::need(p.maxTotalThreadsPerThreadgroup >= detail::BlockSize && p.threadExecutionWidth > 0 &&
                         detail::BlockSize % p.threadExecutionWidth == 0, "unsupported compute threadgroup width");
            return p;
        };
        count_ = pipeline(@"lodcompact_count"); prefix_ = pipeline(@"lodcompact_prefix"); scatter_ = pipeline(@"lodcompact_scatter");
        dummy_ = buffer(4, @"lodcompact empty binding"); memset(dummy_.contents, 0, dummy_.length);
        for (auto& j : jobs_) {
            if (!j.input) j.input = dummy_;
            j.output = buffer(4ull * j.params.count, @"lodcompact stable output quad entries");
            j.scratch = buffer(j.offsetsAt + 4ull * j.params.blocks, @"lodcompact block counts/prefixes");
            j.args = buffer(20, @"lodcompact indexed indirect arguments");
            uint32_t initial[5] = {0, 1, 0, 0, 0}; memcpy(j.args.contents, initial, sizeof(initial));
        }
        budget();
    }
public:
    Filter(id<MTLDevice> dev, const lodscene::Scene& scene) : Filter(dev, scene, nullptr, nullptr) {}
    Filter(id<MTLDevice> dev, const lodscene::Scene& scene,
           const std::vector<id<MTLBuffer>>& memberships, const std::vector<uint32_t>& maskCounts)
        : Filter(dev, scene, &memberships, &maskCounts) {}
    Filter(const Filter&) = delete; Filter& operator=(const Filter&) = delete;
    size_t groupCapacity() const { return jobs_.size(); }
    uint32_t groupCapacity(size_t ordinal) const { return job(ordinal).params.count; }
    uint64_t allocatedBytes() const { return allocated_; }
    id<MTLBuffer> groupQuads(size_t ordinal) const { return job(ordinal).output; }
    id<MTLBuffer> groupArgs(size_t ordinal) const { return job(ordinal).args; }

    void encode(id<MTLCommandBuffer> cb, const std::vector<id<MTLBuffer>>& groupHidden) {
        detail::need(cb && cb.device == device_ && cb.status < MTLCommandBufferStatusCommitted,
                     "expected uncommitted command buffer/device");
        detail::need(groupHidden.size() == jobs_.size(), "mask vector must match original group count");
        // Validate the WHOLE mask ABI before appending any encoder/command.
        for (size_t i = 0; i < jobs_.size(); ++i) {
            auto b = groupHidden[i];
            if (!b && jobs_[i].params.clusters == 0) continue;
            validBuffer(b, 4ull * jobs_[i].params.clusters, "hidden uint mask ABI/device/hazard mode");
            for (const auto& j : jobs_)
                detail::need(b != j.output && b != j.scratch && b != j.args, "hidden mask aliases mutable filter output");
        }
        budget();
        if (jobs_.empty()) return;
        id<MTLComputeCommandEncoder> e = [cb computeCommandEncoder]; detail::need(e != nil, "compute encoder");
        e.label = @"lodcompact stable count-prefix-scatter";
        const MTLSize threads = MTLSizeMake(detail::BlockSize, 1, 1);
        auto bind = [&](const Job& j, id<MTLBuffer> hidden) {
            [e setBuffer:j.input offset:0 atIndex:0]; [e setBuffer:hidden ?: dummy_ offset:0 atIndex:1];
            [e setBuffer:j.scratch offset:0 atIndex:2]; [e setBuffer:j.scratch offset:j.offsetsAt atIndex:3];
            [e setBuffer:j.output offset:0 atIndex:4]; [e setBuffer:j.args offset:0 atIndex:5];
            [e setBytes:&j.params length:sizeof(j.params) atIndex:6];
            [e setBuffer:j.membership ?: dummy_ offset:0 atIndex:7];
        };
        [e setComputePipelineState:count_];
        for (size_t i = 0; i < jobs_.size(); ++i) if (jobs_[i].params.blocks) {
            bind(jobs_[i], groupHidden[i]);
            [e dispatchThreadgroups:MTLSizeMake(jobs_[i].params.blocks, 1, 1) threadsPerThreadgroup:threads];
        }
        [e memoryBarrierWithScope:MTLBarrierScopeBuffers];
        [e setComputePipelineState:prefix_];
        for (size_t i = 0; i < jobs_.size(); ++i) {
            bind(jobs_[i], groupHidden[i]);
            [e dispatchThreadgroups:MTLSizeMake(1, 1, 1) threadsPerThreadgroup:threads];
        }
        [e memoryBarrierWithScope:MTLBarrierScopeBuffers];
        [e setComputePipelineState:scatter_];
        for (size_t i = 0; i < jobs_.size(); ++i) if (jobs_[i].params.blocks) {
            bind(jobs_[i], groupHidden[i]);
            [e dispatchThreadgroups:MTLSizeMake(jobs_[i].params.blocks, 1, 1) threadsPerThreadgroup:threads];
        }
        [e endEncoding];
    }
};
} // namespace lodcompact
