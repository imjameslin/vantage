package net.minecraft.client.renderer.chunk;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure CPU draw preparation. Slot/ranges/cluster inputs must stay immutable; none are modified.
 * Results own their arrays (read-only after publication) and retain no nodes, sources or input arrays.
 */
final class LodPreparedItems {
    /** Request-local group lookup only; no cross-build cache or changed draw admission/order. */
    public static volatile boolean directGroupLookup = false;
    public static volatile long directGroupBuilds, directGroupHits;
    /** Same-session CPU admission A/B, sampled once per build. Uploaded list membership and source
     * metadata must remain immutable across requests; preparedRecords() may still change. */
    public static volatile boolean admissionCache = false;
    /** Cumulative ON-at-entry attempts, including failed/superseded jobs; updated once per build. */
    public static volatile long admissionRequests, admissionHitNodes, admissionMissNodes, admissionHitSections;
    public static volatile long admissionPublications, admissionSnapshotNodes, admissionSnapshotBytes;

    // A separate metadata allowance, not a change to result admission or a JVM heap measurement.
    // One published snapshot plus request-local candidates; no packed records or strong source keys.
    private static final long ADMISSION_CACHE_BYTES = 8L << 20, ADMISSION_ENTRY_BYTES = 128;
    private static long admissionTicket;
    private static Map<ListIdentity, AdmissionSummary> previousAdmission = Map.of();

    private static final class ListIdentity extends WeakReference<List<? extends Source>> {
        private final int hash;
        ListIdentity(List<? extends Source> sections) {
            super(sections);
            this.hash = System.identityHashCode(sections);
        }
        @Override public int hashCode() { return this.hash; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            List<? extends Source> sections = this.get();
            return sections != null && other instanceof ListIdentity key && sections == key.get();
        }
    }

    /** First-occurrence (layer, arena, words) triples, including present empty layers. Arrays are
     * private, frozen before publication, and contain no slots, bounds, payloads or source references. */
    private record AdmissionSummary(int sectionWords, int clusters, int[] groups) { }

    private static final class AdmissionRequest {
        final long ticket;
        final Map<ListIdentity, AdmissionSummary> previous;
        final Map<ListIdentity, AdmissionSummary> next = new HashMap<>();
        long bytes, hits, misses, hitSections;
        AdmissionRequest(long ticket, Map<ListIdentity, AdmissionSummary> previous) {
            this.ticket = ticket; this.previous = previous;
        }
        long remainingBytes() { return ADMISSION_CACHE_BYTES - this.bytes; }
        void remember(ListIdentity key, AdmissionSummary summary) {
            if (this.next.containsKey(key)) return;
            long charge = ADMISSION_ENTRY_BYTES + 4L * summary.groups.length;
            if (charge > this.remainingBytes()) return;
            this.next.put(key, summary);
            this.bytes += charge;
        }
    }

    // Only reference handoffs/counters take this lock. A cancelled worker owns its private candidate;
    // it cannot overwrite a snapshot after any newer request (including OFF) has begun.
    private static synchronized AdmissionRequest beginAdmission() {
        long ticket = ++admissionTicket;
        if (!admissionCache) {
            previousAdmission = Map.of();
            admissionSnapshotNodes = admissionSnapshotBytes = 0;
            return null;
        }
        return new AdmissionRequest(ticket, previousAdmission);
    }

    private static synchronized void finishAdmission(AdmissionRequest request, boolean completed) {
        admissionRequests++;
        admissionHitNodes += request.hits;
        admissionMissNodes += request.misses;
        admissionHitSections += request.hitSections;
        if (!completed || request.ticket != admissionTicket || !admissionCache) return;
        previousAdmission = Collections.unmodifiableMap(request.next);
        admissionSnapshotNodes = request.next.size();
        admissionSnapshotBytes = request.bytes;
        admissionPublications++;
    }

    interface Source {
        int slot();
        int arena(int layer);
        int firstQuad(int layer);
        int[] clusters(int layer);
        /** Optional immutable records of exactly these slot/ranges/clusters. The reference may be
         * published or cleared concurrently; one local read pins a complete cache object for copying.
         * Absence changes only CPU work. Neither path consults mutable geometry or native memory.
         */
        default LodSectionItems preparedRecords() { return null; }
    }

