package net.minecraft.client.renderer.chunk;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.backend.metal.MetalClusterCull;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

/**
 * Round 12 (a) (docs/metal/CONTRACT.md 16.R12-A): GPU-driven culling of SOLID / CUTOUT terrain in 64-quad clusters, drawn
 * with one indirect draw per (layer, vertex heap) and vertex pulling. Exact by construction (it only removes quads that
 * produce no fragment) and order-preserving. Meant as the base of stage 3 (LOD clusters) and stage 4 (shadow-view culls):
 * the kernel takes a view (planes, eye) per call, so more views are more calls over the same cluster lists.
 *
 * <p><b>Mesh time</b> ({@link #compute}, from {@link TerrainMeshInfo#analyze} unless {@code -Dmc.terrain.clusters=false}): each
 * facing bucket of a reordered layer (layout A) is cut into runs of at most 64 quads; per cluster 3 ints: {@code quadStart |
 * (quads - 1) << 16 | slot << 22 | unusable << 25}, then the section-relative bounds of its vertices in 1/16 block, rounded
 * outward, biased by 512, 10 bits per axis (min, max). A cluster whose bounds do not fit (or are not finite) is flagged
 * unusable and never culled.
 *
 * <p><b>Per frame</b> ({@link TerrainDrawList}): a SOLID / CUTOUT section-layer with clusters whose S1 run path would be taken
 * instead appends the clusters of its CPU-visible buckets (the same S1 mask) to its {@link Group} (layer, vertex heap) as
 * 16-byte items (first vertex in the heap, section info index | quads - 1 | slot | unusable, min, max) in draw-list order
 * (CUTOUT: with {@link TerrainToggles#sortCutoutSections} the sections are re-sorted front-to-back as the records are).
 * {@link Frame#dispatch} then writes the frame's section table ((origin - camera), (eye - origin), from doubles) and the
 * items into a CPU-mapped ring slot and runs {@code mcmtl_ext_cluster_cull} (native/src/mcmetal_clusters.m): per cluster the
 * frustum test (the exact frame frustum of S2, box widened by {@link #BOUNDS_MARGIN}) and the facing test (the S1 test on the
 * cluster's own extreme plane, margin {@link TerrainRuns#EPS}), then a prefix sum and the compacted quad list
 * ({@code cluster << 6 | quad}) plus the draw's arguments. {@code ChunkSectionsToRender} draws each group with
 * {@code RenderPipelines.*_TERRAIN_CLUSTER} (the hand-written pull vertex function, {@code TerrainVertexMsl#clusterVertex})
 * and a 32-bit quad index buffer, after the layer's per-record groups.
 *
 * <p>Switches: {@code -Dmc.terrain.clusters} (default true since r12) builds the cluster data and the two cluster pipelines (startup; false = the r11
 * path exactly, nothing built); {@link #enabled} ({@code -Dmc.terrain.clusters.use}, default true when built; abab-switchable,
 * read once per frame) picks the cluster draw or the per-record draw for the same meshes. {@code -Dmc.terrain.clusters.stats=N}
 * logs every N s the clusters / quads submitted and the quads the GPU kept (read back from the arguments of finished frames).
 * Only on Metal with the extension: otherwise the frame keeps the per-record path.
 */
public final class TerrainClusters {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final boolean BUILD = flag("mc.terrain.clusters", true);
    public static volatile boolean enabled = BUILD && flag("mc.terrain.clusters.use", true);
    public static final int STATS_SECONDS = Math.max(0, Integer.getInteger("mc.terrain.clusters.stats", 0));
    /** Diagnostics ({@code -Dmc.terrain.clusters.cullMask}): GPU tests in use, bit 0 frustum, bit 1 facing (default both). */
    public static final int CULL_MASK = Integer.getInteger("mc.terrain.clusters.cullMask", 3);
    public static final int MAX_QUADS = 64;
    public static final int CLUSTER_INTS = 3;
    public static final int ITEM_INTS = 4;
    /** Box widening of the GPU frustum test, in blocks (covers float rounding of camera-relative coordinates, ~1e-5). */
    public static final float BOUNDS_MARGIN = 1.0F / 16.0F;
    private static final int RING = 4;
    private static final int SECTION_BYTES = 32;

    private TerrainClusters() {
    }

