package net.minecraft.client.renderer.chunk;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.IndexType;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.renderer.DynamicGpuData;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/**
 * Allocation-free builder of the multi-draw-indirect terrain draw list (render-thread CPU work; docs/stage2/TERRAIN-PLAN.md
 * S4, enabled by {@link TerrainToggles#fastDrawList}). It produces exactly what {@code LevelRenderer.extractSectionDrawGroups}
 * plus the flattening in {@code prepareChunkRendersIndirect} produce, in the same order, with:
 *
 * <ul>
 *   <li>per-mesh descriptors instead of lookups and pointer chains: {@link CompiledSectionMesh#hot} packs, per layer,
 *       the draw (index count and type, sorted-index flag), the {@link TerrainMeshInfo} data the culling reads (bounds,
 *       bucket planes, quad count) and the mesh's own uber-buffer offsets, next to {@link CompiledSectionMesh#uberBuffers}
 *       (the heap buffers, kept equal to {@code getRenderSectionSlice} by the uber buffers' listeners): no EnumMap, no
 *       HashMap lookups, no {@code RenderSectionBufferSlice}, and no SectionDraw/TerrainMeshInfo object touched per
 *       frame in the common case;</li>
 *   <li>a one-entry cache of the S1 runs per section-layer (in the descriptor): the visible bucket mask of a section
 *       changes only when the eye crosses one of its bucket planes;</li>
 *   <li>the S2 frustum test unrolled over the frame's five planes (same arithmetic as {@link TerrainFrustum#isBoxVisible});</li>
 *   <li>draw records as 5 ints in per-group arrays reused across frames (no {@code IndexedDraw} objects, no growing
 *       {@code ArrayList}s) and section infos as 4 ints (no {@code ChunkSectionInfo} objects);</li>
 *   <li>groups found by identity of (vertex heap, index heap, index type, vertex format) with a per-layer last-hit cache
 *       (no {@code Int2ObjectOpenHashMap} per frame; same grouping as vanilla's hash key barring hash collisions). The
 *       format is the S5 compact flag: compact and 28-byte section-layers of one heap form separate groups, drawn with
 *       the {@code *_COMPACT} and the vanilla pipelines;</li>
 *   <li>one bulk copy of all records into the mapped indirect ring and one of the section infos into the instance
 *       staging buffer ({@link DynamicGpuData#writeChunkSectionCommandsRaw}, {@link DynamicGpuData#writeChunkSectionsInstancedRaw}),
 *       byte-identical to {@code IndexedDraw.write} / {@code ChunkSectionInfo.write}.</li>
 *   <li>with {@link TerrainToggles#sortSolidSections} / {@link TerrainToggles#sortCutoutSections}, the SOLID / CUTOUT groups'
 *       records stably sorted front-to-back by section distance ({@link Group#sortBySectionDistance}); the vanilla-shaped
 *       path applies the same order ({@link #sortBySectionDistance(List, List, double, double, double)}).</li>
 * </ul>
 *
 * Render thread only; {@link #build} runs under SectionRenderDispatcher's copyLock (taken by the caller).
 */
public final class TerrainDrawList {
    private static final ChunkSectionLayer[] LAYERS = ChunkSectionLayer.values();
    private static final int LAYER_COUNT = LAYERS.length;
    private static final IndexType[] INDEX_TYPES = IndexType.values();
    /** Ints per indirect record: indexCount, instanceCount, firstIndex, baseVertex, baseInstance (VkDrawIndexedIndirectCommand). */
    public static final int RECORD_INTS = 5;
    /** Ints per section info: x, y, z, visibility bits (std140 ivec3 + float, CHUNK_SECTION_UBO_SIZE = 16 bytes). */
    public static final int SECTION_INTS = 4;

    // Descriptor layout (ints per layer in CompiledSectionMesh.hot; floats as raw bits, longs as lo/hi).
    static final int HOT_STRIDE = 32;
    static final int H_FLAGS = 0;
    static final int H_INDEX_COUNT = 1;
    static final int H_QUADS = 2;
    static final int H_MIN_X = 3;
    static final int H_MIN_Y = 4;
    static final int H_MIN_Z = 5;
    static final int H_MAX_X = 6;
    static final int H_MAX_Y = 7;
    static final int H_MAX_Z = 8;
    static final int H_PLANE_DOWN = 9;
    static final int H_PLANE_NORTH = 10;
    static final int H_PLANE_EAST = 11;
    static final int H_PLANE_UP = 12;
    static final int H_PLANE_SOUTH = 13;
    static final int H_PLANE_WEST = 14;
    static final int H_RUN_MASK = 15;
    static final int H_RUN_BRIDGE = 16;
    static final int H_RUN_COUNT = 17;
    static final int H_RUNS = 18;
    static final int H_VERTEX_OFFSET = 26;
    static final int H_INDEX_OFFSET = 28;
    /** R9-1 (TranslucentFacing): index count of the short list after the full one (0 = none) and its dropped bucket mask. */
    static final int H_TF_KEPT = 30;
    static final int H_TF_MASK = 31;
    static final int F_PRESENT = 1;
    static final int F_CUSTOM_INDEX = 2;
    static final int F_INFO = 4;
    static final int F_BOUNDS_VALID = 8;
    static final int F_BUCKETED = 16;
    /** S5: the section-layer's vertices are 16-byte {@link TerrainCompact} vertices. */
    static final int F_COMPACT = 32;
    static final int INDEX_TYPE_SHIFT = 8;