    record Node(List<? extends Source> sections, boolean fade, int level) { }
    record Key(int layer, int arena, boolean fade, int level) { }
    record Group(Key key, int[] items, long quads) { }

    static final class Result {
        final List<Group> groups;
        final int[] sectionItems;
        /** Scalar drawnSections, including empty sources; section record count is sectionItems.length / 4. */
        final int sectionCount, clusterCount;
        final int cachedSectionCount, cachedClusterCount;
        final long quadCount;
        final long[] quadsByLevel, quadsByFacing;
        final long chargedBytes;

        private Result(List<Group> groups, int[] sectionItems, int sectionCount, int clusterCount,
            long quadCount, long[] quadsByLevel, long[] quadsByFacing, long chargedBytes,
            int cachedSectionCount, int cachedClusterCount) {
            this.groups = Collections.unmodifiableList(groups);
            this.sectionItems = sectionItems;
            this.sectionCount = sectionCount;
            this.clusterCount = clusterCount;
            this.quadCount = quadCount;
            this.quadsByLevel = quadsByLevel;
            this.quadsByFacing = quadsByFacing;
            this.chargedBytes = chargedBytes;
            this.cachedSectionCount = cachedSectionCount;
            this.cachedClusterCount = cachedClusterCount;
        }
    }

    // Admission allowances, not a JVM heap measurement: stats, result/collection/array headers,
    // keys, batches, map entries/table, list/sort scratch and references, including empty groups.
    private static final long BASE_BYTES = 1024, GROUP_BYTES = 384;
    private static final int MAX_ARRAY_LENGTH = Integer.MAX_VALUE - 8;
    private static final boolean PREPARE_PROFILE = Boolean.getBoolean("mc.lod.prepareProfile");

    private static final class Batch {
        final Key key;
        int words, at;
        int[] items;
        long quads;
        Batch(Key key) { this.key = key; }
    }

    /** Charge = 1024 + 384 * groups + 16 * (clusters + nonempty section records).
     * The caller caps maxBytes. Capacity/size overflow returns null before payload allocation;
     * malformed metadata throws IllegalArgumentException. Only admitted group metadata is allocated
     * during preflight; cluster contents are read when filling the exactly sized owned arrays.
     */
    static Result build(List<Node> nodes, List<Key> existingGroupOrder, boolean diagnostics, long maxBytes) {
        AdmissionRequest admission = beginAdmission();
        LodPrepareTiming timing = PREPARE_PROFILE ? new LodPrepareTiming() : null;
        Result result = null;
        boolean returned = false;
        try {
            result = buildTimed(nodes, existingGroupOrder, diagnostics, maxBytes, timing, admission);
            returned = true;
            return result;
        } finally {
            if (admission != null) finishAdmission(admission, returned && result != null);
            if (PREPARE_PROFILE) timing.finish(returned, result);
        }
    }

    private static Result buildTimed(List<Node> nodes, List<Key> existingGroupOrder, boolean diagnostics,
        long maxBytes, LodPrepareTiming timing, AdmissionRequest admission) {
        if (maxBytes < BASE_BYTES) return null;
        Builder b = new Builder(diagnostics, maxBytes, admission);
        try {
            if (!b.admit(nodes, existingGroupOrder)) return null;
        } catch (ArithmeticException capacity) {
            return null;
        }
        if (PREPARE_PROFILE) timing.next(); // admission -> allocation
        b.allocate();
        if (PREPARE_PROFILE) timing.next(); // allocation -> fill
        for (Node node : nodes) b.appendNode(node);
        if (PREPARE_PROFILE) timing.next(); // fill -> finish
        return b.finish();
    }

    /** One request owns this scratch state. Keep hot per-section/layer work in small methods:
     * a first real scene has hundreds of thousands of sections but only one build() invocation.
     * A giant build loop otherwise spends its first requests waiting for large OSR compilations.
     * This changes neither input capture nor admission, scalar ordering, records or publication.
     */
    private static final class Builder {
        final boolean diagnostics;
        final long maxBytes;
        final AdmissionRequest admission;
        LinkedHashMap<Batch, Integer> nodeBatches;
        final LinkedHashMap<Key, Batch> batches = new LinkedHashMap<>();
        // Three references within the fixed scratch allowance. Adjacent sections often share
        // arena/fade/level; avoid allocating/hashing the same Key for every layer occurrence.
        // The map still owns insertion order, admission and every nonlocal lookup.
        Batch solidBatch, cutoutBatch, translucentBatch;
        // Fixed scratch, independent of arena IDs and scene size. Collisions use the owning map.
        final Batch[] directBatches = directGroupLookup ? new Batch[64] : null;
        long directHits;
        ArrayList<Batch> order;
        int sectionCount, clusterCount, sectionWords, sectionAt;
        int cachedSectionCount, cachedClusterCount;
        long chargedBytes, quadCount;
        int[] sectionItems;
        long[] byLevel, byFacing;
        // Section-local union scratch. Reset before every source, including empty/absent sources.
        int lx, ly, lz, hx, hy, hz;
        boolean any, unusable;

