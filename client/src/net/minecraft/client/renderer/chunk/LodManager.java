package net.minecraft.client.renderer.chunk;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

/**
 * Stage 3: the LOD system of one client level (docs/stage3/LOD-DESIGN.md §4-§6). Owns the store, the worker threads (ingest,
 * downsample, mesh; minimum priority), the selection (screen-space error per node with this frame's focal length, recomputed
 * by the scheduler thread) and the GPU side ({@link LodGpu}, render thread).
 *
 * <p>Threads: render thread = capture of client chunks (copies only), uploads and drawing; {@code lod-sched} = selection,
 * scheduling, periodic flush, singleplayer scan; {@code lod-worker-N} = encode / read / downsample / mesh jobs from one
 * priority queue (nearest first). Nodes are shared through {@link #nodes} (volatile fields; the render thread only reads the
 * published selection and the mesh queue).
 */
final class LodManager {
    private final ClientLevel level;
    private final LodStore store;
    private final String storeLabel;
    /** Round 3: seed-based generation (LodGen) and its own store (regenerable, disk-capped); null until set up / when off. */
    private volatile @Nullable LodGen gen;
    private volatile @Nullable LodStore genStore;
    private volatile boolean genSetupDone;
    private final Path storeDir;
    final AtomicLong genJobs = new AtomicLong();
    final AtomicLong genOverlays = new AtomicLong();
    private final ConcurrentHashMap<Long, Node> nodes = new ConcurrentHashMap<>();
    private final PriorityBlockingQueue<Job> queue = new PriorityBlockingQueue<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final List<Thread> workers = new ArrayList<>();
    private final Thread scheduler;
    private volatile boolean closed;
    final LodGpu gpu;
    final ConcurrentLinkedQueue<Node> uploads = new ConcurrentLinkedQueue<>();
    /** Handoff-only experiment. Does not make the queued stamp an exact identity of later captured inputs. */
    static final boolean ATOMIC_MESH_HANDOFF = Lod.flag("mc.lod.atomicMeshHandoff", true);
    /** Round 8: node jobs are ranked again by the camera's current position when a worker takes them (flight leaves stale ranks). */
    static final boolean REPRIORITIZE = Lod.flag("mc.lod.reprio", true);
    /** Round 8: a split node whose finer subtree isn't drawable yet gets its own (coarse) data and mesh, so there is no hole meanwhile. */
    static final boolean COARSE_FALLBACK = Lod.flag("mc.lod.coarseFallback", true);
    /** Most levels a resident coarse node may sit above the wanted level to stand in for missing finer ones (default 1; 99 = any). */
    static final double STANDIN_GAP = Integer.getInteger("mc.lod.standInGap", 1);
    final @Nullable LodMeshHandoff<Node, List<LodMesher.Section>> meshHandoff = ATOMIC_MESH_HANDOFF ? new LodMeshHandoff<>() : null;
    private final Map<String, Holder<Biome>> biomeCache = new ConcurrentHashMap<>();
    private final Holder<Biome> fallbackBiome;
    private final SectionCompiler compiler;
    /** Round 1b: the mesher of the levels >= {@code Lod.AO_LEVEL}: flat lighting (no AO), so their faces merge exactly. */
    private final SectionCompiler flatCompiler;
    private final int blendRadius;
    private final int worldMinY;
    private final int worldHeight;
    private final boolean skyLight;
    // Render thread: chunks to capture once their light is on.
    private final Long2LongOpenHashMap pendingCapture = new Long2LongOpenHashMap();
    // Downsample debounce: parent key -> last child change (nanos).
    private final ConcurrentHashMap<Long, Long> dirtyParents = new ConcurrentHashMap<>();
    // Singleplayer scan state.
    private final LongOpenHashSet spChecked = new LongOpenHashSet();
    private final AtomicInteger spInFlight = new AtomicInteger();
    private volatile boolean spDone;
    private int spCenterX = Integer.MIN_VALUE;
    private int spCenterZ = Integer.MIN_VALUE;
    private int spRing;
    // Camera snapshot (render thread -> scheduler).
    private volatile @Nullable Snap snap;
    private volatile Selection selection = new Selection(new Node[0], new boolean[0], 0L);
    private long selectionVersion;
    // Stats.
    final AtomicLong captured = new AtomicLong();
    final AtomicLong spRead = new AtomicLong();
    final AtomicLong spAbsent = new AtomicLong();
    final AtomicLong downsampled = new AtomicLong();
    final AtomicLong meshed = new AtomicLong();
    /** Round 6: meshes taken from the on-disk mesh cache (LodMeshCache) instead of the mesher. */
    final AtomicLong cacheHits = new AtomicLong();
    final AtomicLong meshNanos = new AtomicLong();
    final AtomicLong encodeNanos = new AtomicLong();
    final AtomicLong readNanos = new AtomicLong();
    final AtomicLong downNanos = new AtomicLong();
    private final long startNanos = System.nanoTime();
    private long lastStats = System.nanoTime();
    private long lastFlush = System.nanoTime();
    private final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();

    /** One LOD node (level, nx, nz): data presence / version and its mesh state. */
    static final class Node {
        final int level;
        final int nx;
        final int nz;
        final long key;
        volatile boolean hasData;
        volatile int dataVersion;
        volatile long changedNanos;
        /** Data version + neighbour versions the current (or pending) mesh was built from; -1 = none. */
        volatile long meshStamp = -1L;
        volatile boolean meshQueued;
        /** Round 8: the queued mesh / generation job (a job that is no longer the node's is skipped: it was re-queued earlier in line). */
        volatile @Nullable Job meshJob;
        volatile @Nullable Job genJob;
        volatile boolean downsampleTried;
        /** Round 3: built from stored descendants (not generated). */
        volatile boolean genFromChildren;
        /** Built sections waiting for upload (render thread takes them). */
        volatile @Nullable List<LodMesher.Section> pending;
        volatile long pendingStamp;
        /** Render thread only: resident GPU sections and the stamp they were built from. */
        @Nullable List<LodGpu.GpuSection> gpu;
        long gpuStamp = -1L;
        long lastSelectedFrame;
        /** Round 3: wall-clock ms of the last item build that drew this node (vanilla's fade-in skip, Lod.sectionUploadTime). */
        volatile long lastDrawnMillis = Long.MIN_VALUE / 2;
        volatile boolean resident;
        /** Round 8: real data just replaced generated data here and the drawn mesh is still the generated one. Its sections are
         * masked one by one as the real sections arrive, so where the two differ (a sea-bed pit one section lower) nothing is
         * drawn until this node is re-meshed from the real data: that re-mesh is as urgent as a missing node. */
        volatile boolean realUpgrade;
        /** Stamp whose mesh did not fit the GPU budget (LodGpu): treated as done until the data changes. */
        volatile long refusedStamp = -2L;
        /** Render thread writes: bytes of the resident sections (0 when not resident). */
        volatile long gpuBytes;
        /** Round 3: a seed-generation job is queued for this node. */
        volatile boolean genQueued;
        /** Round 4: level 0 meshed flat-lit (blocks small on screen, Lod.FLAT0_PX); part of the mesh stamp. */
        volatile boolean flat;
        /** Round 6 (Lod#area): block-y range of the node's surface (lowest column top - one cell .. highest top); NaN = unknown. */
        volatile float yMin = Float.NaN;
        volatile float yMax = Float.NaN;

        Node(final int level, final int nx, final int nz) {
            this.level = level;
            this.nx = nx;
            this.nz = nz;
            this.key = key(level, nx, nz);
        }
    }

    record Snap(double x, double y, double z, double k, double kWide, double @Nullable [] planes, int rd, long frame) {
    }

    /** The published draw set: nodes to draw, whether each lies inside vanilla's render distance (drawn only if not loaded). */
    record Selection(Node[] nodes, boolean[] insideRd, long version) {
    }

    private abstract static class Job implements Comparable<Job> {
        double priority;
        final long seq = SEQ.getAndIncrement();
        /** Round 8: the node this job works on (null: not re-ranked), and the rank bias added to the distance term. */
        @Nullable Node node;
        double bias;

        Job(final double priority) {
            this.priority = priority;
        }

        Job forNode(final Node n, final double bias) {
            this.node = n;
            this.bias = bias;
            return this;
        }

        abstract void run(LodManager m) throws Exception;

        /** Round 8: the job was dropped without running (node far outside the LOD radius now). */
        void dropped() {
        }

        @Override
        public int compareTo(final Job o) {
            int c = Double.compare(this.priority, o.priority);
            return c != 0 ? c : Long.compare(this.seq, o.seq);
        }

        private static final AtomicLong SEQ = new AtomicLong();
    }

    static long key(final int level, final int nx, final int nz) {
        return (long)level << 58 | ((long)nx & 0x1FFFFFFFL) << 29 | (long)nz & 0x1FFFFFFFL;
    }

    /**
     * The coarsest level whose cell ({@code 2^level} blocks) at distance {@code d} blocks projects to at most {@code tau} panel
     * pixels with focal length {@code k} px: floor(log2(tau * d / k)), clamped to [0, maxLevel] (ZOOM.md §3, texture SSE).
     */
    static int wantedLevel(final double tau, final double d, final double k, final int maxLevel) {
        if (d <= 1.0 || k <= 0.0) {
            return d <= 1.0 ? 0 : maxLevel;
        }
        int want = (int)Math.floor(Math.log(tau * d / k) / Math.log(2.0));
        return Math.max(0, Math.min(maxLevel, want));
    }

    /** Continuous form of {@link #wantedLevel}: log2(tau * d / k) (unclamped; -inf near the camera). */
    static double wantedLevelExact(final double tau, final double d, final double k) {
        if (d <= 1.0 || k <= 0.0) {
            return d <= 1.0 ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        }
        return Math.log(tau * d / k) / Math.log(2.0);
    }

    /**
     * Round 2 selection hysteresis ({@code mc.lod.hysteresis}, log2 units, default 0.2 = 15 % of distance): a drawn node splits
     * only when it wants a level {@code h} finer than its own, a split node merges back only when it wants {@code h} coarser,
     * so a camera hovering at a threshold does not swap levels back and forth.
     */
    static final double HYSTERESIS = Double.parseDouble(System.getProperty("mc.lod.hysteresis", "0.2"));
    /** Scheduler thread: keys of the nodes split in the previous selection. */
    private java.util.Set<Long> splitLast = new java.util.HashSet<>();
    private java.util.Set<Long> splitNow = new java.util.HashSet<>();

    static int levelOf(final long key) {
        return (int)(key >>> 58);
    }

    static int nxOf(final long key) {
        return (int)(key << 6 >> 35);
    }

    static int nzOf(final long key) {
        return (int)(key << 35 >> 35);
    }

