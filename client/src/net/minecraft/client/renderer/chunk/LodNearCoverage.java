package net.minecraft.client.renderer.chunk;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.nio.ByteBuffer;

/** Render-thread coverage, keyed by copied section coordinates, not rotating near-section IDs.
 * Bindings describe installed L0 geometry (including leased retired slabs). Frame evidence is
 * collected from the near draw list's mesh read. No scheduler/worker or GPU storage lives here.
 */
final class LodNearCoverage {
    private final int capacity;
    private final Long2ObjectOpenHashMap<IntArrayList> slots = new Long2ObjectOpenHashMap<>();
    /** Per section: how many bound slots are unbounded (no exact section bounds). */
    private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap unbounded = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
    private final LongOpenHashSet ready = new LongOpenHashSet();
    private final LongOpenHashSet outsideReady = new LongOpenHashSet();
    private final LongOpenHashSet empty = new LongOpenHashSet();
    private final LongOpenHashSet incomplete = new LongOpenHashSet();
    private IntOpenHashSet masked = new IntOpenHashSet(), next = new IntOpenHashSet();
    /** Slots whose cover this frame is not backed by drawn near geometry (authoritative air, outside-view evidence). */
    private final IntOpenHashSet weakCover = new IntOpenHashSet();
    private boolean lastNeedsReset, lastUncoverOnly;
    private final LongOpenHashSet exposed = new LongOpenHashSet();
    private record Handoff(Object owner, long generation) { }
    private Long2ObjectOpenHashMap<Handoff> previousFull = new Long2ObjectOpenHashMap<>(), currentFull = new Long2ObjectOpenHashMap<>();
    private long revision;
    private long bindingRevision, exposureBindings, exposureMask;
    private boolean exposureValid, exposureUnmasked;
    private long exposureBuilds, exposureHits;

    LodNearCoverage(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("coverage capacity");
        this.capacity = capacity;
    }

    void beginFrame() {
        this.ready.clear(); this.outsideReady.clear(); this.incomplete.clear(); this.empty.clear(); this.emptySupport.clear(); this.emptyMesh.clear();
        var old = this.previousFull; this.previousFull = this.currentFull; this.currentFull = old; this.currentFull.clear();
    }

    void observe(long section, boolean complete) {
        // If a coordinate unexpectedly occurs twice, any incomplete observation wins.
        if (complete) this.ready.add(section); else this.incomplete.add(section);
    }

    /** Keep this evidence separate: preparation may replace the installed bindings
     * between near extraction and coverage publication. Final bounds must still qualify. */
    void observeOutside(long section, boolean complete) {
        if (complete) this.outsideReady.add(section); else this.incomplete.add(section);
    }

    void resetVisibilityHistory() { this.clearExposure(); this.previousFull.clear(); this.currentFull.clear(); }

    void observeEmpty(long section) { this.empty.add(section); this.ready.add(section); }

    /** Round 8 (LodGpu#emptySupport): an air section whose first non-air section below is {@code support}. Air only covers the
     * LOD slab bound there once that section is drawn: seed-generated land above a real lake was masked by the lake's air
     * section while the lake's own section was not drawn yet (a hole in the water at the render-distance edge). */
    void observeEmpty(long section, long support) { this.observeEmpty(section); this.emptySupport.put(section, support); }
    private final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap emptySupport = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
    private boolean unsupportedEmpty(long section) {
        if (!this.empty.contains(section) || !this.emptySupport.containsKey(section)) return false;
        long support = this.emptySupport.get(section);
        return !this.ready.contains(support) || this.incomplete.contains(support);
    }

    /** Round 8 (LodGpu#emptyMeshSupport): a listed real section whose mesh has no geometry (all water, all stone). */
    void observeEmptyMesh(long section) { this.emptyMesh.add(section); }
    private final LongOpenHashSet emptyMesh = new LongOpenHashSet();
    int minSectionY = Integer.MIN_VALUE;

