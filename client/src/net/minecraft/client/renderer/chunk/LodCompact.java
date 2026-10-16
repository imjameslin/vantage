package net.minecraft.client.renderer.chunk;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

/**
 * Stage 3 round 2: compact LOD quads (CONTRACT 16.S3-LOD-R2). One 48-byte record per quad instead of four 28-byte BLOCK
 * vertices (112 bytes): the LOD draw's vertex fetch was memory-bound (round-2 attribution: 0.41 ms of the 1.5 ms LOD cost at
 * tau 8, ~146 GB/s). The four vertex invocations of a quad read the same record (cache hits), and positions stay in cell
 * units (the shader scales by 2^level), so a record is exact for every axis-aligned quad on the 1/32-cell grid.
 *
 * <pre>
 * uint  contents
 *  0    corner 0 position, 1/32 cell: x | y &lt;&lt; 10 | z &lt;&lt; 20 (0..1023)
 *  1    edge 1 (corner 1 - corner 0): axis (2 bits) | length (12-bit two's complement, 1/32 cell) &lt;&lt; 2;
 *       edge 2 (corner 3 - corner 0) the same &lt;&lt; 14; level &lt;&lt; 28 (3 bits)
 *  2-5  colours of corners 0-3 (the BLOCK vertex's RGBA8, unchanged)
 *  6    UV2 light low bytes of corners 0 and 1: (block | sky &lt;&lt; 8) | (block | sky &lt;&lt; 8) &lt;&lt; 16
 *  7    the same for corners 2 and 3
 *  8    corner 0 u in 1/16 atlas pixel (20 bits) | greedy tile x + 1 (UV2 u's high byte) &lt;&lt; 20
 *  9    corner 0 v in 1/16 atlas pixel (20 bits) | greedy tile y + 1 &lt;&lt; 20
 * 10    uv edge 1 (corner 1 - corner 0): du | dv &lt;&lt; 16 (int16, 1/16 pixel)
 * 11    uv edge 2 (corner 3 - corner 0)
 * </pre>
 * Corner 2 must equal corner 0 + edge 1 + edge 2 (position and uv); edges must be axis-aligned and of different axes. A
 * section-layer is stored compact only if every quad round-trips exactly (the shader's arithmetic on these integers is exact
 * in binary32 for a power-of-two atlas); otherwise it keeps the BLOCK vertices (cross plants, fluid surfaces, ...).
 */
public final class LodCompact {
    public static final int RECORD_BYTES = 48;
    /** {@code mc.lod.compact} (true): store LOD section-layers as compact records when they round-trip. */
    public static final boolean ENABLED = !"false".equals(System.getProperty("mc.lod.compact"));
    public static final AtomicLong LAYERS_COMPACT = new AtomicLong();
    public static final AtomicLong LAYERS_BLOCK = new AtomicLong();
    public static final AtomicLong QUADS_COMPACT = new AtomicLong();
    /** Fallback reasons (first failing quad of a layer): 0 position grid, 1 uv grid, 2 tile, 3 range, 4 edges, 5 corner 2, 6 uv corner 2 / range, 7 lit table. */
    public static final java.util.concurrent.atomic.AtomicLongArray FAILS = new java.util.concurrent.atomic.AtomicLongArray(8);
    /** Level of the failing layers. */
    public static final java.util.concurrent.atomic.AtomicLongArray FAIL_LEVELS = new java.util.concurrent.atomic.AtomicLongArray(8);

    private static @Nullable ByteBuffer fail(final int reason, final int level) {
        FAILS.incrementAndGet(reason);
        FAIL_LEVELS.incrementAndGet(Math.min(7, level));
        return null;
    }
    private static final int STRIDE = LodMesher.STRIDE;

    private LodCompact() {
    }

    private static int grid(final float f, final float scale) {
        return grid(f, scale, 1e-3F);
    }

    private static int grid(final float f, final float scale, final float tolerance) {
        float x = f * scale;
        float r = Math.round(x);
        if (Math.abs(x - r) > tolerance) {
            return Integer.MIN_VALUE;
        }
        return (int)r;
    }

    /**
     * Position snap tolerance in 1/32 cell ({@code mc.lod.compactSnap}, default 0.5 = always snap to the 1/32-cell grid: at
     * most 1/64 cell, i.e. 1/64 block at level 0 (0.1 px at the real render distance) and below a quarter pixel at every
     * coarse level at tau 16. Equal floats snap to equal codes, so shared edges stay shared. Takes in fluid surface heights
     * (8/9 block) and snow layers' 0.002/16 inner element; rotated / offset elements still fail the axis-aligned test.
     */
    static final float POSITION_SNAP = Float.parseFloat(System.getProperty("mc.lod.compactSnap", "0.5"));

