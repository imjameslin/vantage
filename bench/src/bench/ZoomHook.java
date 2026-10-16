package bench;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/**
 * Finds the patched client's zoom key (docs/GOAL.md, "Zoom") by reflection, so the bench still runs on the vanilla jar:
 * there the zoom_* scenes and zoomseq's zoom segments are skipped. Also holds keys the way a player does.
 *
 * Contract the zoom feature must meet for the bench (all public static, on the class named by -Dbench.zoomClass,
 * default {@value #DEFAULT_CLASS}; loading the class must create its KeyMapping):
 * <ul>
 *   <li>the key: registered under the name -Dbench.zoomKey (default "key.zoom", found with {@code KeyMapping.get}),
 *       or else the class's first public static {@code KeyMapping} field. The bench holds it with {@code setDown(true)};
 *   <li>{@code void setZoomFactor(double)} (or a public static double/float field {@code zoomFactor}): magnification
 *       while the key is held, FOV = unzoomed FOV / factor. 10.0 equals the spyglass (Camera FOV modifier 0.1).
 *       Optional {@code double getZoomFactor()} lets the bench restore the user's value afterwards;
 *   <li>the zoom animation is eased per frame from a public static {@code java.util.function.LongSupplier clockNanos}
 *       (System::nanoTime by default); zoomseq swaps in its lockstep clock ({@link #bindClock}) so captures are
 *       deterministic.
 * </ul>
 */
final class ZoomHook {
    static final String DEFAULT_CLASS = "net.minecraft.client.zoom.Zoom";
    static final String CLASS = System.getProperty("bench.zoomClass", DEFAULT_CLASS);
    static final String KEY_NAME = System.getProperty("bench.zoomKey", "key.zoom");
    /** Magnification of the zoom_* scenes and zoomseq: the spyglass's (FOV x 0.1), so both are compared at equal FOV. */
    static final double FACTOR = 10.0;

    private static boolean resolved;
    private static String missing;
    private static KeyMapping key;
    private static Method setter;
    private static Method getter;
    private static Field field;

    private ZoomHook() {
    }

    /** True when the zoom feature and its key exist (resolved once, on first use, after the game has started). */
    static synchronized boolean available() {
        if (!resolved) {
            resolved = true;
            resolve();
        }
        return missing == null;
    }

    /** Why the zoom feature was not found (for skip messages), or null. */
    static String missing() {
        available();
        return missing;
    }

    private static void resolve() {
        Class<?> c;
        try {
            c = Class.forName(CLASS, true, Minecraft.class.getClassLoader());
        } catch (Throwable t) {
            missing = "no zoom class " + CLASS + " (vanilla jar?)";
            return;
        }
        key = KeyMapping.get(KEY_NAME);
        if (key == null) {
            for (Field f : c.getFields()) {
                if (Modifier.isStatic(f.getModifiers()) && KeyMapping.class.isAssignableFrom(f.getType())) {
                    try {
                        key = (KeyMapping) f.get(null);
                    } catch (Throwable ignored) {
                    }
                    if (key != null) {
                        break;
                    }
                }
            }
        }
        for (Method m : c.getMethods()) {
            if (!Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            Class<?>[] p = m.getParameterTypes();
            if (m.getName().equals("setZoomFactor") && p.length == 1 && (p[0] == double.class || p[0] == float.class)) {
                setter = m;
            } else if (m.getName().equals("getZoomFactor") && p.length == 0 && (m.getReturnType() == double.class || m.getReturnType() == float.class)) {
                getter = m;
            }
        }
        try {
            Field f = c.getField("zoomFactor");
            if (Modifier.isStatic(f.getModifiers()) && (f.getType() == double.class || f.getType() == float.class)) {
                field = f;
            }
        } catch (Throwable ignored) {
        }
        if (key == null) {
            missing = CLASS + " has no KeyMapping (\"" + KEY_NAME + "\" or a public static field)";
        } else if (setter == null && field == null) {
            missing = CLASS + " has no setZoomFactor(double) or static zoomFactor field";
        }
    }

    /** Drives the zoom easing from the lockstep frame clock (true) or restores wall-clock time (false). */
    static void bindClock(boolean lockstep) {
        if (!available()) {
            return;
        }
        try {
            Field f = Class.forName(CLASS, true, Minecraft.class.getClassLoader()).getField("clockNanos");
            f.set(null, lockstep ? (java.util.function.LongSupplier) Lockstep::nanos : (java.util.function.LongSupplier) System::nanoTime);
        } catch (Throwable t) {
            throw new IllegalStateException("zoom clock not settable on " + CLASS, t);
        }
    }

    static KeyMapping key() {
        return available() ? key : null;
    }

    /** Current magnification, or null when it cannot be read (then it is not restored). */
    static Double factor() {
        if (!available()) {
            return null;
        }
        try {
            if (getter != null) {
                return ((Number) getter.invoke(null)).doubleValue();
            }
            if (field != null) {
                return ((Number) field.get(null)).doubleValue();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    static void setFactor(double factor) {
        if (!available()) {
            return;
        }
        try {
            if (setter != null) {
                setter.invoke(null, setter.getParameterTypes()[0] == float.class ? (Object) (float) factor : (Object) factor);
            } else if (field.getType() == float.class) {
                field.setFloat(null, (float) factor);
            } else {
                field.setDouble(null, factor);
            }
        } catch (Throwable t) {
            throw new IllegalStateException("zoom factor not settable on " + CLASS, t);
        }
    }

    /**
     * Holds or releases a key like a player would. Re-applied every frame, because opening or closing a screen and
     * focus changes reset key states. Works for toggle-mode keys too (there setDown(true) flips the state).
     */
    static void hold(KeyMapping k, boolean down) {
        if (k == null) {
            return;
        }
        if (k.isDown() != down) {
            k.setDown(down);
        }
        if (k.isDown() != down) {
            k.setDown(!down);
        }
    }
}
