import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.IntStream;
import javax.imageio.ImageIO;

/**
 * Perceptual comparison of two screenshot folders, scene by scene (docs/GOAL.md, visual bar (a):
 * "indistinguishable to the naked eye").
 *
 *   java tools/PerceptualDiff.java <reference dir> <candidate dir> [--floor <run A dir> <run B dir> [<run C> <run D> ...]]
 *
 * Images larger than the panel's physical resolution (3024x1964) are area-resampled (sRGB values) to it; smaller ones
 * (the scene runner saves 1800x1169: vanilla's screenshot downscale of 2) are compared as they are. Per scene:
 *   dE mean / dE p99.9  CIEDE2000 between the two (sRGB D65 -> CIELAB), mean and 99.9th percentile;
 *   SSIM                mean SSIM of the luma (Rec. 709 weights on sRGB values; 11x11 Gaussian, sigma 1.5);
 *   region              largest 8-connected region of pixels with dE > 10 (a missing or changed object), in pixels.
 * Bar: dE mean < 1.0, dE p99.9 < 3.0, SSIM >= 0.99, region < 256 px at panel resolution (scaled by area otherwise).
 * Run-to-run noise: some scenes differ between two runs of the same renderer (particle spawns, mob idle animation,
 * water flow, end crystals, bubbles, the inventory player model). --floor gives pairs of runs of the reference
 * configuration; the floor is the worst value of each metric over the pairs. A scene that misses the bar is "noise"
 * when each metric it misses is within 1.5x of the floor (SSIM: its dissimilarity 1 - SSIM). Without --floor,
 * particles / entities / water_surface are reported as "noisy".
 * Prints a table; exit code 1 if any scene FAILs (or an image is missing).
 */
public class PerceptualDiff {
    static final double MEAN_MAX = 1.0, P999_MAX = 3.0, SSIM_MIN = 0.99, REGION_DE = 10.0, FLOOR_SLACK = 1.5;
    static final int PANEL_W = 3024, PANEL_H = 1964, REGION_MAX = 256;