    /** Edge code (axis | length &lt;&lt; 2) of d (integer 1/32-cell codes), or -1 if not axis-aligned / out of range. */
    private static int edge(final int dx, final int dy, final int dz) {
        int nz = (dx != 0 ? 1 : 0) + (dy != 0 ? 1 : 0) + (dz != 0 ? 1 : 0);
        if (nz != 1) {
            return -1;
        }
        int axis = dx != 0 ? 0 : dy != 0 ? 1 : 2;
        int len = dx + dy + dz;
        if (len < -2048 || len > 2047) {
            return -1;
        }
        return axis | (len & 4095) << 2;
    }

    /**
     * The compact records of {@code quads} quads of 28-byte BLOCK vertices at position 0 of {@code v} (cell-unit positions,
     * before the 2^level scale), or null if one of them does not round-trip.
     */
    static @Nullable ByteBuffer encode(final ByteBuffer v, final int quads, final int level, final int atlasW, final int atlasH) {
        if (!ENABLED || level > 7 || atlasW <= 0 || atlasH <= 0 || Integer.bitCount(atlasW) != 1 || Integer.bitCount(atlasH) != 1) {
            return null;
        }
        // Lit merge (LodGreedy.LIT_TABLES): lit records' cell tables follow the records, 2 uint4 per cell, in table-index order.
        java.util.List<int[]> tables = LodGreedy.LIT_TABLES.get();
        int[] tableAt = null;
        int tableUint4 = 0;
        for (int q = 0; q < quads; q++) {
            int a = q * 4 * STRIDE;
            if ((v.getInt(a + 24) & 0x00FF00FF) == LodGreedy.LIT_LIGHT) {
                int index = v.getInt(a + 12);
                if (index < 0 || index >= tables.size()) {
                    return fail(7, level);
                }
                if (tableAt == null) {
                    tableAt = new int[tables.size()];
                    Arrays.fill(tableAt, -1);
                }
                if (tableAt[index] < 0) {
                    tableAt[index] = 3 * quads + tableUint4;
                    tableUint4 += 2 * tables.get(index)[0] * tables.get(index)[1];
                }
            }
        }
        ByteBuffer out = ByteBuffer.allocateDirect(quads * RECORD_BYTES + 16 * tableUint4).order(ByteOrder.nativeOrder());
        if (tableAt != null) {
            for (int index = 0; index < tableAt.length; index++) {
                if (tableAt[index] >= 0) {
                    int[] t = tables.get(index);
                    for (int i = 3; i < t.length; i++) {
                        out.putInt(16 * tableAt[index] + 4 * (i - 3), t[i]);
                    }
                }
            }
        }
        float su = atlasW * 16.0F;
        float sv = atlasH * 16.0F;
        int[] px = new int[4];
        int[] py = new int[4];
        int[] pz = new int[4];
        int[] uu = new int[4];
        int[] vv = new int[4];
        for (int q = 0; q < quads; q++) {
            int at = q * 4 * STRIDE;
            if (billboard(v, at, out, q * RECORD_BYTES, level, su, sv)) {
                continue;
            }
            int tile = -1;
            for (int k = 0; k < 4; k++) {
                int a = at + k * STRIDE;
                px[k] = grid(v.getFloat(a), 32.0F, POSITION_SNAP);
                py[k] = grid(v.getFloat(a + 4), 32.0F, POSITION_SNAP);
                pz[k] = grid(v.getFloat(a + 8), 32.0F, POSITION_SNAP);
                uu[k] = grid(v.getFloat(a + 16), su);
                vv[k] = grid(v.getFloat(a + 20), sv);
                if (px[k] == Integer.MIN_VALUE || py[k] == Integer.MIN_VALUE || pz[k] == Integer.MIN_VALUE) {
                    return fail(0, level);
                }
                if (uu[k] == Integer.MIN_VALUE || vv[k] == Integer.MIN_VALUE) {
                    return fail(1, level);
                }
                int l0 = v.getShort(a + 24) & 0xFFFF;
                int l1 = v.getShort(a + 26) & 0xFFFF;
                int t = (l0 >>> 8) | (l1 >>> 8) << 8;
                if (tile >= 0 && t != tile) {
                    return fail(2, level);
                }
                tile = t;
            }
            if (px[0] < 0 || px[0] > 1023 || py[0] < 0 || py[0] > 1023 || pz[0] < 0 || pz[0] > 1023 || uu[0] < 0 || uu[0] >= 1 << 20
                || vv[0] < 0 || vv[0] >= 1 << 20) {
                return fail(3, level);
            }
            int e1 = edge(px[1] - px[0], py[1] - py[0], pz[1] - pz[0]);
            int e2 = edge(px[3] - px[0], py[3] - py[0], pz[3] - pz[0]);
            if (e1 < 0 || e2 < 0 || (e1 & 3) == (e2 & 3)) {
                return fail(4, level);
            }
            if (px[2] != px[1] + px[3] - px[0] || py[2] != py[1] + py[3] - py[0] || pz[2] != pz[1] + pz[3] - pz[0]) {
                return fail(5, level);
            }
            int du1 = uu[1] - uu[0];
            int dv1 = vv[1] - vv[0];
            int du2 = uu[3] - uu[0];
            int dv2 = vv[3] - vv[0];
            if (uu[2] != uu[0] + du1 + du2 || vv[2] != vv[0] + dv1 + dv2 || du1 < -32768 || du1 > 32767 || dv1 < -32768 || dv1 > 32767
                || du2 < -32768 || du2 > 32767 || dv2 < -32768 || dv2 > 32767) {
                return fail(6, level);
            }
            int o = q * RECORD_BYTES;
            out.putInt(o, px[0] | py[0] << 10 | pz[0] << 20);
            out.putInt(o + 4, e1 | e2 << 14 | level << 28);
            if ((v.getInt(at + 24) & 0x00FF00FF) == LodGreedy.LIT_LIGHT) {
                // Lit record: word 2 = cell table start relative to the record (uint4 units), word 3 = w | h << 8 | corner bits
                // << 16, words 4-5 unused, word 6 = 0xFFFFFFFF (marker: no real light word is), word 7 = 0.
                int[] t = tables.get(v.getInt(at + 12));
                out.putInt(o + 8, tableAt[v.getInt(at + 12)] - 3 * q);
                out.putInt(o + 12, t[0] | t[1] << 8 | t[2] << 16);
                out.putInt(o + 16, 0);
                out.putInt(o + 20, 0);
                out.putInt(o + 24, -1);
                out.putInt(o + 28, 0);
            } else {
                for (int k = 0; k < 4; k++) {
                    out.putInt(o + 8 + 4 * k, v.getInt(at + k * STRIDE + 12));
                }
                int[] light = new int[4];
                for (int k = 0; k < 4; k++) {
                    int a = at + k * STRIDE;
                    light[k] = (v.getShort(a + 24) & 0xFF) | (v.getShort(a + 26) & 0xFF) << 8;
                }
                out.putInt(o + 24, light[0] | light[1] << 16);
                out.putInt(o + 28, light[2] | light[3] << 16);
            }
            out.putInt(o + 32, uu[0] | (tile & 0xFF) << 20);
            out.putInt(o + 36, vv[0] | (tile >>> 8 & 0xFF) << 20);
            out.putInt(o + 40, du1 & 0xFFFF | dv1 << 16);
            out.putInt(o + 44, du2 & 0xFFFF | dv2 << 16);
        }
        QUADS_COMPACT.addAndGet(quads);
        return out;
    }

