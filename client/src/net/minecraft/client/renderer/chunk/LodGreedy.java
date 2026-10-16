package net.minecraft.client.renderer.chunk;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stage 3 round 1b (docs/stage3/LOD-DESIGN.md §3.1): quad reduction of one meshed LOD slab-layer, run on the mesher's
 * section-relative cell positions (before the 2^level scale). Two passes:
 *
 * <p><b>1. Back-to-back pairs (SOLID only).</b> Two full-cell quads in the same integer plane with opposite facings over the
 * same cell are both removed. Every SOLID quad is opaque, so such a pair is two cubes' shared faces: each face's front side
 * lies inside the other (opaque) cube and can never be seen. Vanilla's culler leaves these between non-occluding full
 * cubes, i.e. leaves; the LOD meshes leaves opaque ({@code mc.lod.cutoutLeaves=false}), so canopies keep only their hull.
 *
 * <p><b>2. Greedy rectangles.</b> Round 12's prototype rule (TerrainGreedy): an axis-aligned full-cell quad whose UVs map the
 * cell onto exactly one 16x16 atlas tile merges with faces of the same facing, plane, tile, UV orientation and vertex order
 * into rectangles (widest run, then full rows); the merged face carries unwrapped UVs and the tile in UV2's high bytes, and
 * the LOD greedy pipelines ({@code RenderPipelines.*_LOD_GREEDY_CLUSTER}) wrap the UV back into the tile with the
 * unwrapped UV's derivatives (same mip level as per-block faces). Colour rule: {@code tol} = the largest channel
 * difference allowed between the four corners of a face and between a face and the rectangle's first face (0 = exact:
 * vanilla's per-vertex values; AO corners and light gradients never merge); light must be equal. A merged face takes the
 * corner faces' corner colours, so the interpolated colour stays within {@code tol} of every covered face.
 */
public final class LodGreedy {
    public static final AtomicLong QUADS_IN = new AtomicLong();
    public static final AtomicLong QUADS_OUT = new AtomicLong();
    public static final AtomicLong PAIRS_REMOVED = new AtomicLong();
    public static final AtomicLong MERGED_FACES = new AtomicLong();
    private static final int STRIDE = LodMesher.STRIDE;
    private static final int GRID = 32;
    /** {@code tol >= AGGRESSIVE}: merge regardless of colour and light (round 12's aggressive rule: corner values interpolated). */
    public static final int AGGRESSIVE = 256;
    /**
     * Loose keys ({@code mc.lod.mergeLoose}, default true): faces merge across UV orientation and vertex order, i.e. across
     * vanilla's random texture rotations (grass, sand, ... tops) and its AO-driven diagonal flips; the merged face takes its
     * first face's orientation (the per-block rotation pattern is lost inside it, invisible at LOD distances).
     */
    public static volatile boolean LOOSE = !"false".equalsIgnoreCase(System.getProperty("mc.lod.mergeLoose"));
    private static final int BIAS = 8;
    /**
     * Lit merge (Lod.LIT_MERGE): the tables of the lit merged faces of the last {@link #reduce} on this thread. A lit merged
     * face has corner 0's colour = its table index and every corner's light bytes = {@link #LIT_LIGHT} (impossible light:
     * vanilla's are <= 240); LodCompact.encode turns them into lit records. Table: w, h, corner bits (corner k's (i, j) at bits
     * 2k / 2k + 1), then per cell (row-major, i along the face's u axis) 8 ints: colours of canonical corners 00, 10, 11, 01;
     * light (block | sky << 8) of 00 | 10 << 16; of 11 | 01 << 16; diagonal (0: triangles split along 00-11, 1: along 10-01); 0.
     */
    public static final ThreadLocal<List<int[]>> LIT_TABLES = ThreadLocal.withInitial(ArrayList::new);
    public static final int LIT_LIGHT = 0x00FF00FF;
    public static final AtomicLong LIT_FACES = new AtomicLong();
    public static final AtomicLong LIT_CELLS = new AtomicLong();

    private LodGreedy() {
    }