    /** Fills layer {@code layer}'s descriptor in {@code hot} from its draw (offsets untouched; run cache emptied). */
    static void describe(final int[] hot, final int layer, final SectionMesh.SectionDraw draw) {
        int base = layer * HOT_STRIDE;
        TerrainMeshInfo info = draw.info();
        // Index type stored as ordinal + 1 (0 = none; only read for sorted index buffers, which always have one).
        IndexType indexType = draw.indexType();
        int flags = F_PRESENT | (draw.hasCustomIndexBuffer() ? F_CUSTOM_INDEX : 0) | (draw.compact() ? F_COMPACT : 0)
            | (indexType == null ? 0 : indexType.ordinal() + 1) << INDEX_TYPE_SHIFT;
        if (info != null) {
            flags |= F_INFO | (info.boundsValid() ? F_BOUNDS_VALID : 0) | (info.bucketed() ? F_BUCKETED : 0);
            hot[base + H_QUADS] = info.quadCount();
            hot[base + H_MIN_X] = Float.floatToRawIntBits(info.minX);
            hot[base + H_MIN_Y] = Float.floatToRawIntBits(info.minY);
            hot[base + H_MIN_Z] = Float.floatToRawIntBits(info.minZ);
            hot[base + H_MAX_X] = Float.floatToRawIntBits(info.maxX);
            hot[base + H_MAX_Y] = Float.floatToRawIntBits(info.maxY);
            hot[base + H_MAX_Z] = Float.floatToRawIntBits(info.maxZ);
            hot[base + H_PLANE_DOWN] = Float.floatToRawIntBits(info.plane(TerrainMeshInfo.SLOT_DOWN));
            hot[base + H_PLANE_NORTH] = Float.floatToRawIntBits(info.plane(TerrainMeshInfo.SLOT_NORTH));
            hot[base + H_PLANE_EAST] = Float.floatToRawIntBits(info.plane(TerrainMeshInfo.SLOT_EAST));
            hot[base + H_PLANE_UP] = Float.floatToRawIntBits(info.plane(TerrainMeshInfo.SLOT_UP));
            hot[base + H_PLANE_SOUTH] = Float.floatToRawIntBits(info.plane(TerrainMeshInfo.SLOT_SOUTH));
            hot[base + H_PLANE_WEST] = Float.floatToRawIntBits(info.plane(TerrainMeshInfo.SLOT_WEST));
        }

        hot[base + H_FLAGS] = flags;
        hot[base + H_INDEX_COUNT] = draw.indexCount();
        hot[base + H_RUN_MASK] = -1;
    }

    /**
     * Front-to-back sort key of a section at render origin {@code (x, y, z)}: the squared distance from the camera to the
     * section's centre, as the raw bits of a float (non-negative, so the int order is the distance order).
     */
    public static int sectionDistanceKey(final int x, final int y, final int z, final double cameraX, final double cameraY, final double cameraZ) {
        double dx = x + 8.0 - cameraX;
        double dy = y + 8.0 - cameraY;
        double dz = z + 8.0 - cameraZ;
        return Float.floatToRawIntBits((float)(dx * dx + dy * dy + dz * dz));
    }

    /**
     * The section order of {@link Group#sortBySectionDistance} applied to a vanilla-shaped record list (stable
     * {@code List.sort} by the same key): used by {@code LevelRenderer}'s non-S4 path and its draw-list verification.
     */
    public static void sortBySectionDistance(
        final List<DynamicGpuData.IndexedDraw> draws, final List<DynamicGpuData.ChunkSectionInfo> sectionInfos, final double cameraX,
        final double cameraY, final double cameraZ
    ) {
        if (draws.size() > 1) {
            draws.sort(Comparator.comparingInt(draw -> {
                DynamicGpuData.ChunkSectionInfo info = sectionInfos.get(draw.baseInstance());
                return sectionDistanceKey(info.x(), info.y(), info.z(), cameraX, cameraY, cameraZ);
            }));
        }
    }

    /** R9-1: published by the TRANSLUCENT index upload callback, in the same upload pass as the allocation's offset. */
    static void setTranslucentFacing(final int[] hot, final int layer, final int keptIndexCount, final int droppedMask) {
        int base = layer * HOT_STRIDE;
        hot[base + H_TF_KEPT] = keptIndexCount;
        hot[base + H_TF_MASK] = droppedMask;
    }

    static void setOffset(final int[] hot, final int layer, final boolean index, final long offset) {
        int at = layer * HOT_STRIDE + (index ? H_INDEX_OFFSET : H_VERTEX_OFFSET);
        hot[at] = (int)offset;
        hot[at + 1] = (int)(offset >>> 32);
    }

    private static long offset(final int[] hot, final int at) {
        return hot[at] & 0xFFFFFFFFL | (long)hot[at + 1] << 32;
    }

    /** One draw group: the records of one (layer, vertex heap, index heap, index type, vertex format) run. */
    public static final class Group {
        int layer;
        boolean compact;
        /** Round 12 Part B: the group draws greedy copies (TerrainGreedy; greedy pipeline variant). */
        boolean greedy;
        GpuBuffer vertexBuffer;
        @Nullable GpuBuffer indexBuffer;
        @Nullable IndexType indexType;
        GpuBufferSlice vertexSlice;
        @Nullable GpuBufferSlice indexSlice;
        int[] records = new int[RECORD_INTS * 64];
        int recordCount;
        // Front-to-back section order (sortSections): sort keys and the second record array, allocated on first use.
        long @Nullable [] sortKeys;
        int @Nullable [] sortedRecords;

        public int layer() {
            return this.layer;
        }

        /** S5: the group's section-layers use the 16-byte vertex (draw with the compact pipeline variant). */
        public boolean compact() {
            return this.compact;
        }

        public boolean greedy() {
            return this.greedy;
        }

        public GpuBufferSlice vertexSlice() {
            return this.vertexSlice;
        }

        public @Nullable GpuBufferSlice indexSlice() {
            return this.indexSlice;
        }

        public @Nullable IndexType indexType() {
            return this.indexType;
        }

        public int recordCount() {
            return this.recordCount;
        }

        void add(final int indexCount, final int instanceCount, final int firstIndex, final int baseVertex, final int baseInstance) {
            int at = RECORD_INTS * this.recordCount;
            if (at + RECORD_INTS > this.records.length) {
                this.records = Arrays.copyOf(this.records, this.records.length * 2);
            }

            int[] r = this.records;
            r[at] = indexCount;
            r[at + 1] = instanceCount;
            r[at + 2] = firstIndex;
            r[at + 3] = baseVertex;
            r[at + 4] = baseInstance;
            this.recordCount++;
        }

