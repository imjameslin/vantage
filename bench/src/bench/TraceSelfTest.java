package bench;

/**
 * Public test entry to the package-private frame trace (tools/BenchHookTest.java, tools/FrameTraceTest.java). Drives
 * {@link FrameTrace} the way {@code Bench.step} does, with the {@link FrameProbe} probes called by the test instead of
 * the agent-inserted bytecode. Never used by the game.
 */
public final class TraceSelfTest {
    private static long last;

    private TraceSelfTest() {
    }

    /** WARMUP: prepare (allocates, resolves the Metal hook / guard source) and start the measurement now. */
    public static void begin(final double seconds) {
        ConfigBlock.prepare();
        FrameTrace.prepare(seconds, false);
        ConfigBlock.begin();
        last = System.nanoTime();
        FrameTrace.begin(last);
    }

    /** One onFrame: records the interval since the previous call; returns its length (ns). */
    public static long frame(final double yaw) {
        long now = System.nanoTime();
        long dt = now - last;
        last = now;
        FrameTrace.record(now, dt, yaw);
        return dt;
    }

    /** {@code {"label": .., "medianFps": .., "config": .., "frameTrace": ..}} like a bench result (subset). */
    public static String resultJson(final String label) {
        int rows = FrameTrace.rows();
        long[] dts = new long[rows];
        for (int k = 0; k < rows; k++) {
            dts[k] = FrameTrace.value(FrameTrace.DT, k);
        }
        java.util.Arrays.sort(dts);
        double median = rows == 0 ? 0.0 : dts[rows / 2] / 1e6;
        double p99 = rows == 0 ? 0.0 : dts[Math.min(rows - 1, (int) Math.round(0.99 * (rows - 1)))] / 1e6;
        return "{\n  \"label\": " + FrameTrace.quote(label) + ",\n  \"backend\": \"selftest\",\n  \"frames\": " + rows
            + ",\n  \"medianFps\": " + (median > 0 ? 1000.0 / median : 0.0) + ",\n  \"p1LowFps\": " + (p99 > 0 ? 1000.0 / p99 : 0.0)
            + ",\n  \"p01LowFps\": 0.0,\n  \"config\": " + ConfigBlock.json(new Object(), rows) + ",\n  \"frameTrace\": " + FrameTrace.json() + "\n}\n";
    }

    public static int rows() {
        return FrameTrace.rows();
    }

    public static long value(final String column, final int row) {
        for (int i = 0; i < FrameTrace.NCOL; i++) {
            if (FrameTrace.COLUMNS[i].equals(column)) {
                return FrameTrace.value(i, row);
            }
        }
        throw new IllegalArgumentException(column);
    }

    public static String[] columns() {
        return FrameTrace.COLUMNS.clone();
    }

    public static boolean metalLive() {
        return MetalTraceHook.live();
    }

    public static String guardSource() {
        return MetalTraceHook.guardSource;
    }
}
