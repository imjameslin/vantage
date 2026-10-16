import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import net.minecraft.client.renderer.CloudFaceFilter;
import net.minecraft.client.renderer.DynamicGpuData;
import net.minecraft.client.renderer.FaceInfo;
import net.minecraft.client.renderer.chunk.TerrainCompact;
import net.minecraft.client.renderer.chunk.TerrainFrustum;
import net.minecraft.client.renderer.chunk.TerrainMeshInfo;
import net.minecraft.client.renderer.chunk.TerrainRuns;
import net.minecraft.client.renderer.chunk.TerrainStats;
import net.minecraft.client.renderer.chunk.TerrainToggles;
import net.minecraft.client.renderer.chunk.TranslucentFacing;
import com.mojang.renderpearl.api.pipeline.IndexType;
import net.minecraft.core.Direction;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4d;

/**
 * Headless tests for the stage-2 terrain patches (docs/stage2/TERRAIN-PLAN.md S0/S1/S1b/S2/S5/S7): quad facing
 * classification, the in-place stable bucket sort and its metadata, the per-frame visible-bucket mask, merged-run
 * record generation and splitting, the exact frustum (planes, eye, orientation), the cloud face filter, and the S5
 * compact vertex codes (exhaustive round trips, rejections) and in-place transcode. No GPU,
 * window or game instance. Run through tools/terrain-test.sh (needs build/client.jar).
 */
public final class TerrainTest {
    private static int checks;
    private static int failures;

    public static void main(String[] args) {
        classifyStoneFaces();
        classifyFluidAndOddQuads();
        analyzeAndReorder();
        analyzeFrontToBack();
        sectionDistanceOrder();
        analyzeEdgeCases();
        visibleMaskCases();
        visibleMaskProperty();
        runsCases();
        runsProperty();
        recordEmission();
        frustumPlanes();
        frustumEyeAndOrientation();
        cloudFilter();
        statsCounters();
        compactCodes();
        compactTranscode();
        compactSnap();
        translucentFacing();
        System.out.printf("TerrainTest: %d checks, %d failures%n", checks, failures);
        System.exit(failures == 0 ? 0 : 1);
    }


    // ------------------------------------------------------------------------------------------------------------
    // R9-1: TranslucentFacing.extend (CONTRACT 16.R9-1)

    private static void translucentFacing() {
        Random rnd = new Random(919);
        int engaged = 0, drawn = 0, droppedTotal = 0;
        for (int round = 0; round < 400; round++) {
            int quads = 1 + rnd.nextInt(round < 300 ? 200 : 3000);
            ByteBuffer b = ByteBuffer.allocateDirect(quads * 4 * STRIDE).order(ByteOrder.nativeOrder());
            float[][] q = new float[quads][];
            for (int i = 0; i < quads; i++) {
                float[] p = randomQuad(rnd);
                if (rnd.nextInt(3) == 0) {
                    p = reversed(p);   // back faces (water undersides, inner sides)
                }
                q[i] = p;
                putQuad(b, 0, i, p, i);
            }
            TerrainMeshInfo info = TerrainMeshInfo.analyze(b.duplicate(), quads * 4, STRIDE, 0, false);
            check(info != null, "translucent analyze");
            IndexType type = quads * 4 <= 65536 && rnd.nextBoolean() ? IndexType.SHORT : IndexType.INT;
            int[] order = new int[quads];
            for (int i = 0; i < quads; i++) order[i] = i;
            for (int i = quads - 1; i > 0; i--) { int j = rnd.nextInt(i + 1); int t = order[i]; order[i] = order[j]; order[j] = t; }
            int pad = rnd.nextInt(3) * type.bytes * 6;
            ByteBuffer idx = ByteBuffer.allocateDirect(pad + quads * 6 * type.bytes).order(ByteOrder.nativeOrder());
            idx.position(pad);
            for (int k = 0; k < quads; k++) {
                int s0 = order[k] * 4;
                int[] six = {s0, s0 + 1, s0 + 2, s0 + 2, s0 + 3, s0};
                for (int v : six) { if (type == IndexType.SHORT) idx.putShort((short)v); else idx.putInt(v); }
            }
            idx.position(pad);
            byte[] in = new byte[quads * 6 * type.bytes];
            idx.duplicate().get(in);
            double ex = -40 + 96 * rnd.nextDouble(), ey = -40 + 96 * rnd.nextDouble(), ez = -40 + 96 * rnd.nextDouble();
            TranslucentFacing.Extended e = TranslucentFacing.extend(idx, type, info, ex, ey, ez);
            check(idx.position() == pad, "extend leaves the position");
            if (e == null) {
                continue;
            }
            engaged++;
            ByteBuffer out = e.buffer().duplicate().order(ByteOrder.nativeOrder());
            byte[] head = new byte[in.length];
            out.get(0, head);
            check(Arrays.equals(head, in), "first part is the vanilla sorted list byte for byte");
            int kept = e.keptIndexCount() / 6;
            check(out.capacity() == in.length + kept * 6 * type.bytes, "size = full + kept");
            int mask = e.droppedMask();
            check(mask != 0 && (mask & (1 << TerrainMeshInfo.SLOT_UNASSIGNED)) == 0, "mask names facing buckets only");
            // second part = the order-preserving filter of the first by slot
            int w = 0;
            for (int k = 0; k < quads; k++) {
                int src = order[k];
                int slot = TerrainMeshInfo.classifyQuad(q[src]);
                if ((mask & 1 << slot) != 0) { droppedTotal++; continue; }
                for (int t = 0; t < 6; t++) {
                    int at = in.length + (w * 6 + t) * type.bytes;
                    int v = type == IndexType.SHORT ? out.getShort(at) & 0xFFFF : out.getInt(at);
                    int expect = in.length > 0 ? (type == IndexType.SHORT ? ByteBuffer.wrap(in).order(ByteOrder.nativeOrder()).getShort((k * 6 + t) * 2) & 0xFFFF
                        : ByteBuffer.wrap(in).order(ByteOrder.nativeOrder()).getInt((k * 6 + t) * 4)) : 0;
                    if (v != expect) { check(false, "kept list keeps vanilla order (round " + round + ")"); }
                }
                w++;
            }
            check(w == kept, "kept count");
            // draw-time rule: for random eyes near the upload eye, whenever every dropped bucket is still excluded at
            // TerrainRuns.EPS, every dropped quad is strictly back-facing (eye strictly behind its plane)
            for (int t = 0; t < 20; t++) {
                double dx = ex + (rnd.nextDouble() - 0.5) * 3, dy = ey + (rnd.nextDouble() - 0.5) * 3, dz = ez + (rnd.nextDouble() - 0.5) * 3;
                if ((info.visibleMask(dx, dy, dz, TerrainRuns.EPS) & mask) != 0) continue;
                drawn++;
                double[] eye = {dx, dy, dz};
                for (int i = 0; i < quads; i++) {
                    int slot = TerrainMeshInfo.classifyQuad(q[i]);
                    if ((mask & 1 << slot) == 0) continue;
                    int axis = TerrainMeshInfo.slotAxis(slot);
                    double c = q[i][axis];
                    boolean back = TerrainMeshInfo.slotPositive(slot) ? eye[axis] < c : eye[axis] > c;
                    if (!back) check(false, "dropped quad back-facing for an accepted eye (round " + round + ")");
                }
            }
            TranslucentFacing.free(e);
        }
        check(engaged > 100 && drawn > 500 && droppedTotal > 1000, "translucent facing engaged (" + engaged + " / " + drawn + " / " + droppedTotal + ")");
        // mismatched buffer / no slots: null
        ByteBuffer b = ByteBuffer.allocateDirect(4 * STRIDE).order(ByteOrder.nativeOrder());
        putQuad(b, 0, 0, reversed(cuboidFace(Direction.UP, new Vector3f(0, 0, 0), new Vector3f(1, 0.875f, 1), 0, 4, 0)), 0);
        TerrainMeshInfo one = TerrainMeshInfo.analyze(b.duplicate(), 4, STRIDE, 0, false);
        ByteBuffer two = ByteBuffer.allocateDirect(48).order(ByteOrder.nativeOrder());
        check(TranslucentFacing.extend(two, IndexType.INT, one, 0.5, 40, 0.5) == null, "index buffer of another quad count is refused");
        TerrainMeshInfo reordered = TerrainMeshInfo.analyze(b.duplicate(), 4, STRIDE, 0, true);
        ByteBuffer six = ByteBuffer.allocateDirect(24).order(ByteOrder.nativeOrder());
        for (int v : new int[]{0, 1, 2, 2, 3, 0}) six.putInt(v);
        six.flip();
        check(TranslucentFacing.extend(six, IndexType.INT, reordered, 0.5, 40, 0.5) == null, "reordered (solid) metadata is refused");
        TranslucentFacing.Extended all = TranslucentFacing.extend(six, IndexType.INT, one, 0.5, 40, 0.5);
        check(all != null && all.keptIndexCount() == 0, "a lone water underside seen from above is dropped");
        if (all != null) TranslucentFacing.free(all);
        check(TranslucentFacing.extend(six, IndexType.INT, one, 0.5, 4.5, 0.5) == null, "eye within the build margin: nothing dropped");
    }

