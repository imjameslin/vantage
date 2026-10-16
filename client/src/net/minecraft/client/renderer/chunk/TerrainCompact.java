package net.minecraft.client.renderer.chunk;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicLongArray;
import org.jspecify.annotations.Nullable;

/**
 * Compact 16-byte terrain vertex (docs/stage2/TERRAIN-PLAN.md S5), used for a SOLID or CUTOUT section-layer only when
 * <b>every</b> vertex of it round-trips exactly; any other section-layer keeps the vanilla 28-byte {@code BLOCK} vertex.
 *
 * <pre>
 * offset  attribute  format        contents                                   shader decode (terrain.vsh, TERRAIN_COMPACT)
 *  0      Position   RGBA16_UINT   x, y, z = (p + 8) * 2048                   p = float(int(q) - 16384) * 2^-11
 *                                  w = blockLight | skyLight << 8             UV2 = ivec2(w &amp; 0xFF, w &gt;&gt; 8)
 *  8      Color      RGBA8_UNORM   unchanged
 * 12      UV0        RG16_UINT     u * 32768, v * 32768                       uv = float(q) * 2^-15
 * </pre>
 *
 * <p><b>Exactness.</b> A vertex is accepted only if decoding its encoding with the shader's arithmetic gives back the
 * same float bits ({@link #positionCode}, {@link #uvCode}) and both light values (vanilla {@code RG16_SINT}) lie in
 * {@code [0, 255]}. Every decode step is exact in binary32 (a 16-bit integer converted to float, an integer
 * subtraction, a multiplication by a power of two), so the shader sees the same Position, UV0 and UV2 values as
 * vanilla does. Positions cover {@code [-8, 24)} blocks of the section at 1/2048 of a block; UVs cover {@code [0, 2)}
 * at 2^-15 (which includes the atlas edge {@code u = 1}). Negative zero is rejected (it would decode as +0).
 * Typical fallbacks: rotated model elements (cross plants), random block offsets (grass, flowers), fluid heights (lava),
 * and snow layers ({@code snow_height2}'s inner element at 0.002/16 = 1/8000 block, the most common miss in snowy biomes).
 *
 * <p><b>Snap mode</b> ({@code -Dmc.terrain.compactSnap=true}, visual bar "indistinguishable to the naked eye", GOAL.md):
 * a position component is also accepted when it lies within {@link #SNAP_POSITION_TOLERANCE} (default 2^-18 block,
 * about 4e-6: two ulps at 16) of its grid value, and a UV within {@link #SNAP_UV_TOLERANCE} (default 2^-22 of the atlas). This takes in
 * the rounding noise a float rotation matrix would leave (e.g. 0.99999994 instead of 1). Measured in 26.3: block-state
 * rotations are exact, snap accepted 0 layers more than exact mode in the bench, scene and travel worlds.
 * The snapped vertex moves by at most the tolerance (2^-18 block is 0.13 px even at the 0.05-block near plane, 2338 px
 * tall, 70 degree FOV, and 0.02 px at the closest a player's eye gets to a block face, 0.3 block); exact values are unchanged; equal floats snap to equal codes, so shared edges stay shared. Random offsets and
 * rotated elements stay far off the grid and keep the 28-byte vertex. The largest snap applied is tracked
 * ({@link #maxPositionError}, {@link #maxUvError}) and logged with {@code -Dmc.terrain.meshStats}.
 *
 * <p><b>Transcode.</b> {@link #transcodeInPlace} rewrites the 28-byte vertices as 16-byte ones at the start of the same
 * buffer (the write cursor never overtakes the read cursor), after the facing-bucket reorder, so the quad order and
 * the {@link TerrainMeshInfo} quad ranges are unchanged. TRANSLUCENT always stays {@code BLOCK} (its sort reads the
 * float positions).
 *
 * <p><b>Heaps.</b> Compact and 28-byte meshes of a layer share the layer's vertex uber buffers; with compact vertices
 * enabled those are aligned to {@link #SHARED_ALIGNMENT} = lcm(28, 16) so both {@code offset / 28} and
 * {@code offset / 16} are exact base vertices. Draw groups are keyed by heap and format; the compact groups draw
 * with the {@code *_COMPACT} pipelines.
 */