        /**
         * Stable sort of the records by {@link #sectionDistanceKey} of the section info each one draws ({@code baseInstance}
         * into {@code sectionData}): nearest section first, a section's records and equally distant sections keep their
         * order. Same order as {@link TerrainDrawList#sortBySectionDistance(List, List, double, double, double)} on a vanilla-shaped list.
         */
        void sortBySectionDistance(final int[] sectionData, final double cameraX, final double cameraY, final double cameraZ) {
            int n = this.recordCount;
            if (n < 2) {
                return;
            }

            long[] keys = this.sortKeys;
            if (keys == null || keys.length < n) {
                keys = new long[Math.max(n, this.records.length / RECORD_INTS)];
                this.sortKeys = keys;
            }

            int[] r = this.records;
            boolean sorted = true;
            long previous = Long.MIN_VALUE;

            for (int i = 0; i < n; i++) {
                int s = SECTION_INTS * r[RECORD_INTS * i + 4];
                // Distance key (non-negative) in the high half, record position in the low half: stable.
                long key = (long)sectionDistanceKey(sectionData[s], sectionData[s + 1], sectionData[s + 2], cameraX, cameraY, cameraZ) << 32 | i;
                keys[i] = key;
                sorted &= key > previous;
                previous = key;
            }

            if (sorted) {
                return;
            }

            Arrays.sort(keys, 0, n);
            int[] out = this.sortedRecords;
            if (out == null || out.length < r.length) {
                out = new int[r.length];
            }

            for (int i = 0; i < n; i++) {
                System.arraycopy(r, RECORD_INTS * (int)keys[i], out, RECORD_INTS * i, RECORD_INTS);
            }

            this.sortedRecords = r;
            this.records = out;
        }

        /** Reverses records {@code [from, recordCount)} in place (vanilla's {@code Collections.reverse(subList)}). */
        void reverseFrom(final int from) {
            int[] r = this.records;
            for (int i = from, j = this.recordCount - 1; i < j; i++, j--) {
                for (int f = 0; f < RECORD_INTS; f++) {
                    int a = RECORD_INTS * i + f;
                    int b = RECORD_INTS * j + f;
                    int t = r[a];
                    r[a] = r[b];
                    r[b] = t;
                }
            }
        }
    }

    // Frame parameters (set by begin()).
    private boolean respectTranslucentOrder;
    /** Round 12 Part B: draw the layers' greedy copies (TerrainGreedy#draw, once per frame). */
    private boolean greedyDraw;
    private boolean frustumCull;
    private boolean frustumTest;
    private boolean facingEye;
    private final boolean[] layerBucketCull = new boolean[LAYER_COUNT];
    private final boolean[] layerSectionSort = new boolean[LAYER_COUNT];
    private final boolean[] layerTranslucentOrdered = new boolean[LAYER_COUNT];
    private final int[] vertexSize = new int[LAYER_COUNT];
    private double cameraX;
    private double cameraY;
    private double cameraZ;
    private double eyeX;
    private double eyeY;
    private double eyeZ;
    private int maxQuadsPerDraw;
    private int splitProbe;
    private int bridge;
    private long now;
    private long fadeDuration;
    private boolean stats;
    private double boundsMargin;
    // The frustum's five planes, unrolled (TerrainFrustum.isBoxVisible with the same arithmetic, without the array loop).
    private final double[] planeScratch = new double[20];
    private double pa0, pb0, pc0, pd0;
    private double pa1, pb1, pc1, pd1;
    private double pa2, pb2, pc2, pd2;
    private double pa3, pb3, pc3, pd3;
    private double pa4, pb4, pc4, pd4;

    // Groups: pool reused across frames, per-layer creation order.
    private Group[] pool = new Group[16];
    private int poolUsed;
    private final Group[][] layerGroups = new Group[LAYER_COUNT][8];
    private final int[] layerGroupCount = new int[LAYER_COUNT];
    private final @Nullable Group[] lastHit = new Group[LAYER_COUNT];
    private @Nullable GpuBuffer lastTranslucentVertex;
    private @Nullable GpuBuffer lastTranslucentIndex;
    private @Nullable IndexType lastTranslucentIndexType;
    private boolean lastTranslucentCompact;
    private boolean haveLastTranslucent;

    // Output order (layers in order, translucent groups reversed when respecting the order).
    private Group[] outputGroups = new Group[16];
    private int[] outputCounts = new int[16];
    private int outputCount;

    // Section infos.
    private int[] sectionData = new int[SECTION_INTS * 1024];
    private int sectionCount;
    private int largestIndexCount;

    private final int[] runScratch = new int[8];
    private final int[] genericHot = new int[HOT_STRIDE * LAYER_COUNT];

    // Round 12 (a): cluster lists of this frame (TerrainClusters), one per (layer, vertex heap), in creation order.
    private boolean clusterPath;
    private boolean nearFullVisibility;

    private final List<TerrainClusters.Group> clusterPool = new java.util.ArrayList<>();
    private final List<TerrainClusters.Group> clusterGroups = new java.util.ArrayList<>();

    /** Round 12 (a): route SOLID / CUTOUT layers with clusters to {@link #clusterGroups} in the next {@link #build} (default off). */
    public void setClusterPath(final boolean clusterPath) {
        this.clusterPath = clusterPath;
    }

    /** Round 12 (a): the frame's five frustum planes (camera-relative, as copied by {@link #begin}; stale without a frustum). */
    public double[] framePlanes() {
        return this.planeScratch;
    }

    /** Round 12 (a): this frame's cluster lists (empty unless {@link #setClusterPath}). */
    public List<TerrainClusters.Group> clusterGroups() {
        return this.clusterGroups;
    }

    private TerrainClusters.Group clusterGroup(final int l, final GpuBuffer vertexHeap) {
        for (int i = this.clusterGroups.size() - 1; i >= 0; i--) {
            TerrainClusters.Group g = this.clusterGroups.get(i);
            if (g.layer == l && g.vertexBuffer == vertexHeap) {
                return g;
            }
        }
        int n = this.clusterGroups.size();
        if (n == this.clusterPool.size()) {
            this.clusterPool.add(new TerrainClusters.Group());
        }
        TerrainClusters.Group g = this.clusterPool.get(n);
        g.reset(l, vertexHeap);
        this.clusterGroups.add(g);
        return g;
    }
    private final DynamicGpuData.RawWriter recordWriter = this::writeRecords;
    private final DynamicGpuData.RawWriter sectionWriter = this::writeSections;