    record Metrics(double mean, double p999, double ssim, int region, int regionMax, String where) {
        boolean meanOk() { return mean < MEAN_MAX; }
        boolean p999Ok() { return p999 < P999_MAX; }
        boolean ssimOk() { return ssim >= SSIM_MIN; }
        boolean regionOk() { return region < regionMax; }
        boolean ok() { return meanOk() && p999Ok() && ssimOk() && regionOk(); }
        /** Every metric that misses the bar is within FLOOR_SLACK of the floor's. */
        boolean withinFloor(Metrics f) {
            return (meanOk() || mean <= FLOOR_SLACK * f.mean) && (p999Ok() || p999 <= FLOOR_SLACK * f.p999)
                && (ssimOk() || 1 - ssim <= FLOOR_SLACK * (1 - f.ssim)) && (regionOk() || region <= FLOOR_SLACK * f.region);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--selftest")) { selftest(); return; }
        if (args.length >= 3 && args[0].equals("--temporal")) {
            boolean fail = temporal(new File(args[1]), new File(args[2]), true);
            if (fail && args.length >= 6 && args[3].equals("--floor")) {
                // Floor: the worst value of each summary metric over pairs of runs of the reference configuration (e.g.
                // vanilla vs the exact path); the candidate is "noise" when every miss is within FLOOR_SLACK of it.
                double[] f = new double[6];
                for (int i = 4; i + 1 < args.length; i += 2) {
                    temporal(new File(args[i]), new File(args[i + 1]), false);
                    for (int k = 0; k < 6; k++) f[k] = Math.max(f[k], lastSummary[k]);
                }
                double[] s = lastCand;
                boolean noise = (s[0] < MEAN_MAX || s[0] <= FLOOR_SLACK * f[0]) && (s[1] < P999_MAX || s[1] <= FLOOR_SLACK * f[1])
                    && (s[2] < lastRegionMax || s[2] <= FLOOR_SLACK * f[2]) && (s[3] < MEAN_MAX || s[3] <= FLOOR_SLACK * f[3])
                    && (s[4] < P999_MAX || s[4] <= FLOOR_SLACK * f[4]) && (s[5] < lastRegionMax || s[5] <= FLOOR_SLACK * f[5]);
                System.out.printf(Locale.ROOT, "floor (worst over %d pairs): spatial mean %.3f p99.9 %.2f region %.0f; temporal mean %.3f p99.9 %.2f region %.0f -> %s%n",
                    (args.length - 4) / 2, f[0], f[1], f[2], f[3], f[4], f[5], noise ? "NOISE (within floor)" : "FAIL");
                fail = !noise;
            }
            System.exit(fail ? 1 : 0);
        }
        File ref = new File(args[0]), cand = new File(args[1]);
        java.util.List<File[]> floors = new java.util.ArrayList<>();
        if (args.length >= 5 && args[2].equals("--floor")) for (int i = 3; i + 1 < args.length; i += 2) floors.add(new File[] { new File(args[i]), new File(args[i + 1]) });
        String[] names = ref.list((d, n) -> n.endsWith(".png"));
        Arrays.sort(names);
        boolean fail = false;
        System.out.printf("%-16s %8s %8s %8s %8s %-16s %-6s %s%n", "scene", "dE mean", "dE p99.9", "SSIM", "region", "region at", "verdict",
                          !floors.isEmpty() ? "floor (mean p99.9 SSIM region)" : "");
        for (String n : names) {
            String scene = n.replace(".png", "");
            File c = new File(cand, n);
            if (!c.exists()) { System.out.printf("%-16s MISSING%n", scene); fail = true; continue; }
            Metrics m = compare(new File(ref, n), c);
            String verdict, floorText = "";
            if (m.ok()) verdict = "ok";
            else if (floors.isEmpty()) {
                boolean noisy = scene.startsWith("particles") || scene.startsWith("entities") || scene.startsWith("water_surface");
                verdict = noisy ? "noisy" : "FAIL";
            } else {
                Metrics f = null;
                for (File[] pair : floors) {
                    File fa = new File(pair[0], n), fb = new File(pair[1], n);
                    if (!fa.exists() || !fb.exists()) continue;
                    Metrics g = compare(fa, fb);
                    f = f == null ? g : new Metrics(Math.max(f.mean, g.mean), Math.max(f.p999, g.p999), Math.min(f.ssim, g.ssim),
                                                    Math.max(f.region, g.region), g.regionMax, "");
                }
                if (f == null) verdict = "FAIL";
                else {
                    floorText = String.format(Locale.ROOT, "%.3f %.2f %.5f %d", f.mean, f.p999, f.ssim, f.region);
                    verdict = m.withinFloor(f) ? "noise" : "FAIL";
                }
            }
            if (verdict.equals("FAIL")) fail = true;
            System.out.printf(Locale.ROOT, "%-16s %8.3f %8.2f %8.5f %8d %-16s %-6s %s%n", scene, m.mean, m.p999, m.ssim, m.region, m.where, verdict, floorText);
        }
        System.exit(fail ? 1 : 0);
    }

    /**
     * Temporal check (docs/GOAL.md, visual bar (b); round 11): two frame sequences with the same file names (zoomseq:
     * {@code <segment>_<NNN>.png}, frame NNN shows the same FOV on every run). Per frame the spatial metrics against the
     * reference, and per consecutive pair of a segment the "temporal error": CIEDE2000 between the candidate's frame t+1 and
     * the reference's frame t+1 carrying the candidate's error of frame t (ref[t+1] + cand[t] - ref[t], per sRGB channel).
     * A difference that stays put while the camera zooms gives ~0; one that appears, vanishes or jumps between frames
     * (popping, flicker) shows up in full. Both must meet the spatial bar (mean < 1, p99.9 < 3, no region >= 256 px at
     * panel resolution). Exit code 1 on any miss.
     */
    static double[] lastSummary = new double[6], lastCand;
    static int lastRegionMax = REGION_MAX;

