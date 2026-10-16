package com.mojang.renderpearl.backend.metal;

import com.mojang.logging.LogUtils;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Late frame throttle (docs/metal/CONTRACT.md 16.R5-A, results/TAIL-ANALYSIS.md S1/V1; {@code -Dmcmetal.lateThrottle},
 * default ON since round 5 (16.R5-FIN, results/ROUND5.md); {@code -Dmcmetal.lateThrottle=false} is the off switch). One per
 * {@link MetalDevice}; render thread.
 *
 * <p>Off ({@code =false}): {@link MetalCommandEncoder#submit()} waits for the previous frame's timeline value itself
 * (CONTRACT.md 5.2) and nothing in this class ever does anything ({@link #ENABLED} is a static final false, so every guard
 * below folds away).
 *
 * <p>On (default): {@code submit()} of frame N commits, rotates transient memory, runs a non-blocking {@code checkErrors()} +
 * {@code drain()} and only <em>arms</em> the wait for frame N-1 ({@link #arm}). The wait happens at the first of:
 * <ul>
 *   <li>{@link #frameThrottle()}: the explicit throttle point. {@code Minecraft.runTick} calls it after the client tick
 *       (packets, tasks, {@code tick()} x n) and before {@code textureManager.tick()} / sound / mouse /
 *       {@code renderFrame}; {@code renderFrame} calls it on entry (other callers such as the world-load loops);</li>
 *   <li>{@link #ensureThrottled()}: the lazy guard at every backend entry point that appends to a command stream, commits,
 *       or lets the CPU write memory that recorded or in-flight GPU work may access (list in CONTRACT.md 16.R5-A). A wait
 *       taken there is a "guard trip": counted, and the first {@code mcmetal.lateThrottle.logTrips} sites (after start and
 *       after each {@link #resetStats()}, i.e. at the bench's MEASURE start) are logged with a stack trace.</li>
 * </ul>
 * Either way the wait is {@code timeline.await(N-1, 5 s)} (false: the same {@code IllegalStateException} as before),
 * then {@code checkErrors()} and {@code drain()}, exactly the tail of the old {@code submit()}. So the invariant of the
 * old code holds unchanged: nothing for frame N+1 is recorded, committed or written into GPU-visible memory before frame
 * N-1 completed (2 frames in flight). Only code that never touches the backend (the client tick) moves in front of the
 * wait, where it overlaps the GPU instead of following it.
 *
 * <p>Threading: {@link #arm}, {@link #throttle()} and the waits run on the render thread (the thread that called
 * {@code submit()}). A guard call from another thread (none in vanilla: compile workers only append to persistently
 * mapped staging memory, which no backend entry point sees) never waits and never touches the timeline, as before, when
 * such threads were not synchronized with the frame throttle either. {@link #pendingSeq} is only written by the render
 * thread; another thread can at worst read a stale value and then returns on the thread check.
 *
 * <p>Allocation: none per frame (counters are primitive fields / a static array; the stat line allocates once per
 * {@link #STAT_INTERVAL_NS}, a logged trip site once).
 *
 * <p>GPU failures stay fatal exactly as before (review fix): a wait that times out or surfaces a command-buffer error,
 * out-of-memory or device loss (or a drain callback that throws) records the exception and keeps the wait armed, so
 * every later guard and the next throttle point rethrow it. A guard can trip inside a task or packet handler that
 * catches exceptions; the failure then still reaches the frame path at the throttle point (Minecraft wraps it in the
 * same "Render Frame" crash report as an exception from {@code renderFrame}), and nothing more is committed meanwhile.
 *
 * <p>No callbacks inside a commit (review fix): {@code MetalCommandEncoder.commit()}'s own guard waits and checks
 * errors but does not drain ({@link #ensureThrottledNoDrain}); {@code submit()} and {@code flushForWait()} take the full
 * guard before they read the timeline value they commit, so a deferred callback can never commit in between.
 */
public final class MetalFrameThrottle {
    private static final Logger LOGGER = LogUtils.getLogger();
    /**
     * {@code -Dmcmetal.lateThrottle}: the late throttle, default on (16.R5-FIN); {@code false} or {@code 0} turns it off
     * (the old submit() and vanilla Minecraft.runTick, byte-for-byte).
     */
    public static final boolean ENABLED = defaultOn("mcmetal.lateThrottle");
    /**
     * {@code -Dmcmetal.lateThrottle.strict=true} (tests): appending to a command stream while a throttle is pending throws
     * instead of being impossible by construction; proves in the headless suites that every recording path is guarded.
     */
    static final boolean STRICT = ENABLED && Boolean.getBoolean("mcmetal.lateThrottle.strict");
    /** Number of guard-trip sites logged with a stack trace ({@code -Dmcmetal.lateThrottle.logTrips}, default 1). */
    static final int LOG_TRIPS = Integer.getInteger("mcmetal.lateThrottle.logTrips", 1);
    /** Stat line interval ({@code -Dmcmetal.lateThrottle.statSeconds}, default 30; 0 = no stat line). */
    static final long STAT_INTERVAL_NS = Math.max(0L, Long.getLong("mcmetal.lateThrottle.statSeconds", 30L)) * 1_000_000_000L;
    /** {@link #STAT_INTERVAL_NS}; tests shorten it (render thread). */
    static long statIntervalNs = STAT_INTERVAL_NS;

    /** Indices of {@link #stats()}. Frames (submits) since the last reset. */
    public static final int STAT_FRAMES = 0;
    /** Guard trips (waits taken at a guarded entry point before the throttle point). */
    public static final int STAT_TRIPS = 1;
    /** Frames with at least one guard trip. */
    public static final int STAT_TRIP_FRAMES = 2;
    /** Most guard trips in one frame. */
    public static final int STAT_MAX_TRIPS_PER_FRAME = 3;
    /** Waits (throttle point or trip) that found frame N-1 still running. */
    public static final int STAT_BLOCKED_WAITS = 4;
    /** Nanoseconds spent in those waits. */
    public static final int STAT_WAIT_NS = 5;
    private static final int STAT_COUNT = 6;
    /** Cumulative counters (render thread); read by the bench harness through reflection, like MetalSurface.presentStats. */
    private static final long[] STATS = new long[STAT_COUNT];

    /** The game's device when the late throttle is on (render thread); null otherwise and for test devices. */
    private static @Nullable MetalFrameThrottle active;

    private final MetalDevice device;
    /** Timeline value whose wait is armed but not taken yet; 0 = none. Written by the render thread only. */
    private long pendingSeq;
    /** The thread that armed the wait (the render thread). */
    private @Nullable Thread renderThread;
    /** Guard trips since the last {@link #arm}. */
    private int tripsThisFrame;
    /** Trip sites logged since start or the last {@link #resetStats()} (render thread). */
    private static int loggedTrips;
    /** Guard trips since start, never reset (the bench frame trace differentiates it per frame). Render thread. */
    private static long totalTrips;
    /** A GPU failure taken by a wait: rethrown by every later guard / throttle point (render thread). */
    private @Nullable RuntimeException failure;
    /** A wait since the last {@link #arm} found frame N-1 still running (late order: mouse input is pumped again). */
    private boolean blockedSinceArm;
    /** Number of {@link #resetStats()} calls (render thread). */
    private static int resets;
    // Stat line (render thread).
    private long statLastNs;
    private int statResets;
    private final long[] statLast = new long[STAT_COUNT];

    MetalFrameThrottle(final MetalDevice device) {
        this.device = device;
    }

    // =============================================================================================
    // Game hooks (Minecraft.runTick / renderFrame)
    // =============================================================================================

    /** True when the late throttle is on and the game's Metal device exists: {@code Minecraft.runTick} uses the late order. */
    public static boolean active() {
        return ENABLED && active != null;
    }

    /**
     * The explicit throttle point: waits for the armed frame (if any), then checkErrors + drain; rethrows a recorded GPU
     * failure. No-op when off. Returns true when a wait since the last submit (here or at a guard) actually blocked: the
     * game then pumps input again so mouse motion that arrived during the wait is not a frame older than in vanilla.
     */
    public static boolean frameThrottle() {
        if (ENABLED) {
            MetalFrameThrottle throttle = active;
            if (throttle != null) {
                return throttle.throttle();
            }
        }
        return false;
    }

    /** Guard trips since start, never reset (bench frame trace source {@code MetalFrameThrottle#guardTrips}). */
    public static long guardTrips() {
        return totalTrips;
    }

    /** A copy of the counters (index with {@code STAT_*}). */
    public static long[] stats() {
        return STATS.clone();
    }

    /** Zeroes the counters and re-arms the trip-site log (bench harness, start of the measurement). */
    public static void resetStats() {
        Arrays.fill(STATS, 0L);
        resets++;
        loggedTrips = 0; // the first trips of a measurement are logged too, not only those of the loading screens
    }

    /** MetalDevice (real device, toggle on): this device's throttle serves the game hooks. */
    static void register(final MetalFrameThrottle throttle) {
        active = throttle;
        if (ENABLED) {
            LOGGER.info("Metal late frame throttle on (-Dmcmetal.lateThrottle): frames-in-flight wait after the client tick");
        }
    }

    /** MetalDevice.close(). */
    static void unregister(final MetalFrameThrottle throttle) {
        if (active == throttle) {
            active = null;
        }
    }

    // =============================================================================================
    // Backend side
    // =============================================================================================

    /** The armed timeline value (0 = none); tests and the strict record check. */
    long pendingSeq() {
        return this.pendingSeq;
    }

    /** Guard trips so far: the folded counter plus the current frame's (tests). */
    long tripCount() {
        return STATS[STAT_TRIPS] + this.tripsThisFrame;
    }

    /**
     * {@code submit()} of a frame (late mode only), right after its commit: arms the wait for {@code previousFrameSeq}
     * (0 for the first frame: nothing to wait for) and closes the per-frame trip count.
     */
    void arm(final long previousFrameSeq) {
        if (this.failure != null) {
            return;   // stays armed: the next guard / throttle point rethrows the failure
        }
        this.renderThread = Thread.currentThread();
        this.blockedSinceArm = false;
        long[] s = STATS;
        s[STAT_FRAMES]++;
        int trips = this.tripsThisFrame;
        if (trips != 0) {
            this.tripsThisFrame = 0;
            s[STAT_TRIPS] += trips;
            s[STAT_TRIP_FRAMES]++;
            if (trips > s[STAT_MAX_TRIPS_PER_FRAME]) {
                s[STAT_MAX_TRIPS_PER_FRAME] = trips;
            }
        }
        this.pendingSeq = previousFrameSeq;
        long interval = statIntervalNs;
        if (interval > 0L) {
            long now = System.nanoTime();
            if (this.statLastNs == 0L) {
                this.statLastNs = now;
            } else if (now - this.statLastNs >= interval) {
                this.logStats(now);
            }
        }
    }

    /**
     * The lazy guard: when a wait is armed, take it now (a "guard trip"). Called first thing by every backend entry point
     * that records, commits or writes GPU-visible memory. Off: folds to nothing.
     */
    void ensureThrottled() {
        if (ENABLED && this.pendingSeq != 0L) {
            this.trip(true);
        }
    }

    /**
     * {@link #ensureThrottled} without the drain: {@code MetalCommandEncoder.commit()}'s guard. A drain there could run a
     * deferred callback that commits, after the caller fixed the value it commits. The entries run at the next drain
     * (submit's own, the throttle point's, or any other wait's).
     */
    void ensureThrottledNoDrain() {
        if (ENABLED && this.pendingSeq != 0L) {
            this.trip(false);
        }
    }

    /** The throttle point: the armed wait if any; otherwise only a drain (entries a nested trip could not run). */
    boolean throttle() {
        if (this.pendingSeq != 0L) {
            this.waitPending(true);
        } else {
            this.device.timeline().drain();
        }
        boolean blocked = this.blockedSinceArm;
        this.blockedSinceArm = false;
        return blocked;
    }

    /** The recorded GPU failure, or null (tests). */
    @Nullable RuntimeException failure() {
        return this.failure;
    }

    private void trip(final boolean drain) {
        if (Thread.currentThread() != this.renderThread) {
            return;
        }
        totalTrips++;
        this.tripsThisFrame++;
        if (loggedTrips < LOG_TRIPS) {
            loggedTrips++;
            LOGGER.info(
                "Metal late throttle: guard trip {} of {} logged: frame {} waited for at a backend call before the throttle point",
                loggedTrips, LOG_TRIPS, this.pendingSeq, new Throwable("late-throttle guard trip site")
            );
        }
        this.waitPending(drain);
    }

    /**
     * The tail of the old submit(): await(N-1, 5 s) -> ISE on timeout; checkErrors(); drain() (unless {@code drain} is
     * false). A failure is recorded and the wait stays armed, so it is rethrown by every later guard and the next
     * throttle point however a caller handles it (see the class comment).
     */
    private void waitPending(final boolean drain) {
        RuntimeException failed = this.failure;
        if (failed != null) {
            throw failed;
        }
        long seq = this.pendingSeq;
        // Cleared first: the drain below may run callbacks that call guarded entry points again.
        this.pendingSeq = 0L;
        MetalTimeline timeline = this.device.timeline();
        try {
            if (!timeline.isComplete(seq)) {
                long start = System.nanoTime();
                boolean done = timeline.await(seq, this.device.encoder().frameWaitTimeoutNs);
                STATS[STAT_BLOCKED_WAITS]++;
                STATS[STAT_WAIT_NS] += System.nanoTime() - start;
                this.blockedSinceArm = true;
                if (!done) {
                    throw new IllegalStateException("5s timeout reached when waiting for the GPU");
                }
            }
            timeline.checkErrors();
            if (drain) {
                timeline.drain();
            }
        } catch (RuntimeException e) {
            this.failure = e;
            this.pendingSeq = seq;   // stays armed: every guard takes the slow path and rethrows
            throw e;
        }
    }

    private void logStats(final long now) {
        long[] s = STATS;
        long[] last = this.statLast;
        if (this.statResets != resets) {
            this.statResets = resets;
            Arrays.fill(last, 0L); // resetStats() ran since the last line: deltas from zero
        }
        long frames = s[STAT_FRAMES] - last[STAT_FRAMES];
        long blocked = s[STAT_BLOCKED_WAITS] - last[STAT_BLOCKED_WAITS];
        long waitNs = s[STAT_WAIT_NS] - last[STAT_WAIT_NS];
        LOGGER.info(
            "Metal late throttle: {} frames in {} s, {} with guard trips ({} trips, max {}/frame since reset), {} blocking waits (avg {} us)",
            frames, (now - this.statLastNs) / 1_000_000_000L, s[STAT_TRIP_FRAMES] - last[STAT_TRIP_FRAMES], s[STAT_TRIPS] - last[STAT_TRIPS],
            s[STAT_MAX_TRIPS_PER_FRAME], blocked, blocked == 0L ? 0L : waitNs / blocked / 1000L
        );
        System.arraycopy(s, 0, last, 0, STAT_COUNT);
        this.statLastNs = now;
    }

    /** Default-on switch parse (same rule as TravelHitchToggles.flag): unset = on; "false" (any case) or "0" = off. */
    static boolean defaultOn(final String name) {
        String value = System.getProperty(name);
        return value == null || !value.equalsIgnoreCase("false") && !value.equals("0");
    }
}
