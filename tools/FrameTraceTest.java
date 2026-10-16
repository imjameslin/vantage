import bench.FrameProbe;
import bench.TraceSelfTest;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.metal.MetalBackend;
import com.mojang.renderpearl.backend.metal.MetalFrameTrace;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.joml.Vector4f;

/**
 * Headless end-to-end test of the bench frame trace on the real Metal backend (CONTRACT.md 16.R5-B): the real
 * FrontendGpuDevice / MetalDevice / encoder / libmcmetal from build/client.jar with -Dmcmetal.frameTrace=true, driven by
 * the bench's FrameTrace (bench classes on the classpath) the way Bench.onFrame drives it. No window, no surface.
 *
 * <p>Checks: the first commit installs the native ring; the bench finds the backend through RenderSystem and the
 * counters array; each traced frame has one FRAME_END commit (consecutive seq), native submit time, encoders, a
 * throttle wait column; after the run the native ring merges into every row: GPU start / end / busy in the
 * System.nanoTime base (the GPU starts after the frame's commit and ends before the next rows' end + slack; the commit
 * time lies inside the row's interval), command buffers committed = completed, no PRESENT; MetalFrameTrace.nativeConfig
 * and nativeCounters work. Writes the result JSON (args[1]) for tools/frame-trace-test.sh's analyzer checks.
 *
 * <p>Usage: tools/frame-trace-test.sh (builds nothing; run tools/build-client.sh and tools/build-bench.sh first).
 */
public class FrameTraceTest {
    private static int checks;
    private static int failures;