    static boolean temporal(File ref, File cand, boolean verbose) throws Exception {
        String[] names = ref.list((d, n) -> n.endsWith(".png"));
        if (names == null || names.length < 2) throw new IllegalArgumentException("temporal comparison needs at least two reference frames");
        Arrays.sort(names);
        boolean fail = false;
        double worstMean = 0, worstP = 0, worstTMean = 0, worstTP = 0, worstSsim = 1;
        int worstRegion = 0, worstTRegion = 0, frames = 0, pairs = 0;
        float[][] prevR = null, prevC = null;
        String prevSeg = null;
        int w = 0, h = 0;
        for (String n : names) {
            File c = new File(cand, n);
            if (!c.exists()) { System.out.println(n + " MISSING"); fail = true; prevR = null; continue; }
            BufferedImage ia = ImageIO.read(new File(ref, n)), ib = ImageIO.read(c);
            boolean down = ia.getWidth() > PANEL_W;
            w = down ? PANEL_W : ia.getWidth(); h = down ? PANEL_H : ia.getHeight();
            float[][] a = resample(ia, w, h), b = resample(ib, w, h);
            int regionMax = (int) Math.round(REGION_MAX * ((double) w * h) / ((double) PANEL_W * PANEL_H));
            Metrics m = metrics(a, b, w, h, regionMax, true);
            frames++;
            worstMean = Math.max(worstMean, m.mean); worstP = Math.max(worstP, m.p999); worstSsim = Math.min(worstSsim, m.ssim); worstRegion = Math.max(worstRegion, m.region);
            String line = String.format(Locale.ROOT, "%-14s spatial %.3f %.2f %.5f %d", n.replace(".png", ""), m.mean, m.p999, m.ssim, m.region);
            if (!m.ok()) fail = true;
            String seg = temporalSegment(n);
            if (prevR != null && seg.equals(prevSeg)) {
                float[][] x = new float[3][w * h];
                for (int ch = 0; ch < 3; ch++) for (int i = 0; i < w * h; i++) x[ch][i] = Math.max(0f, Math.min(255f, a[ch][i] + prevC[ch][i] - prevR[ch][i]));
                Metrics t = metrics(x, b, w, h, regionMax, false);
                pairs++;
                worstTMean = Math.max(worstTMean, t.mean); worstTP = Math.max(worstTP, t.p999); worstTRegion = Math.max(worstTRegion, t.region);
                line += String.format(Locale.ROOT, "  temporal %.3f %.2f %d", t.mean, t.p999, t.region);
                if (!(t.meanOk() && t.p999Ok() && t.regionOk())) fail = true;
            }
            if (verbose) System.out.println(line + (m.ok() ? "" : "  SPATIAL-FAIL"));
            lastRegionMax = regionMax;
            prevR = a; prevC = b; prevSeg = seg;
        }
        if (pairs == 0) { fail = true; System.out.println("FAIL: no consecutive frames in a common temporal segment"); }
        lastSummary = new double[] { worstMean, worstP, worstRegion, worstTMean, worstTP, worstTRegion };
        if (verbose) lastCand = lastSummary.clone();
        System.out.printf(Locale.ROOT, (verbose ? "" : "  floor pair " + ref.getParentFile().getParentFile().getName() + " vs " + cand.getParentFile().getParentFile().getName() + ": ") + "%d frames: worst spatial mean %.3f p99.9 %.2f SSIM %.5f region %d; %d transitions: worst temporal mean %.3f p99.9 %.2f region %d -> %s%n",
            frames, worstMean, worstP, worstSsim, worstRegion, pairs, worstTMean, worstTP, worstTRegion, fail ? "FAIL" : "PASS");
        return fail;
    }

    static String temporalSegment(String name) {
        // ReuseSequence uses frame-000.png; ZoomSeq uses segment_000.png.
        return name.replaceFirst("[-_]\\d+\\.png$", "");
    }

    static Metrics metrics(float[][] a, float[][] b, int w, int h, int regionMax, boolean withSsim) {
        float[] de = deltaE(a, b, w * h);
        double mean = 0; int[] hist = new int[10001];
        for (float v : de) { mean += v; hist[Math.min(10000, (int) (v * 100))]++; }
        mean /= de.length;
        int[] box = new int[4];
        int region = largestRegion(de, w, h, box);
        return new Metrics(mean, percentile(hist, de.length, 0.999) / 100.0, withSsim ? ssim(luma(a), luma(b), w, h) : 1.0, region, regionMax, "");
    }