    /** Sets the frame's culling state; mirrors the preamble of {@code LevelRenderer.extractSectionDrawGroups}. */
    public void begin(
        final boolean respectTranslucentOrder,
        final @Nullable TerrainFrustum frustum,
        final boolean frustumCull,
        final boolean frustumTest,
        final boolean facingEye,
        final boolean bucketCull,
        final double cameraX,
        final double cameraY,
        final double cameraZ,
        final double eyeX,
        final double eyeY,
        final double eyeZ,
        final int maxQuadsPerDraw,
        final int splitProbe,
        final int bridge,
        final long now,
        final long fadeDuration,
        final boolean stats,
        final double boundsMargin,
        final boolean sortSolidSections,
        final boolean sortCutoutSections
    ) {
        this.respectTranslucentOrder = respectTranslucentOrder;
        this.greedyDraw = TerrainGreedy.draw && !TerrainToggles.drawListVerify;
        this.frustumCull = frustumCull;
        this.frustumTest = frustumTest && frustum != null;
        this.facingEye = facingEye;
        for (ChunkSectionLayer layer : LAYERS) {
            int l = layer.ordinal();
            // Only SOLID/CUTOUT are reordered; skipped quads must be exactly the ones the pipeline culls.
            this.layerBucketCull[l] = bucketCull && !layer.translucent() && layer.pipeline(true).isCull();
            this.layerTranslucentOrdered[l] = layer.translucent() && respectTranslucentOrder;
            // Front-to-back section order: never for TRANSLUCENT, whose order is the blend order.
            this.layerSectionSort[l] = layer == ChunkSectionLayer.SOLID ? sortSolidSections : layer == ChunkSectionLayer.CUTOUT && sortCutoutSections;
            this.vertexSize[l] = layer.pipeline(false).getVertexFormatBinding(0).getVertexSize();
        }

        this.cameraX = cameraX;
        this.cameraY = cameraY;
        this.cameraZ = cameraZ;
        this.eyeX = eyeX;
        this.eyeY = eyeY;
        this.eyeZ = eyeZ;
        this.maxQuadsPerDraw = maxQuadsPerDraw;
        this.splitProbe = splitProbe;
        this.bridge = bridge;
        this.now = now;
        this.fadeDuration = fadeDuration;
        this.stats = stats;
        this.boundsMargin = boundsMargin;
        if (frustum != null) {
            double[] p = this.planeScratch;
            frustum.copyPlanes(p);
            this.pa0 = p[0];
            this.pb0 = p[1];
            this.pc0 = p[2];
            this.pd0 = p[3];
            this.pa1 = p[4];
            this.pb1 = p[5];
            this.pc1 = p[6];
            this.pd1 = p[7];
            this.pa2 = p[8];
            this.pb2 = p[9];
            this.pc2 = p[10];
            this.pd2 = p[11];
            this.pa3 = p[12];
            this.pb3 = p[13];
            this.pc3 = p[14];
            this.pd3 = p[15];
            this.pa4 = p[16];
            this.pb4 = p[17];
            this.pc4 = p[18];
            this.pd4 = p[19];
        }

        this.reset();
    }

    private void reset() {
        for (int i = 0; i < this.poolUsed; i++) {
            Group g = this.pool[i];
            g.recordCount = 0;
            g.vertexBuffer = null;
            g.indexBuffer = null;
            g.indexType = null;
            g.vertexSlice = null;
            g.indexSlice = null;
        }

        this.poolUsed = 0;
        Arrays.fill(this.layerGroupCount, 0);
        Arrays.fill(this.lastHit, null);
        this.lastTranslucentVertex = null;
        this.lastTranslucentIndex = null;
        this.lastTranslucentIndexType = null;
        this.lastTranslucentCompact = false;
        this.haveLastTranslucent = false;
        Arrays.fill(this.outputGroups, 0, this.outputCount, null);
        this.outputCount = 0;
        this.sectionCount = 0;
        this.largestIndexCount = 0;
        for (TerrainClusters.Group g : this.clusterGroups) {
            g.vertexBuffer = null;
            g.vertexSlice = null;
        }
        this.clusterGroups.clear();
    }

    /**
     * Builds the list for {@code visibleSections} (caller holds the dispatcher's copyLock). Returns the largest index
     * count of the draws that use the shared quad index buffer (vanilla's {@code largestIndexCount}).
     */
    public int build(final ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections, final SectionRenderDispatcher dispatcher) {
        long buildT0 = System.nanoTime();
        LodGpu handoff = Lod.beginNearFrame();
        Object[] sections = visibleSections.elements();
        int size = visibleSections.size();

        for (int s = 0; s < size; s++) {
            SectionRenderDispatcher.RenderSection section = (SectionRenderDispatcher.RenderSection)sections[s];
            SectionMesh sectionMesh = section.getSectionMesh();
            boolean nearReady = sectionMesh == CompiledSectionMesh.EMPTY ? section.handoffEmptyReady() : nearMeshReady(sectionMesh, dispatcher);
            if (LodGpu.DNR_PROBE && handoff != null && !nearReady && sectionMesh instanceof CompiledSectionMesh probe) LodGpu.probeDrawnNotReady(section.getSectionNode(), probe);
            this.nearFullVisibility = handoff != null && handoff.observeNearSection(section.getSectionNode(), section, section.handoffGeneration(),
                nearReady, section.getVisibility(this.now, this.fadeDuration));
            if (handoff != null && sectionMesh == CompiledSectionMesh.EMPTY) handoff.observeNearEmptyMesh(section.getSectionNode());
            if (sectionMesh instanceof CompiledSectionMesh compiled) {
                if (!section.lodListed && nearMeshReady(compiled, dispatcher)) section.lodListed = true;
                this.addCompiled(section, compiled);
            } else if (sectionMesh != CompiledSectionMesh.EMPTY && sectionMesh != CompiledSectionMesh.UNCOMPILED) {
                this.addGeneric(section, sectionMesh, dispatcher);
            }
            // EMPTY / UNCOMPILED have no draws (getSectionDraw is null for every layer): nothing to emit.
        }

        this.finishOutputOrder();
        LodGpu.hoBuildNs += System.nanoTime() - buildT0;
        LodGpu.hoFrames++;
        return this.largestIndexCount;
    }

