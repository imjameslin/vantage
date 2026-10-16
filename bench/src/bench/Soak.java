package bench;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.DeviceInfo;
import java.io.IOException;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Soak mode, {@code -Dbench.mode=soak} (docs/GOAL.md stage 1: "30-minute soak (flying, chunk loading, resizing, fullscreen
 * toggling, joining a LAN world) without crashes or memory growth"). Runs in a fresh singleplayer world, or with
 * {@code -Dbench.join=<host:port>} as a multiplayer guest that joined through {@code --quickPlayMultiplayer}
 * (tools/soak-lan.sh). F3 is never opened; nothing a server can observe differs from a player flying around (creative
 * flight at 10.9 blocks/s, the player's own commands).
 *
 * <p><b>Load</b> (t = seconds since the world was ready):
 * <ul>
 *   <li>flight, a 360 s cycle: 180 s east over fresh terrain, 60 s back west (revisits: chunks reloaded), 60 s north
 *       (fresh), 60 s hovering with a 36 deg/s spin; terrain following {@value #CLEARANCE_DEFAULT} blocks above the higher
 *       of the ground below and 8 blocks ahead (climb <= 15, descent <= 8 blocks/s);</li>
 *   <li>window, a {@code soak.windowCycle} cycle (default 120 s, times below scale with it): fullscreen until 75 s, then
 *       windowed (options.fullscreen, the F11 path) with three resize bursts at 82, 97 and 112 s (10 setWindowed steps over
 *       1 s, like a drag, through 1280x800, 960x600, 1500x940, 1120x700, 1600x1000, 800x500 points), fullscreen again at
 *       120 s.</li>
 * </ul>
 *
 * <p><b>Samples</b> every {@code soak.sample} s (default 10) on the render thread: FPS / p99 / max frame time of the
 * interval, Java heap used / committed / used after the last GC cycle (GcInfo), non-heap, direct buffers, GC count / time,
 * phys_footprint and RSS (proc_pid_rusage), MTLDevice.currentAllocatedSize (NativeMem), nativeFootprint = phys_footprint
 * - heap committed (ZGC commits between -Xms2G and -Xmx4G and uncommits after 5 min, which would otherwise dominate the
 * footprint), loaded chunks, entities, window state (fullscreen, framebuffer, "trans" = within 5 s of a window change),
 * flight segment and position. Live heap: a daemon thread calls System.gc() at the end of the warmup and every
 * {@code soak.gcEvery} s (default 60; ZGC: concurrent, the render thread keeps running) and records the live heap: the
 * old generation's usage after that major collection (see liveHeapPoint); the render thread adds a last point at the end.
 *
 * <p><b>Verdict</b> ({@code "verdict"} in the result). PASS needs all of: status "complete" (no exception, no render
 * stall over {@code soak.stallSeconds} = 120 s, no lost connection / world), the full duration, and for each gated metric
 * over the samples after the warmup W ({@code soak.warmup}, default 300 s):
 * <ul>
 *   <li>slope: Theil-Sen slope (median of pairwise slopes, MB/min) of per-{@code soak.bucket} (60 s) medians (live heap:
 *       of the points themselves) over the last min({@code soak.trendWindow} = 20 min, run - W) &lt; X, allowing one
 *       level shift: if the largest local rise (median of the next 3 points - median of the previous 3) stands out from
 *       the point noise by 4 sigma, only pairs on the same side of it count (SoakVerdict.trend). So a single step to a new
 *       plateau (one more terrain-buffer heap, a new high-water mark) is not a "sustained upward trend", a leak is, with
 *       or without a step; plain Theil-Sen is not step-robust (a 128 MB step a quarter into the span reads ~7 MB/min);</li>
 *   <li>delta: median of the last {@code soak.edgeWindow} (3 min) - median of the first 3 min after W &lt; Y (total
 *       growth, steps included).</li>
 * </ul>
 * Window-dependent metrics (metalMB, nativeFootprintMB) use only fullscreen samples at the reference (most common
 * fullscreen) framebuffer size with "trans" false, so render-target sizes never look like growth. Limits
 * ({@code -Dbench.soak.limit.<metric>=X,Y}):
 * <pre>
 *   metric             X (MB/min)  Y (MB)
 *   liveHeapMB         3           160    old generation after a forced major GC (System.gc() every 60 s)
 *   metalMB            4           256    MTLDevice.currentAllocatedSize
 *   nativeFootprintMB  5           320    phys_footprint - Java heap committed
 * </pre>
 * Why these: X is the growth that would matter within one long session on this machine (24 GB unified memory, -Xmx4G)
 * and that 20 minutes can resolve: 3 MB/min of heap is ~1.4 GB per 8 h, half the ~3 GB of -Xmx above the ~0.8 GB live
 * set; 4 MB/min of GPU memory is ~2 GB per 8 h; the native footprint holds the Metal memory plus malloc / JIT /
 * Objective-C (+1 MB/min). The standard error of a 20-minute Theil-Sen slope over per-minute points is ~0.04 x the point
 * noise per minute: the 3-minute smoke measured ~30 MB for the old-generation live heap (terrain-dependent server and client
 * chunk data while flying), ~1 MB for Metal and ~9 MB for the native footprint, so each X is >= 2.5 standard errors and
 * pure noise rarely fails. Y is X times the 25-minute span (75 / 100 / 125 MB) plus what is not a trend: for the heap ~3
 * sigma of the difference of two 3-point medians (~88 MB) -> 160; for Metal one terrain-buffer heap step (UberGpuBuffer
 * heaps grow in steps of up to 128 MB) + the slope budget, rounded to 256; native = Metal's Y + 64 MB of malloc / JIT
 * slack. Checked on synthetic 30-minute runs with that noise (tools/SoakVerdictTest.java: false alarms, leak detection,
 * one-step tolerance). The noise of full runs is not known yet: every metric reports its residual noise (1.4826 x MAD),
 * and the limits must be re-checked against it after the first full runs. "conclusive"
 * is false when the trend span is under 15 min (e.g. the 3-minute smoke): such a verdict only says the harness and the run
 * worked.
 */
final class Soak {
    static final double CLEARANCE_DEFAULT = 6.0;
    static final double MINUTES = num("bench.soak.minutes", 30.0);
    static final double WARMUP_S = num("bench.soak.warmup", 300.0);
    static final double SAMPLE_S = num("bench.soak.sample", 10.0);
    static final double GC_EVERY_S = num("bench.soak.gcEvery", 60.0);
    static final double WINDOW_CYCLE_S = num("bench.soak.windowCycle", 120.0);
    static final boolean WINDOW_OPS = !"false".equals(System.getProperty("bench.soak.window"));
    static final double TREND_S = num("bench.soak.trendWindow", 1200.0);
    static final double EDGE_S = num("bench.soak.edgeWindow", 180.0);
    static final double BUCKET_S = num("bench.soak.bucket", 60.0);
    static final double STALL_S = num("bench.soak.stallSeconds", 120.0);
    static final double SPEED = num("bench.speed", 10.9);
    static final double CLEARANCE = num("bench.soak.clearance", CLEARANCE_DEFAULT);
    /** Multiplayer guest: the server address the game joins with --quickPlayMultiplayer (no world is created). */
    static final String JOIN = System.getProperty("bench.join");
    /** Seconds after a window change during which samples are "trans" (old and new render targets may both be alive). */
    static final double TRANS_S = 5.0;
    private static final double MB = 1024.0 * 1024.0;

    static final SoakVerdict.Limit[] LIMITS = {
        SoakVerdict.limit("liveHeapMB", 3.0, 160.0, false),
        SoakVerdict.limit("metalMB", 4.0, 256.0, true),
        SoakVerdict.limit("nativeFootprintMB", 5.0, 320.0, true),
    };

    private record Seg(String name, float yaw, double seconds, boolean move) {
    }

    private static final Seg[] PLAN = {
        new Seg("east", -90.0F, 180.0, true), new Seg("west", 90.0F, 60.0, true), new Seg("north", 180.0F, 60.0, true),
        new Seg("hover", 0.0F, 60.0, false),
    };
    private static final double PLAN_S = Arrays.stream(PLAN).mapToDouble(Seg::seconds).sum();
    /** Window events in a 120 s cycle (scaled to soak.windowCycle). */
    private static final double[] EV_AT = {0.0, 75.0, 82.0, 97.0, 112.0};
    private static final String[] EV = {"fullscreen", "windowed", "resize", "resize", "resize"};
    private static final int[][] SIZES = {{1280, 800}, {960, 600}, {1500, 940}, {1120, 700}, {1600, 1000}, {800, 500}};

    private final String label;
    private final String out;
    private final long t0;
    private final double durationS = MINUTES * 60.0;
    private final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
    private final List<GarbageCollectorMXBean> gcs = ManagementFactory.getGarbageCollectorMXBeans();
    private final Set<String> heapPools = new HashSet<>();
    /** The old-generation pool ("ZGC Old Generation", "G1 Old Gen", ...; the name contains "Old" or "Tenured"), or null. */
    private final MemoryPoolMXBean oldPool;
    private final BufferPoolMXBean direct;

    private double x, y, z;
    private long lastNs;
    private long frames, framesSinceWarmup;
    private int intervalFrames;
    private final long[] dts = new long[1 << 17];
    private int nDts;
    private double nextSample, lastSampleT, lastWriteT;
    private boolean configBegun;
    private int evCycle, evIndex, resizeCount;
    private double lastWindowOpT = -1e9;
    private int burstStep = -1, burstW0, burstH0, burstW1, burstH1;
    private double burstStartT;
    private int fullscreenToggles, resizes;
    private volatile long lastFrameNs;
    private volatile boolean finished;
    private String error;
    private String status = "running";

    // Columnar time series (guarded by this).
    private final Map<String, List<Object>> series = new LinkedHashMap<>();
    private final List<double[]> live = new ArrayList<>();
    private final List<String> events = new ArrayList<>();

    Soak(final Minecraft mc, final String label, final String out) {
        this.label = label;
        this.out = out;
        this.t0 = System.nanoTime();
        this.lastFrameNs = this.t0;
        MemoryPoolMXBean old = null;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) {
                this.heapPools.add(pool.getName());
                if ((pool.getName().contains("Old") || pool.getName().contains("Tenured")) && pool.getCollectionUsage() != null) {
                    old = pool;
                }
            }
        }
        this.oldPool = old;
        BufferPoolMXBean d = null;
        for (BufferPoolMXBean b : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
            if ("direct".equals(b.getName())) {
                d = b;
            }
        }
        this.direct = d;
        NativeMem.init();
        ConfigBlock.prepare();
        LocalPlayer p = mc.player;
        this.x = p.getX();
        this.z = p.getZ();
        this.y = ground(mc, this.x, this.z, -90.0F) + CLEARANCE;
        this.nextSample = 0.0;
        System.out.println(String.format(Locale.ROOT,
            "[bench] soak: %.1f min, warmup %.0f s, sample %.0f s, gc every %.0f s, window ops %b (cycle %.0f s), %s, native: %s",
            MINUTES, WARMUP_S, SAMPLE_S, GC_EVERY_S, WINDOW_OPS, WINDOW_CYCLE_S,
            JOIN == null ? "singleplayer" : "multiplayer guest of " + JOIN, NativeMem.status));
        startDaemons();
    }

    private static double num(final String key, final double def) {
        String v = System.getProperty(key);
        return v == null || v.isBlank() ? def : Double.parseDouble(v.trim());
    }

    private double now() {
        return (System.nanoTime() - this.t0) / 1e9;
    }

    /** Live-heap GC thread and render-stall watchdog (both daemons; they end with the run). */
    private void startDaemons() {
        List<Double> due = new ArrayList<>();
        for (double t = GC_EVERY_S; GC_EVERY_S > 0 && t < this.durationS - 1.0; t += GC_EVERY_S) {
            due.add(t);
        }
        if (WARMUP_S < this.durationS && !due.contains(WARMUP_S)) {
            due.add(WARMUP_S);
        }
        due.sort(Double::compare);
        Thread gc = new Thread(() -> {
            for (double at : due) {
                try {
                    long sleep = (long) ((at - now()) * 1000.0);
                    if (sleep > 0) {
                        Thread.sleep(sleep);
                    }
                } catch (InterruptedException e) {
                    return;
                }
                if (this.finished) {
                    return;
                }
                liveHeapPoint();
            }
        }, "bench-soak-gc");
        gc.setDaemon(true);
        gc.start();
        Thread stall = new Thread(() -> {
            while (!this.finished) {
                try {
                    Thread.sleep(5000L);
                } catch (InterruptedException e) {
                    return;
                }
                double stalled = (System.nanoTime() - this.lastFrameNs) / 1e9;
                if (!this.finished && stalled > STALL_S) {
                    fail(null, String.format(Locale.ROOT, "render thread stalled for %.0f s", stalled));
                    Runtime.getRuntime().halt(4);
                }
            }
        }, "bench-soak-stall");
        stall.setDaemon(true);
        stall.start();
    }

    /** Forced GC (blocks only the calling thread with ZGC) and the heap used right after it. */
    /**
     * Forced major GC (System.gc(); with ZGC it blocks only the calling thread) and the live set after it: the old
     * generation's usage after that collection (MemoryPoolMXBean.getCollectionUsage), which is where anything long-lived,
     * leaks included, ends up. The total heap used right after the call is recorded too, but it is no live-set measure under
     * a concurrent collector: the game keeps allocating during the ~0.5 s cycle (the 3-minute smoke saw it swing 854-1022
     * MB between consecutive points). Without an old-generation pool the total is used.
     */
    private void liveHeapPoint() {
        double t = now();
        long a = System.nanoTime();
        System.gc();
        long b = System.nanoTime();
        double used = this.memory.getHeapMemoryUsage().getUsed() / MB;
        MemoryUsage oldAfter = this.oldPool == null ? null : this.oldPool.getCollectionUsage();
        double live = oldAfter == null ? used : oldAfter.getUsed() / MB;
        synchronized (this) {
            this.live.add(new double[]{t, live, (b - a) / 1e6, used});
        }
    }

    /** One frame; true when the soak is over (duration reached or world / connection lost). */
    boolean step(final Minecraft mc) {
        long nowNs = System.nanoTime();
        this.lastFrameNs = nowNs;
        double t = (nowNs - this.t0) / 1e9;
        double dt = this.lastNs == 0L ? 0.0 : Math.min(0.1, (nowNs - this.lastNs) / 1e9);
        if (this.lastNs != 0L) {
            long d = nowNs - this.lastNs;
            if (this.nDts < this.dts.length) {
                this.dts[this.nDts++] = d;
            }
        }
        this.lastNs = nowNs;
        this.frames++;
        this.intervalFrames++;
        if (mc.level == null || mc.player == null) {
            this.error = "world lost (screen " + describe(mc.gui.screen()) + ")";
            return true;
        }
        if (t >= WARMUP_S) {
            if (!this.configBegun) {
                this.configBegun = true;
                ConfigBlock.begin();
            }
            this.framesSinceWarmup++;
        }
        fly(mc, t, dt);
        if (WINDOW_OPS) {
            windowOps(mc, t);
        }
        if (t >= this.nextSample) {
            sample(mc, t);
            this.nextSample += SAMPLE_S;
            if (t - this.lastWriteT >= 60.0) {
                this.lastWriteT = t;
                write(mc, null);
            }
        }
        return t >= this.durationS;
    }

    private static String describe(final Screen screen) {
        if (screen == null) {
            return "none";
        }
        String title;
        try {
            title = screen.getTitle().getString();
        } catch (Throwable t) {
            title = "?";
        }
        return screen.getClass().getSimpleName() + " '" + title + "'";
    }

    private Seg segment(final double t) {
        double c = t % PLAN_S;
        for (Seg s : PLAN) {
            if (c < s.seconds()) {
                return s;
            }
            c -= s.seconds();
        }
        return PLAN[PLAN.length - 1];
    }

    /** Ground height at (x, z) and 8 blocks ahead, where loaded; NaN-free (keeps the current height elsewhere). */
    private double ground(final Minecraft mc, final double px, final double pz, final float yaw) {
        double r = Math.toRadians(yaw);
        double best = Double.NEGATIVE_INFINITY;
        for (int k = 0; k <= 8; k += 8) {
            int bx = (int) Math.floor(px - Math.sin(r) * k), bz = (int) Math.floor(pz + Math.cos(r) * k);
            if (mc.level.hasChunk(bx >> 4, bz >> 4)) {
                best = Math.max(best, mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz));
            }
        }
        return best == Double.NEGATIVE_INFINITY ? this.y - CLEARANCE : best;
    }

    private void fly(final Minecraft mc, final double t, final double dt) {
        LocalPlayer p = mc.player;
        Seg seg = segment(t);
        if (seg.move()) {
            double r = Math.toRadians(seg.yaw());
            this.x += -Math.sin(r) * SPEED * dt;
            this.z += Math.cos(r) * SPEED * dt;
        }
        double target = ground(mc, this.x, this.z, seg.yaw()) + CLEARANCE;
        this.y += Math.max(-8.0 * dt, Math.min(15.0 * dt, target - this.y));
        float yaw = seg.move() ? seg.yaw() : (float) ((t * 36.0) % 360.0);
        float pitch = seg.move() ? 5.0F : 10.0F;
        p.getAbilities().flying = true;
        p.setDeltaMovement(0.0, 0.0, 0.0);
        p.snapTo(this.x, this.y, this.z, yaw, pitch);
        p.xo = this.x;
        p.yo = this.y;
        p.zo = this.z;
        p.setYRot(yaw);
        p.yRotO = yaw;
        p.setXRot(pitch);
        p.xRotO = pitch;
    }

    private void windowOps(final Minecraft mc, final double t) {
        double scale = WINDOW_CYCLE_S / 120.0;
        while (true) {
            double at = this.evCycle * WINDOW_CYCLE_S + EV_AT[this.evIndex] * scale;
            if (t < at) {
                break;
            }
            if (this.evCycle > 0 || this.evIndex > 0) {
                fire(mc, EV[this.evIndex], t);
            }
            if (++this.evIndex == EV.length) {
                this.evIndex = 0;
                this.evCycle++;
            }
        }
        if (this.burstStep >= 0) {
            int due = (int) Math.min(10, Math.floor((t - this.burstStartT) / 0.1) + 1);
            Window w = mc.getWindow();
            while (this.burstStep < due) {
                this.burstStep++;
                if (mc.options.fullscreen().get()) {
                    this.burstStep = -1;
                    return;
                }
                int ww = this.burstW0 + (this.burstW1 - this.burstW0) * this.burstStep / 10;
                int hh = this.burstH0 + (this.burstH1 - this.burstH0) * this.burstStep / 10;
                w.setWindowed(ww, hh);
                this.lastWindowOpT = t;
            }
            if (this.burstStep >= 10) {
                this.burstStep = -1;
                this.resizes++;
                event(t, "resized to " + this.burstW1 + "x" + this.burstH1 + " points, framebuffer " + w.getWidth() + "x" + w.getHeight());
            }
        }
    }

    private void fire(final Minecraft mc, final String what, final double t) {
        Window w = mc.getWindow();
        switch (what) {
            case "fullscreen", "windowed" -> {
                boolean on = what.equals("fullscreen");
                this.burstStep = -1;
                if (mc.options.fullscreen().get() != on) {
                    mc.options.fullscreen().set(on);
                    this.fullscreenToggles++;
                    event(t, (on ? "fullscreen on" : "fullscreen off") + " (framebuffer was " + w.getWidth() + "x" + w.getHeight() + ")");
                }
            }
            default -> {
                if (!mc.options.fullscreen().get()) {
                    int[] s = SIZES[this.resizeCount++ % SIZES.length];
                    this.burstW0 = w.getScreenWidth();
                    this.burstH0 = w.getScreenHeight();
                    this.burstW1 = s[0];
                    this.burstH1 = s[1];
                    this.burstStep = 0;
                    this.burstStartT = t;
                }
            }
        }
        this.lastWindowOpT = t;
    }

    private synchronized void event(final double t, final String what) {
        this.events.add(String.format(Locale.ROOT, "%.1f %s", t, what));
    }

    private synchronized void put(final String key, final Object value) {
        this.series.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
    }

    private void sample(final Minecraft mc, final double t) {
        double interval = Math.max(1e-6, t - this.lastSampleT);
        double fps = this.lastSampleT == 0.0 && t < 1e-3 ? 0.0 : this.intervalFrames / interval;
        long[] sorted = Arrays.copyOf(this.dts, this.nDts);
        Arrays.sort(sorted);
        double p99 = sorted.length == 0 ? 0.0 : sorted[(int) Math.min(sorted.length - 1, Math.round(0.99 * (sorted.length - 1)))] / 1e6;
        double max = sorted.length == 0 ? 0.0 : sorted[sorted.length - 1] / 1e6;
        this.intervalFrames = 0;
        this.nDts = 0;
        this.lastSampleT = t;
        MemoryUsage heap = this.memory.getHeapMemoryUsage();
        long gcCount = 0, gcMs = 0;
        double afterGc = -1.0;
        long lastEnd = Long.MIN_VALUE;
        for (GarbageCollectorMXBean gc : this.gcs) {
            gcCount += Math.max(0, gc.getCollectionCount());
            gcMs += Math.max(0, gc.getCollectionTime());
            if (gc instanceof com.sun.management.GarbageCollectorMXBean sun) {
                com.sun.management.GcInfo info = sun.getLastGcInfo();
                if (info != null && info.getEndTime() >= lastEnd) {
                    long sum = 0;
                    for (Map.Entry<String, MemoryUsage> e : info.getMemoryUsageAfterGc().entrySet()) {
                        if (this.heapPools.contains(e.getKey())) {
                            sum += e.getValue().getUsed();
                        }
                    }
                    if (sum > 0) {
                        lastEnd = info.getEndTime();
                        afterGc = sum / MB;
                    }
                }
            }
        }
        long footprint = NativeMem.physFootprint();
        long resident = NativeMem.resident();
        long metal = NativeMem.metalAllocated();
        Window w = mc.getWindow();
        boolean fs = mc.options.fullscreen().get();
        boolean trans = t - this.lastWindowOpT < TRANS_S || this.burstStep >= 0;
        synchronized (this) {
            appendSample(mc, t, fs, trans, w, fps, p99, max, heap, afterGc, footprint, resident, metal, gcCount, gcMs);
        }
    }

    private void appendSample(final Minecraft mc, final double t, final boolean fs, final boolean trans, final Window w,
                              final double fps, final double p99, final double max, final MemoryUsage heap, final double afterGc,
                              final long footprint, final long resident, final long metal, final long gcCount, final long gcMs) {
        put("t", r1(t));
        put("seg", segment(t).name());
        put("fs", fs ? 1 : 0);
        put("trans", trans ? 1 : 0);
        put("fbW", w.getWidth());
        put("fbH", w.getHeight());
        put("fps", r1(fps));
        put("p99Ms", r2(p99));
        put("maxMs", r2(max));
        put("heapUsedMB", r1(heap.getUsed() / MB));
        put("heapCommittedMB", r1(heap.getCommitted() / MB));
        put("heapAfterGcMB", r1(afterGc));
        MemoryUsage oldNow = this.oldPool == null ? null : this.oldPool.getUsage();
        put("oldGenMB", r1(oldNow == null ? -1.0 : oldNow.getUsed() / MB));
        put("nonHeapMB", r1(this.memory.getNonHeapMemoryUsage().getUsed() / MB));
        put("directMB", r1(this.direct == null ? -1.0 : this.direct.getMemoryUsed() / MB));
        put("footprintMB", r1(footprint < 0 ? -1.0 : footprint / MB));
        put("rssMB", r1(resident < 0 ? -1.0 : resident / MB));
        put("nativeFootprintMB", r1(footprint < 0 ? -1.0 : (footprint - heap.getCommitted()) / MB));
        put("metalMB", r1(metal < 0 ? -1.0 : metal / MB));
        put("chunks", mc.level.getChunkSource().getLoadedChunksCount());
        put("entities", mc.level.getEntityCount());
        put("gcCount", gcCount);
        put("gcMs", gcMs);
        put("x", r1(this.x));
        put("z", r1(this.z));
    }

    private static double r1(final double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static double r2(final double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** End of a normal run: last live-heap point, verdict, result. */
    void finish(final Minecraft mc) {
        if (this.error == null) {
            liveHeapPoint();
            this.status = "complete";
        } else {
            this.status = "failed";
        }
        this.finished = true;
        write(mc, null);
        System.out.println("[bench] soak " + this.status + (this.error == null ? "" : ": " + this.error) + ", verdict " + verdictLine);
    }

    /** Exception / stall / watchdog: write what we have with status "failed". mc may be null (other threads). */
    void fail(final Minecraft mc, final String why) {
        if (this.finished) {
            return;
        }
        this.finished = true;
        this.error = why;
        this.status = "failed";
        write(mc, why);
        System.out.println("[bench] soak failed: " + why);
    }

    private String verdictLine = "";

    private synchronized void write(final Minecraft mc, final String failure) {
        StringBuilder sb = new StringBuilder("{\n");
        kv(sb, "label", this.label);
        kv(sb, "mode", "soak");
        kv(sb, "status", this.status);
        if (this.error != null) {
            kv(sb, "error", this.error);
        }
        if (mc != null) {
            try {
                DeviceInfo info = RenderSystem.getDevice().getDeviceInfo();
                kv(sb, "backend", info.backendName());
                kv(sb, "device", info.name());
                num(sb, "renderDistance", mc.options.getEffectiveRenderDistance());
            } catch (Throwable ignored) {
            }
        }
        kv(sb, "server", JOIN == null ? "integrated (singleplayer)" : JOIN);
        num(sb, "plannedSeconds", this.durationS);
        num(sb, "elapsedSeconds", now());
        num(sb, "warmupSeconds", WARMUP_S);
        num(sb, "sampleSeconds", SAMPLE_S);
        num(sb, "frames", this.frames);
        num(sb, "fullscreenToggles", this.fullscreenToggles);
        num(sb, "resizeBursts", this.resizes);
        kv(sb, "nativeMem", NativeMem.status);
        sb.append("  \"verdict\": ").append(verdict()).append(",\n");
        sb.append("  \"events\": [");
        for (int i = 0; i < this.events.size(); i++) {
            sb.append(i == 0 ? "" : ", ").append('"').append(this.events.get(i).replace("\"", "'")).append('"');
        }
        sb.append("],\n  \"liveHeap\": {\"t\": [");
        for (int i = 0; i < this.live.size(); i++) {
            sb.append(i == 0 ? "" : ", ").append(r1(this.live.get(i)[0]));
        }
        sb.append("], \"mb\": [");
        for (int i = 0; i < this.live.size(); i++) {
            sb.append(i == 0 ? "" : ", ").append(r1(this.live.get(i)[1]));
        }
        sb.append("], \"gcMs\": [");
        for (int i = 0; i < this.live.size(); i++) {
            sb.append(i == 0 ? "" : ", ").append(r1(this.live.get(i)[2]));
        }
        sb.append("], \"heapUsedAfterMB\": [");
        for (int i = 0; i < this.live.size(); i++) {
            sb.append(i == 0 ? "" : ", ").append(r1(this.live.get(i)[3]));
        }
        sb.append("], \"pool\": \"").append(this.oldPool == null ? "total heap" : this.oldPool.getName() + " after the collection");
        sb.append("\"},\n  \"series\": {");
        boolean first = true;
        for (Map.Entry<String, List<Object>> e : this.series.entrySet()) {
            sb.append(first ? "\n" : ",\n").append("    \"").append(e.getKey()).append("\": [");
            first = false;
            List<Object> v = e.getValue();
            for (int i = 0; i < v.size(); i++) {
                Object o = v.get(i);
                sb.append(i == 0 ? "" : ", ");
                if (o instanceof String s) {
                    sb.append('"').append(s).append('"');
                } else {
                    sb.append(o);
                }
            }
            sb.append(']');
        }
        sb.append("\n  }");
        if (ConfigBlock.ENABLED && mc != null && "complete".equals(this.status)) {
            try {
                sb.append(",\n  \"config\": ").append(ConfigBlock.json(mc, this.framesSinceWarmup));
            } catch (Throwable t) {
                sb.append(",\n  \"config\": null");
            }
        }
        sb.append("\n}\n");
        try {
            Files.writeString(Path.of(this.out), sb.toString());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /** The verdict object (also refreshed in every periodic write, so a crashed run still shows the trend so far). */
    private String verdict() {
        String[] line = new String[1];
        String json = SoakVerdict.compute(this.series, this.live, this.status, this.error, this.durationS, now(),
            new SoakVerdict.Params(WARMUP_S, TREND_S, EDGE_S, BUCKET_S), LIMITS, line);
        this.verdictLine = line[0];
        return json;
    }

    private static void kv(final StringBuilder sb, final String k, final String v) {
        sb.append("  \"").append(k).append("\": \"").append(v.replace("\\", "\\\\").replace("\"", "\\\"")).append("\",\n");
    }

    private static void num(final StringBuilder sb, final String k, final double v) {
        sb.append("  \"").append(k).append("\": ").append(String.format(Locale.ROOT, "%.3f", v)).append(",\n");
    }
}