public final class TerrainCompact {
    public static final int STRIDE = 16;
    public static final int BLOCK_STRIDE = 28;
    /** lcm(28, 16): alignment of the SOLID/CUTOUT vertex heaps when compact meshes may share them. */
    public static final int SHARED_ALIGNMENT = 112;
    /** Position code = (p + 8) * 2^11. */
    public static final int POSITION_SHIFT = 11;
    public static final int POSITION_BIAS_CODE = 8 << POSITION_SHIFT;
    /** UV code = uv * 2^15. */
    public static final int UV_SHIFT = 15;
    public static final VertexFormat FORMAT = VertexFormat.builder(0)
        .addAttribute("Position", GpuFormat.RGBA16_UINT)
        .addAttribute("Color", GpuFormat.RGBA8_UNORM)
        .addAttribute("UV0", GpuFormat.RG16_UINT)
        .build();
    private static final float POSITION_SCALE = 1.0F / (1 << POSITION_SHIFT);
    private static final float UV_SCALE = 1.0F / (1 << UV_SHIFT);

    /** {@code -Dmc.terrain.compactSnap=true}: accept near-grid values (see the class comment). */
    public static final boolean SNAP = Boolean.getBoolean("mc.terrain.compactSnap");
    /** Largest accepted position snap in blocks ({@code -Dmc.terrain.compactSnapPosition}, default 2^-18). */
    public static final double SNAP_POSITION_TOLERANCE = tolerance("mc.terrain.compactSnapPosition", 0x1p-18, 0x1p-13);
    /** Largest accepted UV snap in atlas units ({@code -Dmc.terrain.compactSnapUv}, default 2^-22). */
    public static final double SNAP_UV_TOLERANCE = tolerance("mc.terrain.compactSnapUv", 0x1p-22, 0x1p-17);

    /** Result of {@link #check}: exact, or the first reason found. */
    public static final int EXACT = 0;
    public static final int FAIL_POSITION = 1;
    public static final int FAIL_UV = 2;
    public static final int FAIL_LIGHT = 3;
    public static final int FAIL_FORMAT = 4;
    /** Meshing statistic only: accepted in snap mode with at least one component moved (never returned by check). */
    public static final int SNAPPED = 5;
    static final int REASONS = 6;
    // Meshing statistics (worker threads): per layer ordinal, [EXACT..SNAPPED] section-layers, then compact vertices and
    // 28-byte vertices. Logged with -Dmc.terrain.meshStats=N (SectionRenderDispatcher.meshMemoryReport).
    private static final int STAT_STRIDE = REASONS + 2;
    static final AtomicLongArray MESH_STATS = new AtomicLongArray(STAT_STRIDE * 3);
    /** Largest snap applied so far (double bits; non-negative doubles order like their bits). [0] position, [1] UV. */
    private static final AtomicLongArray MAX_ERROR = new AtomicLongArray(2);
    // Chunk upload traffic (SectionRenderDispatcher.addSectionBuffersToUberBuffer, counted when -Dmc.terrain.meshStats is
    // on): per layer ordinal, [vertex bytes staged, of which compact, index bytes staged, section-layer uploads].
    static final int UPLOAD_STRIDE = 4;
    static final AtomicLongArray UPLOADS = new AtomicLongArray(UPLOAD_STRIDE * 3);

    private TerrainCompact() {
    }

    private static double tolerance(final String property, final double defaultValue, final double max) {
        String value = System.getProperty(property);
        if (value == null) {
            return defaultValue;
        }

        double parsed = Double.parseDouble(value);
        return parsed >= 0.0 && parsed <= max ? parsed : defaultValue;
    }

    /** 16-bit position code of {@code p}, or -1 when {@code float(code - 16384) * 2^-11} is not bit-identical to it. */
    public static int positionCode(final float p) {
        return positionCode(p, 0.0);
    }

