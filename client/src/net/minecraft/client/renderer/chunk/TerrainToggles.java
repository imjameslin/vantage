package net.minecraft.client.renderer.chunk;

/**
 * Kill switches for the stage-2 terrain patches (docs/stage2/TERRAIN-PLAN.md section 4). Every switch defaults to ON
 * (except the two SOLID diagnostics of S6 and {@link #drawListVerify})
 * and is read from a system property, e.g. {@code -Dmc.terrain.faceBuckets=false}; {@code -Dmc.terrain=false} turns
 * every step off at once (vanilla behaviour). The fields are volatile so a debugger or a test harness can flip them
 * at runtime; the render thread reads each one once per frame.
 *
 * <ul>
 *   <li>{@link #faceBuckets} (S1): SOLID/CUTOUT quads are sorted into facing buckets when a section is meshed, and on
 *       the multi-draw-indirect path only the buckets that can face the camera are drawn. Takes effect for meshes
 *       compiled after it is switched on; switching it off at runtime draws every bucket (same image).</li>
 *   <li>{@link #shortIndices} (S1b): terrain draws without a sorted index buffer use a dedicated 16-bit quad index
 *       buffer (16383 quads per draw) instead of the shared, growable sequential buffer.</li>
 *   <li>{@link #exactFrustum} (S2): section-layers whose geometry bounds miss the exact per-frame frustum (bobbed,
 *       nausea-skewed projection) are dropped from the draw list.</li>
 *   <li>{@link #cloudCull} (S7): cloud faces outside a widened view cone are not uploaded/drawn.</li>
 *   <li>{@link #fastDrawList} (S4, render-thread CPU): the multi-draw-indirect terrain draw list is built by
 *       {@link TerrainDrawList} (per-mesh descriptors, no per-frame HashMap/EnumMap lookups or record objects, bulk
 *       writes) instead of {@code LevelRenderer.extractSectionDrawGroups}; same records, same bytes. Can be flipped at
 *       runtime.</li>
 *   <li>{@link #cloudRunCopy}: the S7 cloud face filter copies runs of kept faces with one bulk put per run instead of
 *       one 3-byte put per face (same bytes).</li>
 *   <li>{@link #COMPACT_VERTICES} (S5, startup only, default OFF: no measurable FPS gain in game, see CONTRACT.md 16):
 *       exactly representable SOLID/CUTOUT section-layers use the 16-byte vertex and the {@code *_COMPACT} pipelines;
 *       {@code -Dmc.terrain.compactSnap=true} also takes in layers whose positions/UVs are within a tiny tolerance of the
 *       grid (block-state rotation noise; {@link TerrainCompact}).</li>
 *   <li>{@link #cutoutFrontToBack} / {@link #solidFrontToBack} (S6, mesh time) and {@link #sortCutoutSections} /
 *       {@link #sortSolidSections} (per frame): front-to-back draw order for alpha-tested and opaque terrain, so
 *       nearer geometry fills the depth buffer first (same image). The CUTOUT pair defaults to ON (in game +1.0-1.6%
 *       FPS in forest views, no measurable change elsewhere: docs/metal/CONTRACT.md 16.10); the SOLID pair defaults to
 *       OFF (diagnostics).</li>
 * </ul>
 *
 * {@link #drawListVerify} ({@code -Dmc.terrain.drawListVerify=true}, default off, debug only) additionally builds the
 * vanilla list every frame and logs any difference from the fast list (doubles the draw-list CPU cost, and the S0
 * counters when they are enabled).
 */
