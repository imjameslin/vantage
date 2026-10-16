package com.mojang.renderpearl.backend.metal;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * Per-frame trace of the Metal backend for the bench (docs/metal/CONTRACT.md 16.R5-B). Off by default:
 * {@code -Dmcmetal.frameTrace=true} (the bench agent sets it for {@code -Dbench.frameTrace=true}). With it off, the three
 * hook sites ({@link MetalCommandEncoder} commit, {@link MetalTimeline#await} and {@link MetalTimeline#drain}) test a
 * static final false and the JIT drops them: the previous code path, exactly.
 *
 * <p>On, the render thread keeps cumulative counters in {@link #C} (plain long increments, no allocation) that the bench
 * reads by reflection once per frame and differentiates, and the first commit installs the native per-timeline-value ring
 * ({@code mcmtl_frametrace_enable}, an optional export outside the frozen ABI: a missing symbol only disables the GPU
 * columns). Completion handlers fill the ring with each value's GPU start / end / busy time; the bench copies it once,
 * after the measurement ({@link #gpuRecords}).
 *
 * <p>Threading: {@link #onCommit}, {@link #onWait}, {@link #onDrain}, {@link #sample} and the bench's reads all run on the
 * render thread (the encoder, the timeline waits and {@code Bench.onFrame} are render-thread only), so {@link #C} needs
 * no synchronization. The ring is written by Metal completion threads with atomics and read here after a full fence;
 * entries of frames still in flight may be partial (their {@code cbCompleted < cbCommitted}).
 */
public final class MetalFrameTrace {
    /** {@code -Dmcmetal.frameTrace=true}: count and install the native ring (default off). */
    public static final boolean ENABLED = Boolean.getBoolean("mcmetal.frameTrace");
    /** log2 of the native ring size ({@code -Dmcmetal.frameTrace.ringLog2}, default 17 = 131072 values, 8 MiB). */
    public static final int RING_LOG2 = Math.max(8, Math.min(24, Integer.getInteger("mcmetal.frameTrace.ringLog2", 17)));

    // Slots of C. Cumulative since startup unless noted.
    /** FRAME_END commits ({@code submit()}). */
    public static final int FRAMES = 0;
    /** Every {@code mcmtl_submit} with COMMIT (FRAME_END and flush-on-wait). */
    public static final int COMMITS = 1;
    /** Sum of {@code mcmtl_submit_result.cpuNanos} (wall time inside the native submit, decode + encode + commit). */
    public static final int SUBMIT_NATIVE_NS = 2;
    /** Wall time {@link MetalTimeline#await} spent in {@code mcmtl_timeline_wait} (the frame throttle and fence waits). */
    public static final int WAIT_NS = 3;
    /** Number of those native waits. */
    public static final int WAITS = 4;
    /** Wall time in {@link MetalTimeline#drain} (deferred releases). */
    public static final int DRAIN_NS = 5;
    /** Sum of {@code encodersCreated}. */
    public static final int ENCODERS = 6;
    /** Sum of {@code drawsEncoded}. */
    public static final int DRAWS = 7;
    /** Sum of {@code recordsDecoded}. */
    public static final int RECORDS = 8;
    /** Timeline value of the latest FRAME_END commit (not cumulative). */
    public static final int LAST_SEQ = 9;
    /** {@code mcmtl_submit_result.flags} of the latest FRAME_END commit (not cumulative). */
    public static final int LAST_RESULT_FLAGS = 10;
    /** {@code MetalTransientMemory} blocks created / freed (refreshed by {@link #sample()}). */
    public static final int BLOCKS_CREATED = 11;
    public static final int BLOCKS_FREED = 12;
    /** Late-throttle guard trips, for a guard that reports them through {@link #countGuardTrip()}. */
    public static final int GUARD_TRIPS = 13;
    public static final int SLOTS = 14;
    public static final long[] C = new long[SLOTS];
    public static final String[] SLOT_NAMES = {
        "frames", "commits", "submitNativeNs", "waitNs", "waits", "drainNs", "encoders", "draws", "records", "lastSeq",
        "lastResultFlags", "blocksCreated", "blocksFreed", "guardTrips"
    };

    // mcmtl_ft_entry / mcmtl_ft_info (native/src/mcmetal_frametrace.h).
    static final long ENTRY_SIZE = 64L;
    static final long INFO_SIZE = 48L;
    /** Longs per seq in {@link #gpuRecords}. */
    public static final int RECORD_LONGS = 8;

    private static @Nullable MetalDevice device;
    private static boolean initTried;
    private static long ringAddress;
    private static long ringCapacity;
    /** System.nanoTime() minus CLOCK_UPTIME_RAW, and minus CACurrentMediaTime (ns); best of 16 bracketed samples. */
    private static long javaMinusUptimeNs;
    private static long javaMinusMediaNs;
    private static long clockBracketNs = -1L;
    private static String status = ENABLED ? "enabled, no commit yet" : "off";

    private MetalFrameTrace() {
    }

    // =============================================================================================
    // Hooks (render thread, ENABLED only)
    // =============================================================================================

    /** {@link MetalCommandEncoder} after every {@code mcmtl_submit} with COMMIT: {@code result} is the result struct. */
    static void onCommit(final MetalDevice dev, final long result, final int flags, final long seq) {
        if (!initTried) {
            initTried = true;
            init(dev);
        }
        long[] c = C;
        c[COMMITS]++;
        c[SUBMIT_NATIVE_NS] += MemoryUtil.memGetLong(result + MtlNative.SUBMIT_RESULT_CPU_NANOS);
        c[ENCODERS] += MemoryUtil.memGetInt(result + MtlNative.SUBMIT_RESULT_ENCODERS_CREATED) & 0xFFFFFFFFL;
        c[DRAWS] += MemoryUtil.memGetInt(result + MtlNative.SUBMIT_RESULT_DRAWS_ENCODED) & 0xFFFFFFFFL;
        c[RECORDS] += MemoryUtil.memGetInt(result + MtlNative.SUBMIT_RESULT_RECORDS_DECODED) & 0xFFFFFFFFL;
        if ((flags & MtlNative.SUBMIT_FLAG_FRAME_END) != 0) {
            c[FRAMES]++;
            c[LAST_SEQ] = seq;
            c[LAST_RESULT_FLAGS] = MemoryUtil.memGetInt(result + MtlNative.SUBMIT_RESULT_FLAGS);
        }
    }

    /** {@link MetalTimeline#await}: {@code ns} spent in a native wait. */
    static void onWait(final long ns) {
        C[WAIT_NS] += ns;
        C[WAITS]++;
    }

    /** {@link MetalTimeline#drain}: {@code ns} spent draining (minus waits / native submits / drains nested in it). */
    static void onDrain(final long ns) {
        C[DRAIN_NS] += Math.max(0L, ns);
    }

    /** Time already attributed to waits, native submits and drains (a drain subtracts what its callbacks spent there). */
    static long nestedNs() {
        long[] c = C;
        return c[WAIT_NS] + c[SUBMIT_NATIVE_NS] + c[DRAIN_NS];
    }

    /**
     * For a frame-throttle guard (late throttle) to report a trip; render thread. The bench reads it only when told to
     * ({@code -Dbench.guardTrips=com.mojang.renderpearl.backend.metal.MetalFrameTrace#guardTrips}): unwired it reads 0.
     */
    public static void countGuardTrip() {
        C[GUARD_TRIPS]++;
    }

    /** {@link #GUARD_TRIPS} (see {@link #countGuardTrip()}). */
    public static long guardTrips() {
        return C[GUARD_TRIPS];
    }

    /** Bench, once per frame (render thread): refreshes the slots read from other objects. No allocation. */
    public static void sample() {
        MetalDevice d = device;
        if (d != null) {
            MetalTransientMemory memory = d.transientMemory();
            C[BLOCKS_CREATED] = memory.blocksCreated();
            C[BLOCKS_FREED] = memory.blocksFreed();
        }
    }

    private static void init(final MetalDevice dev) {
        device = dev;
        if (dev.handle() == 0L) {
            status = "enabled, headless device (no native ring)";
            return;
        }
        MethodHandle enable = Native.ENABLE;
        if (enable == null) {
            status = "enabled, native ring unavailable (" + Native.FAILURE + ")";
            return;
        }
        long info = MemoryUtil.nmemCalloc(1L, INFO_SIZE);
        try {
            MemoryUtil.memPutInt(info, (int)INFO_SIZE);
            int rc = (int)enable.invokeExact(dev.handle(), RING_LOG2, info);
            if (rc != MtlNative.OK) {
                status = "enabled, mcmtl_frametrace_enable failed: " + rc + " " + MtlNative.lastError();
                return;
            }
            ringAddress = MemoryUtil.memGetLong(info + 8L);
            ringCapacity = MemoryUtil.memGetLong(info + 16L);
            calibrateClock();
            status = "enabled, native ring " + ringCapacity + " entries";
        } catch (Throwable t) {
            ringAddress = 0L;
            status = "enabled, native ring failed: " + t;
        } finally {
            MemoryUtil.nmemFree(info);
        }
    }

    private static void calibrateClock() throws Throwable {
        MethodHandle clock = Native.CLOCK;
        if (clock == null) {
            return;
        }
        long pair = MemoryUtil.nmemCalloc(2L, 8L);
        try {
            long best = Long.MAX_VALUE;
            for (int i = 0; i < 16; i++) {
                long j0 = System.nanoTime();
                clock.invokeExact(pair);
                long j1 = System.nanoTime();
                if (j1 - j0 < best) {
                    best = j1 - j0;
                    long mid = j0 + (j1 - j0) / 2L;
                    javaMinusUptimeNs = mid - MemoryUtil.memGetLong(pair);
                    javaMinusMediaNs = mid - MemoryUtil.memGetLong(pair + 8L);
                }
            }
            clockBracketNs = best;
        } finally {
            MemoryUtil.nmemFree(pair);
        }
    }

    // =============================================================================================
    // Bench API (reflection; a few calls per run, allocation allowed)
    // =============================================================================================

    /** Human-readable state of the trace. */
    public static String status() {
        return status;
    }

    /** True when the native ring is installed. */
    public static boolean nativeRing() {
        return ringAddress != 0L;
    }

    /** {@code {"javaMinusUptimeNs": .., "javaMinusMediaNs": .., "bracketNs": .., "ringEntries": ..}}. */
    public static String clockJson() {
        return "{\"javaMinusUptimeNs\": " + javaMinusUptimeNs + ", \"javaMinusMediaNs\": " + javaMinusMediaNs + ", \"bracketNs\": "
            + clockBracketNs + ", \"ringEntries\": " + ringCapacity + "}";
    }

    /**
     * Copies the native records of timeline values {@code [firstSeq, firstSeq + count)} into {@code out}
     * ({@link #RECORD_LONGS} longs per value; times converted to {@code System.nanoTime()}):
     * [0] value, or -1 when the ring no longer (or never) held it; [1] commit time (end of mcmtl_submit), -1 unknown;
     * [2] GPU start, [3] GPU end (-1 when no command buffer completed); [4] GPU busy ns (sum over command buffers);
     * [5] nextDrawable wait ns in that submit; [6] cbCommitted | cbCompleted << 32;
     * [7] flags | encoders << 32 | (1 + fusion fallback reason) << 48 | (1 + present skip reason) << 56.
     * Returns the number of values found, or -1 without a ring. Render thread.
     */
    public static int gpuRecords(final long firstSeq, final int count, final long[] out) {
        if (ringAddress == 0L) {
            return -1;
        }
        VarHandle.fullFence();
        long mask = ringCapacity - 1L;
        int found = 0;
        for (int i = 0; i < count; i++) {
            long seq = firstSeq + i;
            long e = ringAddress + (seq & mask) * ENTRY_SIZE;
            int o = i * RECORD_LONGS;
            if (seq <= 0L || MemoryUtil.memGetLong(e) != seq) {
                for (int k = 0; k < RECORD_LONGS; k++) {
                    out[o + k] = -1L;
                }
                continue;
            }
            found++;
            long commit = MemoryUtil.memGetLong(e + 8L);
            long start = MemoryUtil.memGetLong(e + 16L);
            long end = MemoryUtil.memGetLong(e + 24L);
            out[o] = seq;
            out[o + 1] = commit == 0L ? -1L : commit + javaMinusUptimeNs;
            boolean gpu = start != -1L && end != 0L;
            out[o + 2] = gpu ? start + javaMinusMediaNs : -1L;
            out[o + 3] = gpu ? end + javaMinusMediaNs : -1L;
            out[o + 4] = MemoryUtil.memGetLong(e + 32L);
            out[o + 5] = MemoryUtil.memGetLong(e + 40L);
            out[o + 6] = (MemoryUtil.memGetInt(e + 48L) & 0xFFFFFFFFL) | (long)MemoryUtil.memGetInt(e + 52L) << 32;
            out[o + 7] = (MemoryUtil.memGetInt(e + 56L) & 0xFFFFFFFFL) | (MemoryUtil.memGetShort(e + 60L) & 0xFFFFL) << 32
                | (MemoryUtil.memGetByte(e + 62L) & 0xFFL) << 48 | (MemoryUtil.memGetByte(e + 63L) & 0xFFL) << 56;
        }
        return found;
    }

    /** Native device handle of {@code backend} (a {@link MetalDevice}), else of the traced device; 0 if none. */
    private static long handleOf(final @Nullable Object backend) {
        if (backend instanceof MetalDevice d) {
            return d.handle();
        }
        MetalDevice d = device;
        return d != null ? d.handle() : 0L;
    }

    /** Cumulative native decoder counters (names: {@code counterNames} of {@link #nativeConfig}), or null. Render thread. */
    public static long @Nullable [] nativeCounters(final @Nullable Object backend) {
        long handle = handleOf(backend);
        MethodHandle counters = Native.COUNTERS;
        if (handle == 0L || counters == null) {
            return null;
        }
        long buffer = MemoryUtil.nmemCalloc(128L, 8L);
        try {
            int n = (int)counters.invokeExact(handle, buffer, 128);
            long[] out = new long[Math.max(0, n)];
            for (int i = 0; i < out.length; i++) {
                out[i] = MemoryUtil.memGetLong(buffer + 8L * i);
            }
            return out;
        } catch (Throwable t) {
            return null;
        } finally {
            MemoryUtil.nmemFree(buffer);
        }
    }

    /** JSON object of the effective native configuration ({@code mcmtl_frametrace_config}), or null. Render thread. */
    public static @Nullable String nativeConfig(final @Nullable Object backend) {
        long handle = handleOf(backend);
        MethodHandle config = Native.CONFIG;
        if (handle == 0L || config == null) {
            return null;
        }
        int capacity = 32 * 1024;
        long buffer = MemoryUtil.nmemCalloc(1L, capacity);
        long length = MemoryUtil.nmemCalloc(1L, 8L);
        try {
            int rc = (int)config.invokeExact(handle, buffer, capacity, length);
            if (rc != MtlNative.OK) {
                return null;
            }
            return MemoryUtil.memUTF8(buffer, MemoryUtil.memGetInt(length));
        } catch (Throwable t) {
            return null;
        } finally {
            MemoryUtil.nmemFree(buffer);
            MemoryUtil.nmemFree(length);
        }
    }

    /** Lazily bound optional exports; null handles (and FAILURE) when the library or a symbol is missing. */
    private static final class Native {
        static final @Nullable MethodHandle ENABLE;
        static final @Nullable MethodHandle CLOCK;
        static final @Nullable MethodHandle COUNTERS;
        static final @Nullable MethodHandle CONFIG;
        static final String FAILURE;

        static {
            MethodHandle enable = null;
            MethodHandle clock = null;
            MethodHandle counters = null;
            MethodHandle config = null;
            String failure = "";
            try {
                if (!MtlNative.Loader.isLoaded()) {
                    throw new IllegalStateException("libmcmetal not loaded");
                }
                Linker linker = Linker.nativeLinker();
                enable = linker.downcallHandle(
                    MemorySegment.ofAddress(MtlNative.Loader.address("mcmtl_frametrace_enable")), FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_LONG)
                );
                clock = linker.downcallHandle(MemorySegment.ofAddress(MtlNative.Loader.address("mcmtl_frametrace_clock")), FunctionDescriptor.ofVoid(JAVA_LONG));
                counters = linker.downcallHandle(
                    MemorySegment.ofAddress(MtlNative.Loader.address("mcmtl_frametrace_counters")), FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT)
                );
                config = linker.downcallHandle(
                    MemorySegment.ofAddress(MtlNative.Loader.address("mcmtl_frametrace_config")),
                    FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG)
                );
            } catch (Throwable t) {
                enable = clock = counters = config = null;
                failure = t.toString();
            }
            ENABLE = enable;
            CLOCK = clock;
            COUNTERS = counters;
            CONFIG = config;
            FAILURE = failure;
        }

        private Native() {
        }
    }
}
