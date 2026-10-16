package bench;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Headless test of the soak verdict (bench/src/bench/SoakVerdict.java; run by tools/soak-verdict-test.sh, which compiles
 * SoakVerdict.java alone with this file: the verdict needs no game class). Synthetic 30-minute runs sampled every 10 s
 * with the default limits and windows; each case states what the verdict must say.
 */
public final class SoakVerdictTest {
    private static int checks, failures;
    private static final SoakVerdict.Params P = new SoakVerdict.Params(300, 1200, 180, 60);
    private static final SoakVerdict.Limit[] LIMITS = {
        new SoakVerdict.Limit("liveHeapMB", 3.0, 160.0, false),
        new SoakVerdict.Limit("metalMB", 4.0, 256.0, true),
        new SoakVerdict.Limit("nativeFootprintMB", 5.0, 320.0, true),
    };

    interface Fn {
        double at(double t, int i);
    }

    /** One synthetic run: every 10 s, window cycle as in Soak (fullscreen until 75 s of each 120 s, trans 5 s after changes). */
    static final class Run {
        final Map<String, List<Object>> series = new LinkedHashMap<>();
        final List<double[]> live = new ArrayList<>();
        final double end;

        Run(double end, Fn heap, Fn metal, Fn nat, boolean windowedMetalHuge) {
            this.end = end;
            int i = 0;
            for (double t = 0; t <= end + 1e-9; t += 10, i++) {
                double c = t % 120;
                boolean fs = c < 75 || t < 75;
                boolean trans = (t >= 75 && (Math.abs(c - 75) < 5 || c < 5)) || (!fs && (c >= 82 && c < 87 || c >= 97 && c < 102 || c >= 112 && c < 117));
                put("t", t);
                put("fs", fs ? 1 : 0);
                put("trans", trans ? 1 : 0);
                put("fbW", fs ? 3600 : 2560);
                put("fbH", fs ? 2338 : 1600);
                double mv = metal.at(t, i);
                put("metalMB", mv < 0 ? -1.0 : (!fs && windowedMetalHuge ? mv - 400 : mv));
                put("nativeFootprintMB", nat.at(t, i));
                put("heapAfterGcMB", heap.at(t, i) + 300);
                put("footprintMB", nat.at(t, i) + 3000);
                put("rssMB", 2500.0);
                put("heapUsedMB", heap.at(t, i) + 600);
            }
            int k = 0;
            for (double t = 60; t < end - 1; t += 60, k++) {   // Soak: System.gc() every soak.gcEvery = 60 s (W = 300 on the grid)
                live.add(new double[]{t, heap.at(t, k), 150});
            }
            live.add(new double[]{end, heap.at(end, 100), 150});
        }

        private void put(String k, Object v) {
            series.computeIfAbsent(k, x -> new ArrayList<>()).add(v);
        }

        String verdict(String status, String[] line) {
            return SoakVerdict.compute(series, live, status, null, 1800, end, P, LIMITS, line);
        }
    }