    /** A geometry-less real section covers its LOD slab only once the real geometry seen through it is drawn: the first
     * section below that has a mesh. Deep sea: the all-water real section masked the seed-generated sea bed bound there while
     * the real bed, one section lower, was not listed yet, so neither was drawn (pale wedges in the ocean near the edge). */
    private boolean unsupportedEmptyMesh(long section) {
        if (!LodGpu.emptyMeshSupport || !this.emptyMesh.contains(section) || this.empty.contains(section)) return false;
        long below = section;
        for (int i = 0; i < 32; i++) {
            below = net.minecraft.core.SectionPos.offset(below, 0, -1, 0);
            if (net.minecraft.core.SectionPos.y(below) < this.minSectionY) return false;
            if (!this.ready.contains(below) || this.incomplete.contains(below) && !this.empty.contains(below)) return true;
            if (!this.emptyMesh.contains(below) && !this.empty.contains(below)) return false;
        }
        return false;
    }

    /** Diagnostic (mc.lod.holeDiag): bound sections whose masked slot holds solid LOD geometry although the real section there
     * draws nothing (air 'A', mesh without geometry 'M') or is not in this frame's ready set at all ('S' stale). */
    String maskDiag(java.util.function.IntPredicate slotHasSolid) {
        int air = 0, emptyM = 0, stale = 0;
        StringBuilder sb = new StringBuilder();
        for (var e : this.slots.long2ObjectEntrySet()) {
            long section = e.getLongKey();
            IntArrayList list = e.getValue();
            boolean hit = false;
            for (int i = 0; i < list.size(); i++) hit |= this.masked.contains(list.getInt(i)) && slotHasSolid.test(list.getInt(i));
            if (!hit) continue;
            char c;
            if (this.empty.contains(section)) { air++; c = 'A'; }
            else if (this.emptyMesh.contains(section)) { emptyM++; c = 'M'; }
            else if (!this.ready.contains(section) && !this.outsideReady.contains(section)) { stale++; c = 'S'; }
            else continue;
            if (sb.length() < 400) sb.append(' ').append(net.minecraft.core.SectionPos.x(section)).append(',').append(net.minecraft.core.SectionPos.y(section))
                .append(',').append(net.minecraft.core.SectionPos.z(section)).append(c);
        }
        return "air " + air + " emptyMesh " + emptyM + " stale " + stale + ":" + sb;
    }

    /** Rebinding happens on every item build; recycle the per-section lists instead of reallocating them. */
    private final java.util.ArrayList<IntArrayList> spareLists = new java.util.ArrayList<>();

    void clearBindings() {
        for (IntArrayList list : this.slots.values()) { list.clear(); this.spareLists.add(list); }
        this.slots.clear(); this.unbounded.clear(); this.bindingRevision++;
    }

    void bind(long section, int slot) {
        this.bind(section, slot, false);
    }

    void bind(long section, int slot, boolean bounded) {
        if (slot < 0 || slot >= this.capacity) throw new IllegalArgumentException("coverage slot");
        IntArrayList list = this.slots.get(section);
        if (list == null) {
            list = this.spareLists.isEmpty() ? new IntArrayList(1) : this.spareLists.remove(this.spareLists.size() - 1);
            this.slots.put(section, list);
        }
        list.add(slot);
        if (!bounded) this.unbounded.addTo(section, 1);
        this.bindingRevision++;
    }

    /** Exact inverse of {@link #bind(long, int, boolean)} with the same arguments (incremental rebinding). */
    void unbind(long section, int slot, boolean bounded) {
        IntArrayList list = this.slots.get(section);
        if (list == null || !list.rem(slot)) throw new IllegalStateException("coverage slot not bound");
        if (list.isEmpty()) { this.slots.remove(section); this.spareLists.add(list); }
        if (!bounded && this.unbounded.addTo(section, -1) <= 1) this.unbounded.remove(section);
        this.bindingRevision++;
    }

