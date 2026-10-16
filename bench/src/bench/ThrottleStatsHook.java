package bench;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Reads the Metal late frame throttle's state and counters ({@code MetalFrameThrottle}, docs/metal/CONTRACT.md 16.R5-A)
 * by reflection, so the bench still runs on the vanilla jar ({@code "lateThrottle": null} only there, or if reflection
 * fails); on the patched jar the block is always an object, with {@code "active": false} on Vulkan / OpenGL or with the
 * toggle off (use {@code enabled} / {@code active}, not null, to tell the arms apart). Values
 * cover the MEASURE phase: frames (submits), guard trips (a backend call took the frame wait before the throttle point),
 * frames with trips, most trips in one frame, waits that blocked and their mean length.
 */
final class ThrottleStatsHook {
    private static final String THROTTLE = "com.mojang.renderpearl.backend.metal.MetalFrameThrottle";
    private static final Method STATS;
    private static final Method RESET;
    private static final Method ACTIVE;
    private static final boolean ENABLED;
    private static final int FRAMES;
    private static final int TRIPS;
    private static final int TRIP_FRAMES;
    private static final int MAX_TRIPS;
    private static final int BLOCKED;
    private static final int WAIT_NS;

    static {
        Method stats = null;
        Method reset = null;
        Method active = null;
        boolean enabled = false;
        int frames = 0, trips = 0, tripFrames = 0, maxTrips = 0, blocked = 0, waitNs = 0;
        try {
            Class<?> c = Class.forName(THROTTLE);
            stats = c.getMethod("stats");
            reset = c.getMethod("resetStats");
            active = c.getMethod("active");
            enabled = c.getField("ENABLED").getBoolean(null);
            frames = c.getField("STAT_FRAMES").getInt(null);
            trips = c.getField("STAT_TRIPS").getInt(null);
            tripFrames = c.getField("STAT_TRIP_FRAMES").getInt(null);
            maxTrips = c.getField("STAT_MAX_TRIPS_PER_FRAME").getInt(null);
            blocked = c.getField("STAT_BLOCKED_WAITS").getInt(null);
            waitNs = c.getField("STAT_WAIT_NS").getInt(null);
        } catch (Throwable ignored) {
            stats = null;
        }
        STATS = stats;
        RESET = reset;
        ACTIVE = active;
        ENABLED = enabled;
        FRAMES = frames;
        TRIPS = trips;
        TRIP_FRAMES = tripFrames;
        MAX_TRIPS = maxTrips;
        BLOCKED = blocked;
        WAIT_NS = waitNs;
    }

    private ThrottleStatsHook() {
    }

    static void reset() {
        if (RESET != null) {
            try {
                RESET.invoke(null);
            } catch (Throwable ignored) {
            }
        }
    }

    /** The JSON value of the bench result's {@code "lateThrottle"} key. */
    static String json() {
        if (STATS == null) {
            return "null";
        }
        long[] s;
        boolean active;
        try {
            s = (long[]) STATS.invoke(null);
            active = (Boolean) ACTIVE.invoke(null);
        } catch (Throwable t) {
            return "null";
        }
        return String.format(Locale.ROOT,
            "{\"enabled\": %b, \"active\": %b, \"frames\": %d, \"trips\": %d, \"tripFrames\": %d, \"maxTripsPerFrame\": %d, \"blockedWaits\": %d, \"meanBlockedWaitMs\": %.4f}",
            ENABLED, active, s[FRAMES], s[TRIPS], s[TRIP_FRAMES], s[MAX_TRIPS], s[BLOCKED], s[BLOCKED] == 0 ? 0.0 : s[WAIT_NS] / 1e6 / s[BLOCKED]);
    }
}