    /**
     * Round 4 (LodTrees): a tree stand-in quad (CPU corners (ax - hw, y0, az - hw), (ax + hw, y0, az + hw), (ax + hw, y1, az + hw),
     * (ax - hw, y1, az - hw)) becomes a billboard record: corner 0 = the anchor (ax, y0, az), edge 1 = axis code 3 with the half
     * width as its length, edge 2 = y with the height; the shader turns edge 1 into the horizontal direction perpendicular to the
     * camera ray (TerrainVertexMsl.COMPACT_FETCH). UV corner 0 and the two uv edges as for any quad. False if not that shape.
     */
    private static boolean billboard(final ByteBuffer v, final int at, final ByteBuffer out, final int o, final int level, final float su, final float sv) {
        float[] x = new float[4];
        float[] y = new float[4];
        float[] z = new float[4];
        for (int k = 0; k < 4; k++) {
            x[k] = v.getFloat(at + k * STRIDE);
            y[k] = v.getFloat(at + k * STRIDE + 4);
            z[k] = v.getFloat(at + k * STRIDE + 8);
        }
        if (!LodTrees.isBillboard(x, y, z)) {
            return false;
        }
        int ax = grid((x[0] + x[1]) * 0.5F, 32.0F, POSITION_SNAP);
        int az = grid((z[0] + z[1]) * 0.5F, 32.0F, POSITION_SNAP);
        int ay = grid(y[0], 32.0F, POSITION_SNAP);
        int hw = grid((x[1] - x[0]) * 0.5F, 32.0F, POSITION_SNAP);
        int h = grid(y[3] - y[0], 32.0F, POSITION_SNAP);
        int u0 = grid(v.getFloat(at + 16), su);
        int v0 = grid(v.getFloat(at + 20), sv);
        int u1 = grid(v.getFloat(at + STRIDE + 16), su);
        int v3 = grid(v.getFloat(at + 3 * STRIDE + 20), sv);
        if (ax < 0 || ax > 1023 || az < 0 || az > 1023 || ay < 0 || ay > 1023 || hw <= 0 || hw > 2047 || h <= 0 || h > 2047 || u0 < 0 || v0 < 0
            || u0 >= 1 << 20 || v0 >= 1 << 20 || u1 == Integer.MIN_VALUE || v3 == Integer.MIN_VALUE) {
            return false;
        }
        int du = u1 - u0;
        int dv = v3 - v0;
        if (du < -32768 || du > 32767 || dv < -32768 || dv > 32767) {
            return false;
        }
        out.putInt(o, ax | ay << 10 | az << 20);
        out.putInt(o + 4, 3 | (hw & 4095) << 2 | (1 | (h & 4095) << 2) << 14 | level << 28);
        for (int k = 0; k < 4; k++) {
            out.putInt(o + 8 + 4 * k, v.getInt(at + k * STRIDE + 12));
        }
        int[] light = new int[4];
        for (int k = 0; k < 4; k++) {
            int a = at + k * STRIDE;
            light[k] = (v.getShort(a + 24) & 0xFF) | (v.getShort(a + 26) & 0xFF) << 8;
        }
        out.putInt(o + 24, light[0] | light[1] << 16);
        out.putInt(o + 28, light[2] | light[3] << 16);
        out.putInt(o + 32, u0);
        out.putInt(o + 36, v0);
        out.putInt(o + 40, du & 0xFFFF);
        out.putInt(o + 44, (dv & 0xFFFF) << 16);
        BILLBOARDS.increment();
        return true;
    }