    /** Mesh time: the clusters of a reordered layer (see the class doc); {@code slotStart} as TerrainMeshInfo's. */
    static int[] compute(final ByteBuffer buffer, final int base, final int quadBytes, final int stride, final int positionOffset, final int[] slotStart) {
        int clusters = 0;
        for (int slot = 0; slot < TerrainMeshInfo.SLOT_COUNT; slot++) {
            int n = slotStart[slot + 1] - slotStart[slot];
            clusters += (n + MAX_QUADS - 1) / MAX_QUADS;
        }

        int[] out = new int[clusters * CLUSTER_INTS];
        int k = 0;
        for (int slot = 0; slot < TerrainMeshInfo.SLOT_COUNT; slot++) {
            for (int start = slotStart[slot]; start < slotStart[slot + 1]; start += MAX_QUADS) {
                int count = Math.min(MAX_QUADS, slotStart[slot + 1] - start);
                float minX = Float.POSITIVE_INFINITY;
                float minY = Float.POSITIVE_INFINITY;
                float minZ = Float.POSITIVE_INFINITY;
                float maxX = Float.NEGATIVE_INFINITY;
                float maxY = Float.NEGATIVE_INFINITY;
                float maxZ = Float.NEGATIVE_INFINITY;
                for (int v = 0; v < 4 * count; v++) {
                    int at = base + start * quadBytes + v * stride + positionOffset;
                    float x = buffer.getFloat(at);
                    float y = buffer.getFloat(at + 4);
                    float z = buffer.getFloat(at + 8);
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    minZ = Math.min(minZ, z);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                    maxZ = Math.max(maxZ, z);
                }

                int lo = pack(minX, minY, minZ, false);
                int hi = pack(maxX, maxY, maxZ, true);
                boolean unusable = lo == -1 || hi == -1 || start > 0xFFFF;
                out[k++] = (start & 0xFFFF) | (count - 1) << 16 | slot << 22 | (unusable ? 1 : 0) << 25;
                out[k++] = unusable ? 0 : lo;
                out[k++] = unusable ? 0 : hi;
            }
        }

        return out;
    }

    /** 10 bits per axis in 1/16 block biased by 512 (rounded down for min, up for max); -1 if not finite or out of range. */
    static int pack(final float x, final float y, final float z, final boolean up) {
        int qx = quantize(x, up);
        int qy = quantize(y, up);
        int qz = quantize(z, up);
        return qx < 0 || qy < 0 || qz < 0 ? -1 : qx | qy << 10 | qz << 20;
    }

    private static int quantize(final float v, final boolean up) {
        if (!Float.isFinite(v)) {
            return -1;
        }
        double s = (double)v * 16.0;
        long q = (long)(up ? Math.ceil(s) : Math.floor(s)) + 512L;
        return q < 0L || q > 1023L ? -1 : (int)q;
    }

    /** Decoded bound (section-relative blocks) of a packed axis value, as the kernel computes it. */
    static float unpackAxis(final int packed, final int axis) {
        return ((packed >>> (10 * axis) & 1023) - 512) * 0.0625F;
    }

    /** One per-frame cluster list: the clusters of one (layer, vertex heap). Render thread. */
    public static final class Group {
        int layer;
        GpuBuffer vertexBuffer;
        GpuBufferSlice vertexSlice;
        int[] items = new int[ITEM_INTS * 256];
        int count;
        long quads;
        /** Per appended section-layer: first item, section info index (for the front-to-back section sort). */
        int[] ranges = new int[2 * 64];
        int rangeCount;
        long @Nullable [] sortKeys;
        int @Nullable [] sortedItems;

        public int layer() {
            return this.layer;
        }

        public int count() {
            return this.count;
        }

