import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import javax.imageio.ImageIO;

/**
 * (Round-4 merge: this is task V's variant of tools/PerceptualDiff.java, kept so results/S5-COMPACT.md stays
 * reproducible; it always resamples bilinearly to 3024x1964 and uses a 1000 px region bar. tools/PerceptualDiff.java
 * (area-resample, 256 px region bar, --floor, --selftest) is the canonical tool.)
 * Perceptual scene comparison for the visual bar "indistinguishable to the naked eye" (docs/GOAL.md, amendment of
 * 2026-10-04): both screenshots are resampled (bilinear) to the panel's physical resolution 3024x1964, then per scene
 * <ul>
 *   <li>CIEDE2000 colour difference per pixel (sRGB, D65 Lab): mean (bar &lt; 1.0) and 99.9th percentile (bar &lt; 3.0);</li>
 *   <li>SSIM of the luma (11x11 Gaussian window, sigma 1.5, K1 0.01, K2 0.03; bar &gt;= 0.99);</li>
 *   <li>the largest 8-connected region of pixels with dE00 &gt; 10 (a missing or changed object; bar &lt; 1000 px, about a
 *       32x32 px patch at panel resolution).</li>
 * </ul>
 * Usage: java tools/PerceptualDiffUpsample.java <dirA> <dirB>   (same file names; prints one row per scene, exit 1 if any
 * scene misses a bar). The strict harness (ImgDiff against the vanilla reference) stays the regression detector.
 */
public final class PerceptualDiffUpsample {
    static final int PW = 3024;
    static final int PH = 1964;

    public static void main(final String[] args) throws Exception {
        File a = new File(args[0]);
        File b = new File(args[1]);
        String[] names = a.list((d, n) -> n.endsWith(".png"));
        Arrays.sort(names);
        System.out.printf("%-16s %8s %8s %8s %9s  %s%n", "scene", "meanDE", "p99.9DE", "SSIM", "region", "verdict");
        boolean allOk = true;
        for (String n : names) {
            File fb = new File(b, n);
            if (!fb.exists()) {
                System.out.printf("%-16s MISSING%n", n);
                allOk = false;
                continue;
            }
            float[] x = resample(ImageIO.read(new File(a, n)));
            float[] y = resample(ImageIO.read(fb));
            float[] de = new float[PW * PH];
            float[] lx = lab(x);
            float[] ly = lab(y);
            double sum = 0;
            for (int i = 0; i < PW * PH; i++) {
                de[i] = (float)ciede2000(lx[3 * i], lx[3 * i + 1], lx[3 * i + 2], ly[3 * i], ly[3 * i + 1], ly[3 * i + 2]);
                sum += de[i];
            }
            double mean = sum / (PW * PH);
            float[] sorted = de.clone();
            Arrays.sort(sorted);
            double p999 = sorted[(int)Math.min(sorted.length - 1, Math.floor(0.999 * sorted.length))];
            double ssim = ssim(luma(x), luma(y));
            int region = largestRegion(de, 10.0f);
            boolean ok = mean < 1.0 && p999 < 3.0 && ssim >= 0.99 && region < 1000;
            allOk &= ok;
            System.out.printf("%-16s %8.3f %8.3f %8.5f %9d  %s%n", n.replace(".png", ""), mean, p999, ssim, region, ok ? "ok" : "VISIBLE");
        }
        System.exit(allOk ? 0 : 1);
    }

    /** Bilinear resample to PW x PH, RGB in [0, 1] (sRGB encoded), interleaved. */
    static float[] resample(final BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        float[] out = new float[PW * PH * 3];
        for (int j = 0; j < PH; j++) {
            double sy = Math.max(0, Math.min(h - 1.0, (j + 0.5) * h / PH - 0.5));
            int y0 = (int)sy;
            int y1 = Math.min(h - 1, y0 + 1);
            double fy = sy - y0;
            for (int i = 0; i < PW; i++) {
                double sx = Math.max(0, Math.min(w - 1.0, (i + 0.5) * w / PW - 0.5));
                int x0 = (int)sx;
                int x1 = Math.min(w - 1, x0 + 1);
                double fx = sx - x0;
                for (int c = 0; c < 3; c++) {
                    int sh = 16 - 8 * c;
                    double v00 = px[y0 * w + x0] >> sh & 255;
                    double v01 = px[y0 * w + x1] >> sh & 255;
                    double v10 = px[y1 * w + x0] >> sh & 255;
                    double v11 = px[y1 * w + x1] >> sh & 255;
                    double v = (v00 * (1 - fx) + v01 * fx) * (1 - fy) + (v10 * (1 - fx) + v11 * fx) * fy;
                    out[3 * (j * PW + i) + c] = (float)(v / 255.0);
                }
            }
        }
        return out;
    }

