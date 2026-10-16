package net.minecraft.client.renderer.chunk;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

/**
 * Round 12 Part B (docs/QUEUE.md item 4, CONTRACT.md 16.R12-G): greedy meshing PROTOTYPE, not exact, default OFF, not shipped.
 *
 * <p>{@code -Dmc.terrain.greedy=exact|aggressive} (startup): every SOLID / CUTOUT section-layer additionally gets a greedy
 * copy of its quads, appended after the vanilla quads in the same vertex allocation ({@code [vanilla quads][greedy quads]},
 * both in S1 layout A); {@link #draw} (abab-switchable, read once per frame by {@link TerrainDrawList}) draws the greedy
 * copy with {@code RenderPipelines.*_TERRAIN_GREEDY_MULTIDRAW} instead of the vanilla quads. Both arms draw from the same
 * meshes in the same session, so the in-session A/B measures only the drawing.
 *
 * <p><b>What merges.</b> Full-block faces: an axis-aligned planar quad of the facing buckets (S1, TerrainMeshInfo) whose
 * corners are the four corners of one unit cell of its plane, whose UVs are an affine map of the cell onto exactly one
 * 16x16-texel tile of the block atlas aligned to the 16-texel grid (any rotation / flip), with no other quad of the same
 * facing and plane overlapping the cell (overlays such as the grass side keep their draw order: their cells never merge).
 * Faces with the same facing, plane, tile, UV orientation and vertex order are merged into rectangles (greedy: widest run,
 * then as many full rows as fit), cell by cell inside the section. {@code exact}: only faces whose four corners carry the
 * same colour and the same light, all equal across the rectangle, so every interpolated value is the same constant as before.
 * {@code aggressive}: also across colour / light (AO, light level, tint) differences; the rectangle's corners take the values
 * of the corner faces' corners, interpolated across the rectangle (AO and light gradients inside it are lost).
 *
 * <p><b>Drawing a merged face.</b> The texture repeats once per block, which the atlas cannot do by itself: the vertex UVs
 * run on unwrapped across the rectangle ({@code uv00 + i du + j dv} at cell corner (i, j)), and the tile index (+1) rides in
 * the high bytes of UV2 (light values are below 256). The greedy pipelines' vertex stage masks the light back to the low byte
 * and passes the tile as a flat varying; the fragment stage wraps the UV into the tile ({@code origin + mod(uv - origin, 16
 * texels)}) and samples with the derivatives of the unwrapped UV (same mip level as the per-block quads). Quads that did not
 * merge have tile 0 and are drawn as vanilla draws them.
 *
 * <p><b>Not exact</b>, even in {@code exact} mode: T-junctions where a rectangle's edge meets smaller neighbours (rasterization
 * is not watertight there: single-pixel cracks are possible), the fog distance interpolated across a larger triangle (round
 * 11's per-vertex fog and vanilla's per-vertex distances are not linear in the plane), and UV rounding at the wrap seams
 * (a texel of the neighbouring column at a few pixels). Screenshot pairs: share/greedy (round 12 report).
 */
public final class TerrainGreedy {
    /** 0 off, 1 exact, 2 aggressive. */
    public static final int MODE = mode(System.getProperty("mc.terrain.greedy", "off"));
    public static final boolean BUILD = MODE != 0;
    public static volatile boolean draw = BUILD && flag("mc.terrain.greedy.use", true);
    private static volatile int atlasWidth;
    private static volatile int atlasHeight;
    public static final AtomicLong LAYERS = new AtomicLong();
    public static final AtomicLong QUADS_IN = new AtomicLong();
    public static final AtomicLong CANDIDATES = new AtomicLong();
    public static final AtomicLong QUADS_OUT = new AtomicLong();
    private static final int STRIDE = 28;
    private static final int GRID = 48;
    private static final int GRID_BIAS = 16;

    private TerrainGreedy() {
    }

