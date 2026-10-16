import bench.Abab;
import bench.AbabStats;
import java.lang.reflect.Field;
import java.util.Locale;
import java.util.SplittableRandom;

/**
 * Headless test of the in-session A/B statistics ({@code bench.AbabStats}, {@code bench.Abab} toggle parsing;
 * docs/metal/CONTRACT.md 16.AB). No game, no GPU. Usage: tools/abab-stats-test.sh.
 *
 * <ul>
 *   <li>distributions: Student t quantiles and CDF, exact sign test, lnGamma against known values;</li>
 *   <li>cell summaries: nearest-rank median / p99 / mean per bin, discarded frames and unknown GPU values excluded,
 *       cells under minCell missing; paired log ratios in ABBA order (always ln(B / A));</li>
 *   <li>calibration by simulation (pairs x 24 bins of log ratios, pair effect u ~ N(0, sp), cell noise ~ N(0, sc)): the
 *       decision interval covers the true effect about 95% of the time with and without a pair effect; the cell-level
 *       interval undercovers when the pair effect is real (why it is not the decision interval); a 2% effect is found;
 *       the sequential rule keeps the false-positive rate at most 5% also under a linear drift (looks only at balanced
 *       pair counts: even ones with one arm per block), its repeated intervals cover after a stop, and the expected
 *       number of pairs per effect size is printed for both designs; Holm over the three primary metrics keeps the
 *       chance of any call in an A/A run at most 5%;</li>
 *   <li>pair tails (whole-pair 1 % / 0.1 % low per arm), look schedule, Holm adjustment, p-values;</li>
 *   <li>toggle specs: an int field set by reflection and read back, booleans, rejection of static final and instance
 *       fields, bad values, missing classes, ABBA / ABAB arm order.</li>
 * </ul>
 */
public class AbabStatsTest {
    private static int checks;
    private static int failures;

