package bench;

import java.util.Arrays;
import java.util.Locale;
import java.util.SplittableRandom;

/**
 * Statistics of the in-session A/B mode ({@code mode=abab}, {@link Abab}). Pure Java (no game classes), so
 * tools/AbabStatsTest.java checks it headlessly on synthetic data, and tools/abab.py re-implements it independently.
 *
 * <p><b>Design.</b> A run is a sequence of blocks (one full camera spin each), arms in ABBA order; blocks 2i and 2i+1
 * form pair i (one A, one B, adjacent in time; the order inside a pair alternates, so a linear drift cancels over two
 * pairs). Each block's kept frames are split into {@code bins} cells by their start time in the block, which for a spin
 * block is the 15-degree yaw sector (24 bins), so cell (pair i, bin b) compares the same view direction 10 s apart.
 * With {@code abab.segments} S > 1 the arm also alternates S times inside every block (block 2i+1 runs the opposite
 * arm in each segment), so each bin still has one A and one B cell per pair, while A and B frames are interleaved
 * every 10/S s: the pair's mean log ratio (the sum over bins of ln B - ln A) then compares neighbouring segments and
 * the slow drift of the machine (GPU clock, heat: about 1 % per 10 s, wandering on a 10-30 s scale) cancels.
 *
 * <p><b>Per cell</b> (frames of one block in one bin): median, p99 and mean frame interval, median and p99 GPU busy
 * time, and "median2", the median of consecutive two-frame means ({@code pct}: nearest rank, round(p * (n - 1)), like
 * Bench.pct). With two frames in flight the intervals alternate short / long (lag-2 autocorrelation 0.87 in game): the
 * plain median then sits in the gap between the two modes and jumps by several percent between equal blocks, while
 * median2 (and the mean) do not. Cells with fewer than {@code minCell} frames are
 * missing (NaN). <b>Paired per-bin differences</b>: d[i][b] = ln(m_B / m_A) of a metric m.
 *
 * <p><b>Effect</b> = mean of d over all valid cells (each cell one view direction of one pair, equal weight), reported
 * as exp(effect) - 1 (B relative to A; for frame times negative = B faster). The per-cell p99 ("sector p99") is a
 * view-matched tail: every 15-degree sector counts the same. It is NOT the classic 1 % low, which is the p99 of the whole
 * run and is dominated by the heavy sectors and by sporadic bursts of slow frames (chunk work, GC: 20-30 slow frames in
 * one sector of a 10 s block). <b>Pair metrics</b> {@code pairP99} / {@code pairP999} are the classic definition: per
 * pair, the p99 / p99.9 of all kept frames of each arm (both view sweeps, 10 s per arm), one log ratio per pair
 * ({@link #pairTails}). They are much noisier (pair SD 4-6 % against 1-2 % for the sector p99 in A/A runs). <b>Uncertainty</b>: the cells of one pair
 * share whatever happened during those 20 s (background work, a GC cycle, an autosave), so cells are not independent;
 * the resampling unit is the pair (cluster bootstrap). The decision interval is the bootstrap standard error with a
 * Student t critical value on pairs - 1 degrees of freedom ({@code est +- t * se * sqrt(P / (P - 1))}, the bias-corrected
 * cluster SE): with few pairs a percentile interval cannot cover (its range is bounded by the pair means), the t
 * value widens it honestly. The percentile interval and a cell-level interval (cells treated as independent, valid
 * only when the pair effect is negligible: see {@code icc}) are reported next to it, with two sign tests (cells, pairs).
 *
 * <p><b>Sequential stopping</b> ({@link #lookPairs}, {@link #lookLevel}): looks only after pair counts at which the
 * design is balanced against a linear drift ({@link #balanced}: with one arm per block, ABBA needs an even pair count;
 * with an even number of segments every pair is balanced on its own), each at level alpha / looks (Bonferroni over the
 * planned looks, so the overall false-positive rate stays at most alpha); a metric is decided when its interval
 * excludes 0 or its half-width is at most the margin. With the stop on, the reported intervals are repeated confidence
 * intervals at alpha / looks, valid at whichever look the run ended ({@code level}); {@code ci95} is the fixed-sample
 * interval, for reference only.
 *
 * <p><b>Multiple metrics.</b> Each interval is per metric. The decision family is {@link #PRIMARY}, the two metrics of
 * docs/GOAL.md's stage bars: the two-frame median (for the median) and the whole-pair p99 (the 1 % low). Holm over their
 * p-values ({@link #holm}; with the stop on, p x looks) keeps the chance of any false call in an A/A run at most alpha.
 * The other metrics (avg FPS, plain median, sector p99, 0.1 % low, GPU busy) are descriptive: an interval of theirs
 * that excludes 0 is not a call (six metrics at 95 % each gave at least one "significant" metric in 3 of 7 A/A runs).
 */