    private static final class Face {
        int quad;
        int slot;
        float plane;
        int cellX;
        int cellY;
        long key;
        /** Per corner k: [k] ci, [4 + k] cj, [8 + k] colour, [12 + k] light (one array: a face is allocated per quad). */
        final int[] d = new int[16];
        float u00;
        float v00;
        float duU;
        float duV;
        float dvU;
        float dvV;
        int tile;
        boolean candidate;
        boolean merged;
        boolean removed;
    }

    /**
     * Returns the reduced copy of {@code quads} quads at position 0 of {@code buf} (28-byte BLOCK vertices, any order), or
     * {@code buf} itself when nothing changed. {@code tol < 0}: no greedy pass.
     */
    static ByteBuffer reduce(final ByteBuffer buf, final int quads, final boolean removePairs, final int tol) {
        return reduce(buf, quads, removePairs, tol, TerrainGreedy.atlasWidth(), TerrainGreedy.atlasHeight(), false);
    }

    /** {@link #reduce(ByteBuffer, int, boolean, int)}; {@code lit}: lit merge (tables in {@link #LIT_TABLES}). */
    static ByteBuffer reduce(final ByteBuffer buf, final int quads, final boolean removePairs, final int tol, final boolean lit) {
        return reduce(buf, quads, removePairs, tol, TerrainGreedy.atlasWidth(), TerrainGreedy.atlasHeight(), lit);
    }

    /** Diagnostics: reasons full-cell faces did not become merge candidates (partial quads, colour / light, UV). */
    /** {@code -Dmc.lod.greedyDiag}: per-face rejection counters (tools/LodGreedyTool); off in game, the shared atomics contend across workers. */
    static final boolean DIAG = Boolean.getBoolean("mc.lod.greedyDiag");
    public static final AtomicLong REJ_PARTIAL = new AtomicLong();
    public static final AtomicLong REJ_COLOR = new AtomicLong();
    public static final AtomicLong REJ_UV = new AtomicLong();
    public static final AtomicLong REJ_COVER = new AtomicLong();
    public static final AtomicLong SINGLES = new AtomicLong();

    /** {@link #reduce(ByteBuffer, int, boolean, int)} with an explicit atlas size (tools/LodGreedyTool.java). */
    public static ByteBuffer reduce(final ByteBuffer buf, final int quads, final boolean removePairs, final int tol, final int aw, final int ah) {
        return reduce(buf, quads, removePairs, tol, aw, ah, false);
    }