    private static void check(final boolean ok, final String what) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("  FAIL: " + what);
        }
    }

    private static boolean near(final double a, final double b, final double tol) {
        return Math.abs(a - b) <= tol;
    }

    public static void main(final String[] args) throws Exception {
        distributions();
        summaries();
        pairTailsAndSchedule();
        calibration();
        sequential();
        toggles();
        System.out.println(String.format(Locale.ROOT, "AbabStatsTest: %d checks, %d failures", checks, failures));
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void distributions() {
        System.out.println("distributions");
        double[][] t = {{0.975, 1, 12.7062047}, {0.975, 2, 4.30265273}, {0.975, 5, 2.57058184}, {0.975, 30, 2.04227246},
            {0.995, 3, 5.84090931}, {0.95, 10, 1.81246112}, {0.999, 4, 7.17318222}};
        for (double[] row : t) {
            double q = AbabStats.tQuantile(row[0], row[1]);
            check(near(q, row[2], 2e-6 * row[2]), "tQuantile(" + row[0] + ", " + row[1] + ") = " + q + ", want " + row[2]);
        }
        check(near(AbabStats.tCdf(0.0, 7), 0.5, 1e-12), "tCdf(0) = 0.5");
        check(near(AbabStats.tCdf(-2.57058184, 5), 0.025, 1e-7), "tCdf(-2.5706, 5) = 0.025");
        check(near(AbabStats.lnGamma(5.0), Math.log(24.0), 1e-12), "lnGamma(5)");
        check(near(AbabStats.lnGamma(0.5), Math.log(Math.sqrt(Math.PI)), 1e-12), "lnGamma(0.5)");
        check(near(AbabStats.signTestP(6, 0), 0.03125, 1e-12), "sign 6/0");
        check(near(AbabStats.signTestP(0, 5), 0.0625, 1e-12), "sign 0/5");
        check(near(AbabStats.signTestP(10, 10), 1.0, 1e-12), "sign 10/10");
        check(near(AbabStats.signTestP(15, 5), 0.041389465, 1e-8), "sign 15/5 = " + AbabStats.signTestP(15, 5));
        check(near(AbabStats.signTestP(0, 0), 1.0, 0), "sign 0/0");
        // Large n stays finite (cells: up to thousands).
        double p = AbabStats.signTestP(700, 740);
        check(p > 0.2 && p < 0.4, "sign 700/740 = " + p);
    }

    private static void summaries() {
        System.out.println("summaries");
        int frames = 101 + 50 + 30;
        int[] dt = new int[frames];
        int[] gpu = new int[frames];
        byte[] bin = new byte[frames];
        boolean[] keep = new boolean[frames];
        int k = 0;
        // Bin 0: 101 kept frames 1..101 (reversed), GPU 10x; plus 30 discarded huge frames in bin 0.
        for (int i = 0; i < 30; i++, k++) {
            dt[k] = 1_000_000;
            gpu[k] = 1_000_000;
            bin[k] = 0;
            keep[k] = false;
        }
        for (int i = 101; i >= 1; i--, k++) {
            dt[k] = i;
            gpu[k] = i % 2 == 0 ? 10 * i : -1;   // 50 known GPU values (2..100 even): median of 20..1000 step 20
            bin[k] = 0;
            keep[k] = true;
        }
        // Bin 1: 50 kept frames, all 7; bin 2: none.
        for (int i = 0; i < 50; i++, k++) {
            dt[k] = 7;
            gpu[k] = 3;
            bin[k] = 1;
            keep[k] = true;
        }
        AbabStats.Block b = AbabStats.summarize(1, 3, 20, dt, gpu, bin, keep, 0, frames);
        check(b.arm[0] == 1 && b.arm[2] == 1, "arm");
        check(b.n[0] == 101 && b.n[1] == 50 && b.n[2] == 0, "counts " + b.n[0] + "/" + b.n[1] + "/" + b.n[2]);
        check(b.gpuN[0] == 50, "gpu count " + b.gpuN[0]);
        check(b.m[AbabStats.MEDIAN][0] == 51.0, "median " + b.m[AbabStats.MEDIAN][0]);
        check(b.m[AbabStats.P99][0] == 100.0, "p99 " + b.m[AbabStats.P99][0]);
        check(b.m[AbabStats.MEAN][0] == 51.0, "mean " + b.m[AbabStats.MEAN][0]);
        // 50 GPU values 20, 40, ..., 1000: nearest rank round(0.5 * 49) = 25 (0-based; Math.round(24.5) = 25) -> 520.
        check(b.m[AbabStats.GPU_MEDIAN][0] == 520.0, "gpu median " + b.m[AbabStats.GPU_MEDIAN][0]);
        check(b.m[AbabStats.GPU_P99][0] == 1000.0, "gpu p99 " + b.m[AbabStats.GPU_P99][0]);
        check(b.m[AbabStats.MEDIAN][1] == 7.0 && b.m[AbabStats.GPU_P99][1] == 3.0, "bin 1");
        check(Double.isNaN(b.m[AbabStats.MEDIAN][2]), "empty bin is missing");
        AbabStats.Block small = AbabStats.summarize(0, 3, 60, dt, gpu, bin, keep, 0, frames);
        check(!Double.isNaN(small.m[AbabStats.MEDIAN][0]) && Double.isNaN(small.m[AbabStats.MEDIAN][1])
            && Double.isNaN(small.m[AbabStats.GPU_MEDIAN][0]), "minCell 60: bin 0 kept, bin 1 and bin 0's GPU missing");

        // ABBA: blocks A B B A; B is 10% slower in pair 0 and 5% faster in pair 1. Log ratios are always ln(B / A).
        AbabStats.Block[] blocks = {
            cellBlock(0, 100.0), cellBlock(1, 110.0), cellBlock(1, 95.0), cellBlock(0, 100.0),
        };
        double[][] lr = AbabStats.logRatios(blocks, 2, AbabStats.MEDIAN);
        check(near(lr[0][0], Math.log(1.10), 1e-12) && near(lr[1][0], Math.log(0.95), 1e-12), "log ratios ln(B/A) in ABBA order");
        AbabStats.Block[] same = {cellBlock(0, 100.0), cellBlock(0, 110.0)};
        check(Double.isNaN(AbabStats.logRatios(same, 1, AbabStats.MEDIAN)[0][0]), "a pair of one arm has no ratio");
        // Segments: arms alternate per bin inside a block and are opposite in the pair's other block. A = 100, B = 110.
        AbabStats.Block[] seg = {segBlock(new int[]{0, 1, 0, 1}), segBlock(new int[]{1, 0, 1, 0})};
        double[][] slr = AbabStats.logRatios(seg, 1, AbabStats.MEDIAN);
        boolean all = true;
        for (double v : slr[0]) {
            all &= near(v, Math.log(1.10), 1e-12);
        }
        check(all, "segment cells pair by arm per bin");
    }

    /** A block with 4 bins of 40 frames whose arms are {@code arms}: 100 us frames in A cells, 110 us in B cells. */
    private static AbabStats.Block segBlock(final int[] arms) {
        int frames = 160;
        int[] dt = new int[frames];
        int[] gpu = new int[frames];
        byte[] bin = new byte[frames];
        boolean[] keep = new boolean[frames];
        for (int k = 0; k < frames; k++) {
            bin[k] = (byte) (k / 40);
            dt[k] = arms[bin[k]] == 0 ? 100_000 : 110_000;
            gpu[k] = -1;
            keep[k] = true;
        }
        return AbabStats.summarize(arms, 4, 20, dt, gpu, bin, keep, 0, frames);
    }

    /** A block with 4 bins of 40 frames, every frame {@code value}. */
    private static AbabStats.Block cellBlock(final int arm, final double value) {
        int frames = 160;
        int[] dt = new int[frames];
        int[] gpu = new int[frames];
        byte[] bin = new byte[frames];
        boolean[] keep = new boolean[frames];
        for (int k = 0; k < frames; k++) {
            dt[k] = (int) Math.round(value * 1000);
            gpu[k] = -1;
            bin[k] = (byte) (k / 40);
            keep[k] = true;
        }
        return AbabStats.summarize(arm, 4, 20, dt, gpu, bin, keep, 0, frames);
    }

    private static double[][] simulate(final SplittableRandom r, final int pairs, final int bins, final double effect, final double sp,
        final double sc) {
        double[][] lr = new double[pairs][bins];
        for (int p = 0; p < pairs; p++) {
            double u = sp * gauss(r);
            for (int b = 0; b < bins; b++) {
                lr[p][b] = effect + u + sc * gauss(r);
            }
        }
        return lr;
    }

    private static double gauss(final SplittableRandom r) {
        double u1 = r.nextDouble();
        double u2 = r.nextDouble();
        return Math.sqrt(-2.0 * Math.log(Math.max(1e-300, u1))) * Math.cos(2.0 * Math.PI * u2);
    }

    private static void calibration() {
        System.out.println("calibration (simulated A/A and A/B, 6 pairs x 24 bins)");
        SplittableRandom r = new SplittableRandom(20261005L);
        int sims = 2000;
        double[][] settings = {{0.0, 0.01}, {0.004, 0.01}, {0.01, 0.003}};
        for (double[] s : settings) {
            int cover = 0, cellCover = 0, pctCover = 0;
            for (int i = 0; i < sims; i++) {
                AbabStats.Effect e = AbabStats.effect(simulate(r, 6, 24, 0.0, s[0], s[1]), AbabStats.MEDIAN, 0.05, 1000, i);
                if (e.lo <= 0.0 && e.hi >= 0.0) {
                    cover++;
                }
                if (e.cellLo <= 0.0 && e.cellHi >= 0.0) {
                    cellCover++;
                }
                if (e.pctLo <= 0.0 && e.pctHi >= 0.0) {
                    pctCover++;
                }
            }
            double c = (double) cover / sims;
            System.out.println(String.format(Locale.ROOT, "  sp %.3f sc %.3f: decision CI covers 0 in %.3f, percentile %.3f, cell-level %.3f",
                s[0], s[1], c, (double) pctCover / sims, (double) cellCover / sims));
            check(c >= 0.93 && c <= 0.97, "decision CI coverage " + c + " (sp " + s[0] + ")");
            if (s[0] >= 0.01) {
                check((double) cellCover / sims < 0.85, "cell-level CI undercovers with a real pair effect");
            }
        }
        // Power: a 2% effect with a pair effect of 0.3% and cell noise of 2%.
        int found = 0;
        double bias = 0.0;
        for (int i = 0; i < 500; i++) {
            AbabStats.Effect e = AbabStats.effect(simulate(r, 6, 24, Math.log(1.02), 0.003, 0.02), AbabStats.MEDIAN, 0.05, 1000, i);
            if (e.lo > 0.0) {
                found++;
            }
            bias += e.est - Math.log(1.02);
        }
        check(found >= 495, "2% effect found in " + found + "/500");
        check(Math.abs(bias / 500) < 0.001, "unbiased estimate (" + bias / 500 + ")");
    }

    private static void pairTailsAndSchedule() {
        System.out.println("pair tails, look schedule, Holm, p");
        // Two blocks of 4 bins x 50 frames, one arm per block (x = A, y = B): A frames 1..200 us, B frames 2x.
        int frames = 400;
        int[] dt = new int[frames];
        boolean[] keep = new boolean[frames];
        byte[] bin = new byte[frames];
        for (int k = 0; k < 200; k++) {
            dt[k] = (k + 1) * 1000;
            dt[200 + k] = (k + 1) * 2000;
            bin[k] = (byte) (k / 50);
            bin[200 + k] = (byte) (k / 50);
            keep[k] = keep[200 + k] = true;
        }
        keep[199] = false;   // A's largest frame is not kept
        int[] a = {0, 0, 0, 0};
        int[] b = {1, 1, 1, 1};
        double[][] t = AbabStats.pairTails(dt, keep, bin, a, b, 0, 200, 200, 400, 20);
        // A: 199 kept frames 1..199 us: p99 = rank round(0.99 * 198) = 196 -> 197 us; B: 200 frames 2..400 us: rank 197 -> 396 us.
        check(t[0][0] == 197_000.0 && t[1][0] == 396_000.0, "pair p99 " + t[0][0] + " / " + t[1][0]);
        check(t[0][1] == 199_000.0 && t[1][1] == 400_000.0, "pair p99.9 " + t[0][1] + " / " + t[1][1]);
        // Segments: per-bin arms, frames of both blocks pooled by arm.
        int[] ax = {0, 1, 0, 1};
        int[] ay = {1, 0, 1, 0};
        double[][] ts = AbabStats.pairTails(dt, keep, bin, ax, ay, 0, 200, 200, 400, 20);
        check(ts[0][0] > 0 && ts[1][0] > 0 && ts[0][0] != t[0][0], "segment pair tails pool by cell arm");
        double[][] none = AbabStats.pairTails(dt, keep, bin, a, b, 0, 10, 200, 210, 20);
        check(Double.isNaN(none[0][0]) && Double.isNaN(none[1][1]), "too few frames: NaN");
        double[][][] tails = {t, {{100.0, 200.0}, {110.0, 180.0}}, null};
        double[][] lr = AbabStats.pairLogRatios(tails, 3, AbabStats.PAIR_P99);
        check(lr.length == 3 && lr[0].length == 1 && near(lr[0][0], Math.log(396.0 / 197.0), 1e-12)
            && near(lr[1][0], Math.log(1.1), 1e-12) && Double.isNaN(lr[2][0]), "pair log ratios");
        check(near(AbabStats.pairLogRatios(tails, 2, AbabStats.PAIR_P999)[1][0], Math.log(0.9), 1e-12), "pair p99.9 log ratio");
        boolean threw = false;
        try {
            AbabStats.logRatios(new AbabStats.Block[]{cellBlock(0, 1), cellBlock(1, 1)}, 1, AbabStats.PAIR_P99);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "logRatios rejects pair metrics");
        // Look schedule.
        check(java.util.Arrays.equals(AbabStats.lookPairs(3, 6, 1, true), new int[]{4, 6}), "S=1 ABBA: looks after 4 and 6 pairs");
        check(java.util.Arrays.equals(AbabStats.lookPairs(3, 6, 3, true), new int[]{4, 6}), "S=3 ABBA: even pair counts");
        check(java.util.Arrays.equals(AbabStats.lookPairs(3, 6, 8, true), new int[]{3, 4, 5, 6}), "S=8: every pair");
        check(java.util.Arrays.equals(AbabStats.lookPairs(3, 6, 24, false), new int[]{3, 4, 5, 6}), "S=24 abab order: every pair");
        check(AbabStats.lookPairs(3, 6, 1, false).length == 0, "S=1 abab order: never balanced, no looks");
        check(java.util.Arrays.equals(AbabStats.lookPairs(1, 4, 1, true), new int[]{2, 4}), "minPairs below 2 starts at 2");
        check(near(AbabStats.lookLevel(0.05, 2), 0.025, 1e-15) && near(AbabStats.lookLevel(0.05, 0), 0.05, 1e-15), "look level");
        // Holm: p = {0.01, 0.04, 0.03, NaN} -> {0.03, 0.06, 0.06, NaN}.
        double[] h = AbabStats.holm(new double[]{0.01, 0.04, 0.03, Double.NaN});
        check(near(h[0], 0.03, 1e-15) && near(h[1], 0.06, 1e-15) && near(h[2], 0.06, 1e-15) && Double.isNaN(h[3]),
            "holm " + java.util.Arrays.toString(h));
        check(near(AbabStats.holm(new double[]{0.5, 0.6})[0], 1.0, 1e-15), "holm caps at 1");
        // p-value: pair means {1, 2, 3} (one cell each): t = 2 / (1 / sqrt 3) = 3.4641, 2 df.
        AbabStats.Effect e = AbabStats.effect(new double[][]{{1.0}, {2.0}, {3.0}}, AbabStats.MEDIAN, 0.05, 20000, 3L);
        double want = 2.0 * AbabStats.tCdf(-e.est / e.se, 2);
        check(near(e.p, want, 1e-12) && e.p > 0.04 && e.p < 0.12, "p " + e.p + " (bootstrap SE " + e.se + ")");
        double[] c90 = e.at(0.10);
        check(c90[0] > e.lo && c90[1] < e.hi, "interval at another level is narrower at 90%");
    }

    /**
     * Noise model of one run: per pair, ln(B / A) per bin = effect + pair effect u (SD sp) + cell noise (SD sc) + the
     * drift d per block times the bin's order (+1 when B ran later in that bin, -1 when A did), for the arm layout of
     * {@code segments} segments in ABBA order.
     */
    private static double[][] simulateRun(final SplittableRandom r, final int pairs, final int segments, final double effect,
        final double sp, final double sc, final double drift) {
        double[][] lr = new double[pairs][24];
        for (int p = 0; p < pairs; p++) {
            double u = sp * gauss(r);
            for (int b = 0; b < 24; b++) {
                int s = b * segments / 24;
                double order = Abab.armOf(2 * p, s) == 0 ? 1.0 : -1.0;
                lr[p][b] = effect + u + drift * order + sc * gauss(r);
            }
        }
        return lr;
    }

    /**
     * Sequential rule as Abab runs it with the default stop metric (median2, primary): repeated intervals at alpha / looks,
     * "effect" when the Holm-adjusted p x looks is below alpha. The other primary metric (the 1 % low) is taken as
     * uninformative, so Holm doubles median2's p (the worst case). Returns {pairs at the stop or the end, effect, decision}.
     */
    private static Object[] runStop(final double[][] all, final int segments, final int minPairs, final double margin, final int seed) {
        int[] looks = AbabStats.lookPairs(minPairs, all.length, segments, true);
        double alpha = AbabStats.lookLevel(0.05, looks.length);
        Object[] last = null;
        for (int pairs : looks) {
            double[][] part = java.util.Arrays.copyOf(all, pairs);
            AbabStats.Effect e = AbabStats.effect(part, AbabStats.MEDIAN2, alpha, 400, seed * 7L + pairs);
            String d = AbabStats.decide(e, Math.min(1.0, 2.0 * e.p * looks.length), 0.05, margin);
            last = new Object[]{pairs, e, d};
            if (!"undecided".equals(d)) {
                return last;
            }
        }
        return last;
    }

    private static void sequential() {
        System.out.println("sequential rule (6 pairs max, minPairs 3, margin 0.5%, stop on one metric)");
        SplittableRandom r = new SplittableRandom(16_05L);
        double margin = Math.log1p(0.5 / 100.0);
        int sims = 1500;
        // Pair-mean SDs of the two-frame median pooled over the spin runs of each design (results/ABAB.md): 1.80 % with one
        // arm per block (6 runs), 0.91 % with segments 8 (2), 0.85 % with segments 24 (7). Cell noise 2 % (24 cells: 0.41 %
        // on the pair mean), the rest is the pair effect.
        double[][] designs = {{1, 0.0175}, {8, 0.0081}, {24, 0.00745}};
        for (double[] dz : designs) {
            int seg = (int) dz[0];
            // Under the null with a drift of 0.86 % per block (abab-aa-1): false 'effect' stops at most 5%. The old rule
            // (looks after 3, 4, 5, 6 pairs) is replayed for comparison with one arm per block.
            int falseStops = 0, oldFalse = 0;
            for (int i = 0; i < sims; i++) {
                double[][] all = simulateRun(r, 6, seg, 0.0, dz[1], 0.02, 0.0086);
                if ("effect".equals(runStop(all, seg, 3, margin, i)[2])) {
                    falseStops++;
                }
                for (int pairs = 3; pairs <= 6; pairs++) {
                    AbabStats.Effect e = AbabStats.effect(java.util.Arrays.copyOf(all, pairs), AbabStats.MEDIAN2, 0.05 / 4, 400, i * 11L + pairs);
                    String d = AbabStats.decision(e, margin);
                    if ("effect".equals(d)) {
                        oldFalse++;
                        break;
                    }
                    if ("within-margin".equals(d)) {
                        break;
                    }
                }
            }
            double fpr = (double) falseStops / sims;
            System.out.println(String.format(Locale.ROOT, "  segments %2d, drift 0.86%%/block, no effect: false stops %.4f (looks %s; "
                + "every pair from 3: %.4f)", seg, fpr, java.util.Arrays.toString(AbabStats.lookPairs(3, 6, seg, true)), (double) oldFalse / sims));
            check(fpr <= 0.05, "segments " + seg + ": false-stop rate under drift " + fpr);
            // Expected pairs and coverage of the reported (repeated) interval by effect size, minPairs 3 and 4.
            for (int minPairs = 3; minPairs <= 4; minPairs++) {
            StringBuilder sb = new StringBuilder();
            for (double pct : new double[]{0.0, 1.0, 2.0, 3.0, 5.0}) {
                double eff = Math.log1p(pct / 100.0);
                double pairsSum = 0.0;
                int cover = 0, cover95 = 0, stopped = 0;
                for (int i = 0; i < sims; i++) {
                    Object[] res = runStop(simulateRun(r, 6, seg, eff, dz[1], 0.02, 0.0), seg, minPairs, margin, i);
                    int pairs = (Integer) res[0];
                    AbabStats.Effect e = (AbabStats.Effect) res[1];
                    pairsSum += pairs;
                    if (pairs < 6) {
                        stopped++;
                    }
                    if (e.lo <= eff && eff <= e.hi) {
                        cover++;
                    }
                    double[] c = e.at(0.05);
                    if (c[0] <= eff && eff <= c[1]) {
                        cover95++;
                    }
                }
                sb.append(String.format(Locale.ROOT, " %.0f%%: %.2f pairs (stopped early %.2f, repeated CI covers %.3f, fixed 95%% CI %.3f);",
                    pct, pairsSum / sims, (double) stopped / sims, (double) cover / sims, (double) cover95 / sims));
                check((double) cover / sims >= 0.94, "segments " + seg + ", effect " + pct + "%: repeated CI coverage " + (double) cover / sims);
            }
            System.out.println("  segments " + seg + ", minPairs " + minPairs + ", looks " + java.util.Arrays.toString(AbabStats.lookPairs(minPairs, 6, seg, true))
                + ", expected pairs by effect:" + sb);
            }
        }
        // Family-wise error in A/A runs: six correlated metrics (shared pair effect) at 95 % each, as the first report bolded
        // them, against Holm over the two primary ones (metric 0: a cell metric like median2, metric 1: a pair metric
        // like the 1 % low, one value per pair).
        int anyRaw = 0, anyHolm = 0;
        for (int i = 0; i < sims; i++) {
            double[] u = new double[6];
            for (int p = 0; p < 6; p++) {
                u[p] = 0.005 * gauss(r);
            }
            double[] ps = new double[2];
            boolean raw = false;
            for (int m = 0; m < 6; m++) {
                boolean pairMetric = m == 1;
                double[][] lr = new double[6][pairMetric ? 1 : 24];
                double share = m < 2 ? 1.0 : 0.5;
                for (int p = 0; p < 6; p++) {
                    for (int b = 0; b < lr[p].length; b++) {
                        lr[p][b] = pairMetric ? 0.3 * u[p] + 0.04 * gauss(r) : share * u[p] + 0.01 * gauss(r) + (m >= 2 ? 0.003 * gauss(r) : 0.0);
                    }
                }
                AbabStats.Effect e = AbabStats.effect(lr, AbabStats.MEDIAN2, 0.05, 400, i * 7L + m);
                if (m < 2) {
                    ps[m] = e.p;
                }
                raw |= e.excludesZero();
            }
            double[] h = AbabStats.holm(ps);
            if (raw) {
                anyRaw++;
            }
            if (h[0] < 0.05 || h[1] < 0.05) {
                anyHolm++;
            }
        }
        System.out.println(String.format(Locale.ROOT, "  A/A: any of 6 metrics' 95%% intervals excludes 0 in %.3f; any Holm call on the 2 primary metrics in %.3f",
            (double) anyRaw / sims, (double) anyHolm / sims));
        check((double) anyHolm / sims <= 0.06, "Holm family-wise error " + (double) anyHolm / sims);
    }

    public static int intToggle;
    public static volatile boolean boolToggle;
    public static final boolean FINAL_TOGGLE = true;
    public int instanceToggle;

    private static void toggles() throws Exception {
        System.out.println("toggles");
        Abab.Toggle[] t = Abab.parseToggles("bench.Abab#aaNoop=0|1");
        check(t.length == 1 && t[0].values()[0].equals(0) && t[0].values()[1].equals(1), "aaNoop parsed as int 0|1");
        Field f = t[0].field();
        f.set(null, t[0].values()[1]);
        check(Abab.aaNoop == 1, "aaNoop set to 1 by reflection");
        f.set(null, t[0].values()[0]);
        check(Abab.aaNoop == 0, "aaNoop set back to 0");
        Abab.Toggle[] two = Abab.parseToggles(" bench.Abab#aaNoop=3|4 ; bench.Abab#aaNoop=5|6 ");
        check(two.length == 2 && two[1].values()[1].equals(6), "two specs, ';' separator, spaces");
        check(Abab.parseValue(boolean.class, "on", "x").equals(Boolean.TRUE) && Abab.parseValue(boolean.class, "0", "x").equals(Boolean.FALSE),
            "boolean values");
        check(Abab.parseValue(double.class, "0.5", "x").equals(0.5) && Abab.parseValue(String.class, "a b", "x").equals("a b"),
            "double and String values");
        check(Abab.parseValue(java.util.concurrent.TimeUnit.class, "SECONDS", "x") == java.util.concurrent.TimeUnit.SECONDS, "enum value");
        expectFailure("bench.Abab#ENABLED=true|false", "static final");
        expectFailure("bench.AbabStats$Effect#est=1|2", "not static");
        expectFailure("bench.Abab#aaNoop=x|1", "bad value");
        expectFailure("bench.NoSuchClass#x=1|2", "not found");
        expectFailure("bench.Abab#nope=1|2", "no field");
        expectFailure("bench.Abab#aaNoop=1", "want fqcn#field");
        expectFailure("", "no toggles");
        // Arm order: ABBA by default.
        StringBuilder order = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            order.append(Abab.armOf(i) == 0 ? 'A' : 'B');
        }
        check(order.toString().equals("ABBAABBA"), "ABBA order: " + order);
        StringBuilder segs = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            for (int sgm = 0; sgm < 4; sgm++) {
                segs.append(Abab.armOf(i, sgm) == 0 ? 'A' : 'B');
            }
            segs.append(' ');
        }
        check(segs.toString().equals("ABAB BABA BABA ABAB "), "segment arms: " + segs);
    }

    private static void expectFailure(final String spec, final String fragment) {
        try {
            Abab.parseToggles(spec);
            check(false, "'" + spec + "' accepted");
        } catch (IllegalArgumentException e) {
            check(e.getMessage().contains(fragment), "'" + spec + "' message: " + e.getMessage());
        }
    }
}
