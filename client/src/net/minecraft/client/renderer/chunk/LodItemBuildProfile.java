package net.minecraft.client.renderer.chunk;

import java.util.Arrays;

/** Render-thread-only, opt-in wall-time accounting. No clocks or per-build allocations here.
 *
 * Coverage starts at frame()'s existing build timer and ends at its existing success timer, or at catch entry on failure.
 * Four adjacent intervals include profiler bookkeeping, but exclude finish()/stats aggregation and failure cleanup.
 * updateFades() before buildItems, uploads, GPU culling and GPU execution are outside this measurement.
 * Selection includes resets, chunk checks, membership/fade mutations and visibility writes. Preparation includes lazy
 * cache misses, scalar packing or cached array copies, group lookup/growth and payload sizing; no per-section timing.
 * Ring stages include allocation/retirement and CPU writes to mapped buffers, NOT GPU transfer/execution time.
 *
 * Completed and failed attempts never mix. Failed phaseNs include the elapsed prefix of the failing phase; entered and
 * completed phase counts identify that partial interval. Selection/preparation output counts are reported only after
 * their stage's work finishes. Allocation counters count returned createBuffer calls (full new capacity, not net growth).
 * Copy counters count only returned put() calls; a throwing call may have written an unknown prefix, which is excluded.
 * Prepared record counts describe logical output payload, not total heap traffic (array growth/cache creation omitted).
 * Zero item bytes still execute SECTION_RING; ITEM_RING is unentered, with zero time/bytes for that attempt.
 */
final class LodItemBuildProfile {
    static final int SELECTION_FADES = 0, PREPARE_APPEND = 1, SECTION_RING = 2, ITEM_RING = 3;
    private static final String[] PHASE_NAMES = {"selectionFades", "prepareAppend", "sectionRing", "itemRing"};
    private static final String[] COUNT_NAMES = {
        "selectionNodes", "orderedNodes", "preparedSections", "sectionRecords", "clusterRecords", "quads", "nonemptyGroups",
        "sectionAllocations", "sectionAllocatedCapacityBytes", "itemAllocations", "itemAllocatedCapacityBytes",
        "sectionCompletedCopyBytes", "itemCompletedCopyBytes"
    };
    private static final int SECTION_RECORDS = 3, CLUSTER_RECORDS = 4;
    private final Bucket[] completed = buckets(), failed = buckets();
    private final long[] phaseNs = new long[4], counts = new long[COUNT_NAMES.length];
    private boolean active;
    private int mode, phase;
    private long start, boundary;

    static LodItemBuildProfile create(boolean enabled) {
        return enabled ? new LodItemBuildProfile() : null;
    }

    private static Bucket[] buckets() {
        return new Bucket[]{new Bucket(), new Bucket(), new Bucket(), new Bucket()};
    }

    void begin(long now, boolean cacheItems) {
        if (this.active) throw new IllegalStateException("Nested item build profile");
        Arrays.fill(this.phaseNs, 0L);
        Arrays.fill(this.counts, 0L);
        this.mode = cacheItems ? 2 : 0;
        this.phase = SELECTION_FADES;
        this.start = this.boundary = now;
        this.active = true;
    }

    /** Uses buildItems' already sampled flag; never reads a volatile setting itself. */
    void groups(boolean cacheGroups) {
        this.mode = (this.mode & 2) | (cacheGroups ? 1 : 0);
    }

    void advance(int next, long now) {
        if (!this.active || next != this.phase + 1 || next > ITEM_RING)
            throw new IllegalStateException("Invalid item build phase");
        this.phaseNs[this.phase] += now - this.boundary;
        this.boundary = now;
        this.phase = next;
    }

    void selected(long nodes, long ordered) {
        this.counts[0] = nodes;
        this.counts[1] = ordered;
    }

    void prepared(long sections, long sectionRecords, long clusters, long quads, long groups) {
        this.counts[2] = sections;
        this.counts[SECTION_RECORDS] = sectionRecords;
        this.counts[CLUSTER_RECORDS] = clusters;
        this.counts[5] = quads;
        this.counts[6] = groups;
    }

