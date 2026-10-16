package bench;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The soak verdict (rules and limits: Soak's class comment). Pure computation over the sampled series, no game classes,
 * so tools/SoakVerdictTest.java checks it headlessly.
 *
 * <p>Inputs: the columnar series ("t", "fs", "trans", "fbW", "fbH" and one column per metric, -1 = not measured), the live
 * heap points {t, MB, gcMs}, the run status / error, the planned duration and the current time (seconds since the soak
 * started).
 */
final class SoakVerdict {
    record Limit(String metric, double maxSlope, double maxDelta, boolean windowed) {
    }

    /** warmupS: W; trendS: slope span (the last 20 min); edgeS: delta windows (3 min); bucketS: aggregation (60 s). */
    record Params(double warmupS, double trendS, double edgeS, double bucketS) {
    }

    /**
     * slope: step-tolerant Theil-Sen slope (gated); fullSlope: plain Theil-Sen over the span; stepMB / stepAt: the largest
     * upward level shift the slope allowed for (0 / -1: none).
     */
    record Trend(int n, double slope, double fullSlope, double stepMB, double stepAt, double delta, double noise, double firstMedian,
                 double lastMedian) {
    }

    /** A neighbour-to-neighbour rise above this many robust sigmas of all neighbour differences is a level shift. */
    static final double STEP_SIGMAS = 4.0;

    /** Minimum trend span (after the warmup) for a conclusive verdict. */
    static final double CONCLUSIVE_S = 15 * 60.0;

    private SoakVerdict() {
    }

    /** Limit with an optional -Dbench.soak.limit.&lt;metric&gt;=X,Y override. */
    static Limit limit(final String metric, final double slope, final double delta, final boolean windowed) {
        String v = System.getProperty("bench.soak.limit." + metric);
        if (v != null && v.contains(",")) {
            String[] p = v.split(",");
            return new Limit(metric, Double.parseDouble(p[0].trim()), Double.parseDouble(p[1].trim()), windowed);
        }
        return new Limit(metric, slope, delta, windowed);
    }

    private static double d(final Object o) {
        return ((Number) o).doubleValue();
    }

    /**
     * @param line out: line[0] = one-line summary ("PASS", "PASS (inconclusive)" or "FAIL", with reasons)
     * @return the verdict JSON object
     */
    static String compute(final Map<String, List<Object>> series, final List<double[]> live, final String status, final String error,
                          final double durationS, final double end, final Params p, final Limit[] limits, final String[] line) {
        int rows = series.isEmpty() ? 0 : series.values().stream().mapToInt(List::size).min().orElse(0);
        List<Object> ts = series.getOrDefault("t", List.of());
        ts = ts.subList(0, Math.min(rows, ts.size()));
        List<Object> fs = series.getOrDefault("fs", List.of()), trans = series.getOrDefault("trans", List.of());
        List<Object> fbW = series.getOrDefault("fbW", List.of()), fbH = series.getOrDefault("fbH", List.of());
        // Reference window state: the most common fullscreen framebuffer among settled post-warmup samples.
        Map<String, Integer> fbCount = new LinkedHashMap<>();
        for (int i = 0; i < ts.size(); i++) {
            if (d(ts.get(i)) >= p.warmupS() && d(fs.get(i)) == 1 && d(trans.get(i)) == 0) {
                fbCount.merge(fbW.get(i) + "x" + fbH.get(i), 1, Integer::sum);
            }
        }
        String refFb = fbCount.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("none");
        double spanStart = Math.max(p.warmupS(), end - p.trendS());
        boolean conclusive = end - p.warmupS() >= CONCLUSIVE_S;
        List<String> reasons = new ArrayList<>();
        boolean pass = "complete".equals(status) && end >= durationS - 1.0;
        if (!"complete".equals(status)) {
            reasons.add("status " + status + (error == null ? "" : ": " + error));
        } else if (end < durationS - 1.0) {
            reasons.add("ran " + Math.round(end) + " of " + Math.round(durationS) + " s");
        }
        StringBuilder m = new StringBuilder();
        for (Limit lim : limits) {
            List<double[]> pts = new ArrayList<>();
            boolean bucketed = true;
            boolean measured = false;
            if (lim.metric().equals("liveHeapMB")) {
                bucketed = false;
                for (double[] q : live) {
                    measured = true;
                    if (q[0] >= p.warmupS() - 1.0) {
                        pts.add(new double[]{q[0], q[1]});
                    }
                }
            } else {
                List<Object> v = series.getOrDefault(lim.metric(), List.of());
                for (int i = 0; i < ts.size(); i++) {
                    double t = d(ts.get(i));
                    if (d(v.get(i)) < 0) {
                        continue;
                    }
                    measured = true;
                    if (t < p.warmupS()) {
                        continue;
                    }
                    if (lim.windowed() && (d(fs.get(i)) != 1 || d(trans.get(i)) != 0 || !(fbW.get(i) + "x" + fbH.get(i)).equals(refFb))) {
                        continue;
                    }
                    pts.add(new double[]{t, d(v.get(i))});
                }
            }
            Trend tr = trend(pts, bucketed, spanStart, end, p);
            boolean ok = tr.n() >= 3 && tr.slope() < lim.maxSlope() && tr.delta() < lim.maxDelta();
            if (!ok) {
                pass = false;
                reasons.add(lim.metric() + (!measured ? ": not measured (reader unavailable)"
                    : tr.n() < 3 ? ": insufficient data (" + tr.n() + " points after the warmup)"
                    : String.format(Locale.ROOT, ": slope %.2f MB/min (max %.1f), delta %.1f MB (max %.0f)", tr.slope(), lim.maxSlope(), tr.delta(), lim.maxDelta())));
            }
            m.append(m.isEmpty() ? "" : ", ").append(String.format(Locale.ROOT,
                "\"%s\": {\"pass\": %b, \"slopeMBperMin\": %.3f, \"maxSlope\": %.2f, \"deltaMB\": %.1f, \"maxDelta\": %.1f, \"noiseMB\": %.1f, \"points\": %d, \"first\": %.1f, \"last\": %.1f, \"fullSlopeMBperMin\": %.3f, \"stepMB\": %.1f, \"stepAtSeconds\": %.0f}",
                lim.metric(), ok, tr.slope(), lim.maxSlope(), tr.delta(), lim.maxDelta(), tr.noise(), tr.n(), nz(tr.firstMedian()), nz(tr.lastMedian()),
                tr.fullSlope(), tr.stepMB(), tr.stepAt()));
        }
        // Informational trends (not gated; per-bucket medians, all window states).
        StringBuilder info = new StringBuilder();
        for (String key : new String[]{"heapAfterGcMB", "footprintMB", "rssMB", "heapUsedMB"}) {
            List<double[]> pts = new ArrayList<>();
            List<Object> v = series.getOrDefault(key, List.of());
            for (int i = 0; i < ts.size() && i < v.size(); i++) {
                if (d(ts.get(i)) >= p.warmupS() && d(v.get(i)) >= 0) {
                    pts.add(new double[]{d(ts.get(i)), d(v.get(i))});
                }
            }
            Trend tr = trend(pts, true, spanStart, end, p);
            info.append(info.isEmpty() ? "" : ", ").append(String.format(Locale.ROOT,
                "\"%s\": {\"slopeMBperMin\": %.3f, \"deltaMB\": %.1f, \"noiseMB\": %.1f, \"points\": %d}", key, tr.slope(), tr.delta(), tr.noise(), tr.n()));
        }
        if (!conclusive) {
            reasons.add(String.format(Locale.ROOT, "inconclusive: trend span %.1f min < %.0f min", Math.max(0.0, end - p.warmupS()) / 60.0, CONCLUSIVE_S / 60.0));
        }
        line[0] = (pass ? (conclusive ? "PASS" : "PASS (inconclusive)") : "FAIL") + (reasons.isEmpty() ? "" : " " + reasons);
        StringBuilder r = new StringBuilder();
        for (String s : reasons) {
            r.append(r.isEmpty() ? "" : ", ").append('"').append(s.replace("\\", "/").replace("\"", "'")).append('"');
        }
        return String.format(Locale.ROOT,
            "{\"pass\": %b, \"conclusive\": %b, \"reasons\": [%s], \"referenceFramebuffer\": \"%s\", \"trendFromSeconds\": %.0f, \"toSeconds\": %.0f, \"metrics\": {%s}, \"info\": {%s}}",
            pass, conclusive, r, refFb, spanStart, end, m, info);
    }

    private static double nz(final double v) {
        return Double.isNaN(v) ? -1.0 : v;
    }

    /**
     * Over [spanStart, end] of the points (bucketed: per-bucketS median at the bucket's mean time):
     * <ul>
     *   <li>slope (gated, MB/min): Theil-Sen slope allowing ONE level shift. If the largest local rise (median of the
     *       next 3 points - median of the previous 3) exceeds {@value #STEP_SIGMAS} robust sigmas of all local rises
     *       around their median, the span is split there and the slope is the median of the pairwise slopes of pairs on the same side.
     *       A single step to a new plateau (a terrain-buffer heap growing to a new high-water mark) then gives ~0, a
     *       sustained leak keeps its slope on both sides; the step still counts in delta. Plain Theil-Sen (fullSlope,
     *       reported) is not robust to a level shift: a 128 MB step a quarter into the span reads as ~7 MB/min at 20 MB
     *       noise (tools/SoakVerdictTest.java). No such rise, or under 6 points: plain Theil-Sen;</li>
     *   <li>delta: median of the points in the last edgeS - median of those in the first edgeS after the warmup (no point
     *       in either window gives n = 0, i.e. insufficient data);</li>
     *   <li>noise: 1.4826 x MAD of the residuals around the step-tolerant fit.</li>
     * </ul>
     */
    static Trend trend(final List<double[]> raw, final boolean bucketed, final double spanStart, final double end, final Params p) {
        List<double[]> pts = new ArrayList<>();
        if (bucketed) {
            Map<Long, List<double[]>> buckets = new LinkedHashMap<>();
            for (double[] q : raw) {
                buckets.computeIfAbsent((long) Math.floor((q[0] - p.warmupS()) / p.bucketS()), k -> new ArrayList<>()).add(q);
            }
            for (List<double[]> b : buckets.values()) {
                pts.add(new double[]{b.stream().mapToDouble(q -> q[0]).average().orElse(0), median(b.stream().mapToDouble(q -> q[1]).toArray())});
            }
        } else {
            pts.addAll(raw);
        }
        List<double[]> span = pts.stream().filter(q -> q[0] >= spanStart - 1e-9 && q[0] <= end + 1e-9).toList();
        int n = span.size();
        double slope = 0.0, fullSlope = 0.0, noise = 0.0, stepMB = 0.0, stepAt = -1.0;
        if (n >= 2) {
            fullSlope = pairSlope(span, 0, n, n);
            slope = fullSlope;
            int split = n;
            if (n >= 6) {
                // A level shift = the largest local rise L_k = median(v[k..k+2]) - median(v[k-3..k-1]) above the median
                // of all L_k (a straight line's L_k are all equal: never a shift) by more than STEP_SIGMAS x sigma_L.
                // sigma_L = 0.95 x the point noise (difference of two medians of three), the point noise from the first
                // differences (1.4826 x MAD / sqrt 2), which a single step or a trend barely affects. Choosing the split by
                // the local rise (not by before/after medians, equal at every split of a straight line) keeps noise from
                // picking a split that flattens a real trend; medians of three keep a step whose bucket mixes old and new
                // values (a step inside a bucket) whole.
                int m = n - 5;
                double[] rise = new double[m];
                int best = -1;
                for (int k = 3; k <= n - 3; k++) {
                    rise[k - 3] = median3(span, k) - median3(span, k - 3);
                    if (best < 0 || rise[k - 3] > rise[best - 3]) {
                        best = k;
                    }
                }
                double med = median(rise);
                double[] first = new double[n - 1];
                for (int k = 1; k < n; k++) {
                    first[k - 1] = span.get(k)[1] - span.get(k - 1)[1];
                }
                double firstMed = median(first);
                double[] dev = new double[n - 1];
                for (int i = 0; i < n - 1; i++) {
                    dev[i] = Math.abs(first[i] - firstMed);
                }
                double sigma = 0.95 * 1.4826 * median(dev) / Math.sqrt(2.0);
                double[] diffs = rise;
                if (best > 0 && diffs[best - 3] > 0 && diffs[best - 3] - med > STEP_SIGMAS * sigma) {
                    split = best;
                    slope = pairSlope(span, 0, split, n);
                    stepMB = median(span.subList(split, n).stream().mapToDouble(q -> q[1]).toArray())
                        - median(span.subList(0, split).stream().mapToDouble(q -> q[1]).toArray());
                    stepAt = 0.5 * (span.get(split - 1)[0] + span.get(split)[0]);
                }
            }
            // Residuals around the fit: common slope, separate medians on each side of the split.
            final double s = slope;
            final int cut = split;
            double[] res = new double[n];
            double icptA = median(span.subList(0, cut).stream().mapToDouble(q -> q[1] - s * q[0] / 60.0).toArray());
            double icptB = cut < n ? median(span.subList(cut, n).stream().mapToDouble(q -> q[1] - s * q[0] / 60.0).toArray()) : icptA;
            for (int i = 0; i < n; i++) {
                res[i] = Math.abs(span.get(i)[1] - ((i < cut ? icptA : icptB) + s * span.get(i)[0] / 60.0));
            }
            noise = 1.4826 * median(res);
        }
        double firstMedian = median(raw.stream().filter(q -> q[0] < p.warmupS() + p.edgeS()).mapToDouble(q -> q[1]).toArray());
        double lastMedian = median(raw.stream().filter(q -> q[0] >= end - p.edgeS()).mapToDouble(q -> q[1]).toArray());
        if (Double.isNaN(firstMedian) || Double.isNaN(lastMedian)) {
            return new Trend(0, slope, fullSlope, stepMB, stepAt, 0.0, noise, firstMedian, lastMedian);
        }
        return new Trend(n, slope, fullSlope, stepMB, stepAt, lastMedian - firstMedian, noise, firstMedian, lastMedian);
    }

    private static double median3(final List<double[]> span, final int from) {
        return median(new double[]{span.get(from)[1], span.get(from + 1)[1], span.get(from + 2)[1]});
    }

    /** Median slope (MB/min) of the pairs i &lt; j that lie both in [0, split) or both in [split, n). */
    private static double pairSlope(final List<double[]> span, final int from, final int split, final int n) {
        double[] slopes = new double[n * (n - 1) / 2];
        int k = 0;
        for (int i = from; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if ((i < split) != (j < split)) {
                    continue;
                }
                double dtMin = (span.get(j)[0] - span.get(i)[0]) / 60.0;
                if (dtMin > 1e-9) {
                    slopes[k++] = (span.get(j)[1] - span.get(i)[1]) / dtMin;
                }
            }
        }
        return k == 0 ? 0.0 : median(Arrays.copyOf(slopes, k));
    }

    static double median(final double[] v) {
        if (v.length == 0) {
            return Double.NaN;
        }
        double[] c = v.clone();
        Arrays.sort(c);
        return c.length % 2 == 1 ? c[c.length / 2] : 0.5 * (c[c.length / 2 - 1] + c[c.length / 2]);
    }
}
