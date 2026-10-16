package net.minecraft.client.renderer.chunk;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Stage 3 round 1 (docs/stage3/LOD-DESIGN.md, CONTRACT 16.S3-LOD): far terrain beyond the real render distance. Entry
 * points the patched vanilla classes call (one line each, signatures unchanged) and the switches. Off by default
 * ({@code -Dmc.lod=true} builds it); with it off every hook returns at its first test and nothing is allocated.
 *
 * <p>Client-side only: the LOD store is filled from chunks the client already has (received / loaded chunks; in
 * singleplayer also the integrated server's saved chunks, read through its own IO worker), never from requests: no packet is
 * sent or changed.
 *
 * <p>Switches (startup unless noted):
 * <ul>
 * <li>{@code mc.lod} (false): build the LOD system.</li>
 * <li>{@code mc.lod.radius} (64): LOD radius in chunks.</li>
 * <li>{@link #draw} ({@code mc.lod.draw}, true; abab-switchable, read once per frame): draw the LOD terrain and extend the
 * far plane and the render-distance fog to the LOD radius; false = vanilla's image at the real render distance (the data
 * and meshes stay resident).</li>
 * <li>{@code mc.lod.tau} (16.0; round 1b, was 2): screen-space error target in panel pixels per LOD cell (level choice, ZOOM.md §3).</li>
 * <li>{@code mc.lod.threads} (cores - 6, within 3..6): LOD worker threads (minimum priority).</li>
 * <li>{@code mc.lod.sp} (true): singleplayer: also read the integrated server's saved chunks inside the LOD radius.</li>
 * <li>{@code mc.lod.disk} (true): keep the LOD store on disk ({@code <game dir>/lod/...}).</li>
 * <li>{@code mc.lod.gpuMB} (1024): GPU budget of LOD meshes; {@code mc.lod.ramMB} (384): store blobs kept in memory.</li>
 * <li>{@code mc.lod.maxLevel} (5): coarsest level (cell = 2^level blocks).</li>
 * <li>{@code mc.lod.stats} (0): log the LOD state every N seconds.</li>
 * <li>{@link #fog} ({@code mc.lod.fog}, true; abab-switchable): LOD fog (CONTRACT 16.S3-LOD-FOG): vanilla fog up to the real
 * render distance, beyond it the environmental fog grows as if the LOD edge were {@code mc.lod.fogDist} (320) blocks away,
 * and the last {@code mc.lod.fogEdge} (0.2) of the LOD radius fades into the fog / horizon colour. false = round 1's fog
 * (vanilla's at render distance = LOD radius: everything past ~1024 blocks fully fogged in the overworld).</li>
 * </ul>
 */
public final class Lod {
    static final Logger LOGGER = LogUtils.getLogger();

    /**
     * {@code -Dmc.lod.qos} (default 0x11 = QOS_CLASS_UTILITY, 0 = off): macOS QoS class of the calling LOD background thread.
     * Java priorities are ignored on macOS; a utility/background QoS keeps worker threads on the efficiency cores so the
     * render thread, the driver and the server keep the performance cores.
     */
    static final int QOS = Integer.decode(System.getProperty("mc.lod.qos", "0x11"));
    private static java.lang.invoke.@Nullable MethodHandle qosHandle;
    private static boolean qosFailed;

    /** {@code -Dmc.lod.qosWorkers}: QoS of the mesh/read workers (default: {@link #QOS}); exact generation always uses QOS. */
    static final int QOS_WORKERS = Integer.decode(System.getProperty("mc.lod.qosWorkers", System.getProperty("mc.lod.qos", "0x11")));

    static void backgroundQos() {
        backgroundQos(QOS);
    }

    static void backgroundQos(final int qos) {
        if (qos == 0 || qosFailed) {
            return;
        }
        try {
            java.lang.invoke.MethodHandle h = qosHandle;
            if (h == null) {
                java.lang.foreign.Linker linker = java.lang.foreign.Linker.nativeLinker();
                h = linker.downcallHandle(linker.defaultLookup().find("pthread_set_qos_class_self_np").orElseThrow(),
                    java.lang.foreign.FunctionDescriptor.of(java.lang.foreign.ValueLayout.JAVA_INT, java.lang.foreign.ValueLayout.JAVA_INT, java.lang.foreign.ValueLayout.JAVA_INT));
                qosHandle = h;
            }
            int rc = (int)h.invokeExact(qos, 0);
            if (rc != 0) {
                LOGGER.info("[lod] qos {} failed: {}", qos, rc);
            }
        } catch (Throwable t) {
            qosFailed = true;
            LOGGER.info("[lod] qos unavailable: {}", t.toString());
        }
    }
    public static final boolean BUILD = flag("mc.lod", true);
    public static final int RADIUS = Math.max(4, Integer.getInteger("mc.lod.radius", 256));
    public static volatile boolean draw = flag("mc.lod.draw", true);
    /** Round 7 ({@code mc.lod.realListed}, default true, abab-switchable): a level-0 handoff also waits until vanilla listed
     * the chunk's sections for drawing (RenderSection#lodListed). */
    public static volatile boolean realListed = Lod.flag("mc.lod.realListed", true);
    /**
     * Diagnostics (abab-switchable, read per draw): LOD layers drawn, bit 0 SOLID, 1 CUTOUT, 2 TRANSLUCENT (default 7). 0 keeps
     * the cull dispatch, the far plane and the fog of {@link #draw} without rasterizing any LOD quad (cost attribution).
     */
    public static volatile int layerMask = Integer.getInteger("mc.lod.layerMask", 7);
    /** Diagnostic: bit k draws LOD level k ({@code mc.lod.drawLevels}, default all; abab-switchable, rebuilds the items). */
    public static volatile int drawLevels = Integer.getInteger("mc.lod.drawLevels", 0xFF);
    /**
     * Round 4 ({@code mc.lod.fusedCull}, abab {@code Lod#fusedCull}): the LOD cull as one fused test + append dispatch per job
     * (concurrent) instead of test / single-threadgroup scan / write (CONTRACT 16.S3-LOD-R4).
     */
    public static volatile boolean fusedCull = flag("mc.lod.fusedCull", true);
    public static final double TAU = Double.parseDouble(System.getProperty("mc.lod.tau", "5.0"));
    public static final int THREADS = Math.max(1, Integer.getInteger("mc.lod.threads", Math.max(3, Math.min(6, Runtime.getRuntime().availableProcessors() - 6))));
    public static final boolean SP_SOURCE = flag("mc.lod.sp", true);
    public static final boolean DISK = flag("mc.lod.disk", true);
    public static final long GPU_BYTES = Math.max(64L, Long.getLong("mc.lod.gpuMB", 3072L)) << 20;
    public static final long RAM_BYTES = Math.max(16L, Long.getLong("mc.lod.ramMB", 384L)) << 20;
    public static final int MAX_LEVEL = Math.max(0, Math.min(7, Integer.getInteger("mc.lod.maxLevel", 5)));
    /** Round 1b (LodGreedy): merge LOD faces into greedy rectangles (drawn by the LOD greedy cluster pipelines). */
    public static final boolean MERGE = flag("mc.lod.merge", true);
    /** Greedy colour tolerance (max channel difference) at level 0 and at the coarser levels; -1 = no merging there. */
    public static final int MERGE_TOL0 = Integer.getInteger("mc.lod.mergeTol0", 4);
    public static final int MERGE_TOL = Integer.getInteger("mc.lod.mergeTol", 8);
    /**
     * Lit merge ({@code mc.lod.litMerge}): SOLID / CUTOUT faces merge across colour, light and AO differences; a merged face
     * whose cells differ carries a per-cell table of the original corner colours, light and triangle diagonal, and the LOD
     * fragment shader rebuilds each cell's vertex colour from it (LodGreedy, LodCompact, TerrainVertexMsl.litFragment).
     */
    public static final boolean LIT_MERGE = flag("mc.lod.litMerge", true);
    /**
     * Round 4: {@code mc.lod.flat0Px} (0 = off): level-0 nodes whose blocks are smaller than this many panel px (current FOV)
     * are meshed flat-lit (no AO / smooth light), so their faces merge; larger (near or zoomed) ones keep vanilla's lighting.
     */
    public static final double FLAT0_PX = Double.parseDouble(System.getProperty("mc.lod.flat0Px", "6"));
    /** Levels below this one are meshed with the player's smooth lighting / AO; the others flat-lit (exact merges, DH-like). */
    public static final int AO_LEVEL = Integer.getInteger("mc.lod.aoLevel", 1);
    /** Remove back-to-back opaque face pairs (SOLID): the inside of opaque-leaf canopies. */
    public static final boolean PAIRS = flag("mc.lod.pairs", true);
    /** LOD meshes leaves as cutout like the real terrain (true) or opaque (false: hull only, SOLID layer, like DH / Voxy). */
    public static final boolean CUTOUT_LEAVES = flag("mc.lod.cutoutLeaves", false);
    /** LOD fog (see the class comment); read per frame. */
    public static volatile boolean fog = flag("mc.lod.fog", true);
    /** LOD fog: the LOD edge gets the environmental fog vanilla gives terrain at this distance (blocks). */
    public static volatile float fogDist = Float.parseFloat(System.getProperty("mc.lod.fogDist", "320"));
    /** LOD fog: fraction of the LOD radius over which the terrain fades into the fog colour at the LOD edge. */
    public static volatile float fogEdge = Float.parseFloat(System.getProperty("mc.lod.fogEdge", "0.2"));
    private static float fogLodStart;
    /** Set per frame by {@link #adjustFog}: the environmental fog hides the whole LOD; {@code mc.lod.fogSkip} (true) enables it. */
    private static boolean fogHidesLod;
    static final boolean FOG_SKIP = flag("mc.lod.fogSkip", true);
    private static float fogLodCompress;
    public static final int STATS_SECONDS = Math.max(0, Integer.getInteger("mc.lod.stats", 0));
    /**
     * Round 3: per-level tau ({@code mc.lod.tauLevels}, comma-separated px for levels 1, 2, ...; missing = {@link #TAU}): a node of
     * level k is drawn while its cell is at most tau_k px (the budget control scales all of them). Lower values for the fine
     * levels keep block detail where cells are large on screen; higher values for coarse levels save quads where cells are tiny.
     */
    static final double[] TAU_LEVELS = parseTauLevels(System.getProperty("mc.lod.tauLevels", "2,2,2,2,2,2,2"));

    private static double[] parseTauLevels(final String s) {
        double[] out = new double[8];
        java.util.Arrays.fill(out, TAU);
        String[] parts = s.isBlank() ? new String[0] : s.split(",");
        for (int i = 0; i < parts.length && i + 1 < out.length; i++) {
            try {
                out[i + 1] = Double.parseDouble(parts[i].trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    /** Round 3: tau of level k relative to {@link #TAU} (1 without {@code mc.lod.tauLevels}). */
    /** Video Settings "Far Terrain Quality" (Options.farsightQuality, 1-10, default 10 = the tauLevels schedule as is): every
     * level's cell-size budget is multiplied by 1.25^(10 - quality). Read on the selection thread; a plain int read. */
    static double qualityScale() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        int q = mc == null || mc.options == null ? 10 : Math.max(1, Math.min(10, mc.options.farsightQuality().get()));
        return Math.pow(1.25, 10 - q) * detailScale;
    }

    /** Far-detail trade-off ({@code mc.lod.detailScale}, abab {@code Lod#detailScale}): extra factor on every level's
     * cell-size budget (> 1 = coarser far terrain, fewer quads). Default 1 (2026-10-15: Voxy's rule, cells switch at ~2 px;
     * LodManager's FPS target coarsens it when the GPU can't hold the frame rate). */
    /** Pick far detail per rendered pixel (window height times Render Scale), not per window pixel (abab {@code Lod#renderScaleTau}). */
    public static volatile boolean renderScaleTau = Lod.flag("mc.lod.renderScaleTau", false);

    public static volatile double detailScale = Double.parseDouble(System.getProperty("mc.lod.detailScale", "1.0"));

    static double tauFactor(final int level) {
        return TAU_LEVELS[Math.max(0, Math.min(7, level))] / TAU;
    }

    /** Round 3 diagnostics ({@code mc.lod.genOnly}): draw seed-generated data everywhere except chunks the client has loaded. */
    static final boolean GEN_ONLY = flag("mc.lod.genOnly", false);
    /** Round 3: disk cap of the seed-generated store ({@code mc.lod.genDiskMB}, default 2048): farthest regions are deleted. */
    static final long GEN_DISK_BYTES = Math.max(16L, Long.getLong("mc.lod.genDiskMB", 2048L)) << 20;
    /** Round 3: coarse faces repeat their texture once per block ({@code mc.lod.tileRepeat}, default true; LodGreedy.repeatTiles). */
    static final boolean TILE_REPEAT = flag("mc.lod.tileRepeat", true);
    /** Round 3: build the LOD cluster occlusion (box queries, LodGpu; {@code mc.lod.occlusion}, default true). */
    public static final boolean OCCLUSION = flag("mc.lod.occlusion", true);
    /** Round 3: use it this frame ({@code mc.lod.occlude}, abab-switchable {@code Lod#occlude}). */
    public static volatile boolean occlude = flag("mc.lod.occlude", true);
    /**
     * Stage 3 r6 ({@code mc.lod.secOccl}, abab {@code Lod#secOccl}): occlusion per LOD section (one box per section entirely in view,
     * its clusters skipped as a whole when the box showed nothing, frustum-out sections skipped before their clusters) instead of one
     * box per cluster.
     */
    public static volatile boolean secOccl = flag("mc.lod.secOccl", true);
    /**
     * Stage 3 r6 ({@code mc.lod.area}, abab {@code Lod#area}): the selection's projected-area rule (the rule Voxy describes for its
     * sections): a node's cell size in px is sqrt(projected area of its box) / 16, the box being the node's footprint times the
     * height range of its surface; the node splits while that exceeds tau. Flat terrain seen at a grazing angle stays coarser than
     * the distance rule (which treats every node as facing the eye); cliffs and mountains seen from the side get finer. Nodes whose
     * extents are not known yet (never meshed, children unknown) keep the distance rule.
     */
    public static volatile boolean area = flag("mc.lod.area", false);
    private static @Nullable LodManager manager;
    private static boolean disabledLogged;

    private Lod() {
    }

    /** Round 3: select LOD detail for the spyglass's final FOV while it zooms in ({@code mc.lod.spyglassAhead}, default true). */
    static final boolean SPYGLASS_AHEAD = flag("mc.lod.spyglassAhead", true);
    /** Round 3: skip vanilla's section fade-in where the LOD showed the chunk ({@code mc.lod.fadeSkip}, default true). */
    static final boolean FADE_SKIP = flag("mc.lod.fadeSkip", true);
    /** Round 7: also skip it where a resident LOD mesh backs the chunk, drawn recently or not ({@code mc.lod.fadeSkipResident}, default
     * true): the fog-coloured fade-in otherwise shows as a sky-coloured flash over terrain the LOD has (ice, water). */
    static volatile boolean FADE_SKIP_RESIDENT = flag("mc.lod.fadeSkipResident", true);
    /** Stats: real sections shown (first upload) and those shown without the fade-in. */
    static final java.util.concurrent.atomic.AtomicLong SECTION_UPLOADS = new java.util.concurrent.atomic.AtomicLong();
    static final java.util.concurrent.atomic.AtomicLong FADE_SKIPS = new java.util.concurrent.atomic.AtomicLong();

    /**
     * {@code SectionRenderDispatcher.RenderSection.updateUploadTime} (round 3): the upload time a newly shown real section starts
     * its fade-in from. Vanilla fades a new section in from the fog colour over {@code chunkSectionFadeInTime}; where the LOD drew
     * that chunk within the last 3 s the real section replaces the same terrain, so it is shown at once (no fog-coloured flash).
     */
    public static long sectionUploadTime(final long sectionNode, final long nowMillis) {
        if (!BUILD || !FADE_SKIP || !draw) {
            return nowMillis;
        }
        LodManager m = manager;
        if (m == null) {
            return nowMillis;
        }
        // Retained L0 membership is not proof it was exposed. The near draw snapshot
        // owns the exact handoff/fade decision while coverage masking is active.
        if (m.gpu.nearHandoffActive()) return nowMillis;
        SECTION_UPLOADS.incrementAndGet();
        int cx = net.minecraft.core.SectionPos.x(sectionNode);
        int cz = net.minecraft.core.SectionPos.z(sectionNode);
        for (int level = 0; level <= MAX_LEVEL; level++) {
            LodManager.Node n = m.existing(level, cx >> level, cz >> level);
            if (n != null && (nowMillis - n.lastDrawnMillis < 3000L || FADE_SKIP_RESIDENT && n.resident)) {
                FADE_SKIPS.incrementAndGet();
                return nowMillis - 3_600_000L;
            }
        }
        return nowMillis;
    }

    /** True while a LOD manager is running and drawing this frame (render thread). */
    public static boolean active() {
        return BUILD && draw && manager != null && manager.usable();
    }

    /** {@code ClientLevel.onChunkLoaded}: a chunk (or its biomes) arrived; captured once its light is ready. */
    public static void onChunkLoaded(final ClientLevel level, final ChunkPos pos) {
        levelEpoch++;
        if (!BUILD) {
            return;
        }
        LodManager m = managerFor(level);
        if (m != null) {
            m.onChunkLoaded(pos);
        }
    }

    /** Round 7 ({@code mc.lod.blockRecapture}, default true): {@code ClientLevel.sendBlockUpdated} of a render-changing update. */
    static final boolean BLOCK_RECAPTURE = flag("mc.lod.blockRecapture", true);
    /** Bumped on the render thread by every client chunk load / unload and block update: air evidence caches key on it. */
    static long levelEpoch;
    public static void onBlockChanged(final ClientLevel level, final net.minecraft.core.BlockPos pos) {
        levelEpoch++;
        if (!BUILD || !BLOCK_RECAPTURE) {
            return;
        }
        LodManager m = manager;
        if (m != null && m.level() == level) {
            m.onBlockChanged(pos.getX() >> 4, pos.getZ() >> 4);
        }
    }

    /** {@code ClientLevel.unload}, before vanilla clears it: the chunk leaves the client; capture its final state. */
    public static void onChunkUnload(final ClientLevel level, final LevelChunk chunk) {
        levelEpoch++;
        if (!BUILD) {
            return;
        }
        LodManager m = managerFor(level);
        if (m != null) {
            m.onChunkUnload(chunk);
        }
    }

    /** {@code Camera.update}: the far plane covers the LOD radius while drawing (reversed-Z float depth: precision holds). */
    public static float depthFar(final float vanillaFar) {
        if (!BUILD || !draw || manager == null) {
            return vanillaFar;
        }
        return Math.max(vanillaFar, RADIUS * 16.0F * 1.75F + 512.0F);
    }

    /** {@code GameRenderer.extractCamera}: render-distance fog (and sky fog) at the LOD radius while drawing. */
    public static int fogRenderDistance(final int vanillaChunks) {
        if (!BUILD || !draw || manager == null) {
            return vanillaChunks;
        }
        return Math.max(vanillaChunks, RADIUS);
    }

    /**
     * {@code GameRenderer.extractCamera}, after {@code FogRenderer.setupFog} (which got {@link #fogRenderDistance}): the LOD
     * fog. Environmental fog is evaluated on the compressed spherical distance {@code d - max(d - R0, 0) c} in the terrain
     * shaders (fog.glsl {@code fog_lod_distance}; R0 = the real render distance, c chosen so the LOD radius maps to
     * {@link #fogDist}): the real terrain keeps vanilla's fog, the LOD a light haze that still follows rain, water, night,
     * blindness and the dimension's fog (the nether's 96-block fog hides the LOD as it hides vanilla's far terrain). The
     * render-distance ramp covers the last {@link #fogEdge} of the LOD radius, so the LOD edge meets the sky's horizon
     * (vanilla's sky fog at this render distance) in the fog colour. Non-terrain shaders keep vanilla's fog (nothing else is
     * drawn past R0 except clouds and the sky, which use their own ends).
     */
    public static void adjustFog(final net.minecraft.client.renderer.fog.FogData data, final int vanillaChunks) {
        float r0 = vanillaChunks * 16.0F;
        float l = RADIUS * 16.0F;
        // Environmental fog that is solid before the real render distance (nether 96, water, lava, powder snow, blindness,
        // darkness, boss fog) hides every LOD fragment: skip the LOD draws this frame (the data, uploads and culling state stay).
        fogHidesLod = BUILD && draw && FOG_SKIP && data.environmentalEnd < r0 - 32.0F && data.color.w() >= 1.0F;
        if (!BUILD || !draw || !fog || manager == null || l <= r0) {
            fogLodStart = 0.0F;
            fogLodCompress = 0.0F;
            return;
        }
        fogLodStart = r0;
        float k = Math.max(0.0F, fogDist - r0) / (l - r0);
        fogLodCompress = 1.0F - Math.min(1.0F, k);
        float edge = Math.max(0.0F, Math.min(0.9F, fogEdge));
        data.renderDistanceStart = Math.max(r0, l * (1.0F - edge));
        data.renderDistanceEnd = l;
    }

    /** FogRenderer.updateBuffer: FogLodStart (0 = vanilla). */
    public static float fogLodStart() {
        return fogLodStart;
    }

    /** FogRenderer.updateBuffer: FogLodCompress (0 = vanilla). */
    public static float fogLodCompress() {
        return fogLodCompress;
    }

    /** Start exact near coverage at the draw list, before any LOD dispatch. No work when disabled. */
    /** Round 7 (LodGpu#realHeld): vanilla's translucent layer of this section is hidden while the LOD still draws its chunk. */
    static boolean realHeld(final long sectionNode) {
        LodManager m = manager;
        return BUILD && draw && m != null && m.gpu.realHeld(net.minecraft.core.SectionPos.x(sectionNode), net.minecraft.core.SectionPos.z(sectionNode));
    }

    static @Nullable LodGpu beginNearFrame() {
        if (!BUILD || (!LodGpu.nearHandoff && (manager == null || !manager.gpu.nearHandoffActive()))) return null;
        LodManager m = frameManager();
        if (m == null) return null;
        long t0 = System.nanoTime();
        LodGpu gpu = m.gpu.beginNearFrame(draw && !fogHidesLod && !TerrainToggles.drawListVerify);
        if (gpu != null) gpu.captureNearEmptySections(Minecraft.getInstance().levelRenderer.viewArea());
        LodGpu.hoBeginNs += System.nanoTime() - t0;
        return gpu;
    }

    /** Render thread, once per frame from LevelRenderer: the manager of the current level (created / replaced here). */
    static @Nullable LodManager frameManager() {
        if (!BUILD) {
            return null;
        }
        Minecraft mc = Minecraft.getInstance();
        return managerFor(mc.level);
    }

    private static long frameCounter;
    private static int lastChunkEvents = -1;

    /**
     * Render thread, from {@code LevelRenderer.prepareChunkRendersIndirectFast} after the terrain cluster dispatch: captures
     * loaded chunks, posts the camera to the scheduler, uploads finished meshes, culls the drawn LOD clusters and attaches the
     * draws to the frame's terrain ({@link ChunkSectionsToRender.DrawIndirect#withLodDraws}).
     *
     * @param planes      the frame's five camera-relative frustum planes (20 values) or null
     * @param projM11     the frame projection's (1,1) element: K = height / 2 * |m11| (zoom included)
     */
    public static void prepareFrame(final ChunkSectionsToRender.DrawIndirect target, final double @Nullable [] planes, final boolean frustum, final double camX,
        final double camY, final double camZ, final double eyeX, final double eyeY, final double eyeZ, final float projM11) {
        LodManager m = frameManager();
        if (m == null) {
            return;
        }
        long t0 = System.nanoTime();
        try {
            prepareFrame(m, target, planes, frustum, camX, camY, camZ, eyeX, eyeY, eyeZ, projM11);
        } finally {
            m.gpu.frameNanos += System.nanoTime() - t0;
            m.gpu.frames++;
        }
    }

    private static void prepareFrame(final LodManager m, final ChunkSectionsToRender.DrawIndirect target, final double @Nullable [] planes, final boolean frustum,
        final double camX, final double camY, final double camZ, final double eyeX, final double eyeY, final double eyeZ, final float projM11) {
        Minecraft mc = Minecraft.getInstance();
        m.captureLoaded();
        double h = mc.getWindow().getHeight() * (renderScaleTau ? mc.options.renderScalePercent() / 100.0 : 1.0);
        double k = h / 2.0 * Math.abs(projM11);
        double kWide = h / 2.0 / Math.tan(Math.toRadians(mc.options.fov().get() * 1.1) / 2.0);
        if (SPYGLASS_AHEAD && mc.player != null && mc.player.isScoping() && mc.options.getCameraType().isFirstPerson()) {
            // Round 3: the spyglass FOV eases to 0.1x over several ticks (GameRenderer's FOV smoothing); select for the final
            // FOV from the first frame, so the refinement is done (and its dissolves over) when the view settles.
            k = Math.max(k, h / 2.0 / Math.tan(Math.toRadians(mc.options.fov().get() * 0.1) / 2.0));
        }
        frameCounter++;
        double[] selectionPlanes=planes==null?null:planes.clone();
        if(LodAsyncProducerProbe.GUARDED && selectionPlanes!=null) {
            // Retain central k/detail. Only widen selection; the ordinary synchronous cull
            // below still uses the central view. Producer captures its own guard cull planes.
            try { selectionPlanes=LodFarGuard.selectionPlanes(selectionPlanes,mc.getWindow().getWidth(),(int)h); }
            catch(IllegalArgumentException unsupported) { /* Producer camera admission also declines this view. */ }
        }
        m.postCamera(new LodManager.Snap(camX, camY, camZ, k, Math.min(k, kWide),
            selectionPlanes,
            mc.options.getEffectiveRenderDistance(), frameCounter / 4));
        if (!draw || !m.usable() || fogHidesLod) {
            return;
        }
        m.gpu.uploadPending(48L << 20, camX, camY, camZ);
        boolean chunksChanged = m.chunkEvents != lastChunkEvents;
        lastChunkEvents = m.chunkEvents;
        java.util.List<TerrainClusters.Draw> draws = m.gpu.frame(m.level(), planes, frustum, camX, camY, camZ, eyeX, eyeY, eyeZ, chunksChanged);
        if (draws != null && !draws.isEmpty()) {
            target.withLodDraws(draws, m.gpu.sectionInfos());
            target.withLodOcclusion(m.gpu.occlusionDraw());
            target.withLodReuse(m.gpu.reuse());
            if (LodAsyncProducerProbe.ENABLED) target.withLodAsyncProbe(m.gpu.asyncProbe());
        }
    }

    private static synchronized @Nullable LodManager managerFor(final @Nullable ClientLevel level) {
        LodManager m = manager;
        if (m != null && m.level() == level) {
            return m;
        }
        if (m != null) {
            manager = null;
            m.close();
        }
        if (level == null) {
            return null;
        }
        if (TerrainToggles.COMPACT_VERTICES || !TerrainClusters.BUILD) {
            if (!disabledLogged) {
                disabledLogged = true;
                LOGGER.warn("[lod] needs the 28-byte terrain vertices and the cluster path (mc.terrain.compactVertices off, mc.terrain.clusters on); LOD off");
            }
            return null;
        }
        try {
            manager = new LodManager(level);
        } catch (Throwable t) {
            LOGGER.error("[lod] could not start", t);
            manager = null;
        }
        return manager;
    }

    /** Shutdown / disconnect: flush the store. */
    public static synchronized void shutdown() {
        if (manager != null) {
            manager.close();
            manager = null;
        }
    }

    /** Match vanilla's geometry invalidation after pack reloads and mesh-affecting graphics option changes. */
    public static void invalidateMeshes() {
        if (!BUILD) {
            return;
        }
        shutdown();
        LodMesher.resetSprites();
        LodTrees.resetSprites();
    }

    /** Diagnostics for the bench: true once the manager has no queued ingest, downsample or mesh work and all uploads are done. */
    public static boolean idle() {
        LodManager m = manager;
        return m == null || m.idle();
    }

    /** Diagnostics for the bench: a one-line state summary (JSON object), or null when off. */
    public static @Nullable String statsJson() {
        LodManager m = manager;
        return m == null ? null : m.statsJson();
    }

    static boolean flag(final String name, final boolean defaultValue) {
        String value = System.getProperty(name);
        return value == null ? defaultValue : !value.equalsIgnoreCase("false") && !value.equals("0");
    }
}