    void allocated(boolean items, long capacityBytes) {
        int at = items ? 9 : 7;
        this.counts[at]++;
        this.counts[at + 1] += capacityBytes;
    }

    void copied(boolean items, long bytes) {
        this.counts[items ? 12 : 11] += bytes;
    }

    void finish(boolean success, long now) {
        // In particular, don't replace an original exception if begin() never became active.
        if (!this.active) return;
        this.phaseNs[this.phase] += now - this.boundary;
        Bucket out = (success ? this.completed : this.failed)[this.mode];
        out.attempts++;
        out.totalNs += now - this.start;
        for (int p = 0; p <= this.phase; p++) {
            out.phaseNs[p] += this.phaseNs[p];
            out.phaseEntered[p]++;
            if (p < this.phase || success) out.phaseCompleted[p]++;
        }
        for (int i = 0; i < this.counts.length; i++) out.counts[i] += this.counts[i];
        this.active = false;
    }

    /** Only used while taking existing Lod stats snapshots, never from the measured build. */
    String statsJson() {
        StringBuilder out = new StringBuilder(4096);
        out.append("{\"enabled\":true,\"version\":1,\"clock\":\"wallNs\",\"modeEncoding\":\"items*2+groups\",\"phaseOrder\":[");
        for (int p = 0; p < PHASE_NAMES.length; p++) {
            if (p != 0) out.append(',');
            out.append('"').append(PHASE_NAMES[p]).append('"');
        }
        out.append("],\"failedTimesArePartial\":true,\"copyBytesExcludeThrowingCalls\":true,\"modes\":[");
        for (int m = 0; m < 4; m++) {
            if (m != 0) out.append(',');
            out.append("{\"mode\":").append(m).append(",\"completed\":");
            this.completed[m].appendJson(out);
            out.append(",\"failed\":");
            this.failed[m].appendJson(out);
            out.append('}');
        }
        return out.append("]}").toString();
    }

    /** Read-only scalar access for the dependency-free accounting test. */
    long attempts(boolean success, int mode) { return (success ? this.completed : this.failed)[mode].attempts; }
    long totalNs(boolean success, int mode) { return (success ? this.completed : this.failed)[mode].totalNs; }
    long phaseNs(boolean success, int mode, int phase) { return (success ? this.completed : this.failed)[mode].phaseNs[phase]; }
    long entered(boolean success, int mode, int phase) { return (success ? this.completed : this.failed)[mode].phaseEntered[phase]; }
    long completed(boolean success, int mode, int phase) { return (success ? this.completed : this.failed)[mode].phaseCompleted[phase]; }
    long count(boolean success, int mode, String name) {
        for (int i = 0; i < COUNT_NAMES.length; i++)
            if (COUNT_NAMES[i].equals(name)) return (success ? this.completed : this.failed)[mode].counts[i];
        throw new IllegalArgumentException(name);
    }

    private static final class Bucket {
        long attempts, totalNs;
        final long[] phaseNs = new long[4], phaseEntered = new long[4], phaseCompleted = new long[4];
        final long[] counts = new long[COUNT_NAMES.length];

        void appendJson(StringBuilder out) {
            out.append("{\"attempts\":").append(this.attempts).append(",\"totalNs\":").append(this.totalNs)
                .append(",\"phaseNs\":").append(Arrays.toString(this.phaseNs))
                .append(",\"phaseEntered\":").append(Arrays.toString(this.phaseEntered))
                .append(",\"phaseCompleted\":").append(Arrays.toString(this.phaseCompleted));
            for (int i = 0; i < COUNT_NAMES.length; i++) out.append(",\"").append(COUNT_NAMES[i]).append("\":").append(this.counts[i]);
            out.append(",\"preparedSectionPayloadBytes\":").append(16L * this.counts[SECTION_RECORDS])
                .append(",\"preparedClusterPayloadBytes\":").append(16L * this.counts[CLUSTER_RECORDS]).append('}');
        }
    }
}