public final class AbabStats {
    /** Cell metrics (0..NC-1, one value per block and bin) and pair metrics (NC..NM-1, one value per pair and arm). */
    public static final int MEDIAN = 0, P99 = 1, MEAN = 2, GPU_MEDIAN = 3, GPU_P99 = 4, MEDIAN2 = 5, NC = 6, PAIR_P99 = 6,
        PAIR_P999 = 7, NM = 8;
    public static final String[] NAMES = {"median", "p99", "mean", "gpuMedian", "gpuP99", "median2", "pairP99", "pairP999"};
    /** Decision family: two-frame median and 1 % low (whole-pair p99), the GOAL's stage-bar metrics. Holm over these. */
    public static final int[] PRIMARY = {MEDIAN2, PAIR_P99};
    /** Percentiles of the pair metrics, in {@link #pairTails}'s order. */
    public static final double[] PAIR_PS = {0.99, 0.999};

    public static boolean isPrimary(final int metric) {
        for (int m : PRIMARY) {
            if (m == metric) {
                return true;
            }
        }
        return false;
    }

    private AbabStats() {
    }

    /**
     * One block: per bin the arm of its cell (0 = A, 1 = B; one arm per block unless abab.segments > 1), the frame count,
     * GPU-known count and metric values in ns (NaN: missing).
     */
    public static final class Block {
        public final int[] arm;
        public final int[] n;
        public final int[] gpuN;
        public final double[][] m;

        Block(final int[] arm, final int bins) {
            this.arm = arm.clone();
            this.n = new int[bins];
            this.gpuN = new int[bins];
            this.m = new double[NC][bins];
            for (double[] row : this.m) {
                Arrays.fill(row, Double.NaN);
            }
        }
    }

    /** Nearest-rank percentile of the first {@code len} values of a sorted array (Bench.pct's rule). */
    public static double pct(final long[] sorted, final int len, final double p) {
        if (len <= 0) {
            return Double.NaN;
        }
        return sorted[(int) Math.min(len - 1, Math.round(p * (len - 1)))];
    }

    /**
     * Cell summaries of one block from its kept frames {@code k} in [from, to) with {@code keep[k]}: frame interval
     * {@code dt[k]} (ns), GPU busy {@code gpu[k]} (ns, negative = unknown), bin {@code bin[k]}.
     */
    public static Block summarize(final int arm, final int bins, final int minCell, final int[] dt, final int[] gpu, final byte[] bin,
        final boolean[] keep, final int from, final int to) {
        int[] arms = new int[bins];
        Arrays.fill(arms, arm);
        return summarize(arms, bins, minCell, dt, gpu, bin, keep, from, to);
    }

