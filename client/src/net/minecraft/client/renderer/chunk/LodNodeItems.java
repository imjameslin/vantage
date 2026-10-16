package net.minecraft.client.renderer.chunk;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;

/** Immutable CPU packet. No node, section, arena, source-array or GPU-resource references are retained.
 * Batches are in FIRST OCCURRENCE order of (layer, arena), including present-but-empty layers.
 * Each batch concatenates its sections in input order. Fade/diagnostic level are deliberately not captured.
 */
final class LodNodeItems {
    interface Source {
        int itemArena(int layer);
        /** -1 for an absent layer; zero for a present empty layer. Read without preparing section packets. */
        int itemClusterCount(int layer);
        LodSectionItems itemRecords();
    }

    /** Exact packet charge from immutable raw lengths, without allocating arrays or preparing section caches. */
    static long chargeFor(List<? extends Source> source, int arenaCount) {
        if (arenaCount < 0 || arenaCount > 64) throw new IllegalArgumentException("Arena count");
        long a0 = 0, a1 = 0, a2 = 0, clusters = 0, sectionRecords = 0;
        int batches = 0;
        for (int i = 0; i < source.size(); i++) {
            Source s = source.get(i);
            boolean any = false;
            for (int layer = 0; layer < 3; layer++) {
                int count = s.itemClusterCount(layer);
                if (count < 0) continue;
                int arena = s.itemArena(layer);
                if (arena < 0 || arena >= arenaCount) throw new IllegalArgumentException("Arena identity");
                long bit = 1L << arena, seen = layer == 0 ? a0 : layer == 1 ? a1 : a2;
                if ((seen & bit) == 0) batches++;
                if (layer == 0) a0 |= bit; else if (layer == 1) a1 |= bit; else a2 |= bit;
                clusters += count; any |= count != 0;
            }
            if (any) sectionRecords++;
        }
        return 320L + 16L * (clusters + sectionRecords) + 80L * batches;
    }

    private final int[] sections, layers, arenas;
    private final int[][] items;
    private final long[] quads, facing;
    final int sectionCount, clusterCount;
    final long quadCount, payloadBytes, chargedBytes;

    private LodNodeItems(int sourceSections, int[] sections, int[] layers, int[] arenas, int[][] items,
        long[] quads, long[] facing, int clusters, long totalQuads, long payload, long charge) {
        this.sectionCount = sourceSections;
        this.sections = sections; this.layers = layers; this.arenas = arenas; this.items = items;
        this.quads = quads; this.facing = facing; this.clusterCount = clusters; this.quadCount = totalQuads;
        this.payloadBytes = payload; this.chargedBytes = charge;
    }