    public static ByteBuffer reduce(final ByteBuffer buf, final int quads, final boolean removePairs, final int tol, final int aw, final int ah,
                                    final boolean lit) {
        List<int[]> tables = LIT_TABLES.get();
        tables.clear();
        if (quads < 2) {
            return buf;
        }
        ByteBuffer b = buf.duplicate().order(ByteOrder.nativeOrder());
        float tileU = aw > 0 ? 16.0F / aw : Float.NaN;
        float tileV = ah > 0 ? 16.0F / ah : Float.NaN;
        boolean greedy = tol >= 0 && aw > 0 && ah > 0;
        Face[] faces = new Face[quads];
        float[] p = new float[12];
        // (slot, plane) -> per-cell count of quads covering the cell (more than one: the cell never merges, e.g. overlays).
        Long2ObjectOpenHashMap<int[]> cover = new Long2ObjectOpenHashMap<>();
        for (int q = 0; q < quads; q++) {
            int at = q * 4 * STRIDE;
            for (int k = 0; k < 4; k++) {
                p[3 * k] = b.getFloat(at + k * STRIDE);
                p[3 * k + 1] = b.getFloat(at + k * STRIDE + 4);
                p[3 * k + 2] = b.getFloat(at + k * STRIDE + 8);
            }
            int slot = TerrainMeshInfo.classifyQuad(p);
            if (slot == TerrainMeshInfo.SLOT_UNASSIGNED) {
                continue;
            }
            int a = TerrainMeshInfo.slotAxis(slot);
            int u = (a + 1) % 3;
            int v = (a + 2) % 3;
            float minU = Math.min(Math.min(p[u], p[3 + u]), Math.min(p[6 + u], p[9 + u]));
            float maxU = Math.max(Math.max(p[u], p[3 + u]), Math.max(p[6 + u], p[9 + u]));
            float minV = Math.min(Math.min(p[v], p[3 + v]), Math.min(p[6 + v], p[9 + v]));
            float maxV = Math.max(Math.max(p[v], p[3 + v]), Math.max(p[6 + v], p[9 + v]));
            if (maxU - minU != 1.0F || maxV - minV != 1.0F || minU != (float)Math.floor(minU) || minV != (float)Math.floor(minV)) {
                // Not one full cell: kept as is; the cells it touches in its plane never merge (no overlap changes).
                if (Float.isFinite(minU + maxU + minV + maxV + p[a])) {
                    int[] grid = cover.computeIfAbsent(planeKey(slot, p[a] + 0.0F), k -> new int[GRID * GRID]);
                    int x1 = Math.min(GRID, (int)Math.ceil(maxU) + BIAS);
                    int y1 = Math.min(GRID, (int)Math.ceil(maxV) + BIAS);
                    for (int y = Math.max(0, (int)Math.floor(minV) + BIAS); y < Math.max(y1, (int)Math.floor(minV) + BIAS + 1) && y < GRID; y++) {
                        for (int x = Math.max(0, (int)Math.floor(minU) + BIAS); x < Math.max(x1, (int)Math.floor(minU) + BIAS + 1) && x < GRID; x++) {
                            grid[y * GRID + x] += 2;
                        }
                    }
                }
                if (DIAG) REJ_PARTIAL.incrementAndGet();
                continue;
            }
            int gx = (int)minU + BIAS;
            int gy = (int)minV + BIAS;
            if (gx < 0 || gy < 0 || gx >= GRID || gy >= GRID) {
                continue;
            }
            boolean corners = true;
            Face f = new Face();
            for (int k = 0; k < 4; k++) {
                float pu = p[3 * k + u];
                float pv = p[3 * k + v];
                if (pu != minU && pu != maxU || pv != minV && pv != maxV) {
                    corners = false;
                }
                f.d[k] = pu == maxU ? 1 : 0;
                f.d[4 + k] = pv == maxV ? 1 : 0;
            }
            if (!corners) {
                continue;
            }
            f.quad = q;
            f.slot = slot;
            f.plane = p[a] + 0.0F;
            f.cellX = (int)minU;
            f.cellY = (int)minV;
            faces[q] = f;
            long planeKey = planeKey(slot, f.plane);
            cover.computeIfAbsent(planeKey, k -> new int[GRID * GRID])[gy * GRID + gx]++;
            if (greedy) {
                candidate(b, at, f, tileU, tileV, aw, ah, lit && tol >= 0 ? AGGRESSIVE : tol);
            }
        }
        int removed = 0;
        if (removePairs) {
            Long2ObjectOpenHashMap<List<Face>> byCell = new Long2ObjectOpenHashMap<>();
            for (Face f : faces) {
                if (f != null && f.plane == (float)Math.floor(f.plane)) {
                    byCell.computeIfAbsent(cellKey(f.slot, f.plane, f.cellX, f.cellY), k -> new ArrayList<>(1)).add(f);
                }
            }
            for (List<Face> mine : byCell.values()) {
                Face f = mine.get(0);
                if (f.removed || !TerrainMeshInfo.slotPositive(f.slot)) {
                    continue;
                }
                List<Face> other = byCell.get(cellKey(oppositeSlot(f.slot), f.plane, f.cellX, f.cellY));
                if (other == null) {
                    continue;
                }
                for (Face o : mine) {
                    o.removed = true;
                    removed++;
                }
                for (Face o : other) {
                    o.removed = true;
                    removed++;
                }
            }
        }
        // Greedy groups (first appearance order), excluding cells covered more than once and removed faces.
        Long2ObjectLinkedOpenHashMap<List<Face>> groups = new Long2ObjectLinkedOpenHashMap<>();
        if (greedy) {
            for (Face f : faces) {
                if (f == null || !f.candidate || f.removed) {
                    continue;
                }
                int[] grid = cover.get(planeKey(f.slot, f.plane));
                if (grid[(f.cellY + BIAS) * GRID + f.cellX + BIAS] != 1) {
                    if (DIAG) REJ_COVER.incrementAndGet();
                    continue;
                }
                groups.computeIfAbsent(f.key, k -> new ArrayList<>()).add(f);
            }
        }
        ByteBuffer merged = null;
        int mergedQuads = 0;
        int[] occupancy = new int[GRID * GRID];
        // every occupied cell is cleared when it seeds or joins a rectangle, so the grid is empty again after each group:
        // filled once, and each group scans only its own cells' bounding box (cells outside it are empty: same result)
        Arrays.fill(occupancy, -1);
        for (List<Face> group : groups.values()) {
            if (group.size() < 2) {
                if (DIAG) SINGLES.incrementAndGet();
                continue;
            }
            int bx0 = GRID, by0 = GRID, bx1 = -1, by1 = -1;
            for (int i = 0; i < group.size(); i++) {
                Face f = group.get(i);
                int cx = f.cellX + BIAS, cy = f.cellY + BIAS;
                occupancy[cy * GRID + cx] = i;
                bx0 = Math.min(bx0, cx);
                bx1 = Math.max(bx1, cx);
                by0 = Math.min(by0, cy);
                by1 = Math.max(by1, cy);
            }
            for (int y = by0; y <= by1; y++) {
                for (int x = bx0; x <= bx1; x++) {
                    int seedIndex = occupancy[y * GRID + x];
                    if (seedIndex < 0) {
                        continue;
                    }
                    Face seed = group.get(seedIndex);
                    int w = 1;
                    int mergeTol = lit && tol >= 0 ? AGGRESSIVE : tol;
                    while (x + w < GRID && compatible(seed, group, occupancy[y * GRID + x + w], mergeTol)) {
                        w++;
                    }
                    int h = 1;
                    grow:
                    while (y + h < GRID) {
                        for (int i = 0; i < w; i++) {
                            if (!compatible(seed, group, occupancy[(y + h) * GRID + x + i], mergeTol)) {
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
                        if (merged == null) {
                            merged = ByteBuffer.allocate(quads * 4 * STRIDE).order(ByteOrder.nativeOrder());
                        }
                        boolean uniform = true;
                        if (lit && tol < AGGRESSIVE) {
                            for (int j = 0; j < h && uniform; j++) {
                                for (int i = 0; i < w && uniform; i++) {
                                    Face c = group.get(occupancy[(y + j) * GRID + x + i]);
                                    for (int k = 0; k < 4; k++) {
                                        if (c.d[12 + k] != seed.d[12] || !close(c.d[8 + k], seed.d[8], tol)) {
                                            uniform = false;
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                        if (uniform) {
                            writeMerged(merged, corner, w, h);
                        } else {
                            writeMergedLit(merged, corner, group, occupancy, x, y, w, h, tables);
                        }
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
        if (mergedQuads == 0 && removed == 0) {
            QUADS_IN.addAndGet(quads);
            QUADS_OUT.addAndGet(quads);
            return buf;
        }
        int kept = 0;
        for (Face f : faces) {
            if (f == null || !f.merged && !f.removed) {
                kept++;
            }
        }
        ByteBuffer out = ByteBuffer.allocateDirect(Math.max(1, kept + mergedQuads) * 4 * STRIDE).order(ByteOrder.nativeOrder());
        byte[] quad = new byte[4 * STRIDE];
        for (int q = 0; q < quads; q++) {
            Face f = faces[q];
            if (f == null || !f.merged && !f.removed) {
                b.get(q * 4 * STRIDE, quad);
                out.put(quad);
            }
        }
        if (merged != null) {
            merged.flip();
            out.put(merged);
        }
        out.flip();
        QUADS_IN.addAndGet(quads);
        QUADS_OUT.addAndGet(kept + mergedQuads);
        PAIRS_REMOVED.addAndGet(removed);
        MERGED_FACES.addAndGet(mergedQuads);
        return out;
    }

    private static long planeKey(final int slot, final float plane) {
        return (long)slot << 32 | Float.floatToIntBits(plane + 0.0F) & 0xFFFFFFFFL;
    }

    private static long cellKey(final int slot, final float plane, final int cellX, final int cellY) {
        return planeKey(slot, plane) | (long)(cellX + BIAS) << 40 | (long)(cellY + BIAS) << 48;
    }

    private static int oppositeSlot(final int slot) {
        return switch (slot) {
            case TerrainMeshInfo.SLOT_DOWN -> TerrainMeshInfo.SLOT_UP;
            case TerrainMeshInfo.SLOT_UP -> TerrainMeshInfo.SLOT_DOWN;
            case TerrainMeshInfo.SLOT_NORTH -> TerrainMeshInfo.SLOT_SOUTH;
            case TerrainMeshInfo.SLOT_SOUTH -> TerrainMeshInfo.SLOT_NORTH;
            case TerrainMeshInfo.SLOT_EAST -> TerrainMeshInfo.SLOT_WEST;
            case TerrainMeshInfo.SLOT_WEST -> TerrainMeshInfo.SLOT_EAST;
            default -> slot;
        };
    }

    private static boolean compatible(final Face seed, final List<Face> group, final int index, final int tol) {
        if (index < 0) {
            return false;
        }
        Face f = group.get(index);
        if (tol >= AGGRESSIVE) {
            return true;
        }
        for (int k = 0; k < 4; k++) {
            if (f.d[12 + k] != seed.d[12] || !close(f.d[8 + k], seed.d[8], tol)) {
                return false;
            }
        }
        return true;
    }

    private static boolean close(final int a, final int b, final int tol) {
        if (a == b) {
            return true;
        }
        for (int s = 0; s < 32; s += 8) {
            if (Math.abs((a >>> s & 0xFF) - (b >>> s & 0xFF)) > tol) {
                return false;
            }
        }
        return true;
    }

    /** Fills the merge fields of {@code f} and sets {@code f.candidate} when the quad maps its cell onto one atlas tile. */
    private static void candidate(final ByteBuffer b, final int at, final Face f, final float tileU, final float tileV, final int aw, final int ah, final int tol) {
        float[] uv = new float[8];
        for (int k = 0; k < 4; k++) {
            int vat = at + k * STRIDE;
            int i = f.d[k];
            int j = f.d[4 + k];
            f.d[8 + k] = b.getInt(vat + 12);
            uv[2 * (i + 2 * j)] = b.getFloat(vat + 16);
            uv[2 * (i + 2 * j) + 1] = b.getFloat(vat + 20);
            int l = b.getInt(vat + 24);
            if ((l & 0xFF00FF00) != 0) {
                return;
            }
            f.d[12 + k] = l;
        }
        for (int k = 1; k < 4 && tol < AGGRESSIVE; k++) {
            if (f.d[12 + k] != f.d[12] || !close(f.d[8 + k], f.d[8], tol)) {
                if (DIAG) REJ_COLOR.incrementAndGet();
                return;   // AO corner / light gradient beyond the tolerance: this face keeps its own quad
            }
        }
        float duU = uv[2] - uv[0];
        float duV = uv[3] - uv[1];
        float dvU = uv[4] - uv[0];
        float dvV = uv[5] - uv[1];
        if (uv[6] != uv[0] + duU + dvU || uv[7] != uv[1] + duV + dvV) {
            return;
        }
        boolean axisU = duV == 0.0F && Math.abs(duU) == tileU && dvU == 0.0F && Math.abs(dvV) == tileV;
        boolean axisV = duU == 0.0F && Math.abs(duV) == tileV && dvV == 0.0F && Math.abs(dvU) == tileU;
        if (!axisU && !axisV) {
            if (DIAG) REJ_UV.incrementAndGet();
            return;
        }
        float uMin = Math.min(Math.min(uv[0], uv[2]), Math.min(uv[4], uv[6]));
        float vMin = Math.min(Math.min(uv[1], uv[3]), Math.min(uv[5], uv[7]));
        double tu = (double)uMin * aw / 16.0;
        double tv = (double)vMin * ah / 16.0;
        if (tu != Math.rint(tu) || tv != Math.rint(tv) || tu < 0 || tv < 0 || tu > 254 || tv > 254) {
            if (DIAG) REJ_UV.incrementAndGet();
            return;
        }
        f.u00 = uv[0];
        f.v00 = uv[1];
        f.duU = duU;
        f.duV = duV;
        f.dvU = dvU;
        f.dvV = dvV;
        f.tile = ((int)tu + 1) | ((int)tv + 1) << 8;
        int order = 0;
        for (int k = 0; k < 4; k++) {
            order = order << 2 | f.d[k] | f.d[4 + k] << 1;
        }
        long key = f.tile;
        if (!LOOSE) {
            key = key * 31L + (Float.floatToIntBits(duU) ^ Float.floatToIntBits(duV) * 7 ^ Float.floatToIntBits(dvU) * 13 ^ Float.floatToIntBits(dvV) * 17);
            key = key * 31L + (long)Float.floatToIntBits(f.u00) * 3 + Float.floatToIntBits(f.v00);
            key = key * 31L + order;
        }
        key = key * 31L + planeKey(f.slot, f.plane);
        f.key = key;
        f.candidate = true;
    }

    /** One merged quad over w x h cells (TerrainGreedy#writeMerged's layout), appended to {@code out}. */
    private static void writeMerged(final ByteBuffer out, final Face[] corner, final int w, final int h) {
        Face first = corner[0];
        int a = TerrainMeshInfo.slotAxis(first.slot);
        int u = (a + 1) % 3;
        int v = (a + 2) % 3;
        for (int k = 0; k < 4; k++) {
            int i = first.d[k];
            int j = first.d[4 + k];
            float[] pos = new float[3];
            pos[a] = first.plane;
            pos[u] = first.cellX + i * w;
            pos[v] = first.cellY + j * h;
            Face src = corner[i + 2 * j];
            int sk = 0;
            for (int m = 0; m < 4; m++) {
                if (src.d[m] == i && src.d[4 + m] == j) {
                    sk = m;
                }
            }
            float uu = first.u00 + (i * w) * first.duU + (j * h) * first.dvU;
            float vv = first.v00 + (i * w) * first.duV + (j * h) * first.dvV;
            int light = src.d[12 + sk];
            int packed = (light & 0xFF) | (first.tile & 0xFF) << 8 | (light >>> 16 & 0xFF) << 16 | (first.tile >>> 8 & 0xFF) << 24;
            out.putFloat(pos[0]).putFloat(pos[1]).putFloat(pos[2]).putInt(src.d[8 + sk]).putFloat(uu).putFloat(vv).putInt(packed);
        }
    }

    /** A lit merged face (see {@link #LIT_TABLES}): writeMerged's positions and UVs, its table appended to {@code tables}. */
    private static void writeMergedLit(final ByteBuffer out, final Face[] corner, final List<Face> group, final int[] occupancy, final int x, final int y,
                                       final int w, final int h, final List<int[]> tables) {
        Face first = corner[0];
        int a = TerrainMeshInfo.slotAxis(first.slot);
        int u = (a + 1) % 3;
        int v = (a + 2) % 3;
        int[] t = new int[3 + 8 * w * h];
        t[0] = w;
        t[1] = h;
        for (int k = 0; k < 4; k++) {
            t[2] |= (first.d[k] | first.d[4 + k] << 1) << 2 * k;
        }
        int[] cc = new int[4];
        int[] cl = new int[4];
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                Face c = group.get(occupancy[(y + j) * GRID + x + i]);
                for (int k = 0; k < 4; k++) {
                    // canonical index: 00 -> 0, 10 -> 1, 11 -> 2, 01 -> 3
                    int ck = c.d[4 + k] == 0 ? c.d[k] : 3 - c.d[k];
                    cc[ck] = c.d[8 + k];
                    cl[ck] = (c.d[12 + k] & 0xFF) | (c.d[12 + k] >>> 16 & 0xFF) << 8;
                }
                int at = 3 + 8 * (j * w + i);
                t[at] = cc[0];
                t[at + 1] = cc[1];
                t[at + 2] = cc[2];
                t[at + 3] = cc[3];
                t[at + 4] = cl[0] | cl[1] << 16;
                t[at + 5] = cl[2] | cl[3] << 16;
                // vanilla's triangles are (0, 1, 2) and (2, 3, 0): split along corner 0 - corner 2
                t[at + 6] = c.d[0] == c.d[4] ? 0 : 1;
            }
        }
        int index = tables.size();
        tables.add(t);
        if (DIAG) LIT_FACES.incrementAndGet();
        if (DIAG) LIT_CELLS.addAndGet((long)w * h);
        for (int k = 0; k < 4; k++) {
            int i = first.d[k];
            int j = first.d[4 + k];
            float[] pos = new float[3];
            pos[a] = first.plane;
            pos[u] = first.cellX + i * w;
            pos[v] = first.cellY + j * h;
            float uu = first.u00 + (i * w) * first.duU + (j * h) * first.dvU;
            float vv = first.v00 + (i * w) * first.duV + (j * h) * first.dvV;
            int packed = LIT_LIGHT | (first.tile & 0xFF) << 8 | (first.tile >>> 8 & 0xFF) << 24;
            out.putFloat(pos[0]).putFloat(pos[1]).putFloat(pos[2]).putInt(k == 0 ? index : 0).putFloat(uu).putFloat(vv).putInt(packed);
        }
    }

    /**
     * Round 3 (coarse texture frequency): at level k ({@code factor} = 2^k blocks per cell) every face that maps whole atlas tiles
     * (merged faces, and single faces mapping exactly one tile) gets its UV extent scaled by {@code factor} from corner 0 and its
     * tile in UV2's high bytes, so the LOD greedy pipelines' tile wrap repeats the texture once per block with per-block mip
     * levels: coarse faces show the same texture frequency (and mip-averaged colour) as the real blocks instead of one texture
     * stretched over 2^k blocks. Positions, colours and light are unchanged. In place.
     */
    static void repeatTiles(final ByteBuffer buf, final int quads, final int factor, final int aw, final int ah) {
        if (factor <= 1 || aw <= 0 || ah <= 0) {
            return;
        }
        ByteBuffer b = buf.duplicate().order(ByteOrder.nativeOrder());
        float tileU = 16.0F / aw;
        float tileV = 16.0F / ah;
        float[] u = new float[4];
        float[] v = new float[4];
        for (int q = 0; q < quads; q++) {
            int at = q * 4 * STRIDE;
            int packed0 = b.getInt(at + 24);
            int tile = (packed0 >>> 8 & 0xFF) | (packed0 >>> 24 & 0xFF) << 8;
            for (int k = 0; k < 4; k++) {
                u[k] = b.getFloat(at + k * STRIDE + 16);
                v[k] = b.getFloat(at + k * STRIDE + 20);
            }
            if (tile == 0) {
                float uMin = Math.min(Math.min(u[0], u[1]), Math.min(u[2], u[3]));
                float uMax = Math.max(Math.max(u[0], u[1]), Math.max(u[2], u[3]));
                float vMin = Math.min(Math.min(v[0], v[1]), Math.min(v[2], v[3]));
                float vMax = Math.max(Math.max(v[0], v[1]), Math.max(v[2], v[3]));
                if (uMax - uMin != tileU || vMax - vMin != tileV) {
                    continue;   // not exactly one tile (partial faces, overlays): keeps its stretched texture
                }
                double tu = (double)uMin * aw / 16.0;
                double tv = (double)vMin * ah / 16.0;
                if (tu != Math.rint(tu) || tv != Math.rint(tv) || tu < 0 || tv < 0 || tu > 254 || tv > 254) {
                    continue;
                }
                boolean lightHigh = false;
                for (int k = 0; k < 4; k++) {
                    lightHigh |= (b.getInt(at + k * STRIDE + 24) & 0xFF00FF00) != 0;
                }
                if (lightHigh) {
                    continue;
                }
                tile = ((int)tu + 1) | ((int)tv + 1) << 8;
            }
            for (int k = 0; k < 4; k++) {
                int vat = at + k * STRIDE;
                b.putFloat(vat + 16, u[0] + (u[k] - u[0]) * factor);
                b.putFloat(vat + 20, v[0] + (v[k] - v[0]) * factor);
                int l = b.getInt(vat + 24);
                b.putInt(vat + 24, (l & 0x00FF00FF) | (tile & 0xFF) << 8 | (tile >>> 8 & 0xFF) << 24);
            }
            if (DIAG) TILES_REPEATED.incrementAndGet();
        }
    }

    public static final AtomicLong TILES_REPEATED = new AtomicLong();

    static String stats() {
        long in = QUADS_IN.get();
        long out = QUADS_OUT.get();
        return String.format(java.util.Locale.ROOT, "{\"quadsIn\": %d, \"quadsOut\": %d, \"pairsRemoved\": %d, \"mergedFaces\": %d}", in, out, PAIRS_REMOVED.get(),
            MERGED_FACES.get());
    }

}