    /** Called under copyLock for the SAME mesh used below. All layers must be drawable,
     * even if their faces are culled from this view; EMPTY is authoritative only after validated publication. */
    static boolean nearMeshReady(final SectionMesh mesh, final SectionRenderDispatcher dispatcher) {
        if (mesh == CompiledSectionMesh.UNCOMPILED) return false;
        if (mesh == CompiledSectionMesh.EMPTY) return false; // Requires RenderSection provenance or actual-air evidence.
        boolean hasDraw = false;
        for (int l = 0; l < LAYER_COUNT; l++) {
            SectionMesh.SectionDraw draw = mesh.getSectionDraw(LAYERS[l]);
            if (draw == null) continue;
            hasDraw = true;
            if (mesh instanceof CompiledSectionMesh compiled) {
                if (!compiled.isVertexBufferUploaded(LAYERS[l]) || compiled.uberBuffers[2 * l] == null
                    || draw.hasCustomIndexBuffer() && (!compiled.isIndexBufferUploaded(LAYERS[l])
                        || compiled.uberBuffers[2 * l + 1] == null)) return false;
            } else {
                SectionRenderDispatcher.RenderSectionBufferSlice slice = dispatcher.getRenderSectionSlice(mesh, LAYERS[l]);
                if (slice == null || slice.vertexBuffer() == null
                    || draw.hasCustomIndexBuffer() && slice.indexBuffer() == null) return false;
            }
        }
        return hasDraw || !mesh.hasRenderableLayers();
    }

    private void addCompiled(final SectionRenderDispatcher.RenderSection section, final CompiledSectionMesh mesh) {
        int[] hot = mesh.hot;
        GpuBuffer[] buffers = mesh.uberBuffers;
        int sectionInfoIndex = -1;
        int skip = Lod.realHeld(section.getSectionNode()) ? ChunkSectionLayer.TRANSLUCENT.ordinal() : -1;

        for (int l = 0; l < LAYER_COUNT; l++) {
            if (l != skip && (hot[l * HOT_STRIDE + H_FLAGS] & F_PRESENT) != 0) {
                sectionInfoIndex = this.addLayer(section, mesh.drawsByLayer, hot, l, buffers[2 * l], buffers[2 * l + 1], sectionInfoIndex);
            }
        }
    }

    /**
     * Any other SectionMesh implementation (never reached in vanilla, kept for safety): the vanilla lookups, through a
     * scratch descriptor.
     */
    private void addGeneric(final SectionRenderDispatcher.RenderSection section, final SectionMesh mesh, final SectionRenderDispatcher dispatcher) {
        int sectionInfoIndex = -1;
        int[] hot = this.genericHot;
        SectionMesh.SectionDraw[] draws = new SectionMesh.SectionDraw[LAYER_COUNT];

        for (int l = 0; l < LAYER_COUNT; l++) {
            SectionMesh.SectionDraw draw = mesh.getSectionDraw(LAYERS[l]);
            if (draw != null) {
                draws[l] = draw;
                describe(hot, l, draw);
                if (this.culledByFrustum(section, hot, l * HOT_STRIDE)) {
                    continue;
                }

                SectionRenderDispatcher.RenderSectionBufferSlice slice = dispatcher.getRenderSectionSlice(mesh, LAYERS[l]);
                if (slice != null) {
                    setOffset(hot, l, false, slice.vertexBufferOffset());
                    setOffset(hot, l, true, slice.indexBufferOffset());
                    sectionInfoIndex = this.addLayerUnculled(section, draws, hot, l, slice.vertexBuffer(), slice.indexBuffer(), sectionInfoIndex);
                }
            }
        }
    }

    /** {@link TerrainFrustum#isBoxVisible} unrolled over the copied planes: same expressions, same evaluation order. */
    private boolean boxVisible(final double minX, final double minY, final double minZ, final double maxX, final double maxY, final double maxZ) {
        double a = this.pa0;
        double b = this.pb0;
        double c = this.pc0;
        if (a * (a >= 0.0 ? maxX : minX) + b * (b >= 0.0 ? maxY : minY) + c * (c >= 0.0 ? maxZ : minZ) + this.pd0 < 0.0) {
            return false;
        }

        a = this.pa1;
        b = this.pb1;
        c = this.pc1;
        if (a * (a >= 0.0 ? maxX : minX) + b * (b >= 0.0 ? maxY : minY) + c * (c >= 0.0 ? maxZ : minZ) + this.pd1 < 0.0) {
            return false;
        }

        a = this.pa2;
        b = this.pb2;
        c = this.pc2;
        if (a * (a >= 0.0 ? maxX : minX) + b * (b >= 0.0 ? maxY : minY) + c * (c >= 0.0 ? maxZ : minZ) + this.pd2 < 0.0) {
            return false;
        }

        a = this.pa3;
        b = this.pb3;
        c = this.pc3;
        if (a * (a >= 0.0 ? maxX : minX) + b * (b >= 0.0 ? maxY : minY) + c * (c >= 0.0 ? maxZ : minZ) + this.pd3 < 0.0) {
            return false;
        }

        a = this.pa4;
        b = this.pb4;
        c = this.pc4;
        return !(a * (a >= 0.0 ? maxX : minX) + b * (b >= 0.0 ? maxY : minY) + c * (c >= 0.0 ? maxZ : minZ) + this.pd4 < 0.0);
    }

    /** S2 frustum test of one section-layer (with S0 counters); true when it is dropped. */
    private boolean culledByFrustum(final SectionRenderDispatcher.RenderSection section, final int[] hot, final int base) {
        if (this.frustumTest && (hot[base + H_FLAGS] & (F_INFO | F_BOUNDS_VALID)) == (F_INFO | F_BOUNDS_VALID)) {
            BlockPos origin = section.getRenderOrigin();
            int ox = origin.getX();
            int oy = origin.getY();
            int oz = origin.getZ();
            double margin = this.boundsMargin;
            if (!this.boxVisible(
                ox + (Float.intBitsToFloat(hot[base + H_MIN_X]) - margin) - this.cameraX,
                oy + (Float.intBitsToFloat(hot[base + H_MIN_Y]) - margin) - this.cameraY,
                oz + (Float.intBitsToFloat(hot[base + H_MIN_Z]) - margin) - this.cameraZ,
                ox + (Float.intBitsToFloat(hot[base + H_MAX_X]) + margin) - this.cameraX,
                oy + (Float.intBitsToFloat(hot[base + H_MAX_Y]) + margin) - this.cameraY,
                oz + (Float.intBitsToFloat(hot[base + H_MAX_Z]) + margin) - this.cameraZ
            )) {
                if (this.stats) {
                    TerrainStats.add(TerrainStats.FRUSTUM_SKIPPABLE, hot[base + H_QUADS]);
                    TerrainStats.add(TerrainStats.FRUSTUM_SKIPPABLE_LAYERS, 1L);
                }

                if (this.frustumCull) {
                    if (this.stats) {
                        TerrainStats.add(TerrainStats.FRUSTUM_SKIPPED, hot[base + H_QUADS]);
                    }

                    return true;
                }
            }
        }

        return false;
    }