    /** As above with one arm per bin ({@code cellArm[b]}: the arm the block ran while it was in bin b). */
    public static Block summarize(final int[] cellArm, final int bins, final int minCell, final int[] dt, final int[] gpu, final byte[] bin,
        final boolean[] keep, final int from, final int to) {
        Block block = new Block(cellArm, bins);
        int[] count = new int[bins];
        int[] gcount = new int[bins];
        for (int k = from; k < to; k++) {
            if (keep[k]) {
                count[bin[k]]++;
                if (gpu[k] >= 0) {
                    gcount[bin[k]]++;
                }
            }
        }
        long[][] d = new long[bins][];
        long[][] g = new long[bins][];
        long[][] d2 = new long[bins][];
        for (int b = 0; b < bins; b++) {
            d[b] = new long[count[b]];
            g[b] = new long[gcount[b]];
            d2[b] = new long[count[b]];
        }
        int[] fill = new int[bins];
        int[] gfill = new int[bins];
        int[] fill2 = new int[bins];
        double[] sum = new double[bins];
        for (int k = from; k < to; k++) {
            if (keep[k]) {
                int b = bin[k];
                d[b][fill[b]++] = dt[k];
                sum[b] += dt[k];
                if (gpu[k] >= 0) {
                    g[b][gfill[b]++] = gpu[k];
                }
                if (k > from && keep[k - 1] && bin[k - 1] == bin[k]) {
                    // Two-frame mean (x2, kept integral): frame intervals alternate short / long when two frames are in
                    // flight, and the plain median then falls into the gap between the two modes.
                    d2[b][fill2[b]++] = (long) dt[k - 1] + dt[k];
                }
            }
        }
        for (int b = 0; b < bins; b++) {
            block.n[b] = count[b];
            block.gpuN[b] = gcount[b];
            if (count[b] >= minCell) {
                Arrays.sort(d[b]);
                block.m[MEDIAN][b] = pct(d[b], count[b], 0.5);
                block.m[P99][b] = pct(d[b], count[b], 0.99);
                block.m[MEAN][b] = sum[b] / count[b];
            }
            if (fill2[b] >= minCell) {
                Arrays.sort(d2[b], 0, fill2[b]);
                block.m[MEDIAN2][b] = pct(d2[b], fill2[b], 0.5) / 2.0;
            }
            if (gcount[b] >= minCell) {
                Arrays.sort(g[b]);
                block.m[GPU_MEDIAN][b] = pct(g[b], gcount[b], 0.5);
                block.m[GPU_P99][b] = pct(g[b], gcount[b], 0.99);
            }
        }
        return block;
    }

    /**
     * Paired per-bin log ratios ln(B / A) of metric {@code metric} for the first {@code pairs} pairs (blocks 2i, 2i+1):
     * for bin j, the A cell is the one of the two blocks that ran arm A in bin j. NaN where either cell is missing or not
     * positive, or where both blocks ran the same arm in that bin.
     */
    public static double[][] logRatios(final Block[] blocks, final int pairs, final int metric) {
        if (metric >= NC) {
            throw new IllegalArgumentException("logRatios: " + NAMES[metric] + " is a pair metric (pairLogRatios)");
        }
        int bins = blocks[0].n.length;
        double[][] lr = new double[pairs][bins];
        for (int i = 0; i < pairs; i++) {
            Block x = blocks[2 * i];
            Block y = blocks[2 * i + 1];
            for (int j = 0; j < bins; j++) {
                Block a = x.arm[j] == 0 ? x : y;
                Block b = x.arm[j] == 0 ? y : x;
                double va = a.m[metric][j];
                double vb = b.m[metric][j];
                lr[i][j] = x.arm[j] != y.arm[j] && va > 0.0 && vb > 0.0 ? Math.log(vb / va) : Double.NaN;
            }
        }
        return lr;
    }