    long bindingRevision() { return this.bindingRevision; }

    it.unimi.dsi.fastutil.longs.LongSet boundSections() { return this.slots.keySet(); }

    /** Diagnostic: columns (x, z) with a non-air section vanilla listed this frame (ready or not). */
    void listedColumns(LongOpenHashSet out) {
        out.clear();
        for (var it = this.ready.iterator(); it.hasNext();) { long s = it.nextLong(); if (!this.empty.contains(s)) out.add(column(s)); }
        for (var it = this.incomplete.iterator(); it.hasNext();) out.add(column(it.nextLong()));
    }

    /** Diagnostic: bound sections with an unmasked slot in a column vanilla lists. Reason: 'I' listed but incomplete, 'N' not
     * listed (vanilla draws other sections of the column), 'R' ready yet unmasked. */
    void unmaskedInListedColumns(LongOpenHashSet columns, it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap counts, int[] reasons) {
        for (var e : this.slots.long2ObjectEntrySet()) {
            long section = e.getLongKey();
            if (!columns.contains(column(section))) continue;
            IntArrayList list = e.getValue();
            boolean unmasked = false;
            for (int i = 0; i < list.size(); i++) if (!this.masked.contains(list.getInt(i))) { unmasked = true; break; }
            if (!unmasked) continue;
            int reason = this.incomplete.contains(section) ? 0 : this.ready.contains(section) ? 2 : 1;
            reasons[reason]++;
            counts.addTo(section << 2 | reason, 1);
        }
    }

    static long column(long section) {
        return (long)net.minecraft.core.SectionPos.x(section) << 32 | net.minecraft.core.SectionPos.z(section) & 0xFFFFFFFFL;
    }

    boolean hasFallback(long section) { return this.slots.containsKey(section); }
    boolean hasBoundedFallback(long section) { return this.slots.containsKey(section) && !this.unbounded.containsKey(section); }

    /** Called only after a successful cull (or before enabling from the old unmasked draw list).
     * Merely installed-but-masked fallback must not turn an unrelated near fade off. */
    void captureExposure(boolean unmasked) {
        this.captureExposure(unmasked, false);
    }

    /** Exact memoization: exposure depends only on the installed bindings and mask.
     * Cull failures/history resets invalidate it even when those inputs stay the same. */
    void captureExposure(boolean unmasked, boolean cached) {
        if (cached && this.exposureValid && this.exposureBindings == this.bindingRevision
                && this.exposureMask == this.revision && this.exposureUnmasked == unmasked) {
            this.exposureHits++;
            return;
        }
        this.exposureBuilds++;
        this.exposed.clear();
        for (var entry : this.slots.long2ObjectEntrySet()) {
            IntArrayList list = entry.getValue();
            for (int i = 0; i < list.size(); i++) if (unmasked || !this.masked.contains(list.getInt(i))) {
                this.exposed.add(entry.getLongKey()); break;
            }
        }
        this.exposureBindings = this.bindingRevision;
        this.exposureMask = this.revision;
        this.exposureUnmasked = unmasked;
        this.exposureValid = true;
    }

    /** Latch for the same RenderSection reset generation on consecutive frames. Ordinary
     * ready recompiles preserve it; reset/rotation/unseen
     * coordinates cannot inherit it. Ordinary completed fades need no latch. */
    boolean fullVisibility(long section, Object owner, long generation, boolean complete, float ordinaryVisibility) {
        if (!complete || ordinaryVisibility >= 1.0F) return false;
        Handoff previous = this.previousFull.get(section);
        boolean continuing = previous != null && previous.owner == owner && previous.generation == generation;
        boolean full = this.exposed.contains(section) || continuing;
        if (full) this.currentFull.put(section, continuing ? previous : new Handoff(owner, generation));
        return full;
    }

    void clearExposure() { this.exposed.clear(); this.exposureValid = false; }

    long exposureBuilds() { return this.exposureBuilds; }
    long exposureHits() { return this.exposureHits; }