    /** Round 4 stats: billboard records written. */
    public static final java.util.concurrent.atomic.LongAdder BILLBOARDS = new java.util.concurrent.atomic.LongAdder();

    /**
     * Test mirror of the shader's decode (TerrainVertexMsl.COMPACT_FETCH): {x, y, z (blocks), u, v, colour bits, UV2 u, UV2 v}
     * of corner {@code k} of record {@code q}.
     */
    static float[] decode(final ByteBuffer r, final int q, final int k, final int atlasW, final int atlasH) {
        int o = q * RECORD_BYTES;
        int ax = r.getInt(o);
        int ay = r.getInt(o + 4);
        float fa = k == 1 || k == 2 ? 1.0F : 0.0F;
        float fb = k >= 2 ? 1.0F : 0.0F;
        float[] p = {ax & 1023, ax >>> 10 & 1023, ax >>> 20 & 1023};
        float l1 = (ay << 18) >> 20;
        float l2 = (ay << 4) >> 20;
        if ((ay & 3) != 3) {
            p[ay & 3] += fa * l1;   // axis 3 = billboard (round 4): the shader offsets corners 1 / 2 toward the camera's right
        }
        p[ay >>> 14 & 3] += fb * l2;
        float unit = (1 << (ay >>> 28 & 7)) * 0.03125F;
        int cx = r.getInt(o + 32);
        int cy = r.getInt(o + 36);
        int cz = r.getInt(o + 40);
        int cw = r.getInt(o + 44);
        float u = (cx & 1048575) + fa * (float)((cz << 16) >> 16) + fb * (float)((cw << 16) >> 16);
        float v = (cy & 1048575) + fa * (float)(cz >> 16) + fb * (float)(cw >> 16);
        int col = r.getInt(o + 8 + 4 * k);
        int lw = r.getInt(o + (k < 2 ? 24 : 28)) >>> ((k & 1) * 16);
        return new float[]{p[0] * unit, p[1] * unit, p[2] * unit, u * (1.0F / (16.0F * atlasW)), v * (1.0F / (16.0F * atlasH)), Float.intBitsToFloat(col),
            (lw & 255) | (cx >>> 20 & 255) << 8, (lw >>> 8 & 255) | (cy >>> 20 & 255) << 8};
    }
}