public final class TerrainToggles {
    private static final boolean MASTER = flag("mc.terrain", true);
    public static volatile boolean faceBuckets = MASTER && flag("mc.terrain.faceBuckets", true);
    public static volatile boolean shortIndices = MASTER && flag("mc.terrain.shortIndices", true);
    public static volatile boolean exactFrustum = MASTER && flag("mc.terrain.exactFrustum", true);
    public static volatile boolean cloudCull = MASTER && flag("mc.terrain.cloudCull", true);
    public static volatile boolean fastDrawList = MASTER && flag("mc.terrain.fastDrawList", true);
    public static volatile boolean cloudRunCopy = MASTER && flag("mc.terrain.cloudRunCopy", true);
    public static volatile boolean drawListVerify = flag("mc.terrain.drawListVerify", false);
    /**
     * S5 ({@code -Dmc.terrain.compactVertices}): SOLID/CUTOUT section-layers whose every vertex has an exact 16-byte
     * encoding ({@link TerrainCompact}) are stored and drawn in it; the others keep the 28-byte vertex. Read once at
     * startup (it sets the vertex heaps' alignment), not switchable at runtime.
     */
    public static final boolean COMPACT_VERTICES = MASTER && flag("mc.terrain.compactVertices", false);
    /**
     * {@code -Dmc.terrain.meshStats=N} (seconds, default 0 = off): logs the S5 meshing outcome, the resident terrain
     * vertex/index bytes per layer and format, and the bytes staged for chunk upload every N seconds
     * ({@code SectionRenderDispatcher.meshMemoryReport}); {@code -Dmc.terrain.meshStatsFile=<path>} also appends them there.
     */
    public static final int MESH_STATS_SECONDS = Math.max(0, Integer.getInteger("mc.terrain.meshStats", 0));
    public static final String MESH_STATS_FILE = System.getProperty("mc.terrain.meshStatsFile");
    /**
     * S6 (mesh time, needs {@link #faceBuckets}): CUTOUT quads are ordered front-to-back inside each facing bucket
     * ({@link TerrainMeshInfo}). Takes effect for meshes compiled after it is switched; same image either way. Default ON.
     */
    public static volatile boolean cutoutFrontToBack = MASTER && flag("mc.terrain.cutoutFrontToBack", true);
    /** S6 applied to SOLID as well (diagnostics: opaque fragments are already hidden by the GPU's HSR). */
    public static volatile boolean solidFrontToBack = MASTER && flag("mc.terrain.solidFrontToBack", false);
    /**
     * Per frame, multi-draw-indirect path: the CUTOUT records are sorted front-to-back by the distance from the camera
     * to their section's centre (stable; a section's records keep their order). Vanilla's order is the octree walk,
     * only roughly near-to-far. Can be flipped at runtime. Default ON.
     */
    public static volatile boolean sortCutoutSections = MASTER && flag("mc.terrain.sortCutoutSections", true);
    /** As {@link #sortCutoutSections}, for the SOLID records. */
    public static volatile boolean sortSolidSections = MASTER && flag("mc.terrain.sortSolidSections", false);
    /**
     * R9-1 (CONTRACT 16.R9-1, {@link TranslucentFacing}): TRANSLUCENT sorted index buffers also carry the sorted list without
     * the facing buckets that face away from the camera at upload time (build switch, applies to buffers uploaded after a
     * change); {@link #translucentFacingDraw} (runtime, abab) draws that shorter list when the eye is still beyond every
     * dropped bucket's plane (S1's test and margin), else the full list.
     */
    // Round 7: default off. With it on, recordings showed one-frame lighter flicker on lake ice near the camera (7-47 events
    // per 7 lake passes vs 0-1 with the facing draw off); its gain (+0.2-0.6 %, RC-R9) did not show in an in-session A/B.
    public static final boolean TRANSLUCENT_FACING = MASTER && flag("mc.terrain.translucentFacing", false);
    public static volatile boolean translucentFacingDraw = TRANSLUCENT_FACING;
    /** S1: unwanted (back-facing) buckets of at most this many quads between two drawn buckets are drawn anyway. */
    public static final int BRIDGE_QUADS = Math.max(0, Integer.getInteger("mc.terrain.bridgeQuads", 32));
    /**
     * S0c record-cost probe: every SOLID/CUTOUT record is split into this many records (same triangles, same order).
     * 1 = off.
     */
    public static final int SPLIT_PROBE = Math.max(1, Integer.getInteger("mc.terrain.splitProbe", 1));
    /**
     * Round 12 (b) (CONTRACT 16.R12-B): triangle order inside each quad of the S1b 16-bit quad index buffer, an index into
     * {@link #QUAD_INDEX_PATTERNS} (0 = vanilla {@code 0 1 2 2 3 0}). Every pattern draws the same two triangles with the
     * same winding and the same diagonal: 1 = second triangle rotated to start at vertex 0 ({@code 0 1 2 0 2 3}, fan order,
     * index reuse at the head of each triangle; byte-identical in tools/TerrainShaderTest.java). Not kept: the two triangles
     * swapped ({@code 2 3 0 0 1 2}) changed 2-4 blended pixels of the translucent test image. Read once per frame
     * (abab-switchable; {@code -Dmc.terrain.quadIndexOrder=N}).
     */
    public static volatile int quadIndexOrder = Integer.getInteger("mc.terrain.quadIndexOrder", 0);
    public static final int[][] QUAD_INDEX_PATTERNS = {{0, 1, 2, 2, 3, 0}, {0, 1, 2, 0, 2, 3}};

    private TerrainToggles() {
    }

    private static boolean flag(final String name, final boolean defaultValue) {
        String value = System.getProperty(name);
        return value == null ? defaultValue : !value.equalsIgnoreCase("false") && !value.equals("0");
    }
}
