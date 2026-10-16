package bench;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Reads the Metal backend's presentation counters ({@code MetalSurface.presentStats()}, docs/metal/CONTRACT.md 16.4) by
 * reflection, so the bench still runs on the vanilla jar and on other backends ({@code "present": null}). With vsync
 * off the Metal backend presents without blocking ("mailbox"): frames that find no free drawable are rendered and not
 * presented, so FPS (frames rendered) and presented frames per second differ. Values cover the MEASURE phase.
 */
final class PresentStatsHook {
    private static final String SURFACE = "com.mojang.renderpearl.backend.metal.MetalSurface";
    private static final Method STATS;
    private static final Method RESET;
    /** Indices into {@link #raw()} (0 when the Metal surface is absent). */
    static final int FRAMES;
    static final int PRESENTED;
    private static final int NOT_PRESENTED;
    private static final int NIL;
    private static final int OCCLUDED;

    static {
        Method stats = null;
        Method reset = null;
        int frames = 0, presented = 0, notPresented = 0, nil = 0, occluded = 0;
        try {
            Class<?> c = Class.forName(SURFACE);
            stats = c.getMethod("presentStats");
            reset = c.getMethod("resetPresentStats");
            frames = c.getField("STAT_FRAMES").getInt(null);
            presented = c.getField("STAT_PRESENTED").getInt(null);
            notPresented = c.getField("STAT_NOT_PRESENTED").getInt(null);
            nil = c.getField("STAT_NIL").getInt(null);
            occluded = c.getField("STAT_OCCLUDED").getInt(null);
        } catch (Throwable ignored) {
            stats = null;
        }
        STATS = stats;
        RESET = reset;
        FRAMES = frames;
        PRESENTED = presented;
        NOT_PRESENTED = notPresented;
        NIL = nil;
        OCCLUDED = occluded;
    }

    private PresentStatsHook() {
    }

    static void reset() {
        if (RESET != null) {
            try {
                RESET.invoke(null);
            } catch (Throwable ignored) {
            }
        }
    }

    /** The counters since the last reset ({@link #FRAMES}, {@link #PRESENTED}, ...), or null without the Metal surface. */
    static long[] raw() {
        if (STATS == null) {
            return null;
        }
        try {
            return (long[]) STATS.invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** The JSON value of the bench result's {@code "present"} key; {@code seconds} = length of the MEASURE phase. */
    static String json(final double seconds) {
        if (STATS == null) {
            return "null";
        }
        long[] s;
        try {
            s = (long[]) STATS.invoke(null);
        } catch (Throwable t) {
            return "null";
        }
        double sec = Math.max(seconds, 1e-9);
        return String.format(Locale.ROOT,
            "{\"frames\": %d, \"presented\": %d, \"notPresented\": %d, \"nil\": %d, \"occluded\": %d, \"presentedFps\": %.3f, \"presentedShare\": %.4f}",
            s[FRAMES], s[PRESENTED], s[NOT_PRESENTED], s[NIL], s[OCCLUDED], s[PRESENTED] / sec, s[FRAMES] == 0 ? 0.0 : (double) s[PRESENTED] / s[FRAMES]);
    }
}