        /**
         * Appends the clusters of the slots in {@code visibleMask} (S1) of one section-layer; returns the quads appended.
         * {@code baseVertex}: the layer's first vertex in the heap.
         */
        public int add(final int[] clusters, final int visibleMask, final int baseVertex, final int sectionInfoIndex) {
            int added = 0;
            if (2 * this.rangeCount + 2 > this.ranges.length) {
                this.ranges = Arrays.copyOf(this.ranges, this.ranges.length * 2);
            }
            this.ranges[2 * this.rangeCount] = this.count;
            this.ranges[2 * this.rangeCount + 1] = sectionInfoIndex;
            this.rangeCount++;
            for (int i = 0; i < clusters.length; i += CLUSTER_INTS) {
                int c0 = clusters[i];
                int slot = c0 >>> 22 & 7;
                if ((visibleMask & 1 << slot) == 0) {
                    continue;
                }
                int at = ITEM_INTS * this.count;
                if (at + ITEM_INTS > this.items.length) {
                    this.items = Arrays.copyOf(this.items, this.items.length * 2);
                }
                int quads = (c0 >>> 16 & 63) + 1;
                this.items[at] = baseVertex + 4 * (c0 & 0xFFFF);
                this.items[at + 1] = sectionInfoIndex | (quads - 1) << 20 | slot << 26 | (c0 >>> 25 & 1) << 29;
                this.items[at + 2] = clusters[i + 1];
                this.items[at + 3] = clusters[i + 2];
                this.count++;
                added += quads;
            }
            this.quads += added;
            return added;
        }

        /** Stable front-to-back sort of the appended section-layers (the order of TerrainDrawList.Group#sortBySectionDistance). */
        void sortBySectionDistance(final int[] sectionData, final double cameraX, final double cameraY, final double cameraZ) {
            int n = this.rangeCount;
            if (n < 2) {
                return;
            }
            long[] keys = this.sortKeys;
            if (keys == null || keys.length < n) {
                keys = new long[Math.max(n, 64)];
                this.sortKeys = keys;
            }
            boolean sorted = true;
            long previous = Long.MIN_VALUE;
            for (int r = 0; r < n; r++) {
                int s = TerrainDrawList.SECTION_INTS * this.ranges[2 * r + 1];
                long key = (long)TerrainDrawList.sectionDistanceKey(sectionData[s], sectionData[s + 1], sectionData[s + 2], cameraX, cameraY, cameraZ) << 32 | r;
                keys[r] = key;
                sorted &= key > previous;
                previous = key;
            }
            if (sorted) {
                return;
            }
            Arrays.sort(keys, 0, n);
            int[] out = this.sortedItems;
            if (out == null || out.length < this.items.length) {
                out = new int[this.items.length];
            }
            int k = 0;
            for (int i = 0; i < n; i++) {
                int r = (int)keys[i];
                int from = this.ranges[2 * r];
                int to = r + 1 < n ? this.ranges[2 * r + 2] : this.count;
                System.arraycopy(this.items, ITEM_INTS * from, out, ITEM_INTS * k, ITEM_INTS * (to - from));
                k += to - from;
            }
            this.sortedItems = this.items;
            this.items = out;
        }

        public void reset(final int layer, final GpuBuffer vertexBuffer) {
            this.layer = layer;
            this.vertexBuffer = vertexBuffer;
            this.vertexSlice = vertexBuffer.slice();
            this.count = 0;
            this.quads = 0L;
            this.rangeCount = 0;
        }
    }

    /** One cluster draw of a frame (ChunkSectionsToRender): pipeline class = layer. */
    public record Draw(int layer, GpuBufferSlice vertexBuffer, GpuBufferSlice quads, GpuBufferSlice items, GpuBufferSlice args, GpuBuffer indexBuffer) {
    }

    /** GPU resources and the per-frame dispatch (LevelRenderer owns one). Render thread. */
    public static final class Frame implements AutoCloseable {
        private final Slot[] slots = new Slot[RING];
        private int frame;
        private @Nullable GpuBuffer indexBuffer;
        private long indexQuads;
        private final List<GpuBuffer> retired = new ArrayList<>();
        private final List<Integer> retiredAt = new ArrayList<>();
        private boolean failed;
        private long jobs;
        private long view;
        private long statsStart;
        private long statsFrames;
        private long statsClusters;
        private long statsQuads;
        private long statsKept;
        private long statsKeptFrames;

        private static final class Slot {
            @Nullable GpuBuffer input;
            long inputBytes;
            final List<Job> jobs = new ArrayList<>();
            int usedJobs;
            int frame = -1;
        }

        private static final class Job {
            GpuBuffer outItems;
            long clusterCapacity;
            GpuBuffer quads;
            long quadCapacity;
            GpuBuffer args;
            GpuBuffer scratch;
            boolean pending;
            long submittedQuads;
        }

        private boolean probed;