    /**
     * Whole-pair tails (the classic 1 % / 0.1 % low over one pair): per arm, the {@link #PAIR_PS} percentiles of all kept
     * frame intervals of the pair's blocks x = [fromX, toX) and y = [fromY, toY), each frame in the arm of its cell
     * ({@code armsX[bin]} / {@code armsY[bin]}). Returns [arm][percentile] in ns; NaN where an arm has fewer than
     * {@code minFrames} frames. Allocates (runs at pair ends, in a discarded frame).
     */
    public static double[][] pairTails(final int[] dt, final boolean[] keep, final byte[] bin, final int[] armsX, final int[] armsY,
        final int fromX, final int toX, final int fromY, final int toY, final int minFrames) {
        int[] count = new int[2];
        for (int pass = 0; pass < 2; pass++) {
            int[] arms = pass == 0 ? armsX : armsY;
            for (int k = pass == 0 ? fromX : fromY, end = pass == 0 ? toX : toY; k < end; k++) {
                if (keep[k]) {
                    count[arms[bin[k]]]++;
                }
            }
        }
        long[][] v = {new long[count[0]], new long[count[1]]};
        int[] fill = new int[2];
        for (int pass = 0; pass < 2; pass++) {
            int[] arms = pass == 0 ? armsX : armsY;
            for (int k = pass == 0 ? fromX : fromY, end = pass == 0 ? toX : toY; k < end; k++) {
                if (keep[k]) {
                    int a = arms[bin[k]];
                    v[a][fill[a]++] = dt[k];
                }
            }
        }
        double[][] out = new double[2][PAIR_PS.length];
        for (int a = 0; a < 2; a++) {
            Arrays.sort(v[a]);
            for (int i = 0; i < PAIR_PS.length; i++) {
                out[a][i] = count[a] >= minFrames ? pct(v[a], count[a], PAIR_PS[i]) : Double.NaN;
            }
        }
        return out;
    }

    /**
     * Log ratios ln(B / A) of a pair metric for the first {@code pairs} pairs: one "cell" per pair, so {@link #effect}
     * reduces to a t interval over the pair log ratios. {@code tails[pair][arm][percentile]} as {@link #pairTails} returns.
     */
    public static double[][] pairLogRatios(final double[][][] tails, final int pairs, final int metric) {
        int pi = metric - NC;
        double[][] lr = new double[pairs][1];
        for (int i = 0; i < pairs; i++) {
            double a = tails[i] == null ? Double.NaN : tails[i][0][pi];
            double b = tails[i] == null ? Double.NaN : tails[i][1][pi];
            lr[i][0] = a > 0.0 && b > 0.0 ? Math.log(b / a) : Double.NaN;
        }
        return lr;
    }

    /** Log ratios of any metric: cells for cell metrics, one value per pair for pair metrics. */
    public static double[][] ratios(final Block[] blocks, final double[][][] tails, final int pairs, final int metric) {
        return metric < NC ? logRatios(blocks, pairs, metric) : pairLogRatios(tails, pairs, metric);
    }

    /** The analysis of one metric over some pairs: estimate, intervals, sign tests, variance components. */
    public static final class Effect {
        public int metric;
        public int pairs;
        public int cells;
        public double level;
        public double est = Double.NaN;
        /** Bias-corrected cluster bootstrap standard error (sqrt(P / (P - 1)) x the bootstrap SD). */
        public double se = Double.NaN;
        /** Decision interval: est -+ t_{P-1} * se. */
        public double lo = Double.NaN, hi = Double.NaN;
        /** Cluster percentile interval (reference only: too narrow for few pairs). */
        public double pctLo = Double.NaN, pctHi = Double.NaN;
        /** Cells as independent units: est -+ t_{cells-1} * sd_cells / sqrt(cells) (valid only when icc ~ 0). */
        public double cellLo = Double.NaN, cellHi = Double.NaN;
        /** Critical t value of the decision interval, t statistic est / se and its two-sided p (fixed sample size). */
        public double tCrit = Double.NaN, tStat = Double.NaN, p = Double.NaN;
        public int plusCells, minusCells, plusPairs, minusPairs;
        public double signCellsP = Double.NaN, signPairsP = Double.NaN;
        public double[] pairMeans = new double[0];
        /** SD of the pair means, pooled within-pair SD of the cell log ratios, and the share of between-pair variance. */
        public double sdPairs = Double.NaN, sdWithin = Double.NaN, icc = Double.NaN;

        public boolean excludesZero() {
            return lo > 0.0 || hi < 0.0;
        }

        public double halfWidth() {
            return (hi - lo) / 2.0;
        }

        /** The interval at another level from the same standard error ({@code [lo, hi]}; NaN without one). */
        public double[] at(final double alpha) {
            if (Double.isNaN(se) || pairs < 2) {
                return new double[]{Double.NaN, Double.NaN};
            }
            double t = tQuantile(1.0 - alpha / 2.0, pairs - 1);
            return new double[]{est - t * se, est + t * se};
        }
    }

