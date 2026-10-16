package bench;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/**
 * Timing probes that {@link BenchAgent} inserts into {@code Minecraft.runTick} and {@code Minecraft.renderFrame} with
 * {@code -Dbench.frameTrace=true} (and only then: without it the game gets no probe at all). Every probe is a static call
 * around one vanilla call site, matched by owner, name and descriptor, so the hook works on the vanilla jar and on the
 * patched client alike, whatever order the calls are in ({@link BenchAgent#traceHooks}):
 * <pre>
 *   runTick:      ticks = FrameProbe.ticks(timer.advanceGameTime(now))   bundle start
 *                 enter/exit(PACKETS)  processQueuedPackets()
 *                 enter/exit(TASKS)    runAllTasks()
 *                 enter/exit(TEX_TICK) textureManager.tick()
 *                 enter/exit(TICK)     tick()            (once per client tick)
 *                 FrameProbe.bundleEnd()  before window.setErrorSection("Render")   bundle end
 *   renderFrame:  enter/exit(LEVEL_UPDATE) level.update(), (EXTRACT) gameRenderer.extract(..),
 *                 (RENDER) gameRenderer.render(), (SUBMIT) commandEncoder.submit(), (PRESENT) surface.present(),
 *                 (LIMITER) FramerateLimiter.limitDisplayFPS(..)
 * </pre>
 * On the patched client the late-throttle bundle ({@code Minecraft.runTickBundleLateThrottle}, -Dmcmetal.lateThrottle) gets
 * the same four runTick probes (the agent reports each of them twice: one site per order; one runs per runTick); its
 * frame wait lies between {@code ticks} and {@code bundleEnd} and is split out as {@code bundleWaitNs}.
 * Everything runs on the render (main) thread, which is also where {@code Bench.onFrame} reads the values, so plain
 * statics suffice. Values are cumulative; {@link FrameTrace} differentiates them per frame. No allocation: one
 * {@code System.nanoTime()} per probe (and two thread-CPU reads per bundle with {@code bench.frameTrace.cpu}).
 */
public final class FrameProbe {
    public static final int PACKETS = 0;
    public static final int TASKS = 1;
    public static final int TEX_TICK = 2;
    public static final int TICK = 3;
    public static final int LEVEL_UPDATE = 4;
    public static final int EXTRACT = 5;
    public static final int RENDER = 6;
    public static final int SUBMIT = 7;
    public static final int PRESENT = 8;
    public static final int LIMITER = 9;
    public static final int COUNT = 10;
    static final String[] NAMES = {"packets", "tasks", "texTick", "tick", "levelUpdate", "extract", "render", "submit", "present", "limiter"};

    /** {@code -Dbench.frameTrace.cpu=false} turns the render-thread CPU-time samples off (default on). */
    static final boolean CPU = !"false".equals(System.getProperty("bench.frameTrace.cpu"));

    /** Cumulative wall time per probe (ns), call counts, and the start of the probe's latest call. */
    static final long[] acc = new long[COUNT];
    static final long[] calls = new long[COUNT];
    static final long[] enterNs = new long[COUNT];
    /** Cumulative: sum of advanceGameTime results, bundles, bundle wall / thread-CPU / Metal-wait time. */
    static long ticksToDo;
    static long bundles;
    static long bundleNs;
    static long bundleCpuNs;
    static long bundleWaitNs;
    /** Cumulative: Metal drain and native-submit time inside bundles (subtracted from the tick, like the wait). */
    static long bundleDrainNs;
    static long bundleNativeNs;
    /**
     * Metal cumulative counters ({@code MetalFrameTrace.C}) and the slots of its wait, native-submit and drain time, or
     * null. Set by FrameTrace.
     */
    static long[] metal;
    static int metalWaitSlot;
    static int metalNativeSlot;
    static int metalDrainSlot;
    /** Metal wait / native-submit / drain counters when the latest SUBMIT probe was entered (pre-submit split). */
    static long submitEnterWait;
    static long submitEnterNative;
    static long submitEnterDrain;
    private static long bundleStartNs;
    private static long bundleStartCpu;
    private static long bundleStartWait;
    private static long bundleStartDrain;
    private static long bundleStartNative;

    private FrameProbe() {
    }

    /** Lazy holder: java.management starts at the first probe (first runTick), not when the agent transforms classes. */
    private static final class Cpu {
        static final ThreadMXBean BEAN = bean();

        private static ThreadMXBean bean() {
            try {
                ThreadMXBean bean = ManagementFactory.getThreadMXBean();
                return bean.isCurrentThreadCpuTimeSupported() ? bean : null;
            } catch (Throwable t) {
                return null;
            }
        }
    }

    static boolean cpuSampling() {
        return CPU && Cpu.BEAN != null;
    }

    /** Render-thread CPU time (ns), or 0 when sampling is off. */
    static long threadCpu() {
        if (!CPU) {
            return 0L;
        }
        ThreadMXBean bean = Cpu.BEAN;
        return bean == null ? 0L : bean.getCurrentThreadCpuTime();
    }

    private static long metalWait() {
        long[] m = metal;
        return m == null ? 0L : m[metalWaitSlot];
    }

    public static void enter(final int id) {
        if (id == SUBMIT) {
            long[] m = metal;
            if (m != null) {
                // r5-int review fix: waits / flush commits / drains before submit() (a late-throttle guard trip during
                // rendering, a fence wait) are split out of the Java render time instead of being counted twice.
                submitEnterWait = m[metalWaitSlot];
                submitEnterNative = m[metalNativeSlot];
                submitEnterDrain = m[metalDrainSlot];
            }
        }
        enterNs[id] = System.nanoTime();
    }

    public static void exit(final int id) {
        acc[id] += System.nanoTime() - enterNs[id];
        calls[id]++;
    }

    /** Inserted after {@code DeltaTracker.Timer.advanceGameTime(long)}: returns its result unchanged, starts the bundle. */
    public static int ticks(final int ticksToDo) {
        FrameProbe.ticksToDo += ticksToDo;
        bundleStartWait = metalWait();
        long[] m = metal;
        if (m != null) {
            bundleStartDrain = m[metalDrainSlot];
            bundleStartNative = m[metalNativeSlot];
        }
        bundleStartCpu = threadCpu();
        bundleStartNs = System.nanoTime();
        return ticksToDo;
    }

    /** Inserted before {@code window.setErrorSection("Render")} in runTick: ends the bundle started by {@link #ticks}. */
    public static void bundleEnd() {
        long now = System.nanoTime();
        if (bundleStartNs == 0L) {
            return;   // runTick(false): no advanceGameTime call, no bundle
        }
        bundleNs += now - bundleStartNs;
        bundleCpuNs += threadCpu() - bundleStartCpu;
        bundleWaitNs += metalWait() - bundleStartWait;
        long[] m = metal;
        if (m != null) {
            bundleDrainNs += m[metalDrainSlot] - bundleStartDrain;
            bundleNativeNs += m[metalNativeSlot] - bundleStartNative;
        }
        bundles++;
        bundleStartNs = 0L;
    }
}
