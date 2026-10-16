package bench;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * In-session A/B ({@code -Dbench.mode=abab}, docs/metal/CONTRACT.md 16.AB): one game launch measures two configurations
 * by switching registered runtime toggles at frame boundaries, instead of one launch per configuration.
 *
 * <p><b>Protocol.</b> SETTLING as the perf mode, then WARMUP ({@code bench.warmup} s) with the arms alternating every
 * {@code abab.warmupSwitch} s, so the JIT has profiled and compiled both code paths before anything is measured. MEASURE
 * is {@code abab.blocks} blocks (default 12) of {@code abab.blockSeconds} (default = {@code bench.spin}, 10 s): every
 * block is one full camera turn starting at {@code bench.yaw}, so each block sees every view direction once. Arms in
 * ABBA order ({@code abab.order=abba}, default; {@code abab} = strict alternation): blocks 2i and 2i+1 are pair i. The
 * switch happens in {@code Bench.onFrame} (top of {@code Minecraft.renderFrame}, render thread): the frame rendered
 * next is the first of the new arm. The first {@code abab.discard} frames (default 30) of every block are not
 * analysed (in-flight frames of the old arm, the switch itself, the stop analysis that runs at the boundary).
 *
 * <p><b>Segments</b> ({@code abab.segments=S}, default 24, must divide {@code abab.bins}): the arm alternates S times
 * inside each block, and block 2i+1 runs the opposite arm of block 2i in every segment, so every bin still has one A and
 * one B cell per pair while A and B frames are interleaved every 10/S s (S = 24: every 15-degree sector, 0.42 s). The
 * machine drifts by about 1 % per 10 s (GPU clock, heat) and wanders on a 10-30 s scale, which one arm per block (S = 1)
 * turns into pair-to-pair noise: 1.8-2.0 % on avg FPS and the two-frame median against 0.8 % with S = 24 and 0.9-1.1 %
 * with S = 8 (results/ABAB.md 3). With an even S each pair also cancels a linear drift on its own (half its bins run A
 * first). Interleaved arms read W1's per-sector tail lower than one arm per spin (+1.9 to +4.7 % with S = 24, +4.1 to
 * +5.8 % with S = 8, +6.3 / +6.8 % with S = 1) while the means agree; S = 8 costs 15-40 % more noise for a little of
 * that, so the default is the least noisy design. The discard applies after every segment start, so S suits toggles
 * whose effect settles within {@code abab.discard} frames; S = 1 for anything slower, and as the steady-state check of a
 * toggle that only moves tails.
 *
 * <p>Per frame: the interval (as perf mode), the 1/{@code abab.bins} slice of the block it started in (24 bins: one
 * 15-degree yaw sector per bin), and on Metal with the frame trace ring ({@code -Dmcmetal.frameTrace=true}, which
 * BenchAgent sets in this mode unless {@code abab.gpu=false}) the GPU busy time and end of the timeline values the frame
 * committed (copied from the native ring at each block end). Statistics: {@link AbabStats}.
 *
 * <p><b>Toggles.</b> {@code abab.toggles=fqcn#field=valueA|valueB[,fqcn#field=valueA|valueB...]} (';' also separates).
 * Each field must be static and not final (a static final is folded by the JIT and cannot be switched: full-launch
 * A/B). Values are parsed for the field type (boolean: true/false/1/0, numbers, String, enum constant names); the
 * value read back after every switch is recorded. {@code bench.Abab#aaNoop=0|1} is a field nothing reads (A/A).
 * Contract for the client side (CONTRACT 16.AB): the toggle is read on the render thread at least once per frame and
 * nothing derived from it outlives the frame (or the derived state converges within the discard window).
 *
 * <p><b>Presets.</b> {@code abab.preset=spin} (default) as above; {@code abab.preset=heavy}: the camera holds
 * {@code abab.heavyYaw} at {@code bench.pitch} (default 142.5: the middle of the heaviest sector of the static spin,
 * 135-150 degrees, GPU busy median 2.24 ms there against 1.18 ms at 315-330 in Default mode), blocks of
 * {@code abab.blockSeconds} (default 5 s), bins are time slices of the block.
 *
 * <p><b>Metrics</b> ({@link AbabStats}): per 15-degree sector median2 (median of two-frame means), mean, median, p99
 * ("sector p99", view-matched) and GPU busy median / p99; per pair the whole-arm p99 / p99.9 ("1 % low" / "0.1 % low",
 * the classic definition). Primary (Holm over the two, the GOAL's median + 1 % low): median2 and pairP99; the rest is
 * descriptive. median2 rather than the plain median: the frame intervals are bimodal (two frames in flight), and the
 * plain median moved by 1 % between identical arms in one A/A run.
 *
 * <p><b>Sequential stopping</b> ({@code abab.stop=true}): looks after the balanced pair counts from
 * {@code abab.minPairs} (3) on ({@link AbabStats#lookPairs}: every pair with an even S, even pair counts with S = 1),
 * repeated intervals at {@code abab.alpha} / looks; a stop metric of {@code abab.stopOn} (default {@code median2}) is
 * decided when its Holm-adjusted p x looks is below alpha ("effect") or its interval's half-width is at most
 * {@code abab.margin} percent (0.5). The run ends when every stop metric is decided. The looks are computed and reported
 * with the stop off too ("wouldStopAfterPairs"). With the stop on, the result's intervals are the repeated ones.
 */
public final class Abab {
    static final boolean ENABLED = "abab".equals(System.getProperty("bench.mode"));
    static final String PRESET = prop("preset", "spin");
    static final boolean HEAVY = "heavy".equals(PRESET);
    /** Continuous eastward flight: successive blocks see different terrain, so central FPS can be confounded. */
    static final boolean TRAVEL_LOOP = ENABLED && "travelLoop".equals(PRESET);
    static final boolean TRAVEL = ENABLED && ("travel".equals(PRESET) || TRAVEL_LOOP);
    static final double SPIN_S = Double.parseDouble(System.getProperty("bench.spin", "10"));
    static final double BASE_YAW = Double.parseDouble(System.getProperty("bench.yaw", "0"));
    static final double HEAVY_YAW = dbl("heavyYaw", 142.5);
    static final double BLOCK_S = dbl("blockSeconds", HEAVY ? 5.0 : SPIN_S);
    static final int BLOCKS = Math.max(2, (integer("blocks", 12) + 1) / 2 * 2);
    static final int DISCARD = Math.max(0, integer("discard", 30));
    static final String ORDER = prop("order", "abba");
    static final double WARMUP_SWITCH = Math.max(0.05, dbl("warmupSwitch", 2.0));
    static final int BINS = Math.max(1, Math.min(120, integer("bins", 24)));
    /** Arm segments per block (default 24 = arms interleaved every 15-degree sector; 1 = one arm per block; must divide abab.bins). */
    static final int SEGMENTS = Math.max(1, Math.min(BINS, integer("segments", 24)));
    static final int MIN_CELL = Math.max(1, integer("minCell", 20));
    static final boolean STOP = Boolean.parseBoolean(prop("stop", "false"));
    static final String STOP_ON = prop("stopOn", "median2");
    static final double MARGIN_PCT = dbl("margin", 0.5);
    static final int MIN_PAIRS = Math.max(2, integer("minPairs", 3));
    static final double ALPHA = dbl("alpha", 0.05);
    static final int RESAMPLES = Math.max(200, integer("resamples", 4000));
    static final boolean GPU = !"false".equals(prop("gpu", "true"));
    static final boolean FRAMES_OUT = !"false".equals(prop("frames", "true"));
    /** Boundary-only LOD evidence; segments=1 is required to attribute counter deltas to an arm. */
    static final boolean LOD_COUNTERS = Boolean.parseBoolean(prop("lodCounters", "false"));
    /** Fixed route prefix excluded equally in both arms, independent of the arm's FPS. */
    static final double LOOP_DISCARD_S = dbl("loopDiscardSeconds", 0.5);
    /** Frames not yet complete on the GPU at a block end (their ring entries are copied at the next block end). */
    private static final int IN_FLIGHT = 16;

    /** A/A dummy: switched like a real toggle, read by nothing. */
    public static volatile int aaNoop;

    private static String prop(final String k, final String d) {
        return System.getProperty("bench.abab." + k, d);
    }

    private static double dbl(final String k, final double d) {
        String v = System.getProperty("bench.abab." + k);
        return v == null ? d : Double.parseDouble(v);
    }

    private static int integer(final String k, final int d) {
        String v = System.getProperty("bench.abab." + k);
        return v == null ? d : Integer.parseInt(v.trim());
    }

    /** Arm of block i (of its first segment): 0 = A, 1 = B. */
    public static int armOf(final int i) {
        return "abab".equals(ORDER) ? i % 2 : ((i + 1) / 2) % 2;
    }

    /** Arm of segment {@code s} of block {@code i}: alternating inside the block, opposite in the pair's other block. */
    public static int armOf(final int i, final int s) {
        return (armOf(i) + s) % 2;
    }

    /** Arm of each bin of block {@code i}. */
    static int[] cellArms(final int i) {
        int[] arms = new int[BINS];
        for (int b = 0; b < BINS; b++) {
            arms[b] = armOf(i, b * SEGMENTS / BINS);
        }
        return arms;
    }

    static String armName(final int arm) {
        return arm == 0 ? "A" : "B";
    }

    /** Measured length if every block runs (s). */
    static double plannedSeconds() {
        return BLOCKS * BLOCK_S;
    }

    /** Bench watchdog for this mode (ms): 10 minutes or the planned run plus 7 minutes for loading. */
    static long watchdogMs() {
        double warmup = Double.parseDouble(System.getProperty("bench.warmup", "15"));
        return (long) (Math.max(600.0, warmup + plannedSeconds() + 420.0) * 1000.0);
    }

    // ---- toggles -------------------------------------------------------------------------------------------------

    public record Toggle(String spec, Field field, Object[] values, String[] readBack) {
    }

    private static Toggle[] toggles = new Toggle[0];
    private static int currentArm = -1;
    private static int switches;
    private static long switchNs;

    public static Toggle[] parseToggles(final String specs) {
        if (specs == null || specs.isBlank()) {
            throw new IllegalArgumentException("abab: no toggles (--bench-prop abab.toggles=fqcn#field=valueA|valueB; "
                + "A/A: bench.Abab#aaNoop=0|1)");
        }
        List<Toggle> out = new ArrayList<>();
        for (String raw : specs.split("[,;]")) {
            String spec = raw.trim();
            if (spec.isEmpty()) {
                continue;
            }
            int hash = spec.indexOf('#');
            int eq = spec.indexOf('=', hash + 1);
            int bar = spec.indexOf('|', eq + 1);
            if (hash <= 0 || eq <= hash + 1 || bar < 0) {
                throw new IllegalArgumentException("abab: bad toggle '" + spec + "' (want fqcn#field=valueA|valueB)");
            }
            String className = spec.substring(0, hash);
            String fieldName = spec.substring(hash + 1, eq);
            Class<?> c;
            try {
                c = Class.forName(className, true, Abab.class.getClassLoader());
            } catch (Throwable t) {
                try {
                    c = Class.forName(className, true, Thread.currentThread().getContextClassLoader());
                } catch (Throwable t2) {
                    throw new IllegalArgumentException("abab: toggle class not found: " + className);
                }
            }
            Field f = null;
            for (Class<?> k = c; k != null && f == null; k = k.getSuperclass()) {
                try {
                    f = k.getDeclaredField(fieldName);
                } catch (NoSuchFieldException ignored) {
                }
            }
            if (f == null) {
                throw new IllegalArgumentException("abab: no field " + fieldName + " in " + className);
            }
            int mod = f.getModifiers();
            if (!Modifier.isStatic(mod)) {
                throw new IllegalArgumentException("abab: " + spec + ": field is not static");
            }
            if (Modifier.isFinal(mod)) {
                throw new IllegalArgumentException("abab: " + spec + ": field is static final (folded by the JIT: needs a "
                    + "full-launch A/B, see docs/metal/CONTRACT.md 16.AB)");
            }
            f.setAccessible(true);
            Object a = parseValue(f.getType(), spec.substring(eq + 1, bar).trim(), spec);
            Object b = parseValue(f.getType(), spec.substring(bar + 1).trim(), spec);
            out.add(new Toggle(spec, f, new Object[]{a, b}, new String[2]));
        }
        return out.toArray(new Toggle[0]);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Object parseValue(final Class<?> type, final String v, final String spec) {
        try {
            if (type == boolean.class || type == Boolean.class) {
                if (v.equalsIgnoreCase("true") || v.equals("1") || v.equalsIgnoreCase("on")) {
                    return Boolean.TRUE;
                }
                if (v.equalsIgnoreCase("false") || v.equals("0") || v.equalsIgnoreCase("off")) {
                    return Boolean.FALSE;
                }
                throw new IllegalArgumentException("not a boolean: " + v);
            }
            if (type == int.class || type == Integer.class) {
                return Integer.valueOf(v);
            }
            if (type == long.class || type == Long.class) {
                return Long.valueOf(v);
            }
            if (type == short.class || type == Short.class) {
                return Short.valueOf(v);
            }
            if (type == byte.class || type == Byte.class) {
                return Byte.valueOf(v);
            }
            if (type == float.class || type == Float.class) {
                return Float.valueOf(v);
            }
            if (type == double.class || type == Double.class) {
                return Double.valueOf(v);
            }
            if (type == String.class) {
                return v;
            }
            if (type.isEnum()) {
                return Enum.valueOf((Class) type, v);
            }
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("abab: " + spec + ": bad value '" + v + "' for " + type.getSimpleName() + " (" + e.getMessage() + ")");
        }
        throw new IllegalArgumentException("abab: " + spec + ": unsupported field type " + type.getName());
    }

    /** Sets every toggle to its value for {@code arm} (render thread, frame boundary) and records the value read back. */
    static void setArm(final int arm) {
        long t = System.nanoTime();
        for (Toggle tg : toggles) {
            try {
                tg.field().set(null, tg.values()[arm]);
                if (tg.readBack()[arm] == null) {
                    tg.readBack()[arm] = String.valueOf(tg.field().get(null));
                }
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("abab: cannot set " + tg.spec() + ": " + e);
            }
        }
        if (arm != currentArm) {
            switches++;
        }
        currentArm = arm;
        switchNs += System.nanoTime() - t;
    }

    // ---- per-frame data ------------------------------------------------------------------------------------------

    private static int cap;
    private static int n;
    private static long dropped;
    private static int[] dt;
    private static int[] gpu;
    private static long[] gpuEnd;
    private static long[] seq;
    private static byte[] bin;
    private static short[] blockOf;
    private static boolean[] keep;
    private static int[] blockStart;
    private static int[] blockEnd;
    private static double[] blockSeconds;
    private static AbabStats.Block[] summaries;
    private static long t0;
    private static long prevNs;
    private static long blockStartNs;
    private static int block;
    private static int framesInSegment;
    private static int segment;
    private static int gpuMerged;
    private static long beginSeq = -1L;
    private static boolean gpuLive;
    private static long[] rec;
    private static boolean prepared;
    private static boolean done;
    private static String stopReason = "planned blocks complete";
    private static int wouldStopAfterPairs = -1;
    private static final List<String> looks = new ArrayList<>();
    private static long analysisNs;
    private static long analysisMaxNs;
    private static int[] stopMetrics;
    /** Pair counts after which the sequential rule looks (balanced ones only) and the per-look level alpha / looks. */
    private static int[] lookPairs = new int[0];
    private static double lookAlpha;
    /** Whole-pair tails per pair: [pair][arm][AbabStats.PAIR_PS index], ns (AbabStats.pairTails). */
    private static double[][][] pairTail;
    /** Presentation counters (Metal surface) per block and arm: frames presented / frames counted. */
    private static long[][] presented;
    private static long[][] presentFrames;
    private static long presPrevPresented;
    private static long presPrevFrames;
    private static String[] lodCounterSnapshots;

    /** During warmup (once): parse the toggles, allocate, resolve the GPU ring, warm the statistics code up. */
    static void prepare() {
        if (prepared) {
            return;
        }
        prepared = true;
        if (BINS % SEGMENTS != 0) {
            throw new IllegalArgumentException("abab: segments (" + SEGMENTS + ") must divide bins (" + BINS + ")");
        }
        if (TRAVEL_LOOP && (SEGMENTS != 1 || !Double.isFinite(LOOP_DISCARD_S)
                || LOOP_DISCARD_S < 0 || LOOP_DISCARD_S >= BLOCK_S)) {
            throw new IllegalArgumentException("travelLoop needs segments=1 and 0 <= loopDiscardSeconds < blockSeconds");
        }
        toggles = parseToggles(System.getProperty("bench.abab.toggles"));
        if (LOD_COUNTERS) lodCounterSnapshots = new String[BLOCKS + 1];
        List<Integer> sm = new ArrayList<>();
        for (String s : STOP_ON.split(",")) {
            int idx = Arrays.asList(AbabStats.NAMES).indexOf(s.trim());
            if (idx < 0) {
                throw new IllegalArgumentException("abab: unknown stopOn metric '" + s + "' (" + String.join(", ", AbabStats.NAMES) + ")");
            }
            sm.add(idx);
        }
        stopMetrics = sm.stream().mapToInt(Integer::intValue).toArray();
        lookPairs = AbabStats.lookPairs(MIN_PAIRS, BLOCKS / 2, SEGMENTS, !"abab".equals(ORDER));
        lookAlpha = AbabStats.lookLevel(ALPHA, lookPairs.length);
        if ("abab".equals(ORDER) && SEGMENTS % 2 == 1) {
            System.out.println("[bench] abab: WARNING order abab with an odd number of segments does not cancel a linear drift "
                + "(every pair runs A first): diagnostics only, no sequential looks");
        }
        pairTail = new double[BLOCKS / 2][][];
        presented = new long[BLOCKS][2];
        presentFrames = new long[BLOCKS][2];
        cap = (int) Math.min(4_000_000L, (long) (plannedSeconds() * 3000.0) + 8192L);
        dt = new int[cap];
        gpu = new int[cap];
        gpuEnd = new long[cap];
        seq = new long[cap];
        bin = new byte[cap];
        blockOf = new short[cap];
        keep = new boolean[cap];
        blockStart = new int[BLOCKS + 1];
        blockEnd = new int[BLOCKS + 1];
        blockSeconds = new double[BLOCKS + 1];
        summaries = new AbabStats.Block[BLOCKS];
        Arrays.fill(gpu, -1);
        Arrays.fill(gpuEnd, -1L);
        rec = new long[4096 * 8];
        if (GPU) {
            MetalTraceHook.resolve();
            gpuLive = MetalTraceHook.live() && MetalTraceHook.sLastSeq >= 0;
        }
        warmStats();
        System.out.println("[bench] abab: " + toggles.length + " toggle(s) " + togglesText() + ", " + BLOCKS + " blocks x " + BLOCK_S
            + " s, order " + ORDER + ", " + SEGMENTS + " segment(s) per block, preset " + PRESET + ", discard " + DISCARD + " frames, " + BINS + " bins, gpu "
            + (gpuLive ? MetalTraceHook.status() : "absent") + ", stop " + (STOP ? "on (" + STOP_ON + ", margin " + MARGIN_PCT + "%)" : "off")
            + ", looks after " + Arrays.toString(lookPairs) + " pairs at alpha " + AbabStats.num(lookAlpha));
    }

    private static String togglesText() {
        StringBuilder sb = new StringBuilder();
        for (Toggle t : toggles) {
            sb.append(sb.isEmpty() ? "" : "; ").append(t.spec());
        }
        return sb.toString();
    }

    /** Runs the analysis on synthetic data so the JIT has compiled it before the first block boundary. */
    private static void warmStats() {
        int frames = 4000;
        int[] d = new int[frames];
        int[] g = new int[frames];
        byte[] b = new byte[frames];
        boolean[] kp = new boolean[frames];
        java.util.SplittableRandom r = new java.util.SplittableRandom(1);
        for (int k = 0; k < frames; k++) {
            d[k] = 1_000_000 + r.nextInt(200_000);
            g[k] = 900_000 + r.nextInt(100_000);
            b[k] = (byte) (k * BINS / frames);
            kp[k] = true;
        }
        for (int it = 0; it < 30; it++) {
            AbabStats.Block[] bl = new AbabStats.Block[4];
            for (int i = 0; i < 4; i++) {
                bl[i] = AbabStats.summarize(cellArms(i), BINS, 1, d, g, b, kp, 0, frames);
            }
            double[][][] tails = {
                AbabStats.pairTails(d, kp, b, cellArms(0), cellArms(1), 0, frames / 2, frames / 2, frames, 1),
                AbabStats.pairTails(d, kp, b, cellArms(2), cellArms(3), 0, frames / 2, frames / 2, frames, 1)};
            double[] ps = new double[AbabStats.NM];
            for (int m = 0; m < AbabStats.NM; m++) {
                ps[m] = AbabStats.effect(AbabStats.ratios(bl, tails, 2, m), m, 0.05, RESAMPLES, it).p;
            }
            AbabStats.holm(ps);
        }
    }

    /** WARMUP: the arm for {@code secondsInWarmup} (alternating every warmupSwitch s, starting with B). */
    static void warmup(final double secondsInWarmup) {
        int seg = (int) Math.floor(secondsInWarmup / WARMUP_SWITCH);
        int arm = seg % 2 == 0 ? 1 : 0;
        if (arm != currentArm) {
            setArm(arm);
        }
    }

    /** WARMUP -> MEASURE at {@code now} (= the measurement origin): block 0 starts with its arm. */
    static void begin(final long now) {
        prepare();
        t0 = now;
        prevNs = now;
        block = 0;
        segment = 0;
        framesInSegment = 0;
        n = 0;
        gpuMerged = 0;
        switches = 0;
        switchNs = 0L;
        if (gpuLive) {
            MetalTraceHook.sample();
            beginSeq = MetalTraceHook.counters[MetalTraceHook.sLastSeq];
        }
        // Presentation counters from zero (Bench resets them again right after this, before the first measured frame).
        PresentStatsHook.reset();
        presPrevPresented = 0L;
        presPrevFrames = 0L;
        setArm(armOf(0, 0));
        blockStart[0] = 0;
        if (LOD_COUNTERS) lodCounterSnapshots[0] = lodCounterSnapshot(-1);
        blockStartNs = System.nanoTime();
    }

    /** No renders occur between a block end snapshot and the next block's start. No segment/frame sampling. */
    private static String lodCounterSnapshot(final int completedBlock) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"boundary\": ").append(FrameTrace.quote(completedBlock < 0 ? "measurement-start" : "block-end"))
            .append(", \"completedBlock\": ").append(completedBlock).append(", \"frameIndex\": ").append(n)
            .append(", \"elapsedNs\": ").append(System.nanoTime() - t0)
            .append(", \"activeArm\": ").append(FrameTrace.quote(armName(currentArm)))
            .append(", \"intervalArm\": ").append(completedBlock < 0 ? "null"
                : FrameTrace.quote(SEGMENTS == 1 ? armName(armOf(completedBlock)) : "mixed"))
            .append(", \"toggleReadbacks\": [");
        for (int i = 0; i < toggles.length; i++) {
            Toggle toggle = toggles[i];
            sb.append(i == 0 ? "" : ",").append("{\"field\": ")
                .append(FrameTrace.quote(toggle.field().getDeclaringClass().getName() + "#" + toggle.field().getName()));
            try {
                sb.append(", \"value\": ").append(FrameTrace.quote(String.valueOf(toggle.field().get(null))));
            } catch (IllegalAccessException e) {
                sb.append(", \"error\": ").append(FrameTrace.quote(e.toString()));
            }
            sb.append('}');
        }
        sb.append(']');
        try {
            String lod = LodHook.json();
            sb.append(", \"lod\": ").append(lod);
        } catch (RuntimeException | LinkageError e) {
            sb.append(", \"lod\": null, \"error\": ").append(FrameTrace.quote(e.toString()));
        }
        return sb.append('}').toString();
    }

    /**
     * Attributes the presentation counters since the last mark to the arm that just ran in block {@code blk}. Runs at
     * segment and block switches only, in the first frame of a segment, which is discarded; it allocates one small array
     * (the counters' copy) per switch.
     */
    private static void presentMark(final int blk, final int arm) {
        long[] st = PresentStatsHook.raw();
        if (st == null) {
            return;
        }
        long pr = st[PresentStatsHook.PRESENTED];
        long fr = st[PresentStatsHook.FRAMES];
        presented[blk][arm] += Math.max(0L, pr - presPrevPresented);
        presentFrames[blk][arm] += Math.max(0L, fr - presPrevFrames);
        presPrevPresented = pr;
        presPrevFrames = fr;
    }

    /** One measured interval ending at {@code now} (the frame rendered since the previous onFrame). No allocation. */
    static void record(final long now, final long interval) {
        if (n >= cap) {
            dropped++;
            prevNs = now;
            return;
        }
        int k = n++;
        dt[k] = (int) Math.min(Integer.MAX_VALUE, interval);
        double tIn = (prevNs - blockStartNs) / 1e9;
        int b = (int) Math.floor(tIn / BLOCK_S * BINS);
        bin[k] = (byte) Math.max(0, Math.min(BINS - 1, b));
        blockOf[k] = (short) block;
        keep[k] = TRAVEL_LOOP ? tIn >= LOOP_DISCARD_S : framesInSegment >= DISCARD;
        framesInSegment++;
        if (gpuLive) {
            MetalTraceHook.sample();
            seq[k] = MetalTraceHook.counters[MetalTraceHook.sLastSeq];
        } else {
            seq[k] = -1L;
        }
        prevNs = now;
    }

    /** Camera yaw for the frame about to be rendered. */
    static float yaw(final long now) {
        if (HEAVY) {
            return (float) HEAVY_YAW;
        }
        double t = (now - blockStartNs) / 1e9;
        return (float) (BASE_YAW + 360.0 * (t / SPIN_S) % 360.0);
    }

    /** Route is periodic and starts from the same point after every boundary (no travel teleport). */
    static double loopSeconds(final long now) {
        return Math.max(0, Math.min(BLOCK_S, (now - blockStartNs) / 1e9));
    }

    /**
     * After {@link #record}: ends the block when its time is up (switching to the next arm, analysing at pair ends).
     * Returns true when the measurement is over.
     */
    static boolean step(final long now) {
        if (done) {
            return true;
        }
        if (now - blockStartNs < (long) (BLOCK_S * 1e9)) {
            if (SEGMENTS > 1) {
                // Segment boundary inside the block: the next frame starts the new segment with the other arm.
                int seg = (int) Math.min(SEGMENTS - 1, (long) ((now - blockStartNs) / (BLOCK_S * 1e9) * SEGMENTS));
                if (seg != segment) {
                    presentMark(block, armOf(block, segment));
                    segment = seg;
                    setArm(armOf(block, seg));
                    framesInSegment = 0;
                }
            }
            return false;
        }
        long a0 = System.nanoTime();
        if (LOD_COUNTERS) lodCounterSnapshots[block + 1] = lodCounterSnapshot(block);
        presentMark(block, armOf(block, segment));
        blockEnd[block] = n;
        blockSeconds[block] = (now - blockStartNs) / 1e9;
        mergeGpu(Math.max(gpuMerged, n - IN_FLIGHT));
        summaries[block] = AbabStats.summarize(cellArms(block), BINS, MIN_CELL, dt, gpu, bin, keep, blockStart[block], blockEnd[block]);
        // Earlier block whose last frames were still in flight at its end: complete now.
        if (block > 0 && gpuLive) {
            summaries[block - 1] = AbabStats.summarize(cellArms(block - 1), BINS, MIN_CELL, dt, gpu, bin, keep, blockStart[block - 1],
                blockEnd[block - 1]);
        }
        boolean stopNow = false;
        if (block % 2 == 1) {
            int pair = block / 2;
            pairTail[pair] = AbabStats.pairTails(dt, keep, bin, cellArms(block - 1), cellArms(block), blockStart[block - 1],
                blockEnd[block - 1], blockStart[block], blockEnd[block], MIN_CELL);
            if (Arrays.binarySearch(lookPairs, pair + 1) >= 0) {
                stopNow = look(pair + 1);
            }
        }
        block++;
        if (block >= BLOCKS || stopNow) {
            done = true;
            if (stopNow) {
                stopReason = "sequential stop after " + block / 2 + " pairs";
            }
            noteAnalysis(a0);
            return true;
        }
        segment = 0;
        setArm(armOf(block, 0));
        blockStart[block] = n;
        framesInSegment = 0;
        noteAnalysis(a0);
        blockStartNs = System.nanoTime();
        return false;
    }

    private static void noteAnalysis(final long a0) {
        long d = System.nanoTime() - a0;
        analysisNs += d;
        analysisMaxNs = Math.max(analysisMaxNs, d);
    }

    /**
     * Sequential look after {@code pairs} pairs (a balanced count, {@link AbabStats#lookPairs}); true when the run should
     * stop (abab.stop=true and every stop metric decided). The decisions are the ones the final analysis of these pairs
     * would report: repeated intervals at alpha / looks, a primary stop metric is an "effect" when its Holm-adjusted
     * p x looks over the primary family is below alpha, a secondary one when p x looks x (secondary stop metrics) is.
     */
    private static boolean look(final int pairs) {
        double margin = Math.log1p(MARGIN_PCT / 100.0);
        int nLooks = Math.max(1, lookPairs.length);
        AbabStats.Effect[] es = new AbabStats.Effect[AbabStats.NM];
        double[] famP = new double[AbabStats.NM];
        Arrays.fill(famP, Double.NaN);
        int secondary = 0;
        for (int m : stopMetrics) {
            if (!AbabStats.isPrimary(m)) {
                secondary++;
            }
        }
        for (int m = 0; m < AbabStats.NM; m++) {
            boolean stopMetric = false;
            for (int sm : stopMetrics) {
                stopMetric |= sm == m;
            }
            if (!AbabStats.isPrimary(m) && !stopMetric) {
                continue;
            }
            if ((m == AbabStats.GPU_MEDIAN || m == AbabStats.GPU_P99) && !gpuLive) {
                continue;
            }
            es[m] = AbabStats.effect(AbabStats.ratios(summaries, pairTail, pairs, m), m, lookAlpha, RESAMPLES, 1000L + pairs);
            if (AbabStats.isPrimary(m) && !Double.isNaN(es[m].p)) {
                famP[m] = Math.min(1.0, es[m].p * nLooks);
            }
        }
        double[] holmP = AbabStats.holm(famP);
        StringBuilder sb = new StringBuilder();
        sb.append("{\"pairs\": ").append(pairs).append(", \"alpha\": ").append(AbabStats.num(lookAlpha)).append(", \"metrics\": {");
        boolean all = true;
        boolean first = true;
        for (int m : stopMetrics) {
            AbabStats.Effect e = es[m];
            if (e == null) {
                continue;
            }
            double pAdj = AbabStats.isPrimary(m) ? holmP[m] : Math.min(1.0, e.p * nLooks * Math.max(1, secondary));
            String d = AbabStats.decide(e, pAdj, ALPHA, margin);
            all &= !"undecided".equals(d);
            sb.append(first ? "" : ", ").append('"').append(AbabStats.NAMES[m]).append("\": {\"decision\": \"").append(d)
                .append("\", \"pctChange\": ").append(AbabStats.num(AbabStats.pctChange(e.est)))
                .append(", \"pctCi\": [").append(AbabStats.num(AbabStats.pctChange(e.lo))).append(", ")
                .append(AbabStats.num(AbabStats.pctChange(e.hi))).append("], \"pAdj\": ").append(AbabStats.num(pAdj)).append('}');
            first = false;
        }
        all &= !first;   // nothing tested (GPU metrics only, without GPU data): never decided
        sb.append("}, \"allDecided\": ").append(all).append('}');
        looks.add(sb.toString());
        System.out.println("[bench] abab look " + sb);
        if (all && wouldStopAfterPairs < 0) {
            wouldStopAfterPairs = pairs;
        }
        return all && STOP;
    }

    /**
     * Copies the native ring entries of frames [gpuMerged, upTo): frame k committed the timeline values
     * (seq[k-1], seq[k]] (seq[-1] = the value at the measurement start); its GPU busy time is their sum, its GPU end the
     * latest end. A frame without a value of its own keeps -1. Render thread, no allocation.
     */
    private static void mergeGpu(final int upTo) {
        if (!gpuLive || upTo <= gpuMerged) {
            return;
        }
        int k = gpuMerged;
        long lo = k == 0 ? beginSeq : seq[k - 1];
        long hi = seq[upTo - 1];
        if (lo < 0L || hi <= lo) {
            gpuMerged = upTo;
            return;
        }
        for (long next = lo + 1L; next <= hi; ) {
            int count = (int) Math.min(4096L, hi - next + 1L);
            if (MetalTraceHook.gpuRecords(next, count, rec) < 0) {
                gpuLive = false;
                return;
            }
            for (int i = 0; i < count; i++) {
                long s = next + i;
                while (k < upTo && seq[k] < s) {
                    k++;
                }
                if (k >= upTo) {
                    break;
                }
                int o = i * 8;
                if (rec[o] != s) {
                    continue;
                }
                gpu[k] = (int) Math.min(Integer.MAX_VALUE, Math.max(0, gpu[k]) + Math.max(0L, rec[o + 4]));
                if (rec[o + 3] > gpuEnd[k]) {
                    gpuEnd[k] = rec[o + 3];
                }
            }
            next += count;
        }
        gpuMerged = upTo;
    }

    // ---- result --------------------------------------------------------------------------------------------------

    /** The result's {@code "abab"} block (after the run; allocates). {@code shortForm}: without the per-frame arrays. */
    static String json(final boolean shortForm) {
        if (!shortForm) {
            finish();
        }
        StringBuilder sb = new StringBuilder(shortForm ? 8192 : 64 + n * 32);
        int blocksRun = 0;
        for (int i = 0; i < BLOCKS; i++) {
            if (summaries[i] != null) {
                blocksRun = i + 1;
            }
        }
        int pairs = blocksRun / 2;
        sb.append("{\"version\": 1, \"preset\": \"").append(PRESET).append("\", \"order\": \"").append(ORDER).append('"');
        if (TRAVEL_LOOP) sb.append(", \"loopDiscardSeconds\": ").append(LOOP_DISCARD_S);
        sb.append(", \"binKind\": \"").append(!HEAVY && !TRAVEL && Math.abs(BLOCK_S - SPIN_S) < 1e-9 ? "yaw" : "time").append('"');
        sb.append(", \"bins\": ").append(BINS).append(", \"segments\": ").append(SEGMENTS).append(", \"blockSeconds\": ").append(AbabStats.num(BLOCK_S));
        sb.append(", \"spinSeconds\": ").append(AbabStats.num(SPIN_S)).append(", \"baseYaw\": ").append(AbabStats.num(HEAVY ? HEAVY_YAW : BASE_YAW));
        sb.append(", \"discardFrames\": ").append(DISCARD).append(", \"minCell\": ").append(MIN_CELL);
        sb.append(", \"warmupSwitchSeconds\": ").append(AbabStats.num(WARMUP_SWITCH));
        sb.append(", \"blocksPlanned\": ").append(BLOCKS).append(", \"blocksRun\": ").append(blocksRun);
        sb.append(", \"secondsPlanned\": ").append(AbabStats.num(plannedSeconds()));
        double ran = 0.0;
        for (int i = 0; i < blocksRun; i++) {
            ran += blockSeconds[i];
        }
        sb.append(", \"secondsRun\": ").append(AbabStats.num(ran));
        sb.append(", \"stop\": {\"enabled\": ").append(STOP).append(", \"on\": \"").append(STOP_ON).append("\", \"marginPct\": ")
            .append(AbabStats.num(MARGIN_PCT)).append(", \"minPairs\": ").append(MIN_PAIRS).append(", \"alpha\": ").append(AbabStats.num(ALPHA))
            .append(", \"lookPairs\": ").append(Arrays.toString(lookPairs)).append(", \"lookAlpha\": ").append(AbabStats.num(lookAlpha))
            .append(", \"reason\": ").append(FrameTrace.quote(stopReason))
            .append(", \"wouldStopAfterPairs\": ").append(wouldStopAfterPairs)
            .append(", \"secondsSaved\": ").append(AbabStats.num(plannedSeconds() - blocksRun * BLOCK_S))
            .append(", \"wouldSaveSeconds\": ").append(AbabStats.num(wouldStopAfterPairs > 0 ? plannedSeconds() - wouldStopAfterPairs * 2 * BLOCK_S : 0.0))
            .append(", \"looks\": [");
        for (int i = 0; i < looks.size(); i++) {
            sb.append(i == 0 ? "" : ", ").append(looks.get(i));
        }
        sb.append("]}");
        sb.append(", \"toggles\": [");
        for (int i = 0; i < toggles.length; i++) {
            Toggle t = toggles[i];
            sb.append(i == 0 ? "" : ", ").append("{\"spec\": ").append(FrameTrace.quote(t.spec()))
                .append(", \"field\": ").append(FrameTrace.quote(t.field().getDeclaringClass().getName() + "#" + t.field().getName()))
                .append(", \"type\": ").append(FrameTrace.quote(t.field().getType().getName()))
                .append(", \"volatile\": ").append(Modifier.isVolatile(t.field().getModifiers()))
                .append(", \"a\": ").append(FrameTrace.quote(String.valueOf(t.values()[0])))
                .append(", \"b\": ").append(FrameTrace.quote(String.valueOf(t.values()[1])))
                .append(", \"readBackA\": ").append(FrameTrace.quote(t.readBack()[0]))
                .append(", \"readBackB\": ").append(FrameTrace.quote(t.readBack()[1])).append('}');
        }
        sb.append("], \"switches\": ").append(switches).append(", \"switchMeanUs\": ")
            .append(AbabStats.num(switches == 0 ? 0.0 : switchNs / 1e3 / switches));
        sb.append(", \"analysisMs\": {\"total\": ").append(AbabStats.num(analysisNs / 1e6)).append(", \"max\": ")
            .append(AbabStats.num(analysisMaxNs / 1e6)).append('}');
        sb.append(", \"gpu\": ").append(gpuLive ? FrameTrace.quote(MetalTraceHook.status()) : "null");
        if (LOD_COUNTERS) {
            sb.append(", \"lodCounters\": {\"schema\": \"lod-boundary-counters-1\", \"singleArmBlocks\": ")
                .append(SEGMENTS == 1).append(", \"includesDiscardedFrames\": true, \"gpuCompletionSynchronized\": false")
                .append(", \"completionAttribution\": \"GPU completions may lag across block boundaries; OFF deltas can include prior ON work. Mixed-segment blocks cannot attribute deltas by arm. CPU encoding is not GPU completion.\"")
                .append(", \"snapshots\": [");
            boolean first = true;
            for (String snapshot : lodCounterSnapshots) {
                if (snapshot == null) continue;
                sb.append(first ? "" : ",").append(snapshot);
                first = false;
            }
            sb.append("]}");
        }
        sb.append(", \"framesRecorded\": ").append(n).append(", \"dropped\": ").append(dropped);
        // Per arm, pooled kept frames.
        sb.append(",\n    \"arms\": {");
        for (int arm = 0; arm < 2; arm++) {
            sb.append(arm == 0 ? "" : ", ").append('"').append(armName(arm)).append("\": ").append(armJson(arm, blocksRun));
        }
        sb.append('}');
        // Effects over all pairs at 1 - alpha. With the stop on, every interval is a repeated confidence interval at alpha / looks (valid whichever look ended
        // the run) and p is Bonferroni-adjusted over the looks ("pSeq"); "ci95" is the fixed-sample interval, reference only.
        // Holm over the decision family (AbabStats.PRIMARY) on pSeq: "holmP" < alpha is a call.
        sb.append(",\n    \"effects\": {");
        if (pairs >= 1) {
            double level = STOP ? lookAlpha : ALPHA;
            AbabStats.Effect[] es = new AbabStats.Effect[AbabStats.NM];
            double[] pSeq = new double[AbabStats.NM];
            double[] famP = new double[AbabStats.NM];
            for (int m = 0; m < AbabStats.NM; m++) {
                es[m] = AbabStats.effect(AbabStats.ratios(summaries, pairTail, pairs, m), m, level, RESAMPLES, 7L);
                pSeq[m] = Double.isNaN(es[m].p) ? Double.NaN : Math.min(1.0, es[m].p * (STOP ? Math.max(1, lookPairs.length) : 1));
                famP[m] = AbabStats.isPrimary(m) && !(m == AbabStats.PAIR_P99 && Double.isNaN(pSeq[m])) ? pSeq[m] : Double.NaN;
            }
            double[] holmP = AbabStats.holm(famP);
            for (int m = 0; m < AbabStats.NM; m++) {
                double[] c95 = es[m].at(ALPHA);
                String extra = ", \"primary\": " + AbabStats.isPrimary(m)
                    + ", \"ci95\": [" + AbabStats.num(c95[0]) + ", " + AbabStats.num(c95[1]) + "]"
                    + ", \"pctCi95\": [" + AbabStats.num(AbabStats.pctChange(c95[0])) + ", " + AbabStats.num(AbabStats.pctChange(c95[1])) + "]"
                    + ", \"pSeq\": " + AbabStats.num(pSeq[m]) + ", \"holmP\": " + AbabStats.num(holmP[m])
                    + ", \"call\": " + (AbabStats.isPrimary(m) && holmP[m] < ALPHA);
                sb.append(m == 0 ? "" : ",\n      ").append('"').append(AbabStats.NAMES[m]).append("\": ").append(AbabStats.json(es[m], extra));
            }
        }
        sb.append('}');
        // Blocks with their cells.
        sb.append(",\n    \"blocks\": [");
        for (int i = 0; i < blocksRun; i++) {
            AbabStats.Block s = summaries[i];
            StringBuilder cellArms = new StringBuilder(BINS);
            for (int b = 0; b < BINS; b++) {
                cellArms.append(armName(s.arm[b]));
            }
            sb.append(i == 0 ? "\n      " : ",\n      ").append("{\"index\": ").append(i).append(", \"arm\": \"").append(armName(armOf(i)))
                .append("\", \"cellArms\": \"").append(cellArms).append("\", \"startFrame\": ").append(blockStart[i]).append(", \"frames\": ").append(blockEnd[i] - blockStart[i])
                .append(", \"kept\": ").append(kept(blockStart[i], blockEnd[i])).append(", \"seconds\": ").append(AbabStats.num(blockSeconds[i]))
                .append(", \"presented\": [").append(presented[i][0]).append(", ").append(presented[i][1])
                .append("], \"presentFrames\": [").append(presentFrames[i][0]).append(", ").append(presentFrames[i][1]).append(']');
            if (i % 2 == 1 && pairTail[i / 2] != null) {
                double[][] pt = pairTail[i / 2];
                sb.append(", \"pairTailsNs\": {\"A\": [").append(AbabStats.num(pt[0][0])).append(", ").append(AbabStats.num(pt[0][1]))
                    .append("], \"B\": [").append(AbabStats.num(pt[1][0])).append(", ").append(AbabStats.num(pt[1][1])).append("]}");
            }
            sb.append(", \"cells\": [");
            for (int b = 0; b < BINS; b++) {
                sb.append(b == 0 ? "" : ", ").append('[').append(s.n[b]).append(", ").append(s.gpuN[b]);
                for (int m = 0; m < AbabStats.NC; m++) {
                    sb.append(", ").append(Double.isNaN(s.m[m][b]) ? "null" : String.format(Locale.ROOT, "%.0f", s.m[m][b]));
                }
                sb.append(']');
            }
            sb.append("]}");
        }
        sb.append("\n    ], \"cellColumns\": [\"n\", \"gpuN\", \"medianNs\", \"p99Ns\", \"meanNs\", \"gpuMedianNs\", \"gpuP99Ns\", \"median2Ns\"]");
        sb.append(", \"pairTailPercentiles\": [0.99, 0.999]");
        if (FRAMES_OUT && !shortForm) {
            sb.append(",\n    \"frames\": {\"columns\": [\"dtNs\", \"gpuBusyNs\", \"gpuEndNs\", \"bin\", \"block\", \"keep\"], \"gpuEndOrigin\": \"ns since the measurement origin\"");
            appendInts(sb, "dtNs", dt);
            sb.append(", \"gpuBusyNs\": [");
            for (int k = 0; k < n; k++) {
                sb.append(k == 0 ? "" : ",").append(gpu[k]);
            }
            sb.append("], \"gpuEndNs\": [");
            for (int k = 0; k < n; k++) {
                sb.append(k == 0 ? "" : ",").append(gpuEnd[k] < 0L ? -1L : gpuEnd[k] - t0);
            }
            sb.append("], \"bin\": [");
            for (int k = 0; k < n; k++) {
                sb.append(k == 0 ? "" : ",").append(bin[k]);
            }
            sb.append("], \"block\": [");
            for (int k = 0; k < n; k++) {
                sb.append(k == 0 ? "" : ",").append(blockOf[k]);
            }
            sb.append("], \"keep\": [");
            for (int k = 0; k < n; k++) {
                sb.append(k == 0 ? "" : ",").append(keep[k] ? 1 : 0);
            }
            sb.append("]}");
        }
        return sb.append("\n  }").toString();
    }

    private static void appendInts(final StringBuilder sb, final String key, final int[] a) {
        sb.append(", \"").append(key).append("\": [");
        for (int k = 0; k < n; k++) {
            sb.append(k == 0 ? "" : ",").append(a[k]);
        }
        sb.append(']');
    }

    private static int kept(final int from, final int to) {
        int c = 0;
        for (int k = from; k < to; k++) {
            if (keep[k]) {
                c++;
            }
        }
        return c;
    }

    private static boolean finished;

    /** End of the run: the last ring entries (after a short wait for frames in flight), then every block's summary. */
    private static void finish() {
        if (finished) {
            return;
        }
        finished = true;
        if (!done && block < BLOCKS && n > blockStart[block]) {
            // Measurement cut short (should not happen): the partial block is dropped from the analysis.
            stopReason = "incomplete: block " + block + " unfinished";
        }
        if (gpuLive) {
            try {
                Thread.sleep(150L);
            } catch (InterruptedException ignored) {
            }
            mergeGpu(n);
        }
        for (int i = 0; i < BLOCKS; i++) {
            if (summaries[i] != null) {
                summaries[i] = AbabStats.summarize(cellArms(i), BINS, MIN_CELL, dt, gpu, bin, keep, blockStart[i], blockEnd[i]);
            }
        }
    }

    /** Arm frame k was rendered with. */
    private static int frameArm(final int k) {
        return armOf(blockOf[k], bin[k] * SEGMENTS / BINS);
    }

    /**
     * Pooled kept frames of one arm: FPS figures (median, two-frame median, avg, 1% / 0.1% low), GPU busy and GPU cadence
     * (completion to completion of consecutive kept frames of the same segment).
     */
    private static String armJson(final int arm, final int blocksRun) {
        int to = blocksRun > 0 ? blockEnd[blocksRun - 1] : 0;
        int count = 0;
        int gcount = 0;
        int blocks = 0;
        for (int i = 0; i < blocksRun; i++) {
            for (int b = 0; b < BINS; b++) {
                if (armOf(i, b * SEGMENTS / BINS) == arm) {
                    blocks++;
                    break;
                }
            }
        }
        for (int k = 0; k < to; k++) {
            if (keep[k] && frameArm(k) == arm) {
                count++;
                if (gpu[k] >= 0) {
                    gcount++;
                }
            }
        }
        long[] d = new long[count];
        long[] g = new long[gcount];
        long[] cad = new long[count];
        long[] two = new long[count];
        int c = 0, gc = 0, cc = 0, tc = 0;
        double sum = 0.0, gsum = 0.0;
        for (int k = 0; k < to; k++) {
            if (!keep[k] || frameArm(k) != arm) {
                continue;
            }
            d[c++] = dt[k];
            sum += dt[k];
            boolean prevSame = k > 0 && keep[k - 1] && blockOf[k - 1] == blockOf[k] && frameArm(k - 1) == arm;
            if (prevSame) {
                two[tc++] = (long) dt[k - 1] + dt[k];
                if (gpuEnd[k] >= 0L && gpuEnd[k - 1] >= 0L && gpuEnd[k] > gpuEnd[k - 1]) {
                    cad[cc++] = gpuEnd[k] - gpuEnd[k - 1];
                }
            }
            if (gpu[k] >= 0) {
                g[gc++] = gpu[k];
                gsum += gpu[k];
            }
        }
        Arrays.sort(d);
        Arrays.sort(g);
        Arrays.sort(cad, 0, cc);
        Arrays.sort(two, 0, tc);
        double med = AbabStats.pct(d, c, 0.5) / 1e6;
        double p99 = AbabStats.pct(d, c, 0.99) / 1e6;
        double p999 = AbabStats.pct(d, c, 0.999) / 1e6;
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"blocks\": ").append(blocks).append(", \"frames\": ").append(c);
        sb.append(", \"medianFps\": ").append(AbabStats.num(1000.0 / med)).append(", \"avgFps\": ").append(AbabStats.num(c / (sum / 1e9)));
        sb.append(", \"p1LowFps\": ").append(AbabStats.num(1000.0 / p99)).append(", \"p01LowFps\": ").append(AbabStats.num(1000.0 / p999));
        sb.append(", \"median2Fps\": ").append(AbabStats.num(2000.0 / (AbabStats.pct(two, tc, 0.5) / 1e6)));
        sb.append(", \"medianFrameMs\": ").append(AbabStats.num(med)).append(", \"p99FrameMs\": ").append(AbabStats.num(p99));
        sb.append(", \"maxFrameMs\": ").append(AbabStats.num(c == 0 ? Double.NaN : d[c - 1] / 1e6));
        sb.append(", \"gpuFrames\": ").append(gc);
        sb.append(", \"gpuMedianMs\": ").append(AbabStats.num(AbabStats.pct(g, gc, 0.5) / 1e6));
        sb.append(", \"gpuMeanMs\": ").append(AbabStats.num(gc == 0 ? Double.NaN : gsum / gc / 1e6));
        sb.append(", \"gpuP99Ms\": ").append(AbabStats.num(AbabStats.pct(g, gc, 0.99) / 1e6));
        sb.append(", \"gpuCadenceP50Ms\": ").append(AbabStats.num(AbabStats.pct(cad, cc, 0.5) / 1e6));
        sb.append(", \"gpuCadenceP99Ms\": ").append(AbabStats.num(AbabStats.pct(cad, cc, 0.99) / 1e6));
        long pr = 0L, pf = 0L;
        for (int i = 0; i < blocksRun; i++) {
            pr += presented[i][arm];
            pf += presentFrames[i][arm];
        }
        sb.append(", \"presentedShare\": ").append(pf == 0L ? "null" : AbabStats.num((double) pr / pf));
        return sb.append('}').toString();
    }

    /** One-line summary for the launch log. */
    static String summary() {
        StringBuilder sb = new StringBuilder("abab: ");
        sb.append(stopReason).append(", wouldStopAfterPairs ").append(wouldStopAfterPairs).append(", switches ").append(switches)
            .append(", analysis max ").append(String.format(Locale.ROOT, "%.2f", analysisMaxNs / 1e6)).append(" ms");
        return sb.toString();
    }
}