    /**
     * Analyses {@code lr} (pairs x bins log ratios, NaN = missing) at confidence {@code 1 - alpha} with {@code resamples}
     * cluster bootstrap resamples (deterministic: seeded by {@code seed}).
     */
    public static Effect effect(final double[][] lr, final int metric, final double alpha, final int resamples, final long seed) {
        Effect e = new Effect();
        e.metric = metric;
        e.level = 1.0 - alpha;
        int pairs = lr.length;
        double[] sum = new double[pairs];
        int[] cnt = new int[pairs];
        double total = 0.0;
        int cells = 0;
        for (int i = 0; i < pairs; i++) {
            for (double v : lr[i]) {
                if (!Double.isNaN(v)) {
                    sum[i] += v;
                    cnt[i]++;
                    if (v > 0.0) {
                        e.plusCells++;
                    } else if (v < 0.0) {
                        e.minusCells++;
                    }
                }
            }
            total += sum[i];
            cells += cnt[i];
        }
        // Pairs without a valid cell do not count.
        int usable = 0;
        for (int i = 0; i < pairs; i++) {
            if (cnt[i] > 0) {
                usable++;
            }
        }
        e.pairs = usable;
        e.cells = cells;
        if (cells == 0) {
            return e;
        }
        e.est = total / cells;
        e.signCellsP = signTestP(e.plusCells, e.minusCells);
        double[] means = new double[usable];
        double[] sums = new double[usable];
        int[] cnts = new int[usable];
        int u = 0;
        for (int i = 0; i < pairs; i++) {
            if (cnt[i] > 0) {
                means[u] = sum[i] / cnt[i];
                sums[u] = sum[i];
                cnts[u] = cnt[i];
                if (means[u] > 0.0) {
                    e.plusPairs++;
                } else if (means[u] < 0.0) {
                    e.minusPairs++;
                }
                u++;
            }
        }
        e.pairMeans = means;
        e.signPairsP = signTestP(e.plusPairs, e.minusPairs);
        // Variance components (one-way, unbalanced allowed): within-pair SD of the cells, SD of the pair means.
        double ssWithin = 0.0;
        int dfWithin = 0;
        u = 0;
        for (int i = 0; i < pairs; i++) {
            if (cnt[i] > 0) {
                for (double v : lr[i]) {
                    if (!Double.isNaN(v)) {
                        double dv = v - means[u];
                        ssWithin += dv * dv;
                    }
                }
                dfWithin += cnt[i] - 1;
                u++;
            }
        }
        e.sdWithin = dfWithin > 0 ? Math.sqrt(ssWithin / dfWithin) : Double.NaN;
        // Cells as independent units.
        double ssCells = 0.0;
        for (double[] row : lr) {
            for (double v : row) {
                if (!Double.isNaN(v)) {
                    ssCells += (v - e.est) * (v - e.est);
                }
            }
        }
        if (cells > 1) {
            double sdCells = Math.sqrt(ssCells / (cells - 1));
            double tc = tQuantile(1.0 - alpha / 2.0, cells - 1);
            e.cellLo = e.est - tc * sdCells / Math.sqrt(cells);
            e.cellHi = e.est + tc * sdCells / Math.sqrt(cells);
        }
        if (usable < 2) {
            return e;
        }
        double mm = 0.0;
        for (double v : means) {
            mm += v;
        }
        mm /= usable;
        double ssm = 0.0;
        for (double v : means) {
            ssm += (v - mm) * (v - mm);
        }
        e.sdPairs = Math.sqrt(ssm / (usable - 1));
        if (!Double.isNaN(e.sdWithin)) {
            double cbar = (double) cells / usable;
            double between = e.sdPairs * e.sdPairs - e.sdWithin * e.sdWithin / cbar;
            double totalVar = Math.max(1e-30, between + e.sdWithin * e.sdWithin);
            e.icc = Math.max(0.0, between) / totalVar;
        }
        // Cluster bootstrap over pairs (each resample: P pairs with replacement, the cell-weighted mean).
        SplittableRandom rng = new SplittableRandom(seed);
        double[] boot = new double[resamples];
        double bm = 0.0;
        for (int r = 0; r < resamples; r++) {
            double s = 0.0;
            int c = 0;
            for (int i = 0; i < usable; i++) {
                int j = rng.nextInt(usable);
                s += sums[j];
                c += cnts[j];
            }
            boot[r] = s / c;
            bm += boot[r];
        }
        bm /= resamples;
        double ss = 0.0;
        for (double v : boot) {
            ss += (v - bm) * (v - bm);
        }
        double sdBoot = Math.sqrt(ss / Math.max(1, resamples - 1));
        e.se = sdBoot * Math.sqrt((double) usable / (usable - 1));
        double t = tQuantile(1.0 - alpha / 2.0, usable - 1);
        e.tCrit = t;
        e.lo = e.est - t * e.se;
        e.hi = e.est + t * e.se;
        if (e.se > 0.0) {
            e.tStat = e.est / e.se;
            e.p = Math.min(1.0, 2.0 * tCdf(-Math.abs(e.tStat), usable - 1));
        } else {
            e.tStat = e.est == 0.0 ? 0.0 : Math.copySign(Double.POSITIVE_INFINITY, e.est);
            e.p = e.est == 0.0 ? 1.0 : 0.0;
        }
        Arrays.sort(boot);
        e.pctLo = boot[(int) Math.min(resamples - 1, Math.max(0, Math.floor(alpha / 2.0 * resamples)))];
        e.pctHi = boot[(int) Math.min(resamples - 1, Math.max(0, Math.ceil((1.0 - alpha / 2.0) * resamples) - 1))];
        return e;
    }