    private static void check(final boolean ok, final String what) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("  FAIL: " + what);
        }
    }

    private static void spin(final long ns) {
        long end = System.nanoTime() + ns;
        while (System.nanoTime() < end) {
            Thread.onSpinWait();
        }
    }

    public static void main(final String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        check(MetalFrameTrace.ENABLED, "run with -Dmcmetal.frameTrace=true");
        check(Boolean.getBoolean("bench.frameTrace"), "run with -Dbench.frameTrace=true");
        MetalBackend backend = new MetalBackend();
        backend.loadLibrary();
        GpuDevice device = backend.createDevice(new GpuDebugOptions(0, false, false, true));
        Field field = RenderSystem.class.getDeclaredField("DEVICE");
        field.setAccessible(true);
        field.set(null, device);
        GpuTexture target = device.createTexture("frametrace target", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC
            | GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM, 2048, 2048, 1, 1);
        CommandEncoder enc = device.createCommandEncoder();
        for (int i = 0; i < 3; i++) {
            enc.clearColorTexture(target, new Vector4f(0.1F * i, 0.2F, 0.3F, 1.0F));
            enc.submit();
        }
        System.out.println("trace status: " + MetalFrameTrace.status());
        check(MetalFrameTrace.nativeRing(), "native ring installed by the first commit (" + MetalFrameTrace.status() + ")");

        int frames = 300;
        Vector4f[] colors = new Vector4f[8];
        for (int i = 0; i < colors.length; i++) {
            colors[i] = new Vector4f(i / 8.0F, 1.0F - i / 8.0F, 0.5F, 1.0F);
        }
        long frames0 = MetalFrameTrace.C[MetalFrameTrace.FRAMES];
        TraceSelfTest.begin(1.0);
        check(TraceSelfTest.metalLive(), "bench sees the live Metal counters");
        // bench.SlowFrame JFR events (run with -Dbench.frameTrace.jfrSlowMs=0.25: the tick frames spin 0.3 ms).
        jdk.jfr.Recording recording = new jdk.jfr.Recording();
        recording.enable("bench.SlowFrame");
        recording.start();
        for (int f = 0; f < frames; f++) {
            FrameProbe.enter(FrameProbe.RENDER);
            for (int c = 0; c < 6; c++) {
                enc.clearColorTexture(target, colors[(f + c) % colors.length]);
            }
            FrameProbe.exit(FrameProbe.RENDER);
            FrameProbe.enter(FrameProbe.SUBMIT);
            enc.submit();
            FrameProbe.exit(FrameProbe.SUBMIT);
            FrameProbe.ticks(f % 10 == 0 ? 1 : 0);
            if (f % 10 == 0) {
                FrameProbe.enter(FrameProbe.TICK);
                spin(300_000L);
                FrameProbe.exit(FrameProbe.TICK);
            }
            FrameProbe.bundleEnd();
            TraceSelfTest.frame(f);
        }
        check(MetalFrameTrace.C[MetalFrameTrace.FRAMES] - frames0 == frames, "one FRAME_END commit per frame");
        recording.stop();
        Path jfr = Path.of("slowframes.jfr").toAbsolutePath();
        recording.dump(jfr);
        recording.close();
        java.util.List<jdk.jfr.consumer.RecordedEvent> events = jdk.jfr.consumer.RecordingFile.readAllEvents(jfr);
        long thresholdNs = (long) (Double.parseDouble(System.getProperty("bench.frameTrace.jfrSlowMs", "4")) * 1e6);
        int slowRows = 0;
        for (int k = 0; k < TraceSelfTest.rows(); k++) {
            if (TraceSelfTest.value("dtNs", k) >= thresholdNs) {
                slowRows++;
            }
        }
        boolean fieldsOk = !events.isEmpty();
        for (jdk.jfr.consumer.RecordedEvent e : events) {
            int row = e.getInt("row");
            fieldsOk &= e.getEventType().getName().equals("bench.SlowFrame") && e.getLong("interval") == TraceSelfTest.value("dtNs", row)
                && e.getLong("seq") == TraceSelfTest.value("seq", row) && e.getInt("ticks") == TraceSelfTest.value("ticks", row);
        }
        System.out.println("JFR: " + events.size() + " bench.SlowFrame events, " + slowRows + " rows >= " + thresholdNs + " ns");
        check(events.size() == slowRows && slowRows >= 25, "one bench.SlowFrame event per slow row (" + events.size() + " vs " + slowRows + ")");
        check(fieldsOk, "bench.SlowFrame fields equal the trace rows");
        Thread.sleep(100L);   // let the last frames' completion handlers run
        String json = TraceSelfTest.resultJson("frametrace-e2e");

        check(TraceSelfTest.rows() == frames, "rows " + TraceSelfTest.rows());
        int gpuRows = 0, bad = 0;
        long prevSeq = -1L;
        for (int k = 0; k < frames; k++) {
            long seq = TraceSelfTest.value("seq", k);
            long t = TraceSelfTest.value("tNs", k);
            long tPrev = k == 0 ? 0L : TraceSelfTest.value("tNs", k - 1);
            long start = TraceSelfTest.value("gpuStartNs", k);
            long end = TraceSelfTest.value("gpuEndNs", k);
            long commit = TraceSelfTest.value("commitNs", k);
            long slack = 200_000L;
            boolean ok = (prevSeq < 0L || seq == prevSeq + 1L) && TraceSelfTest.value("commits", k) == 1L
                && TraceSelfTest.value("nativeSubmitNs", k) > 0L && TraceSelfTest.value("waitNs", k) >= 0L
                && TraceSelfTest.value("encoders", k) >= 1L && TraceSelfTest.value("drainNs", k) >= 0L
                && TraceSelfTest.value("resultFlags", k) >= 1L && TraceSelfTest.value("presentKind", k) == 0L
                && TraceSelfTest.value("drawableWaitNs", k) == 0L && TraceSelfTest.value("blocksCreated", k) >= 0L
                && commit >= tPrev - slack && commit <= t + slack;
            if (start >= 0L) {
                gpuRows++;
                ok &= end > start && start >= commit - slack && TraceSelfTest.value("gpuBusyNs", k) > 0L
                    && TraceSelfTest.value("cbs", k) >= 1L && TraceSelfTest.value("cbsDone", k) == TraceSelfTest.value("cbs", k);
            }
            if (!ok) {
                bad++;
                if (bad <= 5) {
                    System.out.printf("  row %d: seq %d commits %d native %d wait %d enc %d flags %d pk %d dw %d commit %d [%d, %d] gpu [%d, %d] busy %d cbs %d/%d%n",
                        k, seq, TraceSelfTest.value("commits", k), TraceSelfTest.value("nativeSubmitNs", k), TraceSelfTest.value("waitNs", k),
                        TraceSelfTest.value("encoders", k), TraceSelfTest.value("resultFlags", k), TraceSelfTest.value("presentKind", k),
                        TraceSelfTest.value("drawableWaitNs", k), commit, tPrev, t, start, end, TraceSelfTest.value("gpuBusyNs", k),
                        TraceSelfTest.value("cbs", k), TraceSelfTest.value("cbsDone", k));
                }
            }
            prevSeq = seq;
        }
        check(bad == 0, bad + " rows inconsistent");
        check(gpuRows == frames, "GPU columns merged for " + gpuRows + " of " + frames + " rows");
        long[] counters = MetalFrameTrace.nativeCounters(null);
        check(counters != null && counters.length == 40, "nativeCounters: " + (counters == null ? "null" : counters.length));
        String config = MetalFrameTrace.nativeConfig(null);
        check(config != null && config.contains("\"fuseMode\"") && config.contains("\"counterNames\""), "nativeConfig: " + config);
        System.out.println("clock: " + MetalFrameTrace.clockJson());
        if (args.length > 0) {
            Files.writeString(Path.of(args[0]), json);
            System.out.println("result JSON: " + args[0] + " (" + json.length() + " chars)");
        }
        device.close();
        System.out.printf("FrameTraceTest: %d checks, %d failures%n", checks, failures);
        System.exit(failures == 0 ? 0 : 1);
    }
}