    static void check(String what, boolean ok, String detail) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL " + what + " :: " + detail);
        } else {
            System.out.println("ok   " + what);
        }
    }

    static boolean pass(String json) {
        return json.startsWith("{\"pass\": true");
    }

    static boolean metricPass(String json, String metric) {
        int i = json.indexOf("\"" + metric + "\": {\"pass\": ");
        return i >= 0 && json.startsWith("true", i + metric.length() + 13);
    }

    public static void main(String[] args) {
        Random rnd = new Random(42);
        double[] noise = new double[400];
        for (int i = 0; i < noise.length; i++) {
            noise[i] = rnd.nextGaussian();
        }
        Fn flatHeap = (t, i) -> 800 + 30 * noise[i % 400];   // old-generation live heap: ~30 MB noise in the smoke
        Fn flatMetal = (t, i) -> 1500 + 20 * noise[(i + 7) % 400];
        Fn flatNat = (t, i) -> 2200 + 25 * noise[(i + 13) % 400];
        String[] line = new String[1];

        String v = new Run(1800, flatHeap, flatMetal, flatNat, true).verdict("complete", line);
        check("flat memory with noise (30/20/25 MB) and big windowed-only metal swings: PASS, conclusive", pass(v) && v.contains("\"conclusive\": true"), line[0] + " " + v);
        check("reference framebuffer is the fullscreen one", v.contains("\"referenceFramebuffer\": \"3600x2338\""), v);

        v = new Run(1800, (t, i) -> 800 + 6.0 * t / 60 + 30 * noise[i % 400], flatMetal, flatNat, false).verdict("complete", line);
        check("heap leak 6 MB/min: FAIL on liveHeapMB", !pass(v) && !metricPass(v, "liveHeapMB") && metricPass(v, "metalMB"), line[0]);

        v = new Run(1800, (t, i) -> 800 + 1.0 * t / 60 + 30 * noise[i % 400], flatMetal, flatNat, false).verdict("complete", line);
        check("heap growth 1 MB/min (below X = 3, delta ~25 MB): PASS", pass(v), line[0]);

        v = new Run(1800, flatHeap, (t, i) -> 1500 + (t > 900 ? 128 : 0) + 20 * noise[(i + 7) % 400], flatNat, false).verdict("complete", line);
        check("one 128 MB metal step (new plateau at 15 min): slope test robust, delta 128 < 256: PASS", pass(v), line[0] + " " + v);

        v = new Run(1800, flatHeap, (t, i) -> 1500 + (t > 700 ? 150 : 0) + (t > 1300 ? 150 : 0) + 20 * noise[(i + 7) % 400], flatNat, false).verdict("complete", line);
        check("two metal steps (300 MB): FAIL on metalMB", !pass(v) && !metricPass(v, "metalMB"), line[0]);

        v = new Run(1800, flatHeap, (t, i) -> 1500 + 6.0 * t / 60 + 20 * noise[(i + 7) % 400], (t, i) -> 2200 + 6.0 * t / 60 + 25 * noise[(i + 13) % 400], false).verdict("complete", line);
        check("metal + native leak 6 MB/min: FAIL on metalMB and nativeFootprintMB", !pass(v) && !metricPass(v, "metalMB") && !metricPass(v, "nativeFootprintMB"), line[0]);

        v = new Run(1800, flatHeap, (t, i) -> 1500 + ((i % 37) == 5 ? 600 : 0) + 20 * noise[(i + 7) % 400], flatNat, false).verdict("complete", line);
        check("isolated 600 MB metal spikes (1 sample in 37): PASS (bucket medians)", pass(v), line[0]);

        v = new Run(1800, flatHeap, (t, i) -> 1500 + (t > 1300 ? 128 : 0) + 20 * noise[(i + 7) % 400], flatNat, false).verdict("complete", line);
        check("one 128 MB metal step late (at 21.7 min): PASS", pass(v), line[0] + " " + v);

        v = new Run(1800, flatHeap, (t, i) -> 1500 + 6.0 * t / 60 + (t > 900 ? 128 : 0) + 20 * noise[(i + 7) % 400], flatNat, false).verdict("complete", line);
        check("metal leak 6 MB/min plus a 128 MB step: FAIL on metalMB (step tolerance keeps the leak's slope)", !pass(v) && !metricPass(v, "metalMB"), line[0]);

        v = new Run(1800, flatHeap, (t, i) -> 1500 + 6.0 * t / 60 + 20 * noise[(i + 7) % 400], flatNat, false).verdict("complete", line);
        check("metal leak 6 MB/min, no step (delta ~150 < 256): FAIL on metalMB slope", !pass(v) && !metricPass(v, "metalMB") && line[0].contains("metalMB: slope"), line[0]);

        int fp = 0;
        for (int seed = 1; seed <= 200; seed++) {
            Random r2 = new Random(seed);
            double[] nz = new double[400];
            for (int i = 0; i < nz.length; i++) {
                nz[i] = r2.nextGaussian();
            }
            String vv = new Run(1800, (t, i) -> 800 + 30 * nz[i % 400], (t, i) -> 1500 + 20 * nz[(i + 7) % 400],
                (t, i) -> 2200 + 25 * nz[(i + 13) % 400], false).verdict("complete", line);
            fp += pass(vv) ? 0 : 1;
        }
        check("false alarms on flat noisy runs (30/20/25 MB per point, 200 seeds) <= 4", fp <= 4, fp + " of 200 failed");
        System.out.println("     false alarms: " + fp + " of 200");
        // Power / robustness over 100 seeds each (same noise levels).
        int heapLeak = 0, metalLeak = 0, stepOk = 0;
        for (int seed = 1; seed <= 100; seed++) {
            Random r2 = new Random(1000 + seed);
            double[] nz = new double[400];
            for (int i = 0; i < nz.length; i++) {
                nz[i] = r2.nextGaussian();
            }
            double stepT = 700 + r2.nextInt(800);
            Fn h = (t, i) -> 800 + 30 * nz[i % 400], mt = (t, i) -> 1500 + 20 * nz[(i + 7) % 400], na = (t, i) -> 2200 + 25 * nz[(i + 13) % 400];
            heapLeak += metricPass(new Run(1800, (t, i) -> h.at(t, i) + 5.0 * t / 60, mt, na, false).verdict("complete", line), "liveHeapMB") ? 0 : 1;
            metalLeak += metricPass(new Run(1800, h, (t, i) -> mt.at(t, i) + 6.0 * t / 60, na, false).verdict("complete", line), "metalMB") ? 0 : 1;
            stepOk += metricPass(new Run(1800, h, (t, i) -> mt.at(t, i) + (t > stepT ? 128 : 0), na, false).verdict("complete", line), "metalMB") ? 1 : 0;
        }
        System.out.println("     detected: heap leak 5 MB/min " + heapLeak + "/100, metal leak 6 MB/min " + metalLeak + "/100; one 128 MB metal step passed " + stepOk + "/100");
        check("heap leak 5 MB/min detected in >= 90 of 100", heapLeak >= 90, heapLeak + "/100");
        check("metal leak 6 MB/min detected in >= 95 of 100", metalLeak >= 95, metalLeak + "/100");
        check("one 128 MB metal step at a random time in 700..1500 s passes in >= 90 of 100", stepOk >= 90, stepOk + "/100");

        v = new Run(1800, flatHeap, (t, i) -> -1, flatNat, false).verdict("complete", line);
        check("metal reader unavailable: FAIL 'not measured'", !pass(v) && line[0].contains("metalMB: not measured"), line[0]);

        v = new Run(1800, flatHeap, flatMetal, flatNat, false).verdict("failed", line);
        check("status failed: FAIL", !pass(v) && line[0].contains("status failed"), line[0]);

        v = new Run(1000, flatHeap, flatMetal, flatNat, false).verdict("complete", line);
        check("stopped at 1000 of 1800 s: FAIL (ran ...)", !pass(v) && line[0].contains("ran 1000 of 1800 s"), line[0]);

        // Short (smoke-like) run with its own windows: inconclusive but computed.
        SoakVerdict.Params sp = new SoakVerdict.Params(60, 1200, 30, 15);
        Run shortRun = new Run(180, flatHeap, flatMetal, flatNat, false);
        String sv = SoakVerdict.compute(shortRun.series, shortRun.live, "complete", null, 180, 180, sp, LIMITS, line);
        check("3-minute run: conclusive false, reason 'inconclusive'", sv.contains("\"conclusive\": false") && line[0].contains("inconclusive"), line[0] + " " + sv);

        // Theil-Sen on exact lines.
        List<double[]> pts = new ArrayList<>();
        for (int k = 0; k < 20; k++) {
            pts.add(new double[]{300 + 60 * k, 100 + 2.5 * k});
        }
        SoakVerdict.Trend tr = SoakVerdict.trend(pts, false, 300, 1500, P);
        check("Theil-Sen slope of an exact 2.5 MB/min line = 2.5", Math.abs(tr.slope() - 2.5) < 1e-9 && tr.noise() < 1e-9, tr.toString());
        check("median of {3,1,2,4} = 2.5", SoakVerdict.median(new double[]{3, 1, 2, 4}) == 2.5, "");

        System.out.println("SoakVerdictTest: " + checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }
}