    /**
     * True when the first {@code pairs} pairs cancel a linear drift of the machine. With an even number of segments per
     * block, half of every pair's bins run A first and half B first, so each pair is balanced on its own. With an odd
     * number (one arm per block: segments = 1) every bin of a pair has the same order, and ABBA balances only over two
     * pairs: a look after 3 or 5 pairs would carry a bias of d / 3 or d / 5 for a drift of d per block (abab-aa-1 drifted
     * 0.86 % per block). Strict alternation (order abab) with an odd number of segments is never balanced.
     */
    public static boolean balanced(final int pairs, final int segments, final boolean abba) {
        return segments % 2 == 0 || abba && pairs % 2 == 0;
    }

    /** Pair counts after which the sequential rule looks: from max(2, minPairs) to maxPairs, balanced ones only. */
    public static int[] lookPairs(final int minPairs, final int maxPairs, final int segments, final boolean abba) {
        int[] tmp = new int[Math.max(0, maxPairs)];
        int n = 0;
        for (int p = Math.max(2, minPairs); p <= maxPairs; p++) {
            if (balanced(p, segments, abba)) {
                tmp[n++] = p;
            }
        }
        return Arrays.copyOf(tmp, n);
    }

    /** Per-look alpha of the sequential rule: Bonferroni over the planned looks. */
    public static double lookLevel(final double alpha, final int looks) {
        return alpha / Math.max(1, looks);
    }

    /**
     * Holm step-down adjustment of {@code p} (NaN entries are skipped and stay NaN): the family-wise error rate of
     * "adjusted p < alpha" is at most alpha under any dependence between the tests.
     */
    public static double[] holm(final double[] p) {
        int n = 0;
        for (double v : p) {
            if (!Double.isNaN(v)) {
                n++;
            }
        }
        Integer[] idx = new Integer[n];
        int k = 0;
        for (int i = 0; i < p.length; i++) {
            if (!Double.isNaN(p[i])) {
                idx[k++] = i;
            }
        }
        Arrays.sort(idx, (a, b) -> Double.compare(p[a], p[b]));
        double[] out = new double[p.length];
        Arrays.fill(out, Double.NaN);
        double run = 0.0;
        for (int r = 0; r < n; r++) {
            run = Math.max(run, Math.min(1.0, (n - r) * p[idx[r]]));
            out[idx[r]] = run;
        }
        return out;
    }