        Builder(boolean diagnostics, long maxBytes, AdmissionRequest admission) {
            this.diagnostics = diagnostics; this.maxBytes = maxBytes;
            this.admission = admission;
        }

        boolean admit(List<Node> nodes, List<Key> seeds) {
            this.chargedBytes = Math.addExact(BASE_BYTES, Math.multiplyExact(GROUP_BYTES, seeds.size()));
            if (this.chargedBytes > this.maxBytes || seeds.size() > MAX_ARRAY_LENGTH) return false;
            // Validate/count ALL sources before reading any payload, including oversized virtual lists.
            for (Node node : nodes) {
                if (node.level < 0) throw new IllegalArgumentException("Negative node level");
                this.sectionCount = Math.addExact(this.sectionCount, node.sections.size());
            }
            for (Key key : seeds) {
                if (key.layer < 0 || key.layer >= 3 || key.arena < 0 || key.level < -1)
                    throw new IllegalArgumentException("Invalid group key");
                if (this.batches.putIfAbsent(key, new Batch(key)) != null)
                    throw new IllegalArgumentException("Duplicate group key");
            }
            for (Node node : nodes) if (!this.admitNode(node)) return false;
            return true;
        }

        boolean admitNode(Node node) {
            int level = this.diagnostics ? node.level : -1;
            ListIdentity identity = this.admission == null ? null : new ListIdentity(node.sections);
            AdmissionSummary summary = this.admission == null ? null : this.admission.previous.get(identity);
            if (summary != null) {
                this.admission.hits++;
                this.admission.hitSections += node.sections.size();
                if (!this.admitSummary(summary, node.fade, level)) return false;
                this.admission.remember(identity, summary);
                return true;
            }
            if (this.admission != null) {
                this.admission.misses++;
                if (this.admission.remainingBytes() >= ADMISSION_ENTRY_BYTES)
                    this.nodeBatches = new LinkedHashMap<>();
            }
            int sectionsBefore = this.sectionWords, clustersBefore = this.clusterCount;
            for (Source source : node.sections)
                if (!this.admitSection(source, node.fade, level)) return false;
            if (this.nodeBatches != null) {
                int[] groups = new int[3 * this.nodeBatches.size()];
                int at = 0;
                for (var entry : this.nodeBatches.entrySet()) {
                    Batch batch = entry.getKey();
                    groups[at++] = batch.key.layer;
                    groups[at++] = batch.key.arena;
                    groups[at++] = batch.words - entry.getValue();
                }
                this.admission.remember(identity, new AdmissionSummary(this.sectionWords - sectionsBefore,
                    this.clusterCount - clustersBefore, groups));
                this.nodeBatches = null;
            }
            return true;
        }

        boolean admitSummary(AdmissionSummary summary, boolean fade, int level) {
            // This entire immutable node was validated by a completed build. All additions are
            // nonnegative: aggregating them preserves capacity/overflow rejection before the next node.
            this.clusterCount = Math.addExact(this.clusterCount, summary.clusters);
            this.sectionWords = Math.addExact(this.sectionWords, summary.sectionWords);
            this.chargedBytes = Math.addExact(this.chargedBytes, 16L * summary.clusters + 4L * summary.sectionWords);
            if (this.sectionWords > MAX_ARRAY_LENGTH || this.chargedBytes > this.maxBytes) return false;
            for (int at = 0; at < summary.groups.length; at += 3) {
                int layer = summary.groups[at], arena = summary.groups[at + 1];
                Batch batch = this.findBatch(layer, arena, fade, level);
                if (batch == null) {
                    this.chargedBytes = Math.addExact(this.chargedBytes, GROUP_BYTES);
                    if (this.chargedBytes > this.maxBytes || this.batches.size() == MAX_ARRAY_LENGTH) return false;
                    Key key = new Key(layer, arena, fade, level);
                    batch = new Batch(key); this.batches.put(key, batch);
                    this.rememberBatch(layer, batch);
                }
                batch.words = Math.addExact(batch.words, summary.groups[at + 2]);
                if (batch.words > MAX_ARRAY_LENGTH) return false;
            }
            return true;
        }