    /** Returns null before allocating packet payload if the admission charge exceeds the remaining budget.
     * Scratch is bounded by the 64-arena renderer limit, independently of section/cluster count.
     * Charge = primitive payload + a fixed header/reference allowance, not a measurement of JVM heap size.
     */
    static LodNodeItems build(List<? extends Source> source, int arenaCount, long remainingBytes) {
        if (arenaCount < 0 || arenaCount > 64) throw new IllegalArgumentException("Arena count");
        if (chargeFor(source, arenaCount) > remainingBytes) return null;
        int maxBatches = 3 * arenaCount;
        int[] lookup = new int[maxBatches], keys = new int[maxBatches], words = new int[maxBatches];
        long[] sums = new long[maxBatches], facing = new long[8];
        Arrays.fill(lookup, -1);
        int batches = 0, sectionWords = 0, clusters = 0;
        long totalQuads = 0;
        for (Source s : source) {
            LodSectionItems p = s.itemRecords();
            if (p.hasSection()) sectionWords = Math.addExact(sectionWords, 4);
            for (int layer = 0; layer < 3; layer++) {
                if (!p.hasLayer(layer)) continue;
                int arena = s.itemArena(layer);
                if (arena < 0 || arena >= arenaCount) throw new IllegalArgumentException("Arena identity");
                int key = layer * arenaCount + arena, b = lookup[key];
                if (b < 0) { b = batches++; lookup[key] = b; keys[b] = key; }
                int count = p.clusterCount(layer);
                words[b] = Math.addExact(words[b], Math.multiplyExact(count, 4));
                clusters = Math.addExact(clusters, count);
                sums[b] += p.quadCount(layer);
                totalQuads += p.quadCount(layer);
            }
            for (int d = 0; d < 8; d++) facing[d] += p.facingQuads(d);
        }
        long payload = 4L * sectionWords + 16L * clusters + 16L * batches + 64;
        long charge = payload + 256 + 64L * batches;
        if (charge > remainingBytes) return null;
        int[] sections = new int[sectionWords], layers = new int[batches], arenas = new int[batches];
        int[][] items = new int[batches][];
        int[] at = new int[batches];
        for (int b = 0; b < batches; b++) {
            layers[b] = keys[b] / arenaCount; arenas[b] = keys[b] % arenaCount;
            items[b] = new int[words[b]];
        }
        int sectionAt = 0;
        for (Source s : source) {
            LodSectionItems p = s.itemRecords();
            if (p.hasSection()) { p.copySection(sections, sectionAt); sectionAt += 4; }
            for (int layer = 0; layer < 3; layer++) {
                if (!p.hasLayer(layer)) continue;
                int b = lookup[layer * arenaCount + s.itemArena(layer)];
                p.copyLayer(layer, items[b], at[b]); at[b] += 4 * p.clusterCount(layer);
            }
        }
        return new LodNodeItems(source.size(), sections, layers, arenas, items,
            Arrays.copyOf(sums, batches), facing, clusters, totalQuads, payload, charge);
    }

    int sectionRecords() { return this.sections.length / 4; }
    int batches() { return this.items.length; }
    int layer(int batch) { return this.layers[batch]; }
    int arena(int batch) { return this.arenas[batch]; }
    int clusters(int batch) { return this.items[batch].length / 4; }
    long quads(int batch) { return this.quads[batch]; }
    long facing(int direction) { return this.facing[direction]; }
    void copySections(int[] target, int at) { System.arraycopy(this.sections, 0, target, at, this.sections.length); }
    void copyBatch(int batch, int[] target, int at) { System.arraycopy(this.items[batch], 0, target, at, this.items[batch].length); }

    /** Render-thread owner; keys MUST be immutable uploaded-list identities, released before source geometry retires.
     * Payload/charged bytes and entry count are all bounded. OFF may keep warm entries, but free/close may not.
     */
    static final class Cache {
        private final IdentityHashMap<Object, LodNodeItems> entries = new IdentityHashMap<>();
        final long limitBytes;
        final int limitEntries;
        private long payloadBytes, chargedBytes;
        Cache(long limitBytes, int limitEntries) {
            if (limitBytes < 0 || limitEntries < 0) throw new IllegalArgumentException("Negative packet cap");
            this.limitBytes = limitBytes; this.limitEntries = limitEntries;
        }
        LodNodeItems get(Object source) { return this.entries.get(source); }
        boolean canAdd() { return this.entries.size() < this.limitEntries && this.remainingBytes() >= 320; }
        long remainingBytes() { return this.limitBytes - this.chargedBytes; }
        boolean put(Object source, LodNodeItems packet) {
            if (source == null || packet == null) throw new IllegalArgumentException("Null packet identity");
            if (this.entries.containsKey(source) || !this.canAdd() || packet.chargedBytes > this.remainingBytes()) return false;
            this.entries.put(source, packet);
            this.payloadBytes += packet.payloadBytes; this.chargedBytes += packet.chargedBytes;
            return true;
        }
        void remove(Object source) {
            LodNodeItems old = this.entries.remove(source);
            if (old != null) { this.payloadBytes -= old.payloadBytes; this.chargedBytes -= old.chargedBytes; }
        }
        void clear() { this.entries.clear(); this.payloadBytes = this.chargedBytes = 0; }
        int size() { return this.entries.size(); }
        long payloadBytes() { return this.payloadBytes; }
        long chargedBytes() { return this.chargedBytes; }
    }
}