    static Metrics compare(File fa, File fb) throws Exception {
        BufferedImage ia = ImageIO.read(fa), ib = ImageIO.read(fb);
        if (ia.getWidth() != ib.getWidth() || ia.getHeight() != ib.getHeight())
            throw new IllegalArgumentException(fa + ": size " + ia.getWidth() + "x" + ia.getHeight() + " vs " + ib.getWidth() + "x" + ib.getHeight());
        boolean down = ia.getWidth() > PANEL_W;
        int w = down ? PANEL_W : ia.getWidth(), h = down ? PANEL_H : ia.getHeight();
        float[][] a = resample(ia, w, h), b = resample(ib, w, h);
        float[] de = deltaE(a, b, w * h);
        double mean = 0; int[] hist = new int[10001];
        for (float v : de) { mean += v; hist[Math.min(10000, (int) (v * 100))]++; }
        mean /= de.length;
        int regionMax = (int) Math.round(REGION_MAX * ((double) w * h) / ((double) PANEL_W * PANEL_H));
        int[] box = new int[4];
        int region = largestRegion(de, w, h, box);
        return new Metrics(mean, percentile(hist, de.length, 0.999) / 100.0, ssim(luma(a), luma(b), w, h), region, regionMax,
                           region == 0 ? "" : box[0] + "," + box[1] + " " + (box[2] - box[0] + 1) + "x" + (box[3] - box[1] + 1));
    }

    /** Area (box) resampling of the sRGB values to w x h; returns {r, g, b} planes in 0..255. */
    static float[][] resample(BufferedImage img, int w, int h) {
        int sw = img.getWidth(), sh = img.getHeight();
        int[] px = img.getRGB(0, 0, sw, sh, null, 0, sw);
        float[][] tmp = new float[3][w * sh];   // horizontal pass
        double sx = (double) sw / w, sy = (double) sh / h;
        IntStream.range(0, sh).parallel().forEach(y -> {
            for (int x = 0; x < w; x++) {
                double x0 = x * sx, x1 = x0 + sx, r = 0, g = 0, bl = 0;
                for (int i = (int) x0; i < Math.min(sw, (int) Math.ceil(x1)); i++) {
                    double wt = Math.min(x1, i + 1) - Math.max(x0, i);
                    int p = px[y * sw + i];
                    r += wt * ((p >> 16) & 255); g += wt * ((p >> 8) & 255); bl += wt * (p & 255);
                }
                tmp[0][y * w + x] = (float) (r / sx); tmp[1][y * w + x] = (float) (g / sx); tmp[2][y * w + x] = (float) (bl / sx);
            }
        });
        float[][] out = new float[3][w * h];   // vertical pass
        IntStream.range(0, h).parallel().forEach(y -> {
            double y0 = y * sy, y1 = y0 + sy;
            for (int ch = 0; ch < 3; ch++) {
                for (int x = 0; x < w; x++) {
                    double s = 0;
                    for (int j = (int) y0; j < Math.min(sh, (int) Math.ceil(y1)); j++) s += (Math.min(y1, j + 1) - Math.max(y0, j)) * tmp[ch][j * w + x];
                    out[ch][y * w + x] = (float) (s / sy);
                }
            }
        });
        return out;
    }

    static double lin(double v) { v /= 255.0; return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); }
    static double f(double t) { return t > 216.0 / 24389 ? Math.cbrt(t) : (24389.0 / 27 * t + 16) / 116; }

