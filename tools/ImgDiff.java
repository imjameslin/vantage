import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Arrays;
import java.util.Locale;
import javax.imageio.ImageIO;

/**
 * Compare two screenshot folders scene by scene.
 *
 *   java tools/ImgDiff.java <reference dir> <candidate dir> <heatmap out dir>
 *
 * For each PNG present in both: mean absolute error (0-255), percentage of pixels whose
 * largest channel difference exceeds 16 and 48, and a heatmap PNG (black = identical,
 * red intensity = max channel delta). Prints a table; exit code 1 if any scene fails.
 *
 * Pass bar, calibrated against vanilla Vulkan vs itself and vanilla OpenGL vs Vulkan:
 * at most 0.1% of pixels may differ by more than 48 levels, except in scenes with
 * inherent randomness (particle spawn positions, mob idle animation, water flow),
 * which may reach 2.5%.
 */
public class ImgDiff {
    public static void main(String[] args) throws Exception {
        File ref = new File(args[0]), cand = new File(args[1]), out = new File(args[2]);
        out.mkdirs();
        String[] names = ref.list((d, n) -> n.endsWith(".png"));
        Arrays.sort(names);
        boolean fail = false;
        System.out.printf("%-16s %8s %9s %9s  %s%n", "scene", "MAE", ">16 (%)", ">48 (%)", "verdict");
        for (String n : names) {
            File c = new File(cand, n);
            if (!c.exists()) {
                System.out.printf("%-16s %8s%n", n.replace(".png", ""), "MISSING");
                fail = true;
                continue;
            }
            BufferedImage a = ImageIO.read(new File(ref, n)), b = ImageIO.read(c);
            if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
                System.out.printf("%-16s size mismatch %dx%d vs %dx%d%n", n, a.getWidth(), a.getHeight(), b.getWidth(), b.getHeight());
                fail = true;
                continue;
            }
            int w = a.getWidth(), h = a.getHeight();
            BufferedImage heat = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            long sum = 0, over16 = 0, over48 = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int p = a.getRGB(x, y), q = b.getRGB(x, y);
                    int dr = Math.abs(((p >> 16) & 255) - ((q >> 16) & 255));
                    int dg = Math.abs(((p >> 8) & 255) - ((q >> 8) & 255));
                    int db = Math.abs((p & 255) - (q & 255));
                    int m = Math.max(dr, Math.max(dg, db));
                    sum += dr + dg + db;
                    if (m > 16) over16++;
                    if (m > 48) over48++;
                    int v = Math.min(255, m * 4);
                    heat.setRGB(x, y, (v << 16) | ((v / 4) << 8));
                }
            }
            double px = (double) w * h;
            double p48 = 100.0 * over48 / px;
            boolean noisy = n.startsWith("particles") || n.contains("entities") || n.startsWith("water_surface");
            boolean bad = p48 > (noisy ? 2.5 : 0.1);
            fail |= bad;
            System.out.printf(Locale.ROOT, "%-16s %8.2f %9.3f %9.3f  %s%n", n.replace(".png", ""), sum / (px * 3), 100.0 * over16 / px, p48, bad ? "CHECK" : "ok");
            ImageIO.write(heat, "png", new File(out, n));
        }
        System.exit(fail ? 1 : 0);
    }
}
