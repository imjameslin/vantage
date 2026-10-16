package bench;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;

/**
 * Per-frame trace of the MEASURE phase ({@code -Dbench.frameTrace=true}, docs/metal/CONTRACT.md 16.R5-B), written as
 * the result's {@code "frameTrace"} block: one row per measured frame interval (row k is the interval of
 * {@code frameTimes[k]}), columns {@link #COLUMNS}. Analyse with {@code tools/frame_trace.py}.
 *
 * <p>Row k covers the interval between two {@code Bench.onFrame} calls (the start of {@code renderFrame}): the frame
 * rendered and submitted in it, the frame throttle (inside submit, or wherever a late throttle puts it), then
 * {@code runTick}'s tick bundle and sound / mouse. Sources: {@link FrameProbe} (bytecode probes in runTick /
 * renderFrame, every backend), {@code MetalFrameTrace.C} (Metal: native submit time, timeline waits, drains, encoders,
 * transient blocks; via {@link MetalTraceHook}), the late-throttle guard-trip counter if one resolves, the render
 * thread's CPU time, the GC beans, and after the run the native per-timeline-value ring (GPU start / end / busy, drawable
 * wait, present kind) merged per row over the timeline values the row committed. Unknown values are -1.
 *
 * <p>Zero allocation per frame: {@link #prepare} (during warmup) allocates every column; {@link #record} only reads
 * cumulative counters and stores differences. The GC beans are resolved once into an array.
 */
final class FrameTrace {
    static final boolean ENABLED = Boolean.getBoolean("bench.frameTrace");

    static final String[] COLUMNS = {
        "dtNs", "tNs", "view", "ticksToDo", "ticks", "bundleNs", "bundleCpuNs", "bundleWaitNs", "packetsNs", "tasksNs",
        "texTickNs", "tickNs", "preSubmitNs", "levelUpdateNs", "extractNs", "renderNs", "submitNs", "presentNs", "limiterNs",
        "frameCpuNs", "gcEvents", "seq", "commits", "nativeSubmitNs", "waitNs", "drainNs", "encoders", "draws",
        "blocksCreated", "blocksFreed", "guardTrips", "resultFlags", "gpuStartNs", "gpuEndNs", "gpuBusyNs", "cbs", "cbsDone",
        "drawableWaitNs", "presentKind", "fuseFallback", "commitNs",
        // r5-int review fix: nested Metal time, so the analyzer subtracts it from the wall-time components it lies in.
        "bundleDrainNs", "bundleNativeNs", "preSubmitWaitNs", "preSubmitNativeNs", "preSubmitDrainNs",
    };
    static final int DT = 0, T = 1, VIEW = 2, TICKS_TO_DO = 3, TICKS = 4, BUNDLE = 5, BUNDLE_CPU = 6, BUNDLE_WAIT = 7, PACKETS = 8,
        TASKS = 9, TEX_TICK = 10, TICK = 11, PRE_SUBMIT = 12, LEVEL_UPDATE = 13, EXTRACT = 14, RENDER = 15, SUBMIT = 16,
        PRESENT = 17, LIMITER = 18, FRAME_CPU = 19, GC = 20, SEQ = 21, COMMITS = 22, NATIVE_SUBMIT = 23, WAIT = 24, DRAIN = 25,
        ENCODERS = 26, DRAWS = 27, BLOCKS_CREATED = 28, BLOCKS_FREED = 29, GUARD_TRIPS = 30, RESULT_FLAGS = 31, GPU_START = 32,
        GPU_END = 33, GPU_BUSY = 34, CBS = 35, CBS_DONE = 36, DRAWABLE_WAIT = 37, PRESENT_KIND = 38, FUSE_FALLBACK = 39,
        COMMIT_T = 40, BUNDLE_DRAIN = 41, BUNDLE_NATIVE = 42, PRE_WAIT = 43, PRE_NATIVE = 44, PRE_DRAIN = 45, NCOL = 46;
    /** presentKind values (from the FRAME_END value's native flags). */
    static final int PK_NONE = 0, PK_FUSED = 1, PK_PRESENT_PASS = 2, PK_SKIPPED = 3, PK_NIL = 4, PK_NOT_PRESENTED = 5;
    // mcmtl_ft_entry.flags (native/src/mcmetal_frametrace.h)
    static final long F_PRESENT_RECORD = 1L << 3, F_PRESENTED = 1L << 4, F_FUSED = 1L << 5, F_SKIPPED = 1L << 6, F_NIL = 1L << 7;

