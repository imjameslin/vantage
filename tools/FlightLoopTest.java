import bench.FlightLoop;

/** Route contract: repeated positions/views, physical speed, and continuous block boundaries. No game/GPU. */
class FlightLoopTest {
    static int checks;
    static void near(double a, double b, double tolerance) {
        checks++;
        if (Math.abs(a - b) > tolerance) throw new AssertionError(a + " != " + b);
    }
    public static void main(String[] args) throws Exception {
        var route = new FlightLoop(-0.5, 1.5, 10.9, 10);
        for (double t : new double[]{0, 0.5, 1.25, 2.5, 4.7, 5, 7.5, 9.999}) {
            route.at(t);
            double x = route.x, z = route.z, yaw = route.yaw;
            for (int lap = 1; lap <= 8; lap++) {
                route.at(t + lap * 10);
                near(route.x, x, 1e-10);
                near(route.z, z, 1e-10);
                near(route.yaw, yaw, 1e-5);
            }
            route.at(t + 1e-5);
            near(Math.hypot(route.x - x, route.z - z) / 1e-5, 10.9, 1e-7);
        }
        route.at(10 - 1e-6);
        double x = route.x, z = route.z;
        route.at(10 + 1e-6);
        near(Math.hypot(route.x - x, route.z - z), 10.9 * 2e-6, 1e-9);
        route.at(0);
        near(route.x, -0.5, 0);
        near(route.z, 1.5, 0);
        near(route.yaw, -90, 0);
        route.at(2.5);
        near(route.yaw, 0, 1e-5);
        route.at(5);
        near(route.yaw, 90, 1e-5);
        for (double speed : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            try { new FlightLoop(0, 0, speed, 10); throw new AssertionError("accepted invalid speed"); }
            catch (IllegalArgumentException expected) { checks++; }
        }
        // The live collector must exclude the same seconds of route, not a frame-count-dependent distance.
        System.setProperty("bench.mode", "abab");
        System.setProperty("bench.abab.preset", "travelLoop");
        System.setProperty("bench.abab.segments", "1");
        Class<?> abab = Class.forName("bench.Abab");
        for (String name : new String[]{"dt", "gpu"}) set(abab, name, new int[3]);
        set(abab, "seq", new long[3]);
        set(abab, "bin", new byte[3]);
        set(abab, "blockOf", new short[3]);
        boolean[] keep = new boolean[3];
        set(abab, "keep", keep);
        set(abab, "cap", 3);
        var record = abab.getDeclaredMethod("record", long.class, long.class);
        record.setAccessible(true);
        for (int i = 0; i < 3; i++) {
            long start = new long[]{0, 499_000_000, 500_000_000}[i];
            set(abab, "prevNs", start);
            set(abab, "framesInSegment", i == 2 ? 0 : 1000);
            record.invoke(null, start + 1_000_000, 1_000_000L);
            near(keep[i] ? 1 : 0, i == 2 ? 1 : 0, 0);
        }
        System.out.println("FlightLoopTest: " + checks + " checks, PASS");
    }
    static void set(Class<?> c, String name, Object value) throws Exception {
        var field = c.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }
}
