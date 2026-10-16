package net.minecraft.client.renderer.chunk;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * Immutable per section-layer metadata for the stage-2 terrain patches (docs/stage2/TERRAIN-PLAN.md S0b/S1/S2),
 * computed once when a section is meshed ({@link SectionCompiler#compile}) from the vertex positions exactly as they
 * were written to the vertex buffer.
 *
 * <p><b>Facing buckets (S1).</b> Every quad is classified by {@link #classifyQuad}: a quad whose four vertices share
 * one axis coordinate and whose two triangles ({@code (0,1,2)} and {@code (2,3,0)}, the pattern of the quad index
 * buffers) both have a strictly positive (negative) normal component on that axis is put in the bucket of that
 * direction; everything else (rotated, sloped, non-planar, bow-tie or degenerate quads) is UNASSIGNED and always
 * drawn. The sign convention is {@code FaceBakery.calculateFacing}, which vanilla renders as front faces with every
 * terrain pipeline culling back faces. For SOLID and CUTOUT the quads are then reordered in place by a stable
 * counting sort into the fixed layout A ({@code DOWN, NORTH, EAST, UP, UNASSIGNED, SOUTH, WEST}); quads share no
 * vertices (the index pattern is per quad), and quads of the same bucket keep their emission order, so coplanar
 * same-facing quads (overlays) keep their relative draw order. TRANSLUCENT is classified but never reordered (its
 * draw order is the sorted index buffer).
 *
 * <p><b>Front-to-back inside a bucket (S6, CUTOUT).</b> With {@code frontToBack} the quads of each facing bucket are
 * additionally ordered by their plane coordinate: descending for the +axis buckets (EAST, UP, SOUTH), ascending for the
 * -axis buckets (DOWN, NORTH, WEST). An eye sees a +axis quad only from the + side of its plane, so of two such quads
 * the one with the larger plane is nearer at every pixel both cover: the order is front-to-back for every eye, without
 * depending on the camera. Alpha-tested ({@code discard}) fragments are shaded whenever they pass the depth test (a
 * tile-based GPU's hidden-surface removal cannot drop them before shading), so drawing nearer cutout geometry first lets
 * the depth test reject the fragments behind it. Pixel-identical: quads of one bucket at different planes are parallel
 * and never meet at the same depth, CUTOUT does not blend, and the sort is stable (coplanar quads, e.g. overlays, keep
 * their emission order). UNASSIGNED quads keep their emission order.
 *
 * <p><b>Planes.</b> For a +axis bucket (EAST, UP, SOUTH) {@link #plane} is the smallest plane coordinate of its quads,
 * for a -axis bucket (DOWN, NORTH, WEST) the largest: an eye on the far side of that plane (by more than the margin)
 * sees every quad of the bucket from behind. Section-relative, NaN when the bucket is empty.
 *
 * <p><b>Bounds (S2).</b> Min/max of every vertex position, section-relative.
 */
public final class TerrainMeshInfo {
    public static final int SLOT_DOWN = 0;
    public static final int SLOT_NORTH = 1;
    public static final int SLOT_EAST = 2;
    public static final int SLOT_UP = 3;
    public static final int SLOT_UNASSIGNED = 4;
    public static final int SLOT_SOUTH = 5;
    public static final int SLOT_WEST = 6;
    public static final int SLOT_COUNT = 7;
    /** Bucket of the +x, +y, +z facing (by axis index 0, 1, 2). */
    private static final int[] POSITIVE = {SLOT_EAST, SLOT_UP, SLOT_SOUTH};
    /** Bucket of the -x, -y, -z facing (by axis index 0, 1, 2). */
    private static final int[] NEGATIVE = {SLOT_WEST, SLOT_DOWN, SLOT_NORTH};
    /** Axis index of each slot (-1 for UNASSIGNED). */
    private static final int[] SLOT_AXIS = {1, 2, 0, 1, -1, 2, 0};
    private static final boolean[] SLOT_POSITIVE = {false, false, true, true, false, true, false};
    private final int[] slotQuadStart;
    private final float[] plane;
    private final boolean bucketed;
    private final boolean boundsValid;
    public final float minX;
    public final float minY;
    public final float minZ;
    public final float maxX;
    public final float maxY;
    public final float maxZ;
    /**
     * Round 9 (R9-1, {@link TranslucentFacing}): the slot of every quad in vertex-buffer order, kept only for layers that
     * were classified without reordering (TRANSLUCENT); null otherwise.
     */
    byte @org.jspecify.annotations.Nullable [] quadSlots;

    TerrainMeshInfo(final int[] slotQuadStart, final float[] plane, final boolean bucketed, final float[] bounds) {
        this.slotQuadStart = slotQuadStart;
        this.plane = plane;
        this.bucketed = bucketed;
        this.minX = bounds[0];
        this.minY = bounds[1];
        this.minZ = bounds[2];
        this.maxX = bounds[3];
        this.maxY = bounds[4];
        this.maxZ = bounds[5];
        boolean finite = true;
        for (float b : bounds) {
            finite &= Float.isFinite(b);
        }

        this.boundsValid = finite && this.minX <= this.maxX && this.minY <= this.maxY && this.minZ <= this.maxZ;
    }

    public int quadCount() {
        return this.slotQuadStart[SLOT_COUNT];
    }

    /** First quad of a slot in the vertex buffer (valid as a draw offset only when {@link #bucketed()}). */
    public int slotStart(final int slot) {
        return this.slotQuadStart[slot];
    }

    public int slotQuads(final int slot) {
        return this.slotQuadStart[slot + 1] - this.slotQuadStart[slot];
    }

    public float plane(final int slot) {
        return this.plane[slot];
    }

    /** True when the vertices were reordered into layout A, so each bucket is one contiguous quad range. */
    public boolean bucketed() {
        return this.bucketed;
    }

    /** False when a position was NaN/infinite: the bounds must not be used for culling. */
    public boolean boundsValid() {
        return this.boundsValid;
    }

    int[] slotQuadStartArray() {
        return this.slotQuadStart;
    }

    /**
     * Buckets that can contain a front face for an eye at {@code (rx, ry, rz)} relative to the section origin, as a
     * bit mask over slots; UNASSIGNED is always included. A facing bucket is excluded only when the eye is beyond
     * the bucket's extreme plane by more than {@code eps} on the back side, so every quad of it is seen from behind.
     */
    public int visibleMask(final double rx, final double ry, final double rz, final double eps) {
        int vis = 1 << SLOT_UNASSIGNED;
        float[] p = this.plane;
        if (ry < p[SLOT_DOWN] + eps) {
            vis |= 1 << SLOT_DOWN;
        }

        if (rz < p[SLOT_NORTH] + eps) {
            vis |= 1 << SLOT_NORTH;
        }

        if (rx > p[SLOT_EAST] - eps) {
            vis |= 1 << SLOT_EAST;
        }

        if (ry > p[SLOT_UP] - eps) {
            vis |= 1 << SLOT_UP;
        }

        if (rz > p[SLOT_SOUTH] - eps) {
            vis |= 1 << SLOT_SOUTH;
        }

        if (rx < p[SLOT_WEST] + eps) {
            vis |= 1 << SLOT_WEST;
        }

        return vis;
    }

    /** Quads in the slots outside {@code mask}. */
    public int quadsOutside(final int mask) {
        int quads = 0;

        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            if ((mask & 1 << slot) == 0) {
                quads += this.slotQuadStart[slot + 1] - this.slotQuadStart[slot];
            }
        }

        return quads;
    }

    /**
     * Facing bucket of one quad. {@code p} holds {@code x0,y0,z0, x1,y1,z1, x2,y2,z2, x3,y3,z3} exactly as written to
     * the vertex buffer. Exact: float differences and their products are exact in double, so the sign is exact.
     */
    public static int classifyQuad(final float[] p) {
        for (int a = 0; a < 3; a++) {
            float c = p[a];
            if (p[3 + a] != c || p[6 + a] != c || p[9 + a] != c) {
                continue;
            }

            int u = (a + 1) % 3;
            int v = (a + 2) % 3;
            double n1 = cross(p, 0, 1, 2, u, v);
            double n2 = cross(p, 2, 3, 0, u, v);
            if (n1 > 0.0 && n2 > 0.0) {
                return POSITIVE[a];
            }

            if (n1 < 0.0 && n2 < 0.0) {
                return NEGATIVE[a];
            }

            return SLOT_UNASSIGNED;
        }

        return SLOT_UNASSIGNED;
    }

    /** Component {@code (u x v)} of {@code (pj - pi) x (pk - pi)} for the cyclic axis order {@code (u, v, a)}. */
    private static double cross(final float[] p, final int i, final int j, final int k, final int u, final int v) {
        double eu = (double)p[3 * j + u] - p[3 * i + u];
        double ev = (double)p[3 * j + v] - p[3 * i + v];
        double fu = (double)p[3 * k + u] - p[3 * i + u];
        double fv = (double)p[3 * k + v] - p[3 * i + v];
        return eu * fv - ev * fu;
    }

    /** Axis (0 x, 1 y, 2 z) whose coordinate a slot's plane holds; -1 for UNASSIGNED. */
    public static int slotAxis(final int slot) {
        return SLOT_AXIS[slot];
    }

    public static boolean slotPositive(final int slot) {
        return SLOT_POSITIVE[slot];
    }

    /** {@link #analyze(ByteBuffer, int, int, int, boolean, boolean)} without the S6 front-to-back order. */
    public static @Nullable TerrainMeshInfo analyze(
        final ByteBuffer vertices, final int vertexCount, final int stride, final int positionOffset, final boolean reorder
    ) {
        return analyze(vertices, vertexCount, stride, positionOffset, reorder, false);
    }

    /**
     * Classifies the quads of one section-layer, optionally reorders them in place into layout A, and returns the
     * metadata; null when the buffer is not a whole number of quads.
     *
     * @param vertices       the built vertex data; read and written from its current position, its position, limit
     *                       and byte order are left unchanged
     * @param vertexCount    number of vertices (a multiple of 4)
     * @param stride         vertex size in bytes
     * @param positionOffset byte offset of the float3 position inside a vertex
     * @param reorder        sort the quads into layout A (SOLID/CUTOUT); false only classifies (TRANSLUCENT)
     * @param frontToBack    with {@code reorder}: S6, order each facing bucket by plane, nearest first for every eye
     *                       (+axis buckets descending, -axis ascending, stable); UNASSIGNED keeps its emission order
     */
    public static @Nullable TerrainMeshInfo analyze(
        final ByteBuffer vertices, final int vertexCount, final int stride, final int positionOffset, final boolean reorder,
        final boolean frontToBack
    ) {
        if (vertexCount <= 0 || (vertexCount & 3) != 0 || stride < positionOffset + 12 || positionOffset < 0) {
            return null;
        }

        int base = vertices.position();
        long quadBytes = 4L * stride;
        int quads = vertexCount >> 2;
        if ((long)quads * quadBytes > vertices.limit() - base) {
            return null;
        }

        ByteBuffer buffer = vertices.duplicate().order(ByteOrder.nativeOrder());
        byte[] slots = new byte[quads];
        int[] counts = new int[SLOT_COUNT];
        float[] plane = new float[SLOT_COUNT];
        Arrays.fill(plane, Float.NaN);
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        float maxZ = Float.NEGATIVE_INFINITY;
        boolean nonFinite = false;
        float[] p = new float[12];
        float[] quadPlane = reorder && frontToBack ? new float[quads] : null;

        for (int q = 0; q < quads; q++) {
            int quadPos = base + q * (int)quadBytes + positionOffset;

            for (int v = 0; v < 4; v++) {
                int at = quadPos + v * stride;
                float x = buffer.getFloat(at);
                float y = buffer.getFloat(at + 4);
                float z = buffer.getFloat(at + 8);
                p[3 * v] = x;
                p[3 * v + 1] = y;
                p[3 * v + 2] = z;
                if (!(Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z))) {
                    nonFinite = true;
                }

                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                minZ = Math.min(minZ, z);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
                maxZ = Math.max(maxZ, z);
            }

            int slot = classifyQuad(p);
            slots[q] = (byte)slot;
            counts[slot]++;
            if (slot != SLOT_UNASSIGNED) {
                float c = p[SLOT_AXIS[slot]];
                if (quadPlane != null) {
                    quadPlane[q] = c;
                }

                float old = plane[slot];
                if (Float.isNaN(old)) {
                    plane[slot] = c;
                } else {
                    plane[slot] = SLOT_POSITIVE[slot] ? Math.min(old, c) : Math.max(old, c);
                }
            }
        }

        int[] start = new int[SLOT_COUNT + 1];

        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            start[slot + 1] = start[slot] + counts[slot];
        }

        if (reorder) {
            int[] src = bucketOrder(slots, start);
            if (quadPlane != null) {
                sortBucketsFrontToBack(src, start, quadPlane);
            }

            applyPermutation(buffer, base, (int)quadBytes, src);
        }

        float[] bounds = nonFinite
            ? new float[]{Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN}
            : new float[]{minX, minY, minZ, maxX, maxY, maxZ};
        TerrainMeshInfo info = new TerrainMeshInfo(start, plane, reorder, bounds);
        if (!reorder && !nonFinite) {
            info.quadSlots = slots;
        }

        if (reorder && TerrainClusters.BUILD) {
            info.clusters = TerrainClusters.compute(buffer, base, (int)quadBytes, stride, positionOffset, start);
        }

        return info;
    }

    /**
     * Round 12 (a) ({@link TerrainClusters}): the section-layer's 64-quad clusters in layout A (3 ints each), or null (not
     * built: {@code -Dmc.terrain.clusters} off, or not reordered).
     */
    int @Nullable [] clusters;

    public int @Nullable [] clusters() {
        return this.clusters;
    }

    /**
     * Round 12 Part B ({@link TerrainGreedy}, prototype): the bucket layout of the layer's greedy copy, stored after its
     * {@link #greedyFirstQuad} vanilla quads in the same vertex data; null without one.
     */
    @Nullable TerrainMeshInfo greedy;
    int greedyFirstQuad;

    public @Nullable TerrainMeshInfo greedy() {
        return this.greedy;
    }

    /**
     * Stable in-place counting sort of whole quads by slot: destination {@code d} receives source quad {@code src[d]};
     * each permutation cycle is rotated through one quad-sized temporary.
     */
    static void permuteQuads(final ByteBuffer buffer, final int base, final int quadBytes, final byte[] slots, final int[] slotStart) {
        applyPermutation(buffer, base, quadBytes, bucketOrder(slots, slotStart));
    }

    /** Stable counting sort by slot: destination {@code d} (layout A) receives source quad {@code src[d]}. */
    static int[] bucketOrder(final byte[] slots, final int[] slotStart) {
        int quads = slots.length;
        int[] next = Arrays.copyOf(slotStart, SLOT_COUNT);
        int[] src = new int[quads];

        for (int q = 0; q < quads; q++) {
            src[next[slots[q]]++] = q;
        }

        return src;
    }

    /**
     * S6: stable sort of each facing bucket's range of {@code src} by the quads' plane coordinate, descending for the
     * +axis buckets and ascending for the -axis buckets (nearest first for any eye that sees the quads). UNASSIGNED is
     * left in emission order.
     */
    static void sortBucketsFrontToBack(final int[] src, final int[] slotStart, final float[] quadPlane) {
        long[] keys = null;
        int[] tmp = null;

        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            int from = slotStart[slot];
            int n = slotStart[slot + 1] - from;
            if (slot == SLOT_UNASSIGNED || n < 2) {
                continue;
            }

            if (keys == null || keys.length < n) {
                keys = new long[n];
                tmp = new int[n];
            }

            boolean descending = SLOT_POSITIVE[slot];
            boolean sorted = true;
            int previous = Integer.MIN_VALUE;

            for (int i = 0; i < n; i++) {
                int key = planeOrderKey(quadPlane[src[from + i]]);
                if (descending) {
                    key = ~key;
                }

                sorted &= key >= previous;
                previous = key;
                // Plane key in the high half, position in the low half: equal planes keep their emission order (stable).
                keys[i] = (long)key << 32 | i;
            }

            if (sorted) {
                continue;
            }

            Arrays.sort(keys, 0, n);
            System.arraycopy(src, from, tmp, 0, n);

            for (int i = 0; i < n; i++) {
                src[from + i] = tmp[(int)keys[i]];
            }
        }
    }

    /**
     * Signed int with the order of the float {@code f} ({@code -0.0} and {@code 0.0} equal). {@code f} is never NaN: a
     * NaN coordinate never makes a quad planar on that axis, so such a quad is UNASSIGNED.
     */
    static int planeOrderKey(final float f) {
        int bits = Float.floatToIntBits(f + 0.0f);
        return bits ^ (bits >> 31 & Integer.MAX_VALUE);
    }

    /** Moves whole quads in place so that destination {@code d} holds source quad {@code src[d]}. */
    static void applyPermutation(final ByteBuffer buffer, final int base, final int quadBytes, final int[] src) {
        int quads = src.length;
        boolean identity = true;

        for (int d = 0; d < quads && identity; d++) {
            identity = src[d] == d;
        }

        if (identity) {
            return;
        }

        boolean[] done = new boolean[quads];
        byte[] tmp = new byte[quadBytes];

        for (int d0 = 0; d0 < quads; d0++) {
            if (done[d0]) {
                continue;
            }

            if (src[d0] == d0) {
                done[d0] = true;
                continue;
            }

            buffer.get(base + d0 * quadBytes, tmp);
            int d = d0;

            while (true) {
                int s = src[d];
                done[d] = true;
                if (s == d0) {
                    buffer.put(base + d * quadBytes, tmp);
                    break;
                }

                buffer.put(base + d * quadBytes, buffer, base + s * quadBytes, quadBytes);
                d = s;
            }
        }
    }
}