    private static long[][] cols;
    private static int n;
    private static int cap;
    private static long dropped;
    private static boolean prepared;
    private static GarbageCollectorMXBean[] gcBeans = new GarbageCollectorMXBean[0];
    private static boolean travel;

    // Snapshot of every cumulative source at the previous row (or at begin()).
    private static long t0;
    private static long prevOnFrameNs;
    private static final long[] prevAcc = new long[FrameProbe.COUNT];
    private static final long[] prevCalls = new long[FrameProbe.COUNT];
    private static long prevTicksToDo, prevBundleNs, prevBundleCpu, prevBundleWait, prevBundleDrain, prevBundleNative, prevCpu, prevGc,
        prevGuard;
    private static final long[] prevMetal = new long[32];
    private static long beginSeq = -1L;
    private static boolean metalLive;

    private FrameTrace() {
    }

    /** During warmup: allocate the columns and resolve every source (no allocation later). */
    static void prepare(final double measureSeconds, final boolean travelMode) {
        if (prepared) {
            return;
        }
        prepared = true;
        travel = travelMode;
        long requested = Long.getLong("bench.frameTrace.max", (long) (measureSeconds * 2500.0) + 4096L);
        cap = (int) Math.max(1024L, Math.min(requested, 4_000_000L));
        cols = new long[NCOL][cap];
        MetalTraceHook.resolve();
        metalLive = MetalTraceHook.live();
        if (metalLive) {
            FrameProbe.metalWaitSlot = MetalTraceHook.sWaitNs;
            FrameProbe.metalNativeSlot = MetalTraceHook.sSubmitNs;
            FrameProbe.metalDrainSlot = MetalTraceHook.sDrainNs;
            FrameProbe.metal = MetalTraceHook.counters;
        }
        try {
            List<GarbageCollectorMXBean> beans = ManagementFactory.getGarbageCollectorMXBeans();
            gcBeans = beans.toArray(new GarbageCollectorMXBean[0]);
        } catch (Throwable ignored) {
        }
        if (SLOW_NS > 0L) {
            try {
                slowType = jdk.jfr.EventType.getEventType(SlowFrameEvent.class);   // registers the event now, not on a slow frame
            } catch (Throwable t) {
                slowType = null;
            }
        }
        System.out.println("[bench] frameTrace: " + cap + " rows, metal " + (metalLive ? MetalTraceHook.status() : "absent")
            + ", guard trips " + (MetalTraceHook.guardSource == null ? "absent" : MetalTraceHook.guardSource)
            + ", cpu sampling " + FrameProbe.cpuSampling());
    }

    private static long gcCount() {
        long sum = 0L;
        for (GarbageCollectorMXBean bean : gcBeans) {
            long c = bean.getCollectionCount();
            if (c > 0L) {
                sum += c;
            }
        }
        return sum;
    }

    /** WARMUP -> MEASURE: snapshot every cumulative source; {@code now} is the measurement's time origin. */
    static void begin(final long now) {
        if (!prepared) {
            prepare(30.0, travel);
        }
        n = 0;
        dropped = 0L;
        t0 = now;
        if (metalLive) {
            MetalTraceHook.sample();
        }
        snapshot(now, FrameProbe.threadCpu(), gcCount(), MetalTraceHook.guardTrips());
        beginSeq = metalLive ? MetalTraceHook.counters[MetalTraceHook.sLastSeq] : -1L;
    }

    /** Bench: the measurement's time origin, sampled after {@link #begin} and the other MEASURE-start snapshots. */
    static void setOrigin(final long now) {
        t0 = now;
        prevOnFrameNs = now;
    }

    private static void snapshot(final long now, final long cpu, final long gc, final long guard) {
        prevOnFrameNs = now;
        System.arraycopy(FrameProbe.acc, 0, prevAcc, 0, FrameProbe.COUNT);
        System.arraycopy(FrameProbe.calls, 0, prevCalls, 0, FrameProbe.COUNT);
        prevTicksToDo = FrameProbe.ticksToDo;
        prevBundleNs = FrameProbe.bundleNs;
        prevBundleCpu = FrameProbe.bundleCpuNs;
        prevBundleWait = FrameProbe.bundleWaitNs;
        prevBundleDrain = FrameProbe.bundleDrainNs;
        prevBundleNative = FrameProbe.bundleNativeNs;
        prevCpu = cpu;
        prevGc = gc;
        prevGuard = guard;
        if (metalLive) {
            long[] c = MetalTraceHook.counters;
            System.arraycopy(c, 0, prevMetal, 0, Math.min(c.length, prevMetal.length));
        }
    }