    // ------------------------------------------------------------------------------------------------------------
    // helpers

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + what);
        }
    }

    private static int slotOf(Direction d) {
        return switch (d) {
            case DOWN -> TerrainMeshInfo.SLOT_DOWN;
            case UP -> TerrainMeshInfo.SLOT_UP;
            case NORTH -> TerrainMeshInfo.SLOT_NORTH;
            case SOUTH -> TerrainMeshInfo.SLOT_SOUTH;
            case WEST -> TerrainMeshInfo.SLOT_WEST;
            case EAST -> TerrainMeshInfo.SLOT_EAST;
        };
    }

    /** The 4 vertices vanilla bakes for face {@code d} of the cuboid [from, to] (FaceInfo order), offset by (ox, oy, oz). */
    private static float[] cuboidFace(Direction d, Vector3f from, Vector3f to, float ox, float oy, float oz) {
        FaceInfo info = FaceInfo.fromFacing(d);
        float[] p = new float[12];
        for (int v = 0; v < 4; v++) {
            Vector3f pos = info.getVertexInfo(v).select(from, to);
            p[3 * v] = pos.x() + ox;
            p[3 * v + 1] = pos.y() + oy;
            p[3 * v + 2] = pos.z() + oz;
        }
        return p;
    }

    private static float[] rotate(float[] p, int by) {
        float[] r = new float[12];
        for (int v = 0; v < 4; v++) {
            System.arraycopy(p, 3 * ((v + by) % 4), r, 3 * v, 3);
        }
        return r;
    }

    private static float[] reversed(float[] p) {
        // FluidRenderer's back face: vertices 0, 3, 2, 1
        int[] order = {0, 3, 2, 1};
        float[] r = new float[12];
        for (int v = 0; v < 4; v++) {
            System.arraycopy(p, 3 * order[v], r, 3 * v, 3);
        }
        return r;
    }

    private static float[] quad(float... xyz) {
        return xyz;
    }

    /** Exact normal (e1 x e2 of triangle 0,1,2) in double. */
    private static double[] normal(float[] p) {
        double ax = p[3] - p[0], ay = p[4] - p[1], az = p[5] - p[2];
        double bx = p[6] - p[0], by = p[7] - p[1], bz = p[8] - p[2];
        return new double[]{ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx};
    }

    // ------------------------------------------------------------------------------------------------------------
    // S1 classification

    private static void classifyStoneFaces() {
        Vector3f[][] cuboids = {
            {new Vector3f(0, 0, 0), new Vector3f(1, 1, 1)},          // full block
            {new Vector3f(0, 0, 0), new Vector3f(1, 0.5f, 1)},       // bottom slab
            {new Vector3f(0.25f, 0, 0.25f), new Vector3f(0.75f, 0.875f, 0.75f)},
            {new Vector3f(-1, 0, 0.4375f), new Vector3f(2, 2, 0.5625f)},  // element outside the block (e.g. tall models)
        };
        for (Vector3f[] c : cuboids) {
            for (Direction d : Direction.values()) {
                for (float[] offset : new float[][]{{0, 0, 0}, {15, 15, 15}, {7, 3, 11}}) {
                    float[] p = cuboidFace(d, c[0], c[1], offset[0], offset[1], offset[2]);
                    check(TerrainMeshInfo.classifyQuad(p) == slotOf(d), "stone face " + d + " of " + Arrays.toString(c) + " classifies as its direction");
                    for (int r = 1; r < 4; r++) {
                        check(TerrainMeshInfo.classifyQuad(rotate(p, r)) == slotOf(d), "cyclic vertex rotation keeps the facing (" + d + ")");
                    }
                    check(TerrainMeshInfo.classifyQuad(reversed(p)) == slotOf(d.getOpposite()), "reversed winding classifies opposite (" + d + ")");
                    // the bucket's direction must be the side the exact normal points to (front side)
                    double[] n = normal(p);
                    int axis = TerrainMeshInfo.slotAxis(slotOf(d));
                    check(TerrainMeshInfo.slotPositive(slotOf(d)) == (n[axis] > 0), "bucket sign matches the exact normal (" + d + ")");
                }
            }
        }
    }

    private static void classifyFluidAndOddQuads() {
        float h = 0.875f;
        // FluidRenderer top face (flat surface): (x, y+hNW, z), (x, y+hSW, z+1), (x+1, y+hSE, z+1), (x+1, y+hNE, z)
        float[] top = quad(3, 5 + h, 4, 3, 5 + h, 5, 4, 5 + h, 5, 4, 5 + h, 4);
        check(TerrainMeshInfo.classifyQuad(top) == TerrainMeshInfo.SLOT_UP, "flat water top is UP");
        check(TerrainMeshInfo.classifyQuad(reversed(top)) == TerrainMeshInfo.SLOT_DOWN, "water top back face is DOWN");
        float[] sloped = quad(3, 5.875f, 4, 3, 5.5f, 5, 4, 5.5f, 5, 4, 5.875f, 4);
        check(TerrainMeshInfo.classifyQuad(sloped) == TerrainMeshInfo.SLOT_UNASSIGNED, "sloped water top is UNASSIGNED");
        // FluidRenderer NORTH side: (x0, y+hh0, z0), (x1, y+hh1, z1), (x1, y+bottom, z1), (x0, y+bottom, z0), z = z + 0.001
        float z = 4.001f;
        float[] north = quad(3, 5 + h, z, 4, 5 + h, z, 4, 5, z, 3, 5, z);
        check(TerrainMeshInfo.classifyQuad(north) == TerrainMeshInfo.SLOT_NORTH, "water north side is NORTH");
        check(TerrainMeshInfo.classifyQuad(reversed(north)) == TerrainMeshInfo.SLOT_SOUTH, "water north side back face is SOUTH");
        float[] east = quad(3.999f, 5 + h, 4, 3.999f, 5 + h, 5, 3.999f, 5, 5, 3.999f, 5, 4);
        check(TerrainMeshInfo.classifyQuad(east) == TerrainMeshInfo.SLOT_EAST, "water east side is EAST");
        // side with one corner at the bottom (degenerate triangle 2,3,0): always drawn
        float[] wedge = quad(3, 5, z, 4, 5 + h, z, 4, 5, z, 3, 5, z);
        check(TerrainMeshInfo.classifyQuad(wedge) == TerrainMeshInfo.SLOT_UNASSIGNED, "half-degenerate quad is UNASSIGNED");
        // 45 degree cross plant
        float[] cross = quad(0.1464f, 0, 0.1464f, 0.1464f, 1, 0.1464f, 0.8536f, 1, 0.8536f, 0.8536f, 0, 0.8536f);
        check(TerrainMeshInfo.classifyQuad(cross) == TerrainMeshInfo.SLOT_UNASSIGNED, "45 degree cross plant is UNASSIGNED");
        // bow-tie (planar on y, triangles of opposite orientation)
        float[] bowtie = quad(0, 1, 0, 1, 1, 1, 1, 1, 0, 0, 1, 1);
        check(TerrainMeshInfo.classifyQuad(bowtie) == TerrainMeshInfo.SLOT_UNASSIGNED, "bow-tie is UNASSIGNED");
        float[] zero = quad(0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1, 0);
        check(TerrainMeshInfo.classifyQuad(zero) == TerrainMeshInfo.SLOT_UNASSIGNED, "zero-area quad is UNASSIGNED");
        float[] line = quad(0, 1, 0, 1, 1, 0, 1, 1, 0, 0, 1, 0);
        check(TerrainMeshInfo.classifyQuad(line) == TerrainMeshInfo.SLOT_UNASSIGNED, "collinear quad is UNASSIGNED");
        float[] nonPlanar = quad(0, 1, 0, 0, 1, 1, 1, 1.0001f, 1, 1, 1, 0);
        check(TerrainMeshInfo.classifyQuad(nonPlanar) == TerrainMeshInfo.SLOT_UNASSIGNED, "non-planar quad is UNASSIGNED");
        float[] nan = quad(Float.NaN, 1, 0, 0, 1, 1, 1, 1, 1, 1, 1, 0);
        check(TerrainMeshInfo.classifyQuad(nan) == TerrainMeshInfo.SLOT_UNASSIGNED, "NaN quad is UNASSIGNED");
        // tiny but valid face far from the origin of the section (exact arithmetic)
        float[] tiny = cuboidFace(Direction.UP, new Vector3f(0, 0, 0), new Vector3f(1.0f / 1024, 1.0f / 1024, 1.0f / 1024), 15.9f, 31.0f, -15.9f);
        check(TerrainMeshInfo.classifyQuad(tiny) == TerrainMeshInfo.SLOT_UP, "tiny UP face classifies exactly");
    }

    // ------------------------------------------------------------------------------------------------------------
    // S0b/S1 analyze + in-place stable bucket sort

    private static final int STRIDE = 28;

    private static void putQuad(ByteBuffer b, int base, int q, float[] p, int id) {
        for (int v = 0; v < 4; v++) {
            int at = base + (q * 4 + v) * STRIDE;
            b.putFloat(at, p[3 * v]);
            b.putFloat(at + 4, p[3 * v + 1]);
            b.putFloat(at + 8, p[3 * v + 2]);
            b.putInt(at + 12, 0xFF000000 | id);   // colour: id tag
            b.putFloat(at + 16, id + 0.25f * v);  // u
            b.putFloat(at + 20, -id);             // v
            b.putInt(at + 24, id * 4 + v);        // light: vertex tag
        }
    }

    private static float[] randomQuad(Random rnd) {
        int kind = rnd.nextInt(8);
        float ox = rnd.nextInt(16), oy = rnd.nextInt(16), oz = rnd.nextInt(16);
        if (kind < 6) {
            Direction d = Direction.values()[kind];
            float a = rnd.nextInt(8) / 16.0f;
            float b = 0.5f + rnd.nextInt(8) / 16.0f;
            return cuboidFace(d, new Vector3f(a, a, a), new Vector3f(b, b, b), ox, oy, oz);
        }
        if (kind == 6) {
            return quad(ox + 0.1464f, oy, oz + 0.1464f, ox + 0.1464f, oy + 1, oz + 0.1464f, ox + 0.8536f, oy + 1, oz + 0.8536f, ox + 0.8536f, oy, oz + 0.8536f);
        }
        return quad(ox, oy + 0.9f, oz, ox, oy + 0.6f, oz + 1, ox + 1, oy + 0.6f, oz + 1, ox + 1, oy + 0.9f, oz);
    }

    private static void analyzeAndReorder() {
        Random rnd = new Random(1234);
        for (int round = 0; round < 60; round++) {
            int quads = 1 + rnd.nextInt(round < 50 ? 300 : 5000);
            int base = round % 3 == 0 ? 0 : 4 * STRIDE * rnd.nextInt(3) + 28;
            ByteBuffer b = ByteBuffer.allocateDirect(base + quads * 4 * STRIDE + 64).order(ByteOrder.nativeOrder());
            float[][] original = new float[quads][];
            int[] expectedSlot = new int[quads];
            int[] counts = new int[TerrainMeshInfo.SLOT_COUNT];
            float[] minB = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
            float[] maxB = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
            for (int q = 0; q < quads; q++) {
                original[q] = randomQuad(rnd);
                putQuad(b, base, q, original[q], q);
                expectedSlot[q] = TerrainMeshInfo.classifyQuad(original[q]);
                counts[expectedSlot[q]]++;
                for (int v = 0; v < 4; v++) {
                    for (int a = 0; a < 3; a++) {
                        minB[a] = Math.min(minB[a], original[q][3 * v + a]);
                        maxB[a] = Math.max(maxB[a], original[q][3 * v + a]);
                    }
                }
            }
            byte[] before = new byte[b.capacity()];
            b.get(0, before);
            ByteBuffer view = b.duplicate().position(base).limit(base + quads * 4 * STRIDE);
            boolean reorder = round % 5 != 4;
            TerrainMeshInfo info = TerrainMeshInfo.analyze(view, quads * 4, STRIDE, 0, reorder);
            check(info != null, "analyze returns metadata");
            if (info == null) {
                continue;
            }
            check(view.position() == base && view.limit() == base + quads * 4 * STRIDE, "analyze leaves position/limit unchanged");
            check(info.quadCount() == quads, "quad count");
            check(info.bucketed() == reorder, "bucketed flag");
            for (int s = 0; s < TerrainMeshInfo.SLOT_COUNT; s++) {
                check(info.slotQuads(s) == counts[s], "slot " + s + " count");
            }
            check(info.minX == minB[0] && info.minY == minB[1] && info.minZ == minB[2], "min bounds");
            check(info.maxX == maxB[0] && info.maxY == maxB[1] && info.maxZ == maxB[2], "max bounds");
            check(info.boundsValid(), "bounds valid");
            // planes: min for + buckets, max for - buckets
            for (int s = 0; s < TerrainMeshInfo.SLOT_COUNT; s++) {
                if (s == TerrainMeshInfo.SLOT_UNASSIGNED) {
                    continue;
                }
                float expected = Float.NaN;
                int axis = TerrainMeshInfo.slotAxis(s);
                for (int q = 0; q < quads; q++) {
                    if (expectedSlot[q] == s) {
                        float c = original[q][axis];
                        expected = Float.isNaN(expected) ? c : TerrainMeshInfo.slotPositive(s) ? Math.min(expected, c) : Math.max(expected, c);
                    }
                }
                check(Float.compare(info.plane(s), expected) == 0, "plane of slot " + s);
            }
            if (!reorder) {
                byte[] after = new byte[b.capacity()];
                b.get(0, after);
                check(Arrays.equals(before, after), "classification only: buffer unchanged");
                continue;
            }
            // reordered: bytes outside the range untouched; quads in layout order, stable, intact
            byte[] after = new byte[b.capacity()];
            b.get(0, after);
            check(Arrays.equals(Arrays.copyOfRange(before, 0, base), Arrays.copyOfRange(after, 0, base)), "bytes before the range untouched");
            int end = base + quads * 4 * STRIDE;
            check(Arrays.equals(Arrays.copyOfRange(before, end, before.length), Arrays.copyOfRange(after, end, after.length)), "bytes after the range untouched");
            boolean[] seen = new boolean[quads];
            boolean intact = true;
            boolean ordered = true;
            boolean stable = true;
            int prevSlot = -1;
            int prevId = -1;
            for (int k = 0; k < quads; k++) {
                int id = b.getInt(base + k * 4 * STRIDE + 12) & 0xFFFFFF;
                if (id >= quads || seen[id]) {
                    intact = false;
                    break;
                }
                seen[id] = true;
                for (int v = 0; v < 4; v++) {
                    int at = base + (k * 4 + v) * STRIDE;
                    intact &= b.getFloat(at) == original[id][3 * v] && b.getFloat(at + 4) == original[id][3 * v + 1]
                        && b.getFloat(at + 8) == original[id][3 * v + 2] && b.getInt(at + 24) == id * 4 + v
                        && b.getFloat(at + 16) == id + 0.25f * v && b.getFloat(at + 20) == -id;
                }
                int slot = expectedSlot[id];
                ordered &= slot >= prevSlot;
                ordered &= k >= info.slotStart(slot) && k < info.slotStart(slot) + info.slotQuads(slot);
                if (slot == prevSlot) {
                    stable &= id > prevId;
                }
                prevSlot = slot;
                prevId = id;
            }
            check(intact, "every quad moved whole (positions, colour, uv, light)");
            check(ordered, "quads are in layout A order with prefix-sum ranges");
            check(stable, "order inside each bucket is the emission order");
        }
    }

    /**
     * S6: analyze(..., reorder, frontToBack) gives the same metadata and layout A ranges as without it; inside each facing
     * bucket the quads are ordered by plane (descending for +axis, ascending for -axis), coplanar quads and UNASSIGNED in
     * emission order; quads move whole; with reorder off nothing moves. Also: for random eyes, every pair of quads of one
     * bucket that the eye sees from the front is drawn nearer-first (property behind "front-to-back for every eye").
     */
    private static void analyzeFrontToBack() {
        Random rnd = new Random(4321);
        for (int round = 0; round < 80; round++) {
            int quads = 1 + rnd.nextInt(round < 60 ? 400 : 6000);
            int base = round % 3 == 0 ? 0 : 4 * STRIDE * rnd.nextInt(3) + 28;
            ByteBuffer b = ByteBuffer.allocateDirect(base + quads * 4 * STRIDE + 64).order(ByteOrder.nativeOrder());
            ByteBuffer plain = ByteBuffer.allocateDirect(b.capacity()).order(ByteOrder.nativeOrder());
            float[][] original = new float[quads][];
            int[] expectedSlot = new int[quads];
            for (int q = 0; q < quads; q++) {
                float[] p = randomQuad(rnd);
                if (round % 4 == 1 && rnd.nextInt(4) == 0) {
                    // negative and -0.0 planes (model elements outside the block, section-relative coordinates below 0)
                    for (int v = 0; v < 4; v++) {
                        p[3 * v + 1] -= 16.0f;
                    }
                }
                if (round % 4 == 2 && rnd.nextInt(3) == 0 && q > 0) {
                    p = original[rnd.nextInt(q)].clone();   // exact duplicates: coplanar ties
                }
                original[q] = p;
                putQuad(b, base, q, p, q);
                putQuad(plain, base, q, p, q);
                expectedSlot[q] = TerrainMeshInfo.classifyQuad(p);
            }
            boolean reorder = round % 7 != 6;
            ByteBuffer view = b.duplicate().position(base).limit(base + quads * 4 * STRIDE);
            byte[] before = new byte[b.capacity()];
            b.get(0, before);
            TerrainMeshInfo info = TerrainMeshInfo.analyze(view, quads * 4, STRIDE, 0, reorder, true);
            TerrainMeshInfo ref = TerrainMeshInfo.analyze(plain.duplicate().position(base).limit(base + quads * 4 * STRIDE), quads * 4, STRIDE, 0, reorder);
            check(info != null && ref != null, "S6: analyze returns metadata");
            if (info == null || ref == null) {
                continue;
            }
            boolean same = info.quadCount() == ref.quadCount() && info.bucketed() == ref.bucketed() && info.minX == ref.minX && info.minY == ref.minY
                && info.minZ == ref.minZ && info.maxX == ref.maxX && info.maxY == ref.maxY && info.maxZ == ref.maxZ;
            for (int s = 0; s <= TerrainMeshInfo.SLOT_COUNT && same; s++) {
                same = s == TerrainMeshInfo.SLOT_COUNT || info.slotStart(s) == ref.slotStart(s) && Float.compare(info.plane(s), ref.plane(s)) == 0;
            }
            check(same, "S6: metadata (counts, ranges, planes, bounds) identical to the plain bucket sort");
            if (!reorder) {
                byte[] after = new byte[b.capacity()];
                b.get(0, after);
                check(Arrays.equals(before, after), "S6 without reorder: buffer unchanged");
                continue;
            }
            byte[] after = new byte[b.capacity()];
            b.get(0, after);
            int end = base + quads * 4 * STRIDE;
            check(Arrays.equals(Arrays.copyOfRange(before, 0, base), Arrays.copyOfRange(after, 0, base))
                && Arrays.equals(Arrays.copyOfRange(before, end, before.length), Arrays.copyOfRange(after, end, after.length)), "S6: bytes outside the range untouched");
            int[] order = new int[quads];
            boolean[] seen = new boolean[quads];
            boolean intact = true;
            for (int k = 0; k < quads && intact; k++) {
                int id = b.getInt(base + k * 4 * STRIDE + 12) & 0xFFFFFF;
                if (id >= quads || seen[id]) {
                    intact = false;
                    break;
                }
                seen[id] = true;
                order[k] = id;
                for (int v = 0; v < 4; v++) {
                    int at = base + (k * 4 + v) * STRIDE;
                    intact &= b.getFloat(at) == original[id][3 * v] && b.getFloat(at + 4) == original[id][3 * v + 1]
                        && b.getFloat(at + 8) == original[id][3 * v + 2] && b.getInt(at + 24) == id * 4 + v
                        && b.getFloat(at + 16) == id + 0.25f * v && b.getFloat(at + 20) == -id;
                }
            }
            check(intact, "S6: every quad moved whole");
            if (!intact) {
                continue;
            }
            boolean inRange = true;
            boolean planeOrder = true;
            boolean stable = true;
            for (int k = 0; k < quads; k++) {
                int id = order[k];
                int slot = expectedSlot[id];
                inRange &= k >= info.slotStart(slot) && k < info.slotStart(slot) + info.slotQuads(slot);
                if (k > 0 && expectedSlot[order[k - 1]] == slot) {
                    int prev = order[k - 1];
                    if (slot == TerrainMeshInfo.SLOT_UNASSIGNED) {
                        stable &= prev < id;
                    } else {
                        int axis = TerrainMeshInfo.slotAxis(slot);
                        float a = original[prev][axis];
                        float c = original[id][axis];
                        if (a == c) {
                            stable &= prev < id;   // coplanar (also -0.0 vs 0.0): emission order
                        } else {
                            planeOrder &= TerrainMeshInfo.slotPositive(slot) ? a > c : a < c;
                        }
                    }
                }
            }
            check(inRange, "S6: quads stay in their layout A bucket range");
            check(planeOrder, "S6: +axis buckets by plane descending, -axis ascending");
            check(stable, "S6: coplanar quads and UNASSIGNED keep their emission order");
            // Property: for random eyes, any two quads of a bucket both facing the eye are drawn nearer-first along the axis.
            boolean frontToBack = true;
            for (int e = 0; e < 20 && quads > 1; e++) {
                double[] eye = {rnd.nextDouble() * 48 - 16, rnd.nextDouble() * 48 - 16, rnd.nextDouble() * 48 - 16};
                for (int k = 1; k < quads; k++) {
                    int prev = order[k - 1];
                    int id = order[k];
                    int slot = expectedSlot[id];
                    if (slot == TerrainMeshInfo.SLOT_UNASSIGNED || expectedSlot[prev] != slot) {
                        continue;
                    }
                    int axis = TerrainMeshInfo.slotAxis(slot);
                    double sign = TerrainMeshInfo.slotPositive(slot) ? 1 : -1;
                    double dPrev = sign * (eye[axis] - original[prev][axis]);
                    double dId = sign * (eye[axis] - original[id][axis]);
                    if (dPrev > 0 && dId > 0) {
                        frontToBack &= dPrev <= dId;   // both seen from the front: the earlier one is not farther
                    }
                }
            }
            check(frontToBack, "S6: for any eye, front-facing quads of one bucket are drawn nearest first");
        }
        // plane order key: monotonic, -0.0 == 0.0
        float[] values = {-1e30f, -17.5f, -16.0f, -1.0f, -0.0625f, -Float.MIN_VALUE, -0.0f, 0.0f, Float.MIN_VALUE, 0.0625f, 1.0f, 15.9375f, 16.0f, 1e30f};
        boolean mono = true;
        for (int i = 1; i < values.length; i++) {
            int a = planeKey(values[i - 1]);
            int c = planeKey(values[i]);
            mono &= values[i - 1] == values[i] ? a == c : a < c;
        }
        check(mono, "S6: plane order key is monotonic and equates -0.0 with 0.0");
    }

    /** TerrainMeshInfo.planeOrderKey (package-private) through reflection. */
    private static int planeKey(float f) {
        try {
            java.lang.reflect.Method m = TerrainMeshInfo.class.getDeclaredMethod("planeOrderKey", float.class);
            m.setAccessible(true);
            return (int)m.invoke(null, f);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Section front-to-back order: the key is monotonic in the distance to the section centre, and the list sort is stable. */
    private static void sectionDistanceOrder() {
        Random rnd = new Random(99);
        boolean mono = true;
        for (int i = 0; i < 20000; i++) {
            double cx = rnd.nextDouble() * 4000 - 2000, cy = rnd.nextDouble() * 400 - 64, cz = rnd.nextDouble() * 4000 - 2000;
            int ax = 16 * (int)Math.floor(cx / 16) + 16 * (rnd.nextInt(41) - 20), ay = 16 * (rnd.nextInt(24) - 4), az = 16 * (int)Math.floor(cz / 16) + 16 * (rnd.nextInt(41) - 20);
            int bx = 16 * (int)Math.floor(cx / 16) + 16 * (rnd.nextInt(41) - 20), by = 16 * (rnd.nextInt(24) - 4), bz = 16 * (int)Math.floor(cz / 16) + 16 * (rnd.nextInt(41) - 20);
            double da = Math.pow(ax + 8 - cx, 2) + Math.pow(ay + 8 - cy, 2) + Math.pow(az + 8 - cz, 2);
            double db = Math.pow(bx + 8 - cx, 2) + Math.pow(by + 8 - cy, 2) + Math.pow(bz + 8 - cz, 2);
            int ka = net.minecraft.client.renderer.chunk.TerrainDrawList.sectionDistanceKey(ax, ay, az, cx, cy, cz);
            int kb = net.minecraft.client.renderer.chunk.TerrainDrawList.sectionDistanceKey(bx, by, bz, cx, cy, cz);
            mono &= ka >= 0 && kb >= 0 && (da < db ? ka <= kb : da > db ? ka >= kb : ka == kb);
        }
        check(mono, "section order: distance key non-negative and monotonic");
        List<DynamicGpuData.ChunkSectionInfo> infos = new ArrayList<>();
        List<DynamicGpuData.IndexedDraw> draws = new ArrayList<>();
        for (int s = 0; s < 300; s++) {
            infos.add(new DynamicGpuData.ChunkSectionInfo(16 * (rnd.nextInt(9) - 4), 16 * (rnd.nextInt(5) - 2), 16 * (rnd.nextInt(9) - 4), 1.0f));
            int runs = 1 + rnd.nextInt(3);
            for (int r = 0; r < runs; r++) {
                draws.add(new DynamicGpuData.IndexedDraw(6 * (1 + rnd.nextInt(100)), 1, 0, 4 * (draws.size() * 1000 + r), s));
            }
        }
        List<DynamicGpuData.IndexedDraw> sorted = new ArrayList<>(draws);
        net.minecraft.client.renderer.chunk.TerrainDrawList.sortBySectionDistance(sorted, infos, 3.3, 5.7, -2.1);
        boolean ok = sorted.size() == draws.size();
        for (int i = 1; i < sorted.size() && ok; i++) {
            DynamicGpuData.ChunkSectionInfo a = infos.get(sorted.get(i - 1).baseInstance());
            DynamicGpuData.ChunkSectionInfo c = infos.get(sorted.get(i).baseInstance());
            int ka = net.minecraft.client.renderer.chunk.TerrainDrawList.sectionDistanceKey(a.x(), a.y(), a.z(), 3.3, 5.7, -2.1);
            int kc = net.minecraft.client.renderer.chunk.TerrainDrawList.sectionDistanceKey(c.x(), c.y(), c.z(), 3.3, 5.7, -2.1);
            ok = ka < kc || ka == kc && draws.indexOf(sorted.get(i - 1)) < draws.indexOf(sorted.get(i));
        }
        check(ok, "section order: list sort is by distance key, stable (records of a section and equal keys keep their order)");
    }

    private static void analyzeEdgeCases() {
        ByteBuffer b = ByteBuffer.allocateDirect(7 * STRIDE).order(ByteOrder.nativeOrder());
        check(TerrainMeshInfo.analyze(b, 7, STRIDE, 0, true) == null, "non-quad vertex count -> null");
        check(TerrainMeshInfo.analyze(b, 0, STRIDE, 0, true) == null, "empty -> null");
        check(TerrainMeshInfo.analyze(b, 8, STRIDE, 0, true) == null, "buffer too small -> null");
        ByteBuffer one = ByteBuffer.allocateDirect(4 * STRIDE).order(ByteOrder.nativeOrder());
        putQuad(one, 0, 0, quad(Float.NaN, 0, 0, 0, 1, 0, 1, 1, 0, 1, 0, 0), 0);
        TerrainMeshInfo nan = TerrainMeshInfo.analyze(one, 4, STRIDE, 0, true);
        check(nan != null && !nan.boundsValid(), "NaN positions: bounds not usable for culling");
        // big-endian view of native memory still reads native floats
        ByteBuffer big = ByteBuffer.allocateDirect(4 * STRIDE).order(ByteOrder.nativeOrder());
        putQuad(big, 0, 0, cuboidFace(Direction.EAST, new Vector3f(0, 0, 0), new Vector3f(1, 1, 1), 2, 2, 2), 0);
        TerrainMeshInfo east = TerrainMeshInfo.analyze(big.duplicate().order(ByteOrder.BIG_ENDIAN), 4, STRIDE, 0, true);
        check(east != null && east.slotQuads(TerrainMeshInfo.SLOT_EAST) == 1 && east.plane(TerrainMeshInfo.SLOT_EAST) == 3.0f, "byte order of the passed view does not matter");
    }

    // ------------------------------------------------------------------------------------------------------------
    // S1 visible-bucket mask

    /** Metadata with the given quads (one per entry) via analyze. */
    private static TerrainMeshInfo infoOf(List<float[]> quads) {
        ByteBuffer b = ByteBuffer.allocateDirect(quads.size() * 4 * STRIDE).order(ByteOrder.nativeOrder());
        for (int q = 0; q < quads.size(); q++) {
            putQuad(b, 0, q, quads.get(q), q);
        }
        return TerrainMeshInfo.analyze(b, quads.size() * 4, STRIDE, 0, true);
    }

    private static boolean has(int mask, int slot) {
        return (mask & 1 << slot) != 0;
    }

    private static void visibleMaskCases() {
        Vector3f lo = new Vector3f(0, 0, 0);
        Vector3f hi = new Vector3f(1, 1, 1);
        List<float[]> quads = new ArrayList<>();
        // UP faces at y = 1 and y = 16, DOWN faces at y = 0 and y = 9, EAST at x = 5 and 13, WEST at x = 2,
        // SOUTH at z = 16, NORTH at z = 0
        quads.add(cuboidFace(Direction.UP, lo, hi, 3, 0, 3));
        quads.add(cuboidFace(Direction.UP, lo, hi, 3, 15, 3));
        quads.add(cuboidFace(Direction.DOWN, lo, hi, 3, 0, 3));
        quads.add(cuboidFace(Direction.DOWN, lo, hi, 3, 9, 3));
        quads.add(cuboidFace(Direction.EAST, lo, hi, 4, 3, 3));
        quads.add(cuboidFace(Direction.EAST, lo, hi, 12, 3, 3));
        quads.add(cuboidFace(Direction.WEST, lo, hi, 2, 3, 3));
        quads.add(cuboidFace(Direction.SOUTH, lo, hi, 3, 3, 15));
        quads.add(cuboidFace(Direction.NORTH, lo, hi, 3, 3, 0));
        TerrainMeshInfo info = infoOf(quads);
        check(info.plane(TerrainMeshInfo.SLOT_UP) == 1.0f, "UP plane = min");
        check(info.plane(TerrainMeshInfo.SLOT_DOWN) == 9.0f, "DOWN plane = max");
        check(info.plane(TerrainMeshInfo.SLOT_EAST) == 5.0f, "EAST plane = min");
        check(info.plane(TerrainMeshInfo.SLOT_WEST) == 2.0f, "WEST plane = max");
        double eps = TerrainRuns.EPS;
        // camera inside the section on every axis: everything that can face it is drawn
        int inside = info.visibleMask(8, 8, 8, eps);
        check(has(inside, TerrainMeshInfo.SLOT_UP) && has(inside, TerrainMeshInfo.SLOT_DOWN) && has(inside, TerrainMeshInfo.SLOT_EAST)
            && !has(inside, TerrainMeshInfo.SLOT_WEST) && has(inside, TerrainMeshInfo.SLOT_UNASSIGNED), "inside the section");
        // y axis: margins around the extreme planes
        check(!has(info.visibleMask(8, 0.74, 8, eps), TerrainMeshInfo.SLOT_UP), "below the lowest UP plane by more than EPS: UP culled");
        check(has(info.visibleMask(8, 0.76, 8, eps), TerrainMeshInfo.SLOT_UP), "within EPS of the lowest UP plane: UP drawn");
        check(has(info.visibleMask(8, 9.24, 8, eps), TerrainMeshInfo.SLOT_DOWN), "within EPS above the highest DOWN plane: DOWN drawn");
        check(!has(info.visibleMask(8, 9.26, 8, eps), TerrainMeshInfo.SLOT_DOWN), "above the highest DOWN plane by more than EPS: DOWN culled");
        check(!has(info.visibleMask(8, 40, 8, eps), TerrainMeshInfo.SLOT_DOWN) && has(info.visibleMask(8, 40, 8, eps), TerrainMeshInfo.SLOT_UP), "camera above the section");
        check(has(info.visibleMask(8, -20, 8, eps), TerrainMeshInfo.SLOT_DOWN) && !has(info.visibleMask(8, -20, 8, eps), TerrainMeshInfo.SLOT_UP), "camera below the section");
        // x axis
        check(!has(info.visibleMask(4.74, 8, 8, eps), TerrainMeshInfo.SLOT_EAST), "west of every EAST plane: EAST culled");
        check(has(info.visibleMask(4.76, 8, 8, eps), TerrainMeshInfo.SLOT_EAST), "EAST margin");
        check(has(info.visibleMask(2.24, 8, 8, eps), TerrainMeshInfo.SLOT_WEST) && !has(info.visibleMask(2.26, 8, 8, eps), TerrainMeshInfo.SLOT_WEST), "WEST margin");
        // z axis
        check(has(info.visibleMask(8, 8, 15.76, eps), TerrainMeshInfo.SLOT_SOUTH) && !has(info.visibleMask(8, 8, 15.74, eps), TerrainMeshInfo.SLOT_SOUTH), "SOUTH margin");
        check(has(info.visibleMask(8, 8, 0.24, eps), TerrainMeshInfo.SLOT_NORTH) && !has(info.visibleMask(8, 8, 0.26, eps), TerrainMeshInfo.SLOT_NORTH), "NORTH margin");
        // empty bucket (NaN plane) is never wanted; UNASSIGNED always
        TerrainMeshInfo onlyUp = infoOf(List.of(cuboidFace(Direction.UP, lo, hi, 0, 0, 0)));
        int m = onlyUp.visibleMask(0, 100, 0, eps);
        check(m == (1 << TerrainMeshInfo.SLOT_UNASSIGNED | 1 << TerrainMeshInfo.SLOT_UP), "empty buckets (NaN planes) never visible");
        check(onlyUp.quadsOutside(m) == 0, "quadsOutside counts only quads");
        // eye (8, 40, 8): DOWN x2, WEST (x 8 > 2.25), NORTH (z 8 > 0.25), SOUTH (z 8 < 15.75) are behind
        check(info.quadsOutside(info.visibleMask(8, 40, 8, eps)) == 5, "quadsOutside: DOWN x2 + WEST + NORTH + SOUTH");
    }

    private static void visibleMaskProperty() {
        Random rnd = new Random(99);
        double eps = TerrainRuns.EPS;
        int skippedTotal = 0;
        for (int round = 0; round < 300; round++) {
            List<float[]> quads = new ArrayList<>();
            int n = 1 + rnd.nextInt(60);
            for (int i = 0; i < n; i++) {
                quads.add(randomQuad(rnd));
            }
            TerrainMeshInfo info = infoOf(quads);
            for (int e = 0; e < 40; e++) {
                double ex = -24 + rnd.nextDouble() * 64;
                double ey = -24 + rnd.nextDouble() * 64;
                double ez = -24 + rnd.nextDouble() * 64;
                int mask = info.visibleMask(ex, ey, ez, eps);
                check(has(mask, TerrainMeshInfo.SLOT_UNASSIGNED), "UNASSIGNED always drawn");
                for (float[] q : quads) {
                    int slot = TerrainMeshInfo.classifyQuad(q);
                    if (has(mask, slot)) {
                        continue;
                    }
                    skippedTotal++;
                    // every skipped quad is seen from behind by every eye within EPS of (ex, ey, ez): the signed
                    // distance of the eye from the quad plane along the exact normal is <= -EPS * |n|
                    double[] nrm = normal(q);
                    double len = Math.sqrt(nrm[0] * nrm[0] + nrm[1] * nrm[1] + nrm[2] * nrm[2]);
                    double dist = (nrm[0] * (ex - q[0]) + nrm[1] * (ey - q[1]) + nrm[2] * (ez - q[2])) / len;
                    check(dist <= -eps + 1e-9, "skipped quad is back-facing with margin (dist " + dist + ")");
                }
            }
        }
        check(skippedTotal > 1000, "property test exercised skipping (" + skippedTotal + ")");
    }

    // ------------------------------------------------------------------------------------------------------------
    // S1 run merging and S1b/S0c record emission

    private static int[] starts(int... counts) {
        int[] s = new int[counts.length + 1];
        for (int i = 0; i < counts.length; i++) {
            s[i + 1] = s[i] + counts[i];
        }
        return s;
    }

    private static int mask(int... slots) {
        int m = 0;
        for (int s : slots) {
            m |= 1 << s;
        }
        return m;
    }

    private static void runsCases() {
        int[] out = new int[8];
        // layout A: DOWN, NORTH, EAST, UP, UNASSIGNED, SOUTH, WEST
        int[] s = starts(10, 0, 5, 20, 3, 7, 40);   // totals: 0,10,10,15,35,38,45,85
        int n = TerrainRuns.computeRuns(s, mask(3, 4, 5), 0, out);
        check(n == 1 && out[0] == 15 && out[1] == 30, "UP+UNASSIGNED+SOUTH merge into one run");
        n = TerrainRuns.computeRuns(s, mask(0, 4), 0, out);
        check(n == 2 && out[0] == 0 && out[1] == 10 && out[2] == 35 && out[3] == 3, "DOWN and UNASSIGNED: two runs without bridging");
        n = TerrainRuns.computeRuns(s, mask(0, 4), 25, out);
        check(n == 1 && out[0] == 0 && out[1] == 38, "gap of 25 quads bridged with bridge = 25");
        n = TerrainRuns.computeRuns(s, mask(0, 4), 24, out);
        check(n == 2, "gap of 25 quads not bridged with bridge = 24");
        n = TerrainRuns.computeRuns(s, mask(0, 2), 0, out);
        check(n == 1 && out[0] == 0 && out[1] == 15, "runs merge across an empty slot (NORTH)");
        n = TerrainRuns.computeRuns(s, mask(1, 4), 0, out);
        check(n == 1 && out[0] == 35 && out[1] == 3, "wanted but empty slot contributes nothing");
        n = TerrainRuns.computeRuns(s, mask(0, 2, 4, 6), 0, out);
        check(n == 3 && out[0] == 0 && out[1] == 15 && out[2] == 35 && out[3] == 3 && out[4] == 45 && out[5] == 40, "three runs");
        n = TerrainRuns.computeRuns(starts(1, 1, 1, 1, 1, 1, 1), mask(0, 2, 4, 6), 0, out);
        check(n == 4, "at most four runs");
        n = TerrainRuns.computeRuns(starts(0, 0, 0, 0, 0, 0, 0), mask(0, 1, 2, 3, 4, 5, 6), 32, out);
        check(n == 0, "empty section-layer: no runs");
        n = TerrainRuns.computeRuns(s, 127, 0, out);
        check(n == 1 && out[0] == 0 && out[1] == 85, "everything wanted: one run");
    }

    private static void runsProperty() {
        Random rnd = new Random(7);
        int[] out = new int[8];
        for (int round = 0; round < 20000; round++) {
            int[] counts = new int[7];
            for (int i = 0; i < 7; i++) {
                counts[i] = rnd.nextInt(4) == 0 ? 0 : rnd.nextInt(50);
            }
            int[] s = starts(counts);
            int wanted = rnd.nextInt(128) | 1 << TerrainMeshInfo.SLOT_UNASSIGNED;
            int bridge = rnd.nextInt(4) == 0 ? 0 : rnd.nextInt(60);
            int n = TerrainRuns.computeRuns(s, wanted, bridge, out);
            boolean[] covered = new boolean[s[7]];
            int prevEnd = -1;
            boolean ok = true;
            for (int r = 0; r < n; r++) {
                int a = out[2 * r];
                int c = out[2 * r + 1];
                ok &= c > 0 && a >= 0 && a + c <= s[7];
                ok &= a > prevEnd;                                  // sorted, disjoint
                if (r > 0) {
                    ok &= a - prevEnd > bridge;                     // not mergeable with the previous run
                }
                for (int q = a; q < a + c; q++) {
                    covered[q] = true;
                }
                // starts and ends on wanted, non-empty slots
                ok &= slotIsWanted(s, wanted, slotAt(s, a)) && slotIsWanted(s, wanted, slotAt(s, a + c - 1));
                // every unwanted stretch inside the run is at most `bridge` quads
                int stretch = 0;
                for (int q = a; q < a + c; q++) {
                    if (slotIsWanted(s, wanted, slotAt(s, q))) {
                        stretch = 0;
                    } else {
                        stretch++;
                        ok &= stretch <= bridge;
                    }
                }
                prevEnd = a + c;
            }
            for (int q = 0; q < s[7]; q++) {
                if (slotIsWanted(s, wanted, slotAt(s, q))) {
                    ok &= covered[q];
                }
            }
            check(ok, "run property, counts " + Arrays.toString(counts) + " mask " + wanted + " bridge " + bridge);
            if (!ok) {
                break;
            }
        }
    }

    private static int slotAt(int[] s, int quad) {
        for (int slot = 0; slot < 7; slot++) {
            if (quad < s[slot + 1]) {
                return slot;
            }
        }
        return -1;
    }

    private static boolean slotIsWanted(int[] s, int mask, int slot) {
        return slot >= 0 && (mask & 1 << slot) != 0 && s[slot + 1] > s[slot];
    }

    private static void recordEmission() {
        List<DynamicGpuData.IndexedDraw> out = new ArrayList<>();
        int q = TerrainRuns.emitWhole(40000, 1000, 7, TerrainRuns.SHORT_INDEX_QUADS, 1, out);
        check(q == 40000 && out.size() == 3, "40000 quads split into 3 records");
        check(out.get(0).indexCount() == 6 * 16383 && out.get(0).baseVertex() == 1000 && out.get(0).firstIndex() == 0, "first part");
        check(out.get(1).indexCount() == 6 * 16383 && out.get(1).baseVertex() == 1000 + 4 * 16383, "second part");
        check(out.get(2).indexCount() == 6 * (40000 - 2 * 16383) && out.get(2).baseVertex() == 1000 + 8 * 16383, "third part");
        check(out.stream().allMatch(d -> d.baseInstance() == 7 && d.instanceCount() == 1), "instance fields");
        // largest index read by a record is 4 * 16383 - 1 = 65531 < 0xFFFF (primitive restart value)
        check(4 * TerrainRuns.SHORT_INDEX_QUADS - 1 < 0xFFFF, "16-bit buffer never contains 0xFFFF");
        out.clear();
        TerrainRuns.emitWhole(500, 64, 0, TerrainRuns.NO_SPLIT, 1, out);
        check(out.size() == 1 && out.get(0).equals(new DynamicGpuData.IndexedDraw(3000, 1, 0, 64, 0)), "no split: exactly the vanilla record");
        out.clear();
        TerrainRuns.emitWhole(10, 0, 0, TerrainRuns.NO_SPLIT, 3, out);
        check(out.size() == 3 && out.get(0).indexCount() == 18 && out.get(1).indexCount() == 18 && out.get(2).indexCount() == 24
            && out.get(1).baseVertex() == 12 && out.get(2).baseVertex() == 24, "S0c split probe: 3 parts, same triangles");
        out.clear();
        TerrainRuns.emitWhole(2, 0, 0, TerrainRuns.NO_SPLIT, 5, out);
        check(out.size() == 2, "split probe never emits empty records");
        // emit through metadata: bucket runs become records relative to the section's base vertex
        Vector3f lo = new Vector3f(0, 0, 0);
        Vector3f hi = new Vector3f(1, 1, 1);
        List<float[]> quads = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            quads.add(cuboidFace(Direction.DOWN, lo, hi, i, 4, 0));
        }
        for (int i = 0; i < 3; i++) {
            quads.add(cuboidFace(Direction.UP, lo, hi, i, 4, 0));
        }
        for (int i = 0; i < 4; i++) {
            quads.add(cuboidFace(Direction.WEST, lo, hi, 8, 4, i));
        }
        TerrainMeshInfo info = infoOf(quads);
        int[] scratch = new int[8];
        out.clear();
        // eye above (y = 20), east of x = 9.25: UP + WEST? WEST visible iff rx < 8 + EPS; eye x = 3 -> WEST visible
        int vis = info.visibleMask(3, 20, 3, TerrainRuns.EPS);
        int drawn = TerrainRuns.emit(info, vis, 0, 100, 2, TerrainRuns.SHORT_INDEX_QUADS, 1, scratch, out);
        // layout: DOWN [0,5) UP [5,8) WEST [8,12): UP and WEST adjacent (UNASSIGNED, SOUTH empty) -> one run [5,12)
        check(drawn == 7 && out.size() == 1 && out.get(0).baseVertex() == 100 + 4 * 5 && out.get(0).indexCount() == 6 * 7, "UP+WEST run after DOWN");
        out.clear();
        vis = info.visibleMask(3, -20, 3, TerrainRuns.EPS);
        drawn = TerrainRuns.emit(info, vis, 0, 100, 2, TerrainRuns.SHORT_INDEX_QUADS, 1, scratch, out);
        check(drawn == 9 && out.size() == 2, "DOWN + WEST: two runs without bridging");
        out.clear();
        drawn = TerrainRuns.emit(info, vis, 32, 100, 2, TerrainRuns.SHORT_INDEX_QUADS, 1, scratch, out);
        check(drawn == 12 && out.size() == 1, "DOWN + WEST: bridged over the 3 UP quads");
    }

    // ------------------------------------------------------------------------------------------------------------
    // S2 exact frustum

    private static Matrix4f projection(float fovDeg, float aspect) {
        // as Projection.getMatrix: reverse-Z (near/far swapped), zero-to-one
        return new Matrix4f().setPerspective(fovDeg * (float) (Math.PI / 180), aspect, 1024.0f, 0.05f, true);
    }

    private static Matrix4f view(float yawDeg, float pitchDeg) {
        return new Matrix4f().rotationX(pitchDeg * (float) (Math.PI / 180)).rotateY((yawDeg + 180) * (float) (Math.PI / 180));
    }

    private static boolean insideClip(Matrix4f p, Matrix4f v, double x, double y, double z) {
        Vector4d c = new Vector4d(x, y, z, 1.0);
        Vector4d e = new Vector4d();
        // double precision P*V*c
        Matrix4f m = new Matrix4f(p).mul(v);
        e.x = m.m00() * c.x + m.m10() * c.y + m.m20() * c.z + m.m30();
        e.y = m.m01() * c.x + m.m11() * c.y + m.m21() * c.z + m.m31();
        e.w = m.m03() * c.x + m.m13() * c.y + m.m23() * c.z + m.m33();
        return e.w > 0 && Math.abs(e.x) <= e.w && Math.abs(e.y) <= e.w;
    }

    private static void frustumPlanes() {
        Random rnd = new Random(5);
        int culled = 0;
        int visible = 0;
        for (int round = 0; round < 40; round++) {
            Matrix4f p = projection(60 + rnd.nextInt(60), 0.5f + rnd.nextFloat() * 2);
            Matrix4f v = view(rnd.nextFloat() * 360, -89 + rnd.nextFloat() * 178);
            TerrainFrustum f = TerrainFrustum.of(p, v);
            for (int b = 0; b < 300; b++) {
                double x0 = -200 + rnd.nextDouble() * 400;
                double y0 = -200 + rnd.nextDouble() * 400;
                double z0 = -200 + rnd.nextDouble() * 400;
                double sx = 0.5 + rnd.nextDouble() * 16;
                double sy = 0.5 + rnd.nextDouble() * 16;
                double sz = 0.5 + rnd.nextDouble() * 16;
                boolean vis = f.isBoxVisible(x0, y0, z0, x0 + sx, y0 + sy, z0 + sz);
                boolean anyInside = false;
                for (int i = 0; i <= 6 && !anyInside; i++) {
                    for (int j = 0; j <= 6 && !anyInside; j++) {
                        for (int k = 0; k <= 6 && !anyInside; k++) {
                            anyInside = insideClip(p, v, x0 + sx * i / 6, y0 + sy * j / 6, z0 + sz * k / 6);
                        }
                    }
                }
                if (anyInside) {
                    check(vis, "box with a point inside the view volume is visible");
                }
                if (vis) {
                    visible++;
                } else {
                    culled++;
                }
            }
            // camera inside the box is always visible; boxes straight behind are culled
            check(f.isBoxVisible(-1, -1, -1, 1, 1, 1), "box around the camera visible");
        }
        check(culled > 2000 && visible > 1000, "frustum culls and keeps (culled " + culled + ", visible " + visible + ")");
        // a box just beyond the right edge is culled, and comes back once it touches the edge
        Matrix4f p = projection(90, 1.0f);
        Matrix4f v = new Matrix4f();   // looking down -z
        TerrainFrustum f = TerrainFrustum.of(p, v);
        check(!f.isBoxVisible(10.01, -1, -10, 11, 1, -9.99), "right of the x = -z plane");
        check(f.isBoxVisible(9.99, -1, -10, 11, 1, -9.99), "touching the x = -z plane");
        check(!f.isBoxVisible(-1, -1, 1, 1, 1, 3), "right behind the camera (eye plane)");
        check(!f.isBoxVisible(2, -1, 1, 3, 1, 1.5), "behind and to the side");
        check(f.isBoxVisible(-1, -1, -0.01, 1, 1, 3), "straddling the eye plane");
        check(f.isBoxVisible(Double.NaN, 0, 0, 1, 1, 1), "NaN bounds never culled");
    }

    private static void frustumEyeAndOrientation() {
        Matrix4f p = projection(77, 1.6f);
        Matrix4f v = view(37, 12);
        TerrainFrustum plain = TerrainFrustum.of(p, v);
        check(plain.hasEye() && Math.abs(plain.eyeX()) < 1e-6 && Math.abs(plain.eyeY()) < 1e-6 && Math.abs(plain.eyeZ()) < 1e-6, "unbobbed eye at the camera");
        // view bob as GameRenderer.bobView: translate then rotate Z, X; applied as projection * bob
        float bob = 0.1f;
        float walk = 0.37f;
        Matrix4f bobMat = new Matrix4f()
            .translate((float) Math.sin(walk * Math.PI) * bob * 0.5f, -Math.abs((float) Math.cos(walk * Math.PI) * bob), 0)
            .rotateZ((float) Math.toRadians(Math.sin(walk * Math.PI) * bob * 3))
            .rotateX((float) Math.toRadians(Math.abs(Math.cos(walk * Math.PI - 0.2) * bob) * 5));
        Matrix4f bobbed = new Matrix4f(p).mul(bobMat);
        TerrainFrustum f = TerrainFrustum.of(bobbed, v);
        double len = Math.sqrt(f.eyeX() * f.eyeX() + f.eyeY() * f.eyeY() + f.eyeZ() * f.eyeZ());
        check(f.hasEye() && len > 0.01 && len <= 1.12 * bob + 1e-4, "bobbed eye offset within 1.12 * bob (" + len + ")");
        // the eye maps to clip x = y = w = 0
        Matrix4f m = new Matrix4f(bobbed).mul(v);
        double cx = m.m00() * f.eyeX() + m.m10() * f.eyeY() + m.m20() * f.eyeZ() + m.m30();
        double cy = m.m01() * f.eyeX() + m.m11() * f.eyeY() + m.m21() * f.eyeZ() + m.m31();
        double cw = m.m03() * f.eyeX() + m.m13() * f.eyeY() + m.m23() * f.eyeZ() + m.m33();
        check(Math.abs(cx) < 1e-5 && Math.abs(cy) < 1e-5 && Math.abs(cw) < 1e-5, "eye is the centre of projection");
        check(f.determinant() > 0 == plain.determinant() > 0, "bob keeps the orientation");
        // nausea: rotate/scale/rotate the projection (GameRenderer.renderLevel): linear, eye unchanged, orientation kept
        Vector3f axis = new Vector3f(0, (float) Math.sqrt(2) / 2, (float) Math.sqrt(2) / 2);
        Matrix4f nausea = new Matrix4f(p).rotate(0.7f, axis).scale(1 / 0.6f, 1, 1).rotate(-0.7f, axis);
        TerrainFrustum fn = TerrainFrustum.of(nausea, v);
        check(fn.hasEye() && Math.abs(fn.eyeX()) < 1e-5 && Math.abs(fn.eyeY()) < 1e-5 && Math.abs(fn.eyeZ()) < 1e-5, "nausea keeps the eye");
        check(fn.determinant() > 0 == plain.determinant() > 0, "nausea keeps the orientation");
        Matrix4f mirrored = new Matrix4f(p).scale(-1, 1, 1);
        check(TerrainFrustum.of(mirrored, v).determinant() > 0 != plain.determinant() > 0, "a mirror flips the orientation sign");
        Matrix4f ortho = new Matrix4f().setOrtho(0, 100, 100, 0, 1000, 21000, true);
        check(!TerrainFrustum.of(ortho, new Matrix4f()).hasEye(), "orthographic: no eye");
        // corner rays: unit vectors pointing into the view
        double[] dirs = plain.cornerDirections();
        check(dirs != null, "corner directions");
        Vector3f forward = new Matrix4f(v).invert().transformDirection(new Vector3f(0, 0, -1));
        for (int i = 0; i < 12; i += 3) {
            double dot = dirs[i] * forward.x + dirs[i + 1] * forward.y + dirs[i + 2] * forward.z;
            check(dot > 0.3 && Math.abs(dirs[i] * dirs[i] + dirs[i + 1] * dirs[i + 1] + dirs[i + 2] * dirs[i + 2] - 1) < 1e-9, "corner ray points forward");
            // a point on the corner ray, nudged towards the view axis, is inside; nudged away, outside
            double d = 50;
            check(insideClip(p, v, dirs[i] * d + forward.x * 0.05, dirs[i + 1] * d + forward.y * 0.05, dirs[i + 2] * d + forward.z * 0.05), "corner ray bounds the view (inside)");
            check(!insideClip(p, v, dirs[i] * d - forward.x * 0.5, dirs[i + 1] * d - forward.y * 0.5, dirs[i + 2] * d - forward.z * 0.5), "corner ray bounds the view (outside)");
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // S7 cloud faces

    private static void encodeFace(ByteBuffer b, int x, int z, int dir, int flags) {
        int dirAndFlags = dir | flags | (x & 1) << 7 | (z & 1) << 6;
        b.put((byte) (x >> 1)).put((byte) (z >> 1)).put((byte) dirAndFlags);
    }

    private static boolean cellVisible(Matrix4f p, Matrix4f mv, int cx, int cz, double ox, double oy, double oz) {
        double x0 = cx * 12.0 + ox;
        double z0 = cz * 12.0 + oz;
        for (int i = 0; i <= 6; i++) {
            for (int j = 0; j <= 4; j++) {
                for (int k = 0; k <= 6; k++) {
                    if (insideClip(p, mv, x0 + 2.0 * i, oy + j, z0 + 2.0 * k)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static void cloudFilter() {
        int radius = 24;
        ByteBuffer all = ByteBuffer.allocate(3 * 6 * (2 * radius + 1) * (2 * radius + 1));
        List<int[]> cells = new ArrayList<>();
        for (int ring = 0; ring <= 2 * radius; ring++) {
            for (int x = -ring; x <= ring; x++) {
                int z = ring - Math.abs(x);
                for (int zz : z == 0 ? new int[]{0} : new int[]{-z, z}) {
                    if (Math.abs(zz) <= radius && Math.abs(x) <= radius) {
                        cells.add(new int[]{x, zz});
                        encodeFace(all, x, zz, 0, 0);       // DOWN
                        encodeFace(all, x, zz, 2, 0);       // NORTH
                        if ((x + zz) % 3 == 0) {
                            encodeFace(all, x, zz, 5, 16);  // EAST inside face
                        }
                    }
                }
            }
        }
        int faceCount = all.position() / 3;
        byte[] faces = Arrays.copyOf(all.array(), all.position());
        Random rnd = new Random(11);
        int keptTotal = 0;
        int facesTotal = 0;
        int coveredFrames = 0;
        for (int round = 0; round < 25; round++) {
            Matrix4f p = projection(77, 1.6f);
            float yaw = rnd.nextFloat() * 360;
            float pitch = -60 + rnd.nextFloat() * 120;
            double ox = -rnd.nextDouble() * 12;
            double oy = round % 2 == 0 ? 40 + rnd.nextDouble() * 60 : -40 - rnd.nextDouble() * 60;
            double oz = -rnd.nextDouble() * 12;
            CloudFaceFilter filter = new CloudFaceFilter();
            TerrainFrustum f0 = TerrainFrustum.of(p, view(yaw, pitch));
            filter.reset(f0, ox, oy, oz);
            check(!filter.keepsAll() && filter.covers(f0, ox, oy, oz), "reference covers its own frame");
            ByteBuffer out = ByteBuffer.allocate(faces.length);
            int kept = filter.filter(faces, faceCount, out);
            // TerrainToggles.cloudRunCopy: one bulk put per run of kept faces writes the same bytes as one put per face
            boolean runCopy = TerrainToggles.cloudRunCopy;
            TerrainToggles.cloudRunCopy = !runCopy;
            ByteBuffer other = ByteBuffer.allocate(faces.length);
            int keptOther = filter.filter(faces, faceCount, other);
            TerrainToggles.cloudRunCopy = runCopy;
            check(keptOther == kept && other.position() == out.position()
                && Arrays.equals(Arrays.copyOf(other.array(), other.position()), Arrays.copyOf(out.array(), out.position())),
                "cloud run copy and per-face copy write the same bytes");
            keptTotal += kept;
            facesTotal += faceCount;
            // order preserved: output is a subsequence of the input
            byte[] o = Arrays.copyOf(out.array(), out.position());
            int at = 0;
            for (int i = 0; i < faceCount && at < kept; i++) {
                if (faces[3 * i] == o[3 * at] && faces[3 * i + 1] == o[3 * at + 1] && faces[3 * i + 2] == o[3 * at + 2]) {
                    at++;
                }
            }
            check(at == kept, "kept faces keep their order");
            // frames near the reference: when covered, every visible cell was kept
            for (int frame = 0; frame < 12; frame++) {
                float dyaw = (rnd.nextFloat() - 0.5f) * 14;
                float dpitch = (rnd.nextFloat() - 0.5f) * 14;
                double dx = (rnd.nextDouble() - 0.5) * 7;
                double dy = (rnd.nextDouble() - 0.5) * 7;
                double dz = (rnd.nextDouble() - 0.5) * 7;
                Matrix4f pf = new Matrix4f(p).translate(0.03f * frame % 0.1f, -0.05f, 0).rotateZ((float) Math.toRadians(frame - 6));
                Matrix4f vf = view(yaw + dyaw, Math.max(-89, Math.min(89, pitch + dpitch)));
                TerrainFrustum ff = TerrainFrustum.of(pf, vf);
                if (!filter.covers(ff, ox + dx, oy + dy, oz + dz)) {
                    continue;
                }
                boolean ok = true;
                for (int[] c : cells) {
                    if (!filter.keepCell(c[0], c[1]) && cellVisible(pf, vf, c[0], c[1], ox + dx, oy + dy, oz + dz)) {
                        ok = false;
                        System.out.println("  visible cloud cell " + Arrays.toString(c) + " dropped (round " + round + ", frame " + frame + ")");
                    }
                }
                check(ok, "covered frame: every visible cloud cell was kept");
                coveredFrames++;
            }
            // big rotation or movement invalidates the reference
            check(!filter.covers(TerrainFrustum.of(p, view(yaw + 25, pitch)), ox, oy, oz), "25 degree turn needs a re-filter");
            check(!filter.covers(f0, ox + 7, oy, oz), "7 block move needs a re-filter");
        }
        check(keptTotal < facesTotal * 0.75, "filter drops a substantial share (" + keptTotal + " of " + facesTotal + ")");
        check(coveredFrames > 100, "nearby frames exercised (" + coveredFrames + ")");
        System.out.printf("  clouds: kept %d of %d faces (%.0f%%), %d covered nearby frames checked%n", keptTotal, facesTotal, 100.0 * keptTotal / facesTotal, coveredFrames);
        // very wide field of view: upload everything
        CloudFaceFilter wide = new CloudFaceFilter();
        TerrainFrustum fw = TerrainFrustum.of(projection(170, 2.0f), view(0, 0));
        wide.reset(fw, 0, 50, 0);
        check(wide.keepsAll() && wide.covers(fw, 0, 50, 0), "wide view keeps every face");
        ByteBuffer out = ByteBuffer.allocate(faces.length);
        check(wide.filter(faces, faceCount, out) == faceCount, "keep-all copies every face");
        check(!new CloudFaceFilter().covers(fw, 0, 0, 0), "a fresh filter covers nothing");
    }

    // ------------------------------------------------------------------------------------------------------------

    // ------------------------------------------------------------------------------------------------------------
    // S5: compact 16-byte vertex (TerrainCompact)

    private static void putBlockVertex(ByteBuffer b, float x, float y, float z, int color, float u, float v, int light) {
        b.putFloat(x).putFloat(y).putFloat(z).putInt(color).putFloat(u).putFloat(v).putInt(light);
    }

    private static void compactCodes() {
        check(TerrainCompact.FORMAT.getVertexSize() == 16, "compact vertex is 16 bytes");
        check(TerrainCompact.FORMAT.getElement("Position").offset() == 0 && TerrainCompact.FORMAT.getElement("Color").offset() == 8
            && TerrainCompact.FORMAT.getElement("UV0").offset() == 12, "compact attribute offsets 0/8/12 (Metal: multiples of 4)");
        check(TerrainCompact.SHARED_ALIGNMENT % 28 == 0 && TerrainCompact.SHARED_ALIGNMENT % 16 == 0, "shared heap alignment is a multiple of both strides");
        // positions: the 1/2048 grid over [-8, 24)
        check(TerrainCompact.positionCode(0.0f) == 16384, "p = 0 -> 16384");
        check(TerrainCompact.positionCode(-8.0f) == 0, "p = -8 -> 0");
        check(TerrainCompact.positionCode(24.0f - 1.0f / 2048) == 65535, "p = 24 - 2^-11 -> 65535");
        check(TerrainCompact.positionCode(24.0f) == -1, "p = 24 is out of range");
        check(TerrainCompact.positionCode(-8.0f - 1.0f / 2048) == -1, "p < -8 is out of range");
        check(TerrainCompact.positionCode(16.0f) == 49152, "p = 16 -> 49152");
        check(TerrainCompact.positionCode(7.5f / 16) >= 0 && TerrainCompact.positionCode(0.001f) == -1, "1/32 exact, 0.001 not");
        check(TerrainCompact.positionCode(1.0f / 4096) == -1, "finer than 1/2048 -> no code");
        check(TerrainCompact.positionCode(0.1464f) == -1 && TerrainCompact.positionCode((float)(0.5 - 0.5 / Math.sqrt(2))) == -1, "45-degree plant coordinates -> no code");
        check(TerrainCompact.positionCode(-0.0f) == -1, "-0.0 rejected (would decode as +0)");
        check(TerrainCompact.positionCode(Float.NaN) == -1 && TerrainCompact.positionCode(Float.POSITIVE_INFINITY) == -1, "NaN/inf rejected");
        check(TerrainCompact.positionCode(Float.MIN_VALUE) == -1, "denormal rejected");
        check(TerrainCompact.positionCode((float)Math.scalb(1.0, -60)) == -1, "tiny value that rounds to 8 in double is rejected by the round trip");
        // uvs: the 2^-15 grid over [0, 2)
        check(TerrainCompact.uvCode(0.0f) == 0 && TerrainCompact.uvCode(1.0f) == 32768, "u = 0 / 1 (atlas edge)");
        check(TerrainCompact.uvCode(37.0f / 1024 + 0.5f / 1024) == (int)((37.5 / 1024) * 32768), "half-texel of a 1024 atlas");
        check(TerrainCompact.uvCode(1.0f / 65536) == -1, "finer than 2^-15 -> no code");
        check(TerrainCompact.uvCode(-0.0f) == -1 && TerrainCompact.uvCode(-1.0f / 32768) == -1, "negative u rejected");
        check(TerrainCompact.uvCode(2.0f) == -1, "u = 2 out of range");
        check(TerrainCompact.uvCode(0.3f) == -1, "non-dyadic u rejected");
        // light: two RG16_SINT values in [0, 255]
        check(TerrainCompact.lightCode(0x00F000F0) == (0xF0 | 0xF0 << 8), "full bright");
        check(TerrainCompact.lightCode(0x00FF0000) == 0xFF00 && TerrainCompact.lightCode(0x000000FF) == 0x00FF, "sky in the high byte, block in the low");
        check(TerrainCompact.lightCode(0x00000100) == -1 && TerrainCompact.lightCode(0x01000000) == -1, "light 256 rejected");
        check(TerrainCompact.lightCode(0x0000FFFF) == -1 && TerrainCompact.lightCode(0xFFFF0000) == -1, "negative light rejected");
        // every code decodes to itself (exhaustive over the 16-bit range)
        boolean roundTrip = true;
        for (int code = 0; code < 65536; code++) {
            roundTrip &= TerrainCompact.positionCode(TerrainCompact.decodePosition(code)) == code || TerrainCompact.decodePosition(code) == 0.0f && code == 16384;
            roundTrip &= TerrainCompact.uvCode(TerrainCompact.decodeUv(code)) == code;
        }
        check(roundTrip, "every 16-bit position and UV code round-trips");
    }

    private static void compactTranscode() {
        Random rnd = new Random(55);
        for (int iter = 0; iter < 200; iter++) {
            int vertices = 4 * (1 + rnd.nextInt(300));
            int base = 28 * rnd.nextInt(3);
            ByteBuffer b = ByteBuffer.allocateDirect(base + vertices * 28 + 64).order(ByteOrder.nativeOrder());
            float[][] fields = new float[vertices][5];
            int[] colors = new int[vertices];
            int[] lights = new int[vertices];
            b.position(base);
            for (int i = 0; i < vertices; i++) {
                for (int a = 0; a < 3; a++) {
                    fields[i][a] = rnd.nextInt(16 * 2048 + 4096) / 2048.0f - 1.0f;
                }
                fields[i][3] = rnd.nextInt(32769) / 32768.0f;
                fields[i][4] = rnd.nextInt(32769) / 32768.0f;
                colors[i] = rnd.nextInt();
                lights[i] = rnd.nextInt(256) | rnd.nextInt(256) << 16;
                putBlockVertex(b, fields[i][0], fields[i][1], fields[i][2], colors[i], fields[i][3], fields[i][4], lights[i]);
            }
            for (int i = 0; i < 64; i++) {
                b.put((byte)0x5A);
            }
            int bad = rnd.nextInt(4);
            int badVertex = rnd.nextInt(vertices);
            int expected = TerrainCompact.EXACT;
            if (iter % 2 == 1) {
                int at = base + badVertex * 28;
                switch (bad) {
                    case 0 -> { b.putFloat(at + 4 * rnd.nextInt(3), 0.3f); expected = TerrainCompact.FAIL_POSITION; }
                    case 1 -> { b.putFloat(at + 16 + 4 * rnd.nextInt(2), 1.0f / 65536); expected = TerrainCompact.FAIL_UV; }
                    case 2 -> { b.putInt(at + 24, 0x0100); expected = TerrainCompact.FAIL_LIGHT; }
                    default -> { b.putFloat(at, 30.0f); expected = TerrainCompact.FAIL_POSITION; }
                }
            }
            ByteBuffer view = b.duplicate().order(ByteOrder.BIG_ENDIAN);   // byte order of the passed view must not matter
            byte[] before = new byte[b.capacity()];
            b.duplicate().clear().get(before);
            int reason = TerrainCompact.check(view, base, vertices);
            check(reason == expected, "check reason " + reason + " (expected " + expected + ")");
            byte[] afterCheck = new byte[b.capacity()];
            b.duplicate().clear().get(afterCheck);
            check(Arrays.equals(before, afterCheck), "check leaves the buffer unchanged");
            if (reason != TerrainCompact.EXACT) {
                continue;
            }
            ByteBuffer out = TerrainCompact.transcodeInPlace(view, base, vertices);
            check(out.remaining() == 16 * vertices && out.order() == ByteOrder.nativeOrder(), "transcode returns exactly the compact bytes");
            boolean exact = true;
            for (int i = 0; i < vertices; i++) {
                int at = 16 * i;
                for (int a = 0; a < 3; a++) {
                    float p = TerrainCompact.decodePosition(Short.toUnsignedInt(out.getShort(at + 2 * a)));
                    exact &= Float.floatToRawIntBits(p) == Float.floatToRawIntBits(fields[i][a]);
                }
                int w = Short.toUnsignedInt(out.getShort(at + 6));
                exact &= (w & 0xFF) == (lights[i] & 0xFFFF) && (w >>> 8) == (lights[i] >>> 16);
                exact &= out.getInt(at + 8) == colors[i];
                exact &= Float.floatToRawIntBits(TerrainCompact.decodeUv(Short.toUnsignedInt(out.getShort(at + 12)))) == Float.floatToRawIntBits(fields[i][3]);
                exact &= Float.floatToRawIntBits(TerrainCompact.decodeUv(Short.toUnsignedInt(out.getShort(at + 14)))) == Float.floatToRawIntBits(fields[i][4]);
            }
            check(exact, "every compact vertex decodes to the 28-byte vertex's position, colour, UV and light bits");
            byte[] after = new byte[b.capacity()];
            b.duplicate().clear().get(after);
            check(Arrays.equals(Arrays.copyOfRange(before, 0, base), Arrays.copyOfRange(after, 0, base)), "transcode: bytes before the range untouched");
            int end = base + vertices * 28;
            check(Arrays.equals(Arrays.copyOfRange(before, end, before.length), Arrays.copyOfRange(after, end, after.length)), "transcode: bytes after the range untouched");
        }
        // SectionCompiler order: reorder into facing buckets first, then transcode; the quad ranges stay valid.
        ByteBuffer b = ByteBuffer.allocateDirect(6 * 4 * 28).order(ByteOrder.nativeOrder());
        Direction[] order = {Direction.WEST, Direction.UP, Direction.SOUTH, Direction.DOWN, Direction.EAST, Direction.NORTH};
        for (int q = 0; q < order.length; q++) {
            float[] p = cuboidFace(order[q], new Vector3f(0, 0, 0), new Vector3f(1, 1, 1), 3, 4, 5);
            for (int v = 0; v < 4; v++) {
                putBlockVertex(b, p[3 * v], p[3 * v + 1], p[3 * v + 2], q, 0.5f, 0.25f, 0x00F00010);
            }
        }
        b.flip();
        TerrainMeshInfo info = TerrainMeshInfo.analyze(b, 24, 28, 0, true);
        check(TerrainCompact.check(b, 0, 24) == TerrainCompact.EXACT, "unit cube faces are exact");
        ByteBuffer out = TerrainCompact.transcodeInPlace(b, 0, 24);
        boolean bucketsKept = true;
        for (int slot = 0; slot < TerrainMeshInfo.SLOT_COUNT; slot++) {
            for (int q = info.slotStart(slot); q < info.slotStart(slot) + info.slotQuads(slot); q++) {
                // colour holds the emission index; its facing must be the slot's
                int emitted = out.getInt(16 * 4 * q + 8);
                bucketsKept &= slotOf(order[emitted]) == slot;
            }
        }
        check(bucketsKept, "transcode after the bucket reorder keeps every quad in its bucket range");
    }

    /** S5 snap mode: near-grid values (block-state rotation noise) get the nearest code; far ones are still rejected. */
    private static void compactSnap() {
        double pt = TerrainCompact.SNAP_POSITION_TOLERANCE;
        double ut = TerrainCompact.SNAP_UV_TOLERANCE;
        check(pt == 0x1p-18 && ut == 0x1p-22, "default snap tolerances");
        // rotation noise as FaceBakery produces it (rotateVertexBy with a float rotation matrix): a few ulps off the grid
        float almostOne = Math.nextDown(1.0f);
        check(TerrainCompact.positionCode(almostOne) == -1, "exact mode rejects 0.99999994");
        check(TerrainCompact.positionCode(almostOne, pt) == TerrainCompact.positionCode(1.0f), "snap: 0.99999994 -> code of 1");
        check(TerrainCompact.positionCode(1.1920929E-7f, pt) == 16384 && TerrainCompact.positionCode(Float.MIN_VALUE, pt) == 16384, "snap: 1.2e-7 / denormal -> code of 0");
        check(TerrainCompact.positionCode(-1.1920929E-7f, pt) == 16384, "snap: -1.2e-7 -> code of 0");
        check(TerrainCompact.positionCode(-0.0f, pt) == 16384, "snap: -0.0 -> code of 0");
        check(TerrainCompact.positionCode(15.999999f, pt) == TerrainCompact.positionCode(16.0f), "snap: 15.999999 -> code of 16");
        check(TerrainCompact.positionCode(Math.nextUp(Math.nextUp(16.0f)), pt) == TerrainCompact.positionCode(16.0f), "snap: two ulps above 16 -> code of 16");
        check(TerrainCompact.positionCode(16.0f + 1e-5f, pt) == -1, "snap: 1e-5 off is rejected");
        check(TerrainCompact.positionCode(-8.0f - 1e-6f, pt) == 0, "snap: just below -8 -> code 0");
        check(TerrainCompact.positionCode(24.0f - 1.0f / 4096, pt) == -1, "snap: half a grid step off is rejected");
        check(TerrainCompact.positionCode(0.2f, pt) == -1 && TerrainCompact.positionCode(0.1464f, pt) == -1, "snap: random offsets / 45-degree plants still rejected");
        check(TerrainCompact.positionCode(1.0f / 30, pt) == -1, "snap: 1/30 (random block offset step) rejected");
        check(TerrainCompact.positionCode(24.0f, pt) == -1 && TerrainCompact.positionCode(Float.NaN, pt) == -1, "snap: range and NaN");
        check(TerrainCompact.uvCode(Math.nextUp(0.5f), ut) == 16384 && TerrainCompact.uvCode(0.5f + 1.0f / 65536, ut) == -1, "snap: UV within 2^-22 only");
        check(TerrainCompact.uvCode(-1e-9f, ut) == 0, "snap: tiny negative u -> 0");
        // every float within the tolerance of a grid value snaps to it, and the decode error stays within the tolerance
        Random rnd = new Random(77);
        boolean bounded = true;
        for (int i = 0; i < 200_000; i++) {
            int code = rnd.nextInt(65536);
            float exact = TerrainCompact.decodePosition(code);
            float noisy = (float)(exact + (rnd.nextDouble() * 2 - 1) * pt);
            int got = TerrainCompact.positionCode(noisy, pt);
            bounded &= got == code || Math.abs(TerrainCompact.decodePosition(got) - (double)noisy) <= pt;
            bounded &= got >= 0;
        }
        check(bounded, "snap: positions within the tolerance always get a code within the tolerance");
        // layer check + transcode in snap mode: the reported max error bounds every decoded component
        ByteBuffer b = ByteBuffer.allocateDirect(400 * 28).order(ByteOrder.nativeOrder());
        float[][] fields = new float[400][5];
        for (int i = 0; i < 400; i++) {
            for (int a = 0; a < 3; a++) {
                float g = rnd.nextInt(16 * 16 + 1) / 16.0f;
                fields[i][a] = rnd.nextInt(3) == 0 ? Math.nextUp(g) : rnd.nextInt(3) == 0 ? Math.nextDown(g) : g;
            }
            fields[i][3] = rnd.nextInt(1025) / 1024.0f;
            fields[i][4] = rnd.nextInt(1025) / 1024.0f;
            putBlockVertex(b, fields[i][0], fields[i][1], fields[i][2], -1, fields[i][3], fields[i][4], 0x00F000F0);
        }
        b.flip();
        double[] err = new double[2];
        check(TerrainCompact.check(b, 0, 400) == TerrainCompact.FAIL_POSITION, "noisy layer is not exact");
        check(TerrainCompact.check(b, 0, 400, pt, ut, err) == TerrainCompact.EXACT && err[0] > 0 && err[0] <= 0x1p-19 && err[1] == 0.0,
            "noisy layer is accepted in snap mode, max position error " + err[0]);
        ByteBuffer out = TerrainCompact.transcodeInPlace(b, 0, 400, pt, ut);
        double worst = 0;
        for (int i = 0; i < 400; i++) {
            for (int a = 0; a < 3; a++) {
                worst = Math.max(worst, Math.abs(TerrainCompact.decodePosition(Short.toUnsignedInt(out.getShort(16 * i + 2 * a))) - (double)fields[i][a]));
            }
        }
        check(worst == err[0], "snap transcode error " + worst + " equals the checked max " + err[0]);
    }

    private static void statsCounters() {
        TerrainStats.reset();
        TerrainStats.add(TerrainStats.QUADS_SOLID, 10);
        TerrainStats.add(TerrainStats.RECORDS, 3);
        TerrainStats.endFrame();
        TerrainStats.add(TerrainStats.QUADS_SOLID, 5);
        TerrainStats.endFrame();
        check(TerrainStats.total[TerrainStats.QUADS_SOLID] == 15 && TerrainStats.total[TerrainStats.RECORDS] == 3 && TerrainStats.frames == 2, "stats accumulate per frame");
        TerrainStats.reset();
        check(TerrainStats.total[TerrainStats.QUADS_SOLID] == 0 && TerrainStats.frames == 0, "stats reset");
    }
}