    static double[] lab(float r8, float g8, float b8) {
        double r = lin(r8), g = lin(g8), b = lin(b8);
        double X = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047;
        double Y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b;
        double Z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / 1.08883;
        double fx = f(X), fy = f(Y), fz = f(Z);
        return new double[] { 116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz) };
    }

    static float[] deltaE(float[][] a, float[][] b, int n) {
        float[] out = new float[n];
        IntStream.range(0, n).parallel().forEach(i -> {
            if (a[0][i] == b[0][i] && a[1][i] == b[1][i] && a[2][i] == b[2][i]) return;
            out[i] = (float) ciede2000(lab(a[0][i], a[1][i], a[2][i]), lab(b[0][i], b[1][i], b[2][i]));
        });
        return out;
    }

    /** CIEDE2000 (Sharma, Wu, Dalal 2005), kL = kC = kH = 1. */
    static double ciede2000(double[] p, double[] q) {
        double L1 = p[0], a1 = p[1], b1 = p[2], L2 = q[0], a2 = q[1], b2 = q[2];
        double C1 = Math.hypot(a1, b1), C2 = Math.hypot(a2, b2), Cm = (C1 + C2) / 2;
        double Cm7 = Math.pow(Cm, 7), G = 0.5 * (1 - Math.sqrt(Cm7 / (Cm7 + 6103515625.0)));
        double a1p = (1 + G) * a1, a2p = (1 + G) * a2;
        double C1p = Math.hypot(a1p, b1), C2p = Math.hypot(a2p, b2);
        double h1p = C1p == 0 ? 0 : Math.toDegrees(Math.atan2(b1, a1p)), h2p = C2p == 0 ? 0 : Math.toDegrees(Math.atan2(b2, a2p));
        if (h1p < 0) h1p += 360;
        if (h2p < 0) h2p += 360;
        double dLp = L2 - L1, dCp = C2p - C1p, dhp;
        if (C1p * C2p == 0) dhp = 0;
        else if (Math.abs(h2p - h1p) <= 180) dhp = h2p - h1p;
        else if (h2p - h1p > 180) dhp = h2p - h1p - 360;
        else dhp = h2p - h1p + 360;
        double dHp = 2 * Math.sqrt(C1p * C2p) * Math.sin(Math.toRadians(dhp / 2));
        double Lpm = (L1 + L2) / 2, Cpm = (C1p + C2p) / 2, hpm;
        if (C1p * C2p == 0) hpm = h1p + h2p;
        else if (Math.abs(h1p - h2p) <= 180) hpm = (h1p + h2p) / 2;
        else if (h1p + h2p < 360) hpm = (h1p + h2p + 360) / 2;
        else hpm = (h1p + h2p - 360) / 2;
        double T = 1 - 0.17 * Math.cos(Math.toRadians(hpm - 30)) + 0.24 * Math.cos(Math.toRadians(2 * hpm))
                 + 0.32 * Math.cos(Math.toRadians(3 * hpm + 6)) - 0.20 * Math.cos(Math.toRadians(4 * hpm - 63));
        double dTheta = 30 * Math.exp(-Math.pow((hpm - 275) / 25, 2));
        double Cpm7 = Math.pow(Cpm, 7), RC = 2 * Math.sqrt(Cpm7 / (Cpm7 + 6103515625.0));
        double Lm50 = (Lpm - 50) * (Lpm - 50);
        double SL = 1 + 0.015 * Lm50 / Math.sqrt(20 + Lm50), SC = 1 + 0.045 * Cpm, SH = 1 + 0.015 * Cpm * T;
        double RT = -Math.sin(Math.toRadians(2 * dTheta)) * RC;
        double l = dLp / SL, c = dCp / SC, h = dHp / SH;
        return Math.sqrt(l * l + c * c + h * h + RT * c * h);
    }

    /** CIEDE2000 against pairs of Sharma's test data; SSIM of an image with itself; sRGB white -> L 100. */
    static void selftest() {
        double[][] t = {
            { 50, 2.6772, -79.7751, 50, 0, -82.7485, 2.0425 }, { 50, 0, 0, 50, -1, 2, 2.3669 },
            { 50, 2.49, -0.001, 50, -2.49, 0.0011, 7.2195 }, { 50, 2.5, 0, 73, 25, -18, 27.1492 },
            { 60.2574, -34.0099, 36.2677, 60.4626, -34.1751, 39.4387, 1.2644 }, { 6.7747, -0.2908, -2.4247, 5.8714, -0.0985, -2.2286, 0.6377 },
            { 2.0776, 0.0795, -1.135, 0.9033, -0.0636, -0.5514, 0.9082 } };
        int bad = 0;
        if (!temporalSegment("frame-000.png").equals(temporalSegment("frame-001.png"))
            || !temporalSegment("zoom_in_000.png").equals(temporalSegment("zoom_in_001.png"))
            || temporalSegment("zoom_in_000.png").equals(temporalSegment("zoom_out_001.png"))) {
            bad++; System.out.println("temporal segment grouping FAILED");
        }
        for (double[] r : t) {
            double d = ciede2000(new double[] { r[0], r[1], r[2] }, new double[] { r[3], r[4], r[5] });
            if (Math.abs(d - r[6]) > 1e-4) { bad++; System.out.printf(Locale.ROOT, "CIEDE2000 %.4f != %.4f%n", d, r[6]); }
        }
        double[] white = lab(255, 255, 255);
        if (Math.abs(white[0] - 100) > 1e-3 || Math.abs(white[1]) > 1e-2 || Math.abs(white[2]) > 1e-2) { bad++; System.out.println("white " + Arrays.toString(white)); }
        float[] img = new float[64 * 64];
        for (int i = 0; i < img.length; i++) img[i] = (i * 37) % 255;
        if (Math.abs(ssim(img, img, 64, 64) - 1) > 1e-6) { bad++; System.out.println("SSIM(x, x) != 1"); }
        System.out.println(bad == 0 ? "selftest ok" : "selftest FAILED");
        System.exit(bad == 0 ? 0 : 1);
    }

    static double percentile(int[] hist, int n, double q) {
        long target = (long) Math.ceil(q * n), acc = 0;
        for (int i = 0; i < hist.length; i++) { acc += hist[i]; if (acc >= target) return i; }
        return hist.length - 1;
    }

    static float[] luma(float[][] c) {
        float[] y = new float[c[0].length];
        for (int i = 0; i < y.length; i++) y[i] = 0.2126f * c[0][i] + 0.7152f * c[1][i] + 0.0722f * c[2][i];
        return y;
    }

    static float[] blur(float[] src, int w, int h, float[] k) {
        int r = k.length / 2;
        float[] tmp = new float[src.length], out = new float[src.length];
        IntStream.range(0, h).parallel().forEach(y -> {
            for (int x = 0; x < w; x++) {
                float s = 0;
                for (int i = -r; i <= r; i++) s += k[i + r] * src[y * w + Math.max(0, Math.min(w - 1, x + i))];
                tmp[y * w + x] = s;
            }
        });
        IntStream.range(0, h).parallel().forEach(y -> {
            for (int x = 0; x < w; x++) {
                float s = 0;
                for (int i = -r; i <= r; i++) s += k[i + r] * tmp[Math.max(0, Math.min(h - 1, y + i)) * w + x];
                out[y * w + x] = s;
            }
        });
        return out;
    }

    static double ssim(float[] x, float[] y, int w, int h) {
        float[] k = new float[11]; float ks = 0;
        for (int i = 0; i < 11; i++) { k[i] = (float) Math.exp(-((i - 5) * (i - 5)) / (2 * 1.5 * 1.5)); ks += k[i]; }
        for (int i = 0; i < 11; i++) k[i] /= ks;
        int n = x.length;
        float[] xx = new float[n], yy = new float[n], xy = new float[n];
        for (int i = 0; i < n; i++) { xx[i] = x[i] * x[i]; yy[i] = y[i] * y[i]; xy[i] = x[i] * y[i]; }
        float[] mx = blur(x, w, h, k), my = blur(y, w, h, k), sxx = blur(xx, w, h, k), syy = blur(yy, w, h, k), sxy = blur(xy, w, h, k);
        double C1 = Math.pow(0.01 * 255, 2), C2 = Math.pow(0.03 * 255, 2);
        return IntStream.range(0, n).parallel().mapToDouble(i -> {
            double ux = mx[i], uy = my[i];
            double vx = sxx[i] - ux * ux, vy = syy[i] - uy * uy, cxy = sxy[i] - ux * uy;
            return ((2 * ux * uy + C1) * (2 * cxy + C2)) / ((ux * ux + uy * uy + C1) * (vx + vy + C2));
        }).sum() / n;
    }

    /** Largest 8-connected region of dE > REGION_DE; box receives its bounds (x0, y0, x1, y1; y down). */
    static int largestRegion(float[] de, int w, int h, int[] box) {
        boolean[] seen = new boolean[de.length];
        int best = 0;
        ArrayDeque<Integer> q = new ArrayDeque<>();
        for (int s = 0; s < de.length; s++) {
            if (seen[s] || de[s] <= REGION_DE) continue;
            int size = 0, x0 = w, y0 = h, x1 = 0, y1 = 0; seen[s] = true; q.add(s);
            while (!q.isEmpty()) {
                int p = q.poll(); size++;
                int px = p % w, py = p / w;
                x0 = Math.min(x0, px); y0 = Math.min(y0, py); x1 = Math.max(x1, px); y1 = Math.max(y1, py);
                for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                    int nx = px + dx, ny = py + dy;
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                    int np = ny * w + nx;
                    if (!seen[np] && de[np] > REGION_DE) { seen[np] = true; q.add(np); }
                }
            }
            if (size > best) { best = size; box[0] = x0; box[1] = y0; box[2] = x1; box[3] = y1; }
        }
        return best;
    }
}