    /** One measured interval ending at {@code now} (= frameTimes[k]); {@code view} = yaw (degrees) or x (travel). */
    static void record(final long now, final long dt, final double view) {
        if (n >= cap) {
            dropped++;
            if (metalLive) {
                MetalTraceHook.sample();
            }
            snapshot(now, FrameProbe.threadCpu(), gcCount(), MetalTraceHook.guardTrips());
            return;
        }
        final int k = n++;
        final long[][] c = cols;
        c[DT][k] = dt;
        c[T][k] = now - t0;
        c[VIEW][k] = Math.round(view * 1000.0);
        long[] acc = FrameProbe.acc;
        c[TICKS_TO_DO][k] = FrameProbe.ticksToDo - prevTicksToDo;
        c[TICKS][k] = FrameProbe.calls[FrameProbe.TICK] - prevCalls[FrameProbe.TICK];
        c[BUNDLE][k] = FrameProbe.bundleNs - prevBundleNs;
        c[BUNDLE_CPU][k] = FrameProbe.cpuSampling() ? FrameProbe.bundleCpuNs - prevBundleCpu : -1L;
        c[BUNDLE_WAIT][k] = metalLive ? FrameProbe.bundleWaitNs - prevBundleWait : -1L;
        c[PACKETS][k] = acc[FrameProbe.PACKETS] - prevAcc[FrameProbe.PACKETS];
        c[TASKS][k] = acc[FrameProbe.TASKS] - prevAcc[FrameProbe.TASKS];
        c[TEX_TICK][k] = acc[FrameProbe.TEX_TICK] - prevAcc[FrameProbe.TEX_TICK];
        c[TICK][k] = acc[FrameProbe.TICK] - prevAcc[FrameProbe.TICK];
        long submitEnter = FrameProbe.enterNs[FrameProbe.SUBMIT];
        boolean submitted = FrameProbe.calls[FrameProbe.SUBMIT] != prevCalls[FrameProbe.SUBMIT];
        c[PRE_SUBMIT][k] = submitted && submitEnter > prevOnFrameNs ? submitEnter - prevOnFrameNs : -1L;
        c[LEVEL_UPDATE][k] = acc[FrameProbe.LEVEL_UPDATE] - prevAcc[FrameProbe.LEVEL_UPDATE];
        c[EXTRACT][k] = acc[FrameProbe.EXTRACT] - prevAcc[FrameProbe.EXTRACT];
        c[RENDER][k] = acc[FrameProbe.RENDER] - prevAcc[FrameProbe.RENDER];
        c[SUBMIT][k] = submitted ? acc[FrameProbe.SUBMIT] - prevAcc[FrameProbe.SUBMIT] : -1L;
        c[PRESENT][k] = acc[FrameProbe.PRESENT] - prevAcc[FrameProbe.PRESENT];
        c[LIMITER][k] = acc[FrameProbe.LIMITER] - prevAcc[FrameProbe.LIMITER];
        long cpu = FrameProbe.threadCpu();
        c[FRAME_CPU][k] = FrameProbe.cpuSampling() ? cpu - prevCpu : -1L;
        long gc = gcCount();
        c[GC][k] = gc - prevGc;
        long guard = MetalTraceHook.guardTrips();
        c[GUARD_TRIPS][k] = guard >= 0L && prevGuard >= 0L ? guard - prevGuard : -1L;
        if (metalLive) {
            MetalTraceHook.sample();
            long[] m = MetalTraceHook.counters;
            c[SEQ][k] = m[MetalTraceHook.sLastSeq];
            c[COMMITS][k] = m[MetalTraceHook.sCommits] - prevMetal[MetalTraceHook.sCommits];
            c[NATIVE_SUBMIT][k] = m[MetalTraceHook.sSubmitNs] - prevMetal[MetalTraceHook.sSubmitNs];
            c[WAIT][k] = m[MetalTraceHook.sWaitNs] - prevMetal[MetalTraceHook.sWaitNs];
            c[DRAIN][k] = m[MetalTraceHook.sDrainNs] - prevMetal[MetalTraceHook.sDrainNs];
            c[ENCODERS][k] = m[MetalTraceHook.sEncoders] - prevMetal[MetalTraceHook.sEncoders];
            c[DRAWS][k] = m[MetalTraceHook.sDraws] - prevMetal[MetalTraceHook.sDraws];
            c[BLOCKS_CREATED][k] = m[MetalTraceHook.sBlocksCreated] - prevMetal[MetalTraceHook.sBlocksCreated];
            c[BLOCKS_FREED][k] = m[MetalTraceHook.sBlocksFreed] - prevMetal[MetalTraceHook.sBlocksFreed];
            c[RESULT_FLAGS][k] = m[MetalTraceHook.sFrames] != prevMetal[MetalTraceHook.sFrames] ? m[MetalTraceHook.sLastFlags] : -1L;
            c[BUNDLE_DRAIN][k] = FrameProbe.bundleDrainNs - prevBundleDrain;
            c[BUNDLE_NATIVE][k] = FrameProbe.bundleNativeNs - prevBundleNative;
            boolean pre = submitted && submitEnter > prevOnFrameNs;
            c[PRE_WAIT][k] = pre ? FrameProbe.submitEnterWait - prevMetal[MetalTraceHook.sWaitNs] : -1L;
            c[PRE_NATIVE][k] = pre ? FrameProbe.submitEnterNative - prevMetal[MetalTraceHook.sSubmitNs] : -1L;
            c[PRE_DRAIN][k] = pre ? FrameProbe.submitEnterDrain - prevMetal[MetalTraceHook.sDrainNs] : -1L;
        } else {
            for (int col = SEQ; col <= RESULT_FLAGS; col++) {
                if (col != GUARD_TRIPS) {
                    c[col][k] = -1L;
                }
            }
            for (int col = BUNDLE_DRAIN; col <= PRE_DRAIN; col++) {
                c[col][k] = -1L;
            }
        }
        for (int col = GPU_START; col <= COMMIT_T; col++) {
            c[col][k] = -1L;   // filled after the run from the native ring (mergeGpu)
        }
        snapshot(now, cpu, gc, guard);
        if (SLOW_NS > 0L && dt >= SLOW_NS) {
            slowFrame(k);
        }
    }

