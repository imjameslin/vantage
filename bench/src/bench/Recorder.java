package bench;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayDeque;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;

/**
 * Flash hunting (not a measurement): -Dbench.rec=N records N consecutive frames of a screen band during MEASURE without
 * stalling the frame: the band (rows bench.rec.y0..y1 as fractions of the height, full width) is copied on the GPU into one of
 * a few pooled buffers, read back at 1/2 resolution in the copy's completion callback and kept in memory; a frame with no free
 * buffer is skipped (counted). bench.rec.start = seconds into MEASURE. When done, the frames are written as
 * bench.shots/screenshots/rec-<label>-NNNN.png with a frame-index/time list (rec-<label>.txt).
 * bench.rec.reps=R / bench.rec.every=S: R windows of N frames, starting every S seconds (frame index = window * N + i).
 */
final class Recorder {
    static final int COUNT = Integer.getInteger("bench.rec", 0);
    static final double START_S = Double.parseDouble(System.getProperty("bench.rec.start", "0"));
    static final double Y0 = Double.parseDouble(System.getProperty("bench.rec.y0", "0.30"));
    static final double Y1 = Double.parseDouble(System.getProperty("bench.rec.y1", "0.80"));
    static final int REPS = Integer.getInteger("bench.rec.reps", 1);
    static final double EVERY_S = Double.parseDouble(System.getProperty("bench.rec.every", "10"));
    private static final int POOL = 6;
    private static final ArrayDeque<GpuBuffer> free = new ArrayDeque<>();
    private static int[][] frames;
    private static long[] times;
    private static int requested, completed, skipped;
    private static long startNs;
    private static boolean written;
    private static int w, h, bx, by, bw, bh;

    private Recorder() {
    }

    static void measure(Minecraft mc, long now, String label) {
        if (COUNT <= 0 || written) return;
        if (startNs == 0L) startNs = now;
        int window = requested / COUNT;
        if (window < REPS && requested % COUNT == 0 && (now - startNs) / 1e9 < START_S + window * EVERY_S) return;
        if (requested >= COUNT * REPS) {
            if (completed >= requested) write(label);
            return;
        }
        GpuTexture tex = mc.gameRenderer.mainRenderTarget().getColorTexture();
        if (tex == null) return;
        if (frames == null) {
            w = tex.getWidth(0);
            h = tex.getHeight(0);
            // Texture rows are bottom-up: screen band [Y0, Y1) is texture rows [h - Y1 h, h - Y0 h).
            bx = 0;
            bw = w;
            by = (int)(h - Y1 * h);
            bh = (int)(h - Y0 * h) - by;
            frames = new int[COUNT * REPS][];
            times = new long[COUNT * REPS];
            for (int i = 0; i < POOL; i++) {
                free.add(RenderSystem.getDevice().createBuffer(() -> "bench recorder", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, 4L * bw * bh));
            }
        }
        GpuBuffer buf = free.poll();
        if (buf == null) {
            skipped++;
            return;
        }
        int index = requested++;
        times[index] = now - startNs;
        RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(tex, buf, 0L, () -> {
            int ow = bw / 2, oh = bh / 2;
            int[] out = new int[ow * oh];
            try (GpuBufferSlice.MappedView read = buf.map(true, false)) {
                var data = read.data();
                for (int y = 0; y < oh; y++) {
                    int row = (bh - 1 - 2 * y) * bw;   // flip to screen order
                    for (int x = 0; x < ow; x++) {
                        out[y * ow + x] = data.getInt((row + 2 * x) * 4);
                    }
                }
            }
            frames[index] = out;
            completed++;
            free.add(buf);
        }, 0, bx, by, bw, bh);
    }

    private static void write(String label) {
        written = true;
        for (GpuBuffer b : free) b.close();
        free.clear();
        int[][] fs = frames;
        long[] ts = times;
        int ow = bw / 2, oh = bh / 2;
        int skips = skipped;
        String dir = System.getProperty("bench.shots", ".");
        Thread t = new Thread(() -> {
            try {
                File shots = new File(dir, "screenshots");
                shots.mkdirs();
                StringBuilder list = new StringBuilder("skipped " + skips + " startNs " + startNs + "\n");
                for (int i = 0; i < fs.length; i++) {
                    if (fs[i] == null) continue;
                    BufferedImage im = new BufferedImage(ow, oh, BufferedImage.TYPE_INT_RGB);
                    for (int p = 0; p < fs[i].length; p++) {
                        int abgr = fs[i][p];
                        im.setRGB(p % ow, p / ow, (abgr & 0xFF) << 16 | (abgr & 0xFF00) | (abgr >> 16 & 0xFF));
                    }
                    ImageIO.write(im, "png", new File(shots, String.format("rec-%s-%04d.png", label, i)));
                    list.append(i).append(' ').append(ts[i] / 1000).append('\n');
                }
                java.nio.file.Files.writeString(new File(shots, "rec-" + label + ".txt").toPath(), list);
                System.out.println("[bench] recorder: wrote " + fs.length + " frames, skipped " + skips);
            } catch (Exception e) {
                System.out.println("[bench] recorder failed: " + e);
            }
        }, "bench-recorder");
        t.start();
    }
}