    /** Returns true on any visible-content change, including uncover and slot recycling. */
    /** Diagnostic ({@code -Dmc.lod.noMask=true}): never mask a bound LOD slab (it draws together with the real section). */
    private static final boolean NO_MASK = Boolean.getBoolean("mc.lod.noMask");

    boolean update() {
        this.next.clear();
        this.weakCover.clear();
        if (NO_MASK) { this.ready.clear(); this.outsideReady.clear(); }
        for (var it = this.ready.iterator(); it.hasNext();) {
            long section = it.nextLong();
            if (this.incomplete.contains(section) && !this.empty.contains(section)) continue;
            if (this.unsupportedEmpty(section)) continue;
            if (this.unsupportedEmptyMesh(section)) continue;
            IntArrayList list = this.slots.get(section);
            // Round 7 (LodGpu#emptyCoverStrong): an empty real section's LOD slab holds no current geometry either, so covering
            // it removes no depth a retained verdict relies on; treat it as a strong cover (no history reset).
            boolean weak = this.empty.contains(section) && !LodGpu.emptyCoverStrong;
            if (list != null) for (int i = 0; i < list.size(); i++) {
                this.next.add(list.getInt(i));
                if (weak) this.weakCover.add(list.getInt(i));
            }
        }
        for (var it = this.outsideReady.iterator(); it.hasNext();) {
            long section = it.nextLong();
            if (!this.hasBoundedFallback(section) || this.incomplete.contains(section) && !this.empty.contains(section)) continue;
            IntArrayList list = this.slots.get(section);
            boolean drawn = this.ready.contains(section) && !this.empty.contains(section);
            for (int i = 0; i < list.size(); i++) {
                this.next.add(list.getInt(i));
                if (!drawn) this.weakCover.add(list.getInt(i));
            }
        }
        boolean changed = !this.masked.equals(this.next);
        this.lastNeedsReset = false;
        this.lastUncoverOnly = changed;
        if (changed) {
            // Newly covered slots remove fallback depth that retained hidden verdicts may rely on. Only a
            // cover backed by a near mesh drawn in this same frame replaces that depth; anything else is weak.
            for (var it = this.next.iterator(); it.hasNext();) {
                int slot = it.nextInt();
                if (this.masked.contains(slot)) continue;
                this.lastUncoverOnly = false;
                if (this.weakCover.contains(slot)) { this.lastNeedsReset = true; break; }
            }
        }
        IntOpenHashSet old = this.masked; this.masked = this.next; this.next = old;
        if (changed) this.revision++;
        return changed;
    }

    /** After a changed {@link #update}: whether the change can invalidate retained hidden verdicts beyond
     * the ordinary temporal-occlusion lag. Uncovering only adds fallback depth; covered fallbacks always
     * write "visible" entries in every cull path, so they never inherit a hidden verdict. */
    boolean changeNeedsHistoryReset() { return this.lastNeedsReset; }
    boolean changeWasUncoverOnly() { return this.lastUncoverOnly; }

    long revision() { return this.revision; }
    int coveredCount() { return this.masked.size(); }

    /** Caller has proved this physical buffer's previous GPU use complete. Always clears the
     * entire valid range: each cull slot has different historical coverage. */
    void write(ByteBuffer target, int entries) {
        if (entries < 0 || entries > this.capacity || target.capacity() < entries)
            throw new IllegalArgumentException("coverage range");
        int i = 0;
        for (; i + Long.BYTES <= entries; i += Long.BYTES) target.putLong(i, 0L);
        for (; i < entries; i++) target.put(i, (byte)0);
        for (var it = this.masked.iterator(); it.hasNext();) {
            int slot = it.nextInt();
            if (slot >= entries) throw new IllegalArgumentException("covered slot beyond range");
            target.put(slot, (byte)1);
        }
    }

    static boolean writable(long lastUse, long completed) { return lastUse <= completed; }
}
