package bench;

/**
 * Deterministic client clock for the zoomseq capture mode (ZoomSeq). While on, BenchAgent's hook at the top of
 * {@code DeltaTracker.Timer.advanceGameTime(long)} returns {@link #step()} instead of measuring wall-clock time: every
 * rendered frame advances exactly 1/K client tick (a client tick runs on every K-th frame, the partial tick of frame f
 * is (f mod K)/K, the frame delta is 1/K tick), whatever the FPS or the cost of taking screenshots. Everything driven
 * by client ticks plus the partial tick (the spyglass FOV animation in Camera, the spyglass scope scale, a tick-driven
 * zoom key) therefore plays back identically on every run and backend. With {@link #hold} set the clock stands still
 * (no tick, same partial tick, zero delta): the next frame renders the same state again. ZoomSeq uses that to wait for
 * screenshot writes without changing the sequence.
 *
 * The hook is only installed with -Dbench.mode=zoomseq (BenchAgent), so other modes keep the vanilla clock.
 * Single-threaded: all calls come from the render thread.
 */
public final class Lockstep {
    private static volatile boolean on;
    private static int framesPerTick = 2;
    private static long frame = -1;
    private static boolean hold;
    private static long steps;
    private static long heldSteps;

    private Lockstep() {
    }

    /** Hook: true while the lockstep clock replaces wall-clock time. */
    public static boolean active() {
        return on;
    }

    /** Hook: game-time delta of the next frame, in ticks (Timer.deltaTicks). */
    public static float delta() {
        return hold ? 0.0F : 1.0F / framesPerTick;
    }

    /** Hook: advances to the next frame (unless held) and returns the client ticks to run before rendering it. */
    public static int step() {
        steps++;
        if (hold) {
            heldSteps++;
            return 0;
        }
        long before = Math.floorDiv(frame, framesPerTick);
        frame++;
        return (int) (Math.floorDiv(frame, framesPerTick) - before);
    }

    /** Hook: partial tick of the frame about to be rendered (Timer.deltaTickResidual). */
    public static float partial() {
        return (float) Math.floorMod(frame, framesPerTick) / framesPerTick;
    }

    /**
     * Turns the clock on; the next frame is frame 0 (it runs client tick 0 first). Frames rendered before that frame
     * still use the vanilla clock.
     */
    public static void start(int framesPerTick) {
        Lockstep.framesPerTick = Math.max(1, framesPerTick);
        frame = -1;
        hold = false;
        steps = 0;
        heldSteps = 0;
        on = true;
    }

    /** Lockstep time of the current frame in nanoseconds (frame x one tick / framesPerTick), for per-frame easing. */
    public static long nanos() {
        return Math.max(0L, frame) * 50_000_000L / framesPerTick;
    }

    public static void stop() {
        on = false;
        hold = false;
    }

    public static void hold(boolean hold) {
        Lockstep.hold = hold;
    }

    /** The frame being rendered now (set by the step at the start of this game-loop iteration); -1 before frame 0. */
    public static long frame() {
        return frame;
    }

    public static int framesPerTick() {
        return framesPerTick;
    }

    /** Number of hook calls since {@link #start}; 0 after a while means the hook is not installed. */
    public static long steps() {
        return steps;
    }

    /** Actual timer calls that held logical time; readiness reference only. */
    public static long heldSteps() { return heldSteps; }
}