    LodManager(final ClientLevel level) throws IOException {
        this.level = level;
        Minecraft mc = Minecraft.getInstance();
        this.worldMinY = level.getMinY();
        this.worldHeight = level.getHeight();
        this.skyLight = level.dimensionType().hasSkyLight();
        String dim = level.dimension().identifier().toString().replace(':', '_').replace('/', '_');
        Path base = mc.gameDirectory.toPath().resolve("lod");
        String label;
        if (mc.getSingleplayerServer() != null) {
            // the seed too: a new world under an old world's name drew the old world's saved far terrain until each chunk was seen again
            label = "sp/" + sanitize(mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString())
                + "-" + Long.toHexString(mc.getSingleplayerServer().overworld().getSeed());
        } else {
            ServerData server = mc.getCurrentServer();
            label = "mp/" + sanitize(server == null ? "unknown" : server.ip);
        }
        this.storeLabel = label + "/" + dim;
        this.storeDir = base.resolve(label).resolve(dim);
        this.store = new LodStore(Lod.DISK ? this.storeDir : null, Lod.RAM_BYTES);
        this.fallbackBiome = level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS);
        this.compiler = new SectionCompiler(
            mc.options.ambientOcclusion().get(), Lod.CUTOUT_LEAVES && mc.options.cutoutLeaves().get(), mc.getModelManager().getBlockStateModelSet(),
            mc.getModelManager().getFluidStateModelSet(), mc.getBlockColors()
        );
        this.flatCompiler = new SectionCompiler(
            false, Lod.CUTOUT_LEAVES && mc.options.cutoutLeaves().get(), mc.getModelManager().getBlockStateModelSet(),
            mc.getModelManager().getFluidStateModelSet(), mc.getBlockColors()
        );
        this.blendRadius = mc.options.biomeBlendRadius().get();
        this.cacheAo = mc.options.ambientOcclusion().get();
        this.cacheCutout = Lod.CUTOUT_LEAVES && mc.options.cutoutLeaves().get();
        this.cacheResources = mc.getResourceManager();
        // Fabric model/color callbacks can depend on mod state outside the vanilla resource/registry inputs.
        // Keep ordinary meshing under Fabric until those callbacks have an explicit persistent-cache contract.
        this.meshCacheTried = net.minecraft.client.compat.ModCompat.fabricPresent();
        this.gpu = new LodGpu(this);
        for (int i = 0; i < Lod.THREADS; i++) {
            Thread t = new Thread(this::workerLoop, "lod-worker-" + i);
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            this.workers.add(t);
        }
        this.scheduler = new Thread(this::schedulerLoop, "lod-sched");
        this.scheduler.setDaemon(true);
        this.scheduler.setPriority(Thread.MIN_PRIORITY + 1);
        Lod.LOGGER.info("[lod] start: store {}, radius {} chunks, tau {} px, levels 0-{}, {} workers, disk {}, sp {}", this.storeLabel, Lod.RADIUS,
            Lod.TAU, Lod.MAX_LEVEL, Lod.THREADS, Lod.DISK, Lod.SP_SOURCE && mc.getSingleplayerServer() != null);
        this.workers.forEach(Thread::start);
        this.scheduler.start();
    }

    private static String sanitize(final String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    ClientLevel level() {
        return this.level;
    }

    boolean usable() {
        return !this.closed && this.gpu.usable();
    }

    Selection selection() {
        return this.selection;
    }

    Node node(final int level, final int nx, final int nz) {
        return this.nodes.computeIfAbsent(key(level, nx, nz), k -> {
            Node n = new Node(level, nx, nz);
            LodStore g = this.genStore;
            if (this.store.has(level, nx, nz) || g != null && g.has(level, nx, nz)) {
                n.hasData = true;
                n.dataVersion = 1;
            }
            return n;
        });
    }

    @Nullable Node existing(final int level, final int nx, final int nz) {
        return this.nodes.get(key(level, nx, nz));
    }

    // ---------------------------------------------------------------------------------------------------------------- capture

    /** Render thread: bumped on every chunk load / unload (the drawn set re-checks which level-0 nodes vanilla covers). */
    int chunkEvents;

    static final int NEAR_SPLIT_MARGIN = Integer.getInteger("mc.lod.nearSplitMargin", 2);

    void onChunkLoaded(final ChunkPos pos) {
        this.chunkEvents++;
        synchronized (this.pendingCapture) {
            this.pendingCapture.put(ChunkPos.pack(pos.x(), pos.z()), System.nanoTime());
        }
    }

    /** Round 7: a block of a loaded chunk changed; re-capture it about 3 s after the first change (batched). Before, a loaded
     * chunk's level-0 data stayed as it was at load, so the LOD drawn while vanilla rebuilt that chunk's sections (each
     * time it left and re-entered the render area) showed old blocks: freezing lakes flashed lighter ice at the same spots. */
    void onBlockChanged(final int cx, final int cz) {
        this.blockRecaptures++;
        synchronized (this.pendingCapture) {
            this.pendingCapture.putIfAbsent(ChunkPos.pack(cx, cz), System.nanoTime() + 2_000_000_000L);
        }
    }
    long blockRecaptures;

    void onChunkUnload(final LevelChunk chunk) {
        this.chunkEvents++;
        ChunkPos pos = chunk.getPos();
        synchronized (this.pendingCapture) {
            this.pendingCapture.remove(ChunkPos.pack(pos.x(), pos.z()));
        }
        this.capture(chunk);
    }

    /** Render thread, every frame: capture up to a few chunks loaded at least 1 s ago whose light is on. */
    static final long CAPTURE_DELAY_NANOS = Math.max(0L, Long.getLong("mc.lod.captureDelayMs", 150L)) * 1_000_000L;

    void captureLoaded() {
        long now = System.nanoTime();
        int budget = 8;
        synchronized (this.pendingCapture) {
            if (this.pendingCapture.isEmpty()) {
                return;
            }
            var it = this.pendingCapture.long2LongEntrySet().fastIterator();
            while (it.hasNext() && budget > 0) {
                var e = it.next();
                if (now - e.getLongValue() < CAPTURE_DELAY_NANOS) {
                    continue;
                }
                long packed = e.getLongKey();
                int x = ChunkPos.getX(packed);
                int z = ChunkPos.getZ(packed);
                LevelChunk chunk = this.level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                if (chunk == null) {
                    it.remove();
                    continue;
                }
                if (!this.level.getLightEngine().lightOnInColumn(SectionPos.getZeroNode(x, z))) {
                    if (now - e.getLongValue() > 20_000_000_000L) {
                        it.remove();
                    }
                    continue;
                }
                it.remove();
                this.capture(chunk);
                budget--;
            }
        }
    }

    /** Render thread: copy what LodData needs out of a live chunk and hand it to a worker. */
    private void capture(final LevelChunk chunk) {
        this.capture(chunk, this.level.getLightEngine());
    }

    /** Copy a chunk with the light of {@code light} (the client's, or the integrated server's for chunks generated for Farsight). */
    private void capture(final LevelChunk chunk, final net.minecraft.world.level.lighting.LevelLightEngine light) {
        if (this.closed) {
            return;
        }
        ChunkPos pos = chunk.getPos();
        if (this.skyLight && !light.lightOnInColumn(SectionPos.getZeroNode(pos.x(), pos.z()))) {
            return;   // Round 8: unloaded before its light was on (fast flight): would store sky 0 (dark squares)
        }
        LodData.Capture c = new LodData.Capture();
        c.cx = pos.x();
        c.cz = pos.z();
        c.minSectionY = this.level.getMinSectionY();
        c.sectionCount = this.level.getSectionsCount();
        @SuppressWarnings("unchecked")
        PalettedContainer<BlockState>[] states = new PalettedContainer[c.sectionCount];
        c.sections = states;
        c.sky = new byte[c.sectionCount][];
        c.block = new byte[c.sectionCount][];
        c.biomes = new String[c.sectionCount][];
        c.hasSkyLight = this.skyLight;
        boolean any = false;
        for (int s = 0; s < c.sectionCount; s++) {
            LevelChunkSection section = chunk.getSection(s);
            if (!section.hasOnlyAir()) {
                states[s] = section.getStates().copy();
                any = true;
            }
            SectionPos sp = SectionPos.of(c.cx, c.minSectionY + s, c.cz);
            if (this.skyLight) {
                DataLayer sky = light.getLayerListener(LightLayer.SKY).getDataLayerData(sp);
                c.sky[s] = sky == null ? null : sky.getData().clone();
            }
            DataLayer block = light.getLayerListener(LightLayer.BLOCK).getDataLayerData(sp);
            c.block[s] = block == null ? null : block.getData().clone();
            c.biomes[s] = biomeKeys(section.getBiomes());
        }
        if (!any) {
            return;
        }
        double prio = -1e9 + this.distanceToCamera(c.cx * 16 + 8, c.cz * 16 + 8);
        this.submit(new Job(prio) {
            @Override
            void run(final LodManager m) {
                m.ingest(c);
                m.captured.incrementAndGet();
            }
        });
    }

    private final Map<Holder<Biome>, String> biomeNames = new java.util.IdentityHashMap<>();

    private String[] biomeKeys(final PalettedContainerRO<Holder<Biome>> biomes) {
        String[] out = new String[64];
        for (int qy = 0; qy < 4; qy++) {
            for (int qz = 0; qz < 4; qz++) {
                for (int qx = 0; qx < 4; qx++) {
                    Holder<Biome> h = biomes.get(qx, qy, qz);
                    String name;
                    synchronized (this.biomeNames) {
                        name = this.biomeNames.computeIfAbsent(h, Holder::getRegisteredName);
                    }
                    out[qy * 16 + qz * 4 + qx] = name;
                }
            }
        }
        return out;
    }

    /** Worker: level-0 data from a capture into the store; parents and neighbour meshes become stale. */
    private void ingest(final LodData.Capture c) {
        long t0 = System.nanoTime();
        if (!this.skyLight) {
            c.hasSkyLight = true;
        }
        LodData data = LodData.fromCapture(c, this.skyLight);
        LodShadow.write(data);
        LodGen ag = this.gen;
        if (LodGenAudit.ENABLED && ag != null) {
            LodGenAudit.compare(data, ag.generate(0, c.cx, c.cz));
        }
        byte[] blob = data.encode();
        boolean wasReal = this.store.has(0, c.cx, c.cz);
        if (RECAPTURE_DIAG && !wasReal) {
            this.capturedThisSession.add(ChunkPos.pack(c.cx, c.cz));
        }
        if (RECAPTURE_DIAG && wasReal) {
            // diagnostic: a chunk captured again against what the store held (columns whose top block differs)
            try {
                byte[] old = this.store.get(0, c.cx, c.cz);
                LodData prev = old == null ? null : LodData.decode(old);
                if (prev != null) {
                    int diff = 0;
                    String ex = "";
                    for (int col = 0; col < 256; col++) {
                        int a0 = top(prev, col), b0 = top(data, col);
                        if (a0 != b0) {
                            if (diff == 0) {
                                ex = " e.g. col " + col + " " + (a0 / 1000) + ":" + LodData.stateOf(a0 % 1000 + (a0 < 0 ? 0 : 0)) + " -> " + (b0 / 1000) + ":" + LodData.stateOf(b0 % 1000);
                            }
                            diff++;
                        }
                    }
                    boolean again = !this.capturedThisSession.add(ChunkPos.pack(c.cx, c.cz)) ;
                    ex += again ? " (same session)" : " (stored earlier)";
                    long n = this.recaptures.incrementAndGet();
                    if (diff > 0) {
                        long m = this.recaptureDiffs.incrementAndGet();
                        if (m <= 12 || m % 200 == 0) {
                            System.out.println("[lod] recapture " + c.cx + "," + c.cz + " columns differ " + diff + ex + " (" + m + " of " + n + " recaptures differ)");
                        }
                    }
                }
            } catch (IOException ignored) {
            }
        }
        this.store.put(0, c.cx, c.cz, blob);
        this.encodeNanos.addAndGet(System.nanoTime() - t0);
        this.changed(0, c.cx, c.cz);
        if (!wasReal) {
            this.node(0, c.cx, c.cz).realUpgrade = true;
        }
    }

    static final boolean RECAPTURE_DIAG = Lod.flag("mc.lod.recaptureDiag", false);
    final AtomicLong recaptures = new AtomicLong(), recaptureDiffs = new AtomicLong();
    final java.util.Set<Long> capturedThisSession = ConcurrentHashMap.newKeySet();

    private static int top(final LodData d, final int col) {
        for (int y = d.height - 1; y >= 0; y--) {
            if (d.states[y * 256 + col] != LodData.AIR_ID) return (y + d.cellMinY) * 1000 + (d.states[y * 256 + col] & 0x3FF);
        }
        return Integer.MIN_VALUE;
    }

    private void changed(final int level, final int nx, final int nz) {
        Node n = this.node(level, nx, nz);
        n.hasData = true;
        n.dataVersion++;
        long now = System.nanoTime();
        n.changedNanos = now;
        if (level < Lod.MAX_LEVEL) {
            this.dirtyParents.put(key(level + 1, nx >> 1, nz >> 1), now);
            Node parent = this.node(level + 1, nx >> 1, nz >> 1);
            parent.changedNanos = now;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------- singleplayer

    /** Scheduler: queue reads of saved chunks inside the LOD radius around the player (rings, nearest first). */
    private void scanSingleplayer(final Snap s) {
        Minecraft mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        if (!Lod.SP_SOURCE || server == null) {
            this.spDone = true;
            return;
        }
        ServerLevel serverLevel = server.getLevel(this.level.dimension());
        if (serverLevel == null) {
            this.spDone = true;
            return;
        }
        int cx = (int)Math.floor(s.x() / 16.0);
        int cz = (int)Math.floor(s.z() / 16.0);
        if (this.spCenterX == Integer.MIN_VALUE || Math.abs(cx - this.spCenterX) > 8 || Math.abs(cz - this.spCenterZ) > 8) {
            this.spCenterX = cx;
            this.spCenterZ = cz;
            this.spRing = 0;
            this.spDone = false;
        }
        int r = Lod.RADIUS;
        while (this.spRing <= r + 1 && this.spInFlight.get() < 96) {
            int ring = this.spRing;
            for (int dz = -ring; dz <= ring; dz++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring || dx * dx + dz * dz > (r + 1) * (r + 1)) {
                        continue;
                    }
                    int x = this.spCenterX + dx;
                    int z = this.spCenterZ + dz;
                    long p = ChunkPos.pack(x, z);
                    if (!this.spChecked.add(p)) {
                        continue;
                    }
                    if (this.store.has(0, x, z)) {
                        Node n = this.node(0, x, z);
                        n.hasData = true;
                        continue;
                    }
                    this.spInFlight.incrementAndGet();
                    double prio = ring * 16.0;
                    this.submit(new Job(prio) {
                        @Override
                        void run(final LodManager m) throws Exception {
                            try {
                                m.readSaved(serverLevel, x, z);
                            } finally {
                                m.spInFlight.decrementAndGet();
                            }
                        }
                    });
                }
            }
            this.spRing++;
        }
        if (this.spRing > r + 1) {
            this.spDone = true;
        }
    }

    /** Worker: one saved chunk through the server's IO worker (thread-safe, read-only) into the store. */
    private void readSaved(final ServerLevel serverLevel, final int x, final int z) throws Exception {
        long t0 = System.nanoTime();
        Optional<CompoundTag> tag = serverLevel.getChunkSource().chunkMap.read(new ChunkPos(x, z)).get(30, TimeUnit.SECONDS);
        if (tag.isEmpty()) {
            this.spAbsent.incrementAndGet();
            if (WORLD_GEN && !this.exactDone.contains(ChunkPos.pack(x, z))) {
                this.genWanted.add(ChunkPos.pack(x, z));
            }
            return;
        }
        SerializableChunkData data = SerializableChunkData.parse(serverLevel, serverLevel.palettedContainerFactory(), tag.get());
        if (data == null || !data.chunkStatus().isOrAfter(ChunkStatus.FULL)) {
            this.spAbsent.incrementAndGet();
            if (WORLD_GEN && !this.exactDone.contains(ChunkPos.pack(x, z))) {
                this.genWanted.add(ChunkPos.pack(x, z));
            }
            return;
        }
        if (this.store.has(0, x, z)) {
            return;   // captured from the client meanwhile (newer)
        }
        LodData.Capture c = new LodData.Capture();
        c.cx = x;
        c.cz = z;
        c.minSectionY = serverLevel.getMinSectionY();
        c.sectionCount = serverLevel.getSectionsCount();
        @SuppressWarnings("unchecked")
        PalettedContainer<BlockState>[] states = new PalettedContainer[c.sectionCount];
        c.sections = states;
        c.sky = new byte[c.sectionCount][];
        c.block = new byte[c.sectionCount][];
        c.biomes = new String[c.sectionCount][];
        c.hasSkyLight = data.lightCorrect() && this.skyLight;
        if (!c.hasSkyLight && this.skyLight) {
            this.spUnlit.incrementAndGet();
        }
        for (SerializableChunkData.SectionData sd : data.sectionData()) {
            int s = sd.y() - c.minSectionY;
            if (s < 0 || s >= c.sectionCount) {
                continue;
            }
            LevelChunkSection section = sd.chunkSection();
            if (section != null) {
                if (!section.hasOnlyAir()) {
                    states[s] = section.getStates();
                }
                c.biomes[s] = this.biomeKeys(section.getBiomes());
            }
            if (c.hasSkyLight && sd.skyLight() != null) {
                c.sky[s] = sd.skyLight().getData();
            }
            if (sd.blockLight() != null) {
                c.block[s] = sd.blockLight().getData();
            }
        }
        this.readNanos.addAndGet(System.nanoTime() - t0);
        this.ingest(c);
        this.spRead.incrementAndGet();
    }

    // ---------------------------------------------------------------------------------------------------------------- world generation

    /**
     * Round 8 ({@code mc.lod.worldGen}, singleplayer): chunks inside the LOD radius that the save doesn't have are generated by the
     * integrated server (vanilla's generator: exact terrain, trees, lakes, structures), nearest first, a few at a time, and
     * captured as real LOD data; vanilla saves them when they unload (like a pregeneration). Seed-generated data covers them meanwhile.
     */
    static final boolean WORLD_GEN = Lod.flag("mc.lod.worldGen", true);
    private static final int WORLD_GEN_MAX = Math.max(1, Integer.getInteger("mc.lod.worldGenMax", 2));
    private final java.util.Set<Long> genWanted = ConcurrentHashMap.newKeySet();
    private final AtomicInteger genInFlight = new AtomicInteger();
    private final ConcurrentLinkedQueue<Runnable> genLightWait = new ConcurrentLinkedQueue<>();
    final AtomicLong worldGenerated = new AtomicLong();
    final AtomicLong spUnlit = new AtomicLong();
    private int plateLogged;

    /** Scheduler: hands the nearest wanted chunks (outside vanilla's own render distance) to the integrated server. */
    private void pumpWorldGen(final Snap s) {
        var server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            return;
        }
        for (Runnable retry; (retry = this.genLightWait.poll()) != null; ) {
            server.execute(retry);
        }
        int free = WORLD_GEN_MAX - this.genInFlight.get();
        if (free <= 0 || this.genWanted.isEmpty()) {
            return;
        }
        ServerLevel serverLevel = server.getLevel(this.level.dimension());
        if (serverLevel != null && LodExactGen.ENABLED) {
            this.pumpExact(s, serverLevel);
            return;
        }
        if (serverLevel == null || server.getAverageTickTimeNanos() > 40_000_000L) {
            return;   // the game's own ticks come first
        }
        int ccx = (int)Math.floor(s.x() / 16.0);
        int ccz = (int)Math.floor(s.z() / 16.0);
        long r2 = (long)(Lod.RADIUS + 1) * (Lod.RADIUS + 1);
        long rd2 = (long)(s.rd() + 2) * (s.rd() + 2);
        long[] best = new long[free];
        long[] bestD = new long[free];
        Arrays.fill(bestD, Long.MAX_VALUE);
        var it = this.genWanted.iterator();
        while (it.hasNext()) {
            long p = it.next();
            long dx = ChunkPos.getX(p) - ccx;
            long dz = ChunkPos.getZ(p) - ccz;
            long d2 = dx * dx + dz * dz;
            if (d2 > r2) {
                it.remove();   // out of range now: the scan offers it again if the player comes back
                this.spChecked.remove(p);
                continue;
            }
            if (d2 < rd2) {
                continue;   // vanilla loads these itself
            }
            // nearest first, in view ahead of out of view (rank in squared chunks; out of view counts four times)
            long rank = this.inView(s, ChunkPos.getX(p) * 16.0, ChunkPos.getZ(p) * 16.0, 16.0) ? d2 : d2 * 4;
            if (rank < bestD[free - 1]) {
                int j = free - 1;
                while (j > 0 && bestD[j - 1] > rank) {
                    bestD[j] = bestD[j - 1];
                    best[j] = best[j - 1];
                    j--;
                }
                bestD[j] = rank;
                best[j] = p;
            }
        }
        for (int i = 0; i < free && bestD[i] != Long.MAX_VALUE; i++) {
            long p = best[i];
            this.genWanted.remove(p);
            this.genInFlight.incrementAndGet();
            int x = ChunkPos.getX(p);
            int z = ChunkPos.getZ(p);
            server.execute(() -> this.startWorldGen(server, serverLevel, x, z));
        }
    }

    // ---------------------------------------------------------------------------------------- exact generation (LodExactGen)

    private static final int EXACT_THREADS = Math.max(1, Integer.getInteger("mc.lod.exactThreads", 3));
    private java.util.concurrent.@Nullable ExecutorService exactPool;
    private java.util.concurrent.@Nullable ForkJoinPool exactWorkers;
    private net.minecraft.world.level.chunk.status.@Nullable WorldGenContext exactContext;
    private final LodExactGen.World exactWorld = new LodExactGen.World();
    private int exactLastX = Integer.MIN_VALUE / 2;
    private int exactLastZ = Integer.MIN_VALUE / 2;
    private final AtomicInteger exactInFlight = new AtomicInteger();
    /** Chunks generated by LodExactGen (the save scan does not ask for them again). */
    private final java.util.Set<Long> exactDone = ConcurrentHashMap.newKeySet();
    final AtomicLong exactChunks = new AtomicLong();
    final AtomicLong exactNanos = new AtomicLong();

    /** Scheduler: the batch around the nearest wanted chunk to an exact-generation thread. */
    private void pumpExact(final Snap s, final ServerLevel serverLevel) {
        if (this.exactInFlight.get() >= 1 || this.genWanted.isEmpty()) {
            return;
        }
        if (this.exactPool == null) {
            this.exactWorkers = new java.util.concurrent.ForkJoinPool(EXACT_THREADS, pool -> {
                var t = new java.util.concurrent.ForkJoinWorkerThread(pool) {
                    @Override
                    protected void onStart() {
                        super.onStart();
                        Lod.backgroundQos();
                    }
                };
                t.setName("lod-exactgen-" + t.getPoolIndex());
                t.setDaemon(true);
                t.setPriority(Integer.getInteger("mc.lod.exactPriority", Thread.MIN_PRIORITY));
                return t;
            }, null, false);
            this.exactPool = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(() -> {
                    Lod.backgroundQos();
                    r.run();
                }, "lod-exactgen");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });
            this.exactContext = LodExactGen.context(serverLevel);
        }
        int ccx = (int)Math.floor(s.x() / 16.0);
        int ccz = (int)Math.floor(s.z() / 16.0);
        long r2 = (long)(Lod.RADIUS + 1) * (Lod.RADIUS + 1);
        long rd2 = (long)(s.rd() + 2) * (s.rd() + 2);
        long best = 0L;
        long bestRank = Long.MAX_VALUE;
        // the best chunk next to the previous batch (its ring chunks are still kept: continuing along the arc reuses them)
        long near = 0L;
        long nearRank = Long.MAX_VALUE;
        int b = LodExactGen.BATCH;
        var it = this.genWanted.iterator();
        while (it.hasNext()) {
            long p = it.next();
            long dx = ChunkPos.getX(p) - ccx;
            long dz = ChunkPos.getZ(p) - ccz;
            long d2 = dx * dx + dz * dz;
            if (d2 > r2) {
                it.remove();
                this.spChecked.remove(p);
                continue;
            }
            if (d2 < rd2) {
                continue;
            }
            long rank = this.inView(s, ChunkPos.getX(p) * 16.0, ChunkPos.getZ(p) * 16.0, 16.0) ? d2 : d2 * 4;
            if (rank < bestRank) {
                bestRank = rank;
                best = p;
            }
            if (rank < nearRank && Math.abs(Math.floorDiv(ChunkPos.getX(p), b) - this.exactLastX) <= 1
                && Math.abs(Math.floorDiv(ChunkPos.getZ(p), b) - this.exactLastZ) <= 1) {
                nearRank = rank;
                near = p;
            }
        }
        if (bestRank == Long.MAX_VALUE) {
            return;
        }
        if (nearRank <= bestRank * 2) {
            best = near;
        }
        int bx = Math.floorDiv(ChunkPos.getX(best), b) * b;
        int bz = Math.floorDiv(ChunkPos.getZ(best), b) * b;
        this.exactLastX = bx / b;
        this.exactLastZ = bz / b;
        // targets: the batch's chunks known to be absent from the save (saved chunks keep their real data)
        java.util.Set<Long> capture = new java.util.HashSet<>();
        for (int z = bz; z < bz + b; z++) {
            for (int x = bx; x < bx + b; x++) {
                long p = ChunkPos.pack(x, z);
                if (this.genWanted.remove(p)) {
                    capture.add(p);
                    this.exactDone.add(p);
                }
            }
        }
        if (capture.isEmpty()) {
            return;
        }
        this.exactInFlight.incrementAndGet();
        var ctx = this.exactContext;
        this.exactPool.execute(() -> {
            long t0 = System.nanoTime();
            try {
                LodExactGen.generate(serverLevel, ctx, this.exactWorld, bx, bz, capture, chunk -> {
                    this.captureProto(chunk);
                    this.exactChunks.incrementAndGet();
                }, () -> this.closed, this.exactWorkers);
            } catch (Throwable e) {
                Lod.LOGGER.warn("[lod] exact generation of batch {},{} failed", bx, bz, e);
                this.exactWorld.holders.clear();   // chunks left part-way through a step
                for (long p : capture) {
                    this.exactDone.remove(p);
                    this.genWanted.add(p);   // offered again
                }
            } finally {
                this.exactNanos.addAndGet(System.nanoTime() - t0);
                this.exactInFlight.decrementAndGet();
            }
        });
    }

    /** Exact-generation thread: a generated proto-chunk (no light: estimated from the columns) into the store. */
    private void captureProto(final net.minecraft.world.level.chunk.ProtoChunk chunk) {
        if (this.closed) {
            return;
        }
        ChunkPos pos = chunk.getPos();
        LodData.Capture c = new LodData.Capture();
        c.cx = pos.x();
        c.cz = pos.z();
        c.minSectionY = this.level.getMinSectionY();
        c.sectionCount = this.level.getSectionsCount();
        @SuppressWarnings("unchecked")
        PalettedContainer<BlockState>[] states = new PalettedContainer[c.sectionCount];
        c.sections = states;
        c.sky = new byte[c.sectionCount][];
        c.block = new byte[c.sectionCount][];
        c.biomes = new String[c.sectionCount][];
        c.hasSkyLight = false;
        boolean any = false;
        for (int i = 0; i < c.sectionCount; i++) {
            LevelChunkSection section = chunk.getSection(i);
            if (!section.hasOnlyAir()) {
                states[i] = section.getStates().copy();
                any = true;
            }
            c.biomes[i] = this.biomeKeys(section.getBiomes());
        }
        if (!any) {
            return;
        }
        double prio = -1e9 + this.distanceToCamera(c.cx * 16 + 8, c.cz * 16 + 8);
        this.submit(new Job(prio) {
            @Override
            void run(final LodManager m) {
                m.ingest(c);
                m.captured.incrementAndGet();
            }
        });
    }

    /** Server thread: ticket + generation of one chunk to FULL; captured when done (light on), then the ticket goes. */
    private void startWorldGen(final net.minecraft.server.MinecraftServer server, final ServerLevel serverLevel, final int x, final int z) {
        ChunkPos pos = new ChunkPos(x, z);
        var cache = serverLevel.getChunkSource();
        if (this.closed) {
            this.genInFlight.decrementAndGet();
            return;
        }
        cache.addTicketAndLoadWithRadius(net.minecraft.server.level.TicketType.FARSIGHT, pos, 0)
            .whenCompleteAsync((r, e) -> this.finishWorldGen(server, serverLevel, pos, 0), server);
    }

    private void finishWorldGen(final net.minecraft.server.MinecraftServer server, final ServerLevel serverLevel, final ChunkPos pos, final int tries) {
        var cache = serverLevel.getChunkSource();
        LevelChunk chunk = cache.getChunkNow(pos.x(), pos.z());
        var light = cache.getLightEngine();
        if (chunk != null && !this.closed && this.skyLight && !light.lightOnInColumn(SectionPos.getZeroNode(pos.x(), pos.z())) && tries < 200) {
            this.genLightWait.add(() -> this.finishWorldGen(server, serverLevel, pos, tries + 1));   // light on its way: next pump
            return;
        }
        try {
            if (chunk != null && !this.closed) {
                this.capture(chunk, light);
                this.worldGenerated.incrementAndGet();
            }
        } finally {
            cache.removeTicketWithRadius(net.minecraft.server.level.TicketType.FARSIGHT, pos, 0);
            this.genInFlight.decrementAndGet();
        }
    }

    // ---------------------------------------------------------------------------------------------------------------- generation

    /** Scheduler: sets up seed generation once (singleplayer seed / user seed for this server). */
    private void setupGen() {
        if (this.genSetupDone) {
            return;
        }
        this.genSetupDone = true;
        LodGen g = LodGen.forLevel(this.level);
        if (g == null) {
            return;
        }
        Path dir = Lod.DISK ? this.storeDir.resolve("gen-" + Long.toHexString(g.seed) + "-" + LodGen.versionTag()) : null;
        LodStore s = new LodStore(dir, Math.max(16L << 20, Lod.RAM_BYTES / 2));
        s.setDiskCap(Lod.GEN_DISK_BYTES, this::regionDistance);
        this.genStore = s;
        this.gen = g;
        // Nodes created before the generator existed: their generated data counts now.
        this.nodes.values().forEach(n -> {
            if (!n.hasData && s.has(n.level, n.nx, n.nz)) {
                n.hasData = true;
                n.dataVersion++;
            }
        });
    }

    /** Disk cap (LodStore): distance in blocks from the camera to a region of 32 x 32 nodes of {@code level}. */
    private double regionDistance(final int level, final int rx, final int rz) {
        double size = 32.0 * (16 << level);
        return this.distanceToCamera(rx * size + size / 2, rz * size + size / 2);
    }

    private void scheduleGen(final Node n, final double prio, final double bias) {
        n.genQueued = true;
        Job job = new Job(prio) {
            @Override
            void run(final LodManager m) {
                if (n.genJob != this) {
                    return;
                }
                try {
                    m.generateNode(n);
                } finally {
                    n.genQueued = false;
                }
            }

            @Override
            void dropped() {
                if (n.genJob == this) {
                    n.genQueued = false;
                }
            }
        }.forNode(n, bias);
        n.genJob = job;
        this.submit(job);
    }

    /** Worker: generates a node into the generated store (no parent marked dirty: coarser nodes are generated at their level). */
    private void generateNode(final Node n) {
        LodGen g = this.gen;
        LodStore s = this.genStore;
        if (g == null || s == null || n.hasData) {
            return;
        }
        if (s.get(n.level, n.nx, n.nz) == null) {
            LodData d = g.generate(n.level, n.nx, n.nz);
            s.put(n.level, n.nx, n.nz, d.encode());
            this.genJobs.incrementAndGet();
        }
        n.hasData = true;
        n.dataVersion++;
        n.changedNanos = System.nanoTime();
    }

    /** The seed-generated data of a node (generated now if missing), or null without a generator. */
    private @Nullable LodData generated(final int level, final int nx, final int nz) throws IOException {
        LodGen g = this.gen;
        LodStore s = this.genStore;
        if (g == null || s == null) {
            return null;
        }
        byte[] blob = s.get(level, nx, nz);
        if (blob != null) {
            return LodData.decode(blob);
        }
        LodData d = g.generate(level, nx, nz);
        s.put(level, nx, nz, d.encode());
        this.genJobs.incrementAndGet();
        return d;
    }

    private boolean isLoadedChunk(final int x, final int z) {
        return this.level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false) != null;
    }

    // ---------------------------------------------------------------------------------------------------------------- downsample

    private void scheduleDownsamples(final Snap s) {
        long now = System.nanoTime();
        for (Map.Entry<Long, Long> e : this.dirtyParents.entrySet()) {
            if (now - e.getValue() < 1_000_000_000L) {
                continue;
            }
            long k = e.getKey();
            if (!this.dirtyParents.remove(k, e.getValue())) {
                continue;
            }
            int level = levelOf(k);
            int nx = nxOf(k);
            int nz = nzOf(k);
            double size = 16 << level;
            double prio = this.distanceToCamera(nx * size + size / 2, nz * size + size / 2) + level * 64.0;
            this.downsamplesScheduled.incrementAndGet();
            this.submit(new Job(prio) {
                @Override
                void run(final LodManager m) throws IOException {
                    m.downsample(level, nx, nz);
                }
            });
        }
    }

    private void downsample(final int level, final int nx, final int nz) throws IOException {
        long t0 = System.nanoTime();
        LodData[] children = new LodData[4];
        for (int dz = 0; dz < 2; dz++) {
            for (int dx = 0; dx < 2; dx++) {
                children[dz * 2 + dx] = this.load(level - 1, nx * 2 + dx, nz * 2 + dz);
            }
        }
        LodData parent = LodData.downsample(level, nx, nz, children, this.worldMinY, this.worldHeight);
        if (parent == null) {
            return;
        }
        if (this.gen != null && (children[0] == null || children[1] == null || children[2] == null || children[3] == null)) {
            // Round 3: quadrants without real or generated children take the seed-generated cells of this node (no holes where
            // real chunks end inside a coarse node); real children always win.
            LodData g = this.generated(level, nx, nz);
            if (g != null && g.height == parent.height && g.cellMinY == parent.cellMinY) {
                parent = LodData.overlay(parent, g, children);
                this.genOverlays.incrementAndGet();
            }
        }
        this.store.put(level, nx, nz, parent.encode());
        this.downNanos.addAndGet(System.nanoTime() - t0);
        this.downsampled.incrementAndGet();
        this.changed(level, nx, nz);
    }

    private final ThreadLocal<LinkedHashMap<Long, Object[]>> decoded = ThreadLocal.withInitial(() -> new LinkedHashMap<>(32, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<Long, Object[]> eldest) {
            return this.size() > 20;
        }
    });

    /**
     * Decoded nodes shared by the workers ({@code mc.lod.sharedDecoded}, default true; {@code mc.lod.decodedMB}, default 128):
     * a mesh decodes its node and 8 neighbours, and the workers take neighbouring nodes in no fixed order, so the per-thread
     * cache (20 nodes) missed most neighbours and decoding was an eighth of the workers' time. Keyed by the stored blob's
     * identity (a change stores a new array), so the mesh cache's captured inputs share it too.
     */
    private static final boolean SHARED_DECODED = Lod.flag("mc.lod.sharedDecoded", true);
    private static final long DECODED_CAP = Long.getLong("mc.lod.decodedMB", 128L) << 20;
    private final LinkedHashMap<Long, Object[]> sharedDecoded = new LinkedHashMap<>(1024, 0.75F, true);
    private long sharedDecodedBytes;
    private final AtomicLong loads = new AtomicLong();
    private final AtomicLong loadHits = new AtomicLong();

    private static long decodedBytes(final LodData d) {
        return d.states.length * 4L + d.light.length + d.biomes.length * 2L + 128L;
    }

    /** A blob's decoded data from the shared cache (by blob identity), decoded and cached on a miss. */
    LodData decodeShared(final long k, final byte[] blob) throws IOException {
        this.loads.incrementAndGet();
        LinkedHashMap<Long, Object[]> cache = this.sharedDecoded;
        Object[] hit;
        synchronized (cache) {
            hit = cache.get(k);
        }
        if (hit != null && hit[0] == blob) {
            this.loadHits.incrementAndGet();
            return (LodData)hit[1];
        }
        LodData data = LodData.decode(blob);
        synchronized (cache) {
            Object[] old = cache.put(k, new Object[]{blob, data});
            this.sharedDecodedBytes += decodedBytes(data) - (old == null ? 0L : decodedBytes((LodData)old[1]));
            for (Iterator<Object[]> it = cache.values().iterator(); this.sharedDecodedBytes > DECODED_CAP && it.hasNext(); ) {
                this.sharedDecodedBytes -= decodedBytes((LodData)it.next()[1]);
                it.remove();
            }
        }
        return data;
    }

    /** Decoded node data (cached), or null. */
    @Nullable LodData load(final int level, final int nx, final int nz) throws IOException {
        Node n = this.existing(level, nx, nz);
        int version = n == null ? 0 : n.dataVersion;
        long k = key(level, nx, nz);
        LinkedHashMap<Long, Object[]> cache = this.decoded.get();
        if (!SHARED_DECODED) {
            Object[] hit = cache.get(k);
            if (hit != null && (int)hit[0] == version) {
                return (LodData)hit[1];
            }
        }
        // Real data first (captured / saved chunks and their downsamples); seed-generated data where there is none. Diagnostics
        // mc.lod.genOnly: only generated data except the chunks the client has loaded (generated vs real look comparisons).
        boolean realAllowed = !Lod.GEN_ONLY || this.gen == null || level == 0 && this.isLoadedChunk(nx, nz);
        byte[] blob = realAllowed ? this.store.get(level, nx, nz) : null;
        if (blob == null) {
            LodStore g = this.genStore;
            blob = g == null ? null : g.get(level, nx, nz);
        }
        if (blob == null) {
            if (n != null && this.gen != null && n.hasData) {
                n.hasData = false;   // generated data dropped by the disk cap: generated again when selected
                n.genQueued = false;
            }
            return null;
        }
        LodData data;
        try {
            data = SHARED_DECODED ? this.decodeShared(k, blob) : LodData.decode(blob);
        } catch (IOException e) {
            Lod.LOGGER.warn("[lod] bad node {} {} {}: {}", level, nx, nz, e.toString());
            return null;
        }
        if (!SHARED_DECODED) {
            cache.put(k, new Object[]{version, data});
        }
        return data;
    }

    // ---------------------------------------------------------------------------------------------------------------- mesh

    /** Mesh stamp of a node: own data version and the 8 neighbours' (a neighbour change re-meshes the border). */
    private long stamp(final Node n) {
        long h = n.dataVersion;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                Node o = this.existing(n.level, n.nx + dx, n.nz + dz);
                h = h * 31L + (o == null || !o.hasData ? 0 : o.dataVersion);
            }
        }
        return n.flat ? h ^ 0x5DEECE66DL << 24 : h;
    }

    /** True when the node and its neighbours have not changed for a second (avoids re-meshing during bulk ingest). */
    private boolean settled(final Node n, final long now) {
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                Node o = dx == 0 && dz == 0 ? n : this.existing(n.level, n.nx + dx, n.nz + dz);
                if (o != null && now - o.changedNanos < 1_000_000_000L) {
                    return false;
                }
            }
        }
        return !this.dirtyParents.containsKey(n.key);
    }

    final java.util.concurrent.atomic.AtomicLongArray meshesScheduled = new java.util.concurrent.atomic.AtomicLongArray(8);
    final AtomicLong downsamplesScheduled = new AtomicLong();

    private void scheduleMesh(final Node n, final double prio, final double bias) {
        n.meshQueued = true;
        this.meshesScheduled.incrementAndGet(Math.min(7, n.level));
        long stamp = this.stamp(n);
        boolean flat = n.flat;
        Job job = new Job(prio) {
            @Override
            void run(final LodManager m) throws IOException {
                if (n.meshJob != this) {
                    return;
                }
                try {
                    // the stamp of the data as it is now, not as it was when the job was queued: a job that waited absorbs
                    // every neighbour change since (each one used to cost a further re-mesh of the same data)
                    m.mesh(n, LATE_STAMP ? m.stamp(n) : stamp, flat);
                } finally {
                    n.meshQueued = false;
                }
            }

            @Override
            void dropped() {
                if (n.meshJob == this) {
                    n.meshQueued = false;
                }
            }
        }.forNode(n, bias);
        n.meshJob = job;
        this.submit(job);
    }

    /** Round 8: a node job's rank from the current camera (as visit ranks it), or NaN if the node is far outside the radius now. */
    private double rank(final Snap s, final Node n, final double bias) {
        double size = 16.0 * (1 << n.level);
        double x0 = n.nx * size;
        double z0 = n.nz * size;
        double dx = Math.max(0.0, Math.max(x0 - s.x(), s.x() - (x0 + size)));
        double dz = Math.max(0.0, Math.max(z0 - s.z(), s.z() - (z0 + size)));
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d > Lod.RADIUS * 16.0 + 512.0) {
            return Double.NaN;
        }
        return this.distRank(s, n.level, d, this.inView(s, x0, z0, size), !n.resident || n.realUpgrade) + bias;
    }

    /**
     * Round 8: the distance term of a node job's rank. Level-0 nodes in (or about to enter) the render-distance ring come strictly
     * first: no coarse node may stand in there, so a late one is a hole, while a late far node is only a coarser picture.
     */
    private double distRank(final Snap s, final int level, final double d, final boolean inView, final boolean missing) {
        // Only nodes with nothing on screen: re-meshes of drawn ring nodes (each capture re-meshes 9) kept ~400 jobs at the
        // front and the nodes still missing waited 5-12 s behind them.
        if (missing && level == 0 && NEAR_FIRST && d < (s.rd() + (LodGpu.nearHandoff ? NEAR_SPLIT_MARGIN : 0) + PREFETCH) * 16.0) {
            return d * 0.02;
        }
        // Missing coarse far leaves (out of view they select coarse and were ranked by plain distance, behind all the nearer
        // work): coarse first as in fallback, so turning toward unseen land finds its horizon already there.
        if (missing && coarseFirstLeaf && level >= COARSE_FIRST && d > 768.0) {
            return 6.0 + d * 0.02;
        }
        double rank = d * (inView ? 0.5 : 1.0);
        // A node that already shows a mesh of its own waits behind the nodes that show nothing or a coarse stand-in: every
        // arriving chunk re-meshes its 8 neighbours, and those re-meshes (all nearer) kept far first meshes waiting for minutes.
        // The level-0 ring keeps its place (its borders meet the real terrain).
        // The ring's own re-meshes too (flying, each arriving chunk re-meshes 9 nodes: more than the workers can do, and
        // the far first meshes never got a turn: the horizon stayed empty for minutes). A late ring re-mesh is a stale border.
        if (!missing && REMESH_PENALTY > 0.0 && (RING_REMESH_PENALTY || !(level == 0 && d < (s.rd() + (LodGpu.nearHandoff ? NEAR_SPLIT_MARGIN : 0) + PREFETCH) * 16.0))) {
            rank += REMESH_PENALTY;
        }
        return rank;
    }

    static final boolean MESH_PROF = Lod.flag("mc.lod.meshProf", false);
    static final java.util.concurrent.atomic.AtomicLongArray PROF_LOAD = new java.util.concurrent.atomic.AtomicLongArray(6), PROF_MESH = new java.util.concurrent.atomic.AtomicLongArray(6), PROF_N = new java.util.concurrent.atomic.AtomicLongArray(6);
    static final AtomicLong PROF_ALL = new AtomicLong();
    static final boolean LATE_STAMP = Lod.flag("mc.lod.lateStamp", true);
    static final int COARSE_FIRST = Integer.getInteger("mc.lod.coarseFirst", 4);
    public static volatile boolean coarseFirstLeaf = Lod.flag("mc.lod.coarseFirstLeaf", true);
    static final boolean RING_REMESH_PENALTY = Lod.flag("mc.lod.ringRemeshPenalty", true);
    static final double REMESH_PENALTY = Double.parseDouble(System.getProperty("mc.lod.remeshPenalty", "800"));

    /** Round 8: a queued job only ever moved back in line (worker re-rank); one queued while its node ranked late (far, a coarse
     * stand-in, behind the camera) stayed there when the node became urgent, behind tens of thousands of jobs: the node showed as
     * a hole for 10 s and more. True when the job should be queued again at {@code prio} (the old one is then skipped). */
    private static boolean promote(final @Nullable Job job, final double prio) {
        return PROMOTE && job != null && prio < job.priority - 32.0 && prio < job.priority * 0.75;
    }

    static final boolean PROMOTE = Lod.flag("mc.lod.promote", true);
    static final boolean NEAR_FIRST = Lod.flag("mc.lod.nearFirst", true);

    private final ThreadLocal<SectionBufferBuilderPack> packs = ThreadLocal.withInitial(SectionBufferBuilderPack::new);
    // Round 6 mesh cache (LodMeshCache): opened lazily at the first mesh (the atlas size is part of its fingerprint).
    private boolean cacheAo;
    private boolean cacheCutout;
    private final net.minecraft.server.packs.resources.ResourceManager cacheResources;
    private volatile @Nullable LodMeshCache meshCache;
    private volatile boolean meshCacheTried;

    private @Nullable LodMeshCache meshCache() {
        if (!LodMeshCache.ENABLED || !Lod.DISK) {
            return null;
        }
        if (!this.meshCacheTried) {
            synchronized (this) {
                if (!this.meshCacheTried) {
                    int w = TerrainGreedy.atlasWidth();
                    int h = TerrainGreedy.atlasHeight();
                    if (w <= 0 || h <= 0) {
                        return null;
                    }
                    try {
                        String context = LodMeshCacheContext.describe(this.cacheResources, this.level);
                        String fp = LodMeshCache.fingerprint(this.blendRadius, this.cacheAo, this.cacheCutout, w, h, context);
                        this.meshCache = LodMeshCache.open(this.storeDir.resolve("mesh-cache"), fp);
                    } catch (Exception e) {
                        Lod.LOGGER.warn("[lod] mesh cache disabled: cannot identify rendering inputs: {}", e.toString());
                    }
                    this.meshCacheTried = true;
                }
            }
        }
        return this.meshCache;
    }

    /** Round 6: the encoded blob load() would decode for this node (real data first, else generated), or null. */
    private byte @Nullable [] rawBlob(final int level, final int nx, final int nz) {
        boolean realAllowed = !Lod.GEN_ONLY || this.gen == null || level == 0 && this.isLoadedChunk(nx, nz);
        byte[] blob = realAllowed ? this.store.get(level, nx, nz) : null;
        if (blob == null) {
            LodStore g = this.genStore;
            blob = g == null ? null : g.get(level, nx, nz);
        }
        return blob;
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, Boolean> BED_STATES = new java.util.concurrent.ConcurrentHashMap<>();
    private static final boolean OPEN_DIAG = Boolean.getBoolean("mc.lod.openDiag");

    private void mesh(final Node n, final long stamp, final boolean flat) throws IOException {
        n.realUpgrade = false;
        long t0 = System.nanoTime();
        LodMeshCache cache = this.meshCache();
        boolean strip = flat && LodTrees.FLAT0 && LodTrees.ENABLED && LodTrees.spritesAvailable();
        byte[] ckey = null;
        LodMeshCache.Inputs inputs = null;
        LodMeshCache.Entry cached = null;
        if (cache != null) {
            inputs = LodMeshCache.capture(n.level, n.nx, n.nz, flat, strip, (x, z) -> this.rawBlob(n.level, x, z));
            if (inputs.blobs()[4] != null) {
                ckey = inputs.key();
                LodMeshCache.Entry e = cache.get(n.level, n.nx, n.nz, ckey);
                cached = e;
                if (e != null && !LodMeshCache.VERIFY && !this.closed) {
                    if (LodShadow.ENABLED) {
                        LodShadow.write(this.load(n.level, n.nx, n.nz));   // the far shadows' heights (no mesh, but the data)
                    }
                    n.yMin = e.yMin();
                    n.yMax = e.yMax();
                    if (ATOMIC_MESH_HANDOFF) this.publishMesh(n, e.sections(), stamp);
                    else {
                        n.pending = e.sections();
                        n.pendingStamp = stamp;
                        n.meshStamp = stamp;
                        this.uploads.add(n);
                    }
                    this.cacheHits.incrementAndGet();
                    this.meshNanos.addAndGet(System.nanoTime() - t0);
                    return;
                }
            }
        }
        LodData[] region;
        if (inputs != null) {
            region = SHARED_DECODED ? inputs.decode((i, b) -> this.decodeShared(key(n.level, n.nx + i % 3 - 1, n.nz + i / 3 - 1), b)) : inputs.decode();
        } else {
            region = new LodData[9];
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    region[(dz + 1) * 3 + dx + 1] = this.load(n.level, n.nx + dx, n.nz + dz);
                }
            }
        }
        if (this.closed) {
            return;
        }
        if (region[4] == null) {
            if (ATOMIC_MESH_HANDOFF) this.publishMesh(n, List.of(), stamp);
            else {
                n.meshStamp = stamp;
                n.pending = List.of();
                n.pendingStamp = stamp;
                this.uploads.add(n);
            }
            return;
        }
        extents(n, region[4]);
        LodShadow.write(region[4]);
        if (OPEN_DIAG && n.level == 0) {
            // Diagnostic: columns without any solid cell (a hole to the void under the water).
            LodData d = region[4];
            int open = 0, sx = -1, sz = -1;
            for (int col = 0; col < 256; col++) {
                boolean solid = false;
                for (int y = 0; y < d.height && !solid; y++) {
                    int id = d.states[y * 256 + col];
                    solid = id == LodData.FILLER_ID || LodData.stateOf(id).isSolidRender();
                }
                if (!solid) { open++; sx = col & 15; sz = col >> 4; }
            }
            if (open > 0) {
                System.out.println("[lod] openColumns node " + n.nx + "," + n.nz + " real " + this.store.has(0, n.nx, n.nz) + " count " + open
                    + " e.g. " + sx + "," + sz + " bottom " + LodData.stateOf(d.states[sz * 16 + sx]) + " height " + d.height + " minY " + d.cellMinY);
            }
        }
        if (LodData.LIGHT_DIAG && n.level == 0) {
            LodData d = region[4];
            int plates = 0, yLo = 9999, yHi = -9999;
            for (int i = 0; i + 256 < d.states.length; i++) {
                if (d.states[i] == LodData.FILLER_ID && d.states[i + 256] != LodData.FILLER_ID && !LodData.stateOf(d.states[i + 256]).isSolidRender()) {
                    int y = i / 256 + d.cellMinY;
                    if (y > 70) {
                        plates++;
                        yLo = Math.min(yLo, y);
                        yHi = Math.max(yHi, y);
                    }
                }
            }
            if (plates > 4 && this.plateLogged++ < 80) {
                Lod.LOGGER.info("[lod] plateDiag {},{} filler tops above y70: {} y {}..{} real={} above0={}", n.nx, n.nz, plates, yLo, yHi, this.store.has(0, n.nx, n.nz),
                    "");
            }
        }
        LodRegion view = new LodRegion(this.level, region, this.blendRadius, this::biome);
        if (flat && LodTrees.FLAT0 && LodTrees.ENABLED) {
            view.stripTrees = strip;   // round 4: flat (far) level 0 draws its trees as stand-ins too
        }
        long tp1 = System.nanoTime();
        List<LodMesher.Section> sections = LodMesher.mesh(view, region, n.level >= Lod.AO_LEVEL || flat ? this.flatCompiler : this.compiler, this.packs.get(), flat);
        if (MESH_PROF) {
            long tp2 = System.nanoTime();
            int lv = n.level == 0 && flat ? 5 : Math.min(4, n.level);   // slot 5: flat level 0
            PROF_LOAD.addAndGet(lv, tp1 - t0);
            PROF_MESH.addAndGet(lv, tp2 - tp1);
            PROF_N.incrementAndGet(lv);
            if (PROF_ALL.incrementAndGet() % 3000L == 0L) {
                StringBuilder b = new StringBuilder("[lod] meshProf");
                for (int i = 0; i < 6; i++) {
                    long c = Math.max(1L, PROF_N.get(i));
                    b.append(String.format(" L%d n=%d load=%.1f mesh=%.1f |", i, PROF_N.get(i), PROF_LOAD.get(i) / 1e6 / c, PROF_MESH.get(i) / 1e6 / c));
                }
                System.out.println(b);
            }
        }
        if (OPEN_DIAG && n.level == 0) {
            // Diagnostic: nodes with a deep pit under water: bed heights against the slabs that got solid quads.
            LodData d = region[4];
            int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE, wet = 0;
            for (int col = 0; col < 256; col++) {
                boolean water = false;
                for (int y = d.height - 1; y >= 0; y--) {
                    int id = d.states[y * 256 + col];
                    if (id == LodData.AIR_ID) continue;
                    if (id != LodData.FILLER_ID && !LodData.stateOf(id).getFluidState().isEmpty()) { water = true; continue; }
                    if (water) { wet++; lo = Math.min(lo, y + d.cellMinY); hi = Math.max(hi, y + d.cellMinY); }
                    if (water) {
                        String key = (id == LodData.FILLER_ID ? "FILLER" : String.valueOf(LodData.stateOf(id))) + (this.store.has(0, n.nx, n.nz) ? " real" : " gen");
                        if (BED_STATES.putIfAbsent(key, Boolean.TRUE) == null) System.out.println("[lod] bedState " + key + " at node " + n.nx + "," + n.nz + " y " + (y + d.cellMinY));
                    }
                    break;
                }
            }
            if (wet > 0 && hi - lo >= 12) {
                StringBuilder sb = new StringBuilder();
                for (LodMesher.Section sec : sections) sb.append(' ').append(sec.originY).append(':').append(sec.quads[0]).append('/').append(sec.quads[2]);
                System.out.println("[lod] pit node " + n.nx + "," + n.nz + " real " + this.store.has(0, n.nx, n.nz) + " bed " + lo + ".." + hi
                    + " wet " + wet + " slabs(y:solid/translucent)" + sb);
            }
        }
        if (this.closed) {
            return;
        }
        if (cached != null) {
            cache.verify(cached, n.yMin, n.yMax, sections);
        }
        if (ckey != null) {
            cache.put(n.level, n.nx, n.nz, ckey, n.yMin, n.yMax, sections);
        }
        if (ATOMIC_MESH_HANDOFF) this.publishMesh(n, sections, stamp);
        else {
            n.pending = sections;
            n.pendingStamp = stamp;
            n.meshStamp = stamp;
            this.uploads.add(n);
        }
        this.meshed.incrementAndGet();
        this.meshNanos.addAndGet(System.nanoTime() - t0);
    }

    /** Called by a node's serialized mesh job, before meshQueued is released. No render-thread I/O or copying.
     * The mesher/cache finish filling section buffers before publication; subsequent users only read those bytes.
     * This freezes the list container, not the arrays inside a Section. Input/version and extent coherence remain separate.
     */
    private void publishMesh(final Node n, final List<LodMesher.Section> sections, final long requestedStamp) {
        List<LodMesher.Section> ready = List.copyOf(sections);
        long bytes = 0;
        for (LodMesher.Section s : ready) bytes = Math.addExact(bytes, s.bytes());
        // Publish the scheduling marker BEFORE the packet becomes claimable. A later GPU eviction may
        // reset it; producer completion must never overwrite that reset and strand an unresident node.
        n.meshStamp = requestedStamp;
        try {
            if (!this.meshHandoff.offer(n, ready, requestedStamp, bytes)) n.meshStamp = -1L;
        } catch (RuntimeException | Error failure) {
            n.meshStamp = -1L;
            throw failure;
        }
    }

    Holder<Biome> biome(final String key) {
        Holder<Biome> h = this.biomeCache.get(key);
        if (h == null) {
            try {
                h = this.level.registryAccess().lookupOrThrow(Registries.BIOME).get(ResourceKey.create(Registries.BIOME, Identifier.parse(key)))
                    .map(r -> (Holder<Biome>)r).orElse(this.fallbackBiome);
            } catch (RuntimeException e) {
                h = this.fallbackBiome;
            }
            this.biomeCache.put(key, h);
        }
        return h;
    }

    // ---------------------------------------------------------------------------------------------------------------- selection

    /** Render thread: this frame's camera for the scheduler. */
    void postCamera(final Snap s) {
        // Round 2: a camera that is not finite (e.g. a projection not set up yet: K NaN or 0) would select level 0 for every
        // node of the disc (wantedLevel(NaN) = 0) and schedule ~10^5 level-0 meshes (seen as 440 s settles, 0.9 GB resident).
        if (!Double.isFinite(s.x()) || !Double.isFinite(s.y()) || !Double.isFinite(s.z()) || !Double.isFinite(s.k()) || !Double.isFinite(s.kWide())
            || s.k() <= 1.0 || s.kWide() <= 1.0) {
            return;
        }
        this.snap = s;
    }

    private double distanceToCamera(final double x, final double z) {
        Snap s = this.snap;
        if (s == null) {
            return 0.0;
        }
        double dx = x - s.x();
        double dz = z - s.z();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Flat level-0 hysteresis factor ({@code mc.lod.flat0Hyst}, default 1.15): a node turns flat below px, back above px * h. */
    static final double FLAT0_HYST = Math.max(1.0, Double.parseDouble(System.getProperty("mc.lod.flat0Hyst", "1.8")));

    /** Selection in near-first order (draw order); runtime toggle for A/B. */
    public static volatile boolean sortNear = Lod.flag("mc.lod.sortNear", false);
    private final List<Node> selNodes = new ArrayList<>();
    private final List<Boolean> selInside = new ArrayList<>();
    private int selPendingMeshes;
    private int selRefused;
    /** Selection's GPU bytes: resident selected nodes + estimates for the pending ones (per-level average mesh size). */
    private long selBytes;
    /**
     * Round 1b: GPU-budget control. tau used = {@code Lod.TAU * tauScale}; the scheduler raises the scale (coarser selection)
     * while the selection would exceed {@link #BUDGET_HIGH} of {@code mc.lod.gpuMB} and lowers it slowly below
     * {@link #BUDGET_LOW}, so a too-fine tau never ends in refused uploads and evict / re-mesh churn.
     */
    private volatile double tauScale = 1.0;
    private long tauChangedNanos;
    private static final double BUDGET_HIGH = 0.75;
    private static final double BUDGET_LOW = 0.45;
    private final double[] meshBytesAvg = new double[8];
    private final long[] meshBytesCount = new long[8];

    /** Render thread: a node's mesh became resident with {@code bytes} (running per-level average for the estimates). */
    void noteMeshBytes(final int level, final long bytes) {
        synchronized (this.meshBytesAvg) {
            int l = Math.min(7, level);
            long c = ++this.meshBytesCount[l];
            this.meshBytesAvg[l] += (bytes - this.meshBytesAvg[l]) / Math.min(c, 256L);
        }
    }

    private double meshBytesEstimate(final int level) {
        synchronized (this.meshBytesAvg) {
            int l = Math.min(7, level);
            return this.meshBytesCount[l] == 0 ? 16.0 * 1024.0 : this.meshBytesAvg[l];
        }
    }

    double tau() {
        return Lod.TAU * this.tauScale * this.fpsScale * Lod.qualityScale();
    }

    /**
     * FPS target ({@code mc.lod.targetFps}, 0 = off): far cells coarsen (up to {@link #FPS_SCALE_MAX}x) while the frame rate is
     * under the target and refine back while it is over it with few meshes pending (refining while loading would only add
     * work). The way Voxy holds its frame rate (it trades its subdivision size for ~60 FPS).
     */
    static final int TARGET_FPS = Integer.getInteger("mc.lod.targetFps", 200);
    static final double FPS_SCALE_MAX = Double.parseDouble(System.getProperty("mc.lod.fpsScaleMax", "4.0"));
    static final long QUAD_FLOOR = Long.getLong("mc.lod.fpsQuadFloor", 5_000_000L);
    private volatile double fpsScale = 1.0;
    private long fpsAdaptNanos;

    private void adaptFps(final long now) {
        if (TARGET_FPS <= 0) {
            return;
        }
        long last = this.fpsAdaptNanos;
        this.fpsAdaptNanos = now;
        if (last == 0L) {
            return;
        }
        double dt = Math.min(0.5, (now - last) / 1e9);
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc == null || mc.isPaused()) {
            return;
        }
        int fps = mc.getFps();
        int limit = mc.options.framerateLimit().get();
        double target = limit > 0 && limit < 260 ? Math.min(TARGET_FPS, limit * 0.9) : TARGET_FPS;
        double old = this.fpsScale;
        // coarser only while the far terrain is what costs: ~1 ms of GPU per million quads, so under QUAD_FLOOR it isn't the
        // reason for a low frame rate (the scale would run to its maximum for nothing, e.g. CPU-bound while flying)
        if (fps < target * 0.95 && this.gpu.drawnQuads() > QUAD_FLOOR) {
            this.fpsScale = Math.min(FPS_SCALE_MAX, old * (1.0 + 0.25 * dt));
        } else if (fps > target * 1.1 && this.selPendingMeshes < 300 || this.gpu.drawnQuads() < QUAD_FLOOR * 0.8) {
            this.fpsScale = Math.max(1.0, old / (1.0 + 0.1 * dt));
        }
    }

    /** Scheduler, after a selection: adapts {@link #tauScale} to the GPU budget. */
    private void adaptTau(final long now) {
        long known = 0L;
        synchronized (this.meshBytesAvg) {
            for (long c : this.meshBytesCount) {
                known += c;
            }
        }
        if (known < 64L && this.selRefused == 0) {
            return;   // no mesh sizes yet: the estimate would be a guess
        }
        double use = (double)this.selBytes / Lod.GPU_BYTES;
        double old = this.tauScale;
        // Round 6: refusals with a selection well under the budget come from arena fragmentation, not from detail: raising tau
        // there ran away to tau 32 and oscillated 28.6 <-> 32 every 5 s (cold mesh-cache run, results/STAGE3-R6.md).
        if ((use > BUDGET_HIGH || this.selRefused > 0 && use > BUDGET_LOW) && now - this.tauChangedNanos > 500_000_000L && old < 16.0) {
            this.tauScale = Math.min(16.0, old * 1.25);
        } else if (use < BUDGET_LOW && old > 1.0 && now - this.tauChangedNanos > 5_000_000_000L) {
            this.tauScale = Math.max(1.0, old / 1.12);
        }
        if (this.tauScale != old) {
            this.tauChangedNanos = now;
            Lod.LOGGER.info("[lod] GPU budget: selection {} MB of {} MB, tau {} -> {} px", this.selBytes >> 20, Lod.GPU_BYTES >> 20,
                String.format(java.util.Locale.ROOT, "%.2f", Lod.TAU * old), String.format(java.util.Locale.ROOT, "%.2f", Lod.TAU * this.tauScale));
        }
    }

    /**
     * Scheduler: the quadtree selection (docs/stage3/LOD-DESIGN.md §5.1). Level wanted for a node = floor(log2(tau * d / K))
     * clamped to [0, maxLevel] (d = horizontal distance to the node box, K = focal length in panel px of this frame inside the
     * view, of the unzoomed option FOV outside it). Nodes overlapping vanilla's render square split to level 0; level-0 nodes
     * inside vanilla's render distance are kept with insideRd (the render thread drops them while the chunk is loaded).
     * Coarse until fine: a split node with an incomplete subtree is drawn itself if resident (never across the real area).
     */
    private void select(final Snap s) {
        this.selNodes.clear();
        this.selInside.clear();
        this.selPendingMeshes = 0;
        this.selRefused = 0;
        this.selBytes = 0L;
        int top = Lod.MAX_LEVEL;
        int span = 1 << top;
        int ccx = (int)Math.floor(s.x() / 16.0);
        int ccz = (int)Math.floor(s.z() / 16.0);
        int r = Lod.RADIUS;
        long now = System.nanoTime();
        for (int nz = Math.floorDiv(ccz - r, span); nz <= Math.floorDiv(ccz + r, span); nz++) {
            for (int nx = Math.floorDiv(ccx - r, span); nx <= Math.floorDiv(ccx + r, span); nx++) {
                this.visit(s, top, nx, nz, ccx, ccz, now, this.selNodes, this.selInside);
            }
        }
        if (HOLE_DIAG && this.holeLogged > 0 || HOLE_DIAG && now - this.holeLogNanos > 2_000_000_000L) {
            if (this.holeCount > 0) {
                Lod.LOGGER.info("[lod] holes: {} L0 nodes near without a mesh, queue {}", this.holeCount, this.queue.size());
                java.util.TreeMap<String, int[]> census = new java.util.TreeMap<>();
                double head = Double.NaN;
                for (Job j : this.queue.toArray(new Job[0])) {
                    String name = j.getClass().getName();
                    int[] c = census.computeIfAbsent(name.substring(name.lastIndexOf('$') + 1) + (j.node != null ? "L" + j.node.level : ""), k -> new int[4]);
                    c[j.priority < 10.0 ? 0 : j.priority < 100.0 ? 1 : j.priority < 1000.0 ? 2 : 3]++;
                }
                Job h = this.queue.peek();
                StringBuilder sb = new StringBuilder();
                census.forEach((k, c) -> sb.append(k).append('=').append(java.util.Arrays.toString(c)).append(' '));
                Lod.LOGGER.info("[lod] queue census (<10,<100,<1000,rest) head {} inFlight {}: {}", h == null ? Double.NaN : h.priority, this.inFlight.get(), sb);
            }
            if (now - this.holeLogNanos > 2_000_000_000L) {
                this.holeLogNanos = now;
            }
            this.holeLogged = 0;
        }
        this.holeCount = 0;
        java.util.Set<Long> t = this.splitLast;
        this.splitLast = this.splitNow;
        this.splitNow = t;
        this.splitNow.clear();
        Node[] arr = this.selNodes.toArray(Node[]::new);
        long bytes = 0L;
        for (Node n : arr) {
            bytes += n.resident ? n.gpuBytes : (long)this.meshBytesEstimate(n.level);
        }
        this.selBytes = bytes;
        boolean[] inside = new boolean[arr.length];
        for (int i = 0; i < inside.length; i++) {
            inside[i] = this.selInside.get(i);
        }
        if (sortNear && arr.length > 1) {
            // Near-first draw order (keyed to the camera chunk, so it only changes when the selection may change anyway):
            // lets the GPU depth-reject farther cutout fragments; opaque hidden-surface removal is order-independent.
            long[] keyed = new long[arr.length];
            for (int i = 0; i < arr.length; i++) {
                Node n = arr[i];
                long cx = 2L * n.nx + 1 << n.level, cz = 2L * n.nz + 1 << n.level;   // node centre, half-chunks
                long dx = cx - 2L * ccx - 1, dz = cz - 2L * ccz - 1;
                keyed[i] = Math.min(dx * dx + dz * dz, (1L << 42) - 1) << 21 | i;
            }
            Arrays.sort(keyed);
            Node[] sorted = new Node[arr.length];
            boolean[] sortedInside = new boolean[arr.length];
            for (int i = 0; i < arr.length; i++) {
                int j = (int)(keyed[i] & (1 << 21) - 1);
                sorted[i] = arr[j];
                sortedInside[i] = inside[j];
            }
            arr = sorted;
            inside = sortedInside;
        }
        Selection old = this.selection;
        if (!Arrays.equals(old.nodes(), arr) || !Arrays.equals(old.insideRd(), inside)) {
            this.selection = new Selection(arr, inside, ++this.selectionVersion);
        }
    }

    /** Returns true if the subtree is complete (every node with data that should be drawn is resident and current). */
    private boolean visit(final Snap s, final int level, final int nx, final int nz, final int ccx, final int ccz, final long now, final List<Node> out,
        final List<Boolean> inside) {
        int chunks = 1 << level;
        int cx0 = nx * chunks;
        int cz0 = nz * chunks;
        double x0 = cx0 * 16.0;
        double z0 = cz0 * 16.0;
        double size = chunks * 16.0;
        double dx = Math.max(0.0, Math.max(x0 - s.x(), s.x() - (x0 + size)));
        double dz = Math.max(0.0, Math.max(z0 - s.z(), s.z() - (z0 + size)));
        double d = Math.sqrt(dx * dx + dz * dz);
        double rBlocks = Lod.RADIUS * 16.0;
        if (d > rBlocks) {
            return true;
        }
        boolean inView = this.inView(s, x0, z0, size);
        double k = inView ? s.k() : s.kWide();
        // Round 3: per-level tau (Lod.TAU_LEVELS): this node may stay at its level while its cell is at most tau(level) px.
        double wantExact = wantedLevelExact(this.tau() * Lod.tauFactor(level), d, k);
        if (Lod.area) {
            double a = this.areaLevel(s, level, nx, nz, x0, z0, size, k, this.tau() * Lod.tauFactor(level));
            if (!Double.isNaN(a)) {
                wantExact = a;
            }
        }
        boolean wasSplit = this.splitLast.contains(key(level, nx, nz));
        boolean wantsSplit = level > Lod.MAX_LEVEL || wantExact < level + (wasSplit ? HYSTERESIS : -HYSTERESIS);
        // Round 1b: split to level 0 only where the node holds a chunk vanilla may render (the same round test as insideRd
        // below, ChunkTrackingView.isInViewDistance): coarse nodes may touch the real disc but never cover it.
        long ndx = Math.max(0, Math.abs(Math.max(cx0, Math.min(cx0 + chunks - 1, ccx)) - ccx) - 1);
        long ndz = Math.max(0, Math.abs(Math.max(cz0, Math.min(cz0 + chunks - 1, ccz)) - ccz) - 1);
        // Round 7: with the per-section near handoff (LodGpu#nearHandoff) split NEAR_SPLIT_MARGIN chunks further: vanilla's
        // drawn disc follows its own (lagging) centre, and a chunk it still draws under a coarse node showed both waters.
        long rdSplit = s.rd() + (LodGpu.nearHandoff ? NEAR_SPLIT_MARGIN : 0);
        boolean overlapsRd = ndx * ndx + ndz * ndz < rdSplit * rdSplit;
        if (level > 0 && (wantsSplit || overlapsRd)) {
            this.splitNow.add(key(level, nx, nz));
            int mark = out.size();
            boolean complete = true;
            for (int cz = 0; cz < 2; cz++) {
                for (int cxx = 0; cxx < 2; cxx++) {
                    complete &= this.visit(s, level - 1, nx * 2 + cxx, nz * 2 + cz, ccx, ccz, now, out, inside);
                }
            }
            // a stand-in only where it is at most STANDIN_GAP levels coarser than wanted here (an L4 box a few hundred
            // blocks out reads as a flat plate; the fog edge looks better until the fine nodes arrive)
            boolean standIn = level - wantExact <= STANDIN_GAP;
            if (!complete && !overlapsRd && COARSE_FALLBACK && standIn) {
                this.fallback(s, level, nx, nz, d, inView, now);
            }
            if (!complete && !overlapsRd && standIn) {
                Node self = this.existing(level, nx, nz);
                if (self != null && self.resident) {
                    while (out.size() > mark) {
                        out.remove(out.size() - 1);
                        inside.remove(inside.size() - 1);
                    }
                    out.add(self);
                    inside.add(false);
                    // Round 8: covered by the stand-in: the parent keeps its other (finer) children instead of replacing them all.
                    return COARSE_FALLBACK || complete;
                }
            }
            return complete;
        }
        // Leaf.
        LodGen g = this.gen;
        if (level == 1 && PREFETCH > 0 && d < (rdSplit + PREFETCH) * 16.0) {
            // Round 8: about to be split to level 0 by the render-distance ring, where no coarse node may stand in: get the
            // four level-0 nodes generated and meshed now, so the ring never waits for them (holes at its outer edge).
            for (int c = 0; c < 4; c++) {
                this.prefetch(nx * 2 + (c & 1), nz * 2 + (c >> 1), this.distRank(s, 0, d, inView, true) + 2.0, this.distRank(s, 0, d, inView, false) + 2.0, now);
            }
        }
        Node n = level == 0 ? (g != null || this.store.has(0, nx, nz) || this.existing(0, nx, nz) != null ? this.node(0, nx, nz) : null) : this.node(level, nx, nz);
        boolean built = false;
        if (n != null && !n.hasData && level > 0 && !n.downsampleTried) {
            // A level that never got data (e.g. a new mc.lod.maxLevel over an existing store): build it from its children.
            n.downsampleTried = true;
            built = !Lod.GEN_ONLY && this.ensureData(level, nx, nz, now);
            n.genFromChildren = built;
        }
        if (n != null && !n.hasData && g != null && (!n.genQueued || promote(n.genJob, this.distRank(s, level, d, inView, true) + level * 8.0))
            && !n.genFromChildren && !this.dirtyParents.containsKey(key(level, nx, nz))) {
            // Round 3: nothing real here (or below): generate the node from the seed at its own level.
            this.scheduleGen(n, this.distRank(s, level, d, inView, true) + level * 8.0, level * 8.0);
        }
        if (HOLE_DIAG && level == 0 && (n == null || !n.hasData) && d < (s.rd() + 4) * 16.0) {
            this.holeCount++;
            if (now - this.holeLogNanos > 2_000_000_000L && this.holeLogged < 12) {
                this.holeLogged++;
                Lod.LOGGER.info("[lod] hole L0 {},{} d={} NO DATA node={} genQueued={} gen={}", nx, nz, (int)d, n != null, n != null && n.genQueued, g != null);
            }
        }
        if (n == null || !n.hasData) {
            return n == null || !this.dirtyParents.containsKey(key(level, nx, nz)) && !n.genQueued;
        }
        boolean insideRd = false;
        if (level == 0) {
            long ddx = Math.max(0, Math.abs(nx - ccx) - 1);
            long ddz = Math.max(0, Math.abs(nz - ccz) - 1);
            insideRd = ddx * ddx + ddz * ddz < (long)s.rd() * s.rd();
        }
        out.add(n);
        inside.add(insideRd);
        if (level == 0 && Lod.FLAT0_PX > 0.0) {
            // Round 4: a level-0 node whose blocks are small on screen (this frame's K, zoom included) is meshed flat-lit, so its
            // faces merge like the coarse levels' (AO / smooth light at a few px per block); zooming in re-meshes it with AO.
            double px = k / Math.max(1.0, d);
            n.flat = n.flat ? px < Lod.FLAT0_PX * FLAT0_HYST : px < Lod.FLAT0_PX;
        }
        long stamp = this.stamp(n);
        boolean current = n.resident && n.gpuStamp == stamp
            || (ATOMIC_MESH_HANDOFF ? this.meshHandoff.hasStamp(n, stamp) : n.meshStamp == stamp && n.pending != null)
            || n.refusedStamp == stamp;
        if (n.refusedStamp == stamp) {
            this.selRefused++;
        }
        if (!current) {
            this.selPendingMeshes++;
            // First mesh as soon as the node itself is quiet; re-meshes (neighbour / data changes) once the 3x3 is quiet.
            boolean ready = n.meshStamp == -1L ? now - n.changedNanos > 300_000_000L : n.realUpgrade || this.settled(n, now);
            if ((!n.meshQueued || promote(n.meshJob, this.distRank(s, level, d, inView, !n.resident || n.realUpgrade) + level * 8.0)) && n.meshStamp != stamp && ready) {
                this.scheduleMesh(n, this.distRank(s, level, d, inView, !n.resident || n.realUpgrade) + level * 8.0, level * 8.0);
            }
        }
        if (HOLE_DIAG && level == 0 && !n.resident && d < (s.rd() + 4) * 16.0) {
            this.holeCount++;
            if (now - this.holeLogNanos > 2_000_000_000L && this.holeLogged < 12) {
                this.holeLogged++;
                Lod.LOGGER.info("[lod] hole L0 {},{} d={} inside={} real={} meshQueued={} meshStamp={} stamp={} refused={} quietMs={} settled={} pending={}",
                    nx, nz, (int)d, insideRd, this.store.has(0, nx, nz), n.meshQueued, n.meshStamp, stamp, n.refusedStamp == stamp,
                    (now - n.changedNanos) / 1_000_000L, this.settled(n, now), n.pending != null);
                Job mj = n.meshJob;
                Lod.LOGGER.info("[lod]   job prio {} inQueue {}", mj == null ? Double.NaN : mj.priority, mj != null && this.queue.contains(mj));
            }
        }
        return n.resident;
    }

    /** Round 8 ({@code mc.lod.prefetch}, chunks; 0 = off): level-0 nodes are prepared this far outside the forced level-0 ring. */
    static final int PREFETCH = Math.max(0, Integer.getInteger("mc.lod.prefetch", 4));

    private void prefetch(final int nx, final int nz, final double prioMissing, final double prioDrawn, final long now) {
        LodGen g = this.gen;
        if (g == null && !this.store.has(0, nx, nz) && this.existing(0, nx, nz) == null) {
            return;
        }
        Node n = this.node(0, nx, nz);
        double prio = n.resident ? prioDrawn : prioMissing;
        if (!n.hasData) {
            if (g != null && (!n.genQueued || promote(n.genJob, prio))) {
                this.scheduleGen(n, prio, 2.0);
            }
            return;
        }
        long stamp = this.stamp(n);
        boolean current = n.resident && n.gpuStamp == stamp
            || (ATOMIC_MESH_HANDOFF ? this.meshHandoff.hasStamp(n, stamp) : n.meshStamp == stamp && n.pending != null)
            || n.refusedStamp == stamp;
        if (!current && (!n.meshQueued || promote(n.meshJob, prio)) && n.meshStamp != stamp && (n.meshStamp == -1L ? now - n.changedNanos > 300_000_000L : this.settled(n, now))) {
            this.scheduleMesh(n, prio, 2.0);
        }
    }

    static final boolean HOLE_DIAG = Lod.flag("mc.lod.holeDiag", false);
    private long holeLogNanos;
    private int holeLogged, holeCount;

    /**
     * Round 8: node (level, nx, nz) is split but its subtree isn't drawable yet: get it data (seed generation, ranked ahead of
     * its children so the coarse cover arrives first) and a mesh, so visit can draw it until the finer nodes are ready.
     */
    private void fallback(final Snap s, final int level, final int nx, final int nz, final double d, final boolean inView, final long now) {
        LodGen g = this.gen;
        Node n = this.node(level, nx, nz);
        // A small edge over its own children only: with a large one, far coarse work starved the nearest level-0 nodes
        // (mesh jobs 60 blocks away waited seconds behind tens of thousands of stand-in jobs).
        double bias = -8.0 * level;
        double prio = d * (inView ? 0.5 : 1.0) + bias;
        if (level >= COARSE_FIRST && d > 768.0 && !n.resident) {
            // The few coarsest far nodes (about a thousand cover the whole radius) right after the ring's missing nodes: a
            // complete far horizon within seconds of joining and at the edge while flying, refined behind it.
            prio = 6.0 + d * 0.02;
        }
        if (!n.hasData) {
            if (g != null && !n.genQueued && !this.dirtyParents.containsKey(key(level, nx, nz))) {
                this.scheduleGen(n, prio, bias);
            }
            return;
        }
        long stamp = this.stamp(n);
        boolean current = n.resident && n.gpuStamp == stamp
            || (ATOMIC_MESH_HANDOFF ? this.meshHandoff.hasStamp(n, stamp) : n.meshStamp == stamp && n.pending != null)
            || n.refusedStamp == stamp;
        if (!current && !n.meshQueued && n.meshStamp != stamp && (n.resident || now - n.changedNanos > 100_000_000L)) {
            this.scheduleMesh(n, prio, bias);
        }
    }

    /** Round 6 (Lod#area): the surface's block-y range of node data {@code d}. */
    static void extents(final Node n, final LodData d) {
        int s = 1 << d.level;
        int baseY = d.cellMinY * s;
        int lo = Integer.MAX_VALUE;
        int hi = Integer.MIN_VALUE;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                for (int y = d.height - 1; y >= 0; y--) {
                    int id = d.states[LodData.index(x, y, z)];
                    if (id != LodData.AIR_ID) {
                        int top = (y + 1) * s + baseY;
                        lo = Math.min(lo, top - s);
                        hi = Math.max(hi, top);
                        // Round 8: through water down to its bed: the near handoff waits for the real sections in [yMin, yMax),
                        // and a lake whose bed lies a section below its surface was dropped before the bed was drawn (sky
                        // through the water).
                        if (id != LodData.FILLER_ID && !LodData.stateOf(id).getFluidState().isEmpty()) {
                            continue;
                        }
                        break;
                    }
                }
            }
        }
        if (lo > hi) {
            lo = hi = baseY;
        }
        n.yMin = lo;
        n.yMax = hi;
    }

    /** Extents of node (level, nx, nz): its own, else the union of its four children's if all are known; NaN-filled if unknown. */
    private float[] extentsOf(final int level, final int nx, final int nz) {
        Node n = this.existing(level, nx, nz);
        if (n != null && !Float.isNaN(n.yMin)) {
            return new float[]{n.yMin, n.yMax};
        }
        if (level == 0) {
            return null;
        }
        float lo = Float.POSITIVE_INFINITY;
        float hi = Float.NEGATIVE_INFINITY;
        for (int c = 0; c < 4; c++) {
            Node ch = this.existing(level - 1, nx * 2 + (c & 1), nz * 2 + (c >> 1));
            if (ch == null || !ch.hasData) {
                continue;   // no data there: nothing to draw
            }
            if (Float.isNaN(ch.yMin)) {
                return null;
            }
            lo = Math.min(lo, ch.yMin);
            hi = Math.max(hi, ch.yMax);
        }
        if (lo > hi) {
            return null;
        }
        if (n != null) {
            n.yMin = lo;
            n.yMax = hi;
        }
        return new float[]{lo, hi};
    }

    /**
     * Round 6 (Lod#area): the wanted level (as {@link #wantedLevelExact}) from the node's projected area: per box face its area times
     * the cosine toward the eye over the squared distance to the nearest box point, times K^2; cell px = sqrt(area) / 16. NaN if the
     * extents are unknown.
     */
    private double areaLevel(final Snap s, final int level, final int nx, final int nz, final double x0, final double z0, final double size,
        final double k, final double tau) {
        float[] e = this.extentsOf(level, nx, nz);
        if (e == null) {
            return Double.NaN;
        }
        double y0 = e[0];
        double y1 = Math.max(e[1], e[0] + 1.0);
        double dxn = Math.max(0.0, Math.max(x0 - s.x(), s.x() - (x0 + size)));
        double dyn = Math.max(0.0, Math.max(y0 - s.y(), s.y() - y1));
        double dzn = Math.max(0.0, Math.max(z0 - s.z(), s.z() - (z0 + size)));
        double dn = Math.sqrt(dxn * dxn + dyn * dyn + dzn * dzn);
        if (dn < 1.0) {
            return Double.NEGATIVE_INFINITY;
        }
        double vx = Math.abs(x0 + size * 0.5 - s.x());
        double vy = Math.abs((y0 + y1) * 0.5 - s.y());
        double vz = Math.abs(z0 + size * 0.5 - s.z());
        double vl = Math.max(1e-6, Math.sqrt(vx * vx + vy * vy + vz * vz));
        double h = y1 - y0;
        double a = (size * size * (vy / vl) + size * h * ((vx + vz) / vl)) * k * k / (dn * dn);
        double cellPx = Math.sqrt(Math.max(a, 1e-12)) / 16.0;
        return level + Math.log(tau / cellPx) / Math.log(2.0);
    }

    /**
     * True if node (level, nx, nz) has data or can be built from stored descendants; queues the downsamples bottom-up (a parent
     * built before all its children exist is rebuilt when they arrive: {@link #changed} marks it dirty again).
     */
    private boolean ensureData(final int level, final int nx, final int nz, final long now) {
        if (this.store.has(level, nx, nz)) {
            return true;
        }
        if (level == 0) {
            return false;
        }
        boolean any = false;
        for (int c = 0; c < 4; c++) {
            any |= this.ensureData(level - 1, nx * 2 + (c & 1), nz * 2 + (c >> 1), now);
        }
        if (any) {
            this.dirtyParents.putIfAbsent(key(level, nx, nz), now - 2_000_000_000L + level * 100_000_000L);
        }
        return any;
    }

    /** Conservative test of a node column box against the frame's five camera-relative planes. */
    private boolean inView(final Snap s, final double x0, final double z0, final double size) {
        double[] p = s.planes();
        if (p == null) {
            return true;
        }
        double mnx = x0 - s.x();
        double mxx = mnx + size;
        double mny = this.worldMinY - s.y();
        double mxy = mny + this.worldHeight;
        double mnz = z0 - s.z();
        double mxz = mnz + size;
        for (int i = 0; i < 5; i++) {
            double a = p[4 * i];
            double b = p[4 * i + 1];
            double c = p[4 * i + 2];
            double w = p[4 * i + 3];
            double px = a >= 0 ? mxx : mnx;
            double py = b >= 0 ? mxy : mny;
            double pz = c >= 0 ? mxz : mnz;
            if (a * px + b * py + c * pz + w < -8.0) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------------------------------------------------------- threads

    private void submit(final Job job) {
        if (!this.closed) {
            this.queue.add(job);
        }
    }

    private void workerLoop() {
        Lod.backgroundQos(Lod.QOS_WORKERS);
        while (!this.closed) {
            Job job;
            try {
                job = this.queue.poll(250, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return;
            }
            if (job == null) {
                continue;
            }
            Snap s = this.snap;
            if (REPRIORITIZE && job.node != null && s != null) {
                double r = this.rank(s, job.node, job.bias);
                if (Double.isNaN(r)) {
                    job.dropped();
                    continue;
                }
                if (r > job.priority + 32.0 && r > job.priority * 1.25) {
                    job.priority = r;   // fell behind the camera: back in line at its current rank
                    this.queue.add(job);
                    continue;
                }
            }
            this.inFlight.incrementAndGet();
            try {
                job.run(this);
            } catch (Throwable t) {
                Lod.LOGGER.warn("[lod] job failed", t);
            } finally {
                this.inFlight.decrementAndGet();
            }
        }
    }

    private void schedulerLoop() {
        long lastFrame = -1L;
        while (!this.closed) {
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                return;
            }
            Snap s = this.snap;
            if (s == null) {
                continue;
            }
            try {
                this.setupGen();
                if (Lod.SP_SOURCE && !this.spDone || this.spCenterX == Integer.MIN_VALUE) {
                    this.scanSingleplayer(s);
                } else if (Minecraft.getInstance().getSingleplayerServer() != null) {
                    int cx = (int)Math.floor(s.x() / 16.0);
                    int cz = (int)Math.floor(s.z() / 16.0);
                    if (Math.abs(cx - this.spCenterX) > 8 || Math.abs(cz - this.spCenterZ) > 8) {
                        this.scanSingleplayer(s);
                    }
                }
                this.scheduleDownsamples(s);
                if (WORLD_GEN) {
                    this.pumpWorldGen(s);
                }
                if (s.frame() != lastFrame) {
                    lastFrame = s.frame();
                    this.select(s);
                    this.adaptTau(System.nanoTime());
                    this.adaptFps(System.nanoTime());
                }
                long now = System.nanoTime();
                if (now - this.lastFlush > 15_000_000_000L) {
                    this.lastFlush = now;
                    this.store.flush();
                    LodStore gs = this.genStore;
                    if (gs != null) {
                        gs.flush();
                    }
                    LodMeshCache mcache = this.meshCache;
                    if (mcache != null) {
                        mcache.flush();
                    }
                }
                if (Lod.STATS_SECONDS > 0 && now - this.lastStats > Lod.STATS_SECONDS * 1_000_000_000L) {
                    this.lastStats = now;
                    Lod.LOGGER.info("[lod] {}", this.statsJson());
                }
            } catch (Throwable t) {
                Lod.LOGGER.warn("[lod] scheduler", t);
            }
        }
    }

    boolean idle() {
        return this.queue.isEmpty() && this.inFlight.get() == 0 && this.uploads.isEmpty()
            && (!ATOMIC_MESH_HANDOFF || this.meshHandoff.isEmpty()) && this.dirtyParents.isEmpty() && (this.spDone || !Lod.SP_SOURCE)
            && this.selPendingMeshes == 0 && this.gpu.idle();
    }

    long workerCpuNanos() {
        long sum = 0L;
        for (Thread t : this.workers) {
            long v = this.threadBean.getThreadCpuTime(t.threadId());
            if (v > 0) {
                sum += v;
            }
        }
        long v = this.threadBean.getThreadCpuTime(this.scheduler.threadId());
        return sum + Math.max(0L, v);
    }

    String statsJson() {
        String j = this.statsJson0();
        j = j.replaceFirst("}$", ", \"spUnlit\": " + this.spUnlit.get() + ", \"waterBorderCuts\": " + LodMesher.WATER_BORDER_CUTS.get() + "}");
        return LodGenAudit.ENABLED ? j.replaceFirst("}$", ", \"genAudit\": " + LodGenAudit.json() + "}") : j;
    }

    private String statsJson0() {
        double up = (System.nanoTime() - this.startNanos) / 1e9;
        Selection sel = this.selection;
        int[] perLevel = new int[Lod.MAX_LEVEL + 1];
        for (Node n : sel.nodes()) {
            perLevel[n.level]++;
        }
        return String.format(java.util.Locale.ROOT,
            "{\"sectionUploads\": " + Lod.SECTION_UPLOADS.get() + ", \"fadeSkips\": " + Lod.FADE_SKIPS.get() + ", \"exactChunks\": " + this.exactChunks.get()
                + ", \"exactBatchMsAvg\": " + String.format(java.util.Locale.ROOT, "%.1f", this.exactNanos.get() / 1e6 / Math.max(1L, this.exactChunks.get() / Math.max(1, LodExactGen.BATCH * LodExactGen.BATCH))) + ", \"exactStepMs\": " + LodExactGen.stepStats() + ", \"exactChunksPerS\": " + String.format(java.util.Locale.ROOT, "%.1f", this.exactChunks.get() / Math.max(1e-9, (System.nanoTime() - this.startNanos) / 1e9)) + ", \"store\": \"%s\", \"uptimeS\": %.1f, \"queue\": %d, \"inFlight\": %d, \"captured\": %d, \"spRead\": %d, \"spAbsent\": %d, \"spDone\": %s, "
                + "\"worldGen\": %d, \"worldGenWanted\": %d, \"downsampled\": %d, \"meshed\": %d, \"meshMsAvg\": %.2f, \"encodeMsAvg\": %.3f, \"readMsAvg\": %.3f, \"downMsAvg\": %.2f, "
                + "\"workerCpuS\": %.1f, \"selected\": %d, \"selectedPerLevel\": %s, \"pendingMeshes\": %d, \"refusedOverBudget\": %d, \"nodes\": %d, \"storeMemMB\": %.1f, "
                + "\"storeRegions\": %d, \"diskWrites\": %d, \"meshesScheduled\": %s, \"downsamplesScheduled\": %d, \"tau\": %.2f, \"fpsScale\": %.2f, \"gpu\": %s, \"idle\": %s}",
            this.storeLabel, up, this.queue.size(), this.inFlight.get(), this.captured.get(), this.spRead.get(), this.spAbsent.get(), this.spDone,
            this.worldGenerated.get(), this.genWanted.size(), this.downsampled.get(), this.meshed.get(), this.meshNanos.get() / 1e6 / Math.max(1L, this.meshed.get()),
            this.encodeNanos.get() / 1e6 / Math.max(1L, this.captured.get() + this.spRead.get()), this.readNanos.get() / 1e6 / Math.max(1L, this.spRead.get()),
            this.downNanos.get() / 1e6 / Math.max(1L, this.downsampled.get()), this.workerCpuNanos() / 1e9, sel.nodes().length, Arrays.toString(perLevel),
            this.selPendingMeshes, this.selRefused, this.nodes.size(), this.store.memoryBytes() / 1048576.0, this.store.regionCount(), this.store.diskWrites, this.meshesScheduled.toString(),
            this.downsamplesScheduled.get(), this.tau(), this.fpsScale, this.gpu.statsJson(),
            this.idle()).replaceFirst("}$", ", \"meshCache\": " + (this.meshCache == null ? "null" : this.meshCache.statsJson()) + ", \"cacheHits\": " + this.cacheHits.get() + ", \"decodeLoads\": " + this.loads.get() + ", \"decodeHits\": " + this.loadHits.get() + "}").replaceFirst("}$", ", \"gen\": " + (this.gen == null ? "null" : this.gen.statsJson().replaceFirst("}$", String.format(java.util.Locale.ROOT,
                ", \"jobs\": %d, \"overlays\": %d, \"storeMemMB\": %.1f, \"diskMB\": %.1f}", this.genJobs.get(), this.genOverlays.get(),
                this.genStore == null ? 0.0 : this.genStore.memoryBytes() / 1048576.0, this.genStore == null ? 0.0 : this.genStore.trackedDiskBytes() / 1048576.0))) + "}")
            .replaceFirst("}$", ", \"meshHandoff\": " + this.meshHandoffStats() + "}");
    }

    private String meshHandoffStats() {
        if (!ATOMIC_MESH_HANDOFF) return "{\"enabled\":false}";
        var s = this.meshHandoff.stats();
        return "{\"enabled\":true,\"closed\":" + s.closed() + ",\"pending\":" + s.pending()
            + ",\"pendingBytes\":" + s.pendingBytes() + ",\"offers\":" + s.offers()
            + ",\"coalesced\":" + s.coalesced() + ",\"claims\":" + s.claims() + ",\"rejected\":" + s.rejected() + "}";
    }

    void forEachNode(final java.util.function.Consumer<Node> action) {
        this.nodes.values().forEach(action);
    }

    long diskBytes() {
        return this.store.diskBytes();
    }

    void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        if (this.exactPool != null) this.exactPool.shutdownNow();
        if (this.exactWorkers != null) this.exactWorkers.shutdownNow();
        if (ATOMIC_MESH_HANDOFF) this.meshHandoff.close();
        this.queue.clear();
        try {
            for (Thread t : this.workers) {
                t.join(2000L);
            }
            this.scheduler.join(2000L);
        } catch (InterruptedException ignored) {
        }
        this.store.flush();
        LodStore gs = this.genStore;
        if (gs != null) {
            gs.flush();
        }
        LodMeshCache mcache = this.meshCache;
        if (mcache != null) {
            mcache.flush();
        }
        if (Minecraft.getInstance().isSameThread()) {
            this.gpu.close();
        }
        Lod.LOGGER.info("[lod] closed {}: disk {} MB", this.storeLabel, String.format(java.util.Locale.ROOT, "%.1f", this.store.diskBytes() / 1048576.0));
    }
}