        /**
         * True if the cluster path can run: the device is Metal with the extension (kernels compiled) and both cluster pipelines
         * got the pulling vertex function. Probed once (a 16-byte buffer); the pipeline check every call (a reload can change it).
         */
        public boolean usable() {
            if (!this.probed) {
                this.probed = true;
                GpuBuffer probe = RenderSystem.getDevice().createBuffer(() -> "Terrain cluster probe", GpuBuffer.USAGE_MAP_WRITE, 16L);
                try {
                    this.failed = !MetalClusterCull.isMetal(probe) || !MetalClusterCull.prepare(probe);
                } finally {
                    probe.close();
                }
                if (this.failed) {
                    LOGGER.info("Terrain clusters: not available on this device (Metal with mcmtl_ext_cluster_cull only), per-record draws");
                }
            }
            return !this.failed && com.mojang.renderpearl.backend.metal.TerrainVertexMsl.clusterPipelinesReady();
        }

        /**
         * Culls {@code groups} for this frame: returns one {@link Draw} per non-empty group, or null if the cluster path is not
         * available (not Metal, extension missing or failed): the caller then draws those groups' section-layers per record
         * (TerrainDrawList keeps nothing else for them, so the caller must rebuild: see LevelRenderer).
         */
        public @Nullable List<Draw> dispatch(final List<Group> groups, final int sectionCount, final int[] sectionData, final double[] planes,
            final boolean frustum, final double cameraX, final double cameraY, final double cameraZ, final double eyeX, final double eyeY, final double eyeZ) {
            if (this.failed) {
                return null;
            }
            long itemsBytes = 0L;
            int jobCount = 0;
            for (Group g : groups) {
                if (g.count > 0) {
                    itemsBytes += 16L * g.count;
                    jobCount++;
                }
            }
            List<Draw> draws = new ArrayList<>(jobCount);
            if (jobCount == 0) {
                return draws;
            }
            this.frame++;
            this.releaseRetired();
            Slot slot = this.slots[this.frame % RING];
            if (slot == null) {
                slot = new Slot();
                this.slots[this.frame % RING] = slot;
            }
            this.collectStats(slot);
            long sectionsBytes = (long)SECTION_BYTES * Math.max(1, sectionCount);
            long need = sectionsBytes + itemsBytes;
            if (slot.input == null || slot.inputBytes < need) {
                this.retire(slot.input);
                slot.inputBytes = Math.max(need + need / 2, 1L << 20);
                slot.input = RenderSystem.getDevice().createBuffer(() -> "Terrain cluster input", GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_COPY_DST, slot.inputBytes);
                if (!MetalClusterCull.isMetal(slot.input) || !MetalClusterCull.prepare(slot.input)) {
                    this.failed = true;
                    LOGGER.warn("Terrain clusters: not available on this device, per-record draws");
                    return null;
                }
            }
            if (this.jobs == 0L) {
                this.jobs = MemoryUtil.nmemAlloc(4096L * MetalClusterCull.JOB_SIZE);
                this.view = MemoryUtil.nmemCalloc(1L, MetalClusterCull.VIEW_SIZE + 4L);
            }
            long base = MetalClusterCull.contents(slot.input);
            // Section table: (origin - camera), (eye - origin), in double, rounded once.
            for (int s = 0; s < sectionCount; s++) {
                int x = sectionData[TerrainDrawList.SECTION_INTS * s];
                int y = sectionData[TerrainDrawList.SECTION_INTS * s + 1];
                int z = sectionData[TerrainDrawList.SECTION_INTS * s + 2];
                long at = base + (long)SECTION_BYTES * s;
                MemoryUtil.memPutFloat(at, (float)(x - cameraX));
                MemoryUtil.memPutFloat(at + 4, (float)(y - cameraY));
                MemoryUtil.memPutFloat(at + 8, (float)(z - cameraZ));
                MemoryUtil.memPutFloat(at + 12, 0.0F);
                MemoryUtil.memPutFloat(at + 16, (float)(eyeX - x));
                MemoryUtil.memPutFloat(at + 20, (float)(eyeY - y));
                MemoryUtil.memPutFloat(at + 24, (float)(eyeZ - z));
                MemoryUtil.memPutFloat(at + 28, 0.0F);
            }
            for (int i = 0; i < 20; i++) {
                MemoryUtil.memPutFloat(this.view + 4L * i, (float)planes[i]);
            }
            MemoryUtil.memPutFloat(this.view + 80, (float)TerrainRuns.EPS);
            MemoryUtil.memPutInt(this.view + 84, ((frustum ? 1 : 0) | 2) & CULL_MASK);
            MemoryUtil.memPutFloat(this.view + 88, BOUNDS_MARGIN);
            long itemsAt = sectionsBytes;
            int j = 0;
            long maxQuads = 0L;
            for (Group g : groups) {
                if (g.count == 0) {
                    continue;
                }
                MemoryUtil.memIntBuffer(base + itemsAt, ITEM_INTS * g.count).put(g.items, 0, ITEM_INTS * g.count);
                Job job = this.job(slot, j, g.count, g.quads);
                MetalClusterCull.writeJob(this.jobs, j, slot.input, itemsAt, slot.input, 0L, job.outItems, job.quads, job.args, 0L, job.scratch, g.count,
                    (int)g.quads);
                job.pending = true;
                job.submittedQuads = g.quads;
                draws.add(new Draw(g.layer, g.vertexSlice, job.quads.slice(), job.outItems.slice(), job.args.slice(0L, 20L), null));
                maxQuads = Math.max(maxQuads, g.quads);
                itemsAt += 16L * g.count;
                j++;
                this.statsClusters += g.count;
                this.statsQuads += g.quads;
            }
            slot.usedJobs = j;
            slot.frame = this.frame;
            this.statsFrames++;
            if (!MetalClusterCull.cull(slot.input, this.jobs, j, this.view)) {
                this.failed = true;
                return null;
            }
            GpuBuffer indices = this.indexBuffer(maxQuads);
            for (int i = 0; i < draws.size(); i++) {
                Draw d = draws.get(i);
                draws.set(i, new Draw(d.layer(), d.vertexBuffer(), d.quads(), d.items(), d.args(), indices));
            }
            return draws;
        }