    /**
     * {@code bench.frameTrace.jfrSlowMs} as ns: 0 (default, r5-int review fix) = no SlowFrame event; set it (e.g. 4) for a
     * run recorded with JFR. Even then an event object is only created while a recording has the event enabled
     * ({@link #slowType}), so a slow frame allocates nothing otherwise.
     */
    private static final long SLOW_NS = (long) (Double.parseDouble(System.getProperty("bench.frameTrace.jfrSlowMs", "0")) * 1e6);
    /** The SlowFrame event type (registered in prepare when SLOW_NS > 0); isEnabled() is a field read. */
    private static jdk.jfr.EventType slowType;

    private static void slowFrame(final int k) {
        jdk.jfr.EventType type = slowType;
        if (type == null || !type.isEnabled()) {
            return;   // no recording wants the event: allocate nothing
        }
        SlowFrameEvent e = new SlowFrameEvent();
        if (!e.isEnabled()) {
            return;
        }
        long[][] c = cols;
        e.row = k;
        e.interval = c[DT][k];
        e.ticks = (int) c[TICKS][k];
        e.tickBundle = c[BUNDLE][k];
        e.preSubmit = c[PRE_SUBMIT][k];
        e.nativeSubmit = c[NATIVE_SUBMIT][k];
        e.timelineWait = c[WAIT][k];
        e.gcEvents = (int) c[GC][k];
        e.seq = c[SEQ][k];
        e.commit();
    }