    private int addLayer(
        final SectionRenderDispatcher.RenderSection section,
        final SectionMesh.@Nullable SectionDraw[] draws,
        final int[] hot,
        final int l,
        final @Nullable GpuBuffer vertexHeap,
        final @Nullable GpuBuffer indexHeap,
        final int sectionInfoIndex
    ) {
        if (this.culledByFrustum(section, hot, l * HOT_STRIDE) || vertexHeap == null) {
            return sectionInfoIndex;
        }

        return this.addLayerUnculled(section, draws, hot, l, vertexHeap, indexHeap, sectionInfoIndex);
    }

    /** {@link TerrainMeshInfo#visibleMask} on the descriptor's plane copies (same comparisons, same result). */
    private static int visibleMask(final int[] hot, final int base, final double rx, final double ry, final double rz, final double eps) {
        int vis = 1 << TerrainMeshInfo.SLOT_UNASSIGNED;
        if (ry < Float.intBitsToFloat(hot[base + H_PLANE_DOWN]) + eps) {
            vis |= 1 << TerrainMeshInfo.SLOT_DOWN;
        }

        if (rz < Float.intBitsToFloat(hot[base + H_PLANE_NORTH]) + eps) {
            vis |= 1 << TerrainMeshInfo.SLOT_NORTH;
        }

        if (rx > Float.intBitsToFloat(hot[base + H_PLANE_EAST]) - eps) {
            vis |= 1 << TerrainMeshInfo.SLOT_EAST;
        }

        if (ry > Float.intBitsToFloat(hot[base + H_PLANE_UP]) - eps) {
            vis |= 1 << TerrainMeshInfo.SLOT_UP;
        }

        if (rz > Float.intBitsToFloat(hot[base + H_PLANE_SOUTH]) - eps) {
            vis |= 1 << TerrainMeshInfo.SLOT_SOUTH;
        }

        if (rx < Float.intBitsToFloat(hot[base + H_PLANE_WEST]) + eps) {
            vis |= 1 << TerrainMeshInfo.SLOT_WEST;
        }

        return vis;
    }

    /** {@link TerrainRuns#computeRuns} with the descriptor's one-entry cache keyed by {@code (mask, bridge)}. */
    private static int runs(final int[] hot, final int base, final TerrainMeshInfo info, final int mask, final int bridge, final int[] out) {
        int at = base + H_RUNS;
        if (hot[base + H_RUN_MASK] != mask || hot[base + H_RUN_BRIDGE] != bridge) {
            int runs = TerrainRuns.computeRuns(info.slotQuadStartArray(), mask, bridge, out);
            System.arraycopy(out, 0, hot, at, 2 * runs);
            hot[base + H_RUN_COUNT] = runs;
            hot[base + H_RUN_BRIDGE] = bridge;
            hot[base + H_RUN_MASK] = mask;
            return runs;
        }

        int runs = hot[base + H_RUN_COUNT];
        System.arraycopy(hot, at, out, 0, 2 * runs);
        return runs;
    }