    static int mode(final String value) {
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "exact", "1", "true" -> 1;
            case "aggressive", "2" -> 2;
            default -> 0;
        };
    }

    /** TextureAtlas: the block atlas size, when it is (re)created. */
    public static void setAtlasSize(final int width, final int height) {
        atlasWidth = width;
        atlasHeight = height;
    }

    /** Stage 3 (LodGreedy): the block atlas size (0 before the atlas exists). */
    static int atlasWidth() {
        return atlasWidth;
    }

    static int atlasHeight() {
        return atlasHeight;
    }

    /** Statistics line (meshStats). */
    public static String stats() {
        long in = QUADS_IN.get();
        long out = QUADS_OUT.get();
        return String.format(java.util.Locale.ROOT, "greedy (%s): %d layers, %d quads -> %d (%.1f %%), %d merge candidates", MODE == 1 ? "exact" : "aggressive",
            LAYERS.get(), in, out, in == 0 ? 0.0 : 100.0 * out / in, CANDIDATES.get());
    }

    /** One quad as read from the vertex buffer. */
    private static final class Face {
        int quad;
        int slot;
        int cellX;
        int cellY;
        long key;
        /** Vertex k sits at cell corner (ci[k], cj[k]). */
        final int[] ci = new int[4];
        final int[] cj = new int[4];
        final int[] color = new int[4];
        final int[] light = new int[4];
        float u00;
        float v00;
        float duU;
        float duV;
        float dvU;
        float dvV;
        int tile;
        boolean merged;
    }

    /** Tests: {@link #merge(ByteBuffer, int, int[])} with the layer's own bucket layout. */
    public static @Nullable ByteBuffer merge(final ByteBuffer buffer, final int quads, final TerrainMeshInfo info) {
        return merge(buffer, quads, info.slotQuadStartArray());
    }

    /**
     * Builds the greedy copy of a layout-A layer ({@code quads} quads at {@code buffer}'s position, 28-byte BLOCK vertices,
     * {@code slotStart} its buckets); returns its vertex bytes (not yet in layout A) or null when nothing merged.
     */
    static @Nullable ByteBuffer merge(final ByteBuffer buffer, final int quads, final int[] slotStart) {
        int aw = atlasWidth;
        int ah = atlasHeight;
        if (!BUILD || aw <= 0 || ah <= 0 || quads < 2) {
            return null;
        }
        ByteBuffer b = buffer.duplicate().order(ByteOrder.nativeOrder());
        int base = buffer.position();
        float tileU = 16.0F / aw;
        float tileV = 16.0F / ah;
        Face[] faces = new Face[quads];
        // Cells covered by each (slot, plane): more than one quad on a cell keeps that cell out of every merge.
        Map<Long, int[]> cover = new java.util.HashMap<>();
        float[] p = new float[12];
        int candidates = 0;
        for (int slot = 0; slot < TerrainMeshInfo.SLOT_COUNT; slot++) {
            if (slot == TerrainMeshInfo.SLOT_UNASSIGNED) {
                continue;
            }
            int a = TerrainMeshInfo.slotAxis(slot);
            int u = (a + 1) % 3;
            int v = (a + 2) % 3;
            for (int q = slotStart[slot]; q < slotStart[slot + 1]; q++) {
                int at = base + q * 4 * STRIDE;
                for (int k = 0; k < 4; k++) {
                    p[3 * k] = b.getFloat(at + k * STRIDE);
                    p[3 * k + 1] = b.getFloat(at + k * STRIDE + 4);
                    p[3 * k + 2] = b.getFloat(at + k * STRIDE + 8);
                }
                float plane = p[a];
                float minU = Math.min(Math.min(p[u], p[3 + u]), Math.min(p[6 + u], p[9 + u]));
                float maxU = Math.max(Math.max(p[u], p[3 + u]), Math.max(p[6 + u], p[9 + u]));
                float minV = Math.min(Math.min(p[v], p[3 + v]), Math.min(p[6 + v], p[9 + v]));
                float maxV = Math.max(Math.max(p[v], p[3 + v]), Math.max(p[6 + v], p[9 + v]));
                if (!Float.isFinite(minU + maxU + minV + maxV + plane)) {
                    continue;
                }
                long planeKey = (long)slot << 32 | Float.floatToIntBits(plane + 0.0F) & 0xFFFFFFFFL;
                int[] grid = cover.computeIfAbsent(planeKey, k -> new int[GRID * GRID]);
                int x0 = (int)Math.floor(minU) + GRID_BIAS;
                int x1 = (int)Math.ceil(maxU) + GRID_BIAS;
                int y0 = (int)Math.floor(minV) + GRID_BIAS;
                int y1 = (int)Math.ceil(maxV) + GRID_BIAS;
                for (int y = Math.max(0, y0); y < Math.min(GRID, Math.max(y1, y0 + 1)); y++) {
                    for (int x = Math.max(0, x0); x < Math.min(GRID, Math.max(x1, x0 + 1)); x++) {
                        grid[y * GRID + x]++;
                    }
                }
                Face f = candidate(b, at, p, u, v, minU, minV, maxU, maxV, tileU, tileV, aw, ah);
                if (f == null) {
                    continue;
                }
                f.quad = q;
                f.slot = slot;
                f.key = f.key * 31L + planeKey;
                faces[q] = f;
                candidates++;
            }
        }
        if (candidates < 2) {
            return null;
        }
        // Group by key (first appearance order), dropping faces on shared cells.
        Map<Long, List<Face>> groups = new LinkedHashMap<>();
        for (Face f : faces) {
            if (f == null) {
                continue;
            }
            long planeKey = (long)f.slot << 32 | Float.floatToIntBits(planeOf(b, base, f) + 0.0F) & 0xFFFFFFFFL;
            int[] grid = cover.get(planeKey);
            int gx = f.cellX + GRID_BIAS;
            int gy = f.cellY + GRID_BIAS;
            if (gx < 0 || gy < 0 || gx >= GRID || gy >= GRID || grid[gy * GRID + gx] != 1) {
                continue;
            }
            groups.computeIfAbsent(f.key, k -> new ArrayList<>()).add(f);
        }
        ByteBuffer merged = ByteBuffer.allocateDirect(quads * 4 * STRIDE).order(ByteOrder.nativeOrder());
        int mergedQuads = 0;
        int[] occupancy = new int[GRID * GRID];
        for (List<Face> group : groups.values()) {
            if (group.size() < 2) {
                continue;
            }
            Arrays.fill(occupancy, -1);
            for (int i = 0; i < group.size(); i++) {
                Face f = group.get(i);
                occupancy[(f.cellY + GRID_BIAS) * GRID + f.cellX + GRID_BIAS] = i;
            }
            for (int y = 0; y < GRID; y++) {
                for (int x = 0; x < GRID; x++) {
                    if (occupancy[y * GRID + x] < 0) {
                        continue;
                    }
                    int w = 1;
                    while (x + w < GRID && occupancy[y * GRID + x + w] >= 0) {
                        w++;
                    }
                    int h = 1;
                    grow:
                    while (y + h < GRID) {
                        for (int i = 0; i < w; i++) {
                            if (occupancy[(y + h) * GRID + x + i] < 0) {
                                break grow;
                            }
                        }
                        h++;
                    }
                    if (w * h > 1) {
                        Face[] corner = {
                            group.get(occupancy[y * GRID + x]), group.get(occupancy[y * GRID + x + w - 1]),
                            group.get(occupancy[(y + h - 1) * GRID + x]), group.get(occupancy[(y + h - 1) * GRID + x + w - 1])
                        };
                        writeMerged(b, base, merged, corner, w, h);
                        mergedQuads++;
                        for (int j = 0; j < h; j++) {
                            for (int i = 0; i < w; i++) {
                                group.get(occupancy[(y + j) * GRID + x + i]).merged = true;
                            }
                        }
                    }
                    for (int j = 0; j < h; j++) {
                        for (int i = 0; i < w; i++) {
                            occupancy[(y + j) * GRID + x + i] = -1;
                        }
                    }
                }
            }
        }
        if (mergedQuads == 0) {
            return null;
        }
        int kept = 0;
        for (Face f : faces) {
            if (f == null || !f.merged) {
                kept++;
            }
        }
        ByteBuffer out = ByteBuffer.allocateDirect((kept + mergedQuads) * 4 * STRIDE).order(ByteOrder.nativeOrder());
        byte[] quad = new byte[4 * STRIDE];
        for (int q = 0; q < quads; q++) {
            if (faces[q] == null || !faces[q].merged) {
                b.get(base + q * 4 * STRIDE, quad);
                out.put(quad);
            }
        }
        merged.flip();
        out.put(merged);
        out.flip();
        LAYERS.incrementAndGet();
        QUADS_IN.addAndGet(quads);
        QUADS_OUT.addAndGet(kept + mergedQuads);
        CANDIDATES.addAndGet(candidates);
        return out;
    }

    private static float planeOf(final ByteBuffer b, final int base, final Face f) {
        int a = TerrainMeshInfo.slotAxis(f.slot);
        return b.getFloat(base + f.quad * 4 * STRIDE + 4 * a);
    }

    /** The face if quad {@code at} is a merge candidate (see the class doc), else null. */
    private static @Nullable Face candidate(final ByteBuffer b, final int at, final float[] p, final int u, final int v, final float minU, final float minV,
        final float maxU, final float maxV, final float tileU, final float tileV, final int aw, final int ah) {
        if (maxU - minU != 1.0F || maxV - minV != 1.0F || minU != (float)Math.floor(minU) || minV != (float)Math.floor(minV)) {
            return null;
        }
        Face f = new Face();
        int seen = 0;
        float[][] uv = new float[4][2];
        for (int k = 0; k < 4; k++) {
            float pu = p[3 * k + u];
            float pv = p[3 * k + v];
            if (pu != minU && pu != maxU || pv != minV && pv != maxV) {
                return null;
            }
            int i = pu == maxU ? 1 : 0;
            int j = pv == maxV ? 1 : 0;
            seen |= 1 << (i + 2 * j);
            f.ci[k] = i;
            f.cj[k] = j;
            int vat = at + k * STRIDE;
            f.color[k] = b.getInt(vat + 12);
            uv[i + 2 * j][0] = b.getFloat(vat + 16);
            uv[i + 2 * j][1] = b.getFloat(vat + 20);
            int l = b.getInt(vat + 24);
            if ((l & 0xFF00FF00) != 0) {
                return null;   // light above 255 (never in vanilla): the tile could not ride in the high bytes
            }
            f.light[k] = l;
        }
        if (seen != 15) {
            return null;
        }
        float duU = uv[1][0] - uv[0][0];
        float duV = uv[1][1] - uv[0][1];
        float dvU = uv[2][0] - uv[0][0];
        float dvV = uv[2][1] - uv[0][1];
        if (uv[3][0] != uv[0][0] + duU + dvU || uv[3][1] != uv[0][1] + duV + dvV) {
            return null;
        }
        boolean axisU = duV == 0.0F && Math.abs(duU) == tileU && dvU == 0.0F && Math.abs(dvV) == tileV;
        boolean axisV = duU == 0.0F && Math.abs(duV) == tileV && dvV == 0.0F && Math.abs(dvU) == tileU;
        if (!axisU && !axisV) {
            return null;
        }
        float uMin = Math.min(Math.min(uv[0][0], uv[1][0]), Math.min(uv[2][0], uv[3][0]));
        float vMin = Math.min(Math.min(uv[0][1], uv[1][1]), Math.min(uv[2][1], uv[3][1]));
        double tu = (double)uMin * aw / 16.0;
        double tv = (double)vMin * ah / 16.0;
        if (tu != Math.rint(tu) || tv != Math.rint(tv) || tu < 0 || tv < 0 || tu > 254 || tv > 254) {
            return null;
        }
        boolean uniform = true;
        for (int k = 1; k < 4; k++) {
            uniform &= f.color[k] == f.color[0] && f.light[k] == f.light[0];
        }
        if (MODE == 1 && !uniform) {
            return null;
        }
        f.cellX = (int)minU;
        f.cellY = (int)minV;
        f.u00 = uv[0][0];
        f.v00 = uv[0][1];
        f.duU = duU;
        f.duV = duV;
        f.dvU = dvU;
        f.dvV = dvV;
        f.tile = ((int)tu + 1) | ((int)tv + 1) << 8;
        int order = 0;
        for (int k = 0; k < 4; k++) {
            order = order << 2 | f.ci[k] | f.cj[k] << 1;
        }
        // Key: tile, orientation, vertex order (and in exact mode the constant colour and light; the plane is added by the caller).
        long key = f.tile;
        key = key * 31L + (Float.floatToIntBits(duU) ^ Float.floatToIntBits(duV) * 7 ^ Float.floatToIntBits(dvU) * 13 ^ Float.floatToIntBits(dvV) * 17);
        key = key * 31L + (long)Float.floatToIntBits(f.u00) * 3 + Float.floatToIntBits(f.v00);
        key = key * 31L + order;
        if (MODE == 1) {
            key = key * 31L + f.color[0];
            key = key * 31L + f.light[0];
        }
        f.key = key;
        return f;
    }

    /**
     * One merged quad over w x h cells: the vertex order of the rectangle's first face, positions at the rectangle corners,
     * unwrapped UVs, colour / light from the corner faces (equal to every face's in exact mode), the tile in UV2's high bytes.
     */
    private static void writeMerged(final ByteBuffer b, final int base, final ByteBuffer out, final Face[] corner, final int w, final int h) {
        Face first = corner[0];
        int a = TerrainMeshInfo.slotAxis(first.slot);
        int u = (a + 1) % 3;
        int v = (a + 2) % 3;
        float plane = b.getFloat(base + first.quad * 4 * STRIDE + 4 * a);
        for (int k = 0; k < 4; k++) {
            int i = first.ci[k];
            int j = first.cj[k];
            float[] pos = new float[3];
            pos[a] = plane;
            pos[u] = first.cellX + i * w;
            pos[v] = first.cellY + j * h;
            Face src = corner[i + 2 * j];
            int sk = 0;
            for (int m = 0; m < 4; m++) {
                if (src.ci[m] == i && src.cj[m] == j) {
                    sk = m;
                }
            }
            float uu = first.u00 + (i * w) * first.duU + (j * h) * first.dvU;
            float vv = first.v00 + (i * w) * first.duV + (j * h) * first.dvV;
            int light = src.light[sk];
            int packed = (light & 0xFF) | (first.tile & 0xFF) << 8 | (light >>> 16 & 0xFF) << 16 | (first.tile >>> 8 & 0xFF) << 24;
            out.putFloat(pos[0]).putFloat(pos[1]).putFloat(pos[2]).putInt(src.color[sk]).putFloat(uu).putFloat(vv).putInt(packed);
        }
    }

    private static boolean flag(final String name, final boolean defaultValue) {
        String value = System.getProperty(name);
        return value == null ? defaultValue : !value.equalsIgnoreCase("false") && !value.equals("0");
    }
}