    /** After the run: merge the native per-value records into the rows (allocates; never in the measured window). */
    private static String mergeGpu() {
        if (!metalLive || beginSeq < 0L || n == 0) {
            return "no metal trace";
        }
        long lastSeq = cols[SEQ][n - 1];
        long count = lastSeq - beginSeq;
        if (count <= 0L || count > 16_000_000L) {
            return "no frame-end commits in the window";
        }
        long[] rec = new long[(int) count * 8];
        int found = MetalTraceHook.gpuRecords(beginSeq + 1L, (int) count, rec);
        if (found < 0) {
            return "native ring unavailable";
        }
        long lo = beginSeq;
        for (int k = 0; k < n; k++) {
            long hi = cols[SEQ][k];
            if (hi > lo) {
                long start = Long.MAX_VALUE, end = -1L, busy = 0L, cbs = 0L, done = 0L, drawable = 0L;
                boolean any = false;
                for (long s = lo + 1L; s <= hi; s++) {
                    int o = (int) (s - beginSeq - 1L) * 8;
                    if (rec[o] != s) {
                        continue;
                    }
                    any = true;
                    if (rec[o + 2] >= 0L) {
                        start = Math.min(start, rec[o + 2]);
                        end = Math.max(end, rec[o + 3]);
                    }
                    busy += rec[o + 4];
                    drawable += rec[o + 5];
                    cbs += rec[o + 6] & 0xFFFFFFFFL;
                    done += rec[o + 6] >>> 32;
                }
                int h = (int) (hi - beginSeq - 1L) * 8;
                if (any) {
                    cols[GPU_START][k] = start == Long.MAX_VALUE ? -1L : start - t0;
                    cols[GPU_END][k] = end < 0L ? -1L : end - t0;
                    cols[GPU_BUSY][k] = busy;
                    cols[CBS][k] = cbs;
                    cols[CBS_DONE][k] = done;
                    cols[DRAWABLE_WAIT][k] = drawable;
                }
                if (rec[h] == hi) {
                    long flags = rec[h + 7];
                    cols[PRESENT_KIND][k] = presentKind(flags);
                    cols[FUSE_FALLBACK][k] = (flags >>> 48) & 0xFFL;
                    cols[COMMIT_T][k] = rec[h + 1] < 0L ? -1L : rec[h + 1] - t0;
                }
                lo = hi;
            }
        }
        return "merged " + found + " of " + count + " timeline values";
    }

    static int presentKind(final long flags) {
        if ((flags & F_FUSED) != 0L) {
            return PK_FUSED;
        }
        if ((flags & F_PRESENTED) != 0L) {
            return PK_PRESENT_PASS;
        }
        if ((flags & F_SKIPPED) != 0L) {
            return PK_SKIPPED;
        }
        if ((flags & F_NIL) != 0L) {
            return PK_NIL;
        }
        return (flags & F_PRESENT_RECORD) != 0L ? PK_NOT_PRESENTED : PK_NONE;
    }

    static int rows() {
        return n;
    }

    static long value(final int col, final int row) {
        return cols[col][row];
    }

    /** The {@code "frameTrace"} JSON value (or null when off / never begun). */
    static String json() {
        if (!ENABLED || cols == null) {
            return "null";
        }
        String gpu = mergeGpu();
        StringBuilder sb = new StringBuilder(64 + n * NCOL * 7);
        sb.append("{\"version\": 1, \"frames\": ").append(n).append(", \"capacity\": ").append(cap).append(", \"dropped\": ").append(dropped);
        sb.append(", \"view\": \"").append(travel ? "x position x1000 (travel)" : "yaw degrees x1000").append('"');
        sb.append(", \"timeOrigin\": \"tNs, gpuStartNs, gpuEndNs, commitNs: ns since the first measured onFrame (System.nanoTime base)\"");
        sb.append(", \"cpuSampling\": ").append(FrameProbe.cpuSampling());
        sb.append(", \"probesInstalled\": ").append(quote(BenchAgent.traceReport()));
        sb.append(", \"probesSeen\": {");
        for (int i = 0; i < FrameProbe.COUNT; i++) {
            sb.append(i == 0 ? "" : ", ").append('"').append(FrameProbe.NAMES[i]).append("\": ").append(FrameProbe.calls[i]);
        }
        sb.append(", \"bundles\": ").append(FrameProbe.bundles).append('}');
        sb.append(", \"metal\": ");
        if (metalLive) {
            sb.append("{\"status\": ").append(quote(MetalTraceHook.status())).append(", \"gpu\": ").append(quote(gpu))
                .append(", \"clock\": ").append(MetalTraceHook.clockJson() == null ? "null" : MetalTraceHook.clockJson())
                .append(", \"beginSeq\": ").append(beginSeq).append('}');
        } else {
            sb.append("null");
        }
        sb.append(", \"guardTripsSource\": ").append(quote(MetalTraceHook.guardSource));
        sb.append(", \"presentKinds\": [\"none\", \"fused\", \"present-pass\", \"skipped\", \"nil\", \"not-presented\"]");
        sb.append(",\n    \"columns\": [");
        for (int i = 0; i < NCOL; i++) {
            sb.append(i == 0 ? "" : ", ").append('"').append(COLUMNS[i]).append('"');
        }
        sb.append("],\n    \"rows\": [");
        for (int k = 0; k < n; k++) {
            sb.append(k == 0 ? "\n      [" : ",\n      [");
            for (int i = 0; i < NCOL; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(cols[i][k]);
            }
            sb.append(']');
        }
        sb.append("\n    ]}");
        return sb.toString();
    }

    static String quote(final String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