    private int addLayerUnculled(
        final SectionRenderDispatcher.RenderSection section,
        final SectionMesh.@Nullable SectionDraw[] draws,
        final int[] hot,
        final int l,
        final GpuBuffer vertexHeap,
        final @Nullable GpuBuffer indexHeap,
        int sectionInfoIndex
    ) {
        int base = l * HOT_STRIDE;
        int flags = hot[base + H_FLAGS];
        boolean customIndex = (flags & F_CUSTOM_INDEX) != 0;
        if (customIndex && indexHeap == null) {
            return sectionInfoIndex;
        }

        BlockPos origin = section.getRenderOrigin();
        int ox = origin.getX();
        int oy = origin.getY();
        int oz = origin.getZ();
        if (sectionInfoIndex == -1) {
            sectionInfoIndex = this.sectionCount;
            this.addSectionInfo(ox, oy, oz, this.nearFullVisibility ? 1.0F : section.getVisibility(this.now, this.fadeDuration));
            if (this.stats) {
                TerrainStats.add(TerrainStats.SECTIONS, 1L);
            }
        }

        int indexCount = hot[base + H_INDEX_COUNT];
        int firstIndex = 0;
        GpuBuffer indexBuffer;
        IndexType indexType;
        if (!customIndex) {
            if (indexCount > this.largestIndexCount) {
                this.largestIndexCount = indexCount;
            }

            indexBuffer = null;
            indexType = null;
        } else {
            indexBuffer = indexHeap;
            indexType = INDEX_TYPES[(flags >>> INDEX_TYPE_SHIFT & 0xFF) - 1];
            firstIndex = (int)(offset(hot, base + H_INDEX_OFFSET) / indexType.bytes);
        }

        boolean compact = (flags & F_COMPACT) != 0;
        int baseVertex = (int)(offset(hot, base + H_VERTEX_OFFSET) / (compact ? TerrainCompact.STRIDE : this.vertexSize[l]));
        boolean translucentOrdered = this.layerTranslucentOrdered[l];
        boolean hasInfo = (flags & F_INFO) != 0;
        int visibleMask = -1;
        if (hasInfo && this.facingEye && (this.layerBucketCull[l] || this.stats)) {
            visibleMask = visibleMask(hot, base, this.eyeX - ox, this.eyeY - oy, this.eyeZ - oz, TerrainRuns.EPS);
        }

        // Round 12 (a): the S1 run path of a 28-byte SOLID / CUTOUT layer with clusters goes to the GPU cull instead.
        if (this.clusterPath && !customIndex && !compact && this.layerBucketCull[l] && hasInfo && (flags & F_BUCKETED) != 0 && visibleMask != -1) {
            int[] clusters = draws[l].info().clusters();
            if (clusters != null) {
                int quadsDrawn = this.clusterGroup(l, vertexHeap).add(clusters, visibleMask, baseVertex, sectionInfoIndex);
                if (this.stats) {
                    TerrainStats.add(l, quadsDrawn);
                }
                return sectionInfoIndex;
            }
        }

        // Round 12 Part B (prototype): the layer's greedy copy instead of its vanilla quads (same allocation, after them).
        TerrainMeshInfo greedyInfo = this.greedyDraw && !customIndex && !compact && hasInfo && !translucentOrdered ? draws[l].info().greedy() : null;
        Group group = translucentOrdered
            ? this.translucentGroup(l, vertexHeap, indexBuffer, indexType, compact)
            : this.cachedGroup(l, vertexHeap, indexBuffer, indexType, compact, greedyInfo != null);
        int recordsBefore = group.recordCount;

        int quadsDrawn;
        if (customIndex) {
            int droppedMask = hot[base + H_TF_MASK];
            if (droppedMask != 0 && this.facingEye && TerrainToggles.translucentFacingDraw && !TerrainToggles.drawListVerify
                && (visibleMask(hot, base, this.eyeX - ox, this.eyeY - oy, this.eyeZ - oz, TerrainRuns.EPS) & droppedMask) == 0) {
                // R9-1: every dropped bucket still faces away from this frame's eye: the short list (after the full one).
                int kept = hot[base + H_TF_KEPT];
                if (kept > 0) {
                    group.add(kept, 1, firstIndex + indexCount, baseVertex, sectionInfoIndex);
                }

                quadsDrawn = kept / 6;
                if (this.stats) {
                    TerrainStats.add(TerrainStats.FACING_SKIPPED, (indexCount - kept) / 6);
                }
            } else {
                group.add(indexCount, 1, firstIndex, baseVertex, sectionInfoIndex);
                quadsDrawn = indexCount / 6;
            }
        } else if (this.layerBucketCull[l] && hasInfo && (flags & F_BUCKETED) != 0 && visibleMask != -1) {
            // S1: only the runs of buckets that can face the eye (index pattern from index 0, per run).
            TerrainMeshInfo info = draws[l].info();
            int[] scratch = this.runScratch;
            int first = 0;
            int runs;
            if (greedyInfo != null) {
                runs = TerrainRuns.computeRuns(greedyInfo.slotQuadStartArray(), visibleMask, this.bridge, scratch);
                first = info.greedyFirstQuad;
                info = greedyInfo;
            } else {
                runs = runs(hot, base, info, visibleMask, this.bridge, scratch);
            }
            quadsDrawn = 0;

            for (int r = 0; r < runs; r++) {
                int count = scratch[2 * r + 1];
                emitRange(group, first + scratch[2 * r], count, baseVertex, sectionInfoIndex, this.maxQuadsPerDraw, this.splitProbe);
                quadsDrawn += count;
            }

            if (this.stats) {
                int wanted = info.quadCount() - info.quadsOutside(visibleMask);
                TerrainStats.add(TerrainStats.FACING_SKIPPED, info.quadCount() - quadsDrawn);
                TerrainStats.add(TerrainStats.BRIDGED_QUADS, quadsDrawn - wanted);
            }
        } else if (greedyInfo != null) {
            quadsDrawn = greedyInfo.quadCount();
            emitRange(group, draws[l].info().greedyFirstQuad, quadsDrawn, baseVertex, sectionInfoIndex, this.maxQuadsPerDraw, this.splitProbe);
        } else {
            quadsDrawn = indexCount / 6;
            emitRange(group, 0, quadsDrawn, baseVertex, sectionInfoIndex, this.maxQuadsPerDraw, LAYERS[l].translucent() ? 1 : this.splitProbe);
            if (translucentOrdered && group.recordCount - recordsBefore > 1) {
                // Translucent draw lists are replayed in reverse; keep the parts of one split draw in order.
                group.reverseFrom(recordsBefore);
            }
        }

        if (this.stats) {
            TerrainStats.add(l, quadsDrawn);
            TerrainStats.add(TerrainStats.RECORDS, group.recordCount - recordsBefore);
            if (visibleMask != -1) {
                TerrainStats.add(TerrainStats.FACING_SKIPPABLE_SOLID + l, draws[l].info().quadsOutside(visibleMask));
            }

            if (!LAYERS[l].translucent()) {
                TerrainStats.add(TerrainStats.OPAQUE_LAYERS, 1L);
            }
        }

        return sectionInfoIndex;
    }

    /** Same records as {@link TerrainRuns#emitRange}. */
    private static void emitRange(
        final Group group, final int firstQuad, final int quads, final int baseVertex, final int baseInstance, final int maxQuadsPerDraw, final int splitProbe
    ) {
        int done = 0;

        while (done < quads) {
            int chunk = Math.min(maxQuadsPerDraw, quads - done);
            int parts = Math.max(1, Math.min(splitProbe, chunk));
            if (parts == 1) {
                group.add(6 * chunk, 1, 0, baseVertex + 4 * (firstQuad + done), baseInstance);
            } else {
                int partStart = 0;

                for (int part = 0; part < parts; part++) {
                    int partEnd = (int)((long)chunk * (part + 1) / parts);
                    group.add(6 * (partEnd - partStart), 1, 0, baseVertex + 4 * (firstQuad + done + partStart), baseInstance);
                    partStart = partEnd;
                }
            }

            done += chunk;
        }
    }

    /** Vanilla's {@code drawGroupCache} for a layer: first group with this key, created in first-use order. */
    private Group cachedGroup(
        final int l, final GpuBuffer vertexBuffer, final @Nullable GpuBuffer indexBuffer, final @Nullable IndexType indexType, final boolean compact,
        final boolean greedy
    ) {
        Group hit = this.lastHit[l];
        if (hit != null && hit.vertexBuffer == vertexBuffer && hit.indexBuffer == indexBuffer && hit.indexType == indexType && hit.compact == compact
            && hit.greedy == greedy) {
            return hit;
        }

        Group[] groups = this.layerGroups[l];
        int n = this.layerGroupCount[l];

        for (int i = 0; i < n; i++) {
            Group g = groups[i];
            if (g.vertexBuffer == vertexBuffer && g.indexBuffer == indexBuffer && g.indexType == indexType && g.compact == compact && g.greedy == greedy) {
                this.lastHit[l] = g;
                return g;
            }
        }

        Group g = this.newGroup(l, vertexBuffer, indexBuffer, indexType, compact);
        g.greedy = greedy;
        this.lastHit[l] = g;
        return g;
    }

