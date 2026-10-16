package net.minecraft.client.renderer.chunk;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.device.GpuOutOfMemoryException;
import com.mojang.renderpearl.backend.metal.MetalClusterCull;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * Stage 3: GPU side of the LOD (docs/stage3/LOD-DESIGN.md §5.2), render thread only. Meshes live in vertex arenas
 * (Shared Metal buffers of {@link #ARENA_BYTES}, first-fit allocation in whole quads, frees after RING + 2 frames
 * and any protecting geometry leases);
 * each meshed slab takes one section slot: a persistent section info (int4: origin, visibility 1) bound as the cluster
 * draw's section buffer, and a persistent cull-table entry ((origin - C0, cell unit), (C0 - origin, 0)) that the round-12
 * cluster kernel reads with flag 4 and this frame's camera deltas (native/src/mcmetal_clusters.m), so no per-frame section
 * table is written. The cluster items of the drawn set are rebuilt only when the selection or residency changes; per frame
 * the CPU writes one view block and the jobs (one per layer and arena), and the kernel culls every drawn LOD cluster
 * (frustum + facing) into one indirect draw per (layer, arena), drawn by ChunkSectionsToRender after the real terrain
 * (SOLID, CUTOUT) and before the real translucent layer.
 */
final class LodGpu {
    /** Same-session CPU item-packing A/B. Sampled once per item build; default off. */
    public static volatile boolean cacheItems = Lod.flag("mc.lod.cacheItems", true);
    /** Independent same-session group-lookup A/B. Sampled once per item build; default off. */
    public static volatile boolean cacheGroups = Lod.flag("mc.lod.cacheGroups", true);
    /** CPU node packet replay, sampled once/build and only admitted with cacheItems. Default off. */
    public static volatile boolean cacheNodeItems = Lod.flag("mc.lod.cacheNodeItems", true);
    /** Experimental CPU packing worker; same-session switch, default off. GPU work remains on the render thread. */
    public static volatile boolean asyncItems = Boolean.getBoolean("mc.lod.asyncItems");
    /** Default-off CPU prototype: accept a completed pack only if fresh inputs match exactly. */
    public static volatile boolean asyncExactRevalidate = false;
    private long asyncRechecks, asyncEquivalent;
    /** Default-off in-game prototype; benchmark-switchable, no startup property yet.
     * A settled async list need not use the dissolve shader. Any live/terminal fade
     * keeps the original shader; image reuse also stays on its existing path. */
    public static volatile boolean asyncOpaque;
    private long asyncOpaqueFrames;
    /** Per-section near mesh handoff, optional native extension required. Round 7: default on ({@code mc.lod.nearHandoff}); with it,
     * LOD and vanilla no longer disagree at the render-distance edge (doubled translucent ice flashed there). */
    private static final boolean NEAR_HANDOFF_ARMED = Lod.flag("mc.lod.nearHandoff", true);
    public static volatile boolean nearHandoff = NEAR_HANDOFF_ARMED;
    /** Exact exposure memoization prototype; runtime A/B only, default off. */
    public static volatile boolean cacheNearExposure = Lod.flag("mc.lod.cacheNearExposure", true);
    /** Ready coverage outside the actual view; runtime prototype, default off. */
    public static volatile boolean stableNearCoverage;
    /** Default-off runtime prototype: keep temporal occlusion history across coverage changes whose
     * newly covered slots are all replaced by near geometry drawn in the same frame (or uncover only).
     * Weak covers (authoritative air, outside-view evidence) still reset the whole history. */
    public static volatile boolean narrowCoverageReset = Lod.flag("mc.lod.narrowCoverageReset", true);
    /** Round 7 A/B ({@code mc.lod.emptyCoverStrong}): see LodNearCoverage#update. */
    public static volatile boolean emptyCoverStrong = Boolean.getBoolean("mc.lod.emptyCoverStrong");
    private long nearHistoryResets, nearHistoryKept;
    /** Diagnostic only (cost attribution, image-changing): drop level-0 non-translucent clusters of
     * UNASSIGNED facing (cross-model plants) from the draw items. Forces the uncached item path. */
    public static volatile boolean plantsL0Off;
    /** Minimum interval between draw-list rebuilds caused only by selection/upload changes ({@code mc.lod.itemMinMs}, 0 = every
     * change; abab-switchable). Vanilla chunk changes still rebuild at once (no holes or double draws at the near edge); retired
     * ranges recycle only after a rebuild that no longer references them has itself aged past the ring. */
    public static volatile int itemMinMs = Integer.getInteger("mc.lod.itemMinMs", 250);
    private long lastItemBuildNanos;
    private long lastItemBuildFrame = Long.MIN_VALUE;
    /** Frame whose node lists the installed draw list was built from (== lastItemBuildFrame unless sliced). */
    private long lastItemBuildStartFrame = Long.MIN_VALUE;
    /** Sliced rebuilds: append at most this many microseconds per frame into back buffers while the old list draws; 0 = whole. */
    public static volatile int sliceUs = Integer.getInteger("mc.lod.sliceUs", 500);
    public static volatile boolean sliceDeferFirst = Lod.flag("mc.lod.sliceDeferFirst", true);
    private @Nullable List<LodManager.Node> sliceOrder;
    private int sliceAt;
    private long sliceSelection;
    private long sliceStartFrame, sliceStartNanos, sliceBuilds, sliceFrames, sliceMaxNs;
    private boolean sliceCached;
    private List<GroupItems> backGroups = new ArrayList<>();
    private int[] backSecItems = new int[4096];
    private int backSecCount, backDrawnSections, backDrawnClusters;
    private long backDrawnQuads;
    private long[] backByLevel = new long[8], backBySlot = new long[8];
    private long itemBuildsDeferred;
    private boolean builtPlantsL0Off;
    private long droppedPlantQuads;
    private long holeNodes, holeBuilds;
    private long maskDiagNanos;
    private long nearOutsideObserved;
    private boolean nearHandoffActive;
    private LodNearCoverage nearCoverage;
    private long nearCoverageBuild = -1;
    private long nearMaskReplacements, nearFullVisibilitySections;
    private static final long ASYNC_ITEM_BYTES = 128L << 20;
    private static final long NODE_ITEM_CACHE_BYTES = (long)Math.max(0, Math.min(256, Integer.getInteger("mc.lod.cacheNodeItemsMB", 64))) << 20;
    private static final int NODE_ITEM_CACHE_ENTRIES = 65536;
    private static final boolean GROUP_LOOKUP_DIAGNOSTICS = Boolean.getBoolean("mc.lod.cacheGroups.diagnostics");
    /** Opt-in wall-time attribution only; no per-section clocks, and no profiler allocation when disabled. */
    private static final boolean ITEM_BUILD_PROFILE = Boolean.getBoolean("mc.lod.itemBuildProfile");
    private final @Nullable LodItemBuildProfile itemBuildProfile = ITEM_BUILD_PROFILE ? LodItemBuildProfile.create(true) : null;
    private static final int GROUP_LOOKUP_SLOTS = 3 * 2 * 9; // layer, fade, level -1..7
    static final long ARENA_BYTES = Math.max(16L, Math.min(256L, Long.getLong("mc.lod.arenaMB", 256L))) << 20;
    static final int QUAD_BYTES = 4 * LodMesher.STRIDE;
    // High-vista tau=2 at 512 chunks exhausted the old 262,144-slot table before the
    // requested 6 GiB geometry budget. IDs already have 29 bits; the section-occlusion
    // byte table is 1 MiB. This adds 45 MiB of metadata, not coarser geometry.
    static final int MAX_SECTIONS = 1 << 20;
    private static final int MAX_ARENAS = 64;
    private static final int RING = 4;
    private static final boolean COMPACT_WORK_BUFFERS = LodGpuBufferCapacity.ENABLED;
    private static final int VISIBLE_QUAD_BYTES = 4;
    private static final int TABLE_BYTES = 32;
    private final LodManager manager;
    private final LodReuse reuse = new LodReuse();
    private LodAsyncProducerProbe asyncProbe;
    private final LodGeometryLeases geometryLeases = new LodGeometryLeases(RenderSystem::assertOnRenderThread, this::disposeGeometry);
    private long visibilityRevision;
    private double[] reuseLastView;
    /** Turn reuse (LodReuse.turn): padding per side as a fraction of the screen, and the minimum number of frames the
     * current turn rate must keep a new guard capture valid ({@code mc.lod.turnPad}, {@code mc.lod.turnMinFrames}). */
    public static volatile float turnPad = Float.parseFloat(System.getProperty("mc.lod.turnPad", "0.18"));
    public static volatile int turnMinFrames = Integer.getInteger("mc.lod.turnMinFrames", 8);
    private byte @Nullable [] turnKey;
    private double[] turnCamEye, turnSrcPlanes, turnLastPlanes, turnLastCamEye;
    private double turnSafe;
    private int turnPadX, turnPadY;
    private long turnRetained, turnCaptureFrames;
    /** Turn strips: once the rotation passes turnStripStart of the guard, the next guard capture is culled and rendered in
     * turnStrips column sub-frusta over that many frames (front image keeps compositing), then one full guard cull of the
     * same view swaps it in ({@code mc.lod.turnStrips}, 0/1 = single-frame capture; {@code mc.lod.turnStripStart}). */
    public static volatile int turnStrips = Integer.getInteger("mc.lod.turnStrips", 12);
    public static volatile float turnStripStart = Float.parseFloat(System.getProperty("mc.lod.turnStripStart", "0.5"));
    private int stripNext = -1, stripTotal;
    /** Strip count when the view has not rotated since the last frame (a soft-window refresh): thinner strips, same work
     * ({@code mc.lod.turnStillStrips}). */
    public static volatile int turnStillStrips = Integer.getInteger("mc.lod.turnStillStrips", 32);
    private double[] stripSrcPlanes, stripWide;
    private double stripSafe;
    private long stripGeometry, stripBuilds, stripVisibility;
    private long turnStripFrames, turnSwaps, turnStripAborts;
    private List<TerrainClusters.Draw> reuseCullDraws;
    private Slot reuseCullSlot;
    private byte[] reuseCullKey;
    private long reuseCullGeometry, reuseCullMembership, reuseCullVisibility, reuseCullHits;

    LodReuse reuse() { return this.reuse; }
    LodAsyncProducerProbe asyncProbe() {
        if (!LodAsyncProducerProbe.ENABLED || this.closed) return null;
        if (this.asyncProbe == null) this.asyncProbe = new LodAsyncProducerProbe(this);
        return this.asyncProbe;
    }
    /** Diagnostic only: a successful tiny capture during world startup proves too little. */
    boolean asyncProbeSettled() { return !this.closed && this.manager.idle() && this.drawnQuads > 0; }

    /**
     * Opt-in for LodAsync: acquire on the render thread BEFORE copying the current live allocation set.
     * Null means closed or three snapshots are already held; skip this offer. Never attach a new lease
     * to an older draw list/retired IDs. Keep this token until producer completion AND all consumers of
     * that image's visibility IDs complete, then poll/close on the render thread, including after world close.
     *
     * This only protects arena ranges, section-slot identities and retired buffer lifetimes. It does NOT
     * freeze section metadata (rebase/fades), item lists, cull outputs, uniforms or material pixels. The
     * snapshot creator must own copies of mutable inputs and separately retain actual buffers/materials;
     * retaining an MTLBuffer alone does not prevent its geometry ranges being overwritten.
     */
    LodGeometryLeases.@Nullable Lease acquireGeometryLease() {
        RenderSystem.assertOnRenderThread();
        return this.closed ? null : this.geometryLeases.acquire();
    }

    /** Next allocation retirement epoch, distinct from the draw-content geometryEpoch. Saturates, never wraps. */
    long allocationRetirementEpoch() { return this.geometryLeases.epoch(); }
    int geometryLeaseCount() { return this.geometryLeases.count(); }
    long oldestGeometryLeaseEpoch() { return this.geometryLeases.oldestEpoch(); }

    /**
     * Call at prepareLodReuse AFTER producer refresh admission, while this frame's groups/view are current.
     * This can copy 48 MiB of section metadata plus items; never call speculatively every game frame.
     * Full pre-cull opaque groups only. The caller separately captures projection/materials and a passive
     * main-submission ticket, then retains native buffers before handing the immutable bytes to a worker.
     * Null means unavailable, fading, stale or over capacity. No cull, readback, submit or GPU wait here.
     */
    private String farCaptureDecline="not attempted";
    String farCaptureDecline() { return this.farCaptureDecline; }
    @Nullable LodFarGeometry captureFarGeometry(final long metadataBudgetBytes) {
        this.farCaptureDecline="unready or fading view";
        RenderSystem.assertOnRenderThread();
        LodFarGeometry.drainDeferredReleases();
        if (this.closed || this.failed || this.itemsDirty || !this.fades.isEmpty() || !this.c0Set
            || !this.farViewReady || this.farViewFrame != this.frame || this.view <= 0
            || this.view > Long.MAX_VALUE - LodFarGeometry.VIEW_BYTES || metadataBudgetBytes <= 0
            || this.nextSlot <= 0 || this.nextSlot > MAX_SECTIONS) return null;
        this.farCaptureDecline="no free geometry lease";
        LodGeometryLeases.Lease lease = this.acquireGeometryLease();
        if (lease == null) return null;
        long token = 0;
        boolean transferred = false;
        long started = System.nanoTime();
        try {
            token = LodFarGeometry.retain(lease);
            if (token == 0) return null;
            this.farCaptureDecline="invalid resident section";
            int sections = 0;
            for (GpuSection section : this.resident) {
                if (section.slot < 0 || section.slot >= this.nextSlot) return null;
                sections = Math.max(sections, section.slot + 1);
            }
            if (sections == 0) return null;
            long bytes = 48L * sections + LodFarGeometry.VIEW_BYTES;
            long budget = Math.min(metadataBudgetBytes, LodFarGeometry.MAX_METADATA_BYTES);
            long maxQuads = 0;
            int inputCount = 0;
            for (GroupItems group : this.groups) {
                this.farCaptureDecline="invalid layer/count";
                if (group.count < 0 || group.layer < 0 || group.layer > 2) return null;
                if (group.layer == 2 || group.count == 0) continue;
                bytes += 16L * group.count;
                this.farCaptureDecline="metadata or input budget";
                if (bytes > budget || ++inputCount > LodFarGeometry.MAX_INPUTS) return null;
                Arena arena = group.arena;
                this.farCaptureDecline="invalid group items or arena";
                if (group.fade || arena.index < 0 || arena.index >= this.arenas.size()
                    || this.arenas.get(arena.index) != arena || arena.buffer.isClosed()
                    || !MetalClusterCull.isMetal(arena.buffer)
                    || !LodFarGeometry.validItems(group.items, group.count, group.quads,
                        arena.quadBytes, arena.slice.length(), sections)) return null;
                maxQuads = Math.max(maxQuads, group.quads);
            }
            this.farCaptureDecline="empty or over-budget inputs";
            if (inputCount == 0 || bytes > budget) return null;
            // The synchronous frame already ensured index capacity. Never allocate/fill a large
            // index buffer while offering a snapshot. A later producer-owned path must prepare it ahead.
            this.farCaptureDecline="index capacity unavailable";
            if (this.indexBuffer == null || this.indexQuads < maxQuads) return null;
            GpuBuffer indices = this.indexBuffer;
            if (indices.isClosed() || indices.size() < maxQuads * 24L || !MetalClusterCull.isMetal(indices)) return null;
            this.farCaptureDecline="metadata buffers not CPU readable";
            byte[] cull = copyFarMetadata(this.sectionTable, sections * TABLE_BYTES);
            byte[] shader = copyFarMetadata(this.sectionInfos, sections * 16);
            if (cull == null || shader == null) return null;
            byte[] ownedView = new byte[LodFarGeometry.VIEW_BYTES];
            MemoryUtil.memByteBuffer(this.view, ownedView.length).get(ownedView);
            this.farCaptureDecline="invalid complete cull view";
            if (!LodFarGeometry.completeView(ownedView)) return null;
            List<LodFarGeometry.Input> inputs = new ArrayList<>(inputCount);
            // Preserve SOLID/CUTOUT and per-arena order; never use Draw.items (the GPU-compacted result).
            for (GroupItems group : this.groups) {
                if (group.layer == 2 || group.count == 0) continue;
                inputs.add(new LodFarGeometry.Input(group.items, group.count, Math.toIntExact(group.quads),
                    group.layer + (group.arena.compact ? 8 : 0), group.arena.index,
                    group.arena.slice, indices.slice(0, group.quads * 24L)));
            }
            var source = new LodFarGeometry.Source(this.farGeneration, this.geometryEpoch, this.builds,
                this.builtSelection, this.visibilityRevision, lease.epoch(), this.frame, this.farViewNanos,
                this.c0x, this.c0y, this.c0z, this.farCameraX, this.farCameraY, this.farCameraZ,
                this.farEyeX, this.farEyeY, this.farEyeZ);
            LodFarGeometry snapshot = new LodFarGeometry(source, inputs, cull, shader, ownedView,
                token, bytes, System.nanoTime() - started);
            transferred = true;
            this.farCaptureDecline="accepted";
            return snapshot;
        } catch (GpuOutOfMemoryException capacity) {
            return null;
        } finally {
            if (!transferred) {
                if (token != 0) {
                    LodFarGeometry.deferredRelease(token);
                    LodFarGeometry.drainDeferredReleases();
                } else lease.close();
            }
        }
    }

    /**
     * Bounded camera-only capture at the same prepareLodReuse hook as captureFarGeometry.
     * The caller MUST still own the original native recipe and its allocation lease, through every
     * producer/consumer use. Source is an identity, not proof of ownership. This method neither acquires
     * nor releases a lease (nor drains releases), touches GPU buffers, submits, or waits.
     *
     * Upload/free/close advance geometryEpoch; item rebuilds advance builds and record builtSelection;
     * fade writes advance visibilityRevision. Rebase rewrites tables WITHOUT advancing geometryEpoch,
     * so compare C0 explicitly. Any retirement (even unrelated cull work buffers) conservatively declines.
     * Thus turns can qualify between selection changes; flying/streaming, fades and rebases often cannot.
     * No resident, group, item or face scan: only scalar checks and a 128-byte CPU view copy.
     */
    @Nullable LodFarRefresh captureFarRefresh(LodFarGeometry.Source original) {
        RenderSystem.assertOnRenderThread();
        if (original == null || this.closed || this.failed || this.itemsDirty || !this.fades.isEmpty()
            || !this.c0Set || !this.farViewReady || this.farViewFrame != this.frame
            || this.view <= 0 || this.view > Long.MAX_VALUE - LodFarGeometry.VIEW_BYTES
            || original.generation() != this.farGeneration || original.geometryEpoch() != this.geometryEpoch
            || original.membershipBuild() != this.builds || original.selectionVersion() != this.builtSelection
            || original.visibilityRevision() != this.visibilityRevision
            || original.originX() != this.c0x || original.originY() != this.c0y || original.originZ() != this.c0z
            || original.allocationEpoch() <= 0 || original.allocationEpoch() == Long.MAX_VALUE
            || original.allocationEpoch() != this.geometryLeases.epoch()
            || this.geometryLeases.closed() || this.geometryLeases.count() == 0
            || this.geometryLeases.oldestEpoch() <= 0
            || this.geometryLeases.oldestEpoch() > original.allocationEpoch()
            || !Double.isFinite(this.farCameraX) || !Double.isFinite(this.farCameraY) || !Double.isFinite(this.farCameraZ)
            || !Double.isFinite(this.farEyeX) || !Double.isFinite(this.farEyeY) || !Double.isFinite(this.farEyeZ)) return null;
        // completeView preserves exact planes/deltas and removes only near-depth section occlusion.
        byte[] ownedView = new byte[LodFarGeometry.VIEW_BYTES];
        MemoryUtil.memByteBuffer(this.view, ownedView.length).get(ownedView);
        if (!LodFarGeometry.completeView(ownedView)) return null;
        var source = new LodFarGeometry.Source(original.generation(), original.geometryEpoch(),
            original.membershipBuild(), original.selectionVersion(), original.visibilityRevision(),
            original.allocationEpoch(), this.frame, this.farViewNanos,
            original.originX(), original.originY(), original.originZ(),
            this.farCameraX, this.farCameraY, this.farCameraZ, this.farEyeX, this.farEyeY, this.farEyeZ);
        return new LodFarRefresh(source, ownedView);
    }

    /** The accessor already includes a Metal suballocation's backing offset. These tables are CPU-written. */
    private static byte @Nullable [] copyFarMetadata(@Nullable GpuBuffer buffer, int bytes) {
        if (buffer == null || buffer.isClosed() || bytes <= 0 || bytes > buffer.size()
            || !MetalClusterCull.isMetal(buffer)) return null;
        long address = MetalClusterCull.contents(buffer);
        if (address <= 0 || address > Long.MAX_VALUE - bytes) return null;
        byte[] copy = new byte[bytes];
        MemoryUtil.memByteBuffer(address, bytes).get(copy);
        return copy;
    }

    private void invalidateReuse() {
        this.reuseCullDraws = null;
        this.reuseCullSlot = null;
        this.reuseCullKey = null;
        this.reuse.invalidate();
    }

    private final List<Arena> arenas = new ArrayList<>();
    private @Nullable GpuBuffer sectionInfos;
    private @Nullable GpuBuffer sectionTable;
    private final int[] freeSlots = new int[MAX_SECTIONS];
    private int freeSlotCount;
    private int nextSlot;
    private final List<long[]> deferredFrees = new ArrayList<>();
    private long frame;
    private boolean failed;
    private boolean probed;
    // Base point of the persistent table (block coordinates).
    private int c0x;
    private int c0y;
    private int c0z;
    private boolean c0Set;
    /** Default-off CPU prototype. Uploaded sections have identity equality and are inserted once;
     * LinkedHashSet preserves the list's iteration order while making retirement O(1). */
    public static volatile boolean residentSet = Lod.flag("mc.lod.residentSet", true);
    private java.util.Collection<LodGpu.GpuSection> resident = new ArrayList<>();
    private boolean residentSetActive;
    private long residentSetRemovals, residentListRemovals;
    private volatile long gpuBytes;
    private long residentAllocationBytes;
    private long uploadedBytes;
    private long uploadNanos;
    // Draw set.
    private long builtSelection = -1L;
    private boolean itemsDirty = true;
    private LodItemWorker<ItemInput, LodPreparedItems.Result> itemWorker;
    private ItemPlan activeItems, pendingItems;
    private long chunkRevision;
    private boolean asyncItemFailure;
    private int asyncRejectStreak;
    private long asyncOffers, asyncAccepted, asyncRejected, asyncBootstrap, asyncFallbacks, asyncUploadDefers;
    private long asyncCaptureNs, asyncInstallNs, asyncWorkerNs;
    private long asyncAcceptedClusters;
    private long asyncRecordSections, asyncRecordClusters, asyncScalarSections;
    private long asyncUploadRecordNs, asyncUploadRecordSections;
    private String asyncItemError = "";
    private final long[] itemRingLastUse = new long[6];
    private final List<GroupItems> groups = new ArrayList<>();
    private boolean buildCacheGroups;
    private boolean buildCacheNodeItems;
    private LodNodeItems.@Nullable Cache nodeItemCache;
    private final GpuBuffer[] itemRing = new GpuBuffer[6];
    private final long[] itemRingBytes = new long[6];
    private int itemRingIndex;
    private final Slot[] slots = new Slot[RING + 1];   // + 1: a retained turn list while strips cull every frame
    private long jobs;
    private long view;
    private long geometryEpoch = 1L;
    private boolean closed;

    private final long farGeneration = LodFarGeometry.nextGeneration();
    private boolean farViewReady;
    private long farViewFrame, farViewNanos;
    private double farCameraX, farCameraY, farCameraZ, farEyeX, farEyeY, farEyeZ;

    private @Nullable GpuBuffer indexBuffer;
    private long indexQuads;
    private final List<GpuBuffer> retired = new ArrayList<>();
    private record BufferRetirement(long frame, long epoch) { }
    private final List<BufferRetirement> retiredAt = new ArrayList<>();
    private final java.util.IdentityHashMap<GpuBuffer, Long> retiredQuadBuffers = new java.util.IdentityHashMap<>();
    private long retiredQuadBytes;
    private final LodGpuBufferCapacity.Maintenance bufferMaintenance = new LodGpuBufferCapacity.Maintenance();
    private record QuadWorkBytes(long required, long current, long inactive, long retired) { }
    // Stats may be requested by the scheduler: never iterate the changing keyed maps there.
    private volatile QuadWorkBytes quadWorkBytes = new QuadWorkBytes(0L, 0L, 0L, 0L);
    private int drawnSections;
    private long drawnQuads;
    private int drawnClusters;
    private static final class Arena {
        final GpuBuffer buffer;
        final GpuBufferSlice slice;
        final long base;
        /** Free ranges in record-sized units: start -> length. */
        final TreeMap<Integer, Integer> free = new TreeMap<>();
        final int index;
        /** Round 2: records of {@link LodCompact} (48 bytes per quad) instead of BLOCK vertices (112 bytes per quad). */
        final boolean compact;
        final int quadBytes;
        /** Lazy CPU-only lookup by this arena's identity; the ordered groups list still owns draw order. */
        @Nullable GroupItems[] groupLookup;
        /** Sliced rebuilds: the lookup of the back (building) group set; swapped with groupLookup. */
        @Nullable GroupItems[] backGroupLookup;

        Arena(final GpuBuffer buffer, final int index, final boolean compact) {
            this.buffer = buffer;
            this.slice = buffer.slice();
            this.base = MetalClusterCull.contents(buffer);
            this.index = index;
            this.compact = compact;
            this.quadBytes = compact ? LodCompact.RECORD_BYTES : QUAD_BYTES;
            // Round 3: ranges are whole multiples of 8 quads (occlusion entries = first quad >> 3 never span two sections).
            this.free.put(0, (int)(ARENA_BYTES / this.quadBytes) & ~7);
        }

        int alloc(final int quads) {
            for (var e : this.free.entrySet()) {
                if (e.getValue() >= quads) {
                    int start = e.getKey();
                    int len = e.getValue();
                    this.free.remove(start);
                    if (len > quads) {
                        this.free.put(start + quads, len - quads);
                    }
                    return start;
                }
            }
            return -1;
        }

        void release(final int start, final int quads) {
            int s = start;
            int l = quads;
            var lower = this.free.floorEntry(s - 1);
            if (lower != null && lower.getKey() + lower.getValue() == s) {
                s = lower.getKey();
                l += lower.getValue();
                this.free.remove(lower.getKey());
            }
            Integer next = this.free.get(start + quads);
            if (next != null) {
                this.free.remove(start + quads);
                l += next;
            }
            this.free.put(s, l);
        }
    }

    /** One resident meshed slab. */
    static final class GpuSection implements LodNodeItems.Source, LodPreparedItems.Source {
        int slot;
        final int[] arena = {-1, -1, -1};
        final int[] firstQuad = new int[3];
        final int[] quads = new int[3];
        /** Allocated record-sized units per layer, rounded up to 8. */
        final int[] allocQuads = new int[3];
        final int[] @Nullable [] clusters = new int[3][];
        // Slot/ranges/clusters never change after upload. No GPU resource or frame ownership here.
        // Optional immutable memo. Worker takes one volatile read; free may clear this reference,
        // but never changes the object or the immutable source metadata. A cache miss is exact.
        volatile @Nullable LodSectionItems preparedItems;
        long bytes;
        long allocationBytes;
        /** Immutable upload origin, retained by a CPU draw snapshot even after the resident map releases it. */
        int[] origin;

        @Override public int slot() { return this.slot; }
        @Override public int arena(int layer) { return this.arena[layer]; }
        @Override public int firstQuad(int layer) { return this.firstQuad[layer]; }
        @Override public int[] clusters(int layer) { return this.clusters[layer]; }
        @Override public @Nullable LodSectionItems preparedRecords() { return this.preparedItems; }

        @Override public int itemArena(int layer) { return this.arena[layer]; }
        @Override public int itemClusterCount(int layer) {
            return this.arena[layer] < 0 || this.clusters[layer] == null ? -1 : this.clusters[layer].length / TerrainClusters.CLUSTER_INTS;
        }
        @Override public LodSectionItems itemRecords() {
            if (this.preparedItems == null) throw new IllegalStateException("Section items not prepared");
            return this.preparedItems;
        }
    }

    private static final class GroupItems {
        final int layer;
        final Arena arena;
        /** Round 2: sections that are fading (drawn with the LOD_FADE pipelines). */
        final boolean fade;
        /** Diagnostics ({@code mc.lod.diag} contains "levels"): the level of every node in the group, else -1. */
        final int level;
        int[] items;
        int count;
        long quads;

        GroupItems(final int layer, final Arena arena, final boolean fade, final int level) {
            this(layer, arena, fade, level, new int[4 * 1024]);
        }

        GroupItems(final int layer, final Arena arena, final boolean fade, final int level, int[] items) {
            this.level = level;
            this.layer = layer;
            this.arena = arena;
            this.fade = fade;
            this.items = items;
        }
    }

    private static final class Slot {
        final List<Job> jobs = new ArrayList<>();
        final LodGpuBufferCapacity.Jobs<Job> compactJobs = new LodGpuBufferCapacity.Jobs<>();
        int used;
        long lastDrawFrame = -100L;
        /** Round 3: occlusion box list and parameters (draw args, counters, camera delta, stamp) of this ring slot. */
        @Nullable GpuBuffer boxes;
        long boxCapacity;
        @Nullable GpuBuffer occlParams;
        boolean occlUsed;
        @Nullable GpuBuffer nearMask;
        long nearMaskLastUse, nearMaskRevision = -1;
        int nearMaskEntries;
    }

    /** Stats: quads drawn after the GPU cull (last complete frame, and the running sum over frames). */
    private long visibleQuads;
    private long visibleSum;
    private long visibleFrames;

    private static final class Job extends LodGpuBufferCapacity.Use {
        GpuBuffer outItems;
        long clusterCapacity;
        GpuBuffer quads;
        long quadCapacity;
        GpuBuffer args;
        GpuBuffer scratch;
        /** Current cull input bounds, independent of overallocated ring-buffer capacity. */
        int quadCount;
        int level = -1;
    }

    LodGpu(final LodManager manager) {
        this.manager = manager;
    }

    boolean usable() {
        if (this.closed) return false;
        if (!this.probed) {
            this.probed = true;
            GpuBuffer probe = RenderSystem.getDevice().createBuffer(() -> "LOD probe", GpuBuffer.USAGE_MAP_WRITE, 16L);
            try {
                this.failed = !MetalClusterCull.isMetal(probe)
                    || !MetalClusterCull.prepare(probe);
            } finally {
                probe.close();
            }
            if (this.failed) {
                Lod.LOGGER.warn("[lod] needs Metal with the cluster kernels; LOD not drawn");
            }
        }
        return !this.failed && com.mojang.renderpearl.backend.metal.TerrainVertexMsl.clusterPipelinesReady();
    }

    /** Round 7 cost probe: render-thread ns spent in each handoff step (and the whole near draw-list build, both arms). */
    static long hoBound, hoEmptyRebuilds, hoRebindNs, hoEmptyNs, hoRebinds, hoBeginNs, hoUpdateNs, hoMaskNs, hoExposureNs, hoBuildNs, hoFrames;
    static String handoffCostJson() {
        double f = Math.max(1, hoFrames);
        return String.format(java.util.Locale.ROOT, "{\"frames\":%d,\"beginUs\":%.1f,\"updateUs\":%.1f,\"maskUs\":%.1f,\"exposureUs\":%.1f,\"buildUs\":%.1f,\"rebindUs\":%.1f,\"emptyUs\":%.1f,\"rebinds\":%d,\"emptyRebuilds\":%d,\"bound\":%d}",
            hoFrames, hoBeginNs/f/1e3, hoUpdateNs/f/1e3, hoMaskNs/f/1e3, hoExposureNs/f/1e3, hoBuildNs/f/1e3, hoRebindNs/f/1e3, hoEmptyNs/f/1e3, hoRebinds, hoEmptyRebuilds, hoBound);
    }

    /** Round 7 diagnostic: vanilla lists a section whose layers it will draw while coverage calls it not ready (LOD stays unmasked). */
    static final boolean DNR_PROBE = Boolean.getBoolean("mc.lod.dnrProbe");
    static long dnrCount, dnrTranslucent, dnrVertex, dnrIndex, dnrBuffer;
    static final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap dnrSections = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
    static void probeDrawnNotReady(long node, CompiledSectionMesh mesh) {
        int[] hot = mesh.hot;
        boolean drawn = false, translucent = false;
        for (int l = 0; l < ChunkSectionLayer.values().length; l++) {
            if ((hot[l * TerrainDrawList.HOT_STRIDE + TerrainDrawList.H_FLAGS] & TerrainDrawList.F_PRESENT) == 0) continue;
            drawn = true;
            if (l == ChunkSectionLayer.TRANSLUCENT.ordinal()) translucent = true;
            var layer = ChunkSectionLayer.values()[l];
            if (!mesh.isVertexBufferUploaded(layer)) dnrVertex++;
            else if (mesh.uberBuffers[2 * l] == null) dnrBuffer++;
            else dnrIndex++;
        }
        if (!drawn) return;
        dnrCount++;
        if (translucent) { dnrTranslucent++; if (dnrSections.size() < 4096) dnrSections.addTo(node, 1); }
    }
    static String dnrJson() {
        StringBuilder top = new StringBuilder();
        dnrSections.long2IntEntrySet().stream().sorted((a, b) -> b.getIntValue() - a.getIntValue()).limit(12).forEach(e ->
            top.append(top.length() == 0 ? "" : ",").append("\"").append(net.minecraft.core.SectionPos.x(e.getLongKey())).append(',')
                .append(net.minecraft.core.SectionPos.y(e.getLongKey())).append(',').append(net.minecraft.core.SectionPos.z(e.getLongKey()))
                .append("\":").append(e.getIntValue()));
        return "{\"count\":" + dnrCount + ",\"translucent\":" + dnrTranslucent + ",\"vertex\":" + dnrVertex + ",\"buffer\":" + dnrBuffer
            + ",\"index\":" + dnrIndex + ",\"sections\":" + dnrSections.size() + ",\"top\":{" + top + "}}";
    }

    boolean nearHandoffActive() { return this.nearHandoffActive; }

    /** Sample once at the near draw list. Mode changes cancel old worker snapshots before
     * they can install membership captured under the other retention rule. */
    @Nullable LodGpu beginNearFrame(boolean drawing) {
        boolean enabled = drawing && NEAR_HANDOFF_ARMED && nearHandoff && !LodAsyncProducerProbe.ENABLED
            && MetalClusterCull.lodCoverageAvailable() && this.usable();
        if (enabled && this.nearCoverage == null) this.nearCoverage = new LodNearCoverage(MAX_SECTIONS);
        if (enabled != this.nearHandoffActive) {
            if (this.nearCoverage != null) this.nearCoverage.resetVisibilityHistory();
            // Capture the old actually installed list before leaveAsyncItems releases it.
            // OFF lists were unmasked, even if this control was enabled in an earlier interval.
            if (enabled) { this.bindInstalledNearSections(); this.nearCoverage.captureExposure(true, cacheNearExposure); }
            this.leaveAsyncItems();
            this.nearHandoffActive = enabled;
            this.itemsDirty = true;
            this.nearCoverageBuild = -1;
            if (this.nearCoverage != null) { this.nearCoverage.clearBindings(); this.boundNear.clear(); this.nearBindEpoch++; }
            this.visibilityRevision++;
            this.invalidateReuse();
            Arrays.fill(this.lastOcclFrame, Long.MIN_VALUE);
        }
        if (!enabled) return null;
        if (this.nearCoverage == null) this.nearCoverage = new LodNearCoverage(MAX_SECTIONS);
        this.nearCoverage.beginFrame();
        if (stableNearCoverage) NearDrawView.capture(this);
        return this;
    }

    /** No later mesh reread: the caller computed readiness under the dispatcher's lock. */
    private static final boolean DIAG_NO_FACING = Boolean.getBoolean("mc.lod.diagNoFacing"), DIAG_NO_FRUSTUM = Boolean.getBoolean("mc.lod.diagNoFrustum");
    void observeNearEmptyMesh(long coordinate) { this.nearCoverage.observeEmptyMesh(coordinate); }
    public static volatile boolean emptyMeshSupport = Lod.flag("mc.lod.emptyMeshSupport", true);

    boolean observeNearSection(long coordinate, Object owner, long generation, boolean ready, float ordinaryVisibility) {
        this.nearCoverage.observe(coordinate, ready);
        boolean full = this.nearCoverage.fullVisibility(coordinate, owner, generation, ready, ordinaryVisibility);
        if (full) this.nearFullVisibilitySections++;
        return full;
    }

    /** The visibility graph omits known-empty sections entirely. Snapshot actual current air
     * under the same dispatcher ownership; its background EMPTY CAS is not authoritative.
     * Only real near columns are visited and getChunk never requests loading. */
    void captureNearEmptySections(net.minecraft.client.renderer.ViewArea view) {
        if (view == null) return;
        this.nearView = view;
        if (this.nearCoverage != null) this.nearCoverage.minSectionY = view.minSectionY();
        if (boundEmptyScan) { this.emptyView = view; return; }
        ClientLevel level = this.manager.level();
        var center = view.getCameraSectionPos();
        int radius = view.getViewDistance();
        var pos = new net.minecraft.core.BlockPos.MutableBlockPos();
        for (int z = center.z() - radius; z <= center.z() + radius; z++) {
            for (int x = center.x() - radius; x <= center.x() + radius; x++) {
                pos.set(x << 4, view.minSectionY() << 4, z << 4);
                if (view.getRenderSectionAt(pos) == null) continue;
                var chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                var sections = chunk.getSections();
                for (int index = 0; index < sections.length; index++) {
                    int y = chunk.getSectionYFromSectionIndex(index);
                    if (y >= view.minSectionY() && y <= view.maxSectionY() && sections[index].hasOnlyAir())
                        this.nearCoverage.observeEmpty(net.minecraft.core.SectionPos.asLong(x, y, z));
                }
            }
        }
    }

    /** Round 7 ({@code mc.lod.boundEmptyScan}, default true): air evidence only matters where installed L0 geometry is bound
     * (LodNearCoverage#update ignores every other section), so test just the bound sections, after this frame's rebinding.
     * Same frame and thread as the draw list, so the level is unchanged; replaces a scan of every section in the view area. */
    public static volatile boolean boundEmptyScan = Lod.flag("mc.lod.boundEmptyScan", true);
    private net.minecraft.client.renderer.ViewArea emptyView;

    /** The bound air sections only change with the bindings, the level (Lod#levelEpoch) or the view area; reuse them otherwise. */
    private final it.unimi.dsi.fastutil.longs.LongArrayList boundEmpty = new it.unimi.dsi.fastutil.longs.LongArrayList();
    private long boundEmptyBinds = -1L, boundEmptyEpoch = -1L, boundEmptyCenter, boundEmptyShape;
    private Object boundEmptyLevel;
    private long nearBindEpoch;

    private void captureBoundEmptySections() {
        var view = this.emptyView;
        this.emptyView = null;
        if (view == null) return;
        ClientLevel level = this.manager.level();
        var center = view.getCameraSectionPos();
        int radius = view.getViewDistance();
        int minY = view.minSectionY(), maxY = view.maxSectionY();
        long centerKey = center.asLong(), shape = (long)radius << 40 ^ (long)minY << 20 ^ maxY;
        if (this.boundEmptyBinds != this.nearBindEpoch || this.boundEmptyEpoch != Lod.levelEpoch || this.boundEmptyCenter != centerKey
                || this.boundEmptyShape != shape || this.boundEmptyLevel != level) {
            this.boundEmptyBinds = this.nearBindEpoch; this.boundEmptyEpoch = Lod.levelEpoch; this.boundEmptyCenter = centerKey;
            this.boundEmptyShape = shape; this.boundEmptyLevel = level;
            this.collectBoundEmpty(view, level, center, radius, minY, maxY);
            hoEmptyRebuilds++;
        }
        for (int i = 0, n = this.boundEmpty.size(); i < n; i++) {
            long support = this.boundEmptySupport.getLong(i);
            if (emptySupport && support != NO_SUPPORT) this.nearCoverage.observeEmpty(this.boundEmpty.getLong(i), support);
            else this.nearCoverage.observeEmpty(this.boundEmpty.getLong(i));
        }
    }

    /** Round 8 ({@code mc.lod.emptySupport}, default true): see LodNearCoverage#observeEmpty(long, long). */
    public static volatile boolean emptySupport = Lod.flag("mc.lod.emptySupport", true);
    private static final long NO_SUPPORT = Long.MIN_VALUE;
    private final it.unimi.dsi.fastutil.longs.LongArrayList boundEmptySupport = new it.unimi.dsi.fastutil.longs.LongArrayList();

    private void collectBoundEmpty(net.minecraft.client.renderer.ViewArea view, ClientLevel level, net.minecraft.core.SectionPos center,
            int radius, int minY, int maxY) {
        this.boundEmpty.clear();
        this.boundEmptySupport.clear();
        var pos = new net.minecraft.core.BlockPos.MutableBlockPos();
        long lastColumn = Long.MIN_VALUE;
        net.minecraft.world.level.chunk.LevelChunk chunk = null;
        for (var it = this.nearCoverage.boundSections().iterator(); it.hasNext();) {
            long section = it.nextLong();
            int x = net.minecraft.core.SectionPos.x(section), y = net.minecraft.core.SectionPos.y(section), z = net.minecraft.core.SectionPos.z(section);
            if (y < minY || y > maxY || Math.abs(x - center.x()) > radius || Math.abs(z - center.z()) > radius) continue;
            long column = (long)x << 32 | z & 0xFFFFFFFFL;
            if (column != lastColumn) {
                lastColumn = column;
                pos.set(x << 4, minY << 4, z << 4);
                chunk = view.getRenderSectionAt(pos) == null ? null : level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
            }
            if (chunk == null) continue;
            int index = chunk.getSectionIndexFromSectionY(y);
            var sections = chunk.getSections();
            if (index >= 0 && index < sections.length && sections[index].hasOnlyAir()) {
                this.boundEmpty.add(section);
                long support = NO_SUPPORT;
                for (int below = index - 1; below >= 0; below--) {
                    if (!sections[below].hasOnlyAir()) {
                        support = net.minecraft.core.SectionPos.asLong(x, chunk.getSectionYFromSectionIndex(below), z);
                        break;
                    }
                }
                this.boundEmptySupport.add(support);
            }
        }
    }

    /** The draw list omits offscreen sections. A ready mesh there may safely mask only
     * installed L0 geometry proven wholly outside this frame's exact view. Re-read its
     * readiness under the caller's copyLock; never retain a stale ready bit after reset.
     * Inside-view sections still require the ordinary draw-list observation. */
    void captureNearReadyOutsideView(net.minecraft.client.renderer.ViewArea view,
            SectionRenderDispatcher dispatcher, double[] planes,
            double camX, double camY, double camZ, long now, long fadeDuration) {
        if (view == null || planes == null || !stableNearCoverage) return;
        var center = view.getCameraSectionPos();
        int radius = view.getViewDistance();
        var pos = new net.minecraft.core.BlockPos.MutableBlockPos();
        for (int z = center.z() - radius; z <= center.z() + radius; z++) {
            for (int x = center.x() - radius; x <= center.x() + radius; x++) {
                for (int y = view.minSectionY(); y <= view.maxSectionY(); y++) {
                    long coordinate = net.minecraft.core.SectionPos.asLong(x, y, z);
                    if (!this.nearCoverage.hasBoundedFallback(coordinate)
                            || !nearFallbackOutside(planes, x * 16.0 - camX, y * 16.0 - camY, z * 16.0 - camZ)) continue;
                    pos.set(x << 4, y << 4, z << 4);
                    var section = view.getRenderSectionAt(pos);
                    if (section == null || section.getSectionNode() != coordinate) continue;
                    var mesh = section.getSectionMesh();
                    boolean ready = mesh == CompiledSectionMesh.EMPTY ? section.handoffEmptyReady()
                        : TerrainDrawList.nearMeshReady(mesh, dispatcher);
                    this.nearCoverage.observeOutside(coordinate, ready);
                    if (this.nearCoverage.fullVisibility(coordinate, section, section.handoffGeneration(), ready,
                            section.getVisibility(now, fadeDuration))) this.nearFullVisibilitySections++;
                    this.nearOutsideObserved++;
                }
            }
        }
    }

    static boolean nearFallbackOutside(double[] planes, double x, double y, double z) {
        // Packed valid cluster bounds cover [-32,32) relative to L0 origin, including
        // model overhangs. Two extra blocks exceed native cull/float-rounding margins.
        if (planes == null || planes.length != 20 || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return false;
        for (double p : planes) if (!Double.isFinite(p)) return false;
        for (int i = 0; i < 20; i += 4) {
            double a = planes[i], b = planes[i + 1], c = planes[i + 2];
            if (a * (x + (a >= 0 ? 34 : -34)) + b * (y + (b >= 0 ? 34 : -34))
                    + c * (z + (c >= 0 ? 34 : -34)) + planes[i + 3] < 0) return true;
        }
        return false;
    }

    /** Reuse the existing draw-list entry point rather than patching a mesher-hashed
     * consumer class. These are reads of the current frame, under its existing lock.
     * Any unavailable field disables the experiment; it never changes vanilla signatures. */
    private static final class NearDrawView {
        private static final java.lang.reflect.Field[] F = fields();
        private static boolean failed;
        private static java.lang.reflect.Field field(Class<?> c, String name) throws ReflectiveOperationException {
            var f = c.getDeclaredField(name);
            if (!f.trySetAccessible()) throw new IllegalAccessException(name);
            return f;
        }
        private static java.lang.reflect.Field[] fields() {
            try {
                return new java.lang.reflect.Field[]{
                    field(net.minecraft.client.renderer.LevelRenderer.class, "terrainDrawList"),
                    field(net.minecraft.client.renderer.LevelRenderer.class, "sectionRenderDispatcher"),
                    field(TerrainDrawList.class, "frustumCull"), field(TerrainDrawList.class, "cameraX"),
                    field(TerrainDrawList.class, "cameraY"), field(TerrainDrawList.class, "cameraZ"),
                    field(TerrainDrawList.class, "now"), field(TerrainDrawList.class, "fadeDuration"),
                    field(SectionRenderDispatcher.class, "copyLock")};
            } catch (ReflectiveOperationException | RuntimeException error) {
                Lod.LOGGER.warn("[lod] stable coverage fields unavailable; using ordinary coverage", error);
                return null;
            }
        }
        static void capture(LodGpu gpu) {
            if (F == null || failed) return;
            try {
                var renderer = net.minecraft.client.Minecraft.getInstance().levelRenderer;
                var list = (TerrainDrawList)F[0].get(renderer);
                var dispatcher = (SectionRenderDispatcher)F[1].get(renderer);
                if (list == null || dispatcher == null || !F[2].getBoolean(list)
                        || !((java.util.concurrent.locks.ReentrantLock)F[8].get(dispatcher)).isHeldByCurrentThread()) return;
                gpu.captureNearReadyOutsideView(renderer.viewArea(), dispatcher, list.framePlanes(),
                    F[3].getDouble(list), F[4].getDouble(list), F[5].getDouble(list), F[6].getLong(list), F[7].getLong(list));
            } catch (ReflectiveOperationException | RuntimeException error) {
                failed = true;
                Lod.LOGGER.warn("[lod] stable coverage unavailable; using ordinary coverage", error);
            }
        }
    }

    /** Round 7 ({@code mc.lod.bindViewOnly}, default true): only L0 nodes vanilla's view area can draw (Chebyshev radius + 1
     * around its camera section, rebound whenever that section changes) need bindings; the rest can never be covered.
     * Binding every installed L0 node cost ~2 ms per item build. */
    public static volatile boolean bindViewOnly = Lod.flag("mc.lod.bindViewOnly", true);
    private net.minecraft.client.renderer.ViewArea nearView;
    private long bindCenter = Long.MIN_VALUE;
    private int bindRadius = -1;

    private boolean bindsNode(LodManager.Node node) {
        if (node.level != 0) return false;
        if (this.bindRadius < 0) return true;
        int cx = net.minecraft.core.SectionPos.x(this.bindCenter), cz = net.minecraft.core.SectionPos.z(this.bindCenter);
        return Math.abs(node.nx - cx) <= this.bindRadius && Math.abs(node.nz - cz) <= this.bindRadius;
    }

    private void bindNearSections(List<GpuSection> sections) {
        if (sections == null) return;
        for (GpuSection section : sections) {
            int[] origin = section.origin;
            if (origin != null && origin[3] == 0)
                this.nearCoverage.bind(net.minecraft.core.SectionPos.asLong(origin[0] >> 4, origin[1] >> 4, origin[2] >> 4), section.slot,
                    section.preparedItems != null && section.preparedItems.hasBoundedSection());
        }
    }

    /** Round 7 ({@code mc.lod.bindAppended}, default true): the level-0 section lists the front (sync / sliced) item list was
     * appended from. A node re-uploaded while a sliced rebuild was still appending kept drawing its old slabs from that list,
     * but coverage bound the node's new ones: the old slabs were never masked and showed over vanilla's ice (z-fighting
     * lighter speckle) until the next rebuild, ~250 ms. Bind exactly what is drawn. */
    public static volatile boolean bindAppended = Lod.flag("mc.lod.bindAppended", true);
    private java.util.IdentityHashMap<LodManager.Node, List<GpuSection>> appendTarget, sliceNear, installedNear;

    /** Round 8 ({@code mc.lod.incrementalBind}, default true): a rebind (every item build and camera section move, ~290 us when
     * rebuilt from scratch) only unbinds nodes that left the bound set or changed their (immutable, published) section list, and
     * binds the new ones. Bindings end up identical to a full rebind; the epoch only advances when they actually changed. */
    public static volatile boolean incrementalBind = Lod.flag("mc.lod.incrementalBind", true);
    private record BoundNode(List<GpuSection> list, long[] sections, int[] slots, boolean[] bounded) { }
    private final java.util.IdentityHashMap<LodManager.Node, BoundNode> boundNear = new java.util.IdentityHashMap<>();
    private final java.util.IdentityHashMap<LodManager.Node, List<GpuSection>> bindWant = new java.util.IdentityHashMap<>();

    private void bindInstalledNearSections() {
        var view = this.nearView;
        if (bindViewOnly && view != null) {
            this.bindCenter = view.getCameraSectionPos().asLong();
            this.bindRadius = view.getViewDistance() + 1;
        } else { this.bindCenter = Long.MIN_VALUE; this.bindRadius = -1; }
        if (!incrementalBind) {
            this.nearBindEpoch++;
            this.nearCoverage.clearBindings();
            this.boundNear.clear();
            if (this.activeItems != null) {
                for (var entry : this.activeItems.geometry.entrySet())
                    if (this.bindsNode(entry.getKey())) this.bindNearSections(entry.getValue());
            } else if (bindAppended && this.installedNear != null) {
                for (var entry : this.installedNear.entrySet())
                    if (this.bindsNode(entry.getKey())) this.bindNearSections(entry.getValue());
            } else {
                for (LodManager.Node node : this.drawnSet) if (this.bindsNode(node)) this.bindNearSections(node.gpu);
                for (var entry : this.fades.entrySet())
                    if (!entry.getValue().in && this.bindsNode(entry.getKey()) && !this.drawnSet.contains(entry.getKey()))
                        this.bindNearSections(entry.getKey().gpu);
            }
            return;
        }
        var want = this.bindWant;
        want.clear();
        if (this.activeItems != null) {
            for (var entry : this.activeItems.geometry.entrySet())
                if (entry.getValue() != null && this.bindsNode(entry.getKey())) want.put(entry.getKey(), entry.getValue());
        } else if (bindAppended && this.installedNear != null) {
            for (var entry : this.installedNear.entrySet())
                if (entry.getValue() != null && this.bindsNode(entry.getKey())) want.put(entry.getKey(), entry.getValue());
        } else {
            for (LodManager.Node node : this.drawnSet) if (node.gpu != null && this.bindsNode(node)) want.put(node, node.gpu);
            for (var entry : this.fades.entrySet())
                if (!entry.getValue().in && entry.getKey().gpu != null && this.bindsNode(entry.getKey()) && !this.drawnSet.contains(entry.getKey()))
                    want.put(entry.getKey(), entry.getKey().gpu);
        }
        long before = this.nearCoverage.bindingRevision();
        // Switching from full rebinds: their bindings are not tracked per node.
        if (this.boundNear.isEmpty() && !this.nearCoverage.boundSections().isEmpty()) this.nearCoverage.clearBindings();
        for (var it = this.boundNear.entrySet().iterator(); it.hasNext();) {
            var e = it.next();
            BoundNode b = e.getValue();
            if (want.get(e.getKey()) == b.list() && !this.boundedChanged(b)) continue;
            for (int i = 0; i < b.sections().length; i++) this.nearCoverage.unbind(b.sections()[i], b.slots()[i], b.bounded()[i]);
            it.remove();
        }
        for (var e : want.entrySet()) if (!this.boundNear.containsKey(e.getKey())) this.boundNear.put(e.getKey(), this.bindNode(e.getValue()));
        want.clear();
        if (this.nearCoverage.bindingRevision() != before) this.nearBindEpoch++;
    }

    private BoundNode bindNode(List<GpuSection> sections) {
        int n = 0;
        for (GpuSection section : sections) if (section.origin != null && section.origin[3] == 0) n++;
        long[] keys = new long[n]; int[] slots = new int[n]; boolean[] bounded = new boolean[n];
        int i = 0;
        for (GpuSection section : sections) {
            int[] origin = section.origin;
            if (origin == null || origin[3] != 0) continue;
            keys[i] = net.minecraft.core.SectionPos.asLong(origin[0] >> 4, origin[1] >> 4, origin[2] >> 4);
            slots[i] = section.slot;
            bounded[i] = section.preparedItems != null && section.preparedItems.hasBoundedSection();
            this.nearCoverage.bind(keys[i], slots[i], bounded[i]);
            i++;
        }
        return new BoundNode(sections, keys, slots, bounded);
    }

    /** preparedItems is populated lazily on a published section: a full rebind would pick up its bounds. */
    private boolean boundedChanged(BoundNode b) {
        int i = 0;
        for (GpuSection section : b.list()) {
            int[] origin = section.origin;
            if (origin == null || origin[3] != 0) continue;
            if (b.bounded()[i++] != (section.preparedItems != null && section.preparedItems.hasBoundedSection())) return true;
        }
        return false;
    }


    /** Round 7 diagnostic ({@code mc.lod.coarseOverlapProbe}): coarse (level >= 1, never masked per section) nodes still drawn
     * or fading over a column vanilla lists this frame - both draw there (doubled translucent ice). */
    static final boolean coarseOverlapProbe = Boolean.getBoolean("mc.lod.coarseOverlapProbe");
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet probeColumns = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap probeFrames = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
    private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<String> probeInfo = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    private long probeOverlapFrames, probeT0;

    private void probeCoarseOverlap() {
        var view = this.nearView;
        if (view == null) return;
        this.nearCoverage.listedColumns(this.probeColumns);
        if (this.probeColumns.isEmpty()) return;
        long now = System.nanoTime();
        if (this.probeT0 == 0L) this.probeT0 = now;
        int ccx = view.getCameraSectionPos().x(), ccz = view.getCameraSectionPos().z();
        boolean any = false;
        java.util.List<LodManager.Node> nodes = new java.util.ArrayList<>(this.drawnSet);
        for (var e : this.fades.entrySet()) if (!this.drawnSet.contains(e.getKey())) nodes.add(e.getKey());
        for (LodManager.Node n : nodes) {
            if (n.level == 0 || n.gpu == null) continue;
            int size = 1 << n.level, x0 = n.nx << n.level, z0 = n.nz << n.level;
            int hits = 0;
            for (int z = z0; z < z0 + size; z++) for (int x = x0; x < x0 + size; x++)
                if (this.probeColumns.contains((long)x << 32 | z & 0xFFFFFFFFL)) hits++;
            if (hits == 0) continue;
            any = true;
            int f = this.probeFrames.addTo(n.key, 1);
            if (f == 0 && this.probeInfo.size() < 200) {
                Fade fade = this.fades.get(n);
                this.probeInfo.put(n.key, String.format(java.util.Locale.ROOT, "t=%dms L%d (%d,%d) chunks=%d hit=%d cam=(%d,%d) %s drawn=%b",
                    (now - this.probeT0) / 1_000_000L, n.level, x0, z0, size * size, hits, ccx, ccz,
                    fade == null ? "nofade" : fade.in ? "fadeIn" : "fadeOut", this.drawnSet.contains(n)));
            }
        }
        if (any) this.probeOverlapFrames++;
    }

    /** Round 7 diagnostic ({@code mc.lod.eventLog}): one line per frame of LOD state counters (absolute nanoTime first),
     * written to lod-events.txt in the game directory; lets a recorded flash be lined up with what the LOD did. */
    static final boolean EVENT_LOG = Boolean.getBoolean("mc.lod.eventLog");
    private final StringBuilder eventLog = new StringBuilder();
    private int eventLines;

    private void logDrawnDiff(java.util.Set<LodManager.Node> before, java.util.Set<LodManager.Node> after) {
        var view = this.nearView;
        int ccx = view == null ? 0 : view.getCameraSectionPos().x(), ccz = view == null ? 0 : view.getCameraSectionPos().z();
        StringBuilder b = new StringBuilder();
        for (LodManager.Node n : after) if (!before.contains(n)) this.describe(b.append(" +"), n, ccx, ccz);
        for (LodManager.Node n : before) if (!after.contains(n)) this.describe(b.append(" -"), n, ccx, ccz);
        this.eventLog.append("B ").append(System.nanoTime()).append(" build=").append(this.builds).append(" cam=").append(ccx).append(',').append(ccz)
            .append(b).append('\n');
    }

    private void describe(StringBuilder b, LodManager.Node n, int ccx, int ccz) {
        int size = 1 << n.level, x0 = n.nx << n.level, z0 = n.nz << n.level;
        int dx = Math.max(0, Math.max(x0 - ccx, ccx - (x0 + size - 1))), dz = Math.max(0, Math.max(z0 - ccz, ccz - (z0 + size - 1)));
        int sections = 0, l0 = 0;
        if (n.gpu != null) for (GpuSection g : n.gpu) { sections++; if (g.origin != null && g.origin[3] == 0) l0++; }
        Fade f = this.fades.get(n);
        b.append('L').append(n.level).append('@').append(x0).append(',').append(z0).append(" d=").append(dx).append(',').append(dz)
            .append(" gpu=").append(sections).append('/').append(l0).append(f == null ? "" : f.in ? " in" : " out")
            .append(n.gpu == null ? " nogpu" : "");
    }

    private void logFrame() {
        if (this.eventLines++ > 200_000) return;
        var view = this.nearView;
        this.eventLog.append(System.nanoTime()).append(' ').append(this.builds).append(' ').append(this.visibilityRevision)
            .append(' ').append(this.geometryEpoch).append(' ').append(this.reuseCullHits).append(' ').append(this.turnCaptureFrames)
            .append(' ').append(this.turnStripFrames).append(' ').append(this.turnSwaps).append(' ').append(this.nearCoverage.revision())
            .append(' ').append(this.nearMaskReplacements).append(' ').append(this.drawnSet.size()).append(' ').append(this.fades.size())
            .append(' ').append(this.activeItems != null ? 1 : 0).append(' ').append(this.nearCoverage.coveredCount())
            .append(' ').append(this.nearBindEpoch).append(' ').append(this.nearCoverage.boundSections().size())
            .append(' ').append(view == null ? "-" : view.getCameraSectionPos().x() + "," + view.getCameraSectionPos().z())
            .append(' ').append(this.indexAsyncSwaps).append('\n');
    }

    static final boolean UNMASKED_PROBE = Boolean.getBoolean("mc.lod.unmaskedProbe");
    private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap unmaskedCounts = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
    private final int[] unmaskedReasons = new int[3];
    private long unmaskedFrames;

    private void probeUnmasked() {
        this.nearCoverage.listedColumns(this.probeColumns);
        if (this.probeColumns.isEmpty()) return;
        this.unmaskedFrames++;
        this.nearCoverage.unmaskedInListedColumns(this.probeColumns, this.unmaskedCounts, this.unmaskedReasons);
    }

    String unmaskedJson() {
        StringBuilder top = new StringBuilder();
        var view = this.nearView;
        int ccx = view == null ? 0 : view.getCameraSectionPos().x(), ccz = view == null ? 0 : view.getCameraSectionPos().z();
        this.unmaskedCounts.long2IntEntrySet().stream().sorted((a, b) -> b.getIntValue() - a.getIntValue()).limit(25).forEach(e -> {
            long sec = e.getLongKey() >> 2; int r = (int)(e.getLongKey() & 3);
            top.append(top.length() == 0 ? "" : ",").append("\"").append(net.minecraft.core.SectionPos.x(sec)).append(',')
                .append(net.minecraft.core.SectionPos.y(sec)).append(',').append(net.minecraft.core.SectionPos.z(sec)).append(' ')
                .append("INR".charAt(r)).append("\":").append(e.getIntValue());
        });
        return "{\"frames\":" + this.unmaskedFrames + ",\"incomplete\":" + this.unmaskedReasons[0] + ",\"notListed\":" + this.unmaskedReasons[1]
            + ",\"readyUnmasked\":" + this.unmaskedReasons[2] + ",\"lastCam\":\"" + ccx + "," + ccz + "\",\"top\":{" + top + "}}";
    }

    String coarseOverlapJson() {
        if (EVENT_LOG && this.eventLog.length() > 0) {
            try {
                java.nio.file.Files.writeString(java.nio.file.Path.of("lod-events.txt"),
                    "t builds visRev geoEpoch reuseHits turnCapt turnStrip turnSwaps covRev maskRepl drawn fades activeItems covered bindEpoch bound cam idxSwaps\n" + this.eventLog);
            } catch (java.io.IOException ignored) {
            }
        }
        StringBuilder b = new StringBuilder("{\"frames\":" + this.probeOverlapFrames + ",\"nodes\":[");
        int i = 0;
        for (var e : this.probeInfo.long2ObjectEntrySet()) {
            if (i++ > 0) b.append(',');
            b.append('"').append(e.getValue()).append(" frames=").append(this.probeFrames.get(e.getLongKey())).append('"');
        }
        return b.append("]}").toString();
    }

    /** Installed geometry, NOT the resident map: a pending worker can still draw leased slabs. */
    private void updateNearCoverage() {
        if (!this.nearHandoffActive) return;
        long t0 = System.nanoTime();
        try { this.updateNearCoverage0(); } finally { hoUpdateNs += System.nanoTime() - t0; }
    }

    private void updateNearCoverage0() {
        long t0 = System.nanoTime();
        var view = this.nearView;
        boolean moved = bindViewOnly && view != null && (this.bindRadius != view.getViewDistance() + 1
            || net.minecraft.core.SectionPos.x(this.bindCenter) != view.getCameraSectionPos().x()
            || net.minecraft.core.SectionPos.z(this.bindCenter) != view.getCameraSectionPos().z());
        if (this.nearCoverageBuild != this.builds || moved) {
            this.bindInstalledNearSections();
            this.nearCoverageBuild = this.builds;
            hoRebinds++;
            hoBound = this.nearCoverage.boundSections().size();
        }
        long t1 = System.nanoTime();
        this.captureBoundEmptySections();
        if (coarseOverlapProbe) this.probeCoarseOverlap();
        if (UNMASKED_PROBE) this.probeUnmasked();
        if (EVENT_LOG) this.logFrame();
        long t2 = System.nanoTime();
        hoRebindNs += t1 - t0; hoEmptyNs += t2 - t1;
        boolean coverageChanged = this.nearCoverage.update();
        if (LodManager.HOLE_DIAG && System.nanoTime() - this.maskDiagNanos > 2_000_000_000L) {
            this.maskDiagNanos = System.nanoTime();
            it.unimi.dsi.fastutil.ints.IntOpenHashSet solid = new it.unimi.dsi.fastutil.ints.IntOpenHashSet();
            for (GpuSection g : this.resident) if (g.arena[0] >= 0 && g.quads[0] > 0) solid.add(g.slot);
            Lod.LOGGER.info("[lod] maskDiag {}", this.nearCoverage.maskDiag(solid::contains));
        }
        if (coverageChanged) {
            this.visibilityRevision++;
            this.invalidateReuse();
            // An uncovered section must not inherit a previous hidden section's occlusion verdict.
            if (!narrowCoverageReset || this.nearCoverage.changeNeedsHistoryReset()) {
                Arrays.fill(this.lastOcclFrame, Long.MIN_VALUE);
                this.nearHistoryResets++;
            } else this.nearHistoryKept++;
        }
    }

    /** Shared storage written only after the timeline proves the last side-cull use completed.
     * If completion reporting lags, replace/defer that buffer; never wait or overwrite in flight. */
    private GpuBuffer nearMask(Slot slot) {
        var timeline = ((com.mojang.renderpearl.backend.metal.MetalBuffer)this.sectionInfos).device().timeline();
        int entries = Math.max(1, this.nextSlot);
        boolean rewrite = slot.nearMaskRevision != this.nearCoverage.revision() || slot.nearMaskEntries != entries;
        if (slot.nearMask == null || slot.nearMask.size() < entries
            || rewrite && !LodNearCoverage.writable(slot.nearMaskLastUse, timeline.completedSeq())) {
            GpuBuffer next = RenderSystem.getDevice().createBuffer(() -> "LOD near coverage",
                GpuBuffer.USAGE_MAP_WRITE, entries);
            if (slot.nearMask != null) { slot.nearMask.close(); this.nearMaskReplacements++; }
            slot.nearMask = next;
            rewrite = true;
        }
        if (rewrite) {
            this.nearCoverage.write(MemoryUtil.memByteBuffer(MetalClusterCull.contents(slot.nearMask), entries), entries);
            slot.nearMaskRevision = this.nearCoverage.revision();
            slot.nearMaskEntries = entries;
        }
        slot.nearMaskLastUse = timeline.pendingSeq();
        return slot.nearMask;
    }

    boolean idle() {
        return this.manager.uploads.isEmpty()
            && (!LodManager.ATOMIC_MESH_HANDOFF || this.manager.meshHandoff.isEmpty()) && this.pendingItems == null;
    }

    long gpuBytes() {
        return this.gpuBytes;
    }

    private void ensureTables() {
        if (this.sectionInfos == null) {
            this.sectionInfos = RenderSystem.getDevice().createBuffer(() -> "LOD section infos", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_COPY_DST,
                16L * MAX_SECTIONS);
            this.sectionTable = RenderSystem.getDevice().createBuffer(() -> "LOD cull table", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_COPY_DST,
                (long)TABLE_BYTES * MAX_SECTIONS);
        }
        if (this.visBuffers[0] == null && Lod.OCCLUSION) {
            int maxArenas = (int)Math.min((long)MAX_ARENAS, Lod.GPU_BYTES / ARENA_BYTES + 2L);
            this.secVisBase = maxArenas * VIS_PER_ARENA;
            this.visEntries = this.secVisBase + MAX_SECTIONS;
            for (int i = 0; i < OCCL_LAG; i++) {
                int n = i;
                this.visBuffers[i] = RenderSystem.getDevice().createBuffer(() -> "LOD occlusion entries " + n, GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_COPY_DST,
                    4L * this.visEntries);
                MemoryUtil.memSet(MetalClusterCull.contents(this.visBuffers[i]), 0, 4L * this.visEntries);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------- occlusion

    /**
     * Round 3 (CONTRACT 16.S3-LOD-R3): LOD cluster occlusion by box queries. Each cluster owns the entry
     * {@code arena * VIS_PER_ARENA + firstQuad / 8} of {@link #visBuffer} (uint: stamp << 1 | visible). The cull kernel
     * (mcmtl_ext_lod_cull) appends a box for every cluster entirely inside the view and skips it when its entry says "tested in the
     * previous frame and no box fragment passed the depth test"; the box draw after the opaque LOD (ChunkSectionsToRender,
     * pipeline lod_occlusion_terrain_cluster) marks the entries of boxes with a visible fragment. Clusters becoming visible appear
     * one frame late (as with a previous-frame depth pyramid); everything not tested counts as visible.
     */
    // One visibility entry per eight physical record slots; sized for the ordinary compact arena.
    static final int VIS_PER_ARENA = 1 << 20;
    /** Box margin in blocks (the box faces stay in front of the cluster's own faces at its bounds). */
    static final float OCCL_MARGIN = Float.parseFloat(System.getProperty("mc.lod.occlMargin", "0.25"));
    /** Boxes closer than this to the eye plane (blocks) are never tested (always drawn). */
    static final float OCCL_EYE_MIN = 4.0F;
    /** Diagnostics ({@code mc.lod.diag} contains "nobox"): kernel path and buffers without the box draw (nothing is culled). */
    static final boolean OCCL_DIAG_NOBOX = System.getProperty("mc.lod.diag", "").contains("nobox");
    /**
     * Frames between a box test and its use ({@code mc.lod.occlLag}, 1 or 2; default 2): the entries live in a ring of that many
     * buffers, so the cull of frame N reads the boxes of frame N - lag. With lag 1 the cull waits for the previous frame's
     * fragment work (no overlap of consecutive frames on the GPU); with 2 that frame is normally complete.
     */
    static final int OCCL_LAG = Math.max(1, Math.min(2, Integer.getInteger("mc.lod.occlLag", 2)));
    private final @Nullable GpuBuffer[] visBuffers = new GpuBuffer[2];
    private int visEntries;
    /** Stage 3 r6: first visibility entry of the per-section region (entry = secVisBase + section slot). */
    private int secVisBase;
    private final GpuBuffer[] secRing = new GpuBuffer[6];
    private final long[] secRingBytes = new long[6];
    private int secCount;
    private int[] secItems = new int[4096];
    private @Nullable GpuBuffer secOcc;
    private long secBlock;
    private int occlStamp = 8;
    private final int[] boxDrawnStamp = new int[2];
    private final long[] lastOcclFrame = {-10L, -10L};
    private long jobExtra;
    private long occlBlock;
    private long occludedQuads;
    private long occludedSum;
    private long occludedFrames;
    private long boxesLast;
    private java.util.function.@Nullable Consumer<com.mojang.renderpearl.api.commands.RenderPass> occlusionDraw;

    private void resetVisibility(final int arena, final int start, final int quads) {
        for (GpuBuffer b : this.visBuffers) {
            if (b != null) {
                MemoryUtil.memSet(MetalClusterCull.contents(b) + 4L * ((long)arena * VIS_PER_ARENA + (start >> 3)), 0, 4L * (quads + 7 >> 3));
            }
        }
    }

    /** This frame's box draw (ChunkSectionsToRender, after the opaque LOD layers), or null. */
    java.util.function.@Nullable Consumer<com.mojang.renderpearl.api.commands.RenderPass> occlusionDraw() {
        return this.occlusionDraw;
    }

    // ---------------------------------------------------------------------------------------------------------------- uploads

    /** Uploads pending meshes (up to {@code budgetBytes}), frees deferred ranges, evicts unselected meshes over budget. */
    void uploadPending(final long budgetBytes, final double camX, final double camY, final double camZ) {
        if (this.closed) return;
        boolean useSet = residentSet;
        if (useSet != this.residentSetActive) {
            this.resident = useSet ? new java.util.LinkedHashSet<>(this.resident) : new ArrayList<>(this.resident);
            this.residentSetActive = useSet;
        }
        // Once per frame, publish the previous CPU frame before aging/reusing any ring slot.
        this.publishQuadWorkBytes();
        this.frame++;
        this.ensureTables();
        if (!this.c0Set || Math.abs(camX - this.c0x) > 4096.0 || Math.abs(camZ - this.c0z) > 4096.0 || Math.abs(camY - this.c0y) > 4096.0) {
            this.c0x = (int)Math.floor(camX);
            this.c0y = (int)Math.floor(camY);
            this.c0z = (int)Math.floor(camZ);
            this.c0Set = true;
            long table = MetalClusterCull.contents(this.sectionTable);
            for (LodGpu.GpuSection s : this.resident) {
                this.writeTable(table, s.slot, this.originOf(s));
            }
            // The active CPU snapshot may still draw a retired list under its lease.
            // Such sections are no longer in resident/origins, but need the same new base.
            if (this.activeItems != null) for (var sections : this.activeItems.geometry.values())
                for (GpuSection s : sections) this.writeTable(table, s.slot, s.origin);
        }
        this.releaseDeferred();
        if (COMPACT_WORK_BUFFERS) this.retireInactiveJobs();
        // Even a completed task holds this gate until frame() accepts/discards it. Never mutate
        // the candidate's GPU identities between worker completion and render-thread installation.
        if (this.pendingItems != null) { this.asyncUploadDefers++; return; }
        long t0 = System.nanoTime();
        long done = 0L;
        LodManager.Node n;
        while (done < budgetBytes) {
            List<LodMesher.Section> sections;
            long claimedStamp = 0L, need;
            if (LodManager.ATOMIC_MESH_HANDOFF) {
                var result = this.manager.meshHandoff.poll();
                if (result == null) break;
                n = result.key();
                sections = result.payload();
                claimedStamp = result.requestedStamp();
                need = result.bytes();
            } else {
                n = this.manager.uploads.poll();
                if (n == null) break;
                sections = n.pending;
                if (sections == null) continue;
                n.pending = null;
                need = 0L;
                for (LodMesher.Section s : sections) need += s.bytes();
            }
            if (this.gpuBytes + need > Lod.GPU_BYTES) {
                this.evict(need);
            }
            this.free(n);
            List<GpuSection> out = new ArrayList<>(sections.size());
            boolean complete = true;
            try {
                for (LodMesher.Section s : sections) {
                    GpuSection g = this.upload(s);
                    if (g == null) {
                        complete = false;
                        break;
                    }
                    out.add(g);
                    done += g.bytes;
                }
            } catch (RuntimeException | Error failure) {
                // Earlier slabs have resident entries even though the node is not published yet.
                // Give them the same retirement/accounting cleanup as a refused partial node.
                // Do not reset meshStamp: a producer may already have queued a newer result.
                n.gpu = out;
                this.free(n);
                throw failure;
            }
            // Publication invariant for node packets: this fresh list is never mutated again. All section
            // slot/range/cluster fields are fixed; only preparedItems may be populated lazily. No worker owns out.
            n.gpu = out;
            if (!complete) {
                // Over budget even after eviction: all or nothing (a partial node would show holes); retried when the
                // selection asks for it again.
                this.free(n);
                n.refusedStamp = LodManager.ATOMIC_MESH_HANDOFF ? claimedStamp : n.pendingStamp;
                this.budgetRefusals++;
                break;
            }
            n.gpuStamp = LodManager.ATOMIC_MESH_HANDOFF ? claimedStamp : n.pendingStamp;
            long nb = 0L;
            for (GpuSection g : out) {
                nb += g.bytes;
            }
            n.gpuBytes = nb;
            this.manager.noteMeshBytes(n.level, nb);
            n.resident = true;
            this.itemsDirty = true;
        }
        this.uploadedBytes += done;
        this.uploadNanos += System.nanoTime() - t0;
        if (this.gpuBytes > Lod.GPU_BYTES * 9 / 10) {
            this.evict(0L);
        }
    }

    private final java.util.IdentityHashMap<GpuSection, int[]> origins = new java.util.IdentityHashMap<>();

    private int[] originOf(final GpuSection s) {
        return this.origins.get(s);
    }

    private @Nullable GpuSection upload(final LodMesher.Section s) {
        int slot = this.freeSlotCount > 0 ? this.freeSlots[--this.freeSlotCount] : this.nextSlot < MAX_SECTIONS ? this.nextSlot++ : -1;
        if (slot < 0) {
            return null;
        }
        // Includes slot/arena range reuse and partial uploads that are undone.
        this.geometryEpoch++;
        GpuSection g = new GpuSection();
        g.slot = slot;
        for (int l = 0; l < 3; l++) {
            ByteBuffer v = s.vertices[l];
            if (v == null || s.clusters[l] == null) {
                continue;
            }
            int quads = s.quads[l];
            if (quads == 0) continue;
            boolean compact = s.compact[l];
            int quadBytes = compact ? LodCompact.RECORD_BYTES : QUAD_BYTES;
            // Compact layers may carry lit-merge cell tables after their records (LodCompact).
            long payloadBytes = compact ? Math.max((long)quads * quadBytes, v.remaining()) : (long)quads * quadBytes;
            int allocQuads = Math.toIntExact((payloadBytes + quadBytes - 1L) / quadBytes + 7L) & ~7;
            int arenaIndex = -1;
            int start = -1;
            for (Arena a : this.arenas) {
                if (a.compact != compact) {
                    continue;
                }
                start = a.alloc(allocQuads);
                if (start >= 0) {
                    arenaIndex = a.index;
                    break;
                }
            }
            if (start < 0) {
                if (this.arenas.size() >= MAX_ARENAS
                    || (long)(this.arenas.size() + 1) * ARENA_BYTES > Lod.GPU_BYTES + ARENA_BYTES - 1) {
                    this.undo(g, slot);
                    return null;
                }
                int idx = this.arenas.size();
                GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "LOD vertex arena " + idx, GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, ARENA_BYTES);
                Arena a = new Arena(buffer, idx, compact);
                this.arenas.add(a);
                start = a.alloc(allocQuads);
                arenaIndex = idx;
                if (start < 0) {
                    this.undo(g, slot);
                    return null;
                }
            }
            Arena a = this.arenas.get(arenaIndex);
            long destination = a.base + (long)start * a.quadBytes;
            MemoryUtil.memCopy(MemoryUtil.memAddress(v), destination, payloadBytes);
            g.arena[l] = arenaIndex;
            g.firstQuad[l] = start;
            g.quads[l] = quads;
            g.allocQuads[l] = allocQuads;
            this.resetVisibility(arenaIndex, start, allocQuads);
            g.clusters[l] = s.clusters[l];
            g.bytes += payloadBytes;
            g.allocationBytes += (long)allocQuads * a.quadBytes;
            this.quadsByLayerLevel[l][Math.min(7, s.level)] += quads;
        }
        if (asyncItems && cacheItems) {
            // One-time per uploaded section, inside the existing upload budget, not a scan/repack
            // during capture. This also warms an ON-at-startup game without relying on an OFF arm.
            long begin = System.nanoTime();
            try {
                this.sectionItems(g);
            } catch (RuntimeException | Error failure) {
                this.undo(g, slot); // Unpublished section still owns all of these ranges and its slot.
                throw failure;
            }
            this.asyncUploadRecordNs += System.nanoTime() - begin;
            this.asyncUploadRecordSections++;
        }
        long infos = MetalClusterCull.contents(this.sectionInfos) + 16L * slot;
        MemoryUtil.memPutInt(infos, s.originX);
        MemoryUtil.memPutInt(infos + 4, s.originY);
        MemoryUtil.memPutInt(infos + 8, s.originZ);
        MemoryUtil.memPutFloat(infos + 12, 1.0F);
        int[] origin = {s.originX, s.originY, s.originZ, s.level};
        g.origin = origin;
        this.origins.put(g, origin);
        this.writeTable(MetalClusterCull.contents(this.sectionTable), slot, origin);
        this.resident.add(g);
        this.gpuBytes += g.bytes;
        this.residentAllocationBytes += g.allocationBytes;
        return g;
    }

    /** A section that did not fit: give back the ranges it already took (never drawn: no deferral) and its slot. */
    private void undo(final GpuSection g, final int slot) {
        // Upload/acquisition are render-thread owned: this unpublished section cannot be in a snapshot.
        for (int l = 0; l < 3; l++) {
            if (g.arena[l] >= 0) {
                this.arenas.get(g.arena[l]).release(g.firstQuad[l], g.allocQuads[l]);
            }
        }
        this.freeSlots[this.freeSlotCount++] = slot;
    }

    private void writeTable(final long table, final int slot, final int[] origin) {
        long at = table + (long)TABLE_BYTES * slot;
        MemoryUtil.memPutFloat(at, (float)(origin[0] - this.c0x));
        MemoryUtil.memPutFloat(at + 4, (float)(origin[1] - this.c0y));
        MemoryUtil.memPutFloat(at + 8, (float)(origin[2] - this.c0z));
        MemoryUtil.memPutFloat(at + 12, (1 << origin[3]) / 16.0F);
        MemoryUtil.memPutFloat(at + 16, (float)(this.c0x - origin[0]));
        MemoryUtil.memPutFloat(at + 20, (float)(this.c0y - origin[1]));
        MemoryUtil.memPutFloat(at + 24, (float)(this.c0z - origin[2]));
        MemoryUtil.memPutFloat(at + 28, 0.0F);
    }

    /** Retire ranges/slots until age > RING + 2 AND no snapshot lease can still refer to them. */
    void free(final LodManager.Node n) {
        if (this.closed) return;
        List<GpuSection> old = n.gpu;
        this.releaseNodeItems(old); // Including OFF-after-ON; drop the list key before slots/ranges can retire.
        if (old == null) {
            return;
        }
        if (!old.isEmpty()) this.geometryEpoch++;
        long retirementEpoch = old.isEmpty() ? 0L : this.geometryLeases.retire();
        n.gpu = null;
        n.gpuBytes = 0L;
        n.resident = false;
        n.gpuStamp = -1L;
        for (GpuSection g : old) {
            this.releaseSectionItems(g);
            for (int l = 0; l < 3; l++) {
                if (g.arena[l] >= 0) {
                    this.deferredFrees.add(new long[]{this.frame, g.arena[l], g.firstQuad[l], g.allocQuads[l], -1, retirementEpoch});
                }
            }
            this.deferredFrees.add(new long[]{this.frame, -1, 0, 0, g.slot, retirementEpoch});
            this.gpuBytes -= g.bytes;
            this.residentAllocationBytes -= g.allocationBytes;
            this.resident.remove(g);
            if (this.residentSetActive) this.residentSetRemovals++; else this.residentListRemovals++;
            this.origins.remove(g);
        }
        this.itemsDirty = true;
    }

    private void releaseDeferred() {
        // One compacting pass (removing entries one by one was quadratic: 30-50 ms stalls after large retirements).
        int keep = 0;
        for (int i = 0, n = this.deferredFrees.size(); i < n; i++) {
            long[] f = this.deferredFrees.get(i);
            if (!this.geometryLeases.canRecycle(f[5], f[0], this.frame, RING + 2)) {
                this.deferredFrees.set(keep++, f);
                continue;
            }
            // Deferred rebuilds (itemMinMs): the draw list built before this retirement may still be current; recycle only
            // once a list built after the retirement has itself aged past the ring.
            if (!asyncItems && (this.lastItemBuildStartFrame <= f[0] || this.frame - this.lastItemBuildFrame <= RING + 2)) {
                this.deferredFrees.set(keep++, f);
                continue;
            }
            if (f[4] >= 0) {
                this.freeSlots[this.freeSlotCount++] = (int)f[4];
            } else {
                this.arenas.get((int)f[1]).release((int)f[2], (int)f[3]);
            }
        }
        this.deferredFrees.subList(keep, this.deferredFrees.size()).clear();
        for (int i = this.retired.size() - 1; i >= 0; i--) {
            BufferRetirement retirement = this.retiredAt.get(i);
            if (this.geometryLeases.canRecycle(retirement.epoch(), retirement.frame(), this.frame, 8)) {
                GpuBuffer buffer = this.retired.remove(i);
                this.closeWorkBuffer(buffer);
                Long quadBytes = this.retiredQuadBuffers.remove(buffer);
                if (quadBytes != null) this.retiredQuadBytes -= quadBytes;
                this.retiredAt.remove(i);
            }
        }
    }

    /** Over budget: free resident nodes that are not in the current selection, least recently selected first. */
    private long budgetRefusals;
    /** Stats: quads uploaded per layer (SOLID, CUTOUT, TRANSLUCENT) and level (cumulative, re-meshes included). */
    private final long[][] quadsByLayerLevel = new long[3][8];
    /** Stats: quads in the drawn set (before the GPU cull) per level. */
    private long[] drawnQuadsByLevel = new long[8];
    /** Stage 3 r6: submitted quads per facing slot (TerrainMeshInfo: 4 = unassigned, never back-face culled). */
    private long[] drawnQuadsBySlot = new long[8];

    private void evict(final long need) {
        LodManager.Selection sel = this.manager.selection();
        java.util.Set<LodManager.Node> keep = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        keep.addAll(Arrays.asList(sel.nodes()));
        List<LodManager.Node> candidates = new ArrayList<>();
        this.manager.forEachNode(n -> {
            if (n.gpu != null && !keep.contains(n)) {
                candidates.add(n);
            }
        });
        candidates.sort((a, b) -> Long.compare(a.lastSelectedFrame, b.lastSelectedFrame));
        for (LodManager.Node n : candidates) {
            if (this.gpuBytes + need <= Lod.GPU_BYTES * 7 / 10) {
                break;
            }
            this.free(n);
            n.meshStamp = -1L;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------- frame

    /**
     * The frame's LOD draws: rebuilds the cluster items when the selection or residency changed, then runs the cull kernel.
     * Null when nothing is drawn.
     */
    @Nullable List<TerrainClusters.Draw> frame(final ClientLevel level, final double @Nullable [] planes, final boolean frustum, final double camX,
        final double camY, final double camZ, final double eyeX, final double eyeY, final double eyeZ, final boolean chunksChanged) {
        this.farViewReady = false;
        if (this.closed) return null;
        this.occlusionDraw = null;
        if (this.failed) {
            if (this.nearCoverage != null) this.nearCoverage.clearExposure();
            this.invalidateReuse();
            return null;
        }
        LodManager.Selection sel = this.manager.selection();
        long nowNanos = System.nanoTime();
        if (chunksChanged) this.chunkRevision++;
        this.updateFades(nowNanos);
        boolean asyncHandled = this.prepareItemsAsync(level, sel, nowNanos);
        if (plantsL0Off != this.builtPlantsL0Off) { this.builtPlantsL0Off = plantsL0Off; this.itemsDirty = true; this.invalidateReuse(); }
        if (this.sliceOrder == null && this.realPendingChanged(level)) this.itemsDirty = true;
        if (Lod.drawLevels != this.builtDrawLevels) { this.builtDrawLevels = Lod.drawLevels; this.itemsDirty = true; this.invalidateReuse(); }
        if (this.sliceOrder != null) {
            if (chunksChanged) this.itemsDirty = true;   // the slice snapshot predates this change
            this.sliceStep(nowNanos);
        }
        boolean itemsWanted = this.sliceOrder == null && !asyncHandled
            && (sel.version() != this.builtSelection || this.itemsDirty || chunksChanged);
        int minMs = itemMinMs;
        if (itemsWanted && !chunksChanged && minMs > 0 && nowNanos - this.lastItemBuildNanos < minMs * 1_000_000L) {
            itemsWanted = false;
            this.itemBuildsDeferred++;
        }
        final boolean sliced = itemsWanted && sliceUs > 0 && !ITEM_BUILD_PROFILE && !asyncItems && cacheItems && !this.builtPlantsL0Off;
        if (sliced) {
            this.lastItemBuildNanos = nowNanos;
            this.sliceBegin(level, sel, nowNanos);
            itemsWanted = false;
        }
        if (!asyncHandled && itemsWanted) {
            this.lastItemBuildNanos = nowNanos;
            this.lastItemBuildFrame = this.frame;
            this.lastItemBuildStartFrame = this.frame;
            final boolean cachedItems = cacheItems && !this.builtPlantsL0Off;
            long b0 = System.nanoTime();
            try {
                if (ITEM_BUILD_PROFILE) this.itemBuildProfile.begin(b0, cachedItems);
                this.buildItems(level, sel, nowNanos, cachedItems);
            } catch (RuntimeException | Error error) {
                if (ITEM_BUILD_PROFILE) this.itemBuildProfile.finish(false, System.nanoTime());
                this.releaseAllNodeItems();
                // Removing the unconditional build invalidation must not preserve a partial/failed build.
                this.itemsDirty = true;
                this.invalidateReuse();
                throw error;
            }
            long elapsed = System.nanoTime() - b0;
            if (ITEM_BUILD_PROFILE) this.itemBuildProfile.finish(true, b0 + elapsed);
            this.buildNanos += elapsed;
            this.builds++;
            int mode = cachedItems ? 1 : 0;
            this.itemModeBuilds[mode]++;
            this.itemModeBuildNanos[mode] += elapsed;
            int groupMode = (cachedItems ? 2 : 0) | (this.buildCacheGroups ? 1 : 0);
            this.groupModeBuilds[groupMode]++;
            this.groupModeBuildNanos[groupMode] += elapsed;
            int nodeMode = this.buildCacheNodeItems ? 1 : 0;
            this.nodeModeBuilds[nodeMode]++;
            this.nodeModeBuildNanos[nodeMode] += elapsed;
        }
        this.updateNearCoverage();
        int jobCount = 0;
        for (GroupItems g : this.groups) {
            if (g.count > 0) {
                jobCount++;
            }
        }
        if (jobCount == 0) {
            if (this.nearCoverage != null) this.nearCoverage.clearExposure();
            this.invalidateReuse();
            return null;
        }
        GpuBuffer items = this.itemRing[this.itemRingIndex];
        if (items == null) {
            if (this.nearCoverage != null) this.nearCoverage.clearExposure();
            this.invalidateReuse();
            return null;
        }
        this.itemRingLastUse[this.itemRingIndex] = this.frame;
        this.occlusionDraw = null;
        if (this.jobs == 0L) {
            this.jobs = MemoryUtil.nmemAlloc(4096L * MetalClusterCull.JOB_SIZE);
            this.view = MemoryUtil.nmemCalloc(1L, MetalClusterCull.VIEW_SIZE_LOD);
        }
        // A moving view keeps the existing occlusion/raster path. Admit a complete cache capture
        // only after an identical culling view; the later shader-input key still checks actual projection.
        double[] reuseView = null;
        boolean reuseCaptureView = false;
        double[] viewPlanes = planes;
        boolean turnCapture = false, turnSwap = false, stripFrame = false;
        int turnPx = 0, turnPy = 0, stripIndex = 0;
        int strips = Math.max(1, Math.min(12, turnStrips));
        if (LodReuse.enabled && LodReuse.turn && planes != null && frustum) {
            if (this.reuse.takeTurnDrop()) { this.turnKey = null; this.stripNext = -1; }
            double[] camEye = {camX, camY, camZ, eyeX, eyeY, eyeZ};
            var mainTarget = net.minecraft.client.Minecraft.getInstance().gameRenderer.mainRenderTarget();
            float pad = Math.max(0.0F, Math.min(0.5F, turnPad));
            turnPx = (int)Math.ceil(mainTarget.width * pad / 8.0) * 8;
            turnPy = (int)Math.ceil(mainTarget.height * pad / 8.0) * 8;
            boolean eyeStill = Arrays.equals(camEye, this.turnLastCamEye);
            double perFrame = eyeStill && this.turnLastPlanes != null ? rotationAngle(this.turnLastPlanes, planes) : Double.POSITIVE_INFINITY;
            this.turnLastPlanes = planes.clone();
            this.turnLastCamEye = camEye;
            this.reuseLastView = null;
            boolean frontOk = this.turnKey != null && Arrays.equals(camEye, this.turnCamEye) && turnPx == this.turnPadX && turnPy == this.turnPadY
                    && this.reuseCullDraws != null && this.reuseCullGeometry == this.geometryEpoch
                    && this.reuseCullMembership == this.builds && this.reuseCullVisibility == this.visibilityRevision
                    && Arrays.equals(this.reuseCullKey, this.turnKey)
                    && rotationAngle(this.turnSrcPlanes, planes) <= 0.85 * this.turnSafe;
            if (this.stripNext >= 0 && !(frontOk && strips > 1 && this.fades.isEmpty() && this.geometryEpoch == this.stripGeometry
                    && this.builds == this.stripBuilds && this.visibilityRevision == this.stripVisibility)) {
                this.stripNext = -1;
                this.turnStripAborts++;
            }
            if (frontOk && this.stripNext < 0 && strips > 1 && eyeStill && this.fades.isEmpty()
                    && (rotationAngle(this.turnSrcPlanes, planes) > turnStripStart * this.turnSafe || this.reuse.softAging())) {
                double[] wide = LodFarCamera.widenFrustumPlanes(planes, new LodFarCamera.Viewport(mainTarget.width, mainTarget.height),
                    new LodFarCamera.Viewport(mainTarget.width + 2 * turnPx, mainTarget.height + 2 * turnPy));
                double safe = safeAngle(planes, wide);
                if (perFrame * (strips + 1 + Math.max(1, turnMinFrames)) <= safe) {
                    this.stripNext = 0;
                    this.stripTotal = perFrame == 0.0 ? Math.max(strips, Math.min(64, turnStillStrips)) : strips;
                    this.stripSrcPlanes = planes.clone();
                    this.stripWide = wide;
                    this.stripSafe = safe;
                    this.stripGeometry = this.geometryEpoch;
                    this.stripBuilds = this.builds;
                    this.stripVisibility = this.visibilityRevision;
                }
            }
            if (frontOk && this.stripNext >= 0) {
                // Front keeps compositing (and its list stays the live/translucent list); this frame culls one back strip,
                // or, after the last strip, the back view's whole guard list, which then becomes the front.
                if (this.stripNext < this.stripTotal) {
                    this.reuse.source(this.geometryEpoch, this.builds, this.visibilityRevision, !this.fades.isEmpty(), this.turnKey);
                    this.reuse.turnFrame(false, turnPx, turnPy);
                    this.reuseCullSlot.lastDrawFrame = this.frame;
                    for (int k = 0; k < this.reuseCullSlot.used; k++) {
                        Job retained = this.reuseCullSlot.jobs.get(k);
                        retained.observe(this.frame, retained.quadCount, retained.quadCapacity, VISIBLE_QUAD_BYTES, RING);
                    }
                    stripFrame = true;
                    stripIndex = this.stripNext++;
                    viewPlanes = stripPlanes(this.stripWide, stripIndex, this.stripTotal);
                    this.turnStripFrames++;
                } else {
                    this.stripNext = -1;
                    viewPlanes = this.stripWide;
                    reuseCaptureView = true;
                    turnCapture = true;
                    turnSwap = true;
                    this.turnSrcPlanes = this.stripSrcPlanes;
                    this.turnSafe = this.stripSafe;
                    this.turnSwaps++;
                }
            } else if (frontOk) {
                // Pure rotation inside the guard: the capture's guard list covers this view; the composite reprojects.
                this.reuse.source(this.geometryEpoch, this.builds, this.visibilityRevision, !this.fades.isEmpty(), this.turnKey);
                this.reuse.turnFrame(false, turnPx, turnPy);
                this.reuseCullSlot.lastDrawFrame = this.frame;
                for (int k = 0; k < this.reuseCullSlot.used; k++) {
                    Job retained = this.reuseCullSlot.jobs.get(k);
                    retained.observe(this.frame, retained.quadCount, retained.quadCapacity, VISIBLE_QUAD_BYTES, RING);
                }
                this.reuseCullHits++;
                this.turnRetained++;
                return this.reuseCullDraws;
            }
            if (!frontOk) { this.turnKey = null; this.stripNext = -1; }
            if (!frontOk) {
            double[] wide = LodFarCamera.widenFrustumPlanes(planes, new LodFarCamera.Viewport(mainTarget.width, mainTarget.height),
                new LodFarCamera.Viewport(mainTarget.width + 2 * turnPx, mainTarget.height + 2 * turnPy));
            double safe = safeAngle(planes, wide);
            if (eyeStill && this.fades.isEmpty() && perFrame * Math.max(1, turnMinFrames) <= safe) {
                viewPlanes = wide;
                reuseCaptureView = true;
                turnCapture = true;
                this.turnCamEye = camEye;
                this.turnSrcPlanes = planes.clone();
                this.turnSafe = safe;
                this.turnPadX = turnPx;
                this.turnPadY = turnPy;
                this.turnCaptureFrames++;
            }
            }
        } else if (LodReuse.enabled) {
            reuseView = new double[27];
            if (planes != null) System.arraycopy(planes, 0, reuseView, 0, 20);
            reuseView[20] = camX; reuseView[21] = camY; reuseView[22] = camZ;
            reuseView[23] = eyeX; reuseView[24] = eyeY; reuseView[25] = eyeZ;
            reuseView[26] = frustum && planes != null ? 1.0 : 0.0;
            reuseCaptureView = Arrays.equals(this.reuseLastView, reuseView);
        }
        if (!(LodReuse.enabled && LodReuse.turn && planes != null && frustum)) this.reuseLastView = reuseView;
        for (int i = 0; i < 20; i++) {
            MemoryUtil.memPutFloat(this.view + 4L * i, viewPlanes == null ? 0.0F : (float)viewPlanes[i]);
        }
        MemoryUtil.memPutFloat(this.view + 80, (float)TerrainRuns.EPS);
        // Round 4: flag 8 = fused LOD cull (one concurrent dispatch per job, mcmetal_clusters.m mcmtl_lod_cull_fused).
        boolean secMode = !reuseCaptureView && !stripFrame && Lod.secOccl && Lod.fusedCull && Lod.occlude && MetalClusterCull.lodCullSecAvailable() && this.secCount > 0
            && this.secRing[this.itemRingIndex] != null;
        // Preserve frustum, facing (2), and persistent section positions (4) for the original cull.
        MemoryUtil.memPutInt(this.view + 84, (frustum && planes != null && !DIAG_NO_FRUSTUM ? 1 : 0) | (DIAG_NO_FACING ? 0 : 2) | 4 | (Lod.fusedCull ? 8 : 0) | (secMode ? 16 : 0));
        MemoryUtil.memPutFloat(this.view + 88, TerrainClusters.BOUNDS_MARGIN);
        MemoryUtil.memPutFloat(this.view + 96, (float)(camX - this.c0x));
        MemoryUtil.memPutFloat(this.view + 100, (float)(camY - this.c0y));
        MemoryUtil.memPutFloat(this.view + 104, (float)(camZ - this.c0z));
        MemoryUtil.memPutFloat(this.view + 112, (float)(eyeX - this.c0x));
        MemoryUtil.memPutFloat(this.view + 116, (float)(eyeY - this.c0y));
        MemoryUtil.memPutFloat(this.view + 120, (float)(eyeZ - this.c0z));
        this.farViewFrame = this.frame;
        this.farViewNanos = nowNanos;
        this.farCameraX = camX; this.farCameraY = camY; this.farCameraZ = camZ;
        this.farEyeX = eyeX; this.farEyeY = eyeY; this.farEyeZ = eyeZ;
        this.farViewReady = true;
        byte[] reuseSource = null;
        if (reuseCaptureView) {
            reuseSource = new byte[MetalClusterCull.VIEW_SIZE_LOD + 60];
            java.nio.ByteBuffer keyView = java.nio.ByteBuffer.wrap(reuseSource).order(java.nio.ByteOrder.nativeOrder());
            keyView.put(MemoryUtil.memByteBuffer(this.view, MetalClusterCull.VIEW_SIZE_LOD));
            keyView.putInt(this.c0x).putInt(this.c0y).putInt(this.c0z);
            keyView.putDouble(camX).putDouble(camY).putDouble(camZ).putDouble(eyeX).putDouble(eyeY).putDouble(eyeZ);
            this.reuse.source(this.geometryEpoch, this.builds, this.visibilityRevision, !this.fades.isEmpty(), reuseSource);
            if (turnCapture) {
                this.turnKey = reuseSource;
                this.reuse.turnFrame(true, turnPx, turnPy);
                if (turnSwap) this.reuse.turnSwap();
            }
            if (!turnCapture && this.reuseCullDraws != null && this.reuseCullGeometry == this.geometryEpoch
                    && this.reuseCullMembership == this.builds && this.reuseCullVisibility == this.visibilityRevision
                    && Arrays.equals(this.reuseCullKey, reuseSource)) {
                // This list was produced without temporal/near occlusion. It depends only on these
                // exact inputs, so even a lighting/atlas refresh can reuse it without a GPU cull.
                this.reuseCullSlot.lastDrawFrame = this.frame;
                for (int k = 0; k < this.reuseCullSlot.used; k++) {
                    Job retained = this.reuseCullSlot.jobs.get(k);
                    retained.observe(this.frame, retained.quadCount, retained.quadCapacity, VISIBLE_QUAD_BYTES, RING);
                }
                this.reuseCullHits++;
                return this.reuseCullDraws;
            }
        } else if (!stripFrame) this.reuse.invalidate();
        if (!stripFrame) {
            this.reuseCullDraws = null;
            this.reuseCullKey = null;
        }
        // A retained cull list can be drawn on many frames. Ring index alone no longer proves
        // that its argument/quad buffers are out of flight when we leave the stationary view.
        int slotIndex = (int)(this.frame % this.slots.length);
        for (int tries = 0; tries < this.slots.length && this.slots[slotIndex] != null
                && this.frame - this.slots[slotIndex].lastDrawFrame < RING; tries++) {
            slotIndex = (slotIndex + 1) % this.slots.length;
        }
        Slot slot = this.slots[slotIndex];
        if (slot != null && this.frame - slot.lastDrawFrame < RING) {
            throw new IllegalStateException("LOD cull ring still in flight");
        }
        if (slot == null) {
            slot = new Slot();
            this.slots[slotIndex] = slot;
        } else {
            // Stats: quads that survived the GPU cull RING frames ago (the slot's draws are complete by now).
            long visible = 0L;
            long[] byLevel = new long[8];
            for (int i = 0; i < slot.used && i < slot.jobs.size(); i++) {
                Job jb = slot.jobs.get(i);
                if (jb.args != null) {
                    long indexCount = MemoryUtil.memGetInt(MetalClusterCull.contents(jb.args)) & 0xFFFFFFFFL;
                    long v = indexCount / 6L;
                    visible += v;
                    if (jb.level >= 0) {
                        byLevel[Math.min(7, jb.level)] += v;
                    }
                }
            }
            this.visibleQuads = visible;
            for (int l = 0; l < 8; l++) {
                this.visibleByLevelSum[l] += byLevel[l];
            }
            this.visibleSum += visible;
            this.visibleFrames++;
            if (slot.occlUsed && slot.occlParams != null) {
                long pr = MetalClusterCull.contents(slot.occlParams);
                this.occludedQuads = MemoryUtil.memGetInt(pr + 24) & 0xFFFFFFFFL;
                this.boxesLast = MemoryUtil.memGetInt(pr + 20) & 0xFFFFFFFFL;
                this.occludedSum += this.occludedQuads;
                this.occludedFrames++;
            }
        }
        slot.lastDrawFrame = this.frame;
        long tm = System.nanoTime();
        GpuBuffer coverage = this.nearHandoffActive ? this.nearMask(slot) : null;
        hoMaskNs += System.nanoTime() - tm;
        List<TerrainClusters.Draw> draws = new ArrayList<>(jobCount);
        // Previous args have been read above. Rebuild only the order list; keyed ownership persists.
        if (COMPACT_WORK_BUFFERS) slot.jobs.clear();
        long itemsAt = 0L;
        long maxQuads = 0L;
        int j = 0;
        boolean occl = !reuseCaptureView && !stripFrame && Lod.occlude && this.visBuffers[0] != null && MetalClusterCull.lodCullAvailable() && RenderPipelines.lodOcclusionPipeline() != null;
        if (this.jobExtra == 0L) {
            this.jobExtra = MemoryUtil.nmemAlloc(4096L * 8L);
            this.occlBlock = MemoryUtil.nmemCalloc(1L, MetalClusterCull.OCCL_SIZE);
        }
        long totalClusters = 0L;
        for (GroupItems g : this.groups) {
            totalClusters += g.count;
        }
        boolean opaqueAsync = asyncOpaque && this.activeItems != null && this.fades.isEmpty() && !LodReuse.enabled;
        if (opaqueAsync) this.asyncOpaqueFrames++;
        for (GroupItems g : this.groups) {
            if (g.count == 0) {
                continue;
            }
            boolean ordered = orderedTranslucent && g.layer == 2;
            Job job = this.job(slot, j, g, g.quads);
            job.level = g.level;
            MetalClusterCull.writeJob(this.jobs, j, items, itemsAt, this.sectionTable, 0L, job.outItems, job.quads, job.args, 0L, job.scratch, g.count, (int)g.quads);
            MemoryUtil.memPutInt(this.jobExtra + 8L * j, g.arena.index * VIS_PER_ARENA | (ordered ? 0x80000000 : 0));
            MemoryUtil.memPutInt(this.jobExtra + 8L * j + 4, (int)(itemsAt / 16L));
            // Round 2: compact-record draws carry layer + 8 (ChunkSectionsToRender picks the compact pipeline).
            TerrainClusters.Draw draw = new TerrainClusters.Draw(g.layer + (g.arena.compact ? 8 : 0) + (g.fade && !opaqueAsync ? 16 : 0), g.arena.slice, job.quads.slice(), job.outItems.slice(), job.args.slice(0L, 20L), null);
            draws.add(draw);
            maxQuads = Math.max(maxQuads, g.quads);
            itemsAt += 16L * g.count;
            j++;
        }
        slot.used = j;
        slot.occlUsed = false;
        GpuBuffer indices = this.indexBuffer(occl ? Math.max(maxQuads, 3L * totalClusters) : maxQuads);
        boolean culled;
        if (occl) {
            // The stamps of the last box draw are only trusted if that draw was recorded (a frame that skipped it, e.g. the
            // wireframe override, would otherwise read "tested, nothing visible" for every box): skip a stamp then.
            // Entries of ring buffer b were tested OCCL_LAG occlusion frames ago; they are trusted only if that was exactly
            // OCCL_LAG frames ago and its box draw was recorded (a frame that skipped it, e.g. the wireframe override, would
            // otherwise read "tested, nothing visible" for every box).
            this.occlStamp++;
            int b = this.occlStamp % OCCL_LAG;
            boolean trusted = this.lastOcclFrame[b] == this.frame - OCCL_LAG && this.boxDrawnStamp[b] == this.occlStamp - OCCL_LAG;
            int prevKey = trusted ? this.occlStamp - OCCL_LAG << 1 : 0xFFFFFFFE;
            this.lastOcclFrame[b] = this.frame;
            GpuBuffer visNow = this.visBuffers[b];
            if (slot.occlParams == null) {
                slot.occlParams = RenderSystem.getDevice().createBuffer(() -> "LOD occlusion params",
                    GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_INDIRECT_PARAMETERS | GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_COPY_DST, 64L);
            }
            if (slot.boxes == null || slot.boxCapacity < totalClusters) {
                long capacity = Math.max(16384L, totalClusters + totalClusters / 2L);
                GpuBuffer next = RenderSystem.getDevice().createBuffer(() -> "LOD occlusion boxes", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, 8L * capacity);
                this.retire(slot.boxes);
                slot.boxes = next;
                slot.boxCapacity = capacity;
            }
            long pr = MetalClusterCull.contents(slot.occlParams);
            MemoryUtil.memSet(pr, 0, 64L);
            MemoryUtil.memPutFloat(pr + 32, (float)(camX - this.c0x));
            MemoryUtil.memPutFloat(pr + 36, (float)(camY - this.c0y));
            MemoryUtil.memPutFloat(pr + 40, (float)(camZ - this.c0z));
            MemoryUtil.memPutFloat(pr + 44, OCCL_MARGIN);
            MemoryUtil.memPutInt(pr + 48, this.occlStamp << 1 | 1);
            int cap = (int)Math.min(totalClusters, slot.boxCapacity);
            MetalClusterCull.writeOccl(this.occlBlock, visNow, this.visEntries, slot.boxes, cap, slot.occlParams, 0L, this.occlStamp, OCCL_EYE_MIN, prevKey);
            if (secMode) {
                if (this.secOcc == null) {
                    this.secOcc = RenderSystem.getDevice().createBuffer(() -> "LOD section occlusion", GpuBuffer.USAGE_COPY_DST, 1L << 20);
                    this.secBlock = MemoryUtil.nmemCalloc(1L, MetalClusterCull.SEC_SIZE);
                }
                MetalClusterCull.writeSec(this.secBlock, this.secRing[this.itemRingIndex], this.secCount, this.secOcc, this.secVisBase);
                culled = coverage != null
                    ? MetalClusterCull.cullLodCoverage(items, this.jobs, j, this.view, this.occlBlock, this.jobExtra, this.secBlock, coverage, slot.nearMaskEntries)
                    : MetalClusterCull.cullLodSec(items, this.jobs, j, this.view, this.occlBlock, this.jobExtra, this.secBlock);
            } else {
                culled = coverage != null
                    ? MetalClusterCull.cullLodCoverage(items, this.jobs, j, this.view, this.occlBlock, this.jobExtra, 0L, coverage, slot.nearMaskEntries)
                    : MetalClusterCull.cullLod(items, this.jobs, j, this.view, this.occlBlock, this.jobExtra);
            }
            if (culled) {
                slot.occlUsed = true;
                GpuBuffer boxes = slot.boxes;
                GpuBuffer boxItems = secMode ? this.secRing[this.itemRingIndex] : items;
                GpuBuffer params = slot.occlParams;
                GpuBuffer vis = visNow;
                GpuBuffer table = this.sectionTable;
                GpuBuffer boxIndices = indices;
                int stamp = this.occlStamp;
                this.occlusionDraw = pass -> {
                    RenderPipeline p = RenderPipelines.lodOcclusionPipeline();
                    if (p == null || OCCL_DIAG_NOBOX) {
                        return;
                    }
                    pass.setPipeline(RenderSystem.getCompiledPipeline(p));
                    pass.setVertexBuffer(0, boxes.slice());
                    pass.setVertexBuffer(1, boxItems.slice());
                    pass.setVertexBuffer(com.mojang.renderpearl.backend.metal.TerrainVertexMsl.CLUSTER_QUADS_SLOT, table.slice());
                    pass.setVertexBuffer(com.mojang.renderpearl.backend.metal.TerrainVertexMsl.CLUSTER_ITEMS_SLOT, params.slice());
                    pass.setUniform("LodOcclusion", vis.slice());
                    pass.setIndexBuffer(boxIndices, com.mojang.renderpearl.api.pipeline.IndexType.INT);
                    pass.drawIndexedIndirect(params.slice(0L, 20L), 1);
                    this.boxDrawnStamp[stamp % OCCL_LAG] = stamp;
                };
            }
        } else {
            culled = coverage != null
                ? MetalClusterCull.cullLodCoverage(items, this.jobs, j, this.view, 0L, 0L, 0L, coverage, slot.nearMaskEntries)
                : MetalClusterCull.cull(items, this.jobs, j, this.view, MetalClusterCull.VIEW_SIZE_LOD);
        }
        if (!culled) {
            if (this.nearCoverage != null) this.nearCoverage.clearExposure();
            this.invalidateReuse();
            this.failed = true;
            Lod.LOGGER.warn("[lod] cull kernel failed; LOD not drawn");
            return null;
        }
        long te = System.nanoTime();
        if (this.nearHandoffActive) this.nearCoverage.captureExposure(false, cacheNearExposure);
        hoExposureNs += System.nanoTime() - te;
        for (int i = 0; i < draws.size(); i++) {
            TerrainClusters.Draw d = draws.get(i);
            TerrainClusters.Draw indexed = new TerrainClusters.Draw(d.layer(), d.vertexBuffer(), d.quads(), d.items(), d.args(), indices);
            draws.set(i, indexed);
        }
        if (stripFrame) {
            // The front list keeps drawing for up to strips + 1 frames; a regrown shared index buffer retires the one it
            // uses after RING + 2. Draw it this frame (still alive) and recapture next frame instead.
            if (!this.reuseCullDraws.isEmpty() && this.reuseCullDraws.get(0).indexBuffer() != indices) {
                this.turnKey = null;
                this.stripNext = -1;
                this.turnStripAborts++;
                return this.reuseCullDraws;
            }
            this.reuse.strip(draws, stripIndex, this.stripTotal);
            return this.reuseCullDraws;
        }
        if (reuseSource != null) {
            this.reuseCullDraws = draws;
            this.reuseCullSlot = slot;
            this.reuseCullKey = reuseSource;
            this.reuseCullGeometry = this.geometryEpoch;
            this.reuseCullMembership = this.builds;
            this.reuseCullVisibility = this.visibilityRevision;
        }
        return draws;
    }

    GpuBufferSlice sectionInfos() {
        if (this.closed) throw new IllegalStateException("LOD GPU is closed");
        return this.sectionInfos.slice();
    }

    // ------------------------------------------------------------------------------------------------------------ level fades

    /**
     * Round 2 (smooth level transitions): a node that enters the drawn set dissolves in over {@code mc.lod.fadeMs} (400 ms) while
     * the nodes it replaces dissolve out with the inverse screen-space dither (terrain.fsh {@code LOD_FADE}), so a level
     * switch never pops. The fade value is the section info's w (see terrain.fsh). 0 disables fades.
     */
    static final long FADE_NANOS = Math.max(0L, Long.getLong("mc.lod.fadeMs", 400L)) * 1_000_000L;

    private static final class Fade {
        final boolean in;
        final long start;

        Fade(final boolean in, final long start) {
            this.in = in;
            this.start = start;
        }

        double progress(final long now) {
            return FADE_NANOS == 0L ? 1.0 : Math.min(1.0, Math.max(0.0, (now - this.start) / (double)FADE_NANOS));
        }
    }

    private final java.util.IdentityHashMap<LodManager.Node, Fade> fades = new java.util.IdentityHashMap<>();
    /** Nodes of the drawn set of the last item build (fading-out nodes excluded). */
    private java.util.Set<LodManager.Node> drawnSet = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private long fadeStarts;

    /** True if a node of the previous drawn set covers part of {@code n}'s area at another level (a level switch: the
     * inverse dissolve of the two keeps the area covered). */
    private boolean replacesDrawn(final LodManager.Node n) {
        for (int up = 1; up <= 2; up++) {
            LodManager.Node p = this.manager.existing(n.level + up, n.nx >> up, n.nz >> up);
            if (p != null && this.drawnSet.contains(p)) return true;
        }
        if (n.level > 0) {
            for (int dz = 0; dz < 2; dz++) {
                for (int dx = 0; dx < 2; dx++) {
                    LodManager.Node c = this.manager.existing(n.level - 1, 2 * n.nx + dx, 2 * n.nz + dz);
                    if (c != null && this.drawnSet.contains(c)) return true;
                }
            }
        }
        return false;
    }

    private void writeVisibility(final LodManager.Node n, final float w) {
        List<GpuSection> gs = this.activeItems == null ? n.gpu : this.activeItems.geometry.get(n);
        this.writeVisibility(gs, w);
    }

    private void writeVisibility(final @Nullable List<GpuSection> gs, final float w) {
        this.visibilityRevision++;
        if (gs == null) {
            return;
        }
        long infos = MetalClusterCull.contents(this.sectionInfos);
        for (GpuSection s : gs) {
            MemoryUtil.memPutFloat(infos + 16L * s.slot + 12, w);
        }
    }

    /** Per frame: advances the fades (section info w), ends finished ones (a finished fade-out leaves the item list). */
    private void updateFades(final long now) {
        if (this.fades.isEmpty()) {
            return;
        }
        var it = this.fades.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            LodManager.Node n = e.getKey();
            Fade f = e.getValue();
            double t = f.progress(now);
            if ((this.activeItems == null ? n.gpu : this.activeItems.geometry.get(n)) == null) {
                it.remove();
                if (!f.in) {
                    this.itemsDirty = true;
                }
                continue;
            }
            if (t >= 1.0) {
                if (this.activeItems != null && !f.in) {
                    // Retained old items remain on the fade pipeline: terminal discard until replaced.
                    this.writeVisibility(n, 3.0F);
                    this.itemsDirty = true;
                    continue;
                }
                it.remove();
                this.writeVisibility(n, 1.0F);
                this.itemsDirty = true;   // out of the fade groups (fade-in) or out of the items (fade-out)
                continue;
            }
            this.writeVisibility(n, f.in ? (float)t : 2.0F + (float)t);
        }
    }

    /** Heap-only worker input. No live Node/GPU buffer/arena is consulted by build(). Source metadata
     * is immutable; an optional volatile reference to exact immutable section records is only a memo.
     */
    private static final class ItemInput {
        final List<LodPreparedItems.Node> nodes;
        final List<LodPreparedItems.Key> keys;
        long elapsed;
        ItemInput(List<LodPreparedItems.Node> nodes, List<LodPreparedItems.Key> keys) {
            this.nodes = nodes; this.keys = keys;
        }
        boolean samePacking(ItemInput other) {
            if (this.nodes.size() != other.nodes.size() || !this.keys.equals(other.keys)) return false;
            for (int i = 0; i < this.nodes.size(); i++) {
                var a = this.nodes.get(i); var b = other.nodes.get(i);
                if (a.sections() != b.sections() || a.fade() != b.fade() || a.level() != b.level()) return false;
            }
            return true;
        }
        LodPreparedItems.Result build() {
            long start = System.nanoTime();
            try { return LodPreparedItems.build(this.nodes, this.keys, DIAG_LEVELS, ASYNC_ITEM_BYTES); }
            finally { this.elapsed = System.nanoTime() - start; }
        }
    }

    /** Owned only by the render thread. A lease is acquired while every captured list is still live. */
    private static final class ItemPlan {
        final long selection, geometryEpoch, chunks;
        final java.util.Set<LodManager.Node> drawn;
        final java.util.IdentityHashMap<LodManager.Node, List<GpuSection>> geometry;
        final LodGeometryLeases.Lease lease;
        final ItemInput input;
        ItemPlan(long selection, long geometryEpoch, long chunks, java.util.Set<LodManager.Node> drawn,
            java.util.IdentityHashMap<LodManager.Node, List<GpuSection>> geometry, LodGeometryLeases.Lease lease, ItemInput input) {
            this.selection = selection; this.geometryEpoch = geometryEpoch; this.chunks = chunks;
            this.drawn = drawn; this.geometry = geometry; this.lease = lease; this.input = input;
        }
    }

    private boolean realChunk(final ClientLevel level, final LodManager.Node node) {
        // Coarser nodes cannot be masked per near section. Preserve their existing policy.
        if (this.nearHandoffActive && node.level == 0
            || level.getChunkSource().getChunk(node.nx, node.nz, ChunkStatus.FULL, false) == null) return false;
        if (!realReady || node.level != 0 || !realForceHold && this.realReady(node)) return true;
        // Loaded but vanilla has not published its meshes yet: keep drawing level 0 (no hole) until it has.
        this.realPending.add(node);
        this.realHeld.add((long)node.nx << 32 | node.nz & 0xFFFFFFFFL);
        return false;
    }

    /** Level-0 handoff ({@code mc.lod.realReady}, default true, abab-switchable): a loaded chunk replaces its level-0 node only
     * once vanilla's sections over the node's surface range are compiled and uploaded. Before, a chunk that had just arrived
     * showed nothing until it compiled - while flying, holes ("unload then load") just inside the render distance. */
    public static volatile boolean realReady = Lod.flag("mc.lod.realReady", true);
    private final java.util.Set<LodManager.Node> realPending = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    /** Round 7: chunk keys of realPending (level 0 still drawn over a loaded chunk). Vanilla's translucent sections there are
     * not drawn ({@code mc.lod.realHoldTranslucent}, default true): both water / ice layers blended showed a lighter, sky-like
     * patch over lakes while a chunk was being handed off. */
    private final it.unimi.dsi.fastutil.longs.LongOpenHashSet realHeld = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    private long heldEnters;
    private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap heldEnterCount = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
    private String heldFlapStats() {
        int keys = this.heldEnterCount.size(), ge3 = 0, max = 0; long maxKey = 0;
        for (var e : this.heldEnterCount.long2IntEntrySet()) { if (e.getIntValue() >= 3) ge3++; if (e.getIntValue() > max) { max = e.getIntValue(); maxKey = e.getLongKey(); } }
        return "{\"enters\": " + this.heldEnters + ", \"chunks\": " + keys + ", \"flap3\": " + ge3 + ", \"max\": " + max + ", \"maxChunk\": \"" + (int)(maxKey >> 32) + "," + (int)maxKey + "\", \"blockChanges\": " + this.manager.blockRecaptures + "}";
    }
    /** Diagnostic only: hold every loaded level-0 chunk (LOD drawn, vanilla translucent hidden) to compare the two looks. */
    public static volatile boolean realForceHold;
    /** Translucent LOD jobs keep their cluster order through the fused cull (mcmtl_lod_cull_fused, Occl.pad1: count, scan, write in
     * input order). Unordered, overlapping ice and water blended in a different order every frame (alternating lighter ice).
     * {@code mc.lod.orderedTranslucent}, default true. */
    public static volatile boolean orderedTranslucent = Lod.flag("mc.lod.orderedTranslucent", true);
    public static volatile boolean realHoldTranslucent = Lod.flag("mc.lod.realHoldTranslucent", true);
    boolean realHeld(final int cx, final int cz) { return realHoldTranslucent && !this.realHeld.isEmpty() && this.realHeld.contains((long)cx << 32 | cz & 0xFFFFFFFFL); }
    private long realPendingRebuilds;
    private int builtDrawLevels = 0xFF;

    private boolean realReady(final LodManager.Node node) {
        float lo = node.yMin, hi = node.yMax;
        if (Float.isNaN(lo) || Float.isNaN(hi)) return true;
        var renderer = net.minecraft.client.Minecraft.getInstance().levelRenderer;
        // Round 7 diagnostic: also wait for the sections down to realDepth blocks below the surface range (default 0: longer
        // holds made the doubled ice worse, see realHeld).
        return renderer.lodRealSectionsReady(node.nx, node.nz, (int)Math.floor(lo) - realDepth, (int)Math.ceil(hi));
    }
    /** Round 7 ({@code mc.lod.realDepth}, blocks, abab-switchable; 0 = round 6). */
    public static volatile int realDepth = Integer.getInteger("mc.lod.realDepth", 0);

    /** Once per frame: a held level-0 node whose chunk is now ready (or gone) needs the item list rebuilt. */
    private boolean realPendingChanged(final ClientLevel level) {
        if (this.realPending.isEmpty() || !realReady) return false;
        for (LodManager.Node n : this.realPending) {
            if (level.getChunkSource().getChunk(n.nx, n.nz, ChunkStatus.FULL, false) == null || this.realReady(n)) {
                this.realPendingRebuilds++;
                return true;
            }
        }
        return false;
    }

    private @Nullable ItemPlan captureItems(final ClientLevel level, final LodManager.Selection sel, final long now) {
        LodGeometryLeases.Lease lease = this.geometryLeases.acquire();
        if (lease == null) return null;
        long begin = System.nanoTime();
        try {
            var drawn = java.util.Collections.<LodManager.Node>newSetFromMap(new java.util.IdentityHashMap<>());
            var captured = new java.util.IdentityHashMap<LodManager.Node, List<GpuSection>>();
            List<LodManager.Node> order = new ArrayList<>(sel.nodes().length + this.fades.size());
            for (int i = 0; i < sel.nodes().length; i++) {
                LodManager.Node n = sel.nodes()[i];
                n.lastSelectedFrame = this.frame;
                if (n.gpu == null || sel.insideRd()[i] && this.realChunk(level, n)) continue;
                drawn.add(n); captured.put(n, n.gpu); order.add(n);
            }
            if (FADE_NANOS > 0) {
                for (LodManager.Node n : this.drawnSet) {
                    if (drawn.contains(n) || n.gpu == null || n.level == 0 && this.realChunk(level, n)) continue;
                    captured.put(n, n.gpu); order.add(n);
                }
                for (var e : this.fades.entrySet()) {
                    LodManager.Node n = e.getKey();
                    // Match buildItems: real-chunk arrival may suppress a NEW departure above,
                    // but cannot cut short an outgoing fade already visible in the old list.
                    // Chunk data presence also does not mean its terrain buffers are ready.
                    if (e.getValue().in || e.getValue().progress(now) >= 1 || captured.containsKey(n) || n.gpu == null) continue;
                    captured.put(n, n.gpu); order.add(n);
                }
            }
            List<LodPreparedItems.Node> sources = new ArrayList<>(order.size());
            for (LodManager.Node n : order)
                sources.add(new LodPreparedItems.Node(captured.get(n), FADE_NANOS > 0, n.level));
            List<LodPreparedItems.Key> keys = new ArrayList<>(this.groups.size());
            for (GroupItems g : this.groups)
                keys.add(new LodPreparedItems.Key(g.layer, g.arena.index, g.fade, g.level));
            return new ItemPlan(sel.version(), this.geometryEpoch, this.chunkRevision, drawn, captured, lease,
                new ItemInput(List.copyOf(sources), List.copyOf(keys)));
        } catch (RuntimeException | Error failure) {
            lease.close(); throw failure;
        } finally { this.asyncCaptureNs += System.nanoTime() - begin; }
    }

    /** Called before this frame's terrain/occlusion commands are encoded. The current pending timeline
     * value conservatively covers all earlier uses; draining still happens after the world stops framing. */
    private void retireItems(final ItemPlan old) {
        if (old != null) ((com.mojang.renderpearl.backend.metal.MetalBuffer)this.sectionInfos)
            .device().timeline().defer(old.lease::close);
    }

    private void cancelItemPreparation() {
        if (this.itemWorker != null) { this.itemWorker.close(); this.itemWorker = null; }
        if (this.pendingItems != null) { this.pendingItems.lease.close(); this.pendingItems = null; }
        // Uninstalled worker results only read heap arrays: cancellation can release their geometry lease immediately.
    }

    private void leaveAsyncItems() {
        this.cancelItemPreparation();
        if (this.activeItems == null) return;
        ItemPlan old = this.activeItems;
        this.activeItems = null;
        // Terminal discard is experimental only; restore the ordinary path's current fade values.
        long now = System.nanoTime();
        this.fades.entrySet().removeIf(e -> e.getValue().progress(now) >= 1);
        for (LodManager.Node n : old.geometry.keySet()) {
            Fade f = this.fades.get(n);
            this.writeVisibility(n.gpu, f == null ? 1.0F : f.in ? (float)f.progress(now) : 2.0F + (float)f.progress(now));
        }
        this.itemsDirty = true;
        this.retireItems(old);
        this.invalidateReuse();
    }

    private void failAsyncItems(String reason) {
        this.asyncItemFailure = true;
        this.asyncItemError = reason;
        Lod.LOGGER.warn("[lod] async item preparation disabled for this ON interval: {}", reason);
        this.leaveAsyncItems();
    }

    /** True keeps a complete installed draw list active while the single CPU job runs. Never waits for it. */
    private boolean prepareItemsAsync(final ClientLevel level, final LodManager.Selection sel, final long now) {
        if (!asyncItems || LodAsyncProducerProbe.ENABLED) {
            this.leaveAsyncItems();
            this.asyncItemFailure = false; this.asyncRejectStreak = 0;
            return false;
        }
        if (this.asyncItemFailure) return false;
        if (this.pendingItems != null) {
            var complete = this.itemWorker.poll();
            if (complete == null) return true;
            ItemPlan plan = this.pendingItems;
            this.pendingItems = null;
            this.asyncWorkerNs += plan.input.elapsed;
            if (complete.request() != plan.input || complete.failure() != null || complete.result() == null) {
                plan.lease.close();
                this.failAsyncItems(complete.failure() == null ? "packing capacity or request mismatch" : complete.failure().getClass().getSimpleName());
                return false;
            }
            this.noteItemRecordPacking(complete.result());
            boolean stale = plan.selection != sel.version() || plan.geometryEpoch != this.geometryEpoch || plan.chunks != this.chunkRevision;
            if (stale && asyncExactRevalidate) {
                this.asyncRechecks++;
                ItemPlan current;
                try { current = this.captureItems(level, sel, now); }
                catch (RuntimeException | Error failure) { plan.lease.close(); throw failure; }
                if (current != null) {
                    if (plan.input.samePacking(current.input)) {
                        // Current ownership, draw/fade sets and revisions; only the exact owned words are reused.
                        plan.lease.close(); plan = current;
                        this.asyncEquivalent++; stale = false;
                    } else current.lease.close();
                }
            }
            if (stale) {
                plan.lease.close(); this.asyncRejected++; this.asyncRejectStreak++; this.itemsDirty = true;
            } else {
                if (!this.installItems(plan, complete.result(), now)) {
                    plan.lease.close(); this.itemsDirty = true; return true;
                }
                this.asyncAccepted++; this.asyncRejectStreak = 0;
                this.asyncAcceptedClusters += complete.result().clusterCount;
                return true;
            }
        }
        if (this.activeItems != null && sel.version() == this.builtSelection && !this.itemsDirty
            && this.activeItems.chunks == this.chunkRevision) return true;
        ItemPlan next = this.captureItems(level, sel, now);
        if (next == null) return this.activeItems != null;
        if (this.activeItems == null || this.asyncRejectStreak >= 2) {
            // One synchronous bootstrap, or a bounded starvation fallback. Both are explicitly counted.
            LodPreparedItems.Result result;
            try { result = next.input.build(); }
            catch (RuntimeException | Error failure) { next.lease.close(); throw failure; }
            if (result == null) { next.lease.close(); this.failAsyncItems("packing capacity"); return false; }
            this.noteItemRecordPacking(result);
            if (!this.installItems(next, result, now)) { next.lease.close(); return this.activeItems != null; }
            if (this.asyncRejectStreak >= 2) this.asyncFallbacks++; else this.asyncBootstrap++;
            this.asyncRejectStreak = 0;
            return true;
        }
        if (this.itemWorker == null) this.itemWorker = new LodItemWorker<>("lod-item-prepare", ItemInput::build);
        if (!this.itemWorker.offer(next.input)) { next.lease.close(); return true; }
        this.pendingItems = next; this.asyncOffers++;
        return true;
    }

    private void noteItemRecordPacking(final LodPreparedItems.Result result) {
        this.asyncRecordSections += result.cachedSectionCount;
        this.asyncRecordClusters += result.cachedClusterCount;
        this.asyncScalarSections += result.sectionCount - result.cachedSectionCount;
    }

    /** Stage all CPU groups and both GPU input buffers before replacing the active list. */
    private boolean installItems(final ItemPlan plan, final LodPreparedItems.Result result, final long now) {
        long begin = System.nanoTime();
        int r = -1;
        for (int offset = 1; offset <= this.itemRing.length; offset++) {
            int candidate = (this.itemRingIndex + offset) % this.itemRing.length;
            if (candidate != this.itemRingIndex
                && (this.itemRing[candidate] == null || this.frame - this.itemRingLastUse[candidate] >= RING)) {
                r = candidate; break;
            }
        }
        if (r < 0) return false;
        List<GroupItems> nextGroups = new ArrayList<>(result.groups.size());
        long itemBytes = 0;
        for (LodPreparedItems.Group p : result.groups) {
            var key = p.key();
            GroupItems g = new GroupItems(key.layer(), this.arenas.get(key.arena()), key.fade(), key.level(),
                p.items().length == 0 ? new int[4] : p.items());
            g.count = p.items().length / 4; g.quads = p.quads(); nextGroups.add(g);
            itemBytes += 4L * p.items().length;
        }
        long sectionBytes = Math.max(16L, 4L * result.sectionItems.length);
        GpuBuffer newSections = this.secRing[r], newItems = this.itemRing[r];
        boolean ownSections = false, ownItems = false;
        try {
            if (newSections == null || this.secRingBytes[r] < sectionBytes) {
                newSections = RenderSystem.getDevice().createBuffer(() -> "LOD prepared sections",
                    GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_COPY_DST, Math.max(1L << 16, sectionBytes + sectionBytes / 2));
                ownSections = true;
            }
            if (newItems == null || this.itemRingBytes[r] < itemBytes) {
                newItems = RenderSystem.getDevice().createBuffer(() -> "LOD prepared cluster items",
                    GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_COPY_DST, Math.max(1L << 20, itemBytes + itemBytes / 2));
                ownItems = true;
            }
            MemoryUtil.memIntBuffer(MetalClusterCull.contents(newSections), result.sectionItems.length).put(result.sectionItems);
            long at = MetalClusterCull.contents(newItems);
            for (GroupItems g : nextGroups) if (g.count > 0) {
                MemoryUtil.memIntBuffer(at, 4 * g.count).put(g.items, 0, 4 * g.count);
                at += 16L * g.count;
            }
        } catch (RuntimeException | Error failure) {
            if (ownSections) newSections.close();
            if (ownItems) newItems.close();
            plan.lease.close();
            throw failure;
        }
        if (ownSections) { this.retire(this.secRing[r]); this.secRing[r] = newSections; this.secRingBytes[r] = newSections.size(); }
        if (ownItems) { this.retire(this.itemRing[r]); this.itemRing[r] = newItems; this.itemRingBytes[r] = newItems.size(); }
        ItemPlan old = this.activeItems;
        var nextFades = new java.util.IdentityHashMap<LodManager.Node, Fade>();
        if (FADE_NANOS > 0) for (LodManager.Node n : plan.geometry.keySet()) {
            boolean in = plan.drawn.contains(n);
            Fade previous = this.fades.get(n);
            Fade next;
            if (in == this.drawnSet.contains(n)) next = previous;
            else {
                long start = previous != null && previous.in != in
                    ? now - (long)((1.0 - previous.progress(now)) * FADE_NANOS) : now;
                next = new Fade(in, start);
                if (in) this.fadeStarts++;
            }
            if (next != null && (!next.in || next.progress(now) < 1)) nextFades.put(n, next);
        }
        this.releaseGroupLookups();
        this.groups.clear(); this.groups.addAll(nextGroups);
        this.activeItems = plan;
        this.itemRingIndex = r; this.secItems = result.sectionItems; this.secCount = result.sectionItems.length / 4;
        this.drawnSections = result.sectionCount; this.drawnClusters = result.clusterCount; this.drawnQuads = result.quadCount;
        System.arraycopy(result.quadsByLevel, 0, this.drawnQuadsByLevel, 0, 8);
        System.arraycopy(result.quadsByFacing, 0, this.drawnQuadsBySlot, 0, 8);
        var oldFades = new java.util.IdentityHashMap<>(this.fades);
        this.drawnSet = plan.drawn; this.fades.clear(); this.fades.putAll(nextFades);
        this.builtSelection = plan.selection;
        this.itemsDirty = false;
        long millis = net.minecraft.util.Util.getMillis();
        for (LodManager.Node n : plan.drawn) n.lastDrawnMillis = millis;
        // Every experimental nonempty group has the fade pipeline; w=1 is the unchanged opaque case.
        for (var e : plan.geometry.entrySet()) {
            Fade f = this.fades.get(e.getKey());
            if (old != null && old.geometry.get(e.getKey()) == e.getValue() && f == null && !oldFades.containsKey(e.getKey())) continue;
            this.writeVisibility(e.getValue(), f == null ? 1.0F : f.in ? (float)f.progress(now) : 2.0F + (float)f.progress(now));
        }
        // The scalar OFF path grows arrays by doubling; preserve its positive-capacity invariant.
        if (this.secItems.length == 0) this.secItems = new int[4];
        this.invalidateReuse();
        this.builds++;
        this.asyncInstallNs += System.nanoTime() - begin;
        this.retireItems(old);
        return true;
    }

    private void buildItems(final ClientLevel level, final LodManager.Selection sel, final long now, final boolean cachedItems) {
        List<LodManager.Node> order = this.selectItems(level, sel, now, cachedItems);
        if (ITEM_BUILD_PROFILE) this.itemBuildProfile.advance(LodItemBuildProfile.PREPARE_APPEND, System.nanoTime());
        var appended = new java.util.IdentityHashMap<LodManager.Node, List<GpuSection>>();
        this.appendTarget = appended;
        this.installedNear = null;
        try {
            this.appendNodes(order, 0, order.size(), cachedItems, Long.MAX_VALUE);
        } finally {
            this.appendTarget = null;
        }
        this.installedNear = appended;
        this.writeItemRing();
    }

    /** Exchanges the drawn (front) and building (back) group sets, section records and drawn counters. */
    private void swapBack() {
        List<GroupItems> g = new ArrayList<>(this.groups);
        this.groups.clear(); this.groups.addAll(this.backGroups); this.backGroups = g;
        for (Arena a : this.arenas) { GroupItems[] t = a.groupLookup; a.groupLookup = a.backGroupLookup; a.backGroupLookup = t; }
        int[] si = this.secItems; this.secItems = this.backSecItems; this.backSecItems = si;
        int i = this.secCount; this.secCount = this.backSecCount; this.backSecCount = i;
        i = this.drawnSections; this.drawnSections = this.backDrawnSections; this.backDrawnSections = i;
        i = this.drawnClusters; this.drawnClusters = this.backDrawnClusters; this.backDrawnClusters = i;
        long q = this.drawnQuads; this.drawnQuads = this.backDrawnQuads; this.backDrawnQuads = q;
        long[] a = this.drawnQuadsByLevel; this.drawnQuadsByLevel = this.backByLevel; this.backByLevel = a;
        a = this.drawnQuadsBySlot; this.drawnQuadsBySlot = this.backBySlot; this.backBySlot = a;
    }

    /** Sliced rebuild start: the selection runs now (into the back set); appends spread over the next frames. */
    private void sliceBegin(final ClientLevel level, final LodManager.Selection sel, final long now) {
        long previousSelection = this.builtSelection;
        this.swapBack();
        try {
            this.sliceOrder = this.selectItems(level, sel, now, true);
        } finally {
            this.swapBack();
        }
        this.sliceSelection = this.builtSelection;
        this.builtSelection = previousSelection;   // the drawn list is still the old one until the swap
        this.sliceAt = 0;
        this.sliceNear = new java.util.IdentityHashMap<>();
        this.sliceStartFrame = this.frame;
        this.sliceStartNanos = now;
        this.sliceCached = true;
        this.sliceBuilds++;
        if (!sliceDeferFirst) this.sliceStep(now);   // else the selection frame does no appends
    }

    private void sliceStep(final long now) {
        List<LodManager.Node> order = this.sliceOrder;
        long b0 = System.nanoTime();
        this.swapBack();
        boolean done = false;
        this.appendTarget = this.sliceNear;
        try {
            this.sliceAt = this.appendNodes(order, this.sliceAt, order.size(), this.sliceCached, b0 + sliceUs * 1000L);
            done = this.sliceAt >= order.size();
            if (done) this.writeItemRing();
        } catch (RuntimeException | Error error) {
            this.appendTarget = null;
            this.installedNear = null;
            this.swapBack();
            this.sliceOrder = null;
            this.releaseAllNodeItems();
            this.itemsDirty = true;
            this.invalidateReuse();
            throw error;
        }
        this.appendTarget = null;
        if (done) {
            // The built set stays in front; the old one becomes the next back set.
            this.installedNear = this.sliceNear;
            this.sliceNear = null;
            this.sliceOrder = null;
            this.builtSelection = this.sliceSelection;
            this.lastItemBuildFrame = this.frame;
            this.lastItemBuildStartFrame = this.sliceStartFrame;
            this.builds++;
            this.itemModeBuilds[1]++;
        } else {
            this.swapBack();
        }
        long elapsed = System.nanoTime() - b0;
        this.buildNanos += elapsed;
        if (done) this.itemModeBuildNanos[1] += elapsed;
        this.sliceFrames++;
        this.sliceMaxNs = Math.max(this.sliceMaxNs, elapsed);
    }

    private List<LodManager.Node> selectItems(final ClientLevel level, final LodManager.Selection sel, final long now, final boolean cachedItems) {
        this.buildCacheGroups = cacheGroups;
        this.buildCacheNodeItems = cacheNodeItems && cachedItems;
        if (ITEM_BUILD_PROFILE) this.itemBuildProfile.groups(this.buildCacheGroups);
        this.builtSelection = sel.version();
        this.itemsDirty = false;
        this.realPending.clear();
        it.unimi.dsi.fastutil.longs.LongOpenHashSet prevHeld = this.realHeld.clone();
        this.realHeld.clear();
        for (GroupItems g : this.groups) {
            g.count = 0;
            g.quads = 0L;
        }
        this.drawnSections = 0;
        this.drawnQuads = 0L;
        this.drawnClusters = 0;
        Arrays.fill(this.drawnQuadsByLevel, 0L);
        Arrays.fill(this.drawnQuadsBySlot, 0L);
        LodManager.Node[] nodes = sel.nodes();
        boolean[] inside = sel.insideRd();
        long nowMillis = net.minecraft.util.Util.getMillis();
        java.util.Set<LodManager.Node> drawn = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        int holes = 0;
        List<LodManager.Node> order = new ArrayList<>(nodes.length + this.fades.size());
        for (int i = 0; i < nodes.length; i++) {
            LodManager.Node n = nodes[i];
            n.lastSelectedFrame = this.frame;
            if (n.gpu == null) {
                if (!(inside[i] && level.getChunkSource().getChunk(n.nx << n.level, n.nz << n.level, ChunkStatus.FULL, false) != null)) holes++;
                continue;
            }
            if (inside[i] && this.realChunk(level, n)) {
                continue;   // vanilla draws this chunk
            }
            if ((Lod.drawLevels >> n.level & 1) == 0) continue;
            drawn.add(n);
            order.add(n);
            n.lastDrawnMillis = nowMillis;
        }
        if (FADE_NANOS > 0L) {
            for (LodManager.Node n : drawn) {
                if (!this.drawnSet.contains(n)) {
                    Fade old = this.fades.get(n);
                    if (old == null && !this.replacesDrawn(n)) {
                        // Nothing drawn under it (first mesh, or a real chunk that just unloaded): a dissolve would show the
                        // void through its dither for the fade's length. Appear at once.
                        this.writeVisibility(n, 1.0F);
                        continue;
                    }
                    // Re-entering while fading out: continue from the pixels it still shows.
                    long start = old != null && !old.in ? now - (long)((1.0 - old.progress(now)) * FADE_NANOS) : now;
                    this.fades.put(n, new Fade(true, start));
                    this.writeVisibility(n, (float)this.fades.get(n).progress(now));
                    this.fadeStarts++;
                }
            }
            for (LodManager.Node n : this.drawnSet) {
                if (drawn.contains(n) || n.gpu == null) {
                    continue;
                }
                if (n.level == 0 && this.realChunk(level, n)) {
                    this.fades.remove(n);   // the real chunk takes over (vanilla's own section fade)
                    continue;
                }
                Fade old = this.fades.get(n);
                long start = old != null && old.in ? now - (long)((1.0 - old.progress(now)) * FADE_NANOS) : now;
                this.fades.put(n, new Fade(false, start));
                this.writeVisibility(n, 2.0F + (float)this.fades.get(n).progress(now));
            }
            for (var e : this.fades.entrySet()) {
                if (!e.getValue().in && e.getKey().gpu != null && !drawn.contains(e.getKey())) {
                    order.add(e.getKey());
                }
            }
        }
        if (EVENT_LOG) this.logDrawnDiff(this.drawnSet, drawn);
        this.drawnSet = drawn;
        // Diagnostics (round 7): chunks entering the level-0 hold again and again (handoff flapping).
        for (long k : this.realHeld) if (!prevHeld.contains(k)) { this.heldEnters++; this.heldEnterCount.addTo(k, 1); }
        // Diagnostics: selected nodes with no mesh yet and no real chunk over them (drawn as nothing: a hole until meshed).
        this.holeNodes += holes;
        if (holes > 0) this.holeBuilds++;
        this.secCount = 0;
        if (ITEM_BUILD_PROFILE) this.itemBuildProfile.selected(nodes.length, order.size());
        return order;
    }

    /** Appends order[from, to) and returns the next index; stops early (checked every 32 nodes) past deadlineNanos. */
    private int appendNodes(final List<LodManager.Node> order, final int from, final int to, final boolean cachedItems, final long deadlineNanos) {
        for (int i = from; i < to; i++) {
            if ((i - from & 31) == 31 && System.nanoTime() > deadlineNanos) return i;
            LodManager.Node n = order.get(i);
            List<GpuSection> gs = n.gpu;
            if (gs == null) continue;   // released since a sliced selection
            if (n.level == 0 && this.appendTarget != null) this.appendTarget.put(n, gs);
            boolean fading = this.fades.containsKey(n);
            if (this.buildCacheNodeItems && this.appendCachedNode(gs, fading, n.level)) continue;
            for (GpuSection s : gs) {
                this.drawnSections++;
                if (cachedItems) {
                    this.appendCachedSection(s, fading, n.level);
                    continue;
                }
                this.addSectionItem(s);
                for (int l = 0; l < 3; l++) {
                    int[] clusters = s.clusters[l];
                    if (clusters == null || s.arena[l] < 0) {
                        continue;
                    }
                    GroupItems g = this.group(l, this.arenas.get(s.arena[l]), fading, DIAG_LEVELS ? n.level : -1);
                    int base = 4 * s.firstQuad[l];
                    for (int c = 0; c < clusters.length; c += TerrainClusters.CLUSTER_INTS) {
                        int c0 = clusters[c];
                        if (this.builtPlantsL0Off && n.level == 0 && l != 2 && (c0 >>> 22 & 7) == TerrainMeshInfo.SLOT_UNASSIGNED) {
                            this.droppedPlantQuads += (c0 >>> 16 & 63) + 1;
                            continue;
                        }
                        int at = 4 * g.count;
                        if (at + 4 > g.items.length) {
                            g.items = Arrays.copyOf(g.items, g.items.length * 2);
                        }
                        int q = (c0 >>> 16 & 63) + 1;
                        g.items[at] = base + 4 * (c0 & 0xFFFF);
                        g.items[at + 1] = s.slot | (q - 1) << 20 | (c0 >>> 22 & 7) << 26 | (c0 >>> 25 & 1) << 29;
                        g.items[at + 2] = clusters[c + 1];
                        g.items[at + 3] = clusters[c + 2];
                        g.count++;
                        g.quads += q;
                        this.drawnClusters++;
                        this.drawnQuads += q;
                        this.drawnQuadsByLevel[Math.min(7, n.level)] += q;
                        this.drawnQuadsBySlot[c0 >>> 22 & 7] += q;
                    }
                }
            }
        }
        return to;
    }

    private void writeItemRing() {
        long bytes = 0L;
        int profileGroups = 0;
        for (GroupItems g : this.groups) {
            bytes += 16L * g.count;
            if (ITEM_BUILD_PROFILE && g.count > 0) profileGroups++;
        }
        if (ITEM_BUILD_PROFILE) {
            this.itemBuildProfile.prepared(this.drawnSections, this.secCount, this.drawnClusters, this.drawnQuads, profileGroups);
            this.itemBuildProfile.advance(LodItemBuildProfile.SECTION_RING, System.nanoTime());
        }
        this.itemRingIndex = (this.itemRingIndex + 1) % this.itemRing.length;
        int r = this.itemRingIndex;
        long sbytes = 16L * Math.max(1, this.secCount);
        if (this.secRing[r] == null || this.secRingBytes[r] < sbytes) {
            long size = Math.max(1L << 16, sbytes + sbytes / 2);
            GpuBuffer next = RenderSystem.getDevice().createBuffer(() -> "LOD section items", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_COPY_DST, size);
            if (ITEM_BUILD_PROFILE) this.itemBuildProfile.allocated(false, size);
            this.retire(this.secRing[r]);
            this.secRing[r] = next;
            this.secRingBytes[r] = size;
        }
        MemoryUtil.memIntBuffer(MetalClusterCull.contents(this.secRing[r]), 4 * this.secCount).put(this.secItems, 0, 4 * this.secCount);
        if (ITEM_BUILD_PROFILE) this.itemBuildProfile.copied(false, 16L * this.secCount);
        if (bytes == 0L) {
            return;
        }
        if (ITEM_BUILD_PROFILE) this.itemBuildProfile.advance(LodItemBuildProfile.ITEM_RING, System.nanoTime());
        if (this.itemRing[r] == null || this.itemRingBytes[r] < bytes) {
            long size = Math.max(1L << 20, bytes + bytes / 2);
            GpuBuffer next = RenderSystem.getDevice().createBuffer(() -> "LOD cluster items", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_COPY_DST, size);
            if (ITEM_BUILD_PROFILE) this.itemBuildProfile.allocated(true, size);
            this.retire(this.itemRing[r]);
            this.itemRing[r] = next;
            this.itemRingBytes[r] = size;
        }
        long at = MetalClusterCull.contents(this.itemRing[r]);
        for (GroupItems g : this.groups) {
            if (g.count > 0) {
                MemoryUtil.memIntBuffer(at, 4 * g.count).put(g.items, 0, 4 * g.count);
                if (ITEM_BUILD_PROFILE) this.itemBuildProfile.copied(true, 16L * g.count);
                at += 16L * g.count;
            }
        }
    }

    /** Render-thread-only creation/accounting. Publication is volatile; the complete object is immutable. */
    private LodSectionItems sectionItems(final GpuSection s) {
        LodSectionItems prepared = s.preparedItems;
        if (prepared == null) {
            prepared = new LodSectionItems(s.slot, s.arena, s.firstQuad, s.clusters);
            s.preparedItems = prepared;
            this.itemCacheBuilds++;
            this.itemCachePayloadBytes += prepared.payloadBytes;
        } else {
            this.itemCacheHits++;
        }
        return prepared;
    }

    /** Copies the same records in the same node/section/layer order as the scalar loop. */
    private void appendCachedSection(final GpuSection s, final boolean fading, final int level) {
        LodSectionItems prepared = this.sectionItems(s);
        if (prepared.hasSection()) {
            int at = 4 * this.secCount;
            if (at + 4 > this.secItems.length) this.secItems = Arrays.copyOf(this.secItems, this.secItems.length * 2);
            prepared.copySection(this.secItems, at);
            this.secCount++;
        }
        for (int l = 0; l < 3; l++) {
            if (!prepared.hasLayer(l)) continue;
            GroupItems g = this.group(l, this.arenas.get(s.arena[l]), fading, DIAG_LEVELS ? level : -1);
            int count = prepared.clusterCount(l), at = 4 * g.count;
            int needed = Math.addExact(at, Math.multiplyExact(count, 4));
            if (needed > g.items.length) g.items = Arrays.copyOf(g.items, Math.max(needed, g.items.length * 2));
            prepared.copyLayer(l, g.items, at);
            long quads = prepared.quadCount(l);
            g.count += count;
            g.quads += quads;
            this.drawnClusters += count;
            this.drawnQuads += quads;
            this.drawnQuadsByLevel[Math.min(7, level)] += quads;
        }
        for (int direction = 0; direction < 8; direction++)
            this.drawnQuadsBySlot[direction] += prepared.facingQuads(direction);
    }

    private void releaseSectionItems(final GpuSection s) {
        if (s.preparedItems != null) {
            this.itemCachePayloadBytes -= s.preparedItems.payloadBytes;
            s.preparedItems = null;
        }
    }

    /** Uploaded-list identity is a generation: only uploadPending assigns a fresh completed list and free nulls it.
     * It and the referenced slot/range/cluster metadata are never mutated after publication (preparedItems excepted).
     * Arena objects likewise remain at their indices until this owner closes. Packets contain primitive copies only.
     */
    private boolean appendCachedNode(final List<GpuSection> sections, final boolean fading, final int level) {
        if (this.closed) return false;
        LodNodeItems.Cache cache = this.nodeItemCache;
        LodNodeItems packet = cache == null ? null : cache.get(sections);
        boolean hit = packet != null;
        if (packet == null) {
            this.nodeItemMisses++;
            if (cache == null && NODE_ITEM_CACHE_BYTES > 0) this.nodeItemCache = cache = new LodNodeItems.Cache(NODE_ITEM_CACHE_BYTES, NODE_ITEM_CACHE_ENTRIES);
            if (cache == null || !cache.canAdd()) { this.nodeItemCapFallbacks++; return false; }
            if (LodNodeItems.chargeFor(sections, this.arenas.size()) > cache.remainingBytes()) {
                this.nodeItemCapFallbacks++; return false;
            }
            // Miss-only preparation; normal replay neither visits section metadata nor rereads mode flags.
            for (GpuSection s : sections) {
                if (s.preparedItems == null) {
                    s.preparedItems = new LodSectionItems(s.slot, s.arena, s.firstQuad, s.clusters);
                    this.itemCacheBuilds++;
                    this.itemCachePayloadBytes += s.preparedItems.payloadBytes;
                } else this.itemCacheHits++;
            }
            packet = LodNodeItems.build(sections, this.arenas.size(), cache.remainingBytes());
            if (packet == null || !cache.put(sections, packet)) { this.nodeItemCapFallbacks++; return false; }
            this.nodeItemPacketBuilds++;
        }
        int sectionAt = Math.multiplyExact(this.secCount, 4);
        int sectionWords = Math.multiplyExact(packet.sectionRecords(), 4);
        int sectionNeeded = Math.addExact(sectionAt, sectionWords);
        if (sectionNeeded > this.secItems.length) this.secItems = Arrays.copyOf(this.secItems, Math.max(sectionNeeded, this.secItems.length * 2));
        packet.copySections(this.secItems, sectionAt);
        this.secCount += packet.sectionRecords();
        // Calling group() in first-occurrence order preserves stable creation ties, including zero-count groups.
        for (int b = 0; b < packet.batches(); b++) {
            GroupItems g = this.group(packet.layer(b), this.arenas.get(packet.arena(b)), fading, DIAG_LEVELS ? level : -1);
            int count = packet.clusters(b), at = Math.multiplyExact(g.count, 4);
            int needed = Math.addExact(at, Math.multiplyExact(count, 4));
            if (needed > g.items.length) g.items = Arrays.copyOf(g.items, Math.max(needed, g.items.length * 2));
            packet.copyBatch(b, g.items, at);
            g.count += count; g.quads += packet.quads(b);
        }
        this.drawnSections += packet.sectionCount;
        this.drawnClusters += packet.clusterCount;
        this.drawnQuads += packet.quadCount;
        this.drawnQuadsByLevel[Math.min(7, level)] += packet.quadCount;
        for (int d = 0; d < 8; d++) this.drawnQuadsBySlot[d] += packet.facing(d);
        this.nodeItemReplayedSections += packet.sectionCount;
        this.nodeItemReplayedClusters += packet.clusterCount;
        if (hit) this.nodeItemHits++;
        return true;
    }

    private void releaseNodeItems(final @Nullable List<GpuSection> sections) {
        if (this.nodeItemCache != null && sections != null) this.nodeItemCache.remove(sections);
    }

    private void releaseAllNodeItems() {
        if (this.nodeItemCache != null) { this.nodeItemCache.clear(); this.nodeItemCache = null; }
    }

    /** Stage 3 r6: the section's item for the section-level occlusion: slot, union of its clusters' bounds (all layers). */
    private void addSectionItem(final GpuSection s) {
        int lx = 1023, ly = 1023, lz = 1023, hx = 0, hy = 0, hz = 0;
        boolean unusable = false;
        boolean any = false;
        for (int l = 0; l < 3; l++) {
            int[] clusters = s.clusters[l];
            if (clusters == null || s.arena[l] < 0) {
                continue;
            }
            for (int c = 0; c < clusters.length; c += TerrainClusters.CLUSTER_INTS) {
                any = true;
                unusable |= (clusters[c] >>> 25 & 1) != 0;
                int lo = clusters[c + 1];
                int hi = clusters[c + 2];
                lx = Math.min(lx, lo & 1023);
                ly = Math.min(ly, lo >>> 10 & 1023);
                lz = Math.min(lz, lo >>> 20 & 1023);
                hx = Math.max(hx, hi & 1023);
                hy = Math.max(hy, hi >>> 10 & 1023);
                hz = Math.max(hz, hi >>> 20 & 1023);
            }
        }
        if (!any) {
            return;
        }
        int at = 4 * this.secCount;
        if (at + 4 > this.secItems.length) {
            this.secItems = Arrays.copyOf(this.secItems, this.secItems.length * 2);
        }
        this.secItems[at] = 0;
        this.secItems[at + 1] = s.slot | (unusable ? 1 << 29 : 0);
        this.secItems[at + 2] = lx | ly << 10 | lz << 20;
        this.secItems[at + 3] = hx | hy << 10 | hz << 20;
        this.secCount++;
    }

    private GroupItems group(final int layer, final Arena arena, final boolean fade, final int level) {
        if (GROUP_LOOKUP_DIAGNOSTICS) this.groupLookupCalls[this.buildCacheGroups ? 1 : 0]++;
        int key = this.buildCacheGroups && layer >= 0 && layer < 3 && level >= -1 && level <= 7
            ? (layer * 2 + (fade ? 1 : 0)) * 9 + level + 1 : -1;
        if (key >= 0 && arena.groupLookup != null) {
            GroupItems hit = arena.groupLookup[key];
            if (hit != null) {
                if (GROUP_LOOKUP_DIAGNOSTICS) this.groupLookupHits++;
                return hit;
            }
        }
        // A miss MUST scan first: OFF may already have created this exact group. Invalid keys keep the old path too.
        GroupItems group = this.linearGroup(layer, arena, fade, level);
        if (key >= 0) {
            if (arena.groupLookup == null) {
                arena.groupLookup = new GroupItems[GROUP_LOOKUP_SLOTS];
                this.groupLookupTables++;
            }
            arena.groupLookup[key] = group;
        }
        return group;
    }

    private GroupItems linearGroup(final int layer, final Arena arena, final boolean fade, final int level) {
        for (GroupItems g : this.groups) {
            if (GROUP_LOOKUP_DIAGNOSTICS) this.groupLookupProbes[this.buildCacheGroups ? 1 : 0]++;
            if (g.layer == layer && g.arena == arena && g.fade == fade && g.level == level) {
                return g;
            }
        }
        GroupItems g = new GroupItems(layer, arena, fade, level);
        this.groups.add(g);
        // Keep layer order stable (SOLID, CUTOUT, TRANSLUCENT; arenas in order) for the draw.
        this.groups.sort((a, b) -> a.layer != b.layer ? Integer.compare(a.layer, b.layer) : Integer.compare(a.arena.index, b.arena.index));
        return g;
    }

    /** Cluster scratch: counts and offsets (8 B per cluster), plus the ordered translucent scan's per-256-cluster totals and offsets. */
    private static long scratchBytes(final long clusters) { return 8L * clusters + 8L * ((clusters + 255L) / 256L); }

    private Job job(final Slot slot, final int index, final GroupItems group, final long quads) {
        int clusters = group.count;
        Job job;
        if (COMPACT_WORK_BUFFERS) {
            var key = new LodGpuBufferCapacity.Key(group.arena.index, group.layer, group.fade, group.level, 0);
            job = slot.compactJobs.get(key, Job::new);
            slot.jobs.add(job); // Same group traversal and native job ordinal as before.
        } else {
            while (slot.jobs.size() <= index) slot.jobs.add(new Job());
            job = slot.jobs.get(index);
        }
        job.observe(this.frame, quads, job.quadCapacity, VISIBLE_QUAD_BYTES, RING);
        job.quadCount = Math.toIntExact(quads);
        if (job.outItems == null || job.clusterCapacity < clusters) {
            long capacity = Math.max(4096L, clusters + clusters / 2L);
            GpuBuffer nextItems = this.jobBuffer("LOD cluster out items",
                GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, 8L * capacity);
            GpuBuffer nextScratch;
            try {
                nextScratch = this.jobBuffer("LOD cluster scratch", GpuBuffer.USAGE_COPY_DST, scratchBytes(capacity));
            } catch (RuntimeException | Error error) {
                this.closeWorkBuffer(nextItems);
                throw error;
            }
            // Publish both replacements together: a failed allocation must not leave
            // a retired buffer still owned by a job and closed a second time at teardown.
            this.retire(job.outItems);
            this.retire(job.scratch);
            job.clusterCapacity = capacity;
            job.outItems = nextItems;
            job.scratch = nextScratch;
        } else if (job.scratch == null) {
            job.scratch = this.jobBuffer("LOD cluster scratch", GpuBuffer.USAGE_COPY_DST, scratchBytes(job.clusterCapacity));
        }
        boolean grow = job.quads == null || job.quadCapacity < quads;
        long target = COMPACT_WORK_BUFFERS ? LodGpuBufferCapacity.capacity(quads) : Math.max(65536L, quads + quads / 2L);
        boolean shrink = !grow && COMPACT_WORK_BUFFERS && job.shrinkReady(this.frame)
            && this.bufferMaintenance.shrink(this.frame, VISIBLE_QUAD_BYTES * job.quadCapacity,
                VISIBLE_QUAD_BYTES * target, this.retiredQuadBytes);
        if (grow || shrink) {
            try {
                // Allocate before retiring the old buffer: an optional OOM leaves the valid draw intact.
                GpuBuffer next = this.jobBuffer("LOD cluster quads",
                    GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, VISIBLE_QUAD_BYTES * target);
                this.retireQuads(job.quads, job.quadCapacity);
                job.quads = next;
                job.quadCapacity = target;
                job.resized();
            } catch (GpuOutOfMemoryException error) {
                if (grow) throw error; // Required bounds must never be clamped to an older allocation.
            }
        }
        if (job.args == null) {
            job.args = this.jobBuffer("LOD cluster args", GpuBuffer.USAGE_INDIRECT_PARAMETERS | GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, 256L);
        }
        return job;
    }

    private GpuBuffer jobBuffer(String label, int usage, long bytes) {
        return RenderSystem.getDevice().createBuffer(() -> label, usage, bytes);
    }

    private void closeWorkBuffer(GpuBuffer buffer) {
        buffer.close();
    }

    /** Next (doubled) quad index buffer, filled off the render thread through its shared mapping before it is needed. */
    private @Nullable GpuBuffer indexPending;
    private long indexPendingQuads;
    private volatile boolean indexPendingReady;
    private java.util.concurrent.@Nullable Future<?> indexPendingFill;
    private long indexAsyncSwaps, indexSyncBuilds;
    /** First index buffer size (quads; 2^22 = 96 MiB), built at the first LOD frame on world join, so growth during play is
     * rare and then prepared in the background ({@code mc.lod.indexMinQuads}). */
    private static final long INDEX_MIN_QUADS = Math.max(65536L, Long.getLong("mc.lod.indexMinQuads", 1L << 22));
    private static final java.util.concurrent.ExecutorService INDEX_FILLER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "lod-index-fill");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private static void fillQuadIndices(final long address, final long quads) {
        for (long q = 0; q < quads; q++) {
            long at = address + 24L * q;
            int v = (int)(4L * q);
            MemoryUtil.memPutInt(at, v);
            MemoryUtil.memPutInt(at + 4, v + 1);
            MemoryUtil.memPutInt(at + 8, v + 2);
            MemoryUtil.memPutInt(at + 12, v + 2);
            MemoryUtil.memPutInt(at + 16, v + 3);
            MemoryUtil.memPutInt(at + 20, v);
        }
    }

    private GpuBuffer indexBuffer(final long quads) {
        // A finished pending buffer replaces the current one as soon as it is ready (it is larger and has the same prefix).
        if (this.indexPending != null && this.indexPendingReady && this.indexPendingQuads <= this.indexQuads) {
            this.indexPending.close();   // overtaken by a synchronous build; the fill is finished, never used by the GPU
            this.indexPending = null;
            this.indexPendingReady = false;
            this.indexPendingFill = null;
        }
        if (this.indexPending != null && this.indexPendingReady) {
            this.retire(this.indexBuffer);
            this.indexBuffer = this.indexPending;
            this.indexQuads = this.indexPendingQuads;
            this.indexPending = null;
            this.indexPendingReady = false;
            this.indexPendingFill = null;
            this.indexAsyncSwaps++;
        }
        if (this.indexBuffer == null || this.indexQuads < quads) {
            // Needed now and nothing ready: build synchronously (startup, or growth faster than the background fill).
            long n = Math.max(INDEX_MIN_QUADS, Long.highestOneBit(Math.max(1L, quads)) << 1);
            ByteBuffer data = MemoryUtil.memAlloc((int)(n * 24L));
            try {
                fillQuadIndices(MemoryUtil.memAddress(data), n);
                GpuBuffer next = RenderSystem.getDevice().createBuffer(() -> "LOD quad index buffer", GpuBuffer.USAGE_INDEX, data);
                this.retire(this.indexBuffer);
                this.indexBuffer = next;
            } finally {
                MemoryUtil.memFree(data);
            }
            this.indexQuads = n;
            this.indexSyncBuilds++;
        }
        // Past half capacity: prepare the doubled buffer in the background (shared storage: written in place).
        if (this.indexPending == null && quads > this.indexQuads / 2 && this.indexQuads <= (1L << 26)) {
            final long n = this.indexQuads << 1;
            GpuBuffer next = RenderSystem.getDevice().createBuffer(() -> "LOD quad index buffer (prepared)", GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_MAP_WRITE, n * 24L);
            final long address = MetalClusterCull.contents(next);
            this.indexPending = next;
            this.indexPendingQuads = n;
            this.indexPendingReady = false;
            this.indexPendingFill = INDEX_FILLER.submit(() -> {
                fillQuadIndices(address, n);
                this.indexPendingReady = true;
            });
        }
        return this.indexBuffer;
    }

    private void retire(final @Nullable GpuBuffer buffer) {
        if (buffer != null) {
            // Call only after replacement allocation succeeds: a later snapshot must never see an
            // already-retired buffer left in a current field by an allocation failure.
            long retirementEpoch = this.geometryLeases.retire();
            this.retired.add(buffer);
            this.retiredAt.add(new BufferRetirement(this.frame, retirementEpoch));
        }
    }

    private void retireQuads(final @Nullable GpuBuffer buffer, final long capacity) {
        if (buffer != null) {
            long bytes = VISIBLE_QUAD_BYTES * capacity;
            this.retiredQuadBuffers.put(buffer, bytes);
            this.retiredQuadBytes += bytes;
            this.retire(buffer);
        }
    }

    private void retireInactiveJobs() {
        for (Slot slot : this.slots) {
            if (slot == null) continue;
            slot.compactJobs.retireInactive(this.frame, this.bufferMaintenance, () -> this.retiredQuadBytes,
                job -> VISIBLE_QUAD_BYTES * job.quadCapacity, job -> {
                    if (slot == this.reuseCullSlot) this.invalidateReuse();
                    this.retireQuads(job.quads, job.quadCapacity);
                    this.retire(job.outItems);
                    this.retire(job.scratch);
                    this.retire(job.args);
                    slot.jobs.remove(job);
                    slot.used = slot.jobs.size();
                });
        }
    }

    /** Diagnostics ({@code mc.lod.diag} contains "levels"): one draw group per level, visible quads per level summed over frames. */
    static final boolean DIAG_LEVELS = System.getProperty("mc.lod.diag", "").contains("levels");
    private final long[] visibleByLevelSum = new long[8];

    /** Stats: CPU time of {@link #frame} (render thread) and of the item rebuilds inside it. */
    long frameNanos;
    long frames;
    private long buildNanos;
    private long builds;
    private long itemCacheBuilds, itemCacheHits, itemCachePayloadBytes;
    // Completed builds, including lazy preparation time. Index 0 scalar, 1 cached.
    private final long[] itemModeBuilds = new long[2], itemModeBuildNanos = new long[2];
    // Mode = (cacheItems ? 2 : 0) | (cacheGroups ? 1 : 0); same whole-build wall timer as itemCache.
    private final long[] groupModeBuilds = new long[4], groupModeBuildNanos = new long[4];
    private final long[] groupLookupCalls = new long[2], groupLookupProbes = new long[2];
    private long groupLookupHits;
    private int groupLookupTables;
    // Completed whole builds, 0 effective OFF / 1 effective ON; existing item/group mode counters stay intact.
    private final long[] nodeModeBuilds = new long[2], nodeModeBuildNanos = new long[2];
    // Cache operations include failed attempts; owned bytes describe only currently retained complete packets.
    private long nodeItemMisses, nodeItemHits, nodeItemCapFallbacks, nodeItemPacketBuilds;
    private long nodeItemReplayedSections, nodeItemReplayedClusters;

    String statsJson() {
        QuadWorkBytes work = this.quadWorkBytes;
        return String.format(java.util.Locale.ROOT,
            "{\"reuse\": " + this.reuse.statsJson()
            + ", \"asyncConsumer\": " + (this.asyncProbe==null?"null":this.asyncProbe.consumerStatsJson())
            + ", \"reuseCullHits\": " + this.reuseCullHits + ", \"turnRetained\": " + this.turnRetained + ", \"turnCaptureFrames\": " + this.turnCaptureFrames + ", \"turnStripFrames\": " + this.turnStripFrames + ", \"turnSwaps\": " + this.turnSwaps + ", \"turnStripAborts\": " + this.turnStripAborts + ", \"heldFlap\": " + this.heldFlapStats()+ ", \"realPending\": " + this.realPending.size() + ", \"realPendingRebuilds\": " + this.realPendingRebuilds
            + ", \"itemBuildProfile\": " + (ITEM_BUILD_PROFILE ? this.itemBuildProfile.statsJson() : "null")
            + ",\"nearHandoff\":{\"requested\":" + nearHandoff + ",\"active\":" + this.nearHandoffActive
            + ",\"coveredSections\":" + (this.nearCoverage == null ? 0 : this.nearCoverage.coveredCount())
            + ",\"revision\":" + (this.nearCoverage == null ? 0 : this.nearCoverage.revision())
            + ",\"maskReplacements\":" + this.nearMaskReplacements + ",\"fullVisibilitySections\":" + this.nearFullVisibilitySections
            + ",\"exposureCache\":" + cacheNearExposure
            + ",\"exposureBuilds\":" + (this.nearCoverage == null ? 0 : this.nearCoverage.exposureBuilds())
            + ",\"exposureHits\":" + (this.nearCoverage == null ? 0 : this.nearCoverage.exposureHits())
            + ",\"stableCoverage\":" + stableNearCoverage + ",\"outsideObserved\":" + this.nearOutsideObserved
            + ",\"narrowReset\":" + narrowCoverageReset + ",\"historyResets\":" + this.nearHistoryResets
            + ",\"historyKept\":" + this.nearHistoryKept + ",\"cost\":" + handoffCostJson() + ",\"drawnNotReady\":" + dnrJson() + ",\"coarseOverlap\":" + this.coarseOverlapJson() + ",\"unmasked\":" + this.unmaskedJson() + "}"
            + ",\"indexAsyncSwaps\":" + this.indexAsyncSwaps + ",\"indexSyncBuilds\":" + this.indexSyncBuilds + ",\"indexQuads\":" + this.indexQuads + ",\"itemMinMs\":" + itemMinMs + ",\"sliceUs\":" + sliceUs + ",\"sliceBuilds\":" + this.sliceBuilds + ",\"sliceFrames\":" + this.sliceFrames + ",\"sliceMaxUs\":" + this.sliceMaxNs / 1000 + ",\"itemBuildsDeferred\":" + this.itemBuildsDeferred + ",\"plantsL0Off\":" + this.builtPlantsL0Off + ",\"droppedPlantQuads\":" + this.droppedPlantQuads + ",\"holeNodes\":" + this.holeNodes + ",\"holeBuilds\":" + this.holeBuilds
            + ",\"asyncItems\":{\"enabled\":" + asyncItems + ",\"active\":" + (this.activeItems != null)
            + ",\"opaqueRequested\":" + asyncOpaque + ",\"opaqueFrames\":" + this.asyncOpaqueFrames
            + ",\"pending\":" + (this.pendingItems != null) + ",\"failed\":" + this.asyncItemFailure
            + ",\"offers\":" + this.asyncOffers + ",\"accepted\":" + this.asyncAccepted
            + ",\"rejected\":" + this.asyncRejected + ",\"bootstrap\":" + this.asyncBootstrap
            + ",\"exactRevalidate\":" + asyncExactRevalidate + ",\"rechecks\":" + this.asyncRechecks + ",\"equivalent\":" + this.asyncEquivalent
            + ",\"fallbacks\":" + this.asyncFallbacks + ",\"uploadDefers\":" + this.asyncUploadDefers
            + ",\"captureNs\":" + this.asyncCaptureNs + ",\"installNs\":" + this.asyncInstallNs
            + ",\"workerNs\":" + this.asyncWorkerNs + ",\"acceptedClusters\":" + this.asyncAcceptedClusters
            + ",\"recordSections\":" + this.asyncRecordSections + ",\"recordClusters\":" + this.asyncRecordClusters
            + ",\"scalarSections\":" + this.asyncScalarSections
            + ",\"uploadRecordNs\":" + this.asyncUploadRecordNs + ",\"uploadRecordSections\":" + this.asyncUploadRecordSections
            + ",\"error\":\"" + this.asyncItemError + "\"}"
            + ",\"nodeItems\":{\"enabled\":" + cacheNodeItems + ",\"activeLastBuild\":" + this.buildCacheNodeItems
            + ",\"budgetBytes\":" + NODE_ITEM_CACHE_BYTES + ",\"limitEntries\":" + NODE_ITEM_CACHE_ENTRIES
            + ",\"entries\":" + (this.nodeItemCache == null ? 0 : this.nodeItemCache.size())
            + ",\"payloadBytes\":" + (this.nodeItemCache == null ? 0 : this.nodeItemCache.payloadBytes())
            + ",\"chargedBytes\":" + (this.nodeItemCache == null ? 0 : this.nodeItemCache.chargedBytes())
            + ",\"misses\":" + this.nodeItemMisses + ",\"packetHits\":" + this.nodeItemHits + ",\"fallbacks\":" + this.nodeItemCapFallbacks
            + ",\"packetBuilds\":" + this.nodeItemPacketBuilds + ",\"replayedSections\":" + this.nodeItemReplayedSections
            + ",\"replayedClusters\":" + this.nodeItemReplayedClusters
            + ",\"modeEncoding\":\"effectiveOffOn\",\"buildsByMode\":" + Arrays.toString(this.nodeModeBuilds)
            + ",\"buildNsByMode\":" + Arrays.toString(this.nodeModeBuildNanos) + "}"
            + ", \"itemCache\": {\"enabled\":" + cacheItems + ",\"builds\":" + this.itemCacheBuilds
            + ",\"hits\":" + this.itemCacheHits + ",\"payloadBytes\":" + this.itemCachePayloadBytes
            + ",\"scalarBuilds\":" + this.itemModeBuilds[0] + ",\"scalarBuildNs\":" + this.itemModeBuildNanos[0]
            + ",\"cachedBuilds\":" + this.itemModeBuilds[1] + ",\"cachedBuildNs\":" + this.itemModeBuildNanos[1] + "}"
            + ",\"groupCache\":{\"enabled\":" + cacheGroups + ",\"diagnostics\":" + GROUP_LOOKUP_DIAGNOSTICS
            + ",\"buildsByMode\":" + Arrays.toString(this.groupModeBuilds) + ",\"buildNsByMode\":" + Arrays.toString(this.groupModeBuildNanos)
            + ",\"callsByMode\":" + Arrays.toString(this.groupLookupCalls) + ",\"probesByMode\":" + Arrays.toString(this.groupLookupProbes)
            + ",\"hits\":" + this.groupLookupHits + ",\"tables\":" + this.groupLookupTables
            + ",\"referenceSlots\":" + this.groupLookupTables * GROUP_LOOKUP_SLOTS + "}"
            + ", \"allocationRetirementEpoch\": " + this.allocationRetirementEpoch()
            + ", \"geometryLeaseCount\": " + this.geometryLeaseCount()
            + ", \"oldestGeometryLeaseEpoch\": " + this.oldestGeometryLeaseEpoch()
            + ", \"geometryDisposalPending\": " + (this.geometryLeases.closed() && !this.geometryLeases.disposed())
            + ", \"visibleQuadBytes\": " + VISIBLE_QUAD_BYTES
            + ", \"visibleQuadBufferBytes\": " + (work.current() + work.inactive())
            + ", \"compactWorkBuffers\": " + COMPACT_WORK_BUFFERS
            + ", \"visibleQuadRequiredBytes\": " + work.required()
            + ", \"visibleQuadCurrentBytes\": " + work.current()
            + ", \"visibleQuadInactiveBytes\": " + work.inactive()
            + ", \"visibleQuadRetiredBytes\": " + work.retired()
            + ", \"arenaReservedBytes\": " + ((long)this.arenas.size() * ARENA_BYTES)
            + ", \"residentAllocationBytes\": " + this.residentAllocationBytes
            + ",\"residentSet\":{\"requested\":" + residentSet + ",\"active\":" + this.residentSetActive
            + ",\"setRemovals\":" + this.residentSetRemovals + ",\"listRemovals\":" + this.residentListRemovals + "}"
            + ", \"occlusionBufferBytes\": " + (4L * this.visEntries * ((this.visBuffers[0] != null ? 1 : 0) + (this.visBuffers[1] != null ? 1 : 0)))
            + ", \"cullGpuMsAvg\": " + cullMsAvg() + ", \"drawnQuadsBySlot\": " + java.util.Arrays.toString(this.drawnQuadsBySlot) + ", \"visibleQuadsByLevelAvg\": " + visibleByLevelAvg() + ", \"treeQuads\": " + LodMesher.TREE_QUADS.get() + ", \"treeBillboards\": " + LodCompact.BILLBOARDS.sum() + ", \"cpuFrameUs\": %.1f, \"itemBuilds\": %d, \"itemBuildUs\": %.1f, \"arenas\": %d, \"gpuMB\": %.1f, \"residentSections\": %d, \"drawnSections\": %d, \"drawnClusters\": %d, \"drawnQuads\": %d, "
                + "\"uploadedMB\": %.1f, \"uploadMs\": %.1f, \"slotsUsed\": %d, \"budgetRefusals\": %d, \"uploadedQuadsByLayerLevel\": %s, \"drawnQuadsByLevel\": %s, \"visibleQuads\": %d, \"visibleQuadsAvg\": %d, \"compactLayers\": %d, \"blockLayers\": %d, \"compactQuads\": %d, \"compactFails\": %s, \"compactFailLevels\": %s, \"fadeStarts\": %d, \"fading\": %d, \"occludedQuads\": %d, \"occludedQuadsAvg\": %d, \"occlusionBoxes\": %d, \"occlusionStamp\": %d, \"greedy\": %s}",
            this.frames == 0L ? 0.0 : this.frameNanos / 1e3 / this.frames, this.builds, this.builds == 0L ? 0.0 : this.buildNanos / 1e3 / this.builds,
            this.arenas.size(), this.gpuBytes / 1048576.0, this.resident.size(), this.drawnSections, this.drawnClusters, this.drawnQuads,
            this.uploadedBytes / 1048576.0, this.uploadNanos / 1e6, this.nextSlot - this.freeSlotCount, this.budgetRefusals, java.util.Arrays.deepToString(this.quadsByLayerLevel),
            java.util.Arrays.toString(this.drawnQuadsByLevel), this.visibleQuads, this.visibleFrames == 0L ? 0L : this.visibleSum / this.visibleFrames, LodCompact.LAYERS_COMPACT.get(), LodCompact.LAYERS_BLOCK.get(), LodCompact.QUADS_COMPACT.get(), LodCompact.FAILS.toString(), LodCompact.FAIL_LEVELS.toString(), this.fadeStarts, this.fades.size(), this.occludedQuads,
            this.occludedFrames == 0L ? 0L : this.occludedSum / this.occludedFrames, this.boxesLast, this.occlStamp, LodGreedy.stats());
    }

    /** Published once per upload frame (one CPU frame behind). Required/current describe each slot's latest use
     * within RING frames; inactive is all older retained capacity.
     * Retired bytes count LodGpu's retirement queue only, not the backend's later deferred release/buffer pool. */
    private void publishQuadWorkBytes() {
        long required = 0L, current = 0L, inactive = 0L;
        for (Slot slot : this.slots) {
            if (slot == null) continue;
            for (Job job : COMPACT_WORK_BUFFERS ? slot.compactJobs.values() : slot.jobs) {
                if (job.quads == null) continue;
                if (job.current(this.frame, RING)) {
                    required += VISIBLE_QUAD_BYTES * job.requiredQuads;
                    current += VISIBLE_QUAD_BYTES * job.quadCapacity;
                } else {
                    inactive += VISIBLE_QUAD_BYTES * job.quadCapacity;
                }
            }
        }
        this.quadWorkBytes = new QuadWorkBytes(required, current, inactive, this.retiredQuadBytes);
    }

    private String visibleByLevelAvg() {
        long[] out = new long[8];
        for (int l = 0; l < 8; l++) {
            out[l] = this.visibleFrames == 0L ? 0L : this.visibleByLevelSum[l] / this.visibleFrames;
        }
        return java.util.Arrays.toString(out);
    }

    /** Stage 3 r6: average GPU time of a cull command buffer (all cluster culls, terrain and LOD), ms. */
    static String cullMsAvg() {
        long[] c = MetalClusterCull.cullStats();
        return String.format(java.util.Locale.ROOT, "%.3f", c[1] == 0L ? 0.0 : c[0] / 1e6 / c[1]);
    }

    long drawnQuads() {
        return this.drawnQuads;
    }

    void close() {
        RenderSystem.assertOnRenderThread();
        if (this.closed) return;
        this.closed = true;
        this.sliceOrder = null;
        this.cancelItemPreparation();
        this.retireItems(this.activeItems);
        this.activeItems = null;
        this.releaseAllNodeItems();
        // CPU-only copies are not held by geometry leases or in-flight GPU work.
        for (GpuSection s : this.resident) this.releaseSectionItems(s);
        this.releaseGroupLookups();
        this.geometryEpoch++;
        this.reuseCullDraws = null;
        this.reuseCullSlot = null;
        this.reuseCullKey = null;
        this.reuseLastView = null;
        this.occlusionDraw = null;
        try {
            if (this.asyncProbe != null) this.asyncProbe.close();
            this.reuse.close();
        } finally {
            // No wait: the final token close calls disposeGeometry even if this world no longer frames.
            this.geometryLeases.close();
        }
    }

    /** Unit forward/side/up of the five camera-relative frame planes (rows of P*V: eye = r3, L-R ~ r0, B-T ~ r1). */
    private static double[] basis(final double[] p) {
        double[] f = {p[16], p[17], p[18]}, x = {p[0] - p[4], p[1] - p[5], p[2] - p[6]};
        norm(f);
        double d = x[0] * f[0] + x[1] * f[1] + x[2] * f[2];
        for (int i = 0; i < 3; i++) x[i] -= d * f[i];
        norm(x);
        double[] y = {f[1] * x[2] - f[2] * x[1], f[2] * x[0] - f[0] * x[2], f[0] * x[1] - f[1] * x[0]};
        return new double[]{x[0], x[1], x[2], y[0], y[1], y[2], f[0], f[1], f[2]};
    }

    private static void norm(final double[] v) {
        double l = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (l > 0) { v[0] /= l; v[1] /= l; v[2] /= l; }
    }

    /** Rotation angle (radians) between the two views' orientations: every view direction moves by at most this. */
    /** Column {@code index} of {@code count} of a (guard) frustum: its left/right planes become x >= x0 and x <= x1 in its
     * own clip space (r3 = (L+R)/2 = w, r0 = (L-R)/2 = x), every other plane unchanged. */
    static double[] stripPlanes(final double[] wide, final int index, final int count) {
        double[] out = wide.clone();
        double x0 = -1.0 + 2.0 * index / count, x1 = -1.0 + 2.0 * (index + 1) / count;
        for (int c = 0; c < 4; c++) {
            double w = (wide[c] + wide[4 + c]) * 0.5, x = (wide[c] - wide[4 + c]) * 0.5;
            out[c] = x - x0 * w;
            out[4 + c] = x1 * w - x;
        }
        return out;
    }

    static double rotationAngle(final double[] a, final double[] b) {
        double[] u = basis(a), v = basis(b);
        double trace = 0;
        for (int r = 0; r < 3; r++) trace += u[3 * r] * v[3 * r] + u[3 * r + 1] * v[3 * r + 1] + u[3 * r + 2] * v[3 * r + 2];
        return Math.acos(Math.max(-1.0, Math.min(1.0, (trace - 1.0) * 0.5)));
    }

    /** Smallest angle (radians) from the central frustum's corner rays to the guard's side planes. */
    static double safeAngle(final double[] central, final double[] guard) {
        double[] f = {central[16], central[17], central[18]};
        int[][] pairs = {{0, 2}, {2, 1}, {1, 3}, {3, 0}};   // planes left 0, right 1, bottom 2, top 3
        double safe = Double.POSITIVE_INFINITY;
        for (int[] pr : pairs) {
            double[] a = {central[4 * pr[0]], central[4 * pr[0] + 1], central[4 * pr[0] + 2]};
            double[] b = {central[4 * pr[1]], central[4 * pr[1] + 1], central[4 * pr[1] + 2]};
            double[] c = {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
            if (c[0] * f[0] + c[1] * f[1] + c[2] * f[2] < 0) { c[0] = -c[0]; c[1] = -c[1]; c[2] = -c[2]; }
            norm(c);
            for (int g = 0; g < 4; g++) {
                double[] n = {guard[4 * g], guard[4 * g + 1], guard[4 * g + 2]};
                norm(n);
                safe = Math.min(safe, Math.asin(Math.max(-1.0, Math.min(1.0, n[0] * c[0] + n[1] * c[1] + n[2] * c[2]))));
            }
        }
        return Math.max(0.0, safe);
    }

    /** CPU-only references; OFF keeps them warm, owner close releases them without touching GPU resources. */
    private void releaseGroupLookups() {
        for (Arena arena : this.arenas) { arena.groupLookup = null; arena.backGroupLookup = null; }
        this.groupLookupTables = 0;
    }

    /** Called exactly once by the lease owner, on the render thread, after close and the final release. */
    private void disposeGeometry() {
        for (Arena a : this.arenas) {
            a.buffer.close();
        }
        this.arenas.clear();
        if (this.indexPending != null) {
            // The filler writes through the buffer's mapping: never free it under the worker.
            try { if (this.indexPendingFill != null) this.indexPendingFill.get(); } catch (Exception ignored) { }
            this.indexPending.close();
            this.indexPending = null;
        }
        for (GpuBuffer b : new GpuBuffer[]{this.sectionInfos, this.sectionTable, this.indexBuffer, this.visBuffers[0], this.visBuffers[1], this.secOcc}) {
            if (b != null) {
                b.close();
            }
        }
        for (GpuBuffer b : this.itemRing) {
            if (b != null) {
                b.close();
            }
        }
        for (GpuBuffer b : this.secRing) {
            if (b != null) b.close();
        }
        for (Slot s : this.slots) {
            if (s == null) {
                continue;
            }
            for (Job j : COMPACT_WORK_BUFFERS ? s.compactJobs.values() : s.jobs) {
                for (GpuBuffer b : new GpuBuffer[]{j.outItems, j.quads, j.args, j.scratch}) {
                    if (b != null) {
                        this.closeWorkBuffer(b);
                    }
                }
            }
            for (GpuBuffer b : new GpuBuffer[]{s.boxes, s.occlParams, s.nearMask}) {
                if (b != null) {
                    b.close();
                }
            }
        }
        for (GpuBuffer b : this.retired) {
            this.closeWorkBuffer(b);
        }
        this.retired.clear();
        this.retiredAt.clear();
        this.deferredFrees.clear();
        this.resident.clear();
        this.origins.clear();
        this.groups.clear();
        this.backGroups.clear();
        this.sliceOrder = null;
        this.fades.clear();
        this.drawnSet.clear();
        this.sectionInfos = null;
        this.sectionTable = null;
        this.indexBuffer = null;
        this.secOcc = null;
        Arrays.fill(this.visBuffers, null);
        Arrays.fill(this.itemRing, null);
        Arrays.fill(this.secRing, null);
        Arrays.fill(this.slots, null);
        this.freeSlotCount = 0;
        this.nextSlot = 0;
        this.gpuBytes = 0L;
        this.residentAllocationBytes = 0L;
        this.retiredQuadBuffers.clear();
        this.retiredQuadBytes = 0L;
        this.quadWorkBytes = new QuadWorkBytes(0L, 0L, 0L, 0L);
        if (this.jobs != 0L) {
            MemoryUtil.nmemFree(this.jobs);
            MemoryUtil.nmemFree(this.view);
            this.jobs = 0L;
            this.view = 0L;
        }
        if (this.jobExtra != 0L) {
            MemoryUtil.nmemFree(this.jobExtra);
            MemoryUtil.nmemFree(this.occlBlock);
            this.jobExtra = 0L;
            this.occlBlock = 0L;
        }
        if (this.secBlock != 0L) {
            MemoryUtil.nmemFree(this.secBlock);
            this.secBlock = 0L;
        }
    }
}