        private Job job(final Slot slot, final int index, final int clusters, final long quads) {
            while (slot.jobs.size() <= index) {
                slot.jobs.add(new Job());
            }
            Job job = slot.jobs.get(index);
            if (job.outItems == null || job.clusterCapacity < clusters) {
                this.retire(job.outItems);
                this.retire(job.scratch);
                job.clusterCapacity = Math.max(1024L, clusters + clusters / 2L);
                job.outItems = RenderSystem.getDevice().createBuffer(() -> "Terrain cluster items", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, 8L * job.clusterCapacity);
                job.scratch = RenderSystem.getDevice().createBuffer(() -> "Terrain cluster scratch", GpuBuffer.USAGE_COPY_DST, 8L * job.clusterCapacity);
            }
            if (job.quads == null || job.quadCapacity < quads) {
                this.retire(job.quads);
                job.quadCapacity = Math.max(16384L, quads + quads / 2L);
                job.quads = RenderSystem.getDevice().createBuffer(() -> "Terrain cluster quads", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, 4L * job.quadCapacity);
            }
            if (job.args == null) {
                job.args = RenderSystem.getDevice().createBuffer(() -> "Terrain cluster args", GpuBuffer.USAGE_INDIRECT_PARAMETERS | GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, 256L);
            }
            return job;
        }

        private final @Nullable GpuBuffer[] indexBuffers = new GpuBuffer[TerrainToggles.QUAD_INDEX_PATTERNS.length];
        private final long[] indexBufferQuads = new long[TerrainToggles.QUAD_INDEX_PATTERNS.length];

        /**
         * 32-bit quad index buffer of at least {@code quads} quads in the triangle order of {@link TerrainToggles#quadIndexOrder}
         * (read once per frame; 0 = vanilla's {@code 4o + {0 1 2 2 3 0}}, round 12 (b)); one cached buffer per order.
         */
        private GpuBuffer indexBuffer(final long quads) {
            int order = Math.floorMod(TerrainToggles.quadIndexOrder, TerrainToggles.QUAD_INDEX_PATTERNS.length);
            this.indexBuffer = this.indexBuffers[order];
            this.indexQuads = this.indexBufferQuads[order];
            if (this.indexBuffer == null || this.indexQuads < quads) {
                this.retire(this.indexBuffer);
                int[] pattern = TerrainToggles.QUAD_INDEX_PATTERNS[order];
                long n = Math.max(65536L, Long.highestOneBit(Math.max(1L, quads)) << 1);
                ByteBuffer data = MemoryUtil.memAlloc((int)(n * 24L));
                try {
                    for (int q = 0; q < n; q++) {
                        int v = 4 * q;
                        for (int i = 0; i < 6; i++) {
                            data.putInt(v + pattern[i]);
                        }
                    }
                    data.flip();
                    this.indexBuffer = RenderSystem.getDevice().createBuffer(() -> "Terrain cluster quad index buffer", GpuBuffer.USAGE_INDEX, data);
                } finally {
                    MemoryUtil.memFree(data);
                }
                this.indexQuads = n;
                this.indexBuffers[order] = this.indexBuffer;
                this.indexBufferQuads[order] = n;
            }
            return this.indexBuffer;
        }