        boolean admitSection(Source source, boolean fade, int level) {
            if (source.slot() < 0 || source.slot() >= 1 << 20)
                throw new IllegalArgumentException("Invalid section slot");
            boolean present = false;
            for (int layer = 0; layer < 3; layer++) {
                int[] input = source.clusters(layer);
                int arena = source.arena(layer);
                if (input == null || arena < 0) continue;
                if (input.length % 3 != 0 || source.firstQuad(layer) < 0)
                    throw new IllegalArgumentException("Invalid cluster metadata");
                int count = input.length / 3;
                this.clusterCount = Math.addExact(this.clusterCount, count);
                this.chargedBytes = Math.addExact(this.chargedBytes, Math.multiplyExact(16L, count));
                if (this.chargedBytes > this.maxBytes) return false;
                Batch batch = this.findBatch(layer, arena, fade, level);
                if (batch == null) {
                    this.chargedBytes = Math.addExact(this.chargedBytes, GROUP_BYTES);
                    if (this.chargedBytes > this.maxBytes || this.batches.size() == MAX_ARRAY_LENGTH) return false;
                    Key key = new Key(layer, arena, fade, level);
                    batch = new Batch(key); this.batches.put(key, batch);
                    this.rememberBatch(layer, batch);
                }
                if (this.nodeBatches != null) {
                    this.nodeBatches.putIfAbsent(batch, batch.words);
                    if (ADMISSION_ENTRY_BYTES + 12L * this.nodeBatches.size() > this.admission.remainingBytes())
                        this.nodeBatches = null;
                }
                batch.words = Math.addExact(batch.words, Math.multiplyExact(count, 4));
                if (batch.words > MAX_ARRAY_LENGTH) return false;
                present |= count != 0;
            }
            if (present) {
                this.sectionWords = Math.addExact(this.sectionWords, 4);
                this.chargedBytes = Math.addExact(this.chargedBytes, 16L);
                if (this.chargedBytes > this.maxBytes || this.sectionWords > MAX_ARRAY_LENGTH) return false;
            }
            return true;
        }

        Batch findBatch(int layer, int arena, boolean fade, int level) {
            if (this.directBatches != null) {
                Batch hit = this.directBatches[(arena * 3 + layer) & 63];
                if (hit != null && hit.key.layer == layer && hit.key.arena == arena
                    && hit.key.fade == fade && hit.key.level == level) {
                    this.directHits++;
                    return hit;
                }
            }
            Batch last = layer == 0 ? this.solidBatch : layer == 1 ? this.cutoutBatch : this.translucentBatch;
            if (last != null && last.key.arena == arena && last.key.fade == fade && last.key.level == level) return last;
            Batch found = this.batches.get(new Key(layer, arena, fade, level));
            this.rememberBatch(layer, found);
            return found;
        }

        void rememberBatch(int layer, Batch batch) {
            if (this.directBatches != null && batch != null)
                this.directBatches[(batch.key.arena * 3 + layer) & 63] = batch;
            if (layer == 0) this.solidBatch = batch;
            else if (layer == 1) this.cutoutBatch = batch;
            else this.translucentBatch = batch;
        }

        void allocate() {
            // Stable layer/arena-only sort keeps fade/level ties in insertion order.
            this.order = new ArrayList<>(this.batches.values());
            this.order.sort((a, b) -> a.key.layer != b.key.layer ? Integer.compare(a.key.layer, b.key.layer)
                : Integer.compare(a.key.arena, b.key.arena));
            for (Batch batch : this.order) batch.items = new int[batch.words];
            this.sectionItems = new int[this.sectionWords];
            this.byLevel = new long[8]; this.byFacing = new long[8];
        }

        void appendNode(Node node) {
            int level = Math.min(7, node.level), groupLevel = this.diagnostics ? node.level : -1;
            for (Source source : node.sections) this.appendSection(source, node.fade, groupLevel, level);
        }