    /**
     * 16-bit position code of {@code p}: the nearest grid value when it is bit-identical to {@code p}, or (tolerance &gt; 0)
     * within {@code tolerance} blocks of it; else -1.
     */
    public static int positionCode(final float p, final double tolerance) {
        double q = ((double)p + 8.0) * (1 << POSITION_SHIFT);
        if (!(q >= -0.5 && q <= 65535.5)) {
            return -1;
        }

        int code = (int)Math.rint(q);
        if (code < 0 || code > 65535) {
            return -1;
        }

        float decoded = decodePosition(code);
        if (Float.floatToRawIntBits(decoded) == Float.floatToRawIntBits(p)) {
            return code;
        }

        return tolerance > 0.0 && Math.abs((double)decoded - p) <= tolerance ? code : -1;
    }

    /** The shader's position decode: exact (integer to float, power-of-two scale). */
    public static float decodePosition(final int code) {
        return (float)(code - POSITION_BIAS_CODE) * POSITION_SCALE;
    }

    /** 16-bit UV code of {@code uv}, or -1 when {@code float(code) * 2^-15} is not bit-identical to it. */
    public static int uvCode(final float uv) {
        return uvCode(uv, 0.0);
    }

    /** 16-bit UV code of {@code uv}: bit-identical, or (tolerance &gt; 0) within {@code tolerance}; else -1. */
    public static int uvCode(final float uv, final double tolerance) {
        double q = (double)uv * (1 << UV_SHIFT);
        if (!(q >= -0.5 && q <= 65535.5)) {
            return -1;
        }

        int code = (int)Math.rint(q);
        if (code < 0 || code > 65535) {
            return -1;
        }

        float decoded = decodeUv(code);
        if (Float.floatToRawIntBits(decoded) == Float.floatToRawIntBits(uv)) {
            return code;
        }

        return tolerance > 0.0 && Math.abs((double)decoded - uv) <= tolerance ? code : -1;
    }

    public static float decodeUv(final int code) {
        return (float)code * UV_SCALE;
    }

    /** Light code {@code block | sky << 8} of a packed vanilla UV2 ({@code block} low 16 bits, {@code sky} high), or -1. */
    public static int lightCode(final int packedLight) {
        return (packedLight & 0xFF00FF00) == 0 ? (packedLight & 0xFF) | (packedLight >>> 8 & 0xFF00) : -1;
    }

    /**
     * {@link #EXACT} when every one of the {@code vertexCount} 28-byte {@code BLOCK} vertices at {@code base} has an exact
     * compact encoding, else the reason of the first vertex that has none.
     */
    public static int check(final ByteBuffer vertices, final int base, final int vertexCount) {
        return check(vertices, base, vertexCount, 0.0, 0.0, null);
    }

    /**
     * As {@link #check(ByteBuffer, int, int)} with snap tolerances (0 = exact); when accepted and {@code maxError} is not
     * null, {@code maxError[0]} / {@code [1]} receive the largest position / UV snap of the layer (0 when exact).
     */
    public static int check(
        final ByteBuffer vertices, final int base, final int vertexCount, final double positionTolerance, final double uvTolerance,
        final double @Nullable [] maxError
    ) {
        ByteBuffer b = vertices.duplicate().order(ByteOrder.nativeOrder());
        if (vertexCount <= 0 || base < 0 || (long)base + (long)vertexCount * BLOCK_STRIDE > b.limit()) {
            return FAIL_FORMAT;
        }

        double maxPosition = 0.0;
        double maxUv = 0.0;
        for (int i = 0, at = base; i < vertexCount; i++, at += BLOCK_STRIDE) {
            for (int a = 0; a < 3; a++) {
                float p = b.getFloat(at + 4 * a);
                int code = positionCode(p, positionTolerance);
                if (code < 0) {
                    return FAIL_POSITION;
                }

                maxPosition = Math.max(maxPosition, Math.abs((double)decodePosition(code) - p));
            }

            for (int a = 0; a < 2; a++) {
                float uv = b.getFloat(at + 16 + 4 * a);
                int code = uvCode(uv, uvTolerance);
                if (code < 0) {
                    return FAIL_UV;
                }

                maxUv = Math.max(maxUv, Math.abs((double)decodeUv(code) - uv));
            }

            if (lightCode(b.getInt(at + 24)) < 0) {
                return FAIL_LIGHT;
            }
        }

        if (maxError != null) {
            maxError[0] = maxPosition;
            maxError[1] = maxUv;
        }

        return EXACT;
    }