    static double lin(final double c) {
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    static double f(final double t) {
        return t > 216.0 / 24389.0 ? Math.cbrt(t) : (24389.0 / 27.0 * t + 16.0) / 116.0;
    }

    /** sRGB -> CIE Lab (D65). */
    static float[] lab(final float[] rgb) {
        float[] out = new float[rgb.length];
        double[] lut = new double[4096];
        for (int i = 0; i < lut.length; i++) {
            lut[i] = lin(i / 4095.0);
        }
        for (int i = 0; i < rgb.length; i += 3) {
            double r = lut[Math.round(rgb[i] * 4095)];
            double g = lut[Math.round(rgb[i + 1] * 4095)];
            double b = lut[Math.round(rgb[i + 2] * 4095)];
            double x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047;
            double y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b;
            double z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / 1.08883;
            double fx = f(x);
            double fy = f(y);
            double fz = f(z);
            out[i] = (float)(116 * fy - 16);
            out[i + 1] = (float)(500 * (fx - fy));
            out[i + 2] = (float)(200 * (fy - fz));
        }
        return out;
    }

    /** CIEDE2000 (Sharma, Wu, Dalal 2005), kL = kC = kH = 1. */
    static double ciede2000(final double l1, final double a1, final double b1, final double l2, final double a2, final double b2) {
        if (l1 == l2 && a1 == a2 && b1 == b2) {
            return 0.0;
        }
        double c1 = Math.hypot(a1, b1);
        double c2 = Math.hypot(a2, b2);
        double cm = (c1 + c2) / 2;
        double cm7 = Math.pow(cm, 7);
        double g = 0.5 * (1 - Math.sqrt(cm7 / (cm7 + 6103515625.0)));
        double a1p = (1 + g) * a1;
        double a2p = (1 + g) * a2;
        double c1p = Math.hypot(a1p, b1);
        double c2p = Math.hypot(a2p, b2);
        double h1p = c1p == 0 ? 0 : Math.toDegrees(Math.atan2(b1, a1p));
        if (h1p < 0) {
            h1p += 360;
        }
        double h2p = c2p == 0 ? 0 : Math.toDegrees(Math.atan2(b2, a2p));
        if (h2p < 0) {
            h2p += 360;
        }
        double dLp = l2 - l1;
        double dCp = c2p - c1p;
        double dhp;
        if (c1p * c2p == 0) {
            dhp = 0;
        } else if (Math.abs(h2p - h1p) <= 180) {
            dhp = h2p - h1p;
        } else if (h2p - h1p > 180) {
            dhp = h2p - h1p - 360;
        } else {
            dhp = h2p - h1p + 360;
        }
        double dHp = 2 * Math.sqrt(c1p * c2p) * Math.sin(Math.toRadians(dhp / 2));
        double lpm = (l1 + l2) / 2;
        double cpm = (c1p + c2p) / 2;
        double hpm;
        if (c1p * c2p == 0) {
            hpm = h1p + h2p;
        } else if (Math.abs(h1p - h2p) <= 180) {
            hpm = (h1p + h2p) / 2;
        } else if (h1p + h2p < 360) {
            hpm = (h1p + h2p + 360) / 2;
        } else {
            hpm = (h1p + h2p - 360) / 2;
        }
        double t = 1 - 0.17 * Math.cos(Math.toRadians(hpm - 30)) + 0.24 * Math.cos(Math.toRadians(2 * hpm))
            + 0.32 * Math.cos(Math.toRadians(3 * hpm + 6)) - 0.20 * Math.cos(Math.toRadians(4 * hpm - 63));
        double dTheta = 30 * Math.exp(-Math.pow((hpm - 275) / 25, 2));
        double cpm7 = Math.pow(cpm, 7);
        double rc = 2 * Math.sqrt(cpm7 / (cpm7 + 6103515625.0));
        double sl = 1 + 0.015 * Math.pow(lpm - 50, 2) / Math.sqrt(20 + Math.pow(lpm - 50, 2));
        double sc = 1 + 0.045 * cpm;
        double sh = 1 + 0.015 * cpm * t;
        double rt = -Math.sin(Math.toRadians(2 * dTheta)) * rc;
        double tl = dLp / sl;
        double tc = dCp / sc;
        double th = dHp / sh;
        return Math.sqrt(tl * tl + tc * tc + th * th + rt * tc * th);
    }

    static float[] luma(final float[] rgb) {
        float[] out = new float[PW * PH];
        for (int i = 0; i < out.length; i++) {
            out[i] = 255f * (0.299f * rgb[3 * i] + 0.587f * rgb[3 * i + 1] + 0.114f * rgb[3 * i + 2]);
        }
        return out;
    }

    /** Separable 11-tap Gaussian (sigma 1.5), clamped edges. */
    static float[] blur(final float[] src) {
        double[] k = new double[11];
        double s = 0;
        for (int i = 0; i < 11; i++) {
            k[i] = Math.exp(-((i - 5) * (i - 5)) / (2 * 1.5 * 1.5));
            s += k[i];
        }
        for (int i = 0; i < 11; i++) {
            k[i] /= s;
        }
        float[] tmp = new float[src.length];
        float[] out = new float[src.length];
        for (int j = 0; j < PH; j++) {
            for (int i = 0; i < PW; i++) {
                double acc = 0;
                for (int t = 0; t < 11; t++) {
                    int x = Math.max(0, Math.min(PW - 1, i + t - 5));
                    acc += k[t] * src[j * PW + x];
                }
                tmp[j * PW + i] = (float)acc;
            }
        }
        for (int j = 0; j < PH; j++) {
            for (int i = 0; i < PW; i++) {
                double acc = 0;
                for (int t = 0; t < 11; t++) {
                    int y = Math.max(0, Math.min(PH - 1, j + t - 5));
                    acc += k[t] * tmp[y * PW + i];
                }
                out[j * PW + i] = (float)acc;
            }
        }
        return out;
    }

    static double ssim(final float[] x, final float[] y) {
        int n = x.length;
        float[] xx = new float[n];
        float[] yy = new float[n];
        float[] xy = new float[n];
        for (int i = 0; i < n; i++) {
            xx[i] = x[i] * x[i];
            yy[i] = y[i] * y[i];
            xy[i] = x[i] * y[i];
        }
        float[] mx = blur(x);
        float[] my = blur(y);
        float[] sxx = blur(xx);
        float[] syy = blur(yy);
        float[] sxy = blur(xy);
        double c1 = Math.pow(0.01 * 255, 2);
        double c2 = Math.pow(0.03 * 255, 2);
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double ux = mx[i];
            double uy = my[i];
            double vx = Math.max(0, sxx[i] - ux * ux);
            double vy = Math.max(0, syy[i] - uy * uy);
            double cxy = sxy[i] - ux * uy;
            sum += ((2 * ux * uy + c1) * (2 * cxy + c2)) / ((ux * ux + uy * uy + c1) * (vx + vy + c2));
        }
        return sum / n;
    }

    /** Area of the largest 8-connected region of pixels whose dE00 exceeds {@code threshold}. */
    static int largestRegion(final float[] de, final float threshold) {
        int n = de.length;
        boolean[] seen = new boolean[n];
        int[] stack = new int[n];
        int best = 0;
        for (int start = 0; start < n; start++) {
            if (seen[start] || de[start] <= threshold) {
                continue;
            }
            int top = 0;
            stack[top++] = start;
            seen[start] = true;
            int area = 0;
            while (top > 0) {
                int p = stack[--top];
                area++;
                int px = p % PW;
                int py = p / PW;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int qx = px + dx;
                        int qy = py + dy;
                        if (qx < 0 || qy < 0 || qx >= PW || qy >= PH) {
                            continue;
                        }
                        int q = qy * PW + qx;
                        if (!seen[q] && de[q] > threshold) {
                            seen[q] = true;
                            stack[top++] = q;
                        }
                    }
                }
            }
            best = Math.max(best, area);
        }
        return best;
    }
}