    /**
     * Vanilla's translucent path with {@code respectTranslucentOrder}: consecutive section-layers with the same key share
     * the layer's last group, any other key starts a new group (the key of the previous translucent section-layer is
     * what vanilla compares against, in {@code lastTransparentGroupHash}).
     */
    private Group translucentGroup(
        final int l, final GpuBuffer vertexBuffer, final @Nullable GpuBuffer indexBuffer, final @Nullable IndexType indexType, final boolean compact
    ) {
        boolean same = this.haveLastTranslucent
            && this.lastTranslucentVertex == vertexBuffer
            && this.lastTranslucentIndex == indexBuffer
            && this.lastTranslucentIndexType == indexType
            && this.lastTranslucentCompact == compact;
        this.haveLastTranslucent = true;
        this.lastTranslucentVertex = vertexBuffer;
        this.lastTranslucentIndex = indexBuffer;
        this.lastTranslucentIndexType = indexType;
        this.lastTranslucentCompact = compact;
        int n = this.layerGroupCount[l];
        if (same && n > 0) {
            return this.layerGroups[l][n - 1];
        }

        return this.newGroup(l, vertexBuffer, indexBuffer, indexType, compact);
    }

    private Group newGroup(
        final int l, final GpuBuffer vertexBuffer, final @Nullable GpuBuffer indexBuffer, final @Nullable IndexType indexType, final boolean compact
    ) {
        if (this.poolUsed == this.pool.length) {
            this.pool = Arrays.copyOf(this.pool, this.pool.length * 2);
        }

        Group g = this.pool[this.poolUsed];
        if (g == null) {
            g = new Group();
            this.pool[this.poolUsed] = g;
        }

        this.poolUsed++;
        g.layer = l;
        g.compact = compact;
        g.greedy = false;
        g.vertexBuffer = vertexBuffer;
        g.indexBuffer = indexBuffer;
        g.indexType = indexType;
        g.vertexSlice = vertexBuffer.slice();
        g.indexSlice = indexBuffer != null ? indexBuffer.slice() : null;
        g.recordCount = 0;
        int n = this.layerGroupCount[l];
        if (n == this.layerGroups[l].length) {
            this.layerGroups[l] = Arrays.copyOf(this.layerGroups[l], n * 2);
        }

        this.layerGroups[l][n] = g;
        this.layerGroupCount[l] = n + 1;
        return g;
    }

    private void addSectionInfo(final int x, final int y, final int z, final float visibility) {
        int at = SECTION_INTS * this.sectionCount;
        if (at + SECTION_INTS > this.sectionData.length) {
            this.sectionData = Arrays.copyOf(this.sectionData, this.sectionData.length * 2);
        }

        int[] d = this.sectionData;
        d[at] = x;
        d[at + 1] = y;
        d[at + 2] = z;
        d[at + 3] = Float.floatToRawIntBits(visibility);
        this.sectionCount++;
    }

    private void finishOutputOrder() {
        for (int l = 0; l < LAYER_COUNT; l++) {
            if (this.layerSectionSort[l]) {
                Group[] groups = this.layerGroups[l];

                for (int i = 0, n = this.layerGroupCount[l]; i < n; i++) {
                    groups[i].sortBySectionDistance(this.sectionData, this.cameraX, this.cameraY, this.cameraZ);
                }
            }
        }

        for (TerrainClusters.Group g : this.clusterGroups) {
            if (this.layerSectionSort[g.layer]) {
                g.sortBySectionDistance(this.sectionData, this.cameraX, this.cameraY, this.cameraZ);
            }
        }

        int total = this.poolUsed;
        if (this.outputGroups.length < total) {
            this.outputGroups = new Group[Math.max(total, this.outputGroups.length * 2)];
            this.outputCounts = new int[this.outputGroups.length];
        }

        int k = 0;

        for (int l = 0; l < LAYER_COUNT; l++) {
            Group[] groups = this.layerGroups[l];
            int n = this.layerGroupCount[l];
            boolean reverse = this.layerTranslucentOrdered[l];

            for (int i = 0; i < n; i++) {
                Group g = groups[reverse ? n - 1 - i : i];
                this.outputGroups[k] = g;
                this.outputCounts[k] = g.recordCount;
                k++;
            }
        }

        this.outputCount = k;
    }

    /** Record bytes for the mapped indirect ring, in output order (translucent groups and their records reversed). */
    private void writeRecords(final ByteBuffer target) {
        IntBuffer out = target.asIntBuffer();

        for (int k = 0; k < this.outputCount; k++) {
            Group g = this.outputGroups[k];
            if (this.layerTranslucentOrdered[g.layer]) {
                for (int r = g.recordCount - 1; r >= 0; r--) {
                    out.put(g.records, RECORD_INTS * r, RECORD_INTS);
                }
            } else {
                out.put(g.records, 0, RECORD_INTS * g.recordCount);
            }
        }
    }

    private void writeSections(final ByteBuffer target) {
        target.asIntBuffer().put(this.sectionData, 0, SECTION_INTS * this.sectionCount);
    }

    public DynamicGpuData.RawWriter recordWriter() {
        return this.recordWriter;
    }

    public DynamicGpuData.RawWriter sectionWriter() {
        return this.sectionWriter;
    }

    /** Groups in output order (the order of the indirect slices). */
    public int outputGroupCount() {
        return this.outputCount;
    }

    public Group outputGroup(final int index) {
        return this.outputGroups[index];
    }

    /** Record count of each output group ({@link #outputGroupCount} entries are valid). */
    public int[] outputCounts() {
        return this.outputCounts;
    }

    public int sectionCount() {
        return this.sectionCount;
    }

    /** Round 12 (a): the section infos ({@link #SECTION_INTS} ints each, {@link #sectionCount} valid). */
    public int[] sectionDataArray() {
        return this.sectionData;
    }

    /** Field {@code field} (0..3: x, y, z, visibility bits) of section info {@code index}. */
    public int sectionField(final int index, final int field) {
        return this.sectionData[SECTION_INTS * index + field];
    }

    /** Output record {@code record} of output group {@code k}, in the order it is written. */
    public int outputRecordField(final int k, final int record, final int field) {
        Group g = this.outputGroups[k];
        int r = this.layerTranslucentOrdered[g.layer] ? g.recordCount - 1 - record : record;
        return g.records[RECORD_INTS * r + field];
    }
}