    /**
     * Rewrites the {@code vertexCount} 28-byte vertices at {@code base} (all of which passed {@link #check}) as 16-byte
     * compact vertices at {@code base}, in place and in order, and returns a view of exactly the compact bytes (native
     * order). Bytes past the compact range are left as they are.
     */
    public static ByteBuffer transcodeInPlace(final ByteBuffer vertices, final int base, final int vertexCount) {
        return transcodeInPlace(vertices, base, vertexCount, 0.0, 0.0);
    }

    /** As {@link #transcodeInPlace(ByteBuffer, int, int)}, snapping within the given tolerances (as {@link #check}). */
    public static ByteBuffer transcodeInPlace(
        final ByteBuffer vertices, final int base, final int vertexCount, final double positionTolerance, final double uvTolerance
    ) {
        ByteBuffer b = vertices.duplicate().order(ByteOrder.nativeOrder());

        for (int i = 0; i < vertexCount; i++) {
            int src = base + i * BLOCK_STRIDE;
            int dst = base + i * STRIDE;
            // Read the whole source vertex before writing: the destination may overlap its first bytes.
            int x = positionCode(b.getFloat(src), positionTolerance);
            int y = positionCode(b.getFloat(src + 4), positionTolerance);
            int z = positionCode(b.getFloat(src + 8), positionTolerance);
            int color = b.getInt(src + 12);
            int u = uvCode(b.getFloat(src + 16), uvTolerance);
            int v = uvCode(b.getFloat(src + 20), uvTolerance);
            int light = lightCode(b.getInt(src + 24));
            if ((x | y | z | u | v | light) < 0) {
                throw new IllegalStateException("vertex " + i + " has no compact encoding");
            }

            b.putShort(dst, (short)x);
            b.putShort(dst + 2, (short)y);
            b.putShort(dst + 4, (short)z);
            b.putShort(dst + 6, (short)light);
            b.putInt(dst + 8, color);
            b.putShort(dst + 12, (short)u);
            b.putShort(dst + 14, (short)v);
        }

        return b.slice(base, vertexCount * STRIDE).order(ByteOrder.nativeOrder());
    }

    /**
     * Section-layer step of {@link SectionCompiler}: when every vertex is exact (or, in snap mode, within the snap
     * tolerances), transcodes the layer in place and returns the compact bytes to upload; otherwise null (the 28-byte
     * data is untouched). Counts the outcome.
     */
    public static @Nullable ByteBuffer tryTranscode(final ChunkSectionLayer layer, final ByteBuffer vertices, final int vertexCount, final boolean blockFormat) {
        double positionTolerance = SNAP ? SNAP_POSITION_TOLERANCE : 0.0;
        double uvTolerance = SNAP ? SNAP_UV_TOLERANCE : 0.0;
        double[] error = new double[2];
        int reason = blockFormat ? check(vertices, vertices.position(), vertexCount, positionTolerance, uvTolerance, error) : FAIL_FORMAT;
        int statBase = STAT_STRIDE * layer.ordinal();
        if (DEBUG && reason == FAIL_POSITION) {
            debugPositions(vertices, vertices.position(), vertexCount);
        }

        if (reason != EXACT) {
            MESH_STATS.incrementAndGet(statBase + reason);
            if (LOSSY && blockFormat) {
                // Diagnostics only (changes pixels): every layer compact, positions/UVs rounded to the grid.
                MESH_STATS.addAndGet(statBase + REASONS, vertexCount);
                return transcodeLossy(vertices, vertices.position(), vertexCount);
            }

            MESH_STATS.addAndGet(statBase + REASONS + 1, vertexCount);
            return null;
        }

        boolean snapped = error[0] > 0.0 || error[1] > 0.0;
        MESH_STATS.incrementAndGet(statBase + (snapped ? SNAPPED : EXACT));
        if (snapped) {
            recordError(error[0], error[1]);
        }

        MESH_STATS.addAndGet(statBase + REASONS, vertexCount);
        return transcodeInPlace(vertices, vertices.position(), vertexCount, positionTolerance, uvTolerance);
    }

