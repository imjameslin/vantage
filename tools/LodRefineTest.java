import java.util.Arrays;
import java.util.Random;

/**
 * CPU specification/counterexamples for FARSIGHT-BOX-REFINE-PROTOTYPE.md.
 * Standalone; no Minecraft initialization, native library, GPU, or client rebuild.
 * This tests a proposed policy, NOT native-kernel/raster equivalence.
 */
public final class LodRefineTest {
    private static int checks;
    private static int counterexamples;
    private static final float[][] PLANES = {
        {1, 0, 1, 0}, {-1, 0, 1, 0}, {0, 1, 1, 0}, {0, -1, 1, 0}, {0, 0, 1, 0}
    };

    private static void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
    }

    private record Box(float[] lo, float[] hi, boolean unusable) {
        boolean finite() {
            for (int a = 0; a < 3; a++) {
                if (!Float.isFinite(lo[a]) || !Float.isFinite(hi[a]) || lo[a] > hi[a]) return false;
            }
            return !unusable;
        }
    }

    private record Volume(boolean visible, boolean eligible) {}

    // Scalar model of the support-point predicates in mcmtl_lod_sec_test.
    // Proposed stricter eligibility also rejects malformed/nonfinite inputs.
    private static Volume support(Box b, float[][] planes, boolean enabled) {
        if (!enabled || !b.finite()) return new Volume(true, false);
        for (float[] p : planes) for (float f : p) if (!Float.isFinite(f)) return new Volume(true, false);
        boolean visible = true, inside = true;
        for (int i = 0; i < planes.length; i++) {
            float[] p = planes[i];
            float near = p[3], far = p[3];
            for (int a = 0; a < 3; a++) {
                near += p[a] * (p[a] >= 0 ? b.lo[a] : b.hi[a]);
                far += p[a] * (p[a] >= 0 ? b.hi[a] : b.lo[a]);
            }
            if (!Float.isFinite(near) || !Float.isFinite(far)) return new Volume(true, false);
            visible &= far >= 0;
            inside &= near >= (i == 4 ? 4 : 0);
        }
        return new Volume(visible, visible && inside);
    }

    // Independent oracle enumerates all eight corners, using doubles.
    private static Volume corners(Box b, float[][] planes) {
        boolean visible = true, inside = true;
        for (int i = 0; i < planes.length; i++) {
            double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
            for (int k = 0; k < 8; k++) {
                double d = planes[i][3];
                for (int a = 0; a < 3; a++) d += planes[i][a] * (double)((k & (1 << a)) == 0 ? b.lo[a] : b.hi[a]);
                min = Math.min(min, d);
                max = Math.max(max, d);
            }
            visible &= max >= 0;
            inside &= min >= (i == 4 ? 4 : 0);
        }
        return new Volume(visible, visible && inside);
    }

    private static Box box(float lx, float ly, float lz, float hx, float hy, float hz) {
        return new Box(new float[]{lx, ly, lz}, new float[]{hx, hy, hz}, false);
    }

    private static Box union(Box a, Box b) {
        float[] lo = new float[3], hi = new float[3];
        for (int i = 0; i < 3; i++) {
            lo[i] = Math.min(a.lo[i], b.lo[i]);
            hi[i] = Math.max(a.hi[i], b.hi[i]);
        }
        return new Box(lo, hi, !a.finite() || !b.finite());
    }

    private static void frustumTests() {
        Random random = new Random(0xB0_5E_C710L);
        for (int n = 0; n < 40_000; n++) {
            // Binary-exact coordinates/coefficients keep this an algebra check,
            // not an assertion about untested GPU fast-math rounding.
            Box[] child = new Box[2];
            for (int c = 0; c < 2; c++) {
                float x = random.nextInt(801) / 8f - 50;
                float y = random.nextInt(801) / 8f - 50;
                float z = random.nextInt(801) / 8f - 25;
                child[c] = box(x, y, z, x + random.nextInt(65) / 8f,
                    y + random.nextInt(65) / 8f, z + random.nextInt(65) / 8f);
            }
            Box parent = union(child[0], child[1]);
            Volume pv = support(parent, PLANES, true);
            check(pv.equals(corners(parent, PLANES)), "section support/corner equivalence");
            for (Box b : child) {
                Volume cv = support(b, PLANES, true);
                check(cv.equals(corners(b, PLANES)), "cluster support/corner equivalence");
                check(pv.visible || !cv.visible, "section rejection never drops a visible child");
            }
        }
        Box ordinary = box(-1, -1, 8, 1, 1, 10);
        check(support(ordinary, PLANES, true).eligible, "ordinary interior box queries");
        check(!support(box(-1, -1, -1, 1, 1, 8), PLANES, true).eligible, "eye straddle draws without query suppression");
        check(!support(box(-1, -1, 3, 1, 1, 8), PLANES, true).eligible, "eye guard draws without suppression");
        check(support(box(-1, -1, 4, 1, 1, 8), PLANES, true).eligible, "eye guard boundary");
        check(!support(box(7, -1, 8, 10, 1, 10), PLANES, true).eligible, "side-plane straddle");
        check(support(ordinary, PLANES, false).equals(new Volume(true, false)), "disabled frustum fails open");
        Box bad = new Box(new float[]{Float.NaN, 0, 1}, new float[]{1, 1, 2}, false);
        check(support(bad, PLANES, true).equals(new Volume(true, false)), "NaN bounds fail open");
        check(support(union(ordinary, bad), PLANES, true).equals(new Volume(true, false)), "bad child poisons parent bounds");
        check(!support(new Box(ordinary.lo, ordinary.hi, true), PLANES, true).eligible, "unusable bounds");
        check(!support(box(2, 0, 8, 1, 1, 10), PLANES, true).eligible, "inverted bounds");
        float[][] invalidPlanes = Arrays.stream(PLANES).map(float[]::clone).toArray(float[][]::new);
        invalidPlanes[2][0] = Float.POSITIVE_INFINITY;
        check(support(ordinary, invalidPlanes, true).equals(new Volume(true, false)), "nonfinite plane fails open");
    }

    // Full certificate required by the design. All values describe the exact
    // depth-producing draw state, not just the world tick or LOD selection.
    private static final class State {
        double cameraX, cameraY, cameraZ, eyeX, eyeY, eyeZ;
        float[] clip = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
        int width = 3024, height = 1964, samples = 1, layerMask = 7;
        long items = 1, geometry = 1, coverage = 1, origin = 1, mode = 1;
        boolean supplied = true, stableCoverage = true;

        State copy() {
            State s = new State();
            s.cameraX = cameraX; s.cameraY = cameraY; s.cameraZ = cameraZ;
            s.eyeX = eyeX; s.eyeY = eyeY; s.eyeZ = eyeZ; s.clip = clip.clone();
            s.width = width; s.height = height; s.samples = samples; s.layerMask = layerMask;
            s.items = items; s.geometry = geometry; s.coverage = coverage; s.origin = origin; s.mode = mode;
            s.supplied = supplied; s.stableCoverage = stableCoverage;
            return s;
        }

        boolean valid() {
            if (!supplied || !stableCoverage || width <= 0 || height <= 0 || samples <= 0) return false;
            for (double x : new double[]{cameraX, cameraY, cameraZ, eyeX, eyeY, eyeZ}) if (!Double.isFinite(x)) return false;
            for (float x : clip) if (!Float.isFinite(x)) return false;
            return true;
        }

        boolean same(State s) {
            return valid() && s.valid()
                && cameraX == s.cameraX && cameraY == s.cameraY && cameraZ == s.cameraZ
                && eyeX == s.eyeX && eyeY == s.eyeY && eyeZ == s.eyeZ && Arrays.equals(clip, s.clip)
                && width == s.width && height == s.height && samples == s.samples && layerMask == s.layerMask
                && items == s.items && geometry == s.geometry && coverage == s.coverage && origin == s.origin && mode == s.mode;
        }
    }

    private record History(State state, long frame, boolean recorded, boolean completed, boolean succeeded,
                           boolean tested, boolean visible, long identity) {}

    private static boolean suppress(History h, State now, long frame, int lag, boolean eligible,
                                    boolean queryFits, long identity, boolean enabled) {
        return enabled && h != null && (lag == 1 || lag == 2) && frame - h.frame == lag
            && h.recorded && h.completed && h.succeeded && h.tested && !h.visible
            && eligible && queryFits && h.identity == identity && h.state.same(now);
    }

    private static History hidden(State s) { return new History(s.copy(), 100, true, true, true, true, false, 7); }

    private static void historyTests() {
        State base = new State();
        History h = hidden(base);
        check(suppress(h, base, 102, 2, true, true, 7, true), "unchanged completed certificate permits refinement");
        check(suppress(h, base, 101, 1, true, true, 7, true), "lag one");
        check(!suppress(h, base, 103, 2, true, true, 7, true), "frame gap");
        check(!suppress(h, base, 102, 1, true, true, 7, true), "lag change");
        check(!suppress(null, base, 102, 2, true, true, 7, true), "initialization");
        check(!suppress(h, base, 102, 2, true, true, 7, false), "toggle off never refines");
        check(!suppress(h, base, 102, 2, false, true, 7, true), "current near/side guard wins over history");
        check(!suppress(h, base, 102, 2, true, false, 7, true), "query overflow fails open");
        check(!suppress(h, base, 102, 2, true, true, 8, true), "slot/arena reuse fails open");
        for (int i = 0; i < 6; i++) {
            History bad = new History(base, 100, i != 0, i != 1, i != 2, i != 3, i == 4, i == 5 ? 8 : 7);
            check(!suppress(bad, base, 102, 2, true, true, 7, true), "missing/failed/visible history " + i);
        }
        for (int mutation = 0; mutation < 24; mutation++) {
            State now = base.copy();
            switch (mutation) {
                case 0 -> now.cameraX = Double.MIN_VALUE;
                case 1 -> now.cameraY = 0.25;
                case 2 -> now.cameraZ = 1;
                case 3 -> now.eyeX = 0.01;
                case 4 -> now.eyeY = 0.01;
                case 5 -> now.eyeZ = 0.01;
                case 6 -> now.clip[0] = 10; // zoom
                case 7 -> now.clip[2] = 1; // depth/near-plane row only
                case 8 -> now.clip[4] = 0.001f; // shear/rotation
                case 9 -> now.width++;
                case 10 -> now.height++;
                case 11 -> now.samples++;
                case 12 -> now.items++;
                case 13 -> now.geometry++;
                case 14 -> now.coverage++;
                case 15 -> now.origin++;
                case 16 -> now.mode++;
                case 17 -> now.supplied = false;
                case 18 -> now.stableCoverage = false; // fading/animated depth coverage
                case 19 -> now.cameraX = Double.NaN;
                case 20 -> now.clip[1] = Float.POSITIVE_INFINITY;
                case 21 -> now.layerMask = 3;
                case 22 -> now.clip[12] = 0.2f; // projection translation
                case 23 -> now.clip[5] = Math.nextUp(1f); // no tolerance for slow motion
            }
            check(!suppress(h, now, 102, 2, true, true, 7, true), "invalidation " + mutation);
        }
    }

    // Discrete reversed-Z coverage oracle. Tests explicitly use identical pixel
    // grids, full sampling, and conservative rectangles; no sample reduction.
    private record Footprint(int x0, int y0, int x1, int y1, float depth) {
        boolean visible(float[][] z) {
            for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) if (depth >= z[y][x]) return true;
            return false;
        }
    }

    private static float[][] depth(float value) {
        float[][] z = new float[32][32];
        for (float[] row : z) Arrays.fill(row, value);
        return z;
    }

    private static void paint(float[][] z, Footprint f, float value) {
        for (int y = f.y0; y < f.y1; y++) Arrays.fill(z[y], f.x0, f.x1, value);
    }

    private static void staleHistoryCounterexamples() {
        Footprint child = new Footprint(10, 10, 20, 20, .4f);
        Footprint parent = new Footprint(5, 5, 25, 25, .4f);
        float[][] old = depth(.1f);
        paint(old, child, .8f);
        check(parent.visible(old) && !child.visible(old), "visible section has a hidden child");
        State before = new State();
        History h = hidden(before);
        for (String event : new String[]{"vanilla mesh replacement", "cutout animation", "LOD fade", "near-plane change", "failed box draw"}) {
            float[][] current = depth(.1f);
            check(child.visible(current), "exposed child oracle: " + event);
            // Current production trust sees only lag and a recorded draw stamp;
            // each of these can leave those values and LOD selection unchanged.
            int stamp = 102, lag = 2, oldWord = 100 << 1;
            boolean existingTrust = 100 == stamp - lag;
            boolean naiveDrop = existingTrust && oldWord == ((stamp - lag) << 1);
            check(naiveDrop, "counterexample exercises a real stale-zero predicate: " + event);
            State after = before.copy();
            History source = h;
            switch (event) {
                case "vanilla mesh replacement" -> after.geometry++;
                case "cutout animation" -> after.coverage++;
                case "LOD fade" -> after.stableCoverage = false;
                case "near-plane change" -> after.clip[2] = 2;
                case "failed box draw" -> source = new History(before, 100, true, true, false, true, false, 7);
            }
            check(!suppress(source, after, 102, lag, true, true, 7, true), "full certificate preserves exposed child: " + event);
            counterexamples++;
        }
        // The side/eye planes omit matrix row 2 entirely. Changing depth/near
        // projection can leave all twenty current LodGpu plane inputs identical.
        float[] a = before.clip.clone(), b = a.clone(); b[2] = 2; b[14] = -8;
        check(Arrays.equals(fivePlanes(a), fivePlanes(b)) && !Arrays.equals(a, b), "five-plane key misses near/depth projection");
        State noCertificate = before.copy(); noCertificate.supplied = false;
        check(!suppress(h, noCertificate, 102, 2, true, true, 7, true), "missing producer contract must fail open");
    }

    private static float[] fivePlanes(float[] m) {
        float[] out = new float[20];
        for (int c = 0; c < 4; c++) {
            float r0 = m[4 * c], r1 = m[4 * c + 1], r3 = m[4 * c + 3];
            out[c] = r3 + r0; out[4 + c] = r3 - r0;
            out[8 + c] = r3 + r1; out[12 + c] = r3 - r1; out[16 + c] = r3;
        }
        return out;
    }

    private static void hierarchyTests() {
        Random r = new Random(0x5EC710);
        int hiddenChildren = 0, rejectedParents = 0;
        State unchanged = new State();
        for (int scene = 0; scene < 2_000; scene++) {
            float[][] z = depth(.1f);
            for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++) z[y][x] = r.nextInt(8) / 8f;
            if (scene % 10 == 0) z = depth(.9f);
            Footprint parent = new Footprint(4, 4, 28, 28, .75f);
            boolean parentHidden = !parent.visible(z);
            if (parentHidden) rejectedParents++;
            for (int c = 0; c < 8; c++) {
                int x = 4 + r.nextInt(20), y = 4 + r.nextInt(20);
                Footprint child = new Footprint(x, y, x + 4, y + 4, (r.nextInt(6) + 1) / 8f);
                boolean visible = child.visible(z);
                check(!parentHidden || !visible, "parent bound contains child samples and depth");
                History ch = new History(unchanged, 100, true, true, true, true, visible, c);
                boolean refined = !parentHidden && suppress(ch, unchanged, 102, 2, true, true, c, true);
                if (refined) hiddenChildren++;
                boolean drawn = !parentHidden && !refined;
                check(!visible || drawn, "hierarchy keeps every oracle-visible cluster in a certified static scene");
                check(drawn == visible, "hierarchy equals flat full-sample oracle in this rectangular fixture");
                // Without a matching certificate even a previously hidden parent
                // must stop rejecting; checking only the child is insufficient.
                State changed = unchanged.copy(); changed.geometry++;
                History ph = new History(unchanged, 100, true, true, true, true, !parentHidden, 99);
                boolean staleParent = suppress(ph, changed, 102, 2, true, true, 99, true);
                boolean staleChild = suppress(ch, changed, 102, 2, true, true, c, true);
                check(!staleParent && !staleChild, "both hierarchy levels invalidate");
            }
        }
        check(hiddenChildren > 0 && rejectedParents > 0, "nonvacuous coarse and fine rejection");
    }

    private static void layoutTests() {
        // Existing box shader accepts exactly one item table: prefix sections to
        // cluster items, and shift each jobExtra.itemsBase by sectionCount.
        int sections = 3, clusters = 5;
        int[] mixed = new int[4 * (sections + clusters)];
        for (int i = 0; i < mixed.length; i++) mixed[i] = i * 31 + 7;
        for (int c = 0; c < clusters; c++) {
            int[] jobItem = Arrays.copyOfRange(mixed, 4 * (sections + c), 4 * (sections + c + 1));
            int absoluteBoxIndex = sections + c;
            check(Arrays.equals(jobItem, Arrays.copyOfRange(mixed, 4 * absoluteBoxIndex, 4 * absoluteBoxIndex + 4)), "mixed table index identity");
        }
        check(sections + clusters > clusters, "old cluster-only capacity is insufficient");
        check(18L * (sections + clusters) == 6L * (3L * (sections + clusters)), "box index-buffer extent");
        State s = new State(); History h = hidden(s);
        for (int capacity = 0; capacity <= sections + clusters; capacity++) {
            for (int query = 0; query < sections + clusters; query++) {
                boolean fits = query < capacity;
                check(suppress(h, s, 102, 2, true, fits, 7, true) == fits, "overflow never suppresses an unreserved query");
            }
        }
        // A child skipped while its parent was hidden must have no current test.
        History skipped = new History(s, 100, true, true, true, false, false, 7);
        check(!suppress(skipped, s, 102, 2, true, true, 7, true), "reopened section draws children without fresh tests");
    }

    public static void main(String[] args) {
        frustumTests(); historyTests(); staleHistoryCounterexamples(); hierarchyTests(); layoutTests();
        System.out.printf("LodRefineTest: %,d CPU checks passed; %d stale-history counterexamples reproduced.%n", checks, counterexamples);
        System.out.println("Specification/oracle only: no production prototype or native/GPU equivalence is claimed.");
    }
}