    /**
     * The sequential rule's decision for one metric at a look: "effect" when its adjusted p ({@code pAdj}: Holm over the
     * primary family of p x looks, or Bonferroni for a secondary stop metric) is below {@code alpha}, "within-margin"
     * when its repeated interval ({@code e} at alpha / looks) has a half-width of at most {@code marginLog}.
     */
    public static String decide(final Effect e, final double pAdj, final double alpha, final double marginLog) {
        if (Double.isNaN(e.lo)) {
            return "undecided";
        }
        if (pAdj < alpha) {
            return "effect";
        }
        return e.halfWidth() <= marginLog ? "within-margin" : "undecided";
    }

    /** A metric is decided at a look when its interval excludes 0 or its half-width is at most {@code marginLog}. */
    public static String decision(final Effect e, final double marginLog) {
        if (Double.isNaN(e.lo)) {
            return "undecided";
        }
        if (e.excludesZero()) {
            return "effect";
        }
        return e.halfWidth() <= marginLog ? "within-margin" : "undecided";
    }

    // ---- distributions -------------------------------------------------------------------------------------------

    /** Two-sided exact sign test p-value (ties dropped): 2 * P(X <= min(plus, minus)), X ~ Bin(plus + minus, 1/2). */
    public static double signTestP(final int plus, final int minus) {
        int n = plus + minus;
        if (n == 0) {
            return 1.0;
        }
        int k = Math.min(plus, minus);
        double lnHalfN = n * Math.log(0.5);
        double cdf = 0.0;
        for (int i = 0; i <= k; i++) {
            cdf += Math.exp(lnChoose(n, i) + lnHalfN);
        }
        return Math.min(1.0, 2.0 * cdf);
    }

    public static double lnChoose(final int n, final int k) {
        return lnGamma(n + 1.0) - lnGamma(k + 1.0) - lnGamma(n - k + 1.0);
    }

    /** Lanczos approximation (g = 7, n = 9), |error| < 1e-13 for x > 0. */
    public static double lnGamma(final double x) {
        final double[] c = {0.99999999999980993, 676.5203681218851, -1259.1392167224028, 771.32342877765313,
            -176.61502916214059, 12.507343278686905, -0.13857109526572012, 9.9843695780195716e-6, 1.5056327351493116e-7};
        if (x < 0.5) {
            return Math.log(Math.PI / Math.abs(Math.sin(Math.PI * x))) - lnGamma(1.0 - x);
        }
        double xx = x - 1.0;
        double a = c[0];
        double t = xx + 7.5;
        for (int i = 1; i < 9; i++) {
            a += c[i] / (xx + i);
        }
        return 0.5 * Math.log(2.0 * Math.PI) + (xx + 0.5) * Math.log(t) - t + Math.log(a);
    }

    /** Regularized incomplete beta I_x(a, b) (continued fraction, modified Lentz). */
    public static double regIncBeta(final double a, final double b, final double x) {
        if (x <= 0.0) {
            return 0.0;
        }
        if (x >= 1.0) {
            return 1.0;
        }
        double lnFront = lnGamma(a + b) - lnGamma(a) - lnGamma(b) + a * Math.log(x) + b * Math.log(1.0 - x);
        if (x < (a + 1.0) / (a + b + 2.0)) {
            return Math.exp(lnFront) * betaCf(a, b, x) / a;
        }
        return 1.0 - Math.exp(lnFront) * betaCf(b, a, 1.0 - x) / b;
    }