    private static void recordError(final double position, final double uv) {
        MAX_ERROR.accumulateAndGet(0, Double.doubleToRawLongBits(position), Math::max);
        MAX_ERROR.accumulateAndGet(1, Double.doubleToRawLongBits(uv), Math::max);
    }

    /** Largest position snap (blocks) applied since startup (snap mode and the lossy diagnostic). */
    public static double maxPositionError() {
        return Double.longBitsToDouble(MAX_ERROR.get(0));
    }

    /** Largest UV snap (atlas units) applied since startup (snap mode and the lossy diagnostic). */
    public static double maxUvError() {
        return Double.longBitsToDouble(MAX_ERROR.get(1));
    }

    /** Counts one section-layer upload into the staging buffer (meshStats only). */
    public static void countUpload(final ChunkSectionLayer layer, final @Nullable ByteBuffer vertexBuffer, final @Nullable ByteBuffer indexBuffer, final boolean compact) {
        int at = UPLOAD_STRIDE * layer.ordinal();
        if (vertexBuffer != null) {
            UPLOADS.addAndGet(at, vertexBuffer.remaining());
            if (compact) {
                UPLOADS.addAndGet(at + 1, vertexBuffer.remaining());
            }
        }

        if (indexBuffer != null) {
            UPLOADS.addAndGet(at + 2, indexBuffer.remaining());
        }

        UPLOADS.incrementAndGet(at + 3);
    }

    /** Upload counters of one layer: vertex bytes, of which compact, index bytes, uploads. */
    public static long[] uploadStats(final ChunkSectionLayer layer) {
        long[] out = new long[UPLOAD_STRIDE];
        for (int i = 0; i < UPLOAD_STRIDE; i++) {
            out[i] = UPLOADS.get(UPLOAD_STRIDE * layer.ordinal() + i);
        }

        return out;
    }

    /**
     * {@code -Dmc.terrain.compactLossy=true} (opt-in, alters the image by design): layers without an exact encoding are
     * transcoded anyway with rounded (clamped) codes, so that every SOLID/CUTOUT layer is compact. Measured in game: max
     * position error 2.41e-4 block (2^-12), max UV error 1.52e-5 (2^-16), passes the perceptual bar on every
     * terrain-static scene; travel +4-6% FPS, -40% resident vertex bytes, -43% chunk upload (results/S5-COMPACT.md).
     * Not default: GOAL's temporal check and the user's side-by-side sign-off are still open (CONTRACT.md 16.9).
     */
    static final boolean LOSSY = Boolean.getBoolean("mc.terrain.compactLossy");

    static ByteBuffer transcodeLossy(final ByteBuffer vertices, final int base, final int vertexCount) {
        ByteBuffer b = vertices.duplicate().order(ByteOrder.nativeOrder());
        double maxPosition = 0.0;
        double maxUv = 0.0;
        for (int i = 0; i < vertexCount; i++) {
            int src = base + i * BLOCK_STRIDE;
            int dst = base + i * STRIDE;
            float px = b.getFloat(src);
            float py = b.getFloat(src + 4);
            float pz = b.getFloat(src + 8);
            float pu = b.getFloat(src + 16);
            float pv = b.getFloat(src + 20);
            int x = roundCode((px + 8.0) * (1 << POSITION_SHIFT));
            int y = roundCode((py + 8.0) * (1 << POSITION_SHIFT));
            int z = roundCode((pz + 8.0) * (1 << POSITION_SHIFT));
            int color = b.getInt(src + 12);
            int u = roundCode(pu * (double)(1 << UV_SHIFT));
            int v = roundCode(pv * (double)(1 << UV_SHIFT));
            maxPosition = Math.max(maxPosition, Math.max(Math.abs(decodePosition(x) - (double)px), Math.max(Math.abs(decodePosition(y) - (double)py), Math.abs(decodePosition(z) - (double)pz))));
            maxUv = Math.max(maxUv, Math.max(Math.abs(decodeUv(u) - (double)pu), Math.abs(decodeUv(v) - (double)pv)));
            int packed = b.getInt(src + 24);
            int light = Math.clamp((short)packed, 0, 255) | Math.clamp((short)(packed >>> 16), 0, 255) << 8;
            b.putShort(dst, (short)x);
            b.putShort(dst + 2, (short)y);
            b.putShort(dst + 4, (short)z);
            b.putShort(dst + 6, (short)light);
            b.putInt(dst + 8, color);
            b.putShort(dst + 12, (short)u);
            b.putShort(dst + 14, (short)v);
        }

        recordError(maxPosition, maxUv);
        return b.slice(base, vertexCount * STRIDE).order(ByteOrder.nativeOrder());
    }