        void appendSection(Source source, boolean fade, int groupLevel, int level) {
            LodSectionItems cached = source.preparedRecords();
            if (cached != null) {
                this.appendCachedSection(source, cached, fade, groupLevel, level);
                return;
            }
            this.lx = this.ly = this.lz = 1023; this.hx = this.hy = this.hz = 0;
            this.any = this.unusable = false;
            int slot = source.slot();
            for (int layer = 0; layer < 3; layer++) {
                int[] input = source.clusters(layer);
                int arena = source.arena(layer);
                if (input == null || arena < 0) continue;
                Batch batch = this.findBatch(layer, arena, fade, groupLevel);
                this.appendLayer(input, 4 * source.firstQuad(layer), slot, level, batch);
            }
            if (this.any) {
                this.sectionItems[this.sectionAt] = 0;
                this.sectionItems[this.sectionAt + 1] = slot | (this.unusable ? 1 << 29 : 0);
                this.sectionItems[this.sectionAt + 2] = this.lx | this.ly << 10 | this.lz << 20;
                this.sectionItems[this.sectionAt + 3] = this.hx | this.hy << 10 | this.hz << 20;
                this.sectionAt += 4;
            }
        }

        void appendCachedSection(Source source, LodSectionItems cached, boolean fade, int groupLevel, int level) {
            this.cachedSectionCount++;
            if (cached.hasSection()) {
                cached.copySection(this.sectionItems, this.sectionAt);
                this.sectionAt += 4;
            }
            for (int layer = 0; layer < 3; layer++) {
                if (!cached.hasLayer(layer)) continue;
                Batch batch = this.findBatch(layer, source.arena(layer), fade, groupLevel);
                int count = cached.clusterCount(layer);
                cached.copyLayer(layer, batch.items, batch.at);
                batch.at += 4 * count;
                long quads = cached.quadCount(layer);
                batch.quads += quads;
                this.quadCount += quads; this.byLevel[level] += quads;
                this.cachedClusterCount += count;
            }
            for (int facing = 0; facing < 8; facing++) this.byFacing[facing] += cached.facingQuads(facing);
        }

        void appendLayer(int[] input, int base, int slot, int level, Batch batch) {
            int[] output = batch.items;
            int at = batch.at, lx = this.lx, ly = this.ly, lz = this.lz;
            int hx = this.hx, hy = this.hy, hz = this.hz;
            boolean unusable = this.unusable;
            long quads = 0;
            long[] facingCounts = this.byFacing;
            for (int c = 0; c < input.length; c += 3) {
                int c0 = input[c], lo = input[c + 1], hi = input[c + 2];
                int q = (c0 >>> 16 & 63) + 1, facing = c0 >>> 22 & 7;
                output[at] = base + 4 * (c0 & 0xFFFF);
                output[at + 1] = slot | (q - 1) << 20 | facing << 26 | (c0 >>> 25 & 1) << 29;
                output[at + 2] = lo; output[at + 3] = hi;
                at += 4; quads += q; facingCounts[facing] += q;
                unusable |= (c0 >>> 25 & 1) != 0;
                lx = Math.min(lx, lo & 1023);
                ly = Math.min(ly, lo >>> 10 & 1023);
                lz = Math.min(lz, lo >>> 20 & 1023);
                hx = Math.max(hx, hi & 1023);
                hy = Math.max(hy, hi >>> 10 & 1023);
                hz = Math.max(hz, hi >>> 20 & 1023);
            }
            batch.at = at; batch.quads += quads;
            this.quadCount += quads; this.byLevel[level] += quads;
            this.any |= input.length != 0; this.unusable = unusable;
            this.lx = lx; this.ly = ly; this.lz = lz;
            this.hx = hx; this.hy = hy; this.hz = hz;
        }

        Result finish() {
            if (this.directBatches != null) noteDirectBuild(this.directHits);
            var groups = new ArrayList<Group>(this.order.size());
            for (Batch batch : this.order) groups.add(new Group(batch.key, batch.items, batch.quads));
            return new Result(groups, this.sectionItems, this.sectionCount, this.clusterCount,
                this.quadCount, this.byLevel, this.byFacing, this.chargedBytes,
                this.cachedSectionCount, this.cachedClusterCount);
        }
    }

    private static synchronized void noteDirectBuild(long hits) {
        directGroupBuilds++;
        directGroupHits += hits;
    }
}