    private static double betaCf(final double a, final double b, final double x) {
        final double tiny = 1e-300;
        double qab = a + b, qap = a + 1.0, qam = a - 1.0;
        double c = 1.0;
        double d = 1.0 - qab * x / qap;
        if (Math.abs(d) < tiny) {
            d = tiny;
        }
        d = 1.0 / d;
        double h = d;
        for (int m = 1; m <= 400; m++) {
            int m2 = 2 * m;
            double aa = m * (b - m) * x / ((qam + m2) * (a + m2));
            d = 1.0 + aa * d;
            if (Math.abs(d) < tiny) {
                d = tiny;
            }
            c = 1.0 + aa / c;
            if (Math.abs(c) < tiny) {
                c = tiny;
            }
            d = 1.0 / d;
            h *= d * c;
            aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2));
            d = 1.0 + aa * d;
            if (Math.abs(d) < tiny) {
                d = tiny;
            }
            c = 1.0 + aa / c;
            if (Math.abs(c) < tiny) {
                c = tiny;
            }
            d = 1.0 / d;
            double del = d * c;
            h *= del;
            if (Math.abs(del - 1.0) < 1e-15) {
                break;
            }
        }
        return h;
    }

    /** Student t CDF P(T <= t) with {@code df} degrees of freedom. */
    public static double tCdf(final double t, final double df) {
        double x = df / (df + t * t);
        double tail = 0.5 * regIncBeta(df / 2.0, 0.5, x);
        return t >= 0.0 ? 1.0 - tail : tail;
    }

    /** Student t quantile (bisection on the CDF; |error| < 1e-10). */
    public static double tQuantile(final double p, final double df) {
        if (df <= 0.0) {
            return Double.NaN;
        }
        if (p == 0.5) {
            return 0.0;
        }
        double lo = -1e7, hi = 1e7;
        for (int i = 0; i < 200; i++) {
            double mid = 0.5 * (lo + hi);
            if (tCdf(mid, df) < p) {
                lo = mid;
            } else {
                hi = mid;
            }
            if (hi - lo < 1e-11 * Math.max(1.0, Math.abs(mid))) {
                break;
            }
        }
        return 0.5 * (lo + hi);
    }

    // ---- JSON ----------------------------------------------------------------------------------------------------

    public static String num(final double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? "null" : String.format(Locale.ROOT, "%.6g", v);
    }

    /** Percent change for a log ratio. */
    public static double pctChange(final double logRatio) {
        return Double.isNaN(logRatio) ? Double.NaN : (Math.exp(logRatio) - 1.0) * 100.0;
    }

    public static String json(final Effect e) {
        return json(e, "");
    }

    /** As {@link #json(Effect)}, with {@code extra} (", \"key\": value..." or empty) appended inside the object. */
    public static String json(final Effect e, final String extra) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"metric\": \"").append(NAMES[e.metric]).append('"');
        sb.append(", \"pairs\": ").append(e.pairs).append(", \"cells\": ").append(e.cells);
        sb.append(", \"level\": ").append(num(e.level));
        sb.append(", \"logRatio\": ").append(num(e.est)).append(", \"se\": ").append(num(e.se));
        sb.append(", \"t\": ").append(num(e.tStat)).append(", \"p\": ").append(num(e.p));
        sb.append(", \"ci\": [").append(num(e.lo)).append(", ").append(num(e.hi)).append(']');
        sb.append(", \"pctChange\": ").append(num(pctChange(e.est)));
        sb.append(", \"pctCi\": [").append(num(pctChange(e.lo))).append(", ").append(num(pctChange(e.hi))).append(']');
        sb.append(", \"percentileCi\": [").append(num(e.pctLo)).append(", ").append(num(e.pctHi)).append(']');
        sb.append(", \"cellCi\": [").append(num(e.cellLo)).append(", ").append(num(e.cellHi)).append(']');
        sb.append(", \"signCells\": {\"plus\": ").append(e.plusCells).append(", \"minus\": ").append(e.minusCells)
            .append(", \"p\": ").append(num(e.signCellsP)).append('}');
        sb.append(", \"signPairs\": {\"plus\": ").append(e.plusPairs).append(", \"minus\": ").append(e.minusPairs)
            .append(", \"p\": ").append(num(e.signPairsP)).append('}');
        sb.append(", \"sdPairs\": ").append(num(e.sdPairs)).append(", \"sdWithin\": ").append(num(e.sdWithin))
            .append(", \"icc\": ").append(num(e.icc));
        sb.append(", \"pairMeans\": [");
        for (int i = 0; i < e.pairMeans.length; i++) {
            sb.append(i == 0 ? "" : ", ").append(num(e.pairMeans[i]));
        }
        return sb.append(']').append(extra).append('}').toString();
    }
}