    private static int roundCode(final double q) {
        return q >= 0.0 ? (int)Math.min(65535.0, Math.rint(q)) : 0;
    }

    // ---- diagnostics (-Dmc.terrain.compactDebug=true): what the inexact positions are ---------------------------------
    static final boolean DEBUG = Boolean.getBoolean("mc.terrain.compactDebug");
    static final java.util.concurrent.ConcurrentHashMap<Integer, java.util.concurrent.atomic.LongAdder> DEBUG_VALUES = new java.util.concurrent.ConcurrentHashMap<>();
    /** [0] failing components near the 1/2048 grid (< 2^-16 away), [1] far, [2] position-failed layers whose misses are all near. */
    static final AtomicLongArray DEBUG_COUNTS = new AtomicLongArray(4);

    static void debugPositions(final ByteBuffer vertices, final int base, final int vertexCount) {
        ByteBuffer b = vertices.duplicate().order(ByteOrder.nativeOrder());
        boolean allNear = true;
        for (int i = 0; i < vertexCount; i++) {
            for (int a = 0; a < 3; a++) {
                float p = b.getFloat(base + i * BLOCK_STRIDE + 4 * a);
                if (positionCode(p) < 0) {
                    double q = ((double)p + 8.0) * 2048.0;
                    boolean near = Math.abs(q - Math.rint(q)) < 1.0 / 32.0;
                    DEBUG_COUNTS.incrementAndGet(near ? 0 : 1);
                    allNear &= near;
                    if (DEBUG_VALUES.size() < 500_000) {
                        DEBUG_VALUES.computeIfAbsent(Float.floatToRawIntBits(p), k -> new java.util.concurrent.atomic.LongAdder()).increment();
                    }
                }
            }
        }
        if (allNear) {
            DEBUG_COUNTS.incrementAndGet(2);
        }
    }

    public static String debugReport() {
        if (!DEBUG) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(java.util.Locale.ROOT, "%n  compact debug: %d distinct inexact position floats; components near grid %d, far %d; position-failed layers with only near misses %d",
            DEBUG_VALUES.size(), DEBUG_COUNTS.get(0), DEBUG_COUNTS.get(1), DEBUG_COUNTS.get(2)));
        DEBUG_VALUES.entrySet().stream().sorted((x, y) -> Long.compare(y.getValue().sum(), x.getValue().sum())).limit(40).forEach(e ->
            sb.append(String.format(java.util.Locale.ROOT, "%n    %.9g (0x%08x) x%d", Float.intBitsToFloat(e.getKey()), e.getKey(), e.getValue().sum())));
        return sb.toString();
    }

    /** Meshing statistics of one layer: section-layers by outcome [EXACT..SNAPPED], then compact and 28-byte vertex counts. */
    public static long[] meshStats(final ChunkSectionLayer layer) {
        long[] out = new long[STAT_STRIDE];
        for (int i = 0; i < STAT_STRIDE; i++) {
            out[i] = MESH_STATS.get(STAT_STRIDE * layer.ordinal() + i);
        }

        return out;
    }
}