        /** A replaced buffer may still be read by a command buffer in flight: closed 8 frames later. */
        private void retire(final @Nullable GpuBuffer buffer) {
            if (buffer != null) {
                this.retired.add(buffer);
                this.retiredAt.add(this.frame);
            }
        }

        private void releaseRetired() {
            for (int i = this.retired.size() - 1; i >= 0; i--) {
                if (this.frame - this.retiredAt.get(i) > 8) {
                    this.retired.remove(i).close();
                    this.retiredAt.remove(i);
                }
            }
        }

        /** Quads the GPU kept for job {@code index} of the last dispatch (valid once that frame's command buffer completed). */
        public long keptQuads(final int index) {
            Slot slot = this.slots[this.frame % RING];
            return slot == null || index >= slot.usedJobs ? -1L : Integer.toUnsignedLong(MemoryUtil.memGetInt(MetalClusterCull.contents(slot.jobs.get(index).args))) / 6L;
        }

        /** Stats: the quads kept by the GPU in this slot's previous use (RING frames ago: finished). */
        private void collectStats(final Slot slot) {
            if (STATS_SECONDS == 0) {
                return;
            }
            for (int i = 0; i < slot.usedJobs && i < slot.jobs.size(); i++) {
                Job job = slot.jobs.get(i);
                if (job.pending) {
                    this.statsKept += Integer.toUnsignedLong(MemoryUtil.memGetInt(MetalClusterCull.contents(job.args))) / 6L;
                    job.pending = false;
                }
            }
            this.statsKeptFrames++;
            long now = System.nanoTime();
            if (this.statsStart == 0L) {
                this.statsStart = now;
            } else if (now - this.statsStart >= STATS_SECONDS * 1_000_000_000L && this.statsFrames > 0) {
                LOGGER.info("[terrain-clusters] per frame: {} clusters, {} quads submitted, {} quads kept by the GPU cull ({} %)",
                    this.statsClusters / this.statsFrames, this.statsQuads / this.statsFrames, this.statsKept / Math.max(1L, this.statsKeptFrames),
                    String.format(java.util.Locale.ROOT, "%.1f", 100.0 * this.statsKept / Math.max(1L, this.statsKeptFrames) / Math.max(1.0, (double)this.statsQuads / this.statsFrames)));
                this.statsStart = now;
                this.statsFrames = 0L;
                this.statsClusters = 0L;
                this.statsQuads = 0L;
                this.statsKept = 0L;
                this.statsKeptFrames = 0L;
            }
        }

        @Override
        public void close() {
            for (Slot slot : this.slots) {
                if (slot == null) {
                    continue;
                }
                if (slot.input != null) {
                    slot.input.close();
                }
                for (Job job : slot.jobs) {
                    for (GpuBuffer b : new GpuBuffer[]{job.outItems, job.quads, job.args, job.scratch}) {
                        if (b != null) {
                            b.close();
                        }
                    }
                }
            }
            Arrays.fill(this.slots, null);
            for (GpuBuffer b : this.retired) {
                b.close();
            }
            this.retired.clear();
            this.retiredAt.clear();
            for (int i = 0; i < this.indexBuffers.length; i++) {
                if (this.indexBuffers[i] != null) {
                    this.indexBuffers[i].close();
                    this.indexBuffers[i] = null;
                }
            }
            this.indexBuffer = null;
            if (this.jobs != 0L) {
                MemoryUtil.nmemFree(this.jobs);
                MemoryUtil.nmemFree(this.view);
                this.jobs = 0L;
                this.view = 0L;
            }
        }
    }

    private static boolean flag(final String name, final boolean defaultValue) {
        String value = System.getProperty(name);
        return value == null ? defaultValue : !value.equalsIgnoreCase("false") && !value.equals("0");
    }
}
